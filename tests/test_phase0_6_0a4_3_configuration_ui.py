import json
from pathlib import Path

from utils.config_ui import build_configuration_ui_payload
import pytest
from utils.html_export import compose_report_script as _composed_report_script

pytestmark = pytest.mark.configuration


ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = (ROOT / "templates" / "index.html").read_text(encoding="utf-8")
APP = _composed_report_script()
CSS = (ROOT / "static" / "style.css").read_text(encoding="utf-8")
# codebase_modularization (backend): the integration-checkpoint pipeline moved
# from main.py into application/workflows/checkpoint.py.
CHECKPOINT_WF = (ROOT / "application" / "workflows" / "checkpoint.py").read_text(encoding="utf-8")
HTML_EXPORT = (ROOT / "utils" / "html_export.py").read_text(encoding="utf-8")


def _config_result(tmp_path: Path):
    manifest = {
        "schema_version": "0.6.0A4.2.2",
        "devices": [
            {
                "device": "FW-A",
                "serial": "SER123",
                "results": [
                    {
                        "alignment_key": "/config/devices/entry[@name='__DEVICE__']/deviceconfig/system/hostname",
                        "classification": "ALIGNED",
                        "category": "system",
                        "expected_source_kind": "template",
                        "expected_source_name": "CommonTemplate",
                        "expected_value_sha256": "do-not-copy-host-hash",
                        "effective_value_sha256": "do-not-copy-effective-host-hash",
                        "confidence": "high",
                    },
                    {
                        "alignment_key": "/config/devices/entry[@name='__DEVICE__']/deviceconfig/system/dns-setting/servers/primary",
                        "classification": "ALIGNED",
                        "category": "dns",
                        "expected_source_kind": "template",
                        "expected_source_name": "CommonTemplate",
                        "expected_value_sha256": "do-not-copy-dns-hash",
                        "effective_value_sha256": "also-do-not-copy-dns-hash",
                        "confidence": "high",
                    },
                    {
                        "alignment_key": "/config/devices/entry[@name='__DEVICE__']/deviceconfig/system/permitted-ip/entry[@name='10.10.0.0/16']/description",
                        "classification": "LOCAL_OVERRIDE",
                        "category": "system",
                        "expected_source_kind": "template",
                        "expected_source_name": "CommonTemplate",
                        "expected_value_sha256": "do-not-copy-this-hash",
                        "effective_value_sha256": "also-do-not-copy-this-hash",
                        "confidence": "high",
                        "reason": "effective_differs_from_expected_and_matches_local_active_scalar",
                    },
                    {
                        "alignment_key": "/config/devices/entry[@name='__DEVICE__']/deviceconfig/high-availability/peer-ip",
                        "classification": "MEMBER_SPECIFIC",
                        "category": "ha",
                        "confidence": "medium",
                        "reason": "member_relative_ha_setting",
                    },
                ],
            }
        ],
    }
    manifest_path = tmp_path / "setting-alignment.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    snapshot = tmp_path / "effective-snapshot"
    snapshot.mkdir()
    (snapshot / "direct-effective-running.xml").write_text(
        """<?xml version='1.0' encoding='UTF-8'?>
<config>
  <devices>
    <entry name="localhost.localdomain">
      <deviceconfig>
        <system>
          <hostname src="tpl">FW-A</hostname>
          <domain src="tpl">corp.example</domain>
          <timezone src="tpl">Europe/Istanbul</timezone>
          <dns-setting src="tpl"><servers><primary>10.0.0.53</primary><secondary>10.0.0.54</secondary></servers></dns-setting>
          <ntp-servers src="tpl"><primary-ntp-server><ntp-server-address>10.0.0.123</ntp-server-address></primary-ntp-server></ntp-servers>
          <permitted-ip><entry name="10.10.0.0/16" src="local"><description>operator-mgmt</description></entry></permitted-ip>
          <device-telemetry><region>americas</region><threat-prevention>yes</threat-prevention></device-telemetry>
          <password>SUPER_SECRET_DO_NOT_EMBED</password>
        </system>
        <high-availability>
          <enabled src="tpl">yes</enabled>
          <group><group-id src="tpl">11</group-id></group>
          <peer-ip>1.1.1.1</peer-ip>
        </high-availability>
      </deviceconfig>
      <vsys><entry name="vsys1"><display-name>Payments</display-name></entry></vsys>
    </entry>
  </devices>
</config>
""",
        encoding="utf-8",
    )

    effective_artifact = {
        "status": "success",
        "method": "DIRECT_EFFECTIVE",
        "change_state": "same",
        "sha256": "do-not-copy-effective-hash",
        "snapshot": str(snapshot),
        "artifact_file": "direct-effective-running.xml",
        "structural_validation": {
            "schema_status": "pass",
            "counts": {
                "vsys_entries": 1,
                "virtual_router_entries": 2,
                "zone_entries": 3,
                "interface_definitions_total": 4,
                "security_rule_entries_total": 5,
            },
        },
    }

    return {
        "setting_alignment_manifest_path": str(manifest_path),
        "summary": {
            "run_id": "20260823_201923_1db52a0c",
            "stage": "all-connected",
            "selected": 1,
            "success": 1,
            "primary_evidence_success": 1,
            "alignment_evidence_complete": 1,
            "setting_alignment_classifications": {
                "ALIGNED": 20,
                "LOCAL_OVERRIDE": 1,
                "MEMBER_SPECIFIC": 1,
                "EXPECTED_ONLY": 2,
                "LOCAL_ONLY": 3,
            },
            "setting_alignment_category_counts": {
                "system": {"ALIGNED": 10, "LOCAL_OVERRIDE": 1},
                "ha": {"ALIGNED": 10, "MEMBER_SPECIFIC": 1},
            },
            "setting_alignment_devices_with_local_override": 1,
            "setting_alignment_devices_with_effective_drift": 0,
            "setting_alignment_devices_out_of_sync": 0,
            "semantic_policy_member_specific": 1,
            "semantic_policy_provenance_unverified": 0,
            "semantic_policy_identity_translation_required": 0,
            "setting_alignment_alignment_ready_settings": 24,
            "setting_alignment_expected_settings": 24,
            "same": 1,
            "a4_2_2_stage_pass": True,
        },
        "transport": {
            "direct_firewall": {
                "tls_verify": False,
                "ca_bundle_configured": False,
            }
        },
        "devices": [
            {
                "device": "FW-A",
                "serial": "SER123",
                "management_ip": "10.0.0.10",
                "model": "PA-TEST",
                "sw_version": "11.1.0",
                "ha_state": "active",
                "connected": "yes",
                "status": "success",
                "primary_evidence_status": "success",
                "alignment_evidence_status": "complete",
                "completed_at": "2026-08-23T20:19:23+03:00",
                "expected_configuration": {
                    "primary_template_stack": "Stack-A",
                    "policy_scope_count": 1,
                    "policy_lineage_complete": True,
                    "template_expected": {"unresolved_variable_setting_count": 0},
                },
                "configuration_alignment": {
                    "panorama_template_sync": "in sync",
                    "panorama_shared_policy_sync": "in sync",
                    "panorama_reports_out_of_sync": False,
                    "panorama_assignment": {
                        "assignment_status": "mapped",
                        "template_stacks": [{"name": "Stack-A", "templates": ["Template-A"]}],
                        "device_groups": [{"name": "DG-A", "vsys": ["vsys1"]}],
                    },
                },
                "setting_alignment": {
                    "status": "success",
                    "device_status": "LOCAL_OVERRIDE",
                    "summary": {
                        "expected_settings": 24,
                        "alignment_ready_settings": 24,
                        "expected_settings_observed_in_effective": 22,
                        "observed_percent": 91.667,
                        "coverage_percent": 100.0,
                        "value_comparison_coverage_percent": 100.0,
                        "classification_counts": {
                            "ALIGNED": 20,
                            "LOCAL_OVERRIDE": 1,
                            "MEMBER_SPECIFIC": 1,
                            "EXPECTED_ONLY": 2,
                            "LOCAL_ONLY": 3,
                        },
                        "category_counts": {
                            "system": {"ALIGNED": 10, "LOCAL_OVERRIDE": 1},
                            "ha": {"ALIGNED": 10, "MEMBER_SPECIFIC": 1},
                        },
                    },
                },
                "panorama_control": {"status": "success", "method": "PANORAMA_API", "sha256": "do-not-copy-control-hash"},
                "direct": {
                    "active": {"status": "success", "method": "DIRECT_ACTIVE", "change_state": "same", "sha256": "do-not-copy-active-hash"},
                    "merged": {"status": "success", "method": "DIRECT_MERGED", "change_state": "same", "sha256": "do-not-copy-merged-hash"},
                    "effective": effective_artifact,
                },
            }
        ],
    }


