"""`log_explanation_v1` output schema (design §8.3) and a dependency-free validator.

The JSON schema is sent to the API as `output_config.format`, so the model is
constrained to it. The validator re-checks the parsed answer client-side, because
the trial measures schema validity rather than assuming it, and because some
constraints (at least one evidence entry) cannot be expressed in the API's schema
subset.
"""

from __future__ import annotations

from typing import Any

SCHEMA_ID = "log_explanation_v1"

CAUSE_CATEGORIES = (
    "link",
    "hardware",
    "high_availability",
    "resource",
    "software_fault",
    "configuration_change",
    "external_dependency",
    "benign",
    "unknown",
)
CONFIDENCE = ("low", "medium", "high")
PRIORITIES = ("act_now", "review_today", "monitor", "likely_benign")

# next_checks: a closed list of kinds and targets (design §4.5, §8.3). A target
# is only valid for the kind whose prefix it carries.
NEXT_CHECK_KINDS = ("open_screen", "read_gate", "compare_peer", "vendor_doc_topic")
NEXT_CHECK_TARGETS = (
    "screen:system_health",
    "screen:device_logs",
    "screen:cluster_status",
    "screen:interfaces",
    "screen:vpn_tunnels",
    "screen:policy_install_history",
    "screen:certificates",
    "screen:disk_and_resources",
    "screen:log_server_connectivity",
    "screen:software_versions",
    "screen:change_history",
    "gate:cp_messages",
    "gate:cp_cpwd_elg",
    "gate:cp_cpd_elg",
    "gate:cp_fwd_elg",
    "gate:cp_fwk_elg",
    "gate:cp_sar_daily",
    "gate:pan_system",
    "peer:cluster_peer",
    "topic:clusterxl_state_changes",
    "topic:cpwd_process_monitoring",
    "topic:sic_certificates",
    "topic:log_server_connectivity",
    "topic:ike_ipsec_negotiation",
    "topic:disk_space_management",
    "topic:upgrade_procedure",
    "topic:panos_ha",
    "topic:panos_certificate_management",
    "topic:interface_errors",
)
KIND_TARGET_PREFIX = {
    "open_screen": "screen:",
    "read_gate": "gate:",
    "compare_peer": "peer:",
    "vendor_doc_topic": "topic:",
}


def _obj(properties: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": "object",
        "properties": properties,
        "required": list(properties),
        "additionalProperties": False,
    }


SCHEMA: dict[str, Any] = _obj(
    {
        "finding_id": {"type": "string"},
        "summary": {"type": "string"},
        "what_happened": {"type": "string"},
        "likely_cause": _obj(
            {
                "category": {"type": "string", "enum": list(CAUSE_CATEGORIES)},
                "statement": {"type": "string"},
                "confidence": {"type": "string", "enum": list(CONFIDENCE)},
            }
        ),
        "evidence": {
            "type": "array",
            "items": _obj({"reference": {"type": "string"}, "why": {"type": "string"}}),
        },
        "unknowns": {"type": "array", "items": {"type": "string"}},
        "triage": _obj(
            {
                "priority": {"type": "string", "enum": list(PRIORITIES)},
                "reason": {"type": "string"},
            }
        ),
        "next_checks": {
            "type": "array",
            "items": _obj(
                {
                    "kind": {"type": "string", "enum": list(NEXT_CHECK_KINDS)},
                    "target": {"type": "string", "enum": list(NEXT_CHECK_TARGETS)},
                    "why": {"type": "string"},
                }
            ),
        },
    }
)


def validate(value: Any, schema: dict[str, Any] = SCHEMA, path: str = "$") -> list[str]:
    """Return a list of violations; an empty list means valid.

    Supports exactly the keywords used in SCHEMA: type (object, array, string),
    properties, required, additionalProperties false, items, enum.
    """
    errors: list[str] = []
    kind = schema.get("type")
    if kind == "object":
        if not isinstance(value, dict):
            return [f"{path}: expected object"]
        props = schema.get("properties", {})
        for key in schema.get("required", []):
            if key not in value:
                errors.append(f"{path}.{key}: missing")
        if schema.get("additionalProperties") is False:
            for key in value:
                if key not in props:
                    errors.append(f"{path}.{key}: not allowed")
        for key, sub in props.items():
            if key in value:
                errors.extend(validate(value[key], sub, f"{path}.{key}"))
    elif kind == "array":
        if not isinstance(value, list):
            return [f"{path}: expected array"]
        for i, item in enumerate(value):
            errors.extend(validate(item, schema.get("items", {}), f"{path}[{i}]"))
    elif kind == "string":
        if not isinstance(value, str):
            return [f"{path}: expected string"]
    if "enum" in schema and value not in schema["enum"]:
        errors.append(f"{path}: {value!r} not in enum")
    return errors
