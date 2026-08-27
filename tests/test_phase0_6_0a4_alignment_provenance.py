import json
import zipfile
from pathlib import Path
from types import SimpleNamespace

from configuration import panorama_config_collector as collector
from configuration.pan_config_alignment import (
    alignment_profile,
    analyze_panorama_intent,
    assignment_for_serial,
)
from utils.config_evidence import ConfigEvidenceStore


PANORAMA_XML = b"""<config>
  <devices><entry name="localhost.localdomain">
    <template><entry name="BASE"/><entry name="DC"/></template>
    <template-stack><entry name="STACK-A">
      <templates><member>DC</member><member>BASE</member></templates>
      <devices><entry name="SERIAL-1"/></devices>
      <config><devices/></config>
    </entry></template-stack>
    <device-group><entry name="DG-EDGE">
      <devices><entry name="SERIAL-1"><vsys><entry name="vsys1"/></vsys></entry></devices>
    </entry></device-group>
  </entry></devices>
  <shared/>
</config>"""

FIREWALL_XML = b"""<config><devices><entry name="localhost.localdomain">
  <deviceconfig><system><hostname>fw</hostname></system></deviceconfig>
  <network><virtual-router><entry name="default"/></virtual-router></network>
  <vsys><entry name="vsys1"><zone><entry name="trust"/></zone></entry></vsys>
</entry></devices><shared/></config>"""


def test_panorama_intent_maps_template_stack_and_device_group():
    analysis = analyze_panorama_intent(PANORAMA_XML)
    assignment = assignment_for_serial(analysis, "SERIAL-1")
    assert analysis["summary"]["templates"] == 2
    assert analysis["summary"]["template_stacks"] == 1
    assert analysis["summary"]["device_groups"] == 1
    assert assignment["assignment_status"] == "mapped"
    assert assignment["template_stacks"][0]["name"] == "STACK-A"
    assert assignment["template_stacks"][0]["templates"] == ["DC", "BASE"]
    assert assignment["device_groups"][0]["name"] == "DG-EDGE"
    assert assignment["device_groups"][0]["vsys"] == ["vsys1"]
    assert analysis["compiled_expected_config"] is False


def test_alignment_profile_does_not_promote_difference_to_proven_override():
    profile = alignment_profile(
        panorama_sync={
            "panorama_shared_policy_sync": "in_sync",
            "panorama_template_sync": "in_sync",
            "panorama_reports_out_of_sync": False,
        },
        assignment={"assignment_status": "mapped"},
        direct={
            "active": {"status": "success"},
            "merged": {"status": "success"},
            "effective": {"status": "success"},
        },
        comparison={
            "direct_active_vs_merged": {"available": True, "exact_canonical_match": False},
            "direct_merged_vs_effective": {"available": True, "exact_canonical_match": False},
        },
    )
    assert profile["status"] == "DIFFERENCE_OBSERVED"
    assert profile["local_override_candidate"] is True
    assert profile["local_override_status"] == "NOT_PROVEN"


