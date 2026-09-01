import json
from pathlib import Path

import pytest

from configuration import checkpoint_config_probe as probe

pytestmark = pytest.mark.configuration


def test_expert_login_contract_invokes_gaia_clish_explicitly():
    assert probe.EXPERT_READ_ONLY_COMMANDS["hostname"] == "clish -c 'show hostname'"
    assert probe.EXPERT_READ_ONLY_COMMANDS["version"] == "clish -c 'show version all'"
    assert probe.EXPERT_READ_ONLY_COMMANDS["configuration"] == "clish -c 'show configuration'"
    source = Path(probe.__file__).read_text(encoding="utf-8")
    assert 'channel.exec_command("clish")' in source
    assert 'channel.send(f"set virtual-system {vs_id}\\n")' in source
    assert 'channel.send("show configuration\\n")' in source
    assert "vsenv {target.vs_id}" in source


def test_configuration_summary_never_returns_raw_configuration_and_uses_set_fingerprint():
    raw = """# Exported by admin now\nset hostname gw1\nset timezone Europe / Istanbul\nset snmp community dont-share read-only\n"""
    summary = probe._configuration_summary(raw)
    assert summary["set_lines"] == 3
    assert summary["secret_bearing_lines_detected"] == 1
    assert summary["feature_markers"]["hostname"] is True
    assert "dont-share" not in json.dumps(summary)
    assert "fingerprint_sha256" not in summary
    assert summary["canonical_set_fingerprint_sha256"]


def test_canonical_set_fingerprint_ignores_export_comment_noise():
    a = probe._configuration_summary("# Exported at 10:00\nset hostname gw1\nset ntp active on\n")
    b = probe._configuration_summary("# Exported at 10:01\nset hostname gw1\nset ntp active on\n")
    assert a["canonical_set_fingerprint_sha256"] == b["canonical_set_fingerprint_sha256"]


def test_target_selection_builds_standalone_cluster_pair_and_vsx_context(tmp_path, monkeypatch):
    out = tmp_path / "output"
    out.mkdir()
    telemetry = {
        "remote_command_status": [
            {"device": "GW-STANDALONE", "management_ip": "10.0.0.1", "object_type": "gateway", "management_state": "communicating", "vsx_cluster_member": "false", "vs_cluster_member": "false", "cma": "CMA1"},
            {"device": "CL-M1", "management_ip": "10.0.0.2", "object_type": "cluster_member", "management_state": "communicating", "vsx_cluster_member": "false", "vs_cluster_member": "false", "cma": "CMA1"},
            {"device": "CL-M2", "management_ip": "10.0.0.3", "object_type": "cluster_member", "management_state": "communicating", "vsx_cluster_member": "false", "vs_cluster_member": "false", "cma": "CMA1"},
            {"device": "VSX-M1", "management_ip": "10.0.0.4", "object_type": "cluster_member", "management_state": "communicating", "vsx_cluster_member": "true", "vs_cluster_member": "true", "cma": "CMA1"},
        ]
    }
    cp = [
        {"device": "CL-M1", "cluster_topology": {"group_id": "g1"}},
        {"device": "CL-M2", "cluster_topology": {"group_id": "g1"}},
    ]
    vsx = [
        {"device": "VSX-M1", "device_ip": "10.0.0.4", "vsys": "VS-A", "vs_id": "3"},
    ]
    (out / "cp_telemetry.json").write_text(json.dumps(telemetry), encoding="utf-8")
    (out / "cp.json").write_text(json.dumps(cp), encoding="utf-8")
    (out / "vsx.json").write_text(json.dumps(vsx), encoding="utf-8")
    monkeypatch.setattr(probe, "OUTPUT_DIR", out)

    targets, gaps = probe._pick_targets()
    assert gaps == []
    roles = [t.role for t in targets]
    assert roles == ["standalone", "clusterxl_member_1", "clusterxl_member_2", "vsx_host", "vsx_virtual_system"]
    assert targets[-1].vs_id == "3"


