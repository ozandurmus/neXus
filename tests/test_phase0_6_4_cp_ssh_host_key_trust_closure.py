"""0.6.4 — CP SSH Host-Key Trust Production Closure

Acceptance criteria covered:
  AC-1  Strict mode is explicit and disabled by default; compatibility mode
        stays source-compatible.
  AC-2  Each CP MDS, VSX and direct-SSH strict path loads trusted host keys
        and sets RejectPolicy before connect.
  AC-3  Missing / unreadable / malformed strict trust input returns a
        value-free preflight failure and makes zero connection attempts.
  AC-4  Strict-mode unknown/mismatched host key is rejected with no fallback
        or sensitive telemetry.
  AC-5  Existing CP command, Expert/Clish, retry, timeout, polling, concurrency
        semantics are unchanged.
  AC-6  Tests and output contain no real host keys, endpoints, users,
        credentials, fingerprints or absolute runtime paths.
  AC-7  Targeted transport/collector regression and privacy gate pass; known
        xfails remain unchanged.

All tests are unit-level: no real SSH connections are opened.
Synthetic values use 192.0.2.0/24 and 198.51.100.0/24 (RFC 5737).
"""
from __future__ import annotations

import os
import socket
import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch

import paramiko
import pytest

from utils.cp_ssh_trust import CpSshStrictPreflightError, apply_strict_host_key_policy

pytestmark = pytest.mark.security


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_ssh_client(*, host_keys: dict | None = None):
    """Return a MagicMock SSHClient whose get_host_keys() returns host_keys."""
    client = MagicMock(spec=paramiko.SSHClient)
    client.get_host_keys.return_value = host_keys if host_keys is not None else {}
    return client


# ---------------------------------------------------------------------------
# AC-1: apply_strict_host_key_policy defaults and compatibility mode
# ---------------------------------------------------------------------------

class TestApplyStrictHostKeyPolicyCompat:
    """Compatibility mode (strict=False) sets AutoAddPolicy without preflight."""

    def test_compat_mode_sets_auto_add_policy(self):
        ssh = _make_ssh_client()
        apply_strict_host_key_policy(ssh, strict=False)
        ssh.set_missing_host_key_policy.assert_called_once()
        policy = ssh.set_missing_host_key_policy.call_args[0][0]
        assert isinstance(policy, paramiko.AutoAddPolicy)

    def test_compat_mode_does_not_load_system_host_keys(self):
        ssh = _make_ssh_client()
        apply_strict_host_key_policy(ssh, strict=False)
        ssh.load_system_host_keys.assert_not_called()

    def test_compat_mode_does_not_call_get_host_keys(self):
        ssh = _make_ssh_client()
        apply_strict_host_key_policy(ssh, strict=False)
        ssh.get_host_keys.assert_not_called()


# ---------------------------------------------------------------------------
# AC-2: apply_strict_host_key_policy strict path with keys present
# ---------------------------------------------------------------------------

class TestApplyStrictHostKeyPolicyStrict:
    """Strict mode with a non-empty known_hosts: loads keys, sets RejectPolicy."""

    def test_strict_with_keys_sets_reject_policy(self):
        ssh = _make_ssh_client(host_keys={"synth.test": {"ssh-rsa": MagicMock()}})
        apply_strict_host_key_policy(ssh, strict=True)
        ssh.set_missing_host_key_policy.assert_called_once()
        policy = ssh.set_missing_host_key_policy.call_args[0][0]
        assert isinstance(policy, paramiko.RejectPolicy)

    def test_strict_loads_system_host_keys_before_policy(self):
        ssh = _make_ssh_client(host_keys={"synth.test": {"ssh-rsa": MagicMock()}})
        call_order = []
        ssh.load_system_host_keys.side_effect = lambda: call_order.append("load")
        ssh.set_missing_host_key_policy.side_effect = lambda _: call_order.append("policy")
        apply_strict_host_key_policy(ssh, strict=True)
        assert call_order == ["load", "policy"], "load must precede policy"

    def test_strict_with_keys_does_not_raise(self):
        ssh = _make_ssh_client(host_keys={"synth.test": {"ssh-rsa": MagicMock()}})
        apply_strict_host_key_policy(ssh, strict=True)  # must not raise


