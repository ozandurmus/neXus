# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-07. `m9_enrollment_preview_confirmation_ui` (`M9`) —
  **IN_PROGRESS, round 2 (corrective)**. Round 1 landed and validated on
  branch `m9-enrollment-preview-confirmation-ui`, PR #104 opened, open,
  unmerged. A Product Owner corrective review (relay
  `ozandurmus/nexus-agent-relay#3`) found real gaps (see §5). Predecessor
  `gov_relay_1_canonical_agent_relay` (`GOV.RELAY.1`) —
  **AUTOMATED_VALIDATED, MERGED** via PR #105; relay #3 repaired, its stored
  M9 `SESSION_START` revalidated. Claude remains the sole M9 implementation
  owner; Codex owned only the completed GOV.RELAY.1 recovery.
- Predecessor `m8_evidence_host_key_fingerprint_not_persisted` —
  **AUTOMATED_VALIDATED, MERGED** via PR #103. `M8.4` **AUTOMATED_VALIDATED,
  MERGED** via PR #101. `M8.3` stays deferred, `M7` stays blocked.

## 2. What changed (round 1; round 2 in progress)

Full-implementation movement against the frozen
`docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §9.1 contract
(all seventeen conditions), scoped down from the SESSION_START packet's
manual + candidate-based enrollment to **manual-endpoint only** —
candidate-based enrollment is schema-present but server-refused
(`candidate_enrollment_not_available_pending_m10`), per §9.2 amendment `A6`
assigning the registry↔evidence reconciliation join to `M10` (confirmed by
grep: no such join exists anywhere yet).

1. Retrieved and structurally validated the relay `SESSION_START` packet
   (`py scripts/gov_session_transfer.py validate` → `valid: true`); verified
   author, `origin/main` baseline, every `refs` file, and every baseline fact
   against `CURRENT_STATE.md`/`AI_HANDOVER.md` before treating it as
   authoritative.
2. Researched the existing implementation in depth (three parallel Explore
   agents + direct reads) and found `utils/first_contact_producer.py`
   (M8.3) cannot be reused for the probe step — it requires an
   already-`ENROLLED_UNVERIFIED` `device_id` and a discovery-seam match
   against prior plane-wide collection output; M9 must probe an endpoint
   that isn't registered yet.
3. Ran a Plan-agent design pass, then verified its riskiest claims directly
   against source (`execute_admitted_collection`, `PhysicalTarget`,
   `_collect_host`'s `entity_type` branching, `ControlPlaneStore`'s own
   contract forbidding endpoint data) before writing any code.
4. Implemented:
   - `utils/pre_enrollment_identity_probe.py` (new) — the pre-registration
     identity probe, reusing `lookup_trusted_host_key`/`_collect_host`
     directly against a synthetic target, no registry lookup.
   - `utils/enrollment_audit.py` (new) + `EnrollmentAuditBackend` in
     `utils/evidence_backend.py` — immutable confirmation/outcome audit
     rows, filesystem-JSON beside the Device Registry (**not**
     `utils/control_plane_store.py`: that store's own docstring forbids
     "copied endpoints"/"Device Registry rows" — a real correction to the
     original plan, made mid-implementation, not a silent deviation).
   - `console/registry.py` — new `device_enrollment_identity_probe`
     `JobType` (`CLASS_0_READ`, `console_reachable_via="enrollment_probe_api"`).
   - `console/runner.py` — one narrow, documented exception: this job type
     executes via `utils.collection_executor.execute_admitted_collection`
     directly, never `main.main()`.
   - `console/jobs.py` — `JobRecord.preview` field (sanitized identity/
     capability data only).
   - `console/app.py` — `POST /api/enrollment/probe`,
     `POST /api/registry/enrollments`, `GET /api/registry/devices`, plus
     closed-schema validation, an intent-binding hash (job `targets[0]`,
     never the raw endpoint — `JobRecord`'s own forbidden-field list),
     single-use confirmation enforcement, and a `SECURITYEXPERT_DEPLOYMENT_PROFILE`
     defense-in-depth guard (condition 17; always permits today, no runtime
     path sets it).
   - `tests/test_m9_enrollment_preview_and_confirmation.py` (new, ~25
     tests mapped to the 17 conditions + binding/duplicate/candidate
     refusal cases) — **written and now passing** (see §4).
   - UI: `static/console_actions.js` + `templates/console.html` +
     `static/style.css` — one "Add device" dialog in the Inventory pane
     header (probe → preview → confirm). `static/navigation_ui.js`'s
     `add_device` stub flipped to `available: true` (documentation only —
     `navigationContextualActions()` has no render call site anywhere).
5. Updated project state: `CURRENT_STATE.md` (rotated, trimmed back to
   exactly 200 lines), `project/roadmap.json` (`current_build`/`now_next.now`
   rotated, `next` left untouched), `project/feature_registry.json`
   (`no_enrollment_affordance` rewritten in place, matching the established
   `accessibility_closure` precedent).

Round 1 also added the frozen `GOV.RELAY.1` relay contract and shared
Codex/Claude bootstrap prompt (predecessor movement, merged via PR #105),
mandatory pointers from the constitution/cold-start/Claude/Copilot/build
prompts, and focused governance/convergence tests; `NEXUS_SESSION_PACKET` v2
schema/parser behavior is unchanged.

## 3. Exact next action

Audit the existing M9 implementation against the Product Owner's corrective
`SESSION_START` (relay `ozandurmus/nexus-agent-relay#3`, revalidated:
`valid: true`) findings, then correct incrementally — do not restart from
scratch. Per that packet's own instruction: where the audit finds a real
frozen-authority dependency conflict (e.g. whether M8's existing controlled
path can satisfy an unregistered-endpoint probe without a second network/
credential path), report the exact conflict rather than self-authorizing an
exception. Close the findings that are correctable in place: make
`credential_profile_ref`/`trust_profile_ref` select the actual executed
source or fail closed; make preview confirmation single-use under
concurrency; implement the closed candidate-id flow or return the exact
scope conflict; extend `tests/fixtures/uitest` and run the render harness
(Playwright fallback if node/bun unavailable); add the Administration →
Device Management second `PO-NAV-1` entry point. Then re-run required
validation, reconcile project state once more, and post a validated
`SESSION_CLOSE` as the final raw relay comment. PR #104 stays open/unmerged
throughout — merge remains a separate, standing Product Owner decision.

