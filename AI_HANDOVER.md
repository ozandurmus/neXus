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

- Date: 2026-09-04. `main` at `d387bbf` + this branch. Build
  `op0b_s8_realenv_campaign_corrections` — `AUTOMATED_VALIDATED`.
- **S8-A is PO-accepted as PASS** on the approved CP ClusterXL pair: 8/8
  reads `success`, four checks PASS (`state_sync_current`, `parity`,
  `no_split_brain`, `control_sync_link_health`), `flap_history` at its
  authorized ceiling (`threshold_policy_unresolved:D-F3`), `preemption_known`
  at D-V7b. One unexpected evidence gap remains: `viable_target` =
  `unknown:cp_pnote_any_problem` (A5 has a third output shape; the #58
  layout diagnostic will name it on the next run, value-free).
- This branch closes the **CLI ≠ Operator Console** integration defect for
  the same unit/invocation (see "Console parity" below).

## 2. Root cause of the S8-A read failures (settled)

**An application execution-path defect, not an environment fact.** Three
wrong diagnoses preceded it — a `$PATH`/PTY theory, and then the conclusion
that the collector account lands in Clish. The PO rejected the second as
contradicting the validated CP execution contract, and was right.

The device's own `clish`/`xpand` audit trail shows each *non-interactive
exec channel* being dispatched through the Gaia CLI wrapper: 8 reads → 8
device-side `clish -c ver` initializations (the "repeated `ver`" long
mistaken for our own command), only the three `clish -c '...'` forms
(A1/A2/A8) executing, and the five bare Expert reads never reaching an
Expert shell at all.

That does **not** prove the account's login shell is Clish. It proves the
collector never established an Expert *execution context*: one SSH
transport is not one Expert shell, and the old model opened one independent
exec channel per read — eight execution contexts, each re-entering the
wrapper. An interactive login (the operator's own path, which the contract
fixes as Expert) was never used.

Corrected by running the whole battery inside **one persistent Expert shell
per member**, reusing the existing real-environment-validated
`InteractiveSshSession`. No new command, credential, or device change.

## 3. What changed this session

- **#46** `utils/cp_ssh_trust.py`: strict preflight counted the wrong Paramiko
  store, so `strict=True` was unusable with a correct `known_hosts`.
- **#47** typed `HostKeyNotTrustedError`: host-key rejections (and, in
  `direct_ssh_probe`, auth failures and key mismatches) no longer enter the
  connect retry loop.
- **#48** `run_cp_preflight` defaults `strict_host_key=False` — the same
  compatibility trust every sibling CP SSH caller already had. Strict stays
  implemented and selectable; production enforcement deferred by PO decision
  to backlog `cp_production_ssh_host_key_trust_hardening` (P0).
- **#49** A3 differential: `collect_member` now merges
  `local_role`/`cluster_mode` into the `fields` contract
  `project_cp_preflight_facts` always documented, via the established
  canonical parsers.
- **#50** device session architecture: `_run_exec` gains explicit `use_pty`
  (default unchanged for all existing callers); the preflight session binds
  `use_pty=False`; `MemberSession` is now the per-member execution context
  resolving its command plan once. Per member: connects 1, closes 1, exec
  channels == scheduled reads.
- **#52** A7 `fw stat` parsed from its real column table (`HOST POLICY DATE`),
  legacy `Policy name:` shape kept; first regression test driving the real
  CLI call graph with only `paramiko.SSHClient` faked.
- **#53** per-read outcome disclosure in the safe summary (both vendors) —
  the change that made the root cause above visible at all.
