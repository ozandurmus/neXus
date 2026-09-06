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

**CORRECTION ROUND 1 (2026-09-06, PO architecture review of PR #96, same
draft/movement, no second movement record — updated in place per
`AGENTS.md` "Project-state update rule").** The `nexus-decision-council`
was **not** re-invoked for this round — direct repository evidence only, per
PO instruction. Seven corrections applied, all narrowing or correcting prior
overclaims; the roadmap order (`M6 → M8 → M7`) is unchanged and PO-accepted.
Summary (full detail inline at each numbered question/section below):

1. **Evidence scope narrowed.** The design never independently corroborates
   the directly-read serial against the CMA/MDS `entity_id` — the candidate
   is still selected by registry-endpoint == `management_ip` equality; the
   live session proves liveness/authenticity/reachability at that endpoint,
   not independent confirmation of the CMA's own labeling. The relationship
   is now explicitly scoped (`mapping_scope`, §Q5) to
   `CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY` (the `utils.action_taxonomy`
   sense of "CLASS_0", **not** the unrelated `PRIVACY_AND_DATA_HANDLING.md`
   CLASS 0–3 scheme — `AGENTS.md` "Privacy and DLP" names these as
   unrelated namespaces not to be conflated) and is explicitly prohibited
   from any CLASS 2/authorization/`operational_entity_id`/enrollment/
   security-identity use. "Proven" throughout this document now means
   "proven within that stated scope only," never general identity proof.
2. **Trust/credential sequencing corrected** against the real
   `configuration/checkpoint_config_probe.py::_connect` implementation
   (§4): `apply_strict_host_key_policy` loads/configures the trusted store
   and installs `RejectPolicy`; it does not itself verify a *specific*
   target's key — that happens inside `ssh.connect()`'s own transport
   handshake. The collector resolves the run's credential into process
   memory once, before any per-target loop (confirmed at
   `configuration/checkpoint_config_collector.py:1976-1978`), so "credential
   is not read before trust" was an overclaim; the true, existing guarantee
   is narrower ("not authenticated over the wire to an untrusted host") and
   is stated as such, with the stronger, not-yet-existing guarantee named
   as an explicit implementation prerequisite, not assumed.
3. **M4 compatibility corrected.** `trust_authority_generation` as a named
   column is withdrawn — `tests/test_m4_control_plane_metadata_store.py::test_schema_owns_no_forbidden_concept`
   test-enforces `"trust"` as a forbidden column-name fragment (confirmed by
   reading the test). Reused instead: the already-approved
   `capability_projections.authority_generations TEXT NOT NULL` pattern —
   a single closed JSON payload column, no new column name, no M4
   contract/test amendment needed. The "existing five tables" claim is also
   corrected: schema version 1 has **seven** `STRICT` tables, including
   `schema_migrations` (confirmed by reading `utils/control_plane_store.py`
   directly).
4. **Contradictory-evidence comparison mechanism specified.** A raw serial
   can no longer be compared once discarded (as originally drafted). Now
   specified: the existing, already privacy-reviewed
   `data/.support_hmac.key` HMAC-SHA256 pseudonymization mechanism
   (`utils/support_bundle.py`) — a comparison-safe, non-reversible
   fingerprint, never the raw value, classified `CLASS 2` (same as
   `control_plane.db` itself already is per `PRIVACY_AND_DATA_HANDLING.md`)
   — with an explicit threat model and an explicit rule for a missing/
   rotated key (§Q5/§6).
5. **Cardinality corrected.** VSX means one `device_id` legitimately
   produces *multiple* `entity_id` rows (one physical + one per
   `VsContext.vs_id`) — confirmed directly from
   `configuration/checkpoint_config_collector.py`'s own
   `_entity_id`/`_collect_host` iteration over `target.contexts`. The
   one-active-relationship invariant is corrected to be scoped per
   physical-or-VS-id slot, not per `device_id` (§Q5/§Q6).
6. **Invalidation-mechanics contradiction resolved.** Split explicitly into
   *logical, read-time* invalidity (registry disable/retire/endpoint
   change, authority-version staleness — computed live by the resolver,
   never a stored mutation) versus *durable, producer-written* state
   transitions (`SUPERSEDED`, `AMBIGUOUS_IDENTITY`,
   `CONTRADICTORY_EVIDENCE` — written only by the producer, in the same
   transaction as the proof that discovered them). `console/` remains
   strictly read-only; no consumer-side write of any kind (§Q6/§Q7).
7. **Status preserved.** Still `DRAFT — IN REVIEW`, still no code/schema/
   device contact, `M7` still `blocked`, no frozen parent contract touched.

