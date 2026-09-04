"""OP.0b S4-A' — Check Point VSX Virtual System Load Sharing (VSLS) per-VS
readiness.

Contract: `docs/history/phase/OP_0B_S4A_VSX_PER_VS_FAILOVER_DOMAIN_REVIEW.md`
(DRAFT, PO architecture review) + PO correction 2026-09-04: the approved VSX
pair's real `Cluster Mode:` line reads `Virtual System Load Sharing (Active
Up)` on both members -- the "physical parent is the sole readiness unit"
assumption does not hold for this estate. This build adds:

  - a canonical `vsx_vsls` mode token (was silently `unknown`)
  - `MemberSession.run_vsenv` -- the CP-C0 context-switch primitive, numeric-
    validated, verified by exit status (never by prompt shape), same session
  - `collect_member_vsx_per_vs` -- the CP-C1 minimum per-VS slice
    (`cphaprob stat` in a verified `vsenv <N>` context), never A4-A8 per-VS
  - `run_cp_preflight` attaching one `PreflightSnapshot` per VSID to the
    physical snapshot's `subordinate_snapshots`, same `preflight_run_id`,
    same open sessions -- no reconnect, no new SSH transport
  - the workflow threading every subordinate snapshot into the same one
    canonical evaluator (`compute_ha_readiness`) as the physical unit

No CLASS 2 mutation, no new command beyond `vsenv` + the already-approved
`cphaprob stat`, no management-plane read.
"""
from __future__ import annotations

import re

import pytest

from checkpoint.cp_preflight_battery import (
    COMMAND_TEXT,
    MAX_VS_SCOPES_PER_PREFLIGHT,
    CPPreflightRead,
)
from checkpoint.preflight_collector import (
    CPPhysicalMemberTarget,
    MemberSession,
    collect_member_vsx_per_vs,
    enumerated_vsids,
    run_cp_preflight,
)
from configuration.checkpoint_config_collector import (
    CLUSTERXL_CLUSTER_MODES,
    _parse_clusterxl_cluster_mode,
)
from utils.failover import compute_ha_readiness
from utils.failover.preflight_model import ContextKind, FactState, PreflightMemberEvidence, Transport

pytestmark = pytest.mark.configuration

_TEXT_TO_READ = {text: read for read, text in COMMAND_TEXT.items()}

# The real S8-B' device evidence (SAFE, value-free -- this is the fixed,
# already-published mode string, not raw device output).
_REAL_VSLS_A3 = (
    "Cluster Mode:   Virtual System Load Sharing (Active Up)\n"
    "Number   Unique Address  Assigned Load   State             Name\n"
    "1 (local) 10.10.10.1      100%            ACTIVE            gw-a\n"
    "2         10.10.10.2      0%              STANDBY           gw-b\n"
)
_REAL_VSLS_C1_ACTIVE = (
    "Cluster Mode:   Virtual System Load Sharing (Active Up)\n"
    "Number   Unique Address  Assigned Load   State             Name\n"
    "1 (local) 10.10.10.1      100%            ACTIVE            gw-a\n"
    "2         10.10.10.2      0%              STANDBY           gw-b\n"
)
_REAL_VSLS_C1_STANDBY = (
    "Cluster Mode:   Virtual System Load Sharing (Active Up)\n"
    "Number   Unique Address  Assigned Load   State             Name\n"
    "1         10.10.10.1      100%            ACTIVE            gw-a\n"
    "2 (local) 10.10.10.2      0%              STANDBY           gw-b\n"
)


# =====================================================================
# 1. Mode parser -- the real-env finding this whole build traces to
# =====================================================================

