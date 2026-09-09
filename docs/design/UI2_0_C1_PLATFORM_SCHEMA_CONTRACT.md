# UI 2.0 — C1: platform & schema contract

## Status

**DRAFT — FOR PRODUCT OWNER FREEZE.** Produced 2026-09-09 as `B0` movement 1
(`project/backlog.json` id `ui2_b0_c1_platform_schema_contract`), against
`docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — PRODUCT OWNER APPROVED,
2026-09-09) and `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` (BASELINE,
revision 2). This document is a **contract, not code**: no `ui2/` source, no
Flyway migration file, no Docker artefact is written or authorized here. It
defines the UI 2.0 (Java) product's own PostgreSQL schema ownership, Flyway
as the sole migration authority, the projection-table model, the audit
mechanism that exists from the first mutation, per-component secrets, key
custody for backup artefacts, and the data classes the schema carries. `B1-2`
(the Flyway `V1` movement) implements this contract; it does not re-derive
it.

Runs concurrently with `C2` (job execution contract). This document owns the
tables; `C2` owns job/lease/outcome semantics. Where a table below needs a
column `C2` owns, this document names the column by reference and does not
define its values or transitions.

---

## 1. Scope and authority chain

**In scope:** UI 2.0's own PostgreSQL schema — ownership, migration
authority, the core B1 tables at DDL-sketch level, data classes and
retention, the provenance record, per-component secrets, backup-artefact key
custody, privacy/identity rules as they land in the schema, Oracle
portability exceptions, and acceptance criteria for the `B1-2` migration
movement.

**Out of scope:** any `ui2/` source, Gradle/Docker artefacts (`B1-1`), the
job state machine (`C2`), identity/session/RBAC tables beyond the foreign
keys this document reserves for them (`C3`), the capability registry and
gate-resolution rules (`C4`), amendment text (`C5`), the extraction contract
(`C6`), and the backup/artefact/restore engine (`C7`). Device contact of any
kind is out of scope; this is a document-only movement.

**Authority chain, highest first:**

1. `AGENTS.md` — durable constitution: identity law (opaque identifiers),
   raw-evidence law, sensitive-identity reporting law, privacy/DLP.
2. `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN) — binding direction and
   Phase 0 decisions this document must not reopen: `RUNTIME-DIRECTION`,
   `RAW-RETENTION` (default NO), `FREEZE-SLICING`, `DIRECTORY-POSTURE`
   (conditional), acceptance sentences A-1…A-3, and §1 item 6 ("independent
   schema… Flyway is its only migration authority").
3. `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` (BASELINE rev 2) — §3.5
   (provenance record after `discard_raw`), §5 B0-1 scope, §10 (Astra's
   disposition table).
4. `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` (DRAFT, amended by the items
   above) — §4 (shell boundary; not this document's concern except where it
   names storage), §6.2/§6.4 (backup objects and raw-output handling, which
   `C7` implements but this document's key-custody and data-class rules must
   not contradict), §9 (storage: PostgreSQL recorded, not re-derived; Oracle
   portability rules), §10 (invariants).
5. `docs/design/PCP_STORAGE_ENGINE_DECISION.md`, `PRIVACY_AND_DATA_HANDLING.md`
   ("Distributed evidence store"), `utils/recovery_key_custody.py`,
   `docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md` — the existing
   Line-1 custody/secret discipline this document extends rather than
   re-derives (§6, §7).

### New finding: `C1-1` — audit from the first mutation

`UI2_0_DEVELOPMENT_WORKFLOW.md:352` and `project/backlog.json`'s entry for
this movement both cite this finding as a correction to "the brief's §11.5".
That citation is stale: `docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md`
has no §11 — it ends at §8. The actual correction is recorded there, §8
(Product Owner directive), the sentence beginning "Correction to §6.9/§7
wording":

> "'audit from the first mutation' was attributed to SR-D8/DO-D8, which are
> key-custody findings. It is a new finding, carried into `C1` under its own
> id."

`SR-D8`/`DO-D8` (brief §4.1, `K-7`) is the finding that "encrypted at rest
under the application role's key" (`UI2_0_ARCHITECTURE_DESIGN.md` §5.2) names
no key source, custody or rotation, and joins the existing
`recovery_offhost_key_custody` backlog item — a question of **where an
encryption key lives**. "Audit from the first mutation" is a different
question — **when the audit trail begins to exist and whether a mutation
without a corresponding audit row is possible** — and was never actually
about key custody; the brief's own §7 reconciliation table lists key custody
(`K-7`) and per-component secrets (`K-8`) as the two items carried into `C1`
and does not separately list this one, confirming the omission this document
now corrects.

**This document mints finding id `C1-1` for "audit from the first
mutation"**, used consistently below (§3, §9) instead of `SR-D8`/`DO-D8`.
`C1-1`'s mechanism is specified in §3.5 and its acceptance criteria in §9.

---

## 2. Schema ownership and Flyway rules

### 2.1 Ownership

UI 2.0 owns one PostgreSQL schema, in a **dedicated database instance** for
this product (never shared multi-tenant, mirroring the existing DEV.3.3 rule
for Line-1's opt-in Postgres backend — `PRIVACY_AND_DATA_HANDLING.md`
"Distributed evidence store"). It is a **different database** from whatever
instance Line-1's `SECURITYEXPERT_EVIDENCE_BACKEND=postgres` mode may be
pointed at in a given deployment; nothing in this contract assumes or
requires the two to share a server, and no table defined here is read or
written by Line-1 code (`RUNTIME-DIRECTION`, P-1).

Line-1's own migration path — `utils/db_migrations.py`,
`migrations/postgres/*.sql`, `utils/evidence_backend.py`'s per-backend
`_ensure_schema` — is **provenance only** (workflow P-4): read for the
precedent it sets (versioned SQL, advisory-lock-guarded apply, a tracked
migration-history table), never imported, never extended to cover a UI 2.0
table, never sharing a migration-history table with UI 2.0's own.

### 2.2 Flyway as sole migration authority

- Every DDL change to the UI 2.0 schema is a numbered Flyway SQL file under
  `ui2/service/src/main/resources/db/migration/V<n>__<snake_case_name>.sql`
  (`B1-1`'s module layout; exact path confirmed there, not moved by this
  document). `V<n>` is a strictly increasing integer, one per file, no gaps
  required but no reuse ever.
- Flyway's own `flyway_schema_history` table is the applied-version ledger;
  this document does not redefine it.
- **Forbidden:** any DDL from application code (no ORM `hbm2ddl`/
  auto-migrate, no `CREATE TABLE IF NOT EXISTS` at service startup — the
  pattern Line-1's evidence-backend `_ensure_schema` uses is explicitly not
  carried over, `DEV.4.6`), any DDL issued by hand against a live instance
  outside a Flyway file, any DDL issued by Python.
- **Migration role ≠ application role** (`DEV.4.6`): the Flyway apply step
  connects as a distinct `ui2_migrate` role holding `CREATE`/`ALTER`/`DROP`
  on the UI 2.0 schema; the running service, worker and scheduler processes
  connect as `ui2_app` (or narrower — §2.4), which holds no DDL privilege at
  all. A schema change is therefore never reachable from a compromised or
  buggy running process, only from the deployment-controlled Flyway apply
  step (§6 names the credential for each).
- **Rollback posture:** forward-only. Flyway Community (assumed; no
  commercial edition decided here) has no automatic `undo`. A defect in an
  applied migration is corrected by a new, later-numbered migration, never by
  editing or deleting an applied file. `V<n>` files are immutable once
  applied to any shared environment (including local Testcontainers CI runs
  that exercise the real sequence); a pre-apply defect caught in review may
  still be edited freely.
- **Naming:** `V<n>__<verb>_<subject>.sql`, e.g. `V1__initial_schema.sql`,
  `V2__add_cp_inventory_projection.sql`. A migration file's own header
  comment states which table(s) it touches and which of this contract's
  tables (§3) or later contracts (`C2`–`C7`) it is realizing.

### 2.3 Two schema layers, one migration authority

Flyway owns both: (a) the **control-plane and audit layer** this document
sketches (§3), and (b) every later **projection table** a capability adds
(workflow §3.1/§3.2). A capability's `Implement` movement (workflow §3.3
step b) ships its own Flyway migration for its projection table(s); it does
not touch the audit mechanism (§3.5) or the identity tables (§3.1–§3.3) —
those are amended only through a `C1`-successor contract movement, never
silently by a feature build.

