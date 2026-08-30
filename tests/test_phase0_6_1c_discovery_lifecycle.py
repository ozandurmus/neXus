"""Tests for discovery lifecycle and capability planner — 0.6.1C."""
import pytest

from utils.discovery_lifecycle import (
    EntityRecord,
    LifecycleState,
    LifecycleStore,
    LifecycleTransitionError,
    TransitionReason,
    make_discovered,
    transition,
    update_observed,
)
from utils.capability_registry import (
    PLATFORM_FAMILY_LABELS,
    CapabilityProfile,
    CapabilityStore,
    CollectionMode,
    CollectionPlan,
    PlanReasonCode,
    ShellType,
    plan_collection,
    platform_fields_from_classification,
)


# ---------------------------------------------------------------------------
# EntityRecord basics
# ---------------------------------------------------------------------------

def test_make_discovered_returns_discovered_state():
    r = make_discovered("checkpoint", "DEVICE-A")
    assert r.state == LifecycleState.DISCOVERED
    assert r.vendor == "checkpoint"
    assert r.canonical_id == "DEVICE-A"
    assert 0 <= r.confidence <= 100


def test_entity_record_rejects_bad_confidence():
    with pytest.raises(ValueError, match="confidence"):
        make_discovered("checkpoint", "X", confidence=101)
    with pytest.raises(ValueError, match="confidence"):
        make_discovered("checkpoint", "X", confidence=-1)


def test_entity_record_rejects_empty_canonical_id():
    with pytest.raises(ValueError, match="canonical_id"):
        make_discovered("checkpoint", "  ")


def test_entity_record_rejects_unknown_evidence_plane():
    with pytest.raises(ValueError):
        EntityRecord(
            vendor="checkpoint",
            canonical_id="X",
            state=LifecycleState.DISCOVERED,
            confidence=50,
            evidence_plane="invalid_plane",
            first_observed="2026-01-01T00:00:00+00:00",
            last_observed="2026-01-01T00:00:00+00:00",
            last_transition="2026-01-01T00:00:00+00:00",
            transition_reason=TransitionReason.MANAGEMENT_DISCOVERY.value,
        )


# ---------------------------------------------------------------------------
# Valid state transitions
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("from_state,to_state,reason", [
    (LifecycleState.DISCOVERED, LifecycleState.VALIDATED,
     TransitionReason.DIRECT_COLLECTION_OK.value),
    (LifecycleState.DISCOVERED, LifecycleState.EXCLUDED,
     TransitionReason.RUNTIME_POLICY_EXCLUDE.value),
    (LifecycleState.DISCOVERED, LifecycleState.REMOVED,
     TransitionReason.NOT_IN_MANAGEMENT_VIEW.value),
    (LifecycleState.VALIDATED,  LifecycleState.STABLE,
     TransitionReason.MULTI_CYCLE_STABLE.value),
    (LifecycleState.VALIDATED,  LifecycleState.EXCLUDED,
     TransitionReason.RUNTIME_POLICY_EXCLUDE.value),
    (LifecycleState.VALIDATED,  LifecycleState.REMOVED,
     TransitionReason.NOT_IN_MANAGEMENT_VIEW.value),
    (LifecycleState.VALIDATED,  LifecycleState.DISCOVERED,
     TransitionReason.MANAGEMENT_DISCOVERY.value),
    (LifecycleState.STABLE,     LifecycleState.EXCLUDED,
     TransitionReason.RUNTIME_POLICY_EXCLUDE.value),
    (LifecycleState.STABLE,     LifecycleState.REMOVED,
     TransitionReason.NOT_IN_MANAGEMENT_VIEW.value),
    (LifecycleState.STABLE,     LifecycleState.VALIDATED,
     TransitionReason.CONFIDENCE_DROP.value),
    (LifecycleState.EXCLUDED,   LifecycleState.DISCOVERED,
     TransitionReason.POLICY_INCLUSION.value),
    (LifecycleState.REMOVED,    LifecycleState.DISCOVERED,
     TransitionReason.RE_APPEARED.value),
])
def test_valid_transitions(from_state, to_state, reason):
    r = make_discovered("checkpoint", "DEVICE-B")
    # Force the starting state via the store so we can test from any state.
    store = LifecycleStore()
    store.put(r)
    if from_state != LifecycleState.DISCOVERED:
        # Walk to the target start state using permitted paths.
        _walk_to(store, "checkpoint", "DEVICE-B", from_state)
    current = store.get("checkpoint", "DEVICE-B")
    result = transition(current, to_state, reason=reason)
    assert result.state == to_state
    assert result.transition_reason == reason


