from __future__ import annotations

import hashlib
from collections import Counter, defaultdict
from typing import Any, Iterable

from lxml import etree

from configuration.pan_setting_alignment import normalize_compiler_path


COMPILER_SCHEMA_VERSION = "0.6.0A4.2"
RULEBASE_TYPES = (
    "security",
    "nat",
    "qos",
    "pbf",
    "decryption",
    "application-override",
    "captive-portal",
    "dos",
)


def _local_name(tag: Any) -> str:
    if not isinstance(tag, str):
        return ""
    return tag.rsplit("}", 1)[-1]


def _safe_xml(content: bytes) -> etree._Element:
    parser = etree.XMLParser(resolve_entities=False, no_network=True, recover=False, huge_tree=True)
    return etree.fromstring(content, parser=parser)


def _sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _name(node: etree._Element) -> str:
    return str(node.get("name") or "").strip()


def _path_component(node: etree._Element) -> str:
    tag = _local_name(node.tag)
    name = _name(node)
    if name:
        # Local-only manifest: names remain available so A4.2 can map the same
        # setting on direct effective-running. They never enter the support ZIP.
        escaped = name.replace("'", "&apos;")
        return f"{tag}[@name='{escaped}']"
    return tag


def _node_path(node: etree._Element, stop: etree._Element) -> str:
    parts: list[str] = []
    cur: etree._Element | None = node
    while cur is not None:
        parts.append(_path_component(cur))
        if cur is stop:
            break
        cur = cur.getparent()
    return "/" + "/".join(reversed(parts))


def _is_variable_reference(value: str) -> bool:
    # PAN template variables are represented with a leading '$'. Do not try to
    # resolve them in A4.1 because per-firewall/stack variable precedence needs
    # an explicit compiler stage of its own.
    return value.strip().startswith("$")


def _flatten_scalar_settings(config: etree._Element | None) -> dict[str, Any]:
    """Extract precedence-safe scalar leaves from a template/stack config.

    We deliberately do not pretend that arbitrary XML list semantics can be
    reconstructed generically. Repeated un-keyed leaves (especially <member>)
    are inventoried but excluded from the alignment-ready scalar manifest.
    Named <entry> nodes are safe because their identity is explicit in the path.
    """

    if config is None:
        return {
            "settings": {},
            "total_leaf_nodes": 0,
            "compilable_scalar_leaves": 0,
            "omitted_collection_leaves": 0,
            "omitted_attribute_only_leaves": 0,
            "variable_reference_settings": 0,
        }

    settings: dict[str, dict[str, Any]] = {}
    total_leaf_nodes = 0
    omitted_collection = 0
    omitted_attribute_only = 0
    variable_refs = 0

    for node in config.iter():
        if not isinstance(node.tag, str) or len(node) != 0:
            continue
        total_leaf_nodes += 1
        tag = _local_name(node.tag)
        parent = node.getparent()
        repeated_same_tag = False
        if parent is not None:
            same = [child for child in parent if isinstance(child.tag, str) and _local_name(child.tag) == tag]
            repeated_same_tag = len(same) > 1 and not _name(node)

        # PAN member lists and any repeated un-keyed leaf are not scalar merge
        # units. Their list replacement/union semantics vary by setting.
        if tag == "member" or repeated_same_tag:
            omitted_collection += 1
            continue

        text = str(node.text or "").strip()
        # Attribute-only leaf semantics are not safe to compile generically.
        value_attributes = {k: v for k, v in node.attrib.items() if k not in {"name", "src"}}
        if value_attributes and not text:
            omitted_attribute_only += 1
            continue

        path = _node_path(node, config)
        alignment_key = normalize_compiler_path(path)
        value = text if text else "__PRESENT__"
        variable = _is_variable_reference(text)
        if variable:
            variable_refs += 1
        settings[path] = {
            "path": path,
            "path_sha256": _sha256_text(path),
            "alignment_key": alignment_key,
            "alignment_key_sha256": _sha256_text(alignment_key),
            "value_sha256": _sha256_text(value),
            "value_kind": "variable_reference" if variable else ("presence" if not text else "scalar"),
            "alignment_ready": not variable,
        }

    return {
        "settings": settings,
        "total_leaf_nodes": total_leaf_nodes,
        "compilable_scalar_leaves": len(settings),
        "omitted_collection_leaves": omitted_collection,
        "omitted_attribute_only_leaves": omitted_attribute_only,
        "variable_reference_settings": variable_refs,
    }