def test_configuration_ui_payload_includes_selected_current_values_but_excludes_hashes_and_secrets(tmp_path):
    payload = build_configuration_ui_payload(_config_result(tmp_path))
    encoded = json.dumps(payload)
    device = payload["devices"][0]
    sections = {section["id"]: section for section in device["current_configuration"]["sections"]}

    assert payload["available"] is True
    assert payload["fleet"]["primary_evidence_success"] == 1
    assert payload["fleet"]["local_override"] == 1
    assert device["serial"] == "SER123"
    assert device["ha_role"] == "ACTIVE"
    assert device["vsys_count"] == 1
    assert device["policy_scope"] == "DG-A"
    assert device["assignment"]["primary_template_stack"] == "Stack-A"
    assert device["alignment"]["findings"][0]["setting"].startswith("/config/")
    assert device["alignment"]["findings"][0]["expected_source_name"] == "CommonTemplate"

    dns = sections["dns"]["settings"]
    assert any(row["setting"] == "Primary DNS" and row["value"] == "10.0.0.53" and row["origin"] == "central" for row in dns)
    management = sections["management"]["settings"]
    assert any(row["value"] == "operator-mgmt" and row["origin"] == "local_override" for row in management)
    ha = sections["high_availability"]["settings"]
    assert any(row["value"] == "1.1.1.1" and row["origin"] == "member_specific" for row in ha)

    assert "SUPER_SECRET_DO_NOT_EMBED" not in encoded
    assert "do-not-copy-this-hash" not in encoded
    assert "do-not-copy-control-hash" not in encoded
    assert "value_sha256" not in encoded
    assert payload["structured_current_values_included"] is True
    assert payload["raw_configuration_blob_included"] is False
    assert payload["value_hashes_included"] is False


