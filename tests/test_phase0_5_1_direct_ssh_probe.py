import json
import time
import zipfile

from checkpoint import cp_runner, direct_ssh_probe
from utils.verification import run_verification
from utils import support_bundle
import pytest

pytestmark = pytest.mark.inventory


def test_cp_status_extended_target_fields_are_parsed():
    raw = (
        "Spark_One\t3\t3\t2\t2\t124\t124\tcommand_error\tcommand_error\t"
        "timeout\ttimeout\tcommunicating\tcollection_failed\t192.0.2.44\tCMA1\n"
    )
    row = cp_runner._parse_collection_status(raw)[0]
    assert row["management_ip"] == "192.0.2.44"
    assert row["cma"] == "CMA1"
    assert row["collection_outcome"] == "collection_failed"


def test_cp_shell_status_keeps_management_target_for_direct_probe():
    text = cp_runner.LOCAL_COLLECTION_SCRIPT.read_text(encoding="utf-8")
    assert '"$IP" "$CMA_NAME"' in text


def test_direct_ssh_probe_is_observe_only_and_candidates_are_failed_or_partial(tmp_path, monkeypatch):
    rows = [
        {"device": "ok", "management_ip": "192.0.2.1", "management_state": "communicating", "collection_outcome": "success"},
        {"device": "failed", "management_ip": "192.0.2.2", "management_state": "communicating", "collection_outcome": "collection_failed"},
        {"device": "down", "management_ip": "192.0.2.3", "management_state": "uninitialized", "collection_outcome": "management_down"},
    ]

    class Auth:
        principal = "user"
        secret = "pass"

    class Cfg:
        auth = Auth()

    seen = []

    def fake_probe(row, **kwargs):
        seen.append(row["device"])
        return {
            "device": row["device"],
            "management_ip": row["management_ip"],
            "management_state": row["management_state"],
            "cprid_outcome": row["collection_outcome"],
            "ssh_reachable": True,
            "authenticated": True,
            "inventory_cli_capable": True,
            "platform_hint": "quantum_spark",
            "commands": {},
            "error_class": "none",
        }

    monkeypatch.setattr(direct_ssh_probe, "_probe_one", fake_probe)
    monkeypatch.setattr(direct_ssh_probe, "OUTPUT_FILE", tmp_path / "cp_direct_ssh_probe.json")
    monkeypatch.setenv("FBUDDY_CP_DIRECT_SSH_PARALLELISM", "1")
    payload = direct_ssh_probe.probe_direct_ssh_fallback(Cfg(), rows)

    assert seen == ["failed"]
    assert payload["mode"] == "observe_only"
    assert payload["read_only"] is True
    assert payload["configuration_collected"] is False
    assert payload["summary"]["inventory_cli_capable"] == 1


def test_support_safe_probe_never_contains_raw_cli_output():
    raw_secret = "show route\nS 10.20.30.0/24 via 10.20.30.1, LAN1"
    probe = {
        "summary": {"candidates": 1},
        "devices": [{
            "device": "REAL-SPARK",
            "management_ip": "10.10.10.10",
            "commands": {
                "routes": {
                    "success": True,
                    "command": "show route all",
                    "stdout": raw_secret,
                    "stderr": "secret error body",
                    "stdout_bytes": len(raw_secret),
                    "stdout_lines": 2,
                    "stderr_bytes": 17,
                    "fingerprint": "a" * 64,
                }
            },
        }],
    }
    safe = direct_ssh_probe.support_safe_probe(probe)
    encoded = json.dumps(safe)
    assert raw_secret not in encoded
    assert "secret error body" not in encoded
    assert safe["devices"][0]["commands"]["routes"]["success"] is True


