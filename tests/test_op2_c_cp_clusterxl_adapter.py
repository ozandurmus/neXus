"""OP.2.C -- the Check Point ClusterXL CLASS 2 capability adapter.

Exercises `checkpoint.clusterxl_capability_adapter.CPClusterXLCapabilityAdapter`
(the `VendorCapabilityAdapter` implementation for CP-M1/CP-M1-R,
`docs/history/phase/OP_2_1_CP_CLUSTERXL_MUTATION_COMMAND_GATE.md`) both in
isolation and wired through the real `utils.operate.coordinator.
ActionCoordinator` (`OP.2.A`/`OP.2.B`). No test contacts a device -- every
`ClusterXLMemberSession` here is an in-module fake, exactly the discipline
`tests/test_op2_a_b_execution_foundation.py` already uses for
`VendorCapabilityAdapter` itself.

Building and testing this adapter does not make CLASS 2 reachable: this
file also re-asserts, the same way `tests/test_op2_1_cp_clusterxl_command_
gate.py` re-asserts over its own build, that `DenyAllAuthorizer` is still
the only production `Authorizer`, `utils/operate/`'s allowlist and
transport-import ban are unaffected, and `CLASS_2_OPERATIONAL_STATE_CHANGE`
still has no member.
"""
from __future__ import annotations

from pathlib import Path

import pytest

from checkpoint.clusterxl_capability_adapter import (
    ACTION_TYPE_HA_GRACEFUL_FAILBACK,
    ACTION_TYPE_HA_GRACEFUL_FAILOVER,
    ENTITY_KIND_CP_CLUSTER,
    CPClusterXLCapabilityAdapter,
    MemberRoleReading,
    SubmissionConfirmation,
)
from utils import action_taxonomy
from utils.operate import authorization as operate_authorization
from utils.operate.adapter import PreconditionResult, SubmissionOutcomeFamily
from utils.operate.authorization import AuthorizationDecision
from utils.operate.coordinator import ActionCoordinator
from utils.operate.eligibility import EligibilityResult, PreflightSnapshot
from utils.operate.states import ActionState

pytestmark = pytest.mark.operate

REPO_ROOT = Path(__file__).resolve().parents[1]


# ---------------------------------------------------------------------------
# Fakes -- never used outside tests/, mirroring test_op2_a_b's own discipline.
# ---------------------------------------------------------------------------


class PermitAuthorizer:
    def authorize(self, *, actor_ref, action_type, operational_entity_id):
        return AuthorizationDecision(permitted=True, reason_code="test_permit")


class FakeClusterXLMemberSession:
    """One physical member's fake session -- tracks call counts so tests can
    prove `execute_once` submits exactly once and `observe_postcondition`
    reads independently of `execute_once`.

    `check_precondition()` and `observe_postcondition()` both read through
    this same session, back-to-back, inside one `coordinator.confirm()`
    call -- there is no point between them for a test to mutate a plain
    `role` attribute and have it be seen by only the second read. `role_
    sequence`, when given, returns one entry per successive `read_role()`
    call (the last entry repeats after exhausted) so a test can state the
    precondition-time role and the post-submission role explicitly and
    unambiguously. `fail_starting_from_call` models a read that starts
    failing only after the mutation has actually been submitted.
    """

    def __init__(self, *, role="ACTIVE", role_sequence=None, admin_down_pnote_present=False,
                 read_failed=False, fail_starting_from_call=None,
                 submission=SubmissionConfirmation.SUBMITTED_OR_AMBIGUOUS):
        self.role = role
        self.role_sequence = list(role_sequence) if role_sequence is not None else None
        self.admin_down_pnote_present = admin_down_pnote_present
        self.read_failed = read_failed
        self.fail_starting_from_call = fail_starting_from_call
        self.submission = submission
        self.read_role_calls = 0
        self.submit_admin_down_calls = 0
        self.submit_admin_up_calls = 0

    def read_role(self) -> MemberRoleReading:
        self.read_role_calls += 1
        failed = self.read_failed or (
            self.fail_starting_from_call is not None and self.read_role_calls >= self.fail_starting_from_call
        )
        if self.role_sequence:
            index = min(self.read_role_calls - 1, len(self.role_sequence) - 1)
            current_role = self.role_sequence[index]
        else:
            current_role = self.role
        return MemberRoleReading(
            role=None if failed else current_role,
            admin_down_pnote_present=None if failed else self.admin_down_pnote_present,
            read_failed=failed,
        )

    def submit_admin_down(self) -> SubmissionConfirmation:
        self.submit_admin_down_calls += 1
        return self.submission

    def submit_admin_up(self) -> SubmissionConfirmation:
        self.submit_admin_up_calls += 1
        return self.submission


