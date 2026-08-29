# SecurityExpert — Current State

Hot-path state only. Historical build detail lives in
`project/build_history.json` (structured index) and `docs/history/` (archived
agreements and validation reports). `docs/history/INDEX.md` is the one-line
timeline.

- **Authoritative checkpoint:** 2026-08-29
- **Product baseline:** `0.7.4 — framework_mappings: Requirement-Level Coverage` — AUTOMATED_VALIDATED (0.7.x VERIFY track)
- **Engineering baseline:** `DEV.1` complete; `DEV.2.1` (non-interactive runtime config) — AUTOMATED_VALIDATED
- **Product evidence baseline:** `0.6.1B.1.2` interactive Check Point configuration
  collection is REAL-ENVIRONMENT VALIDATED.

---

## Active build

`0.7.4 — framework_mappings: Requirement-Level Coverage` — **AUTOMATED_VALIDATED** (2026-08-29)
Contract: `docs/history/phase/0_7_4_FRAMEWORK_REQUIREMENTS.md` (§10 impl record)
Design: `docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md` §9. Additive; no
server; no new control/collector.

Models the **framework side**: a framework is now a list of *requirements*, each
with its own coverage.

- **`utils/framework_catalog.py`** (new) — `FRAMEWORK_CATALOG_VERSION "0.7.4"`.
  CIS / PCI-DSS / BDDK, each an authored requirement list covering every
  structured control `frameworks[].reference` **plus** a curated gap set; our own
  one-line titles (**no verbatim benchmark text**); process-only requirements
  carry `applies:false`. `normalize_ref` is the join key.
- **`utils/compliance_posture.py`** — `_compliance_overview.by_framework[name]`
  gains `version` / `profile` + `requirements: [{id, section, title,
  control_ids, applicable, monitored, aligned, finding, unknown, coverage,
  posture}]`, `requirement_counts`, `unmapped_control_refs` (drift guard).
  `coverage` (`COVERED`/`PARTIALLY_COVERED`/`UNCOVERED`/`NOT_APPLICABLE`) =
  monitoring completeness over *applicable* mapped controls; `posture`
  (`ALIGNED`/`FINDING`/`UNKNOWN`) is orthogonal. Catalog **and** `x_` user
  checks join by `normalize_ref`. Top-level `framework_catalog_version`.
- **UI** (`static/app.js` + `static/style.css`) — each framework readiness card
  gets a version line, a 4-segment requirement mini-bar, and a `Requirements (N)`
  expand → per-requirement rows with coverage + posture pills and mapped control
  ids (reuses the 0.7.2 explain-toggle listener + framework filter chips).

`COMPLIANCE_SCHEMA_VERSION` unchanged; payload additive; framework-level
percentages unchanged.

Evidence (2026-08-29):

```
py -m pytest -q -n auto:    523 passed, 3 skipped, 0 failed (Python 3.12)
                            (516 baseline → +7; tests/test_phase0_7_4_*)
scripts/render_sample.py:   exit 0, 0 placeholders
repository privacy gate:    PASS / 0 on a clean tree — no device identity, no
                            verbatim benchmark text, no certification claim
```

**Deferred:** a signed / user-authored framework pack (custom frameworks + a UI
mapping editor) — `DEPLOY.1A`-gated, same class as the assignment editor and the
CE.3 check editor.

**Product trajectory (owner, 2026-08-29):** the end-state is a **write-capable
device administration platform**; read-only now is a staging phase. Every
VERIFY-plane design must keep a future enforce/remediate capability additive.

Recent predecessors (all AUTOMATED_VALIDATED 2026-08-29):

- `0.7.3 — CE.1: User-Authored Compliance Check Engine` (+ crypto-source wire
  + `unified.interfaces` / `unified.routes` wire) —
  `docs/history/phase/0_7_3_COMPLIANCE_CHECK_ENGINE.md` (§11, §12); design
  `docs/design/COMPLIANCE_CHECK_ENGINE.md` (D1–D16 resolved; CE.2/CE.3/CE.4
  gated). A check is *data* over already-collected evidence; fail-closed pack;
  safe selector grammar + 14 operators; `advisory` mode; `remediation` reserved.
  Fast-follow #2 (2026-08-29): `build_compliance_posture(..., unified_inventory)`
  joins each subject to its merged-inventory row(s) by normalised device
  identity, vendor-scoped; the interface/route collections are load-restricted to
  presence/count operators and render a count-only `observed` — no network
  identity in the payload.
- `0.7.2 — Compliance Follow-ups` — `docs/history/phase/0_7_2_COMPLIANCE_FOLLOWUPS.md`.
  `password_policy` / `banner` (presence only) / `services` projection-extension
  sections (no new collector) + 6 enrichment controls; framework filter chips +
  inline Explain panel. `CURRENT_CONFIG_SCHEMA_VERSION → "0.7.2"` (PAN).
- `0.7.1b — Compliance Assignment, Waivers & Coverage Roll-up` —
  `docs/history/phase/0_7_1_COMPLIANCE_ASSIGNMENT.md`. File-based per-device
  control assignment + waivers, +8 enrichment controls, additive
  `compliance_overview` roll-up + `assignment_policy`, Overview card +
  coverage / framework-readiness band.
- `0.7.1a — Compliance Control Catalog & Framework Grouping` — the ten controls
  moved into `utils/compliance_catalog.py` verbatim with `severity`, `rationale`
  and real per-framework CIS / PCI-DSS / BDDK membership.
- `0.7.0 — Cryptographic Posture, Crypto-Agility & PQC Readiness` —
  `docs/history/phase/0_7_x_CRYPTO_AGILITY_PQC.md`. IKE/IPsec/TLS/cert facts from
  the already-stored PAN XML → static crypto rule pack, additive
  `build_crypto_posture` payload, one Compliance card. No new collector.

Full timeline: `project/build_history.json` / `docs/history/INDEX.md`.

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
529 passed / 3 skipped / 0 failed (Python 3.12)
Repository privacy gate: PASS / 0 on a clean checkout. Locally it flags the
gitignored `data/` + `logs/` + `data/.support_hmac.key` that a test run
creates — delete them before running the gate.
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
