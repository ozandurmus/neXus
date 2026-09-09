# UI 2.0 — B0/C4 capability registry & command-gate resolution contract

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-09** (platform contract freeze (C1–C6 + baseline directory), per `UI2_0_BASELINE_CONTRACT.md` §2 `FREEZE-SLICING`). Open items listed in this document's own open-items section are deferred to the movements they name; they do not reopen this freeze. Previous status: DRAFT — FOR PRODUCT OWNER FREEZE. Written under
`docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — PRODUCT OWNER APPROVED,
2026-09-09) and `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §3.1–§3.2 (BASELINE,
revision 2). Direct inputs: council items `K-3`, `K-4`, `CP-D6`, `CP-D7`
(`docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md` §4). Frozen
together with `C6` per `FREEZE-SLICING` (D-3), as two separate documents.
Nothing in this document is implementation authority until its own status
line changes to `FROZEN`.

This document is a **contract, not code**: no `ui2/` source, no Line-1 code
change, no device contact. It defines the *runtime schema* a completed `C6`
extraction spec populates — the capability registry, the gate-registry
resolution rule, the closed step-kind set, the VSX/ClusterXL target model,
and the transport default. `B1-4` (collection engine core) and `B1-5`/`B1-6`
(extract/implement the first CP inventory capability) implement this
contract; they do not re-derive it.

---

## 1. Scope, authority chain, and the `C4`/`C6` boundary

### 1.1 What this contract owns

The runtime form of a UI 2.0 capability: the capability registry schema
(identity, transport, closed step-kind set, gate-registry references, parser
reference, evidence-shape reference, field-state model); the gate-registry
resolution rule (`K-4`) from a step's command/call tuple to a gate row; the
VSX/ClusterXL target-multiplicity model (`CP-D6`); the transport default and
its evidence bar for interactive SSH (`CP-D7`); and how `C2`'s step interface
(kind, gate reference, timeout, expect/validation result) is populated from
this registry.

### 1.2 What this contract explicitly does not own

| Not owned here | Owner | This document's relationship to it |
|---|---|---|
| The capability extraction **process** and the **extraction inventory** (which Line-1 collectors get extracted, in what order, what their nine spec fields actually contain) | `C6` (capability extraction contract) | This document defines only the schema shape a completed `C6` spec populates (workflow §3.1's nine fields become this registry's columns); it performs no extraction and asserts no vendor semantics beyond what is already gate-signed-off in `docs/design/BACKUP_RECOVERY_CONTRACTS.md` (`AC-10`) |
| Table identity, audit-table schema, secrets storage, key custody, the `cp_inventory_projection` table itself | `C1` (platform & schema contract) | This document references `C1`'s tables by name (`jobs.capability_id`, `provenance_records`, `cp_inventory_projection`) and states what a *later* VSX/ClusterXL-scoped capability's own projection table must additionally carry (§4.4); it does not redefine any `C1`-owned DDL |
| The job state machine, leasing/heartbeat, `OUTCOME_UNKNOWN`, retry rules, pre-execution checks | `C2` (job execution contract) | `C2` §1.2 names exactly the interface it needs from this document ("a step has a kind, a gate reference, a timeout and an expect/validation result"); §6 below supplies exactly that, populated, and nothing more |
| Session authenticity, RBAC evaluation (`E1`–`E6`), role tokens | `C3` (identity, sessions, RBAC) | Where a step needs "a role token required to run this capability," this document names only an opaque role-token id reference and defers resolution to `C3` (baseline §"sequencing") |
| The `D1`–`D7` capability-state presentation resolver (`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md`, FROZEN) and its existing Line-1 producer `utils/capability_registry.py` | that frozen document; ported as rules per workflow line 285 | **Naming collision warning, stated once, binding:** this document's "capability registry" (§2) is the **execution/runtime schema** — steps, gates, transport, parser. It is a different object from Line-1's `utils/capability_registry.py`, which computes the seven presentation dimensions `D1`…`D7` (surface eligibility, entity applicability, vendor support, …) for the UI shell. This document's registry *supplies input facts* to that resolver (vendor/platform scope feeds `D3`; capability maturity state feeds `D5`) without redefining the resolver, its result algebra, or its five-value/three-value dimension shapes |
| The backup-profile object model, profile lifecycle/approval, artefact store, restore execution | `C7` (backup, artefact and restore engine) | A backup profile is a capability with `action_class = recovery-write`; this document's step model and gate resolution apply to it identically — `C7` owns the profile-version object, approval and artefact lifecycle around it |
| Device contact itself: transport implementation, credentials, network access | Out of scope for every `B0` contract | Nothing here authorizes execution of any capability on a device (`UI2_0_BASELINE_CONTRACT.md` acceptance sentence A-1) |

### 1.3 Authority chain (highest first, per `AGENTS.md` "Authority hierarchy")

1. `AGENTS.md` — durable constitution: identity law (opaque identifiers),
   raw-evidence law, UNKNOWN/fail-closed law, the network-device command gate
   (`docs/AI_DEVELOPMENT_PROTOCOL.md` "Network-device command gate" — ten
   required fields per new/changed device command), VSX/ClusterXL vendor
   notes ("Check Point" section).
