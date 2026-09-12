# UI 2.0 — B1-2a audit redaction contract

## Status

**FROZEN — 2026-09-12**, under the Product Owner's standing written
authorization to approve, revise or cancel UI 2.0 B1 contracts.

Scope-limited successor to `docs/design/UI2_0_B1_02_SCHEMA_V1_CONTRACT.md`
(FROZEN) §3.5. It changes one thing: what `fn_audit_capture` is permitted to
persist. Every other clause of B1-2 stands unchanged.

## 1. The defect this contract fixes

`V1__initial_schema.sql`'s `fn_audit_capture` writes
`to_jsonb(OLD)` / `to_jsonb(NEW)` — **the whole row** — into
`audit_log.before_state` / `after_state`. Measured against a live PostgreSQL
16 server: inserting one `sessions` row places that row's `csrf_secret` value
verbatim into `audit_log.after_state`, where the `ui2_app` role reads it with
a plain `SELECT` (`ui2_app` holds `SELECT` on `audit_log` by V1 §5).

Two repository laws are violated as written:

- `AGENTS.md` "Privacy and DLP": secrets never enter browser or shareable
  artifacts. `audit_log` is the backing store of the B1-8 audit screen, so
  this defect was one screen away from rendering a live session secret into a
  browser.
- `AGENTS.md` "Raw-evidence law": persist the minimum required semantics,
  prefer tokens and relationships over raw retention. A full-row snapshot is
  raw retention by default.

`role_bindings.group_reference_encrypted` is the same class with an extra
consequence: copying ciphertext into a second table puts it outside the
lifecycle of the key that protects it, so a key rotation or revocation never
reaches the audit copies.

This defect originates in this repository's own contracts, not in any
inherited material.

## 2. Decision

Full-row capture is retained — the audit trail's value depends on it — but a
**declared redaction set** is never persisted as a value. A redacted column's
value is replaced by `sha256:<hex>` of the value's text representation. `NULL`
stays `NULL`.

The digest form is chosen over blanking because it preserves every audit
property the value itself provided, except disclosure:

| Property | Value stored | Blanked | `sha256:` digest |
| --- | --- | --- | --- |
| Column was present / absent | yes | yes | yes |
| Value changed between two audit rows | yes | **no** | yes |
| Two rows carry the same value | yes | **no** | yes |
| Value is disclosed | **yes** | no | no |

This is `AGENTS.md`'s "compare locally → report the relationship, not the
values" applied to storage: the audit trail can still prove *that* a secret
changed, and that two records agree or disagree, without holding it.

A digest is not a protection for a low-entropy value: an attacker who can
read `audit_log` can confirm a guess. It is therefore **not** a substitute for
the column's own protection, and never a reason to relax a grant, an
encryption boundary, or a revoke elsewhere.

## 3. The redaction set

Tier 1 — secret or cryptographic material. Redaction is mandatory.

| Table | Column | Reason |
| --- | --- | --- |
| `sessions` | `csrf_secret` | live secret material |
| `role_bindings` | `group_reference_encrypted` | ciphertext outside its key's lifecycle |

Tier 2 — operational identity, credential location, or device-derived
content. Redaction is mandatory; each is a pointer or payload that the audit
trail needs to track but never to disclose.

| Table | Column | Reason |
| --- | --- | --- |
| `credential_references` | `backend_pointer` | credential location |
| `secrets_metadata` | `reference_pointer` | credential location |
| `endpoints` | `address_ref` | management-path operational identity |
| `job_step_attempt` | `captured_variables` | device-derived captured content |
| `job_reconciliation` | `evidence` | free-text evidence of unbounded shape |

Explicitly **not** redacted, with reason: `provenance_records.sanitized_fragment`
(a sanitization boundary is already asserted upstream; redacting it here would
hide the evidence the audit exists to carry), `gate_registry.safe_telemetry_fields`
(declared safe by the command gate), `*_fingerprint_sha256` columns (already
digests), and every identifier, state, timestamp, class and count column.

A column being absent from both lists is not permission to persist it — see
§4.

