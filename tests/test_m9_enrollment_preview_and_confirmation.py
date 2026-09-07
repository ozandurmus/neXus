"""M9 acceptance criteria — enrollment preview and confirmation.

See `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §9.1 (the
seventeen mandatory conditions) and §15.4 (`AC-EN-1`..`AC-EN-14`/`AC-RT-4`).
Every ``test_ac<N>_*`` docstring below names the condition it asserts.

No test contacts a device: every probe-job execution here patches
``utils.pre_enrollment_identity_probe.run_pre_enrollment_identity_probe`` —
the one seam `console/runner.py::_execute_enrollment_probe` calls — never a
lower-level collector primitive, matching CON.2's own
``mock.patch("main.main")`` convention for every other job type. No M8.3 or
M7 command runs anywhere in this file.
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

_PROBE_JOB_TYPE = "device_enrollment_identity_probe"
_VALID_TRUST_REF = "system_known_hosts"


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


def _wait_terminal(env, job_id, timeout=5.0):
    from console.jobs import TERMINAL_STATES

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        record = env.job_store.get(job_id)
        if record is not None and record.state in TERMINAL_STATES:
            return record
        time.sleep(0.05)
    raise AssertionError(f"job {job_id} did not reach a terminal state within {timeout}s")


def _post_probe(env, *, endpoint="198.51.100.10", vendor_hint="checkpoint",
                 credential_profile_ref="lab-cred-1", trust_profile_ref=_VALID_TRUST_REF,
                 idem="probe-idem-1", extra_body=None):
    body = {
        "endpoint": endpoint,
        "vendor_hint": vendor_hint,
        "credential_profile_ref": credential_profile_ref,
        "trust_profile_ref": trust_profile_ref,
    }
    if extra_body:
        body.update(extra_body)
    return env.client.post(
        "/api/enrollment/probe", headers=_headers(env, **{"Idempotency-Key": idem}), json=body,
    )


def _positive_outcome():
    from utils.pre_enrollment_identity_probe import POSITIVE_IDENTITY, IdentityPreview, PreEnrollmentProbeOutcome

    return PreEnrollmentProbeOutcome(
        status=POSITIVE_IDENTITY,
        preview=IdentityPreview(
            platform_family="spark", platform_label="Check Point Spark", platform_confidence="HIGH",
            model="1550", sw_version="R81.20", identity_gate_status="ACCEPTED",
            identity_gate_confidence="HIGH", ha_role=None,
        ),
    )


def _negative_outcome():
    from utils.pre_enrollment_identity_probe import IDENTITY_GATE_REJECTED, PreEnrollmentProbeOutcome

    return PreEnrollmentProbeOutcome(status=IDENTITY_GATE_REJECTED)


def _run_probe_to_terminal(env, outcome, *, endpoint="198.51.100.10", idem="probe-idem-1"):
    with mock.patch(
        "utils.pre_enrollment_identity_probe.run_pre_enrollment_identity_probe", return_value=outcome,
    ):
        response = _post_probe(env, endpoint=endpoint, idem=idem)
        assert response.status_code == 200, response.text
        job_id = response.json()["job_id"]
        record = _wait_terminal(env, job_id)
    return record


def _confirm(env, *, probe_job_id, endpoint="198.51.100.10", vendor_hint="checkpoint",
             credential_profile_ref="lab-cred-1", trust_profile_ref=_VALID_TRUST_REF,
             tags=None, candidate_id=None, confirm=True):
    body = {
        "probe_job_id": probe_job_id,
        "endpoint": endpoint,
        "vendor_hint": vendor_hint,
        "credential_profile_ref": credential_profile_ref,
        "trust_profile_ref": trust_profile_ref,
        "confirm": confirm,
    }
    if tags is not None:
        body["tags"] = tags
    if candidate_id is not None:
        body["candidate_id"] = candidate_id
    return env.client.post("/api/registry/enrollments", headers=_headers(env), json=body)


# --- condition 2 / AC-2: per-launch auth on the new routes ------------------

def test_ac2_enrollment_routes_require_bearer_token(console_env):
    response = console_env.client.post(
        "/api/enrollment/probe", headers={"Idempotency-Key": "x"},
        json={"endpoint": "198.51.100.10", "vendor_hint": "checkpoint",
              "credential_profile_ref": "c1", "trust_profile_ref": _VALID_TRUST_REF},
    )
    assert response.status_code == 401

    response = console_env.client.post(
        "/api/registry/enrollments",
        json={"probe_job_id": "x", "endpoint": "e", "vendor_hint": "checkpoint",
              "credential_profile_ref": "c1", "trust_profile_ref": _VALID_TRUST_REF, "confirm": True},
    )
    assert response.status_code == 401

    response = console_env.client.get("/api/registry/devices")
    assert response.status_code == 401


# --- condition 3/4: closed typed intent, strict schema ----------------------

def test_ac3_probe_request_rejects_unknown_fields(console_env):
    response = _post_probe(console_env, extra_body={"argv": ["--anything"]})
    assert response.status_code == 400


def test_ac4_probe_request_rejects_malformed_endpoint(console_env):
    response = _post_probe(console_env, endpoint="not a valid endpoint::::")
    assert response.status_code == 400


def test_ac3_confirm_request_rejects_unknown_fields(console_env):
    response = _confirm(console_env, probe_job_id="whatever-does-not-exist")
    # probe_job_id lookup happens after schema validation for known fields,
    # so a genuinely unknown field is what this test targets.
    body = {
        "probe_job_id": "whatever", "endpoint": "e", "vendor_hint": "checkpoint",
        "credential_profile_ref": "c1", "trust_profile_ref": _VALID_TRUST_REF,
        "confirm": True, "command": "rm -rf /",
    }
    response = console_env.client.post(
        "/api/registry/enrollments", headers=_headers(console_env), json=body
    )
    assert response.status_code == 400


# --- condition 6/7: opaque references only, closed vocabulary --------------

def test_ac6_probe_request_rejects_credential_ref_bad_format(console_env):
    response = _post_probe(console_env, credential_profile_ref="has a space")
    assert response.status_code == 400


def test_ac7_probe_request_rejects_non_sentinel_trust_profile_ref(console_env):
    response = _post_probe(console_env, trust_profile_ref="my-custom-trust-profile")
    assert response.status_code == 400


# --- condition 8: no credential payload of any kind -------------------------

def test_ac8_probe_request_rejects_password_field(console_env):
    response = _post_probe(console_env, extra_body={"password": "hunter2"})
    assert response.status_code == 400


# --- condition 9: no device I/O inside the enrollment request ---------------

def test_ac9_probe_post_returns_before_any_ssh_call(console_env):
    """Deterministic proof, not a timing race: the mocked probe function
    blocks on an Event the test controls, so the HTTP response can only have
    come back *before* the probe ran if the request handler never waited for
    it — exactly condition 9, "no device contact inside the HTTP request"."""
    import threading

    probe_may_proceed = threading.Event()

    def _blocking_probe(**kwargs):
        assert probe_may_proceed.wait(timeout=5.0), "probe never released -- test bug"
        return _positive_outcome()

    with mock.patch(
        "utils.pre_enrollment_identity_probe.run_pre_enrollment_identity_probe", side_effect=_blocking_probe,
    ):
        response = _post_probe(console_env, idem="probe-io-1")
        assert response.status_code == 200
        # The HTTP response already came back. The probe is blocked on
        # `probe_may_proceed`, which this request never touched -- so the
        # job cannot have reached `succeeded` yet, proving the request
        # handler itself performed no device I/O.
        job_id = response.json()["job_id"]
        record_before_release = console_env.job_store.get(job_id)
        assert record_before_release.state != "succeeded"

        probe_may_proceed.set()
        _wait_terminal(console_env, job_id)


# --- condition 10 / AC-EN-4: queued CLASS 0 read-only job -------------------

def test_ac10_probe_job_is_class_0_and_goes_through_job_store(console_env):
    from utils.action_taxonomy import CLASS_0_READ
    from console.registry import get_job_type

    job_type = get_job_type(_PROBE_JOB_TYPE)
    assert job_type is not None
    assert job_type.action_class is CLASS_0_READ
    assert job_type.console_reachable_via == "enrollment_probe_api"

    record = _run_probe_to_terminal(console_env, _positive_outcome())
    assert record.command_class == "read"
    assert record.state == "succeeded"


def test_ac10b_generic_jobs_api_refuses_the_probe_job_type(console_env):
    response = console_env.client.post(
        "/api/jobs", headers=_headers(console_env, **{"Idempotency-Key": "x"}),
        json={"job_type": _PROBE_JOB_TYPE, "targets": []},
    )
    assert response.status_code == 400


def test_ac5_probe_job_execution_never_calls_main_main(console_env):
    with mock.patch("main.main") as fake_main:
        _run_probe_to_terminal(console_env, _positive_outcome())
    fake_main.assert_not_called()


# --- condition 11/12: positive evidence only, sanitized preview ------------

def test_ac11_negative_identity_gate_produces_no_preview(console_env):
    record = _run_probe_to_terminal(console_env, _negative_outcome(), idem="probe-neg-1")
    assert record.state == "succeeded"
    assert record.preview is None
    assert record.outcome_counts["probe_status"] == "identity_gate_rejected"


def test_ac12_preview_excludes_serial_and_host_key_fingerprint(console_env):
    record = _run_probe_to_terminal(console_env, _positive_outcome(), idem="probe-pos-1")
    assert record.preview is not None
    assert "serial" not in record.preview
    assert "host_key_fingerprint" not in record.preview
    assert record.preview["model"] == "1550"


# --- condition 13/14/15/16: confirm + audit-before-mutation + registry -----

def test_ac13_confirm_rejects_endpoint_mismatch_with_probed_job(console_env):
    record = _run_probe_to_terminal(console_env, _positive_outcome(), endpoint="198.51.100.10", idem="probe-c1")
    response = _confirm(console_env, probe_job_id=record.job_id, endpoint="198.51.100.99")
    assert response.status_code == 409
    assert response.json()["detail"]["error"] == "probe_intent_mismatch"


def test_ac_confirm_requires_positive_preview(console_env):
    record = _run_probe_to_terminal(console_env, _negative_outcome(), idem="probe-neg-2")
    response = _confirm(console_env, probe_job_id=record.job_id)
    assert response.status_code == 409
    assert response.json()["detail"]["error"] == "no_positive_identity_evidence"


def test_ac14_audit_confirmation_row_written_before_registry_enroll_called(console_env):
    """Ordering proof, the same monkeypatch-and-observe-call-order technique
    `test_pcp1_device_registry.py::test_duplicate_enroll_refused_before_device_id_generated`
    uses: force `DeviceRegistry.enroll` to raise, then assert the
    confirmation audit row is durable anyway."""
    from utils.device_registry import DeviceRegistry, DeviceRegistryError
    from utils.enrollment_audit import EnrollmentAuditStore

    record = _run_probe_to_terminal(console_env, _positive_outcome(), idem="probe-audit-1")

    def _boom(self, **kwargs):
        raise DeviceRegistryError("synthetic failure for ordering proof")

    with mock.patch.object(DeviceRegistry, "enroll", _boom):
        response = _confirm(console_env, probe_job_id=record.job_id)
    assert response.status_code in (409, 500)

    audit = EnrollmentAuditStore(console_env.runtime_paths.data_root)
    rows = audit.list_all()
    confirmations = [r for r in rows if r.kind == "confirmation" and r.probe_job_id == record.job_id]
    outcomes = [r for r in rows if r.kind == "outcome" and r.probe_job_id == record.job_id]
    assert len(confirmations) == 1
    assert len(outcomes) == 1
    assert outcomes[0].outcome in ("refused_duplicate", "refused_other")


def test_ac15_confirm_persists_through_the_one_device_registry_path(console_env):
    from utils.device_registry import DeviceRegistry

    record = _run_probe_to_terminal(console_env, _positive_outcome(), endpoint="198.51.100.20", idem="probe-enroll-1")
    response = _confirm(console_env, probe_job_id=record.job_id, endpoint="198.51.100.20", tags={"site": "lab"})
    assert response.status_code == 200, response.text
    device = response.json()
    assert device["endpoint"] == "198.51.100.20"
    assert device["state"] == "ENROLLED_UNVERIFIED"
    assert device["enrollment_source"] == "manual"

    registry = DeviceRegistry(console_env.runtime_paths.data_root)
    persisted = registry.list()
    assert any(r.device_id == device["device_id"] for r in persisted)


def test_ac16_confirm_surfaces_existing_duplicate_refusal_unchanged(console_env):
    record1 = _run_probe_to_terminal(console_env, _positive_outcome(), endpoint="198.51.100.30", idem="probe-dup-1")
    first = _confirm(console_env, probe_job_id=record1.job_id, endpoint="198.51.100.30")
    assert first.status_code == 200

    record2 = _run_probe_to_terminal(console_env, _positive_outcome(), endpoint="198.51.100.30", idem="probe-dup-2")
    second = _confirm(console_env, probe_job_id=record2.job_id, endpoint="198.51.100.30")
    assert second.status_code == 409
    assert second.json()["detail"]["error"] == "refused_duplicate"


def test_probe_already_consumed_is_refused_on_replay(console_env):
    record = _run_probe_to_terminal(console_env, _positive_outcome(), endpoint="198.51.100.40", idem="probe-replay-1")
    first = _confirm(console_env, probe_job_id=record.job_id, endpoint="198.51.100.40")
    assert first.status_code == 200

    second = _confirm(console_env, probe_job_id=record.job_id, endpoint="198.51.100.40")
    assert second.status_code == 409
    assert second.json()["detail"]["error"] == "probe_already_consumed"


def test_confirm_requires_explicit_true_confirm(console_env):
    record = _run_probe_to_terminal(console_env, _positive_outcome(), endpoint="198.51.100.50", idem="probe-noconfirm-1")
    response = _confirm(console_env, probe_job_id=record.job_id, endpoint="198.51.100.50", confirm=False)
    assert response.status_code == 400


# --- candidate-based enrollment: scoped out, honestly, pending M10 ---------

def test_candidate_id_is_refused_pending_m10(console_env):
    record = _run_probe_to_terminal(console_env, _positive_outcome(), endpoint="198.51.100.60", idem="probe-cand-1")
    response = _confirm(
        console_env, probe_job_id=record.job_id, endpoint="198.51.100.60", candidate_id="cand-123",
    )
    assert response.status_code == 409
    assert response.json()["detail"]["error"] == "candidate_enrollment_not_available_pending_m10"


# --- condition 17: server/non-loopback mode refuses the write routes -------

def test_ac17_enrollment_routes_refuse_when_deployment_profile_is_server(console_env, monkeypatch):
    monkeypatch.setenv("SECURITYEXPERT_DEPLOYMENT_PROFILE", "server")
    response = _post_probe(console_env, idem="probe-server-1")
    assert response.status_code == 403
    assert response.json()["detail"]["error"] == "enrollment_blocked_non_local_profile"

    response = _confirm(console_env, probe_job_id="whatever")
    assert response.status_code == 403


def test_read_only_registry_devices_route_is_unaffected_by_deployment_profile(console_env, monkeypatch):
    monkeypatch.setenv("SECURITYEXPERT_DEPLOYMENT_PROFILE", "server")
    response = console_env.client.get("/api/registry/devices", headers=_headers(console_env))
    assert response.status_code == 200


# --- read-only registry listing ---------------------------------------------

def test_get_registry_devices_lists_persisted_enrollments(console_env):
    record = _run_probe_to_terminal(console_env, _positive_outcome(), endpoint="198.51.100.70", idem="probe-list-1")
    confirm_response = _confirm(console_env, probe_job_id=record.job_id, endpoint="198.51.100.70")
    assert confirm_response.status_code == 200

    response = console_env.client.get("/api/registry/devices", headers=_headers(console_env))
    assert response.status_code == 200
    endpoints = {row["endpoint"] for row in response.json()}
    assert "198.51.100.70" in endpoints
