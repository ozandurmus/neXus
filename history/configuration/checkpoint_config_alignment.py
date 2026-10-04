from __future__ import annotations

from collections import Counter, defaultdict
from typing import Any


SCHEMA_VERSION = "0.6.1D"
TRUSTED_INTENT_CONFIDENCE = {"HIGH", "MEDIUM"}
SENSITIVE_KEY_TOKENS = {
    "password",
    "passwd",
    "secret",
    "private-key",
    "private_key",
    "pre-shared-key",
    "pre_shared_key",
    "auth-key",
    "auth_key",
    "api-key",
    "api_key",
    "community",
    "token",
    "credential",
}


def _as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _normalized(value: Any) -> str:
    return " ".join(str(value or "").strip().lower().split())


def _sensitive_key(key: str) -> bool:
    lowered = key.lower()
    return any(token in lowered for token in SENSITIVE_KEY_TOKENS)


def _entity_key(row: dict[str, Any]) -> tuple[str | None, str | None]:
    entity_type = _normalized(row.get("entity_type")) or "unknown"
    if entity_type == "virtual_system":
        physical = str(
            row.get("physical_entity_id")
            or row.get("parent_entity_id")
            or row.get("device")
            or ""
        ).strip()
        vs_id = str(row.get("vs_id") or "").strip()
        if not physical or not vs_id.isdigit():
            return None, "virtual_system_physical_identity_or_vsid_unavailable"
        return f"virtual_system:{physical}:vsid:{int(vs_id)}", None

    entity_id = str(row.get("entity_id") or row.get("device") or "").strip()
    if not entity_id:
        return None, "entity_identity_unavailable"
    return f"{entity_type}:{entity_id}", None


def _actual_setting_key(section_id: str, setting: dict[str, Any]) -> str:
    explicit = str(setting.get("key") or setting.get("_key") or "").strip()
    context = _normalized(setting.get("context"))
    label = _normalized(setting.get("setting"))
    if explicit:
        normalized_explicit = _normalized(explicit)
        if "|" in normalized_explicit:
            return normalized_explicit

        suffix = normalized_explicit
        if section_id and suffix.startswith(f"{section_id} "):
            suffix = suffix[len(section_id) + 1 :]
        leaf = label or suffix

        parts: list[str] = []
        for part in (section_id, context, leaf):
            normalized_part = _normalized(part)
            if not normalized_part:
                continue
            if not parts or parts[-1] != normalized_part:
                parts.append(normalized_part)
        return "|".join(parts)
    return "|".join(part for part in (section_id, context, label) if part)


def _actual_settings(row: dict[str, Any]) -> tuple[dict[str, dict[str, Any]], int]:
    result: dict[str, dict[str, Any]] = {}
    withheld = 0
    current = _as_dict(row.get("current_configuration"))
    for section in _as_list(current.get("sections")):
        section = _as_dict(section)
        category = _normalized(section.get("id")) or "other"
        for item in _as_list(section.get("settings")):
            item = _as_dict(item)
            key = _actual_setting_key(category, item)
            if not key or _sensitive_key(key):
                withheld += 1
                continue
            result[key] = {
                "key": key,
                "category": category,
                "value": str(item.get("value") or ""),
                "member_specific": bool(item.get("member_specific")),
            }
    return result, withheld


def _intent_settings(row: dict[str, Any]) -> tuple[dict[str, dict[str, Any]], int]:
    result: dict[str, dict[str, Any]] = {}
    withheld = 0
    for item in _as_list(row.get("settings")):
        item = _as_dict(item)
        key = _normalized(item.get("key"))
        if not key or _sensitive_key(key):
            withheld += 1
            continue
        result[key] = {
            "key": key,
            "category": _normalized(item.get("category")) or "other",
            "value": str(item.get("value") or ""),
            "comparable": item.get("comparable") is not False,
            "member_specific": bool(item.get("member_specific")),
        }
    return result, withheld


def _result_row(
    *,
    key: str,
    category: str,
    classification: str,
    reason: str,
    confidence: str,
) -> dict[str, Any]:
    return {
        "setting": key,
        "category": category,
        "classification": classification,
        "reason": reason,
        "confidence": confidence,
        "expected_source_kind": "checkpoint_management",
        "raw_values_included": False,
        "value_hashes_included": False,
    }


def _empty_entity_result(
    row: dict[str, Any],
    *,
    entity_key: str | None,
    reason: str,
) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "entity_key": entity_key,
        "entity_id": row.get("entity_id") or row.get("device"),
        "status": "insufficient_evidence",
        "device_status": "INSUFFICIENT_EVIDENCE",
        "reason": reason,
        "summary": {
            "expected_settings": 0,
            "actual_settings": 0,
            "evaluated_settings": 0,
            "classification_counts": {"INSUFFICIENT_EVIDENCE": 1},
            "category_counts": {},
            "sensitive_settings_withheld": 0,
            "peer_evidence_incomplete": False,
        },
        "results": [],
        "raw_values_included": False,
        "value_hashes_included": False,
    }


