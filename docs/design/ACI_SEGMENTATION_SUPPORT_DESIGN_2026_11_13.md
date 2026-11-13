# Cisco ACI segmentation programme support - design

## Status

DRAFT - 2026-11-13. Design only; not implementation authority (AGENTS.md "Contract-status law"). Written without
any device, APIC or host contact. Every APIC read in section 6 is a proposal: nothing is gated, implemented or run
until the Product Owner approves each exact literal (AGENTS.md "Network action taxonomy", 2026-09-26 amendment). The
zone matrix contract and the legacy upper-bound match this design builds on are themselves unmerged DRAFT work on
their own branches (section 3).

## 1. Context and goal

The bank's data-centre network runs a Cisco ACI fabric. The fabric can send traffic between data-centre layers
straight to a chosen firewall (an ACI service graph with policy-based redirect, "PBR") instead of letting it travel
through the intermediate layers. The network programme moves traffic onto this redirected path in three phases:
phase 1 is live (zero downtime), phase 2 is in measurement and design, phase 3 is being planned. For every flow the
team works out the forward path, the return path and a rollback plan by hand.

The two hard parts are the ones neXus can make measurable:

1. **Rule readiness.** Traffic that used to cross firewall X now crosses target firewall Y. If Y's policy does not
   allow a flow that is in use today, the cutover breaks it.
2. **Asymmetry.** A stateful firewall drops a session whose first packet it did not see. If the request leaves by
   the new path and the reply comes back by the old one (or the reverse), the connection breaks at once.

Goal of this design: three capabilities, smallest valuable step first, each stating what it uses from data neXus
already holds and what it would need new.

| # | Capability | New device or API reads? |
| --- | --- | --- |
| 1 | Pre-cutover impact analysis per segment (section 4) | None |
| 2 | Cutover and asymmetry monitoring (section 5) | None for step 2a; PO-approved literals for 2b |
| 3 | ACI (APIC) as a read-only data source (section 6) | Yes, every read PO-approved by exact literal |

The answers neXus gives are evidence for the network team's decision. neXus never decides a cutover, never triggers a
rollback, never writes to the fabric or to a firewall, and never says a segment is "safe".

## 2. Terms

- **Segment**: a named set of IPv4 blocks (CIDR) that is moved onto the redirected path together. Same block rules as
  a zone of the zone matrix (canonical CIDR, no silent correction). A segment is not a zone: zones describe the
  bank's segmentation policy, segments describe one cutover wave. A segment may be built from zones (section 6.6).
- **Old path / new path**: the firewall(s) a flow crosses before and after the cutover.
- **Target firewall**: the enrolled firewall (and context: Check Point VS, Palo Alto vsys) the fabric will redirect
  the segment's traffic to. Several target firewalls per segment are allowed (one per redirect).
- **Cutover**: the moment the fabric starts redirecting a segment. neXus does not observe it; the operator records
  its time (section 5.4).
- **One-sided session**: a TCP session one firewall sees only half of (for example a non-SYN first packet, or a reply
  with no session). The vendor signals are in section 5.1.
- **Flow tuple**: (source address, destination address, IP protocol, destination port), as in Rule object usage.
- Synthetic values only in this document: `192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`; segment names
  `SEG-ALPHA`, `SEG-BRAVO`; masked device names `FW-TANGO-04`, `FW-JULIET-06`; APIC names `<TENANT>`, `<EPG>`.

## 3. What this design reuses, and the laws it keeps

Reused, by state:

| Piece | Where | State on 2026-11-13 |
| --- | --- | --- |
| Policy lookup (three-valued first match, CP ordered/inline layers, PAN pre/post, placement checks) | `ui2/service/.../policy/PolicyLookup.java`, `PolicyNormalization.java` | On `main`; CP and PAN only |
| Legacy upper-bound match (`PolicyNormalization.upperBound`: narrowing constraints dropped, every selector must still resolve, CP access-role network bound not used) | branch `feat/hygiene-legacy-resolution-1113` (commit 8f7aaa57) | Not merged, not wired to a screen; its A0 amendment needs PO approval |
| Rule object usage (uploaded traffic-log CSV, in-memory read, aggregates only; one-rule and whole-policy modes) | `docs/design/RULE_OBJECT_USAGE_CONTRACT.md`, `OfflineAnalysis.java`, `OfflineCsv.java` | Contract FROZEN; one-rule mode on a local lane, not on `main` |
| Path analysis / Trace (topology graph from inventory, hop engine, return-path walk, `ASYMMETRIC`, `PBR_PRESENT`, `EXITS_KNOWN_TOPOLOGY`) | `docs/design/PATH_ANALYSIS_DESIGN.md`, `ui2/service/.../pathanalysis/` | FROZEN; phase A shipped |
| Path validation matchers (PAN `test security-policy-match`, `test nat-policy-match`, `test routing fib-lookup`, CP `ip route get`) | `docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md` | Literals approved; TCP only; multi-vsys PAN hops not evaluable |
| Zone matrix (zones as IP blocks, four-eyes publish, address lookup right) | `ZONE_MATRIX_CONTRACT.md` on branch `docs/zone-matrix-contract-1113` | DRAFT, revision 2, not frozen |
| Device system logs (scheduled collection, masked view) | `docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md` | Live; see section 5.2 for what it does and does not hold |

Laws kept throughout (AGENTS.md):

- **Fail-closed / UNKNOWN.** A flow neXus cannot decide is `NOT_DECIDED` with its reason, never counted as allowed or
  as dropped. A segment with any undecided flow or any coverage gap never gets a clean verdict.
- **Intent is not runtime.** Policy results describe the policy as stored in management. Install state on the
  gateway, Panorama sync and the fabric's actual redirect are not verified by a policy result.
- **Raw-evidence law.** Uploaded logs and APIC responses are read in memory and reduced to tuples, counts, states and
  opaque references. No raw log line or raw APIC response is stored.
- **Masked identities.** aiview and every shared surface see masked device names (`FW-TANGO-04`), masked addresses
  (subnet-preserving masker) and pseudonymised tenant, VRF, bridge-domain and EPG names. Real names never enter
  chat, commits, PRs or repository records.
- **Vendor semantics.** A vendor fact that is load-bearing is cited to official documentation and, before it gates
  anything, corroborated by a sanitized real sample. Section 5.1 marks each fact's confidence.
- **No new device or API command without the PO's exact-literal approval.**

## 4. Capability 1 - pre-cutover impact analysis

### 4.1 Question answered

"For segment S, about to be redirected into target firewall Y: which flows in use today would Y's current policy drop,
or leave undecided, and which rules are missing?"

### 4.2 Inputs

