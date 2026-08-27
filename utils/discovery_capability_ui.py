"""Discovery lifecycle / capability / coordinator UI payload — 0.6.1C Phase 3.

Builds a sanitized, additive UI payload from the pure Phase 1/2 data
structures (LifecycleStore, CapabilityStore, CollectionCoordinator,
SchedulerPolicy).  This module performs no I/O and no device access; it is
a projection over already-collected, already-sanitized in-memory state.

Privacy contract
-----------------
* ``canonical_id`` values are the same opaque, non-secret device handles
  already used elsewhere in the Inventory/Configuration UI payloads.
* No credentials, raw configuration or transport transcripts are read or
  emitted here — the underlying stores never contain them.
* Coordinator job rows use ``Job.to_manifest_dict()`` which intentionally
  omits ``canonical_ids`` to avoid duplicating device identity data in
  job/audit views.

Until 0.6.1C Phase 4 wires real collectors through the coordinator, callers
may build this payload with empty stores; the UI renders an explicit
"no data yet" fleet summary rather than fabricating collection results.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from utils.capability_registry import (
    CapabilityProfile,
    CapabilityStore,
    plan_collection,
)
from utils.discovery_lifecycle import LifecycleStore
from utils.collection_executor import (
    CollectionCoordinator,
    DEFAULT_CONCURRENCY_BUDGETS,
    SchedulerPolicy,
)


DISCOVERY_UI_SCHEMA_VERSION = "0.6.1C"

LIFECYCLE_STATE_LABELS = {
    "DISCOVERED": "Discovered",
    "VALIDATED": "Validated",
    "STABLE": "Stable",
    "EXCLUDED": "Excluded",
    "REMOVED": "Removed",
}

COLLECTION_MODE_LABELS = {
    "expert_explicit_clish": "Expert + explicit Clish",
    "direct_clish_capable": "Direct Clish (capability only)",
    "vsx_vsenv": "VSX vsenv context",
    "pan_api": "Palo Alto API",
    "deferred_standby": "Deferred — standby member",
    "deferred_lifecycle": "Deferred — lifecycle state",
    "unknown": "Unknown",
}

JOB_STATUS_LABELS = {
    "pending": "Pending",
    "running": "Running",
    "completed": "Completed",
    "failed": "Failed",
    "cancelled": "Cancelled",
    "coalesced": "Coalesced",
}


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _entity_row(record: Any, profile: CapabilityProfile) -> dict[str, Any]:
    plan = plan_collection(profile, record.state)
    return {
        "vendor": record.vendor,
        "canonical_id": record.canonical_id,
        "lifecycle_state": record.state.value,
        "confidence": record.confidence,
        "evidence_plane": record.evidence_plane,
        "last_observed": record.last_observed,
        "transition_reason": record.transition_reason,
        "shell_type": profile.shell_type.value,
        "capability_confidence": profile.confidence,
        "standby_member": profile.standby_member,
        "planned_mode": plan.mode.value,
        "plan_allowed": plan.allowed,
        "plan_reason_code": plan.reason_code,
        "plan_notes": list(plan.notes),
    }


def _coordinator_section(coordinator: CollectionCoordinator | None) -> dict[str, Any]:
    if coordinator is None:
        return {
            "available": False,
            "active_job_count": 0,
            "budgets": {},
            "recent_jobs": [],
        }
    active = coordinator.active_jobs()
    recent = sorted(
        coordinator.all_jobs(),
        key=lambda j: j.created_at,
        reverse=True,
    )[:25]
    return {
        "available": True,
        "active_job_count": len(active),
        "budgets": coordinator.budget_snapshot(),
        "recent_jobs": [job.to_manifest_dict() for job in recent],
    }


def _scheduler_section(policy: SchedulerPolicy | None) -> dict[str, Any]:
    if policy is None:
        return {
            "configured": False,
            "enabled": False,
            "workflow_count": 0,
            "workflows": [],
        }
    return {
        "configured": True,
        "enabled": policy.enabled,
        "workflow_count": len(policy.workflows),
        "workflows": [
            {"workflow": w.workflow, "interval_minutes": w.interval_minutes}
            for w in policy.workflows
        ],
    }


def build_discovery_capability_payload(
    lifecycle_store: LifecycleStore | None = None,
    capability_store: CapabilityStore | None = None,
    coordinator: CollectionCoordinator | None = None,
    scheduler_policy: SchedulerPolicy | None = None,
) -> dict[str, Any]:
    """Build the sanitized Discovery/Capability/Coordinator UI payload.

    All arguments are optional; omitting them yields an explicit empty-state
    payload rather than raising, so the UI module can render safely before
    Phase 4 wires real collectors through the coordinator.
    """
    lifecycle_store = lifecycle_store or LifecycleStore()
    capability_store = capability_store or CapabilityStore()

    entities: list[dict[str, Any]] = []
    lifecycle_state_counts: dict[str, int] = {}
    vendor_counts: dict[str, int] = {}
    deferred_count = 0

    for record in lifecycle_store.all_records():
        lifecycle_state_counts[record.state.value] = (
            lifecycle_state_counts.get(record.state.value, 0) + 1
        )
        vendor_counts[record.vendor] = vendor_counts.get(record.vendor, 0) + 1

        profile = capability_store.get(record.vendor, record.canonical_id)
        if profile is None:
            profile = CapabilityProfile(vendor=record.vendor, canonical_id=record.canonical_id)

        row = _entity_row(record, profile)
        if not row["plan_allowed"]:
            deferred_count += 1
        entities.append(row)

    entities.sort(key=lambda row: (row["vendor"], row["canonical_id"]))

    return {
        "schema_version": DISCOVERY_UI_SCHEMA_VERSION,
        "generated_at": _utc_now(),
        "fleet_summary": {
            "total_entities": len(entities),
            "deferred_count": deferred_count,
            "lifecycle_state_counts": lifecycle_state_counts,
            "vendor_counts": vendor_counts,
        },
        "entities": entities,
        "coordinator": _coordinator_section(coordinator),
        "scheduler": _scheduler_section(scheduler_policy),
        "default_concurrency_budgets": dict(DEFAULT_CONCURRENCY_BUDGETS),
        "lifecycle_state_labels": LIFECYCLE_STATE_LABELS,
        "collection_mode_labels": COLLECTION_MODE_LABELS,
        "job_status_labels": JOB_STATUS_LABELS,
    }
