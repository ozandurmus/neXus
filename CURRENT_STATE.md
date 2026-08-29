# SecurityExpert — Current State

Hot-path state only. Historical build detail lives in
`project/build_history.json` (structured index) and `docs/history/` (archived
agreements and validation reports). `docs/history/INDEX.md` is the one-line
timeline.

- **Authoritative checkpoint:** 2026-08-29
- **Product baseline:** `0.7.3 — CE.1: User-Authored Compliance Check Engine (data-driven, evidence-only)` — AUTOMATED_VALIDATED (0.7.x VERIFY track; first phase of `compliance_check_engine`)
- **Engineering baseline:** `DEV.1` complete; `DEV.2.1` (non-interactive runtime config) — AUTOMATED_VALIDATED
- **Product evidence baseline:** `0.6.1B.1.2` interactive Check Point configuration
  collection is REAL-ENVIRONMENT VALIDATED.

---

## Active build

`0.7.3 — CE.1: User-Authored Compliance Check Engine` — **AUTOMATED_VALIDATED** (2026-08-29)
Contract: `docs/history/phase/0_7_3_COMPLIANCE_CHECK_ENGINE.md` (§10 impl record)
Design: `docs/design/COMPLIANCE_CHECK_ENGINE.md` (decisions D1–D16 resolved §10;
write-capability trajectory §11; CE.4 §12). All additive; no server; no new
device command.

The BackBox/Nipper model: a check is **data** — name + evidence source +
expected pattern + verdict — evaluated over **already-collected** evidence.

- **`utils/compliance_check_pack.py`** — `load_compliance_checks(data_root) ->
  CompliancePack` from `data/state/compliance_checks.json` (schema v1,
  fail-closed; mirrors `control_assignment.py`). `x_`-prefixed check ids; a
  hand-written selector grammar `NS('.'SEGMENT)*` over
  `{current_configuration, unified, crypto_facts, alignment}` (no `eval`, no new
  dep); a fixed 14-operator assertion set; `evidence.steps[]` with
  `combine all|any`; anchored regex ≤ 512 chars behind a complexity linter +
  eval-time timeout; `mode enforced|advisory`. A `remediation` key is
  **rejected** (reserved for CE.4 / the write future).
- **`utils/compliance_check_engine.py`** — `resolve_source` / `apply_select` /
  `apply_assertion` / `evaluate_check`, all pure. A path that can't be walked →
  `None` → `on_no_evidence` for every operator (never an inferred `PASS`); a
  regex timeout → step inconclusive. `redacted_selector` blanks `[attr=value]`
  filter values.
- **`utils/compliance_posture.py`** — `_subject_user_checks` → `extended_controls`
  (`control_class:"user_check"`, `advisory`, `pack`, `check_steps`, redacted
  `evidence_fields`); `_scoring_rows` excludes `advisory` rows from subject
  status + fleet counts + `compliance_overview`; new top-level `check_packs`
  (counts + `pack_id` only). `control_assignment.load_control_assignments` gains
  `extra_known_ids` so an `include`/`exclude`/waiver can target an `x_` id.
- **UI** (`static/app.js` + `static/style.css`) — "user-defined" + "advisory"
  badges; source-pack + per-step `expected`/`observed` lines in the 0.7.2
  Explain panel (pattern `expected` shown as "redacted").

`COMPLIANCE_SCHEMA_VERSION` unchanged (`"0.6.6B"`); payload additive
(`check_packs`).

Evidence (2026-08-29):

```
py -m pytest -q -n auto:    516 passed, 3 skipped, 0 failed (Python 3.12)
                            (483 baseline → +33; new tests/test_phase0_7_3_*
                            incl. the crypto-source follow-up)
scripts/render_sample.py:   exit 0, 0 placeholders (no pack → renders as 0.7.2)
repository privacy gate:    PASS / 0 on a clean tree — no raw pattern, no raw
                            selector filter value, no device name/IP in the
                            payload; check_packs is counts + pack_id only
```

**Decisions resolved (design doc §10):** D1 extend-only (`^x_` id namespace);
D2 fixed operators + `all/any` (no expression tree); D3 anchored regex with
cap + linter + timeout; D4 all read-only namespaces
(`current_configuration` / `unified.device` / `alignment` / `crypto_facts` wired;
`unified.interfaces`/`routes` parse but resolve empty — later step); D5 multi-step;
D6 `advisory` mode excluded from the score; D7 reuse `control_assignments.json`
waivers; D8 CE.2 opt-in `--compliance-probe` only; D9 CE.3 signing deferred to
DEPLOY.1; D10 `0.7.3` = CE.1; D11–D16 engine placement / payload / grammar /
determinism / file location.

**Product trajectory (owner, 2026-08-29):** the end-state is a **write-capable
device administration platform**; read-only now is a staging phase. Every
VERIFY-plane design must keep a future enforce/remediate capability additive —
the check model reserves an (unused, validator-rejected) `remediation` block,
and CE.2's primitive registry is the single chokepoint for a future write
primitive under the OP.2 gate stack.

Deferred onward: `CE.2` curated read-only command primitives
(`compliance_check_engine_primitives`, blocked on `cp_device_interaction_safety`
P0 + the command gate); `CE.3` UI check editor + signed org packs
(`compliance_check_engine_ui`, `DEPLOY.1A`); `CE.4` remediation checks
(`compliance_remediation_checks`, hard-gated on the OP.2 bar). The 0.7.2 UI
assignment **editor** + tagged device registry stay `DEPLOY.1A`-gated
(`compliance_assignment_ui_and_registry`). Real-environment check-authoring
folds into `on_hardware_real_env_validation` (P0, laptop-blocked).

Recent predecessors (all AUTOMATED_VALIDATED 2026-08-29):

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
516 passed / 3 skipped / 0 failed (Python 3.12)
Repository privacy gate: on a clean checkout, PASS / 0 (0.7.2 adds no finding;
locally the gate reports 3 gitignored runtime artifacts + 2 pre-existing
AI_HANDOVER path lines — see the Active build evidence block)
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
