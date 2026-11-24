from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

import pytest

import fcntl


PROJECT_ROOT = Path(__file__).resolve().parents[2]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))


@pytest.fixture(autouse=True)
def _clear_ambient_relay_env(monkeypatch):
    """An orchestrated engineer session (docs/design/
    GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md, section 3.6) always has
    NEXUS_RELAY_FILE/NEXUS_CANONICAL_RELAY_DIR set in its real process
    environment for its own relay coordination. Left set, pytest inherits
    them into every test that shells out to or imports
    tools/delivery/local_relay.py with its own unrelated tmp_path relay file,
    which local_relay.py's append now rejects when an explicit --file
    doesn't match NEXUS_RELAY_FILE (NXS-LOCAL-0021/0026) -- so the full
    suite must run hermetic to the invoking session's own ambient relay
    state, exactly as it does in CI, where neither variable is ever set."""
    monkeypatch.delenv("NEXUS_RELAY_FILE", raising=False)
    monkeypatch.delenv("NEXUS_CANONICAL_RELAY_DIR", raising=False)


@pytest.fixture(autouse=True)
def _hermetic_process_umask():
    """tools/delivery/release_snapshot.py and tools/security/security_*.py call
    os.umask(0o077) from main(); a test that runs one in-process left every
    later test creating 0600 files (test_workbench_companion's mode check then
    saw a private file where it builds a deliberately public one). Start each
    test from the usual 022 and restore whatever the invoking process had."""
    previous = os.umask(0o022)
    try:
        yield
    finally:
        os.umask(previous)


@pytest.fixture
def real_repository_privacy_lock():
    """Serialize tests that scan or mutate the shared repository root."""
    # ponytail: global test lock; scope by repository if concurrent suites need throughput.
    lock_path = Path(tempfile.gettempdir()) / "nexus-real-repository-privacy.lock"
    with lock_path.open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


class _NoPregate:
    """Stand-in for standalone_orchestrate._SecurityPregate in ship-flow tests."""

    def cancel(self, *args, **kwargs):
        return None

    def join(self, *args, **kwargs):
        return None

    def close(self, *args, **kwargs):
        return None


@pytest.fixture(autouse=True)
def _no_real_security_pregate(request, monkeypatch):
    """Ship-flow tests double every host call; the pre-merge security gate (PO 2026-11-23) is doubled here as a PASS for
    the shipped commit so no test reaches a host. tools/tests/test_security_pregate.py tests the real functions.

    Both import names are loaded and patched BEFORE the test body runs, so a test that imports the module lazily
    (inside the test function) or package-qualified (tools.delivery.standalone_orchestrate) gets the doubled module
    from sys.modules too, never one that could start the real wrapper."""
    if request.module.__name__.endswith("test_security_pregate"):
        return
    import importlib
    delivery = str(PROJECT_ROOT / "tools" / "delivery")
    if delivery not in sys.path:
        sys.path.insert(0, delivery)
    for name in ("standalone_orchestrate", "tools.delivery.standalone_orchestrate"):
        try:
            module = sys.modules.get(name) or importlib.import_module(name)
        except Exception:  # noqa: BLE001 -- a module that cannot import cannot start a pregate either
            continue
        if not hasattr(module, "_start_security_pregate"):
            continue
        monkeypatch.setattr(module, "_start_security_pregate", lambda *a, **k: _NoPregate())
        monkeypatch.setattr(module, "_await_security_verdict", lambda *a, **k: "PASS")
        monkeypatch.setattr(module, "SECURITY_PREGATE_WRAPPER", PROJECT_ROOT / "tools" / "tests" / "no-real-pregate")
