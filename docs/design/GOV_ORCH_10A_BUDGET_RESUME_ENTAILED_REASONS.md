# GOV.ORCH.10-A — What "only budget exhaustion" actually means

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-13.** Correcting amendment to
`docs/design/GOV_ORCH_10_BUDGET_EXHAUSTION_IS_RESUMABLE.md` R-2, which is
FROZEN and is not edited in place. Every other clause of GOV.ORCH.10 — R-1,
R-3, R-4, R-5, R-6 and its §4 acceptance — stands unchanged.

**This corrects a Product Owner defect, not a worker's.** GOV.ORCH.10 R-2
specified a condition that cannot occur. The movement that implemented it,
`NXS-LOCAL-0132`, implemented it faithfully and even tested it against the
reason set real runs produce; that test is what made the defect visible.

## 1. The measurement

GOV.ORCH.10 R-2 made a movement resumable when `budget_exhausted` was present
in its recorded `failure_reasons` **and no other reason was**.

Both budget-exhausted movements of 2026-09-13 recorded exactly:

```
["engineer_exit_nonzero", "budget_exhausted", "relay_not_closed"]
```

— `NXS-LOCAL-0130` and `NXS-LOCAL-0131`, identically.

Reading `scripts/orchestrator.py` shows this is not a coincidence of two runs
but a property of the code:

- `budget_exhausted` is appended **inside** the `if exit_code != 0` branch. It
  is therefore **impossible** for it to appear without `engineer_exit_nonzero`.
- A killed engineer never reaches its own `SESSION_CLOSE`, so the relay is
  still `OPEN` and `relay_not_closed` is appended too.

**R-2 as written is unsatisfiable. The feature it authorized can never fire.**

## 2. The correction

- **R-2a.** A movement is resumable under R-1 when `budget_exhausted` is
  present in its recorded `failure_reasons` and **every other reason present
  is one that budget exhaustion entails**. The entailed set is closed and is
  exactly:

  | Reason | Why it is entailed |
  | --- | --- |
  | `engineer_exit_nonzero` | The engineer is killed at the ceiling, so it exits non-zero. The code appends `budget_exhausted` only inside this branch. |
  | `relay_not_closed` | A killed engineer never reaches its own `SESSION_CLOSE`, so its relay is still open. |

- **R-2b.** Any reason **not** in that table blocks the resume, including one
  added by a future movement. The default is closed: a reason nobody has
  classified is treated as independent evidence that the run was defective,
  not as another consequence of the kill. Adding a reason to the table is an
  amendment to this document, never an implementation decision.
- **R-2c.** `budget_exhausted` must still be **present**. A movement carrying
  only `engineer_exit_nonzero`, or only `relay_not_closed`, or both without
  `budget_exhausted`, is not a budget failure and stays terminal. The entailed
  set is a permission to *accompany*, never a substitute.

## 3. A failed verify does not block a budget resume, and why

`verify` is not recorded in `failure_reasons` at all: `verify_result` is
computed separately and folds into the phase decision. So the entailed-set
rule cannot see it, and the question has to be answered deliberately rather
than left to fall out of the implementation.

**It does not block.** A run killed at its ceiling is killed mid-work, so its
verify is almost always red — `NXS-LOCAL-0131` failed verify on
`uncommitted_changes` alone, because the budget stopped it before it committed.
Treating that as a reason to refuse the resume would refuse nearly every
resume, which is the same defect as R-2 in a different place.

The safety argument is that **a resume is not an acceptance.** The resumed
engineer continues the same session and the orchestrator runs `verify` again at
the end; a movement whose tests are genuinely red gets a red verify then too,
and the Product Owner assistant still reviews the diff before any merge
(`roles/PO.md` §2, merge gate). Nothing about this amendment lets defective
work reach `main`.

## 4. Acceptance

1. `decide_start` returns `resume` for a failed record whose `failure_reasons`
   are `{budget_exhausted, engineer_exit_nonzero, relay_not_closed}` — the set
   both real movements produced. A test uses exactly that set.
2. It also returns `resume` for `{budget_exhausted, engineer_exit_nonzero}`.
3. It returns `dispatch` for a set containing `budget_exhausted` plus any
   reason outside the R-2a table. A test uses an invented reason name, so the
   check is the allowlist rather than a list of today's known reasons.
4. It returns `dispatch` for `{engineer_exit_nonzero, relay_not_closed}`
   without `budget_exhausted` (R-2c).
5. Every other clause of GOV.ORCH.10 keeps its existing test coverage, and
   the tests written for the superseded R-2 are updated rather than deleted —
   the mixed-reason test of GOV.ORCH.10 §4 item 2 becomes acceptance item 3
   here, with a reason outside the table.
6. `python3 -m pytest tests/test_orchestrator.py -q` passes.

## 5. Out of scope

Everything GOV.ORCH.10 §5 already excludes, plus: changing how
`failure_reasons` is constructed, making `verify` a recorded failure reason,
and any change to the phase decision.

## 6. Cross-references

- `docs/design/GOV_ORCH_10_BUDGET_EXHAUSTION_IS_RESUMABLE.md` — R-2 is
  superseded by §2 here; every other clause stands.
- `docs/design/GOV_ORCH_1_SYNCHRONOUS_RUN_AND_ORCHESTRATOR_VERIFY.md` §2.3 —
  the retry accounting both documents leave unchanged.
- `roles/PO.md` §2 — the merge gate that is the real protection against
  defective work, and the reason §3's argument holds.
