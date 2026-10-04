"""M10.2 -- capability-state resolver core (stages 0-3).

Proves the frozen contract:
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` sections 4
(`AC-2`) and 5 (`AC-1`, `AC-3`, `AC-4`, `AC-5`), plus boundary/vocabulary
separation (`AC-2`, `AC-6`) from the job lifecycle (X1), the `OP.2` action
state (X2) and `utils.action_taxonomy` tokens.
"""
from __future__ import annotations

import pytest

from console.jobs import TERMINAL_STATES
from utils.action_taxonomy import ACTION_CLASSES
from utils.operate.states import ActionState
from utils.registry_evidence_reconciliation import (
    EVIDENCE_ONLY,
    RECONCILED,
    RECONCILIATION_UNKNOWN,
    REGISTRY_DISABLED,
    REGISTRY_ONLY,
    RegistrySide,
)
from utils.capability_state_resolver import (
    CAPABILITY_QUALIFIER_KINDS,
    CAPABILITY_STATE_VALUES,
    QUALIFIER_IDENTITY_TRANSLATION_REQUIRED,
    QUALIFIER_MEMBER_SPECIFIC,
    QUALIFIER_NOT_SCHEDULED,
    QUALIFIER_PARTIAL,
    QUALIFIER_SCHEDULE_UNKNOWN,
    QUALIFIER_SOURCE_TRUST_LIMITED,
    QUALIFIER_STALE,
    CapabilityPolicy,
    CapabilityQualifier,
    CapabilityState,
    Comparability,
    ComparabilityFacts,
    ConfigurationEvidence,
    Diagnostic,
    EntityApplicability,
    FreshnessFacts,
    IdentityConflict,
    LadderInputs,
    Omitted,
    PrimaryStatus,
    QualifierInputs,
    Resolved,
    Ri1Comparability,
    Stage1Inputs,
    Stage1Outcome,
    SurfaceEligibility,
    SurfaceOmissionReason,
    VendorSupport,
    evaluate_ri1_comparability,
    evaluate_stage1,
    resolve_primary_status,
    resolve_qualifiers,
    resolve_union_tag,
    to_wire,
)

JOB_LIFECYCLE_TOKENS = frozenset({"queued", "running", "console_restarted"}) | TERMINAL_STATES
ACTION_STATE_TOKENS = frozenset(state.value for state in ActionState)
ACTION_TAXONOMY_TOKENS = frozenset(
    token
    for action_class in ACTION_CLASSES
    for token in (action_class.id, action_class.label, action_class.refusal_code)
    if token is not None
)


# --------------------------------------------------------------------------
# AC-1 -- stage 0 / result algebra
# --------------------------------------------------------------------------

class TestStage0UnionTag:
    def test_v_a_surface_absent_is_omitted_not_shipped(self):
        result = resolve_union_tag(SurfaceEligibility.SURFACE_ABSENT)
        assert result == Omitted(reason=SurfaceOmissionReason.NOT_SHIPPED)

    def test_v_b_surface_absent_with_diagnostic_still_not_shipped(self):
        diag = Diagnostic(detail="both inputs disagreed")
        result = resolve_union_tag(SurfaceEligibility.SURFACE_ABSENT, diagnostic=diag)
        assert result == Omitted(reason=SurfaceOmissionReason.NOT_SHIPPED, diagnostic=diag)

    def test_v_c_surface_present_is_resolved(self):
        assert resolve_union_tag(SurfaceEligibility.SURFACE_PRESENT) == Resolved()

    def test_v_d_missing_d1_is_omitted_unresolvable(self):
        result = resolve_union_tag(None)
        assert result == Omitted(reason=SurfaceOmissionReason.SURFACE_ELIGIBILITY_UNRESOLVABLE)

    def test_v_d_malformed_d1_is_omitted_unresolvable(self):
        result = resolve_union_tag("not-a-real-value")
        assert result == Omitted(reason=SurfaceOmissionReason.SURFACE_ELIGIBILITY_UNRESOLVABLE)

    def test_v_d_explicit_unresolvable_value_is_omitted_unresolvable(self):
        result = resolve_union_tag(SurfaceEligibility.SURFACE_ELIGIBILITY_UNRESOLVABLE)
        assert result.reason is SurfaceOmissionReason.SURFACE_ELIGIBILITY_UNRESOLVABLE

    def test_omitted_carries_no_capability_output_fields(self):
        """AC-1: OMITTED is a structurally distinct type -- exactly `reason`
        and `diagnostic`, never a None-filled primary_status/qualifiers."""
        import dataclasses

        field_names = {f.name for f in dataclasses.fields(Omitted)}
        assert field_names == {"reason", "diagnostic"}

    def test_resolved_carries_no_stage0_payload(self):
        import dataclasses

        assert dataclasses.fields(Resolved) == ()

    def test_omitted_and_resolved_are_distinct_types(self):
        assert not isinstance(resolve_union_tag(SurfaceEligibility.SURFACE_PRESENT), Omitted)
        assert not isinstance(resolve_union_tag(None), Resolved)