class FakePreflightProvider:
    """Unlike test_op2_a_b's fixed fake, this one carries `check_statuses`
    (subject/peer member tokens, cluster mode, recovery mode) -- exactly
    the evidence shape this adapter's `capability()`/`build_plan()` need."""

    def __init__(self, *, check_statuses, coherent=True, verdict="positive", run_id="pf-cp-1"):
        self.check_statuses = check_statuses
        self.coherent = coherent
        self.verdict = verdict
        self.run_id = run_id

    def run_preflight(self, *, action_id, operational_entity_id):
        return PreflightSnapshot(
            preflight_run_id=self.run_id, action_id=action_id, operational_entity_id=operational_entity_id,
            coherent=self.coherent, readiness_verdict=self.verdict, check_statuses=dict(self.check_statuses),
        )


class FakeEligibilityEvaluator:
    def __init__(self, *, eligible=True, reason_codes=()):
        self.eligible = eligible
        self.reason_codes = reason_codes

    def evaluate(self, *, snapshot, capability):
        return EligibilityResult(eligible=self.eligible, reason_codes=self.reason_codes)


_DEFAULT_CHECK_STATUSES = {
    "cluster_mode": "ha",
    "subject_member_token": "member-a-token",
    "peer_member_token": "member-b-token",
}


def _make_adapter(sessions: dict[str, FakeClusterXLMemberSession]) -> CPClusterXLCapabilityAdapter:
    return CPClusterXLCapabilityAdapter(session_resolver=lambda token: sessions[token])


def _make_coordinator(tmp_path, *, adapter, check_statuses=None, eligible=True):
    return ActionCoordinator(
        data_root=tmp_path,
        authorizer=PermitAuthorizer(),
        preflight_provider=FakePreflightProvider(check_statuses=check_statuses or _DEFAULT_CHECK_STATUSES),
        eligibility_evaluator=FakeEligibilityEvaluator(eligible=eligible),
        adapter_resolver=lambda entity_kind, action_type: adapter,
    )


def _create_and_preflight(coord, *, action_type=ACTION_TYPE_HA_GRACEFUL_FAILOVER, entity_id="cp-cluster-1"):
    record = coord.create_action(
        actor_ref="operator-1", action_type=action_type, operational_entity_id=entity_id,
        entity_kind=ENTITY_KIND_CP_CLUSTER, vendor="checkpoint", reason="controlled failover test",
    )
    return coord.run_preflight(record.action_id, member_canonical_ids=["member-a-token", "member-b-token"])


# ---------------------------------------------------------------------------
# capability() -- fail-closed gating
# ---------------------------------------------------------------------------


def test_capability_supported_for_ha_mode_cp_cluster_with_resolved_identity():
    adapter = _make_adapter({})
    snapshot = PreflightSnapshot(
        preflight_run_id="pf1", action_id="a1", operational_entity_id="e1", coherent=True,
        readiness_verdict="positive", check_statuses=_DEFAULT_CHECK_STATUSES,
    )
    cap = adapter.capability(
        entity_kind=ENTITY_KIND_CP_CLUSTER, action_type=ACTION_TYPE_HA_GRACEFUL_FAILOVER, evidence=snapshot,
    )
    assert cap.supported is True
    assert cap.reason is None