### 2.4 Roles, concretely

| Role | Privilege | Used by |
| --- | --- | --- |
| `ui2_migrate` | `CREATE`/`ALTER`/`DROP` on the UI 2.0 schema; `INSERT`/`SELECT` on `flyway_schema_history` | the Flyway apply step only (CI job or a deployment-controlled operator action), never a running service |
| `ui2_app` | `SELECT`/`INSERT`/`UPDATE`/`DELETE` on the tables it needs, explicitly **no** `INSERT`/`UPDATE`/`DELETE` on `audit_log` (§3.5), no DDL | the Java service, worker and scheduler processes (a single role is sufficient at B1 scope; splitting it further is a later, non-blocking hardening item, not required by this contract) |

---

## 3. Core tables for B1 — DDL-level sketches

These are **sketches**: illustrative column sets and constraints that fix
the contract's load-bearing decisions (identity shape, audit linkage, data
class, ownership boundary). `B1-2` writes the literal Flyway SQL; it may add
non-load-bearing columns (e.g. an index, a `NOT NULL` default) without a
`C1`-successor movement, but may not remove, retype, or reassign the
ownership of anything fixed here without one.

### 3.1 Ownership matrix (`AC-1`)

| Table | Owner | Data class (§4) |
| --- | --- | --- |
| `devices` | `B1-4b` (placeholder identity only) | control-plane (not one of the five evidence classes; see §4 note) |
| `endpoints` | `B1-4b` (placeholder identity only) | control-plane |
| `credential_references` | `B1-4b` (placeholder identity only) | control-plane, secret-adjacent (§6) |
| `jobs` | `C1` (identity/audit-linkage columns only); `C2` (lifecycle columns, by reference) | job log |
| `job_steps` | `C1` (identity columns only); `C2` (lifecycle columns, by reference) | job log |
| `audit_log` | `C1` | audit |
| `provenance_records` | `C1` | evidence (the linkage layer; §5) |
| `cp_inventory_projection` | `C1` (first-capability sketch); `C4` (capability identity, by reference) | evidence |
| `secrets_metadata` | `C1` | control-plane, secret-adjacent (§6) — never the class "secret" itself: no secret value is ever a column here |

No table above is created without an owner and a data class; none is
"orphaned" per `AC-1`. Tables this document deliberately does **not**
sketch: `role_bindings`, `sessions`, `actor_authz_state`, `authz_decisions`
(`C3`); `capability_registry` and its gate-resolution rows (`C4`); any
`BackupProfile`/`BackupAssignment`/`BackupSchedule`/`BackupRun`/artefact
manifest table (`C7`) — those contracts define their own DDL against this
document's rules, not the other way around.

### 3.2 Identity placeholders (`devices`, `endpoints`, `credential_references`) — owned by `B1-4b`

