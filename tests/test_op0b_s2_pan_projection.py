"""OP.0b S2 — PAN preflight fact projection (pure, no I/O, no lxml).

Contract: docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md
(FROZEN WITH REAL-ENV VALIDATION GATES). Proves `project_pan_preflight_facts`
turns an already-parsed field dict into S1 `PreflightFact`/
`PreflightMemberEvidence` instances correctly, per the task's §20 required
matrix. No XML, no network, no collector import.
"""
from __future__ import annotations

from panorama.pan_preflight_projection import project_pan_preflight_facts
from utils.failover.preflight_model import (
    FactCategory,
    FactState,
    Outcome,
    PreflightSnapshot,
    evaluate_coherence,
)

RUN_A = "preflight-run-aaaa"


def _fields(**overrides):
    base = {
        "enabled": "yes", "state": "active", "mode": "Active-Passive",
        "peer_state": "passive", "state_sync": "Complete",
        "peer_conn_status": "up", "peer_serial_num": "TOKEN_PEER",
        "local_serial_num": "TOKEN_LOCAL",
    }
    base.update(overrides)
    return base


def _member(fields, *, physical_device_identity="OPAQUE_A", collected_at="2026-09-03T12:00:00Z", **kwargs):
    return project_pan_preflight_facts(
        fields,
        preflight_run_id=RUN_A,
        collected_at=collected_at,
        physical_device_identity=physical_device_identity,
        operational_entity_id="pan_ha_pair__example",
        **kwargs,
    )


def _by_name(member, name):
    for fact in member.own_facts + member.peer_claim_facts:
        if fact.name == name:
            return fact
    raise KeyError(name)


# 18. All facts from one observation share the caller preflight_run_id ------

def test_all_facts_share_caller_preflight_run_id():
    member = _member(_fields())
    for fact in member.own_facts + member.peer_claim_facts:
        assert fact.provenance.preflight_run_id == RUN_A


# 19. Physical identity token and operational entity id remain separate -----

def test_physical_identity_and_operational_entity_id_remain_separate():
    member = _member(_fields())
    fact = _by_name(member, "local_state")
    assert fact.provenance.physical_device_identity == "OPAQUE_A"
    assert fact.provenance.operational_entity_id == "pan_ha_pair__example"
    assert fact.provenance.physical_device_identity != fact.provenance.operational_entity_id


# 20. Source plane = device runtime for runtime HA facts ---------------------

def test_source_plane_is_device_runtime_for_runtime_facts():
    member = _member(_fields())
    for fact in member.own_facts + member.peer_claim_facts:
        assert fact.provenance.source_plane.value == "device_runtime"


# 21. Source command contains no target identity -----------------------------

def test_source_command_contains_no_target_identity():
    member = _member(_fields(), physical_device_identity="OPAQUE_SPECIFIC_DEVICE_9")
    fact = _by_name(member, "local_state")
    assert fact.provenance.source_command == "show high-availability state"
    assert "OPAQUE_SPECIFIC_DEVICE_9" not in fact.provenance.source_command
    assert "9" not in fact.provenance.source_command


# 22. Peer serial claim maps to peer-relationship evidence, not own ---------

def test_peer_serial_claim_maps_to_peer_relationship_not_own_observation():
    member = _member(_fields())
    peer_claim_names = {f.name for f in member.peer_claim_facts}
    own_names = {f.name for f in member.own_facts}
    assert "peer_serial_claim" in peer_claim_names
    assert "peer_serial_claim" not in own_names
    fact = _by_name(member, "peer_serial_claim")
    assert fact.category is FactCategory.PEER_IDENTITY_RELATIONSHIP
    # And local's own serial claim is the opposite: own_facts, category A.
    assert "local_serial_claim" in own_names
    assert "local_serial_claim" not in peer_claim_names
    assert _by_name(member, "local_serial_claim").category is FactCategory.PHYSICAL_IDENTITY


# 23. One member projection does not synthesize another member --------------

def test_one_member_projection_produces_exactly_one_member_evidence():
    member = _member(_fields())
    # project_pan_preflight_facts returns a single PreflightMemberEvidence --
    # there is no code path here that could produce a second one from a
    # peer_state/peer_conn_status/peer_serial_num claim.
    assert member.__class__.__name__ == "PreflightMemberEvidence"
    snapshot = PreflightSnapshot(
        operational_unit_id="pan_ha_pair__example", vendor="panorama",
        unit_type="pan_ha_pair", preflight_run_id=RUN_A, members=(member,),
    )
    assert len(snapshot.members) == 1


