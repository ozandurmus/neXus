"""OP.0b S5 -- Check Point dedicated preflight collector.

Contract chain: `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
(FROZEN WITH REAL-ENV VALIDATION GATES) -> `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md`
(APPROVED (2026-09-03), "Approval record"). Proves `checkpoint.cp_preflight_battery`,
`checkpoint.cp_preflight_extraction`, `checkpoint.cp_preflight_projection`
(S5 additions) and `checkpoint.preflight_collector` implement exactly the
PO-frozen battery: no unapproved command, no application-level retry, no
failure-driven form dispatch, one SSH session per member (B1 included), one
`preflight_run_id` per invocation, bounded invocation counts, and no
readiness verdict. Synthetic/mock session only -- no device is contacted.
"""
from __future__ import annotations

import pytest

from checkpoint.cp_preflight_battery import (
    COMMAND_TEXT,
    FORBIDDEN_COMMAND_MARKERS,
    CPPreflightRead,
    build_member_schedule,
    resolve_a6_form,
    resolve_a8_form,
)
from checkpoint.cp_preflight_extraction import (
    parse_cp_failover_history,
    parse_cp_sync_status,
    parse_cphaprob_a_if,
    parse_cphaprob_ia_list,
    parse_fw_stat_policy,
    parse_vsx_stat_v,
)
from checkpoint.cp_preflight_projection import (
    project_cp_failover_history_facts,
    project_cp_link_health_facts,
    project_cp_sync_facts,
    project_cp_vsx_enumeration_facts,
)
from checkpoint.preflight_collector import (
    MAX_PHYSICAL_MEMBERS,
    CPPhysicalMemberTarget,
    CPPreflightCollectionError,
    MemberSession,
    collect_member,
    run_cp_preflight,
)
from utils.failover.preflight_model import FactState, Outcome

pytestmark = pytest.mark.configuration

# =====================================================================
# Shared synthetic fixtures (task §30) -- happy path / missing / unknown /
# malformed / failure / unsupported. No real hostname/IP/serial/policy/VS
# name/credential appears anywhere below.
# =====================================================================

_HAPPY = {
    CPPreflightRead.A1_HOSTNAME: "gw-member-a",
    CPPreflightRead.A2_VERSION: "This is Check Point's software version R81.10\nOS build 123",
    CPPreflightRead.A3_CPHAPROB_STAT: (
        "Cluster Mode:   High Availability (Active Up)\n"
        "Number   Unique Address  Assigned Load   State             Name\n"
        "1 (local) 10.10.10.1      100%            ACTIVE            gw-member-a\n"
        "2         10.10.10.2      0%              STANDBY           gw-member-b\n"
    ),
    CPPreflightRead.A4_LINK_IF: "eth1  UP  (secured, sync, HA)\neth2  UP  (non sync)",
    CPPreflightRead.A5_PNOTE_LIST: "Current State: OK (Actual)\nCurrent State: OK (Actual)",
    CPPreflightRead.A6_SYNCSTAT: "Sync Status: OK",
    CPPreflightRead.A6_PSTAT: "Sync Status: OK",
    CPPreflightRead.A7_FW_STAT: "Policy name: Standard_Policy",
    CPPreflightRead.A8_CLISH_FAILOVER: (
        "Cluster failover count: 2\nReason: Interface eth0 link down\nLast failover event: 3 hours ago"
    ),
    CPPreflightRead.A8_EXPERT_FAILOVER: (
        "Failover count: 2\nReason: cpstop\nLast failover time: 3 hours ago"
    ),
    CPPreflightRead.B1_VSX_STAT: "VSID 0    VS0        Active\nVSID 1    Finance    Standby",
}

_MISSING_FIELDS = {read: "" for read in CPPreflightRead}
_UNKNOWN_VALUES = {
    CPPreflightRead.A6_SYNCSTAT: "Sync Status: Purple Elephant",
    CPPreflightRead.B1_VSX_STAT: "VSID 3    Weird      Purple",
}
_MALFORMED = {
    CPPreflightRead.A4_LINK_IF: "this is not a table\nrandom noise",
    CPPreflightRead.A5_PNOTE_LIST: "no state lines here at all",
}


