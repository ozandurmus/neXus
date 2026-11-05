# Path analysis — Phase B architecture

Status: DRAFT — 2026-11-05. Not implementation authority (AGENTS.md, contract-status law). Every load-bearing
semantic below that is marked UNKNOWN stays UNKNOWN until a vendor document, an A6-style real-environment
corroboration or an explicit PO decision closes it. No device command is proposed for approval by this document;
it only names where a command-gate entry would be needed. Written without access to the live system or any device.

Inputs (read for this draft):
- `PATH_ANALYSIS_DESIGN.md` (FROZEN 2026-11-04) — Phase A design, cited as "design §n".
- `PATH_ANALYSIS_VENDOR_SEMANTICS.md` (FROZEN 2026-11-04) — A0 record, cited as "A0 §n".
- `PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md` (RATIFIED rows 1-5) and `PO_DECISION_RECORD_2026_11_03_PATH_ANALYSIS_PHASE_A.md`
  (D-1 to D-9, RATIFIED).
- `PATH_ANALYSIS_DESIGN_FABLE.md` F§1.4 / F§4.6 (reserved B node kinds), `REPORTS_R1_CONTRACT.md` (FROZEN).
- The engine: `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/pathanalysis/**`
  (`engine/PathEngine.java`, `engine/PathQuery.java`, `engine/RouteLookup.java`, `query/PathQueryService.java`,
  `query/PathPolicyBinder.java`, `validation/PathValidationPlanner.java`), the rule engine
  `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/HopPolicyEvaluator.java` and
  `HopNatEvaluator.java`, the controller `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/PathAnalysisController.java`
  and the screen `ui2/frontend/src/screens/PathAnalysisScreen.tsx`.

Sibling drafts written the same day on separate branches, which this document does not duplicate and defers to:
- `PATH_ANALYSIS_A6_UDP_PROPOSAL.md` — exact UDP (and possibly ICMP) literals for the A6 matchers.
- `LOAD_BALANCER_ARCHITECTURE_2026_11_05.md` — F5 / Citrix collection, evidence model and LB phasing (L1-L3).
- `PATH_UNKNOWN_REDUCTION_PLAN_2026_11_05.md` — reduction of `EXITS_KNOWN_TOPOLOGY`, `NO_ROUTE`, `AMBIGUOUS_INGRESS`,
  `UNKNOWN_HOP` on the live estate. Items B2 and B4 below touch the same code; whichever lands first, the other rebases.

All examples use synthetic values only (`FW-TANGO-04`, `CLS-ROMEO-01`, `192.0.2.x`, `198.51.100.x`).

## Authority contradiction to resolve first

The brief for this document says "see D-9: F5 and Citrix", and the sibling LB brief states a PO decision
"D-9 (2026-11-05): the estate has BOTH F5 BIG-IP and Citrix ADC". The repository record
`PO_DECISION_RECORD_2026_11_03_PATH_ANALYSIS_PHASE_A.md` still says D-9 is "open, PO input when Phase B is planned",
and no 2026-11-05 decision record exists on `cloud-base/1105`. Per the authority hierarchy this draft does not
reconcile the two: it plans for both vendors as the brief says, and item B5 stays blocked until the D-9 decision is
written to a repository record (a short amendment to the 2026-11-03 record, or a new record).

## 0. What Phase A already provides (corrections to the brief)

Read in code, not assumed:

| Brief item | Already in Phase A | Evidence |
|---|---|---|
| UDP queries | The engine, rule engine and NAT evaluator accept `udp`; the screen offers TCP/UDP. Only A6 validation refuses UDP (`PROTOCOL_NOT_APPROVED`) | `PathQuery.PROTOCOLS`; `HopPolicyEvaluator.Tuple.service()`; `PathValidationPlanner.java:180-184`; `PathAnalysisScreen.tsx:123` |
| ICMP queries | Not supported: `PathQuery` refuses any protocol but tcp/udp; `Tuple.service()` is `null` for other protocols and `HopNatEvaluator` then answers `NO_MATCH` for service-bound rules | `PathQuery.java`; `HopPolicyEvaluator.java:91`; `HopNatEvaluator.java:193` |
| Return-path symmetry | A return walk by routing only, undoing NAT at the units that applied it; stateful-unit **set** comparison → `SYMMETRIC` / `ASYMMETRIC` / `INCOMPLETE`; asymmetric → `UNKNOWN` + `OUT_OF_STATE_DROP_LIKELY`; `DENIED` reserved for collected out-of-state evidence | `PathEngine.returnPath`, `walkBack`, `returnBranch` |
| Chained NAT | The translated tuple of one unit is the next unit's input (`choice.hop().packetOut()`), loop key includes the tuple. Limited to single-address / single-port translations | `PathEngine.explore`; `HopNatEvaluator` class comment |
| VRF / VSX context | Nodes are `(unit, context, routing table)`; PAN `next-vr` resolves to the same unit's other VR by exact name; CP VSX VS hops are `POLICY_CONTEXT_UNPROVEN`; VSX warp links are cloud segments | `TopologyBuilder.java:55`; `PathEngine.nextVirtualRouter`; `PathPolicyBinder` |
| Ingress hint | Free-text node id + interface name; aiview interface resolved through the masked reverse map | `PathQuery.IngressHint`; `PathAnalysisScreen.tsx:130-133` |
| LB hops | Node kind `lb_virtual_server` reserved, not implemented | `TopologyGraph.NodeKind` comment; F§1.4 |
| Rate limit | 30 queries/min per actor, in-memory; shared `PolicyBulkhead` | `PathAnalysisController.RATE_PER_MINUTE` |
| Caching | Policy parse cache; prepared rule views (16 entries); graph held in memory; linear route scan per lookup | `PathPolicyBinder.PREPARED_KEPT`; `RouteLookup.lookup` |

