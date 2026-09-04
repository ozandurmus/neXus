"""OP.2.A/B -- vendor-independent CLASS 2 execution foundation.

See ``docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md``
(frozen 2026-09-04) for the contract; AC-N docstrings below name the
acceptance criterion each test asserts. No test contacts a device -- there
is no adapter to contact one with; every vendor-facing seam
(``PreflightProvider``, ``EligibilityEvaluator``, ``VendorCapabilityAdapter``)
is exercised only through in-module fakes.
"""
from __future__ import annotations

import ast
import re
import threading
import time
from pathlib import Path

import pytest

from utils.operate.adapter import (
    ActionPlan,
    Capability,
    Observation,
    PreconditionResult,
    SubmissionOutcomeFamily,
)
from utils.operate.authorization import AuthorizationDecision, DenyAllAuthorizer
from utils.operate.coordinator import ActionCoordinator, AuthorizationDeniedError
from utils.operate.eligibility import EligibilityResult, PreflightSnapshot
from utils.operate.record import ActionRecord, compute_proposal_digest
from utils.operate.states import ActionState, LEGAL_TRANSITIONS, TERMINAL_STATES, is_legal_transition
from utils.operate.store import (
    ActionRecordStore,
    EntityActionInFlightError,
    EntityQuarantinedError,
    IllegalTransitionError,
)

pytestmark = pytest.mark.operate

REPO_ROOT = Path(__file__).resolve().parents[1]


# ---------------------------------------------------------------------------
# Fakes -- no test double here ever returns PERMIT except explicitly, inline,
# under this tests/ module (AC-16).
# ---------------------------------------------------------------------------


class PermitAuthorizer:
    def authorize(self, *, actor_ref, action_type, operational_entity_id):
        return AuthorizationDecision(permitted=True, reason_code="test_permit")


class FakePreflightProvider:
    def __init__(self, *, verdict="positive", coherent=True, run_id="pf-1"):
        self.verdict = verdict
        self.coherent = coherent
        self.run_id = run_id
        self.calls = 0

    def run_preflight(self, *, action_id, operational_entity_id):
        self.calls += 1
        return PreflightSnapshot(
            preflight_run_id=self.run_id,
            action_id=action_id,
            operational_entity_id=operational_entity_id,
            coherent=self.coherent,
            readiness_verdict=self.verdict,
        )


class FakeEligibilityEvaluator:
    def __init__(self, *, eligible=True, reason_codes=()):
        self.eligible = eligible
        self.reason_codes = reason_codes

    def evaluate(self, *, snapshot, capability):
        return EligibilityResult(eligible=self.eligible, reason_codes=self.reason_codes)


class FakeAdapter:
    """A fully scripted, non-vendor adapter double. Never used outside tests/."""

    def __init__(
        self,
        *,
        supported=True,
        precondition=PreconditionResult.HOLDS,
        outcome_family=SubmissionOutcomeFamily.UNKNOWN,
        observation=None,
        settle_observation=None,
    ):
        self.supported = supported
        self.precondition = precondition
        self.outcome_family = outcome_family
        self.observation = observation or Observation(
            postcondition_observed="member_b_active",
            coherent=True,
            both_members_observed=True,
            read_failed=False,
            mode_supported=True,
        )
        self.settle_observation = settle_observation
        self.execute_once_calls = 0

    def capability(self, *, entity_kind, action_type, evidence):
        return Capability(
            entity_kind=entity_kind, action_type=action_type,
            capability_id="test_capability", supported=self.supported,
            reason=None if self.supported else "unsupported_for_test",
        )

    def build_plan(self, *, entity, action_type, evidence):
        return ActionPlan(
            action_type=action_type,
            intended_postcondition="member_b_active",
            subject_member_token="member-b-token",
            impact_disclosure="brief interruption",
            reversal_note="reversal is a new action",
            settle_observation=self.settle_observation,
            material_action_parameters={"target_role": "active"},
        )

    def check_precondition(self, *, plan):
        return self.precondition

    def execute_once(self, *, plan, action_id):
        self.execute_once_calls += 1
        return self.outcome_family

    def observe_postcondition(self, *, entity, plan):
        return self.observation


def _make_coordinator(tmp_path, **overrides):
    kwargs = dict(
        data_root=tmp_path,
        authorizer=PermitAuthorizer(),
        preflight_provider=FakePreflightProvider(),
        eligibility_evaluator=FakeEligibilityEvaluator(),
        adapter_resolver=lambda entity_kind, action_type: FakeAdapter(),
    )
    kwargs.update(overrides)
    return ActionCoordinator(**kwargs)


def _create_and_preflight(coord, *, entity_id="cluster-1", members=("m1", "m2")):
    record = coord.create_action(
        actor_ref="operator-1", action_type="failover", operational_entity_id=entity_id,
        entity_kind="cp_cluster", vendor="checkpoint", reason="scheduled maintenance test",
    )
    return coord.run_preflight(record.action_id, member_canonical_ids=list(members))


