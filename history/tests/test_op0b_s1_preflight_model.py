"""OP.0b S1 — preflight fact + provenance model (pure, no I/O).

Contract: docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md
(FROZEN WITH REAL-ENV VALIDATION GATES). Targeted tests only, per the S1
task's required matrix -- no future-collector or future-readiness behavior
is exercised here.
"""
from __future__ import annotations

import dataclasses

import pytest

from utils.failover.preflight_model import (
    ContextKind,
    CoherenceResult,
    FactCategory,
    FactContext,
    FactState,
    OpaqueToken,
    Outcome,
    PreflightFact,
    PreflightMemberEvidence,
    PreflightSnapshot,
    Provenance,
    RUNTIME_COHERENCE_CATEGORIES,
    ShellProfile,
    SourceOrigin,
    Transport,
    evaluate_coherence,
)

RUN_A = "preflight-run-aaaa"
RUN_B = "preflight-run-bbbb"


def _prov(
    *,
    collected_at: str = "2026-09-03T12:00:00Z",
    preflight_run_id: str = RUN_A,
    source_vendor: str = "panorama",
    source_plane: SourceOrigin = SourceOrigin.DEVICE_RUNTIME,
    transport: Transport = Transport.DIRECT_API,
    physical_device_identity: str = "OPAQUE_MEMBER_A",
    operational_entity_id: str = "pan_ha_pair__example",
    context: FactContext | None = None,
    outcome: Outcome = Outcome.SUCCESS,
    **kwargs,
) -> Provenance:
    return Provenance(
        collected_at=collected_at,
        preflight_run_id=preflight_run_id,
        source_vendor=source_vendor,
        source_plane=source_plane,
        transport=transport,
        physical_device_identity=OpaqueToken(physical_device_identity),
        operational_entity_id=operational_entity_id,
        context=context or FactContext.physical(),
        outcome=outcome,
        **kwargs,
    )


def _runtime_fact(name: str, *, run_id: str = RUN_A, collected_at: str = "2026-09-03T12:00:00Z", value: str = "up") -> PreflightFact:
    return PreflightFact(
        name=name,
        category=FactCategory.LINK_HEALTH,
        state=FactState.KNOWN,
        value=value,
        provenance=_prov(preflight_run_id=run_id, collected_at=collected_at),
    )


# 1. Evidence identity and operational identity remain separate -------------

def test_physical_and_operational_identity_are_separate_fields():
    member = PreflightMemberEvidence(physical_device_identity=OpaqueToken("OPAQUE_A"))
    snapshot = PreflightSnapshot(
        operational_unit_id="pan_ha_pair__example",
        vendor="panorama",
        unit_type="pan_ha_pair",
        preflight_run_id=RUN_A,
        members=(member,),
    )
    # Different objects, different fields -- no shared attribute could ever
    # collapse a physical member id into the operational unit id.
    assert member.physical_device_identity != snapshot.operational_unit_id
    assert not hasattr(snapshot, "physical_device_identity")
    assert not hasattr(member, "operational_unit_id")


# 2/3. Same-run coherence vs. mixed-run incoherence --------------------------

def test_runtime_facts_same_run_id_are_coherent():
    member_a = PreflightMemberEvidence(
        physical_device_identity=OpaqueToken("OPAQUE_A"),
        own_facts=(_runtime_fact("conn_ha1_status"),),
    )
    member_b = PreflightMemberEvidence(
        physical_device_identity=OpaqueToken("OPAQUE_B"),
        own_facts=(_runtime_fact("conn_ha1_status"),),
    )
    snapshot = PreflightSnapshot(
        operational_unit_id="pan_ha_pair__example", vendor="panorama",
        unit_type="pan_ha_pair", preflight_run_id=RUN_A, members=(member_a, member_b),
    )
    result = evaluate_coherence(snapshot)
    assert result.coherent is True
    assert result.reasons == ()


