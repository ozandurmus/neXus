"""0.7.1b — deterministic evaluators for the enrichment compliance controls.

These read the *already projected* current-configuration sections only (no new
collector, no projection change). Each returns ``(status, summary, coverage)``;
``utils.compliance_posture`` wraps that with the catalog metadata and the
standard control shape. Missing evidence never becomes an inferred ``PASS`` —
it resolves to ``UNKNOWN`` (section absent) or ``FINDING`` (section present but
the expected signal is not observed), matching the 0.6.6B baseline discipline.
"""
from __future__ import annotations

from typing import Any, Callable

_Result = tuple[str, str, str]


def _as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _section_settings(device: dict[str, Any], section_id: str) -> list[dict[str, Any]]:
    current = _as_dict(device.get("current_configuration"))
    for section in _as_list(current.get("sections")):
        row = _as_dict(section)
        if str(row.get("id") or "") == section_id:
            return [_as_dict(item) for item in _as_list(row.get("settings"))]
    return []


def _section_present(device: dict[str, Any], *section_ids: str) -> bool:
    current = _as_dict(device.get("current_configuration"))
    present = {
        str(_as_dict(section).get("id") or "")
        for section in _as_list(current.get("sections"))
        if _as_list(_as_dict(section).get("settings"))
    }
    return any(section_id in present for section_id in section_ids)


def _pairs(settings: list[dict[str, Any]]) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for row in settings:
        out.append((
            str(row.get("setting") or "").strip().lower(),
            str(row.get("value") or "").strip().lower(),
        ))
    return out


def _any_token(settings: list[dict[str, Any]], *tokens: str) -> bool:
    wanted = tuple(t.lower() for t in tokens if t)
    for name, value in _pairs(settings):
        blob = f"{name} {value}"
        if any(tok in blob for tok in wanted):
            return True
    return False


def _value_for_tokens(settings: list[dict[str, Any]], *tokens: str) -> str | None:
    wanted = tuple(t.lower() for t in tokens if t)
    for name, value in _pairs(settings):
        if all(tok in name for tok in wanted) and value:
            return value
    return None


_ENABLED = ("enable", "enabled", "on", "true", "yes", "require", "required")
_DISABLED = ("disable", "disabled", "off", "false", "no", "none")


def _has_digit(text: str) -> bool:
    return any(ch.isdigit() for ch in text or "")


# --- individual evaluators ---------------------------------------------------

def _timezone_configured(device: dict[str, Any]) -> _Result:
    settings = _section_settings(device, "system")
    if not settings:
        return ("UNKNOWN", "System section is unavailable for timezone evaluation.", "not_collected")
    value = _value_for_tokens(settings, "timezone") or _value_for_tokens(settings, "time", "zone")
    if value:
        return ("PASS", "A system timezone value is present in normalized current-state evidence.", "complete")
    return ("FINDING", "System section is present but no timezone value was observed.", "complete")


def _login_banner_present(device: dict[str, Any]) -> _Result:
    settings = _section_settings(device, "system") + _section_settings(device, "management")
    if not _section_present(device, "system", "management"):
        return ("UNKNOWN", "Neither system nor management section is available for banner evaluation.", "not_collected")
    if _any_token(settings, "banner", "motd", "login message"):
        return ("PASS", "An administrative login banner / message-of-the-day setting is present.", "complete")
    return ("FINDING", "Management/system sections are present but no login banner setting was observed.", "complete")


def _remote_syslog_configured(device: dict[str, Any]) -> _Result:
    settings = _section_settings(device, "logging")
    if not settings:
        return ("UNKNOWN", "Logging section is unavailable for remote-syslog evaluation.", "not_collected")
    if _any_token(settings, "syslog") or _any_token(settings, "remote") or _any_token(settings, "forward") \
            or _any_token(settings, "log server") or _any_token(settings, "log-server") or _any_token(settings, "log host"):
        return ("PASS", "Remote syslog / log-forwarding evidence is present in normalized logging settings.", "complete")
    return ("FINDING", "Logging section is present but no remote syslog / forwarding target was observed.", "complete")


def _ntp_authentication_enabled(device: dict[str, Any]) -> _Result:
    settings = _section_settings(device, "ntp")
    if not settings:
        return ("UNKNOWN", "NTP section is unavailable for authentication evaluation.", "not_collected")
    auth_pairs = [
        (name, value) for name, value in _pairs(settings)
        if any(tok in f"{name} {value}" for tok in ("auth", "key", "md5", "sha", "autokey"))
    ]
    if not auth_pairs:
        return ("UNKNOWN", "NTP section is present but carries no authentication-related setting to evaluate.", "partial")
    blob = " ".join(f"{name} {value}" for name, value in auth_pairs)
    if any(tok in blob for tok in _DISABLED):
        return ("FINDING", "NTP authentication appears disabled in normalized settings.", "complete")
    if any(tok in blob for tok in _ENABLED) or any(_has_digit(value) for _, value in auth_pairs):
        return ("PASS", "NTP authentication evidence (key/auth) is present in normalized settings.", "complete")
    return ("UNKNOWN", "NTP authentication setting is present but its state could not be concluded.", "partial")