```sql
CREATE TABLE devices (
    device_id       TEXT        PRIMARY KEY,   -- opaque, application-generated; never numeric, never derived from a hostname/serial
    vendor_hint     TEXT        NOT NULL,
    registration_source TEXT    NOT NULL,       -- 'manual_registration' at B1-4b scope; REL-DISCOVERY adds enrollment sources later
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_test_target  BOOLEAN     NOT NULL DEFAULT false   -- B1-4b's "test target" flag (workflow B1 step 4b)
);

CREATE TABLE endpoints (
    endpoint_id     TEXT        PRIMARY KEY,   -- opaque
    device_id       TEXT        NOT NULL REFERENCES devices(device_id),
    transport_kind  TEXT        NOT NULL,      -- 'ssh_exec' at B1 scope (only transport B1-4/step 4 implements)
    address_ref     TEXT        NOT NULL,      -- the management address; CLASS 2 (§7) — never appears in audit_log free text or log output
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE credential_references (
    credential_reference_id TEXT PRIMARY KEY,  -- opaque
    purpose         TEXT        NOT NULL,      -- e.g. 'device_read', 'backup_cp_gaia' — never a free-text label that could carry a secret
    backend_pointer TEXT        NOT NULL,      -- opaque pointer into the secret backend (§6); never the secret value itself
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`B1-4b` owns the full column set (indices, additional lifecycle columns for
enrollment); this sketch fixes only: identifiers are opaque `TEXT` (identity
law), `address_ref` is a CLASS 2 field that must never be copied into
`audit_log.before_state`/`after_state` as free text beyond the row's own
column value (the audit trigger captures the row, not a derived narrative —
§3.5), and `credential_references` never stores a secret value (§6).

### 3.3 `jobs` / `job_steps` — identity columns only; `C2` owns lifecycle

```sql
CREATE TABLE jobs (
    job_id          TEXT        PRIMARY KEY,   -- opaque, application-generated
    job_type        TEXT        NOT NULL,      -- closed vocabulary; C4's action registry is the source of truth
    capability_id   TEXT,                       -- FK to C4's capability_registry once C4 lands; TEXT + no enforced FK until then (see note)
    target_device_id TEXT       NOT NULL REFERENCES devices(device_id),
    submitted_by_actor_fingerprint TEXT NOT NULL,  -- opaque fingerprint, C3's actor identity shape
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now()
    -- C2 adds: state, lease_owner, lease_expires_at, heartbeat_at, outcome,
    -- attempt/duplicate-detection columns, approver identity for scheduled
    -- runs, schedule optimistic-concurrency token. Not sketched here.
);

CREATE TABLE job_steps (
    job_step_id     TEXT        PRIMARY KEY,   -- opaque
    job_id          TEXT        NOT NULL REFERENCES jobs(job_id),
    step_index      INTEGER     NOT NULL
    -- C2 adds: kind, outcome, matched_expectation, duration_ms, error_class,
    -- output_fingerprint/output_bytes (never raw output — §4/§5, discard_raw).
);
```

**Note on the deferred `capability_id` FK.** `C1` and `C2` are concurrent
movements; `C4` (capability registry) is a later `B0` movement. This
document reserves the column and its semantics (an opaque id resolving into
`C4`'s registry) so `B1-2` can write `jobs.capability_id` directly against
`C4`'s eventual table without a later `ALTER TABLE`; `B1-2` adds the literal
`FOREIGN KEY` constraint once `C4` freezes and its migration lands (a small,
additive migration, not a rework of this table). `B1-2` must not proceed
past this without `C4`'s freeze — per the workflow's own dependency-gate
rule (§5 B0 preamble: "`B1` starts when `C1`–`C4` and the authorization/audit
rows are frozen").

`jobs`/`job_steps` carry the **job log** data class (§4): identity,
timestamps, outcome classification. Never a device transcript byte (design
§6.4's `discard_raw`, carried into the Java engine unchanged by workflow
§3.2's "Evidence writer").

### 3.4 `cp_inventory_projection` — the first capability's evidence table

```sql
CREATE TABLE cp_inventory_projection (
    projection_id   TEXT        PRIMARY KEY,   -- opaque
    device_id       TEXT        NOT NULL REFERENCES devices(device_id),
    endpoint_id     TEXT        NOT NULL REFERENCES endpoints(endpoint_id),
    job_id          TEXT        NOT NULL REFERENCES jobs(job_id),
    provenance_id   TEXT        NOT NULL REFERENCES provenance_records(provenance_id),
    product_version TEXT,                       -- derived fact; UNKNOWN-capable (nullable, never a sentinel string)
    ha_state        TEXT,                       -- derived fact; UNKNOWN-capable
    collected_at    TIMESTAMPTZ NOT NULL
);
```

Every derived-fact column here is nullable, never a magic string, so
`UNKNOWN` is representable as `NULL` plus the provenance row's own
completeness signal (§5) — never invented certainty (`AGENTS.md`
UNKNOWN/fail-closed law). Every row **must** carry a `provenance_id`; there
is no code path that writes a projection row without one (a `NOT NULL`
foreign key, not a convention). This is the pattern every later capability's
own projection table (owned by that capability's `Implement` movement, §2.3)
follows: identity columns to `devices`/`endpoints`/`jobs`, a mandatory
`provenance_id`, and only derived facts — never a raw fragment.

### 3.5 `audit_log` and the `C1-1` mechanism

```sql
CREATE TABLE audit_log (
    audit_id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    table_name          TEXT        NOT NULL,
    row_pk              TEXT        NOT NULL,     -- the mutated row's own opaque identifier, as text
    operation            TEXT        NOT NULL CHECK (operation IN ('INSERT','UPDATE','DELETE')),
    actor_fingerprint    TEXT        NOT NULL,     -- from the session-scoped app.actor_fingerprint setting; never NULL
    action_id            TEXT        NOT NULL,     -- the closed action-registry id (C4) that authorized the mutation
    occurred_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    before_state          JSONB,                     -- NULL on INSERT; the row's own control-plane columns only, never evidence-raw content
    after_state           JSONB,                     -- NULL on DELETE
    correlation_run_id   TEXT                       -- nullable; links to jobs.job_id when the mutation is job-caused
);

REVOKE INSERT, UPDATE, DELETE ON audit_log FROM ui2_app;
GRANT SELECT ON audit_log TO ui2_app;   -- read path for the Audit & logs screen (workflow B1 step 8)
```

**Mechanism (`C1-1`): a database trigger, not an application convention.**
Every mutation-bearing table listed in §3.1 (`devices`, `endpoints`,
`credential_references`, `jobs`, `job_steps`, and every later projection or
policy table added by a capability or a later `C`-contract) carries an
`AFTER INSERT OR UPDATE OR DELETE FOR EACH ROW` trigger executing one shared
function:

```sql
CREATE OR REPLACE FUNCTION fn_audit_capture() RETURNS trigger AS $$
DECLARE
    v_actor  TEXT  := current_setting('app.actor_fingerprint', true);
    v_action TEXT  := current_setting('app.action_id', true);
    v_pk_col TEXT  := TG_ARGV[0];
    v_row    JSONB := to_jsonb(COALESCE(NEW, OLD));
