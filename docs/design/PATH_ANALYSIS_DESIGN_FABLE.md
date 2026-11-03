<!-- Fable consultation 2026-11-03T12:37:52Z, model claude-fable-5-1; unaltered output -->
<!-- Privacy-gate edit 2026-11-03 (only change to the unaltered output): example address literals (class-E masking range, private-range examples) replaced by 240/4 notation, a masked-address placeholder, and RFC 5737 documentation ranges. Text otherwise unaltered. -->

# neXus Path Analysis and Topology — Fable design (Phase A in detail, B/C/D as plug-in points)

Status: DRAFT — design-session output, 2026-11-03. Not implementation authority. Every item marked **PO DECISION** is undecided; every **ASSUMPTION** was not verified in code or vendor documentation.

Author: Fable (product and systems architect role). Inputs: `docs/design/PATH_ANALYSIS_ARCHITECTURE_BRIEF.md` and the repository files cited inline. No live system was contacted; no real device data was read.

---

## 0. Corrections to the brief, from reading the code

The brief (§2) says neXus has no runtime routing tables and no interface model. That is not what the repository shows. These already exist and are already gated and persisted:

| Fact | Where |
|---|---|
| Interfaces per device and per context (physical / VSID / vsys), with kind, state, VLAN id, MAC, and (PAN) zone | `ui2/service/src/main/resources/db/migration/V13__device_inventory.sql:38`, `V17`, `V148__interface_mac_and_zone.sql:14`; model `ui2/persistence/.../device/inventory/InventoryInterface.java:31` |
| Interface addresses with role `member` vs `cluster_virtual` kept apart at parse time | `V13__device_inventory.sql:60`; `CheckPointFwGetifsParser.java:28`, `CheckPointClusterVirtualInterfaceParser.java:31` |
| Runtime routes per context: destination, next hop, interface, protocol, route table / virtual router | `V13__device_inventory.sql:78`; `CheckPointIpRouteParser.java:28` (`ip -4 route show table all`), `PaloAltoRouteParser.java:31` (`show routing route`, grouped by `virtual-router`) |
| HA role per context (ClusterXL, VSLS, PAN HA) | `V20__inventory_ha_facts_and_vlan.sql:29` |
| Gate rows for every one of those reads, SIGNED_OFF, once per device per run | `V15__inventory_command_gate_entries.sql:34-126`, `V148:19` |
| GiST indexes over routes and interface addresses for IP search | `V127__global_ip_search.sql:11-13` |
| NAT rulebases parsed into `PolicySnapshot` sections: Check Point `CP NAT rulebase` with original/translated cells and `method`; Palo Alto pre/post NAT with source/destination translation detail in `extras` | `CheckPointPolicyMapper.java:49-78`, `PanoramaPolicyMapper.java:174-242`, `PolicySnapshot.java:25-40` |
| A three-valued, budgeted, first-match rule engine with CP ordered + inline layers, PAN pre/post phases, zones, install-on and target binding | `PolicyLookup.java:51-235`, `PolicyNormalization.java:108-156` |
| Device-to-policy binding: PAN by serial, exact whole match (`PolicyFirewallNames.java:17-46`); CP via installation targets resolved to enrolled devices (`CheckPointPolicyCollector.java:980-996`) |
| Masked projection for `role:replay_viewer`, including keys `zone`, `interface_name`, `next_hop`, `destination`, `pre_nat_*`, `post_nat_*`, `translated_*` | `PrivacyMaskingResponseBodyAdvice.java:246-249, 399-405` |

Consequence: Phase A is not "build collection"; it is "bind what is already collected into a graph and an engine". That shrinks Phase A materially and changes the privacy question (§6): the route and interface **values** the brief worries about are already persisted under the FROZEN 14C D-4 contract. What is new is derived data (segments, links, path results) and the NAT translated values, which are already in `policy_snapshot.snapshot` JSONB as object values (`V115__management_policy_snapshot.sql:2`).

What genuinely does not exist: PAN interface-to-virtual-router persistence (parsed in `PaloAltoInterfaceParser.java:62` but `InventoryInterface` has no column for it), ECMP next hops on Check Point (see §2.3), a topology graph, NAT evaluation, a path engine, Check Point gateway topology / anti-spoofing from management (explicitly dropped: `CpObjectInventoryParser.java:8`), LB and ACI connectors, a topology UI.

---

## 1. Data model

Principle: the graph is **derived, versioned, and references inventory rows by their opaque ids**. Nothing in the graph is a second copy of a vendor response. All new tables are additive migrations (append-only, as every V-file to date).

### 1.1 Core (Phase A)

