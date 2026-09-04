"""OP.2.0 confirmation approval-policy boundary (P5).

One ``approval_policy(action) -> ApprovalRequirement`` boundary decides how
many confirmations, from whom, are required. Production/release inputs
(second approver, role combinations, a maintenance window, a change-ticket
reference) are deployment/release policy, not architecture (``op_four_eyes``,
PO 2026-09-04) -- this module builds no generic quorum framework. The
initial implementation is "one confirmation by the requesting operator",
with no configuration surface.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from .record import ActionRecord


@dataclass(frozen=True)
class ApprovalRequirement:
    required_confirmations: int = 1


class ApprovalPolicy(Protocol):
    def approval_policy(self, action: ActionRecord) -> ApprovalRequirement:
        ...


class SingleOperatorApprovalPolicy:
    """The only policy implementation until a release policy decision exists."""

    def approval_policy(self, action: ActionRecord) -> ApprovalRequirement:
        return ApprovalRequirement(required_confirmations=1)
