# UI 2.0 — B1-9: device workspace (first read screen) contract

## Status

**FROZEN — 2026-09-12**, under the Product Owner's standing written
authorization to approve, revise or cancel UI 2.0 B1 contracts.

## 1. Scope

This is the implementation contract for the queue row
`ui2_b1_09_device_workspace_first_read_screen`,
`docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §5 Phase B1 row 9: "Device
workspace (first read screen) — device list + inventory projection from step
6; RBAC visible-but-refused behaviour proven."

**The row is split by a standing Product Owner gate, and this contract
implements only the half the gate leaves open.** The gate
(`docs/history/backlog/ui2_b1_05_extract_cp_inventory_subset.md`, PO GATE
2026-09-12, standing) suspends collection and extraction work until the
Product Owner specifies, per vendor, which collection type and which
data-collection methods apply. Rows B1-5, B1-6 and B1-13 are deferred for
exactly that reason. Row 9's "inventory projection from step 6" therefore has
no producer and no settled content, and **§7 of this contract forbids
implementing any of it**.

What this contract does settle: the device workspace as a **read surface over
rows the product already holds** — `devices`, `endpoints` (identity and
transport shape only, §4), the enrollment state machine, and the
`authz_decisions` trail — plus the end-to-end proof of visible-but-refused
RBAC on a real screen, which is the row's own stated purpose and needs no
collected data to prove.

### 1.1 Authority, cited not re-decided

- `docs/design/UI2_0_ARCHITECTURE_CONTRACT.md` (FROZEN — 2026-09-12) §2.1
  (UI 2.0 is a separate shell with its own typed read API), §2.2 (a persisted
  projection is never resolved presentation state; `primary_status`, tone,
  copy and affordances are computed per request and never stored as truth),
  §2.4 (the browser sends intent, never a command; honest affordances), §3.1
  (RBAC is `D1`/`D7`, never menu-hiding; no client-side visibility toggle
  exists and the front end has no role concept), §3.2 (the three levels of
  indirection), §8 (the invariants this screen must preserve).
- `docs/design/UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md`
  (FROZEN, as amended by
  `docs/design/UI2_0_B1_ADJUDICATION_2026_09_12.md`) — the Device / Endpoint
  / CredentialReference model, §3's enrollment states and transitions, §7's
  identity and privacy rules. This is this contract's primary input; every
  column §3 below renders is one that document's movement created.
- `docs/design/UI2_0_B1_03_IDENTITY_SESSIONS_CONTRACT.md` (FROZEN) §6
  (closed role-token vocabulary, unmapped identity gets no role), §7 (the
  `E1`–`E6` chain, the four `E4` outcomes, the refusal envelope and its closed
  `reason_code` vocabulary, and the visible-but-refused rule for a `GET` that
  renders affordances), §8 (the RBAC test set this screen must extend rather
  than duplicate).
- `docs/design/UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md` — the redaction
  policy realized as `V5__audit_redaction_policy.sql`, which classifies
  `endpoints.address_ref` as a tier-2 "management-path operational identity"
  whose value `fn_audit_capture` must never persist. §4 below reasons from
  that classification.
- `docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §2 (module map)
  and §5 (the test-harness carrier rule this contract's §8 follows without
  restating).
- `AGENTS.md` — "Sensitive identity reporting law", the identity law,
  "configuration intent != runtime truth", "collection success != semantic
  correctness", and the UNKNOWN/fail-closed law. Load-bearing for §4 and §5,
  applied there rather than restated.

`docs/design/UI2_0_B1_07_JOBS_SCREEN_CONTRACT.md` is **DRAFT and therefore
not authority** for anything here; it is named only to fix a boundary (§6):
job list, job detail, the step log and Run Now submission belong to that
movement's surface and are absent from this one, and because that document is
not frozen, nothing in this contract may be implemented to add a submission
path in its place.

## 2. The screen

| Screen | Route | Shows |
|---|---|---|
| **Device list** | `/devices` | one row per `devices` row the actor may read (§3.1), newest first |
| **Device workspace** | `/devices/{device_id}` | one device: its identity header (§3.2), its transport summary (§4.2), its enrollment panel (§5), and its refused-action affordances (§6) |