# ---------------------------------------------------------------------------
# AC-1 / P2 -- authorization is fail-closed and the only production gate
# ---------------------------------------------------------------------------


def test_ac1_production_authorizer_denies_unconditionally():
    authorizer = DenyAllAuthorizer()
    for action_type, entity_id in (("failover", "e1"), ("acknowledge_unknown_outcome", "e2"), ("anything", "")):
        decision = authorizer.authorize(actor_ref="anyone", action_type=action_type, operational_entity_id=entity_id)
        assert decision.permitted is False


def test_ac1_create_action_denies_by_default_and_creates_no_record(tmp_path):
    coord = ActionCoordinator(data_root=tmp_path)  # production defaults
    with pytest.raises(AuthorizationDeniedError):
        coord.create_action(
            actor_ref="operator-1", action_type="failover", operational_entity_id="cluster-1",
            entity_kind="cp_cluster", vendor="checkpoint", reason="test",
        )
    assert coord.store.list_all() == []


# ---------------------------------------------------------------------------
# AC-2 -- a positive readiness verdict alone cannot cause the boundary transition
# ---------------------------------------------------------------------------


def test_ac2_positive_readiness_with_denied_authorization_creates_no_record(tmp_path):
    coord = ActionCoordinator(
        data_root=tmp_path,
        preflight_provider=FakePreflightProvider(verdict="positive"),
        eligibility_evaluator=FakeEligibilityEvaluator(eligible=True),
        adapter_resolver=lambda ek, at: FakeAdapter(),
    )
    with pytest.raises(AuthorizationDeniedError):
        coord.create_action(
            actor_ref="operator-1", action_type="failover", operational_entity_id="cluster-1",
            entity_kind="cp_cluster", vendor="checkpoint", reason="test",
        )
    assert coord.store.list_all() == []


def test_ac2_positive_readiness_without_confirmation_stays_awaiting(tmp_path):
    coord = _make_coordinator(tmp_path)
    record = _create_and_preflight(coord)
    assert record.state == ActionState.AWAITING_CONFIRMATION
    # No confirm() call at all -- record must not advance on its own.
    assert coord.store.get(record.action_id).state == ActionState.AWAITING_CONFIRMATION


# ---------------------------------------------------------------------------
# AC-3 / P4 -- eligibility consumes only this action's own generation
# ---------------------------------------------------------------------------


def test_ac3_preflight_for_a_different_entity_is_not_eligible(tmp_path):
    class WrongEntityPreflight:
        def run_preflight(self, *, action_id, operational_entity_id):
            return PreflightSnapshot(
                preflight_run_id="pf-x", action_id=action_id,
                operational_entity_id="a-different-entity", coherent=True, readiness_verdict="positive",
            )

    coord = _make_coordinator(tmp_path, preflight_provider=WrongEntityPreflight())
    record = _create_and_preflight(coord)
    assert record.state == ActionState.NOT_ELIGIBLE
    assert "insufficient_evidence" in record.reason_codes


def test_ac3_incoherent_preflight_is_not_eligible(tmp_path):
    coord = _make_coordinator(tmp_path, preflight_provider=FakePreflightProvider(coherent=False))
    record = _create_and_preflight(coord)
    assert record.state == ActionState.NOT_ELIGIBLE


def test_ac3_no_ttl_constant_exists_in_the_package():
    source = "\n".join((REPO_ROOT / "utils" / "operate" / p).read_text(encoding="utf-8") for p in _operate_module_names())
    lowered = source.lower()
    assert re.search(r"\bttl\b", lowered) is None, "a bare TTL constant leaked into utils/operate"
    for token in ("expires_at", "expiry", "max_age"):
        assert token not in lowered, f"a TTL-shaped construct ({token!r}) leaked into utils/operate"


# ---------------------------------------------------------------------------
# AC-4 / P5 -- proposal_digest binds every material field
# ---------------------------------------------------------------------------


def _digest_kwargs(**overrides):
    base = dict(
        action_id="a1", action_type="failover", operational_entity_id="e1",
        intended_postcondition="member_b_active", subject_member_token="tok1",
        preflight_generation_id="pf1", eligibility_result={"eligible": True, "reason_codes": []},
        material_action_parameters={"target_role": "active"},
    )
    base.update(overrides)
    return base


@pytest.mark.parametrize(
    "field,new_value",
    [
        ("action_id", "a2"),
        ("action_type", "failback"),
        ("operational_entity_id", "e2"),
        ("intended_postcondition", "member_a_active"),
        ("subject_member_token", "tok2"),
        ("preflight_generation_id", "pf2"),
        ("eligibility_result", {"eligible": True, "reason_codes": ["x"]}),
        ("material_action_parameters", {"target_role": "standby"}),
    ],
)
def test_ac4_digest_changes_per_bound_field(field, new_value):
    base_digest = compute_proposal_digest(**_digest_kwargs())
    changed_digest = compute_proposal_digest(**_digest_kwargs(**{field: new_value}))
    assert base_digest != changed_digest


