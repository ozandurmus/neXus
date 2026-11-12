# PO decision record -- 2026-11-13: FortiGate HA and Cisco ASA failover, scope and Phase 0

**Status: RATIFIED -- PO APPROVED (chat, 2026-11-13, relayed by the PO session).** Recorded by the engineering session
from the Product Owner's answers to the questions of `FAILOVER_FORTIGATE_ASA_DESIGN_DRAFT.md` §9. This record is the
implementation authority for Phase 0 only. The design draft stays DRAFT for everything this record does not decide.

## Decisions (PO's words quoted)

1. **Order and scope (Q1, Q6):** "Evet, bu sırayla". Phase 0 first, then Cisco ASA, FortiGate last. Cisco FTD
   (FMC / FDM / cdFMC) is out of scope.
2. **ASA switch method (Q4):** "Aktifte `no failover active`" -- on the verified active unit. Approved as the design
   method only; every execution needs its own PO go-ahead per run.
3. **FortiGate switch method (Q3):** "Evet, reset-uptime" -- `diagnose sys ha reset-uptime` on the primary, global
   context. Approved as the design method only; no execution approval.
4. **ASA reads (Q5):** "Onaylıyorum (tek çift, tek sefer)". The exact literals `show failover`,
   `show failover state`, `show failover history`, `show conn count` are approved for **one** read-only measurement on
   **one** ASA pair the PO picks, with the PO present, at a time the PO sets. Not run before the PO session confirms the
   pair and time. Regular use (readiness) needs a separate approval.
5. **FortiGate member addresses (Q2):** open (PO asking the network team).
6. **Phase 0 (PO session, 2026-11-13):** vendor abstraction of the live failover path, serial-based identity and the
   HA facts record for Cisco ASA and FortiGate. **No new device command.** The session-drop dispatch state needed by
   the ASA switch goes through the decision council and the code-review plugin before it is built (not Phase 0).

## Phase 0 semantics fixed by this record

- **Serial:** the unit's own serial from reads the inventory already makes (ASA `show version` "Serial Number:",
  FortiGate `get system status` "Serial-Number:"), stored as the device's platform fact (`device_platform_facts`,
  source `asa_show_version` / `fgt_get_system_status`). A parse-scope extension of an issued command, same
  command/session/timeout/frequency: no gate change. An absent serial never erases a recorded one.
- **Pair identity (`ha_pair_identity`, V209):** for a pair of exactly two enrolled members of one vendor, the two
  serials are recorded the first time both members report two different serials; afterwards each inventory only
  compares: MATCH, MISMATCH, MISSING, AMBIGUOUS. Never re-bound automatically. For Cisco ASA the two device rows are
  the pair's management address slots (both present the same SSH host key, measured 2026-11-12 on all five pairs);
  a unit is identified by its serial within the pair, never by its address. Serials are compared exactly.
- **HA facts:** `device_inventory_ha` rows of the physical context with sources `asa_show_failover` (role
  active/standby) and `fgt_get_system_ha_status` (role primary/secondary, mode a-p/a-a), only when the read reported
  a role.
- **Vendor descriptor:** Check Point and Palo Alto behaviour unchanged; Cisco ASA pairs (and FortiGate pairs once two
  members are enrolled) are listed with "readiness not available yet"; every write or job entry point refuses them
  with `FAILOVER_VENDOR_NOT_AVAILABLE` before any store call.

## Not authorized by this record

Any device command beyond item 4's one measurement; any readiness run, approval, run or switch for Cisco ASA or
FortiGate; the session-drop dispatch state; FTD.
