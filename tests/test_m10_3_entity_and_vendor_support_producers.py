"""M10.3 -- D2 entity-applicability and D3 vendor/platform-support producers.

Proves the frozen contract:
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` section 3.3 D2
and D3 (`AC-1`, `AC-2`), section 7.3 ("what may never prove capability
support", `AC-2`), and vocabulary separation from the job lifecycle, `OP.2`
action-state and action-taxonomy vocabularies (`AC-4`, mirroring `M10.1`'s
own `AC-8` precedent). Both producers are pure and offline (`AC-3`): every
test below calls them with plain typed arguments and asserts on the return
value only -- no I/O, no device, no network.
"""
from __future__ import annotations

import pytest

from console.jobs import TERMINAL_STATES
from utils.action_taxonomy import ACTION_CLASSES
from utils.capability_applicability import (
    APPLICABLE,
    APPLICABILITY_UNKNOWN,
    CAPABILITY_IDS as D2_CAPABILITY_IDS,
    D2_VALUES,
    NOT_APPLICABLE,
    ApplicabilityResult,
    EntityType,
    resolve_d2,
)
from utils.capability_vendor_support import (
    CAPABILITY_IDS as D3_CAPABILITY_IDS,
    D3_VALUES,
    SUPPORT_UNKNOWN,
    SUPPORTED,
    UNSUPPORTED,
    VENDORS,
    VendorSupportResult,
    resolve_d3,
)
from utils.operate.states import ActionState

JOB_LIFECYCLE_TOKENS = frozenset({"queued", "running", "console_restarted"}) | TERMINAL_STATES
ACTION_STATE_TOKENS = frozenset(state.value for state in ActionState)
ACTION_TAXONOMY_TOKENS = frozenset(
    token
    for action_class in ACTION_CLASSES
    for token in (action_class.id, action_class.label, action_class.refusal_code)
    if token is not None
)


# --- D2 vocabulary -----------------------------------------------------

class TestD2Values:
    def test_exactly_three_frozen_values(self):
        assert D2_VALUES == {APPLICABLE, NOT_APPLICABLE, APPLICABILITY_UNKNOWN}

    def test_result_rejects_a_value_outside_the_vocabulary(self):
        with pytest.raises(ValueError):
            ApplicabilityResult(value="NOT_A_D2_VALUE", reason="x")

    def test_seven_frozen_capability_ids(self):
        assert D2_CAPABILITY_IDS == {
            "inventory", "configuration_collection", "backup", "ha_readiness",
            "controlled_operations", "telemetry", "diagnostics",
        }

    def test_eight_entity_types(self):
        assert len(list(EntityType)) == 8


# --- D2 resolution -------------------------------------------------------

class TestResolveD2:
    @pytest.mark.parametrize("entity_type", list(EntityType))
    @pytest.mark.parametrize("capability", ["inventory", "configuration_collection", "backup"])
    def test_applicable_for_every_entity_type(self, entity_type, capability):
        result = resolve_d2(entity_type=entity_type, capability=capability)
        assert result.value == APPLICABLE

    @pytest.mark.parametrize("entity_type", [
        EntityType.CP_CLUSTERXL_CLUSTER,
        EntityType.CP_VSX_HOST_CLUSTER,
        EntityType.CP_VIRTUAL_SYSTEM,
        EntityType.PAN_HA_PAIR,
    ])
    def test_ha_readiness_applicable_for_cluster_like_entity_types(self, entity_type):
        result = resolve_d2(entity_type=entity_type, capability="ha_readiness")
        assert result.value == APPLICABLE

    @pytest.mark.parametrize("entity_type", [
        EntityType.CP_STANDALONE_GATEWAY,
        EntityType.PAN_STANDALONE_FIREWALL,
    ])
    def test_ha_readiness_not_applicable_for_standalone_entity_types(self, entity_type):
        result = resolve_d2(entity_type=entity_type, capability="ha_readiness")
        assert result.value == NOT_APPLICABLE

    @pytest.mark.parametrize("entity_type", [EntityType.CP_CLUSTERXL_MEMBER, EntityType.PAN_HA_MEMBER])
    def test_ha_readiness_unknown_for_member_level_entity_types(self, entity_type):
        """Neither section 6.4 nor section 7.2 addresses member-level HA
        readiness applicability; the gap is named, not guessed."""
        result = resolve_d2(entity_type=entity_type, capability="ha_readiness")
        assert result.value == APPLICABILITY_UNKNOWN

    def test_controlled_operations_not_applicable_for_virtual_system(self):
        result = resolve_d2(entity_type=EntityType.CP_VIRTUAL_SYSTEM, capability="controlled_operations")
        assert result.value == NOT_APPLICABLE

    @pytest.mark.parametrize("entity_type", [
        et for et in EntityType if et is not EntityType.CP_VIRTUAL_SYSTEM
    ])
    def test_controlled_operations_unknown_elsewhere(self, entity_type):
        result = resolve_d2(entity_type=entity_type, capability="controlled_operations")
        assert result.value == APPLICABILITY_UNKNOWN

    @pytest.mark.parametrize("capability", ["telemetry", "diagnostics"])
    @pytest.mark.parametrize("entity_type", list(EntityType))
    def test_telemetry_and_diagnostics_unknown_for_every_entity_type(self, entity_type, capability):
        result = resolve_d2(entity_type=entity_type, capability=capability)
        assert result.value == APPLICABILITY_UNKNOWN

    def test_unestablished_entity_type_is_unknown(self):
        result = resolve_d2(entity_type=None, capability="inventory")
        assert result.value == APPLICABILITY_UNKNOWN
        assert result.reason == "entity_type_not_established"

    def test_unrecognized_capability_is_unknown_never_a_guess(self):
        result = resolve_d2(entity_type=EntityType.PAN_HA_PAIR, capability="not_a_real_capability")
        assert result.value == APPLICABILITY_UNKNOWN

    def test_never_returns_unsupported_style_or_off_vocabulary_value(self):
        for entity_type in list(EntityType) + [None]:
            for capability in list(D2_CAPABILITY_IDS) + ["bogus"]:
                result = resolve_d2(entity_type=entity_type, capability=capability)
                assert result.value in D2_VALUES

    def test_every_applicable_and_not_applicable_entry_cites_a_real_source(self):
        """AC-5: every positive/negative entry names its exact source; only
        the named-gap default may lack a document citation."""
        for entity_type in EntityType:
            for capability in D2_CAPABILITY_IDS:
                result = resolve_d2(entity_type=entity_type, capability=capability)
                if result.value in (APPLICABLE, NOT_APPLICABLE):
                    assert ".md" in result.reason, (entity_type, capability, result)


