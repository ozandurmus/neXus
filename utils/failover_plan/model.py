"""OP.1 — `FailoverPlan` / `DryRunReport` value objects.

Contract: `docs/history/phase/OP_1_FAILOVER_PLAN_COMPILER_AND_DRY_RUN.md`
§3 (`FailoverPlan`), §5 (`DryRunReport`). No behaviour lives here — every
dataclass is an immutable value object populated by `compiler.py` /
`dry_run.py`, never constructed with computed defaults that could hide a
fabricated field.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class PreconditionBinding:
    """One `utils.failover.assessment.STOP_CONDITIONS` entry, reused
    verbatim (§3.2) — never re-evaluated by this package."""

    id: str
    label: str
    status: str
    reason: str
    missing_evidence: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "label": self.label,
            "status": self.status,
            "reason": self.reason,
            "missing_evidence": self.missing_evidence,
        }


@dataclass(frozen=True)
class PlanStep:
    """The one compiled primitive, `CP-M1` (§3.2)."""

    primitive_id: str
    action_type: str
    subject_member_token: str
    preconditions: tuple[PreconditionBinding, ...]
    intended_postcondition: str
    impact_disclosure: str
    verification_reads: tuple[str, ...]
    settle_observation: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "primitive_id": self.primitive_id,
            "action_type": self.action_type,
            "subject_member_token": self.subject_member_token,
            "preconditions": [p.to_dict() for p in self.preconditions],
            "intended_postcondition": self.intended_postcondition,
            "impact_disclosure": self.impact_disclosure,
            "verification_reads": list(self.verification_reads),
            "settle_observation": self.settle_observation,
        }


@dataclass(frozen=True)
class ReversalStep:
    """`CP-M1-R`, disclosed but never chained (§3.3)."""

    primitive_id: str
    action_type: str
    reverses: str
    intended_postcondition: str
    impact_disclosure: str
    authorization_note: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "primitive_id": self.primitive_id,
            "action_type": self.action_type,
            "reverses": self.reverses,
            "intended_postcondition": self.intended_postcondition,
            "impact_disclosure": self.impact_disclosure,
            "authorization_note": self.authorization_note,
        }


@dataclass(frozen=True)
class FailoverPlan:
    """One CP ClusterXL operational unit's compiled plan (§3)."""

    unit_id: str
    vendor: str
    capability_id: str
    evidence_basis: str
    readiness_verdict: str
    plan_compilable: bool
    compilation_blocked_reason: str | None
    step: PlanStep | None
    reversal: ReversalStep | None
    generated_at: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "unit_id": self.unit_id,
            "vendor": self.vendor,
            "capability_id": self.capability_id,
            "evidence_basis": self.evidence_basis,
            "readiness_verdict": self.readiness_verdict,
            "plan_compilable": self.plan_compilable,
            "compilation_blocked_reason": self.compilation_blocked_reason,
            "step": self.step.to_dict() if self.step is not None else None,
            "reversal": self.reversal.to_dict() if self.reversal is not None else None,
            "generated_at": self.generated_at,
        }


@dataclass(frozen=True)
class PreconditionCheckResult:
    """One `DryRunReport.precondition_results` entry — copied verbatim from
    the plan's own `PreconditionBinding`, never re-evaluated (§5)."""

    id: str
    label: str
    status: str
    reason: str
    missing_evidence: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "label": self.label,
            "status": self.status,
            "reason": self.reason,
            "missing_evidence": self.missing_evidence,
        }


@dataclass(frozen=True)
class DryRunReport:
    """§5 — answers "if this plan's preconditions were evaluated right now
    against evidence we already have, which would hold?". Grants no
    authorization (§6): `authorization_note` is fixed and unconditional."""

    plan: FailoverPlan
    precondition_results: tuple[PreconditionCheckResult, ...]
    readiness_verdict: str
    would_proceed: bool
    authorization_note: str
    generated_at: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "plan": self.plan.to_dict(),
            "precondition_results": [r.to_dict() for r in self.precondition_results],
            "readiness_verdict": self.readiness_verdict,
            "would_proceed": self.would_proceed,
            "authorization_note": self.authorization_note,
            "generated_at": self.generated_at,
        }
