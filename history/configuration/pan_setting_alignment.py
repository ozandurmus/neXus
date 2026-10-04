from __future__ import annotations

import hashlib
import re
from collections import Counter, defaultdict
from typing import Any

from lxml import etree

from configuration.pan_semantic_policy import (
    TRUSTED_EXPECTED_SOURCE_CONFIDENCE,
    semantic_policy_contract,
    semantic_policy_for_setting,
)


SETTING_ALIGNMENT_SCHEMA_VERSION = "0.6.0A4.2.2"

KNOWN_PROVENANCE = {
    "tpl": "template",
    "template": "template",
    "dg": "device_group",
    "device-group": "device_group",
    "shared": "shared",
    "local": "local",
}


def _local_name(tag: Any) -> str:
    if not isinstance(tag, str):
        return ""
    return tag.rsplit("}", 1)[-1]


def _safe_xml(content: bytes) -> etree._Element:
    parser = etree.XMLParser(
        resolve_entities=False,
        no_network=True,
        recover=False,
        huge_tree=True,
    )
    return etree.fromstring(content, parser=parser)


def _sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _name(node: etree._Element) -> str:
    return str(node.get("name") or "").strip()


def _path_component(node: etree._Element, stop: etree._Element) -> str:
    tag = _local_name(node.tag)
    name = _name(node)
    if name:
        # The root device entry is an implementation identity, not a semantic
        # configuration key. Templates and firewall configs normally use
        # localhost.localdomain here, but A4.2 does not rely on that literal.
        parent = node.getparent()
        grandparent = parent.getparent() if parent is not None else None
        if (
            tag == "entry"
            and parent is not None
            and _local_name(parent.tag) == "devices"
            and grandparent is stop
        ):
            name = "__DEVICE__"
        escaped = name.replace("'", "&apos;")
        return f"{tag}[@name='{escaped}']"
    return tag


def alignment_key_for_node(node: etree._Element, stop: etree._Element) -> str:
    parts: list[str] = []
    cur: etree._Element | None = node
    while cur is not None:
        parts.append(_path_component(cur, stop))
        if cur is stop:
            break
        cur = cur.getparent()
    return "/" + "/".join(reversed(parts))


def normalize_compiler_path(path: str) -> str:
    """Normalize an A4.1 compiler path to the A4.2 comparison key.

    This compatibility path lets A4.2 consume A4.1 manifests that do not yet
    contain ``alignment_key``. Only the first /config/devices/entry identity is
    normalized; VSYS, interface and other named-entry identities remain part of
    the semantic key.
    """

    prefix = "/config/devices/entry[@name='"
    if not path.startswith(prefix):
        return path
    end = path.find("']", len(prefix))
    if end < 0:
        return path
    return "/config/devices/entry[@name='__DEVICE__']" + path[end + 2 :]


def _nearest_provenance(node: etree._Element, stop: etree._Element) -> str:
    cur: etree._Element | None = node
    while cur is not None:
        src = str(cur.get("src") or "").strip().lower()
        if src:
            return KNOWN_PROVENANCE.get(src, "other")
        if cur is stop:
            break
        cur = cur.getparent()
    return "unmarked"


def _semantic_category(key: str) -> str:
    text = key.lower()
    if "/deviceconfig/high-availability" in text or "/high-availability/" in text:
        return "ha"
    if "/deviceconfig/system/dns" in text or "/dns-setting" in text:
        return "dns"
    if "/deviceconfig/system/ntp" in text or "/ntp-servers" in text:
        return "ntp"
    if "/deviceconfig/system" in text:
        return "system"
    if "/network/interface" in text:
        return "interfaces"
    if "/network/virtual-router" in text or "/routing-table" in text:
        return "routing"
    if "/network/ike" in text or "/network/ipsec" in text or "/global-protect" in text:
        return "vpn"
    if "/log-settings" in text or "/syslog" in text or "/snmp" in text:
        return "logging"
    if "/profiles/" in text or "/profile-setting" in text:
        return "profiles"
    if "/vsys" in text:
        return "vsys"
    return "other"


