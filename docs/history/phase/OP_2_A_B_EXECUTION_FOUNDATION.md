# OP.2.A / OP.2.B — CLASS 2 execution foundation

## Status

**IMPLEMENTED 2026-09-04** against the frozen
`docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md` contract.
Not a contract of its own — this doc records what the two implementation
movements built, per that contract's own "Definition of done" pointer.

## Scope delivered

New package `utils/operate/` (zero device I/O, no vendor adapter, no
transport import, no command text, no CLI/argv entry point):

- `states.py` — the frozen four-non-terminal/six-terminal state machine and
  its legal-transitions graph (P6/AC-6).
- `record.py` — the durable `ActionRecord` audit shape (P13) and
  `compute_proposal_digest` (P5).
- `authorization.py` — the `authorize()` boundary; `DenyAllAuthorizer` is the
  only production implementation (P2, AC-1, AC-16).
- `approval_policy.py` — the one-confirmation `approval_policy` boundary (P5).
- `adapter.py` — the typed `VendorCapabilityAdapter` boundary shape only, no
  implementation (P11, P15).
- `eligibility.py` — `PreflightProvider`/`EligibilityEvaluator` injection
  seams; no production implementation exists (`OP.2.C`'s job).
- `store.py` — `ActionRecordStore`: the HA-entity lock as record uniqueness
  (P8), the guarded compare-and-set transition (P6), the derived quarantine
  predicate (P10).
- `coordinator.py` — `ActionCoordinator`: `create_action` → `run_preflight` →
  `confirm` (the guarded mutation-boundary commit) → crash
  `reconcile_on_startup` → `acknowledge_unknown_outcome`.

A seventh `utils.evidence_backend` storage concern,
`ActionRecordBackend` (filesystem default / opt-in Postgres), backs the
store — the same dumb-storage shape `ConsoleJobBackend` uses, deliberately
without its orphan-sweep-to-`failed` behaviour (an `EXECUTING` record left by
a dead process resolves to `OUTCOME_UNKNOWN`, never `failed`).

`tests/test_op2_a_b_execution_foundation.py` (67 tests) proves, with real
thread concurrency where the contract requires it: lifecycle transitions
end-to-end (`CREATED` → `PREFLIGHTING` → `AWAITING_CONFIRMATION` →
`EXECUTING` → terminal); duplicate/idempotent `action_id` and confirmation
handling; the outer entity lock under a concurrent create race; member
admission held only during a device-contact stage; the guarded boundary
transition with two racing confirmations and a confirm/cancel race, each
yielding exactly one winner and at most one `execute_once` call; crash
reconciliation for every state in the contract's table; `OUTCOME_UNKNOWN`
quarantine and its refusal of new actions; `FAILED_NO_CHANGE`
unreachability while `settle_observation` is unknown; and that no
production wiring anywhere reaches a device — `DenyAllAuthorizer` denies at
`create_action` before a record even exists, and independently, no adapter
exists anywhere in the product, so eligibility fails
`no_adapter_capability` even under a permissive test authorizer.

## What remains CLASS 2 structurally unreachable

- `utils.action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE` gains no member.
- `console/registry.py` gains no job type; `console/runner.py` and `main.py`
  gain no reference to `utils.operate`.
- No `Authorizer` outside `tests/` returns `PERMIT` (source-scanned).
- No vendor adapter implementation exists anywhere in the repository.

## Closes

- `project/backlog.json` `ha_entity_operational_lock` (OP.0b.0 §26 row X-1) —
  the per-HA-entity lock now exists as the record-uniqueness rule described
  in that backlog item's own MODEL note.

## Not in scope (unchanged, still gated)

Everything gated on `OP.2.C`'s prerequisites: the first vendor adapter
(Check Point ClusterXL), the `OP.2.1` command-gate package, `DEPLOY.1A`
OIDC/RBAC, real preflight/readiness wiring, the Operator Console class 2
workflow, and the real-environment pilot. See the frozen contract's
"Implementation plan" and "Explicit blockers" for the full gate list.

## Validation

Targeted: `tests/test_op2_a_b_execution_foundation.py` (67 passed) plus
`tests/test_architecture_convergence.py` (unaffected, 20 passed). Fast PR CI
(`.github/workflows/validation.yml` `validate` job) covers the rest of this
bounded change. Full regression not required — no existing product code path
was modified beyond an additive `utils/evidence_backend.py` storage concern
and a new `pytest.ini` marker.
