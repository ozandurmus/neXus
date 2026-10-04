"""Tests for the DEV.3.2 distributed coordinator backend.

Design: docs/history/phase/DEV3_2_DISTRIBUTED_ENDPOINT_LOCK.md.

Two tiers:

* Always-run unit tests: lock-key derivation, the in-memory backend's two
  incidental bug fixes (dead ``_endpoint_locks``, ``budget_snapshot``
  undercounting above capacity 1), backend selection, and the preflight's
  failure-detection logic against a mocked driver (deterministic, no
  database required).
* ``requires_postgres``-gated integration tests against a real local
  PostgreSQL database (``SECURITYEXPERT_TEST_POSTGRES_DSN``, default
  ``postgresql://securityexpert:securityexpert@127.0.0.1:5432/securityexpert_test``),
  skipped when unavailable — the same posture as the render harness's ``bun``
  skipif. These include a genuine multi-process fault-injection test
  (spawn a real child process, SIGKILL it, prove the lock is reclaimed)
  because the property under test — cross-process exclusion — cannot be
  proven with threads.
"""
from __future__ import annotations

import os
import subprocess
import sys
import threading
import time

import pytest

from utils.collection_executor import CollectionCoordinator, select_coordinator_backend
from utils.coordinator_backend import (
    CoordinatorBackendError,
    CoordinatorDecision,
    InMemoryCoordinatorBackend,
    JobStatus,
    derive_lock_key,
    verify_postgres_backend_ready,
)

pytestmark = pytest.mark.discovery


# ---------------------------------------------------------------------------
# Lock-key derivation
# ---------------------------------------------------------------------------

def test_derive_lock_key_deterministic():
    secret = b"a-test-secret"
    a = derive_lock_key(secret, "endpoint", "10.0.0.1")
    b = derive_lock_key(secret, "endpoint", "10.0.0.1")
    assert a == b
    assert isinstance(a, int)


def test_derive_lock_key_domain_separated():
    """The same canonical_id must not collide with a gate key for it."""
    secret = b"a-test-secret"
    endpoint_key = derive_lock_key(secret, "endpoint", "checkpoint")
    gate_key = derive_lock_key(secret, "gate", "checkpoint")
    assert endpoint_key != gate_key


def test_derive_lock_key_differs_by_secret():
    key_a = derive_lock_key(b"secret-a", "endpoint", "10.0.0.1")
    key_b = derive_lock_key(b"secret-b", "endpoint", "10.0.0.1")
    assert key_a != key_b


def test_derive_lock_key_fits_signed_bigint():
    secret = b"a-test-secret"
    for cid in ["10.0.0.1", "10.0.0.2", "mgmt-cluster-a", ""]:
        key = derive_lock_key(secret, "endpoint", cid)
        assert -(2 ** 63) <= key < 2 ** 63


# ---------------------------------------------------------------------------
# In-memory backend — incidental bug fixes found while auditing DEV.3.2
# ---------------------------------------------------------------------------

def test_in_memory_backend_has_no_dead_endpoint_locks_attribute():
    """`_endpoint_locks` was initialized and never read; removed as dead code."""
    backend = InMemoryCoordinatorBackend()
    assert not hasattr(backend, "_endpoint_locks")


def test_in_memory_budget_snapshot_reports_correct_availability_above_capacity_one():
    """Regression: the old probe always reported at most 1 available,
    silently undercounting for any capacity above 1 (latent because every
    shipped budget is currently 1)."""
    import threading as _threading

    backend = InMemoryCoordinatorBackend()
    backend._budgets["checkpoint"] = _threading.Semaphore(3)

    snap = backend.budget_snapshot()
    assert snap["checkpoint"] == {"capacity": 1, "available": 3}  # capacity label from DEFAULT_CONCURRENCY_BUDGETS

    d1, j1, _ = backend.admit_request("checkpoint", "checkpoint", ["EP-1"])
    d2, j2, _ = backend.admit_request("checkpoint", "checkpoint", ["EP-2"])
    assert d1 == CoordinatorDecision.ADMITTED
    assert d2 == CoordinatorDecision.ADMITTED

    snap = backend.budget_snapshot()
    assert snap["checkpoint"]["available"] == 1

    backend.release(j1.job_id)
    backend.release(j2.job_id)
    snap = backend.budget_snapshot()
    assert snap["checkpoint"]["available"] == 3


