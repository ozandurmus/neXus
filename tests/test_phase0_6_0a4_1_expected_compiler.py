from configuration.pan_expected_compiler import compile_panorama_expected, expected_for_serial
import pytest

pytestmark = pytest.mark.configuration


PANORAMA_COMPILER_XML = b"""<config>
  <devices><entry name="localhost.localdomain">
    <template>
      <entry name="BASE"><config><devices><entry name="localhost.localdomain">
        <deviceconfig><system><dns-setting><servers><primary>10.0.0.1</primary><secondary>10.0.0.2</secondary></servers></dns-setting></system></deviceconfig>
        <network><virtual-router><entry name="VR-A"><routing-table><ip><static-route>
          <entry name="DEFAULT"><nexthop><ip-address>$GW</ip-address></nexthop></entry>
        </static-route></ip></routing-table></entry></virtual-router></network>
      </entry></devices></config></entry>
      <entry name="SITE"><config><devices><entry name="localhost.localdomain">
        <deviceconfig><system><dns-setting><servers><primary>10.0.0.9</primary></servers></dns-setting></system></deviceconfig>
        <network><profiles><monitor-profile><entry name="M1"><interval>3</interval><action>fail-over</action></entry></monitor-profile></profiles></network>
      </entry></devices></config></entry>
    </template>
    <template-stack><entry name="STACK-A">
      <templates><member>SITE</member><member>BASE</member></templates>
      <devices><entry name="SERIAL-1"/></devices>
      <config><devices><entry name="localhost.localdomain">
        <deviceconfig><system><dns-setting><servers><secondary>10.0.0.8</secondary></servers></dns-setting></system></deviceconfig>
      </entry></devices></config>
    </entry></template-stack>
    <device-group>
      <entry name="DG-PARENT">
        <pre-rulebase><security><rules><entry name="PARENT-PRE"/></rules></security></pre-rulebase>
        <post-rulebase><security><rules><entry name="PARENT-POST"/></rules></security></post-rulebase>
      </entry>
      <entry name="DG-CHILD">
        <parent-dg>DG-PARENT</parent-dg>
        <devices><entry name="SERIAL-1"><vsys><entry name="vsys1"/></vsys></entry></devices>
        <pre-rulebase><security><rules><entry name="CHILD-PRE"/></rules></security></pre-rulebase>
        <post-rulebase><security><rules><entry name="CHILD-POST"/></rules></security></post-rulebase>
      </entry>
    </device-group>
  </entry></devices>
  <shared>
    <pre-rulebase><security><rules><entry name="SHARED-PRE"/></rules></security></pre-rulebase>
    <post-rulebase><security><rules><entry name="SHARED-POST"/></rules></security></post-rulebase>
  </shared>
</config>"""


def _setting_by_suffix(stack, suffix):
    return next(item for item in stack["manifest"] if item["path"].endswith(suffix))


def test_template_stack_compiler_respects_stack_and_template_priority():
    compiled = compile_panorama_expected(PANORAMA_COMPILER_XML)
    stack = compiled["template_stacks"]["STACK-A"]

    primary = _setting_by_suffix(stack, "/primary")
    secondary = _setting_by_suffix(stack, "/secondary")
    interval = _setting_by_suffix(stack, "/interval")

    # SITE is higher than BASE in the list, so duplicate primary comes from SITE.
    assert primary["source_kind"] == "template"
    assert primary["source_name"] == "SITE"
    # Stack-level values override inherited templates.
    assert secondary["source_kind"] == "template_stack_override"
    assert secondary["source_name"] == "STACK-A"
    assert interval["source_name"] == "SITE"
    assert stack["shadowed_setting_count"] >= 2
    assert stack["precedence_contract"]["template_order"] == "listed_top_to_bottom_high_to_low"


def test_compiler_marks_variable_references_not_alignment_ready():
    compiled = compile_panorama_expected(PANORAMA_COMPILER_XML)
    stack = compiled["template_stacks"]["STACK-A"]
    variable = _setting_by_suffix(stack, "/ip-address")
    assert variable["value_kind"] == "variable_reference"
    assert variable["alignment_ready"] is False
    assert stack["unresolved_variable_setting_count"] == 1
    assert stack["status"] == "partial"


