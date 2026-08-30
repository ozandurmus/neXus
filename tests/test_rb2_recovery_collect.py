"""RB.2/RB.3 — recovery collection orchestration, target selection, PAN
device-state collector (fixture transport only, never a live device), CP
blocked stub, and the scheduler policy `targets` extension.

Contract: docs/design/BACKUP_RECOVERY_CONTRACTS.md §10.
"""
import pytest

import main
from checkpoint.checkpoint_recovery_collector import BLOCK_REASON, CheckpointGaiaBackupCollector
from utils import recovery_store
from utils.collection_executor import ALLOWLISTED_WORKFLOWS, load_scheduler_policy
from utils.recovery_collect import (
    RecoveryCollectionBlockedError,
    RecoveryCollectionError,
    RecoveryCollectionRequest,
    run_recovery_collection,
    select_recovery_targets,
)
from utils.runtime_paths import resolve_recovery_root, resolve_runtime_paths

pytestmark = pytest.mark.recovery


def _unified():
    return [
        {"source": "cp", "device": "fw-01", "vsys": "default",
         "inventory_status": {"data_state": "live"}},
        {"source": "vsx", "device": "vsx-01", "vsys": "", "cluster": "",
         "inventory_status": {"data_state": "live"}},
        {"source": "vsx", "device": "vsx-01", "vsys": "VS-A", "vs_id": "10", "cluster": "",
         "inventory_status": {"data_state": "live"}},
        {"source": "panorama", "device": "pan-01", "serial": "SN1", "management_ip": "192.0.2.10",
         "inventory_status": {"data_state": "live"}},
    ]


# --- select_recovery_targets ------------------------------------------------

def test_select_all_filters_by_vendor_and_sorts():
    targets = select_recovery_targets(_unified(), vendor="checkpoint", selector={"mode": "all"})
    ids = [t.entity_id for t in targets]
    assert ids == sorted(ids)
    assert set(ids) == {"fw-01", "vsx-01", "vsx-01__vsid_10"}
    assert all(t.vendor == "checkpoint" for t in targets)


def test_select_all_panorama_only_returns_panorama_devices():
    targets = select_recovery_targets(_unified(), vendor="panorama", selector={"mode": "all"})
    assert [t.entity_id for t in targets] == ["pan-01"]


def test_select_targets_explicit_list_including_vsx_vsid():
    targets = select_recovery_targets(
        _unified(), vendor="checkpoint",
        selector={"mode": "targets", "entity_ids": ["fw-01", "vsx-01__vsid_10"]},
    )
    assert {t.entity_id for t in targets} == {"fw-01", "vsx-01__vsid_10"}


def test_select_targets_unresolvable_entity_id_raises_before_anything_else():
    with pytest.raises(RecoveryCollectionError, match="unresolvable"):
        select_recovery_targets(
            _unified(), vendor="checkpoint",
            selector={"mode": "targets", "entity_ids": ["fw-01", "does-not-exist"]},
        )


def test_select_targets_empty_list_raises():
    with pytest.raises(RecoveryCollectionError, match="non-empty"):
        select_recovery_targets(_unified(), vendor="checkpoint", selector={"mode": "targets", "entity_ids": []})


def test_select_unknown_selector_mode_raises():
    with pytest.raises(RecoveryCollectionError, match="unknown selector mode"):
        select_recovery_targets(_unified(), vendor="checkpoint", selector={"mode": "bogus"})


# --- run_recovery_collection -------------------------------------------------

def _paths(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "main.py").write_text("", encoding="utf-8")
    runtime = resolve_runtime_paths(str(tmp_path / "runtime"), environ={}, repository_root=repo)
    recovery = resolve_recovery_root(
        str(tmp_path / "recovery"), environ={}, repository_root=repo, runtime_root=runtime.runtime_root
    )
    return runtime, recovery


class _FakeCollector:
    def __init__(self, plan):
        self.plan = plan  # entity_id -> ("ok"|"blocked"|"error", payload)
        self.calls = []

    def collect(self, target):
        self.calls.append(target.entity_id)
        kind, payload = self.plan[target.entity_id]
        if kind == "ok":
            return payload
        if kind == "blocked":
            raise RecoveryCollectionBlockedError(payload)
        raise RuntimeError(payload)


def _meta(**overrides):
    meta = {
        "class": "cp_gaia_backup", "vendor_native_filename": "backup.tgz",
        "collected_via": "cp_ssh_scp_fetch", "compression": "gzip",
        "platform": "gaia", "software_version": "R81.20", "ha_role": "standalone",
    }
    meta.update(overrides)
    return meta