| | |
| --- | --- |
| **Movement** | `ARCHITECTURE`, serving roadmap `now_next` review row `m6_next_movement_po_sequencing_review` |
| **Baseline** | `main` at `0a9048ceeb2a318444f918e2688b126641eaeab0`, verified equal to `origin/main` before this session began |
| **Parent contracts** | `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §5 (identity layering), §6 (Device Registry), §21 (`PCP.1`, FROZEN); `docs/history/phase/M4_LOCAL_CONTROL_PLANE_METADATA_STORE.md` (control-plane SQLite store, IMPLEMENTED); `project/roadmap.json` decisions `pcp_local_control_plane_storage` (DECIDED, Option A), `pcp_first_contact_trust_policy` (DECIDED — names `M8` as the trust-establishment movement over `utils/cp_ssh_trust.py`/`utils/pan_tls_trust.py`, no new credential/network path) |
| **Preserves unchanged** | `PCP.1` Device Registry frozen field set and `relationships: []` structural invariant; `M6` fail-closed admission shell (`console/registry_targets.py`); `M4` schema version 1's existing seven `STRICT` tables (`schema_migrations`, `control_plane_metadata`, `job_definitions`, `job_submissions`, `job_runs`, `schedules`, `capability_projections`) and its `test_schema_owns_no_forbidden_concept` column-name boundary; the `CON.2` job lifecycle vocabulary; `utils/action_taxonomy.py`; one Console, one registry, one identity authority |
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

**Accepted, and only in this order — and only as a *scope-limited*
association, not general identity proof (correction round 1, below):**

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
3. **What this actually proves, precisely — corrected, round 1.** The
   collector's identity gate reporting `accepted = True` (an authenticated
   session, a successful `show hostname`, a successful read-only
   configuration capability) **and** a directly-read `serial`
   (`_parse_asset_semantic(..., "serial")`) together prove that **a real,
   live, authenticated Check Point device answers at the registry's
   declared endpoint** — strong, direct-device evidence of *liveness and
   authenticity at that endpoint*. They do **not** independently prove that
   this live device is the same object the CMA/MDS labels with the
   candidate `entity_id` from step 1: no repository evidence source
   corroborates the CMA's own `entity_id`↔serial (or any other
   CMA-side hardware-identity field) pairing independently of the
   endpoint-equality hypothesis that selected the candidate in the first
   place (confirmed absent, §Q2). Put plainly: the *final* `entity_id`
   binding still traces back to registry-endpoint == `management_ip`
   equality at its root; step 2 corroborates that the endpoint is live and
   genuinely a Check Point device, it does not independently corroborate
   the CMA's own labeling of that endpoint. Describing this as universally
   proven identity would overstate it.
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
   `mapping_scope`," never "proven as security identity."
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

### Q3 — What strict transport-trust condition must be satisfied before credentials or identity reads are attempted? (corrected, round 1)

**Correction round 1 applies here directly.** The original answer collapsed
several distinct steps into one claim ("credential is not read before
trust") that direct inspection of
`configuration/checkpoint_config_probe.py::_connect` (the real
implementation `checkpoint_config_collector.py` calls) does not support.
The seven steps, named separately as the PO required:

| # | Step | What it actually is, per real code | Exists today? |
| --- | --- | --- | --- |
| 1 | **Trust-store preflight** | `apply_strict_host_key_policy(ssh, strict)` (`utils/cp_ssh_trust.py:158`): loads the system `known_hosts` store, installs `_NonRetryableRejectPolicy`, and raises `CpSshStrictPreflightError` if **zero** trusted entries were loaded — a generic "is there any trust material at all" check, **not target-specific**. Called once as a throwaway pre-check before the retry loop and again on the real client (`checkpoint_config_probe.py:581-594`). | Yes, exists, unchanged |
| 2 | **Credential reference resolution** | The registry's `credential_ref` resolves to an env-var-backed `username`/`secret` pair (`configuration/checkpoint_config_collector.py:1976-1978`, `SECURITYEXPERT_CP_CONFIG_SSH_USERNAME`/`_PASSWORD`) | Yes, exists, unchanged |
| 3 | **Secret material resolution into process memory** | The *same* step as #2 — `username`/`secret` become local Python values **once, for the whole collector run, before any per-target loop begins** | Yes, exists, unchanged — and this is the step the original draft mischaracterized |
| 4 | **Transport connection** | `ssh.connect(target.management_ip, port=port, username=username, password=secret, ...)` (`checkpoint_config_probe.py:596-606`) — one call that internally performs TCP connect, SSH transport/key-exchange, host-key verification, and authentication | Yes, exists, unchanged |
| 5 | **Target host-key verification** | Happens *inside* step 4's `connect()` call, when paramiko compares the specific target's presented key against the policy installed in step 1 — `RejectPolicy` raises `HostKeyNotTrustedError`/`SSHException` here if that specific key was never loaded, **before** authentication is attempted on the wire for that connection | Yes, exists, unchanged — this is real, but it is a **protocol-level** ordering internal to a single `connect()` call, not an application-level gate this repository wrote |
| 6 | **Authentication / credential presentation** | Also inside step 4's `connect()` call, after step 5 succeeds — the already-in-memory `username`/`secret` from steps 2–3 are sent over the now-verified transport | Yes, exists, unchanged |
| 7 | **Identity reads** | `show hostname` / asset-command reads, after `connect()` returns successfully | Yes, exists, unchanged |

**What is actually true today:** for a given target, the SSH protocol
itself verifies that target's host key (step 5) before authenticating to it
(step 6) — a real, existing, `RejectPolicy`-enforced guarantee with no TOFU
and no bypass. **What is not true, and was wrongly claimed:** that the
credential is *not resolved into process memory* until trust is confirmed.
Steps 2–3 happen once, for the entire run, before the per-target loop even
starts — well before step 5 for any specific target. `M8`'s producer, reusing
this exact seam unmodified, inherits precisely this same, narrower
guarantee: **not authenticated to an untrusted host**, not **"credential
never resolved before any trust interaction."**

**Named implementation prerequisite, not assumed to exist.** If a stronger
guarantee is wanted specifically for `M8`'s first-contact scenario (a
manually-enrolled, never-before-contacted endpoint — exactly the case
`pcp_first_contact_trust_policy` worries about, "credentials are never
presented to a mistyped or hostile endpoint") — i.e., not even resolving a
credential into memory for a given target until that *specific* target's
host key is independently confirmed already trusted — that requires a
**new, target-specific, pre-connection host-key lookup seam**.
`utils/cp_ssh_trust.py` exposes no such per-target lookup today (only the
two functions named in the table above); building one is a legitimate,
narrowly-scoped addition **within the existing `known_hosts`
trust seam** (parsing the same file with `paramiko.HostKeys`, mirroring the
cardinality-count technique the module's own docstring already documents)
— not a new credential path, not a new trust authority, and not proposed as
already existing. It is named here as an open implementation question for
the slice-2 producer movement (§13), not decided or built by this document.

No TOFU, no automatic host-key acceptance, and no compatibility-mode
carve-out for a non-corroborated endpoint remain allowed, unchanged from the
already-DECIDED `pcp_first_contact_trust_policy` — that part of the original
answer stands.

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

**Correction round 1 — M4 compatibility check, performed against the real
schema, not the prose summary.** `utils/control_plane_store.py` confirms
schema version 1 has **seven** `STRICT` tables (`schema_migrations`,
`control_plane_metadata`, `job_definitions`, `job_submissions`, `job_runs`,
`schedules`, `capability_projections`) — the original draft's "existing five
tables" undercounted them; corrected throughout this document.
`tests/test_m4_control_plane_metadata_store.py::test_schema_owns_no_forbidden_concept`
test-enforces a closed list of forbidden column-name *fragments* against
every real column, scanned structurally (not a maintained list) — and
`"trust"` is one of them. A column literally named
`trust_authority_generation` (as originally drafted) would fail that test
outright; renaming it to dodge the substring would be exactly the
"cosmetic renaming" the PO ruled out. The honest fix, chosen and justified
below (§Q5), is to reuse the *already-approved* `capability_projections.
authority_generations TEXT NOT NULL` shape verbatim — a single closed JSON
payload column, already reviewed, already in production schema version 1 —
rather than inventing any new per-authority column name. **No `M4`
contract or test amendment is required or proposed.**

**Registry's own `relationships: []` field is explicitly not the target.**
`utils/device_registry.py::_validate_persisted_record` test-enforces that
field as an empty list in `PCP.1` (`AC-2a`/structural). Populating it would
require reopening the `PCP.1` §21 frozen contract's closed field set and its
own test-enforced invariant — precisely the frozen-amendment path this
session is not authorized to apply (§15 lists it instead as a *rejected*
option, not a proposed amendment, because `M4` already provides an
in-scope alternative that needs no amendment at all).

### Q5 — Minimum persisted relationship fields (corrected, round 1)

Corrected against three PO findings: (a) `"trust"` is a test-enforced
forbidden column-name fragment in `M4` — no column may be named
`trust_authority_generation`; (b) a raw serial cannot be compared later if
discarded, so a comparison-safe mechanism is required; (c) VSX gives one
`device_id` legitimately multiple `entity_id` rows, so the uniqueness
invariant must be scoped per physical-or-VS-id slot, not per `device_id`.

| Field | Type | Notes |
| --- | --- | --- |
| `relationship_id` | PK, opaque | new identifier for the row itself, never reused for `device_id` or `entity_id` |
| `device_id` | text, `NOT NULL` | opaque `PCP.1` value; **not** a foreign key into the registry (`M4` owns none of the registry's rows — §7 of the `M4` doc) — validity is re-checked live against the registry at consumption time (Q7), never trusted from a stale join |
| `entity_id` | text, `NOT NULL` | opaque, exactly as `_entity_id` produced it — no normalization |
| `entity_scope` | text, `CHECK IN ('physical', 'vsx_virtual_system')` | **new, round 1** — mirrors `configuration/checkpoint_config_collector.py`'s own model directly: a `PhysicalTarget` with no `VsContext` produces `physical`; each `VsContext` iterated by `_collect_host` produces one `vsx_virtual_system` row. Recorded as a direct, structural fact carried over from the producing run's own `target`/`context` objects — never re-derived by parsing the `entity_id` string after the fact (would itself be a string-manipulation identity inference, `AGENTS.md` "Identity law") |
| `vs_id_slot` | text, `NOT NULL`, `DEFAULT ''` | **new, round 1** — the opaque `VsContext.vs_id` value for a `vsx_virtual_system` row, or the empty-string sentinel for a `physical` row. A non-null sentinel is required because SQL `UNIQUE` treats every `NULL` as distinct, which would silently defeat the one-active-slot invariant below if `vs_id` were left `NULL` for physical rows — an explicit, tested design choice, not an oversight |
| `vendor_namespace` | text, `CHECK IN ('checkpoint')` at this movement's scope; extensible only by a later migration if PAN is ever added | prevents a cross-vendor identity collision from silently aliasing |
| `mapping_scope` | text, `CHECK IN ('CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY')` | **new, round 1** (§Q1 point 4) — the `utils.action_taxonomy` `CLASS_0_READ` sense of "class," not the unrelated `PRIVACY_AND_DATA_HANDLING.md` CLASS 0–3 scheme. Closed vocabulary; a future scope value requires its own migration and its own PO decision, never silent reuse for a broader purpose. Any code outside `console/registry_targets.py`'s `config_refresh_cp` consumption path reading this table for any other purpose is out of contract |
| `producing_run_ref` | text, `NOT NULL` | opaque reference to the exact first-contact run/session that produced this row — the "which invocation" provenance, not raw evidence (Raw-evidence law: reference only, never the raw transcript) |
| `proof_type` | text, `CHECK IN ('first_contact_identity_gate_and_serial')` at this movement's scope | closed vocabulary, not a free-text confidence field (Q1's constraint) |
| `proof_source` | text, `CHECK IN ('direct_device_read')` | records evidence *grade* (direct vs. management-plane), not a *confidence* score — keeps the `AGENTS.md` evidence-grade distinction machine-readable without inventing a subjective scale |
| `serial_fingerprint` | text, nullable | **new, round 1** (§4 below) — an HMAC-SHA256 digest of the directly-read serial, keyed by the existing, already privacy-reviewed `data/.support_hmac.key` (`utils/support_bundle.py`'s established pseudonymization mechanism), never the raw serial itself. Nullable only for the case the collector's identity gate accepted the session without a usable serial read (rare — `_parse_asset_semantic` returned nothing); such a row can still be `identity_mapping_proven = 1` within `mapping_scope` (hostname/config-read acceptance is still real evidence at that scope) but can never later prove or disprove a serial contradiction |
| `observed_at_utc` | text, `NOT NULL` | when the producing run observed the evidence, not when the row was written |
| `authority_generations` | text, `NOT NULL` | **corrected, round 1** — reuses `capability_projections.authority_generations`'s exact shape verbatim (a single closed JSON payload, e.g. `{"trust_policy_contract_version": "...", "identity_derivation_contract_version": "..."}`), not two individually-named columns. Both values are **source-code constants a human bumps when the respective contract changes** — mirroring `capability_projections.producer_version`/`support_rule_version` exactly, never a runtime-computed "generation" counter, because no such counter exists for either `utils/cp_ssh_trust.py`'s policy contract or `_entity_id`'s derivation contract today (confirmed absent by reading both modules) — inventing one would itself be an unbuilt-and-unproposed mechanism, not something to assume |
| `identity_mapping_proven` | integer (`0`/`1`), `NOT NULL` | binary, never a probability/confidence float — `1` only when steps in Q1 completed **and understood as scoped to `mapping_scope`, never general identity proof**; the sole authoritative "is this usable, within scope" bit `M6`'s resolver may read |
| `state` | text, `CHECK IN ('ACTIVE', 'INVALIDATED', 'SUPERSEDED')` | durable states only — see Q6's split between this column and read-time logical invalidity |
| `invalidation_reason` | text, nullable | populated only when `state != 'ACTIVE'`; closed vocabulary drawn from Q6's list, never a raw exception or free text |
| `created_at_utc` / `updated_at_utc` | text, `NOT NULL` | standard audit pair, matching every other `M4` table |

`UNIQUE (device_id, vendor_namespace, entity_scope, vs_id_slot)` where
`state = 'ACTIVE'` (a partial unique index, mirroring `M4`'s existing
`ux_job_runs_one_active_per_definition` pattern) — **corrected, round 1**:
at most one active proven relationship per *physical-or-specific-VS-id
slot*, not per `device_id` as a whole. A `device_id` legitimately has one
`ACTIVE` `physical` row **and** any number of `ACTIVE`
`vsx_virtual_system` rows simultaneously (one per distinct `vs_id_slot`) —
this is normal, expected VSX cardinality, never treated as ambiguity (Q6).
Ambiguity is instead scoped to the **same slot** — two different `entity_id`
values proven for the same `(device_id, vendor_namespace, entity_scope,
vs_id_slot)` — which the partial unique index cannot by itself prevent
(different `entity_id` values, same slot, is not a uniqueness violation on
this index) and which the producer must therefore detect explicitly at
write time (Q6).

**No subjective confidence column anywhere in this table** — satisfies the
PO boundary directly; `identity_mapping_proven` is the only authority bit,
and it is binary by construction, not derived from the collector's own
`confidence`/`acceptance_basis` strings (those may be logged in
`producing_run_ref`'s target evidence for audit, never copied into this
table as if they were proof).

### Q6 — When must a relationship become unusable? (corrected, round 1 — logical vs. durable split)

The original answer proposed `ACTIVE → INVALIDATED` for every case,
including registry disable/retire — but `console/registry_targets.py`'s
consumption (Q7) is strictly read-only and never writes to this table, so
nothing at *consumption time* can perform that mutation. Corrected into two
explicitly separate mechanisms, with a single rule for who may write:

**Only the producer (§13's slice-2 first-contact run) ever writes to this
table — never `console/`, never a background sweep, never any other
process.** A durable `state` write happens only inside the producer's own
transaction, at the moment a *new* proof attempt discovers the condition.

**A. Logical, read-time invalidity — computed live by the resolver, never a stored mutation:**

- **Registry disable/retire.** `device_id`'s registry state leaves
  `ENROLLED_UNVERIFIED` (`DISABLED`, `RETIRED`, or any state `M6`'s own
  `_ELIGIBLE_STATES` would refuse). `M6`'s resolver already re-reads the
  registry live on every call (Q7) — this table's own rows are never
  touched or marked because of it; the row can stay `ACTIVE` indefinitely
  and simply not be reachable while the registry itself refuses first.
- **Registry endpoint changed.** The registry's endpoint for `device_id`
  no longer matches what a stored row's `producing_run_ref` was proven
  against. Detected the same way — a live comparison at resolution time,
  not a row mutation. (A future re-proof for the new endpoint creates its
  own new row via the ordinary flow; the old row is left exactly as
  written, simply never resolved to again because its own producing
  context no longer matches live registry state.)
- **Authority-contract staleness.** The row's `authority_generations`
  payload (§Q5) no longer matches the *current* source-code constants for
  the trust-policy contract and the identity-derivation contract. Computed
  live by comparing the row's stored values against the current constants
  at resolution time — never a background sweep, never a stored mutation
  triggered by the mere passage of time or a code deploy.

None of these three ever produces a durable write. `M6`'s resolver (Q7)
folds all three into its existing "not currently usable" refusal alongside
`identity_mapping_proven`/`state == 'ACTIVE'`/`mapping_scope` checks — all
computed at read time, all re-checked on every call, exactly matching the
"re-read every call, no cache" posture `M6` already established for the
registry itself.

**B. Durable, producer-written state transitions — written only inside the producer's own proof transaction:**

- **Ambiguity.** A *new* first-contact proof attempt for the same
  `(device_id, vendor_namespace, entity_scope, vs_id_slot)` slot (§Q5)
  yields a *different* `entity_id` than an existing `ACTIVE` row for that
  same slot. The producer, in the same transaction as the new attempt,
  moves both the old and the (rejected) new claim to `INVALIDATED` with
  reason `AMBIGUOUS_IDENTITY` — no code path may pick one over the other by
  recency or any heuristic; this is a `RELATIONSHIP_INCONSISTENT` state
  (`AGENTS.md` UNKNOWN/fail-closed law), reported, not resolved silently.
- **Contradictory evidence.** A *new* first-contact proof for the same
  slot returns a `serial_fingerprint` (§Q5, §4) that differs from an
  existing `ACTIVE` row's fingerprint for the *same* `entity_id`. The
  producer moves the old row to `INVALIDATED` with reason
  `CONTRADICTORY_EVIDENCE` in the same transaction — both the old and new
  claims are surfaced via `producing_run_ref`, not auto-reconciled (mirrors
  the PAN HA serial `B2` posture of reporting the conflict rather than
  resolving it). **Missing/rotated-key case:** if `data/.support_hmac.key`
  is unreadable or has changed since an `ACTIVE` row's fingerprint was
  computed, that row's fingerprint can never again be meaningfully compared
  — the producer must treat this as **inability to prove non-contradiction**,
  not as a clean bill of health, and must not silently supersede or confirm
  using an uncomparable fingerprint. Key rotation does not, by itself,
  invalidate the old row (rotation alone is not evidence of contradiction),
  but it does permanently foreclose future contradiction-detection against
  that row's fingerprint — a disclosed limit (§9), not a silently accepted
  one.
- **Supersession.** A fresh, successful proof for the *same* slot
  (`device_id`/`vendor_namespace`/`entity_scope`/`vs_id_slot`) that agrees
  with the existing `ACTIVE` row's `entity_id` and (when comparable)
  `serial_fingerprint` — e.g. a deliberate re-proof after an
  authority-contract bump — moves the old row to `SUPERSEDED` (not an error
  condition) and inserts the new row, in the same transaction.

**Corrupt state / missing provenance — fail-closed read outcomes, not row mutations:**

- A row failing its own `STRICT`/`CHECK` constraints, or the
  table/database itself being unreadable
  (`ControlPlaneCorruptionError`/`ControlPlaneSchemaVersionError`), is
  reported by the resolver as `RELATIONSHIP_STORE_UNAVAILABLE` — never
  misreported as "no relationship exists," mirroring `M6`'s
  `DEVICE_REGISTRY_UNAVAILABLE` precedent exactly.
- A row whose `producing_run_ref` cannot be resolved is treated as
  logically unusable at read time (folded into the same live-check family
  as §A) — never a stored mutation either, since the row itself may still
  be structurally intact; only its provenance is unverifiable.

### Q7 — How does `M6` resolve the relationship at admission and re-check it before execution? (corrected, round 1)

Exactly the pattern `M6` already established for the registry itself,
extended by one more read, never a copy. Corrected to make explicit that
every check the resolver performs is **live and read-only** — nothing here
ever writes to the relationship table (§Q6 established the producer as the
sole writer):

1. `console/registry_targets.py::resolve_registry_targets()` keeps its
   existing registry-only checks (`UNKNOWN_DEVICE_ID` /
   `DEVICE_NOT_ELIGIBLE`) unchanged, first.
2. For a `device_id` that is known and eligible, a **new**, equally
   fail-closed, **read-only** lookup against the `M4` relationship table
   (re-read from disk on every call — no caching between admission and the
   pre-execution re-check, exactly like the registry lookup) replaces the
   current unconditional `IDENTITY_TRANSLATION_REQUIRED` refusal. For the
   candidate `physical`-scope row (`config_refresh_cp` targets the physical
   host, never a specific VS by this movement's scope — §14 non-goals), the
   resolver requires, all computed live, none stored as a consumer-side
   write:
   - the row's `mapping_scope == 'CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY'`
     (defensive — `config_refresh_cp` is the only consumer and the only
     scope value that exists, but never assumed without checking);
   - the row's `state == 'ACTIVE'`;
   - the row's `authority_generations` matches the current trust-policy and
     identity-derivation contract constants (§Q6.A — a stale row is treated
     identically to no row, `IDENTITY_TRANSLATION_REQUIRED`, not a distinct
     error, since re-proof under the new contract is the only remedy
     either way);
   - the registry's own eligibility check from step 1, re-confirmed live at
     this same moment (no result is cached from admission into execution).

   Outcomes:
   - no qualifying `ACTIVE` row (missing, wrong scope, or stale
     authority-generations) → `IDENTITY_TRANSLATION_REQUIRED` (nothing
     regresses; this is the exact current behavior when no relationship has
     been produced yet, deliberately not split into finer-grained reasons
     that would leak whether a stale/wrong-scope row exists);
   - relationship-store unavailable/corrupt →
     `RELATIONSHIP_STORE_UNAVAILABLE` (distinct reason, sanitized detail,
     mirroring `DEVICE_REGISTRY_UNAVAILABLE`'s correction-round-1 pattern —
     never conflated with "not proven");
   - exactly one qualifying `ACTIVE` row → its `entity_id` is returned as
     the resolved collector target;
   - more than one qualifying `ACTIVE` `physical`-scope row for the same
     `device_id` (should be structurally prevented by the partial unique
     index in §Q5, but checked defensively) → fail closed, never a
     first-match/most-recent heuristic.
3. `console/runner.py`'s pre-execution re-check calls the same function
   again, immediately before `main.main()`, so a relationship that has
   become logically unusable between admission and execution (registry
   disabled, endpoint changed, authority contract bumped) refuses there
   too, purely by re-evaluating the same live checks — the identical
   defense-in-depth shape `M6` already uses for the registry check and for
   the action-class refusal.
4. **No endpoint, hostname, or identity fallback authority is ever copied
   into `console/`, and `console/` never writes to the relationship table.**
   The console-side function only ever asks the `M4` store "what active,
   in-scope, current-authority relationship exists," never recomputes,
   re-derives, second-guesses, or durably invalidates a relationship using
   any of the evidence classes `M6`/`M8` already forbid at that boundary.

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
   `device_id` and writes one row per successfully proven slot via slice
   1's API — a `physical`-scope row for the host itself, plus one
   `vsx_virtual_system`-scope row per hosted VS the same run's identity
   gate accepts (§Q5's cardinality correction, round 1; not literally
   "exactly one row" when VSX is present). No admission change, no
   `console/` change. This is the slice that needs real-device validation
   (§9) before being trusted as evidence of anything.
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

## 4. Trust-before-credential sequence (corrected, round 1 — the seven steps named separately, per Q3)

```
1. Registry lookup (existing, M6-unchanged): device_id known + ENROLLED_UNVERIFIED?
      no  -> UNKNOWN_DEVICE_ID / DEVICE_NOT_ELIGIBLE (unchanged)
      yes -> continue, using the registry's own normalized endpoint

