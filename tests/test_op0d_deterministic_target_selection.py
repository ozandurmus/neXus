"""OP.0d — deterministic, fail-closed target selection for real-environment

validation. `--cp-config-targets` / `--pan-config-targets` narrow the already-
resolved CP physical-host candidates / already-discovered PAN connected
firewalls to an exact operator-approved allowlist, evaluated entirely before
any device contact. No new device command, no retry/concurrency change, no
regex/wildcard/substring matching -- exact identity only. All boundaries here
are mocked/synthetic; no real device access.
"""
from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from application.cli import build_parser
from configuration import checkpoint_config_collector as cp_collector
from configuration import panorama_config_collector as pan_collector

pytestmark = pytest.mark.configuration


# --- CP: pure selector unit behavior -----------------------------------------

def _cp_targets():
    return [
        cp_collector.PhysicalTarget(device="FW1", management_ip="10.0.0.1", object_type="gateway", entity_type="standalone_gateway"),
        cp_collector.PhysicalTarget(device="CL1", management_ip="10.0.0.2", object_type="cluster_member", entity_type="clusterxl_member", cluster_group_id="g1"),
        cp_collector.PhysicalTarget(device="CL2", management_ip="10.0.0.3", object_type="cluster_member", entity_type="clusterxl_member", cluster_group_id="g1"),
    ]


def test_cp_selector_narrows_to_exact_requested_entity_ids():
    resolved = cp_collector._apply_cp_target_selector(_cp_targets(), ["CL1", "CL2"])
    assert [t.device for t in resolved] == ["CL1", "CL2"]


def test_cp_selector_unknown_id_fails_before_transport():
    with pytest.raises(ValueError, match="unknown entity_id"):
        cp_collector._apply_cp_target_selector(_cp_targets(), ["CL1", "GHOST"])


def test_cp_selector_partial_resolution_fails_closed():
    # One of two requested ids does not exist -- the whole request is refused,
    # not silently narrowed to the one that matched.
    with pytest.raises(ValueError, match="unknown entity_id"):
        cp_collector._apply_cp_target_selector(_cp_targets(), ["FW1", "NOPE"])


def test_cp_selector_ambiguous_match_fails_closed():
    targets = _cp_targets()
    targets.append(cp_collector.PhysicalTarget(device="CL1", management_ip="10.0.0.99", object_type="cluster_member", entity_type="clusterxl_member", cluster_group_id="g1"))
    with pytest.raises(ValueError, match="ambiguous entity_id"):
        cp_collector._apply_cp_target_selector(targets, ["CL1"])


def test_cp_selector_never_broadens_scope():
    resolved = cp_collector._apply_cp_target_selector(_cp_targets(), ["CL1"])
    assert [t.device for t in resolved] == ["CL1"]


def test_cp_selector_rejects_empty_selector_syntax():
    with pytest.raises(ValueError, match="no valid entity_id"):
        cp_collector._apply_cp_target_selector(_cp_targets(), [" ", ""])


# --- CP: end-to-end via run_checkpoint_config_collection ---------------------

def test_cp_run_contacts_only_requested_targets(monkeypatch, tmp_path):
    contacted = []

    def fake_collect_host(target, **_kwargs):
        contacted.append(target.device)
        return [{
            "entity_id": target.device, "entity_type": target.entity_type, "device": target.device,
            "display_name": target.device, "status": "success", "error_class": None,
            "platform": {"family": "gaia", "label": "Gaia"},
            "identity_gate": {"accepted": True, "confidence": "HIGH"}, "history": {},
            "current_configuration": {"status": "success"},
        }]

    monkeypatch.setattr(cp_collector, "_resolve_targets", lambda: (_cp_targets(), []))
    monkeypatch.setattr(cp_collector, "_collect_host", fake_collect_host)
    monkeypatch.setattr(cp_collector, "_apply_cluster_member_semantics", lambda _rows: None)
    monkeypatch.setattr(cp_collector, "_strip_internal_projection_keys", lambda _rows: None)
    monkeypatch.setattr(cp_collector, "ConfigEvidenceStore", lambda: object())
    monkeypatch.setattr(cp_collector, "OUTPUT_DIR", tmp_path)
    monkeypatch.setattr(cp_collector, "register_sensitive_value", lambda *_a, **_k: None)

    cfg = SimpleNamespace(auth=SimpleNamespace(principal="user", secret="pass"))
    result = cp_collector.run_checkpoint_config_collection(
        cfg, stage="all", max_workers=1, target_entity_ids=["CL1"],
    )
    assert contacted == ["CL1"]
    assert result["summary"]["physical_hosts"] == 1


