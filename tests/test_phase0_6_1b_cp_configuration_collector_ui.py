import json
from pathlib import Path

from configuration import checkpoint_config_collector as collector
from utils.config_ui import build_configuration_ui_payload
import pytest
from utils.html_export import compose_report_script as _composed_report_script

pytestmark = pytest.mark.configuration


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")
APP = _composed_report_script()


def test_secret_aware_sanitization_never_persists_secret_line_and_tracks_raw_change():
    a = "\n".join([
        "set hostname gw1",
        "set dns primary 10.0.0.53",
        "set snmp community very-secret read-only",
    ])
    b = a.replace("very-secret", "another-secret")
    sa = collector._sanitize_configuration(a)
    sb = collector._sanitize_configuration(b)

    assert "very-secret" not in sa["sanitized_text"]
    assert "another-secret" not in sb["sanitized_text"]
    assert sa["secret_bearing_line_count"] == 1
    assert sa["safe_set_line_count"] == 2
    assert "SECRET-BEARING CONFIGURATION LINE WITHHELD" in sa["sanitized_text"]
    # Secret-only change must still produce a different safe history object.
    assert sa["raw_canonical_sha256"] != sb["raw_canonical_sha256"]
    assert sa["sanitized_text"] != sb["sanitized_text"]
    assert "set_lines" not in sa  # raw secret-bearing lines are not retained in the summary object


def test_checkpoint_projection_exposes_basic_safe_values_and_withheld_count():
    current = collector.build_checkpoint_current_configuration(
        [
            "set hostname GW-CP-01",
            "set domainname example.net",
            "set timezone Europe/Istanbul",
            "set dns primary 10.0.0.53",
            "set dns secondary 10.0.0.54",
            "set ntp server primary 10.0.0.10 version 4",
            "set interface eth0 ipv4-address 10.0.0.1 mask-length 24",
            "set static-route 0.0.0.0/0 nexthop gateway address 10.0.0.254 on",
        ],
        secret_bearing_line_count=3,
        entity_type="standalone_gateway",
    )
    labels = {row["label"]: row["value"] for row in current["highlights"]}
    assert labels["Hostname"] == "GW-CP-01"
    assert labels["Primary DNS"] == "10.0.0.53"
    assert current["redacted_secret_setting_count"] == 3
    assert current["raw_config_included"] is False
    assert any(section["id"] == "interfaces" for section in current["sections"])
    assert any(section["id"] == "routing" for section in current["sections"])


def test_target_resolution_covers_standalone_clusterxl_and_observed_vsx_contexts(tmp_path, monkeypatch):
    out = tmp_path / "output"
    out.mkdir()
    telemetry = {
        "remote_command_status": [
            {"device": "GW1", "management_ip": "10.0.0.1", "object_type": "gateway", "cma": "CMA1"},
            {"device": "CL1", "management_ip": "10.0.0.2", "object_type": "cluster_member", "cma": "CMA1"},
            {"device": "CL2", "management_ip": "10.0.0.3", "object_type": "cluster_member", "cma": "CMA1"},
            {"device": "VSX1", "management_ip": "10.0.0.4", "object_type": "cluster_member", "vsx_cluster_member": "true", "cma": "CMA1"},
        ]
    }
    cp = [
        {"device": "CL1", "cluster_topology": {"group_id": "g1", "display_name": "CLUSTER-A"}},
        {"device": "CL2", "cluster_topology": {"group_id": "g1", "display_name": "CLUSTER-A"}},
    ]
    vsx = [
        {"device": "VSX1", "device_ip": "10.0.0.4", "vsys": "VS-A", "vs_id": "3"},
        {"device": "VSX1", "device_ip": "10.0.0.4", "vsys": "VS-B", "vs_id": "7"},
    ]
    (out / "cp_telemetry.json").write_text(json.dumps(telemetry), encoding="utf-8")
    (out / "cp.json").write_text(json.dumps(cp), encoding="utf-8")
    (out / "vsx.json").write_text(json.dumps(vsx), encoding="utf-8")
    monkeypatch.setattr(collector, "OUTPUT_DIR", out)

    targets, skipped = collector._resolve_targets()
    assert skipped == []
    by_device = {target.device: target for target in targets}
    assert by_device["GW1"].entity_type == "standalone_gateway"
    assert by_device["CL1"].entity_type == "clusterxl_member"
    assert by_device["CL1"].cluster_group_id == "g1"
    assert by_device["VSX1"].entity_type == "vsx_host"
    assert [ctx.vs_id for ctx in by_device["VSX1"].contexts] == ["3", "7"]


