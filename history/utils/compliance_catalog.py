"""0.7.1a — Compliance control catalog.

The control library as a versioned declarative model: every control carries a
rationale, a severity, vendor applicability, an evidence contract and explicit
per-framework membership (CIS / PCI-DSS / BDDK) with the specific reference.
The engine and the UI both render from this; adding a control is one catalog
entry plus its evaluator.

`framework` membership is many-to-one both ways — one framework requirement may
need several controls; one control may touch several requirements. `applies`
records membership, not equivalence. No certification / attestation claim.
"""
from __future__ import annotations

from typing import Any

CATALOG_VERSION = "0.7.2"
SEVERITY_VALUES = ("informational", "low", "medium", "high", "critical")
_SEVERITY_WEIGHT = {"informational": 1, "low": 2, "medium": 3, "high": 4, "critical": 5}

# The ten controls frozen by the 0.6.6B rule pack (and its tests). They keep
# their own evaluators and pack routing; `catalog_baseline_controls()` returns
# exactly these, in this order, in the 5-key shape the pack consumes. Everything
# added in 0.7.1b is an *enrichment* control — a separate subject-control list,
# never routed through `DEFAULT_RULE_PACK`.
LEGACY_CONTROL_IDS: tuple[str, ...] = (
    "hostname_configured_non_default",
    "dns_primary_secondary_configured",
    "ntp_primary_secondary_configured",
    "aaa_provider_presence",
    "management_session_timeout_policy",
    "telnet_disabled",
    "http_management_restricted",
    "snmp_v3_only",
    "update_server_identity_verified",
    "management_audit_logging",
)


def _fw(framework: str, reference: str, applies: bool = True, **extra: Any) -> dict[str, Any]:
    return {"framework": framework, "reference": reference, "applies": applies, **extra}