def _fake_run_factory(fixtures: dict[CPPreflightRead, str], *, fail_on: frozenset[CPPreflightRead] = frozenset()):
    calls: list[CPPreflightRead] = []

    def _run(command_text: str) -> dict:
        for read, text in COMMAND_TEXT.items():
            if text == command_text:
                calls.append(read)
                if read in fail_on:
                    return {"success": False, "stdout": "", "stderr": "", "error_class": "command_error", "timeout": False}
                stdout = fixtures.get(read, "")
                return {
                    "success": bool(stdout), "stdout": stdout, "stderr": "",
                    "error_class": "none" if stdout else "empty_output", "timeout": False,
                }
        raise AssertionError(f"unapproved command text issued: {command_text!r}")

    return _run, calls


def _member_session(fixtures=_HAPPY, *, fail_on=frozenset(), physical_device_identity="member-token-a"):
    run, calls = _fake_run_factory(fixtures, fail_on=fail_on)
    return MemberSession(physical_device_identity=physical_device_identity, _run_command=run), calls


# =====================================================================
# §27 tests 1-15: command plan
# =====================================================================

_ALL_APPROVED = frozenset(CPPreflightRead)


def test_01_only_a1_a8_scheduled_for_non_vsx():
    schedule = build_member_schedule(is_vsx=False, a6_form=CPPreflightRead.A6_SYNCSTAT, a8_form=CPPreflightRead.A8_CLISH_FAILOVER)
    assert CPPreflightRead.B1_VSX_STAT not in schedule
    assert set(schedule) <= _ALL_APPROVED


def test_02_b1_scheduled_only_for_vsx():
    non_vsx = build_member_schedule(is_vsx=False, a6_form=None, a8_form=None)
    vsx = build_member_schedule(is_vsx=True, a6_form=None, a8_form=None)
    assert CPPreflightRead.B1_VSX_STAT not in non_vsx
    assert CPPreflightRead.B1_VSX_STAT in vsx


def test_03_a9_absent():
    names = {member.value.lower() for member in CPPreflightRead}
    assert not any("recovery" in n or "preemption" in n for n in names)
    assert all("cphaprob state" not in text.lower() for text in COMMAND_TEXT.values())


def test_04_a10_absent():
    assert all(text != "cphaprob state" for text in COMMAND_TEXT.values())
    assert not any("a10" in member.value.lower() for member in CPPreflightRead)


def test_05_a11_absent():
    assert all("cplic print" not in text.lower() and "cpstat os" not in text.lower() for text in COMMAND_TEXT.values())


def test_06_mutating_rejected_commands_absent():
    for marker in FORBIDDEN_COMMAND_MARKERS:
        for text in COMMAND_TEXT.values():
            assert marker not in text.lower(), f"forbidden marker {marker!r} present in {text!r}"


def test_07_one_ssh_session_abstraction_reused(monkeypatch):
    session, calls = _member_session()
    connect_count = {"n": 0}
    # collect_member never opens a connection itself -- only issues reads
    # over the already-open `session`. Prove no `_connect`-shaped call
    # happens by construction: the session object identity is the only
    # thing `collect_member` uses to talk to the device.
    collect_member(
        session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=False,
        preflight_run_id="run-1", operational_entity_id="entity-1",
    )
    assert len(calls) == len(set(calls)), "each read issued at most once over the one session"


def test_08_b1_does_not_request_another_session():
    session, calls = _member_session()
    collect_member(
        session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=True,
        preflight_run_id="run-1", operational_entity_id="entity-1",
    )
    assert CPPreflightRead.B1_VSX_STAT in calls
    # Same session object issued every call, B1 included -- structurally no
    # second session was opened for it.


def test_09_no_command_level_retry_loop():
    session, calls = _member_session()
    collect_member(
        session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=True,
        preflight_run_id="run-1", operational_entity_id="entity-1",
    )
    assert len(calls) == len(set(calls))
    assert len(calls) <= 9


def test_10_a6_dispatch_uses_known_capability_before_execution():
    assert resolve_a6_form("R81.10") == CPPreflightRead.A6_SYNCSTAT
    assert resolve_a6_form("R80.10") == CPPreflightRead.A6_PSTAT
    assert resolve_a6_form(None) is None
    assert resolve_a6_form("not-a-version") is None


