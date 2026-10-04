"""M6 -- registry-keyed job target admission shell, `config_refresh_cp` only.

PO decision, 2026-09-06 (Option D, bounded `nexus-decision-council`
synthesis): implement a fail-closed targeting *foundation*, not functional
per-device collection. This file proves the admission/pre-execution
boundary in `console/registry_targets.py`, `console/app.py` and
`console/runner.py` -- never a real device, never a real collector.

Constraints this file also proves negatively (PO decision items 3/4/9):
no device_id -> entity_id mapping is ever inferred from endpoint, hostname,
display name, vendor hint, spelling equality, or collection output; no
Device Registry relationship write and no new durable mapping record is
ever produced by any admission or execution path here.
"""
from __future__ import annotations

from pathlib import Path
from unittest import mock

import pytest

from console.registry import get_job_type
from console.registry_targets import (
    DEVICE_ID_TARGET_MODE,
    DEVICE_NOT_ELIGIBLE,
    DEVICE_REGISTRY_UNAVAILABLE,
    IDENTITY_TRANSLATION_REQUIRED,
    UNKNOWN_DEVICE_ID,
    resolve_registry_targets,
)
from utils.device_registry import DeviceRegistry, REGISTRY_FILENAME

# Reuses the CON.2 fixture/harness verbatim -- cross-test-file fixture reuse
# is an existing convention in this suite (tests/test_m2_nav_accessibility_
# closure.py, tests/test_navigation_information_architecture.py).
from tests.test_con2_console_job_engine import (  # noqa: F401
    console_env,
    uitest_runtime_paths,
    _headers,
    _post_job,
    _wait_terminal,
)

pytestmark = pytest.mark.configuration


def _enroll(data_root: Path, *, endpoint: str, disable: bool = False) -> str:
    registry = DeviceRegistry(data_root)
    record = registry.enroll(endpoint=endpoint, vendor_hint="checkpoint")
    if disable:
        registry.disable(record.device_id)
    return record.device_id


def _corrupt_registry(data_root: Path) -> None:
    """Simulate utils.device_registry.DeviceRegistryError -- an unreadable/
    unparseable persisted document, not merely an empty or absent one."""
    path = data_root / "state" / REGISTRY_FILENAME
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("{not valid json", encoding="utf-8")


# --- JOB_REGISTRY boundary: M6 flips target_mode for config_refresh_cp only -

def test_config_refresh_cp_target_mode_is_device_ids():
    job_type = get_job_type("config_refresh_cp")
    assert job_type.target_mode == DEVICE_ID_TARGET_MODE


@pytest.mark.parametrize("job_type_id", [
    "inventory_refresh_cp", "inventory_refresh_vsx", "config_refresh_pan",
    "report_rebuild", "recovery_attest_cp", "cp_gaia_backup",
])
def test_no_other_job_type_target_mode_changed_by_m6(job_type_id):
    job_type = get_job_type(job_type_id)
    assert job_type.target_mode != DEVICE_ID_TARGET_MODE


# --- resolve_registry_targets: the shared admission/pre-execution function -

def test_empty_targets_is_the_only_pass_through(tmp_path):
    assert resolve_registry_targets((), data_root=tmp_path) is None
    assert resolve_registry_targets([], data_root=tmp_path) is None


def test_unknown_device_id_refused(tmp_path):
    refusal = resolve_registry_targets(("does-not-exist",), data_root=tmp_path)
    assert refusal is not None
    assert refusal.reason == UNKNOWN_DEVICE_ID


def test_disabled_device_id_refused(tmp_path):
    device_id = _enroll(tmp_path, endpoint="192.0.2.50", disable=True)
    refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
    assert refusal is not None
    assert refusal.reason == DEVICE_NOT_ELIGIBLE


def test_eligible_device_id_still_refused_with_identity_translation_required(tmp_path):
    device_id = _enroll(tmp_path, endpoint="192.0.2.51")
    refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
    assert refusal is not None
    assert refusal.error == "unsupported"
    assert refusal.reason == IDENTITY_TRANSLATION_REQUIRED


def test_one_ineligible_target_refuses_the_whole_request(tmp_path):
    # Ordering/opaque-spelling law: a mixed request never silently drops the
    # bad target and proceeds with the rest -- it refuses as a whole.
    eligible = _enroll(tmp_path, endpoint="192.0.2.52")
    refusal = resolve_registry_targets((eligible, "unknown-id"), data_root=tmp_path)
    assert refusal is not None
    assert refusal.reason == UNKNOWN_DEVICE_ID


