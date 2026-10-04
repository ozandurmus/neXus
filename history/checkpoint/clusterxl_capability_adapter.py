"""OP.2.C -- the Check Point ClusterXL CLASS 2 capability adapter.

Implements `utils.operate.adapter.VendorCapabilityAdapter` for the two
mutation primitives `docs/history/phase/OP_2_1_CP_CLUSTERXL_MUTATION_
COMMAND_GATE.md` names `APPROVED_FOR_OP2C`:

- `cp_clusterxl_ha_graceful_failover` (CP-M1, `clusterXL_admin down`)
- `cp_clusterxl_ha_graceful_failback` (CP-M1-R, `clusterXL_admin up`,
  CP-M1's explicit reversal -- never chained automatically, P12)

Building this class does not, by itself, make CLASS 2 reachable: nothing in
this repository constructs an `ActionCoordinator` with a real
`adapter_resolver` outside `tests/`, `utils.action_taxonomy.
CLASS_2_OPERATIONAL_STATE_CHANGE` still has no member, and `DenyAllAuthorizer`
is still the only production `Authorizer` (`utils/operate/authorization.py`).
This module only supplies the typed vendor-adapter implementation `OP.2.C`'s
own contract calls for; wiring it into a production resolver is a separate,
later decision this module does not make (`CURRENT_STATE.md` "Open
blockers" -- `DEPLOY.1A`, SSH trust hardening, the signed change-management
review all remain unresolved by this file).

Command text (`clusterXL_admin down` / `up`) never appears on `ActionPlan`
or `ActionRecord` (P18) -- it is resolved only inside a concrete
`ClusterXLMemberSession` implementation, which this module deliberately
does not provide. `ClusterXLMemberSession` is a narrow, typed transport
seam (read the member's current role/pnote state; submit exactly one of the
two approved admin-state commands) that a later, separate movement backs
with the real per-member SSH session already validated for the CP.0b read
battery (`checkpoint/preflight_collector.py::MemberSession`) -- that
integration is intentionally out of this build's scope (production
transport/session-lifecycle wiring is not "the adapter itself").

Every fact this adapter reasons about -- cluster mode, the opaque
`subject_member_token`/`peer_member_token`, the configured recovery method
-- is read from the already-resolved `check_statuses` evidence dict a
`PreflightProvider` attaches to the action's own preflight generation
(`utils.operate.eligibility.PreflightSnapshot`) or, at confirm/execute time,
from the durable `ActionRecord` that generation was written onto
(`utils.operate.record.ActionRecord.check_statuses`). This adapter never
re-resolves a member's identity from a hostname or address (`OP.2.0` P17)
and never invents a settle timer (`settle_observation` stays `None` per the
gate's own finding: `FAILED_NO_CHANGE` is not reachable until a real-env
pilot measures it).
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Callable, Protocol

from utils.operate.adapter import (
    ActionPlan,
    Capability,
    Observation,
    PreconditionResult,
    SubmissionOutcomeFamily,
)

__all__ = [
    "ENTITY_KIND_CP_CLUSTER",
    "ACTION_TYPE_HA_GRACEFUL_FAILOVER",
    "ACTION_TYPE_HA_GRACEFUL_FAILBACK",
    "MemberRoleReading",
    "SubmissionConfirmation",
    "ClusterXLMemberSession",
    "CPClusterXLCapabilityAdapter",
]

#: `OP.2.0`/`OP.2.1` identity invariants -- this adapter never targets a VSID,
#: never a PAN entity, never a Load Sharing cluster.
ENTITY_KIND_CP_CLUSTER = "cp_cluster"

#: The two `action_type` labels `OP.2.1`'s command-gate matrix proposes.
#: Never a command string (P18) -- typed, vendor-neutral labels only.
ACTION_TYPE_HA_GRACEFUL_FAILOVER = "cp_clusterxl_ha_graceful_failover"
ACTION_TYPE_HA_GRACEFUL_FAILBACK = "cp_clusterxl_ha_graceful_failback"

_SUPPORTED_ACTION_TYPES = frozenset({ACTION_TYPE_HA_GRACEFUL_FAILOVER, ACTION_TYPE_HA_GRACEFUL_FAILBACK})

_CAPABILITY_ID = "cp_clusterxl_admin_state_v1"

#: Only High Availability (New) mode is in scope -- VSX and Load Sharing have
#: no single standby/failover unit in the frozen model (`OP.2.1` Scope).
_CLUSTER_MODE_HA = "ha"

#: `OP.2.1` "Reversal preemption disclosure" / `D-V7b` -- the configured
#: recovery method is not machine-readably established; both named official
#: values are accepted when a future `PreflightProvider` does resolve it.
_RECOVERY_MODE_MAINTAIN_CURRENT_ACTIVE = "maintain_current_active"
_RECOVERY_MODE_SWITCH_TO_HIGHER_PRIORITY = "switch_to_higher_priority"
_RECOVERY_MODE_UNKNOWN = "unknown"

#: Symbolic, vendor-neutral postcondition tokens -- never a command string,
#: never a bare role string that could collide with a real observed value.
_POSTCONDITION_PEER_ACTIVE = "peer_member_active"
_POSTCONDITION_SUBJECT_STANDBY = "subject_member_standby"
_POSTCONDITION_SUBJECT_ACTIVE = "subject_member_active"
#: Deliberately never equal to any real observed postcondition token above --
#: this is what keeps a disclosed-`UNKNOWN` reversal from ever spuriously
#: classifying as `SUCCEEDED` (`OP.2.0` P12 tolerates `UNKNOWN`; it must
#: never silently resolve to a guess).
_POSTCONDITION_UNKNOWN = "UNKNOWN"

_ROLE_ACTIVE = "ACTIVE"
_ROLE_STANDBY = "STANDBY"
_ROLE_DOWN = "DOWN"


class SubmissionConfirmation(str, Enum):
    """What a `ClusterXLMemberSession` positively knows about one submitted
    admin-state command -- never more than this two-way split (`OP.2.0` P6:
    `SUBMISSION_NOT_SENT` is the only pre-boundary escape; everything else,
    including a timeout after send or an ambiguous shell response, is
    `SUBMISSION_OUTCOME_UNKNOWN`, per `OP.2.1` "No-blind-retry behaviour")."""

    CONFIRMED_NOT_SENT = "CONFIRMED_NOT_SENT"
    SUBMITTED_OR_AMBIGUOUS = "SUBMITTED_OR_AMBIGUOUS"


@dataclass(frozen=True)
class MemberRoleReading:
    """One member's already-approved-battery evidence (`CP-A3`/`CP-A5`),
    already parsed to typed facts -- this adapter never parses raw CLI text
    itself (that stays a transport-layer concern, same separation
    `cp_preflight_projection.py` already draws between raw reads and
    projected facts)."""

    role: str | None  # one of ACTIVE / STANDBY / DOWN, or None if unresolved
    admin_down_pnote_present: bool | None
    read_failed: bool


class ClusterXLMemberSession(Protocol):
    """Narrow per-member transport seam -- callers can request exactly the
    approved role/pnote read or exactly one of the two approved admin-state
    submissions; there is no method here that accepts caller-supplied
    command text (`OP.2.0` P11/P18). A concrete implementation resolves
    `clusterXL_admin down`/`up` internally, over the existing per-member
    session/transport `OP.2.1` already requires reuse of -- this module
    provides no such implementation (see module docstring)."""

    def read_role(self) -> MemberRoleReading:
        ...

    def submit_admin_down(self) -> SubmissionConfirmation:
        ...

    def submit_admin_up(self) -> SubmissionConfirmation:
        ...


def _check_statuses(evidence: Any) -> dict[str, Any]:
    if evidence is None:
        return {}
    statuses = getattr(evidence, "check_statuses", None)
    return statuses if isinstance(statuses, dict) else {}


class CPClusterXLCapabilityAdapter:
    """`VendorCapabilityAdapter` for CP-M1 / CP-M1-R (`OP.2.1`).

    `session_resolver` is the only injection seam: given an opaque
    `subject_member_token` (or `peer_member_token`), it returns the
    `ClusterXLMemberSession` to read/act through. Production code supplies
    no real resolver today (see module docstring); tests supply a fake.
    """

    def __init__(self, *, session_resolver: Callable[[str], ClusterXLMemberSession]) -> None:
        self._session_resolver = session_resolver

    # ------------------------------------------------------------------
    # capability() -- fail closed on anything not exactly HA-mode CP
    # ------------------------------------------------------------------

    def capability(self, *, entity_kind: str, action_type: str, evidence: Any) -> Capability:
        if entity_kind != ENTITY_KIND_CP_CLUSTER:
            return Capability(
                entity_kind=entity_kind, action_type=action_type, capability_id=_CAPABILITY_ID,
                supported=False, reason="unsupported_entity_kind",
            )
        if action_type not in _SUPPORTED_ACTION_TYPES:
            return Capability(
                entity_kind=entity_kind, action_type=action_type, capability_id=_CAPABILITY_ID,
                supported=False, reason="unsupported_action_type",
            )
        statuses = _check_statuses(evidence)
        # OP.2.1 "Intended effect and target scope": never propose this
        # primitive against a non-HA-mode entity (VSX/Load Sharing) even if
        # asked -- capability() must return UNSUPPORTED, not attempt it.
        if statuses.get("cluster_mode") != _CLUSTER_MODE_HA:
            return Capability(
                entity_kind=entity_kind, action_type=action_type, capability_id=_CAPABILITY_ID,
                supported=False, reason="unsupported_cluster_mode",
            )
        if not statuses.get("subject_member_token") or not statuses.get("peer_member_token"):
            return Capability(
                entity_kind=entity_kind, action_type=action_type, capability_id=_CAPABILITY_ID,
                supported=False, reason="insufficient_member_identity_evidence",
            )
        return Capability(
            entity_kind=entity_kind, action_type=action_type, capability_id=_CAPABILITY_ID,
            supported=True, reason=None,
        )

    # ------------------------------------------------------------------
    # build_plan() -- P18: no command text anywhere on the returned plan
    # ------------------------------------------------------------------

    def build_plan(self, *, entity: Any, action_type: str, evidence: Any) -> ActionPlan:
        statuses = _check_statuses(evidence)
        subject_member_token = statuses.get("subject_member_token")
        peer_member_token = statuses.get("peer_member_token")
        if not subject_member_token or not peer_member_token:
            # capability() already excludes this; reaching here is a defect
            # in the caller, not a condition this adapter silently tolerates.
            raise ValueError("build_plan called without resolved member identity evidence")

        if action_type == ACTION_TYPE_HA_GRACEFUL_FAILOVER:
            return ActionPlan(
                action_type=action_type,
                intended_postcondition=_POSTCONDITION_PEER_ACTIVE,
                subject_member_token=subject_member_token,
                impact_disclosure=(
                    "Graceful ClusterXL admin-down failover: the currently-active "
                    "member (subject_member_token) registers the admin_down "
                    "Critical Device and transitions to DOWN; the peer member "
                    "observes this and takes over as ACTIVE. Peer-driven, not a "
                    "direct manipulation of the peer."
                ),
                reversal_note=(
                    "Reversal is a separate typed action "
                    f"({ACTION_TYPE_HA_GRACEFUL_FAILBACK}) that must reference this "
                    "action_id via reverses_action_id; never fired automatically."
                ),
                settle_observation=None,
                material_action_parameters={"target_admin_state": "down"},
            )

        # ACTION_TYPE_HA_GRACEFUL_FAILBACK -- CP-M1-R, the explicit reversal.
        recovery_mode = statuses.get("recovery_mode", _RECOVERY_MODE_UNKNOWN)
        if recovery_mode == _RECOVERY_MODE_MAINTAIN_CURRENT_ACTIVE:
            intended_postcondition = _POSTCONDITION_SUBJECT_STANDBY
            disclosure = (
                "Reversal via clusterXL_admin up. Cluster recovery method is "
                "'maintain current active member': the reversed member returns "
                "to STANDBY; the member that took over during the failover "
                "stays ACTIVE. No second impact expected."
            )
        elif recovery_mode == _RECOVERY_MODE_SWITCH_TO_HIGHER_PRIORITY:
            intended_postcondition = _POSTCONDITION_SUBJECT_ACTIVE
            disclosure = (
                "Reversal via clusterXL_admin up. Cluster recovery method is "
                "'switch to higher priority member' and the reversed member has "
                "higher configured priority: it takes over again immediately. "
                "A second brief impact is caused by this reversal itself."
            )
        else:
            # OP.2.1 "Reversal preemption disclosure" / D-V7b: the recovery
            # method is not established. The plan discloses UNKNOWN and the
            # operator decides (OP.2.0 P12) -- no guess is made here.
            intended_postcondition = _POSTCONDITION_UNKNOWN
            disclosure = (
                "Reversal via clusterXL_admin up. The cluster's configured "
                "recovery method (maintain-current-active vs. "
                "switch-to-higher-priority) is UNKNOWN for this entity -- "
                "whether this reversal causes a second impact is therefore also "
                "UNKNOWN. The operator must decide with this disclosed."
            )

        return ActionPlan(
            action_type=action_type,
            intended_postcondition=intended_postcondition,
            subject_member_token=subject_member_token,
            impact_disclosure=disclosure,
            reversal_note="This action is itself a reversal; it defines no further reversal.",
            settle_observation=None,
            material_action_parameters={"target_admin_state": "up"},
        )

    # ------------------------------------------------------------------
    # check_precondition() -- class 0 read only, re-observed fresh (P6)
    # ------------------------------------------------------------------

    def check_precondition(self, *, plan: ActionPlan) -> PreconditionResult:
        session = self._session_resolver(plan.subject_member_token)
        reading = session.read_role()
        if reading.read_failed or reading.role is None:
            return PreconditionResult.UNKNOWN

        if plan.action_type == ACTION_TYPE_HA_GRACEFUL_FAILOVER:
            # The subject must still be the currently-active member.
            return PreconditionResult.HOLDS if reading.role == _ROLE_ACTIVE else PreconditionResult.CHANGED

        # ACTION_TYPE_HA_GRACEFUL_FAILBACK -- the subject must still be in
        # exactly the state CP-M1 left it in (DOWN / admin_down registered).
        if reading.role != _ROLE_DOWN:
            return PreconditionResult.CHANGED
        if reading.admin_down_pnote_present is None:
            # Missing A5 evidence (the pnote read failed or was never
            # observed) never authorizes a failback -- fail closed rather
            # than silently falling through to HOLDS (OP.2.C1 safety
            # correction). The role read alone is never sufficient
            # corroboration for the state CP-M1 actually left this member
            # in.
            return PreconditionResult.UNKNOWN
        if reading.admin_down_pnote_present is False:
            return PreconditionResult.CHANGED
        return PreconditionResult.HOLDS

    # ------------------------------------------------------------------
    # execute_once() -- exactly one submission, no retry (P7)
    # ------------------------------------------------------------------

    def execute_once(self, *, plan: ActionPlan, action_id: str) -> SubmissionOutcomeFamily:
        session = self._session_resolver(plan.subject_member_token)
        if plan.action_type == ACTION_TYPE_HA_GRACEFUL_FAILOVER:
            confirmation = session.submit_admin_down()
        elif plan.action_type == ACTION_TYPE_HA_GRACEFUL_FAILBACK:
            confirmation = session.submit_admin_up()
        else:
            raise ValueError(f"unsupported action_type reached execute_once: {plan.action_type!r}")

        if confirmation == SubmissionConfirmation.CONFIRMED_NOT_SENT:
            return SubmissionOutcomeFamily.NOT_SENT
        return SubmissionOutcomeFamily.UNKNOWN

    # ------------------------------------------------------------------
    # observe_postcondition() -- independent, dual-member, class 0 only (P9)
    # ------------------------------------------------------------------

    def observe_postcondition(self, *, entity: Any, plan: ActionPlan) -> Observation:
        statuses = _check_statuses(entity)
        peer_member_token = statuses.get("peer_member_token")
        cluster_mode = statuses.get("cluster_mode")

        subject_reading = self._session_resolver(plan.subject_member_token).read_role()
        peer_reading = (
            self._session_resolver(peer_member_token).read_role()
            if peer_member_token
            else MemberRoleReading(role=None, admin_down_pnote_present=None, read_failed=True)
        )

        read_failed = subject_reading.read_failed or peer_reading.read_failed
        both_members_observed = (
            not read_failed and subject_reading.role is not None and peer_reading.role is not None
        )
        coherent = True
        if subject_reading.role is not None and peer_reading.role is not None:
            # Split-brain / stalled-transition symptom: both members reporting
            # the identical role is never a coherent post-action pair for a
            # two-member HA cluster.
            coherent = subject_reading.role != peer_reading.role
        mode_supported = cluster_mode == _CLUSTER_MODE_HA

        postcondition_observed = self._observed_postcondition(
            action_type=plan.action_type, subject_reading=subject_reading, peer_reading=peer_reading,
        )

        return Observation(
            postcondition_observed=postcondition_observed,
            coherent=coherent,
            both_members_observed=both_members_observed,
            read_failed=read_failed,
            mode_supported=mode_supported,
        )

    @staticmethod
    def _observed_postcondition(
        *, action_type: str, subject_reading: MemberRoleReading, peer_reading: MemberRoleReading,
    ) -> str | None:
        # OP.2.1's own "Expected observable postcondition" rows name two
        # independent corroborating signals for each primitive -- the role
        # transition and the admin_down pnote's appearance/disappearance --
        # never role alone (OP.2.C1 safety correction: a role-only read is
        # a generic "role changed" inference, exactly what the gate's own
        # pnote signal exists to avoid).
        if action_type == ACTION_TYPE_HA_GRACEFUL_FAILOVER:
            if (
                subject_reading.role == _ROLE_DOWN
                and subject_reading.admin_down_pnote_present is True
                and peer_reading.role == _ROLE_ACTIVE
            ):
                return _POSTCONDITION_PEER_ACTIVE
            return None
        # ACTION_TYPE_HA_GRACEFUL_FAILBACK -- the admin_down pnote must be
        # positively confirmed gone; `None` (missing A5 evidence) or `True`
        # (still registered) both fail to corroborate the reversal.
        if subject_reading.admin_down_pnote_present is not False:
            return None
        if subject_reading.role == _ROLE_STANDBY:
            return _POSTCONDITION_SUBJECT_STANDBY
        if subject_reading.role == _ROLE_ACTIVE:
            return _POSTCONDITION_SUBJECT_ACTIVE
        return None
