import json
import time
import zipfile
from pathlib import Path

from checkpoint import cp_runner
from utils import run_context, support_bundle
from utils.verification import run_verification


def test_extended_cp_status_parses_retry_and_error_fields():
    raw = (
        "gw_a\t0\t0\t2\t1\t124\t0\tnone\tnone\ttimeout\tnone\n"
        "gw_b\t124\t0\t2\t1\t124\t0\ttimeout\tnone\ttimeout\tnone\n"
    )
    rows = cp_runner._parse_collection_status(raw)

    assert rows[0]["interface_attempts"] == 2
    assert rows[0]["interface_first_error"] == "timeout"
    assert cp_runner._command_retried(rows[0]) is True
    assert cp_runner._recovered_after_retry(rows[0]) is True

    assert rows[1]["interface_error"] == "timeout"
    assert cp_runner._command_failed(rows[1]) is True
    assert cp_runner._recovered_after_retry(rows[1]) is False


def test_cp_shell_uses_bounded_parallel_workers_and_single_capture_per_type():
    text = cp_runner.LOCAL_COLLECTION_SCRIPT.read_text(encoding="utf-8")

    assert "FBUDDY_CP_PARALLELISM" in text
    assert "FBUDDY_CP_FIRST_TIMEOUT_SECONDS" in text
    assert "FBUDDY_CP_RETRY_TIMEOUT_SECONDS" in text
    assert 'collect_gateway "$SEQ" "$CMA_NAME" "$GW" "$IP" "$SAFE_GW" "$MGMT_STATE" "$OBJECT_TYPE" "$VSX_CLUSTER_MEMBER" "$VS_CLUSTER_MEMBER" &' in text
    assert 'run_live_command IF "$IF_RAW" "$IF_ERR" "ip -details -4 addr show"' in text
    assert 'run_live_command RT "$RT_RAW" "$RT_ERR" "ip -4 route show table all"' in text
    assert "Preserve the legacy CSV feature without issuing a second remote command" in text

    # Old duplicate live-query paths must not come back.
    assert '"ip -4 addr show 2>/dev/null"' not in text
    assert "ROUTE_OUTPUT=$(timeout" not in text


def test_run_context_can_mark_stage_and_run_degraded(tmp_path, monkeypatch):
    output_dir = tmp_path / "output"
    runs_dir = tmp_path / "runs"
    output_dir.mkdir()

    monkeypatch.setattr(run_context, "OUTPUT_DIR", output_dir)
    monkeypatch.setattr(run_context, "RUNS_DIR", runs_dir)
    monkeypatch.setattr(run_context, "info", lambda _msg: None)

    ctx = run_context.RunContext.create()
    ctx.start_stage("cp")
    ctx.finish_stage("cp", {"failed_devices": 2}, status="degraded")
    ctx.write_manifest(status="degraded")

    manifest = json.loads(ctx.manifest_path.read_text(encoding="utf-8"))
    assert manifest["stages"]["cp"]["status"] == "degraded"
    assert manifest["stages"]["cp"]["details"]["failed_devices"] == 2
    assert manifest["status"] == "degraded"
    assert manifest["completed_at"] is not None