def _align_entity(
    actual: dict[str, Any],
    intent: dict[str, Any] | None,
    *,
    intent_trusted: bool,
    intent_available: bool,
    peer_evidence_incomplete: bool,
) -> dict[str, Any]:
    entity_key, identity_error = _entity_key(actual)
    if identity_error:
        return _empty_entity_result(actual, entity_key=None, reason=identity_error)

    actual_ok = (
        actual.get("status") == "success"
        and _as_dict(actual.get("current_configuration")).get("status") == "available"
        and _as_dict(actual.get("identity_gate")).get("accepted") is True
    )
    actual_settings, actual_withheld = _actual_settings(actual) if actual_ok else ({}, 0)
    intent_settings, intent_withheld = _intent_settings(intent or {}) if intent else ({}, 0)

    results: list[dict[str, Any]] = []
    all_keys = sorted(set(actual_settings) | set(intent_settings))
    for key in all_keys:
        expected = intent_settings.get(key)
        observed = actual_settings.get(key)
        category = str((expected or observed or {}).get("category") or "other")

        if observed is not None and observed.get("member_specific") and expected is None:
            classification, reason, confidence = (
                "MEMBER_SPECIFIC",
                "observed_cluster_member_difference_is_excluded_from_generic_drift_claims",
                "medium",
            )
        elif not intent_available or intent is None:
            classification, reason, confidence = (
                "ACTUAL_ONLY",
                "direct_actual_setting_has_no_management_intent_counterpart",
                "none",
            )
        elif not intent_trusted:
            classification, reason, confidence = (
                "PROVENANCE_UNVERIFIED",
                "management_intent_source_confidence_is_not_sufficient_for_alignment_claim",
                "none",
            )
        elif expected is None:
            classification, reason, confidence = (
                "ACTUAL_ONLY",
                "direct_actual_setting_has_no_management_intent_counterpart",
                "low",
            )
        elif observed is None:
            classification, reason, confidence = (
                "EXPECTED_ONLY",
                "management_intent_setting_not_observed_in_direct_actual_evidence",
                "low",
            )
        elif not expected.get("comparable"):
            classification, reason, confidence = (
                "UNKNOWN",
                "management_intent_setting_is_not_declared_directly_comparable",
                "none",
            )
        elif expected.get("value") == observed.get("value"):
            classification, reason, confidence = (
                "ALIGNED",
                "trusted_management_intent_and_direct_actual_value_match",
                "high",
            )
        elif expected.get("member_specific") or observed.get("member_specific"):
            classification, reason, confidence = (
                "MEMBER_SPECIFIC",
                "member_or_context_specific_difference_is_excluded_from_generic_drift_claims",
                "medium",
            )
        else:
            classification, reason, confidence = (
                "DIFFERENCE_OBSERVED",
                "trusted_management_intent_and_direct_actual_differ_but_cp_drift_proof_chain_is_unavailable",
                "medium",
            )

        results.append(
            _result_row(
                key=key,
                category=category,
                classification=classification,
                reason=reason,
                confidence=confidence,
            )
        )

    counts = Counter(str(item.get("classification") or "UNKNOWN") for item in results)
    category_counts: dict[str, Counter[str]] = defaultdict(Counter)
    for item in results:
        category_counts[str(item.get("category") or "other")][str(item.get("classification") or "UNKNOWN")] += 1

    if not actual_ok or not intent_available or intent is None:
        device_status = "INSUFFICIENT_EVIDENCE"
        status = "insufficient_evidence"
        reason = "management_intent_or_verified_direct_actual_evidence_unavailable"
    elif not intent_trusted:
        device_status = "PROVENANCE_UNVERIFIED"
        status = "partial"
        reason = "management_intent_provenance_unverified"
    elif counts["DIFFERENCE_OBSERVED"]:
        device_status = "DIFFERENCE_OBSERVED"
        status = "success"
        reason = "trusted_comparable_difference_observed_without_drift_overclaim"
    elif counts["EXPECTED_ONLY"] or counts["ACTUAL_ONLY"] or counts["UNKNOWN"]:
        device_status = "ALIGNED_WITH_COVERAGE_GAPS" if counts["ALIGNED"] else "UNKNOWN"
        status = "partial"
        reason = "alignment_completed_with_coverage_gaps"
    elif counts["MEMBER_SPECIFIC"]:
        device_status = "ALIGNED_WITH_SEMANTIC_EXCLUSIONS" if counts["ALIGNED"] else "SEMANTIC_EXCLUSIONS_ONLY"
        status = "success"
        reason = "member_specific_differences_excluded_from_drift_claims"
    else:
        device_status = "ALIGNED"
        status = "success"
        reason = "trusted_comparable_management_intent_matches_direct_actual"

    return {
        "schema_version": SCHEMA_VERSION,
        "entity_key": entity_key,
        "entity_id": actual.get("entity_id") or actual.get("device"),
        "status": status,
        "device_status": device_status,
        "reason": reason,
        "summary": {
            "expected_settings": len(intent_settings),
            "actual_settings": len(actual_settings),
            "evaluated_settings": len(results),
            "classification_counts": dict(sorted(counts.items())),
            "category_counts": {
                category: dict(sorted(category_count.items()))
                for category, category_count in sorted(category_counts.items())
            },
            "sensitive_settings_withheld": actual_withheld + intent_withheld,
            "peer_evidence_incomplete": peer_evidence_incomplete,
        },
        "results": results,
        "engine_contract": {
            "scope": "explicit_management_projection_vs_secret_aware_direct_actual_scalars",
            "effective_drift_emitted": False,
            "local_override_emitted": False,
            "peer_difference_is_drift": False,
            "raw_values_stored": False,
            "value_hashes_exported": False,
        },
        "raw_values_included": False,
        "value_hashes_included": False,
    }


