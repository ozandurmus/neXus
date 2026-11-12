# FortiGate HA and Cisco ASA failover: design and phased plan

**Status: DRAFT -- for Product Owner decision. Not a contract, not a command approval.** No device was contacted while
writing this; every literal in §8 is a proposal awaiting the PO's exact-literal approval. Written 2026-11-12 against
`origin/main` `62dd0b7c`. Vendor facts come from the research notes
`fortigate-ha-research-1112.md` and `cisco-asa-failover-research-1112.md` (side-session notes folder; official Cisco
and Fortinet sources cited there; points marked UNVERIFIED there stay unverified here and are measured in Phase 1).

It reuses `FAILOVER_EXECUTION_CP_CONTRACT.md` (approval windows, roles, runs, stepper, refusals, audit),
`PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md` (approval policy, operator flow, one fleet mutation at a
time, no automatic retry or fail-back) and the live CP/PAN pipeline. Only the unit, the checks, the commands and the
member-reach model differ.

## 1. What the estate holds (live DB, counts only, 2026-11-12)

| | Cisco ASA | Fortinet |
| --- | --- | --- |
| Enrolled devices | 12 gateways | 9 FortiGate gateways + 1 FortiManager |
| HA units known | **5 failover pairs** (10 members, both members enrolled, corroborated `asa-failover` claim); 2 standalone | **1 FGCP cluster** (only its primary/cluster address is enrolled); 8 standalone. FortiManager discovery agrees: 1 HA cluster with 2 members, 8 single FortiGates |
| Transport, credentials | SSH interactive shell, privilege 15, a credential on all 12 | SSH interactive shell (FortiGate), HTTPS JSON-RPC (FortiManager); a credential on all 10 |
| Software | 9.18 (8 members, two different builds), 9.22 (4) | FortiOS 7.4 (all 9) |
| Recorded identity (serial) | **none** (`recorded_identity_primary` and platform serial empty on all 12) | **none** on all 10 |
| HA facts as `device_inventory_ha` rows | none (role kept only in `devices.observed_ha_role`) | none |
| Approved gate rows today | 12 read + 2 backup archive writes; HA: `show failover \| include This host`, `show mode` | 25 read (FortiGate + FortiManager); HA: `get system ha status`, `show full-configuration system ha`, `execute ha manage ?`, `execute ha manage <index> <username>` (measurement only; code path off) |
| Failover support | none | none |

For comparison: Check Point 43 units (39 READY now), Palo Alto 17 pairs (12 READY now); one SUCCEEDED attended
failover per vendor (2026-10-21/22).

