"""OP.2.A/B -- the class 2 action coordinator.

The sole entry point for a class 2 action (§"Authorization boundary"): no
CLI/argv route exists anywhere to any method here, and no environment
variable or runtime flag selects the ``Authorizer`` -- it is a constructor
argument that defaults to ``DenyAllAuthorizer``. Nothing in this module
performs device I/O; ``preflight_provider``, ``eligibility_evaluator`` and
``adapter_resolver`` are injection seams with no production implementation
(``OP.2.C`` wires the first one). With the production defaults
(``DenyAllAuthorizer``, no adapter resolver), CLASS 2 stays structurally
unreachable: ``create_action`` denies before a record is even created, and
even a coordinator misconfigured to permit everything else still cannot
reach ``EXECUTING`` because eligibility fails ``no_adapter_capability``
first (defense in depth, not reliance on one gate).
"""
from __future__ import annotations

import uuid
from pathlib import Path
from typing import Callable

from utils.collection_executor import CollectionCoordinator
from utils.coordinator_backend import CoordinatorDecision

from .adapter import ActionPlan, PreconditionResult, SubmissionOutcomeFamily, VendorCapabilityAdapter
from .approval_policy import ApprovalPolicy, SingleOperatorApprovalPolicy
from .authorization import Authorizer, DenyAllAuthorizer
from .eligibility import EligibilityEvaluator, EligibilityResult, PreflightProvider
from .record import ActionRecord, compute_proposal_digest, utc_now
from .states import ActionState
from .store import ActionRecordStore


class AuthorizationDeniedError(RuntimeError):
    def __init__(self, reason_code: str) -> None:
        super().__init__(f"authorization denied: {reason_code}")
        self.reason_code = reason_code


def _admission_entry(*, stage: str, admitted_at: str | None, released_at: str | None, member_count: int) -> dict:
    return {"stage": stage, "admitted_at": admitted_at, "released_at": released_at, "member_count": member_count}


