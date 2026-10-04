from __future__ import annotations

import hashlib
import re
from collections import Counter
from typing import Any

from lxml import etree


STRUCTURE_SCHEMA_VERSION = "0.6.0A2.2"
MAX_SCHEMA_PATHS = 320
_SAFE_TAG_RE = re.compile(r"^[a-z_][a-z0-9_.-]{0,63}$")


def _local_name(tag: Any) -> str:
    if not isinstance(tag, str):
        return ""
    return tag.split("}")[-1]


def _safe_tag_name(tag: Any) -> str:
    """Return a privacy-safe element name for schema-only telemetry.

    PAN-OS configuration object names normally live in attributes/text rather
    than XML element names. Standard ASCII schema tags are therefore safe to
    expose. Unexpected/non-standard tag names are replaced by a stable hash so
    schema inspection cannot accidentally leak a dynamic value.
    """

    name = _local_name(tag)
    if _SAFE_TAG_RE.fullmatch(name):
        return name
    digest = hashlib.sha256(name.encode("utf-8", errors="replace")).hexdigest()[:12]
    return f"tag_{digest}"


def _parse_config(content: bytes) -> etree._Element:
    parser = etree.XMLParser(
        resolve_entities=False,
        no_network=True,
        recover=False,
        remove_comments=False,
    )
    root = etree.fromstring(content, parser=parser)
    if _local_name(root.tag) != "config":
        raise ValueError("PAN structure analysis requires a <config> root")
    return root


def _count(root: etree._Element, xpath: str) -> int:
    return len(root.xpath(xpath))


def _inspect_schema_paths(root: etree._Element) -> dict[str, Any]:
    """Inspect XML topology without exposing values or attributes.

    Output contains element-tag paths and occurrence counts only. Attribute
    names/values, element text, tails and comments are intentionally ignored.
    """

    path_counts: Counter[str] = Counter()
    max_depth = 0

    def walk(node: etree._Element, parents: tuple[str, ...]) -> None:
        nonlocal max_depth
        if not isinstance(node.tag, str):
            return
        tag = _safe_tag_name(node.tag)
        current = parents + (tag,)
        path = "/" + "/".join(current)
        path_counts[path] += 1
        max_depth = max(max_depth, len(current))
        for child in node:
            if isinstance(child.tag, str):
                walk(child, current)

    walk(root, ())
    ordered = sorted(path_counts.items(), key=lambda item: item[0])
    truncated = len(ordered) > MAX_SCHEMA_PATHS
    visible = ordered[:MAX_SCHEMA_PATHS]

    interesting_tokens = (
        "/deviceconfig",
        "/network",
        "/interface",
        "/virtual-router",
        "/vsys",
        "/zone",
        "/rulebase",
        "/security",
        "/panorama",
        "/template",
        "/shared",
    )
    interesting = [
        {"path": path, "occurrences": count}
        for path, count in ordered
        if any(token in path for token in interesting_tokens)
    ][:MAX_SCHEMA_PATHS]

    return {
        "privacy_safe": True,
        "value_content_included": False,
        "attribute_content_included": False,
        "distinct_path_count": len(ordered),
        "max_depth": max_depth,
        "paths_truncated": truncated,
        "path_limit": MAX_SCHEMA_PATHS,
        "paths": [{"path": path, "occurrences": count} for path, count in visible],
        "interesting_paths": interesting,
    }


