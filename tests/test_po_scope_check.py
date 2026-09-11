"""GOV.ORCH.7 section 2.3 item 3 / AC-6 -- scripts/nexus_po_scope_check.py."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import nexus_po_scope_check as scope_check  # noqa: E402

_CONFIG = {
    "branch_prefix": "gov/po-",
    "allowed_paths": [
        "docs/design/PRODUCT_DIRECTION_RECORD.md",
        "project/roadmap.json",
    ],
}


def test_config_file_exists_and_loads():
    config = scope_check.load_scope_config()
    assert config["branch_prefix"] == "gov/po-"
    assert "project/roadmap.json" in config["allowed_paths"]


def test_non_po_branch_is_out_of_scope_and_always_ok():
    ok, reason = scope_check.check_scope(branch="feature/x", files=["utils/x.py"], config=_CONFIG)
    assert ok is True
    assert "skipped" in reason


def test_po_branch_touching_only_governance_paths_is_ok():
    ok, _reason = scope_check.check_scope(
        branch="gov/po-x", files=["project/roadmap.json"], config=_CONFIG,
    )
    assert ok is True


def test_po_branch_touching_a_utils_path_is_denied():
    ok, reason = scope_check.check_scope(
        branch="gov/po-x", files=["project/roadmap.json", "utils/x.py"], config=_CONFIG,
    )
    assert ok is False
    assert "utils/x.py" in reason


def _git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True, check=True)


def _init_repo_with_po_branch(tmp_path: Path, touch_out_of_scope: bool) -> Path:
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init", "-q")
    _git(repo, "config", "user.email", "test@example.com")
    _git(repo, "config", "user.name", "Test")
    (repo / "project").mkdir()
    (repo / "project" / "roadmap.json").write_text("{}\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-q", "-m", "base")
    _git(repo, "branch", "-m", "main")
    _git(repo, "checkout", "-q", "-b", "gov/po-x")
    (repo / "project" / "roadmap.json").write_text('{"x": 1}\n', encoding="utf-8")
    if touch_out_of_scope:
        (repo / "utils_x.py").write_text("x = 1\n", encoding="utf-8")
        _git(repo, "add", "utils_x.py")
    _git(repo, "add", "project/roadmap.json")
    _git(repo, "commit", "-q", "-m", "po change")
    return repo


def test_fixture_gov_po_branch_touching_utils_exits_1(tmp_path):
    repo = _init_repo_with_po_branch(tmp_path, touch_out_of_scope=True)
    files = scope_check.changed_files("gov/po-x", "main", cwd=repo)
    ok, _reason = scope_check.check_scope(
        branch="gov/po-x", files=files,
        config={"branch_prefix": "gov/po-", "allowed_paths": ["project/roadmap.json"]},
    )
    assert ok is False


def test_fixture_gov_po_branch_touching_only_governance_exits_0(tmp_path):
    repo = _init_repo_with_po_branch(tmp_path, touch_out_of_scope=False)
    files = scope_check.changed_files("gov/po-x", "main", cwd=repo)
    ok, _reason = scope_check.check_scope(
        branch="gov/po-x", files=files,
        config={"branch_prefix": "gov/po-", "allowed_paths": ["project/roadmap.json"]},
    )
    assert ok is True


def test_cli_main_end_to_end_denies_out_of_scope(tmp_path):
    repo = _init_repo_with_po_branch(tmp_path, touch_out_of_scope=True)
    config_path = tmp_path / "scope.json"
    config_path.write_text(
        '{"branch_prefix": "gov/po-", "allowed_paths": ["project/roadmap.json"]}',
        encoding="utf-8",
    )
    result = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "nexus_po_scope_check.py"),
         "--branch", "gov/po-x", "--base", "main", "--config", str(config_path)],
        cwd=str(repo), capture_output=True, text=True,
    )
    assert result.returncode == 1
    assert "utils_x.py" in result.stderr


def test_cli_main_end_to_end_allows_governance_only(tmp_path):
    repo = _init_repo_with_po_branch(tmp_path, touch_out_of_scope=False)
    config_path = tmp_path / "scope.json"
    config_path.write_text(
        '{"branch_prefix": "gov/po-", "allowed_paths": ["project/roadmap.json"]}',
        encoding="utf-8",
    )
    result = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "nexus_po_scope_check.py"),
         "--branch", "gov/po-x", "--base", "main", "--config", str(config_path)],
        cwd=str(repo), capture_output=True, text=True,
    )
    assert result.returncode == 0