def test_cp_run_unknown_target_raises_before_any_collect_host_call(monkeypatch, tmp_path):
    contacted = []
    monkeypatch.setattr(cp_collector, "_resolve_targets", lambda: (_cp_targets(), []))
    monkeypatch.setattr(cp_collector, "_collect_host", lambda target, **_kwargs: contacted.append(target.device))
    monkeypatch.setattr(cp_collector, "OUTPUT_DIR", tmp_path)

    cfg = SimpleNamespace(auth=SimpleNamespace(principal="user", secret="pass"))
    with pytest.raises(ValueError, match="unknown entity_id"):
        cp_collector.run_checkpoint_config_collection(
            cfg, stage="all", max_workers=1, target_entity_ids=["GHOST"],
        )
    assert contacted == []


def test_cp_sample_stage_unchanged_when_selector_absent(monkeypatch, tmp_path):
    contacted = []
    monkeypatch.setattr(cp_collector, "_resolve_targets", lambda: (_cp_targets(), []))
    monkeypatch.setattr(cp_collector, "_collect_host", lambda target, **_kwargs: (contacted.append(target.device), [])[1])
    monkeypatch.setattr(cp_collector, "_apply_cluster_member_semantics", lambda _rows: None)
    monkeypatch.setattr(cp_collector, "_strip_internal_projection_keys", lambda _rows: None)
    monkeypatch.setattr(cp_collector, "ConfigEvidenceStore", lambda: object())
    monkeypatch.setattr(cp_collector, "OUTPUT_DIR", tmp_path)
    monkeypatch.setattr(cp_collector, "register_sensitive_value", lambda *_a, **_k: None)

    cfg = SimpleNamespace(auth=SimpleNamespace(principal="user", secret="pass"))
    result = cp_collector.run_checkpoint_config_collection(cfg, stage="sample", max_workers=1)
    # _sample_targets picks the standalone + the first 2-member cluster -- same
    # as pre-OP.0d behavior, unaffected by the new (absent) selector.
    assert sorted(contacted) == ["CL1", "CL2", "FW1"]
    assert result["summary"]["stage"] == "sample"


def test_cp_explicit_selector_takes_precedence_over_stage(monkeypatch, tmp_path):
    contacted = []
    monkeypatch.setattr(cp_collector, "_resolve_targets", lambda: (_cp_targets(), []))
    monkeypatch.setattr(cp_collector, "_collect_host", lambda target, **_kwargs: (contacted.append(target.device), [])[1])
    monkeypatch.setattr(cp_collector, "_apply_cluster_member_semantics", lambda _rows: None)
    monkeypatch.setattr(cp_collector, "_strip_internal_projection_keys", lambda _rows: None)
    monkeypatch.setattr(cp_collector, "ConfigEvidenceStore", lambda: object())
    monkeypatch.setattr(cp_collector, "OUTPUT_DIR", tmp_path)
    monkeypatch.setattr(cp_collector, "register_sensitive_value", lambda *_a, **_k: None)

    cfg = SimpleNamespace(auth=SimpleNamespace(principal="user", secret="pass"))
    # stage="sample" would normally pull in FW1/CL1/CL2; the explicit selector
    # narrows to exactly FW1 regardless.
    cp_collector.run_checkpoint_config_collection(
        cfg, stage="sample", max_workers=1, target_entity_ids=["FW1"],
    )
    assert contacted == ["FW1"]