## 4. Test delta

Round 1 (Python 3.12.8, project `.venv`): targeted M9/CON1/CON2/PCP1/
architecture-convergence suite **164 passed, 1 skipped (unrelated), 0
failed**; broader sweep **43 passed**; `git diff --check` and `compileall`
clean; live-Playwright console render walk **2 passed**, zero console
errors; an ad hoc full click-through of the new dialog also passed, zero
console errors. No M8.3/M7 command run; no real device, network, or
credential provider contacted — every probe outcome was mocked or a genuine
`trust_source_unreadable` refusal against an RFC 5737 documentation address.
GOV.RELAY.1 (predecessor, merged): focused relay/session-packet/convergence
149 passed; application-package/privacy 14 passed; repository privacy gate
PASS/0; state consistency, history index and `git diff --check` clean.
Round 2 (corrective) validation not yet run — pending the audit above.

## 5. Risks / notes forward

- Product Owner corrective findings still open: candidate-id enrollment not
  actually implemented (schema-present, server-refused); the new
  pre-enrollment probe path's relationship to the existing M8 controlled
  path needs re-verification, not an assumed exception; `credential_profile_ref`/
  `trust_profile_ref` persisted/audited but not yet selecting the executed
  source; preview-confirmation consumption not yet proven atomic under
  concurrency; `tools/render-harness/check-render.mjs` and a
  `tests/fixtures/uitest` extension still not run; Administration → Device
  Management second `PO-NAV-1` entry point still not built.
- Non-default SSH ports remain structurally unprobeable
  (`configuration/checkpoint_config_probe.py::_connect` uses one global
  env-var port) — pre-existing M8.3 limitation, inherited not introduced.
- `M8.3` stays deferred, `M7` stays blocked — unchanged by this movement.
- A project-local `.venv/` (gitignored) now holds Python 3.12 for this repo.
- Relay #3 preserves the earlier malformed `M9_CORRECTIVE_REVIEW` comment
  under `RELAY_CORRECTION`; it is evidence only, not authority — the
  current validated issue body governs.
- No real device, network, or credential provider was contacted anywhere in
  this movement.
