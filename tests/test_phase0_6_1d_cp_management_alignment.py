import json
from pathlib import Path

from configuration.checkpoint_config_alignment import align_checkpoint_management_intent
from configuration.checkpoint_config_collector import build_checkpoint_current_configuration
from utils.config_ui import build_configuration_ui_payload
import pytest
from utils.html_export import compose_report_script as _composed_report_script

pytestmark = pytest.mark.configuration


ROOT = Path(__file__).resolve().parents[1]
APP = _composed_report_script()
TEMPLATE = (ROOT / "templates" / "index.html").read_text(encoding="utf-8")


def _actual(
    entity_id="CP-GW-TEST-01",
    *,
    entity_type="standalone_gateway",
    lines=None,
    **extra,
):
    lines = lines or ["set hostname CP-GW-TEST-01", "set dns primary 192.0.2.53"]
    return {
        "entity_id": entity_id,
        "entity_type": entity_type,
        "device": extra.pop("device", entity_id),
        "status": "success",
        "identity_gate": {"accepted": True, "status": "VERIFIED", "confidence": "HIGH"},
        "current_configuration": build_checkpoint_current_configuration(
            lines,
            secret_bearing_line_count=0,
            entity_type=entity_type,
            context_label=extra.get("context_label"),
        ),
        **extra,
    }


def _intent(entities, *, confidence="HIGH"):
    return {
        "schema_version": "0.6.1D",
        "status": "success",
        "source_plane": "checkpoint_management",
        "source_method": "synthetic_normalized_projection",
        "source_confidence": confidence,
        "entities": entities,
    }


def _entity_intent(entity_id="CP-GW-TEST-01", *, entity_type="standalone_gateway", settings=None, **extra):
    return {
        "entity_id": entity_id,
        "entity_type": entity_type,
        "settings": settings or [
            {"key": "system|hostname", "category": "system", "value": "CP-GW-TEST-01"},
            {"key": "dns|primary dns", "category": "dns", "value": "192.0.2.53"},
        ],
        **extra,
    }


def test_trusted_intent_classifies_match_and_difference_without_drift_claim():
    result = align_checkpoint_management_intent(
        {"devices": [_actual()]},
        _intent([
            _entity_intent(settings=[
                {"key": "system|hostname", "category": "system", "value": "CP-GW-TEST-01"},
                {"key": "dns|primary dns", "category": "dns", "value": "198.51.100.53"},
            ])
        ]),
    )

    entity = result["entities"][0]
    assert entity["device_status"] == "DIFFERENCE_OBSERVED"
    assert entity["summary"]["classification_counts"] == {
        "ALIGNED": 1,
        "DIFFERENCE_OBSERVED": 1,
    }
    blob = json.dumps(result)
    assert "EFFECTIVE_DRIFT" not in blob
    assert "LOCAL_OVERRIDE" not in blob
    assert "198.51.100.53" not in blob
    assert "192.0.2.53" not in blob
    assert entity["engine_contract"]["effective_drift_emitted"] is False


def test_cluster_member_difference_stays_member_specific_without_intent():
    row = _actual(
        "CP-CL-TEST-01",
        entity_type="clusterxl_member",
        cluster_group_id="cluster-test-a",
    )
    dns = next(
        setting
        for section in row["current_configuration"]["sections"]
        for setting in section["settings"]
        if setting["setting"] == "Primary DNS"
    )
    dns["member_specific"] = True
    dns["origin"] = "member_specific"

    result = align_checkpoint_management_intent({"devices": [row]})
    entity = result["entities"][0]

    assert entity["device_status"] == "INSUFFICIENT_EVIDENCE"
    assert entity["summary"]["classification_counts"]["MEMBER_SPECIFIC"] == 1
    assert "DIFFERENCE_OBSERVED" not in entity["summary"]["classification_counts"]