def _walk_to(store: LifecycleStore, vendor: str, cid: str, target: LifecycleState) -> None:
    """Helper: walk an entity to a desired state through a known valid path."""
    r = store.get(vendor, cid)
    paths: dict[LifecycleState, list[tuple[LifecycleState, str]]] = {
        LifecycleState.VALIDATED: [
            (LifecycleState.VALIDATED, TransitionReason.DIRECT_COLLECTION_OK.value),
        ],
        LifecycleState.STABLE: [
            (LifecycleState.VALIDATED, TransitionReason.DIRECT_COLLECTION_OK.value),
            (LifecycleState.STABLE,    TransitionReason.MULTI_CYCLE_STABLE.value),
        ],
        LifecycleState.EXCLUDED: [
            (LifecycleState.EXCLUDED, TransitionReason.RUNTIME_POLICY_EXCLUDE.value),
        ],
        LifecycleState.REMOVED: [
            (LifecycleState.REMOVED, TransitionReason.NOT_IN_MANAGEMENT_VIEW.value),
        ],
    }
    for state, reason in paths.get(target, []):
        r = transition(r, state, reason=reason)
    store.put(r)


# ---------------------------------------------------------------------------
# Invalid transition rejection
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("from_state,to_state", [
    (LifecycleState.DISCOVERED, LifecycleState.STABLE),
    (LifecycleState.VALIDATED,  LifecycleState.REMOVED,   ),  # actually valid; skip
    (LifecycleState.STABLE,     LifecycleState.DISCOVERED),
    (LifecycleState.EXCLUDED,   LifecycleState.VALIDATED),
    (LifecycleState.EXCLUDED,   LifecycleState.STABLE),
    (LifecycleState.EXCLUDED,   LifecycleState.REMOVED),
    (LifecycleState.REMOVED,    LifecycleState.VALIDATED),
    (LifecycleState.REMOVED,    LifecycleState.STABLE),
    (LifecycleState.REMOVED,    LifecycleState.EXCLUDED),
])
def test_invalid_transitions_raise(from_state, to_state):
    # Skip VALIDATED → REMOVED since that IS valid per the state machine.
    if from_state == LifecycleState.VALIDATED and to_state == LifecycleState.REMOVED:
        pytest.skip("VALIDATED → REMOVED is a valid transition")
    store = LifecycleStore()
    r = make_discovered("checkpoint", "DEVICE-C")
    store.put(r)
    _walk_to(store, "checkpoint", "DEVICE-C", from_state)
    current = store.get("checkpoint", "DEVICE-C")
    with pytest.raises(LifecycleTransitionError):
        transition(current, to_state, reason="test")


# ---------------------------------------------------------------------------
# Confidence and evidence plane update
# ---------------------------------------------------------------------------

def test_update_observed_does_not_change_state():
    r = make_discovered("checkpoint", "DEVICE-D", confidence=50)
    updated = update_observed(r, confidence=80, evidence_plane="direct")
    assert updated.state == r.state
    assert updated.confidence == 80
    assert updated.evidence_plane == "direct"


def test_transition_updates_confidence():
    r = make_discovered("checkpoint", "DEVICE-E", confidence=50)
    validated = transition(
        r,
        LifecycleState.VALIDATED,
        reason=TransitionReason.DIRECT_COLLECTION_OK.value,
        confidence=90,
        evidence_plane="direct",
    )
    assert validated.confidence == 90
    assert validated.evidence_plane == "direct"


# ---------------------------------------------------------------------------
# Exclusion preserves reason code
# ---------------------------------------------------------------------------

def test_exclusion_preserves_reason_code():
    r = make_discovered("checkpoint", "DEVICE-F")
    excluded = transition(
        r,
        LifecycleState.EXCLUDED,
        reason=TransitionReason.RUNTIME_POLICY_EXCLUDE.value,
    )
    assert excluded.state == LifecycleState.EXCLUDED
    assert excluded.transition_reason == TransitionReason.RUNTIME_POLICY_EXCLUDE.value
    # Excluded can return to DISCOVERED (re-inclusion).
    rediscovered = transition(
        excluded,
        LifecycleState.DISCOVERED,
        reason=TransitionReason.POLICY_INCLUSION.value,
    )
    assert rediscovered.state == LifecycleState.DISCOVERED


# ---------------------------------------------------------------------------
# Serialisation round-trip (backward compat for pre-0.6.1C snapshots)
# ---------------------------------------------------------------------------

