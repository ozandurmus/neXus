# UI 2.0 — B1-8: Audit & logs screen contract

## Status

**FROZEN — 2026-09-12**, under the Product Owner's standing written
authorization to approve, revise or cancel UI 2.0 B1 contracts.

Implementation contract for queue row `ui2_b1_08_audit_logs_screen`,
`docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` (BASELINE) §5 Phase B1 row 8:
"Audit & logs screen — every mutation since B1 step 2 visible; export-free."
It writes no `ui2/` source and issues no SQL itself; it fixes what the
implementing movement's handlers, projection and tests must contain, and how
they must fail.

## 1. Scope, authority, and the one boundary that matters

Authority chain: `AGENTS.md` → `docs/design/UI2_0_ARCHITECTURE_CONTRACT.md`
(FROZEN) §2 (shell boundary), §3 (RBAC is `D1`/`D7`, never menu-hiding), §8
(invariants) → `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` (FROZEN)
§3.5, §4 (the **Audit** data class, its retention posture and its `CLASS 2`
tier) → `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (FROZEN)
§4.1, §5 → `docs/design/UI2_0_B1_02_SCHEMA_V1_CONTRACT.md` (FROZEN) §4, §5,
§6 → `docs/design/UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md` (FROZEN) §2, §3,
§4 → `docs/design/UI2_0_B1_03_IDENTITY_SESSIONS_CONTRACT.md` (FROZEN) §§3–7
→ `docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` (FROZEN) §2, §5.
Data-sensitivity vocabulary is `PRIVACY_AND_DATA_HANDLING.md`'s `CLASS 0–3`,
cited and not restated here (`AGENTS.md` "Privacy and DLP").

**This screen reads this product's own PostgreSQL database and nothing
else.** It contacts no device, opens no transport, and resolves no
credential. No code path this movement adds may reach `job-engine`,
`worker`, `scheduler` or any vendor adapter (§7, §8 test 12). Collection and
extraction remain under their own standing Product Owner gate and are
outside this document entirely; a clause here that implies a device read is
a defect in this document, not an authorization.

The screen is the first surface that renders `audit_log` into a browser.
`UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md` §1 named that fact as the reason
its own defect mattered ("one screen away from rendering a live session
secret into a browser") and §7 closed by stating it "only makes one safe to
design." This document is that design, and its centre of gravity is §3: the
presentation boundary over a whole-row JSONB snapshot of an arbitrary
audited table.

## 2. Screens, routes and actions

| Screen | Route | Shows | Fed by |
|---|---|---|---|
| **Audit list** | `/audit` | one row per `audit_log` row in the actor's scope (§5), newest first: `audit_id`, `occurred_at`, `table_name`, `row_pk`, `operation`, `actor_fingerprint`, `action_id`, `correlation_run_id` | `GET /api/audit` (§4.1) |
| **Audit entry detail** | `/audit/{audit_id}` | the same metadata plus the **field projection** of `before_state`/`after_state` (§3) | `GET /api/audit/{audit_id}` (§4.2) |

Two action registry entries (`C4`'s closed registry; `C3` §4.1 supplies the
required token for each):

| `action_id` | Required role token | Scope |
|---|---|---|
| `ui2.audit.read_own` | `role:viewer` | rows whose `actor_fingerprint` equals the requesting actor's — `C3` §4.1, `role:viewer` "read own audit rows" |
| `ui2.audit.read_all` | `role:security_admin` | every row — `C3` §4.1, `role:security_admin` "audit read across all actors" |

Both are `CLASS_0_READ` (`utils/action_taxonomy.py`); neither is a device
action, so `role:security_admin`'s "may not: any device-facing action"
separation of duties (`C3` §4.1) is not crossed by granting it here.

There is no third scope, no per-table grant, and no route that accepts a
`table_name` the registry does not already audit.

## 3. The presentation boundary — the two decisions this contract exists for

### 3.1 Which `audit_log` columns reach the browser

| Column | Reaches the browser | Why |
|---|---|---|
| `audit_id` | yes — as an opaque row handle and pagination cursor only | a surrogate `IDENTITY`, "not a product identifier" (`B1-2` §4); never presented as a count, a sequence position, or evidence of how many mutations exist |
| `table_name` | yes | a repository-committed schema name, `CLASS 0` |
| `row_pk` | yes, **verbatim and opaque** | the audited row's identity. Never parsed, split, cast to integer, zero-stripped, case-folded or pattern-matched in any layer (`AGENTS.md` identity law; `UI2_0_ARCHITECTURE_CONTRACT.md` §8) |
| `operation` | yes | closed set `INSERT`/`UPDATE`/`DELETE` |
| `actor_fingerprint` | yes, **as the fingerprint** | never resolved to a DN, a directory group, a username or a display name, in the payload or the rendered page (`AGENTS.md` sensitive identity reporting law; `UI2_0_ARCHITECTURE_CONTRACT.md` §3.3: "no DN, group name or address enters a log, an API response or a projection") |
| `action_id` | yes | repository-committed registry identity |
| `occurred_at` | yes | see §5.3 on what it does and does not order |
| `correlation_run_id` | yes, opaque | the join to a job; rendered as a link, never interpreted |
| `before_state` | **no** | never serialized into a response in any field, at any verbosity. Only the §3.2 field projection derived from it |
| `after_state` | **no** | as above |

`audit_redaction_policy` (`V5`) is read by the server to build the field
projection and to source each redaction's `reason`. Its rows are not a
screen of their own in this movement, and no route exposes the table
directly.

### 3.2 Decision — a `sha256:` digest never leaves the server

`B1-2a` §2 stores a redacted column as `sha256:<hex>`, and §2's own table
states what that buys: presence, change detection between two audit rows,
and equality between two rows — without disclosure. §2 also states the
limit in the same breath: "A digest is not a protection for a low-entropy
value: an attacker who can read `audit_log` can confirm a guess."

**Decision: the digest string is never present in an API response or in the
rendered page.** Instead each snapshot key is projected to exactly one
state, and the audit properties the digest existed to provide are computed
**server-side** and reported as a relationship:

| Field state | Meaning | Payload shape |
|---|---|---|
| `PRESENT` | the column is not in the redaction set; its value is rendered | `{state:"PRESENT", value:…}` |
| `NULL` | the column exists in the snapshot and its value is JSON `null` | `{state:"NULL"}` |
| `REDACTED` | the column is in the redaction set and held a non-null value | `{state:"REDACTED", tier, reason}` — `tier` and `reason` read from `audit_redaction_policy`, never invented in code |
| `ABSENT` | the key is not in this snapshot at all (the column did not exist when the row was written) | `{state:"ABSENT"}` |
| `UNCLASSIFIED` | §3.3 | `{state:"UNCLASSIFIED", table_name, column_name}` |

`NULL`, `REDACTED` and `ABSENT` are three distinct states and no layer may
collapse any two of them — `AGENTS.md`'s UNKNOWN/fail-closed law ("absence of
evidence is not evidence of absence") applied to a rendered field. A
`REDACTED` field is never drawn as an empty cell, a dash, or a blank that an
operator could mistake for "was null"; it carries the policy's own `reason`
text so the screen answers *why*, not merely *that*, a field is hidden.

For an `UPDATE` row the detail screen additionally reports, per key, one of
`CHANGED` / `UNCHANGED` / `NOT_EVALUABLE`, computed on the server by
comparing the `before_state` and `after_state` values of that key — for a
redacted key this is a comparison of the two digests, which is exactly
`B1-2a` §2's "value changed between two audit rows" property, and
`AGENTS.md`'s "compare locally → report the relationship, not the values"
applied to the presentation layer. `NOT_EVALUABLE` is reported when one side
is absent (`INSERT`/`DELETE`, or a key present on only one side) — never
`UNCHANGED` by default.

**Reasoning, stated plainly because the trade-off is real.** Shipping the
digest to the browser would let an operator compare any two records by eye,
which is a genuine audit capability. It would also put a guess-confirmation
oracle for seven declared-sensitive columns into a browser tab, a browser
cache, a screenshot, a support bundle and a DLP-inspected HTTP response —
and several of those columns are plausibly low-entropy (`endpoints.address_ref`
is a management-path reference; `secrets_metadata.reference_pointer` and
`credential_references.backend_pointer` are pointers of bounded shape). The
comparison capability is preserved without the oracle by computing the
comparison where the digest already legitimately lives, so the oracle is
not exported to buy a capability that does not require exporting it.
Truncating the digest is **not** an accepted middle: a prefix confirms a
guess for exactly the low-entropy values that matter, so a prefix is treated
as the digest itself and is equally forbidden.

The one capability this decision does not deliver is comparing a redacted
field across two *arbitrary* audit rows the operator picks. That is
`UNKNOWN-2` (§9) and nothing may be implemented against it.

### 3.3 Decision — whole-row snapshots are rendered against a closed classification, and an unclassified key refuses the snapshot

`before_state`/`after_state` are whole rows of thirteen audited tables
(`V1`'s eight `trg_audit_*` triggers, `V2`'s `role_bindings` and `sessions`,
`V4`'s `job_step_attempt`, `job_reconciliation` and `gate_registry`). A
future migration can add a column to any of them, and that column's value
would reach this screen with nobody revisiting this document. A
policy-driven filter alone does not close this: `audit_redaction_policy`
enumerates only what is *redacted*, so a new unclassified column passes
straight through it as `PRESENT`.

**Decision: field-granular allowlist, with a snapshot-level refusal when a
key is unclassified.** Concretely:

1. The movement commits a presentation classification resource in `service`
   — one entry per audited table listing the columns that are *auditable in
   full*. This is the declarative half `B1-2a` §4 leaves implicit: §4
   requires every column to be "auditable in full, or redacted", but the
   policy table stores only the redacted half, so the full half exists
   nowhere a renderer can read. No DDL and no new grant is added for it.
2. A key of a snapshot is projected as `PRESENT` **only** if that resource
   lists it. A key in `audit_redaction_policy` projects as `REDACTED`.
3. A key in neither projects as `UNCLASSIFIED`, and **the whole snapshot for
   that audit row is refused**: every `PRESENT` field of that snapshot is
   withheld and replaced by one `UNCLASSIFIED` marker naming `table_name`
   and `column_name` and carrying no value of any kind. `REDACTED`, `NULL`
   and `ABSENT` markers still render, as does every §3.1 metadata column —
   the audit row remains visible as "who mutated what and when", which is
   the screen's workflow §5 purpose, while its payload stays closed.
4. The request returns `200`, not `500`. An unclassified column is a build
   defect to be fixed, not a reason for the audit trail to become
   unreadable; the refusal is the product telling the operator the truth
   about its own coverage gap.

What fails if a new unclassified column appears: the `B1-2a` §4 coverage
test fails the build first (that test enumerates live columns of every
`trg_audit_*` table and is the primary gate), the §8 test 2 of this contract
fails independently at the presentation layer, and if both were somehow
bypassed the running screen refuses the snapshot rather than rendering the
column. Three layers, fail-closed at every one. The allowlist resource is
proven equal to (live audited columns − policy columns) by §8 test 13, so it
cannot silently drift into hiding a column that is merely new.

## 4. API contract

Every request runs the full `E1`–`E6` chain (`C3` §6.1, `B1-3` §7) before
this screen's handler. `E1` requires a valid `ACTIVE` session; `E2`/`E3`
admit the `action_id`; `E4` evaluates the §2 token. There is no `E5`/`E6`
check specific to these actions — they name no device and no prerequisite.

### 4.1 `GET /api/audit`

Paginated list in the actor's §5 scope. Query parameters, all optional, all
bounded:

| Parameter | Accepts | Bound |
|---|---|---|
| `table_name` | one name from the audited-table set | rejected with `400` if unknown to the set |
| `row_pk` | exact match only, used with `table_name` | opaque string, never a pattern or a prefix |
| `operation` | `INSERT`/`UPDATE`/`DELETE` | closed set |
| `actor_fingerprint` | exact match | accepted only for `ui2.audit.read_all`; ignored-and-refused, not silently widened, for `read_own` (§5.2) |
| `action_id` | exact match against the registry | closed set |
| `correlation_run_id` | exact match | opaque |
| `occurred_from` / `occurred_to` | `TIMESTAMPTZ` | half-open `[from, to)`; a `to` before `from` is `400` |
| `limit` | integer | default 50, server maximum 100; a larger request is **capped, not refused** |
| `cursor` | an opaque cursor minted by a previous response | §5.3 |

No parameter searches inside `before_state`/`after_state`, by substring,
pattern or JSONB containment. This is deliberate and doubly grounded: such a
query is unbounded over an append-only table with no supporting index, and a
substring probe against stored digests would re-introduce exactly the oracle
§3.2 removes.

Response: `{ "entries": [ …§3.1 metadata… ], "next_cursor": string|null }`.
List entries carry **no** field projection — the snapshot is detail-only, so
a list page never serializes thirteen tables' worth of row content.

### 4.2 `GET /api/audit/{audit_id}`

One row: the §3.1 metadata plus `before_fields` / `after_fields` (the §3.2
projection, keys in the audited table's committed column order) and, for an
`UPDATE`, the per-key `CHANGED`/`UNCHANGED`/`NOT_EVALUABLE` comparison. An
`audit_id` outside the actor's scope returns `404`, not `403`: for
`read_own`, the existence of another actor's audit row is itself information
the actor has no authority over.

### 4.3 Refusal shapes

A refusal is `C3` §5.2's envelope verbatim — `403 ACTION_REFUSED` with
`{action_id, outcome, authority, reason_code, decision_id}` and the closed
`reason_code` set `actor_not_in_required_group` / `role_token_unbound` /
`actor_group_set_stale`. This document adds no fourth code and no new
envelope.

## 5. Who may read it, refusals, and query bounds

### 5.1 Visible but refused

The `Audit` navigation entry always renders, for every authenticated actor,
including one holding neither token — `UI2_0_ARCHITECTURE_CONTRACT.md` §3.1
("Every navigation entry always renders per `D1`") and `C3` §5.4. The screen
then renders its own refusal with the `reason_code`'s explanation. The entry
is never hidden, never conditionally built into the front end (`C3` §5.4's
`AG-J3`: no role-conditional rendering in built assets), and the front end
computes no scope of its own — `read_own` versus `read_all` is decided by
the server on every request and is never inferable from, or alterable by,
anything the browser sends.

### 5.2 A refused read is recorded in `authz_decisions`, not in `audit_log`

Every `E4` evaluation of `ui2.audit.read_own` / `ui2.audit.read_all` writes
one `authz_decisions` row — `PERMITTED`, `DENIED` or `AUTHZ_NOT_EVALUATED` —
owned by `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §5.3 and created by
`V2__identity_sessions_rbac.sql`, where `ui2_app` holds `SELECT, INSERT` and
no `UPDATE`/`DELETE`. `decision_id` in the refusal envelope (§4.3) is that
row.

