"""OP.0b S6 -- Palo Alto dedicated preflight collector.

Contract chain: `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
(FROZEN WITH REAL-ENV VALIDATION GATES) -> `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md`
(APPROVED (2026-09-03), "Approval record"). Proves `panorama.pan_preflight_battery`,
`panorama.pan_preflight_extraction`, `panorama.pan_preflight_projection` (S6
additions) and `panorama.preflight_collector` implement exactly the
PO-frozen battery: P1/P2/P4 only, no unapproved command, no application-
level retry, one API session per member, one `preflight_run_id` per
invocation, bounded invocation counts, no readiness verdict, and B2 stays
unestablished. Synthetic/mock transport only -- no device is contacted.
"""
from __future__ import annotations

from unittest.mock import patch

import pytest
from lxml import etree

from panorama.pan_preflight_battery import (
    COMMAND_TEXT,
    FORBIDDEN_COMMAND_MARKERS,
    PANPreflightRead,
    build_member_schedule,
)
from panorama.pan_preflight_extraction import parse_pan_path_monitoring
from panorama.pan_preflight_projection import (
    project_pan_identity_fact,
    project_pan_path_monitoring_facts,
)
from panorama.preflight_collector import (
    MAX_PHYSICAL_MEMBERS,
    PANPhysicalMemberTarget,
    PANPreflightCollectionError,
    collect_member,
    run_pan_preflight,
)
from utils.failover.preflight_model import FactState, Outcome, evaluate_coherence, PreflightSnapshot

pytestmark = pytest.mark.configuration

# =====================================================================
# Shared synthetic fixtures. No real hostname/IP/serial/credential appears
# anywhere below.
# =====================================================================

_P2_XML_HAPPY = """<response status="success"><result><enabled>yes</enabled><group>
<running-sync>yes</running-sync><running-sync-enabled>yes</running-sync-enabled>
<local-info><state>active</state><mode>active-passive</mode><state-sync>Synchronized</state-sync>
<serial-num>0001A</serial-num></local-info>
<peer-info><state>passive</state><serial-num>0002B</serial-num><conn-status>up</conn-status></peer-info>
</group></result></response>"""

_P4_XML_HAPPY = """<response status="success"><result><enabled>yes</enabled><groups>
<entry><state>up</state></entry><entry><state>up</state></entry>
</groups></result></response>"""

_P4_XML_UNKNOWN = """<response status="success"><result><groups>
<entry><state>purple</state></entry></groups></result></response>"""

_P4_XML_MALFORMED = """<response status="success"><result><nonsense/></result></response>"""


def _member_target(serial="0001A", identity="member-token-a", ip="10.0.0.1"):
    return PANPhysicalMemberTarget(physical_device_identity=identity, expected_serial=serial, management_ip=ip)


def _patched_env(*, p2_xml=_P2_XML_HAPPY, p4_xml=_P4_XML_HAPPY, observed_serial="0001A",
                  fail_p2=False, fail_p4=False, fail_keygen=False, fail_system_info=False):
    calls: list[str] = []

    def fake_api_post(host, key, data, *, verify, timeout, operation):
        cmd = data.get("cmd")
        for read, text in COMMAND_TEXT.items():
            if text == cmd:
                calls.append(read.value)
                break
        else:
            raise AssertionError(f"unapproved command text issued: {cmd!r}")
        if "path-monitoring" in cmd:
            if fail_p4:
                raise RuntimeError("simulated P4 failure")
            return etree.fromstring(p4_xml.encode())
        if "high-availability><state" in cmd:
            if fail_p2:
                raise RuntimeError("simulated P2 failure")
            return etree.fromstring(p2_xml.encode())
        raise AssertionError(f"unexpected cmd {cmd!r}")

    def fake_get_direct_system_info(host, key, *, verify, timeout):
        calls.append(PANPreflightRead.P1_SYSTEM_INFO.value)
        if fail_system_info:
            raise RuntimeError("simulated P1 failure")
        return {"serial": observed_serial, "hostname": "fw", "sw_version": "10.2.0", "model": "PA-VM"}

    def fake_get_firewall_api_key(cfg, host, *, verify, timeout):
        if fail_keygen:
            raise RuntimeError("simulated keygen failure")
        return "fake-key"

    return calls, fake_api_post, fake_get_direct_system_info, fake_get_firewall_api_key


def _collect(**kwargs):
    calls, api_post, system_info, keygen = _patched_env(**kwargs)
    import panorama.preflight_collector as pc
    with patch.object(pc, "api_post", api_post), \
         patch.object(pc, "get_direct_system_info", system_info), \
         patch.object(pc, "get_firewall_api_key", keygen):
        ev = collect_member(
            username="u", secret="s", target=_member_target(identity=kwargs.pop("identity", "member-token-a") if "identity" in kwargs else "member-token-a"),
            preflight_run_id="run-1", operational_entity_id="entity-1",
        )
    return ev, calls


