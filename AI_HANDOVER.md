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

- Date: 2026-09-04. `main` at `cd45f66`. Build
  `op0b_s8_realenv_campaign_corrections` — `AUTOMATED_VALIDATED`.
- The `OP.0b` S8 real-environment campaign ran S8-A three times against the
  approved CP ClusterXL pair, surfaced and merged six corrections
  (PRs #47–#50, #52–#54), then corrected the CP **remote execution
  primitive** itself. Ready for the S8-A retry; no operator prerequisite.

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
  and an inter-read delay on the disproven account-shell conclusion. Both
  were corrected in the persistent-shell change below: the delay is gone
  entirely, and the classification survives only as a narrow diagnostic.
- **Persistent Expert shell** (this movement): the whole battery for one
  member runs inside one `InteractiveSshSession` — one `invoke_shell`, one
  close, zero exec channels, zero per-command CLI initialization. Commands
  are *framed* (a per-session `echo`-of-`$?` end marker) so completion and
  exit status are read explicitly rather than inferred from a quiet period;
  the marker is read-only, stripped before any parser sees it, and
  test-enforced never to reach evidence. Framing is opt-in, so the
  established config-collection path is unchanged. No artificial pacing:
  sequential send/complete/parse is its own backpressure.

## 4. Exact next action

1. Retry S8-A unchanged:
   `py .\main.py --cp-ha-preflight-check --cp-preflight-targets <A>,<B>`.
   Report SAFE counts only. Expect: one SSH login and one Expert shell per
   member, the repeated device-side `ver` initialization gone entirely, and
   the five bare Expert reads (A3-A7) actually executing — A3 yielding
   `High Availability` plus local/peer roles through the canonical parser.
2. Then S8-B (VSX): `vsx stat -v` and any `vsenv` transition run in that
   same Expert shell, no reconnect per VSID.
3. Then S8-C (PAN), untouched by this change (HTTPS API, no shell).
   `Sonnet 5, normal`.

## 5. Test delta

- Full serial suite 1574 passed / 24 skipped / 0 failed (+106 over the S7.5
  baseline of 1468/24/0), from seven new S8 regression files.
- Privacy gate PASS/0; architecture convergence 19 passed; `git diff --check`
  clean.

## 6. New risks

- **The A3 outcome on the real device is now explained, not unresolved.**
  `ha_mode_not_established` persisted because A3 never executed. The wiring
  defect (#49) and the parser were both already correct; neither was the
  cause. A3 stays unvalidated against real output until the S8-A retry runs.
- **The persistent Expert shell is not yet real-environment validated.**
  Its adapter is the one the CP config collector has used against real
  devices, and the framing is covered by unit tests, but this specific
  execution model has only been exercised against a device double. The S8-A
  retry is its first real proof.
- `capability_gap` now means only "the device CLI rejected the read before
  any binary ran". If it still appears after the retry, suspect the
  execution model first — it must never be allowed to stand in for an
  application defect.
- Production strict host-key enforcement is deliberately incomplete by PO
  decision, not a regression — `cp_production_ssh_host_key_trust_hardening`.
- Pre-existing, unrelated: test-order state leaks between
  `test_op0b_s7_readiness_v2.py` and its neighbours
  (`op0b_s7_s6_test_order_isolation`). Default order and the serial suite are
  unaffected.
