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

- Date: 2026-09-04. Build `op0b_s8a_clusterxl_execution_model_console_parity`
  — **REAL_ENV_VALIDATED** on the approved CP ClusterXL pair, PO-accepted.
- **S8-A: PASS.** 8/8 approved reads `success` per member; `state_sync_current`,
  `parity`, `no_split_brain`, `control_sync_link_health` PASS;
  `preemption_known` D-V7b and `flap_history` D-F3 INSUFFICIENT_EVIDENCE by
  decision; `viable_target` closed by the real A5 shape (#60). The Operator
  Console shows the identical seven checks and MODE `ha_new_mode`.
- **S8-B (VSX) and S8-C (PAN): NOT EXECUTED** — this session has no route to
  the devices; the operator runs them.

## 2. What changed this session (PRs #52–#60, all merged to `main`)

- **Execution primitive corrected (#55).** The S5 collector issued one
  non-interactive exec channel per read; the device dispatched each through
  the Gaia CLI wrapper (one `clish -c ver` per channel) and the five bare
  Expert reads never reached an Expert shell. That does *not* prove the
  account's shell (an earlier claim, withdrawn); it proves one SSH transport
  is not one Expert context. Now: one persistent Expert shell per member
  (`InteractiveSshSession`, existing adapter), reads framed by a per-session
  `echo` of `$?` (read-only, stripped, never a fact).
- **Pacing (#56, PO decision):** `INTER_COMMAND_DELAY_SECONDS = 0.3` strictly
  between completed reads — N reads, N-1 waits, none first/last; never
  retry/backoff/reconnect/adaptive; sleeper injected in tests.
- **Real output shapes (#52/#56/#60):** `fw stat` table; `cphaprob -a if`
  annotated rows + `Non-Monitored` + trailing VIP block; `cphaprob -ia list`
  healthy sentence `There are no pnotes in problem state` (positive
  `any_problem=False`, count not reported); A8 count wording. All
  fail-closed; an unrecognised shape logs a value-free layout skeleton (#58).
- **Console parity (#59):** the preflight evaluates once and hands the same
  `compute_ha_readiness` record to the CLI summary and to
  `run_html_export(failover_readiness_report=...)`; the report projects it,
  evaluates nothing, refuses a snapshot alongside a record. No snapshot
  persistence, no TTL; normal reports byte-identical; UI projection-only.
  The live console (separate process) keeps its stored-telemetry answer.
- **Disclosure (#53):** per-read outcome in the safe summary (both vendors).
- **Backlog:** `cp_preflight_ccp_tablestat_evidence` (PO request; NEW command,
  gate row + readiness mapping first).

## 3. Exact next action

1. **S8-B (VSX)** — operator runs
   `py .\main.py --cp-ha-preflight-check --cp-preflight-targets <VSX-A>,<VSX-B>`
   and opens the regenerated `index.html`. Expect per member 1 transport,
   1 Expert shell, ~0.3 s spacing, B1 `vsx stat -v` in the same shell, no
   reconnect per VSID; report identical to the CLI for the VSX unit; one
   physical parent, two members, VSIDs not duplicated, VS child does not
   inherit the parent verdict. Paste SAFE counts plus any
   `observed layout:` log line.
2. **S8-C (PAN)** — `py .\main.py --pan-ha-preflight-check ...` against the
   approved pair; one API context per member, P1/P2/P4 only, no PAN pacing
   unless evidence shows a need; B2 stays NOT ESTABLISHED unless
   already-authorized evidence closes it; report parity via the same seam.
3. Mechanical parser fixes inside frozen semantics: fix and continue. Stop
   for PO on any new command/API, credential, mutation, retry authority,
   identity or readiness-contract change, unresolved vendor semantic,
   schema change, CLASS 1/2 behaviour. `Sonnet 5, normal`.

## 4. Test delta

- Closing full serial run: see `CURRENT_STATE.md` "Automated test baseline"
  (recorded from `pytest_result.log` at close). Render harness (Playwright)
  PASS; architecture convergence 19 passed; `git diff --check` clean.
- Ten S8 regression files now exist; `tests/test_op0b_s75_preflight_entrypoint.py`
  harness points `repository_root` at the real repo because an explicit
  preflight now regenerates the report.

## 5. New risks

- VSX/PAN parity is proven synthetically only; real proof is S8-B/S8-C.
- A4/A5/A8 shapes validated on one real release; the layout diagnostic
  covers a fourth shape without guessing.
- `capability_gap` means only "device CLI rejected the read before any binary
  ran"; if it reappears, suspect the execution model first.
- D-V7b/D-F3 keep overall readiness INSUFFICIENT_EVIDENCE by decision.
- Strict host-key production enforcement deferred
  (`cp_production_ssh_host_key_trust_hardening`, P0). Pre-existing:
  `op0b_s7_s6_test_order_isolation` (P2).