**Value note (devil's advocate).** ASA brings 5 pairs; FortiGate brings exactly one cluster. A full FortiGate switch
path (Phase 3) is the same engineering cost as ASA for one unit. Recommendation: ASA readiness + switch first;
FortiGate readiness (cheap, shares Phase 1) but its switch only if the PO wants it for that one cluster or more
FortiGate HA clusters are coming.

## 2. Failover gaps that already exist for Check Point and Palo Alto (one place)

1. **Palo Alto has no "resume return".** A stopped PAN run can leave the former active *suspended*; recovery is by hand
   on the firewall. CP has an approved resume (`failover_resume`, CP-only constraint, V170).
2. **Session continuity on Check Point is never evaluated** (check 6 always "not evaluated"; the PO rejected
   `fw tab -t connections` on 2026-10-06). CPS (check 7) is still deferred.
3. **Palo Alto check 6 depends on a deployment switch**: while `show session info` is retired, check 6 is UNKNOWN and
   readiness ends UNKNOWN (PAN-OS 12+ uses the session meter, whose shape is still unmeasured on the estate).
4. **Readiness right now**: CP 2 NOT_READY + 1 UNKNOWN of 43; PAN 2 NOT_READY + 3 UNKNOWN of 17, four of them on
   configuration/session sync. These are real cluster findings or parser gaps, not checked further here.
5. **Unsupported modes refused**: VSX chassis-level failover (per-VS only, by PO decision), VSLS without a VSID,
   PAN active-active.
6. **No scheduled or automatic failover.** The generic "Phase C" stack (schedules, signed grants, preflight
   registry) is fenced off and is dead code in production; only the manual live path runs.
7. **Evidence breadth**: one successful run per vendor, one CP run ended OUTCOME_UNKNOWN. SNMP / Splunk verification
   parked. The last-failover check assumes `Europe/Istanbul`.
8. **Code shape**: the live path is named `CpFailover*` and branches on a two-way "is it Palo Alto" boolean in about
   fifteen places (repository `admit`, DB CHECK constraints, lease list, worker routing, check titles, UI paths). A
   third and fourth vendor need a vendor descriptor instead of more booleans (Phase 0).

## 3. Vendor model and the failover unit

### 3.1 Cisco ASA
- **Unit (phase 1):** an Active/Standby failover pair in **single context mode**. Refused with a named reason:
  Active/Active (failover groups; needs multiple context mode), multiple context mode (neXus does not issue
  `changeto` anywhere today), failover off, a mate that is not *Standby Ready*.
- **No "down" state.** `no failover active` on the active unit makes it *Standby* directly; the mate becomes Active.
  There is no return step: the run ends with roles swapped, which is the same end state as a CP/PAN run.
- **Addresses follow the role.** The two enrolled endpoints are the management interface's active and standby
  addresses. After a switch, the "active" address answers from the other physical unit. Members are therefore
  identified by the failover unit label (`Primary`/`Secondary`) and the serial number, never by endpoint. The SSH
  session that sends the switch loses its address and drops; that is expected (§5).
- **No preempt in Active/Standby**: roles stay where the switch put them.

### 3.2 FortiGate
- **Unit (phase 1):** an FGCP **active-passive** cluster of two members, VDOMs allowed, virtual clustering off.
  Refused: FGCP active-active (phase 2 candidate; same primary move), virtual clustering (failover is per virtual
  cluster; a partial move leaves split primaries), FGSP (standalone peers; no primary to move, traffic moves by
  routing), more than two members.
- **One enrolled address.** The cluster is reached on one management address that always lands on the primary. The
  primary's `get system ha status` and `diagnose sys ha checksum cluster` report both members; a reciprocal read from
  the secondary needs either the approved `execute ha manage` hop (V95, code path currently off) or a per-member HA
  management address enrolled as its own device (PO question Q2).
- **Election** (override disabled, the default): more monitored interfaces up, then HA uptime (when the difference
  exceeds `ha-uptime-diff-margin`, default 300 s), then priority, then serial. With override enabled, priority comes
  before uptime and the preferred unit takes the role back after any event.

### 3.3 Cisco FTD (FMC / FDM / cdFMC)
**Out of scope for this plan.** The supported switch is FMC "Switch Active Peer" or the FMC REST call
`PUT .../devicehapairs/ftddevicehapairs/{id}` with `action: SWITCH`; that is a new HTTPS transport, FMC API
credentials and an FMC identity model. neXus holds no FTD device today. A later, separate design if FTD arrives.
Firepower 4100/9300 chassis running the **ASA** image are in scope: same ASA commands, nothing on FXOS.

## 4. Readiness checks (pre, post, and the 4-hourly readiness run)

Rules shared with CP/PAN: one SSH session per member, commands one at a time, pause between commands; unrecognised
output is UNKNOWN and blocks; derived values only (no raw output, no names, addresses or serials stored or shown);
readiness never sends a write.

### 4.1 ASA (both endpoints)
| # | Check | Read | PASS when |
| --- | --- | --- | --- |
| 1 | Failover state and roles | `show failover` | `Failover On`; single context; exactly one *Active* and one *Standby Ready* across both observers, each observer's "This host" mirrors the other's "Other host" (by unit label and serial); no failover group lines |
| 2 | Failover and state links | `show failover` | failover LAN interface up; stateful failover link configured and up |
| 3 | Monitored interfaces | `show failover` | every monitored interface `Normal` on both hosts (any Failed / No Link / Link Down / Testing / Unknown = FAIL) |
| 4 | Configuration sync | `show failover state` | standby `Configuration State: Sync Done`; no unit in Cold Standby / Sync Config / Bulk Sync; no recorded failure reason now |
| 5 | Stateful replication | `show failover`, two samples 5 s apart | `xerr`/`rerr` not increasing; active `xmit` increasing |
| 6 | Sessions carried | `show conn count` | pre: recorded on both; post: new active at least 80 % of the pre-check active |
| 7 | Version and hardware parity | `show failover` (`Version: Ours, Mate`), `show version` (approved) | same software and same hardware model on both |
| 8 | Last failover | `show failover history` | information; WARN when the last role change is under 6 h old |

### 4.2 FortiGate (from the primary; secondary per Q2)
| # | Check | Read (global context when VDOMs are on) | PASS when |
| --- | --- | --- | --- |
| 1 | HA mode and members | `get system ha status` (approved) | mode a-p; exactly one Primary and one Secondary listed; HA health OK; virtual clustering off |
| 2 | Heartbeat links | `get system ha status` | every HBDEV up for both members |
| 3 | Monitored interfaces | `get system ha status` | every MONDEV up for both members (the peer must not lose the election on interfaces) |
| 4 | Configuration sync | `get system ha status`, `diagnose sys ha checksum cluster` | both members `in-sync`; all checksums equal |
| 5 | Session pickup | `show full-configuration system ha` (approved) | `session-pickup enable`; connectionless / NAT pickup recorded as information |
| 6 | Sessions carried | `diagnose sys session stat` per VDOM (approved context moves) | pre recorded; post new primary at least 80 % of the pre-check primary |
| 7 | Version parity | `get system ha status` / `get system status` (approved) | same FortiOS build on both members (source decided by the Phase 1 measurement) |
| 8 | Switch preconditions | `show full-configuration system ha`, `diagnose sys ha dump-by group`, `execute ha failover status` | `override disable`; the secondary's HA uptime exceeds `ha-uptime-diff-margin`; no failover flag latched |
| 9 | Last failover | `get system ha status` ("primary selected using" history) | information; WARN under 6 h |

## 5. The switch, verification and recovery

### 5.1 ASA
1. Fresh pre-checks (all PASS).
2. On the endpoint whose `show failover` says *This host: ... - Active* (serial matches the pre-check active):
   `no failover active`. No confirmation prompt is documented (to be measured). The session may drop before the
   prompt returns. **Today's dispatch path cannot absorb that** (verified, `JooqCpFailoverRepository.dispatch`): a
   send without a reply sets `delivery='UNKNOWN'` and stops the run as OUTCOME_UNKNOWN with an incident before any
   poll, and `confirmDispatch` only confirms `REPLY_RECEIVED`. Required change, §10 item 1.
3. Poll both endpoints every 3 s, up to 120 s: reconnect, `show failover`; settled when the former standby's serial
   reports *Active* and the former active's serial reports *Standby Ready*, Sync Done.
4. Post-checks (§4.1) against the pre values. DONE. No return step.
5. **Problem after step 2:** stop, change nothing, incident (existing quarantine), notify. Recovery is a new approved
   run in the other direction (same switch command on the new active), never automatic.

### 5.2 FortiGate -- two methods; the PO picks one
- **F1 (recommended; Fortinet's production method): `diagnose sys ha reset-uptime`** on the primary, global context.
  The primary's HA uptime goes to zero, the secondary is now the older unit and wins. Persistent (no latch), no
  return step. Works only with override disabled and an uptime gap above the margin (check 8 enforces it). Side
  effect: the former primary stays secondary until the next election event; `reset_cnt` increments.
- **F2: `execute ha failover set 1`** answered `y` at `Do you want to continue? (y/n)`, later
  **`execute ha failover unset 1`** answered `y`. Fortinet labels it "for testing"; the flag is latched until unset
  or reboot, and the unset may move the primary back. Needs the return step, like CP/PAN.
Then: the management session drops (the cluster address moves); reconnect to the cluster address, require the
serial to be the former secondary's, `get system ha status` settled (two members, roles swapped, in-sync), poll 3 s up
to 120 s, post-checks, DONE.

### 5.3 What can go wrong (both)
Mate not ready (refused by check 1/4), stateful link down (sessions drop; check 2/5), split brain after heartbeat loss
(check 2; post read shows two primaries/actives -> stop, incident), out-of-sync configuration (check 4), FortiGate
override flip-back (check 8 refuses override), a latched FortiGate flag (F2 only; check 8), upstream ARP/MAC learning
(gratuitous ARP is the vendor's; post traffic/sessions check), session loss for unsynchronised traffic (UDP/ICMP on
FortiGate without connectionless pickup; HTTP on ASA without `failover replication http`; proxy inspection, SSL VPN,
multicast) -- shown to the operator before the start as information.

## 6. Fit with the existing machine and contracts

- **State machine unchanged**: PLANNED, PRECHECK, FAILING_OVER, SWITCHED, POSTCHECK, DONE / STOPPED. ASA and F1 skip
  RETURNING; F2 uses it. Dispatch intents, NOT_SENT, mutation switch, fleet fence, approval windows, four-eyes,
  incidents and reconciliation are reused as they are.
- **New per vendor**: capability ids `asa_cluster_failover`, `fgt_cluster_failover`; `failover_run.vendor` and
  `failover_approval.vendor` CHECK widened to `cisco_asa`, `fortinet`; gate rows (§8); one worker executor per vendor
  using the existing interactive SSH transport; HA facts persisted (`device_inventory_ha` sources `asa_show_failover`,
  `fgt_ha_status`).
- **Identity**: execution needs a recorded identity per member (fail-closed today, since none exists). Phase 0 records
  the serial from the already-approved `show version` / `get system status`.
- **Session drop on the switch**: NOT handled today (see §10 item 1); the reconnect also lands on a different physical
  unit, so every observation is matched by serial (§10 item 2).
- **UI**: the existing failover panel and HA & readiness list with vendor labels; the stepper for ASA/F1 is
  *Preparing -> Failing over -> Switched -> Checking -> No problems found*; the "Check Point and Palo Alto only" text
  goes away for supported units; refused units show their reason (multiple context, A/A, vcluster, FGSP).
- **Approval and roles**: identical to CP/PAN.

## 7. Phased plan

| Phase | Content | Device commands | Size / risk |
| --- | --- | --- | --- |
| 0 | Vendor descriptor refactor of the live path (no behaviour change for CP/PAN); serial identity and HA facts for ASA/FortiGate from approved reads; units visible with "readiness not available yet" | none new | M / low (CP/PAN regression tests and architecture tests guard it) |
| 1 | One attended measurement per vendor on one pair / the one cluster (sanitized fixtures), then read-only readiness on demand and every 4 h | §8.1 reads | M / low (read-only) |
| 2 | ASA switch, mutation switch off by default; one attended pilot on one pair the PO names | §8.2 A1 | M / high (traffic) |
| 3 | FortiGate switch (F1 or F2), attended pilot on the one cluster | §8.2 F1 or F2 | M / high; low value with one cluster |
| Later | ASA Active/Active and multiple context; FortiGate active-active and virtual clusters; FTD through FMC REST | separate approvals | -- |

Each phase ships through PRs with the code-review plugin; Phases 2-3 also go to the decision council before build.

## 8. Device command literals (PO exact-literal approval needed)

### 8.1 Read-only (new)
| ID | Vendor | Literal | Purpose | Risk |
| --- | --- | --- | --- | --- |
| R-A1 | ASA | `show failover` | roles, links, monitored interfaces, versions, stateful counters (checks 1-3, 5, 7) | none; output names interfaces and addresses (parsed to derived values only) |
| R-A2 | ASA | `show failover state` | configuration sync and failure reason (check 4) | none |
| R-A3 | ASA | `show failover history` | last role change (check 8) | none; long output |
| R-A4 | ASA | `show conn count` | sessions carried (check 6) | none |
| R-F1 | FortiGate | `diagnose sys ha checksum cluster` | configuration checksums of both members (check 4) | none (the `checksum recalculate` form is NOT requested) |
| R-F2 | FortiGate | `diagnose sys ha dump-by group` | per-member HA uptime, reset count, failover flag (check 8) | none |
| R-F3 | FortiGate | `execute ha failover status` | is a failover flag latched (check 8) | none; "execute" verb but status only |
| R-F4 | FortiGate | `diagnose sys session stat` (in each VDOM via the approved context moves) | sessions carried (check 6) | none; per VDOM |
Already approved and reused: `show version`, `show mode`, `terminal pager 0`, `show curpriv` (ASA); `get system ha
status`, `get system status`, `show full-configuration system ha`, the context moves, `execute ha manage ?`,
`execute ha manage <index> <username>` (FortiGate; the last two only if Q2 picks the hop).

### 8.2 State-changing (new; CLASS 2 operational state change; only through the failover flow, never console-submittable)
| ID | Vendor | Literal | Where | Purpose | Risk |
| --- | --- | --- | --- | --- | --- |
| A1 | ASA | `no failover active` | the verified Active unit | makes it Standby; the mate takes over | **traffic moves**; stateful sessions survive, unsynchronised ones drop; session to the device drops |
| F1 | FortiGate | `diagnose sys ha reset-uptime` | the verified primary, global context | the secondary wins the election (persistent) | **traffic moves**; no latch; the former primary stays secondary |
| F2a (withdrawn) | FortiGate | `execute ha failover set 1` then `y` | the verified primary, global context | forces the peer to primary | **traffic moves**; latched until unset or reboot; vendor says "testing" |
| F2b (withdrawn) | FortiGate | `execute ha failover unset 1` then `y` | the new primary | clears the latch | **may move traffic back** |
Not requested, and never sent: `failover active` (ASA alternative, on the standby), `failover reset`,
`failover reload-standby`, `failover exec`, `write standby`, `diagnose sys ha reset-uptime-primary-only`, any
`config system ha` change (priority, override), `diagnose sys ha checksum recalculate`.

## 9. Questions for the PO (after the review in §10)
- **Q1.** Order: Phase 0, then ASA readiness and switch (5 pairs); FortiGate only after each member's own HA management
  address is enrolled as a device. Agreed? (recommended)
- **Q2.** Can the network team provide each FortiGate member's own HA management address (ha-mgmt-interface)? If not,
  FortiGate failover is dropped for now.
- **Q3.** FortiGate switch method F1 `diagnose sys ha reset-uptime` (F2 withdrawn, §10 item 7). Agreed?
- **Q4.** ASA: send `no failover active` on the active unit (recommended; matches CP "down on the active") or
  `failover active` on the standby?
- **Q5.** Approve the §8.1 ASA reads (R-A1..R-A4) and the reuse of the approved reads for failover (§10 item 6) for a
  one-off attended measurement on one ASA pair the PO names, then for readiness?
- **Q6.** FTD stays out until neXus manages FTD/FMC devices -- agreed?

## 10. Changes after the Argon architecture review (2026-11-12)

Review: `ARGON_FAILOVER_FORTIGATE_ASA_REVIEW.md` (verdict CHANGES_REQUIRED). Each point below was checked against the
code or the live DB (counts only) before it was adopted; these items override §3-§8 where they differ.

1. **Session-dropping writes (adopted; verified in code).** A vendor descriptor flag `writeMayDropSession` (ASA, FortiGate)
   lets `dispatch` record a new delivery value `SESSION_DROPPED_AFTER_SEND` (only when the literal was written to the
   channel; a failure before the write stays NOT_SENT / UNKNOWN as today) and continue into observation. The run is
   confirmed only by the two-sided serial-matched observation; if the poll budget ends without the expected roles,
   `unknown(...)` runs exactly as today (OUTCOME_UNKNOWN, incident, quarantine). This changes the mutation boundary
   (DB CHECK on `failover_dispatch_intent.delivery`, `confirmDispatch` predicate): decision council + code-review
   plugin before build, CP/PAN behaviour byte-identical. Phase 1 also measures whether the prompt returns before the
   address moves; if it does, the flag is not needed for that vendor.
2. **ASA identity across the address swap (adopted; measured).** Live trust store, counts only: in all 5 ASA pairs
   both enrolled addresses present the **same** SSH host key (1 distinct key per pair). So SSH trust survives the swap,
   and the host key cannot tell the two physical units apart. Model: the two ASA device rows are the pair's
   **address slots**, not chassis. Phase 0 records the pair's two serials (from the approved `show version` on each
   address) as a pair identity set; every readiness and failover read requires the observed serial to be one of the two
   recorded serials and the two observers to report different serials. Recorded identity is never re-bound per address
   after a switch. Today ASA inventory/backup check no identity (none recorded), so they are unaffected by a swap.
3. **FortiGate needs two enrolled members (adopted).** The live path requires exactly two enrolled members
   (`enrolledFailoverMembers`), and the evidence law forbids treating the primary's report about its peer as peer
   evidence. Q2 is answered: **enrol each member's own HA management address as its own device**. The
   `execute ha manage` hop is not used for failover. Without per-member addresses, FortiGate gets no failover
   readiness and no switch; FortiGate therefore moves behind ASA and behind the network team providing those addresses.
4. **Phase 0 scope widened (adopted).** SSH host-key trust (`requireTrusted`) for ASA and FortiGate members at request
   and readiness time; a migration adding `asa_cluster_failover`, `asa_failover_readiness`, `fgt_cluster_failover`,
   `fgt_failover_readiness` to `ui2_job_module()` and widening the vendor CHECKs; the summary query's PAN-only lateral
   generalised; `failover_resume` stays Check Point only (ASA and FortiGate F1 have no return step).
5. **Check rules corrected (adopted).**
   - ASA check 5: PASS when the error counters (`xerr`, `rerr`) did not increase between the two samples and cumulative
     `xmit` (active) / `rcv` (standby) are positive when the connection count is positive. No "xmit must increase in
     5 s" rule (false FAIL on a quiet pair).
   - Check 6 (both vendors): the CP low-count rule applies: below 10,000 sessions PASS if the absolute difference is
     under 2,000 or the ratio is at least 50 %; at 10,000 and above, at least 80 %; zero before passes.
   - FortiGate check 8: secondary HA uptime greater than `ha-uptime-diff-margin`, both members' priorities recorded,
     override disabled, monitored-interface counts equal; otherwise NOT_READY ("the switch would not move the
     primary").
   - FortiGate VDOM framing uses the approved context moves (`config global` / `config vdom` / `edit <vdom>` / `end`,
     gate `fgt_context_moves`); the VDOM list comes from the approved `get system status` / configuration reads.
   - R-F2 and R-F3 are requested only if the Phase 1 measurement shows `get system ha status` on 7.4 lacks the uptime,
     priority and flag fields.
   - Poll budget: 3 s interval, at most 40 polls per member (120 s).
6. **Approved reads used for a new purpose (adopted).** `show version`, `show mode` (ASA) and `get system ha status`,
   `get system status`, `show full-configuration system ha` (FortiGate) get their own failover-purpose gate rows
   (frequency "readiness every 4 h + each run phase"), as the CP/PAN failover reads did; the PO approves that use.
7. **F1 over F2 (confirmed).** F2's unset re-elects and, with override disabled, can move traffic back (a second
   outage). F2 is withdrawn from the literal request.