| Input | Source | Have today? |
| --- | --- | --- |
| Segment blocks | Typed in by a security_admin (step 1); later imported from zone-matrix zones or APIC subnets (section 6.6) | New small record |
| Redirect scope: which traffic of S is redirected (peer blocks, and optionally protocol/port filter) | Network team's change plan, typed in per segment; later read from the ACI contract filter (section 6.7) | New small record |
| Target firewall and context; for Palo Alto also the ingress and egress zone the redirected traffic arrives in and leaves by | Network team (which firewall interfaces the service graph uses); zone names picked from inventory | Inventory has interfaces and PAN zones; the mapping itself is new |
| Target policy | Latest stored policy snapshot of the target's policy source | Yes |
| Old-path policy (optional, for the "missing rule" reference) | Stored snapshots of the firewalls the flows cross today | Yes |
| Traffic evidence | One or more uploaded traffic-log exports (generic CSV with column mapping; vendor presets only after their measurement record, Rule object usage section 3) | Yes (upload path); new: several files per assessment, see 4.8 |

Why the zone mapping matters: after the redirect, a Palo Alto target sees the traffic on the service-graph interfaces,
not on the interfaces its routing table would choose. Palo Alto rules match zones, so the lookup needs the zone pair
the redirected traffic will really use. neXus never infers it from routing for a redirected segment; without it, every
PAN flow is `NOT_DECIDED (zone or target required)`. Check Point rules can also carry security zones, which the lookup
already leaves UNKNOWN until the gateway topology parse exists (path analysis decision D-6).

### 4.3 Flow selection

1. Parse each uploaded export with the existing CSV reader (limits: 100 MB / 2,000,000 rows per file, gzip counted
   decompressed). Required columns as today: `source`, `destination`, `port`, `protocol`; optional `time`, `action`,
   `rule`, `count`, zones.
2. Keep a row when it is in the redirect scope: source in S and destination in the scope's peer blocks, or the
   reverse (both initiation directions are separate flows; a stateful allow for one does not permit sessions started
   from the other side).
3. Reduce rows to distinct flow tuples with summed count, first and last seen, and the set of `action` values the
   logging firewall recorded. Rows with an unparsable field are counted by reason (`parseStats`), as today.
4. Bound: at most 50,000 distinct tuples per assessment (proposal; set from a memory measurement before step 1
   ships). Over the bound the assessment stops with `TUPLE_LIMIT`, never a truncated result.

Rows the logging firewall recorded as dropped are kept and marked `ALREADY_DROPPED_TODAY`: a flow that fails today is
not a cutover regression, but it is shown, because it may be a flow the redirect is meant to fix.

### 4.4 Evaluation per flow

For each tuple, against the target's stored snapshot, in this order:

1. **Unsupported target.** Target vendor is Cisco ASA or FortiGate: `NOT_EVALUABLE (VENDOR_POLICY_NOT_COLLECTED)`.
   neXus collects inventory and backups for these vendors but holds no evaluable policy model for them today.
2. **Unsupported tuple.** IPv6, or a protocol other than TCP/UDP: `NOT_EVALUABLE` with the reason (the lookup is IPv4
   and TCP/UDP only).
3. **Sound lookup** (`PolicyLookup.Prepared.evaluate`, the exact engine the Policy lookup screen uses) with the target,
   context and, for PAN, the zone pair of 4.2:
   - `ALLOWED` -> `WOULD_ALLOW`, deciding rule recorded.
   - `DENIED` -> `WOULD_DROP`, deciding rule recorded.
   - `UNKNOWN` -> step 4.
4. **Upper-bound pass**, only to explain an UNKNOWN, never to turn it into a decision. Using
   `PolicyNormalization.upperBound` (once merged): if the first rule whose upper bound covers the tuple is an allow
   rule that the sound match left UNKNOWN because of a narrowing constraint (time, schedule, source user, VPN,
   content), the flow is `NOT_DECIDED (CONDITIONAL_RULE)` naming that rule and the dropped constraint kinds. If no
   rule's upper bound covers the tuple, the flow falls to the implicit default: `NOT_DECIDED` with the existing
   reasons (CP "implicit cleanup action not collected"; PAN intrazone/interzone default "override not collected").
   An upper bound can say "at most this rule could match"; it never proves an allow.
5. **Lookup reasons carried through.** Every other UNKNOWN keeps the lookup's own reason text (for example
   "snapshot collection incomplete", "target context unproven", "application constraint", "earlier rule could
   match", "analysis budget").

Additional flags on a flow (they never change the decision):

- `OLD_PATH_RULE`: when an old-path snapshot is given, the rule that decides the tuple there today, run through the
  same lookup. A tuple allowed on the old path and `WOULD_DROP` or `NOT_DECIDED` on the target is the core of the
  missing-rule list.
- `LOG_RULE_DISAGREES`: the export's `rule` column names a rule on the logging firewall; when the logging firewall is
  the old-path firewall and its snapshot is given, disagreement with neXus's old-path result is counted (Rule object
  usage section 4), never silently resolved.
- `NAT_NOT_EVALUATED`: the target has a NAT rule whose original destination covers the tuple's destination. The policy
  lookup does not evaluate NAT; path analysis does. Shown so the operator runs a Trace for that tuple.
- `ASYMMETRY_RISK`: the redirect scope covers only one initiation direction of a pair of blocks that also has traffic
  in the other direction in the logs, or a peer block lies outside every segment and outside the redirect scope (its
  reply may take the old path). This is a planning hint from the typed scope; it is not a path computation.

### 4.5 Missing rules

Group `WOULD_DROP` and `NOT_DECIDED` tuples that were allowed on the old path (or have no old-path snapshot) by
(destination address, protocol, port), then by source. Each group is one "rule needed" line: source set (exact
observed addresses, or the observed narrower range when there are more than 50, as Rule object usage narrows today),
destination, service, flow count, first/last seen, old-path rule reference. It is a worklist for the rule-request
process, never a rule pushed anywhere, never a proposed rule text for a device.

### 4.6 Readiness verdict per segment

One verdict per (segment, target), always printed with its coverage list:

| Verdict | When |
| --- | --- |
| `BLOCKERS_FOUND` | At least one in-scope tuple that is not `ALREADY_DROPPED_TODAY` is `WOULD_DROP` |
| `NOT_DECIDED` | No `WOULD_DROP`, but at least one tuple is `NOT_DECIDED` or `NOT_EVALUABLE` |
| `NO_BLOCKERS_IN_EVALUATED_FLOWS` | Every in-scope tuple is `WOULD_ALLOW` or `ALREADY_DROPPED_TODAY` |
| `NO_EVIDENCE` | No in-scope tuple at all (wrong scope, wrong file, or no logging firewall on the old path) |

Rules:

