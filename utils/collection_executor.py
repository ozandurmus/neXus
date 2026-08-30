"""Collection execution coordinator and limited scheduler — 0.6.1C.

The coordinator is the single admission boundary for all collection
requests.  Every collection path (CP MDS, direct SSH, VSX, CP config,
PAN runtime, PAN config) must be admitted through the coordinator before
opening a device session.

Safety contracts
----------------
* Each physical endpoint (``canonical_id``) may have at most one active
  collection job at a time.  VSX VSID and PAN VSYS are planning/context
  dimensions, not separate lock keys.
* Vendor/context concurrency budgets are fixed and conservative; they
  cannot be increased without explicit real-environment evidence.
* Lock conflicts are coalesced onto the active job; no second device
  connection is opened.
* Job provenance is ``manual`` or ``scheduled``.  ``event`` is a reserved
  schema value only; no webhook/event trigger is implemented here.
* Job metadata written to the RunContext manifest must not contain
  secrets, raw target addresses or transport transcripts.
* The scheduler is disabled by default (no RuntimeRoot policy = no jobs).
  Malformed policy fails before any network access.

Coordinator persistence
-----------------------
This implementation is single-process in-memory.  Distributed locking,
durable queue and multi-node HA scheduling are explicitly deferred to
DEPLOY.1.
"""
from __future__ import annotations

import json
import threading
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Any, Callable, TypeVar

from utils.capability_registry import CapabilityStore
from utils.discovery_lifecycle import LifecycleStore


# ---------------------------------------------------------------------------
# Enumerations
# ---------------------------------------------------------------------------

class Provenance(str, Enum):
    MANUAL    = "manual"
    SCHEDULED = "scheduled"
    # "event" is a reserved schema value; no trigger implemented in 0.6.1C.


class CoordinatorDecision(str, Enum):
    ADMITTED         = "admitted"
    COALESCED        = "coalesced"
    REJECTED_BUDGET  = "rejected_budget"
    REJECTED_LOCKED  = "rejected_locked"


class JobStatus(str, Enum):
    PENDING   = "pending"
    RUNNING   = "running"
    COMPLETED = "completed"
    FAILED    = "failed"
    CANCELLED = "cancelled"
    COALESCED = "coalesced"  # request merged into an existing job


class CollectionAdmissionError(RuntimeError):
    """Raised when a collection request must not open a transport session."""

    def __init__(self, decision: CoordinatorDecision, job: "Job") -> None:
        super().__init__(f"collection admission did not execute: {decision.value}")
        self.decision = decision
        self.job = job


# ---------------------------------------------------------------------------
# Job record
# ---------------------------------------------------------------------------

@dataclass
class Job:
    """Mutable lifecycle record for one collection request."""
    job_id: str
    vendor: str
    workflow_scope: str      # e.g. "checkpoint", "pan-config"
    provenance: str          # Provenance enum value
    canonical_ids: list[str] = field(default_factory=list)
    status: str = JobStatus.PENDING.value
    created_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    admitted_at: str | None = None
    completed_at: str | None = None
    coalesced_to: str | None = None   # job_id of the job this was merged into
    reason: str | None = None          # safe reason code; no secrets
    cancel_event: threading.Event = field(default_factory=threading.Event, repr=False, compare=False)
    completion_event: threading.Event = field(default_factory=threading.Event, repr=False, compare=False)

    def to_manifest_dict(self) -> dict[str, Any]:
        """Return a secrets-free dict suitable for RunContext manifest."""
        return {
            "job_id": self.job_id,
            "vendor": self.vendor,
            "workflow_scope": self.workflow_scope,
            "provenance": self.provenance,
            "status": self.status,
            "created_at": self.created_at,
            "admitted_at": self.admitted_at,
            "completed_at": self.completed_at,
            "coalesced_to": self.coalesced_to,
            "reason": self.reason,
            # canonical_ids intentionally omitted — may contain device names.
        }


# ---------------------------------------------------------------------------
# Concurrency budget
# ---------------------------------------------------------------------------

# Conservative fixed budgets per vendor/context.
# Increasing these requires explicit real-environment evidence (see phase doc).
_DEFAULT_BUDGETS: dict[str, int] = {
    "checkpoint":     1,
    "checkpoint_vsx": 1,
    "paloalto":       1,
    "_default":       1,
}