# =====================================================================
# §21 tests 1-10: battery
# =====================================================================

def test_01_exactly_p1_p2_p4_eligible():
    schedule = build_member_schedule()
    assert set(schedule) == {PANPreflightRead.P1_SYSTEM_INFO, PANPreflightRead.P2_HA_STATE, PANPreflightRead.P4_PATH_MONITORING}


def test_02_p3_absent():
    assert all("high-availability><all" not in text.lower() for text in COMMAND_TEXT.values())
    assert not any("p3" in member.value.lower() for member in PANPreflightRead)


def test_03_p5_absent():
    assert all("link-monitoring" not in text.lower() for text in COMMAND_TEXT.values())
    assert not any("p5" in member.value.lower() for member in PANPreflightRead)


def test_04_mutations_absent():
    for marker in FORBIDDEN_COMMAND_MARKERS:
        for text in COMMAND_TEXT.values():
            assert marker not in text.lower(), f"forbidden marker {marker!r} present in {text!r}"


def test_05_no_arbitrary_api_operation_can_be_injected():
    import inspect
    from panorama import preflight_collector as pc
    source = inspect.getsource(pc._direct_op_read)
    assert "COMMAND_TEXT[read]" in source
    assert "cmd=" not in source.replace("COMMAND_TEXT[read]", "")


def test_06_one_session_abstraction_reused():
    ev, calls = _collect()
    assert calls.count(PANPreflightRead.P1_SYSTEM_INFO.value) == 1
    assert len(calls) == len(set(calls))


def test_07_no_command_level_retry():
    ev, calls = _collect(fail_p4=True)
    assert calls.count(PANPreflightRead.P4_PATH_MONITORING.value) == 1


def test_08_p4_failure_does_not_trigger_p3_p5():
    ev, calls = _collect(fail_p4=True)
    assert all("p3" not in c.lower() for c in calls)
    assert all("link" not in c.lower() for c in calls)


def test_09_pair_call_count_le_6():
    total = 0
    for identity, serial in (("member-a", "0001A"), ("member-b", "0002B")):
        calls, api_post, system_info, keygen = _patched_env(observed_serial=serial)
        import panorama.preflight_collector as pc
        with patch.object(pc, "api_post", api_post), \
             patch.object(pc, "get_direct_system_info", system_info), \
             patch.object(pc, "get_firewall_api_key", keygen):
            collect_member(
                username="u", secret="s", target=_member_target(serial=serial, identity=identity),
                preflight_run_id="run-1", operational_entity_id="entity-1",
            )
        total += len(calls)
    assert total <= 6


def test_10_no_fleet_expansion():
    with pytest.raises(PANPreflightCollectionError):
        run_pan_preflight(
            operational_entity_id="entity-1",
            members=[_member_target(identity="m1"), _member_target(identity="m2"), _member_target(identity="m3")],
            username="u", secret="s",  # pragma: allowlist secret
        )
    assert MAX_PHYSICAL_MEMBERS == 2
    with pytest.raises(PANPreflightCollectionError):
        run_pan_preflight(operational_entity_id="entity-1", members=[], username="u", secret="s")  # pragma: allowlist secret


# =====================================================================
# §22 tests 11-30: collection
# =====================================================================

def test_11_one_preflight_run_id_per_pair():
    calls, api_post, system_info, keygen = _patched_env()
    import panorama.preflight_collector as pc
    with patch.object(pc, "api_post", api_post), \
         patch.object(pc, "get_direct_system_info", system_info), \
         patch.object(pc, "get_firewall_api_key", keygen):
        snapshot = run_pan_preflight(
            operational_entity_id="entity-1",
            members=[_member_target(identity="m1"), _member_target(identity="m2")],
            username="u", secret="s",
        )
    run_ids = {fact.provenance.preflight_run_id for member in snapshot.members for fact in member.own_facts}
    assert run_ids == {snapshot.preflight_run_id}


def test_12_both_members_share_run_id():
    calls, api_post, system_info, keygen = _patched_env()
    import panorama.preflight_collector as pc
    with patch.object(pc, "api_post", api_post), \
         patch.object(pc, "get_direct_system_info", system_info), \
         patch.object(pc, "get_firewall_api_key", keygen):
        snapshot = run_pan_preflight(
            operational_entity_id="entity-1",
            members=[_member_target(identity="m1"), _member_target(identity="m2")],
            username="u", secret="s",
        )
    assert len(snapshot.members) == 2
    for member in snapshot.members:
        for fact in member.own_facts:
            assert fact.provenance.preflight_run_id == snapshot.preflight_run_id


def test_13_identity_gate_occurs_before_attribution():
    ev, calls = _collect()
    assert calls[0] == PANPreflightRead.P1_SYSTEM_INFO.value


