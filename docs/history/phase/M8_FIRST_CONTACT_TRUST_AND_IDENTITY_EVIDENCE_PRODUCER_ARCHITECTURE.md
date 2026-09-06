# `M8` — First-contact trust and identity-evidence producer (bounded contract)

## Status

**DRAFT — ARCHITECTURE, IN REVIEW. NOT FROZEN. NOT IMPLEMENTED.** Produced by
a bounded Claude-side `nexus-decision-council` synthesis
(2026-09-06), scoped only to the questions below. Authorizes no code, no
schema migration, no device contact, no Device Registry relationship write.
Only `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §21 (Device
Registry, FROZEN) and `docs/history/phase/M4_LOCAL_CONTROL_PLANE_METADATA_STORE.md`
(control-plane SQLite store, IMPLEMENTED) are cited as parent authority; this
document amends neither and is subordinate to both per `AGENTS.md` "Authority
hierarchy."

| | |
| --- | --- |
| **Movement** | `ARCHITECTURE`, serving roadmap `now_next` review row `m6_next_movement_po_sequencing_review` |
| **Baseline** | `main` at `0a9048ceeb2a318444f918e2688b126641eaeab0`, verified equal to `origin/main` before this session began |
| **Parent contracts** | `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §5 (identity layering), §6 (Device Registry), §21 (`PCP.1`, FROZEN); `docs/history/phase/M4_LOCAL_CONTROL_PLANE_METADATA_STORE.md` (control-plane SQLite store, IMPLEMENTED); `project/roadmap.json` decisions `pcp_local_control_plane_storage` (DECIDED, Option A), `pcp_first_contact_trust_policy` (DECIDED — names `M8` as the trust-establishment movement over `utils/cp_ssh_trust.py`/`utils/pan_tls_trust.py`, no new credential/network path) |
| **Preserves unchanged** | `PCP.1` Device Registry frozen field set and `relationships: []` structural invariant; `M6` fail-closed admission shell (`console/registry_targets.py`); `M4` schema versions 1's existing five tables; the `CON.2` job lifecycle vocabulary; `utils/action_taxonomy.py`; one Console, one registry, one identity authority |
| **Council seats invoked** | Product/PO intent steward, Security/trust-boundary steward, Evidence/identity-law steward, Storage/ownership steward, Implementation-sequencing steward. Bounded to the ten questions below only — the closed `M6` Option D debate was not reopened. |
| **Transcript disposition** | Raw multi-seat transcript discarded per instruction. Only this synthesis and the one recorded dissent are durable. |

---

## 1. Problem and dependency statement

`M6` (`registry_keyed_job_targets`, `AUTOMATED_VALIDATED`) built a fail-closed
admission shell: `console/registry_targets.py::resolve_registry_targets()`
checks a console-submitted `config_refresh_cp` `device_id` against the
`PCP.1` Device Registry's own `device_id`/`state` fields only, and every
otherwise-eligible target unconditionally refuses with
`IDENTITY_TRANSLATION_REQUIRED` — because **no legitimate producer of a
`device_id` → collector `entity_id` relationship exists**. `entity_id`
(`utils/restore_readiness.resolve_entity_id`, mirroring
`configuration/checkpoint_config_collector.py::_entity_id`) is a distinct
identity space (§5 of the parent contract): it is `target.device`, a value
originating from Check Point management-plane (CMA/MDS) discovery, keyed to
a `PhysicalTarget` the existing collector enumerates on every `cp-config`
run — never from the registry, never console-submitted.

The PO decision closing `M6` (2026-09-06) withheld automatic `M7`
authorization and required this producer's design, and the `M7`/`M8`
sequencing, to return to PO review before either proceeds. This document is
that review's architecture output.

**Dependency correction confirmed by this session:** `M7` (real
device-targeted Collect now) cannot be built on top of `M6` as-is, because
`M6`'s admission shell has nothing to resolve `device_id` targets against.
`M8` (this document) is the actual prerequisite. See §13 for the corrected
order.

---

## 2. Authority / ownership model

Four identity spaces already exist and stay **related, never unified**
(parent contract §5): `device_id` (registry, opaque), `canonical_id`
(discovery lifecycle), `entity_id` (evidence/collector), `operational_entity_id`
(topology/HA). `M8` adds **no fifth identity authority** and **no second
producer of any existing one**:

- **`device_id`** stays sole property of `utils/device_registry.py` —
  opaque, enrollment-time generated, never derived from an endpoint,
  hostname or serial (`AC-1a`). `M8` never generates, mutates, or infers a
  `device_id`.
- **`entity_id`** stays sole property of the existing Check Point config
  collector's already-frozen derivation
  (`configuration/checkpoint_config_collector.py::_entity_id`,
  `target.device` sourced from CMA/MDS management-plane discovery). `M8`
  never invents a competing `entity_id` derivation, a synthetic target name,
  or a shortcut that bypasses management-plane discovery.
- **The relationship** `(device_id, entity_id)` is a **new, narrow, third
  object** — not a merge of the two identity spaces and not a promotion of
  either into the other. It records that a specific, evidence-backed run
  bound them; it is not itself an identity.
