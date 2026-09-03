"""0.6.1B.1.4 — CP SSH Host-Key Trust Foundation

Verifies that:
- cp_runner.run_cp selects AutoAddPolicy (default) or RejectPolicy (strict)
  based on SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY env var.
- vsx_runner.connect() accepts strict_host_key kwarg and applies correct policy.
- vsx_runner.run_vsx reads FBUDDY_VSX_SSH_STRICT_HOST_KEY and passes it to connect().
- direct_ssh_probe._env_bool contract for FBUDDY_CP_DIRECT_SSH_STRICT_HOST_KEY.

All tests are unit-level: no real SSH connections are opened.

S8-P0.1 correction (OP.0b): the fake client no longer simulates the trust
store through ``get_host_keys()`` (Paramiko's writable local store); strict
tests provision a real synthetic ``known_hosts`` in an isolated user profile
and the shared helper makes the trust decision over it.
"""
from pathlib import Path
from unittest.mock import MagicMock, patch
import pytest

pytestmark = pytest.mark.security


# ---------------------------------------------------------------------------
# Helpers — tracked SSHClient factory
# ---------------------------------------------------------------------------

def _provision_synthetic_known_hosts(tmp_path: Path, monkeypatch) -> Path:
    """Isolated user profile (HOME + USERPROFILE) whose ``.ssh/known_hosts``
    holds one synthetic, in-process generated entry (RFC 5737 host)."""
    import paramiko

    home = tmp_path / "home"
    (home / ".ssh").mkdir(parents=True, exist_ok=True)
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("USERPROFILE", str(home))
    monkeypatch.delenv("HOMEDRIVE", raising=False)
    monkeypatch.delenv("HOMEPATH", raising=False)
    key = paramiko.ECDSAKey.generate()
    known_hosts = home / ".ssh" / "known_hosts"
    known_hosts.write_text(f"192.0.2.10 {key.get_name()} {key.get_base64()}\n", encoding="utf-8")
    return known_hosts


def _make_tracked_ssh():
    """Return (FakeSSHClient class, instances list).

    FakeSSHClient records every instance created and the policy that was set
    on it.  connect() raises immediately so the real run is aborted after
    policy selection without hitting the network.  It does not simulate the
    trust store: the shared helper reads the real (isolated) known_hosts.
    """
    instances = []

    class FakeSSHClient:
        def __init__(self_inner):
            self_inner._policy = None
            self_inner._system_host_keys_loaded = False
            instances.append(self_inner)

        def load_system_host_keys(self_inner, filename=None):
            self_inner._system_host_keys_loaded = True

        def set_missing_host_key_policy(self_inner, policy):
            self_inner._policy = policy

        def connect(self_inner, host, **kwargs):
            raise RuntimeError("abort-after-policy-selection")

        def close(self_inner):
            pass

    return FakeSSHClient, instances


# ---------------------------------------------------------------------------
# cp_runner — MDS SSH host-key policy via SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY
# ---------------------------------------------------------------------------

class TestCpRunnerHostKeyPolicy:
    """cp_runner.run_cp selects the correct paramiko policy via env var."""

    def _make_cfg(self):
        class Auth:
            principal = "admin"
            secret = "secret"

        class RuntimePaths:
            output_root = "/tmp/cp_test_out"
            data_root = "/tmp/cp_test_data"

        class Cfg:
            mds_ip = "192.0.2.1"
            auth = Auth()
            runtime_paths = RuntimePaths()

        return Cfg()

    def test_default_uses_auto_add_policy(self, monkeypatch):
        monkeypatch.delenv("SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY", raising=False)

        import checkpoint.cp_runner as cp_runner
        import paramiko

        FakeSSHClient, instances = _make_tracked_ssh()

        with patch("paramiko.SSHClient", FakeSSHClient), \
             patch.object(cp_runner, "load_inventory_exclusions") as mock_excl:
            mock_excl.return_value = MagicMock(identities_for=MagicMock(return_value=[]))
            try:
                cp_runner.run_cp(self._make_cfg())
            except Exception:
                pass

        assert instances, "SSHClient must be instantiated"
        assert isinstance(instances[0]._policy, paramiko.AutoAddPolicy), \
            "Default must use AutoAddPolicy"
        assert not instances[0]._system_host_keys_loaded

    def test_strict_env_uses_reject_policy(self, monkeypatch, tmp_path):
        monkeypatch.setenv("SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY", "1")
        _provision_synthetic_known_hosts(tmp_path, monkeypatch)

        import checkpoint.cp_runner as cp_runner
        import paramiko

        FakeSSHClient, instances = _make_tracked_ssh()

        with patch("paramiko.SSHClient", FakeSSHClient), \
             patch.object(cp_runner, "load_inventory_exclusions") as mock_excl:
            mock_excl.return_value = MagicMock(identities_for=MagicMock(return_value=[]))
            try:
                cp_runner.run_cp(self._make_cfg())
            except Exception:
                pass

        assert instances, "SSHClient must be instantiated"
        assert isinstance(instances[0]._policy, paramiko.RejectPolicy), \
            "SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY=1 must use RejectPolicy"
        assert instances[0]._system_host_keys_loaded, \
            "Strict mode must call load_system_host_keys()"