def _ssh_management_v2_only(device: dict[str, Any]) -> _Result:
    settings = _section_settings(device, "management")
    if not settings:
        return ("UNKNOWN", "Management section is unavailable for SSH protocol-version evaluation.", "not_collected")
    ssh_blob = " ".join(
        f"{name} {value}" for name, value in _pairs(settings) if "ssh" in name or "ssh" in value
    )
    if not ssh_blob:
        return ("UNKNOWN", "No SSH management setting was observed in normalized evidence.", "partial")
    if "v1" in ssh_blob or "version 1" in ssh_blob or "protocol 1" in ssh_blob:
        return ("FINDING", "SSH management evidence indicates protocol v1 is accepted.", "complete")
    if "v2" in ssh_blob or "version 2" in ssh_blob or "protocol 2" in ssh_blob:
        return ("PASS", "SSH management evidence indicates protocol v2 only.", "complete")
    return ("UNKNOWN", "SSH management setting is present but the accepted protocol version is not explicit.", "partial")


_DEFAULT_COMMUNITIES = ("public", "private", "community")


def _snmp_no_default_community(device: dict[str, Any]) -> _Result:
    settings = _section_settings(device, "snmp")
    if not settings:
        return ("UNKNOWN", "SNMP section is unavailable for default-community evaluation.", "not_collected")
    community_pairs = [
        (name, value) for name, value in _pairs(settings)
        if "community" in name or "community" in value
    ]
    if not community_pairs:
        return ("UNKNOWN", "SNMP section is present but carries no community-string setting to evaluate.", "partial")
    for _name, value in community_pairs:
        if value in {"public", "private"} or value.endswith(" public") or value.endswith(" private"):
            return ("FINDING", "SNMP configuration evidence contains a default community string (public/private).", "complete")
    return ("PASS", "SNMP community setting is present and is not a default string in normalized evidence.", "complete")


def _admin_lockout_policy(device: dict[str, Any]) -> _Result:
    settings = _section_settings(device, "authentication") + _section_settings(device, "management")
    if not _section_present(device, "authentication", "management"):
        return ("UNKNOWN", "Neither authentication nor management section is available for lockout evaluation.", "not_collected")
    for name, value in _pairs(settings):
        blob = f"{name} {value}"
        if any(tok in blob for tok in ("lockout", "lock-out", "max-attempts", "max attempts",
                                       "failed attempts", "failed-attempts", "login attempts", "attempts")):
            if any(tok in blob for tok in _DISABLED) and not _has_digit(value):
                return ("FINDING", "An administrative lockout setting is present but appears disabled.", "complete")
            return ("PASS", "An administrative account-lockout / failed-attempt setting is present in normalized evidence.", "complete")
    return ("FINDING", "Authentication/management sections are present but no account-lockout policy was observed.", "complete")


def _dns_domain_configured(device: dict[str, Any]) -> _Result:
    settings = _section_settings(device, "dns") + _section_settings(device, "system")
    if not _section_present(device, "dns", "system"):
        return ("UNKNOWN", "Neither DNS nor system section is available for search-domain evaluation.", "not_collected")
    for name, value in _pairs(settings):
        if "domain" in name and "server" not in name and value:
            return ("PASS", "A DNS search domain value is present in normalized current-state evidence.", "complete")
    return ("FINDING", "DNS/system sections are present but no search-domain value was observed.", "complete")


ENRICHMENT_EVALUATORS: dict[str, Callable[[dict[str, Any]], _Result]] = {
    "timezone_configured": _timezone_configured,
    "login_banner_present": _login_banner_present,
    "remote_syslog_configured": _remote_syslog_configured,
    "ntp_authentication_enabled": _ntp_authentication_enabled,
    "ssh_management_v2_only": _ssh_management_v2_only,
    "snmp_no_default_community": _snmp_no_default_community,
    "admin_lockout_policy": _admin_lockout_policy,
    "dns_domain_configured": _dns_domain_configured,
}


def evaluate_enrichment_control(device: dict[str, Any], control_id: str) -> _Result:
    """Return ``(status, summary, coverage)`` for one enrichment control.

    An unmapped id is treated as a not-yet-implemented evaluator (``PLANNED``),
    never an inferred pass.
    """
    fn = ENRICHMENT_EVALUATORS.get(control_id)
    if fn is None:
        return (
            "PLANNED",
            "This enrichment control is catalogued but its deterministic evaluator is not implemented in this build.",
            "not_collected",
        )
    return fn(device)