```
topology_snapshot
  snapshot_id        TEXT PK
  built_at           TIMESTAMPTZ
  status             built | partial | failed
  input_runs         JSONB   -- [{device_id, run_id, collected_at}]  (opaque refs only)
  input_policies     JSONB   -- [{policy_id, collected_at}]
  conflicts          INTEGER -- count; detail in topology_conflict
  overrides_applied  INTEGER

topology_node        -- one row per routing context, cluster unit, or unknown segment
  node_id      TEXT PK
  snapshot_id  FK
  kind         routing_context | cluster_unit | cloud | external
  device_id    FK devices (NULL for cloud/external)
  context      TEXT   -- 'physical' | VSID | vsys id ; same vocabulary as device_interface.context
  routing_table TEXT  -- CP: 'main' (V13 route_table) ; PAN: virtual-router / logical-router name
  cluster_ref  TEXT   -- devices.cluster_member_ref when the node stands for a unit
  active_member_device_id TEXT -- from device_inventory_ha, NULL when not provable

topology_segment     -- an L3 broadcast domain or prefix the graph knows
  segment_id   TEXT PK
  snapshot_id  FK
  prefix       CIDR     -- canonical network/len (representation-only normalization; identity law §)
  kind         connected | cloud | manual
  site_tag     TEXT NULL -- Phase D; NULL in A

topology_attachment  -- node ↔ segment, through a real interface
  attachment_id TEXT PK
  snapshot_id   FK
  node_id       FK
  segment_id    FK
  interface_id  FK device_interface        (opaque)
  address_id    FK device_interface_address (opaque)
  address_role  member | cluster_virtual
  zone          TEXT NULL   -- copy of device_interface.zone at build time (PAN only in A)

topology_link_override   -- human-declared, survives rebuilds
  override_id   TEXT PK
  kind          join_segments | isolate_segment | declare_next_hop_device | ignore_interface
  a_ref, b_ref  TEXT    -- interface_id / segment prefix / node_id as the kind requires
  reason        TEXT
  created_by    TEXT (actor fingerprint), created_at, revoked_at NULL
  
topology_conflict
  conflict_id, snapshot_id, kind (overlapping_prefix | duplicate_prefix_unrelated |
     cluster_members_disagree | next_hop_unreachable | interface_without_context),
  refs JSONB (opaque ids), resolution (open | overridden | accepted)

path_query_audit     -- what was asked and the verdict; never the full hop list (§6)
  audit_id, session_id, actor, asked_at, masked_query JSONB, verdict, snapshot_id, hop_count
```

One additive column on an existing table:

```
ALTER TABLE device_interface ADD COLUMN routing_context TEXT;  -- PAN virtual-router / logical-router of the interface
```

Routes are **not** copied. The engine loads `device_route` for the input runs into an in-memory radix trie per `topology_node` (§7).

### 1.2 Identity rules in the model

- Node identity = `(device_id, context, routing_table)`; a cluster unit node is keyed by `cluster_member_ref` and carries its members as evidence, never the other way round (AGENTS.md: evidence identity ≠ operational identity).
- Segment identity = canonical prefix text. Two prefixes join only on **exact equality** after `network(inet)` canonicalisation; overlap without equality is a `topology_conflict`, never a link. Address-text equality is the only accepted join (same rule `PolicyLookup.java:204` applies to install-on).
- Zone is a per-attachment label, never an identity gate.
- `cloud` segments are named by the opaque `segment_id`; their display label is `CLOUD` plus the prefix.

### 1.3 Versioning and freshness

A `topology_snapshot` is immutable once `built`. Each node records the inventory run it came from; each hop in a path result reports `inventoryCollectedAt` and `policyCollectedAt` separately (freshness per source, as the brief asks). A path over a snapshot older than the newest inventory run for any hop device is returned with `stale: true` per hop, never silently refreshed mid-query.

### 1.4 How B/C/D extend it (reserved now so A is not thrown away)

- **Node kinds reserved:** `lb_virtual_server`, `aci_vrf`, `router` (plain routing context without policy). The engine dispatches on `kind`; A implements only `routing_context`, `cluster_unit`, `cloud`, `external`.
- **Phase B (LB):** `lb_virtual_server(vs_id, device_id, vip CIDR/32, port, protocol, pool_id, snat_mode, snat_pool_ref)`, `lb_pool(pool_id, lb_method)`, `lb_pool_member(pool_id, address, port, state)`, `lb_snat_pool(pool_id, addresses)`. A VIP is a `/32` segment attached to the LB node; the hook (§4.6) expands it to members. Address values here are new persisted CLASS-sensitive values; see §6.
- **Phase C (ACI):** `aci_tenant`, `aci_vrf` (becomes a `topology_node` of kind `aci_vrf`), `aci_bridge_domain(subnets[])`, `aci_epg(bd_id)`, `aci_epg_subnet_binding` (static paths / IP-based EPG), `aci_contract`, `aci_subject`, `aci_filter_entry(proto, dport range, sport range, direction)`, `aci_contract_binding(epg_id, contract_id, provider|consumer)`, `aci_l3out(vrf_id, external_epgs[] with subnets)`. A BD subnet is a `topology_segment` of kind `connected` owned by the VRF node.
- **Phase D:** `routing_table` already exists on the node, so VRFs and multi-table lookups need no schema change; `site_tag` on segments disambiguates duplicate private prefixes across sites; `router` nodes reuse `device_interface`/`device_route` once router collectors exist.

