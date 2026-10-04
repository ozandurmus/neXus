"""OP.0b S3 — Check Point preflight fact projection (pure, no I/O).

Contract: docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md
(FROZEN WITH REAL-ENV VALIDATION GATES). Proves `project_cp_preflight_facts`
turns an already-parsed field dict into S1 `PreflightFact`/
`PreflightMemberEvidence` instances correctly, per the task's §17 required
matrix. No SSH, no network, no collector import.
"""
from __future__ import annotations

from checkpoint.cp_preflight_projection import project_cp_preflight_facts
from utils.failover.preflight_model import (
    FactCategory,
    FactContext,
    FactState,
    Outcome,
    PreflightSnapshot,
    ShellProfile,
    evaluate_coherence,
)

RUN_A = "preflight-run-cp-aaaa"


def _fields(**overrides):
    base = {
        "local_role": "ACTIVE",
        "cluster_mode": "ha_new_mode",
        "peer_row_states": ("STANDBY",),
        "local_attention": False,
    }
    base.update(overrides)
    return base


def _member(fields, *, physical_device_identity="OPAQUE_A", collected_at="2026-09-03T12:00:00Z",
            operational_entity_id="cp_clusterxl_cluster__example", **kwargs):
    return project_cp_preflight_facts(
        fields,
        preflight_run_id=RUN_A,
        collected_at=collected_at,
        physical_device_identity=physical_device_identity,
        operational_entity_id=operational_entity_id,
        **kwargs,
    )


def _by_name(member, name):
    for fact in member.own_facts + member.peer_claim_facts:
        if fact.name == name:
            return fact
    raise KeyError(name)


# 1/2/3 -- role parse -------------------------------------------------------

def test_1_active_local_role_projected():
    fact = _by_name(_member(_fields(local_role="ACTIVE")), "ha_local_role")
    assert fact.state is FactState.KNOWN
    assert fact.value == "ACTIVE"
    assert fact.category is FactCategory.RUNTIME_HA_STATE


def test_2_standby_local_role_projected():
    fact = _by_name(_member(_fields(local_role="STANDBY")), "ha_local_role")
    assert fact.state is FactState.KNOWN
    assert fact.value == "STANDBY"


def test_3_unknown_role_stays_unknown():
    fact = _by_name(_member(_fields(local_role=None)), "ha_local_role")
    assert fact.state is FactState.UNKNOWN
    assert fact.value is None


# 4/5 -- cluster mode ---------------------------------------------------------

def test_4_cluster_mode_parsed_where_frozen():
    fact = _by_name(_member(_fields(cluster_mode="ha_new_mode")), "ha_cluster_mode")
    assert fact.state is FactState.KNOWN
    assert fact.value == "ha_new_mode"


def test_5_unknown_mode_stays_unknown():
    fact = _by_name(_member(_fields(cluster_mode="unknown")), "ha_cluster_mode")
    assert fact.state is FactState.UNKNOWN
    assert fact.value is None


def test_5b_absent_mode_key_stays_unknown():
    fields = _fields()
    del fields["cluster_mode"]
    fact = _by_name(_member(fields), "ha_cluster_mode")
    assert fact.state is FactState.UNKNOWN


# 6/7/8 -- physical / VSX physical / VSID context -----------------------------

def test_6_physical_clusterxl_context_projected_correctly():
    member = _member(_fields(), context=FactContext.physical())
    fact = _by_name(member, "ha_local_role")
    assert fact.provenance.context.kind.value == "physical"


def test_7_vsx_physical_context_projected_correctly():
    member = _member(_fields(), context=FactContext.physical(), operational_entity_id="cp_vsx_cluster__example")
    fact = _by_name(member, "ha_local_role")
    assert fact.provenance.context.kind.value == "physical"
    assert fact.provenance.operational_entity_id == "cp_vsx_cluster__example"


def test_8_vsid_context_projected_independently():
    member = project_cp_preflight_facts(
        _fields(local_role="STANDBY"),
        preflight_run_id=RUN_A,
        collected_at="2026-09-03T12:00:00Z",
        physical_device_identity="OPAQUE_A",
        operational_entity_id="cp_vsx_virtual_system__example__vsid_3",
        context=FactContext.vsid("3"),
    )
    fact = _by_name(member, "ha_local_role")
    assert fact.provenance.context.kind.value == "vsid"
    assert fact.provenance.context.identifier == "3"