# --- PO correction round 1, item 1: order/spelling is never sorted ----------

def test_unknown_device_id_refusal_preserves_submitted_order_not_sorted(tmp_path):
    # Deliberately non-alphabetical: "zzz-unknown" sorts after "aaa-unknown",
    # but the caller submitted them in the reverse order.
    submitted = ["zzz-unknown", "mmm-unknown", "aaa-unknown"]
    refusal = resolve_registry_targets(submitted, data_root=tmp_path)
    assert refusal is not None
    assert refusal.reason == UNKNOWN_DEVICE_ID
    assert f"{submitted}" in refusal.detail


def test_ineligible_device_id_refusal_preserves_submitted_order_not_sorted(tmp_path):
    zzz = _enroll(tmp_path, endpoint="192.0.2.90", disable=True)
    aaa = _enroll(tmp_path, endpoint="192.0.2.91", disable=True)
    # zzz enrolled first (so its device_id has no alphabetic relationship to
    # aaa's) -- submit aaa before zzz and require that exact order back.
    submitted = [aaa, zzz]
    refusal = resolve_registry_targets(submitted, data_root=tmp_path)
    assert refusal is not None
    assert refusal.reason == DEVICE_NOT_ELIGIBLE
    assert f"{submitted}" in refusal.detail


# --- PO correction round 1, item 2: registry failure vs. unknown device_id -

def test_corrupt_registry_is_not_classified_as_unknown_device_id(tmp_path):
    _corrupt_registry(tmp_path)
    refusal = resolve_registry_targets(("any-device-id",), data_root=tmp_path)
    assert refusal is not None
    assert refusal.reason == DEVICE_REGISTRY_UNAVAILABLE
    assert refusal.reason != UNKNOWN_DEVICE_ID


def test_corrupt_registry_refusal_detail_is_sanitized(tmp_path):
    _corrupt_registry(tmp_path)
    refusal = resolve_registry_targets(("any-device-id",), data_root=tmp_path)
    assert refusal is not None
    # Never the raw exception text, a filesystem path, or a parser detail.
    assert str(tmp_path) not in refusal.detail
    assert "json" not in refusal.detail.lower()
    assert "traceback" not in refusal.detail.lower()
    assert ".py" not in refusal.detail
    assert "any-device-id" not in refusal.detail


def test_registry_state_is_re_read_every_call_not_cached(tmp_path):
    device_id = _enroll(tmp_path, endpoint="192.0.2.53")
    first = resolve_registry_targets((device_id,), data_root=tmp_path)
    assert first.reason == IDENTITY_TRANSLATION_REQUIRED

    DeviceRegistry(tmp_path).disable(device_id)

    second = resolve_registry_targets((device_id,), data_root=tmp_path)
    assert second.reason == DEVICE_NOT_ELIGIBLE


# --- Never inferred from endpoint/hostname/vendor/spelling/collection ------

def test_endpoint_hostname_vendor_spelling_never_used_as_translation_evidence(tmp_path):
    """A device_id whose endpoint/hostname *spells* like a plausible
    collector entity_id must still refuse with IDENTITY_TRANSLATION_REQUIRED
    -- never be treated as if that spelling proved a mapping."""
    device_id = _enroll(tmp_path, endpoint="cl1.example.invalid")
    refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
    assert refusal.reason == IDENTITY_TRANSLATION_REQUIRED
    # The refusal never leaks the endpoint back as if it were resolved identity.
    assert "cl1.example.invalid" not in refusal.detail


def test_resolver_never_touches_unified_json_or_collection_output(tmp_path, monkeypatch):
    """Unlike the entity_ids target_mode's _known_entity_ids, this resolver
    must consult only the Device Registry -- never collection output."""
    import utils.restore_readiness as restore_readiness

    def _fail_if_called(*a, **k):
        raise AssertionError("resolve_registry_targets must never resolve entity_id from collection output")

    monkeypatch.setattr(restore_readiness, "resolve_entity_id", _fail_if_called)
    device_id = _enroll(tmp_path, endpoint="192.0.2.54")
    refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
    assert refusal.reason == IDENTITY_TRANSLATION_REQUIRED


def test_resolver_never_writes_a_device_registry_relationship(tmp_path):
    device_id = _enroll(tmp_path, endpoint="192.0.2.55")
    resolve_registry_targets((device_id,), data_root=tmp_path)
    record = next(r for r in DeviceRegistry(tmp_path).list() if r.device_id == device_id)
    assert record.relationships == []