# --------------------------------------------------------------------------
# AC-2 -- vocabulary
# --------------------------------------------------------------------------

class TestCapabilityStateVocabulary:
    def test_exactly_nine_values(self):
        assert CAPABILITY_STATE_VALUES == {
            "NOT_APPLICABLE", "DEVICE_DISABLED", "UNSUPPORTED", "NOT_ENROLLED",
            "POLICY_DISABLED", "COLLECTION_FAILED", "UNKNOWN", "NOT_CONFIGURED",
            "AVAILABLE",
        }

    def test_not_shipped_is_never_a_capability_state(self):
        assert "NOT_SHIPPED" not in CAPABILITY_STATE_VALUES

    def test_unknown_requires_a_reason(self):
        with pytest.raises(ValueError):
            PrimaryStatus(CapabilityState.AVAILABLE, reason="not-allowed")
        with pytest.raises(ValueError):
            PrimaryStatus(CapabilityState.UNKNOWN)

    @pytest.mark.parametrize("value", sorted(CAPABILITY_STATE_VALUES))
    def test_state_not_a_job_lifecycle_token(self, value):
        assert value not in JOB_LIFECYCLE_TOKENS

    @pytest.mark.parametrize("value", sorted(CAPABILITY_STATE_VALUES))
    def test_state_not_an_action_state_token(self, value):
        assert value not in ACTION_STATE_TOKENS

    @pytest.mark.parametrize("value", sorted(CAPABILITY_STATE_VALUES))
    def test_state_not_an_action_taxonomy_token(self, value):
        assert value not in ACTION_TAXONOMY_TOKENS

    def test_collection_failed_is_not_named_failed(self):
        assert "FAILED" not in CAPABILITY_STATE_VALUES
        assert "BLOCKED" not in CAPABILITY_STATE_VALUES


class TestCapabilityQualifierVocabulary:
    def test_exactly_seven_values(self):
        assert CAPABILITY_QUALIFIER_KINDS == {
            "STALE", "PARTIAL", "SOURCE_TRUST_LIMITED",
            "IDENTITY_TRANSLATION_REQUIRED", "NOT_SCHEDULED",
            "SCHEDULE_UNKNOWN", "MEMBER_SPECIFIC",
        }

    @pytest.mark.parametrize("value", sorted(CAPABILITY_QUALIFIER_KINDS))
    def test_qualifier_not_a_job_lifecycle_token(self, value):
        assert value not in JOB_LIFECYCLE_TOKENS

    @pytest.mark.parametrize("value", sorted(CAPABILITY_QUALIFIER_KINDS))
    def test_qualifier_not_an_action_state_token(self, value):
        assert value not in ACTION_STATE_TOKENS

    @pytest.mark.parametrize("value", sorted(CAPABILITY_QUALIFIER_KINDS))
    def test_qualifier_not_an_action_taxonomy_token(self, value):
        assert value not in ACTION_TAXONOMY_TOKENS

    def test_stale_requires_a_real_anchor(self):
        with pytest.raises(ValueError):
            CapabilityQualifier(kind=QUALIFIER_STALE)

    def test_source_trust_limited_requires_a_reason_code(self):
        with pytest.raises(ValueError):
            CapabilityQualifier(kind=QUALIFIER_SOURCE_TRUST_LIMITED)

    def test_identity_translation_required_requires_a_reason_code(self):
        with pytest.raises(ValueError):
            CapabilityQualifier(kind=QUALIFIER_IDENTITY_TRANSLATION_REQUIRED)

    def test_partial_carries_no_extra_fields(self):
        with pytest.raises(ValueError):
            CapabilityQualifier(kind=QUALIFIER_PARTIAL, reason_code="x")

    def test_rejects_unrecognized_kind(self):
        with pytest.raises(ValueError):
            CapabilityQualifier(kind="NOT_A_QUALIFIER")


