from __future__ import annotations

import json
import zipfile
from types import SimpleNamespace

from configuration import panorama_config_collector as collector
from utils.config_evidence import ConfigEvidenceStore


LOCAL_XML = b'''<?xml version="1.0"?>
<config version="11.1">
  <devices><entry name="localhost.localdomain">
    <deviceconfig><system><hostname>LOCAL</hostname></system></deviceconfig>
    <network><interface><ethernet/></interface><virtual-router/></network>
    <vsys><entry name="vsys1"><zone/><rulebase><security><rules/></security></rulebase></entry></vsys>
  </entry></devices>
</config>
'''

EFFECTIVE_XML = b'''<?xml version="1.0"?>
<config version="11.1">
  <devices><entry name="localhost.localdomain">
    <deviceconfig><system><hostname>EFFECTIVE</hostname></system></deviceconfig>
    <network>
      <interface><ethernet><entry name="ethernet1/1"><layer3><units><entry name="ethernet1/1.10"/></units></layer3></entry></ethernet></interface>
      <virtual-router><entry name="VR1"/></virtual-router>
    </network>
    <vsys><entry name="vsys1"><zone><entry name="ZONE1"/></zone><rulebase><security><rules><entry name="RULE1"/></rules></security></rulebase></entry></vsys>
  </entry></devices>
</config>
'''


class FakeResponse:
    def __init__(self, content: bytes):
        self.content = content

    def raise_for_status(self):
        return None


def test_direct_firewall_keygen_uses_post_body_not_query(monkeypatch):
    calls = []

    def fake_post(url, **kwargs):
        calls.append((url, kwargs))
        return FakeResponse(b'<response status="success"><result><key>DIRECTKEY</key></result></response>')

    monkeypatch.setattr(collector.requests, "post", fake_post)
    cfg = SimpleNamespace(auth=SimpleNamespace(principal="alice", secret="pw"))
    key = collector.get_firewall_api_key(cfg, "https://192.0.2.5", verify=False, timeout=10)

    assert key == "DIRECTKEY"
    url, kwargs = calls[0]
    assert url == "https://192.0.2.5/api/"
    assert kwargs["data"]["type"] == "keygen"
    assert kwargs["data"]["user"] == "alice"
    assert kwargs["data"]["password"] == "pw"
    assert "?" not in url


def test_direct_operational_config_uses_read_only_effective_running_op(monkeypatch):
    captured = {}

    def fake_post(url, **kwargs):
        captured.update({"url": url, **kwargs})
        return FakeResponse(b'<response status="success"><result>' + EFFECTIVE_XML.split(b'\n', 1)[1] + b'</result></response>')

    monkeypatch.setattr(collector.requests, "post", fake_post)
    content = collector.get_direct_operational_config(
        "https://192.0.2.5", "KEY", "effective-running", verify=False, timeout=10
    )

    assert captured["data"]["type"] == "op"
    assert "<effective-running" in captured["data"]["cmd"]
    assert captured["headers"] == {"X-PAN-KEY": "KEY"}
    assert b"<config" in content


