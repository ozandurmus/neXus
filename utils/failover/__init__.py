"""SecurityExpert — failover plane (OP.x).

Contract: docs/history/phase/OP_0A_HA_READINESS_ASSESSMENT.md (`assessment`).
docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md
(`preflight_model`, status FROZEN WITH REAL-ENV VALIDATION GATES, slice S1).
Architecture: docs/design/FAILOVER_ENGINE_ARCHITECTURE.md.

This package's actual, durable boundary (contract decision P5) is that it
contains **no write-capable surface** — the architecture doc's §7 `plan`,
`executor`, `verification`, `audit` and vendor `adapters/` may not appear
here until their own gates are cleared. `utils/cleanup.py` was deleted by
the `remove_dormant_remote_cleanup` build precisely because dormant
write-capable code is a standing liability even when nothing references it —
creating an `executor.py` stub ahead of the OP.2 gate would repeat that
mistake with a far more dangerous primitive. `preflight_model` (OP.0b S1) is
a pure, zero-I/O evidence/provenance domain model — no command, no network,
no verdict, no CLASS 2 authorization — and does not touch that boundary; its
presence here is exactly what the frozen OP.0b.0 contract's own slice table
names (`utils/failover/preflight_model.py (new)`).

`preflight_readiness` (OP.0b S7) is the one typed fact→check mapping that
interprets a `PreflightSnapshot` into the seven canonical checks; it is pure
and zero-I/O like `preflight_model`, computes check statuses only, and feeds
the single verdict roll-up in `assessment._verdict_for` — one readiness
authority, not two.

`tests/test_op0a_ha_readiness.py` asserts this package exposes no
executor/plan/action/rollback symbol, so the absence is enforced rather than
merely current; its companion structural test now allows exactly
`{__init__.py, assessment.py, preflight_model.py, preflight_readiness.py}`,
updated in the same build that added the fourth file.
"""
from __future__ import annotations

from .assessment import (  # noqa: F401
    SCHEMA,
    EVIDENCE_BASIS_STORED_TELEMETRY,
    EVIDENCE_BASIS_PREFLIGHT_SNAPSHOT,
    UNRESOLVED_POLICY_DECISIONS,
    VERDICT_SAFE,
    VERDICT_DEGRADED,
    VERDICT_UNSAFE,
    VERDICT_INSUFFICIENT,
    VERDICT_NOT_A_FAILOVER_UNIT,
    CHECK_PASS,
    CHECK_FAIL,
    CHECK_INSUFFICIENT,
    STOP_CONDITIONS,
    OP0A_EVALUABLE_CHECKS,
    HaUnit,
    UnitAssessment,
    compute_ha_readiness,
)
from .preflight_model import (  # noqa: F401
    FactCategory,
    RUNTIME_COHERENCE_CATEGORIES,
    SourceOrigin,
    Transport,
    ShellProfile,
    Outcome,
    FactState,
    ContextKind,
    FactContext,
    OpaqueToken,
    Provenance,
    PreflightFact,
    PreflightMemberEvidence,
    PreflightSnapshot,
    CoherenceResult,
    evaluate_coherence,
)
from .preflight_readiness import (  # noqa: F401
    FACT_CHECK_MAP,
    CheckEvidenceSpec,
    FactRule,
    SnapshotEvaluation,
    evaluate_snapshot_checks,
)

__all__ = [
    "SCHEMA",
    "EVIDENCE_BASIS_STORED_TELEMETRY",
    "EVIDENCE_BASIS_PREFLIGHT_SNAPSHOT",
    "UNRESOLVED_POLICY_DECISIONS",
    "VERDICT_SAFE",
    "VERDICT_DEGRADED",
    "VERDICT_UNSAFE",
    "VERDICT_INSUFFICIENT",
    "VERDICT_NOT_A_FAILOVER_UNIT",
    "CHECK_PASS",
    "CHECK_FAIL",
    "CHECK_INSUFFICIENT",
    "STOP_CONDITIONS",
    "OP0A_EVALUABLE_CHECKS",
    "HaUnit",
    "UnitAssessment",
    "compute_ha_readiness",
    "FactCategory",
    "RUNTIME_COHERENCE_CATEGORIES",
    "SourceOrigin",
    "Transport",
    "ShellProfile",
    "Outcome",
    "FactState",
    "ContextKind",
    "FactContext",
    "OpaqueToken",
    "Provenance",
    "PreflightFact",
    "PreflightMemberEvidence",
    "PreflightSnapshot",
    "CoherenceResult",
    "evaluate_coherence",
    "FACT_CHECK_MAP",
    "CheckEvidenceSpec",
    "FactRule",
    "SnapshotEvaluation",
    "evaluate_snapshot_checks",
]