def _extract_scalar_facts(
    content: bytes,
    *,
    interested_keys: set[str] | None = None,
) -> dict[str, Any]:
    """Extract hashed scalar facts using the same conservative A4.1 contract.

    Values are never returned. Repeated un-keyed leaves (especially ``member``)
    and attribute-only leaves are omitted because their merge semantics are not
    generically scalar. Named ``entry`` identity remains in the comparison key.
    """

    root = _safe_xml(content)
    if _local_name(root.tag) != "config":
        raise ValueError("Expected PAN <config> root")

    facts: dict[str, dict[str, Any]] = {}
    total_leaf_nodes = 0
    omitted_collection = 0
    omitted_attribute_only = 0
    scalar_fact_count = 0

    # Template-Stack scalar intent compiled by A4.1 lives under /config/devices.
    # Effective-running can also contain a very large Panorama policy expansion;
    # skip that unrelated subtree when all requested keys are device/network
    # settings. This keeps A4.2 cost proportional to the relevant config plane.
    scan_roots: list[etree._Element] = [root]
    scope_optimized = False
    if interested_keys and all(key.startswith("/config/devices/") for key in interested_keys):
        device_roots = [node for node in root if isinstance(node.tag, str) and _local_name(node.tag) == "devices"]
        if device_roots:
            scan_roots = device_roots
            scope_optimized = True

    for scan_root in scan_roots:
      for node in scan_root.iter():
        if not isinstance(node.tag, str) or len(node) != 0:
            continue
        total_leaf_nodes += 1
        tag = _local_name(node.tag)
        parent = node.getparent()
        repeated_same_tag = False
        if parent is not None:
            same = [
                child
                for child in parent
                if isinstance(child.tag, str) and _local_name(child.tag) == tag
            ]
            repeated_same_tag = len(same) > 1 and not _name(node)
        if tag == "member" or repeated_same_tag:
            omitted_collection += 1
            continue

        text = str(node.text or "").strip()
        value_attributes = {
            key: value
            for key, value in node.attrib.items()
            if key not in {"name", "src"}
        }
        if value_attributes and not text:
            omitted_attribute_only += 1
            continue

        key = alignment_key_for_node(node, root)
        scalar_fact_count += 1
        if interested_keys is not None and key not in interested_keys:
            continue
        value = text if text else "__PRESENT__"
        facts[key] = {
            "alignment_key": key,
            "path_sha256": _sha256_text(key),
            "value_sha256": _sha256_text(value),
            "value_kind": "presence" if not text else "scalar",
            "provenance": _nearest_provenance(node, root),
            "category": _semantic_category(key),
        }

    return {
        "facts": facts,
        "total_leaf_nodes": total_leaf_nodes,
        "scalar_fact_count": scalar_fact_count,
        "selected_fact_count": len(facts),
        "omitted_collection_leaves": omitted_collection,
        "omitted_attribute_only_leaves": omitted_attribute_only,
        "scope_optimized_to_devices_subtree": scope_optimized,
        "raw_values_included": False,
    }




_VSYS_SELECTOR_RE = re.compile(r"/vsys/entry\[@name='([^']+)'\]")
_VSYS_INTERNAL_ID_RE = re.compile(r"^vsys[0-9]+$", re.IGNORECASE)


