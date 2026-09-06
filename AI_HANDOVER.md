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

- Date: 2026-09-06. `origin/main` = `d363b179`. Reviewed revision-2 head
  `4b4339f`; this is **revision 3** on the same branch, no history rewritten.
- Build: `nav_3_capability_state_vocabulary` (`M3`) — **IN_PROGRESS**.
  Movement `ARCHITECTURE`, documentation only. Contract stays
  **`DRAFT — DO NOT FREEZE, NOT PRODUCT OWNER APPROVED`**.
- No runtime, UI, CSS, template, JavaScript, test, script, workflow,
  dependency, storage, device, enrollment, registry, authorization or job
  change. No frozen contract edited. No PR, no merge, no freeze, no `M4`.

## 2. What changed this revision

Twelve defects (`X15`–`X26`, contract §11.1). The four that matter most:

- **`D1` visibility is absolute.** Contradiction detection was ranked *above*
  the `D1` check, so an absent surface would have rendered on a contradiction.
  Stage 0 now decides visibility from `D1` alone, before everything, with
  cases `V-A`–`V-D`. A contradiction on an absent surface emits diagnostics,
  audit evidence or telemetry and changes **no** rendering. Absent or malformed
  `D1` fails closed toward **omission**.
- **Action eligibility has two gate sets.** Set A — action identity, taxonomy
  class, every applicable authorization regime, subject/target integrity — is
  mandatory and is never shortened by an empty Set B. Set B stays the action's
  own semantic prerequisites. Targetless reads gain no invented target
  prerequisite; manual collection is independent of scheduling policy and of
  nothing else; retry must satisfy its own retry contract and is never granted
  by a prior failure.
- **Unevaluated authorization ≠ denial.** `AUTHZ_NOT_EVALUATED` is
  non-executable but asserts nothing about permission, and is rendered and
  audited distinctly.
- **`H9` was inverted.** Its cells named each contradiction class's *affected*
  actions under a heading reading "Action eligible" — the opposite of the scope
  rule. `H1`–`H11` now carry a shared baseline, a named action per row, all
  four outputs, and no unconditional-permission wording.

Also: resolver inputs completed as `I1`–`I17` with input-condition handling
resolved before affected outputs (`POLICY_UNKNOWN` is valid input, not
malformed, and erases no evidence); cache validity bound to every consumed
generation **plus** producer/support-rule versions, with missed or delayed
invalidation assumed and live registry checks at admission and immediately
before execution preserved; the unsupported `REGISTRY_ONLY` "never observed"
claim removed; frozen-parent inventory expanded to `FA-1`…`FA-9`.

## 3. Exact next action

**Product Owner review of the corrected `M3` draft.** Close `PO-M3-1`…`PO-M3-6`
(§11.2) and rule on `FA-1`…`FA-9` (§2.7, §11.3). Those amend a FROZEN document
and cannot be applied by an agent. If approval is withheld on a row, the
acceptance criteria its consequence column names are **blocked** and the
authority conflict stands — do not send implementers back to semantics the
draft demonstrated false. **Do not begin `M4`** or any implementation.

## 4. Test delta

- No test added or changed. Commands and results:
  - `python3 -m pytest -q tests/test_architecture_convergence.py` → **20 passed**
  - `python3 -m pytest -q tests/test_navigation_information_architecture.py` →
    **16 passed, 4 skipped**
  - combined → **36 passed, 4 skipped, 0 failed**
  - `metadata_warnings == []`; `build_history_index.py --check` clean;
    `main.py --repository-privacy-check` **PASS / 0 findings**;
    `git diff --check` clean; source tree untouched.
- These validate **repository consistency only**. No resolver exists, so every
  resolver acceptance criterion is unexercised until its owning movement
  implements it. No full local suite, no GitHub full regression, no
  `workflow_dispatch`, no device contact, no dependency change.

## 5. New risks / notes forward

- `D3` and `D5` still have **no producer** (`M10`, `M12`); both resolve to
  `UNKNOWN`, never a favourable default and never a confirmed absence.
- `FA-1`…`FA-9` are prepared and unapplied. Until ruled on, the draft
  **knowingly diverges** from frozen parent wording in favour of verified
  source semantics — declared at §2.7, and a defect in the draft if declined.
- `AC-CS-1`…`85`, contiguous; `AC-CS-17` remains marked superseded by
  `AC-CS-65` rather than renumbered.
- Council dissents D1–D5 unresolved. The council record is a single authoring
  session's structured self-critique — not an installed skill, not independent
  execution, not cross-model validation.
