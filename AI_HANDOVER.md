# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase
doc. Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-06. `origin/main` = `d363b179` (PR #90). Reviewed revision-1
  head `5be42b1`; this is **revision 2** on the same branch, no history
  rewritten.
- Build: `nav_3_capability_state_vocabulary` (`M3`) — **IN_PROGRESS**.
  Movement type `ARCHITECTURE`. Contract remains
  **`DRAFT — DO NOT FREEZE, NOT PRODUCT OWNER APPROVED`**.
- Bounded correction of the draft after an independent review. No runtime, UI,
  payload, source, test, dependency, workflow, storage or device change. No
  frozen-parent edit. No PR, no merge, no freeze.

## 2. What changed this revision

Fourteen draft defects corrected in place (contract §11.1). The four that
matter most:

- **Four facets, not one evidence axis.** `PROVENANCE_UNVERIFIED` is a
  *source-trust / comparability* classification carrying no age and no
  timestamp (verified: `pan_setting_alignment.py` l.438-443, l.649-652). It no
  longer produces `STALE`. Freshness now comes from the evidence the
  repository already ships — `snapshot.py::_status`'s `fresh`, `collected_at`,
  `last_successful_collection`, `stale_reason`.
- **`UNKNOWN_SHELL` is unknown, not unsupported** (verified:
  `capability_registry.py` l.276-283, the branch commented "Unknown /
  insufficient evidence", sibling to `INSUFFICIENT_EVIDENCE`).
- **Status label ≠ displayed evidence ≠ action eligibility.** The resolver now
  emits four independent outputs. The blanket "non-`AVAILABLE` ⇒ empty view"
  rule is gone; retained evidence stays displayed when collection fails, a
  schedule is disabled, or reconciliation is incomplete.
- **Authorization is five regimes.** `OP.2`'s `DenyAllAuthorizer` governs
  CLASS 2 **only**; CLASS 0 console operations are governed by the shipped
  bearer/origin gate and the taxonomy refusal, and are permitted.

Also: contradiction narrowed to a closed pre-ladder set `CX1`–`CX3` with
declared scopes; `AVAILABLE` requires a positive conjunction plus a fail-closed
catch-all; hard-case table rebuilt to eleven complete rows; `D5` defaults to
`POLICY_UNKNOWN` and makes only present-tense schedule claims; persistence
corrected against frozen `AC-ST-2`; `AC-CS-1`…`72` with existing ids preserved.

**Recorded, not applied:** five frozen-parent corrections `FA-1`…`FA-5`
(contract §2.7, §11.3). Four are the same two conflations inside the frozen
navigation contract; one is the tab-omission contradiction.

**Withdrawn on evidence:** the revision-1 claim that the two job-lifecycle
vocabularies are a defect. `discovery_ui.js` l.30 and
`console_actions.js::_consoleJobStatePill` l.150-152 each use their own
subsystem's map; no renderer crosses them. The PO question resting on it is
withdrawn.

## 3. Exact next action

1. **Independent review of the revised draft.**
2. Then Product Owner: decide `PO-M3-1`…`PO-M3-6` (§11.2) and rule on
   `FA-1`…`FA-5`. Those amend a FROZEN document and cannot be applied by an
   agent. If `FA-1`…`FA-4` are declined, §4.3 and §9 of the draft must be
   reverted to the parent's wording — the divergence is declared, not silent.
3. **Do not begin `M4`** or any implementation on the strength of this draft.

## 4. Test delta

- No test added or changed. Authoritative focused run this revision:
  `test_architecture_convergence.py` **20 passed**;
  `test_navigation_information_architecture.py` **16 passed, 4 skipped**;
  combined **36 passed, 4 skipped, 0 failed**.
- `metadata_warnings == []`; `build_history_index.py --check` clean; privacy
  gate **PASS / 0 findings**; `git diff --check` clean.
- **Evidence correction:** the focused counts previously written into
  `CURRENT_STATE.md` for revision 1 (24 / 20 passed) were recorded before the
  run and were never true. The revision-1 session close figure (36 / 4) was
  correct. No retained log exists for revision 1.
- No full local suite, no GitHub full regression, no dependency change.

## 5. New risks / notes forward

- `D3` and `D5` still have **no producer** (`M10`, `M12`). Both resolve to
  `UNKNOWN` — never to a favourable default, and `D5` never to a confirmed
  schedule absence.
- Until `FA-1`…`FA-4` are ruled on, the draft **knowingly diverges** from the
  frozen navigation contract's presentation rows in favour of verified source
  semantics. This is declared at §2.7 and is a defect in the draft if the
  amendments are declined.
- Council dissents D1–D5 remain unresolved; D1 was amended where this revision
  withdrew the coverage-arithmetic claim it rested on. The council record is a
  **single authoring session's structured self-critique** — not an installed
  skill, not independent execution, not cross-model validation.