def _extract_vsys_identity_map(content: bytes | None) -> dict[str, Any]:
    """Build a per-firewall VSYS ID <-> display-name map from direct config.

    PAN-OS stores the stable/internal VSYS identifier in the ``entry @name``
    (for example ``vsys1``) and can expose a human-readable ``display-name``.
    Panorama Template XML can reference the human-readable identity while the
    firewall effective tree uses the internal ID. Those representations must be
    normalized before a string/hash mismatch can become an override finding.

    Raw names are used only in-memory for the current device and are never
    returned in support-safe telemetry.
    """
    if not content:
        return {
            "id_to_display": {},
            "display_to_id": {},
            "ambiguous_display_names": set(),
            "entry_count": 0,
        }
    root = _safe_xml(content)
    if _local_name(root.tag) != "config":
        return {
            "id_to_display": {},
            "display_to_id": {},
            "ambiguous_display_names": set(),
            "entry_count": 0,
        }

    id_to_display: dict[str, str] = {}
    display_candidates: dict[str, set[str]] = defaultdict(set)
    entries = root.xpath(
        "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='vsys']/*[local-name()='entry']"
    )
    for entry in entries:
        vsys_id = str(entry.get("name") or "").strip()
        if not vsys_id:
            continue
        display = str(entry.findtext("display-name") or "").strip()
        if display:
            id_to_display[vsys_id] = display
            display_candidates[display].add(vsys_id)
        else:
            id_to_display.setdefault(vsys_id, vsys_id)
            display_candidates[vsys_id].add(vsys_id)

    ambiguous = {name for name, ids in display_candidates.items() if len(ids) != 1}
    display_to_id = {
        name: next(iter(ids))
        for name, ids in display_candidates.items()
        if len(ids) == 1
    }
    return {
        "id_to_display": id_to_display,
        "display_to_id": display_to_id,
        "ambiguous_display_names": ambiguous,
        "entry_count": len(entries),
    }


def _canonicalize_expected_vsys_key(key: str, identity_map: dict[str, Any]) -> tuple[str, bool]:
    """Replace logical VSYS selectors with the direct firewall internal ID.

    Only a unique display-name -> internal-ID mapping is accepted. Unknown or
    ambiguous identities are deliberately left unchanged.
    """
    display_to_id = identity_map.get("display_to_id") or {}
    changed = False

    def repl(match: re.Match[str]) -> str:
        nonlocal changed
        name = match.group(1).replace("&apos;", "'")
        if _VSYS_INTERNAL_ID_RE.fullmatch(name):
            return match.group(0)
        mapped = display_to_id.get(name)
        if not mapped:
            return match.group(0)
        changed = True
        escaped = str(mapped).replace("'", "&apos;")
        return f"/vsys/entry[@name='{escaped}']"

    return _VSYS_SELECTOR_RE.sub(repl, key), changed


def _vsys_identity_hash_equivalent(
    expected_hash: str | None,
    actual_hash: str | None,
    identity_map: dict[str, Any],
) -> bool:
    """Return True when hashes represent the same VSYS ID/display-name pair."""
    if not expected_hash or not actual_hash:
        return False
    for vsys_id, display in (identity_map.get("id_to_display") or {}).items():
        id_hash = _sha256_text(str(vsys_id))
        display_hash = _sha256_text(str(display))
        if (expected_hash == display_hash and actual_hash == id_hash) or (
            expected_hash == id_hash and actual_hash == display_hash
        ):
            return True
    return False


def _sync_state(panorama_sync: dict[str, Any] | None) -> tuple[bool, bool]:
    sync = panorama_sync or {}
    # A4.2 aligns Template-Stack device/network scalar settings. Shared-policy
    # sync belongs to the later Device-Group policy alignment plane and must not
    # be used to classify a template setting as drift/out-of-sync.
    template = sync.get("panorama_template_sync")
    out = template == "out_of_sync"
    known = template in {"in_sync", "out_of_sync"}
    return out, known


