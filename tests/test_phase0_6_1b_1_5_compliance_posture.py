import json

from utils.compliance_posture import build_compliance_posture


def _sample_configuration_payload():
    return {
        "available": True,
        "raw_configuration_blob_included": False,
        "privacy": {
            "raw_configuration_blob_included": False,
            "credentials_included": False,
            "secret_values_redacted": True,
        },
        "fleet": {
            "tls_verify": False,
        },
        "devices": [
            {
                "vendor_key": "check_point",
                "connected": True,
                "host_key_policy": "strict_known_hosts",
                "current_configuration": {
                    "status": "available",
                    "sections": [
                        {"id": "system", "settings": [{"setting": "Hostname", "value": "CP-GW-01"}, {"setting": "Timezone", "value": "Europe/Istanbul"}]},
                        {"id": "dns", "settings": [{"setting": "Primary DNS", "value": "203.0.113.53"}, {"setting": "Secondary DNS", "value": "203.0.113.54"}]},
                        {"id": "ntp", "settings": [{"setting": "Primary NTP Server", "value": "203.0.113.10"}]},
                        {"id": "authentication", "settings": [{"setting": "AAA Type", "value": "radius"}]},
                        {"id": "management", "settings": [{"setting": "Telnet Disabled", "value": "yes"}, {"setting": "HTTPS Port", "value": "443"}, {"setting": "Inactivity Timeout", "value": "10"}]},
                        {"id": "snmp", "settings": [{"setting": "SNMP Version", "value": "v3"}]},
                        {"id": "logging", "settings": [{"setting": "Logging Audit", "value": "enabled"}]},
                    ],
                },
                "alignment": {"counts": {}},
            },
            {
                "vendor_key": "palo_alto",
                "connected": True,
                "current_configuration": {
                    "status": "available",
                    "sections": [
                        {"id": "system", "settings": [{"setting": "Hostname", "value": "PAN-FW-01"}, {"setting": "Update Server Verify Identity", "value": "enabled"}]},
                        {"id": "dns", "settings": [{"setting": "Primary DNS", "value": "203.0.113.60"}, {"setting": "Secondary DNS", "value": "203.0.113.61"}]},
                        {"id": "management", "settings": [{"setting": "Permitted IP / Network", "value": "198.51.100.0/24"}]},
                        {"id": "ntp", "settings": [{"setting": "Primary NTP Server", "value": "203.0.113.20"}, {"setting": "Secondary NTP Server", "value": "203.0.113.21"}]},
                        {"id": "authentication", "settings": [{"setting": "Authentication Profile", "value": "ldap"}]},
                        {"id": "snmp", "settings": [{"setting": "SNMP Polling", "value": "version 3"}]},
                        {"id": "logging", "settings": [{"setting": "Audit Logging", "value": "enabled"}]},
                    ],
                },
                "alignment": {
                    "counts": {
                        "LOCAL_OVERRIDE": 1,
                    }
                },
            },
            {
                "vendor_key": "palo_alto",
                "connected": True,
                "current_configuration": {
                    "status": "available",
                    "sections": [
                        {"id": "system", "settings": [{"setting": "Hostname", "value": "unused"}]},
                    ],
                },
                "alignment": {
                    "counts": {
                        "UNKNOWN": 2,
                    }
                },
            },
            {
                "vendor_key": "check_point",
                "connected": False,
                "host_key_policy": "observe_and_record_not_production",
                "current_configuration": {
                    "status": "unavailable",
                    "sections": [],
                },
                "alignment": {"counts": {}},
            },
        ],
    }


def _sample_project_plan_payload():
    return {
        "tracks": [
            {
                "features": [
                    {
                        "id": "compliance_engine",
                        "title": "Compliance Rule Engine",
                        "status": "planned",
                        "target": "0.7.x",
                    },
                    {
                        "id": "framework_mappings",
                        "title": "Framework Mappings",
                        "status": "planned",
                        "target": "0.7.x",
                    },
                    {
                        "id": "evidence_reporting",
                        "title": "Evidence Reports",
                        "status": "planned",
                        "target": "0.7.x",
                    },
                    {
                        "id": "cp_management_alignment",
                        "title": "Check Point Management Intent Alignment",
                        "status": "planned",
                        "target": "0.6.1D",
                    },
                    {
                        "id": "crypto_agility_pqc",
                        "title": "Cryptographic Posture, Crypto-Agility & PQC Readiness",
                        "status": "planned",
                        "target": "0.7.x",
                    },
                ]
            }
        ],
        "backlog": [
            {
                "id": "crypto_agility_pqc",
                "title": "Cryptographic Posture, Crypto-Agility & PQC Readiness",
                "status": "planned",
                "target": "0.7.x",
            }
        ],
    }


