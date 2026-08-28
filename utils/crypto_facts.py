"""0.7.x — normalized cryptographic facts from already-collected config evidence.

PAN facts come from the stored `effective-running` XML (the same immutable CAS
artifact `build_pan_current_configuration` reads). CP facts are best-effort from
the sanitized CP configuration projection (web-UI TLS / SSH crypto where the
Gaia `show configuration` surfaced them); CP VPN-community crypto is a
Management-plane concept and is reported as an explicit evidence gap.

Privacy: only algorithm names, DH group numbers, key sizes, protocol versions
and certificate validity dates are extracted. Never key material, pre-shared
keys or certificate bodies.
"""
from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any

from lxml import etree

WEAK_ENCRYPTION = {"des", "3des", "des3", "rc4", "null", "none"}
WEAK_INTEGRITY = {"md5", "sha1", "sha-1", "none"}
WEAK_DH_GROUPS = {"1", "2", "5", "group1", "group2", "group5"}
LEGACY_DH_GROUPS = {"14", "group14"}
STRONG_DH_GROUPS = {"15", "16", "19", "20", "21", "group15", "group16", "group19", "group20", "group21"}
WEAK_TLS_MIN = {"tls1-0", "tls1.0", "tls1_0", "tls1-1", "tls1.1", "tls1_1", "sslv3", "ssl3"}


def _safe_xml(content: bytes) -> etree._Element:
    parser = etree.XMLParser(resolve_entities=False, no_network=True, recover=False, huge_tree=True)
    return etree.fromstring(content, parser=parser)


def _ln(el: etree._Element, name: str) -> list[etree._Element]:
    return el.xpath(f".//*[local-name()=$n]", n=name)


def _members(el: etree._Element, child: str) -> list[str]:
    out: list[str] = []
    for holder in el.xpath(f"./*[local-name()=$n]", n=child):
        members = holder.xpath("./*[local-name()='member']")
        if members:
            out += [str(m.text or "").strip().lower() for m in members if (m.text or "").strip()]
        elif (holder.text or "").strip():
            out.append(str(holder.text).strip().lower())
    return [v for v in out if v]


def _first_text(el: etree._Element, *path_names: str) -> str | None:
    node = el
    for name in path_names:
        found = node.xpath(f"./*[local-name()=$n]", n=name)
        if not found:
            return None
        node = found[0]
    text = str(node.text or "").strip()
    return text or None


def _lifetime_hours(el: etree._Element) -> float | None:
    lt = el.xpath("./*[local-name()='lifetime']")
    if not lt:
        return None
    lt = lt[0]
    for unit, mult in (("seconds", 1 / 3600), ("minutes", 1 / 60), ("hours", 1), ("days", 24)):
        v = _first_text(lt, unit)
        if v:
            try:
                return round(float(v) * mult, 2)
            except ValueError:
                return None
    return None


def _proposal_count(enc: list[str], integ: list[str], dh: list[str]) -> int:
    return max(len(enc), 1) * max(len(integ), 1) * max(len(dh), 1)