def test_run_recovery_collection_writes_real_artifact_for_success(tmp_path):
    runtime, recovery = _paths(tmp_path)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    collector = _FakeCollector({"fw-01": ("ok", (b"real backup bytes", _meta()))})

    request = RecoveryCollectionRequest(vendor="checkpoint", selector={"mode": "targets", "entity_ids": ["fw-01"]})
    result = run_recovery_collection(
        request, unified_devices=_unified(), collector=collector,
        recovery_paths=recovery, vault_key=vault_key, vault_key_id=vault_key_id,
    )
    assert result.collected_count == 1
    assert result.failed_count == 0
    artifact_dirs = recovery_store.list_artifact_dirs(recovery)
    assert len(artifact_dirs) == 1
    manifest = recovery_store.read_manifest(artifact_dirs[0])
    assert manifest["device"]["entity_id"] == "fw-01"
    decrypted = recovery_store.decrypt_artifact(artifact_dirs[0], manifest, vault_key=vault_key)
    assert decrypted == b"real backup bytes"


def test_run_recovery_collection_one_failure_does_not_abort_the_batch(tmp_path):
    runtime, recovery = _paths(tmp_path)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    collector = _FakeCollector({
        "fw-01": ("error", "simulated transport failure"),
        "vsx-01": ("ok", (b"vsx backup", _meta())),
    })
    request = RecoveryCollectionRequest(
        vendor="checkpoint", selector={"mode": "targets", "entity_ids": ["fw-01", "vsx-01"]},
    )
    result = run_recovery_collection(
        request, unified_devices=_unified(), collector=collector,
        recovery_paths=recovery, vault_key=vault_key, vault_key_id=vault_key_id,
    )
    assert collector.calls == ["fw-01", "vsx-01"]  # both attempted
    statuses = {o.entity_id: o.status for o in result.outcomes}
    assert statuses["fw-01"] == "failed"
    assert statuses["vsx-01"] == "collected"
    assert result.collected_count == 1
    assert result.failed_count == 1


def test_run_recovery_collection_blocked_collector_reports_blocked_not_failed(tmp_path):
    runtime, recovery = _paths(tmp_path)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    collector = _FakeCollector({"fw-01": ("blocked", "audit not started")})
    request = RecoveryCollectionRequest(vendor="checkpoint", selector={"mode": "targets", "entity_ids": ["fw-01"]})
    result = run_recovery_collection(
        request, unified_devices=_unified(), collector=collector,
        recovery_paths=recovery, vault_key=vault_key, vault_key_id=vault_key_id,
    )
    assert result.outcomes[0].status == "blocked"
    assert result.outcomes[0].error == "audit not started"
    assert recovery_store.list_artifact_dirs(recovery) == []


def test_run_recovery_collection_admission_hook_called_per_target(tmp_path):
    runtime, recovery = _paths(tmp_path)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    collector = _FakeCollector({
        "fw-01": ("ok", (b"x", _meta())),
        "vsx-01": ("ok", (b"y", _meta())),
    })
    admitted_entity_ids = []

    def run_under_admission(entity_id, operation):
        admitted_entity_ids.append(entity_id)
        return operation()

    request = RecoveryCollectionRequest(
        vendor="checkpoint", selector={"mode": "targets", "entity_ids": ["fw-01", "vsx-01"]},
    )
    run_recovery_collection(
        request, unified_devices=_unified(), collector=collector,
        recovery_paths=recovery, vault_key=vault_key, vault_key_id=vault_key_id,
        run_under_admission=run_under_admission,
    )
    assert admitted_entity_ids == ["fw-01", "vsx-01"]


def test_run_recovery_collection_admission_rejection_reports_failed(tmp_path):
    runtime, recovery = _paths(tmp_path)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    collector = _FakeCollector({"fw-01": ("ok", (b"x", _meta()))})

    def run_under_admission(entity_id, operation):
        raise RuntimeError("endpoint_lock_conflict")

    request = RecoveryCollectionRequest(vendor="checkpoint", selector={"mode": "targets", "entity_ids": ["fw-01"]})
    result = run_recovery_collection(
        request, unified_devices=_unified(), collector=collector,
        recovery_paths=recovery, vault_key=vault_key, vault_key_id=vault_key_id,
        run_under_admission=run_under_admission,
    )
    assert result.outcomes[0].status == "failed"
    assert collector.calls == []  # never reached the collector


# --- CP blocked stub ----------------------------------------------------------