Phase B is therefore mostly "widen the evaluable space without inventing certainty", plus two new capabilities
(what-if, batch) and one new vendor family (LB).

## 1. Cross-cutting rules for every Phase B item

- The Phase A verdict lattice (design §5) is unchanged: UNKNOWN taints and continues; `DENIED` only when every hop up
  to the block is decided; asymmetric is never clean `ALLOWED`; caveats make a verdict non-plain.
- D-1/D-2 stand unless a PO decision below changes them: graph in memory, no path result storage, one masked
  `authz_decisions` row per query.
- D-3/D-8 stand: aiview submits masked values only, uniform refusals, no side effects, no new aiview collection rights.
- No new device command, management API call or parser of a new command output without a network-device command
  gate entry and the PO's approval of the exact literal (D-7; AGENTS.md 2026-09-26 amendment). A parse-scope
  extension of an output already collected (same command, session, timeout, frequency) needs no new gate entry.
- Every new address- or name-bearing persisted column is registered in `audit_redaction_policy` (design §3).
- New result reason codes go into `PathReasons` (codes only, no values) and the frontend word lists
  (`pathWords.ts`), with a render-harness fixture under `tests/fixtures/uitest/` when the payload shape changes.

## 2. Items

Each item: today, semantics (cited or UNKNOWN), data (exists / new), command gate, design, effort
(engineer-days, excluding PO time), risk.

### B1. UDP and ICMP queries

**Today.** UDP is evaluated end to end except A6 validation. ICMP is refused at the query.

**Semantics.**
- UDP rule and NAT matching: the same service-port matching as TCP; already implemented, no new semantics.
- UDP state: both vendors keep UDP sessions, but the timeout and out-of-state behaviour for UDP are not in the A0
  record. UNKNOWN. Consequence: B2's asymmetry rule applies to UDP unchanged (asymmetric UDP is never clean
  `ALLOWED`), and no `DENIED` for UDP asymmetry is ever claimed.
- PAN `application-default` with UDP: D-5 applies unchanged (default ports, "App-ID assumed" caveat).
- ICMP rule matching, Check Point: ICMP services are typed objects (type/code) in the Management API object model.
  The exact field names in the collected objects-dictionary are UNKNOWN until checked against a masked repository
  fixture; until then an ICMP service object that the normaliser cannot resolve makes the rule `UNKNOWN`, never
  "no match".
- ICMP rule matching, Palo Alto: ICMP is identified by App-ID applications (for example `ping`, `icmp`), not by a
  service port. Whether a rule whose application is `ping` matches the first echo request before App-ID has
  identified the application is UNKNOWN (A0 §6 has the same gap for `application-default`). Any rule that needs
  App-ID to match ICMP → hop caveat `APP_ID_ASSUMED`, never plain `ALLOWED` (D-5 pattern).
- ICMP and NAT: static address translation is protocol-independent by its definition in A0 §1a / §4b (address
  rewrite); hide/dynamic-ip-and-port translation of ICMP uses an identifier rather than a port — UNKNOWN, so a
  hide/DIPP rule on an ICMP flow is `NAT_TRANSLATION_NOT_EXPRESSIBLE` (existing code) unless the source address
  alone is single-valued.
- ICMP state on Check Point: Global Properties has a "Drop out of state ICMP packets" option (CP-OOS, R76SP.50 —
  an old Scalable Platform guide; no current R8x page found → LOW). This shows stateful ICMP handling exists; the
  option's value per gateway is not collected → UNKNOWN.
- F5: SNATs forward only TCP and UDP by default; other IP traffic such as ICMP is dropped unless "SNAT packet
  forwarding" is changed (F5-LTM-SNAT). Relevant to B5: an ICMP flow through an LB hop with SNAT is `UNKNOWN` unless
  that setting is collected.
- Echo reply type mapping (8 → 0) is RFC 792 protocol fact, not vendor semantics.

**Data.** Exists: policy snapshots (service objects are already in the collected rulebases), routes, NAT. New: none
for evaluation; ICMP service object normalisation is a parse-scope extension of collected policy.