# --- D3 vocabulary -----------------------------------------------------

class TestD3Values:
    def test_exactly_three_frozen_values(self):
        assert D3_VALUES == {SUPPORTED, UNSUPPORTED, SUPPORT_UNKNOWN}

    def test_result_rejects_a_value_outside_the_vocabulary(self):
        with pytest.raises(ValueError):
            VendorSupportResult(value="NOT_A_D3_VALUE", reason="x")

    def test_seven_frozen_capability_ids(self):
        assert D3_CAPABILITY_IDS == {
            "inventory", "configuration_collection", "backup", "ha_readiness",
            "controlled_operations", "telemetry", "diagnostics",
        }

    def test_two_vendors(self):
        assert VENDORS == {"checkpoint", "panorama"}


# --- D3 resolution -------------------------------------------------------

class TestResolveD3:
    @pytest.mark.parametrize("vendor", ["checkpoint", "panorama"])
    @pytest.mark.parametrize("capability", [
        "inventory", "configuration_collection", "backup", "ha_readiness",
    ])
    def test_supported_for_both_vendors_where_a_real_collector_exists(self, vendor, capability):
        result = resolve_d3(vendor=vendor, capability=capability)
        assert result.value == SUPPORTED

    @pytest.mark.parametrize("vendor", ["checkpoint", "panorama"])
    @pytest.mark.parametrize("capability", ["controlled_operations", "telemetry", "diagnostics"])
    def test_unknown_where_no_producer_exists(self, vendor, capability):
        """utils/operate/adapter.py carries zero concrete vendor
        implementation and no telemetry/diagnostics collector exists
        anywhere -- absence of a producer, never UNSUPPORTED (section 8.2)."""
        result = resolve_d3(vendor=vendor, capability=capability)
        assert result.value == SUPPORT_UNKNOWN

    def test_never_returns_unsupported(self):
        """No citable positive evidence of vendor non-support was found for
        any (vendor, capability) pair reviewed for this movement (AC-5)."""
        for vendor in VENDORS:
            for capability in D3_CAPABILITY_IDS:
                assert resolve_d3(vendor=vendor, capability=capability).value != UNSUPPORTED

    def test_unrecognized_vendor_is_unknown_never_unsupported(self):
        result = resolve_d3(vendor="fortinet", capability="inventory")
        assert result.value == SUPPORT_UNKNOWN

    def test_unestablished_vendor_is_unknown(self):
        result = resolve_d3(vendor=None, capability="inventory")
        assert result.value == SUPPORT_UNKNOWN
        assert result.reason == "vendor_not_established"

    def test_unrecognized_capability_is_unknown_never_a_guess(self):
        result = resolve_d3(vendor="checkpoint", capability="not_a_real_capability")
        assert result.value == SUPPORT_UNKNOWN

    def test_a_failed_collection_is_never_modeled_as_a_support_conclusion(self):
        """Section 7.3: this producer's signature accepts only (vendor,
        capability) -- it has no collection-outcome parameter at all, so a
        caller cannot even construct a call that feeds collection success or
        failure into a support conclusion (AC-3)."""
        import inspect
        params = set(inspect.signature(resolve_d3).parameters)
        assert params == {"vendor", "capability"}

    def test_every_supported_entry_cites_a_real_module_path(self):
        for vendor in VENDORS:
            for capability in D3_CAPABILITY_IDS:
                result = resolve_d3(vendor=vendor, capability=capability)
                if result.value == SUPPORTED:
                    assert ".py" in result.reason, (vendor, capability, result)


# --- Vocabulary separation (AC-4, mirrors M10.1's AC-8) -------------------

class TestVocabularySeparation:
    """AC-4: none of the D2 or D3 tokens may appear in the job lifecycle,
    the OP.2 action-state set, or the action taxonomy."""

    @pytest.mark.parametrize("value", sorted(D2_VALUES | D3_VALUES))
    def test_not_a_job_lifecycle_token(self, value):
        assert value not in JOB_LIFECYCLE_TOKENS
        assert value.lower() not in JOB_LIFECYCLE_TOKENS

    @pytest.mark.parametrize("value", sorted(D2_VALUES | D3_VALUES))
    def test_not_an_action_state_token(self, value):
        assert value not in ACTION_STATE_TOKENS

    @pytest.mark.parametrize("value", sorted(D2_VALUES | D3_VALUES))
    def test_not_an_action_taxonomy_token(self, value):
        assert value not in ACTION_TAXONOMY_TOKENS

    def test_d2_and_d3_vocabularies_are_themselves_disjoint(self):
        assert D2_VALUES.isdisjoint(D3_VALUES)
