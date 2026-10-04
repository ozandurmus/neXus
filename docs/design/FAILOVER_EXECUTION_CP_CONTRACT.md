# Check Point cluster failover: approval window, pre-checks, failover, post-checks

**Status:** FROZEN -- Product Owner decisions 2026-09-27 (§8). First release: Check Point ClusterXL and VSX (chassis
level). Palo Alto, FortiGate and Cisco ASA follow on the same skeleton, each with its own approved commands.
Supersedes, for Check Point execution, the read-only-only scope of `FAILOVER_READINESS_CHECKS_CP_PAN_DRAFT.md`.

## 1. The flow
1. **Approval window (pre-approval).** A `security_admin` records: which cluster(s), from / until (local time shown,
   stored UTC), reason. Only then can a failover be started for that cluster, and only inside the window. No window,
   or outside it -> refused before any device contact ("no pre-approval").
2. **Start.** Inside the window an `operator` (or the `security_admin` who approved it) presses **Failover** on the
   cluster, or schedules it for a time inside the window.
3. **Pre-checks** on both members (one SSH session per member, commands one at a time, in order).
   Every check must pass; any FAIL or UNKNOWN stops the run before the failover ("not suitable now").
4. **Failover.** On the active member: `clusterXL_admin down`. Wait (poll `cphaprob stat` on both members, every 3 s,
   up to 60 s) until the former standby reports **Active** and the former active reports **Down**.
5. **Post-checks** on the new active (and the old one), the same set as the pre-checks, compared with the
   pre-check values (§3).
6. **Return to standby.** When the post-checks pass: on the former active `clusterXL_admin up`; wait until it
   reports **Standby**. The cluster is two members again, roles swapped.
7. **Any problem after step 4:** stop, change nothing more, mark the run failed at that step, notify
   (`job_failure` route). The operator decides. No automatic fail-back.

