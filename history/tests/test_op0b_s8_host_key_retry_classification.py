"""OP.0b S8 real-env finding — CP SSH connect retries must never retry a
host-key trust decision.

Discovered during the first live S8-A attempt: ``RejectPolicy`` correctly
rejected an unprovisioned target's host key, but the log showed a retry
("attempt 1/2... retrying in 2s") before the final failure. A host-key
trust refusal is a security decision, never a transient reachability blip,
and the surrounding code's own comments already say so
("host-key failures are never retried") -- the *behavior* did not match.

Root cause: Paramiko's ``RejectPolicy.missing_host_key`` raises a bare
``paramiko.SSHException`` on a *missing* entry, indistinguishable by type
from a transient transport failure, so it fell into the generic retryable
``except`` clause in both shared CP SSH retry loops
(``configuration/checkpoint_config_probe.py::_connect`` and
``checkpoint/direct_ssh_probe.py::_probe_one``).

A second, more severe instance of the same defect class was found while
auditing the second loop: ``checkpoint/direct_ssh_probe.py``'s inner retry
loop had *no* non-retryable exception clause at all before this
correction, so ``paramiko.AuthenticationException`` and
``paramiko.BadHostKeyException`` were *also* being silently retried there
-- contradicting the file's own adjacent comment ("auth and host-key
failures are never retried"). ``configuration/checkpoint_config_probe.py``
did not have this second defect: it already listed
``AuthenticationException``/``BadHostKeyException`` in a first,
non-retryable ``except`` clause ahead of the generic one.

Fix: ``utils.cp_ssh_trust.HostKeyNotTrustedError``, a typed exception
raised by a ``RejectPolicy`` subclass instead of Paramiko's generic
``SSHException`` on a missing-entry rejection, is now classified as
non-retryable at both seams alongside the pre-existing
``AuthenticationException``/``BadHostKeyException`` handling. No parsing
of exception text; no change to the already-approved generic transport
retry count/backoff for genuinely retryable failures.

All tests use mocks/synthetic Paramiko exception instances only -- no real
SSH session, no real network, no production key material.
"""
from __future__ import annotations

import time
from unittest.mock import MagicMock

import paramiko
import pytest

from utils.cp_ssh_trust import HostKeyNotTrustedError

pytestmark = pytest.mark.security


def _bad_host_key_exc() -> paramiko.BadHostKeyException:
    return paramiko.BadHostKeyException(
        hostname="synth.test", got_key=MagicMock(), expected_key=MagicMock()
    )


# ---------------------------------------------------------------------------
# utils.cp_ssh_trust — the typed exception and the policy that raises it
# ---------------------------------------------------------------------------

class TestHostKeyNotTrustedErrorType:

    def test_is_an_ssh_exception_subclass(self):
        """So existing ``except paramiko.SSHException`` sites still catch it
        (defense in depth) -- but every retry loop must classify it BEFORE
        any generic SSHException clause, proven separately below."""
        assert issubclass(HostKeyNotTrustedError, paramiko.SSHException)

    def test_reject_policy_raises_the_typed_error_on_missing_entry(self):
        from utils.cp_ssh_trust import apply_strict_host_key_policy

        ssh = MagicMock(spec=paramiko.SSHClient)
        apply_strict_host_key_policy(ssh, strict=False)  # cheap way to get a real policy object below
        # Build the real strict policy directly to exercise missing_host_key.
        import utils.cp_ssh_trust as trust_mod

        policy = trust_mod._NonRetryableRejectPolicy()
        assert isinstance(policy, paramiko.RejectPolicy)
        client = MagicMock()
        client._transport = MagicMock()
        unknown_key = paramiko.ECDSAKey.generate()
        with pytest.raises(HostKeyNotTrustedError):
            policy.missing_host_key(client, "198.51.100.20", unknown_key)

    def test_value_free(self):
        import utils.cp_ssh_trust as trust_mod

        policy = trust_mod._NonRetryableRejectPolicy()
        client = MagicMock()
        client._transport = MagicMock()
        unknown_key = paramiko.ECDSAKey.generate()
        try:
            policy.missing_host_key(client, "198.51.100.21", unknown_key)
            assert False, "expected HostKeyNotTrustedError"
        except HostKeyNotTrustedError as exc:
            assert "198.51.100.21" not in str(exc)


