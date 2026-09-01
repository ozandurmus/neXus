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
  recommendation) and `CON.1` (read-only console) was implemented and reached
  **AUTOMATED_VALIDATED** the same session.
- Branch: `main`, clean, up to date with `origin/main` at session start. This
  session's changes are **uncommitted** — see §3.

## 2. What changed this session

- Answered `C-D1` (approve `fastapi`+`uvicorn` as an optional dependency,
  `requirements-console.txt`) and `C-D2` (cookieless per-launch bearer token
  in the URL fragment) — both per the architecture doc's own recommendation.
  `project/roadmap.json`'s two entries marked `decided`.
- Implemented `CON.1` per `docs/history/phase/CON_1_OPERATOR_CONSOLE_READ_ONLY.md`,
  following its 9-step plan:
  1. `utils/html_export.py` gained `MODULE_ORDER` / `compose_modules()` (C1-2)
     and `build_report_payloads()` — the payload-building half of
     `run_html_export` extracted into its own function, called by both
     `run_html_export` and the console (`console/payloads.py`), so the two
     surfaces cannot drift apart (C1-4). `compose_report_script()` and
     `SCRIPT_MODULE_FILENAMES` kept as-is for the ~16 existing tests bound to
     them.
  2. `app_bootstrap.js`'s tail became `initializeReport(payloads)` (C1-3);
     `app_core.js` gained the mutable payload globals (`let rawData`, etc.)
     and a `reportMode()` accessor reading `window.SECURITYEXPERT_MODE`.
     `templates/index.html` sets the mode flag and calls `initializeReport`
     with the inline JSON. This is the phase's flagged risky step — done
     first, alone, render harness green before anything else landed
     (contract's own risk note). One real bug caught immediately: adding a
     *second* `<script>` tag broke `tools/render-harness/check-render.mjs`'s
     single-inline-script assumption — fixed by keeping one script tag.
  3. `console/auth.py` — token generation/comparison, origin/Sec-Fetch-Site
     checks (C1-5, AC-6).
  4. `console/payloads.py` — thin wrapper calling `build_report_payloads`
     with no lifecycle/capability/coordinator state (a freshly launched
     console has none; discovery/exclusions render their existing explicit
     empty state, same as `--render-only` before a coordinator exists).
  5. `console/app.py` — FastAPI app, CSP response header (C1-1), the four
     asset/shell routes (unauthenticated) + `/api/payloads` (authenticated).
     Routes use `@app.api_route(..., methods=["GET", "HEAD"])` explicitly —
     this Starlette version does **not** auto-add HEAD to `@app.get`.
  6. `templates/console.html` (full body markup, reused from `index.html`;
     external `<link>`/`<script src>` only — CSP has no `unsafe-inline`) +
     `static/console_actions.js` (mode flag, launch-token fragment handling,
     fetch/refresh cycle, opt-in ≥30s auto-refresh — zero action affordance).
  7. `console/server.py` (C1-8 fail-closed preflight) + `main.py --console
     [--console-port N]` wiring in `application/cli.py` (new Phase A
     preflight before anything else, new branch after Phase C's runtime
     foundation, mutual-exclusivity validation).
  8. `requirements-console.txt`; `AI_START_HERE.md` CLI table + "no web
     server" line corrected.
  9. `tests/test_con1_operator_console_read_only.py` (AC-1…AC-11); full
     suite; privacy gate; project metadata (`backlog.json`,
     `feature_registry.json`, `build_history.json`, `roadmap.json`, this
     file, `CURRENT_STATE.md`).
- **Real finding during AC-8 (console imports no vendor module,
  transitively):** three pre-existing eager `configuration.*` imports were
  reachable from `utils/html_export.py` — `utils/config_ui.py`
  (`align_checkpoint_management_intent`, `build_pan_current_configuration`),
  `utils/config_history.py` (four helpers from
  `configuration.current_config_projection`), `utils/crypto_posture.py`
  (`_artifact_bytes`). Not introduced by this build — every other mode
  already pulled these in eagerly — but only closeable once `console/` made
  the import graph a tested invariant. Fixed by moving each to a lazy
  import at its point of use (same `DEV.3.3`-established pattern as the
  optional-dependency preflights); behavior-preserving for every existing
  caller.
- Installed `fastapi`, `uvicorn`, `httpx` (dev/test only — `httpx` powers
  `fastapi.testclient.TestClient`, not itself a runtime dependency) into the
  active Python environment. Not yet reflected in any lockfile beyond
  `requirements-console.txt` itself.
- Live end-to-end smoke test: started `main.py --console` for real, hit it
  over real HTTP with `curl` (200 on `/`, `/assets/app.js`,
  `/assets/style.css`; 401 on `/api/payloads` without a token; correct CSP
  response header), then stopped the process cleanly.

## 3. Exact next action

**This session's changes are uncommitted on `main`.** Before anything else,
review and commit/PR them (repo convention elsewhere in this history is a
feature branch + PR, not a direct commit to `main` — follow whatever the user
directs). Touched/added files:

```
 M AI_START_HERE.md
 M application/cli.py
 M project/{backlog,build_history,feature_registry,roadmap}.json
 M static/app_bootstrap.js
 M static/app_core.js
 M templates/index.html
 M tests/test_frontend_module_composition.py
 M tests/test_frontend_rendering_boundary.py
 M tests/test_html_export_placeholder_integrity.py
 M tests/test_html_render_harness.py
 M tests/test_phase0_6_1b_1_2_interactive_project_plan.py
 M tests/test_phase0_7_0_crypto_agility.py
 M utils/config_history.py
 M utils/config_ui.py
 M utils/crypto_posture.py
 M utils/html_export.py
?? console/               (new package)
?? requirements-console.txt
?? static/console_actions.js
?? templates/console.html
?? tests/test_con1_operator_console_read_only.py
```

After that, independent options for the next session:

- **`CON.1` real-browser open** (cheap, non-blocking): render the uitest
  fixture behind a live `--console` process and drive it in an actual
  browser (same pattern the frontend split used) to move `CON.1` from
  AUTOMATED_VALIDATED to `DONE`. AC-1's Playwright test in
  `tests/test_con1_operator_console_read_only.py` skips cleanly in this
  sandbox (Chromium binary not downloaded); `py -m pip install -r
  requirements-dev.txt && playwright install chromium` would let it run for
  real instead.
- **`CON.2`** (job engine + `read`-class actions) — blocked on `CON.1`
  (now clear) and `C-D3` (product-owner: `Provenance.CONSOLE` vocabulary,
  recommendation already on file in `project/roadmap.json`).
- `OP.0`/`OP.1` — design-frozen, write-free, buildable now.
- `RB.3b` — blocked on the external watched real-device R81.10/R81.20 run
  (hardware-gated, not engineering).

## 4. Test delta

Full suite **907 passed / 27 skipped / 2 failed** (`pytest_result.log`), vs
the session-start baseline **896 / 26 / 2** — `+11 passed` (the new
`test_con1_operator_console_read_only.py`, minus its one Chromium-gated
skip) `+1 skipped` (that same AC-1 test). The 2 failures are the same
pre-existing order-pollution pair as every prior session, reproduced on an
unmodified checkout of the same batch (not a regression). Repository privacy
gate `PASS/0` on a clean checkout (two rounds of leftover `data/`/`logs/`
runtime artifacts from manual `--console` smoke-testing and from the full
suite's own known logger side effect were found and removed before the
final gate run — neither is new to this session).

## 5. New risks / debt

- `fastapi`/`uvicorn`/`httpx` are now installed in the active dev
  environment but this was not captured anywhere beyond
  `requirements-console.txt` (which only the optional console path needs).
  If CI or another environment runs `tests/test_con1_operator_console_read_only.py`
  without those installed, every test in that file will fail at collection
  (`from fastapi...`) rather than skip — unlike the Playwright AC-1 case,
  this file has no top-level skip guard for a missing FastAPI/uvicorn.
  Consider whether that file should import lazily / skip cleanly if a CI
  environment doesn't install `requirements-console.txt` as part of its test
  setup, or whether CI is expected to always install it. Not resolved this
  session — flagging for whoever wires CI for this track.
- The three lazy-import fixes (`utils/config_ui.py`, `utils/config_history.py`,
  `utils/crypto_posture.py`) touch code paths every mode already exercises;
  full suite is green at baseline, but if a future change re-adds a
  module-level `configuration.*`/`checkpoint.*`/`panorama.*` import anywhere
  reachable from `console.app`, AC-8 in `tests/test_con1_operator_console_read_only.py`
  will catch it — no action needed, just don't weaken that test.

## 6. Continue or fresh chat

**Either works**, but commit/PR the uncommitted changes first (§3) before
starting new work — nothing else is mid-flight.

## 7. main.py / UI effect

**`--console` is a new, additive, mutually-exclusive CLI mode** — every
existing mode's behavior, output and CLI surface is unchanged (verified by
the full suite holding at baseline). The one non-additive, behavior-preserving
change is `app_bootstrap.js`'s `initializeReport(payloads)` refactor
(render harness green, exported report byte-for-byte the same modulo the
initialization call shape).
