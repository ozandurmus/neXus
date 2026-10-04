"""OP.2.0 preflight/eligibility injection points (P3, P4, §"Correctness
contract").

Neither this action's own preflight generation nor the eligibility
evaluation is implemented against a live device here -- ``OP.2.A``/``OP.2.B``
are zero-device-I/O movements. ``PreflightProvider`` and
``EligibilityEvaluator`` are typed seams that a future movement (``OP.2.C``)
wires to the real, already-approved class 0 battery and the canonical
``utils.failover.compute_ha_readiness`` projection. No production code
constructs a real implementation of either today; ``ActionCoordinator``
degrades to ``NOT_ELIGIBLE`` when neither is configured, never to a
default-permit (``AGENTS.md`` UNKNOWN / fail-closed law).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol

from .adapter import Capability


@dataclass(frozen=True)
class PreflightSnapshot:
    """P4 -- must be this action's own, same-entity, same-workflow generation.

    Never a rendered report, stored telemetry, a persisted historical
    readiness record, a cached snapshot, another action's preflight, or a
    preflight for a different entity.
    """

    preflight_run_id: str
    action_id: str
    operational_entity_id: str
    coherent: bool
    readiness_verdict: str
    check_statuses: dict[str, Any] = field(default_factory=dict)
    pair_identity_state: str | None = None


class PreflightProvider(Protocol):
    def run_preflight(self, *, action_id: str, operational_entity_id: str) -> PreflightSnapshot:
        ...


@dataclass(frozen=True)
class EligibilityResult:
    eligible: bool
    reason_codes: tuple[str, ...] = ()


class EligibilityEvaluator(Protocol):
    """§"Correctness contract" -- never re-derives, re-weights or overrides
    a readiness check; a disagreement between readiness and eligibility is a
    defect in this layer, not a tie to break."""

    def evaluate(
        self, *, snapshot: PreflightSnapshot, capability: Capability | None
    ) -> EligibilityResult:
        ...
