"""Coordinator backend implementations — DEV.3.2 distributed per-endpoint lock.

``CollectionCoordinator`` (``utils/collection_executor.py``) is the single
admission boundary that keeps the product from opening two concurrent
sessions to the same physical network device. This module extracts that
admission logic behind a ``CoordinatorBackend`` protocol so it can be
enforced either in-process (the validated 0.6.1C behavior, unchanged, and
still the default) or across processes via PostgreSQL session-level advisory
locks (DEV.3.2 — ``docs/history/phase/DEV3_2_DISTRIBUTED_ENDPOINT_LOCK.md``).

Safety contracts carried over unchanged from the single-process coordinator:

* At most one active job per physical endpoint (``canonical_id``) at a time.
* Vendor/context concurrency budgets are fixed and conservative
  (``DEFAULT_CONCURRENCY_BUDGETS``); raising them needs its own
  real-environment evidence and is out of scope here.
* A conflicting request coalesces onto the active job; no second device
  session opens.
* No ``canonical_id``, device name, address, credential or transport
  transcript is ever persisted by a backend — only keyed hashes, job ids,
  vendor/scope labels, status and timestamps.

The PostgreSQL backend additionally requires (see the phase doc, decision
D6): a dedicated, non-pooled (or session-pooled) connection per in-flight
job. A transaction-pooling proxy (e.g. pgbouncer ``pool_mode=transaction``)
silently breaks session-level advisory locks; ``verify_postgres_backend_ready``
detects this at startup and fails closed rather than degrading to unsafe
concurrent device access.
"""
from __future__ import annotations

import abc
import hashlib
import hmac
import struct
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any


# ---------------------------------------------------------------------------
# Enumerations
# ---------------------------------------------------------------------------

class Provenance(str, Enum):
    MANUAL = "manual"
    SCHEDULED = "scheduled"
    # "event" is a reserved schema value; no trigger implemented.


class CoordinatorDecision(str, Enum):
    ADMITTED = "admitted"
    COALESCED = "coalesced"
    REJECTED_BUDGET = "rejected_budget"
    REJECTED_LOCKED = "rejected_locked"


class JobStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    COALESCED = "coalesced"   # request merged into an existing job
    ORPHANED = "orphaned"     # postgres backend: reclaimed a dead holder's row


class CollectionAdmissionError(RuntimeError):
    """Raised when a collection request must not open a transport session."""

    def __init__(self, decision: "CoordinatorDecision", job: "Job") -> None:
        super().__init__(f"collection admission did not execute: {decision.value}")
        self.decision = decision
        self.job = job


class CoordinatorBackendError(RuntimeError):
    """Raised when a backend cannot safely admit or observe jobs.

    Backend failures are fail-closed: a caller must treat this exactly like
    a non-admitted decision and must never fall back to unsynchronized local
    admission.
    """


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


def vendor_budget_key(vendor: str, workflow_scope: str) -> str:
    v = vendor.strip().lower()
    w = workflow_scope.strip().lower()
    if v == "checkpoint" and "vsx" in w:
        return "checkpoint_vsx"
    if v in {"checkpoint", "cp"}:
        return "checkpoint"
    if v in {"paloalto", "pan"}:
        return "paloalto"
    return "_default"


def derive_lock_key(secret: bytes, *parts: str) -> int:
    """Deterministic signed 64-bit lock key. Never derived from output — input only.

    HMAC-SHA256 keyed by a deployment secret (by convention
    ``data/.support_hmac.key`` — see ``utils.support_bundle._get_support_key``)
    so the key cannot be reversed to a device identity or forged without the
    secret. Domain-separated via ``parts`` (e.g. ``("endpoint", canonical_id)``
    vs ``("gate", budget_key)``) so distinct purposes never collide by
    construction. Collisions between distinct endpoints are fail-safe: they
    serialize collection (slower), never share exclusion across genuinely
    distinct physical devices (the unsafe direction is avoided).
    """
    message = "\x1f".join(parts).strip().lower().encode("utf-8")
    digest = hmac.new(secret, message, hashlib.sha256).digest()
    return struct.unpack(">q", digest[:8])[0]


# ---------------------------------------------------------------------------
# Backend protocol
# ---------------------------------------------------------------------------

