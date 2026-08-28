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

CATALOG_VERSION = "0.7.1a"
SEVERITY_VALUES = ("informational", "low", "medium", "high", "critical")
_SEVERITY_WEIGHT = {"informational": 1, "low": 2, "medium": 3, "high": 4, "critical": 5}


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

    # 0.7.1b adds ~12 further controls (existing sections + a password_policy
    # projection). 0.7.1a is the catalog model + framework grouping + severity
    # for the ten above, purely additive to the payload.
)

_BY_ID = {c["id"]: c for c in CONTROL_CATALOG}


def catalog_entry(control_id: str) -> dict[str, Any] | None:
    return _BY_ID.get(control_id)


def severity_weight(severity: str) -> int:
    return _SEVERITY_WEIGHT.get(str(severity or "").lower(), 1)


def frameworks_for(control_id: str) -> list[dict[str, Any]]:
    entry = _BY_ID.get(control_id) or {}
    return [dict(f) for f in entry.get("frameworks", [])]


def catalog_baseline_controls() -> tuple[dict[str, Any], ...]:
    """The 5-key view utils.compliance_rulepack.DEFAULT_RULE_PACK consumes.

    Preserves the exact shape and order the 0.6.6B pack (and its tests) expect;
    the added catalog fields are ignored by that view.
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
    )
