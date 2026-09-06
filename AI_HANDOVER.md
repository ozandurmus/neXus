# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal.

---

## 1. Snapshot

- Date: 2026-09-07. `M8.1` — **AUTOMATED_VALIDATED**, branch
  `build/m8-1-relationship-storage-api` from `origin/main`, PR not yet
  opened/merged (awaiting user authorization).
- Build: `m8_1_relationship_storage_api` (`M8.1`) — `status:
  automated_validated`.
- Contract: `docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md`
  §5/§6/§8/§9 — FROZEN, unchanged by this session.

## 2. What this session did

Implemented `M8.1` per the frozen `M8` contract, scope exactly as named in
§9's table (storage/API only; no producer, no consumer).

1. **Migration 2** in `utils/control_plane_store.py`: new
   `device_identity_relationships` `STRICT` table (contract §5's exact
   field set) plus `ux_device_identity_relationships_one_active_per_triple`
   (partial unique index, at most one `ACTIVE` row per `(device_id,
   vendor_namespace, mapping_scope)`). `SUPPORTED_SCHEMA_VERSION` bumped
   `1` → `2`.
2. **New typed API** `utils/device_identity_relationships.py`: closed
   vocabularies (`VENDOR_NAMESPACES`, `MAPPING_SCOPES`, `PROOF_TYPES`,
   `PROOF_SOURCES`, `RELATIONSHIP_STATES`, `INVALIDATION_REASONS`);
   `DeviceIdentityRelationship` dataclass; `record_first_contact_proof`
   (transactional `NEW`/`SUPERSEDED`/`AMBIGUOUS_IDENTITY` write per §6 — no
   parameter exists to write an unproven or `identity_mapping_proven=0`
   row, by construction); `get_active_relationship` (fail-closed read,
   never returns `INVALIDATED`/`SUPERSEDED` as `ACTIVE`, defends against an
   unexpected multi-row read).
3. **Extended `tests/test_m4_control_plane_metadata_store.py`**: fixed the
   migration-count-sensitive tests that assumed exactly one migration
   (`test_creation_applies_the_initial_schema_version`, the
   monkeypatch-based prefix/gap/out-of-order-ledger tests — their synthetic
   extra migration moved from version 2 to 3 to stop colliding with the new
   real migration 2); extended `test_schema_owns_no_forbidden_concept`'s
   fragment list with `serial`/`host_key`/`fingerprint` per §8 AC6.
4. **New `tests/test_m8_1_device_identity_relationships.py`** — 31 tests:
   schema shape/STRICT/CHECK constraints, the structural
   one-`ACTIVE`-per-triple index, the three write outcomes (including
   no-tie-break-after-`AMBIGUOUS_IDENTITY`), read-side `None`/
   never-returns-invalidated/fail-closed-on-multiplicity, Python-layer
   closed-vocabulary and empty-identifier validation, and a structural
   assertion that the write API has no serial/endpoint/credential-shaped
   parameter.
5. **Project-state update**: `project/roadmap.json` (`now` = `M8.1`
   `automated_validated`; `next` = `m8_2_endpoint_specific_trusted_key_lookup`,
   `planned`), `project/build_history.json` (new head record),
   `CURRENT_STATE.md` (checkpoint, Active build, Exact next build, test
   baseline), `docs/history/INDEX.md` regenerated via
   `py scripts/build_history_index.py`.

## 3. Exact next action

**`M8.2` — endpoint-specific local trusted-key lookup.** The new, required
`utils/cp_ssh_trust.py` function (contract §4 step 1): checks whether the
exact normalized endpoint/port already has a trusted host-key entry in the
same `known_hosts` source `apply_strict_host_key_policy` already reads. No
network/device contact, no key added or accepted. Needs its own focused
tests, including a deliberately untrusted endpoint proving no credential is
ever resolved and no network/device contact occurs during the lookup
itself. `M8.3` (the producer) must not begin without `M8.2`.

Before that: **this session's PR is not yet opened.** `git status` is clean
against the new commit once made; branch `build/m8-1-relationship-storage-api`
is pushed nowhere yet — confirm with the user whether to push/open the PR
now or continue straight to `M8.2` first.

## 4. Test delta

New: `tests/test_m8_1_device_identity_relationships.py` (31 tests).
Extended: `tests/test_m4_control_plane_metadata_store.py` (same test count,
several bodies updated for the new real migration 2 — see §2.3 above).
Targeted run: 101 passed. Affected run (`M4`/`M8.1`/`M6`/`M5`/`PCP.1`/
`CON.2`/`test_architecture_convergence.py`): 388 passed, 0 failed. Privacy
gate: PASS, 0 findings (`data/`/`logs/` cleared before each run). No
`full-regression`/`workflow_dispatch` run (risk-based; this is a bounded
additive-schema change with no shared-core/runner/scheduler surface
touched).

## 5. Risks / notes forward

- This movement is `AUTOMATED_VALIDATED` only, by design — no device
  contact, no producer, no consumer exist yet to real-environment-validate.
- Closed vocabularies stay exactly as narrow as the frozen contract states;
  widening any of them (a PAN `vendor_namespace`, a second `proof_type`) is
  its own migration/contract amendment, not a parameter to loosen in
  `M8.1`'s API.
- `CONTRADICTORY_EVIDENCE` detection stays deliberately unbuilt (§6/§10) —
  do not add it as a side effect of `M8.2`/`M8.3`.
- Three dissents remain open, unchanged: `operator_assertion`,
  single-sourced identity evidence, deferred contradiction detection.
- Table/column names are no longer illustrative for `M8.1` specifically —
  they are now the real, committed schema; `M8.2`+ must read them from
  `utils/device_identity_relationships.py`, not re-derive them.
