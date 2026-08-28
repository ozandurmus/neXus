"""0.7.x — Cryptographic posture rule pack.

A static, versioned, in-repository declarative pack over normalized IPsec/IKE/
TLS/certificate facts (see utils/crypto_facts). Same model as
utils/compliance_rulepack: a frozen dict, no I/O, no network, no runtime
mutation, no certification claim. Distinct from the compliance rule pack.
"""
from __future__ import annotations

from typing import Any

CRYPTO_RULE_PACK_SCHEMA_VERSION = "1.0"
CRYPTO_RULE_PACK_ID = "securityexpert.crypto.cp-pan"
CRYPTO_RULE_PACK_VERSION = "0.7.0"

CRYPTO_STATUS_VALUES = ("PASS", "FINDING", "INFORMATIONAL", "INSUFFICIENT_EVIDENCE", "NOT_APPLICABLE")

# category ∈ {weak_algorithm, crypto_agility, pqc_readiness}
_RULES: tuple[dict[str, Any], ...] = (
    {"control_id": "ike_encryption_weak", "category": "weak_algorithm",
     "title": "IKE encryption avoids known-weak ciphers",
     "evidence_fields": ["network.ike.crypto-profiles.ike-crypto-profiles.encryption"]},
    {"control_id": "ike_integrity_weak", "category": "weak_algorithm",
     "title": "IKE integrity avoids MD5/SHA-1",
     "evidence_fields": ["network.ike.crypto-profiles.ike-crypto-profiles.hash"]},
    {"control_id": "ike_dh_group_weak", "category": "weak_algorithm",
     "title": "IKE Diffie-Hellman group is not 1/2/5",
     "evidence_fields": ["network.ike.crypto-profiles.ike-crypto-profiles.dh-group"]},
    {"control_id": "ikev1_aggressive_mode", "category": "weak_algorithm",
     "title": "IKEv1 aggressive mode not in use",
     "evidence_fields": ["network.ike.gateway.protocol.ikev1.exchange-mode"]},
    {"control_id": "ipsec_esp_encryption_weak", "category": "weak_algorithm",
     "title": "IPsec ESP encryption avoids known-weak ciphers",
     "evidence_fields": ["network.ike.crypto-profiles.ipsec-crypto-profiles.esp.encryption"]},
    {"control_id": "ipsec_integrity_weak", "category": "weak_algorithm",
     "title": "IPsec integrity avoids MD5/SHA-1",
     "evidence_fields": ["network.ike.crypto-profiles.ipsec-crypto-profiles.esp.authentication"]},
    {"control_id": "ipsec_pfs_absent_or_weak", "category": "weak_algorithm",
     "title": "IPsec PFS enabled with a non-weak DH group",
     "evidence_fields": ["network.ike.crypto-profiles.ipsec-crypto-profiles.dh-group"]},
    {"control_id": "tls_min_version_below_1_2", "category": "weak_algorithm",
     "title": "Management TLS minimum version is >= 1.2",
     "evidence_fields": ["ssl-tls-service-profile.protocol-settings.min-version"]},
    {"control_id": "certificate_expired_or_near_expiry", "category": "weak_algorithm",
     "title": "Certificates are valid and not near expiry",
     "evidence_fields": ["shared.certificate.not-valid-after"]},
    {"control_id": "certificate_algorithm_legacy", "category": "weak_algorithm",
     "title": "Certificate key algorithm (RSA legacy vs ECDSA)",
     "evidence_fields": ["shared.certificate.algorithm"]},
    {"control_id": "single_ike_proposal", "category": "crypto_agility",
     "title": "IKE crypto profile offers more than one proposal",
     "evidence_fields": ["network.ike.crypto-profiles.ike-crypto-profiles"]},
    {"control_id": "single_ipsec_proposal", "category": "crypto_agility",
     "title": "IPsec crypto profile offers more than one proposal",
     "evidence_fields": ["network.ike.crypto-profiles.ipsec-crypto-profiles"]},
    {"control_id": "ike_lifetime_out_of_range", "category": "crypto_agility",
     "title": "IKE SA lifetime is within a sane range (1h..24h)",
     "evidence_fields": ["network.ike.crypto-profiles.ike-crypto-profiles.lifetime"]},
    {"control_id": "pqc_hybrid_kex_platform_capability", "category": "pqc_readiness",
     "title": "Platform capability for hybrid post-quantum key exchange",
     "evidence_fields": ["system.sw-version"]},
)

_FRAMEWORK_REFS = {
    "weak_algorithm": ["NIST SP 800-131A", "PCI-DSS 4.0 (strong cryptography)"],
    "crypto_agility": ["NIST SP 800-131A"],
    "pqc_readiness": ["NIST PQC transition guidance"],
}


def _rule(spec: dict[str, Any]) -> dict[str, Any]:
    return {
        "rule_id": f"{CRYPTO_RULE_PACK_ID}::{spec['control_id']}",
        "control_id": spec["control_id"],
        "category": spec["category"],
        "title": spec["title"],
        "evidence_fields": list(spec["evidence_fields"]),
        "framework_refs": list(_FRAMEWORK_REFS.get(spec["category"], [])),
        "applicability": {"vendors": ["check_point", "palo_alto"], "scope": "SUBJECT"},
        "evaluator": spec["control_id"],
    }


DEFAULT_CRYPTO_RULE_PACK: dict[str, Any] = {
    "pack_id": CRYPTO_RULE_PACK_ID,
    "pack_version": CRYPTO_RULE_PACK_VERSION,
    "schema_version": CRYPTO_RULE_PACK_SCHEMA_VERSION,
    "title": "SecurityExpert CP/PAN cryptographic posture pack",
    "source": "in_repository_static",
    "certification_claim": False,
    "disclaimer": (
        "Evidence-backed crypto-area evaluation only. Not a certification, "
        "attestation or complete cryptographic assessment."
    ),
    "rules": tuple(_rule(spec) for spec in _RULES),
}


def crypto_rule_pack_summary(pack: dict[str, Any] = DEFAULT_CRYPTO_RULE_PACK) -> dict[str, Any]:
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
