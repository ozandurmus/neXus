# AI_HANDOVER

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase doc.
Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-01.
- Product baseline `0.7.7`; engineering `DEV.3.3` — both AUTOMATED_VALIDATED,
  unchanged. `RB.3b` `in_progress` (hardware-gated), unchanged.
- `codebase_modularization` (both halves, including the real-browser
  follow-up) is `DONE`, unchanged this session.
- `CON.x` operator console: `CON.0` architecture stayed FROZEN; `C-D1`/`C-D2`
  were answered this session (both approved per their documented
  recommendation) and `CON.1` (read-only console) was implemented **and**
  closed to **DONE** the same session, including its own human real-browser
  open.
- Branch: `main`, commit `135a201` ("fix(ui): two real bugs found by CON.1's
  real-browser open"), on top of `6e22e0a` (CON.1 implementation) and
  `686dfa5` (handover docs). All three pushed to `origin/main` — `main` is
  clean and in sync.

## 2. What changed this session

Two parts, same session:

**A. `CON.1` implementation** (commit `6e22e0a`) — see that commit message
or the previous handover in git history for full detail. Summary: new
`console/` package (FastAPI/uvicorn loopback service, cookieless bearer
auth, strict CSP), `templates/console.html`, `static/console_actions.js`,
`main.py --console [--console-port N]`, `requirements-console.txt`.
`utils/html_export.py` gained `build_report_payloads()` so the console and
the exported report call the identical payload builder. Fixed three
pre-existing eager `configuration.*` imports (AC-8 surfaced them) to lazy
imports.

**B. `CON.1`'s human real-browser open** (commit `135a201`) — per the
contract's own closure requirement (same pattern `codebase_modularization`
(frontend) used). Seeded a scratch runtime root with the
`tests/fixtures/uitest` bundle (same monkeypatch technique as
`scripts/render_uitest.py`), launched `main.py --console` against it, and
drove it live in a real Chromium tab (Browser pane) through all seven
modules. **Found and fixed two real regressions that neither `pytest` nor
the bun/happy-dom render harness had caught:**

1. **CSP `style-src` violations.** Three JS-set inline `style=""`
   attributes — `compliance_ui.js`'s segmented framework-coverage bar
   (`style="flex:${n}"`) and `project_plan_ui.js`'s roadmap progress bar
   (`style="width:${numeric}%"`) — were blocked outright by the console's
   stricter CSP (`style-src 'self'`, no `unsafe-inline`, per C1-1). The
   static report's CSP keeps `unsafe-inline` so this never surfaced there.
   Fixed with a fixed `.w-pct-0`…`.w-pct-100` CSS class set
   (`static/style.css`), applied via `classList` instead of a computed
   style attribute; the segmented bar converts its flex ratios to rounded
   percentages first. CSP-safe in both modes, so this is a genuine
   improvement to the static report too (one less style-src risk if its CSP
   is ever tightened).
2. **Stale module-load-time derived state.** `inventory_ui.js` (`inventory`
   / `inventoryRoots`), `configuration_ui.js` (`configDevices`) and
   `compliance_ui.js` (`complianceSubjects`) each computed a derived
   collection **exactly once, at module-load time**, from `rawData` /
   `configUiData` / `complianceUiData` — which are still their empty
   defaults at that point, since `initializeReport(payloads)` (CON.1's own
   C1-3 refactor, the previous commit) only assigns the real payloads
   *afterward*. Network Inventory, Configuration and Compliance rendered
   empty. **This affected the exported static report too, not only the
   console** — both modes run every module's top-level code before calling
   `initializeReport`. Fixed by wrapping each into a `rebuildX()` function,
   called once at load (harmless against the empty default — preserves the
   exact prior behavior) and again from `initializeReport`, before any
   `renderX()` call.

Re-verified after both fixes: fresh Browser-pane tab, full walk through all
seven modules (Overview → Network Inventory → Configuration → Compliance →
Discovery → Exclusions → Project Plan), zero console errors, real
device/compliance/roadmap data rendered and visually correct (18 inventory
entities, 14 assessed compliance devices, segmented coverage bars, roadmap
progress bars all correct).

Project metadata updated to close `CON.1` to `DONE`: `project/backlog.json`,
`project/feature_registry.json` (`operator_console_surface` → `done`),
`project/build_history.json` (new `operator_console_read_only_real_browser_close`
entry), `CURRENT_STATE.md`, this file.

