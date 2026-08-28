"""0.6.6B — Compliance rule-pack transition foundation.

A minimal, in-repository, static, versioned declarative boundary over the
existing ten deterministic CP/PAN compliance controls. The pack is a local
evaluation contract, not a policy source of truth and not a certification
engine. Dynamic/remote/signed packs and tenant overrides are deferred to
0.7.x deployment-era governance.

`BASELINE_CONTROLS` is the single source of truth for the ten controls;
`utils.compliance_posture` derives its evaluators and payload from
`DEFAULT_RULE_PACK`. No I/O, no network, no runtime mutation.
"""
from __future__ import annotations

from typing import Any

RULE_PACK_SCHEMA_VERSION = "1.0"
DEFAULT_RULE_PACK_ID = "securityexpert.baseline.cp-pan"
DEFAULT_RULE_PACK_VERSION = "0.6.6B"

# The ten deterministic vendor-neutral controls. Content is unchanged from the
# former utils.compliance_posture.VENDOR_NEUTRAL_CONTROLS literal; only its home
# moved here so the pack and the evaluators share one definition.
BASELINE_CONTROLS: tuple[dict[str, Any], ...] = (
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


def _rule(control: dict[str, Any]) -> dict[str, Any]:
    """One declarative rule for one baseline control.

    Keeps the exact keys the existing deterministic evaluators read
    (`control_id`, `title`, `control_area`, `cis_reference`, `evidence_fields`)
    so routing evaluation through the pack cannot change evaluation inputs, and
    adds the pack traceability metadata.
    """
    return {
        "rule_id": f"{DEFAULT_RULE_PACK_ID}::{control['control_id']}",
        "control_id": control["control_id"],
        "title": control["title"],
        "control_area": control["control_area"],
        "cis_reference": control["cis_reference"],
        "evidence_fields": list(control["evidence_fields"]),
        "benchmark": "CIS",
        "benchmark_reference": control["cis_reference"],
        "applicability": {"vendors": ["check_point", "palo_alto"], "scope": "SUBJECT"},
        "evaluator": control["control_id"],
    }


DEFAULT_RULE_PACK: dict[str, Any] = {
    "pack_id": DEFAULT_RULE_PACK_ID,
    "pack_version": DEFAULT_RULE_PACK_VERSION,
    "schema_version": RULE_PACK_SCHEMA_VERSION,
    "title": "SecurityExpert baseline CP/PAN control pack",
    "source": "in_repository_static",
    "certification_claim": False,
    "disclaimer": (
        "Evidence-backed control-area evaluation only. Not a certification, "
        "attestation or complete framework assessment."
    ),
    "rules": tuple(_rule(control) for control in BASELINE_CONTROLS),
}


def rule_pack_summary(pack: dict[str, Any] = DEFAULT_RULE_PACK) -> dict[str, Any]:
    """Safe, static metadata block for the compliance payload — pack identity,
    version and the no-certification contract. Contains no evidence values."""
    return {
        "pack_id": pack["pack_id"],
        "pack_version": pack["pack_version"],
        "schema_version": pack["schema_version"],
        "title": pack["title"],
        "source": pack["source"],
        "certification_claim": pack["certification_claim"],
        "disclaimer": pack["disclaimer"],
        "rule_count": len(pack["rules"]),
    }