Both routes are `GET` only. No other route, control, dialog, form field or
hidden path is added by this movement. The workspace is a leaf: it links to no
screen this movement ships, and its `{device_id}` path segment is the opaque
`devices.device_id` value, never an index, ordinal or address.

## 3. What the screen shows, column by column

Every field below is a column that exists today in
`ui2/service/src/main/resources/db/migration/V1__initial_schema.sql` or
`V3__device_enrollment.sql`. Nothing else is rendered.

### 3.1 Device list columns

| Rendered | Source column | Notes |
|---|---|---|
| Device reference | `devices.device_id` | opaque `TEXT`, shown verbatim, never parsed, padded, truncated for display-as-identity, or sorted as a number (identity law) |
| Vendor | `devices.vendor_hint` | a *hint* recorded at registration, labelled as such in copy; never presented as a verified platform identity |
| Registered | `devices.created_at` | registration time — not a collection, contact or liveness time |
| Source | `devices.registration_source` | `'manual_registration'` is the only value this movement's data carries |
| Enrollment state | `devices.enrollment_state` | rendered per §5's table, never as a health or reachability claim |
| Disabled | `devices.disabled` | an independent boolean, rendered as its own marker, never folded into the state column (§5.2) |
| Test target | `devices.is_test_target` | a classification marker only; it changes no affordance, gate or eligibility on this screen |

Sort and filter are server-side over these columns only. `role:viewer` or
stronger is required to read either route (`UI2_0_B1_03_IDENTITY_SESSIONS_
CONTRACT.md` §6's token vocabulary; `viewer` may read every projection UI 2.0
ships). An actor with no role reads neither, and receives the refusal
envelope of §6, never an empty list that implies no devices exist.

### 3.2 Workspace identity header

`devices.device_id`, `vendor_hint`, `registration_source`, `created_at`,
`is_test_target`, plus the **presence** of a `credential_reference_id` —
rendered as configured / not configured. The
`devices.credential_reference_id` value itself, and every column of
`credential_references` (`purpose`, `backend_pointer`), are absent from the
payload: `backend_pointer` is a tier-2 credential location in
`V5__audit_redaction_policy.sql`, and a screen has no purpose a redacted
audit row does not.

### 3.3 Not a column on this screen

`devices` carries no display-name or label column, deliberately:
`V3__device_enrollment.sql` records that none was invented, to avoid adding an
unspecified sensitive field. This screen therefore **invents none either** and
renders no client-side derived label. The consequence is §9's `UNKNOWN-1`.

## 4. Values that never reach the browser

The rule applied here is `AGENTS.md`'s sensitive identity reporting law:
compare locally, report the relationship, and do not reproduce management
addresses or other raw device identity values into a browser artifact.

### 4.1 The management address — the decision

**`endpoints.address_ref` is never sent to the browser, in any field, at any
verbosity, to any role this movement's routes serve.** It is not masked, not
partially shown, not revealed behind a disclosure control, and not included
in an error or debug field.

The reasoning, stated because this is the decision that matters most:

1. The repository has already classified this value once, in the direction of
   less disclosure. `V5__audit_redaction_policy.sql` declares
   `endpoints.address_ref` tier 2, "management-path operational identity", and
   replaces its value with `sha256:<hex>` even in `audit_log` — a table
   readable only by `ui2_app` inside the product's own database. A browser
   payload is strictly lower-trust than that table: it lands in a DOM, a
   devtools network log, a browser cache, a screenshot, and a support bundle
   assembled from any of those. A value the product refuses to keep in its own
   audit trail cannot coherently be rendered in a tab.
2. `AGENTS.md` names management addresses explicitly among the values a model
   "must not retrieve or echo merely because it is available", and names
   screenshots intended for sharing as a destination the rule covers.
3. No operation this screen offers needs it. The screen's jobs are: find a
   device, read its enrollment state, and see which actions are refused. Each
   is answerable from opaque identity plus relationship vocabulary.

**What the operator can still accomplish without it**: identify and select a
device by its opaque `device_id`; see its vendor hint, registration time and
test-target classification; see whether a transport and a credential
reference are configured at all (§4.2, §3.2); read the enrollment state and
its meaning; and see, for every declared action, whether they may perform it
and why not. **What they cannot accomplish here**: confirm that the address
enrolled for a device is the intended one. That is a registration-time
question, owned by the manual registration flow and `role:onboarding_admin`,
and whether any role ever reads the value back is `UNKNOWN-2` (§9) — not
answered by silence in this contract, and not implementable against it.