class CoordinatorBackend(abc.ABC):
    """Admission backend contract. See module docstring for safety invariants."""

    @abc.abstractmethod
    def admit_request(
        self,
        vendor: str,
        workflow_scope: str,
        canonical_ids: list[str],
        provenance: str = Provenance.MANUAL.value,
    ) -> tuple[CoordinatorDecision, Job, Job | None]:
        ...

    @abc.abstractmethod
    def release(self, job_id: str) -> None:
        ...

    @abc.abstractmethod
    def fail(self, job_id: str, reason: str) -> None:
        ...

    @abc.abstractmethod
    def cancel(self, job_id: str) -> bool:
        ...

    @abc.abstractmethod
    def get_job(self, job_id: str) -> Job | None:
        ...

    @abc.abstractmethod
    def wait_for_terminal(self, job_id: str, timeout: float) -> Job | None:
        ...

    @abc.abstractmethod
    def active_jobs(self) -> list[Job]:
        ...

    @abc.abstractmethod
    def all_jobs(self) -> list[Job]:
        ...

    @abc.abstractmethod
    def budget_snapshot(self) -> dict[str, dict[str, int]]:
        ...


# ---------------------------------------------------------------------------
# In-memory backend — the validated 0.6.1C behavior, unchanged, default.
# ---------------------------------------------------------------------------

class InMemoryCoordinatorBackend(CoordinatorBackend):
    """Thread-safe, single-process admission backend.

    Not safe to use across multiple OS processes — see
    ``PostgresCoordinatorBackend`` for the distributed equivalent.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        # Per-vendor semaphores for concurrency budget.
        self._budgets: dict[str, threading.Semaphore] = {
            key: threading.Semaphore(val)
            for key, val in _DEFAULT_BUDGETS.items()
        }
        # Active and recently completed jobs.
        self._jobs: dict[str, Job] = {}
        # Map of canonical_id → active job_id (for coalescing).
        self._active_for: dict[str, str] = {}

    def admit_request(self, vendor, workflow_scope, canonical_ids, provenance=Provenance.MANUAL.value):
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

        budget_key = vendor_budget_key(vendor, workflow_scope)

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
            job.reason = "admitted"
            self._jobs[job_id] = job
            # Store semaphore ref for release (using a hidden attribute).
            object.__setattr__(job, "_budget_sem", sem)  # type: ignore[arg-type]
            return CoordinatorDecision.ADMITTED, job, None

    def release(self, job_id: str) -> None:
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
        vendor/context key by draining and immediately restoring each
        semaphore under the coordinator lock — accurate for any capacity,
        not only 1 (fixes the earlier probe, which always reported at most
        1 available regardless of true capacity).
        """
        with self._lock:
            snapshot: dict[str, dict[str, int]] = {}
            for key, sem in self._budgets.items():
                capacity = DEFAULT_CONCURRENCY_BUDGETS.get(key, 0)
                acquired = 0
                while sem.acquire(blocking=False):
                    acquired += 1
                for _ in range(acquired):
                    sem.release()
                snapshot[key] = {"capacity": capacity, "available": acquired}
            return snapshot


# ---------------------------------------------------------------------------
# PostgreSQL backend — DEV.3.2 distributed equivalent.
# ---------------------------------------------------------------------------

_SCHEMA_STATEMENTS = (
    """
    CREATE TABLE IF NOT EXISTS collection_job (
        job_id TEXT PRIMARY KEY,
        vendor TEXT NOT NULL,
        workflow_scope TEXT NOT NULL,
        budget_key TEXT NOT NULL,
        provenance TEXT NOT NULL,
        status TEXT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        admitted_at TIMESTAMPTZ,
        completed_at TIMESTAMPTZ,
        coalesced_to TEXT,
        reason TEXT
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS collection_job_lock (
        job_id TEXT NOT NULL REFERENCES collection_job(job_id) ON DELETE CASCADE,
        lock_key BIGINT NOT NULL,
        PRIMARY KEY (job_id, lock_key)
    )
    """,
    "CREATE INDEX IF NOT EXISTS collection_job_lock_key_idx ON collection_job_lock(lock_key)",
    "CREATE INDEX IF NOT EXISTS collection_job_status_idx ON collection_job(status, budget_key)",
)