# ---------------------------------------------------------------------------
# Backend selection
# ---------------------------------------------------------------------------

def test_select_coordinator_backend_defaults_to_memory(monkeypatch):
    monkeypatch.delenv("SECURITYEXPERT_COORDINATOR_BACKEND", raising=False)
    backend = select_coordinator_backend()
    assert isinstance(backend, InMemoryCoordinatorBackend)


def test_select_coordinator_backend_unsupported_name_raises(monkeypatch):
    monkeypatch.setenv("SECURITYEXPERT_COORDINATOR_BACKEND", "sqlite")
    with pytest.raises(CoordinatorBackendError):
        select_coordinator_backend()


def test_select_coordinator_backend_postgres_without_dsn_raises(monkeypatch):
    monkeypatch.setenv("SECURITYEXPERT_COORDINATOR_BACKEND", "postgres")
    monkeypatch.delenv("SECURITYEXPERT_COORDINATOR_POSTGRES_DSN", raising=False)
    with pytest.raises(CoordinatorBackendError):
        select_coordinator_backend()


# ---------------------------------------------------------------------------
# Preflight failure-detection logic — mocked driver, no database required
# ---------------------------------------------------------------------------

class _FakeCursor:
    def __init__(self, script):
        self._script = script

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql, params=None):
        self._last = self._script(sql, params)

    def fetchone(self):
        return self._last


class _FakeConn:
    def __init__(self, script):
        self._script = script

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def cursor(self):
        return _FakeCursor(self._script)


class _FakePsycopg:
    """Minimal psycopg stand-in whose connect() hands out scripted responses."""

    def __init__(self, connect_scripts):
        self._connect_scripts = list(connect_scripts)

    def connect(self, dsn, autocommit=True):
        return _FakeConn(self._connect_scripts.pop(0))


def _patch_psycopg(monkeypatch, fake):
    monkeypatch.setattr("utils.coordinator_backend._psycopg", lambda: fake)


def test_preflight_detects_backend_pid_instability(monkeypatch):
    """A transaction-pooling proxy can hand each query a different physical
    backend even on what the client believes is one persistent connection."""
    pids = iter([111, 222])  # different pid on the second query -> unsafe

    def conn_a_script(sql, params):
        if "pg_backend_pid" in sql:
            return (next(pids),)
        return (True,)

    fake = _FakePsycopg([conn_a_script])
    _patch_psycopg(monkeypatch, fake)

    with pytest.raises(CoordinatorBackendError, match="transaction-pooling"):
        verify_postgres_backend_ready("postgresql://fake/dsn")


def test_preflight_detects_lock_visible_across_connections(monkeypatch):
    """The actual property the coordinator depends on: a lock held on one
    connection must not be acquirable from a second."""
    stable_pid = 999

    def conn_a_script(sql, params):
        if "pg_backend_pid" in sql:
            return (stable_pid,)
        return (True,)  # pg_advisory_lock / pg_advisory_unlock

    def conn_b_script(sql, params):
        # Wrongly reports the lock as acquirable — simulates a pooler that
        # handed this "connection" a different, unlocked physical backend.
        return (True,)

    fake = _FakePsycopg([conn_a_script, conn_b_script, conn_b_script])
    _patch_psycopg(monkeypatch, fake)

    with pytest.raises(CoordinatorBackendError, match="acquirable from a second connection"):
        verify_postgres_backend_ready("postgresql://fake/dsn")


def test_preflight_passes_when_lock_and_pid_are_stable(monkeypatch):
    stable_pid = 42
    state = {"locked": False}

    def conn_a_script(sql, params):
        if "pg_backend_pid" in sql:
            return (stable_pid,)
        if "pg_advisory_lock" in sql and "try" not in sql:
            state["locked"] = True
            return (None,)
        if "pg_advisory_unlock" in sql:
            state["locked"] = False
            return (None,)
        return (None,)

    def conn_b_script(sql, params):
        if "pg_try_advisory_lock" in sql:
            return (not state["locked"],)
        return (None,)

    def conn_c_script(sql, params):
        if "pg_try_advisory_lock" in sql:
            return (not state["locked"],)
        return (None,)

    fake = _FakePsycopg([conn_a_script, conn_b_script, conn_c_script])
    _patch_psycopg(monkeypatch, fake)

    verify_postgres_backend_ready("postgresql://fake/dsn")  # must not raise


