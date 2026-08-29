"""0.7.1b — enrichment controls, file-based assignment, waivers, coverage roll-up.

AC-1..AC-7 of docs/builds/0_7_1_COMPLIANCE_ASSIGNMENT.md (§2c-2e). No network,
no server, no real-environment gate.
"""
import json

import pytest

from utils.compliance_catalog import (
    CATALOG_VERSION,
    all_subject_control_ids,
    catalog_baseline_controls,
    catalog_enrichment_controls,
)
from utils.compliance_posture import build_compliance_posture
from utils.control_assignment import (
    ControlAssignmentPolicyError,
    load_control_assignments,
)

from test_phase0_6_1b_1_5_compliance_posture import _sample_project_plan_payload


_PRIOR_KEYS = {
    "schema_version", "available", "classification", "disclaimer", "rule_pack",
    "fleet", "fleet_controls", "platform_controls", "subjects", "privacy",
}
_ADDITIVE_KEYS = {"compliance_overview", "assignment_policy", "check_packs"}

_DEVICE_NAME_CP = "corp-cp-gw-01"
_DEVICE_NAME_PAN = "corp-pan-fw-01"


def _cp_sections():
    return [
        {"id": "system", "settings": [{"setting": "Hostname", "value": "CP-GW-01"}, {"setting": "Timezone", "value": "Europe/Istanbul"}, {"setting": "Login Banner", "value": "Authorized use only"}]},
        {"id": "dns", "settings": [{"setting": "Primary DNS", "value": "203.0.113.53"}, {"setting": "Secondary DNS", "value": "203.0.113.54"}, {"setting": "Search Domain", "value": "corp.example"}]},
        {"id": "ntp", "settings": [{"setting": "Primary NTP Server", "value": "203.0.113.10"}, {"setting": "Secondary NTP Server", "value": "203.0.113.11"}, {"setting": "NTP Authentication Key", "value": "7"}]},
        {"id": "authentication", "settings": [{"setting": "AAA Type", "value": "radius"}, {"setting": "Failed Attempts Lockout", "value": "5"}]},
        {"id": "management", "settings": [{"setting": "Telnet Disabled", "value": "yes"}, {"setting": "HTTPS Port", "value": "443"}, {"setting": "Inactivity Timeout", "value": "10"}, {"setting": "SSH Version", "value": "v2"}]},
        {"id": "snmp", "settings": [{"setting": "SNMP Version", "value": "v3"}, {"setting": "SNMP Community", "value": "s3cure-ro"}]},
        {"id": "logging", "settings": [{"setting": "Logging Audit", "value": "enabled"}, {"setting": "Syslog Server", "value": "203.0.113.90"}]},
    ]


def _configuration_payload(*, cp_name=_DEVICE_NAME_CP, pan_name=_DEVICE_NAME_PAN):
    return {
        "available": True,
        "raw_configuration_blob_included": False,
        "privacy": {
            "raw_configuration_blob_included": False,
            "credentials_included": False,
            "secret_values_redacted": True,
        },
        "fleet": {"tls_verify": True},
        "devices": [
            {
                "vendor_key": "check_point",
                "name": cp_name,
                "serial": "SN-CP-REDACTED",
                "management_ip": "192.0.2.10",
                "connected": True,
                "host_key_policy": "strict_known_hosts",
                "current_configuration": {"status": "available", "sections": _cp_sections()},
                "alignment": {"counts": {}},
            },
            {
                "vendor_key": "palo_alto",
                "name": pan_name,
                "serial": "SN-PAN-REDACTED",
                "management_ip": "192.0.2.20",
                "connected": True,
                "current_configuration": {
                    "status": "available",
                    "sections": [
                        {"id": "system", "settings": [{"setting": "Hostname", "value": "PAN-FW-01"}]},
                    ],
                },
                "alignment": {"counts": {}},
            },
        ],
    }


def _write_policy(tmp_path, body):
    state = tmp_path / "state"
    state.mkdir(parents=True, exist_ok=True)
    (state / "control_assignments.json").write_text(json.dumps(body), encoding="utf-8")
    return tmp_path


# --- AC-1: catalog / enrichment shape -------------------------------------

