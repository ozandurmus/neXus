# PAN HA serial identity hardening — decision document

## Status

**DECISION DOCUMENT — NOT FREEZABLE AS SCOPED. AWAITING PO DECIDE EPISODE.**
Not a contract, not implementation authority. Produced for
`project/backlog.json` id `pan_ha_serial_identity_hardening` (P2), per the
deferral recorded in
`docs/history/phase/OP_0A_PAN_HA_PEER_PAIRING_IDENTITY_CLOSURE.md` ("Explicitly
out of scope" / "Migrating PAN entity identity to `serial`") and the
forward-reference in `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §10.1 item
2 ("PAN requires the serial identity gate"). No code change accompanies this
document; `utils.restore_readiness.resolve_entity_id` and every consumer
named below are untouched.

**Owner:** Product Owner (architecture/security-boundary call). **Blocks:**
nothing today — `OP.2` (the only consumer of §10.1's identity gate) is not yet
in flight. This decision should land before `OP.2` contract freeze, not
before this movement's own merge.

---

## 1. The question, restated precisely

The backlog note and this movement's objective both frame the question as
"migrate `utils.restore_readiness.resolve_entity_id` from hostname-based to
serial-based for PAN." Section 3 below finds that framing conflates two
distinct identity concerns that this codebase already treats differently in
practice, and that distinction is itself the first thing this decision needs
to resolve before "migrate or not" can even be answered. Restated as two
separable questions:

- **Q-A.** Should `resolve_entity_id`'s PAN branch (the collector-identity
  namespace used by recovery, config history, and enrollment) key on
  `serial` instead of hostname?
- **Q-B.** Does `FAILOVER_ENGINE_ARCHITECTURE.md` §10.1 item 2's "PAN
  requires the serial identity gate" actually require Q-A's answer to be
  yes — or is it already satisfied by a different, narrower mechanism?

## 2. AC-1 — why the prior movement deferred this

`OP_0A_PAN_HA_PEER_PAIRING_IDENTITY_CLOSURE.md`'s "Explicitly out of scope"
section records the finding and the reason together: `serial` is already
collected on every PAN row in both parsers and is already this codebase's own
precedent for authoritative device identity
(`configuration/panorama_config_collector.py::_collect_direct_compare`'s
`identity_mismatch` check) — "a stronger anchor than hostname, which is
mutable (admin rename)." It was deliberately not done in that contract
because it "would touch `resolve_entity_id` and every PAN entity-id consumer,
which this narrow closure must not broaden into." The same document's
"Risks" section repeats the open risk: "Hostname-rename risk remains for the
underlying entity identity... `serial` is a stronger anchor, already
collected, already precedented in this codebase, deliberately not adopted
here." No design work toward *how* to migrate was done there — only the
observation that it's out of scope and the risk it defers. This movement is
that follow-up.

## 3. AC-2 — every real consumer of `resolve_entity_id`, found by grep

Non-test call sites of `utils.restore_readiness.resolve_entity_id` (its own
definition at `utils/restore_readiness.py:45`):

| # | File : line(s) | What it does with the value |
| --- | --- | --- |
| 1 | `utils/restore_readiness.py:189` | `compute_restore_readiness` — the function's own dedup key (`seen_entities`) and the `DeviceReadiness.entity_id` field it emits. |
| 2 | `utils/recovery_validation.py:101,104` | `_find_unified_device` — matches a recovery manifest's stored `device.entity_id` back against a fresh `unified.json` row, to run V3 inventory cross-checks (RB.4). |
| 3 | `utils/recovery_collect.py:20,115` | `select_recovery_targets` — builds the `entity_id -> row` map used both for `selector.mode == "all"` enumeration and to validate an explicit `entity_ids` selector list before any device is touched. |
| 4 | `utils/failover/assessment.py:681` | `_derive_cp_units` — CP/VSX entity id used to build `vs_rows` and physical-unit grouping. **Same function serves CP, not PAN**; relevant because this is the same shared resolver, not a PAN-only seam. |
| 5 | `utils/failover/assessment.py:892` | `_derive_pan_units` — keys `pan_rows`/`by_management_ip` for PAN HA mutual-pairing (`OP.0a.P7`, Q3/Q5). |
| 6 | `utils/failover/assessment.py:1019` | `_apply_pan_explicit_candidate` — re-derives `pan_rows` to validate an operator-supplied two-member candidate before building a bounded `HaUnit`. |
| 7 | `utils/failover/assessment.py:1078` | `derive_ha_units`'s `usable_rows` filter — a row is only "usable" (for either vendor) if `resolve_entity_id(row)` is truthy. |
| 8 | `application/workflows/preflight.py:394,408,416,440,445` | `_resolve_pan_operational_entity` / `pan_ha_preflight_check` — builds `selected_entity_ids`, the `by_entity_id` lookup, and each `PANPhysicalMemberTarget.physical_device_identity` passed into `panorama.preflight_collector.collect_member`. |
| 9 | `console/app.py:61,124` | `_known_entity_ids` — the set of currently-valid `entity_id`s an M9 enrollment request's `entity_ids` target_mode is validated against. |

Additionally, the **value** `resolve_entity_id` produces (not a call site of
the function, but a real consumer of what it returns) is persisted or keyed
on downstream in:

| # | File | How the value is used |
| --- | --- | --- |
| 10 | `utils/recovery_store.py:123-132` | `entity_id` is one path component of the on-disk recovery vault: `vault/<vendor>/<entity_id>/<utc_stamp>/`. This is a **persisted, on-disk key**, not a recomputed-every-run value. |
| 11 | `utils/config_storage.py` / `utils/config_history.py` | Config-snapshot history is looked up by the tuple `(source, entity_id, artifact_type)`. `ConfigSnapshotBackend.list_snapshots`/`get_snapshot` key on `entity_id` as given by the caller — this is a **persisted storage key**, and the caller chain for PAN traces back to `resolve_entity_id`'s value. |
| 12 | `utils/operate/store.py`, `utils/operate/record.py` | `operational_entity_id` (for PAN, `pan_explicit_candidate_unit_id`'s `"A+B"` join of two `resolve_entity_id` values) is the key for the CLASS_2 in-flight action lock and the quarantine record. Currently in-memory/session-scoped per `EntityActionInFlightError`/`EntityQuarantinedError`, but quarantine records are a durability candidate (see AC-4). |

Explicitly **not** a real consumer, confirmed by grep and by reading the
code: `utils/registry_evidence_reconciliation.py` only references
`resolve_entity_id` in a docstring, to explain that the Device Registry's
`device_id` and the collector's `entity_id` are deliberately different
concepts — it never calls the function. `utils/device_identity_relationships.py`
(the M8 `(device_id, entity_id)` relationship store) is a related but
separate mechanism, discussed in section 5 — it does not call
`resolve_entity_id` either, and its `VENDOR_NAMESPACES` closed vocabulary is
`("checkpoint",)` only: it does not cover PAN today. Test files that
reference `resolve_entity_id` (`test_op0b_s75_preflight_entrypoint.py`,
`test_op0a_ha_readiness.py`, `test_m6_registry_keyed_job_targets.py`) either
exercise consumer #8 directly or assert that a *different* code path (the
Device Registry resolver) must never call it — not independent consumers.

## 4. AC-2 continued — one shared function, two vendors

`resolve_entity_id` is not PAN-specific. Its body is:

```python
device = str(row.get("device") or "").strip()
vs_id = str(row.get("vs_id") or "").strip()
if row.get("source") == "vsx" and vs_id:
    return f"{device}__vsid_{vs_id}"
return device
```

CP/VSX rows take the first branch (physical endpoint + VSID, already
serial-independent, already frozen per `AGENTS.md`'s "VSX actual identity"
rule). PAN rows (`source == "panorama"`) fall through to the plain `return
device` branch — this is the only branch a PAN-serial migration would touch.
Consumers #4 and #7 above are shared across vendors; a scoped PAN-only change
inside the existing `if/else` does not alter CP behavior, but it means this
is a shared, actively-relied-upon function, not a small isolated PAN helper —
any change here is reviewed against both vendors' existing test coverage,
not just PAN's.

## 5. AC-3 — migration strategies and tradeoffs

### Option A — hard cutover: `resolve_entity_id` returns `serial` for PAN rows

Change the PAN fallthrough to `return str(row.get("serial") or "").strip() or
device` (never silently drop identity if `serial` happens to be blank on a
row — see the caveat below).

- **Consumers #1-3, #5-9: mechanical follow-through.** Each already treats
  `entity_id` as an opaque string; none of them number-coerce or
  case-normalize it. Swapping the string PAN rows resolve to does not break
  their logic, only their concrete values — including every existing test
  fixture that asserts a specific hostname-shaped PAN entity_id
  (`test_op0a_ha_readiness.py`, `test_op0b_s2_pan_projection.py`,
  `test_op0b_s6_pan_preflight_collector.py`,
  `test_op0b_s75_preflight_entrypoint.py`, and the `phase0_6_0a*` PAN
  fixture suite). This is the "touches every PAN entity_id consumer and
  existing tests" cost the prior movement named — real, but bounded and
  mechanical, not a design risk in itself.
- **Consumers #10-12: a real one-time data migration is needed, not just a
  fixture update.** Every already-persisted recovery vault directory,
  config-snapshot history key, and (if ever made durable) quarantine record
  for every existing PAN device was written under the current hostname-based
  key. The moment `resolve_entity_id` starts returning `serial`, every
  historical artifact for every PAN device — not just devices that get
  renamed — becomes unreachable under the new key unless it is migrated.
  This is **not** the same event as an admin renaming one device; it is a
  flag-day re-keying of the entire PAN evidence history at once. See AC-4
  for the sketch.
- **`serial` is not unconditionally hostname-independent in this codebase's
  own extraction today.** `configuration/panorama_config_collector.py:229`:
  `serial = (entry.findtext("serial") or entry.get("name") or "").strip()`
  — when Panorama's managed-device-discovery response omits `<serial>`, the
  code falls back to the `name` attribute, which is hostname-derived. A hard
  cutover to "PAN entity_id = serial" inherits this fallback's hostname
  dependency in exactly the case (missing serial) it is least equipped to
  handle honestly — it would look like a clean serial-based identity while
  silently still being hostname-based for that row. Any migration must
  either treat a missing hardware serial as unresolvable identity (fail
  closed, consistent with `resolve_entity_id`'s existing "never guess"
  posture) or explicitly document the fallback as a known, bounded
  exception — it must not be glossed over.
- **The open P0 backlog item is a live blocker on trusting `serial` as a
  foundation right now.** `project/backlog.json` id
  `pan_serial_representation_identity_evidence_closure` (status
  `in_progress`, P0, `AWAITING_PO`) records a real-environment run where one
  member of an approved PAN HA pair showed `self_identity_consistent =
  MISMATCH` and `runtime_peer_serial_state = MISMATCH` against the *same*
  serial-identity comparison this migration would newly rely on as
  authoritative, with **root cause not yet determined**
  (`docs/design/PAN_SERIAL_REPRESENTATION_IDENTITY_EVIDENCE_CLOSURE.md`).
  Adopting `serial` as the authoritative collector-identity namespace while
  that item is open risks building the identity system on the exact
  primitive currently under active doubt — this is not a hypothetical risk,
  it is an already-observed anomaly on real hardware.

### Option B — dual-identity / compatibility period

Keep `resolve_entity_id` hostname-based (no change to any of the nine call
sites), and separately extend the existing M8
`utils/device_identity_relationships.py` mechanism — today CP-only
(`VENDOR_NAMESPACES = ("checkpoint",)`) — to PAN, recording a proven
`(device_id, entity_id)` binding per device and superseding it when a fresh
first-contact read shows the same `device_id` now producing a different
`entity_id` (the same `ACTIVE` → `SUPERSEDED` transition M8 already defines
for CP). This gives a **detectable, provable** hostname-rename event without
an atomic flag day, and reuses a precedented, already-reviewed schema rather
than inventing a new one.

- Tradeoff: this does not close the underlying risk — `resolve_entity_id`
  stays hostname-based, so consumers #10-12 still fork history at every
  rename, indefinitely, not just once. It converts an invisible failure mode
  (silent identity drift) into a *visible, detected* one, which is real
  progress, but is not the same as removing the mutability risk.
  `IDENTITY_DERIVATION_CONTRACT_VERSION` and `PROOF_TYPES` are both written
  as CP-specific closed vocabularies today (`checkpoint_physical_entity_id.v1`,
  `first_contact_identity_gate_and_serial`); extending them to PAN is itself
  new contract surface, not a parameter change, and needs its own design
  pass (PAN's "first contact" shape is different — PAN already carries
  `serial` on every collected row without a separate identity-gate dial-out
  the way CP's M8.3 producer requires).

### Option C — narrow the fix to the operational-identity axis only, leave `resolve_entity_id` alone

Re-reads `FAILOVER_ENGINE_ARCHITECTURE.md` §10.1 item 2 literally: *"CP
identity is physical endpoint + VSID; PAN requires the serial identity
gate."* This names a **gate** — a fresh, execution-time verification — not a
namespace migration. That gate already exists in nascent form:
`panorama/preflight_collector.py::collect_member`'s "P1: identity gate" does
`accepted = bool(observed_serial) and observed_serial ==
target.expected_serial` (exact string comparison, no normalization, per the
opaque-identifier law), and `PANPhysicalMemberTarget` already carries
`expected_serial` **alongside**, and distinct from,
`physical_device_identity` (which *is* `resolve_entity_id`'s hostname-based
value, passed through from `application/workflows/preflight.py`). In other
words: this codebase already separates "the label evidence is attributed
under" (hostname-based, stable enough for presentation and recovery/config
lookup) from "the proof used at the moment a `CLASS_2` action is about to be
authorized" (serial, freshly re-verified, never persisted as an identity
claim). Under this reading, §10.1's requirement is already substantially
built, and it was built without touching `resolve_entity_id` at all.

- Tradeoff: this leaves the hostname-mutability risk against
  `resolve_entity_id` (consumers #1-3, #10-12) completely open — a rename
  still forks recovery/config history the same way it does today. It
  answers **Q-B** (the `OP.2` forcing requirement) without answering **Q-A**
  (whether the broader identity-hardening the backlog note describes is
  worth doing). If Q-A is judged worth doing anyway, on its own architectural
  merits, Option A or B still apply on top of this.

## 6. AC-4 — does a hostname-rename-in-place need a data migration for already-persisted records?

**Yes — and this is true today, independent of which option above is chosen.**
An admin renaming a PAN device's hostname causes the *next* collection run to
produce a new `resolve_entity_id` value for the same physical device, right
now, with zero migration mechanism in place for it:

1. `utils/recovery_collect.py`/`utils/recovery_store.py` start writing new
   backups under `vault/<vendor>/<new_entity_id>/...`; every previously
   collected artifact remains on disk under `vault/<vendor>/<old_entity_id>/...`,
   invisible to any future `select_recovery_targets`/retention lookup keyed
   on the current entity_id — retention and recovery-selection code has no
   way to know the two directories are the same device.
2. `utils/config_storage.py`/`utils/config_history.py`'s snapshot timeline
   for that device forks at the rename boundary: pre-rename and post-rename
   snapshots are stored under different keys and a history/diff view over
   "this device" silently only shows one side.
3. `utils/operate/store.py` quarantine is in-memory/session-scoped today
   (confirmed by reading the module), so a rename mid-process would only
   affect it for that process's lifetime — but if quarantine is ever made
   durable (a reasonable direction once `CLASS_2` ships), a stale
   `operational_entity_id` in a persisted quarantine record would silently
   stop applying to the renamed device, a fail-open safety gap worth
   flagging now even though it is not yet realized.
4. There is no PAN equivalent of the M8 `device_identity_relationships`
   supersession mechanism today (`VENDOR_NAMESPACES` is CP-only) — so unlike
   a hypothetical CP re-identification, a PAN rename has **no existing
   reconciliation path at all**.

**Sketch of a migration, design-only, not authorized to implement**, for
whichever future contract picks this up (applies whether the trigger is a
one-time Option A cutover or an ordinary in-place rename under Option B/C):

1. Build a `hostname_entity_id -> serial` map from the most recent
   collection run that still has the pre-rename hostname on file (or, for a
   full Option A cutover, from every historical run's most recent PAN rows).
2. For each mapped entity: locate every `vault/<vendor>/<old_entity_id>/`
   directory and move/relink it under the serial-keyed (or new hostname-
   keyed) path; same operation for the config-history backend's stored keys.
3. Re-key any persisted quarantine/lock record for that entity, if such
   persistence exists by the time this runs.
4. **Unreconcilable case, explicit and fail-closed:** a historical directory
   whose old entity_id no longer resolves to any row in current inventory
   (decommissioned device, or a rename that happened before serial was ever
   collected for it) has no automatic target. Per the opaque-identifier law,
   this must never be guessed or best-matched — it is left as an orphaned,
   clearly-labeled artifact pending manual/inventory-informed reconciliation,
   or explicitly retained under its original key with a recorded rename
   history, never silently merged into a new device's history.

This sketch is real engineering work — a own contract, its own tests, its
own review — not something to fold into whichever movement picks the
migration itself up.

## 7. AC-5 — recommendation

**Not freezable now.** Three independent reasons, each PO-level on its own:

1. **Scope-fit is unresolved (Q-A vs Q-B).** Section 5's Option C finding —
   that `FAILOVER_ENGINE_ARCHITECTURE.md` §10.1's actual forcing requirement
   is a fresh execution-time serial gate, already substantially built,
   rather than a `resolve_entity_id` namespace migration — changes what "this
   decision" is even about. Whether the PO wants the broader identity
   hardening the backlog note describes, as a separate architectural
   investment, or considers §10.1 satisfied by what already exists, is a
   product/architecture call this document cannot make for them.
2. **`pan_serial_representation_identity_evidence_closure` (P0,
   `AWAITING_PO`) is open and directly undercuts trusting `serial` as a
   foundation today.** A real-environment MATCH/MISMATCH asymmetry on the
   same serial-identity comparison this migration would elevate to
   authoritative status has an undetermined root cause. This should resolve
   — or at minimum be explicitly accepted as a known, bounded risk by the PO
   — before Option A or B is adopted, regardless of which is chosen.
3. **The blast radius is real and the persisted-record migration (AC-4) is
   substantial, cross-cutting engineering work** — recovery vault
   directories, config-history storage keys, and a not-yet-existing PAN
   extension to the M8 identity-relationship mechanism. This is its own
   contract's worth of design and implementation, not a follow-on patch.

**If forced to a preliminary lean:** Option C (narrow the fix to confirming/
hardening the existing execution-time serial gate, leave `resolve_entity_id`
untouched) most directly satisfies §10.1 with the least blast radius and no
data-migration exposure, and does not foreclose Option A/B later if the PO
separately wants the broader hardening. This is a lean, not a recommendation
ready to freeze — it does not resolve reason 1 above (whether the PO
considers Q-A worth pursuing on its own merits), and it does not touch
reason 2 at all.

**This needs a PO DECIDE episode**, given the blast radius across nine real
consumers plus three persisted-record stores, the open P0 dependency, and the
scope-fit ambiguity this document surfaces rather than resolves.

## 8. Scope

**In scope (this document):** the consumer inventory, the migration-strategy
options and their tradeoffs, the hostname-rename data-migration sketch, and
the recommendation above.

**Out of scope:** any change to `resolve_entity_id` or any consumer named in
section 3; any change to `utils/device_identity_relationships.py`'s
`VENDOR_NAMESPACES`; the actual persisted-record migration sketched in
section 6; resolving `pan_serial_representation_identity_evidence_closure`.