---

## 2. Collection (Phase A)

### 2.1 Check Point gateways, clusters, VSX

No new device command is required for interfaces and routes. The existing `inventory_collect` capability issues (`InventoryReadPlan.java:101-131`):

| Read | Form | Gate row | Gives |
|---|---|---|---|
| `fw getifs` | bare, or `bash -lc 'vsenv 0 && …'` on VSX, `vsenv <VSID> && fw getifs && ip -4 route show` per VS | V15 rows `cp_inventory_vsid_addr_and_route` etc. | interface name, IPv4/mask (member) |
| `ip -4 route show table all` | physical context | `cp_inventory_ip_route_show_table_all` | routes, next hop, dev, proto, table |
| `cphaprob -a if` | physical and per VS | `cp_inventory_cphaprob_a_m_if` / `_vsenv0` / vsid row | cluster virtual addresses |
| `cphaprob stat` | | | HA role (active member) |
| `vsx stat -v` | | | VS enumeration |
| `ip -o link show` | | V148 | MAC |

NAT and access rules come from the management policy collection (`V116__cp_policy_reads.sql:5-9`: `show-packages`, `show-access-rulebase`, `show-nat-rulebase` via `mgmt_cli` over SSH, at most once per domain per 6 h automatically).

**Parse-scope extensions (no new command, no new gate per AGENTS.md):**

1. **ECMP on Gaia.** `ip route` prints multipath routes as a header line followed by `nexthop via A dev X weight 1` lines. `CheckPointIpRouteParser.java:51-71` skips any line whose first token is not a destination, `default`, `blackhole`, `local` or `broadcast`, so `nexthop` lines are dropped and the route keeps no next hop. ASSUMPTION: the estate has such routes; must be measured on a fixture before freeze. Fix: carry the last destination forward and emit one `ParsedRoute` per `nexthop` line.
2. **Route table filter.** Phase A evaluates the `main` table only (rows with no `table` token or `table main`); any other table (PBR) is recorded on the hop as `UNKNOWN: policy-based routing table present` when the device has one. ASSUMPTION: Gaia PBR tables appear in `table all` output.

**One optional new read, PO DECISION A-1:** Check Point gateway/cluster object topology and anti-spoofing (interface "internal/external", "leads to" network group) from `show-gateways-and-servers`/`show-simple-gateway details-level full` through the already-gated object inventory read (`V124__cp_policy_object_inventory.sql`). `CpObjectInventoryParser.java:8` deliberately drops topology today. Phase A does **not** need it for routing or rule matching (CP rules are address-based, `PolicyLookup` needs install-on only). It is needed only to (a) report anti-spoofing drops and (b) draw the CP interface roles. Recommend: defer to a Phase A.2 parse-scope extension after the PO sees the first path results; until then the hop reports `antiSpoofing: NOT_EVALUATED`.

### 2.2 Palo Alto firewalls and Panorama

| Read | Gate row | Gives |
|---|---|---|
| `<show><interface>all</interface></show>` | `pan_inventory_show_interface_all` (V15), switchable per `PanFirewallCommandSwitches.java:30` | ifnet entries: ip, addr, vsys, `fwd` (virtual router), zone, tag; hw: mac |
| effective-running derived interfaces on PAN-OS 12+ | no device command (`PaloAltoConfiguredInterfaceParser.java:29-53`) | same shape, grade C (configuration intent, not runtime) |
| `<show><routing><route/></routing></show>` | `pan_inventory_show_routing_route` | routes per virtual router, flags (C/S/O/B/H, E = ECMP) |
| `<show><high-availability><state/>…` | `pan_inventory_show_ha_state` | HA role |
| Panorama config reads (`V117__pan_policy_reads.sql`) | `pan_policy_*` | security + NAT pre/post rulebases, device-group hierarchy, targets by serial |

**Required Phase A changes, all parse-scope / persistence:**

1. Persist `fwd` (virtual router) into the new `device_interface.routing_context` column. The parser already produces `interfaceNameToVirtualRouter` (`PaloAltoInterfaceParser.java:62`); the executor discards it. On PAN-OS 12 the configured-interface parser also yields the router name (`PaloAltoConfiguredInterfaceParser.java:43-44`).
2. Interfaces from the configured-interface fallback are marked grade C in the hop output (`interfaceSource: configuration`), never presented as runtime truth.
3. **Advanced Routing Engine (logical routers).** ASSUMPTION: `show routing route` does not return logical-router routes on devices using the advanced routing engine; the correct op command is `<show><advanced-routing><route/>…`. That is a **new command** and needs a gate entry (vendor, read, PAN_XML_API, 30 s, no retry, once per device per run). Until gated: such devices get `routing: UNKNOWN (advanced routing engine, route read not approved)` and the path stops there.