class TestModeParser:
    def test_virtual_system_load_sharing_is_a_distinct_canonical_token(self):
        assert _parse_clusterxl_cluster_mode(
            "Cluster Mode:   Virtual System Load Sharing (Active Up)\n"
        ) == "vsx_vsls"
        assert "vsx_vsls" in CLUSTERXL_CLUSTER_MODES

    def test_both_members_classify_identically(self):
        assert _parse_clusterxl_cluster_mode(_REAL_VSLS_A3) == "vsx_vsls"
        assert _parse_clusterxl_cluster_mode(
            _REAL_VSLS_A3.replace("gw-a", "gw-b").replace("(local)", "").replace("2 ", "2 (local)", 1)
        ) == "vsx_vsls"

    def test_vsls_never_collapses_into_generic_load_sharing(self):
        # Regression: before this build, this exact real string fell through
        # the bare "load sharing" branch (no multicast/unicast/pivot token)
        # to "unknown" -- which is why S8-B could never establish mode.
        assert _parse_clusterxl_cluster_mode(
            "Cluster Mode: Virtual System Load Sharing (Active Up)\n"
        ) != "unknown"
        assert _parse_clusterxl_cluster_mode(
            "Cluster Mode: Virtual System Load Sharing (Active Up)\n"
        ) not in ("load_sharing_unicast", "load_sharing_multicast")

    def test_vsx_single_vs_failover_unaffected(self):
        assert _parse_clusterxl_cluster_mode("Cluster Mode: Single VS Failover\n") == "vsx_single_vs_failover"

    def test_ordinary_load_sharing_unaffected(self):
        assert _parse_clusterxl_cluster_mode("Cluster Mode: Load Sharing (Multicast)\n") == "load_sharing_multicast"


# =====================================================================
# 2. MemberSession.run_vsenv -- CP-C0 context primitive
# =====================================================================

def _vsenv_fake_run(script: dict[str, dict]):
    """`script` maps exact command text -> result dict. Anything else
    raises, mirroring the collector's own "no arbitrary command text"
    invariant at the test layer."""
    calls: list[str] = []

    def _run(command_text: str) -> dict:
        calls.append(command_text)
        if command_text not in script:
            raise AssertionError(f"unscripted command: {command_text!r}")
        return dict(script[command_text])

    return _run, calls


class TestRunVsenv:
    def test_rejects_non_numeric_vsid(self):
        run, calls = _vsenv_fake_run({})
        session = MemberSession(physical_device_identity="m1", _run_command=run)
        with pytest.raises(ValueError):
            session.run_vsenv("1; rm -rf /")
        with pytest.raises(ValueError):
            session.run_vsenv("")
        assert calls == []  # never reaches the device

    def test_verified_switch_updates_current_vsid(self):
        run, _ = _vsenv_fake_run({"vsenv 3": {"success": True, "stdout": "", "stderr": ""}})
        session = MemberSession(physical_device_identity="m1", _run_command=run)
        session.run_vsenv("3")
        assert session.current_vsid == "3"
        assert session.context_verified is True

    def test_unverified_switch_does_not_move_current_vsid(self):
        run, _ = _vsenv_fake_run({"vsenv 3": {"success": False, "stdout": "", "stderr": "", "error_class": "command_error"}})
        session = MemberSession(physical_device_identity="m1", _run_command=run)
        assert session.current_vsid == "0"
        session.run_vsenv("3")
        assert session.current_vsid == "0"  # unchanged
        assert session.context_verified is False

    def test_restore_to_vs0_is_the_same_primitive(self):
        run, calls = _vsenv_fake_run({"vsenv 0": {"success": True, "stdout": "", "stderr": ""}})
        session = MemberSession(physical_device_identity="m1", _run_command=run)
        session.run_vsenv("0")
        assert calls == ["vsenv 0"]
        assert session.current_vsid == "0" and session.context_verified is True

    def test_paced_between_commands(self):
        sleeps: list[float] = []
        run, _ = _vsenv_fake_run({
            "vsenv 1": {"success": True, "stdout": "", "stderr": ""},
            "vsenv 0": {"success": True, "stdout": "", "stderr": ""},
        })
        session = MemberSession(physical_device_identity="m1", _run_command=run, _sleep=sleeps.append)
        session.run_vsenv("1")
        session.run_vsenv("0")
        assert sleeps == [0.3]  # no delay before the first call, one between the two

    def test_no_fw_ctl_set_int_vsid_anywhere_in_this_module(self):
        import checkpoint.preflight_collector as collector_module
        source = open(collector_module.__file__, encoding="utf-8").read().lower()
        assert "fw ctl set int vsid" not in source


# =====================================================================
# 3. enumerated_vsids -- VS0 excluded, deterministic order
# =====================================================================