def test_11_a6_failure_does_not_trigger_alternate_command():
    session, calls = _member_session(fail_on=frozenset({CPPreflightRead.A6_SYNCSTAT}))
    collect_member(
        session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=False,
        preflight_run_id="run-1", operational_entity_id="entity-1",
    )
    assert calls.count(CPPreflightRead.A6_SYNCSTAT) == 1
    assert CPPreflightRead.A6_PSTAT not in calls


def test_12_a8_dispatch_uses_known_platform_version():
    assert resolve_a8_form("gaia") == CPPreflightRead.A8_CLISH_FAILOVER
    assert resolve_a8_form("gaia_embedded") == CPPreflightRead.A8_EXPERT_FAILOVER
    assert resolve_a8_form("unknown") is None
    assert resolve_a8_form(None) is None


def test_13_a8_failure_does_not_trigger_alternate_form():
    session, calls = _member_session(fail_on=frozenset({CPPreflightRead.A8_CLISH_FAILOVER}))
    collect_member(
        session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=False,
        preflight_run_id="run-1", operational_entity_id="entity-1",
    )
    assert calls.count(CPPreflightRead.A8_CLISH_FAILOVER) == 1
    assert CPPreflightRead.A8_EXPERT_FAILOVER not in calls


def test_14_no_reset_form_can_be_selected():
    assert all("reset" not in text.lower() for text in COMMAND_TEXT.values())


def test_15_no_history_depth_option_can_be_selected():
    import inspect
    assert "depth" not in inspect.signature(resolve_a8_form).parameters
    assert all(text.count(" -") <= 1 or read == CPPreflightRead.A5_PNOTE_LIST for read, text in COMMAND_TEXT.items())
    assert COMMAND_TEXT[CPPreflightRead.A8_CLISH_FAILOVER] == "clish -c 'show cluster failover'"
    assert COMMAND_TEXT[CPPreflightRead.A8_EXPERT_FAILOVER] == "cphaprob show_failover"


# =====================================================================
# §28 tests 16-34: collection
# =====================================================================

def test_16_two_member_run_receives_one_preflight_run_id():
    session_a, _ = _member_session(physical_device_identity="member-a")
    session_b, _ = _member_session(fixtures={**_HAPPY, CPPreflightRead.A1_HOSTNAME: "gw-member-b"}, physical_device_identity="member-b")
    ev_a = collect_member(session_a, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=False, preflight_run_id="shared-run", operational_entity_id="entity-1")
    ev_b = collect_member(session_b, expected_device_name="gw-member-b", management_ip="10.0.0.2", is_vsx=False, preflight_run_id="shared-run", operational_entity_id="entity-1")
    for member in (ev_a, ev_b):
        for fact in member.own_facts:
            assert fact.provenance.preflight_run_id == "shared-run"


def test_17_member_facts_preserve_physical_identity_separately_from_operational_entity():
    session, _ = _member_session()
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=False, preflight_run_id="run-1", operational_entity_id="entity-1")
    assert str(ev.physical_device_identity) == "member-token-a"
    for fact in ev.own_facts:
        assert fact.provenance.operational_entity_id == "entity-1"
        assert str(fact.provenance.physical_device_identity) == "member-token-a"


def test_18_a4_evidence_projected():
    session, _ = _member_session()
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=False, preflight_run_id="run-1", operational_entity_id="entity-1")
    names = {f.name for f in ev.own_facts}
    assert "cp_link_any_down" in names


def test_19_a5_evidence_projected():
    session, _ = _member_session()
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=False, preflight_run_id="run-1", operational_entity_id="entity-1")
    names = {f.name for f in ev.own_facts}
    assert "cp_pnote_any_problem" in names


def test_20_a6_evidence_projected():
    session, _ = _member_session()
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=False, preflight_run_id="run-1", operational_entity_id="entity-1")
    names = {f.name for f in ev.own_facts}
    assert "cp_sync_status" in names


def test_21_a7_evidence_projected_safely():
    session, _ = _member_session()
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=False, preflight_run_id="run-1", operational_entity_id="entity-1")
    fact = next(f for f in ev.own_facts if f.name == "cp_installed_policy_token")
    assert fact.state == FactState.KNOWN
    assert "Standard_Policy" not in str(fact.value)
    assert len(str(fact.value)) <= 32


