import json
import time
from pathlib import Path

from utils import run_context
from utils.completeness import build_vsx_completeness
from utils.verification import run_verification


def test_run_context_records_stage_and_artifact_integrity(tmp_path, monkeypatch):
    output_dir = tmp_path / "output"
    runs_dir = tmp_path / "data" / "runs"
    output_dir.mkdir(parents=True)

    monkeypatch.setattr(run_context, "OUTPUT_DIR", output_dir)
    monkeypatch.setattr(run_context, "RUNS_DIR", runs_dir)
    monkeypatch.setattr(run_context, "info", lambda _msg: None)

    ctx = run_context.RunContext.create()
    ctx.start_stage("cp")
    (output_dir / "cp.json").write_text('[{"source":"cp"}]', encoding="utf-8")
    ctx.capture("cp.json", "parsed")
    ctx.finish_stage("cp")
    ctx.write_manifest(status="completed")

    manifest = json.loads(ctx.manifest_path.read_text(encoding="utf-8"))
    assert manifest["schema_version"] == "0.6"
    assert manifest["stages"]["cp"]["status"] == "success"
    assert manifest["artifact_integrity"]["cp.json"]["json_valid"] is True
    assert manifest["artifact_integrity"]["cp.json"]["objects"] == 1
    assert len(manifest["artifact_integrity"]["cp.json"]["sha256"]) == 64


def test_vsx_completeness_detects_prompt_timeout_and_parser_delta():
    raw = [{
        "device": "vsx1",
        "vsys": "BigVS",
        "vs_id": "1",
        "interfaces_raw": "eth1 Link encap:Ethernet\n[Expert@vsx1:1]# ",
        "routes_raw": "default via 10.0.0.1 dev eth1\n10.0.0.0/24 dev eth1 scope link\n",
    }]
    parsed = [{
        "device": "vsx1",
        "vsys": "BigVS",
        "vs_id": "1",
        "interfaces": [{"name": "eth1"}],
        "routing": [{"network": "0.0.0.0/0", "interface": "eth1"}],
    }]
    telemetry = [
        {"device": "vsx1", "context": "BigVS", "vs_id": "1", "command": "interfaces", "prompt_seen": True, "timeout": False},
        {"device": "vsx1", "context": "BigVS", "vs_id": "1", "command": "routes", "prompt_seen": False, "timeout": True},
    ]

    report = build_vsx_completeness(raw, parsed, telemetry)
    codes = {x["code"] for x in report["warnings"]}
    assert report["status"] == "warning"
    assert report["telemetry"]["timeout_count"] == 1
    assert report["telemetry"]["prompt_miss_count"] == 1
    assert report["raw_to_parsed"]["route_delta_contexts"] == 1
    assert "VSX_COMMAND_TIMEOUT" in codes
    assert "VSX_PROMPT_NOT_SEEN" in codes
    assert "VSX_RAW_PARSED_ROUTE_DELTA" in codes


def test_verification_accepts_fresh_cp_marker_and_complete_vsx(tmp_path, monkeypatch):
    cp = [{
        "source": "cp",
        "device": "cp1",
        "interfaces": [{"name": "eth0"}],
        "routes": [{"network": "10.0.0.0/24", "interface": "eth0"}],
    }]
    vsx = [{
        "source": "vsx",
        "device": "vsx1",
        "vsys": "VS1",
        "vs_id": "1",
        "interfaces": [{"name": "eth1"}],
        "routing": [{"network": "0.0.0.0/0", "interface": "eth1"}],
    }]
    pan = [{
        "source": "panorama",
        "device": "pan1",
        "interfaces": [{"name": "ethernet1/1"}],
        "routes": [{"network": "0.0.0.0/0", "interface": "ethernet1/1"}],
    }]
    unified = cp + [{**vsx[0], "routes": vsx[0]["routing"]}] + pan

    for name, data in {
        "cp.json": cp,
        "vsx.json": vsx,
        "panorama_runtime.json": pan,
        "unified.json": unified,
        "vsx_raw.json": [{
            "device": "vsx1",
            "vsys": "VS1",
            "vs_id": "1",
            "interfaces_raw": "eth1 Link encap:Ethernet\n[Expert@vsx1:1]# ",
            "routes_raw": "default via 10.0.0.1 dev eth1\n[Expert@vsx1:1]# ",
        }],
        "vsx_telemetry.json": [
            {"device": "vsx1", "context": "VS1", "vs_id": "1", "command": "interfaces", "prompt_seen": True, "timeout": False},
            {"device": "vsx1", "context": "VS1", "vs_id": "1", "command": "routes", "prompt_seen": True, "timeout": False},
        ],
        "panorama_telemetry.json": {
            "discovered": 1,
            "connected_yes": 1,
            "connected_no": 0,
            "successful": 1,
            "failed": 0,
            "devices": [{
                "device": "pan1",
                "serial": "SERIAL1",
                "connected": "yes",
                "interfaces": {"status": "success", "parsed": 1},
                "routes": {"status": "success", "parsed": 1},
            }],
        },
        "cp_telemetry.json": {
            "collector_script": {
                "upload_verified": True,
                "local_sha256": "a" * 64,
                "remote_sha256": "a" * 64,
                "remote_exit_status": 0,
                "done_marker_seen": True,
                "reported_total_gw": 1,
                "processed_gw": 1,
            },
            "remote_collection_marker": {
                "available": True,
                "started_epoch": int(time.time()) - 10,
                "completed_epoch": int(time.time()) - 5,
                "discovered": 1,
            },
            "remote_command_status": [{"device": "cp1", "interface_rc": 0, "route_rc": 0}],
            "remote_files": [
                {"file": "cp1_interfaces.txt", "size_bytes": 100, "age_seconds_at_download": 5},
                {"file": "cp1_routes.txt", "size_bytes": 100, "age_seconds_at_download": 5},
            ],
            "summary": {
                "devices": 1,
                "raw_txt_files": 2,
                "oldest_file_age_seconds": 5,
                "newest_file_age_seconds": 5,
                "command_failures": 0,
            },
        },
    }.items():
        (tmp_path / name).write_text(json.dumps(data), encoding="utf-8")

    monkeypatch.setenv("FBUDDY_CP_RAW_MAX_AGE_SECONDS", "3600")
    report = run_verification(tmp_path)

    assert report["phase"] == "0.5"
    assert report["collection_integrity"]["cp"]["status"] == "success"
    assert report["collection_integrity"]["vsx"]["status"] == "success"
    assert report["run_status"] == "success"
