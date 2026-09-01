"""CON.2 acceptance criteria — console job engine and read-class actions.

See docs/history/phase/CON_2_CONSOLE_JOB_ENGINE_READ_ACTIONS.md. Every AC-N
docstring below names the acceptance criterion it asserts. No test contacts a
device: every job execution here patches ``main.main`` (AC-2 — the runner
never calls a collector or vendor module directly, only ``main()``).
"""
from __future__ import annotations

import json
import shutil
import time
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import pytest

pytestmark = pytest.mark.configuration

REPO_ROOT = Path(__file__).resolve().parents[1]
FIXTURE = REPO_ROOT / "tests" / "fixtures" / "uitest"


def _load_fixture(name: str):
    return json.loads((FIXTURE / name).read_text(encoding="utf-8"))


@pytest.fixture()
def uitest_runtime_paths(tmp_path, monkeypatch):
    output_root = tmp_path / "output"
    data_root = tmp_path / "data"
    (data_root / "state").mkdir(parents=True)
    output_root.mkdir(parents=True)
    for f in (FIXTURE / "state").iterdir():
        shutil.copy2(f, data_root / "state" / f.name)
    (output_root / "unified.json").write_text(
        json.dumps(_load_fixture("unified.json")), encoding="utf-8"
    )

    import utils.html_export as html_export
    monkeypatch.setattr(html_export, "build_configuration_ui_payload", lambda *a, **k: _load_fixture("configuration_ui.json"))
    monkeypatch.setattr(html_export, "build_crypto_posture", lambda *a, **k: _load_fixture("crypto_ui.json"))
    monkeypatch.setattr(html_export, "build_discovery_capability_payload", lambda *a, **k: _load_fixture("discovery_ui.json"))

    return SimpleNamespace(
        repository_root=REPO_ROOT,
        output_root=output_root,
        data_root=data_root,
        runtime_root=tmp_path,
    )


@pytest.fixture()
def console_env(uitest_runtime_paths):
    from console.jobs import ConsoleJobStore
    from console.runner import ConsoleJobRunner
    from console.app import create_app
    from utils.collection_executor import CollectionCoordinator, RuntimeCollectionServices

    services = RuntimeCollectionServices(coordinator=CollectionCoordinator())
    job_store = ConsoleJobStore(uitest_runtime_paths.data_root)
    runner = ConsoleJobRunner(job_store=job_store, runtime_paths=uitest_runtime_paths, services=services)
    runner.start()

    token = "test-launch-token"
    app = create_app(
        runtime_paths=uitest_runtime_paths,
        launch_token=token,
        bound_origin="http://127.0.0.1:8765",
        job_store=job_store,
        runner=runner,
    )
    from fastapi.testclient import TestClient

    client = TestClient(app, base_url="http://127.0.0.1:8765")
    return SimpleNamespace(
        client=client, token=token, job_store=job_store, runner=runner, services=services,
        runtime_paths=uitest_runtime_paths,
    )


def _headers(env, **extra):
    return {"Authorization": f"Bearer {env.token}", **extra}


def _post_job(env, job_type, targets=None, idem="idem-key-1"):
    return env.client.post(
        "/api/jobs",
        headers=_headers(env, **{"Idempotency-Key": idem}),
        json={"job_type": job_type, "targets": targets or []},
    )


def _wait_terminal(env, job_id, timeout=5.0):
    from console.jobs import TERMINAL_STATES

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        record = env.job_store.get(job_id)
        if record is not None and record.state in TERMINAL_STATES:
            return record
        time.sleep(0.05)
    raise AssertionError(f"job {job_id} did not reach a terminal state within {timeout}s")


# --- AC-7: shared argv construction -----------------------------------------

@pytest.mark.parametrize("workflow", ["checkpoint", "cp", "vsx", "pan-config", "cp-config", "recovery-pan"])
def test_ac7_workflow_argv_matches_scheduler_wrapper(workflow):
    from utils.collection_executor import workflow_argv
    from application.workflows.maintenance import _scheduler_workflow_argv

    class _Row:
        pass

    row = _Row()
    row.workflow = workflow
    row.targets = ()
    assert _scheduler_workflow_argv(row, "R") == workflow_argv(workflow, "R", targets=())


def test_ac7_workflow_argv_passes_targets_through():
    from utils.collection_executor import workflow_argv

    argv = workflow_argv("recovery-pan", "R", targets=("fw-01", "fw-02"))
    assert argv[-2:] == ["--recovery-gateways", "fw-01,fw-02"]


# --- AC-8: Provenance.CONSOLE is additive ------------------------------------

def test_ac8_provenance_console_value():
    from utils.coordinator_backend import Provenance

    assert Provenance.CONSOLE.value == "console"
    # Job/RunContext both store provenance as a free string (no fixed-value
    # validation anywhere in this codebase) -- the enumeration itself is the
    # implementation-notes record required by C2-3/AC-8.
    from utils.coordinator_backend import Job

    job = Job(job_id="j1", vendor="checkpoint", workflow_scope="cp", provenance=Provenance.CONSOLE.value)
    assert job.to_manifest_dict()["provenance"] == "console"