def test_runtime_facts_mixed_run_id_are_incoherent():
    member_a = PreflightMemberEvidence(
        physical_device_identity=OpaqueToken("OPAQUE_A"),
        own_facts=(_runtime_fact("conn_ha1_status", run_id=RUN_A),),
    )
    member_b = PreflightMemberEvidence(
        physical_device_identity=OpaqueToken("OPAQUE_B"),
        # Stale evidence from an earlier preflight -- must not silently pass.
        own_facts=(_runtime_fact("conn_ha1_status", run_id=RUN_B),),
    )
    snapshot = PreflightSnapshot(
        operational_unit_id="pan_ha_pair__example", vendor="panorama",
        unit_type="pan_ha_pair", preflight_run_id=RUN_A, members=(member_a, member_b),
    )
    result = evaluate_coherence(snapshot)
    assert result.coherent is False
    assert len(result.reasons) == 1
    assert "conn_ha1_status" in result.reasons[0]


# 4/5. Configuration intent: independent provenance, doesn't force incoherence

def test_older_configuration_intent_does_not_force_runtime_incoherence():
    member_a = PreflightMemberEvidence(
        physical_device_identity=OpaqueToken("OPAQUE_A"),
        own_facts=(_runtime_fact("conn_ha1_status"),),
    )
    member_b = PreflightMemberEvidence(
        physical_device_identity=OpaqueToken("OPAQUE_B"),
        own_facts=(_runtime_fact("conn_ha1_status"),),
    )
    old_config_fact = PreflightFact(
        name="configured_peer_ha1",
        category=FactCategory.CONFIGURATION_INTENT,
        state=FactState.KNOWN,
        value="OPAQUE_HA1_PEER_TOKEN",
        provenance=_prov(
            source_plane=SourceOrigin.DEVICE_CONFIG,
            preflight_run_id=RUN_A,
            collection_run_id="earlier-inventory-run-9999",
            original_collected_at="2026-08-20T00:00:00Z",
        ),
    )
    snapshot = PreflightSnapshot(
        operational_unit_id="pan_ha_pair__example", vendor="panorama",
        unit_type="pan_ha_pair", preflight_run_id=RUN_A, members=(member_a, member_b),
        configuration_facts=(old_config_fact,),
    )
    result = evaluate_coherence(snapshot)
    assert result.coherent is True
    assert result.stale_intent_present is True


def test_configuration_intent_retains_independent_provenance():
    fact = PreflightFact(
        name="configured_peer_ha1",
        category=FactCategory.CONFIGURATION_INTENT,
        state=FactState.KNOWN,
        value="OPAQUE_HA1_PEER_TOKEN",
        provenance=_prov(
            source_plane=SourceOrigin.DEVICE_CONFIG,
            collection_run_id="earlier-inventory-run-9999",
            original_collected_at="2026-08-20T00:00:00Z",
        ),
    )
    assert fact.provenance.collection_run_id == "earlier-inventory-run-9999"
    assert fact.provenance.original_collected_at == "2026-08-20T00:00:00Z"
    # And it is still stamped with *this* preflight's own run/collected_at too.
    assert fact.provenance.preflight_run_id == RUN_A


# 6/7. Member skew: real when timestamps exist, never a fake zero -----------

def test_missing_timestamps_do_not_produce_fake_zero_skew():
    member_a = PreflightMemberEvidence(
        physical_device_identity=OpaqueToken("OPAQUE_A"),
        own_facts=(_runtime_fact("conn_ha1_status", collected_at="2026-09-03T12:00:00Z"),),
    )
    member_b = PreflightMemberEvidence(
        physical_device_identity=OpaqueToken("OPAQUE_B"),
        # Malformed/unparseable timestamp -- must not be read as "now" or "0".
        own_facts=(_runtime_fact("conn_ha1_status", collected_at="not-a-timestamp"),),
    )
    snapshot = PreflightSnapshot(
        operational_unit_id="pan_ha_pair__example", vendor="panorama",
        unit_type="pan_ha_pair", preflight_run_id=RUN_A, members=(member_a, member_b),
    )
    result = evaluate_coherence(snapshot)
    assert result.member_skew_ms is None