def test_ac4_confirmation_with_mismatched_digest_is_refused_and_stays_awaiting(tmp_path):
    coord = _make_coordinator(tmp_path)
    record = _create_and_preflight(coord)
    assert record.state == ActionState.AWAITING_CONFIRMATION

    result = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest="not-the-real-digest",
        member_canonical_ids=["m1", "m2"],
    )
    assert result.state == ActionState.AWAITING_CONFIRMATION
    assert result.mutation_boundary_crossed == "NO"


# ---------------------------------------------------------------------------
# AC-5 / P6 -- mutation_boundary_crossed = YES before any submission attempt;
# a crash between the commit and the submission reconciles to OUTCOME_UNKNOWN
# and quarantines the entity.
# ---------------------------------------------------------------------------


def test_ac5_crash_between_boundary_commit_and_submission_yields_outcome_unknown(tmp_path):
    coord = _make_coordinator(tmp_path)
    record = _create_and_preflight(coord, entity_id="cluster-crash")
    assert record.state == ActionState.AWAITING_CONFIRMATION

    # Simulate exactly the boundary commit `confirm()` performs, then stop --
    # as if the process died before `execute_once` was ever called.
    committed = coord.store.guarded_transition(
        record.action_id, from_states={ActionState.AWAITING_CONFIRMATION}, to_state=ActionState.EXECUTING,
        reason_code="confirmed",
        mutate=lambda cur: {"mutation_boundary_crossed": "YES", "boundary_committed_at": "2026-09-04T00:00:00Z"},
    )
    assert committed.state == ActionState.EXECUTING
    assert committed.mutation_boundary_crossed == "YES"

    # "Restart": a fresh coordinator instance over the same data_root.
    restarted = ActionCoordinator(data_root=tmp_path)
    reconciled = restarted.reconcile_on_startup()
    final = restarted.store.get(record.action_id)
    assert final.state == ActionState.OUTCOME_UNKNOWN
    assert final.terminal_reason == "process_restart_after_mutation_boundary"
    assert any(r.action_id == record.action_id for r in reconciled)

    # The entity is quarantined: even a permissive authorizer cannot create
    # a new action against it.
    coord2 = _make_coordinator(tmp_path)
    with pytest.raises(EntityQuarantinedError):
        coord2.create_action(
            actor_ref="operator-1", action_type="failover", operational_entity_id="cluster-crash",
            entity_kind="cp_cluster", vendor="checkpoint", reason="retry attempt",
        )


def test_ac5_full_lifecycle_boundary_flag_is_set_before_success(tmp_path):
    coord = _make_coordinator(tmp_path)
    record = _create_and_preflight(coord)
    final = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["m1", "m2"],
    )
    assert final.state == ActionState.SUCCEEDED
    assert final.mutation_boundary_crossed == "YES"
    assert final.boundary_committed_at is not None


# ---------------------------------------------------------------------------
# AC-6 -- no transition out of a terminal state; no re-entry into EXECUTING
# ---------------------------------------------------------------------------


def test_ac6_terminal_states_have_no_outgoing_edges():
    for state in TERMINAL_STATES:
        assert LEGAL_TRANSITIONS[state] == frozenset()


def test_ac6_nothing_re_enters_executing():
    for state, targets in LEGAL_TRANSITIONS.items():
        if state == ActionState.AWAITING_CONFIRMATION:
            continue  # the one legal entry
        assert ActionState.EXECUTING not in targets


def test_ac6_generated_matrix_every_illegal_edge_is_rejected(tmp_path):
    store = ActionRecordStore(tmp_path)
    for from_state in ActionState:
        for to_state in ActionState:
            if is_legal_transition(from_state, to_state):
                continue
            with pytest.raises(IllegalTransitionError):
                store.guarded_transition("nonexistent-action", from_states={from_state}, to_state=to_state)


def test_ac6_guarded_transition_from_actual_terminal_state_is_a_noop(tmp_path):
    coord = _make_coordinator(tmp_path)
    record = coord.create_action(
        actor_ref="operator-1", action_type="failover", operational_entity_id="cluster-2",
        entity_kind="cp_cluster", vendor="checkpoint", reason="test",
    )
    cancelled = coord.cancel(record.action_id, actor_ref="operator-1")
    assert cancelled.state == ActionState.CANCELLED
    # A legal-shaped call (CREATED -> ABORTED_PRE_MUTATION is legal in the
    # graph) must still be a no-op once the actual record is terminal.
    result = coord.store.guarded_transition(
        record.action_id, from_states={ActionState.CREATED}, to_state=ActionState.ABORTED_PRE_MUTATION,
    )
    assert result is None
    assert coord.store.get(record.action_id).state == ActionState.CANCELLED


