# Topology conflicts guide (path analysis)

Status: DRAFT — operator/engineering explanation derived from source; not a contract and not implementation authority.
Date: 2026-11-05. Scope: `ui2/service/.../pathanalysis/topology/` (`TopologyBuilder`, `TopologyGraph`, `NextHopResolver`,
`TopologyLinkOverride`). Source read at `origin/cloud-base/1105`. No live-system access: no counts, hostnames, addresses or
serials appear here; the live estate reports roughly 250 conflicts and this guide does not break that number down (UNKNOWN —
the per-kind split must be read from `topology_conflict` through the aiview projection).

## 1. How a conflict is produced

`TopologyGraph.ConflictKind` has exactly ten values; the builder emits each of them (section 3). Every conflict carries
opaque ids (device, peer device, context, node ids) and at most one network (`Ipv4Prefix`). Conflict ids are a hash of
`kind|device|peer|context|network|override`, and identical ids are de-duplicated, so a kind fires once per distinct key,
not once per route or interface. Conflicts are sorted by id, persisted per snapshot (cap `MAX_STORED_CONFLICTS` = 5000)
and have a `resolution` of `OPEN`, or `OVERRIDDEN` when an override resolved it in this build (only
`DUPLICATE_PRIVATE_PREFIX` can be `OVERRIDDEN`). A conflict never blocks the build: the graph is still produced; the
conflict says which relationship the builder refused to guess.

Background rules that explain most conflicts:
- A node is one routing context of one unit (standalone device, or an enrolled cluster), per routing table/VRF for
  non-Check Point vendors. The same device in two VRFs is two nodes.
- Nodes sharing an exact prefix are joined into one segment automatically only if the prefix is globally reachable.
  Prefixes in the IANA not-globally-reachable blocks (`10/8`, `172.16/12`, `192.168/16`, `100.64/10`, `169.254/16`,
  `192.0.0/24`, the three documentation ranges, `198.18/15`, `240/4`) are joined only when corroborated: a route on one
  node whose next hop is exactly the other node's address on that prefix, or a FORCE_LINK override.
- Identifiers and addresses are compared as opaque text; nothing is merged by hostname or by formatting similarity.

## 2. Triage table

| Kind | Expected? | Typical meaning |
|---|---|---|
| `DUPLICATE_PRIVATE_PREFIX` | Often expected | Same private prefix on 2+ unlinked nodes (separate VRFs / sites) |
| `OVERLAPPING_PREFIX` | Mixed | Different-length nested prefixes on different units |
| `DUPLICATE_ADDRESS` | Rarely expected | Same host address on 2+ units inside one segment |
| `HA_ROUTE_DRIFT` | Informational | Active and standby route sets differ |
| `CLUSTER_MEMBERS_DISAGREE` | Data problem | Cluster members disagree about being a cluster / VIPs |
| `INTERFACE_WITHOUT_CONTEXT` | Data/parsing problem | Interface lacks a virtual-router tag on a multi-VRF context |
| `ADDRESS_PREFIX_UNKNOWN` | Data/parsing problem | Address with no mask and no unique connected route |
| `NEXT_HOP_UNREACHABLE` | Mixed | A route's next hop lies in no attached prefix |
| `NEXT_HOP_AMBIGUOUS` | Data problem or duplicate | Next hop address held by 2+ nodes on a segment |
| `OVERRIDE_NO_MATCH` | Operator-fixable | A manual override no longer matches the inventory |

## 3. Each conflict kind

### 3.1 `DUPLICATE_PRIVATE_PREFIX`
- Condition (`buildSegments`): for one exact prefix, the prefix is **not** globally reachable and, after auto-joining by
  corroboration, there is more than one connected component of nodes (`componentsBefore > 1`). One conflict per prefix;
  `network` = the prefix, `device`/`peer` = first member of the first two nodes. Marked `OVERRIDDEN` when active overrides
  leave a single component (FORCE_LINK) or every component pair declared apart (SUPPRESS_LINK).
- Meaning: the same private network number is used on nodes that nothing proves to be the same L2/L3 segment. The builder
  keeps them as separate segments rather than inventing a link.
- Expected: yes for overlapping private ranges in separate VRFs or sites, for default lab/management ranges reused
  per site, and for point-to-point /30 or /31 ranges reused on different links. It is normal on a large estate and likely
  the largest class by count.
- Indicates a problem when: the two nodes really are on one network but neither has a route naming the other's address as
  next hop (uncollected peer, missing route data, or the peer is a cluster counted as a different unit).
