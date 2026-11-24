"""Pipelined ships (2026-11-23): the preview lock is held until the deploy lock is taken, then handed on."""
import fcntl
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "delivery"))
import standalone_orchestrate as sa  # noqa: E402


def lockable(path):
    handle = path.open("a+")
    try:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return True
    except BlockingIOError:
        return False
    finally:
        handle.close()


def test_deploy_lock_hands_the_preview_lock_to_the_next_ship(tmp_path, monkeypatch):
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path)
    monkeypatch.setattr(sa, "_DEPLOY_LOCK", None)
    preview = (tmp_path / "ship-preview.lock").open("a+")
    fcntl.flock(preview, fcntl.LOCK_EX)
    assert not lockable(tmp_path / "ship-preview.lock")
    sa._acquire_deploy_lock(preview, "batch/a")
    assert lockable(tmp_path / "ship-preview.lock"), "the next ship may preview now"
    assert not lockable(tmp_path / "ship.lock"), "the deploy lock is held until the ship ends"
    assert (tmp_path / "ship.lock").read_text() == "batch/a"
    sa._DEPLOY_LOCK.close()
    monkeypatch.setattr(sa, "_DEPLOY_LOCK", None)
    preview.close()


def test_cmd_ship_releases_both_locks_and_waits_for_the_deploy_lock_only_before_the_merge(tmp_path, monkeypatch):
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path)
    monkeypatch.setattr(sa, "_DEPLOY_LOCK", None)
    seen = []

    def fake_ship(args):
        seen.append(("preview-held", not lockable(tmp_path / "ship-preview.lock")))
        sa._acquire_deploy_lock(sa._PREVIEW_LOCK_HANDLE, sa._SHIP_NAME)
        seen.append(("deploy-held", not lockable(tmp_path / "ship.lock")))
        seen.append(("preview-free", lockable(tmp_path / "ship-preview.lock")))
        return 0
    monkeypatch.setattr(sa, "_ship", fake_ship)
    args = type("A", (), {"task": None, "branch": "batch/b"})()
    assert sa.cmd_ship(args) == 0
    assert seen == [("preview-held", True), ("deploy-held", True), ("preview-free", True)]
    assert lockable(tmp_path / "ship.lock") and lockable(tmp_path / "ship-preview.lock")
    assert sa._DEPLOY_LOCK is None and sa._PREVIEW_LOCK_HANDLE is None


def test_the_merge_point_waits_for_the_checks_before_it_takes_the_deploy_lock():
    """2026-11-24 review: a stuck check (up to 45 min) must not sit on the lock every other ship's merge waits for."""
    text = (ROOT / "tools" / "delivery" / "standalone_orchestrate.py").read_text()
    checks = text.index("        _wait_for_checks(url)\n    # Pipelined ships: wait here")
    lock = text.index("_acquire_deploy_lock(_PREVIEW_LOCK_HANDLE, _SHIP_NAME)")
    assert checks < lock < text.index("merge = _merge_pull_request(url, head=ship_commit)")