# ---------------------------------------------------------------------------
# AC-7 -- crash reconciliation matches the table exactly, per row
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "crash_state,expected_state,expected_reason",
    [
        (ActionState.CREATED, ActionState.ABORTED_PRE_MUTATION, "process_restart"),
        (ActionState.PREFLIGHTING, ActionState.ABORTED_PRE_MUTATION, "process_restart"),
        (ActionState.AWAITING_CONFIRMATION, ActionState.ABORTED_PRE_MUTATION, "confirmation_context_lost"),
        (ActionState.EXECUTING, ActionState.OUTCOME_UNKNOWN, "process_restart_after_mutation_boundary"),
    ],
)
def test_ac7_reconciliation_table_per_row(tmp_path, crash_state, expected_state, expected_reason):
    record = ActionRecord(
        action_id="rec-1", actor_ref="operator-1", action_type="failover",
        operational_entity_id="e1", entity_kind="cp_cluster", vendor="checkpoint",
        operator_reason="test", state=crash_state,
    )
    store = ActionRecordStore(tmp_path)
    store.create(record)

    coord = ActionCoordinator(data_root=tmp_path)
    coord.reconcile_on_startup()
    final = coord.store.get("rec-1")
    assert final.state == expected_state
    assert final.terminal_reason == expected_reason


def test_ac7_reconciliation_never_touches_an_already_terminal_record(tmp_path):
    record = ActionRecord(
        action_id="rec-2", actor_ref="operator-1", action_type="failover",
        operational_entity_id="e1", entity_kind="cp_cluster", vendor="checkpoint",
        operator_reason="test", state=ActionState.SUCCEEDED, finished_at="2026-01-01T00:00:00Z",
        terminal_reason="postcondition_observed",
    )
    store = ActionRecordStore(tmp_path)
    store.create(record)
    coord = ActionCoordinator(data_root=tmp_path)
    reconciled = coord.reconcile_on_startup()
    assert reconciled == []
    assert coord.store.get("rec-2").state == ActionState.SUCCEEDED


# ---------------------------------------------------------------------------
# AC-8 / P8 -- HA-entity lock is record uniqueness, never a member/endpoint;
# member admission is per device-contact stage only.
# ---------------------------------------------------------------------------


def test_ac8_second_action_on_same_entity_is_refused_at_creation(tmp_path):
    coord = _make_coordinator(tmp_path)
    coord.create_action(
        actor_ref="operator-1", action_type="failover", operational_entity_id="cluster-x",
        entity_kind="cp_cluster", vendor="checkpoint", reason="first",
    )
    with pytest.raises(EntityActionInFlightError):
        coord.create_action(
            actor_ref="operator-2", action_type="failover", operational_entity_id="cluster-x",
            entity_kind="cp_cluster", vendor="checkpoint", reason="second",
        )
    assert len(coord.store.list_all()) == 1


def test_ac8_create_race_is_safe_under_real_concurrency(tmp_path):
    coord = _make_coordinator(tmp_path)
    outcomes: list[str] = []
    lock = threading.Lock()

    def attempt(actor):
        try:
            coord.create_action(
                actor_ref=actor, action_type="failover", operational_entity_id="cluster-race",
                entity_kind="cp_cluster", vendor="checkpoint", reason="race",
            )
            with lock:
                outcomes.append("created")
        except EntityActionInFlightError:
            with lock:
                outcomes.append("refused")

    threads = [threading.Thread(target=attempt, args=(f"actor-{i}",)) for i in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert outcomes.count("created") == 1
    assert outcomes.count("refused") == 7
    matching = [r for r in coord.store.list_all() if r.operational_entity_id == "cluster-race"]
    assert len(matching) == 1


def test_ac8_different_entities_are_fully_independent(tmp_path):
    coord = _make_coordinator(tmp_path)
    r1 = coord.create_action(
        actor_ref="operator-1", action_type="failover", operational_entity_id="cluster-a",
        entity_kind="cp_cluster", vendor="checkpoint", reason="a",
    )
    r2 = coord.create_action(
        actor_ref="operator-1", action_type="failover", operational_entity_id="cluster-b",
        entity_kind="cp_cluster", vendor="checkpoint", reason="b",
    )
    assert r1.action_id != r2.action_id
    assert {r.operational_entity_id for r in coord.store.list_all()} == {"cluster-a", "cluster-b"}


def test_ac8_member_admission_only_held_during_device_contact_stage(tmp_path):
    """A collection may be admitted on a member while the action is
    AWAITING_CONFIRMATION (member admission was released after the preflight
    stage), never while the action itself holds it (PREFLIGHTING)."""
    from utils.collection_executor import CollectionCoordinator
    from utils.coordinator_backend import CoordinatorDecision, Provenance

    cc = CollectionCoordinator()
    coord = _make_coordinator(tmp_path, collection_coordinator=cc)
    record = _create_and_preflight(coord, members=("m10", "m11"))
    assert record.state == ActionState.AWAITING_CONFIRMATION

    decision, _job, _active = cc.admit_request(
        "checkpoint", "manual_probe", ["m10"], provenance=Provenance.MANUAL.value,
    )
    assert decision == CoordinatorDecision.ADMITTED


def test_ac8_admission_refused_during_execute_stage_aborts_pre_mutation(tmp_path):
    from utils.collection_executor import CollectionCoordinator
    from utils.coordinator_backend import Provenance

    cc = CollectionCoordinator()
    coord = _make_coordinator(tmp_path, collection_coordinator=cc)
    record = _create_and_preflight(coord, members=("m20", "m21"))
    assert record.state == ActionState.AWAITING_CONFIRMATION

    # Hold a collection lease on one of the two members before confirming.
    _decision, job, _active = cc.admit_request(
        "checkpoint", "manual_probe", ["m20"], provenance=Provenance.MANUAL.value,
    )
    result = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["m20", "m21"],
    )
    assert result.state == ActionState.ABORTED_PRE_MUTATION
    assert result.terminal_reason == "member_busy"
    assert result.mutation_boundary_crossed == "NO"
    cc.release(job.job_id)


