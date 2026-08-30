import json

from configuration.checkpoint_alignment_validation import (
    evaluate_checkpoint_alignment_real_env_gate,
)
from configuration.checkpoint_config_collector import build_checkpoint_current_configuration
import pytest

pytestmark = pytest.mark.configuration


def _actual(entity_id, entity_type, *, value="CP-GW-TEST-01", **extra):
    context_label = extra.get("context_label")
    return {
        "entity_id": entity_id,
        "entity_type": entity_type,
        "device": extra.pop("device", entity_id),
        "status": "success",
        "identity_gate": {"accepted": True, "status": "VERIFIED", "confidence": "HIGH"},
        "current_configuration": build_checkpoint_current_configuration(
            [f"set hostname {value}"],
            secret_bearing_line_count=0,
            entity_type=entity_type,
            context_label=context_label,
        ),
        **extra,
    }


def _intent_entity(entity_id, entity_type, *, value="CP-GW-TEST-01", **extra):
    context = f"vsid {extra['vs_id']}|" if entity_type == "virtual_system" else ""
    return {
        "entity_id": entity_id,
        "entity_type": entity_type,
        "settings": [
            {
                "key": f"system|{context}hostname",
                "category": "system",
                "value": value,
            }
        ],
        **extra,
    }


def _representative_payload():
    devices = [
        _actual("CP-GW-TEST-01", "standalone_gateway"),
        _actual("CP-CL-TEST-01", "clusterxl_member", cluster_group_id="cluster-test-a"),
        _actual("CP-CL-TEST-02", "clusterxl_member", cluster_group_id="cluster-test-a"),
        _actual(
            "CP-VSX-TEST-01__vsid_7",
            "virtual_system",
            value="CP-VS-TEST-07",
            device="CP-VSX-TEST-01",
            parent_entity_id="CP-VSX-TEST-01",
            vs_id="7",
            context_label="VSID 7",
        ),
    ]
    entities = [
        _intent_entity("CP-GW-TEST-01", "standalone_gateway"),
        _intent_entity("CP-CL-TEST-01", "clusterxl_member"),
        _intent_entity("CP-CL-TEST-02", "clusterxl_member"),
        _intent_entity(
            "display-label-not-identity",
            "virtual_system",
            value="CP-VS-TEST-07",
            physical_entity_id="CP-VSX-TEST-01",
            vs_id="7",
        ),
    ]
    return {
        "devices": devices,
        "management_intent": {
            "schema_version": "0.6.1D",
            "status": "success",
            "source_plane": "checkpoint_management",
            "source_method": "approved_normalized_projection_v1",
            "source_confidence": "HIGH",
            "entities": entities,
        },
    }


def _source_approval(**overrides):
    approval = {
        "decision": "approved",
        "source_method": "approved_normalized_projection_v1",
        "source_owner_role": "management-evidence-owner",
        "approved_by_role": "security-architecture-reviewer",
        "approved_on": "2026-08-26",
        "introduces_network_change": False,
        "controls": {
            "read_only": True,
            "versioned_projection": True,
            "provenance_preserved": True,
            "identity_mapping_verified": True,
            "collection_status_verified": True,
            "secret_output_reviewed": True,
            "sanitization_verified": True,
        },
    }
    approval.update(overrides)
    return approval


def _attestation(**overrides):
    attestation = {
        "status": "accepted",
        "evidence_origin": "real_environment",
        "evidence_class": "CLASS_1_SANITIZED",
        "representative_scope_confirmed": True,
        "privacy_review_passed": True,
        "reviewed_by_role": "network-security-validator",
        "validated_on": "2026-08-26",
    }
    attestation.update(overrides)
    return attestation


def test_representative_trusted_and_attested_gate_passes_value_free():
    report = evaluate_checkpoint_alignment_real_env_gate(
        _representative_payload(),
        _source_approval(),
        _attestation(),
    )

    assert report["status"] == "passed"
    assert report["promotion_status"] == "REAL_ENV_VALIDATED"
    assert report["main_merge"] == "approved"
    assert report["failed_checks"] == []
    assert report["gate_calculation"] == {
        "rule": "all_required_checks_must_pass",
        "required_checks": 35,
        "passed_checks": 35,
        "failed_checks": 0,
        "pass_rate_percent": 100.0,
    }
    assert report["summary"]["entity_type_coverage"] == {
        "clusterxl_member": 2,
        "standalone_gateway": 1,
        "virtual_system": 1,
    }
    blob = json.dumps(report)
    assert "CP-GW-TEST-01" not in blob
    assert "CP-VSX-TEST-01" not in blob
    assert "approved_normalized_projection_v1" not in blob
    assert report["privacy_contract"]["entity_identities_included"] is False


