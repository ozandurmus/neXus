"""Tests for collection execution coordinator and scheduler policy — 0.6.1C."""
import json
import threading
from datetime import datetime, timezone, timedelta
from pathlib import Path

import pytest

from utils.collection_executor import (
    ALLOWLISTED_WORKFLOWS,
    CoordinatorDecision,
    CollectionCoordinator,
    JobStatus,
    Provenance,
    SchedulerPolicyError,
    load_scheduler_policy,
    is_workflow_due,
)
from utils import run_context

pytestmark = pytest.mark.discovery


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_coordinator() -> CollectionCoordinator:
    return CollectionCoordinator()


# ---------------------------------------------------------------------------
# Atomic physical endpoint lock
# ---------------------------------------------------------------------------

def test_admit_manual_job_returns_admitted():
    coord = _make_coordinator()
    decision, job = coord.admit("checkpoint", "checkpoint", ["DEV-A"])
    assert decision == CoordinatorDecision.ADMITTED
    assert job.status == JobStatus.RUNNING.value
    assert job.provenance == Provenance.MANUAL.value


def test_release_frees_endpoint_for_next_job():
    coord = _make_coordinator()
    _, job1 = coord.admit("checkpoint", "checkpoint", ["DEV-B"])
    coord.release(job1.job_id)
    decision2, _ = coord.admit("checkpoint", "checkpoint", ["DEV-B"])
    assert decision2 == CoordinatorDecision.ADMITTED


def test_two_jobs_same_endpoint_coalesce():
    coord = _make_coordinator()
    _, job1 = coord.admit("checkpoint", "checkpoint", ["DEV-C"])
    assert job1.status == JobStatus.RUNNING.value

    decision2, job2 = coord.admit("checkpoint", "checkpoint", ["DEV-C"])
    # Second request must coalesce; no second device connection opened.
    assert decision2 == CoordinatorDecision.COALESCED
    assert job2.job_id == job1.job_id  # returned the existing job
    # Coalesced request should not appear as a new running job.
    running = coord.active_jobs()
    assert len(running) == 1


def test_coalesce_is_recorded_in_jobs():
    coord = _make_coordinator()
    _, job1 = coord.admit("checkpoint", "checkpoint", ["DEV-D"])
    _, _ = coord.admit("checkpoint", "checkpoint", ["DEV-D"])
    # At least two entries: the running job and the coalesced entry.
    all_jobs = coord.all_jobs()
    coalesced = [j for j in all_jobs if j.status == JobStatus.COALESCED.value]
    assert len(coalesced) == 1
    assert coalesced[0].coalesced_to == job1.job_id


def test_different_endpoints_admitted_independently():
    coord = _make_coordinator()
    d1, _ = coord.admit("checkpoint", "checkpoint", ["DEV-E"])
    d2, _ = coord.admit("checkpoint", "checkpoint", ["DEV-F"])
    # CP budget is 1 — second admission should hit budget limit.
    assert d1 == CoordinatorDecision.ADMITTED
    assert d2 == CoordinatorDecision.REJECTED_BUDGET