- **Storage owner**: the already-approved `M4` local control-plane SQLite
  store (`control_plane.db`), under the already-DECIDED
  `pcp_local_control_plane_storage` Option A scope ("job definitions...
  job/run lifecycle records, schedules, capability projections,
  idempotency/submission metadata, **control-plane runtime metadata**").
  This decision already authorizes exactly this category of row; see §4 for
  why this is an *additive migration inside already-decided scope*, not a
  new storage-engine decision and not a `PCP.1` frozen-contract amendment.
- **Transport**: the existing, already-validated
  `utils/cp_ssh_trust.py` strict host-key preflight and the existing
  `configuration/checkpoint_config_collector.py` interactive Gaia/Clish
  session mechanics. `M8` opens **no new credential path, no new network
  seam, no new device command** — consistent with `project/roadmap.json`
  `pcp_first_contact_trust_policy`, which already names `M8` as exactly this
  scope.

---

## 3. Q&A synthesis (council-closed questions)

### Q1 — What exact positive evidence may bind an opaque `device_id` to the collector `entity_id`?

**Not accepted, individually or in combination:** endpoint/address equality,
hostname equality, display-name equality, vendor-hint equality, spelling
equality, `operator_assertion`, or the collector's own MEDIUM-confidence
"accept for collection purposes" identity gate
(`_collector_identity_gate`'s `acceptance_basis` /
`confidence` fields) treated as relationship proof. That gate answers "is it
safe to read configuration from what answered," a different and lower bar
than "is this the entity the registry means" — reusing its confidence tier
as authority would be exactly the subjective-confidence-as-authority this
council was told not to introduce.

**Accepted, and only in this order:**

1. **Candidate selection (hint, not proof).** The registry's own normalized
   endpoint for `device_id` D is used **only to select which
   management-plane-discovered candidate to test** — i.e., which
   `PhysicalTarget` (from the existing CMA/MDS enumeration) has a
   `management_ip` matching D's endpoint. This produces at most one
   candidate `entity_id` hypothesis, classified `management_discovery`
   (parent contract §6/§21's own `classification_basis` vocabulary). This
   step alone is explicitly **not** identity proof — it is exactly the
   "address equality" the PO boundaries name as insufficient, used here only
   to narrow the search space, never persisted as a proven relationship.
2. **Live, direct, identity-gated confirmation.** `M8` then drives the
   *existing, unmodified* Check Point config collector first-contact path
   (§3, Q2) against that same endpoint, under the *existing, unmodified*
   strict trust preflight (§3, Q3). This is a real, credentialed,
   read-only, single-target session — not a second identity authority, the
   same transport and identity-gate machinery `cp-config` already uses in
   production.
3. **Positive evidence = the live session's own outcome**, not a comparison
   of two pre-existing strings: the collector's identity gate reports
   `accepted = True` (an authenticated session, a successful `show
   hostname`, a successful read-only configuration capability against
   *that* connection) **and** the run captures the device's own
   directly-read `serial` (`_parse_asset_semantic(..., "serial")`) as the
   proof artifact — a vendor-issued hardware identity token read from the
   device itself, not compared against any second independent source
   (checked and confirmed absent from this repository's CMA/MDS enumeration
   path — see Q2). The relationship is proven **by the run having occurred
   and succeeded against the registry's own declared endpoint**, using the
   collector's own unmodified `entity_id` derivation for whatever target
   that live session was constructed from — not by post-hoc string
   comparison of `entity_id` to `device_id`, `endpoint` to `management_ip`,
   or hostname to hostname.
4. Because no second, independent source of Check Point serial exists in
   this repository today (§3 Q2), this evidence is **single-sourced direct
   evidence**, not bidirectionally corroborated in the `B2` sense the PAN HA
   serial work uses. That is disclosed as a limit, not concealed as
   stronger than it is (§9, real-environment validation gate).

**Explicitly not proof:** the candidate-selection step's endpoint match, by
itself, promoted directly to `first_contact_evidence` without step 2
succeeding. A registry endpoint that happens to equal a CMA-declared
`management_ip` is a hypothesis to test, never an accepted relationship.

### Q2 — Which existing validated first-contact/identity reads and transport seams can produce this evidence without inventing a second identity authority or a new collector?

- `configuration/checkpoint_config_collector.py::_entity_id` — the sole,
  unmodified `entity_id` derivation (`target.device`, or
  `f"{target.device}__vsid_{context.vs_id}"` for a VSX context).
- `configuration/checkpoint_config_collector.py::_identity_gate` /
  `_collector_identity_gate` — the existing accept/reject logic
  (authenticated + hostname read + configuration read), reused *as a
  gate*, never as a confidence-authority substitute (Q1).
- `configuration/checkpoint_config_collector.py::_parse_asset_semantic(...,
  "serial")` — the existing asset-command serial parser, already used by
  the collector for its own recovery/attestation evidence.
- `utils/cp_ssh_trust.py::apply_strict_host_key_policy` — the existing
  strict host-key preflight (Q3), already the single seam every CP SSH
  client path goes through.
- **Confirmed absent, checked, not assumed:** no CMA/MDS management-plane
  read anywhere in `checkpoint/cp_runner.py` or `checkpoint/*.py` exposes a
  device serial today (`grep` returned zero matches). This closes off a
  bidirectional-corroboration design (independent management-plane serial
  vs. direct-device serial) as **not currently available evidence** — it is
  not proposed here, and must not be assumed available in a later
  implementation movement without first proving the CMA does expose it.
- **No second collector is created.** `M8`'s producer is a bounded,
  read-only, single-target *invocation shape* of the existing collector's
  first-contact/identity-gate/serial-read code, not a parallel
  implementation of any of it.
- Palo Alto: `utils/pan_tls_trust.py` is the structurally symmetric seam
  named alongside `cp_ssh_trust.py` in `pcp_first_contact_trust_policy`, but
  `M6`'s `config_refresh_cp` scope and this document's acceptance criteria
  are Check-Point-only. A PAN producer is a distinct, later, separately
  evidenced movement (§14 non-goals) — not implied complete by this design.

### Q3 — What strict transport-trust condition must be satisfied before credentials or identity reads are attempted?

`utils/cp_ssh_trust.py::apply_strict_host_key_policy` must complete without
raising `CpSshStrictPreflightError` **before any `ssh.connect()` call**,
consistent with the already-DECIDED `pcp_first_contact_trust_policy`:
trusted host-key material must already exist for the target endpoint in the
system `known_hosts` store; `RejectPolicy` is installed; no missing-key
acceptance, no TOFU, no compatibility-mode fallback for a manually enrolled,
non-corroborated endpoint. If the preflight fails, `M8` must refuse before
any credential is read or presented — the identical fail-closed sequence
`configuration/checkpoint_config_collector.py` already enforces for
production `cp-config` collection. `M8` introduces no new trust policy,
no new credential source, and no compatibility-mode carve-out beyond what
is already DECIDED.

### Q4 — Where should the proven relationship live?

**The already-approved `M4` local control-plane SQLite store
(`control_plane.db`)**, via an additive schema migration to a **new table**
inside that already-owned database — not the Device Registry, not a new
store, not a new engine.

Ownership check performed before proposing a new table (as instructed):

- `control_plane_metadata` (`key`/`value`) is an opaque scalar
  key-value table; it cannot represent a one-to-many, multi-field,
  invalidatable relationship without becoming an ad hoc serialized blob —
  rejected, it would defeat the store's own `STRICT` typing and
  ownership-boundary test (`M4` §7).
- `capability_projections` is reserved for the **capability projection**
  object specifically (`M3` §8.2.1's two-object boundary: projection, never
  presentation resolution). An identity relationship is a third, distinct
  concern from a capability projection and must not be folded into it —
  doing so would silently widen `capability_projections`' own frozen
  column contract.
- **Conclusion:** a new table, `device_identity_relationships` (name
  illustrative, not frozen — see §14), added by an additive migration
  (schema version 2) using `M4`'s already-built migration ledger mechanism
  (`schema_migrations`, exact-prefix validation, one transaction per
  migration, `STRICT` typing, the same ownership-boundary column-scan test
  extended to cover the new table). This is **not** a new storage-engine
  decision (`pcp_storage_engine` stays open/deferred, untouched by this
  document) and **not** a `PCP.1` frozen-contract amendment (§6 below) —
  it is additive use of scope `pcp_local_control_plane_storage` already
  DECIDED to include "control-plane runtime metadata."

**Registry's own `relationships: []` field is explicitly not the target.**
`utils/device_registry.py::_validate_persisted_record` test-enforces that
field as an empty list in `PCP.1` (`AC-2a`/structural). Populating it would
require reopening the `PCP.1` §21 frozen contract's closed field set and its
own test-enforced invariant — precisely the frozen-amendment path this
session is not authorized to apply (§15 lists it instead as a *rejected*
option, not a proposed amendment, because `M4` already provides an
in-scope alternative that needs no amendment at all).

### Q5 — Minimum persisted relationship fields

| Field | Type | Notes |
| --- | --- | --- |
| `relationship_id` | PK, opaque | new identifier for the row itself, never reused for `device_id` or `entity_id` |
| `device_id` | text, `NOT NULL` | opaque `PCP.1` value; **not** a foreign key into the registry (`M4` owns none of the registry's rows — §7 of the `M4` doc) — validity is re-checked live against the registry at consumption time (Q7), never trusted from a stale join |
| `entity_id` | text, `NOT NULL` | opaque, exactly as `_entity_id` produced it — no normalization |
| `vendor_namespace` | text, `CHECK IN ('checkpoint')` at this movement's scope; extensible only by a later migration if PAN is ever added | prevents a cross-vendor identity collision from silently aliasing |
| `producing_run_ref` | text, `NOT NULL` | opaque reference to the exact first-contact run/session that produced this row — the "which invocation" provenance, not raw evidence (RaIw-evidence law: reference only, never the raw transcript) |
| `proof_type` | text, `CHECK IN ('first_contact_identity_gate_and_serial')` at this movement's scope | closed vocabulary, not a free-text confidence field (Q1's constraint) |
| `proof_source` | text, `CHECK IN ('direct_device_read')` | records evidence *grade* (direct vs. management-plane), not a *confidence* score — keeps the `AGENTS.md` evidence-grade distinction machine-readable without inventing a subjective scale |
| `observed_at_utc` | text, `NOT NULL` | when the producing run observed the evidence, not when the row was written |
| `trust_authority_generation` | integer, `NOT NULL` | which host-key trust epoch (`cp_ssh_trust` known_hosts state generation) the preflight consulted — a trust-store rotation invalidates rows bearing an older generation (Q6) |
| `identity_authority_generation` | integer, `NOT NULL` | which `entity_id`-producing collector contract generation produced this row — a later change to `_entity_id`'s derivation invalidates rows bearing an older generation (Q6), mirroring `capability_projections.producer_version` |
| `identity_mapping_proven` | integer (`0`/`1`), `NOT NULL` | binary, never a probability/confidence float — `1` only when steps in Q1 completed; the sole authoritative "is this usable" bit `M6`'s resolver may read |
| `state` | text, `CHECK IN ('ACTIVE', 'INVALIDATED', 'SUPERSEDED')` | see Q6 |
| `invalidation_reason` | text, nullable | populated only when `state != 'ACTIVE'`; closed vocabulary drawn from Q6's list, never a raw exception or free text |
| `created_at_utc` / `updated_at_utc` | text, `NOT NULL` | standard audit pair, matching every other `M4` table |

`UNIQUE (device_id, entity_id, vendor_namespace)` where `state = 'ACTIVE'`
(a partial unique index, mirroring `M4`'s existing
`ux_job_runs_one_active_per_definition` pattern) — at most one active proven
relationship per `device_id`/`entity_id`/vendor triple; a new proof
supersedes rather than duplicates.

**No subjective confidence column anywhere in this table** — satisfies the
PO boundary directly; `identity_mapping_proven` is the only authority bit,
and it is binary by construction, not derived from the collector's own
`confidence`/`acceptance_basis` strings (those may be logged in
`producing_run_ref`'s target evidence for audit, never copied into this
table as if they were proof).

### Q6 — When must a relationship become unusable?

`state` transitions `ACTIVE → INVALIDATED` (terminal for that row; a fresh
proof creates a new row, never resurrects an old one) on any of:

- **Registry disable/retire.** `device_id`'s registry state leaves
  `ENROLLED_UNVERIFIED` (`DISABLED`, `RETIRED`, or any state `M6`'s own
  `_ELIGIBLE_STATES` would refuse). Detected at consumption time (Q7), not
  by a background sweep — consistent with "re-read every call, no cache."
- **Ambiguity.** A later first-contact run against the same `device_id`
  endpoint yields a *different* `entity_id` than an existing `ACTIVE` row.
  Both rows move to `INVALIDATED` with reason `AMBIGUOUS_IDENTITY`; no
  code path may pick one over the other by recency, confidence, or any
  other heuristic — this is a `RELATIONSHIP_INCONSISTENT` state
  (`AGENTS.md` UNKNOWN/fail-closed law), reported, not resolved silently.
- **Contradictory evidence.** A later first-contact run at the same
  endpoint returns a serial that differs from the one an `ACTIVE` row
  recorded for the same `entity_id`. Reason `CONTRADICTORY_EVIDENCE`; both
  the old and new claims are surfaced, not auto-reconciled (mirrors the PAN
  HA serial `B2` posture of reporting the conflict rather than resolving
  it).
- **Changed authoritative identity.** The registry's own endpoint for
  `device_id` changes (an operator edits/re-enrolls). Any row keyed to the
  old endpoint's proof is invalidated with reason
  `REGISTRY_ENDPOINT_CHANGED` — the relationship was proven for a specific
  endpoint, not for the `device_id` label in perpetuity.
- **Changed trust authority.** `trust_authority_generation` on the row is
  older than the current one (a `known_hosts`/trust-store rotation).
  Reason `TRUST_AUTHORITY_ROTATED` — a relationship proven under a
  since-revoked or since-replaced host-key trust epoch must not be reused
  under a new one without re-proof.
- **Changed identity authority.** `identity_authority_generation` on the
  row is older than the current one (`_entity_id`'s own derivation
  contract changed). Reason `IDENTITY_AUTHORITY_CHANGED` — mirrors
  `capability_projections.producer_version`/`authority_generations`
  exactly (`M4` §5).
- **Corrupt state.** The row fails its own `STRICT`/`CHECK` constraints on
  read, or the table/database itself is unreadable
  (`ControlPlaneCorruptionError`/`ControlPlaneSchemaVersionError`). Treated
  identically to `M6`'s `DEVICE_REGISTRY_UNAVAILABLE` posture: fail closed,
  sanitized detail, never misreported as "no relationship exists" — a
  distinct `RELATIONSHIP_STORE_UNAVAILABLE` outcome so a storage fault is
  never conflated with "not yet proven."
- **Missing evidence provenance.** A row whose `producing_run_ref` cannot
  be resolved/does not exist is treated as unusable — the same
  provenance-required posture the Raw-evidence law implies: a relationship
  is only as good as the run that can still be pointed to, even though the
  raw session output itself is never retained (only the reference and the
  minimal fields above are).

`SUPERSEDED` is reserved for the specific case of a fresh, successful proof
for the *same* `device_id`/`entity_id`/`vendor_namespace` triple replacing
an existing `ACTIVE` row (not an error condition — normal re-proof, e.g.
after a trust/identity-authority rotation is deliberately re-run).

### Q7 — How does `M6` resolve the relationship at admission and re-check it before execution?

Exactly the pattern `M6` already established for the registry itself,
extended by one more read, never a copy:

1. `console/registry_targets.py::resolve_registry_targets()` keeps its
   existing registry-only checks (`UNKNOWN_DEVICE_ID` /
   `DEVICE_NOT_ELIGIBLE`) unchanged, first.
2. For a `device_id` that is known and eligible, a **new**, equally
   fail-closed lookup against the `M4` relationship table (read-only,
   re-read from disk on every call — no caching between admission and the
   pre-execution re-check, exactly like the registry lookup) replaces the
   current unconditional `IDENTITY_TRANSLATION_REQUIRED` refusal:
   - no `ACTIVE` row for that `device_id` → still
     `IDENTITY_TRANSLATION_REQUIRED` (nothing regresses; this is the exact
     current behavior when no relationship has been produced yet);
   - relationship-store unavailable/corrupt →
     `RELATIONSHIP_STORE_UNAVAILABLE` (distinct reason, sanitized detail,
     mirroring `DEVICE_REGISTRY_UNAVAILABLE`'s correction-round-1 pattern —
     never conflated with "not proven");
   - exactly one `ACTIVE` row → its `entity_id` is returned as the resolved
     collector target, **and** the registry's own eligibility check from
     step 1 is re-confirmed live at that same moment (no result is cached
     from admission into execution);
   - more than one `ACTIVE` row (should be structurally prevented by the
     partial unique index, but checked defensively) → fail closed, never a
     first-match/most-recent heuristic.
3. `console/runner.py`'s pre-execution re-check calls the same function
   again, immediately before `main.main()`, so a relationship invalidated
   between admission and execution (registry disabled, trust/identity
   authority rotated, contradiction discovered) refuses there too — the
   identical defense-in-depth shape `M6` already uses for the registry
   check and for the action-class refusal.
4. **No endpoint, hostname, or identity fallback authority is ever copied
   into `console/`.** The console-side function only ever asks the `M4`
   store "what active, non-invalidated relationship exists," never
   recomputes, re-derives, or second-guesses the relationship using any of
   the evidence classes `M6`/`M8` already forbid at that boundary.

### Q8 — Does `M8` need to be split into small implementation movements?

Yes. The smallest dependency-preserving sequence (names below are
illustrative slice labels for PO sequencing discussion — **not adopted as
frozen movement IDs**; a later contract review names them for real against
the repository state at that time):

1. **Storage/contract slice** — the `M4` additive migration (schema version
   2, the new relationship table from Q5, its `STRICT`/`CHECK`/unique-index
   definitions, its ownership-boundary test extension) and the closed
   vocabularies from Q5/Q6, with **no producer and no consumer wired up
   yet** — pure schema + typed read/write API on the store, unit-tested in
   isolation exactly as `M4`'s own tables were.
2. **Read-only first-contact producer slice** — a bounded, single-target,
   CLI-invoked (never console-submitted, never scheduled) command that
   performs Q1's candidate-selection + live-confirmation sequence for one
   `device_id` and writes exactly one row via slice 1's API. No admission
   change, no `console/` change. This is the slice that needs real-device
   validation (§9) before being trusted as evidence of anything.
3. **`M6` resolver-consumption slice** — the `console/registry_targets.py`
   extension in Q7, wired to slice 1's read API only (never writing). This
   is the slice that finally lets an eligible `device_id` resolve to
   something other than `IDENTITY_TRANSLATION_REQUIRED`, and is the
   earliest point at which `M7`-shaped work becomes meaningful.
4. **`M7` — real device-targeted Collect now**, unblocked only once slice 3
   exists and has at least one real-environment-proven relationship row to
   resolve against (§9, §13).

Each slice is independently testable and independently revertable; none
requires the next to exist to be validated on its own terms — the same
incremental-and-rollback-friendly posture `AGENTS.md` "Engineering laws"
already requires.

### Q9 — Real-environment evidence required before the mapping producer may be considered proven

Automated/fixture tests can prove the *code path* (candidate selection,
transport-trust gating, table read/write, invalidation transitions) but
never the *vendor claim* that a live, trust-verified, identity-gate-accepted
session against a real Check Point endpoint actually yields a stable,
correct `entity_id`/serial pairing repeatable across at least:

- one real enrolled endpoint, contacted twice (separate runs, same
  device) — confirming stability, not merely single-shot success;
- one real endpoint where the CMA-declared `management_ip` candidate and
  the directly-read device disagree in some observable way (a deliberately
  misconfigured/relabeled test case, if available) — confirming the design
  actually refuses/flags `CONTRADICTORY_EVIDENCE`/`AMBIGUOUS_IDENTITY`
  rather than silently accepting whatever answered;
- confirmation, against real CMA/MDS API output (not assumed from source
  reading alone), that no serial or other independent hardware-identity
  field is in fact exposed by management-plane discovery today (Q2) — if
  real evidence contradicts that source-reading conclusion, the whole `B2`-
  style bidirectional-corroboration option must be reopened, not folded in
  silently.

Per `docs/reference/REAL_ENV_VALIDATION_PROTOCOL.md` and `AI_START_HERE.md`
"Real-environment procedure": this session proposes no live command and
performs no device contact — that gate belongs to the implementation
movement(s) in §13's slice 2, each proposing exactly one bounded, read-only
validation command with an explicit target scope for the controlled
environment/human to execute.

### Q10 — Corrected `M8`-before-`M7` roadmap order

**Confirmed.** `M6 → M8 (this document's slices, §13) → M7 → remaining
enrollment/capability movements` is the only viable order: `M7`'s "real
device-targeted Collect now" has nothing legitimate to target until a
producer exists, and `M6`'s admission shell was deliberately built to
refuse rather than fabricate one. No stronger alternative was found in
council: reordering `M7` ahead of `M8` would force `M7` to either invent its
own identity evidence (a second, competing producer — rejected by §2) or
fall back to `operator_assertion` (an open dissent, never accepted mapping
authority per the `M6` PO decision).

---

## 4. Trust-before-credential sequence (composite, cites existing seams only)

```
1. Registry lookup (existing, M6-unchanged): device_id known + ENROLLED_UNVERIFIED?
      no  -> UNKNOWN_DEVICE_ID / DEVICE_NOT_ELIGIBLE (unchanged)
      yes -> continue, using the registry's own normalized endpoint

2. Candidate selection (Q1 step 1): CMA/MDS-discovered PhysicalTarget whose
   management_ip equals the registry endpoint -> at most one entity_id
   hypothesis. None found -> IDENTITY_TRANSLATION_REQUIRED (unchanged
   current behavior). This step is a hypothesis, never persisted as proof.

3. Strict transport-trust preflight (Q3, existing, unchanged):
   utils.cp_ssh_trust.apply_strict_host_key_policy against that candidate's
   endpoint. Fails -> refuse before any credential is read; nothing in
   steps 4+ ever runs.

4. Credential resolution (existing DEV.2.1/DEV.2.2 non-interactive sources
   only, via the registry's own credential_ref, resolved in-process --
   never persisted, never logged) -- only after step 3 succeeds.

5. Live first-contact session (Q2, existing, unchanged): the collector's
   own _collect_host / _identity_gate / _collector_identity_gate /
   _parse_asset_semantic(..., "serial") sequence, run for exactly the one
   candidate target from step 2.

6. Positive-evidence decision (Q1 step 3): identity gate accepted AND a
   serial was read -> proceed to step 7. Otherwise -> no row written;
   IDENTITY_TRANSLATION_REQUIRED stands (this run produced no evidence, not
   a false negative to retry automatically).

7. Relationship write (Q4/Q5), single-writer, transactional, into the new
   M4 table -- never into the Device Registry.
```

No step is reordered, skipped, or short-circuited for convenience; step 3
strictly precedes step 4 for every endpoint, including one a management-
plane candidate already named — matching `pcp_first_contact_trust_policy`'s
explicit "including management-plane candidates" clause.

---

## 5. Creation, consumption and invalidation rules (summary)

- **Creation**: only by the slice-2 producer (§13), only after §4's full
  sequence succeeds, one row per successful run, never speculative, never
  batch-inferred from existing `unified.json`/collection output (mirrors
  `M6`'s own "never consults collection output" boundary).
- **Consumption**: read-only, by `M6`'s resolver only (Q7); re-read on every
  admission and every pre-execution re-check; never cached; never consulted
  by anything outside the `console/registry_targets.py` boundary that
  already exists.
- **Invalidation**: transactional `state` transition only (§Q6); never a
  physical delete (auditability — an `INVALIDATED`/`SUPERSEDED` row stays
  queryable for history, exactly as `job_runs` rows are never deleted).

---

## 6. Explicit failure/refusal vocabulary

New, in addition to `M6`'s existing four:

| Reason | Meaning |
| --- | --- |
| `RELATIONSHIP_STORE_UNAVAILABLE` | the `M4` relationship table/database itself is unreadable, corrupt, or on an unsupported schema version — a storage fault, never conflated with "not yet proven" |
| `AMBIGUOUS_IDENTITY` | more than one distinct `entity_id` has been proven for the same `device_id` across separate runs — refuse, never pick one |
| `CONTRADICTORY_EVIDENCE` | a later run's serial disagrees with an earlier `ACTIVE` row's serial for the same `entity_id` — refuse, surface both |
| `IDENTITY_TRANSLATION_REQUIRED` | unchanged from `M6`: known/eligible `device_id`, no `ACTIVE` relationship row exists yet |

None of these is ever paired with a raw exception, filesystem path,
endpoint, hostname, serial, or credential — matching `M6` correction round
1's sanitized-detail precedent exactly.

---

## 7. Privacy / support-bundle classification

- `device_id`, `entity_id`, `vendor_namespace`, `proof_type`, `proof_source`,
  the two authority-generation integers, `identity_mapping_proven`, `state`,
  and `invalidation_reason` are the only values ever leaving the store for
  display/logging — all already-opaque or closed-vocabulary values, none
  raw evidence.
- The device serial captured during first contact (Q1/Q5) is **not** a
  persisted column — only its having-been-read (via `proof_type`) is
  recorded; the raw value stays in-memory for the duration of the producing
  run only (Raw-evidence law), then discarded, matching `show configuration`
  handling elsewhere in this repository.
- `producing_run_ref` is an opaque reference, not a transcript.
- The new table lives inside `control_plane.db`, already `DATABASE_ARTIFACT`
  classified by `utils/repository_privacy.py` and already `.gitignore`d;
  already structurally excluded from `support_bundle.py` (§7 of the `M4`
  doc: enumeration is scoped to `data_root/runs/<run_id>` plus nine named
  payloads — `data/state/` is out of scope by construction, no new
  exclusion rule needed).
- No new field carries an endpoint, hostname, credential, secret, or raw
  configuration — to be proven the same way `M4`'s own ownership-boundary
  test proves it (a column-name scan over the real schema), not by a
  hand-maintained list.

---

## 8. Concurrency / idempotency / crash-consistency expectations

- **Concurrency**: single-writer via `M4`'s existing `busy_timeout`/`WAL`
  contention posture — a concurrent producer run against the same
  `device_id` fails closed on contention (`ControlPlaneContentionError`),
  never queues or retries silently, matching the registry's own "no wait,
  retry, or queue" lock posture (`AC-13`).
- **Idempotency**: re-running the producer for a `device_id`/`entity_id`
  pair that already has an identical `ACTIVE` proof is a no-op at the
  storage layer (matching values, no duplicate row) — a *materially new*
  proof (different serial, different generation) creates a new row and
  supersedes the old one (§Q6), never silently overwrites it in place (loss
  of audit trail would violate "do not silently rewrite historical
  outcomes," `AGENTS.md` "Project-state update rule").
- **Crash consistency**: inherited directly from `M4`'s already-proven
  `synchronous=FULL`/`WAL`/transactional-migration guarantees — no new
  durability mechanism invented; a crash mid-write leaves either the prior
  `ACTIVE` row or nothing, never a half-written row (the same guarantee
  `job_runs` already relies on for `CON.0` §7.9's durability-before-start
  rule).

---

## 9. Focused acceptance criteria (draft, for the eventual frozen contract)

1. No relationship row is ever created without §4's full sequence
   succeeding end-to-end in one run.
2. Step 3 (strict trust preflight) unconditionally precedes step 4
   (credential resolution) for every endpoint, with a test proving refusal
   before any credential-bearing call for a deliberately untrusted
   endpoint.
3. `identity_mapping_proven` is `1` only when set by the producer under
   rule 1; no other code path may set it.
4. `console/registry_targets.py` never reads the registry's endpoint,
   hostname, or any `M6`-forbidden field to resolve a relationship — it
   only ever queries the `M4` table's read API by `device_id`.
5. An `INVALIDATED`/`SUPERSEDED` row is never returned by the resolver as
   if `ACTIVE`.
6. Every reason in §6 is reachable by a focused test and never paired with
   raw evidence in its detail string.
7. The `M4` ownership-boundary column-scan test is extended to cover the
   new table and still passes (no endpoint/credential/secret column name).
8. `identity_mapping_proven` and `state` together are the only bits `M6`'s
   resolver reads — no confidence/acceptance_basis string crosses that
   boundary.
9. Registry disable/retire is independently re-checked at consumption time
   (Q7 step 4) even when a relationship row is otherwise `ACTIVE` — a
   disabled `device_id` refuses regardless of relationship state.

---

## 10. Real-environment validation gate

Per §9 (Q9) and `docs/reference/REAL_ENV_VALIDATION_PROTOCOL.md`: the
slice-2 implementation movement (§13) must propose exactly one bounded,
read-only validation command (single target, explicit scope) for the
controlled environment/human to run; this session performs and proposes no
device contact. The producer may not be marked `REAL_ENV_VALIDATED` from
automated/fixture tests alone (`AGENTS.md` evidence laws), and `M7` must not
begin against a relationship whose only evidence is `AUTOMATED_VALIDATED`.

---

## 11. Proposed small implementation sequence

See §13 (Q8) for the four-slice sequence and its rationale. Restated as a
single line for the roadmap: **storage/contract → read-only producer (real-
env gated) → `M6` resolver consumption → `M7`**.

---

## 12. Council consensus and unresolved dissent

**Consensus** (all five seats): the candidate-selection-then-live-
confirmation design (§Q1) is the smallest evidence-led design that avoids
both extremes — neither trusting endpoint/hostname equality alone (rejected
unanimously, it is exactly the evidence class the PO boundaries name), nor
requiring evidence this repository does not actually have available today
(bidirectional serial corroboration — confirmed absent, §Q2). All five seats
agreed the `M4` store, not the registry, is the correct owner (§Q4), and
that `identity_mapping_proven` must stay a binary bit, never a confidence
score (§Q1, §Q5).

**Unresolved dissent, carried forward, not closed here:**

- **`operator_assertion` remains an OPEN dissent** (inherited from `M6`,
  not reopened or re-litigated by this council per instruction). This
  document's design does not require or accept it; it does not resolve
  whether a future, separately-gated PO decision could ever authorize it
  for a lower-assurance use (e.g., display-only annotation, never
  targeting authority). Left exactly where `M6` left it.
- **Single-sourced evidence limit (§Q1 point 4, §Q9)**: one seat (Evidence/
  identity-law steward) recorded a standing objection that single-sourced
  direct-device evidence, however strong its grade, is a **weaker** proof
  than the `B2`-style bidirectional corroboration this repository's own PAN
  HA work aspires to, and recommended treating any `M8`-produced
  relationship as provisionally weaker evidence than a hypothetical future
  design that also corroborates via a second independent source — *if* one
  is ever confirmed to exist on the Check Point management plane. This is
  not blocking (no such second source is currently available to build
  against — confirmed, not assumed), but it is recorded so a later movement
  does not silently forget to re-open the question if CMA/MDS ever is found
  to expose an identity field usable for corroboration.
- **Vendor-namespace extensibility**: one seat asked whether
  `vendor_namespace`'s `CHECK IN ('checkpoint')` constraint should instead
  be open-ended now to avoid a second migration when PAN support is added.
  Rejected by the majority — `M6`'s own scope is Check-Point-only
  (`config_refresh_cp`), and a premature open constraint would let an
  unimplemented PAN path silently pass validation with no producer behind
  it. A future PAN movement adds its own `CHECK` value via its own
  migration, exactly as `M4`'s own migration ledger is designed to support.

---

## 13. Corrected roadmap order (restated for `SESSION CLOSE`/PO decision)

`M6` (done) → **`M8`, this document's four slices** (storage/contract →
read-only producer, real-env gated → `M6` resolver consumption) → `M7` →
remaining enrollment/capability movements. `M7` is not renamed, retimed, or
implicitly reauthorized by this document — it stays blocked until slice 3
exists and has at least one real-environment-proven relationship to target.

---

## 14. Non-goals (explicit)

- No production code change. No test change. No device contact.
- No `PCP.1` frozen-contract amendment applied or frozen in this session
  (§Q4's rejected-registry-field-write path is recorded as a rejected
  option, not a pending amendment — no amendment is actually needed given
  the `M4` alternative).
- No `M4` schema migration actually applied — this document proposes the
  shape; slice 1 (§13) implements it as its own reviewed movement.
- No Device Registry write, no enrollment change, no automatic enrollment.
- No `M7` implementation or UI affordance.
- No PAN/Palo Alto producer design — `utils/pan_tls_trust.py` symmetry is
  noted (§Q2) but not designed here; Check-Point-only scope throughout.
- No CLASS 2 mutation, no authorization/RBAC change, no new credential or
  secret storage, no new network path beyond the existing `cp-config`
  collector's own.
- No resolution of the `operator_assertion` dissent (left exactly where
  `M6` left it) and no resolution of the single-sourced-evidence dissent
  recorded in §12 — both are carried forward, not closed.
- No table/column name, migration version number, or movement ID in this
  document is frozen; all are illustrative pending a dedicated contract
  freeze review citing the real repository state at that time
  (`AGENTS.md` "Contract-status law").

---

## 15. Rejected options (recorded, not proposed as pending amendments)

| Option | Why rejected |
| --- | --- |
| Populate `PCP.1`'s `relationships: []` field on the Device Registry record | Requires reopening the frozen §21 closed-field-set contract and its own test-enforced "always `[]`" invariant; the `M4` store already provides an in-scope alternative, so no amendment is actually needed |
| Accept endpoint/`management_ip` equality alone as proof | Explicitly named insufficient by the PO boundaries; used here only as a candidate-selection hint (§Q1 step 1), never as terminal proof |
| Reuse the collector's `confidence`/`acceptance_basis` string as relationship authority | Explicitly prohibited ("do not introduce subjective confidence as authority"); replaced by the binary `identity_mapping_proven` bit |
| Bidirectional serial corroboration (management-plane + direct-device) | No independent management-plane serial source exists in this repository today (checked, §Q2) — not available evidence, not merely undesigned |
| A new, independent identity-mapping store/engine outside `M4` | `pcp_local_control_plane_storage` already decided Option A's scope covers this; a second store would fragment control-plane metadata ownership without any decided justification |