# Order preserved: the ten 0.6.1B.1.6 controls first (verbatim ids / areas /
# evidence_fields / evaluators), then 0.7.1a additions.
CONTROL_CATALOG: tuple[dict[str, Any], ...] = (
    {
        "id": "hostname_configured_non_default", "cis_reference": 'CIS 2.1.8', "title": "Hostname configured and non-default",
        "rationale": "A default or empty hostname makes devices ambiguous in logs, audit trails and incident response.",
        "control_area": "System identity baseline", "severity": "low",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.system.settings.Hostname"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.1.8", profile="CIS Firewall Benchmark"), _fw("PCI-DSS", "2.2.1", version="4.0"),
                       _fw("BDDK", "Sistem Sıkılaştırma - Kimlik")],
        "lifecycle": "active", "introduced": "0.6.1B.1.6", "evaluator": "hostname_configured_non_default",
    },
    {
        "id": "dns_primary_secondary_configured", "cis_reference": 'CIS 2.1.6', "title": "Primary and secondary DNS configured",
        "rationale": "A single DNS server is a single point of failure for name resolution used by updates, logging and auth.",
        "control_area": "Name resolution resilience", "severity": "low",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.dns.settings.Primary DNS",
                                                          "current_configuration.sections.dns.settings.Secondary DNS"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.1.6"), _fw("PCI-DSS", "2.2.1", version="4.0", applies=False),
                       _fw("BDDK", "Süreklilik - Ad Çözümleme")],
        "lifecycle": "active", "introduced": "0.6.1B.1.6", "evaluator": "dns_primary_secondary_configured",
    },
    {
        "id": "ntp_primary_secondary_configured", "cis_reference": 'CIS 2.3.1', "title": "Primary and secondary NTP configured",
        "rationale": "Accurate, redundant time is required for correlatable logs, certificate validation and audit integrity.",
        "control_area": "Time synchronization and secure operations", "severity": "medium",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.ntp.settings.Primary NTP Server",
                                                          "current_configuration.sections.ntp.settings.Secondary NTP Server"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.3.1"), _fw("PCI-DSS", "10.6.1", version="4.0"),
                       _fw("BDDK", "Kayıt Yönetimi - Zaman Damgası")],
        "lifecycle": "active", "introduced": "0.6.1B.1.6", "evaluator": "ntp_primary_secondary_configured",
    },
    {
        "id": "aaa_provider_presence", "cis_reference": 'CIS 2.5.4 / AAA server configured', "title": "AAA provider presence (RADIUS/TACACS/LDAP)",
        "rationale": "Centralised authentication enables consistent policy, rapid deprovisioning and per-admin accountability.",
        "control_area": "Authentication policy strength", "severity": "high",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.authentication.settings"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.5.4"), _fw("PCI-DSS", "8.3.1", version="4.0"),
                       _fw("BDDK", "Erişim Yönetimi - Merkezi Kimlik Doğrulama")],
        "lifecycle": "active", "introduced": "0.6.1B.1.6", "evaluator": "aaa_provider_presence",
    },
    {
        "id": "management_session_timeout_policy", "cis_reference": 'CIS 2.5.2 / PanOS Idle Timeout', "title": "Management session timeout policy",
        "rationale": "Idle administrative sessions left open are a hijack and unattended-console risk.",
        "control_area": "Administrative access restrictions", "severity": "medium",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.management.settings.inactivity-timeout",
                                                          "current_configuration.sections.management.settings.idle-timeout"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.5.2"), _fw("PCI-DSS", "8.2.8", version="4.0"),
                       _fw("BDDK", "Erişim Yönetimi - Oturum Zaman Aşımı")],
        "lifecycle": "active", "introduced": "0.6.1B.1.6", "evaluator": "management_session_timeout_policy",
    },
    {
        "id": "telnet_disabled", "cis_reference": 'CIS 2.1.9 / PanOS Telnet Disabled', "title": "Telnet disabled",
        "rationale": "Telnet exposes administrative credentials and session content in cleartext.",
        "control_area": "Administrative access restrictions", "severity": "high",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.management.settings.protocol_enablement"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.1.9"), _fw("PCI-DSS", "2.2.5", version="4.0"),
                       _fw("BDDK", "Sistem Sıkılaştırma - Güvensiz Protokoller")],
        "lifecycle": "active", "introduced": "0.6.1B.1.6", "evaluator": "telnet_disabled",
    },
    {
        "id": "http_management_restricted", "cis_reference": 'PanOS Permitted Protocols / CP management web hardening', "title": "HTTP management disabled or HTTPS 443-only",
        "rationale": "Cleartext HTTP management exposes credentials and configuration to interception.",
        "control_area": "Administrative access restrictions", "severity": "high",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.management.settings.http_https_ports"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.1.x", profile="CP web hardening"), _fw("PCI-DSS", "2.2.7", version="4.0"),
                       _fw("BDDK", "Sistem Sıkılaştırma - Yönetim Arayüzü")],
        "lifecycle": "active", "introduced": "0.6.1B.1.6", "evaluator": "http_management_restricted",
    },
    {
        "id": "snmp_v3_only", "cis_reference": 'CIS 2.2.2 / PanOS SNMP Polling v3', "title": "SNMP v3 only",
        "rationale": "SNMP v1/v2c community strings are cleartext and trivially replayed; v3 provides auth and privacy.",
        "control_area": "Management-plane protocol hardening", "severity": "medium",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.snmp.settings.version"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.2.2"), _fw("PCI-DSS", "2.2.5", version="4.0"),
                       _fw("BDDK", "Sistem Sıkılaştırma - İzleme Protokolleri")],
        "lifecycle": "active", "introduced": "0.6.1B.1.6", "evaluator": "snmp_v3_only",
    },
    {
        "id": "update_server_identity_verified", "cis_reference": 'PanOS Verify Update Server Identity', "title": "Update server identity verification enabled",
        "rationale": "Unverified update channels allow a man-in-the-middle to deliver tampered content or signatures.",
        "control_area": "Supply-chain and update-channel trust", "severity": "high",
        "vendors": ["palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.system.settings.Update Server"], "basis": "configured"},
        "frameworks": [_fw("CIS", "PanOS Verify Update Server Identity"), _fw("PCI-DSS", "6.3.3", version="4.0"),
                       _fw("BDDK", "Tedarik Zinciri - Güncelleme Kanalı", applies=False)],
        "lifecycle": "active", "introduced": "0.6.1B.1.6", "evaluator": "update_server_identity_verified",
    },
    {
        "id": "management_audit_logging", "cis_reference": 'CIS 2.6.1 / 2.6.2', "title": "Management audit logging configured",
        "rationale": "Without administrative activity logging, configuration changes and access cannot be reconstructed.",
        "control_area": "Administrative activity traceability", "severity": "high",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.logging.settings.audit"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.6.1"), _fw("PCI-DSS", "10.2.1", version="4.0"),
                       _fw("BDDK", "Kayıt Yönetimi - Yönetici İşlemleri")],
        "lifecycle": "active", "introduced": "0.6.1B.1.6", "evaluator": "management_audit_logging",
    },

    # --- 0.7.1b enrichment controls -------------------------------------------
    # Evaluated from the *already projected* current-configuration sections
    # (no new collector, no projection change). A control whose evidence
    # section is genuinely absent resolves to UNKNOWN / PLANNED, never an
    # inferred PASS. These are a separate subject-control list; they are not
    # routed through the frozen 0.6.6B rule pack. The password_policy / banner /
    # services projection sections and their controls are added in 0.7.2 below.
    {
        "id": "timezone_configured", "cis_reference": 'CIS 2.3.2', "title": "System timezone explicitly configured",
        "rationale": "An unset or drifting timezone makes cross-device log correlation and audit timelines unreliable.",
        "control_area": "Time synchronization and secure operations", "severity": "low",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.system.settings.Timezone"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.3.2"), _fw("PCI-DSS", "10.6.1", version="4.0"),
                       _fw("BDDK", "Kayıt Yönetimi - Zaman Damgası")],
        "lifecycle": "active", "introduced": "0.7.1b", "evaluator": "timezone_configured",
    },
    {
        "id": "login_banner_present", "cis_reference": 'CIS 2.1.1 / PanOS Login Banner', "title": "Administrative login banner present",
        "rationale": "A legal warning banner is a common regulatory prerequisite for prosecuting unauthorised access.",
        "control_area": "Administrative access restrictions", "severity": "low",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.system.settings.banner",
                                                          "current_configuration.sections.management.settings.banner"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.1.1"), _fw("PCI-DSS", "not applicable", version="4.0", applies=False),
                       _fw("BDDK", "Erişim Yönetimi - Yasal Uyarı")],
        "lifecycle": "active", "introduced": "0.7.1b", "evaluator": "login_banner_present",
    },
    {
        "id": "remote_syslog_configured", "cis_reference": 'CIS 2.6.3 / PanOS Syslog Forwarding', "title": "Remote syslog / log forwarding configured",
        "rationale": "Logs kept only on the device are lost on compromise or failure; off-box forwarding preserves the audit trail.",
        "control_area": "Administrative activity traceability", "severity": "high",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.logging.settings"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.6.3"), _fw("PCI-DSS", "10.5.3", version="4.0"),
                       _fw("BDDK", "Kayıt Yönetimi - Merkezi Loglama")],
        "lifecycle": "active", "introduced": "0.7.1b", "evaluator": "remote_syslog_configured",
    },
    {
        "id": "ntp_authentication_enabled", "cis_reference": 'CIS 2.3.1.1', "title": "NTP authentication enabled",
        "rationale": "Unauthenticated NTP lets an on-path attacker move device time and undermine certificate and log validity.",
        "control_area": "Time synchronization and secure operations", "severity": "medium",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.ntp.settings"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.3.1.1"), _fw("PCI-DSS", "10.6.2", version="4.0"),
                       _fw("BDDK", "Kayıt Yönetimi - Zaman Damgası")],
        "lifecycle": "active", "introduced": "0.7.1b", "evaluator": "ntp_authentication_enabled",
    },
    {
        "id": "ssh_management_v2_only", "cis_reference": 'CIS 2.1.10 / PanOS SSH v2', "title": "SSH management restricted to protocol v2",
        "rationale": "SSH protocol v1 has known cryptographic weaknesses and must not be accepted for administration.",
        "control_area": "Management-plane protocol hardening", "severity": "high",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.management.settings"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.1.10"), _fw("PCI-DSS", "2.2.5", version="4.0"),
                       _fw("BDDK", "Sistem Sıkılaştırma - Güvensiz Protokoller")],
        "lifecycle": "active", "introduced": "0.7.1b", "evaluator": "ssh_management_v2_only",
    },
    {
        "id": "snmp_no_default_community", "cis_reference": 'CIS 2.2.1', "title": "No default SNMP community string",
        "rationale": "Default community strings such as 'public' / 'private' are universally known and allow trivial read access.",
        "control_area": "Management-plane protocol hardening", "severity": "high",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.snmp.settings"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.2.1"), _fw("PCI-DSS", "2.2.2", version="4.0"),
                       _fw("BDDK", "Sistem Sıkılaştırma - Varsayılan Kimlik Bilgileri")],
        "lifecycle": "active", "introduced": "0.7.1b", "evaluator": "snmp_no_default_community",
    },
    {
        "id": "admin_lockout_policy", "cis_reference": 'CIS 2.4.2 / PanOS Failed Attempts', "title": "Administrative account lockout policy configured",
        "rationale": "Without a failed-attempt lockout, administrative logins are exposed to unbounded password guessing.",
        "control_area": "Authentication policy strength", "severity": "medium",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.authentication.settings",
                                                          "current_configuration.sections.management.settings"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.4.2"), _fw("PCI-DSS", "8.3.4", version="4.0"),
                       _fw("BDDK", "Erişim Yönetimi - Hesap Kilitleme")],
        "lifecycle": "active", "introduced": "0.7.1b", "evaluator": "admin_lockout_policy",
    },
    {
        "id": "dns_domain_configured", "cis_reference": 'CIS 2.1.7', "title": "DNS search domain configured",
        "rationale": "A configured search domain is a baseline hygiene signal that name resolution was deliberately set up.",
        "control_area": "Name resolution resilience", "severity": "informational",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.dns.settings",
                                                          "current_configuration.sections.system.settings"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.1.7"), _fw("PCI-DSS", "not applicable", version="4.0", applies=False),
                       _fw("BDDK", "Süreklilik - Ad Çözümleme", applies=False)],
        "lifecycle": "active", "introduced": "0.7.1b", "evaluator": "dns_domain_configured",
    },

    # --- 0.7.2 enrichment controls ------------------------------------------
    # Evaluated from the 0.7.2 projection extension sections `password_policy`,
    # `banner` and `services` (new projections over already-stored config — no
    # new collector). Section genuinely absent → UNKNOWN; present-but-weak →
    # FINDING; never an inferred PASS. Separate subject-control list; not routed
    # through the frozen 0.6.6B rule pack.
    {
        "id": "password_min_length", "cis_reference": 'CIS 2.4.1 / PanOS Password Min Length', "title": "Administrative password minimum length enforced",
        "rationale": "A short minimum length lets weak administrative passwords survive policy, undermining every other access control.",
        "control_area": "Authentication policy strength", "severity": "high",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.password_policy.settings"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.4.1"), _fw("PCI-DSS", "8.3.6", version="4.0"),
                       _fw("BDDK", "Erişim Yönetimi - Parola Uzunluğu")],
        "lifecycle": "active", "introduced": "0.7.2", "evaluator": "password_min_length",
    },
    {
        "id": "password_complexity_enabled", "cis_reference": 'CIS 2.4.3 / PanOS Password Complexity', "title": "Administrative password complexity requirement enabled",
        "rationale": "Without a character-class requirement, minimum length alone still permits predictable, easily guessed passwords.",
        "control_area": "Authentication policy strength", "severity": "medium",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.password_policy.settings"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.4.3"), _fw("PCI-DSS", "8.3.6", version="4.0"),
                       _fw("BDDK", "Erişim Yönetimi - Parola Karmaşıklığı")],
        "lifecycle": "active", "introduced": "0.7.2", "evaluator": "password_complexity_enabled",
    },
    {
        "id": "password_history_depth", "cis_reference": 'CIS 2.4.4 / PanOS Password History', "title": "Administrative password history / reuse prevention configured",
        "rationale": "Allowing immediate password reuse defeats forced rotation and lets a compromised credential be reinstated.",
        "control_area": "Authentication policy strength", "severity": "low",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.password_policy.settings"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.4.4"), _fw("PCI-DSS", "8.3.7", version="4.0"),
                       _fw("BDDK", "Erişim Yönetimi - Parola Geçmişi")],
        "lifecycle": "active", "introduced": "0.7.2", "evaluator": "password_history_depth",
    },
    {
        "id": "password_lockout_policy", "cis_reference": 'CIS 2.4.2 / PanOS Failed Attempts', "title": "Administrative password failed-attempt lockout configured",
        "rationale": "A password policy without a failed-attempt lockout leaves administrative logins open to unbounded online guessing.",
        "control_area": "Authentication policy strength", "severity": "high",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.password_policy.settings"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.4.2"), _fw("PCI-DSS", "8.3.4", version="4.0"),
                       _fw("BDDK", "Erişim Yönetimi - Hesap Kilitleme")],
        "lifecycle": "active", "introduced": "0.7.2", "evaluator": "password_lockout_policy",
    },
    {
        "id": "login_banner_text_present", "cis_reference": 'CIS 2.1.1 / PanOS Login Banner', "title": "Administrative login banner text projected as present",
        "rationale": "A legal warning banner is a common regulatory prerequisite for prosecuting unauthorised administrative access.",
        "control_area": "Administrative access restrictions", "severity": "low",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.banner.settings"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.1.1"), _fw("PCI-DSS", "not applicable", version="4.0", applies=False),
                       _fw("BDDK", "Erişim Yönetimi - Yasal Uyarı")],
        "lifecycle": "active", "introduced": "0.7.2", "evaluator": "login_banner_text_present",
    },
    {
        "id": "unused_services_disabled", "cis_reference": 'CIS 2.1.2 / PanOS Disable Unused Mgmt Services', "title": "Unused management-plane services disabled",
        "rationale": "Legacy or unused inbound services (finger, ident, echo, telnet, HTTP) widen the management-plane attack surface for no operational gain.",
        "control_area": "Management-plane protocol hardening", "severity": "medium",
        "vendors": ["check_point", "palo_alto"],
        "evidence": {"plane": "direct_actual", "fields": ["current_configuration.sections.services.settings"], "basis": "configured"},
        "frameworks": [_fw("CIS", "2.1.2"), _fw("PCI-DSS", "2.2.4", version="4.0"),
                       _fw("BDDK", "Sistem Sıkılaştırma - Gereksiz Servisler")],
        "lifecycle": "active", "introduced": "0.7.2", "evaluator": "unused_services_disabled",
    },
)