- **#54** (merged, then superseded) added a `capability_gap` classification
  and an inter-read delay on the disproven account-shell conclusion. The
  classification survives only as a narrow diagnostic; the account-shell
  conclusion is withdrawn. (Pacing later returned on real evidence — see
  #56 — but as a session-stability measure, not as that conclusion's remedy.)
- **Persistent Expert shell** (this movement): the whole battery for one
  member runs inside one `InteractiveSshSession` — one `invoke_shell`, one
  close, zero exec channels, zero per-command CLI initialization. Commands
  are *framed* (a per-session `echo`-of-`$?` end marker) so completion and
  exit status are read explicitly rather than inferred from a quiet period;
  the marker is read-only, stripped before any parser sees it, and
  test-enforced never to reach evidence. Framing is opt-in, so the
  established config-collection path is unchanged.
- **Console parity (this movement)**: after an explicit HA preflight the
  generated report showed the stored-telemetry reasons and `MODE = unknown`
  for the very unit the CLI had just evaluated fresh. Closed by ONE seam:
  the workflow evaluates once and hands that same `compute_ha_readiness`
  record to both the CLI summary and `run_html_export(...,
  failover_readiness_report=...)`; `build_failover_readiness_payload`
  projects it and evaluates nothing (a snapshot alongside a finished record
  is refused). `PreflightSnapshot` stays in memory -- no file, cache, TTL.
  Normal reports are byte-for-byte unchanged. Boundary: the *live* console
  (`console/payloads.py`, a separate process) cannot see an ephemeral
  snapshot; the regenerated `index.html` from the same invocation is the
  parity surface.
- **#56** real-environment follow-ups. (a) **Pacing**: the battery runs
  correctly in one Expert shell but destabilizes the SSH session issued back
  to back, so reads are paced by one constant,
  `INTER_COMMAND_DELAY_SECONDS = 0.3`, strictly BETWEEN reads — N reads, N-1
  waits, none before the first, none after the last, each after deterministic
  completion. Never retry/backoff/reconnect/adaptive; the sleeper resolves at
  call time so tests inject it. (b) **A4/A5/A8 output shapes**: those reads
  succeeded but their parsers matched zero rows (A4/A5) or no count (A8) —
  same class as the `fw stat` table. All three now accept the real layouts
  and still fail closed on an unknown one.

## 4. Exact next action

1. Real ClusterXL **parity check**: rerun
   `py .\main.py --cp-ha-preflight-check --cp-preflight-targets <A>,<B>`
   and open the `index.html` it regenerates. Expect the unit's seven checks
   and mode in the report to equal the CLI summary exactly (four PASS,
   D-V7b, D-F3, and `viable_target` identical), MODE the fresh ClusterXL
   mode, never legacy `unknown`. The log line `A5 cphaprob -ia list:
   executed but produced no usable evidence; observed layout: ...` gives the
   value-free shape needed to close the pnote gap — fix that parser then.
2. Then S8-B (VSX): `vsx stat -v` and any `vsenv` transition in the same
   Expert shell, same 0.3s pacing, no reconnect per VSID — validate BOTH
   fresh backend readiness and report parity through the same seam.
3. Then S8-C (PAN): same seam, no PAN pacing unless PAN evidence shows a
   need. `Sonnet 5, normal`.

If the SSH session still drops at 0.3s spacing: **stop.** Do not tune the
delay upward by trial and error — that is a transport/session stability
problem needing its own root cause.

## 5. Test delta

- Full serial suite 1615 passed / 24 skipped / 0 failed at `d387bbf`; this
  movement adds 12 parity tests (affected-suite run 399 passed / 2 skipped).
- Privacy gate PASS/0; architecture convergence 19 passed; `git diff --check`
  clean.

## 6. New risks

- **The persistent Expert shell IS real-environment validated** (S8-A retry:
  8/8 reads `success`, `ha_mode_not_established` gone, three checks PASS).
  A3 executes and yields cluster mode plus local/peer roles.
- **The A4/A5/A8 shape fixes are not yet real-environment validated.** They
  were written against documented vendor layouts, not against this device's
  observed output, because the run reports safe counts only. If a check is
  still `unknown:` after the next retry, that parser has a third shape and
  needs the real one — not another guess.
- **`preemption_known` and `flap_history` cannot reach PASS in this
  campaign.** D-V7b (A9 not authorized) and D-F3 (flap threshold) are
  unresolved by decision; a parsed `cp_failover_count` has no threshold to
  be judged against. Overall S8-A readiness stays INSUFFICIENT_EVIDENCE.
- `capability_gap` now means only "the device CLI rejected the read before
  any binary ran". If it appears again, suspect the execution model first —
  it must never stand in for an application defect.
- Production strict host-key enforcement is deliberately incomplete by PO
  decision, not a regression — `cp_production_ssh_host_key_trust_hardening`.
- Pre-existing, unrelated: test-order state leaks between
  `test_op0b_s7_readiness_v2.py` and its neighbours
  (`op0b_s7_s6_test_order_isolation`). Default order and the serial suite are
  unaffected.