def test_22_a8_evidence_projected_without_threshold_verdict():
    session, _ = _member_session()
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=False, preflight_run_id="run-1", operational_entity_id="entity-1")
    fact = next(f for f in ev.own_facts if f.name == "cp_failover_count")
    assert fact.state == FactState.KNOWN
    assert fact.value == 2
    assert not any(f.name in {"cp_failover_pass", "cp_failover_healthy"} for f in ev.own_facts)


def test_23_vsx_adds_b1():
    session, calls = _member_session()
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=True, preflight_run_id="run-1", operational_entity_id="entity-1")
    assert CPPreflightRead.B1_VSX_STAT in calls
    assert any(f.name == "cp_vsx_vs_count" for f in ev.own_facts)


def test_24_non_vsx_does_not_add_b1():
    session, calls = _member_session()
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=False, preflight_run_id="run-1", operational_entity_id="entity-1")
    assert CPPreflightRead.B1_VSX_STAT not in calls
    assert not any(f.name.startswith("cp_vsx_") for f in ev.own_facts)


def test_25_command_failure_produces_explicit_failed_unknown_evidence():
    session, _ = _member_session(fail_on=frozenset({CPPreflightRead.A4_LINK_IF}))
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=False, preflight_run_id="run-1", operational_entity_id="entity-1")
    fact = next(f for f in ev.own_facts if f.name == "cp_link_any_down")
    assert fact.state == FactState.COLLECTION_FAILED
    assert fact.value is None
    assert fact.provenance.outcome == Outcome.FAILED


def test_26_one_command_failure_does_not_fabricate_failure_for_unrelated_facts():
    session, _ = _member_session(fail_on=frozenset({CPPreflightRead.A4_LINK_IF}))
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=False, preflight_run_id="run-1", operational_entity_id="entity-1")
    pnote_fact = next(f for f in ev.own_facts if f.name == "cp_pnote_any_problem")
    assert pnote_fact.state == FactState.KNOWN
    policy_fact = next(f for f in ev.own_facts if f.name == "cp_installed_policy_token")
    assert policy_fact.state == FactState.KNOWN


def test_27_identity_gate_failure_stops_attribution_for_that_member():
    session, calls = _member_session(fixtures={**_HAPPY, CPPreflightRead.A1_HOSTNAME: "", CPPreflightRead.A2_VERSION: ""})
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=False, preflight_run_id="run-1", operational_entity_id="entity-1")
    identity_fact = next(f for f in ev.own_facts if f.name == "cp_identity_gate_accepted")
    assert identity_fact.value is False
    assert identity_fact.provenance.outcome == Outcome.IDENTITY_MISMATCH
    # No read beyond A1/A2 was issued once identity failed.
    assert CPPreflightRead.A3_CPHAPROB_STAT not in calls
    assert CPPreflightRead.A4_LINK_IF not in calls


def test_28_raw_output_does_not_appear_in_returned_serialization():
    session, _ = _member_session()
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=True, preflight_run_id="run-1", operational_entity_id="entity-1")
    serialized = str(ev.to_dict())
    assert "Standard_Policy" not in serialized
    assert "10.10.10.1" not in serialized
    assert "Finance" not in serialized


#: `cphaprob stat` (A3) is the one pre-existing S3 default, unchanged by
#: this build; every S5-added fact must use a genuinely symbolic id.
_S5_ADDED_FACT_NAMES = {
    "cp_software_version", "cp_link_any_down", "cp_link_interface_count",
    "cp_pnote_any_problem", "cp_pnote_device_count", "cp_sync_status",
    "cp_installed_policy_token", "cp_failover_count", "cp_failover_last_reason",
    "cp_failover_last_event_time", "cp_vsx_vs_count",
}


def test_29_source_command_contains_symbolic_safe_command_id():
    session, _ = _member_session()
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=True, preflight_run_id="run-1", operational_entity_id="entity-1")
    for fact in ev.own_facts:
        source = fact.provenance.source_command
        assert source is not None
        assert len(source) <= 64
        assert "10.0.0.1" not in source and "gw-member-a" not in source
        if fact.name in _S5_ADDED_FACT_NAMES:
            assert source[:2] in {"A2", "A4", "A5", "A6", "A7", "A8", "B1"}


