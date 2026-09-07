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

- Date: 2026-09-07. `m9_enrollment_preview_confirmation_ui` (`M9`) —
  **AUTOMATED_VALIDATED**. Implementation landed and validated on branch
  `m9-enrollment-preview-confirmation-ui`, two local commits made, **not yet
  pushed, no PR opened** (pending explicit PO go-ahead — Git push/merge is a
  standing human-approval boundary, independent of the movement's own
  authorization). PO-authorized via `SESSION_START` relay
  `ozandurmus/nexus-agent-relay#3`, out of `now_next` order (the roadmap's
  own `next` still names `m8_3_real_environment_validation`, deferred,
  unchanged).
- Predecessor `m8_evidence_host_key_fingerprint_not_persisted` —
  **AUTOMATED_VALIDATED, MERGED** via PR #103. `M8.4` **AUTOMATED_VALIDATED,
  MERGED** via PR #101.

## 2. What this session did

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

## 3. Exact next action

**Environment blocker, disclosed to the Product Owner mid-session, then
resolved with their explicit help:** this authoring session started with
only Python 3.9.6 (repo needs 3.10+). The Product Owner authorized and
personally ran the `sudo installer` step for the official python.org 3.12.8
`.pkg`; a project-local `.venv` was then built and dependencies installed.
Playwright's Chromium was also installed (`playwright install chromium`,
no sudo needed). node/bun remain unavailable — not resolved.

Remaining before merge:

1. Push the branch and open the PR — **pending explicit Product Owner
   go-ahead**, asked once already (answer was "wait — validate first",
   which is now done; needs to be asked again).
2. `tools/render-harness/check-render.mjs` (needs node/bun) was not run —
   the Python/Playwright equivalent (`test_ac1_console_renders_every_module_live_with_zero_console_errors`
   plus an ad hoc full click-through of the new dialog) was run instead and
   passed, but the JS-runtime-specific check is still unexercised.
   `tests/fixtures/uitest/` was not extended for the new module/payload
   shape either.
3. A user-assisted, corporate-PC2 real-browser walkthrough of "Add device"
   against fixture/synthetic data only — per the SESSION_START packet, ask
   the Product Owner to type any local application authentication directly
   into the app's own field or a masked terminal prompt, never into chat.
4. Only after the PR exists: render, validate, and post the protocol-v2
   `SESSION_CLOSE` to relay issue #3, then return
   `RELAY_READY ozandurmus/nexus-agent-relay#3` per the packet's
   `output_contract`.

## 4. Test delta

Real run, Python 3.12.8, project `.venv`: `py -m pytest -q -n auto --dist worksteal tests/test_m9_enrollment_preview_and_confirmation.py tests/test_con1_operator_console_read_only.py tests/test_con2_console_job_engine.py tests/test_pcp1_device_registry.py tests/test_architecture_convergence.py` — first run surfaced 3 expected failures (a stale derived doc, two existing closed-set tests needing extension for the new field/routes — not implementation bugs); fixed; **164 passed, 1 skipped (unrelated), 0 failed**. Broader sweep (`-k "architecture_convergence or privacy or application_package"`): **43 passed**. `git diff --check` clean. `compileall` clean on every touched file. Live-Playwright console render walk (real Chromium): **2 passed**, zero console errors. An additional ad hoc script clicked all the way through the new "Add device" dialog (open → fill → probe → real job pipeline → correct negative-evidence status message → close) against a real running console and real browser: **passed, zero console errors**. No M8.3 or M7 command was run; no real device, network, or credential provider was contacted anywhere — every probe outcome exercised was either mocked or a genuine `trust_source_unreadable` refusal against an RFC 5737 documentation address.

## 5. Risks / notes forward

- `tools/render-harness/check-render.mjs` (node/bun) and a `tests/fixtures/uitest/` extension for the new dialog/module are still not run/done — the Python/Playwright-based checks above are real but narrower coverage.
- Administration → Device Management second `PO-NAV-1` entry point not built — disclosed scope trim, not silent.
- `credential_profile_ref`/`trust_profile_ref` are opaque, persisted, audited, but do not select an actual credential/trust source (exactly one of each exists system-wide today) — consistent with the frozen contract's own text, worth PO confirmation at review.
- Non-default SSH ports remain structurally unprobeable (`configuration/checkpoint_config_probe.py::_connect` uses one global env-var port) — pre-existing M8.3 limitation, inherited not introduced.
- `M8.3` stays deferred, `M7` stays blocked — unchanged by this session.
- A new `.venv/` now exists in the repo working tree (gitignored, matches the existing `.venv/`/`venv/` pattern) — local Python 3.12 environment for this repo going forward.
- No real device, network, or credential provider was contacted anywhere in this session.
