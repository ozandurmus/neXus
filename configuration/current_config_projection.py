from __future__ import annotations

from pathlib import Path
from typing import Any

from lxml import etree

from configuration.pan_setting_alignment import alignment_key_for_node


CURRENT_CONFIG_SCHEMA_VERSION = "0.6.0A4.3.3.2"

# The browser payload is a local operator view, not a support artifact. We still
# refuse to surface secret-bearing values even locally. The initial projection
# is intentionally bounded to device/system configuration; policy/rule content
# remains a separate future plane.
SENSITIVE_TOKENS = {
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
    "certificate",
}

PAN_PROVENANCE = {
    "tpl": "central",
    "template": "central",
    "shared": "central",
    "dg": "central",
    "device-group": "central",
    "local": "local",
}


SECTION_ORDER = [
    "system",
    "dns",
    "ntp",
    "management",
    "telemetry",
    "high_availability",
    "network_summary",
]



HIGHLIGHT_PRIORITY = [
    ("system", "Hostname"),
    ("system", "Domain"),
    ("system", "Timezone"),
    ("dns", "Primary DNS"),
    ("dns", "Secondary DNS"),
    ("ntp", "Primary NTP Server"),
    ("ntp", "Secondary NTP Server"),
    ("management", "Panorama Server"),
    ("management", "Panorama Server 2"),
    ("high_availability", "HA Enabled"),
    ("high_availability", "Group ID"),
]

SECTION_LABELS = {
    "system": "System",
    "dns": "DNS",
    "ntp": "NTP",
    "management": "Management",
    "telemetry": "Telemetry",
    "high_availability": "High Availability",
    "network_summary": "Network Configuration",
}


def _as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _local_name(tag: Any) -> str:
    if not isinstance(tag, str):
        return ""
    return tag.rsplit("}", 1)[-1]


def _safe_xml(content: bytes) -> etree._Element:
    parser = etree.XMLParser(resolve_entities=False, no_network=True, recover=False, huge_tree=True)
    return etree.fromstring(content, parser=parser)


def _artifact_bytes(base_dir: Path, artifact: dict[str, Any] | None) -> bytes | None:
    artifact = _as_dict(artifact)
    if artifact.get("status") != "success":
        return None

    object_path = artifact.get("artifact_object")
    if object_path:
        path = Path(str(object_path))
        if not path.is_absolute():
            path = base_dir / path
        try:
            return path.read_bytes()
        except OSError:
            return None

    # Legacy pre-A4.3.2 evidence fallback.
    snapshot = artifact.get("snapshot")
    artifact_file = artifact.get("artifact_file")
    if not snapshot or not artifact_file:
        return None
    directory = Path(str(snapshot))
    if not directory.is_absolute():
        directory = base_dir / directory
    try:
        return (directory / str(artifact_file)).read_bytes()
    except OSError:
        return None


def _sensitive_path(key: str) -> bool:
    lowered = key.lower()
    return any(token in lowered for token in SENSITIVE_TOKENS)


def _nearest_provenance(node: etree._Element, stop: etree._Element) -> str:
    cur: etree._Element | None = node
    while cur is not None:
        src = str(cur.get("src") or "").strip().lower()
        if src:
            return PAN_PROVENANCE.get(src, "unknown")
        if cur is stop:
            break
        cur = cur.getparent()
    return "unknown"


def _result_index(alignment_detail: dict[str, Any] | None) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for item in _as_dict(alignment_detail).get("results") or []:
        if not isinstance(item, dict):
            continue
        key = str(item.get("alignment_key") or "")
        if key:
            result[key] = item
    return result


