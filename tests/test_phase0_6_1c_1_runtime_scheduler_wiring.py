"""Production wiring tests for 0.6.1C.1 runtime admission and scheduler one-shot."""
from __future__ import annotations

import json
import threading
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest

from utils.collection_executor import (
    CollectionAdmissionError,
    CoordinatorDecision,
    Provenance,
    RuntimeCollectionServices,
    ScheduledWorkflow,
    SchedulerPolicy,
    SchedulerPolicyError,
    execute_admitted_collection,
    load_scheduler_policy,
    load_scheduler_state,
)
from utils.run_context import RunContext


def _write_policy(root: Path, value: dict) -> None:
    path = root / "state" / "scheduler_policy.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def _runtime_paths(root: Path):
    return SimpleNamespace(
        repository_root=Path(__file__).resolve().parents[1],
        runtime_root=root,
        data_root=root / "data",
        output_root=root / "output",
        logs_root=root / "logs",
    )


def test_execute_admitted_collection_runs_operation_and_releases():
    services = RuntimeCollectionServices()
    calls = []

    result = execute_admitted_collection(
        services,
        vendor="checkpoint",
        workflow_scope="cp",
        canonical_ids=["CP-MGMT-TEST-01"],
        provenance=Provenance.MANUAL.value,
        operation=lambda: calls.append("called") or {"status": "ok"},
    )

    assert result == {"status": "ok"}
    assert calls == ["called"]
    job = services.coordinator.all_jobs()[0]
    assert job.status == "completed"
    assert services.coordinator.active_jobs() == []


def test_execute_admitted_collection_coalesces_without_second_operation():
    services = RuntimeCollectionServices()
    decision, active = services.coordinator.admit(
        "checkpoint", "cp", ["CP-MGMT-TEST-02"]
    )
    assert decision == CoordinatorDecision.ADMITTED
    calls = []

    with pytest.raises(CollectionAdmissionError) as exc:
        execute_admitted_collection(
            services,
            vendor="checkpoint",
            workflow_scope="cp-config",
            canonical_ids=["CP-MGMT-TEST-02"],
            provenance=Provenance.SCHEDULED.value,
            operation=lambda: calls.append("forbidden"),
        )

    assert exc.value.decision == CoordinatorDecision.COALESCED
    assert exc.value.job.coalesced_to == active.job_id
    assert calls == []
    services.coordinator.release(active.job_id)


def test_execute_admitted_collection_failure_releases_exactly_once():
    services = RuntimeCollectionServices()

    def fail_operation():
        raise RuntimeError("synthetic_failure")

    with pytest.raises(RuntimeError, match="synthetic_failure"):
        execute_admitted_collection(
            services,
            vendor="paloalto",
            workflow_scope="pan-config",
            canonical_ids=["PAN-MGMT-TEST-01"],
            provenance=Provenance.MANUAL.value,
            operation=fail_operation,
        )

    failed = services.coordinator.all_jobs()[0]
    assert failed.status == "failed"
    assert services.coordinator.active_jobs() == []
    # Releasing an already-terminal job is a safe no-op and cannot over-release.
    services.coordinator.release(failed.job_id)
    next_decision, next_job = services.coordinator.admit(
        "paloalto", "pan-config", ["PAN-MGMT-TEST-01"]
    )
    assert next_decision == CoordinatorDecision.ADMITTED
    services.coordinator.release(next_job.job_id)


def test_coordinator_wait_for_terminal_observes_active_completion():
    services = RuntimeCollectionServices()
    decision, job = services.coordinator.admit(
        "checkpoint", "cp", ["CP-MGMT-TEST-05"]
    )
    assert decision == CoordinatorDecision.ADMITTED

    thread = threading.Thread(target=lambda: services.coordinator.release(job.job_id))
    thread.start()
    terminal = services.coordinator.wait_for_terminal(job.job_id, timeout=1)
    thread.join()

    assert terminal is not None
    assert terminal.status == "completed"


def test_admission_writes_value_free_run_context_metadata(tmp_path):
    services = RuntimeCollectionServices()
    ctx = RunContext.create(data_root=tmp_path / "data", output_root=tmp_path / "output")

    execute_admitted_collection(
        services,
        vendor="checkpoint",
        workflow_scope="vsx",
        canonical_ids=["CP-MGMT-TEST-03"],
        provenance=Provenance.SCHEDULED.value,
        operation=lambda: None,
        run_context=ctx,
    )

    manifest = json.loads(ctx.manifest_path.read_text(encoding="utf-8"))
    assert manifest["provenance"] == "scheduled"
    assert manifest["coordinator_decision"] == "admitted"
    assert manifest["effective_scope"] == "vsx"
    assert "CP-MGMT-TEST-03" not in json.dumps(manifest)


def test_duplicate_scheduler_workflow_fails_closed(tmp_path):
    _write_policy(
        tmp_path,
        {
            "version": 1,
            "enabled": True,
            "schedule": [
                {"workflow": "cp", "interval_minutes": 60},
                {"workflow": "cp", "interval_minutes": 120},
            ],
        },
    )

    with pytest.raises(SchedulerPolicyError, match="duplicate workflow"):
        load_scheduler_policy(tmp_path)


def test_scheduler_once_missing_policy_exits_before_prompt_or_network(tmp_path, monkeypatch, capsys):
    import main as main_module

    runtime_root = tmp_path / "runtime"
    monkeypatch.setattr(
        "builtins.input",
        lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("prompt forbidden")),
    )

    main_module.main(["--runtime-root", str(runtime_root), "--scheduler-once"])

    output = capsys.readouterr().out
    assert "no jobs produced and no network access performed" in output


