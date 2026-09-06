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

- Date: 2026-09-06. Branch `architecture/m8-first-contact-trust-identity-evidence`
  (PR #96, open, not merged) from verified `main` at
  `0a9048ceeb2a318444f918e2688b126641eaeab0` (`M6` merged).
- Build: `m8_first_contact_trust_identity_evidence_producer_architecture`
  (`M8`, architecture review) — **DRAFT / IN REVIEW**, not implemented, not
  automated_validated, not yet PO-approved for freeze or merge, corrected
  across two PO review rounds.
- Contract: `docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md`
  — fully rewritten in round 2 to its final, clean state (round-1 inline
  correction markers removed from the body; history kept in the doc's
  Status-block "Correction history" and in `project/build_history.json`).

## 2. What this session did (correction round 2, PO architecture review)

The PO returned five further blockers against the round-1-corrected draft —
**`nexus-decision-council` not re-invoked**, direct repository evidence
only, per instruction. `GOV.SESSION.1` stayed parked/untouched.

1. **Cardinality corrected to physical-only.** Confirmed by reading
   `configuration/checkpoint_config_collector.py::_apply_cp_target_selector`
   that it indexes candidates only by `_entity_id(target)` with no
   `VsContext` argument — `M6`/`M7` targeting never independently selects a
   VSX child `entity_id`. Round 1's `entity_scope`/`vs_id_slot` one-to-many
   model solved a problem that does not exist at this boundary — removed
   entirely; the relationship is now a plain one-to-one
   `device_id`→physical-`entity_id` association.
2. **`data/.support_hmac.key` coupling removed entirely.** Confirmed by
   reading `utils/support_bundle.py::_get_support_key` that the key is
   created on demand, freely overridable by an environment variable, with
   no rotation/versioning/identity-association lifecycle — unsuited to
   gating target-selection authority. `serial_fingerprint`/HMAC design
   withdrawn; replaced by `producing_run_ref`, an opaque reference to the
   CP config collector's own already-governed, already-persisted per-run
   evidence (confirmed present: `_host_key_fingerprint` and the serial
   parse are both already captured per entity per run). Serial-based
   contradiction detection is now explicitly **DEFERRED** pending
   confirmation that evidence retention keeps that evidence resolvable
   long enough to compare — not fabricated to look solved.
3. **Target-specific trust lookup made mandatory, not optional.** Confirmed
   by reading `configuration/checkpoint_config_probe.py::_connect` that
   `apply_strict_host_key_policy` is not target-specific and that the bulk
   collector resolves its credential once per run before any per-target
   check — so `M8`'s producer cannot reuse the bulk entry point at all. It
   must be a new, minimal, single-target driver that performs a new,
   required, local-only (no device contact) trusted-key lookup — to be
   added to `utils/cp_ssh_trust.py` — before resolving any credential, then
   reuses the existing, unmodified `_collect_host`/`_identity_gate`
   per-host primitives.
4. **Relationship bound to live currency, not a source-code proxy.** Added
   `registry_record_revision` (the registry's own existing
   `DeviceRecord.updated_at` signal) compared live against the current
   record; replaced the withdrawn "trust-policy contract version" with a
   live comparison of the current trusted host-key fingerprint (via item
   3's lookup) against the fingerprint `producing_run_ref`'s evidence
   captured — both checks specified to run live, read-only, server-side, at
   admission and pre-execution, before any credential presentation or
   device contact.
5. **Fields reconciled; duplicated round-1 prose removed.** Final minimum
   field set specified (§Q5 of the doc); `identity_mapping_proven` retained
   under its `capability_projections` precedent name on the explicit
   condition every read API pairs it with `mapping_scope`. The document was
   rewritten wholesale rather than patched incrementally, so the body no
   longer carries scattered "(corrected, round 1)" qualifiers describing
   designs that round 2 removed.

Updated in place, no second movement record: the doc itself (full rewrite),
`project/build_history.json` (appended correction-round-2 evidence to the
same head record), `project/roadmap.json` (`now` block notes), `CURRENT_STATE.md`
(Active build section).

## 3. Exact next action

**Product Owner re-review of the corrected draft** in PR #96: freeze it (as
corrected, or with further changes), or reject/redirect. No implementation
slice may begin before that. The roadmap order (`M6 → M8 → M7`) and the
`CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY` scope remain PO-accepted from
round 1 and were not reopened in round 2.

If further corrections are requested, repeat this session's pattern: verify
each claim against real source before amending, and prefer rewriting the
affected sections cleanly over layering another "(corrected, round N)"
marker on top of the last one.

## 4. Test delta

None. No source or test file changed. Re-ran (all green, all state-only
changes): `tests/test_architecture_convergence.py` (20 passed),
`tests/test_application_package.py`, `py scripts/build_history_index.py
--check` (up to date), `py main.py --repository-privacy-check` (PASS, 0
findings), `git diff --check` (clean).

## 5. Risks / notes forward

- `mapping_scope` remains the single most important constraint: even once
  implemented, this design proves an association usable **only** for
  `CLASS_0` `config_refresh_cp` target selection.
- Serial-based `CONTRADICTORY_EVIDENCE` detection is **DEFERRED**, not
  built — the slice-3 implementation movement must measure real CP config
  evidence retention before this gap can be honestly closed either way.
- The mandatory trust-lookup seam (`utils/cp_ssh_trust.py`) does not exist
  yet and blocks the producer slice until built and tested.
- Open implementation-shape question: whether `console/registry_targets.py`
  may import the new trust-lookup function directly or needs a
  vendor-neutral wrapper to preserve "console imports no vendor/collector
  module."
- Three dissents remain open: `operator_assertion` (inherited from `M6`),
  single-sourced identity evidence, and deferred contradiction detection.
- Table/column names and migration version numbers remain illustrative, not
  frozen.
