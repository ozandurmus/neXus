"""OP.0b S8 — device session / execution context architecture.

Production invariant this file makes hard to regress:

    ONE DEVICE -> ONE AUTHENTICATED SESSION -> ONE EXECUTION CONTEXT
    -> ONE PRECOMPUTED PLAN -> ALL APPROVED READS -> ONE CLEAN CLOSE

Real-environment finding that motivated it (S8-A, ClusterXL pair): the
device-side log showed **one extra CLI session per read** — a bounded
8-read battery produced 8 additional device-side `ver` CLI sessions, one
per command. Root cause: every read opened its exec channel with a PTY
(`_run_exec`'s long-standing default), and a PTY-backed channel makes the
device run its per-session login/CLI initialization on *every* command.
The authenticated SSH connection count was already correct (one per
member); what was wrong was the per-command device-side work hanging off
it, which is exactly the kind of amplification a network-management
product must not inflict on shared infrastructure.

Covered here (task §15):

CHECK POINT non-VSX
  - authenticated SSH connect count / member == 1
  - close count / member == 1
  - no nested `SSHClient` creation below the session layer
  - exec channels == scheduled reads (no per-command amplification)
  - no PTY on the preflight exec path (the `ver` amplification guard)
  - version/platform evidence collected once; A6 and A8 selected once
  - every read runs on the same session object; no command-level reconnect
  - static execution semantics (Expert shell, `clish -c`, `vsenv`) are
    never runtime-probed

CHECK POINT VSX
  - authenticated SSH connect count / member == 1, B1 on the same session
  - no SSH connection per VSID

PALO ALTO
  - one authenticated API context / member; P1/P2/P4 reuse it verbatim

No device is contacted: `paramiko.SSHClient` is replaced by a counting
fake whose transport/channel serve fixture output, so the **real**
`_run_exec`/`_connect`/`collect_member` code paths execute.
"""
from __future__ import annotations

import inspect
from unittest.mock import patch

import paramiko
import pytest

import checkpoint.preflight_collector as pc
from checkpoint.cp_preflight_battery import COMMAND_TEXT, CPPreflightRead
from checkpoint.preflight_collector import CPPhysicalMemberTarget, run_cp_preflight
from utils.failover.preflight_model import FactState

pytestmark = pytest.mark.configuration


# ---------------------------------------------------------------------------
# Fixture device: a genuine ClusterXL HA member
# ---------------------------------------------------------------------------

_FIXTURES = {
    CPPreflightRead.A1_HOSTNAME: "gw-member-a",
    CPPreflightRead.A2_VERSION: "This is Check Point's software version R81.10\nOS build 123",
    CPPreflightRead.A3_CPHAPROB_STAT: (
        "Cluster Mode:   High Availability (Active Up)\n"
        "Number   Unique Address  Assigned Load   State             Name\n"
        "1 (local) 10.10.10.1      100%            ACTIVE            gw-member-a\n"
        "2         10.10.10.2      0%              STANDBY           gw-member-b\n"
    ),
    CPPreflightRead.A4_LINK_IF: "eth1  UP  (secured, sync, HA)",
    CPPreflightRead.A5_PNOTE_LIST: "Current State: OK (Actual)",
    CPPreflightRead.A6_SYNCSTAT: "Sync Status: OK",
    CPPreflightRead.A6_PSTAT: "Sync Status: OK",
    CPPreflightRead.A7_FW_STAT: "Policy name: Standard_Policy",
    CPPreflightRead.A8_CLISH_FAILOVER: "Cluster failover count: 2",
    CPPreflightRead.A8_EXPERT_FAILOVER: "Failover count: 2",
    CPPreflightRead.B1_VSX_STAT: "VSID 0    VS0        Active",
}

_TEXT_TO_READ = {text: read for read, text in COMMAND_TEXT.items()}