def analyze_pan_config_structure(content: bytes) -> dict[str, Any]:
    """Return privacy-safe structural telemetry for a PAN-OS <config> tree.

    Phase 0.6.0A2.2 deliberately separates XML/schema validity from evidence
    completeness. A syntactically valid active configuration can still be an
    incomplete representation of the effective device state (for example when
    relevant configuration is inherited/pushed from Panorama). A2.1 therefore
    reports evidence_status=unknown until the observed real schema is mapped to
    an explicitly validated evidence contract.
    """

    root = _parse_config(content)

    devices = root.xpath("./*[local-name()='devices']")
    device_entries = root.xpath("./*[local-name()='devices']/*[local-name()='entry']")
    deviceconfig_sections = root.xpath(
        "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='deviceconfig']"
    )
    network_sections = root.xpath(
        "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='network']"
    )
    vsys_sections = root.xpath(
        "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='vsys']"
    )
    shared_sections = root.xpath("./*[local-name()='shared']")
    panorama_sections = root.xpath("./*[local-name()='panorama']")
    mgt_config_sections = root.xpath("./*[local-name()='mgt-config']")
    interface_containers = root.xpath(
        "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='network']/*[local-name()='interface']"
    )
    virtual_router_containers = root.xpath(
        "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='network']/*[local-name()='virtual-router']"
    )
    zone_containers = root.xpath(
        "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='vsys']/*[local-name()='entry']/*[local-name()='zone']"
    )
    security_rules_containers = root.xpath(
        "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='vsys']/*[local-name()='entry']/*[local-name()='rulebase']/*[local-name()='security']/*[local-name()='rules']"
    )

    counts = {
        "top_level_sections": len(root),
        "device_entries": len(device_entries),
        "deviceconfig_sections": len(deviceconfig_sections),
        "network_sections": len(network_sections),
        "vsys_sections": len(vsys_sections),
        "vsys_entries": _count(
            root,
            "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='vsys']/*[local-name()='entry']",
        ),
        "virtual_router_entries": _count(
            root,
            "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='network']/*[local-name()='virtual-router']/*[local-name()='entry']",
        ),
        "zone_entries": _count(
            root,
            "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='vsys']/*[local-name()='entry']/*[local-name()='zone']/*[local-name()='entry']",
        ),
        "ethernet_interface_entries": _count(
            root,
            "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='network']/*[local-name()='interface']/*[local-name()='ethernet']/*[local-name()='entry']",
        ),
        "aggregate_ethernet_interface_entries": _count(
            root,
            "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='network']/*[local-name()='interface']/*[local-name()='aggregate-ethernet']/*[local-name()='entry']",
        ),
        "loopback_interface_entries": _count(
            root,
            "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='network']/*[local-name()='interface']/*[local-name()='loopback']/*[local-name()='units']/*[local-name()='entry']",
        ),
        "vlan_interface_entries": _count(
            root,
            "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='network']/*[local-name()='interface']/*[local-name()='vlan']/*[local-name()='units']/*[local-name()='entry']",
        ),
        "tunnel_interface_entries": _count(
            root,
            "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='network']/*[local-name()='interface']/*[local-name()='tunnel']/*[local-name()='units']/*[local-name()='entry']",
        ),
        "ethernet_subinterface_entries": _count(
            root,
            "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='network']/*[local-name()='interface']/*[local-name()='ethernet']/*[local-name()='entry']/*[local-name()='layer3']/*[local-name()='units']/*[local-name()='entry']",
        ),
        "aggregate_ethernet_subinterface_entries": _count(
            root,
            "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='network']/*[local-name()='interface']/*[local-name()='aggregate-ethernet']/*[local-name()='entry']/*[local-name()='layer3']/*[local-name()='units']/*[local-name()='entry']",
        ),
        "local_security_rule_entries": _count(
            root,
            "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='vsys']/*[local-name()='entry']/*[local-name()='rulebase']/*[local-name()='security']/*[local-name()='rules']/*[local-name()='entry']",
        ),
        "panorama_pre_security_rule_entries": _count(
            root,
            "./*[local-name()='panorama']/*[local-name()='vsys']/*[local-name()='entry']/*[local-name()='pre-rulebase']/*[local-name()='security']/*[local-name()='rules']/*[local-name()='entry']",
        ),
        "panorama_post_security_rule_entries": _count(
            root,
            "./*[local-name()='panorama']/*[local-name()='vsys']/*[local-name()='entry']/*[local-name()='post-rulebase']/*[local-name()='security']/*[local-name()='rules']/*[local-name()='entry']",
        ),
    }

    counts["interface_definitions_total"] = sum(
        counts[key]
        for key in (
            "ethernet_interface_entries",
            "aggregate_ethernet_interface_entries",
            "loopback_interface_entries",
            "vlan_interface_entries",
            "tunnel_interface_entries",
            "ethernet_subinterface_entries",
            "aggregate_ethernet_subinterface_entries",
        )
    )
    counts["security_rule_entries_total"] = (
        counts["local_security_rule_entries"]
        + counts["panorama_pre_security_rule_entries"]
        + counts["panorama_post_security_rule_entries"]
    )

    presence = {
        "devices": bool(devices),
        "device_entry": bool(device_entries),
        "deviceconfig": bool(deviceconfig_sections),
        "network": bool(network_sections),
        "vsys": bool(vsys_sections),
        "shared": bool(shared_sections),
        "panorama": bool(panorama_sections),
        "mgt_config": bool(mgt_config_sections),
    }

    warnings: list[str] = []
    if not presence["devices"]:
        warnings.append("devices_section_not_observed")
    elif not presence["device_entry"]:
        warnings.append("device_entry_not_observed")

    for section in ("deviceconfig", "network", "vsys"):
        if not presence[section]:
            warnings.append(f"{section}_section_not_observed")

    if virtual_router_containers and counts["virtual_router_entries"] == 0:
        warnings.append("virtual_router_container_observed_without_entries")
    if interface_containers and counts["interface_definitions_total"] == 0:
        warnings.append("interface_container_observed_without_entries")
    if zone_containers and counts["zone_entries"] == 0:
        warnings.append("zone_container_observed_without_entries")
    if security_rules_containers and counts["local_security_rule_entries"] == 0:
        warnings.append("security_rules_container_observed_without_entries")

    meaningful_core_sections = sum(
        1 for section in ("deviceconfig", "network", "vsys") if presence[section]
    )
    schema_status = (
        "pass" if presence["device_entry"] and meaningful_core_sections > 0 else "warn"
    )

    schema_inspection = _inspect_schema_paths(root)

    return {
        "schema_version": STRUCTURE_SCHEMA_VERSION,
        # Compatibility alias retained for the A2 support reader/tests.
        "status": schema_status,
        "schema_status": schema_status,
        "evidence_status": "unknown",
        "evidence_reason": "schema_mapping_in_progress",
        "privacy_safe": True,
        "presence": presence,
        "counts": counts,
        "schema_inspection": schema_inspection,
        "warnings": warnings,
    }
