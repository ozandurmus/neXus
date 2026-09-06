# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.

---

## 1. Snapshot

- Date: 2026-09-06. `origin/main` = `d363b179`. Reviewed revision-3 head
  `f8c0be5`; this is **revision 4** on the same branch, no history rewritten.
- Build: `nav_3_capability_state_vocabulary` (`M3`) — **IN_PROGRESS**.
  Movement `ARCHITECTURE`, documentation only. Contract stays
  **`DRAFT — DO NOT FREEZE, NOT PRODUCT OWNER APPROVED`**.
- No runtime, UI, CSS, template, JavaScript, test, script, workflow,
  dependency, storage, device, enrollment, registry, authorization or job
  change. No frozen contract edited. No PR, no merge, no freeze, no `M4`.

## 2. What changed this revision

Thirteen defects (`X27`–`X39`, contract §11.1). The five that matter most:

- **Surface resolution is now separate from capability resolution.** `D1` is
  three-valued; a missing or malformed `D1` yields `OMIT_UNRESOLVABLE` with
  `d1_input_unresolvable` and **does not assert `NOT_SHIPPED`** — omitting is a
  disposition, `NOT_SHIPPED` is a claim. Under either omission the four
  capability outputs are **absent, not empty**.
- **Action outcomes are action-keyed.** `ACTION_DENIED`, `ACTION_REFUSED` and
  `AUTHZ_NOT_EVALUATED` live only in
  `action_eligibility[action_id].blocking_reasons[]`; `capability_qualifiers`
  holds capability/evidence facts only. Report parity follows structurally: the
  report declares no actions, so the whole projection is not generated.
- **Gate ownership corrected.** `D7` describes **actor authorization only**.
  The seven server-owned concepts are named `E1`–`E7`, and the action taxonomy
  (`E3`) is evaluated **exactly once** — the previous revision evaluated it in
  `G2` and again inside `G3`.
- **`D6d` expected-source trust is separate from `D6e` identity translation**,
  with source reason codes preserved. Neither implies age; neither becomes
  `CX1` unless `CX1`'s own conditions independently hold.
- **`CX2` has a comparability rule (`K1`–`K6`).** A platform or support-rule
  change never manufactures a contradiction from historical evidence;
  contemporaneous evidence under the same rules is not ignored; unestablished
  comparability resolves to `UNKNOWN(support_comparability_unestablished)`.

Also: document structure repaired; `H1`–`H11` made atomic with lettered
subcases; unconditional-eligibility wording removed; every `FA-*` row given an
owning PO decision; the withheld-approval rule corrected.

**Closure-report correction (X27).** Revision 3's SESSION CLOSE said a
duplicated heading had been found and fixed. That was true of one instance and
**false as a general claim** — an identical concatenation at `### 4.2` survived
into the pushed document, because the audit used `uniq -d` over whole heading
lines and two headings concatenated onto **one** line form a unique string. A
rendered-structure audit now guards this class.

## 3. Exact next action

**Product Owner review of the corrected `M3` draft.** Close `PO-M3-1`…`PO-M3-6`
(§11.2) and rule on `FA-1`…`FA-9` (§2.7), each of which names its owner. Those
amend a FROZEN document and cannot be applied by an agent. If approval is
withheld: the conflict stays recorded, the named acceptance criteria stay
blocked, no implementer is sent back to false semantics, and the draft is not
silently rewritten. **Do not begin `M4`** or any implementation.

## 4. Test delta

- No test added or changed. Commands and results:
  - `python3 -m pytest -q tests/test_architecture_convergence.py` → **20 passed**
  - `python3 -m pytest -q tests/test_navigation_information_architecture.py` →
    **16 passed, 4 skipped**
  - combined → **36 passed, 4 skipped, 0 failed**
  - `metadata_warnings == []`; `build_history_index.py --check` clean;
    `main.py --repository-privacy-check` **PASS / 0 findings**;
    `git diff --check` clean; source/test/script/workflow/dependency trees
    untouched; rendered-structure audit **0 failures**.
- These validate **repository consistency only**. No resolver exists, so every
  resolver acceptance criterion is unexercised until its owning movement
  implements it. No full local suite, no GitHub full regression, no
  `workflow_dispatch`, no device contact, no dependency change.

## 5. New risks / notes forward

- `D3` and `D5` still have **no producer** (`M10`, `M12`); both resolve to
  `UNKNOWN`, and `D5` never to a confirmed schedule absence.
- `FA-1`…`FA-9` are prepared and unapplied. Until ruled on, the draft
  **knowingly diverges** from frozen parent wording in favour of verified
  source semantics — declared at §2.7.
- `AC-CS-1`…`93`, contiguous; `AC-CS-17` remains marked superseded by
  `AC-CS-65` rather than renumbered.
- **Process risk:** two successive revisions introduced heading-concatenation
  defects through section-replacement edits, and one closure report wrongly
  declared the class fixed. The rendered-structure audit now guards it; run it
  before any future closure claim about document structure.
- Council dissents D1–D5 unresolved. The council record is a single authoring
  session's structured self-critique — not an installed skill, not independent
  execution, not cross-model validation.
