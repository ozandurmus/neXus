from __future__ import annotations

import json
import zipfile
from pathlib import Path
from types import SimpleNamespace

from configuration import panorama_config_collector as collector
from configuration.pan_config_structure import analyze_pan_config_structure
from utils.config_evidence import ConfigEvidenceStore


REPRESENTATIVE_XML = b'''<?xml version="1.0"?>
<config version="11.1">
  <mgt-config><users><entry name="SECRET-ADMIN"/></users></mgt-config>
  <shared><address><entry name="SECRET-SHARED"><ip-netmask>192.0.2.10</ip-netmask></entry></address></shared>
  <devices>
    <entry name="SECRET-DEVICE">
      <deviceconfig><system><hostname>SECRET-HOST</hostname></system></deviceconfig>
      <network>
        <interface>
          <ethernet>
            <entry name="ethernet1/1">
              <layer3>
                <ip><entry name="198.51.100.10/24"/></ip>
                <units><entry name="ethernet1/1.100"/></units>
              </layer3>
            </entry>
          </ethernet>
          <loopback><units><entry name="loopback.1"/></units></loopback>
        </interface>
        <virtual-router><entry name="SECRET-VR"/></virtual-router>
      </network>
      <vsys>
        <entry name="SECRET-VSYS">
          <zone><entry name="SECRET-ZONE"/></zone>
          <rulebase><security><rules><entry name="SECRET-LOCAL-RULE"/></rules></security></rulebase>
        </entry>
      </vsys>
    </entry>
  </devices>
  <panorama>
    <vsys>
      <entry name="SECRET-PAN-VSYS">
        <pre-rulebase><security><rules><entry name="SECRET-PRE-RULE"/></rules></security></pre-rulebase>
      </entry>
    </vsys>
  </panorama>
</config>
'''


def test_pan_structure_reports_only_presence_counts_and_fixed_warnings():
    result = analyze_pan_config_structure(REPRESENTATIVE_XML)

    assert result["status"] == "pass"
    assert result["privacy_safe"] is True
    assert result["presence"] == {
        "devices": True,
        "device_entry": True,
        "deviceconfig": True,
        "network": True,
        "vsys": True,
        "shared": True,
        "panorama": True,
        "mgt_config": True,
    }
    assert result["counts"]["device_entries"] == 1
    assert result["counts"]["vsys_entries"] == 1
    assert result["counts"]["virtual_router_entries"] == 1
    assert result["counts"]["zone_entries"] == 1
    assert result["counts"]["ethernet_interface_entries"] == 1
    assert result["counts"]["ethernet_subinterface_entries"] == 1
    assert result["counts"]["loopback_interface_entries"] == 1
    assert result["counts"]["interface_definitions_total"] == 3
    assert result["counts"]["local_security_rule_entries"] == 1
    assert result["counts"]["panorama_pre_security_rule_entries"] == 1
    assert result["counts"]["security_rule_entries_total"] == 2
    assert result["warnings"] == []

    safe_text = json.dumps(result, sort_keys=True)
    for forbidden in (
        "SECRET-ADMIN",
        "SECRET-DEVICE",
        "SECRET-HOST",
        "SECRET-VR",
        "SECRET-VSYS",
        "SECRET-ZONE",
        "SECRET-LOCAL-RULE",
        "SECRET-PRE-RULE",
        "198.51.100.10",
    ):
        assert forbidden not in safe_text


def test_pan_structure_missing_sections_is_warning_not_false_failure():
    result = analyze_pan_config_structure(b"<config><devices/></config>")

    assert result["status"] == "warn"
    assert result["presence"]["devices"] is True
    assert result["presence"]["device_entry"] is False
    assert "device_entry_not_observed" in result["warnings"]
    assert "deviceconfig_section_not_observed" in result["warnings"]
    assert "network_section_not_observed" in result["warnings"]
    assert "vsys_section_not_observed" in result["warnings"]


def test_structural_validation_is_persisted_with_immutable_snapshot(tmp_path):
    store = ConfigEvidenceStore(tmp_path / "configs")
    structure = analyze_pan_config_structure(REPRESENTATIVE_XML)

    result = store.write_xml_snapshot(
        source="panorama",
        entity_id="SER1",
        artifact_type="panos_active_running_config",
        content=REPRESENTATIVE_XML,
        method="test",
        collector_version="0.6.0A2",
        additional_validation={"pan_structure": structure},
    )

    metadata = json.loads(result.metadata_path.read_text(encoding="utf-8"))
    assert metadata["validation"]["xml_valid"] is True
    assert metadata["validation"]["pan_structure"]["status"] == "pass"
    assert metadata["validation"]["pan_structure"]["counts"]["vsys_entries"] == 1