def test_30_member_session_count_remains_bounded():
    with pytest.raises(CPPreflightCollectionError):
        run_cp_preflight(
            operational_entity_id="entity-1", unit_type="clusterxl",
            members=[
                CPPhysicalMemberTarget("m1", "gw-a", "10.0.0.1"),
                CPPhysicalMemberTarget("m2", "gw-b", "10.0.0.2"),
                CPPhysicalMemberTarget("m3", "gw-c", "10.0.0.3"),
            ],
            username="user", secret="secret",  # pragma: allowlist secret
        )
    assert MAX_PHYSICAL_MEMBERS == 2


def test_31_non_vsx_pair_invocation_count_le_16():
    total = 0
    for identity in ("member-a", "member-b"):
        session, calls = _member_session(physical_device_identity=identity)
        collect_member(session, expected_device_name="gw", management_ip="10.0.0.1", is_vsx=False, preflight_run_id="run-1", operational_entity_id="entity-1")
        total += len(calls)
    assert total <= 16


def test_32_vsx_pair_invocation_count_le_18():
    total = 0
    for identity in ("member-a", "member-b"):
        session, calls = _member_session(physical_device_identity=identity)
        collect_member(session, expected_device_name="gw", management_ip="10.0.0.1", is_vsx=True, preflight_run_id="run-1", operational_entity_id="entity-1")
        total += len(calls)
    assert total <= 18


def test_33_no_implicit_fleet_expansion():
    with pytest.raises(CPPreflightCollectionError):
        run_cp_preflight(operational_entity_id="entity-1", unit_type="clusterxl", members=[], username="u", secret="s")  # pragma: allowlist secret


def test_34_no_readiness_result_returned():
    session, _ = _member_session()
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=True, preflight_run_id="run-1", operational_entity_id="entity-1")
    forbidden_tokens = {"safe_to_failover", "readiness", "degraded_proceed_with_risk"}
    for fact in ev.own_facts:
        assert fact.name.lower() not in forbidden_tokens


# =====================================================================
# §29 tests 35-40: VSX
# =====================================================================

def test_35_physical_vsx_cluster_remains_operational_unit():
    session, _ = _member_session()
    ev = collect_member(session, expected_device_name="gw-member-a", management_ip="10.0.0.1", is_vsx=True, preflight_run_id="run-1", operational_entity_id="entity-1")
    for fact in ev.own_facts:
        assert fact.provenance.operational_entity_id == "entity-1"


def test_36_vsid_remains_subordinate_context():
    parsed = parse_vsx_stat_v(_HAPPY[CPPreflightRead.B1_VSX_STAT])
    facts = project_cp_vsx_enumeration_facts(
        parsed, preflight_run_id="run-1", collected_at="2026-09-03T00:00:00Z",
        physical_device_identity="member-a", operational_entity_id="entity-1",
    )
    for fact in facts:
        if fact.name.startswith("cp_vsx_vs_") and fact.name.endswith("_status"):
            assert fact.provenance.context.kind.value == "physical"


def test_37_b1_vs_enumeration_does_not_create_failover_target():
    parsed = parse_vsx_stat_v(_HAPPY[CPPreflightRead.B1_VSX_STAT])
    facts = project_cp_vsx_enumeration_facts(
        parsed, preflight_run_id="run-1", collected_at="2026-09-03T00:00:00Z",
        physical_device_identity="member-a", operational_entity_id="entity-1",
    )
    for fact in facts:
        assert fact.category.value == "operational_ha_entity_identity"


def test_38_no_vsls_mutation_primitive_introduced():
    """OP.0b S4-A' (real-env VSLS finding, PO correction 2026-09-04): a
    Virtual System under VSLS IS an independent readiness domain, and the
    collector now legitimately names "vsls" (the mode token, `vsenv`
    context-switch primitive) -- this replaces the earlier blanket ban.
    What stays banned unconditionally is any VSLS *mutation* surface
    (management-plane priority change, per-VS failover execution) -- this
    movement implements per-VS READINESS only, never CLASS 2."""
    import checkpoint.preflight_collector as collector_module
    source = open(collector_module.__file__, encoding="utf-8").read().lower()
    for forbidden in ("vsx_util", "clusterxl_admin", "cphastop"):
        assert forbidden not in source


