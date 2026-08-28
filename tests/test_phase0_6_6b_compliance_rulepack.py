"""0.6.6B — Compliance Rule-Pack Transition Foundation.

The ten deterministic CP/PAN controls now execute through a static, versioned,
in-repository rule pack. This proves AC-1..AC-6 from
docs/history/phase/PHASE0_6_6B_COMPLIANCE_RULE_PACK_TRANSITION.md: pack identity
and version, stable control IDs with unchanged outcomes, per-control
traceability, no inferred PASS on missing evidence, additive/backward-compatible
payload, and no certification claim.
"""
import json

from utils.compliance_posture import (
    _evaluate_vendor_neutral_control,
    build_compliance_posture,
)
from utils.compliance_rulepack import (
    BASELINE_CONTROLS,
    DEFAULT_RULE_PACK,
    DEFAULT_RULE_PACK_ID,
    DEFAULT_RULE_PACK_VERSION,
    rule_pack_summary,
)

_BASELINE_IDS = {c["control_id"] for c in BASELINE_CONTROLS}


def _device(vendor_key, sections, *, connected=True, status="available"):
    return {
        "vendor_key": vendor_key,
        "connected": connected,
        "current_configuration": {"status": status, "sections": sections},
        "alignment": {"counts": {}},
    }


def _configuration_payload(devices):
    return {
        "available": True,
        "raw_configuration_blob_included": False,
        "privacy": {
            "raw_configuration_blob_included": False,
            "credentials_included": False,
            "secret_values_redacted": True,
        },
        "fleet": {"tls_verify": True},
        "devices": devices,
    }


_FULL_CP = _device("check_point", [
    {"id": "system", "settings": [{"setting": "Hostname", "value": "CP-GW-77"}]},
    {"id": "dns", "settings": [{"setting": "Primary DNS", "value": "a"}, {"setting": "Secondary DNS", "value": "b"}]},
    {"id": "ntp", "settings": [{"setting": "Primary NTP Server", "value": "a"}, {"setting": "Secondary NTP Server", "value": "b"}]},
    {"id": "authentication", "settings": [{"setting": "AAA Type", "value": "radius"}]},
    {"id": "management", "settings": [{"setting": "Telnet Disabled", "value": "yes"}, {"setting": "HTTPS Port", "value": "443"}, {"setting": "Inactivity Timeout", "value": "5"}]},
    {"id": "snmp", "settings": [{"setting": "SNMP Version", "value": "v3"}]},
    {"id": "logging", "settings": [{"setting": "Logging Audit", "value": "enabled"}]},
])

# Only a bare system section: every other control must be UNKNOWN/PLANNED, none PASS.
_SPARSE_PAN = _device("palo_alto", [
    {"id": "system", "settings": [{"setting": "Hostname", "value": ""}]},
])


# --- AC-1: static pack, immutable identity + version -----------------------

def test_ac1_default_pack_identity_and_version():
    pack = DEFAULT_RULE_PACK
    assert pack["pack_id"] == DEFAULT_RULE_PACK_ID == "securityexpert.baseline.cp-pan"
    assert pack["pack_version"] == DEFAULT_RULE_PACK_VERSION == "0.6.6B"
    assert pack["schema_version"] == "1.0"
    assert pack["source"] == "in_repository_static"
    assert pack["certification_claim"] is False
    assert isinstance(pack["rules"], tuple)  # frozen sequence
    assert len(pack["rules"]) == 10

    for rule in pack["rules"]:
        assert rule["control_id"] in _BASELINE_IDS
        assert rule["rule_id"] == f"{DEFAULT_RULE_PACK_ID}::{rule['control_id']}"
        assert rule["benchmark"] == "CIS"
        assert rule["benchmark_reference"]
        assert rule["evidence_fields"]
        assert rule["applicability"] == {"vendors": ["check_point", "palo_alto"], "scope": "SUBJECT"}
        assert rule["evaluator"] == rule["control_id"]

    assert {r["control_id"] for r in pack["rules"]} == _BASELINE_IDS
    assert rule_pack_summary()["rule_count"] == 10


# --- AC-2: ten controls through the boundary, stable IDs + equal outcomes --

def test_ac2_routing_through_pack_does_not_change_outcomes():
    payload = build_compliance_posture(_configuration_payload([_FULL_CP, _SPARSE_PAN]), None)
    baseline_by_id = {c["control_id"]: c for c in BASELINE_CONTROLS}

    for subject, source in zip(payload["subjects"], [_FULL_CP, _SPARSE_PAN]):
        control_ids = [c["control_id"] for c in subject["controls"]]
        # AC-2: exactly the ten, in pack order, stable IDs.
        assert control_ids == [r["control_id"] for r in DEFAULT_RULE_PACK["rules"]]
        assert set(control_ids) == _BASELINE_IDS
        # AC-2: routing changes nothing — same status the raw evaluator returns
        # for the same baseline control definition.
        for control in subject["controls"]:
            direct = _evaluate_vendor_neutral_control(source, baseline_by_id[control["control_id"]])
            assert control["status"] == direct["status"]


