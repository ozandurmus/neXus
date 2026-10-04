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


# --- 0.7.2 enrichment evaluators -------------------------------------------
# Read the 0.7.2 projection-extension sections `password_policy`, `banner` and
# `services` only. Same discipline: section absent → UNKNOWN; section present
# but the expected signal is not observed → FINDING; never an inferred PASS.

def _first_int(text: str) -> int | None:
    digits = ""
    for ch in str(text or ""):
        if ch.isdigit():
            digits += ch
        elif digits:
            break
    return int(digits) if digits else None


_COMPLEXITY_TOKENS = (
    "complexity", "uppercase", "lowercase", "numeric", "digit", "special",
    "character class", "character-class", "palindrome", "class",
)
_LOCKOUT_TOKENS = (
    "lockout", "lock-out", "lock out", "deny-on-fail", "deny on fail",
    "failed-attempts", "failed attempts", "max-fail", "max fail",
    "max-failed", "attempts", "deny-on-nonuse", "deny on nonuse",
)
_HISTORY_TOKENS = ("history", "differs", "repeated", "reuse", "previous")


def _password_min_length(device: dict[str, Any]) -> _Result:
    settings = _section_settings(device, "password_policy")
    if not settings:
        return ("UNKNOWN", "No password-policy section in normalized current-state evidence.", "not_collected")
    value = (
        _value_for_tokens(settings, "min", "length")
        or _value_for_tokens(settings, "minimum", "length")
        or _value_for_tokens(settings, "min-password-length")
        or _value_for_tokens(settings, "min password length")
    )
    length = _first_int(value or "")
    if length is None:
        return ("FINDING", "Password-policy section is present but no minimum-length value was observed.", "complete")
    if length >= 12:
        return ("PASS", f"Administrative password minimum length is {length} (>= 12).", "complete")
    if length >= 8:
        return ("PASS", f"Administrative password minimum length is {length} (meets the >= 8 baseline; >= 12 recommended).", "complete")
    return ("FINDING", f"Administrative password minimum length is {length}, below the >= 8 baseline.", "complete")


def _password_complexity_enabled(device: dict[str, Any]) -> _Result:
    settings = _section_settings(device, "password_policy")
    if not settings:
        return ("UNKNOWN", "No password-policy section in normalized current-state evidence.", "not_collected")
    pairs = [
        (name, value) for name, value in _pairs(settings)
        if any(tok in f"{name} {value}" for tok in _COMPLEXITY_TOKENS)
    ]
    if not pairs:
        return ("FINDING", "Password-policy section is present but no complexity / character-class requirement was observed.", "complete")
    blob = " ".join(f"{name} {value}" for name, value in pairs)
    if any(tok in blob for tok in _DISABLED) and not any(_has_digit(value) for _, value in pairs):
        return ("FINDING", "A password complexity requirement is present but appears disabled.", "complete")
    return ("PASS", "A password complexity / character-class requirement is present in normalized evidence.", "complete")


def _password_history_depth(device: dict[str, Any]) -> _Result:
    settings = _section_settings(device, "password_policy")
    if not settings:
        return ("UNKNOWN", "No password-policy section in normalized current-state evidence.", "not_collected")
    pairs = [
        (name, value) for name, value in _pairs(settings)
        if any(tok in name for tok in _HISTORY_TOKENS)
    ]
    if not pairs:
        return ("FINDING", "Password-policy section is present but no history / reuse-prevention depth was observed.", "complete")
    depth = _first_int(" ".join(value for _, value in pairs))
    if depth is None:
        blob = " ".join(f"{name} {value}" for name, value in pairs)
        if any(tok in blob for tok in _ENABLED):
            return ("PASS", "A password history / reuse-prevention setting is present and enabled.", "complete")
        return ("FINDING", "A password history setting is present but its depth could not be concluded.", "complete")
    if depth >= 1:
        return ("PASS", f"Password history / reuse-prevention depth is {depth}.", "complete")
    return ("FINDING", "Password history / reuse-prevention depth is 0.", "complete")