class TestEnumeratedVsids:
    def _member_evidence(self, statuses: dict[str, str]) -> PreflightMemberEvidence:
        from checkpoint.cp_preflight_projection import project_cp_vsx_enumeration_facts
        from checkpoint.cp_preflight_extraction import parse_vsx_stat_v

        rows = "\n".join(f"VSID {vsid}    name{vsid}        {status}" for vsid, status in statuses.items())
        facts = project_cp_vsx_enumeration_facts(
            parse_vsx_stat_v(rows), preflight_run_id="r1", collected_at="2026-09-04T00:00:00Z",
            physical_device_identity="m1", operational_entity_id="grp1",
        )
        return PreflightMemberEvidence(physical_device_identity="m1", own_facts=facts, peer_claim_facts=())

    def test_vs0_excluded(self):
        ev = self._member_evidence({"0": "active", "1": "active", "2": "standby"})
        assert enumerated_vsids(ev) == ["1", "2"]

    def test_numeric_sort_not_lexical(self):
        ev = self._member_evidence({str(n): "active" for n in (10, 2, 1)})
        assert enumerated_vsids(ev) == ["1", "2", "10"]

    def test_no_vsids_is_empty(self):
        ev = PreflightMemberEvidence(physical_device_identity="m1", own_facts=(), peer_claim_facts=())
        assert enumerated_vsids(ev) == []


# =====================================================================
# 4. collect_member_vsx_per_vs -- CP-C1 slice, context safety
# =====================================================================

class TestCollectMemberVsxPerVs:
    def _session(self, script: dict[str, dict]) -> tuple[MemberSession, list[str]]:
        run, calls = _vsenv_fake_run(script)
        return MemberSession(physical_device_identity="member-token-a", _run_command=run), calls

    def _happy_script(self, *vsids: str) -> dict[str, dict]:
        script: dict[str, dict] = {}
        for vsid in vsids:
            script[f"vsenv {vsid}"] = {"success": True, "stdout": "", "stderr": ""}
        script["cphaprob stat"] = {"success": True, "stdout": _REAL_VSLS_C1_ACTIVE, "stderr": ""}
        script["vsenv 0"] = {"success": True, "stdout": "", "stderr": ""}
        return script

    def test_produces_one_evidence_per_vsid(self):
        session, calls = self._session(self._happy_script("1", "2"))
        result = collect_member_vsx_per_vs(
            session, vsids=["1", "2"], physical_operational_entity_id="grp1", preflight_run_id="run-1",
        )
        assert set(result.keys()) == {"1", "2"}
        assert calls == ["vsenv 1", "cphaprob stat", "vsenv 0", "vsenv 2", "cphaprob stat", "vsenv 0"]

    def test_facts_carry_vsid_context_and_vs_unit_operational_id(self):
        session, _ = self._session(self._happy_script("1"))
        result = collect_member_vsx_per_vs(
            session, vsids=["1"], physical_operational_entity_id="grp1", preflight_run_id="run-1",
        )
        ev = result["1"]
        assert ev.own_facts, "role/mode/attention facts expected"
        for fact in ev.own_facts:
            assert fact.provenance.context.kind is ContextKind.VSID
            assert fact.provenance.context.identifier == "1"
            assert fact.provenance.operational_entity_id == "grp1__vsid_1"
            assert fact.provenance.physical_device_identity == "member-token-a"

    def test_local_row_matched_by_local_marker_never_physical_hostname(self):
        """§26 CP-4 closure: the physical hostname must never be passed as
        the VS-context match token."""
        session, _ = self._session(self._happy_script("1"))
        result = collect_member_vsx_per_vs(
            session, vsids=["1"], physical_operational_entity_id="grp1", preflight_run_id="run-1",
        )
        role_fact = next(f for f in result["1"].own_facts if f.name == "ha_local_role")
        assert role_fact.state == FactState.KNOWN and role_fact.value == "ACTIVE"

    def test_unverified_switch_produces_no_fact_for_that_vsid_and_continues(self):
        script = self._happy_script("2")
        script["vsenv 1"] = {"success": False, "stdout": "", "stderr": "", "error_class": "command_error"}
        session, calls = self._session(script)
        result = collect_member_vsx_per_vs(
            session, vsids=["1", "2"], physical_operational_entity_id="grp1", preflight_run_id="run-1",
        )
        assert "1" not in result  # unverified switch: no read, no attribution
        assert "2" in result
        # No read/restore issued for VSID 1's failed switch -- straight to VSID 2.
        assert calls == ["vsenv 1", "vsenv 2", "cphaprob stat", "vsenv 0"]

    def test_failed_restore_stops_remaining_vsids(self):
        script = self._happy_script("1", "2")
        script["vsenv 0"] = {"success": False, "stdout": "", "stderr": "", "error_class": "command_error"}
        session, calls = self._session(script)
        result = collect_member_vsx_per_vs(
            session, vsids=["1", "2"], physical_operational_entity_id="grp1", preflight_run_id="run-1",
        )
        assert "1" in result
        assert "2" not in result  # never attempted -- restore after VSID 1 failed
        assert calls == ["vsenv 1", "cphaprob stat", "vsenv 0"]

    def test_no_cross_vs_fact_leakage_between_two_vsids(self):
        script = self._happy_script("1", "2")
        script["cphaprob stat"] = {"success": True, "stdout": _REAL_VSLS_C1_ACTIVE, "stderr": ""}
        session, _ = self._session(script)
        result = collect_member_vsx_per_vs(
            session, vsids=["1", "2"], physical_operational_entity_id="grp1", preflight_run_id="run-1",
        )
        ids = {f.provenance.operational_entity_id for ev in result.values() for f in ev.own_facts}
        assert ids == {"grp1__vsid_1", "grp1__vsid_2"}

    def test_deterministic_order_follows_input_sequence(self):
        session, calls = self._session(self._happy_script("2", "1"))
        collect_member_vsx_per_vs(
            session, vsids=["2", "1"], physical_operational_entity_id="grp1", preflight_run_id="run-1",
        )
        assert calls[0] == "vsenv 2"
        assert calls[3] == "vsenv 1"