def test_configuration_ui_payload_without_config_is_explicitly_unavailable():
    payload = build_configuration_ui_payload(None)
    assert payload["available"] is False
    assert payload["devices"] == []
    assert payload["backup"]["status"] == "not_configured"
    assert payload["structured_current_values_included"] is False


def test_template_has_vendor_neutral_configuration_information_architecture_and_preserves_inventory_contract():
    assert "SecurityExpert" in TEMPLATE
    assert "F-Buddy" not in TEMPLATE
    for element_id in [
        "overviewNav", "inventoryNav", "configurationNav",
        "complianceNav",
        "overviewModule", "inventoryModule", "configurationModule",
        "complianceModule",
        "configDeviceList", "configOverviewTab", "configCurrentTab", "configAlignmentTab",
        "configPolicyTab", "configEvidenceTab", "configHistoryTab", "configBackupTab",
        "configCurrentPanel", "configPolicyPanel", "configHeaderFacts",
        "globalSearch", "deviceList", "interfaceTable", "routeTable",
    ]:
        assert f'id="{element_id}"' in TEMPLATE
    assert "__CONFIG_JSON_PLACEHOLDER__" in TEMPLATE
    assert "__COMPLIANCE_JSON_PLACEHOLDER__" in TEMPLATE
    assert "Search Palo Alto device" not in TEMPLATE


def test_frontend_separates_current_configuration_from_alignment_and_policy():
    for marker in [
        "function switchModule(",
        "function renderConfigDeviceList(",
        "function renderConfigCurrentPanel(",
        "function renderConfigAlignmentPanel(",
        "function renderConfigPolicyPanel(",
        "function renderConfigEvidencePanel(",
        "function renderConfigHistoryPanel(",
        "function renderConfigBackupPanel(",
        "Current actual state",
        "Policy &amp; Objects",
        "Expected member difference",
        "Provenance unverified",
        "EXPECTED_ONLY and LOCAL_ONLY are coverage states, not drift.",
    ]:
        assert marker in APP or marker in TEMPLATE
    assert "currentOriginLabel" in APP
    assert "Serial Number" in APP
    assert "HA / Role" in APP
    assert ".current-config-table" in CSS
    assert ".config-header-facts" in CSS
    assert ".status-pill.danger" in CSS
    assert ".status-pill.warning" in CSS
    for compliance_marker in [
        "compliance-traceability-grid",
        "compliance-evidence-fields",
        "compliance-planned-note",
        "Evidence checked",
        "Lifecycle",
        "Scope",
    ]:
        assert compliance_marker in APP or compliance_marker in CSS