# --- AC-1/AC-2: a read job runs through main(), reaches a terminal state ----

def test_ac1_ac2_read_job_runs_through_main_with_console_provenance(console_env):
    calls = []

    def fake_main(argv, **kwargs):
        calls.append((argv, kwargs.get("provenance")))
        return None

    with mock.patch("main.main", side_effect=fake_main):
        response = _post_job(console_env, "report_rebuild")
        assert response.status_code == 200
        job_id = response.json()["job_id"]
        record = _wait_terminal(console_env, job_id)

    assert record.state == "succeeded"
    assert record.run_id is not None
    assert len(calls) == 1
    argv, provenance = calls[0]
    assert provenance == "console"
    assert "--render-only" in argv


def test_ac2_runner_never_imports_a_vendor_or_collector_module_directly(console_env):
    """Patching main.main to a no-op must fully prevent any device path from
    executing; if the runner called a collector directly this would still
    "succeed" without main ever having been invoked."""
    with mock.patch("main.main") as fake_main:
        fake_main.return_value = None
        response = _post_job(console_env, "inventory_refresh_cp")
        job_id = response.json()["job_id"]
        record = _wait_terminal(console_env, job_id)
    fake_main.assert_called_once()
    assert record.state == "succeeded"


# --- AC-3: rejections before any execution ----------------------------------

def test_ac3_unknown_job_type_is_400(console_env):
    with mock.patch("main.main"):
        response = _post_job(console_env, "does_not_exist")
    assert response.status_code == 400


def test_ac3_unresolvable_entity_id_is_400(console_env):
    with mock.patch("main.main"):
        response = _post_job(console_env, "recovery_attest_cp", targets=["ghost-device"])
    assert response.status_code == 400


def test_ac3_missing_idempotency_key_is_400(console_env):
    response = console_env.client.post(
        "/api/jobs", headers=_headers(console_env), json={"job_type": "report_rebuild"}
    )
    assert response.status_code == 400


def test_ac3_operational_write_is_409(console_env):
    # A resolvable entity_id -- 409 must come from the action-class gate
    # (C2-6), not be masked by an unrelated 400 from entity_id validation.
    #
    # The refusal code names the class. cp_gaia_backup is a CLASS 1 controlled
    # recovery write: it is permitted product-wide (the CLI runs it under the
    # RB.x ledger contracts) but is not console-submittable, which is a
    # different statement from the old catch-all "operational_write_not_enabled".
    # A future CLASS 2 failover job must not report the same reason as a backup.
    response = _post_job(console_env, "cp_gaia_backup", targets=["cp-core-01"])
    assert response.status_code == 409
    detail = response.json()["detail"]
    assert detail["error"] == "recovery_write_not_console_submittable"
    assert detail["action_class"] == "recovery-write"


def test_ac3_missing_or_invalid_token_is_401(console_env):
    response = console_env.client.get("/api/jobs", headers={"Authorization": "Bearer WRONG"})
    assert response.status_code == 401


def test_ac3_origin_mismatch_is_403(console_env):
    response = console_env.client.get(
        "/api/jobs", headers={**_headers(console_env), "Origin": "http://evil.example"}
    )
    assert response.status_code == 403


# --- AC-4: idempotency ------------------------------------------------------

def test_ac4_repeated_idempotency_key_returns_original_job(console_env):
    with mock.patch("main.main"):
        first = _post_job(console_env, "report_rebuild", idem="same-key")
        second = _post_job(console_env, "report_rebuild", idem="same-key")
    assert first.json()["job_id"] == second.json()["job_id"]
    assert len(console_env.job_store.list_all()) == 1


# --- AC-5: forbidden fields never appear in a job record --------------------

FORBIDDEN_SUBSTRINGS = ("password", "secret", "token", "management_ip", "10.0.")


def test_ac5_job_record_has_no_forbidden_fields(console_env):
    with mock.patch("main.main"):
        response = _post_job(console_env, "report_rebuild", idem="ac5-key")
        job_id = response.json()["job_id"]
        _wait_terminal(console_env, job_id)
    record_dict = console_env.job_store.get(job_id).to_dict()
    allowed_fields = {
        "job_id", "idempotency_key", "job_type", "command_class", "targets", "state",
        "requested_at", "started_at", "finished_at", "run_id", "coordinator_decision",
        "outcome_counts", "error_code", "error_summary",
    }
    assert set(record_dict.keys()) == allowed_fields
    serialized = json.dumps(record_dict).lower()
    for needle in FORBIDDEN_SUBSTRINGS:
        assert needle not in serialized


