"""OP.2.0 vendor-adapter contract -- the *boundary shape only* (P11, P15).

No concrete implementation exists in this module or anywhere in this
package. The first (Check Point ClusterXL) adapter is ``OP.2.C`` work, gated
on every prerequisite named in the frozen contract; this module exists so
the boundary's shape is fixed and typed before that day, not so that day's
work has anything to inherit from.

Never introduce here: ``execute(command)``, ``execute_shell(...)``,
``api_call(...)`` or any operation-name-from-caller passthrough (P11). Every
field on ``ActionPlan`` is typed and vendor-neutral; none of them is or ever
contains a command string, an argv fragment, an XML payload or an API path
(P18).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Protocol


class PreconditionResult(str, Enum):
    HOLDS = "HOLDS"
    CHANGED = "CHANGED"
    UNKNOWN = "UNKNOWN"


class SubmissionOutcomeFamily(str, Enum):
    NOT_SENT = "NOT_SENT"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class Capability:
    entity_kind: str
    action_type: str
    capability_id: str
    supported: bool
    reason: str | None = None


@dataclass(frozen=True)
class ActionPlan:
    """No command string, no argv, no XML, no API path (P18)."""

    action_type: str
    intended_postcondition: str
    subject_member_token: str
    impact_disclosure: str
    reversal_note: str
    #: Per-capability, per-vendor fact; ``None``/``"UNKNOWN"`` until real
    #: environment evidence establishes it (§"Post-action verification").
    #: No numeric timer is ever invented here.
    settle_observation: str | None = None
    material_action_parameters: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class Observation:
    postcondition_observed: str | None
    coherent: bool
    both_members_observed: bool
    read_failed: bool
    mode_supported: bool


class VendorCapabilityAdapter(Protocol):
    """Four typed operations plus one precondition check, per P11/P16.

    ``check_precondition`` and ``observe_postcondition`` are class 0 only --
    they use the already-approved read battery, never a mutation primitive.
    """

    def capability(self, *, entity_kind: str, action_type: str, evidence: Any) -> Capability:
        ...

    def build_plan(self, *, entity: Any, action_type: str, evidence: Any) -> ActionPlan:
        ...

    def check_precondition(self, *, plan: ActionPlan) -> PreconditionResult:
        ...

    def execute_once(self, *, plan: ActionPlan, action_id: str) -> SubmissionOutcomeFamily:
        ...

    def observe_postcondition(self, *, entity: Any, plan: ActionPlan) -> Observation:
        ...