def _classification_row(
    expected: dict[str, Any],
    *,
    effective: dict[str, Any] | None,
    merged: dict[str, Any] | None,
    active: dict[str, Any] | None,
    panorama_out_of_sync: bool,
    sync_known: bool,
    vsys_identity_map: dict[str, Any] | None = None,
) -> dict[str, Any]:
    key = expected.get("alignment_key") or normalize_compiler_path(str(expected.get("path") or ""))
    policy = semantic_policy_for_setting(key, source_kind=expected.get("source_kind"))
    vsys_identity_map = vsys_identity_map or {}
    base = {
        "alignment_key": key,
        "path_sha256": expected.get("alignment_key_sha256") or _sha256_text(key),
        "category": _semantic_category(key),
        "expected_value_sha256": expected.get("value_sha256"),
        "expected_value_kind": expected.get("value_kind"),
        "expected_source_kind": expected.get("source_kind"),
        "expected_source_name": expected.get("source_name"),
        "expected_source_priority": expected.get("source_priority"),
        "expected_source_alignment_key": expected.get("source_alignment_key") or key,
        "expected_source_confidence": policy.expected_source_confidence,
        "semantic_policy": policy.policy,
        "identity_path_normalized": bool(expected.get("identity_path_normalized")),
        "identity_value_normalized": False,
        "directly_comparable": policy.directly_comparable,
        "override_eligible": policy.override_eligible,
        "drift_eligible": policy.drift_eligible,
        "effective_value_sha256": (effective or {}).get("value_sha256"),
        "merged_value_sha256": (merged or {}).get("value_sha256"),
        "active_value_sha256": (active or {}).get("value_sha256"),
        "effective_provenance": (effective or {}).get("provenance"),
        "raw_values_included": False,
    }

    if not expected.get("alignment_ready"):
        return {
            **base,
            "classification": "UNKNOWN",
            "reason": "expected_value_requires_resolution",
            "confidence": "none",
        }

    if effective is None:
        if panorama_out_of_sync:
            classification = "PANORAMA_OUT_OF_SYNC"
            reason = "expected_scalar_not_observed_and_panorama_reports_out_of_sync"
            confidence = "medium"
        else:
            classification = "EXPECTED_ONLY"
            reason = "expected_scalar_not_observed_in_effective_running"
            confidence = "medium" if sync_known else "low"
        return {**base, "classification": classification, "reason": reason, "confidence": confidence}

    if expected.get("value_sha256") == effective.get("value_sha256"):
        return {
            **base,
            "classification": "ALIGNED",
            "reason": "expected_and_effective_scalar_hash_match",
            "confidence": "high" if policy.directly_comparable else "medium",
        }

    # VSYS identity is a typed semantic value, not a free-form string. PAN-OS
    # commonly exposes an internal ID (vsysN) on the firewall and a friendly
    # display name in Panorama/Template context. Resolve the pair before any
    # mismatch policy can fire. This specifically prevents false LOCAL_OVERRIDE
    # findings such as "Friendly-VSYS-A" vs "vsys1" when they are the same VSYS.
    if policy.policy == "IDENTITY_TRANSLATION_REQUIRED" and _vsys_identity_hash_equivalent(
        expected.get("value_sha256"), effective.get("value_sha256"), vsys_identity_map
    ):
        return {
            **base,
            "classification": "ALIGNED",
            "reason": "vsys_internal_id_and_display_name_resolve_to_same_identity",
            "confidence": "high",
            "identity_value_normalized": True,
        }

    # A4.2.2 semantic guardrails take precedence over generic mismatch logic.
    # A member-relative HA scalar, unverified expected provenance, or unresolved
    # identity representation must never become LOCAL_OVERRIDE/EFFECTIVE_DRIFT.
    if policy.mismatch_classification:
        return {
            **base,
            "classification": policy.mismatch_classification,
            "reason": policy.reason,
            "confidence": "medium" if policy.mismatch_classification == "MEMBER_SPECIFIC" else "none",
        }

    if policy.expected_source_confidence not in TRUSTED_EXPECTED_SOURCE_CONFIDENCE:
        return {
            **base,
            "classification": "PROVENANCE_UNVERIFIED",
            "reason": "expected_source_confidence_is_not_sufficient_for_override_or_drift_claim",
            "confidence": "none",
        }

    if panorama_out_of_sync:
        return {
            **base,
            "classification": "PANORAMA_OUT_OF_SYNC",
            "reason": "expected_and_effective_differ_while_panorama_reports_out_of_sync",
            "confidence": "high",
        }

    active_matches_effective = bool(
        active
        and active.get("value_sha256")
        and active.get("value_sha256") == effective.get("value_sha256")
    )
    merged_matches_effective = bool(
        merged
        and merged.get("value_sha256")
        and merged.get("value_sha256") == effective.get("value_sha256")
    )

    if active_matches_effective and policy.override_eligible:
        return {
            **base,
            "classification": "LOCAL_OVERRIDE",
            "reason": "effective_differs_from_expected_and_matches_local_active_scalar",
            "confidence": "high" if merged_matches_effective else "medium",
        }

    if sync_known and merged_matches_effective and policy.drift_eligible:
        return {
            **base,
            "classification": "EFFECTIVE_DRIFT",
            "reason": "effective_and_merged_match_but_differ_from_compiled_expected_without_local_active_explanation",
            "confidence": "medium",
        }

    return {
        **base,
        "classification": "UNKNOWN",
        "reason": "expected_and_effective_differ_but_available_evidence_does_not_prove_override_or_drift",
        "confidence": "low",
    }


