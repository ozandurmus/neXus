"""0.7.x — Cryptographic Posture, Crypto-Agility & PQC Readiness.

Normalized IKE/IPsec/TLS/certificate facts from the stored PAN effective-running
XML (no new collector), evaluated through a static versioned crypto rule pack.
AC-1..AC-6 from docs/history/phase/0_7_x_CRYPTO_AGILITY_PQC.md.
"""
import json
import time

import pytest

from utils.crypto_posture import build_crypto_posture
from utils.crypto_rulepack import DEFAULT_CRYPTO_RULE_PACK, crypto_rule_pack_summary

pytestmark = pytest.mark.compliance

_EXPIRED_EPOCH = int(time.time()) - 86400
_FUTURE_EPOCH = int(time.time()) + 400 * 86400

_WEAK_XML = f"""<config>
 <network><ike>
  <crypto-profiles>
   <ike-crypto-profiles>
    <entry name="legacy">
     <encryption><member>3des</member></encryption>
     <hash><member>sha1</member></hash>
     <dh-group><member>group2</member></dh-group>
     <lifetime><hours>8</hours></lifetime>
    </entry>
   </ike-crypto-profiles>
   <ipsec-crypto-profiles>
    <entry name="legacy-ipsec">
     <esp><encryption><member>3des</member></encryption><authentication><member>md5</member></authentication></esp>
     <dh-group><member>no-pfs</member></dh-group>
    </entry>
   </ipsec-crypto-profiles>
  </crypto-profiles>
  <gateway><entry name="gw1"><protocol><version>ikev1</version><ikev1><exchange-mode>aggressive</exchange-mode></ikev1></protocol></entry></gateway>
 </ike></network>
 <shared>
  <ssl-tls-service-profile><entry name="mgmt"><protocol-settings><min-version>tls1-0</min-version></protocol-settings></entry></ssl-tls-service-profile>
  <certificate>
   <entry name="old"><algorithm>RSA</algorithm><ca>no</ca><expiry-epoch>{_EXPIRED_EPOCH}</expiry-epoch></entry>
  </certificate>
 </shared>
</config>"""

_STRONG_XML = f"""<config>
 <network><ike>
  <crypto-profiles>
   <ike-crypto-profiles>
    <entry name="modern">
     <encryption><member>aes-256-gcm</member><member>aes-128-gcm</member></encryption>
     <hash><member>sha384</member><member>sha256</member></hash>
     <dh-group><member>group20</member><member>group19</member></dh-group>
     <lifetime><hours>8</hours></lifetime>
    </entry>
   </ike-crypto-profiles>
   <ipsec-crypto-profiles>
    <entry name="modern-ipsec">
     <esp><encryption><member>aes-256-gcm</member><member>aes-128-gcm</member></encryption><authentication><member>sha256</member></authentication></esp>
     <dh-group><member>group20</member></dh-group>
    </entry>
   </ipsec-crypto-profiles>
  </crypto-profiles>
  <gateway><entry name="gw1"><protocol><version>ikev2</version></protocol></entry></gateway>
 </ike></network>
 <shared>
  <ssl-tls-service-profile><entry name="mgmt"><protocol-settings><min-version>tls1-2</min-version></protocol-settings></entry></ssl-tls-service-profile>
  <certificate>
   <entry name="ecdsa"><algorithm>EC</algorithm><ca>no</ca><expiry-epoch>{_FUTURE_EPOCH}</expiry-epoch></entry>
  </certificate>
 </shared>
</config>"""


def _config_result(tmp_path, xml, name="fw.xml", sw="11.2.0"):
    p = tmp_path / name
    p.write_text(xml, encoding="utf-8")
    return {"devices": [{"serial": "SER1", "sw_version": sw,
                         "direct": {"effective": {"status": "success", "artifact_object": str(p)}}}]}


def _finding_status(payload, control_id):
    row = next(r for s in payload["subjects"] for r in s["findings"] if r["control_id"] == control_id)
    return row["status"]


# --- AC-1: static versioned pack ---------------------------------------

def test_ac1_crypto_rule_pack_identity():
    pack = DEFAULT_CRYPTO_RULE_PACK
    assert pack["pack_id"] == "securityexpert.crypto.cp-pan"
    assert pack["pack_version"] == "0.7.0"
    assert pack["schema_version"] == "1.0"
    assert pack["certification_claim"] is False
    assert isinstance(pack["rules"], tuple) and len(pack["rules"]) >= 12
    cats = {r["category"] for r in pack["rules"]}
    assert cats == {"weak_algorithm", "crypto_agility", "pqc_readiness"}
    for r in pack["rules"]:
        assert r["rule_id"] == f"securityexpert.crypto.cp-pan::{r['control_id']}"
        assert r["evidence_fields"]
        if r["category"] == "weak_algorithm":
            assert r["framework_refs"]
    assert crypto_rule_pack_summary()["rule_count"] == len(pack["rules"])