# 9 -- VS fact does not inherit physical-host role ---------------------------

def test_9_vs_fact_does_not_inherit_physical_host_role():
    physical = _member(_fields(local_role="ACTIVE"), context=FactContext.physical())
    vs = project_cp_preflight_facts(
        _fields(local_role="STANDBY"),
        preflight_run_id=RUN_A,
        collected_at="2026-09-03T12:00:00Z",
        physical_device_identity="OPAQUE_A",
        operational_entity_id="cp_vsx_virtual_system__example__vsid_3",
        context=FactContext.vsid("3"),
    )
    assert _by_name(physical, "ha_local_role").value == "ACTIVE"
    assert _by_name(vs, "ha_local_role").value == "STANDBY"


# 10 -- one physical member observation does not synthesize peer observation -

def test_10_no_peer_rows_does_not_synthesize_a_peer_member():
    member = _member(_fields(peer_row_states=()))
    peer_facts = member.peer_claim_facts
    assert len(peer_facts) == 1
    assert peer_facts[0].state is FactState.UNKNOWN
    assert peer_facts[0].value is None
    # Still exactly one PreflightMemberEvidence -- no second member appears.
    snapshot = PreflightSnapshot(
        operational_unit_id="cp_clusterxl_cluster__example", vendor="checkpoint",
        unit_type="cp_clusterxl_cluster", preflight_run_id=RUN_A, members=(member,),
    )
    assert len(snapshot.members) == 1


def test_10b_multiple_peer_rows_each_become_a_claim_fact():
    member = _member(_fields(peer_row_states=("STANDBY", "DOWN")))
    names = {f.name for f in member.peer_claim_facts}
    assert "peer_row_state_1" in names and "peer_row_state_2" in names
    assert _by_name(member, "peer_row_state_1").value == "STANDBY"
    assert _by_name(member, "peer_row_state_2").value == "DOWN"


def test_peer_row_state_is_a_claim_not_own_observation():
    member = _member(_fields())
    own_names = {f.name for f in member.own_facts}
    peer_names = {f.name for f in member.peer_claim_facts}
    assert "peer_row_state_1" in peer_names
    assert "peer_row_state_1" not in own_names
    assert _by_name(member, "peer_row_state_1").category is FactCategory.PEER_IDENTITY_RELATIONSHIP


# 11 -- all projected facts share caller preflight_run_id -------------------

def test_11_all_facts_share_caller_preflight_run_id():
    member = _member(_fields())
    for fact in member.own_facts + member.peer_claim_facts:
        assert fact.provenance.preflight_run_id == RUN_A


# 12 -- physical identity and operational identity remain separate ----------

def test_12_physical_identity_and_operational_entity_id_remain_separate():
    fact = _by_name(_member(_fields()), "ha_local_role")
    assert fact.provenance.physical_device_identity == "OPAQUE_A"
    assert fact.provenance.operational_entity_id == "cp_clusterxl_cluster__example"
    assert fact.provenance.physical_device_identity != fact.provenance.operational_entity_id


# 13 -- source_plane = device_runtime ----------------------------------------

def test_13_source_plane_is_device_runtime():
    member = _member(_fields())
    for fact in member.own_facts + member.peer_claim_facts:
        assert fact.provenance.source_plane.value == "device_runtime"


# 14 -- transport/source-command provenance safe -----------------------------

def test_14_source_command_and_shell_profile_are_safe():
    member = _member(_fields(), physical_device_identity="OPAQUE_SPECIFIC_DEVICE_9",
                     shell_profile=ShellProfile.EXEC_EXPERT)
    fact = _by_name(member, "ha_local_role")
    assert fact.provenance.source_command == "cphaprob stat"
    assert "OPAQUE_SPECIFIC_DEVICE_9" not in fact.provenance.source_command
    assert "9" not in fact.provenance.source_command
    assert fact.provenance.shell_profile is ShellProfile.EXEC_EXPERT


# 15 -- no raw command output serialized -------------------------------------