# =====================================================================
# 5. run_cp_preflight -- same-session VSLS wiring, real device contact path
# =====================================================================

class _FakeVsxShell:
    """Minimal persistent-shell fake: one command -> one scripted result,
    used only to prove `run_cp_preflight`'s per-VS wiring (session reuse,
    ordering, subordinate snapshot assembly) -- not a second copy of the
    S8 device-session-architecture harness."""

    def __init__(self, script: dict[str, str]):
        self._script = script
        self.sent: list[str] = []

    def run(self, command: str, _timeout: int, *, frame: bool = False) -> dict:
        self.sent.append(command)
        stdout = self._script.get(command)
        return {
            "success": stdout is not None, "stdout": stdout or "", "stderr": "",
            "error_class": "none" if stdout is not None else "command_error",
            "timeout": False, "exit_status": 0 if stdout is not None else 1,
        }

    def close(self) -> None:
        pass


def _make_session_for(script: dict[str, str], identity: str) -> MemberSession:
    shell = _FakeVsxShell(script)
    return MemberSession(
        physical_device_identity=identity,
        _run_command=lambda text: shell.run(text, 20, frame=True),
        _shell=shell, _sleep=lambda _s: None,
    )


class TestRunCpPreflightVsls:
    def _connect_stub(self, monkeypatch, scripts: dict[str, dict[str, str]]):
        """Patches `_connect`/`make_real_member_session` so `run_cp_preflight`
        drives the real per-member/per-VS control flow over fake sessions,
        one per `physical_device_identity`, keyed by `scripts`. Returns the
        underlying `_FakeVsxShell`s directly (not the `MemberSession`, which
        `run_cp_preflight` closes -- and `.close()` drops its `_shell`
        reference -- before returning)."""
        import checkpoint.preflight_collector as pc

        shells: dict[str, _FakeVsxShell] = {}

        def fake_connect(probe_target, username, secret, *, strict, connect_timeout):
            return object(), "fp"

        def fake_make_session(ssh, *, physical_device_identity, command_timeout):
            shell = _FakeVsxShell(scripts[physical_device_identity])
            shells[physical_device_identity] = shell
            return MemberSession(
                physical_device_identity=physical_device_identity,
                _run_command=lambda text: shell.run(text, 20, frame=True),
                _shell=shell, _sleep=lambda _s: None,
            )

        monkeypatch.setattr(pc, "_connect", fake_connect)
        monkeypatch.setattr(pc, "make_real_member_session", fake_make_session)
        return shells

    def _physical_script(self, role: str, hostname: str) -> dict[str, str]:
        a3 = _REAL_VSLS_C1_ACTIVE if role == "active" else _REAL_VSLS_C1_STANDBY
        return {
            COMMAND_TEXT[CPPreflightRead.A1_HOSTNAME]: hostname,
            COMMAND_TEXT[CPPreflightRead.A2_VERSION]: "This is Check Point's software version R81.10",
            COMMAND_TEXT[CPPreflightRead.A3_CPHAPROB_STAT]: a3,
            COMMAND_TEXT[CPPreflightRead.A4_LINK_IF]: "eth1  UP  (secured, sync, HA)",
            COMMAND_TEXT[CPPreflightRead.A5_PNOTE_LIST]: "Current State: OK (Actual)",
            COMMAND_TEXT[CPPreflightRead.A6_SYNCSTAT]: "Sync Status: OK",
            COMMAND_TEXT[CPPreflightRead.A7_FW_STAT]: "Policy name: Standard_Policy",
            COMMAND_TEXT[CPPreflightRead.A8_CLISH_FAILOVER]: "Cluster failover count: 2",
            COMMAND_TEXT[CPPreflightRead.B1_VSX_STAT]: "VSID 0    VS0        Active\nVSID 1    LeasedLine Active\nVSID 2    Extranet   Standby",
            "vsenv 1": "",
            "vsenv 2": "",
            "vsenv 0": "",
        }

    def test_subordinate_snapshots_one_per_vsid(self, monkeypatch):
        scripts = {
            "mem-a": self._physical_script("active", "gw-a"),
            "mem-b": self._physical_script("standby", "gw-b"),
        }
        opened = self._connect_stub(monkeypatch, scripts)
        members = [
            CPPhysicalMemberTarget(physical_device_identity="mem-a", expected_device_name="mem-a", management_ip="10.0.0.1"),
            CPPhysicalMemberTarget(physical_device_identity="mem-b", expected_device_name="mem-b", management_ip="10.0.0.2"),
        ]
        snapshot = run_cp_preflight(
            operational_entity_id="grp1", unit_type="vsx", members=members, username="u", secret="s",
        )
        assert {s.operational_unit_id for s in snapshot.subordinate_snapshots} == {"grp1__vsid_1", "grp1__vsid_2"}
        for sub in snapshot.subordinate_snapshots:
            assert sub.preflight_run_id == snapshot.preflight_run_id
            assert len(sub.members) == 2

    def test_no_reconnect_no_new_shell_for_per_vs_phase(self, monkeypatch):
        scripts = {"mem-a": self._physical_script("active", "gw-a")}
        opened = self._connect_stub(monkeypatch, scripts)
        members = [CPPhysicalMemberTarget(physical_device_identity="mem-a", expected_device_name="mem-a", management_ip="10.0.0.1")]
        run_cp_preflight(operational_entity_id="grp1", unit_type="vsx", members=members, username="u", secret="s")
        assert len(opened) == 1  # exactly one MemberSession/shell for the whole run

    def test_vsenv_commands_appear_after_the_physical_battery_and_b1(self, monkeypatch):
        scripts = {"mem-a": self._physical_script("active", "gw-a")}
        opened = self._connect_stub(monkeypatch, scripts)
        members = [CPPhysicalMemberTarget(physical_device_identity="mem-a", expected_device_name="mem-a", management_ip="10.0.0.1")]
        run_cp_preflight(operational_entity_id="grp1", unit_type="vsx", members=members, username="u", secret="s")
        sent = opened["mem-a"].sent
        b1_index = next(i for i, c in enumerate(sent) if "vsx stat" in c)
        first_vsenv_index = next(i for i, c in enumerate(sent) if c.startswith("vsenv"))
        assert first_vsenv_index > b1_index

    def test_no_vsid_zero_context_switch(self, monkeypatch):
        """VSID "0" (VS0, listed by B1 alongside the real VSIDs) is never
        entered as a subordinate scope -- only the restore half of each
        enter/restore pair may target it."""
        scripts = {"mem-a": self._physical_script("active", "gw-a")}
        opened = self._connect_stub(monkeypatch, scripts)
        members = [CPPhysicalMemberTarget(physical_device_identity="mem-a", expected_device_name="mem-a", management_ip="10.0.0.1")]
        run_cp_preflight(operational_entity_id="grp1", unit_type="vsx", members=members, username="u", secret="s")
        sent = opened["mem-a"].sent
        vsenv_targets = [c.split()[1] for c in sent if c.startswith("vsenv")]
        enter_targets = vsenv_targets[0::2]  # enter, restore, enter, restore, ...
        restore_targets = vsenv_targets[1::2]
        assert "0" not in enter_targets
        assert set(restore_targets) == {"0"}

    def test_cap_enforced(self, monkeypatch):
        many_vsids = "\n".join(f"VSID {n}    name{n}        Active" for n in range(1, MAX_VS_SCOPES_PER_PREFLIGHT + 5))
        script = self._physical_script("active", "gw-a")
        script[COMMAND_TEXT[CPPreflightRead.B1_VSX_STAT]] = f"VSID 0    VS0        Active\n{many_vsids}"
        for n in range(1, MAX_VS_SCOPES_PER_PREFLIGHT + 5):
            script[f"vsenv {n}"] = ""
        opened = self._connect_stub(monkeypatch, {"mem-a": script})
        members = [CPPhysicalMemberTarget(physical_device_identity="mem-a", expected_device_name="mem-a", management_ip="10.0.0.1")]
        snapshot = run_cp_preflight(operational_entity_id="grp1", unit_type="vsx", members=members, username="u", secret="s")
        assert len(snapshot.subordinate_snapshots) <= MAX_VS_SCOPES_PER_PREFLIGHT

    def test_clusterxl_unit_type_produces_no_subordinate_snapshots(self, monkeypatch):
        script = self._physical_script("active", "gw-a")
        opened = self._connect_stub(monkeypatch, {"mem-a": script})
        members = [CPPhysicalMemberTarget(physical_device_identity="mem-a", expected_device_name="mem-a", management_ip="10.0.0.1")]
        snapshot = run_cp_preflight(operational_entity_id="grp1", unit_type="clusterxl", members=members, username="u", secret="s")
        assert snapshot.subordinate_snapshots == ()
        assert not any(c.startswith("vsenv") for c in opened["mem-a"].sent)


