import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from configuration import checkpoint_config_collector as collector
from utils.config_ui import build_configuration_ui_payload


ROOT = Path(__file__).resolve().parents[1]
APP = (ROOT / "static" / "app.js").read_text(encoding="utf-8")
CSS = (ROOT / "static" / "style.css").read_text(encoding="utf-8")
TEMPLATE = (ROOT / "templates" / "index.html").read_text(encoding="utf-8")
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")


def _result(stdout="", *, success=True, stderr="", error_class=None, timeout=False):
    return {
        "success": success,
        "error_class": error_class or ("none" if success else "command_error"),
        "error_detail": None,
        "timeout": timeout,
        "exit_status": 0 if success else 1,
        "duration_ms": 1,
        "stdout": stdout,
        "stderr": stderr,
    }


def test_quantum_spark_classification_prefers_explicit_evidence_and_exact_model_tokens():
    explicit = collector._classify_platform(
        version_stdout="Check Point Quantum Spark - Gaia Embedded R82.00",
        asset_stdout="",
        model=None,
    )
    assert explicit["family"] == "gaia_embedded"
    assert explicit["confidence"] == "HIGH"

    hinted = collector._classify_platform(
        version_stdout="Check Point appliance 1570 R81.10",
        asset_stdout="",
        model=None,
    )
    assert hinted["family"] == "gaia_embedded"
    assert hinted["confidence"] == "MEDIUM"

    enterprise = collector._classify_platform(
        version_stdout="Check Point Gaia R81.20",
        asset_stdout="Model: 15000",
        model="15000",
    )
    assert enterprise["family"] == "gaia"
    assert collector._model_token("15000") is None

    copyright_year = collector._classify_platform(
        version_stdout="Copyright 2000 Check Point Software Technologies\nCheck Point Gaia R81.20",
        asset_stdout="",
        model=None,
    )
    assert copyright_year["family"] == "gaia"


def test_expert_first_gaia_read_has_show_only_direct_clish_fallback(monkeypatch):
    commands = []

    def fake_exec(_ssh, command, _timeout):
        commands.append(command)
        if command.startswith("clish -c"):
            return _result("Unknown command: clish", success=False, error_class="cli_rejected")
        return _result("Check Point Gaia Embedded R82.00\n")

    monkeypatch.setattr(collector, "_run_exec", fake_exec)
    result, mode = collector._run_gaia_read(object(), "show version all", 5)
    assert result["success"] is True
    assert mode == "direct_login_clish"
    assert commands[0].startswith("clish -c")
    assert commands[1] == "show version all"

    with pytest.raises(ValueError):
        collector._run_gaia_read(object(), "set hostname unsafe", 5)


def test_spark_capability_gap_is_not_confused_with_authorization_or_operational_failure():
    unsupported = _result("Unknown command: show configuration", success=False, error_class="cli_rejected")
    assert collector._configuration_failure_reason(unsupported, "gaia_embedded") == (
        "gaia_embedded_capability_unsupported", "capability_gap"
    )

    denied = _result("Permission denied", success=False, error_class="cli_rejected")
    assert collector._configuration_failure_reason(denied, "gaia_embedded") == (
        "gaia_command_not_authorized", "authorization_failure"
    )

    odd_shape = _result("configuration output without canonical set lines")
    assert collector._configuration_failure_reason(odd_shape, "gaia_embedded") == (
        "gaia_embedded_configuration_shape_unsupported", "capability_gap"
    )
    assert collector._configuration_failure_reason(odd_shape, "gaia") == (
        "gaia_configuration_shape_unrecognized", "operational_failure"
    )


