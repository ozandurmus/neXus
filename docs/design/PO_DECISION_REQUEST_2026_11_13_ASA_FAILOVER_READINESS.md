# PO decision request -- 2026-11-13: Cisco ASA failover readiness (Phase 1) reads and checks

**Status: DRAFT -- awaiting the Product Owner's exact-literal approval.** Built switched off: the four permanent gate rows
are inserted `DRAFTED` (a `DRAFTED` row never resolves, so no command is sent), and the readiness switch
`UI2_ASA_FAILOVER_READINESS` is off by default on both the service and the worker. Nothing here sends a state-changing
command; the ASA switch (`no failover active`) is not built and waits for the decision council.

Inputs: `FAILOVER_FORTIGATE_ASA_DESIGN_DRAFT.md` §4.1 and §10, `ASA_FAILOVER_MEASUREMENT_2026_11_13.md` (one pair,
2026-11-13), `PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md` (pair serial identity, PO Q4/Q5).

## 1. What the PO is asked to approve (exact literals, read-only)

| Gate id | Literal | Use | Per run, per member | Risk |
| --- | --- | --- | --- | --- |
| `asa_failover_show_failover` | `show failover` | checks 1-4, 6, 8 | **twice**, at least 5 s apart (the second sample is for the counter comparison of check 6) | none; output names interfaces, addresses and serials, reduced in memory to derived values |
| `asa_failover_show_failover_state` | `show failover state` | check 5; last-failure fields shown as history | once | none |
| `asa_failover_show_failover_history` | `show failover history` | check 9 (newest entry, read from the end) | once | none; long answer (about 70 lines measured) |
| `asa_failover_show_conn_count` | `show conn count` | check 7 | once | none |

Session: one SSH session per member, the ASA inventory session's own preamble (`terminal pager 0`, `show curpriv`
must report privilege 15, `show mode` must report single context -- all three already approved), commands one at a
time with a configurable pause (default 2 s). When: on demand ("Run pre-checks") and in the existing 4-hourly
readiness pass, once the PO approves and the switch is turned on. The one-time measurement rows of V210
(`asa_measure_*`) stay as they are and are not used by readiness.

## 2. Checks (both members; result READY / NOT_READY / UNKNOWN)

Unrecognised or missing output is UNKNOWN and blocks (fail-closed). Only derived values are stored: states, booleans,
counts, MATCH/MISMATCH; no serial, address, interface name, host name or version value.

| # | Check | PASS when | FAIL when | Otherwise |
| --- | --- | --- | --- | --- |
| 1 | Failover state and roles | both observers report `Failover On`, no failover group lines; across the pair exactly one `Active` and one `Standby Ready`; each observer's `This host` (unit label and state) equals the other observer's `Other host` | the roles are not one Active + one Standby Ready, or the two views disagree | UNKNOWN (incl. failover groups / Active-Active and multiple context: reason `UNSUPPORTED_MODE`) |
| 2 | Pair identity | the two observers' `Serial Number: Ours, Mate` mirror each other and the pair's serial set equals the recorded `ha_pair_identity` set (exact equality) | a reported serial is not one of the recorded two | UNKNOWN when no identity is recorded or a serial is missing |
| 3 | Failover and state links | `Failover LAN Interface: ... (up)` and the stateful `Link : ... (up)` on both observers | either link not `(up)` | UNKNOWN when a line is missing |
| 4 | Monitored interfaces | every `Interface ... : <status>` line under both hosts on both observers is `Normal (Monitored)` (`Normal (Not-Monitored)` is shown, not judged) | any `Failed`, `No Link`, `Link Down`, `Testing`, `Unknown` or `Waiting` | UNKNOWN when no interface line is present |
| 5 | Configuration sync | `====Configuration State===` holds `Sync Done` on both observers (`Sync Done - STANDBY` on the standby) | a host state is `Cold Standby`, `Sync Config`, `Sync File System` or `Bulk Sync` | UNKNOWN when the section is missing. The `Last Failure Reason` columns and their date are history: shown, never judged |
| 6 | Stateful replication | between the two `show failover` samples no `xerr` or `rerr` counter increased; when connections are in use, the active's cumulative `xmit` and the standby's cumulative `rcv` are positive | an error counter increased | UNKNOWN when a table or row is unparsable. Never an absolute-zero rule (measured: `rerr` is large and cumulative on a healthy pair) |
| 7 | Sessions carried | the standby's `in use` against the active's: below 10,000 active, absolute difference under 2,000 or ratio at least 50 %; at 10,000 or more, ratio at least 80 %; zero active passes (the Check Point check-6 rule) | outside that rule | UNKNOWN when either count does not parse (`N in use, M most used`) |
| 8 | Version parity | `Version: Ours, Mate` equal on each observer and mirrored across the two | different | UNKNOWN when missing |
| 9 | Last failover (information) | newest history entry (the **last** one; the list is oldest first) older than 6 h | -- | WARN when under 6 h; UNKNOWN (information only) when the time zone of the entry cannot be resolved |

Outcome: READY when checks 1-8 PASS; NOT_READY when any of them FAILs; UNKNOWN otherwise. Check 9 never blocks.

## 3. Logging and evidence
Logs carry the outcome, the check numbers with PASS/FAIL/UNKNOWN and reason codes; when a read does not parse, its
masked shape (vendor words kept, other words `a`, digits `9`, addresses as placeholders) -- never a value. The answers
are not recorded in the job transcript.

## 4. Questions for the PO
- **Q1.** Approve the four literals in §1 exactly as written, for readiness on demand and every 4 hours, on Cisco ASA
  failover pairs in single context mode?
- **Q2.** Approve `show failover` twice per run (counter comparison) -- or once, with check 6 reduced to "counters
  present" (weaker)?
- **Q3.** After approval: turn `UI2_ASA_FAILOVER_READINESS` on for all five pairs at once, or first for one pair?
