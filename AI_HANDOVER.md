# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-09. `event_signal_intake` Slice 1 (relay/NXS-LOCAL-0032) —
  design + first implementation slice of `project/backlog.json`'s
  `event_signal_intake` (P1), dispatched as one bounded `IMPLEMENTATION`
  movement.
- Own worktree/branch (`feature/event-signal-intake-design-and-slice-1`),
  standing `relay#13` merge authorization.
- New code: `docs/design/EVENT_SIGNAL_INTAKE_ARCHITECTURE.md` (FROZEN, Slice
  1 only), `utils/event_signal_intake.py`, `signal_intake/` (new package,
  `app.py`), `tests/test_event_signal_intake.py`. Two small additive edits
  to existing `CON.2` code: `console/runner.py` (`ConsoleJobRunner` gained
  an optional `provenance` parameter, default unchanged) and
  `utils/coordinator_backend.py` (`Provenance.EVENT` added, filling in a
  reserved value its own comment already named).

## 2. What changed

- AC-1 gate re-verification (see the design doc's own section): collection
  coordinator and safe diff (0.6.3, PAN-scoped) confirmed EXISTS AND WORKS;
  cross-vendor timeline (0.8.x) confirmed still MISSING — Slice 1 does not
  depend on it and does not attempt to build it, per this movement's own
  scoping requirement.
- Webhook intake boundary: HMAC-SHA256 shared-secret auth
  (`SECURITYEXPERT_EVENT_SIGNAL_HMAC_SECRET`, timestamp+nonce bound into
  the signature), timestamp-window + nonce replay protection, a five-field
  strict schema allowlist, per-`(device_id, event_type)` 600s cooldown.
- Canonical identity resolution (AC-3): reuses
  `utils.device_registry.normalize_endpoint` + `DeviceRegistry` — no
  parallel identity model invented.
- Trigger (AC-4): on success, submits to the *existing*, unmodified `CON.2`
  job engine (`console.jobs.ConsoleJobStore` / `console.runner.
  ConsoleJobRunner`) using the existing `config_refresh_cp` job type. The
  intake path never calls a collector and never writes evidence — proven
  structurally (an AC-8-style import-graph probe) and behaviorally (a
  `_RecordingRunner` whose worker thread never starts, so no test in the
  suite can reach `main.main()`).
- Explicitly scoped down and stated as such (not silently left unbuilt):
  Check-Point-only trigger target (no PAN per-device config
  target-selection seam exists yet), no real Splunk/network exposure
  (`fastapi.testclient` only — no `main.py` CLI flag, no server binder),
  HMAC shared-secret instead of the feature registry's aspirational
  mTLS/OIDC wording.
- `project/backlog.json` and `project/feature_registry.json`:
  `event_signal_intake` moved `planned` → `in_progress` with a detailed
  note; four of five feature criteria marked `done` (with a `note` on each
  substitution/scope decision), `authenticated_ingress`'s mTLS/OIDC wording
  explicitly not literally satisfied — HMAC is a documented substitution.

## 3. Exact next action

1. No PO decision is blocking — this movement completed without a
   `RELAY_NOTE` stop. Line-1 (`gov_po_2_implementation`, next per
   `project/roadmap.json`) is untouched and unaffected.
2. Whenever `cross_vendor_change_timeline` (0.8.x) is eventually built, a
   later movement should decide whether/how to link a signal-triggered
   collection's resulting evidence into it — not authorized or attempted
   here.
3. Any expansion of this slice (a PAN per-device target seam, real network
   exposure, mTLS/OIDC ingress, a new event type) needs its own explicit
   Product Owner decision and, if it changes load-bearing semantics, its
   own amendment to `docs/design/EVENT_SIGNAL_INTAKE_ARCHITECTURE.md` — the
   current freeze authorizes Slice 1 only.

## 4. Test delta

- New: `tests/test_event_signal_intake.py`, 19 passed (schema rejection,
  signature/replay rejection, cooldown/dedup, idempotent resend,
  `unknown_identity`, `unsupported_vendor_target_seam`, and the structural
  no-vendor-import probe).
- Subsystem regression (`console`/`coordinator` reuse surfaces):
  `tests/test_con2_console_job_engine.py`,
  `tests/test_m9_enrollment_preview_and_confirmation.py`,
  `tests/test_m8_4_m6_resolver_consumption.py`,
  `tests/test_m6_registry_keyed_job_targets.py`,
  `tests/test_phase0_6_1c_collection_executor.py` — 154 passed, unaffected
  by the additive `ConsoleJobRunner.provenance` parameter.
- Full regression (`py -m pytest -q -n auto --dist worksteal`): 3070
  passed, 25 skipped, 2 failed — both pre-existing DLP-token-collision
  findings in `project/build_history.json`/`relay/*.json` prose, confirmed
  present on `origin/main` and untouched by this diff.
- Repository privacy gate (`--privacy-baseline-ref origin/main`): PASS, 0
  new findings (5 pre-existing, none in a file this movement touched).
- `git diff --check`: clean.

## 5. New risks

- `SECURITYEXPERT_EVENT_SIGNAL_HMAC_SECRET` has no fallback generation —
  intentional (design doc "Authentication"), but means the intake app
  fails closed (401 on every request) until an operator sets it; this is
  correct behavior, not a bug, should it surprise a future session.
- The in-process `event_signal_intake_state.json` nonce/cooldown store is
  single-process, filesystem-JSON, no cross-process lock — adequate for
  Slice 1 (no server binder exists to run two processes against it yet);
  revisit if/when real network exposure is authorized.
