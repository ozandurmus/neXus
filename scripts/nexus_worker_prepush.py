"""GOV.ORCH.3 Part B -- git `pre-push` hook body.

Installed per-worktree by `scripts/orchestrator.py` at dispatch (never in
the main checkout): `git config --worktree core.hooksPath
<worktree>/.nexus/hooks` plus a thin executable `.nexus/hooks/pre-push`
wrapper that invokes `python3 <this script>`. This script depends on no
AI-tool environment variable -- it reads only git's own pre-push protocol
(argv + stdin), repository state via `git`, and
`.nexus/approved_task.json` / a record path passed at install time.

Git's pre-push hook protocol: argv[1] is the remote name, argv[2] the
remote URL; stdin carries one line per ref being pushed:
``<local ref> <local sha1> <remote ref> <remote sha1>``. A non-zero exit
denies the push (git prints this hook's stderr to the user).
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "scripts"))

ZERO_SHA = "0" * 40

#: A neutral marker an installer/wrapper may set to deny a push outright
#: (e.g. the caller itself is about to run `git push --force`). This names
#: no AI tool or vendor -- it is a plain git-workflow marker.
FORCE_PUSH_MARKER_ENV = "NEXUS_GIT_FORCE_PUSH"


def _is_ancestor(sha: str, of: str, cwd: str) -> bool:
    """True iff `sha` is an ancestor of `of` (a fast-forward from `sha` to
    `of`). Any git-level error (unknown sha, not a repo) is treated as
    "not an ancestor" -- fail closed on force-push detection."""
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", sha, of],
        cwd=cwd, capture_output=True, text=True,
    )
    return result.returncode == 0


def _load_base_sha(worktree: Path, record_path: str | None) -> str | None:
    """`base_sha` comes from an explicit record path passed at install
    time (a JSON file carrying a `base_sha` field) when given, otherwise
    from `.nexus/approved_task.json`'s `report.git.base`."""
    if record_path:
        try:
            data = json.loads(Path(record_path).read_text(encoding="utf-8"))
            if data.get("base_sha"):
                return data["base_sha"]
        except (OSError, ValueError):
            pass
    approved = worktree / ".nexus" / "approved_task.json"
    try:
        data = json.loads(approved.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    git_cfg = (data.get("report") or {}).get("git") or {}
    return git_cfg.get("base")


def _current_branch(worktree: Path) -> str | None:
    result = subprocess.run(
        ["git", "rev-parse", "--abbrev-ref", "HEAD"],
        cwd=str(worktree), capture_output=True, text=True,
    )
    if result.returncode != 0:
        return None
    branch = result.stdout.strip()
    return branch or None


def check_push(
    *, stdin_lines: list[str], worktree: Path, force_marker: bool,
    record_path: str | None = None, privacy_check=None, po_scope_check=None,
) -> tuple[bool, str]:
    """The decision core, given already-read stdin lines and an explicit
    force marker -- kept separate from `main` so tests drive it directly
    with fixture input, never a real git subprocess for the ancestor
    check's own caller (the ancestor check itself still shells out to
    `git`, against a real temporary repo the tests construct)."""
    if force_marker:
        return False, "denied: force push (NEXUS_GIT_FORCE_PUSH marker set)"

    if po_scope_check is not None:
        branch = _current_branch(worktree)
        if branch is not None:
            ok, reason = po_scope_check(worktree, branch)
            if not ok:
                return False, f"denied: {reason}"

    for line in stdin_lines:
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 4:
            continue
        local_ref, local_sha, remote_ref, remote_sha = parts
        if local_sha == ZERO_SHA:
            continue  # deleting a ref: nothing to check for non-fast-forward
        if remote_sha == ZERO_SHA:
            continue  # brand-new ref on the remote: nothing to be ahead of
        if not _is_ancestor(remote_sha, local_sha, str(worktree)):
            return False, (
                f"denied: non-fast-forward update of {remote_ref} "
                f"({remote_sha[:8]}..{local_sha[:8]})"
            )

    if privacy_check is not None:
        base_sha = _load_base_sha(worktree, record_path)
        ok, reason = privacy_check(worktree, base_sha)
        if not ok:
            return False, f"denied: {reason}"

    return True, "ok"


def _po_scope_check(worktree: Path, branch: str) -> tuple[bool, str]:
    """GOV.ORCH.7 section 2.3 item 3: the same `nexus_po_scope_check.py`
    check CI runs, applied here as a pre-push gate when the current branch
    matches the configured `gov/po-*` prefix -- a branch that doesn't
    match is out of scope and always passes (see
    `nexus_po_scope_check.check_scope`). Base is always the configured
    remote main branch: a PO governance branch's own base is always
    `origin/main`, never a movement's own `report.git.base`."""
    import nexus_po_scope_check as scope_check  # noqa: E402 (flat import)

    try:
        config = scope_check.load_scope_config()
        if not branch.startswith(config["branch_prefix"]):
            return True, "not a PO branch, skipped"
        files = scope_check.changed_files(branch, "origin/main", cwd=worktree)
    except scope_check.ScopeCheckError as exc:
        return False, f"PO scope check could not run: {exc}"
    return scope_check.check_scope(branch=branch, files=files, config=config)


def main(argv: list[str]) -> int:
    worktree = Path.cwd()
    record_path = argv[1] if len(argv) > 1 else None
    stdin_lines = sys.stdin.read().splitlines()
    force_marker = os.environ.get(FORCE_PUSH_MARKER_ENV) == "1"

    import orchestrator_verify as ov  # noqa: E402  (flat import, see sys.path above)

    ok, reason = check_push(
        stdin_lines=stdin_lines, worktree=worktree, force_marker=force_marker,
        record_path=record_path, privacy_check=ov.privacy_check,
        po_scope_check=_po_scope_check,
    )
    if not ok:
        print(reason, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