class _Recorder:
    """Device-side bookkeeping shared by every fake client in one run."""

    def __init__(self) -> None:
        self.clients: list[_FakeSSHClient] = []
        self.connects: list[str] = []
        self.closes = 0
        self.channels = 0
        self.pty_requests = 0
        self.commands: list[str] = []


class _FakeChannel:
    def __init__(self, rec: _Recorder) -> None:
        self._rec = rec
        self._out = b""
        self._sent = False

    def get_pty(self, **_kw):
        self._rec.pty_requests += 1

    def exec_command(self, command: str) -> None:
        self._rec.commands.append(command)
        read = _TEXT_TO_READ.get(command)
        self._out = (_FIXTURES.get(read, "") if read else "").encode()

    def recv_ready(self) -> bool:
        return bool(self._out) and not self._sent

    def recv(self, _n: int) -> bytes:
        self._sent = True
        return self._out

    def recv_stderr_ready(self) -> bool:
        return False

    def exit_status_ready(self) -> bool:
        return True

    def recv_exit_status(self) -> int:
        return 0

    def close(self) -> None:
        pass


class _FakeTransport:
    def __init__(self, rec: _Recorder) -> None:
        self._rec = rec

    def is_active(self) -> bool:
        return True

    def open_session(self, **_kw) -> _FakeChannel:
        self._rec.channels += 1
        return _FakeChannel(self._rec)

    def get_remote_server_key(self):
        return None


class _FakeSSHClient:
    """Counts authentication/close; serves the battery over its transport."""

    rec: _Recorder | None = None

    def __init__(self) -> None:
        assert _FakeSSHClient.rec is not None, "recorder not installed"
        self._rec = _FakeSSHClient.rec
        self._rec.clients.append(self)
        self._policy = None

    def load_system_host_keys(self, filename=None):
        pass

    def set_missing_host_key_policy(self, policy):
        self._policy = policy

    def connect(self, host, **_kw):
        self._rec.connects.append(str(host))

    def get_transport(self) -> _FakeTransport:
        return _FakeTransport(self._rec)

    def close(self) -> None:
        self._rec.closes += 1


def _run(monkeypatch, *, unit_type: str = "clusterxl", members: int = 2) -> _Recorder:
    rec = _Recorder()
    _FakeSSHClient.rec = rec
    monkeypatch.setattr(paramiko, "SSHClient", _FakeSSHClient)
    targets = [
        CPPhysicalMemberTarget("member-a", "gw-member-a", "192.0.2.10"),
        CPPhysicalMemberTarget("member-b", "gw-member-b", "192.0.2.11"),
    ][:members]
    snapshot = run_cp_preflight(
        operational_entity_id="unit-1", unit_type=unit_type, members=targets,
        username="admin", secret="s3cr3t",  # pragma: allowlist secret
    )
    rec.snapshot = snapshot
    return rec


# ---------------------------------------------------------------------------
# Check Point non-VSX: one session, one plan, no amplification
# ---------------------------------------------------------------------------

