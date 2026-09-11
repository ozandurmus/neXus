# UI 2.0 — B1-7: Jobs screen + Run Now (read class) + step log contract

**DRAFT — FOR PRODUCT OWNER FREEZE, 2026-09-11.**

## 1. Scope and authority

This is the implementation contract for `UI2_0_DEVELOPMENT_WORKFLOW.md` §5
Phase B1 row 7: "Jobs screen + Run Now (read class) + step log — submit a
read job from the browser, watch steps, see outcome; Run Now for read-class
only until Karar 2 lands." It is the **first browser screen** this product
ships: the point where the frozen platform contracts (`C2` job execution,
`C3` identity/sessions/RBAC, `C4` capability registry/gate resolution) meet
a real operator in a real tab.

**Only read-class Run Now exists in this movement.** `CLASS_0_READ`
(`utils/action_taxonomy.py`) is the only action class this screen can
submit. No write capability of any kind — `CLASS_1_RECOVERY_WRITE`,
`CLASS_1B_CONTROLLED_RESTORE_WRITE`, or any higher class — is reachable
from this screen, in any control, dialog, or hidden route: `CLASS_1_
RECOVERY_WRITE.console_submittable = false` is unconditional (`C2` §7.3,
`utils/action_taxonomy.py`'s UI 2.0 amendment) and this contract does not
touch it. The mockup's own "Schedule collection" primary action (M3
Operations) and the backup-oriented rows of its job history table
(`job-2416`/`job-2415`, backup creation) are **out of scope for this
movement** — schedule authoring is `C2` §7's own later slice, and backup
creation stays behind the pilot allowlist regardless of who is looking at
this screen. This document fixes only what B1-7 ships: submitting and
watching a read job, and the job list/detail screens that make every job
— read or write, run by this movement or another — visible.

The Product Owner decision this document's title refers to ("until the
operational-write decision lands," workflow §5 row 7's "until Karar 2
lands") is out of this document's authority to anticipate; nothing here is
written so it needs to change when that decision lands — it needs only a
new movement to *add* a class-1 surface, never an edit to this one.

## 2. Screens and routes

| Screen | Route | Shows | Fed by |
|---|---|---|---|
| **Job list** | `/operations/jobs` | every job the actor may see (§4), most recent first: job id, capability/type, target(s), started time, duration, outcome — the same column set as the mockup's "Recent jobs" table (`UI2_0_MOCKUP_REFERENCE_NOTES.md` "Operations"); a "Run now" filled button (mockup button hierarchy: one primary action per screen) opens the Run Now dialog (§2.2) | `GET /api/jobs` (§3.2) |
| **Job detail** | `/operations/jobs/{job_id}` | the job's current state/outcome, its `precheck_results` list in order (§3.2), and its step log: one row per `job_step_attempt`, in `step_index` order, each showing kind, outcome, timing and (on failure) `error_class` — never raw device output (`C2` §5.4) | `GET /api/jobs/{job_id}` (§3.2), live-updated per §7 while non-terminal |
| **Run Now dialog** | modal over `/operations/jobs`, no route of its own | capability picker restricted to the server-supplied execution-eligible, class-0 set (§3.2); target picker; a mandatory reason field, ≥ 8 characters (`C2` §2.1's `D7` mandatory-reason pattern); submit / cancel | `POST /api/jobs` (§3.2) on submit; on success, navigates to the new job's detail screen |

No route submits, edits, or displays a schedule; no route exposes a
free-form command field (§8).

## 3. The API contract

### 3.1 Authorization applied to every route

Every request runs the full `E1`–`E6` gate chain (`C3` §6.1) before this
screen's own handler runs. `E1`–`E3` are identical for every route named
here (valid session, `action_id` known, taxonomy admits the action). `E4`
(`D7` role evaluation) requires `role:viewer` or stronger to render the job
list/detail (`C3` §4.1: `viewer` may "read every projection UI 2.0 ships");
it requires `role:operator` or stronger to submit a Run Now (`C3` §4.1:
`operator` gets "class-0 typed jobs, retry"). `E5`/`E6` are the action-
specific checks: for submission, that the named target resolves and is not
`disabled`/`DRAFT` (`C4` §3.5, `UI2_0_B1_ADJUDICATION_2026_09_12.md` F6),
and that the named capability is a member of `C4`'s execution-eligible view
(§3.5 of that contract) — a job against a capability with any unresolved
gate is refused here, never left to sit in `REQUESTED` forever (`C4` §3.5,
adjudication F4: admission runs **before** a job row is created).

### 3.2 Endpoints

**`GET /api/jobs`** — paginated list, newest first, filterable by capability,
target and outcome. Every row's `action_affordance` is irrelevant here (this
is a read of existing rows, not an affordance render); the response carries
only fields already public to `role:viewer` — no raw output, no credential
reference, no directory group name (`C3` §4.2's display rule stays scoped to
`security_admin`).