def test_entity_record_roundtrip():
    r = make_discovered("paloalto", "PAN-DEVICE-G", confidence=60)
    d = r.to_dict()
    r2 = EntityRecord.from_dict(d)
    assert r2.vendor == r.vendor
    assert r2.canonical_id == r.canonical_id
    assert r2.state == r.state
    assert r2.confidence == r.confidence


def test_lifecycle_store_roundtrip():
    store = LifecycleStore()
    store.observe("checkpoint", "DEVICE-H", confidence=50)
    store.observe("paloalto", "PAN-DEVICE-I", confidence=70)
    raw = store.to_dict()
    store2 = LifecycleStore.from_dict(raw)
    assert store2.get("checkpoint", "DEVICE-H") is not None
    assert store2.get("paloalto", "PAN-DEVICE-I") is not None


def test_lifecycle_store_from_dict_skips_unknown_states():
    raw = {
        "schema_version": 1,
        "records": [
            {
                "vendor": "checkpoint",
                "canonical_id": "VALID-A",
                "state": "DISCOVERED",
                "confidence": 50,
                "evidence_plane": "management",
                "first_observed": "2026-01-01T00:00:00+00:00",
                "last_observed":  "2026-01-01T00:00:00+00:00",
                "last_transition": "2026-01-01T00:00:00+00:00",
                "transition_reason": "management_discovery",
            },
            {
                "vendor": "checkpoint",
                "canonical_id": "BOGUS-B",
                "state": "FUTURE_STATE_9000",  # unrecognised — must be skipped
                "confidence": 50,
                "evidence_plane": "management",
                "first_observed": "2026-01-01T00:00:00+00:00",
                "last_observed":  "2026-01-01T00:00:00+00:00",
                "last_transition": "2026-01-01T00:00:00+00:00",
                "transition_reason": "unknown",
            },
        ],
    }
    store = LifecycleStore.from_dict(raw)
    assert store.get("checkpoint", "VALID-A") is not None
    assert store.get("checkpoint", "BOGUS-B") is None  # skipped


# ---------------------------------------------------------------------------
# LifecycleStore observe (upsert)
# ---------------------------------------------------------------------------

def test_store_observe_creates_new_record():
    store = LifecycleStore()
    r = store.observe("checkpoint", "DEVICE-J")
    assert r.state == LifecycleState.DISCOVERED


def test_store_observe_refreshes_existing_without_state_change():
    store = LifecycleStore()
    store.observe("checkpoint", "DEVICE-K", confidence=40)
    store.observe("checkpoint", "DEVICE-K", confidence=60)
    r = store.get("checkpoint", "DEVICE-K")
    assert r.confidence == 60
    assert r.state == LifecycleState.DISCOVERED


def test_store_vendor_case_insensitive():
    store = LifecycleStore()
    store.observe("CheckPoint", "DEVICE-L")
    assert store.get("checkpoint", "DEVICE-L") is not None


# ===========================================================================
# Capability registry and planner
# ===========================================================================

def _profile(
    vendor="checkpoint",
    cid="DEV-M",
    shell=ShellType.UNKNOWN,
    direct=None,
    vsx=None,
    pan_vsys=None,
    standby=None,
    id_fail=False,
    confidence=50,
) -> CapabilityProfile:
    return CapabilityProfile(
        vendor=vendor,
        canonical_id=cid,
        shell_type=shell,
        direct_collection_capable=direct,
        vsx_vsenv_capable=vsx,
        pan_vsys_capable=pan_vsys,
        standby_member=standby,
        had_identity_failure=id_fail,
        confidence=confidence,
    )


# --- Lifecycle gate ---------------------------------------------------------

def test_planner_excluded_lifecycle_not_allowed():
    p = _profile()
    plan = plan_collection(p, LifecycleState.EXCLUDED)
    assert not plan.allowed
    assert plan.mode == CollectionMode.DEFERRED_LIFECYCLE
    assert plan.reason_code == PlanReasonCode.LIFECYCLE_EXCLUDED.value


def test_planner_removed_lifecycle_not_allowed():
    p = _profile()
    plan = plan_collection(p, LifecycleState.REMOVED)
    assert not plan.allowed
    assert plan.mode == CollectionMode.DEFERRED_LIFECYCLE
    assert plan.reason_code == PlanReasonCode.LIFECYCLE_REMOVED.value


# --- ClusterXL standby suppression -----------------------------------------

def test_planner_standby_member_deferred():
    p = _profile(shell=ShellType.EXPERT, standby=True)
    plan = plan_collection(p, LifecycleState.VALIDATED)
    assert not plan.allowed
    assert plan.mode == CollectionMode.DEFERRED_STANDBY
    assert plan.reason_code == PlanReasonCode.STANDBY_MEMBER.value


