# SecurityExpert — Current State

Hot-path state only. Historical build detail lives in
`project/build_history.json` (structured index) and `docs/history/` (archived
agreements and validation reports). `docs/history/INDEX.md` is the one-line
timeline.

- **Authoritative checkpoint:** 2026-08-27
- **Product baseline:** `0.6.6A — CP/PAN Parser Correctness Hardening` — AUTOMATED_VALIDATED
- **Engineering baseline:** `DEV.1 — Corporate Git Foundation` — CORPORATE GIT BASELINE ACTIVE
- **Product evidence baseline:** `0.6.1B.1.2` interactive Check Point configuration
  collection is REAL-ENVIRONMENT VALIDATED.

---

## Active build

`0.6.6A — CP/PAN Parser Correctness Hardening` — **AUTOMATED_VALIDATED** (2026-08-27)
Agreement: `docs/history/phase/PHASE0_6_6A_PARSER_CORRECTNESS_HARDENING.md`

Frozen scope: correct only the VSX canonical network CIDR and the PAN
default-route precedence defects represented by the two strict parser `xfail`
tests. No new collector command, network access, retry/timeout, scheduler,
polling, concurrency, CAS, storage, UI or alignment behavior.

Evidence (2026-08-27):

```
Parser characterization suites:  7 passed
Impacted PAN regression suites: 17 passed
```

The two strict characterization xfails were converted to passing regressions
(VSX canonical network CIDR; PAN default-route classification precedence).

---

## Next builds (frozen contracts)

- `0.6.6B — Compliance Rule-Pack Transition Foundation` — **PLANNED**, next
  product build. Static versioned rule-pack boundary around the existing ten
  deterministic CP/PAN compliance controls. Offline, evidence-bounded; no raw
  configuration, secret, real identity or certification-claim semantics.
  Agreement: `docs/history/phase/PHASE0_6_6B_COMPLIANCE_RULE_PACK_TRANSITION.md`
- `DEPLOY.1 — Ubuntu + Docker Server Migration & Git Repository Foundation` —
  **CONTRACT_FROZEN** (2026-08-27). No runtime behavior change before server
  arrival (~1 week). Mandatory gates on arrival: OIDC viewer boundary, evidence
  egress policy, CP strict host-key R2 validation, PAN TLS corporate-CA
  validation.
  Handover: `docs/history/handover/DEPLOY_1_CONTRACT_FREEZE_HANDOVER_2026_08_27.md`
- After the engineering-readiness checkpoint, product architecture proceeds
  toward `0.6.1C` follow-ups already validated in the 0.6.x track.

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
227 passed / 2 known xfail
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
