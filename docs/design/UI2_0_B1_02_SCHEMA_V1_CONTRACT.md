# UI 2.0 — B1-2: Schema V1 via Flyway contract

**DRAFT — FOR PRODUCT OWNER FREEZE, 2026-09-11.**

This document is the mechanical implementation contract for
`ui2_b1_02_schema_v1`, workflow §5 Phase B1 row 2: "Schema V1 via Flyway:
tables from C1; audit table; projections for the first capability." It
writes no `ui2/` source and issues no DDL itself — it fixes exactly what the
implementing movement's `V1__initial_schema.sql` must contain and how its
tests must fail.

## 2. Scope and authority

**In scope:** the literal Flyway `V1` migration file — table DDL, indexes,
foreign keys, the audit trigger mechanism, and the `ui2_app`/`ui2_migrate`
grants — realizing `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §3
("Core tables for B1... `B1-2` writes the literal Flyway SQL") without
re-deciding anything C1 already fixed. **Out of scope:** device contact of
any kind; `C2`'s job state-machine columns beyond the identity columns C1
§3.3 sketches (lease/fencing/outcome columns and the `job_reconciliation`
table land in `B1-4`'s own additive migration, once the collection engine
that needs them ships — C1 §3.3's own note: "not sketched here"); `C4`'s
`capability_registry`/gate-registry tables (C1 §3.1: explicitly not
sketched by this contract; C4 "define[s] its own DDL"); `C3`'s
`role_bindings`/`sessions`/`actor_authz_state`/`authz_decisions` (owned by
`B1-3`); `C7`'s artefact/manifest tables. Role **creation** (`CREATE ROLE
ui2_migrate`, `CREATE ROLE ui2_app`) is out of scope here too — per
`UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` Amendment B1-1-A item 4, both
roles are created by the integration harness's own bootstrap step, executed
before Flyway runs; `V1` only grants/revokes privileges against roles that
already exist.

**Interpretation this document adopts, flagged in §11:** C1 §3.1's ownership
matrix marks `devices`/`endpoints`/`credential_references` "Owner: `B1-4b`",
but `jobs.target_device_id` and `cp_inventory_projection`'s FKs (both owned
by this movement) reference those tables in the same schema generation, and
Postgres cannot create a forward FK to a table that does not yet exist. This
document reads "Owner: `B1-4b`" as contractual authority over the full
column set and the onboarding logic, not as "created by a later migration":
`V1` creates all nine tables of C1 §3.1 verbatim from C1's own sketches
(§3.2–§3.6), and `B1-4b` amends `devices`/`endpoints`/`credential_references`
only if it needs a column beyond what §3.2 already fixes — at present none,
since `is_test_target`/`registration_source` are already in the sketch.

## 3. Migration file layout and naming

Exact path, per `UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` Amendment
B1-1-A item 3 (`persistence` owns the Flyway integration code; `service`
owns the migration resources) and C1 §2.2:

```
ui2/service/src/main/resources/db/migration/V1__initial_schema.sql
```

`V1` is the first Flyway migration in the sequence; no `V0` exists. Every
statement in the file runs as `ui2_migrate`. Naming for any later file this
movement does **not** ship follows C1 §2.2's pattern
(`V<n>__<verb>_<subject>.sql`). **A migration is never edited after it has
been applied to any shared environment**, including a local Testcontainers
CI run that exercises the real sequence (C1 §2.2, forward-only rollback
posture): a defect found after that point is corrected by a new,
later-numbered file, never by mutating `V1` in place. `V1` may still be
edited freely before its first apply in review.

## 4. The V1 schema

`V1` creates exactly the nine tables of C1 §3.1's ownership matrix, column-
for-column as C1 §3.2–§3.6 sketch them (this document does not restate the
SQL; it fixes creation order, indexes and the trigger wiring C1 leaves
implicit). Creation order (FK-dependency order): `devices`, `endpoints`,
`credential_references`, `provenance_records`, `jobs`, `job_steps`,
`cp_inventory_projection`, `secrets_metadata`, `audit_log`.

| Table | Purpose | PK | FKs | Indexes | Cited requirement |
| --- | --- | --- | --- | --- | --- |
| `devices` | opaque physical-endpoint identity, manual-registration placeholder | `device_id` | — | none required at B1 scope | C1 §3.2 |
| `endpoints` | one management transport surface per device | `endpoint_id` | `device_id → devices` | `idx_endpoints_device_id` | C1 §3.2 |
| `credential_references` | opaque pointer to a secret-backend credential, never a value | `credential_reference_id` | — | none required | C1 §3.2, §6 |
| `provenance_records` | linkage layer from a derived fact back to its (discarded) raw source | `provenance_id` | — | `idx_provenance_run_id` | C1 §5 |
| `jobs` | identity columns only; `C2` adds lifecycle columns by a later migration | `job_id` | `target_device_id → devices` | `idx_jobs_target_device_id` | C1 §3.3; C2 §2.1 (by reference) |
| `job_steps` | identity columns only; `C2` adds outcome/kind columns later | `job_step_id` | `job_id → jobs` | `idx_job_steps_job_id` | C1 §3.3; C2 §5.1 (by reference) |
| `cp_inventory_projection` | first capability's evidence table (`show version`/HA state) | `projection_id` | `device_id → devices`, `endpoint_id → endpoints`, `job_id → jobs`, `provenance_id → provenance_records` (`NOT NULL`) | `idx_cp_inv_device_id` | C1 §3.4; C4 §2.5 (capability identity, by reference) |
| `secrets_metadata` | per-component secret pointer, never a secret value | `secret_id` | — | none required | C1 §3.6, §6 |
| `audit_log` | append-only mutation ledger, `C1-1` | `audit_id` (surrogate `IDENTITY`, not a product identifier) | none (`row_pk` is a free-text opaque value, not a live FK — it must remain valid after the referenced row is deleted) | `idx_audit_log_table_row`, `idx_audit_log_correlation_run_id` | C1 §3.5 |

`jobs.capability_id` and `provenance_records.capability_version` are plain
`TEXT`, **no `FOREIGN KEY`**, per C1 §3.3's explicit note: the constraint is
added by a small, additive migration once `C4`'s own `capability_registry`
migration lands — not reworked here.

**Deliberately not in `V1`, with reason:**

- `capability_registry` / gate-registry rows — C1 §3.1: "`C4` define[s] its
  own DDL", needed only once the collection engine (workflow B1 row 4)
  begins resolving gates.
- `role_bindings`, `sessions`, `actor_authz_state`, `authz_decisions` — C1
  §3.1: owned by `C3`, delivered with workflow B1 row 3 (Identity &
  sessions).
- `job_steps`' `kind`/`outcome`/`duration_ms`/etc., a `step_attempt` table,
  and `job_reconciliation` — C2 §2.1/§5.1/§3.5; these have no reader until
  the collection engine (workflow B1 row 4) exists, and C1 §3.3 states
  in-line that they are "not sketched here."
- Any `BackupProfile`/`BackupAssignment`/`BackupSchedule`/artefact-manifest
  table — C1 §3.1: `C7`'s own scope, not scheduled before Phase REL.

## 5. Roles and grants

Per C1 §2.4, `V1` contains exactly:

```sql
GRANT SELECT, INSERT, UPDATE, DELETE
  ON devices, endpoints, credential_references, provenance_records,
     jobs, job_steps, cp_inventory_projection, secrets_metadata
  TO ui2_app;

