# GOV.ORCH.4-C — Table-first Material 3 Workbench navigation

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-15.** Revision
`01863714f087d97b782bfd66033f2d5445114a85` was reviewed and approved through
canonical relay decision sequence 5. This contract is implementation authority
only for the successor named in §7.

## 1. Amendment and scope

Upon PO approval and freezing, this amendment replaces GOV.ORCH.4 §3.5's
Kanban-first default and AC-12's presentation acceptance with §§2–7 below.
GOV.ORCH.4 §3.4's transport remains compatible; additive projection fields
may supply classification evidence without renaming existing fields.
All other clauses, including token handling and the authentication boundary,
remain unchanged. GOV.ORCH.4-A's requested-model estimate and GOV.ORCH.4-B's
external-participant health semantics remain unchanged.

The approved direction is a table first, Active / Needs you / Archive filters,
movement/objective search, details below the list, and the existing UI2 M3
semantic theme bridge. Kanban may be a bounded secondary view only.
This movement delivers the contract, not Workbench code or browser tests.
The deployment/NetworkPolicy baseline was withdrawn by relay correction seq 3;
it is not authority and supplies no requirement here.

Out of scope: retention/storage architecture, price values, framework rewrite,
new execution permissions, network commands/vendor semantics, host/device
access, deployment inspection or changes, raw log/credential exposure.

## 2. Table-first navigation

- On authenticated first load, show **Active** in a semantic HTML table.
  Provide native buttons for **Active**, **Needs you**, **Archive**, with the
  selected filter exposed programmatically, plus a labelled search input.
- Search is a literal, case-insensitive substring of movement ID or objective;
  IDs remain opaque and unchanged. Search and filter state survive polling and
  movement selection. Empty results say whether the filter or search is empty.
- Visible row information: movement ID and objective; labelled classification
  and health; provider, observed/requested model and effort; process and stage;
  duration and idle evidence; input/cache-read/output tokens and cache ratio;
  labelled cost; PR/state and verification when present. Missing evidence is
  visibly unavailable, never synthesized as zero, success or completion.
- Default order is movement ID for Active/Needs you, newest terminal timestamp
  first for Archive, with movement ID as deterministic tie-breaker and missing
  timestamps last. No new sorting dependency or preference store is required.
- A native movement button selects details **below** the table. Preserve Summary,
  Traffic, Changes, Evidence, Usage and the existing controlled Log surface,
  section-open memory, pending decisions, rejection, routing and message delivery
  states. No automatic selection change on refresh, filtering or search.
- If selection leaves the filter, keep its details with an explanatory label and
  a Close button. If the movement is no longer available, show that condition;
  do not substitute a different movement or erase its unsent draft.
- Optional Kanban is a session-only view toggle for Active/Needs you. It consumes
  the same classified rows, search and selection; no drag/drop, writes, alternate
  classifier or independent counts. Omitting it fully satisfies this contract.

## 3. One classification and honest counts

Use one shared, deterministic classification projection for table, optional
Kanban, filter counts and status summary. Derive it from one refresh snapshot;
never combine `/api/board` rows with independently sampled `/api/movements`
counts. Polling cadence and health derivation do not change.

Preserve the existing seven health values and precedence. Classification is a
presentation projection, not a replacement health enum or operational outcome.
The successor supplies existing phase/terminal-outcome evidence additively to
board rows where necessary; a missing field is not proof of cancellation.

| First matching evidence | Classification | Filter membership |
| --- | --- | --- |
| Explicit cancelled phase or cancelled terminal outcome | Stopped | Archive |
| Existing closed/done evidence | Done | Archive |
| Existing failed health | Failed | Active, Needs you |
| Existing awaiting_po health | Awaiting you | Active, Needs you |
| Existing exited_without_close health | Exited without close | Active, Needs you |
| Existing silent health | Silent | Active, Needs you |
| Existing handed_over health | Handed over | Active |
| Existing integration stage | Integration | Active |
| Existing healthy health | Running | Active |
| Otherwise | Unknown | Active |

A closed/terminal record with unavailable outcome is labelled Done with
outcome unavailable, never guessed Stopped or successful integration.
Stopped must never count as Running, Failed, Silent or Needs you; a missing
PID alone never establishes Stopped or Handed over. Handed over does not use
idle duration as a stuck signal; preserve the latest-commit evidence of
GOV.ORCH.4-B without inventing progress.

Active includes Needs you, so their counts overlap intentionally. Filter
counts describe unique loaded movements before search; show the matching row
count separately after search and any archive display limit. Status counts use
the same projection. Deduplicate by exact movement ID, preferring a process
record over a relay-only archive row, not formatting similarity. Inconsistent
inputs remain visible as uncertain evidence and never authorize an action.

## 4. Drafts, asynchronous responses and actions

- Keep unsent message text in browser memory per exact movement ID. Polling,
  view/filter changes, section collapse, Close and A→B→A selection must preserve
  it, caret and focused editor. Do not add persistent draft storage; page close
  or reload may discard drafts and the UI states that limit near the editor.
- Detail, traffic, log and error responses may render only for the still-current
  selection and request generation. Older responses for the same movement must
  not overwrite newer evidence. A closed panel stays closed when requests finish.
- Sending captures movement ID and the submitted text. Only successful receipt
  clears that movement's draft, and only if it is still the submitted version.
  A newer edit survives; success/failure for A never clears or writes status into
  B. Failed sends retain text. Preserve `saved_queued_for_next_resume`, appended
  versus locally queued receipt, and duplicate-submit protection.
- Preserve registered action IDs, relay-sequence staleness checks and consumed-ID
  rejection. Responses cannot re-enable or activate another movement's action.
  Platform-policy blocks remain non-overridable. Browser inputs never become
  commands, paths, argv fragments or execution authority.

