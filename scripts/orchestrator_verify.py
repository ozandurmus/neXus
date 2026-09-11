"""GOV.ORCH.1 -- orchestrator-side verification and the shared privacy-gate
call (docs/design/GOV_ORCH_1_SYNCHRONOUS_RUN_AND_ORCHESTRATOR_VERIFY.md,
section 2.2). Two callers share this module unchanged in behavior:

  * `scripts/orchestrator.py` (`run` and `verify` subcommands) -- runs the
    full verification sequence against a finished movement worktree.
  * `scripts/nexus_engineer_tool_gate.py` (`_privacy_check`) -- the engineer
    session's own pre-push privacy gate, moved here so both callers use one
    implementation (`privacy_check` below); its behaviour is unchanged from
    before the move.

Every subprocess here is an argv list run with `shell=False`.
"""
from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT))
from utils.repository_privacy import (  # noqa: E402
    PrivacyFinding,
    RepositoryPrivacyError,
    scan_repository,
)
from utils.repository_privacy import baseline_finding_keys  # noqa: E402
from utils.repository_privacy import finding_key  # noqa: E402

#: Section 2.2: "the last 40 lines of combined output".
TAIL_LINES = 40
DEFAULT_STEP_TIMEOUT = 1800


def _format_finding(finding: PrivacyFinding) -> str:
    location = f"{finding.path}:{finding.line}" if finding.line else finding.path
    return f"{location} {finding.rule}"


def privacy_check(cwd: str | Path, base_ref: str | None) -> tuple[bool, str]:
    """Baseline-aware repository privacy gate -- identical behaviour to the
    function this replaces (`nexus_engineer_tool_gate.py::_privacy_check`,
    pre-GOV.ORCH.1): a finding already present at `base_ref` is pre-existing
    repository debt and never blocks; only a finding absent from that
    baseline is new and fails the gate."""
    root = Path(cwd)
    try:
        current = scan_repository(root)
    except RepositoryPrivacyError as exc:
        return False, f"repository privacy gate: ERROR ({exc})"

    if not current.findings:
        return True, "repository privacy gate: PASS (0 findings)"

    baseline_keys, baseline_note = baseline_finding_keys(cwd, base_ref)
    new_findings = [f for f in current.findings if finding_key(root, f) not in baseline_keys]

    if not new_findings:
        return True, (
            f"repository privacy gate: PASS ({len(current.findings)} finding(s), "
            f"all pre-existing, {baseline_note})"
        )

    named = "; ".join(_format_finding(f) for f in new_findings)
    return False, (
        f"repository privacy gate: {len(new_findings)} new finding(s) not present in "
        f"the movement's base commit ({baseline_note}): {named}"
    )


def _resolve_interpreter(cwd: str) -> str:
    """A worktree checkout has no `.venv` of its own -- every worktree
    shares the one `.venv` next to the main checkout -- so `sys.executable`
    here would resolve to whatever `python3` the *calling* process happened
    to be launched with, not necessarily the interpreter with pytest
    installed (relay/NXS-LOCAL-0016 seq 2, the live incident this closes;
    moved here unchanged from `nexus_engineer_tool_gate.py` as part of
    `integrate`'s own move, GOV.ORCH.2 section 2.5). Locate the shared
    `.venv` via `git rev-parse --git-common-dir`, stable across every
    worktree, and fall back to `sys.executable` if none is found."""
    result = subprocess.run(["git", "rev-parse", "--git-common-dir"], cwd=cwd, capture_output=True, text=True, timeout=30)
    if result.returncode != 0:
        return sys.executable
    common_dir = Path(result.stdout.strip())
    if not common_dir.is_absolute():
        common_dir = (Path(cwd) / common_dir).resolve()
    venv_root = common_dir.parent
    for candidate in (
        venv_root / ".venv" / "bin" / "python3",
        venv_root / ".venv" / "Scripts" / "python.exe",
    ):
        if candidate.is_file():
            return str(candidate)
    return sys.executable


def integrate(cwd: str | Path) -> tuple[bool, str]:
    """GOV.ORCH.2 section 2.5: the `git fetch origin` + `git merge
    origin/main` + convergence/build-history validation sequence, moved
    here from `nexus_engineer_tool_gate.py::_integration_check` (GOV.ORCH.1)
    so both the PreToolUse hook (which still acquires/releases the
    cross-movement merge lock around this call itself) and
    `orchestrator.py`'s own `run` (merge-mode orchestrator, GOV.ORCH.2
    section 2.5) share one implementation. Behaviour is unchanged from the
    function this replaces; merge-lock acquisition is not part of this
    function -- each caller owns that around its own call, exactly as the
    hook already did."""
    cwd = str(cwd)
    fetch = subprocess.run(["git", "fetch", "origin"], cwd=cwd, capture_output=True, text=True, timeout=DEFAULT_STEP_TIMEOUT)
    if fetch.returncode != 0:
        return False, f"git fetch origin failed: {fetch.stderr.strip()}"
    merge = subprocess.run(["git", "merge", "origin/main"], cwd=cwd, capture_output=True, text=True, timeout=DEFAULT_STEP_TIMEOUT)
    if merge.returncode != 0:
        return False, f"git merge origin/main failed (resolve conflicts and retry): {merge.stderr.strip()}"
    python = _resolve_interpreter(cwd)
    for argv in (
        [python, "-m", "pytest", "tests/test_architecture_convergence.py", "-q"],
        [python, "scripts/build_history_index.py", "--check"],
    ):
        result = subprocess.run(argv, cwd=cwd, capture_output=True, text=True, timeout=DEFAULT_STEP_TIMEOUT)
        if result.returncode != 0:
            tail = (result.stdout + result.stderr).strip()[-2000:]
            return False, f"{' '.join(argv)} failed after merging origin/main: {tail}"
    return True, "origin/main merged; convergence and build-history checks green"