def test_device_group_policy_lineage_matches_panorama_evaluation_order():
    compiled = compile_panorama_expected(PANORAMA_COMPILER_XML)
    expected = expected_for_serial(compiled, "SERIAL-1")
    assert expected["primary_template_stack"] == "STACK-A"
    assert expected["device_group_assignments"][0]["device_group"] == "DG-CHILD"

    lineage = expected["policy_scopes"][0]["lineage"]
    assert lineage["lineage_high_to_low"] == ["Shared", "DG-PARENT", "DG-CHILD"]
    contract = lineage["policy_evaluation_contract"]
    assert contract["pre_rules"] == ["Shared", "DG-PARENT", "DG-CHILD", "LOCAL_FIREWALL_RULES"]
    assert contract["post_rules"] == ["LOCAL_FIREWALL_RULES", "DG-CHILD", "DG-PARENT", "Shared"]
    assert lineage["expected_panorama_rule_counts"]["pre"]["security"] == 3
    assert lineage["expected_panorama_rule_counts"]["post"]["security"] == 3


def test_compiler_does_not_store_raw_values_in_manifest_contract():
    compiled = compile_panorama_expected(PANORAMA_COMPILER_XML)
    stack = compiled["template_stacks"]["STACK-A"]
    assert stack["raw_values_in_manifest"] is False
    assert compiled["compiler_contract"]["raw_values_stored"] is False
    blob = str(stack["manifest"])
    assert "10.0.0.9" not in blob
    assert "10.0.0.8" not in blob
    assert "$GW" not in blob


def test_unmapped_serial_is_explicit_not_inferred():
    compiled = compile_panorama_expected(PANORAMA_COMPILER_XML)
    expected = expected_for_serial(compiled, "SERIAL-NOT-THERE")
    assert expected["status"] == "unmapped"
    assert expected["template_stack_status"] == "unmapped"
    assert "serial_not_present_in_expected_compiler" in expected["anomalies"]


def test_support_safe_expected_summary_contains_no_raw_stack_or_values(tmp_path, monkeypatch):
    import json
    import zipfile
    from types import SimpleNamespace
    from configuration import panorama_config_collector as collector
    from utils.config_evidence import ConfigEvidenceStore

    firewall_xml = b"""<config><devices><entry name="localhost.localdomain">
      <deviceconfig><system><hostname>FW-SECRET</hostname></system></deviceconfig>
      <network><virtual-router><entry name="VR-SECRET"/></virtual-router></network>
      <vsys><entry name="vsys1"/></vsys>
    </entry></devices></config>"""

    monkeypatch.setattr(collector, "OUTPUT_DIR", tmp_path / "output")
    monkeypatch.setattr(collector, "DERIVED_EXPECTED_DIR", tmp_path / "derived")
    monkeypatch.setattr(collector, "_get_support_key", lambda: b"fixed-test-key")
    monkeypatch.setattr(collector, "_tls_verify_setting", lambda: False)
    monkeypatch.setattr(collector, "_direct_tls_verify_setting", lambda: False)
    monkeypatch.setattr(collector, "get_api_key", lambda *a, **k: "PANKEY")
    monkeypatch.setattr(collector, "get_firewall_api_key", lambda *a, **k: "FWKEY")
    monkeypatch.setattr(collector, "get_panorama_management_config", lambda *a, **k: PANORAMA_COMPILER_XML)
    monkeypatch.setattr(collector, "get_active_running_config", lambda *a, **k: firewall_xml)
    monkeypatch.setattr(collector, "get_direct_active_config", lambda *a, **k: firewall_xml)
    monkeypatch.setattr(collector, "get_direct_operational_config", lambda *a, **k: firewall_xml)
    monkeypatch.setattr(
        collector,
        "get_devices",
        lambda *a, **k: [{
            "serial": "SERIAL-1",
            "hostname": "FW-SECRET",
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
        lambda *a, **k: {"serial": "SERIAL-1", "hostname": "FW-SECRET", "model": "PA", "sw_version": "11.1"},
    )

    class TempStore(ConfigEvidenceStore):
        def __init__(self):
            super().__init__(tmp_path / "configs")

    monkeypatch.setattr(collector, "ConfigEvidenceStore", TempStore)
    cfg = SimpleNamespace(auth=SimpleNamespace(principal="user", secret="secret"), panorama_ip="192.0.2.250")
    result = collector.run_panorama_config_evidence(cfg, limit=1, max_workers=1)

    assert result["summary"]["expected_compiler_selected_mapped"] == 1
    assert result["summary"]["expected_compiler_gate"] is True
    assert result["summary"]["a4_1_stage_pass"] is True
    assert result["expected_compiler_manifest_path"]
    assert result["expected_compiler_report_path"]

    with zipfile.ZipFile(result["support_path"]) as zf:
        support_text = zf.read("summary.json").decode("utf-8")
        support = json.loads(support_text)
    assert support["expected_compiler"]["manifest_in_support_bundle"] is False
    assert support["devices"][0]["expected_configuration"]["template_stack_count"] == 1
    assert "STACK-A" not in support_text
    assert "SITE" not in support_text
    assert "10.0.0.9" not in support_text
    assert "FW-SECRET" not in support_text