class ActionCoordinator:
    def __init__(
        self,
        *,
        data_root: Path,
        collection_coordinator: CollectionCoordinator | None = None,
        authorizer: Authorizer | None = None,
        approval_policy: ApprovalPolicy | None = None,
        preflight_provider: PreflightProvider | None = None,
        eligibility_evaluator: EligibilityEvaluator | None = None,
        adapter_resolver: Callable[[str, str], VendorCapabilityAdapter | None] | None = None,
    ) -> None:
        self.store = ActionRecordStore(data_root)
        self._collection_coordinator = collection_coordinator or CollectionCoordinator()
        self._authorizer: Authorizer = authorizer or DenyAllAuthorizer()
        self._approval_policy: ApprovalPolicy = approval_policy or SingleOperatorApprovalPolicy()
        self._preflight_provider = preflight_provider
        self._eligibility_evaluator = eligibility_evaluator
        self._adapter_resolver = adapter_resolver or (lambda entity_kind, action_type: None)

    # ------------------------------------------------------------------
    # Entry point (P2)
    # ------------------------------------------------------------------

    def create_action(
        self,
        *,
        actor_ref: str,
        action_type: str,
        operational_entity_id: str,
        entity_kind: str,
        vendor: str,
        reason: str,
        reverses_action_id: str | None = None,
    ) -> ActionRecord:
        """Authorization is evaluated before entity/record creation and
        before any device contact (P2). A ``DENY`` creates no record."""
        decision = self._authorizer.authorize(
            actor_ref=actor_ref, action_type=action_type, operational_entity_id=operational_entity_id,
        )
        if not decision.permitted:
            raise AuthorizationDeniedError(decision.reason_code)

        record = ActionRecord(
            action_id=uuid.uuid4().hex,
            actor_ref=actor_ref,
            action_type=action_type,
            operational_entity_id=operational_entity_id,
            entity_kind=entity_kind,
            vendor=vendor,
            operator_reason=reason,
            reverses_action_id=reverses_action_id,
            state=ActionState.CREATED,
        )
        return self.store.create(record)

    # ------------------------------------------------------------------
    # PREFLIGHTING -> AWAITING_CONFIRMATION | NOT_ELIGIBLE | ABORTED_PRE_MUTATION
    # ------------------------------------------------------------------

    def run_preflight(self, action_id: str, *, member_canonical_ids: list[str]) -> ActionRecord:
        record = self.store.get(action_id)
        if record is None:
            raise ValueError(f"unknown action_id {action_id!r}")
        if record.state != ActionState.CREATED:
            return record

        moved = self.store.guarded_transition(
            action_id, from_states={ActionState.CREATED}, to_state=ActionState.PREFLIGHTING, reason_code="preflight_started",
        )
        if moved is None:
            return self.store.get(action_id)  # type: ignore[return-value]

        decision, job, _active = self._collection_coordinator.admit_request(
            record.vendor, "op2_preflight", member_canonical_ids, provenance="class2_action",
        )
        if decision != CoordinatorDecision.ADMITTED:
            admissions = list(moved.admissions) + [
                _admission_entry(stage="preflight", admitted_at=None, released_at=None, member_count=len(member_canonical_ids))
            ]
            return self._abort_pre_mutation(
                action_id, reason_code="member_busy", admissions=admissions, from_states={ActionState.PREFLIGHTING},
            )

        admitted_at = utc_now()
        try:
            snapshot = (
                self._preflight_provider.run_preflight(action_id=action_id, operational_entity_id=record.operational_entity_id)
                if self._preflight_provider is not None
                else None
            )
        finally:
            self._collection_coordinator.release(job.job_id)
        admissions = list(moved.admissions) + [
            _admission_entry(stage="preflight", admitted_at=admitted_at, released_at=utc_now(), member_count=len(member_canonical_ids))
        ]

        # P4: eligibility may consume only this action's own, same-entity generation.
        if snapshot is None or snapshot.action_id != action_id or snapshot.operational_entity_id != record.operational_entity_id:
            return self._not_eligible(action_id, reason_codes=("insufficient_evidence",), admissions=admissions)
        if not snapshot.coherent:
            return self._not_eligible(action_id, reason_codes=("insufficient_evidence",), admissions=admissions)

        adapter = self._adapter_resolver(record.entity_kind, record.action_type)
        capability = (
            adapter.capability(entity_kind=record.entity_kind, action_type=record.action_type, evidence=snapshot)
            if adapter is not None
            else None
        )
        eligibility: EligibilityResult = (
            self._eligibility_evaluator.evaluate(snapshot=snapshot, capability=capability)
            if self._eligibility_evaluator is not None
            else EligibilityResult(eligible=False, reason_codes=("no_eligibility_evaluator_configured",))
        )

        if capability is None or not capability.supported:
            reason_codes = tuple(eligibility.reason_codes) + ("no_adapter_capability",)
            return self._not_eligible(action_id, reason_codes=reason_codes, admissions=admissions)
        if not eligibility.eligible:
            return self._not_eligible(action_id, reason_codes=eligibility.reason_codes, admissions=admissions)

        plan = adapter.build_plan(entity=record, action_type=record.action_type, evidence=snapshot)
        eligibility_payload = {"eligible": eligibility.eligible, "reason_codes": list(eligibility.reason_codes)}
        digest = compute_proposal_digest(
            action_id=action_id,
            action_type=record.action_type,
            operational_entity_id=record.operational_entity_id,
            intended_postcondition=plan.intended_postcondition,
            subject_member_token=plan.subject_member_token,
            preflight_generation_id=snapshot.preflight_run_id,
            eligibility_result=eligibility_payload,
            material_action_parameters=plan.material_action_parameters,
        )

        proposed = self.store.guarded_transition(
            action_id,
            from_states={ActionState.PREFLIGHTING},
            to_state=ActionState.AWAITING_CONFIRMATION,
            reason_code="proposal_ready",
            mutate=lambda cur: {
                "admissions": admissions,
                "pre_action_preflight_run_id": snapshot.preflight_run_id,
                "preflight_generation_id": snapshot.preflight_run_id,
                "readiness_verdict": snapshot.readiness_verdict,
                "check_statuses": dict(snapshot.check_statuses),
                "eligibility_result": eligibility_payload,
                "reason_codes": list(eligibility.reason_codes),
                "proposal_digest": digest,
                "intended_postcondition": plan.intended_postcondition,
                "subject_member_token": plan.subject_member_token,
                "material_action_parameters": dict(plan.material_action_parameters),
                "settle_observation": plan.settle_observation,
                "capability_id": capability.capability_id,
            },
        )
        return proposed if proposed is not None else self.store.get(action_id)  # type: ignore[return-value]

    def _not_eligible(self, action_id: str, *, reason_codes: tuple[str, ...], admissions: list[dict]) -> ActionRecord:
        moved = self.store.guarded_transition(
            action_id,
            from_states={ActionState.PREFLIGHTING},
            to_state=ActionState.NOT_ELIGIBLE,
            reason_code=",".join(reason_codes) or "not_eligible",
            mutate=lambda cur: {"reason_codes": list(reason_codes), "admissions": admissions},
        )
        return moved if moved is not None else self.store.get(action_id)  # type: ignore[return-value]

    def _abort_pre_mutation(
        self,
        action_id: str,
        *,
        reason_code: str,
        from_states: set[ActionState],
        admissions: list[dict] | None = None,
    ) -> ActionRecord:
        mutate = (lambda cur: {"admissions": admissions}) if admissions is not None else None
        moved = self.store.guarded_transition(
            action_id, from_states=from_states, to_state=ActionState.ABORTED_PRE_MUTATION, mutate=mutate, reason_code=reason_code,
        )
        return moved if moved is not None else self.store.get(action_id)  # type: ignore[return-value]

    # ------------------------------------------------------------------
    # Cancellation -- legal pre-mutation only (impossible from EXECUTING: no
    # code path below even attempts it).
    # ------------------------------------------------------------------

    def cancel(self, action_id: str, *, actor_ref: str) -> ActionRecord:
        moved = self.store.guarded_transition(
            action_id,
            from_states={ActionState.CREATED, ActionState.PREFLIGHTING, ActionState.AWAITING_CONFIRMATION},
            to_state=ActionState.CANCELLED,
            reason_code="operator_cancelled",
        )
        return moved if moved is not None else self.store.get(action_id)  # type: ignore[return-value]

    # ------------------------------------------------------------------
    # The mutation boundary (P6) -- guarded AWAITING_CONFIRMATION -> EXECUTING
    # ------------------------------------------------------------------

    def _plan_from_record(self, record: ActionRecord) -> ActionPlan:
        return ActionPlan(
            action_type=record.action_type,
            intended_postcondition=record.intended_postcondition or "",
            subject_member_token=record.subject_member_token or "",
            impact_disclosure="",
            reversal_note="",
            settle_observation=record.settle_observation,
            material_action_parameters=dict(record.material_action_parameters),
        )

    def confirm(
        self, action_id: str, *, actor_ref: str, proposal_digest: str, member_canonical_ids: list[str]
    ) -> ActionRecord:
        record = self.store.get(action_id)
        if record is None:
            raise ValueError(f"unknown action_id {action_id!r}")
        if record.state != ActionState.AWAITING_CONFIRMATION:
            # Duplicate confirmation, or a confirmation that lost a race, or
            # one arriving after reconciliation already moved the record:
            # return the existing truth, submit nothing (P7).
            return record
        if proposal_digest != record.proposal_digest:
            # Refused, not an invalidation: the record stays where it is.
            return record

        decision, job, _active = self._collection_coordinator.admit_request(
            record.vendor, "op2_execute", member_canonical_ids, provenance="class2_action",
        )
        if decision != CoordinatorDecision.ADMITTED:
            return self._abort_pre_mutation(
                action_id, reason_code="member_busy", from_states={ActionState.AWAITING_CONFIRMATION},
            )

        admitted_at = utc_now()
        adapter = self._adapter_resolver(record.entity_kind, record.action_type)
        plan = self._plan_from_record(record)
        try:
            precondition = adapter.check_precondition(plan=plan) if adapter is not None else PreconditionResult.UNKNOWN
            if precondition != PreconditionResult.HOLDS:
                admissions = list(record.admissions) + [
                    _admission_entry(stage="execute", admitted_at=admitted_at, released_at=utc_now(), member_count=len(member_canonical_ids))
                ]
                reason = "precondition_changed" if precondition == PreconditionResult.CHANGED else "precondition_unknown"
                return self._abort_pre_mutation(
                    action_id, reason_code=reason, admissions=admissions, from_states={ActionState.AWAITING_CONFIRMATION},
                )

            confirmations = list(record.confirmations) + [{"actor_ref": actor_ref, "at": utc_now()}]
            committed = self.store.guarded_transition(
                action_id,
                from_states={ActionState.AWAITING_CONFIRMATION},
                to_state=ActionState.EXECUTING,
                reason_code="confirmed",
                mutate=lambda cur: {
                    "confirmed_at": utc_now(),
                    "confirmations": confirmations,
                    "precondition_result": precondition.value,
                    "precondition_observed_at": utc_now(),
                    # P6: written durably before execute_once is ever called.
                    "mutation_boundary_crossed": "YES",
                    "boundary_committed_at": utc_now(),
                },
            )
            if committed is None:
                # Lost the guarded race: another confirmation or a
                # cancellation won first. Submit nothing (P6/AC-15).
                return self.store.get(action_id)  # type: ignore[return-value]

            return self._submit_and_verify(committed, plan=plan, adapter=adapter, admitted_at=admitted_at, member_count=len(member_canonical_ids))
        finally:
            self._collection_coordinator.release(job.job_id)

    def _submit_and_verify(
        self,
        record: ActionRecord,
        *,
        plan: ActionPlan,
        adapter: VendorCapabilityAdapter | None,
        admitted_at: str,
        member_count: int,
    ) -> ActionRecord:
        assert adapter is not None  # eligibility (no_adapter_capability) already excludes this
        outcome_family = adapter.execute_once(plan=plan, action_id=record.action_id)
        admissions = list(record.admissions) + [
            _admission_entry(stage="execute", admitted_at=admitted_at, released_at=utc_now(), member_count=member_count)
        ]

        if outcome_family == SubmissionOutcomeFamily.NOT_SENT:
            # P6: the only case that maps back to the pre-mutation family --
            # the adapter positively proved the submission never left.
            moved = self.store.guarded_transition(
                record.action_id,
                from_states={ActionState.EXECUTING},
                to_state=ActionState.ABORTED_PRE_MUTATION,
                reason_code="submission_not_sent",
                mutate=lambda cur: {"admissions": admissions, "submission_outcome_family": outcome_family.value},
            )
            return moved if moved is not None else self.store.get(record.action_id)  # type: ignore[return-value]

        observation = adapter.observe_postcondition(entity=record, plan=plan)
        outcome_state, reason_code = _classify_observation(observation, plan)
        moved = self.store.guarded_transition(
            record.action_id,
            from_states={ActionState.EXECUTING},
            to_state=outcome_state,
            reason_code=reason_code,
            mutate=lambda cur: {
                "admissions": admissions,
                "submission_outcome_family": outcome_family.value,
                "observed_postcondition": observation.postcondition_observed,
            },
        )
        return moved if moved is not None else self.store.get(record.action_id)  # type: ignore[return-value]

    # ------------------------------------------------------------------
    # Crash / restart reconciliation (§"Crash / restart recovery")
    # ------------------------------------------------------------------

    def reconcile_on_startup(self) -> list[ActionRecord]:
        """Runs once, before this process accepts any request. Never
        replays, never resumes past the boundary, never resolves a
        post-boundary record to anything but ``OUTCOME_UNKNOWN``."""
        reconciled: list[ActionRecord] = []
        table = {
            ActionState.CREATED: ("process_restart", ActionState.ABORTED_PRE_MUTATION),
            ActionState.PREFLIGHTING: ("process_restart", ActionState.ABORTED_PRE_MUTATION),
            ActionState.AWAITING_CONFIRMATION: ("confirmation_context_lost", ActionState.ABORTED_PRE_MUTATION),
            ActionState.EXECUTING: ("process_restart_after_mutation_boundary", ActionState.OUTCOME_UNKNOWN),
        }
        for record in self.store.list_all():
            entry = table.get(record.state)
            if entry is None:
                continue
            reason_code, to_state = entry
            moved = self.store.guarded_transition(
                record.action_id, from_states={record.state}, to_state=to_state, reason_code=reason_code,
            )
            if moved is not None:
                reconciled.append(moved)
        return reconciled

    # ------------------------------------------------------------------
    # OUTCOME_UNKNOWN acknowledgement (P10) -- itself an authorized action
    # ------------------------------------------------------------------

    def acknowledge_unknown_outcome(self, action_id: str, *, actor_ref: str) -> ActionRecord:
        record = self.store.get(action_id)
        if record is None:
            raise ValueError(f"unknown action_id {action_id!r}")
        decision = self._authorizer.authorize(
            actor_ref=actor_ref,
            action_type="acknowledge_unknown_outcome",
            operational_entity_id=record.operational_entity_id,
        )
        if not decision.permitted:
            raise AuthorizationDeniedError(decision.reason_code)
        return self.store.acknowledge(action_id, actor_ref=actor_ref)


def _classify_observation(observation, plan: ActionPlan) -> tuple[ActionState, str]:
    """§"Post-action verification" -- strict, and ``FAILED_NO_CHANGE`` is
    unreachable while ``settle_observation`` is ``UNKNOWN``/unset (AC-18)."""
    if observation.read_failed or not observation.both_members_observed or not observation.coherent or not observation.mode_supported:
        return ActionState.OUTCOME_UNKNOWN, "verification_inconclusive"
    if observation.postcondition_observed == plan.intended_postcondition:
        return ActionState.SUCCEEDED, "postcondition_observed"
    if plan.settle_observation and plan.settle_observation != "UNKNOWN":
        return ActionState.FAILED_NO_CHANGE, "original_state_confirmed_settled"
    return ActionState.OUTCOME_UNKNOWN, "settle_unknown_no_transition_observed"