- Operator action: leave it if they are different networks (or record `SUPPRESS_LINK` with reason
  `DUPLICATE_PRIVATE_PREFIX_DIFFERENT_NETWORK` / `SEPARATE_ROUTING_DOMAIN` so it shows `OVERRIDDEN`); if the same
  network, create `FORCE_LINK` with `DUPLICATE_PRIVATE_PREFIX_SAME_NETWORK` (or `VERIFIED_CABLING`). Overrides name ends by
  device + context + interface name.

### 3.2 `OVERLAPPING_PREFIX`
- Condition (`detectOverlaps`): two segments with unequal prefixes where one contains the other (e.g. /24 vs /25), both
  non-/32, neither VSX_WARP nor MANUAL, attached to different nodes. One conflict per prefix pair, naming the first
  differing node pair; `network` = the outer prefix. Never produces a link.
- Meaning: mask mismatch between neighbours, or a summary/secondary range configured as an interface network.
- Expected: sometimes (a /24 on one VRF overlapping a /25 elsewhere is plausible). Suspicious when two adjacent
  devices disagree on the mask of the same link: usually a parsing/mask-collection issue or a genuine misconfiguration.
- Operator action: no override resolves it (overrides act on same-prefix groups and FORCE_LINK segments are excluded).
  Check both interfaces' masks in inventory; fix the device or the collector.

### 3.3 `DUPLICATE_ADDRESS`
- Condition (`buildSegments`): inside one resulting component of a segment, the same host address appears on attachments
  of two different nodes (`hostOwner` check). `network` = the segment prefix.
- Meaning: one IP, two units, same segment. On a real network that is an address conflict; in the graph it is more often
  a modelling artefact.
- Expected: no. Typical causes: (a) cluster VIP recorded per member as separate units (see section 4); (b) a
  globally-reachable prefix auto-joined although the nodes are different networks (public-looking address reused
  internally); (c) VRRP/HSRP virtual addresses on non-Check Point pairs that are not enrolled as a cluster;
  (d) stale inventory after renumbering.
- Operator action: enrol the pair as a cluster if it is one; re-collect; for a reused public-style range use
  `SUPPRESS_LINK` between the two ends (SUPPRESS applies to any prefix group that was joined) or fix the data.

### 3.4 `HA_ROUTE_DRIFT`
- Condition (`buildNode`): cluster unit with exactly one provable active routing member, and some collected peer's route
  set (destination|next hop|interface, active routes only, per context/table) differs from the active member's.
  Keyed per active member + peer + context.
- Meaning: standby routing state differs from active. Routing uses the active member only. Per
  `PATH_ANALYSIS_DESIGN.md` amendment 2026-11-04 it is informational, not an error.
- Expected: often (dynamic routes, standby not installing them, different collection timestamps).
- Operator action: none required; investigate only if the standby is expected to be symmetrical. Re-collect both
  members close in time. Not overridable.

### 3.5 `CLUSTER_MEMBERS_DISAGREE`
- Conditions (`clusterDisagreement`), for enrolled clusters only: (a) a member whose latest run reports the physical
  context HA role `STANDALONE`; (b) Check Point only: two collected members whose `cluster_virtual` address sets
  (`interface|host`) differ in some context (reported against the first member). The cluster node also gets flag
  `CLUSTER_MEMBERS_DISAGREE`.
- Meaning: members of one enrolled cluster do not describe the same cluster.
- Expected: no. Usual causes: stale or partial collection of one member, wrong cluster enrolment (two devices that are
  not a pair), a member run before cluster formation. Before 2026-11-04 VSX VS address-role mislabelling (section 4) could
  also feed (b).
- Operator action: fix enrolment (cluster member reference) or re-collect both members; not overridable.

### 3.6 `INTERFACE_WITHOUT_CONTEXT`
- Condition (`buildNode`): non-Check Point vendor, a context with more than one routing table/VRF, and an interface with no
  `routingContext` recorded. The interface is skipped (not attached anywhere). One conflict per device and context.