- No verdict is worded "safe", "ready", "pass" or shown green. The best verdict reads: "No blocking flow found among
  N evaluated flows. Coverage: ..." The coverage list is never empty, because some items are always UNKNOWN:
  - log period covered (first to last row) and whether every relevant rule logged in that period (UNKNOWN unless the
    export proves it, as Rule object usage says);
  - flows that crossed no logging firewall are invisible to this analysis;
  - target snapshot age and that install/sync state is not verified;
  - NAT not evaluated (count of flagged tuples);
  - IPv6 and non-TCP/UDP rows set aside (counts);
  - for PAN, applications and `application-default` (lookup reason "application constraint").
- The verdict cites the snapshot id and collection time, the upload ids (not names), the scope revision and the
  engine version, so a later reader knows exactly what was evaluated.
- A verdict is stale (`STALE`) when the target snapshot changes or the segment or scope is edited; re-running is one
  click, never automatic.

### 4.7 Output

- Screen: per segment, the verdict line and coverage, then four tabs: Would drop, Not decided (grouped by reason),
  Missing rules, Would allow. Each flow row: tuple, count, first/last seen, decision, deciding rule (number, uid, name
  through `PolicyPrivacy`), reason, flags.
- aiview and every masked role see masked addresses and device names; rule identity follows today's policy RBAC
  (withheld without `policy_read`).
- Export: CSV through Reports R1 (stored file, formula guard), following the caller's screen view; raw needs the R1
  raw right.
- CLI parity (PO P1 rule): the same assessment can be created, run and read from the CLI.

### 4.8 Storage, rights, limits

- New tables (one migration, dry-run in BEGIN/ROLLBACK on the live database before rollout): `aci_segment`
  (id, name, blocks, scope peers, optional service filter, revision, audit), `cutover_assessment` (segment revision,
  target device and context, PAN zone pair, snapshot ids, upload ids, engine version, verdict, coverage, counts) and
  `cutover_assessment_flow` (bounded tuples with decision, rule ref, reason, flags). Address columns registered in
  `audit_redaction_policy`. No raw row, header, mapping or file content (same CHECK guard as V146).
- Several uploads per assessment (old-path exports from several firewalls) are allowed; each is read once, streamed
  and discarded, as today.
- Rights: `cutover_assessment_read` (security_admin, compliance_admin, onboarding_admin, viewer; replay_viewer
  masked), `cutover_assessment_write` (security_admin, onboarding_admin: edit segments, upload, run). Segment blocks
  are address space: raw view only with a raw-view right, like zone blocks.