_WAIT_POLL_SECONDS = 0.1
_SCHEDULER_LOCK_DOMAIN = ("scheduler", "global-one-shot-evaluation")


class SchedulerLockUnavailable(RuntimeError):
    """Another process already holds the scheduler evaluation lock."""


class _SchedulerLockHandle:
    """Context manager holding the scheduler-wide advisory lock for one cycle.

    Gates only the read-evaluate-write cycle in ``main._run_scheduler_once``
    (correctness contract item 6: the scheduler must not dispatch the same
    due workflow from two workers in one interval). It has nothing to do
    with, and does not substitute for, the per-endpoint/per-vendor admission
    locks above — a workflow that gets past this gate still goes through
    the normal ``admit_request`` path per device.
    """

    def __init__(self, psycopg_module, dsn: str, lock_key: int) -> None:
        self._psycopg = psycopg_module
        self._dsn = dsn
        self._lock_key = lock_key
        self._conn = None

    def __enter__(self) -> "_SchedulerLockHandle":
        self._conn = self._psycopg.connect(self._dsn, autocommit=True)
        with self._conn.cursor() as cur:
            cur.execute("SELECT pg_try_advisory_lock(%s)", (self._lock_key,))
            (acquired,) = cur.fetchone()
        if not acquired:
            self._conn.close()
            self._conn = None
            raise SchedulerLockUnavailable(
                "another process already holds the scheduler evaluation lock for this cycle"
            )
        return self

    def __exit__(self, *exc: object) -> bool:
        if self._conn is not None:
            with self._conn.cursor() as cur:
                cur.execute("SELECT pg_advisory_unlock(%s)", (self._lock_key,))
            self._conn.close()
            self._conn = None
        return False


def try_acquire_scheduler_lock(dsn: str, secret: bytes) -> _SchedulerLockHandle:
    """Non-blocking scheduler-wide gate for one read-evaluate-write cycle.

    Usage::

        try:
            with try_acquire_scheduler_lock(dsn, secret):
                ...load state, evaluate due workflows, dispatch, write state...
        except SchedulerLockUnavailable:
            ...another process is already evaluating this cycle; skip...
    """
    psycopg = _psycopg()
    lock_key = derive_lock_key(secret, *_SCHEDULER_LOCK_DOMAIN)
    return _SchedulerLockHandle(psycopg, dsn, lock_key)


def _psycopg():
    try:
        import psycopg
    except ImportError as exc:  # pragma: no cover - exercised only without the driver
        raise CoordinatorBackendError(
            "SECURITYEXPERT_COORDINATOR_BACKEND=postgres requires the 'psycopg' package "
            "(psycopg[binary]>=3.1); it is not installed."
        ) from exc
    return psycopg