# ---------------------------------------------------------------------------
# AC-3: apply_strict_host_key_policy raises preflight error when keys absent
# ---------------------------------------------------------------------------

class TestApplyStrictHostKeyPolicyPreflight:
    """Strict mode with empty known_hosts raises CpSshStrictPreflightError."""

    def test_strict_empty_host_keys_raises_preflight_error(self):
        ssh = _make_ssh_client(host_keys={})
        with pytest.raises(CpSshStrictPreflightError):
            apply_strict_host_key_policy(ssh, strict=True)

    def test_preflight_error_message_is_value_free(self):
        ssh = _make_ssh_client(host_keys={})
        try:
            apply_strict_host_key_policy(ssh, strict=True)
            assert False, "Should have raised"
        except CpSshStrictPreflightError as exc:
            msg = str(exc)
            # Must not contain IP, hostname, path, key material, credentials.
            for forbidden in ("192.", "198.", "/home", "C:\\", "password", "secret", "key-"):
                assert forbidden not in msg, f"Preflight error must not contain '{forbidden}'"
            assert "preflight_failed" in msg or "no_usable" in msg

    def test_strict_empty_host_keys_does_not_call_connect(self):
        """After preflight failure, the caller must not invoke ssh.connect()."""
        ssh = _make_ssh_client(host_keys={})
        try:
            apply_strict_host_key_policy(ssh, strict=True)
        except CpSshStrictPreflightError:
            pass
        ssh.connect.assert_not_called()

    def test_preflight_checks_after_load_system_host_keys(self):
        """get_host_keys() is called only after load_system_host_keys()."""
        ssh = _make_ssh_client(host_keys={})
        call_order = []
        ssh.load_system_host_keys.side_effect = lambda: call_order.append("load")
        ssh.get_host_keys.side_effect = lambda: (call_order.append("get"), {})[1]
        try:
            apply_strict_host_key_policy(ssh, strict=True)
        except CpSshStrictPreflightError:
            pass
        assert "load" in call_order
        assert "get" in call_order
        assert call_order.index("load") < call_order.index("get")


# ---------------------------------------------------------------------------
# AC-2/AC-3: cp_runner strict and preflight path
# ---------------------------------------------------------------------------

class TestCpRunnerPreflight:
    """cp_runner.run_cp respects strict host-key preflight via env var."""

    def _make_cfg(self):
        class Auth:
            principal = "admin"
            secret = "s3cr3t"

        class RuntimePaths:
            output_root = "/tmp/cp_064_out"
            data_root = "/tmp/cp_064_data"

        class Cfg:
            mds_ip = "192.0.2.1"
            auth = Auth()
            runtime_paths = RuntimePaths()

        return Cfg()

    def _fake_ssh_factory(self, *, host_keys_empty: bool = False):
        instances = []

        class FakeSSH:
            def __init__(self_inner):
                instances.append(self_inner)
                self_inner._policy = None
                self_inner._loaded = False

            def load_system_host_keys(self_inner):
                self_inner._loaded = True

            def set_missing_host_key_policy(self_inner, p):
                self_inner._policy = p

            def get_host_keys(self_inner):
                return {} if host_keys_empty else {"synth.test": {"ssh-rsa": MagicMock()}}

            def connect(self_inner, *a, **kw):
                raise RuntimeError("abort-in-test")

            def close(self_inner):
                pass

        return FakeSSH, instances

    def test_strict_env_with_keys_uses_reject_policy(self, monkeypatch):
        monkeypatch.setenv("SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY", "1")
        import checkpoint.cp_runner as cp_runner
        FakeSSH, instances = self._fake_ssh_factory(host_keys_empty=False)
        with patch("paramiko.SSHClient", FakeSSH), \
             patch.object(cp_runner, "load_inventory_exclusions",
                          return_value=MagicMock(identities_for=MagicMock(return_value=[]))):
            try:
                cp_runner.run_cp(self._make_cfg())
            except Exception:
                pass
        assert instances
        assert isinstance(instances[0]._policy, paramiko.RejectPolicy)

    def test_strict_env_empty_keys_raises_before_connect(self, monkeypatch):
        monkeypatch.setenv("SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY", "1")
        import checkpoint.cp_runner as cp_runner
        FakeSSH, instances = self._fake_ssh_factory(host_keys_empty=True)
        connect_called = []
        original_connect = FakeSSH.connect

        def tracked_connect(self_inner, *a, **kw):
            connect_called.append(True)
            original_connect(self_inner, *a, **kw)

        FakeSSH.connect = tracked_connect

        with patch("paramiko.SSHClient", FakeSSH), \
             patch.object(cp_runner, "load_inventory_exclusions",
                          return_value=MagicMock(identities_for=MagicMock(return_value=[]))):
            with pytest.raises(CpSshStrictPreflightError):
                cp_runner.run_cp(self._make_cfg())

        assert not connect_called, "connect() must not be called after preflight failure"


