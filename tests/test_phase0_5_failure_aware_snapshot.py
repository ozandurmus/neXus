import json
from pathlib import Path
from types import SimpleNamespace

import utils.snapshot as snapshot


def write_json(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


def fake_ctx(tmp_path, run_id="run-2"):
    root = tmp_path / run_id
    stage = root / "stage"
    raw = root / "raw"
    stage.mkdir(parents=True)
    raw.mkdir(parents=True)
    return SimpleNamespace(
        run_id=run_id,
        root=root,
        stage_dir=stage,
        raw_dir=raw,
        created_at="2026-08-22T10:00:00+00:00",
        stages={
            "cp": {"completed_at": "2026-08-22T10:01:00+00:00"},
            "vsx_parse": {"completed_at": "2026-08-22T10:02:00+00:00"},
            "panorama": {"completed_at": "2026-08-22T10:03:00+00:00"},
        },
    )


def test_failed_device_uses_last_known_good(tmp_path, monkeypatch):
    monkeypatch.setattr(snapshot, "LKG_FILE", tmp_path / "state" / "lkg.json")
    ctx1 = fake_ctx(tmp_path, "run-1")
    write_json(ctx1.stage_dir / "cp.json", [{"source": "cp", "device": "GW1", "interfaces": [{"name": "eth0"}], "routes": []}])
    write_json(ctx1.stage_dir / "vsx.json", [])
    write_json(ctx1.stage_dir / "panorama_runtime.json", [])
    write_json(ctx1.raw_dir / "cp_telemetry.json", {"remote_command_status": [{"device": "GW1", "management_state": "communicating", "collection_outcome": "success"}]})
    write_json(ctx1.raw_dir / "panorama_telemetry.json", {"devices": []})
    snapshot.build_failure_aware_snapshot(ctx1)

    ctx2 = fake_ctx(tmp_path, "run-2")
    write_json(ctx2.stage_dir / "cp.json", [])
    write_json(ctx2.stage_dir / "vsx.json", [])
    write_json(ctx2.stage_dir / "panorama_runtime.json", [])
    write_json(ctx2.raw_dir / "cp_telemetry.json", {"remote_command_status": [{"device": "GW1", "management_state": "communicating", "collection_outcome": "failed", "interface_error": "command_error"}]})
    write_json(ctx2.raw_dir / "panorama_telemetry.json", {"devices": []})
    snapshot.build_failure_aware_snapshot(ctx2)

    effective = json.loads((ctx2.stage_dir / "cp_effective.json").read_text())
    assert len(effective) == 1
    assert effective[0]["interfaces"] == [{"name": "eth0"}]
    status = effective[0]["inventory_status"]
    assert status["fresh"] is False
    assert status["data_state"] == "last_known_good"
    assert status["stale_reason"] == "collection_failed"


def test_management_down_without_history_creates_no_data_placeholder(tmp_path, monkeypatch):
    monkeypatch.setattr(snapshot, "LKG_FILE", tmp_path / "state" / "lkg.json")
    ctx = fake_ctx(tmp_path)
    write_json(ctx.stage_dir / "cp.json", [])
    write_json(ctx.stage_dir / "vsx.json", [])
    write_json(ctx.stage_dir / "panorama_runtime.json", [])
    write_json(ctx.raw_dir / "cp_telemetry.json", {"remote_command_status": [{"device": "GW-DOWN", "management_state": "uninitialized", "collection_outcome": "management_down", "interface_error": "management_down"}]})
    write_json(ctx.raw_dir / "panorama_telemetry.json", {"devices": []})
    snapshot.build_failure_aware_snapshot(ctx)

    effective = json.loads((ctx.stage_dir / "cp_effective.json").read_text())
    assert effective[0]["device"] == "GW-DOWN"
    assert effective[0]["inventory_status"]["data_state"] == "no_data"
    assert effective[0]["inventory_status"]["availability_state"] == "uninitialized"


def test_panorama_disconnected_uses_previous_entity(tmp_path, monkeypatch):
    monkeypatch.setattr(snapshot, "LKG_FILE", tmp_path / "state" / "lkg.json")
    ctx1 = fake_ctx(tmp_path, "run-1")
    write_json(ctx1.stage_dir / "cp.json", [])
    write_json(ctx1.stage_dir / "vsx.json", [])
    write_json(ctx1.stage_dir / "panorama_runtime.json", [{"source": "panorama", "device": "PAN1", "serial": "SER1", "interfaces": [{"name": "ethernet1/1"}], "routes": []}])
    write_json(ctx1.raw_dir / "cp_telemetry.json", {"remote_command_status": []})
    write_json(ctx1.raw_dir / "panorama_telemetry.json", {"devices": [{"device": "PAN1", "serial": "SER1", "connected": "yes", "interfaces": {"status": "success"}, "routes": {"status": "success"}}]})
    snapshot.build_failure_aware_snapshot(ctx1)

    ctx2 = fake_ctx(tmp_path, "run-2")
    write_json(ctx2.stage_dir / "cp.json", [])
    write_json(ctx2.stage_dir / "vsx.json", [])
    write_json(ctx2.stage_dir / "panorama_runtime.json", [])
    write_json(ctx2.raw_dir / "cp_telemetry.json", {"remote_command_status": []})
    write_json(ctx2.raw_dir / "panorama_telemetry.json", {"devices": [{"device": "PAN1", "serial": "SER1", "connected": "no", "interfaces": {"status": "failed"}, "routes": {"status": "pending"}}]})
    snapshot.build_failure_aware_snapshot(ctx2)

    effective = json.loads((ctx2.stage_dir / "panorama_effective.json").read_text())
    assert effective[0]["interfaces"] == [{"name": "ethernet1/1"}]
    assert effective[0]["inventory_status"]["data_state"] == "last_known_good"
    assert effective[0]["inventory_status"]["availability_state"] == "disconnected"
