from __future__ import annotations

import hashlib
from collections import Counter, defaultdict
from typing import Any

from lxml import etree


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
    parser = etree.XMLParser(resolve_entities=False, no_network=True, recover=False)
    return etree.fromstring(content, parser=parser)


def _hash_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def analyze_provenance_markers(content: bytes) -> dict[str, Any]:
    """Return value-free provenance telemetry from a PAN configuration tree.

    PAN-OS configuration XML may annotate nodes with a ``src`` attribute. Palo
    Alto documents ``src=tpl`` in its template-override API example. We count
    only recognized source categories and never export source values that are
    not on the allow-list.
    """

    root = _safe_xml(content)
    counts: Counter[str] = Counter()
    src_nodes = 0
    entry_nodes = 0
    leaf_nodes = 0
    for node in root.iter():
        if not isinstance(node.tag, str):
            continue
        if _local_name(node.tag) == "entry":
            entry_nodes += 1
        if len(node) == 0:
            leaf_nodes += 1
        src = str(node.get("src") or "").strip().lower()
        if src:
            src_nodes += 1
            counts[KNOWN_PROVENANCE.get(src, "other")] += 1
    return {
        "nodes": sum(1 for node in root.iter() if isinstance(node.tag, str)),
        "entries": entry_nodes,
        "leaves": leaf_nodes,
        "nodes_with_src": src_nodes,
        "source_categories": {
            "template": counts["template"],
            "device_group": counts["device_group"],
            "shared": counts["shared"],
            "local": counts["local"],
            "other": counts["other"],
        },
        "raw_src_values_included": False,
        "configuration_values_included": False,
    }


def _device_names(entry: etree._Element) -> list[str]:
    values = []
    for device in entry.xpath("./devices/entry"):
        name = str(device.get("name") or "").strip()
        if name:
            values.append(name)
    return values


def analyze_panorama_intent(content: bytes) -> dict[str, Any]:
    """Map Panorama template-stack/device-group assignments without compiling intent.

    This intentionally does *not* claim to reproduce Panorama's complete
    inheritance/precedence engine. It inventories the active Panorama control
    configuration and maps firewall serial assignments so later alignment logic
    has explicit provenance rather than inferring it from a firewall snapshot.
    """

    root = _safe_xml(content)
    if _local_name(root.tag) != "config":
        raise ValueError("Expected Panorama <config> root")

    template_names: set[str] = set()
    stack_rows: list[dict[str, Any]] = []
    group_rows: list[dict[str, Any]] = []
    by_serial: dict[str, dict[str, Any]] = defaultdict(
        lambda: {"template_stacks": [], "device_groups": []}
    )

    for controller in root.xpath("./devices/entry"):
        for template in controller.xpath("./template/entry"):
            name = str(template.get("name") or "").strip()
            if name:
                template_names.add(name)

        for stack in controller.xpath("./template-stack/entry"):
            stack_name = str(stack.get("name") or "").strip()
            templates = [
                str(member.text or "").strip()
                for member in stack.xpath("./templates/member")
                if str(member.text or "").strip()
            ]
            devices = _device_names(stack)
            stack_level_config = stack.find("config") is not None
            row = {
                "name": stack_name,
                "templates": templates,
                "devices": devices,
                "stack_level_config_present": stack_level_config,
            }
            stack_rows.append(row)
            for serial in devices:
                by_serial[serial]["template_stacks"].append({
                    "name": stack_name,
                    "templates": templates,
                    "stack_level_config_present": stack_level_config,
                })

        for group in controller.xpath("./device-group/entry"):
            group_name = str(group.get("name") or "").strip()
            parent = str(group.findtext("parent-dg") or "").strip() or None
            devices = []
            for device in group.xpath("./devices/entry"):
                serial = str(device.get("name") or "").strip()
                if not serial:
                    continue
                vsys = [
                    str(v.get("name") or "").strip()
                    for v in device.xpath("./vsys/entry")
                    if str(v.get("name") or "").strip()
                ]
                devices.append({"serial": serial, "vsys": vsys})
                by_serial[serial]["device_groups"].append({
                    "name": group_name,
                    "parent": parent,
                    "vsys": vsys,
                })
            group_rows.append({
                "name": group_name,
                "parent": parent,
                "devices": devices,
            })

    assigned_serials = set(by_serial)
    return {
        "schema_version": "0.6.0A4",
        "templates": sorted(template_names),
        "template_stacks": stack_rows,
        "device_groups": group_rows,
        "by_serial": dict(by_serial),
        "summary": {
            "templates": len(template_names),
            "template_stacks": len(stack_rows),
            "device_groups": len(group_rows),
            "assigned_serials": len(assigned_serials),
            "shared_present": root.find("shared") is not None,
            "panorama_section_present": root.find("panorama") is not None,
        },
        "compiled_expected_config": False,
        "compiled_expected_reason": "template_stack_precedence_and_device_group_inheritance_not_yet_compiled",
    }


