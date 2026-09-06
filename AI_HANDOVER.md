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
  automated_validated, not yet PO-approved for freeze or merge.
- Contract: `docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md`
  — now carries a **correction round 1** applied this session.

## 2. What this session did (correction round 1, PO architecture review)

The Product Owner reviewed PR #96 and returned seven blockers against the
draft, all resolvable by direct repository evidence — **the
`nexus-decision-council` was explicitly not re-invoked**, per instruction.
Each blocker was verified against real source before correcting the doc:

1. **Evidence scope narrowed.** Confirmed the design never independently
   corroborates the directly-read serial against the CMA/MDS `entity_id` —
   the candidate is still selected by registry-endpoint == `management_ip`
   equality; the live session proves liveness/authenticity at that
   endpoint, not independent confirmation of the CMA's own labeling. Added
   a closed `mapping_scope` column fixed to
   `CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY` (the `utils.action_taxonomy`
   `CLASS_0_READ` sense — explicitly not the unrelated
   `PRIVACY_AND_DATA_HANDLING.md` CLASS 0–3 scheme), with an explicit
   prohibited-uses list (`CLASS_2`, authorization, `operational_entity_id`,
   enrollment, security identity).
2. **Trust/credential sequencing corrected**, against a direct read of
   `configuration/checkpoint_config_probe.py::_connect`: confirmed
   `apply_strict_host_key_policy` is not target-specific (target host-key
   verification happens inside `ssh.connect()`'s own handshake instead),
   and confirmed the collector resolves its run credential into process
   memory once, before any per-target loop
   (`checkpoint_config_collector.py:1976-1978`). The original "credential
   is not read before trust" claim was an overclaim — corrected to the
   real, narrower guarantee, with the stronger guarantee named as an
   explicit, not-yet-built implementation prerequisite.
3. **M4 compatibility corrected.** Confirmed
   `tests/test_m4_control_plane_metadata_store.py::test_schema_owns_no_forbidden_concept`
   test-enforces `"trust"` as a forbidden column-name fragment — withdrew
   the originally-proposed `trust_authority_generation` column (a cosmetic
   rename was explicitly rejected) in favor of reusing
   `capability_projections.authority_generations`'s already-approved
   TEXT/JSON shape. Also corrected "existing five tables" to seven `STRICT`
   tables including `schema_migrations` (confirmed by reading
   `utils/control_plane_store.py`).
4. **Contradictory-evidence comparison mechanism specified.** A raw serial
   cannot be compared later once discarded (the original design's flaw) —
   specified a `serial_fingerprint` column: an HMAC-SHA256 digest via the
   existing, already privacy-reviewed `data/.support_hmac.key` mechanism
   (`utils/support_bundle.py`), never the raw value, `CLASS 2` classified,
   with an explicit rule for a missing/rotated key.
5. **Cardinality corrected.** Confirmed directly from
   `configuration/checkpoint_config_collector.py`'s own `_entity_id`/
   `_collect_host` iteration over `target.contexts` that one `device_id`
   legitimately produces multiple `entity_id` rows under VSX (one physical
   + one per `VsContext.vs_id`) — added `entity_scope`/`vs_id_slot`
   columns and corrected the one-active-relationship invariant to be
   scoped per physical-or-VS-id slot, not per `device_id`.
6. **Invalidation-mechanics contradiction resolved.** Split explicitly into
   logical, read-time invalidity (registry disable/retire/endpoint change,
   authority-contract staleness — computed live by `M6`'s resolver, never a
   stored mutation) versus durable, producer-only state transitions
   (`SUPERSEDED`/`AMBIGUOUS_IDENTITY`/`CONTRADICTORY_EVIDENCE`). `console/`
   remains strictly read-only; no consumer-side write of any kind.
7. **Status preserved**: still `DRAFT — IN REVIEW`, `M7` still `blocked`, no
   frozen parent contract touched, no code/schema/device contact.

Updated in place, no second movement record: the doc itself (correction
round 1 section + every affected Q&A/section), `project/build_history.json`
(appended correction-round-1 evidence to the same head record, mirroring
`M6`'s own correction-round precedent), `project/roadmap.json` (`now` block
notes), `CURRENT_STATE.md` (Active build section).

## 3. Exact next action

**Product Owner re-review of the corrected draft** in PR #96: freeze it (as
corrected, or with further changes), or reject/redirect. No implementation
slice may begin before that. The roadmap order (`M6 → M8 → M7`) itself was
already PO-accepted in round 1 and is not reopened.

If approved: push is already done (see §6); await PO decision. If further
corrections are requested, repeat this session's pattern — verify each claim
against real source before amending, update the doc in place, append a new
correction round to the same `build_history.json` record.

## 4. Test delta

None. No source or test file changed. Re-ran (all green, all state-only
changes): `tests/test_architecture_convergence.py` (20 passed, includes the
`now_next.now` / build-history-head consistency check),
`tests/test_application_package.py`, `py scripts/build_history_index.py
--check` (up to date), `py main.py --repository-privacy-check` (PASS, 0
findings), `git diff --check` (clean).

## 5. Risks / notes forward

- The `mapping_scope` narrowing is the single most important correction:
  even once implemented, this design proves an association usable **only**
  for `CLASS_0` `config_refresh_cp` target selection — never general device
  identity, never `CLASS_2`/authorization/enrollment evidence. A future
  session must not silently widen `identity_mapping_proven`'s meaning
  without a separate, explicit PO decision.
- Two dissents remain open and were not touched by this round:
  `operator_assertion` (inherited from `M6`) and single-sourced
  (non-bidirectionally-corroborated) identity evidence.
- A new open item from this round: whether `M8`'s slice-2 producer should
  eventually gain a target-specific pre-connection host-key lookup seam
  (stronger than the existing protocol-level guarantee) — named, not
  decided, not built.
- Table/column names and migration version numbers remain illustrative, not
  frozen, per `AGENTS.md` "Contract-status law".