# --- AC-2: weak evidence -> FINDING; strong -> PASS; absent -> INSUFFICIENT

def test_ac2_weak_config_produces_findings(tmp_path):
    payload = build_crypto_posture(_config_result(tmp_path, _WEAK_XML), repository_root=tmp_path)
    assert payload["available"] is True
    assert payload["subjects"][0]["vendor_key"] == "palo_alto"
    for cid in ("ike_encryption_weak", "ike_integrity_weak", "ike_dh_group_weak",
                "ikev1_aggressive_mode", "ipsec_esp_encryption_weak", "ipsec_integrity_weak",
                "ipsec_pfs_absent_or_weak", "tls_min_version_below_1_2",
                "certificate_expired_or_near_expiry"):
        assert _finding_status(payload, cid) == "FINDING", cid
    assert payload["subjects"][0]["status"] == "FINDING"
    # single-proposal profile -> INFORMATIONAL, not FINDING
    assert _finding_status(payload, "single_ike_proposal") == "INFORMATIONAL"


def test_ac2_strong_config_has_no_findings(tmp_path):
    payload = build_crypto_posture(_config_result(tmp_path, _STRONG_XML), repository_root=tmp_path)
    statuses = {r["control_id"]: r["status"] for s in payload["subjects"] for r in s["findings"]}
    for cid in ("ike_encryption_weak", "ike_integrity_weak", "ike_dh_group_weak",
                "ikev1_aggressive_mode", "ipsec_esp_encryption_weak", "ipsec_integrity_weak",
                "ipsec_pfs_absent_or_weak", "tls_min_version_below_1_2",
                "certificate_expired_or_near_expiry"):
        assert statuses[cid] in ("PASS", "INFORMATIONAL"), (cid, statuses[cid])
    assert all(r["status"] != "FINDING" for s in payload["subjects"] for r in s["findings"])


def test_ac2_absent_sections_never_infer_pass(tmp_path):
    payload = build_crypto_posture(_config_result(tmp_path, "<config></config>"), repository_root=tmp_path)
    for r in payload["subjects"][0]["findings"]:
        assert r["status"] in ("INSUFFICIENT_EVIDENCE", "INFORMATIONAL", "NOT_APPLICABLE")
        assert r["status"] != "PASS"


# --- AC-3: evidence basis; no 'negotiated' produced -------------------

def test_ac3_every_finding_has_basis_and_no_negotiated(tmp_path):
    payload = build_crypto_posture(_config_result(tmp_path, _WEAK_XML), repository_root=tmp_path)
    bases = [r["evidence_basis"] for s in payload["subjects"] for r in s["findings"]]
    assert bases  # non-empty
    # 'negotiated' is a declared future layer, never a produced finding basis.
    assert set(bases) <= {"configured", "inferred", "insufficient"}
    # it does appear once, as documentation, in the confidence model.
    assert "negotiated" in payload["evidence_confidence_model"]


# --- AC-4: no key material / cert body / real identity ---------------

def test_ac4_no_secret_or_key_material_in_payload(tmp_path):
    payload = build_crypto_posture(_config_result(tmp_path, _WEAK_XML), repository_root=tmp_path)
    lowered = json.dumps(payload).lower()
    for token in ("begin certificate", "begin rsa", "private-key", "pre-shared", "public-key", "-----begin"):
        assert token not in lowered
    assert payload["privacy"]["contains_key_material"] is False
    assert payload["privacy"]["contains_certificate_content"] is False
    assert payload["rule_pack"]["certification_claim"] is False


# --- AC-5 / AC-6: additive + unavailable path ------------------------

def test_ac5_template_wires_crypto_placeholder():
    from pathlib import Path
    tpl = (Path(__file__).resolve().parents[1] / "templates" / "index.html").read_text(encoding="utf-8")
    assert "cryptoUiData: __CRYPTO_JSON_PLACEHOLDER__," in tpl
    assert 'id="cryptoPostureCard"' in tpl


def test_ac6_unavailable_path_is_explicit():
    payload = build_crypto_posture(None)
    assert payload["available"] is False
    assert payload["subjects"] == []
    assert payload["rule_pack"]["pack_id"] == "securityexpert.crypto.cp-pan"
    assert payload["pqc"]["status"] == "INFORMATIONAL"


def test_ac6_pqc_capability_is_informational_only(tmp_path):
    payload = build_crypto_posture(_config_result(tmp_path, _STRONG_XML, sw="11.2.0"), repository_root=tmp_path)
    pqc_rows = [r for s in payload["subjects"] for r in s["findings"]
               if r["control_id"] == "pqc_hybrid_kex_platform_capability"]
    assert pqc_rows and all(r["status"] == "INFORMATIONAL" for r in pqc_rows)
    assert pqc_rows[0]["hybrid_key_exchange_capable"] in ("LIKELY", "NO", "UNKNOWN")