def test_cluster_member_current_state_difference_is_member_specific_not_drift():
    def row(name, ip):
        current = collector.build_checkpoint_current_configuration(
            [f"set hostname {name}", f"set interface eth0 ipv4-address {ip} mask-length 24", "set dns primary 10.0.0.53"],
            secret_bearing_line_count=0,
            entity_type="clusterxl_member",
        )
        return {
            "entity_type": "clusterxl_member",
            "cluster_group_id": "g1",
            "status": "success",
            "current_configuration": current,
        }

    rows = [row("CL1", "10.0.0.1"), row("CL2", "10.0.0.2")]
    collector._apply_cluster_member_semantics(rows)
    assert all(item["member_specific_setting_count"] >= 1 for item in rows)
    assert any(
        setting["origin"] == "member_specific"
        for section in rows[0]["current_configuration"]["sections"]
        for setting in section["settings"]
    )


def test_configuration_ui_merges_pan_and_checkpoint_without_raw_or_hashes(tmp_path):
    cp_result = {
        "summary": {
            "selected": 2,
            "success": 2,
            "failed": 0,
            "first": 2,
            "same": 0,
            "changed": 0,
            "secret_bearing_lines_withheld": 4,
            "member_specific_settings": 2,
            "host_key_policy": "observe_and_record_not_production",
            "production_trust_ready": False,
        },
        "devices": [
            {
                "entity_id": "GW1",
                "entity_type": "standalone_gateway",
                "device": "GW1",
                "display_name": "GW1",
                "management_ip": "10.0.0.1",
                "status": "success",
                "completed_at": "2026-08-24T00:00:00+00:00",
                "identity_gate": {"accepted": True, "status": "VERIFIED_MANAGEMENT_ENDPOINT_AND_HOSTNAME", "confidence": "HIGH"},
                "host_key_policy": "observe_and_record_not_production",
                "current_configuration": collector.build_checkpoint_current_configuration(
                    ["set hostname GW1", "set dns primary 10.0.0.53"],
                    secret_bearing_line_count=2,
                    entity_type="standalone_gateway",
                ),
                "evidence": {"actual": {"status": "success", "method": collector.PHYSICAL_METHOD, "transport": "direct_ssh", "change_state": "first", "size_bytes": 100}},
                "history": {"actual_change_state": "first"},
            },
            {
                "entity_id": "VSX1__vsid_3",
                "entity_type": "virtual_system",
                "device": "VSX1",
                "display_name": "VS-A",
                "parent_name": "VSX1",
                "parent_entity_id": "VSX1",
                "management_ip": "10.0.0.4",
                "vs_id": "3",
                "status": "success",
                "completed_at": "2026-08-24T00:00:00+00:00",
                "identity_gate": {"accepted": True, "status": "VERIFIED", "confidence": "HIGH"},
                "host_key_policy": "observe_and_record_not_production",
                "current_configuration": collector.build_checkpoint_current_configuration(
                    ["set hostname VS-A"], secret_bearing_line_count=2, entity_type="virtual_system", context_label="VSID 3"
                ),
                "evidence": {"actual": {"status": "success", "method": collector.VSX_METHOD, "transport": "direct_ssh", "change_state": "first", "size_bytes": 80}},
                "history": {"actual_change_state": "first"},
            },
        ],
    }
    payload = build_configuration_ui_payload(None, checkpoint_config_result=cp_result)
    assert payload["available"] is True
    assert payload["fleet"]["checkpoint_selected"] == 2
    assert payload["fleet"]["primary_evidence_success"] == 2
    assert {device["vendor_key"] for device in payload["devices"]} == {"check_point"}
    vs = next(device for device in payload["devices"] if device["entity_type"] == "virtual_system")
    assert vs["parent_name"] == "VSX1"
    blob = json.dumps(payload)
    assert "raw-canonical-sha256" not in blob
    assert "raw_canonical_sha256" not in blob
    assert "password" not in blob.lower()


