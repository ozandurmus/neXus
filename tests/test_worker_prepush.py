"""GOV.ORCH.3 Part B -- scripts/nexus_worker_prepush.py (the pre-push hook
body) and scripts/orchestrator.py::install_prepush_hook (the installer).
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import nexus_worker_prepush as prepush  # noqa: E402
import orchestrator as orch  # noqa: E402

ZERO = prepush.ZERO_SHA


def _git(args, cwd):
    subprocess.run(["git", *args], cwd=str(cwd), check=True, capture_output=True, text=True)


def _init_repo(tmp_path: Path) -> Path:
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(["init", "-q"], repo)
    _git(["config", "user.email", "t@example.com"], repo)
    _git(["config", "user.name", "t"], repo)
    (repo / "f.txt").write_text("one\n", encoding="utf-8")
    _git(["add", "-A"], repo)
    _git(["commit", "-q", "-m", "init"], repo)
    return repo


def _sha(repo: Path, rev: str = "HEAD") -> str:
    return subprocess.run(["git", "rev-parse", rev], cwd=str(repo), capture_output=True, text=True, check=True).stdout.strip()


# --- check_push: force push denied ------------------------------------------

def test_check_push_denies_when_force_marker_set(tmp_path):
    repo = _init_repo(tmp_path)
    ok, reason = prepush.check_push(
        stdin_lines=[f"refs/heads/main {_sha(repo)} refs/heads/main {ZERO}"],
        worktree=repo, force_marker=True,
    )
    assert ok is False
    assert "force" in reason.lower()


# --- check_push: non-fast-forward denied ------------------------------------

def test_check_push_denies_non_fast_forward_update(tmp_path):
    repo = _init_repo(tmp_path)
    base_sha = _sha(repo)
    # A remote sha the local history doesn't contain -- simulates a rewritten
    # (force-pushed-shape) history without needing a second remote.
    (repo / "f.txt").write_text("two\n", encoding="utf-8")
    _git(["commit", "-aqm", "amend-like"], repo)
    local_sha = _sha(repo)
    fake_remote_sha = "1" * 40

    ok, reason = prepush.check_push(
        stdin_lines=[f"refs/heads/main {local_sha} refs/heads/main {fake_remote_sha}"],
        worktree=repo, force_marker=False,
    )
    assert ok is False
    assert "non-fast-forward" in reason


def test_check_push_allows_fast_forward_update(tmp_path):
    repo = _init_repo(tmp_path)
    remote_sha = _sha(repo)
    (repo / "f.txt").write_text("two\n", encoding="utf-8")
    _git(["commit", "-aqm", "ff"], repo)
    local_sha = _sha(repo)

    ok, reason = prepush.check_push(
        stdin_lines=[f"refs/heads/main {local_sha} refs/heads/main {remote_sha}"],
        worktree=repo, force_marker=False,
    )
    assert ok is True


def test_check_push_allows_a_brand_new_ref(tmp_path):
    repo = _init_repo(tmp_path)
    local_sha = _sha(repo)
    ok, reason = prepush.check_push(
        stdin_lines=[f"refs/heads/feature/x {local_sha} refs/heads/feature/x {ZERO}"],
        worktree=repo, force_marker=False,
    )
    assert ok is True


# --- check_push: new privacy findings denied --------------------------------

def test_check_push_denies_on_new_privacy_findings(tmp_path):
    repo = _init_repo(tmp_path)
    local_sha = _sha(repo)
    nexus = repo / ".nexus"
    nexus.mkdir()
    (nexus / "approved_task.json").write_text(
        json.dumps({"report": {"git": {"base": "origin/main"}}}), encoding="utf-8",
    )

    def fake_privacy_check(cwd, base_ref):
        assert str(cwd) == str(repo)
        assert base_ref == "origin/main"
        return False, "repository privacy gate: 1 new finding(s)"

    ok, reason = prepush.check_push(
        stdin_lines=[f"refs/heads/main {local_sha} refs/heads/main {ZERO}"],
        worktree=repo, force_marker=False, privacy_check=fake_privacy_check,
    )
    assert ok is False
    assert "privacy" in reason.lower()


def test_check_push_allows_a_clean_push_with_no_new_findings(tmp_path):
    repo = _init_repo(tmp_path)
    local_sha = _sha(repo)

    def fake_privacy_check(cwd, base_ref):
        return True, "repository privacy gate: PASS (0 findings)"

    ok, reason = prepush.check_push(
        stdin_lines=[f"refs/heads/main {local_sha} refs/heads/main {ZERO}"],
        worktree=repo, force_marker=False, privacy_check=fake_privacy_check,
    )
    assert ok is True


# --- base_sha resolution -----------------------------------------------------

def test_load_base_sha_prefers_explicit_record_path(tmp_path):
    repo = _init_repo(tmp_path)
    (repo / ".nexus").mkdir()
    (repo / ".nexus" / "approved_task.json").write_text(
        json.dumps({"report": {"git": {"base": "origin/from-approved-task"}}}), encoding="utf-8",
    )
    record = tmp_path / "record.json"
    record.write_text(json.dumps({"base_sha": "origin/from-record"}), encoding="utf-8")
    assert prepush._load_base_sha(repo, str(record)) == "origin/from-record"


def test_load_base_sha_falls_back_to_approved_task_json(tmp_path):
    repo = _init_repo(tmp_path)
    (repo / ".nexus").mkdir()
    (repo / ".nexus" / "approved_task.json").write_text(
        json.dumps({"report": {"git": {"base": "origin/from-approved-task"}}}), encoding="utf-8",
    )
    assert prepush._load_base_sha(repo, None) == "origin/from-approved-task"


# --- no dependency on any AI-tool environment variable ----------------------

def test_hook_script_names_no_ai_tool_environment_variable():
    text = (ROOT / "scripts" / "nexus_worker_prepush.py").read_text(encoding="utf-8")
    for banned in ("CLAUDE_", "ANTHROPIC_", "CODEX_", "OPENAI_", "COPILOT_"):
        assert banned not in text


# --- installer: hooksPath configured, hook executable -----------------------

def _init_bare_and_clone(tmp_path: Path) -> Path:
    bare = tmp_path / "origin.git"
    subprocess.run(["git", "init", "-q", "--bare", str(bare)], check=True)
    work = tmp_path / "work"
    subprocess.run(["git", "clone", "-q", str(bare), str(work)], check=True)
    _git(["config", "user.email", "t@example.com"], work)
    _git(["config", "user.name", "t"], work)
    (work / "README.md").write_text("hello\n", encoding="utf-8")
    _git(["add", "-A"], work)
    _git(["commit", "-q", "-m", "init"], work)
    subprocess.run(["git", "push", "-q", "origin", "HEAD:main"], cwd=str(work), check=True)
    subprocess.run(["git", "fetch", "-q", "origin"], cwd=str(work), check=True)
    return work


def test_install_prepush_hook_configures_hookspath_and_executable_hook(tmp_path):
    work = _init_bare_and_clone(tmp_path)
    worktree = tmp_path / "wt"
    subprocess.run(["git", "worktree", "add", str(worktree), "origin/main", "-b", "feature/x"],
                    cwd=str(work), check=True)

    hook_path = orch.install_prepush_hook(worktree)

    assert hook_path == worktree / ".nexus" / "hooks" / "pre-push"
    assert hook_path.is_file()
    assert hook_path.stat().st_mode & 0o111  # executable

    result = subprocess.run(["git", "config", "--worktree", "core.hooksPath"],
                             cwd=str(worktree), capture_output=True, text=True, check=True)
    assert result.stdout.strip() == str(worktree / ".nexus" / "hooks")
