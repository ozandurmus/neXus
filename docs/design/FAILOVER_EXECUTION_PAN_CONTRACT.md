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
| 5 | Session synchronization (HA2) | `<show><high-availability><state-synchronization/></high-availability></show>` (new) | on the active peer `enabled=yes`, session setup/update entries `enable=yes` and `sent > 0`; on the passive peer the same entries have `recv > 0` (PO measured correction, 2026-10-03) |
| 6 | Sessions carried | `<show><session><info/></session></show>` (new) | pre: recorded on both; post: the new active's active-session count at least 80 % of the pre-check active's |
| 7 | Version parity | `<show><system><info/></system></show>` (existing gate) | PAN-OS version and app / threat content versions equal on both peers |
All three run as pre- and post-checks. New read gate rows for 5 and 6.

## 7. Amendment 2026-09-30 -- pre-checks on their own: on demand and every 4 hours (PO: "yes ilerleyelim")
Same rule as `FAILOVER_EXECUTION_CP_CONTRACT.md` §13 for the §3/§6 Palo Alto checks: a read-only "Run pre-checks"
per HA pair, and a 4-hourly pass (one session per peer, commands in order, configurable pause); the result is shown
with its age in Operations › HA & readiness and never replaces the fresh pre-check at the start of a run.

## 8. Measured parser correction 2026-10-03 (Product Owner brief)

Check 5 reads `response/result/{dp, enabled, aa_enabled, sync_conf,
messages/entry{enable, version, sent, recv, desc}}`, not a `Complete` or `session-sync` field.
Select the active observer from the existing identity-verified HA state; match `session setup` and `session update`
by `desc`, independently of entry ordering. Require active `enabled=yes`, both active message entries `enable=yes`
and positive `sent`, and positive passive `recv` for both entries. Passive `enabled=no (device not in active state)`
is normal and does not fail this check. Disabled active sync or disabled active message entries FAIL; zero active
sent counters while sessions exist, or zero passive receive counters for active traffic, FAIL. Missing required
fields/entries remain UNKNOWN; present invalid counters fail closed. The existing session-info count read is unchanged.
The positive-counter PASS requirement is applied conservatively even to an idle pair; zero counters do not prove sync.
No new command, session, gate row, retry or collection cadence is introduced; raw XML is not persisted.

## 9. Amendment 2026-10-21 -- check 7 (version parity) reads the HA state (PO: "Evet, HA durumundan al")

Decision record: `PO_DECISION_RECORD_2026_10_21_PAN_TYPE_VERSION_AND_CHECK7.md` (RATIFIED). This amends the check 7 row of
§6 only; every other check, the flow and the approvals are unchanged.

| # | Check | Read (direct firewall XML API) | PASS when |
| --- | --- | --- | --- |
| 7 | Version parity | `<show><high-availability><state/></high-availability></show>` (the existing read, issued to each member in every phase; no new command) | each member's own `local-info` `build-rel` (PAN-OS build), `app-version` and `threat-version` are present on both members and equal |

- **Deviation from §6.** §6 read `<show><system><info/></system></show>` (`sw-version`, `app-version`, `threat-version`).
  The PO retired that read for Palo Alto firewalls on 2026-10-21 (`PO_DECISION_RECORD_2026_10_21_PAN_SHOW_SYSTEM_INFO_RETIRED.md`),
  and approved the HA state as the source for this check. The three compared values keep their meaning; only their source
  changes. `local-info/version` is an HA-protocol counter, not the software version, and is not used.
- **Whose facts.** Each member's own `local-info`, read from that member (the HA-state read is already identity-verified
  against the recorded serial). `peer-info` is never read for this check: a member's report about its peer is one-sided.
- **Results.** PASS: all three fields present on both members and equal. FAIL: any present pair differs. UNKNOWN (blocks,
  as every UNKNOWN): any field missing, repeated or unusable on either member, including beside a mismatch; HA disabled
  or an unusable response carries no `local-info`, so the check is UNKNOWN. Never a guessed or defaulted value.
- **Evidence.** Derived values only: `{"source":"ha_state_local_info","software":…,"app":…,"threat":…}` with each field
  `MATCH`, `MISMATCH` or `MISSING`; no version value is recorded or shown.
- **Unchanged.** The check still runs as a pre-check, post-check and post-return check, in readiness runs and in
  executions; a non-PASS result stops an execution before any write. Check 6 (§6) is unchanged by this amendment: while the
  deployment retires `show session info` (`NEXUS_PAN_FW_SHOW_SESSION_INFO=retired`), check 6 is UNKNOWN with the reason
  `not_collected` and readiness ends UNKNOWN there.
- **Evidence limit.** That a given PAN-OS release returns these three `local-info` fields is from the vendor's HA-state
  response as captured earlier, not yet measured on the estate's PAN-OS 12 members: until measured, a member that omits
  one reads UNKNOWN, which is the fail-closed outcome.