def test_verification_reports_failed_cp_devices_and_retry_metrics(tmp_path, monkeypatch):
    cp = [
        {"source": "cp", "device": "gw_a", "interfaces": [{"name": "eth0"}], "routes": []},
        {"source": "cp", "device": "gw_b", "interfaces": [], "routes": []},
    ]
    vsx = []
    pan = []
    unified = cp

    now = int(time.time())
    files = {
        "cp.json": cp,
        "vsx.json": vsx,
        "panorama_runtime.json": pan,
        "unified.json": unified,
        "vsx_raw.json": [],
        "vsx_telemetry.json": [],
        "panorama_telemetry.json": {"discovered": 0, "successful": 0, "failed": 0, "devices": []},
        "cp_telemetry.json": {
            "collector_script": {
                "upload_verified": True,
                "local_sha256": "a" * 64,
                "remote_sha256": "a" * 64,
                "remote_exit_status": 0,
                "done_marker_seen": True,
            },
            "remote_collection_marker": {
                "available": True,
                "started_epoch": now - 30,
                "completed_epoch": now - 1,
                "discovered": 2,
                "parallelism": 6,
                "collection_mode": "bounded_parallel",
            },
            "remote_command_status": [
                {
                    "device": "gw_a", "interface_rc": 0, "route_rc": 0,
                    "interface_attempts": 2, "route_attempts": 1,
                    "interface_error": "none", "route_error": "none",
                },
                {
                    "device": "gw_b", "interface_rc": 124, "route_rc": 0,
                    "interface_attempts": 2, "route_attempts": 1,
                    "interface_error": "timeout", "route_error": "none",
                },
            ],
            "remote_files": [
                {"file": "gw_a_interfaces.txt", "size_bytes": 10, "age_seconds_at_download": 1},
                {"file": "gw_a_routes.txt", "size_bytes": 10, "age_seconds_at_download": 1},
                {"file": "gw_b_interfaces.txt", "size_bytes": 0, "age_seconds_at_download": 1},
                {"file": "gw_b_routes.txt", "size_bytes": 10, "age_seconds_at_download": 1},
            ],
            "summary": {
                "raw_txt_files": 4,
                "command_status": "known",
                "command_failures": 1,
                "retried_devices": 2,
                "recovered_after_retry": 1,
                "parallelism": 6,
                "collection_mode": "bounded_parallel",
            },
        },
    }
    for name, data in files.items():
        (tmp_path / name).write_text(json.dumps(data), encoding="utf-8")

    monkeypatch.setenv("FBUDDY_CP_RAW_MAX_AGE_SECONDS", "3600")
    report = run_verification(tmp_path)
    cp_integrity = report["collection_integrity"]["cp"]

    assert cp_integrity["status"] == "warning"
    assert cp_integrity["command_failures"] == 1
    assert cp_integrity["retried_devices"] == 2
    assert cp_integrity["recovered_after_retry"] == 1
    warning = next(x for x in cp_integrity["warnings"] if x["code"] == "CP_REMOTE_COMMAND_FAILURE")
    assert warning["examples"][0]["device"] == "gw_b"
    assert warning["examples"][0]["interface_error"] == "timeout"


def test_support_bundle_has_anonymized_errors_json(tmp_path, monkeypatch):
    run_dir = tmp_path / "runs" / "20260101_000000_deadbeef"
    stage = run_dir / "stage"
    raw = run_dir / "raw"
    stage.mkdir(parents=True)
    raw.mkdir(parents=True)

    for name, data in {
        "cp.json": [{"source": "cp", "device": "REAL-GW", "interfaces": [], "routes": []}],
        "vsx.json": [],
        "panorama_runtime.json": [],
        "unified.json": [{"source": "cp", "device": "REAL-GW", "interfaces": [], "routes": []}],
    }.items():
        (stage / name).write_text(json.dumps(data), encoding="utf-8")

    (run_dir / "verification.json").write_text(json.dumps({"run_status": "warning", "sources": {}}), encoding="utf-8")
    (run_dir / "manifest.json").write_text(json.dumps({"status": "degraded"}), encoding="utf-8")
    (raw / "vsx_raw.json").write_text("[]", encoding="utf-8")
    (raw / "vsx_telemetry.json").write_text("[]", encoding="utf-8")
    (raw / "panorama_telemetry.json").write_text(json.dumps({"devices": []}), encoding="utf-8")
    (raw / "cp_telemetry.json").write_text(json.dumps({
        "summary": {"command_failures": 1, "failed_devices": 1, "parallelism": 6},
        "remote_command_status": [{
            "device": "REAL-GW",
            "interface_rc": 124,
            "route_rc": 0,
            "interface_attempts": 2,
            "route_attempts": 1,
            "interface_error": "timeout",
            "route_error": "none",
        }],
    }), encoding="utf-8")

    monkeypatch.setattr(support_bundle, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(support_bundle, "SUPPORT_KEY_FILE", tmp_path / "support.key")

    zip_path = support_bundle.run_support_bundle(run_dir)
    with zipfile.ZipFile(zip_path) as zf:
        assert "errors.json" in zf.namelist()
        errors_text = zf.read("errors.json").decode("utf-8")
        errors = json.loads(errors_text)

    assert errors["cp"]["count"] == 1
    assert errors["cp"]["devices"][0]["interface_error"] == "timeout"
    assert errors["cp"]["devices"][0]["device"].startswith("DEV_")
    assert "REAL-GW" not in errors_text