def test_member_skew_is_computed_deterministically_when_timestamps_exist():
    member_a = PreflightMemberEvidence(
        physical_device_identity=OpaqueToken("OPAQUE_A"),
        own_facts=(_runtime_fact("conn_ha1_status", collected_at="2026-09-03T12:00:00Z"),),
    )
    member_b = PreflightMemberEvidence(
        physical_device_identity=OpaqueToken("OPAQUE_B"),
        own_facts=(_runtime_fact("conn_ha1_status", collected_at="2026-09-03T12:00:01Z"),),
    )
    snapshot = PreflightSnapshot(
        operational_unit_id="pan_ha_pair__example", vendor="panorama",
        unit_type="pan_ha_pair", preflight_run_id=RUN_A, members=(member_a, member_b),
    )
    result = evaluate_coherence(snapshot)
    assert result.member_skew_ms == 1000
    # Deterministic -- re-running yields the identical result.
    assert evaluate_coherence(snapshot) == result


# 8. No numeric freshness threshold is implicitly applied --------------------

def test_no_freshness_threshold_constant_exists_in_module():
    import utils.failover.preflight_model as m

    src = open(m.__file__, encoding="utf-8").read()
    # D-F1/D-F2/D-F3 stay open product-owner decisions; a magic minute/
    # millisecond/count threshold here would silently resolve one.
    for token in ("300", "600", "3600", "5 * 60", "10 * 60"):
        assert token not in src, f"found a plausible magic threshold literal: {token!r}"
    assert not hasattr(m, "MAX_AGE_SECONDS")
    assert not hasattr(m, "SKEW_TOLERANCE_MS")
    assert not hasattr(m, "FLAP_THRESHOLD")


# 9/10. UNKNOWN is explicit; distinct from False/0/empty and from KNOWN_BAD --

def test_unknown_state_is_not_false_zero_or_empty():
    fact = PreflightFact(
        name="conn_ha2_status", category=FactCategory.LINK_HEALTH,
        state=FactState.UNKNOWN, provenance=_prov(),
    )
    assert fact.value is None
    assert fact.state is FactState.UNKNOWN
    assert fact.state != False  # noqa: E712 -- deliberately checking identity of meaning, not truthiness
    assert fact.to_dict()["value"] is None
    assert fact.to_dict()["state"] == "unknown"


def test_collection_failed_is_distinct_from_known_bad_semantics():
    # S1 does not define a KNOWN_BAD state at all -- that is readiness-verdict
    # vocabulary (a later slice's job), never evidence vocabulary.
    assert not hasattr(FactState, "KNOWN_BAD")
    fact = PreflightFact(
        name="cphaprob_stat_read", category=FactCategory.RUNTIME_HA_STATE,
        state=FactState.COLLECTION_FAILED, provenance=_prov(source_vendor="checkpoint", outcome=Outcome.FAILED),
    )
    assert fact.state is FactState.COLLECTION_FAILED
    assert fact.value is None


# 11/12. Peer claim without independent observation; no phantom member -------

def test_peer_claim_can_exist_without_independent_peer_observation():
    peer_claim = PreflightFact(
        name="peer_conn_status", category=FactCategory.PEER_IDENTITY_RELATIONSHIP,
        state=FactState.KNOWN, value="up", provenance=_prov(),
    )
    member_a = PreflightMemberEvidence(
        physical_device_identity=OpaqueToken("OPAQUE_A"),
        peer_claim_facts=(peer_claim,),
    )
    # Member A carries a claim about its peer with no own_facts observation
    # of that peer at all -- exactly the one-sided-claim case the contract
    # requires to stay representable.
    assert member_a.own_facts == ()
    assert member_a.peer_claim_facts == (peer_claim,)