def _tail(text: str, lines: int = TAIL_LINES) -> str:
    return "\n".join(text.splitlines()[-lines:])


def _run_argv_step(name: str, argv: list[str], cwd: Path, timeout: int = DEFAULT_STEP_TIMEOUT) -> dict[str, Any]:
    """Run one argv list with shell=False and record the §2.2 step shape."""
    started = time.monotonic()
    try:
        result = subprocess.run(
            argv, cwd=str(cwd), capture_output=True, text=True, timeout=timeout,
        )
        exit_code = result.returncode
        output = (result.stdout or "") + (result.stderr or "")
    except subprocess.TimeoutExpired as exc:
        exit_code = None
        out = exc.stdout if isinstance(exc.stdout, str) else (exc.stdout or b"").decode("utf-8", "replace")
        err = exc.stderr if isinstance(exc.stderr, str) else (exc.stderr or b"").decode("utf-8", "replace")
        output = out + err
    except OSError as exc:
        exit_code = None
        output = str(exc)
    duration = time.monotonic() - started
    return {
        "name": name, "argv": argv, "exit_code": exit_code,
        "duration_s": round(duration, 3), "tail": _tail(output),
    }


def _uncommitted_changes_step(worktree_path: Path) -> dict[str, Any]:
    """§2.2 step 1: `git status --porcelain` must be empty."""
    argv = ["git", "status", "--porcelain"]
    started = time.monotonic()
    result = subprocess.run(argv, cwd=str(worktree_path), capture_output=True, text=True, timeout=30)
    duration = time.monotonic() - started
    porcelain = result.stdout
    clean = result.returncode == 0 and not porcelain.strip()
    return {
        "name": "uncommitted_changes", "argv": argv, "exit_code": 0 if clean else 1,
        "duration_s": round(duration, 3), "tail": _tail(porcelain),
    }


def _validation_plan_step(index: int, entry: Any, worktree_path: Path) -> dict[str, Any]:
    name = f"validation_plan[{index}]"
    if isinstance(entry, str):
        # §2.2 step 2: a prose entry is recorded skipped_prose and never
        # fails verification -- keeps every pre-GOV.ORCH.1 relay file
        # working unchanged.
        return {
            "name": name, "argv": None, "exit_code": None,
            "duration_s": 0.0, "tail": "skipped_prose", "status": "skipped_prose",
        }
    step_name = entry.get("name") or name
    argv = entry.get("argv")
    step = _run_argv_step(step_name, list(argv), worktree_path)
    step["name"] = name if not entry.get("name") else f"{name} ({entry['name']})"
    return step


def _privacy_gate_step(worktree_path: Path, base_ref: str | None) -> dict[str, Any]:
    started = time.monotonic()
    ok, reason = privacy_check(worktree_path, base_ref)
    duration = time.monotonic() - started
    return {
        "name": "privacy_gate", "argv": None, "exit_code": 0 if ok else 1,
        "duration_s": round(duration, 3), "tail": reason,
    }


def _step_passed(step: dict[str, Any]) -> bool:
    if step.get("status") == "skipped_prose":
        return True
    return step.get("exit_code") == 0


def verify_movement(
    *, worktree_path: Path, validation_plan: list[Any], base_ref: str | None,
) -> dict[str, Any]:
    """§2.2's full orchestrator-side verification sequence, run in
    `worktree_path`. Returns the §2.4 `verify` object: `{"passed": bool,
    "steps": [...]}`."""
    steps = [_uncommitted_changes_step(worktree_path)]
    for i, entry in enumerate(validation_plan or []):
        steps.append(_validation_plan_step(i, entry, worktree_path))
    steps.append(_run_argv_step(
        "git_diff_check", ["git", "diff", "--check", base_ref] if base_ref else ["git", "diff", "--check"],
        worktree_path,
    ))
    steps.append(_privacy_gate_step(worktree_path, base_ref))
    passed = all(_step_passed(step) for step in steps)
    return {"passed": passed, "steps": steps}
