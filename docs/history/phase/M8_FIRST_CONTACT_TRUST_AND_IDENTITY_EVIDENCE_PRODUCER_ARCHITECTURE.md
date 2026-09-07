# `M8` — First-contact trust and identity-evidence producer (bounded contract)

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-06.** Authorizes the design
below; authorizes **no code, no schema migration, no device contact, no
Device Registry relationship write**. Implementation proceeds only through
the sequence in §9, each slice its own separately reviewed movement.

Parent authority (unamended, subordinate per `AGENTS.md` "Authority
hierarchy"): `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §21
(Device Registry, FROZEN); `docs/history/phase/M4_LOCAL_CONTROL_PLANE_METADATA_STORE.md`
(control-plane SQLite store, IMPLEMENTED); `project/roadmap.json` decisions
`pcp_local_control_plane_storage` (Option A) and `pcp_first_contact_trust_policy`
(names `M8` as the trust-establishment movement, no new credential/network
path). `GOV.SESSION.1` is unrelated and untouched.

Produced by a bounded `nexus-decision-council` synthesis, then corrected
across two PO architecture-review rounds using direct repository evidence
only (council not re-invoked for either round) and compacted into this
normative form for the freeze. **Round-by-round correction narrative is not
repeated here** — it lives in `project/build_history.json`'s head record
and this branch's PR commits; this document states only the final,
approved design.

| | |
| --- | --- |
| **Movement** | `ARCHITECTURE`, `FROZEN` |
| **Baseline** | `main` at `0a9048ceeb2a318444f918e2688b126641eaeab0` |
| **Preserves unchanged** | `PCP.1` Device Registry frozen field set and `relationships: []` structural invariant; `M6` fail-closed admission shell; `M4` schema version 1's seven `STRICT` tables and its forbidden-column-fragment test; `data/.support_hmac.key`'s own support-bundle-only purpose (not reused here); one Console, one registry, one identity authority |

---

## 1. Problem

`M6` resolves a console-submitted `config_refresh_cp` `device_id` against the
`PCP.1` Device Registry, but every otherwise-eligible target refuses with
`IDENTITY_TRANSLATION_REQUIRED`: no legitimate producer of a `device_id` →
collector `entity_id` relationship exists. `entity_id`
(`configuration/checkpoint_config_collector.py::_entity_id`) is a distinct
identity space from `device_id` — a value from Check Point management-plane
(CMA/MDS) discovery — never console-submitted, never derived from the
registry.

**Targeting is physical-only.**
`configuration/checkpoint_config_collector.py::_apply_cp_target_selector`
indexes candidates only by `_entity_id(target)` with no `VsContext`
argument — a VSX child `entity_id` is never independently selectable.
`M8`'s relationship model is therefore a plain **one-to-one**
`device_id` ↔ physical-`entity_id` association.

`M7` (real device-targeted Collect now) cannot be built on `M6` as-is — it
has nothing legitimate to target. `M8` is the prerequisite; the corrected
order is `M6 → M8 → M7` (§9).

---

## 2. Authority / ownership model

Four identity spaces stay related, never unified: `device_id` (registry),
`canonical_id` (discovery lifecycle), `entity_id` (evidence/collector),
`operational_entity_id` (topology/HA). `M8` adds **no fifth authority** and
**no second producer** of any existing one:

- `device_id` stays sole property of `utils/device_registry.py` — opaque,
  never derived from an endpoint, hostname, or serial.
- `entity_id` stays sole property of the existing collector's frozen
  physical derivation (`_entity_id(target)`, no `VsContext`).
- The relationship `(device_id, entity_id)` is a new, narrow, third object
  — a record that a specific evidence-backed run bound them, never an
  identity itself.
- **Storage**: the already-approved `M4` SQLite store, an additive
  migration inside its already-DECIDED "control-plane runtime metadata"
  scope — not a new engine, not a `PCP.1` amendment.
- **Transport**: the existing `utils/cp_ssh_trust.py` seam plus one
  required addition (§4) — no new credential path, no new device command.
- **Evidence**: the relationship never copies the endpoint, serial, or
  trust material into `control_plane.db`. It references the CP config
  collector's own already-governed per-run evidence and the registry's own
  existing revision signal (§5).

---

## 3. Evidence model

**Rejected as proof, individually or combined:** endpoint/address equality,
hostname equality, display-name equality, vendor-hint equality, spelling
equality, `operator_assertion`, and the collector's own `confidence`/
`acceptance_basis` string (that gate answers "safe to read configuration
from," not "is this the entity the registry means").

**Accepted sequence:**

1. **Candidate selection (hint only).** The registry's normalized endpoint
   for `device_id` D selects the one CMA/MDS-discovered `PhysicalTarget`
   whose `management_ip` matches — a hypothesis (`classification_basis =
   management_discovery`), never proof. No match → `IDENTITY_TRANSLATION_REQUIRED`.
2. **Live, direct, identity-gated confirmation**, gated by the mandatory
   trust lookup (§4), against that one candidate — the existing,
   unmodified per-host collector logic (`_collect_host`/`_identity_gate`/
   `_collector_identity_gate`), driven by a new, minimal, single-target
   entry point (§6), not the bulk multi-target orchestration.
3. **What this proves.** The identity gate's `accepted = True` plus a
   directly-read serial together prove a real, live, authenticated Check
   Point device answers at the registry's declared endpoint — direct-device
   evidence of liveness/authenticity there. They do **not** independently
   corroborate that this device is the object the CMA/MDS labels with the
   candidate `entity_id`: no repository evidence source ties the CMA's
   `entity_id`↔serial pairing to anything other than the endpoint-equality
   hypothesis that selected the candidate (confirmed: no CMA/MDS
   management-plane read anywhere exposes a device serial today). The
   *final* binding still traces to registry-endpoint == `management_ip`
   equality at its root.
4. **Serial is mandatory, not optional, for a proven row.** `proof_type`
   is fixed to `first_contact_identity_gate_and_serial` — both halves are
   required together. If the identity gate accepts but no usable serial is
   read: **no relationship row is written**, `IDENTITY_TRANSLATION_REQUIRED`
   stands, and the producer records a sanitized missing-evidence reason
   (e.g. `identity_gate_accepted_serial_unavailable`) in its own run
   outcome/log — never in the `M4` table, never as a downgraded or
   weaker-but-accepted `proof_type`, never silently treated as sufficient.
5. **Consequence: a narrow, machine-readable scope, not general identity
   proof.** Every row carries `mapping_scope =
   CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY` — the `utils.action_taxonomy`
   `CLASS_0_READ` sense (never `PRIVACY_AND_DATA_HANDLING.md`'s unrelated
   CLASS 0–3 scheme). `identity_mapping_proven = 1` means "proven for
   `mapping_scope`" only; every read API must expose the two together,
   never a bare boolean. Explicitly prohibited as authority for: `CLASS_2`
   operational state change, any authorization decision,
   `operational_entity_id` derivation, Device Registry enrollment, or any
   security-identity purpose.
6. **Single-sourced, disclosed.** No independent management-plane serial
   source exists to corroborate this bidirectionally (unlike the PAN HA
   `B2` model) — a standing, disclosed limit (§10).

No second collector, no new `entity_id` derivation, no synthetic target
name.

---

## 4. Mandatory trust-before-credential sequence

The existing collector seam alone is insufficient for first contact: its
trust preflight (`apply_strict_host_key_policy`) only confirms *some*
trusted material exists anywhere, and the bulk collector resolves its
credential once per run, **before** any per-target check
(`checkpoint_config_collector.py:1976-1978`, ahead of
`_apply_cp_target_selector`). `M8`'s producer therefore cannot reuse the
bulk entry point and must be a new, minimal, single-target driver following
this exact order:

```
1. Target-specific trusted-key lookup (NEW, required, blocking).
   A new function in utils/cp_ssh_trust.py checks whether the exact
   normalized endpoint/port already has a trusted host-key entry in the
   same known_hosts source apply_strict_host_key_policy already reads.
   No network/device contact. No key added or accepted. Not found ->
   refuse; no credential is ever resolved for this target.
2. Credential/secret resolution (existing DEV.2.1/DEV.2.2 source,
   invoked per-target) -- only reached after step 1 succeeds.
3. ssh.connect() with RejectPolicy (existing, unchanged).
4. Target host-key verification during the handshake (existing;
   redundant with step 1 for this connection, intentionally so).
5. Authentication (existing, unchanged).
6. Identity reads: show hostname / asset command, via the existing,
   unmodified _collect_host / _identity_gate / _collector_identity_gate /
   _parse_asset_semantic(..., "serial"), plus the already-captured
   _host_key_fingerprint -- all persisted as part of this run's ordinary,
   already-governed CP config evidence, not a new evidence type.
7. Positive-evidence decision (§3 point 4): gate accepted AND serial
   present -> proceed to write. Otherwise -> no row, no credential/serial
   ever leaves this producer's process, IDENTITY_TRANSLATION_REQUIRED
   stands.
8. Relationship write, single-writer (producer only), transactional --
   never into the Device Registry, never duplicating the endpoint, serial,
   or trust material.
```

No TOFU, no automatic host-key acceptance, no compatibility-mode carve-out
for a non-corroborated endpoint. Step 1 must exist and be independently
tested before any producer code exists (§9, `M8.2` gates `M8.3`).

---

## 5. Relationship schema (`M4` additive migration, illustrative names)

New table (e.g. `device_identity_relationships`) inside the already-owned
`control_plane.db`, schema version 2, via `M4`'s existing migration ledger.
Rejected: the Device Registry's `relationships: []` field (would reopen the
`PCP.1` §21 frozen field set); a new store/engine (already-decided scope
covers this); folding into `capability_projections` (a distinct concern,
would silently widen its frozen columns).

| Field | Type | Purpose |
| --- | --- | --- |
| `relationship_id` | PK, opaque | row identifier only |
| `device_id` | text, `NOT NULL` | opaque `PCP.1` value; not a FK — re-checked live at consumption (§7) |
| `entity_id` | text, `NOT NULL` | opaque, physical only, exactly as `_entity_id(target)` produced it |
| `vendor_namespace` | text, `CHECK IN ('checkpoint')` | closed; a future PAN value needs its own migration |
| `mapping_scope` | text, `CHECK IN ('CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY')` | closed; always paired with `identity_mapping_proven` in every read API |
| `producing_run_ref` | text, `NOT NULL` | opaque reference to the producing run's already-governed CP config evidence (serial + host-key fingerprint live there, not here) |
| `registry_record_revision` | text, `NOT NULL` | the registry's own `DeviceRecord.updated_at`, captured at proof time; compared live against the current record at every resolution |
| `proof_type` | text, `CHECK IN ('first_contact_identity_gate_and_serial')` | closed; both halves mandatory (§3 point 4) |
| `proof_source` | text, `CHECK IN ('direct_device_read')` | evidence grade, never a confidence score |
| `identity_derivation_contract_version` | text, `NOT NULL` | source-code constant, bumped when `_entity_id`'s contract changes — mirrors `capability_projections.producer_version` |
| `identity_mapping_proven` | integer `0`/`1`, `NOT NULL` | binary only; scoped to `mapping_scope`, never general identity proof |
| `observed_at_utc` | text, `NOT NULL` | when the producing run observed the evidence |
| `state` | text, `CHECK IN ('ACTIVE','INVALIDATED','SUPERSEDED')` | durable states only (§6) |
| `invalidation_reason` | text, nullable | closed vocabulary (§7), never a raw exception |
| `created_at_utc` / `updated_at_utc` | text, `NOT NULL` | standard audit pair |

`UNIQUE (device_id, vendor_namespace, mapping_scope) WHERE state = 'ACTIVE'`
— at most one `ACTIVE` physical association per triple. A different
`entity_id` proven for the same triple is `AMBIGUOUS_IDENTITY` (§6) — the
producer must detect this explicitly; the index alone cannot.

No serial, host-key fingerprint, endpoint, or trust material is ever a
column here — proven by extending `M4`'s own ownership-boundary column-scan
test (§8).

---

## 6. Creation, consumption, invalidation

**Only the producer writes to this table** — never `console/`, never a
background sweep. A durable `state` write happens only inside the
producer's own transaction, at the moment a new proof attempt discovers the
condition.

**Logical, read-time invalidity — computed live by `M6`'s resolver, never a stored mutation:**
registry disable/retire or the registry record's live `updated_at` no
longer matching `registry_record_revision`; the current trusted host-key
fingerprint for the endpoint no longer matching the fingerprint
`producing_run_ref`'s evidence captured (including that evidence becoming
unresolvable at all — treated as unusable, never a confirmed match);
`identity_derivation_contract_version` staleness. All folded into
`IDENTITY_TRANSLATION_REQUIRED` at resolution (§7) — deliberately not split
into finer reasons that would leak which precondition failed.