def _source_row(kind: str, name: str, config: etree._Element | None, priority: int) -> dict[str, Any]:
    flattened = _flatten_scalar_settings(config)
    return {
        "kind": kind,
        "name": name,
        "priority": priority,
        **flattened,
    }


def _compile_template_stack(
    stack: etree._Element,
    template_catalog: dict[str, etree._Element],
) -> dict[str, Any]:
    stack_name = _name(stack)
    template_order = [
        str(member.text or "").strip()
        for member in stack.xpath("./templates/member")
        if str(member.text or "").strip()
    ]
    assigned_serials = [
        _name(device)
        for device in stack.xpath("./devices/entry")
        if _name(device)
    ]

    # Vendor precedence: stack-level overrides are above inherited templates;
    # within the stack, the list is top-to-bottom / high-to-low priority.
    sources: list[dict[str, Any]] = []
    stack_config = stack.find("config")
    if stack_config is not None:
        sources.append(_source_row("template_stack_override", stack_name, stack_config, 0))

    missing_templates: list[str] = []
    for offset, template_name in enumerate(template_order, start=1):
        template = template_catalog.get(template_name)
        if template is None:
            missing_templates.append(template_name)
            continue
        sources.append(_source_row("template", template_name, template.find("config"), offset))

    compiled: dict[str, dict[str, Any]] = {}
    shadowed = 0
    shadowed_by_kind: Counter[str] = Counter()
    contribution_counts: Counter[str] = Counter()
    source_stats: list[dict[str, Any]] = []
    for source in sources:
        selected_from_source = 0
        shadowed_from_source = 0
        for path, setting in source["settings"].items():
            if path in compiled:
                shadowed += 1
                shadowed_from_source += 1
                shadowed_by_kind[source["kind"]] += 1
                continue
            compiled[path] = {
                **setting,
                "source_kind": source["kind"],
                "source_name": source["name"],
                "source_priority": source["priority"],
            }
            selected_from_source += 1
            contribution_counts[source["kind"]] += 1
        source_stats.append({
            "kind": source["kind"],
            "name": source["name"],
            "priority": source["priority"],
            "candidate_scalar_settings": len(source["settings"]),
            "selected_settings": selected_from_source,
            "shadowed_settings": shadowed_from_source,
            "total_leaf_nodes": source["total_leaf_nodes"],
            "omitted_collection_leaves": source["omitted_collection_leaves"],
            "omitted_attribute_only_leaves": source["omitted_attribute_only_leaves"],
            "variable_reference_settings": source["variable_reference_settings"],
        })

    manifest = [compiled[key] for key in sorted(compiled)]
    alignment_ready = sum(1 for item in manifest if item["alignment_ready"])
    unresolved_variables = sum(1 for item in manifest if item["value_kind"] == "variable_reference")
    total_leaf_nodes = sum(int(source["total_leaf_nodes"]) for source in sources)
    omitted_collection = sum(int(source["omitted_collection_leaves"]) for source in sources)
    omitted_attribute_only = sum(int(source["omitted_attribute_only_leaves"]) for source in sources)

    status = "compiled"
    reasons: list[str] = []
    if missing_templates:
        status = "partial"
        reasons.append("referenced_template_missing")
    if unresolved_variables:
        status = "partial"
        reasons.append("unresolved_template_variable_references")
    if omitted_collection or omitted_attribute_only:
        # This is a coverage limitation, not a failure of the scalar compiler.
        reasons.append("non_scalar_xml_semantics_omitted")

    return {
        "name": stack_name,
        "status": status,
        "reasons": reasons,
        "precedence_contract": {
            "stack_level_override_first": True,
            "template_order": "listed_top_to_bottom_high_to_low",
            "first_scalar_definition_wins": True,
            "full_panorama_merge_engine_claimed": False,
        },
        "template_order": template_order,
        "assigned_serials": assigned_serials,
        "missing_templates": missing_templates,
        "stack_level_config_present": stack_config is not None,
        "source_stats": source_stats,
        "compiled_setting_count": len(manifest),
        "alignment_ready_setting_count": alignment_ready,
        "unresolved_variable_setting_count": unresolved_variables,
        "shadowed_setting_count": shadowed,
        "shadowed_by_source_kind": dict(sorted(shadowed_by_kind.items())),
        "source_contribution_counts": dict(sorted(contribution_counts.items())),
        "coverage": {
            "source_leaf_nodes": total_leaf_nodes,
            "compiled_scalar_settings": len(manifest),
            "omitted_collection_leaves": omitted_collection,
            "omitted_attribute_only_leaves": omitted_attribute_only,
        },
        "manifest": manifest,
        "raw_values_in_manifest": False,
    }


