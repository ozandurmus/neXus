# UI 2.0 — B0/C7 backup, artefact and restore engine contract

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-09** (backup/artefact/restore engine freeze (C7), per `UI2_0_BASELINE_CONTRACT.md` §2 `FREEZE-SLICING`). Open items listed in this document's own open-items section are deferred to the movements they name; they do not reopen this freeze. Restore execution remains structurally disabled until `ui2_taxonomy_device_write_class_and_step_kind` resolves the §9.1–9.3 gaps (`DIRECTORY-POSTURE`-style: specified, not enabled). Previous status: DRAFT — FOR PRODUCT OWNER FREEZE. Written under
`docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — PRODUCT OWNER APPROVED,
2026-09-09), decisions `RESTORE-IN-RELEASE-1` (D-7), `APPROVAL-MODEL` (D-2c)
and `RAW-RETENTION` (D-2d), and `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md`
§3.5 (BASELINE, revision 2). Direct inputs: `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md`,
`docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md`,
`docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md`,
`docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` — all
four MERGED as **DRAFT — FOR PO FREEZE**, read as authority for their own
scope, not reopened. This is the second and final platform-adjacent freeze
candidate (`FREEZE-SLICING`, D-3): the first is `C1`–`C5`. Nothing in this
document is implementation authority until its own status line reads
`FROZEN`.

This document is a **contract, not code**: no `ui2/` source, no Line-1 code
change, no Flyway migration file, no device contact. It defines the backup
profile model, the artefact store/manifest/validation/retention model, key
custody, restore execution, the approval model for backup vs. restore, and
the device/version support matrix that acceptance sentence A-3 depends on.
`REL-BACKUP`'s own Extract/Implement/Validate/Integrate movements implement
this contract; they do not re-derive it.

---

## 1. Scope, authority chain, and the RAW-RETENTION distinction

### 1.1 What this contract owns

The backup profile object model (against `C1`/`C2`/`C4`'s actual schema);
the artefact store/manifest/validation/retention model, carrying forward
`docs/design/BACKUP_RECOVERY_CONTRACTS.md` (BRC) as UI 2.0-native table
designs; key custody for backup artefacts; restore execution — plan
compilation, preconditions, execution steps (via `C4`), result verification,
and a restore-specific `OUTCOME_UNKNOWN` behaviour; the approval model
distinguishing routine backup from restore; and the device/version support
matrix template.

### 1.2 What this contract explicitly does not own

| Not owned here | Owner | This document's relationship to it |
|---|---|---|
| Table identity, audit-table schema, secrets storage mechanism, the generic key-custody boundary | `C1` | This document's tables follow `C1`'s ownership-matrix/DDL-sketch discipline exactly (opaque `TEXT` identifiers, `provenance_id` linkage, `C1-1`'s audit trigger); it does not redefine `C1`'s tables, only adds its own following the same rules |
| The job state machine, leasing, `OUTCOME_UNKNOWN` semantics in general, retry rules, pre-execution check mechanics | `C2` | A `BackupRun` and a `RestoreRun` are each literally one `C2` `jobs` row (§2.5, §5.6). This document supplies the restore-*specific* meaning of `OUTCOME_UNKNOWN` (§5.7) on top of `C2`'s generic mechanism; it does not redefine the state machine, the fencing token, or the crash matrix |
| Session authenticity, RBAC evaluation, role tokens, the `E1`–`E7` gate chain | `C3` | This document names `role:backup_admin`/`role:security_admin` by `C3`'s closed vocabulary (`C3` §4.1) and reuses `C3`'s owner/approver/execution-identity resolution (`C3` §7) unchanged; it adds no new role token |
| The capability registry schema, closed step-kind set, gate-registry resolution algorithm, VSX/ClusterXL target model, transport decision | `C4` | Every backup and restore profile step is a `C4` §2.2 step row using `C4` §2.3's closed set and `C4` §3's resolution algorithm verbatim; `C4` §2.4's `cp_gaia_backup_local` worked example is reused, not re-derived (§2.4 below) |
| `utils/action_taxonomy.py` itself; any new taxonomy member | Out of scope, per the dispatch's own scope exclusion | This document identifies exactly where restore's action class does not yet fit the existing five-class taxonomy (§9) and names it as an open item for a taxonomy-successor movement; it adds no class, edits no taxonomy file, and does not treat the gap as license to invent a class here |
| Device contact of any kind | Out of scope for every `B0` contract | Nothing here authorizes execution of any capability on a device (`UI2_0_BASELINE_CONTRACT.md` acceptance sentence A-1) |

### 1.3 Authority chain (highest first, per `AGENTS.md` "Authority hierarchy")

1. `AGENTS.md` — durable constitution: identity law, raw-evidence law,
   UNKNOWN/fail-closed law, the network-device command gate, evidence laws.
2. `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN) — `RESTORE-IN-RELEASE-1`
   (D-7), `APPROVAL-MODEL` (D-2c), `RAW-RETENTION` (D-2d), `FREEZE-SLICING`
   (D-3), acceptance sentences A-1…A-3, Release 1 definition (§4 of that
   document).
3. This document, once its own status line reads `FROZEN`.
4. `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §3.2 ("backup profiles are
   capabilities with the operational-write class... the profile engine is
   therefore not a second engine; it is the same executor with stricter
   admission"), §3.5 (provenance/data-class distinction), §4 (extraction
   inventory row for the recovery-plane modules).
5. `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §3–§8 (concurrent/
   MERGED) — table sketch discipline, data classes, key-custody boundary
   (§6.3), Oracle portability rules.
6. `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` §2–§9 (concurrent/
   MERGED) — job record, state machine, pre-execution checks, retry rules,
   crash matrix, schedule optimistic concurrency, owner/approver/execution
   identity.
7. `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §4, §7
   (MERGED) — role tokens, owner/approver resolution.
8. `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
   §2–§6 (MERGED) — capability registry schema, closed step-kind set, gate
   resolution, target model, transport decision.
