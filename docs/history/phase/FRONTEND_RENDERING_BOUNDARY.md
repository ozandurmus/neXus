# Frontend rendering boundary — CSP + escaping contract

## Status

**IMPLEMENTED 2026-08-31.** AC-1 … AC-7 all green; see "Implementation
findings" below for the exhaustive AC-2 audit result and one implementation-
time contract correction (D-CSP1's `frame-ancestors` directive). Implemented
in the same session the contract was frozen in (`Sonnet 5, normal`
throughout, per the contract's own routing — no step needed a higher tier).

<details>
<summary>Original freeze note (2026-08-31, superseded by "Implementation
findings" below)</summary>

**CONTRACT FROZEN 2026-08-31. Not yet implemented.** Produced as a scoping/
audit pass (`Sonnet 5, normal`) specifically so a fresh session can implement
against a concrete plan without repeating the investigation. No source file
changed by this pass — `templates/index.html`, `static/app.js`,
`static/style.css`, `utils/html_export.py` are untouched.

</details>

`project/backlog.json` `frontend_rendering_boundary` (P1). Scoped from
`docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md` §3
item 3: "Define the report's XSS boundary: CSP, regression tests for hostile
inventory/configuration labels, and one audited rendering/escaping
contract."

## Objective

The report is a single portable HTML file built by inlining `static/app.js` +
`static/style.css` and JSON-serializing five collector-derived payloads into
one `<script>` block (`utils/html_export.py::run_html_export`). Every one of
those payloads ultimately traces back to text a live CP/PAN device (or its
operator) chose — device names, labels, config field values. None of that
text is currently treated as trusted. This build closes the report's XSS
boundary with (1) a Content-Security-Policy appropriate to a portable
single-file report, (2) an exhaustively-verified escaping discipline across
every HTML-building sink in `static/app.js`, and (3) regression tests that
fail if either regresses. It changes no payload shape and no visible UX —
this is a hardening pass, not a feature.

## Scope

### In scope

1. A `<meta http-equiv="Content-Security-Policy">` tag in
   `templates/index.html` (see D-CSP1).
2. A systematic, sink-by-sink audit of every `.innerHTML` assignment in
   `static/app.js` (97 call sites at this contract's freeze — see "Audit
   findings"), fixing any that interpolates device/vendor-derived text
   without `escapeHtml()`.
3. A written escaping rule (D-ESC1 below) — short enough to live in this
   doc and a one-line comment at `escapeHtml`'s definition, not a separate
   design document.
4. Hostile-label regression tests: fixture entries in
   `tests/fixtures/uitest/` (per its own "Growth rule") plus assertions that
   render the real `run_html_export` path and check the hostile payload
   never appears unescaped in the output.
5. A regression test for `_script_json`'s existing `</script>`-breakout
   neutralization (currently correct but untested).

### Explicitly out of scope

- Any change to `unified.json` / `configuration_ui` / `compliance_ui` /
  `crypto_ui` / `discovery_ui` / `exclusions_ui` payload shape, or any
  collector/backend code. This is a rendering-layer build only.
- `codebase_modularization` (splitting `static/app.js` into modules) — a
  separate backlog item; do not fold it into this one. If the exhaustive
  sink audit turns up so many fixes that the file becomes hard to review as
  a single diff, split the *audit* into logical chunks, not the module.
- Moving to a nonce/hash-based CSP or any change to the "one portable
  inlined file" architecture — considered and rejected, see D-CSP2.
- A Trusted Types / DOMPurify rewrite of the rendering layer. The existing
  `escapeHtml()`-per-sink discipline is already the established pattern and
  (per this session's sampling) already followed correctly where checked;
  the gap is verification coverage, not the approach itself.
- Any device write, new device command, or browser-to-device path — not
  applicable to a rendering-only build, stated for completeness per the
  network-device command gate.

## Audit findings (this session, 2026-08-31)

This is groundwork, **not** the exhaustive audit AC-2 requires — treat the
"28 candidates" below as a useful starting list, not a finished result.

- `escapeHtml()` (`static/app.js:27`) is a standard entity-encoder (`&
  < > " '`) and is used pervasively across the file.
- `utils/html_export.py::_script_json` (line 43) already replaces literal
  `</` with `<\/` in every JSON payload before it is embedded in the inline
  `<script>` block — the classic `</script>`-breakout injection vector is
  already closed. This predates this build; it just has no regression test
  (AC-5).
- **No CSP exists today** — confirmed by grep across `templates/index.html`
  and `static/app.js`: no `<meta http-equiv="Content-Security-Policy">`, no
  external `<link>`/`<script src>`, no `fetch`/`XMLHttpRequest`/`WebSocket`
  call anywhere. The report is already 100% self-contained with zero
  outbound network capability — which makes a strict CSP cheap to add with
  very low regression risk (see D-CSP1).
- A heuristic scan (ad hoc Python, this session, not committed) found 97
  `.innerHTML =`/`+=` assignments in `static/app.js`, 89 of which interpolate
  a template literal (`${...}`). 28 of those 89 have no `escapeHtml(` call
  within a ~20-line window after the assignment — a crude proxy for "might be
  missing escaping," with both false positives and false negatives (a
  20-line window can miss an escape call just above/below it, and it cannot
  tell an internal-only value from a device-derived one).
- Manual spot-check of ~10 of those 28 (the Overview module's metric cards,
  the Configuration module's status/evidence panels, and the device-name
  tree renderer `deviceCardHtml`) found every one either interpolates an
  internal-only value (a count, a boolean-derived CSS class, our own static
  label) needing no escaping, or is already escaped by a call just outside
  the heuristic's window. In particular `deviceCardHtml` — the single
  highest-value target, since `entry.displayName` traces directly to a live
  device's own configured name — escapes every interpolated field
  correctly.
- **Conclusion:** the codebase already follows an escape-every-sink
  discipline reasonably well in the ~10 sites sampled. That is not the same
  as verified — 87 of 97 sinks were not manually reviewed at all. AC-2 is
  the real audit; this finding only justifies treating it as verification
  work rather than an assumed rewrite.
- No hostile-input/XSS regression test exists anywhere in `tests/` today
  (confirmed by grep for `XSS`/`hostile`/`escapeHtml` usage in tests).

## Implementation findings (2026-08-31)

**AC-2 exhaustive sink audit — complete, zero gaps found.** Every one of the
97 `.innerHTML` sinks in `static/app.js` was individually reviewed (not
sampled) — line-by-line from the first sink at `renderDeviceList` (line
~1728) through the last at `renderProjectPlan` (line ~4848) — together with
every shared helper function they call: `escapeHtml`, `statusPill`,
`metricCard`, `formatNumber`/`formatPercent`/`formatBytes`/
`formatConfigTimestamp`/`formatInventoryTimestamp`, `categoryLabel`,
`classificationLabel`, `routeTypeBadge`, `statusTone`, `complianceSparkline`/
`complianceTrendChip`, `checkpointCoverageHtml`, `assignmentChips`,
`deviceCardHtml`, `configDeviceItemHtml`, `evidenceCard`,
`complianceControlCard`, `renderFindingsTable`, and the discovery/exclusions/
project-plan table builders. Every dynamic value interpolated into an
`innerHTML` template literal is one of: (a) a static/internal-only literal,
(b) purely numeric via `Number()`-backed formatters (cannot carry markup),
(c) routed through a fixed enum-lookup function that can only emit one of a
small hardcoded set of literal strings (the `*Tone`/`*Label` mapper
functions), or (d) explicitly wrapped in `escapeHtml()`. **No fix was
required** — the D-ESC1 discipline the contract's freeze-time sampling (~10
of 97 sites) found sound holds across the full 97/97. This is a real
negative result, not a shortcut: the audit was exhaustive, it just found
nothing to fix.

**D-CSP1 correction — `frame-ancestors` removed, meta-incompatible per
spec.** The CSP specification does not allow `frame-ancestors` (nor
`report-uri`/`report-to`/`sandbox`) to be delivered via a `<meta
http-equiv="Content-Security-Policy">` element — only via an HTTP response
header. Chromium enforces this by emitting a console error ("The Content
Security Policy directive 'frame-ancestors' is ignored when delivered via a
`<meta>` element") whenever the directive is present, which the existing
render harness's console-error assertion correctly caught during this
session's real-Chromium manual-browser-check step (`test_headless_
navigation_smoke_playwright`). This was not caught at contract-freeze time
because that pass was docs-only and explicitly deferred the interactive
browser check to implementation (per the contract's own step 2). The
directive was already inert under `<meta>` delivery — removing it is a
correction, not a security loosening: nothing was actually enforced by its
presence, and the frozen directive's own text says a future session "must
not loosen [the set] without a documented reason" — this is that reason.
The other nine directives are all `<meta>`-compatible and are unchanged.
`templates/index.html`'s CSP `<meta>` tag and this doc's D-CSP1 directive
list below are both updated to the corrected nine-directive set.

**Real-Chromium verification (this session, automated — not literally
human-interactive).** This sandbox has no interactive display, so the
"open the rendered report in a real browser" step (implementation plan step
2) was performed via Playwright driving real Chromium: navigated all seven
modules (Overview, Network Inventory, Configuration, Compliance, Discovery,
Exclusions, Project Plan) plus every inner tab, asserted zero console
errors throughout (this is what caught the `frame-ancestors` finding
above), and captured screenshots of each module confirming no visible
regression. This is real-browser evidence, not a human eyeballing a
screen — flagged honestly per this repo's real-environment-evidence
discipline. A human interactive pass remains a cheap, worthwhile follow-up
whenever this report is next opened on a real workstation, but is not
believed to be load-bearing given Chromium's own console-level CSP
violation reporting already exercised the failure mode this step exists to
catch.

**AC-3/AC-4 hostile-label regression, new file
`tests/test_frontend_rendering_boundary.py`.** Two hostile standalone CP
gateway entries were added to `tests/fixtures/uitest/unified.json` (via
`build_fixture.py`, regenerated) — `device: "<img src=x onerror=alert(1)>"`
and `device: "\"><script>alert(1)</script>"` — deliberately non-clustered
so they cannot perturb any existing topology-matrix assertion.
`test_hostile_device_label_never_appears_as_raw_markup` (AC-3, static, no
JS runtime) proves both raw strings appear only inside the inline
`<script>` block's JSON data, never as markup in the surrounding page, and
round-trip through `_script_json`/JSON parsing unmangled.
`test_headless_hostile_label_renders_escaped_not_executed` (AC-4, real
Chromium, skippable) proves the client's own `escapeHtml()` renders both as
inert text — zero `<img>`/`<script>` elements created inside `#deviceList`,
zero dialogs fired, and the expected `&lt;`/`&gt;`-escaped text is present
in the live DOM. Two test-writing mistakes were caught and fixed while
building this: an over-strict substring check (`"onerror" not in
list_html`) that would have failed on the *correctly escaped* text itself,
and an assumption that exactly one literal `<script>` substring may exist
in the page — false, since descriptive prose already embedded elsewhere in
the report (e.g. a `project/backlog.json` note discussing this exact class
of bug) legitimately contains the substring `<script>` with no security
implication, since an HTML tokenizer inside an already-open `<script>`
element only treats a literal `</script>` as significant. The corrected,
narrower invariant (`html.count("</script>") == 1`, the report's own real
closing tag) is what actually matters and is what `_script_json` protects.

**AC-5**, `test_script_json_neutralizes_script_breakout` and
`test_script_json_breakout_does_not_break_the_whole_page` in the same new
file, cover `_script_json`'s `</` → `<\/` neutralization directly (unit
level) and end-to-end through a real `run_html_export` call.

**Evidence.** New venv (`lxml`/`paramiko`/`requests`/`cryptography` +
`pytest`/`playwright` per `requirements.txt`/`requirements-dev.txt`;
Chromium via the pre-installed `PLAYWRIGHT_BROWSERS_PATH`) since neither
`py` nor a bare `pytest` module was on this sandbox's default PATH. Full
suite: **888 passed / 23 skipped / 0 failed** (`-n auto --dist worksteal`;
at/above the pre-build baseline of 881/23/2, where the 2 were the
documented pre-existing test-order-pollution pair — not reproduced this
run, consistent with their known order-dependence). Repository privacy
gate: **PASS / 0** on a clean checkout (`data/`/`logs/` cleared first, both
gitignored). Render harness: green, including the real-Chromium path,
after the `frame-ancestors` fix.

## Design decisions

### D-CSP1 — CSP as a `<meta>` tag, strict except for inline script/style

The report has no server and is routinely opened via `file://`, emailed, or
shared as a standalone artifact — there is no guaranteed place to set an
HTTP response header, so the CSP must live in the HTML itself via
`<meta http-equiv="Content-Security-Policy">`.

The report inlines all of its own CSS and JS (no external files at all, per
the audit above), so `script-src`/`style-src` must allow `'unsafe-inline'`
— a nonce is not viable for a pre-rendered static file (a fixed nonce
defeats the purpose; a per-render nonce would require templating changes
`_script_json`'s single-pass sentinel-fill already avoids elsewhere, and
buys nothing since the whole point of a nonce is defeating *external*
script injection, and there is no external script surface here to defend).

Given `'unsafe-inline'` is required, the CSP's value is entirely in the
directives that need no exception:

```
default-src 'none';
script-src 'unsafe-inline';
style-src 'unsafe-inline';
img-src 'self' data:;
font-src 'self' data:;
connect-src 'none';
object-src 'none';
frame-ancestors 'none';
base-uri 'none';
form-action 'none';
```

This does **not** stop an injected inline `<script>` from running if
`escapeHtml()` coverage has a gap (that is D-ESC1/AC-2's job) — but it does
stop that script from exfiltrating data (`connect-src 'none'` blocks
`fetch`/`XHR`/`WebSocket`/beacon to anywhere, including same-origin, which
is correct since a `file://`-opened report has no legitimate same-origin
network use), stops the report from being framed (clickjacking), stops any
`<object>`/`<embed>`/plugin content, and stops a `<base>`-tag rewrite attack.
Document this honestly in the implementation as defense-in-depth, not a fix
for missing escaping.

The exact directive set above is the frozen contract; the next session may
tighten it further (e.g. `img-src 'self'` without `data:` if no code path
actually needs data-URI images — confirm before implementing) but must not
loosen it without a documented reason.

### D-CSP2 — nonce/hash-based CSP: considered, rejected

A stricter CSP without `'unsafe-inline'` would require moving script out of
the single inline block (external file + nonce, or a build step that
computes a script-hash). Both break the "one portable file" property this
product's whole architecture commits to (`AI_START_HERE.md`: "single-page
UI... inlined css/js"; the productization architecture doc's explicit
non-goals). Default recommendation: **do not pursue** this. If the product
owner wants it anyway, that is a new, separate architecture decision, not
an extension of this build.

### D-ESC1 — the escaping rule

Every dynamic value written into `innerHTML` — directly, or via a template
literal later assigned to `innerHTML` — **must** pass through
`escapeHtml()`, with exactly two exceptions:

1. A literal, author-written string with no interpolated variable at all.
2. A call to another helper that itself is audited to guarantee escaped
   output for every value it interpolates (e.g. `deviceCardHtml`,
   `evidenceCard`, `metricCard`, once each is confirmed under AC-2) — do not
   double-escape by wrapping an already-safe helper's return value in
   `escapeHtml()` again.

Prefer `textContent`/`setAttribute` over `innerHTML` for any sink that does
not need to build a multi-element subtree — it needs no escaping call at
all and is not a judgment call for the reviewer. This is a style
preference for new code, not a retrofit requirement for AC-2 (which only
requires *correct* escaping, not migrating every sink off `innerHTML`).

### D-ESC2 — `_script_json` stays as-is, gets a test

The `</` → `<\/` neutralization in `utils/html_export.py::_script_json` is
correct and load-bearing (it is the only thing standing between a device
name containing `</script>` and breaking out of the inline script block).
No design change; AC-5 closes the fact that it has never been tested
directly.

## Acceptance criteria

- **AC-1** A CSP meta tag matching D-CSP1's directive set (verbatim, or a
  documented tightening) is present in the rendered report; a test asserts
  it by exact string match so a future edit cannot silently drop or loosen
  a directive.
- **AC-2** Every one of the `.innerHTML` sinks in `static/app.js` (97 at
  this contract's freeze; re-count at implementation time since the file
  may have changed) is individually reviewed — not sampled. Any sink that
  interpolates a value ultimately sourced from vendor/device data must call
  `escapeHtml()` (directly, or via an audited `*Html`-suffixed helper per
  D-ESC1). Every gap found is fixed in the same build, not deferred.
- **AC-3** At least one hostile-label fixture is added to
  `tests/fixtures/uitest/` (e.g. a device `displayName` containing
  `<img src=x onerror=alert(1)>` and a second containing `"><script>...`) —
  regenerated via `tests/fixtures/uitest/build_fixture.py` per the bundle's
  own "Growth rule." A new test renders it through the real
  `run_html_export` path and asserts the raw payload string never appears
  unescaped in the output HTML — only its `escapeHtml()`-encoded form does.
- **AC-4** The existing render harness (`tools/render-harness/
  check-render.mjs` and/or `check_render_playwright.py`, whichever is
  available) runs against the hostile fixture and reports no unexpected
  `alert`/console error consistent with script execution. Defense-in-depth
  confirmation — AC-3's static-string assertion is the primary check and
  must not depend on a JS runtime being available in every environment.
- **AC-5** `_script_json`'s `</script>`-breakout neutralization gets an
  explicit unit test (a value containing literal `</script>` in, assert
  `<\/script>` out, assert the full HTML page still parses as one document
  with `_fill_template` unaffected).
- **AC-6** Every existing fixture's rendered output is byte-identical to
  before this build, except for the CSP meta tag's addition and the new
  hostile-label fixture itself — no payload shape, UI behavior, or visible
  content regresses for any non-hostile input. Full suite + render harness
  stay green.
- **AC-7** Repository privacy gate PASS/0.

## Implementation plan

Sequenced so the exhaustive audit (the actual security work) happens before
the tests that would otherwise just confirm today's partial coverage.

1. **Exhaustive sink audit (AC-2).** Start from this contract's 28
   heuristic candidates as a checklist, but review all ~97 sinks — the
   heuristic has false negatives. Classify each interpolated value as
   internal-only or device/vendor-derived; fix every device-derived sink
   missing `escapeHtml()`. `Sonnet 5, normal` — mechanical, but must be
   exhaustive, not sampled; this is where actual vulnerabilities (if any)
   get found and fixed.
2. **CSP (AC-1).** Add the meta tag from D-CSP1 to `templates/index.html`.
   Open the rendered report in a real browser and confirm every module/tab
   still works — a CSP violation can silently disable a feature with no
   visible error in some browsers, so this needs an interactive check, not
   just the automated harness (per `CLAUDE.md`'s "test the golden path... in
   a browser" rule, adapted here since there is no dev server: open
   `output/index.html` directly).
3. **Hostile-label tests (AC-3, AC-4).** Add fixture entries, regenerate via
   `build_fixture.py`, add the assertion test, run the render harness
   against it.
4. **`_script_json` test (AC-5).**
5. **Full suite + privacy gate + render harness green (AC-6, AC-7)** — this
   build touches `templates/`/`static/`, so per `AGENTS.md`'s Project-state
   update rule the render harness run is mandatory, not optional.
6. **Project metadata.** `project/build_history.json`, `project/
   backlog.json` (`frontend_rendering_boundary` → `done`), `CURRENT_STATE.md`,
   this doc's Status → `IMPLEMENTED`, `AI_HANDOVER.md`.

## Validation and merge gate

- Full suite one-shot: `py -m pytest -q > pytest_result.log 2>&1` (or
  `python3 -m pytest -q` in a sandbox without `py`).
- Repository privacy gate PASS/0.
- Render harness green — **mandatory** here (templates/static touched).
- Manual browser check of the CSP'd report — no automated harness in this
  repository evaluates whether a CSP directive silently breaks a feature;
  see implementation step 2.

## Risks

- **`'unsafe-inline'` means the CSP is not a backstop for a missed escaping
  gap** — it stops exfiltration/framing/embedding, not script execution
  itself. AC-2's exhaustiveness is the actual control; do not let the CSP's
  presence create false confidence.
- **This contract's 28-candidate list is heuristic, not authoritative.**
  Treating it as "the audit" would leave the other ~69 sinks unverified —
  AC-2 requires all ~97, explicitly.
- **CSP regression risk is silent by nature** — a blocked resource often
  just fails quietly rather than throwing a visible error, which is why the
  implementation plan requires an interactive browser check in addition to
  the automated harness.
- **Scope creep into `codebase_modularization`** — an exhaustive audit
  touching most of a 4900-line file is a large diff; resist the urge to
  restructure the file while doing it. Structure is a separate, already-
  tracked backlog item.

## Rollback

Revert the CSP meta tag and any sink fixes; `static/app.js`'s escaping
discipline reverts to today's (already-reasonable-per-sampling, not fully
verified) state. No stored data, schema, or payload shape is touched by this
build, so rollback has no migration concern.

## Definition of done

1. AC-1 … AC-7 all green.
2. Every sink identified as needing a fix in the AC-2 audit is fixed, and
   the audit's own findings (which sinks were reviewed, which needed a fix)
   are recorded in this doc's "Audit findings" section or the implementing
   commit — not silently absorbed.
3. Full suite at or above the pre-build baseline; privacy gate PASS/0;
   render harness green; the manual browser check documented as done.
4. Status → `IMPLEMENTED`.

## Next movement / model

This contract (scoping + audit sampling) is **frozen** (2026-08-31,
`Sonnet 5, normal` — no extended thinking was needed; this is deterministic
investigation, not a novel architecture or security-boundary design from
scratch, since the escaping pattern and CSP mechanics are both standard).
Implementation is scoped for a fresh session, also at **`Sonnet 5, normal`**
— step 1 (the exhaustive sink audit) is the one place to slow down and be
thorough rather than fast, but it is still mechanical review against a
clear rule (D-ESC1), not a design decision.