- Meaning: the collector could not tell which VRF the interface belongs to; its networks are missing from the topology.
- Expected: no — data/parsing gap (vendor collector not emitting the interface's virtual router).
- Operator action: none in-product; fix the collector/parsing or re-collect. Missing links around that interface follow.

### 3.7 `ADDRESS_PREFIX_UNKNOWN`
- Condition (`buildNode`): an attachable IPv4 address has no mask and the context has not exactly one connected route on that
  interface containing it (length < 32). The address is dropped.
- Meaning: the builder will not guess a prefix length.
- Expected: no — typical of collectors that record bare addresses (for example Check Point `cphaprob -a if` lists) when
  the connected route is missing or ambiguous.
- Operator action: ensure routes are collected for that context; otherwise create a `FORCE_LINK` with an `assumedSubnet`
  (manual segment). Not overridable directly.

### 3.8 `NEXT_HOP_UNREACHABLE`
- Condition (`resolveNextHops`): for an active IP next-hop route, the next hop is unparseable (or not a host/32), or lies in
  no attached segment of that node. One conflict per node and context, however many routes.
- Meaning: the route points at an address the node has no attached network for — typically a dropped attachment
  (3.6, 3.7), a stale route, or a next hop reached via an interface the topology does not model (tunnel, VRF leak).
- Expected: sometimes (routes via tunnel interfaces, black-hole style routes). Frequent counts usually trace to 3.6/3.7.
- Operator action: resolve upstream data conflicts first; a `FORCE_LINK` manual segment can model a tunnel/WAN hop.

### 3.9 `NEXT_HOP_AMBIGUOUS`
- Condition: the next-hop address is held by two or more other nodes on the same egress segment. One conflict per node,
  context and segment; `network` = the segment prefix.
- Meaning: the address cannot be attributed to one next hop.
- Expected: no; usually a consequence of `DUPLICATE_ADDRESS` (cluster VIP seen as two units, or genuine duplicates).
- Operator action: clear the duplicate address (3.3); then this disappears.

### 3.10 `OVERRIDE_NO_MATCH`
- Condition (`buildSegments`/`noMatch`): an active override whose ends cannot be placed. Reasons (`NoMatchReason`):
  `DEVICE_REMOVED`, `ENDPOINT_NOT_FOUND`, `ENDPOINT_AMBIGUOUS` (end matches more than one node), `SAME_NODE`.
- Meaning: the override is reported, never dropped, and changes nothing.
- Expected: no; inventory changed under a saved override (device deleted, interface renamed, VRF changed).
- Operator action: revoke or re-create the override against the current device/context/interface.

## 4. Effect of the 2026-11-04 VSX change (commit ed068bbb)

From the commit message and its tests: on a VSX cluster-member Virtual System, `cphaprob -a if` lists the VS's cluster
interfaces, so those addresses are cluster (virtual) addresses. They were recorded with role `member`, which made the
builder see the same address on both members. The inventory executor now records them as `cluster_virtual`; the
connected-route prefix derivation, standalone VSX and the physical context are unchanged; no schema change.

Expected effect on conflicts (inference from builder logic and the added tests, not measured on the live estate):
- Reduces: the duplicate-address family for VSX VS in enrolled clusters — `DUPLICATE_ADDRESS` and the derived
  `NEXT_HOP_AMBIGUOUS`, and any `DUPLICATE_PRIVATE_PREFIX` created because two member units carried the same VS address.
  A cluster unit now attaches a single VIP (`attaches()`: Check Point cluster units accept only `cluster_virtual`), and
  `CLUSTER_MEMBERS_DISAGREE` (b) compares consistent VIP sets.
- Does not reduce: the same VS address on two different clusters or two different VSIDs (the test
  `theSameVsAddressOnTwoDifferentClustersOrTwoVsidsIsStillADuplicate` asserts this stays `DUPLICATE_PRIVATE_PREFIX`) —
  that is a correct, expected conflict.
- Timing: stored rows change only on the next inventory collection; the topology must be rebuilt afterwards. Counts
  will not drop until both happen. Verify by comparing per-kind counts before/after, reported as counts only.

## 5. Practical triage order
1. Fix data first: 3.5, 3.6, 3.7, 3.10 (these hide real topology and cascade into 3.8/3.9).
2. Then address duplicates: 3.3, then 3.9.
3. Treat 3.1 as inventory of reused ranges; override only where the answer is known (same network → FORCE_LINK;
   different → SUPPRESS_LINK) so the remainder stays meaningful.
4. 3.2 per link by checking masks; 3.4 informational.

## 6. Limits of this guide
Per-kind live counts, the share attributable to VRFs, and the realised effect of ed068bbb are UNKNOWN. Collector-level
causes named above (which vendor leaves interface VRF empty, etc.) are hypotheses from builder logic, not verified
against vendor output.