# =====================================================================
# 6. Readiness integration -- per-VS viable_target/no_split_brain only
# =====================================================================

def _identity_gate_fact(*, member: str, unit: str, run_id: str):
    from utils.failover.preflight_model import (
        FactCategory, FactContext, FactState, OpaqueToken, Outcome, PreflightFact, Provenance, SourceOrigin, Transport,
    )

    return PreflightFact(
        name="cp_identity_gate_accepted", category=FactCategory.PHYSICAL_IDENTITY,
        state=FactState.KNOWN, value=True,
        provenance=Provenance(
            collected_at="2026-09-04T10:00:00Z", preflight_run_id=run_id, source_vendor="checkpoint",
            source_plane=SourceOrigin.DEVICE_RUNTIME, transport=Transport.SSH_DIRECT,
            physical_device_identity=OpaqueToken(member), operational_entity_id=unit,
            context=FactContext.vsid("1"), outcome=Outcome.SUCCESS, source_command="gate",
        ),
    )


def _cp_preflight_facts_from_stdout(*, vsid: str, unit: str, role: str, member: str, run_id="run-vsls"):
    import dataclasses

    from checkpoint.cp_preflight_projection import project_cp_preflight_facts
    from configuration.checkpoint_config_collector import (
        _parse_clusterxl_cluster_mode, _parse_clusterxl_runtime_role, _parse_clusterxl_stat_preflight_fields,
    )
    from utils.failover.preflight_model import FactContext, Outcome, PreflightMemberEvidence

    stdout = _REAL_VSLS_C1_ACTIVE if role == "ACTIVE" else _REAL_VSLS_C1_STANDBY
    fields = {
        **_parse_clusterxl_stat_preflight_fields(stdout, None),
        "local_role": _parse_clusterxl_runtime_role(stdout, None),
        "cluster_mode": _parse_clusterxl_cluster_mode(stdout),
    }
    ev = project_cp_preflight_facts(
        fields, preflight_run_id=run_id, collected_at="2026-09-04T10:00:00Z",
        physical_device_identity=member, operational_entity_id=unit,
        context=FactContext.vsid(vsid), outcome=Outcome.SUCCESS,
    )
    gate = dataclasses.replace(
        _identity_gate_fact(member=member, unit=unit, run_id=run_id),
        provenance=dataclasses.replace(_identity_gate_fact(member=member, unit=unit, run_id=run_id).provenance, context=FactContext.vsid(vsid)),
    )
    return PreflightMemberEvidence(
        physical_device_identity=member, own_facts=(gate, *ev.own_facts), peer_claim_facts=ev.peer_claim_facts,
    )