def test_verifier_reports_direct_ssh_fallback_capability(tmp_path, monkeypatch):
    now = int(time.time())
    cp = []
    files = {
        "cp.json": cp,
        "vsx.json": [],
        "panorama_runtime.json": [],
        "unified.json": [],
        "vsx_raw.json": [],
        "vsx_telemetry.json": [],
        "panorama_telemetry.json": {"discovered": 0, "successful": 0, "failed": 0, "devices": []},
        "cp_telemetry.json": {
            "collector_script": {"upload_verified": True, "local_sha256": "a" * 64, "remote_sha256": "a" * 64, "remote_exit_status": 0, "done_marker_seen": True},
            "remote_collection_marker": {"available": True, "started_epoch": now - 10, "completed_epoch": now - 1, "discovered": 1},
            "remote_command_status": [{
                "device": "Spark", "interface_rc": 3, "route_rc": 3,
                "interface_attempts": 2, "route_attempts": 2,
                "interface_error": "command_error", "route_error": "command_error",
                "management_state": "communicating", "collection_outcome": "collection_failed",
            }],
            "remote_files": [],
            "summary": {
                "raw_txt_files": 0, "command_status": "known", "command_failures": 1,
                "failed_devices": 1, "direct_ssh_probe_candidates": 1,
                "direct_ssh_reachable": 1, "direct_ssh_authenticated": 1,
                "direct_ssh_inventory_cli_capable": 1, "direct_ssh_spark_hints": 1,
            },
        },
    }
    for name, data in files.items():
        (tmp_path / name).write_text(json.dumps(data), encoding="utf-8")
    monkeypatch.setenv("FBUDDY_CP_RAW_MAX_AGE_SECONDS", "3600")
    report = run_verification(tmp_path)
    integ = report["collection_integrity"]["cp"]
    assert integ["direct_ssh_inventory_cli_capable"] == 1
    assert any(x["code"] == "CP_DIRECT_SSH_FALLBACK_CAPABLE" for x in integ["warnings"])


def test_support_bundle_hmac_anonymizes_direct_ssh_probe_and_strips_raw(tmp_path, monkeypatch):
    run_dir = tmp_path / "runs" / "20260101_000000_deadbeef"
    stage = run_dir / "stage"
    raw = run_dir / "raw"
    stage.mkdir(parents=True)
    raw.mkdir(parents=True)
    for name, data in {
        "cp.json": [], "vsx.json": [], "panorama_runtime.json": [], "unified.json": [],
    }.items():
        (stage / name).write_text(json.dumps(data), encoding="utf-8")
    (run_dir / "verification.json").write_text(json.dumps({"run_status": "warning", "sources": {}}), encoding="utf-8")
    (run_dir / "manifest.json").write_text(json.dumps({"status": "degraded"}), encoding="utf-8")
    (raw / "vsx_raw.json").write_text("[]", encoding="utf-8")
    (raw / "vsx_telemetry.json").write_text("[]", encoding="utf-8")
    (raw / "panorama_telemetry.json").write_text(json.dumps({"devices": []}), encoding="utf-8")
    (raw / "cp_telemetry.json").write_text(json.dumps({"summary": {}, "remote_command_status": []}), encoding="utf-8")
    (raw / "cp_direct_ssh_probe.json").write_text(json.dumps({
        "summary": {"candidates": 1, "ssh_reachable": 1, "authenticated": 1, "inventory_cli_capable": 1},
        "devices": [{
            "device": "SYNTHETIC_SPARK_REAL",
            "management_ip": "10.99.88.77",
            "ssh_reachable": True,
            "authenticated": True,
            "inventory_cli_capable": True,
            "commands": {"interfaces": {"success": True, "command": "show interfaces table", "stdout": "LAN1 10.99.88.77", "stderr": "", "stdout_bytes": 17, "stdout_lines": 1, "fingerprint": "b" * 64}},
        }],
    }), encoding="utf-8")

    monkeypatch.setattr(support_bundle, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(support_bundle, "SUPPORT_KEY_FILE", tmp_path / "support.key")
    zip_path = support_bundle.run_support_bundle(run_dir)
    with zipfile.ZipFile(zip_path) as zf:
        text = zf.read("cp_direct_ssh_probe_anonymized.json").decode("utf-8")
    assert "SYNTHETIC_SPARK_REAL" not in text
    assert "10.99.88.77" not in text
    assert "LAN1 10.99.88.77" not in text
    assert "DEV_" in text
    assert "IP_" in text


def test_direct_ssh_probe_writes_runtime_output_without_repository_output(tmp_path, monkeypatch):
    runtime_output = tmp_path / "runtime" / "output"

    class RuntimePaths:
        output_root = runtime_output

    class Auth:
        principal = "user"
        secret = "pass"

    class Cfg:
        auth = Auth()
        runtime_paths = RuntimePaths()

    monkeypatch.setenv("FBUDDY_CP_DIRECT_SSH_PROBE_ENABLED", "0")
    legacy_output = tmp_path / "legacy" / "output" / "cp_direct_ssh_probe.json"
    monkeypatch.setattr(direct_ssh_probe, "OUTPUT_FILE", legacy_output)

    payload = direct_ssh_probe.probe_direct_ssh_fallback(Cfg(), [])

    runtime_file = runtime_output / "cp_direct_ssh_probe.json"
    assert payload["enabled"] is False
    assert runtime_file.exists()
    assert not runtime_file.with_suffix(runtime_file.suffix + ".tmp").exists()
    assert not legacy_output.exists()