def test_source_method_mismatch_blocks_merge_and_promotion():
    report = evaluate_checkpoint_alignment_real_env_gate(
        _representative_payload(),
        _source_approval(source_method="other_projection"),
        _attestation(),
    )

    assert report["status"] == "blocked"
    assert report["promotion_status"] == "AUTOMATED_VALIDATED"
    assert report["main_merge"] == "blocked"
    assert "trusted_source_approval.source_method_bound" in report["failed_checks"]


def test_unsupported_intent_schema_blocks_gate():
    payload = _representative_payload()
    payload["management_intent"]["schema_version"] = "unsupported"

    report = evaluate_checkpoint_alignment_real_env_gate(
        payload,
        _source_approval(),
        _attestation(),
    )

    assert report["status"] == "blocked"
    assert "representative_evidence.intent_schema_supported" in report["failed_checks"]


def test_missing_representative_vsx_or_cluster_peer_blocks_gate():
    payload = _representative_payload()
    payload["devices"] = payload["devices"][:2]
    payload["management_intent"]["entities"] = payload["management_intent"]["entities"][:2]

    report = evaluate_checkpoint_alignment_real_env_gate(
        payload,
        _source_approval(),
        _attestation(),
    )

    assert report["status"] == "blocked"
    assert "representative_evidence.required_entity_types_present" in report["failed_checks"]
    assert "representative_evidence.clusterxl_representative_present" in report["failed_checks"]


def test_synthetic_or_unreviewed_attestation_cannot_close_real_env_gate():
    report = evaluate_checkpoint_alignment_real_env_gate(
        _representative_payload(),
        _source_approval(),
        _attestation(evidence_origin="synthetic", privacy_review_passed=False),
    )

    assert report["status"] == "blocked"
    assert "human_real_env_attestation.real_environment_origin" in report["failed_checks"]
    assert "human_real_env_attestation.privacy_review_passed" in report["failed_checks"]


def test_unverified_actual_identity_blocks_gate():
    payload = _representative_payload()
    payload["devices"][0]["identity_gate"]["accepted"] = False

    report = evaluate_checkpoint_alignment_real_env_gate(
        payload,
        _source_approval(),
        _attestation(),
    )

    assert report["status"] == "blocked"
    assert "representative_evidence.all_actual_identities_accepted" in report["failed_checks"]
    assert "representative_evidence.no_insufficient_or_unverified_entity" in report["failed_checks"]


def test_duplicate_actual_identity_blocks_gate():
    payload = _representative_payload()
    payload["devices"][2]["entity_id"] = payload["devices"][1]["entity_id"]
    payload["devices"][2]["device"] = payload["devices"][1]["device"]
    payload["management_intent"]["entities"].pop(2)

    report = evaluate_checkpoint_alignment_real_env_gate(
        payload,
        _source_approval(),
        _attestation(),
    )

    assert report["status"] == "blocked"
    assert "representative_evidence.actual_identities_unique" in report["failed_checks"]


def test_cluster_members_from_different_groups_do_not_satisfy_representative_gate():
    payload = _representative_payload()
    payload["devices"][2]["cluster_group_id"] = "cluster-test-b"

    report = evaluate_checkpoint_alignment_real_env_gate(
        payload,
        _source_approval(),
        _attestation(),
    )

    assert report["status"] == "blocked"
    assert "representative_evidence.clusterxl_representative_present" in report["failed_checks"]


def test_unsupported_actual_entity_type_blocks_gate():
    payload = _representative_payload()
    payload["devices"].append(_actual("CP-UNKNOWN-TEST-01", "unsupported_type"))
    payload["management_intent"]["entities"].append(
        _intent_entity("CP-UNKNOWN-TEST-01", "unsupported_type")
    )

    report = evaluate_checkpoint_alignment_real_env_gate(
        payload,
        _source_approval(),
        _attestation(),
    )

    assert report["status"] == "blocked"
    assert "representative_evidence.only_supported_entity_types_present" in report["failed_checks"]


def test_entity_without_evaluated_facts_blocks_gate():
    payload = _representative_payload()
    payload["devices"][0]["current_configuration"]["sections"] = []
    payload["management_intent"]["entities"][0]["settings"] = []

    report = evaluate_checkpoint_alignment_real_env_gate(
        payload,
        _source_approval(),
        _attestation(),
    )

    assert report["status"] == "blocked"
    assert "representative_evidence.all_entities_have_evaluated_facts" in report["failed_checks"]


def test_future_approval_and_attestation_dates_block_gate():
    report = evaluate_checkpoint_alignment_real_env_gate(
        _representative_payload(),
        _source_approval(approved_on="9999-12-31"),
        _attestation(validated_on="9999-12-31"),
    )

    assert report["status"] == "blocked"
    assert "trusted_source_approval.approval_date_valid" in report["failed_checks"]
    assert "human_real_env_attestation.validation_date_valid" in report["failed_checks"]