2. Candidate selection (Q1 step 1): CMA/MDS-discovered PhysicalTarget whose
   management_ip equals the registry endpoint -> at most one entity_id
   hypothesis (entity_scope=physical; a VSX child's vs_id_slot is a
   separate, later slot -- see Q5/section 14). None found ->
   IDENTITY_TRANSLATION_REQUIRED (unchanged current behavior). This step is
   a hypothesis, never persisted as proof (Q1 point 3-4).

3. Trust-store preflight (Q3 step 1, existing, unchanged):
   utils.cp_ssh_trust.apply_strict_host_key_policy -- confirms trusted
   host-key material exists at all (not yet target-specific). Fails ->
   CpSshStrictPreflightError, refuse before any connection attempt.

4. Credential reference resolution + secret material resolution into
   process memory (Q3 steps 2-3, existing, unchanged): the registry's
   credential_ref resolves to the existing DEV.2.1/DEV.2.2 non-interactive
   source -- never persisted, never logged. Stated honestly (round 1): this
   step happens before step 5's target-specific host-key check, exactly as
   it does in today's collector run -- it is NOT gated behind a
   target-specific trust confirmation, because no such per-target
   pre-check seam exists yet (Q3's named implementation prerequisite). What
   IS guaranteed is step 6 below.

5. Transport connection attempt (Q3 step 4, existing, unchanged): a single
   ssh.connect() call carrying the step-4 credential, target.management_ip,
   and the step-3 policy.

6. Target host-key verification (Q3 step 5, existing, unchanged, real
   guarantee): inside the same connect() call, RejectPolicy verifies this
   SPECIFIC target's key before authentication is attempted on the wire.
   Untrusted -> HostKeyNotTrustedError/SSHException, connect() raises,
   authentication for this target never occurs; nothing in steps 7+ runs.
   This is the actual, existing "no credential presented to an untrusted
   endpoint" guarantee -- narrower than "credential never resolved," as
   corrected in Q3.

7. Authentication / credential presentation + identity reads (Q3 steps
   6-7, existing, unchanged): the collector's own _collect_host /
   _identity_gate / _collector_identity_gate / _parse_asset_semantic(...,
   "serial") sequence, run for exactly the one candidate target from step 2.

8. Positive-evidence decision, scope-limited (Q1 points 3-4): identity gate
   accepted -> proceed to step 9, computing serial_fingerprint (Q5) from
   any serial read via the existing data/.support_hmac.key mechanism.
   Not accepted -> no row written; IDENTITY_TRANSLATION_REQUIRED stands
   (this run produced no evidence, not a false negative to retry
   automatically).

9. Relationship write (Q4/Q5/Q6), single-writer (the producer only),
   transactional, into the new M4 table -- never into the Device Registry.
   In the same transaction: check for an existing ACTIVE row in the same
   slot (device_id, vendor_namespace, entity_scope, vs_id_slot) and apply
   Q6.B's ambiguity/contradiction/supersession rules before inserting.
```

No step is reordered, skipped, or short-circuited for convenience beyond
what is already true of the existing, unmodified collector seam. Step 6's
target-specific trust check strictly precedes step 7's authentication for
every endpoint, including one a management-plane candidate already named —
matching `pcp_first_contact_trust_policy`'s explicit "including
management-plane candidates" clause. Step 4 is **not** gated behind a
target-specific trust confirmation today (corrected, round 1) — see Q3 for
the named, not-yet-built, stronger seam this would require.

---

## 5. Creation, consumption and invalidation rules (summary, corrected round 1)

- **Creation**: only by the slice-2 producer (§13), only after §4's full
  sequence succeeds, one row per successful run, never speculative, never
  batch-inferred from existing `unified.json`/collection output (mirrors
  `M6`'s own "never consults collection output" boundary). The producer is
  the **sole writer** of this table, full stop (§Q6).
- **Consumption**: read-only, by `M6`'s resolver only (Q7); re-read on every
  admission and every pre-execution re-check; never cached; never consulted
  by anything outside the `console/registry_targets.py` boundary that
  already exists; every check the resolver performs (`mapping_scope`,
  `state`, `authority_generations` currency, live registry re-check) is
  computed at read time — **the resolver never writes to this table under
  any circumstance** (§Q6.A, §Q7 — corrected from the original draft's
  implication that consumption-time registry disable could invalidate a
  row).
- **Invalidation**: split (§Q6, corrected round 1) into logical, read-time
  invalidity (registry disable/retire/endpoint change, authority-contract
  staleness — never a stored mutation) and durable, producer-written `state`
  transitions (`AMBIGUOUS_IDENTITY`, `CONTRADICTORY_EVIDENCE`,
  `SUPERSEDED` — written only inside the producer's own proof transaction).
  Never a physical delete either way (auditability — an
  `INVALIDATED`/`SUPERSEDED` row stays queryable for history, exactly as
  `job_runs` rows are never deleted).

---

## 6. Explicit failure/refusal vocabulary

New, in addition to `M6`'s existing four:

| Reason | Meaning |
| --- | --- |
| `RELATIONSHIP_STORE_UNAVAILABLE` | the `M4` relationship table/database itself is unreadable, corrupt, or on an unsupported schema version — a storage fault, never conflated with "not yet proven" |
| `AMBIGUOUS_IDENTITY` | more than one distinct `entity_id` has been proven for the **same slot** (`device_id`/`vendor_namespace`/`entity_scope`/`vs_id_slot`, §Q5 — corrected round 1, not merely "the same `device_id`," which would wrongly flag normal VSX multiplicity) across separate producer runs — refuse, never pick one |
| `CONTRADICTORY_EVIDENCE` | a later run's `serial_fingerprint` disagrees with an earlier `ACTIVE` row's fingerprint for the same `entity_id` (comparison via the HMAC mechanism, §Q5/§Q6 — corrected round 1, not a raw serial comparison, which cannot exist once the raw value is discarded) — refuse, surface both |
| `IDENTITY_TRANSLATION_REQUIRED` | unchanged from `M6`: known/eligible `device_id`, no `ACTIVE`, in-scope, current-authority relationship row exists yet (also covers a stale-authority or wrong-scope row, deliberately not split further — §Q7) |

None of these is ever paired with a raw exception, filesystem path,
endpoint, hostname, serial, or credential — matching `M6` correction round
1's sanitized-detail precedent exactly.

---

## 7. Privacy / support-bundle classification (corrected, round 1)

- `device_id`, `entity_id`, `entity_scope`, `vs_id_slot`, `vendor_namespace`,
  `mapping_scope`, `proof_type`, `proof_source`, `authority_generations`
  (source-code version strings, not secrets), `identity_mapping_proven`,
  `state`, and `invalidation_reason` are opaque or closed-vocabulary values,
  none raw evidence, consistent with the original design.
- **Corrected, round 1 — `serial_fingerprint` is a genuine new privacy
  surface, not "not persisted" as originally drafted.** The original
  answer said the serial is discarded and never persisted; §Q5/§Q6
  corrected this because a discarded value cannot be compared for
  `CONTRADICTORY_EVIDENCE` later. The mechanism actually specified:
  `serial_fingerprint = HMAC-SHA256(key=<contents of the existing,
  already privacy-reviewed data/.support_hmac.key>, message="cp_serial:" +
  raw_serial)`, computed once in-memory during the producing run and
  persisted as the digest only — the exact scheme
  `utils/support_bundle.py` already uses to pseudonymize identities
  (`hmac.new(self.key, f"{kind}:{text}", hashlib.sha256).hexdigest()`,
  `utils/support_bundle.py:71`), reused verbatim, not a new key-management
  mechanism. **Threat model**: the digest is not reversible to the raw
  serial without the local HMAC key; it is not comparable across a
  different neXus installation (different key); it does not appear
  anywhere the raw serial itself wasn't already implicitly classified —
  `PRIVACY_AND_DATA_HANDLING.md` already lists "serial numbers" as `CLASS 2`
  and already separately lists `data/state/control_plane.db` itself as
  `CLASS 2` — so this field stays inside a boundary already drawn at that
  classification, it does not need a new or higher one. **Missing/rotated
  key**: if the key is unreadable or has changed since a row's fingerprint
  was computed, that fingerprint becomes permanently uncomparable (§Q6.B)
  — a disclosed limit, not a silent one; the fingerprint is never
  recomputed retroactively from a raw value that no longer exists anywhere.
  The raw serial itself is never persisted in this table or any other new
  field — only the fingerprint, matching the Raw-evidence law's "parse the
  minimum required semantics → safe... tokenized identities... discard the
  raw response" pattern exactly, with the fingerprint standing in for the
  "safe tokenized identity."
- `producing_run_ref` is an opaque reference, not a transcript.
- The new table lives inside `control_plane.db`, already `DATABASE_ARTIFACT`
  classified by `utils/repository_privacy.py`, already `.gitignore`d, and
  already listed `CLASS 2` in `PRIVACY_AND_DATA_HANDLING.md`; already
  structurally excluded from `support_bundle.py` (§7 of the `M4` doc:
  enumeration is scoped to `data_root/runs/<run_id>` plus nine named
  payloads — `data/state/` is out of scope by construction, no new
  exclusion rule needed).
- No new field carries an endpoint, hostname, raw credential, raw secret, or
  raw configuration — to be proven the same way `M4`'s own ownership-
  boundary test proves it (a column-name scan over the real schema, §Q4/§Q5
  — confirmed the proposed column names do not collide with any forbidden
  fragment, `"trust"` included, since `authority_generations` and
  `serial_fingerprint` were chosen specifically to avoid it), not by a
  hand-maintained list.

---

## 8. Concurrency / idempotency / crash-consistency expectations (corrected, round 1 for cardinality/writer model)

- **Concurrency**: single-writer via `M4`'s existing `busy_timeout`/`WAL`
  contention posture — a concurrent producer run against the same slot
  (`device_id`/`vendor_namespace`/`entity_scope`/`vs_id_slot`) fails closed
  on contention (`ControlPlaneContentionError`), never queues or retries
  silently, matching the registry's own "no wait, retry, or queue" lock
  posture (`AC-13`). The producer is the only writer that ever exists
  (§Q6) — `console/` performs no write of any kind, so no reader/writer
  race with `console/` is possible by construction, not merely by
  discipline.
- **Idempotency**: re-running the producer for the same slot that already
  has an `ACTIVE` proof whose `entity_id` and (when comparable)
  `serial_fingerprint` agree is a no-op at the storage layer (matching
  values, no duplicate row) — a *materially new* proof for the same slot
  that disagrees triggers Q6.B's ambiguity/contradiction handling in the
  same transaction, never silently overwriting a row in place (loss of
  audit trail would violate "do not silently rewrite historical outcomes,"
  `AGENTS.md` "Project-state update rule"). A materially new proof that
  *agrees* but reflects a bumped `authority_generations` (a deliberate
  re-proof) is `SUPERSEDED`, not a duplicate.
- **Crash consistency**: inherited directly from `M4`'s already-proven
  `synchronous=FULL`/`WAL`/transactional-migration guarantees — no new
  durability mechanism invented; a crash mid-write leaves either the prior
  `ACTIVE` row or nothing, never a half-written row (the same guarantee
  `job_runs` already relies on for `CON.0` §7.9's durability-before-start
  rule). The ambiguity/contradiction/supersession transitions (§Q6.B) are
  written in the *same* transaction as the new row's insert, so a crash
  mid-write never leaves an old row `INVALIDATED`/`SUPERSEDED` without its
  replacement, or vice versa.

---

## 9. Focused acceptance criteria (draft, for the eventual frozen contract; corrected, round 1)

1. No relationship row is ever created without §4's full sequence
   succeeding end-to-end in one run.
2. **Corrected, round 1.** Step 6 (target host-key verification,
   `RejectPolicy`) unconditionally precedes step 7 (authentication/
   credential presentation) for every endpoint, with a test proving refusal
   before any authentication attempt against a deliberately untrusted
   endpoint. This criterion is **not** "credential resolution happens after
   trust" (steps 3–4 precede step 5 regardless, per §4/§Q3) — that stronger
   property is out of scope unless and until the named implementation
   prerequisite (a target-specific pre-connection host-key lookup, §Q3) is
   separately built and its own criterion added.
3. `identity_mapping_proven` is `1` only when set by the producer under
   rule 1, and is documented and tested as scoped to `mapping_scope` —
   never read or presented anywhere as general identity proof.
4. `console/registry_targets.py` never reads the registry's endpoint,
   hostname, or any `M6`-forbidden field to resolve a relationship — it
   only ever queries the `M4` table's read API by `device_id`, and never
   writes to it under any circumstance (a structural test, not only a
   documented rule).
5. An `INVALIDATED`/`SUPERSEDED` row is never returned by the resolver as
   if `ACTIVE`.
6. Every reason in §6 is reachable by a focused test and never paired with
   raw evidence (including the raw serial) in its detail string.
7. The `M4` ownership-boundary column-scan test is extended to cover the
   new table and still passes (no endpoint/credential/secret/`trust`-
   fragment column name) — a test specifically proving
   `authority_generations`/`serial_fingerprint` do not themselves
   regress this boundary.
8. `identity_mapping_proven`, `state`, `mapping_scope`, and
   `authority_generations`-currency together are the only signals `M6`'s
   resolver reads — no confidence/acceptance_basis string crosses that
   boundary.
9. Registry disable/retire is independently re-checked live at consumption
   time (Q7) even when a relationship row is otherwise `ACTIVE` — a
   disabled `device_id` refuses regardless of relationship state, and this
   check never mutates the relationship row (a structural test proving the
   resolver's read-only-ness, not only a documented rule).
10. **New, round 1.** A `physical`-scope row and a `vsx_virtual_system`-scope
    row for the same `device_id` may coexist `ACTIVE` simultaneously without
    triggering `AMBIGUOUS_IDENTITY` — a regression test proving normal VSX
    cardinality is never misclassified as ambiguity.
11. **New, round 1.** Two `ACTIVE` proofs for the *same* slot with
    different `entity_id` values are refused as `AMBIGUOUS_IDENTITY`; two
    proofs for the same slot and `entity_id` but different
    `serial_fingerprint` values are refused as `CONTRADICTORY_EVIDENCE`;
    neither the raw serial nor a plaintext comparison ever appears in any
    test assertion or log line — only the fingerprint.
12. **New, round 1.** A missing or rotated `data/.support_hmac.key` at
    comparison time causes the producer to treat the affected row's
    fingerprint as uncomparable (refuses to confirm or supersede on that
    basis) rather than silently treating a rotated key as a clean match.

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

**Consensus** (all five seats, original synthesis): the candidate-selection-
then-live-confirmation design (§Q1) is the smallest evidence-led design that
avoids both extremes — neither trusting endpoint/hostname equality alone
(rejected unanimously, it is exactly the evidence class the PO boundaries
name), nor requiring evidence this repository does not actually have
available today (bidirectional serial corroboration — confirmed absent,
§Q2). All five seats agreed the `M4` store, not the registry, is the correct
owner (§Q4), and that `identity_mapping_proven` must stay a binary bit,
never a confidence score (§Q1, §Q5).

**Correction round 1 (direct repository evidence only, council not
re-invoked per PO instruction) confirmed, sharpened, and in one place
directly corrected that consensus** — recorded here rather than as a new
seat opinion, since no council session produced it: the original synthesis
described the live first-contact confirmation as proving the relationship
outright; direct re-reading of `configuration/checkpoint_config_collector.py`
and the confirmed absence of independent CMA-side serial evidence (§Q2,
unchanged) show that the *final* `entity_id` binding still traces to
registry-endpoint == `management_ip` equality at its root (§Q1 point 3) —
the live session corroborates liveness/authenticity at that endpoint, not
independent confirmation of the CMA's own labeling. This is now made
structural via `mapping_scope` (§Q1 point 4, §Q5) rather than left as
prose. This correction **narrows** the original consensus; it does not
overturn the decision to build on candidate-selection-plus-live-
confirmation as the best available design, and does not change §Q4's
storage-owner conclusion or §Q10's roadmap order.

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
- **Named implementation prerequisite, not yet built (round 1)**: whether
  `M8`'s slice-2 producer should require a *stronger* trust guarantee than
  the existing collector seam provides — resolving a specific target's
  host key as already-trusted *before* even resolving/passing a credential
  for that target, rather than the existing protocol-level "not
  authenticated to an untrusted host" guarantee (§Q3/§4) — is left open. No
  such per-target pre-check seam exists in `utils/cp_ssh_trust.py` today.
  Building one is plausible future hardening specifically motivated by
  `M8`'s first-contact (never-before-contacted, manually-enrolled endpoint)
  scenario, but is not decided, designed, or assumed here; it is named so a
  later reviewer does not mistake the existing guarantee for the stronger
  one.

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
- No new per-target, pre-connection host-key lookup seam built or assumed
  to exist (§Q3/§12, round 1) — named as an open implementation question,
  not decided or designed here.
- **No general identity-authority claim.** `mapping_scope =
  'CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY'` is the entire authority this
  design produces (round 1); it is not, and must not be read as, evidence
  for `CLASS_2` operational state change, authorization, enrollment, or any
  security-identity purpose. Every "CLASS_0"/"CLASS 2" reference in this
  document is the `utils.action_taxonomy` operational-risk scheme, never
  the unrelated `PRIVACY_AND_DATA_HANDLING.md` data-sensitivity CLASS 0–3
  scheme (`AGENTS.md` "Privacy and DLP" names these as unrelated
  namespaces not to be conflated).
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
| A column literally named `trust_authority_generation` (round 1) | `tests/test_m4_control_plane_metadata_store.py::test_schema_owns_no_forbidden_concept` test-enforces `"trust"` as a forbidden column-name fragment; a cosmetically-renamed equivalent would evade the boundary's intent rather than honor it — replaced by reusing `capability_projections.authority_generations`'s already-approved TEXT/JSON shape with source-code version constants inside it (§Q5) |
| Persisting the raw device serial for later contradiction comparison (round 1) | Violates the Raw-evidence law and `PRIVACY_AND_DATA_HANDLING.md`'s `CLASS 2` classification of serial numbers directly; replaced by an HMAC-SHA256 fingerprint via the existing, already privacy-reviewed `data/.support_hmac.key` mechanism (`utils/support_bundle.py`), which supports equality comparison without ever storing or re-exposing the raw value (§Q5/§Q6/§7) |
| Treating a `device_id` with more than one `ACTIVE` `entity_id` row as inherently ambiguous (round 1) | Contradicted directly by `configuration/checkpoint_config_collector.py`'s own `_entity_id`/`_collect_host` model: one physical device legitimately produces one physical entity_id plus one per hosted VSX virtual system, in a single collector run — replaced by scoping ambiguity to same-slot (`device_id`/`vendor_namespace`/`entity_scope`/`vs_id_slot`) conflicts only (§Q5/§Q6) |
| Claiming the existing collector seam already gates credential resolution behind trust confirmation (round 1) | Direct reading of `configuration/checkpoint_config_probe.py::_connect` and `checkpoint_config_collector.py`'s once-per-run credential resolution shows this is not what the code does; replaced by the corrected, separately-named seven-step sequence (§Q3/§4) and an explicitly named, not-yet-built prerequisite for the stronger guarantee |
