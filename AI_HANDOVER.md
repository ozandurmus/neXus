# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal.

---

## 1. Snapshot

- Date: 2026-09-07. `M8.4` — **AUTOMATED_VALIDATED**, branch
  `claude/m8-4-m6-resolver-consumption-nh0260`, created from `origin/main`
  at `ef43d59` (the merged `M8.3`). PR not yet opened.
- Build: `m8_4_m6_resolver_consumption` (`M8.4`) — `status:
  automated_validated`.
- Contract: `docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md`
  §6/§7/§8/§9 — FROZEN; **§12 is a new amendment this session added**
  (Product Owner sequencing: `M8.4` authorized ahead of `M8.3`'s
  real-environment gate; `M7` unaffected, stays blocked).
- `M8.3` is confirmed **merged** to `main` via PR #100 (commit `ef43d59`).
  Its own real-environment validation is **deferred to backlog**
  (`project/backlog.json`: `m8_3_real_environment_validation`) by explicit
  Product Owner decision — it stays `AUTOMATED_VALIDATED`, never
  `REAL_ENV_VALIDATED`/`DONE` from that deferral.

## 2. What this session did

1. **Repository transition (PO-authorized):** pushed `M8.3`'s branch,
   opened PR #100, waited for the `validate` CI check (succeeded,
   `mergeable_state=clean`), merged with a true merge commit (`ef43d59`),
   fetched and verified the resulting `origin/main`, then branched
   `claude/m8-4-m6-resolver-consumption-nh0260` from that exact main before
   any `M8.4` edit.
2. **Recorded the PO's sequencing decision first**, as a new, dated §12
   amendment to the frozen `M8` contract (mirroring the existing
   `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §20.1 amendment
   precedent) — before touching any code.
3. **Extended `console/registry_targets.py`** (§7): a known+eligible
   `config_refresh_cp` `device_id` now stops refusing with
   `IDENTITY_TRANSLATION_REQUIRED` only once a currently proven `M8.1`
   relationship exists and every registry/identity-derivation/trust/evidence
   currency condition holds — checked identically at admission
   (`console/app.py`) and pre-execution (`console/runner.py`), before any
   credential/device operation. New `RELATIONSHIP_STORE_UNAVAILABLE`
   outcome. Deliberately does **not** implement `M7`: no wiring substitutes
   a resolved `entity_id` into a collector command, so `config_refresh_cp`
   stays non-functional for a real device regardless of this build.
4. **Two small, behavior-preserving refactors**, both justified as
   necessary for `M8.4` to correctly read state `M8.3` writes, neither
   changing `M8.3`'s own observable behavior (its 19+18 tests re-verified
   green after each): moved `IDENTITY_DERIVATION_CONTRACT_VERSION` from
   `utils/first_contact_producer.py` to `utils/device_identity_relationships.py`
   (shared by writer and reader); added `utils/config_evidence.py::
   build_evidence_reference`/`resolve_evidence_reference` (the
   `producing_run_ref` format `M8.3` already produced, now one documented,
   shared, vendor-neutral contract instead of an independently-reconstructed
   guess in `console/`).
5. **Found and reported, not silently patched:** `_collect_host` (the
   shared, unmodified collector primitive) never persists a host-key
   fingerprint into CP config evidence metadata, so `M8.4`'s trust-currency
   check — fully and correctly implemented against the frozen wording —
   cannot affirmatively pass for any real `M8.3`-produced relationship
   today. Recorded as `project/backlog.json`:
   `m8_evidence_host_key_fingerprint_not_persisted`, proven by a dedicated
   test, not fixed in this movement (its own scope boundary).
6. **New `tests/test_m8_4_m6_resolver_consumption.py`** (23 tests) — see
   §4 below.
7. **Project-state update**: `project/roadmap.json` (`now` = `M8.4`
   `automated_validated`; `next` unchanged build id but `status: deferred`;
   `current_build` updated), `project/backlog.json` (two new items:
   `m8_3_real_environment_validation` deferred, `m8_evidence_host_key_
   fingerprint_not_persisted` planned), `project/build_history.json` (new
   head record), `CURRENT_STATE.md`/this file rewritten, `docs/history/
   INDEX.md` regenerated.

## 3. Exact next action

**`M8.3` real-environment validation — still Product Owner authorization
required, deferred to backlog by explicit PO decision this session, not
performed.** Exactly one bounded, read-only command remains proposed:

```
py main.py --identity-first-contact <one enrolled registry device_id>
```

`M7` ("real device-targeted Collect now") stays blocked until this runs and
produces a genuinely real-environment-validated relationship — no automated
fixture or synthetic relationship may ever satisfy that gate, and `M8.4`'s
own `AUTOMATED_VALIDATED` completion does not change this.

## 4. Test delta

New: `tests/test_m8_4_m6_resolver_consumption.py` (23 tests, no real
device/socket/credential resolution anywhere). Targeted run: 23 passed.
Affected run (`M6`/`M8.1`/`M8.2`/`M8.3`/`PCP.1`/`M4`/`CON.1`/`CON.2`/`OP.0d`/
`OP.0b` S7.5/architecture convergence/application-package): 440 passed, 1
skipped (pre-existing, unrelated). Fixed fast-PR smoke set: 14 passed.
`compileall` clean. Privacy gate: PASS, 0 findings. `git diff --check`:
clean. No `full-regression`/`workflow_dispatch` run (risk-based; one
console module extended plus two small behavior-preserving `utils`
additions/refactors, no schema/storage-engine/UI change).

## 5. Risks / notes forward

- `M7` remains blocked — its own §9 gate (a genuinely real-environment-
  validated relationship) is unamended by §12's narrow `M8.4` sequencing
  amendment, and no fixture built for `M8.4`'s own tests may ever satisfy it.
- The host-key-fingerprint-not-persisted gap (`m8_evidence_host_key_
  fingerprint_not_persisted`) means `M8.4`'s trust-currency check fails
  closed for every real relationship until `_collect_host`'s evidence-write
  path gets a small, separately-reviewed metadata addition — tracked, not
  fixed here.
- `CONTRADICTORY_EVIDENCE` detection stays deliberately unbuilt.
- Three dissents remain open, unchanged: `operator_assertion`,
  single-sourced identity evidence, deferred contradiction detection.
- No UI change; no console job type's `target_mode` changed; the operator
  console still submits only typed intent against a closed job-type
  registry, and `console/` still imports no vendor/collector module.
