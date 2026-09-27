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
| 2 | Sync tables | `cphaprob tablestat` | synchronized tables present and consistent on both members |
| 3 | Cluster interfaces | `cphaprob -a if` | every required interface UP on both; CCP up |
| 4 | Critical devices | derived from 1 (`cphaprob stat` problem notification) | no problem notification on either member |
| 5 | ARP | `arp -an` (entry count only) | standby has a comparable ARP population (recorded for §3) |
| 6 | Connections | `fw tab -t connections -s` (#VALS, #PEAK) | recorded; standby within sync tolerance of active |
| 7 | New connections / s | two samples of 6, 5 s apart | recorded (baseline for §3) |
| 8 | Traffic rate | two samples of `cat /proc/net/dev`, 5 s apart (cluster interfaces only) | recorded (baseline for §3) |
Unrecognised output -> UNKNOWN (blocks). Values are stored as derived numbers and states only, never raw output.

## 3. Post-checks (same commands on both members)
- 1: the former standby is Active, the former active is Down (then Standby after step 6).
- 2, 3, 4: as in the pre-checks, on the new active.
- 5: new active ARP count at least 80 % of the pre-check active's.
- 6: new active connection count at least 80 % of the pre-check active's (sync carried them).
- 7, 8: new active carries traffic: rate at least 50 % of the pre-check active's (not zero).
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