class TestCheckPointSessionInvariants:

    def test_one_authenticated_connect_per_member(self, monkeypatch):
        rec = _run(monkeypatch, members=2)
        assert len(rec.connects) == 2, "exactly one authenticated connect per member"
        assert rec.connects == ["192.0.2.10", "192.0.2.11"]

    def test_one_close_per_member(self, monkeypatch):
        rec = _run(monkeypatch, members=2)
        assert rec.closes == 2

    def test_no_nested_ssh_client_below_the_session_layer(self, monkeypatch):
        """Only the session/transport layer may construct a client: one
        client object per member, not one per command."""
        rec = _run(monkeypatch, members=2)
        assert len(rec.clients) == 2

    def test_exec_channels_equal_scheduled_reads_no_amplification(self, monkeypatch):
        """The whole point: 8 approved reads must cost 8 device operations —
        not 8 plus a per-command CLI initialization each."""
        rec = _run(monkeypatch, members=1)
        assert rec.channels == 8
        assert len(rec.commands) == 8

    def test_no_pty_requested_on_the_preflight_path(self, monkeypatch):
        """A PTY-backed channel is what made the device run its login/CLI
        initialization once per command (S8-A `ver` amplification)."""
        rec = _run(monkeypatch, members=2)
        assert rec.pty_requests == 0

    def test_only_approved_battery_text_reaches_the_device(self, monkeypatch):
        rec = _run(monkeypatch, members=1)
        approved = set(COMMAND_TEXT.values())
        assert all(c in approved for c in rec.commands), rec.commands

    def test_version_evidence_collected_once_per_member(self, monkeypatch):
        rec = _run(monkeypatch, members=1)
        assert rec.commands.count(COMMAND_TEXT[CPPreflightRead.A2_VERSION]) == 1

    def test_no_repeated_version_probe_amplification(self, monkeypatch):
        """Guard against a future helper silently turning one member run back
        into repeated version/capability probing."""
        rec = _run(monkeypatch, members=2)
        for text in COMMAND_TEXT.values():
            assert rec.commands.count(text) <= 2, f"{text!r} issued more than once per member"

    def test_execution_context_resolved_exactly_once_per_member(self, monkeypatch):
        sessions: list = []
        real_factory = pc.make_real_member_session

        def spy(ssh, **kw):
            session = real_factory(ssh, **kw)
            sessions.append(session)
            return session

        monkeypatch.setattr(pc, "make_real_member_session", spy)
        _run(monkeypatch, members=2)

        assert len(sessions) == 2, "one session/execution context per member"
        for session in sessions:
            assert session.execution_context_resolutions == 1
            assert session.command_invocations == 8, "every read on the same session"

    def test_a6_and_a8_variants_selected_once_from_cached_evidence(self, monkeypatch):
        a6_calls: list = []
        a8_calls: list = []
        monkeypatch.setattr(pc, "resolve_a6_form", lambda v: (a6_calls.append(v), CPPreflightRead.A6_SYNCSTAT)[1])
        monkeypatch.setattr(pc, "resolve_a8_form", lambda p: (a8_calls.append(p), CPPreflightRead.A8_CLISH_FAILOVER)[1])
        _run(monkeypatch, members=2)
        assert len(a6_calls) == 2, "A6 selected once per member, not per command"
        assert len(a8_calls) == 2, "A8 selected once per member, not per command"

    def test_second_context_resolution_is_a_no_op(self):
        session = pc.MemberSession(physical_device_identity="m", _run_command=lambda _t: {})
        session.resolve_execution_context(sw_version="R81.10", platform_family="gaia")
        first = (session.a6_form, session.a8_form)
        session.resolve_execution_context(sw_version="R80.40", platform_family="other")
        assert session.execution_context_resolutions == 1
        assert (session.a6_form, session.a8_form) == first, "plan is fixed once per session"

    def test_battery_still_produces_real_evidence_without_a_pty(self, monkeypatch):
        """The efficiency fix must not cost evidence: A3's mode/role still
        land as KNOWN facts over the plain non-interactive channel."""
        rec = _run(monkeypatch, members=2)
        member = rec.snapshot.members[0]
        facts = {f.name: f for f in member.own_facts}
        assert facts["ha_cluster_mode"].state is FactState.KNOWN
        assert facts["ha_cluster_mode"].value == "ha_new_mode"
        assert facts["ha_local_role"].state is FactState.KNOWN


# ---------------------------------------------------------------------------
# Static execution semantics are contract, not runtime discovery
# ---------------------------------------------------------------------------