def test_checkpoint_collector_always_blocked():
    collector = CheckpointGaiaBackupCollector()
    from utils.recovery_collect import RecoveryCollectionTarget
    target = RecoveryCollectionTarget(entity_id="fw-01", vendor="checkpoint", row={})
    with pytest.raises(RecoveryCollectionBlockedError) as exc:
        collector.collect(target)
    assert str(exc.value) == BLOCK_REASON
    assert "cp_device_interaction_safety" in BLOCK_REASON
    assert "D3" in BLOCK_REASON


# --- PAN device-state collector (fixture transport only) ----------------------

class _FakeResponse:
    def __init__(self, *, content=b"", status_code=200):
        self.content = content
        self.status_code = status_code

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


_KEYGEN_XML = b'<response status="success"><result><key>FAKEKEY</key></result></response>'


def test_pan_collector_success_path(monkeypatch):
    from panorama.panorama_recovery_collector import PanDeviceStateCollector
    from utils.recovery_collect import RecoveryCollectionTarget

    calls = []

    def fake_get(url, params=None, verify=None, timeout=None):
        calls.append((url, dict(params or {})))
        if params.get("type") == "keygen":
            return _FakeResponse(content=_KEYGEN_XML)
        return _FakeResponse(content=b"FAKE_DEVICE_STATE_TGZ_BYTES", status_code=200)

    monkeypatch.setattr("panorama.panorama_recovery_collector.requests.get", fake_get)

    class _Cfg:
        class auth:
            principal = "u"
            secret = "p"

    collector = PanDeviceStateCollector(_Cfg(), verify=False)
    target = RecoveryCollectionTarget(
        entity_id="pan-01", vendor="panorama", row={"management_ip": "192.0.2.10"},
    )
    plaintext, meta = collector.collect(target)
    assert plaintext == b"FAKE_DEVICE_STATE_TGZ_BYTES"
    assert meta["class"] == "pan_device_state"
    assert meta["compression"] == "gzip"
    assert meta["software_version"] == "unknown"

    export_calls = [c for c in calls if c[1].get("type") == "export"]
    assert len(export_calls) == 1
    assert export_calls[0][1]["category"] == "device-state"


def test_pan_collector_reuses_api_key_across_targets(monkeypatch):
    from panorama.panorama_recovery_collector import PanDeviceStateCollector
    from utils.recovery_collect import RecoveryCollectionTarget

    keygen_calls = []

    def fake_get(url, params=None, verify=None, timeout=None):
        if params.get("type") == "keygen":
            keygen_calls.append(1)
            return _FakeResponse(content=_KEYGEN_XML)
        return _FakeResponse(content=b"bytes", status_code=200)

    monkeypatch.setattr("panorama.panorama_recovery_collector.requests.get", fake_get)

    class _Cfg:
        class auth:
            principal = "u"
            secret = "p"

    collector = PanDeviceStateCollector(_Cfg(), verify=False)
    target = RecoveryCollectionTarget(entity_id="pan-01", vendor="panorama", row={"management_ip": "192.0.2.10"})
    collector.collect(target)
    collector.collect(target)
    assert len(keygen_calls) == 1  # session reuse -- contract §7.1 point 7


def test_pan_collector_403_raises_without_retry(monkeypatch):
    from panorama.panorama_recovery_collector import PanDeviceStateCollector
    from utils.recovery_collect import RecoveryCollectionTarget

    export_call_count = []

    def fake_get(url, params=None, verify=None, timeout=None):
        if params.get("type") == "keygen":
            return _FakeResponse(content=_KEYGEN_XML)
        export_call_count.append(1)
        return _FakeResponse(content=b"", status_code=403)

    monkeypatch.setattr("panorama.panorama_recovery_collector.requests.get", fake_get)

    class _Cfg:
        class auth:
            principal = "u"
            secret = "p"

    collector = PanDeviceStateCollector(_Cfg(), verify=False)
    target = RecoveryCollectionTarget(entity_id="pan-01", vendor="panorama", row={"management_ip": "192.0.2.10"})
    with pytest.raises(RuntimeError, match="403"):
        collector.collect(target)
    assert len(export_call_count) == 1  # not retried


def test_pan_collector_missing_management_ip_raises_before_any_request(monkeypatch):
    from panorama.panorama_recovery_collector import PanDeviceStateCollector
    from utils.recovery_collect import RecoveryCollectionTarget

    def fake_get(*a, **k):
        raise AssertionError("must not be called")

    monkeypatch.setattr("panorama.panorama_recovery_collector.requests.get", fake_get)

    class _Cfg:
        class auth:
            principal = "u"
            secret = "p"

    collector = PanDeviceStateCollector(_Cfg(), verify=False)
    target = RecoveryCollectionTarget(entity_id="pan-01", vendor="panorama", row={})
    with pytest.raises(RuntimeError, match="management_ip"):
        collector.collect(target)