def align_expected_to_effective(
    *,
    serial: str,
    expected_compiler: dict[str, Any] | None,
    expected_row: dict[str, Any] | None,
    effective_content: bytes | None,
    merged_content: bytes | None = None,
    active_content: bytes | None = None,
    panorama_sync: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Align compiled Template-Stack scalar intent to direct effective-running.

    The engine deliberately limits itself to A4.1 alignment-ready scalar facts.
    It does not compare rule/member collections, does not resolve template
    variables, and does not claim that expected-only values are necessarily
    drift (mode-specific applicability can legitimately suppress a setting).
    """

    expected_row = expected_row or {}
    if not expected_compiler or not effective_content:
        return {
            "schema_version": SETTING_ALIGNMENT_SCHEMA_VERSION,
            "status": "insufficient_evidence",
            "device_status": "INSUFFICIENT_EVIDENCE",
            "reason": "expected_compiler_or_effective_running_unavailable",
            "summary": {
                "expected_settings": 0,
                "alignment_ready_settings": 0,
                "evaluated_settings": 0,
                "classification_counts": {"INSUFFICIENT_EVIDENCE": 1},
            },
            "results": [],
            "raw_values_included": False,
        }

    stack_name = expected_row.get("primary_template_stack")
    stack = ((expected_compiler.get("template_stacks") or {}).get(stack_name) or {})
    source_manifest = list(stack.get("manifest") or [])
    if not stack_name or not source_manifest:
        return {
            "schema_version": SETTING_ALIGNMENT_SCHEMA_VERSION,
            "status": "insufficient_evidence",
            "device_status": "INSUFFICIENT_EVIDENCE",
            "reason": "single_template_stack_manifest_unavailable",
            "summary": {
                "expected_settings": len(source_manifest),
                "alignment_ready_settings": 0,
                "evaluated_settings": 0,
                "classification_counts": {"INSUFFICIENT_EVIDENCE": 1},
            },
            "results": [],
            "raw_values_included": False,
        }

    # Per-device identity normalization must happen after direct effective config
    # is available. A Template may use the VSYS display name while the firewall
    # stores the same object under an internal identifier such as vsys5.
    vsys_identity_map = _extract_vsys_identity_map(effective_content)
    manifest: list[dict[str, Any]] = []
    for source_item in source_manifest:
        item = dict(source_item)
        key = item.get("alignment_key") or normalize_compiler_path(str(item.get("path") or ""))
        item["source_alignment_key"] = str(key)
        canonical_key, identity_path_normalized = _canonicalize_expected_vsys_key(str(key), vsys_identity_map)
        item["alignment_key"] = canonical_key
        item["alignment_key_sha256"] = _sha256_text(canonical_key)
        item["identity_path_normalized"] = identity_path_normalized
        manifest.append(item)

    interested = {str(item["alignment_key"]) for item in manifest if item.get("alignment_key")}
    # Local active is small. Parse it first so the effective/merged extractors
    # can also sample local-only keys without scanning or storing the entire
    # effective configuration fact universe.
    active_all = _extract_scalar_facts(active_content, interested_keys=None) if active_content else {"facts": {}}
    comparison_keys = interested | set((active_all.get("facts") or {}).keys())
    effective_index = _extract_scalar_facts(effective_content, interested_keys=comparison_keys)
    merged_index = _extract_scalar_facts(merged_content, interested_keys=comparison_keys) if merged_content else {"facts": {}}
    active_expected = {
        key: value for key, value in (active_all.get("facts") or {}).items() if key in interested
    }

    panorama_out, sync_known = _sync_state(panorama_sync)
    results: list[dict[str, Any]] = []
    for expected in manifest:
        key = str(expected.get("alignment_key") or "")
        results.append(
            _classification_row(
                expected,
                effective=(effective_index.get("facts") or {}).get(key),
                merged=(merged_index.get("facts") or {}).get(key),
                active=active_expected.get(key),
                panorama_out_of_sync=panorama_out,
                sync_known=sync_known,
                vsys_identity_map=vsys_identity_map,
            )
        )

    # Local-only is informational, not a drift finding. It is useful for
    # distinguishing firewall-specific local configuration from Panorama intent.
    local_only_rows: list[dict[str, Any]] = []
    for key, fact in (active_all.get("facts") or {}).items():
        if key in interested:
            continue
        effective_local = (effective_index.get("facts") or {}).get(key)
        merged_local = (merged_index.get("facts") or {}).get(key)
        if effective_local is None:
            local_effective_state = "not_observed"
        elif effective_local.get("value_sha256") == fact.get("value_sha256"):
            local_effective_state = "effective_same"
        else:
            local_effective_state = "effective_different"
        local_only_rows.append({
            "alignment_key": key,
            "path_sha256": fact.get("path_sha256"),
            "category": fact.get("category"),
            "classification": "LOCAL_ONLY",
            "reason": "local_active_scalar_has_no_compiled_template_stack_counterpart",
            "confidence": "high",
            "local_effective_state": local_effective_state,
            "active_value_sha256": fact.get("value_sha256"),
            "effective_value_sha256": (effective_local or {}).get("value_sha256"),
            "merged_value_sha256": (merged_local or {}).get("value_sha256"),
            "expected_value_sha256": None,
            "expected_source_kind": None,
            "expected_source_name": None,
            "expected_source_priority": None,
            "effective_provenance": None,
            "raw_values_included": False,
        })
    results.extend(local_only_rows)

    counts = Counter(str(row.get("classification") or "UNKNOWN") for row in results)
    category_counts: dict[str, Counter[str]] = defaultdict(Counter)
    for row in results:
        category_counts[str(row.get("category") or "other")][str(row.get("classification") or "UNKNOWN")] += 1

    if counts["PANORAMA_OUT_OF_SYNC"]:
        device_status = "PANORAMA_OUT_OF_SYNC"
    elif counts["EFFECTIVE_DRIFT"]:
        device_status = "EFFECTIVE_DRIFT"
    elif counts["LOCAL_OVERRIDE"]:
        device_status = "LOCAL_OVERRIDE"
    elif counts["EXPECTED_ONLY"] or counts["UNKNOWN"] or counts["PROVENANCE_UNVERIFIED"] or counts["IDENTITY_TRANSLATION_REQUIRED"]:
        device_status = "ALIGNED_WITH_COVERAGE_GAPS" if counts["ALIGNED"] else "UNKNOWN"
    elif counts["MEMBER_SPECIFIC"]:
        device_status = "ALIGNED_WITH_SEMANTIC_EXCLUSIONS" if counts["ALIGNED"] else "SEMANTIC_EXCLUSIONS_ONLY"
    else:
        device_status = "ALIGNED"

    ready = sum(1 for item in manifest if item.get("alignment_ready"))
    evaluated = sum(
        1
        for row in results[: len(manifest)]
        if row.get("classification") not in {"UNKNOWN"}
    )
    value_compared = sum(
        1
        for row in results[: len(manifest)]
        if row.get("classification") in {"ALIGNED", "LOCAL_OVERRIDE", "EFFECTIVE_DRIFT", "PANORAMA_OUT_OF_SYNC", "EXPECTED_ONLY"}
    )
    semantic_exclusions = sum(
        int(counts.get(name, 0))
        for name in ("MEMBER_SPECIFIC", "PROVENANCE_UNVERIFIED", "IDENTITY_TRANSLATION_REQUIRED")
    )
    identity_path_normalized = sum(1 for item in manifest if item.get("identity_path_normalized"))
    identity_value_normalized = sum(1 for row in results[: len(manifest)] if row.get("identity_value_normalized"))
    observed_expected = sum(
        1
        for row in results[: len(manifest)]
        if row.get("effective_value_sha256") is not None
    )
    summary = {
        "expected_settings": len(manifest),
        "alignment_ready_settings": ready,
        "evaluated_settings": evaluated,
        "value_compared_settings": value_compared,
        "semantic_exclusion_settings": semantic_exclusions,
        "expected_settings_observed_in_effective": observed_expected,
        "local_only_settings": len(local_only_rows),
        "classification_counts": dict(sorted(counts.items())),
        "category_counts": {
            category: dict(sorted(counter.items()))
            for category, counter in sorted(category_counts.items())
        },
        "coverage_percent": round((evaluated / ready * 100.0), 3) if ready else 0.0,
        "value_comparison_coverage_percent": round((value_compared / ready * 100.0), 3) if ready else 0.0,
        "observed_percent": round((observed_expected / ready * 100.0), 3) if ready else 0.0,
        "vsys_identity_map_entries": int(vsys_identity_map.get("entry_count") or 0),
        "identity_path_normalized_settings": identity_path_normalized,
        "identity_value_normalized_settings": identity_value_normalized,
        "findings": {
            "local_override": counts["LOCAL_OVERRIDE"],
            "effective_drift": counts["EFFECTIVE_DRIFT"],
            "panorama_out_of_sync": counts["PANORAMA_OUT_OF_SYNC"],
            "expected_only": counts["EXPECTED_ONLY"],
            "unknown": counts["UNKNOWN"],
            "local_only": counts["LOCAL_ONLY"],
            "member_specific": counts["MEMBER_SPECIFIC"],
            "provenance_unverified": counts["PROVENANCE_UNVERIFIED"],
            "identity_translation_required": counts["IDENTITY_TRANSLATION_REQUIRED"],
        },
    }

    engine_status = "success" if merged_content is not None and active_content is not None else "partial"
    return {
        "schema_version": SETTING_ALIGNMENT_SCHEMA_VERSION,
        "status": engine_status,
        "device_status": device_status,
        "reason": (
            "compiled_template_stack_scalar_intent_compared_to_direct_effective_running"
            if engine_status == "success"
            else "effective_running_compared_but_active_or_merged_alignment_evidence_is_incomplete"
        ),
        "serial": serial,
        "summary": summary,
        "engine_contract": {
            "scope": "template_stack_alignment_ready_scalar_settings",
            "primary_actual": "direct_firewall_effective_running",
            "local_override_proof": "directly-comparable trusted expected provenance; expected differs; local-active hash equals effective hash",
            "effective_drift_proof": "directly-comparable trusted expected provenance; expected differs; no local-active explanation; merged hash equals effective hash; Panorama sync known",
            "semantic_policy": semantic_policy_contract(),
            "vsys_identity_normalization": "direct effective /config/devices/entry/vsys entry @name (internal ID) mapped to display-name before path/value comparison",
            "identity_equivalent_is_override": False,
            "expected_only_is_drift": False,
            "local_only_is_drift": False,
            "template_variables_resolved": False,
            "collection_list_semantics_compared": False,
            "policy_rule_values_compared": False,
            "raw_values_stored": False,
        },
        "source_coverage": {
            "vsys_identity": {
                "map_entries": int(vsys_identity_map.get("entry_count") or 0),
                "unique_display_name_mappings": len(vsys_identity_map.get("display_to_id") or {}),
                "ambiguous_display_names": len(vsys_identity_map.get("ambiguous_display_names") or set()),
                "raw_names_included": False,
            },
            "effective": {key: value for key, value in effective_index.items() if key != "facts"},
            "merged": {key: value for key, value in merged_index.items() if key != "facts"},
            "active": {key: value for key, value in active_all.items() if key != "facts"},
        },
        "results": sorted(
            results,
            key=lambda row: (
                str(row.get("classification") or ""),
                str(row.get("category") or ""),
                str(row.get("alignment_key") or ""),
            ),
        ),
        "raw_values_included": False,
    }
