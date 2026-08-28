# 0.7.x — Cryptographic Posture, Crypto-Agility & PQC Readiness (contract)

**Status:** AUTOMATED_VALIDATED (2026-08-29) — `py -m pytest -q` **458 passed,
3 skipped, 0 failed**; `--render-only` PASS (`cryptoUiData` embeds); repository
privacy gate PASS / 0. Real-environment validation not required (no
network-facing behavior). Human `main` merge blocked pending review.
**Movement:** ARCHITECTURE → IMPLEMENTATION · **Opens the `0.7.x` VERIFY track.**
**Backlog / feature:** `crypto_agility_pqc` (P1)

## Implementation record (2026-08-29)

- `utils/crypto_rulepack.py` (new) — `DEFAULT_CRYPTO_RULE_PACK`
  (`securityexpert.crypto.cp-pan @ 0.7.0`, schema `1.0`, `certification_claim:
  False`, 14 rules in `weak_algorithm` / `crypto_agility` / `pqc_readiness`),
  `crypto_rule_pack_summary()`.
- `utils/crypto_facts.py` (new) — `extract_pan_crypto_facts(xml_bytes)` (IKE /
  IPsec crypto profiles, IKE gateways, SSL/TLS service profiles, certificate
  metadata: algorithm + validity only) and `extract_cp_crypto_facts(cp_device)`
  (best-effort web-TLS / SSH from the sanitized projection; `management_plane_gap`
  flag). Every fact carries `evidence_basis`.
- `utils/crypto_posture.py` (new) — `build_crypto_posture(config_result,
  checkpoint_config_result, *, repository_root, configuration_ui)`: reuses
  `current_config_projection._artifact_bytes` to re-read the stored PAN XML,
  runs the rule evaluators, rolls up subject status, emits the §4 payload with
  `fleet.status_counts` / `category_counts` and the INFORMATIONAL `pqc` block.
- `utils/html_export.py` — `build_crypto_posture` call + `__CRYPTO_JSON_PLACEHOLDER__`
  replace. `templates/index.html` — `const cryptoUiData` + `#cryptoPostureCard`
  in the Compliance module. `static/app.js` — `renderCryptoPostureCard()` called
  from `renderComplianceFleetView`.
- `tests/test_phase0_7_0_crypto_agility.py` (new) — 9 tests, AC-1…AC-6, incl.
  weak-config → FINDING, strong-config → no FINDING, absent-section → never
  inferred PASS, no key material in the payload.
- State: `project/*`, `CURRENT_STATE.md` → `0.7.0` automated_validated;
  `0.7.x` track → in_progress.

Decisions 1–4 accepted as recommended (single-proposal = INFORMATIONAL; cert
near-expiry 30d FINDING / 90d INFORMATIONAL; card-in-Compliance-module not a new
module; CP best-effort SSH/web-TLS only).

---

Active build contract; moves to `docs/history/phase/` on close.

---

## PROJE ÖZETİ (Türkçe — teknik olmayan özet)

- **Proje nedir:** SecurityExpert, Check Point ve Palo Alto güvenlik
  duvarlarının yapılandırmasını salt-okunur toplayıp tek bir panelde durum,
  uyum ve kanıt olarak gösteren bir araç.
- **Bu görev nedir:** Zaten topladığımız yapılandırma kanıtından **şifreleme
  (kripto) bilgilerini** ayıklamak: VPN'lerde kullanılan IKE/IPsec algoritma ve
  anahtar grupları, yönetim arayüzü TLS sürümü, sertifika imza türü ve süresi.
  Sonra bunları **sürümlenmiş bir kural paketiyle** değerlendirip zayıf/eski
  algoritmaları ve kuantum-sonrası (PQC) hazırlık durumunu **bulgu** olarak
  çıkarmak.
- **Neden / ne kazanırız:** Bir denetçi veya güvenlik ekibi "hangi cihazımda
  hâlâ 3DES/SHA-1/DH-2 var, hangi sertifikam yakında doluyor, TLS 1.0 açık mı"
  sorusunu bugün elle, cihaz cihaz araştırıyor. Bu iş onu tek bakışta,
  kanıta-dayalı ve filtrelenebilir hâle getirir. Yeni cihaz bağlamayı veya
  yeni komut çalıştırmayı gerektirmez — mevcut kanıtı işe çevirir.