class TestSerializer:
    def test_emits_only_the_two_namespaced_keys(self):
        payload = to_wire(PrimaryStatus(CapabilityState.AVAILABLE))
        assert set(payload) <= {"capability_state", "capability_qualifiers", "reason"}
        assert "capability_state" in payload
        assert "capability_qualifiers" in payload

    def test_bare_state_string_never_returned_alone(self):
        payload = to_wire(PrimaryStatus(CapabilityState.AVAILABLE))
        assert isinstance(payload, dict)
        assert payload["capability_state"] == "AVAILABLE"

    def test_unknown_reason_carried_under_its_own_key(self):
        payload = to_wire(PrimaryStatus(CapabilityState.UNKNOWN, "no_data"))
        assert payload["reason"] == "no_data"

    def test_qualifiers_serialize_under_capability_qualifiers(self):
        qualifiers = frozenset({CapabilityQualifier(kind=QUALIFIER_PARTIAL)})
        payload = to_wire(PrimaryStatus(CapabilityState.AVAILABLE), qualifiers)
        assert payload["capability_qualifiers"] == [{"kind": "PARTIAL"}]


# --------------------------------------------------------------------------
# AC-3 -- stage 1: contradictions and bounded inconsistencies
# --------------------------------------------------------------------------

def _comparability(**overrides) -> ComparabilityFacts:
    base = dict(
        k1_same_subject=Comparability.TRUE,
        k2_same_capability=Comparability.TRUE,
        k3_same_platform=Comparability.TRUE,
        k4_generation_not_superseded=Comparability.TRUE,
        k5_same_support_rule_version=Comparability.TRUE,
        k6_producer_version_comparable=Comparability.TRUE,
    )
    base.update(overrides)
    return ComparabilityFacts(**base)


class TestCx1:
    def test_identity_conflict_produces_cx1(self):
        conflict = IdentityConflict(canonical_id="cid-1", resolution_a="checkpoint", resolution_b="panorama")
        outcome = evaluate_stage1(Stage1Inputs(identity_conflict=conflict))
        assert outcome.cx1_holds is True
        assert outcome.cx1_disputed_subject == "cid-1"

    def test_no_conflict_is_no_cx1(self):
        outcome = evaluate_stage1(Stage1Inputs())
        assert outcome.cx1_holds is False
        assert outcome.cx1_disputed_subject is None


class TestRi1ComparabilityTable:
    """AC-3: one case per K1-K6 precedence row, evaluated in order, plus
    the mixed-case ordering guarantees."""

    def test_row1_k1_false_is_irrelevant_evidence(self):
        assert evaluate_ri1_comparability(_comparability(k1_same_subject=Comparability.FALSE)) is (
            Ri1Comparability.IRRELEVANT_EVIDENCE
        )

    def test_row1_k2_false_is_irrelevant_evidence(self):
        assert evaluate_ri1_comparability(_comparability(k2_same_capability=Comparability.FALSE)) is (
            Ri1Comparability.IRRELEVANT_EVIDENCE
        )

    def test_row2_k1_unestablished_is_identity_unknown(self):
        assert evaluate_ri1_comparability(_comparability(k1_same_subject=Comparability.UNESTABLISHED)) is (
            Ri1Comparability.IDENTITY_UNKNOWN
        )

    def test_row3_k6_false_is_known_incompatible_producer(self):
        assert evaluate_ri1_comparability(_comparability(k6_producer_version_comparable=Comparability.FALSE)) is (
            Ri1Comparability.KNOWN_INCOMPATIBLE_PRODUCER
        )

    def test_row4_k3_false_is_historical_mismatch(self):
        assert evaluate_ri1_comparability(_comparability(k3_same_platform=Comparability.FALSE)) is (
            Ri1Comparability.HISTORICAL_MISMATCH
        )

    def test_row4_k4_false_is_historical_mismatch(self):
        assert evaluate_ri1_comparability(
            _comparability(k4_generation_not_superseded=Comparability.FALSE)
        ) is Ri1Comparability.HISTORICAL_MISMATCH

    def test_row4_k5_false_is_historical_mismatch(self):
        assert evaluate_ri1_comparability(
            _comparability(k5_same_support_rule_version=Comparability.FALSE)
        ) is Ri1Comparability.HISTORICAL_MISMATCH

    def test_row5_k3_unestablished_is_comparability_unknown(self):
        assert evaluate_ri1_comparability(
            _comparability(k3_same_platform=Comparability.UNESTABLISHED)
        ) is Ri1Comparability.COMPARABILITY_UNKNOWN

    def test_row6_all_true_holds(self):
        assert evaluate_ri1_comparability(_comparability()) is Ri1Comparability.HOLDS

    def test_mixed_false_on_k1_beats_unestablished_elsewhere(self):
        facts = _comparability(k1_same_subject=Comparability.FALSE, k3_same_platform=Comparability.UNESTABLISHED)
        assert evaluate_ri1_comparability(facts) is Ri1Comparability.IRRELEVANT_EVIDENCE

    def test_mixed_known_mismatch_beats_unknown_one(self):
        facts = _comparability(
            k3_same_platform=Comparability.FALSE,
            k4_generation_not_superseded=Comparability.UNESTABLISHED,
        )
        assert evaluate_ri1_comparability(facts) is Ri1Comparability.HISTORICAL_MISMATCH


