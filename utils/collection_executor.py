"""Collection execution coordinator and limited scheduler — 0.6.1C / DEV.3.2.

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
* Job provenance is ``manual``, ``scheduled`` or ``console`` (CON.2).
  ``event`` is a reserved schema value only; no webhook/event trigger is
  implemented here.
* Job metadata written to the RunContext manifest must not contain
  secrets, raw target addresses or transport transcripts.
* The scheduler is disabled by default (no RuntimeRoot policy = no jobs).
  Malformed policy fails before any network access.

Coordinator persistence
-----------------------
``CollectionCoordinator`` delegates every admission decision to a
``utils.coordinator_backend.CoordinatorBackend``. The default
``InMemoryCoordinatorBackend`` is single-process — the same validated 0.6.1C
behavior, byte-for-byte. ``select_coordinator_backend()`` opts into
``PostgresCoordinatorBackend`` (``SECURITYEXPERT_COORDINATOR_BACKEND=postgres``)
for cross-process admission — see
``docs/history/phase/DEV3_2_DISTRIBUTED_ENDPOINT_LOCK.md``. Multi-node HA
scheduling beyond the scheduler-advisory-lock gate below remains deferred.
"""
from __future__ import annotations

import json
import os
import threading
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, TypeVar

from utils.capability_registry import CapabilityStore
from utils.discovery_lifecycle import LifecycleStore
from utils.evidence_backend import SchedulerStateBackend, select_scheduler_state_backend
from utils.coordinator_backend import (
    CollectionAdmissionError,
    CoordinatorBackend,
    CoordinatorBackendError,
    CoordinatorDecision,
    DEFAULT_CONCURRENCY_BUDGETS,
    InMemoryCoordinatorBackend,
    Job,
    JobStatus,
    Provenance,
    derive_lock_key,
    vendor_budget_key,
)


# ---------------------------------------------------------------------------
# Coordinator
# ---------------------------------------------------------------------------

class CollectionCoordinator:
    """Admission coordinator for collection jobs — a thin, backend-delegating shell.

    All admission logic lives in a ``CoordinatorBackend``
    (``utils/coordinator_backend.py``). The default ``InMemoryCoordinatorBackend``
    is single-process only (not safe across multiple OS processes); pass a
    ``PostgresCoordinatorBackend`` (via ``select_coordinator_backend()``) for
    cross-process admission.
    """

    def __init__(self, backend: CoordinatorBackend | None = None) -> None:
        self.backend: CoordinatorBackend = backend if backend is not None else InMemoryCoordinatorBackend()

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
        return self.backend.admit_request(vendor, workflow_scope, canonical_ids, provenance=provenance)

    def release(self, job_id: str) -> None:
        """Mark a job as completed and free its resources."""
        self.backend.release(job_id)

    def fail(self, job_id: str, reason: str) -> None:
        """Mark a job as failed and free its resources."""
        self.backend.fail(job_id, reason)

    def cancel(self, job_id: str) -> bool:
        """Request cancellation of a running job.

        Marks it CANCELLED and frees its resources; returns True if the job
        was found and still open, False otherwise.
        """
        return self.backend.cancel(job_id)

    def get_job(self, job_id: str) -> Job | None:
        return self.backend.get_job(job_id)

    def wait_for_terminal(self, job_id: str, timeout: float) -> Job | None:
        """Wait boundedly for an active job to reach a terminal state."""
        return self.backend.wait_for_terminal(job_id, timeout)

    def active_jobs(self) -> list[Job]:
        return self.backend.active_jobs()

    def all_jobs(self) -> list[Job]:
        return self.backend.all_jobs()

    def budget_snapshot(self) -> dict[str, dict[str, int]]:
        """Return a safe, read-only snapshot of concurrency budgets.

        Reports configured capacity and currently available permits per
        vendor/context key. Contains no device identities or secrets.
        """
        return self.backend.budget_snapshot()


