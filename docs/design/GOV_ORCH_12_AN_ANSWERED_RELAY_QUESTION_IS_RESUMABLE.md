# GOV.ORCH.12 — An answered relay question is resumable

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-14.** Extension of
`docs/design/GOV_ORCH_10_BUDGET_EXHAUSTION_IS_RESUMABLE.md` and its amendment
`GOV_ORCH_10A_BUDGET_RESUME_ENTAILED_REASONS.md` (both FROZEN, neither edited
in place) to a second stop that is not a defect: an engineer that raises a
`RELAY_QUESTION` and exits cleanly. Backlog item
`gov_orch_resume_after_relay_question` (P1).

## 1. The measurement

`AGENTS.md` and every worker brief require an engineer to raise a
`RELAY_QUESTION` and stop rather than reconcile a contract conflict silently.
The engineer then exits **0** with its relay open, and `orchestrator.py`
records `phase=failed`, `failure_reasons=["relay_not_closed"]`. Nothing is
wrong with the run: the worker did exactly what the law asks.

Today that record is terminal. Observed three times (movements
`NXS-LOCAL-0147`, `NXS-LOCAL-0157`, `NXS-LOCAL-0164`): after the Product
Owner answered on the relay, the movement could not be continued. Each time
the worktree and the engineer's session were discarded and the movement was
dispatched fresh, paying for the whole baseline again — `0164` alone re-ran
its full validation baseline twice for one three-line decision.

## 2. The decision

- **RQ-1. A movement that stopped on its own relay question is resumable
  once per answer.** The resume reuses the existing worktree, branch and
  engineer session exactly as the budget resume of GOV.ORCH.10 R-1 does.
- **RQ-2. The resumable signature is closed (default-closed, as R-2b):**
  the record's `phase` is `failed`, its `exit_code` is `0`, its
  `failure_reasons` are exactly `{"relay_not_closed"}` — no other reason,
  present or future — **and** the relay's entries end with a
  Product-Owner-authored answer (`RELAY_DECISION`, `RELAY_CORRECTION`, or a
  `RELAY_NOTE` whose `next_actor` is `engineer`) posted **after** the
  engineer's `RELAY_QUESTION`. Any other combination blocks the resume and
  the movement is dispatched fresh, as today.
- **RQ-3. One resume per answer.** A resumed run that stops again on a new
  `RELAY_QUESTION` is resumable again once that new question is answered.
  A resume attempt with no answer newer than the last resume is refused
  with a reason naming the missing answer — never a silent re-dispatch.
- **RQ-4. The resume carries a recovery note**, in the same place the
  budget resume carries its own (`needs_resume_recovery_note`), telling the
  engineer that its question was answered, naming the answering entry's
  marker and subject, and instructing it to read the relay before doing
  anything else.
- **RQ-5. Budget.** The resume inherits the prior ceiling unless a higher
  one is given; GOV.ORCH.10 R-3's refusal of a same-or-lower budget resume
  applies only to a *budget* resume and does not apply here, because the
  ceiling was not the reason for the stop.
- **RQ-6. Provider neutrality.** The resume uses the provider's own resume
  path (`claude --resume`, `codex exec resume <id>`) per GOV.ORCH.2 §2.3; a
  provider with no resume path falls back to a fresh dispatch in the same
  worktree, and the record says which happened.

## 3. What this does not change

GOV.ORCH.10's budget resume and its R-2a/R-2b allowlist; the retry limit;
the worker-slot accounting (a resume is not a new slot, R-1); the relay
protocol itself; every other failure reason stays terminal.

## 4. Acceptance

A movement stopped on a relay question, then answered, resumes into the same
worktree and branch with the recovery note present; the same movement with
no answer posted is refused; a record carrying any reason beyond
`relay_not_closed` is refused; each case is a unit test over the pure
decision function, not a live dispatch.

## 5. Cross-references

- `GOV_ORCH_10_BUDGET_EXHAUSTION_IS_RESUMABLE.md` R-1..R-6;
  `GOV_ORCH_10A_BUDGET_RESUME_ENTAILED_REASONS.md` R-2a..R-2c.
- `GOV_ORCH_2_PROVIDER_ADAPTER.md` §2.3 (resume per provider).
- `NEXUS_AGENT_RELAY_PROTOCOL.md` — the markers RQ-2 names.
