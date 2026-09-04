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

- Date: 2026-09-04. Product build unchanged: `op0b_s8a_clusterxl_execution_model_console_parity`
  REAL_ENV_VALIDATED; S8-A PASS, PO-accepted; **S8-B (VSX) / S8-C (PAN) NOT
  EXECUTED** — operator-run.
- **OP.2.0** (`docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`):
  the Opus draft received its independent challenge review this session with
  the PO's eleven decisions applied. Status: `DRAFT` — **READY FOR PO
  FREEZE**, not frozen. Not a build; CLASS 2 has no member; no source changed.

## 2. What changed this session (docs + project metadata only)

- **OP.2.0 draft corrected in place** (§"Independent challenge review —
  2026-09-04" carries the matrix): state machine 8+7 → 4+6 (`LOCKING`,
  `LOCKED`, `EVALUATING`, `VERIFYING`, `SUCCEEDED_WITH_WARNINGS` removed; the
  re-preflight loop removed); HA-entity lock = the action record's per-entity
  uniqueness rule; member admission per device-contact stage, never across
  the human wait; quarantine = derived predicate over the unacknowledged
  `OUTCOME_UNKNOWN` record (one owner); boundary commit = guarded
  compare-and-set with one winner; adapter `check_precondition` before the
  boundary; `FAILED_NO_CHANGE` unreachable while `settle_observation` is
  `UNKNOWN`; no argv/CLI entry point; no `PERMIT` outside `tests/`;
  approval-policy boundary carries four-eyes / maintenance window; AC-15–19.
- **Parent reconciled:** `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §10.2
  + inline supersession markers (auto-rollback, `FAILED_ROLLED_BACK`,
  freshness window, §7 layout, per-VS units, feature flag/token, class 0
  sweep not universal). History kept, not rewritten.
- **Project state:** `op_reversal_model`, `op_outcome_unknown_recovery` →
  `decided`; `op_four_eyes`, `op_continuity_tolerance`, `op_emergency_evac`,
  `op_aa_vsls_scope`, `op_degraded_verdict` re-pointed to their real
  milestone, none an OP.2.0 freeze blocker; `ha_entity_operational_lock` and
  `failover_controlled_execution` (backlog + feature registry) no longer say
  "auto-rollback".

## 3. Exact next action

1. **PO: freeze decision on OP.2.0.** Flip the status line to `FROZEN` (or
   send it back with the objection). Nothing else is owed before that.
2. **Product work continues regardless:** S8-B (VSX) then S8-C (PAN) exactly
   as `project/roadmap.json` `now_next.next` describes — operator-executed,
   SAFE counts only, mechanical parser fixes inside frozen semantics only.
   `Sonnet 5, normal`.
3. **After freeze:** `OP.2.A` (typed action model, 4+6 lifecycle, audit
   record, unconditional-`DENY` authorizer, convergence assertions; zero
   device I/O) at `Sonnet 5, normal`; `OP.2.1` CP mutation command gate
   (docs only, official sources) in parallel at `Sonnet 5, extended`;
   `OP.2.B` after `A` at extended.

## 4. Test delta

- No product code changed. Architecture convergence: 19 passed;
  `metadata_warnings == []`; repository privacy gate PASS; `git diff --check`
  clean. Full regression not run (not required for a docs/metadata change).
- Note for Linux/container sessions: `py` does not exist there; `python3 -m
  pytest` with `requirements.txt` + `requirements-dev.txt` installed.

## 5. New risks

- OP.2.0 is reviewed, not frozen: nothing in it authorizes implementation
  until the PO changes the status line.
- `FAILED_NO_CHANGE` is unreachable until the CP `settle_observation` is
  measured in the `OP.2.D` pilot — expected, and the pilot must record it.
- Single-coordinator-process topology is now an explicit OP.2 invariant; a
  multi-worker console (`per_vendor_worker_split`, P2) would need its own
  contract before touching class 2.
- Unchanged from the previous session: VSX/PAN parity proven synthetically
  only (S8-B/S8-C owed); D-V7b/D-F3 keep readiness INSUFFICIENT_EVIDENCE;
  `cp_production_ssh_host_key_trust_hardening` (P0) deferred.