## 2. Pre-checks (both members; VSX: physical member and every VS via the existing `vsenv <VSID>` wrapper)
| # | Check | Command (Expert) | PASS when |
| --- | --- | --- | --- |
| 1 | Cluster state | `cphaprob stat` | exactly one Active and one Standby, both members listed by both, mode supported |
| 2 | Cluster IP table | `cphaprob tablestat` | both observers report the same address set per opaque member, including interface names when present; numeric interface indices are observer-local and excluded (PO measured correction, 2026-10-03) |
| 3 | Cluster interfaces | `cphaprob -a if` | every required interface UP on both; CCP up |
| 5 | ARP | `arp -an` (entry count only) | standby has a comparable ARP population (recorded for §3) |
| 6 | Connections | `fw tab -t connections -s` (#VALS, #PEAK) | recorded; standby within sync tolerance of active |
| 7 | New connections / s | **deferred** -- `fw tab -t connections -s` gives the table population and peak, not a rate (Check Point CLI reference); the PO names the CPS command, then it is added | -- |
| 8 | Traffic rate | two samples of `cat /proc/net/dev`, 5 s apart (cluster interfaces only) | recorded (baseline for §3) |
Unrecognised output -> UNKNOWN (blocks). Values are stored as derived numbers and states only, never raw output.

## 3. Post-checks (same commands on both members)
- 1: the former standby is Active, the former active is Down (then Standby after step 6).
- 2, 3: as in the pre-checks, on the new active.
- 5: new active ARP count at least 80 % of the pre-check active's.
- 6: new active connection count at least 80 % of the pre-check active's (sync carried them).
- 8: new active carries traffic: rate at least 50 % of the pre-check active's (not zero). (7 deferred, §2.)
Tolerances are constants in one place so the PO can tune them.

## 4. Commands and gates (migration: gate rows)
Read (new): `cphaprob tablestat`, `arp -an`, `fw tab -t connections -s`, `cat /proc/net/dev`; each also in the
VSX-wrapped form. Existing: `cphaprob stat`, `cphaprob -a if`, `vsx stat -v`.
Write (new, CLASS operational state change): `clusterXL_admin down`, `clusterXL_admin up` -- only through this flow,
never console-submittable, never in a script run. Ledgered before and after (job + audit rows).

## 5. Data
- `failover_approval(approval_id, cluster_ref, window_from, window_until, reason, approved_by, approved_at,
  revoked_at, revoked_by)`.
- `failover_run(run_id, cluster_ref, approval_id, requested_by, scheduled_for, state, step, started_at,
  finished_at, outcome, failed_check, message)`; `state` in PLANNED, PRECHECK, FAILING_OVER, SWITCHED, POSTCHECK,
  RETURNING, DONE, STOPPED.
- `failover_check_result(run_id, phase pre|post, member_ref, vs_id, check_no, status, derived jsonb, observed_at)`.
- Audit rows for every approval, start, command sent, and state change.

## 6. Screen
On the cluster (Devices › cluster › a new **Failover** tab) and in Operations:
- Approval card (security_admin): windows list, "Approve a window" (cluster(s), from, until, reason), revoke.
- Failover card: current window state; **Failover** / **Schedule** buttons, enabled only inside an approved window
  for an allowed role.
- Run view: a simple horizontal stepper -- **Preparing** (pre-checks) -> **Failing over** -> **Switched** ->
  **Checking** -> **No problems found** (or the step where it stopped, in red, with the failed check named). Under
  the stepper a compact table: check, before, after, result. No raw device text.
- VSX: the chassis run shows its VS rows under it. Palo Alto vsys are never separate units: shown under their device.

## 7. Safety rules
Refused before contact: no window / outside window / wrong role / cluster not exactly one Active + one Standby /
another run active on the cluster. One run per cluster at a time. Host keys trusted. Every command through the gate
registry. The legacy Python `utils/failover/` read-only rule is unchanged (this lives in ui2).

## 8. Product Owner decisions (2026-09-27)
- Flow and screen as §1/§6 ("hazırlanıyor, failover ediliyor, geçiş yapıldı, kontrol ediliyor, sorun görünmüyor ...
  görsel ve basit"); pre-approval window by the super admin, operator presses inside it; no pre-approval -> not
  possible.
- Checks: `cphaprob stat`, `cphaprob tablestat`, `cphaprob -a if`, ARP, CPS, traffic rate, connection count; all
  re-checked after the switch. `installed_jumbo_take` is not a failover check.
- CP method: `clusterXL_admin down` then `up` on the former active. On a post-check problem: stop and warn.
- Roles: normally security_admin approves and an operator starts; security_admin may also start.
- Scope: Check Point + VSX first.

## 9. Amendment 2026-09-27 (engineering, found during implementation)
Check 7 (new connections per second) is deferred: the approved `fw tab -t connections -s` reports the connection
table's current population (#VALS) and peak (#PEAK), not a new-connection rate, and two samples cannot derive one.
The first release runs checks 1-6 and 8; check 7 is added when the PO names the command that reports CPS.

## 10. Amendment 2026-09-27 -- VSX: every Virtual System is its own failover unit (PO)
The PO: "Biz VSLS kullanmıyoruz. VSX'ler kendileri failover oluyor, şasi bağımsız. ... önce vsenv ile içine
giriyorum, her bir kontrolü spesifik olarak VSX için yapıyorum ve clusterXL_admin down ile failover yapıyorum."
This replaces every "VSX at chassis level" statement above:
- **Unit.** A failover unit is either a physical ClusterXL cluster (non-VSX) or **one Virtual System** of a VSX
  cluster (cluster + VSID). The VSX chassis itself is not failed over by this feature.
- **Context.** For a VS unit every pre-check, the failover, the wait loop, every post-check and the return run inside
  `vsenv <VSID>` on each member (the existing VSX-wrapped command form, `bash -lc 'vsenv <VSID> && <command>'`), one
  session per member, one command at a time.
- **Commands.** Write: `clusterXL_admin down` / `clusterXL_admin up` in the VS context on the member where that VS
  is Active (resp. the former active); new wrapped gate rows for both. Reads: the §2 checks in the VS context.
- **Approval, runs, screen.** Approval windows, runs and the stepper are per unit: a window names clusters and/or
  specific VSs; the Failover tab on a VSX cluster lists its VSs, each with its own window state, button, stepper
  and check table, shown under the chassis. One active run per unit. Different VSs of the same chassis may be failed
  over, each in its own run, whenever each is covered by an approval (PO: "aynı şasideki iki VS tabii ki failover
  edebilirsin, kapsamda varsa").
- **Refusals.** Also refused: the VS is not exactly one Active + one Standby across the two members in its own
  `cphaprob stat` (VS context).

## 11. Amendment 2026-09-27 -- checks are exactly the PO's list
Check 4 ("critical devices") is removed: it was not in the PO's list and needs `cphaprob -l list`, which is not
approved. Check 2 is the cluster IP table comparison. See `PO_DECISION_RECORD_2026_09_27_CP_FAILOVER_EXECUTION.md`
for how this contract relates to `PRODUCT_DIRECTION_RECORD.md` items 37, 39 and 368.

## 12. Amendment 2026-09-27 -- state sync and policy parity (PO approved the two reads)
From the older OP.0a stop-conditions (state sync current; version/policy parity) the PO approved: "Bu komutları da
ekleyelim."
| # | Check | Command (Expert; VSX via `vsenv <VSID>`) | PASS when |
| --- | --- | --- | --- |
| 9 | State synchronization | `cphaprob syncstat` | sync status reported OK on both members and no lost / unsynchronized update counters (a non-zero lost count or a non-OK status -> FAIL; unrecognised output -> UNKNOWN) |
| 10 | Installed policy parity | `fw stat` | both members report the same installed policy name (install time recorded, not compared); a member with no policy -> FAIL |
Both are pre-checks and post-checks (post: 9 on the new active; 10 unchanged on both). New gate rows (plain and
`vsenv`-wrapped), read-only.

## 13. Amendment 2026-09-30 -- pre-checks on their own: on demand and every 4 hours (PO: "yes ilerleyelim")
The §2/§12 pre-check set (plain and per-VS in `vsenv`) also runs **without a failover and without an approval
window** -- it is read-only:
- **On demand:** a "Run pre-checks" action per unit (cluster or VS) in Operations › HA & readiness and on the
  Failover tab; allowed to the roles that may read failover state and run collections (operator, security_admin).
- **Every 4 hours** (the PO-approved cadence in this amendment): one SSH session per
  member, commands one at a time in order, a configurable pause between commands (default 2 s), units processed one
  after another (never in parallel against the same member).
- **Result:** stored like a run's pre-check phase (a readiness record per unit with its check rows); shown as
  "Ready" / "Not ready -- <failed check>" / "Unknown" with its age. A displayed result never replaces the fresh
  pre-check that every failover run performs at its start (§1 step 3).
- The Phase A `PreflightService` panel in Operations (inventory projection, no device reads) is removed from the
  screen; this readiness replaces it.

## 14. Amendment 2026-09-30 -- four more reads, and the real output shapes (PO: "son 4 komut da ok")
Added as pre-checks (plain and `vsenv`-wrapped): `cphaprob -ia list` (blocking: any pnote in problem state),
`cphaprob show_bond` (blocking: a bond not `UP` -- including `UP!` -- or link-up below required; "No bond interfaces
are configured." passes), `cphaprob show_failover` (information; warning if the last failover is within 6 h), and
`cpstat os -f routing` (post-check: the new active has a default route and the same route count as the pre-check
active; counts and booleans only). The PO measured every check command on a real VSX cluster and a plain HA cluster
the same day: VSX reports `Cluster Mode: Virtual System Load Sharing` on the chassis and in each VS context although
the estate does not run VSLS -- the parser accepts that label; `fw stat` may print a single-digit hour;
`cphaprob tablestat` has four columns (member, interface, IP, MAC).

## 15. Amendment 2026-09-30 -- ARP is information before the switch (PO)
PO: "Aktif olan cihazla pasifin ARP'ı eşleşse ne olur, eşleşmese ne olur." A standby member carries no traffic, so its
ARP table is naturally smaller; comparing it with the active member's is meaningless. Check 5 is therefore
**informational in the pre-check** (both counts recorded, never FAIL/blocking). In the post-check the useful comparison
stays: the new active's ARP count against the pre-failover active's count (§3) -- also informational unless the PO
promotes it.

## 16. Amendment 2026-10-01 -- readiness rule tuning (Product Owner lane brief)
- Check 10 supersedes §12's time rule: equal policy names and installation timestamps no more than 600 seconds
  apart pass, including exactly 10 minutes. Different names, a missing policy, or greater skew fail; invalid or
  unavailable times remain UNKNOWN. Compare parsed local calendar times (same member clock basis; no timezone
  conversion inferred). Store both installation times, name-equality boolean, skew seconds and a reason enum;
  do not persist policy names. Post-check policy-name continuity remains required.
- Check 6 pre-check: when the active count is below 10,000, PASS if the absolute count difference is strictly
  less than 2,000 OR standby/active is at least 50%. At 10,000 and above, retain standby/active >= 80%.
  Zero active count passes for nonnegative standby counts; the ratio is undefined and omitted. Missing counts
  or unverified active role remain UNKNOWN. Store member count/peak, active and compared counts, difference,
  ratio when defined, and the applied rule. Post-check remains new-active/pre-switch-active >= 80% (§3).
- Check 2 compares per-member address sets, including explicit interface names when supplied. Numeric interface
  indices are observer-local and never comparison keys (PO measured correction, 2026-10-03). Ordering, `(Local)`
  markers and MAC differences do not affect equality. Keep member-specific addresses under their own opaque member;
  do not compare one member's address set with the other's. Equal sets PASS. A member address absent on either
  observer FAILS, including VS contexts; absent/unrecognised tables or equal tables without two-member evidence remain UNKNOWN.
  The supplied four-row shape (two addresses per member, interface 6 on one observer and 3 on the other) must PASS.
  Persist missing-observer enums and comparison-local numbered address aliases only; no raw address, numeric index
  or interface name is retained in derived data. Aliases preserve equality only within the comparison.
- All changes reuse existing reads, sessions and command gates. No new command or gate row.