def test_clusterxl_runtime_role_parses_local_member_without_inference():
    sample = """
Cluster Mode: High Availability
ID         Unique Address  Assigned Load   State
1 (local)  10.0.0.1       100%            ACTIVE
2          10.0.0.2       0%              STANDBY
"""
    assert collector._parse_clusterxl_runtime_role(sample, "GW-01") == "ACTIVE"

    sample_by_name = "GW-01 10.0.0.1 STANDBY READY\nGW-02 10.0.0.2 ACTIVE\n"
    assert collector._parse_clusterxl_runtime_role(sample_by_name, "GW-01.example.net") == "STANDBY READY"
    assert collector._parse_clusterxl_runtime_role("GW-02 ACTIVE", "GW-01") is None


def test_asset_scalar_parser_accepts_colon_and_table_layouts():
    colon = "Model: 6500\nSerial Number: ABC123\n"
    table = "Appliance Model          1570\nAppliance Serial Number  SPARK123\n"
    assert collector._parse_asset_field(colon, ("Model",)) == "6500"
    assert collector._parse_asset_field(colon, ("Serial Number",)) == "ABC123"
    assert collector._parse_asset_field(table, ("Appliance Model",)) == "1570"
    assert collector._parse_asset_field(table, ("Appliance Serial Number",)) == "SPARK123"


def test_vsx_presentation_pair_is_inferred_only_for_conventional_member_names():
    g1 = collector._vsx_presentation_group("CP-VSX-PAIR-1_", "CMA-A")
    g2 = collector._vsx_presentation_group("CP-VSX-PAIR-2_", "CMA-A")
    other_cma = collector._vsx_presentation_group("CP-VSX-PAIR-2_", "CMA-B")
    assert g1[0] == g2[0]
    assert g1[1] == "CP-VSX-PAIR-VSX"
    assert g1[2] == "inferred_member_name_pattern_presentation_only"
    assert other_cma[0] != g1[0]
    assert collector._vsx_presentation_group("VSX-HOST-WITHOUT-MEMBER-SUFFIX", "CMA-A") == (None, None, None)


def test_configuration_ui_carries_platform_ha_failure_and_vsx_group_metadata():
    current = collector.build_checkpoint_current_configuration(
        ["set hostname CP1", "set dns primary 10.0.0.53"],
        secret_bearing_line_count=0,
        entity_type="vsx_host",
    )
    cp_result = {
        "summary": {
            "selected": 1,
            "success": 1,
            "failed": 0,
            "unavailable": 0,
            "operational_failures": 0,
            "capability_gaps": 0,
            "entity_type_counts": {"vsx_host": {"selected": 1, "success": 1, "unavailable": 0}},
            "platform_counts": {"gaia_embedded": {"selected": 1, "success": 1, "unavailable": 0}},
            "model_covered": 1,
            "serial_covered": 1,
            "ha_role_covered": 1,
        },
        "devices": [{
            "entity_id": "CP1",
            "entity_type": "vsx_host",
            "device": "CP1",
            "display_name": "CP1",
            "management_ip": "10.0.0.1",
            "status": "success",
            "platform": {"family": "gaia_embedded", "label": "Quantum Spark / Gaia Embedded"},
            "model": "1570",
            "serial": "SER1",
            "sw_version": "R82.00",
            "ha_role": "ACTIVE",
            "ha_role_source": "cphaprob_state_runtime",
            "presentation_group_id": "pg1",
            "presentation_group_label": "CP-VSX",
            "presentation_group_source": "inferred_member_name_pattern_presentation_only",
            "identity_gate": {"accepted": True, "status": "VERIFIED", "confidence": "HIGH"},
            "current_configuration": current,
            "evidence": {"actual": {"status": "success", "method": collector.PHYSICAL_METHOD}},
            "history": {"actual_change_state": "first"},
        }],
    }
    payload = build_configuration_ui_payload(None, checkpoint_config_result=cp_result)
    device = payload["devices"][0]
    assert device["platform_family"] == "gaia_embedded"
    assert device["ha_role"] == "ACTIVE"
    assert device["presentation_group_id"] == "pg1"
    assert payload["fleet"]["checkpoint_model_covered"] == 1
    assert payload["fleet"]["checkpoint_serial_covered"] == 1
    assert payload["fleet"]["checkpoint_ha_role_covered"] == 1


