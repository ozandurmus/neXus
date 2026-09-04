"""OP.2.0 durable action/audit record (``ha_action_record``) -- P13, §"Audit
/ evidence model".

One append-only record per ``action_id``. Every field named here is either
required by that section's table or is P5's confirmation-binding field. No
field for a credential, token, management address, HA/control-link address,
host-key material, raw serial, raw device output, file path outside the
runtime root, stack trace, or **command text** exists -- P18: command text
never exists above the adapter boundary, so there is nothing here to forbid
by filtering; it is forbidden by the type simply having no such field.
"""
from __future__ import annotations

import dataclasses
import hashlib
import json
from dataclasses import dataclass, field, fields
from datetime import datetime, timezone
from typing import Any

from .states import ActionState


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


# Backward-compatible private alias used within this module's own defaults.
_utc_now = utc_now


def compute_proposal_digest(
    *,
    action_id: str,
    action_type: str,
    operational_entity_id: str,
    intended_postcondition: str,
    subject_member_token: str,
    preflight_generation_id: str,
    eligibility_result: dict[str, Any],
    material_action_parameters: dict[str, Any],
) -> str:
    """P5 -- a content digest for confirmation binding, not a signature.

    Truncated to 16 hex characters, the repository's own precedent for a
    binding (not authentication) digest (``group_id`` =
    ``sha256(...)[:16]``).
    """
    payload = {
        "action_id": action_id,
        "action_type": action_type,
        "operational_entity_id": operational_entity_id,
        "intended_postcondition": intended_postcondition,
        "subject_member_token": subject_member_token,
        "preflight_generation_id": preflight_generation_id,
        "eligibility_result": eligibility_result,
        "material_action_parameters": material_action_parameters,
    }
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]


@dataclass
class ActionRecord:
    action_id: str
    actor_ref: str
    action_type: str
    operational_entity_id: str
    entity_kind: str
    vendor: str
    operator_reason: str
    state: ActionState = ActionState.CREATED
    created_at: str = field(default_factory=_utc_now)
    finished_at: str | None = None
    terminal_reason: str | None = None

    # Bound by proposal_digest (P5) -- immutable once the proposal exists.
    intended_postcondition: str | None = None
    subject_member_token: str | None = None
    material_action_parameters: dict[str, Any] = field(default_factory=dict)
    #: Adapter-declared capability fact, not a digest-bound field (P5 lists
    #: exactly eight bound fields and this is not one of them) -- carried so
    #: the plan can be reconstructed at `confirm()` time for
    #: `check_precondition`/`execute_once`/`observe_postcondition` without a
    #: second, non-durable cache. `None`/`"UNKNOWN"` until real-environment
    #: evidence establishes it; never a number invented here.
    settle_observation: str | None = None

    pre_action_preflight_run_id: str | None = None
    preflight_generation_id: str | None = None
    readiness_verdict: str | None = None
    check_statuses: dict[str, Any] = field(default_factory=dict)
    eligibility_result: dict[str, Any] | None = None
    reason_codes: list[str] = field(default_factory=list)

    proposal_digest: str | None = None
    confirmed_at: str | None = None
    confirmations: list[dict[str, Any]] = field(default_factory=list)
    superseded_proposals: list[dict[str, Any]] = field(default_factory=list)

    admissions: list[dict[str, Any]] = field(default_factory=list)
    precondition_result: str | None = None
    precondition_observed_at: str | None = None

    # P6 -- deliberately two-valued, never UNKNOWN, written before submission.
    mutation_boundary_crossed: str = "NO"
    boundary_committed_at: str | None = None

    capability_id: str | None = None
    adapter_version: str | None = None
    submission_outcome_family: str | None = None

    post_action_preflight_run_id: str | None = None
    observed_postcondition: str | None = None
    continuity_observations: list[dict[str, Any]] = field(default_factory=list)

    reverses_action_id: str | None = None
    acknowledged_at: str | None = None
    acknowledged_by: str | None = None
    post_hoc_observations: list[dict[str, Any]] = field(default_factory=list)

    transitions: list[dict[str, Any]] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        payload = dataclasses.asdict(self)
        payload["state"] = self.state.value if isinstance(self.state, ActionState) else self.state
        return payload

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "ActionRecord":
        data = dict(data)
        data["state"] = ActionState(data["state"])
        known = {f.name for f in fields(cls)}
        filtered = {k: v for k, v in data.items() if k in known}
        return cls(**filtered)
