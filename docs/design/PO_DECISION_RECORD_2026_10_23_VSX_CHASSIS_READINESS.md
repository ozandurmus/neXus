# PO decision record — 2026-10-23: VSX chassis readiness reads in VS0; the chassis failover decision comes later

**Status: RATIFIED — PO DECISION (chat, 2026-10-23, relayed by the coordinating session).**
Recorded by the engineering session on `lane/vsx-chassis-unit`. It answers
[VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md](VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md) §5 items 1-3. It is backed by automated
validation only. Nothing here has been validated in the real environment yet.

## 1. Context

A Check Point VSX cluster had no chassis unit. The chassis step of the cluster (CP-01..CP-07 on the physical members, PO
2026-09-27 and 2026-10-23) needs its reads in context VS0. The repository's CF-2 rule never assumes a VSX host's landing
context. So each read is sent as `bash -lc 'vsenv 0 && <read>'`. Before this decision, only two such literals had gate
rows, and those rows were for the inventory purpose.

## 2. The PO's answers (verbatim)

1. VS0 chassis readiness reads: **"Onaylıyorum"** ("I approve").
2. Chassis failover execution: **"Önce readiness, sonra karar"** ("Readiness first, then the decision").

## 3. Decisions

| # | Decision | Status |
| --- | --- | --- |
| 1 | The VS0 literals in §4 are approved, exactly as written, for the **readiness** of the VSX chassis unit. | RATIFIED. V177 seeds eleven rows SIGNED_OFF and extends the purpose of the two existing VS0 inventory rows. |
| 2 | `bash -lc 'vsenv 0 && cpstat -f policy fw'` is approved as the execution-only read. It stays unused while the chassis failover is refused. | RATIFIED. Row seeded (`cp_policy_install_cpstat_vsenv0`); the worker issues it only in a FAILOVER run, and a chassis FAILOVER run is refused before contact. |
| 3 | The chassis failover stays refused (`VSX_CHASSIS_FAILOVER_NOT_APPROVED`). A later decision will need two things: the exact write literal, with its vendor semantics for the VSs on that member, and an amendment of FROZEN `FAILOVER_EXECUTION_CP_CONTRACT.md` §10. | RATIFIED as "not now". No write row exists. |

## 4. Approved literals (verbatim)

Every literal below is Expert, SSH_EXEC, `cp_gaia_gateway`, CLASS_0 read, 30 s, no retry. Each runs in the run's single
SSH session per member, one command at a time. The one-shell framing is not used for these literals, because its
`<READ>` definition does not name the `vsenv 0` form.

| Read | Literal | Gate row |
| --- | --- | --- |
| Cluster state | `bash -lc 'vsenv 0 && cphaprob stat'` | `cp_inventory_cphaprob_stat_vsenv0` (purpose extended) |
| Cluster interfaces | `bash -lc 'vsenv 0 && cphaprob -a if'` | `cp_inventory_cphaprob_a_if_vsenv0` (purpose extended) |
| Cluster IP table | `bash -lc 'vsenv 0 && cphaprob tablestat'` | `cp_failover_tablestat_vsenv0` |
| ARP | `bash -lc 'vsenv 0 && arp -an'` | `cp_failover_arp_vsenv0` |
| Traffic | `bash -lc 'vsenv 0 && cat /proc/net/dev'` | `cp_failover_traffic_vsenv0` |
| State sync | `bash -lc 'vsenv 0 && cphaprob syncstat'` | `cp_failover_syncstat_vsenv0` |
| Installed policy | `bash -lc 'vsenv 0 && fw stat'` | `cp_failover_fw_stat_vsenv0` |
| Critical devices | `bash -lc 'vsenv 0 && cphaprob -ia list'` | `cp_failover_pnotes_vsenv0` |
| Bonds | `bash -lc 'vsenv 0 && cphaprob show_bond'` | `cp_failover_bonds_vsenv0` |
| Bond members | `bash -lc 'vsenv 0 && cphaprob show_bond <BOND>'` | `cp_failover_bond_members_vsenv0` |
| Last failover | `bash -lc 'vsenv 0 && cphaprob show_failover'` | `cp_failover_last_event_vsenv0` |
| Routing | `bash -lc 'vsenv 0 && cpstat os -f routing'` | `cp_failover_routing_vsenv0` |
| Policy install (execution only, unused) | `bash -lc 'vsenv 0 && cpstat -f policy fw'` | `cp_policy_install_cpstat_vsenv0` |

`<BOND>` is substituted exactly as in the existing bond-member rows. Only a bond name that the same pass's `show_bond`
summary observed is accepted (`bond[0-9]+(\.[0-9]+)?`), and it is quoted.

## 5. What this changes

A chassis readiness run on a VSX cluster now reads both members in VS0 and stores the chassis result under the chassis
unit (`vs_id` null). Before this, it stopped before contact with `VSX_CHASSIS_COMMANDS_NOT_APPROVED`. That stop still
applies to any VS0 row that is absent or not signed off. Per-VS readiness and execution are unchanged.
