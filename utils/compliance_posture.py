from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from utils.compliance_catalog import (
    CATALOG_VERSION,
    LEGACY_CONTROL_IDS,
    catalog_enrichment_controls,
    catalog_entry,
    severity_weight,
)
from utils.compliance_check_engine import evaluate_check, redacted_selector
from utils.compliance_check_pack import CompliancePack, load_compliance_checks
from utils.compliance_history import history_view
from utils.framework_catalog import (
    FRAMEWORK_CATALOG_VERSION,
    framework_entry,
    normalize_ref,
    requirements_for,
)
from utils.compliance_evaluators_ext import ENRICHMENT_EVALUATORS, evaluate_enrichment_control
from utils.compliance_rulepack import (
    BASELINE_CONTROLS,
    DEFAULT_RULE_PACK,
    rule_pack_summary,
)
from utils.control_assignment import ControlAssignmentPolicy, load_control_assignments


BASE_DIR = Path(__file__).resolve().parent.parent

COMPLIANCE_SCHEMA_VERSION = "0.6.6B"
STATUS_VALUES = ("PASS", "FINDING", "UNKNOWN", "NOT_APPLICABLE", "PLANNED", "WAIVED")

_FRAMEWORKS = ("CIS", "PCI-DSS", "BDDK")
_ENRICHMENT_CONTROLS = catalog_enrichment_controls()
_ENRICHMENT_BY_ID = {c["control_id"]: c for c in _ENRICHMENT_CONTROLS}
# Alignment (roll-up numerator) is PASS only; WAIVED is tracked but never
# counted as aligned or as a finding.
_ALIGNED_STATUSES = frozenset({"PASS"})
_DENOMINATOR_STATUSES = frozenset({"PASS", "FINDING", "UNKNOWN", "PLANNED"})


# 0.6.6B: the ten deterministic controls now live in utils.compliance_rulepack
# as the single source of truth; subject-control evaluation is routed through
# DEFAULT_RULE_PACK (see _subject_controls). This alias preserves the internal
# symbol and its former shape.
VENDOR_NEUTRAL_CONTROLS: tuple[dict[str, Any], ...] = BASELINE_CONTROLS


def _as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _bool(value: Any) -> bool:
    return bool(value)


