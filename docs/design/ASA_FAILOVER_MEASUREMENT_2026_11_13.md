# Cisco ASA failover: one-time read-only measurement, 2026-11-13

**Status: MEASUREMENT RECORD (evidence, not a contract).** One PO-attended run of the four approved literals on one
Cisco ASA failover pair, made to learn the real output shapes before the Phase 1 readiness parser and contract.
Authority: `PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md` item 4 ("Onaylıyorum (tek çift, tek sefer)")
and the PO's go on 2026-11-13 ("Şimdi yapalım", PO present). Design: `FAILOVER_FORTIGATE_ASA_DESIGN_DRAFT.md`.

## 1. What ran
- **Target:** FW-FOXTROT-07 (masked), both members (M1, M2). Picked as the quietest of the five pairs by stored
  evidence: 0 configuration changes and 0 non-completed jobs in 14 days, 0 crash alerts, roles reciprocal,
  same software on both, both last inventories successful. Connection counts and last failover were not stored
  before this run.
- **Commands (exactly these, nothing else):** `show failover`, `show failover state`, `show failover history`,
  `show conn count` (gates `asa_measure_*`, V210), once per member, in each member's normal ASA inventory session
  (pager off, privilege 15), after the inventory's own reads. No retry, no configuration or state change.
- **How:** `UI2_ASA_FAILOVER_MEASURE` set on `ui2-worker` with the two device ids and a 45-minute window at
  09:06 local, one aiview inventory request per member (both COMPLETED), the setting removed at 09:07 (worker
  rolled out twice; no job was in flight). No deploy or e2e ran at the time.
- **Evidence kept:** only the worker's shape lines below. Raw answers were reduced in memory and discarded; the job
  transcript did not record them. In a shape every vendor word is kept, every other word becomes `a`, every digit
  becomes `9`, IPv4/IPv6/MAC values become `<ip>`/`<ip6>`/`<mac>`. Separators `, ` in a line are shown as new lines.

## 2. Findings
1. **Roles and reciprocity:** one member reports `This host: Primary - Active` / `Other host: Secondary - Standby
   Ready`, the other the mirror image. `Failover On`; `Failover unit Primary|Secondary` present. Single context
   on both (`multiple_context=false`).
2. **Links:** `Failover LAN Interface: ... (up)` and the stateful `Link : ... (up)` on both.
3. **Monitored interfaces:** 5 per host, every one `Normal (Monitored)` on both observers.
4. **Version line:** `Version: Ours <v>, Mate <v>` on one line; same shape on both (equality not checked here:
   values are not kept). A hardware/slot line `... status (Up Sys)` follows each host's state line.
5. **Stateful counters:** the table has 28 rows (`General`, `sys cmd`, `TCP conn`, `UDP conn`, `ARP tbl`, VPN,
   SIP, `User-Identity`, CTS, routing ...). `xerr` is a single digit on every row (likely 0). **`rerr` is large and
   cumulative on several rows** (up to 4 digits on one member, up to 9 digits on the other for `General` and
   `TCP conn`). A rule "rerr = 0" would fail this healthy pair: check 5 must compare two samples (no increase),
   as the design's §10 item 5 already says.
6. **Configuration sync (`show failover state`):** `====Configuration State===` with `Sync Done` (and
   `Sync Done - STANDBY` on the standby); `====Communication State===` `Mac set`. The last failure reason is
   `Ifc Failure` for both hosts with dates in September/October, plus a line `<name>: No Link` naming the interface
   of that past failure. The parser must treat the last-failure columns as history, not as the current state.
7. **History (`show failover history`):** about 20 entries per member (66 and 70 lines), **oldest first**. State
   words seen: `Active`, `Standby Ready`, `Just Active`, `Failed`, `Bulk Sync`, plus three `Active <word>` forms the
   shape masked (`Active Drain`, `Active Applying Config`, `Active Config Applied` are the documented ASA states;
   to confirm in Phase 1). Reasons seen: `Set by the config command (failover active)` (masked as
   `Set a the config a`), `Other unit wants me Standby`, `Interface check` (with per-host counts), and
   `Stateful Failover Link ...` (Bulk Sync transitions in early September on one member).
