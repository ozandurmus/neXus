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

- Date: 2026-09-06. `origin/main` = `d363b179`. Reviewed revision-7 head
  `1127125`; this is **revision 8** on the same branch, no history rewritten.
- Build: `nav_3_capability_state_vocabulary` (`M3`) — **IN_PROGRESS**.
  `ARCHITECTURE`, documentation only. Contract stays
  **`DRAFT — DO NOT FREEZE, NOT PRODUCT OWNER APPROVED`**.
- No runtime, UI, CSS, template, JavaScript, test, script, workflow,
  dependency, registry, storage, authorization or job change. No frozen
  contract edited. No PR, no merge, no freeze, no `M4`.

## 2. What changed this revision

Revision 8 corrects the fixture model only. The capability model — tagged
union, four `RESOLVED` outputs, affordance/server boundary, `CX1`/`RI`
taxonomy — is unchanged.

- **The three-class model conflated two independent questions**: *does the
  named action exist* and *does the producer of the required input exist*. Its
  `CURRENT` class therefore claimed a case was "fully executable today", which
  is false for any premise containing a `D3` or `D5` value. `H4f` was the
  clearest: it needs a positively supported `D3 = UNSUPPORTED`, and `D3` has no
  producer.
- **Replaced by two orthogonal axes**, tagged `[ACT:… · IN:…]`.
  Action basis: `CURRENT` (every named `action_id` is a `JOB_REGISTRY` member),
  `ACTION_FREE` (no affordance entry produced), `FUTURE` (no action contract,
  so no affordance entry can exist). Input basis: `CURRENT` (produced today),
  `SYNTHETIC(owner)` (valid future fixture, producer not shipped, value
  supplied explicitly), `FUTURE_PRODUCER(owner)` (tests output defined for a
  future producer contract).
- **Executability stated precisely.** Source/registry assertions are verifiable
  today; resolver cases become unit fixtures once the resolver exists, using
  explicitly identified synthetic inputs; **the focused repository tests
  neither execute nor prove the resolver**; and no case depending on an absent
  producer is called fully executable.
- **The honest result is stark and is stated as such:** 20 of 23 atomic cases
  are `IN:SYNTHETIC`, because `AVAILABLE` requires `D3 = SUPPORTED` and
  `D4 = RECONCILED` and neither has a producer. Only `H9a`/`H9a-t` are
  `IN:CURRENT`; `H4e` alone is `IN:FUTURE_PRODUCER(M10)`; `H4f` is
  `CURRENT_ACTION + SYNTHETIC(M10)`.
- **`ACT:FUTURE` cases left the atomic table.** `ActionAffordance` requires a
  closed-registry `action_id`, so a case without one cannot produce an entry.
  `H5d`/`H8b` became future contract scenarios `S-FUT-1`/`S-FUT-2` (§5.5.1),
  each recording the affordance its owning movement must produce once an action
  is declared, plus the safety requirement preserved for that movement.
  `AC-CS-65` lists the 23 atomic cases and excludes them. No AC added or
  renumbered; set stays `AC-CS-1`…`97`.

## 3. Exact next action

**Product Owner review of the corrected `M3` draft.** Close `PO-M3-1`…`PO-M3-6`
(§11.2) and rule on `FA-1`…`FA-9` (§2.7), each of which names its owner. Those
amend a FROZEN document and cannot be applied by an agent. If approval is
withheld: the conflict stays recorded, the named criteria stay blocked, no
implementer is sent back to false semantics, and the draft is not silently
rewritten. **Do not begin `M4`** or any implementation.

## 4. Test delta

- No test added or changed. Commands and results:
  - `python3 -m pytest -q tests/test_architecture_convergence.py` → **20 passed**
  - `python3 -m pytest -q tests/test_navigation_information_architecture.py` →
    **16 passed, 4 skipped**
  - combined → **36 passed, 4 skipped, 0 failed**
  - `metadata_warnings == []`; `build_history_index.py --check` clean;
    `main.py --repository-privacy-check` **PASS / 0 findings**;
    `git diff --check` clean; source/test/script/workflow/dependency trees
    untouched; rendered-structure audit **0 failures**; stale-token audit
    clean; AC continuity and PO↔FA mapping verified programmatically.
- These validate **repository consistency only**. No resolver exists, so every
  resolver acceptance criterion is unexercised until its owning movement
  implements it. No full local suite, no GitHub full regression, no
  `workflow_dispatch`, no device contact, no dependency change.

## 5. New risks / notes forward

- `D3` and `D5` still have **no producer** (`M10`, `M12`); both resolve to
  `UNKNOWN`, and `D5` never to a confirmed schedule absence.
- `FA-1`…`FA-9` prepared and unapplied; until ruled on the draft knowingly
  diverges from frozen parent wording in favour of verified source semantics
  (§2.7).
- `AC-CS-1`…`97`, contiguous; `AC-CS-17` remains marked superseded by
  `AC-CS-65`.
- **Process note:** run the rendered-structure and stale-token audits before
  any closure claim about document structure — two earlier revisions
  introduced heading-concatenation defects through section-replacement edits.
- Council dissents D1–D5 unresolved; the council record is a single authoring
  session's structured self-critique, not independent validation.