@pytest.mark.parametrize(
    "entity_kind,action_type,check_statuses,expected_reason",
    [
        ("pan_ha_pair", ACTION_TYPE_HA_GRACEFUL_FAILOVER, _DEFAULT_CHECK_STATUSES, "unsupported_entity_kind"),
        (ENTITY_KIND_CP_CLUSTER, "some_other_action", _DEFAULT_CHECK_STATUSES, "unsupported_action_type"),
        (ENTITY_KIND_CP_CLUSTER, ACTION_TYPE_HA_GRACEFUL_FAILOVER, {**_DEFAULT_CHECK_STATUSES, "cluster_mode": "vsx"}, "unsupported_cluster_mode"),
        (ENTITY_KIND_CP_CLUSTER, ACTION_TYPE_HA_GRACEFUL_FAILOVER, {**_DEFAULT_CHECK_STATUSES, "cluster_mode": "load_sharing"}, "unsupported_cluster_mode"),
        (ENTITY_KIND_CP_CLUSTER, ACTION_TYPE_HA_GRACEFUL_FAILOVER, {"cluster_mode": "ha"}, "insufficient_member_identity_evidence"),
    ],
)
def test_capability_unsupported_cases(entity_kind, action_type, check_statuses, expected_reason):
    adapter = _make_adapter({})
    snapshot = PreflightSnapshot(
        preflight_run_id="pf1", action_id="a1", operational_entity_id="e1", coherent=True,
        readiness_verdict="positive", check_statuses=check_statuses,
    )
    cap = adapter.capability(entity_kind=entity_kind, action_type=action_type, evidence=snapshot)
    assert cap.supported is False
    assert cap.reason == expected_reason


# ---------------------------------------------------------------------------
# build_plan() -- P18 (no command text), P12 (reversal disclosure)
# ---------------------------------------------------------------------------


def test_build_plan_failover_never_contains_command_text():
    adapter = _make_adapter({})
    snapshot = PreflightSnapshot(
        preflight_run_id="pf1", action_id="a1", operational_entity_id="e1", coherent=True,
        readiness_verdict="positive", check_statuses=_DEFAULT_CHECK_STATUSES,
    )
    plan = adapter.build_plan(entity=None, action_type=ACTION_TYPE_HA_GRACEFUL_FAILOVER, evidence=snapshot)
    assert plan.subject_member_token == "member-a-token"
    assert plan.intended_postcondition == "peer_member_active"
    assert plan.settle_observation is None
    for value in (plan.action_type, plan.intended_postcondition, plan.impact_disclosure,
                  plan.reversal_note, str(plan.material_action_parameters)):
        assert "clusterxl_admin" not in value.lower()


@pytest.mark.parametrize(
    "recovery_mode,expected_postcondition",
    [
        ("maintain_current_active", "subject_member_standby"),
        ("switch_to_higher_priority", "subject_member_active"),
        ("unknown", "UNKNOWN"),
        (None, "UNKNOWN"),
    ],
)
def test_build_plan_failback_intended_postcondition_follows_disclosed_recovery_mode(recovery_mode, expected_postcondition):
    adapter = _make_adapter({})
    check_statuses = dict(_DEFAULT_CHECK_STATUSES)
    if recovery_mode is not None:
        check_statuses["recovery_mode"] = recovery_mode
    snapshot = PreflightSnapshot(
        preflight_run_id="pf1", action_id="a1", operational_entity_id="e1", coherent=True,
        readiness_verdict="positive", check_statuses=check_statuses,
    )
    plan = adapter.build_plan(entity=None, action_type=ACTION_TYPE_HA_GRACEFUL_FAILBACK, evidence=snapshot)
    assert plan.intended_postcondition == expected_postcondition
    assert "separate" not in plan.reversal_note or "reversal" in plan.reversal_note.lower()