**Command gate.** None for evaluation. A6 validation of UDP: the sibling `PATH_ANALYSIS_A6_UDP_PROPOSAL.md` (PO
approval of the exact `<protocol>17</protocol>` literals). A6 validation of ICMP: whether PAN
`test security-policy-match` takes an ICMP type/code parameter is UNKNOWN from the documentation found (PA-TPM shows
a numeric `protocol` and `destination-port` only); a separate literal and PO approval would be needed. Until then
ICMP hops are `NOT_EVALUABLE` in A6.

**Design.**
- `PathQuery`: protocol `icmp`; `destinationPort` replaced by a sealed `L4` (`Port(int)` for tcp/udp,
  `Icmp(type, Optional<code>)`); default `Icmp(8, empty)` (echo request). API field `icmp_type` / `icmp_code`; `port`
  forbidden with `icmp`. `Tuple` gains the same `L4`; `Tuple.service()` returns `icmp/<type>[/<code>]`.
- `PolicyNormalization` / `PolicyLookup`: a service selector family `icmp`; rules with service `any` match; ICMP
  objects resolved to (type, code) ranges; unresolved → three-valued `UNKNOWN`.
- Return walk for ICMP: echo-reply tuple; asymmetry rule unchanged.
- Loop key in `explore` already includes the tuple; it must include the L4 discriminator.
- UI: protocol select gains ICMP; port field becomes ICMP type (default echo) when ICMP is chosen.

**Effort.** UDP (A6 integration once the sibling literal is approved, tests, UI copy): 2. ICMP: 6.

**Risk.** Medium. The main failure mode is a silent false `ALLOWED` when an ICMP service object is misread as `any`;
mitigated by fail-closed normalisation and golden tests with masked CP/PAN ICMP fixtures.

### B2. Return-path symmetry check

**Today.** See §0. Three weaknesses found in code:
1. **Every unit on the delivered segment starts a return branch** (`returnPath` loops over all attachments of the
   delivered segment). The destination host uses one gateway, which neXus does not know. One candidate gateway that
   is not on the forward path makes the whole return `ASYMMETRIC`, even when the host's real gateway is the forward
   unit. This is a false-asymmetry source on every multi-firewall segment.
2. **Set comparison only.** A return that re-enters the same unit through a different interface (PAN: a different
   zone; CP: a different interface for anti-spoofing) counts as symmetric.
3. **Ports are not reversed** and the return tuple carries no L4 part, so a port translation on the forward path is
   not undone.

**Semantics.**
- Palo Alto: the TCP setting "Asymmetric Path" globally drops or bypasses packets with out-of-sync ACKs or
  out-of-window sequence numbers, and can be overridden per Zone Protection profile under TCP Drop (PA-TCPS,
  PA-ZPTD, PAN-OS 11.1; HIGH for existence and meaning). Whether a bypassed out-of-sync packet is then forwarded by
  security policy, and the default value, are not stated on those pages → UNKNOWN. The XML element names in the
  running configuration are UNKNOWN from docs (repository evidence only).
- Check Point: Global Properties → Stateful Inspection → "Drop out of state TCP packets" / "Drop out of state ICMP
  packets", with per-gateway exceptions (CP-OOS; LOW, old guide). Current-version semantics and default values:
  UNKNOWN. Whether Management API v2.x exposes these settings: UNKNOWN (no read exists in the repository; no
  `global-properties` read was found in source).
- ClusterXL state synchronisation makes a return through the other member of the same cluster part of the same
  stateful unit — already modelled (`stateKey` = cluster unit). PAN active/active session-owner semantics: UNKNOWN →
  an active/active PAN pair stays `ACTIVE_MEMBER_UNPROVEN` (existing flag).
- Whether a PAN return arriving in a different zone of the same vsys matches the existing session is UNKNOWN from
  the A0 record; this draft does not claim it. An interface/zone difference is reported as a new caveat
  `RETURN_INTERFACE_DIFFERS` (information, not a verdict change) until documented.

**Data.** Exists: routes, attachments, NAT per hop, cluster identity. PAN Asymmetric Path: the configuration plane
already reads PAN `effective-running` (`ui2/worker/.../configuration/ConfigurationReadPlan.java`); if that output
contains the TCP settings and zone-protection profiles, reading them is a parse-scope extension — to be verified in
source and with a masked fixture before anything relies on it. CP out-of-state settings: not collected; new read.

**Command gate.** PAN: none if the parse-scope check holds, otherwise a gate entry. CP: a new Management API read
(endpoint name UNKNOWN) → gate entry and PO approval of the exact call. Without them B2 never produces `DENIED`.