class TestNoRuntimeDiscoveryOfStaticSemantics:

    def test_no_shell_detection_probe_in_the_preflight_path(self, monkeypatch):
        """Expert login shell / `clish -c` / `vsenv` are platform contract.
        The battery must not spend a device operation rediscovering them."""
        rec = _run(monkeypatch, members=1)
        joined = " ".join(rec.commands)
        for probe in ("$SHELL", "id -un", "echo $0", "uname"):
            assert probe not in joined, f"static execution semantics probed at runtime: {probe!r}"

    def test_session_layer_owns_the_transport_not_the_collector(self):
        """`collect_member` consumes an execution context; it never
        constructs a client or authenticates."""
        src = inspect.getsource(pc.collect_member)
        for forbidden in ("SSHClient", "_connect(", "connect(", "paramiko"):
            assert forbidden not in src, f"collector must not own transport lifecycle: {forbidden!r}"

    def test_only_the_session_factory_binds_a_transport(self):
        src = inspect.getsource(pc)
        assert src.count("_run_exec(") == 1, "exactly one place binds the exec primitive"
        assert "use_pty=False" in src


# ---------------------------------------------------------------------------
# Check Point VSX: same physical-member session for B1
# ---------------------------------------------------------------------------

class TestVsxSessionInvariants:

    def test_one_connect_per_member_and_b1_on_the_same_session(self, monkeypatch):
        rec = _run(monkeypatch, unit_type="vsx", members=2)
        assert len(rec.connects) == 2, "no extra connection for the VSX read"
        assert len(rec.clients) == 2
        assert rec.commands.count(COMMAND_TEXT[CPPreflightRead.B1_VSX_STAT]) == 2

    def test_no_ssh_connection_per_vsid(self, monkeypatch):
        rec = _run(monkeypatch, unit_type="vsx", members=1)
        assert len(rec.connects) == 1
        assert rec.channels == 9, "8 physical reads + B1, all on one session"

    def test_vsx_adds_no_pty_and_no_reconnect(self, monkeypatch):
        rec = _run(monkeypatch, unit_type="vsx", members=2)
        assert rec.pty_requests == 0
        assert rec.closes == 2


# ---------------------------------------------------------------------------
# Palo Alto: one authenticated API context per member, reused
# ---------------------------------------------------------------------------

class TestPanAuthenticatedContextInvariants:

    def _collect(self, monkeypatch):
        import panorama.preflight_collector as pan
        from panorama.preflight_collector import PANPhysicalMemberTarget, collect_member

        keys: list = []
        used: list = []

        def fake_keygen(_cfg, host, *, verify, timeout):
            keys.append(host)
            return "synthetic-key"

        def fake_api_post(host, key, data, *, verify, timeout, operation):
            used.append(key)
            import xml.etree.ElementTree as ET
            return ET.fromstring("<response status='success'><result/></response>")

        def fake_system_info(host, key, *, verify, timeout):
            used.append(key)
            return {"serial": "0001A", "hostname": "fw-a"}

        monkeypatch.setattr(pan, "get_firewall_api_key", fake_keygen)
        monkeypatch.setattr(pan, "api_post", fake_api_post)
        monkeypatch.setattr(pan, "get_direct_system_info", fake_system_info)

        collect_member(
            username="u", secret="s",  # pragma: allowlist secret
            target=PANPhysicalMemberTarget(
                physical_device_identity="member-token-a", expected_serial="0001A",
                management_ip="192.0.2.20",
            ),
            preflight_run_id="run-1", operational_entity_id="unit-1",
        )
        return keys, used

    def test_one_authenticated_context_per_member(self, monkeypatch):
        keys, _used = self._collect(monkeypatch)
        assert len(keys) == 1, "authentication happens once per member, not per request"

    def test_every_read_reuses_the_same_context(self, monkeypatch):
        _keys, used = self._collect(monkeypatch)
        assert used, "expected P1/P2/P4 requests"
        assert set(used) == {"synthetic-key"}, "no per-request re-authentication"

    def test_authentication_is_not_repeated_per_request(self, monkeypatch):
        keys, used = self._collect(monkeypatch)
        assert len(keys) == 1 < len(used), "many requests, one authentication"
