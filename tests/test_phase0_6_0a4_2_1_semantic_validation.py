from __future__ import annotations

import json
import zipfile
from pathlib import Path
from types import SimpleNamespace

import configuration.panorama_config_collector as collector
from configuration.pan_semantic_validation import build_semantic_validation
from utils.config_evidence import ConfigEvidenceStore
import pytest

pytestmark = pytest.mark.configuration


PANORAMA_XML = b"""<config>
  <devices><entry name="localhost.localdomain">
    <template><entry name="BASE"><config><devices><entry name="localhost.localdomain">
      <deviceconfig><system>
        <dns-setting><servers><primary>10.0.0.1</primary></servers></dns-setting>
        <ntp-servers><primary-ntp-server><ntp-server-address>10.0.0.2</ntp-server-address></primary-ntp-server></ntp-servers>
        <password>super-secret</password>
      </system></deviceconfig>
    </entry></devices></config></entry></template>
    <template-stack><entry name="STACK-A">
      <templates><member>BASE</member></templates>
      <devices><entry name="SERIAL-1"/></devices>
    </entry></template-stack>
    <device-group><entry name="DG-A"><devices><entry name="SERIAL-1"><vsys><entry name="vsys1"/></vsys></entry></devices></entry></device-group>
  </entry></devices>
  <shared/>
</config>"""


def _direct_xml(dns: str = "10.0.0.9") -> bytes:
    return f"""<config><devices><entry name="SERIAL-1"><deviceconfig><system>
      <dns-setting><servers><primary>{dns}</primary></servers></dns-setting>
      <ntp-servers><primary-ntp-server><ntp-server-address>10.0.0.2</ntp-server-address></primary-ntp-server></ntp-servers>
      <password>different-secret</password>
    </system></deviceconfig></entry></devices></config>""".encode()


def _hash(text: str) -> str:
    import hashlib
    return hashlib.sha256(text.encode()).hexdigest()


def test_a4_2_1_builds_local_manual_override_samples_without_reclassifying():
    key = "/config/devices/entry[@name='__DEVICE__']/deviceconfig/system/dns-setting/servers/primary"
    row = {
        "device": "FW-1",
        "serial": "SERIAL-1",
        "management_ip": "192.0.2.10",
        "direct": {},
        "_setting_alignment_detail": {
            "results": [{
                "alignment_key": key,
                "path_sha256": _hash(key),
                "category": "dns",
                "classification": "LOCAL_OVERRIDE",
                "expected_source_kind": "template",
                "expected_source_name": "BASE",
                "expected_source_priority": 1,
                "expected_value_sha256": _hash("10.0.0.1"),
                "active_value_sha256": _hash("10.0.0.9"),
                "effective_value_sha256": _hash("10.0.0.9"),
                "merged_value_sha256": _hash("10.0.0.9"),
            }]
        },
    }
    direct = _direct_xml()
    result = build_semantic_validation(
        rows=[row],
        panorama_content=PANORAMA_XML,
        artifact_loader=lambda _row, _kind: direct,
    )
    assert result["status"] == "success"
    assert result["summary"]["manual_confirmation_status"] == "pending"
    assert result["manifest"]["reclassification_performed"] is False
    samples = result["operator_report"]["samples"]
    sample = next(item for item in samples if item["classification"] == "LOCAL_OVERRIDE")
    assert sample["expected_value"] == "10.0.0.1"
    assert sample["local_active_value"] == "10.0.0.9"
    assert sample["effective_value"] == "10.0.0.9"
    assert "expected_value" not in result["manifest"]["manual_samples"][0]


def test_a4_2_1_redacts_sensitive_sample_values_even_in_local_operator_report():
    key = "/config/devices/entry[@name='__DEVICE__']/deviceconfig/system/password"
    row = {
        "device": "FW-1", "serial": "SERIAL-1", "management_ip": "192.0.2.10", "direct": {},
        "_setting_alignment_detail": {"results": [{
            "alignment_key": key,
            "path_sha256": _hash(key),
            "category": "system",
            "classification": "LOCAL_OVERRIDE",
            "expected_source_kind": "template",
            "expected_source_name": "BASE",
            "expected_source_priority": 1,
            "expected_value_sha256": _hash("super-secret"),
            "active_value_sha256": _hash("different-secret"),
            "effective_value_sha256": _hash("different-secret"),
            "merged_value_sha256": _hash("different-secret"),
        }]},
    }
    result = build_semantic_validation(
        rows=[row], panorama_content=PANORAMA_XML, artifact_loader=lambda _row, _kind: _direct_xml()
    )
    sample = next(item for item in result["operator_report"]["samples"] if item["classification"] == "LOCAL_OVERRIDE")
    assert sample["expected_value"] == "[SENSITIVE:REDACTED]"
    assert sample["local_active_value"] == "[SENSITIVE:REDACTED]"