_BY_ID = {c["id"]: c for c in CONTROL_CATALOG}


def catalog_entry(control_id: str) -> dict[str, Any] | None:
    return _BY_ID.get(control_id)


def severity_weight(severity: str) -> int:
    return _SEVERITY_WEIGHT.get(str(severity or "").lower(), 1)


def frameworks_for(control_id: str) -> list[dict[str, Any]]:
    entry = _BY_ID.get(control_id) or {}
    return [dict(f) for f in entry.get("frameworks", [])]


def _subject_view(c: dict[str, Any]) -> dict[str, Any]:
    """The shape the compliance engine's evaluators and card render consume."""
    return {
        "control_id": c["id"],
        "title": c["title"],
        "control_area": c["control_area"],
        "cis_reference": c["cis_reference"],
        "evidence_fields": list(c["evidence"]["fields"]),
        "severity": c["severity"],
        "rationale": c["rationale"],
        "frameworks": [dict(f) for f in c["frameworks"]],
        "lifecycle": c["lifecycle"],
        "evaluator": c["evaluator"],
        "applicable_vendors": list(c["vendors"]),
        "introduced": c.get("introduced"),
    }


def catalog_baseline_controls() -> tuple[dict[str, Any], ...]:
    """The 5-key view utils.compliance_rulepack.DEFAULT_RULE_PACK consumes.

    Exactly the ten `LEGACY_CONTROL_IDS`, in that order, in the shape and with
    the keys the 0.6.6B pack (and its tests) expect; the added catalog fields
    are ignored by that view. Enrichment controls are excluded here on purpose.
    """
    return tuple(
        {
            "control_id": c["id"],
            "title": c["title"],
            "control_area": c["control_area"],
            "cis_reference": c["cis_reference"],
            "evidence_fields": list(c["evidence"]["fields"]),
        }
        for c in CONTROL_CATALOG
        if c["id"] in LEGACY_CONTROL_IDS
    )


def catalog_enrichment_controls() -> tuple[dict[str, Any], ...]:
    """Subject-scoped controls added after the 0.6.6B freeze (0.7.1b+).

    A separate list from the pack-routed baseline ten; ordered as declared.
    """
    return tuple(
        _subject_view(c)
        for c in CONTROL_CATALOG
        if c["id"] not in LEGACY_CONTROL_IDS and c.get("lifecycle") != "deprecated"
    )


def all_subject_control_ids() -> frozenset[str]:
    """Every catalog control that can be assigned to a subject (baseline + enrichment)."""
    return frozenset(c["id"] for c in CONTROL_CATALOG if c.get("lifecycle") != "deprecated")
