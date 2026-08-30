from __future__ import annotations

import json
import zipfile
from types import SimpleNamespace

from configuration import panorama_config_collector as collector
from configuration.pan_config_structure import analyze_pan_config_structure
from utils.config_evidence import ConfigEvidenceStore
import pytest

pytestmark = pytest.mark.configuration


XML = b'''<?xml version="1.0"?>
<config version="11.1">
  <shared><address><entry name="SECRET-ADDRESS"><ip-netmask>192.0.2.10</ip-netmask></entry></address></shared>
  <devices>
    <entry name="SECRET-DEVICE">
      <deviceconfig><system><hostname>SECRET-HOST</hostname></system></deviceconfig>
      <network>
        <interface><ethernet><entry name="ethernet1/1"><layer3><units><entry name="ethernet1/1.10"/></units></layer3></entry></ethernet></interface>
        <virtual-router><entry name="SECRET-VR"/></virtual-router>
      </network>
      <vsys><entry name="SECRET-VSYS"><zone><entry name="SECRET-ZONE"/></zone></entry></vsys>
    </entry>
  </devices>
</config>
'''


def test_schema_inspector_emits_paths_only_without_values_or_attributes():
    result = analyze_pan_config_structure(XML)

    assert result["schema_status"] == "pass"
    assert result["evidence_status"] == "unknown"
    assert result["evidence_reason"] == "schema_mapping_in_progress"
    inspection = result["schema_inspection"]
    assert inspection["value_content_included"] is False
    assert inspection["attribute_content_included"] is False
    paths = {item["path"] for item in inspection["paths"]}
    assert "/config/devices/entry/network/interface/ethernet/entry/layer3/units/entry" in paths
    assert "/config/devices/entry/network/virtual-router/entry" in paths
    assert "/config/devices/entry/vsys/entry/zone/entry" in paths

    safe = json.dumps(result, sort_keys=True)
    for forbidden in (
        "SECRET-ADDRESS",
        "SECRET-DEVICE",
        "SECRET-HOST",
        "SECRET-VR",
        "SECRET-VSYS",
        "SECRET-ZONE",
        "192.0.2.10",
        "ethernet1/1",
    ):
        assert forbidden not in safe


def test_non_standard_element_name_is_hashed_in_schema_output():
    content = b"<config><devices><entry><SECRET_DYNAMIC_TAG>hidden</SECRET_DYNAMIC_TAG></entry></devices></config>"
    result = analyze_pan_config_structure(content)
    safe = json.dumps(result, sort_keys=True)
    assert "SECRET_DYNAMIC_TAG" not in safe
    assert "hidden" not in safe
    assert "tag_" in safe


def test_collector_reports_read_only_retrieval_semantics(tmp_path, monkeypatch):
    monkeypatch.setattr(collector, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(collector, "_get_support_key", lambda: b"fixed-test-key")
    monkeypatch.setattr(collector, "_tls_verify_setting", lambda: False)
    monkeypatch.setattr(collector, "get_api_key", lambda *args, **kwargs: "KEY")
    monkeypatch.setattr(
        collector,
        "get_devices",
        lambda *args, **kwargs: [{
            "serial": "S1",
            "hostname": "FW1",
            "connected": "yes",
            "management_ip": "192.0.2.1",
            "model": "PA",
            "sw_version": "11.1",
        }],
    )
    monkeypatch.setattr(collector, "get_active_running_config", lambda *args, **kwargs: XML)

    class TempStore(ConfigEvidenceStore):
        def __init__(self):
            super().__init__(tmp_path / "configs")

    monkeypatch.setattr(collector, "ConfigEvidenceStore", TempStore)
    cfg = SimpleNamespace(auth=SimpleNamespace(principal="u", secret="p"), panorama_ip="192.0.2.10")
    result = collector.run_panorama_config_evidence(cfg, limit=1, direct_compare=False)

    assert result["artifact_type"] == "panos_active_config_via_panorama"
    assert result["method"] == "panorama_xml_api_config_show"
    assert result["transport"]["request_type"] == "config"
    assert result["transport"]["action"] == "show"
    assert result["transport"]["xpath"] == "/config"
    assert result["transport"]["remote_artifact_created"] is False
    assert result["transport"]["remote_configuration_changed"] is False
    assert result["transport"]["local_artifact_created"] is True
    assert result["summary"]["schema_pass"] == 1
    assert result["summary"]["evidence_unknown"] == 1

    with zipfile.ZipFile(result["support_path"]) as zf:
        support = json.loads(zf.read("summary.json").decode("utf-8"))
    assert support["transport"]["remote_artifact_created"] is False
    assert support["devices"][0]["structural_validation"]["schema_inspection"]["distinct_path_count"] > 0