# --- Admission: POST /api/jobs (console_env, HTTP boundary) -----------------

def test_admission_refuses_unknown_device_id_before_contact(console_env):
    with mock.patch("main.main") as fake_main:
        response = _post_job(console_env, "config_refresh_cp", targets=["ghost-device-id"])
    assert response.status_code == 400
    assert response.json()["detail"]["reason"] == UNKNOWN_DEVICE_ID
    fake_main.assert_not_called()


def test_admission_refuses_disabled_device_before_contact(console_env):
    device_id = _enroll(console_env.runtime_paths.data_root, endpoint="192.0.2.60", disable=True)
    with mock.patch("main.main") as fake_main:
        response = _post_job(console_env, "config_refresh_cp", targets=[device_id])
    assert response.status_code == 400
    assert response.json()["detail"]["reason"] == DEVICE_NOT_ELIGIBLE
    fake_main.assert_not_called()


def test_admission_refuses_valid_registry_record_with_identity_translation_required(console_env):
    device_id = _enroll(console_env.runtime_paths.data_root, endpoint="192.0.2.61")
    with mock.patch("main.main") as fake_main:
        response = _post_job(console_env, "config_refresh_cp", targets=[device_id])
    assert response.status_code == 400
    detail = response.json()["detail"]
    assert detail["error"] == "unsupported"
    assert detail["reason"] == IDENTITY_TRANSLATION_REQUIRED
    fake_main.assert_not_called()


def test_admission_never_creates_a_job_record_for_a_refused_device_id_target(console_env):
    device_id = _enroll(console_env.runtime_paths.data_root, endpoint="192.0.2.62")
    with mock.patch("main.main"):
        _post_job(console_env, "config_refresh_cp", targets=[device_id])
    assert console_env.job_store.list_all() == []


def test_admission_refuses_unreadable_registry_without_calling_main(console_env):
    _corrupt_registry(console_env.runtime_paths.data_root)
    with mock.patch("main.main") as fake_main:
        response = _post_job(console_env, "config_refresh_cp", targets=["some-device-id"])
    assert response.status_code == 400
    detail = response.json()["detail"]
    assert detail["reason"] == DEVICE_REGISTRY_UNAVAILABLE
    fake_main.assert_not_called()


def test_admission_unreadable_registry_response_is_sanitized(console_env):
    _corrupt_registry(console_env.runtime_paths.data_root)
    with mock.patch("main.main"):
        response = _post_job(console_env, "config_refresh_cp", targets=["some-device-id"])
    body = response.text
    assert str(console_env.runtime_paths.data_root) not in body
    assert "json.decode" not in body.lower()
    assert "traceback" not in body.lower()


def test_admission_unreadable_registry_creates_no_job_record(console_env):
    _corrupt_registry(console_env.runtime_paths.data_root)
    with mock.patch("main.main"):
        _post_job(console_env, "config_refresh_cp", targets=["some-device-id"])
    assert console_env.job_store.list_all() == []


def test_admission_target_free_config_refresh_cp_is_unaffected_by_m6(console_env):
    """M5's plane-wide, target-free behavior must stay byte-identical."""
    with mock.patch("main.main", return_value=None) as fake_main:
        response = _post_job(console_env, "config_refresh_cp", targets=[])
        assert response.status_code == 200
        job_id = response.json()["job_id"]
        record = _wait_terminal(console_env, job_id)
    assert record.state == "succeeded"
    argv = fake_main.call_args.args[0]
    assert "--cp-config-targets" not in argv


# --- Pre-execution re-check: a target can go stale between queue and run ---

def test_pre_execution_recheck_refuses_a_target_disabled_after_admission(console_env):
    """Simulates a device becoming ineligible between admission and
    execution by submitting the job record directly to the store/runner
    (bypassing the HTTP admission check, the way an already-queued job
    would reach the runner), then disabling the device before the runner
    dequeues it."""
    device_id = _enroll(console_env.runtime_paths.data_root, endpoint="192.0.2.63")

    record, is_new = console_env.job_store.submit(
        job_type="config_refresh_cp", command_class="read",
        targets=[device_id], idempotency_key="direct-submit-1",
    )
    assert is_new

    DeviceRegistry(console_env.runtime_paths.data_root).disable(device_id)

    with mock.patch("main.main") as fake_main:
        console_env.runner.enqueue(record.job_id)
        final = _wait_terminal(console_env, record.job_id)
    fake_main.assert_not_called()
    assert final.state == "blocked"
    assert final.error_code == DEVICE_NOT_ELIGIBLE