# 24. UNKNOWN stays explicit --------------------------------------------------

def test_unknown_stays_explicit_for_absent_fields():
    member = _member(_fields(state_sync=None))
    fact = _by_name(member, "local_state_sync")
    assert fact.state is FactState.UNKNOWN
    assert fact.value is None


# 25. Collection failure representable without KNOWN_BAD inference ----------

def test_collection_failure_represented_without_known_bad_inference():
    assert not hasattr(FactState, "KNOWN_BAD")
    member = _member(None, outcome=Outcome.FAILED)
    all_facts = member.own_facts + member.peer_claim_facts
    assert all_facts  # facts are still produced, just all COLLECTION_FAILED
    for fact in all_facts:
        assert fact.state is FactState.COLLECTION_FAILED
        assert fact.value is None
        assert fact.provenance.outcome is Outcome.FAILED


# 26. Projected facts are accepted by S1 PreflightSnapshot ------------------

def test_projected_facts_accepted_by_preflight_snapshot():
    member_a = _member(_fields(), physical_device_identity="OPAQUE_A")
    member_b = _member(_fields(state="passive", peer_state="active"), physical_device_identity="OPAQUE_B", collected_at="2026-09-03T12:00:01Z")
    snapshot = PreflightSnapshot(
        operational_unit_id="pan_ha_pair__example", vendor="panorama",
        unit_type="pan_ha_pair", preflight_run_id=RUN_A, members=(member_a, member_b),
    )
    assert len(snapshot.members) == 2
    result = evaluate_coherence(snapshot)
    assert result.coherent is True


# 27. Same-run facts remain coherent -----------------------------------------

def test_same_run_facts_remain_coherent_through_projection():
    member_a = _member(_fields())
    member_b = _member(_fields(), physical_device_identity="OPAQUE_B")
    snapshot = PreflightSnapshot(
        operational_unit_id="pan_ha_pair__example", vendor="panorama",
        unit_type="pan_ha_pair", preflight_run_id=RUN_A, members=(member_a, member_b),
    )
    result = evaluate_coherence(snapshot)
    assert result.coherent is True
    assert result.reasons == ()


# 28. Mixed-run behavior governed by S1, not bypassed by PAN projection -----

def test_mixed_run_behavior_governed_by_s1_coherence_not_bypassed():
    member_a = project_pan_preflight_facts(
        _fields(), preflight_run_id="run-old", collected_at="2026-09-01T00:00:00Z",
        physical_device_identity="OPAQUE_A", operational_entity_id="pan_ha_pair__example",
    )
    member_b = _member(_fields(), physical_device_identity="OPAQUE_B")
    # Deliberately assembled into a snapshot declaring RUN_A while member_a's
    # facts were projected under "run-old" -- the projection layer does not
    # (and must not) silently fix this up; S1's evaluate_coherence is the
    # single authority that catches it.
    snapshot = PreflightSnapshot(
        operational_unit_id="pan_ha_pair__example", vendor="panorama",
        unit_type="pan_ha_pair", preflight_run_id=RUN_A, members=(member_a, member_b),
    )
    result = evaluate_coherence(snapshot)
    assert result.coherent is False
    assert result.reasons  # names the mismatching facts


# Malformed numeric field, at the projection layer (extraction-layer
# behavior is covered in tests/test_op0b_s2_pan_extraction.py) -------------

def test_malformed_numeric_field_degrades_to_unknown_not_a_crash():
    member = _member(_fields(local_max_flaps="not-a-number"))
    fact = _by_name(member, "local_max_flaps")
    assert fact.state is FactState.UNKNOWN
    assert fact.value is None


def test_well_formed_numeric_field_becomes_int():
    member = _member(_fields(local_priority="100"))
    fact = _by_name(member, "local_priority")
    assert fact.state is FactState.KNOWN
    assert fact.value == 100
    assert isinstance(fact.value, int)


# D-F3 is never applied here --------------------------------------------------

def test_no_flap_threshold_applied_to_flap_counters():
    member = _member(_fields(local_max_flaps="3", local_nonfunc_flap_cnt="0"))
    for name in ("local_max_flaps", "local_nonfunc_flap_cnt"):
        fact = _by_name(member, name)
        # A raw, unjudged counter -- no PASS/FAIL/healthy state exists to
        # assert against, because none is ever computed here.
        assert fact.state is FactState.KNOWN
        assert fact.category is FactCategory.TRANSITION_FLAP_HISTORY