- Runs on the policy bulkhead with a work budget per tuple (the lookup's own budget) and a wall-clock ceiling; a run
  over budget ends `INCOMPLETE` with what it decided, and the verdict is `NOT_DECIDED`.
- Retention: per bank decision (open question Q7); proposed 90 days for flows, verdict rows kept with the segment.

### 4.9 Out of scope for capability 1

No live traffic, no device command, no fabric read, no rule change or ticket, no claim about the return path (that is
Trace and capability 2), no ASA or FortiGate policy evaluation.

## 5. Capability 2 - cutover and asymmetry monitoring

### 5.1 Vendor signals of a one-sided session

Confidence scale used in this section (research 2026-11-13, documentation only; **no fact below is corroborated in
the bank's environment yet**):

- **HIGH**: stated on an official vendor documentation page that was read.
- **MEDIUM**: official vendor page, but only partly read (search excerpt) or the page states the fact without detail.
- **LOW**: vendor community or forum only (including vendor-staff technical tips outside the admin guides).
- **UNKNOWN**: not found; nothing is built on it.

| Vendor | Signal | Kind | Confidence | Source |
| --- | --- | --- | --- | --- |
| Check Point | Global property Stateful Inspection > "Drop out of state TCP packets"; out-of-state drops; a policy installation overrides kernel values | Setting / drop behaviour | HIGH | Check Point sk147093 |
| Check Point | Kernel parameter `fw_allow_out_of_state_tcp` (read with `fw ctl get int`) | Setting | MEDIUM: the sk shows the command, not the meaning of its values | sk147093 |
| Check Point | Debug drop strings "Possible TCP state violation", "invalid state detected" in `fw ctl zdebug + drop` | Kernel debug | HIGH | sk147093 |
| Check Point | Log text "First packet isn't SYN" / "TCP packet out of state" in Logs & Monitor | Drop log | LOW | CheckMates community threads only |
| Check Point | Whether out-of-state drops are logged by default | Logging behaviour | UNKNOWN | not in sk147093 or sk170144 |
| Palo Alto | Zone protection, packet-based: "Reject Non-SYN TCP" (global / yes / no) and "Asymmetric Path" (global / drop / bypass) | Setting | HIGH | docs.paloaltonetworks.com, PAN-OS 12.1 web help, Zone Protection > TCP Drop |
| Palo Alto | Device-wide CLI `set deviceconfig setting session tcp-reject-non-syn`, `set deviceconfig setting tcp asymmetric-path` | Setting | LOW | community configuration article |
| Palo Alto | Global counters `flow_tcp_non_syn`, `flow_tcp_non_syn_drop` (`show counter global`) | Counter | LOW | community threads; the official KB page was not reachable |
| Palo Alto | Traffic-log session end reason for these drops | Drop log | UNKNOWN | not found |
| Cisco ASA | Syslog `%ASA-6-106015` "Deny TCP (no connection)" | Drop log | HIGH | Cisco ASA syslog messages guide, 101001-199021 |
| Cisco ASA | Syslog `%ASA-4-106023` is an access-list deny, not a state drop (useful to capability 1, not to asymmetry) | Drop log | HIGH | same guide |
| Cisco ASA | `show asp drop` reason `tcp-not-syn` "First TCP packet not SYN" | Counter | MEDIUM: official page, excerpt only | Cisco "show asp drop command usage" |
| Cisco ASA | Syslog 313004 | Drop log | UNKNOWN | not checked |
| Cisco ASA | TCP state bypass removes the state check for matched traffic (no drop, no signal) | Setting | LOW | community answer; Cisco warning seen only in passing |
| FortiGate | `diagnose debug flow` message "no session matched" | Kernel debug | LOW | Fortinet community technical tip |
| FortiGate | These drops reach the traffic log only with `log-invalid-packet` enabled | Logging behaviour | LOW | Fortinet community technical tip |
| FortiGate | `config system settings` / `set asymroute enable`: a non-SYN packet without a session skips policy and is routed; the log shows policy id 0 | Setting | LOW | Fortinet community technical tip |
| FortiGate | `diagnose sys session stat` drop counters | Counter | UNKNOWN | not found |

Consequences for the design:

1. **Silence is not symmetry.** Four settings make a one-sided session pass silently instead of dropping it: Check Point
   out-of-state accepted, Palo Alto asymmetric path `bypass`, ASA TCP state bypass, FortiGate `asymroute`. Where a
   setting like this applies, "no one-sided drops seen" means nothing. The live view (5.4) therefore shows each
   target's setting as `DROPS` / `PASSES_SILENTLY` / `UNKNOWN`, and "no signal" is never shown as good while it is
   `UNKNOWN`.
2. **Debugs are out.** `fw ctl zdebug` and `diagnose debug flow` load the gateway and print raw packets; neXus never
   runs them. They are the network team's own tools.
3. **No LOW or UNKNOWN fact drives a state.** Before any signal in the table gates a state on a screen, it needs a
   measurement record: one sanitized real sample (log export or command output) read through the masked path, the
   same rule Rule object usage applies to vendor presets.

### 5.2 What neXus collects today

Collected (device system logs, Check Point gateways and Palo Alto firewalls only):

- Check Point: incremental text files (`/var/log/messages*`, `$FWDIR/log/fwd.elg*`, `$CPDIR/log/cpd.elg*`,
  `$CPDIR/log/cpwd.elg*`, `$FWDIR/log/fwk.elg*`, `$FWDIR/log/mq_mng.elg*`, spike detective), a daily `sar` file and HCP
  report, and crash/dump file names (`DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md` gates C1-C8).
- Palo Alto: `log-type=system` and `log-type=config` only (gates P1-P5).
- Masked view `logmask-v6` for aiview; unmasked view needs `system_log_read_unmasked` and is audited first.

Not collected, by contract:

- Traffic and drop logs of any vendor. `$FWDIR/log/fw.log` and Palo Alto traffic/threat logs are excluded by the
  system-log programme contract, and a test pins that the Palo Alto collector refuses `log-type=traffic`. Rule object
  usage's non-goals add "no live log ingestion, no syslog receiver".
- Any system log from Cisco ASA or FortiGate (research only, no gate row).
- Any drop counter: no gate row exists for Check Point `fw ctl pstat` / `fwaccel stats -d`, Palo Alto `show counter
  global`, ASA `show asp drop` or FortiGate `diagnose sys session stat`.

Related pieces that exist:

- Gated session reads approved for failover: Check Point `fw tab -t connections -s`, Palo Alto
  `<show><session><info/></session></show>` and `<show><session><meter/></session></show>`, ASA `show conn count`.
  They read session totals, not drops, and "command presence in source != command approval": reusing them for
  cutover watching is a new purpose that needs its own gate review.
- Packet Analysis (uploaded pcaps) already finds `asymmetric_routing`, `handshake_incomplete` (SYN with no SYN-ACK),
  `handshake_blocked` and `half_open` sessions.
- Path analysis already computes the return path and makes a result `UNKNOWN` with `OUT_OF_STATE_DROP_LIKELY` when
  the return crosses a different set of stateful units. It does not know the fabric redirect (section 6.7); a Gaia
  non-main routing table only raises `PBR_PRESENT`, and the phase B architecture records "PBR remains a caveat".
- Uploaded traffic-log CSV (Rule object usage) has an optional `action` column, but the analysis does not read it
  today, and there is no TCP-flag or session-state column.

### 5.3 Signal paths, in order of cost

| Path | New reads? | What it gives | Limits |
| --- | --- | --- | --- |
| 2a-1 Upload of the target's traffic-log export for the watch window | None | Per-segment one-sided drops, attributed by tuple to the segment | Needs that vendor's measurement record to know which export column/value marks an out-of-state drop; as fresh as the operator's uploads |
| 2a-2 Packet Analysis on a capture the network team takes at cutover | None | Proven one-sided handshakes and asymmetric returns for the captured link | Only what the capture point saw |
| 2a-3 Trace queries for sample tuples of the segment | None until 6.7; then APIC reads | Computed forward and return path with `OUT_OF_STATE_DROP_LIKELY` | Until the redirect overlay exists, a Trace for a redirected segment is wrong; it must carry a "fabric redirect not modelled" caveat (Q9) |
| 2a-4 State-handling settings from configuration neXus already collects | None if it is a parse-scope extension (Palo Alto zone protection in the collected configuration is the likely first case) | `DROPS` / `PASSES_SILENTLY` per target (5.1 point 1) | Each vendor's field needs a measurement record; Check Point global properties and ASA/FortiGate settings: whether they are in collected data is UNKNOWN |
| 2b Counter snapshots on the target, operator-started, bounded to the watch window | Yes: one PO-approved literal per vendor, after a measurement record | A device-wide before/after rate of one-sided drops within minutes | Device-wide, **not attributable to a segment**; counter names are LOW confidence today |
| 2c Live traffic-log streaming (syslog receiver, log exporter, log-server query) | Yes, and a new contract | Full live per-segment view | Excluded by two current contracts; volume and raw-evidence cost; not proposed |

Candidate 2b literals, listed only so the PO sees the shape; **none is proposed for approval** until its measurement
record exists: Palo Alto `<show><counter><global><filter><delta>yes</delta></filter></global></counter></show>`; ASA
`show asp drop`; Check Point and FortiGate: no candidate, the counter that carries out-of-state drops is UNKNOWN.

### 5.4 Per-segment live view for the first hours

A **cutover watch** is a record an operator opens for one segment at cutover: segment revision, target(s), the
time the fabric change is made (typed by the operator; neXus cannot observe it), and a watch window (default 4 hours,
at most 24). It shows:

1. The segment's last readiness verdict (4.6) with its age, and whether it is `STALE`.
2. Per target: its state-handling setting (`DROPS` / `PASSES_SILENTLY` / `UNKNOWN`, 5.1 point 1).
3. One-sided signal timeline from whatever path the target has (2a-1 uploads now; 2b counters only if approved),
   with the pre-cutover baseline beside it when one was taken. Device-wide counters are labelled "device-wide, not
   attributable to this segment".
4. Top one-sided tuples of the segment (from uploads), masked for aiview, each with its Trace link.
5. Packet Analysis cases linked to the watch (2a-2).

States per target: `NO_SIGNAL_SOURCE`, `NO_ONE_SIDED_SIGNAL_SEEN` (always with its coverage: source, period, setting),
`ONE_SIDED_SIGNAL_SEEN`, `SIGNAL_SOURCE_FAILED`. Never "healthy", "symmetric" or green. `NO_ONE_SIDED_SIGNAL_SEEN`
on a target whose setting is `PASSES_SILENTLY` or `UNKNOWN` is shown as "no evidence either way".

neXus shows evidence; the rollback decision and action stay with the network team. Refresh is operator-driven (an
upload, a button); with 2b, a bounded repeat inside the watch window only (proposal: every 5 minutes, ends with the
window, one read per target at a time). No recurring schedule outside a watch. Manual admin jobs are not held to the
device polling windows and ask for confirmation in business hours (PO decision 2026-10-20), which fits night-time
cutovers.

## 6. Capability 3 - ACI as a read-only data source

### 6.1 Purpose

Two uses, both read-only:

- (a) **Zone and segment fill.** Suggest zone-matrix zones and capability-1 segments from the fabric's own address
  definitions (EPG, endpoint security group, external EPG and bridge-domain subnets), instead of the bank typing them.
- (b) **Redirect knowledge for Trace.** Know which traffic the fabric redirects to which firewall, so path analysis
  computes the real path (and return path) of a redirected flow instead of the routing-table path.

ACI was deferred to path-analysis phase C (`PO_DECISION_RECORD_2026_11_03_PATH_ANALYSIS_PHASE_A.md`), and a
read-only APIC account was already listed as pending in the 2026-11-05 PO handover. This section is the proposal for
that phase's data source; it changes no earlier decision.

### 6.2 Transport and credentials

- **Transport**: HTTPS to the APIC REST API, from the worker that already runs device jobs (no new network path from
  the browser; AGENTS.md "No Browser -> device path"). One APIC address from a bank-provided list; the first that
  answers is used for the whole run and recorded as an opaque reference.
- **TLS**: verified against the bank's CA, with the APIC certificate pinned at enrollment like device identity today.
  The Palo Alto "TLS verification disabled" decision of 2026-09-21 is Palo Alto-specific and does not extend to APIC;
  if the bank wants the same for APIC, that is a separate PO decision.
- **Account**: one dedicated APIC user for neXus, security domain `all`, role `read-all` with read privilege only
  (HIGH: Cisco APIC security configuration guide 6.1). The password lives in the existing encrypted credential
  store, never in the browser or a log.
- **Session**: `aaaLogin` at run start, token in memory only, `aaaLogout` in a `finally` path. No token refresh: a run
  is short (6.4); an expired token fails the run, which is retried by the operator.
- **Hardening option** (Q6): signature-based request authentication with an X.509 certificate bound to a local APIC
  user (HIGH: APIC REST API configuration guide 4.0.1; needs a local user and HTTPS). No password crosses the wire and
  no session exists; the private key sits in the credential store.

### 6.3 Proposed reads (exact literals, for PO approval; nothing runs before that)

Common to every read: method `GET` only (except login/logout), JSON, paged with `page=<N>` (integer from 0, generated
by the loop) and `page-size=500`, timeout 30 seconds per page, at most 200 pages per class, no retry inside a run,
at most 2 requests per second, one run per fabric at a time, run wall-clock ceiling 15 minutes. Parameters in angle
brackets are filled only by the code: `<N>` the page number, `<USER>` and `<SECRET>` from the credential store.

| # | Exact literal | Purpose | Fields kept (everything else discarded in memory) |
| --- | --- | --- | --- |
| R0a | `POST /api/aaaLogin.json` body `{"aaaUser":{"attributes":{"name":"<USER>","pwd":"<SECRET>"}}}` | Session start | token (memory only) |
| R0b | `POST /api/aaaLogout.json` body `{"aaaUser":{"attributes":{"name":"<USER>"}}}` | Session end | none |
| R1 | `GET /api/class/fvTenant.json?page=<N>&page-size=500` | Tenants | `dn`, `name` |
| R2 | `GET /api/class/fvCtx.json?page=<N>&page-size=500` | VRFs | `dn`, `name`, `pcEnfPref` (enforced or not: an unenforced VRF has no contracts, so no redirect) |
| R3 | `GET /api/class/fvBD.json?rsp-subtree=children&rsp-subtree-class=fvSubnet,fvRsCtx&page=<N>&page-size=500` | Bridge domains, their subnets and VRF | BD `dn`, `name`; subnet `ip`, `scope`; `fvRsCtx.tDn` |
| R4 | `GET /api/class/fvAEPg.json?rsp-subtree=children&rsp-subtree-class=fvRsBd,fvRsProv,fvRsCons,fvSubnet&page=<N>&page-size=500` | EPGs: bridge domain, provided/consumed contracts, EPG subnets | `dn`, `name`, `isAttrBasedEPg`, `pcEnfPref`, `prefGrMemb`; relation `tDn`s; subnet `ip` |
| R5 | `GET /api/class/l3extInstP.json?rsp-subtree=children&rsp-subtree-class=l3extSubnet,fvRsProv,fvRsCons&page=<N>&page-size=500` | External EPGs (L3Out) and their subnets | `dn`, `name`; subnet `ip`, `scope`; relation `tDn`s |
| R6 | `GET /api/class/vzBrCP.json?rsp-subtree=full&rsp-subtree-class=vzSubj,vzRsSubjFiltAtt,vzRsSubjGraphAtt,vzInTerm,vzOutTerm&page=<N>&page-size=500` | Contracts, subjects, filters attached, service graph attached, per-direction terms | `dn`, `name`, `scope`; subject `revFltPorts`; relation `tDn`s |
| R7 | `GET /api/class/vzEntry.json?page=<N>&page-size=500` | Filter entries | `dn`, `etherT`, `prot`, `dFromPort`, `dToPort`, `sFromPort`, `sToPort` |
| R8 | `GET /api/class/vzAny.json?rsp-subtree=children&rsp-subtree-class=vzRsAnyToProv,vzRsAnyToCons&page=<N>&page-size=500` | VRF-wide contracts | `dn`; relation `tDn`s |
| R9 | `GET /api/class/vnsAbsGraph.json?rsp-subtree=full&rsp-subtree-class=vnsAbsNode,vnsAbsFuncConn,vnsAbsConnection&page=<N>&page-size=500` | Service graph templates and node chain | `dn`, `name`; node `funcType`, `routingMode`; connection order |
| R10 | `GET /api/class/vnsLDevCtx.json?rsp-subtree=full&rsp-subtree-class=vnsLIfCtx,vnsRsLIfCtxToSvcRedirectPol,vnsRsLIfCtxToBD,vnsRsLDevCtxToLDev&page=<N>&page-size=500` | Device selection policies: which device, which interface context, which redirect policy, which BD | `dn`, `ctrctNameOrLbl`, `graphNameOrLbl`, `nodeNameOrLbl`; `connNameOrLbl`; relation `tDn`s |
| R11 | `GET /api/class/vnsSvcRedirectPol.json?rsp-subtree=children&rsp-subtree-class=vnsRedirectDest&page=<N>&page-size=500` | PBR policies and their destinations | `dn`, `name`, `hashingAlgorithm`, `thresholdEnable`, `thresholdDownAction`, `destType`; destination `ip`, `mac` (mac only compared, never stored) |
| R12 | `GET /api/class/vnsLDevVip.json?rsp-subtree=children&rsp-subtree-class=vnsCDev,vnsLIf&page=<N>&page-size=500` | L4-L7 logical devices, concrete devices, logical interfaces | `dn`, `name`, `devtype`, `svcType`, `managed`, `contextAware`; child `dn`s |
| R13 | `GET /api/class/vnsGraphInst.json?rsp-subtree=children&rsp-subtree-class=vnsNodeInst&page=<N>&page-size=500` | Deployed graph instances (what the APIC rendered) | `dn`, `configSt`; node `dn` |

Confidence of the literals' form: the endpoints, `/api/class/<class>.json`, `query-target`/`target-subtree-class`,
`rsp-subtree`/`rsp-subtree-class` and `page`/`page-size` are HIGH (APIC REST API configuration guide 4.2(x) and
later). The **class and attribute names are MEDIUM at best**: `vnsSvcRedirectPol`, `vnsRedirectDest` and `fvTenant`
were seen in official guides; the rest are from the management information model and were not yet checked against
the official MIM reference (developer.cisco.com/docs/apic-mim). Before the gate entry is written, every class and
kept attribute is checked against the MIM reference **for the bank's APIC version**, and one sanitized sample per
class is measured (measurement record). A class that does not exist in that version is dropped from the list, never
guessed.

Endpoint security groups (`fvESg` and their IP selectors) are not in the list: whether the bank uses them is asked
first (section 9); if yes, their read is proposed separately after the MIM check.

Not proposed, deliberately: endpoints (`fvCEp`, `fvIp`: live server addresses and MACs, high volume), faults, events
and audit logs, health scores, any websocket subscription, any write, any Nexus Dashboard Orchestrator (multi-site)
read.

### 6.4 Parsing, storage, masking

- Response in memory -> keep the fields of 6.3 -> discard (raw-evidence law). No raw JSON stored.
- Tables (one migration, dry-run in BEGIN/ROLLBACK on the live database): `aci_fabric` (enrolled APIC set, pinned
  certificate, credential reference), `aci_read_run` (start/end, state, per-class state and counts),
  `aci_object` (class, opaque key, parent key, kept attributes) and `aci_subnet` (owner key, canonical network block,
  scope flags). Replaced per class only when that class read COMPLETE (6.8).
- **Identity**: an APIC `dn` embeds tenant, profile and EPG names. It is stored as a keyed hash (the existing HMAC
  key) for joins, plus the names in name-bearing columns registered in `audit_redaction_policy`. The masked projection
  shows pseudonyms (new prefixes in the estate dictionary, for example `TEN-`, `VRF-`, `BD-`, `EPG-`, `CTR-`, `SGR-`)
  and masked subnets; raw names only with a raw-view right. aiview never sees a raw name.
- **Subnets**: a bridge-domain subnet is a gateway address with a prefix (`198.51.100.1/24`). The network block is
  computed explicitly (`198.51.100.0/24`) and both forms are shown on import; no silent correction.

### 6.5 Rights

`aci_read` (security_admin, onboarding_admin, viewer; replay_viewer masked), `aci_fabric_manage` (security_admin:
enroll the APIC, credential, pin), `aci_read_run` (security_admin, onboarding_admin; aiview only if the PO extends the
2026-10-04 diagnostic-read decision to APIC, Q6).

### 6.6 Use (a): zone and segment suggestions

Placement rules, fail-closed:

| Source | Placed as | Not placed when |
| --- | --- | --- |
| EPG subnet (`fvSubnet` under the EPG) | that EPG's block | - |
| Bridge-domain subnet | the EPG's block **only if exactly one EPG uses the BD** | several EPGs share the BD: `AMBIGUOUS_BD_SHARED` (EPG membership is by port, VLAN or VM attachment, not by address; neXus does not read endpoints) |
| Attribute-based (uSeg) EPG | only its IP attributes, after their read is measured | IP attributes not read: `NOT_EVALUABLE` |
| External EPG subnet with the import-security scope | an external zone block | `0.0.0.0/0` is never placed automatically |
| Same block in two VRFs | - | always: `VRF_OVERLAP`; the zone model is one address space and the bank chooses |

The output is a **suggestion into the zone-matrix draft** (or into a capability-1 segment): a mapping table "EPG ->
zone" that the bank edits, each block carrying its provenance (APIC run, owner pseudonym). It never publishes; the
zone matrix's four-eyes publish rule applies unchanged. A later read shows a diff (added, removed, moved blocks) and
never overwrites a manual edit.

### 6.7 Use (b): redirect overlay for Trace

From R2-R13 neXus derives **redirect statements**: in VRF V, traffic from consumer selector C (EPG, external EPG or
vzAny) to provider selector P matching filter entries F is redirected, per direction, through the node chain
[L4-L7 device D, PBR policy R with destinations {d1..dn}].

Binding to enrolled firewalls: a PBR destination address is matched against the interface addresses neXus already
holds in inventory (`member` and `cluster_virtual`). Exactly one enrolled unit holds it: `MATCH`, the hop is that
unit and that interface. Several: `AMBIGUOUS`; none: `MISSING` (an unmanaged firewall, shown as a cloud hop). Names
are never used to join (presentation identity law). The network team's own device-to-firewall list (section 9) is a
corroboration, recorded as `MATCH` / `MISMATCH`, never the key.

Engine rules (an extension of the frozen path-analysis design; they need their own vendor-semantics freeze, like A0,
before implementation):

- A path query whose source and destination fall in C and P (by the subnets of 6.6) and whose service matches F
  replaces the fabric segment with: ingress interface of the bound firewall on the consumer connector -> hop policy
  evaluation on that unit with that interface's zone -> egress on the provider connector -> destination.
- **Return path**: modelled through the same unit only when the redirect applies in the reverse direction too (both
  connectors redirect to the same device). Otherwise the result is `ASYMMETRIC` and, as today, never a clean
  `ALLOWED`: `UNKNOWN` with `OUT_OF_STATE_DROP_LIKELY`.
- **Several destinations**: one branch per destination (the existing ECMP rule). Both directions landing on the same
  destination depends on the PBR hashing configuration (MEDIUM: Cisco ACI PBR white paper; the default hash is source
  IP, destination IP and protocol, and the two directions must use compatible settings). Until that semantics is
  frozen from official documentation and a measured sample, the hop carries `PBR_HASH_SYMMETRY_UNVERIFIED` and the
  overall verdict is not a clean `ALLOWED`.
- **Threshold down action** (HIGH: APIC L4-L7 services deployment guide 5.3; `permit`, `deny`, and `bypass` since
  4.1(2); tracking and threshold must be enabled): with `permit`, traffic goes directly to the destination when the
  redirect destinations are down, skipping the firewall. Destination tracking state is runtime and not read, so the hop
  carries `PBR_DOWN_ACTION_PERMIT` (path depends on tracking state) or `PBR_DOWN_ACTION_BYPASS`.
- **Intent, not runtime**: everything here is APIC configuration and rendered state, not what the leaf switches
  have programmed. Every redirected hop carries `FABRIC_REDIRECT_FROM_APIC_INTENT`.
- Anything the overlay does not model (unenforced VRF exceptions, preferred group, intra-EPG contracts, copy services,
  multi-site, unbound or ambiguous destinations) makes the hop `UNKNOWN (FABRIC_POLICY_NOT_MODELLED)` with the
  construct named. Never a guess.
- The overlay is built from the last COMPLETE read; when that is older than a threshold (proposal 24 hours) every
  redirected hop is `STALE`.

### 6.8 Failure behaviour

| Event | Behaviour |
| --- | --- |
| TLS certificate not trusted or not the pinned one | Run `FAILED (TLS_UNTRUSTED)` before login; no credential sent |
| Login refused, 401, 403 | Run `FAILED (AUTH_FAILED / FORBIDDEN)`; nothing replaced |
| One class fails, times out, or exceeds 200 pages or the size cap | That class `FAILED` or `TRUNCATED`; its previous data kept and marked `STALE`; run `PARTIAL`. A truncated class is never used in part |
| `totalCount` changes between pages of one class | Class `INCONSISTENT`, previous data kept; read again next run |
| An attribute value outside the known vocabulary | That object `UNKNOWN`; never mapped to the nearest value |
| Redirect overlay inputs (R6, R7, R9-R11) not all COMPLETE in the same run | No new overlay; Trace keeps the previous one marked `STALE`, or has none |
| Run exceeds 15 minutes | Stopped, `PARTIAL`, logout still sent |
| Any error | Logout in a `finally` path; the token never leaves memory |

No retry inside a run (no retry storm against the fabric controller); the next run is the operator's.

## 7. Phase plan

Smallest valuable first step: capability 1 on data neXus already has (the guess in the brief holds). Sizes are rough
engineer-day estimates, each step its own PR with targeted tests.

| Step | Content | New reads | Depends on | Size |
| --- | --- | --- | --- | --- |
| 0 | This design, PO answers (section 10); merge the legacy upper-bound branch after its A0 amendment is approved | None | PO | - |
| 1 | Capability 1: segment and scope records, target and PAN zone pair, several uploads, sound lookup, verdicts, missing rules, coverage, screen (Fable), CSV export, CLI. Without the upper-bound branch it ships with the lookup's own UNKNOWN reasons and adds `CONDITIONAL_RULE` later | None | None (reuses the CSV reader and lookup on `main`) | 8-10 |
| 1v | Backtest on a phase-1 segment: pre-cutover exports (authorized operator uploads, agent reads masked aggregates) compared with the rules the team actually had to add | None | Step 1 | PO / team time |
| 2a | Cutover watch record and view; post-cutover upload attribution (after the first vendor's measurement record); Packet Analysis link; "fabric redirect not modelled" caveat on Trace (Q9); state-handling setting from collected Palo Alto configuration if it is a parse-scope extension | None | Step 1; a sanitized export sample | 6-8 |
| 3 | APIC enrollment, read job R0-R13 (after MIM check, measurement records and PO literal approval), storage, masking; zone and segment suggestions | R0-R13 | PO approval; read-only account; zone-matrix delivery step 1 for zone import | 10-12 |
| 4 | Redirect overlay in Trace (6.7), after its vendor-semantics freeze | None beyond step 3 | Step 3 | 8-10 |
| 2b | Counter snapshots in the watch view, only if 2a is too slow for the first hours | One literal per vendor | Measurement records; PO approval | 4-6 |
| 5 | Capability 1 takes its scope from the APIC contracts instead of typed scope; per-wave readiness for phase 3 | None beyond step 3 | Steps 3-4 | 4-6 |

Phase 2 of the programme is in measurement now, so step 1 is useful at once; phase 3 planning can use steps 3-5.

## 8. Risks

| Risk | Effect | Mitigation in this design |
| --- | --- | --- |
| "No blockers" read as "safe" | A cutover goes ahead on incomplete evidence | Wording rules and a never-empty coverage list (4.6); no green state anywhere; readiness is evidence, not authorization |
| Logs miss flows (rules without logging, short period, flows that crossed no logging firewall, month-end jobs) | A used flow is not evaluated and breaks at cutover | Coverage lists log period and "logging completeness UNKNOWN"; the team chooses the period (section 9); `NO_EVIDENCE` verdict for empty scope |
| Wrong PAN zone pair typed for the redirected interfaces | Verdicts are wrong in either direction | Zone pair printed on every result; spot check with the approved `test security-policy-match` literal on a sample (TCP, single-vsys targets only) |
| Stored policy is not the installed policy | A rule added in management but not installed looks present | Snapshot time on the verdict; "install and sync state not verified" coverage item |
| NAT on the target is not evaluated by the lookup | A destination-NATed flow is judged on the wrong address | `NAT_NOT_EVALUATED` flag sends the operator to Trace |
| Upper-bound branch not merged when step 1 ships | Fewer named reasons for UNKNOWN | UNKNOWN stays UNKNOWN with the lookup's own reason; nothing is decided by it either way |
| ASA or FortiGate targets in the programme | Capability 1 cannot evaluate them | `NOT_EVALUABLE (VENDOR_POLICY_NOT_COLLECTED)`; a policy model for them is a separate build (Q8) |
| Asymmetry hidden by a bypass setting | No drop, no signal, but no inspection either | Setting shown per target; "no signal" with `PASSES_SILENTLY` or `UNKNOWN` means "no evidence either way" (5.1) |
| Vendor facts of LOW confidence used as if proven | Wrong states on the watch view | Measurement record before any signal or literal is used (5.1 point 3) |
| APIC credential leak | Full read of the fabric's design (a map of the data centre) | Read-only role, credential store, pinned TLS, short sessions, no raw names to aiview, audit of every run |
| APIC names leak through `dn` values | Tenant and EPG names in records or screens | `dn` stored hashed; names in redaction-registered columns; pseudonyms in every masked view |
| Load on the fabric controller | APIC slowdown during operations | GET only, paged, 2 requests per second, one run at a time, no retry, operator-started |
| Device reads at a sensitive moment (2b) | A read during cutover adds load | Operator-started, bounded to the watch window, one small read per target; only if 2a is not enough |
| PBR semantics (hash symmetry, down action) wrong in the model | Trace claims a symmetric path that is not | `PBR_HASH_SYMMETRY_UNVERIFIED`, down-action caveats, intent-only caveat; vendor-semantics freeze before step 4 |
| Scope drift towards a SIEM | Raw traffic logs stored, privacy and volume cost | Live log streaming is out (2c); uploads are read once and discarded |

## 9. What the bank's network team must provide

For capability 1 (step 1):

- Per segment: its IP blocks; the peer blocks (or contracts) whose traffic is redirected; the target firewall(s) and
  context; for Palo Alto targets the zone the redirected traffic arrives in and leaves by (the service-graph
  interfaces); the planned cutover date.
- Traffic-log exports from the old-path firewalls for a period the team considers representative (proposal: at least
  14 days including a month-end), as CSV with source, destination, port, protocol and ideally time, action, rule and
  a count column (an export aggregated per tuple with a count is smaller and fully supported). Uploaded by an
  authorized operator inside neXus; the agent never sees a raw export.
- One sanitized sample export per vendor for the measurement record that a vendor preset needs.

For capability 2:

- The time each cutover starts, and the watch window they want.
- Target-firewall exports for the watch window (or the period after it), and any capture they take at cutover.
- The state-handling setting of each target (out-of-state handling, asymmetric bypass), until neXus reads it.

For capability 3:

- A dedicated read-only APIC account (security domain `all`, role `read-all`, read privilege), the APIC addresses
  and the CA chain; or a local user with a certificate if the signature option is chosen.
- The APIC version, and whether endpoint security groups, attribute-based EPGs, vzAny contracts, preferred groups,
  multi-site orchestration or copy services are in use.
- Their list of which L4-L7 device is which firewall (used only to corroborate the address match).
- Confirmation of the PBR hashing and threshold settings they configured, to check against the read.

Validation help: the phase 1 experience (masked): which rules had to be added and which flows broke, so step 1 can
be backtested against what really happened.

## 10. Open questions for the PO

| # | Question | Recommendation |
| --- | --- | --- |
| Q1 | Start with step 1 (impact analysis on uploaded exports and stored policies, no new reads), on one phase-2 segment? | Yes |
| Q2 | Is a segment its own record (one cutover wave), separate from zone-matrix zones, with zones as an optional import source? | Yes, separate |
| Q3 | For Palo Alto targets, the network team types the zone pair of the redirected interfaces (until the APIC overlay can derive it)? | Yes |
| Q4 | Can the team export old-path traffic logs per segment, and which vendor's sanitized sample comes first for the preset measurement record? | Ask the team; Palo Alto or Check Point, whichever is first |
| Q5 | For the first hours after a cutover, is upload-based evidence (2a) enough, or do you want counter reads on the target (2b, new literals after measurement)? | 2a first; decide 2b after one real cutover |
| Q6 | APIC: (a) approve the read list R0-R13 in principle, so the MIM check and measurement can start (the exact literals come back for approval at gate time); (b) password or certificate (signature) login; (c) may aiview start APIC read runs, as for other CLASS_0 reads? | (a) yes in principle; (b) password first, certificate as hardening; (c) yes, masked |
| Q7 | Retention for assessments, flows, watch records and APIC data | 90 days for flows and watches; verdicts and APIC data kept with their segment or fabric |
| Q8 | Are Cisco ASA or FortiGate firewalls redirect targets in phase 2 or 3? If yes, capability 1 cannot evaluate them; a policy model for them is a separate, large build | Answer decides whether to plan that build |
| Q9 | Mark Trace results for addresses in a redirected segment "fabric redirect not modelled" now, before the APIC overlay exists? | Yes (small change in step 2a) |
| Q10 | Is a vendor-semantics freeze for ACI PBR (hash symmetry, down actions, connectors) acceptable as the gate before the Trace overlay, as A0 was for path analysis? | Yes |

## 11. Acceptance criteria

For the contract that will follow this design (numbering provisional):

- `ACI-1` No verdict, state, export or report text says "safe", "ready", "pass", "healthy" or "symmetric", or shows a
  green state; the best readiness verdict is printed with a non-empty coverage list.
- `ACI-2` The upper-bound pass never produces `WOULD_ALLOW` or `WOULD_DROP`; it only names a rule and dropped
  constraint kinds on a `NOT_DECIDED` flow.
- `ACI-3` A Cisco ASA or FortiGate target gives `NOT_EVALUABLE (VENDOR_POLICY_NOT_COLLECTED)` for every tuple.
- `ACI-4` A Palo Alto target without a zone pair gives `NOT_DECIDED (zone or target required)` for every tuple; no
  zone is inferred from routing for a redirected segment.
- `ACI-5` Stored assessments contain no raw row, header, mapping or file content (CHECK constraint and a test); APIC
  storage contains no raw response.
- `ACI-6` aiview and masked roles see masked addresses, device pseudonyms and APIC pseudonyms only (test per surface).
- `ACI-7` Over the tuple, page or size limits the run stops with a named reason; nothing is truncated and used.
- `ACI-8` A verdict becomes `STALE` when the target snapshot, the segment or the scope changes.
- `ACI-9` The APIC job can issue only the approved literal set (architecture test pins it); no method other than
  `GET` apart from login and logout.
- `ACI-10` A failed, partial or inconsistent APIC read never replaces earlier data; affected products are `STALE`.
- `ACI-11` A bridge domain shared by several EPGs, and a block present in two VRFs, are never placed in a zone
  automatically.
- `ACI-12` APIC-derived zone suggestions never publish; the zone matrix four-eyes rule applies unchanged.
- `ACI-13` A one-direction redirect gives `ASYMMETRIC` and never a clean `ALLOWED`; `PBR_HASH_SYMMETRY_UNVERIFIED`
  and the down-action caveats keep a redirected verdict from being clean until their semantics are frozen.
- `ACI-14` A PBR destination held by zero or several enrolled units is `MISSING` or `AMBIGUOUS`; no binding by name.
- `ACI-15` The watch view labels device-wide counters "not attributable to this segment" and shows "no evidence
  either way" when the target's state setting is `PASSES_SILENTLY` or `UNKNOWN`.
- `ACI-16` Every screen action has a CLI equivalent (PO P1 rule).

## Cross-references

- `docs/design/RULE_OBJECT_USAGE_CONTRACT.md`
- `docs/design/PATH_ANALYSIS_DESIGN.md`
- `docs/design/PATH_ANALYSIS_VENDOR_SEMANTICS.md`
- `docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md`
- `docs/design/DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md`
- `ZONE_MATRIX_CONTRACT.md` and `PO_DECISION_RECORD_2026_11_13_ZONE_MATRIX.md` on branch `docs/zone-matrix-contract-1113`
  (not on `main` yet)
- Legacy upper-bound match: branch `feat/hygiene-legacy-resolution-1113`