def test_15_no_raw_command_output_in_projected_facts():
    member = _member(_fields())
    for fact in member.own_facts + member.peer_claim_facts:
        rendered = fact.to_dict()
        assert "192.0.2" not in repr(rendered)
        assert "member-a" not in repr(rendered)
        assert "member-b" not in repr(rendered)


# 16/17 -- missing/malformed input degrades safely ---------------------------

def test_16_missing_expected_field_degrades_safely():
    fields = _fields()
    del fields["local_role"]
    fact = _by_name(_member(fields), "ha_local_role")
    assert fact.state is FactState.UNKNOWN


def test_17_malformed_local_attention_type_is_treated_as_bool():
    # local_attention is already a bool from the extraction layer -- the
    # projection layer coerces defensively but never crashes on odd input.
    fact = _by_name(_member(_fields(local_attention=1)), "local_member_attention")
    assert fact.state is FactState.KNOWN
    assert fact.value is True


# 18 -- no future-command evidence fabricated --------------------------------

def test_18_no_facts_projected_for_uncollectable_categories():
    member = _member(_fields())
    names = {f.name for f in member.own_facts + member.peer_claim_facts}
    forbidden_substrings = ("sync", "pnote", "policy_parity", "preemption", "failover_history")
    for name in names:
        for forbidden in forbidden_substrings:
            assert forbidden not in name


# 19/20/21 -- categories that must stay NOT_COLLECTED / never fabricated ----

def test_19_state_session_sync_never_projected():
    member = _member(_fields())
    assert not any(f.category.value == "state_session_synchronization" for f in member.own_facts + member.peer_claim_facts)


def test_20_pnote_and_failover_history_never_projected():
    member = _member(_fields())
    names = {f.name for f in member.own_facts + member.peer_claim_facts}
    assert not any("pnote" in n or "failover_history" in n for n in names)


def test_21_no_configured_preemption_fact_fabricated():
    member = _member(_fields())
    assert not any(f.category is FactCategory.ELECTION_PREEMPTION_BEHAVIOR for f in member.own_facts + member.peer_claim_facts)


# 22/23 -- same-run / mixed-run coherence governed by S1 ----------------------

def test_22_same_run_facts_remain_coherent():
    member_a = _member(_fields(local_role="ACTIVE"), physical_device_identity="OPAQUE_A")
    member_b = _member(_fields(local_role="STANDBY", peer_row_states=("ACTIVE",)), physical_device_identity="OPAQUE_B",
                        collected_at="2026-09-03T12:00:01Z")
    snapshot = PreflightSnapshot(
        operational_unit_id="cp_clusterxl_cluster__example", vendor="checkpoint",
        unit_type="cp_clusterxl_cluster", preflight_run_id=RUN_A, members=(member_a, member_b),
    )
    result = evaluate_coherence(snapshot)
    assert result.coherent is True
    assert result.reasons == ()


def test_23_mixed_run_incoherence_is_governed_by_s1_not_bypassed():
    member_a = project_cp_preflight_facts(
        _fields(), preflight_run_id="run-old", collected_at="2026-09-01T00:00:00Z",
        physical_device_identity="OPAQUE_A", operational_entity_id="cp_clusterxl_cluster__example",
    )
    member_b = _member(_fields(), physical_device_identity="OPAQUE_B")
    snapshot = PreflightSnapshot(
        operational_unit_id="cp_clusterxl_cluster__example", vendor="checkpoint",
        unit_type="cp_clusterxl_cluster", preflight_run_id=RUN_A, members=(member_a, member_b),
    )
    result = evaluate_coherence(snapshot)
    assert result.coherent is False
    assert result.reasons


# 24 -- synthetic identifiers only (this whole suite uses only OPAQUE_*/run-* literals) --

def test_24_synthetic_identifiers_only():
    member = _member(_fields(), physical_device_identity="OPAQUE_A")
    assert member.physical_device_identity == "OPAQUE_A"


# Collection failure represented without KNOWN_BAD inference -----------------

def test_collection_failure_represented_without_known_bad_inference():
    assert not hasattr(FactState, "KNOWN_BAD")
    member = _member(None, outcome=Outcome.FAILED)
    all_facts = member.own_facts + member.peer_claim_facts
    assert all_facts
    for fact in all_facts:
        assert fact.state is FactState.COLLECTION_FAILED
        assert fact.value is None
        assert fact.provenance.outcome is Outcome.FAILED
