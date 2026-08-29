"""0.7.4 — declarative framework catalog for requirement-level coverage.

Each framework (CIS / PCI-DSS / BDDK) is modelled as an ordered list of
*requirements*. A control joins a requirement when its
``frameworks[].reference`` (the clean structured ref added in 0.7.1a) matches the
requirement id under :func:`normalize_ref`. Requirements with no mapped control
are a deliberate, visible ``UNCOVERED`` statement.

Titles are our own one-line paraphrase — **no verbatim CIS / PCI-DSS benchmark
text** (copyright). Section number + id + our title only. No certification claim.
A signed / user-authored framework pack (custom frameworks, a UI mapping editor)
is DEPLOY.1A-gated, the same class as the assignment editor.
"""
from __future__ import annotations

from typing import Any

FRAMEWORK_CATALOG_VERSION = "0.7.4"

_NA_TOKENS = {"", "not applicable", "n/a", "none", "-"}
_REF_PREFIXES = ("cis ", "pci-dss ", "pci dss ", "pci ", "bddk ")


def normalize_ref(ref: Any) -> str:
    """Canonical join key for a framework reference / requirement id.

    Lowercases, drops a framework-name prefix and a trailing "/ description",
    and maps not-applicable sentinels to "" (never matches)."""
    text = str(ref or "").strip()
    lowered = text.lower()
    if lowered in _NA_TOKENS:
        return ""
    for prefix in _REF_PREFIXES:
        if lowered.startswith(prefix):
            text = text[len(prefix):].strip()
            lowered = text.lower()
            break
    if " / " in text:
        head = text.split(" / ", 1)[0].strip()
        if head:
            text = head
    return text.strip().lower()


def _req(rid: str, section: str, title: str, applies: bool = True) -> dict[str, Any]:
    return {"id": rid, "section": section, "title": title, "applies": applies}


_CIS_REQUIREMENTS: tuple[dict[str, Any], ...] = (
    _req("2.1.1", "2.1", "Administrative login banner configured"),
    _req("2.1.2", "2.1", "Unused management-plane services disabled"),
    _req("2.1.6", "2.1", "Primary and secondary DNS configured"),
    _req("2.1.7", "2.1", "DNS search domain configured"),
    _req("2.1.8", "2.1", "Hostname configured and non-default"),
    _req("2.1.9", "2.1", "Telnet disabled"),
    _req("2.1.10", "2.1", "SSH management restricted to protocol v2"),
    _req("2.1.11", "2.1", "SSH ciphers and MACs restricted to strong set"),   # curated gap
    _req("2.1.x", "2.1", "Section 2.1 - management-plane hardening (unspecified)"),
    _req("2.2.1", "2.2", "No default SNMP community string"),
    _req("2.2.2", "2.2", "SNMP restricted to v3"),
    _req("2.2.3", "2.2", "SNMP traps sent only to authorised hosts"),          # curated gap
    _req("2.3.1", "2.3", "Primary and secondary NTP configured"),
    _req("2.3.1.1", "2.3", "NTP authentication enabled"),
    _req("2.3.2", "2.3", "System timezone explicitly configured"),
    _req("2.3.3", "2.3", "NTP source interface pinned"),                       # curated gap
    _req("2.4.1", "2.4", "Administrative password minimum length enforced"),
    _req("2.4.2", "2.4", "Administrative account lockout policy configured"),
    _req("2.4.3", "2.4", "Administrative password complexity requirement enabled"),
    _req("2.4.4", "2.4", "Administrative password history / reuse prevention"),
    _req("2.5.1", "2.5", "Console session idle timeout configured"),          # curated gap
    _req("2.5.2", "2.5", "Management session (idle) timeout policy"),
    _req("2.5.4", "2.5", "Central AAA provider configured"),
    _req("2.6.1", "2.6", "Management / administrative audit logging configured"),
    _req("2.6.2", "2.6", "Log retention and rotation configured"),            # curated gap
    _req("2.6.3", "2.6", "Remote syslog / log forwarding configured"),
    _req("2.7.1", "2.7", "Scheduled configuration backup"),                   # curated gap
    _req("panos verify update server identity", "supply-chain",
         "Update server identity verification enabled"),
)

_PCI_REQUIREMENTS: tuple[dict[str, Any], ...] = (
    # process/procedural requirements — not evidenced from device configuration
    _req("1.2.1", "1.2", "Firewall/router ruleset reviewed and restrictive", applies=False),
    _req("10.4.1", "10.4", "Audit logs reviewed", applies=False),
    _req("2.2.1", "2.2", "System configuration standards applied (identity baseline)"),
    _req("2.2.2", "2.2", "Only necessary services / protocols enabled"),
    _req("2.2.4", "2.2", "Unnecessary functionality (services) removed or disabled"),
    _req("2.2.5", "2.2", "Insecure services / protocols disabled (Telnet, SSHv1, SNMP v1/2c)"),
    _req("2.2.6", "2.2", "System security parameters configured to prevent misuse"),  # curated gap
    _req("2.2.7", "2.2", "Non-console administrative access encrypted"),
    _req("6.3.3", "6.3", "Software update integrity / source verified"),
    _req("8.2.8", "8.2", "Idle administrative session re-authentication"),
    _req("8.3.1", "8.3", "All access authenticated (central AAA)"),
    _req("8.3.4", "8.3", "Account lockout after repeated failed attempts"),
    _req("8.3.6", "8.3", "Password minimum length and complexity"),
    _req("8.3.7", "8.3", "Password history - no reuse of recent passwords"),
    _req("8.3.9", "8.3", "Password change interval enforced"),               # curated gap
    _req("10.2.1", "10.2", "Administrative activity audit logging"),
    _req("10.5.1", "10.5", "Audit log access restricted"),                  # curated gap
    _req("10.5.3", "10.5", "Audit logs promptly backed up to a central server"),
    _req("10.6.1", "10.6", "Time-synchronisation technology in use"),
    _req("10.6.2", "10.6", "Time data protected (authenticated NTP)"),
)