def test_one_members_peer_claim_does_not_synthesize_a_second_member():
    peer_claim = PreflightFact(
        name="peer_conn_status", category=FactCategory.PEER_IDENTITY_RELATIONSHIP,
        state=FactState.KNOWN, value="up", provenance=_prov(),
    )
    member_a = PreflightMemberEvidence(
        physical_device_identity=OpaqueToken("OPAQUE_A"),
        peer_claim_facts=(peer_claim,),
    )
    snapshot = PreflightSnapshot(
        operational_unit_id="pan_ha_pair__example", vendor="panorama",
        unit_type="pan_ha_pair", preflight_run_id=RUN_A, members=(member_a,),
    )
    # Exactly one PreflightMemberEvidence exists -- the peer claim lives
    # *inside* member A's own evidence bundle, never as its own entry in
    # `snapshot.members`. This is what makes the PAN phantom-member defect
    # (assessment.py `_pan_states` counting a lone member's peer claim as a
    # second observed member) structurally impossible to repeat here.
    assert len(snapshot.members) == 1


# 13. Raw serial/IP/config values are not required by the model -------------

def test_model_does_not_require_raw_identity_values():
    # Only an opaque token is required for physical identity -- never a raw
    # serial/IP/hostname. Constructing a full snapshot needs no such value.
    member = PreflightMemberEvidence(physical_device_identity=OpaqueToken("OPAQUE_TOKEN_1234"))
    snapshot = PreflightSnapshot(
        operational_unit_id="pan_ha_pair__example", vendor="panorama",
        unit_type="pan_ha_pair", preflight_run_id=RUN_A, members=(member,),
    )
    rendered = repr(snapshot) + repr(member)
    for raw_marker in ("192.0.2.", "0011", "SERIAL"):
        assert raw_marker not in rendered


def test_overlong_string_value_is_rejected_as_unsafe():
    with pytest.raises(ValueError):
        PreflightFact(
            name="raw_dump", category=FactCategory.RUNTIME_HA_STATE,
            state=FactState.KNOWN, value="x" * 200, provenance=_prov(),
        )


# 14. Safe serialization preserves provenance and explicit unknown state -----

def test_serialization_preserves_provenance_and_unknown_state():
    fact = PreflightFact(
        name="conn_ha2_status", category=FactCategory.LINK_HEALTH,
        state=FactState.UNKNOWN, provenance=_prov(shell_profile=None),
    )
    record = fact.to_dict()
    assert record["state"] == "unknown"
    assert record["value"] is None
    assert record["provenance"]["preflight_run_id"] == RUN_A
    assert record["provenance"]["physical_device_identity"] == "OPAQUE_MEMBER_A"
    assert isinstance(record["provenance"]["physical_device_identity"], str)


def test_coherence_result_serialization_round_trips_shape():
    result = CoherenceResult(coherent=False, reasons=("x",), member_skew_ms=None, stale_intent_present=True)
    record = result.to_dict()
    assert record == {
        "coherent": False, "reasons": ["x"], "member_skew_ms": None, "stale_intent_present": True,
    }


# 15. Immutability ------------------------------------------------------------

def test_preflight_fact_is_frozen():
    fact = PreflightFact(
        name="conn_ha1_status", category=FactCategory.LINK_HEALTH,
        state=FactState.KNOWN, value="up", provenance=_prov(),
    )
    with pytest.raises(dataclasses.FrozenInstanceError):
        fact.value = "down"  # type: ignore[misc]


def test_provenance_and_snapshot_are_frozen():
    prov = _prov()
    with pytest.raises(dataclasses.FrozenInstanceError):
        prov.outcome = Outcome.FAILED  # type: ignore[misc]

    snapshot = PreflightSnapshot(
        operational_unit_id="x", vendor="panorama", unit_type="pan_ha_pair", preflight_run_id=RUN_A,
    )
    with pytest.raises(dataclasses.FrozenInstanceError):
        snapshot.vendor = "checkpoint"  # type: ignore[misc]