class TestRi1StageIntegration:
    def test_unsupported_with_full_comparability_is_ri1(self):
        outcome = evaluate_stage1(Stage1Inputs(
            ri1_support_status=VendorSupport.UNSUPPORTED,
            ri1_comparability=_comparability(),
        ))
        assert outcome.ri1_holds is True
        assert outcome.ri1_comparability_unestablished is False

    def test_unsupported_with_unestablished_comparability_is_flagged_not_ri1(self):
        outcome = evaluate_stage1(Stage1Inputs(
            ri1_support_status=VendorSupport.UNSUPPORTED,
            ri1_comparability=_comparability(k1_same_subject=Comparability.UNESTABLISHED),
        ))
        assert outcome.ri1_holds is False
        assert outcome.ri1_comparability_unestablished is True
        assert outcome.ri1_comparability_reason == "support_comparability_unestablished"

    def test_unsupported_with_irrelevant_evidence_is_neither_ri1_nor_unestablished(self):
        outcome = evaluate_stage1(Stage1Inputs(
            ri1_support_status=VendorSupport.UNSUPPORTED,
            ri1_comparability=_comparability(k1_same_subject=Comparability.FALSE),
        ))
        assert outcome.ri1_holds is False
        assert outcome.ri1_comparability_unestablished is False

    def test_supported_status_never_triggers_ri1(self):
        outcome = evaluate_stage1(Stage1Inputs(
            ri1_support_status=VendorSupport.SUPPORTED,
            ri1_comparability=_comparability(),
        ))
        assert outcome.ri1_holds is False


class TestRi2ReusesM10_1:
    def test_ri2_true_reuses_detect_ri2(self):
        outcome = evaluate_stage1(Stage1Inputs(
            ri2_d4_value=EVIDENCE_ONLY,
            ri2_cross_check_registry_side=RegistrySide.ENROLLED,
        ))
        assert outcome.ri2_holds is True

    def test_ri2_false_when_registry_disabled(self):
        outcome = evaluate_stage1(Stage1Inputs(
            ri2_d4_value=EVIDENCE_ONLY,
            ri2_cross_check_registry_side=RegistrySide.DISABLED,
        ))
        assert outcome.ri2_holds is False

    def test_no_ri2_inputs_means_no_ri2(self):
        assert evaluate_stage1(Stage1Inputs()).ri2_holds is False


# --------------------------------------------------------------------------
# AC-4 -- stage 2: the primary ladder
# --------------------------------------------------------------------------

def _ladder(**overrides) -> LadderInputs:
    base = dict(
        stage1=Stage1Outcome(),
        d2_entity_applicability=EntityApplicability.APPLICABLE,
        d3_vendor_support=VendorSupport.SUPPORTED,
        d4_reconciliation=RECONCILED,
        d5_capability_policy=CapabilityPolicy.POLICY_ACTIVE,
        d6b_data_state="live",
        d6c_collection_outcome="success",
        configuration_evidence=ConfigurationEvidence.NOT_ESTABLISHED,
    )
    base.update(overrides)
    return LadderInputs(**base)