def test_main_and_frontend_expose_061b_without_changing_expert_shell_contract():
    assert '"--cp-config-collect"' in MAIN
    assert '"--cp-config-stage"' in MAIN
    assert '"--cp-config-workers"' in MAIN
    assert "run_checkpoint_config_collection" in MAIN
    assert "checkpoint_config_result=checkpoint_config_result" in MAIN
    assert "Expert shell" in Path(collector.__file__).read_text(encoding="utf-8")
    assert "vsenv {context.vs_id}" in Path(collector.__file__).read_text(encoding="utf-8")
    assert "Check Point alignment" in APP
    assert "Gaia current configuration" in APP
    assert "Check Point SSH trust" in APP


def test_collect_host_writes_only_redacted_cas_for_physical_and_vsx(tmp_path, monkeypatch):
    from utils.config_evidence import ConfigEvidenceStore

    class FakeSSH:
        def close(self):
            pass

    def fake_connect(target, username, secret, *, strict, connect_timeout):
        return FakeSSH(), "SHA256:fake-host-key"

    def result(stdout, success=True):
        return {
            "success": success,
            "error_class": "none" if success else "command_error",
            "error_detail": None,
            "timeout": False,
            "exit_status": 0 if success else 1,
            "duration_ms": 1,
            "stdout": stdout,
            "stderr": "",
        }

    def fake_exec(_ssh, command, _timeout):
        if "shell=" in command:
            return result("shell=/bin/bash\nuser=admin\n")
        if "show hostname" in command:
            return result("GW1\n")
        if "show version all" in command:
            return result("Product version Check Point Gaia R82\n")
        if "cpstat os -f hw_info" in command:
            return result("Appliance SN: SERIAL1\nAppliance Name: Check Point 6500\n")
        if command.startswith("vsenv "):
            return result("set hostname VS-A\nset user admin password verysecret\nset dns primary 10.0.0.53\n")
        if "show configuration" in command:
            return result("set hostname GW1\nset snmp community dontshare read-only\nset dns primary 10.0.0.53\n")
        raise AssertionError(command)

    monkeypatch.setattr(collector, "_connect", fake_connect)
    monkeypatch.setattr(collector, "_run_exec", fake_exec)
    monkeypatch.setattr(
        collector,
        "_run_vsx_clish_context",
        lambda _ssh, _vsid, _timeout: result("set hostname VS-A\nset dns primary 10.0.0.53\n"),
    )

    store = ConfigEvidenceStore(root=tmp_path / "configs")
    target = collector.PhysicalTarget(
        device="GW1",
        management_ip="10.0.0.1",
        object_type="cluster_member",
        entity_type="vsx_host",
        contexts=[collector.VsContext(vs_id="3", vs_name="VS-A")],
    )
    rows = collector._collect_host(
        target,
        username="admin",
        secret="pw",
        strict_host_key=False,
        connect_timeout=2,
        command_timeout=5,
        store=store,
    )
    assert len(rows) == 2
    assert all(row["status"] == "success" for row in rows)
    blobs = [path for path in store.artifact_root.rglob("*") if path.is_file()]
    assert len(blobs) == 2
    content = "\n".join(path.read_text(encoding="utf-8") for path in blobs)
    assert "dontshare" not in content
    assert "verysecret" not in content
    assert content.count("SECRET-BEARING CONFIGURATION LINE WITHHELD") == 2
    assert "raw-canonical-sha256=" in content
    assert all(row["raw_configuration_persisted"] is False for row in rows)