def test_shareable_support_exposes_safe_structure_but_no_raw_values(tmp_path, monkeypatch):
    monkeypatch.setattr(collector, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(collector, "_get_support_key", lambda: b"fixed-test-key")
    structure = analyze_pan_config_structure(REPRESENTATIVE_XML)

    payload = {
        "summary": {
            "run_id": "REAL-RUN-ID",
            "discovered": 1,
            "selected": 1,
            "eligible_connected": 1,
            "success": 1,
            "failed": 0,
            "skipped_disconnected": 0,
            "first": 1,
            "same": 0,
            "changed": 0,
            "total_bytes": len(REPRESENTATIVE_XML),
            "structural_pass": 1,
            "structural_warn": 0,
            "observed_vsys_entries": 1,
            "observed_virtual_router_entries": 1,
            "observed_zone_entries": 1,
            "observed_interface_definitions": 3,
        },
        "transport": {"api": "PAN-OS XML API", "tls_verify": False},
        "devices": [{
            "device": "REAL-FW-NAME",
            "serial": "REAL-SERIAL-123",
            "management_ip": "192.0.2.55",
            "connected": "yes",
            "status": "success",
            "duration_ms": 123,
            "size_bytes": len(REPRESENTATIVE_XML),
            "sha256": "abc123",
            "change_state": "first",
            "structural_validation": structure,
        }],
    }

    bundle = collector._write_shareable_support(payload, "REAL-RUN-ID")
    with zipfile.ZipFile(bundle) as zf:
        text = zf.read("summary.json").decode("utf-8")
        data = json.loads(text)

    assert data["summary"]["structural_pass"] == 1
    assert data["devices"][0]["structural_validation"]["counts"]["vsys_entries"] == 1
    assert "REAL-RUN-ID" not in text
    assert "REAL-FW-NAME" not in text
    assert "REAL-SERIAL-123" not in text
    assert "192.0.2.55" not in text
    assert "SECRET-" not in text
    assert "198.51.100.10" not in text
    assert "<config" not in text


def test_collector_summarizes_pass_and_warn_without_reclassifying_warn_as_failure(tmp_path, monkeypatch):
    monkeypatch.setattr(collector, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(collector, "_get_support_key", lambda: b"fixed-test-key")
    monkeypatch.setattr(collector, "_tls_verify_setting", lambda: False)
    monkeypatch.setattr(collector, "get_api_key", lambda *args, **kwargs: "KEY")
    monkeypatch.setattr(
        collector,
        "get_devices",
        lambda *args, **kwargs: [
            {"serial": "S1", "hostname": "FW1", "connected": "yes", "management_ip": "192.0.2.1", "model": "PA", "sw_version": "11"},
            {"serial": "S2", "hostname": "FW2", "connected": "yes", "management_ip": "192.0.2.2", "model": "PA", "sw_version": "11"},
        ],
    )

    def fake_config(host, key, serial, **kwargs):
        if serial == "S1":
            return REPRESENTATIVE_XML
        return b"<config><devices/><shared/></config>"

    monkeypatch.setattr(collector, "get_active_running_config", fake_config)

    class TempStore(ConfigEvidenceStore):
        def __init__(self):
            super().__init__(tmp_path / "configs")

    monkeypatch.setattr(collector, "ConfigEvidenceStore", TempStore)
    cfg = SimpleNamespace(auth=SimpleNamespace(principal="u", secret="p"), panorama_ip="192.0.2.10")
    result = collector.run_panorama_config_evidence(cfg, direct_compare=False)

    assert result["summary"]["success"] == 2
    assert result["summary"]["failed"] == 0
    assert result["summary"]["structural_pass"] == 1
    assert result["summary"]["structural_warn"] == 1
    assert result["summary"]["observed_vsys_entries"] == 1
    assert result["summary"]["observed_virtual_router_entries"] == 1
    assert result["summary"]["observed_zone_entries"] == 1
    assert result["summary"]["observed_interface_definitions"] == 3