def test_a4_2_1_marks_only_conservative_schema_equivalent_candidates():
    expected_key = "/config/devices/entry[@name='__DEVICE__']/deviceconfig/system/ssh/timeout"
    local_key = "/config/devices/entry[@name='__DEVICE__']/deviceconfig/system/services/ssh/timeout"
    value_hash = _hash("30")
    row = {
        "device": "FW-1", "serial": "SERIAL-1", "management_ip": "192.0.2.10", "direct": {},
        "_setting_alignment_detail": {"results": [
            {
                "alignment_key": expected_key, "path_sha256": _hash(expected_key), "category": "system",
                "classification": "EXPECTED_ONLY", "expected_value_sha256": value_hash,
                "expected_value_kind": "scalar", "expected_source_kind": "template", "expected_source_name": "BASE",
            },
            {
                "alignment_key": local_key, "path_sha256": _hash(local_key), "category": "system",
                "classification": "LOCAL_ONLY", "active_value_sha256": value_hash,
            },
        ]},
    }
    result = build_semantic_validation(
        rows=[row], panorama_content=None, artifact_loader=lambda _row, _kind: None
    )
    assert result["summary"]["possible_schema_equivalent_candidates"] == 1
    candidate = result["manifest"]["possible_schema_equivalents"][0]
    assert candidate["classification"] == "POSSIBLE_SCHEMA_EQUIVALENT"
    assert candidate["promoted_to_aligned"] is False


def test_a4_2_1_support_contains_counts_not_manual_paths_or_values(tmp_path, monkeypatch):
    firewall_xml = _direct_xml()
    monkeypatch.setattr(collector, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(collector, "DERIVED_EXPECTED_DIR", tmp_path / "expected")
    monkeypatch.setattr(collector, "DERIVED_ALIGNMENT_DIR", tmp_path / "alignment")
    monkeypatch.setattr(collector, "DERIVED_SEMANTIC_DIR", tmp_path / "semantic")
    monkeypatch.setattr(collector, "_get_support_key", lambda: b"fixed-test-key")
    monkeypatch.setattr(collector, "_tls_verify_setting", lambda: False)
    monkeypatch.setattr(collector, "_direct_tls_verify_setting", lambda: False)
    monkeypatch.setattr(collector, "get_api_key", lambda *a, **k: "PANKEY")
    monkeypatch.setattr(collector, "get_firewall_api_key", lambda *a, **k: "FWKEY")
    monkeypatch.setattr(collector, "get_panorama_management_config", lambda *a, **k: PANORAMA_XML)
    monkeypatch.setattr(collector, "get_active_running_config", lambda *a, **k: firewall_xml)
    monkeypatch.setattr(collector, "get_direct_active_config", lambda *a, **k: firewall_xml)
    monkeypatch.setattr(collector, "get_direct_operational_config", lambda *a, **k: firewall_xml)
    monkeypatch.setattr(
        collector,
        "get_devices",
        lambda *a, **k: [{
            "serial": "SERIAL-1", "hostname": "FW-SECRET", "connected": "yes",
            "management_ip": "192.0.2.10", "model": "PA", "sw_version": "11.1",
            "shared_policy_status": "In Sync", "template_status": "In Sync", "ha_state": "active",
        }],
    )
    monkeypatch.setattr(
        collector,
        "get_direct_system_info",
        lambda *a, **k: {"serial": "SERIAL-1", "hostname": "FW-SECRET", "model": "PA", "sw_version": "11.1"},
    )

    class TempStore(ConfigEvidenceStore):
        def __init__(self):
            super().__init__(tmp_path / "configs")

    monkeypatch.setattr(collector, "ConfigEvidenceStore", TempStore)
    cfg = SimpleNamespace(auth=SimpleNamespace(principal="user", secret="secret"), panorama_ip="192.0.2.250")
    result = collector.run_panorama_config_evidence(cfg, limit=1, max_workers=1, probe_pushed_template=False)
    assert result["summary"]["semantic_validation_engine_gate"] is True
    assert result["summary"]["a4_2_1_engine_pass"] is True
    assert result["summary"]["a4_2_1_stage_pass"] is None
    assert Path(result["semantic_validation_report_path"]).exists()
    assert Path(result["semantic_validation_samples_csv_path"]).exists()

    with zipfile.ZipFile(result["support_path"]) as zf:
        text = zf.read("summary.json").decode("utf-8")
        support = json.loads(text)
    semantic = support["semantic_validation"]
    assert semantic["operator_report_in_support_bundle"] is False
    assert semantic["sample_setting_paths_in_support_bundle"] is False
    assert semantic["sample_values_in_support_bundle"] is False
    assert "10.0.0.9" not in text
    assert "super-secret" not in text
    assert "FW-SECRET" not in text