# ---------------------------------------------------------------------------
# configuration/checkpoint_config_probe.py::_connect
# ---------------------------------------------------------------------------

class TestConfigProbeConnectRetryClassification:

    def _target(self):
        from configuration.checkpoint_config_probe import ProbeTarget

        return ProbeTarget(
            role="standalone_gateway", device="SYNTH-CP-GW", management_ip="192.0.2.70",
            object_type="gateway", cma=None, selection_source="management_discovery",
        )

    def _run(self, monkeypatch, exc_factory, *, retries=1):
        from configuration import checkpoint_config_probe as probe

        calls = {"connect": 0}
        sleeps: list = []

        class FakeSSH:
            def set_missing_host_key_policy(self_inner, policy):
                pass

            def connect(self_inner, *a, **kw):
                calls["connect"] += 1
                raise exc_factory()

            def close(self_inner):
                pass

            def get_transport(self_inner):
                return None

        monkeypatch.setattr(paramiko, "SSHClient", FakeSSH)
        monkeypatch.setattr(probe, "CONNECT_RETRY_ATTEMPTS", retries)
        monkeypatch.setattr(time, "sleep", lambda s: sleeps.append(s))

        raised = None
        try:
            probe._connect(self._target(), "admin", "s3cr3t", strict=False, connect_timeout=1)
        except Exception as exc:  # noqa: BLE001 - captured for assertion, not swallowed
            raised = exc
        return calls["connect"], sleeps, raised

    def test_missing_host_key_is_not_retried(self, monkeypatch):
        attempts, sleeps, raised = self._run(
            monkeypatch, lambda: HostKeyNotTrustedError("no_usable_host_keys_loaded"), retries=1
        )
        assert attempts == 1
        assert sleeps == []
        assert isinstance(raised, HostKeyNotTrustedError)

    def test_mismatched_host_key_is_not_retried(self, monkeypatch):
        attempts, sleeps, raised = self._run(monkeypatch, _bad_host_key_exc, retries=1)
        assert attempts == 1
        assert sleeps == []
        assert isinstance(raised, paramiko.BadHostKeyException)

    def test_authentication_failure_is_not_retried(self, monkeypatch):
        attempts, sleeps, raised = self._run(
            monkeypatch, lambda: paramiko.AuthenticationException("bad credentials"), retries=1
        )
        assert attempts == 1
        assert sleeps == []
        assert isinstance(raised, paramiko.AuthenticationException)

    def test_generic_transport_failure_still_retries_the_same_bounded_count(self, monkeypatch):
        """Pre-existing, already-approved retry behavior is unchanged."""
        attempts, sleeps, raised = self._run(
            monkeypatch, lambda: paramiko.SSHException("connection reset"), retries=1
        )
        assert attempts == 2  # 1 + CONNECT_RETRY_ATTEMPTS
        assert len(sleeps) == 1
        assert isinstance(raised, paramiko.SSHException)
        assert not isinstance(raised, HostKeyNotTrustedError)

    def test_generic_transport_failure_respects_zero_retries(self, monkeypatch):
        attempts, sleeps, raised = self._run(
            monkeypatch, lambda: paramiko.SSHException("connection reset"), retries=0
        )
        assert attempts == 1
        assert sleeps == []
        assert isinstance(raised, paramiko.SSHException)

    def test_socket_timeout_still_retries(self, monkeypatch):
        import socket

        attempts, sleeps, raised = self._run(monkeypatch, lambda: socket.timeout("timed out"), retries=1)
        assert attempts == 2
        assert len(sleeps) == 1


# ---------------------------------------------------------------------------
# checkpoint/direct_ssh_probe.py::_probe_one
# ---------------------------------------------------------------------------