# ---------------------------------------------------------------------------
# Live PostgreSQL integration tests
# ---------------------------------------------------------------------------

def _postgres_dsn() -> str:
    return os.environ.get(
        "SECURITYEXPERT_TEST_POSTGRES_DSN",
        "postgresql://securityexpert:securityexpert@127.0.0.1:5432/securityexpert_test",
    )


def _postgres_available() -> bool:
    try:
        import psycopg
    except ImportError:
        return False
    try:
        with psycopg.connect(_postgres_dsn(), connect_timeout=2, autocommit=True) as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT 1")
        return True
    except Exception:
        return False


requires_postgres = pytest.mark.skipif(
    not _postgres_available(),
    reason="live PostgreSQL test database not available (set SECURITYEXPERT_TEST_POSTGRES_DSN)",
)

_TEST_SECRET = b"dev3-2-test-fixture-secret-key"


def _reset_tables() -> None:
    import psycopg

    with psycopg.connect(_postgres_dsn(), autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute("DROP TABLE IF EXISTS collection_job_lock, collection_job CASCADE")


@pytest.fixture()
def pg_backend():
    from utils.coordinator_backend import PostgresCoordinatorBackend

    _reset_tables()
    backend = PostgresCoordinatorBackend(_postgres_dsn(), _TEST_SECRET)
    yield backend
    _reset_tables()


@requires_postgres
def test_postgres_verify_backend_ready_passes_on_direct_connection():
    verify_postgres_backend_ready(_postgres_dsn())  # must not raise


@requires_postgres
def test_postgres_admit_and_release_roundtrip(pg_backend):
    decision, job, active = pg_backend.admit_request("checkpoint", "checkpoint", ["EP-1"])
    assert decision == CoordinatorDecision.ADMITTED
    assert job.status == JobStatus.RUNNING.value
    assert active is None

    fetched = pg_backend.get_job(job.job_id)
    assert fetched.status == JobStatus.RUNNING.value

    pg_backend.release(job.job_id)
    fetched = pg_backend.get_job(job.job_id)
    assert fetched.status == JobStatus.COMPLETED.value


@requires_postgres
def test_postgres_second_request_same_endpoint_coalesces_even_at_budget(pg_backend):
    """A coalesce for an already-held endpoint must win over budget rejection
    — coalescing opens no new session and must not be starved by a full
    per-vendor budget (regression: an earlier draft of this backend checked
    budget before coalescing and wrongly rejected this case)."""
    d1, j1, _ = pg_backend.admit_request("checkpoint", "checkpoint", ["EP-1"])
    assert d1 == CoordinatorDecision.ADMITTED

    d2, j2, active2 = pg_backend.admit_request("checkpoint", "checkpoint", ["EP-1"])
    assert d2 == CoordinatorDecision.COALESCED
    assert active2.job_id == j1.job_id

    pg_backend.release(j1.job_id)


@requires_postgres
def test_postgres_budget_exhausted_for_distinct_endpoint(pg_backend):
    d1, j1, _ = pg_backend.admit_request("checkpoint", "checkpoint", ["EP-1"])
    assert d1 == CoordinatorDecision.ADMITTED

    d2, j2, _ = pg_backend.admit_request("checkpoint", "checkpoint", ["EP-2"])
    assert d2 == CoordinatorDecision.REJECTED_BUDGET

    pg_backend.release(j1.job_id)
    d3, j3, _ = pg_backend.admit_request("checkpoint", "checkpoint", ["EP-2"])
    assert d3 == CoordinatorDecision.ADMITTED
    pg_backend.release(j3.job_id)


@requires_postgres
def test_postgres_budget_is_independent_per_vendor(pg_backend):
    d1, j1, _ = pg_backend.admit_request("checkpoint", "checkpoint", ["EP-1"])
    d2, j2, _ = pg_backend.admit_request("paloalto", "pan-config", ["EP-2"])
    assert d1 == CoordinatorDecision.ADMITTED
    assert d2 == CoordinatorDecision.ADMITTED
    pg_backend.release(j1.job_id)
    pg_backend.release(j2.job_id)


@requires_postgres
def test_postgres_wait_for_terminal_observes_release_from_another_thread(pg_backend):
    decision, job, _ = pg_backend.admit_request("checkpoint", "checkpoint", ["EP-1"])
    assert decision == CoordinatorDecision.ADMITTED

    def _release_soon():
        time.sleep(0.3)
        pg_backend.release(job.job_id)

    threading.Thread(target=_release_soon).start()
    terminal = pg_backend.wait_for_terminal(job.job_id, timeout=5)
    assert terminal is not None
    assert terminal.status == JobStatus.COMPLETED.value


@requires_postgres
def test_postgres_cross_process_exclusion_and_orphan_reclaim_on_crash(pg_backend):
    """AC-1 / AC-3: real multi-process exclusion and crash reclamation.

    Threads cannot prove this — the property under test is that a *second
    OS process*, sharing only the database, cannot open a second admission
    for the same endpoint, and that a SIGKILL'd holder's exclusion is
    reclaimed with no TTL and no operator action.
    """
    dsn = _postgres_dsn()
    child_code = (
        "import sys, time\n"
        "from utils.coordinator_backend import PostgresCoordinatorBackend\n"
        "b = PostgresCoordinatorBackend(sys.argv[1], sys.argv[2].encode())\n"
        "d, j, _ = b.admit_request('checkpoint', 'checkpoint', ['EP-CROSS-PROCESS'])\n"
        "print(f'{d.value} {j.job_id}', flush=True)\n"
        "time.sleep(30)\n"
    )
    child = subprocess.Popen(
        [sys.executable, "-c", child_code, dsn, _TEST_SECRET.decode()],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        cwd=os.getcwd(),
    )
    try:
        line = child.stdout.readline().strip()
        assert line, f"child produced no output; stderr={child.stderr.read()}"
        child_decision, child_job_id = line.split()
        assert child_decision == "admitted"

        # Second, independent-in-process backend instance simulates a second
        # worker: same database, no shared Python state with the child.
        decision, job, active = pg_backend.admit_request("checkpoint", "checkpoint", ["EP-CROSS-PROCESS"])
        assert decision == CoordinatorDecision.COALESCED
        assert active.job_id == child_job_id

        child.kill()
        child.wait(timeout=5)

        # No TTL, no heartbeat: reclamation is immediate because PostgreSQL
        # itself released the advisory lock when the child's connection died.
        deadline = time.monotonic() + 5
        decision = None
        while time.monotonic() < deadline:
            decision, job, _ = pg_backend.admit_request("checkpoint", "checkpoint", ["EP-CROSS-PROCESS"])
            if decision == CoordinatorDecision.ADMITTED:
                break
            time.sleep(0.2)
        assert decision == CoordinatorDecision.ADMITTED

        orphaned = pg_backend.get_job(child_job_id)
        assert orphaned.status == JobStatus.ORPHANED.value
        pg_backend.release(job.job_id)
    finally:
        if child.poll() is None:
            child.kill()
            child.wait(timeout=5)


@requires_postgres
def test_postgres_scheduler_lock_rejects_second_concurrent_holder(pg_backend):
    """Correctness contract item 6: the scheduler must not dispatch the same
    due workflow from two workers in one interval — proven with a real
    second process holding the scheduler-wide gate, not a mock."""
    from utils.coordinator_backend import SchedulerLockUnavailable

    dsn = _postgres_dsn()
    child_code = (
        "import sys, time\n"
        "from utils.coordinator_backend import try_acquire_scheduler_lock\n"
        "with try_acquire_scheduler_lock(sys.argv[1], sys.argv[2].encode()):\n"
        "    print('locked', flush=True)\n"
        "    time.sleep(5)\n"
    )
    child = subprocess.Popen(
        [sys.executable, "-c", child_code, dsn, _TEST_SECRET.decode()],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        line = child.stdout.readline().strip()
        assert line == "locked", f"child did not report holding the lock; stderr={child.stderr.read()}"

        with pytest.raises(SchedulerLockUnavailable):
            with pg_backend.scheduler_lock():
                pytest.fail("must not enter the gated block while another process holds the lock")
    finally:
        child.kill()
        child.wait(timeout=5)

    # After the child releases (killed), the lock must become available again.
    with pg_backend.scheduler_lock():
        pass