## 5. Usage and cost labels

Token counts measure recorded model work. With recorded counters, a model
(observed, or requested under GOV.ORCH.4-A) and an existing approved price,
render **Estimated model-token cost**. Preserve `estimated` versus
`estimated_from_requested_model`; the latter visibly says **requested model**
and preserves the provider-default audit exception. A reported amount renders
**Reported model-token cost**, distinct from the estimate. Neither is an invoice
or a statement about subscription billing.

Use **Unknown — token/model evidence absent** only where that evidence is
absent. If evidence exists but no approved model price exists, display
**Estimate unavailable — model price missing**, not unknown subscription
billing and not an invented price. Observed zero counters or a recorded zero
amount are valid zero values; absent usage remains absent. Missing time, cache
ratio, verification and PR evidence retain explicit unavailable labels.

Totals keep reported and estimated amounts distinct and disclose missing-cost
movement counts; never imply complete coverage by summing only known values.
The existing `tokens_today`/`cost_today` fields sum lifetime movement totals
whose last usage activity is today. Until genuine period deltas exist, label
that scope **Movement totals last active today (UTC)** rather than tokens/cost
consumed today. No accounting or retention redesign is authorized.

## 6. Material 3 and keyboard contract

Use `/dashboard-theme.css` from
`scripts/orchestrator_dashboard.py::product_theme_css`, reading the existing
`ui2/frontend/src/theme/m3Theme.ts` palette. That TypeScript file is a visual
reference, not permission to adopt React/MUI or copy a second palette.
Use existing surface/container, on-surface, outline, primary, attention,
warning/error/success and provider variables with their established meanings.
Colour never establishes evidence, provider identity, state or verdict; every
state and provider retains readable text. Keep focus-visible outlines,
contrast, existing shape/type patterns and native controls; no new dependency.

Tab reaches filters, search, movement buttons, Close, section summaries and
message/actions in logical order. Native Enter/Space activate buttons and
summaries; Enter inside the textarea inserts a newline and never sends.
Polling preserves focus/caret and does not steal focus. Closing details returns
focus to its movement button if present, otherwise the selected filter.
Use table headers, labelled inputs/buttons and programmatic selection; avoid
whole-page live announcements on every poll. At narrow widths, contain table
scrolling without clipping controls or preventing access to details.

## 7. Exact implementation/test successor and completion gates

The next movement is **IMPLEMENTATION — table-first Workbench navigation**,
limited to `scripts/dashboard_assets/index.html`, `dashboard.js`,
`dashboard.css`, the minimal compatible projection in
`scripts/orchestrator_dashboard.py`, and its targeted tests. Reuse the current
theme bridge and authenticated routes; no new credential/network path.
Tests extend `tests/test_orchestrator_dashboard.py` and add one browser test
file `tests/test_orchestrator_dashboard_browser.py` using synthetic same-origin
responses, isolated browser storage and no live operational data. Reuse an
existing browser harness if available; the successor must name its executable
runtime and command before implementation. DOM-string/server-field assertions
alone cannot substitute for these interaction checks:

| Check | Required failure reproduction and passing behavior |
| --- | --- |
| Navigation | Active default, filter/search counts, empty states, details below; optional view uses identical membership/counts |
| Draft preservation | Type and set caret, poll twice, filter and close/reopen, A→B→A: exact draft and caret survive; B never inherits A |
| Response ordering | Resolve A detail/traffic/log/errors after selecting B or closing; resolve older same-movement requests last: current content remains correct |
| Send isolation | Send A, edit again/switch B, resolve success and failure: only acknowledged unchanged A draft clears, B unaffected; duplicate submit blocked |
| Classification | Cancelled with dead PID/PO turn/PR is Stopped in Archive only; seven health values, terminal precedence, exact-ID deduplication and all counts agree |
| Usage honesty | Reported, observed-model estimate, requested-model estimate, missing evidence/price, zeros and mixed totals show exact labels/scope without billed-spend claims |
| Keyboard | Tab/Enter/Space selection and summaries, textarea newline, focus/caret after polls, Close focus return; labels and selected state observable |
| Archive | More than ten terminal rows: first ten by specified order; Show all expands only loaded rows, count discloses loaded/displayed scope; relay-only entries have no invented details/actions |
| Security regression | No-token state makes no API calls; auth/Origin and stale/consumed actions remain enforced; synthetic text remains escaped |

Archive display starts at ten matching loaded rows, with Show all/Show fewer.
It makes no retention promise, deletes nothing and requires no new archive
store or unbounded backend expansion. Relay-only entries remain visibly limited
and cannot call missing process-record detail/action endpoints.

Contract-only validation: the two authority/cross-reference tests named in the
worker brief, repository privacy gate and diff checks. Implementation acceptance
requires the browser matrix plus affected dashboard/usage/provider/orchestrator
regression, applicable render/privacy gates and full suite required by repository
law. Human browser validation remains a distinct UI completion gate; synthetic
coverage is not real-environment or production certification. No successor code
is authorized until this draft is reviewed and frozen.

## 8. Cross-references

- `docs/design/GOV_ORCH_4_WORKBENCH_OBSERVABILITY_TOKENS_AND_STUCK_DETECTION.md` §§3.1–3.5, AC-12/13.
- `docs/design/GOV_ORCH_4A_COST_FROM_THE_REQUESTED_MODEL_WHEN_THE_STREAM_IS_SILENT.md` CU-1–4.
- `docs/design/GOV_ORCH_4B_A_PARTICIPANT_THE_ORCHESTRATOR_DID_NOT_SPAWN.md` HB-1–4.