BEGIN
    IF v_actor IS NULL OR v_actor = '' OR v_action IS NULL OR v_action = '' THEN
        RAISE EXCEPTION 'audit_context_missing on %.%: actor or action not set in this transaction',
            TG_TABLE_NAME, v_pk_col;
    END IF;

    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id,
                           before_state, after_state, correlation_run_id)
    VALUES (TG_TABLE_NAME, v_row ->> v_pk_col, TG_OP, v_actor, v_action,
            CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE to_jsonb(OLD) END,
            CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE to_jsonb(NEW) END,
            current_setting('app.correlation_run_id', true));
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE TRIGGER trg_audit_jobs
    AFTER INSERT OR UPDATE OR DELETE ON jobs
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('job_id');
-- one CREATE TRIGGER per mutation-bearing table, each naming its own PK column
```

The Java service's transaction interceptor — the same interceptor that
already runs the `E1`–`E6` gate chain (design §5.3, `C3`'s eventual
implementation) — issues `SET LOCAL app.actor_fingerprint = ...` and
`SET LOCAL app.action_id = ...` (and, when applicable,
`app.correlation_run_id`) at the start of every transaction that will mutate
a table in §3.1, before the mutation itself. `SET LOCAL` scopes the value to
the current transaction only; it is never visible to another concurrent
transaction and is discarded on commit/rollback (no persistent session
state, no cross-request leakage).

**Why this is structurally impossible to bypass, not merely convention:**

1. `fn_audit_capture()` runs `SECURITY DEFINER`, owned by `ui2_migrate`.
   `ui2_app` cannot `INSERT`/`UPDATE`/`DELETE` `audit_log` directly (grants
   above) — the only way an audit row is ever written is through a
   mutation-bearing table's own trigger.
2. The trigger fires in the **same transaction** as the mutation, before
   commit. If the actor/action context is missing, `fn_audit_capture()`
   raises, the triggering statement fails, and — because it is the same
   transaction — the mutation itself never commits. A mutation and its audit
   row commit atomically or neither does; there is no window where one
   exists without the other.
3. A raw `INSERT`/`UPDATE`/`DELETE` issued against a mutation-bearing table
   from any path that does not set the two session variables first —
   including a bug, a bypassed service layer, or a direct database client —
   fails with `audit_context_missing`, not silently succeeds without an
   audit row.

**Test-enforced (`B1-2`, §9):** a Testcontainers-backed test connects
directly (bypassing the Java service layer entirely) and issues a raw
`INSERT` on a mutation-bearing table without setting the session variables,
asserting the statement raises `audit_context_missing`; a second test sets
the variables, performs the mutation, and asserts exactly one matching
`audit_log` row exists in the same transaction; a third test rolls back a
mutating transaction and asserts no `audit_log` row survives (transactional
atomicity, not merely "usually true together" — echoing `AGENTS.md`
"Evidence laws"' pattern of never collapsing two invariants that are usually
true together). A fourth, coverage-completeness test enumerates
`information_schema.triggers` and asserts one `trg_audit_<table>` exists for
every table in §3.1's mutation-bearing set — so a future migration that adds
a table without wiring the trigger fails this test, not merely a code
review.

`before_state`/`after_state` never carry a value classified as raw device
output: every table this trigger attaches to is a control-plane or
projection table (§3.1), never a raw-transcript store (none exists,
`RAW-RETENTION` NO). The trigger captures whatever the table's own DDL
defines — if a table never has a raw-output column, its audit diff cannot
carry one either; this is enforced by §3's ownership matrix leaving no such
column in any table this contract or its successors define.

### 3.6 `secrets_metadata`

```sql
CREATE TABLE secrets_metadata (
    secret_id       TEXT        PRIMARY KEY,   -- opaque
    component       TEXT        NOT NULL,      -- 'service' | 'worker' | 'scheduler' | '<adapter name>'
    purpose         TEXT        NOT NULL,      -- e.g. 'db_dsn', 'ldap_service_account', 'recovery_vault_master_key'
    backend_kind    TEXT        NOT NULL CHECK (backend_kind IN ('env_file','vault_reference')),
    reference_pointer TEXT      NOT NULL,      -- the *_FILE path or vault key id — never the secret value
    rotation_policy_ref TEXT,                   -- nullable free-text pointer to a rotation runbook/policy id
    rotated_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

No column in this table can hold a secret value by construction: every
column is either an enum, an opaque identifier, a filesystem path, or a
timestamp. §6 defines what populates it.

---

## 4. Data classes and retention per class

Building on the precedent workflow §3.5 already establishes — "job logs,
audit entries and complete encrypted backup artefacts are distinct data
classes" plus the recurring "sanitized evidence fragment" term — this
document names and completes the **five data classes** this contract's
`output_contract` requires, and states how each maps onto
`PRIVACY_AND_DATA_HANDLING.md`'s repository-shareability `CLASS 0/1/2`
scheme (a different, orthogonal axis — never conflated with these five,
matching `AGENTS.md`'s explicit warning about the taxonomy's own unrelated
`CLASS_0`.. namespace).

| Data class | What it is | Owner table(s) | Retention | `PRIVACY_AND_DATA_HANDLING.md` tier |
| --- | --- | --- | --- | --- |
| **Job log** | Execution narrative of a job/step: timestamps, outcome, step kind, matched-expectation flag, duration, error class, byte/line counts — never a device transcript | `jobs`, `job_steps` (`C1` identity + `C2` lifecycle) | per-table policy, PO-set at a later movement; default proposal 400 days, mirroring design §5.4's `authz_decisions` default | CLASS 2 (carries opaque device/entity identifiers; the whole instance is CLASS 2, §7) |
| **Audit** | Append-only record of every mutation to control-plane state: who, when, what changed | `audit_log` | indefinite; no deletion path in this contract (mirrors the Line-1 operational-write ledger precedent, `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §8: "append-only, effectively unbounded... no deletion path") | CLASS 2 |
| **Evidence (projection)** | Derived facts from device collection, each linked to a provenance record; never raw device output | `provenance_records`, every capability projection table (e.g. `cp_inventory_projection`) | per-capability policy, owned by the feature-contribution contract (`ui2_b0_baseline_directory`) — referenced, not decided here | CLASS 2 |
| **Encrypted artefact** | Backup/recovery byte content | never a database row (design §6.2: "no artefact byte ever enters a database row"); `C7` owns the manifest/pointer table that references it | `C7`'s retention-policy contract, out of this document's scope | CLASS 2 bytes at rest on the recovery volume, unchanged from today's posture |
| **Sanitized fragment** | The bounded, redaction-filtered "permitted sanitized evidence fragment" component of a provenance record, where the data class allows one | `provenance_records.sanitized_fragment` (§5) | same lifecycle as its owning provenance row | CLASS 2 as stored; the type a future CLASS 1 export would draw from, if one is ever authorized — no such export exists or is decided here |

**Note on "control-plane" rows** (`devices`, `endpoints`,
`credential_references`, `secrets_metadata`): these are not evidence in the
sense above — they describe the product's own configuration of itself, not
a derived fact about a monitored device — and are listed in §3.1's ownership
matrix without forcing them into one of the five classes. They are CLASS 2
by the same instance-wide rule (§7).

---

## 5. Provenance record schema

Per workflow §3.5 (`R-06`), completed for the schema layer:

```sql
CREATE TABLE provenance_records (
    provenance_id       TEXT        PRIMARY KEY,   -- opaque
    run_id               TEXT        NOT NULL,       -- the job/run this fact was produced by
    step_id               TEXT        NOT NULL,       -- the specific step within the run
    parser_version        TEXT        NOT NULL,
    capability_version     TEXT        NOT NULL,       -- FK to C4's capability registry once C4 lands, by convention (§3.3 note)
    capture_artifact_id   TEXT,                        -- nullable: the fixture/capture this fact traces to, when sourced from CAP-OFFLINE fixtures rather than a live run
    source_location        TEXT        NOT NULL,       -- where within the output the fact was found (a locator, never the raw text itself)
    sanitized_fragment     TEXT,                        -- nullable, bounded, redaction-filtered; present only when the data class permits one
    fingerprint_sha256      TEXT        NOT NULL,       -- identifies and integrity-protects the (discarded) raw source; does not prove its content
    collected_at            TIMESTAMPTZ NOT NULL
);
```

All fields from workflow §3.5 are present: run/step id, parser + capability
version, capture/artifact id, source location, and the permitted sanitized
evidence fragment. `fingerprint_sha256` is the hash workflow §3.5 names: it
"identifies the source and protects integrity; it does not by itself prove
the content of something that was deleted."

**What the UI/API may claim about discarded raw content:**

- **May**: show the fingerprint, show that a raw fragment existed and was
  discarded (never imply it never existed), show `source_location`,
  `parser_version`, `capability_version`, and `sanitized_fragment` when
  present.
- **May not**: offer a "view raw" affordance backed by nothing, claim the
  fingerprint "proves" the derived fact's content (it proves identity and
  integrity of a since-deleted source, not the source's content to a reader
  who never saw it), or imply raw content is retrievable when it was
  discarded (workflow §3.5, verbatim: "the UI never implies raw content is
  retrievable when it was discarded"). This is a schema-adjacent rule: no
  column exists that a future UI could point at to violate it, and the
  absence of any raw-content column is itself the enforcement.

---

## 6. Secrets and key custody

### 6.1 Per-component secret scopes (`AC-4`)

No shared "app" secret exists. Every component that needs a credential
resolves it independently through the same mechanism (§6.2), under its own
name, so one component's secret can rotate, be revoked, or be scoped down
without touching another's.

| Component | Secret(s) | Purpose | Rotation | Outage behaviour |
| --- | --- | --- | --- | --- |
| **service** (HTTP/API, §5.3 design) | `ui2_app` DB DSN | reads/writes control-plane and projection tables | replace the mounted file + reload/restart; no code change | fails closed: refuses to start (§6.3) |
| **worker** (device transport, backup execution) | `ui2_app` DB DSN (may be the same role as service at B1 scope, §2.4); per-vendor/profile backup credential (`C7`, following the existing `D4` pattern) | executes jobs, contacts devices under `C2`/`C7` | backup credential rotates independently of the DB DSN and of any other component's secret (mirrors `D4` §5: "rotatable and revocable independently of the inventory secret") | fails closed: a job requiring an unresolvable credential is refused before any device contact, never attempted with a stale/empty one |
| **scheduler** | `ui2_app` DB DSN (read-mostly: evaluates due schedules) | schedule evaluation only, no device contact of its own | as service | fails closed: refuses to evaluate schedules; no silent skip that looks like "nothing due" |
| **LDAP re-validation adapter** (`C3`) | read-only directory service account password | periodic group-membership re-read (design §7.6) | operator-driven, independent of the operator's own bind credential (which is never stored at all — used once, cleared, design §7.3) | design §7.6's existing rule: a failed re-read leaves the actor `AUTHZ_NOT_EVALUATED`, refused at submission — never logged out by a directory hiccup, never granted by a missing check |
| **Migration apply step** | `ui2_migrate` DB DSN | Flyway apply only (§2.4) | rotates on the same cadence as any other privileged operational credential; never held by a long-running process | fails closed: the deployment-controlled apply step aborts; no partial schema state is left reachable by `ui2_app` beyond what already committed |

### 6.2 Mechanism — `DEV.2.1`/`DEV.2.2`, carried over unchanged

Every secret above is resolved the same way Line-1 already resolves one
(`utils/runtime_config_source.py`, `DEV.2.1`), re-implemented in Java for
each component's own process:

1. `<COMPONENT>_<PURPOSE>_FILE` — an absolute path (Docker/Kubernetes
   secret-mount convention: read-only bind mount, matching `deploy/secrets/`)
   whose UTF-8 contents (stripped) are the value. **Wins** when set.
2. `<COMPONENT>_<PURPOSE>` — a plain environment variable, local/dev
   convenience only, never used in the server deployment shape.
3. Neither set: the component treats the secret as absent.

Naming convention (illustrative, `C7`/`C3` finalize their own concrete
variable names against this pattern): `SECURITYEXPERT_UI2_SERVICE_DB_DSN_FILE`,
`SECURITYEXPERT_UI2_WORKER_BACKUP_CP_GAIA_CREDENTIAL_FILE`,
`SECURITYEXPERT_UI2_SERVICE_LDAP_SERVICE_ACCOUNT_PASSWORD_FILE`,
`SECURITYEXPERT_UI2_MIGRATE_DB_DSN_FILE`. Every one is a **distinct**
variable; none is reused across components or purposes (the concrete
`no shared 'app' secret` invariant).

**Fail-closed outage behaviour (`DEV.2.1`/`DEV.2.2` unchanged in kind):** a
`_FILE` variable that is **set but unreadable, missing, or empty** is a hard
failure at resolution time — the component refuses to start (service,
worker, scheduler) or refuses the specific action needing it (a job
requiring a credential that fails to resolve), never falls through to the
plain variable, a default, or a degraded-but-functional mode. This mirrors
`runtime_config_source.py`'s own contract exactly ("Fail closed: a
`<NAME>_FILE` that is set but missing/unreadable/empty raises
`RuntimeConfigError`") and `DEV.2.2`'s startup preflight pattern
(`utils/persistent_secret_material.py`): each UI 2.0 component runs an
equivalent preflight at boot that verifies every secret it declares is
present, non-default, and resolvable **before** accepting a request or
claiming a job — refusing to start otherwise. No secret value ever appears
in a log line or an exception message; only the variable/component/purpose
name does.

### 6.3 Backup artefact key custody — `recovery_key_custody` semantics, inherited not re-decided

`C7` owns the backup engine and its artefact manifest table; this document
fixes only the **custody boundary** that table must respect, inherited
unchanged from `utils/recovery_key_custody.py`'s existing envelope model
(the same model UI 2.0's recovery store reuses, per design §6.2: artefacts
live on the recovery-store volume "exactly as `RB.x` defines"):

- **Envelope encryption**: a vault **master key** wraps a per-artefact
  **DEK** (data encryption key). Any table or manifest referencing an
  artefact stores only the **wrapped-DEK blob** and an opaque **`key_id`**
  fingerprint — never raw master-key bytes, never a raw DEK.
- **Custody today**: `LocalFileKeyCustodyBackend` — the master key resolves
  from an env var or a `0600` file on `data_root`, held in-process. This is
  explicitly **not** an off-host KMS/secret-manager; `recovery_key_custody.py`'s
  own docstring states it plainly. UI 2.0 inherits the identical posture at
  B1 scope: `secrets_metadata.backend_kind = 'env_file'` for the recovery
  vault master key, exactly as for every other component secret (§6.1/§6.2).
- **Off-host custody remains a separately tracked, unresolved item**: the
  existing backlog id `recovery_offhost_key_custody` (P0, target
  `DEPLOY.1`) — and `PCP_STORAGE_ENGINE_DECISION.md`'s own gap #9 (whether
  Postgres's standard backup posture satisfies the same off-host custody
  bar) — are **not decided by this document**. This document's schema is
  deliberately shaped so that decision can land later without a data
  migration: because only `key_id` + `wrapped_dek` are ever persisted, a
  future off-host custody backend can be swapped in by re-wrapping existing
  DEKs under a new `key_id` — old wrapped blobs stay valid until re-wrapped,
  no artefact re-encryption required.
- **Rotation**: the master key itself is not rotated by this contract (no
  rotation mechanism ships in `recovery_key_custody.py` today); when one
  ships (`DEPLOY.1` or later), it operates by minting a new `key_id` and
  re-wrapping outstanding DEKs — the `key_id` column this document requires
  in any artefact-referencing table (`C7`'s to define) is what makes that
  possible without schema change.

This section states the boundary `C7` must build inside; it does not sketch
`C7`'s manifest table (out of scope, §1).

---

## 7. Privacy and identity rules in the schema

- **The entire UI 2.0 PostgreSQL instance is CLASS 2** (`PRIVACY_AND_DATA_HANDLING.md`),
  by direct extension of the rule already stated for Line-1's own opt-in
  Postgres backend ("architecturally equivalent to local disk, not a CLASS
  1/shareable artifact") — every table in §3 carries opaque device/entity
  identifiers, management endpoint references, or audit trails over them.
  Concretely, per the existing DEV.3.3 rule, applied here: a **dedicated
  instance** for this product (§2.1), **TLS on the connection DSN** in
  production (the DSN itself is a component secret, §6), the application
  role restricted to the tables it needs (§2.4), and volume/disk encryption
  at rest wherever the deployment already encrypts other local-disk
  evidence. None of this schema, in whole or in row, is ever a CLASS
  0/1/shareable artefact; no table here is ever enumerated into a support
  bundle.
- **Identifiers are opaque** (`AGENTS.md` identity law): every `device_id`,
  `endpoint_id`, `job_id`, `job_step_id`, `provenance_id`, `credential_reference_id`,
  `secret_id` and `projection_id` in §3 is `TEXT`, application-generated,
  never a numeric surrogate, never cast, truncated, or case-normalized.
  Surrogate keys that are **not** product identifiers — `audit_log.audit_id`
  only — may use `GENERATED ALWAYS AS IDENTITY` (a pure internal sequence,
  never exposed as or compared against a device/entity identity) because the
  identity law governs identifiers, not internal row-ordering keys.
- **Corporate hostnames/serials/IPs** live only in the fields
  `PRIVACY_AND_DATA_HANDLING.md` classifies as such — concretely
  `endpoints.address_ref` in this document's own tables — and **never** in
  `audit_log.before_state`/`after_state` free text beyond that column's own
  captured value, never in `jobs`/`job_steps` job-log fields (which carry
  outcome/timing/classification, not addresses), and never in any
  `provenance_records` field other than a `sanitized_fragment` that has
  itself passed redaction (§5). No log line, error message, or audit free
  text in this schema's own design carries a raw address, serial or
  hostname as narrative text — only as the value of the one column defined
  to hold it.
- **`DIRECTORY-POSTURE` (`D-6`) stays conditional, not decided here.**
  `credential_references`/`secrets_metadata` reserve the storage pattern a
  directory service-account secret would use (§6.1's LDAP row), but this
  document enables nothing: `C3` is the contract that states the required
  access scope, secret storage and rotation, and outage behaviour for the
  actual group-reference persistence and service-account bind, and only
  after the corporate-policy verification `UI2_0_BASELINE_CONTRACT.md`
  requires is recorded does that function activate (`AC-8`).
- **`RAW-RETENTION` stays NO, not decided here.** No table in §3, present or
  future under this contract's rules, has a column that could hold raw
  device output; §5's provenance record is the entire mechanism by which a
  derived fact links back to a (discarded) raw fragment (`AC-3`, `AC-8`).

---

## 8. Oracle portability exceptions (`AC-5`)

Per design §9's rule: PostgreSQL-specific constructs are used only where a
portable equivalent does not exist, behind a named interface, with the
Oracle-side fallback documented. Every construct used above:

| Construct | Where used | Portable? | Oracle equivalent / reason none is needed |
| --- | --- | --- | --- |
| `GENERATED ALWAYS AS IDENTITY` | every internal surrogate key (`audit_log.audit_id`) | **Yes — no exception needed.** SQL-standard identity columns, supported by PostgreSQL 10+ and Oracle 12c+ | none required |
| Opaque `TEXT` identifiers, application-generated | every product identifier (`device_id`, `job_id`, …) | **Yes.** No DB-side `gen_random_uuid()`/`uuid-ossp` dependency at all — identifiers are minted in Java, never by a PostgreSQL extension | Oracle `VARCHAR2`; no extension dependency to replace |
| `TIMESTAMPTZ` | every timestamp column | **Yes** (design §9 already records this) | Oracle `TIMESTAMP WITH TIME ZONE` |
| `JSONB` | `audit_log.before_state`/`after_state`; `provenance_records.sanitized_fragment` if a fragment is ever structured rather than plain text | **No — named exception.** Used only as an opaque payload column: no `jsonb` operator (`@>`, `?`, GIN index) appears in any query this contract requires, satisfying design §9's "used only behind a named repository interface" rule; the interface is the audit-read path (workflow B1 step 8) and any provenance reader, both accessed only through jOOQ-generated, whole-value read/write | Oracle native `JSON` type (21c+) or `CLOB` with application-side validation (design §9's own stated fallback) — either is acceptable; no query behaviour depends on which |
| PL/pgSQL trigger function (`fn_audit_capture`) | `audit_log`'s mutation-capture mechanism (§3.5) | **No — named exception.** Row-level `AFTER` triggers exist in both engines; the procedural body language does not | Oracle PL/SQL `AFTER` row trigger with equivalent logic |
| `current_setting(...)`/`SET LOCAL` session variables | the actor/action audit context (§3.5) | **No — named exception.** Session-scoped context values exist in both engines; the mechanism differs | Oracle `DBMS_SESSION.SET_CONTEXT` / `SYS_CONTEXT()` |
| `SECURITY DEFINER` function | `fn_audit_capture`'s elevated write to `audit_log` | **No — named exception**, though the concept (a routine executing with its owner's privileges rather than the caller's) exists in both engines | Oracle: a definer's-rights PL/SQL procedure (the Oracle default execution model, so this is in fact simpler on that engine, not harder) |

**Not used anywhere in this contract**, deliberately, to avoid exceptions
that would otherwise be needed: partial indexes, `LISTEN`/`NOTIFY` (a `C2`
concern if the job worker ever wants push notification of newly-submitted
jobs; this document takes no position beyond noting design §9's documented
polling fallback applies there too), any PostgreSQL-only extension
(`pgcrypto`, `uuid-ossp`, `pg_trgm`), and any stored procedure or trigger
carrying **business logic** — `fn_audit_capture` is infrastructure (the
audit mechanism itself, `C1-1`), not a business rule, consistent with design
§9's "no stored procedures, no triggers carrying business logic… business
rules live in Java, the database stays a store." An Oracle migration would
additionally need the jOOQ commercial dialect licence (design §8.3, `U-J3`,
already recorded as deferred with Oracle) — unchanged by this document.

---

## 9. Acceptance criteria for `B1-2` (the Flyway `V1` movement) — `AC-6`

At least eight, each independently testable against a real (Testcontainers)
PostgreSQL 16 instance:

1. **Migration applies cleanly and idempotently.** `V1` applies to a fresh
   database with no error; re-running the Flyway apply step against an
   already-migrated database is a no-op (checksum match in
   `flyway_schema_history`, no statement re-executed).
2. **Audit bypass is rejected (`C1-1`).** A raw `INSERT`/`UPDATE`/`DELETE`
   against any mutation-bearing table (§3.1), issued without first setting
   `app.actor_fingerprint` and `app.action_id` in the same transaction,
   raises `audit_context_missing` and the mutation does not commit.
3. **Audit atomicity (`C1-1`).** With the session variables set, a
   mutation and its `audit_log` row commit together; rolling back the
   mutating transaction leaves no `audit_log` row for it (query `audit_log`
   after rollback and assert zero matching rows).
4. **Audit coverage completeness (`C1-1`).** A test enumerates
   `information_schema.triggers` and asserts a `trg_audit_<table>` exists
   for every table in §3.1's mutation-bearing set — failing if a future
   migration adds such a table without wiring its trigger.
5. **Direct write to `audit_log` is refused.** Connecting as `ui2_app` and
   attempting a raw `INSERT`/`UPDATE`/`DELETE` on `audit_log` fails with a
   permission error (the grants in §3.5); only `fn_audit_capture`'s
   `SECURITY DEFINER` execution can write there.
6. **Role separation is real.** Connecting as `ui2_app` and attempting any
   DDL (`CREATE TABLE`, `ALTER TABLE`) against the UI 2.0 schema fails with
   a permission error; only `ui2_migrate` can alter the schema.
7. **No raw-output column exists.** A static test enumerates every column
   in every table `V1` creates and asserts none is named or commented as
   holding a raw device transcript/response (a denylist check mirroring the
   `discard_raw` pattern design §6.4 already applies at the application
   layer, now also asserted at the schema layer).
8. **Provenance completeness.** Every row inserted into
   `cp_inventory_projection` (or any later capability's projection table
   following the same pattern) carries a non-null `provenance_id` that
   resolves to an existing `provenance_records` row — enforced by the
   foreign key itself; the test proves the constraint exists and rejects a
   `NULL`/dangling insert attempt.
9. **No secret value is storable in `secrets_metadata`.** A test asserts
   `secrets_metadata`'s columns are limited to the enum/opaque-pointer shape
   in §3.6 (no column capable of holding an arbitrary-length credential
   value under a plausible name), and that `reference_pointer` values used
   in the fixture set are file paths / opaque ids, never a value that would
   fail the repository privacy gate's own secret-literal detection if it
   were ever accidentally logged.
10. **Every PostgreSQL-specific construct is accounted for.** A migration
    lint step (CI, not necessarily a JUnit test) greps `V1__*.sql` for a
    fixed denylist (`SERIAL`, `BIGSERIAL`, unqualified proprietary
    functions, `pg_catalog`-only syntax) not already named in §8's table,
    and fails the build if a new one appears without a matching §8 entry
    added in the same PR.

---

## 10. Contradictions and open items for the PO (`AC-7`)

**Contradictions with FROZEN authority: none found.** Documents checked
while writing this contract: `AGENTS.md` (identity law, raw-evidence law,
sensitive-identity reporting law, privacy/DLP, `DEV.4.6`-adjacent build
lifecycle rules), `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN),
`docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` (BASELINE rev 2),
`docs/design/UI2_0_ARCHITECTURE_DESIGN.md` §4/§5.2/§6.2/§6.4/§9/§10/§11
(DRAFT, amended), `docs/design/PCP_STORAGE_ENGINE_DECISION.md`,
`PRIVACY_AND_DATA_HANDLING.md`, `utils/recovery_key_custody.py`,
`docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md`,
`docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`,
`docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md`. None of
`UI2_0_ARCHITECTURE_DESIGN.md` §11's existing contradiction rows
(`UA-1`…`UA-8`) are engaged or extended by this document's schema-only
scope.

**Open items flagged, not decided here:**

1. **Stale internal citation.** `UI2_0_DEVELOPMENT_WORKFLOW.md:352` and
   `project/backlog.json`'s entry for this movement both cite "the brief's
   §11.5" for the `C1-1` correction; the brief has no §11 (it ends at §8).
   §1 above cites the correct location (brief §8). This is a documentation
   hygiene item, not a contradiction — the brief is a review record, not a
   FROZEN contract — but both citing documents should be corrected to say
   "§8" when next touched.
2. **`PRIVACY_AND_DATA_HANDLING.md` does not yet name UI 2.0's database.**
   Its "Distributed evidence store (DEV.3.3, opt-in)" section states the
   CLASS 2/dedicated-instance/TLS-DSN/restricted-role rules for Line-1's
   *opt-in* Postgres backend specifically. §7 above applies the same rules
   to UI 2.0's *mandatory* (not opt-in) instance by direct extension of that
   section's stated rationale, but the source document itself should be
   amended to name UI 2.0 explicitly rather than leaving the extension
   implicit. Flagged for the PO as a documentation gap to close in a later
   movement (not blocking `B1-2`, since this contract states the rule that
   applies regardless).
3. **Off-host key custody remains open.** `PCP_STORAGE_ENGINE_DECISION.md`
   gap #9 (whether PostgreSQL's standard backup posture satisfies
   `recovery_offhost_key_custody`'s bar) and the backlog item itself are
   unresolved; §6.3 states this explicitly and shapes the schema so the
   eventual decision needs no migration, but does not resolve it.

---

## 11. Cross-references

- `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — PRODUCT OWNER APPROVED,
  2026-09-09) — binding direction and Phase 0 decisions.
- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` (BASELINE rev 2) — §3.1
  (capability extraction contract, the eventual `capability_version`/
  `capability_id` source), §3.5 (provenance record, §5 above), §5 B0/B1
  tables (dependency gating), §8 Astra's directive (the `C1-1` correction).
- `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` (DRAFT, amended) — §5.2/§5.4
  (the `SR-D8`/`DO-D8` key-custody finding this document distinguishes
  `C1-1` from), §6.2/§6.4 (backup objects, `discard_raw`), §8.3 (Flyway/jOOQ
  stack choice), §9 (storage decision, Oracle portability rules this
  document's §8 extends), §10 (invariants preserved).
- `docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md` §4.1 (`K-7`
  `SR-D8`/`DO-D8`), §7 (reconciliation table), §8 (the `C1-1` correction
  text, cited in full in §1 above).
- `docs/design/PCP_STORAGE_ENGINE_DECISION.md` — §4 gap #9 (off-host key
  custody vs. database backup posture, §6.3/§10 above).
- `PRIVACY_AND_DATA_HANDLING.md` — "Distributed evidence store (DEV.3.3,
  opt-in)" (§7 above); CLASS 0–3 vocabulary.
- `utils/recovery_key_custody.py`; `docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md`
  §5 (`DEV.2.2` secret-material pattern, §6 above).
- `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md` — fail-closed-on-
  unreadable precedent informing this document's own outage-behaviour
  rules (§6.2).
- `utils/runtime_config_source.py` (`DEV.2.1`); `utils/persistent_secret_material.py`
  (`DEV.2.2`); `deploy/secrets/README.md` — the secret-resolution mechanism
  §6.2 carries into Java.
- `utils/db_migrations.py`, `migrations/postgres/*.sql`,
  `utils/evidence_backend.py` — Line-1 precedent, provenance only (§2.1).
- `AGENTS.md` — identity law, raw-evidence law, sensitive identity
  reporting law, privacy/DLP, mandatory build lifecycle (`DEV.4.6`-adjacent
  rules).
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — approval boundaries (schema/storage
  migration requires explicit human approval, unchanged and binding on
  `B1-2`).