def test_compliance_posture_foundation_emits_all_required_statuses_and_links():
    payload = build_compliance_posture(_sample_configuration_payload(), _sample_project_plan_payload())

    assert payload["available"] is True
    assert payload["schema_version"] == "0.6.1B.1.6"
    assert payload["classification"] == "evidence_backed_control_area"

    statuses = payload["fleet"]["status_counts"]
    assert statuses["PASS"] > 0
    assert statuses["FINDING"] > 0
    assert statuses["PLANNED"] > 0
    assert payload["fleet"]["unavailable_subjects"] > 0

    subject_ids = {subject["subject_id"] for subject in payload["subjects"]}
    assert any(subject_id.startswith("cp-") for subject_id in subject_ids)
    assert any(subject_id.startswith("pan-") for subject_id in subject_ids)

    all_controls = [control for subject in payload["subjects"] for control in subject["controls"]]
    unique_subject_control_ids = {control["control_id"] for control in all_controls}
    assert len(unique_subject_control_ids) == 10

    planned_subject_ids = {
        control["control_id"]
        for control in all_controls
        if control["status"] == "PLANNED"
    }
    assert planned_subject_ids == set()

    implemented_subject = next(control for control in all_controls if control["control_id"] == "hostname_configured_non_default")
    assert implemented_subject["control_lifecycle"] == "IMPLEMENTED"
    assert implemented_subject["scope"] == "SUBJECT"
    assert implemented_subject["benchmark"] == "CIS"
    assert implemented_subject["benchmark_reference"]
    assert implemented_subject["evidence_fields"]

    snmp_subject = next(control for control in all_controls if control["control_id"] == "snmp_v3_only")
    assert snmp_subject["control_lifecycle"] == "IMPLEMENTED"
    assert snmp_subject["scope"] == "SUBJECT"

    fleet_and_platform = list(payload.get("fleet_controls", [])) + list(payload.get("platform_controls", []))
    cp_alignment = next(control for control in fleet_and_platform if control["control_id"] == "cp_management_actual_alignment")
    assert any(link["feature_id"] == "cp_management_alignment" for link in cp_alignment["roadmap_links"])
    assert any(link["feature_id"] == "compliance_engine" for link in cp_alignment["roadmap_links"])

    # Subject controls should remain device-specific; global/planned controls stay in fleet area.
    forbidden_subject_ids = {
        "cp_management_actual_alignment",
        "crypto_agility_pqc_readiness",
        "pan_collector_tls_trust",
        "evidence_privacy_boundary",
    }
    subject_control_ids = {control["control_id"] for control in all_controls}
    assert forbidden_subject_ids.isdisjoint(subject_control_ids)


def test_unreachable_subject_is_not_reported_as_unknown_compliance_posture():
    payload = build_compliance_posture(_sample_configuration_payload(), _sample_project_plan_payload())

    unavailable = next(subject for subject in payload["subjects"] if subject["availability"] == "UNAVAILABLE")
    assert unavailable["status"] == "UNAVAILABLE"
    assert unavailable["controls"] == []
    assert unavailable["availability_reason"]
    assert payload["fleet"]["unavailable_subjects"] == 1
    assert payload["fleet"]["evaluated_subjects"] == 3


def test_compliance_payload_never_contains_sensitive_identity_or_raw_config_values():
    payload = build_compliance_posture(_sample_configuration_payload(), _sample_project_plan_payload())
    encoded = json.dumps(payload)

    assert payload["privacy"]["contains_secrets"] is False
    assert payload["privacy"]["contains_raw_configuration"] is False
    assert payload["privacy"]["contains_device_identity"] is False
    assert payload["privacy"]["contains_network_identity"] is False

    # Source payload contained RFC-range values and no real identity. Compliance
    # payload must not copy those values.
    assert "203.0.113.10" not in encoded
    assert "198.51.100.0/24" not in encoded

    forbidden_tokens = [
        "management_ip",
        "serial",
        "raw_configuration_blob",
        "raw-canonical",
        "raw-canonical-sha256",
        "super_secret_do_not_embed",
        "private_key",
    ]
    lowered = encoded.lower()
    for token in forbidden_tokens:
        assert token not in lowered


def test_compliance_posture_unavailable_contract_is_explicit():
    payload = build_compliance_posture(None, _sample_project_plan_payload())
    assert payload["available"] is False
    assert payload["subjects"] == []
    assert payload["fleet"]["subjects"] == 0