8. **Recent role changes (unexpected for a "quiet" pair):** both histories show operator-initiated role moves by
   the config command `failover active` in August, September and **November 2026** (a move to the secondary and
   back). The estate's own practice is `failover active` on the unit that should become active; the design's
   approved method is `no failover active` on the active unit (PO Q4). Both are documented by Cisco; the PO may want
   to know the team's habit differs.
9. **Connections (`show conn count`):** parses as `N in use, M most used`. One member 4,778 in use (1,085,767 most
   used), the other 3,801 in use (573,185 most used): the standby holds replicated connections, about 80 % of the
   active's count, which supports check 6's rule.
10. **Timestamps:** `HH:MM:SS <+zz> Mon DD YYYY` (a numeric zone offset, not `UTC`). The shape masked the clock as
    `<ip9>` (the IPv6 placeholder matched `HH:MM:SS`): harmless here, fixed in the Phase 1 projector.

## 3. Consequences for Phase 1 (to carry into the contract)
- Check 5 (stateful replication): two samples, `xerr`/`rerr` must not increase; never an absolute zero rule.
- Check 4: read `Configuration State` (`Sync Done`) and `Communication State` (`Mac set`); ignore last-failure
  history columns for PASS/FAIL, show them as information.
- Check 8 (last failover): `show failover history` is oldest first; read the **last** entry. Answers can exceed the
  80-line measurement cap; the readiness read must keep the tail, not the head.
- Check 1: parse `This host` / `Other host` with unit label and state; the hardware line after it is not a state.
- Vocabulary for the parser: `Drain`, `Applying`, `Applied`, `wants`, `me`, `by`, `command` are expected words.

## 4. Shapes as logged (both members)

### `asa_measure_show_failover: answered lines=63`

```
Failover On
Failover unit Primary
Failover LAN Interface: a a9/9 (up)
a a <ip9>
Unit Poll frequency 9 seconds
holdtime 99 seconds
Interface Poll frequency 9 seconds
holdtime 99 seconds
Interface Policy 9
Monitored Interfaces 9 of 9999 maximum
MAC Address Move Notification a not set
Version: Ours 9.99(9)99
Mate 9.99(9)99
Serial Number: Ours a9999a
Mate a9999a
Last Failover at: <ip9> +99 Nov 99 9999
This host: Primary - Active
Active time: 99999 (a)
a 9: a-a999-a9-a a/a a (9.9/9.99(9)99) status (Up Sys)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Other host: Secondary - Standby Ready
Active time: 9999 (a)
a 9: a-a999-a9-a a/a a (9.9/9.99(9)99) status (Up Sys)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Stateful Failover Logical Update Statistics
Link : a a9/9 (up)
Stateful a xmit xerr rcv rerr
General 99999999999 9 999999999 9999
sys cmd 9999999 9 9999999 9
up time 9 9 9 9
RPC services 9 9 9 9
TCP conn 9999999999 9 999999999 9999
UDP conn 999999999 9 9999999 99
ARP tbl 999999999 9 99999999 9
Xlate_Timeout 9 9 9 9
IPv9 ND tbl 9 9 9 9
VPN IKEv9 SA 9 9 9 9
VPN IKEv9 P9 9 9 9 9
VPN IKEv9 SA 999999999 9 99999999 9
VPN IKEv9 P9 99999999 9 999999 9
VPN CTCP a 9 9 9 9
VPN a a 9 9 9 9
VPN a a 9 9 9 9
SIP Session 9 9 9 9
SIP a 9 9 9 9
SIP a 9 9 9 9
Route Session 9 9 9 9
Router ID 9 9 9 9
User-Identity 99 9 9 9
CTS SGTNAME 9 9 9 9
CTS PAC 9 9 9 9
a-a 9 9 9 9
IPv9 Route 9 9 9 9
a a 9 9 9 9
a a-ID 9 9 9 9
Logical Update Queue Information
Cur Max Total
Recv Q: 9 99 999999999
Xmit Q: 9 9999 99999999999
```

