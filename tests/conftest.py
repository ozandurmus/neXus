from __future__ import annotations

import sys
import types
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))


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
