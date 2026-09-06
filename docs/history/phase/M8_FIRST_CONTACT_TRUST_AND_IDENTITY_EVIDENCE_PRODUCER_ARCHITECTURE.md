# `M8` — First-contact trust and identity-evidence producer (bounded contract)

## Status

**DRAFT — ARCHITECTURE, IN REVIEW. NOT FROZEN. NOT IMPLEMENTED.** Produced by
a bounded Claude-side `nexus-decision-council` synthesis (2026-09-06),
corrected across two PO architecture-review rounds using direct repository
evidence only (council not re-invoked for either correction round, per
instruction). Authorizes no code, no schema migration, no device contact, no
Device Registry relationship write. Only
`docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §21 (Device Registry,
FROZEN) and `docs/history/phase/M4_LOCAL_CONTROL_PLANE_METADATA_STORE.md`
(control-plane SQLite store, IMPLEMENTED) are cited as parent authority; this
document amends neither and is subordinate to both per `AGENTS.md` "Authority
hierarchy." `GOV.SESSION.1` is out of scope and untouched by this document.

**Correction history** (this section only; the body below reflects the
current, final state directly rather than repeating each round's diff):

- **Round 1** (2026-09-06): narrowed the evidence claim to an explicit
  `mapping_scope`; corrected the trust/credential sequencing claim against
  real code; withdrew a `M4`-forbidden column name; introduced (then, in
  round 2, replaced) an HMAC-based serial fingerprint; introduced (then, in
  round 2, removed) a VSX one-to-many cardinality model; split invalidation
  into logical/read-time vs. durable/producer-written.
- **Round 2** (2026-09-06): confirmed, from
  `configuration/checkpoint_config_collector.py::_apply_cp_target_selector`,
  that `M6`/`M7` targeting only ever resolves a **physical** collector
  `entity_id` — removed the round-1 VSX cardinality model as solving a
  problem that does not exist at this boundary. Removed the
  `data/.support_hmac.key` coupling entirely — that key has no
  identity-association lifecycle (on-demand creation, environment
  override, no rotation/versioning) and must never gate target-selection
  authority; replaced with a reference to the CP config collector's own
  already-governed, already-persisted per-run evidence (which already
  captures both the serial and the host-key fingerprint). Made the
  stronger, target-specific trust guarantee **mandatory**, not an open
  question: a new, required, local-only, no-device-contact seam must exist
  and be tested before the producer may resolve any credential. Replaced
  the source-code "trust-policy contract version" idea with a real,
  live currency check — the relationship is consumable only while the
  *current* trusted host-key fingerprint for the registry's endpoint
  agrees with the fingerprint the producing evidence captured. Bound the
  relationship to the registry record's own `updated_at` revision signal
  instead of assuming an endpoint could silently change. `M6 → M8 → M7`
  and the `CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY` scope are unchanged and
  remain PO-approved.

| | |
| --- | --- |
| **Movement** | `ARCHITECTURE`, serving roadmap `now_next` review row `m6_next_movement_po_sequencing_review` |
| **Baseline** | `main` at `0a9048ceeb2a318444f918e2688b126641eaeab0`, verified equal to `origin/main` before this session began |
| **Parent contracts** | `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §5 (identity layering), §6 (Device Registry), §21 (`PCP.1`, FROZEN); `docs/history/phase/M4_LOCAL_CONTROL_PLANE_METADATA_STORE.md` (control-plane SQLite store, IMPLEMENTED); `project/roadmap.json` decisions `pcp_local_control_plane_storage` (DECIDED, Option A), `pcp_first_contact_trust_policy` (DECIDED — names `M8` as the trust-establishment movement over `utils/cp_ssh_trust.py`/`utils/pan_tls_trust.py`, no new credential/network path) |
| **Preserves unchanged** | `PCP.1` Device Registry frozen field set and `relationships: []` structural invariant; `M6` fail-closed admission shell (`console/registry_targets.py`); `M4` schema version 1's existing seven `STRICT` tables (`schema_migrations`, `control_plane_metadata`, `job_definitions`, `job_submissions`, `job_runs`, `schedules`, `capability_projections`) and its `test_schema_owns_no_forbidden_concept` column-name boundary; the `CON.2` job lifecycle vocabulary; `utils/action_taxonomy.py`; `data/.support_hmac.key`'s own support-bundle-only purpose (untouched, not reused); one Console, one registry, one identity authority |
| **Council seats invoked (original synthesis only)** | Product/PO intent steward, Security/trust-boundary steward, Evidence/identity-law steward, Storage/ownership steward, Implementation-sequencing steward. Bounded to the ten questions below only — the closed `M6` Option D debate was not reopened. |
| **Transcript disposition** | Raw multi-seat transcript discarded per instruction. Only this synthesis and the recorded dissent are durable. |

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
identity space (§5 of the parent contract): for the *physical* host it is
`target.device`, a value originating from Check Point management-plane
(CMA/MDS) discovery, keyed to a `PhysicalTarget` the existing collector
enumerates on every `cp-config` run — never from the registry, never
console-submitted.