**`GET /api/jobs/{job_id}`** — one job's full state: `job_id`, `capability_id`
(or `profile_ref` for a non-B1-7 job), `action_class`, `target_refs`,
`owner`/`approvers` (as `actor_fingerprint`s, opaque — never resolved to a
directory identity in the API body, `AGENTS.md` identity law), `state`,
`outcome`/`terminal_reason` (once terminal), `reason`, `origin`,
`precheck_results[]` (§3 of `C2`, ordered, truncated at first failure for a
`REJECTED` job), and `steps[]` (§3.3).

**`POST /api/jobs`** — Run Now submission. Request:
```json
{ "capability_id": "cp_gaia_inventory_show_version_ha_state",
  "target_refs": [{ "device_id": "dev-example-042" }],
  "reason": "Confirming HA state after maintenance window",
  "idempotency_key": null }
```
`idempotency_key` is optional; the server mints one when absent (`C2`
§2.3). On success: `201`, the created job's `job_id` and initial `state:
"REQUESTED"`. The endpoint never accepts a `profile_ref`, a class other than
`CLASS_0_READ`, or a free-form command string — the request shape itself has
no field capable of expressing either.

### 3.3 Step-log shape

`steps[]` entries are the durable, server-recorded facts and nothing else:
`step_index`, `step_kind` (one of `C4` §2.3's closed set), `attempt_number`,
`sent_at`, `outcome` (`matched` / `expectation_unmet` / `ambiguous` /
pending), `error_class` when applicable, and — only where the capability's
`evidence_shape_ref` permits one — a bounded, redaction-filtered
`sanitized_fragment` (`C1` §4, `UI2_0_B1_04_...` §7). The raw device response
is never present in this payload, in any field, at any verbosity (raw-
evidence law).

### 3.4 The three refusal shapes

A refusal at any gate is `HTTP 403`/`404`/`409` with a body naming exactly
one of three distinct facts, so the operator (and the UI) can tell them
apart without guessing:

| What happened | HTTP envelope | Distinguishing field |
|---|---|---|
| **"You may not."** The actor's role is not bound to this action, or is bound to a group the actor is not in. | `403 ACTION_REFUSED`, `outcome: "DENIED"` (`C3` §5.2) | `reason_code: "actor_not_in_required_group"` |
| **"This device cannot."** The named target is `DRAFT`/disabled, or fails a device-scoped `E5`/`E6` check. | `403`/`404`, action-specific (`C3` §6.1's `E5`/`E6` row) | a device-scoped `reason_code` (e.g. `device_not_enrolled`, `device_disabled`) |
| **"This capability is unavailable."** The action's role token has no binding at all, the actor's group set is stale (`AUTHZ_NOT_EVALUATED`), or the capability itself has an unresolved gate and is outside `C4`'s execution-eligible view. | `403 ACTION_REFUSED`, `outcome: "AUTHZ_NOT_EVALUATED"` (`C3` §5.2), or `403 CAPABILITY_NOT_EXECUTION_ELIGIBLE` | `reason_code: "role_token_unbound"` / `"actor_group_set_stale"` / `"capability_gate_unresolved"` |

No response ever collapses two of these into one code or one message; the
render path (§6) depends on the distinction to explain *why*, never merely
*that*, an action failed (`C3` §5.1's `AUTHZ_NOT_EVALUATED` is never
downgraded to a silent absence).

## 4. State and outcome vocabulary

Every `C2` §3.1 state maps to exactly one label and one visual treatment,
drawn from the mockup's own chip vocabulary (`M3Components`'s "State
vocabulary" card and `M3Operations`'s job-history chips):

| `C2` state / outcome | UI label | Treatment |
|---|---|---|
| `REQUESTED` | "Queued" | neutral chip, no icon — routine, not a fault (`C2` §8 pt 1) |
| `CLAIMED` | "Claimed" | neutral chip, transient (`C2` §8 pt 2: normal worker-loss recovery looks identical and is never surfaced as an error) |
| `EXECUTING` | "Running" | neutral/informational chip, in-progress indicator, no percentage (§8) |
| `COMPLETED` | "Succeeded" | success (`ok`) chip — green, matching the mockup's `job-2419`/`job-2418` rows |
| `FAILED` | "Failed" | error (`bad`) chip — this is the one state the mockup's own rule reserves red for: "a definite, non-ambiguous failure" (`C2` §3.1) |
| `REJECTED` | "Blocked" | **neutral** chip, with the specific `precheck_results` reason printed beneath it — exactly the mockup's `job-2415` treatment (a plain, unmodified `.chip`, not `.chip.bad`, with "Outside the D3 pilot allowlist" as a caption). A pre-execution refusal is expected admission control, not a fault; it never borrows the red/error treatment |
| `CANCELLED` | "Cancelled" | neutral chip |
| `OUTCOME_UNKNOWN` | "Outcome unknown" | **attention/warn** chip (the mockup's `warn`/`attn` tint, the same family used for "Partial") — explicitly **not** the success chip and **not** the error chip; caption: "a command may have reached the device; no automatic retry" (`C2` §8 pt 3), with a "start reconciliation" affordance shown to `role:backup_admin`, never a "retry" affordance (`C2` §3.5) |
| `RECONCILED` | shows the underlying `reconciled_outcome` (`RECONCILED_SUCCESS` → "Succeeded", `RECONCILED_FAILURE` → "Failed", `RECONCILED_STILL_UNKNOWN` → "Outcome unknown") | the same chip the resolved value would carry on its own, plus a small "reconciled" badge naming who recorded the evidence and when (`C2` §3.5) — never presented as if execution itself produced a fresh, unqualified success |

**The mockup's stated rule, carried into this vocabulary verbatim**: "Red is
reserved for real fault, unsafe drift, out-of-sync and failure. Expected
member differences carry no warning icon and no failure wording"
(`M3Components`, state-vocabulary card). Applied to jobs: only `FAILED`
gets the red/error treatment. `REJECTED` (an admission-time refusal, working
exactly as designed) and `OUTCOME_UNKNOWN` (an honest "we don't know," `C2`
§3.5) both explicitly avoid it — `REJECTED` reads as neutral, `OUTCOME_
UNKNOWN` reads as attention, and neither reads as failure.

**`OUTCOME_UNKNOWN` is neither success nor failure, and the UI says so.** No
success icon, no failure icon, no percentage, no default assumption in
either direction — the label and caption above are this contract's fixed
wording; a future screen may restyle the chip but may not reclassify the
state as one of the other two.

## 5. Open item this section surfaces: "Partial" has no `C2` owner

The mockup's job-history table shows an outcome value, "Partial"
(`job-2417`, `M3Operations`), that does not correspond to any `C2` §3.1
state or terminal outcome. `C2`'s job model is single-job, single-outcome;
nothing in it defines a job that is "partially" successful. This screen
therefore renders only the eight states/outcomes in §4's table; it does
**not** invent a "Partial" mapping to satisfy the mockup, and does not
display the word "Partial" anywhere. This is flagged as an open item (§13)
rather than resolved here, because resolving it means deciding what a
partial job *is* at the `C2` layer (a multi-target job with mixed per-target
results? A job whose `finally` step failed after its main steps succeeded?)
— a job-model question, not a screen-rendering one.

## 6. The visible-but-refused rule

A control the current role may not use is never a bare greyed button with no
explanation, and it is never simply omitted when the capability genuinely
has no surface for this actor at all — the mockup states both halves of
this exactly: *"A capability with no surface is omitted. A capability that
exists but cannot run here is shown and explained, never a bare greyed
control"* (`M3Components`, device overflow menu). Concretely for this
screen: the "Run now" button and, inside its dialog, each capability option
are rendered from the server's own `action_affordance` map (`C3` §5.4) — a
`DENIED`/`AUTHZ_NOT_EVALUATED` outcome renders the control disabled with the
matching `reason_code` shown inline (mirroring the mockup's "Collect now …
console only" pattern), never hidden and never enabled-then-failing.

**The hard rule**: this screen never presents an enabled control that the
API (§3.4) would refuse for a reason the render path already knew. If the
`GET` that populates this screen already carries `DENIED`, `AUTHZ_NOT_
EVALUATED`, or `capability_gate_unresolved` for an action, that action
renders disabled-and-explained on the very same page load — there is no
"click it and see" path where the UI presents an option genuinely
foreclosed by information it already had (mockup's own rule, "Actions the
product may not perform are never rendered as enabled buttons," `M3
Components`, Actions card).

## 7. Live updates

While a job is in `CLAIMED` or `EXECUTING`, the job-detail screen receives
new `job_step_attempt` rows as the worker commits them — a push channel
(server-sent events or a WebSocket, best-effort per `C2` §8 pt 5) layered
over the same `GET /api/jobs/{job_id}` shape, never a separate, richer data
source. **Every row shown is a row the server has actually committed.** The
UI never shows a step as "starting" before its pre-contact attempt row
exists, never interpolates a percentage between steps, and never predicts
step N+1 from step N's kind (§8).

**On connection drop**: the UI shows a plain "reconnecting" indicator over
the last-known step log — it does not clear the log, does not mark anything
failed, and does not fabricate a "still running" heartbeat of its own. On
reconnect, it re-fetches `GET /api/jobs/{job_id}` in full and reconciles
against that authoritative read, exactly matching `C2` §8 crash point 5's
own principle: the push channel is best-effort, the job row (and its step
log) is the source of truth, and a lost notification produces a delayed
correct view, never a wrong one.

## 8. What the screen must never do

- **No job submission from an exported report or any downloaded artifact.**
  The mockup states this rule for its own report export ("Jobs cannot be
  submitted from the exported report. Submission is a console capability and
  is subject to the action taxonomy," `M3Operations`); this screen carries no
  code path that accepts a job request from anywhere but its own
  authenticated `POST /api/jobs`.
- **No free-form command entry**, anywhere — not in the Run Now dialog, not
  in a "notes" field, not as a debug affordance. Every field this screen
  submits is a closed choice (capability id from a server-supplied list,
  target from a server-supplied list) or a bounded free-text reason that is
  never interpreted as a command.
- **No presentation of a class-1 (or higher) action.** No capability picker
  entry, no button, no menu item on this screen resolves to any action class
  other than `CLASS_0_READ`.
- **No fabricated percentage, ETA, or progress bar not backed by a specific,
  named step.** "Running" means exactly one step is currently attempted and
  no more; the UI states which step by name/index, never a synthetic
  overall-progress number.

## 9. Test specification

1. **`RunNowDeniedRoleIsVisibleButRefused`** — an actor holding `role:viewer`
   only opens the Jobs screen; fails unless the "Run now" button (or its
   capability options) render disabled with `reason_code:
   actor_not_in_required_group`, **and** a direct `POST /api/jobs` from the
   same session returns `403 DENIED` with the same code — proving the UI's
   refusal and the API's refusal agree, not merely that the UI looks right.
2. **`UnavailableCapabilityExplainsWhy`** — a capability with an unresolved
   gate (`C4` §3.3, `UNKNOWN`) is offered in the registry but outside the
   execution-eligible view; fails unless the Run Now dialog shows it
   disabled with `reason_code: capability_gate_unresolved` (never simply
   absent from the list, and never shown enabled).
3. **`OutcomeUnknownIsNeitherSucceededNorFailed`** — a job in
   `OUTCOME_UNKNOWN`; fails if the job-detail screen renders the success
   chip, the error/red chip, any percentage, or any label other than
   "Outcome unknown" with its fixed caption.
4. **`StepLogShowsOnlyServerReportedSteps`** — a job with three committed
   `job_step_attempt` rows; fails if the UI ever renders a fourth row, an
   interpolated "in progress" row for a step with no attempt yet, or any
   step-log content sourced from anywhere but the API response.
5. **`RejectedJobNeverGetsRedTreatment`** — a job that reaches `REJECTED`
   from a pre-execution check failure; fails if the chip carries the
   error/`bad` styling rather than the neutral styling §4 fixes.
6. **`NoWriteClassSurfaceAnywhereOnScreen`** — a static/DOM-level test walks
   every rendered control on the Jobs screen and its dialogs; fails if any
   resolves to an `action_class` other than `CLASS_0_READ`.
7. **`ConnectionDropNeverFabricatesProgress`** — the push channel is severed
   mid-job; fails if the step log changes to anything other than the
   last-known state plus a reconnecting indicator, or if a step appears
   before its attempt row is confirmed present via a subsequent `GET`.
8. **`RefusalShapesAreDistinguishable`** — three submissions, one against
   each refusal in §3.4's table; fails unless each response's `outcome`/
   `reason_code` combination is unique and the UI's rendered explanation
   text differs across all three.
9. **`SubmitEndpointRejectsNonReadCapability`** — a request naming a
   capability whose resolved `action_class` is not `CLASS_0_READ`; fails
   unless the request is refused before a job row is created (never a
   `201`, never a `REQUESTED` row).
10. **`IdempotentDoubleSubmitCreatesOneJob`** — two Run Now submissions with
    the same client-omitted key in rapid succession; fails if two distinct
    `job_id`s result (`C2` §2.3).

## 10. Acceptance criteria

- **AC-1.** The Jobs screen submits only `CLASS_0_READ` capabilities;
  static analysis of the built frontend finds no code path capable of
  constructing a request naming any other class (test 6, 9).
- **AC-2.** Every `C2` state/outcome maps to exactly one of §4's labels;
  no state renders unlabeled or with an invented label.
- **AC-3.** `OUTCOME_UNKNOWN` never renders as success or failure (test 3).
- **AC-4.** `REJECTED` never renders with the error/red chip (test 5).
- **AC-5.** A control's enabled/disabled state on page load matches what a
  direct API call against the same action returns, for every refusal shape
  in §3.4 (test 1, 8).
- **AC-6.** The step log renders exactly the server-reported
  `job_step_attempt` rows, in `step_index` order, with no interpolation
  (test 4, 7).
- **AC-7.** No route, dialog, or hidden control on this screen accepts a
  free-form command string.
- **AC-8.** A connection drop during a running job never changes the
  displayed outcome and never fabricates progress (test 7).
- **AC-9.** Submitting the same Run Now twice without a client key produces
  exactly one job (test 10).
- **AC-10.** "Partial" appears nowhere in this screen's rendered output
  (§5).

## 11. Validation plan

```
./ui2/gradlew -p ui2 unitTest
./ui2/gradlew -p ui2 integrationTest --tests "*Jobs*" --tests "*RunNow*"
./ui2/gradlew -p ui2 architectureTest
cd ui2/frontend && npm ci && npm test && npm run build
./ui2/gradlew -p ui2 check
python3 -m pytest -q -p no:cacheprovider tests/test_architecture_convergence.py tests/test_cold_start_budget.py
python3 scripts/repository_privacy_check.py
git diff --check
```
The frontend tests run inside the `frontend` npm workspace `UI2_0_B1_01_
SKELETON_CI_DOCKER_CONTRACT.md` §2/§3.2 already fixes (`npm ci && npm test
&& npm run build`, folded into `./ui2/gradlew check`); this movement adds no
new build-time tool and no new CI workflow.

## 12. Worker route and effort

**Sonnet 5, normal.** This movement wires a screen against three already-
`FROZEN` contracts (`C2`, `C3`, `C4`) and one adjudication that already
settled the admission-ownership questions this screen would otherwise raise
(`UI2_0_B1_ADJUDICATION_2026_09_12.md` F4/F6) — deterministic implementation
against a frozen contract, not new architecture or an unresolved security
boundary, per `CLAUDE.md`'s routing table. `Sonnet 5, extended thinking
(high)` would be more than this step needs; escalate only if the live-
update transport choice (§7) turns out to require a genuine new
cross-subsystem decision the Product Owner has not already made.

## 13. Open items for the Product Owner

1. **"Partial" has no `C2` job-model owner** (§5). The mockup shows it as a
   real outcome; this screen renders none of the eight §4 states as
   "Partial" until a job-model decision (multi-target jobs? partial
   `finally`-step failure?) gives it a defined meaning. Recorded here, not
   decided here.
2. **Live-update transport (SSE vs. WebSocket) is unspecified by any C-series
   contract.** `C2` §8 pt 5 anticipates "a push notification (websocket,
   email)" only as a best-effort mechanism whose absence never matters; this
   document does not choose between the two, only fixes the behavioral
   contract (§7) either must satisfy.
3. **The Run Now dialog's target picker source is unspecified here.** B1-7
   needs *some* way to name a `target_ref`; the device workspace (workflow
   §5 row 9) is the natural long-term source but is not guaranteed to land
   before this movement. Whether B1-7 ships a minimal inline target picker
   or depends on row 9 landing first is a sequencing question for the
   Product Owner, not a contract question this document can settle alone.
4. **Whether the Jobs screen's "Run now" button is its own primary action or
   is instead launched only from a device's own row (per the mockup's
   "Collect now" overflow-menu pattern)** is a UX placement choice within
   the constraints this document already fixes (§2, §6); either placement
   satisfies every acceptance criterion above.

## 14. Cross-references

`UI2_0_DEVELOPMENT_WORKFLOW.md` §5 B1 rows 6–9, exit criterion;
`UI2_0_MOCKUP_REFERENCE_NOTES.md` (Operations, Navigation & components);
`docs/design/ui2_mockups/M3Operations.dc.html`,
`docs/design/ui2_mockups/M3Components.dc.html`;
`UI2_0_C2_JOB_EXECUTION_CONTRACT.md` (FROZEN) §2, §3, §5, §8;
`UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (FROZEN) §5, §6;
`UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` (FROZEN) §3.5;
`UI2_0_B1_04_COLLECTION_ENGINE_CORE_CONTRACT.md` (FROZEN);
`UI2_0_B1_ADJUDICATION_2026_09_12.md` (DECIDED) F4, F6;
`UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` §2 (module map), §3.2;
`AGENTS.md` — network action taxonomy, identity law;
`utils/action_taxonomy.py`.