def test_scheduler_once_malformed_policy_fails_before_prompt_or_network(tmp_path, monkeypatch):
    import main as main_module

    runtime_root = tmp_path / "runtime"
    policy = runtime_root / "data" / "state" / "scheduler_policy.json"
    policy.parent.mkdir(parents=True)
    policy.write_text("NOT_JSON", encoding="utf-8")
    monkeypatch.setattr(
        "builtins.input",
        lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("prompt forbidden")),
    )

    with pytest.raises(SystemExit) as exc:
        main_module.main(["--runtime-root", str(runtime_root), "--scheduler-once"])

    assert exc.value.code == 2


def test_scheduler_once_dispatches_due_workflow_once_and_persists_state(tmp_path, monkeypatch):
    import main as main_module

    paths = _runtime_paths(tmp_path / "runtime")
    for path in (paths.runtime_root, paths.data_root, paths.output_root, paths.logs_root):
        path.mkdir(parents=True, exist_ok=True)
    services = RuntimeCollectionServices(
        scheduler_policy=SchedulerPolicy(
            source="runtime-policy",
            enabled=True,
            workflows=(ScheduledWorkflow(workflow="cp", interval_minutes=60),),
        )
    )
    dispatches = []

    def fake_main(argv=None, *, runtime_services=None, provenance="manual", admission_run_context=None):
        dispatches.append((list(argv or []), provenance))
        return execute_admitted_collection(
            runtime_services,
            vendor="checkpoint",
            workflow_scope="cp",
            canonical_ids=["CP-MGMT-TEST-04"],
            provenance=provenance,
            operation=lambda: {"status": "ok"},
            run_context=admission_run_context,
        )

    monkeypatch.setattr(main_module, "main", fake_main)

    first = main_module._run_scheduler_once(paths, services)
    second = main_module._run_scheduler_once(paths, services)

    assert first == [{"workflow": "cp", "status": "completed"}]
    assert second == []
    assert len(dispatches) == 1
    assert dispatches[0][1] == "scheduled"
    state = load_scheduler_state(paths.data_root)
    assert state["cp"] <= datetime.now(timezone.utc)
    manifests = list((paths.data_root / "runs").glob("*/manifest.json"))
    assert len(manifests) == 1
    manifest = json.loads(manifests[0].read_text(encoding="utf-8"))
    assert manifest["provenance"] == "scheduled"
    assert manifest["coordinator_decision"] == "admitted"
    assert manifest["scheduler_result"] == "completed"
    assert "CP-MGMT-TEST-04" not in json.dumps(manifest)


def test_main_cp_path_uses_shared_admission_and_runtime_observability(tmp_path, monkeypatch):
    import main as main_module
    import checkpoint.cp_runner as cp_runner
    import utils.html_export as html_module
    import utils.merge as merge_module

    runtime_root = tmp_path / "runtime"
    output_root = runtime_root / "output"
    output_root.mkdir(parents=True)
    for name in ("vsx.json", "panorama_runtime.json"):
        (output_root / name).write_text("[]", encoding="utf-8")
    services = RuntimeCollectionServices()
    html_args = {}

    def fake_cp(cfg, *, exclude_vsx=False):
        (output_root / "cp.json").write_text("[]", encoding="utf-8")
        assert exclude_vsx is True
        return {"summary": {}}

    def fake_merge(*args, **kwargs):
        (output_root / "unified.json").write_text("[]", encoding="utf-8")

    def fake_html(*args, **kwargs):
        html_args.update(kwargs)

    prompts = iter(["CP-MGMT-TEST-06", "synthetic-user"])
    monkeypatch.setattr("builtins.input", lambda prompt="": next(prompts))
    monkeypatch.setattr("getpass.getpass", lambda prompt="": "synthetic-secret")
    monkeypatch.setattr(cp_runner, "run_cp", fake_cp)
    monkeypatch.setattr(merge_module, "run_merge", fake_merge)
    monkeypatch.setattr(html_module, "run_html_export", fake_html)

    main_module.main(
        ["--runtime-root", str(runtime_root), "--only", "cp"],
        runtime_services=services,
    )

    jobs = services.coordinator.all_jobs()
    assert len(jobs) == 1
    assert jobs[0].workflow_scope == "cp"
    assert jobs[0].status == "completed"
    assert html_args["coordinator"] is services.coordinator
    assert html_args["lifecycle_store"] is services.lifecycle_store
    assert html_args["capability_store"] is services.capability_store


def test_main_cp_config_probe_uses_shared_admission(tmp_path, monkeypatch):
    import main as main_module
    import configuration.checkpoint_config_probe as probe_module

    runtime_root = tmp_path / "runtime"
    services = RuntimeCollectionServices()
    calls = []

    def fake_probe(cfg):
        calls.append("probe")
        return {"summary": {}}

    prompts = iter(["CP-MGMT-TEST-07", "synthetic-user"])
    monkeypatch.setattr("builtins.input", lambda prompt="": next(prompts))
    monkeypatch.setattr("getpass.getpass", lambda prompt="": "synthetic-secret")
    monkeypatch.setattr(probe_module, "run_checkpoint_config_probe", fake_probe)

    main_module.main(
        ["--runtime-root", str(runtime_root), "--cp-config-probe"],
        runtime_services=services,
    )

    assert calls == ["probe"]
    jobs = services.coordinator.all_jobs()
    assert len(jobs) == 1
    assert jobs[0].workflow_scope == "cp-config-probe"
    assert jobs[0].status == "completed"
