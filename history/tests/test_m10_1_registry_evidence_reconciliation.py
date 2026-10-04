"""M10.1 -- D4 registry <-> evidence reconciliation projection.

Proves the frozen contract:
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` section 3.3 D4
(`AC-1`..`AC-6`), section 5.1.1 `RI-2`/`S-RI-2` (`AC-7`), and vocabulary
separation from the job lifecycle, `OP.2` action-state and action-taxonomy
vocabularies (`AC-8`). `RELAY_DECISION` `ozandurmus/nexus-agent-relay#11`:
this module never reads `utils/device_identity_relationships.py`; every
production call today classifies the evidence side as
`EvidenceSide.UNRESOLVABLE`, proven by its own dedicated test below rather
than assumed.
"""
from __future__ import annotations

import pytest

from console.jobs import TERMINAL_STATES
from utils.action_taxonomy import ACTION_CLASSES
from utils.operate.states import ActionState
from utils.registry_evidence_reconciliation import (
    D4_VALUES,
    EVIDENCE_ONLY,
    RECONCILED,
    RECONCILIATION_UNKNOWN,
    REGISTRY_DISABLED,
    REGISTRY_ONLY,
    RI_2,
    EvidenceSide,
    ReconciliationResult,
    RegistrySide,
    detect_ri2,
    resolve_d4,
)

JOB_LIFECYCLE_TOKENS = frozenset({"queued", "running", "console_restarted"}) | TERMINAL_STATES
ACTION_STATE_TOKENS = frozenset(state.value for state in ActionState)
ACTION_TAXONOMY_TOKENS = frozenset(
    token
    for action_class in ACTION_CLASSES
    for token in (action_class.id, action_class.label, action_class.refusal_code)
    if token is not None
)


class TestD4Values:
    def test_exactly_five_frozen_values(self):
        assert D4_VALUES == {
            RECONCILED, EVIDENCE_ONLY, REGISTRY_ONLY, REGISTRY_DISABLED, RECONCILIATION_UNKNOWN,
        }

    def test_result_rejects_a_value_outside_the_vocabulary(self):
        with pytest.raises(ValueError):
            ReconciliationResult(value="NOT_A_D4_VALUE")

    def test_result_rejects_an_unrecognized_bounded_inconsistency(self):
        with pytest.raises(ValueError):
            ReconciliationResult(value=RECONCILIATION_UNKNOWN, bounded_inconsistency="RI-1")


class TestResolveD4:
    def test_enrolled_and_observed_is_reconciled(self):
        result = resolve_d4(registry_side=RegistrySide.ENROLLED, evidence_side=EvidenceSide.OBSERVED)
        assert result == ReconciliationResult(RECONCILED)

    def test_enrolled_and_not_observed_is_registry_only(self):
        result = resolve_d4(registry_side=RegistrySide.ENROLLED, evidence_side=EvidenceSide.ABSENT)
        assert result == ReconciliationResult(REGISTRY_ONLY)

    def test_absent_and_observed_is_evidence_only(self):
        result = resolve_d4(registry_side=RegistrySide.ABSENT, evidence_side=EvidenceSide.OBSERVED)
        assert result == ReconciliationResult(EVIDENCE_ONLY)

    def test_absent_and_not_observed_is_unknown(self):
        result = resolve_d4(registry_side=RegistrySide.ABSENT, evidence_side=EvidenceSide.ABSENT)
        assert result == ReconciliationResult(RECONCILIATION_UNKNOWN)

    @pytest.mark.parametrize("evidence_side", list(EvidenceSide))
    def test_disabled_registry_row_is_registry_disabled_regardless_of_evidence(self, evidence_side):
        result = resolve_d4(registry_side=RegistrySide.DISABLED, evidence_side=evidence_side)
        assert result == ReconciliationResult(REGISTRY_DISABLED)

    @pytest.mark.parametrize("evidence_side", list(EvidenceSide))
    def test_unreadable_registry_is_unknown_never_registry_only_or_evidence_only(self, evidence_side):
        result = resolve_d4(registry_side=RegistrySide.UNREADABLE, evidence_side=evidence_side)
        assert result.value == RECONCILIATION_UNKNOWN

    @pytest.mark.parametrize("registry_side", [RegistrySide.ABSENT, RegistrySide.ENROLLED])
    def test_unreadable_evidence_is_unknown_never_registry_only_or_evidence_only(self, registry_side):
        result = resolve_d4(registry_side=registry_side, evidence_side=EvidenceSide.UNREADABLE)
        assert result.value == RECONCILIATION_UNKNOWN

    def test_todays_default_join_input_is_unresolvable_for_every_entity(self):
        """RELAY_DECISION #11, option 4: with no canonical id spanning the
        registry and the merged evidence model, an enrolled, non-disabled
        registry row resolves to RECONCILIATION_UNKNOWN today, not
        REGISTRY_ONLY -- an unresolvable join is a failed read (AC-3), never
        a confirmed absence of evidence."""
        result = resolve_d4(registry_side=RegistrySide.ENROLLED, evidence_side=EvidenceSide.UNRESOLVABLE)
        assert result.value == RECONCILIATION_UNKNOWN

    def test_disabled_still_reports_even_with_an_unresolvable_join(self):
        result = resolve_d4(registry_side=RegistrySide.DISABLED, evidence_side=EvidenceSide.UNRESOLVABLE)
        assert result.value == REGISTRY_DISABLED

    def test_result_never_carries_a_bounded_inconsistency_by_itself(self):
        for registry_side in RegistrySide:
            for evidence_side in EvidenceSide:
                assert resolve_d4(registry_side=registry_side, evidence_side=evidence_side).bounded_inconsistency is None


