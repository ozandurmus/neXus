from __future__ import annotations

from typing import Any


COMPLIANCE_SCHEMA_VERSION = "0.6.1B.1.6"
STATUS_VALUES = ("PASS", "FINDING", "UNKNOWN", "NOT_APPLICABLE", "PLANNED")


VENDOR_NEUTRAL_CONTROLS: tuple[dict[str, Any], ...] = (
    {
        "control_id": "hostname_configured_non_default",
        "title": "Hostname configured and non-default",
        "control_area": "System identity baseline",
        "cis_reference": "CIS 2.1.8",
        "evidence_fields": ["current_configuration.sections.system.settings.Hostname"],
    },
    {
        "control_id": "dns_primary_secondary_configured",
        "title": "Primary and secondary DNS configured",
        "control_area": "Name resolution resilience",
        "cis_reference": "CIS 2.1.6",
        "evidence_fields": [
            "current_configuration.sections.dns.settings.Primary DNS",
            "current_configuration.sections.dns.settings.Secondary DNS",
        ],
    },
    {
        "control_id": "ntp_primary_secondary_configured",
        "title": "Primary and secondary NTP configured",
        "control_area": "Time synchronization and secure operations",
        "cis_reference": "CIS 2.3.1",
        "evidence_fields": [
            "current_configuration.sections.ntp.settings.Primary NTP Server",
            "current_configuration.sections.ntp.settings.Secondary NTP Server",
        ],
    },
    {
        "control_id": "aaa_provider_presence",
        "title": "AAA provider presence (RADIUS/TACACS/LDAP)",
        "control_area": "Authentication policy strength",
        "cis_reference": "CIS 2.5.4 / AAA server configured",
        "evidence_fields": ["current_configuration.sections.authentication.settings"],
    },
    {
        "control_id": "management_session_timeout_policy",
        "title": "Management session timeout policy",
        "control_area": "Administrative access restrictions",
        "cis_reference": "CIS 2.5.2 / PanOS Idle Timeout",
        "evidence_fields": [
            "current_configuration.sections.management.settings.inactivity-timeout",
            "current_configuration.sections.management.settings.idle-timeout",
        ],
    },
    {
        "control_id": "telnet_disabled",
        "title": "Telnet disabled",
        "control_area": "Administrative access restrictions",
        "cis_reference": "CIS 2.1.9 / PanOS Telnet Disabled",
        "evidence_fields": ["current_configuration.sections.management.settings.protocol_enablement"],
    },
    {
        "control_id": "http_management_restricted",
        "title": "HTTP management disabled or HTTPS 443-only",
        "control_area": "Administrative access restrictions",
        "cis_reference": "PanOS Permitted Protocols / CP management web hardening",
        "evidence_fields": ["current_configuration.sections.management.settings.http_https_ports"],
    },
    {
        "control_id": "snmp_v3_only",
        "title": "SNMP v3 only",
        "control_area": "Management-plane protocol hardening",
        "cis_reference": "CIS 2.2.2 / PanOS SNMP Polling v3",
        "evidence_fields": ["current_configuration.sections.snmp.settings.version"],
    },
    {
        "control_id": "update_server_identity_verified",
        "title": "Update server identity verification enabled",
        "control_area": "Supply-chain and update-channel trust",
        "cis_reference": "PanOS Verify Update Server Identity",
        "evidence_fields": ["current_configuration.sections.system.settings.Update Server"],
    },
    {
        "control_id": "management_audit_logging",
        "title": "Management audit logging configured",
        "control_area": "Administrative activity traceability",
        "cis_reference": "CIS 2.6.1 / 2.6.2",
        "evidence_fields": ["current_configuration.sections.logging.settings.audit"],
    },
)


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


def _evaluate_timezone_control(device: dict[str, Any], control: dict[str, Any]) -> dict[str, Any]:
    settings = _section_settings(device, "system")
    if not settings:
        return _implemented_control(control, "UNKNOWN", "System section is unavailable for timezone evaluation.", coverage="partial")
    if _setting_with_value(settings, "timezone"):
        return _implemented_control(control, "PASS", "Timezone setting is present with a non-empty value in normalized current-state evidence.")
    return _implemented_control(control, "FINDING", "System section is present but timezone setting was not observed.")


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


def _subject_controls(device: dict[str, Any]) -> list[dict[str, Any]]:
    has_current = _bool(device.get("connected")) and str(_as_dict(device.get("current_configuration")).get("status") or "") == "available"
    if not has_current:
        return []
    return [_evaluate_vendor_neutral_control(device, control) for control in VENDOR_NEUTRAL_CONTROLS]


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


def build_compliance_posture(
    configuration_ui: dict[str, Any] | None,
    project_plan: dict[str, Any] | None = None,
) -> dict[str, Any]:
    payload = _as_dict(configuration_ui)
    plan = _as_dict(project_plan)

    if not payload.get("available"):
        return {
            "schema_version": COMPLIANCE_SCHEMA_VERSION,
            "available": False,
            "classification": "evidence_backed_control_area",
            "disclaimer": "Not a compliance certification or complete framework assessment.",
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
        controls = _subject_controls(device) if has_current else []
        subject_status = "UNAVAILABLE" if not has_current else "PASS"
        if has_current and any(control.get("status") == "FINDING" for control in controls):
            subject_status = "FINDING"
        elif has_current and any(control.get("status") == "UNKNOWN" for control in controls):
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
        })

    fleet_controls = _fleet_controls(payload, link_map)
    platform_controls = _platform_controls(devices, link_map, fleet_tls_verify)
    status_counts = _status_counts()

    for control in fleet_controls + platform_controls:
        status_counts[str(control.get("status") or "UNKNOWN")] += 1
    for subject in subjects:
        for control in _as_list(subject.get("controls")):
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
        "privacy": {
            "contains_secrets": False,
            "contains_raw_configuration": False,
            "contains_device_identity": False,
            "contains_network_identity": False,
        },
    }
