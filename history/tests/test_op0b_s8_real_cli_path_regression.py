"""OP.0b S8 — real CLI-path regression (no network).

Every other preflight test stubs `run_cp_preflight` or `MemberSession`, so
none of them exercised what the operator's `--cp-ha-preflight-check` run
actually does. This file closes that gap: it drives the **actual S7.5 CLI
call graph**

    parsed CLI args -> application.workflows.preflight.cp_ha_preflight_check
      -> local target resolution -> run_cp_preflight -> _connect -> _run_exec
      -> canonical CP parsers -> S3 projection -> S5 PreflightFact
      -> PreflightSnapshot -> compute_ha_readiness

with **only `paramiko.SSHClient` replaced**. Everything below the transport
is the real production code, and the device fixtures are sanitized copies of
the real vendor output shapes S8-A observed on the approved ClusterXL pair:

  - `cphaprob stat` reporting `High Availability (Active Up) with IGMP
    Membership`, an `ID` (not `Number`) column header, local member
    `STANDBY` and peer `ACTIVE`
  - `fw stat` as its real column table (`HOST POLICY DATE`), which the A7
    parser previously did not recognize at all

Every read runs on a PTY-backed channel: the Check Point profile it loads
is what puts `$CPDIR/bin`/`$FWDIR/bin` on `$PATH`, without which every bare
Expert read silently fails while `clish -c` reads keep working.

Identities are synthetic throughout: RFC 5737 addresses, invented member
names, no real hostname, serial, policy name or timestamp.

Asserted (task §8): one authenticated SSH connect per member, zero PTY
requests, no command outside the approved battery reaching the device (the
login-init amplification guard), A2 collected once, A3 recognized as HA with
the local role preserved, a coherent snapshot, and
`ha_mode_not_established` absent from every readiness check.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import paramiko
import pytest

from application.cli import build_parser
from application.context import ApplicationContext
from checkpoint.cp_preflight_battery import COMMAND_TEXT, CPPreflightRead
from utils.failover.preflight_model import FactState

pytestmark = pytest.mark.configuration


# ---------------------------------------------------------------------------
# Sanitized real-shape device fixtures
# ---------------------------------------------------------------------------

_A3_LOCAL_STANDBY = """Cluster Mode:   High Availability (Active Up) with IGMP Membership

ID         Unique Address  Assigned Load   State          Name

1 (local)  192.0.2.101     0%              STANDBY        gw-member-a
2          192.0.2.102     100%            ACTIVE         gw-member-b


Active PNOTEs: None

Last member state change event:
   Event Code:                 CLUS-114802
   State change:               DOWN -> STANDBY
   Reason for state change:    There is already an ACTIVE member in the cluster (member 2)
