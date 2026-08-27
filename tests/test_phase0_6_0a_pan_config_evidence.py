from __future__ import annotations

import json
import zipfile
from pathlib import Path
from types import SimpleNamespace

from configuration import panorama_config_collector as collector
from utils.config_evidence import ConfigEvidenceStore, sha256_bytes


class FakeResponse:
    def __init__(self, content: bytes):
        self.content = content

    def raise_for_status(self):
        return None


def test_pan_config_keygen_uses_post_body_not_url_query(monkeypatch):
    calls = []

    def fake_post(url, **kwargs):
        calls.append((url, kwargs))
        return FakeResponse(b'<response status="success"><result><key>SECRETKEY</key></result></response>')

    monkeypatch.setattr(collector.requests, "post", fake_post)
    cfg = SimpleNamespace(auth=SimpleNamespace(principal="alice", secret="pw"))
    key = collector.get_api_key(cfg, "https://pan.example", verify=False, timeout=10)

    assert key == "SECRETKEY"
    url, kwargs = calls[0]
    assert url == "https://pan.example/api/"
    assert kwargs["data"]["type"] == "keygen"
    assert kwargs["data"]["user"] == "alice"
    assert kwargs["data"]["password"] == "pw"
    assert "?" not in url


def test_pan_active_config_uses_header_target_serial_and_active_show(monkeypatch):
    captured = {}

    def fake_post(url, **kwargs):
        captured.update({"url": url, **kwargs})
        return FakeResponse(
            b'<response status="success"><result><config version="11.1"><devices/></config></result></response>'
        )

    monkeypatch.setattr(collector.requests, "post", fake_post)
    content = collector.get_active_running_config(
        "https://pan.example",
        "APIKEY",
        "SER123",
        verify=False,
        timeout=20,
    )

    assert captured["headers"] == {"X-PAN-KEY": "APIKEY"}
    assert captured["data"]["type"] == "config"
    assert captured["data"]["action"] == "show"
    assert captured["data"]["xpath"] == "/config"
    assert captured["data"]["target"] == "SER123"
    assert "key" not in captured["data"]
    assert b"<config" in content
    assert b"<devices" in content


def test_config_evidence_store_is_immutable_and_tracks_change_state(tmp_path):
    store = ConfigEvidenceStore(tmp_path / "configs")
    first_xml = b'<?xml version="1.0"?><config><devices><entry name="a"/></devices></config>'
    changed_xml = b'<?xml version="1.0"?><config><devices><entry name="b"/></devices></config>'

    first = store.write_xml_snapshot(
        source="panorama",
        entity_id="SER1",
        artifact_type="panos_active_running_config",
        content=first_xml,
        method="test",
    )
    second = store.write_xml_snapshot(
        source="panorama",
        entity_id="SER1",
        artifact_type="panos_active_running_config",
        content=first_xml,
        method="test",
    )
    third = store.write_xml_snapshot(
        source="panorama",
        entity_id="SER1",
        artifact_type="panos_active_running_config",
        content=changed_xml,
        method="test",
    )

    assert first.directory != second.directory != third.directory
    assert first.change_state == "first"
    assert second.change_state == "same"
    assert third.change_state == "changed"
    assert first.artifact_path.read_bytes() == first_xml
    assert second.artifact_path.read_bytes() == first_xml
    assert third.artifact_path.read_bytes() == changed_xml
    assert first.sha256 == sha256_bytes(first_xml)
    assert second.previous_sha256 == first.sha256
    assert third.previous_sha256 == second.sha256

    metadata = json.loads(third.metadata_path.read_text(encoding="utf-8"))
    assert metadata["status"] == "success"
    assert metadata["validation"]["xml_valid"] is True
    assert metadata["artifact_type"] == "panos_active_running_config"


def test_shareable_pan_config_support_contains_no_raw_config_or_real_identifiers(tmp_path, monkeypatch):
    monkeypatch.setattr(collector, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(collector, "_get_support_key", lambda: b"fixed-test-key")

    payload = {
        "summary": {
            "discovered": 1,
            "eligible_connected": 1,
            "success": 1,
            "failed": 0,
            "skipped_disconnected": 0,
            "first": 1,
            "same": 0,
            "changed": 0,
        },
        "transport": {"api": "PAN-OS XML API", "tls_verify": False},
        "devices": [{
            "device": "REAL-FW-NAME",
            "serial": "REAL-SERIAL-123",
            "management_ip": "192.0.2.55",
            "connected": "yes",
            "status": "success",
            "duration_ms": 123,
            "size_bytes": 456,
            "sha256": "abc123",
            "change_state": "first",
        }],
    }

    bundle = collector._write_shareable_support(payload, "run-real-id")
    with zipfile.ZipFile(bundle) as zf:
        names = zf.namelist()
        assert names == ["summary.json", "support.log"]
        text = zf.read("summary.json").decode("utf-8")

    assert "REAL-FW-NAME" not in text
    assert "REAL-SERIAL-123" not in text
    assert "192.0.2.55" not in text
    assert "<config" not in text
    assert "raw_configuration_in_bundle" in text


def test_pan_config_collector_skips_disconnected_and_writes_only_connected(tmp_path, monkeypatch):
    monkeypatch.setattr(collector, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(collector, "_get_support_key", lambda: b"fixed-test-key")
    monkeypatch.setattr(collector, "_tls_verify_setting", lambda: False)
    monkeypatch.setattr(collector, "get_api_key", lambda *args, **kwargs: "KEY")
    monkeypatch.setattr(
        collector,
        "get_devices",
        lambda *args, **kwargs: [
            {"serial": "S1", "hostname": "FW1", "connected": "yes", "management_ip": "192.0.2.1", "model": "PA", "sw_version": "11"},
            {"serial": "S2", "hostname": "FW2", "connected": "no", "management_ip": "192.0.2.2", "model": "PA", "sw_version": "11"},
        ],
    )
    calls = []
    monkeypatch.setattr(
        collector,
        "get_active_running_config",
        lambda host, key, serial, **kwargs: calls.append(serial) or b'<config><devices><entry name="localhost"/></devices></config>',
    )

    class TempStore(ConfigEvidenceStore):
        def __init__(self):
            super().__init__(tmp_path / "configs")

    monkeypatch.setattr(collector, "ConfigEvidenceStore", TempStore)
    cfg = SimpleNamespace(auth=SimpleNamespace(principal="u", secret="p"), panorama_ip="192.0.2.10")
    result = collector.run_panorama_config_evidence(cfg, direct_compare=False)

    assert calls == ["S1"]
    assert result["summary"]["success"] == 1
    assert result["summary"]["skipped_disconnected"] == 1
    assert result["summary"]["failed"] == 0
    snapshots = list((tmp_path / "configs" / "panorama" / "S1").glob("*/metadata.json"))
    assert len(snapshots) == 1
    assert not list((tmp_path / "configs" / "panorama" / "S1").glob("*/running-config.xml"))
    assert list((tmp_path / "configs" / "panorama" / "S1").glob("*/running-config.xml.ref.json"))
    cas_objects = [p for p in (tmp_path / "artifacts" / "config" / "sha256").rglob("*") if p.is_file()]
    assert len(cas_objects) == 1
    assert not (tmp_path / "configs" / "panorama" / "S2").exists()
