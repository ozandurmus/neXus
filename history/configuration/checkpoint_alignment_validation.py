from __future__ import annotations

from datetime import date
from typing import Any

from configuration.checkpoint_config_alignment import align_checkpoint_management_intent


REQUIRED_ENTITY_TYPES = {
    "standalone_gateway",
    "clusterxl_member",
    "virtual_system",
}
SUPPORTED_ENTITY_TYPES = REQUIRED_ENTITY_TYPES | {"vsx_host"}
REQUIRED_SOURCE_CONTROLS = {
    "read_only",
    "versioned_projection",
    "provenance_preserved",
    "identity_mapping_verified",
    "collection_status_verified",
    "secret_output_reviewed",
    "sanitization_verified",
}
PROHIBITED_CLASSIFICATIONS = {"EFFECTIVE_DRIFT", "LOCAL_OVERRIDE"}


def _as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _valid_non_future_iso_date(value: Any) -> bool:
    try:
        parsed = date.fromisoformat(str(value))
    except (TypeError, ValueError):
        return False
    return parsed <= date.today()


def _source_approval_checks(
    management_intent: dict[str, Any],
    source_approval: dict[str, Any],
) -> dict[str, bool]:
    approved_method = str(source_approval.get("source_method") or "").strip()
    payload_method = str(management_intent.get("source_method") or "").strip()
    controls = _as_dict(source_approval.get("controls"))
    return {
        "decision_approved": source_approval.get("decision") == "approved",
        "source_method_bound": bool(approved_method) and approved_method == payload_method,
        "source_owner_role_present": bool(str(source_approval.get("source_owner_role") or "").strip()),
        "approval_role_present": bool(str(source_approval.get("approved_by_role") or "").strip()),
        "approval_date_valid": _valid_non_future_iso_date(source_approval.get("approved_on")),
        "network_change_separately_gated": source_approval.get("introduces_network_change") is False,
        **{
            f"control_{control}": controls.get(control) is True
            for control in sorted(REQUIRED_SOURCE_CONTROLS)
        },
    }


def _attestation_checks(attestation: dict[str, Any]) -> dict[str, bool]:
    return {
        "human_status_accepted": attestation.get("status") == "accepted",
        "real_environment_origin": attestation.get("evidence_origin") == "real_environment",
        "sanitized_evidence_class": attestation.get("evidence_class") == "CLASS_1_SANITIZED",
        "representative_scope_confirmed": attestation.get("representative_scope_confirmed") is True,
        "privacy_review_passed": attestation.get("privacy_review_passed") is True,
        "reviewer_role_present": bool(str(attestation.get("reviewed_by_role") or "").strip()),
        "validation_date_valid": _valid_non_future_iso_date(attestation.get("validated_on")),
    }