"""

_A3_LOCAL_ACTIVE = _A3_LOCAL_STANDBY.replace(
    "1 (local)  192.0.2.101     0%              STANDBY        gw-member-a\n"
    "2          192.0.2.102     100%            ACTIVE         gw-member-b",
    "1          192.0.2.101     0%              STANDBY        gw-member-a\n"
    "2 (local)  192.0.2.102     100%            ACTIVE         gw-member-b",
)

#: Real `fw stat` shape: a column table, not a `Policy name:` line.
_A7_FW_STAT = (
    "HOST      POLICY           DATE\n"
    "localhost SynthPolicy      1Jan2026 00:00:00 :  [>bond1.100] [<bond1.100]\n"
)

_A2_VERSION = "This is Check Point's software version R81.10\nOS build 123"
_A4_LINK = "eth1  UP  (secured, sync, HA)\neth2  UP  (non sync)"
_A5_PNOTE = "There are no pnotes in problem state"
_A6_SYNC = "Sync Status: OK"
_A8_FAILOVER = "Cluster failover count: 2\nReason: cpstop\nLast failover event: 3 hours ago"

_TEXT_TO_READ = {text: read for read, text in COMMAND_TEXT.items()}


def _fixture_for(read: CPPreflightRead, hostname: str, a3: str) -> str:
    return {
        CPPreflightRead.A1_HOSTNAME: hostname,
        CPPreflightRead.A2_VERSION: _A2_VERSION,
        CPPreflightRead.A3_CPHAPROB_STAT: a3,
        CPPreflightRead.A4_LINK_IF: _A4_LINK,
        CPPreflightRead.A5_PNOTE_LIST: _A5_PNOTE,
        CPPreflightRead.A6_SYNCSTAT: _A6_SYNC,
        CPPreflightRead.A6_PSTAT: _A6_SYNC,
        CPPreflightRead.A7_FW_STAT: _A7_FW_STAT,
        CPPreflightRead.A8_CLISH_FAILOVER: _A8_FAILOVER,
        CPPreflightRead.A8_EXPERT_FAILOVER: _A8_FAILOVER,
        CPPreflightRead.B1_VSX_STAT: "VSID 0    VS0        Active",
    }.get(read, "")


# ---------------------------------------------------------------------------
# Transport double: the ONLY thing replaced
# ---------------------------------------------------------------------------

class _Device:
    """Per-run device-side bookkeeping for the safe counts S8-A reports."""

    def __init__(self) -> None:
        self.connects: list[str] = []
        self.closes = 0
        self.shells = 0
        self.shell_closes = 0
        self.channels = 0
        self.pty_requests = 0
        self.commands: list[str] = []
        #: What the device actually ran, including any CLI initialization a
        #: non-interactive exec channel would have cost it.
        self.device_executed: list[str] = []
        #: One entry per inter-command pacing wait.
        self.waits: list[float] = []

    #: Per-member hostname served by A1, keyed by management IP.
    HOSTS = {"10.0.0.1": ("gw-member-a", _A3_LOCAL_STANDBY),
             "10.0.0.2": ("gw-member-b", _A3_LOCAL_ACTIVE)}


_FRAMING_RE = re.compile(r";\s*echo\s+(\S*?)\$\?(\S*)\s*$")


class _Shell:
    """One persistent Expert shell on the real CLI path."""

    def __init__(self, device: _Device, host: str) -> None:
        self._device = device
        self._host = host
        hostname, _a3 = _Device.HOSTS.get(host, ("gw-member-a", _A3_LOCAL_STANDBY))
        self._buf = f"[Expert@{hostname}:0]# ".encode()
        self._pending = ""

    def settimeout(self, _t):
        pass

    def send(self, data: str) -> int:
        self._pending += data
        while "\n" in self._pending:
            line, self._pending = self._pending.split("\n", 1)
            self._handle(line.strip())
        return len(data)

    def _handle(self, line: str) -> None:
        hostname, a3 = _Device.HOSTS.get(self._host, ("gw-member-a", _A3_LOCAL_STANDBY))
        prompt = f"[Expert@{hostname}:0]# "
        if not line:
            self._buf += prompt.encode()
            return
        marker_open = marker_close = ""
        match = _FRAMING_RE.search(line)
        if match:
            marker_open, marker_close = match.group(1), match.group(2)
            line = line[: match.start()].strip()
        self._device.commands.append(line)
        self._device.device_executed.append(line)
        read = _TEXT_TO_READ.get(line)
        out = _fixture_for(read, hostname, a3) if read else ""
        status = 0 if read is not None and out else (0 if read is not None else 127)
        payload = (out + "\n") if out else ""
        if marker_open or marker_close:
            payload += f"{marker_open}{status}{marker_close}\n"
        payload += prompt
        self._buf += payload.encode()

    def recv_ready(self) -> bool:
        return bool(self._buf)

    def recv(self, _n: int) -> bytes:
        out, self._buf = self._buf, b""
        return out

    def recv_stderr_ready(self) -> bool:
        return False

    def close(self) -> None:
        self._device.shell_closes += 1


class _Channel:
    def __init__(self, device: _Device, host: str) -> None:
        self._device = device
        self._host = host
        self._out = b""
        self._sent = False

    def get_pty(self, **_kw):
        self._device.pty_requests += 1

    def exec_command(self, command: str) -> None:
        self._device.commands.append(command)
        read = _TEXT_TO_READ.get(command)
        hostname, a3 = _Device.HOSTS.get(self._host, ("gw-member-a", _A3_LOCAL_STANDBY))
        self._out = (_fixture_for(read, hostname, a3) if read else "").encode()

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


class _Transport:
    def __init__(self, device: _Device, host: str) -> None:
        self._device = device
        self._host = host

    def is_active(self) -> bool:
        return True

    def open_session(self, **_kw) -> _Channel:
        self._device.channels += 1
        return _Channel(self._device, self._host)

    def get_remote_server_key(self):
        return None


class _SSHClient:
    device: _Device | None = None

    def __init__(self) -> None:
        assert _SSHClient.device is not None
        self._device = _SSHClient.device
        self._host = ""

    def load_system_host_keys(self, filename=None):
        pass

    def set_missing_host_key_policy(self, policy):
        pass

    def connect(self, host, **_kw):
        self._host = str(host)
        self._device.connects.append(self._host)

    def invoke_shell(self, **_kw) -> _Shell:
        self._device.shells += 1
        return _Shell(self._device, self._host)

    def get_transport(self) -> _Transport:
        return _Transport(self._device, self._host)

    def close(self) -> None:
        self._device.closes += 1


# ---------------------------------------------------------------------------
# Real CLI-path driver
# ---------------------------------------------------------------------------

_REPO_ROOT = Path(__file__).resolve().parents[1]


class _RuntimePaths:
    def __init__(self, root: Path):
        # Templates/static come from the real repository; every artifact
        # written by the run lands in the temporary root.
        self.repository_root = _REPO_ROOT
        self.runtime_root = root
        self.data_root = root / "data"
        self.output_root = root
        self.logs_root = root / "logs"


def _write_cp_fixture(output_root: Path) -> None:
    output_root.mkdir(parents=True, exist_ok=True)
    status = [
        {"device": "cp-a", "management_ip": "10.0.0.1", "object_type": "cluster_member"},
        {"device": "cp-b", "management_ip": "10.0.0.2", "object_type": "cluster_member"},
    ]
    (output_root / "cp_telemetry.json").write_text(
        json.dumps({"remote_command_status": status}), encoding="utf-8")
    cp_rows = [
        {"device": "cp-a", "source": "cp", "cluster_topology": {"group_id": "grp1"}},
        {"device": "cp-b", "source": "cp", "cluster_topology": {"group_id": "grp1"}},
    ]
    (output_root / "cp.json").write_text(json.dumps(cp_rows), encoding="utf-8")
    (output_root / "vsx.json").write_text(json.dumps([]), encoding="utf-8")
    (output_root / "unified.json").write_text(json.dumps(cp_rows), encoding="utf-8")


def _drive_cli(tmp_path: Path, monkeypatch, capsys) -> tuple[_Device, str]:
    """Run the real CLI workflow end to end with only the transport faked."""
    _write_cp_fixture(tmp_path)
    monkeypatch.setenv("SECURITYEXPERT_PRINCIPAL", "tester")
    monkeypatch.setenv("SECURITYEXPERT_SECRET", "s3cret")  # pragma: allowlist secret
    monkeypatch.setenv("SECURITYEXPERT_CP_MDS_ENDPOINT", "mds.example.invalid")

    device = _Device()
    _SSHClient.device = device
    monkeypatch.setattr(paramiko, "SSHClient", _SSHClient)

    import checkpoint.preflight_collector as pc
    # §5: mock the sleeper so the suite never waits real seconds; every
    # pacing call is still recorded on the device for assertion.
    monkeypatch.setattr(pc, "INTER_COMMAND_DELAY_SECONDS", 0)
    _real_sleep = pc.time.sleep

    def _recording_sleep(seconds):
        # Only the pacing value is recorded; the shell's own read-loop poll
        # sleeps must keep working or nothing completes.
        if seconds == pc.INTER_COMMAND_DELAY_SECONDS:
            device.waits.append(seconds)
            return
        _real_sleep(seconds)

    monkeypatch.setattr(pc.time, "sleep", _recording_sleep)

    import application.workflows.preflight as preflight_wf
    from utils.collection_executor import RuntimeCollectionServices

    args = build_parser().parse_args(
        ["--cp-ha-preflight-check", "--cp-preflight-targets", "cp-a,cp-b"])
    ctx = ApplicationContext(args=args, parser=build_parser(), provenance="manual")
    ctx.runtime_paths = _RuntimePaths(tmp_path)
    ctx.services = RuntimeCollectionServices()

    rc = preflight_wf.cp_ha_preflight_check(ctx)
    assert rc == 0, "the real CLI workflow must complete"
    return device, capsys.readouterr().out


# ---------------------------------------------------------------------------
# Session/transport invariants on the real path
# ---------------------------------------------------------------------------

def _embedded_readiness(html_path: Path) -> dict:
    html = html_path.read_text(encoding="utf-8")
    marker = "failoverReadinessData: "
    payload, _ = json.JSONDecoder().raw_decode(html, html.index(marker) + len(marker))
    return payload


_CLI_CHECK_RE = re.compile(r"^\s{2}(\S+)\s+(\S+)\s+(\S+)")


class TestOperatorReportParity:
    """OP.0b S7.5 console closure on the REAL invocation path: the report the
    preflight regenerates is a projection of the very record the CLI printed."""

    def test_report_is_regenerated_from_the_same_invocation(self, tmp_path, monkeypatch, capsys):
        _device, out = _drive_cli(tmp_path, monkeypatch, capsys)
        assert "Operator report regenerated from this invocation's readiness" in out
        assert (tmp_path / "index.html").exists()

    def test_cli_and_report_agree_on_all_seven_checks_and_mode(self, tmp_path, monkeypatch, capsys):
        _device, out = _drive_cli(tmp_path, monkeypatch, capsys)
        # What the operator saw on the CLI, parsed from the safe summary.
        cli: dict[str, tuple[str, str]] = {}
        in_checks = False
        for line in out.splitlines():
            if line.strip() == "Checks:":
                in_checks = True
                continue
            if in_checks:
                m = _CLI_CHECK_RE.match(line)
                if not m:
                    if line.strip():
                        in_checks = False
                    continue
                cli[m.group(1)] = (m.group(2), m.group(3))
        assert len(cli) == 7, out
        unit_id = re.search(r"Operational unit:\s+(\S+)", out).group(1)

        payload = _embedded_readiness(tmp_path / "index.html")
        unit = next(u for u in payload["units"] if u["unit_id"] == unit_id)
        web = {c["id"]: (c["status"], c["reason"]) for c in unit["checks"]}
        assert web == cli, "CLI and report must be the same record"
        assert unit_id in payload["preflight"]["applied"]
        assert unit["cluster_mode"] == "ha_new_mode", "fresh A3 mode, never legacy unknown"


class TestRealCliPathSessionInvariants:

    def test_one_authenticated_connect_per_member(self, tmp_path, monkeypatch, capsys):
        device, _out = _drive_cli(tmp_path, monkeypatch, capsys)
        assert device.connects == ["10.0.0.1", "10.0.0.2"]

    def test_one_close_per_member(self, tmp_path, monkeypatch, capsys):
        device, _out = _drive_cli(tmp_path, monkeypatch, capsys)
        assert device.closes == 2

    def test_no_terminal_allocated_on_the_real_path(self, tmp_path, monkeypatch, capsys):
        """One-shot reads allocate no terminal on the real CLI path.

        A PTY was briefly suspected of being what made the bare Expert reads
        resolvable. The device's own audit trail disproves it -- those reads
        never reach a shell (see `classify_execution_context_gap`) -- so the
        real path stays on the cheaper PTY-less channel."""
        device, _out = _drive_cli(tmp_path, monkeypatch, capsys)
        assert device.pty_requests == 0

    def test_no_login_init_amplification(self, tmp_path, monkeypatch, capsys):
        """Only approved battery text reaches the device: 8 reads per member,
        nothing else -- no `ver`, no shell probe, no capability rediscovery."""
        device, _out = _drive_cli(tmp_path, monkeypatch, capsys)
        approved = set(COMMAND_TEXT.values())
        assert all(c in approved for c in device.commands), device.commands
        assert len(device.commands) == 16, "8 approved reads x 2 members"
        assert device.channels == 0, "no per-command exec channel"
        assert device.shells == 2 and device.shell_closes == 2
        assert "ver" not in device.device_executed, device.device_executed

    def test_pacing_on_the_real_path(self, tmp_path, monkeypatch, capsys):
        """8 reads x 2 members = 16 reads, but pacing is per member and only
        BETWEEN reads: 7 waits per member, none before a member's first read
        and none after its last."""
        device, _out = _drive_cli(tmp_path, monkeypatch, capsys)
        assert len(device.commands) == 16
        assert len(device.waits) == 14, "7 waits per member, none trailing"

    def test_pacing_never_reconnects_or_retries(self, tmp_path, monkeypatch, capsys):
        """Pacing must never become retry or reconnect authority: the same
        session is retained across every wait."""
        device, _out = _drive_cli(tmp_path, monkeypatch, capsys)
        assert len(device.connects) == 2, "no reconnect across pacing"
        assert device.shells == 2, "the same Expert shell spans every wait"
        assert len(set(device.commands)) == 8, "the same 8 approved reads, per member"
        assert len(device.commands) == 16, "no read re-issued"

    def test_version_collected_once_per_member(self, tmp_path, monkeypatch, capsys):
        device, _out = _drive_cli(tmp_path, monkeypatch, capsys)
        assert device.commands.count(COMMAND_TEXT[CPPreflightRead.A2_VERSION]) == 2

    def test_no_command_issued_more_than_once_per_member(self, tmp_path, monkeypatch, capsys):
        device, _out = _drive_cli(tmp_path, monkeypatch, capsys)
        for text in set(device.commands):
            assert device.commands.count(text) <= 2, f"{text!r} amplified"


# ---------------------------------------------------------------------------
# A3 semantic survives the real path end to end
# ---------------------------------------------------------------------------

class TestRealCliPathA3Semantics:

    def test_readiness_never_reports_mode_not_established(self, tmp_path, monkeypatch, capsys):
        """The exact S8-A symptom, driven through the real CLI path."""
        _device, out = _drive_cli(tmp_path, monkeypatch, capsys)
        assert "ha_mode_not_established" not in out, out

    def test_safe_summary_reports_a_coherent_applied_snapshot(self, tmp_path, monkeypatch, capsys):
        _device, out = _drive_cli(tmp_path, monkeypatch, capsys)
        assert "Coherent:                True" in out
        assert "Snapshot applied:        True" in out
        assert "Bounded members:         2" in out

    def test_cluster_mode_and_roles_survive_to_the_facts(self, tmp_path, monkeypatch, capsys):
        """`High Availability (Active Up) with IGMP Membership` -> ha_new_mode,
        with each member's own role preserved (local STANDBY / peer ACTIVE)."""
        from checkpoint.preflight_collector import CPPhysicalMemberTarget, run_cp_preflight

        device = _Device()
        _SSHClient.device = device
        monkeypatch.setattr(paramiko, "SSHClient", _SSHClient)

        snapshot = run_cp_preflight(
            operational_entity_id="grp1", unit_type="clusterxl",
            members=[
                CPPhysicalMemberTarget("member-a", "gw-member-a", "10.0.0.1"),
                CPPhysicalMemberTarget("member-b", "gw-member-b", "10.0.0.2"),
            ],
            username="tester", secret="s3cret",  # pragma: allowlist secret
        )
        roles = []
        for member in snapshot.members:
            facts = {f.name: f for f in member.own_facts}
            assert facts["ha_cluster_mode"].state is FactState.KNOWN
            assert facts["ha_cluster_mode"].value == "ha_new_mode"
            assert facts["ha_local_role"].state is FactState.KNOWN
            roles.append(facts["ha_local_role"].value)
        assert sorted(roles) == ["ACTIVE", "STANDBY"], roles

    def test_installed_policy_parses_from_the_real_fw_stat_table(self, tmp_path, monkeypatch, capsys):
        """A7's real output is a column table; the pre-existing regex matched
        nothing on a real gateway, so parity evidence was silently lost."""
        from checkpoint.cp_preflight_extraction import parse_fw_stat_policy

        parsed = parse_fw_stat_policy(_A7_FW_STAT)
        assert parsed["observed"] is True
        assert parsed["policy_name"] == "SynthPolicy"

    def test_legacy_policy_name_shape_still_supported(self):
        from checkpoint.cp_preflight_extraction import parse_fw_stat_policy

        assert parse_fw_stat_policy("Policy name: Standard_Policy") == {
            "observed": True, "policy_name": "Standard_Policy"}

    def test_absent_policy_column_fails_closed(self):
        from checkpoint.cp_preflight_extraction import parse_fw_stat_policy

        assert parse_fw_stat_policy(
            "HOST      POLICY           DATE\nlocalhost -    1Jan2026 00:00:00") == {
            "observed": False, "policy_name": None}