# --- PAN: pure selector unit behavior -----------------------------------------

def _pan_devices():
    return [
        {"serial": "SER1", "hostname": "FW1", "connected": "yes"},
        {"serial": "SER2", "hostname": "FW2", "connected": "yes"},
        {"serial": "SER3", "hostname": "FW3", "connected": "no"},
    ]


def test_pan_selector_narrows_to_exact_requested_serials():
    devices = _pan_devices()
    connected = [d for d in devices if d["connected"] == "yes"]
    resolved = pan_collector._apply_pan_target_selector(devices, connected, ["SER1"])
    assert [d["serial"] for d in resolved] == ["SER1"]


def test_pan_selector_unknown_serial_fails_before_transport():
    devices = _pan_devices()
    connected = [d for d in devices if d["connected"] == "yes"]
    with pytest.raises(ValueError, match="unknown serial"):
        pan_collector._apply_pan_target_selector(devices, connected, ["GHOST"])


def test_pan_selector_disconnected_serial_fails_closed():
    devices = _pan_devices()
    connected = [d for d in devices if d["connected"] == "yes"]
    with pytest.raises(ValueError, match="not currently connected"):
        pan_collector._apply_pan_target_selector(devices, connected, ["SER3"])


def test_pan_selector_partial_resolution_fails_closed():
    devices = _pan_devices()
    connected = [d for d in devices if d["connected"] == "yes"]
    with pytest.raises(ValueError, match="unknown serial"):
        pan_collector._apply_pan_target_selector(devices, connected, ["SER1", "GHOST"])


def test_pan_selector_unknown_serial_hints_leading_zero_mismatch():
    # Recurring operator pain point: a serial retyped/copied through a
    # spreadsheet or numeric field loses its leading zero(s). The selector
    # must still fail closed (no silent acceptance) but should name the
    # discovered serial the operator most likely meant.
    devices = _pan_devices() + [{"serial": "0026109000729", "hostname": "FW4", "connected": "yes"}]
    connected = [d for d in devices if d["connected"] == "yes"]
    with pytest.raises(ValueError, match=r"did you mean 0026109000729\?"):
        pan_collector._apply_pan_target_selector(devices, connected, ["26109000729"])


def test_pan_selector_unknown_serial_without_zero_match_has_no_hint():
    devices = _pan_devices()
    connected = [d for d in devices if d["connected"] == "yes"]
    with pytest.raises(ValueError) as excinfo:
        pan_collector._apply_pan_target_selector(devices, connected, ["GHOST"])
    assert "did you mean" not in str(excinfo.value)


def test_pan_selector_ambiguous_serial_fails_closed():
    devices = _pan_devices()
    devices.append({"serial": "SER1", "hostname": "FW1-DUP", "connected": "yes"})
    connected = [d for d in devices if d["connected"] == "yes"]
    with pytest.raises(ValueError, match="ambiguous serial"):
        pan_collector._apply_pan_target_selector(devices, connected, ["SER1"])


def test_pan_selector_never_broadens_scope():
    devices = _pan_devices()
    connected = [d for d in devices if d["connected"] == "yes"]
    resolved = pan_collector._apply_pan_target_selector(devices, connected, ["SER2"])
    assert [d["serial"] for d in resolved] == ["SER2"]


def test_pan_selector_rejects_empty_selector_syntax():
    devices = _pan_devices()
    connected = [d for d in devices if d["connected"] == "yes"]
    with pytest.raises(ValueError, match="no valid serial"):
        pan_collector._apply_pan_target_selector(devices, connected, [" ", ""])


# --- PAN: end-to-end via run_panorama_config_evidence -------------------------