REVOKE INSERT, UPDATE, DELETE ON audit_log FROM ui2_app;
GRANT SELECT ON audit_log TO ui2_app;

REVOKE CREATE ON SCHEMA <ui2_schema> FROM PUBLIC;
REVOKE ALL ON SCHEMA <ui2_schema> FROM ui2_app;
GRANT USAGE ON SCHEMA <ui2_schema> TO ui2_app;
```

`ui2_migrate` receives no explicit grant statement in `V1`: its
`CREATE`/`ALTER`/`DROP` on the schema and `INSERT`/`SELECT` on
`flyway_schema_history` are fixed at role-creation time by the harness
bootstrap step (§2), not by this file. **`ui2_app` never holds a DDL
privilege of any kind** — no `CREATE`, no `ALTER`, no `DROP`, on this or any
future table; the explicit `REVOKE CREATE ON SCHEMA ... FROM PUBLIC` closes
the one default-privilege path Postgres could otherwise leave open.

## 6. The audit context and its fail-closed behaviour

Restating C1 §3.5 (`C1-1`), not re-deciding it: every mutation-bearing table
above except `audit_log` itself (`devices`, `endpoints`,
`credential_references`, `provenance_records`, `jobs`, `job_steps`,
`cp_inventory_projection`, `secrets_metadata` — eight tables) carries an
`AFTER INSERT OR UPDATE OR DELETE FOR EACH ROW` trigger `trg_audit_<table>`
executing the shared `SECURITY DEFINER` function `fn_audit_capture()`. The
function reads `current_setting('app.actor_fingerprint', true)` and
`current_setting('app.action_id', true)`; if either is `NULL` or empty, it
`RAISE EXCEPTION`s `audit_context_missing` **before** the mutating statement
completes, in the same transaction — the mutation itself never commits.
There is no fallback value, no default actor, and no "log a warning and
proceed" path: a mutation with no audit context is not merely unaudited, it
does not happen. `audit_log` is excluded from the trigger set because it
would be self-referential (auditing the audit table's own insert has no
defined actor context distinct from the mutation it is already recording).

## 7. Test specification

Each of the following is a JUnit test in the `integration-tests` module
(module map, B1-1 §2), Testcontainers-backed, PostgreSQL 16:

1. **`FlywayPrecedesAppAccessTest`** — the harness opens the `ui2_migrate`
   connection, runs Flyway, and only then opens a `ui2_app` connection;
   fails if any `ui2_app` connection succeeds before Flyway's `migrate()`
   call returns.
2. **`FlywaySecondApplyIsNoOpTest`** — calls `migrate()` twice against the
   same database and asserts the **second** `MigrateResult.migrationsExecuted
   == 0` (not merely that the call does not throw); a change that made the
   second apply silently re-run a statement passes "does not throw" but
   fails this assertion.
3. **`Ui2AppDeniedDdlTest`** — opens the `ui2_app` connection **outside**
   the assertion block, then attempts `CREATE TABLE` and asserts the
   thrown `SQLException.getSQLState()` equals `42501`
   (`insufficient_privilege`); opening the connection first means a
   connection failure (wrong credential, unreachable host) cannot be
   mistaken for the DDL denial this test exists to prove.
4. **`AuditContextMissingFailsClosedTest`** — per
   `UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` §4's
   `AuditContextIntegrationTest` spec: a raw `ui2_app` mutation without
   `SET LOCAL app.actor_fingerprint`/`app.action_id` fails with
   `audit_context_missing`, and a follow-up `SELECT` proves no row was
   written to the target table.
5. **`AuditAtomicityTest`** — sets both session variables, mutates, then
   rolls back; asserts zero matching `audit_log` rows survive.
6. **`AuditCoverageCompletenessTest`** — enumerates
   `information_schema.triggers` and asserts `trg_audit_<table>` exists for
   all eight tables of §6, failing if a future migration adds a
   mutation-bearing table without wiring its trigger.
7. **`DirectAuditLogWriteDeniedTest`** — as `ui2_app` (opened outside the
   assertion), attempts a raw `INSERT` on `audit_log`; asserts `SQLState
   42501`.
8. **`ProvenanceForeignKeyTest`** — attempts a `NULL` and a dangling
   `provenance_id` insert into `cp_inventory_projection`; asserts both are
   rejected by the constraint, proving the FK exists rather than merely
   that a well-formed row happens to work.
9. **`NoRawOutputColumnTest`** — statically enumerates every column `V1`
   creates and asserts none matches the `discard_raw` denylist naming
   pattern for a device transcript/response.
10. **`MigrationLintTest`** — greps `V1__*.sql` for the C1 §8 denylist
    (`SERIAL`, `BIGSERIAL`, unqualified proprietary functions) and fails if
    a new construct appears without a matching §8 entry in the same PR.

None of these tests proves anything beyond its own assertion: test 2 proves
zero migrations ran on the second apply, not that the schema is otherwise
correct; test 3/7 prove the specific `SQLState`, not that `ui2_app` is
denied every conceivable privilege; test 6 proves trigger existence, not
trigger correctness (tests 4–5 cover that separately).

## 8. Acceptance criteria

- **AC-1.** `V1__initial_schema.sql` exists at the path in §3 and applies
  cleanly to a fresh PostgreSQL 16 instance as `ui2_migrate`.
- **AC-2.** All nine tables of §4 exist with the exact columns, types, and
  nullability of C1 §3.2–§3.6; every identifier column is `TEXT`, never
  numeric, except `audit_log.audit_id`.
- **AC-3.** Every FK in §4's table exists and is enforced (test 8).
- **AC-4.** `jobs.capability_id` and `provenance_records.capability_version`
  are `TEXT` with no `FOREIGN KEY` constraint in `V1`.
- **AC-5.** The grants of §5 are exactly as stated; `ui2_app` holds no DDL
  privilege (test 3) and cannot write `audit_log` directly (test 7).
- **AC-6.** `trg_audit_<table>` exists on all eight tables of §6 (test 6)
  and enforces fail-closed behaviour (test 4) and atomicity (test 5).
- **AC-7.** No column in `V1` is named or documented as holding raw device
  output (test 9).
- **AC-8.** A second Flyway apply executes zero migrations (test 2).
- **AC-9.** No undocumented PostgreSQL-specific construct appears (test 10).
- **AC-10.** `capability_registry`, `role_bindings`/`sessions`/
  `actor_authz_state`/`authz_decisions` (C3), and any C7 table are absent
  from `V1` (§4's exclusion list).

## 9. Validation plan

```
./ui2/gradlew -p ui2 integrationTest --tests "*Flyway*" --tests "*Audit*" --tests "*Ui2App*" --tests "*Provenance*"
./ui2/gradlew -p ui2 check
podman build --file ui2/Containerfile --tag nexus-ui2:local ui2
python3 -m pytest -q -p no:cacheprovider tests/test_architecture_convergence.py tests/test_cold_start_budget.py
python3 scripts/repository_privacy_check.py
```

## 10. Worker route

**Sonnet 5, normal.** This movement is deterministic implementation against
a frozen contract (C1, already Product-Owner-approved) — no new
architecture, storage, or security-boundary decision is on the table, only
literal SQL and JUnit tests. Extended thinking would be more than the step
needs.

## 11. Open items for the Product Owner

1. **`devices`/`endpoints`/`credential_references` creation timing.** §2
   resolves an ambiguity in C1 §3.1's "Owner: `B1-4b`" label by having `V1`
   create these tables (required by FK dependency order) and treating
   `B1-4b` as owning the column set and onboarding logic, not the table's
   existence. Confirm this reading, or amend C1 §3.1 to say so explicitly.
2. **`V<n>` allocation across concurrent B1 movements.** C1 §3.3 defers
   `C2`'s job lifecycle columns and `C4`'s `capability_registry` to later,
   additive migrations, but no document assigns which movement (`B1-3`,
   `B1-4`, `B1-4b`) ships which `V<n>` file or in what order, and Flyway's
   version sequence is a single global ledger. A collision or gap is a
   process risk this contract does not resolve.
3. **`job_steps`/`jobs` unusable until `B1-4`.** Between `B1-2` and `B1-4`,
   `jobs`/`job_steps` exist with identity columns only and no state machine
   — no job can legally transition. This is expected per C1 §3.3 but is
   worth the PO's explicit acknowledgement since it means `B1-2`'s own
   acceptance run never inserts a `jobs` row through any code path other
   than direct test SQL.