# Public read-only alias for UI/observability consumers (e.g. discovery_capability_ui).
DEFAULT_CONCURRENCY_BUDGETS: dict[str, int] = dict(_DEFAULT_BUDGETS)


def _vendor_budget_key(vendor: str, workflow_scope: str) -> str:
    v = vendor.strip().lower()
    w = workflow_scope.strip().lower()
    if v == "checkpoint" and "vsx" in w:
        return "checkpoint_vsx"
    if v in {"checkpoint", "cp"}:
        return "checkpoint"
    if v in {"paloalto", "pan"}:
        return "paloalto"
    return "_default"


# ---------------------------------------------------------------------------
# Coordinator
# ---------------------------------------------------------------------------

class CollectionCoordinator:
    """Thread-safe admission coordinator for collection jobs.

    One instance should be shared across the process lifetime.  It is not
    safe to use across multiple OS processes (single-process scope only).
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        # Per-physical-endpoint locks (canonical_id → Lock).
        self._endpoint_locks: dict[str, threading.Lock] = {}
        # Per-vendor semaphores for concurrency budget.
        self._budgets: dict[str, threading.Semaphore] = {
            key: threading.Semaphore(val)
            for key, val in _DEFAULT_BUDGETS.items()
        }
        # Active and recently completed jobs.
        self._jobs: dict[str, Job] = {}
        # Map of canonical_id → active job_id (for coalescing).
        self._active_for: dict[str, str] = {}

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def admit(
        self,
        vendor: str,
        workflow_scope: str,
        canonical_ids: list[str],
        provenance: str = Provenance.MANUAL.value,
    ) -> tuple[CoordinatorDecision, Job]:
        """Request admission for a collection job.

        Returns (decision, job).  On COALESCED the returned job is the
        *existing* active job; the caller must not open a second session.
        On REJECTED_* the returned job has status FAILED.
        """
        decision, request_job, active_job = self.admit_request(
            vendor,
            workflow_scope,
            canonical_ids,
            provenance=provenance,
        )
        if decision == CoordinatorDecision.COALESCED and active_job is not None:
            return decision, active_job
        return decision, request_job

    def admit_request(
        self,
        vendor: str,
        workflow_scope: str,
        canonical_ids: list[str],
        provenance: str = Provenance.MANUAL.value,
    ) -> tuple[CoordinatorDecision, Job, Job | None]:
        """Return the request job as well as an optional active coalesce target.

        ``admit()`` retains its legacy return contract. Runtime orchestration
        uses this detailed form so a coalesced request can write its own safe
        job id and ``coalesced_to`` relationship to a manifest.
        """
        job_id = f"job_{uuid.uuid4().hex[:12]}"
        now = datetime.now(timezone.utc).isoformat()
        job = Job(
            job_id=job_id,
            vendor=vendor,
            workflow_scope=workflow_scope,
            provenance=provenance,
            canonical_ids=list(canonical_ids),
            created_at=now,
        )

        budget_key = _vendor_budget_key(vendor, workflow_scope)

        with self._lock:
            # --- Coalesce check ----------------------------------------
            for cid in canonical_ids:
                existing_id = self._active_for.get(cid)
                if existing_id and existing_id in self._jobs:
                    existing = self._jobs[existing_id]
                    if existing.status == JobStatus.RUNNING.value:
                        job.status = JobStatus.COALESCED.value
                        job.coalesced_to = existing_id
                        job.reason = "coalesced_into_active_job"
                        job.completed_at = now
                        job.completion_event.set()
                        self._jobs[job_id] = job
                        return CoordinatorDecision.COALESCED, job, existing

            # --- Concurrency budget check (non-blocking tryacquire) -----
            sem = self._budgets.get(budget_key) or self._budgets["_default"]
            if not sem.acquire(blocking=False):
                job.status = JobStatus.FAILED.value
                job.reason = "concurrency_budget_exhausted"
                job.completed_at = now
                job.completion_event.set()
                self._jobs[job_id] = job
                return CoordinatorDecision.REJECTED_BUDGET, job, None

            # --- Endpoint lock check ------------------------------------
            conflicting = [
                cid for cid in canonical_ids
                if cid in self._active_for
            ]
            if conflicting:
                # Release the semaphore we just took since we cannot proceed.
                sem.release()
                job.status = JobStatus.FAILED.value
                job.reason = "endpoint_lock_conflict"
                job.completed_at = now
                job.completion_event.set()
                self._jobs[job_id] = job
                return CoordinatorDecision.REJECTED_LOCKED, job, None

            # --- Admit --------------------------------------------------
            job.status = JobStatus.RUNNING.value
            job.admitted_at = now
            for cid in canonical_ids:
                self._active_for[cid] = job_id
            # Attach the budget semaphore so release() can return it.
            job.reason = "admitted"
            self._jobs[job_id] = job
            # Store semaphore ref for release (using a hidden attribute).
            object.__setattr__(job, "_budget_sem", sem)  # type: ignore[arg-type]
            return CoordinatorDecision.ADMITTED, job, None

    def release(self, job_id: str) -> None:
        """Mark a job as completed and free its resources."""
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return
            if job.status not in (JobStatus.RUNNING.value, JobStatus.PENDING.value):
                return
            job.status = JobStatus.COMPLETED.value
            job.completed_at = datetime.now(timezone.utc).isoformat()
            job.completion_event.set()
            for cid in job.canonical_ids:
                self._active_for.pop(cid, None)
            sem = getattr(job, "_budget_sem", None)
            if sem is not None:
                sem.release()

    def fail(self, job_id: str, reason: str) -> None:
        """Mark a job as failed and free its resources."""
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return
            job.status = JobStatus.FAILED.value
            job.reason = reason
            job.completed_at = datetime.now(timezone.utc).isoformat()
            job.completion_event.set()
            for cid in job.canonical_ids:
                self._active_for.pop(cid, None)
            sem = getattr(job, "_budget_sem", None)
            if sem is not None:
                sem.release()

    def cancel(self, job_id: str) -> bool:
        """Request cancellation of a running job.

        Sets the job's cancel_event and marks it CANCELLED; returns True if
        the job was found and running, False otherwise.
        """
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None or job.status != JobStatus.RUNNING.value:
                return False
            job.cancel_event.set()
            job.status = JobStatus.CANCELLED.value
            job.completed_at = datetime.now(timezone.utc).isoformat()
            job.reason = "cancelled_by_request"
            job.completion_event.set()
            for cid in job.canonical_ids:
                self._active_for.pop(cid, None)
            sem = getattr(job, "_budget_sem", None)
            if sem is not None:
                sem.release()
            return True

    def get_job(self, job_id: str) -> Job | None:
        with self._lock:
            return self._jobs.get(job_id)

    def wait_for_terminal(self, job_id: str, timeout: float) -> Job | None:
        """Wait boundedly for an active job without holding the coordinator lock."""
        job = self.get_job(job_id)
        if job is None:
            return None
        job.completion_event.wait(timeout=max(0.0, float(timeout)))
        return self.get_job(job_id)

    def active_jobs(self) -> list[Job]:
        with self._lock:
            return [j for j in self._jobs.values() if j.status == JobStatus.RUNNING.value]

    def all_jobs(self) -> list[Job]:
        with self._lock:
            return list(self._jobs.values())

    def budget_snapshot(self) -> dict[str, dict[str, int]]:
        """Return a safe, read-only snapshot of concurrency budgets.

        Reports configured capacity and currently available permits per
        vendor/context key. Contains no device identities or secrets.
        """
        with self._lock:
            snapshot: dict[str, dict[str, int]] = {}
            for key, sem in self._budgets.items():
                capacity = DEFAULT_CONCURRENCY_BUDGETS.get(key, 0)
                # threading.Semaphore does not expose current value publicly;
                # approximate availability via a non-blocking acquire/release probe.
                available = 0
                if sem.acquire(blocking=False):
                    available = 1
                    sem.release()
                snapshot[key] = {"capacity": capacity, "available": available}
            return snapshot


# ---------------------------------------------------------------------------
# Scheduler policy
# ---------------------------------------------------------------------------

SCHEDULER_POLICY_PATH = Path("state") / "scheduler_policy.json"
SCHEDULER_POLICY_SCHEMA_VERSION = 1
ALLOWLISTED_WORKFLOWS: frozenset[str] = frozenset({
    "checkpoint", "cp", "vsx", "pan-config", "cp-config",
    # RB.2 (docs/design/BACKUP_RECOVERY_CONTRACTS.md §10.4): PAN device-state
    # recovery collection. "recovery-cp" deliberately does NOT join this set
    # yet -- CP Gaia backup collection is blocked (P0 audit + open decision
    # D3); scheduling a blocked collector must fail at policy-load time, the
    # same fail-closed posture this allowlist already gives any unknown name.
    "recovery-pan",
})
_MIN_INTERVAL_MINUTES = 10


class SchedulerPolicyError(ValueError):
    """Raised when the scheduler policy file is present but not safe to use."""


@dataclass(frozen=True)
class ScheduledWorkflow:
    workflow: str
    interval_minutes: int
    # Additive (contract §10.4): omitted/empty means "all admitted devices
    # of this workflow's vendor" -- every policy file written before this
    # field existed keeps its exact prior meaning. Only meaningful for
    # recovery-* workflows today; ignored by the existing checkpoint/cp/vsx/
    # pan-config workflows, which have never taken a target list.
    targets: tuple[str, ...] = ()


@dataclass(frozen=True)
class SchedulerPolicy:
    source: str
    enabled: bool
    workflows: tuple[ScheduledWorkflow, ...]


@dataclass
class RuntimeCollectionServices:
    """Process-lifetime 0.6.1C services shared by manual and scheduled runs."""

    coordinator: CollectionCoordinator = field(default_factory=CollectionCoordinator)
    lifecycle_store: LifecycleStore = field(default_factory=LifecycleStore)
    capability_store: CapabilityStore = field(default_factory=CapabilityStore)
    scheduler_policy: SchedulerPolicy | None = None


def load_scheduler_policy(data_root: Path) -> SchedulerPolicy | None:
    """Load and validate the RuntimeRoot scheduler policy.

    Returns None when no policy file exists (default disabled; no jobs).
    Raises SchedulerPolicyError for any present-but-invalid policy.
    No network access is made.
    """
    path = Path(data_root) / SCHEDULER_POLICY_PATH
    if not path.exists():
        return None

    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SchedulerPolicyError("scheduler policy cannot be read safely") from exc

    if not isinstance(raw, dict):
        raise SchedulerPolicyError("scheduler policy must be a JSON object")

    if raw.get("version") != SCHEDULER_POLICY_SCHEMA_VERSION:
        raise SchedulerPolicyError(
            f"scheduler policy has unsupported schema version "
            f"(expected {SCHEDULER_POLICY_SCHEMA_VERSION})"
        )

    enabled = raw.get("enabled", False)
    if not isinstance(enabled, bool):
        raise SchedulerPolicyError("scheduler policy 'enabled' must be a boolean")

    jobs_raw = raw.get("schedule", [])
    if not isinstance(jobs_raw, list):
        raise SchedulerPolicyError("scheduler policy 'schedule' must be a list")

    workflows: list[ScheduledWorkflow] = []
    seen_workflows: set[str] = set()
    for entry in jobs_raw:
        if not isinstance(entry, dict):
            raise SchedulerPolicyError("scheduler policy schedule entry must be an object")
        workflow = str(entry.get("workflow", "")).strip().lower()
        if not workflow:
            raise SchedulerPolicyError("scheduler policy schedule entry missing 'workflow'")
        if workflow not in ALLOWLISTED_WORKFLOWS:
            raise SchedulerPolicyError(
                f"scheduler policy references a non-allowlisted workflow: {workflow!r}. "
                f"Allowed: {sorted(ALLOWLISTED_WORKFLOWS)}"
            )
        if workflow in seen_workflows:
            raise SchedulerPolicyError(
                f"scheduler policy contains duplicate workflow: {workflow!r}"
            )
        try:
            interval = int(entry["interval_minutes"])
        except (KeyError, TypeError, ValueError) as exc:
            raise SchedulerPolicyError(
                "scheduler policy schedule entry 'interval_minutes' must be an integer"
            ) from exc
        if interval < _MIN_INTERVAL_MINUTES:
            raise SchedulerPolicyError(
                f"scheduler policy interval_minutes must be >= {_MIN_INTERVAL_MINUTES}, "
                f"got {interval}"
            )

        targets_raw = entry.get("targets")
        if targets_raw is None:
            targets: tuple[str, ...] = ()
        elif isinstance(targets_raw, list) and all(isinstance(t, str) and t.strip() for t in targets_raw):
            targets = tuple(targets_raw)
        else:
            raise SchedulerPolicyError(
                "scheduler policy schedule entry 'targets', if present, must be a list of non-empty strings"
            )

        workflows.append(ScheduledWorkflow(workflow=workflow, interval_minutes=interval, targets=targets))
        seen_workflows.add(workflow)

    return SchedulerPolicy(
        source=str(path),
        enabled=enabled,
        workflows=tuple(workflows),
    )


def is_workflow_due(
    scheduled: ScheduledWorkflow,
    last_run_at: datetime | None,
    now: datetime | None = None,
) -> bool:
    """Return True if a scheduled workflow is due for execution.

    ``last_run_at`` is the datetime of the most recent completed run, or None
    if the workflow has never run.  ``now`` defaults to UTC now.
    """
    if now is None:
        now = datetime.now(timezone.utc)
    if last_run_at is None:
        return True
    # Ensure timezone-aware comparison.
    if last_run_at.tzinfo is None:
        last_run_at = last_run_at.replace(tzinfo=timezone.utc)
    elapsed = (now - last_run_at).total_seconds() / 60.0
    return elapsed >= scheduled.interval_minutes


SCHEDULER_STATE_PATH = Path("state") / "scheduler_state.json"
_ResultT = TypeVar("_ResultT")


def load_scheduler_state(data_root: Path) -> dict[str, datetime]:
    """Load value-free last-success timestamps; malformed state fails closed."""
    path = Path(data_root) / SCHEDULER_STATE_PATH
    if not path.exists():
        return {}
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SchedulerPolicyError("scheduler state cannot be read safely") from exc
    if not isinstance(raw, dict) or raw.get("version") != 1:
        raise SchedulerPolicyError("scheduler state has unsupported schema")
    rows = raw.get("last_completed_at", {})
    if not isinstance(rows, dict):
        raise SchedulerPolicyError("scheduler state last_completed_at must be an object")
    result: dict[str, datetime] = {}
    for workflow, value in rows.items():
        if workflow not in ALLOWLISTED_WORKFLOWS or not isinstance(value, str):
            raise SchedulerPolicyError("scheduler state contains an unsupported workflow or timestamp")
        try:
            parsed = datetime.fromisoformat(value)
        except ValueError as exc:
            raise SchedulerPolicyError("scheduler state contains an invalid timestamp") from exc
        result[workflow] = parsed if parsed.tzinfo is not None else parsed.replace(tzinfo=timezone.utc)
    return result


def write_scheduler_state(data_root: Path, state: dict[str, datetime]) -> None:
    """Atomically persist value-free completion timestamps under RuntimeRoot."""
    path = Path(data_root) / SCHEDULER_STATE_PATH
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "version": 1,
        "last_completed_at": {
            workflow: value.astimezone(timezone.utc).isoformat()
            for workflow, value in sorted(state.items())
        },
    }
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    tmp.replace(path)


def execute_admitted_collection(
    services: RuntimeCollectionServices,
    *,
    vendor: str,
    workflow_scope: str,
    canonical_ids: list[str],
    provenance: str,
    operation: Callable[[], _ResultT],
    run_context: Any | None = None,
) -> _ResultT:
    """Execute one unchanged collector only after process-local admission.

    Canonical ids remain in memory and are omitted from manifests/job views.
    Any non-admitted decision raises before ``operation`` is invoked.
    """
    decision, request_job, _active_job = services.coordinator.admit_request(
        vendor,
        workflow_scope,
        canonical_ids,
        provenance=provenance,
    )
    if run_context is not None:
        run_context.set_job_metadata(
            request_job.job_id,
            provenance,
            coordinator_decision=decision.value,
            coalesced_to=request_job.coalesced_to,
            effective_scope=workflow_scope,
        )
    if decision != CoordinatorDecision.ADMITTED:
        raise CollectionAdmissionError(decision, request_job)
    try:
        result = operation()
    except BaseException as exc:
        services.coordinator.fail(request_job.job_id, f"collector_{type(exc).__name__.lower()}")
        raise
    services.coordinator.release(request_job.job_id)
    return result
