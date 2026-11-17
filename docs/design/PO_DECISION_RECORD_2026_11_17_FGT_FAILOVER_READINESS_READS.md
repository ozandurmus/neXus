# PO decision record -- 2026-11-17: FortiGate failover readiness reads approved

**Status: RATIFIED -- PO APPROVED (chat, 2026-11-17, relayed by the PO session).** Implementation authority for signing
off the three new FortiGate readiness read rows only. It approves no state-changing command and no switch.
Context: `FAILOVER_FORTIGATE_ASA_DESIGN_DRAFT.md` section 4.2 and 8.1, `PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md`.

## Decision (PO's words quoted)

"Evet, onaylıyorum" to the exact literals, for permanent readiness use:

1. `diagnose sys ha checksum cluster` (check 4, configuration checksums equal)
2. `diagnose sys ha dump-by group` (check 8, per-member HA uptime)
3. `execute ha failover status` (check 8, no failover flag latched; status only, the verb sets nothing)

## Use

- Only when a readiness run runs (on demand, or the scheduled readiness pass).
- On the enrolled primary address only: the cluster has one enrolled address and the secondary has none of its own
  (design question Q2 stays open), so the secondary is judged from what the primary reports (one-sided evidence, marked
  `PRIMARY_REPORT` in every result).
- Global context when VDOMs are on (the approved context moves `config global` / `end`); read-only; 30 s timeout; no
  retry; once per run; commands one at a time with a pause; output reduced in memory to derived values, never stored.

## What this record changes

- Migration `V247__fgt_failover_readiness.sql` inserts exactly the three gate rows `fgt_failover_checksum_cluster`,
  `fgt_failover_dump_by_group`, `fgt_failover_ha_failover_status` as `SIGNED_OFF`, vendor `fortinet`, action class `read`.
  The same migration widens `failover_run.vendor` to accept `fortinet` and maps `fgt_failover_readiness` to the failover module.
- The already-approved reads are reused unchanged: `get system status`, `get system ha status`,
  `show full-configuration system ha`, the context moves.

## What this record does not change

- **Not approved, not built:** `diagnose sys session stat` (check 6 stays UNKNOWN, "not evaluated"); any switch or
  execution path (`diagnose sys ha reset-uptime` stays design-only); approvals, schedules and resume for `fortinet`
  keep refusing with `FAILOVER_VENDOR_NOT_AVAILABLE`.
- **The readiness switch `UI2_FGT_FAILOVER_READINESS` is off by default** on the service and the worker and is not in any
  deploy manifest; the PO turns it on (value `on` on both).
- Output shapes of the three new reads are not measured yet; the parsers accept the documented forms only and anything
  else is UNKNOWN, logged as a masked shape. Check 7 (version parity) stays UNKNOWN: a primary reports no firmware
  build for its secondary (open item for the PO, see the engineering report).