**Design.**
- **Return gateway candidates.** The return starts from the units on the delivered segment as today, but each start
  is a *candidate* return gateway. New result field `return_path.gateway_candidates` and an optional query field
  `return_gateway_node_id` (a hint, same masking and refusal rules as the ingress hint, labelled `GIVEN`).
  Overall return status: with a hint, the hinted candidate's walk decides; without one, `ASYMMETRIC` only when *every*
  candidate's walk is asymmetric, `SYMMETRIC` only when every candidate's walk is symmetric, otherwise `INCOMPLETE`
  with reason `RETURN_GATEWAY_AMBIGUOUS`. This narrows false asymmetry without inventing symmetry: an ambiguous
  return still blocks a plain `ALLOWED` (it is `INCOMPLETE` → `RETURN_PATH_UNPROVEN` caveat). Changes a FROZEN
  sentence of design §5 ("If the return crosses ... the result is ASYMMETRIC") → needs a freeze amendment (P-B5).
- **Interface-level check.** For each forward unit the return crosses, compare return ingress/egress with forward
  egress/ingress; difference → caveat `RETURN_INTERFACE_DIFFERS`.
- **Port reversal.** The return tuple gets the L4 part (B1) and undoes destination-port translation at the units
  that translated it.
- **Out-of-state `DENIED`.** Only when (a) the asymmetry is proven (no tainted return hop, hinted or unique gateway),
  (b) the crossing unit's out-of-state drop setting is collected, fresh and says drop, for TCP (or ICMP on CP). Then
  `DENIED` with reason `OUT_OF_STATE_DROP_COLLECTED`. Otherwise today's `UNKNOWN` + `OUT_OF_STATE_DROP_LIKELY`.
- UDP: never `DENIED` for asymmetry.

**Effort.** Gateway candidates + hint + interface check + port reversal: 5. Out-of-state `DENIED`: 4 after the gate
decisions (PAN parse-scope verification 1, CP read 3).

**Risk.** Medium. A wrong "drop" reading would produce a false `DENIED`; guarded by the double condition above and
golden tests. The candidate rule must be frozen before code (P-B5).

### B3. Chained NAT across multiple firewalls

**Today.** Chaining works for single-address translations. Translations to a pool or range, PAN
`dynamic-ip-and-port` without a single interface address, PAN dynamic destination translation, CP hide NAT source
port, and PAN bi-directional reverse matches are `UNKNOWN` (`NAT_TRANSLATION_NOT_EXPRESSIBLE` and siblings). On
Check Point each hop with a destination NAT evaluates access on both tuples (A0 §1b) and may add
`CP_ACCESS_NAT_ORDER_UNKNOWN`; route-after-NAT may add `CP_ROUTE_AFTER_NAT_UNKNOWN` (A0 §1c). A two-gateway chain
can compound both.

**Semantics.**
- Per-unit NAT order and first match: A0 §1a, §4a-4c (HIGH); no new vendor claim is needed for chaining itself — the
  next unit sees the packet the previous one emitted, which is how both vendors' documentation describes egress
  translation (PA-NAT: translation "do[es] not occur until the packet leaves the firewall").
- Which address of a pool / range a hide or DIPP translation picks: vendor-internal and not documented as
  deterministic → UNKNOWN per packet. What *is* known is that it is one of the pool's addresses.
- CP "Translate destination on client side" (A0 §1c): still UNKNOWN (default value and effect on routing). Reading
  the option (same Global Properties question as B2) would not by itself close the semantics; a vendor document is
  still required.