def test_build_plan_raises_without_resolved_member_identity():
    adapter = _make_adapter({})
    snapshot = PreflightSnapshot(
        preflight_run_id="pf1", action_id="a1", operational_entity_id="e1", coherent=True,
        readiness_verdict="positive", check_statuses={"cluster_mode": "ha"},
    )
    with pytest.raises(ValueError):
        adapter.build_plan(entity=None, action_type=ACTION_TYPE_HA_GRACEFUL_FAILOVER, evidence=snapshot)


# ---------------------------------------------------------------------------
# Eligibility handoff -- full run_preflight() through the real coordinator
# ---------------------------------------------------------------------------


def test_eligibility_handoff_reaches_awaiting_confirmation_with_real_adapter(tmp_path):
    sessions = {"member-a-token": FakeClusterXLMemberSession(role="ACTIVE"),
                "member-b-token": FakeClusterXLMemberSession(role="STANDBY")}
    adapter = _make_adapter(sessions)
    coord = _make_coordinator(tmp_path, adapter=adapter)
    record = _create_and_preflight(coord)

    assert record.state == ActionState.AWAITING_CONFIRMATION
    assert record.capability_id == "cp_clusterxl_admin_state_v1"
    assert record.subject_member_token == "member-a-token"
    assert record.intended_postcondition == "peer_member_active"
    # No session I/O yet -- capability()/build_plan() only consume evidence
    # already collected by the PreflightProvider, never touch a member.
    assert sessions["member-a-token"].read_role_calls == 0
    assert sessions["member-b-token"].read_role_calls == 0


def test_eligibility_handoff_is_not_eligible_when_cluster_mode_unsupported(tmp_path):
    sessions = {"member-a-token": FakeClusterXLMemberSession(), "member-b-token": FakeClusterXLMemberSession()}
    adapter = _make_adapter(sessions)
    check_statuses = {**_DEFAULT_CHECK_STATUSES, "cluster_mode": "vsx"}
    coord = _make_coordinator(tmp_path, adapter=adapter, check_statuses=check_statuses)
    record = _create_and_preflight(coord)

    assert record.state == ActionState.NOT_ELIGIBLE
    assert "no_adapter_capability" in record.reason_codes


def test_eligibility_handoff_not_eligible_when_evaluator_says_not_eligible(tmp_path):
    sessions = {"member-a-token": FakeClusterXLMemberSession(), "member-b-token": FakeClusterXLMemberSession()}
    adapter = _make_adapter(sessions)
    coord = _make_coordinator(tmp_path, adapter=adapter, eligible=False)
    record = _create_and_preflight(coord)

    assert record.state == ActionState.NOT_ELIGIBLE


# ---------------------------------------------------------------------------
# One-shot execution semantics -- exactly one submission, no retry
# ---------------------------------------------------------------------------


def test_execute_once_submits_exactly_once_and_succeeds_on_clean_failover(tmp_path):
    # role_sequence models the vendor's peer-driven transition: the fresh
    # precondition re-observation (1st read) still sees ACTIVE; the
    # independent post-action read (2nd read) sees DOWN, after execute_once
    # actually ran in between.
    sessions = {
        "member-a-token": FakeClusterXLMemberSession(role_sequence=["ACTIVE", "DOWN"], admin_down_pnote_present=True),
        "member-b-token": FakeClusterXLMemberSession(role="ACTIVE"),
    }
    adapter = _make_adapter(sessions)
    coord = _make_coordinator(tmp_path, adapter=adapter)
    record = _create_and_preflight(coord)

    confirmed = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["member-a-token", "member-b-token"],
    )

    assert confirmed.state == ActionState.SUCCEEDED
    assert confirmed.observed_postcondition == "peer_member_active"
    assert sessions["member-a-token"].submit_admin_down_calls == 1
    assert sessions["member-a-token"].submit_admin_up_calls == 0
    assert sessions["member-b-token"].submit_admin_down_calls == 0