def _int(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def _status_counts() -> dict[str, int]:
    return {key: 0 for key in STATUS_VALUES}


def _mapping(control_area: str, cis_reference: str | None = None) -> dict[str, dict[str, Any]]:
    # Intentionally generic in this foundation build: evidence-backed area mapping,
    # not normative control-number claims.
    return {
        "cis": {
            "alignment": "evidence-backed control area",
            "control_area": control_area,
            "framework_reference": cis_reference,
            "mapping_type": "INFORMATIONAL",
        },
        "pci_dss": {
            "alignment": "evidence-backed control area",
            "control_area": control_area,
            "framework_reference": None,
            "mapping_type": "INFORMATIONAL",
        },
        "bddk": {
            "alignment": "evidence-backed control area",
            "control_area": control_area,
            "framework_reference": None,
            "mapping_type": "INFORMATIONAL",
        },
    }


def _roadmap_link_map(project_plan: dict[str, Any]) -> dict[str, dict[str, Any]]:
    links: dict[str, dict[str, Any]] = {}

    for feature in _as_list(project_plan.get("completed_features")):
        row = _as_dict(feature)
        feature_id = str(row.get("id") or "").strip()
        if feature_id:
            links[feature_id] = {
                "feature_id": feature_id,
                "title": row.get("title") or feature_id,
                "status": row.get("status") or "done",
                "target": row.get("target") or row.get("introduced"),
            }

    for track in _as_list(project_plan.get("tracks")):
        for feature in _as_list(_as_dict(track).get("features")):
            row = _as_dict(feature)
            feature_id = str(row.get("id") or "").strip()
            if feature_id:
                links[feature_id] = {
                    "feature_id": feature_id,
                    "title": row.get("title") or feature_id,
                    "status": row.get("status") or "planned",
                    "target": row.get("target") or row.get("introduced"),
                }

    for item in _as_list(project_plan.get("backlog")):
        row = _as_dict(item)
        item_id = str(row.get("id") or "").strip()
        if item_id and item_id not in links:
            links[item_id] = {
                "feature_id": item_id,
                "title": row.get("title") or item_id,
                "status": row.get("status") or "planned",
                "target": row.get("target"),
            }
    return links


def _link_rows(link_map: dict[str, dict[str, Any]], *feature_ids: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for feature_id in feature_ids:
        row = _as_dict(link_map.get(feature_id))
        if row:
            rows.append(row)
    return rows


def _control(
    control_id: str,
    title: str,
    status: str,
    evidence_summary: str,
    control_area: str,
    *,
    scope: str = "FLEET",
    benchmark: str | None = None,
    benchmark_reference: str | None = None,
    evidence_fields: list[str] | None = None,
    evidence_plane: str = "direct_actual",
    evidence_coverage: str = "complete",
    control_lifecycle: str = "IMPLEMENTED",
    planned_reason: str | None = None,
    future_evidence_requirement: str | None = None,
    roadmap_links: list[dict[str, Any]] | None = None,
    rule_pack: dict[str, Any] | None = None,
    severity: str | None = None,
    rationale: str | None = None,
    frameworks: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    normalized_status = status if status in STATUS_VALUES else "UNKNOWN"
    return {
        "control_id": control_id,
        "title": title,
        "scope": scope,
        "status": normalized_status,
        "control_lifecycle": control_lifecycle,
        "benchmark": benchmark,
        "benchmark_reference": benchmark_reference,
        "evidence_area": "evidence-backed control area",
        "evidence_plane": evidence_plane,
        "evidence_coverage": evidence_coverage,
        "evidence_fields": evidence_fields or [],
        "planned_reason": planned_reason,
        "future_evidence_requirement": future_evidence_requirement,
        "evidence_summary": evidence_summary,
        "framework_mappings": _mapping(control_area, benchmark_reference if benchmark == "CIS" else None),
        "applicable_vendors": ["check_point", "palo_alto"],
        "roadmap_links": roadmap_links or [],
        # 0.6.6B: rule-pack provenance for the ten baseline controls; None for
        # the separate platform/fleet posture controls.
        "rule_pack": rule_pack,
        # 0.7.1a: catalog metadata (None for platform/fleet posture controls).
        "severity": severity,
        "rationale": rationale,
        "frameworks": frameworks or [],
    }


def _section_present(device: dict[str, Any], section_id: str) -> bool:
    current = _as_dict(device.get("current_configuration"))
    for section in _as_list(current.get("sections")):
        row = _as_dict(section)
        if str(row.get("id") or "") == section_id and _as_list(row.get("settings")):
            return True
    return False


def _section_settings(device: dict[str, Any], section_id: str) -> list[dict[str, Any]]:
    current = _as_dict(device.get("current_configuration"))
    for section in _as_list(current.get("sections")):
        row = _as_dict(section)
        if str(row.get("id") or "") == section_id:
            return [_as_dict(item) for item in _as_list(row.get("settings"))]
    return []


def _all_settings(device: dict[str, Any]) -> list[dict[str, Any]]:
    current = _as_dict(device.get("current_configuration"))
    rows: list[dict[str, Any]] = []
    for section in _as_list(current.get("sections")):
        for item in _as_list(_as_dict(section).get("settings")):
            rows.append(_as_dict(item))
    return rows


def _setting_has_token(
    settings: list[dict[str, Any]],
    *tokens: str,
) -> bool:
    wanted = tuple(token.strip().lower() for token in tokens if token.strip())
    if not wanted:
        return False
    for setting in settings:
        name = str(setting.get("setting") or "").strip().lower()
        if not name:
            continue
        if all(token in name for token in wanted):
            return True
    return False


def _setting_with_value(
    settings: list[dict[str, Any]],
    *tokens: str,
) -> bool:
    wanted = tuple(token.strip().lower() for token in tokens if token.strip())
    if not wanted:
        return False
    for setting in settings:
        name = str(setting.get("setting") or "").strip().lower()
        value = str(setting.get("value") or "").strip()
        if all(token in name for token in wanted) and value:
            return True
    return False


def _extract_first_int(*values: str) -> int | None:
    for value in values:
        digits = ""
        for char in str(value or ""):
            if char.isdigit():
                digits += char
            elif digits:
                break
        if digits:
            try:
                return int(digits)
            except ValueError:
                continue
    return None


def _is_disabled_text(text: str) -> bool:
    normalized = str(text or "").strip().lower()
    return any(token in normalized for token in ("disable", "disabled", "off", "false", "no"))


def _is_enabled_text(text: str) -> bool:
    normalized = str(text or "").strip().lower()
    return any(token in normalized for token in ("enable", "enabled", "on", "true", "yes"))


def _catalog_meta(control_id: str) -> dict[str, Any]:
    entry = catalog_entry(control_id) or {}
    return {
        "severity": entry.get("severity"),
        "rationale": entry.get("rationale"),
        "frameworks": [dict(f) for f in entry.get("frameworks", [])],
    }


def _implemented_control(
    control: dict[str, Any],
    status: str,
    summary: str,
    *,
    coverage: str = "complete",
) -> dict[str, Any]:
    return _control(
        str(control.get("control_id") or ""),
        str(control.get("title") or "Control"),
        status,
        summary,
        str(control.get("control_area") or "evidence-backed control area"),
        scope="SUBJECT",
        benchmark="CIS",
        benchmark_reference=str(control.get("cis_reference") or "") or None,
        evidence_fields=list(control.get("evidence_fields") or []),
        evidence_plane="direct_actual",
        evidence_coverage=coverage,
        control_lifecycle="IMPLEMENTED",
        **_catalog_meta(str(control.get("control_id") or "")),
    )


def _planned_evidence_gap(control: dict[str, Any]) -> dict[str, Any]:
    return _control(
        str(control.get("control_id") or ""),
        str(control.get("title") or "Control"),
        "PLANNED",
        "This benchmark control is defined and vendor-neutral, but its normalized evidence adapter is not complete for deterministic evaluation in this build.",
        str(control.get("control_area") or "evidence-backed control area"),
        scope="SUBJECT",
        benchmark="CIS",
        benchmark_reference=str(control.get("cis_reference") or "") or None,
        evidence_fields=list(control.get("evidence_fields") or []),
        evidence_plane="direct_actual",
        evidence_coverage="not_collected",
        control_lifecycle="PLANNED_EVIDENCE_GAP",
        planned_reason=str(control.get("planned_reason") or "Normalized evidence adapter is not available."),
        future_evidence_requirement=str(control.get("future_evidence_requirement") or "normalized.adapter.contract"),
        **_catalog_meta(str(control.get("control_id") or "")),
    )


def _evaluate_hostname_control(device: dict[str, Any], control: dict[str, Any]) -> dict[str, Any]:
    settings = _section_settings(device, "system")
    if not settings:
        return _implemented_control(control, "UNKNOWN", "System section is unavailable for hostname evaluation.", coverage="partial")
    value = ""
    for setting in settings:
        name = str(setting.get("setting") or "").strip().lower()
        if "hostname" in name:
            value = str(setting.get("value") or "").strip()
            break
    if not value:
        return _implemented_control(control, "FINDING", "System section is present but hostname value was not observed.")

    baseline_names = {
        "localhost",
        "localhost.localdomain",
        "gateway",
        "firewall",
        "checkpoint",
        "paloalto",
        "default",
    }
    if value.strip().lower() in baseline_names:
        return _implemented_control(control, "FINDING", "Hostname is present but still appears to be a baseline/default value.")
    if _setting_with_value(settings, "hostname"):
        return _implemented_control(control, "PASS", "Hostname setting is present with a non-default value in normalized current-state evidence.")
    return _implemented_control(control, "FINDING", "System section is present but hostname value was not observed.")


def _evaluate_dns_control(device: dict[str, Any], control: dict[str, Any]) -> dict[str, Any]:
    settings = _section_settings(device, "dns")
    if not settings:
        return _implemented_control(control, "UNKNOWN", "DNS section is unavailable for primary/secondary DNS evaluation.", coverage="partial")
    has_primary = _setting_with_value(settings, "primary", "dns")
    has_secondary = _setting_with_value(settings, "secondary", "dns")
    if has_primary and has_secondary:
        return _implemented_control(control, "PASS", "Both primary and secondary DNS settings are present in normalized current-state evidence.")
    return _implemented_control(control, "FINDING", "DNS section is present but one or more required DNS server settings were not observed.")


def _evaluate_ntp_control(device: dict[str, Any], control: dict[str, Any]) -> dict[str, Any]:
    settings = _section_settings(device, "ntp")
    if not settings:
        return _implemented_control(control, "UNKNOWN", "NTP section is unavailable for primary/secondary NTP evaluation.", coverage="partial")
    has_primary = _setting_with_value(settings, "primary", "ntp")
    has_secondary = _setting_with_value(settings, "secondary", "ntp")
    if has_primary and has_secondary:
        return _implemented_control(control, "PASS", "Both primary and secondary NTP settings are present in normalized current-state evidence.")
    return _implemented_control(control, "FINDING", "NTP section is present but one or more required NTP server settings were not observed.")


def _evaluate_aaa_presence_control(device: dict[str, Any], control: dict[str, Any]) -> dict[str, Any]:
    settings = _section_settings(device, "authentication")
    if not settings:
        return _implemented_control(control, "UNKNOWN", "Authentication section is unavailable for AAA provider evaluation.", coverage="partial")
    has_aaa = False
    for setting in settings:
        name = str(setting.get("setting") or "").strip().lower()
        value = str(setting.get("value") or "").strip().lower()
        if any(token in name or token in value for token in ("radius", "tacacs", "ldap")):
            has_aaa = True
            break
    if has_aaa:
        return _implemented_control(control, "PASS", "AAA provider evidence (RADIUS/TACACS/LDAP) is present in normalized configuration settings.")
    return _implemented_control(control, "FINDING", "Authentication section is present but AAA provider evidence was not observed.")


def _evaluate_telnet_disabled_control(device: dict[str, Any], control: dict[str, Any]) -> dict[str, Any]:
    observed = [
        row for row in _all_settings(device)
        if "telnet" in str(row.get("setting") or "").strip().lower()
    ]
    if not observed:
        return _implemented_control(control, "UNKNOWN", "No explicit telnet setting was observed in normalized evidence.", coverage="partial")

    if any(_is_disabled_text(str(row.get("setting") or "") + " " + str(row.get("value") or "")) for row in observed):
        return _implemented_control(control, "PASS", "Telnet is explicitly disabled in normalized management settings.")

    if any(_is_enabled_text(str(row.get("setting") or "") + " " + str(row.get("value") or "")) for row in observed):
        return _implemented_control(control, "FINDING", "Telnet appears enabled in normalized management settings.")

    return _implemented_control(control, "UNKNOWN", "Telnet setting was observed but enable/disable state could not be determined.", coverage="partial")


def _evaluate_http_restricted_control(device: dict[str, Any], control: dict[str, Any]) -> dict[str, Any]:
    observed = [
        row for row in _all_settings(device)
        if any(token in str(row.get("setting") or "").strip().lower() for token in ("http", "https", "web", "ssl"))
    ]
    if not observed:
        return _implemented_control(control, "UNKNOWN", "No HTTP/HTTPS management setting was observed in normalized evidence.", coverage="partial")

    explicit_http_enabled = False
    explicit_http_disabled = False
    has_https_443 = False
    for row in observed:
        name = str(row.get("setting") or "").strip().lower()
        value = str(row.get("value") or "").strip().lower()
        combined = f"{name} {value}"
        if "http" in name and _is_enabled_text(combined):
            explicit_http_enabled = True
        if "http" in name and _is_disabled_text(combined):
            explicit_http_disabled = True
        if ("https" in name or "ssl" in name or "web" in name) and "443" in value:
            has_https_443 = True

    if explicit_http_enabled:
        return _implemented_control(control, "FINDING", "HTTP management appears enabled in normalized settings.")
    if explicit_http_disabled or has_https_443:
        return _implemented_control(control, "PASS", "Management protocol evidence indicates HTTP is disabled or HTTPS uses the expected secure port.")
    return _implemented_control(control, "UNKNOWN", "HTTP/HTTPS settings were observed but secure protocol posture could not be concluded.", coverage="partial")


def _evaluate_session_timeout_control(device: dict[str, Any], control: dict[str, Any]) -> dict[str, Any]:
    settings = _section_settings(device, "management")
    if not settings:
        return _implemented_control(control, "UNKNOWN", "Management section is unavailable for session-timeout evaluation.", coverage="partial")

    timeout_value = None
    for row in settings:
        name = str(row.get("setting") or "").strip().lower()
        value = str(row.get("value") or "").strip().lower()
        if "timeout" in name or "idle" in name or "inactivity" in name:
            timeout_value = _extract_first_int(value, name)
            if timeout_value is not None:
                break

    if timeout_value is None:
        return _implemented_control(control, "UNKNOWN", "No numeric session timeout value was observed in normalized management settings.", coverage="partial")
    if timeout_value <= 10:
        return _implemented_control(control, "PASS", f"Observed session timeout value ({timeout_value}) is within the <=10 policy threshold.")
    return _implemented_control(control, "FINDING", f"Observed session timeout value ({timeout_value}) exceeds the <=10 policy threshold.")


def _evaluate_snmp_v3_control(device: dict[str, Any], control: dict[str, Any]) -> dict[str, Any]:
    settings = _section_settings(device, "snmp")
    if not settings:
        return _implemented_control(control, "UNKNOWN", "SNMP section is unavailable for protocol-version evaluation.", coverage="partial")
    combined = "\n".join(
        f"{str(row.get('setting') or '').lower()} {str(row.get('value') or '').lower()}"
        for row in settings
    )
    if "v3" in combined or "version 3" in combined:
        return _implemented_control(control, "PASS", "SNMP configuration evidence indicates version 3 usage.")
    return _implemented_control(control, "FINDING", "SNMP settings are present but explicit version 3 evidence was not observed.")


def _evaluate_update_server_identity_control(device: dict[str, Any], control: dict[str, Any]) -> dict[str, Any]:
    settings = _section_settings(device, "system")
    if not settings:
        return _implemented_control(control, "UNKNOWN", "System section is unavailable for update-server identity verification evaluation.", coverage="partial")

    candidates = []
    for row in settings:
        name = str(row.get("setting") or "").strip().lower()
        value = str(row.get("value") or "").strip().lower()
        if "update" in name:
            candidates.append((name, value))

    if not candidates:
        return _implemented_control(control, "UNKNOWN", "No update-server setting was observed in normalized evidence.", coverage="partial")

    for name, value in candidates:
        combined = f"{name} {value}"
        if "verify" in combined or "identity" in combined:
            if _is_enabled_text(combined):
                return _implemented_control(control, "PASS", "Update-server identity verification appears enabled in normalized settings.")
            if _is_disabled_text(combined):
                return _implemented_control(control, "FINDING", "Update-server identity verification appears disabled in normalized settings.")
    return _implemented_control(control, "UNKNOWN", "Update-server setting is present but identity-verification state is not explicit in normalized evidence.", coverage="partial")


def _evaluate_audit_logging_control(device: dict[str, Any], control: dict[str, Any]) -> dict[str, Any]:
    settings = _section_settings(device, "logging")
    if not settings:
        return _implemented_control(control, "UNKNOWN", "Logging section is unavailable for management audit evaluation.", coverage="partial")
    if settings:
        return _implemented_control(control, "PASS", "Logging/audit-related settings are present in normalized configuration evidence.")
    return _implemented_control(control, "UNKNOWN", "Logging settings could not be evaluated.", coverage="partial")


def _evaluate_vendor_neutral_control(device: dict[str, Any], control: dict[str, Any]) -> dict[str, Any]:
    control_id = str(control.get("control_id") or "")
    if control_id == "hostname_configured_non_default":
        return _evaluate_hostname_control(device, control)
    if control_id == "dns_primary_secondary_configured":
        return _evaluate_dns_control(device, control)
    if control_id == "ntp_primary_secondary_configured":
        return _evaluate_ntp_control(device, control)
    if control_id == "aaa_provider_presence":
        return _evaluate_aaa_presence_control(device, control)
    if control_id == "management_session_timeout_policy":
        return _evaluate_session_timeout_control(device, control)
    if control_id == "telnet_disabled":
        return _evaluate_telnet_disabled_control(device, control)
    if control_id == "http_management_restricted":
        return _evaluate_http_restricted_control(device, control)
    if control_id == "snmp_v3_only":
        return _evaluate_snmp_v3_control(device, control)
    if control_id == "update_server_identity_verified":
        return _evaluate_update_server_identity_control(device, control)
    if control_id == "management_audit_logging":
        return _evaluate_audit_logging_control(device, control)
    return _planned_evidence_gap(control)


def _pan_mgmt_allowlist_present(device: dict[str, Any]) -> bool:
    current = _as_dict(device.get("current_configuration"))
    for section in _as_list(current.get("sections")):
        row = _as_dict(section)
        if str(row.get("id") or "") != "management":
            continue
        for setting in _as_list(row.get("settings")):
            name = str(_as_dict(setting).get("setting") or "").strip().lower()
            if "permitted ip" in name:
                return True
    return False


def _cp_mgmt_allowlist_present(device: dict[str, Any]) -> bool:
    current = _as_dict(device.get("current_configuration"))
    for section in _as_list(current.get("sections")):
        row = _as_dict(section)
        if str(row.get("id") or "") != "management":
            continue
        for setting in _as_list(row.get("settings")):
            name = str(_as_dict(setting).get("setting") or "").strip().lower()
            if "allowed client" in name or "permitted" in name or "trusted client" in name:
                return True
    return False


def _pan_alignment_status(device: dict[str, Any]) -> str:
    counts = _as_dict(_as_dict(device.get("alignment")).get("counts"))
    if _int(counts.get("EFFECTIVE_DRIFT")) > 0 or _int(counts.get("PANORAMA_OUT_OF_SYNC")) > 0:
        return "FINDING"
    if _int(counts.get("LOCAL_OVERRIDE")) > 0:
        return "FINDING"
    uncertain_keys = (
        "PROVENANCE_UNVERIFIED",
        "IDENTITY_TRANSLATION_REQUIRED",
        "EXPECTED_ONLY",
        "LOCAL_ONLY",
        "UNKNOWN",
    )
    if any(_int(counts.get(key)) > 0 for key in uncertain_keys):
        return "UNKNOWN"
    return "PASS"


def _apply_waiver(
    result: dict[str, Any],
    policy: ControlAssignmentPolicy | None,
    device_name: str,
    now: datetime,
) -> dict[str, Any]:
    """0.7.1b: an unexpired waiver turns a (control, subject) cell into WAIVED.

    The pre-waiver status is preserved; free-text reason / approver stay in the
    local policy file and never enter this payload.
    """
    if policy is None:
        return result
    waiver = policy.waiver_for(str(result.get("control_id") or ""), device_name, now)
    if waiver is None:
        return result
    result["pre_waiver_status"] = result.get("status")
    result["status"] = "WAIVED"
    result["waived"] = True
    result["waiver"] = {
        "control_id": waiver.control_id,
        "expires": waiver.expires.isoformat() if waiver.expires else None,
    }
    return result


def _subject_controls(
    device: dict[str, Any],
    policy: ControlAssignmentPolicy | None = None,
    device_name: str = "",
    resolved_ids: frozenset[str] | None = None,
    now: datetime | None = None,
) -> list[dict[str, Any]]:
    has_current = _bool(device.get("connected")) and str(_as_dict(device.get("current_configuration")).get("status") or "") == "available"
    if not has_current:
        return []
    now = now or datetime.now(timezone.utc)
    # 0.6.6B: the ten deterministic controls execute through the default rule
    # pack. Each rule carries the exact keys the evaluators read, so outcomes
    # are unchanged; each result is stamped with rule-pack provenance.
    # 0.7.1b: when a local assignment policy is active a control can be
    # de-scoped for this device (omitted) or waived (status WAIVED).
    results: list[dict[str, Any]] = []
    for rule in DEFAULT_RULE_PACK["rules"]:
        if resolved_ids is not None and rule["control_id"] not in resolved_ids:
            continue
        result = _evaluate_vendor_neutral_control(device, rule)
        result["rule_pack"] = {
            "pack_id": DEFAULT_RULE_PACK["pack_id"],
            "pack_version": DEFAULT_RULE_PACK["pack_version"],
            "rule_id": rule["rule_id"],
        }
        results.append(_apply_waiver(result, policy, device_name, now))
    return results


def _subject_extended_controls(
    device: dict[str, Any],
    policy: ControlAssignmentPolicy | None,
    device_name: str,
    resolved_ids: frozenset[str],
    now: datetime,
) -> list[dict[str, Any]]:
    """0.7.1b enrichment controls — a separate list, not routed through the
    frozen 0.6.6B pack. Evaluated only where the vendor applies and the control
    is in scope for this device."""
    vendor_key = str(device.get("vendor_key") or "")
    results: list[dict[str, Any]] = []
    for control in _ENRICHMENT_CONTROLS:
        control_id = str(control.get("control_id") or "")
        if vendor_key not in list(control.get("applicable_vendors") or []):
            continue
        if control_id not in resolved_ids:
            continue
        status, summary, coverage = evaluate_enrichment_control(device, control_id)
        lifecycle = "IMPLEMENTED" if control_id in ENRICHMENT_EVALUATORS else "PLANNED_EVIDENCE_GAP"
        result = _control(
            control_id,
            str(control.get("title") or "Control"),
            status,
            summary,
            str(control.get("control_area") or "evidence-backed control area"),
            scope="SUBJECT",
            benchmark="CIS",
            benchmark_reference=str(control.get("cis_reference") or "") or None,
            evidence_fields=list(control.get("evidence_fields") or []),
            evidence_plane="direct_actual",
            evidence_coverage=coverage,
            control_lifecycle=lifecycle,
            rule_pack=None,
            severity=control.get("severity"),
            rationale=control.get("rationale"),
            frameworks=[dict(f) for f in control.get("frameworks", [])],
        )
        result["control_class"] = "enrichment"
        results.append(_apply_waiver(result, policy, device_name, now))
    return results


def _platform_controls(
    devices: list[dict[str, Any]],
    link_map: dict[str, dict[str, Any]],
    fleet_tls_verify: Any,
) -> list[dict[str, Any]]:
    cp_rows = [row for row in devices if str(row.get("vendor_key") or "") == "check_point"]
    pan_rows = [row for row in devices if str(row.get("vendor_key") or "") == "palo_alto"]

    def _available(row: dict[str, Any]) -> bool:
        return _bool(row.get("connected")) and str(_as_dict(row.get("current_configuration")).get("status") or "") == "available"

    controls: list[dict[str, Any]] = []

    mgmt_candidates = [row for row in devices if _available(row)]
    if not mgmt_candidates:
        mgmt_status = "UNKNOWN"
        mgmt_summary = "No assessed device has current-state evidence for management-access restriction evaluation."
    else:
        mgmt_findings = 0
        mgmt_pass = 0
        for row in mgmt_candidates:
            vendor_key = str(row.get("vendor_key") or "")
            if vendor_key == "palo_alto":
                mgmt_pass += 1 if _pan_mgmt_allowlist_present(row) else 0
                mgmt_findings += 0 if _pan_mgmt_allowlist_present(row) else 1
            elif vendor_key == "check_point":
                if _cp_mgmt_allowlist_present(row):
                    mgmt_pass += 1
                elif _section_present(row, "management"):
                    mgmt_findings += 1
        if mgmt_findings > 0:
            mgmt_status = "FINDING"
            mgmt_summary = f"Management-access allowlist evidence missing on {mgmt_findings} assessed device(s)."
        elif mgmt_pass > 0:
            mgmt_status = "PASS"
            mgmt_summary = f"Management-access allowlist evidence observed on {mgmt_pass} assessed device(s)."
        else:
            mgmt_status = "UNKNOWN"
            mgmt_summary = "Management-access evidence could not be concluded from assessed devices."

    controls.append(_control(
        "management_access_restriction_observed",
        "Management access restriction evidence observed",
        mgmt_status,
        mgmt_summary,
        "Administrative access restrictions",
        scope="PLATFORM",
        benchmark=None,
        evidence_fields=["current_configuration.sections.management.settings"],
        evidence_plane="direct_actual",
    ))

    pan_available = [row for row in pan_rows if _available(row)]
    if not pan_available:
        pan_align_status = "UNKNOWN"
        pan_align_summary = "No assessed PAN firewall is available for intent-versus-actual alignment evaluation."
    elif any(_pan_alignment_status(row) == "FINDING" for row in pan_available):
        pan_align_status = "FINDING"
        pan_align_summary = "At least one assessed PAN firewall indicates drift/override alignment findings."
    elif any(_pan_alignment_status(row) == "UNKNOWN" for row in pan_available):
        pan_align_status = "UNKNOWN"
        pan_align_summary = "Assessed PAN alignment includes unresolved/coverage-limited evidence categories."
    else:
        pan_align_status = "PASS"
        pan_align_summary = "Assessed PAN alignment shows no drift/override signal in this cycle."

    controls.append(_control(
        "pan_intent_actual_alignment",
        "Panorama intent versus effective alignment",
        pan_align_status,
        pan_align_summary,
        "Intent versus actual control consistency",
        scope="PLATFORM",
        benchmark=None,
        evidence_fields=["alignment.counts"],
        evidence_plane="management_intent_vs_direct_actual",
    ))

    if fleet_tls_verify is True:
        pan_tls_status = "PASS"
    elif fleet_tls_verify is False:
        pan_tls_status = "FINDING"
    else:
        pan_tls_status = "UNKNOWN"
    controls.append(_control(
        "pan_collector_tls_trust",
        "PAN collector TLS peer verification",
        pan_tls_status,
        "Direct firewall collection verifies TLS peers." if pan_tls_status == "PASS" else (
            "Direct firewall collection is running without TLS peer verification." if pan_tls_status == "FINDING" else "TLS verification state is unavailable in this payload."
        ),
        "Collection transport trust",
        scope="PLATFORM",
        benchmark=None,
        evidence_fields=["fleet.tls_verify"],
        evidence_plane="collector_runtime",
    ))

    cp_available = [row for row in cp_rows if _available(row)]
    if not cp_available:
        cp_trust_status = "UNKNOWN"
        cp_trust_summary = "No assessed Check Point firewall is available for SSH host-key trust evaluation."
    elif any(str(row.get("host_key_policy") or "").strip().lower() == "strict_known_hosts" for row in cp_available):
        weak_or_unknown = [
            row for row in cp_available
            if str(row.get("host_key_policy") or "").strip().lower() not in {"strict_known_hosts"}
        ]
        if weak_or_unknown:
            cp_trust_status = "FINDING"
            cp_trust_summary = "At least one assessed Check Point firewall is not on strict known_hosts host-key trust mode."
        else:
            cp_trust_status = "PASS"
            cp_trust_summary = "Assessed Check Point firewalls use strict known_hosts host-key trust mode."
    else:
        cp_trust_status = "UNKNOWN"
        cp_trust_summary = "Check Point host-key trust mode is unavailable in assessed payload rows."

    controls.append(_control(
        "cp_collector_host_key_trust",
        "Check Point collector SSH host-key trust",
        cp_trust_status,
        cp_trust_summary,
        "Collection transport trust",
        scope="PLATFORM",
        benchmark=None,
        evidence_fields=["devices[].host_key_policy"],
        evidence_plane="collector_runtime",
    ))

    controls.append(_control(
        "cp_management_actual_alignment",
        "Check Point management intent versus actual alignment",
        "PLANNED",
        "CP management intent alignment is intentionally deferred and remains a planned evidence-backed control area.",
        "Intent versus actual control consistency",
        scope="PLATFORM",
        benchmark=None,
        evidence_fields=["cp_management_alignment"],
        evidence_plane="management_intent_vs_direct_actual",
        control_lifecycle="PLANNED_ROADMAP",
        roadmap_links=_link_rows(link_map, "cp_management_alignment", "compliance_engine"),
    ))
    controls.append(_control(
        "crypto_agility_pqc_readiness",
        "Crypto-agility and PQC readiness",
        "PLANNED",
        "Crypto-agility/PQC readiness is intentionally planned and linked to later compliance engine expansion.",
        "Cryptographic posture and readiness",
        scope="PLATFORM",
        benchmark=None,
        evidence_fields=["crypto_agility_pqc"],
        evidence_plane="planned",
        control_lifecycle="PLANNED_ROADMAP",
        roadmap_links=_link_rows(link_map, "crypto_agility_pqc", "compliance_engine"),
    ))

    return controls


def _fleet_controls(configuration_ui: dict[str, Any], link_map: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    privacy = _as_dict(configuration_ui.get("privacy"))
    flags_ok = (
        configuration_ui.get("raw_configuration_blob_included") is False
        and privacy.get("raw_configuration_blob_included") is False
        and privacy.get("credentials_included") is False
        and privacy.get("secret_values_redacted") is True
    )
    return [
        _control(
            "evidence_privacy_boundary",
            "Evidence privacy boundary",
            "PASS" if flags_ok else "FINDING",
            "Compliance payload is built from redacted structured evidence and excludes raw configuration and credentials." if flags_ok else "One or more privacy boundary flags indicate unsafe payload composition.",
            "Privacy and evidence handling",
            scope="GLOBAL",
            benchmark=None,
            evidence_fields=["privacy.raw_config_included_flag", "privacy.credentials_included_flag", "privacy.secret_values_redacted_flag"],
            evidence_plane="payload_contract",
        ),
        _control(
            "compliance_engine_transition",
            "Compliance engine transition",
            "PLANNED",
            "This build provides evidence-backed compliance posture foundation; full rule engine and expanded framework mapping remain planned.",
            "Compliance rule-evaluation expansion",
            scope="GLOBAL",
            benchmark=None,
            evidence_fields=["project_plan.tracks"],
            evidence_plane="roadmap",
            control_lifecycle="PLANNED_ROADMAP",
            roadmap_links=_link_rows(link_map, "compliance_engine", "framework_mappings", "evidence_reporting"),
        ),
    ]


def _catalog_control_frameworks(
    extra_meta: dict[str, dict[str, Any]] | None = None,
) -> dict[str, dict[str, Any]]:
    """control_id -> {framework -> applies bool} across baseline + enrichment
    (+ 0.7.3 enforced user checks)."""
    out: dict[str, dict[str, Any]] = {}
    for control_id in LEGACY_CONTROL_IDS:
        entry = catalog_entry(control_id) or {}
        out[control_id] = {
            str(f.get("framework")): bool(f.get("applies"))
            for f in entry.get("frameworks", [])
        }
    for control in _ENRICHMENT_CONTROLS:
        out[str(control["control_id"])] = {
            str(f.get("framework")): bool(f.get("applies"))
            for f in control.get("frameworks", [])
        }
    for control_id, meta in (extra_meta or {}).items():
        out[control_id] = dict(meta.get("frameworks") or {})
    return out


def _control_severity(control_id: str, extra_meta: dict[str, dict[str, Any]] | None = None) -> str:
    entry = catalog_entry(control_id) or {}
    if entry:
        return str(entry.get("severity") or "informational")
    meta = (extra_meta or {}).get(control_id) or {}
    return str(meta.get("severity") or "informational")


def _control_framework_refs(
    control_id: str,
    extra_meta: dict[str, dict[str, Any]] | None = None,
) -> dict[str, str]:
    """0.7.4 — control_id -> {framework: reference string} across baseline +
    enrichment + enforced user checks. The reference is the join key into the
    framework catalog's requirements."""
    entry = catalog_entry(control_id)
    if entry:
        return {
            str(f.get("framework")): str(f.get("reference") or "")
            for f in entry.get("frameworks", [])
        }
    meta = (extra_meta or {}).get(control_id) or {}
    return {str(k): str(v or "") for k, v in (meta.get("framework_refs") or {}).items()}


def _user_check_meta(pack: CompliancePack) -> dict[str, dict[str, Any]]:
    """Enforced user-check id -> {frameworks: {name: applies}, severity, advisory}.

    Advisory checks are omitted so they never enter the coverage roll-up (D6).
    """
    out: dict[str, dict[str, Any]] = {}
    for check in pack.checks:
        if check.advisory:
            continue
        out[check.id] = {
            "frameworks": {
                str(f.get("framework")): bool(f.get("applies"))
                for f in check.frameworks
            },
            "framework_refs": {
                str(f.get("framework")): str(f.get("reference") or "")
                for f in check.frameworks
            },
            "severity": check.severity,
            "advisory": False,
        }
    return out


def _check_pack_block(pack: CompliancePack) -> dict[str, Any]:
    """Counts + pack id only — safe for the shareable artifact (D12)."""
    return {
        "pack_id": pack.pack_id,
        "pack_version": pack.pack_version,
        "source": pack.source,
        "enabled": pack.is_active,
        "checks": len(pack.checks),
        "advisory_checks": pack.advisory_count,
    }


def _norm_identity(value: Any) -> str:
    return str(value or "").strip().lower()


# CE.1 fast-follow: which unified.json `source` values a config subject of a
# given vendor may join to — keeps a coincidental cross-vendor name collision
# from pulling the wrong inventory.
_VENDOR_UNIFIED_SOURCES = {
    "check_point": frozenset({"cp", "vsx"}),
    "palo_alto": frozenset({"panorama"}),
}


def _index_unified_inventory(
    rows: list[dict[str, Any]] | None,
) -> dict[str, list[dict[str, Any]]]:
    """Index the merged inventory list by normalised device identity (and PAN
    serial). One identity can map to several rows (VSX VSIDs, per-vsys records)."""
    index: dict[str, list[dict[str, Any]]] = {}
    for row in _as_list(rows):
        row = _as_dict(row)
        for key in (_norm_identity(row.get("device")), _norm_identity(row.get("serial"))):
            if key:
                index.setdefault(key, []).append(row)
    return index


def _match_unified_rows(
    index: dict[str, list[dict[str, Any]]],
    device: dict[str, Any],
    vendor_key: str,
) -> list[dict[str, Any]]:
    """Exact (normalised) identity join, tolerant of which config-UI field
    carries the name. Zero matches → ``[]`` → the namespace stays unresolved."""
    if not index:
        return []
    allowed_sources = _VENDOR_UNIFIED_SOURCES.get(vendor_key, frozenset())
    seen: set[int] = set()
    matched: list[dict[str, Any]] = []
    candidates = {
        _norm_identity(device.get("device_name")),
        _norm_identity(device.get("name")),
        _norm_identity(device.get("id")),
    }
    candidates.discard("")
    for key in candidates:
        for row in index.get(key, []):
            if allowed_sources and _norm_identity(row.get("source")) not in allowed_sources:
                continue
            if id(row) not in seen:
                seen.add(id(row))
                matched.append(row)
    return matched


def _inventory_collection(rows: list[dict[str, Any]], key: str) -> list[Any] | None:
    """Union of one merged-inventory collection across the matched rows. ``None``
    (no subject match at all) is distinct from ``[]`` (matched, none present) —
    the engine treats ``None`` as no-evidence, ``[]`` as a judged count of 0."""
    if not rows:
        return None
    out: list[Any] = []
    for row in rows:
        out.extend(_as_list(_as_dict(row).get(key)))
    return out


def _subject_evidence(
    device: dict[str, Any],
    crypto_facts: dict[str, Any] | None = None,
    unified_rows: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """The read-only evidence namespaces a user check can assert over (D4).

    In-process only; nothing here is echoed into the payload beyond the bounded
    ``observed`` description the engine produces. ``crypto_facts`` is the
    already-normalised, privacy-reviewed 0.7.0 fact set for this subject
    (``ike_crypto_profiles`` / ``ipsec_crypto_profiles`` / ``ike_gateways`` /
    ``tls_service_profiles`` / ``certificates``) — never key material, PSK or
    certificate body. ``unified_rows`` is the matched merged-inventory record(s)
    for this subject (CE.1 fast-follow); when unmatched the ``interfaces`` /
    ``routes`` namespaces stay ``None`` → any check using them is on_no_evidence.
    """
    return {
        "current_configuration": _as_dict(device.get("current_configuration")),
        "unified": {
            "device": {
                "vendor_key": device.get("vendor_key"),
                "platform_family": device.get("platform_family"),
                "model": device.get("model"),
                "sw_version": device.get("sw_version"),
                "ha_role": device.get("ha_role"),
                "entity_type": device.get("entity_type"),
            },
            "interfaces": _inventory_collection(unified_rows, "interfaces"),
            "routes": _inventory_collection(unified_rows, "routes"),
        },
        "alignment": {
            "results": _as_list(_as_dict(device.get("alignment")).get("findings")),
        },
        "crypto_facts": _as_dict(crypto_facts),
    }


def _subject_user_checks(
    device: dict[str, Any],
    pack: CompliancePack,
    policy: ControlAssignmentPolicy | None,
    device_name: str,
    resolved_ids: frozenset[str],
    now: datetime,
    crypto_facts: dict[str, Any] | None = None,
    unified_rows: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    """0.7.3 (CE.1) — evaluate the user pack's checks for one subject.

    Only checks whose ``applies_to`` matches this subject and that are in scope
    for it (assignment resolution) are evaluated. Advisory checks are marked so
    the roll-up can exclude them; they still render.
    """
    if not pack.is_active:
        return []
    vendor_key = str(device.get("vendor_key") or "")
    platform_family = str(device.get("platform_family") or "")
    entity_type = str(device.get("entity_type") or "")
    evidence = _subject_evidence(device, crypto_facts, unified_rows)
    results: list[dict[str, Any]] = []
    for check in pack.checks:
        if not check.applies_to_subject(
            vendor=vendor_key, platform_family=platform_family, entity_type=entity_type,
        ):
            continue
        if check.id not in resolved_ids:
            continue
        status, summary, coverage, steps = evaluate_check(evidence, check)
        result = _control(
            check.id,
            check.title,
            status,
            summary,
            "user-authored compliance check",
            scope="SUBJECT",
            benchmark="USER",
            benchmark_reference=None,
            evidence_fields=[redacted_selector(step.source) for step in check.steps],
            evidence_plane="direct_actual",
            evidence_coverage=coverage,
            control_lifecycle="IMPLEMENTED",
            rule_pack=None,
            severity=check.severity,
            rationale=check.rationale,
            frameworks=[dict(f) for f in check.frameworks],
        )
        result["control_class"] = "user_check"
        result["advisory"] = check.advisory
        result["pack"] = {
            "pack_id": pack.pack_id,
            "pack_version": pack.pack_version,
            "source": pack.source,
        }
        result["check_steps"] = steps
        results.append(_apply_waiver(result, policy, device_name, now))
    return results


def _scoring_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """0.7.3 (D6) — advisory user checks render but never affect the score."""
    return [row for row in rows if not _as_dict(row).get("advisory")]


def _empty_framework_block(name: str) -> dict[str, Any]:
    fw_cat = framework_entry(name) or {}
    req_rows: list[dict[str, Any]] = []
    req_counts = {"COVERED": 0, "PARTIALLY_COVERED": 0, "UNCOVERED": 0, "NOT_APPLICABLE": 0}
    for requirement in requirements_for(name):
        req_applies = bool(requirement.get("applies", True))
        cov = "UNCOVERED" if req_applies else "NOT_APPLICABLE"
        req_counts[cov] += 1
        req_rows.append({
            "id": requirement["id"], "section": requirement["section"],
            "title": requirement["title"], "control_ids": [], "applicable": req_applies,
            "monitored": 0, "aligned": 0, "finding": 0, "unknown": 0,
            "coverage": cov, "posture": "UNKNOWN",
        })
    return {
        "controls": 0, "monitored": 0, "aligned": 0, "finding": 0, "coverage": "UNCOVERED",
        "version": fw_cat.get("version"), "profile": fw_cat.get("profile"),
        "requirements": req_rows, "requirement_counts": req_counts,
        "unmapped_control_refs": [],
    }


def _empty_overview(history: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    total = len(LEGACY_CONTROL_IDS) + len(_ENRICHMENT_CONTROLS)
    view = history_view(history)  # no current run -> records only, trend None
    return {
        "catalog_version": CATALOG_VERSION,
        "framework_catalog_version": FRAMEWORK_CATALOG_VERSION,
        "total_controls": total,
        "monitored_controls": 0,
        "unmonitored_controls": total,
        "subjects": 0,
        "cells": {"aligned": 0, "finding": 0, "unknown": 0, "planned": 0, "waived": 0},
        "aligned_percent": 0.0,
        "risk_weighted_alignment_percent": 0.0,
        "by_framework": {name: _empty_framework_block(name) for name in _FRAMEWORKS},
        "by_subject": [],
        "history": view["records"],
        "trend": view["trend"],
    }


def _compliance_overview(
    subjects: list[dict[str, Any]],
    extra_meta: dict[str, dict[str, Any]] | None = None,
    history: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    extra_meta = extra_meta or {}
    evaluated = [s for s in subjects if s.get("availability") == "AVAILABLE"]
    fw_applies = _catalog_control_frameworks(extra_meta)

    cells = {"aligned": 0, "finding": 0, "unknown": 0, "planned": 0, "waived": 0}
    weight_num = 0
    weight_den = 0
    # control_id -> did any subject produce hard evidence (PASS/FINDING/WAIVED)
    has_evidence: dict[str, bool] = {}
    assigned_anywhere: set[str] = set()
    per_control_aligned: dict[str, int] = {}
    per_control_finding: dict[str, int] = {}
    per_control_unknown: dict[str, int] = {}
    by_subject: list[dict[str, Any]] = []

    for subject in evaluated:
        rows = _scoring_rows(
            _as_list(subject.get("controls")) + _as_list(subject.get("extended_controls"))
        )
        assignment = _as_dict(subject.get("assignment"))
        assigned_anywhere.update(str(x) for x in _as_list(assignment.get("assigned")))
        s_counts = {"aligned": 0, "finding": 0, "unknown": 0, "planned": 0, "waived": 0}
        for control in rows:
            control_id = str(_as_dict(control).get("control_id") or "")
            status = str(_as_dict(control).get("status") or "UNKNOWN")
            bucket = {
                "PASS": "aligned", "FINDING": "finding", "UNKNOWN": "unknown",
                "PLANNED": "planned", "WAIVED": "waived",
            }.get(status)
            if bucket is None:
                continue
            cells[bucket] += 1
            s_counts[bucket] += 1
            if bucket in ("aligned", "finding", "waived"):
                has_evidence[control_id] = True
            else:
                has_evidence.setdefault(control_id, False)
            if status in _DENOMINATOR_STATUSES:
                w = severity_weight(_control_severity(control_id, extra_meta))
                weight_den += w
                if status in _ALIGNED_STATUSES:
                    weight_num += w
            if bucket == "aligned":
                per_control_aligned[control_id] = per_control_aligned.get(control_id, 0) + 1
            if bucket == "finding":
                per_control_finding[control_id] = per_control_finding.get(control_id, 0) + 1
            if bucket == "unknown":
                per_control_unknown[control_id] = per_control_unknown.get(control_id, 0) + 1
        by_subject.append({
            "subject_id": subject.get("subject_id"),
            "assigned": len(_as_list(assignment.get("assigned"))),
            "aligned": s_counts["aligned"],
            "finding": s_counts["finding"],
            "unknown": s_counts["unknown"],
            "planned": s_counts["planned"],
            "waived": s_counts["waived"],
        })

    all_ids = (
        list(LEGACY_CONTROL_IDS)
        + [str(c["control_id"]) for c in _ENRICHMENT_CONTROLS]
        + list(extra_meta)          # 0.7.3 — enforced user checks (advisory omitted upstream)
    )
    monitored = {
        cid for cid in all_ids
        if cid in assigned_anywhere and has_evidence.get(cid)
    }
    denom = sum(cells[k] for k in ("aligned", "finding", "unknown", "planned"))
    aligned_percent = round(cells["aligned"] / denom * 100, 1) if denom else 0.0
    risk_weighted = round(weight_num / weight_den * 100, 1) if weight_den else 0.0

    # 0.7.4 — control_id -> {framework: normalized ref}, the requirement join key.
    norm_refs = {
        cid: {fw: normalize_ref(ref) for fw, ref in _control_framework_refs(cid, extra_meta).items()}
        for cid in all_ids
    }

    by_framework: dict[str, Any] = {}
    for name in _FRAMEWORKS:
        fw_control_ids = [cid for cid in all_ids if fw_applies.get(cid, {}).get(name)]
        fw_monitored = [cid for cid in fw_control_ids if cid in monitored]
        if not fw_control_ids:
            coverage = "UNCOVERED"
        elif len(fw_monitored) == len(fw_control_ids):
            coverage = "COVERED"
        elif fw_monitored:
            coverage = "PARTIALLY_COVERED"
        else:
            coverage = "UNCOVERED"

        # 0.7.4 — requirement-level roll-up
        fw_cat = framework_entry(name) or {}
        req_rows: list[dict[str, Any]] = []
        req_counts = {"COVERED": 0, "PARTIALLY_COVERED": 0, "UNCOVERED": 0, "NOT_APPLICABLE": 0}
        req_norm_ids: set[str] = set()
        for requirement in requirements_for(name):
            rid_norm = normalize_ref(requirement["id"])
            req_norm_ids.add(rid_norm)
            mapped = [cid for cid in all_ids if rid_norm and norm_refs.get(cid, {}).get(name) == rid_norm]
            applicable_mapped = [cid for cid in mapped if fw_applies.get(cid, {}).get(name)]
            mon = [cid for cid in applicable_mapped if cid in monitored]
            aligned = sum(per_control_aligned.get(cid, 0) for cid in applicable_mapped)
            finding = sum(per_control_finding.get(cid, 0) for cid in applicable_mapped)
            unknown = sum(per_control_unknown.get(cid, 0) for cid in applicable_mapped)
            req_applies = bool(requirement.get("applies", True))
            if not req_applies or (mapped and not applicable_mapped):
                req_coverage = "NOT_APPLICABLE"
            elif not applicable_mapped:
                req_coverage = "UNCOVERED"
            elif len(mon) == len(applicable_mapped):
                req_coverage = "COVERED"
            elif mon:
                req_coverage = "PARTIALLY_COVERED"
            else:
                req_coverage = "UNCOVERED"
            req_posture = "FINDING" if finding > 0 else ("ALIGNED" if aligned > 0 else "UNKNOWN")
            req_counts[req_coverage] += 1
            req_rows.append({
                "id": requirement["id"],
                "section": requirement["section"],
                "title": requirement["title"],
                "control_ids": sorted(mapped),
                "applicable": req_applies and (bool(applicable_mapped) or not mapped),
                "monitored": len(mon),
                "aligned": aligned,
                "finding": finding,
                "unknown": unknown,
                "coverage": req_coverage,
                "posture": req_posture,
            })
        unmapped_refs = sorted({
            _control_framework_refs(cid, extra_meta).get(name, "")
            for cid in all_ids
            if name in _control_framework_refs(cid, extra_meta)
            and norm_refs.get(cid, {}).get(name)
            and norm_refs[cid][name] not in req_norm_ids
        })

        by_framework[name] = {
            "controls": len(fw_control_ids),
            "monitored": len(fw_monitored),
            "aligned": sum(per_control_aligned.get(cid, 0) for cid in fw_control_ids),
            "finding": sum(per_control_finding.get(cid, 0) for cid in fw_control_ids),
            "coverage": coverage,
            "version": fw_cat.get("version"),
            "profile": fw_cat.get("profile"),
            "requirements": req_rows,
            "requirement_counts": req_counts,
            "unmapped_control_refs": unmapped_refs,
        }

    total = len(all_ids)
    view = history_view(
        history,
        current_aligned=aligned_percent,
        current_risk_weighted=risk_weighted,
    )
    return {
        "catalog_version": CATALOG_VERSION,
        "framework_catalog_version": FRAMEWORK_CATALOG_VERSION,
        "total_controls": total,
        "monitored_controls": len(monitored),
        "unmonitored_controls": total - len(monitored),
        "subjects": len(evaluated),
        "cells": cells,
        "aligned_percent": aligned_percent,
        "risk_weighted_alignment_percent": risk_weighted,
        "by_framework": by_framework,
        "by_subject": by_subject,
        "history": view["records"],
        "trend": view["trend"],
    }


def _assignment_policy_block(policy: ControlAssignmentPolicy) -> dict[str, Any]:
    """Counts-only policy provenance — safe for the shareable artifact."""
    return {
        "active": policy.is_active,
        "source": policy.source,
        "default_mode": policy.default_mode,
        "groups": policy.group_count,
        "waivers": policy.waiver_count,
    }


def build_compliance_posture(
    configuration_ui: dict[str, Any] | None,
    project_plan: dict[str, Any] | None = None,
    *,
    data_root: Any = None,
    crypto_facts_by_subject: dict[str, dict[str, Any]] | None = None,
    unified_inventory: list[dict[str, Any]] | None = None,
    history: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    payload = _as_dict(configuration_ui)
    plan = _as_dict(project_plan)
    crypto_by_subject = _as_dict(crypto_facts_by_subject)
    # CE.1 fast-follow: the merged inventory (utils/merge.py → unified.json), keyed
    # by device identity, so a user check can assert over unified.interfaces /
    # unified.routes. Omitted (render paths that pre-date the wire) → the
    # namespaces stay unresolved and any check using them is on_no_evidence.
    inventory_index = _index_unified_inventory(unified_inventory)

    # 0.7.1b: local, file-based per-device control assignment + waivers.
    # Missing file → all-applicable (byte-identical to the prior behaviour).
    # 0.7.3 (CE.1): a user-authored check pack (data/state/compliance_checks.json)
    # adds x_-prefixed checks the assignment policy can also target. Both loaders
    # are fail-closed — a malformed file stops the posture build.
    resolved_root = Path(data_root) if data_root is not None else (BASE_DIR / "data")
    check_pack = load_compliance_checks(resolved_root)
    policy = load_control_assignments(resolved_root, extra_known_ids=check_pack.check_ids())
    user_check_meta = _user_check_meta(check_pack)
    check_packs_block = [] if check_pack.source == "missing" else [_check_pack_block(check_pack)]
    now = datetime.now(timezone.utc)

    if not payload.get("available"):
        return {
            "schema_version": COMPLIANCE_SCHEMA_VERSION,
            "available": False,
            "classification": "evidence_backed_control_area",
            "disclaimer": "Not a compliance certification or complete framework assessment.",
            "rule_pack": rule_pack_summary(),
            "fleet": {
                "subjects": 0,
                "evaluated_subjects": 0,
                "unavailable_subjects": 0,
                "status_counts": _status_counts(),
                "subject_status_counts": {
                    "PASS": 0,
                    "FINDING": 0,
                    "UNKNOWN": 0,
                },
            },
            "fleet_controls": [],
            "platform_controls": [],
            "subjects": [],
            "compliance_overview": _empty_overview(history),
            "assignment_policy": _assignment_policy_block(policy),
            "check_packs": check_packs_block,
            "privacy": {
                "contains_secrets": False,
                "contains_raw_configuration": False,
                "contains_device_identity": False,
                "contains_network_identity": False,
            },
        }

    link_map = _roadmap_link_map(plan)
    devices = [_as_dict(row) for row in _as_list(payload.get("devices"))]

    cp_index = 0
    pan_index = 0
    subjects: list[dict[str, Any]] = []
    fleet_tls_verify = _as_dict(payload.get("fleet")).get("tls_verify")

    baseline_ids = frozenset(LEGACY_CONTROL_IDS)

    for source_config_index, device in enumerate(devices):
        vendor_key = str(device.get("vendor_key") or "")
        if vendor_key == "check_point":
            cp_index += 1
            subject_id = f"cp-{cp_index:03d}"
            subject_label = f"Configuration subject {subject_id}"
        elif vendor_key == "palo_alto":
            pan_index += 1
            subject_id = f"pan-{pan_index:03d}"
            subject_label = f"Configuration subject {subject_id}"
        else:
            continue

        has_current = _bool(device.get("connected")) and str(_as_dict(device.get("current_configuration")).get("status") or "") == "available"

        # In-process only: the real device name drives assignment matching and
        # never enters the payload (subjects stay positional: cp-001, pan-001).
        device_name = str(device.get("name") or "")
        applicable_enrichment = {
            str(c["control_id"]) for c in _ENRICHMENT_CONTROLS
            if vendor_key in list(c.get("applicable_vendors") or [])
        }
        applicable_user_checks = {
            check.id for check in check_pack.checks
            if check.applies_to_subject(
                vendor=vendor_key,
                platform_family=str(device.get("platform_family") or ""),
                entity_type=str(device.get("entity_type") or ""),
            )
        }
        applicable_ids = baseline_ids | applicable_enrichment | applicable_user_checks
        resolved_ids = policy.resolve(device_name, vendor_key, applicable_ids)

        controls = (
            _subject_controls(device, policy, device_name, resolved_ids, now)
            if has_current else []
        )
        subject_unified_rows = _match_unified_rows(inventory_index, device, vendor_key)
        extended_controls = (
            _subject_extended_controls(device, policy, device_name, resolved_ids, now)
            + _subject_user_checks(
                device, check_pack, policy, device_name, resolved_ids, now,
                crypto_by_subject.get(subject_id),
                subject_unified_rows,
            )
            if has_current else []
        )

        all_rows = controls + extended_controls
        scoring_rows = _scoring_rows(all_rows)
        evaluated_ids = {str(c.get("control_id") or "") for c in all_rows}
        waived_ids = sorted(
            str(c.get("control_id") or "") for c in all_rows if c.get("status") == "WAIVED"
        )

        subject_status = "UNAVAILABLE" if not has_current else "PASS"
        if has_current and any(control.get("status") == "FINDING" for control in scoring_rows):
            subject_status = "FINDING"
        elif has_current and any(control.get("status") == "UNKNOWN" for control in scoring_rows):
            subject_status = "UNKNOWN"

        subjects.append({
            "subject_id": subject_id,
            "subject_label": subject_label,
            "vendor_key": vendor_key,
            "source_config_index": source_config_index,
            "availability": "AVAILABLE" if has_current else "UNAVAILABLE",
            "availability_reason": None if has_current else "Current-state evidence was not collected for this subject.",
            "status": subject_status,
            "controls": controls,
            "extended_controls": extended_controls,
            "assignment": {
                "assigned": sorted(resolved_ids),
                "not_assigned": sorted(applicable_ids - resolved_ids),
                "evaluated": sorted(evaluated_ids),
                "waived": waived_ids,
            },
        })

    fleet_controls = _fleet_controls(payload, link_map)
    platform_controls = _platform_controls(devices, link_map, fleet_tls_verify)
    status_counts = _status_counts()

    for control in fleet_controls + platform_controls:
        status_counts[str(control.get("status") or "UNKNOWN")] += 1
    for subject in subjects:
        for control in _scoring_rows(
            _as_list(subject.get("controls")) + _as_list(subject.get("extended_controls"))
        ):
            key = str(_as_dict(control).get("status") or "UNKNOWN")
            if key in status_counts:
                status_counts[key] += 1

    evaluated_subjects = [subject for subject in subjects if subject.get("availability") == "AVAILABLE"]
    subject_status_counts = {
        "PASS": sum(1 for subject in evaluated_subjects if subject.get("status") == "PASS"),
        "FINDING": sum(1 for subject in evaluated_subjects if subject.get("status") == "FINDING"),
        "UNKNOWN": sum(1 for subject in evaluated_subjects if subject.get("status") == "UNKNOWN"),
    }

    return {
        "schema_version": COMPLIANCE_SCHEMA_VERSION,
        "available": True,
        "classification": "evidence_backed_control_area",
        "disclaimer": "Not a compliance certification or complete framework assessment.",
        "rule_pack": rule_pack_summary(),
        "fleet": {
            "subjects": len(subjects),
            "evaluated_subjects": len(evaluated_subjects),
            "unavailable_subjects": len(subjects) - len(evaluated_subjects),
            "status_counts": status_counts,
            "subject_status_counts": subject_status_counts,
        },
        "fleet_controls": fleet_controls,
        "platform_controls": platform_controls,
        "subjects": subjects,
        "compliance_overview": _compliance_overview(subjects, user_check_meta, history),
        "assignment_policy": _assignment_policy_block(policy),
        "check_packs": check_packs_block,
        "privacy": {
            "contains_secrets": False,
            "contains_raw_configuration": False,
            "contains_device_identity": False,
            "contains_network_identity": False,
        },
    }