It is **not** recorded in `audit_log`, for two independent reasons that must
both hold: a refused read mutates no control-plane state, and `audit_log` is
the append-only record of mutations (`C1` §3.5); and `ui2_app` holds only
`SELECT` on `audit_log` (`B1-2` §5), so the application cannot write it at
all. A successful read likewise writes no `audit_log` row. Reading the audit
trail is therefore visible in `authz_decisions` and nowhere else, which is
the correct home: `UI2_0_ARCHITECTURE_CONTRACT.md` §3.3 already fixes
`authz_decisions` as append-only and never part of a support bundle.

`read_own` never widens. A request passing `actor_fingerprint` for another
actor under `read_own` is refused with `actor_not_in_required_group` rather
than quietly narrowed to the caller's own rows — a silent narrowing would
make a privilege test pass for the wrong reason.

### 5.3 Ordering, pagination, and what `occurred_at` does not prove

`audit_log` grows without bound (`C1` §4: "indefinite; no deletion path in
this contract"), so **no unbounded query may reach the database from a
browser.** Every read is keyset-paginated on `audit_id DESC`, with the
server-capped `limit` of §4.1 and a `WHERE audit_id < :cursor` predicate;
`OFFSET` is never used, and no endpoint returns a total row count (a
`COUNT(*)` over an unbounded append-only table is itself the unbounded query
this rule forbids — the UI shows "more" via `next_cursor`, never "page 7 of
412").

`audit_id DESC` is the sort key because it is the primary key and therefore
index-backed (`B1-2` §4). `occurred_at` is displayed and filterable but is
**not** the sort key: `V1` creates no index on it, and its `DEFAULT now()`
is transaction-start time, so it is not a total order over concurrent
transactions. Neither is `audit_id` a proof of commit order — it is assigned
at insert. The screen therefore presents `occurred_at` as the time a
mutation's transaction began and `audit_id` order as the order rows were
written, and makes **no** tamper-evidence or gap-detection claim from
either. `UNKNOWN-3` (§9) records that a strict evidential ordering, if ever
required, needs its own decision.

Filters are index-supported or bounded: `table_name`+`row_pk` uses
`idx_audit_log_table_row`, `correlation_run_id` uses
`idx_audit_log_correlation_run_id` (both `V1`). `actor_fingerprint` has no
index, and `read_own` filters on it on **every** request, so this movement's
forward-only migration (`V6`, `B1-2` §2: `V1`–`V5` are not edited) adds
`idx_audit_log_actor_fingerprint`. That is the only DDL this movement
contains: no new table, no new grant, no trigger, and no change to
`fn_audit_capture`.

## 6. Data class and handling

The screen's payload is `CLASS 2` (`C1` §4's **Audit** row; the UI 2.0
PostgreSQL instance is itself a `CLASS 2` identity-bearing store,
`PRIVACY_AND_DATA_HANDLING.md`). It is therefore local-only: the payload is
served to an authenticated browser over the product's own TLS surface and is
never written to a support bundle, an AI-shareable artifact, a log line, or a
fixture. No test fixture, screenshot or document produced by this movement
contains a real operational identity or a real digest (`AGENTS.md` privacy
and DLP). Server logs for these endpoints record `action_id`, `outcome` and
`actor_fingerprint` only — never a `row_pk`, never a field value, never a
snapshot.

## 7. What the screen must never do

- **No write path to `audit_log`.** No `INSERT`, `UPDATE`, `DELETE`, `COPY`,
  `TRUNCATE` or DDL statement against `audit_log` or
  `audit_redaction_policy` exists anywhere in the movement's source. The
  `B1-2` §5 grant already makes it impossible at the database; this contract
  makes it absent in the code, so the product never depends on the grant
  being the only line of defence (`DirectAuditLogWriteDeniedTest` already
  proves the grant side).
- **No export, of any kind, in this movement.** No CSV, JSON, PDF, print
  view, clipboard bulk-copy affordance, or "download" route. Workflow §5 B1-8
  states the movement is "export-free", and egress of audit content is the
  `DEPLOY.1` evidence-egress policy's decision (`B1-2a` §7) — not a UI
  convenience. Any future export needs that gate plus an explicit Product
  Owner decision, not an edit to this screen.
- **No device contact and no collection trigger.** No button, menu item,
  link or route on this screen submits a job, starts a collection, or
  reaches a transport. The movement adds no module dependency from `service`
  to `job-engine`, `worker`, `scheduler` or any adapter
  (`B1-1a` §2 module map; proven by §8 test 12).
- **No raw snapshot, at any verbosity.** There is no `?raw=`, `?verbose=`,
  debug header, error-path fallback or exception message that serializes
  `before_state`/`after_state`. A projection failure returns a refusal, never
  the input it failed to project.
- **No identity resolution.** `actor_fingerprint` is never expanded to a DN,
  group, mail address or display name; `row_pk` is never resolved by joining
  to the audited table to "enrich" the row, because that join would render a
  live, unredacted value of the current row under the guise of an audit
  entry.
- **No free-text or structured search inside snapshots** (§4.1).
- **No resolved presentation state stored anywhere.** The field projection is
  computed per request and never persisted (`UI2_0_ARCHITECTURE_CONTRACT.md`
  §2.2).

## 8. Test specification

All database-backed tests live in `ui2/integration-tests` and acquire their
carrier through the single fixture of
`UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §5: a real PostgreSQL **16**
(`UI2_TEST_JDBC_URL`, else Testcontainers), **failing** when no carrier
exists — never skipping, never passing. Tests 12 and 13's source/graph
assertions live in `ui2/architecture-tests`. No test prints a DSN,
credential or digest.

1. **`AuditScreenRedactedValueNeverReachesPayloadTest`** — inserts a
   `sessions` row carrying a known test `csrf_secret` under a valid audit
   context, then fetches `GET /api/audit/{audit_id}` and asserts the fully
   serialized response body contains neither the value nor the string
   `sha256:`, and that the field projects as `REDACTED` with the policy's own
   `reason`. **Proves** the presentation boundary of §3.2 for a Tier 1
   column. **Does not prove** that storage redaction works — that is
   `AuditRedactionPolicyTest` under `B1-2a` §6 — nor that the other six
   columns are covered (test 4).
2. **`AuditScreenUnclassifiedColumnIsRefusedTest`** — adds a real new column
   to an audited table, inserts a row, and asserts the detail response
   projects that key as `UNCLASSIFIED` naming table and column, withholds
   every `PRESENT` field of that snapshot, still returns the §3.1 metadata,
   and returns `200`. The test must be proven to fail by removing the
   refusal. **Proves** §3.3's runtime layer. **Does not prove** the build-time
   gate — that is `B1-2a` §4's coverage test — and does not prove the new
   column's correct classification, which is a human decision.
3. **`AuditScreenDistinguishesRedactedNullAbsentTest`** — three audit rows:
   a redacted non-null column, the same column `NULL`, and a snapshot
   written before the column existed. Fails if any two of `REDACTED`,
   `NULL`, `ABSENT` render identically or serialize to the same shape.
4. **`AuditScreenEveryDeclaredRedactionIsProjectedRedactedTest`** — one
   assertion per row of `audit_redaction_policy`, naming its table and
   column (not a blanket scan, mirroring `B1-2a` §6.4). Fails if any declared
   redaction projects as `PRESENT`.
5. **`AuditScreenFieldComparisonIsServerComputedTest`** — updates a redacted
   column and an unredacted column on one audited row; asserts `CHANGED` and
   `UNCHANGED` appear per key, `NOT_EVALUABLE` appears for an `INSERT` row's
   `before_fields`, and no digest appears in the payload. **Proves** that
   `B1-2a` §2's change-detection property survives §3.2. **Does not prove**
   cross-row comparison, which is not shipped (`UNKNOWN-2`).
6. **`AuditScreenViewerSeesOnlyOwnRowsTest`** — two actors, rows from each;
   a `role:viewer` session sees only its own, and a direct
   `GET /api/audit/{other_audit_id}` returns `404`. Fails on any leakage and
   on a `403` where §4.2 requires `404`.
7. **`AuditScreenReadOwnNeverWidensTest`** — a `role:viewer` request passing
   another actor's `actor_fingerprint`; fails unless refused with
   `actor_not_in_required_group` rather than silently narrowed.
8. **`AuditScreenRefusalRecordedInAuthzDecisionsTest`** — an actor with
   neither token requests the list; fails unless exactly one
   `authz_decisions` row is written with the envelope's `decision_id`, **and**
   zero new `audit_log` rows exist. **Proves** §5.2 in both directions.
9. **`AuditScreenQueryIsBoundedTest`** — `limit=100000` is capped at 100; no
   response carries a total count; a second page is reachable only through
   `next_cursor`; the executed SQL contains no `OFFSET` and no `COUNT(*)`
   over `audit_log`. Fails if any single request can return an unbounded row
   set.
10. **`AuditScreenHasNoWritePathTest`** — a source-level assertion that no
    statement in the movement's code targets `audit_log` or
    `audit_redaction_policy` for write, plus a live attempt as `ui2_app`
    expecting SQLState `42501`. **Does not replace**
    `DirectAuditLogWriteDeniedTest`; it proves the code-absence half §7
    adds.
11. **`AuditScreenNeverResolvesIdentityOrExportsTest`** — asserts no response
    field or rendered node contains a DN, group reference, or resolved
    display name for an `actor_fingerprint`, and that no route under
    `/api/audit` or `/audit` serves a downloadable content type or a print/
    export affordance.
12. **`AuditScreenNoDeviceContactEdgeTest`** (architecture-tests) — fails if
    the movement introduces any dependency edge from the audit screen's
    packages to `job-engine`, `worker`, `scheduler` or an adapter module, or
    any import of a transport type. **Proves** the §1 boundary
    mechanically rather than by assertion in prose.
13. **`AuditPresentationAllowlistMatchesLiveSchemaTest`** — enumerates, from
    the live database, every column of every `trg_audit_*` table and fails
    unless the committed allowlist equals that set minus
    `audit_redaction_policy`'s columns. **Proves** the allowlist cannot drift
    into hiding a legitimately new column or into listing a removed one.
14. **`RedactedColumnIsNeverAPrimaryKeyTest`** — fails if any
    `audit_redaction_policy` column is the primary key of its table. This
    guards a real gap in the mechanism, not a hypothetical: `fn_audit_capture`
    computes `row_pk` as `v_row ->> v_pk_col` from the **unredacted** row, so
    a redacted primary key would be stored in cleartext in `audit_log.row_pk`
    and rendered verbatim by §3.1. No current policy column is a primary key,
    so the invariant holds today and this test is what keeps it true.
    Reported as a gap to `B1-2a`'s owner (§10); this document does not amend
    `B1-2a`.

## 9. `UNKNOWN` — nothing may be implemented against these

- **`UNKNOWN-1` — retention and deletion of `audit_log`.** `C1` §4 fixes the
  **Audit** class as "indefinite; no deletion path in this contract", while
  `B1-2a` §7 states that retention "is the B1-8 audit-screen contract's and
  the `DEPLOY.1` evidence-egress policy's business". This document declines
  to settle it and is explicitly not the owner: a retention decision changes
  what evidence exists, not how a screen renders it, and `C1` §4 is the
  higher-scoped owner of the data class. See §10 for the contradiction.
  No retention, purge, archive or TTL behaviour may be implemented under
  this contract.
- **`UNKNOWN-2` — comparing a redacted field across two operator-chosen
  audit rows.** §3.2 keeps digests server-side, which leaves the
  within-row `before`/`after` comparison available and the arbitrary
  cross-row comparison unavailable. Whether the product needs a bounded
  server-side comparison surface (and, if so, what rate limit and audit
  record it needs, since a comparison oracle is exactly what §3.2 declines
  to export) cannot be settled from anything that exists. No comparison
  endpoint beyond §4.2's within-row one may be built.
- **`UNKNOWN-3` — an evidential total ordering of `audit_log`.** Neither
  `occurred_at` (transaction-start time, unindexed) nor `audit_id` (assigned
  at insert, not at commit) is a proven commit order, and nothing in `V1`–`V5`
  establishes gap-detection or tamper-evidence over the sequence. §5.3
  therefore makes no such claim. Any feature that would depend on one —
  "no audit row is missing", "these two mutations happened in this order" —
  needs its own decision first.
- **`UNKNOWN-4` — the "logs" half of the movement's title.** Workflow §5 B1-8
  is titled "Audit & logs screen" and its scope column names only "every
  mutation since B1 step 2 visible; export-free". The **Job log** data class
  (`C1` §4: `jobs`, `job_steps`, `job_step_attempt`) is a separate class with
  a separate retention posture and is rendered by the jobs screen's own step
  log, so whether B1-8 is also expected to ship a second, log-class surface
  cannot be settled from the workflow row. This contract covers the **Audit**
  class only, and no job-log surface may be built under it.

## 10. Contradictions found and not reconciled

Reported per `AGENTS.md` "Authority hierarchy" ("never silently reconcile a
disagreement between two authorities") and left for the Product Owner.

1. **`C1` §4 versus `B1-2a` §5.7 / `V5`, on deletion.** `C1` §4 (FROZEN)
   states the **Audit** class has "no deletion path in this contract";
   `B1-2a` §5.7 (FROZEN) directs, and `V5__audit_redaction_policy.sql`
   executes, `DELETE FROM audit_log`. `B1-2a` gives a bounded reason (no
   production environment, no retention obligation over development rows),
   which is a coherent case — but it is a deletion path in a `FROZEN`
   contract that another `FROZEN` contract says has none. Not reconciled
   here; recorded as `UNKNOWN-1`'s context.
2. **`B1-2a` §7 versus `C1` §4, on who owns retention.** `B1-2a` §7 assigns
   audit retention to this document; `C1` §4 already fixes a retention
   posture for the class. This document does not accept an ownership
   transfer that would let a screen contract overwrite a platform data-class
   contract. Left as `UNKNOWN-1`.
3. **Gap, not a contradiction, reported to `B1-2a`'s owner.** `B1-2a` §2's
   redaction guarantee does not cover `audit_log.row_pk`, which
   `fn_audit_capture` derives from the unredacted row (§8 test 14). It is
   benign today because no declared-redacted column is a primary key.
   Closing it is `B1-2a`'s decision; this document adds the test that
   detects a future violation and does not amend `B1-2a`.

## 11. Acceptance criteria

- **AC-1.** No response body and no rendered node produced by this screen
  contains a `sha256:` digest, a digest prefix, or a declared-redacted value
  (tests 1, 4).
- **AC-2.** `REDACTED`, `NULL` and `ABSENT` are distinguishable in the
  payload and on screen, and every `REDACTED` field carries the policy's own
  reason (tests 3, 4).
- **AC-3.** An unclassified snapshot key refuses that snapshot's `PRESENT`
  fields, names the table and column, and still returns the audit row's
  metadata with `200` (test 2).
- **AC-4.** The committed presentation allowlist equals the live audited
  column set minus the policy's columns (test 13).
- **AC-5.** `role:viewer` reads only its own rows; `role:security_admin`
  reads all; an actor with neither sees the navigation entry and an explained
  refusal (tests 6, 7, 8; §5.1).
- **AC-6.** Every read attempt, permitted or refused, produces exactly one
  `authz_decisions` row and zero `audit_log` rows (test 8).
- **AC-7.** No single request can return an unbounded row set; no response
  carries a total count; no `OFFSET` is used (test 9).
- **AC-8.** The movement's source contains no write statement against
  `audit_log` or `audit_redaction_policy`, and `ui2_app` is refused at the
  database (test 10).
- **AC-9.** No export route, downloadable content type, or identity
  resolution exists (test 11).
- **AC-10.** No dependency edge or import from this screen reaches a device
  path (test 12).
- **AC-11.** No declared-redacted column is the primary key of its table
  (test 14).
- **AC-12.** The full `ui2` suite and the repository privacy gate pass.

## 12. Validation plan

```
./ui2/gradlew -p ui2 unitTest
./ui2/gradlew -p ui2 integrationTest --tests "*Audit*"
./ui2/gradlew -p ui2 architectureTest
cd ui2/frontend && npm ci && npm test && npm run build
./ui2/gradlew -p ui2 check
python3 -m pytest -q -p no:cacheprovider tests/test_architecture_convergence.py tests/test_contract_authority_status.py tests/test_design_cross_references_resolve.py tests/test_cold_start_budget.py
python3 main.py --repository-privacy-check
```

Automated validation only; this movement is browser-and-database, contacts no
device, and therefore requires no real-environment network evidence
(`AGENTS.md`: automated and real-environment validation are separate gates —
this one has no device-facing behaviour to gate). Product Owner review of
the rendered screen against §3 is a human acceptance step, not a network
validation.

## 13. Worker route and effort

**Sonnet 5, normal.** The security decisions are made here (§3, §5); the
implementing movement wires two read endpoints, one projection, one index
and fourteen tests against frozen contracts — deterministic implementation,
not new architecture. Escalate only if `UNKNOWN-2` or `UNKNOWN-4` is
answered in a way that adds a new surface.

## Cross-references

`docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §5 B1 row 8;
`docs/design/UI2_0_ARCHITECTURE_CONTRACT.md` §2, §3, §8;
`docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §3.5, §4;
`docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §4.1, §5;
`docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
(action registry);
`docs/design/UI2_0_B1_02_SCHEMA_V1_CONTRACT.md` §4, §5, §6;
`docs/design/UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md` §2, §3, §4, §5, §7;
`docs/design/UI2_0_B1_03_IDENTITY_SESSIONS_CONTRACT.md` §§3–7;
`docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §2, §5;
`docs/design/UI2_0_B1_07_JOBS_SCREEN_CONTRACT.md` — adjacent screen, DRAFT and
not authority for anything here; consulted for document shape only, and it
owns the job/step-log surface this document deliberately does not touch;
`docs/design/UI2_0_BASELINE_DIRECTORY.md` §6 (log/audit data classes, which
cites `C1` §4 as owner);
`PRIVACY_AND_DATA_HANDLING.md` (`CLASS 0–3`);
`AGENTS.md` — identity law, sensitive identity reporting law, raw-evidence
law, UNKNOWN/fail-closed law, privacy and DLP, network action taxonomy;
`utils/action_taxonomy.py`;
`ui2/service/src/main/resources/db/migration/V1__initial_schema.sql`,
`V2__identity_sessions_rbac.sql`, `V4__collection_engine_core.sql`,
`V5__audit_redaction_policy.sql`.