## 3. Exact next action

`CON.1` is fully closed — nothing left there. Next independent step for the
`CON.x` track:

**Start `CON.2`** (job engine + `read`-class actions,
`docs/history/phase/CON_2_CONSOLE_JOB_ENGINE_READ_ACTIONS.md`) — blocked
only on `C-D3`, the next open product-owner decision in its path: add
`Provenance.CONSOLE = "console"` to the provenance vocabulary, or reuse
`"manual"` for UI-triggered runs. Recommendation already on file in
`project/roadmap.json` (`open_decisions`, id `C-D3`): **add `"console"`** —
a UI-triggered device action must be distinguishable from a CLI one in
every manifest and audit record; conflating them destroys the audit trail
on the first day it matters. Whoever picks this up next should surface that
question to the product owner first (same `AskUserQuestion` pattern used
for `C-D1`/`C-D2` this session and the session before), then read
`CON_2_CONSOLE_JOB_ENGINE_READ_ACTIONS.md`'s own implementation plan.

Worth flagging to whoever picks up `CON.2`: this session's finding #2 above
(stale module-load-time derived state) is a *class* of bug — any new
`CON.2` UI code that adds its own derived-state variable at module-load
time will have the same problem unless it is likewise recomputed inside
`initializeReport` (or, for `CON.2`'s job-status polling, inside whatever
periodic refresh hook it adds). Worth a quick read of `static/app_bootstrap.js`'s
`initializeReport` before adding new UI state.

Other independent, unrelated options (unchanged from before this session):

- `OP.0`/`OP.1` — design-frozen, write-free, buildable now.
- `RB.3b` — blocked on the external watched real-device R81.10/R81.20 run
  (hardware-gated, not engineering).

## 4. Test delta

Full suite **907 passed / 27 skipped / 2 failed** (`pytest_result.log`),
unchanged from immediately after `CON.1`'s implementation commit (this
session's real-browser bugfixes touched only client-side JS/CSS, which the
pytest suite doesn't execute in a real browser — that's exactly the gap the
real-browser open exists to cover, and it's now covered by the manual walk
described in §2, not by an automated test). The 2 failures are the same
pre-existing order-pollution pair as every prior session. Repository privacy
gate `PASS/0` on a clean checkout (leftover `data/`/`logs/` runtime
artifacts from the full suite's own known logger side effect were found and
removed before each final gate run — not new to this session).

## 5. New risks / debt

- **Test coverage gap, now known and partially addressed by fix, not by a
  new test.** `tools/render-harness/check-render.mjs` asserts scripts
  execute cleanly and nav/tabs are clickable, but never asserts an actual
  rendered device/entity *count* — which is exactly the class of bug
  finding #2 (§2) was. Not fixed this session (scope discipline — `CON.1`'s
  contract only required the human open, not a harness enhancement); flagged
  in `project/build_history.json`'s `risks_forward` for the
  `operator_console_read_only_real_browser_close` entry. Worth considering
  whether `check-render.mjs` should gain a minimal device-count assertion
  for the uitest fixture, so this class of regression is caught
  automatically next time instead of only by a human open.
- Carried over from the previous handover, still true: `fastapi`/`uvicorn`/
  `httpx` are installed in the active dev environment but only captured in
  `requirements-console.txt` (the optional path). `tests/test_con1_operator_console_read_only.py`
  has no top-level skip guard for a missing FastAPI/uvicorn — if a CI
  environment doesn't install `requirements-console.txt`, that whole file
  fails at collection rather than skipping cleanly. Not resolved.

## 6. Continue or fresh chat

**Either works.** Both commits (`6e22e0a`, `135a201`) plus the docs commit
are pushed to `origin/main`; nothing is mid-flight, nothing uncommitted.

## 7. main.py / UI effect

**No CLI surface change this session's second half** (the bugfix commit
touched only `static/*.js`, `static/style.css`, and one test file). The two
fixes are both behavior-preserving-or-better for the exported static report
(finding #2 was an active bug there too, now fixed; finding #1 is a
CSP-hardening improvement with no visible behavior change under the report's
own more permissive CSP) and are the actual fix that makes the console's
Network Inventory / Configuration / Compliance modules render real data.