### 4.2 Transport summary — what replaces it

| Rendered | Derived from | Vocabulary |
|---|---|---|
| Transport configured | existence of an `endpoints` row for the device | `PRESENT` / `MISSING` |
| Transport kind | `endpoints.transport_kind` | the closed transport vocabulary; `'ssh_exec'` is the only value B1 data carries |
| Endpoint reference | `endpoints.endpoint_id` | opaque, shown only where an operator must name one endpoint of several |
| Address | — | **never rendered.** Copy states that the management address is held by the service and not displayed, so its absence reads as a deliberate product rule, not as missing data |

`MISSING` is a real outcome, not an error: the schema permits zero or several
endpoints per device even though the manual registration flow creates exactly
one. Zero endpoints renders `MISSING`, never an empty string that could be
mistaken for an empty address.

### 4.3 The complete never-rendered list

`endpoints.address_ref`; every `credential_references` column including
`backend_pointer`; `devices.credential_reference_id` as a value;
`role_bindings.group_reference_encrypted`, `group_reference_key_id` and any
directory group name or DN; `sessions.csrf_secret`; any `actor_fingerprint`
resolved to a directory identity (fingerprints are opaque and stay opaque);
and any raw vendor response, in any field (raw-evidence law — and this
movement produces none to render in the first place, §7).

## 5. Enrollment state presentation

### 5.1 The four states

`devices.enrollment_state` is a closed, `CHECK`-constrained vocabulary of
exactly `DRAFT`, `ENROLLED`, `UNREACHABLE`, `DEGRADED`
(`V3__device_enrollment.sql`). The screen renders each with copy that states
what the row records, never what the device is doing now.

| State | What the operator is told | What the screen must never imply |
|---|---|---|
| `DRAFT` | Registered. Not confirmed reachable. Never collected from. | that a confirmation is pending automatically, or that the device is broken |
| `ENROLLED` | A past authorized confirmation succeeded. | **that the device is reachable now.** `ENROLLED` is a recorded outcome of an earlier contact, not a live fact ("configuration intent != runtime truth") |
| `UNREACHABLE` | The most recent recorded contact failed. | that the device is down, that submission is impossible, or that the label itself is a verdict — an outcome is judged when it happens, never inferred from this value |
| `DEGRADED` | Reachable at last contact, with a non-fatal capability-level signal recorded. | that the signal's meaning is known to this screen. `DEGRADED` carries the same eligibility as `ENROLLED`; the distinction is presentational |

Two further rules follow from `AGENTS.md` and are load-bearing, not copy
preferences:

- **No freshness claim without a freshness fact.** None of the four columns
  records when the recorded state was last established. The screen therefore
  renders no "last seen", "as of" or staleness indicator, and no green/amber/red
  health tone that would assert currency. This is `UNKNOWN-3` (§9), and the
  absence of the indicator is the correct behaviour until it is closed, not a
  gap to fill with `created_at`.
- **"Collection success != semantic correctness."** Even once a producer
  exists, a state reached because a command returned output proves the contact,
  not the meaning of what came back. The screen's copy attributes the state to
  the recorded transition, never to a device's self-description.

An `enrollment_state` value outside the four fails closed: the row renders
`NOT_EVALUABLE` and no affordance treats it as eligible. It is never defaulted
to `ENROLLED` and never hidden from the list.

### 5.2 `disabled` is independent

`devices.disabled` is a separate boolean, not a fifth state, and a device
retains its `enrollment_state` while disabled. The screen renders the two
facts side by side and never collapses them: a disabled `ENROLLED` device
shows both, because "was confirmed reachable" and "is administratively
withdrawn" are different claims with different remedies. Disabling and
enabling are not performed from this screen (§6.4).

## 6. RBAC visible but refused, proven

This is the row's stated purpose. The rule is not invented here:
`UI2_0_ARCHITECTURE_CONTRACT.md` §3.1 and
`UI2_0_B1_03_IDENTITY_SESSIONS_CONTRACT.md` §7 fix it. This contract fixes
what it looks like on a real screen and what counts as proof.

### 6.1 What the server sends

Each `GET` of either route runs the `E1`–`E6` chain for its own read action,
then evaluates every action declared against the rendered device and returns
`200` with an `action_affordance` map:

```
action_affordance[action_id] = { outcome, authority?, reason_code? }
```

`outcome` is one of the four `E4` outcomes — `PERMITTED`,
`NO_APPLICABLE_AUTHORITY`, `DENIED`, `AUTHZ_NOT_EVALUATED`. `reason_code` is
the closed vocabulary already fixed for the refusal envelope:
`actor_not_in_required_group`, `role_token_unbound`, `actor_group_set_stale`.
The map's **key set does not vary by actor**: an actor with no role receives
the same `action_id` keys as a `security_admin`, each carrying its own
outcome. A refused action is never absent from the map, and a map key is never
omitted to save bytes.

### 6.2 What the operator sees

- The control renders, in place, enabled-looking in layout and labelled with
  its real name. It is not hidden, greyed out of existence, moved to an
  overflow menu, or replaced by a placeholder.
- Adjacent to it, the refusal is stated in the product's relationship
  vocabulary: what was refused, the authority that refused it, and the
  `reason_code`'s plain-language form. "You may not" (`DENIED`), "this
  capability has no binding" (`role_token_unbound`) and "your group set is
  stale" (`actor_group_set_stale`) are three distinct messages, never collapsed
  into one.
- Activating a refused control issues the request and shows the server's own
  `403` refusal. The front end performs no pre-emptive block, because it has no
  role concept to block with (`UI2_0_ARCHITECTURE_CONTRACT.md` §3.1).
- No message names a directory group, DN, or any actor's identity beyond an
  opaque fingerprint.

### 6.3 What is recorded, and where

Every affordance evaluation and every activation writes an `authz_decisions`
row (`V2__identity_sessions_rbac.sql`), append-only, carrying the acting
session's `actor_fingerprint`, the `action_id`, the outcome and the
`reason_code`. The `decision_id` returned in a `403` body is that row. A
refusal rendered without a corresponding row is a defect, not a cosmetic
difference: the displayed label and the recorded decision are one fact with
two presentations, and §8's tests fail on disagreement. `authz_decisions`
never enters a support bundle.

### 6.4 No write path, structurally

The device workspace submits nothing. It contains no `POST`, `PUT`, `PATCH` or
`DELETE` route; no device registration, confirm, disable, enable, credential
reassignment or endpoint edit; no job submission and no retry; no free-form
field of any kind. Device contact is out of scope for every B0/B1 contract to
date and is not reintroduced here. The refused affordances of §6.1 therefore
refer to actions **owned and served elsewhere** — the manual registration and
enrollment-confirm actions of the device-model movement, and the job
submission surface whose own contract is not frozen (§1.1) — which is exactly
what makes this screen the honest place to prove visible-but-refused: the
refusal is real, and this movement adds no executable path behind it.

## 7. Explicitly not in scope, each with its gate

| Not built | Gate that must clear first |
|---|---|
| Any rendering of collected device data, including the inventory projection row 9's own text names | The standing PO gate of 2026-09-12: the Product Owner specifies, per vendor, which collection type and which data-collection methods apply. Until then no screen may read a projection this movement would have to explain. |
| Which data a device workspace *should* show once collection is direction-ed — field set, grouping, completeness or provenance presentation | Same gate. This contract deliberately proposes no candidate field list, because a proposed list is a collection specification in presentation clothing. |
| Any capability, capability content, command, command gate entry, timeout, frequency, session-reuse or parser decision | Same gate, plus the network-device command gate of `docs/AI_DEVELOPMENT_PROTOCOL.md` for anything device-facing. |
| Any device-facing read, probe, liveness check or reachability refresh triggered by opening the screen | Baseline: no B0/B1 contract contacts a device. A refresh control would be a device-facing read with a browser trigger. |
| The meaning, completeness semantics and provenance display of `cp_inventory_projection` | Same PO gate; the table exists with no producer (§10). |
| Job list, job detail, step log, Run Now | A frozen jobs-screen contract, which does not exist yet (§1.1). |
| Schedule authoring, backup, compliance, restore | Their own subsystems; none has an action in the registry to refuse. |
| A device display label | §3.3 and `UNKNOWN-1`. |

Where the screen would display collected data, it displays nothing and the
copy names the gate: the workspace states that collection is not yet
direction-ed for this device's vendor, with no partial field set, no empty
table shell implying a coming field set, and no spinner.