def test_vsx_virtual_system_gets_independent_ha_role_not_inherited(tmp_path, monkeypatch):
    """A virtual system's HA role must come from its own vsenv-scoped probe,
    not be presented as inherited runtime evidence from the physical member.

    Regression for cp_ha_runtime (0.6.1B.1.2 VSX closure):
    docs/history/phase/PHASE0_6_1B_1_2_CP_HA_RUNTIME_VSX_CLOSURE.md.
    """
    from utils.config_evidence import ConfigEvidenceStore

    class FakeSSH:
        def close(self):
            pass

    def fake_connect(target, username, secret, *, strict, connect_timeout):
        return FakeSSH(), "SHA256:fake-host-key"

    def result(stdout, success=True):
        return {
            "success": success,
            "error_class": "none" if success else "command_error",
            "error_detail": None,
            "timeout": False,
            "exit_status": 0 if success else 1,
            "duration_ms": 1,
            "stdout": stdout,
            "stderr": "",
        }

    def fake_exec(_ssh, command, _timeout):
        if "show hostname" in command:
            return result("GW1\n")
        if "show version all" in command:
            return result("Product version Check Point Gaia R82\n")
        if "cpstat os -f hw_info" in command:
            return result("Appliance SN: SERIAL1\nAppliance Name: Check Point 6500\n")
        if command == "cphaprob stat":
            # Physical member is ACTIVE...
            return result("GW1 (local) ACTIVE\nGW2 STANDBY\n")
        if command.startswith("vsenv ") and command.endswith("cphaprob stat"):
            # ...but this virtual system is independently STANDBY on GW1.
            return result("VS-A (local) STANDBY\n")
        if command.startswith("vsenv ") and "clish -c" in command:
            return result("set hostname VS-A\nset dns primary 10.0.0.53\n")
        if "show configuration" in command:
            return result("set hostname GW1\nset dns primary 10.0.0.53\n")
        raise AssertionError(command)

    monkeypatch.setattr(collector, "_connect", fake_connect)
    monkeypatch.setattr(collector, "_run_exec", fake_exec)
    monkeypatch.setattr(
        collector,
        "_run_vsx_clish_context",
        lambda _ssh, _vsid, _timeout: result("set hostname VS-A\nset dns primary 10.0.0.53\n"),
    )

    store = ConfigEvidenceStore(root=tmp_path / "configs")
    target = collector.PhysicalTarget(
        device="GW1",
        management_ip="10.0.0.1",
        object_type="cluster_member",
        entity_type="vsx_host",
        contexts=[collector.VsContext(vs_id="3", vs_name="VS-A")],
    )
    rows = collector._collect_host(
        target,
        username="admin",
        secret="pw",
        strict_host_key=False,
        connect_timeout=2,
        command_timeout=5,
        store=store,
    )
    host_row = next(row for row in rows if row["entity_type"] == "vsx_host")
    vs_row = next(row for row in rows if row["entity_type"] == "virtual_system")

    assert host_row["ha_role"] == "ACTIVE"
    assert vs_row["ha_role"] == "STANDBY"
    assert vs_row["ha_role"] != host_row["ha_role"]
    assert vs_row["ha_role_source"] == "interactive_cphaprob_stat_runtime_per_vs"
    assert vs_row["ha_runtime_status"] == "success"


def test_vsx_virtual_system_falls_back_to_labeled_inherited_role_when_per_vs_probe_fails(tmp_path, monkeypatch):
    """When the per-VS probe cannot produce an independent role, the fallback
    to the physical member's role must be explicitly labeled as inherited,
    never presented as VS-specific runtime evidence.
    """
    from utils.config_evidence import ConfigEvidenceStore

    class FakeSSH:
        def close(self):
            pass

    def fake_connect(target, username, secret, *, strict, connect_timeout):
        return FakeSSH(), "SHA256:fake-host-key"

    def result(stdout, success=True):
        return {
            "success": success,
            "error_class": "none" if success else "command_error",
            "error_detail": None,
            "timeout": False,
            "exit_status": 0 if success else 1,
            "duration_ms": 1,
            "stdout": stdout,
            "stderr": "",
        }

    def fake_exec(_ssh, command, _timeout):
        if "show hostname" in command:
            return result("GW1\n")
        if "show version all" in command:
            return result("Product version Check Point Gaia R82\n")
        if "cpstat os -f hw_info" in command:
            return result("Appliance SN: SERIAL1\nAppliance Name: Check Point 6500\n")
        if command == "cphaprob stat":
            return result("GW1 (local) ACTIVE\n")
        if command.startswith("vsenv ") and command.endswith("cphaprob stat"):
            # Per-VS probe returns no parseable role (no local-marker/state token).
            return result("unrecognized output\n")
        if command.startswith("vsenv ") and "clish -c" in command:
            return result("set hostname VS-A\nset dns primary 10.0.0.53\n")
        if "show configuration" in command:
            return result("set hostname GW1\nset dns primary 10.0.0.53\n")
        raise AssertionError(command)

    monkeypatch.setattr(collector, "_connect", fake_connect)
    monkeypatch.setattr(collector, "_run_exec", fake_exec)
    monkeypatch.setattr(
        collector,
        "_run_vsx_clish_context",
        lambda _ssh, _vsid, _timeout: result("set hostname VS-A\nset dns primary 10.0.0.53\n"),
    )

    store = ConfigEvidenceStore(root=tmp_path / "configs")
    target = collector.PhysicalTarget(
        device="GW1",
        management_ip="10.0.0.1",
        object_type="cluster_member",
        entity_type="vsx_host",
        contexts=[collector.VsContext(vs_id="3", vs_name="VS-A")],
    )
    rows = collector._collect_host(
        target,
        username="admin",
        secret="pw",
        strict_host_key=False,
        connect_timeout=2,
        command_timeout=5,
        store=store,
    )
    vs_row = next(row for row in rows if row["entity_type"] == "virtual_system")

    assert vs_row["ha_role"] == "ACTIVE"  # inherited value, not invented
    assert vs_row["ha_role_source"] == "inherited_from_physical_member"
    assert vs_row["ha_runtime_status"] == "unavailable_inherited"