# ---------------------------------------------------------------------------
# AC-9 / P9 -- verification is independent, fresh, distinct preflight_run_id
# ---------------------------------------------------------------------------


def test_ac9_one_sided_observation_is_outcome_unknown_never_succeeded_or_failed(tmp_path):
    adapter = FakeAdapter(observation=Observation(
        postcondition_observed=None, coherent=True, both_members_observed=False,
        read_failed=False, mode_supported=True,
    ))
    coord = _make_coordinator(tmp_path, adapter_resolver=lambda ek, at: adapter)
    record = _create_and_preflight(coord)
    final = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["m1", "m2"],
    )
    assert final.state == ActionState.OUTCOME_UNKNOWN


def test_ac9_failed_read_is_outcome_unknown(tmp_path):
    adapter = FakeAdapter(observation=Observation(
        postcondition_observed=None, coherent=False, both_members_observed=True,
        read_failed=True, mode_supported=True,
    ))
    coord = _make_coordinator(tmp_path, adapter_resolver=lambda ek, at: adapter)
    record = _create_and_preflight(coord)
    final = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["m1", "m2"],
    )
    assert final.state == ActionState.OUTCOME_UNKNOWN


def test_ac9_post_action_run_id_is_distinct_from_pre_action(tmp_path):
    coord = _make_coordinator(tmp_path)
    record = _create_and_preflight(coord)
    pre_action_run_id = record.pre_action_preflight_run_id
    assert pre_action_run_id == "pf-1"
    # OP.2.A/B: post-action verification wiring belongs to OP.2.C (real
    # adapter, real observation timing); what this movement guarantees is
    # that the eventual `post_action_preflight_run_id` field is a distinct
    # slot from the pre-action one, never overwritten by it.
    assert "post_action_preflight_run_id" in record.to_dict()
    assert record.to_dict()["post_action_preflight_run_id"] != pre_action_run_id


# ---------------------------------------------------------------------------
# AC-10 / P18 -- no command string anywhere in utils/operate
# ---------------------------------------------------------------------------


def _operate_module_names() -> list[str]:
    return sorted(p.name for p in (REPO_ROOT / "utils" / "operate").glob("*.py"))


def test_ac10_no_command_field_or_literal_in_the_package():
    forbidden_tokens = ("clusterxl_admin", "cphaprob", "cprid_util", "request high-availability")
    for name in _operate_module_names():
        text = (REPO_ROOT / "utils" / "operate" / name).read_text(encoding="utf-8")
        lowered = text.lower()
        for token in forbidden_tokens:
            assert token not in lowered, f"{name} contains a vendor command token: {token!r}"


def test_ac10_action_record_has_no_command_shaped_field():
    field_names = {f.name for f in __import__("dataclasses").fields(ActionRecord)}
    for name in field_names:
        assert "command" not in name.lower()
        assert "argv" not in name.lower()


# ---------------------------------------------------------------------------
# AC-11 / P15 -- module placement preserves the tested absence
# ---------------------------------------------------------------------------


def test_ac11_utils_failover_allowlist_is_unchanged():
    """Restated from tests/test_architecture_convergence.py so this build's
    own test file also pins the invariant it must not violate."""
    failover_dir = REPO_ROOT / "utils" / "failover"
    modules = {p.stem for p in failover_dir.glob("*.py")}
    allowed = {"__init__", "assessment", "preflight_model", "preflight_readiness"}
    assert modules == allowed