class TestPrimaryLadder:
    def test_rank0_cx1_wins_over_everything(self):
        stage1 = Stage1Outcome(cx1_holds=True, cx1_disputed_subject="cid")
        result = resolve_primary_status(_ladder(stage1=stage1))
        assert result == PrimaryStatus(CapabilityState.UNKNOWN, "identity_contradiction")

    def test_rank0_ri1_holds(self):
        stage1 = Stage1Outcome(ri1_holds=True)
        result = resolve_primary_status(_ladder(stage1=stage1, d3_vendor_support=VendorSupport.UNSUPPORTED))
        assert result == PrimaryStatus(CapabilityState.UNKNOWN, "support_inconsistency")

    def test_rank0_ri2_holds(self):
        stage1 = Stage1Outcome(ri2_holds=True)
        result = resolve_primary_status(_ladder(stage1=stage1, d4_reconciliation=EVIDENCE_ONLY))
        assert result == PrimaryStatus(CapabilityState.UNKNOWN, "reconciliation_inconsistency")

    def test_rank1_ri1_comparability_unestablished(self):
        stage1 = Stage1Outcome(ri1_comparability_unestablished=True)
        result = resolve_primary_status(_ladder(stage1=stage1, d3_vendor_support=VendorSupport.UNSUPPORTED))
        assert result == PrimaryStatus(CapabilityState.UNKNOWN, "support_comparability_unestablished")

    def test_rank2_not_applicable(self):
        result = resolve_primary_status(_ladder(d2_entity_applicability=EntityApplicability.NOT_APPLICABLE))
        assert result == PrimaryStatus(CapabilityState.NOT_APPLICABLE)

    def test_rank3_registry_disabled(self):
        result = resolve_primary_status(_ladder(d4_reconciliation=REGISTRY_DISABLED))
        assert result == PrimaryStatus(CapabilityState.DEVICE_DISABLED)

    def test_rank4_unsupported(self):
        result = resolve_primary_status(_ladder(d3_vendor_support=VendorSupport.UNSUPPORTED))
        assert result == PrimaryStatus(CapabilityState.UNSUPPORTED)

    def test_rank5_evidence_only(self):
        result = resolve_primary_status(_ladder(d4_reconciliation=EVIDENCE_ONLY))
        assert result == PrimaryStatus(CapabilityState.NOT_ENROLLED)

    def test_rank6_policy_disabled(self):
        result = resolve_primary_status(_ladder(d5_capability_policy=CapabilityPolicy.POLICY_DISABLED))
        assert result == PrimaryStatus(CapabilityState.POLICY_DISABLED)

    def test_rank7_collection_failed(self):
        result = resolve_primary_status(_ladder(d6c_collection_outcome="failed"))
        assert result == PrimaryStatus(CapabilityState.COLLECTION_FAILED)

    def test_rank8_support_unknown(self):
        result = resolve_primary_status(_ladder(d3_vendor_support=VendorSupport.SUPPORT_UNKNOWN))
        assert result == PrimaryStatus(CapabilityState.UNKNOWN, "support_unknown")

    def test_rank8_applicability_unknown(self):
        result = resolve_primary_status(_ladder(d2_entity_applicability=EntityApplicability.APPLICABILITY_UNKNOWN))
        assert result == PrimaryStatus(CapabilityState.UNKNOWN, "applicability_unknown")

    def test_rank8_registry_only(self):
        result = resolve_primary_status(_ladder(d4_reconciliation=REGISTRY_ONLY))
        assert result == PrimaryStatus(CapabilityState.UNKNOWN, "registry_only")

    def test_rank8_reconciliation_unknown(self):
        result = resolve_primary_status(_ladder(d4_reconciliation=RECONCILIATION_UNKNOWN))
        assert result == PrimaryStatus(CapabilityState.UNKNOWN, "reconciliation_unknown")

    def test_rank8_no_data(self):
        result = resolve_primary_status(_ladder(d6b_data_state="no_data"))
        assert result == PrimaryStatus(CapabilityState.UNKNOWN, "no_data")

    def test_rank8_insufficient_evidence(self):
        result = resolve_primary_status(_ladder(insufficient_evidence=True))
        assert result == PrimaryStatus(CapabilityState.UNKNOWN, "insufficient_evidence")

    def test_rank9_not_configured(self):
        result = resolve_primary_status(_ladder(configuration_evidence=ConfigurationEvidence.POSITIVELY_ABSENT))
        assert result == PrimaryStatus(CapabilityState.NOT_CONFIGURED)

    def test_rank8_beats_rank9_fail_closed_ordering(self):
        """AC-4: rank-8-vs-9 fail-closed ordering -- an UNKNOWN-worthy gap
        outranks a positive NOT_CONFIGURED claim even when both conditions
        are simultaneously true."""
        result = resolve_primary_status(_ladder(
            d3_vendor_support=VendorSupport.SUPPORT_UNKNOWN,
            configuration_evidence=ConfigurationEvidence.POSITIVELY_ABSENT,
        ))
        assert result == PrimaryStatus(CapabilityState.UNKNOWN, "support_unknown")

    @pytest.mark.parametrize("data_state", ["live", "last_known_good", "partial"])
    def test_rank10_available(self, data_state):
        result = resolve_primary_status(_ladder(d6b_data_state=data_state))
        assert result == PrimaryStatus(CapabilityState.AVAILABLE)

    def test_rank11_missing_required_input_is_unclassified(self):
        result = resolve_primary_status(_ladder(d3_vendor_support=None))
        assert result == PrimaryStatus(CapabilityState.UNKNOWN, "unclassified_input")

    def test_rank11_malformed_input_is_unclassified_never_coerced(self):
        result = resolve_primary_status(_ladder(d6b_data_state="bogus-state"))
        assert result == PrimaryStatus(CapabilityState.UNKNOWN, "unclassified_input")

    def test_policy_unknown_never_reaches_rank11_and_is_available(self):
        """5.2: D5 = POLICY_UNKNOWN is absent from the ladder; it must not
        erase otherwise-usable evidence. SCHEDULE_UNKNOWN is a stage-3
        qualifier concern, not a stage-2 ladder concern."""
        result = resolve_primary_status(_ladder(d5_capability_policy=CapabilityPolicy.POLICY_UNKNOWN))
        assert result == PrimaryStatus(CapabilityState.AVAILABLE)

    def test_available_requires_no_open_inconsistency(self):
        stage1 = Stage1Outcome(ri2_holds=True)
        result = resolve_primary_status(_ladder(stage1=stage1, d4_reconciliation=EVIDENCE_ONLY))
        assert result.state is not CapabilityState.AVAILABLE


