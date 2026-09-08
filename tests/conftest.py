from __future__ import annotations

import sys
import types
from pathlib import Path

import pytest


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))


@pytest.fixture(autouse=True)
def _clear_ambient_relay_env(monkeypatch):
    """An orchestrated engineer session (docs/design/
    GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md, section 3.6) always has
    NEXUS_RELAY_FILE/NEXUS_CANONICAL_RELAY_DIR set in its real process
    environment for its own relay coordination. Left set, pytest inherits
    them into every test that shells out to or imports
    scripts/local_relay.py with its own unrelated tmp_path relay file,
    which local_relay.py's append now rejects when an explicit --file
    doesn't match NEXUS_RELAY_FILE (NXS-LOCAL-0021/0026) -- so the full
    suite must run hermetic to the invoking session's own ambient relay
    state, exactly as it does in CI, where neither variable is ever set."""
    monkeypatch.delenv("NEXUS_RELAY_FILE", raising=False)
    monkeypatch.delenv("NEXUS_CANONICAL_RELAY_DIR", raising=False)


# The parser characterization tests do not open SSH sessions.  Allow them to
# run in a lightweight test environment where Paramiko is not installed.
try:
    import paramiko  # noqa: F401
except ModuleNotFoundError:
    stub = types.ModuleType("paramiko")

    class _SSHClient:
        pass

    class _AutoAddPolicy:
        pass

    class _RejectPolicy:
        pass

    stub.SSHClient = _SSHClient
    stub.AutoAddPolicy = _AutoAddPolicy
    stub.RejectPolicy = _RejectPolicy
    sys.modules["paramiko"] = stub