def test_main_exposes_cp_config_probe_without_promoting_configuration():
    # codebase_modularization (backend): the flag definition and the mode body
    # moved out of main.py into application/cli.py and application/workflows/checkpoint.py.
    root = Path(__file__).resolve().parents[1]
    cli_text = (root / "application" / "cli.py").read_text(encoding="utf-8")
    checkpoint_wf_text = (root / "application" / "workflows" / "checkpoint.py").read_text(encoding="utf-8")
    assert '"--cp-config-probe"' in cli_text
    assert "run_checkpoint_config_probe" in checkpoint_wf_text
    module_text = Path(probe.__file__).read_text(encoding="utf-8")
    assert '"configuration_promoted_to_product": False' in module_text
    assert '"raw_configuration_persisted": False' in module_text
    assert "write_xml_snapshot" not in module_text
    assert "write_text_snapshot" not in module_text


def test_vsx_target_resolution_falls_back_to_mature_vsx_device_ip(tmp_path, monkeypatch):
    out = tmp_path / "output"
    out.mkdir()
    telemetry = {
        "remote_command_status": [
            {"device": "GW-STANDALONE", "management_ip": "10.0.0.1", "object_type": "gateway", "management_state": "communicating", "vsx_cluster_member": "false", "vs_cluster_member": "false", "cma": "CMA1"},
            {"device": "CL-M1", "management_ip": "10.0.0.2", "object_type": "cluster_member", "management_state": "communicating", "vsx_cluster_member": "false", "vs_cluster_member": "false", "cma": "CMA1"},
            {"device": "CL-M2", "management_ip": "10.0.0.3", "object_type": "cluster_member", "management_state": "communicating", "vsx_cluster_member": "false", "vs_cluster_member": "false", "cma": "CMA1"},
        ]
    }
    cp = [
        {"device": "CL-M1", "cluster_topology": {"group_id": "g1"}},
        {"device": "CL-M2", "cluster_topology": {"group_id": "g1"}},
    ]
    # This is exactly the important A.1 case: mature VSX runtime has a proven
    # physical member/IP/VSID even when the CP telemetry name map has no VSX row.
    vsx = [
        {"device": "VSX-ACTIVE-MEMBER", "device_ip": "10.0.0.44", "vsys": "VS-A", "vs_id": "3"},
    ]
    (out / "cp_telemetry.json").write_text(json.dumps(telemetry), encoding="utf-8")
    (out / "cp.json").write_text(json.dumps(cp), encoding="utf-8")
    (out / "vsx.json").write_text(json.dumps(vsx), encoding="utf-8")
    monkeypatch.setattr(probe, "OUTPUT_DIR", out)

    targets, gaps = probe._pick_targets()
    assert gaps == []
    vsx_host = next(t for t in targets if t.role == "vsx_host")
    vsx_vs = next(t for t in targets if t.role == "vsx_virtual_system")
    assert vsx_host.management_ip == "10.0.0.44"
    assert vsx_host.selection_source == "mature_vsx_artifact"
    assert vsx_vs.vs_id == "3"


def test_identity_gate_accepts_exact_management_endpoint_even_when_object_name_differs():
    target = probe.ProbeTarget(
        role="standalone",
        device="MGMT-OBJECT-NAME",
        management_ip="10.0.0.1",
        object_type="gateway",
        selection_source="management_discovery",
    )
    result = probe._identity_gate(
        target=target,
        observed_hostname="actual-gaia-hostname",
        hostname_success=True,
        version_success=True,
        authenticated=True,
    )
    assert result["accepted"] is True
    assert result["status"] == "VERIFIED_MANAGEMENT_ENDPOINT_HOSTNAME_DIFF_OBSERVED"
    assert result["confidence"] == "MEDIUM"
    assert result["name_relation"] == "different_observed"


def test_identity_gate_raises_confidence_when_hostname_agrees():
    target = probe.ProbeTarget(
        role="clusterxl_member_1",
        device="FW-CP-01.example.net",
        management_ip="10.0.0.2",
        object_type="cluster_member",
    )
    result = probe._identity_gate(
        target=target,
        observed_hostname="FW-CP-01",
        hostname_success=True,
        version_success=True,
        authenticated=True,
    )
    assert result["accepted"] is True
    assert result["confidence"] == "HIGH"
    assert result["name_relation"] == "shortname_match"