def _password_lockout_policy(device: dict[str, Any]) -> _Result:
    settings = _section_settings(device, "password_policy")
    if not settings:
        return ("UNKNOWN", "No password-policy section in normalized current-state evidence.", "not_collected")
    pairs = [
        (name, value) for name, value in _pairs(settings)
        if any(tok in f"{name} {value}" for tok in _LOCKOUT_TOKENS)
    ]
    if not pairs:
        return ("FINDING", "Password-policy section is present but no failed-attempt lockout knob was observed.", "complete")
    blob = " ".join(f"{name} {value}" for name, value in pairs)
    if any(tok in blob for tok in _DISABLED) and not any(_has_digit(value) for _, value in pairs):
        return ("FINDING", "A failed-attempt lockout knob is present but appears disabled.", "complete")
    return ("PASS", "A failed-attempt lockout policy is present in normalized password-policy evidence.", "complete")


_BANNER_ABSENT = ("absent", "none", "off", "no", "0", "disabled", "false", "unset")


def _login_banner_text_present(device: dict[str, Any]) -> _Result:
    settings = _section_settings(device, "banner")
    if not settings:
        return ("UNKNOWN", "No banner section in normalized current-state evidence.", "not_collected")
    for name, value in _pairs(settings):
        if any(tok in name for tok in ("banner", "motd", "message", "caption")):
            if value and not any(value == absent or value.startswith(absent) for absent in _BANNER_ABSENT):
                return ("PASS", "An administrative login banner / message-of-the-day is projected as present.", "complete")
    return ("FINDING", "Banner section is present but no login banner / MOTD is projected as present.", "complete")


_RISKY_SERVICES = (
    "finger", "ident", "echo", "daytime", "chargen", "discard",
    "rlogin", "rsh", "tftp", "telnet",
)
_ON_TOKENS = ("enable", "enabled", "on", "yes", "true", "1")


def _unused_services_disabled(device: dict[str, Any]) -> _Result:
    settings = _section_settings(device, "services")
    if not settings:
        return ("UNKNOWN", "No services section in normalized current-state evidence.", "not_collected")
    pairs = _pairs(settings)
    for name, value in pairs:
        blob = f"{name} {value}"
        if "disable" in name:
            continue
        for svc in _RISKY_SERVICES:
            if svc in name and any(tok in blob for tok in _ON_TOKENS):
                return ("FINDING", f"A commonly-unused management-plane service ({svc}) appears enabled in normalized evidence.", "complete")
    disabled_hits = [
        (name, value) for name, value in pairs
        if "disable" in name and any(tok in f"{name} {value}" for tok in _ON_TOKENS)
    ]
    if disabled_hits:
        return ("PASS", f"{len(disabled_hits)} management-plane service(s) are explicitly disabled and no risky service is enabled.", "complete")
    if any("disable" in name for name, _ in pairs):
        return ("UNKNOWN", "Services section is present but service-disable state could not be concluded.", "partial")
    return ("FINDING", "Services section is present but no unused-service hardening was observed.", "complete")


ENRICHMENT_EVALUATORS: dict[str, Callable[[dict[str, Any]], _Result]] = {
    "timezone_configured": _timezone_configured,
    "login_banner_present": _login_banner_present,
    "remote_syslog_configured": _remote_syslog_configured,
    "ntp_authentication_enabled": _ntp_authentication_enabled,
    "ssh_management_v2_only": _ssh_management_v2_only,
    "snmp_no_default_community": _snmp_no_default_community,
    "admin_lockout_policy": _admin_lockout_policy,
    "dns_domain_configured": _dns_domain_configured,
    "password_min_length": _password_min_length,
    "password_complexity_enabled": _password_complexity_enabled,
    "password_history_depth": _password_history_depth,
    "password_lockout_policy": _password_lockout_policy,
    "login_banner_text_present": _login_banner_text_present,
    "unused_services_disabled": _unused_services_disabled,
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
