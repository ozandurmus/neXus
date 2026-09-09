# UI 2.0 — B0/C6 capability extraction contract

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-09** (platform contract freeze (C1–C6 + baseline directory), per `UI2_0_BASELINE_CONTRACT.md` §2 `FREEZE-SLICING`). Open items listed in this document's own open-items section are deferred to the movements they name; they do not reopen this freeze. Previous status: DRAFT — FOR PRODUCT OWNER FREEZE. Written under
`docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — PRODUCT OWNER APPROVED,
2026-09-09) and `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §3.1/§3.4/§3.5/§4
(BASELINE, revision 2). Direct input: `docs/design/UI2_0_C4_CAPABILITY_
REGISTRY_GATE_RESOLUTION_CONTRACT.md` (DRAFT — FOR PRODUCT OWNER FREEZE),
whose §2 registry schema is the field-by-field target every spec this
document's procedure produces must populate. Frozen together with `C4` per
`FREEZE-SLICING` (D-3), as two separate documents; neither is implementation
authority until its own status line changes to `FROZEN`.

This document is a **contract, not code, and not an extraction**: no `ui2/`
source, no Line-1 code change, no device contact, and — stated once, binding
— **no real capability's spec or fixture set is produced, filled in, or
committed by this movement**. It is the *procedure* a later `Extract`
movement (workflow §3.3 step a; the first instance is `B1-5`) follows, and
the *ordered queue* those movements draw from. §4's worked example is
illustrative prose inside this document, not a spec file.

---

## 1. Scope, authority chain, and the `C6`/`B1-5` boundary

### 1.1 What this contract owns

1. The **extraction procedure**: an ordered checklist a movement follows to
   turn one Line-1 collector/runner into a `C4`-schema-valid capability spec
   — including the mandatory existing-`produces_facts` check that keeps a
   capability a semantic unit, not a Line-1-file mirror (workflow §3.3).
2. The **fixture-generation procedure**: the Private Replay tokenizer
   invocation, the `SYNTHETIC`/`DERIVED` marking rule, the preserved-property
   checklist with a proof method per property, and the mandatory DLP gate
   step.
3. One **fully worked example** — the `FIRST-CAPABILITY` narrow subset (CP
   inventory `show version` + HA state, `UI2_0_BASELINE_CONTRACT.md` §2) —
   filled against `C4`'s exact schema, to prove the procedure produces a
   complete, valid spec in practice.
4. The **corrected ordered extraction inventory** (workflow §4's rows,
   carried forward and now naming each row's `C4` gate-registry / `C1` table
   target).
5. **Acceptance criteria** for `B1-5` (the first real Extract movement) and
   for the extraction-tooling movement (`ui2_b0_extraction_tooling`, workflow
   §5 B0-9) that implements this document's fixture-generation procedure in
   Line-1 Python.

### 1.2 What this contract explicitly does not own

| Not owned here | Owner | This document's relationship to it |
|---|---|---|
| The capability registry **schema** itself — field names, the field-state model, the closed step-kind set, the gate-registry resolution algorithm, the VSX/ClusterXL target model, the transport decision | `C4` | This document's spec template (§2) names `C4` §2.2's columns verbatim and fills them; it defines no new schema, redefines no algorithm, and invents no gate-registry row |
| Table identity, audit schema, `cp_inventory_projection`, `provenance_records` | `C1` | Referenced by name (§2, §4, §5); no DDL is sketched or amended here |
| Job state machine, leasing, `OUTCOME_UNKNOWN`, pre-execution checks | `C2` | Not touched; a spec this procedure produces feeds `C4`'s registry, which feeds `C2`'s step interface (`C4` §6) — this document does not re-derive that population |
| **Authorship / sign-off of any real `gate_registry` row** (the ten-field network-device command gate, `docs/AI_DEVELOPMENT_PROTOCOL.md` "Network-device command gate") | The gate-authoring/sign-off process itself, per capability, per step | This document's procedure (§2) and worked example (§4) *name* which steps need a gate entry and record them `UNKNOWN: requires gate entry` (`C4` §3.3 step 4); it never mints a `gate_id`, never asserts a `sign_off_state`, and never performs the authorship act itself — that is `B1-5`'s (or a dedicated preceding gate-authoring step's) job, not this contract's |
| **Actually extracting a real capability's spec/fixtures** — for any Line-1 source, including the `FIRST-CAPABILITY` subset used as this document's own worked example | `B1-5` (first instance); every later capability's own `Extract` movement | §4 is illustrative text inside this document; no `ui2/` spec file, no fixture file, and no Flyway migration is created by this movement (scope, `.nexus/approved_task.json`) |
| The extraction-tooling **script's own code** (a Line-1 Python deliverable that runs the tokenizer over a capture directory and emits a fixture set in this contract's layout) | `ui2_b0_extraction_tooling` (workflow §5 B0-9, a separate, later movement) | §3 and §6 state what that tool must do and how it is tested; this document writes no Python |
| Device contact of any kind | Out of scope for every `B0` contract | Nothing here authorizes execution of any capability on a device (`UI2_0_BASELINE_CONTRACT.md` acceptance sentence A-1) |

### 1.3 Authority chain (highest first, per `AGENTS.md` "Authority hierarchy")

1. `AGENTS.md` — durable constitution: identity law, raw-evidence law
   (never reproduce real secrets or operational identities in docs), the
   UNKNOWN/fail-closed law, the network-device command gate's ten required
   fields, privacy/DLP ("follow the local repository privacy gate").
2. `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN) — `RUNTIME-DIRECTION`,
   `FREEZE-SLICING` (`C4`+`C6` together), `FIRST-CAPABILITY` (the exact
   worked-example scope this document must not exceed), `RAW-RETENTION`
   (default NO), acceptance sentences A-1…A-3 — none reopened here.
3. `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §3.1 (the nine-field spec this
   document sharpens into `C4`'s columns, §2.1 below), §3.3 ("a capability is
   a semantic unit, not a Line-1 file" — the produces_facts check, §2.2 step
   2), §3.4 (device-contact coordination — referenced, not restated), §3.5
   (provenance after `discard_raw` — the fixture/evidence boundary, §3
   below), §4 (the extraction inventory this document corrects, §5 below).