def test_concurrent_admit_same_endpoint_only_one_admitted():
    coord = _make_coordinator()
    results = []
    barrier = threading.Barrier(2)

    def _worker():
        barrier.wait()
        decision, _ = coord.admit("checkpoint", "checkpoint", ["DEV-G"])
        results.append(decision)

    threads = [threading.Thread(target=_worker) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    admitted = results.count(CoordinatorDecision.ADMITTED)
    assert admitted == 1


# ---------------------------------------------------------------------------
# CP / PAN / VSX budget isolation
# ---------------------------------------------------------------------------

def test_checkpoint_budget_exhausted_blocks_second_cp_job():
    coord = _make_coordinator()
    d1, _ = coord.admit("checkpoint", "checkpoint", ["DEV-H1"])
    d2, _ = coord.admit("checkpoint", "checkpoint", ["DEV-H2"])
    assert d1 == CoordinatorDecision.ADMITTED
    assert d2 == CoordinatorDecision.REJECTED_BUDGET


def test_paloalto_budget_independent_of_checkpoint():
    coord = _make_coordinator()
    d_cp, _ = coord.admit("checkpoint", "checkpoint", ["DEV-I"])
    d_pan, _ = coord.admit("paloalto", "pan-config", ["PAN-DEV-J"])
    assert d_cp == CoordinatorDecision.ADMITTED
    assert d_pan == CoordinatorDecision.ADMITTED


def test_vsx_budget_independent_of_cp_base():
    coord = _make_coordinator()
    # VSX uses "checkpoint_vsx" budget key (separate from "checkpoint").
    d_vsx, _ = coord.admit("checkpoint", "vsx", ["VSX-K"])
    d_cp,  _ = coord.admit("checkpoint", "checkpoint", ["DEV-K2"])
    assert d_vsx == CoordinatorDecision.ADMITTED
    assert d_cp == CoordinatorDecision.ADMITTED


# ---------------------------------------------------------------------------
# Cancellation
# ---------------------------------------------------------------------------

def test_cancel_running_job_succeeds():
    coord = _make_coordinator()
    _, job = coord.admit("checkpoint", "checkpoint", ["DEV-L"])
    cancelled = coord.cancel(job.job_id)
    assert cancelled is True
    assert coord.get_job(job.job_id).status == JobStatus.CANCELLED.value
    assert job.cancel_event.is_set()


def test_cancel_nonexistent_job_returns_false():
    coord = _make_coordinator()
    assert coord.cancel("nonexistent-job-id") is False


def test_cancelled_job_frees_budget_for_next():
    coord = _make_coordinator()
    _, job1 = coord.admit("checkpoint", "checkpoint", ["DEV-M"])
    coord.cancel(job1.job_id)
    d2, _ = coord.admit("checkpoint", "checkpoint", ["DEV-M"])
    assert d2 == CoordinatorDecision.ADMITTED


# ---------------------------------------------------------------------------
# Fail
# ---------------------------------------------------------------------------

def test_fail_releases_budget():
    coord = _make_coordinator()
    _, job = coord.admit("checkpoint", "checkpoint", ["DEV-N"])
    coord.fail(job.job_id, "timeout")
    assert coord.get_job(job.job_id).status == JobStatus.FAILED.value
    d2, _ = coord.admit("checkpoint", "checkpoint", ["DEV-N"])
    assert d2 == CoordinatorDecision.ADMITTED


# ---------------------------------------------------------------------------
# Job manifest — no secrets
# ---------------------------------------------------------------------------

def test_job_manifest_dict_has_no_canonical_ids():
    coord = _make_coordinator()
    _, job = coord.admit("checkpoint", "checkpoint", ["REAL-DEVICE-NAME-SECRET"])
    manifest = job.to_manifest_dict()
    # canonical_ids must NOT appear in the manifest dict.
    assert "canonical_ids" not in manifest
    assert "REAL-DEVICE-NAME-SECRET" not in str(manifest)


def test_job_manifest_dict_contains_provenance_and_status():
    coord = _make_coordinator()
    _, job = coord.admit("paloalto", "pan-config", ["PAN-DEV-O"],
                         provenance=Provenance.SCHEDULED.value)
    d = job.to_manifest_dict()
    assert d["provenance"] == Provenance.SCHEDULED.value
    assert d["status"] == JobStatus.RUNNING.value
    assert d["job_id"] == job.job_id


# ---------------------------------------------------------------------------
# RunContext manifest extension — 0.6.1C
# ---------------------------------------------------------------------------

def test_run_context_set_job_metadata_written_to_manifest(tmp_path, monkeypatch):
    output_dir = tmp_path / "output"
    runs_dir   = tmp_path / "data" / "runs"
    output_dir.mkdir(parents=True)
    monkeypatch.setattr(run_context, "OUTPUT_DIR", output_dir)
    monkeypatch.setattr(run_context, "RUNS_DIR", runs_dir)
    monkeypatch.setattr(run_context, "info", lambda _: None)

    ctx = run_context.RunContext.create()
    ctx.set_job_metadata(
        job_id="job_test01",
        provenance=Provenance.SCHEDULED.value,
        coordinator_decision=CoordinatorDecision.ADMITTED.value,
    )

    manifest = json.loads(ctx.manifest_path.read_text(encoding="utf-8"))
    assert manifest["job_id"] == "job_test01"
    assert manifest["provenance"] == Provenance.SCHEDULED.value
    assert manifest["coordinator_decision"] == CoordinatorDecision.ADMITTED.value
    assert "coalesced_to" not in manifest   # not set; must be absent


def test_run_context_coalesced_to_present_when_set(tmp_path, monkeypatch):
    output_dir = tmp_path / "output"
    runs_dir   = tmp_path / "data" / "runs"
    output_dir.mkdir(parents=True)
    monkeypatch.setattr(run_context, "OUTPUT_DIR", output_dir)
    monkeypatch.setattr(run_context, "RUNS_DIR", runs_dir)
    monkeypatch.setattr(run_context, "info", lambda _: None)

    ctx = run_context.RunContext.create()
    ctx.set_job_metadata("job_test02", "manual", coalesced_to="job_test01")
    manifest = json.loads(ctx.manifest_path.read_text(encoding="utf-8"))
    assert manifest["coalesced_to"] == "job_test01"


def test_run_context_without_job_metadata_omits_fields(tmp_path, monkeypatch):
    output_dir = tmp_path / "output"
    runs_dir   = tmp_path / "data" / "runs"
    output_dir.mkdir(parents=True)
    monkeypatch.setattr(run_context, "OUTPUT_DIR", output_dir)
    monkeypatch.setattr(run_context, "RUNS_DIR", runs_dir)
    monkeypatch.setattr(run_context, "info", lambda _: None)

    ctx = run_context.RunContext.create()
    manifest = json.loads(ctx.manifest_path.read_text(encoding="utf-8"))
    for field in ("job_id", "provenance", "effective_scope", "coordinator_decision", "coalesced_to"):
        assert field not in manifest


# ---------------------------------------------------------------------------
# Scheduler policy — default disabled (no policy file = no jobs)
# ---------------------------------------------------------------------------

def test_missing_policy_file_returns_none(tmp_path):
    policy = load_scheduler_policy(tmp_path)
    assert policy is None


def test_disabled_policy_returns_disabled_policy(tmp_path):
    pol_dir = tmp_path / "state"
    pol_dir.mkdir()
    (pol_dir / "scheduler_policy.json").write_text(
        json.dumps({"version": 1, "enabled": False, "schedule": []}),
        encoding="utf-8",
    )
    policy = load_scheduler_policy(tmp_path)
    assert policy is not None
    assert policy.enabled is False
    assert len(policy.workflows) == 0


def test_valid_policy_parses_correctly(tmp_path):
    pol_dir = tmp_path / "state"
    pol_dir.mkdir()
    (pol_dir / "scheduler_policy.json").write_text(
        json.dumps({
            "version": 1,
            "enabled": True,
            "schedule": [
                {"workflow": "checkpoint", "interval_minutes": 60},
                {"workflow": "pan-config", "interval_minutes": 30},
            ],
        }),
        encoding="utf-8",
    )
    policy = load_scheduler_policy(tmp_path)
    assert policy.enabled is True
    assert len(policy.workflows) == 2
    workflows = {w.workflow: w.interval_minutes for w in policy.workflows}
    assert workflows["checkpoint"] == 60
    assert workflows["pan-config"] == 30


# ---------------------------------------------------------------------------
# Malformed policy fails before network access
# ---------------------------------------------------------------------------

def test_malformed_policy_not_json_raises(tmp_path):
    pol_dir = tmp_path / "state"
    pol_dir.mkdir()
    (pol_dir / "scheduler_policy.json").write_text("NOT_JSON", encoding="utf-8")
    with pytest.raises(SchedulerPolicyError, match="cannot be read"):
        load_scheduler_policy(tmp_path)


def test_policy_wrong_version_raises(tmp_path):
    pol_dir = tmp_path / "state"
    pol_dir.mkdir()
    (pol_dir / "scheduler_policy.json").write_text(
        json.dumps({"version": 99, "enabled": True, "schedule": []}),
        encoding="utf-8",
    )
    with pytest.raises(SchedulerPolicyError, match="unsupported schema"):
        load_scheduler_policy(tmp_path)


def test_policy_non_allowlisted_workflow_raises(tmp_path):
    pol_dir = tmp_path / "state"
    pol_dir.mkdir()
    (pol_dir / "scheduler_policy.json").write_text(
        json.dumps({
            "version": 1,
            "enabled": True,
            "schedule": [{"workflow": "delete-all-rules", "interval_minutes": 60}],
        }),
        encoding="utf-8",
    )
    with pytest.raises(SchedulerPolicyError, match="non-allowlisted"):
        load_scheduler_policy(tmp_path)


def test_policy_interval_too_short_raises(tmp_path):
    pol_dir = tmp_path / "state"
    pol_dir.mkdir()
    (pol_dir / "scheduler_policy.json").write_text(
        json.dumps({
            "version": 1,
            "enabled": True,
            "schedule": [{"workflow": "checkpoint", "interval_minutes": 1}],
        }),
        encoding="utf-8",
    )
    with pytest.raises(SchedulerPolicyError, match="interval_minutes"):
        load_scheduler_policy(tmp_path)


def test_allowlisted_workflows_are_read_only():
    """Allowlisted workflows must not include any write/change operation.

    "recovery-pan" (RB.2, docs/design/BACKUP_RECOVERY_CONTRACTS.md §7.1) is
    PAN device-state export -- `read` class per the network-device command
    gate, not a write/change operation. "recovery-cp" (CP Gaia backup,
    `operational-write` class) deliberately does NOT appear here: it is
    blocked on the P0 cp_device_interaction_safety audit and open decision
    D3, and utils.collection_executor.ALLOWLISTED_WORKFLOWS is exactly the
    gate that must keep it out until both clear.
    """
    for w in ALLOWLISTED_WORKFLOWS:
        assert w in {"checkpoint", "cp", "vsx", "pan-config", "cp-config", "recovery-pan"}, \
            f"Unexpected workflow in allowlist: {w!r}"
    assert "recovery-cp" not in ALLOWLISTED_WORKFLOWS


# ---------------------------------------------------------------------------
# Scheduler due-check logic
# ---------------------------------------------------------------------------

def test_workflow_due_when_never_run():
    from utils.collection_executor import ScheduledWorkflow
    w = ScheduledWorkflow(workflow="checkpoint", interval_minutes=60)
    assert is_workflow_due(w, last_run_at=None)


def test_workflow_not_due_when_recently_run():
    from utils.collection_executor import ScheduledWorkflow
    w = ScheduledWorkflow(workflow="checkpoint", interval_minutes=60)
    recent = datetime.now(timezone.utc) - timedelta(minutes=10)
    assert not is_workflow_due(w, last_run_at=recent)


def test_workflow_due_when_interval_elapsed():
    from utils.collection_executor import ScheduledWorkflow
    w = ScheduledWorkflow(workflow="checkpoint", interval_minutes=60)
    old = datetime.now(timezone.utc) - timedelta(minutes=61)
    assert is_workflow_due(w, last_run_at=old)


def test_workflow_due_handles_naive_datetime():
    """Naive last_run_at is treated as UTC without raising."""
    from utils.collection_executor import ScheduledWorkflow
    w = ScheduledWorkflow(workflow="checkpoint", interval_minutes=60)
    naive = datetime.utcnow() - timedelta(minutes=120)
    # Must not raise; must return True (overdue).
    assert is_workflow_due(w, last_run_at=naive)