def _origin_for(
    *,
    key: str,
    node: etree._Element,
    root: etree._Element,
    alignment_index: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    alignment = alignment_index.get(key) or {}
    classification = str(alignment.get("classification") or "")
    expected_kind = str(alignment.get("expected_source_kind") or "").lower()
    provenance = _nearest_provenance(node, root)

    if classification == "LOCAL_OVERRIDE":
        origin = "local_override"
    elif classification == "MEMBER_SPECIFIC":
        origin = "member_specific"
    elif provenance == "local":
        origin = "local"
    elif provenance == "central":
        origin = "central"
    elif classification == "ALIGNED" and expected_kind in {"template", "template_stack", "shared", "device_group"}:
        origin = "central"
    elif classification == "PROVENANCE_UNVERIFIED":
        origin = "unknown"
    else:
        origin = "effective"

    return {
        "origin": origin,
        "alignment_classification": classification or None,
        "confidence": alignment.get("confidence"),
    }


def _pretty_token(value: str) -> str:
    token = value.replace("_", " ").replace("-", " ").strip()
    aliases = {
        "ha1": "HA1",
        "ha2": "HA2",
        "ha3": "HA3",
        "ip": "IP",
        "dns": "DNS",
        "ntp": "NTP",
        "vsys": "VSYS",
        "id": "ID",
        "ssh": "SSH",
        "https": "HTTPS",
        "http": "HTTP",
        "snmp": "SNMP",
        "fqdn": "FQDN",
    }
    words = []
    for word in token.split():
        words.append(aliases.get(word.lower(), word.capitalize()))
    return " ".join(words)


def _entry_identity(node: etree._Element) -> str | None:
    cur = node.getparent()
    while cur is not None:
        if _local_name(cur.tag) == "entry" and cur.get("name"):
            return str(cur.get("name"))
        cur = cur.getparent()
    return None


def _relative_components(node: etree._Element, stop: etree._Element) -> list[str]:
    parts: list[str] = []
    cur: etree._Element | None = node
    while cur is not None and cur is not stop:
        tag = _local_name(cur.tag)
        if tag == "entry" and cur.get("name"):
            parts.append(str(cur.get("name")))
        elif tag:
            parts.append(tag)
        cur = cur.getparent()
    return list(reversed(parts))


def _section_for(key: str) -> str | None:
    text = key.lower()
    if "/deviceconfig/high-availability" in text or "/high-availability/" in text:
        return "high_availability"
    if "/deviceconfig/system/dns" in text or "/dns-setting" in text:
        return "dns"
    if "/deviceconfig/system/ntp" in text or "/ntp-servers" in text:
        return "ntp"
    if "/deviceconfig/system/device-telemetry" in text or "/device-telemetry/" in text or "/telemetry/" in text:
        return "telemetry"
    if (
        "/deviceconfig/system/service" in text
        or "/deviceconfig/system/permitted-ip" in text
        or text.endswith("/deviceconfig/system/panorama-server")
        or text.endswith("/deviceconfig/system/panorama-server-2")
    ):
        return "management"
    if "/deviceconfig/system/" in text:
        return "system"
    return None


def _label_for(node: etree._Element, section: str, root: etree._Element) -> str:
    tag = _local_name(node.tag)
    key = tag.lower()
    special = {
        ("system", "hostname"): "Hostname",
        ("system", "domain"): "Domain",
        ("system", "timezone"): "Timezone",
        ("system", "panorama-server"): "Panorama Server",
        ("system", "panorama-server-2"): "Panorama Server 2",
        ("system", "update-server"): "Update Server",
        ("dns", "primary"): "Primary DNS",
        ("dns", "secondary"): "Secondary DNS",
        ("ntp", "ntp-server-address"): "NTP Server",
        ("high_availability", "enabled"): "HA Enabled",
        ("high_availability", "group-id"): "Group ID",
        ("high_availability", "peer-ip"): "Peer IP",
        ("telemetry", "region"): "Region",
    }
    label = special.get((section, key))
    if label:
        if section == "ntp":
            components = [part.lower() for part in _relative_components(node, root)]
            if "primary-ntp-server" in components:
                return "Primary NTP Server"
            if "secondary-ntp-server" in components:
                return "Secondary NTP Server"
        return label

    if section == "management" and key.startswith("disable-"):
        return f"{_pretty_token(key.removeprefix('disable-'))} Disabled"

    components = _relative_components(node, root)
    # The last few semantic components are enough for operator context without
    # exposing a raw XML path as the primary label.
    tail = [part for part in components[-4:] if part not in {"config", "devices", "deviceconfig", "system"}]
    return " · ".join(_pretty_token(part) for part in tail) or _pretty_token(tag)


def _context_for(node: etree._Element, section: str) -> str | None:
    identity = _entry_identity(node)
    if not identity:
        return None
    # Entry names often carry the exact object/interface/CIDR identity an
    # operator needs. Keep it local to the HTML and never support-bundle it.
    if section in {"management", "high_availability"}:
        # PAN effective-running commonly wraps deviceconfig under an entry
        # named localhost.localdomain. That is an XML/container identity, not
        # useful operator context. Keep meaningful entry identities only.
        if identity.strip().lower() in {"localhost.localdomain", "localhost", "localdomain"}:
            return None
        return identity
    return None


def _scalar_rows(
    root: etree._Element,
    alignment_index: dict[str, dict[str, Any]],
    *,
    max_rows: int = 600,
) -> tuple[dict[str, list[dict[str, Any]]], int]:
    sections: dict[str, list[dict[str, Any]]] = {key: [] for key in SECTION_ORDER}
    redacted = 0

    for node in root.iter():
        if len(node) != 0 or not isinstance(node.tag, str):
            continue
        text = str(node.text or "").strip()
        if not text:
            continue
        key = alignment_key_for_node(node, root)
        section = _section_for(key)
        if section is None:
            continue
        if _sensitive_path(key):
            redacted += 1
            continue
        if sum(len(rows) for rows in sections.values()) >= max_rows:
            break

        origin = _origin_for(key=key, node=node, root=root, alignment_index=alignment_index)
        sections[section].append({
            "setting": _label_for(node, section, root),
            "value": text,
            "origin": origin["origin"],
            "alignment_classification": origin["alignment_classification"],
            "confidence": origin["confidence"],
            "context": _context_for(node, section),
            "member_specific": origin["origin"] == "member_specific",
        })

    # Named permitted-IP entries are meaningful configuration values even when
    # the CIDR is stored in @name rather than leaf text.
    for entry in root.xpath(
        "./*[local-name()='devices']/*[local-name()='entry']/*[local-name()='deviceconfig']"
        "/*[local-name()='system']/*[local-name()='permitted-ip']/*[local-name()='entry']"
    ):
        name = str(entry.get("name") or "").strip()
        if not name:
            continue
        key = alignment_key_for_node(entry, root)
        origin = _origin_for(key=key, node=entry, root=root, alignment_index=alignment_index)
        sections["management"].append({
            "setting": "Permitted IP / Network",
            "value": name,
            "origin": origin["origin"],
            "alignment_classification": origin["alignment_classification"],
            "confidence": origin["confidence"],
            "context": None,
            "member_specific": False,
        })

    for rows in sections.values():
        rows.sort(key=lambda row: (str(row.get("setting") or "").lower(), str(row.get("context") or "").lower()))
    return sections, redacted


def _current_highlights(section_rows: dict[str, list[dict[str, Any]]]) -> list[dict[str, Any]]:
    """Return a bounded operator-oriented basic configuration summary.

    Values are selected only from already-redacted structured rows. No new
    collector call, raw configuration blob, or vendor schema expansion occurs
    here.
    """
    result: list[dict[str, Any]] = []
    by_key: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for section_id, rows in section_rows.items():
        for row in rows:
            by_key.setdefault((section_id, str(row.get("setting") or "")), []).append(row)

    for section_id, label in HIGHLIGHT_PRIORITY:
        matches = by_key.get((section_id, label)) or []
        if not matches:
            continue
        row = matches[0]
        result.append({
            "label": label,
            "value": row.get("value"),
            "section": section_id,
            "section_label": SECTION_LABELS.get(section_id, section_id.replace("_", " ").title()),
            "origin": row.get("origin"),
            "context": row.get("context"),
        })
        if len(result) >= 10:
            break

    permitted = [
        row for row in section_rows.get("management", [])
        if row.get("setting") == "Permitted IP / Network"
    ]
    if permitted and len(result) < 10:
        result.append({
            "label": "Management Access Entries",
            "value": str(len(permitted)),
            "section": "management",
            "section_label": SECTION_LABELS["management"],
            "origin": "effective",
            "context": "Permitted IP / Network",
        })
    return result


def _section_index(section_rows: dict[str, list[dict[str, Any]]]) -> list[dict[str, Any]]:
    return [
        {
            "id": section_id,
            "label": SECTION_LABELS.get(section_id, section_id.replace("_", " ").title()),
            "count": len(section_rows.get(section_id) or []),
        }
        for section_id in SECTION_ORDER
        if section_rows.get(section_id)
    ]


def build_pan_current_configuration(
    *,
    base_dir: Path,
    row: dict[str, Any],
    alignment_detail: dict[str, Any] | None,
) -> dict[str, Any]:
    direct = _as_dict(row.get("direct"))
    effective_artifact = _as_dict(direct.get("effective"))
    content = _artifact_bytes(base_dir, effective_artifact)
    if not content:
        return {
            "schema_version": CURRENT_CONFIG_SCHEMA_VERSION,
            "status": "unavailable",
            "vendor": "palo_alto",
            "reason": "effective_running_artifact_unavailable",
            "source_plane": "effective-running",
            "sections": [],
            "structured_values_included": False,
            "raw_config_included": False,
            "secrets_redacted": True,
        }

    try:
        root = _safe_xml(content)
    except (etree.XMLSyntaxError, ValueError):
        return {
            "schema_version": CURRENT_CONFIG_SCHEMA_VERSION,
            "status": "unavailable",
            "vendor": "palo_alto",
            "reason": "effective_running_xml_invalid",
            "source_plane": "effective-running",
            "sections": [],
            "structured_values_included": False,
            "raw_config_included": False,
            "secrets_redacted": True,
        }

    alignment_index = _result_index(alignment_detail)
    section_rows, redacted = _scalar_rows(root, alignment_index)
    counts = _as_dict(_as_dict(effective_artifact.get("structural_validation")).get("counts"))
    network_summary = [
        {"setting": "VSYS", "value": str(int(counts.get("vsys_entries") or 0)), "origin": "effective"},
        {"setting": "Virtual Routers", "value": str(int(counts.get("virtual_router_entries") or 0)), "origin": "effective"},
        {"setting": "Zones", "value": str(int(counts.get("zone_entries") or 0)), "origin": "effective"},
        {"setting": "Interfaces", "value": str(int(counts.get("interface_definitions_total") or 0)), "origin": "effective"},
    ]
    section_rows["network_summary"] = network_summary

    sections = []
    for section_id in SECTION_ORDER:
        rows = section_rows.get(section_id) or []
        if not rows:
            continue
        sections.append({
            "id": section_id,
            "label": SECTION_LABELS.get(section_id, section_id.replace("_", " ").title()),
            "settings": rows,
            "count": len(rows),
        })

    return {
        "schema_version": CURRENT_CONFIG_SCHEMA_VERSION,
        "status": "available",
        "vendor": "palo_alto",
        "source_plane": "effective-running",
        "sections": sections,
        "section_index": _section_index(section_rows),
        "highlights": _current_highlights(section_rows),
        "setting_count": sum(section["count"] for section in sections),
        "redacted_secret_setting_count": redacted,
        "projection_scope": "device_system_management_dns_ntp_ha_telemetry_plus_network_counts",
        "native_view": {
            "status": "deferred",
            "reason": "native_config_render_requires_secret_aware_authorized_view",
        },
        "structured_values_included": True,
        "raw_config_included": False,
        "secrets_redacted": True,
    }