### 2.3 Job and gate shape, cadence, rate limits

- No new capability kind for A. Topology build is a **service-side computation**, not a device job: it never touches a device, so it needs no gate, no job window, no rate limit against devices.
- Triggers: completion of an `inventory_collect` run for any CP/PAN device, completion of a policy collection, creation/revocation of an override. Debounced (default 5 min) so a fleet-wide inventory wave produces one rebuild. Manual "Rebuild topology" button for `security_admin`.
- Cadence of inputs stays as today (inventory windows per `JobWindowPolicy`; policy ≤ 1 per 6 h per domain). Path analysis does not raise polling (AGENTS.md: stability over speed).
- Query rate limit: same shape as `PolicyLookupController.java:80-88` (30 per actor per minute, 10 000 actors), because a path query runs up to 32 rule lookups.

---

## 3. Topology construction

Deterministic function of (inventory runs, policies, overrides). Steps, in order:

1. **Nodes.** For each enrolled CP/PAN device with a latest inventory run (`JooqDeviceInventoryRepository.findLatestRun`, `:99`): one `routing_context` node per `(context, routing_table)` seen in `device_route`. Devices sharing `cluster_member_ref` with matching `cphaprob`/HA facts form one `cluster_unit` node; the unit's forwarding tables are taken from the member whose HA role is active (`device_inventory_ha`). If members' route sets differ (set comparison of canonical `destination|next_hop|interface` triples), record `cluster_members_disagree` and mark the unit `degraded`; paths through it return `UNKNOWN: RELATIONSHIP_INCONSISTENT`. If no active member is provable, the unit is still a node but every hop through it is `UNKNOWN: active member unproven`. Per AGENTS.md: pair existence ≠ pair health; this design never upgrades the former into the latter.
2. **Segments from attachments.** Every `device_interface_address` of a context yields a candidate segment `network(address)`. Connected routes (`protocol = connected`, `V13:85` vocabulary) corroborate; an address without a connected route or vice versa is recorded on the attachment as `corroborated: false` (still attached). Cluster virtual addresses attach the `cluster_unit` node; member addresses attach the members as evidence only.
3. **Links by shared segment.** Two attachments with identical canonical prefix share a segment. Overlapping non-identical prefixes (`/24` vs `/25` on different devices) are a conflict: both attachments keep their own segments, no link, conflict row written. Duplicate identical private prefixes on devices with no other common segment and no next hop between them (classic two-branch 203.0.113.0/24) are **not** auto-joined: a prefix seen on more than two unrelated nodes becomes `duplicate_prefix_unrelated` and only an override (`join_segments` or `isolate_segment`) resolves it. Phase D adds `site_tag`.
4. **Next-hop resolution.** For each route with a next hop, find the egress interface's segment; if the next hop falls inside it and another node attaches to that segment with that exact address, the route points to that node. If no node holds the address, create (or reuse) a `cloud` segment for that `(segment_id)` and record the next hop as `unknown device`. A default route whose next hop is unknown points to the single `external` node of the snapshot.
5. **VSX internals.** Routes with `dev wrp*` (warp links to a virtual switch/router) are ASSUMPTION territory: treat the VSW as a `cloud` segment named by the VS context until a measured fixture proves the warp pairing. The per-VS inventory already runs `vsenv <VSID>`, so VS nodes exist.
6. **Overrides** are applied last and recorded on the snapshot (`overrides_applied`). An override that no longer matches any current id is reported, not silently dropped.
7. **Unknown segments ("clouds")** are first-class nodes: a path may end in one (`verdict: UNKNOWN, reason: exits known topology`). Phase D routers will replace clouds with real nodes without changing the path contract.

---

## 4. Path engine

### 4.1 Query

```
PathQuery {
  source: IPv4 | CIDR, destination: IPv4 | CIDR,
  service: "tcp/443" | "udp/53" | "any"          (same grammar as PolicyLookup.Query:23)
  start: { mode: auto | node, nodeId?, ingressInterfaceId? }
  options: { maxHops: 32, maxBranches: 16, ecmpFanout: 4, computeReturn: true }
}
```

IPv4 only in A (the rule engine is IPv4 only, `PolicyLookup.java:11`). CIDR queries are evaluated by partitioning exactly as the lookup does (`PolicyLookup.java:142-156`); a partition count above the lookup's own limits yields `PARTIAL` with the parts listed.

### 4.2 Ingress determination