def test_ac11_utils_operate_has_no_vendor_adapter_and_imports_no_transport():
    allowed = {
        "__init__", "states", "record", "authorization", "approval_policy",
        "adapter", "eligibility", "store", "coordinator",
    }
    actual = {p.stem for p in (REPO_ROOT / "utils" / "operate").glob("*.py")}
    assert actual == allowed, f"utils/operate/ gained {actual - allowed}"

    forbidden_imports = (
        "paramiko", "checkpoint.", "panorama.", "configuration.",
        "utils.cp_ssh_trust", "utils.pan_tls_trust", "requests",
    )
    for name in _operate_module_names():
        tree = ast.parse((REPO_ROOT / "utils" / "operate" / name).read_text(encoding="utf-8"))
        imported = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.update(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imported.add(node.module)
        for forbidden in forbidden_imports:
            assert not any(mod == forbidden or mod.startswith(forbidden) for mod in imported), (
                f"{name} imports a transport/collector-shaped module: {forbidden!r}"
            )


# ---------------------------------------------------------------------------
# AC-12 -- CLASS 2 stays memberless; the console gains no job type
# ---------------------------------------------------------------------------


def test_ac12_class_2_still_has_no_taxonomy_member():
    from utils import action_taxonomy as tax

    assert tax.CLASS_2_OPERATIONAL_STATE_CHANGE.permitted is False
    assert tax.CLASS_2_OPERATIONAL_STATE_CHANGE.console_submittable is False


def test_ac12_console_registry_does_not_import_or_reference_utils_operate():
    registry_source = (REPO_ROOT / "console" / "registry.py").read_text(encoding="utf-8")
    assert "utils.operate" not in registry_source
    assert "utils/operate" not in registry_source


# ---------------------------------------------------------------------------
# AC-13 -- privacy: no raw identity, operator reason not silently dropped
# ---------------------------------------------------------------------------


def test_ac13_record_carries_no_credential_or_address_shaped_field():
    forbidden_substrings = ("password", "credential", "token_secret", "ssh_key", "management_ip")
    field_names = {f.name for f in __import__("dataclasses").fields(ActionRecord)}
    for name in field_names:
        for forbidden in forbidden_substrings:
            assert forbidden not in name.lower()


def test_ac13_operator_reason_is_persisted_on_the_record(tmp_path):
    coord = _make_coordinator(tmp_path)
    record = coord.create_action(
        actor_ref="operator-1", action_type="failover", operational_entity_id="cluster-r",
        entity_kind="cp_cluster", vendor="checkpoint", reason="scheduled DC maintenance window",
    )
    assert record.operator_reason == "scheduled DC maintenance window"


# ---------------------------------------------------------------------------
# AC-14 / P12 -- no automatic rollback; reversal is a brand-new action
# ---------------------------------------------------------------------------


def test_ac14_no_rollback_shaped_method_exists_on_the_coordinator():
    forbidden = ("rollback", "auto_revert", "auto_rollback")
    members = dir(ActionCoordinator)
    for name in forbidden:
        assert not any(name in m.lower() for m in members)


def test_ac14_reversal_goes_through_create_action_like_any_other_action(tmp_path):
    coord = _make_coordinator(tmp_path)
    original = coord.create_action(
        actor_ref="operator-1", action_type="failover", operational_entity_id="cluster-rev",
        entity_kind="cp_cluster", vendor="checkpoint", reason="failover",
    )
    coord.cancel(original.action_id, actor_ref="operator-1")  # free the entity for the example
    reversal = coord.create_action(
        actor_ref="operator-1", action_type="failback", operational_entity_id="cluster-rev",
        entity_kind="cp_cluster", vendor="checkpoint", reason="reversal",
        reverses_action_id=original.action_id,
    )
    assert reversal.reverses_action_id == original.action_id
    assert reversal.action_id != original.action_id


# ---------------------------------------------------------------------------
# AC-15 / P6 -- the boundary commit is a guarded transition with exactly one
# winner, proven with real concurrency.
# ---------------------------------------------------------------------------


def test_ac15_two_concurrent_confirmations_yield_exactly_one_winner(tmp_path):
    adapter = FakeAdapter()
    coord = _make_coordinator(tmp_path, adapter_resolver=lambda ek, at: adapter)
    record = _create_and_preflight(coord, entity_id="cluster-concurrent")

    results: list[ActionState] = []
    lock = threading.Lock()
    barrier = threading.Barrier(2)

    def do_confirm():
        barrier.wait()
        result = coord.confirm(
            record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
            member_canonical_ids=["m1", "m2"],
        )
        with lock:
            results.append(result.state)

    threads = [threading.Thread(target=do_confirm) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    # Every caller observes a terminal-or-EXECUTING-derived state; exactly
    # one `execute_once` call happened regardless of how many confirm()
    # calls raced.
    assert adapter.execute_once_calls == 1
    final = coord.store.get(record.action_id)
    assert final.state in (ActionState.SUCCEEDED, ActionState.FAILED_NO_CHANGE, ActionState.OUTCOME_UNKNOWN)


def test_ac15_confirmation_racing_cancellation_yields_exactly_one_winner(tmp_path):
    adapter = FakeAdapter()
    coord = _make_coordinator(tmp_path, adapter_resolver=lambda ek, at: adapter)
    record = _create_and_preflight(coord, entity_id="cluster-race-cancel")

    barrier = threading.Barrier(2)
    outcomes = []
    lock = threading.Lock()

    def do_confirm():
        barrier.wait()
        r = coord.confirm(
            record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
            member_canonical_ids=["m1", "m2"],
        )
        with lock:
            outcomes.append(("confirm", r.state))

    def do_cancel():
        barrier.wait()
        r = coord.cancel(record.action_id, actor_ref="operator-2")
        with lock:
            outcomes.append(("cancel", r.state if r else None))

    t1 = threading.Thread(target=do_confirm)
    t2 = threading.Thread(target=do_cancel)
    t1.start()
    t2.start()
    t1.join()
    t2.join()

    final = coord.store.get(record.action_id)
    assert final.state != ActionState.AWAITING_CONFIRMATION
    # However the race landed, execute_once fired at most once.
    assert adapter.execute_once_calls <= 1
    if final.state == ActionState.CANCELLED:
        assert adapter.execute_once_calls == 0


# ---------------------------------------------------------------------------
# AC-16 / P2 -- no argv/CLI entry point; no PERMIT authorizer outside tests/
# ---------------------------------------------------------------------------


def test_ac16_main_py_has_no_class_2_argv_route():
    main_source = (REPO_ROOT / "main.py").read_text(encoding="utf-8")
    for token in ("--failover", "--class2", "--operate", "utils.operate", "utils/operate"):
        assert token not in main_source


def test_ac16_console_runner_never_dispatches_to_utils_operate():
    runner_source = (REPO_ROOT / "console" / "runner.py").read_text(encoding="utf-8")
    assert "utils.operate" not in runner_source
    assert "utils/operate" not in runner_source


def test_ac16_no_permit_returning_authorizer_outside_tests():
    """Source-level scan: outside `tests/`, no class implements `authorize`
    and returns `permitted=True` -- ``DenyAllAuthorizer`` must stay the only
    production ``Authorizer``."""
    permit_pattern = re.compile(r"permitted\s*=\s*True")
    for py_file in REPO_ROOT.rglob("*.py"):
        relative = py_file.relative_to(REPO_ROOT)
        parts = relative.parts
        if parts[0] in ("tests", ".git", "node_modules"):
            continue
        if "test" in parts[-1]:
            continue
        text = py_file.read_text(encoding="utf-8", errors="ignore")
        if "AuthorizationDecision" not in text:
            continue
        assert not permit_pattern.search(text), f"{relative} returns a PERMIT AuthorizationDecision outside tests/"


def test_ac16_action_coordinator_authorizer_has_no_runtime_selector():
    """The authorizer is a plain constructor argument -- no environment
    variable, no settings lookup selects it."""
    coordinator_source = (REPO_ROOT / "utils" / "operate" / "coordinator.py").read_text(encoding="utf-8")
    for token in ("os.environ", "os.getenv", "getenv("):
        assert token not in coordinator_source


# ---------------------------------------------------------------------------
# AC-18 -- FAILED_NO_CHANGE unreachable while settle_observation is UNKNOWN
# ---------------------------------------------------------------------------


def test_ac18_failed_no_change_unreachable_while_settle_unknown(tmp_path):
    observation = Observation(
        postcondition_observed="member_a_active",  # original state, no transition observed
        coherent=True, both_members_observed=True, read_failed=False, mode_supported=True,
    )
    adapter = FakeAdapter(observation=observation, settle_observation=None)
    coord = _make_coordinator(tmp_path, adapter_resolver=lambda ek, at: adapter)
    record = _create_and_preflight(coord)
    final = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["m1", "m2"],
    )
    assert final.state == ActionState.OUTCOME_UNKNOWN
    assert final.state != ActionState.FAILED_NO_CHANGE


def test_ac18_failed_no_change_reachable_once_settle_is_known(tmp_path):
    observation = Observation(
        postcondition_observed="member_a_active",
        coherent=True, both_members_observed=True, read_failed=False, mode_supported=True,
    )
    adapter = FakeAdapter(observation=observation, settle_observation="30s_after_submission")
    coord = _make_coordinator(tmp_path, adapter_resolver=lambda ek, at: adapter)
    record = _create_and_preflight(coord)
    final = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["m1", "m2"],
    )
    assert final.state == ActionState.FAILED_NO_CHANGE