def test_pre_execution_recheck_refuses_even_when_admission_would_have_passed(console_env):
    """A job constructed directly (as if it had somehow been admitted) with
    an eligible-but-unmapped device_id must still refuse at the runner's
    pre-execution boundary, never reaching main.main()."""
    device_id = _enroll(console_env.runtime_paths.data_root, endpoint="192.0.2.64")

    record, is_new = console_env.job_store.submit(
        job_type="config_refresh_cp", command_class="read",
        targets=[device_id], idempotency_key="direct-submit-2",
    )
    assert is_new

    with mock.patch("main.main") as fake_main:
        console_env.runner.enqueue(record.job_id)
        final = _wait_terminal(console_env, record.job_id)
    fake_main.assert_not_called()
    assert final.state == "blocked"
    assert final.error_code == IDENTITY_TRANSLATION_REQUIRED


def test_pre_execution_recheck_refuses_registry_that_became_unreadable_after_admission(console_env):
    """A registry that becomes corrupt between admission and execution --
    e.g. an external write race -- must mark an already-queued job blocked
    without ever reaching main.main(), the same as any other pre-execution
    refusal."""
    device_id = _enroll(console_env.runtime_paths.data_root, endpoint="192.0.2.68")

    record, is_new = console_env.job_store.submit(
        job_type="config_refresh_cp", command_class="read",
        targets=[device_id], idempotency_key="direct-submit-5",
    )
    assert is_new

    _corrupt_registry(console_env.runtime_paths.data_root)

    with mock.patch("main.main") as fake_main:
        console_env.runner.enqueue(record.job_id)
        final = _wait_terminal(console_env, record.job_id)
    fake_main.assert_not_called()
    assert final.state == "blocked"
    assert final.error_code == DEVICE_REGISTRY_UNAVAILABLE
    assert str(console_env.runtime_paths.data_root) not in (final.error_summary or "")


# --- No refusal path ever reaches main.main(), a collector, or a device ----

def test_no_refusal_path_calls_main_main(console_env):
    device_id = _enroll(console_env.runtime_paths.data_root, endpoint="192.0.2.65")
    with mock.patch("main.main") as fake_main:
        _post_job(console_env, "config_refresh_cp", targets=[device_id])
        record, is_new = console_env.job_store.submit(
            job_type="config_refresh_cp", command_class="read",
            targets=["still-unknown"], idempotency_key="direct-submit-3",
        )
        console_env.runner.enqueue(record.job_id)
        _wait_terminal(console_env, record.job_id)
    fake_main.assert_not_called()


def test_no_plane_wide_fallback_or_partial_target_execution(console_env):
    """A request naming one eligible and one unknown device_id must refuse
    entirely -- never silently drop the bad id and run the good one, and
    never fall back to a plane-wide (target-free) cp-config run."""
    eligible = _enroll(console_env.runtime_paths.data_root, endpoint="192.0.2.66")
    with mock.patch("main.main") as fake_main:
        response = _post_job(console_env, "config_refresh_cp", targets=[eligible, "unknown-id"])
    assert response.status_code == 400
    assert response.json()["detail"]["reason"] == UNKNOWN_DEVICE_ID
    fake_main.assert_not_called()


def test_no_device_registry_relationship_or_mapping_record_written_end_to_end(console_env):
    device_id = _enroll(console_env.runtime_paths.data_root, endpoint="192.0.2.67")
    with mock.patch("main.main"):
        _post_job(console_env, "config_refresh_cp", targets=[device_id])
        record, _ = console_env.job_store.submit(
            job_type="config_refresh_cp", command_class="read",
            targets=[device_id], idempotency_key="direct-submit-4",
        )
        console_env.runner.enqueue(record.job_id)
        _wait_terminal(console_env, record.job_id)

    stored = next(
        r for r in DeviceRegistry(console_env.runtime_paths.data_root).list()
        if r.device_id == device_id
    )
    assert stored.relationships == []


# --- M5 compatibility: target-free / unsupported-target paths stay green ---

def test_m5_target_free_and_unsupported_workflows_still_refuse_before_main(console_env):
    from utils.collection_executor import UnsupportedTargetSelectionError, workflow_argv

    with pytest.raises(UnsupportedTargetSelectionError):
        workflow_argv("vsx", Path("R"), targets=("SOMETHING",))

    argv = workflow_argv("cp-config", Path("R"), targets=())
    assert "--cp-config-targets" not in argv
