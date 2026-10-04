from __future__ import annotations

import hashlib
import re
from collections import Counter, defaultdict
from difflib import SequenceMatcher
from typing import Any, Callable

from lxml import etree

from configuration.pan_setting_alignment import alignment_key_for_node


SEMANTIC_VALIDATION_SCHEMA_VERSION = "0.6.0A4.2.1"
_PRESENT_HASH = hashlib.sha256(b"__PRESENT__").hexdigest()
_SENSITIVE_TOKENS = {
    "password", "passwd", "secret", "private-key", "pre-shared-key",
    "authentication-key", "auth-key", "community", "api-key",
}


def _safe_xml(content: bytes) -> etree._Element:
    parser = etree.XMLParser(resolve_entities=False, no_network=True, recover=False, huge_tree=True)
    return etree.fromstring(content, parser=parser)


def _sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _component_shape(component: str) -> str:
    return re.sub(r"\[@name='[^']*'\]", "[@name='*']", component)


def _shape_tokens(key: str) -> list[str]:
    return [_component_shape(part) for part in str(key or "").split("/") if part]


def _leaf_tag(key: str) -> str:
    tokens = _shape_tokens(key)
    if not tokens:
        return ""
    return tokens[-1].split("[", 1)[0]


def _parent_leaf_signature(key: str) -> tuple[str, str]:
    tokens = _shape_tokens(key)
    if not tokens:
        return ("", "")
    leaf = tokens[-1].split("[", 1)[0]
    parent = tokens[-2].split("[", 1)[0] if len(tokens) > 1 else ""
    return (parent, leaf)


def _path_similarity(left: str, right: str) -> float:
    a = "/".join(_shape_tokens(left))
    b = "/".join(_shape_tokens(right))
    return SequenceMatcher(None, a, b).ratio()


def _is_sensitive_key(key: str) -> bool:
    lowered = str(key or "").lower()
    return any(token in lowered for token in _SENSITIVE_TOKENS)


def _display_value(key: str, value: str | None) -> str | None:
    if value is None:
        return None
    if _is_sensitive_key(key):
        return "[SENSITIVE:REDACTED]"
    return value


def _raw_scalar_values(content: bytes | None, interested: set[str]) -> dict[str, str]:
    if not content or not interested:
        return {}
    root = _safe_xml(content)
    values: dict[str, str] = {}
    for node in root.iter():
        if not isinstance(node.tag, str) or len(node) != 0:
            continue
        tag = node.tag.rsplit("}", 1)[-1]
        parent = node.getparent()
        same = []
        if parent is not None:
            same = [
                child for child in parent
                if isinstance(child.tag, str) and child.tag.rsplit("}", 1)[-1] == tag
            ]
        if tag == "member" or (len(same) > 1 and not node.get("name")):
            continue
        text = str(node.text or "").strip()
        attrs = {k: v for k, v in node.attrib.items() if k not in {"name", "src"}}
        if attrs and not text:
            continue
        key = alignment_key_for_node(node, root)
        if key in interested:
            values[key] = text if text else "__PRESENT__"
    return values


def _source_configs(panorama_content: bytes | None) -> dict[tuple[str, str], etree._Element]:
    if not panorama_content:
        return {}
    root = _safe_xml(panorama_content)
    configs: dict[tuple[str, str], etree._Element] = {}
    for controller in root.xpath("./devices/entry"):
        for template in controller.xpath("./template/entry"):
            name = str(template.get("name") or "").strip()
            config = template.find("config")
            if name and config is not None:
                configs[("template", name)] = config
        for stack in controller.xpath("./template-stack/entry"):
            name = str(stack.get("name") or "").strip()
            config = stack.find("config")
            if name and config is not None:
                configs[("template_stack_override", name)] = config
    return configs


