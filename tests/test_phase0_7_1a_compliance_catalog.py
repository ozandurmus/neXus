"""0.7.1a — Compliance control catalog + framework grouping + severity.

The ten controls move into a versioned declarative catalog verbatim (same ids,
areas, evidence_fields, evaluators, outcomes) with `severity`, `rationale` and
real per-framework CIS/PCI-DSS/BDDK membership added. Purely additive to the
compliance payload.
"""
import json

from utils.compliance_catalog import (
    CATALOG_VERSION,
    CONTROL_CATALOG,
    SEVERITY_VALUES,
    catalog_baseline_controls,
    catalog_entry,
    severity_weight,
)
from utils.compliance_posture import build_compliance_posture
from utils.compliance_rulepack import BASELINE_CONTROLS, DEFAULT_RULE_PACK

from test_phase0_6_1b_1_5_compliance_posture import (
    _sample_configuration_payload,
    _sample_project_plan_payload,
)

_LEGACY_IDS = {
    "hostname_configured_non_default", "dns_primary_secondary_configured",
    "ntp_primary_secondary_configured", "aaa_provider_presence",
    "management_session_timeout_policy", "telnet_disabled",
    "http_management_restricted", "snmp_v3_only",
    "update_server_identity_verified", "management_audit_logging",
}


def test_catalog_schema_and_membership():
    assert CATALOG_VERSION == "0.7.1a"
    assert {c["id"] for c in CONTROL_CATALOG} == _LEGACY_IDS
    for c in CONTROL_CATALOG:
        assert c["severity"] in SEVERITY_VALUES
        assert c["rationale"].strip()
        assert c["lifecycle"] in ("active", "planned_evidence_gap", "deprecated")
        assert c["evaluator"] == c["id"]
        assert c["evidence"]["fields"]
        fw = {f["framework"] for f in c["frameworks"]}
        assert fw == {"CIS", "PCI-DSS", "BDDK"}
        for f in c["frameworks"]:
            assert isinstance(f["applies"], bool)
            assert f["reference"].strip()


def test_severity_weight_scale():
    assert [severity_weight(s) for s in SEVERITY_VALUES] == [1, 2, 3, 4, 5]
    assert severity_weight("bogus") == 1


def test_baseline_view_is_verbatim_and_drives_the_pack():
    view = catalog_baseline_controls()
    assert len(view) == 10
    assert view == BASELINE_CONTROLS  # rule pack derives from the same view
    assert len(DEFAULT_RULE_PACK["rules"]) == 10
    for row in view:
        assert set(row) == {"control_id", "title", "control_area", "cis_reference", "evidence_fields"}
    by_id = {r["control_id"]: r for r in view}
    assert by_id["hostname_configured_non_default"]["cis_reference"] == "CIS 2.1.8"
    assert by_id["management_audit_logging"]["cis_reference"] == "CIS 2.6.1 / 2.6.2"
    assert by_id["update_server_identity_verified"]["cis_reference"] == "PanOS Verify Update Server Identity"


def test_subject_controls_unchanged_outcomes_plus_additive_metadata():
    payload = build_compliance_posture(_sample_configuration_payload(), _sample_project_plan_payload())
    all_controls = [c for s in payload["subjects"] for c in s["controls"]]
    assert {c["control_id"] for c in all_controls} == _LEGACY_IDS  # still exactly the ten

    for c in all_controls:
        entry = catalog_entry(c["control_id"])
        assert c["severity"] == entry["severity"]
        assert c["rationale"] == entry["rationale"]
        assert c["frameworks"] and {f["framework"] for f in c["frameworks"]} == {"CIS", "PCI-DSS", "BDDK"}
        # 0.6.6B keys preserved
        assert c["framework_mappings"] and "cis" in c["framework_mappings"]
        assert c["rule_pack"]["pack_id"] == "securityexpert.baseline.cp-pan"


def test_platform_and_fleet_controls_have_null_catalog_metadata():
    payload = build_compliance_posture(_sample_configuration_payload(), _sample_project_plan_payload())
    for c in payload["fleet_controls"] + payload["platform_controls"]:
        assert c["severity"] is None
        assert c["frameworks"] == []
        assert c["rationale"] is None


def test_payload_carries_no_certification_claim_or_leak():
    payload = build_compliance_posture(_sample_configuration_payload(), _sample_project_plan_payload())
    lowered = json.dumps(payload).lower()
    for phrase in ("is certified", "certified compliant", "compliance attestation", "attests that"):
        assert phrase not in lowered