### `asa_measure_show_failover: answered lines=63`

```
Failover On
Failover unit Secondary
Failover LAN Interface: a a9/9 (up)
a a <ip9>
Unit Poll frequency 9 seconds
holdtime 99 seconds
Interface Poll frequency 9 seconds
holdtime 99 seconds
Interface Policy 9
Monitored Interfaces 9 of 9999 maximum
MAC Address Move Notification a not set
Version: Ours 9.99(9)99
Mate 9.99(9)99
Serial Number: Ours a9999a
Mate a9999a
Last Failover at: <ip9> +99 Nov 99 9999
This host: Secondary - Standby Ready
Active time: 9999 (a)
a 9: a-a999-a9-a a/a a (9.9/9.99(9)99) status (Up Sys)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Other host: Primary - Active
Active time: 99999 (a)
a 9: a-a999-a9-a a/a a (9.9/9.99(9)99) status (Up Sys)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Interface a (<ip>): Normal (Monitored)
Stateful Failover Logical Update Statistics
Link : a a9/9 (up)
Stateful a xmit xerr rcv rerr
General 999999999 9 99999999999 999999999
sys cmd 9999999 9 9999999 9
up time 9 9 9 9
RPC services 9 9 9 9
TCP conn 999999999 9 9999999999 999999999
UDP conn 9999999 9 999999999 999
ARP tbl 99999999 9 999999999 9
Xlate_Timeout 9 9 9 9
IPv9 ND tbl 9 9 9 9
VPN IKEv9 SA 9 9 9 9
VPN IKEv9 P9 9 9 9 9
VPN IKEv9 SA 99999999 9 999999999 9
VPN IKEv9 P9 999999 9 99999999 9
VPN CTCP a 9 9 9 9
VPN a a 9 9 9 9
VPN a a 9 9 9 9
SIP Session 9 9 9 9
SIP a 9 9 9 9
SIP a 9 9 9 9
Route Session 9 9 9 9
Router ID 9 9 9 9
User-Identity 9 9 99 9
CTS SGTNAME 9 9 9 9
CTS PAC 9 9 9 9
a-a 9 9 9 9
IPv9 Route 9 9 9 9
a a 9 9 9 9
a a-ID 9 9 9 9
Logical Update Queue Information
Cur Max Total
Recv Q: 9 99 99999999999
Xmit Q: 9 9999 999999999
```

### `asa_measure_show_failover_state: answered lines=11`

```
State Last Failure Reason a/Time
This host - Primary
Active Ifc Failure <ip9> +99 Sep 99 9999
Other host - Secondary
Standby Ready Ifc Failure <ip9> +99 Oct 99 9999
a: No Link
====Configuration State===
Sync Done
Sync Done - STANDBY
====Communication State===
Mac set
```

### `asa_measure_show_failover_state: answered lines=10`

```
State Last Failure Reason a/Time
This host - Secondary
Standby Ready Ifc Failure <ip9> +99 Oct 99 9999
Other host - Primary
Active Ifc Failure <ip9> +99 Sep 99 9999
a: No Link
====Configuration State===
Sync Done - STANDBY
====Communication State===
Mac set
```

### `asa_measure_show_failover_history: answered lines=70`