2. `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN) — binding direction and
   Phase 0 decisions this document must not reopen: `RUNTIME-DIRECTION`,
   `FREEZE-SLICING` (`C4`+`C6` together), `FIRST-CAPABILITY` (CP inventory
   narrow subset), acceptance sentences A-1…A-3.
3. `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §3.1 (the nine-field
   extraction spec this registry is the runtime target of), §3.2 (collection
   engine core: capability registry, step executor, parser framework,
   evidence writer), §3.3 ("a capability is a semantic unit, not a Line-1
   file").
4. This document, once its own status line reads `FROZEN`.
5. `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §3.1/§3.3/§3.4 (DRAFT —
   FOR PRODUCT OWNER FREEZE, same freeze slice) — the `cp_inventory_projection`
   sketch and the deferred `jobs.capability_id` FK this document's registry
   is the eventual target of.
6. `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` §1.2/§5 (same freeze
   slice) — the step interface this document populates (§6).
7. `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` §6.3 (DRAFT, amended here where
   `CP-D7` requires) — the closed step-kind set this document ports and
   extends (§2.3).
8. `docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md` §4 — `K-3`,
   `K-4`, `CP-D6`, `CP-D7`, this document's direct inputs.
9. `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.3/§7.4/§7.7/§7.8 — the
   frozen, gate-signed-off `RB.3b` command tuple used as this document's
   worked example (`K-3`'s correction).
10. `utils/action_taxonomy.py` — the five action classes, ported verbatim.

### 1.4 Reference-only, not ported

- **`docs/design/UI2_0_ARCHITECTURE_DESIGN.md` §6.3's illustrative CP
  profile.** Read for the step-kind vocabulary shape and the expectation
  rules (§2.3's six numbered rules, ported verbatim). **Not** read as a
  sample command sequence: per `K-3`, that profile invents `show backup
  status` polling, discovers the backup artifact by listing the directory,
  deletes it by the discovered name, and defines a precondition the engine
  (not the profile) owns — none of that ships. §2.4 and §6 below use the
  real, frozen `RB.3b` sequence instead.
- **`checkpoint/vsx_runner.py`, `configuration/checkpoint_config_collector.py`
  (interactive PTY SSH handshake).** Read as the real-environment evidence
  class `CP-D7`'s evidence bar requires (§5.2) — never ported, never imported.
- **`utils/capability_registry.py`, `capability_state_resolver.py`.** Read to
  confirm the naming boundary in §1.2; their `D1`–`D7` rules are ported by a
  later movement, not this one.

---

## 2. Capability registry schema

### 2.1 What a capability is (workflow §3.3, invariant)

A capability is a **semantic unit, not a Line-1 file**. The registry's schema
must allow one capability to be composed from facts originally read from
multiple Line-1 sources, and must allow one fact to be produced once and
consumed by several capabilities without re-extraction. §2.2 states the
mechanism; §2.5 works the mandatory example (HA identity shared by inventory,
readiness and backup).

### 2.2 Capability row (the runtime target of workflow §3.1's nine fields)

```
capability_registry
├─ capability_id            opaque, e.g. "cp_gaia_inventory_show_version_ha_state"
├─ vendor                   e.g. "check_point" | "panorama"
├─ platform_role_scope      e.g. "cp_gaia_gateway" | "cp_management" | "pan_firewall" | "panorama"
├─ action_class             one of utils.action_taxonomy's five classes (field 1)
├─ maturity_state           CAP-SPEC | CAP-OFFLINE | CAP-VALIDATED | CAP-RELEASED (baseline §1)
├─ transport                {kind, trust_rule_ref}                              (field 2, §5 below)
├─ target_shape_ref         which §4 target model this capability's steps run against
├─ steps[]                  ordered list of step rows (§2.3)                    (field 3)
├─ finally_steps[]           ordered list of step rows, run unconditionally     (field 3)
├─ parser_ref               {parser_version, semantics_doc_ref}                 (field 4)
├─ produces_facts[]         named facts this capability's parser derives, each with its own fact_id (§2.5)
├─ consumes_facts[]         named facts this capability reads by provenance reference instead of re-deriving (§2.5)
├─ evidence_shape_ref       {projection_table_ref, discard_raw: true, provenance columns per C1 §5} (field 5)
├─ known_quirks[]           free text + source pointer                         (field 6)
├─ source_pointers[]        Line-1 files/functions read, never imported        (field 7)
├─ validation_status        REAL_ENV_VALIDATED(date, fingerprint) | FIXTURE_ONLY | UNPROVEN (field 8)
└─ revalidation_plan_ref    what the PO runs, on which device class            (field 9)
```

Every field above that can independently be `KNOWN`/`UNKNOWN`/`NOT_APPLICABLE`
per workflow §3.1 carries that state explicitly (§2.6); the capability row is
never reduced to a single pass/fail flag.

### 2.3 Closed step-kind set

Ported from `UI2_0_ARCHITECTURE_DESIGN.md` §6.3, with one addition this
document makes explicit (`xml_api_call`, needed because design §6.3 was
drafted only against the CP/SSH backup-profile example, while workflow §3.2
requires the same step model to also drive PAN XML API capabilities and
plain read capabilities, not only backup writes). This set is **closed**:
adding a kind is an amendment to this document (a `C4`-successor movement),
never a runtime configuration choice, never inferred from a spec author's
free-form step definition.

| kind | What it does | Expectation gate | Gate-reference applicability (§3) |
|---|---|---|---|
| `connect` | opens the transport session (SSH or, for `xml_api_call`-only capabilities, the API key/session per §7.1's existing reuse pattern) through the trust preflight | for `ssh_exec` (§5's default): authentication success + optional banner match — never a shell-prompt regex (`CP-D7`); for `ssh_interactive` (gated, §5.3): login banner/prompt must match `expect.regex` within `timeout_s` | `NOT_APPLICABLE` — no device command is sent |
| `exec` | sends one command/call, reads the response | `ssh_exec`: response evaluated against `expect.regex` over the one-shot `exec_command` channel's output until process exit; `ssh_interactive`: response evaluated by reading a persistent shell until `expect.regex`/timeout; optional `fail_on.regex` short-circuits to failure | `KNOWN`/`UNKNOWN` per §3 — every `exec` step names a gate reference |
| `poll` | repeats `send` every `interval_s` until `until.regex`/`fail_on.regex`/`timeout_s` | as design §6.3's rule 2–4 | `KNOWN`/`UNKNOWN` per §3 |
| `sftp_get` / `scp_get` | fetches one remote path into the recovery/evidence store's staging area (streamed, never to a database) | size bounds; transfer integrity | `NOT_APPLICABLE` if the fetch path/name was itself produced by a gated prior step (the fetch operation is not a new command, per `AGENTS.md`'s parse-scope-extension rule); otherwise `KNOWN`/`UNKNOWN` |
| `sftp_put` | reserved, refused at spec-validation time — no capability may push bytes to a device at current maturity | — | — |
| `xml_api_call` | sends one PAN HTTPS XML API request (`{http_method, type, category, target_scope: direct_firewall\|panorama}`), reads the response | HTTP status + `min_bytes`/`max_bytes`/`content_type` per §7.1/§7.2's shape | `KNOWN`/`UNKNOWN` per §3 |
| `verify` | local checks over a fetched artifact (no device contact) | all listed checks pass | `NOT_APPLICABLE` |
| `disconnect` | closes the session | — | `NOT_APPLICABLE` |

The six expectation-semantics rules of design §6.3 (no step without an
expectation; anchored matching with the `CE.1`/`D3` safeguards; fail-closed on
ambiguity; a timeout is never a success; a step failure stops the sequence
and `finally` runs regardless; captured variables validated against a
declared shape) are ported verbatim and apply to every kind above that has an
expectation gate.

`B1-4` implements only the `ssh_exec` transport adapter for `connect`/`exec`
first (workflow §5 B1 step 4: "only the transport the first capability
needs"); `xml_api_call`, `sftp_get`/`scp_get`, `ssh_interactive` are defined
here as registry-schema members and Java interfaces from day one, built out
in later slices (Astra 5.3, cited by workflow). Being schema-defined but
not-yet-implemented is not the same as being outside the closed set: no
capability may declare a step kind absent from this table, implemented or
not.

### 2.4 Worked example — `RB.3b`'s corrected sample (`K-3`)

The real, gate-signed-off CP Gaia backup sequence (`BACKUP_RECOVERY_CONTRACTS.md`
§7.3/§7.4/§7.7/§7.8), expressed against this registry's shape — **not**
design §6.3's invented polling/listing/deletion sample:

```
capability_id: cp_gaia_backup_local
action_class:  recovery-write
transport:     { kind: ssh_exec, trust_rule_ref: utils.cp_ssh_trust }
steps:
  1. { kind: connect }
  2. { kind: exec, send: "show diskspace"  (Clish, primary form) OR
              "df -P /var/log" (Expert, fallback form — §7.7 point 3),
       gate_reference: rb3b_freespace_read,       action_class: read }
  3. { kind: exec, send: "clish -c 'add backup local'",
       gate_reference: rb3b_add_backup_local,     action_class: recovery-write,
       timeout_s: 900 }
  4. { kind: scp_get, remote: <path produced by the backup subsystem itself,
       per §7.3 point 13 — never discovered by a listing step, per K-3>,
       gate_reference: NOT_APPLICABLE (fetch of a path already gate-governed by step 3) }
  5. { kind: verify, checks: [sha256_recorded, size_within_bounds] }
finally:
  1. { kind: exec, send: "clish -c 'delete backup <name>'",
       gate_reference: rb3b_delete_backup_local,  action_class: recovery-write,
       on_failure: record_and_continue }
  2. { kind: disconnect }
```

No `poll` step: the real sequence's `add backup local` is a single blocking
`exec` bounded at 900 s (§7.3 point 4), not a fire-and-poll pattern — the
`poll` kind stays in the closed set for capabilities that genuinely need it
(e.g. an async device operation with a separate status command), but this
capability does not use it, and a spec author reintroducing polling here
without a gate-signed-off `show backup status` command repeats exactly the
`K-3` defect.

### 2.5 Shared facts — worked example (`AC-1`)

A capability's `produces_facts[]` names each derived fact with its own
`fact_id`, independent of the capability's own `capability_id`. HA identity
is produced once, by whichever capability's step sequence first derives it
against a given target in a given window, and consumed by reference
thereafter:

```
capability cp_gaia_inventory_show_version_ha_state:
  produces_facts: [ { fact_id: "ha_state:<target_ref>", ... },
                     { fact_id: "product_version:<target_ref>", ... } ]

capability cp_gaia_ha_readiness:
  consumes_facts: [ "ha_state:<target_ref>" ]   # provenance_id points at the
                                                  # producing capability's own
                                                  # provenance_records row
  produces_facts: [ { fact_id: "ha_readiness_verdict:<target_ref>", ... } ]

capability cp_gaia_backup_local:
  consumes_facts: [ "ha_state:<target_ref>" ]   # e.g. to decide whether a
                                                  # standby-only precondition
                                                  # applies; no re-derivation
```

A consuming capability's own evidence row (its `cp_*_projection` table, per
`C1` §3.4's pattern) links to the *producing* capability's `provenance_id`
when that fact is still fresh (a freshness rule is `C6`'s to state per
capability, not this document's); when absent or stale, the consuming
capability's own step sequence includes the producing steps itself. This is
exactly what keeps the capability boundary semantic rather than a 1:1 mirror
of Line-1 file boundaries (workflow §3.3, `Astra 4.1`): `ha_state` is read
once by whichever capability runs first and is never re-extracted by
inventory, readiness and backup independently just because Line-1 happened
to read it from three different collectors.

### 2.6 Field-state model (`AC-6`)

Every field enumerated in §2.2 that workflow §3.1 marks as state-carrying is
represented in this registry as a **three-valued enum column**, never a
boolean and never an omitted field:

- `KNOWN` — the field has a concrete, validated value.
- `UNKNOWN` — the field's value is not yet established; **mandatory**
  companion: a `validation_plan_ref` (workflow §3.1 field 9). An `UNKNOWN`
  field never blocks the capability row or the offline parser/tests
  (`CAP-SPEC`/`CAP-OFFLINE` remain reachable); it blocks **device execution**
  of the affected step only (§3.5). Inventing a value to avoid `UNKNOWN` is a
  spec defect (workflow R-04) and this document places that rule at the
  schema level: there is no "default" value a field silently falls back to.
- `NOT_APPLICABLE` — the field genuinely does not apply (e.g. `gate_reference`
  on a `disconnect` step, §2.3's rightmost column). Never conflated with
  `UNKNOWN`: a `NOT_APPLICABLE` field is a closed, correct state; an
  `UNKNOWN` field is an open gap with a plan to close it.

`C1`'s `cp_inventory_projection` sketch (§3.4 of that document) already
follows the matching rule at the evidence-row level: a derived-fact column is
nullable, never a magic string, so `NULL` plus the linked `provenance_record`
row's own completeness signal represents `UNKNOWN` there. This document's
registry-level fields are the pre-execution counterpart of that same rule.

---

## 3. Gate-registry resolution rule (`K-4`)

### 3.1 The problem `K-4` names

`gate_review_ref` presence in a step is not itself sufficient authority to
execute a command: the reference must resolve to a **source-committed** gate
registry entry; the step's `action_class` must be **derived from that row**,
never author-declared in the capability spec; and the write-marker denylist
(no `send`/call template may resolve to a class-2/3/4 command, `AGENTS.md`
"No new class 2, 3 or 4 command") applies to every step that sends anything,
not only ones an author remembered to mark.

### 3.2 Gate registry row shape

A `gate_registry` row is the runtime, queryable form of one
`docs/AI_DEVELOPMENT_PROTOCOL.md` "Network-device command gate" entry (the
same ten fields: why required, action class, vendor/platform/shell/context,
timeout, retry, max frequency, session reuse, unsupported behavior,
secret-output risk, safe telemetry), plus:

```
gate_registry
├─ gate_id            opaque, source-coined slug, e.g. "rb3b_add_backup_local"
├─ vendor, platform_role_scope, shell_context, transport_kind
├─ canonical_command_key   the exact, literal command/call template this row
│                          answers for (never a prefix; RB.3b's own rule:
│                          "the implementing module carries this as a literal
│                          tuple, not a `show ` prefix test")
├─ action_class            per utils.action_taxonomy — authoritative
├─ sign_off_state          DRAFTED | SIGNED_OFF | SIGNED_OFF_PENDING_HARDWARE_CONFIRMATION
│                          | BLOCKED | SUPERSEDED
├─ timeout_s, retry_rule, max_frequency, session_reuse_rule
├─ unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields[]
└─ source_document_pointer  e.g. "docs/design/BACKUP_RECOVERY_CONTRACTS.md §7.3"
```

`gate_id` values are source-committed (the row lives in a Flyway-migrated
table or an equivalently version-controlled fixture, `C1`'s to place) —
never an author-typed free-text string a spec can invent at will. This is
what makes `gate_review_ref` "resolve to a source-committed gate registry
entry" rather than merely "be present" (`K-4`).

### 3.3 Resolution algorithm

`resolve_gate(step) -> {KNOWN, gate_id, action_class} | {UNKNOWN, reason}`,
deterministic and re-evaluated at two points — spec/registry-load time
(offline, no device contact) and immediately before device execution
(`C2` claim time, never trusted stale from spec-load) — never cached across
a sign-off state change:

1. **Kinds with `NOT_APPLICABLE` gate applicability** (§2.3's rightmost
   column: `connect` under `ssh_exec`, `verify`, `disconnect`, a fetch of a
   path already produced by a gated prior step) skip resolution entirely;
   `gate_reference` is recorded `NOT_APPLICABLE`, not looked up.
2. Compute the step's **canonical command key**: `(vendor, platform_role_scope,
   shell_context, transport_kind, exact command/call template)` — the
   *literal authored string* (or, for `xml_api_call`, the `{type, category,
   target_scope}` tuple), never the runtime-substituted value of a captured
   variable, and never a prefix or regex match over it.
3. Query `gate_registry` for rows whose `canonical_command_key` **exactly**
   equals the step's key.
4. **Zero rows** → `UNKNOWN: requires gate entry`. The step, and the
   capability row, remain valid at `CAP-SPEC`/`CAP-OFFLINE`; the step is
   excluded from the execution-eligible view (§3.5).
5. **Exactly one row**:
   - `sign_off_state = SIGNED_OFF` → `KNOWN`. `step.action_class` is set
     from the row. If the spec author's own declared `action_class` on the
     step (present for readability/review, never for resolution) disagrees
     with the row's, spec validation **fails** — a silent override is never
     performed in either direction; a mismatch is a spec defect to fix, not
     a runtime ambiguity to paper over.
   - `sign_off_state ∈ {DRAFTED, SIGNED_OFF_PENDING_HARDWARE_CONFIRMATION,
     BLOCKED, SUPERSEDED}` → `UNKNOWN: gate exists, <state> pending`, named
     with the specific state (mirrors `BACKUP_RECOVERY_CONTRACTS.md` §7.7/§7.8's
     real "signed off, command-string confirm-on-hardware" precedent —
     signed off is necessary but the hardware-confirmation step is a
     distinct, trackable gap, never collapsed into "resolved").
6. **More than one row matching the same canonical key** → hard validation
   error at spec-load time (never resolved by "pick first," "pick most
   specific," or any other implicit ranking) — an ambiguous gate resolution
   is a spec defect that must be fixed by narrowing the rows' keys, not a
   runtime condition to route around.
7. A row whose `action_class` is class 2, 3 or 4 can never be `SIGNED_OFF`
   by construction (`AGENTS.md`: "No new class 2, 3 or 4 command at the
   current product maturity") — the write-marker denylist is therefore
   enforced once, at gate-row creation, and every step resolution inherits
   it automatically rather than re-checking it per step.

### 3.4 Worked resolution — the `cp_gaia_backup_local` example (§2.4)

Step 3 (`exec`, `send: "clish -c 'add backup local'"`) resolves: canonical
key `(check_point, cp_gaia_gateway, expert_via_clish_c, ssh_exec, "clish -c
'add backup local'")` → exactly one row, `gate_id = rb3b_add_backup_local`,
`sign_off_state = SIGNED_OFF` (§7.3's gate sign-off is unconditional, no
hardware-confirm caveat) → `KNOWN`, `action_class = recovery-write`. Step 2
(`exec`, `send: "show diskspace"`) resolves against `rb3b_freespace_read`,
`sign_off_state = SIGNED_OFF_PENDING_HARDWARE_CONFIRMATION` (§7.7's sign-off
note: the exact primary-vs-fallback command form is confirmed at the first
watched real gateway run) → `UNKNOWN: gate exists,
SIGNED_OFF_PENDING_HARDWARE_CONFIRMATION pending`. The capability's
`CAP-OFFLINE` build and parser tests proceed against fixtures regardless
(§3.5); its `CAP-VALIDATED` transition (baseline §1's ladder) is blocked
until that step resolves `KNOWN`.

### 3.5 `UNKNOWN` blocks execution, not spec (workflow §3.1 field 3, invariant)

A capability's **execution-eligible view** — the subset of `capability_registry`
that `C2`'s job admission (`E3`/`E6`, "a job whose resolved `action_class` is
one of these classes is refused at admission") is permitted to read
`capability_id`/`action_class` from — includes a capability **only when every
step's gate resolution is `KNOWN` or `NOT_APPLICABLE`**, computed as a
boolean AND over all steps (`finally` steps included, since a `finally` write
is exactly as capable of reaching a device as any other step). A capability
with any `UNKNOWN` step:

- **is** present in the full spec-level registry: its Java implementation
  compiles, its parser runs against fixtures, its unit/integration tests
  pass, `CAP-OFFLINE` is reachable (workflow §3.1: "an `UNKNOWN` field never
  blocks the spec or the offline implementation");
- **is not** present in the execution-eligible view `C2` admission consults,
  so no `job_type`/`capability_id` referencing it can leave `REQUESTED`
  through a legitimate path (§7 gives the testable form of this, criterion
  3).

This is the schema-level implementation of workflow §3.1 field 3's rule
("the offline parser may still be built, but the step is not executable on a
device until the entry exists") and needs no new entry in `C2` §6's
pre-execution check battery: the capability simply is not a member of the
set `C2` claim-time admission is allowed to dispatch, by construction, not by
an additional runtime check re-deriving what this document already fixes at
registry-compile time.

---

## 4. VSX/ClusterXL multiplicity model (`CP-D6`)

### 4.1 The rule

`CP-D6`: a VS-scoped `device_id` must be refused; each ClusterXL cluster
member is its own endpoint/artifact; Spark/Gaia Embedded is unsupported by
lifecycle classification (not inferred from shell behavior, `AGENTS.md`).
This document's target model must represent VSX and ClusterXL multiplicity
**without collapsing distinct members/contexts into one identity** — the
`AC-4` requirement — while staying inside `C1`'s existing rule that `devices`/
`endpoints` rows are physical-endpoint identity only (`C1` §3.2).

### 4.2 Target model

A capability's `target_ref` is a composite, never a single opaque id that
could silently mean either a physical device or a virtual context:

```
target_ref = {
  device_id:            physical device (C1 devices.device_id) — always present
  endpoint_id:          physical endpoint (C1 endpoints.endpoint_id) — always present
  virtual_system_ref:   null (physical/default context) | "<device_id>__vsid_<vs_id>"
                         — mirrors the existing Line-1 entity_id convention
                         (utils/restore_readiness.resolve_entity_id and
                         PAN_HA_SERIAL_IDENTITY_HARDENING_DECISION.md's
                         `f"{device}__vsid_{vs_id}"`), reused here as a
                         UI 2.0-native composite identity, not a new devices row
  cluster_member_ref:   null (standalone device) | opaque id of this physical
                         device's ClusterXL cluster membership — grouping/display
                         only (§4.3); never a devices/endpoints row of its own
}
```

**No VS-scoped or cluster-scoped row is ever created in `C1`'s `devices` or
`endpoints` tables** — `virtual_system_ref` and `cluster_member_ref` are
target-model modifiers layered over one physical `device_id`/`endpoint_id`,
exactly the refusal `CP-D6` requires.

### 4.3 ClusterXL — worked example (`fw-ist-core`-shaped)

A two-member ClusterXL cluster is **two independently registered physical
devices**, each with its own `device_id`/`endpoint_id` (`B1-4b`'s manual
registration, one enrollment per member):

```
device: fw-ist-core-a   (device_id A, endpoint_id A)
device: fw-ist-core-b   (device_id B, endpoint_id B)
cluster_member_ref: "fw-ist-core-cls" on both — a display/grouping label only,
                     never a row in devices/endpoints
```

A capability run against this cluster (e.g. `cp_gaia_inventory_show_version_ha_state`)
executes **twice**, once per member — `target_ref = {device_id: A, ...}` and
`target_ref = {device_id: B, ...}` are two separate jobs, two separate step
sequences, two separate `provenance_records` chains, two separate
`cp_inventory_projection` rows. Each row carries its own `ha_state` derived
fact (e.g. `ACTIVE` for A, `STANDBY` for B) tagged `MEMBER_SPECIFIC` by
default, per `AGENTS.md`'s Check Point rule ("ClusterXL member differences
are `MEMBER_SPECIFIC` unless expected-state evidence proves otherwise — do
not infer drift from a peer comparison alone"). Nothing merges the two rows
into one cluster-level fact; the `cluster_member_ref` label is what lets the
UI *group* them for display without collapsing their identity.

### 4.4 VSX — worked example (`vsx-ist`-shaped)

A VSX host is **one physical device** with several virtual-system contexts:

```
device: vsx-ist-01      (device_id V, endpoint_id V — one row, the physical box)
virtual systems: VS0 (default context, no vsenv needed), VS2, VS5
```

A capability run against VS2 has `target_ref = {device_id: V, endpoint_id: V,
virtual_system_ref: "vsx-ist-01__vsid_2"}`. Its step sequence inserts one
additional, independently gated `exec` step before the capability's normal
steps: `{ kind: exec, send: "vsenv 2", gate_reference: <vsx_vsenv_context_switch>,
action_class: read }` (`AGENTS.md`: "Expert `vsenv <VSID>` is a validated
context mechanism"). Every subsequent step in that run — and every derived
fact it produces — carries `virtual_system_ref = "vsx-ist-01__vsid_2"`; a run
against VS5 is a wholly separate step sequence, provenance chain and evidence
row with `virtual_system_ref = "vsx-ist-01__vsid_5"`. VS0's row (or a
physical-context capability that never calls `vsenv`) carries
`virtual_system_ref = null`, distinct from both.

### 4.5 What `C1`'s current `cp_inventory_projection` sketch does and does not need to change

`C1` §3.4's `cp_inventory_projection` sketch has no `virtual_system_ref` or
`cluster_member_ref` column today. This is **not a contradiction** with the
rule above: the `FIRST-CAPABILITY` decision (baseline §2) scopes the first CP
inventory capability to a narrow, non-VSX, non-cluster-specific subset
(`show version`, HA state), and `C1`'s sketch is deliberately sized to that
scope, not to the general model. The general target model (§4.2) is this
document's contribution; the first VSX- or ClusterXL-scoped capability's own
`Extract`/`Implement` movement (a later `C6`/B-phase pair, not `B1-5`/`B1-6`)
adds `virtual_system_ref`/`cluster_member_ref` (or an equivalent target-model
foreign key) to *that capability's own* projection table, following `C1`
§3.4's stated pattern ("this is the pattern every later capability's own
projection table … follows"), via a small additive `C1`-successor migration —
not a rework of `cp_inventory_projection` itself. Recorded as an open item
for the PO in §8, not a contradiction.

---

## 5. Transport decision (`CP-D7`)

### 5.1 The default: validated-device-first

A capability defaults to the **least-interactive transport proven
sufficient by its Line-1 evidence**: `ssh_exec` (`exec_command` per channel —
one authenticated SSH connection, each `exec`/`poll` step opening its own
one-shot command channel and reading until that channel's process exits, no
persistent shell state, no prompt-reading loop), PAN XML API, or SFTP/SCP.
`ssh_interactive` (a persistent shell channel, read-until-prompt-regex) is
used **only** where a capability's extraction spec's Line-1 provenance
proves it necessary (§5.2). This is `CP-D7`'s resolution of the open
question the brief posed: "decide exec-per-step (connect gate = auth +
banner), or gate an interactive-shell transport with real-environment
prompt evidence" — this document decides **exec-per-step is the default**,
and states the evidence bar for the gated exception.

Under `ssh_exec`, the `connect` step (§2.3) is exactly what `CP-D7` names:
authentication success plus an optional banner match — never a shell-prompt
regex, because no persistent shell is opened. `RB.3b`'s real sequence (§2.4)
is `ssh_exec` throughout: each Clish command is invoked as `clish -c '<cmd>'`,
a **self-terminating one-shot process** whose output is read until the
process exits over its own `exec_command` channel. `§7.3` point 7's "existing
session reuse" means the same authenticated TCP/SSH connection is kept open
across steps to avoid re-authenticating — a connection-level optimization,
not a shared shell/PTY with persistent state. Session reuse and interactive
transport are not the same thing, and this document does not conflate them.

### 5.2 The evidence bar for interactive SSH

A capability may declare `transport.kind = ssh_interactive` only when its
`C6` extraction spec's Line-1 source pointers (field 7) include a **captured
transcript or documented behavior showing that a subsequent command's
success depends on shell state established by an earlier command in the
same session** — i.e., the commands are not independently issuable as
separate one-shot `exec_command` invocations. The precedent already in this
repository is `vsenv <VSID>`: `checkpoint/vsx_runner.py`'s nested-SSH +
`vsenv <VSID>` pattern and `AGENTS.md`'s "Expert `vsenv <VSID>` is a
validated context mechanism" together show that once a VSX context is
entered, subsequent commands must run *inside that same shell's continued
state* — a `vsenv 2` issued as an isolated `exec_command` on a fresh channel
would not affect any later, separately-opened channel. That is exactly the
shape of evidence `CP-D7` requires: not "the vendor CLI happens to support
an interactive mode," but "this repository's own real-environment capture
shows a later command's correctness is conditioned on earlier shell state in
the same session."

Absent that proof, `ssh_exec` stays the default even for a capability whose
Line-1 collector historically kept one SSH session open across several
commands (§5.1) — reused authentication is not evidence of required shell
state. `configuration/checkpoint_config_collector.py`'s "interactive PTY SSH
handshake" (`AI_START_HERE.md`'s directory map) is Line-1's own real-world
instance of a capability that *did* need this: its extraction spec, when
written, is expected to carry the transcript evidence justifying
`ssh_interactive` for whatever it extracts, by the same rule — this document
does not pre-judge that outcome (extraction is `C6`'s job, `AC-10`), only
states the bar it must clear.

### 5.3 What `ssh_interactive` changes in the step model

Under `ssh_interactive`, `connect`'s expectation is the login banner/prompt
match design §6.3 specifies (`expect.regex` within `timeout_s`); every
`exec` step's expectation is evaluated by reading the persistent shell
stream until its own `expect.regex`/`fail_on.regex`/timeout, exactly as
design §6.3 rows 1–2 describe. No new step kind is needed — `ssh_interactive`
is a transport-level setting on the capability (§2.2's `transport.kind`),
not a different closed-set member; the same `connect`/`exec`/`disconnect`
kinds apply, with the expectation-evaluation mechanics in the rightmost
column of §2.3 selected by `transport.kind`.

---

## 6. Populating `C2`'s step interface — worked example

`C2` §1.2/§5.1 assumes exactly: a step has a **kind**, a **gate reference**, a
**timeout**, and an **expect/validation result**. Using `cp_gaia_backup_local`
step 3 (§2.4, `add backup local`) as the concrete instance, at the moment
`C2`'s worker writes the pre-contact `job_step_attempt` row (`C2` §5.1):

| `C2` interface field | Value, from this registry |
|---|---|
| `step_kind` | `exec` — copied verbatim from `capability_registry.steps[2].kind` (§2.3's closed enum; `C2` never invents or infers a kind) |
| `action_class` | `recovery-write` — **not** author-declared on the step; resolved from `gate_registry.rb3b_add_backup_local.action_class` (§3.3 step 5), the row `C2` §5.1 already requires this field to key retry rules on |
| gate reference | `rb3b_add_backup_local`, state `KNOWN` (§3.4) — `C2`'s claim-time re-resolution (§3.3) re-runs the same algorithm fresh; a sign-off revoked between spec-compile and claim time flips this to `UNKNOWN` and the job is refused at `C2`'s pre-execution check 1 (approval state re-checked fresh) rather than reaching `EXECUTING` |
| `timeout_s` | `900` — from `gate_registry.rb3b_add_backup_local.timeout_s`, mirrored onto the step (§3.2); this is the same 900 s `BACKUP_RECOVERY_CONTRACTS.md` §7.3 point 4 fixes, carried through unchanged, not re-derived |
| expect/validation result | `matched_expectation` is evaluated against `steps[2].expect.regex` — this **exact regex string is `C6` extraction output**, not something this document asserts; at registry-schema level the field is recorded `UNKNOWN` (workflow §3.1 field 3/4: parser semantics not yet extracted) until `C6`'s spec for this capability supplies it, which is itself an instance of §2.6's field-state rule and `AC-10`'s boundary (this document defines the *slot*, `C6` fills it) |

This shows the interface fully populated in shape — kind, gate reference
(with its `KNOWN`/`UNKNOWN` state), timeout, and the expect-result slot — and
shows, in the same worked example, exactly where this document's authority
ends and `C6`'s begins: the regex value itself is not invented here.

---

## 7. Acceptance criteria for `B1-4` (collection engine core) and `B1-5`/`B1-6` (extract/implement the first CP inventory capability)

At least ten, each independently testable:

1. **Closed step-kind enforcement.** Loading a capability spec whose any
   step's `kind` is not one of §2.3's eight members fails registry
   validation with a named error (`STEP_KIND_NOT_IN_CLOSED_SET`); no code
   path accepts an unrecognized kind string, logs a warning, and continues.
2. **Gate resolution is deterministic and exact-match only.** A property/
   fuzz test constructs command strings that are proper prefixes, suffixes,
   or superstrings of a `gate_registry` row's `canonical_command_key` and
   asserts every one of them resolves `UNKNOWN` (zero rows), never matching
   the near-miss row — proves the RB.3b "never a `show ` prefix test" rule
   at the registry layer, in code.
3. **`UNKNOWN`-gated capability is offline-buildable but execution-ineligible.**
   A capability with one `UNKNOWN`-resolved step: its Java implementation
   compiles, its parser test suite runs and passes against fixtures
   (`CAP-OFFLINE` reachable); a job submission naming its `capability_id`
   is refused before `C2`'s `REQUESTED` state is ever reached (the
   execution-eligible view, §3.5, excludes it) — both assertions in one
   test, proving neither half of workflow §3.1 field 3's rule is
   accidentally satisfied by breaking the other.
4. **Ambiguous gate resolution fails closed at load time.** Two
   `gate_registry` rows sharing one `canonical_command_key` (a seeded test
   fixture) cause registry load to fail with a named validation error before
   any capability referencing that key is registered — never silently
   resolved by insertion order or row id.
5. **Sign-off-state-gated `UNKNOWN`, worked from real data.** A test seeds
   the `rb3b_freespace_read` row exactly as `BACKUP_RECOVERY_CONTRACTS.md`
   §7.7 states it (`SIGNED_OFF_PENDING_HARDWARE_CONFIRMATION`) and asserts
   `resolve_gate` on the matching step returns `UNKNOWN` with that state
   named, while `rb3b_add_backup_local` (`SIGNED_OFF`, no caveat) on the
   same capability resolves `KNOWN` — proving per-step resolution, not a
   capability-wide flag.
6. **`sftp_put` is refused at validation, unconditionally.** Any spec
   containing an `sftp_put` step fails validation regardless of gate
   references, sign-off state, or action class — proving the reserved kind
   never becomes executable by any registry-side path.
7. **VSX target identity never collapses.** Two capability runs against
   `vsx-ist-01__vsid_2` and `vsx-ist-01__vsid_5` (§4.4) produce two distinct
   provenance chains and two distinct evidence rows sharing `device_id` but
   differing `virtual_system_ref`; a query for one never returns the other's
   facts, and no code path writes a `devices`/`endpoints` row keyed by a
   VS-scoped identity.
8. **ClusterXL members never merge.** Two capability runs against
   `fw-ist-core-a` and `fw-ist-core-b` (§4.3) produce two independent
   `cp_inventory_projection` rows with `MEMBER_SPECIFIC` HA-state facts by
   default; a test asserts no aggregation step averages, dedupes, or
   selects "the" cluster's HA state from the two rows.
9. **Transport default is `ssh_exec` absent evidence.** A capability spec
   with no Line-1 shell-state-dependency evidence in its source pointers
   fails validation if it declares `transport.kind = ssh_interactive`
   (`STEP_TRANSPORT_EVIDENCE_MISSING`); the same spec with `ssh_exec`
   validates. (The evidence-presence check itself is a `C6`-authored,
   `C4`-schema-enforced boolean field on the spec — this document fixes
   that the check exists and blocks validation on its absence, not the
   judgment call of what counts as sufficient evidence for any specific
   future capability.)
10. **`connect`'s expectation matches transport kind.** Under `ssh_exec`, a
    `connect` step spec carrying a shell-prompt `expect.regex` fails
    validation (`CP-D7`: connect gate is auth+banner only under `ssh_exec`);
    under `ssh_interactive`, the same step requires one.
11. **Shared-fact reuse, worked end to end.** Running
    `cp_gaia_inventory_show_version_ha_state` then `cp_gaia_ha_readiness`
    against the same `target_ref` within the freshness window: the second
    capability's evidence row links its `ha_state` input to the *first*
    capability's `provenance_id` and issues zero device steps for that fact;
    outside the freshness window (or on first run) it issues its own.
12. **`C2` interface fields populate without invention.** For
    `cp_gaia_backup_local` step 3 (§6), a test asserts `job_step.step_kind`,
    `job_step_attempt.action_class` and `.timeout_s` are read verbatim from
    the registry/gate rows (never hand-set by the worker), while the
    `expect.regex` slot is recorded `UNKNOWN` until a `C6` spec populates
    it — proving the interface is populated, not fabricated, at every field.

---

## 8. Contradictions and open items for the Product Owner

**Contradictions with frozen authority: none found.** Documents checked:
`AGENTS.md`, `docs/design/UI2_0_BASELINE_CONTRACT.md`,
`docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md`,
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` (checked
specifically for the `D1`–`D7`/`capability_registry` naming collision, §1.2 —
no collision once the two objects are named distinctly, and this document
redefines neither the resolver, its result algebra, nor its dimension
shapes), `docs/design/BACKUP_RECOVERY_CONTRACTS.md`,
`utils/action_taxonomy.py`, `docs/AI_DEVELOPMENT_PROTOCOL.md`. This document
reopens no PO-reserved decision: `FIRST-CAPABILITY`'s narrow-subset scoping
and `RUNTIME-DIRECTION` are implemented as ruled, not re-litigated.

**Amendment to a `DRAFT` (not frozen) document, stated per §1.3 item 7:**
`docs/design/UI2_0_ARCHITECTURE_DESIGN.md` §6.3's illustrative CP profile
sample is superseded by §2.4's `RB.3b`-accurate sequence (`K-3`), and its
implicit interactive-shell transport assumption is superseded by §5's
`ssh_exec`-default decision (`CP-D7`). Both are exactly what the council
brief assigned this document to resolve (`K-3`, `CP-D7` — baseline §7 direct
inputs), not an unauthorized reopening of frozen authority — `UI2_0_
ARCHITECTURE_DESIGN.md` itself is `DRAFT`, not `FROZEN` (its own status
line), and its §5 cross-reference note already anticipates amendment by the
`C*` contracts.

**Open items, not contradictions:**

1. **VSX/ClusterXL target-model columns on future capabilities' own
   projection tables are not yet added anywhere** (§4.5) — this document
   defines the general model; the first VSX- or ClusterXL-scoped
   capability's own `Extract`/`Implement` movement (later than `B1-5`/`B1-6`,
   which stay in the narrow `FIRST-CAPABILITY` subset) is where a small,
   additive `C1`-successor migration adds `virtual_system_ref`/
   `cluster_member_ref` to that capability's specific table — not to
   `cp_inventory_projection` as sketched today.
2. **`gate_registry`'s `sign_off_state` vocabulary (`DRAFTED` /
   `SIGNED_OFF` / `SIGNED_OFF_PENDING_HARDWARE_CONFIRMATION` / `BLOCKED` /
   `SUPERSEDED`) is this document's own proposal**, chosen to model the real
   `BACKUP_RECOVERY_CONTRACTS.md` §7.7/§7.8 "signed off, command-string
   confirm-on-hardware" precedent exactly; it is not an existing repository
   convention the PO has separately ratified. The *rule* it enforces (only
   an unconditionally signed-off row resolves `KNOWN`) is the load-bearing
   part; the exact state names are open to PO relabeling without touching
   §3's algorithm.
3. **The `xml_api_call` step kind (§2.3) is new relative to design §6.3's
   literal list.** It is this document's own decision, made because
   workflow §3.2 requires the closed step-kind set to also cover PAN XML
   API capabilities and design §6.3 was drafted only against the CP backup
   example — presented as this document's answer to a gap design left open,
   not as overriding any PO ruling naming a different or narrower set.
4. **The `C6` freshness rule for shared-fact reuse (§2.5)** — how stale an
   `ha_state` fact may be before a consuming capability must re-derive it
   rather than reference the existing `provenance_id` — is explicitly left
   to `C6` (each capability's own re-validation plan, workflow §3.1 field 9)
   and is not fixed by this document; §2.5 fixes only the reuse *mechanism*
   (a fact has its own id and a provenance link), not any specific
   freshness window.

---

## 9. Cross-references

- `docs/design/UI2_0_BASELINE_CONTRACT.md` — binding direction and Phase 0
  decisions (`RUNTIME-DIRECTION`, `FREEZE-SLICING`, `FIRST-CAPABILITY`).
- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §3.1 (capability extraction
  spec — the fields this registry is the runtime target of), §3.2 (Java
  collection engine core), §3.3 (capability as semantic unit), §4
  (extraction inventory — `C6`'s scope, not read as authority here beyond
  the nine-field shape).
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §3.1/§3.3/§3.4/§5 —
  `cp_inventory_projection`, `jobs.capability_id`'s deferred FK,
  `provenance_records`.
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` §1.2, §5, §6 — the step
  interface this document populates and the pre-execution check battery
  the execution-eligible view (§3.5) feeds.
- `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` §6.3/§6.4 — the closed
  step-kind set and raw-output-handling rules this document ports and
  (per §8) amends where `K-3`/`CP-D7` require.
- `docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md` §4 — `K-3`,
  `K-4`, `CP-D6`, `CP-D7`, this document's direct inputs.
- `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.3/§7.4/§7.7/§7.8 — the real
  `RB.3b` command tuple used throughout as the worked example.
- `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` — the
  frozen `D1`–`D7` presentation resolver this document's registry feeds
  input facts to, without redefining it (§1.2).
- `AGENTS.md` — network-device command gate, identity law, VSX/ClusterXL
  vendor notes, action taxonomy pointer.
- `utils/action_taxonomy.py` — the five action classes.