def test_a22_direct_compare_verifies_identity_and_finds_richer_effective_config(tmp_path, monkeypatch):
    monkeypatch.setattr(collector, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(collector, "_get_support_key", lambda: b"fixed-test-key")
    monkeypatch.setattr(collector, "_tls_verify_setting", lambda: False)
    monkeypatch.setattr(collector, "_direct_tls_verify_setting", lambda: False)
    monkeypatch.setattr(collector, "get_api_key", lambda *args, **kwargs: "PANKEY")
    monkeypatch.setattr(collector, "get_firewall_api_key", lambda *args, **kwargs: "FWKEY")
    monkeypatch.setattr(
        collector,
        "get_devices",
        lambda *args, **kwargs: [{
            "serial": "SER1",
            "hostname": "REAL-FW-NAME",
            "connected": "yes",
            "management_ip": "192.0.2.55",
            "model": "PA-TEST",
            "sw_version": "11.1",
        }],
    )
    monkeypatch.setattr(collector, "get_active_running_config", lambda *args, **kwargs: LOCAL_XML)
    monkeypatch.setattr(
        collector,
        "get_direct_system_info",
        lambda *args, **kwargs: {"serial": "SER1", "hostname": "FW", "sw_version": "11.1", "model": "PA-TEST"},
    )
    monkeypatch.setattr(collector, "get_direct_active_config", lambda *args, **kwargs: LOCAL_XML)

    def fake_op(host, key, mode, **kwargs):
        assert mode in {"effective-running", "merged"}
        return EFFECTIVE_XML

    monkeypatch.setattr(collector, "get_direct_operational_config", fake_op)

    class TempStore(ConfigEvidenceStore):
        def __init__(self):
            super().__init__(tmp_path / "configs")

    monkeypatch.setattr(collector, "ConfigEvidenceStore", TempStore)
    cfg = SimpleNamespace(auth=SimpleNamespace(principal="u", secret="p"), panorama_ip="192.0.2.10")
    result = collector.run_panorama_config_evidence(cfg, limit=1)

    summary = result["summary"]
    assert summary["panorama_control_success"] == 1
    assert summary["direct_api_auth_success"] == 1
    assert summary["direct_identity_verified"] == 1
    assert summary["direct_active_success"] == 1
    assert summary["direct_effective_success"] == 1
    assert summary["direct_merged_success"] == 1
    assert summary["panorama_direct_active_exact_match"] == 1
    assert summary["effective_richer_than_direct_active"] == 1

    row = result["devices"][0]
    assert row["direct"]["identity_verified"] is True
    effective_counts = row["direct"]["effective"]["structural_validation"]["counts"]
    assert effective_counts["virtual_router_entries"] == 1
    assert effective_counts["zone_entries"] == 1
    assert effective_counts["interface_definitions_total"] >= 2
    assert effective_counts["local_security_rule_entries"] == 1

    with zipfile.ZipFile(result["support_path"]) as zf:
        text = zf.read("summary.json").decode("utf-8")
        safe = json.loads(text)
    assert safe["summary"]["direct_effective_success"] == 1
    assert safe["devices"][0]["direct"]["identity_verified"] is True
    assert "REAL-FW-NAME" not in text
    assert "SER1" not in text
    assert "192.0.2.55" not in text
    assert "RULE1" not in text
    assert "ZONE1" not in text
    assert "<config" not in text


def test_direct_identity_mismatch_stops_config_collection(tmp_path, monkeypatch):
    monkeypatch.setattr(collector, "get_firewall_api_key", lambda *args, **kwargs: "FWKEY")
    monkeypatch.setattr(
        collector,
        "get_direct_system_info",
        lambda *args, **kwargs: {"serial": "OTHER-SERIAL", "hostname": "wrong", "sw_version": "11", "model": "PA"},
    )

    called = []
    monkeypatch.setattr(collector, "get_direct_active_config", lambda *args, **kwargs: called.append("active") or LOCAL_XML)
    monkeypatch.setattr(collector, "get_direct_operational_config", lambda *args, **kwargs: called.append("op") or EFFECTIVE_XML)

    result = collector._collect_direct_compare(
        SimpleNamespace(auth=SimpleNamespace(principal="u", secret="p")),
        {
            "serial": "SER1",
            "hostname": "FW1",
            "management_ip": "192.0.2.55",
            "model": "PA",
            "sw_version": "11",
        },
        store=ConfigEvidenceStore(tmp_path / "configs"),
        run_id="RUN1",
        verify=False,
        timeout=5,
    )

    assert result["status"] == "identity_mismatch"
    assert result["identity_mismatch"] is True
    assert result["identity_verified"] is False
    assert called == []


def test_config_store_history_is_scoped_per_artifact_type(tmp_path):
    store = ConfigEvidenceStore(tmp_path / "configs")
    a1 = store.write_xml_snapshot(
        source="panos-direct",
        entity_id="SER1",
        artifact_type="active",
        artifact_name="active.xml",
        content=LOCAL_XML,
        method="test",
    )
    b1 = store.write_xml_snapshot(
        source="panos-direct",
        entity_id="SER1",
        artifact_type="effective",
        artifact_name="effective.xml",
        content=EFFECTIVE_XML,
        method="test",
    )
    a2 = store.write_xml_snapshot(
        source="panos-direct",
        entity_id="SER1",
        artifact_type="active",
        artifact_name="active.xml",
        content=LOCAL_XML,
        method="test",
    )

    assert a1.change_state == "first"
    assert b1.change_state == "first"
    assert a2.change_state == "same"
    assert a2.previous_sha256 == a1.sha256
    assert a2.previous_sha256 != b1.sha256