# --- scheduler policy: additive `targets`, ALLOWLISTED_WORKFLOWS --------------

def test_recovery_pan_is_allowlisted_recovery_cp_is_not():
    assert "recovery-pan" in ALLOWLISTED_WORKFLOWS
    assert "recovery-cp" not in ALLOWLISTED_WORKFLOWS


def test_scheduler_policy_targets_field_is_optional_and_additive(tmp_path):
    import json
    policy_dir = tmp_path / "state"
    policy_dir.mkdir()
    (policy_dir / "scheduler_policy.json").write_text(json.dumps({
        "version": 1, "enabled": True,
        "schedule": [{"workflow": "recovery-pan", "interval_minutes": 1440}],
    }), encoding="utf-8")
    policy = load_scheduler_policy(tmp_path)
    assert policy.workflows[0].targets == ()


def test_scheduler_policy_targets_field_when_present(tmp_path):
    import json
    policy_dir = tmp_path / "state"
    policy_dir.mkdir()
    (policy_dir / "scheduler_policy.json").write_text(json.dumps({
        "version": 1, "enabled": True,
        "schedule": [{"workflow": "recovery-pan", "interval_minutes": 1440, "targets": ["pan-01", "pan-02"]}],
    }), encoding="utf-8")
    policy = load_scheduler_policy(tmp_path)
    assert policy.workflows[0].targets == ("pan-01", "pan-02")


def test_scheduler_policy_rejects_recovery_cp_workflow(tmp_path):
    import json
    policy_dir = tmp_path / "state"
    policy_dir.mkdir()
    (policy_dir / "scheduler_policy.json").write_text(json.dumps({
        "version": 1, "enabled": True,
        "schedule": [{"workflow": "recovery-cp", "interval_minutes": 1440}],
    }), encoding="utf-8")
    from utils.collection_executor import SchedulerPolicyError
    with pytest.raises(SchedulerPolicyError, match="non-allowlisted"):
        load_scheduler_policy(tmp_path)


def test_scheduler_workflow_argv_recovery_pan_with_targets():
    from utils.collection_executor import ScheduledWorkflow

    row = ScheduledWorkflow(workflow="recovery-pan", interval_minutes=1440, targets=("pan-01", "pan-02"))
    argv = main._scheduler_workflow_argv(row, "/runtime")
    assert "--recovery-collect" in argv
    assert argv[argv.index("--recovery-vendor") + 1] == "panorama"
    assert argv[argv.index("--recovery-gateways") + 1] == "pan-01,pan-02"


def test_scheduler_workflow_argv_recovery_pan_without_targets():
    from utils.collection_executor import ScheduledWorkflow

    row = ScheduledWorkflow(workflow="recovery-pan", interval_minutes=1440)
    argv = main._scheduler_workflow_argv(row, "/runtime")
    assert "--recovery-collect" in argv
    assert "--recovery-gateways" not in argv


# --- CLI integration ----------------------------------------------------------

def test_cli_recovery_collect_requires_vendor(tmp_path):
    with pytest.raises(SystemExit) as exc:
        main.main(["--runtime-root", str(tmp_path / "runtime"), "--recovery-collect"])
    assert exc.value.code == 2


def test_cli_recovery_gateways_requires_recovery_collect(tmp_path):
    with pytest.raises(SystemExit) as exc:
        main.main([
            "--runtime-root", str(tmp_path / "runtime"), "--recovery-gateways", "fw-01",
        ])
    assert exc.value.code == 2


def test_cli_recovery_collect_checkpoint_end_to_end_blocked(tmp_path, capsys):
    runtime_root = tmp_path / "runtime"
    recovery_root = tmp_path / "recovery"
    output_dir = runtime_root / "output"
    output_dir.mkdir(parents=True)
    import json
    (output_dir / "unified.json").write_text(json.dumps(_unified()), encoding="utf-8")

    with pytest.raises(SystemExit) as exc:
        main.main([
            "--runtime-root", str(runtime_root),
            "--recovery-root", str(recovery_root),
            "--recovery-collect", "--recovery-vendor", "checkpoint", "--recovery-gateways", "fw-01",
        ])
    assert exc.value.code == 1
    out = capsys.readouterr().out
    assert "fw-01: blocked" in out
    assert "Gate:                    FAIL" in out
