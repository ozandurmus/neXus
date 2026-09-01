"""SecurityExpert — failover plane (OP.x).

Contract: docs/history/phase/OP_0A_HA_READINESS_ASSESSMENT.md.
Architecture: docs/design/FAILOVER_ENGINE_ARCHITECTURE.md.

This package contains **`assessment` only**, and that is deliberate (contract
decision P5). The architecture doc's §7 lists `plan`, `executor`,
`verification`, `audit` and vendor `adapters/` alongside it; none of them may
appear here until their own gates are cleared. `utils/cleanup.py` was deleted
by the `remove_dormant_remote_cleanup` build precisely because dormant
write-capable code is a standing liability even when nothing references it —
creating an `executor.py` stub ahead of the OP.2 gate would repeat that
mistake with a far more dangerous primitive.

`tests/test_op0a_ha_readiness.py` asserts this package exposes no
executor/plan/action/rollback symbol, so the absence is enforced rather than
merely current.
"""
from __future__ import annotations

from .assessment import (  # noqa: F401
    SCHEMA,
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

__all__ = [
    "SCHEMA",
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
]