# ---------------------------------------------------------------------------
# Per-read outcome disclosure: a readiness reason must never be the only
# thing an operator gets when the reads themselves never produced evidence.
# ---------------------------------------------------------------------------

class TestReadOutcomeDisclosure:

    def _clish_only_device(self, monkeypatch):
        """A session that behaves like the real one S8-A hit: `clish -c '...'`
        reads answer, bare Expert reads produce nothing."""
        clish_reads = {CPPreflightRead.A1_HOSTNAME, CPPreflightRead.A2_VERSION,
                       CPPreflightRead.A8_CLISH_FAILOVER}
        original = _Shell._handle

        def only_clish(self, line: str) -> None:
            match = _FRAMING_RE.search(line)
            bare = line[: match.start()].strip() if match else line
            if bare and _TEXT_TO_READ.get(bare) not in clish_reads:
                # Clish rejects the bare Expert read: no output, non-zero status.
                marker_open, marker_close = (match.group(1), match.group(2)) if match else ("", "")
                self._device.commands.append(bare)
                self._device.device_executed.append("ver")
                payload = "CLINFR0329  Invalid command.\n"
                if marker_open or marker_close:
                    payload += f"{marker_open}1{marker_close}\n"
                self._buf += payload.encode()
                return
            original(self, line)

        monkeypatch.setattr(_Shell, "_handle", only_clish)

    def test_healthy_run_reports_every_read_successful(self, tmp_path, monkeypatch, capsys):
        _device, out = _drive_cli(tmp_path, monkeypatch, capsys)
        assert "Reads (approved battery, safe outcome only):" in out
        # A per-read disclosure line, not a blanket substring check: CP pilot
        # readiness-policy amendment means a fully healthy run's own
        # readiness summary now legitimately prints "SAFE_TO_FAILOVER",
        # which contains "FAIL" as a substring without any read having
        # failed -- match the sibling test's own convention instead.
        assert not any(ln.strip().startswith(("FAIL", "GAP")) for ln in out.splitlines()), out
        assert "produced no usable evidence" not in out

    def test_expert_reads_failing_are_named_not_hidden_behind_readiness(
        self, tmp_path, monkeypatch, capsys
    ):
        """The S8-A symptom: readiness says `ha_mode_not_established` for six
        checks, which alone cannot tell an operator whether the device
        reported an uninterpretable mode or whether those reads never ran.
        The per-read disclosure must name them."""
        self._clish_only_device(monkeypatch)
        _device, out = _drive_cli(tmp_path, monkeypatch, capsys)

        assert "ha_mode_not_established" in out, "precondition: the symptom reproduces"
        assert "produced no usable evidence" in out, out
        # The Expert-shell reads are the ones named -- as an execution-context
        # gap now that the battery runs in one persistent Expert shell, since
        # the device rejected them before any binary ran.
        named = [ln for ln in out.splitlines()
                 if ln.strip().startswith(("FAIL", "GAP"))]
        assert named, out
        assert "Expert-shell execution context could not be confirmed" in out
        assert any("cphaprob stat" in ln for ln in named), named
        # ...and the clish-wrapped reads are not.
        assert not any("A8:" in ln for ln in named), named

    def test_disclosure_is_value_free(self, tmp_path, monkeypatch, capsys):
        """Only approved source-command ids and the Outcome enum -- never
        device output, hostname, address or policy value."""
        self._clish_only_device(monkeypatch)
        _device, out = _drive_cli(tmp_path, monkeypatch, capsys)
        block = out.split("Reads (approved battery")[1].split("Vendor:")[0]
        for forbidden in ("gw-member-a", "gw-member-b", "192.0.2.", "SynthPolicy",
                          "STANDBY", "ACTIVE", "IGMP"):
            assert forbidden not in block, f"{forbidden!r} leaked into the disclosure"