def align_checkpoint_management_intent(
    checkpoint_result: dict[str, Any] | None,
    management_intent: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Align explicit CP Management intent with existing direct actual evidence.

    The function is intentionally pure and collection-agnostic. It never infers
    intent from actual rows and never emits EFFECTIVE_DRIFT or LOCAL_OVERRIDE.
    """

    checkpoint_result = _as_dict(checkpoint_result)
    management_intent = _as_dict(
        management_intent
        if management_intent is not None
        else checkpoint_result.get("management_intent")
    )
    actual_rows = [_as_dict(row) for row in _as_list(checkpoint_result.get("devices"))]
    intent_rows = [_as_dict(row) for row in _as_list(management_intent.get("entities"))]

    intent_available = (
        management_intent.get("status") == "success"
        and management_intent.get("source_plane") == "checkpoint_management"
    )
    intent_confidence = str(management_intent.get("source_confidence") or "UNKNOWN").upper()
    intent_trusted = intent_available and intent_confidence in TRUSTED_INTENT_CONFIDENCE

    intent_by_key: dict[str, dict[str, Any]] = {}
    ambiguous_intent_keys: set[str] = set()
    for row in intent_rows:
        key, error = _entity_key(row)
        if error or key is None:
            continue
        if key in intent_by_key:
            ambiguous_intent_keys.add(key)
        else:
            intent_by_key[key] = row

    group_status: dict[str, list[bool]] = defaultdict(list)
    for row in actual_rows:
        group_id = str(row.get("cluster_group_id") or "")
        if group_id and row.get("entity_type") in {"clusterxl_member", "vsx_host", "virtual_system"}:
            group_status[group_id].append(row.get("status") == "success")

    entity_results: list[dict[str, Any]] = []
    matched_intent_keys: set[str] = set()
    for actual in actual_rows:
        key, error = _entity_key(actual)
        if error or key is None:
            entity_results.append(_empty_entity_result(actual, entity_key=None, reason=error or "identity_unavailable"))
            continue
        intent = None if key in ambiguous_intent_keys else intent_by_key.get(key)
        if intent is not None:
            matched_intent_keys.add(key)
        group_id = str(actual.get("cluster_group_id") or "")
        peers = group_status.get(group_id) or []
        peer_incomplete = bool(group_id and peers and not all(peers))
        entity_results.append(
            _align_entity(
                actual,
                intent,
                intent_trusted=intent_trusted,
                intent_available=intent_available,
                peer_evidence_incomplete=peer_incomplete,
            )
        )

    counts = Counter(
        classification
        for entity in entity_results
        for classification, count in _as_dict(_as_dict(entity.get("summary")).get("classification_counts")).items()
        for _ in range(int(count or 0))
    )
    return {
        "schema_version": SCHEMA_VERSION,
        "status": "success" if intent_trusted else "insufficient_evidence",
        "reason": (
            "trusted_management_intent_projection_compared_to_direct_actual"
            if intent_trusted
            else "trusted_management_intent_projection_unavailable"
        ),
        "intent_source": {
            "status": management_intent.get("status") or "unavailable",
            "source_plane": management_intent.get("source_plane") or "unknown",
            "source_method": management_intent.get("source_method") or "unknown",
            "source_confidence": intent_confidence,
            "trusted": intent_trusted,
        },
        "summary": {
            "actual_entities": len(actual_rows),
            "intent_entities": len(intent_rows),
            "aligned_entities": len(entity_results),
            "unmatched_intent_entities": len(set(intent_by_key) - matched_intent_keys),
            "ambiguous_intent_entities": len(ambiguous_intent_keys),
            "classification_counts": dict(sorted(counts.items())),
        },
        "entities": entity_results,
        "raw_values_included": False,
        "value_hashes_included": False,
    }