def test_untrusted_intent_cannot_create_actionable_difference():
    result = align_checkpoint_management_intent(
        {"devices": [_actual()]},
        _intent([
            _entity_intent(settings=[
                {"key": "dns|primary dns", "category": "dns", "value": "198.51.100.53"},
            ])
        ], confidence="LOW"),
    )

    entity = result["entities"][0]
    assert entity["device_status"] == "PROVENANCE_UNVERIFIED"
    assert set(entity["summary"]["classification_counts"]) == {"PROVENANCE_UNVERIFIED"}


def test_vsx_identity_is_physical_endpoint_plus_numeric_vsid():
    actual = _actual(
        "CP-VSX-TEST-01__vsid_7",
        entity_type="virtual_system",
        device="CP-VSX-TEST-01",
        parent_entity_id="CP-VSX-TEST-01",
        vs_id="7",
        context_label="VSID 7",
        lines=["set hostname CP-VS-TEST-07"],
    )
    intent = _entity_intent(
        "ignored-display-identity",
        entity_type="virtual_system",
        physical_entity_id="CP-VSX-TEST-01",
        vs_id="7",
        settings=[{"key": "system|vsid 7|hostname", "category": "system", "value": "CP-VS-TEST-07"}],
    )

    result = align_checkpoint_management_intent({"devices": [actual]}, _intent([intent]))
    entity = result["entities"][0]

    assert entity["entity_key"] == "virtual_system:CP-VSX-TEST-01:vsid:7"
    assert entity["device_status"] == "ALIGNED"


def test_missing_or_ambiguous_identity_fails_closed():
    actual = _actual(
        "CP-VSX-TEST-01__unknown",
        entity_type="virtual_system",
        device="CP-VSX-TEST-01",
        parent_entity_id="CP-VSX-TEST-01",
        vs_id="not-numeric",
    )
    result = align_checkpoint_management_intent({"devices": [actual]}, _intent([]))

    assert result["entities"][0]["device_status"] == "INSUFFICIENT_EVIDENCE"
    assert result["entities"][0]["reason"] == "virtual_system_physical_identity_or_vsid_unavailable"


def test_sensitive_intent_setting_is_withheld_from_alignment_output():
    sensitive_value = "synthetic-do-not-export"
    intent = _entity_intent(settings=[
        {"key": "authentication|shared-secret", "category": "authentication", "value": sensitive_value},
        {"key": "dns|primary dns", "category": "dns", "value": "192.0.2.53"},
    ])
    result = align_checkpoint_management_intent({"devices": [_actual()]}, _intent([intent]))
    entity = result["entities"][0]
    blob = json.dumps(result)

    assert sensitive_value not in blob
    assert "shared-secret" not in blob
    assert entity["summary"]["sensitive_settings_withheld"] == 1
    assert result["raw_values_included"] is False
    assert result["value_hashes_included"] is False


def test_configuration_ui_exposes_evidence_aware_cp_alignment_without_values_or_hashes():
    cp_result = {
        "summary": {"selected": 1, "success": 1, "failed": 0},
        "devices": [_actual()],
        "management_intent": _intent([
            _entity_intent(settings=[
                {"key": "dns|primary dns", "category": "dns", "value": "198.51.100.53"},
            ])
        ]),
    }
    payload = build_configuration_ui_payload(None, checkpoint_config_result=cp_result)
    device = payload["devices"][0]
    alignment_blob = json.dumps(device["alignment"])

    assert device["alignment_evidence_status"] == "success"
    assert device["alignment"]["device_status"] == "DIFFERENCE_OBSERVED"
    assert device["alignment"]["counts"]["DIFFERENCE_OBSERVED"] == 1
    assert device["alignment"]["findings"][0]["classification"] == "DIFFERENCE_OBSERVED"
    assert "198.51.100.53" not in alignment_blob
    assert "value_sha256" not in alignment_blob
    assert "raw_canonical_sha256" not in alignment_blob
    assert device["alignment"]["raw_values_included"] is False


def test_frontend_replaces_legacy_placeholder_with_bounded_cp_alignment_panel():
    assert "Management intent ↔ direct actual" in APP
    assert "Not an EFFECTIVE_DRIFT claim" in APP
    assert 'value="DIFFERENCE_OBSERVED"' in TEMPLATE
    assert "Not implemented in 0.6.1B.1" not in APP