9. `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` §6 (DRAFT, amended by
   `APPROVAL-MODEL` and by `C4`'s `K-3`/`CP-D7` corrections) — the profile
   model, object diagram, and lifecycle this document turns into a schema.
10. `docs/design/BACKUP_RECOVERY_CONTRACTS.md` (BRC, CONTRACT, frozen for
    Line-1's `RB.x`) — §2–§10, carried forward as the specification this
    document's own tables realize (§3, §4).
11. `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` (BAA) — the reasoning
    behind BRC; §6 (verified-backup levels), §8 (restore's original hard
    gate, superseded for UI 2.0 by D-7, §9.4 below), §13 (`D2`/`D4` resolved
    decisions).
12. `docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md`,
    `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`,
    `utils/recovery_key_custody.py` — Line-1 reference precedent, carried
    forward as rules (`RUNTIME-DIRECTION` P-4), never as code.
13. `utils/action_taxonomy.py` — the five action classes; referenced, not
    edited (§9).

### 1.4 The `RAW-RETENTION`-vs-backup-artefact distinction, stated explicitly (`AC-1`)

`RAW-RETENTION` (D-2d) reads: *"Retaining raw device output beyond the
sanitized evidence fragment... DEFERRED — default NO until a named need
appears; metadata logs, audit and encrypted backup artefacts are distinct
data classes and ship regardless."* This sentence, on its own, is exactly the
distinction this contract must state so the two are never conflated by a
later reader:

**`RAW-RETENTION`'s default-NO governs one thing only: the raw device
*transcript* a job step's expect engine reads into memory while executing a
capability** — the text a Clish command or a PAN API call returns, captured
by `C2` §5.1's `job_step_attempt` mechanism and discarded per
`output_policy: discard_raw` (design §6.4; `C1` §3.3's "never a device
transcript byte", `C1` §4's job-log data class). This is the *collection
output* rule, and it is unaffected by anything in this document: no step this
contract's profiles run retains a transcript beyond what `C2`/`C4`'s
provenance mechanism already allows.

**A backup artefact is not that.** It is not a captured transcript of a
command's *response* — it is the *purpose-built payload* a class-1 step
fetches (`scp_get`/`sftp_get`, `C4` §2.3), namely the vendor-native backup
blob itself (`cp_gaia_backup`, `pan_device_state`, etc.). Workflow §3.5's own
text is unambiguous: *"job logs, audit entries and complete encrypted backup
artefacts are **distinct data classes**"* — three different things, not one
rule applied three times. `C1` §4's five-class data-class table already
enumerates "Encrypted artefact" as its own row, separate from "Job log",
with its own retention owner (`C7`, i.e. this document, §3.4) and its own
storage rule ("never a database row"). **`RAW-RETENTION`'s default NO does
not apply to it, has never applied to it, and this contract does not need a
waiver, an exception, or a named-need justification to define an artefact
store** — workflow §3.5 states the artefacts "ship regardless" in the same
sentence that defers raw retention. This document proceeds on that basis
throughout §3–§4.

---

## 2. The backup profile model

### 2.1 Objects, against `C1`/`C2`/`C4`'s actual schema (`AC-2`)

Design §6.2's illustrative object diagram (`BackupProfile ─ ProfileVersion ─
Step[]`, `BackupAssignment`, `BackupSchedule`, `BackupRun`, `StepResult`) is
restated here field-for-field against the three sibling contracts' real
tables and columns — no field is re-invented independent of them:

```
backup_profile                         (C7-owned; C1 §3's sketch discipline)
├─ profile_id            TEXT PK, opaque, application-generated
├─ vendor_hint            TEXT NOT NULL
├─ platform_role_scope    TEXT NOT NULL   -- C4 §2.2's exact field name/shape
├─ title                  TEXT NOT NULL
├─ created_by_actor_fingerprint TEXT NOT NULL   -- C3 §3.2 shape
└─ created_at             TIMESTAMPTZ NOT NULL DEFAULT now()

backup_profile_version                 (C7-owned)
├─ profile_id             TEXT NOT NULL REFERENCES backup_profile(profile_id)
├─ version                INTEGER NOT NULL         -- immutable once APPROVED (design §6.5)
├─ state                  TEXT NOT NULL CHECK (state IN ('DRAFT','VALIDATED','APPROVED','RETIRED'))
├─ capability_id           TEXT   -- FK into C4's capability_registry (§2.3 below); NULL until VALIDATED
├─ steps_json              JSONB NOT NULL   -- authored content BEFORE compilation into C4's registry (§2.3)
├─ created_by_actor_fingerprint  TEXT NOT NULL
├─ approved_by_actor_fingerprint TEXT       -- NULL until APPROVED; created_by <> approved_by enforced (design §6.5, mirrors C3 §4.3's self-grant refusal)
├─ approved_at             TIMESTAMPTZ
├─ retired_at              TIMESTAMPTZ
└─ last_connect_check_job_id TEXT REFERENCES jobs(job_id)   -- §2.6

backup_assignment                      (C7-owned; "which profile backs up this device")
├─ assignment_id           TEXT PK, opaque
├─ device_id               TEXT NOT NULL REFERENCES devices(device_id)   -- C1 §3.2
├─ profile_id, profile_version   -- pins the APPROVED version this assignment uses
├─ credential_profile_ref  TEXT NOT NULL
├─ enabled                 BOOLEAN NOT NULL DEFAULT false
├─ created_by_actor_fingerprint  TEXT NOT NULL
└─ approved_by_actor_fingerprint TEXT     -- NULL until an APPROVED assignment; created_by <> approved_by

backup_schedule                        (C7-owned; C2 §7.1's optimistic-concurrency shape verbatim)
├─ schedule_id             TEXT PK, opaque
├─ assignment_id           TEXT NOT NULL REFERENCES backup_assignment(assignment_id)
├─ cadence, window          -- design §6.7's typed schedule-edit-intent shape
├─ retention_policy_ref     TEXT NOT NULL
├─ enabled                  BOOLEAN NOT NULL
├─ version                  INTEGER NOT NULL DEFAULT 1     -- C2 §7.1
├─ created_by                TEXT NOT NULL   -- C2 §7.2 "owner" source
└─ authorized_by             TEXT            -- C2 §7.2 "approver" source; NULL until D7-authorized

BackupRun == one C1 `jobs` row (no new run table; §2.5)
StepResult == C2 `job_step_attempt` rows, populated per C4 §6 (no new table)
Artefact == `backup_artefact` (§3.2 below; C7-owned, replaces BRC §3's manifest.json)
```

No object above duplicates a column `C1`/`C2`/`C3` already own: `device_id`
is `C1`'s, `actor_fingerprint` shape is `C3`'s, `job`/`job_step` lifecycle is
`C2`'s. This document adds exactly the columns design §6.2 names that none of
the three sibling contracts already carry: the profile/version/assignment/
schedule policy objects themselves.

### 2.2 `BackupRun` is a `C2` job, not a fifth object (`AC-2`)

Design §6.2 lists `BackupRun` as its own object. This contract does **not**
add a `backup_run` table: a backup run **is** `C1`'s `jobs` row, exactly as
`C2` §1.2 states ("this contract treats a profile version as an opaque,
immutable reference (`profile_id`, `version`) resolved once at claim time").
Concretely, using `C2` §2.1's field list:

| `C2` `jobs` field | Value for a backup run |
|---|---|
| `job_type` / `capability_id` | the `capability_id` `backup_profile_version.capability_id` resolves to (§2.3) |
| `action_class` | read from that capability row — `recovery-write` for every profile whose steps include a class-1 step (every shipped profile today; a profile whose steps are all class-0 is a plain read capability, not a backup profile, and does not belong in `backup_profile` at all) |
| `capability_ref` / `profile_ref` | `{profile_id, version}` — `C2` §2.1's own worked distinction between a plain capability job and a profile job |
| `target_refs` | the assignment's `device_id` (plus `C4` §4.2's `virtual_system_ref`/`cluster_member_ref` where the target is VSX/ClusterXL-scoped) |
| `reason` | the D7 mandatory-reason field for a Run Now of an approved, enabled assignment (§6.1) |
| `owner` | `C3` §7's resolution: the submitting session's actor for a Run Now, the schedule's `created_by` for a scheduled run |
| `approvers` | the profile version's `approved_by_actor_fingerprint` and, for a scheduled run, the schedule's `authorized_by` |
| `execution_credential_ref` | resolved at claim time from `credential_profile_ref` (`C2` §6 check 6), following the `D4` pattern (distinct backup credential per vendor, no fallback to the collection identity, §4.1 below) |
| `origin` | `{kind:"run_now", session_id}` or `{kind:"schedule", schedule_id, schedule_version}` |

`StepResult` is likewise not a new table: it is `C2` §5.1's `job_step_attempt`
row set, with `step_kind`/`action_class`/`timeout_s`/expectation-match fields
populated exactly as `C4` §6 works through for `cp_gaia_backup_local` step 3.
This is the concrete meaning of workflow §3.2's "the profile engine is
therefore not a second engine; it is the same executor with stricter
admission" — restated here as a schema fact, not only a sentence.

### 2.3 Compiling a profile version into `C4`'s capability registry (`AC-2`, `AC-3`)

`backup_profile_version.steps_json` is the **authored** content — the closed
step-kind vocabulary and expectation semantics of `C4` §2.3/design §6.3,
written by the profile's author (§2.6's Phase-1 CLI/file path). Moving a
version from `VALIDATED` to `APPROVED` (design §6.5) is exactly the point at
which this document requires the version's `steps_json` to be **compiled**
into one `C4` `capability_registry` row:

- `capability_id` — deterministically derived from `{profile_id, version}`
  (e.g. `backup_profile:<profile_id>:v<version>`), never author-chosen and
  never reused across versions (`C4` §2.2's identity is opaque but stable
  per row; a retired-then-superseding version gets its own `capability_id`,
  matching `C2` §2.2's "a later profile edit produces a new version and
  never mutates a job already created against the old one").
- `action_class` — **not** author-declared on the profile (mirroring `C4`
  §3.3 step 5's rule that a step's class is never author-declared): it is
  the maximum class over every resolved step, computed at compile time from
  each step's own `C4` §3.3 gate resolution — matching design §6.9's
  amendment text verbatim ("its class is the maximum class of its steps").
- `steps[]` / `finally_steps[]` — copied into `C4`'s exact shape (§2.3's
  eight-member closed set, §3's gate-resolution algorithm applied per step);
  `validation` (§9's `VALIDATED` state below) fails closed on any step
  outside the closed set, any step without an expectation, or any step whose
  `gate_review_ref` does not resolve per `C4` §3.3 — the profile-authoring
  validation battery design §6.5 already names, now expressed as "run `C4`
  §3.3's `resolve_gate` over every step and require the closed-set/expectation
  invariants `C4` §2.3 lists," not a separately re-derived rule set.
- `evidence_shape_ref` — points at `backup_artefact` (§3.2), never a
  `*_projection` table: a backup profile's "evidence" is the artefact itself
  plus its manifest row, not a derived fact table.
- `parser_ref` — `NOT_APPLICABLE` for a pure backup profile (no derived fact
  is parsed out of device output; the artefact is opaque per BAA §3.3
  invariant 1, carried forward at §3.1 below).

A `DRAFT`/`VALIDATED` version has no `capability_id` (column is `NULL`) —
static validation and the connectivity check (§2.6) can run against the
authored `steps_json` directly without a registry row existing yet; only
`APPROVED` requires the compiled row, because only an `APPROVED` version may
ever be referenced by a `BackupAssignment`/`BackupRun`/`C2` job (`C2` §2.1's
"the immutable version is copied by value from the currently `APPROVED`
row").

### 2.4 Worked example — `cp_gaia_backup_local`, reused not re-derived (`AC-3`)

`C4` §2.4 already works the real, gate-signed-off `RB.3b` sequence
(BRC §7.3/§7.4/§7.7/§7.8) against the closed step-kind set. This document
adopts it **verbatim** as the compiled form of the `cp_gaia_r81_20_backup`
profile's `APPROVED` version: the five ordered `steps[]` (`connect`,
`show diskspace`/`df -P /var/log` precondition read, `add backup local`,
`scp_get` of the produced path, `verify`) and the two `finally[]` steps
(`delete backup <name>`, `disconnect`), each with the gate references `C4`
§2.4 names (`rb3b_freespace_read`, `rb3b_add_backup_local`,
`rb3b_delete_backup_local`). Design §6.3's own illustrative sample — which
invents a `show backup status` poll, discovers the artefact by listing the
directory, and deletes it by a discovered name — is **not** the shipped
profile, exactly as `C4` §1.4/§2.4 already state (`K-3`): this document does
not re-invent what `C4` already corrected.

### 2.5 Data class and provenance for a backup run

A `BackupRun`'s own step log (`job_step_attempt` rows) carries `C1` §4's job-
log data class — identity, timing, outcome, never a device transcript,
exactly as any other capability's steps (§1.4's `RAW-RETENTION` distinction).
The **artefact** the run produces is a separate row (§3.2), linked by
`backup_artefact.job_id → jobs.job_id`, not folded into the job's own step
log — this is what keeps "job ran successfully" and "artefact exists,
validated, and is held" two independently queryable facts, matching BRC §5
rule 1's "held vs. attested-not-held are never merged" principle applied one
layer up (a completed job is not itself proof an artefact was produced and
stored; the `backup_artefact` row is).

### 2.6 Lifecycle, connectivity check, and Phase-1 authoring (design §6.5, restated)

| State | Meaning | Who (`C3` role token) |
|---|---|---|
| `DRAFT` | authored, `steps_json` editable | Phase 1 (this contract, unchanged from design §6.10): CLI/file import only — no browser step-content path exists or is authorized here |
| `VALIDATED` | passed the §2.3 static battery | any `role:backup_admin` may trigger validation; it is not itself a `D7`-gated mutation of device-facing state |
| `APPROVED` | compiled into `C4`'s registry (§2.3), immutable, assignable, schedulable | `role:backup_admin` **other than the version's own author** — `created_by_actor_fingerprint ≠ approved_by_actor_fingerprint`, enforced server-side exactly as `C3` §4.3 enforces `security_admin` self-grant refusal |
| `RETIRED` | not assignable; a `C2` claim against its `capability_id` fails `C2` §6 check 1 ("the profile version is still `APPROVED`") | `role:backup_admin` |

The **connectivity check** (design §6.5) is a class-0 typed job
(`backup_profile_connect_check`) compiled the same way (§2.3) but scoped to
only the profile's `connect` step and its first class-0 `exec`/precondition
step — never a class-1 step. It is a `C2` job like any other class-0 job
(bounded transient retry per `C2` §5.3), and its outcome is written to
`backup_profile_version.last_connect_check_job_id`; `C2` §6 check 2 ("a prior
successful `backup_profile_connect_check` exists for this
`{profile_id, version, device_id}`") reads exactly this column, and design
§6.7's `SCHEDULE_ENABLE_REQUIRES_CONNECT_CHECK` refuses `backup_schedule`
enable when it is absent — carried forward unchanged.

---

## 3. Artefact store, manifest, validation, retention

### 3.1 What carries forward unchanged from BRC (`AC-3`)

Every cross-vendor invariant BAA §3.3 states stands unmodified: an artefact
is opaque (never parsed for evidence — the evidence plane is a different
subsystem), version-bound and identity-bound, never claimed "verified"
without the level qualifying it (§3.3 below, BRC §6), and retrieval must not
degrade the device (`C4`'s gate-resolution/precondition mechanism, §5.3
below). BRC §9's security invariants (9.1–9.13) are re-adopted as this
contract's own test obligations at UI 2.0 scope (§8), not re-derived.

### 3.2 `backup_artefact` — the manifest, as a Postgres table (`AC-3`)

BRC §3's `manifest.json` is "the only recovery-plane object any other
subsystem may read." On the UI 2.0 line, that object becomes a Flyway-owned
table, field-for-field, so it can be queried by the readiness/overview
screens (workflow B1 step 9 pattern) the way `cp_inventory_projection` is —
while the payload bytes stay off the database exactly as `C1` §4 already
requires ("Encrypted artefact... never a database row"):

```sql
CREATE TABLE backup_artefact (
    artefact_id           TEXT        PRIMARY KEY,   -- opaque; BRC §3's sha256-of-ciphertext identity, kept as the value, not the column's identity mechanism (C1 identity law: application-generated, never DB-derived)
    device_id             TEXT        NOT NULL REFERENCES devices(device_id),
    endpoint_id           TEXT        NOT NULL REFERENCES endpoints(endpoint_id),
    virtual_system_ref    TEXT,                        -- C4 §4.2 composite target model
    job_id                 TEXT        NOT NULL REFERENCES jobs(job_id),        -- the BackupRun that produced it
    provenance_id           TEXT        NOT NULL REFERENCES provenance_records(provenance_id),  -- C1 §5, mandatory per capability projection pattern

    vendor                 TEXT        NOT NULL,       -- 'check_point' | 'panorama'
    platform                TEXT        NOT NULL,       -- 'gaia' | 'pan-os'
    software_version        TEXT,                        -- NULL only ever legal for a PAN class (BRC §3 rule 5, carried forward verbatim, §3.3 below)
    ha_role                 TEXT,                        -- 'active'|'standby'|'standalone'|'unknown'
    hostname_fingerprint     TEXT        NOT NULL,       -- HMAC, never the raw hostname (BRC §3 rule 2, carried forward)

    artefact_class           TEXT        NOT NULL,       -- 'pan_device_state'|'pan_running_config'|'cp_gaia_backup'|'cp_mgmt_export'|'cp_mds_backup'
    is_rma_grade             BOOLEAN     NOT NULL,       -- derived, never author-asserted (BRC §3 rule 3)
    vendor_native_filename    TEXT        NOT NULL,
    plaintext_sha256          TEXT        NOT NULL,
    plaintext_bytes           BIGINT      NOT NULL,
    ciphertext_sha256          TEXT        NOT NULL,
    ciphertext_bytes           BIGINT      NOT NULL,
    compression               TEXT        NOT NULL,
    collected_via              TEXT        NOT NULL,
    collection_duration_ms      INTEGER     NOT NULL,

    key_id                     TEXT        NOT NULL,     -- C1 §6.3 envelope pattern
    wrapped_data_key            TEXT        NOT NULL,     -- opaque; useless without the vault key (§4 below)

    validation                  JSONB       NOT NULL,     -- BRC §4 shape, opaque payload column (C1 §8's JSONB rule)
    restore_constraints           JSONB       NOT NULL,     -- BRC §3's restore_constraints object, carried forward verbatim

    consistency_group_id          TEXT        REFERENCES backup_consistency_group(group_id),
    retention_policy_ref           TEXT        NOT NULL,
    retention_tier                  TEXT        NOT NULL,   -- 'daily'|'weekly'|'monthly'
    expires_at                       TIMESTAMPTZ NOT NULL,

    recovery_volume_path              TEXT        NOT NULL, -- server-side only; never returned in an API response (§3.6)
    created_at                        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_audit_backup_artefact
    AFTER INSERT OR UPDATE OR DELETE ON backup_artefact
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('artefact_id');   -- C1-1, extended here exactly as C3 extended it to sessions/role_bindings
```

**What is deliberately not carried forward as a literal field, and why:**
BRC §3's `manifest.json` embeds a `restore: null` field reserved so a future
`RB.6` restore record is "additive, never a rework" — the check-engine
remediation-block pattern. This document keeps that *intent* but expresses
it **relationally** instead of via a nullable JSON field: `restore_run`
(§5.6) is its own table with `restore_run.source_artefact_id → 
backup_artefact.artefact_id` as a foreign key **into** the artefact, never a
column **on** it. This is a stronger form of the same guarantee BRC §3 rule
1 states ("the `RB.1` validator rejects a manifest with a non-null `restore`
block") — a restore record cannot even be *attempted* as a mutation of
`backup_artefact`, because no such column exists for it to mutate; `C1-1`'s
audit trigger on `backup_artefact` would in any case never see a restore
write, since restore writes a different table entirely.

**Payload bytes stay off the database, on the same physically-separated
recovery volume BRC §2 already specifies** (`SECURITYEXPERT_RECOVERY_ROOT`-
equivalent, its own mount, not inside the UI 2.0 database's own storage,
mirroring `C1` §2.1's "different database... no table here is ever a CLASS
0/1/shareable artefact" extended to a second, non-database volume). The path
layout carries forward from BRC §2 with one deliberate adaptation: BRC §2
keys the path by `entity_id` (a Line-1 `safe_component`-derived string);
this document keys it by `device_id`/`endpoint_id` — UI 2.0's own opaque,
application-generated identifiers (`C1` identity law) — because BRC's
`safe_component` convention is a Line-1 hostname-sanitization mechanism this
document does not import (`RUNTIME-DIRECTION`, P-2). The layout shape is
otherwise unchanged:

```
<recovery_root>/vault/<vendor>/<device_id>[/vs_<vs_id>]/<artefact_id>/artefact.enc
```

`recovery_volume_path` is the pointer stored in the row; per BRC §6 rule 1
("no payload bytes, no download URL... ever") and `C1` §7's CLASS 2 posture,
it is read only by the worker process resolving the physical file for a
restore fetch (§5.4) or a retention deletion (§3.5) — never serialized into
any HTTP response, never rendered, matching BRC §6's frozen `recovery_ui`
payload rule exactly.

### 3.3 Version-locking rule, carried forward verbatim (`AC-3`)

BRC §3 rule 5 (amendment C4, RB.3b prep) is adopted unchanged: for
version-locked CP classes (`cp_gaia_backup`, `cp_mgmt_export`,
`cp_mds_backup`), if the exact `software_version` cannot be resolved from
existing evidence (the equivalent UI 2.0 projection, once a CP config/
inventory capability ships), the collecting job **refuses to store the
artefact** — no `backup_artefact` row is written, no new device command is
issued to obtain a version. For PAN classes, `unified.json`'s Line-1
successor still carries no PAN version field; the `"unknown"` sentinel is
retained (`software_version = NULL`, never a placeholder string, per `C1`
§3.4's nullable-not-magic-string rule), the artefact **is** stored, and
readiness (a later, `C7`-consuming screen, not sketched here) is `UNKNOWN`
until a version is available. Consequence unchanged: a stored artefact whose
`software_version` is `NULL` is therefore only ever a PAN artefact.

### 3.4 Validation levels, carried forward verbatim (`AC-3`)

BRC §4's four ascending levels (`V1 Transport/INTACT`, `V2 Structural/
WELL_FORMED`, `V3 Semantic/CONSISTENT`, `V4 Restore-proven/RESTORE_PROVEN`)
and its frozen rules are adopted unchanged into `backup_artefact.validation`
(JSONB, same shape BRC §4 documents): `level` is the highest fully-passed
level, a `FAIL` caps it without invalidating a lower passed level;
`restore_proven` is `false` unless a `restore_proof` record exists (§5.5
below is what can ever populate one); V3 checks compare against the
equivalent UI 2.0 inventory projection and are `NOT_APPLICABLE`, never
`PASS`, when that projection is absent or stale for the device. `C1` §8's
JSONB-as-opaque-payload rule applies: no query in this contract's own scope
inspects `validation` with a `jsonb` operator; every read is whole-value,
through the evidence-read path.

### 3.5 Retention, carried forward verbatim with a UI 2.0-native approval path (`AC-3`)

BRC §8's GFS policy shape (`daily`/`weekly`/`monthly` counts), its absolute
floor ("retention may never drive a device to zero held artefacts, and may
never delete the last `is_rma_grade: true` artefact"), and its append-only
tombstone ledger are adopted unchanged:

```sql
CREATE TABLE backup_retention_ledger (
    ledger_id       TEXT        PRIMARY KEY,   -- opaque
    artefact_id      TEXT        NOT NULL,       -- the deleted artefact's own id, kept even after backup_artefact's row is gone
    device_id         TEXT        NOT NULL,
    deleted_at         TIMESTAMPTZ NOT NULL,
    policy             TEXT        NOT NULL,
    deleted_by_actor_fingerprint TEXT NOT NULL,
    reason              TEXT        NOT NULL
);
-- append-only by grant, mirroring C1 §3.5's audit_log / RECOVERY_OPERATIONAL_WRITE_LEDGER.md §3.2's insert-only pattern:
REVOKE UPDATE, DELETE ON backup_retention_ledger FROM ui2_app;
GRANT SELECT, INSERT ON backup_retention_ledger TO ui2_app;
```

BRC §8 rule 2 ("deletion is dry-run by default and requires `--apply`") is
UI 2.0-native here rather than a CLI flag: retention deletion is a **typed
intent** (`backup_retention_apply`) requiring the same `D7`
`role:backup_admin` gate and mandatory reason as a schedule-edit intent
(`C2` §6/`C4` §3.5-style admission — the intent names the candidate
artefact set computed by the policy, and the operator explicitly confirms
before any row is deleted or any file removed from the recovery volume). The
floor invariant is enforced **before** the intent is even offered as
executable — an intent whose candidate set would violate the floor is
refused at compile time, never silently narrowed.

### 3.6 Security invariants adopted (`AC-3`)

BRC §9's invariants (9.1–9.12; 9.13 is Line-1's operational-write-ledger
test and does not apply to UI 2.0's own admission model, which is `C2` §6's
battery) are adopted as this contract's own test obligations, restated for
UI 2.0's stack in §8. In particular: no plaintext artefact is ever written
under any root, including transiently (decrypt-to-memory or a bounded
tmpfs-equivalent only); the wrapping key is never resolvable from
`backup_artefact` alone (§4); no artefact byte reaches an HTTP response,
matching `C1` §7's CLASS 2 posture and BRC §6 rule 1's "never, in any form."

---

## 4. Key custody (`AC-4`)

### 4.1 Envelope model, inherited unchanged (`utils/recovery_key_custody.py`, `C1` §6.3)

The key hierarchy is exactly `recovery_key_custody.py`'s `KeyCustodyBackend`
abstraction, carried forward as a rule (not code, `RUNTIME-DIRECTION` P-4)
and already fixed by `C1` §6.3 as the boundary this document's own table
must respect:

```
vault master key  ──wraps──►  per-artefact DEK  ──encrypts──►  artefact.enc bytes
(never persisted            (persisted only as        (on the recovery volume,
 anywhere but the             `wrapped_data_key`,        never as a DB row,
 custody backend)              opaque; useless           §3.2)
                                without the master key)
```

`backup_artefact.key_id` and `.wrapped_data_key` are the **only** custody-
adjacent columns this table carries — raw master-key bytes and raw DEK bytes
never appear in Postgres, never in a log line, never in an HTTP response,
exactly as `LocalFileKeyCustodyBackend`'s own docstring states the Python
implementation's contract today. A fresh DEK is generated per artefact at
write time (BRC §9.1's "no plaintext... incl. temp paths" invariant governs
the encryption step itself, not this document's concern beyond naming it).

### 4.2 Custody today — `env_file`, per `C1` §6.1/§6.2/§6.3 (`AC-4`)

The vault master key is a **component secret** exactly like every other one
`C1` §6.1 tables: `secrets_metadata` row with `component = 'worker'`,
`purpose = 'recovery_vault_master_key'`, `backend_kind = 'env_file'`,
resolved via `SECURITYEXPERT_UI2_WORKER_RECOVERY_VAULT_MASTER_KEY_FILE`
(naming per `C1` §6.2's convention). This is the direct Java-side successor
of `LocalFileKeyCustodyBackend` — "resolves the master key via an env
override, else a `0600` file on `data_root`", held in-process for the
worker's lifetime — with the same explicit non-guarantee
`recovery_key_custody.py`'s docstring states: **this is not an off-host
KMS/secret-manager.** `C1` §6.3's fail-closed outage behaviour applies
unchanged: an unresolvable master key at worker boot is a hard failure
(refuses to start), never a degraded mode that stores an artefact
unencrypted or defers wrapping.

### 4.3 Rotation rule (`AC-4`)

Because the model is envelope encryption, **rotating the master key never
requires re-encrypting a single artefact byte** — this is the concrete,
load-bearing consequence `C1` §6.3 already names ("a future off-host custody
backend can be swapped in by re-wrapping existing DEKs under a new `key_id`
— old wrapped blobs stay valid until re-wrapped, no artefact re-encryption
required"), stated here as the operational rotation procedure:

1. A new master key is minted, given a new opaque `key_id`.
2. For each `backup_artefact` row still carrying an old `key_id`: the old
   master key unwraps `wrapped_data_key` to recover the DEK (in-process,
   never persisted), the new master key re-wraps that same DEK, and the row
   is updated with the new `key_id` + new `wrapped_data_key` — `artefact.enc`
   on the recovery volume is never touched.
3. Once every row referencing the old `key_id` is re-wrapped, the old master
   key may be retired from the custody backend.

No rotation mechanism ships in `recovery_key_custody.py` today (its own
docstring states plainly there is none); this document specifies the
procedure the eventual Java implementation follows once one is built — it
does not claim rotation is implemented at B1/`REL-BACKUP` scope.

### 4.4 What stays open (`AC-4`, cross-referenced at §9)

Off-host key custody — whether a real KMS/secret-manager backend exists, and
whether PostgreSQL's own backup posture satisfies the same bar — is the
existing `recovery_offhost_key_custody` backlog item and `C1` §6.3's own
open item 3 (`PCP_STORAGE_ENGINE_DECISION.md` gap #9). This document does
not resolve it; the schema (`key_id` + `wrapped_data_key` only) is shaped so
that decision needs no migration when it lands, exactly as `C1` §6.3 already
states.

---

## 5. Restore execution

Everything in §2–§4 has direct Line-1 precedent to carry forward. Restore
does not — BRC's own manifest reserves a `restore: null` field precisely
because Line-1 never built this (`RB.6`, hard-gated, "designed but not
buildable" per BAA §8). This section is the part of the contract with no
prior implementation to restate; it is built directly against `C2`'s job
model and `C4`'s capability/step model, per baseline `RESTORE-IN-RELEASE-1`
(D-7, ACCEPTED).

### 5.1 What D-7 commits to, restated so it is not reopened (`AC-11`)

`RESTORE-IN-RELEASE-1` (D-7): *"Release 1 product acceptance includes a
restore flow on the platforms the product declares supported; the supported
device/version set may be limited... Any release without restore is named
'platform pilot / backup collection release', never Release 1. High risk
strengthens the `C7` contract and acceptance tests; it is not a reason to
drop the promise."* This document treats D-7 as decided: restore execution
is specified in full below, on the same "conditional means the spec is
ready, not skipped" principle `C3` §4.4 already applied to
`DIRECTORY-POSTURE`. What is **not** decided by D-7, and is reported rather
than resolved here, is *how* restore's device-write fits the existing
action-class taxonomy and `C4`'s closed step-kind set — §9.1/§9.2 name this
precisely; §5.2–§5.7 specify the parts of the engine that do not depend on
that gap closing.

### 5.2 Restore plan compilation (`AC-5`)

A `RestorePlan` is compiled, never authored freehand (mirroring §2.3's
"steps are never author-declared class" discipline):

```
restore_plan
├─ plan_id                 TEXT, opaque
├─ source_artefact_id       TEXT NOT NULL REFERENCES backup_artefact(artefact_id)
├─ target_device_id          TEXT NOT NULL REFERENCES devices(device_id)
├─ target_endpoint_id         TEXT NOT NULL REFERENCES endpoints(endpoint_id)
├─ restore_capability_id       TEXT       -- FK into C4's capability_registry, once §9.1/§9.2's gap closes (NULL/UNKNOWN today)
├─ compiled_at                 TIMESTAMPTZ NOT NULL
└─ compiled_by_actor_fingerprint TEXT NOT NULL
```

Compilation selects the source artefact (an operator-chosen
`backup_artefact` row, or the latest artefact meeting §5.3's validity floor
for the target device), resolves the vendor/platform-specific restore
capability for that artefact's `artefact_class` (a `C4` capability row, per
§9.2, once one can exist), and evaluates §5.3's precondition battery. A plan
that fails any precondition is never handed to `C2` as a job request — this
mirrors `C2` §6's own claim-time battery, applied one step earlier, at
compile time, because a restore's consequences are severe enough that a
human should see the refusal reason **before** requesting approval (§6.2),
not discover it only when a worker claims the job.

### 5.3 Preconditions (`AC-5`)

Evaluated in this fixed order, exactly like `C2` §6's own battery shape
(first failure aborts compilation, later checks do not run, every check is
recorded):

| # | Check | What it re-reads |
|---|---|---|
| 1 | **Connectivity** — an early, non-authoritative pass using `RestoreConnectivityFreshnessPolicy` (`RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.3): active probe over the actual restore-write transport, or explicitly configured cached evidence meeting TTL, identity-binding and per-vendor/platform council-reviewed semantic-sufficiency requirements; insufficient cached evidence falls back to the active probe, whose failure/timeout refuses compilation. Numeric timeout/TTL values remain `UNKNOWN`. | connect-check record and freshness policy (§2.6's pattern, reused for restore) |
| 2 | **Backup validity** — `source_artefact_id`'s `validation.level` is at least `V2` (`WELL_FORMED`); a `V1`-only or `FAILED` artefact refuses restore unconditionally, before any approval is even requested — restoring from a blob that has not even been proven structurally sound is refused categorically, never left to an approver's judgment | `backup_artefact.validation` |
| 3 | **Target identity match** — the artefact's `device_id`/`hostname_fingerprint`/`platform` match the target's own registry identity; a mismatch refuses compilation outright (restoring artefact X onto device Y is never offered as a choice, not even behind an override) | `backup_artefact`, `devices`/`endpoints` |
| 4 | **Version match** — for a version-locked artefact class (BRC §3 rule 5, §3.3 above), the artefact's `software_version` must match the target's currently-known running version from the latest evidence projection; a mismatch refuses compilation (whether a future, explicitly-authorized override may ever bypass this is left open, §9.5) | `backup_artefact.software_version`, the target's inventory projection |
| 5 | **No concurrent restore or backup against the same target** — `C2`'s own per-target job admission (a `REQUESTED`/`CLAIMED`/`EXECUTING` job already exists against this `device_id`) refuses a second plan's job request; this is `C2`'s existing mechanism, not a new lock this document invents | `C1` `jobs` rows for the target |
| 6 | **Credential resolution** — the restore capability's declared `credential_profile_ref` resolves; an unresolvable credential refuses compilation before any approval is requested | credential store |
| 7 | **No unreconciled prior restore-write outcome**, `check_id=C7_RESTORE_NO_UNRECONCILED_PRIOR` (`controlled-restore-write` plans only) — an early, non-authoritative, staleness-tolerant pass against `RestoreWriteLedger.has_unreconciled_prior` for the physical target; an unreconciled prior outcome or unreadable ledger refuses compilation; `NOT_APPLICABLE` for every other class's plan | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.1.1/§5/§3.7 |

Checks 1–7 above are the compile-time battery; `C2` §6 retains six checks
(approval, connectivity, coordination, class-scoped ledger, registry/allowlist,
credential resolution) and re-reads current state at **claim** time. A pass
at compilation is never carried forward as claim-time authority. Check 7
is independently recorded under `C7_RESTORE_NO_UNRECONCILED_PRIOR`, never
merged into check 2's or check 5's outcome; checks 2–6 retain their text/order.

**Target eligibility, separately recorded before approval:**
`RESTORE_TARGET_TOPOLOGY_ELIGIBILITY` must positively establish
`STANDALONE_PHYSICAL_DEVICE` from authoritative registry plus current topology
evidence. Known ClusterXL members yield `UNSUPPORTED_CLUSTERXL_MEMBER`;
missing, stale or conflicting membership evidence yields `NOT_EVALUABLE`.
Either refuses compilation before approval. Targets are physical
`device_id`/`endpoint_id` only; VSX-context restore is unsupported. Claim-time
`C2` §6 check 5 re-evaluates eligibility with its distinct claim-time refusal
reasons: this compile-time `UNSUPPORTED_CLUSTERXL_MEMBER` outcome maps to
`TARGET_CLUSTERXL_MEMBER_UNSUPPORTED` at claim (`RESTORE_CONTROLLED_WRITE_LEDGER.md`
§3.7). Per-member identity separation
does not prove cross-member restore safety.

**Note on `C2` §6 check 4, per-class interpretation (D1 Option A):**

- `CLASS_1_RECOVERY_WRITE` retains the `RB.x` cadence ledger and its existing
  minimum re-execution interval, unchanged.
- `CLASS_1B_CONTROLLED_RESTORE_WRITE` consults `RestoreWriteLedger` for an
  unreconciled prior restore-write outcome on the same physical device,
  independently named and recorded; an unreadable ledger blocks fail-closed.
- Every other class is `NOT_APPLICABLE`.

This supersedes the former blanket restore exemption, per the frozen ledger
§3.6 companion amendment. Check 5 above remains the independent live-job
concurrency check; neither it nor the cadence ledger substitutes for the
authoritative claim-time reconciliation check in `C2` §6 check 4. Runtime
restore remains disabled pending the remaining D1 implementation/validation gates.

### 5.4 Execution steps, via `C4` (`AC-5`)

Once `restore_capability_id` resolves (§9.2), the restore job runs on `C2`'s
executor exactly like a backup job: `C4` §2.3's closed step-kind set,
`C4` §3's gate-resolution algorithm, `C2` §5.1's step-attempt-before-contact
discipline. The step *sequence* is necessarily vendor/platform-specific
(carrying the fetched artefact to the device, invoking the vendor's own
restore procedure, confirming completion) and is not authored by this
document — it is exactly the kind of capability a future `C6` extraction
movement specifies once §9.2's step-kind gap closes, the same way `C4` §2.4
specified `cp_gaia_backup_local` from BRC's frozen gate entries. What this
document fixes now, so that movement is not starting from nothing: every
restore capability's steps use `C4`'s closed set and gate-resolution rule
identically to a backup capability's; no restore-specific step kind, no
restore-specific gate-resolution rule, and no restore-specific transport
model exists or is needed beyond what `C4` already defines and §9.2 flags as
currently incomplete for this one purpose (device-directed byte transfer).

### 5.5 Result verification (`AC-5`)

A restore's success is never inferred from "the device did not report an
error." Verification is **re-collection**, applying BRC §6's V3 methodology
after the fact instead of before storage:

```
restore_verification
├─ verification_id     TEXT, opaque
├─ restore_run_id        TEXT NOT NULL REFERENCES restore_run(run_id)   -- §5.6
├─ verified_at            TIMESTAMPTZ NOT NULL
├─ checks[]                -- {id, result: PASS|FAIL|NOT_APPLICABLE, detail}
│    - "post_restore_connectivity_confirmed"  -- a fresh class-0 job reaches the device at all
│    - "post_restore_version_matches_artefact" -- the device's live version now matches the restored artefact's software_version
│    - "post_restore_ha_role_plausible"         -- where applicable, HA role is not left in an unexpected state
└─ verdict                -- RESTORE_APPLIED_VERIFIED | RESTORE_APPLIED_UNVERIFIED | RESTORE_OUTCOME_UNKNOWN (§5.7)
```

`RESTORE_APPLIED_VERIFIED` requires every listed check to `PASS`;
`NOT_APPLICABLE` is legal per check (mirroring BRC §4 rule 3's "never `PASS`
when the comparison basis is absent") but never counts toward `VERIFIED`.
This gives restore exactly the honesty BRC §6 already established for
backup: **"verified" never appears unqualified**, and a restore whose
post-check evidence is thin is `RESTORE_APPLIED_UNVERIFIED`, not silently
promoted.

### 5.6 `RestoreRun` — one `C2` job, mirroring `BackupRun` (`AC-5`)

```sql
CREATE TABLE restore_run (
    run_id              TEXT PRIMARY KEY REFERENCES jobs(job_id),  -- the RestoreRun IS the C2 job row; this table adds restore-specific columns only
    plan_id               TEXT NOT NULL REFERENCES restore_plan(plan_id),
    source_artefact_id      TEXT NOT NULL REFERENCES backup_artefact(artefact_id),
    approval_id              TEXT NOT NULL REFERENCES restore_approval(approval_id)  -- §6.2, mandatory, never nullable
);
```

Exactly as §2.2 states for `BackupRun`, `restore_run` is not a duplicate job
record — it is the restore-specific columns a `C2` `jobs` row does not
otherwise carry (which plan, which artefact, which specific approval record
authorized this exact operation), joined 1:1 to the job by `job_id`.

### 5.7 `OUTCOME_UNKNOWN`, specific to restore (`AC-6`)

`C2` §3.5/§8 already define the generic mechanism: a step-attempt record
proving a command may have reached the device but whose result could not be
confirmed lands the job in `OUTCOME_UNKNOWN`, terminal until reconciled, with
**no automatic second write**. Restore's own consequence is materially
different from a read job's, and this document states it concretely, per
`UI2_0_BASELINE_CONTRACT.md`'s own risk framing ("a restore left half-applied
on a device is a materially different, more consequential state than an
uncertain read outcome"):

**What state the device may be left in.** Unlike a backup's `add backup
local` (a single bounded write whose worst ambiguous outcome is "an archive
may exist on `/var/log` that was never confirmed and never cleaned up" — a
resource question, not a configuration one), a restore's write step
*replaces or merges configuration state*. A restore job that reaches
`OUTCOME_UNKNOWN` may have left the device in any of:

1. **Unaffected** — the restore-apply command never reached the device
   (the crash happened before send; `C2` §4.4's lease-expiry table already
   distinguishes this case by `mutation_boundary_crossed = NO`, and it
   resolves to `REQUESTED`/retryable exactly as any other job, **not**
   `OUTCOME_UNKNOWN` — restated here because for a read job this
   distinction is almost never operator-visible, while for restore it is
   the single most important fact a human needs first).
2. **Fully applied, confirmation lost** — the device applied the restore
   and then became unreachable (a reboot the restore procedure itself
   triggers, a dropped session) before the worker could confirm.
3. **Partially applied** — the restore procedure is itself multi-step on
   the device side (e.g. upload, then a separate apply/commit action); the
   worker's own step sequence crossed the mutation boundary on an
   intermediate step and cannot tell whether a later step in that same
   on-device procedure also ran.

`C2`'s own durable state cannot, by construction, distinguish cases 2 and 3
from each other — both are "a step with `mutation_boundary_crossed = YES`
and no confirmed outcome" — and this document does not claim it can. What it
requires is that the operator-facing surface **never presents case 1's
certainty** ("nothing happened, safe to retry") for a job that reached
`OUTCOME_UNKNOWN`: only a job that never left `REQUESTED`/never crossed the
boundary gets that message: `C2` §8's crash-matrix point 2 language exactly.

**What a human must verify before any retry is even considered.** Per `C2`
§3.5's `job_reconciliation` mechanism, applied here with a restore-specific
`evidence` requirement — reconciliation for a restore `OUTCOME_UNKNOWN` is
never accepted from a subsequent automated read alone (contrast a routine
backup's ledger-style reconciliation, which a later attestation-class read
can often settle): the `job_reconciliation.evidence` field for a restore
must record **direct, out-of-band confirmation** of the device's actual
configuration state — a console/SSH session outside this product, or the
vendor's own management-plane record — establishing which of cases 1–3
above actually happened, before `reconciled_outcome` is set. This is
stricter than `C2` §3.5's generic rule (which accepts "a subsequent
read-class job's observation" as sufficient evidence for a routine backup)
because a device whose configuration is genuinely unknown is not merely a
missed backup (BRC's own framing for ledger ambiguity) — it may be
mid-outage.

**Whether/when a retry is permitted.** Never automatically, in every case,
matching `C2` §5.3's `CLASS_1_RECOVERY_WRITE` rule extended to whatever class
restore's own write step resolves to (§9.1): a restore `OUTCOME_UNKNOWN`
closes only via `RECONCILED` (`C2` §3.2's closed-transitions graph, no
exception for restore). A **new** restore attempt after reconciliation is a
**new** `restore_plan` and a **new** `restore_run`/job — never a retried or
resumed one — and it requires:

1. a fresh `restore_approval` record (§6.2), independently granted — a
   revoked/expired prior approval is never reused, and even a valid one is
   never reused across two distinct plan compilations (§6.2's
   per-operation scope);
2. the `job_reconciliation` record above, establishing what the *prior*
   attempt actually did, referenced by the new plan
   (`restore_plan.superseding_prior_run_id`, mirroring `C2` §3.2's
   `supersedes_job_id` field for the equivalent generic case);
3. re-evaluation of every §5.3 precondition against the device's
   **now-confirmed** state (case 2/3 above may mean the artefact that was
   valid for the first attempt is no longer the right target — e.g. if the
   device's version already changed mid-restore).

No automatic path exists that skips any of the three; the standing
relay-authorized "green tests → self-merge" pattern this repository uses for
routine engineering movements has no analogue here by design — restore
reconciliation is inherently a human judgment call this contract requires,
never something a test suite passing could substitute for.

---

## 6. Approval model — routine backup vs. restore (`AC-7`)

### 6.1 Routine backup — `D7` role + reason, no second approver (`APPROVAL-MODEL`, unchanged)

Exactly `C2` §6 check 1 and `C2` §7's mechanism, unchanged: a Run Now of an
**already-`APPROVED`** profile version against an **already-enabled**
`BackupAssignment` requires `role:backup_admin` and a mandatory reason
(≥ 8 characters, redaction-filtered), re-checked fresh at claim time — no
second human approver per run. Four-eyes applies once, upstream, at
`backup_profile_version` approval (§2.6) and at `BackupAssignment`
enable/`BackupSchedule` authorization (§2.1) — never per execution. This is
`APPROVAL-MODEL`'s own text, restated with no amendment: *"Routine backup
runs inside an approved scope... do **not** need a second human each time;
they need the `D7` role and a mandatory reason."*

### 6.2 Restore — per-operation independent approval (`APPROVAL-MODEL`, `AC-7`)

`APPROVAL-MODEL`'s own text names restore as the one exception: *"Restore
and, later, controlled failover require **per-operation independent
approval**."* This is not the profile-version/schedule four-eyes reused —
it is a **separate approval record scoped to one specific, already-compiled
`RestorePlan`**, never reusable:

```sql
CREATE TABLE restore_approval (
    approval_id           TEXT        PRIMARY KEY,   -- opaque
    plan_id                 TEXT        NOT NULL REFERENCES restore_plan(plan_id),
    requested_by_actor_fingerprint  TEXT NOT NULL,   -- the operator proposing this specific restore
    approved_by_actor_fingerprint    TEXT,              -- NULL until granted; requested_by <> approved_by, enforced identically to C3 §4.3's self-grant refusal
    reason                    TEXT        NOT NULL,     -- >= 8 chars, redaction-filtered (D7 pattern)
    approved_at                TIMESTAMPTZ,
    expires_at                  TIMESTAMPTZ NOT NULL,   -- bounded validity (proposed 30 minutes) -- a stale, unexercised approval cannot be exercised later without a fresh grant
    revoked_at                   TIMESTAMPTZ,
    revoked_by_actor_fingerprint  TEXT
);
```

**Who may hold the approving role.** `role:backup_admin` — `C3`'s closed
six-token vocabulary (§4.1) names no separate "restore approver" token, and
this document does not mint one (it would be a `C3`-successor amendment,
out of this document's scope). `role:security_admin` is deliberately **not**
eligible: `C3` §4.1's own separation-of-duties rule ("the actor who
administers authorization does not also execute") already excludes it from
every device-facing action, and this document does not carve an exception.
`requested_by_actor_fingerprint ≠ approved_by_actor_fingerprint` is
structurally required — the same pattern `C3` §4.3 uses for
`security_admin` self-grant refusal and design §6.5 uses for profile
approval, applied a third time, consistently.

**How this differs from a `BackupAssignment`/`BackupSchedule` approval,
concretely:**

| | Backup profile/schedule approval | Restore approval |
|---|---|---|
| Scope | one profile version, or one schedule — reused across every future run in that scope | one specific `RestorePlan`, never reused |
| Granted once, exercised... | many times (every Run Now / every due schedule fire) | exactly once — a second restore attempt requires a fresh approval (§5.7) |
| Re-checked at claim time | yes (`C2` §6 check 1) — but against the *standing* approval row | yes — against *this exact* `restore_approval` row, additionally checked for `expires_at`/`revoked_at` |
| `C2` pre-execution check | check 1 as written | check 1's restore-specific instance: a valid, unexpired, unrevoked `restore_approval` row exists for `plan_id`, in addition to (not instead of) the standing profile-version-equivalent checks §5.3 already runs at compile time |

**`C2` §6 integration.** Restore's pre-execution battery is `C2` §6's six
checks, with check 1 reading `restore_approval` instead of a profile
version's standing approval row, and check 2 (connectivity precondition)
already satisfied by §5.3 check 1's own compile-time evaluation, re-verified
fresh at claim exactly as `C2` §6 already requires for any check.

---

## 7. Device/version support matrix template (`AC-8`, `AC-10`)

### 7.1 The rule (`AC-10`)

**A platform is never declared `declared_supported: true` in this matrix on
`FIXTURE_ONLY`-only evidence for a class-1 write.** Only `REAL_ENV_VALIDATED`
evidence — a real device run, Product-Owner-executed, per workflow §3.3 step
c — clears the bar for `declared_supported`. `FIXTURE_ONLY` and `UNPROVEN`
are honestly recorded as *what the evidence actually is*, never rounded up.
This is the same "explicit `UNKNOWN` over invented certainty" law BRC §5
rule 3 and `C4` §2.6 already apply; this document applies it to the one
place acceptance sentence A-3 depends on.

### 7.2 Template columns

| Column | Meaning |
|---|---|
| `platform / version` | the declared device class |
| `backup_capability_status` | `NOT_BUILT` \| `CAP-SPEC` \| `CAP-OFFLINE` \| `CAP-VALIDATED` \| `CAP-RELEASED` (baseline §1 maturity ladder) |
| `backup_evidence_class` | `REAL_ENV_VALIDATED(date, fingerprint)` \| `FIXTURE_ONLY` \| `UNPROVEN` — workflow §3.1 field 8's vocabulary |
| `restore_capability_status` | same maturity ladder |
| `restore_evidence_class` | same vocabulary |
| `declared_supported` | boolean; `true` only per §7.1's rule |
| `notes` | inherited gaps, blockers, open items |

### 7.3 Worked row 1 — CP Gaia `add backup local` (`RB.3b`)

| Field | Value |
|---|---|
| platform / version | Check Point Gaia R81.10, R81.20 |
| `backup_capability_status` | `CAP-SPEC` — the command tuple and gate entries (BRC §7.3/§7.4/§7.7/§7.8) are gate-signed-off and `C4` §2.4 already compiles them into the closed step model; the Line-1 collector itself is a **blocked stub** (BRC §10.3: "blocked stub — implementation-cleared") that has never executed, on fixtures or hardware |
| `backup_evidence_class` | **`UNPROVEN`** — not `FIXTURE_ONLY`: BRC §10.3 records no fixture-transport test run for the CP collector (unlike PAN, §7.4 below); the command tuple's semantics are gate-signed-off documentation, not executed evidence of any kind. Per `C4` §3.4's own worked resolution, `rb3b_freespace_read`'s gate row is additionally `SIGNED_OFF_PENDING_HARDWARE_CONFIRMATION`, not unconditionally `SIGNED_OFF` — one of the two steps this capability needs is itself not yet execution-`KNOWN` |
| `restore_capability_status` | `NOT_BUILT` — no Line-1 precedent exists at all (BAA §8: "hard-gated, designed but not buildable"); §9.2 below names the schema/taxonomy gap blocking even a `CAP-SPEC` |
| `restore_evidence_class` | `UNPROVEN` |
| `declared_supported` | **`false`** — `backup_evidence_class = UNPROVEN` fails §7.1's bar outright; restore is `NOT_BUILT`. Neither half of this platform's declared support can be `true` yet |
| notes | First real run is "a single, named, non-production-critical gateway, watched" per BRC §7.3's sign-off note — that run, once it happens, is what would move `backup_evidence_class` to `REAL_ENV_VALIDATED` and is the prerequisite for `CAP-VALIDATED` (workflow §3.1 field 9's re-validation plan) |

### 7.4 Worked row 2 — PAN device-state export (`RB.2`)

| Field | Value |
|---|---|
| platform / version | PAN-OS ≥ 7.1 (device-state export requires superuser at this floor, BRC §7.1) |
| `backup_capability_status` | `CAP-OFFLINE`-equivalent — BAA §12: "`RB.2` IMPLEMENTED 2026-08-30". The Line-1 collector is implemented and its own tests exercise it against a fixture HTTP transport |
| `backup_evidence_class` | **`FIXTURE_ONLY`** — BAA §12's own words, quoted precisely: *"this cloud sandbox has no device reachability... Automated tests exercise it against a fixture HTTP transport only, never a live firewall... this is `IMPLEMENTED`, not `REAL_ENV_VALIDATED` — never mark a network-facing behavior `DONE` from automated tests alone."* This document follows that explicit statement rather than workflow §4's extraction-inventory table cell, which reads "RB.2 REAL_ENV_VALIDATED" — a discrepancy between two Line-1-era documents flagged as an open item (§9.4) rather than silently resolved in either direction |
| `restore_capability_status` | `NOT_BUILT` — same reasoning as row 1; PAN restore has no more Line-1 precedent than CP does |
| `restore_evidence_class` | `UNPROVEN` |
| `declared_supported` | **`false`** — `FIXTURE_ONLY` is exactly the evidence class §7.1's rule exists to stop from being rounded up to "supported"; this is the rule's own worked example, not an incidental result |
| notes | `D4`'s own §8 names an owed follow-up: `RB.2` currently authenticates with the reused inventory API key, not the distinct backup identity §4.1 assumes for CP — a UI 2.0 PAN backup capability inherits that same owed retrofit before it can advance past `CAP-OFFLINE` |

### 7.5 Reading the matrix (`AC-8`)

Both worked rows land at `declared_supported: false`. This is the honest,
fail-closed answer §7.1's rule requires given the evidence actually on
record — not a failure to fill in the template. Acceptance sentence A-3
("Release 1 completion is measured by acceptance of product flows on an
explicit device/version matrix, not by movement count") is served by this
matrix existing and being queried honestly at every later checkpoint, not by
this document manufacturing a `true` row it cannot support.

---

## 8. Acceptance criteria for `REL-BACKUP` (`AC-9`)

At least twelve, each independently testable, mirroring the Testcontainers
discipline `C1`/`C2`/`C3` already establish:

1. **Backup profile compiles into `C4`'s registry correctly.** Approving a
   `VALIDATED` `backup_profile_version` produces exactly one
   `capability_registry` row whose `action_class` equals the maximum class
   over its resolved steps (§2.3); a version with any step outside `C4`
   §2.3's closed set is refused at `VALIDATED` transition, never reaching
   `APPROVED`.
2. **`BackupRun` is a `C2` job, not a shadow record.** A backup Run Now
   creates exactly one `jobs` row and zero rows in any table this document
   does not itself define for run bookkeeping; `job_step_attempt` rows
   populate per `C4` §6's worked pattern (kind/gate/timeout/action_class
   read from the registry, never hand-set).
3. **Artefact round-trip encryption invariants (BRC 9.1/9.2, adapted).** A
   test writes an artefact through the store and asserts (a) no plaintext
   byte exists on disk anywhere, including a bounded decrypt buffer path,
   and (b) `backup_artefact.wrapped_data_key` cannot be unwrapped without
   the custody backend's master key.
4. **`backup_artefact` never carries a `restore` column, and a restore write
   never mutates it.** A static test enumerates `backup_artefact`'s columns
   and asserts none references a restore outcome; an integration test
   writes a `restore_run`/`restore_verification` pair and asserts the
   source `backup_artefact` row's own columns are byte-for-byte unchanged.
5. **Version-locking refusal (BRC §3 rule 5).** A CP-class collection whose
   evidence carries no resolvable `software_version` writes zero
   `backup_artefact` rows; the equivalent PAN-class collection with no
   version evidence writes one row with `software_version IS NULL`.
6. **Retention floor cannot produce an unprotected device.** A property test
   over retention policies asserts no generated deletion candidate set ever
   reduces a device to zero held artefacts or removes its last
   `is_rma_grade` artefact while a non-RMA-grade one remains.
7. **Restore plan compilation refuses on each precondition independently.**
   Seven scenarios, one per §5.3 check, each producing a named refusal before
   any `restore_approval` row is even created and before any `C2` job is
   requested.
8. **Restore approval is per-operation and non-reusable.** A granted
   `restore_approval` row, once its linked `restore_run` reaches any
   terminal state, cannot be referenced by a second `restore_plan`; a test
   asserts the foreign-key/uniqueness shape enforces this.
9. **Restore approver ≠ requester, structurally enforced.** An attempt to
   approve a `restore_approval` row with `approved_by_actor_fingerprint =
   requested_by_actor_fingerprint` is refused at the database or service
   layer, mirroring `C3` §4.3's self-grant test.
10. **Restore-failure acceptance scenario — `OUTCOME_UNKNOWN`, no automatic
    second write.** A restore job's worker is killed after its write step
    crosses `mutation_boundary_crossed = YES` but before an outcome is
    confirmed: the job reaches `OUTCOME_UNKNOWN`; the claim query never
    selects this `job_id` again (mirroring `C2` `AC-9` criterion 7); a
    reconciliation attempt using only an automated read-class job's evidence
    (no direct out-of-band confirmation) is refused as insufficient
    evidence for a restore-class reconciliation (§5.7); a reconciliation
    carrying the required out-of-band evidence succeeds, and only then can a
    **new** `restore_plan` referencing `superseding_prior_run_id` be
    compiled — and compiling it re-runs every §5.3 precondition fresh.
11. **Device/version matrix respects the `FIXTURE_ONLY`/`UNPROVEN` bar.** A
    property test asserts no code path can set `declared_supported = true`
    for a row whose `backup_evidence_class` or `restore_evidence_class` is
    anything other than `REAL_ENV_VALIDATED`.
12. **Key rotation re-wraps without artefact re-encryption.** A test rotates
    the custody backend's master key and asserts every affected
    `backup_artefact` row's `ciphertext_sha256` is unchanged while `key_id`/
    `wrapped_data_key` are updated, and that the artefact still decrypts
    correctly under the new key via the custody boundary.
13. **Consistency group propagation (CP management HA).** A `backup_run`
    for one member of a `backup_consistency_group` failing marks the whole
    group `INCONSISTENT`; an `INCONSISTENT` group's artefacts are excluded
    from any future readiness "held" computation (mirroring BRC §5 rule 1's
    held-vs-attested distinction, extended to group consistency).
14. **`RAW-RETENTION` is not violated by the artefact store.** A static
    test asserts no table this document defines carries a raw device
    transcript column (mirroring `C1` `AC-6` criterion 7's denylist check,
    extended to `backup_artefact`/`restore_run`/`restore_verification`).

---

## 9. Contradictions and open items for the Product Owner (`AC-11`)

**`RESTORE-IN-RELEASE-1`, `APPROVAL-MODEL` and `RAW-RETENTION` are not
reopened.** Each is implemented as ruled: restore is specified in full
(§5–§6) as D-7 requires, without arguing whether it belongs in Release 1;
the routine-vs-per-operation approval split is implemented exactly as
`APPROVAL-MODEL`'s own text states (§6), without narrowing or widening
either half; `RAW-RETENTION`'s default-NO is applied only to raw device
transcripts, never to the backup artefact itself (§1.4), without treating
that distinction as license to retain anything `RAW-RETENTION` actually
covers.

**Documents checked for contradiction:** `AGENTS.md`,
`docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN),
`docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` (BASELINE rev 2),
`docs/design/UI2_0_ARCHITECTURE_DESIGN.md` §6 (DRAFT, amended),
`docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md`,
`docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md`,
`docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md`,
`docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`,
`docs/design/BACKUP_RECOVERY_CONTRACTS.md`,
`docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md`,
`docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md`,
`docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`,
`utils/recovery_key_custody.py`, `utils/action_taxonomy.py`.

### 9.1 Restore's write does not yet fit `utils/action_taxonomy.py`'s five classes — genuine gap, not fixed here

`CLASS_1_RECOVERY_WRITE`'s own docstring scopes it explicitly: *"Narrowly
scoped recovery operations only: temporary backup creation, exact
generated-artifact cleanup, recovery-specific device operations."* A
restore's write — pushing a fetched artefact to a device and invoking a
vendor restore/apply procedure that replaces or merges configuration state
— is not "backup creation" or "artefact cleanup," and BAA §8 independently
classifies restore as *"a `config-write` at the highest blast radius
available... sits at the `OP.2` bar."* `CLASS_2_OPERATIONAL_STATE_CHANGE`
"has no member yet" and is scoped to failover/cluster-role transition, not
configuration restore; `CLASS_3_CONFIGURATION_WRITE` is prohibited outright
at current maturity. **No existing class cleanly names what a restore write
is**, and this document does not invent one — per this movement's own scope
exclusion and per `AGENTS.md`'s prohibition on a new class-2/3/4 command,
`utils/action_taxonomy.py` is not edited here. This is named as the load-
bearing open item for a **taxonomy-successor movement** (the same wave-
scoping principle the dispatch itself applied to `C5`'s ownership of this
file: `utils/action_taxonomy.py` belongs to one writer at a time, and this
movement is not that writer). Until it closes, §5's restore engine is fully
specified but **not executable** on any device — exactly the same posture
`C3` §4.4 already established for `DIRECTORY-POSTURE`: the spec is ready,
the function is not enabled.

**Resolved (D1 Option A, baseline §2 `DEVICE-WRITE-CLASS`):** the write
resolves to `CLASS_1B_CONTROLLED_RESTORE_WRITE`, distinct from recovery-write
and operational-state-change, limited to provenance-bound replay to its
originating physical device. Admission is governed by
`RESTORE_CONTROLLED_WRITE_LEDGER.md`; class 3/4 remain prohibited.

### 9.2 `C4`'s closed step-kind set has no device-directed write/push step, and `sftp_put` is refused unconditionally

`C4` §2.3 lists `sftp_put` as *"reserved, refused at spec-validation time —
no capability may push bytes to a device at current maturity."* A restore
necessarily transfers the artefact **to** the device (or, depending on
vendor procedure, invokes a command that reads it from a location the
platform placed it) — the opposite direction of every step kind `C4` §2.3
currently permits. Beyond transport, no step kind represents "apply a
transferred restore artefact via the vendor's own restore procedure" at all
— `exec`/`poll` could plausibly carry the apply *command*, but the artefact
transfer itself has no legal step kind today. This is a `C4`-successor
amendment, not something this document resolves: §5.4 states restore
capabilities use `C4`'s model "identically to a backup capability's" and
flags this exact gap rather than working around it by inventing a step kind
in this document (which would be exactly the kind of silent reopening of
`C4`'s closed set `C4` §2.3 itself forbids: "adding a kind is an amendment
to this document [`C4`], never a runtime configuration choice").

**Resolved (same D1 decision):** `restore_push` exists in C4 §2.3 and is
legal only for `controlled-restore-write` under C4 §3.3 step 7. `sftp_put`
remains reserved and refused unconditionally.

### 9.3 §9.1 and §9.2 are one combined blocker, not two independent ones

Closing §9.1 (a taxonomy class restore's write can resolve to) without
closing §9.2 (a step kind that can carry it) leaves restore classified but
still inexpressible in `C4`'s registry; closing §9.2 without §9.1 leaves a
step kind with no class-2/3/4-adjacent name to resolve to under `C4` §3.3
step 7's "a row whose `action_class` is class 2, 3 or 4 can never be
`SIGNED_OFF`" rule. Both need the same successor movement's attention
together; recorded here so neither is scheduled as though it alone unblocks
restore execution.

**Resolved, both halves together:** the taxonomy class and C4 step kind
landed together in the predecessor D1 slice. That resolution did not alter
restore plan/run/approval schemas or enable execution. This movement separately
applies the approved §5.3 admission amendments; runtime restore remains disabled.

### 9.4 Documentation inconsistency: `RB.2`'s real-environment status

`docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §4's extraction-inventory table
records the Line-1 status inherited for `RB.2`/`RB.3b`'s row as "RB.2
REAL_ENV_VALIDATED; RB.3b hardware-gated." `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md`
§12 states, in the same document's own words, that `RB.2` is "IMPLEMENTED...
real-environment validation owed" and explicitly warns against marking a
network-facing behaviour done from automated tests alone. `CURRENT_STATE.md`'s
"Real-environment validation owed" section does not list `RB.2` either way.
This document's own matrix (§7.4) follows BAA §12's more detailed, more
recent, and more specific statement (`FIXTURE_ONLY`, not
`REAL_ENV_VALIDATED`) per the UNKNOWN/fail-closed law — flagged here as a
documentation-hygiene item for the workflow document to correct at its next
touch, not a contradiction this document resolves by editing that file.

### 9.5 Version-mismatch restore override — left open

§5.3 check 4 refuses a version-mismatched restore unconditionally. Whether
an authorized emergency override should ever exist (e.g. "restore the
closest available version and accept known gaps") is not decided here —
BRC §3 rule 4's `known_gaps` field already documents the kind of exclusion
such a restore would carry, but this document does not define an override
path; the default refusal stands until the Product Owner names a need for
one.

### 9.6 Retention-deletion approval's exact typed-intent shape is named, not fully specified

§3.5 states retention deletion becomes a `backup_retention_apply` typed
intent gated by `D7` + reason, by analogy to design §6.7's schedule-edit
intent. This document does not write that intent's full JSON schema (window
bounds, aggregate-effect preview) — a small, bounded gap left to
`REL-BACKUP`'s own implementation movement, not blocking anything else in
this contract.

### 9.7 Off-host key custody remains open (inherited from `C1`)

Unchanged from `C1` §6.3/§10 item 3: `recovery_offhost_key_custody` and
`PCP_STORAGE_ENGINE_DECISION.md` gap #9 are not resolved by this document;
§4.4 restates the boundary, not a resolution.

### 9.8 Multi-member consistency-group restore is not fully worked out

§8 criterion 13 covers *backup* consistency-group propagation (inherited
from BRC §7.6 unchanged). Whether restoring one member of a management-HA
consistency group ever requires restoring the group as a unit — and what
that would mean for §6.2's one-plan-one-approval model — is not addressed
here; named as an open item for `REL-BACKUP`'s own implementation movement,
since no Line-1 precedent exists for restore at all (§5.1) to carry forward
a rule from.

---

## 10. Cross-references

- `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — PRODUCT OWNER APPROVED,
  2026-09-09) — `RESTORE-IN-RELEASE-1` (D-7), `APPROVAL-MODEL` (D-2c),
  `RAW-RETENTION` (D-2d), `FREEZE-SLICING` (D-3), acceptance sentences
  A-1…A-3, Release 1 definition.
- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §3.2 (profile engine = same
  executor, stricter admission), §3.5 (provenance/data-class distinction,
  §1.4 above), §4 (extraction inventory row, §9.4's flagged discrepancy),
  §5 B0-7 (this movement's own scope line), §8 D-7 (restore's open-question
  framing before baseline resolved it).
- `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` §6 (the profile model this
  document turns into schema; §6.9/§6.10's PCP/constitutional contradiction
  reports, unaffected by this document's own scope).
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §3 (table sketch
  discipline), §4 (data classes, the encrypted-artefact row this document's
  §3.2 is the successor to), §5 (provenance), §6 (secrets, key custody
  boundary), §8 (Oracle portability, JSONB rule).
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` §2 (job record fields,
  §2.2's `BackupRun`-is-a-job mapping), §3 (state machine, `OUTCOME_UNKNOWN`,
  §5.7's restore-specific extension), §5.3 (retry rules per class), §6
  (pre-execution checks, §5.3/§6.2's restore-specific instances), §7 (owner/
  approver/execution identity, schedule optimistic concurrency), §8 (crash
  matrix).
- `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §4.1 (role
  tokens), §4.3 (self-grant refusal pattern, reused at §2.6/§6.2), §7
  (owner/approver resolution).
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
  §2 (capability registry schema, §2.3's compilation target), §2.4
  (`cp_gaia_backup_local` worked example, reused at §2.4 above), §3 (gate
  resolution algorithm), §4 (VSX/ClusterXL target model), §5 (transport
  decision), §6 (populating `C2`'s step interface).
- `docs/design/BACKUP_RECOVERY_CONTRACTS.md` — §2 (storage layout, §3.2's
  adaptation), §3 (manifest, §3.2's table), §4 (validation levels, §3.4),
  §5 (readiness record — not sketched by this document, a later screen's
  concern), §7 (gate entries, reused by `C4`), §8 (retention, §3.5), §9
  (security invariants, §3.6/§8).
- `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` — §3 (per-vendor
  analysis), §5 (`operational-write` class definition), §6 (verified-backup
  levels), §8 (restore's original hard gate, superseded for UI 2.0 scope by
  D-7 per §9 above), §12 (`RB.2` real-env status, §7.4/§9.4), §13 (`D2`/`D4`
  resolved decisions).
- `docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md` — distinct backup
  credential, no fallback, §2.2/§4.1's inherited pattern.
- `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md` — fail-closed-on-
  unreadable precedent; `C2` §6 check 4 reuses it for backup; §5.3's note
  states why it is `NOT_APPLICABLE` for restore.
- `utils/recovery_key_custody.py` — the envelope-encryption abstraction §4
  carries forward as rules, not code.
- `utils/action_taxonomy.py` — the five action classes; §9.1's gap, named
  and not edited.
- `AGENTS.md` — identity law, raw-evidence law, UNKNOWN/fail-closed law,
  the network-device command gate, evidence laws.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — approval boundaries; destructive
  local-data operation approval (§3.5's retention-deletion intent).
