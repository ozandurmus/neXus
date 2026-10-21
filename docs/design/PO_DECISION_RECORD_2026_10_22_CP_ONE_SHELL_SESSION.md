# PO decision record — 2026-10-22: Check Point failover runs its reads in one shell per member per pass

**Status: RATIFIED — PO DECISION (chat, 2026-10-22: "Onaylıyorum, önce tek kümede" — "I approve; one cluster first").**
Recorded by the engineering session on `lane/cp-failover-one-shell`. It approves the literals of
[CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md](CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md) §5 and fixes the rollout order.
Automated validation only; nothing here is real-environment validated yet.

## 1. Context

The Check Point audit log shows one "Log In" entry per command of a failover readiness run (about 30-40 per run), against
the design rule "one SSH session per member per pass" (FAILOVER_READINESS_CHECKS_CP_PAN_DRAFT.md, PO decisions 2026-09-27,
item 2). The cause and the design are in the one-shell document, §2 and §3.

## 2. Decisions

| # | Decision | Status |
| --- | --- | --- |
| 1 | The four literals L1-L4 below are approved as written. | RATIFIED. Amended by A1 below (pending); gate row seeded DRAFTED (V175) until A1 is confirmed |
| 2 | Roll out on **one cluster first**: the shell path runs only for clusters allow-listed on the worker (`NEXUS_CP_FAILOVER_ONE_SHELL=enabled` and `NEXUS_CP_FAILOVER_ONE_SHELL_CLUSTERS=<cluster ref>`). | RATIFIED, implemented |
| 3 | After the pilot cluster's audit log shows one login per member per pass: the failover fleet (`*` or the full list). | Approved as order; each step is a worker configuration change |
| 4 | Later, separately: inventory, configuration, backup and system-log collection on the same one-shell pattern. | Direction only; each needs its own design and gate review. Not in this change |

### Approved literals (verbatim from the design §5)

| # | Literal sent inside the shell | Replaces |
| --- | --- | --- |
| L1 | (channel) one SSH `shell` channel with PTY `vt100` 4096x10000 per member per run | one `exec` channel per command |
| L2 | `echo __NX_<nonce>_BEGIN__; <read>; echo __NX_<nonce>_END_$?__` -- `<nonce>` 32 lowercase hex characters from `SecureRandom`, new per command | nothing (new framing) |
| L3 | `<read>` = the bare gated read (e.g. `cphaprob stat`, `cpstat -f policy fw`, `cphaprob show_bond "<bond>"`) | `bash -lc '<read>'` |
| L4 | `<read>` in a VS run = `vsenv <VSID> && <cmd>` | `bash -lc 'vsenv <VSID> && <cmd>'` |

Not changed by this decision: the read gates themselves (each read is still resolved by its own SSH_EXEC row), and the
`clusterXL_admin down/up` writes, which stay on their exec path (design §3.5).

## 3. What changed

- **Gate row.** `V175__cp_failover_shell_framing_gate.sql` (renumbered from V174, which another branch took) inserts
  `cp_failover_shell_framing` (SSH_INTERACTIVE, CLASS 0 read) with the amended key of A1, `DRAFTED` until A1 is
  confirmed; source pointer this record. The fixture row is the same.
- **Worker.** The shell path runs for a Check Point readiness (including scheduled readiness), execution or resume run only
  when `NEXUS_CP_FAILOVER_ONE_SHELL=enabled` and the run's cluster ref is in `NEXUS_CP_FAILOVER_ONE_SHELL_CLUSTERS`
  (comma-separated; `*` = all; empty or unset = none). Every other run uses the exec path unchanged.

## 4. Next step

Design §6 step 3: set both variables for the one pilot cluster on the worker, restart, run one readiness pass, and the PO
reads that cluster's audit log for the run's window (counts only).

## 5. Amendment A1 (2026-10-22) -- PENDING PO CONFIRMATION

The external review ([ARGON_REVIEW_2026_10_22_CP_ONE_SHELL.md](ARGON_REVIEW_2026_10_22_CP_ONE_SHELL.md), P1-3, P1-5,
P2-1) found that the approved L2 changes two behaviours of the exec path. The fix changes the literal, so it needs the
PO's confirmation before the gate row is signed off (approval of one command does not approve the next):

| # | Literal | Replaces |
| --- | --- | --- |
| L2' | `echo __NX_<nonce>_BEGIN__; ( <read> ) 2>/dev/null; echo __NX_<nonce>_END_$?__`; for `cphaprob -a if`: `... ( <read> ) 2>&1; ...` | L2 |
| L5 | Ctrl-C (one 0x03 byte) once on a read timeout, before the shell is closed | nothing |

L1, L3 and L4 are unchanged. Detail: one-shell design §7.