def test_a4_primary_gate_accepts_effective_when_active_is_unavailable(tmp_path, monkeypatch):
    monkeypatch.setattr(collector, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(collector, "_get_support_key", lambda: b"fixed-test-key")
    monkeypatch.setattr(collector, "_tls_verify_setting", lambda: False)
    monkeypatch.setattr(collector, "_direct_tls_verify_setting", lambda: False)
    monkeypatch.setattr(collector, "get_api_key", lambda *a, **k: "PAN-KEY")
    monkeypatch.setattr(collector, "get_firewall_api_key", lambda *a, **k: "FW-KEY")
    monkeypatch.setattr(collector, "get_panorama_management_config", lambda *a, **k: PANORAMA_XML)
    monkeypatch.setattr(collector, "get_active_running_config", lambda *a, **k: FIREWALL_XML)
    monkeypatch.setattr(
        collector,
        "get_devices",
        lambda *a, **k: [{
            "serial": "SERIAL-1",
            "hostname": "FW-ONE",
            "connected": "yes",
            "management_ip": "192.0.2.10",
            "model": "PA",
            "sw_version": "11.1",
            "shared_policy_status": "In Sync",
            "template_status": "In Sync",
        }],
    )
    monkeypatch.setattr(
        collector,
        "get_direct_system_info",
        lambda *a, **k: {"serial": "SERIAL-1", "hostname": "FW-ONE", "model": "PA", "sw_version": "11.1"},
    )

    def direct_active(*a, **k):
        raise collector.PanoramaConfigError("API denied")

    def op_config(host, key, mode, **kwargs):
        if mode == "pushed-template":
            raise collector.PanoramaConfigError("unsupported")
        return FIREWALL_XML

    monkeypatch.setattr(collector, "get_direct_active_config", direct_active)
    monkeypatch.setattr(collector, "get_direct_operational_config", op_config)

    class TempStore(ConfigEvidenceStore):
        def __init__(self):
            super().__init__(tmp_path / "configs")

    monkeypatch.setattr(collector, "ConfigEvidenceStore", TempStore)
    cfg = SimpleNamespace(auth=SimpleNamespace(principal="user", secret="secret"), panorama_ip="192.0.2.1")
    result = collector.run_panorama_config_evidence(cfg, limit=1, max_workers=1)

    summary = result["summary"]
    assert summary["primary_evidence_success"] == 1
    assert summary["direct_effective_success"] == 1
    assert summary["direct_merged_success"] == 1
    assert summary["direct_active_success"] == 0
    assert summary["alignment_evidence_partial"] == 1
    assert summary["stage_pass"] is True
    assert summary["panorama_assignment_mapped"] == 1

    failures = json.loads(Path(result["failures_path"]).read_text(encoding="utf-8"))["failures"]
    active = next(item for item in failures if item["method"] == "DIRECT_HTTPS_API_ACTIVE_CONFIG")
    assert active["failure_stage"] == "direct_active_api_query"
    assert active["transport"] == "DIRECT_HTTPS_XML_API"
    assert active["required_for_primary"] is False
    assert active["required_for_alignment"] is True

    with zipfile.ZipFile(result["support_path"]) as zf:
        support = json.loads(zf.read("summary.json"))
        text = zf.read("support.log").decode("utf-8")
    assert "FW-ONE" not in json.dumps(support)
    assert "192.0.2.10" not in json.dumps(support)
    assert "DIRECT_HTTPS_API_ACTIVE_CONFIG" in text
    assert "FW-ONE" not in text


def test_direct_artifact_separates_local_store_permission_failure(monkeypatch):
    monkeypatch.setattr(
        collector,
        "_store_artifact",
        lambda *a, **k: (_ for _ in ()).throw(PermissionError("locked")),
    )
    result = collector._collect_direct_artifact(
        getter=lambda: FIREWALL_XML,
        store=object(),
        serial="SERIAL-1",
        device={"hostname": "FW-ONE", "management_ip": "192.0.2.10"},
        run_id="RUN",
        name="active",
        artifact_type=collector.DIRECT_ACTIVE_ARTIFACT_TYPE,
        evidence_method=collector.DIRECT_ACTIVE_METHOD,
        method_id="DIRECT_HTTPS_API_ACTIVE_CONFIG",
        artifact_name="direct-active-config.xml",
        retrieval_scope="direct_firewall_active_config",
        required_for_primary=False,
        required_for_alignment=True,
    )
    assert result["status"] == "failed"
    assert result["failure_stage"] == "direct_active_local_store"
    assert result["failure_domain"] == "local_store"
    assert result["transport"] == "LOCAL_IMMUTABLE_EVIDENCE_STORE"
    assert result["error_type"] == "PermissionError"
    assert result["error_hint"] == "local_filesystem_permission_or_lock"
