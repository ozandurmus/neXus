from __future__ import annotations

import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from utils.config_history import ConfigHistoryService, build_history_payload

# CON.1 AC-8: console/ must import no vendor/collector module, transitively —
# checkpoint/*, panorama/*, configuration/* — so these two stay lazy (at the
# point of use, below) instead of a module-level import. Every other caller
# of build_configuration_ui_payload is unaffected: Python caches the import
# on first call either way.


CONFIG_UI_SCHEMA_VERSION = "0.6.1B"
BASE_DIR = Path(__file__).resolve().parent.parent

# Detailed setting rows are intentionally limited to classifications that an
# operator may reasonably inspect. EXPECTED_ONLY/LOCAL_ONLY can be very large
# and remain aggregate coverage telemetry in this first UI foundation.
DETAIL_CLASSIFICATIONS = {
    "DIFFERENCE_OBSERVED",
    "LOCAL_OVERRIDE",
    "EFFECTIVE_DRIFT",
    "PANORAMA_OUT_OF_SYNC",
    "MEMBER_SPECIFIC",
    "PROVENANCE_UNVERIFIED",
    "IDENTITY_TRANSLATION_REQUIRED",
    "UNKNOWN",
}


CLASSIFICATION_LABELS = {
    "ALIGNED": "Aligned",
    "DIFFERENCE_OBSERVED": "Difference observed",
    "LOCAL_OVERRIDE": "Local override",
    "EFFECTIVE_DRIFT": "Effective drift",
    "PANORAMA_OUT_OF_SYNC": "Panorama out of sync",
    "EXPECTED_ONLY": "Expected only",
    "ACTUAL_ONLY": "Actual only",
    "LOCAL_ONLY": "Local only",
    "MEMBER_SPECIFIC": "Expected member difference",
    "PROVENANCE_UNVERIFIED": "Provenance unverified",
    "IDENTITY_TRANSLATION_REQUIRED": "Identity translation required",
    "UNKNOWN": "Unknown",
    "INSUFFICIENT_EVIDENCE": "Insufficient evidence",
}


CATEGORY_LABELS = {
    "dns": "DNS",
    "ha": "High Availability",
    "interfaces": "Interfaces",
    "logging": "Logging",
    "ntp": "NTP",
    "other": "Other",
    "profiles": "Profiles",
    "routing": "Routing",
    "system": "System",
    "vpn": "VPN",
    "vsys": "VSYS",
}


def _as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _integer(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def _float(value: Any) -> float:
    try:
        return float(value or 0.0)
    except (TypeError, ValueError):
        return 0.0


def _read_alignment_manifest(path_value: Any) -> dict[str, Any]:
    if not path_value:
        return {}
    try:
        path = Path(str(path_value))
        candidates = [path]
        if not path.is_absolute():
            candidates = [
                Path.cwd() / path,
                Path(__file__).resolve().parent.parent / path,
            ]
        for candidate in candidates:
            if candidate.exists():
                return json.loads(candidate.read_text(encoding="utf-8"))
        return {}
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, TypeError, ValueError):
        return {}


def _artifact_view(artifact: dict[str, Any] | None, *, role: str) -> dict[str, Any]:
    artifact = _as_dict(artifact)
    structure = _as_dict(artifact.get("structural_validation"))
    counts = _as_dict(structure.get("counts"))
    return {
        "role": role,
        "status": artifact.get("status") or "unavailable",
        "method": artifact.get("method"),
        "transport": artifact.get("transport"),
        "change_state": artifact.get("change_state"),
        "size_bytes": _integer(artifact.get("size_bytes")),
        "duration_ms": _integer(artifact.get("duration_ms")),
        "schema_status": structure.get("schema_status") or structure.get("status"),
        "counts": {
            "vsys": _integer(counts.get("vsys_entries")),
            "virtual_routers": _integer(counts.get("virtual_router_entries")),
            "zones": _integer(counts.get("zone_entries")),
            "interfaces": _integer(counts.get("interface_definitions_total")),
            "security_rules": _integer(counts.get("security_rule_entries_total")),
        },
    }


def _device_tone(row: dict[str, Any], counts: dict[str, int]) -> str:
    if row.get("status") != "success" or row.get("primary_evidence_status") != "success":
        return "danger"
    if counts.get("PANORAMA_OUT_OF_SYNC", 0) or counts.get("EFFECTIVE_DRIFT", 0):
        return "danger"
    if counts.get("LOCAL_OVERRIDE", 0):
        return "warning"
    if (
        counts.get("PROVENANCE_UNVERIFIED", 0)
        or counts.get("IDENTITY_TRANSLATION_REQUIRED", 0)
        or counts.get("UNKNOWN", 0)
        or counts.get("EXPECTED_ONLY", 0)
    ):
        return "coverage"
    return "success"