- **Tür:** Yeni özellik (0.7.x VERIFY hattının ilk yapı taşı; küçük ve sınırlı
  bir "temel" sürüm — büyük özellik değil).
- **Gelecekte ne çözer / neyi açar:** PQC (kuantum-sonrası kripto) geçiş
  planlamasının, NIST/PCI-DSS kripto eşlemelerinin ve ileride "görüşülen"
  (canlı IKE oturumundan okunan) kanıt katmanının temelini kurar.

---

## 1. What exists (frozen baseline)

- The PAN `effective-running` config XML is collected and stored immutably in
  the content-addressed store (`data/artifacts/config/sha256/`).
  `configuration/current_config_projection._artifact_bytes(base_dir,
  row["direct"]["effective"])` resolves the stored bytes.
- The `current_configuration` projection (`_scalar_rows` / `_section_for`) walks
  **only `/deviceconfig/system/*`** — sections `system, dns, ntp, management,
  telemetry, high_availability, network_summary`. **No crypto section**, and
  `certificate` is in `SENSITIVE_TOKENS` (redacted). IKE/IPsec/TLS/cert config
  lives under `/network/ike/...`, `/shared/ssl-decrypt`,
  `/shared/certificate`, `/…/ssl-tls-service-profile` — none projected today.
- CP `show configuration` is redacted, memory-only during collection; a
  sanitized artifact + a canonical fingerprint go to CAS. The browser sees
  whatever `config_ui` projects for CP — SSH / web-UI settings where present;
  **CP VPN-community crypto is a Management-plane concept and is not in the
  gateway config**.
- 0.6.6B established the rule-pack model: `utils/compliance_rulepack.py`
  (`BASELINE_CONTROLS` + `DEFAULT_RULE_PACK` + `rule_pack_summary`) and
  `utils/compliance_posture.build_compliance_posture(...)` → additive
  `rule_pack` payload block. `html_export` wires five JSON placeholders.

**Therefore:** crypto facts exist in *already-collected* evidence but are not
projected. Extracting them is a **new projection over existing evidence, not a
new collector** — which satisfies the feature's "no new vendors or collectors".

## 2. Objective

Turn the stored CP/PAN configuration evidence into normalized crypto facts and,
via a versioned crypto rule pack, into weak-algorithm / crypto-agility / PQC
findings — as an additive, privacy-safe payload plane, distinct from the
0.6.6B compliance engine.

## 3. Scope

### In

- **`utils/crypto_facts.py`** — parse normalized crypto facts:
  - **PAN** (from the stored `effective-running` XML, read the same way
    `build_pan_current_configuration` does): IKE crypto profiles
    (`dh-group`, `encryption[]`, `hash[]`, `lifetime`), IPsec crypto profiles
    (`esp`/`ah`, `encryption[]`, `authentication[]`, `dh-group` / PFS,
    `lifetime`), IKE gateways (`protocol-version` v1/v2, `exchange-mode`
    aggressive/main), SSL/TLS service profiles (`min-version`, `max-version`),
    certificate **metadata only** (`algorithm` RSA/ECDSA, key size,
    `signature-algorithm` / `hash`, `not-valid-after`). **Never** key material,
    PSK, certificate body.
  - **CP** (best-effort from the sanitized CP config projection): SSH KEX /
    ciphers / MACs and web-UI TLS min version *where present*; everything else
    (VPN-community crypto) → `PLANNED_EVIDENCE_GAP`.
  - Each fact tagged `evidence_basis ∈ {configured, inferred, insufficient}`.
    `negotiated` (from a live IKE SA) is a declared **future runtime-evidence
    gap** — not produced here.
- **`utils/crypto_rulepack.py`** — a static versioned pack in the 0.6.6B model:
  `pack_id "securityexpert.crypto.cp-pan"`, `pack_version "0.7.0"`,
  `schema_version "1.0"`, `certification_claim: False`, rules grouped by
  `category ∈ {weak_algorithm, crypto_agility, pqc_readiness}`. Each rule:
  `rule_id`, `control_id`, `category`, `applicability`, `evidence_fields`,
  `framework_refs` (NIST SP 800-131A / PCI-DSS 4.0 — evidence-area only),
  `evaluator` key.