**Data.** Exists: all NAT rulebases, objects, pools/ranges as policy objects. New: none (CP Global Properties only if
B2's CP read is approved and documents the option).

**Command gate.** None.

**Design.**
- **Address-set tuples.** `Tuple.source` (and destination for PAN dynamic destination translation, if ever
  expressible) may be a bounded `AddressSet` (≤ 16 addresses, or one prefix whose members all fall in one route of
  every unit consulted). Downstream: policy is evaluated per member through the existing three-valued engine; equal
  verdicts across the set → decided; different → hop `UNKNOWN (NAT_POOL_VERDICT_VARIES)`. Forward routing is
  unaffected (destination-based); PBR remains a caveat. The return walk routes toward each set member, bounded by the
  work budget; a set whose members leave by different routes → `INCOMPLETE`.
- **NAT chain in the result.** `branch.nat_chain`: ordered list of (hop index, field, before, after) built from the
  hops already carried; masked by the existing response masking. UI: a "NAT chain" strip in `PathResultView`.
- **Compounding UNKNOWNs.** No change in semantics; the result names the first hop that introduced the NAT-related
  UNKNOWN so the PO sees which gateway to corroborate in A6.
- Overlapping private prefixes after translation stay never auto-joined (design §4); only overrides join them.

**Effort.** 7.

**Risk.** Medium. Combinatorial growth is bounded by the set cap and the 4,000 work budget; a capped set → `UNKNOWN`
(never sampled).

### B4. VRF / VSX context selection with an ingress hint UX

**Today.** The ingress hint is a free-text opaque node id plus interface name. `AMBIGUOUS_INGRESS` (two of nine live
test queries per the sibling path-unknown brief) returns candidates but the screen gives no way to pick one. PAN
inter-VR routing is resolved by exact VR name. CP VSX: every VS hop is `POLICY_CONTEXT_UNPROVEN`.

**Semantics.**
- Contexts: PAN vsys / virtual router / logical router (A0 §5a, HIGH; VR and LR equivalent for routing context).
  CP VSX: VS identity = physical endpoint + VSID (AGENTS.md, Check Point), `vsenv <VSID>` validated.
- **CP VS ↔ policy target.** No proven relation between a VSID and the virtual-system reference in a policy target
  (design §5 amendment A5a). A name join (VS name ↔ object name) is forbidden (presentation identity ≠ security
  identity). UNKNOWN; this is the largest blocker for VSX estates and needs its own research step (B0) and, if a
  device or management read is needed, a gate entry. Candidates to research, none proposed for approval here: a
  management-side object that carries the VSID as a typed field; a per-VS installed-policy read already corroborating
  `policy_target_binding` (design §3 names `cpstat -f policy fw`). Each must be shown to give an opaque-identifier
  join, not a name join.
- VSX warp links: stay cloud segments until a measured fixture proves the pairing (design §4); unchanged.

**Data.** Exists: contexts, interfaces per context, `routing_context`, zones, segments, the masked reverse map.
New: only the VSX binding proof (B0 outcome).

**Command gate.** None for the UX. VSX binding: depends on B0; possibly one new read.

**Design.**
- `GET /api/v2/path-analysis/ingress-candidates?src=<address>` (action `path_analysis_read`): candidates from the held
  graph — node id, context label (vsys / VR / VSID), interface, segment, and why it is a candidate (segment contains
  the source / most specific route toward the source into a cloud). Masked for aiview by the existing response
  advice; aiview submits a masked address, resolved by the same reverse map with the same uniform refusal. This is
  the D-3 fallback "picking from masked lists" made first-class. Rate-limited with the query (counts as one query).
- Screen: ingress unit/interface become selects fed by that endpoint; an `AMBIGUOUS_INGRESS` result shows each
  candidate with a "trace from here" action that re-submits with the hint. The same control serves the B2
  `return_gateway_node_id` hint.
- Every result shows the context of every hop (already in the node id; add a display label).
- CP VSX policy binding: implement only after B0 freezes a proven join; until then `POLICY_CONTEXT_UNPROVEN` stays.

**Effort.** Endpoint + UX + render harness: 5. B0 VSX binding research: 3 (+ gate if a read is needed, + 3 to
implement).

**Risk.** Low for UX. High for VSX binding if done wrongly (identity law); that is why it is research-first.

### B5. Load-balancer hops (D-9: F5 BIG-IP and Citrix ADC)

**Today.** Node kind reserved (`lb_virtual_server`, F§1.4/F§4.6). No LB data is collected. Collection, evidence model
and credential boundary belong to the sibling `LOAD_BALANCER_ARCHITECTURE_2026_11_05.md` (its L1 inventory + backup,
L2 configuration). This item is only the path-engine hop (its L3).

**Semantics.**
- F5 virtual server → pool: member selection by load-balancing method, persistence and iRules is runtime/per
  connection → which member: UNKNOWN per flow. Known: the set of pool members. SNAT: none, Automap (translation
  address chosen from self IPs, floating self IPs on the egress VLAN preferred), or a SNAT pool (F5-K7820,
  F5-LTM-SNAT; 11.x guides, MEDIUM for current versions). Default Automap applies to all connections of the virtual
  server (F5-LTM-SNAT). A pool's "Allow SNAT" setting can prevent translation even when the virtual server enables it
  (F5-K13590401). iRules can override the virtual server's SNAT with `snat none|automap|<addr>` (F5-IRULE-SNAT).
  ICMP is dropped by SNAT by default (B1).
- Citrix ADC vserver → service / service group: USIP sends the client address to the server; set globally or per
  service, a global change not altering existing services (CTX-USIP, current release, HIGH). Without USIP the ADC
  uses an ADC-owned address (SNIP when USNIP is enabled) (CTX-TOPO, HIGH for the option, UNKNOWN which SNIP is
  chosen when several are eligible). With USIP the server's gateway must be an ADC SNIP for responses to return
  through the ADC (CTX-USIP) — a return-path condition B2 can check. DSR mode sends responses directly
  (CTX-DSR) — a designed asymmetry.
- Persistence, content switching, responder/rewrite policies, iRules: decide per request → `UNKNOWN` for anything
  they could change (member choice, translation, drop).
- Member health is runtime evidence; a `down` member is skipped only when its monitor state is collected and fresh,
  otherwise it stays a branch with caveat `LB_MEMBER_STATE_UNKNOWN`.

**Data.** New, all of it, via the sibling L1/L2: virtual servers (address, port, protocol), pools/service groups and
members (address, port, runtime state), SNAT mode/pool, self IPs / SNIPs and VLANs, USIP per service, DSR flag,
iRule/policy presence (presence boolean only; iRule bodies are never parsed into path semantics in B).
`lb_*` tables per F§1.4; every address column registered for tier-2 audit redaction.

**Command gate.** Every F5 iControl REST / tmsh and Citrix NITRO read is a new command: gate entry and PO approval
of each exact literal (sibling doc proposes them). New credential boundary → security review (diagnostic-path law
does not apply; there is no existing path).

**Design.**
- Topology: an LB unit is a `CLUSTER_UNIT`-like stateful unit with routing contexts (route domains / partitions on F5
  and traffic domains on Citrix map to contexts — mapping UNKNOWN until L1 measures them); each VIP is a `/32`
  attachment on the LB node.
- `HopSemantics` strategy `lb_virtual_server`: when the current destination and L4 match a VIP, the hop reports
  `LB_TRANSLATION` and branches once per pool member (bounded by `ecmpPerHop`/`maxBranches`; truncation →
  `ecmpTruncated`, counted UNKNOWN). Destination → member address/port; source → per SNAT mode (none / single
  address / B3 `AddressSet` for Automap with several eligible self IPs or a SNAT pool). iRule or policy present on the
  virtual server → the hop is `CONDITIONAL`-tainting `UNKNOWN (LB_PROGRAMMABLE_LOGIC)`; persistence alone does not
  taint (every member is already a branch).
- Overall: all member branches `ALLOWED` → `ALLOWED`; mixed → `PARTIAL` (existing lattice).
- Return: with SNAT the return targets the LB address and must cross the LB (B2 applies). Without SNAT / with USIP,
  B2's candidate check applies with the LB as a stateful unit; DSR flag collected → the LB is excluded from the
  return expectation and the hop carries caveat `LB_DSR`.
- No LB management-plane validation in A6 terms is proposed here (no LB matcher command is known); LB hops stay
  `NOT_EVALUABLE` in path validation until a gated read exists.

**Effort.** Engine + topology + UI for LB hops: 8, after LB L1/L2 (sibling estimate) and the D-9 record.

**Risk.** High: new vendor family, new credential boundary, new persisted sensitive addresses, programmable data
planes. Order: last.

### B6. What-if rule changes (offline, never pushed)

**Today.** None.

**Semantics.** No new vendor semantics: a what-if evaluation is the existing evaluator over a modified copy of a
stored snapshot. Placement semantics are those already frozen — CP ordered/inline layers and sections (A0 §2,
section order caveats included), PAN pre/local/post (A0 §4c), NAT first match (A0 §1a, §4b). A delta placed where
the A0 record leaves order UNKNOWN (for example relative to sections whose order is unproven) is `UNKNOWN`, exactly as
a real rule there would be.

**Data.** Exists: snapshots, objects, routes. New: none persisted (see design).

**Command gate.** None, and must stay none: the feature has no write path, no export to a device format, no
`ActionClass` write, no job type. A test in `architecture-tests` asserts the what-if package imports no transport,
job-engine or command type.

**Design.**
- Request = a normal query plus a bounded `delta` (≤ 20 operations): `add_rule(position, fields)`,
  `disable_rule(rule_ref)`, `modify_rule(rule_ref, fields)`, `add_nat_rule(...)`, `add_route(node, ...)`,
  `remove_route(node, route_ref)`. Fields reference existing objects of the same snapshot by opaque id, or literal
  addresses/ports. No new named objects.
- Evaluation over a copy-on-write overlay of the prepared rule view; the cached `PolicySnapshot` / `Prepared` are
  never mutated (they are shared through the policy parse cache).
- Response: the actual result and the what-if result side by side, per-branch diff, labelled `HYPOTHETICAL` on every
  hop; the what-if verdict is never written to `authz_decisions` as a verdict — the audit row records the masked
  query, a masked digest of the delta and both verdict codes, marked `what_if: true`.
- Not stored (D-2). A saved draft is out of scope for B.
- RBAC: new action `path_whatif` (CLASS_0) for roles holding `policy_read`; aiview excluded in B (P-B4).

**Effort.** 9.

**Risk.** Medium: a hypothetical `ALLOWED` read as an approval. Mitigated by labelling and by having no path to the
device. Low technical risk.

### B7. Path results as evidence in reports

**Today.** D-2: path results are not stored; result history is Phase D. `REPORTS_R1_CONTRACT.md` (FROZEN) has no path
report; R2/R3 are later. A6 validation outcomes are stored (`path_validation`, `path_validation_hop`, V188).

**Semantics.** None vendor-side. Evidence semantics: a recomputed path verdict is "verdict over stored inventory and
policy as of their collection times", never live truth (AGENTS.md evidence laws); A6 `MATCH` rows are the only
device-corroborated evidence.

**Data.** Exists: topology, policies, `path_validation*`. Missing: *which* flows a report should cover.

**Command gate.** None (reports read only the database, per the R1 contract).

**Design.**
- Report generation recomputes the verdict of each listed flow against the topology held at generation time; the
  report records the topology snapshot id and the per-hop policy collection times as its "data as of", per the R1
  data-and-honesty rules. No result row is stored outside the report artefact itself (the artefact store already
  retains reports for 30 days — that is report storage, governed by the R1 contract, not path-result storage).
- A6 outcomes from `path_validation*` are shown next to the recomputed verdict, with their own age.
- The flow list needs a persisted "flow set" (queries, not results): owner, name, up to N masked-at-rest tuples.
  That is new storage of addresses and must be decided against D-2 (P-B3).
- Masking: the report's single masking pass (R1) must reproduce `PathResultView` masking; rule identity withheld
  without `policy_read`; aiview always masked.
- UNKNOWN is its own reported category; the reason histogram (`EXITS_KNOWN_TOPOLOGY`, `NO_ROUTE`, …) is shown.
- Lands with Reports R2 (new template id under the R2 contract), not as an R1 change.

**Effort.** 6 after the R2 framework.

**Risk.** Low-medium: the report must not become a de-facto result history (keep generation-time recomputation;
no separate result table).

### B8. Batch queries (CSV of flows) with rate limits

**Today.** One query per request, 30/min per actor, in-memory limiter, shared `PolicyBulkhead`.

**Semantics.** None vendor-side.

**Data.** Exists.

**Command gate.** None.

**Design.**
- `POST /api/v2/path-analysis/batch` with a CSV body: header `src,dst,protocol,port,icmp_type,ingress_node_id,edge_mode`;
  ≤ 200 rows (proposed); strict parser (no quoted multiline fields, no formulas accepted as values); the whole batch
  refused on a malformed header, row-level `INVALID_ROW` otherwise.
- Synchronous and bounded: one held graph and one `PathPolicyBinder.forQuery` per batch (consistent snapshot, catalog
  read once), rows evaluated sequentially under one bulkhead permit, 30 s deadline; rows past the deadline are
  `NOT_EVALUATED` (never dropped silently). No job, no stored result (D-2). Larger or asynchronous batches need
  result storage → Phase D.
- Rate limits (proposed values, PO to set): one batch at a time per actor; 5 batches/hour and 1,000 rows/day per
  actor; a batch counts as one query against the 30/min limiter so it cannot be interleaved to bypass it. The shared
  `PolicyBulkhead` gets a separate, lower-priority permit for batches so interactive policy lookups are not starved.
- aiview: masked values per row, row refusals uniform (`PSEUDONYM_REFUSED`, no row-specific code), and a lower row
  budget (proposed 200 rows/day) because a batch is otherwise a 200-guess probe of the reverse map (D-3 condition 5).
- Output: JSON (per-row verdict, reason, headline, branch count) and CSV download built from it; CSV cells starting
  with `=`, `+`, `-`, `@` are prefixed to prevent formula injection. Masking by the existing response advice.
- Audit: D-2 says one `authz_decisions` row per query. 200 rows per batch multiplies audit volume (and
  `authz_decisions` has no retention — PO record 2026-11-03 "Not decided here"). Options: one row per row (D-2 as
  written) or one row per batch with a masked row list and per-row verdict codes (P-B2).

**Effort.** 6.

**Risk.** Medium: load on the shared bulkhead and reverse-map probing; both bounded above.

### B9. Performance and caching

**Today.** Pure in-memory engine with a 4,000-visit work budget; `RouteLookup.lookup` scans every route of a unit and
re-parses each destination per lookup; ingress inference scans all segments; prepared rule views cached 16 entries;
catalog read once per query. No timing telemetry specific to path analysis was found.

**Semantics.** None.

**Data / gate.** None.

**Design.**
1. **Measure first.** Safe telemetry only (durations, unit visits, branch counts, cache hit counters; no addresses or
   names): per query and per batch. Baseline on the estate-size golden fixture.
2. **Per-snapshot indexes**, built once with the graph and immutable: a per-node LPM structure over parsed prefixes
   (keeps `RouteLookup` rules exactly: inactive excluded, NULL active taints, unparseable taints every lookup); a
   segment containment index for ingress inference. Golden tests must give byte-equal results before and after.
3. **Prepared rule views.** Size `PREPARED_KEPT` from the unit count of the held graph (bounded, e.g. ≤ 256) instead of
   16, so a long path does not evict its own earlier hops.
4. **Result cache: not proposed for B.** A shared result cache would be a cross-user warm-up visible through timing
   (D-3 condition 4) and a de-facto result store (D-2). If measurement shows a need: per-actor, in-memory, keyed by
   (graph fingerprint, policy snapshot ids and collection times, query, projection), short TTL, never for aiview.
5. **Targets (proposed, to be confirmed by measurement):** single query p95 < 300 ms on the estate fixture; a
   200-row batch < 30 s.

**Effort.** 4.

**Risk.** Low, provided golden equality is a gate.

## 3. Order, effort and dependencies

| Order | Item | Depends on | Effort (days) | Risk |
|---|---|---|---|---|
| 0 | B0: vendor research for B1 ICMP objects, B2 out-of-state settings, B4 VSX binding; record the D-9 decision | — | 3 + PO | — |
| 1 | B9 measurement + indexes | — | 4 | Low |
| 2 | B1 UDP validation | sibling UDP literal approved | 2 | Low |
| 3 | B4 ingress picker UX | coordinate with path-unknown plan | 5 | Low |
| 4 | B2 return gateway candidates, interface check, port reversal | P-B5 freeze | 5 | Medium |
| 5 | B3 chained NAT address sets + NAT chain | B9 (budget headroom) | 7 | Medium |
| 6 | B1 ICMP | B0 | 6 | Medium |
| 7 | B8 batch | B9, P-B2 | 6 | Medium |
| 8 | B7 reports | Reports R2, P-B3 | 6 | Low-medium |
| 9 | B6 what-if | P-B4 | 9 | Medium |
| 10 | B2 out-of-state `DENIED` | gate decisions (PAN parse scope, CP read) | 4 | Medium |
| 11 | B4 VSX policy binding | B0 proof (+ gate) | 3 | High |
| 12 | B5 LB hops | D-9 record, LB L1/L2, B3 | 8 | High |
| — | Real-environment validation (A6-style) of each network-facing item | PO samples | 3 + PO | — |

Total ≈ 71 engineer-days excluding LB collection (sibling estimate) and PO time. Status of every network-facing item
stays `AUTOMATED_VALIDATED` until its real-environment step.

## 4. Decisions needed from the PO

| # | Question | Recommendation |
|---|---|---|
| P-B1 | Record D-9 (F5 and Citrix) in a repository decision record | Amend the 2026-11-03 record |
| P-B2 | Batch audit: one `authz_decisions` row per row (D-2 as written) or one per batch with a masked row list | One per batch, with per-row verdict codes |
| P-B3 | Persisted flow sets (queries, not results) for reports | Allow, owner-scoped, masked at rest for aiview, delete with the owner |
| P-B4 | What-if: aiview access; any export of a proposed change | No aiview in B; no export in B |
| P-B5 | Freeze the return-gateway-candidate rule (B2), amending design §5 | Freeze as written in B2 |
| P-B6 | Command gate: CP out-of-state settings read; PAN ICMP matcher literal; LB reads (sibling) | Each exact literal separately, after B0 |

## 5. Not in Phase B

IPv6; source-port modelling beyond NAT reversal; PAN active/active session-owner semantics; CP gateway topology /
anti-spoofing (D-6 stays deferred); result history (Phase D); estate map (Phase D); APIC / ACI (Phase C); iRule /
AppExpert body interpretation.

## Sources

Phase A sources (CP-*, PA-*) are listed in `PATH_ANALYSIS_VENDOR_SEMANTICS.md`. New in this draft (searched
2026-11-05, read in search excerpts; pages not re-verified in full — every claim drawn from them is to be re-read
in B0 before freezing):

- PA-TCPS (Device > Setup > Session > TCP Settings, 11.1): https://docs.paloaltonetworks.com/ngfw/help/11-1/device/device-setup-session/tcp-settings
- PA-ZPTD (Zone Protection > Packet Based Attack Protection > TCP Drop, 11.1): https://docs.paloaltonetworks.com/ngfw/help/11-1/network/network-network-profiles/network-network-profiles-zone-protection/packet-based-attack-protection/tcp-drop
- PA-TPM (CLI quick start, Test Policy Matches, 9.1): https://docs.paloaltonetworks.com/pan-os/9-1/pan-os-cli-quick-start/use-the-cli/test-the-configuration/test-policy-matches
- CP-OOS (R76SP.50 Security System guide, port mirroring; Global Properties > Stateful Inspection out-of-state options): https://sc1.checkpoint.com/documents/R76SP.50/CP_R76SP.50_Security_System_AdminGuide/125644.htm
- F5-K7820 (Overview of SNAT features): https://my.f5.com/manage/s/article/K7820
- F5-LTM-SNAT (BIG-IP LTM Concepts 11.5.1, SNATs): https://techdocs.f5.com/kb/en-us/products/big-ip_ltm/manuals/product/ltm-concepts-11-5-1/17.html
- F5-K13590401 (pool "Allow SNAT"): https://my.f5.com/manage/s/article/K13590401
- F5-IRULE-SNAT (tmsh reference, iRule `snat`): https://clouddocs.f5.com/cli/tmsh-reference/v13/modules/ltm/ltm_rule_command_snat.html
- CTX-USIP (NetScaler, Use the source IP address of the client): https://docs.netscaler.com/en-us/citrix-adc/current-release/load-balancing/load-balancing-advanced-settings/usip-of-client.html
- CTX-TOPO (NetScaler, Common network topologies, USNIP): https://docs.netscaler.com/us/en/citrix-adc/current-release/getting-started-with-citrix-adc/network-topologies.html
- CTX-DSR (NetScaler, load balancing in DSR mode): https://docs.netscaler.com/en-us/citrix-adc/current-release/load-balancing/load-balancing-dsrmode.html
