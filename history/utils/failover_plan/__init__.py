"""SecurityExpert — OP.1 failover plan compiler and dry-run (class 0).

Contract: `docs/history/phase/OP_1_FAILOVER_PLAN_COMPILER_AND_DRY_RUN.md`.
Compiles what a controlled Check Point ClusterXL failover *would* look
like from evidence the platform already holds. Runs no command, contacts
no device, constructs nothing that could execute one -- `compile_failover_
plan` is proven zero-I/O (AC-1) and this package is a one-way, read-only
consumer of `utils.operate.adapter`/`utils.operate.eligibility` types and
`checkpoint.clusterxl_capability_adapter`; nothing under `utils/operate/`
or `checkpoint/clusterxl_capability_adapter.py` may import it back (AC-7).

Deliberately **not** `utils/failover/` or `utils/operate/` -- both of those
packages have test-enforced exact module allowlists that exist precisely
to keep a plan/executor out.
"""
from __future__ import annotations

from .compiler import compile_failover_plan
from .dry_run import evaluate_dry_run
from .model import (
    DryRunReport,
    FailoverPlan,
    PlanStep,
    PreconditionBinding,
    PreconditionCheckResult,
    ReversalStep,
)

__all__ = [
    "compile_failover_plan",
    "evaluate_dry_run",
    "DryRunReport",
    "FailoverPlan",
    "PlanStep",
    "PreconditionBinding",
    "PreconditionCheckResult",
    "ReversalStep",
]
