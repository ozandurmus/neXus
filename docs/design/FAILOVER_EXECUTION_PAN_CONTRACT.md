# Palo Alto HA failover: same approval / run / screen skeleton as Check Point

**Status:** FROZEN -- Product Owner decisions 2026-09-27 (§5). Reuses everything in
`FAILOVER_EXECUTION_CP_CONTRACT.md` (approval windows, roles, runs, stepper, refusals, audit) except the unit, the
checks and the commands below.

## 1. Unit
A Palo Alto HA pair in **active-passive** mode (two direct firewalls, reciprocally paired). Active-active pairs are
refused ("unsupported HA mode"). Virtual systems are never units: they are shown under their device (legacy view).

## 2. Flow (per the CP contract §1, with these steps)
1. Approval window and start: as CP.
2. **Pre-checks** on both peers (direct firewall XML API, the approved `<show><high-availability><state/>` read).
3. **Suspend** the active peer: `<request><high-availability><state><suspend/></state></high-availability></request>`.
4. **Confirm the switch** (PO: "suspend ettiğinde diğerinin aktif olduğuna bakılması gerekiyor"): poll the HA state on
   both peers every 3 s, up to 60 s, until the former passive reports **active** and the former active reports
   **suspended**. Not reached -> stop and warn (no further command).
5. **Post-checks** (same checks) on both peers.
6. **Return**: on the former active `<request><high-availability><state><functional/></state></high-availability></request>`;
   poll until it reports **passive** while the new active stays **active**.
7. Any problem after step 3: stop, no automatic fail-back, notify.

## 3. Checks (from `show high-availability state`, both peers)
| # | Check | PASS when |
| --- | --- | --- |
| 1 | Mode and roles | both report active-passive; exactly one active and one passive (pre); after the switch the expected roles (post) |
| 2 | Peer relationship | each peer's view of the other is the other device (reciprocal), peer connection status up |
| 3 | HA links | HA1 (and HA1 backup if configured) and HA2 links up on both |
| 4 | Configuration sync | running configuration synchronized on both |
Unrecognised output -> UNKNOWN (blocks). Derived values only.

## 4. Commands and gates
Read: existing HA-state XML gate. Write (new, CLASS operational state change, exact-key exception like CP):
the two `request high-availability state` op commands above, direct firewall only, never console-submittable.

## 5. Product Owner decisions (2026-09-27)
- Method: suspend, confirm the other peer became active, then functional on the former active.
- Checks: HA state and connectivity only (option "HA durumu + bağlantı").
- Same approval windows, roles and screen as Check Point; vsys under their device.

## 6. Amendment 2026-09-27 -- session sync, session takeover, version parity (PO: "Ekleyelim")
| # | Check | Read (direct firewall XML API) | PASS when |
| --- | --- | --- | --- |
| 5 | Session synchronization (HA2) | `<show><high-availability><state-synchronization/></high-availability></show>` (new) | session sync reported current / in sync on both peers; a failed or disabled state -> FAIL; unrecognised -> UNKNOWN |
| 6 | Sessions carried | `<show><session><info/></session></show>` (new) | pre: recorded on both; post: the new active's active-session count at least 80 % of the pre-check active's |
| 7 | Version parity | `<show><system><info/></system></show>` (existing gate) | PAN-OS version and app / threat content versions equal on both peers |
All three run as pre- and post-checks. New read gate rows for 5 and 6.

## 7. Amendment 2026-09-30 -- pre-checks on their own: on demand and every 4 hours (PO: "yes ilerleyelim")
Same rule as `FAILOVER_EXECUTION_CP_CONTRACT.md` §13 for the §3/§6 Palo Alto checks: a read-only "Run pre-checks"
per HA pair, and a 4-hourly pass (one session per peer, commands in order, configurable pause); the result is shown
with its age in Operations › HA & readiness and never replaces the fresh pre-check at the start of a run.