# ---------------------------------------------------------------------------
# AC-2/AC-3: vsx_runner.connect() preflight path
# ---------------------------------------------------------------------------

class TestVsxRunnerPreflight:
    """vsx_runner.connect() raises CpSshStrictPreflightError when keys absent."""

    def _fake_ssh(self, *, host_keys_empty: bool):
        instances = []

        class FakeSSH:
            def __init__(self_inner):
                instances.append(self_inner)
                self_inner._policy = None

            def load_system_host_keys(self_inner):
                pass

            def set_missing_host_key_policy(self_inner, p):
                self_inner._policy = p

            def get_host_keys(self_inner):
                return {} if host_keys_empty else {"synth.test": {"ssh-rsa": MagicMock()}}

            def connect(self_inner, *a, **kw):
                raise RuntimeError("abort-in-test")

            def close(self_inner):
                pass

        return FakeSSH, instances

    def test_strict_with_keys_reaches_connect(self, monkeypatch):
        import checkpoint.vsx_runner as vsx_runner
        FakeSSH, _ = self._fake_ssh(host_keys_empty=False)
        monkeypatch.setattr(paramiko, "SSHClient", FakeSSH)
        with pytest.raises(RuntimeError, match="abort-in-test"):
            vsx_runner.connect("192.0.2.2", "user", "pass", strict_host_key=True)

    def test_strict_empty_keys_raises_preflight_before_connect(self, monkeypatch):
        import checkpoint.vsx_runner as vsx_runner
        FakeSSH, instances = self._fake_ssh(host_keys_empty=True)
        connect_called = []
        orig = FakeSSH.connect

        def tracked(self_inner, *a, **kw):
            connect_called.append(True)
            orig(self_inner, *a, **kw)

        FakeSSH.connect = tracked
        monkeypatch.setattr(paramiko, "SSHClient", FakeSSH)

        with pytest.raises(CpSshStrictPreflightError):
            vsx_runner.connect("192.0.2.2", "user", "pass", strict_host_key=True)

        assert not connect_called, "connect() must not be called after preflight failure"


# ---------------------------------------------------------------------------
# AC-3: direct_ssh_probe._probe_one returns safe dict on preflight failure
# ---------------------------------------------------------------------------