def test_execute_once_never_called_when_precondition_no_longer_holds(tmp_path):
    """AWAITING_CONFIRMATION -> ABORTED_PRE_MUTATION when the fresh
    precondition re-observation (P6) finds the subject already changed --
    the mutation boundary must never be crossed in this case."""
    sessions = {
        "member-a-token": FakeClusterXLMemberSession(role="ACTIVE"),
        "member-b-token": FakeClusterXLMemberSession(role="STANDBY"),
    }
    adapter = _make_adapter(sessions)
    coord = _make_coordinator(tmp_path, adapter=adapter)
    record = _create_and_preflight(coord)

    # Someone else already failed this member over before confirmation.
    sessions["member-a-token"].role = "DOWN"

    confirmed = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["member-a-token", "member-b-token"],
    )

    assert confirmed.state == ActionState.ABORTED_PRE_MUTATION
    assert confirmed.mutation_boundary_crossed == "NO"
    assert sessions["member-a-token"].submit_admin_down_calls == 0


def test_execute_once_not_sent_maps_to_aborted_pre_mutation(tmp_path):
    sessions = {
        "member-a-token": FakeClusterXLMemberSession(role="ACTIVE", submission=SubmissionConfirmation.CONFIRMED_NOT_SENT),
        "member-b-token": FakeClusterXLMemberSession(role="STANDBY"),
    }
    adapter = _make_adapter(sessions)
    coord = _make_coordinator(tmp_path, adapter=adapter)
    record = _create_and_preflight(coord)

    confirmed = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["member-a-token", "member-b-token"],
    )

    assert confirmed.state == ActionState.ABORTED_PRE_MUTATION
    assert confirmed.submission_outcome_family == SubmissionOutcomeFamily.NOT_SENT.value
    assert sessions["member-a-token"].submit_admin_down_calls == 1


def test_adapter_never_retries_or_calls_admin_up_for_a_failover_action_type():
    """Static, adapter-level proof of OP.2.1's own no-blind-retry text: a
    failover's execute_once() only ever calls submit_admin_down(), exactly
    once, regardless of the confirmation it gets back."""
    for submission in SubmissionConfirmation:
        session = FakeClusterXLMemberSession(submission=submission)
        adapter = _make_adapter({"member-a-token": session})
        plan = adapter.build_plan(
            entity=None, action_type=ACTION_TYPE_HA_GRACEFUL_FAILOVER,
            evidence=PreflightSnapshot(
                preflight_run_id="pf1", action_id="a1", operational_entity_id="e1", coherent=True,
                readiness_verdict="positive", check_statuses=_DEFAULT_CHECK_STATUSES,
            ),
        )
        adapter.execute_once(plan=plan, action_id="a1")
        assert session.submit_admin_down_calls == 1
        assert session.submit_admin_up_calls == 0


# ---------------------------------------------------------------------------
# Unknown-outcome handling (P10) -- OUTCOME_UNKNOWN, never a guess
# ---------------------------------------------------------------------------


def test_ambiguous_submission_with_unreadable_postcondition_is_outcome_unknown(tmp_path):
    sessions = {
        "member-a-token": FakeClusterXLMemberSession(role="ACTIVE", read_failed=False),
        "member-b-token": FakeClusterXLMemberSession(role="STANDBY", read_failed=False),
    }
    adapter = _make_adapter(sessions)
    coord = _make_coordinator(tmp_path, adapter=adapter)
    record = _create_and_preflight(coord)

    # execute_once submits (SUBMITTED_OR_AMBIGUOUS, the default), but the
    # post-action read-back cannot corroborate a role flip on either side --
    # e.g. the transition had not yet settled at read time.
    confirmed = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["member-a-token", "member-b-token"],
    )

    assert confirmed.state == ActionState.OUTCOME_UNKNOWN
    assert confirmed.submission_outcome_family == SubmissionOutcomeFamily.UNKNOWN.value


