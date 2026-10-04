"""OP.1 — `evaluate_dry_run`, the write-free dry-run report.

Contract: `docs/history/phase/OP_1_FAILOVER_PLAN_COMPILER_AND_DRY_RUN.md` §5,
§6. Pure formatting/aggregation over a `FailoverPlan` already compiled by
`compiler.compile_failover_plan` -- no new evaluation logic, no live read,
no second readiness engine. The seven `PreconditionCheckResult` rows are the
plan's own `PreconditionBinding`s, copied verbatim, never re-evaluated.
"""
from __future__ import annotations

from datetime import datetime, timezone

from utils.failover.assessment import VERDICT_SAFE

from .model import DryRunReport, FailoverPlan, PreconditionCheckResult

__all__ = ["evaluate_dry_run"]

#: Fixed, unconditional (§5, §6) -- present on every `DryRunReport` regardless
#: of verdict, so a reader can never encounter a report that omits it.
AUTHORIZATION_NOTE = (
    "This dry-run grants no authorization. It issues no command and "
    "constructs no ActionCoordinator. Executing this plan requires a "
    "separate, independently authorized OP.2 action once that track's own "
    "prerequisites are met."
)


def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def evaluate_dry_run(plan: FailoverPlan, *, generated_at: str | None = None) -> DryRunReport:
    """`would_proceed` is `True` iff `plan.plan_compilable` and
    `plan.readiness_verdict == VERDICT_SAFE` -- never for any other verdict
    (AC-5), including `INSUFFICIENT_EVIDENCE` and the structurally
    unreachable `DEGRADED_PROCEED_WITH_RISK` (§8 Option A)."""
    precondition_results = (
        tuple(
            PreconditionCheckResult(
                id=binding.id, label=binding.label, status=binding.status,
                reason=binding.reason, missing_evidence=binding.missing_evidence,
            )
            for binding in plan.step.preconditions
        )
        if plan.step is not None
        else ()
    )
    would_proceed = bool(plan.plan_compilable and plan.readiness_verdict == VERDICT_SAFE)

    return DryRunReport(
        plan=plan,
        precondition_results=precondition_results,
        readiness_verdict=plan.readiness_verdict,
        would_proceed=would_proceed,
        authorization_note=AUTHORIZATION_NOTE,
        generated_at=generated_at or _utc_now(),
    )