1. If `start.mode = node`, begin there.
2. Otherwise find segments whose prefix contains the source. If exactly one, begin at every node attached to it (a shared segment behind two firewalls gives two candidate first hops → each becomes a branch). If several (nested prefixes), take the longest. If none, candidate first hops are the nodes holding the most specific **non-default** route toward the source whose egress is a cloud; if several tie, the result is `AMBIGUOUS_INGRESS` listing the candidates, and the UI asks the user to pick. Never guess.

### 4.3 Per-hop evaluation

State per hop: `(nodeId, ingressAttachment, packet{src,dst,proto,dport}, natHistory[])`, plus a visited set of `(nodeId, packet)` for loop detection.

Vendor order is encoded in a per-vendor `HopSemantics` strategy. The orders below are what the design will implement; **each is ASSUMPTION until confirmed against official vendor documentation, which is mandatory before freeze (AGENTS.md vendor-semantics law).**

**Check Point (`CP`)**

1. Anti-spoofing: `NOT_EVALUATED` in A (§2.1).
2. NAT rulebase first match over `CP NAT rulebase` sections in rulebase order on *original* `(src, dst, service)`; manual rules precede automatic rules in the rulebase as collected (`auto-generated` extra). `method` static vs hide and `translated-*` cells give the translation. ASSUMPTION: the global property "translate destination on client side" is at its default (true), so destination NAT precedes routing; this property is not collected and must become a collected fact or a documented assumption shown on the hop.
3. Route lookup on the **post-DNAT** destination in the context's `main` table, longest prefix. ECMP → branch.
4. Security evaluation via `PolicyLookup.Prepared.evaluate` on the snapshot bound to this node, query = **original** addresses (ASSUMPTION: CP matches the rulebase pre-NAT for both source and destination), `target` = the install-on object id bound to this gateway (§4.4), `context` = VS context where the snapshot carries targets with context.
5. Source NAT (hide/static) applied to the packet handed to the next hop.

**Palo Alto (`PAN`)**

1. Ingress zone = zone of the ingress attachment. Route lookup on **original** destination in the ingress interface's virtual router → provisional egress interface and zone.
2. NAT policy match: pre-rulebase then post-rulebase sections, first match, on pre-NAT `(src, dst, service)`, `from` = ingress zone, `to` = provisional egress zone (`to-interface` honoured when present). Translation from `extras` (`source-translation-type`, `translated-source`, `translated-destination`, `translated-destination-port`, dynamic destination). `dynamic-ip-and-port` with `interface-address` resolves to the egress interface's address from inventory; if the interface has no address recorded, the translated source is `UNKNOWN` (still continues, flagged).
3. If destination NAT applied: second route lookup on translated destination → actual egress interface and zone.
4. Security evaluation: `PolicyLookup` with pre-NAT addresses, `sourceZone` = ingress zone, `destinationZone` = post-NAT egress zone, `target` = device id (snapshot targets by serial, `PolicyLookup.java:82-84`). Pre → device-group → post order is already inside the engine (`PolicyNormalization.EvaluationScope`).
5. Default rules: the engine already returns `UNKNOWN` with `PAN_INTRAZONE`/`PAN_INTERZONE` reasons when no rule matches (`PolicyLookup.java:13-14`); the path engine keeps that as `UNKNOWN`, with the UI explaining the built-in default would apply. Upgrading intrazone-default to `ALLOWED` is a PO decision only after the override collection ("default-security-rules", `PanoramaPolicyMapper.java:244-249`) is complete.

**Common**