def test_ac5_failed_job_record_has_no_stack_trace(console_env):
    def raising_main(argv, **kwargs):
        raise RuntimeError("device unreachable during render")

    with mock.patch("main.main", side_effect=raising_main):
        response = _post_job(console_env, "report_rebuild", idem="ac5-fail-key")
        job_id = response.json()["job_id"]
        record = _wait_terminal(console_env, job_id)
    assert record.state == "failed"
    assert "Traceback" not in (record.error_summary or "")
    assert len((record.error_summary or "")) <= 500


# --- AC-6: crash recovery ----------------------------------------------------

def test_ac6_runner_exception_yields_terminal_failed_with_error_code(console_env):
    def raising_main(argv, **kwargs):
        raise ValueError("boom")

    with mock.patch("main.main", side_effect=raising_main):
        response = _post_job(console_env, "report_rebuild", idem="ac6-key")
        job_id = response.json()["job_id"]
        record = _wait_terminal(console_env, job_id)
    assert record.state == "failed"
    assert record.error_code == "ValueError"


def test_ac6_console_restart_marks_orphaned_running_as_failed(console_env):
    from console.jobs import ConsoleJobStore

    record, _ = console_env.job_store.submit(
        job_type="report_rebuild", command_class="read", targets=[], idempotency_key="orphan-key"
    )
    console_env.job_store.mark_running(record.job_id)

    # Simulate a fresh process attaching to the same data_root.
    fresh_store = ConsoleJobStore(console_env.runtime_paths.data_root)
    swept = fresh_store.sweep_orphaned_running()

    assert record.job_id in swept
    final = fresh_store.get(record.job_id)
    assert final.state == "failed"
    assert final.error_code == "console_restarted"


# --- AC-9: concurrent submissions serialize through the coordinator --------

def test_ac9_two_jobs_both_reach_a_terminal_state(console_env):
    with mock.patch("main.main"):
        first = _post_job(console_env, "inventory_refresh_cp", idem="concurrent-1")
        second = _post_job(console_env, "inventory_refresh_vsx", idem="concurrent-2")
        r1 = _wait_terminal(console_env, first.json()["job_id"])
        r2 = _wait_terminal(console_env, second.json()["job_id"])
    assert r1.state == "succeeded"
    assert r2.state == "succeeded"


# --- AC-10: SSE carries state only, terminates on terminal state -----------

def test_ac10_events_stream_terminates_and_carries_no_collector_output(console_env):
    with mock.patch("main.main"):
        response = _post_job(console_env, "report_rebuild", idem="sse-key")
        job_id = response.json()["job_id"]
        _wait_terminal(console_env, job_id)

        with console_env.client.stream("GET", f"/api/jobs/{job_id}/events", headers=_headers(console_env)) as stream:
            assert stream.status_code == 200
            body = "".join(stream.iter_text())

    assert "succeeded" in body
    for needle in FORBIDDEN_SUBSTRINGS:
        assert needle not in body.lower()


def test_ac10_events_stream_404_for_unknown_job(console_env):
    response = console_env.client.get("/api/jobs/does-not-exist/events", headers=_headers(console_env))
    assert response.status_code == 404


# --- C2-1: registry is closed and declares non-read classes as blocked -----

def test_c2_1_job_types_endpoint_declares_operational_write_as_blocked(console_env):
    response = console_env.client.get("/api/job-types", headers=_headers(console_env))
    assert response.status_code == 200
    by_id = {jt["id"]: jt for jt in response.json()}
    assert by_id["cp_gaia_backup"]["blocked"] is True
    assert by_id["cp_gaia_backup"]["blocked_reason"] == "recovery_write_not_console_submittable"
    assert by_id["cp_gaia_backup"]["action_class"] == "recovery-write"
    assert by_id["cp_gaia_backup"]["action_class_level"] == 1
    assert by_id["report_rebuild"]["blocked"] is False
    assert by_id["report_rebuild"]["action_class"] == "read"
    assert by_id["report_rebuild"]["action_class_level"] == 0


def test_console_submits_nothing_above_class_0(console_env):
    """The console's standing guarantee, asserted over the whole registry
    rather than one job type: every submittable entry is CLASS 0. This is the
    invariant that must survive OP.x adding a CLASS 2 failover job type."""
    from console.registry import JOB_REGISTRY

    response = console_env.client.get("/api/job-types", headers=_headers(console_env))
    by_id = {jt["id"]: jt for jt in response.json()}
    for job_id, job_type in JOB_REGISTRY.items():
        submittable = by_id[job_id]["blocked"] is False
        assert submittable == (job_type.action_class.level == 0), (
            f"{job_id}: only CLASS 0 may be submittable from the console"
        )


# --- AC-11: static report still contains no action surface ------------------

def test_ac11_console_actions_js_is_not_part_of_module_composition_order():
    from utils.html_export import MODULE_ORDER

    assert not any("console_actions" in name for name in MODULE_ORDER)