def _pan_common_mocks(monkeypatch, tmp_path, devices):
    from utils.config_evidence import ConfigEvidenceStore

    LOCAL_XML = b'<config version="11.1"><devices><entry name="localhost.localdomain"><deviceconfig/><network><interface/></network><vsys/></entry></devices></config>'

    monkeypatch.setattr(pan_collector, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(pan_collector, "_get_support_key", lambda: b"fixed-test-key")
    monkeypatch.setattr(pan_collector, "_tls_verify_setting", lambda: False)
    monkeypatch.setattr(pan_collector, "_direct_tls_verify_setting", lambda: False)
    monkeypatch.setattr(pan_collector, "get_api_key", lambda *a, **k: "PANKEY")
    monkeypatch.setattr(pan_collector, "get_firewall_api_key", lambda *a, **k: "FWKEY")
    monkeypatch.setattr(pan_collector, "get_devices", lambda *a, **k: devices)
    monkeypatch.setattr(pan_collector, "get_active_running_config", lambda *a, **k: LOCAL_XML)
    monkeypatch.setattr(pan_collector, "get_direct_active_config", lambda *a, **k: LOCAL_XML)
    monkeypatch.setattr(pan_collector, "get_direct_operational_config", lambda *a, **k: LOCAL_XML)

    def system_info(host, key, **kwargs):
        serial = next((d["serial"] for d in devices if d["management_ip"] == host), "UNKNOWN")
        return {"serial": serial, "hostname": "FW", "sw_version": "11.1", "model": "PA"}

    monkeypatch.setattr(pan_collector, "get_direct_system_info", system_info)

    class TempStore(ConfigEvidenceStore):
        def __init__(self):
            super().__init__(tmp_path / "configs")

    monkeypatch.setattr(pan_collector, "ConfigEvidenceStore", TempStore)


def test_pan_run_contacts_only_requested_serial(monkeypatch, tmp_path):
    devices = [
        {"serial": "SER1", "hostname": "FW1", "connected": "yes", "management_ip": "192.0.2.1", "model": "PA-TEST", "sw_version": "11.1"},
        {"serial": "SER2", "hostname": "FW2", "connected": "yes", "management_ip": "192.0.2.2", "model": "PA-TEST", "sw_version": "11.1"},
    ]
    _pan_common_mocks(monkeypatch, tmp_path, devices)
    cfg = SimpleNamespace(auth=SimpleNamespace(principal="user", secret="secret"), panorama_ip="192.0.2.250")

    result = pan_collector.run_panorama_config_evidence(
        cfg, limit=5, max_workers=1, target_serials=["SER1"], probe_pushed_template=False,
    )
    summary = result["summary"]
    assert summary["selected"] == 1
    assert summary["stage"] == "explicit-1-target(s)"


def test_pan_run_unknown_target_raises_before_any_firewall_call(monkeypatch, tmp_path):
    devices = [
        {"serial": "SER1", "hostname": "FW1", "connected": "yes", "management_ip": "192.0.2.1", "model": "PA-TEST", "sw_version": "11.1"},
    ]
    contacted = []
    _pan_common_mocks(monkeypatch, tmp_path, devices)
    monkeypatch.setattr(
        pan_collector, "get_direct_active_config",
        lambda *a, **k: (contacted.append(a), b"")[1] or (_ for _ in ()).throw(AssertionError("must not be called")),
    )
    cfg = SimpleNamespace(auth=SimpleNamespace(principal="user", secret="secret"), panorama_ip="192.0.2.250")

    with pytest.raises(ValueError, match="unknown serial"):
        pan_collector.run_panorama_config_evidence(
            cfg, limit=5, max_workers=1, target_serials=["GHOST"], probe_pushed_template=False,
        )
    assert contacted == []


def test_pan_limit_behavior_unchanged_when_selector_absent(monkeypatch, tmp_path):
    devices = [
        {"serial": f"SER{i}", "hostname": f"FW{i}", "connected": "yes", "management_ip": f"192.0.2.{i}", "model": "PA-TEST", "sw_version": "11.1"}
        for i in range(1, 4)
    ]
    _pan_common_mocks(monkeypatch, tmp_path, devices)
    cfg = SimpleNamespace(auth=SimpleNamespace(principal="user", secret="secret"), panorama_ip="192.0.2.250")

    result = pan_collector.run_panorama_config_evidence(cfg, limit=2, max_workers=1, probe_pushed_template=False)
    assert result["summary"]["selected"] == 2
    assert result["summary"]["stage"] == "first-2-connected"


def test_pan_explicit_selector_takes_precedence_over_limit(monkeypatch, tmp_path):
    devices = [
        {"serial": f"SER{i}", "hostname": f"FW{i}", "connected": "yes", "management_ip": f"192.0.2.{i}", "model": "PA-TEST", "sw_version": "11.1"}
        for i in range(1, 4)
    ]
    _pan_common_mocks(monkeypatch, tmp_path, devices)
    cfg = SimpleNamespace(auth=SimpleNamespace(principal="user", secret="secret"), panorama_ip="192.0.2.250")

    # limit=2 would normally admit SER1+SER2; the explicit selector narrows to
    # exactly SER3 regardless.
    result = pan_collector.run_panorama_config_evidence(
        cfg, limit=2, max_workers=1, target_serials=["SER3"], probe_pushed_template=False,
    )
    assert result["summary"]["selected"] == 1
    assert result["summary"]["stage"] == "explicit-1-target(s)"


# --- CLI surface ---------------------------------------------------------------

def test_cli_exposes_target_flags_with_precedence_documented():
    parser = build_parser()
    args = parser.parse_args(["--cp-config-collect", "--cp-config-targets", "CL1,CL2"])
    assert args.cp_config_targets == "CL1,CL2"
    args = parser.parse_args(["--only", "pan-config", "--pan-config-targets", "SER1,SER2"])
    assert args.pan_config_targets == "SER1,SER2"

    # Both flags' own help text documents precedence over stage/limit (source
    # contract check -- this is a maintainability guard, not a UI test).
    root = Path(__file__).resolve().parents[1]
    cli_source = (root / "application" / "cli.py").read_text(encoding="utf-8")
    assert "Takes precedence over --cp-config-stage" in cli_source
    assert "Takes precedence over " in cli_source and "--pan-config-limit/--pan-config-stage" in cli_source


def test_cli_defaults_leave_existing_stage_and_limit_flags_untouched():
    parser = build_parser()
    args = parser.parse_args([])
    assert args.cp_config_targets is None
    assert args.pan_config_targets is None
    assert args.cp_config_stage == "sample"
    assert args.pan_config_limit is None


# --- Safety invariants: no new command, no concurrency/retry change ----------

def test_no_new_device_command_introduced_by_target_selection():
    source = (Path(__file__).resolve().parents[1] / "configuration" / "checkpoint_config_collector.py").read_text(encoding="utf-8")
    selector_start = source.index("def _apply_cp_target_selector")
    selector_end = source.index("def _management_state_is_down")
    selector_body = source[selector_start:selector_end]
    assert "_run_exec" not in selector_body
    assert "interactive.run" not in selector_body
    assert "paramiko" not in selector_body.lower()
    assert "_connect(" not in selector_body


def test_no_concurrency_or_retry_behavior_change():
    source = (Path(__file__).resolve().parents[1] / "configuration" / "checkpoint_config_collector.py").read_text(encoding="utf-8")
    selector_start = source.index("def _apply_cp_target_selector")
    selector_end = source.index("def _management_state_is_down")
    selector_body = source[selector_start:selector_end]
    assert "ThreadPoolExecutor" not in selector_body
    assert "retry" not in selector_body.lower()
    assert "sleep" not in selector_body.lower()