def evaluate_checkpoint_alignment_real_env_gate(
    checkpoint_result: dict[str, Any] | None,
    source_approval: dict[str, Any] | None,
    validation_attestation: dict[str, Any] | None,
) -> dict[str, Any]:
    """Evaluate the 0.6.1D real-environment promotion gate.

    Inputs remain local and may contain operational identities. The returned
    acceptance report is deliberately value-free and identity-free.
    """

    checkpoint_result = _as_dict(checkpoint_result)
    management_intent = _as_dict(checkpoint_result.get("management_intent"))
    source_approval = _as_dict(source_approval)
    validation_attestation = _as_dict(validation_attestation)
    actual_rows = [_as_dict(row) for row in _as_list(checkpoint_result.get("devices"))]
    alignment = align_checkpoint_management_intent(checkpoint_result, management_intent)

    observed_entity_types = {
        str(row.get("entity_type") or "").strip()
        for row in actual_rows
        if str(row.get("entity_type") or "").strip()
    }
    classifications = set(_as_dict(_as_dict(alignment.get("summary")).get("classification_counts")))
    aligned_entities = _as_list(alignment.get("entities"))
    entity_statuses = {str(row.get("device_status") or "UNKNOWN") for row in aligned_entities}
    actual_entity_keys = [str(row.get("entity_key") or "").strip() for row in aligned_entities]
    cluster_member_keys_by_group: dict[str, set[str]] = {}
    for actual, aligned in zip(actual_rows, aligned_entities):
        if actual.get("entity_type") != "clusterxl_member":
            continue
        group_id = str(actual.get("cluster_group_id") or "").strip()
        entity_key = str(_as_dict(aligned).get("entity_key") or "").strip()
        if group_id and entity_key:
            cluster_member_keys_by_group.setdefault(group_id, set()).add(entity_key)
    source_checks = _source_approval_checks(management_intent, source_approval)
    attestation_checks = _attestation_checks(validation_attestation)
    evidence_checks = {
        "required_entity_types_present": REQUIRED_ENTITY_TYPES <= observed_entity_types,
        "only_supported_entity_types_present": bool(observed_entity_types)
        and observed_entity_types <= SUPPORTED_ENTITY_TYPES,
        "clusterxl_representative_present": any(
            len(member_keys) >= 2 for member_keys in cluster_member_keys_by_group.values()
        ),
        "intent_schema_supported": management_intent.get("schema_version") == "0.6.1D",
        "trusted_intent_consumed": _as_dict(alignment.get("intent_source")).get("trusted") is True,
        "all_actual_identities_accepted": bool(actual_rows) and all(
            row.get("status") == "success"
            and _as_dict(row.get("identity_gate")).get("accepted") is True
            for row in actual_rows
        ),
        "all_entities_mapped": bool(aligned_entities)
        and len(aligned_entities) == len(actual_rows)
        and all(row.get("entity_key") for row in aligned_entities),
        "actual_identities_unique": bool(actual_entity_keys)
        and all(actual_entity_keys)
        and len(set(actual_entity_keys)) == len(actual_entity_keys),
        "all_entities_have_evaluated_facts": bool(aligned_entities)
        and all(
            int(_as_dict(row.get("summary")).get("evaluated_settings") or 0) > 0
            for row in aligned_entities
        ),
        "no_ambiguous_intent_entities": _as_dict(alignment.get("summary")).get(
            "ambiguous_intent_entities"
        ) == 0,
        "no_unmatched_intent_entities": _as_dict(alignment.get("summary")).get(
            "unmatched_intent_entities"
        ) == 0,
        "no_insufficient_or_unverified_entity": not entity_statuses
        & {"INSUFFICIENT_EVIDENCE", "PROVENANCE_UNVERIFIED", "UNKNOWN"},
        "no_prohibited_drift_claim": not classifications & PROHIBITED_CLASSIFICATIONS,
        "raw_values_excluded": alignment.get("raw_values_included") is False,
        "value_hashes_excluded": alignment.get("value_hashes_included") is False,
    }

    checks = {
        "representative_evidence": evidence_checks,
        "trusted_source_approval": source_checks,
        "human_real_env_attestation": attestation_checks,
    }
    failed_checks = [
        f"{group}.{name}"
        for group, group_checks in checks.items()
        for name, passed in group_checks.items()
        if not passed
    ]
    approved = not failed_checks
    required_check_count = sum(len(group_checks) for group_checks in checks.values())
    failed_check_count = len(failed_checks)
    passed_check_count = required_check_count - failed_check_count

    return {
        "schema_version": "0.6.1D-validation-gate-1",
        "status": "passed" if approved else "blocked",
        "promotion_status": "REAL_ENV_VALIDATED" if approved else "AUTOMATED_VALIDATED",
        "main_merge": "approved" if approved else "blocked",
        "reason": (
            "representative_real_environment_and_trusted_intent_source_gates_passed"
            if approved
            else "real_environment_or_trusted_intent_source_gate_incomplete"
        ),
        "checks": checks,
        "failed_checks": failed_checks,
        "gate_calculation": {
            "rule": "all_required_checks_must_pass",
            "required_checks": required_check_count,
            "passed_checks": passed_check_count,
            "failed_checks": failed_check_count,
            "pass_rate_percent": round(100 * passed_check_count / required_check_count, 2),
        },
        "summary": {
            "actual_entities": len(actual_rows),
            "aligned_entities": len(aligned_entities),
            "entity_type_coverage": {
                entity_type: sum(row.get("entity_type") == entity_type for row in actual_rows)
                for entity_type in sorted(REQUIRED_ENTITY_TYPES)
            },
            "classification_counts": dict(
                sorted(_as_dict(_as_dict(alignment.get("summary")).get("classification_counts")).items())
            ),
        },
        "privacy_contract": {
            "entity_identities_included": False,
            "raw_values_included": False,
            "value_hashes_included": False,
            "reviewer_identity_included": False,
        },
    }