def _group_parent(group: etree._Element) -> str | None:
    value = str(group.findtext("parent-dg") or "").strip()
    return value or None


def _rule_counts(container: etree._Element | None) -> dict[str, dict[str, int]]:
    result = {
        "pre": {kind: 0 for kind in RULEBASE_TYPES},
        "post": {kind: 0 for kind in RULEBASE_TYPES},
    }
    if container is None:
        return result
    for side, tag in (("pre", "pre-rulebase"), ("post", "post-rulebase")):
        base = container.find(tag)
        if base is None:
            continue
        for kind in RULEBASE_TYPES:
            result[side][kind] = len(base.xpath(f"./{kind}/rules/entry"))
    return result


def _object_inventory(container: etree._Element | None) -> dict[str, int]:
    """Count named object entries without claiming inheritance precedence.

    Rulebases and administrative metadata are excluded. Exact object override
    compilation is deliberately deferred because Panorama can reverse the
    normal descendant-over-ancestor object precedence globally.
    """
    if container is None:
        return {"object_entries": 0, "object_collections": 0}
    excluded = {"pre-rulebase", "post-rulebase", "devices", "parent-dg"}
    entries = 0
    collections = 0
    for child in container:
        if not isinstance(child.tag, str) or _local_name(child.tag) in excluded:
            continue
        named = child.xpath(".//entry[@name]")
        if named:
            collections += 1
            entries += len(named)
    return {"object_entries": entries, "object_collections": collections}


def _lineage_for_group(
    group_name: str,
    group_catalog: dict[str, etree._Element],
) -> dict[str, Any]:
    low_to_high: list[str] = []
    seen: set[str] = set()
    current = group_name
    missing_parent: str | None = None
    cycle = False
    while current:
        if current in seen:
            cycle = True
            break
        seen.add(current)
        group = group_catalog.get(current)
        if group is None:
            missing_parent = current
            break
        low_to_high.append(current)
        parent = _group_parent(group)
        if not parent or parent.lower() == "shared":
            break
        current = parent
    high_to_low = list(reversed(low_to_high))
    status = "compiled" if not cycle and not missing_parent else "partial"
    return {
        "status": status,
        "group": group_name,
        "lineage_high_to_low": ["Shared", *high_to_low],
        "lineage_low_to_high": [*low_to_high, "Shared"],
        "cycle_detected": cycle,
        "missing_parent": missing_parent,
    }


def _compile_policy_lineage(
    group_name: str,
    group_catalog: dict[str, etree._Element],
    shared: etree._Element | None,
) -> dict[str, Any]:
    lineage = _lineage_for_group(group_name, group_catalog)
    high_to_low_groups = [name for name in lineage["lineage_high_to_low"] if name != "Shared"]
    low_to_high_groups = list(reversed(high_to_low_groups))

    layer_counts: dict[str, Any] = {
        "Shared": {
            "rules": _rule_counts(shared),
            "objects": _object_inventory(shared),
        }
    }
    for name in high_to_low_groups:
        group = group_catalog.get(name)
        layer_counts[name] = {
            "rules": _rule_counts(group),
            "objects": _object_inventory(group),
        }

    def total(side: str, kind: str) -> int:
        return sum(int((layer_counts[name]["rules"][side]).get(kind) or 0) for name in layer_counts)

    return {
        **lineage,
        "policy_evaluation_contract": {
            "pre_rules": ["Shared", *high_to_low_groups, "LOCAL_FIREWALL_RULES"],
            "post_rules": ["LOCAL_FIREWALL_RULES", *low_to_high_groups, "Shared"],
            "local_rule_position": "between_panorama_pre_and_post_rules",
        },
        "layer_counts": layer_counts,
        "expected_panorama_rule_counts": {
            "pre": {kind: total("pre", kind) for kind in RULEBASE_TYPES},
            "post": {kind: total("post", kind) for kind in RULEBASE_TYPES},
        },
        "object_inheritance": {
            "status": "inventory_only",
            "exact_value_precedence_compiled": False,
            "reason": "Panorama can reverse ancestor/descendant object precedence; A4.1 does not infer the global mode from absence of an explicit marker",
        },
    }


