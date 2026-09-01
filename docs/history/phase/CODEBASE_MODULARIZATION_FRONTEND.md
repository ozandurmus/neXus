# Codebase modularization — frontend (`static/app.js` split)

## Status

**IMPLEMENTED 2026-09-01** (`Sonnet 5, normal`). `static/app.js` is gone; its
4,905 lines / 173 top-level functions are distributed across the eight D-MOD5
files and concatenated back into the identical single inline `<script>` by
`utils/html_export.py`. AC-1…AC-6 green against fixture transports and the bun
render harness; AC-7's real-browser confirmation stood in as the bun
DOM-execution harness (this sandbox has no display / no Playwright Chromium) —
a human interactive open remains a cheap, non-blocking follow-up, so the
backlog id stays `in_progress`. Two contract-audit gaps found during
extraction and resolved by the ownership rule are recorded in
"Implementation deviations" below. `project/build_history.json` entry
`codebase_modularization_frontend`.

**Contract-freeze record (unchanged) follows.**

**CONTRACT FROZEN 2026-08-31.** Produced as a `SCOPE → AUDIT → CONTRACT` pass
(`Sonnet 5, normal`) in the same session as, and immediately following,
`frontend_rendering_boundary`'s implementation — that session's full,
line-by-line read of `static/app.js` (all 97 `.innerHTML` sinks plus every
helper) is the source of this contract's function inventory, so the audit
below is a verified re-read for module boundaries, not a fresh guess.

`project/backlog.json` `codebase_modularization` (P1). Scoped from
`docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md` §5
"Frontend: preserve one portable report" — this contract is the concrete,
line-verified version of that section's directional proposal, covering the
frontend half only (the backend half, `main.py`/vendor-collector splitting,
is a separate, later item under the same backlog entry).

## Objective

