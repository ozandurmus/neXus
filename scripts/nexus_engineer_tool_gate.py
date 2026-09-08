#!/usr/bin/env python3
"""GOV.PO.3 PreToolUse/PostToolUse gate for an orchestrated engineer session
(docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md, FROZEN, section 3.4;
Addition A of the freeze decision on relay/NXS-LOCAL-0007).

Usage (as a Claude Code hook, from .claude/nexus-engineer.settings.json):
    python3 scripts/nexus_engineer_tool_gate.py --event pre
    python3 scripts/nexus_engineer_tool_gate.py --event post

Unlike scripts/nexus_po_tool_gate.py (default-deny, a narrow allowlist),
this gate is **default-allow**: an orchestrated engineer session has
"normal dev tools: Read/Edit/Write/Bash/Git" (the FROZEN amendment's own
words), exactly as an interactive engineer session has today. Exactly two
command shapes are checked, at the code-publish / artifact-egress boundary
(AC-7) and the cross-movement integration boundary (section 3.8):

  git push* / gh pr create*  -- deny unless the repository privacy gate
                                 (`utils.repository_privacy.scan_repository`,
                                 the same function `main.py
                                 --repository-privacy-check` calls) reports
                                 no *new* findings relative to this
                                 movement's own base commit. Not a new
                                 mandatory scan: the identical check already
                                 runs in CI on every PR (GOV_PO_2 section
                                 3.1); this runs it locally, before push, so
                                 an unattended session fails fast on its own
                                 mistake. It is baseline-aware (see
                                 `_privacy_check` below) -- a finding already
                                 present at the movement's own base commit is
                                 pre-existing repository debt and never
                                 blocks; only a finding absent from that
                                 baseline is new and blocks, exactly as
                                 before (relay/NXS-LOCAL-0012 seq 3-4: the
                                 live incident this closes).

  gh pr merge*                -- acquire the cross-movement merge lock
                                 (scripts/orchestrator.py merge-lock),
                                 merge origin/main into the movement branch,
                                 re-run tests/test_architecture_convergence.py,
                                 scripts/build_history_index.py --check, and
                                 the movement's own targeted tests (read
                                 from .nexus/approved_task.json's
                                 report.validation_plan), and only then
                                 allow the actual merge -- Addition A of the
                                 relay/NXS-LOCAL-0007 freeze decision, closing
                                 the "re-validate against an advanced main"
                                 gap the FROZEN amendment itself named as a
                                 residual risk (section 3.8/9). Any failure
                                 denies the merge and releases the lock
                                 immediately, so one movement's own failure
                                 never blocks another's integration.

  git push --force*/-f*       -- denied unconditionally (the one deny this
                                 profile shares with the PO profile -- a
                                 general stance on force-push, not
                                 orchestration-specific reasoning).

Everything else -- arbitrary Read/Edit/Write/Bash/git add|commit|branch|...
-- is unrestricted. No device, deployment, or collection capability is
added or changed: the existing network-device command gate and real-device
approval boundary in docs/AI_DEVELOPMENT_PROTOCOL.md are untouched by this
profile.

The `post` event releases the merge lock after a `gh pr merge*` call
completes (success or failure), best-effort; a crash before this fires is
covered by the lock's own TTL-based staleness reclaim
(scripts/orchestrator.py::decide_merge_lock_acquire), not by this hook.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ORCHESTRATOR = REPO_ROOT / "scripts" / "orchestrator.py"

sys.path.insert(0, str(REPO_ROOT))
from utils.repository_privacy import (  # noqa: E402
    PrivacyFinding,
    RepositoryPrivacyError,
    scan_repository,
)

VALIDATION_COMMANDS = (
    [sys.executable, "-m", "pytest", "tests/test_architecture_convergence.py", "-q"],
    [sys.executable, "scripts/build_history_index.py", "--check"],
)


def _movement_id(cwd: str) -> str | None:
    marker = Path(cwd) / ".nexus" / "movement_id.txt"
    if not marker.is_file():
        return None
    return marker.read_text(encoding="utf-8").strip() or None


def _run(argv: list[str], cwd: str, timeout: int = 600) -> subprocess.CompletedProcess:
    return subprocess.run(argv, cwd=cwd, capture_output=True, text=True, timeout=timeout)


def _approved_task_git_base(cwd: str) -> str | None:
    path = Path(cwd) / ".nexus" / "approved_task.json"
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    base = data.get("report", {}).get("git", {}).get("base")
    return base if isinstance(base, str) and base else None


def _finding_fingerprint(root: Path, finding: PrivacyFinding) -> str:
    # AC-3: match on (file, finding_type, a stable content fingerprint of the
    # flagged span), not (file, exact line number) -- a pre-existing finding
    # whose line number shifted because of unrelated earlier lines in the
    # same file, without the flagged content itself changing, must still
    # count as pre-existing. File-level findings (finding.line == 0, e.g.
    # RUNTIME_DIRECTORY_PRESENT) have no line span; (path, rule) alone is
    # already stable for those. The hash is never printed -- only compared
    # in-process -- consistent with "matched values are never returned or
    # printed".
    if finding.line <= 0:
        return ""
    try:
        lines = (root / finding.path).read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        return ""
    if not (1 <= finding.line <= len(lines)):
        return ""
    return hashlib.sha256(lines[finding.line - 1].strip().encode("utf-8")).hexdigest()


def _finding_key(root: Path, finding: PrivacyFinding) -> tuple[str, str, str]:
    return (finding.path, finding.rule, _finding_fingerprint(root, finding))


def _format_finding(finding: PrivacyFinding) -> str:
    location = f"{finding.path}:{finding.line}" if finding.line else finding.path
    return f"{location} {finding.rule}"


def _export_ref_to_tempdir(ref_sha: str, cwd: str) -> Path:
    tmp_dir = Path(tempfile.mkdtemp(prefix="nexus-privacy-baseline-"))
    archive = subprocess.run(
        ["git", "archive", ref_sha], cwd=cwd, capture_output=True, timeout=120,
    )
    if archive.returncode != 0:
        shutil.rmtree(tmp_dir, ignore_errors=True)
        raise RepositoryPrivacyError(
            f"git archive {ref_sha} failed: {archive.stderr.decode(errors='replace').strip()}"
        )
    with tarfile.open(fileobj=io.BytesIO(archive.stdout)) as tar:
        tar.extractall(tmp_dir, filter="data")
    return tmp_dir


def _baseline_finding_keys(cwd: str) -> tuple[frozenset[tuple[str, str, str]], str]:
    """Scan the movement's own base commit once and return its finding keys.

    AC-5: this is the *only* place a second scan happens, and the caller
    only invokes it when the post-changes scan already found something --
    never once per file, never on the common zero-findings path.
    """
    base_ref = _approved_task_git_base(cwd)
    if not base_ref:
        return frozenset(), "baseline unavailable (no .nexus/approved_task.json report.git.base)"
    merge_base = _run(["git", "merge-base", "HEAD", base_ref], cwd)
    if merge_base.returncode != 0:
        return frozenset(), f"baseline unavailable (git merge-base HEAD {base_ref} failed: {merge_base.stderr.strip()})"
    base_sha = merge_base.stdout.strip()
    try:
        baseline_root = _export_ref_to_tempdir(base_sha, cwd)
    except RepositoryPrivacyError as exc:
        return frozenset(), f"baseline unavailable ({exc})"
    try:
        try:
            baseline_report = scan_repository(baseline_root)
        except RepositoryPrivacyError as exc:
            return frozenset(), f"baseline scan of {base_sha[:12]} failed ({exc})"
        keys = frozenset(_finding_key(baseline_root, f) for f in baseline_report.findings)
        return keys, f"baseline={base_sha[:12]}"
    finally:
        shutil.rmtree(baseline_root, ignore_errors=True)


def _privacy_check(cwd: str) -> tuple[bool, str]:
    root = Path(cwd)
    try:
        current = scan_repository(root)
    except RepositoryPrivacyError as exc:
        return False, f"repository privacy gate: ERROR ({exc})"

    if not current.findings:
        return True, "repository privacy gate: PASS (0 findings)"

    # AC-5: baseline scan only runs when the post-changes scan is non-empty.
    baseline_keys, baseline_note = _baseline_finding_keys(cwd)
    new_findings = [f for f in current.findings if _finding_key(root, f) not in baseline_keys]

    if not new_findings:
        return True, (
            f"repository privacy gate: PASS ({len(current.findings)} finding(s), "
            f"all pre-existing, {baseline_note})"
        )

    # AC-4: name exactly the new findings, not the pre-existing ones alongside them.
    named = "; ".join(_format_finding(f) for f in new_findings)
    return False, (
        f"repository privacy gate: {len(new_findings)} new finding(s) not present in "
        f"the movement's base commit ({baseline_note}): {named}"
    )


def _merge_lock(action: str, movement_id: str, cwd: str) -> tuple[bool, str]:
    argv = [sys.executable, str(ORCHESTRATOR), "merge-lock", action, "--movement", movement_id,
            "--pid", str(os.getpid())]
    result = _run(argv, cwd)
    try:
        payload = json.loads(result.stdout.strip() or "{}")
    except json.JSONDecodeError:
        payload = {}
    if action == "acquire":
        return bool(payload.get("acquired")), payload.get("reason", result.stderr.strip())
    return bool(payload.get("released")), payload.get("reason", result.stderr.strip())


def _integration_check(movement_id: str, cwd: str) -> tuple[bool, str]:
    # NOTE: `report.validation_plan` (GOV.SESSION.1 schema) is free-text
    # English prose ("run the focused suite"), not a machine-executable
    # command list -- there is no general way to run "the movement's own
    # targeted tests" from it mechanically. VALIDATION_COMMANDS above is
    # the mechanically-enforced floor Addition A actually specifies
    # (tests/test_architecture_convergence.py + build_history_index.py
    # --check); a movement's own targeted tests remain the engineer
    # session's own pre-merge responsibility, unchanged and unenforced by
    # this gate.
    fetch = _run(["git", "fetch", "origin"], cwd)
    if fetch.returncode != 0:
        return False, f"git fetch origin failed: {fetch.stderr.strip()}"
    merge = _run(["git", "merge", "origin/main"], cwd)
    if merge.returncode != 0:
        return False, f"git merge origin/main failed (resolve conflicts and retry): {merge.stderr.strip()}"
    for argv in VALIDATION_COMMANDS:
        result = _run(argv, cwd)
        if result.returncode != 0:
            tail = (result.stdout + result.stderr).strip()[-2000:]
            return False, f"{' '.join(argv)} failed after merging origin/main: {tail}"
    return True, "origin/main merged; convergence and build-history checks green"


def _deny(reason: str) -> int:
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": f"nexus-engineer gate: {reason}",
    }}))
    print(f"nexus-engineer gate denied: {reason}", file=sys.stderr)
    return 2


def _allow(reason: str) -> int:
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "allow",
        "permissionDecisionReason": f"nexus-engineer gate: {reason}",
    }}))
    return 0


def handle_pre(payload: dict) -> int:
    tool = str(payload.get("tool_name", ""))
    tool_input = payload.get("tool_input") or {}
    cwd = payload.get("cwd") or str(Path.cwd())
    if tool != "Bash":
        return _allow("non-Bash tool, unrestricted")
    cmd = " ".join(str(tool_input.get("command", "")).split())

    if cmd.startswith("git push") and ("--force" in cmd.split() or cmd.startswith("git push -f")):
        return _deny("force push is never allowed")

    if cmd.startswith("git push") or cmd.startswith("gh pr create"):
        ok, reason = _privacy_check(cwd)
        return _allow(reason) if ok else _deny(reason)

    if cmd.startswith("gh pr merge"):
        movement_id = _movement_id(cwd)
        if not movement_id:
            return _deny("no .nexus/movement_id.txt in cwd; cannot serialize this merge -- refusing to guess")
        acquired, reason = _merge_lock("acquire", movement_id, cwd)
        if not acquired:
            return _deny(f"merge lock not acquired: {reason}")
        ok, check_reason = _integration_check(movement_id, cwd)
        if not ok:
            _merge_lock("release", movement_id, cwd)
            return _deny(check_reason)
        return _allow(f"merge lock held ({reason}); {check_reason}")

    return _allow("unrestricted (normal dev tools)")


def handle_post(payload: dict) -> int:
    tool = str(payload.get("tool_name", ""))
    tool_input = payload.get("tool_input") or {}
    cwd = payload.get("cwd") or str(Path.cwd())
    if tool != "Bash":
        return 0
    cmd = " ".join(str(tool_input.get("command", "")).split())
    if cmd.startswith("gh pr merge"):
        movement_id = _movement_id(cwd)
        if movement_id:
            _merge_lock("release", movement_id, cwd)
    return 0


def main(argv: list[str] | None = None) -> int:
    import argparse
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--event", choices=("pre", "post"), required=True)
    args = parser.parse_args(argv)
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError):
        payload = {}
    if args.event == "pre":
        return handle_pre(payload)
    return handle_post(payload)


if __name__ == "__main__":
    sys.exit(main())