def _device_group_assignments(group_catalog: dict[str, etree._Element]) -> dict[str, list[dict[str, Any]]]:
    by_serial: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for group_name, group in group_catalog.items():
        for device in group.xpath("./devices/entry"):
            serial = _name(device)
            if not serial:
                continue
            vsys = [_name(v) for v in device.xpath("./vsys/entry") if _name(v)]
            by_serial[serial].append({
                "device_group": group_name,
                "vsys": vsys,
                "scope": "explicit_vsys" if vsys else "firewall_or_default_vsys",
            })
    return dict(by_serial)


def compile_panorama_expected(content: bytes) -> dict[str, Any]:
    """Compile a conservative, evidence-oriented Panorama expected-state model.

    A4.1 compiles template-stack scalar precedence and device-group policy
    lineage. It intentionally does not claim byte-equivalence with Panorama's
    vendor merge engine, resolve template variables, or compile object override
    values. Those limitations are explicit so A4.2 can align only facts with
    sufficient provenance.
    """

    root = _safe_xml(content)
    if _local_name(root.tag) != "config":
        raise ValueError("Expected Panorama <config> root")

    template_catalog: dict[str, etree._Element] = {}
    stack_catalog: dict[str, etree._Element] = {}
    group_catalog: dict[str, etree._Element] = {}
    for controller in root.xpath("./devices/entry"):
        for template in controller.xpath("./template/entry"):
            if _name(template):
                template_catalog[_name(template)] = template
        for stack in controller.xpath("./template-stack/entry"):
            if _name(stack):
                stack_catalog[_name(stack)] = stack
        for group in controller.xpath("./device-group/entry"):
            if _name(group):
                group_catalog[_name(group)] = group

    compiled_stacks = {
        name: _compile_template_stack(stack, template_catalog)
        for name, stack in sorted(stack_catalog.items())
    }

    stack_by_serial: dict[str, list[str]] = defaultdict(list)
    for name, compiled in compiled_stacks.items():
        for serial in compiled["assigned_serials"]:
            stack_by_serial[serial].append(name)

    dg_by_serial = _device_group_assignments(group_catalog)
    shared = root.find("shared")
    lineage_cache: dict[str, dict[str, Any]] = {}
    for group_name in group_catalog:
        lineage_cache[group_name] = _compile_policy_lineage(group_name, group_catalog, shared)

    all_serials = sorted(set(stack_by_serial) | set(dg_by_serial))
    by_serial: dict[str, dict[str, Any]] = {}
    anomaly_counts: Counter[str] = Counter()
    for serial in all_serials:
        stack_names = list(stack_by_serial.get(serial) or [])
        dg_assignments = list(dg_by_serial.get(serial) or [])
        anomalies: list[str] = []
        if len(stack_names) == 0:
            anomalies.append("template_stack_not_assigned")
        elif len(stack_names) > 1:
            anomalies.append("multiple_template_stack_assignments")
        for stack_name in stack_names:
            if compiled_stacks[stack_name]["missing_templates"]:
                anomalies.append("referenced_template_missing")
        policy_scopes = []
        for assignment in dg_assignments:
            lineage = lineage_cache.get(assignment["device_group"])
            if lineage is None:
                anomalies.append("device_group_lineage_missing")
                continue
            if lineage.get("cycle_detected"):
                anomalies.append("device_group_parent_cycle")
            if lineage.get("missing_parent"):
                anomalies.append("device_group_parent_missing")
            policy_scopes.append({
                **assignment,
                "lineage": lineage,
            })

        for anomaly in set(anomalies):
            anomaly_counts[anomaly] += 1

        primary_stack = compiled_stacks.get(stack_names[0]) if len(stack_names) == 1 else None
        if primary_stack is None:
            template_status = "unmapped" if not stack_names else "ambiguous"
        else:
            template_status = primary_stack["status"]

        by_serial[serial] = {
            "status": "compiled" if primary_stack is not None and primary_stack.get("status") == "compiled" and not anomalies else "partial",
            "template_stack_status": template_status,
            "template_stacks": stack_names,
            "primary_template_stack": stack_names[0] if len(stack_names) == 1 else None,
            "template_expected": None if primary_stack is None else {
                "compiled_setting_count": primary_stack["compiled_setting_count"],
                "alignment_ready_setting_count": primary_stack["alignment_ready_setting_count"],
                "unresolved_variable_setting_count": primary_stack["unresolved_variable_setting_count"],
                "shadowed_setting_count": primary_stack["shadowed_setting_count"],
                "coverage": primary_stack["coverage"],
            },
            "device_group_assignments": dg_assignments,
            "policy_scopes": policy_scopes,
            "anomalies": sorted(set(anomalies)),
        }

    total_settings = sum(int(stack["compiled_setting_count"]) for stack in compiled_stacks.values())
    alignment_ready = sum(int(stack["alignment_ready_setting_count"]) for stack in compiled_stacks.values())
    unresolved_variables = sum(int(stack["unresolved_variable_setting_count"]) for stack in compiled_stacks.values())
    shadowed = sum(int(stack["shadowed_setting_count"]) for stack in compiled_stacks.values())
    missing_refs = sum(len(stack["missing_templates"]) for stack in compiled_stacks.values())
    omitted_collections = sum(int(stack["coverage"]["omitted_collection_leaves"]) for stack in compiled_stacks.values())
    omitted_attributes = sum(int(stack["coverage"]["omitted_attribute_only_leaves"]) for stack in compiled_stacks.values())

    policy_rule_totals = {"pre": Counter(), "post": Counter()}
    for lineage in lineage_cache.values():
        counts = lineage["expected_panorama_rule_counts"]
        for side in ("pre", "post"):
            policy_rule_totals[side].update(counts[side])

    return {
        "schema_version": COMPILER_SCHEMA_VERSION,
        "status": "success",
        "compiler_contract": {
            "template_stack_scalar_precedence_compiled": True,
            "template_stack_overrides_templates": True,
            "templates_top_to_bottom_high_to_low": True,
            "template_variables_resolved": False,
            "arbitrary_xml_collection_merge_compiled": False,
            "device_group_policy_lineage_compiled": True,
            "device_group_object_value_precedence_compiled": False,
            "vendor_effective_config_equivalence_claimed": False,
            "raw_values_stored": False,
        },
        "templates": sorted(template_catalog),
        "template_stacks": compiled_stacks,
        "device_groups": {
            name: {
                "name": name,
                "parent": _group_parent(group),
                "lineage": lineage_cache[name],
            }
            for name, group in sorted(group_catalog.items())
        },
        "by_serial": by_serial,
        "summary": {
            "templates": len(template_catalog),
            "template_stacks": len(compiled_stacks),
            "device_groups": len(group_catalog),
            "assigned_serials": len(all_serials),
            "serials_with_exactly_one_stack": sum(1 for serial in all_serials if len(stack_by_serial.get(serial) or []) == 1),
            "serials_without_stack": sum(1 for serial in all_serials if len(stack_by_serial.get(serial) or []) == 0),
            "serials_with_multiple_stacks": sum(1 for serial in all_serials if len(stack_by_serial.get(serial) or []) > 1),
            "compiled_scalar_settings_across_unique_stacks": total_settings,
            "alignment_ready_scalar_settings_across_unique_stacks": alignment_ready,
            "unresolved_variable_settings_across_unique_stacks": unresolved_variables,
            "shadowed_settings_across_unique_stacks": shadowed,
            "missing_template_references": missing_refs,
            "omitted_collection_leaves_across_unique_stacks": omitted_collections,
            "omitted_attribute_only_leaves_across_unique_stacks": omitted_attributes,
            "device_group_assignment_serials": len(dg_by_serial),
            "device_group_lineages_compiled": sum(1 for row in lineage_cache.values() if row["status"] == "compiled"),
            "device_group_lineages_partial": sum(1 for row in lineage_cache.values() if row["status"] != "compiled"),
            "anomaly_counts": dict(sorted(anomaly_counts.items())),
        },
        "policy_rule_totals_across_group_lineages": {
            side: dict(sorted(counter.items())) for side, counter in policy_rule_totals.items()
        },
        "privacy": {
            "local_only": True,
            "contains_real_assignment_names": True,
            "raw_configuration_values_included": False,
            "support_bundle_must_use_summary_only": True,
        },
    }


def expected_for_serial(compiled: dict[str, Any] | None, serial: str) -> dict[str, Any]:
    if not compiled:
        return {
            "status": "unavailable",
            "template_stack_status": "unknown",
            "template_stacks": [],
            "primary_template_stack": None,
            "template_expected": None,
            "device_group_assignments": [],
            "policy_scopes": [],
            "anomalies": ["compiler_unavailable"],
        }
    row = ((compiled.get("by_serial") or {}).get(serial) or {})
    if not row:
        return {
            "status": "unmapped",
            "template_stack_status": "unmapped",
            "template_stacks": [],
            "primary_template_stack": None,
            "template_expected": None,
            "device_group_assignments": [],
            "policy_scopes": [],
            "anomalies": ["serial_not_present_in_expected_compiler"],
        }
    return row
