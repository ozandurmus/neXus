# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-07. `m9_enrollment_preview_confirmation_ui` (`M9`) —
  **AUTOMATED_VALIDATED (rounds 1+2)**. Branch
  `m9-enrollment-preview-confirmation-ui`, PR #104 open, unmerged. Round 1
  shipped manual-endpoint enrollment; a Product Owner corrective review
  (relay `ozandurmus/nexus-agent-relay#3`) found six gaps, two resolved by
  explicit `RELAY_DECISION` and four corrected directly (see §2/§5).
  Predecessor `gov_relay_1_canonical_agent_relay` (`GOV.RELAY.1`) —
  **AUTOMATED_VALIDATED, MERGED** via PR #105/#106; relay #3 repaired.
  Claude is the sole M9 implementation owner; Codex owned only the
  completed GOV.RELAY.1 recovery.
- Predecessor `m8_evidence_host_key_fingerprint_not_persisted` —
  **AUTOMATED_VALIDATED, MERGED** via PR #103. `M8.4` **AUTOMATED_VALIDATED,
  MERGED** via PR #101. `M8.3` stays deferred, `M7` stays blocked.

## 2. What changed

Round 1 — full-implementation movement against the frozen
`docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §9.1 contract
(all seventeen conditions), manual-endpoint only:

- `utils/pre_enrollment_identity_probe.py` (new) — pre-registration identity
  probe reusing `lookup_trusted_host_key`/`_collect_host` against a
  synthetic target, no registry lookup.
- `utils/enrollment_audit.py` (new) + `EnrollmentAuditBackend` in
  `utils/evidence_backend.py` — immutable confirmation/outcome audit rows,
  filesystem-JSON beside the Device Registry (not `control_plane_store.py`,
  which forbids endpoint data).
- `console/registry.py` — new `device_enrollment_identity_probe` `JobType`.
- `console/runner.py` — one narrow, documented exception: this job type
  executes via `execute_admitted_collection` directly, never `main.main()`.
- `console/jobs.py` — `JobRecord.preview` field.
- `console/app.py` — `POST /api/enrollment/probe`,
  `POST /api/registry/enrollments`, `GET /api/registry/devices`.
- `tests/test_m9_enrollment_preview_and_confirmation.py` (new).
- UI: one "Add device" dialog in the Inventory pane header.
- Added the frozen `GOV.RELAY.1` relay contract (predecessor movement,
  merged via PR #105/#106) — governance-only, no product/M9 behavior change.

Round 2 (corrective, same branch/PR) — Product Owner review found six real
findings. Two crossed a frozen-authority/security boundary and were
resolved by an explicit `RELAY_DECISION` (relay #3) rather than
self-authorized:

1. **Candidate-id enrollment** stays explicitly deferred — no candidate-id
   source exists anywhere in the repository (§9.2 amendment `A6`, the
   registry↔evidence reconciliation projection that would produce one, is
   explicitly reserved for `M3`/`M10`). `console/app.py` now returns a
   stable error naming the exact dependency (`candidate_enrollment_not_available_pending_m10`,
   `dependency: "M10 registry<->evidence reconciliation projection (§9.2 amendment A6)"`).
2. **The pre-enrollment probe's `console/runner.py` exception** (bypassing
   `main.main()`) is explicitly authorized as M9-only — the Product Owner
   explicitly rejected the alternative of a throwaway "pending" Device
   Registry stub. `console/runner.py`'s module docstring now documents this
   decision and cites the relay; a new test
   (`test_enrollment_probe_is_the_only_job_type_exempt_from_main_main`) pins
   that exactly one `JobType` carries the exception.

The other four findings were corrected directly, no new authorization
needed:

3. `credential_profile_ref` is now closed to one sentinel
   (`system_cp_config_ssh`) that actually matches the one executed
   credential source, mirroring `trust_profile_ref`'s existing pattern
   (`console/app.py`, `static/console_actions.js`, `templates/console.html`).
   Previously it accepted an arbitrary format-valid string that selected
   nothing real.
4. Confirmation consumption is now atomic under concurrency:
   `utils/enrollment_audit.py::record_confirmation`'s `audit_id` is
   deterministic on `probe_job_id`, checked-and-created under one lock
   (`EnrollmentAuditConflictError` on collision), replacing a racy
   list-scan-then-create in `console/app.py`. Proven by a new real-thread
   concurrency test, not just sequential replay.
5. The round-1 ad hoc dialog click-through script is now a permanent test
   (`test_add_device_dialog_live_click_through_zero_console_errors`), and
   `test_con1`'s module-nav walk includes `device-management`.
6. Administration → Device Management (`PO-NAV-1`'s second entry point) is
   now implemented: a new `device-management` module panel
   (`templates/console.html`, `static/navigation_ui.js`'s
   `NAVIGATION_MODEL`) with a read-only registry table and an "Add device"
   button that opens the exact same `#m9EnrollDialog` — one enrollment
   implementation, two entry points.