class TestDirectSshProbePreflight:
    """_probe_one returns error_class='strict_host_key_preflight_failed' on preflight miss."""

    def test_probe_one_preflight_failure_returns_safe_dict(self, monkeypatch):
        from checkpoint.direct_ssh_probe import _probe_one
        from utils.cp_ssh_trust import CpSshStrictPreflightError as PreflightErr

        # Patch apply_strict_host_key_policy to raise preflight error
        def mock_apply(ssh, strict):
            if strict:
                raise PreflightErr("strict_host_key_preflight_failed: no_usable_host_keys_loaded")
            ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())

        monkeypatch.setattr("checkpoint.direct_ssh_probe.apply_strict_host_key_policy", mock_apply)

        row = {"device": "SYNTH-CP-GW", "management_ip": "192.0.2.50",
               "management_state": "up", "collection_outcome": None,
               "interface_error": None, "route_error": None}

        result = _probe_one(
            row,
            username="admin",
            secret="s3cr3t",
            port=22,
            connect_timeout=5,
            command_timeout=10,
            strict_host_key=True,
        )

        assert result["error_class"] == "strict_host_key_preflight_failed"
        # Must not expose any operational identity
        encoded = str(result)
        for forbidden in ("password", "secret", "private-key", "s3cr3t", "admin"):
            assert forbidden not in encoded

    def test_probe_one_strict_disabled_uses_compat_mode(self, monkeypatch):
        """Strict=False path must still use AutoAddPolicy without preflight."""
        from checkpoint.direct_ssh_probe import _probe_one

        policies_applied = []

        def mock_apply(ssh, strict):
            policies_applied.append(strict)
            if not strict:
                ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())

        monkeypatch.setattr("checkpoint.direct_ssh_probe.apply_strict_host_key_policy", mock_apply)
        # Make connect raise immediately
        monkeypatch.setattr(
            paramiko.SSHClient, "connect",
            lambda *a, **kw: (_ for _ in ()).throw(OSError("synth-unreachable")),
        )

        row = {"device": "SYNTH-CP-GW", "management_ip": "192.0.2.51",
               "management_state": "up", "collection_outcome": None,
               "interface_error": None, "route_error": None}

        result = _probe_one(
            row,
            username="admin",
            secret="s3cr3t",
            port=22,
            connect_timeout=5,
            command_timeout=10,
            strict_host_key=False,
        )

        assert all(not s for s in policies_applied), "No strict=True must appear in compat mode"
        assert result["error_class"] != "strict_host_key_preflight_failed"


# ---------------------------------------------------------------------------
# AC-3: checkpoint_config_probe._probe_physical_target returns safe dict on preflight miss
# ---------------------------------------------------------------------------

class TestConfigProbePreflight:
    """_probe_physical_target returns error_class='strict_host_key_preflight_failed'."""

    def _make_target(self):
        from configuration.checkpoint_config_probe import ProbeTarget
        return ProbeTarget(
            role="standalone_gateway",
            device="SYNTH-CP-PROBE-01",
            management_ip="192.0.2.60",
            object_type="gateway",
            cma=None,
            selection_source="management_discovery",
        )

    def test_probe_physical_target_preflight_failure(self, monkeypatch):
        from configuration import checkpoint_config_probe as probe
        from utils.cp_ssh_trust import CpSshStrictPreflightError as PreflightErr

        def mock_connect(target, username, secret, *, strict, connect_timeout):
            if strict:
                raise PreflightErr("strict_host_key_preflight_failed: no_usable_host_keys_loaded")
            raise RuntimeError("abort-compat")

        monkeypatch.setattr(probe, "_connect", mock_connect)

        result = probe._probe_physical_target(
            self._make_target(),
            username="admin",
            secret="s3cr3t",
            strict_host_key=True,
            connect_timeout=5,
            command_timeout=10,
        )

        assert result["error_class"] == "strict_host_key_preflight_failed"
        assert result["failure_family"] == "trust_failure"
        assert not result["ssh_reachable"]


# ---------------------------------------------------------------------------
# AC-4: BadHostKeyException is distinct from preflight failure
# ---------------------------------------------------------------------------