## 4. Fail-closed coverage rule

Every column of every audited table is classified exactly once: auditable in
full, or redacted. An unclassified column is a **build failure**, not a
default.

A test enumerates, from the live database, every column of every table
carrying a `trg_audit_*` trigger and fails if any column appears in neither
list. Adding a column to an audited table therefore forces an explicit
decision in the same change. The test must be proven to fail by injecting a
real unclassified column.

This is the part that matters more than the current list: the list will be
wrong again the next time a table grows a column, and the rule is what
catches it.

## 5. Implementation requirements

1. A forward-only migration (`V5`). `V1` is not edited — B1-2 §2 forbids
   editing a migration already applied to any environment.
2. The policy is stored in a table the migration creates and seeds, so that
   both the coverage test and the audit screen can read it. `ui2_app` receives
   `SELECT` only; no `INSERT`/`UPDATE`/`DELETE` grant, so the application
   cannot widen or narrow its own audit policy at runtime.
3. `fn_audit_capture` keeps its `SECURITY DEFINER` and its existing
   fail-closed `audit_context_missing` behaviour unchanged. Redaction is
   applied after the context check, never instead of it.
4. The function must not be able to fail open: if the policy table is
   unreadable or empty for a table that has declared redactions, the mutation
   fails rather than persisting an unredacted row.
5. No DSN, secret, or redacted value appears in any log, error message, or
   exception raised by the function.
6. `audit_redaction_policy` itself carries no audit trigger, and is a
   declared exclusion from B1-2 §3.5's audit-coverage rule. The reason is the
   same one that excludes `flyway_schema_history`: the table is written only
   by a migration running as `ui2_migrate`, before any audit context can
   exist, so wiring `fn_audit_capture` to it would make every migration fail
   closed with `audit_context_missing`. Tamper-evidence for the policy comes
   from the §5.2 grant instead — `ui2_app` cannot write it at all — and from
   the §4 coverage test, which fails if the policy stops covering a column.
7. Existing `audit_log` rows written before `V5` still contain unredacted
   values. The migration purges them, because there is no production
   environment and no retention obligation over development rows; a future
   environment with a retention obligation needs its own successor decision.

## 6. Acceptance checks

1. Inserting a `sessions` row leaves no occurrence of the `csrf_secret` value
   anywhere in `audit_log`, and leaves `sha256:<hex>` in its place.
2. Two different secret values produce two different digests; the same value
   twice produces the same digest — proving change detection survives.
3. A `NULL` redacted column stays `NULL`, and is distinguishable from a
   redacted non-null value.
4. Every Tier 1 and Tier 2 column named in §3 is proven redacted by an
   assertion naming that table and column, not by a blanket scan.
5. The §4 coverage test fails when an unclassified column is added, and the
   failure names the table and column.
6. `fn_audit_capture`'s `audit_context_missing` refusal still fires, and no
   row and no audit row commits.
7. `ui2_app` cannot write to the policy table (SQLState `42501`).
8. The full `ui2` integration suite passes against a real PostgreSQL 16.

## 7. What this contract does not decide

Retention and export of `audit_log` (who may read it, for how long, and in
what form it leaves the product) is the B1-8 audit-screen contract's and the
DEPLOY.1 evidence-egress policy's business, not this one's. Nor does this
contract authorize any audit screen: it only makes one safe to design.

## 8. Amendment A-1 (2026-09-12) — `row_pk` is outside the redaction guarantee

Found while writing `UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md`, which had to
render every `audit_log` column and therefore had to ask what each one is
guaranteed to hold.

`fn_audit_capture` computes the audit row's `row_pk` from the **unredacted**
snapshot:

```sql
v_row JSONB := to_jsonb(COALESCE(NEW, OLD));
...
VALUES (TG_TABLE_NAME, v_row ->> v_pk_col, ...)
```

Redaction is applied to `before_state` and `after_state` only. So if a
declared-redacted column were ever a table's primary key, its value would be
persisted in cleartext in `row_pk` — in the same table this contract exists to
keep values out of.