def test_direct_clish_ha_probe_reports_explicit_capability_gap(tmp_path, monkeypatch):
    """cphaprob is Expert/bash-level; a direct-Clish-only session must resolve
    to an explicit capability_gap, not an unexplained 'unavailable'.
    """
    from utils.config_evidence import ConfigEvidenceStore

    class FakeSSH:
        def close(self):
            pass

    class FakeInteractive:
        def __init__(self, responses):
            self.responses = responses

        def run(self, command, _timeout):
            return self.responses.get(
                command,
                {
                    "success": False, "error_class": "cli_rejected", "error_detail": None,
                    "timeout": False, "exit_status": None, "duration_ms": 1,
                    "stdout": "", "stderr": "unknown command",
                },
            )

        def close(self):
            pass

    def fake_connect(target, username, secret, *, strict, connect_timeout):
        return FakeSSH(), "SHA256:fake-host-key"

    def result(stdout, success=True, error_class="none"):
        return {
            "success": success,
            "error_class": error_class,
            "error_detail": None,
            "timeout": False,
            "exit_status": 0 if success else 1,
            "duration_ms": 1,
            "stdout": stdout,
            "stderr": "",
        }

    # Every command in this test is issued through the interactive session
    # (proven "interactive_direct_clish" below) EXCEPT cphaprob, which the
    # collector deliberately routes through the exec channel for direct-Clish
    # hosts since it is an Expert/bash-level command, not a Clish one.
    interactive = FakeInteractive({
        "show hostname": result("GW1\n"),
        "show version all": result("Product version Check Point Gaia R82\n"),
        "cpstat os -f hw_info": result("Appliance SN: SERIAL1\nAppliance Name: Check Point 6500\n"),
        "show configuration": result("set hostname GW1\nset dns primary 10.0.0.53\n"),
    })

    def fake_exec(_ssh, command, _timeout):
        if command == "cphaprob stat":
            # Rejected: not a recognized command in a direct-Clish-only shell.
            return result("Unknown command cphaprob\n", success=False, error_class="command_error")
        raise AssertionError(command)

    monkeypatch.setattr(collector, "_connect", fake_connect)
    monkeypatch.setattr(collector, "_run_exec", fake_exec)
    monkeypatch.setattr(collector, "InteractiveSshSession", lambda _ssh, _timeout: interactive)

    store = ConfigEvidenceStore(root=tmp_path / "configs")
    target = collector.PhysicalTarget(
        device="GW1",
        management_ip="10.0.0.1",
        object_type="cluster_member",
        entity_type="clusterxl_member",
        contexts=[],
    )
    rows = collector._collect_host(
        target,
        username="admin",
        secret="pw",
        strict_host_key=False,
        connect_timeout=2,
        command_timeout=5,
        store=store,
    )
    host_row = rows[0]
    assert host_row["ssh_shell_mode"] == "interactive_direct_clish"
    assert host_row["ha_runtime_status"] == "capability_gap"
    assert host_row["ha_runtime_error_class"] == "cphaprob_unavailable_in_direct_clish"
