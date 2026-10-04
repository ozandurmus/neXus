from __future__ import annotations

from types import SimpleNamespace

from configuration import panorama_config_collector as collector
from utils.config_evidence import ConfigEvidenceStore
import pytest

pytestmark = pytest.mark.configuration


LOCAL_XML = b'''<config version="11.1"><devices><entry name="localhost.localdomain"><deviceconfig/><network><interface><ethernet/></interface><virtual-router/></network><vsys><entry name="vsys1"/></vsys></entry></devices></config>'''
EFFECTIVE_XML = b'''<config version="11.1"><devices><entry name="localhost.localdomain"><deviceconfig/><network><interface><ethernet><entry name="ethernet1/1"/></ethernet></interface><virtual-router><entry name="VR1"/></virtual-router></network><vsys><entry name="vsys1"><zone><entry name="ZONE1"/></zone></entry></vsys></entry></devices></config>'''
PUSHED_XML = b'''<config version="11.1"><devices><entry name="localhost.localdomain"><network><virtual-router><entry name="VR1"/></virtual-router></network></entry></devices></config>'''


def test_panorama_discovery_captures_sync_status(monkeypatch):
    xml = b'''<response status="success"><result><devices><entry name="S1"><serial>S1</serial><hostname>FW1</hostname><connected>yes</connected><ip-address>192.0.2.1</ip-address><shared-policy-status>In Sync</shared-policy-status><template-status>Out of Sync</template-status><ha-state>active</ha-state></entry></devices></result></response>'''

    class FakeResponse:
        content = xml
        def raise_for_status(self):
            return None

    monkeypatch.setattr(collector.requests, "post", lambda *args, **kwargs: FakeResponse())
    rows = collector.get_devices("https://pan", "KEY", verify=False, timeout=5)
    assert rows[0]["shared_policy_status"] == "In Sync"
    assert rows[0]["template_status"] == "Out of Sync"
    assert rows[0]["ha_state"] == "active"


def test_canonical_hash_ignores_insignificant_formatting():
    a = b'<config><devices><entry name="x"><network/></entry></devices></config>'
    b = b'<config>\n  <devices>\n    <entry name="x"><network /></entry>\n  </devices>\n</config>'
    assert collector._canonical_sha256(a) == collector._canonical_sha256(b)


def test_alignment_does_not_overclaim_override():
    alignment = collector._configuration_alignment(
        {"shared_policy_status": "In Sync", "template_status": "Out of Sync"},
        {"panorama_active_vs_direct_active": {"available": True, "exact_canonical_match": True}},
        {"pushed_template": {"status": "success"}},
    )
    assert alignment["panorama_shared_policy_sync"] == "in_sync"
    assert alignment["panorama_template_sync"] == "out_of_sync"
    assert alignment["panorama_reports_out_of_sync"] is True
    assert alignment["panorama_active_vs_direct_active"] == "aligned"
    assert alignment["pushed_template_evidence"] == "available"
    assert alignment["override_analysis"] == "not_classified"


def test_a3_staged_fleet_validation_uses_connected_scope_and_parallel_safe_store(tmp_path, monkeypatch):
    monkeypatch.setattr(collector, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(collector, "_get_support_key", lambda: b"fixed-test-key")
    monkeypatch.setattr(collector, "_tls_verify_setting", lambda: False)
    monkeypatch.setattr(collector, "_direct_tls_verify_setting", lambda: False)
    monkeypatch.setattr(collector, "get_api_key", lambda *args, **kwargs: "PANKEY")
    monkeypatch.setattr(collector, "get_firewall_api_key", lambda *args, **kwargs: "FWKEY")

    devices = []
    for i in range(1, 7):
        devices.append({
            "serial": f"SER{i}",
            "hostname": f"FW{i}",
            "connected": "yes",
            "management_ip": f"192.0.2.{i}",
            "model": "PA-TEST",
            "sw_version": "11.1",
            "shared_policy_status": "In Sync",
            "template_status": "Out of Sync" if i == 2 else "In Sync",
            "ha_state": "active",
        })
    devices.append({
        "serial": "SER7", "hostname": "FW7", "connected": "no",
        "management_ip": "192.0.2.7", "model": "PA-TEST", "sw_version": "11.1",
        "shared_policy_status": "In Sync", "template_status": "In Sync", "ha_state": None,
    })
    monkeypatch.setattr(collector, "get_devices", lambda *args, **kwargs: devices)
    monkeypatch.setattr(collector, "get_active_running_config", lambda *args, **kwargs: LOCAL_XML)

    def system_info(host, key, **kwargs):
        serial_num = int(host.rsplit(".", 1)[-1])
        return {"serial": f"SER{serial_num}", "hostname": "FW", "sw_version": "11.1", "model": "PA"}

    monkeypatch.setattr(collector, "get_direct_system_info", system_info)
    monkeypatch.setattr(collector, "get_direct_active_config", lambda *args, **kwargs: LOCAL_XML)

    def op_config(host, key, mode, **kwargs):
        if mode == "pushed-template":
            return PUSHED_XML
        return EFFECTIVE_XML

    monkeypatch.setattr(collector, "get_direct_operational_config", op_config)

    class TempStore(ConfigEvidenceStore):
        def __init__(self):
            super().__init__(tmp_path / "configs")

    monkeypatch.setattr(collector, "ConfigEvidenceStore", TempStore)
    cfg = SimpleNamespace(auth=SimpleNamespace(principal="user", secret="secret"), panorama_ip="192.0.2.250")
    result = collector.run_panorama_config_evidence(cfg, limit=5, max_workers=3)
    summary = result["summary"]

    assert summary["discovered"] == 7
    assert summary["connected_discovered"] == 6
    assert summary["disconnected_discovered"] == 1
    assert summary["selected"] == 5
    assert summary["workers"] == 3
    assert summary["direct_full_evidence_success"] == 5
    assert summary["direct_effective_success"] == 5
    assert summary["direct_merged_success"] == 5
    assert summary["direct_pushed_template_success"] == 5
    assert summary["panorama_template_out_of_sync"] == 1
    assert summary["panorama_any_out_of_sync"] == 1
    assert summary["stage_pass"] is True
    assert all(row["status"] == "success" for row in result["devices"])


def test_pushed_template_query_is_read_only_operational(monkeypatch):
    captured = {}

    class FakeResponse:
        content = b'<response status="success"><result><config><devices/></config></result></response>'
        def raise_for_status(self):
            return None

    def fake_post(url, **kwargs):
        captured.update({"url": url, **kwargs})
        return FakeResponse()

    monkeypatch.setattr(collector.requests, "post", fake_post)
    content = collector.get_direct_operational_config(
        "https://192.0.2.5", "KEY", "pushed-template", verify=False, timeout=5
    )
    assert captured["data"]["type"] == "op"
    assert "<pushed-template" in captured["data"]["cmd"]
    assert captured["headers"] == {"X-PAN-KEY": "KEY"}
    assert b"<config" in content