**Not exploitable today, and that is measured, not assumed.** None of the seven
redacted columns of §3 is a primary key: `sessions.csrf_secret` against PK
`session_id`, `role_bindings.group_reference_encrypted` against `binding_id`,
`credential_references.backend_pointer` against `credential_reference_id`,
`secrets_metadata.reference_pointer` against `secret_id`,
`endpoints.address_ref` against `endpoint_id`,
`job_step_attempt.captured_variables` against `attempt_id`,
`job_reconciliation.evidence` against `job_id`.

**What this amendment changes is the claim, not the code.** §2's guarantee is
hereby stated at its true scope: a declared-redacted column's value is never
persisted **in `before_state` or `after_state`**. `row_pk` is not covered. A
contract that implied otherwise would be the more dangerous artifact, because
the next person to add a redacted column would read it as covered.

**The rule that keeps it safe:** a declared-redacted column must never be, or
become part of, its table's primary key. `UI2_0_B1_08_*`'s test
`RedactedColumnIsNeverAPrimaryKeyTest` enforces it against the live database,
so the day someone adds one the build fails rather than the value leaking.

Closing the gap in `fn_audit_capture` itself — refusing the mutation when the
pk column is in the policy — is the stronger fix and is deliberately **not**
done here: it would change a frozen migration's behaviour on a path with no
current exposure, and the invariant above makes that path unreachable. If a
future table genuinely needs a redacted identifier, that is the successor
decision, and it must resolve what `row_pk` then means for joining an audit row
back to its subject.

## 9. Amendment A-2 (2026-09-12) — §5.4's fail-closed check was wrong, and blocked a real write

Found by the movement that enabled the collection-engine lease tests, which hit
it while seeding rows against a real server.

`V5` implemented §5.4 ("the function must not be able to fail open") by
comparing the whole redacted snapshot against the raw row: if they were equal,
redaction had evidently not happened, so refuse. That reasoning is wrong
whenever **every declared-redacted column of the row is NULL**. §2 deliberately
leaves `NULL` as `NULL`, so that a null is distinguishable from a redacted
value — and then the snapshot legitimately equals the raw row, and the check
fires on a row that had nothing to redact.

**Measured, not inferred.** `job_step_attempt.captured_variables` is that
table's only declared-redacted column, and `C2` §5.1 leaves it unknown until
the step runs, so it is `NULL` at pre-contact insert time. Against a real
PostgreSQL 16 database with `V1`–`V5` applied, every pre-contact
`job_step_attempt` insert raised `audit_redaction_not_applied` and rolled back.
That blocked the collection engine's **first per-step write** — the executor
could not record that it was about to contact a device.

This is the more dangerous kind of fail-closed defect: the refusal was real and
loud, but it refused correct work, and it would have surfaced only when someone
ran the executor against a migrated database.

**`V6` replaces the check with the property it meant to assert**, per column and
NULL-safe: every declared-redacted key present in a snapshot must be either
`NULL` or a `sha256:` string. That is **strictly stronger** than what it
replaces — the whole-object comparison passed as long as *any* field differed,
so it would not have noticed one redacted column among several going through
raw; the per-column form inspects each declared column individually.

Proven against a real PostgreSQL 16 server, all three directions:

1. A pre-contact insert with the redacted column `NULL` now commits, and the
   audit row preserves `NULL` rather than inventing a digest.
2. With a value present, redaction still applies: the value appears nowhere in
   `audit_log` and `sha256:<hex>` stands in its place.
3. With `fn_audit_redact` deliberately replaced by a no-op, the mutation is
   **refused** with `audit_redaction_not_applied` and nothing leaks — so the
   fix did not trade the guarantee for the bug.

§5.4 of this contract is amended to require the per-column form. The
whole-snapshot comparison must not be reintroduced.

**Migration numbering note.** `UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md` §3
reserved `V6` for `idx_audit_log_actor_fingerprint`. `V6` is taken by this fix,
which is a correctness defect blocking a write path; the B1-8 index takes the
next free number when that movement implements it.