class TestInferenceBanSupportNeverFromFailedCollection:
    """4.1.5 ban: capability support is never inferred from a failed
    collection or missing evidence."""

    def test_failed_collection_with_unknown_support_is_collection_failed_not_unsupported(self):
        result = resolve_primary_status(_ladder(
            d6c_collection_outcome="failed",
            d3_vendor_support=VendorSupport.SUPPORT_UNKNOWN,
        ))
        assert result.state is CapabilityState.COLLECTION_FAILED

    def test_no_data_never_becomes_unsupported(self):
        result = resolve_primary_status(_ladder(d6b_data_state="no_data"))
        assert result.state is not CapabilityState.UNSUPPORTED


# --------------------------------------------------------------------------
# AC-5 -- stage 3: qualifiers, and the four inference bans
# --------------------------------------------------------------------------

class TestQualifiers:
    def test_stale_from_fresh_false_with_anchor(self):
        qualifiers = resolve_qualifiers(QualifierInputs(freshness=FreshnessFacts(fresh=False, as_of="2026-09-01")))
        assert CapabilityQualifier(kind=QUALIFIER_STALE, as_of="2026-09-01") in qualifiers

    def test_no_stale_without_anchor(self):
        qualifiers = resolve_qualifiers(QualifierInputs(freshness=FreshnessFacts(fresh=False, as_of=None)))
        assert all(q.kind != QUALIFIER_STALE for q in qualifiers)

    def test_no_stale_when_fresh_true(self):
        qualifiers = resolve_qualifiers(QualifierInputs(freshness=FreshnessFacts(fresh=True, as_of="2026-09-01")))
        assert all(q.kind != QUALIFIER_STALE for q in qualifiers)

    def test_partial_from_d6b_partial(self):
        qualifiers = resolve_qualifiers(QualifierInputs(data_state="partial"))
        assert CapabilityQualifier(kind=QUALIFIER_PARTIAL) in qualifiers

    def test_source_trust_limited_from_d6d(self):
        qualifiers = resolve_qualifiers(QualifierInputs(source_trust_limited_reason="untrusted_expected_source"))
        assert CapabilityQualifier(
            kind=QUALIFIER_SOURCE_TRUST_LIMITED, reason_code="untrusted_expected_source"
        ) in qualifiers

    def test_identity_translation_required_from_d6e(self):
        qualifiers = resolve_qualifiers(
            QualifierInputs(identity_translation_required_reason="representation_mismatch")
        )
        assert CapabilityQualifier(
            kind=QUALIFIER_IDENTITY_TRANSLATION_REQUIRED, reason_code="representation_mismatch"
        ) in qualifiers

    def test_not_scheduled_from_no_applicable_schedule(self):
        qualifiers = resolve_qualifiers(QualifierInputs(capability_policy=CapabilityPolicy.NO_APPLICABLE_SCHEDULE))
        assert CapabilityQualifier(kind=QUALIFIER_NOT_SCHEDULED) in qualifiers

    def test_schedule_unknown_from_policy_unknown(self):
        qualifiers = resolve_qualifiers(QualifierInputs(capability_policy=CapabilityPolicy.POLICY_UNKNOWN))
        assert CapabilityQualifier(kind=QUALIFIER_SCHEDULE_UNKNOWN) in qualifiers

    def test_member_specific_from_i15(self):
        qualifiers = resolve_qualifiers(QualifierInputs(member_specific=True))
        assert CapabilityQualifier(kind=QUALIFIER_MEMBER_SPECIFIC) in qualifiers

    def test_no_qualifiers_from_empty_input(self):
        assert resolve_qualifiers(QualifierInputs()) == frozenset()