def test_ac1_catalog_shape_and_derived_views():
    assert CATALOG_VERSION == "0.7.2"
    assert len(catalog_baseline_controls()) == 10          # frozen 0.6.6B view
    enrichment = catalog_enrichment_controls()
    assert len(enrichment) >= 6
    baseline_ids = {c["control_id"] for c in catalog_baseline_controls()}
    for c in enrichment:
        assert c["control_id"] not in baseline_ids
        assert c["severity"] in ("informational", "low", "medium", "high", "critical")
        assert {f["framework"] for f in c["frameworks"]} == {"CIS", "PCI-DSS", "BDDK"}
        assert c["evaluator"] == c["control_id"]
    assert all_subject_control_ids() == baseline_ids | {c["control_id"] for c in enrichment}


# --- AC-2: no policy → additive-only, baseline outcomes unchanged --------

def test_ac2_no_policy_is_additive_only():
    payload = build_compliance_posture(_configuration_payload(), _sample_project_plan_payload())
    assert set(payload) - _PRIOR_KEYS == _ADDITIVE_KEYS
    assert payload["assignment_policy"]["active"] is False
    assert payload["assignment_policy"]["source"] == "missing"

    cp_subject = next(s for s in payload["subjects"] if s["vendor_key"] == "check_point")
    assert {c["control_id"] for c in cp_subject["controls"]} == {
        c["control_id"] for c in catalog_baseline_controls()
    }
    assert len(cp_subject["controls"]) == 10
    # enrichment lives in its own list
    assert cp_subject["extended_controls"]
    assert all(c["control_class"] == "enrichment" for c in cp_subject["extended_controls"])
    # rich CP fixture: deterministic enrichment PASSes exist, none inferred on gaps
    ext_status = {c["control_id"]: c["status"] for c in cp_subject["extended_controls"]}
    assert ext_status["timezone_configured"] == "PASS"
    assert ext_status["remote_syslog_configured"] == "PASS"
    pan_subject = next(s for s in payload["subjects"] if s["vendor_key"] == "palo_alto")
    assert "PASS" not in {c["status"] for c in pan_subject["extended_controls"]}


# --- AC-3: assignment resolution + fail-closed --------------------------

def test_ac3_include_exclude_wildcard_and_precedence(tmp_path):
    body = {
        "version": 1,
        "default_mode": "none",
        "groups": {"pci-scope": {"match": [{"vendor": "check_point"}]}},
        "assignments": [
            {"target": {"group": "pci-scope"}, "include": ["*"]},
            {"target": {"device_name": _DEVICE_NAME_CP}, "exclude": ["telnet_disabled"]},
        ],
    }
    _write_policy(tmp_path, body)
    payload = build_compliance_posture(_configuration_payload(), None, data_root=tmp_path)
    cp = next(s for s in payload["subjects"] if s["vendor_key"] == "check_point")
    pan = next(s for s in payload["subjects"] if s["vendor_key"] == "palo_alto")

    cp_ids = {c["control_id"] for c in cp["controls"]}
    assert "telnet_disabled" not in cp_ids           # device exclude wins over group include
    assert "snmp_v3_only" in cp_ids                   # group include "*"
    assert "telnet_disabled" in cp["assignment"]["not_assigned"]
    assert pan["assignment"]["assigned"] == []        # default_mode none, no match
    assert pan["controls"] == []


def test_ac3_missing_file_is_all_applicable(tmp_path):
    payload = build_compliance_posture(_configuration_payload(), None, data_root=tmp_path)
    cp = next(s for s in payload["subjects"] if s["vendor_key"] == "check_point")
    assert len(cp["controls"]) == 10
    assert cp["assignment"]["not_assigned"] == []


@pytest.mark.parametrize("body", [
    {"version": 2},
    {"version": 1, "default_mode": "bogus"},
    {"version": 1, "assignments": [{"target": {"device_name": "x"}, "include": ["no_such_control"]}]},
    {"version": 1, "assignments": [{"target": {"group": "ghost"}, "include": ["*"]}]},
    {"version": 1, "waivers": [{"control_id": "no_such_control", "reason": "r", "approver": "a"}]},
])
def test_ac3_malformed_policy_fails_closed(tmp_path, body):
    _write_policy(tmp_path, body)
    with pytest.raises(ControlAssignmentPolicyError):
        load_control_assignments(tmp_path)