Incidental convergence debt paid down while running the broader suite for
the first time in this movement: `tests/test_navigation_information_architecture.py`
had two tests (`test_ac4_add_device_is_a_contextual_action_and_renders_nowhere`,
the `test_ac8` console module-nav assertion) that predated M9 and were never
updated for round 1's shipped UI — fixed to reflect actual current reality.

## 3. Exact next action

None outstanding for M9 itself. PR #104 is open, unmerged, and this close's
`SESSION_CLOSE` is pending. Per this movement's own `merge_gate`: do not
merge without a later, explicit Product Owner integration decision.
Disclosed residual risk (unchanged from round 1, not a new gap):
`tools/render-harness/check-render.mjs` (node/bun) remains unexercised —
node/bun are still unavailable in this environment; the console's own
live-Playwright coverage is the real substitute exercised.

## 4. Test delta

Round 1 (Python 3.12.8, project `.venv`): targeted M9/CON1/CON2/PCP1/
architecture-convergence suite 164 passed, 1 skipped, 0 failed; broader
sweep 43 passed; live-Playwright console render walk 2 passed, zero console
errors. GOV.RELAY.1 (predecessor): focused relay/session-packet/convergence
149 passed; application-package/privacy 14 passed.

Round 2 (corrective): re-ran targeted+affected suite after every fix —
`tests/test_m9_enrollment_preview_and_confirmation.py` (27, incl. two new:
atomic-confirmation-under-real-concurrency and the M9-only-exception pin),
`test_con1_operator_console_read_only.py`, `test_con2_console_job_engine.py`,
`test_pcp1_device_registry.py`, `test_architecture_convergence.py`,
`test_gov_relay_protocol.py`, `test_navigation_information_architecture.py`,
`test_m2_nav_accessibility_closure.py`, `test_frontend_module_composition.py`:
**247 passed, 0 failed**. Broader sweep (`-k "architecture_convergence or
privacy or application_package"`): 44 passed. `git diff --check` and
`compileall` clean. No M8.3/M7 command run; no real device, network, or
credential provider contacted in either round — every probe outcome was
mocked or a genuine `trust_source_unreadable`/identity-gate refusal against
an RFC 5737 documentation address.

## 5. Risks / notes forward

- `tools/render-harness/check-render.mjs` (node/bun) still not run — node/bun
  unavailable in this environment; unchanged residual risk from round 1.
- `credential_profile_ref`/`trust_profile_ref` are each closed to exactly one
  real sentinel today (no multi-credential/multi-trust system exists) —
  consistent with the frozen contract, worth Product Owner confirmation at
  review that this is the intended shape going forward.
- Non-default SSH ports remain structurally unprobeable
  (`configuration/checkpoint_config_probe.py::_connect` uses one global
  env-var port) — pre-existing M8.3 limitation, inherited not introduced.
- `M8.3` stays deferred, `M7` stays blocked — unchanged by this movement.
- A project-local `.venv/` (gitignored) now holds Python 3.12 for this repo.
- Relay #3 preserves the earlier malformed `M9_CORRECTIVE_REVIEW` comment
  under `RELAY_CORRECTION`; it is evidence only, not authority.
- No real device, network, or credential provider was contacted anywhere in
  this movement.