def test_postcondition_read_failure_after_submission_is_outcome_unknown(tmp_path):
    # The precondition re-observation (1st read) succeeds; the submission
    # goes out; the post-action read-back (2nd read onward) itself fails --
    # this must never be inferred as success.
    sessions = {
        "member-a-token": FakeClusterXLMemberSession(role="ACTIVE", fail_starting_from_call=2),
        "member-b-token": FakeClusterXLMemberSession(role="STANDBY"),
    }
    adapter = _make_adapter(sessions)
    coord = _make_coordinator(tmp_path, adapter=adapter)
    record = _create_and_preflight(coord)

    confirmed = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["member-a-token", "member-b-token"],
    )

    assert confirmed.state == ActionState.OUTCOME_UNKNOWN


def test_split_brain_shaped_postcondition_is_incoherent_and_outcome_unknown(tmp_path):
    sessions = {
        "member-a-token": FakeClusterXLMemberSession(role="ACTIVE"),
        "member-b-token": FakeClusterXLMemberSession(role="ACTIVE"),
    }
    adapter = _make_adapter(sessions)
    coord = _make_coordinator(tmp_path, adapter=adapter)
    record = _create_and_preflight(coord)

    confirmed = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["member-a-token", "member-b-token"],
    )

    assert confirmed.state == ActionState.OUTCOME_UNKNOWN


def test_failback_with_undisclosed_recovery_mode_can_never_resolve_to_succeeded(tmp_path):
    """P12: an UNKNOWN reversal disclosure must never coincide with a
    SUCCEEDED classification, no matter what role is actually observed."""
    check_statuses = {**_DEFAULT_CHECK_STATUSES, "recovery_mode": "unknown"}
    # Precondition-time (1st read): still DOWN/admin_down, exactly what CP-M1
    # left it in. Post-submission (2nd read): reversal actually lands the
    # subject back in STANDBY -- a real, valid role -- but it can never equal
    # the literal "UNKNOWN" plan token.
    sessions = {
        "member-a-token": FakeClusterXLMemberSession(role_sequence=["DOWN", "STANDBY"], admin_down_pnote_present=True),
        "member-b-token": FakeClusterXLMemberSession(role="ACTIVE"),
    }
    adapter = _make_adapter(sessions)
    coord = _make_coordinator(tmp_path, adapter=adapter, check_statuses=check_statuses)
    record = _create_and_preflight(coord, action_type=ACTION_TYPE_HA_GRACEFUL_FAILBACK)
    assert record.intended_postcondition == "UNKNOWN"

    confirmed = coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["member-a-token", "member-b-token"],
    )

    assert confirmed.state == ActionState.OUTCOME_UNKNOWN
    assert confirmed.observed_postcondition == "subject_member_standby"


# ---------------------------------------------------------------------------
# Independent post-action verification (P9) -- observe reads independently
# of execute_once, both members, never inferred from the submission alone
# ---------------------------------------------------------------------------


def test_observe_postcondition_reads_both_members_independently_of_execute_once(tmp_path):
    sessions = {
        "member-a-token": FakeClusterXLMemberSession(role_sequence=["ACTIVE", "DOWN"], admin_down_pnote_present=True),
        "member-b-token": FakeClusterXLMemberSession(role="ACTIVE"),
    }
    adapter = _make_adapter(sessions)
    coord = _make_coordinator(tmp_path, adapter=adapter)
    record = _create_and_preflight(coord)

    coord.confirm(
        record.action_id, actor_ref="operator-1", proposal_digest=record.proposal_digest,
        member_canonical_ids=["member-a-token", "member-b-token"],
    )

    # check_precondition() reads the subject once; observe_postcondition()
    # reads both members once more, independently -- never derived from the
    # execute_once() call itself, which returns no role information at all.
    assert sessions["member-a-token"].read_role_calls == 2
    assert sessions["member-b-token"].read_role_calls == 1