def _device_status_label(row: dict[str, Any], counts: dict[str, int]) -> str:
    if row.get("status") != "success" or row.get("primary_evidence_status") != "success":
        return "Collection issue"
    if counts.get("PANORAMA_OUT_OF_SYNC", 0):
        return "Panorama out of sync"
    if counts.get("EFFECTIVE_DRIFT", 0):
        return "Effective drift"
    if counts.get("LOCAL_OVERRIDE", 0):
        return "Local overrides"
    if (
        counts.get("PROVENANCE_UNVERIFIED", 0)
        or counts.get("IDENTITY_TRANSLATION_REQUIRED", 0)
        or counts.get("UNKNOWN", 0)
        or counts.get("EXPECTED_ONLY", 0)
    ):
        return "Aligned with coverage gaps"
    if counts.get("MEMBER_SPECIFIC", 0):
        return "Aligned with expected member differences"
    return "Aligned"


def _category_rows(category_counts: dict[str, Any]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for category, raw_counts in sorted(category_counts.items()):
        counts = {str(key): _integer(value) for key, value in _as_dict(raw_counts).items()}
        rows.append({
            "category": str(category),
            "label": CATEGORY_LABELS.get(str(category), str(category).replace("_", " ").title()),
            "counts": counts,
            "total": sum(counts.values()),
        })
    return rows


def _assignment_view(row: dict[str, Any]) -> dict[str, Any]:
    expected = _as_dict(row.get("expected_configuration"))
    alignment = _as_dict(row.get("configuration_alignment"))

    # The local collector row carries the authoritative Panorama assignment at
    # row.panorama_assignment. A4.3.3 originally looked only under
    # configuration_alignment.panorama_assignment, which is populated in the
    # shareable serializer but not in the normal local result. Prefer the
    # local assignment and retain the older location as a compatibility
    # fallback. Expected-compiler DG assignments are a final evidence-backed
    # fallback for older saved results.
    assignment = _as_dict(row.get("panorama_assignment"))
    if not assignment:
        assignment = _as_dict(alignment.get("panorama_assignment"))
    stacks = []
    for item in _as_list(assignment.get("template_stacks")):
        item = _as_dict(item)
        if item.get("name"):
            stacks.append({
                "name": item.get("name"),
                "templates": [str(v) for v in _as_list(item.get("templates"))],
                "stack_level_config_present": bool(item.get("stack_level_config_present")),
            })
    device_groups = []
    for item in _as_list(assignment.get("device_groups")):
        item = _as_dict(item)
        if item.get("name"):
            device_groups.append({
                "name": item.get("name"),
                "parent": item.get("parent"),
                "vsys": [str(v) for v in _as_list(item.get("vsys"))],
            })
    if not device_groups:
        for item in _as_list(expected.get("device_group_assignments")):
            item = _as_dict(item)
            name = item.get("device_group") or item.get("name")
            if name:
                device_groups.append({
                    "name": name,
                    "parent": item.get("parent"),
                    "vsys": [str(v) for v in _as_list(item.get("vsys"))],
                })

    return {
        "status": assignment.get("assignment_status") or alignment.get("panorama_assignment_status"),
        "primary_template_stack": expected.get("primary_template_stack"),
        "template_stacks": stacks,
        "device_groups": device_groups,
        "policy_scope_count": _integer(expected.get("policy_scope_count")),
        "policy_lineage_complete": bool(expected.get("policy_lineage_complete")),
        "unresolved_variable_settings": _integer(
            _as_dict(expected.get("template_expected")).get("unresolved_variable_setting_count")
        ),
        "primary_device_group": device_groups[0].get("name") if device_groups else None,
    }


def _detail_index(manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for device in _as_list(manifest.get("devices")):
        device = _as_dict(device)
        serial = str(device.get("serial") or "")
        if serial:
            result[serial] = device
    return result


def _finding_rows(detail: dict[str, Any]) -> list[dict[str, Any]]:
    findings: list[dict[str, Any]] = []
    for item in _as_list(detail.get("results")):
        item = _as_dict(item)
        classification = str(item.get("classification") or "UNKNOWN")
        if classification not in DETAIL_CLASSIFICATIONS:
            continue
        category = str(item.get("category") or "other")
        findings.append({
            "classification": classification,
            "classification_label": CLASSIFICATION_LABELS.get(classification, classification.replace("_", " ").title()),
            "category": category,
            "category_label": CATEGORY_LABELS.get(category, category.replace("_", " ").title()),
            # Paths and source names are intentionally local-UI-only. Value
            # hashes and raw values are never copied into the browser payload.
            "setting": item.get("alignment_key"),
            "expected_source_kind": item.get("expected_source_kind"),
            "expected_source_name": item.get("expected_source_name"),
            "confidence": item.get("confidence"),
            "reason": item.get("reason"),
            "identity_path_normalized": bool(item.get("identity_path_normalized")),
            "identity_value_normalized": bool(item.get("identity_value_normalized")),
        })
    findings.sort(key=lambda row: (
        str(row.get("classification") or ""),
        str(row.get("category") or ""),
        str(row.get("setting") or ""),
    ))
    return findings


def _ha_header_evidence(row: dict[str, Any], current_configuration: dict[str, Any]) -> tuple[str | None, str | None]:
    """Return the runtime HA role when proven, otherwise only config state.

    A4.3.3.2 adds a targeted read-only ``show high-availability state`` query
    when Panorama managed-device discovery does not expose ``ha-state``. The
    header must never infer Active/Passive from static HA group configuration.
    """
    ha_runtime = _as_dict(row.get("ha_runtime"))
    runtime_role = str(row.get("ha_state") or ha_runtime.get("state") or "").strip()
    if runtime_role:
        return runtime_role.upper(), str(ha_runtime.get("source") or "panorama_runtime")

    runtime_enabled = str(ha_runtime.get("enabled") or "").strip().lower()
    if ha_runtime.get("status") == "success" and runtime_enabled in {"no", "false", "off", "disabled", "0"}:
        return "HA Disabled", str(ha_runtime.get("source") or "panorama_target_ha_state")

    highlights = {
        str(item.get("label") or ""): item
        for item in _as_list(_as_dict(current_configuration).get("highlights"))
        if isinstance(item, dict)
    }
    enabled = str(_as_dict(highlights.get("HA Enabled")).get("value") or "").strip()
    normalized = enabled.lower()
    if normalized in {"yes", "true", "on", "enabled", "1"}:
        return "HA Enabled", "effective_configuration"
    if normalized in {"no", "false", "off", "disabled", "0"}:
        return "HA Disabled", "effective_configuration"
    return None, None


def _aggregate_category_counts(devices: list[dict[str, Any]]) -> dict[str, dict[str, int]]:
    aggregate: dict[str, Counter[str]] = defaultdict(Counter)
    for device in devices:
        for row in _as_list(_as_dict(device.get("alignment")).get("categories")):
            row = _as_dict(row)
            aggregate[str(row.get("category") or "other")].update(_as_dict(row.get("counts")))
    return {
        category: dict(sorted((key, _integer(value)) for key, value in counter.items()))
        for category, counter in sorted(aggregate.items())
    }


def _build_pan_configuration_ui_payload(
    config_result: dict[str, Any] | None,
    *,
    workflow_context: dict[str, Any] | None = None,
    history_service: ConfigHistoryService | None = None,
) -> dict[str, Any]:
    """Build a compact local-only UI view from the A4.2.2 collector result.

    This is deliberately *not* the support-bundle serializer. Device identity,
    Template/Device-Group names and setting paths are useful in the local UI,
    but raw configuration values, configuration hashes, and credentials are
    excluded from the browser payload.
    """

    if not isinstance(config_result, dict):
        return {
            "schema_version": CONFIG_UI_SCHEMA_VERSION,
            "available": False,
            "local_only": True,
            "workflow": _as_dict(workflow_context),
            "reason": "configuration_result_not_available_for_this_html_export",
            "fleet": {},
            "devices": [],
            "backup": {"status": "not_configured", "phase": "0.6.0B"},
            "structured_current_values_included": False,
            "raw_configuration_blob_included": False,
            "value_hashes_included": False,
        }

    summary = _as_dict(config_result.get("summary"))
    manifest = _read_alignment_manifest(config_result.get("setting_alignment_manifest_path"))
    details = _detail_index(manifest)
    devices: list[dict[str, Any]] = []

    total_observed = 0
    total_ready = 0
    total_findings = Counter()
    devices_with_provenance_unverified = 0
    devices_with_member_specific = 0
    devices_with_coverage_gaps = 0

    for row_value in _as_list(config_result.get("devices")):
        row = _as_dict(row_value)
        setting = _as_dict(row.get("setting_alignment"))
        setting_summary = _as_dict(setting.get("summary"))
        counts = {
            str(key): _integer(value)
            for key, value in _as_dict(setting_summary.get("classification_counts")).items()
        }
        if counts.get("PROVENANCE_UNVERIFIED", 0):
            devices_with_provenance_unverified += 1
        if counts.get("MEMBER_SPECIFIC", 0):
            devices_with_member_specific += 1
        if any(counts.get(name, 0) for name in (
            "PROVENANCE_UNVERIFIED", "IDENTITY_TRANSLATION_REQUIRED", "UNKNOWN", "EXPECTED_ONLY", "LOCAL_ONLY"
        )):
            devices_with_coverage_gaps += 1
        total_findings.update(counts)
        ready = _integer(setting_summary.get("alignment_ready_settings"))
        observed = _integer(setting_summary.get("expected_settings_observed_in_effective"))
        total_ready += ready
        total_observed += observed

        direct = _as_dict(row.get("direct"))
        alignment = _as_dict(row.get("configuration_alignment"))
        serial = str(row.get("serial") or "")
        detail = details.get(serial, {})
        from configuration.current_config_projection import build_pan_current_configuration
        current_configuration = build_pan_current_configuration(
            base_dir=BASE_DIR,
            row=row,
            alignment_detail=detail,
        )
        assignment_view = _assignment_view(row)
        effective_view = _artifact_view(_as_dict(direct.get("effective")), role="Primary effective configuration")
        vsys_count = _integer(_as_dict(effective_view.get("counts")).get("vsys"))
        policy_groups = _as_list(assignment_view.get("device_groups"))
        if policy_groups:
            first_policy_group = str(_as_dict(policy_groups[0]).get("name") or "")
            policy_scope = (
                f"{first_policy_group} (+{len(policy_groups) - 1})"
                if first_policy_group and len(policy_groups) > 1
                else first_policy_group or None
            )
        else:
            policy_scope = None

        ha_role, ha_role_source = _ha_header_evidence(row, current_configuration)

        devices.append({
            "id": serial or str(row.get("device") or ""),
            "name": row.get("device"),
            "vendor": "Palo Alto Networks",
            "vendor_key": "palo_alto",
            "serial": row.get("serial"),
            "management_ip": row.get("management_ip"),
            "model": row.get("model"),
            "sw_version": row.get("sw_version"),
            "ha_role": ha_role,
            "ha_role_source": ha_role_source,
            "vsys_count": vsys_count,
            "policy_scope_label": "Device Group",
            "policy_scope": policy_scope,
            "connected": row.get("connected"),
            "status": row.get("status"),
            "primary_evidence_status": row.get("primary_evidence_status"),
            "alignment_evidence_status": row.get("alignment_evidence_status"),
            "started_at": row.get("started_at"),
            "completed_at": row.get("completed_at"),
            "tone": _device_tone(row, counts),
            "status_label": _device_status_label(row, counts),
            "panorama_sync": {
                "template": alignment.get("panorama_template_sync"),
                "shared_policy": alignment.get("panorama_shared_policy_sync"),
                "out_of_sync": bool(alignment.get("panorama_reports_out_of_sync")),
            },
            "assignment": assignment_view,
            "current_configuration": current_configuration,
            "alignment": {
                "engine_status": setting.get("status"),
                "device_status": setting.get("device_status"),
                "counts": counts,
                "categories": _category_rows(_as_dict(setting_summary.get("category_counts"))),
                "expected_settings": _integer(setting_summary.get("expected_settings")),
                "alignment_ready_settings": ready,
                "observed_settings": observed,
                "observed_percent": _float(setting_summary.get("observed_percent")),
                "coverage_percent": _float(setting_summary.get("coverage_percent")),
                "value_comparison_coverage_percent": _float(
                    setting_summary.get("value_comparison_coverage_percent")
                ),
                "local_only_settings": _integer(setting_summary.get("local_only_settings")),
                "semantic_exclusion_settings": _integer(setting_summary.get("semantic_exclusion_settings")),
                "identity_path_normalized_settings": _integer(
                    setting_summary.get("identity_path_normalized_settings")
                ),
                "identity_value_normalized_settings": _integer(
                    setting_summary.get("identity_value_normalized_settings")
                ),
                "vsys_identity_map_entries": _integer(setting_summary.get("vsys_identity_map_entries")),
                "findings": _finding_rows(detail),
            },
            "evidence": {
                "panorama_control": _artifact_view(_as_dict(row.get("panorama_control")), role="Panorama control-plane active"),
                "active": _artifact_view(_as_dict(direct.get("active")), role="Local active / override evidence"),
                "merged": _artifact_view(_as_dict(direct.get("merged")), role="Merged / provenance evidence"),
                "effective": effective_view,
            },
            "history": {
                "active_change_state": _as_dict(direct.get("active")).get("change_state"),
                "merged_change_state": _as_dict(direct.get("merged")).get("change_state"),
                "effective_change_state": _as_dict(direct.get("effective")).get("change_state"),
            },
            "history_v1": None,  # populated below when history_service available
        })

    # --- PAN history projection (0.6.3) -----------------------------------
    # Requires a live history_service; skipped gracefully when unavailable.
    if history_service is not None:
        for device in devices:
            row_ref = next(
                (r for r in _as_list(config_result.get("devices"))
                 if str(_as_dict(r).get("serial") or _as_dict(r).get("device") or "") == device.get("id")),
                None,
            )
            if row_ref is None:
                continue
            row_ref = _as_dict(row_ref)
            direct_ref = _as_dict(row_ref.get("direct"))
            effective_ref = _as_dict(direct_ref.get("effective"))
            # Only projection-eligible CAS-stored snapshots qualify.
            eff_snap = str(effective_ref.get("snapshot") or "")
            if not eff_snap:
                device["history_v1"] = {
                    "status": "insufficient_evidence",
                    "scope": "single_entity_single_artifact",
                }
                continue
            entity_id = str(row_ref.get("serial") or row_ref.get("device") or "")
            if not entity_id:
                device["history_v1"] = {
                    "status": "insufficient_evidence",
                    "scope": "single_entity_single_artifact",
                }
                continue
            try:
                hist = history_service.get_device_history(
                    source="panos-direct",
                    entity_id=entity_id,
                    artifact_type="effective",
                )
                device["history_v1"] = build_history_payload(hist)
            except Exception:  # pragma: no cover – safe degradation
                device["history_v1"] = {
                    "status": "unavailable",
                    "scope": "single_entity_single_artifact",
                }

    devices.sort(key=lambda item: str(item.get("name") or "").lower())
    selected = _integer(summary.get("selected"))
    primary = _integer(summary.get("primary_evidence_success"))
    classification_counts = {
        str(key): _integer(value)
        for key, value in _as_dict(summary.get("setting_alignment_classifications")).items()
    }
    # Prefer collector aggregate counts when available; derived device totals
    # serve as a compatibility fallback for older A4.2 payloads.
    if not classification_counts:
        classification_counts = dict(sorted(total_findings.items()))

    fleet = {
        "run_id": summary.get("run_id"),
        "stage": summary.get("stage"),
        "selected": selected,
        "success": _integer(summary.get("success")),
        "failed": _integer(summary.get("failed")),
        "partial": _integer(summary.get("partial")),
        "primary_evidence_success": primary,
        "alignment_evidence_complete": _integer(summary.get("alignment_evidence_complete")),
        "classification_counts": classification_counts,
        "category_counts": _as_dict(summary.get("setting_alignment_category_counts")) or _aggregate_category_counts(devices),
        "devices_with_local_override": _integer(summary.get("setting_alignment_devices_with_local_override")),
        "devices_with_effective_drift": _integer(summary.get("setting_alignment_devices_with_effective_drift")),
        "devices_out_of_sync": _integer(summary.get("setting_alignment_devices_out_of_sync")),
        "devices_with_provenance_unverified": devices_with_provenance_unverified,
        "devices_with_member_specific": devices_with_member_specific,
        "devices_with_coverage_gaps": devices_with_coverage_gaps,
        "member_specific": _integer(summary.get("semantic_policy_member_specific")),
        "provenance_unverified": _integer(summary.get("semantic_policy_provenance_unverified")),
        "identity_translation_required": _integer(summary.get("semantic_policy_identity_translation_required")),
        "expected_only": classification_counts.get("EXPECTED_ONLY", 0),
        "local_only": classification_counts.get("LOCAL_ONLY", 0),
        "unknown": classification_counts.get("UNKNOWN", 0),
        "aligned": classification_counts.get("ALIGNED", 0),
        "local_override": classification_counts.get("LOCAL_OVERRIDE", 0),
        "effective_drift": classification_counts.get("EFFECTIVE_DRIFT", 0),
        "panorama_out_of_sync": classification_counts.get("PANORAMA_OUT_OF_SYNC", 0),
        "observed_percent": round((total_observed / total_ready * 100.0), 2) if total_ready else 0.0,
        "alignment_ready_settings": total_ready or _integer(summary.get("setting_alignment_alignment_ready_settings")),
        "expected_settings": _integer(summary.get("setting_alignment_expected_settings")),
        "first": _integer(summary.get("first")),
        "same": _integer(summary.get("same")),
        "changed": _integer(summary.get("changed")),
        "method_failures": _integer(summary.get("method_failures_total")),
        "tls_verify": bool(_as_dict(_as_dict(config_result.get("transport")).get("direct_firewall")).get("tls_verify")),
        "ca_bundle_configured": bool(
            _as_dict(_as_dict(config_result.get("transport")).get("direct_firewall")).get("ca_bundle_configured")
        ),
        "a4_2_2_stage_pass": summary.get("a4_2_2_stage_pass"),
    }

    return {
        "schema_version": CONFIG_UI_SCHEMA_VERSION,
        "available": True,
        "local_only": True,
        "build": "phase-0.6.0A4.3.3.2-pan-ha-runtime-role-development-workflow-modes",
        "workflow": _as_dict(workflow_context),
        "information_architecture": {
            "configuration": "current_actual_device_configuration",
            "alignment": "expected_vs_current_reconciliation",
            "policy_objects": "separate_future_management_policy_plane",
            "history": "configuration_change_over_time",
            "backup": "vendor_native_recovery_artifact",
        },
        "fleet": fleet,
        "devices": devices,
        "classification_labels": CLASSIFICATION_LABELS,
        "category_labels": CATEGORY_LABELS,
        "backup": {
            "status": "not_configured",
            "phase": "0.6.0B",
            "message": "Native PAN device-state backup is the next configuration-plane capability.",
        },
        "privacy": {
            "structured_current_values_included": True,
            "raw_configuration_blob_included": False,
            "secret_values_redacted": True,
            "value_hashes_included": False,
            "credentials_included": False,
            "setting_paths_local_ui_only": True,
            "device_identity_local_ui_only": True,
        },
        "structured_current_values_included": True,
        "raw_configuration_blob_included": False,
        "value_hashes_included": False,
    }


def _checkpoint_current_copy(value: Any) -> dict[str, Any]:
    """Return only the explicit secret-safe projection contract for browser use."""
    current = _as_dict(value)
    if current.get("status") != "available":
        return {
            "schema_version": current.get("schema_version") or "0.6.1B",
            "status": "unavailable",
            "vendor": "check_point",
            "reason": current.get("reason") or "checkpoint_configuration_unavailable",
            "source_plane": current.get("source_plane") or "gaia-clish-show-configuration",
            "sections": [],
            "highlights": [],
            "structured_values_included": False,
            "raw_config_included": False,
            "secrets_redacted": True,
        }
    sections = []
    for section in _as_list(current.get("sections")):
        section = _as_dict(section)
        rows = []
        for row in _as_list(section.get("settings")):
            row = _as_dict(row)
            rows.append({
                "setting": row.get("setting"),
                "value": row.get("value"),
                "origin": row.get("origin") or "local",
                "context": row.get("context"),
                "member_specific": bool(row.get("member_specific")),
            })
        sections.append({
            "id": section.get("id"),
            "label": section.get("label"),
            "count": len(rows),
            "settings": rows,
        })
    highlights = []
    for row in _as_list(current.get("highlights")):
        row = _as_dict(row)
        highlights.append({
            "label": row.get("label"),
            "value": row.get("value"),
            "section": row.get("section"),
            "section_label": row.get("section_label"),
            "origin": row.get("origin") or "local",
            "context": row.get("context"),
        })
    return {
        "schema_version": current.get("schema_version") or "0.6.1B",
        "status": "available",
        "vendor": "check_point",
        "source_plane": current.get("source_plane") or "gaia-clish-show-configuration",
        "entity_type": current.get("entity_type"),
        "sections": sections,
        "section_index": [
            {"id": section.get("id"), "label": section.get("label"), "count": section.get("count")}
            for section in sections
        ],
        "highlights": highlights,
        "setting_count": sum(_integer(section.get("count")) for section in sections),
        "redacted_secret_setting_count": _integer(current.get("redacted_secret_setting_count")),
        "projection_scope": current.get("projection_scope"),
        "native_view": _as_dict(current.get("native_view")),
        "structured_values_included": True,
        "raw_config_included": False,
        "secrets_redacted": True,
    }


def _checkpoint_ui_devices(checkpoint_result: dict[str, Any] | None) -> list[dict[str, Any]]:
    result = _as_dict(checkpoint_result)
    raw_devices = [_as_dict(row) for row in _as_list(result.get("devices"))]
    from configuration.checkpoint_config_alignment import align_checkpoint_management_intent
    alignment_payload = align_checkpoint_management_intent(result)
    alignment_by_entity = {
        str(row.get("entity_id") or ""): row
        for row in _as_list(alignment_payload.get("entities"))
        if _as_dict(row).get("entity_id")
    }
    context_counts = Counter(
        str(row.get("parent_entity_id") or "")
        for row in raw_devices
        if row.get("entity_type") == "virtual_system" and row.get("parent_entity_id")
    )
    devices = []
    for row in raw_devices:
        alignment_detail = _as_dict(alignment_by_entity.get(str(row.get("entity_id") or row.get("device") or "")))
        alignment_summary = _as_dict(alignment_detail.get("summary"))
        alignment_counts = {
            str(key): _integer(value)
            for key, value in _as_dict(alignment_summary.get("classification_counts")).items()
        }
        alignment_findings = [
            _as_dict(item)
            for item in _as_list(alignment_detail.get("results"))
            if _as_dict(item).get("classification") in DETAIL_CLASSIFICATIONS
        ]
        entity_type = str(row.get("entity_type") or "unknown")
        current = _checkpoint_current_copy(row.get("current_configuration"))
        success = row.get("status") == "success" and current.get("status") == "available"
        member_count = _integer(row.get("member_specific_setting_count"))
        if entity_type == "clusterxl_member":
            scope_label = "ClusterXL"
            scope = row.get("cluster_display_name") or row.get("cluster_group_id") or "Member"
        elif entity_type == "vsx_host":
            scope_label = "VSX"
            scope = "Host"
        elif entity_type == "virtual_system":
            scope_label = "VSX Context"
            scope = f"VSID {row.get('vs_id')}" if row.get("vs_id") not in (None, "") else "Virtual System"
        else:
            scope_label = "Entity"
            scope = "Standalone gateway"

        evidence_actual = _as_dict(_as_dict(row.get("evidence")).get("actual"))
        devices.append({
            "id": "cp:" + str(row.get("entity_id") or row.get("device") or "unknown"),
            "name": row.get("display_name") or row.get("device"),
            "device_name": row.get("device"),
            "vendor": "Check Point",
            "vendor_key": "check_point",
            "entity_type": entity_type,
            "parent_name": row.get("parent_name"),
            "parent_entity_id": row.get("parent_entity_id"),
            "cluster_group_id": row.get("cluster_group_id"),
            "cluster_display_name": row.get("cluster_display_name"),
            "presentation_group_id": row.get("presentation_group_id"),
            "presentation_group_label": row.get("presentation_group_label"),
            "presentation_group_source": row.get("presentation_group_source"),
            "platform": _as_dict(row.get("platform")),
            "platform_family": _as_dict(row.get("platform")).get("family") or "unknown",
            "platform_label": _as_dict(row.get("platform")).get("label") or "Check Point platform",
            "management_state": row.get("management_state"),
            "failure_reason": row.get("error_class"),
            "failure_family": row.get("failure_family"),
            "serial": row.get("serial"),
            "management_ip": row.get("management_ip"),
            "model": row.get("model"),
            "sw_version": row.get("sw_version"),
            "ha_role": row.get("ha_role"),
            "ha_role_source": row.get("ha_role_source"),
            "vsys_count": context_counts.get(str(row.get("entity_id") or ""), 0) if entity_type == "vsx_host" else (1 if entity_type == "virtual_system" else 0),
            "policy_scope_label": scope_label,
            "policy_scope": scope,
            "connected": success,
            "status": row.get("status"),
            "primary_evidence_status": "success" if success else "failed",
            "alignment_evidence_status": alignment_detail.get("status") or "insufficient_evidence",
            "started_at": row.get("started_at"),
            "completed_at": row.get("completed_at"),
            "tone": "success" if success else ("muted" if row.get("failure_family") == "capability_gap" else "danger"),
            "status_label": "Current" if success else ("Capability gap" if row.get("failure_family") == "capability_gap" else "Collection issue"),
            "assignment": {},
            "current_configuration": current,
            "alignment": {
                "available": alignment_detail.get("status") in {"success", "partial"},
                "status": alignment_detail.get("status") or "insufficient_evidence",
                "device_status": alignment_detail.get("device_status") or "INSUFFICIENT_EVIDENCE",
                "reason": alignment_detail.get("reason") or "trusted_management_intent_projection_unavailable",
                "message": (
                    "Trusted Check Point Management intent was compared with direct actual evidence."
                    if alignment_detail.get("status") in {"success", "partial"}
                    else "Trusted Check Point Management intent is unavailable; direct actual evidence is not treated as expected state."
                ),
                "counts": alignment_counts or ({"MEMBER_SPECIFIC": member_count} if member_count else {}),
                "categories": _category_rows(_as_dict(alignment_summary.get("category_counts"))),
                "findings": alignment_findings,
                "peer_evidence_incomplete": bool(alignment_summary.get("peer_evidence_incomplete")),
                "raw_values_included": False,
                "value_hashes_included": False,
            },
            "evidence": {"actual": {
                "role": evidence_actual.get("role") or "Primary actual Gaia configuration evidence",
                "status": evidence_actual.get("status") or ("success" if success else "unavailable"),
                "method": evidence_actual.get("method"),
                "transport": evidence_actual.get("transport") or "direct_ssh",
                "change_state": evidence_actual.get("change_state"),
                "size_bytes": _integer(evidence_actual.get("size_bytes")),
                "schema_status": evidence_actual.get("schema_status") or "redacted_text_valid",
            }},
            "history": {
                "actual_change_state": _as_dict(row.get("history")).get("actual_change_state"),
                "effective_change_state": _as_dict(row.get("history")).get("actual_change_state"),
            },
            "identity": {
                "status": _as_dict(row.get("identity_gate")).get("status"),
                "confidence": _as_dict(row.get("identity_gate")).get("confidence"),
            },
            "host_key_policy": row.get("host_key_policy"),
            "history_v1": None,  # populated by caller with history_service
        })
    return devices


def _build_cp_history_projections(
    devices: list[dict[str, Any]],
    cp_result: dict[str, Any],
    history_service: ConfigHistoryService,
) -> None:
    """Attach safe history_v1 projections to CP device dicts in-place.

    Only the gaia_show_configuration_redacted timeline is available for CP;
    diff status will be INSUFFICIENT_EVIDENCE per the 0.6.3 contract.
    """
    for device in devices:
        if device.get("vendor_key") != "check_point":
            continue
        entity_id = str(device.get("device_name") or device.get("name") or "")
        if not entity_id:
            device["history_v1"] = {"status": "insufficient_evidence", "scope": "single_entity_single_artifact"}
            continue
        try:
            hist = history_service.get_device_history(
                source="checkpoint-gaia",
                entity_id=entity_id,
                artifact_type="gaia_show_configuration_redacted",
            )
            device["history_v1"] = build_history_payload(hist)
        except Exception:  # pragma: no cover – safe degradation
            device["history_v1"] = {"status": "unavailable", "scope": "single_entity_single_artifact"}


def build_configuration_ui_payload(
    config_result: dict[str, Any] | None,
    *,
    checkpoint_config_result: dict[str, Any] | None = None,
    workflow_context: dict[str, Any] | None = None,
    history_service: ConfigHistoryService | None = None,
) -> dict[str, Any]:
    payload = _build_pan_configuration_ui_payload(
        config_result,
        workflow_context=workflow_context,
        history_service=history_service,
    )
    cp_result = _as_dict(checkpoint_config_result)
    cp_devices = _checkpoint_ui_devices(cp_result)
    if history_service is not None and cp_devices:
        _build_cp_history_projections(cp_devices, cp_result, history_service)
    if not cp_devices:
        # Preserve the 0.6.1B payload build marker for compatibility when no
        # Check Point entity is present. B.1 hardening metadata is attached
        # only when the Check Point adapter actually participates.
        payload["build"] = "phase-0.6.1B-check-point-configuration-ui-integration" if payload.get("available") else payload.get("build")
        return payload

    pan_devices = list(_as_list(payload.get("devices"))) if payload.get("available") else []
    pan_fleet = _as_dict(payload.get("fleet")) if payload.get("available") else {}
    cp_summary = _as_dict(cp_result.get("summary"))
    combined_devices = pan_devices + cp_devices
    combined_devices.sort(key=lambda item: (
        str(item.get("vendor") or "").lower(),
        str(item.get("parent_name") or item.get("name") or "").lower(),
        1 if item.get("entity_type") == "virtual_system" else 0,
        str(item.get("name") or "").lower(),
    ))

    pan_selected = _integer(pan_fleet.get("selected"))
    pan_success = _integer(pan_fleet.get("primary_evidence_success"))
    cp_selected = _integer(cp_summary.get("selected"))
    cp_success = _integer(cp_summary.get("success"))
    fleet = dict(pan_fleet)
    fleet.update({
        "selected": pan_selected + cp_selected,
        "success": _integer(pan_fleet.get("success")) + cp_success,
        "failed": _integer(pan_fleet.get("failed")) + _integer(cp_summary.get("failed")),
        "primary_evidence_success": pan_success + cp_success,
        "first": _integer(pan_fleet.get("first")) + _integer(cp_summary.get("first")),
        "same": _integer(pan_fleet.get("same")) + _integer(cp_summary.get("same")),
        "changed": _integer(pan_fleet.get("changed")) + _integer(cp_summary.get("changed")),
        "method_failures": _integer(pan_fleet.get("method_failures")) + _integer(cp_summary.get("operational_failures", cp_summary.get("failed"))),
        "devices_with_coverage_gaps": _integer(pan_fleet.get("devices_with_coverage_gaps")) + _integer(cp_summary.get("unavailable", cp_summary.get("failed"))),
        "pan_selected": pan_selected,
        "pan_success": pan_success,
        "checkpoint_selected": cp_selected,
        "checkpoint_planned_entities": _integer(cp_summary.get("planned_entities", cp_selected)),
        "checkpoint_unmaterialized_entities": _integer(cp_summary.get("unmaterialized_entities")),
        "checkpoint_success": cp_success,
        "checkpoint_secret_bearing_lines_withheld": _integer(cp_summary.get("secret_bearing_lines_withheld")),
        "checkpoint_member_specific_settings": _integer(cp_summary.get("member_specific_settings")),
        "checkpoint_host_key_policy": cp_summary.get("host_key_policy"),
        "checkpoint_production_trust_ready": bool(cp_summary.get("production_trust_ready")),
        "checkpoint_operational_failures": _integer(cp_summary.get("operational_failures")),
        "checkpoint_capability_gaps": _integer(cp_summary.get("capability_gaps")),
        "checkpoint_coverage_complete": bool(cp_summary.get("coverage_complete")),
        "checkpoint_failure_reason_counts": _as_dict(cp_summary.get("failure_reason_counts")),
        "checkpoint_failure_family_counts": _as_dict(cp_summary.get("failure_family_counts")),
        "checkpoint_entity_type_counts": _as_dict(cp_summary.get("entity_type_counts")),
        "checkpoint_platform_counts": _as_dict(cp_summary.get("platform_counts")),
        "checkpoint_model_covered": _integer(cp_summary.get("model_covered")),
        "checkpoint_serial_covered": _integer(cp_summary.get("serial_covered")),
        "checkpoint_ha_role_covered": _integer(cp_summary.get("ha_role_covered")),
        "checkpoint_gaia_embedded_entities": _integer(cp_summary.get("gaia_embedded_entities")),
        "checkpoint_gaia_embedded_success": _integer(cp_summary.get("gaia_embedded_success")),
        "checkpoint_management_reported_down_entities": _integer(cp_summary.get("management_reported_down_entities")),
        "checkpoint_management_reported_down_hosts": _integer(cp_summary.get("management_reported_down_hosts")),
        "checkpoint_platform_unknown_entities": _integer(cp_summary.get("platform_unknown_entities")),
        "vendor_counts": {
            "palo_alto": {"selected": pan_selected, "success": pan_success},
            "check_point": {"selected": cp_selected, "success": cp_success},
        },
        "alignment_supported_selected": pan_selected,
        "alignment_supported_success": _integer(pan_fleet.get("alignment_evidence_complete")),
    })

    payload.update({
        "schema_version": CONFIG_UI_SCHEMA_VERSION,
        "available": True,
        "local_only": True,
        "build": "phase-0.6.1B.1-check-point-configuration-coverage-device-ux-hardening",
        "workflow": _as_dict(workflow_context),
        "fleet": fleet,
        "devices": combined_devices,
        "structured_current_values_included": True,
        "raw_configuration_blob_included": False,
        "value_hashes_included": False,
    })
    payload.setdefault("privacy", {}).update({
        "structured_current_values_included": True,
        "raw_configuration_blob_included": False,
        "secret_values_redacted": True,
        "value_hashes_included": False,
        "credentials_included": False,
        "checkpoint_raw_configuration_included": False,
    })
    return payload
