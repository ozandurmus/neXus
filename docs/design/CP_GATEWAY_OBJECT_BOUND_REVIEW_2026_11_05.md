# Check Point gateway objects as rule addresses — can shadowing bound them? (2026-11-05)

Status: DRAFT — 2026-11-05. Vendor-semantics review only; authorizes nothing. Every UNKNOWN below stays UNKNOWN.
Input: `POLICY_UNKNOWN_REDUCTION_PLAN_2026_11_05.md` §0.1 (23 gateway-object blockers behind 18,624 later rules, live
counters only), A0 = `PATH_ANALYSIS_VENDOR_SEMANTICS.md` (FROZEN 2026-11-04, incl. the access-role amendment),
`PO_DECISION_RECORD_2026_11_03_CP_LAYER_SEMANTICS.md` (RATIFIED), the code at `origin/main` (e8337003). Official
Check Point pages read in full from this session (sc1.checkpoint.com reachable; support.checkpoint.com and
community.checkpoint.com answered 403 to page reads, so no sk article and no CheckMates thread is cited as read).
No device contacted, no customer data read.

Question. A rule whose Source or Destination names a gateway-class object (stored type `gateway`:
`CpObjectTypes.GATEWAY_TYPES` — simple-gateway, simple-cluster, checkpoint-host, cluster-member, CpmiGatewayCluster,
CpmiVsxNetobj, CpmiVsClusterNetobj, ...) is rejected by `PolicyNormalization.Resolver#object` ("dynamic or
unsupported object type"), so it is a partial prior with a null address cell. Can an upper bound of the addresses such
an object matches be read from collected data, soundly enough to prove disjointness / cannot-cover (X1, never cover)?

## 1. What address set does a gateway / cluster / member / VSX object match in Source or Destination?

**Answer: UNKNOWN.** No official page read states it. What the pages do establish:

- **Gateway objects are admissible in the columns, but their matching is not described.** R81 "The Columns of the
  Access Control Rule Base" lists the object kinds for Source/Destination ("you can add Network objects including
  groups of all types") — Network, Host, Zones, Dynamic Objects, Domain Objects, Access Roles, Updatable Objects —
  and says nothing about how a gateway, cluster or Check Point host object matches (CP-COLS). The R82 "Rule Matching
  in the Access Control Policy" page describes first-match only (CP-MATCH). Both pages were read in full.
- **A gateway object has one main address plus a list of interfaces with addresses and topology.** The management
  object shape in the R81 ClusterXL "Cluster Management APIs" sample reply: top-level `ipv4-address`, an `interfaces`
  list whose entries carry `name`, `ipv4-address`, `ipv4-mask-length`, `topology`, `anti-spoofing`, `interface-type`
  (`"interface-type": "cluster"`), and `cluster-members[]` each with `ip-address` and its own `interfaces[]` with
  member addresses (CP-CXLAPI). The main address is what SmartConsole's General Properties holds; Site-to-Site VPN
  uses "the main IPv4 address, defined in the General Properties page" by default (CP-S2S) — a VPN statement, not an
  access-rule statement.
- **Interface topology defines networks *behind* interfaces, for Anti-Spoofing and VPN domains, not for the object's
  own address.** R81 "Preventing IP Spoofing": "Network defined by the interface IP and Net Mask - Only the network
  that directly connects to this internal interface"; "Network defined by routes" lets the gateway dynamically
  calculate the topology behind the interface (CP-ASPF). Dynamic Anti-Spoofing builds "a list of valid IP addresses
  based on the IP address and netmask of the interface" (CP-NGSG). The VPN Domain default is "all IP Addresses behind
  the Security Gateway, based on the topology configuration" (CP-S2S). None of these pages says the access-rule match
  of the gateway object includes, or excludes, those networks.
- **Networks behind an interface have their own rule object (Security Zone).** "You assign a network interface of a
  Security Gateway to a Security Zone" and "use the Security Zone objects in the Source and Destination columns"
  (CP-ZONE). This shows the product offers a separate object for "the network behind interface X"; it does not state
  that the gateway object excludes those networks. Inference only (LOW).
- **NAT uses interface addresses of the gateway object.** Automatic NAT "Hide behind Gateway - Uses the IP address of
  the corresponding Security Gateway's interface" (CP-ANAT). NAT semantics, not access-rule matching.
- **SmartConsole Packet Search** matches "rules that have: The IP address in a column of the rule", "Any", or a
  negated / group-with-exclusion field containing it (CP-PKT). It does not say which addresses a gateway object
  contributes to that test.

Per claim, with the evidence grade of A0:

| Claim | Verdict | Basis |
|---|---|---|
| Matches its main (`ipv4-address`) address | UNKNOWN as a full statement; the main address being *included* is the natural reading of CP-COLS + CP-PKT but no page says it | LOW |
| Matches every interface address of the object (`interfaces[].ipv4-address`) | UNKNOWN | no page |
| Cluster object matches the cluster virtual addresses | UNKNOWN (CP-CXLAPI shows they are the cluster object's `interfaces[]`; matching not stated) | no page |
| Cluster object also matches member physical addresses (`cluster-members[].interfaces[]`) | UNKNOWN | no page |
| Member object matches only its own addresses | UNKNOWN | no page |
| VSX / VS objects (CpmiVsxNetobj, CpmiVsClusterNetobj, ...) behave like gateway / cluster objects | UNKNOWN; VSX guide not read for this | no page |
| Topology-defined networks behind interfaces are **excluded** from the object's match | UNKNOWN. The pages tie them to Anti-Spoofing, VPN domain and Security Zones only, never to the gateway object's address set | LOW inference |
| Anti-Spoofing is irrelevant to the Source/Destination match | Anti-Spoofing is an inbound-interface validity check (CP-ASPF); the pages never connect it to rule matching, so it is *irrelevant to the bound question* but that is an absence of a statement, not a statement | LOW |
| IPv6 addresses of the object | UNKNOWN (CP-CXLAPI entries carry IPv6 fields; nothing read on matching) | no page |

The Management API reference (`show-simple-gateway`, `show-simple-cluster`, `show-generic-object`) is a JavaScript
shell from this session ("Generating Documentation..."), so its field descriptions could not be read; the reply shape
is taken from CP-CXLAPI (an R80.20-era sample). sk articles: none readable (403). Community material (a thread on
"This Gateway" and one on a cluster object "including all cluster IPs") was found by search extract only, not read,
and would be non-official anyway: not cited as evidence.

## 2. What neXus already collects, with evidence, and how a gateway object ties to a device

**Policy snapshot (what shadowing sees).** `CheckPointPolicyMapper#object` (worker) stores a dictionary object as
`PolicySnapshot.PolicyObject(id, name, type, members, values, status)`; for a gateway-class native type:
`type = "gateway"`, `status = "RESOLVED"` (`CpObjectTypes.status`), `members = []`, and `values` = the
`VALUE_FIELDS` present on the node (`ipv4-address`, `ipv6-address`, `subnet4`, `mask-length4`, ... prefixed by field
name). The dictionary comes from `show-access-rulebase ... details-level full use-object-dictionary true`
(`CpPolicyGates`). Whether the dictionary entry for a gateway object carries its `interfaces` list is not proven
(no fixture read here); either way the mapper **does not read `interfaces`** — only the top-level main address
survives. `id` is `PolicySnapshot.ref(metadata.id(), "object", uid)`; the raw uid is not stored on the object.

**CP object inventory (domain join).** `CpObjectInventoryParser` ("explicit field projection; credentials, topology
and vendor blobs are never retained") keeps for `show-gateways-and-servers ... details-level full` the same scalar
fields (`ipv4-address`, `ipv6-address`, subnet/range/port fields) plus `policy/*-installed`; `interfaces` is
deliberately not retained. `PolicyDomainIndex.CP_OUTSIDE` states the product position: "VPN, gateway topology and
threat prevention references are not collected." So **the management-side interface list of a gateway object exists
nowhere in neXus today.**

**Device inventory (runtime, direct read).** `device_interface` (V13: `run_id`, `context`, `name`, `kind`, `state`)
and `device_interface_address` (`address`, `family` ipv4|ipv6, `role` member|cluster_virtual) hold, per enrolled
device and per VSX context, the addresses read directly from the gateway: `fw getifs` (`CheckPointFwGetifsParser`,
IPv4 only, interfaces with an address only; `vsenv <VSID> && fw getifs` per virtual system) and `cphaprob -a if`
(`CheckPointClusterVirtualInterfaceParser`, role `cluster_virtual`, cluster members only). This is direct-device
runtime truth, a different evidence plane from the management object the rule references (AGENTS.md evidence laws:
"Management-plane observation != direct-device runtime truth"; "Configuration intent != runtime truth").

**Identity: gateway object ↔ device, already proven, never by name.**
- Discovery: `MgmtCliEnumerationAdapter#queryGateways` keys each `show-gateways-and-servers` object by
  `CandidateKey(owningDomain, OpaqueId.of(uid))`; `discovery_candidate.stable_identifier` (V14) is that uid, and
  `DiscoveryMatchKey` = `check_point|<owning_domain>|<uid>` is what `device_discovery_match` stores for an imported
  device (`DiscoveryRunService`). A cluster member found through `cluster-member-names` carries
  `cluster_reference` = the cluster object's uid.
- Policy side: `policy_target_binding` (V185, FROZEN under PATH_ANALYSIS_DESIGN A2 / PO decision 2026-11-03 D-1)
  already joins a package's installation-target uid to the enrolled device "by the exact stored discovery key
  (`PolicyCollectionRepository.targets`: stable identifier or cluster reference)" and stores
  `install_on_ref = PolicySnapshot.ref(policy_id, 'object', target_uid)` — the same opaque reference the mapper
  stores. A rule's Source/Destination reference to a gateway object can therefore be tied to a device by **exact
  equality of `ref(policy_id, "object", uid)`** with a binding row, or by recomputing the ref from
  `discovery_candidate.stable_identifier` — no name, no address, no ordinal involved. Caveat: the binding is written
  for *installation targets* only; a gateway object named in a rule but not a target of that package has no row and
  would need the recomputation path (same proof, not yet implemented anywhere).

Summary table (what the bound in §3 would need vs. what exists):

| Address class of the object | Needed source | Collected? | Where |
|---|---|---|---|
| Main address | object `ipv4-address` | yes | snapshot `values` (`ipv4-address: ...`); inventory Item values |
| Management-defined interface addresses (gateway, cluster VIPs, member addresses) | object `interfaces[]`, `cluster-members[].interfaces[]` | **no** (not parsed, by design) | — |
| Runtime interface addresses | `fw getifs`, `cphaprob -a if` | yes, IPv4, per enrolled device/context | `device_interface_address` |
| IPv6 addresses | object `ipv6-address`, `interfaces[].ipv6-*` | main only; runtime read is IPv4-only | partial |
| Topology networks behind interfaces | `interfaces[].topology-settings` | no | — |

## 3. Is there a sound upper bound?

**No — stop at UNKNOWN.** A sound upper bound must be a set that provably contains every packet address the object
can match. Two independent gaps block it:

1. **Semantic gap (vendor).** §1 establishes no statement of what the object matches. Without a documented ceiling
   (e.g. "a gateway object stands for its interface addresses"), any candidate set — main address, main + collected
   interfaces, those + cluster VIPs — might miss addresses the gateway actually matches (members' addresses for a
   cluster object; networks behind interfaces if the object were ever expanded by topology; an interface present in
   the management object but absent at runtime). A bound that may be too small is not an upper bound; using it for
   disjointness would release later rules that the gateway rule can in fact match, i.e. a false NOT_SHADOWED.
   This differs from the 2026-11-04 access-role amendment, where the Identity Awareness guide gave the ceiling
   ("add a network restriction to the Access Role") and only a stated residual (X-Forwarded-For) remained.
2. **Data gap (repository).** Even under the most plausible unproven reading ("all interface addresses of the
   management object"), the needed source is the management object's `interfaces` list, which is not collected
   (§2). The runtime `device_interface_address` rows are a different evidence plane: they prove what the gateway has
   now, not what the installed object said when the policy was compiled; a management interface without a runtime
   address, or an interface renamed/removed since, makes the runtime set an unproven proxy. The collected main
   address is a lower bound only (useful for nothing in a disjointness proof), exactly as the plan already states.

Consequence for X1: a gateway-object cell stays unresolved; X1 can still pass over such a blocker on its *other*
resolved dimensions (service, the other address cell, zones, install-on), which needs no new semantics.

What would close each gap, in order:
- Gap 1: an official statement (admin guide, API reference field description, or an sk readable through an
  authorized channel) of the address set a gateway/cluster object denotes in Source/Destination; or A6 real-environment
  corroboration (SmartConsole Packet Search on PO-chosen masked samples: does a packet from a member address, a VIP,
  a network behind an interface hit a rule whose Destination is the cluster object?). Both are evidence the vendor
  semantics law requires before any freeze; the second alone yields MEDIUM at best.
- Gap 2: parse `interfaces[]` (and for clusters `cluster-members[].interfaces[]`) from the already-issued
  `show-gateways-and-servers details-level full` reply (`CpObjectInventoryParser`) and/or the rulebase dictionary. That
  is a parse-scope extension of a gated command, not a new command (AGENTS.md: "A parse-scope extension of a command
  already issued ... is not a command addition"), but it reverses the explicit "topology ... never retained" projection
  and adds interface addresses (tier-2 data) to the object inventory: a privacy/raw-evidence review, and a PO decision
  because it widens what the inventory stores. A `show-simple-gateway` / `show-simple-cluster` read would be a new
  command (gate).

## 4. Effect, risks, tests, gate

**Effect (qualitative, if both gaps were closed and a bound frozen).** Up to 23 blockers behind 18,624 later rules
(§0.1) would gain a resolved-as-bound address cell; with X1, each would then block only later rules whose matching
address cell is contained in the bound — in practice rules to/from the same firewall addresses. Expected: a large
share of the 18,624 released, since gateway-address rules (management access, VPN, monitoring) rarely contain
broad later rules. Unmeasured; a counter run (X0 pattern) after any implementation, never before.

**Risks.**
- Semantic: a bound smaller than the real match set produces false NOT_SHADOWED verdicts on exactly the rules that
  protect the firewall itself (management, control connections). HIGH impact if wrong; this is why no bound is
  proposed now.
- Evidence-plane mixing: using runtime interface addresses to bound a management object silently collapses two
  planes the constitution keeps apart. Not acceptable as a bound; at most a diagnostic cross-check.
- Privacy: interface addresses in the object inventory and in shadowing reasons; `PolicyPrivacy` must keep masking
  them (it already masks `ipv4-address` values; a new `interfaces/...` value key would need the same treatment).
- Staleness: a bound read from the object inventory inherits `INVENTORY_MAX_AGE` (48 h) semantics; the dictionary
  entry inherits the snapshot's time.

**Tests and oracle changes an implementation would need.**
- `PartialPriorOracle`: a gateway object's match in the brute-force universe modelled as the *frozen* set (not a
  guess) with an explicit residual, as `accessRoleBound` is; `assertNoCover` for every release attributable to the
  bound, `assertReleaseIsSafe` kept for the X1-disabled reference path (plan §1 X1).
- `PolicyNormalizationTest` / `PolicyHygienePartialPriorTest`: gateway object with main address only → still no bound
  (fail closed); with interfaces → bound = union; cluster with members; IPv6 interface → no bound until A1; object
  whose interfaces field is absent/empty → no bound; negated cell → no bound; destination and source both covered
  only if the frozen statement covers both.
- Mapper fixtures (`PolicyMappersTest`, `CpObjectInventoryParserTest`): real-shaped `interfaces[]` for simple-gateway
  and simple-cluster (masked 192.0.2.x), proving the parse before any semantics; identity test that the bound is
  taken from the object by `ref` equality, never from a device by name.
- Differential run (`PolicyHygieneDifferentialTest` pattern) with per-vendor release/kept counters.

**Gate before code.** Not "review only". Required, in order: (a) vendor evidence closing gap 1 (official page or sk via
an authorized channel, plus A6 samples) recorded as an **A0 amendment** under the same delegation as the access-role
amendment or, if the evidence is MEDIUM, a **PO decision**; (b) a **PO decision** on parsing gateway interfaces into the
object inventory (privacy projection change, parse-scope extension of a gated command); (c) X1 ratified first, since the
bound is only useful through cannot-cover. Until (a) exists, no code: the item stays in "Stays UNKNOWN".

## Recommendation

1. Keep "CP gateway objects as addresses" in the plan's "Stays UNKNOWN" list; do not implement an interface-address
   bound from runtime inventory or from the main address. Any such bound is unsound today.
2. Pursue gap 1 cheaply before gap 2: ask the PO for an authorized read of the relevant sk / API field descriptions and
   two or three masked Packet Search samples (A6) on a cluster object rule; record the outcome as an A0 amendment or
   as a confirmed UNKNOWN.
3. Only if gap 1 closes: a PO decision to retain `interfaces[]` in `CpObjectInventoryParser` (and the dictionary
   mapper), then the bound as a sibling of `accessRoleBound`, gated by X1 and the oracle changes above.

## Sources

Repository: `ui2/service/.../policy/PolicyNormalization.java` (`Resolver#object`, `#accessRoleBound`);
`ui2/platform-core/.../policy/CpObjectTypes.java`, `PolicySnapshot.java` (`PolicyObject`, `ref`), `CpObjectInventory.java`;
`ui2/worker/.../policy/CheckPointPolicyMapper.java` (`VALUE_FIELDS`, `#object`), `CpObjectInventoryParser.java`;
`ui2/worker/.../discovery/cp/MgmtCliEnumerationAdapter.java`, `MgmtCliCommands.java`;
`ui2/service/.../discovery/DiscoveryMatchKey.java`, `DiscoveryRunService.java`; `ui2/service/.../policy/PolicyDomainIndex.java`
(`CP_OUTSIDE`); `ui2/worker/.../inventory/cp/CheckPointFwGetifsParser.java`, `CheckPointClusterVirtualInterfaceParser.java`;
migrations `V13__device_inventory.sql`, `V14__discovery_run_and_candidate.sql`, `V185__path_analysis_policy_target_binding.sql`;
`ui2/job-engine/.../policy/CpPolicyGates.java`. Design: A0 (`PATH_ANALYSIS_VENDOR_SEMANTICS.md`, FROZEN, amendment
2026-11-04), `PO_DECISION_RECORD_2026_11_03_CP_LAYER_SEMANTICS.md`, `POLICY_UNKNOWN_REDUCTION_PLAN_2026_11_05.md`.

Check Point, official, read in full from this session unless noted:
- CP-COLS https://sc1.checkpoint.com/documents/R81/WebAdminGuides/EN/CP_R81_SecurityManagement_AdminGuide/Topics-SECMG/The-Columns-of-the-Access-Control-Rule-Base.htm
- CP-MATCH https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/CP_R82_SecurityManagement_AdminGuide/Content/Topics-SECMG/Rule-Matching-in-the-Access-Control-Policy.htm
- CP-CXLAPI https://sc1.checkpoint.com/documents/R81/WebAdminGuides/EN/CP_R81_ClusterXL_AdminGuide/Content/Topics-CXLG/Cluster-Management-APIs.htm (sample `show simple-cluster` reply, R80.20-era)
- CP-ASPF https://sc1.checkpoint.com/documents/R81/WebAdminGuides/EN/CP_R81_SecurityManagement_AdminGuide/Topics-SECMG/Preventing-IP-Spoofing.htm
- CP-ZONE https://sc1.checkpoint.com/documents/R81/WebAdminGuides/EN/CP_R81_SecurityManagement_AdminGuide/Topics-SECMG/Security-Zones.htm
- CP-ANAT https://sc1.checkpoint.com/documents/R81/WebAdminGuides/EN/CP_R81_SecurityManagement_AdminGuide/Topics-SECMG/Working_with_Automatic_NAT_Rules.htm
- CP-NGSG https://sc1.checkpoint.com/documents/R80.30/WebAdminGuides/EN/CP_R80.30_NextGenSecurityGateway_Guide/136972.htm (first 100k characters read)
- CP-PKT https://sc1.checkpoint.com/documents/R80.30/SmartConsole_OLH/EN/lTxhoOMr4uuA-bojwzhElQ2.htm (SmartConsole Packet Search)
- CP-S2S https://sc1.checkpoint.com/documents/R81.20/WebAdminGuides/EN/CP_R81.20_SitetoSiteVPN_AdminGuide/CP_R81.20_SitetoSiteVPN_AdminGuide.pdf (search extract only: main IPv4 address for VPN; VPN Domain default)
- CP-API https://sc1.checkpoint.com/documents/latest/APIs/index.html#web/show-simple-gateway — JavaScript shell from this session; not read.
- support.checkpoint.com (sk) and community.checkpoint.com: HTTP 403 to page reads; nothing cited.
