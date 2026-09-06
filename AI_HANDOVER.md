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

- Date: 2026-09-06. `origin/main` = `d363b179`. Reviewed revision-4 head
  `f022ee1`; this is **revision 5** on the same branch, no history rewritten.
- Build: `nav_3_capability_state_vocabulary` (`M3`) — **IN_PROGRESS**.
  `ARCHITECTURE`, documentation only. Contract stays
  **`DRAFT — DO NOT FREEZE, NOT PRODUCT OWNER APPROVED`**.
- No runtime, UI, CSS, template, JavaScript, test, script, workflow,
  dependency, registry, storage, authorization or job change. No frozen
  contract edited. No PR, no merge, no freeze, no `M4`.

## 2. What changed this revision

Defects `X40`–`X49` (§11.1). The five that matter most:

- **Tagged union.** `RESOLVED{primary_status, capability_qualifiers,
  evidence_presentation, action_affordance}` or `OMITTED{reason, diagnostic}`.
  `NOT_SHIPPED` is a `SurfaceOmissionReason`, **not** a `CapabilityState`
  (nine resolved-only states). `OMITTED` carries no capability output at all.
  The omission diagnostic pass is a separate render-independent pass, stated
  **optional** consistently everywhere.
- **Presentation-time vs server-time.** `action_affordance[]` replaces
  `eligible: true|false`. `AVAILABLE_FOR_SUBMISSION` means *no
  presentation-time blocker is known* — never that `E7` passed, never an
  execution grant. "Evaluated once" is **per decision phase**, so `AC-ST-4`'s
  admission **and** immediately-before-execution checks both survive.
- **`E4` is total.** `NO_APPLICABLE_AUTHORITY` (the current CLASS 0 case) is
  distinct from `PERMITTED`/`DENIED`/`AUTHZ_NOT_EVALUATED`, non-blocking, and
  **explicitly not a grant**.
- **`CX1` is subject-scoped.** It blocks only actions whose declared subject
  depends on the disputed identity; targetless actions are unaffected and get
  no invented prerequisite.
- **Severity settled.** `CX2`/`CX3` renamed `RI-1`/`RI-2` **bounded
  inconsistencies** (muted). Danger is reserved for `CX1`, so `AC-DIF-6`,
  `AC-DIF-8` and `AC-CS-47` reconcile with **no** new parent amendment.

Also: `RI-1` comparability total over `K1`–`K6` with six precedence rows;
H-cases rewritten against **real registry action ids** (verified
`enroll_device` ∉ `JOB_REGISTRY`, so the enrollment example produces no action
entry rather than inventing an authority); former `H9b`/`H9c` reclassified as
multi-action scope tests; persistable projection separated from the composed
presentation resolution; stale `R1`/`R2`/`R5` and `G1`–`G4` language removed or
labelled non-normative.

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
