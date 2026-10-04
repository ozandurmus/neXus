import json
import time
import zipfile

from checkpoint import cp_runner
from utils.verification import run_verification
from utils import support_bundle
import pytest

pytestmark = pytest.mark.inventory


def test_cp_status_parses_management_state_and_outcome():
    raw = (
        "gw_up\t0\t0\t1\t1\t0\t0\tnone\tnone\tnone\tnone\tcommunicating\tsuccess\n"
        "gw_down\t126\t126\t0\t0\t126\t126\tmanagement_down\tmanagement_down\tmanagement_down\tmanagement_down\tnot_communicating\tmanagement_down\n"
    )
    rows = cp_runner._parse_collection_status(raw)
    assert rows[0]["management_state"] == "communicating"
    assert rows[0]["collection_outcome"] == "success"
    assert cp_runner._command_failed(rows[0]) is False
    assert cp_runner._management_down(rows[1]) is True
    assert cp_runner._command_failed(rows[1]) is False


def test_cp_shell_discovers_connection_state_skips_management_down_and_isolates_failed_output():
    text = cp_runner.LOCAL_COLLECTION_SCRIPT.read_text(encoding="utf-8")
    assert "-a __name__,ipaddr,connection_state" in text
    assert "management_down" in text
    assert 'ERROR_DIR="${RAW_DIR}/errors"' in text
    assert 'mv "$TMP_OUTPUT" "$OUTPUT_FILE"' in text
    assert 'rm -f "$TMP_OUTPUT" "$TMP_ERROR" "$OUTPUT_FILE"' in text
    assert "connection_state='communicating'" not in text


def test_verifier_reports_management_down_separately_from_command_failure(tmp_path, monkeypatch):
    cp = [{"source": "cp", "device": "gw_up", "interfaces": [{"name": "eth0"}], "routes": []}]
    now = int(time.time())
    files = {
        "cp.json": cp,
        "vsx.json": [],
        "panorama_runtime.json": [],
        "unified.json": cp,
        "vsx_raw.json": [],
        "vsx_telemetry.json": [],
        "panorama_telemetry.json": {"discovered": 0, "successful": 0, "failed": 0, "devices": []},
        "cp_telemetry.json": {
            "collector_script": {
                "upload_verified": True, "local_sha256": "a" * 64,
                "remote_sha256": "a" * 64, "remote_exit_status": 0,
                "done_marker_seen": True,
            },
            "remote_collection_marker": {
                "available": True, "started_epoch": now - 10, "completed_epoch": now - 1,
                "discovered": 2, "attempted": 1, "successful": 1,
                "management_up": 1, "management_down": 1, "management_unknown": 0,
            },
            "remote_command_status": [
                {"device": "gw_up", "interface_rc": 0, "route_rc": 0,
                 "interface_attempts": 1, "route_attempts": 1,
                 "interface_error": "none", "route_error": "none",
                 "management_state": "communicating", "collection_outcome": "success"},
                {"device": "gw_down", "interface_rc": 126, "route_rc": 126,
                 "interface_attempts": 0, "route_attempts": 0,
                 "interface_error": "management_down", "route_error": "management_down",
                 "management_state": "not_communicating", "collection_outcome": "management_down"},
            ],
            "remote_files": [
                {"file": "gw_up_interfaces.txt", "size_bytes": 10, "age_seconds_at_download": 1},
                {"file": "gw_up_routes.txt", "size_bytes": 10, "age_seconds_at_download": 1},
            ],
            "summary": {"raw_txt_files": 2, "command_status": "known", "command_failures": 0,
                        "successful_devices": 1, "failed_devices": 0, "management_down_devices": 1},
        },
    }
    for name, data in files.items():
        (tmp_path / name).write_text(json.dumps(data), encoding="utf-8")
    monkeypatch.setenv("FBUDDY_CP_RAW_MAX_AGE_SECONDS", "3600")
    report = run_verification(tmp_path)
    integ = report["collection_integrity"]["cp"]
    assert integ["command_failures"] == 0
    assert integ["management_down_devices"] == 1
    assert integ["parsed_devices"] == 1
    assert not any(x["code"] == "CP_REMOTE_COMMAND_FAILURE" for x in integ["warnings"])
    assert any(x["code"] == "CP_MANAGEMENT_DEVICE_DOWN" for x in integ["warnings"])


def test_support_bundle_separates_management_down_from_cp_errors(tmp_path, monkeypatch):
    run_dir = tmp_path / "runs" / "20260101_000000_deadbeef"
    stage = run_dir / "stage"
    raw = run_dir / "raw"
    stage.mkdir(parents=True)
    raw.mkdir(parents=True)
    for name, data in {
        "cp.json": [{"source": "cp", "device": "UP", "interfaces": [], "routes": []}],
        "vsx.json": [], "panorama_runtime.json": [],
        "unified.json": [{"source": "cp", "device": "UP", "interfaces": [], "routes": []}],
    }.items():
        (stage / name).write_text(json.dumps(data), encoding="utf-8")
    (run_dir / "verification.json").write_text(json.dumps({"run_status": "warning", "sources": {}}), encoding="utf-8")
    (run_dir / "manifest.json").write_text(json.dumps({"status": "degraded"}), encoding="utf-8")
    (raw / "vsx_raw.json").write_text("[]", encoding="utf-8")
    (raw / "vsx_telemetry.json").write_text("[]", encoding="utf-8")
    (raw / "panorama_telemetry.json").write_text(json.dumps({"devices": []}), encoding="utf-8")
    (raw / "cp_telemetry.json").write_text(json.dumps({
        "summary": {"command_failures": 0, "management_down_devices": 1},
        "remote_command_status": [{
            "device": "REAL-DOWN", "interface_rc": 126, "route_rc": 126,
            "interface_error": "management_down", "route_error": "management_down",
            "management_state": "not_communicating", "collection_outcome": "management_down",
        }],
    }), encoding="utf-8")
    monkeypatch.setattr(support_bundle, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(support_bundle, "SUPPORT_KEY_FILE", tmp_path / "support.key")
    zip_path = support_bundle.run_support_bundle(run_dir)
    with zipfile.ZipFile(zip_path) as zf:
        errors = json.loads(zf.read("errors.json"))
        text = zf.read("errors.json").decode()
    assert errors["cp"]["count"] == 0
    assert errors["cp"]["management_down_count"] == 1
    assert errors["cp"]["management_down_devices"][0]["device"].startswith("DEV_")
    assert "REAL-DOWN" not in text
