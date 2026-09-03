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

- Date: 2026-09-04. `main` at `57d78f1`. Build
  `op0b_s8_realenv_campaign_corrections` — `AUTOMATED_VALIDATED`.
- The `OP.0b` S8 real-environment campaign ran S8-A once against the approved
  CP ClusterXL pair, surfaced four defects, corrected and merged all four
  (PRs #47–#50), and is now **blocked on device SSH access**.

## 2. What changed this session

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
  channels == scheduled reads, PTY requests 8/9 → 0. PAN audited, already
  compliant, untouched.

## 3. Exact next action

1. **Operator confirms SSH access to the approved CP pair is restored.**
   During the campaign the device stopped offering password authentication;
   operator recovery (clish `set ssh server password-authentication yes` +
   `save config`, then direct `/etc/ssh/sshd_config` + Gaia template edits,
   then `service sshd restart`) is unconfirmed. Nothing in this repository
   can write device configuration — the collectors are read-only by
   construction and test-enforced.
2. Then retry S8-A unchanged:
   `py .\main.py --cp-ha-preflight-check --cp-preflight-targets <A>,<B>`.
   Report SAFE counts only. Expect the per-command device-side CLI init to
   be gone. Then S8-B (VSX), then S8-C (PAN). `Sonnet 5, normal`.

## 4. Test delta

- Full serial suite 1535 passed / 24 skipped / 0 failed (+67 over the S7.5
  baseline of 1468/24/0), from four new S8 regression files.
- Privacy gate PASS/0; architecture convergence 19 passed; `git diff --check`
  clean.

## 5. New risks

- **A3 on the real device is unresolved.** The wiring defect is fixed and
  proven, but the one live retry after it was never confirmed to have run on
  the fixed commit before access was lost. Source tracing shows the
  established parser and the S5 projection now agree exactly, and the parser
  survives terminal-escape contamination — so if `ha_mode_not_established`
  persists on a confirmed HA pair over the now-clean channel, the remaining
  divergence is device-side execution context (Expert-shell vs direct-Clish
  landing), not the parser.
- Production strict host-key enforcement is deliberately incomplete by PO
  decision, not a regression — `cp_production_ssh_host_key_trust_hardening`.
- Pre-existing, unrelated: test-order state leaks between
  `test_op0b_s7_readiness_v2.py` and its neighbours
  (`op0b_s7_s6_test_order_isolation`). Default order and the serial suite are
  unaffected.