## 8. Test specification

JUnit tests in the `integration-tests` module, following
`UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §5's carrier rule without
restating it: a real PostgreSQL 16 server through the single fixture, failing
when no carrier exists, never skipping.

1. **`DeviceWorkspacePayloadNeverCarriesAddressRef`** — registers a device
   whose `endpoints.address_ref` holds a distinctive synthetic marker, then
   asserts the marker appears nowhere in the serialized list or workspace
   payload: no field, no error body, no header. *Proves*: the one sensitive
   value this screen is closest to never crosses the boundary. *Does not
   prove*: that no other sensitive value leaks — test 2 covers the declared set
   and no test covers a column that does not exist yet.
2. **`DeviceWorkspacePayloadCarriesOnlyDeclaredColumns`** — asserts the
   payload's field set equals §3's exactly: every rendered field is a declared
   column, and no column of `credential_references`, no
   `credential_reference_id` value, no `group_reference_encrypted` and no
   `csrf_secret` is present. *Proves*: the payload is closed by construction.
   *Does not prove*: that the values rendered are correct.
3. **`RefusedActionIsRecordedNotMerelyDisplayed`** — an actor whose group set
   does not contain the bound group loads the workspace; fails unless the
   affordance renders with `DENIED` / `actor_not_in_required_group` **and** a
   matching `authz_decisions` row exists whose outcome and `reason_code` equal
   the rendered ones. A rendered label with no row, or a row disagreeing with
   the label, fails. *Proves*: the refusal is a recorded server decision.
   *Does not prove*: that the browser renders it legibly.
4. **`RefusedAffordanceStillRendersInPayload`** — compares the
   `action_affordance` key sets of a no-role actor, a `viewer` and a
   `security_admin` over the same device; fails if any key is absent from any
   of them, or if a refused key is returned as `null`.
5. **`GetAffordanceAndPostRefusalAgree`** — for each refused `action_id` on
   the workspace, activates it and asserts the `403` body's `outcome` and
   `reason_code` equal the `GET`'s affordance for the same actor and instant.
   *Proves*: display and enforcement are one fact. *Does not prove*: agreement
   across a binding change mid-request.
6. **`NoRoleActorGetsRefusalNotEmptyList`** — an actor with no role bound to
   the read action; fails if the response is `200` with an empty device array
   rather than the refusal envelope with a `decision_id`.
7. **`WorkspaceExposesNoMutatingRoute`** — enumerates the routes this
   movement registers; fails if any is not `GET`, or if a `GET` handler writes
   to any table other than `authz_decisions`.
8. **`DisabledAndEnrollmentStateRenderIndependently`** — a disabled
   `ENROLLED` device; fails unless both facts appear as separate fields with
   `enrollment_state` still `ENROLLED`.
9. **`UnknownEnrollmentStateFailsClosed`** — forces an out-of-vocabulary
   value past the application layer; fails unless the row renders
   `NOT_EVALUABLE` with no eligible affordance, and never `ENROLLED`.
10. **`NoFreshnessClaimWithoutFreshnessColumn`** — fails if the payload
    carries any `last_seen`, `as_of`, staleness or health-tone field, or if
    `created_at` is surfaced under a freshness label.
11. **`WorkspaceReadsNoProjectionTable`** — fails if either route's SQL
    touches `cp_inventory_projection` or any table a collection producer would
    write. *Proves*: the PO gate is enforced by the code, not only by this
    document.
12. **`EnrolledIsNotARechabilityClaim`** — fails if opening the workspace
    issues any outbound device connection, or if the rendered copy for
    `ENROLLED` asserts present-tense reachability.

No test proves more than its own assertion. Together they do not prove the
screen is usable; they prove it is closed, honest and recorded.

## 9. UNKNOWN — nothing may be implemented against these

- **`UNKNOWN-1` — device identification without a label or an address.**
  With no display-name column (§3.3) and no rendered address (§4.1), an
  operator distinguishes two devices only by opaque `device_id`, vendor hint,
  registration time and the test-target marker. Whether that is sufficient,
  and if not whether the answer is a label column with its own data class or a
  different affordance, is open. No label may be added, derived or inferred
  until it is answered.
- **`UNKNOWN-2` — address read-back for onboarding.** Whether any role ever
  reads `endpoints.address_ref` back through any surface, and under what
  audit, is undecided. §4.1 settles only this screen: never. No read-back path
  may be built on the strength of this contract's silence.
- **`UNKNOWN-3` — state freshness.** No column records when an
  `enrollment_state` value was last established, so no freshness or health
  indicator is implementable. Adding one requires a column and its own
  decision about what the timestamp means.
- **`UNKNOWN-4` — the collected half of row 9.** Everything the standing PO
  gate covers (§7). The row cannot be marked `DONE` on this contract alone;
  it advances at most to the state this half's evidence supports, with the
  gated half recorded as outstanding.
- **`UNKNOWN-5` — multi-endpoint presentation.** The schema permits several
  endpoints per device while whether a second endpoint belongs to this era is
  itself an open item of the device-model movement. §4.2 renders a list and
  asserts nothing about ordering, primacy or a "default" endpoint; no primacy
  rule may be inferred from render order.

## 10. Contradictions found, reported not reconciled

1. **`cp_inventory_projection`'s migration is misattributed.**
   `UI2_0_ARCHITECTURE_CONTRACT.md` §2.2 states "One projection table exists
   (`cp_inventory_projection`, `V4`)". The table is created in
   `V1__initial_schema.sql`; `V4__collection_engine_core.sql` creates
   `job_step_attempt`, `job_reconciliation` and `gate_registry` and no
   projection table. Two level-2 sources disagree about which migration owns
   the table this row was to read. Not reconciled here — a frozen contract's
   own statement is not edited by a downstream one.
2. **Row 9's text presupposes work its own gate suspends.** Workflow §5 row 9
   reads "device list + inventory projection from step 6", while the standing
   PO gate of 2026-09-12 defers step 6 (B1-6) entirely. The row as written
   cannot be satisfied while the gate stands. This contract splits the row
   rather than reinterpreting either source, and reports the conflict for the
   Product Owner to resolve by amending the row or lifting the gate.
3. **`cp_inventory_projection` exists with no producer and no direction.** The
   table, its foreign keys and its audit-redaction entries are built, while the
   collection method that would fill it is gated. Structure shipped ahead of
   the decision that gives it meaning; this contract reads no row from it (§8
   test 11) rather than treating the table's existence as a specification.

## 11. Acceptance criteria

- **AC-1.** Both routes are `GET`-only and render exactly §3's columns; no
  other route, field, or control is added.
- **AC-2.** `endpoints.address_ref` appears in no payload, error or header
  (test 1), and the transport summary uses §4.2's relationship vocabulary
  instead.
- **AC-3.** No value of §4.3's list reaches the browser (test 2).
- **AC-4.** Each of the four enrollment states renders §5.1's meaning; no
  freshness or health claim exists (test 10); an out-of-vocabulary value fails
  closed (test 9); `disabled` renders independently (test 8).
- **AC-5.** Every declared action's affordance renders for every actor with
  its own outcome, and the key set does not vary by role (test 4).
- **AC-6.** Every refusal has a matching `authz_decisions` row agreeing with
  what was displayed (test 3), and `GET` affordance and `POST` refusal agree
  (test 5).
- **AC-7.** An actor with no role receives a refusal, never an empty list
  (test 6).
- **AC-8.** No mutating route, no device contact, no projection read (tests 7,
  11, 12).
- **AC-9.** Every `UNKNOWN` of §9 is unimplemented, and §10's contradictions
  are carried to the Product Owner unreconciled.

## 12. Worker route

**Sonnet 5, normal.** One read API, one closed payload shape, and an
affordance map over an already-frozen gate chain: no new architecture,
storage, vendor semantic or security boundary is decided here — the hard calls
(§4.1, §7) are made in this document, leaving deterministic implementation.
Escalate only if implementation cannot satisfy AC-2 or AC-6 without a schema
change, which would be a new decision rather than a harder one.

## 13. Cross-references

- `docs/design/UI2_0_ARCHITECTURE_CONTRACT.md`
- `docs/design/UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md`
- `docs/design/UI2_0_B1_ADJUDICATION_2026_09_12.md`
- `docs/design/UI2_0_B1_03_IDENTITY_SESSIONS_CONTRACT.md`
- `docs/design/UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md`
- `docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md`
- `docs/design/UI2_0_B1_07_JOBS_SCREEN_CONTRACT.md`
- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md`