def test_summary_separates_capability_gap_from_operational_failure(tmp_path, monkeypatch):
    targets = [
        collector.PhysicalTarget(device="SPARK1", management_ip="10.0.0.1", object_type="gateway", entity_type="standalone_gateway"),
        collector.PhysicalTarget(device="GW1", management_ip="10.0.0.2", object_type="gateway", entity_type="standalone_gateway"),
    ]
    rows = {
        "SPARK1": [{
            "entity_id": "SPARK1", "entity_type": "standalone_gateway", "device": "SPARK1", "display_name": "SPARK1",
            "status": "failed", "error_class": "gaia_embedded_capability_unsupported", "failure_family": "capability_gap",
            "platform": {"family": "gaia_embedded", "label": "Quantum Spark / Gaia Embedded"},
            "identity_gate": {"accepted": True, "confidence": "HIGH"}, "history": {}, "current_configuration": {"status": "unavailable"},
        }],
        "GW1": [{
            "entity_id": "GW1", "entity_type": "standalone_gateway", "device": "GW1", "display_name": "GW1",
            "status": "failed", "error_class": "ssh_connect_timeout", "failure_family": "reachability_failure",
            "platform": {"family": "gaia", "label": "Gaia"},
            "identity_gate": {"accepted": False, "confidence": "LOW"}, "history": {}, "current_configuration": {"status": "unavailable"},
        }],
    }

    monkeypatch.setattr(collector, "_resolve_targets", lambda: (targets, []))
    monkeypatch.setattr(collector, "_collect_host", lambda target, **_kwargs: rows[target.device])
    monkeypatch.setattr(collector, "_apply_cluster_member_semantics", lambda _rows: None)
    monkeypatch.setattr(collector, "_strip_internal_projection_keys", lambda _rows: None)
    monkeypatch.setattr(collector, "ConfigEvidenceStore", lambda: object())
    monkeypatch.setattr(collector, "OUTPUT_DIR", tmp_path)
    monkeypatch.setattr(collector, "register_sensitive_value", lambda *_a, **_k: None)

    cfg = SimpleNamespace(auth=SimpleNamespace(principal="user", secret="pass"))
    result = collector.run_checkpoint_config_collection(cfg, stage="all", max_workers=1)
    summary = result["summary"]
    assert summary["unavailable"] == 2
    assert summary["capability_gaps"] == 1
    assert summary["operational_failures"] == 1
    assert summary["collector_gate"] is False
    assert summary["coverage_complete"] is False
    assert summary["failure_reason_counts"]["gaia_embedded_capability_unsupported"] == 1


def test_device_ux_source_contract_has_vendor_fleets_vsx_grouping_and_responsive_header():
    assert '["all", "All", all]' in APP
    assert '["check_point", "Check Point", cp]' in APP
    assert '["palo_alto", "Palo Alto", pan]' in APP
    assert "Check Point Fleet" in APP or "configFleetVendorLabel" in APP
    assert "VSX Pair" in APP
    assert "presentation_group_id" in APP
    assert "configHeaderToggle" in TEMPLATE
    assert "configSidebarToggle" in TEMPLATE
    assert "configFleetFilters" in TEMPLATE
    assert "securityexpert-config-header-expanded" in APP
    assert "@media (max-width: 900px)" in CSS
    assert ".config-workspace.sidebar-open .config-sidebar" in CSS
    assert "@media (max-width: 680px)" in CSS
    assert "grid-template-columns: 1fr" in CSS
    assert "0.6.1B.1 SAFE COLLECTION SUMMARY" in MAIN
    assert "Operational failures:" in MAIN
    assert "Capability gaps:" in MAIN
    assert "Quantum Spark/Embedded:" in MAIN