class TestRi2BoundedInconsistency:
    def test_evidence_only_against_a_non_disabled_registry_row_is_ri2(self):
        assert detect_ri2(d4_value=EVIDENCE_ONLY, cross_check_registry_side=RegistrySide.ENROLLED) is True

    def test_evidence_only_against_a_disabled_registry_row_is_not_ri2(self):
        assert detect_ri2(d4_value=EVIDENCE_ONLY, cross_check_registry_side=RegistrySide.DISABLED) is False

    def test_evidence_only_against_an_absent_registry_row_is_not_ri2(self):
        assert detect_ri2(d4_value=EVIDENCE_ONLY, cross_check_registry_side=RegistrySide.ABSENT) is False

    @pytest.mark.parametrize("d4_value", [RECONCILED, REGISTRY_ONLY, REGISTRY_DISABLED, RECONCILIATION_UNKNOWN])
    @pytest.mark.parametrize("registry_side", list(RegistrySide))
    def test_only_evidence_only_can_ever_be_ri2(self, d4_value, registry_side):
        assert detect_ri2(d4_value=d4_value, cross_check_registry_side=registry_side) is False

    def test_rejects_a_value_outside_the_d4_vocabulary(self):
        with pytest.raises(ValueError):
            detect_ri2(d4_value="NOT_A_D4_VALUE", cross_check_registry_side=RegistrySide.ENROLLED)

    def test_ri2_token_is_the_one_named_by_the_contract(self):
        assert RI_2 == "RI-2"


class TestVocabularySeparation:
    """AC-8: none of the five D4 tokens may appear in the job lifecycle,
    the OP.2 action-state set, or the action taxonomy. (No capability-support
    vocabulary exists yet -- D3's producer arrives at M10 per the frozen
    contract's own 'Evidence today: no producer' note -- so there is nothing
    to check it against.)"""

    @pytest.mark.parametrize("value", sorted(D4_VALUES))
    def test_not_a_job_lifecycle_token(self, value):
        assert value not in JOB_LIFECYCLE_TOKENS
        assert value.lower() not in JOB_LIFECYCLE_TOKENS

    @pytest.mark.parametrize("value", sorted(D4_VALUES))
    def test_not_an_action_state_token(self, value):
        assert value not in ACTION_STATE_TOKENS

    @pytest.mark.parametrize("value", sorted(D4_VALUES))
    def test_not_an_action_taxonomy_token(self, value):
        assert value not in ACTION_TAXONOMY_TOKENS
