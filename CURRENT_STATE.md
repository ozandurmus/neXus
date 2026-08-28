# SecurityExpert — Current State

Hot-path state only. Historical build detail lives in
`project/build_history.json` (structured index) and `docs/history/` (archived
agreements and validation reports). `docs/history/INDEX.md` is the one-line
timeline.

- **Authoritative checkpoint:** 2026-08-29
- **Product baseline:** `0.7.1a — Compliance Control Catalog & Framework Grouping` — AUTOMATED_VALIDATED (0.7.x VERIFY track; follows `0.7.0` crypto posture)
- **Engineering baseline:** `DEV.1` complete; `DEV.2.1` (non-interactive runtime config) — AUTOMATED_VALIDATED
- **Product evidence baseline:** `0.6.1B.1.2` interactive Check Point configuration
  collection is REAL-ENVIRONMENT VALIDATED.

---

## Active build

`0.7.1a — Compliance Control Catalog & Framework Grouping` — **AUTOMATED_VALIDATED** (2026-08-29)
Contract: `docs/builds/0_7_1_COMPLIANCE_ASSIGNMENT.md` (§ Build split, § 8 impl record)

Frozen scope: the ten deterministic compliance controls move into a versioned
declarative catalog (`utils/compliance_catalog.py`, `CATALOG_VERSION = "0.7.1a"`)
*verbatim* — same ids, areas, `evidence_fields`, evaluators, outcomes — with
`severity` (5-level, weighted), `rationale` and real per-framework
`frameworks` (CIS / PCI-DSS / BDDK membership + reference) added.
`compliance_rulepack.BASELINE_CONTROLS` is now a derived 5-key view; the 0.6.6B
rule pack and its frozen `rule_count == 10` tests are untouched.
`compliance_posture._control` emits additive `severity` / `rationale` /
`frameworks`; the `app.js` control card shows a severity badge + real per-framework
references. **Purely additive to the compliance payload** — no engine, collector,
network, CAS or UI-module change.

Evidence (2026-08-29):

```
py -m pytest -q:            464 passed, 3 skipped, 0 failed (Python 3.12)
--render-only:              PASS (0 placeholders left)
repository privacy gate:    PASS / 0 findings
```

Next: `0.7.1b` — file-based assignment (`data/state/control_assignments.json`) +
waivers + `compliance_overview` roll-up + Overview card + Compliance workbench
spine + ~12 enrichment controls + `password_policy` projection. Contract already
written (`docs/builds/0_7_1_COMPLIANCE_ASSIGNMENT.md` §2c–2e); for review before
implementation.

Previous: `0.7.0 — Cryptographic Posture, Crypto-Agility & PQC Readiness` —
AUTOMATED_VALIDATED (2026-08-29), `docs/history/phase/0_7_x_CRYPTO_AGILITY_PQC.md`.

Frozen scope: normalize IKE/IPsec/TLS/certificate facts from the **already-stored**
PAN `effective-running` XML (CP best-effort from the sanitized projection) — no
new collector — and evaluate them through a static versioned crypto rule pack
(`utils/crypto_rulepack.py`, `securityexpert.crypto.cp-pan @ 0.7.0`; categories
`weak_algorithm` / `crypto_agility` / `pqc_readiness`). Additive
`build_crypto_posture` payload with a per-finding `evidence_basis` (`configured`
/ `insufficient`; `negotiated` is a declared future runtime layer), rendered as
one card in the existing Compliance module. Never key material, PSK or
certificate body. No new collectors, vendors, network, CAS or UI module.

Evidence (2026-08-29):

```
py -m pytest -q:            458 passed, 3 skipped, 0 failed (Python 3.12)
--render-only:              PASS (cryptoUiData embeds in HTML)
repository privacy gate:    PASS / 0 findings
```

Dynamic/signed packs, scoring and live `negotiated` crypto evidence remain later
0.7.x / 0.8.x work.

---

## Next builds (frozen contracts)

- `DEPLOY.1 — Ubuntu + Docker Server Migration & Git Repository Foundation` —
  **CONTRACT_FROZEN** (2026-08-27). No runtime behavior change before server
  arrival (~1 week). Mandatory gates on arrival: OIDC viewer boundary, evidence
  egress policy, CP strict host-key R2 validation, PAN TLS corporate-CA
  validation.
  Handover: `docs/history/handover/DEPLOY_1_CONTRACT_FREEZE_HANDOVER_2026_08_27.md`
- After the engineering-readiness checkpoint, product architecture proceeds
  toward `0.6.1C` follow-ups already validated in the 0.6.x track.
- `OP.x — Controlled Failover` (new track, OPERATE theme): design frozen in
  `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md`. Write-free parts (OP.0 HA
  readiness assessment + SCC dashboard, OP.1 dry-run plan compiler) are
  buildable post-`DEPLOY.1`; OP.2 controlled execution is hard-gated (see the
  doc's §10 and `roadmap_notes`).

---

## Standing priorities and blockers

1. **CP device-interaction-safety audit (P0)** — must complete before any
   recurring scheduling or concurrency increase. The admission coordinator
   concurrency budget stays at 1 per vendor until this closes.
2. Do **not** increase recurring polling frequency or concurrency before that
   audit closes.
3. DEPLOY.1 gates are blocked on server availability (external, ~1 week).
4. Corporate Git push/merge remains **human-controlled**.

## Known xfails

- VSX network canonicalization.
- PAN default-route classification.

(Both were converted to passing regressions in 0.6.6A; reconfirm on the next
full regression run.)

## Automated test baseline

```
464 passed / 3 skipped / 0 failed (Python 3.12)
Repository privacy gate: 0 findings / PASS
```

Run one-shot and read from file (see `docs/AI_DEVELOPMENT_PROTOCOL.md`):
`py -m pytest -q > pytest_result.log 2>&1`

---

## Engineering foundation completed before DEV.1

`DEV.0` repository readiness is complete except the intentionally deferred
pre-server storage checkpoint:

- `DEV.0.1` runtime management endpoint decoupling — DONE / real-env validated.
- `DEV.0.2` repository sanitization — DONE.
- `DEV.0.3A/B/B.1` runtime path foundation + artifact migration + direct-SSH
  closure — DONE / real-env validated.
- `DEV.0.3C` History/CAS runtime boundary — DEFERRED / pre-server; not a
  Corporate Git blocker.
- `DEV.0.4 / 0.4.1` local repository privacy gate + runtime inventory exclusion
  policy — DONE; clean candidate, 0 findings.
- `DEV.0.5A/B/B.1/B.2` authentication boundary + canonical config + repository-wide
  DLP closure — DONE.

## Copilot audit follow-up debt

- Environment authentication overrides remain explicit operational compatibility
  paths; do not remove implicitly.
- PAN authentication transport behavior is not fully converged across old/new
  paths; track under explicit security hardening.
- Production CP SSH host-key trust and PAN TLS corporate-CA trust remain
  production gates.
