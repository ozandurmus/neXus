import json
import zipfile
from types import SimpleNamespace

from configuration import panorama_config_collector as collector
from configuration.pan_expected_compiler import compile_panorama_expected, expected_for_serial
from configuration.pan_setting_alignment import align_expected_to_effective
from utils.config_evidence import ConfigEvidenceStore
import pytest

pytestmark = pytest.mark.configuration


PANORAMA_XML = b"""<config>
  <devices><entry name="localhost.localdomain">
    <template><entry name="BASE"><config><devices><entry name="localhost.localdomain">
      <deviceconfig><system>
        <dns-setting><servers><primary>10.0.0.1</primary></servers></dns-setting>
        <ntp-servers><primary-ntp-server><ntp-server-address>10.0.0.2</ntp-server-address></primary-ntp-server></ntp-servers>
        <domain>$DOMAIN</domain>
        <timezone>UTC</timezone>
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


def _firewall_xml(*, dns: str, ntp: str, include_timezone: bool = True, local_only: bool = False, root_name: str = "SERIAL-1") -> bytes:
    timezone = "<timezone>UTC</timezone>" if include_timezone else ""
    local = "<hostname>FW-LOCAL</hostname>" if local_only else ""
    return f"""<config><devices><entry name="{root_name}">
      <deviceconfig><system>
        <dns-setting><servers><primary>{dns}</primary></servers></dns-setting>
        <ntp-servers><primary-ntp-server><ntp-server-address>{ntp}</ntp-server-address></primary-ntp-server></ntp-servers>
        {timezone}{local}
      </system></deviceconfig>
    </entry></devices></config>""".encode()


def _class_counts(result):
    return result["summary"]["classification_counts"]


def test_a4_2_alignment_key_normalizes_root_device_identity_and_finds_aligned_scalars():
    compiled = compile_panorama_expected(PANORAMA_XML)
    expected = expected_for_serial(compiled, "SERIAL-1")
    result = align_expected_to_effective(
        serial="SERIAL-1",
        expected_compiler=compiled,
        expected_row=expected,
        effective_content=_firewall_xml(dns="10.0.0.1", ntp="10.0.0.2", root_name="different-root-name"),
        merged_content=_firewall_xml(dns="10.0.0.1", ntp="10.0.0.2", root_name="localhost.localdomain"),
        active_content=_firewall_xml(dns="10.0.0.1", ntp="10.0.0.2", root_name="localhost.localdomain"),
        panorama_sync={"panorama_shared_policy_sync": "in_sync", "panorama_template_sync": "in_sync"},
    )
    counts = _class_counts(result)
    assert counts["ALIGNED"] >= 3
    # Unresolved variable is not promoted to drift.
    assert counts["UNKNOWN"] == 1
    assert result["raw_values_included"] is False


def test_a4_2_proves_local_override_when_local_active_matches_effective_difference():
    compiled = compile_panorama_expected(PANORAMA_XML)
    expected = expected_for_serial(compiled, "SERIAL-1")
    effective = _firewall_xml(dns="10.0.0.9", ntp="10.0.0.2")
    result = align_expected_to_effective(
        serial="SERIAL-1",
        expected_compiler=compiled,
        expected_row=expected,
        effective_content=effective,
        merged_content=effective,
        active_content=effective,
        panorama_sync={"panorama_shared_policy_sync": "in_sync", "panorama_template_sync": "in_sync"},
    )
    assert _class_counts(result)["LOCAL_OVERRIDE"] >= 1
    assert result["device_status"] == "LOCAL_OVERRIDE"


def test_a4_2_marks_effective_drift_only_when_merged_confirms_and_local_does_not_explain():
    compiled = compile_panorama_expected(PANORAMA_XML)
    expected = expected_for_serial(compiled, "SERIAL-1")
    effective = _firewall_xml(dns="10.0.0.1", ntp="10.0.0.99")
    active = b"""<config><devices><entry name="localhost.localdomain"><deviceconfig><system>
      <dns-setting><servers><primary>10.0.0.1</primary></servers></dns-setting>
    </system></deviceconfig></entry></devices></config>"""
    result = align_expected_to_effective(
        serial="SERIAL-1",
        expected_compiler=compiled,
        expected_row=expected,
        effective_content=effective,
        merged_content=effective,
        active_content=active,
        panorama_sync={"panorama_shared_policy_sync": "in_sync", "panorama_template_sync": "in_sync"},
    )
    assert _class_counts(result)["EFFECTIVE_DRIFT"] >= 1
    assert result["device_status"] == "EFFECTIVE_DRIFT"


def test_a4_2_out_of_sync_takes_precedence_over_drift_claim():
    compiled = compile_panorama_expected(PANORAMA_XML)
    expected = expected_for_serial(compiled, "SERIAL-1")
    effective = _firewall_xml(dns="10.0.0.1", ntp="10.0.0.99")
    result = align_expected_to_effective(
        serial="SERIAL-1",
        expected_compiler=compiled,
        expected_row=expected,
        effective_content=effective,
        merged_content=effective,
        active_content=b"<config><devices><entry name='localhost.localdomain'><deviceconfig><system/></deviceconfig></entry></devices></config>",
        panorama_sync={"panorama_shared_policy_sync": "in_sync", "panorama_template_sync": "out_of_sync"},
    )
    counts = _class_counts(result)
    assert counts["PANORAMA_OUT_OF_SYNC"] >= 1
    assert counts.get("EFFECTIVE_DRIFT", 0) == 0


def test_a4_2_expected_only_and_local_only_are_observations_not_automatic_drift():
    compiled = compile_panorama_expected(PANORAMA_XML)
    expected = expected_for_serial(compiled, "SERIAL-1")
    effective = _firewall_xml(dns="10.0.0.1", ntp="10.0.0.2", include_timezone=False)
    active = _firewall_xml(dns="10.0.0.1", ntp="10.0.0.2", include_timezone=False, local_only=True)
    result = align_expected_to_effective(
        serial="SERIAL-1",
        expected_compiler=compiled,
        expected_row=expected,
        effective_content=effective,
        merged_content=effective,
        active_content=active,
        panorama_sync={"panorama_shared_policy_sync": "in_sync", "panorama_template_sync": "in_sync"},
    )
    counts = _class_counts(result)
    assert counts["EXPECTED_ONLY"] >= 1
    assert counts["LOCAL_ONLY"] >= 1
    assert counts.get("EFFECTIVE_DRIFT", 0) == 0


def test_a4_2_support_exposes_counts_not_setting_paths_or_value_hashes(tmp_path, monkeypatch):
    firewall_xml = _firewall_xml(dns="10.0.0.9", ntp="10.0.0.2", local_only=True)
    monkeypatch.setattr(collector, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(collector, "DERIVED_EXPECTED_DIR", tmp_path / "expected")
    monkeypatch.setattr(collector, "DERIVED_ALIGNMENT_DIR", tmp_path / "alignment")
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
    result = collector.run_panorama_config_evidence(
        cfg, limit=1, max_workers=1, probe_pushed_template=False
    )
    assert result["summary"]["setting_alignment_engine_gate"] is True
    assert result["summary"]["a4_2_stage_pass"] is True
    assert result["setting_alignment_manifest_path"]
    assert result["setting_alignment_report_path"]

    with zipfile.ZipFile(result["support_path"]) as zf:
        text = zf.read("summary.json").decode("utf-8")
        support = json.loads(text)
    assert support["setting_alignment"]["local_manifest_in_support_bundle"] is False
    assert support["devices"][0]["setting_alignment"]["detail_in_support_bundle"] is False
    assert "alignment_key" not in text
    assert "expected_value_sha256" not in text
    assert "10.0.0.9" not in text
    assert "FW-SECRET" not in text


def test_a4_2_template_setting_does_not_use_shared_policy_sync_as_template_sync():
    compiled = compile_panorama_expected(PANORAMA_XML)
    expected = expected_for_serial(compiled, "SERIAL-1")
    effective = _firewall_xml(dns="10.0.0.1", ntp="10.0.0.99")
    result = align_expected_to_effective(
        serial="SERIAL-1",
        expected_compiler=compiled,
        expected_row=expected,
        effective_content=effective,
        merged_content=effective,
        active_content=b"<config><devices><entry name='localhost.localdomain'><deviceconfig><system/></deviceconfig></entry></devices></config>",
        panorama_sync={"panorama_shared_policy_sync": "out_of_sync", "panorama_template_sync": "in_sync"},
    )
    counts = _class_counts(result)
    assert counts.get("PANORAMA_OUT_OF_SYNC", 0) == 0
    assert counts["EFFECTIVE_DRIFT"] >= 1