def _source_raw_values(
    panorama_content: bytes | None,
    needs: dict[tuple[str, str], set[str]],
) -> dict[tuple[str, str, str], str]:
    if not panorama_content or not needs:
        return {}
    configs = _source_configs(panorama_content)
    output: dict[tuple[str, str, str], str] = {}
    for source, keys in needs.items():
        config = configs.get(source)
        if config is None:
            continue
        # Serialize only the selected source subtree and reuse the same key
        # normalizer as direct firewall configs.
        content = etree.tostring(config)
        for key, value in _raw_scalar_values(content, keys).items():
            output[(source[0], source[1], key)] = value
    return output


def _candidate_schema_twins(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    expected_rows = [row for row in results if row.get("classification") == "EXPECTED_ONLY"]
    local_rows = [row for row in results if row.get("classification") == "LOCAL_ONLY"]
    by_signature: dict[tuple[str, str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in local_rows:
        value_hash = str(row.get("active_value_sha256") or "")
        if not value_hash or value_hash == _PRESENT_HASH:
            continue
        key = str(row.get("alignment_key") or "")
        by_signature[(str(row.get("category") or "other"), _leaf_tag(key), value_hash)].append(row)

    candidates: list[dict[str, Any]] = []
    used_local: set[str] = set()
    for expected in sorted(expected_rows, key=lambda row: str(row.get("path_sha256") or "")):
        value_hash = str(expected.get("expected_value_sha256") or "")
        if not value_hash or value_hash == _PRESENT_HASH or expected.get("expected_value_kind") == "presence":
            continue
        expected_key = str(expected.get("alignment_key") or "")
        pool = by_signature.get((str(expected.get("category") or "other"), _leaf_tag(expected_key), value_hash), [])
        scored = []
        for local in pool:
            local_key = str(local.get("alignment_key") or "")
            if local_key in used_local:
                continue
            score = _path_similarity(expected_key, local_key)
            same_parent = _parent_leaf_signature(expected_key) == _parent_leaf_signature(local_key)
            scored.append((score, same_parent, local))
        if not scored:
            continue
        scored.sort(key=lambda item: (item[0], item[1]), reverse=True)
        top_score, same_parent, top = scored[0]
        second = scored[1][0] if len(scored) > 1 else 0.0
        # Conservative only: same value/category/leaf is not enough because PAN
        # configs contain many common booleans. Require strong path-shape evidence
        # and a clear winner when multiple candidates exist.
        threshold = 0.70 if same_parent else 0.82
        if top_score < threshold:
            continue
        if len(scored) > 1 and (top_score - second) < 0.08:
            continue
        local_key = str(top.get("alignment_key") or "")
        used_local.add(local_key)
        candidates.append({
            "classification": "POSSIBLE_SCHEMA_EQUIVALENT",
            "category": expected.get("category") or "other",
            "expected_alignment_key": expected_key,
            "expected_path_sha256": expected.get("path_sha256"),
            "local_alignment_key": local_key,
            "local_path_sha256": top.get("path_sha256"),
            "shared_value_sha256": value_hash,
            "expected_source_kind": expected.get("expected_source_kind"),
            "expected_source_name": expected.get("expected_source_name"),
            "expected_source_priority": expected.get("expected_source_priority"),
            "expected_source_alignment_key": expected.get("expected_source_alignment_key") or expected_key,
            "path_shape_similarity": round(top_score, 4),
            "same_parent_leaf_signature": bool(same_parent),
            "confidence": "candidate_only",
            "reason": "same_scalar_hash_category_leaf_with_conservative_path_shape_match",
            "promoted_to_aligned": False,
        })
    return candidates


def _sample_rows(results: list[dict[str, Any]], candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    samples: list[dict[str, Any]] = []
    by_class_category: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in results:
        cls = str(row.get("classification") or "UNKNOWN")
        cat = str(row.get("category") or "other")
        if cls in {"LOCAL_OVERRIDE", "EFFECTIVE_DRIFT", "PANORAMA_OUT_OF_SYNC", "UNKNOWN", "EXPECTED_ONLY", "LOCAL_ONLY"}:
            by_class_category[(cls, cat)].append(row)

    limits = {
        "LOCAL_OVERRIDE": 3,
        "EFFECTIVE_DRIFT": 3,
        "PANORAMA_OUT_OF_SYNC": 3,
        "UNKNOWN": 1,
        "EXPECTED_ONLY": 1,
        "LOCAL_ONLY": 1,
    }
    for (cls, cat), rows in sorted(by_class_category.items()):
        rows = sorted(rows, key=lambda row: str(row.get("path_sha256") or row.get("alignment_key") or ""))
        for row in rows[: limits.get(cls, 1)]:
            samples.append({**row, "sample_kind": "classification_sample"})

    for candidate in sorted(candidates, key=lambda row: (str(row.get("category")), str(row.get("expected_path_sha256")))):
        # Up to two semantic-twin candidates per category.
        if sum(1 for s in samples if s.get("sample_kind") == "schema_equivalent_candidate" and s.get("category") == candidate.get("category")) >= 2:
            continue
        samples.append({**candidate, "sample_kind": "schema_equivalent_candidate"})
    return samples


def build_semantic_validation(
    *,
    rows: list[dict[str, Any]],
    panorama_content: bytes | None,
    artifact_loader: Callable[[dict[str, Any], str], bytes | None],
) -> dict[str, Any]:
    """Build A4.2.1 semantic-validation evidence and a local manual checklist.

    The engine never reclassifies A4.2 results. It identifies conservative
    schema-equivalence candidates and emits deterministic manual samples. Raw
    values, when useful for operator verification, exist only in the local
    operator report and sensitive paths are redacted.
    """

    all_candidates: list[dict[str, Any]] = []
    sample_pool: list[tuple[dict[str, Any], dict[str, Any]]] = []
    class_counts: Counter[str] = Counter()
    category_candidate_counts: Counter[str] = Counter()

    for row in rows:
        detail = row.get("_setting_alignment_detail") or {}
        results = list(detail.get("results") or [])
        class_counts.update(str(item.get("classification") or "UNKNOWN") for item in results)
        device_candidates = _candidate_schema_twins(results)
        for candidate in device_candidates:
            candidate = {**candidate, "device_serial": row.get("serial")}
            all_candidates.append(candidate)
            category_candidate_counts[str(candidate.get("category") or "other")] += 1
        for sample in _sample_rows(results, device_candidates):
            sample_pool.append((row, sample))

    # Keep the human checklist intentionally small and fleet-representative.
    # Selection is deterministic across runs for the same evidence because the
    # sort key is identity/path-derived rather than collection order.
    samples_by_device: list[tuple[dict[str, Any], dict[str, Any]]] = []
    sample_caps = {
        "LOCAL_OVERRIDE": 4,
        "EFFECTIVE_DRIFT": 4,
        "PANORAMA_OUT_OF_SYNC": 4,
        "UNKNOWN": 2,
        "EXPECTED_ONLY": 1,
        "LOCAL_ONLY": 1,
        "POSSIBLE_SCHEMA_EQUIVALENT": 2,
    }
    selected_counts: Counter[tuple[str, str, str]] = Counter()
    for row, sample in sorted(
        sample_pool,
        key=lambda pair: (
            str((pair[1] or {}).get("sample_kind") or ""),
            str((pair[1] or {}).get("classification") or ""),
            str((pair[1] or {}).get("category") or ""),
            str((pair[0] or {}).get("serial") or ""),
            str((pair[1] or {}).get("path_sha256") or (pair[1] or {}).get("expected_path_sha256") or ""),
        ),
    ):
        cls = str(sample.get("classification") or "UNKNOWN")
        cat = str(sample.get("category") or "other")
        kind = str(sample.get("sample_kind") or "classification_sample")
        bucket = (kind, cls, cat)
        if selected_counts[bucket] >= sample_caps.get(cls, 1):
            continue
        selected_counts[bucket] += 1
        samples_by_device.append((row, sample))

    expected_needs: dict[tuple[str, str], set[str]] = defaultdict(set)
    direct_needs: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
    for row, sample in samples_by_device:
        serial = str(row.get("serial") or "")
        keys = []
        if sample.get("sample_kind") == "schema_equivalent_candidate":
            keys.extend([sample.get("expected_alignment_key"), sample.get("local_alignment_key")])
        else:
            keys.append(sample.get("alignment_key"))
        for key in [str(value) for value in keys if value]:
            direct_needs[serial]["active"].add(key)
            direct_needs[serial]["effective"].add(key)
            direct_needs[serial]["merged"].add(key)
        source_kind = sample.get("expected_source_kind")
        source_name = sample.get("expected_source_name")
        expected_key = sample.get("expected_source_alignment_key") or sample.get("expected_alignment_key") or sample.get("alignment_key")
        if source_kind and source_name and expected_key:
            expected_needs[(str(source_kind), str(source_name))].add(str(expected_key))

    source_values = _source_raw_values(panorama_content, expected_needs)
    direct_values: dict[tuple[str, str, str], str] = {}
    row_by_serial = {str(row.get("serial") or ""): row for row in rows}
    for serial, kinds in direct_needs.items():
        row = row_by_serial.get(serial)
        if not row:
            continue
        for kind, keys in kinds.items():
            content = artifact_loader(row, kind)
            for key, value in _raw_scalar_values(content, keys).items():
                direct_values[(serial, kind, key)] = value

    operator_samples: list[dict[str, Any]] = []
    manifest_samples: list[dict[str, Any]] = []
    sample_kind_counts: Counter[str] = Counter()
    sample_class_counts: Counter[str] = Counter()
    for row, sample in samples_by_device:
        serial = str(row.get("serial") or "")
        sample_kind = str(sample.get("sample_kind") or "classification_sample")
        sample_kind_counts[sample_kind] += 1
        classification = str(sample.get("classification") or "UNKNOWN")
        sample_class_counts[classification] += 1
        primary_key = str(sample.get("expected_alignment_key") or sample.get("alignment_key") or "")
        source_key = str(sample.get("expected_source_alignment_key") or primary_key)
        local_key = str(sample.get("local_alignment_key") or primary_key)
        source_kind = str(sample.get("expected_source_kind") or "")
        source_name = str(sample.get("expected_source_name") or "")
        expected_raw = source_values.get((source_kind, source_name, source_key)) if source_kind and source_name else None
        active_raw = direct_values.get((serial, "active", local_key if sample_kind == "schema_equivalent_candidate" else primary_key))
        effective_raw = direct_values.get((serial, "effective", local_key if sample_kind == "schema_equivalent_candidate" else primary_key))
        merged_raw = direct_values.get((serial, "merged", local_key if sample_kind == "schema_equivalent_candidate" else primary_key))
        sample_id = "SV_" + _sha256_text(f"{serial}|{sample_kind}|{classification}|{primary_key}|{local_key}")[:12]
        manifest_samples.append({
            "sample_id": sample_id,
            "device_serial": serial,
            "sample_kind": sample_kind,
            "classification": classification,
            "category": sample.get("category") or "other",
            "alignment_key": sample.get("alignment_key"),
            "expected_alignment_key": sample.get("expected_alignment_key"),
            "local_alignment_key": sample.get("local_alignment_key"),
            "path_sha256": sample.get("path_sha256") or sample.get("expected_path_sha256"),
            "local_path_sha256": sample.get("local_path_sha256"),
            "expected_source_kind": sample.get("expected_source_kind"),
            "expected_source_name": sample.get("expected_source_name"),
            "expected_source_alignment_key": sample.get("expected_source_alignment_key"),
            "manual_result": "PENDING",
            "raw_values_included": False,
        })
        operator_samples.append({
            "sample_id": sample_id,
            "device": row.get("device"),
            "serial": row.get("serial"),
            "management_ip": row.get("management_ip"),
            "sample_kind": sample_kind,
            "classification": classification,
            "category": sample.get("category") or "other",
            "setting": primary_key,
            "possible_local_equivalent_setting": sample.get("local_alignment_key"),
            "expected_source_kind": sample.get("expected_source_kind"),
            "expected_source_name": sample.get("expected_source_name"),
            "expected_value": _display_value(primary_key, expected_raw),
            "local_active_value": _display_value(local_key, active_raw),
            "merged_value": _display_value(local_key, merged_raw),
            "effective_value": _display_value(local_key, effective_raw),
            "path_shape_similarity": sample.get("path_shape_similarity"),
            "manual_result": "PENDING",
            "operator_action": (
                "Verify the Panorama source setting and the direct firewall local/effective setting. "
                "Mark PASS only when the classification matches the vendor UI/CLI semantics."
            ),
            "local_only_sensitive_report": True,
        })

    expected_only = int(class_counts.get("EXPECTED_ONLY", 0))
    local_only = int(class_counts.get("LOCAL_ONLY", 0))
    candidate_count = len(all_candidates)
    summary = {
        "devices_evaluated": len(rows),
        "possible_schema_equivalent_candidates": candidate_count,
        "candidate_counts_by_category": dict(sorted(category_candidate_counts.items())),
        "expected_only_total": expected_only,
        "local_only_total": local_only,
        "expected_only_with_candidate": candidate_count,
        "local_only_with_candidate": candidate_count,
        "unexplained_expected_only": max(0, expected_only - candidate_count),
        "unexplained_local_only": max(0, local_only - candidate_count),
        "manual_samples_total": len(operator_samples),
        "manual_sample_kind_counts": dict(sorted(sample_kind_counts.items())),
        "manual_sample_classification_counts": dict(sorted(sample_class_counts.items())),
        "manual_confirmation_status": "pending",
        "raw_values_in_support_bundle": False,
        "setting_paths_in_support_bundle": False,
    }
    manifest = {
        "schema_version": SEMANTIC_VALIDATION_SCHEMA_VERSION,
        "status": "success",
        "local_only": True,
        "contains_device_identity": True,
        "contains_setting_paths": True,
        "contains_value_hashes": True,
        "contains_raw_configuration_values": False,
        "reclassification_performed": False,
        "candidate_contract": {
            "same_category_required": True,
            "same_leaf_tag_required": True,
            "same_scalar_hash_required": True,
            "conservative_path_shape_match_required": True,
            "automatic_alignment_promotion": False,
        },
        "summary": summary,
        "possible_schema_equivalents": all_candidates,
        "manual_samples": manifest_samples,
    }
    operator_report = {
        "schema_version": SEMANTIC_VALIDATION_SCHEMA_VERSION,
        "status": "awaiting_manual_confirmation",
        "local_only": True,
        "contains_device_identity": True,
        "contains_setting_paths": True,
        "contains_raw_configuration_values": True,
        "sensitive_values_redacted": True,
        "instructions": [
            "Do not share this file outside the trusted operator environment.",
            "Validate LOCAL_OVERRIDE samples against Panorama Template/Stack and the firewall local/effective view.",
            "Validate POSSIBLE_SCHEMA_EQUIVALENT samples only as coverage-gap hypotheses; they are not auto-promoted to ALIGNED.",
            "Return sample_id plus PASS/FAIL/UNKNOWN to record manual validation; raw values are not required for support analysis.",
        ],
        "summary": summary,
        "samples": operator_samples,
    }
    return {
        "schema_version": SEMANTIC_VALIDATION_SCHEMA_VERSION,
        "status": "success",
        "summary": summary,
        "manifest": manifest,
        "operator_report": operator_report,
    }
