# PO decision record -- 2026-11-17: failover "Traffic and ARP (information)" reads approved

**Status: RATIFIED -- PO APPROVED (chat, 2026-11-17).** Implementation authority for signing off the six read rows
inserted DRAFTED by `V248__failover_traffic_arp_reads.sql`, and for using the already approved FortiGate
`show system interface` read at a readiness run to list the VDOMs. It approves no state-changing command and no switch.

## Decision (PO's words quoted)

Asked for the exact literals below, the PO answered "Evet, onaylıyorum":

| Gate | Vendor | Literal |
|---|---|---|
| `asa_failover_show_traffic` | Cisco ASA | `show traffic` |
| `asa_failover_show_arp_statistics` | Cisco ASA | `show arp statistics` |
| `fgt_failover_performance_status` | FortiGate | `get system performance status` |
| `fgt_failover_arp` | FortiGate | `get system arp` (inside each VDOM on a multi-VDOM unit) |
| `pan_failover_traffic_counters` | Palo Alto | `<show><counter><interface>all</interface></counter></show>` (two samples, at least 5 s apart) |
| `pan_failover_arp_table` | Palo Alto | `<show><arp><entry name = 'all'/></arp></show>` |

and the reuse of `fgt_show_system_interface` (`show system interface`, global context, SIGNED_OFF since V80) at every
FortiGate readiness run on a multi-VDOM unit, to list the VDOMs.

## Use

- Only when a readiness run runs (on demand or the scheduled readiness pass), on the active member (ASA, Palo Alto) or
  the enrolled primary (FortiGate). Read-only, 30 s timeout, no retry.
- Output is reduced in memory to a traffic rate and ARP entry counts (FortiGate: one count per VDOM by ordinal, no VDOM
  names); nothing raw is stored or written to the transcript.
- The check is information only: it never blocks a readiness outcome. Against an earlier run it reports an ARP drop.

## What this record changes

- `V249__failover_traffic_arp_signoff.sql` flips the six rows from DRAFTED to SIGNED_OFF (only from DRAFTED; literals,
  keys, timeouts unchanged), restates the FortiGate ARP row's per-VDOM session rule, and widens the stated frequency of
  `fgt_show_system_interface` to the readiness use. The gate fixture mirrors it.
- The ASA executor also logs the masked shape of `show failover state` when its configuration-state section is empty or
  unrecognised (evidence for a pair whose configuration sync check is UNKNOWN), not only when the section is missing.

## What this record does not change

- No switch or execution path; no approval, schedule or resume behaviour.
- The FortiGate session read (`diagnose sys session stat`) stays unapproved; check 6 stays "not evaluated".