def test_full_run_passes_current_configuration_result_into_same_html_export():
    assert "config_result=config_result" in CHECKPOINT_WF
    assert "build_configuration_ui_payload" in HTML_EXPORT
    assert '"__CONFIG_JSON_PLACEHOLDER__"' in HTML_EXPORT
    assert '"__COMPLIANCE_JSON_PLACEHOLDER__"' in HTML_EXPORT


def test_a4_3_3_basic_configuration_highlights_are_bounded_safe_and_operator_oriented(tmp_path):
    payload = build_configuration_ui_payload(_config_result(tmp_path))
    current = payload["devices"][0]["current_configuration"]
    highlights = current["highlights"]
    by_label = {item["label"]: item for item in highlights}

    assert payload["schema_version"] == "0.6.1B"
    assert payload["build"] == "phase-0.6.1B-check-point-configuration-ui-integration"
    assert current["schema_version"] == "0.7.2"   # 0.7.2 projection extension
    assert by_label["Hostname"]["value"] == "FW-A"
    assert by_label["Primary DNS"]["value"] == "10.0.0.53"
    assert by_label["Primary NTP Server"]["value"] == "10.0.0.123"
    assert by_label["HA Enabled"]["value"] == "yes"
    assert by_label["Management Access Entries"]["value"] == "1"
    assert len(highlights) <= 10

    encoded = json.dumps(highlights)
    assert "SUPER_SECRET_DO_NOT_EMBED" not in encoded
    assert "value_sha256" not in encoded


def test_a4_3_3_frontend_surfaces_basic_snapshot_freshness_and_vendor_scope_without_cp_collection_claim():
    for marker in [
        "function renderCurrentHighlights(",
        "Basic configuration",
        "Operator snapshot",
        "Config freshness",
        "Current source",
        "secret-bearing setting(s) were withheld",
        "Check Point Gaia",
        "Check Point SSH trust",
        ".current-config-highlights",
        ".config-highlight-card",
    ]:
        assert marker in APP or marker in CSS


def test_a4331_header_uses_local_panorama_assignment_and_config_ha_fallback(tmp_path):
    result = _config_result(tmp_path)
    row = result["devices"][0]
    # Local full-run rows carry assignment here, not under configuration_alignment.
    row["panorama_assignment"] = {
        "assignment_status": "mapped",
        "template_stacks": [{"name": "Stack-A", "templates": ["Template-A"]}],
        "device_groups": [{"name": "DG-LOCAL", "vsys": ["vsys1"]}],
    }
    row["configuration_alignment"].pop("panorama_assignment", None)
    row["ha_state"] = None

    payload = build_configuration_ui_payload(result)
    device = payload["devices"][0]
    assert device["policy_scope"] == "DG-LOCAL"
    assert device["ha_role"] == "HA Enabled"
    assert device["ha_role_source"] == "effective_configuration"


def test_a4331_runtime_ha_role_wins_over_static_config(tmp_path):
    result = _config_result(tmp_path)
    result["devices"][0]["ha_state"] = "active"
    payload = build_configuration_ui_payload(result)
    device = payload["devices"][0]
    assert device["ha_role"] == "ACTIVE"
    assert device["ha_role_source"] == "panorama_runtime"


def test_a4331_config_ha_fallback_recognizes_only_the_canonical_panos_boolean_text(tmp_path):
    """OP.0b S9: the config-state fallback recognizes only the literal
    ``yes``/``no`` PAN-OS XML API returns for this field (the same
    vocabulary `utils.failover.assessment._derive_pan_units` already treats
    as canonical for the same kind of field) -- not the wider, independently
    -invented `on`/`off`/`true`/`false`/`1`/`0` tolerance the header used to
    accept. No real PAN-OS response ever emits those, so tightening this
    never reclassifies a real device."""
    result = _config_result(tmp_path)
    result["devices"][0]["ha_state"] = None
    xml_path = tmp_path / "effective-snapshot" / "direct-effective-running.xml"
    xml_path.write_text(
        xml_path.read_text(encoding="utf-8").replace(
            '<enabled src="tpl">yes</enabled>', '<enabled src="tpl">true</enabled>'
        ),
        encoding="utf-8",
    )

    payload = build_configuration_ui_payload(result)
    device = payload["devices"][0]

    assert device["current_configuration"]["highlights"]
    by_label = {item["label"]: item for item in device["current_configuration"]["highlights"]}
    assert by_label["HA Enabled"]["value"] == "true"
    assert device["ha_role"] is None
    assert device["ha_role_source"] is None