- **`utils/crypto_posture.py`** — `build_crypto_posture(config_result,
  checkpoint_config_result, *, repository_root)` → payload (§4). Deterministic,
  offline, side-effect free.
- **Wiring:** `html_export` calls `build_crypto_posture` and replaces a new
  `__CRYPTO_JSON_PLACEHOLDER__`; `templates/index.html` gains
  `const cryptoUiData = __CRYPTO_JSON_PLACEHOLDER__;` (line ~432);
  `static/app.js` reads `cryptoUiData` for a small crypto card **inside the
  existing Compliance module** (no new module).
- Synthetic-fixture tests (§6).

### Out

- New collectors / commands / network / CAS / vendors.
- Live / **negotiated** crypto evidence (needs `show vpn ike-sa` etc. + a
  server) — declared as the next evidence layer, not built.
- CP VPN-community / Management-plane crypto (needs the CP Management API,
  a separate future build).
- A dedicated Crypto UI **module** — payload + one compliance-module card in
  v1; a full view is a follow-up (mirrors the 0.6.0A4.3 pattern of shipping the
  payload before the UI).
- Any scoring, certification/attestation claim, or remediation.
- Key material, PSK, certificate content in any payload, ever.

## 4. Payload — `build_crypto_posture(...)`

```json
{
  "schema_version": "0.7.0",
  "available": true,
  "classification": "evidence_backed_crypto_posture",
  "disclaimer": "Evidence-backed crypto-area evaluation only. Not a certification or complete cryptographic assessment.",
  "rule_pack": { "pack_id": "...", "pack_version": "0.7.0", "schema_version": "1.0",
                 "certification_claim": false, "disclaimer": "...", "rule_count": <n> },
  "evidence_confidence_model": {
    "configured": "read from stored device configuration",
    "negotiated": "from a live security association — future runtime-evidence layer, not in this build",
    "inferred":   "derived from platform/OS facts",
    "insufficient": "the relevant configuration section was not observed"
  },
  "subjects": [
    { "subject_id": "pan-001", "vendor_key": "palo_alto", "availability": "AVAILABLE",
      "facts": { "ike_crypto_profiles": [ { "name": "...", "dh_group": "group2",
                   "encryption": ["3des"], "hash": ["sha1"], "lifetime": "8h" } ],
                 "ipsec_crypto_profiles": [ ... ], "ike_gateways": [ { "protocol_version": "ikev1",
                   "exchange_mode": "aggressive" } ], "tls_service_profiles": [ { "min_version": "tls1-0" } ],
                 "certificates": [ { "algorithm": "RSA", "key_size": 1024,
                   "signature_hash": "sha1", "not_valid_after": "2026-09-10" } ] },
      "findings": [ { "rule_id": "...", "control_id": "ike_dh_group_weak", "category": "weak_algorithm",
                   "status": "FINDING", "evidence_basis": "configured",
                   "summary": "IKE crypto profile uses DH group 2 (1024-bit MODP).",
                   "evidence_fields": ["network.ike.crypto-profiles...dh-group"],
                   "framework_refs": ["NIST SP 800-131A", "PCI-DSS 4.0"] } ],
      "status": "FINDING" }
  ],
  "fleet": { "subjects": <n>, "evaluated_subjects": <n>,
             "status_counts": { "PASS": 0, "FINDING": 0, "UNKNOWN": 0, "NOT_APPLICABLE": 0, "PLANNED": 0 },
             "category_counts": { "weak_algorithm": {...}, "crypto_agility": {...}, "pqc_readiness": {...} } },
  "pqc": { "status": "INFORMATIONAL",
           "platform_capability": [ { "vendor_key": "palo_alto", "sw_version": "...",
             "hybrid_key_exchange_capable": "UNKNOWN", "note": "capability, not configured posture" } ] },
  "privacy": { "contains_secrets": false, "contains_key_material": false,
               "contains_certificate_content": false, "contains_real_identity": false }
}
```
Non-`AVAILABLE` / no-config path returns `available: false` with the same shape
(empty subjects, `rule_pack` present).

## 5. Rules (v1)