def assignment_for_serial(intent: dict[str, Any] | None, serial: str) -> dict[str, Any]:
    if not intent:
        return {"template_stacks": [], "device_groups": [], "assignment_status": "unknown"}
    row = ((intent.get("by_serial") or {}).get(serial) or {})
    stacks = list(row.get("template_stacks") or [])
    groups = list(row.get("device_groups") or [])
    if stacks or groups:
        status = "mapped"
    else:
        status = "not_found"
    return {
        "template_stacks": stacks,
        "device_groups": groups,
        "assignment_status": status,
    }


def alignment_profile(
    *,
    panorama_sync: dict[str, Any],
    assignment: dict[str, Any],
    direct: dict[str, Any],
    comparison: dict[str, Any],
) -> dict[str, Any]:
    """Classify evidence without over-claiming local overrides.

    A4 distinguishes observed differences from proven override/drift. Exact
    setting-level override classification requires compiled Panorama intent.
    """

    effective_ok = ((direct.get("effective") or {}).get("status") == "success")
    merged_ok = ((direct.get("merged") or {}).get("status") == "success")
    active_ok = ((direct.get("active") or {}).get("status") == "success")
    panorama_out = bool(panorama_sync.get("panorama_reports_out_of_sync"))
    sync_known = any(
        panorama_sync.get(key) in {"in_sync", "out_of_sync"}
        for key in ("panorama_shared_policy_sync", "panorama_template_sync")
    )
    merged_effective = (comparison.get("direct_merged_vs_effective") or {})
    active_merged = (comparison.get("direct_active_vs_merged") or {})

    if not effective_ok:
        status = "INSUFFICIENT_EVIDENCE"
        reason = "effective_running_unavailable"
    elif panorama_out:
        status = "PANORAMA_OUT_OF_SYNC"
        reason = "panorama_reports_out_of_sync"
    elif not merged_ok:
        status = "INSUFFICIENT_EVIDENCE"
        reason = "merged_config_unavailable"
    elif merged_effective.get("available") and merged_effective.get("exact_canonical_match") is True:
        status = "CANONICALLY_ALIGNED"
        reason = "merged_and_effective_canonical_match"
    elif merged_effective.get("available"):
        status = "DIFFERENCE_OBSERVED"
        reason = "merged_and_effective_differ; fact_level_provenance_required"
    elif not sync_known:
        status = "UNKNOWN"
        reason = "panorama_sync_unknown"
    else:
        status = "UNKNOWN"
        reason = "comparison_unavailable"

    local_difference = (
        active_merged.get("available") is True
        and active_merged.get("exact_canonical_match") is False
    )
    return {
        "status": status,
        "reason": reason,
        "primary_evidence_status": "success" if effective_ok else "failed",
        "alignment_evidence_status": "complete" if effective_ok and merged_ok else "partial",
        "panorama_assignment_status": assignment.get("assignment_status") or "unknown",
        "local_active_available": active_ok,
        "local_vs_merged_difference_observed": local_difference,
        "local_override_status": "NOT_PROVEN",
        "local_override_candidate": bool(local_difference and effective_ok and merged_ok),
        "local_override_reason": "active-vs-merged difference is a candidate only; exact override requires Panorama intent/value provenance",
        "compiled_expected_config": False,
    }
