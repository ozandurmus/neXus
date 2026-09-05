# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase
doc. Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-05. Branch: `claude/left-nav-vertical-redesign-e673q6`, built
  on `origin/main` at `310593e` (merged PR #83, `PCP.1`).
- Build: `nav_1_left_vertical_product_navigation` (`NAV.1`) —
  **AUTOMATED_VALIDATED**. Movement: `UI`, against a contract frozen in the
  same session (`docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md`).
- Unlike the `PCP.1` session's sandbox, this one has pytest / lxml / paramiko /
  playwright / fastapi available, so the full suite, both render harnesses and
  the live-browser console walk all really ran.

## 2. What changed this session

- **`docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md`** (new, FROZEN): the
  navigation shape's single owner. D-NAV1…D-NAV9, the §3 evaluation of the
  seven candidate domains down to six shipped roots, §4 contextual actions,
  §5 shell parity, §7 AC-1…AC-12.
- **`static/navigation_ui.js`** (new, composed second): the one navigation
  model for both the left rail and the device-detail tab strip; the
  availability rule (`navigationShellHasPanel` — does *this shell* ship the
  `[data-module-panel]`?); the authorization seam
  (`navigationAuthorizationContext()` → `model: "none"`); the rail renderer
  and one delegated click dispatcher.
- **`templates/index.html` / `templates/console.html`**: topbar
  `<nav class="module-nav">` removed; module sections wrapped in
  `.app-body` → `[data-primary-nav]` + `.app-canvas`; the device tab strip is
  now `<nav class="config-tabs" data-device-tabs>`. The console's `CON.2` job
  card became its own `jobs` module panel (no boundary, route or job-type
  change; `static/console_actions.js` untouched).
- **`static/app_bootstrap.js`**: `savedModule()`/`switchModule()` derive the
  valid-module universe from the model instead of two hard-coded lists that
  had drifted apart; per-button nav binding replaced by the rail's delegation;
  guarded `jobs` refresh dispatch.
- **`static/style.css`**: left-rail styles (expanded + collapsed density);
  the horizontal strip's rules and its per-id `::before` label-abbreviation
  hack removed; `.app-module` height now `100%` inside the canvas.
- **`utils/html_export.py`**: `navigation_ui.js` added to
  `SCRIPT_MODULE_FILENAMES` at position 2 (the console composes the same list).
- **Tests**: new `tests/test_navigation_information_architecture.py`
  (19, AC-1…AC-12, four in a real Chromium).
  `tests/test_frontend_module_composition.py`: function count 179 → 196, and
  its AST-lite scanner now strips quoted string literals — a module *id* in a
  string is data, not a load-time reference. Four `test_phase0_6_*` template
  marker tests now assert navigation reachability against the model
  (`module: "x"` in the composed script + `data-module-panel="x"` in the
  shell) instead of the removed `id="xNav"` buttons.
- **One deliberate out-of-navigation fix**, recorded not folded in silently:
  `static/failover_readiness_ui.js` emitted an inline
  `style="padding-left:2rem"` that the console's stricter CSP
  (`style-src 'self'`) refuses; now the `.failover-child-cell` class.
- **Docs/state**: `AI_START_HERE.md` directory map + harness trigger,
  `docs/ARCHITECTURE.md` §6, `AGENTS.md` + `docs/AI_DEVELOPMENT_PROTOCOL.md`
  harness trigger lists, `CURRENT_STATE.md`, `project/roadmap.json` (`now`
  rotated), `project/build_history.json` (new head), `project/feature_registry.json`
  (`left_vertical_product_navigation`, done), `project/backlog.json` (the
  discovered `PCP.1` test defect), `docs/history/INDEX.md` regenerated.

## 3. Exact next action

1. Push this branch and open a PR to `main` if the Product Owner wants it
   merged. Note that the fast PR `validate` job will **not** run the new
   navigation tests (it runs a fixed smoke set); a full regression via
   `workflow_dispatch` — or the post-merge `full-regression` on `main` — is
   what actually covers them.
2. Decide `pcp1_registry_uuid_call_count_test_defect` (backlog, P2): two
   `PCP.1` registry tests fail deterministically on `main` because their
   `uuid4` call-count proof also counts the registry lock's own owner token.
   The AC still holds; the proof technique over-reaches. It is a `PCP.1`
   contract-internal test decision and was deliberately **not** fixed inside
   this UI movement.
3. `now_next.next` is unchanged: `pcp_2_local_control_plane_sequencing_po_review`
   — a Product Owner sequencing decision, not started, not pre-designed.
   Do **not** build an Add Device UI, a browser enrollment form, or any RBAC
   behaviour off the back of `NAV.1`: the model declares where enrollment will
   live and renders nothing, exactly because
   `pcp_console_registry_write_gate` is open.

## 4. Test delta

- Full serial suite here: **1950 passed / 22 skipped / 2 failed** (before this
  build, same machine: 1929 / 24 / 2). The same two failures before and after,
  both pre-existing `PCP.1` registry tests — see item 2 above.
- New: 19 navigation tests. 2 fewer skips: the node+happy-dom render harness
  and `CON.1`'s live console walk both actually run here now.
- Both render harnesses green; repository privacy gate PASS / 0 findings /
  490 files; `metadata_warnings == []`; `build_history_index.py --check`
  clean; `git diff --check` clean for this change.

## 5. New risks / notes forward

- `PCP.4` and `PCP.5` now have a shape to land in: a device-scoped capability
  is a **device tab**, never a new root, and `Jobs` promotes from an
  Operations child to a root only when it owns definitions/runs/schedules.
- Adding the authorization conjunct to the availability predicate when
  `DEPLOY.1A` lands is a `NAV.2` amendment to the frozen contract, not a
  silent edit. Until then the navigation makes exactly one authorization
  statement: there is no model.
- The `CON.1` live console walk is now a real gate wherever a Chromium
  resolves. It caught a CSP violation that had been invisible for as long as
  that test skipped; expect it to catch inline-style regressions in future UI
  work rather than the report-only CSP being the whole story.
- No real-environment validation was performed or claimed. `NAV.1` contacts
  no device and needs none.