# --- AC-4: waivers -----------------------------------------------------

def test_ac4_active_waiver_becomes_waived_not_pass(tmp_path):
    body = {
        "version": 1,
        "waivers": [
            {"control_id": "snmp_v3_only", "device_name": _DEVICE_NAME_CP,
             "reason": "legacy NMS - risk accepted", "approver": "netsec-lead", "expires": "2099-12-31"},
            {"control_id": "telnet_disabled", "device_name": _DEVICE_NAME_CP,
             "reason": "expired waiver", "approver": "x", "expires": "2000-01-01"},
        ],
    }
    _write_policy(tmp_path, body)
    payload = build_compliance_posture(_configuration_payload(), None, data_root=tmp_path)
    cp = next(s for s in payload["subjects"] if s["vendor_key"] == "check_point")
    by_id = {c["control_id"]: c for c in cp["controls"]}
    assert by_id["snmp_v3_only"]["status"] == "WAIVED"
    assert by_id["snmp_v3_only"]["pre_waiver_status"] in ("PASS", "FINDING", "UNKNOWN")
    assert by_id["telnet_disabled"]["status"] != "WAIVED"      # expired → ignored
    assert cp["assignment"]["waived"] == ["snmp_v3_only"]
    assert payload["compliance_overview"]["cells"]["waived"] >= 1
    cp_row = next(r for r in payload["compliance_overview"]["by_subject"] if r["subject_id"] == cp["subject_id"])
    assert cp_row["waived"] >= 1


# --- AC-5: compliance_overview math ----------------------------------

def test_ac5_overview_math_reconciles(tmp_path):
    payload = build_compliance_posture(_configuration_payload(), None, data_root=tmp_path)
    ov = payload["compliance_overview"]
    assert ov["catalog_version"] == CATALOG_VERSION
    assert ov["monitored_controls"] + ov["unmonitored_controls"] == ov["total_controls"]
    assert 0.0 <= ov["aligned_percent"] <= 100.0
    assert 0.0 <= ov["risk_weighted_alignment_percent"] <= 100.0
    for fw in ov["by_framework"].values():
        assert fw["coverage"] in ("COVERED", "PARTIALLY_COVERED", "UNCOVERED")
        assert fw["monitored"] <= fw["controls"]
    # by_subject cell sums reconcile with the fleet cells block
    agg = {"aligned": 0, "finding": 0, "unknown": 0, "planned": 0, "waived": 0}
    for row in ov["by_subject"]:
        for k in agg:
            agg[k] += row[k]
    assert agg == ov["cells"]


# --- AC-6: empty path still additive --------------------------------

def test_ac6_unavailable_payload_carries_overview_and_policy():
    payload = build_compliance_posture(None, None)
    assert payload["available"] is False
    assert payload["compliance_overview"]["total_controls"] == len(all_subject_control_ids())
    assert payload["compliance_overview"]["cells"] == {
        "aligned": 0, "finding": 0, "unknown": 0, "planned": 0, "waived": 0
    }
    assert payload["assignment_policy"]["active"] is False


# --- AC-7: no identity leak even with a device-naming policy --------

def test_ac7_policy_device_names_never_reach_the_payload(tmp_path):
    body = {
        "version": 1,
        "groups": {"scope": {"match": [{"device_name": _DEVICE_NAME_CP}]}},
        "assignments": [{"target": {"group": "scope"}, "exclude": ["telnet_disabled"]}],
        "waivers": [{"control_id": "snmp_v3_only", "device_name": _DEVICE_NAME_PAN,
                     "reason": "x", "approver": "y", "expires": "2099-01-01"}],
    }
    _write_policy(tmp_path, body)
    payload = build_compliance_posture(_configuration_payload(), None, data_root=tmp_path)
    encoded = json.dumps(payload)
    for token in (_DEVICE_NAME_CP, _DEVICE_NAME_PAN, "192.0.2.10", "192.0.2.20",
                  "SN-CP-REDACTED", "netsec-lead", "risk accepted"):
        assert token not in encoded
    assert payload["privacy"]["contains_device_identity"] is False