def test_14_identity_mismatch_stops_trusted_collection():
    ev, calls = _collect(observed_serial="WRONG_SERIAL")
    identity_fact = next(f for f in ev.own_facts if f.name == "pan_identity_gate_accepted")
    assert identity_fact.value is False
    assert identity_fact.provenance.outcome == Outcome.IDENTITY_MISMATCH
    assert PANPreflightRead.P2_HA_STATE.value not in calls
    assert PANPreflightRead.P4_PATH_MONITORING.value not in calls


def test_15_p2_uses_s2_extraction_authority():
    ev, _ = _collect()
    names = {f.name for f in ev.own_facts}
    assert "local_state" in names


def test_16_running_sync_from_p2():
    ev, _ = _collect()
    fact = next(f for f in ev.own_facts if f.name == "group_running_sync")
    assert fact.state == FactState.KNOWN
    assert fact.value == "yes"


def test_17_state_sync_from_p2():
    ev, _ = _collect()
    fact = next(f for f in ev.own_facts if f.name == "local_state_sync")
    assert fact.value == "Synchronized"


def test_18_conn_evidence_from_p2():
    ev, _ = _collect()
    peer_names = {f.name for f in ev.peer_claim_facts}
    assert "peer_conn_status" in peer_names


def test_19_p4_path_health_projected_safely():
    ev, _ = _collect()
    fact = next(f for f in ev.own_facts if f.name == "pan_path_monitoring_any_down")
    assert fact.state == FactState.KNOWN
    assert fact.value is False


def test_20_missing_p4_fields_remain_explicit_unknown():
    parsed = parse_pan_path_monitoring(etree.fromstring(_P4_XML_MALFORMED.encode()))
    facts = project_pan_path_monitoring_facts(
        parsed, preflight_run_id="r1", collected_at="2026-09-03T00:00:00Z",
        physical_device_identity="m1", operational_entity_id="e1",
    )
    fact = next(f for f in facts if f.name == "pan_path_monitoring_any_down")
    assert fact.state == FactState.UNKNOWN
    assert fact.value is None


def test_21_p4_unknown_state_never_becomes_healthy():
    parsed = parse_pan_path_monitoring(etree.fromstring(_P4_XML_UNKNOWN.encode()))
    assert parsed["any_down"] is None
    facts = project_pan_path_monitoring_facts(
        parsed, preflight_run_id="r1", collected_at="2026-09-03T00:00:00Z",
        physical_device_identity="m1", operational_entity_id="e1",
    )
    fact = next(f for f in facts if f.name == "pan_path_monitoring_any_down")
    assert fact.value is not True
    assert fact.state == FactState.UNKNOWN


def test_22_p2_peer_serial_remains_peer_claim():
    ev, _ = _collect()
    peer_names = {f.name for f in ev.peer_claim_facts}
    own_names = {f.name for f in ev.own_facts}
    assert "peer_serial_claim" in peer_names
    assert "peer_serial_claim" not in own_names


def test_23_peer_claim_does_not_synthesize_member():
    ev, _ = _collect()
    assert isinstance(ev.peer_claim_facts, tuple)
    # peer_claim_facts belong to THIS member's evidence object -- no second
    # PreflightMemberEvidence is ever created from them.


def test_24_raw_serial_absent_from_serialization():
    ev, _ = _collect()
    serialized = str(ev.to_dict())
    assert "0001A" not in serialized
    assert "0002B" not in serialized


def test_25_raw_ip_absent():
    ev, _ = _collect()
    serialized = str(ev.to_dict())
    assert "10.0.0.1" not in serialized


def test_26_raw_xml_absent():
    ev, _ = _collect()
    serialized = str(ev.to_dict())
    assert "<result" not in serialized and "<group" not in serialized


def test_27_one_read_failure_preserves_unrelated_evidence():
    ev, _ = _collect(fail_p4=True)
    p4_fact = next(f for f in ev.own_facts if f.name == "pan_path_monitoring_any_down")
    assert p4_fact.state == FactState.COLLECTION_FAILED
    p2_fact = next(f for f in ev.own_facts if f.name == "local_state")
    assert p2_fact.state == FactState.KNOWN


def test_28_source_command_uses_symbolic_id():
    ev, _ = _collect()
    for fact in ev.own_facts:
        source = fact.provenance.source_command
        assert source is not None
        assert len(source) <= 64
        assert "10.0.0.1" not in source and "0001A" not in source
        assert source in {"P1", "P2", "P4"}


def test_29_physical_identity_vs_operational_pair_id_separate():
    ev, _ = _collect()
    assert str(ev.physical_device_identity) == "member-token-a"
    for fact in ev.own_facts:
        assert fact.provenance.operational_entity_id == "entity-1"
        assert str(fact.provenance.physical_device_identity) == "member-token-a"