4. `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
   (DRAFT, same freeze slice) §2 (the registry schema this document's
   template fills field by field), §2.5 (the shared-fact worked example this
   document reuses rather than duplicates, §2.2 step 2), §3 (gate resolution
   — referenced when naming, never resolving, a gate reference), §4
   (VSX/ClusterXL target model — referenced in §4's worked example), §5
   (transport decision and its evidence bar — referenced in §4), §8 open
   item 4 (the shared-fact freshness rule `C4` explicitly leaves to this
   document — closed as a mechanism in §2.2 step 2, not as a fixed value).
5. This document, once its own status line reads `FROZEN`.
6. `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §3.4 (`cp_inventory_
   projection`), §5 (`provenance_records`) — the tables §4's worked example
   populates by reference.
7. `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md` — the tokenizer path (`utils/
   support_bundle.py::Tokenizer`) this document's §3 names as the fixture
   sanitization tool.
8. `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.3/§7.4/§7.7/§7.8 — the real
   `RB.3b` gate records `C4` §2.4/§6 already worked; this document reuses
   that worked example by reference (§4.3) instead of re-deriving it.
9. `docs/AI_DEVELOPMENT_PROTOCOL.md` "Network-device command gate" — the
   ten-field documentation requirement a gate entry must satisfy before a
   step this procedure names can resolve `KNOWN` (not performed here, §1.2).
10. `utils/action_taxonomy.py` — the five action classes, referenced for
    field 1 (Identity)'s `action_class`.

### 1.4 The `C6`/`B1-5` boundary, stated once

This document is the **reusable procedure and the ordered queue**. `B1-5` is
the **first execution** of that procedure against one specific Line-1
source (`checkpoint/scripts/cp_inventory.sh`, `checkpoint/cp_runner.py`),
producing one committed spec and one committed fixture set under the `ui2/`
tree. Every later row in §5's queue gets its own `Extract` movement that
does the same thing against a different source. This document itself
commits neither a spec nor a fixture — it is what those movements are
graded against.

---

## 2. The extraction procedure

### 2.1 Workflow's nine fields, mapped onto `C4`'s registry columns

Workflow §3.1 sketched nine mandatory capability-spec fields. `C4` §2.2
already annotates its own registry columns with the workflow field number
each realizes; this document reproduces that mapping as the literal spec
template an extraction movement fills, adding the two structural columns
`C4` introduced beyond the original nine (`maturity_state`, `target_shape_ref`)
and the shared-fact pair (`produces_facts[]` / `consumes_facts[]`, `C4` §2.5)
that make a capability a semantic unit rather than a 1:1 Line-1 mirror.

| Workflow field | `C4` registry column(s) (verbatim names, `C4` §2.2) | Notes |
|---|---|---|
| 1. Identity | `capability_id`, `vendor`, `platform_role_scope`, `action_class` | four columns for one workflow field; `action_class` per `utils.action_taxonomy` |
| — (`C4` addition, not one of the nine) | `maturity_state` | `CAP-SPEC` at the end of a successful `Extract` movement (baseline §1 ladder) |
| 2. Transport | `transport` = `{kind, trust_rule_ref}` | `kind` per `C4` §5.1's `ssh_exec`-default rule; `ssh_interactive` only past `C4` §5.2's evidence bar |
| — (`C4` addition) | `target_shape_ref` | which `C4` §4 target model (physical / VSX-scoped / ClusterXL-member-scoped) this capability's steps run against |
| 3. Command / call tuple | `steps[]`, `finally_steps[]` | ordered step rows, `C4` §2.3's closed step-kind set; each step's `gate_reference` per `C4` §3 |
| 4. Parser semantics | `parser_ref` = `{parser_version, semantics_doc_ref}` | extracted fields, tolerant rules, failure classification and evidence-of-truth rule are recorded as prose the `semantics_doc_ref` slot points at; `parser_version` is `UNKNOWN` until an `Implement` movement (step b) mints one |
| — (`C4` addition, `C4` §2.5) | `produces_facts[]`, `consumes_facts[]` | §2.2 step 2 below states when each is populated |
| 5. Evidence shape | `evidence_shape_ref` = `{projection_table_ref, discard_raw: true, provenance columns per C1 §5}` | `projection_table_ref` names a `C1` table by its literal name |
| 6. Known vendor quirks | `known_quirks[]` | free text + `source_pointer`; a quirk's *existence* and its *effect on this capability's subset* are two separate states (§2.2 step 3) |
| 7. Line-1 source pointers | `source_pointers[]` | files/functions read, never imported (`RUNTIME-DIRECTION`) |
| 8. Validation status inherited | `validation_status` | `REAL_ENV_VALIDATED(date, fingerprint)` \| `FIXTURE_ONLY` \| `UNPROVEN` — the *Line-1* validation state, distinct from `maturity_state` |
| 9. Re-validation plan | `revalidation_plan_ref` | what the PO runs, on which device class, to reach `CAP-VALIDATED` |

Every field above that `C4` §2.6 marks state-carrying is filled with an
explicit `KNOWN` / `UNKNOWN` / `NOT_APPLICABLE` value, never omitted, never a
boolean. An `UNKNOWN` field's companion `validation_plan_ref` is mandatory
(workflow §3.1 field 9; `C4` §2.6); a `NOT_APPLICABLE` field carries a short
`state_rationale` string naming *why* it genuinely does not apply, so the two
states are never interchangeable by omission (`AC-3` below; worked in full
in §4).

### 2.2 Ordered checklist

An extraction movement performs these steps, in order, for exactly one
queue row (§5) per movement (workflow §3.3: "steps a and b can be one
movement for small capabilities"):

1. **Identify the candidate.** Name the Line-1 source file(s)/function(s)
   this movement reads, and confirm the candidate matches one row of §5's
   ordered queue (never an ad hoc, off-queue capability — the queue's
   ordering is itself an acceptance criterion, §6).

2. **Check for an existing `produces_facts` entry before starting a new
   extraction.** Search every already-committed capability spec's
   `produces_facts[]` list for the fact(s) this candidate would derive. Two
   outcomes:
   - **Not found** (or this is the first capability to touch the fact): the
     new spec's own `produces_facts[]` names it, with its own `fact_id`,
     independent of `capability_id` (`C4` §2.2/§2.5).
   - **Found** (the fact already has a producer): the new spec's
     `consumes_facts[]` names the existing `fact_id` **instead of** adding
     steps that re-derive it, and states a `freshness_window` for that
     entry (how stale the producing capability's `provenance_id` may be
     before this consumer must re-derive rather than reference it — the
     concrete value is this movement's own engineering call per capability,
     never invented certainty; `C4` §8 open item 4 left the *mechanism*
     open, and this is that mechanism). Zero new device-contacting steps are
     added for a fact obtained this way.

   **Worked negative example (reused, not duplicated, from `C4` §2.5):** a
   future extraction movement processing §5 row 6 (OP.0 HA readiness) checks
   `produces_facts` first and finds `ha_state:<target_ref>` already produced
   by `cp_gaia_inventory_show_version_ha_state` (§4 below, §5 row 1). The
   readiness capability's spec therefore sets
   `consumes_facts: ["ha_state:<target_ref>"]`, adds **no** step that
   re-issues `cphaprob stat` for that fact, and links its own evidence row to
   the *producing* capability's `provenance_id` when within the freshness
   window; only outside that window, or on a target the producing capability
   has never run against, does its own step sequence include the producing
   steps itself (`C4` §2.5, verbatim mechanism). This is the extraction of a
   *fact reference*, not a re-extraction — the negative case `AC-1` requires.

3. **Fill every `C4` §2.2 field, per §2.1's mapping**, assigning each
   state-carrying field its `KNOWN`/`UNKNOWN`/`NOT_APPLICABLE` value:
   - A field is `NOT_APPLICABLE` only when it is structurally excluded by
     this capability's own step-kind set or transport (e.g. `gate_reference`
     on a `disconnect` step, `C4` §2.3's rightmost column) — record the
     `state_rationale` naming the structural reason.
   - Otherwise, if the concrete value is not yet established, the field is
     `UNKNOWN` with a `validation_plan_ref` naming exactly what closes the
     gap (a real-environment capture, a gate-entry authorship step, a PO
     validation session) — never a default, never invented certainty
     (workflow R-04; `C4` §2.6).
   - `known_quirks[]` carries **two** states where relevant, not one: the
     quirk's *existence* (usually `KNOWN` — it is documented in Line-1) and
     its *effect on this capability's own narrow subset* (often `UNKNOWN`
     until proven, per `FIRST-CAPABILITY`'s own ruling — worked in §4.1 field
     14 below). Collapsing these into a single state is a spec defect.

4. **Name the gate-registry entries the spec's steps will need**, without
   authoring them (§1.2). For each `exec`/`xml_api_call` step: either its
   `gate_reference` is `NOT_APPLICABLE` (per `C4` §2.3's applicability
   column), or it is recorded `UNKNOWN: requires gate entry` naming the
   exact `(vendor, platform_role_scope, shell_context, transport_kind,
   canonical_command_key)` tuple (`C4` §3.2/§3.3) a future gate-authoring
   step must resolve — this movement never invents a `gate_id` or asserts a
   `sign_off_state`.

5. **Choose the transport** against `C4` §5's evidence bar: default
   `ssh_exec`; declare `ssh_interactive` only when this candidate's own
   Line-1 source pointers include a captured transcript or documented
   behaviour proving a later command depends on shell state a prior command
   established in the same session (`C4` §5.2) — absent that proof, `ssh_exec`
   stays the default even if the Line-1 collector happened to keep one SSH
   session open across commands (session reuse ≠ required shell state).

6. **Choose `target_shape_ref`** against `C4` §4's model: physical
   `device_id`/`endpoint_id` always present; `virtual_system_ref` set only
   when the candidate's own scope is VSX-context-specific;
   `cluster_member_ref` set only when the candidate runs per ClusterXL
   member. A candidate outside `FIRST-CAPABILITY`'s narrow scope that
   touches VSX or ClusterXL multiplicity states so explicitly and follows
   `C4` §4.5's rule: its own projection table (not `cp_inventory_projection`)
   carries the multiplicity columns, added by that capability's own later
   `C1`-successor migration.

7. **Mark `validation_status` (field 8)** from the Line-1 evidence actually
   read: `REAL_ENV_VALIDATED(date, fingerprint)` when Line-1's own record
   shows a real device run and fingerprint exist; `FIXTURE_ONLY` when only
   sanitized fixtures/tests exist in Line-1; `UNPROVEN` when Line-1 itself
   never validated the behaviour (e.g. CE.2, `cphaprob tablestat`). This
   state is inherited, not re-earned — the Java capability's own maturity is
   `maturity_state`, tracked separately.

8. **Write `source_pointers[]` (field 7).** Every file/function actually
   read, so the extraction is auditable; a source pointer is never imported,
   and a pointer that only *corroborates* a command literal (rather than
   being the queue row's own named source in §5) is named as corroboration,
   not conflated with the primary source (worked in §4.1 field 15).

9. **Write `revalidation_plan_ref` (field 9).** What the PO runs, on which
   device class, to move this capability from `CAP-OFFLINE` to
   `CAP-VALIDATED` (workflow §3.3 step c) — always concrete enough that a
   human could execute it without further clarification, even when the
   capability's other fields still carry `UNKNOWN` states.

10. **Self-check against this document's §6 acceptance criteria** before
    treating the spec as ready for the `Implement` movement (step b). A spec
    that fails any applicable §6 criterion is not yet a valid extraction
    output, regardless of how many fields are filled.

---

## 3. Fixture-generation procedure

### 3.1 Tokenizer invocation — the exact entry point (`AC-4`)

Every fixture starts from a **sanitized real capture**, produced through:

- **`utils/support_bundle.py::Tokenizer`** (the class) — instantiated with an
  HMAC key from `_get_support_key()` (`FBUDDY_SUPPORT_HASH_KEY` env var, or a
  generated, `0600`-permissioned key file under `data/.support_hmac.key`;
  never a hardcoded key).
- **`Tokenizer.token(kind: str, value: Any, length: int = 14) -> str | None`**
  — the general-purpose pseudonym primitive: `f"{KIND}_{hmac_sha256(key,
  f'{kind}:{value}')[:length]}"`. Deterministic per `(key, kind, value)`
  triple — the same real value always tokenizes to the same token under the
  same key, which is exactly the property §3.3's cross-output-identity
  checklist item relies on.