class TestFourInferenceBans:
    """4.1.5: the four inference bans, each with a negative test."""

    def test_ban1_stale_never_derived_from_source_trust_or_identity_translation(self):
        qualifiers = resolve_qualifiers(QualifierInputs(
            source_trust_limited_reason="untrusted_source",
            identity_translation_required_reason="representation_mismatch",
        ))
        assert all(q.kind != QUALIFIER_STALE for q in qualifiers)

    def test_ban2_support_never_inferred_from_failed_collection_or_missing_evidence(self):
        result = resolve_primary_status(_ladder(
            d6c_collection_outcome="failed",
            d3_vendor_support=VendorSupport.SUPPORT_UNKNOWN,
        ))
        assert result.state is CapabilityState.COLLECTION_FAILED

    def test_ban3_identity_translation_never_promoted_to_cx1_on_its_own(self):
        """D6e alone -- with no I10 identity_conflict supplied -- never
        produces CX1."""
        outcome = evaluate_stage1(Stage1Inputs())
        qualifiers = resolve_qualifiers(
            QualifierInputs(identity_translation_required_reason="representation_mismatch")
        )
        assert outcome.cx1_holds is False
        assert any(q.kind == QUALIFIER_IDENTITY_TRANSLATION_REQUIRED for q in qualifiers)

    def test_ban4_source_trust_and_identity_translation_are_mutually_independent(self):
        only_trust = resolve_qualifiers(QualifierInputs(source_trust_limited_reason="untrusted_source"))
        assert {q.kind for q in only_trust} == {QUALIFIER_SOURCE_TRUST_LIMITED}

        only_translation = resolve_qualifiers(
            QualifierInputs(identity_translation_required_reason="representation_mismatch")
        )
        assert {q.kind for q in only_translation} == {QUALIFIER_IDENTITY_TRANSLATION_REQUIRED}


# --------------------------------------------------------------------------
# AC-6 -- boundaries: purity (no I/O module attributes exist to call)
# --------------------------------------------------------------------------

class TestPurity:
    def test_module_imports_no_io_or_device_modules(self):
        import ast

        import utils.capability_state_resolver as module

        with open(module.__file__, encoding="utf-8") as handle:
            tree = ast.parse(handle.read())
        imported_modules = {
            alias.name
            for node in ast.walk(tree)
            if isinstance(node, ast.Import)
            for alias in node.names
        } | {
            node.module
            for node in ast.walk(tree)
            if isinstance(node, ast.ImportFrom) and node.module
        }
        assert imported_modules.isdisjoint({"requests", "paramiko", "socket", "subprocess"})

    def test_never_imports_capability_registry(self):
        import ast

        import utils.capability_state_resolver as module

        with open(module.__file__, encoding="utf-8") as handle:
            tree = ast.parse(handle.read())
        imported_modules = {
            alias.name
            for node in ast.walk(tree)
            if isinstance(node, ast.Import)
            for alias in node.names
        } | {
            node.module
            for node in ast.walk(tree)
            if isinstance(node, ast.ImportFrom) and node.module
        }
        assert not any("capability_registry" in name for name in imported_modules)
