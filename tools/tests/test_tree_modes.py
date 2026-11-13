"""tools/delivery/tree_modes.py: tracked paths in the deploy tree must stay world-readable (offline, synthetic repo)."""
import os
from pathlib import Path
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "delivery"))
import tree_modes  # noqa: E402


@pytest.fixture
def repo(tmp_path):
    repo = tmp_path / "tree"
    (repo / "suite" / "e2e").mkdir(parents=True)
    for relative in ("playwright.config.ts", "suite/e2e/spec.ts", "suite/readme.md"):
        (repo / relative).write_text("synthetic\n")
        (repo / relative).chmod(0o644)
    (repo / "ignored.pem").write_text("synthetic\n")
    (repo / "ignored.pem").chmod(0o600)
    (repo / ".gitignore").write_text("ignored.pem\n")
    (repo / ".gitignore").chmod(0o644)
    os.symlink("playwright.config.ts", repo / "link")
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    subprocess.run(["git", "-C", str(repo), "add", "-A"], check=True)
    for directory in (repo / "suite", repo / "suite" / "e2e"):
        directory.chmod(0o755)
    repo.chmod(0o750)  # the closed top directory is fine
    return repo


def run(repo):
    return subprocess.run([sys.executable, str(ROOT / "tools/delivery/tree_modes.py"), "check", "--repo", str(repo)],
                          capture_output=True, text=True, timeout=30)


def test_world_readable_tree_passes_with_a_closed_top_and_private_untracked_files(repo):
    assert tree_modes.unreadable(repo) == []
    assert run(repo).returncode == 0


def test_a_tracked_file_without_world_read_stops_the_build(repo):
    (repo / "playwright.config.ts").chmod(0o640)
    result = run(repo)
    assert result.returncode == 7
    assert "1 tracked paths are not world-readable, first: playwright.config.ts" in result.stderr


def test_a_tracked_directory_without_world_traverse_is_reported(repo):
    (repo / "suite" / "e2e").chmod(0o750)
    try:
        assert tree_modes.unreadable(repo) == ["suite/e2e/"]
    finally:
        (repo / "suite" / "e2e").chmod(0o755)


def test_both_context_loaders_normalise_modes_after_extraction():
    sys.path.insert(0, str(ROOT / "tools" / "e2e"))
    import hosta_preview_e2e as preview
    lines = preview.LOAD_CONTEXT.splitlines()
    assert lines[lines.index("tar -xf - -C /workspace") + 1] == "chmod -R a+rX /workspace/ui2 /workspace/project /workspace/docs"