# 16. Category C and D facts retain different source planes -----------------

def test_configuration_and_runtime_facts_retain_distinct_source_planes():
    config_fact = PreflightFact(
        name="configured_peer_ha1", category=FactCategory.CONFIGURATION_INTENT,
        state=FactState.KNOWN, value="OPAQUE_HA1_PEER_TOKEN",
        provenance=_prov(source_plane=SourceOrigin.DEVICE_CONFIG),
    )
    runtime_fact = PreflightFact(
        name="conn_ha1_status", category=FactCategory.LINK_HEALTH,
        state=FactState.KNOWN, value="up",
        provenance=_prov(source_plane=SourceOrigin.DEVICE_RUNTIME),
    )
    assert config_fact.provenance.source_plane is SourceOrigin.DEVICE_CONFIG
    assert runtime_fact.provenance.source_plane is SourceOrigin.DEVICE_RUNTIME
    assert config_fact.provenance.source_plane != runtime_fact.provenance.source_plane


# 17. Presentation-only category cannot masquerade as identity --------------

def test_presentation_only_category_is_not_an_identity_category():
    hostname_label = PreflightFact(
        name="display_hostname", category=FactCategory.PRESENTATION_ONLY,
        state=FactState.KNOWN, value="fw-example", provenance=_prov(),
    )
    assert hostname_label.category is FactCategory.PRESENTATION_ONLY
    assert hostname_label.category is not FactCategory.PHYSICAL_IDENTITY
    assert hostname_label.category is not FactCategory.OPERATIONAL_HA_ENTITY_IDENTITY
    # A presentation-only fact's category alone can never register it in the
    # runtime same-run coherence gate either -- that set is closed over D/E/F/G/J/K.
    assert hostname_label.category not in RUNTIME_COHERENCE_CATEGORIES


# 18. Unsupported context/value remains explicit -----------------------------

def test_unsupported_state_is_explicit_not_silently_dropped():
    fact = PreflightFact(
        name="vs_failover_stats", category=FactCategory.TRANSITION_FLAP_HISTORY,
        state=FactState.UNSUPPORTED, provenance=_prov(source_vendor="checkpoint", outcome=Outcome.UNSUPPORTED),
    )
    assert fact.state is FactState.UNSUPPORTED
    assert fact.value is None
    assert fact.to_dict()["state"] == "unsupported"


def test_vsid_context_requires_an_identifier_but_physical_forbids_one():
    ctx = FactContext.vsid("7")
    assert ctx.kind is ContextKind.VSID
    assert ctx.identifier == "7"
    with pytest.raises(ValueError):
        FactContext(kind=ContextKind.VSID)  # missing identifier
    with pytest.raises(ValueError):
        FactContext(kind=ContextKind.PHYSICAL, identifier="should-not-be-here")


# 26. Privacy: opaque identity accepted without a raw serial/IP -------------

def test_privacy_opaque_token_accepted_without_raw_serial_or_ip():
    # Synthetic/example values only -- no real operational identity.
    opaque = OpaqueToken("PAN_TOKEN_5b6c7d8e9f")
    member = PreflightMemberEvidence(physical_device_identity=opaque)
    fact = PreflightFact(
        name="local_serial_claim", category=FactCategory.PHYSICAL_IDENTITY,
        state=FactState.KNOWN, value=opaque,
        provenance=_prov(physical_device_identity=str(opaque)),
    )
    record = fact.to_dict()
    assert record["value"] == "PAN_TOKEN_5b6c7d8e9f"
    for raw_marker in ("192.0.2.", "0011223344556677"):
        assert raw_marker not in repr(member) + repr(fact)