def test_ac2_outcome_snapshot_is_frozen():
    payload = build_compliance_posture(_configuration_payload([_FULL_CP, _SPARSE_PAN]), None)
    got = {
        (s["subject_id"], c["control_id"]): c["status"]
        for s in payload["subjects"]
        for c in s["controls"]
    }
    expected_full_cp = {
        "hostname_configured_non_default": "PASS",
        "dns_primary_secondary_configured": "PASS",
        "ntp_primary_secondary_configured": "PASS",
        "aaa_provider_presence": "PASS",
        "management_session_timeout_policy": "PASS",
        "telnet_disabled": "PASS",
        "http_management_restricted": "PASS",
        "snmp_v3_only": "PASS",
        "update_server_identity_verified": "UNKNOWN",
        "management_audit_logging": "PASS",
    }
    for cid, status in expected_full_cp.items():
        assert got[("cp-001", cid)] == status


# --- AC-3: per-control traceability --------------------------------------

def test_ac3_every_subject_control_carries_pack_and_benchmark_traceability():
    payload = build_compliance_posture(_configuration_payload([_FULL_CP]), None)
    controls = payload["subjects"][0]["controls"]
    assert len(controls) == 10
    for control in controls:
        rp = control["rule_pack"]
        assert rp["pack_id"] == DEFAULT_RULE_PACK_ID
        assert rp["pack_version"] == DEFAULT_RULE_PACK_VERSION
        assert rp["rule_id"] == f"{DEFAULT_RULE_PACK_ID}::{control['control_id']}"
        assert control["benchmark"] == "CIS"
        assert control["benchmark_reference"]
        assert control["evidence_fields"]
        assert control["applicable_vendors"] == ["check_point", "palo_alto"]


# --- AC-4: missing evidence never becomes an inferred PASS ---------------

def test_ac4_sparse_evidence_never_infers_pass():
    payload = build_compliance_posture(_configuration_payload([_SPARSE_PAN]), None)
    controls = payload["subjects"][0]["controls"]
    statuses = {c["status"] for c in controls}
    assert "PASS" not in statuses
    assert statuses <= {"UNKNOWN", "PLANNED", "FINDING"}
    # hostname present but empty -> FINDING (observed but not a real value), the
    # rest with no section -> UNKNOWN. No guessed PASS anywhere.
    assert all(c["status"] in {"UNKNOWN", "PLANNED", "FINDING"} for c in controls)


# --- AC-5: additive, backward-compatible payload ------------------------

_PRIOR_TOP_LEVEL_KEYS = {
    "schema_version", "available", "classification", "disclaimer",
    "fleet", "fleet_controls", "platform_controls", "subjects", "privacy",
}


def test_ac5_payload_shape_is_additive_only():
    available = build_compliance_posture(_configuration_payload([_FULL_CP]), None)
    unavailable = build_compliance_posture(None, None)

    # 0.6.6B added `rule_pack`; 0.7.1b adds `compliance_overview` and
    # `assignment_policy` (both additive, counts-only). The prior keys and their
    # types are unchanged.
    _ADDITIVE_KEYS = {"rule_pack", "compliance_overview", "assignment_policy"}
    for payload in (available, unavailable):
        assert _PRIOR_TOP_LEVEL_KEYS <= set(payload)
        assert set(payload) - _PRIOR_TOP_LEVEL_KEYS == _ADDITIVE_KEYS
        assert payload["rule_pack"]["pack_id"] == DEFAULT_RULE_PACK_ID

    # platform / fleet posture controls are not pack rules.
    for control in available["platform_controls"] + available["fleet_controls"]:
        assert control["rule_pack"] is None


# --- AC-6: no certification claim, no leaked evidence -------------------

def test_ac6_no_certification_claim_or_sensitive_leak():
    payload = build_compliance_posture(_configuration_payload([_FULL_CP, _SPARSE_PAN]), None)
    # The pack explicitly disclaims certification; it never asserts one.
    assert payload["rule_pack"]["certification_claim"] is False
    disclaimer = payload["rule_pack"]["disclaimer"].lower()
    assert "not a certification" in disclaimer
    assert "attestation" in disclaimer  # used only to deny it

    lowered = json.dumps(payload).lower()
    # No positive certification / attestation claim anywhere in the payload.
    for phrase in (
        "is certified",
        "certified compliant",
        "certification granted",
        "compliance attestation",
        "attests that",
        "is compliant with",
    ):
        assert phrase not in lowered

    assert payload["privacy"]["contains_secrets"] is False
    assert payload["privacy"]["contains_raw_configuration"] is False
