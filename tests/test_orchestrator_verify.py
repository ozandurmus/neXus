"""GOV.ORCH.1 -- tests for scripts/orchestrator_verify.py (docs/design/
GOV_ORCH_1_SYNCHRONOUS_RUN_AND_ORCHESTRATOR_VERIFY.md, section 2.2).

Covers the orchestrator-side verification sequence run against a real git
worktree: the uncommitted-changes gate (AC-5), object-form validation_plan
execution with shell=False (AC-4), string entries recorded skipped_prose,
and the shared privacy-gate call this module now hosts (AC-8, exercised
directly here; scripts/nexus_engineer_tool_gate.py's own reuse of it is
covered by tests/test_nexus_engineer_tool_gate.py).
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import orchestrator_verify as ov  # noqa: E402

pytestmark = pytest.mark.runtime_platform


def _git(repo: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=repo, capture_output=True, text=True, check=True)


def _init_repo(repo: Path) -> None:
    _git(repo, "init", "-q", "-b", "main")
    _git(repo, "config", "user.email", "test@example.com")
    _git(repo, "config", "user.name", "Test")


def _write(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _commit_all(repo: Path, message: str) -> None:
    _git(repo, "add", "-A")
    _git(repo, "commit", "-q", "-m", message)


CREDENTIAL_LINE = 'api_key = "hunter2-synthetic"\n'


# --- privacy_check (shared with nexus_engineer_tool_gate.py, AC-8) --------

def test_privacy_check_pass_with_zero_findings(tmp_path):
    _init_repo(tmp_path)
    _write(tmp_path, "README.md", "nothing sensitive here\n")
    _commit_all(tmp_path, "base")
    ok, reason = ov.privacy_check(tmp_path, "main")
    assert ok, reason
    assert "0 findings" in reason


def test_privacy_check_pre_existing_finding_passes(tmp_path):
    _init_repo(tmp_path)
    _write(tmp_path, "docs/notes.md", "intro\n" + CREDENTIAL_LINE)
    _commit_all(tmp_path, "base")
    ok, reason = ov.privacy_check(tmp_path, "main")
    assert ok, reason
    assert "pre-existing" in reason


def test_privacy_check_new_finding_fails(tmp_path):
    _init_repo(tmp_path)
    _write(tmp_path, "docs/notes.md", "intro\n")
    _commit_all(tmp_path, "base")
    _write(tmp_path, "docs/other.md", CREDENTIAL_LINE)
    ok, reason = ov.privacy_check(tmp_path, "main")
    assert not ok
    assert "docs/other.md" in reason


# --- uncommitted-changes step (AC-5) ---------------------------------------

def test_uncommitted_changes_step_clean_worktree(tmp_path):
    _init_repo(tmp_path)
    _write(tmp_path, "README.md", "hello\n")
    _commit_all(tmp_path, "base")
    step = ov._uncommitted_changes_step(tmp_path)
    assert step["exit_code"] == 0
    assert step["name"] == "uncommitted_changes"


def test_uncommitted_changes_step_dirty_worktree_fails(tmp_path):
    _init_repo(tmp_path)
    _write(tmp_path, "README.md", "hello\n")
    _commit_all(tmp_path, "base")
    _write(tmp_path, "README.md", "changed\n")
    step = ov._uncommitted_changes_step(tmp_path)
    assert step["exit_code"] != 0


# --- validation_plan entries (AC-4) ----------------------------------------

def test_validation_plan_string_entry_is_skipped_prose():
    step = ov._validation_plan_step(0, "run the focused suite", Path("."))
    assert step["status"] == "skipped_prose"
    assert step["exit_code"] is None
    assert step["argv"] is None


def test_validation_plan_object_entry_runs_argv_with_shell_false(tmp_path, monkeypatch):
    calls = []
    real_run = subprocess.run

    def spy_run(argv, **kwargs):
        calls.append((argv, kwargs.get("shell", False)))
        return real_run(argv, **kwargs)

    monkeypatch.setattr(ov.subprocess, "run", spy_run)
    step = ov._validation_plan_step(0, {"name": "true-check", "argv": [sys.executable, "-c", "pass"]}, tmp_path)
    assert step["exit_code"] == 0
    assert calls and calls[0][0] == [sys.executable, "-c", "pass"]
    assert calls[0][1] is False


def test_validation_plan_object_entry_nonzero_exit_recorded():
    step = ov._validation_plan_step(0, {"argv": [sys.executable, "-c", "import sys; sys.exit(3)"]}, Path("."))
    assert step["exit_code"] == 3


# --- verify_movement: the full sequence ------------------------------------

def _base_repo(tmp_path: Path) -> Path:
    repo = tmp_path / "repo"
    repo.mkdir()
    _init_repo(repo)
    _write(repo, "README.md", "hello\n")
    _commit_all(repo, "base")
    return repo


def test_verify_movement_passes_when_clean_and_plan_green(tmp_path):
    repo = _base_repo(tmp_path)
    result = ov.verify_movement(
        worktree_path=repo,
        validation_plan=["prose entry", {"argv": [sys.executable, "-c", "pass"]}],
        base_ref="main",
    )
    assert result["passed"] is True
    names = [s["name"] for s in result["steps"]]
    assert "uncommitted_changes" in names
    assert any(s.get("status") == "skipped_prose" for s in result["steps"])


def test_verify_movement_fails_on_uncommitted_changes(tmp_path):
    repo = _base_repo(tmp_path)
    _write(repo, "README.md", "dirty\n")
    result = ov.verify_movement(worktree_path=repo, validation_plan=["prose only"], base_ref="main")
    assert result["passed"] is False
    uncommitted = next(s for s in result["steps"] if s["name"] == "uncommitted_changes")
    assert uncommitted["exit_code"] != 0


def test_verify_movement_fails_when_a_validation_plan_command_fails(tmp_path):
    repo = _base_repo(tmp_path)
    result = ov.verify_movement(
        worktree_path=repo,
        validation_plan=[{"argv": [sys.executable, "-c", "import sys; sys.exit(1)"]}],
        base_ref="main",
    )
    assert result["passed"] is False


def test_verify_movement_string_entries_never_fail_verification(tmp_path):
    repo = _base_repo(tmp_path)
    result = ov.verify_movement(worktree_path=repo, validation_plan=["run the focused suite"], base_ref="main")
    assert result["passed"] is True


# --- integrate (GOV.ORCH.2 section 2.5, moved from
# --- nexus_engineer_tool_gate.py::_integration_check) ----------------------

def test_integrate_fails_when_fetch_has_no_origin_remote(tmp_path):
    repo = _base_repo(tmp_path)  # no "origin" remote configured at all
    ok, reason = ov.integrate(repo)
    assert ok is False
    assert "git fetch origin failed" in reason


def test_integrate_merges_origin_main_and_runs_the_convergence_check(tmp_path):
    bare = tmp_path / "origin.git"
    subprocess.run(["git", "init", "-q", "--bare", str(bare)], check=True)
    work = tmp_path / "work"
    subprocess.run(["git", "clone", "-q", str(bare), str(work)], check=True)
    _git(work, "config", "user.email", "t@example.com")
    _git(work, "config", "user.name", "t")
    _write(work, "README.md", "hello\n")
    _commit_all(work, "init")
    subprocess.run(["git", "push", "-q", "origin", "HEAD:main"], cwd=work, check=True)

    # A second clone of the SAME bare repo, on the same branch it just
    # pushed, diverges by one further commit on origin/main -- integrate()
    # must fetch and merge that into `work` before validating.
    other = tmp_path / "other"
    subprocess.run(["git", "clone", "-q", "-b", "main", str(bare), str(other)], check=True)
    _git(other, "config", "user.email", "t2@example.com")
    _git(other, "config", "user.name", "t2")
    _write(other, "UPSTREAM.md", "new upstream file\n")
    _commit_all(other, "upstream change")
    subprocess.run(["git", "push", "-q", "origin", "HEAD:main"], cwd=other, check=True)

    # This synthetic repo has neither tests/test_architecture_convergence.py
    # nor scripts/build_history_index.py -- integrate() still attempts to
    # run them (exercising the merge step, not the real repo's own
    # convergence suite), so the missing-file check step reports the
    # expected failure rather than raising.
    ok, reason = ov.integrate(work)
    assert (work / "UPSTREAM.md").is_file()  # the merge itself did happen -- the assertion this test is really for
    assert ok is False
    assert "test_architecture_convergence.py" in reason