```
==========================================================================
From State To State Reason
==========================================================================
<ip9> +99 Aug 99 9999
Just Active Active a Set a the config a
(failover active)
<ip9> +99 Aug 99 9999
Active a Active a Config Set a the config a
(failover active)
<ip9> +99 Aug 99 9999
Active a Config Active Config a Set a the config a
(failover active)
<ip9> +99 Aug 99 9999
Active Config a Active Set a the config a
(failover active)
<ip9> +99 Sep 99 9999
Active Standby Ready Other unit a a Standby
(Set a the config a)
<ip9> +99 Sep 99 9999
Standby Ready Failed Interface check
This host:9
a: a
Other host:9
<ip9> +99 Sep 99 9999
Failed Standby Ready Interface check
This host:9
Other host:9
<ip9> +99 Sep 99 9999
Standby Ready Failed Interface check
This host:9
a: a
Other host:9
<ip9> +99 Sep 99 9999
Failed Standby Ready Interface check
This host:9
Other host:9
<ip9> +99 Sep 99 9999
Standby Ready Just Active Set a the config a
(failover active)
<ip9> +99 Sep 99 9999
Just Active Active a Set a the config a
(failover active)
<ip9> +99 Sep 99 9999
Active a Active a Config Set a the config a
(failover active)
<ip9> +99 Sep 99 9999
Active a Config Active Config a Set a the config a
(failover active)
<ip9> +99 Sep 99 9999
Active Config a Active Set a the config a
(failover active)
<ip9> +99 Nov 99 9999
Active Standby Ready Other unit a a Standby
(Set a the config a)
<ip9> +99 Nov 99 9999
Standby Ready Just Active Set a the config a
(failover active)
<ip9> +99 Nov 99 9999
Just Active Active a Set a the config a
(failover active)
<ip9> +99 Nov 99 9999
Active a Active a Config Set a the config a
(failover active)
<ip9> +99 Nov 99 9999
Active a Config Active Config a Set a the config a
(failover active)
<ip9> +99 Nov 99 9999
Active Config a Active Set a the config a
(failover active)
==========================================================================
```

### `asa_measure_show_failover_history: answered lines=66`

```
==========================================================================
From State To State Reason
==========================================================================
<ip9> +99 Sep 9 9999
Standby Ready Bulk Sync Stateful Failover Link a
<ip9> +99 Sep 9 9999
Bulk Sync Standby Ready Stateful Failover Link a
<ip9> +99 Sep 9 9999
Standby Ready Bulk Sync Stateful Failover Link a
<ip9> +99 Sep 9 9999
Bulk Sync Standby Ready Stateful Failover Link a
<ip9> +99 Sep 99 9999
Standby Ready Failed Interface check
This host:9
a: a
Other host:9
<ip9> +99 Sep 99 9999
Failed Standby Ready Interface check
This host:9
Other host:9
<ip9> +99 Sep 99 9999
Standby Ready Just Active Set a the config a
(failover active)
<ip9> +99 Sep 99 9999
Just Active Active a Set a the config a
(failover active)
<ip9> +99 Sep 99 9999
Active a Active a Config Set a the config a
(failover active)
<ip9> +99 Sep 99 9999
Active a Config Active Config a Set a the config a
(failover active)
<ip9> +99 Sep 99 9999
Active Config a Active Set a the config a
(failover active)
<ip9> +99 Sep 99 9999
Active Standby Ready Other unit a a Standby
(Set a the config a)
<ip9> +99 Oct 99 9999
Standby Ready Failed Interface check
This host:9
a: a
Other host:9
<ip9> +99 Oct 99 9999
Failed Standby Ready Interface check
This host:9
Other host:9
<ip9> +99 Nov 99 9999
Standby Ready Just Active Set a the config a
(failover active)
<ip9> +99 Nov 99 9999
Just Active Active a Set a the config a
(failover active)
<ip9> +99 Nov 99 9999
Active a Active a Config Set a the config a
(failover active)
<ip9> +99 Nov 99 9999
Active a Config Active Config a Set a the config a
(failover active)
<ip9> +99 Nov 99 9999
Active Config a Active Set a the config a
(failover active)
<ip9> +99 Nov 99 9999
Active Standby Ready Other unit a a Standby
(Set a the config a)
==========================================================================
```

### `asa_measure_show_conn_count: answered lines=1 in_use=4778 most_used=1085767`

```
9999 in use
9999999 most used
```

### `asa_measure_show_conn_count: answered lines=1 in_use=3801 most_used=573185`

```
9999 in use
999999 most used
```