class TestDirectProbeConnectRetryClassification:

    def _row(self):
        return {
            "device": "SYNTH-CP-GW", "management_ip": "192.0.2.71", "management_state": "up",
            "collection_outcome": None, "interface_error": None, "route_error": None,
        }

    def _run(self, monkeypatch, exc_factory, *, retries=1):
        from checkpoint.direct_ssh_probe import _probe_one

        calls = {"connect": 0}
        sleeps: list = []

        class FakeSSH:
            def set_missing_host_key_policy(self_inner, policy):
                pass

            def connect(self_inner, *a, **kw):
                calls["connect"] += 1
                raise exc_factory()

            def close(self_inner):
                pass

        monkeypatch.setattr(paramiko, "SSHClient", FakeSSH)
        monkeypatch.setenv("FBUDDY_CP_DIRECT_SSH_CONNECT_RETRIES", str(retries))
        monkeypatch.setenv("FBUDDY_CP_DIRECT_SSH_CONNECT_RETRY_BACKOFF_SECONDS", "1")
        monkeypatch.setattr(time, "sleep", lambda s: sleeps.append(s))

        result = _probe_one(
            self._row(), username="admin", secret="s3cr3t", port=22, connect_timeout=1,
            command_timeout=1, strict_host_key=False,
        )
        return calls["connect"], sleeps, result

    def test_missing_host_key_is_not_retried(self, monkeypatch):
        attempts, sleeps, result = self._run(
            monkeypatch, lambda: HostKeyNotTrustedError("no_usable_host_keys_loaded"), retries=1
        )
        assert attempts == 1
        assert sleeps == []
        assert result["error_class"] == "host_key_not_trusted"
        assert result["ssh_reachable"] is True

    def test_mismatched_host_key_is_not_retried(self, monkeypatch):
        """The more severe pre-existing defect this file had: BadHostKeyException
        fell into the generic retryable clause because the inner loop had no
        non-retryable except clause at all."""
        attempts, sleeps, result = self._run(monkeypatch, _bad_host_key_exc, retries=1)
        assert attempts == 1
        assert sleeps == []
        assert result["error_class"] == "host_key_mismatch"
        assert result["ssh_reachable"] is True

    def test_authentication_failure_is_not_retried(self, monkeypatch):
        """Same pre-existing defect: AuthenticationException was also being
        retried here despite the file's own comment against it."""
        attempts, sleeps, result = self._run(
            monkeypatch, lambda: paramiko.AuthenticationException("bad credentials"), retries=1
        )
        assert attempts == 1
        assert sleeps == []
        assert result["error_class"] == "authentication_failed"
        assert result["ssh_reachable"] is True
        assert result["authenticated"] is False

    def test_generic_transport_failure_still_retries_the_same_bounded_count(self, monkeypatch):
        attempts, sleeps, result = self._run(
            monkeypatch, lambda: paramiko.SSHException("connection reset"), retries=1
        )
        assert attempts == 2
        assert len(sleeps) == 1
        assert result["error_class"] == "ssh_error"

    def test_socket_timeout_still_retries_and_classifies_as_timeout(self, monkeypatch):
        import socket

        attempts, sleeps, result = self._run(monkeypatch, lambda: socket.timeout("timed out"), retries=1)
        assert attempts == 2
        assert len(sleeps) == 1
        assert result["error_class"] == "connect_timeout"


# ---------------------------------------------------------------------------
# configuration/checkpoint_config_collector.py::_collect_host — labeling only
# (retry itself is already prevented upstream by the shared _connect fix;
# this proves the evidence label is honest, not merely "collector_error").
# ---------------------------------------------------------------------------

class TestCollectorHostKeyNotTrustedLabeling:

    def test_host_key_not_trusted_is_a_distinct_trust_failure(self, monkeypatch):
        from configuration import checkpoint_config_collector as collector
        from configuration.checkpoint_config_collector import PhysicalTarget

        def fake_connect(*a, **kw):
            raise HostKeyNotTrustedError("no_usable_host_keys_loaded")

        monkeypatch.setattr(collector, "_connect", fake_connect)
        target = PhysicalTarget(
            entity_type="standalone_gateway", device="SYNTH-CP-GW", management_ip="192.0.2.72",
            object_type="gateway", cma=None, cluster_group_id=None, cluster_display_name=None,
            presentation_group_id=None, presentation_group_label=None, presentation_group_source=None,
            management_state="up", selection_source="management_discovery",
        )
        rows = collector._collect_host(
            target, username="admin", secret="s3cr3t", strict_host_key=True,
            connect_timeout=1, command_timeout=1, store=MagicMock(),
        )
        host_row = rows[0]
        assert host_row["error_class"] == "host_key_not_trusted"
        assert host_row["failure_family"] == "trust_failure"
        assert host_row["error_class"] != "collector_error"
        assert host_row["failure_family"] != "reachability_failure"