# --- Expert shell ----------------------------------------------------------

def test_planner_expert_shell_returns_explicit_clish():
    p = _profile(shell=ShellType.EXPERT, direct=True, confidence=90)
    plan = plan_collection(p, LifecycleState.VALIDATED)
    assert plan.allowed
    assert plan.mode == CollectionMode.EXPERT_EXPLICIT_CLISH
    assert plan.reason_code == PlanReasonCode.SHELL_EXPERT_CONFIRMED.value


# --- Direct-Clish is capability NOT platform identity ----------------------

def test_planner_direct_clish_not_platform_identity():
    p = _profile(shell=ShellType.DIRECT_CLISH, confidence=70)
    plan = plan_collection(p, LifecycleState.VALIDATED)
    assert plan.allowed
    assert plan.mode == CollectionMode.DIRECT_CLISH_CAPABLE
    assert plan.reason_code == PlanReasonCode.SHELL_DIRECT_CLISH.value
    # The planner must note that direct-Clish is NOT a platform identity claim.
    assert "direct_clish_is_capability_not_platform_identity" in plan.notes


# --- VSX vsenv context -----------------------------------------------------

def test_planner_vsx_vsenv_capable():
    p = _profile(shell=ShellType.EXPERT, vsx=True, confidence=85)
    plan = plan_collection(p, LifecycleState.STABLE)
    assert plan.allowed
    assert plan.mode == CollectionMode.VSX_VSENV
    assert plan.reason_code == PlanReasonCode.VSX_VSENV_CAPABLE.value


# --- PAN API ---------------------------------------------------------------

def test_planner_pan_api_capable():
    p = _profile(vendor="paloalto", cid="PAN-DEV-N", pan_vsys=True, confidence=80)
    plan = plan_collection(p, LifecycleState.STABLE)
    assert plan.allowed
    assert plan.mode == CollectionMode.PAN_API
    assert plan.reason_code == PlanReasonCode.PAN_API_CAPABLE.value


# --- Identity failure on unvalidated entity --------------------------------

def test_planner_identity_failure_on_discovered_deferred():
    p = _profile(id_fail=True, shell=ShellType.EXPERT)
    plan = plan_collection(p, LifecycleState.DISCOVERED)
    assert not plan.allowed
    assert plan.reason_code == PlanReasonCode.IDENTITY_FAILURE_HISTORY.value


def test_planner_identity_failure_on_validated_still_proceeds():
    """Once an entity is VALIDATED, identity failure history doesn't block it."""
    p = _profile(shell=ShellType.EXPERT, id_fail=True, confidence=80)
    plan = plan_collection(p, LifecycleState.VALIDATED)
    assert plan.allowed
    assert plan.mode == CollectionMode.EXPERT_EXPLICIT_CLISH


# --- Unknown capability → UNKNOWN, not guessed ----------------------------

def test_planner_unknown_shell_not_guessed():
    p = _profile(shell=ShellType.UNKNOWN)
    plan = plan_collection(p, LifecycleState.DISCOVERED)
    assert not plan.allowed
    assert plan.mode == CollectionMode.UNKNOWN
    assert plan.reason_code == PlanReasonCode.UNKNOWN_SHELL.value


# --- CapabilityProfile validation ------------------------------------------

def test_capability_profile_rejects_bad_confidence():
    with pytest.raises(ValueError):
        CapabilityProfile(vendor="cp", canonical_id="X", confidence=200)


def test_capability_profile_roundtrip():
    p = CapabilityProfile(
        vendor="checkpoint",
        canonical_id="DEV-O",
        shell_type=ShellType.EXPERT,
        direct_collection_capable=True,
        vsx_vsenv_capable=False,
        confidence=75,
    )
    d = p.to_dict()
    p2 = CapabilityProfile.from_dict(d)
    assert p2.vendor == p.vendor
    assert p2.shell_type == p.shell_type
    assert p2.confidence == p.confidence


def test_capability_store_roundtrip():
    store = CapabilityStore()
    store.put(CapabilityProfile(vendor="checkpoint", canonical_id="DEV-P", confidence=50))
    store.put(CapabilityProfile(vendor="paloalto", canonical_id="PAN-DEV-Q", confidence=60))
    raw = store.to_dict()
    store2 = CapabilityStore.from_dict(raw)
    assert store2.get("checkpoint", "DEV-P") is not None
    assert store2.get("paloalto", "PAN-DEV-Q") is not None
    assert store2.get("checkpoint", "NONEXISTENT") is None