`static/app.js` is 4,905 lines and 169 top-level functions in one file,
covering seven distinct UI concerns (Network Inventory, Overview,
Configuration, Compliance/Crypto, Discovery/Exclusions, Project Plan,
report-wide chrome/bootstrap) with no internal structure beyond loose
physical ordering. `frontend_rendering_boundary`'s own risk section named
this directly: "an exhaustive audit touching most of a 4900-line file is a
large diff; resist the urge to restructure the file while doing it." This
build is that restructuring, done deliberately and separately, now that the
escaping audit backing it is complete and clean (zero gaps — see that
contract's "Implementation findings"). The goal is smaller, single-concern
source files that are easier to review, audit, and extend, composed back
into the exact same single portable inline `<script>` the product
architecture commits to — **zero behavior, markup, payload, or visible
change**. This is a code-health/maintainability build, not a feature.

## Scope

### In scope

1. Splitting `static/app.js` into responsibility-owned source files under
   `static/`, per the module ownership in "Design decisions" below.
2. A composition mechanism in `utils/html_export.py` that concatenates the
   split files, in a fixed dependency order, into the same single inlined
   `<script>` block — replacing today's single `read_text_file(script_file)`
   call for `__SCRIPT_PLACEHOLDER__`.
3. A new regression test that statically proves module boundaries are
   respected (a module references no identifier owned by a module that
   loads after it) — see AC-3.
4. Byte-identical rendered output proof against the `uitest` fixture bundle,
   module-nav/tab behavior proof via the existing render harness (both the
   static-JSON check and, where available, the real-Chromium check).

### Explicitly out of scope

- Any change to `templates/index.html`'s markup, `static/style.css`, or any
  payload builder (`utils/config_ui.py`, `utils/compliance_posture.py`,
  etc.) — this is a `static/app.js` file-layout change only.
- The CSP/escaping contract itself (`frontend_rendering_boundary`,
  IMPLEMENTED 2026-08-31) — not reopened; this build must not touch
  `escapeHtml()`'s behavior or any sink's escaping.
- Introducing a bundler, transpiler, `<script type="module">`, or any build
  step. The product ships one dependency-free HTML file with no build
  pipeline anywhere in this repository (`main.py` is the only build tool,
  and it is Python) — this build preserves that, per D-MOD1 below.
- The backend half of `codebase_modularization` (`main.py` /
  `configuration/pan/` / `configuration/checkpoint/` splitting per the
  architecture doc §5's second half) — a separate, later item under the
  same backlog id. Do not fold it into this build.
- Any new feature, UX change, or performance optimization. A module split
  that happens to reveal an obvious quick win (e.g. two near-duplicate
  helpers) is noted as a candidate follow-up, not folded into this diff —
  same discipline `frontend_rendering_boundary` and `html_render_
  optimization` both already applied to adjacent temptations.

## Audit findings (this session, 2026-08-31)

Verified against the same full read this session already performed for
`frontend_rendering_boundary`'s AC-2 sink audit, re-examined here for
structural boundaries rather than escaping correctness.

- **169 top-level `function` declarations**, one flat scope, no IIFE/module
  wrapper anywhere in the file (`grep -c '^function \w\+('` = 169). Because
  there is no `"use strict"` pragma and no wrapper, every one of them is
  already an implicit `window.*` global today — this is load-bearing: the
  render harness's own smoke check asserts `typeof window.switchModule ===
  'function'` directly, so whatever composition mechanism this build picks
  must keep every currently-reachable-from-window identifier reachable the
  same way (see D-MOD2).
- **Module-level mutable state is not centralized.** Distinct `let`/`const`
  bindings for the same top-level scope: `inventory`, `selectedId`,
  `activeTab`, `activeRouteViewByEntry`, `interfaceSort`, `routeSort`
  (Inventory); `expandedGroups`, `activeRouteMemberByEntry` (also
  Inventory, declared later at line ~1509 rather than the file's top);
  `activeModule`, `activeConfigTab`, `configSelectedId`, `configFleetFilter`,
  `configHeaderExpanded`, `configSidebarOpen`, `configDevices` (Configuration
  + cross-module dispatch); `complianceSelectedSubjectId`,
  `complianceVendorFilter`, `complianceStatusFilter`,
  `complianceFrameworkFilter`, `COMPLIANCE_FRAMEWORKS`, `complianceSubjects`
  (Compliance). There is no single "shared state" bucket to move into
  `app_core` — each belongs to the module that owns its reads/writes; see
  D-MOD3 for why `app_core` should carry none of it.
- **Top-level executable code is interleaved with function definitions, not
  only at the end of the file.** Three distinct regions do real work at
  script-parse time, not inside a function:
  1. Lines ~1503–1510: `logicalEntries`/`inventoryRoots`/`inventory =
     flattenHierarchy(...)` — builds the Inventory module's entire working
     data set from the `rawData` global (declared in `templates/index.html`,
     outside `app.js` entirely) immediately after the functions it calls are
     defined.
  2. Lines ~2223–2238: theme bootstrap (`applyTheme(preferredTheme())`,
     `themeToggle` click listener) and Inventory-panel DOM wiring
     (`globalSearch`/`subnetSearch`/`vendorFilter`/`interfaceSearch`/
     `routeSearch` input listeners, tab click listeners, the initial
     `renderDeviceList(); switchTab(activeTab);` calls).
  3. Lines ~4853–4905: the report-wide wiring tail — module-nav click
     listeners, Overview's two "Open Configuration/Compliance" buttons,
     Configuration/Compliance filter listeners, the delegated
     `data-explain-toggle` click handler, Config-tab listeners, and the
     final full-module render sequence
     (`renderOverviewModule(); renderComplianceModule();
     renderDiscoveryModule(); renderExclusionsModule(); renderProjectPlan();
     renderConfigDeviceList(); renderConfigSelected();
     switchConfigTab(activeConfigTab); switchModule(savedModule());`).

  Any composition mechanism must preserve execution order exactly: region 1
  must run after Inventory's own functions are defined but does not depend
  on any other module; region 3 must run last, after every module's
  functions exist, because it calls into all of them.
- **The architecture doc's 7-file proposal (§5) has one real gap**: Overview
  (`inventoryOverviewStats`, `currentConfigurationFleet`,
  `deviceLifecycleFamilies`, `renderOverviewModule`, lines ~2423–2608) is
  not assignable to any of the seven listed files without either wrongly
  folding it into `inventory_ui.js` (it reads `configUiData` and
  `complianceUiData` directly, not just inventory state) or into
  `configuration_ui.js` (it also reads raw inventory stats and is a
  distinct module tab, not a Configuration sub-view). This build adds an
  eighth file, `overview_ui.js`, to the proposal — see D-MOD4.
- **A handful of functions are physically located under one module's
  heading but are not that module's concern**, because the file grew by
  physical proximity, not by owner:
  - `preferredTheme`/`applyTheme`/`toggleTheme` (lines ~2181–2220) sit
    between Inventory's route-table code and the `formatNumber`/`statusPill`
    helper block, but are report-wide chrome (the theme toggle button lives
    in the topbar, not any one module) — these belong in `app_bootstrap.js`.
  - `switchModule`/`savedModule` (lines ~2611–2665) sit physically among
    Overview/Configuration code but are the cross-module navigation
    dispatcher every module's render entrypoint is reached through — these
    belong in `app_bootstrap.js`, per the architecture doc's own description
    of that file ("navigation, report initialization, public facade").
  - `statusPill`/`metricCard`/`formatNumber`/`formatPercent`/`formatBytes`/
    `formatConfigTimestamp`/`statusTone`/`classificationLabel`/
    `categoryLabel` (lines ~2262–2349) are called from every feature
    module and belong in `app_core.js`, not wherever they happen to sit
    today (between Inventory and Overview).

## Design decisions

### D-MOD1 — file concatenation, not ES modules or a bundler

The report ships as one dependency-free HTML file, opened via `file://`,
email, or as a standalone artifact, with no server and no build pipeline
(`AI_START_HERE.md`; the architecture doc's own non-goals). `<script
type="module">` is rejected outright: Chrome/Firefox/Safari all block
`import`/module scripts from a `file://`-loaded document under their
same-origin/CORS module-loading rules, which would break the single most
important usage mode this product exists for. A bundler (esbuild/webpack/
Rollup) is rejected for the same reason `frontend_rendering_boundary`
rejected a per-render CSP nonce: it would be the first build step this
Python-only repository has ever needed, for a benefit (smaller source
files) available for free by simpler means. The chosen mechanism: `utils.
html_export.run_html_export` reads each split source file in a fixed
dependency order and concatenates their contents (each already valid,
already-tested JavaScript) into the exact same single string it inlines
into `__SCRIPT_PLACEHOLDER__` today. At runtime, in the browser, the result
is byte-for-byte the same flat top-level script it is today — this build
changes where the source lives on disk, never what the browser executes.

### D-MOD2 — no `window.SecurityExpert` namespace; keep implicit top-level globals

The architecture doc's §5 allows "a temporary `window.SecurityExpert`
namespace... at the composition boundary." This contract deliberately does
**not** introduce one. Reasoning: every one of the 169 functions is already
an implicit global today (no strict mode, no wrapper), the render harness
already depends on that fact directly (`window.switchModule`), and D-MOD1's
flat-concatenation mechanism does not change that — introducing a
namespace object now would be a real, visible behavior/API change to a
build whose entire premise is zero behavior change, for a benefit (explicit
public-surface signaling) this build's real enforcement tool (AC-3's static
ordering check, below) already delivers without one. If a future build adds
strict mode or per-module IIFE wrapping (a legitimate next step, e.g. to
stop cross-module state mutation outside its owner), *that* is the point to
introduce an explicit namespace for the genuinely public surface
(`switchModule` and whatever else the render harness / inline `<script>`
epilogue call) — not this one. Flagged as a named follow-up, not silently
dropped.

### D-MOD3 — `app_core.js` carries helpers only, no shared state bucket

The architecture doc describes `app_core.js` as owning "safe/escapeHtml,
formatters, shared state and DOM helpers." This contract narrows "shared
state": per the audit above, no state variable is actually read/written by
more than one feature module — each `let`/`const` belongs to exactly the
module whose render functions touch it. Inventing a shared-state bucket in
`app_core` for state nothing shares would recreate the undifferentiated
grab-bag this build exists to break up. `app_core.js` owns only: `safe`,
`escapeHtml`, `normalizedSource`, `vendorLabel`, `vendorTitle`,
`formatNumber`, `formatPercent`, `formatBytes`, `formatConfigTimestamp`,
`formatInventoryTimestamp`, `statusPill`, `metricCard`, `statusTone`,
`classificationLabel`, `categoryLabel` — pure functions with no module-level
state of their own, called from two or more other modules. A function
called by only one module belongs to that module, even if it looks generic
(e.g. `routeTypeBadge` is Inventory-only today and stays in
`inventory_ui.js`).

### D-MOD4 — add `overview_ui.js` as an eighth module

Per the audit finding above. `overview_ui.js` owns `inventoryOverviewStats`,
`currentConfigurationFleet`, `deviceLifecycleFamilies`,
`renderOverviewModule` and depends on `app_core.js` plus read-only access to
`inventory` (from `inventory_ui.js`), `configUiData`/`configDevices` (from
`configuration_ui.js`'s scope or the page-level const, whichever the
implementation step's precise trace finds — see AC-1), and
`complianceUiData` (page-level const). It must load after `inventory_ui.js`
and `configuration_ui.js` in the composition order; `compliance_ui.js` may
load before or after it since Overview only reads the page-level
`complianceUiData` const directly, not any Compliance-module function.

### D-MOD5 — module ownership table (amends architecture doc §5)

Line ranges are this session's verified read of the current file and are a
starting map for the implementation session, not a promise the exact same
lines survive unchanged (helper extraction may reorder within a module).

| File | Owns (representative, not exhaustive) | Depends on |
| --- | --- | --- |
| `app_core.js` | See D-MOD3's exact list | nothing (loads first) |
| `app_bootstrap.js` | `preferredTheme`/`applyTheme`/`toggleTheme`, `switchModule`/`savedModule`, the theme + module-nav + Overview-open-button top-level wiring (regions 2's theme half and region 3 in the audit above) | `app_core.js`; calls into every other module's render entrypoints, so loads **last** |
| `inventory_ui.js` | Lines ~37–1683 (`normalizedSource` … `routeDiffRows`) plus `deviceCardHtml`, `renderDeviceList`, `renderSelected`, `sortRows`, `matrixAddressHtml`, `renderClusterInterfaceMatrix`, `renderInterfaceTable`, `renderRouteMemberTabs`, `renderRouteTable`, `switchTab`, `routeTypeBadge`; owns `inventory`/`selectedId`/`activeTab`/`activeRouteViewByEntry`/`interfaceSort`/`routeSort`/`expandedGroups`/`activeRouteMemberByEntry`; region 1 and region 2's Inventory-DOM-wiring half of the audit's top-level code | `app_core.js`; reads the page-level `rawData` const |
| `overview_ui.js` | See D-MOD4 | `app_core.js`, `inventory_ui.js`, `configuration_ui.js`; reads page-level `configUiData`/`complianceUiData` |
| `configuration_ui.js` | Lines ~2668–3827 (`configDeviceCounts` … `renderConfigSelected`/`switchConfigTab`); owns `configSelectedId`/`configFleetFilter`/`configHeaderExpanded`/`configSidebarOpen`/`configDevices` | `app_core.js`; reads page-level `configUiData` |
| `compliance_ui.js` | Lines ~3851–4491 (`complianceStatusTone` … `renderComplianceModule`) plus `renderCryptoPostureCard`; owns `complianceSelectedSubjectId`/`complianceVendorFilter`/`complianceStatusFilter`/`complianceFrameworkFilter`/`COMPLIANCE_FRAMEWORKS`/`complianceSubjects` | `app_core.js`, `configuration_ui.js` (`complianceSourceDevice` reads `configDevices`); reads page-level `complianceUiData`/`cryptoUiData` |
| `discovery_ui.js` | Lines ~4521–4687 (`lifecycleStateTone`, `jobStatusTone`, `renderDiscoveryModule`, `renderExclusionsModule`) | `app_core.js`; reads page-level `discoveryUiData`/`exclusionsUiData` |
| `project_plan_ui.js` | Lines ~4494–4518 (`roadmapStatusTone`, `roadmapStatusLabel`, `roadmapProgress` — physically placed just above the Discovery functions, but grep-verified as used only by `renderProjectPlan`, never by `renderDiscoveryModule`/`renderExclusionsModule`) plus lines ~4690–4848 (`renderProjectPlan`) | `app_core.js` |

**Composition order** (dependency order, first-loaded first):
`app_core.js` → `inventory_ui.js` → `configuration_ui.js` →
`compliance_ui.js` → `discovery_ui.js` → `project_plan_ui.js` →
`overview_ui.js` → `app_bootstrap.js`.

**Cross-reference resolved during contract-freeze** (grep-verified, not
left open): `roadmapStatusTone`/`roadmapStatusLabel`/`roadmapProgress` are
physically adjacent to the Discovery functions but exclusively consumed by
`renderProjectPlan` — every call site (lines 4703–4835) is inside that one
function. They belong to `project_plan_ui.js`, the same
physical-location-versus-ownership mismatch already called out for the
theme functions and `switchModule` in the audit findings above.

## Acceptance criteria

- **AC-1** `static/app.js` no longer exists; its full content is
  distributed across the eight files in D-MOD5 with zero functions
  dropped, zero functions duplicated, and zero behavior change. Every
  function/state-variable placement decision that differs from a naive
  physical-location split is because D-MOD3/D-MOD4/D-MOD5 said so, not
  improvised mid-extraction.
- **AC-2** `utils/html_export.py` composes the eight files in the fixed
  order from D-MOD5 into the same single `__SCRIPT_PLACEHOLDER__` fill it
  performs today — one `read_text_file` per module file, concatenated with
  a newline separator, no other change to `_fill_template`/`_script_json`/
  the JSON-payload placeholders.
- **AC-3** A new static regression test enforces the dependency order: for
  each module file, no top-level identifier it references (by simple
  regex/AST-lite scan, matching this repo's existing style of pragmatic
  regex-based checks rather than a full JS parser dependency) is defined
  by a module that loads after it in D-MOD5's order. This is the tooling
  D-MOD2 points to instead of a namespace object — cheap to build given the
  ownership table already exists, and it is the one new thing this build
  adds that today's flat file could never have had.
- **AC-4** The rendered report is byte-identical to the pre-split baseline
  on every fixture this repo already has (`tests/fixtures/uitest`,
  `scripts/render_sample.py`'s empty-state path), except for whatever
  incidental whitespace the concatenation join introduces at module
  boundaries (acceptable; the executed JavaScript is unaffected either
  way) — no payload shape, markup, or CSS changes anywhere.
- **AC-5** Full existing suite green, including
  `tests/test_html_render_harness.py`'s real-Chromium and static-JSON
  checks and `tests/test_frontend_rendering_boundary.py`'s hostile-label
  checks (this build must not silently reopen the escaping boundary that
  contract just closed).
- **AC-6** Repository privacy gate PASS/0.
- **AC-7** A human/automated real-Chromium open of the composed report
  confirms every module/tab still works, matching
  `frontend_rendering_boundary`'s own "CSP violations fail silently"
  caution — a concatenation-order mistake is the equivalent silent-failure
  risk here (a module referencing something not yet defined throws a
  `ReferenceError` that may or may not surface as a visible break
  depending on where in the render sequence it lands).

## Implementation plan

1. **Extract `app_core.js` first** (D-MOD3's exact list) — every other
   module depends on it, so it must exist and be verified (each function
   moved verbatim, no behavior change) before any other extraction.
2. **Extract the five feature modules** (`inventory_ui.js`,
   `configuration_ui.js`, `compliance_ui.js`, `discovery_ui.js`,
   `project_plan_ui.js`) per D-MOD5, in any order relative to each other —
   verify each in isolation is syntactically complete (references only
   `app_core.js` + page-level consts + itself) before moving to the next.
3. **Extract `overview_ui.js`** (D-MOD4) — depends on three prior modules,
   so it comes after them.
4. **Extract `app_bootstrap.js`** last — it is the only file allowed to
   reference every other module's render entrypoints, and its top-level
   wiring code must run only once everything else exists.
5. **Wire the composer** (AC-2) into `utils/html_export.py`; update
   `SCRIPT_FILE`/`script_file` from a single path to the eight-file list in
   D-MOD5's order.
6. **AC-3's static ordering test**, then AC-4/AC-5/AC-6/AC-7 validation, in
   that order — the ordering test is cheap and will catch an extraction
   mistake before the expensive full-suite/render-harness runs do.
7. **Project metadata**: `project/build_history.json`,
   `project/backlog.json` (`codebase_modularization` → in_progress, since
   this build is the frontend half only — the backend half stays `planned`
   under the same id per this contract's scope), `CURRENT_STATE.md`, this
   doc's Status → `IMPLEMENTED`, `AI_HANDOVER.md`.

## Validation and merge gate

- Full suite one-shot: `py -m pytest -q > pytest_result.log 2>&1`.
- Repository privacy gate PASS/0.
- Render harness green — mandatory (this build touches `static/`,
  `utils/html_export.py`, and by extension every rendered report).
- Real-browser confirmation per AC-7.

## Risks

- **Silent `ReferenceError` from a wrong composition order** — the exact
  failure mode `frontend_rendering_boundary` warned CSP violations share:
  a script that throws partway through can leave some event listeners
  wired and others not, looking superficially fine. AC-3's static check
  exists specifically to catch this before it ever reaches a browser.
- **D-MOD5's line ranges will drift slightly during extraction** (e.g. a
  helper only meaningfully used by one caller might get inlined, or a
  physically-misplaced function found during extraction that this
  contract's read missed) — expected and fine, provided the *ownership
  rule* (which module's concern is this) is followed rather than
  preserving today's accidental physical position.
- **Scope creep back into `frontend_rendering_boundary` or a new feature**
  — resist "while I'm in here" changes to escaping, payload shape, or UX;
  file-layout only.
- **The backend half of `codebase_modularization`** (same backlog id) is
  explicitly not this build — do not let this contract's implementer treat
  a clean frontend split as license to start on `main.py`/collector
  splitting in the same session without a separate contract for that half.

## Rollback

Revert to the single `static/app.js` file and the single-`read_text_file`
call in `utils/html_export.py`; no stored data, schema, payload shape, or
runtime artifact is touched by this build, so rollback has no migration
concern — it is a pure source-layout revert.

## Definition of done

1. AC-1 … AC-7 all green.
2. Full suite at or above the pre-build baseline; privacy gate PASS/0;
   render harness green; the real-browser check documented as done (and
   whether it was literally human-interactive or automated real-Chromium,
   per `frontend_rendering_boundary`'s own precedent for saying so
   honestly rather than implying more than was actually verified).
3. Status → `IMPLEMENTED`.

## Implementation deviations (2026-09-01)

Both are "a physically-misplaced function this contract's read missed", which
the Risks section explicitly anticipated — resolved by the ownership rule, not
by preserving an accidental position.

1. **`currentConfigurationFleet` → `configuration_ui.js`**, not `overview_ui.js`
   as D-MOD4 listed. It is a one-line pure accessor over the page-level
   `configUiData` const, and `configuration_ui`'s own `checkpointCoverageHtml`
   calls it as well as `overview_ui`'s `renderOverviewModule`. It moves to the
   earlier-loading of its two callers; `overview_ui` (which loads after
   `configuration_ui`) still reaches it. `complianceSparkline` /
   `complianceTrendChip` are the mirror case the contract *did* anticipate —
   shared by Overview and Compliance, placed in `compliance_ui` (earlier
   loader).

2. **`switchModule` / `savedModule` stay in `app_bootstrap.js`** (loads last)
   per D-MOD5, even though `compliance_ui.renderComplianceCoverageOverview`
   references `switchModule`. Every such call site is inside a deferred
   `addEventListener` callback that fires long after all eight files have
   loaded (and after region-3 already ran in the pre-split file too), so the
   forward reference is runtime-safe. AC-3's static check carries an explicit,
   reasoned `NAVIGATION_PUBLIC_SURFACE` carve-out for exactly these two names —
   this is the "genuinely public surface" D-MOD2 said the ordering check would
   stand in for instead of a `window.SecurityExpert` namespace.

Order-of-execution note: the pre-split file ran `applyTheme(preferredTheme())`
before the inventory-panel bootstrap; in the composed script it runs later
(in `app_bootstrap`, last). The whole report is one synchronous `<script>`
with a single paint at the end, so this is unobservable — confirmed by the
render harness (no console errors, all modules/tabs switch).

## Next movement / model

This contract (scope + audit + design) is **frozen** (2026-08-31, `Sonnet 5,
normal` — the module boundaries follow directly from re-reading code this
session already fully read for `frontend_rendering_boundary`, and the one
real design call (D-MOD2, no namespace object) follows from a concrete,
statable reason rather than a novel security/architecture judgment; extended
thinking would not have changed the outcome here). Implementation is scoped
for a fresh session, also at **`Sonnet 5, normal`** — mechanical extraction
against a concrete ownership table, with AC-3's static check doing the
verification work a human re-review of a large mechanical diff otherwise
would. The one place to slow down is the AC-1 completeness check (zero
functions dropped, zero duplicated) — mechanical, not novel-design, so
still `normal` reasoning, just careful rather than fast.