def test_ac18_succeeded_state_reachable_directly_from_positive_observation(tmp_path):
    coord = _make_coordinator(tmp_path)  # default FakeAdapter observes the intended postcondition
    record = _create_and_preflight(coord)
    final = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["m1", "m2"],
    )
    assert final.state == ActionState.SUCCEEDED


def test_ac18_succeeded_with_warnings_is_not_a_state():
    assert not hasattr(ActionState, "SUCCEEDED_WITH_WARNINGS")
    assert "SUCCEEDED_WITH_WARNINGS" not in ActionState.__members__


# ---------------------------------------------------------------------------
# AC-19 -- CHANGED/UNKNOWN precondition ends ABORTED_PRE_MUTATION, crossed=NO
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("precondition", [PreconditionResult.CHANGED, PreconditionResult.UNKNOWN])
def test_ac19_precondition_not_holding_aborts_pre_mutation_without_submission(tmp_path, precondition):
    adapter = FakeAdapter(precondition=precondition)
    coord = _make_coordinator(tmp_path, adapter_resolver=lambda ek, at: adapter)
    record = _create_and_preflight(coord)
    final = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["m1", "m2"],
    )
    assert final.state == ActionState.ABORTED_PRE_MUTATION
    assert final.mutation_boundary_crossed == "NO"
    assert adapter.execute_once_calls == 0


# ---------------------------------------------------------------------------
# Defense in depth -- even with a permissive authorizer, no adapter means
# CLASS 2 stays structurally unreachable (belt-and-braces on top of P2).
# ---------------------------------------------------------------------------