def extract_pan_crypto_facts(xml_bytes: bytes) -> dict[str, Any]:
    """Return normalized PAN crypto facts, or an unavailable marker."""
    try:
        root = _safe_xml(xml_bytes)
    except (etree.XMLSyntaxError, ValueError):
        return {"available": False, "reason": "effective_running_xml_invalid"}

    ike_profiles: list[dict[str, Any]] = []
    ipsec_profiles: list[dict[str, Any]] = []
    ike_gateways: list[dict[str, Any]] = []
    tls_profiles: list[dict[str, Any]] = []
    certificates: list[dict[str, Any]] = []

    for holder in root.xpath("//*[local-name()='ike-crypto-profiles']/*[local-name()='entry']"):
        enc = _members(holder, "encryption")
        hsh = _members(holder, "hash")
        dh = _members(holder, "dh-group")
        ike_profiles.append({
            "name": str(holder.get("name") or "").strip() or None,
            "encryption": enc, "hash": hsh, "dh_group": dh,
            "lifetime_hours": _lifetime_hours(holder),
            "proposal_count": _proposal_count(enc, hsh, dh),
            "evidence_basis": "configured",
        })

    for holder in root.xpath("//*[local-name()='ipsec-crypto-profiles']/*[local-name()='entry']"):
        esp = holder.xpath("./*[local-name()='esp']")
        enc = _members(esp[0], "encryption") if esp else []
        auth = _members(esp[0], "authentication") if esp else _members(holder, "authentication")
        dh = _members(holder, "dh-group")
        ipsec_profiles.append({
            "name": str(holder.get("name") or "").strip() or None,
            "esp_encryption": enc, "authentication": auth, "dh_group": dh,
            "lifetime_hours": _lifetime_hours(holder),
            "proposal_count": _proposal_count(enc, auth, dh or ["_"]),
            "evidence_basis": "configured",
        })

    for gw in root.xpath("//*[local-name()='ike']/*[local-name()='gateway']/*[local-name()='entry']"):
        proto = gw.xpath("./*[local-name()='protocol']")
        version = _first_text(proto[0], "version") if proto else None
        exch = _first_text(proto[0], "ikev1", "exchange-mode") if proto else None
        ike_gateways.append({
            "name": str(gw.get("name") or "").strip() or None,
            "protocol_version": (version or "").strip().lower() or None,
            "exchange_mode": (exch or "").strip().lower() or None,
            "evidence_basis": "configured",
        })

    for prof in root.xpath("//*[local-name()='ssl-tls-service-profile']/*[local-name()='entry']"):
        ps = prof.xpath("./*[local-name()='protocol-settings']")
        min_v = (_first_text(ps[0], "min-version") if ps else None) or ""
        max_v = (_first_text(ps[0], "max-version") if ps else None) or ""
        tls_profiles.append({
            "name": str(prof.get("name") or "").strip() or None,
            "min_version": min_v.strip().lower() or None,
            "max_version": max_v.strip().lower() or None,
            "evidence_basis": "configured",
        })

    now = datetime.now(timezone.utc)
    for cert in root.xpath("//*[local-name()='certificate']/*[local-name()='entry']"):
        algo = (_first_text(cert, "algorithm") or "").strip().upper() or None
        epoch = _first_text(cert, "expiry-epoch")
        not_after = _first_text(cert, "not-valid-after")
        days_left: float | None = None
        if epoch:
            try:
                days_left = round((datetime.fromtimestamp(int(epoch), timezone.utc) - now).total_seconds() / 86400, 1)
            except (ValueError, OverflowError):
                days_left = None
        elif not_after:
            m = re.match(r"([A-Za-z]{3})\s+(\d{1,2})\s+\d{2}:\d{2}:\d{2}\s+(\d{4})", not_after)
            if m:
                try:
                    dt = datetime.strptime(f"{m.group(1)} {m.group(2)} {m.group(3)}", "%b %d %Y").replace(tzinfo=timezone.utc)
                    days_left = round((dt - now).total_seconds() / 86400, 1)
                except ValueError:
                    days_left = None
        certificates.append({
            "algorithm": algo,  # RSA / EC — no key material
            "ca": (_first_text(cert, "ca") or "").strip().lower() in {"yes", "true"},
            "days_until_expiry": days_left,
            "evidence_basis": "configured" if (epoch or not_after) else "insufficient",
        })

    return {
        "available": True,
        "vendor_key": "palo_alto",
        "ike_crypto_profiles": ike_profiles,
        "ipsec_crypto_profiles": ipsec_profiles,
        "ike_gateways": ike_gateways,
        "tls_service_profiles": tls_profiles,
        "certificates": certificates,
    }


_CP_CRYPTO_SETTING_RE = re.compile(r"\b(tls|ssl|ssh)\b.*\b(version|cipher|kex|mac|protocol)\b", re.I)


def extract_cp_crypto_facts(cp_device: dict[str, Any]) -> dict[str, Any]:
    """Best-effort CP crypto facts from the sanitized configuration projection.

    Gaia `show configuration` does not carry VPN-community crypto (Management
    plane). Web-UI TLS / SSH crypto is picked up only where the projection
    surfaced it; otherwise this is an explicit evidence gap.
    """
    sections = (cp_device.get("current_configuration") or {}).get("sections") or []
    tls_service_profiles: list[dict[str, Any]] = []
    for section in sections:
        for row in section.get("settings") or []:
            name = str(row.get("setting") or "")
            value = str(row.get("value") or "").strip().lower()
            if _CP_CRYPTO_SETTING_RE.search(name):
                tls_service_profiles.append({
                    "name": name, "min_version": value or None, "max_version": None,
                    "evidence_basis": "configured",
                })
    return {
        "available": True,
        "vendor_key": "check_point",
        "ike_crypto_profiles": [],
        "ipsec_crypto_profiles": [],
        "ike_gateways": [],
        "tls_service_profiles": tls_service_profiles,
        "certificates": [],
        "management_plane_gap": True,
    }