def test_observe_postcondition_direct_call_is_independent_of_plan_intended_value():
    """Adapter-level unit proof: observe_postcondition() derives its result
    purely from the two sessions' current reads -- it never consults
    plan.intended_postcondition to decide what to report."""
    sessions = {
        "member-a-token": FakeClusterXLMemberSession(role="DOWN", admin_down_pnote_present=True),
        "member-b-token": FakeClusterXLMemberSession(role="ACTIVE"),
    }
    adapter = _make_adapter(sessions)
    from checkpoint.clusterxl_capability_adapter import ACTION_TYPE_HA_GRACEFUL_FAILOVER as FAILOVER
    plan = adapter.build_plan(
        entity=None, action_type=FAILOVER,
        evidence=PreflightSnapshot(
            preflight_run_id="pf1", action_id="a1", operational_entity_id="e1", coherent=True,
            readiness_verdict="positive", check_statuses=_DEFAULT_CHECK_STATUSES,
        ),
    )

    class _Entity:
        check_statuses = _DEFAULT_CHECK_STATUSES

    observation = adapter.observe_postcondition(entity=_Entity(), plan=plan)
    assert observation.postcondition_observed == "peer_member_active"
    assert observation.postcondition_observed == plan.intended_postcondition
    assert observation.coherent is True
    assert observation.both_members_observed is True


# ---------------------------------------------------------------------------
# Re-asserted non-reachability invariants (unaffected by this build)
# ---------------------------------------------------------------------------


def test_class_2_still_has_no_member_and_deny_all_is_still_the_only_authorizer():
    assert action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE.permitted is False
    assert action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE.console_submittable is False

    source = Path(operate_authorization.__file__).read_text(encoding="utf-8")
    assert "DenyAllAuthorizer" in source
    assert "class PermitAllAuthorizer" not in source


def test_utils_operate_allowlist_and_transport_ban_are_unaffected():
    allowed = {
        "__init__", "states", "record", "authorization", "approval_policy",
        "adapter", "eligibility", "store", "coordinator",
    }
    actual = {p.stem for p in (REPO_ROOT / "utils" / "operate").glob("*.py")}
    assert actual == allowed, f"utils/operate/ gained {actual - allowed}"


def test_production_coordinator_default_stays_unreachable(tmp_path):
    """Production defaults (no authorizer override, no adapter_resolver
    override) must stay exactly what OP.2.A/B already guarantees -- this
    adapter existing changes nothing about that."""
    coord = ActionCoordinator(data_root=tmp_path)
    with pytest.raises(Exception):
        coord.create_action(
            actor_ref="operator-1", action_type=ACTION_TYPE_HA_GRACEFUL_FAILOVER,
            operational_entity_id="cp-cluster-1", entity_kind=ENTITY_KIND_CP_CLUSTER,
            vendor="checkpoint", reason="must stay denied",
        )
    assert coord.store.list_all() == []


def test_adapter_module_is_not_referenced_by_any_production_coordinator_construction():
    """No non-test file anywhere in the repository constructs an
    ActionCoordinator with this adapter -- OP.2.C ships the typed adapter,
    it does not wire it live."""
    marker = "CPClusterXLCapabilityAdapter("
    for py_file in REPO_ROOT.rglob("*.py"):
        relative = py_file.relative_to(REPO_ROOT)
        parts = relative.parts
        if parts[0] in ("tests", ".git", "node_modules"):
            continue
        if parts[0] == "checkpoint" and relative.name == "clusterxl_capability_adapter.py":
            continue
        text = py_file.read_text(encoding="utf-8", errors="ignore")
        assert marker not in text, f"{relative} constructs the CP ClusterXL adapter outside tests/"