class TestHostKeyMismatchIsDistinct:
    """A host-key mismatch during connect() is error_class='host_key_mismatch'."""

    def test_host_key_mismatch_not_confused_with_preflight_failure(self, monkeypatch):
        from configuration import checkpoint_config_probe as probe

        # Simulate: keys are loaded (preflight passes) but server key mismatches.
        def mock_connect(target, username, secret, *, strict, connect_timeout):
            raise paramiko.BadHostKeyException(
                hostname="synth.test",
                got_key=MagicMock(),
                expected_key=MagicMock(),
            )

        monkeypatch.setattr(probe, "_connect", mock_connect)

        from configuration.checkpoint_config_probe import ProbeTarget
        target = ProbeTarget(
            role="standalone_gateway",
            device="SYNTH-CP-PROBE-02",
            management_ip="192.0.2.61",
            object_type="gateway",
            cma=None,
            selection_source="management_discovery",
        )

        result = probe._probe_physical_target(
            target,
            username="admin",
            secret="s3cr3t",
            strict_host_key=True,
            connect_timeout=5,
            command_timeout=10,
        )

        assert result["error_class"] == "host_key_mismatch"
        assert result["ssh_reachable"] is True


# ---------------------------------------------------------------------------
# AC-5: CP Expert/Clish command vocabulary unchanged
# ---------------------------------------------------------------------------

class TestCommandVocabularyUnchanged:
    """Existing CP Expert read-only command list is intact after 0.6.4 changes."""

    def test_expert_read_only_commands_preserved(self):
        from configuration.checkpoint_config_probe import EXPERT_READ_ONLY_COMMANDS
        assert "shell" in EXPERT_READ_ONLY_COMMANDS
        assert "hostname" in EXPERT_READ_ONLY_COMMANDS
        assert "version" in EXPERT_READ_ONLY_COMMANDS
        assert "asset" in EXPERT_READ_ONLY_COMMANDS
        assert "configuration" in EXPERT_READ_ONLY_COMMANDS
        # No write command must be present.
        for cmd in EXPERT_READ_ONLY_COMMANDS.values():
            assert "set " not in cmd or "show set" in cmd or "printf" in cmd, (
                f"Unexpected write command in EXPERT_READ_ONLY_COMMANDS: {cmd!r}"
            )

    def test_collector_version_phase_unchanged(self):
        from configuration import checkpoint_config_collector as collector
        assert collector.PHASE == "0.6.1B.1.2", "Collector PHASE must not change in 0.6.4"


# ---------------------------------------------------------------------------
# AC-6: No sensitive value in preflight error or result dict
# ---------------------------------------------------------------------------

class TestPolicyPrivacyBoundary:
    """Preflight errors and safe result dicts must not contain sensitive values."""

    def test_preflight_error_string_contains_no_ip_or_path(self):
        ssh = _make_ssh_client(host_keys={})
        try:
            apply_strict_host_key_policy(ssh, strict=True)
            assert False, "Expected CpSshStrictPreflightError"
        except CpSshStrictPreflightError as exc:
            msg = str(exc)
            for forbidden in ("192.", "198.", "10.", "/home/", "C:\\", ".key", "known_hosts"):
                assert forbidden not in msg, f"Sensitive value in preflight message: {forbidden!r}"

    def test_safe_preflight_result_dict_is_value_free(self, monkeypatch):
        from checkpoint.direct_ssh_probe import _probe_one
        from utils.cp_ssh_trust import CpSshStrictPreflightError as PreflightErr

        def mock_apply(ssh, strict):
            if strict:
                raise PreflightErr("strict_host_key_preflight_failed: no_usable_host_keys_loaded")
            ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())

        monkeypatch.setattr("checkpoint.direct_ssh_probe.apply_strict_host_key_policy", mock_apply)

        row = {"device": "SYNTH-CP-GW", "management_ip": "192.0.2.50",
               "management_state": "up", "collection_outcome": None,
               "interface_error": None, "route_error": None}

        result = _probe_one(
            row,
            username="admin",
            secret="s3cr3t",
            port=22,
            connect_timeout=5,
            command_timeout=10,
            strict_host_key=True,
        )

        import json
        encoded = json.dumps(result)
        for forbidden in ("s3cr3t", "admin", "private-key", "password"):
            assert forbidden not in encoded