_BDDK_REQUIREMENTS: tuple[dict[str, Any], ...] = (
    _req("Sistem Sıkılaştırma - Kimlik", "Sistem Sıkılaştırma", "Sistem kimlik temeli (hostname)"),
    _req("Sistem Sıkılaştırma - Güvensiz Protokoller", "Sistem Sıkılaştırma", "Güvensiz protokoller kapalı"),
    _req("Sistem Sıkılaştırma - Yönetim Arayüzü", "Sistem Sıkılaştırma", "Yönetim arayüzü sıkılaştırma"),
    _req("Sistem Sıkılaştırma - İzleme Protokolleri", "Sistem Sıkılaştırma", "İzleme protokolleri (SNMP v3)"),
    _req("Sistem Sıkılaştırma - Varsayılan Kimlik Bilgileri", "Sistem Sıkılaştırma", "Varsayılan kimlik bilgileri kaldırıldı"),
    _req("Sistem Sıkılaştırma - Gereksiz Servisler", "Sistem Sıkılaştırma", "Gereksiz servisler kapalı"),
    _req("Sistem Sıkılaştırma - Kriptografik Yapılandırma", "Sistem Sıkılaştırma", "Kriptografik yapılandırma sıkılaştırma"),  # curated gap
    _req("Süreklilik - Ad Çözümleme", "Süreklilik", "Yedekli ad çözümleme"),
    _req("Süreklilik - Yapılandırma Yedeği", "Süreklilik", "Zamanlanmış yapılandırma yedeği"),  # curated gap
    _req("Kayıt Yönetimi - Zaman Damgası", "Kayıt Yönetimi", "Doğru ve yedekli zaman damgası"),
    _req("Kayıt Yönetimi - Yönetici İşlemleri", "Kayıt Yönetimi", "Yönetici işlemleri denetim kaydı"),
    _req("Kayıt Yönetimi - Merkezi Loglama", "Kayıt Yönetimi", "Merkezi log iletimi"),
    _req("Kayıt Yönetimi - Log Saklama Süresi", "Kayıt Yönetimi", "Log saklama ve rotasyon"),  # curated gap
    _req("Erişim Yönetimi - Merkezi Kimlik Doğrulama", "Erişim Yönetimi", "Merkezi kimlik doğrulama"),
    _req("Erişim Yönetimi - Oturum Zaman Aşımı", "Erişim Yönetimi", "Yönetim oturumu zaman aşımı"),
    _req("Erişim Yönetimi - Yasal Uyarı", "Erişim Yönetimi", "Yasal uyarı bandı"),
    _req("Erişim Yönetimi - Hesap Kilitleme", "Erişim Yönetimi", "Hesap kilitleme politikası"),
    _req("Erişim Yönetimi - Parola Uzunluğu", "Erişim Yönetimi", "Parola asgari uzunluğu"),
    _req("Erişim Yönetimi - Parola Karmaşıklığı", "Erişim Yönetimi", "Parola karmaşıklığı"),
    _req("Erişim Yönetimi - Parola Geçmişi", "Erişim Yönetimi", "Parola geçmişi / tekrar engeli"),
    _req("Erişim Yönetimi - Yetki Ayrıştırma", "Erişim Yönetimi",
         "Yönetici yetki ayrıştırma (süreç)", applies=False),
    _req("Tedarik Zinciri - Güncelleme Kanalı", "Tedarik Zinciri", "Güncelleme kanalı doğrulama"),
)

FRAMEWORKS: tuple[dict[str, Any], ...] = (
    {"id": "CIS", "name": "CIS Firewall Benchmark", "version": "generic (CP/PAN aligned)",
     "profile": "Level 1", "requirements": _CIS_REQUIREMENTS},
    {"id": "PCI-DSS", "name": "PCI-DSS", "version": "4.0", "profile": None,
     "requirements": _PCI_REQUIREMENTS},
    {"id": "BDDK", "name": "BDDK İyi Uygulama Rehberi", "version": "—", "profile": None,
     "requirements": _BDDK_REQUIREMENTS},
)

_BY_ID = {f["id"]: f for f in FRAMEWORKS}
FRAMEWORK_IDS: tuple[str, ...] = tuple(f["id"] for f in FRAMEWORKS)


def framework_entry(framework_id: str) -> dict[str, Any] | None:
    return _BY_ID.get(framework_id)


def requirements_for(framework_id: str) -> tuple[dict[str, Any], ...]:
    entry = _BY_ID.get(framework_id) or {}
    return tuple(entry.get("requirements") or ())


def requirement_index(framework_id: str) -> dict[str, dict[str, Any]]:
    """normalized requirement id -> requirement dict."""
    return {normalize_ref(r["id"]): r for r in requirements_for(framework_id)}