**Durable, producer-written transitions:**
- `AMBIGUOUS_IDENTITY` — a new proof for the same triple yields a different
  `entity_id`; both the old and rejected new claim move to `INVALIDATED` in
  the same transaction. No recency/heuristic tie-break, ever.
- `SUPERSEDED` — a fresh proof for the same triple agrees with the existing
  `ACTIVE` row's `entity_id` (e.g. after a currency loss); the old row is
  superseded, the new row inserted, same transaction.
- **`CONTRADICTORY_EVIDENCE` is deferred, not built.** Comparing a new
  serial against an old `ACTIVE` row's requires both to remain resolvable
  under this repository's existing CP config evidence retention policy —
  unmeasured by this architecture. `M8.3` (§9) must measure it before this
  gap can be closed either way; no new key-management/fingerprint-storage
  mechanism is invented to work around the uncertainty in the meantime.

Corrupt store or unreadable table → `RELATIONSHIP_STORE_UNAVAILABLE`, never
misreported as "no relationship exists." Never a physical delete.

---

## 7. `M6` resolution (admission + pre-execution)

Extends `M6`'s existing registry-only checks
(`UNKNOWN_DEVICE_ID`/`DEVICE_NOT_ELIGIBLE`, unchanged) with a **live,
read-only** lookup, performed identically at admission
(`console/app.py`'s `POST /api/jobs`) and immediately before execution
(`console/runner.py`), server-side, before any credential presentation or
device contact:

- exactly one `ACTIVE` row for `(device_id, vendor_namespace,
  mapping_scope)`;
- `registry_record_revision` currency (§6);
- `identity_derivation_contract_version` currency (§6);
- trust currency: current trusted-key fingerprint for the live registry
  endpoint (via §4's lookup — a local `known_hosts` read, no device
  contact) agrees with `producing_run_ref`'s captured fingerprint, and
  that evidence is still resolvable.

Any failure/absence → `IDENTITY_TRANSLATION_REQUIRED`; store fault →
`RELATIONSHIP_STORE_UNAVAILABLE`; more than one qualifying row (should be
structurally prevented by §5's index) → fail closed, never a heuristic
pick. No endpoint, hostname, serial, or trust material is ever copied into
`console/`, and `console/` never writes to the relationship table. Whether
`console/registry_targets.py` imports the trust-lookup function directly or
through a vendor-neutral wrapper (preserving "console imports no
vendor/collector module") is an open implementation-shape question for
`M8.4`.

---

## 8. Privacy, concurrency, acceptance criteria

**Privacy**: every column in §5 is opaque or closed-vocabulary — no serial,
fingerprint, endpoint, or trust material. The table lives inside
`control_plane.db` (`CLASS 2`, already `.gitignore`d, already excluded from
`support_bundle.py`'s enumeration by construction). `data/.support_hmac.key`
is untouched and unreferenced.

**Concurrency/idempotency**: single-writer via `M4`'s existing
`busy_timeout`/`WAL` posture; a concurrent producer run for the same triple
fails closed on contention, never queues. Re-proof with an unchanged
`entity_id` is a no-op or `SUPERSEDED`; a different `entity_id` is
`AMBIGUOUS_IDENTITY`. `AMBIGUOUS_IDENTITY`/`SUPERSEDED` writes happen in the
same transaction as the new row's insert — a crash mid-write never leaves
one without the other.

**Acceptance criteria (focused):**

1. No row is ever created without §4's full sequence succeeding end-to-end,
   including a present serial (§3 point 4).
2. §4 step 1 (trust lookup) unconditionally precedes step 2 (credential
   resolution) — tested with a deliberately untrusted endpoint, proving no
   credential is ever resolved and no network/device contact occurs during
   the lookup itself.
3. `identity_mapping_proven` is never returned without `mapping_scope` in
   the same call.
4. `console/registry_targets.py` never writes to the relationship table
   (structural test) and never reads registry endpoint/hostname to resolve
   it.
5. An `INVALIDATED`/`SUPERSEDED` row is never returned as `ACTIVE`.
6. `M4`'s ownership-boundary column-scan test, extended, still passes — no
   endpoint/credential/secret/`trust`-fragment column, no serial or
   host-key column of any kind.
7. Two `ACTIVE` proofs for the same triple with different `entity_id`
   values are refused as `AMBIGUOUS_IDENTITY`, no tie-break.
8. Registry-record, identity-derivation, and trust currency are each
   independently testable and each independently force
   `IDENTITY_TRANSLATION_REQUIRED`.
9. A relationship whose `producing_run_ref` evidence is unresolvable is
   treated as unusable, never as confirmed.
10. Gate-accepted-but-serial-absent writes no row, leaves
    `IDENTITY_TRANSLATION_REQUIRED`, and is observable only via the
    producer's own sanitized run-outcome record — never in `control_plane.db`.

---

## 9. Implementation sequence and roadmap order

**`M6` → `M8.1` → `M8.2` → `M8.3` → `M8.4` → `M7` → remaining
enrollment/capability movements.** Each slice is its own reviewed
movement, independently testable and revertable; names are illustrative,
not frozen movement IDs beyond this ordering.

| Slice | Scope | Gate |
| --- | --- | --- |
| **`M8.1`** — relationship storage/API | The `M4` additive migration (§5), closed vocabularies (§6), typed read/write API, ownership-boundary test extension. No producer, no consumer wired up. | none — the exact planned **NEXT** movement |
| **`M8.2`** — endpoint-specific local trusted-key lookup | The new, required `utils/cp_ssh_trust.py` function (§4 step 1), its own focused tests. | `M8.1` complete |
| **`M8.3`** — read-only first-contact producer + real-environment gate | The new, minimal, single-target driver (§3/§4), reusing existing per-host primitives; proposes exactly one bounded, read-only real-environment validation command. **Must not begin without `M8.2`.** | `M8.2` complete |
| **`M8.4`** — `M6` resolver consumption | The `console/registry_targets.py` extension (§7): registry/identity-derivation/trust currency, all live, read-only. | `M8.3` complete, including at least one real-environment-validated relationship |
| **`M7`** — real device-targeted Collect now | Functional per-device `config_refresh_cp`. **Stays blocked** until `M8.4` exists and the required real-environment evidence exist. | `M8.4` complete + real-env evidence |

**Real-environment gate** (`M8.3`): automated/fixture tests prove the code
path, never the vendor claim that a live session yields a stable, correct
`entity_id` pairing, nor the operational fact of how long CP config evidence
stays resolvable. `M8.3` must measure real evidence-retention duration
(determining whether `M8`'s deferred contradiction detection can ever be
built) and must not be marked `REAL_ENV_VALIDATED` from automated tests
alone. `M7` must not target a relationship whose only evidence is
`AUTOMATED_VALIDATED`.

---

## 10. Unresolved dissent and risks (carried forward, not closed by this freeze)

- **`operator_assertion`** remains an OPEN dissent, inherited from `M6`.
  This design does not require or accept it.
- **Single-sourced evidence**: no independent management-plane serial
  source corroborates the direct-device read (unlike PAN HA's `B2` model).
  Not blocking; reopen only if CMA/MDS is ever found to expose a usable
  corroborating field.
- **Deferred contradiction detection**: whether a later run's serial can
  ever be honestly compared against an earlier proof depends on CP config
  evidence retention behavior `M8.3` must measure. If retention cannot
  support it, this design accepts staying without contradiction detection
  rather than inventing new key management.
- **Console/trust-module import boundary** (§7): left to `M8.4`.
- **`vendor_namespace` stays closed** (`checkpoint` only) — a future PAN
  producer adds its own value via its own migration, not a premature open
  constraint.

---

## 11. Non-goals

No production code, test, schema migration, or device contact in this
freeze. No `PCP.1` frozen-contract amendment (none needed). No Device
Registry write, enrollment change, or automatic enrollment. No `M7`
implementation or UI affordance. No PAN/Palo Alto producer design
(`utils/pan_tls_trust.py` symmetry noted, not designed). No `CLASS_2`
mutation, authorization/RBAC change, new credential/secret storage, new
network path beyond the existing `cp-config` collector's own, or any reuse
of `data/.support_hmac.key` beyond its existing support-bundle purpose. No
`CONTRADICTORY_EVIDENCE` mechanism built or assumed. No general
identity-authority claim — `mapping_scope` is the entire authority this
design produces. No table/column name or migration version number is
frozen beyond the ordering in §9 — each implementation slice re-derives the
exact shape against the real repository state at that time.

---

## 12. Amendment (2026-09-07) — Product Owner sequencing: `M8.4` authorized ahead of `M8.3`'s real-environment gate

Source authorization: Product Owner instruction, `M8.4` `SESSION_START`
packet, 2026-09-07. This amends only the §9 sequencing table's stated gate
for `M8.4` (previously: "`M8.3` complete, including at least one
real-environment-validated relationship") — nothing else in this contract
changes; no existing check, outcome, schema field, or acceptance criterion
is reinterpreted.

- **`M8.3`'s real-environment validation is deferred to backlog**, at
  Product Owner discretion (manual/real-device validation requests default
  to backlog deferral absent an explicit instruction otherwise). This
  deferral does **not** promote `M8.3` to `REAL_ENV_VALIDATED` or `DONE` —
  it stays `AUTOMATED_VALIDATED`, and the deferred command
  (`py main.py --identity-first-contact <device_id>`) remains open
  validation debt (`project/backlog.json`).
- **`M8.4`'s fail-closed implementation and automated validation are
  explicitly authorized to proceed before that gate closes.** `M8.4`'s own
  build status may reach `AUTOMATED_VALIDATED` — never further — without
  waiting on `M8.3`'s real-environment evidence.
- **`M7` ("real device-targeted Collect now") remains blocked regardless.**
  Its own §9 gate ("`M8.4` complete + real-env evidence") is unchanged and
  is strictly stronger than `M8.4`'s amended gate above: `M7` requires an
  actually real-environment-validated relationship, and no automated
  fixture or synthetic relationship may ever satisfy that specific
  requirement. `M8.4` opening its own admission gate for a genuinely
  identity-resolved target does not, by itself, make `config_refresh_cp`
  functional for a real device — no wiring exists (nor is any added by
  `M8.4`) that substitutes a resolved `entity_id` into the collector's
  actual target list; that substitution is `M7`'s own, separately gated
  scope.
- This is a narrow sequencing amendment only. Every other frozen provision
  in this document — the evidence model (§3), the mandatory
  trust-before-credential sequence (§4), the schema (§5),
  creation/consumption/invalidation (§6), `M6` resolution (§7),
  privacy/concurrency/acceptance criteria (§8), and non-goals (§11) — is
  unchanged and unamended by this entry.