**Targeting is physical-only — confirmed, not assumed.**
`configuration/checkpoint_config_collector.py::_apply_cp_target_selector`,
the exact function `--cp-config-targets`/`target_entity_ids` (the seam `M5`
promoted and `M6`/`M7` build on) uses to narrow candidates, indexes
candidates **only** by `_entity_id(target)` called with no `VsContext`
argument — i.e., only the physical host's `entity_id`. Selecting a physical
target causes the collector to internally collect every hosted VSX virtual
system as part of that one run; a VSX child `entity_id` is never itself an
independently selectable target. **This scopes `M8`'s entire relationship
model to physical `entity_id`s only** — a plain one-to-one association per
`device_id`, not a one-to-many VSX model (a round-1 design this document no
longer carries — see "Correction history").

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
  collector's already-frozen physical derivation
  (`configuration/checkpoint_config_collector.py::_entity_id(target)`,
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
  session mechanics, plus **one small, mandatory, local-only addition**
  (§Q3/§4): a target-specific trusted-key lookup, over the same
  `known_hosts` source `cp_ssh_trust.py` already reads — no new credential
  path, no new network seam, no new device command, no new trust
  *authority*. Consistent with `project/roadmap.json`
  `pcp_first_contact_trust_policy`, which already names `M8` as exactly
  this scope.
- **Evidence reference**: the relationship never copies the endpoint, the
  serial, or trust material into `control_plane.db`. It references the
  *existing*, already-governed CP config collector evidence for the
  producing run (`producing_run_ref`) and the registry's own existing
  revision signal (`registry_record_revision`) — no new key-management
  mechanism, no new evidence store (§Q4/§Q5).

---

## 3. Q&A synthesis (council-closed questions)

### Q1 — What exact positive evidence may bind an opaque `device_id` to the collector `entity_id`?

**Not accepted, individually or in combination:** endpoint/address equality,
hostname equality, display-name equality, vendor-hint equality, spelling
equality, `operator_assertion`, or the collector's own MEDIUM-confidence
"accept for collection purposes" identity gate
(`_collector_identity_gate`'s `acceptance_basis`/`confidence` fields) treated
as relationship proof. That gate answers "is it safe to read configuration
from what answered," a different and lower bar than "is this the entity the
registry means" — reusing its confidence tier as authority would be exactly
the subjective-confidence-as-authority this council was told not to
introduce.

**Accepted, and only in this order — and only as a *scope-limited*
association, never general identity proof:**

1. **Candidate selection (hint, not proof).** The registry's own normalized
   endpoint for `device_id` D is used **only to select which
   management-plane-discovered candidate to test** — i.e., which physical
   `PhysicalTarget` (from the existing CMA/MDS enumeration) has a
   `management_ip` matching D's endpoint. This produces at most one
   candidate physical `entity_id` hypothesis, classified `management_discovery`
   (parent contract §6/§21's own `classification_basis` vocabulary). This
   step alone is explicitly **not** identity proof — it is exactly the
   "address equality" the PO boundaries name as insufficient, used here only
   to narrow the search space, never persisted as a proven relationship.
2. **Live, direct, identity-gated confirmation, under a mandatory,
   target-specific trust gate.** `M8`'s producer drives the *existing,
   unmodified* per-host Check Point identity-gate/serial-read logic
   (§Q2) against that same endpoint — but only *after* a new,
   target-specific trusted-host-key lookup for that exact endpoint
   succeeds (§Q3/§4), a stronger precondition than the bulk collector's own
   existing sequencing. This is a real, credentialed, read-only,
   single-target session — not a second identity authority, the same
   per-host transport and identity-gate machinery `cp-config` already uses
   in production, driven by a new, minimal, single-target entry point
   rather than the bulk multi-target orchestration (§Q2/§4).
3. **What this actually proves, precisely.** The collector's identity gate
   reporting `accepted = True` (an authenticated session, a successful
   `show hostname`, a successful read-only configuration capability) and a
   directly-read `serial` together prove that **a real, live, authenticated
   Check Point device answers at the registry's declared endpoint** —
   strong, direct-device evidence of *liveness and authenticity at that
   endpoint*. They do **not** independently prove that this live device is
   the same object the CMA/MDS labels with the candidate `entity_id` from
   step 1: no repository evidence source corroborates the CMA's own
   `entity_id`↔serial (or any other CMA-side hardware-identity field)
   pairing independently of the endpoint-equality hypothesis that selected
   the candidate in the first place (confirmed absent, §Q2). Put plainly:
   the *final* `entity_id` binding still traces back to registry-endpoint
   == `management_ip` equality at its root; step 2 corroborates that the
   endpoint is live, trust-verified, and genuinely a Check Point device — it
   does not independently corroborate the CMA's own labeling of that
   endpoint. Describing this as universally proven identity would overstate
   it.
4. **Consequence: an explicit, narrow, machine-readable scope, not a
   general identity claim.** Every relationship row carries a
   `mapping_scope` (§Q5) fixed to
   `CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY` — the `utils.action_taxonomy`
   `CLASS_0_READ` sense of "class" (this document's every other use of
   "CLASS_0"/"CLASS 2" also refers to that taxonomy, never the unrelated
   `PRIVACY_AND_DATA_HANDLING.md` CLASS 0–3 data-sensitivity scheme —
   `AGENTS.md` "Privacy and DLP" names these as unrelated namespaces not to
   be conflated). Within that scope — selecting which collector `entity_id`
   a `config_refresh_cp` (a `CLASS_0_READ` job) may target — the
   corroboration in steps 1–3 is the best available, fail-closed-outside-
   its-scope association this repository can produce today. It is
   **explicitly prohibited** from being read as authority for: `CLASS_2`
   operational state change, any authorization decision, deriving or
   corroborating an `operational_entity_id`, Device Registry enrollment
   authority, or any security-identity purpose (SIC, certificate, or
   otherwise). `identity_mapping_proven = 1` means "proven for
   `mapping_scope`," never "proven as security identity," and every API/type
   surfacing the bit must require and expose `mapping_scope` alongside it —
   a bare boolean is never an acceptable read shape (§Q5).
5. Because no second, independent source of Check Point serial exists in
   this repository today (§Q2), this evidence is also **single-sourced
   direct evidence**, not bidirectionally corroborated in the `B2` sense the
   PAN HA serial work uses — a second, independent limit on top of the
   scope limit in point 4, disclosed rather than concealed (§9).

**Explicitly not proof, at any scope:** the candidate-selection step's
endpoint match, by itself, promoted directly to `first_contact_evidence`
without step 2 succeeding. A registry endpoint that happens to equal a
CMA-declared `management_ip` is a hypothesis to test, never an accepted
relationship, at any scope.

### Q2 — Which existing validated first-contact/identity reads and transport seams can produce this evidence without inventing a second identity authority or a new collector?

- `configuration/checkpoint_config_collector.py::_entity_id(target)` (no
  `VsContext` argument) — the sole, unmodified physical `entity_id`
  derivation this movement's scope uses (§1).
- `configuration/checkpoint_config_collector.py::_identity_gate` /
  `_collector_identity_gate` — the existing accept/reject logic
  (authenticated + hostname read + configuration read), reused *as a
  gate*, never as a confidence-authority substitute (Q1).
- `configuration/checkpoint_config_collector.py::_parse_asset_semantic(...,
  "serial")` and `checkpoint_config_probe.py::_host_key_fingerprint` — the
  existing asset-command serial parser and host-key fingerprint capture,
  both already produced by the existing per-host collection path
  (`_collect_host`/`_connect`) and already retained as part of that run's
  governed CP config evidence (§Q4/§Q5) — not a new read, not new evidence.
- `utils/cp_ssh_trust.py::apply_strict_host_key_policy` — the existing
  strict host-key preflight, already the single seam every CP SSH client
  path goes through — plus one small, mandatory addition this movement
  requires and must build before the producer may run (§Q3/§4): a
  target-specific trusted-key lookup over the same `known_hosts` source.
- **Confirmed absent, checked, not assumed:** no CMA/MDS management-plane
  read anywhere in `checkpoint/cp_runner.py` or `checkpoint/*.py` exposes a
  device serial today (`grep` returned zero matches). This closes off a
  bidirectional-corroboration design (independent management-plane serial
  vs. direct-device serial) as **not currently available evidence** — it is
  not proposed here, and must not be assumed available in a later
  implementation movement without first proving the CMA does expose it.
- **No second collector is created, but a new, minimal, single-target entry
  point is required.** `M8`'s producer cannot simply call the existing
  bulk entry point (`run_checkpoint_config_collection`) with a one-item
  target list: that function resolves the run's SSH credential once, for
  the whole run, **before** narrowing to any requested target and before
  any per-target trust check (confirmed at
  `checkpoint_config_collector.py:1976-1978`, executed ahead of
  `_apply_cp_target_selector` at line ~1997) — exactly the ordering §Q3/§4
  requires `M8` not to inherit. `M8`'s producer is therefore a new, minimal,
  single-target orchestration: it performs its own trust lookup (§Q3) and
  its own per-target credential resolution (reusing the same existing
  DEV.2.1/DEV.2.2 source, just invoked once for one target instead of once
  for the whole run), then calls the **same, unmodified**
  `_collect_host`/`_connect`/`_identity_gate` per-host primitives the bulk
  collector already uses for the actual connection, identity gate, and
  evidence capture. This is a new *driver*, not a new *collector* — no
  per-host transport, parsing, or identity-gate logic is reimplemented.
- Palo Alto: `utils/pan_tls_trust.py` is the structurally symmetric seam
  named alongside `cp_ssh_trust.py` in `pcp_first_contact_trust_policy`, but
  `M6`'s `config_refresh_cp` scope and this document's acceptance criteria
  are Check-Point-only. A PAN producer is a distinct, later, separately
  evidenced movement (§14 non-goals) — not implied complete by this design.

### Q3 — What strict transport-trust condition must be satisfied before credentials or identity reads are attempted?

**A target-specific trusted-key lookup is mandatory, not optional, and must
exist and be tested before `M8`'s producer may run at all.** The existing
seam alone is insufficient: `apply_strict_host_key_policy`
(`utils/cp_ssh_trust.py:158`) only confirms *some* trusted host-key material
exists anywhere in the system `known_hosts` store — it is not
target-specific, and the specific target's key is verified only later,
inside `ssh.connect()`'s own handshake, by which point the credential has
already been resolved into process memory (confirmed at
`checkpoint_config_collector.py:1976-1978`, once per run, before any
per-target check). For `M8`'s first-contact scenario — a manually-enrolled,
possibly-never-contacted endpoint — that ordering is not strong enough.

**Required sequence, in this exact order, for `M8`'s producer only:**

```
1. Target-specific trusted-key lookup (NEW, required)
     -> is there already a trusted host-key entry, in the same known_hosts
        source apply_strict_host_key_policy already reads, for the exact
        normalized endpoint/port the registry declares for this device_id?
     -> performs NO network or device contact, adds NO key, accepts NO key
        -- a pure local read of the existing trusted-key source.
     -> not found / unreadable -> refuse. Nothing below runs. No credential
        is ever resolved for this target.
2. Credential / secret resolution (existing DEV.2.1/DEV.2.2 source,
   invoked per-target by M8's new single-target driver -- see Q2)
     -> only reached after step 1 succeeds.
3. ssh.connect() with RejectPolicy (existing, unchanged)
4. Target host-key verification during the handshake (existing, unchanged
   -- redundant with step 1 for this specific connection, but harmless;
   step 1 exists precisely so a mistyped/hostile endpoint is refused
   *before* step 2, not merely before authentication succeeds inside
   step 3/4)
5. Authentication (existing, unchanged)
6. Identity reads (existing, unchanged): show hostname / asset command
```

**This closes the round-1 gap directly**: previously, "credential is not
read before trust" was an overclaim because the bulk collector resolves
credentials once, before any per-target trust check. `M8`'s producer is
now specified to never resolve a credential for a target whose endpoint has
no pre-existing trusted key — step 1 is a hard gate, not a documented
aspiration.

**Where step 1 lives.** A new function in `utils/cp_ssh_trust.py` (name
illustrative, e.g. `has_trusted_host_key_for(endpoint, port=None)`),
built over the same trusted `known_hosts` source `load_trusted_host_keys`
already reads — parsing it with `paramiko.HostKeys`'s own lookup for the
specific host string, mirroring the cardinality-count technique the
module's own docstring already documents for a different purpose. **No new
trust source, no key ever added or accepted, no network or device contact.**
This is a required implementation prerequisite for the slice-2 producer
movement (§13) — the producer must not exist or run until this seam exists
and is tested (its own focused test suite, exercised before the producer's
own tests depend on it).

No TOFU, no automatic host-key acceptance, and no compatibility-mode
carve-out for a non-corroborated endpoint are ever allowed, unchanged from
the already-DECIDED `pcp_first_contact_trust_policy`.

### Q4 — Where should the proven relationship live, and how is it kept current?

**Storage: the already-approved `M4` local control-plane SQLite store**
(`control_plane.db`), via an additive schema migration to a **new table**
inside that already-owned database — not the Device Registry, not a new
store, not a new engine, not `data/.support_hmac.key`.

Ownership check performed before proposing a new table:

- `control_plane_metadata` (`key`/`value`) is an opaque scalar key-value
  table; it cannot represent a multi-field, invalidatable relationship
  without becoming an ad hoc serialized blob — rejected.
- `capability_projections` is reserved for the **capability projection**
  object specifically (`M3` §8.2.1's two-object boundary). An identity
  relationship is a third, distinct concern and must not be folded into
  it.
- **Conclusion:** a new table, `device_identity_relationships` (name
  illustrative, not frozen — §14), added by an additive migration (schema
  version 2) using `M4`'s already-built migration ledger mechanism
  (`schema_migrations`, exact-prefix validation, one transaction per
  migration, `STRICT` typing, the ownership-boundary column-scan test
  extended to cover it). Not a new storage-engine decision
  (`pcp_storage_engine` stays open/deferred) and not a `PCP.1`
  frozen-contract amendment — additive use of scope
  `pcp_local_control_plane_storage` already DECIDED to include
  "control-plane runtime metadata." Schema version 1 has **seven** `STRICT`
  tables today (`schema_migrations`, `control_plane_metadata`,
  `job_definitions`, `job_submissions`, `job_runs`, `schedules`,
  `capability_projections`, confirmed by reading
  `utils/control_plane_store.py` directly) — this movement would add the
  eighth.

**Why not `data/.support_hmac.key` (rejected).** That key is support-bundle
infrastructure: created on demand on first use
(`utils/support_bundle.py::_get_support_key`, `secrets.token_hex(32)`),
freely overridable by an environment variable
(`FBUDDY_SUPPORT_HASH_KEY`), with no rotation policy, no versioning, and no
identity-association lifecycle of any kind. A support operation (or an
operator setting an env var for an unrelated reason) must never be able to
create, rotate, or invalidate target-selection authority as a side effect.
`serial_fingerprint`/HMAC pseudonymization (round 1's design) is withdrawn
entirely — see §Q5/§Q6 for the replacement.

**Currency, not a static snapshot.** The relationship is not a one-time
proof that stays valid forever — it must remain **consumable only while
two independent things are still true**, checked live, read-only, at both
admission and immediately before execution (§Q7):

1. **Registry currency.** The registry record for `device_id` has not
   changed since the relationship was proven. `PCP.1` has no endpoint-edit
   function today (only `enroll`/`list`/`disable` exist) — but
   `DeviceRecord.updated_at` already changes on every mutation that does
   exist (e.g. `disable()`), and would change on any future edit capability
   too. `registry_record_revision` (§Q5) captures this existing, already-
   present signal at proof time; currency holds only while it still equals
   the *current* live record's `updated_at` for that `device_id`. This
   binds the row to "the registry record as it was when proven," without
   ever copying the endpoint itself into `control_plane.db`.
2. **Trust currency.** The *current* trusted host-key fingerprint for the
   registry's declared endpoint (via Q3's new lookup seam, performed fresh
   — no network contact, a local `known_hosts` read) must agree with the
   host-key fingerprint the producing run actually captured
   (`checkpoint_config_probe.py::_host_key_fingerprint`, already retained
   as part of that run's governed CP config evidence — §Q2). The
   fingerprint itself is **never copied into `control_plane.db`** — it is
   resolved, both at proof time and at every later currency check, through
   `producing_run_ref` (§Q5), exactly as instructed: reference the captured
   evidence, do not duplicate trust material into the new table. A
   source-code "trust-policy contract version" (round 1's design) cannot
   detect a `known_hosts` change made without any code change — it is
   withdrawn in favor of this live comparison.

**Evidence-retention dependency, stated explicitly.** Both the trust-
currency check and any future serial-contradiction check depend on the
producing run's evidence remaining resolvable under this repository's
*existing* CP config evidence retention/dedup policy
(`utils/config_evidence.py`/`utils/config_storage.py`/
`utils/config_history.py` — unmodified, unextended by this document). This
architecture session did not measure how long a given run's per-entity
evidence realistically stays resolvable in practice. If `producing_run_ref`
cannot be resolved when a currency check runs, the relationship is
**unusable** (`IDENTITY_TRANSLATION_REQUIRED` — never treated as a
confirmed, still-current match); serial-based contradiction detection is
correspondingly marked **DEFERRED** (§Q6) pending the slice-2 implementation
movement confirming, against real evidence-retention behavior, that this
comparison is practically meaningful rather than assumed.

**Registry's own `relationships: []` field is explicitly not the target.**
`utils/device_registry.py::_validate_persisted_record` test-enforces that
field as an empty list in `PCP.1` (`AC-2a`/structural). Populating it would
require reopening the `PCP.1` §21 frozen contract's closed field set and its
own test-enforced invariant — the `M4` alternative needs no such amendment
(§15).

### Q5 — Minimum persisted relationship fields

| Field | Type | Notes |
| --- | --- | --- |
| `relationship_id` | PK, opaque | new identifier for the row itself, never reused for `device_id` or `entity_id` |
| `device_id` | text, `NOT NULL` | opaque `PCP.1` value; **not** a foreign key into the registry (`M4` owns none of the registry's rows) — validity is re-checked live against the registry at consumption time (Q7), never trusted from a stale join |
| `entity_id` | text, `NOT NULL` | opaque, physical only, exactly as `_entity_id(target)` (no `VsContext`) produced it — no normalization |
| `vendor_namespace` | text, `CHECK IN ('checkpoint')` at this movement's scope; extensible only by a later migration if PAN is ever added | prevents a cross-vendor identity collision from silently aliasing |
| `mapping_scope` | text, `CHECK IN ('CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY')` | the `utils.action_taxonomy` `CLASS_0_READ` sense of "class," not the unrelated `PRIVACY_AND_DATA_HANDLING.md` CLASS 0–3 scheme. Closed vocabulary; a future scope value requires its own migration and its own PO decision. Every read API exposing `identity_mapping_proven` must also expose this field in the same call — never a bare boolean (Q1 point 4) |
| `producing_run_ref` | text, `NOT NULL` | opaque reference (the CP config collection `orchestration_run_id`, or an equivalent existing run identifier) to the exact first-contact run whose **already-governed, already-persisted** CP config evidence for this `entity_id` contains the directly-observed serial and the host-key fingerprint. References existing evidence; duplicates nothing (Raw-evidence law) |
| `registry_record_revision` | text, `NOT NULL` | the registry's own `DeviceRecord.updated_at` value, captured at proof time — an opaque revision signal, never the endpoint itself. Compared live against the *current* record's `updated_at` at every resolution (Q4/Q7); a mismatch means the registry record has changed since proof |
| `proof_type` | text, `CHECK IN ('first_contact_identity_gate_and_serial')` at this movement's scope | closed vocabulary, not a free-text confidence field (Q1's constraint) |
| `proof_source` | text, `CHECK IN ('direct_device_read')` | records evidence *grade* (direct vs. management-plane), not a *confidence* score |
| `identity_derivation_contract_version` | text, `NOT NULL` | a source-code constant a human bumps when `_entity_id`'s derivation contract changes — mirrors `capability_projections.producer_version` exactly. (Trust currency is handled separately and more precisely via the live fingerprint check in Q4/§4 — no `authority_generations`-style trust-version field is needed or proposed here) |
| `identity_mapping_proven` | integer (`0`/`1`), `NOT NULL` | binary, never a probability/confidence float — `1` only when Q1's sequence completed **and understood as scoped to `mapping_scope`, never general identity proof**; retained under this name for consistency with the already-approved `capability_projections.identity_mapping_proven` precedent, on the explicit condition that every consumer requires and exposes `mapping_scope` alongside it |
| `observed_at_utc` | text, `NOT NULL` | when the producing run observed the evidence, not when the row was written |
| `state` | text, `CHECK IN ('ACTIVE', 'INVALIDATED', 'SUPERSEDED')` | durable states only — see Q6's split between this column and read-time logical invalidity |
| `invalidation_reason` | text, nullable | populated only when `state != 'ACTIVE'`; closed vocabulary drawn from Q6's list, never a raw exception or free text |
| `created_at_utc` / `updated_at_utc` | text, `NOT NULL` | standard audit pair, matching every other `M4` table |

**Removed from the round-1 design, and why:** `entity_scope`/`vs_id_slot`
(targeting is physical-only, §1 — the VSX one-to-many model solved a
problem that does not exist at this boundary); `serial_fingerprint` and any
HMAC/key-rotation machinery (§Q4 — `data/.support_hmac.key` has no
identity-association lifecycle); `trust_authority_generation` and the
`authority_generations` JSON payload's trust-policy half (§Q4 — replaced by
a live, per-endpoint fingerprint comparison, a strictly stronger and more
precise mechanism than a source-code version bump).

`UNIQUE (device_id, vendor_namespace, mapping_scope)` where
`state = 'ACTIVE'` (a partial unique index, mirroring `M4`'s existing
`ux_job_runs_one_active_per_definition` pattern) — **at most one `ACTIVE`
physical config-target association per `device_id` + `vendor_namespace` +
`mapping_scope`**, exactly as instructed. A different `entity_id` proven for
the same triple is `AMBIGUOUS_IDENTITY` (Q6) — the partial unique index
cannot by itself prevent this (a different `entity_id` value for the same
triple is not itself a uniqueness violation), so the producer must detect it
explicitly at write time.

**No subjective confidence column anywhere in this table.**
`identity_mapping_proven` is the only authority bit, binary by construction,
never derived from the collector's own `confidence`/`acceptance_basis`
strings.

### Q6 — When must a relationship become unusable?

**Only the producer (§13's slice-2 first-contact run) ever writes to this
table — never `console/`, never a background sweep, never any other
process.** A durable `state` write happens only inside the producer's own
transaction, at the moment a *new* proof attempt discovers the condition.

**A. Logical, read-time invalidity — computed live by the resolver, never a stored mutation:**

- **Registry disable/retire, or registry-record change.** `device_id`'s
  registry state leaves `ENROLLED_UNVERIFIED`, **or** the registry record's
  live `updated_at` no longer equals the row's stored
  `registry_record_revision` (Q4/Q5 — covers disable, retire, and any
  future edit capability uniformly, since both are visible through the
  same signal). Detected live at every resolution, never a stored
  mutation.
- **Trust-currency loss.** The *current* trusted host-key fingerprint for
  the registry's endpoint (via Q3's lookup seam) no longer agrees with the
  fingerprint the row's `producing_run_ref` evidence captured — including
  the case where that evidence is no longer resolvable at all (Q4's
  evidence-retention dependency). Detected live, never a stored mutation;
  a rotated/changed host key does not by itself prove anything wrong
  happened, but the relationship cannot be trusted as current until
  re-proven.
- **Identity-derivation-contract staleness.** The row's
  `identity_derivation_contract_version` no longer matches the current
  source-code constant. Computed live; a stale row is treated identically
  to no row.

None of these three ever produces a durable write. `M6`'s resolver (Q7)
folds all three into its existing "not currently usable" refusal alongside
`identity_mapping_proven`/`state == 'ACTIVE'`/`mapping_scope` checks — all
computed at read time, all re-checked on every call, matching the "re-read
every call, no cache" posture `M6` already established for the registry
itself.

**B. Durable, producer-written state transitions — written only inside the producer's own proof transaction:**

- **Ambiguity.** A *new* first-contact proof attempt for the same
  `(device_id, vendor_namespace, mapping_scope)` triple yields a *different*
  `entity_id` than an existing `ACTIVE` row. The producer, in the same
  transaction, moves both the old and the rejected new claim to
  `INVALIDATED` with reason `AMBIGUOUS_IDENTITY` — no code path may pick one
  over the other by recency or any heuristic; a `RELATIONSHIP_INCONSISTENT`
  state (`AGENTS.md` UNKNOWN/fail-closed law), reported, not resolved
  silently.
- **Contradictory evidence — DEFERRED, not built here.** A *new* proof for
  the same triple/`entity_id` whose serial disagrees with the serial the
  existing `ACTIVE` row's `producing_run_ref` evidence captured would, in
  principle, be `CONTRADICTORY_EVIDENCE`. This document does **not**
  specify how that comparison is performed today: it depends on the
  slice-2 implementation movement confirming that both the old and new
  evidence remain resolvable long enough for the comparison to be
  meaningful (Q4's evidence-retention dependency) — no new key-management
  or fingerprint-storage mechanism is invented to work around that
  uncertainty. If retention cannot support it, this comparison stays
  DEFERRED and undetected rather than fabricated; the relationship's
  assurance is narrower than a full contradiction-detection design would
  provide, and that narrowing is disclosed, not hidden (§9/§12).
- **Supersession.** A fresh, successful proof for the same triple that
  agrees with the existing `ACTIVE` row's `entity_id` — e.g. a deliberate
  re-proof after an `identity_derivation_contract_version` bump or a
  trust-currency loss — moves the old row to `SUPERSEDED` (not an error
  condition) and inserts the new row, in the same transaction.

**Corrupt state / missing provenance — fail-closed read outcomes, not row mutations:**

- A row failing its own `STRICT`/`CHECK` constraints, or the
  table/database itself being unreadable, is reported by the resolver as
  `RELATIONSHIP_STORE_UNAVAILABLE` — never misreported as "no relationship
  exists," mirroring `M6`'s `DEVICE_REGISTRY_UNAVAILABLE` precedent.
- A row whose `producing_run_ref` cannot be resolved is treated as
  logically unusable at read time (folded into §A) — never a stored
  mutation, since the row itself may still be structurally intact; only
  its provenance and the trust-currency check that depends on it are
  unverifiable.

### Q7 — How does `M6` resolve the relationship at admission and re-check it before execution?

Exactly the pattern `M6` already established for the registry itself,
extended by several more **live, read-only** checks — nothing here ever
writes to the relationship table (Q6 established the producer as the sole
writer). Performed identically at admission
(`console/app.py`'s `POST /api/jobs`) and immediately before execution
(`console/runner.py`'s pre-execution re-check), both **server-side**,
**before any credential presentation or device contact**:

1. `console/registry_targets.py::resolve_registry_targets()` keeps its
   existing registry-only checks (`UNKNOWN_DEVICE_ID` / `DEVICE_NOT_ELIGIBLE`)
   unchanged, first.
2. For a `device_id` that is known and eligible, a lookup against the `M4`
   relationship table (re-read from disk on every call) requires, all
   computed live, none stored as a consumer-side write:
   - exactly one `ACTIVE` row for `(device_id, vendor_namespace,
     mapping_scope)`;
   - `registry_record_revision` currency: the row's stored value equals the
     *current* registry record's `updated_at` (Q4/Q6.A);
   - `identity_derivation_contract_version` currency: the row's stored
     value equals the current source-code constant (Q6.A);
   - trust currency: the current trusted host-key fingerprint for the
     registry's live endpoint (via Q3's lookup seam — a local
     `known_hosts` read, no device contact) agrees with the fingerprint the
     row's `producing_run_ref` evidence captured, and that evidence is
     itself still resolvable (Q4/Q6.A).
3. Outcomes:
   - any of the above fails or is missing → `IDENTITY_TRANSLATION_REQUIRED`
     (deliberately not split into finer-grained reasons that would leak
     which specific precondition failed);
   - relationship-store unavailable/corrupt → `RELATIONSHIP_STORE_UNAVAILABLE`;
   - all checks pass → the row's `entity_id` is returned as the resolved
     collector target;
   - more than one qualifying `ACTIVE` row (should be structurally
     prevented by Q5's partial unique index, but checked defensively) →
     fail closed, never a first-match/most-recent heuristic.
4. `console/runner.py`'s pre-execution re-check calls the same function
   again, immediately before `main.main()`, re-evaluating every one of the
   above live — the identical defense-in-depth shape `M6` already uses.
5. **No endpoint, hostname, serial, trust material, or identity fallback
   authority is ever copied into `console/`, and `console/` never writes
   to the relationship table.** Whether `console/registry_targets.py` may
   import `utils/cp_ssh_trust.py`'s new lookup function directly, or must
   go through a thin vendor-neutral wrapper to preserve the existing
   "console imports no vendor/collector module" posture (`AI_START_HERE.md`
   directory map), is an open implementation-shape question left to the
   slice-3 movement (§13) — this document requires the check to happen,
   live, server-side, read-only, before credential presentation, but does
   not decide the exact import boundary.

### Q8 — Does `M8` need to be split into small implementation movements?

Yes. The smallest dependency-preserving sequence (names below are
illustrative slice labels for PO sequencing discussion — **not adopted as
frozen movement IDs**):

1. **Storage/contract slice** — the `M4` additive migration (schema version
   2, the relationship table from Q5, its `STRICT`/`CHECK`/unique-index
   definitions, its ownership-boundary test extension) and the closed
   vocabularies from Q5/Q6, with **no producer and no consumer wired up
   yet** — pure schema + typed read/write API, unit-tested in isolation.
2. **Mandatory trust-lookup seam** (Q3) — the new, target-specific
   trusted-host-key lookup in `utils/cp_ssh_trust.py`, built and tested
   **before** any producer code is written. Blocking, not optional.
3. **Read-only first-contact producer slice** — the new, minimal,
   single-target driver (Q2) built on slice 2's lookup seam and the
   existing per-host `_collect_host`/`_identity_gate` primitives, writing
   one row per successful proof via slice 1's API. No admission change, no
   `console/` change. This is the slice that needs real-device validation
   (§9) before being trusted as evidence of anything.
4. **`M6` resolver-consumption slice** — the `console/registry_targets.py`
   extension in Q7 (registry currency + trust currency + contract currency,
   all live), wired to slice 1's read API only (never writing). This is
   the slice that finally lets an eligible `device_id` resolve to something
   other than `IDENTITY_TRANSLATION_REQUIRED`.
5. **`M7` — real device-targeted Collect now**, unblocked only once slice 4
   exists and has at least one real-environment-proven relationship row to
   resolve against (§9/§13).

Each slice is independently testable and independently revertable.

### Q9 — Real-environment evidence required before the mapping producer may be considered proven

Automated/fixture tests can prove the *code path* but never the *vendor
claim* that a live, trust-verified, identity-gate-accepted session against a
real Check Point endpoint actually yields a stable, correct `entity_id`
pairing, and never the *operational fact* of how long CP config evidence
practically remains resolvable. Required before real-environment
validation:

- one real enrolled endpoint, contacted twice (separate producer runs, same
  device) — confirming stability, not merely single-shot success, and
  confirming the trust-currency check (Q4/Q7) correctly re-confirms on the
  second run;
- one real endpoint where the CMA-declared `management_ip` candidate and
  the directly-read device disagree in some observable way (a deliberately
  misconfigured/relabeled test case, if available) — confirming the design
  actually refuses/flags `AMBIGUOUS_IDENTITY` rather than silently accepting
  whatever answered;
- direct measurement, against the real CP config evidence store, of how
  long a given run's per-entity evidence (serial, host-key fingerprint)
  remains resolvable via `producing_run_ref` under existing retention/dedup
  behavior — this determines whether Q6.B's DEFERRED contradiction
  comparison can ever be built honestly, or must stay deferred permanently;
- confirmation, against real CMA/MDS API output (not assumed from source
  reading alone), that no serial or other independent hardware-identity
  field is in fact exposed by management-plane discovery today (Q2) — if
  real evidence contradicts that source-reading conclusion, the
  bidirectional-corroboration option must be reopened, not folded in
  silently.

Per `docs/reference/REAL_ENV_VALIDATION_PROTOCOL.md` and `AI_START_HERE.md`
"Real-environment procedure": this session proposes no live command and
performs no device contact — that gate belongs to the implementation
movement(s) in §13's slice 3.

### Q10 — Corrected `M8`-before-`M7` roadmap order

**Confirmed, unchanged from round 1, PO-accepted.** `M6 → M8 (this
document's slices, §13) → M7 → remaining enrollment/capability movements`
is the only viable order.

---

## 4. Trust-before-credential sequence

```
1. Registry lookup (existing, M6-unchanged): device_id known + ENROLLED_UNVERIFIED?
      no  -> UNKNOWN_DEVICE_ID / DEVICE_NOT_ELIGIBLE (unchanged)
      yes -> continue, using the registry's own normalized endpoint

2. Candidate selection (Q1 step 1): CMA/MDS-discovered PhysicalTarget whose
   management_ip equals the registry endpoint -> at most one physical
   entity_id hypothesis. None found -> IDENTITY_TRANSLATION_REQUIRED
   (unchanged current behavior). A hypothesis only, never persisted as
   proof.

3. Target-specific trusted-key lookup (Q3, NEW, mandatory, no device
   contact): does a trusted host-key entry already exist for this exact
   endpoint/port? No -> refuse. No credential is ever resolved for this
   target.

4. Credential / secret resolution (existing DEV.2.1/DEV.2.2 source,
   invoked per-target by M8's new single-target driver) -- only reached
   after step 3 succeeds.

5. ssh.connect() with RejectPolicy (existing, unchanged) -- carries the
   step-4 credential and the registry endpoint.

6. Target host-key verification during the handshake (existing, unchanged;
   redundant with step 3 for this connection, intentionally so -- step 3
   exists precisely to refuse before step 4, not merely before step 6).

7. Authentication (existing, unchanged).

8. Identity reads (existing, unchanged): show hostname / asset command via
   _collect_host / _identity_gate / _collector_identity_gate /
   _parse_asset_semantic(..., "serial"), plus the already-captured
   _host_key_fingerprint -- all persisted as part of this run's ordinary,
   already-governed CP config evidence (Q2/Q4), not a new evidence type.

9. Positive-evidence decision, scope-limited (Q1 points 3-4): identity gate
   accepted -> proceed to step 10. Not accepted -> no row written;
   IDENTITY_TRANSLATION_REQUIRED stands.

10. Relationship write (Q4/Q5/Q6), single-writer (the producer only),
    transactional, into the new M4 table -- never into the Device
    Registry, never duplicating the endpoint, serial, or trust material.
    In the same transaction: check for an existing ACTIVE row for the same
    (device_id, vendor_namespace, mapping_scope) and apply Q6.B's
    ambiguity/supersession rules before inserting.
```

Step 3 strictly precedes step 4 for every endpoint, including one a
management-plane candidate already named — matching
`pcp_first_contact_trust_policy`'s explicit "including management-plane
candidates" clause, and closing the gap the bulk collector's own
once-per-run credential resolution leaves open.

---

## 5. Creation, consumption and invalidation rules (summary)

- **Creation**: only by the slice-3 producer (§13), only after §4's full
  sequence succeeds, one row per successful run, never speculative, never
  batch-inferred from existing `unified.json`/collection output. The
  producer is the **sole writer** of this table, full stop (§Q6).
- **Consumption**: read-only, by `M6`'s resolver only (Q7); re-read on every
  admission and every pre-execution re-check; never cached; every check
  (`mapping_scope`, `state`, registry-record currency, identity-derivation
  currency, trust currency) is computed at read time — the resolver never
  writes to this table under any circumstance.
- **Invalidation**: split (§Q6) into logical, read-time invalidity (never a
  stored mutation) and durable, producer-written `state` transitions
  (`AMBIGUOUS_IDENTITY`, `SUPERSEDED` — written only inside the producer's
  own proof transaction; `CONTRADICTORY_EVIDENCE` DEFERRED, §Q6.B). Never a
  physical delete either way.

---

## 6. Explicit failure/refusal vocabulary

New, in addition to `M6`'s existing four:

| Reason | Meaning |
| --- | --- |
| `RELATIONSHIP_STORE_UNAVAILABLE` | the `M4` relationship table/database itself is unreadable, corrupt, or on an unsupported schema version — a storage fault, never conflated with "not yet proven" |
| `AMBIGUOUS_IDENTITY` | more than one distinct `entity_id` has been proven for the same `(device_id, vendor_namespace, mapping_scope)` triple across separate producer runs — refuse, never pick one |
| `IDENTITY_TRANSLATION_REQUIRED` | unchanged from `M6`: known/eligible `device_id`, no `ACTIVE`, in-scope, currently-valid (registry/identity-contract/trust currency all held) relationship row exists yet — deliberately not split further to avoid leaking which precondition failed |

`CONTRADICTORY_EVIDENCE` is **not** part of this movement's vocabulary — it
is DEFERRED (§Q6.B) pending confirmation that evidence retention supports
it. None of the above is ever paired with a raw exception, filesystem path,
endpoint, hostname, serial, or credential.

---

## 7. Privacy / support-bundle classification

- `device_id`, `entity_id`, `vendor_namespace`, `mapping_scope`,
  `proof_type`, `proof_source`, `identity_derivation_contract_version`,
  `identity_mapping_proven`, `state`, `invalidation_reason`,
  `registry_record_revision`, and `producing_run_ref` are opaque or
  closed-vocabulary references, none raw evidence.
- **No serial, no host-key fingerprint, no endpoint, and no trust material
  is ever persisted in this table.** The serial and fingerprint live only
  in the CP config collector's own existing, already-governed evidence
  (referenced by `producing_run_ref`), which has its own existing privacy
  classification and retention policy, unmodified by this document. The
  new table's own column set is `CLASS 0`-shaped (opaque identifiers and
  closed vocabularies only) — but the *table* itself still lives inside
  `control_plane.db`, already `CLASS 2`-classified in
  `PRIVACY_AND_DATA_HANDLING.md`, and stays there.
- `data/.support_hmac.key` is untouched, unreferenced, and unextended by
  this design (§Q4) — it remains support-bundle-only infrastructure.
- The new table is already structurally excluded from `support_bundle.py`
  (`M4` doc §7: enumeration is scoped to `data_root/runs/<run_id>` plus
  nine named payloads — `data/state/` is out of scope by construction).
- No new field carries an endpoint, hostname, credential, secret, or raw
  configuration — proven the same way `M4`'s own ownership-boundary test
  proves it (a column-name scan over the real schema), not a hand-
  maintained list; the proposed column names (§Q5) contain none of the
  test's forbidden fragments, `"trust"` included.

---

## 8. Concurrency / idempotency / crash-consistency expectations

- **Concurrency**: single-writer via `M4`'s existing `busy_timeout`/`WAL`
  contention posture — a concurrent producer run against the same
  `(device_id, vendor_namespace, mapping_scope)` triple fails closed on
  contention (`ControlPlaneContentionError`), never queues or retries
  silently. The producer is the only writer that ever exists — `console/`
  performs no write of any kind, so no reader/writer race with `console/`
  is possible by construction.
- **Idempotency**: re-running the producer for the same triple that already
  has an `ACTIVE` proof with the same `entity_id` is a no-op at the storage
  layer (matching values, no duplicate row) — a materially different
  `entity_id` for the same triple triggers Q6.B's ambiguity handling in the
  same transaction; a deliberate re-proof after a currency loss
  (`identity_derivation_contract_version` bump, or trust-currency loss) that
  agrees on `entity_id` is `SUPERSEDED`, not a duplicate.
- **Crash consistency**: inherited directly from `M4`'s already-proven
  `synchronous=FULL`/`WAL`/transactional-migration guarantees — no new
  durability mechanism invented; the ambiguity/supersession transitions
  (§Q6.B) are written in the *same* transaction as the new row's insert, so
  a crash mid-write never leaves an old row `INVALIDATED`/`SUPERSEDED`
  without its replacement, or vice versa.

---

## 9. Focused acceptance criteria (draft, for the eventual frozen contract)

1. No relationship row is ever created without §4's full sequence
   succeeding end-to-end in one run.
2. Step 3 (the new target-specific trusted-key lookup) unconditionally
   precedes step 4 (credential resolution) for every endpoint — a test
   proving no credential is ever resolved for a target with no
   pre-existing trusted host-key entry, and that this lookup itself makes
   no network or device contact.
3. `identity_mapping_proven` is `1` only when set by the producer, and
   every read API returns it paired with `mapping_scope` — never a bare
   boolean.
4. `console/registry_targets.py` never reads the registry's endpoint,
   hostname, or any `M6`-forbidden field to resolve a relationship, and
   never writes to the relationship table under any circumstance (a
   structural test, not only a documented rule).
5. An `INVALIDATED`/`SUPERSEDED` row is never returned by the resolver as
   if `ACTIVE`.
6. Registry-record currency (`registry_record_revision`), identity-
   derivation currency, and trust currency (live fingerprint comparison via
   `producing_run_ref`) are each independently testable and each
   independently able to force `IDENTITY_TRANSLATION_REQUIRED`.
7. The `M4` ownership-boundary column-scan test is extended to cover the
   new table and still passes (no endpoint/credential/secret/`trust`-
   fragment column name; no serial, host-key, or trust material column of
   any kind).
8. Two `ACTIVE` proofs for the same `(device_id, vendor_namespace,
   mapping_scope)` triple with different `entity_id` values are refused as
   `AMBIGUOUS_IDENTITY`, with a test proving no recency/heuristic tie-break
   ever occurs.
9. Registry disable/retire is independently re-checked live at consumption
   time (Q7) even when a relationship row is otherwise `ACTIVE`, and this
   check never mutates the relationship row.
10. A relationship whose `producing_run_ref` evidence is no longer
    resolvable is treated as unusable, never as a confirmed match — a test
    using a deliberately pruned/unresolvable evidence reference.

---

## 10. Real-environment validation gate

Per §9 and `docs/reference/REAL_ENV_VALIDATION_PROTOCOL.md`: the slice-3
implementation movement (§13) must propose exactly one bounded, read-only
validation command (single target, explicit scope) for the controlled
environment/human to run; this session performs and proposes no device
contact. The producer may not be marked `REAL_ENV_VALIDATED` from
automated/fixture tests alone, and `M7` must not begin against a
relationship whose only evidence is `AUTOMATED_VALIDATED`.

---

## 11. Proposed small implementation sequence

See §13 (Q8) for the five-slice sequence. Restated as a single line for the
roadmap: **storage/contract → mandatory trust-lookup seam → read-only
producer (real-env gated) → `M6` resolver consumption → `M7`**.

---

## 12. Council consensus and unresolved dissent

**Consensus** (original synthesis, all five seats): the candidate-selection-
then-live-confirmation design (§Q1) is the smallest evidence-led design that
avoids both extremes — neither trusting endpoint/hostname equality alone,
nor requiring evidence this repository does not actually have available
today (bidirectional serial corroboration — confirmed absent, §Q2). All
five seats agreed the `M4` store, not the registry, is the correct owner
(§Q4), and that `identity_mapping_proven` must stay a binary bit, never a
confidence score (§Q1, §Q5). Two correction rounds (direct repository
evidence only, council not re-invoked) sharpened and, in several places,
materially narrowed this consensus without overturning it: `mapping_scope`
made the association's real limit structural rather than aspirational; the
VSX cardinality model was removed as solving a non-existent problem at this
boundary; `data/.support_hmac.key` coupling was removed entirely as
unsuited to identity-association integrity; the stronger trust guarantee was
made mandatory rather than an open question; and the relationship was bound
to live registry/trust currency rather than a source-code version proxy.
Neither round changed §Q4's storage-owner conclusion or §Q10's roadmap
order.

**Unresolved dissent, carried forward, not closed here:**

- **`operator_assertion` remains an OPEN dissent** (inherited from `M6`,
  not reopened or re-litigated). This document's design does not require
  or accept it. Left exactly where `M6` left it.
- **Single-sourced evidence limit** (§Q1 point 5, §Q9): direct-device
  evidence here is not bidirectionally corroborated in the `B2` sense the
  PAN HA serial work uses, because no independent management-plane serial
  source is confirmed to exist. Not blocking, but recorded so a later
  movement does not silently forget to re-open the question if CMA/MDS is
  ever found to expose a usable corroborating field.
- **Contradiction detection is deferred, not solved** (§Q6.B/§Q9): whether
  a later run's serial can be honestly compared against an earlier proof's
  serial depends entirely on CP config evidence retention behavior this
  architecture session did not measure. The slice-3 movement must measure
  it before claiming this gap is closed; if retention cannot support it,
  this document's design accepts staying without contradiction detection
  rather than inventing new key management to work around the gap.
- **Console/trust-module import boundary** (§Q7 point 5): whether
  `console/registry_targets.py` may import `utils/cp_ssh_trust.py`'s new
  lookup function directly, or needs a vendor-neutral wrapper to preserve
  the "console imports no vendor/collector module" posture, is left to the
  slice-4 implementation movement.
- **Vendor-namespace extensibility**: `vendor_namespace`'s
  `CHECK IN ('checkpoint')` constraint stays closed rather than
  open-ended now, so an unimplemented PAN path cannot silently pass
  validation with no producer behind it. A future PAN movement adds its
  own `CHECK` value via its own migration.

---

## 13. Corrected roadmap order (restated for `SESSION CLOSE`/PO decision)

`M6` (done) → **`M8`, this document's five slices** (storage/contract →
mandatory trust-lookup seam → read-only producer, real-env gated → `M6`
resolver consumption) → `M7` → remaining enrollment/capability movements.
`M7` stays blocked until slice 4 exists and has at least one
real-environment-proven relationship to target.

---

## 14. Non-goals (explicit)

- No production code change. No test change. No device contact.
- No `PCP.1` frozen-contract amendment applied or frozen in this session —
  no amendment is actually needed given the `M4` alternative (§15).
- No `M4` schema migration actually applied — this document proposes the
  shape; slice 1 (§13) implements it as its own reviewed movement.
- No Device Registry write, no enrollment change, no automatic enrollment.
- No `M7` implementation or UI affordance.
- No PAN/Palo Alto producer design — `utils/pan_tls_trust.py` symmetry is
  noted (§Q2) but not designed here; Check-Point-only scope throughout.
- No CLASS 2 mutation, no authorization/RBAC change, no new credential or
  secret storage, no new network path beyond the existing `cp-config`
  collector's own, and no reuse of `data/.support_hmac.key` for anything
  beyond its existing support-bundle purpose.
- No resolution of the `operator_assertion` dissent, no resolution of the
  single-sourced-evidence dissent, and no resolution of the deferred
  contradiction-detection question — all three carried forward, not
  closed (§12).
- No `CONTRADICTORY_EVIDENCE` detection mechanism built or assumed to work
  — explicitly DEFERRED (§Q6.B) pending real evidence-retention
  measurement.
- **No general identity-authority claim.** `mapping_scope =
  'CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY'` is the entire authority this
  design produces; it must never be read as evidence for `CLASS_2`
  operational state change, authorization, enrollment, or any
  security-identity purpose. Every "CLASS_0"/"CLASS 2" reference in this
  document is the `utils.action_taxonomy` operational-risk scheme, never
  the unrelated `PRIVACY_AND_DATA_HANDLING.md` data-sensitivity CLASS 0–3
  scheme.
- No table/column name, migration version number, or movement ID in this
  document is frozen; all are illustrative pending a dedicated contract
  freeze review citing the real repository state at that time.

---

## 15. Rejected options (recorded, not proposed as pending amendments)

| Option | Why rejected |
| --- | --- |
| Populate `PCP.1`'s `relationships: []` field on the Device Registry record | Requires reopening the frozen §21 closed-field-set contract and its own test-enforced "always `[]`" invariant; the `M4` store already provides an in-scope alternative, so no amendment is actually needed |
| Accept endpoint/`management_ip` equality alone as proof | Explicitly named insufficient by the PO boundaries; used here only as a candidate-selection hint (§Q1 step 1), never as terminal proof |
| Reuse the collector's `confidence`/`acceptance_basis` string as relationship authority | Explicitly prohibited; replaced by the binary `identity_mapping_proven` bit, always paired with `mapping_scope` |
| Bidirectional serial corroboration (management-plane + direct-device) | No independent management-plane serial source exists in this repository today (checked, §Q2) — not available evidence, not merely undesigned |
| A new, independent identity-mapping store/engine outside `M4` | `pcp_local_control_plane_storage` already decided Option A's scope covers this; a second store would fragment control-plane metadata ownership without any decided justification |
| A column literally named `trust_authority_generation`, or any cosmetically-renamed equivalent | `tests/test_m4_control_plane_metadata_store.py::test_schema_owns_no_forbidden_concept` test-enforces `"trust"` as a forbidden column-name fragment; evading it by renaming would defeat the boundary's intent — replaced by a live per-endpoint fingerprint currency check that references, never copies, trust material |
| A one-device_id-to-many-entity_id (VSX) relationship model | Contradicted directly by `_apply_cp_target_selector`'s own physical-only indexing — `M6`/`M7` targeting never selects a VSX child independently, so this cardinality problem does not exist at this boundary |
| `data/.support_hmac.key`-based serial pseudonymization for contradiction detection | That key has no identity-association lifecycle (on-demand creation, environment-overridable, no rotation/versioning) — a support operation must never be able to gate target-selection authority; replaced by referencing already-governed collector evidence via `producing_run_ref`, with contradiction detection itself DEFERRED pending retention verification |
| A source-code "trust-policy contract version" as the trust-currency signal | Cannot detect a `known_hosts` change made without any code change; replaced by a live, per-endpoint trusted-key-fingerprint comparison (§Q4) |
| Treating the stronger target-specific trust guarantee as an optional future hardening item | The PO ruled this a required prerequisite, not an open question — the producer must not exist until the lookup seam (§Q3) exists and is tested |