# ---------------------------------------------------------------------------
# vsx_runner — connect() host-key policy via kwarg + env var routing
# ---------------------------------------------------------------------------

class TestVsxRunnerConnectPolicy:
    """vsx_runner.connect() passes strict_host_key to the paramiko client."""

    def test_connect_default_uses_auto_add_policy(self, monkeypatch):
        import checkpoint.vsx_runner as vsx_runner
        import paramiko

        FakeSSHClient, instances = _make_tracked_ssh()
        monkeypatch.setattr(paramiko, "SSHClient", FakeSSHClient)

        try:
            vsx_runner.connect("192.0.2.2", "user", "pass")
        except Exception:
            pass

        assert instances, "SSHClient must be instantiated"
        assert isinstance(instances[0]._policy, paramiko.AutoAddPolicy)
        assert not instances[0]._system_host_keys_loaded

    def test_connect_strict_uses_reject_policy(self, monkeypatch, tmp_path):
        import checkpoint.vsx_runner as vsx_runner
        import paramiko

        _provision_synthetic_known_hosts(tmp_path, monkeypatch)

        FakeSSHClient, instances = _make_tracked_ssh()
        monkeypatch.setattr(paramiko, "SSHClient", FakeSSHClient)

        try:
            vsx_runner.connect("192.0.2.2", "user", "pass", strict_host_key=True)
        except Exception:
            pass

        assert instances, "SSHClient must be instantiated"
        assert isinstance(instances[0]._policy, paramiko.RejectPolicy)
        assert instances[0]._system_host_keys_loaded

    def test_strict_env_var_forwarded_to_run_vsx(self, monkeypatch):
        """run_vsx reads FBUDDY_VSX_SSH_STRICT_HOST_KEY and passes it to connect()."""
        monkeypatch.setenv("FBUDDY_VSX_SSH_STRICT_HOST_KEY", "1")

        import checkpoint.vsx_runner as vsx_runner

        strict_seen = []

        def fake_connect(host, user, pwd, *, timeout=None, strict_host_key=False):
            strict_seen.append(strict_host_key)
            raise RuntimeError("abort-in-test")

        monkeypatch.setattr(vsx_runner, "connect", fake_connect)

        class Cfg:
            mds_ip = "192.0.2.1"
            class auth:
                principal = "admin"
                secret = "secret"
            class runtime_paths:
                output_root = "/tmp/test_vsx_out"

        try:
            vsx_runner.run_vsx(Cfg())
        except Exception:
            pass

        assert strict_seen, "connect() must have been called by run_vsx"
        assert strict_seen[0] is True, "strict_host_key must be True when env var is set"


# ---------------------------------------------------------------------------
# direct_ssh_probe — _env_bool contract for FBUDDY_CP_DIRECT_SSH_STRICT_HOST_KEY
# ---------------------------------------------------------------------------

class TestDirectSshProbeEnvBool:
    """_env_bool correctly interprets truthy/falsy env var values."""

    def test_absent_env_returns_default_false(self, monkeypatch):
        monkeypatch.delenv("FBUDDY_CP_DIRECT_SSH_STRICT_HOST_KEY", raising=False)
        from checkpoint.direct_ssh_probe import _env_bool
        assert _env_bool("FBUDDY_CP_DIRECT_SSH_STRICT_HOST_KEY", False) is False

    def test_truthy_env_returns_true(self, monkeypatch):
        monkeypatch.setenv("FBUDDY_CP_DIRECT_SSH_STRICT_HOST_KEY", "1")
        from checkpoint.direct_ssh_probe import _env_bool
        assert _env_bool("FBUDDY_CP_DIRECT_SSH_STRICT_HOST_KEY", False) is True

    def test_false_string_returns_false(self, monkeypatch):
        monkeypatch.setenv("FBUDDY_CP_DIRECT_SSH_STRICT_HOST_KEY", "false")
        from checkpoint.direct_ssh_probe import _env_bool
        assert _env_bool("FBUDDY_CP_DIRECT_SSH_STRICT_HOST_KEY", True) is False