def select_coordinator_backend(data_root: Path | None = None) -> CoordinatorBackend:
    """Choose a coordinator backend from the environment. Fails closed.

    ``SECURITYEXPERT_COORDINATOR_BACKEND`` = ``memory`` (default) |
    ``postgres``. The in-memory default is the unchanged, validated 0.6.1C
    behavior and needs no configuration. ``postgres`` requires
    ``SECURITYEXPERT_COORDINATOR_POSTGRES_DSN`` and runs the DEV.3.2 startup
    preflight (``verify_postgres_backend_ready``) before returning — a
    misconfigured or unreachable deployment raises rather than silently
    falling back to unsynchronized local admission.
    """
    backend_name = os.getenv("SECURITYEXPERT_COORDINATOR_BACKEND", "memory").strip().lower()
    if backend_name in ("", "memory"):
        return InMemoryCoordinatorBackend()
    if backend_name != "postgres":
        raise CoordinatorBackendError(
            f"Unsupported SECURITYEXPERT_COORDINATOR_BACKEND={backend_name!r}; expected 'memory' or 'postgres'."
        )

    dsn = os.getenv("SECURITYEXPERT_COORDINATOR_POSTGRES_DSN", "").strip()
    if not dsn:
        raise CoordinatorBackendError(
            "SECURITYEXPERT_COORDINATOR_BACKEND=postgres requires SECURITYEXPERT_COORDINATOR_POSTGRES_DSN."
        )

    from utils.coordinator_backend import PostgresCoordinatorBackend, verify_postgres_backend_ready
    from utils.support_bundle import _get_support_key

    verify_postgres_backend_ready(dsn)
    support_key_file = (Path(data_root) / ".support_hmac.key") if data_root is not None else None
    secret = _get_support_key(support_key_file) if support_key_file is not None else _get_support_key()
    return PostgresCoordinatorBackend(dsn, secret)


# ---------------------------------------------------------------------------
# Shared argv construction (CON.2 C2-2)
# ---------------------------------------------------------------------------

def workflow_argv(workflow: str, runtime_root: Path, *, targets: "tuple[str, ...] | list[str]" = ()) -> list[str]:
    """Build the ``main.py`` argv for one workflow name.

    The single argv construction path shared by the scheduler
    (``application.workflows.maintenance._scheduler_workflow_argv``) and the
    console job runner (``console/runner.py``) — CON.2 contract C2-2. If the
    scheduler and the console ever built argv differently, one of them would
    eventually build it wrongly. No string originating from an HTTP request
    is ever placed into argv here except an already-validated ``entity_id``
    (C2-2's one exception).
    """
    normalized = "cp" if workflow == "checkpoint" else workflow
    base = ["--runtime-root", str(runtime_root)]
    if normalized == "cp-config":
        return [*base, "--cp-config-collect", "--cp-config-stage", "all"]
    if normalized.startswith("recovery-"):
        vendor = {"recovery-pan": "panorama", "recovery-cp": "checkpoint"}[normalized]
        argv = [*base, "--recovery-collect", "--recovery-vendor", vendor]
        if targets:
            argv += ["--recovery-gateways", ",".join(targets)]
        return argv
    return [*base, "--only", normalized]


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
    #
    # RB.3a (contract RB_3A_CP_GAIA_BACKUP_ATTESTATION.md, design decision A9):
    # "recovery-attest-cp" (CP Gaia backup/snapshot attestation) is likewise
    # absent by design -- NOT because it is blocked, but because allowlisting
    # it would let a policy file schedule fleet-wide SSH at any interval
    # >= 10 min, which is a separate decision from "may this command run at
    # all" and needs its own review (CURRENT_STATE.md standing priority 2: do
    # not increase recurring polling frequency/concurrency). RB.3a ships the
    # on-demand CLI path only (main.py --recovery-attest).
    #
    # OP.0a (contract OP_0A_HA_READINESS_ASSESSMENT.md, decision P6):
    # "ha-readiness" is absent for a different reason again -- it performs no
    # device I/O at all, so there is nothing to rate-limit, and it recomputes
    # from state that only changes when a collection runs. Scheduling an
    # offline derivation would add runs without adding evidence.
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


def load_scheduler_state(data_root: Path, backend: SchedulerStateBackend | None = None) -> dict[str, datetime]:
    """Load value-free last-success timestamps; malformed state fails closed.

    Validation stays here rather than in the backend so both storage backends
    enforce the identical allowlist rules (DEV.3.3 contract, amendment A5).
    """
    if backend is None:
        backend = select_scheduler_state_backend(path=Path(data_root) / SCHEDULER_STATE_PATH)
    try:
        raw = backend.load_raw()
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SchedulerPolicyError("scheduler state cannot be read safely") from exc
    if raw is None:
        return {}
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


def write_scheduler_state(
    data_root: Path, state: dict[str, datetime], backend: SchedulerStateBackend | None = None
) -> None:
    """Persist value-free completion timestamps through the active backend."""
    if backend is None:
        backend = select_scheduler_state_backend(path=Path(data_root) / SCHEDULER_STATE_PATH)
    payload = {
        "version": 1,
        "last_completed_at": {
            workflow: value.astimezone(timezone.utc).isoformat()
            for workflow, value in sorted(state.items())
        },
    }
    backend.save_raw(payload)


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
