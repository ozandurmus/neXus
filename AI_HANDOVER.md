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

- Date: 2026-09-06. `origin/main` = `d363b179`. Reviewed revision-5 head
  `ef94f7c`; this is **revision 6** on the same branch, no history rewritten.
- Build: `nav_3_capability_state_vocabulary` (`M3`) — **IN_PROGRESS**.
  `ARCHITECTURE`, documentation only. Contract stays
  **`DRAFT — DO NOT FREEZE, NOT PRODUCT OWNER APPROVED`**.
- No runtime, UI, CSS, template, JavaScript, test, script, workflow,
  dependency, registry, storage, authorization or job change. No frozen
  contract edited. No PR, no merge, no freeze, no `M4`.

## 2. What changed this revision

Revision 6 is a bounded consistency correction; the revision-5 model is
retained unchanged.

- **Stage summary and taxonomy made consistent.** §5's stage block now uses the
  tagged-union terminology and states the presentation-time / server-phase
  boundary (submission → admission → immediately before execution, where both
  `AC-ST-4` registry checks live). `CX1`/`RI-1`/`RI-2` is used in every
  normative clause; `CX2`/`CX3`, `G1`–`G4` and `R1`–`R5` survive only in
  clearly-labelled historical notes.
- **Ten stale criteria reconciled without renumbering** — `AC-CS-17`, `62`,
  `64`, `65`, `74`, `75`, `76`, `78`, `81`, `86`. Set stays `AC-CS-1`…`97`;
  none added.
- **Fixture integrity.** Every hard case is tagged `[CURRENT]` or `[FUTURE]`
  with a mechanical obligation: a `[CURRENT]` case's `action_id` must be a
  `JOB_REGISTRY` member, a `[FUTURE]` case's described action must be asserted
  **absent** — so a `[FUTURE]` case that silently becomes real is caught.
  `H5b` corrected (`report_rebuild` is `workflow = render-only`, a
  report-rendering job, **not** a retry); retry moved to `[FUTURE]` `H5d`.
  `H8b` and new `H4e` are `[FUTURE]`. `H11a`/`H11c` became action-free.
- **`H4` resolved from source, not the action name.** `config_refresh_cp` runs
  `configuration/checkpoint_config_collector.py`, whose `_classify_platform`
  (l.926-941, invoked l.1469-1471) emits a platform-family classification with
  a confidence grade; frozen `PCP.0` §8 lists that classification among the
  capability projection's inputs. So a re-collection **can** re-evaluate a
  `D3` input, and the "collecting again will not help" copy is removed from
  `H4a`–`H4d`. `UNSUPPORTED` reasons now carry a `REVALIDATABLE`/`TERMINAL`
  class; **which** reasons are which is **`UCQ-1`**, an open contract question
  owned by `M10` — deliberately **not** a seventh PO decision.
- **`PO-M3-2` aligned with §11.3**: withholding approval leaves the conflict
  recorded and the implementation blocked; it never requires reverting the
  draft to semantics demonstrated false.

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