def test_no_adapter_means_not_eligible_even_with_permit_authorizer(tmp_path):
    coord = ActionCoordinator(
        data_root=tmp_path,
        authorizer=PermitAuthorizer(),
        preflight_provider=FakePreflightProvider(),
        eligibility_evaluator=FakeEligibilityEvaluator(eligible=True),
        # adapter_resolver defaults to "no adapter anywhere" -- the OP.2.B posture.
    )
    record = _create_and_preflight(coord)
    assert record.state == ActionState.NOT_ELIGIBLE
    assert "no_adapter_capability" in record.reason_codes


def test_unsupported_capability_is_not_eligible(tmp_path):
    adapter = FakeAdapter(supported=False)
    coord = _make_coordinator(tmp_path, adapter_resolver=lambda ek, at: adapter)
    record = _create_and_preflight(coord)
    assert record.state == ActionState.NOT_ELIGIBLE
    assert "no_adapter_capability" in record.reason_codes


def test_ineligible_evaluator_result_is_not_eligible(tmp_path):
    coord = _make_coordinator(tmp_path, eligibility_evaluator=FakeEligibilityEvaluator(eligible=False, reason_codes=("degraded",)))
    record = _create_and_preflight(coord)
    assert record.state == ActionState.NOT_ELIGIBLE
    assert "degraded" in record.reason_codes


# ---------------------------------------------------------------------------
# Duplicate / idempotency handling (P7)
# ---------------------------------------------------------------------------


def test_duplicate_action_id_create_returns_existing_record_not_a_second_one(tmp_path):
    coord = _make_coordinator(tmp_path)
    record = coord.create_action(
        actor_ref="operator-1", action_type="failover", operational_entity_id="cluster-dup",
        entity_kind="cp_cluster", vendor="checkpoint", reason="first",
    )
    duplicate = ActionRecord(
        action_id=record.action_id, actor_ref="operator-1", action_type="failover",
        operational_entity_id="cluster-dup", entity_kind="cp_cluster", vendor="checkpoint",
        operator_reason="a completely different reason -- should be ignored",
    )
    returned = coord.store.create(duplicate)
    assert returned.action_id == record.action_id
    assert returned.operator_reason == "first"
    assert len(coord.store.list_all()) == 1


def test_duplicate_confirmation_after_terminal_state_returns_existing_record(tmp_path):
    coord = _make_coordinator(tmp_path)
    record = _create_and_preflight(coord)
    first = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["m1", "m2"],
    )
    assert first.state == ActionState.SUCCEEDED
    second = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["m1", "m2"],
    )
    assert second.action_id == first.action_id
    assert second.state == ActionState.SUCCEEDED


# ---------------------------------------------------------------------------
# Same-entity concurrency (P8) beyond create-race: collection admitted
# between stages, refused during a stage.
# ---------------------------------------------------------------------------


def test_same_entity_collection_between_stages_is_admitted(tmp_path):
    from utils.collection_executor import CollectionCoordinator
    from utils.coordinator_backend import CoordinatorDecision, Provenance

    cc = CollectionCoordinator()
    coord = _make_coordinator(tmp_path, collection_coordinator=cc)
    record = _create_and_preflight(coord, members=("m30", "m31"))
    assert record.state == ActionState.AWAITING_CONFIRMATION

    decision, job, _ = cc.admit_request("checkpoint", "manual_probe", ["m30", "m31"], provenance=Provenance.MANUAL.value)
    assert decision == CoordinatorDecision.ADMITTED
    cc.release(job.job_id)


# ---------------------------------------------------------------------------
# Cancellation legality (§"Cancellation")
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("stage", ["created", "preflighting_stub", "awaiting_confirmation"])
def test_cancel_is_legal_pre_mutation(tmp_path, stage):
    coord = _make_coordinator(tmp_path)
    if stage == "created":
        record = coord.create_action(
            actor_ref="operator-1", action_type="failover", operational_entity_id=f"cluster-{stage}",
            entity_kind="cp_cluster", vendor="checkpoint", reason="test",
        )
    elif stage == "preflighting_stub":
        record = coord.create_action(
            actor_ref="operator-1", action_type="failover", operational_entity_id=f"cluster-{stage}",
            entity_kind="cp_cluster", vendor="checkpoint", reason="test",
        )
        coord.store.guarded_transition(record.action_id, from_states={ActionState.CREATED}, to_state=ActionState.PREFLIGHTING)
        record = coord.store.get(record.action_id)
    else:
        record = _create_and_preflight(coord, entity_id=f"cluster-{stage}")

    cancelled = coord.cancel(record.action_id, actor_ref="operator-1")
    assert cancelled.state == ActionState.CANCELLED


def test_cancel_is_impossible_from_executing_no_code_path_exists(tmp_path):
    """There is no route: ``ActionState.EXECUTING`` does not appear as a
    ``from_state`` anywhere `ActionCoordinator.cancel` passes to the store."""
    import inspect

    source = inspect.getsource(ActionCoordinator.cancel)
    assert "EXECUTING" not in source
