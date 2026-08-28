"""0.7.x — crypto posture payload: normalized facts + rule-pack evaluation.

Deterministic, offline, side-effect free. Reuses the immutable stored PAN
`effective-running` XML (no new collection). Additive to the existing HTML
payloads — see utils/html_export.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

from configuration.current_config_projection import _artifact_bytes
from utils.crypto_facts import (
    LEGACY_DH_GROUPS,
    STRONG_DH_GROUPS,
    WEAK_DH_GROUPS,
    WEAK_ENCRYPTION,
    WEAK_INTEGRITY,
    WEAK_TLS_MIN,
    extract_cp_crypto_facts,
    extract_pan_crypto_facts,
)
from utils.crypto_rulepack import DEFAULT_CRYPTO_RULE_PACK, crypto_rule_pack_summary

CRYPTO_SCHEMA_VERSION = "0.7.0"
BASE_DIR = Path(__file__).resolve().parent.parent


def _as_dict(v: Any) -> dict[str, Any]:
    return v if isinstance(v, dict) else {}


def _as_list(v: Any) -> list[Any]:
    return v if isinstance(v, list) else []


# --- evaluators: (facts) -> (status, summary) --------------------------------

def _ike_enc(f):
    hits = {a for p in f["ike_crypto_profiles"] for a in p["encryption"] if a in WEAK_ENCRYPTION}
    if hits:
        return "FINDING", f"IKE encryption includes weak cipher(s): {', '.join(sorted(hits))}."
    if f["ike_crypto_profiles"]:
        return "PASS", "IKE encryption avoids known-weak ciphers."
    return "INSUFFICIENT_EVIDENCE", "No IKE crypto profile observed in configuration."


def _ike_integ(f):
    hits = {a for p in f["ike_crypto_profiles"] for a in p["hash"] if a in WEAK_INTEGRITY}
    if hits:
        return "FINDING", f"IKE integrity uses weak hash(es): {', '.join(sorted(hits))}."
    if f["ike_crypto_profiles"]:
        return "PASS", "IKE integrity avoids MD5/SHA-1."
    return "INSUFFICIENT_EVIDENCE", "No IKE crypto profile observed."


def _ike_dh(f):
    groups = {g for p in f["ike_crypto_profiles"] for g in p["dh_group"]}
    weak = groups & WEAK_DH_GROUPS
    if weak:
        return "FINDING", f"IKE DH group(s) {', '.join(sorted(weak))} are weak (<=1024-bit / broken)."
    if groups & STRONG_DH_GROUPS:
        return "PASS", "IKE uses a strong DH/ECP group."
    if groups & LEGACY_DH_GROUPS:
        return "INFORMATIONAL", "IKE uses DH group 14 (2048-bit MODP) - acceptable; plan migration to ECP / group 19+."
    if f["ike_crypto_profiles"]:
        return "INFORMATIONAL", "IKE DH group not recognised; review manually."
    return "INSUFFICIENT_EVIDENCE", "No IKE crypto profile observed."


def _ikev1_aggr(f):
    hits = [g["name"] for g in f["ike_gateways"]
            if (g.get("protocol_version") or "").startswith("ikev1") and g.get("exchange_mode") == "aggressive"]
    if hits:
        return "FINDING", "IKEv1 aggressive mode in use on IKE gateway(s)."
    if f["ike_gateways"]:
        return "PASS", "No IKEv1 aggressive mode observed."
    return "INSUFFICIENT_EVIDENCE", "No IKE gateway observed."


def _ipsec_enc(f):
    hits = {a for p in f["ipsec_crypto_profiles"] for a in p["esp_encryption"] if a in WEAK_ENCRYPTION}
    if hits:
        return "FINDING", f"IPsec ESP encryption includes weak cipher(s): {', '.join(sorted(hits))}."
    if f["ipsec_crypto_profiles"]:
        return "PASS", "IPsec ESP encryption avoids known-weak ciphers."
    return "INSUFFICIENT_EVIDENCE", "No IPsec crypto profile observed."


def _ipsec_integ(f):
    hits = {a for p in f["ipsec_crypto_profiles"] for a in p["authentication"] if a in WEAK_INTEGRITY}
    if hits:
        return "FINDING", f"IPsec integrity uses weak hash(es): {', '.join(sorted(hits))}."
    if f["ipsec_crypto_profiles"]:
        return "PASS", "IPsec integrity avoids MD5/SHA-1."
    return "INSUFFICIENT_EVIDENCE", "No IPsec crypto profile observed."


def _ipsec_pfs(f):
    if not f["ipsec_crypto_profiles"]:
        return "INSUFFICIENT_EVIDENCE", "No IPsec crypto profile observed."
    for p in f["ipsec_crypto_profiles"]:
        groups = set(p["dh_group"])
        if not groups or groups & {"no-pfs", "none"}:
            return "FINDING", "IPsec profile has PFS disabled (no-pfs)."
        if groups & WEAK_DH_GROUPS:
            return "FINDING", f"IPsec PFS uses a weak DH group: {', '.join(sorted(groups & WEAK_DH_GROUPS))}."
    return "PASS", "IPsec PFS is enabled with a non-weak DH group."


def _tls_min(f):
    hits = {p["min_version"] for p in f["tls_service_profiles"] if p.get("min_version") in WEAK_TLS_MIN}
    if hits:
        return "FINDING", f"Management TLS minimum version is below 1.2: {', '.join(sorted(hits))}."
    if f["tls_service_profiles"]:
        return "PASS", "Management TLS minimum version is 1.2 or higher."
    return "INSUFFICIENT_EVIDENCE", "No SSL/TLS service profile observed."


def _cert_expiry(f):
    if not f["certificates"]:
        return "INSUFFICIENT_EVIDENCE", "No certificate metadata observed."
    days = [c["days_until_expiry"] for c in f["certificates"] if c.get("days_until_expiry") is not None]
    if not days:
        return "INSUFFICIENT_EVIDENCE", "Certificate validity dates not present in evidence."
    worst = min(days)
    if worst < 0:
        return "FINDING", f"At least one certificate is expired ({worst:.0f} days)."
    if worst < 30:
        return "FINDING", f"A certificate expires in {worst:.0f} days (< 30)."
    if worst < 90:
        return "INFORMATIONAL", f"A certificate expires in {worst:.0f} days (< 90) - schedule renewal."
    return "PASS", "No certificate is expired or within 90 days of expiry."


def _cert_algo(f):
    algos = {(c.get("algorithm") or "").upper() for c in f["certificates"] if c.get("algorithm")}
    if not algos:
        return "INSUFFICIENT_EVIDENCE", "Certificate key algorithm not present in evidence."
    if algos - {"EC", "ECDSA"}:
        return "INFORMATIONAL", "RSA certificate key(s) present - consider ECDSA and track PQC-capable options."
    return "PASS", "Certificate key algorithm is ECDSA."


def _single_ike(f):
    if not f["ike_crypto_profiles"]:
        return "INSUFFICIENT_EVIDENCE", "No IKE crypto profile observed."
    if all(p["proposal_count"] <= 1 for p in f["ike_crypto_profiles"]):
        return "INFORMATIONAL", "IKE crypto profile(s) offer a single proposal - brittle for negotiation/agility."
    return "PASS", "IKE crypto profile offers multiple proposals."


def _single_ipsec(f):
    if not f["ipsec_crypto_profiles"]:
        return "INSUFFICIENT_EVIDENCE", "No IPsec crypto profile observed."
    if all(p["proposal_count"] <= 1 for p in f["ipsec_crypto_profiles"]):
        return "INFORMATIONAL", "IPsec crypto profile(s) offer a single proposal - brittle for negotiation/agility."
    return "PASS", "IPsec crypto profile offers multiple proposals."


def _ike_lifetime(f):
    lts = [p["lifetime_hours"] for p in f["ike_crypto_profiles"] if p.get("lifetime_hours") is not None]
    if not lts:
        return "INSUFFICIENT_EVIDENCE", "IKE lifetime not present in evidence."
    if any(h > 24 or h < 1 for h in lts):
        return "INFORMATIONAL", "An IKE SA lifetime is outside the 1h..24h range - review rekey policy."
    return "PASS", "IKE SA lifetime is within a sane range."


def _pqc_capability(f, *, sw_version: str | None):
    ver = str(sw_version or "").strip()
    capable = "UNKNOWN"
    note = "Post-quantum hybrid key-exchange capability could not be determined from evidence."
    try:
        major, minor = (int(x) for x in ver.split(".")[:2])
        if (major, minor) >= (11, 1):
            capable = "LIKELY"
            note = f"PAN-OS {ver} likely supports IKEv2 hybrid post-quantum key exchange; verify configuration and roadmap."
        else:
            capable = "NO"
            note = f"PAN-OS {ver} predates hybrid post-quantum key-exchange support."
    except (ValueError, TypeError):
        pass
    return "INFORMATIONAL", note, capable


_DISPATCH = {
    "ike_encryption_weak": _ike_enc, "ike_integrity_weak": _ike_integ, "ike_dh_group_weak": _ike_dh,
    "ikev1_aggressive_mode": _ikev1_aggr, "ipsec_esp_encryption_weak": _ipsec_enc,
    "ipsec_integrity_weak": _ipsec_integ, "ipsec_pfs_absent_or_weak": _ipsec_pfs,
    "tls_min_version_below_1_2": _tls_min, "certificate_expired_or_near_expiry": _cert_expiry,
    "certificate_algorithm_legacy": _cert_algo, "single_ike_proposal": _single_ike,
    "single_ipsec_proposal": _single_ipsec, "ike_lifetime_out_of_range": _ike_lifetime,
}

_STATUS_ORDER = ["FINDING", "INFORMATIONAL", "PASS", "INSUFFICIENT_EVIDENCE", "NOT_APPLICABLE"]


def _subject_from_facts(subject_id: str, facts: dict[str, Any], *, sw_version: str | None) -> dict[str, Any]:
    findings: list[dict[str, Any]] = []
    for rule in DEFAULT_CRYPTO_RULE_PACK["rules"]:
        cid = rule["control_id"]
        if cid == "pqc_hybrid_kex_platform_capability":
            status, summary, capable = _pqc_capability(facts, sw_version=sw_version)
            extra = {"hybrid_key_exchange_capable": capable}
        else:
            status, summary = _DISPATCH[cid](facts)
            extra = {}
        findings.append({
            "rule_id": rule["rule_id"], "control_id": cid, "category": rule["category"],
            "title": rule["title"], "status": status,
            "evidence_basis": "configured" if status not in ("INSUFFICIENT_EVIDENCE",) else "insufficient",
            "summary": summary, "evidence_fields": rule["evidence_fields"],
            "framework_refs": rule["framework_refs"], **extra,
        })
    statuses = {row["status"] for row in findings}
    rollup = next((s for s in _STATUS_ORDER if s in statuses), "INSUFFICIENT_EVIDENCE")
    return {
        "subject_id": subject_id, "vendor_key": facts.get("vendor_key"),
        "availability": "AVAILABLE", "status": rollup,
        "facts": {k: facts[k] for k in
                  ("ike_crypto_profiles", "ipsec_crypto_profiles", "ike_gateways",
                   "tls_service_profiles", "certificates") if k in facts},
        "management_plane_gap": bool(facts.get("management_plane_gap")),
        "findings": findings,
    }


def _empty_payload() -> dict[str, Any]:
    return {
        "schema_version": CRYPTO_SCHEMA_VERSION, "available": False,
        "classification": "evidence_backed_crypto_posture",
        "disclaimer": "Evidence-backed crypto-area evaluation only. Not a certification or complete cryptographic assessment.",
        "rule_pack": crypto_rule_pack_summary(),
        "evidence_confidence_model": {
            "configured": "read from stored device configuration",
            "negotiated": "from a live security association - future runtime-evidence layer, not in this build",
            "inferred": "derived from platform/OS facts",
            "insufficient": "the relevant configuration section was not observed",
        },
        "subjects": [], "fleet": {"subjects": 0, "evaluated_subjects": 0,
                                  "status_counts": {}, "category_counts": {}},
        "pqc": {"status": "INFORMATIONAL", "platform_capability": []},
        "privacy": {"contains_secrets": False, "contains_key_material": False,
                    "contains_certificate_content": False, "contains_real_identity": False},
    }


def build_crypto_posture(
    config_result: dict[str, Any] | None,
    checkpoint_config_result: dict[str, Any] | None = None,
    *,
    repository_root: Path | str | None = None,
    configuration_ui: dict[str, Any] | None = None,
) -> dict[str, Any]:
    base_dir = Path(repository_root) if repository_root is not None else BASE_DIR
    pan = _as_dict(config_result)
    subjects: list[dict[str, Any]] = []
    pqc_capability: list[dict[str, Any]] = []

    pan_i = 0
    for row_value in _as_list(pan.get("devices")):
        row = _as_dict(row_value)
        artifact = _as_dict(_as_dict(row.get("direct")).get("effective"))
        content = _artifact_bytes(base_dir, artifact)
        if not content:
            continue
        facts = extract_pan_crypto_facts(content)
        if not facts.get("available"):
            continue
        pan_i += 1
        sw = row.get("sw_version")
        subjects.append(_subject_from_facts(f"pan-{pan_i:03d}", facts, sw_version=sw))
        _, _, capable = _pqc_capability(facts, sw_version=sw)
        pqc_capability.append({"vendor_key": "palo_alto", "sw_version": sw,
                               "hybrid_key_exchange_capable": capable,
                               "note": "capability, not configured posture"})

    cp_i = 0
    cp_devices = [d for d in _as_list(_as_dict(configuration_ui).get("devices"))
                  if _as_dict(d).get("vendor_key") == "check_point"]
    for d in cp_devices:
        facts = extract_cp_crypto_facts(_as_dict(d))
        cp_i += 1
        subjects.append(_subject_from_facts(f"cp-{cp_i:03d}", facts, sw_version=None))

    if not subjects:
        return _empty_payload()

    status_counts: dict[str, int] = {}
    category_counts: dict[str, dict[str, int]] = {}
    for s in subjects:
        for row in s["findings"]:
            status_counts[row["status"]] = status_counts.get(row["status"], 0) + 1
            cat = category_counts.setdefault(row["category"], {})
            cat[row["status"]] = cat.get(row["status"], 0) + 1

    payload = _empty_payload()
    payload.update({
        "available": True,
        "subjects": subjects,
        "fleet": {
            "subjects": len(subjects),
            "evaluated_subjects": len(subjects),
            "status_counts": status_counts,
            "category_counts": category_counts,
        },
        "pqc": {"status": "INFORMATIONAL", "platform_capability": pqc_capability},
    })
    return payload