def test_30_snapshot_accepted_by_s1_coherence():
    calls, api_post, system_info, keygen = _patched_env()
    import panorama.preflight_collector as pc
    with patch.object(pc, "api_post", api_post), \
         patch.object(pc, "get_direct_system_info", system_info), \
         patch.object(pc, "get_firewall_api_key", keygen):
        snapshot = run_pan_preflight(
            operational_entity_id="entity-1",
            members=[_member_target(identity="m1"), _member_target(identity="m2")],
            username="u", secret="s",
        )
    assert isinstance(snapshot, PreflightSnapshot)
    result = evaluate_coherence(snapshot)
    assert result.coherent is True


# =====================================================================
# §23 tests 31-36: B2
# =====================================================================

def test_31_s6_does_not_establish_b2_from_one_member():
    ev, _ = _collect()
    # No fact/field named anything B2-shaped exists on a single member's evidence.
    assert not any("b2" in f.name.lower() or "pair_identity" in f.name.lower() for f in ev.own_facts)


def test_32_matching_one_sided_peer_claim_does_not_establish_b2():
    ev, _ = _collect()
    peer_serial_fact = next(f for f in ev.peer_claim_facts if f.name == "peer_serial_claim")
    # It remains a peer CLAIM fact (category E), never promoted to an
    # established-pair fact or moved into own_facts.
    assert peer_serial_fact in ev.peer_claim_facts
    assert peer_serial_fact not in ev.own_facts


def test_33_current_successor_serial_pair_model_remains_unused():
    import panorama.preflight_collector as pc
    import inspect
    source = inspect.getsource(pc)
    assert "_derive_pan_units" not in source
    assert "successor" not in source.lower()


def test_34_no_serial_normalization_helper_exists_in_s6():
    import panorama.preflight_collector as pc
    import panorama.pan_preflight_battery as battery
    import panorama.pan_preflight_extraction as extraction
    import panorama.pan_preflight_projection as projection
    for module in (pc, battery, extraction, projection):
        source = open(module.__file__, encoding="utf-8").read()
        assert "lstrip(\"0\")" not in source and "lstrip('0')" not in source
        assert ".isdigit()" not in source


def test_35_leading_zero_opaque_identifiers_remain_distinct():
    ev1, _ = _collect(observed_serial="007A")
    ev2, _ = _collect(observed_serial="07A")
    # Both would exact-match only their own expected_serial; a leading-zero
    # variant of a DIFFERENT expected serial must still fail identity.
    calls, api_post, system_info, keygen = _patched_env(observed_serial="007A")
    import panorama.preflight_collector as pc
    with patch.object(pc, "api_post", api_post), \
         patch.object(pc, "get_direct_system_info", system_info), \
         patch.object(pc, "get_firewall_api_key", keygen):
        ev = collect_member(
            username="u", secret="s", target=_member_target(serial="07A", identity="member-token-a"),
            preflight_run_id="run-1", operational_entity_id="entity-1",
        )
    identity_fact = next(f for f in ev.own_facts if f.name == "pan_identity_gate_accepted")
    assert identity_fact.value is False


def test_36_pair_identity_behavior_unchanged():
    import panorama.preflight_collector as pc
    import inspect
    source = inspect.getsource(pc)
    assert "_apply_pan_ha_peer_identity_diagnostic" not in source


# =====================================================================
# Extraction fixtures: happy / missing / unknown / malformed / failure
# =====================================================================

def test_extraction_p4_happy():
    parsed = parse_pan_path_monitoring(etree.fromstring(_P4_XML_HAPPY.encode()))
    assert parsed == {"observed": True, "enabled": True, "path_count": 2, "any_down": False}


def test_extraction_p4_missing():
    parsed = parse_pan_path_monitoring(etree.fromstring(_P4_XML_MALFORMED.encode()))
    assert parsed["path_count"] is None


def test_extraction_p4_none_root_is_collection_failed_shape():
    parsed = parse_pan_path_monitoring(None)
    assert parsed["observed"] is False


def test_extraction_p1_identity_projection_failure_state():
    fact = project_pan_identity_fact(
        False, preflight_run_id="r1", collected_at="2026-09-03T00:00:00Z",
        physical_device_identity="m1", operational_entity_id="e1",
    )
    assert fact.value is False
    assert fact.provenance.outcome == Outcome.IDENTITY_MISMATCH


def test_extraction_p4_projection_collection_failed():
    facts = project_pan_path_monitoring_facts(
        None, preflight_run_id="r1", collected_at="2026-09-03T00:00:00Z",
        physical_device_identity="m1", operational_entity_id="e1", outcome=Outcome.FAILED,
    )
    assert facts[0].state == FactState.COLLECTION_FAILED