class TestVsUnitReadiness:
    _CP_UNIT = "grp-vsls-1"

    def _vs_snapshot(self, vsid: str, run_id="run-vsls"):
        from utils.failover.preflight_model import PreflightSnapshot

        unit = f"{self._CP_UNIT}__vsid_{vsid}"
        active = _cp_preflight_facts_from_stdout(vsid=vsid, unit=unit, role="ACTIVE", member="member-a", run_id=run_id)
        standby = _cp_preflight_facts_from_stdout(vsid=vsid, unit=unit, role="STANDBY", member="member-b", run_id=run_id)
        return PreflightSnapshot(
            operational_unit_id=unit, vendor="checkpoint", unit_type="vsx",
            preflight_run_id=run_id, members=(active, standby), configuration_facts=(),
        )

    def _rows(self, vsids=("1", "2")):
        rows = [
            {"device": d, "source": "cp", "cluster_topology": {"group_id": self._CP_UNIT, "display_name": "Core"},
             "inventory_status": {"data_state": "ok"}}
            for d in ("vsx-1", "vsx-2")
        ]
        for d in ("vsx-1", "vsx-2"):
            for vsid in vsids:
                rows.append({"device": d, "source": "vsx", "vs_id": vsid, "vsys": f"vs{vsid}", "inventory_status": {"data_state": "ok"}})
        return rows

    def test_viable_target_and_no_split_brain_evaluable_from_per_vs_evidence(self):
        report = compute_ha_readiness(self._rows(), preflight_snapshots=[self._vs_snapshot("1")])
        unit = next(u for u in report["units"] if u["unit_id"] == f"{self._CP_UNIT}__vsid_1")
        checks = {c["id"]: c for c in unit["checks"]}
        # no_split_brain needs only ha_local_role (_CP_ROLE) -- collected by
        # C1 -- so it reaches PASS. viable_target's own frozen fact-check
        # mapping (utils.failover.preflight_readiness.FACT_CHECK_MAP)
        # additionally requires cp_pnote_any_problem (A5, physical/VS0-only,
        # never collected per-VS by this slice) -- so it honestly stays
        # INSUFFICIENT_EVIDENCE, never fabricated from partial evidence.
        # This is the fail-closed law working correctly, not a defect.
        assert checks["no_split_brain"]["status"] == "PASS"
        assert checks["viable_target"]["status"] == "INSUFFICIENT_EVIDENCE"
        assert "cp_pnote_any_problem" in checks["viable_target"]["reason"]

    def test_other_checks_stay_insufficient_never_fabricated(self):
        report = compute_ha_readiness(self._rows(), preflight_snapshots=[self._vs_snapshot("1")])
        unit = next(u for u in report["units"] if u["unit_id"] == f"{self._CP_UNIT}__vsid_1")
        checks = {c["id"]: c for c in unit["checks"]}
        for check_id in ("viable_target", "state_sync_current", "parity", "control_sync_link_health", "preemption_known", "flap_history"):
            assert checks[check_id]["status"] == "INSUFFICIENT_EVIDENCE", check_id

    def test_verdict_stays_insufficient_evidence_never_safe(self):
        report = compute_ha_readiness(self._rows(), preflight_snapshots=[self._vs_snapshot("1")])
        unit = next(u for u in report["units"] if u["unit_id"] == f"{self._CP_UNIT}__vsid_1")
        assert unit["verdict"] == "INSUFFICIENT_EVIDENCE"

    def test_one_logical_unit_per_vsid_no_duplication(self):
        report = compute_ha_readiness(
            self._rows(("1", "2")), preflight_snapshots=[self._vs_snapshot("1"), self._vs_snapshot("2")],
        )
        vsid_units = [u for u in report["units"] if u["unit_type"] == "cp_vsx_virtual_system"]
        assert sorted(u["unit_id"] for u in vsid_units) == [f"{self._CP_UNIT}__vsid_1", f"{self._CP_UNIT}__vsid_2"]

    def test_no_parent_verdict_inheritance(self):
        """The physical parent has NO fresh snapshot in this test (only the
        VS units do) -- its verdict must not be influenced by the VS units'
        positive evidence, and vice versa."""
        report = compute_ha_readiness(self._rows(("1",)), preflight_snapshots=[self._vs_snapshot("1")])
        parent = next(u for u in report["units"] if u["unit_id"] == self._CP_UNIT)
        vs = next(u for u in report["units"] if u["unit_id"] == f"{self._CP_UNIT}__vsid_1")
        assert vs["verdict"] != parent["verdict"] or vs["evidence"]["basis"] != parent["evidence"]["basis"]
        assert parent["evidence"]["basis"] == "op0a_stored_telemetry"
        assert vs["evidence"]["basis"] == "op0b_preflight_snapshot"

    def test_mode_established_as_vsx_vsls(self):
        report = compute_ha_readiness(self._rows(("1",)), preflight_snapshots=[self._vs_snapshot("1")])
        unit = next(u for u in report["units"] if u["unit_id"] == f"{self._CP_UNIT}__vsid_1")
        assert unit["cluster_mode"] == "vsx_vsls"

    def test_sk165432_rule_still_applies_to_a_contradictory_non_vs0_read(self):
        """The fail-closed D-V9a rule (a contradictory non-VS0 role/attention
        read is UNKNOWN, never KNOWN_BAD) is untouched by this build -- it's
        the same evaluator path, just now actually reachable with real
        per-VS evidence."""
        import dataclasses

        from checkpoint.cp_preflight_projection import project_cp_preflight_facts
        from utils.failover.preflight_model import FactContext, Outcome, PreflightMemberEvidence, PreflightSnapshot

        unit = f"{self._CP_UNIT}__vsid_1"
        bad_ev = project_cp_preflight_facts(
            {"local_role": "DOWN", "cluster_mode": "vsx_vsls", "peer_row_states": (), "local_attention": True},
            preflight_run_id="run-x", collected_at="2026-09-04T10:00:00Z",
            physical_device_identity="member-a", operational_entity_id=unit,
            context=FactContext.vsid("1"), outcome=Outcome.SUCCESS,
        )
        gate = dataclasses.replace(
            _identity_gate_fact(member="member-a", unit=unit, run_id="run-x"),
            provenance=dataclasses.replace(
                _identity_gate_fact(member="member-a", unit=unit, run_id="run-x").provenance,
                context=FactContext.vsid("1"),
            ),
        )
        bad = PreflightMemberEvidence(
            physical_device_identity="member-a", own_facts=(gate, *bad_ev.own_facts), peer_claim_facts=bad_ev.peer_claim_facts,
        )
        standby = _cp_preflight_facts_from_stdout(vsid="1", unit=unit, role="STANDBY", member="member-b", run_id="run-x")
        snap = PreflightSnapshot(
            operational_unit_id=unit, vendor="checkpoint", unit_type="vsx",
            preflight_run_id="run-x", members=(bad, standby), configuration_facts=(),
        )
        report = compute_ha_readiness(self._rows(("1",)), preflight_snapshots=[snap])
        u = next(x for x in report["units"] if x["unit_id"] == unit)
        checks = {c["id"]: c for c in u["checks"]}
        # The contradictory DOWN/attention read is in a non-VS0 (VSID)
        # context -- D-V9a says UNKNOWN, never KNOWN_BAD -- so it must not
        # decide the verdict as a device failure.
        assert checks["viable_target"]["status"] != "FAIL"
        assert checks["no_split_brain"]["status"] != "FAIL"
        assert u["verdict"] != "UNSAFE_DO_NOT_FAILOVER"