def verify_postgres_backend_ready(dsn: str) -> None:
    """Fail-closed startup preflight for the PostgreSQL coordinator backend.

    Detects the single highest-severity deployment hazard for this design
    (phase doc decision D6): a transaction-pooling connection pooler (e.g.
    pgbouncer ``pool_mode=transaction``) silently breaks session-level
    advisory locks, because the lock outlives the transaction while the
    underlying physical connection returns to the pool and may be handed to
    a different client. No test against a direct connection catches this —
    it must be checked against the deployment's actual connection path.

    Two checks, both must pass:

    1. **Backend-process stability.** Two queries issued on the *same*
       client-visible connection, with no explicit reconnect between them,
       must observe the same ``pg_backend_pid()``. A transaction-pooling
       proxy can hand each query to a different physical backend even
       though the client believes it holds one persistent connection.
    2. **Cross-connection lock visibility.** A lock taken on connection A
       must be observed as unavailable from connection B, and must become
       available from B only after A releases it. This is the actual
       property the coordinator depends on.

    Raises ``CoordinatorBackendError`` (never falls back to unsafe local
    admission) if either check fails.
    """
    psycopg = _psycopg()
    probe_key = derive_lock_key(b"verify-postgres-backend-ready", "preflight-probe")

    try:
        with psycopg.connect(dsn, autocommit=True) as conn_a:
            with conn_a.cursor() as cur:
                cur.execute("SELECT pg_backend_pid()")
                pid_1 = cur.fetchone()[0]
                cur.execute("SELECT pg_backend_pid()")
                pid_2 = cur.fetchone()[0]
            if pid_1 != pid_2:
                raise CoordinatorBackendError(
                    "PostgreSQL backend preflight failed: pg_backend_pid() changed "
                    "across two queries on one connection. This is the signature of a "
                    "transaction-pooling proxy (e.g. pgbouncer pool_mode=transaction), "
                    "which silently breaks session-level advisory locks. Use a direct "
                    "connection or session-mode pooling only."
                )

            with conn_a.cursor() as cur:
                cur.execute("SELECT pg_advisory_lock(%s)", (probe_key,))
            try:
                with psycopg.connect(dsn, autocommit=True) as conn_b:
                    with conn_b.cursor() as cur:
                        cur.execute("SELECT pg_try_advisory_lock(%s)", (probe_key,))
                        (still_locked_from_b,) = cur.fetchone()
                    if still_locked_from_b:
                        # Unlock what B just (wrongly) acquired before raising.
                        with conn_b.cursor() as cur:
                            cur.execute("SELECT pg_advisory_unlock(%s)", (probe_key,))
                        raise CoordinatorBackendError(
                            "PostgreSQL backend preflight failed: a lock held on one "
                            "connection was acquirable from a second connection. "
                            "Session-level advisory locks are not being honoured by "
                            "this connection path (pooling or driver misconfiguration)."
                        )
            finally:
                with conn_a.cursor() as cur:
                    cur.execute("SELECT pg_advisory_unlock(%s)", (probe_key,))

            with psycopg.connect(dsn, autocommit=True) as conn_c:
                with conn_c.cursor() as cur:
                    cur.execute("SELECT pg_try_advisory_lock(%s)", (probe_key,))
                    (now_free,) = cur.fetchone()
                    if not now_free:
                        raise CoordinatorBackendError(
                            "PostgreSQL backend preflight failed: a released advisory "
                            "lock was still reported as held from a fresh connection."
                        )
                    cur.execute("SELECT pg_advisory_unlock(%s)", (probe_key,))
    except CoordinatorBackendError:
        raise
    except Exception as exc:
        raise CoordinatorBackendError(f"PostgreSQL backend preflight could not run: {exc}") from exc


