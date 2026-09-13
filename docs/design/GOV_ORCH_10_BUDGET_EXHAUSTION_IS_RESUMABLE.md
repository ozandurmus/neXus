# GOV.ORCH.10 — A budget-exhausted movement is resumable, not terminal

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-13.** Successor amendment to
`docs/design/GOV_ORCH_1_SYNCHRONOUS_RUN_AND_ORCHESTRATOR_VERIFY.md` §2.3
(retry and failure accounting) and to the `decide_start` contract its §2.1
describes. Neither is edited in place. Nothing else in GOV.ORCH.1 is touched.

Recorded on a Product Owner directive given in the 2026-09-13 local session:
*when a movement is cut off at its budget ceiling, raise the ceiling and
continue it.* `AGENTS.md` "Authority hierarchy" item 7 makes that chat
non-authoritative, so this document is the durable record — and the
measurement in §1 is why the directive could not be carried out as the tool
stands.

## 1. The measurement

Three movements ran on 2026-09-13. Two were cut off at their ceiling:

| Movement | Ceiling | Reached | Turns | Work at cut-off |
| --- | --- | --- | --- | --- |
| `NXS-LOCAL-0129` | $7.00 | $3.54 | 53 | finished, verified |
| `NXS-LOCAL-0130` | $4.00 | $4.00 | 92 | complete and committed; verify red only on runtime residue |
| `NXS-LOCAL-0131` | $5.00 | $5.00 | 109 | complete, every validation step green, **zero commits** |

In both cut-off cases the work was sound and the worktree intact. Neither
movement was defective; each simply stopped.

**The tool cannot continue them.** `decide_start` reaches its `resume` branch —
same worktree, same branch, same engineer session — only when the existing
record's phase is **not** terminal. Budget exhaustion sets `phase: failed`, and
`TERMINAL_PHASES` contains `failed`. Re-running with a higher
`--max-budget-usd` therefore takes the **fresh dispatch** branch: a new
worktree and a new engineer session with no memory of the run, which repeats
every turn already paid for and can duplicate commits on a lane that already
carries them.

So the Product Owner's instruction — raise the ceiling and continue — is
correct in intent and, before this amendment, impossible in practice.

## 2. The decision

- **R-1.** A movement whose failure is **exclusively** budget exhaustion is
  **resumable**. `decide_start` returns `resume` for it: same worktree, same
  branch, same engineer session, exactly as for a process that died.
- **R-2. "Exclusively" is the whole safety of this amendment.** The signal is
  the recorded `failure_reasons` set, which already distinguishes
  `budget_exhausted` from every other reason. A movement is resumable under
  R-1 only when `budget_exhausted` is present **and no other failure reason
  is**. A movement that also failed verification, exceeded its heartbeat,
  exited non-zero for an unrelated cause, or left its relay in an unexpected
  state is **not** resumable here, and stays terminal.
- **R-3.** A resume under R-1 requires a **higher** `--max-budget-usd` than the
  ceiling that stopped it. Resuming into the same ceiling would stop at the
  same place and burn the resumed session's own start-up cost for nothing; the
  orchestrator refuses it with that reason stated.
- **R-4.** `retry_count` increments on a budget resume exactly as GOV.ORCH.1
  §2.3 already requires of every resume, and `retry_limit` applies unchanged.
  A movement that keeps exhausting successive ceilings is a scoping failure,
  not a budgeting one, and the retry limit is what says so.
- **R-5.** The Product Owner assistant states the new ceiling and its basis
  when resuming, exactly as `roles/PO.md` §3 item 1 requires of the first
  dispatch. A resume is a dispatch decision, not a formality.
- **R-6.** Nothing here changes what a budget ceiling *is*. It remains a hard
  stop the worker cannot raise for itself, and `DEFAULT_MAX_BUDGET_USD` is
  unchanged. This amendment governs only what may happen **after** the stop,
  and only at the Product Owner assistant's explicit instruction.

## 3. Why terminal was the wrong classification

`failed` carries one meaning across the orchestrator: *this run produced
something that should not be continued.* Budget exhaustion does not mean that.
It means the run was **interrupted by a limit the Product Owner set**, which is
the same category as the process dying — the case `resume` already exists for
— and not the same category as a run whose output is wrong.

Classifying an interruption as a defect is what forced the Product Owner
assistant to review and integrate two cut-off movements by hand this session.
That worked, but it put the assistant in the worker's seat, which
`roles/PO.md` §1 says it must not occupy.

## 4. Acceptance

1. `decide_start` returns `resume` for a record whose phase is `failed` and
   whose `failure_reasons` contains `budget_exhausted` and nothing else.
2. It still returns `dispatch` — not `resume` — for a record whose
   `failure_reasons` contains `budget_exhausted` **together with** any other
   reason, and for every other terminal record. A test covers at least one
   mixed-reason case explicitly.
3. A resume under R-1 with a `--max-budget-usd` less than or equal to the
   ceiling that stopped the movement is **refused**, with the reason naming
   both numbers.
4. A resume under R-1 reuses the recorded `worktree_path`, branch and engineer
   session id; it creates no new worktree and consumes no new worker slot,
   exactly as the existing resume branch does.
5. `retry_count` increments, and a movement at `retry_limit` is not resumable
   by this path either.
6. The report of a resumed run records that it was a budget resume and the
   ceiling it was given.
7. `python3 -m pytest tests/test_orchestrator.py -q` passes, including new
   tests for items 1, 2 and 3.

## 5. Out of scope

Changing `DEFAULT_MAX_BUDGET_USD`; letting a worker raise its own ceiling;
automatic resume without an explicit Product Owner assistant instruction;
resuming any other failure reason; changing `retry_limit`; changing
verification, the relay protocol, or the report schema beyond acceptance
item 6.

## 6. Cross-references

- `docs/design/GOV_ORCH_1_SYNCHRONOUS_RUN_AND_ORCHESTRATOR_VERIFY.md` §2.1,
  §2.3 — the `decide_start` contract and the retry accounting this amends.
- `docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md` — the dispatch
  mechanics this sits inside.
- `roles/PO.md` §1, §3 — the role boundary this restores, and the budget
  discipline a resume inherits.
