# PO decision record - 2026-11-15: legacy Python separation, steps 2-4

## Status

RATIFIED - 2026-11-15 (about 12:00), PO answer relayed by the PO session. Decides the section 6 questions of
`LEGACY_PYTHON_SEPARATION_PLAN.md` and approves its steps 2, 3 and 4. Steps 5-8 are not approved by this record.

## 1. Decision

The Product Owner took the plan's section 6 recommendations as approved:

| # | Question | Decision |
| --- | --- | --- |
| 1 | Delete `main.py`, `utils/__init__.py` and the two uncollectable root tests | Yes (step 3). |
| 2 | Move the one-shot consult scripts into `history/scripts/` and amend `AGENTS.md` | Yes (step 4). The count is 27, not 29: `consult_argon` and `consult_fable_design` stay, and `consult_claude_log_trial_judge` stays until step 5 because `tools/llm_trial/judge.py` calls it. |
| 3 | Move `tools/llm_trial` and `scripts/measurement` Python into `history/` | Yes in principle, after the cross-reference rewrite; executes with step 5, which needs its own go-ahead. |
| 4 | Keep `history/` in this repository | Yes, for now. |
| 5 | Keep the PacketBreaker parity tooling until the port closes | Yes. Never moved mid-port. |

## 2. Conditions

- Deletions are limited to files the plan names. Every reference in ship tooling is fixed in the same change.
  Nothing referenced by a workflow, manifest, ship tool or `ui2/` may break; the reference greps are re-run on
  the exact tree and recorded in plan section 9.1.
- `AGENTS.md` ("External model and second-opinion consultation law") is amended to name
  `scripts/consult_argon.py` and `scripts/consult_fable_design.py` instead of the `scripts/consult_*.py` class,
  citing this record. The consultation law itself (no simulated reviews) is unchanged.
- Each step ships as its own change through the normal ship path with the Python tools tests and
  `:architecture-tests:test`.