class PostgresCoordinatorBackend(CoordinatorBackend):
    """Cross-process admission backend using session-level advisory locks.

    Design (``docs/history/phase/DEV3_2_DISTRIBUTED_ENDPOINT_LOCK.md``):

    * Each endpoint's exclusion is a ``pg_advisory_lock`` held on a
      **dedicated connection for the job's lifetime** — released by the
      server itself if that connection dies, so there is no TTL to guess
      and no window where a still-running collection loses its lock.
    * The job row (``collection_job`` / ``collection_job_lock``) is
      observability and coalescing only; it is never consulted to decide
      whether a device session may open. Only the advisory lock is.
    * Per-vendor budget admission is a counted check performed under a
      short-lived per-budget-key gate lock: reconcile orphans, count
      running jobs, admit if under capacity, insert the row, release the
      gate. The gate is held only for that decision, never for the job.
    * Lock keys are HMAC-derived from ``canonical_id`` (see
      ``derive_lock_key``); no device identity is ever written to Postgres.
    """

    def __init__(self, dsn: str, secret: bytes, *, capacities: dict[str, int] | None = None) -> None:
        self._psycopg = _psycopg()
        self._dsn = dsn
        self._secret = secret
        self._capacities = dict(capacities) if capacities is not None else dict(DEFAULT_CONCURRENCY_BUDGETS)
        self._conn_lock = threading.Lock()
        self._open_conns: dict[str, Any] = {}
        self._ensure_schema()

    # -- connection helpers -------------------------------------------------

    def _connect(self):
        return self._psycopg.connect(self._dsn, autocommit=True)

    def _ensure_schema(self) -> None:
        with self._connect() as conn:
            with conn.cursor() as cur:
                for statement in _SCHEMA_STATEMENTS:
                    cur.execute(statement)

    def _gate_key(self, budget_key: str) -> int:
        return derive_lock_key(self._secret, "gate", budget_key)

    def _endpoint_key(self, canonical_id: str) -> int:
        return derive_lock_key(self._secret, "endpoint", canonical_id)

    # -- row mapping ----------------------------------------------------

    @staticmethod
    def _row_to_job(row) -> Job:
        (job_id, vendor, workflow_scope, _budget_key, provenance, status,
         created_at, admitted_at, completed_at, coalesced_to, reason) = row
        job = Job(
            job_id=job_id,
            vendor=vendor,
            workflow_scope=workflow_scope,
            provenance=provenance,
            canonical_ids=[],  # never persisted — see module/class docstring
            status=status,
            created_at=created_at.isoformat() if created_at else "",
            admitted_at=admitted_at.isoformat() if admitted_at else None,
            completed_at=completed_at.isoformat() if completed_at else None,
            coalesced_to=coalesced_to,
            reason=reason,
        )
        if status in (
            JobStatus.COMPLETED.value, JobStatus.FAILED.value,
            JobStatus.CANCELLED.value, JobStatus.COALESCED.value,
            JobStatus.ORPHANED.value,
        ):
            job.completion_event.set()
        return job

    _ROW_COLUMNS = (
        "job_id, vendor, workflow_scope, budget_key, provenance, status, "
        "created_at, admitted_at, completed_at, coalesced_to, reason"
    )

    # -- admission --------------------------------------------------------

    def admit_request(self, vendor, workflow_scope, canonical_ids, provenance=Provenance.MANUAL.value):
        job_id = f"job_{uuid.uuid4().hex[:12]}"
        now = datetime.now(timezone.utc)
        budget_key = vendor_budget_key(vendor, workflow_scope)
        endpoint_keys = sorted({self._endpoint_key(cid) for cid in canonical_ids})
        gate_key = self._gate_key(budget_key)

        job = Job(
            job_id=job_id,
            vendor=vendor,
            workflow_scope=workflow_scope,
            provenance=provenance,
            canonical_ids=list(canonical_ids),
            created_at=now.isoformat(),
        )

        try:
            with self._connect() as gate_conn:
                with gate_conn.cursor() as cur:
                    cur.execute("SELECT pg_advisory_lock(%s)", (gate_key,))
                try:
                    # Reconcile globally (not scoped to this budget_key): a dead
                    # holder's row must not block either the coalesce check below
                    # or another vendor's budget count. Safe under concurrent
                    # gates — the underlying primitive is the advisory lock
                    # itself, which is exclusive across sessions regardless of
                    # which gate is doing the reconciling.
                    self._reconcile_orphans(gate_conn)

                    # --- Coalesce check first (mirrors the in-memory backend:
                    # a request for an endpoint already held by a running job
                    # always coalesces, even if this vendor's budget is full —
                    # coalescing opens no new session and consumes no budget).
                    holder = None
                    for key in endpoint_keys:
                        holder = self._find_running_holder(gate_conn, key)
                        if holder is not None:
                            break
                    if holder is not None:
                        job.status = JobStatus.COALESCED.value
                        job.coalesced_to = holder
                        job.reason = "coalesced_into_active_job"
                        job.completed_at = now.isoformat()
                        job.completion_event.set()
                        self._insert_row(gate_conn, job, budget_key)
                        active = self.get_job(holder)
                        return CoordinatorDecision.COALESCED, job, active

                    capacity = self._capacities.get(budget_key, self._capacities.get("_default", 1))
                    running = self._count_running(gate_conn, budget_key)
                    if running >= capacity:
                        job.status = JobStatus.FAILED.value
                        job.reason = "concurrency_budget_exhausted"
                        job.completed_at = now.isoformat()
                        job.completion_event.set()
                        self._insert_row(gate_conn, job, budget_key)
                        return CoordinatorDecision.REJECTED_BUDGET, job, None

                    job_conn = self._connect()
                    acquired: list[int] = []
                    conflict_key: int | None = None
                    for key in endpoint_keys:
                        with job_conn.cursor() as cur:
                            cur.execute("SELECT pg_try_advisory_lock(%s)", (key,))
                            (ok,) = cur.fetchone()
                        if ok:
                            acquired.append(key)
                        else:
                            conflict_key = key
                            break

                    if conflict_key is not None:
                        for key in acquired:
                            with job_conn.cursor() as cur:
                                cur.execute("SELECT pg_advisory_unlock(%s)", (key,))
                        job_conn.close()

                        holder = self._find_running_holder(gate_conn, conflict_key)
                        if holder is not None:
                            job.status = JobStatus.COALESCED.value
                            job.coalesced_to = holder
                            job.reason = "coalesced_into_active_job"
                            job.completed_at = now.isoformat()
                            job.completion_event.set()
                            self._insert_row(gate_conn, job, budget_key)
                            active = self.get_job(holder)
                            return CoordinatorDecision.COALESCED, job, active

                        job.status = JobStatus.FAILED.value
                        job.reason = "endpoint_lock_conflict"
                        job.completed_at = now.isoformat()
                        job.completion_event.set()
                        self._insert_row(gate_conn, job, budget_key)
                        return CoordinatorDecision.REJECTED_LOCKED, job, None

                    job.status = JobStatus.RUNNING.value
                    job.admitted_at = now.isoformat()
                    job.reason = "admitted"
                    self._insert_row(gate_conn, job, budget_key, endpoint_keys=endpoint_keys)
                    with self._conn_lock:
                        self._open_conns[job_id] = job_conn
                    return CoordinatorDecision.ADMITTED, job, None
                finally:
                    with gate_conn.cursor() as cur:
                        cur.execute("SELECT pg_advisory_unlock(%s)", (gate_key,))
        except CoordinatorBackendError:
            raise
        except Exception as exc:
            raise CoordinatorBackendError(f"postgres coordinator backend admit failed: {exc}") from exc

    def _insert_row(self, conn, job: Job, budget_key: str, *, endpoint_keys: list[int] | None = None) -> None:
        with conn.cursor() as cur:
            cur.execute(
                f"INSERT INTO collection_job ({self._ROW_COLUMNS}) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
                (
                    job.job_id, job.vendor, job.workflow_scope, budget_key, job.provenance,
                    job.status, job.created_at, job.admitted_at, job.completed_at,
                    job.coalesced_to, job.reason,
                ),
            )
            if endpoint_keys:
                cur.executemany(
                    "INSERT INTO collection_job_lock (job_id, lock_key) VALUES (%s, %s)",
                    [(job.job_id, key) for key in endpoint_keys],
                )

    def _count_running(self, conn, budget_key: str) -> int:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT count(*) FROM collection_job WHERE budget_key = %s AND status = %s",
                (budget_key, JobStatus.RUNNING.value),
            )
            (count,) = cur.fetchone()
            return int(count)

    def _find_running_holder(self, conn, lock_key: int) -> str | None:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT j.job_id FROM collection_job_lock l "
                "JOIN collection_job j ON j.job_id = l.job_id "
                "WHERE l.lock_key = %s AND j.status = %s LIMIT 1",
                (lock_key, JobStatus.RUNNING.value),
            )
            row = cur.fetchone()
            return row[0] if row else None

    def _reconcile_orphans(self, conn, budget_key: str | None = None) -> None:
        """Reclaim rows whose holding connection is provably gone.

        A running row's endpoint locks are, by construction, held on its
        holder's dedicated connection. If that connection died, PostgreSQL
        has already released the locks — so if *every* lock_key belonging
        to a running row is acquirable right now, the row is stale (its
        holder is gone), never a live race (a live holder's own locks can
        never be acquired out from under it). Reclaiming is therefore safe
        with no TTL and no heartbeat.

        Scans globally by default (``budget_key=None``): a dead holder's row
        must not block the coalesce check or another vendor's budget count,
        and the underlying primitive (the advisory lock itself) is exclusive
        across sessions regardless of which gate calls this.
        """
        with conn.cursor() as cur:
            if budget_key is None:
                cur.execute(
                    "SELECT job_id FROM collection_job WHERE status = %s",
                    (JobStatus.RUNNING.value,),
                )
            else:
                cur.execute(
                    "SELECT job_id FROM collection_job WHERE budget_key = %s AND status = %s",
                    (budget_key, JobStatus.RUNNING.value),
                )
            running_ids = [row[0] for row in cur.fetchall()]

        for job_id in running_ids:
            with conn.cursor() as cur:
                cur.execute("SELECT lock_key FROM collection_job_lock WHERE job_id = %s", (job_id,))
                keys = [row[0] for row in cur.fetchall()]
            if not keys:
                continue

            probe_conn = self._connect()
            try:
                acquired: list[int] = []
                all_free = True
                for key in keys:
                    with probe_conn.cursor() as cur:
                        cur.execute("SELECT pg_try_advisory_lock(%s)", (key,))
                        (ok,) = cur.fetchone()
                    if ok:
                        acquired.append(key)
                    else:
                        all_free = False
                        break
                for key in acquired:
                    with probe_conn.cursor() as cur:
                        cur.execute("SELECT pg_advisory_unlock(%s)", (key,))
                if all_free:
                    with conn.cursor() as cur:
                        cur.execute(
                            "UPDATE collection_job SET status = %s, reason = %s, completed_at = %s "
                            "WHERE job_id = %s AND status = %s",
                            (
                                JobStatus.ORPHANED.value,
                                "orphaned_holder_connection_lost",
                                datetime.now(timezone.utc),
                                job_id,
                                JobStatus.RUNNING.value,
                            ),
                        )
            finally:
                probe_conn.close()

    # -- lifecycle ----------------------------------------------------------

    def _finish(self, job_id: str, status: str, reason: str | None) -> bool:
        with self._conn_lock:
            conn = self._open_conns.pop(job_id, None)
        if conn is None:
            return False
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT lock_key FROM collection_job_lock WHERE job_id = %s", (job_id,))
                keys = [row[0] for row in cur.fetchall()]
            for key in keys:
                with conn.cursor() as cur:
                    cur.execute("SELECT pg_advisory_unlock(%s)", (key,))
        finally:
            conn.close()

        with self._connect() as admin_conn:
            with admin_conn.cursor() as cur:
                cur.execute(
                    "UPDATE collection_job SET status = %s, reason = %s, completed_at = %s "
                    "WHERE job_id = %s",
                    (status, reason, datetime.now(timezone.utc), job_id),
                )
        return True

    def release(self, job_id: str) -> None:
        self._finish(job_id, JobStatus.COMPLETED.value, None)

    def fail(self, job_id: str, reason: str) -> None:
        self._finish(job_id, JobStatus.FAILED.value, reason)

    def cancel(self, job_id: str) -> bool:
        with self._conn_lock:
            still_open = job_id in self._open_conns
        if not still_open:
            return False
        return self._finish(job_id, JobStatus.CANCELLED.value, "cancelled_by_request")

    def get_job(self, job_id: str) -> Job | None:
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    f"SELECT {self._ROW_COLUMNS} FROM collection_job WHERE job_id = %s",
                    (job_id,),
                )
                row = cur.fetchone()
        return self._row_to_job(row) if row else None

    def wait_for_terminal(self, job_id: str, timeout: float) -> Job | None:
        deadline = time.monotonic() + max(0.0, float(timeout))
        job = self.get_job(job_id)
        while job is not None and not job.completion_event.is_set():
            if time.monotonic() >= deadline:
                break
            time.sleep(_WAIT_POLL_SECONDS)
            job = self.get_job(job_id)
        return job

    def active_jobs(self) -> list[Job]:
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    f"SELECT {self._ROW_COLUMNS} FROM collection_job WHERE status = %s",
                    (JobStatus.RUNNING.value,),
                )
                rows = cur.fetchall()
        return [self._row_to_job(row) for row in rows]

    def all_jobs(self) -> list[Job]:
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(f"SELECT {self._ROW_COLUMNS} FROM collection_job")
                rows = cur.fetchall()
        return [self._row_to_job(row) for row in rows]

    def budget_snapshot(self) -> dict[str, dict[str, int]]:
        snapshot: dict[str, dict[str, int]] = {}
        with self._connect() as conn:
            for key, capacity in self._capacities.items():
                self._reconcile_orphans(conn, key)
                running = self._count_running(conn, key)
                snapshot[key] = {"capacity": capacity, "available": max(0, capacity - running)}
        return snapshot

    def scheduler_lock(self) -> "_SchedulerLockHandle":
        """Non-blocking gate for one scheduler read-evaluate-write cycle.

        See ``try_acquire_scheduler_lock``. Not part of the ``CoordinatorBackend``
        protocol — the in-memory backend has no cross-process scheduler race to
        gate, so callers should only reach for this when they already know
        they hold a ``PostgresCoordinatorBackend``.
        """
        return try_acquire_scheduler_lock(self._dsn, self._secret)