**weak_algorithm** — `ike_encryption_weak` (des/3des/rc4/null), `ike_integrity_weak`
(md5/sha1), `ike_dh_group_weak` (groups 1/2/5), `ikev1_aggressive_mode`,
`ipsec_esp_encryption_weak`, `ipsec_integrity_weak`, `ipsec_pfs_absent_or_weak`,
`tls_min_version_below_1_2`, `certificate_signature_weak` (md5/sha1WithRSA),
`certificate_key_size_weak` (RSA < 2048 / DSA any), `certificate_expired_or_near_expiry`.

**crypto_agility** — `single_ike_proposal` / `single_ipsec_proposal` (brittle: one
proposal only), `ike_lifetime_out_of_range` (too long / too short).

**pqc_readiness** — `pqc_hybrid_kex_platform_capability` (INFORMATIONAL: does the
OS/JHF version support hybrid post-quantum key exchange).

DH-group scale: `{1,2,5}` = weak → FINDING; group 14 (2048 MODP) = acceptable,
INFORMATIONAL "plan migration"; ECP groups 19/20/21 and 15/16 = PASS.

## 6. Acceptance & validation

| AC | Covered by |
| --- | --- |
| AC-1 | Static crypto pack: immutable `pack_id` / version, `certification_claim is False`, rules grouped by category with `framework_refs`. |
| AC-2 | Synthetic PAN XML with mixed strong+weak IKE/IPsec/TLS/cert → facts normalized to the §4 shape; weak → `FINDING`, strong → `PASS`, absent section → `INSUFFICIENT`/`PLANNED` (never inferred PASS). |
| AC-3 | Every finding carries `evidence_basis`; `negotiated` never appears as a produced value. |
| AC-4 | `json.dumps(payload)` contains no key material, PSK, PEM/certificate body, or real device/network identity; `privacy` flags all false/true as declared. |
| AC-5 | Additive: `build_compliance_posture` output and every existing payload/consumer unchanged; `cryptoUiData` is the only new template symbol. |
| AC-6 | `--render-only` embeds `cryptoUiData` and renders with no error; the Compliance module's crypto card degrades cleanly when `available: false`. |
| AC-7 | Targeted crypto tests + compliance/UI regression + `--repository-privacy-check` pass. |

**Validation path:** synthetic fixtures + `py -m pytest` + `--render-only`. No
network, no server, no real-environment gate (no network-facing behavior).

## 7. Files

- `utils/crypto_facts.py`, `utils/crypto_rulepack.py`, `utils/crypto_posture.py` — new.
- `utils/html_export.py` — `build_crypto_posture` call + one `.replace`.
- `templates/index.html` — one `const cryptoUiData = ...` line.
- `static/app.js` — read `cryptoUiData`; one crypto card in the Compliance module.
- `tests/test_phase0_7_0_crypto_agility.py` — new (AC-1…AC-6).
- `project/*`, `CURRENT_STATE.md` — state on close.

## 8. Definition of Done

AC-1…AC-7 pass; `--render-only` healthy; diff review confirms no
collector / CAS / scheduler / storage / network change; `crypto_agility_pqc`
advanced to `automated_validated` with the four criteria (`crypto_facts`,
`rule_packs`, `evidence_confidence`, `pqc_capability`) mapped. No real-env gate.

## 9. Open decisions (my recommendation)

1. **`single_ike_proposal` / `single_ipsec_proposal`** — `FINDING` or
   `INFORMATIONAL` in v1? *Recommend `INFORMATIONAL`* — one proposal is brittle,
   not wrong; escalate to `FINDING` after calibration against real configs
   (mirrors the OP.0 `DEGRADED` decision).
2. **Near-expiry certificate window** — 30 vs 90 days. *Recommend 30* for
   `FINDING`, plus an `INFORMATIONAL` at 90.
3. **Crypto UI** — payload + one card in the Compliance module now, dedicated
   module later? *Recommend yes (card only in v1)* — smallest blast radius,
   matches the 0.6.x "payload before UI" pattern.
4. **CP scope** — attempt SSH/web-TLS crypto facts from the sanitized CP
   projection in v1, or CP entirely `PLANNED_EVIDENCE_GAP` until a CP Management
   crypto build? *Recommend best-effort SSH/web-TLS only*; everything else an
   explicit gap.