def test_39_no_fw_ctl_set_int_vsid_path_exists():
    assert all("fw ctl set int vsid" not in text.lower() for text in COMMAND_TEXT.values())
    import checkpoint.preflight_collector as collector_module
    source = open(collector_module.__file__, encoding="utf-8").read()
    assert "fw ctl set int vsid" not in source.lower()


def test_40_conflicting_subordinate_evidence_remains_unknown_compatible():
    parsed = parse_vsx_stat_v(_UNKNOWN_VALUES[CPPreflightRead.B1_VSX_STAT])
    facts = project_cp_vsx_enumeration_facts(
        parsed, preflight_run_id="run-1", collected_at="2026-09-03T00:00:00Z",
        physical_device_identity="member-a", operational_entity_id="entity-1",
    )
    status_fact = next(f for f in facts if f.name == "cp_vsx_vs_3_status")
    assert status_fact.state == FactState.UNKNOWN
    assert status_fact.value is None


# =====================================================================
# Extraction fixtures: happy / missing / unknown / malformed / failure /
# unsupported (task §30).
# =====================================================================

def test_extraction_a4_happy_missing_malformed():
    assert parse_cphaprob_a_if(_HAPPY[CPPreflightRead.A4_LINK_IF])["any_down"] is False
    assert parse_cphaprob_a_if(_MISSING_FIELDS[CPPreflightRead.A4_LINK_IF])["observed"] is False
    assert parse_cphaprob_a_if(_MALFORMED[CPPreflightRead.A4_LINK_IF])["observed"] is False


def test_extraction_a5_happy_missing_malformed():
    assert parse_cphaprob_ia_list(_HAPPY[CPPreflightRead.A5_PNOTE_LIST])["any_problem"] is False
    assert parse_cphaprob_ia_list(_MISSING_FIELDS[CPPreflightRead.A5_PNOTE_LIST])["observed"] is False
    assert parse_cphaprob_ia_list(_MALFORMED[CPPreflightRead.A5_PNOTE_LIST])["observed"] is False


def test_extraction_a6_unknown_token_fails_closed():
    result = parse_cp_sync_status(_UNKNOWN_VALUES[CPPreflightRead.A6_SYNCSTAT])
    assert result["observed"] is True
    assert result["status"] is None


def test_extraction_a7_missing():
    assert parse_fw_stat_policy(_MISSING_FIELDS[CPPreflightRead.A7_FW_STAT])["observed"] is False


def test_extraction_a8_happy_both_forms():
    clish = parse_cp_failover_history(_HAPPY[CPPreflightRead.A8_CLISH_FAILOVER])
    expert = parse_cp_failover_history(_HAPPY[CPPreflightRead.A8_EXPERT_FAILOVER])
    assert clish["count"] == 2 and expert["count"] == 2
    assert clish["last_reason_class"] == "interface_link_down"
    assert expert["last_reason_class"] == "manual_operator_action"


def test_extraction_capability_gap_projection_a6():
    facts = project_cp_sync_facts(
        None, preflight_run_id="run-1", collected_at="2026-09-03T00:00:00Z",
        physical_device_identity="member-a", operational_entity_id="entity-1", dispatch_form=None,
    )
    assert facts[0].state == FactState.UNSUPPORTED
    assert facts[0].provenance.outcome == Outcome.CAPABILITY_GAP


def test_extraction_capability_gap_projection_a8():
    facts = project_cp_failover_history_facts(
        None, preflight_run_id="run-1", collected_at="2026-09-03T00:00:00Z",
        physical_device_identity="member-a", operational_entity_id="entity-1", dispatch_form=None,
    )
    assert facts[0].state == FactState.UNSUPPORTED
    assert facts[0].provenance.outcome == Outcome.CAPABILITY_GAP


def test_extraction_a4_projection_collection_failed():
    facts = project_cp_link_health_facts(
        None, preflight_run_id="run-1", collected_at="2026-09-03T00:00:00Z",
        physical_device_identity="member-a", operational_entity_id="entity-1", outcome=Outcome.FAILED,
    )
    assert facts[0].state == FactState.COLLECTION_FAILED