import pytest


# ---------------------------------------------------------------------------
# cp_identity_edges (0.6.1B.1.2 / 0.6.1C) — _identity_relation() synthetic
# edge-case matrix. Reviewed 2026-08-30: docs/history/phase/
# PHASE0_6_1C_CP_IDENTITY_EDGES_REVIEW.md. Every pair below is fabricated,
# not from any real estate.

@pytest.mark.parametrize(
    "expected,observed,relation",
    [
        # -- exact --------------------------------------------------------
        ("fw-core-01", "fw-core-01", "exact"),
        ("FW-CORE-01", "fw-core-01", "exact"),           # case folding
        ("fw-core-01.", "fw-core-01", "exact"),           # trailing FQDN root dot
        ("fw-core-01", "FW-CORE-01.", "exact"),
        # -- shortname_match (FQDN vs short name) --------------------------
        ("fw-core-01.corp.example.net", "fw-core-01", "shortname_match"),
        ("fw-core-01", "fw-core-01.corp.example.net", "shortname_match"),
        ("FW-CORE-01.corp.example.net", "fw-core-01.otherdomain.example", "shortname_match"),
        # -- normalized_match (separator variance, no dot involved --
        #    a dot is always FQDN-parsed as a domain boundary on both
        #    sides, see test_identity_relation_dot_as_literal_separator_*
        #    below, so it is deliberately excluded from this bucket) ------
        ("fw-core-01", "fw_core_01", "normalized_match"),
        ("fw_core_01", "fw core 01", "normalized_match"),
        ("FW CORE 01", "fw-core-01", "normalized_match"),
        # -- different_observed (genuinely different identity) -------------
        ("fw-core-01", "fw-core-02", "different_observed"),
        ("fw-core-01", "unrelated-device-name", "different_observed"),
        # -- unavailable ----------------------------------------------------
        ("fw-core-01", None, "unavailable"),
        ("fw-core-01", "", "unavailable"),
    ],
)
def test_identity_relation_synthetic_edge_case_matrix(expected, observed, relation):
    assert probe._identity_relation(expected, observed) == relation


def test_identity_relation_dot_as_literal_separator_stays_safe_not_a_false_exact():
    """A dot inside the pre-first-dot segment is FQDN-parsed (domain-stripped)
    consistently on both sides, per _normalize_host_token's contract. This
    under-matches (falls to different_observed) rather than producing a
    false HIGH-confidence match -- the safe direction, since a mismatch here
    still yields MEDIUM/accepted, never a rejection (see
    _identity_gate: relation only ever raises confidence, never blocks).
    """
    assert probe._identity_relation("fw.01", "fw-01") == "different_observed"


def test_identity_gate_exact_shortname_and_normalized_relations_are_equally_high_confidence():
    """The three matched relations are informational/audit granularity only
    -- _identity_gate treats them identically. Documented finding, not a
    defect: preserves the existing confidence vocabulary per the review's
    correctness contract (AC-4).
    """
    base_kwargs = dict(hostname_success=True, version_success=True, authenticated=True)
    for observed, expected_relation in [
        ("fw-core-01", "exact"),
        ("fw-core-01.corp.example.net", "shortname_match"),
        ("fw_core_01", "normalized_match"),
    ]:
        target = probe.ProbeTarget(
            role="standalone", device="fw-core-01", management_ip="10.0.0.1", object_type="gateway",
        )
        result = probe._identity_gate(target=target, observed_hostname=observed, **base_kwargs)
        assert result["name_relation"] == expected_relation
        assert result["confidence"] == "HIGH"
        assert result["accepted"] is True


def test_identity_gate_fails_closed_without_hostname_or_version_evidence():
    target = probe.ProbeTarget(
        role="standalone",
        device="GW1",
        management_ip="10.0.0.1",
        object_type="gateway",
    )
    result = probe._identity_gate(
        target=target,
        observed_hostname=None,
        hostname_success=False,
        version_success=True,
        authenticated=True,
    )
    assert result["accepted"] is False
    assert result["status"] == "UNVERIFIED"
