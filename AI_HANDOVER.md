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

- Date: 2026-09-06. `M8` architecture — **FROZEN, PRODUCT OWNER APPROVED**,
  PR #96 merged into `main` via a true merge commit (see
  `project/build_history.json`/PR #96 for the exact merge SHA and parents).
- Build: `m8_first_contact_trust_identity_evidence_producer_architecture`
  (`M8`) — `status: complete` (architecture only; no code exists).
- Contract: `docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md`
  — **FROZEN**, compacted to a concise normative contract (~395 lines, down
  from the ~1086-line working draft). Round-by-round correction narrative
  now lives only in `project/build_history.json` and this PR's commits.

## 2. What this session did (final reconciliation, freeze, merge)

PO approved the round-2-corrected direction and requested final
reconciliation before freeze — **`nexus-decision-council` not invoked**,
`GOV.SESSION.1` kept parked/untouched, direct repository evidence only.

1. **Serial made explicitly mandatory**: `proof_type =
   first_contact_identity_gate_and_serial` requires both an accepted
   identity gate *and* a usable directly-read serial before
   `identity_mapping_proven` may be `1`. Gate-accepted-but-serial-absent
   writes no row, leaves `IDENTITY_TRANSLATION_REQUIRED`, and is observable
   only via the producer's own sanitized run-outcome record.
2. **Explicit implementation sequence recorded**: `M8.1` (relationship
   storage/API) → `M8.2` (mandatory endpoint-specific trusted-key lookup)
   → `M8.3` (read-only producer + real-environment gate, must not begin
   without `M8.2`) → `M8.4` (`M6` resolver consumption) → `M7` (blocked
   until `M8.4` and real-environment evidence exist).
3. **Contract compacted**: rewrote the ~1086-line working draft into a
   ~395-line normative contract — removed correction-round narratives,
   superseded schema designs (VSX cardinality, HMAC serial fingerprint),
   repeated Q&A restatements, and duplicated lists. Retained only
   rationale, final rules/schema, acceptance criteria, unresolved
   risks/dissent, non-goals, and the implementation sequence.
4. **Status flipped DRAFT → FROZEN.** Project state reconciled:
   `project/build_history.json` (status `complete`, title/summary updated,
   freeze evidence appended), `project/roadmap.json` (`now` = `M8`
   `complete`; `next` = `m8_1_relationship_storage_api`, `planned`, no
   blocker; `m7_real_device_targeted_collect_now` moved to
   `upcoming`/`blocked`), `CURRENT_STATE.md` (Active build + Exact next
   build sections).
5. **PR #96 description updated** to reflect the final physical-only
   design, no support-HMAC coupling, mandatory trust seam, required
   serial, and the five-step sequence.
6. **Merged** via `gh pr merge 96 --merge` (true merge commit, matching
   PR #93/#94/#95 precedent) after `validate` passed on the final commit.
   Local `main` fast-forwarded to match `origin/main`.

## 3. Exact next action

**`M8.1` — device-identity-relationship storage and typed read/write
API.** Not started, not authorized to begin without its own `SCOPE →
AUDIT → CONTRACT` pass against `M8`'s frozen §5/§9 (this is a
deterministic-implementation movement against an already-frozen contract,
so a new architecture document is not expected — `AGENTS.md` "Mandatory
build lifecycle"). `M8.2` (the mandatory trust-lookup seam) is the
following slice; `M8.3` (the producer) must not begin without `M8.2`.

## 4. Test delta

None this session. No source or test file changed. Validation re-run on
every state/doc change: `tests/test_architecture_convergence.py` (20
passed), `tests/test_application_package.py`, `py scripts/build_history_index.py
--check` (up to date), `py main.py --repository-privacy-check` (PASS, 0
findings), `git diff --check` (clean). No `full-regression`/
`workflow_dispatch` triggered (not authorized this session).

## 5. Risks / notes forward

- `mapping_scope = CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY` remains the
  entire authority this design produces — `M8.1`+ must never widen it
  without a separate, explicit PO decision.
- Serial-based `CONTRADICTORY_EVIDENCE` detection is `DEFERRED` — `M8.3`
  must measure real CP config evidence retention before this gap can be
  honestly closed either way.
- `M8.2`'s trust-lookup seam does not exist yet and gates `M8.3`.
- Open implementation-shape question for `M8.4`: whether
  `console/registry_targets.py` imports the new trust-lookup function
  directly or through a vendor-neutral wrapper.
- Three dissents remain open: `operator_assertion`, single-sourced identity
  evidence, deferred contradiction detection.
- Table/column names and migration version numbers remain illustrative
  beyond the `M8.1`–`M8.4`/`M7` ordering itself.