- Delivery: if the (post-NAT) destination lies in a segment attached to the current node, the hop's egress is that segment and the path terminates `DELIVERED`.
- Cloud / external egress: terminate with `EXITS_KNOWN_TOPOLOGY` (overall `UNKNOWN` unless the user asked for "up to the edge" mode, in which case the firewalls' verdicts are reported and the overall is `ALLOWED_TO_EDGE`).
- Loop: visited `(node, packet)` repeats → `LOOP`, overall `UNKNOWN`.
- Hop limit 32, branch limit 16; exceeding either → `UNKNOWN: analysis budget`, the same vocabulary as the lookup.
- Stale inputs → hop `stale: true`, overall verdict unaffected but shown.

### 4.4 Binding a node to its policy snapshot

- PAN: `PolicySnapshot.Metadata.targets[].deviceId` already matched by serial. Local-firewall snapshots (`LocalFirewallPolicyService`) take precedence when Panorama marks the device out of sync (`Target.syncStatus`); if neither is provable → `UNKNOWN: policy binding unproven`.
- CP: a gateway's node needs the policy package installed on it **and** the opaque install-on object id the rulebase uses. `installationTargets` (`CheckPointPolicyCollector.java:980`) gives enrolled `Target`s, but the install-on placement check at `PolicyLookup.java:204` compares object ids. ASSUMPTION: `PolicyCollectionRepository.targets(run, domain, uid)` records the gateway object uid alongside the device id; if it does not, A adds a `policy_target_binding(device_id, context, policy_id, install_on_object_id)` table populated at collection time. Runtime corroboration already exists: `cpstat -f policy fw` (`InventoryReadPlan.java:89`) reports the installed policy name; a mismatch between management intent and installed name makes the binding `UNKNOWN` (configuration intent ≠ runtime truth).

### 4.5 Return path and asymmetry

With `computeReturn`, the engine runs the reverse query from the delivered packet (post-NAT dst as source, post-NAT src as destination, ports swapped) without NAT lookups of its own at hops that already translated forward (reverse translation is implied by the forward NAT entry, which both vendors keep state for). If the reverse hop set differs from the forward set, `asymmetric: true` with both hop lists. In Phase A every node is a stateful firewall, so an asymmetric return is a warning, not a denial; Phase D routers/ACLs make it a verdict input.

### 4.6 Hooks for B and C

- `lb_virtual_server` node: when the current destination equals a VIP `/32` and the service matches the VS port, the hop reports `LB_TRANSLATION` and the path **branches per pool member** (bounded by `maxBranches`; members of state `down` are skipped and listed). Source NAT (automap / SNAT pool) rewrites the packet. Overall verdict = all member branches `ALLOWED` → `ALLOWED`; mixed → `PARTIAL`.
- `aci_vrf` node: when a segment is owned by a VRF node, the hop evaluates EPG membership of src and dst (BD subnet + static/IP binding), then contracts: provider/consumer binding, subject filters on proto/port, vzAny, preferred groups; L3Out external EPG subnets for traffic entering/leaving the fabric. Verdict vocabulary is the same (`ALLOWED/DENIED/UNKNOWN`), the deciding object is `{contract, subject, filterEntry}` instead of a rule.
- Both are strategy implementations behind the same `HopSemantics` interface; A ships with CP and PAN only.

### 4.7 Output contract

```
PathResult {
  query, topologySnapshotId, computedAt,
  verdict: ALLOWED | DENIED | PARTIAL | UNKNOWN | ALLOWED_TO_EDGE,
  reason: string,                       -- fixed vocabulary, superset of PolicyLookup.REASONS
  blockingHop: hopIndex | null,         -- "who blocks this": first DENIED hop
  hops: [{
    index, nodeId, nodeKind, deviceId, context, routingTable, displayName,
    ingress: {interfaceId, interface_name, zone, segmentId}, egress: {...},
    packetIn: {src, dst, proto, dport}, packetOut: {...},
    nat: {applied: bool, kind: static|hide|dynamic-ip-and-port|destination|..., ruleId, number, name, sectionName} | null,
    route: {destination, next_hop, protocol, ecmp: bool, routeId},
    policy: {policyId, collectedAt, verdict, lookupReason, decidingRule, undecidableRules[]},   -- PolicyLookup body verbatim
    antiSpoofing: NOT_EVALUATED | ...,
    inventoryCollectedAt, policyCollectedAt, stale: bool, interfaceSource: runtime | configuration
  }],
  branches: [PathResult-lite],          -- ECMP / LB alternatives
  returnPath: {hops[], asymmetric: bool} | null,
  warnings: [...], limitations: string
}
```

Key names deliberately reuse the ones `PrivacyMaskingResponseBodyAdvice` already masks (`interface_name`, `zone`, `next_hop`, `destination`, `src`, `dst`, `rule_name`, `section_name`, `device_id`, `interface_id`, `rule_id`), and the policy sub-object is the `PolicyResponse` body so `PolicyPrivacy.mask` applies unchanged.

---

## 5. UX

- **Where:** a new top-level screen `Path` (`ui2/frontend/src/screens/PathAnalysisScreen.tsx`), sibling of Policy. The form is the lookup form (`PolicyLookupTab.tsx:36-46`) without the policy picker, plus "Start from" (auto or a device/context chosen from the inventory tree).
- **Answer:** a verdict chip and a one-line reason at the top; under it a vertical hop list. Each hop row: device pseudonym/name and context, ingress → egress (interface, zone), a NAT chip when applied, the deciding rule as a button that opens the existing `PolicyRuleViewer`, a verdict chip. `UNKNOWN` hops show the reason inline exactly as the lookup does ("rule N could match …").
- **"Who blocks this":** the first DENIED hop is pinned above the list with the rule link; "what would have to change" is Phase D (change design), but the hop already carries everything needed (device, zones, pre-NAT addresses, service).
- **Branches and return:** ECMP/LB branches as tabs on the hop; return path as a collapsible second list with an asymmetry banner.
- **Map in A:** path-local only: the hops ±1 neighbour rendered as a left-to-right diagram with segments as pills, clouds dashed. No fleet map in A.
- **Map in D (enterprise scale):** server-side aggregation (site/segment collapse, node degree thresholds), viewport-driven fetch (`/api/v2/topology/snapshot/{id}/view?bbox=…`), WebGL canvas (Sigma.js or similar; library choice is an implementation decision), thousands of nodes never sent at once; search-to-focus instead of pan-to-find.
- **Overrides:** a "Topology conflicts" panel under Administration lists conflicts with "join / isolate / ignore" actions for `security_admin`; every action is audited.
- **aiview:** sees the same screen; names are `FW-TANGO-04`-style pseudonyms, interfaces/zones/rules the `POL/ZONE/RULE-` labels, addresses the subnet-preserving masked form (`SubnetPreservingIpMasker`), overrides read-only, no rebuild button. The path response is a self-masking response type (like `IpSearchResponse`, `PrivacyMaskingResponseBodyAdvice.java:62-70`) so the policy sub-objects are not masked twice. Fixtures and previews use `192.0.2.x` only.

---

## 6. Privacy, storage and RBAC — options for the PO

Facts first: interface addresses, routes and next hops are **already persisted** as values (V13, FROZEN 14C D-4), and NAT translated objects live inside `policy_snapshot` JSONB. The new questions are narrower.

**PO DECISION P-1 — derived topology persistence.** Options:
- (a) *Recommended.* Persist `topology_*` tables as in §1.1: only opaque ids plus segment prefixes (CIDR values, already derivable from V13 rows). Rationale: overrides and conflicts must be durable anyway, rebuilds are cheap, and nothing new of higher sensitivity is written.
- (b) In-memory graph, persist only overrides and conflicts. Saves two tables; costs rebuild on every service start and loses snapshot history ("what did the path look like last week").

**PO DECISION P-2 — path result retention.**
- (a) *Recommended for A.* Do not persist results. Persist `path_query_audit` with the **masked** query and verdict only, through `AuthzDecisionRepository` exactly as `PolicyLookupController.java:50-57` does.
- (b) Persist full `PathResult` for N days (contains raw addresses and rule names) to support "has this path changed" alerts. Needs its own retention and a `security_admin`-only read. Defer to D.

**PO DECISION P-3 — new persisted values in A.** `device_interface.routing_context` (a router name, CLASS-sensitive like a zone name; masked like `zone`). Recommend approve.

**PO DECISION P-4 — Phase B/C values.** LB member and SNAT addresses and ACI subnets are new persisted addresses from new sources. Recommend the same V13 treatment (values persisted, masked on the way out), decided per connector when its security/command review is written (diagnostic-path law: each is a new credential boundary).

**RBAC (`ActionRegistry`):**
- `path_analysis_read` — default `role:security_admin`; `role:viewer` only by custom role. aiview gets it masked (`PO DECISION P-5`, mirrors the 2026-10-04 and 2026-10-18 aiview decisions).
- `topology_read` — same as above.
- `topology_override_write` — `role:security_admin` only, never aiview.
- `topology_rebuild` — `role:security_admin` / `role:operation_admin`.
No device action class is involved; nothing here is a network action.

**Raw-evidence law compliance:** the engine reads parsed rows only; no vendor response is retained by this design; the only new reads (advanced routing, optional CP topology) parse in memory like every other read.

---

## 7. Scale and performance

- **Rule engine cost.** `PolicyLookup.prepare` normalises a snapshot once; keep a service-level cache keyed by `(policy_id, collected_at)`, bounded (LRU, ~64 entries; a 16k-rule PAN snapshot's `Prepared` is a few MB). Per hop one `evaluate` with the existing 250 000 budget. 32 hops × 2 (return) worst case stays well under a second per query on cached snapshots; cold prepare of a 16k-rule snapshot is the dominant cost (measure; expected hundreds of ms).
- **Routes.** Radix trie per node built from `device_route`; hundreds of devices × a few thousand routes = low millions of entries, tens of MB. Built incrementally per node on each inventory run; snapshot build is a pointer swap.
- **Segments.** Hash by canonical prefix; linear in attachments.
- **Recompute triggers.** Inventory run → rebuild that node's trie and attachments, then a debounced snapshot; policy snapshot → evict `Prepared` for that policy; override → re-link only.
- **Query limits.** 30 per actor per minute; per-query budgets as in §4.3; CIDR partitions bounded by the lookup's 128.
- **Database.** Topology tables are small (one row per interface attachment, one per segment); indexes on `(snapshot_id, device_id)` and on `prefix` (GiST `inet_ops`, as V127).

---

## 8. Testing strategy

1. **Fixture corpus** (`ui2/service/src/test/resources/path/`): synthetic inventory runs and policy snapshots over `192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`, built with the same helpers as `PolicyLookupTest` and `PolicyHygieneTest` (`PanNatSectionsEvaluationTest.java:5-6`).
2. **Golden cases, one test each, asserted on the full `PathResult`:** CP static DNAT before routing; CP hide NAT at egress; CP ordered layers + inline layer through a path; CP cluster VIP as hop address with active-member tables; CP VS context binding; PAN DNAT with zone change and second route lookup; PAN dynamic-ip-and-port with interface address; PAN intrazone no-match → `UNKNOWN`; ECMP branching with mixed verdicts → `PARTIAL`; loop detection; cloud exit → `EXITS_KNOWN_TOPOLOGY`; duplicate private prefix → conflict and override resolution; stale inventory flag; policy binding unproven; grade C interfaces flagged.
3. **Invariant (property) tests:** no `ALLOWED` overall when any hop is `UNKNOWN` or `DENIED`; determinism for identical inputs; masking idempotence of the response for `replay_viewer`; no raw address in `path_query_audit`.
4. **Parser extension tests:** Gaia multipath fixture; PAN `fwd` persistence; PAN-OS 12 configured-interface VR/zone.
5. **Render harness and privacy gate** per AGENTS.md whenever the frontend screen lands.
6. **Real-environment validation (separate gate, never skipped):** compare a PO-chosen sample of paths against the vendors' own read-only matchers. PAN `test security-policy-match`, `test nat-policy-match`, `test routing fib-lookup` and Gaia `ip route get` are read-only but are **new commands** and need gate entries and PO approval under the 2026-09-26 ad hoc diagnostics amendment before a single one is sent. Status stays `AUTOMATED_VALIDATED` until then.

---

## 9. Risks, open questions, stage plan

**Risks**
- R1 Vendor NAT/route ordering encoded from general knowledge; freezing without official documentation would violate the vendor-semantics law. Documentation research is a stage of its own.
- R2 CP gateway ↔ install-on object binding may not exist in persisted form (§4.4).
- R3 Gaia ECMP output shape unmeasured; PAN advanced routing engine unread.
- R4 Duplicate private prefixes in a large estate will generate many conflicts; without site tagging (D) the override workload could be high. Mitigation: conflicts are informative, paths through them are `AMBIGUOUS`, not wrong.
- R5 PAN-OS 12 interfaces are configuration-grade; a path through such a device is intent-based at that hop and must say so.
- R6 Cluster units with unprovable active member make every path through them `UNKNOWN`; this is correct but may look like a regression to users used to Tufin's optimism.

**Open questions for the PO**
- Q1 Decisions A-1, P-1…P-5 above.
- Q2 Should "ALLOWED_TO_EDGE" exist, or must a path that exits known topology always be `UNKNOWN`? (Recommendation: exist, clearly labelled.)
- Q3 LB vendor for B (assume F5 BIG-IP iControl REST; new credential boundary, own security review).
- Q4 Whether the Check Point global property "translate destination on client side" should be collected (one `show-global-properties`-style management read, new gate) or asserted.

**Stage plan, Phase A** (sizes are engineering-days, rough, single engineer)

| Stage | Content | Size |
|---|---|---|
| A0 | Vendor documentation research for CP and PAN NAT/route/policy order; write `PATH_ANALYSIS_VENDOR_SEMANTICS.md` and freeze it | 3 |
| A1 | Parse-scope extensions: Gaia multipath, PAN `routing_context` column + persistence, grade C flag; tests | 3 |
| A2 | Topology builder + tables (P-1), conflicts, overrides API; tests | 6 |
| A3 | Path engine with CP/PAN `HopSemantics`, binding (§4.4), return path, output contract; golden tests | 8 |
| A4 | API, RBAC actions, audit, masking as self-masking response; Path screen with hop list and path-local diagram; render harness, privacy gate | 6 |
| A5 | Optional: CP topology/anti-spoofing parse extension (A-1); validation command gates and real-environment comparison (PO-run) | 3 + PO time |

Status progression per AGENTS.md: A3 completes `IMPLEMENTED → AUTOMATED_VALIDATED`; `REAL_ENV_VALIDATED` only after A5's comparison; `DONE` never from automated tests alone.

**Where B/C/D plug in (summary):** new node kinds and `HopSemantics` strategies (§1.4, §4.6); new connectors as new transport kinds with their own gate rows and security reviews; D adds `router` nodes, `site_tag`, multi-table lookups (already modelled by `routing_table`), the fleet map, and change design built on the `blockingHop` contract.

---

Ürün Sahibi için kısa not: Belge yukarıda, İngilizce (AGENTS.md mühendislik çıktısı dili kuralı). En önemli bulgu, brief'in aksine arayüz, adres, rota ve HA verilerinin V13/V148 ile zaten toplanıp saklandığı; Faz A bu yüzden "toplama" değil "grafik + motor" işi. Karar bekleyen maddeler A-1 ve P-1…P-5 olarak işaretli; Argon tasarımıyla çapraz inceleme için hazır.