def test_capability_store_from_dict_skips_bad_profiles():
    raw = {
        "schema_version": 1,
        "profiles": [
            {"vendor": "checkpoint", "canonical_id": "DEV-GOOD",
             "shell_type": "expert", "confidence": 50},
            {"vendor": "checkpoint", "canonical_id": "",  # invalid — empty canonical_id
             "shell_type": "expert", "confidence": 50},
        ],
    }
    store = CapabilityStore.from_dict(raw)
    assert store.get("checkpoint", "DEV-GOOD") is not None
    # Empty canonical_id skipped; no crash.
    assert len(store.all_profiles()) == 1


# ---------------------------------------------------------------------------
# cp_unknown_platform (0.6.1C): platform_family/platform_confidence
# ---------------------------------------------------------------------------

def test_platform_fields_from_classification_extracts_family_and_confidence():
    classification = {
        "family": "gaia_embedded", "label": "Quantum Spark / Gaia Embedded",
        "confidence": "HIGH", "evidence": "explicit_product_or_os_marker",
    }
    family, confidence = platform_fields_from_classification(classification)
    assert family == "gaia_embedded"
    assert confidence == "HIGH"


def test_platform_fields_from_classification_handles_unknown_family():
    # _classify_platform()'s own fallback shape when nothing matched.
    classification = {
        "family": "unknown", "label": "Check Point platform",
        "confidence": "LOW", "evidence": "insufficient_platform_evidence",
    }
    family, confidence = platform_fields_from_classification(classification)
    assert family == "unknown"
    assert confidence == "LOW"


def test_platform_fields_from_classification_none_input_yields_none():
    assert platform_fields_from_classification(None) == (None, None)
    assert platform_fields_from_classification({}) == (None, None)


def test_capability_profile_platform_fields_default_to_unclassified():
    p = CapabilityProfile(vendor="checkpoint", canonical_id="DEV-R")
    assert p.platform_family is None
    assert p.platform_confidence is None


def test_capability_profile_platform_fields_roundtrip():
    p = CapabilityProfile(
        vendor="checkpoint", canonical_id="DEV-S",
        platform_family="gaia", platform_confidence="MEDIUM",
    )
    d = p.to_dict()
    p2 = CapabilityProfile.from_dict(d)
    assert p2.platform_family == "gaia"
    assert p2.platform_confidence == "MEDIUM"


@pytest.mark.parametrize("platform_family", [None, "unknown", "gaia", "gaia_embedded"])
def test_platform_family_never_changes_the_collection_plan(platform_family):
    """Correctness contract: platform_family must be fully independent of
    the collection plan. Two profiles identical except for platform_family
    must produce the identical CollectionPlan.
    """
    baseline = _profile(shell=ShellType.EXPERT, confidence=80)
    with_platform = CapabilityProfile(
        vendor=baseline.vendor, canonical_id=baseline.canonical_id,
        shell_type=baseline.shell_type,
        direct_collection_capable=baseline.direct_collection_capable,
        vsx_vsenv_capable=baseline.vsx_vsenv_capable,
        pan_vsys_capable=baseline.pan_vsys_capable,
        standby_member=baseline.standby_member,
        had_identity_failure=baseline.had_identity_failure,
        confidence=baseline.confidence,
        platform_family=platform_family,
    )
    plan_baseline = plan_collection(baseline, LifecycleState.DISCOVERED)
    plan_with_platform = plan_collection(with_platform, LifecycleState.DISCOVERED)
    assert plan_with_platform == plan_baseline


def test_platform_family_labels_cover_every_classify_platform_family():
    # Mirrors configuration.checkpoint_config_collector._classify_platform()'s
    # three possible family values.
    assert set(PLATFORM_FAMILY_LABELS) == {"gaia_embedded", "gaia", "unknown"}


def test_platform_fields_from_classification_accepts_real_classify_platform_output():
    """Integration guard: the real collector's _classify_platform() output
    must remain a valid input to platform_fields_from_classification() --
    catches drift if that function's return shape ever changes.
    """
    from configuration.checkpoint_config_collector import _classify_platform

    unknown = _classify_platform(version_stdout="", asset_stdout="", model=None)
    family, confidence = platform_fields_from_classification(unknown)
    assert family == "unknown"
    assert family in PLATFORM_FAMILY_LABELS

    spark = _classify_platform(
        version_stdout="", asset_stdout="Appliance Name: Quantum Spark 1600", model=None
    )
    family, confidence = platform_fields_from_classification(spark)
    assert family == "gaia_embedded"
    assert family in PLATFORM_FAMILY_LABELS