- **`Tokenizer.network_token(value: Any) -> {"id": ..., "prefix": ...}`** —
  the address-specific form: tokenizes the address itself but **preserves the
  CIDR prefix length as a separate, untokenized integer field**.
- **`_sanitize_dict`/`_sanitize_value`, keyed by the module-level
  `SENSITIVE_KEYS` map** — the driver that decides, per dictionary key,
  whether a value is tokenized (and with which `kind`) or passed through
  unchanged. `SENSITIVE_KEYS` is Line-1's own general support-bundle map; per
  `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md` §"Existing sanitization
  primitive": "a useful primitive, not a complete export policy for
  arbitrary application data" — the extraction-tooling movement
  (`ui2_b0_extraction_tooling`) extends this driver with the specific field
  names each capability's raw output actually carries (e.g. a CP Gaia
  appliance serial the general map does not already name); this document
  fixes the entry point and the rule, not that movement's own key list.

This is the tool used **at extraction time only**: it runs as ordinary
Line-1 Python invoked by the person/agent performing the extraction, never
inside the UI 2.0 (Java) runtime — `P-1` is not violated by this usage
(workflow §4's own note on this exact tool).

### 3.2 `SYNTHETIC` / `DERIVED` marking rule, with an example of each (`AC-4`)

Every fixture file's own metadata (§3.4) carries a `fixture_kind` field:
`REAL` (the default: a sanitized real capture, untouched beyond
tokenization), `SYNTHETIC`, or `DERIVED`. `SYNTHETIC` and `DERIVED` are the
**only** exceptions to "every fixture is a sanitized real capture"; neither
substitutes for device validation (workflow §3.1, `R-05`).

- **`SYNTHETIC`** — a never-captured path, most often an error path Line-1
  never actually observed. **Example:** a `show version all` `exec` step
  whose one-shot `exec_command` channel returns zero bytes before process
  exit (no real-environment capture of this failure exists in Line-1's own
  record) — authored by hand, `fixture_kind: SYNTHETIC`, metadata field
  `synthetic_reason: "no real-environment capture of an empty-output show
  version response exists yet; exercises the parser's OUTCOME_UNKNOWN
  failure classification path"`.
- **`DERIVED`** — a variant built from a real capture: version drift,
  empty/multiple results, a parser boundary case, a relationship-consistency
  case. **Example:** a `product_version` field edited on a copy of a real,
  already-tokenized `show version all` fixture to a second real CP Gaia
  release string Line-1 has separately observed on a different gateway —
  `fixture_kind: DERIVED`, `derived_from: <source fixture id>`,
  `derived_change: "product_version field only"`.

A fixture with neither `fixture_kind` present, nor `REAL`/`SYNTHETIC`/
`DERIVED` as its value, is not a valid fixture (§6 criterion 7).

### 3.3 Preserved-property checklist — a proof method per property (`AC-5`)

Workflow §3.1's fixture rules name what the tokenizer must preserve; domain-
separated HMAC pseudonyms do not guarantee this by themselves (workflow
R-05). This checklist restates each property as something the extraction
movement actually tests, not merely documents:

| Property | Proof method |
|---|---|
| **Address format** | `Tokenizer.network_token()` keeps the CIDR prefix as an untokenized integer alongside the tokenized address id. A fixture-set test asserts the token's own id matches the tokenizer's fixed shape (`NET_[0-9a-f]{14}` by default) and that `prefix` is an integer in `0..32` — proving the *format* (a network reference with a separable prefix) survives, not merely that some string is present. |
| **Serial length/shape** | `Tokenizer.token()`'s default output (`SERIAL_<14 hex chars>`) does **not** by itself match a real device serial's length/character class (e.g. a fixed-length, all-digit PAN serial). Before tokenizing, the extraction step records the real capture's serial `shape` (`{length, charset}`) in the fixture's own metadata (never the real value); a fixture-set test asserts the fixture's declared `shape` is present and that any parser test built against this fixture exercises boundary lengths matching that shape — recorded as an **open item on the extraction-tooling movement** (§7) to add a shape-preserving token encoding on top of the existing primitive, since the default hex form alone does not satisfy this property. |
| **Cross-output identity equality** | `Tokenizer.token(kind, value)` is a deterministic HMAC of `(kind, value)` under one key — the same real identity tokenizes to the identical string wherever it appears. A fixture-set test tokenizes the same raw identity value once per output it appears in (using the same `kind` and the same key/session) and asserts the resulting tokens are byte-identical; a fixture set spanning more than one Line-1 output for one capture session is generated within a single tokenizer-key session for exactly this reason (worked in §4.2, fixtures 1–2). |
| **Cluster/context membership** | Two real captures from the same ClusterXL cluster (or the same VSX host's two virtual-system contexts) tokenize their shared grouping identifier (a `cluster_member_ref`-shaped label, or the VS-context label) to the **same** token across both fixtures, while their member/context-distinguishing fields (device identity, `ha_state`, `vsid`) tokenize to **different** tokens. A fixture-set test asserts both halves of this — the same grouping token, different member tokens — proving `C4` §4.3/§4.4's grouping model survives sanitization without collapsing member/context identity (worked in §4.2, fixture 5). |
| **Ordering** | For any output whose field sequence is meaningful (e.g. an interface table's row order, a cluster-member enumeration as the device itself printed it), the sanitization step tokenizes values **in place** and never sorts, deduplicates, or reorders the sequence. A fixture-set test records a position fingerprint of the real capture before tokenization and asserts the finished fixture's element order is a position-for-position match against it (same length, same index correspondence). |

### 3.4 Fixture metadata (mandatory on every fixture file)

- `command_tuple` — the exact command(s)/call(s) this fixture answers
  (matches a spec's `steps[]` entry).
- `vendor_version` — the vendor/platform version this capture is from.
- `capture_date` — when the underlying real capture was taken (never
  applicable to `SYNTHETIC`; the *authoring* date is recorded instead for
  that case).
- `fixture_kind` — `REAL` \| `SYNTHETIC` \| `DERIVED` (§3.2).
- `derived_from` / `derived_change` — required, and only present, on
  `DERIVED` fixtures.
- `synthetic_reason` — required, and only present, on `SYNTHETIC` fixtures.
- `capture_session_id` — shared across every fixture drawn from the same
  real capture session, so a validator can check the cross-output-identity
  property (§3.3) without external context.

The Java parser test asserts against the capability spec's own parser
semantics, never against raw Python output (workflow §3.1, verbatim).

### 3.5 The DLP gate step — mandatory, not optional (`AC-6`)

Before any fixture file is committed, the same interpreter that runs the
rest of this repository's checks runs:

```
<same interpreter> main.py --repository-privacy-check
```

(`application/workflows/maintenance.py::repository_privacy_check()`, which
calls `utils.repository_privacy.scan_repository(...)` — the local, offline
Corporate-Git privacy gate: no network, no credentials, matched values never
printed.) This applies to a fixture file **exactly as to any other
repository file** (workflow §3.1, verbatim) — there is no "it's just a test
fixture" or "it's just documentation" exemption. A fixture directory that
fails this gate is not committed; the gate failing on a fixture is treated
as a sanitization defect in the fixture-generation step, not as a reason to
weaken or bypass the gate (`AGENTS.md` "Privacy and DLP": "do not weaken
security detection... to satisfy DLP").

---

## 4. Worked example — `FIRST-CAPABILITY` (CP inventory narrow subset)

This is the illustrative text this document commits to prove the procedure
produces a complete, `C4`-schema-valid spec. It is **not** a spec file, is
**not** committed under `ui2/`, and authorizes no extraction of the real
capability — that is `B1-5`'s job (§1.4).

### 4.1 The spec, field by field

Capability: `cp_gaia_inventory_show_version_ha_state` — the exact
`capability_id` `C4` §2.5 already used for its own shared-fact worked
example; reused here, not reinvented.

| # | Field | Value | State | Rationale / `validation_plan_ref` |
|---|---|---|---|---|
| 1a | `capability_id` | `cp_gaia_inventory_show_version_ha_state` | `KNOWN` | matches `C4` §2.5's own naming |
| 1b | `vendor` | `check_point` | `KNOWN` | — |
| 1c | `platform_role_scope` | `cp_gaia_gateway` | `KNOWN` | `FIRST-CAPABILITY` is gateway-scoped, not management |
| 1d | `action_class` | `read` (class 0) | `KNOWN` | both steps are read-only vendor CLI reads |
| — | `maturity_state` | `CAP-SPEC` | `KNOWN` | this movement's own exit state |
| 2 | `transport` | `{kind: ssh_exec, trust_rule_ref: utils.cp_ssh_trust}` | `KNOWN` | `C4` §5.1 default; no Line-1 evidence of cross-command shell-state dependency for these two commands (`C4` §5.2 bar not met and not needed — `checkpoint/direct_ssh_probe.py` issues each as its own one-shot command) |
| — | `target_shape_ref` | `device_id`/`endpoint_id` always; `virtual_system_ref: NOT_APPLICABLE`; `cluster_member_ref`: `KNOWN` per-member when the target is a ClusterXL member, `NOT_APPLICABLE` for a standalone gateway | mixed, per-field as shown | `virtual_system_ref` is `NOT_APPLICABLE` because `FIRST-CAPABILITY` is explicitly scoped non-VSX (`UI2_0_BASELINE_CONTRACT.md` §2, `C4` §4.5) — structural exclusion, not an open gap |
| 3 | `steps[]` | `1. {kind: connect}`; `2. {kind: exec, send: "show version all", gate_reference: UNKNOWN: requires gate entry, action_class: read}`; `3. {kind: exec, send: "cphaprob stat", gate_reference: UNKNOWN: requires gate entry, action_class: read}` | steps 1 `NOT_APPLICABLE` (connect gate, `ssh_exec`, `C4` §2.3); steps 2–3 `UNKNOWN` | no `gate_registry` row exists yet for either literal command under `(check_point, cp_gaia_gateway, *, ssh_exec)` (§1.2 — this document never authors one); `validation_plan_ref`: author both gate entries (`docs/AI_DEVELOPMENT_PROTOCOL.md`'s ten fields) before `B1-6` |
| 3b | `steps[2].timeout_s` | not yet fixed | `UNKNOWN` | `validation_plan_ref`: `B1-5`/PO validation captures real observed latency against the registered test CP gateway (`B1-4b`'s `is_test_target` flag) before `B1-6` hardcodes a bound |
| — | `finally_steps[]` | `1. {kind: disconnect}` | `NOT_APPLICABLE` (gate) | `KNOWN` step content |
| 4 | `parser_ref` | `{parser_version: UNKNOWN, semantics_doc_ref: <this row's own prose, below>}` | `UNKNOWN` | `validation_plan_ref`: `B1-6` (Implement) assigns the first `parser_version` at `CAP-OFFLINE`; re-checked against `C4` §2.6 at that point. Prose recorded now regardless: extracted fields are `product_version` (from `show version all`'s version line) and `ha_state` (from `cphaprob stat`'s local-role line); failure classification is `OUTCOME_UNKNOWN` when the channel-drain effect (field 14, below) is later shown to apply and no completion marker is observed; evidence-of-truth is `product_version` → the `show version all` raw line, `ha_state` → the `cphaprob stat` raw role line, each via its own `provenance_records` row. The exact tolerant-parsing regex (whitespace/version-drift handling) is left `UNKNOWN` pending real-environment capture review at `B1-5`. |
| — | `produces_facts[]` | `[{fact_id: "ha_state:<target_ref>"}, {fact_id: "product_version:<target_ref>"}]` | `KNOWN` | identical `fact_id` naming to `C4` §2.5's own worked example — reused, not reinvented |
| — | `consumes_facts[]` | `[]` | `NOT_APPLICABLE` | this capability is the **producer** of both facts, not a consumer of any (structural exclusion, not a gap) |
| 5 | `evidence_shape_ref` | `{projection_table_ref: cp_inventory_projection, discard_raw: true, provenance columns per C1 §5}` | `KNOWN` | `C1` §3.4's sketch already carries exactly `product_version`/`ha_state` as its two derived-fact columns, sized to this exact subset |
| 6 | `known_quirks[]` (existence) | channel-drain: a deployed remote collector's completion is only certain once a literal `DONE` marker line is observed; ending on EOF/process-exit without it must not be treated as success | `KNOWN` (the quirk exists in Line-1) | source: `checkpoint/cp_runner.py::_run_remote_collection` (`state["done_marker_seen"]`) |
| 6b | `known_quirks[]` (effect on this subset) | whether the same drain ambiguity applies to these two one-shot `exec_command` calls (as opposed to the batched multi-gateway collection script the quirk was documented against) | `UNKNOWN` | `validation_plan_ref`: per `FIRST-CAPABILITY`'s own ruling (`UI2_0_BASELINE_CONTRACT.md` §2), the `B1-5`/PO validation session must determine and record whether these two one-shot execs need a completion-marker check or are bounded by ordinary process exit; **`CAP-VALIDATED` is blocked until this is recorded**, exactly as the baseline ruling states — if shown to be without effect, that boundary is recorded here, not silently assumed |
| 7 | `source_pointers[]` | `checkpoint/scripts/cp_inventory.sh`; `checkpoint/cp_runner.py::_run_remote_collection` (channel-drain, primary source per workflow §4); `checkpoint/direct_ssh_probe.py::READ_ONLY_COMMANDS["version"]` (corroborates the literal `show version all`/`show version`/`clish -c` command forms); `checkpoint/vsx_runner.py` and `checkpoint/cp_preflight_battery.py` (`A3_CPHAPROB_STAT`) (corroborate `cphaprob stat`); `checkpoint/cp_preflight_projection.py::project_cp_software_version_fact` (`CP-A2`, corroborates the software-version fact shape) | `KNOWN` | read, never imported |
| 8 | `validation_status` | `REAL_ENV_VALIDATED` (channel-drain quirk open) | `KNOWN` | matches workflow §4's own inherited status for this row |
| 9 | `revalidation_plan_ref` | PO validation session (workflow §3.3 step c / baseline B1 step 6): start this capability from the Java product against the registered test CP gateway; confirm both commands resolve `KNOWN` gate rows, the timeout bound holds under real latency, and the channel-drain effect (field 6b) is recorded either `NOT_APPLICABLE` (proven no drain ambiguity) or an updated, still-open `UNKNOWN` — `CAP-VALIDATED` is blocked until that specific determination lands | `KNOWN` | the plan itself is concrete even though its outcome is not yet known |

Every field is present with an explicit state; no field is left ambiguous
between `UNKNOWN` and `NOT_APPLICABLE` — each `NOT_APPLICABLE` above carries
its structural reason inline, each `UNKNOWN` its `validation_plan_ref`
(`AC-3`).

### 4.2 The described fixture set

Five fixtures, none committed by this movement (§1.4):

1. **REAL** — a sanitized `show version all` capture from one real,
   already-collected CP Gaia gateway. Metadata: `fixture_kind: REAL`,
   `command_tuple: ["show version all"]`, `vendor_version` (the real,
   observed release string — retained in clear; a version string is not a
   sensitive identity), `capture_date`, `capture_session_id: S1`.
2. **REAL** — a sanitized `cphaprob stat` capture from the **same** gateway
   and session as fixture 1. `capture_session_id: S1` — proves cross-output
   identity equality (§3.3): the device-identity token in this fixture must
   be byte-identical to fixture 1's.
3. **DERIVED** — a version-drift variant of fixture 1: `fixture_kind:
   DERIVED`, `derived_from: <fixture-1-id>`, `derived_change: "product_version
   field only"`, using a second real CP Gaia release string Line-1 has
   separately observed.
4. **SYNTHETIC** — the empty-output `show version all` failure path (§3.2's
   worked `SYNTHETIC` example, verbatim).
5. **REAL, ClusterXL pair** — two `cphaprob stat` captures from a real
   two-member cluster, one per member. Both fixtures share one grouping
   token (`cluster_member_ref`-shaped) and carry distinct device tokens and
   distinct `ha_state` values (`ACTIVE` / `STANDBY`) — proves cluster
   membership is preserved without collapsing member identity (§3.3,
   `C4` §4.3).

### 4.3 Cross-reference to `C4` §6, not duplication

`C4` §6 already worked, field by field, how `C2`'s step interface (`step_kind`,
`action_class`, gate reference, `timeout_s`, expect/validation result) is
populated from a registry row and a gate row, using `cp_gaia_backup_local`
step 3. The mechanism is identical in kind for
`cp_gaia_inventory_show_version_ha_state`'s step 2: once a real
`show_version_all`-shaped gate row exists and resolves, `step_kind` is
copied verbatim from `steps[1].kind` (`exec`), `action_class` is resolved
from the gate row (never author-declared, `C4` §3.3 step 5), and the
`expect.regex` slot stays `UNKNOWN` until this document's `parser_ref` field
supplies it — exactly `C4` §6's own worked closing sentence: "this document
defines the *slot*, `C6` fills it." This document does not re-derive that
mechanism a second time; it names the reuse.

---

## 5. Corrected ordered extraction inventory

Workflow §4's rows, carried forward in order, each now naming its target
`C4` gate-registry entries and/or `C1` table (`AC-8`). Rows workflow §4
itself already marks as non-capability infrastructure are listed separately
below the table, unchanged, and are not queue items (they were never
candidates for a `produces_facts`/gate-resolution spec to begin with).

| # | Line-1 source (reference only) | Capability (illustrative id) | Target `C4` gate-registry entries / `C1` (or future `C1`-successor) table |
|---|---|---|---|
| 1 | `checkpoint/scripts/cp_inventory.sh`, `checkpoint/cp_runner.py` | `cp_gaia_inventory_show_version_ha_state` (§4, first row — `B1-5`) | `C1.cp_inventory_projection` (existing sketch); new gate rows needed for `show version all` and `cphaprob stat` under `(check_point, cp_gaia_gateway, *, ssh_exec)` — both `UNKNOWN: requires gate entry` until authored |
| 2 | `checkpoint/scripts/vsx_collect.sh`, `checkpoint/vsx_runner.py`, `checkpoint/vsx_parser.py` | `cp_vsx_context_enumeration` | a new, not-yet-sketched VSX-scoped projection table (`C4` §4.5's later `C1`-successor migration, not `cp_inventory_projection`); gate row `vsx_vsenv_context_switch` (already named by `C4` §4.4) plus per-context read rows — each needs its own `canonical_command_key` row even where the literal string matches row 1's, because `shell_context` differs inside a VS (`C4` §3.2) |
| 3 | `configuration/checkpoint_config_collector.py` | `cp_gaia_configuration_snapshot` | a new `C1`-successor evidence table (evidence shape re-decided, no HTML per workflow); gate rows pending — this row is also `C4` §5.2's own named candidate for proving the `ssh_interactive` evidence bar (interactive PTY SSH handshake), an open item for that row's own future Extract movement, not decided here |
| 4 | `panorama/panorama_runtime_runner.py`, `panorama/pan_identity.py` | `pan_runtime_identity` | a new `C1`-successor PAN projection table; `xml_api_call`-kind gate rows for the PAN XML API `{type, category, target_scope}` tuples this capability calls; the PAN auth-transport P0 fix is applied in the Java transport adapter (`PAN_AUTH_TRANSPORT_CONVERGENCE_AUDIT.md`), not re-litigated by this row |
| 5 | `configuration/panorama_config_collector.py` | `panorama_configuration_export` | a new `C1`-successor Panorama-config table; `xml_api_call` gate rows, same transport fix as row 4 |
| 6 | `checkpoint/preflight_collector.py`, `cp_preflight_battery.py`, `cp_preflight_extraction.py`, `cp_preflight_projection.py`; `panorama/preflight_collector.py` and siblings; `utils/failover/*` | `cp_gaia_ha_readiness` (+ PAN equivalent) | a new `C1`-successor readiness projection table; **no new gate row** for the `ha_state` reuse case (§2.2 step 2's worked negative example — `consumes_facts`, zero new device steps); new gate rows only for readiness-battery items not already covered by row 1 (e.g. `cphaprob syncstat`; `cphaprob tablestat` held per baseline D-5 until proven on hardware) |
| 7 | `utils/failover_plan/` (OP.1.S1) | `cp_failover_plan_compile` | gate reference `NOT_APPLICABLE` throughout — pure compile-only function over already-collected evidence, no `exec`/`xml_api_call` step; a new `C1`-successor plan/dry-run-report projection table |
| 8 | `utils/recovery_collect.py`, `checkpoint/checkpoint_recovery_collector.py`, `panorama/panorama_recovery_collector.py`, `BACKUP_RECOVERY_CONTRACTS.md` §7 | `cp_gaia_backup_local` (already fully worked, `C4` §2.4/§6 — reused verbatim, not re-derived) + `pan_device_state_export` (RB.2) | gate rows `rb3b_freespace_read`, `rb3b_add_backup_local`, `rb3b_delete_backup_local` (already named, `C4` §2.4); `C7`'s future artefact-manifest table (referenced, `C1` §3.1 excludes it from this document's own sketch) |
| 9 | `utils/recovery_store.py`, `recovery_manifest.py`, `recovery_validation.py`, `recovery_retention.py`, `recovery_crypto.py`, `recovery_key_custody.py` | not a capability itself (server-side store) | `C7`'s manifest table (future) plus `C1` §6.3's key-custody boundary (`key_id`/`wrapped_dek` columns) |
| 10 | `utils/restore_readiness.py`, `failover_readiness_ui.py`, RB.5 harvest | `cp_recovery_readiness_projection` | a new `C1`-successor readiness table — **open item**: whether this folds into row 6's readiness table or stays separate is left to that row's own future Extract movement (§7) |
| 11 | `utils/compliance_check_engine.py`, `compliance_*.py` (CE.2) | `compliance_check_rule_pack` (one umbrella capability, or one per rule pack — **open item**, §7) | a new `C1`-successor compliance projection table; `validation_status: UNPROVEN` inherited (workflow, movement 0037 ran out of budget) |
| 12 | `utils/device_registry.py`, `discovery_lifecycle.py`, `first_contact_producer.py`, `pre_enrollment_identity_probe.py`, `enrollment_audit.py` | `device_enrollment_and_identity` | `C1`'s own `devices`/`endpoints`/`credential_references` tables directly (already sketched, `B1-4b` owner) — **open item**: whether this capability runs through the same gate-resolution machinery as a read/write capability, or through `B1-4b`'s separate manual-registration flow outside the gate chain, is unresolved and named for the PO (§7) |
| 15 | `utils/event_signal_intake.py`, `signal_intake/` | `http_in_signal_intake` (B2 scope) | a new `C1`-successor intake table; gate reference `NOT_APPLICABLE` (HTTP-in, not a device command) |

**Not queue items (workflow §4's own ruling, carried forward unchanged, no
target-naming requirement applies because these were never capability
extraction candidates):**

- `utils/capability_registry.py`, `capability_state_resolver.py`,
  `capability_applicability.py`, `capability_vendor_support.py` — the `D1`–
  `D7` presentation resolver, ported as rules by a later movement, not
  extracted as a capability (`C4` §1.2's naming-collision note).
- `utils/action_taxonomy.py` — ported verbatim as an enumeration; it *feeds*
  every row's `action_class` field, it is not itself a queue row.
- `utils/support_bundle.py::Tokenizer`, `replay/` — the extraction **tool**
  this document's §3 specifies the use of; not a capability to extract.
- `console/`, `templates/`, `static/`, `utils/html_export.py`, `*_ui.py` —
  explicitly **not extracted** (static-page concerns; UI 2.0 designs its own
  screens against the same evidence).

---

## 6. Acceptance criteria for `B1-5` and the extraction-tooling movement

At least ten, each independently testable:

1. **Produces-facts pre-check is enforced, not advisory.** Given a target
   `fact_id` already present in another committed spec's `produces_facts[]`,
   a spec that re-derives that same `fact_id` via its own steps (instead of
   `consumes_facts` + zero re-derivation steps) fails a spec-lint check
   (`DUPLICATE_FACT_PRODUCER`) at commit/PR time — tested with a seeded
   producer/consumer spec pair, one compliant and one not.
2. **Every `C4` §2.2 field is present with an explicit state.** A spec
   missing any field, or carrying an implicit/omitted state, fails a
   spec-schema validator; the §4 worked example passes it, a deliberately
   incomplete fixture spec fails it.
3. **Every `UNKNOWN` field carries a non-empty `validation_plan_ref`.** The
   validator rejects an `UNKNOWN` field whose `validation_plan_ref` is empty
   or missing.
4. **`NOT_APPLICABLE` is never a stand-in for an unproven `UNKNOWN`.** Every
   `NOT_APPLICABLE` field carries a non-empty `state_rationale`; a validator
   flags a spec that reuses identical rationale text across an `UNKNOWN` and
   a `NOT_APPLICABLE` field on the same capability as a likely
   lazily-flipped state, requiring human review before it passes.
5. **Gate-reference naming discipline.** Every `exec`/`xml_api_call` step's
   `gate_reference` is either the literal `NOT_APPLICABLE` (matching `C4`
   §2.3's applicability column for that step kind) or the literal
   `UNKNOWN: requires gate entry` — never a free-text guess at a `gate_id`
   that does not exist in the current `gate_registry` fixture/migration set;
   a validator cross-checks every non-`NOT_APPLICABLE` reference against that
   set and rejects an invented id.
6. **Fixture DLP gate is mandatory and blocking.** A seeded fixture
   directory containing one file with an untokenized real-looking hostname
   or serial fails `--repository-privacy-check` with non-zero exit; the
   extraction movement's commit is refused on that failure exactly as for
   any other file.
7. **`SYNTHETIC`/`DERIVED` fixtures are marked, never silently unmarked.** A
   fixture missing `fixture_kind`, or `DERIVED` without `derived_from`, fails
   fixture-set validation; a correctly annotated `SYNTHETIC` and `DERIVED`
   pair both pass.
8. **Cross-output identity equality is provable, not assumed.** Two fixture
   files sharing one `capture_session_id` have byte-identical tokenized
   device-identity fields; a deliberately mismatched pair (different
   underlying tokenizer key/session) fails the same assertion.
9. **Extraction-tooling movement: the tokenizer invocation runs fully
   offline.** Running the extraction-tooling script against a seeded capture
   directory, under a network-egress-denying test harness, still completes
   and emits a valid fixture set — proving no live device contact or network
   access is required to run it (workflow §4's own "P-1 is not violated"
   note, tested rather than merely asserted).
10. **Extraction-tooling movement: the DLP gate is wired into the tool's own
    exit code.** The tool's own process exits non-zero whenever the fixtures
    it just wrote fail `--repository-privacy-check`, run automatically as
    its own last step — tested by seeding one unsanitized value into the
    tool's input capture directory and asserting the tool's own run fails,
    not merely a later, separate CI stage.
11. **The ordered queue is honored, not reshuffled ad hoc.** An extraction
    movement claims exactly the next `not_started` row of §5's queue, in
    order, unless a named, PO-recorded exception applies — checked as a
    review-gate assertion against `project/backlog.json`'s own row ordering
    at PR time.
12. **`FIRST-CAPABILITY`'s channel-drain boundary is explicitly recorded.**
    `B1-5`'s own emitted spec for `cp_gaia_inventory_show_version_ha_state`
    carries the field-6b state/`validation_plan_ref` pairing §4.1 fixes (not
    silently resolved to `KNOWN`/`NOT_APPLICABLE` without the named PO
    validation session artifact) — a spec-schema check specific to this
    `capability_id` asserts the field's presence and its exact pairing.

---

## 7. Contradictions and open items for the Product Owner

**Contradictions with frozen authority: none found.** Documents checked:
`AGENTS.md`, `docs/design/UI2_0_BASELINE_CONTRACT.md`,
`docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md`,
`docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`,
`docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md`,
`docs/design/PRIVATE_REPLAY_ARCHITECTURE.md`,
`docs/design/BACKUP_RECOVERY_CONTRACTS.md`, `docs/AI_DEVELOPMENT_PROTOCOL.md`,
`utils/action_taxonomy.py`. This document reopens no PO-reserved decision:
`FIRST-CAPABILITY`'s narrow, non-VSX, non-cluster-specific scope is
implemented as ruled (§4.1's `virtual_system_ref: NOT_APPLICABLE`),
`RUNTIME-DIRECTION` is honored (Line-1 files are read, never imported, no
`ui2/` source written), and `FREEZE-SLICING` is honored (this document
freezes together with `C4`, not standalone). **This document performs no
actual extraction of a real capability**: §4's worked example is illustrative
prose inside this contract, not a spec file, fixture file, or Flyway
migration.

**Resolved as a mechanism, not as a fixed value — `C4` §8 open item 4:** `C4`
left the shared-fact freshness rule to this document. §2.2 step 2 fixes the
*mechanism* (every `consumes_facts[]` entry names its own `freshness_window`);
the concrete duration for any given capability pair remains that capability's
own engineering call, made when its own Extract movement runs — not fixed
here, and not a PO ruling to make in the abstract.

**Open items, not contradictions:**

1. **The tokenizer's default `Tokenizer.token()` hex-token shape does not by
   itself preserve a real identifier's original length/character class**
   (the "serial length/shape" preserved property, §3.3). This document
   requires the extraction-tooling movement (`ui2_b0_extraction_tooling`) to
   add a shape-preserving encoding on top of the existing primitive; it is
   Line-1 tool code, out of this document's own scope (docs-only, no Line-1
   code change), so it is named here as that movement's own design item, not
   decided.
2. **The exact `gate_id` slugs and their ten-field documentation for
   `FIRST-CAPABILITY`'s two commands are not minted here** (§1.2, by design —
   this document never authors a gate entry). A gate-authoring step,
   preceding or inside `B1-5`, must produce
   `docs/AI_DEVELOPMENT_PROTOCOL.md`-compliant entries before those steps can
   resolve `KNOWN`.
3. **Row 3's (`cp_gaia_configuration_snapshot`) `ssh_interactive` evidence
   bar is not yet cleared.** `C4` §5.2 names this row as Line-1's own
   candidate for the interactive-PTY-SSH-handshake evidence class; whether
   its own future Extract movement actually clears that bar is not decided
   or pre-judged here.
4. **Row 10 vs. row 6 table consolidation** (recovery readiness projection
   vs. HA readiness projection) — whether these are one table or two is left
   to whichever of the two rows' Extract movements runs first, not fixed by
   this document.
5. **Row 11's capability granularity** (one umbrella `compliance_check_
   rule_pack` capability vs. one capability per rule pack) is left open for
   that row's own future Extract movement to decide against the actual CE.2
   rule-pack boundaries, not decided here.
6. **Row 12's gate-chain question** — whether device enrollment/discovery
   runs through this document's/`C4`'s gate-resolution machinery at all, or
   is entirely `B1-4b`'s own manual-registration flow outside it — is
   unresolved and named for the PO or a later `C4`-/`C6`-successor
   clarification, not decided here.

---

## 8. Cross-references

- `docs/design/UI2_0_BASELINE_CONTRACT.md` — binding direction and Phase 0
  decisions (`RUNTIME-DIRECTION`, `FREEZE-SLICING`, `FIRST-CAPABILITY`,
  acceptance sentences A-1…A-3).
- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §3.1 (the nine-field spec this
  document sharpens), §3.3 (capability as semantic unit), §3.4 (device-contact
  coordination), §3.5 (provenance after `discard_raw`), §4 (the extraction
  inventory this document corrects).
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` §2
  (the registry schema this document's spec template fills field by field),
  §2.5 (shared-fact worked example reused in §2.2/§4), §3 (gate-registry
  resolution, referenced when naming a gate reference), §4 (VSX/ClusterXL
  target model, referenced in §4's worked example and §5's queue), §5
  (transport decision and evidence bar, referenced in §2.2/§4), §6 (`C2`
  step-interface population, cross-referenced rather than duplicated in
  §4.3), §8 open item 4 (the shared-fact freshness rule closed as a mechanism
  in §2.2 step 2).
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §3.4
  (`cp_inventory_projection`), §5 (`provenance_records`) — the tables §4's
  worked example populates by reference.
- `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md` — the tokenizer path and its
  stated limits ("a useful primitive, not a complete export policy"), cited
  in §3.1.
- `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.3/§7.4/§7.7/§7.8 — the real
  `RB.3b` command tuple and gate records, reused by reference via `C4` §2.4/§6
  in §4.3 and named as row 8's target in §5.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` "Network-device command gate" — the
  ten-field documentation requirement a gate entry must satisfy before a
  step named `UNKNOWN: requires gate entry` in this document's worked example
  (§4) can resolve `KNOWN`; authorship is out of this document's scope (§1.2).
- `AGENTS.md` — identity law, raw-evidence law, UNKNOWN/fail-closed law,
  privacy/DLP ("follow... the local repository privacy gate", cited in §3.5).
- `utils/action_taxonomy.py` — the five action classes, referenced for
  field 1's `action_class`.
- `utils/support_bundle.py::Tokenizer`, `utils/repository_privacy.py`,
  `application/workflows/maintenance.py::repository_privacy_check()` — the
  exact fixture-generation and DLP-gate entry points named in §3.
