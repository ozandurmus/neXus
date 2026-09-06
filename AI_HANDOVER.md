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

- Date: 2026-09-06. Baseline verified independently: `origin/main` =
  `d363b179fe8f552544402e070f5908ec10df2115` (PR #90).
- Build: `nav_3_capability_state_vocabulary` (`M3`) — **IN_PROGRESS**.
  Movement type `ARCHITECTURE` (contract + evidence), not implementation.
- Delivered a **DRAFT** contract:
  `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md`. Status is
  `DRAFT — DO NOT FREEZE, NOT PRODUCT OWNER APPROVED`. It authorizes no
  implementation and must not be cited as design authority.
- No runtime, UI, CSS, JavaScript, template, adapter, registry, storage,
  enrollment, authorization, job or report-generation change. No PR opened.

## 2. What changed this session

- **Evidence inventory**: thirty state/status/lifecycle vocabularies located
  across nine subsystems, each with its owning module. Key findings —
  **E1** no capability-state vocabulary exists today; **E2** eight duplicated
  terms with different meanings (`available` has three; `blocked` is both a
  job record state and a job-type taxonomy fact, one route apart in
  `console/app.py`); **E3** two live job-lifecycle vocabularies, and the UI
  surfaces the second (`discovery_capability_ui.JOB_STATUS_LABELS`).
  Eight conflations catalogued `C1`–`C8`.
- **Contract**: seven independently owned dimensions `D1`–`D7`; a ten-value
  `CapabilityState` plus six non-exclusive qualifiers; a deterministic
  ten-rank precedence ladder resolving all nine required hard cases; vendor
  normalization boundaries; an event→dimension transition table; fail-closed
  rules; 53 acceptance criteria `AC-CS-1`…`53`, each naming its owning
  movement.
- **`PO-NAV-7` settled in contract**: capability-policy → `D5`
  `POLICY_DISABLED`, owned by the schedule/capability-policy contract
  (amendment `A5`, `M12`); "not enrolled" → `D4` `EVIDENCE_ONLY`, owned by
  the registry/evidence reconciliation projection (amendment `A6`, `M10`).
  Neither joins the job lifecycle, which is byte-unchanged.
- **`PO-NAV-6` owned**: preserve the pale-yellow/gold member emphasis on its
  own `--member-specific` token, decoupled from `--warning`; red stays
  reserved for actual fault. Contract only — no CSS changed.
- **Reported, not reconciled**: the FROZEN navigation contract contradicts
  itself on omitting a structurally inapplicable tab (§8/§8.1 vs
  §6.5/D-NAV13/`AC-WS-7`/`AC-WS-8`). Raised as `PO-M3-1`.
- **State**: `build_history.json` head record added (`in_progress`);
  `roadmap.json` `current_build`/`now_next.now` → `M3`, `now_next.next` →
  `M4`; `CURRENT_STATE.md` checkpoint corrected from the stale `6ca67cc`/PR
  #88 line to `d363b17`/PR #90; `docs/history/INDEX.md` regenerated;
  one DRAFT-marked forward pointer added to `docs/ARCHITECTURE.md` §7.

## 3. Exact next action

1. **Product Owner review of the DRAFT contract.** Close `PO-M3-1`…`PO-M3-6`
   (contract §11). `PO-M3-1` requires a one-line correction to a FROZEN
   contract and cannot be applied by an agent.
2. Then either freeze the contract (status line → `FROZEN`) or return it with
   corrections. `M3` stays `in_progress` until then.
3. **Do not begin `M4`** or any implementation on the strength of this draft.
   Recommended for the PO review: `Sonnet 5, extended thinking (high)`, same
   session. For later implementation of the frozen contract:
   `Sonnet 5, normal`, new session.

## 4. Test delta

- No test added or changed — documentation-only movement.
- Focused validation run: `tests/test_architecture_convergence.py` and
  `tests/test_navigation_information_architecture.py` green;
  `metadata_warnings == []`; `scripts/build_history_index.py --check` clean;
  repository privacy gate PASS / 0 findings; `git diff --check` clean.
- **No full local suite and no GitHub full regression.** Neither is warranted
  for a documentation-only change, and the movement's scope forbade them.

## 5. New risks / notes forward

- `D3` (vendor capability support) and `D5` (capability policy) have **no
  producer** in the repository. Until `M10`/`M12` ship them, both resolve to
  `UNKNOWN` rather than a favourable default — this is deliberate, and any
  implementation that defaults them to `SUPPORTED`/`POLICY_ACTIVE` breaks the
  contract.
- Five council dissents are recorded **unresolved** (contract §10). The
  `DEVICE_DISABLED`-above-`UNSUPPORTED` ordering is a legitimate alternative
  the Product Owner may choose.
- `PO-M3-4` — the two live job-lifecycle vocabularies — is out of `M3` scope
  and currently has no owning movement. It needs one.
- The council ran with **role diversity only, not independent cross-model
  validation**; the installed `nexus-decision-council` skill was not present
  in this environment.
