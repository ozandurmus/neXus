# Path analysis — UNKNOWN reduction plan (2026-11-05)

Status: DRAFT — DO NOT FREEZE. Items marked **implemented** are deterministic engine choices inside the frozen
contracts and are recorded as the 2026-11-05 amendment of `PATH_ANALYSIS_DESIGN.md` §4 (engineer decision under the
PO freeze delegation, `PO_DECISION_RECORD_2026_11_04_DEV_MODE_DELEGATIONS.md` decision 3). Items marked **proposed**
are not authorized by this document: each names the evidence or decision it needs first.

Authority: `PATH_ANALYSIS_DESIGN.md` (FROZEN) and `PATH_ANALYSIS_VENDOR_SEMANTICS.md` (FROZEN, A0). Where this plan and
those differ, they win. Engine: `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/pathanalysis/`.

## 0. Input and method

Live estate, 9 computed test queries between interface addresses of different segments, all `UNKNOWN`:
4 `EXITS_KNOWN_TOPOLOGY`, 2 `NO_ROUTE`, 2 `AMBIGUOUS_INGRESS`, 1 `UNKNOWN_HOP`.

This session had no access to the live system. Every cause below is derived from the code and is a **hypothesis**
until the masked check named with it confirms it. The checks read counts, conflict kinds and reason codes through
the aiview projection only (AGENTS.md masking law); none needs a device command.

### 0.1 A finding common to all nine queries

The queries used addresses that enrolled units hold themselves (interface addresses). Phase A models **transit**
traffic (design §5). With a unit's own address:

- as the **source**, the engine starts at every unit attached to that segment as if a host there sent the packet,
  including the unit that owns the address. Traffic a firewall originates is governed by other rules (Check Point
  implied rules; Palo Alto firewall-originated traffic), which are not modelled;
- as the **destination**, the packet is "delivered" on the owner's segment, although it ends at the unit itself
  (to-the-box traffic: management-plane handling, implied rules, not modelled).

So these nine queries test a case the engine does not claim to answer, and they also exercise the edges (default
routes, VIPs, duplicate prefixes) harder than host traffic does. **Implemented:** a result whose source or destination
is an address an enrolled unit holds carries the caveat `SOURCE_IS_UNIT_ADDRESS` / `DESTINATION_IS_UNIT_ADDRESS`
(never plain). **Recommended re-run:** the same segment pairs with a host address inside each segment that no unit
holds (the masked pickers show segment prefixes), plus an ingress hint where a source prefix exists on several units.

## 1. `EXITS_KNOWN_TOPOLOGY` (4 queries)

**Mechanism.** `PathEngine.step()` returns `Exit` when the route's next hop resolves (`NextHopResolver`) to
`ToCloud` or `Unheld`: no enrolled unit is attached at exactly that address on the egress segment. `explore()` ends the
branch `EXITS_KNOWN_TOPOLOGY`; `branch()` makes it `UNKNOWN`, or `ALLOWED_TO_EDGE` in edge mode (D-4).

**Likely real-estate causes.**

| # | Cause | Code path | Masked check |
|---|---|---|---|
| E-a | The longest match is the **default route** toward an unenrolled core/WAN router. Two firewalls on different segments usually reach each other through such a router. | `RouteLookup` picks `0.0.0.0/0`; next hop unheld → cloud | New hop note `EXIT_VIA_DEFAULT_ROUTE` on the exiting hop |
| E-b | The next hop **is** an enrolled unit, but that unit has no attachment there: a Check Point cluster VIP without a prefix (`ADDRESS_PREFIX_UNKNOWN`). The VIP prefix comes only from a connected route with protocol `connected` (`proto kernel`): `TopologyBuilder.isConnected`, worker `applyUniqueConnectedRoutePrefixes`. PR-1 measured that a Gaia connected row may read `dev X proto 7 scope link` (stored `static`, kind UNKNOWN), which neither counts. A VIP on a different subnet than the members has no connected route at all. | builder drops the candidate → resolver finds no holder → `Unheld` | Topology conflicts: count of `ADDRESS_PREFIX_UNKNOWN` by context type (physical / VS); new branch warning `DESTINATION_IN_KNOWN_TOPOLOGY` |
| E-c | **VSX**: a virtual system reached over a warp link (`wrp*`) stays a `VSX_WARP` cloud (design §4, frozen until a measured fixture proves the pairing). A VSX cluster VS whose `cphaprob -a if` addresses got no prefix (as E-b, per VS) has no attachment at all. | warp segment → cloud; or no attachment | New hop note `EXIT_VIA_VSX_WARP`; `ADDRESS_PREFIX_UNKNOWN` count on VS contexts |

**Implemented (diagnostics, no verdict change).** Hop note `EXIT_VIA_DEFAULT_ROUTE` (the exit route is `/0`), hop
note `EXIT_VIA_VSX_WARP` (the exit attachment is on a `VSX_WARP` segment), branch warning
`DESTINATION_IN_KNOWN_TOPOLOGY` (the destination lies on a segment an enrolled unit is attached to: something between
is not enrolled or not attached). Together they tell E-a, E-b and E-c apart on each of the four results.

**Proposed.**
- P-E1 (E-b): count a no-`via` row with an interface and `scope link` as connected for the VIP prefix and for segment
  corroboration. Needs: the parser to keep `scope`, a masked real fixture of the `proto 7` connected row, and a
  freeze decision on the A1 G4/G5 contract ("a `proto kernel` row without `via` is CONNECTED; any other row's kind is
  UNKNOWN"). Risk to settle first: a Gaia interface static route (`nexthop gateway logical`) may print the same shape
  and must not become a VIP prefix.
- P-E2 (E-c): warp pairing stays a cloud until the measured fixture design §4 asks for exists.
- E-a is not reducible by the engine. Leaving on a default route into an unenrolled router is the edge of the known
  topology by definition; treating it as anything else would invent the router's routing. Levers: edge mode for the
  question "up to the edge" (D-4), enrolling the router (Phase D node kinds), or a `FORCE_LINK` override only where the
  two ends really share a link.

**Expected effect.** No verdict change from the diagnostics. E-b exits become traversals once VIPs attach (P-E1).
E-a exits stay `UNKNOWN` (`ALLOWED_TO_EDGE` in edge mode) by design.

## 2. `NO_ROUTE` (2 queries)

**Mechanism.** `choices()` with an empty longest-prefix result (`RouteLookup`) ends the branch `NO_ROUTE`.

**Likely real-estate causes.**

| # | Cause | Code path | Masked check |
|---|---|---|---|
| N-a | Routes **withheld**: a cluster unit whose single active member is not provable has an empty table (`ACTIVE_MEMBER_UNPROVEN`, design §4 step 1). The hop said so, but the branch and overall reason said `NO_ROUTE`. On VSX, a VS without its own HA fact falls back to the physical role (`TopologyBuilder.haRole`); under VSLS both members may report physical `ACTIVE`, giving two actives (vendor semantics of that fallback: UNKNOWN). | builder `routes = List.of()` | Reason now `ACTIVE_MEMBER_UNPROVEN`; count of VS contexts without their own HA fact |
| N-b | **Empty table** in that context: nothing collected or attributed there. Palo Alto attributes a virtual router's routes to a vsys through that vsys's interfaces (PM-3); a router with no such interface lands in `physical`. With PAN-OS 12 configuration-derived interfaces lacking a routing context, the routes and the interfaces can end up in different contexts. A Check Point VS whose per-VS route read came back empty has the same effect. | node exists (from interfaces), `routes` empty | Reason now `ROUTE_TABLE_EMPTY`; `INTERFACE_WITHOUT_CONTEXT` conflict count |
| N-c | A real table without a default route and without a covering route (internal firewall). Stays `NO_ROUTE`: a RIB row is not a FIB fact (A0 §5b), so "dropped" is not claimed. | lookup empty | Reason stays `NO_ROUTE` |

**Implemented (diagnostics, no verdict change).** The branch and overall reason of a `NO_ROUTE` branch is
`ACTIVE_MEMBER_UNPROVEN` when the unit's routes were withheld, `ROUTE_TABLE_EMPTY` when the unit's table is empty; the
termination stays `NO_ROUTE`.

**Proposed.** P-N1: measure (counters only) whether VSLS VS contexts lack their own HA fact before touching the
physical fallback; any change to it needs vendor evidence (A0-style) and a freeze decision. P-N2: if N-b is PAN
attribution, fix the attribution in the worker under its own build (inventory semantics, not the engine).

**Expected effect.** Same `UNKNOWN` count; the two results now name the cause, which points at collection (N-a/N-b)
instead of routing.

## 3. `AMBIGUOUS_INGRESS` (2 queries)

**Mechanism.** `ingress()` without a hint: (a) several equally specific segments contain the source — the same
private prefix on units no route corroborates is never auto-joined (`DUPLICATE_PRIVATE_PREFIX`, design §4); (b) no
segment holds the source and several units tie on the most specific non-default route toward it into a cloud
(F§4.2). Before this change both returned zero branches.

**Likely real-estate causes.** Shared transit or user segments where the firewalls do not route at each other's
addresses (each points at a third-party gateway), so nothing corroborates the join; HA members enrolled without a
`cluster_member_ref` (each holds the same addresses as its own unit — check enrolment); separate VSs that really reuse
a prefix (genuinely separate). Masked check: `DUPLICATE_PRIVATE_PREFIX` conflicts with resolution `OPEN`, and whether
the two candidates are members of one cluster.

**Implemented (case a only).** Every candidate is traced, as for one segment shared by two firewalls (design §4
amendment 2026-11-04). The verdict stands only when every candidate was traced in full and all of them give the same
decided verdict; the result then carries the caveat `INGRESS_CANDIDATES_AGREE` and the ingress status stays
`AMBIGUOUS_INGRESS` (never `RESOLVED`). Otherwise the result is `UNKNOWN (AMBIGUOUS_INGRESS)` as before, now with each
candidate's branches visible. Nothing is joined and no candidate is chosen. Case (b) is unchanged: F§4.2 states that
result in so many words.

**Rejected.** Picking the tied segment on which a unit holds exactly the source address: that holder is the firewall
itself (§0.1, from-the-box), duplicate private prefixes can repeat addresses, and it would only help unit-address
queries.

**Proposed.** P-A1: where the PO knows two duplicate segments are one link, a `FORCE_LINK` override (security_admin,
D-8) joins them with an audit trail. P-A2 (UI): offer the candidates as the ingress picker (the response already
lists them).

**Expected effect.** An ambiguous query gets a decided verdict only where it does not depend on the ingress (for
example every candidate denies); otherwise unchanged.

## 4. `UNKNOWN_HOP` (1 query)

**Mechanism.** A `DELIVERED` branch with at least one hop whose own verdict is `UNKNOWN` (§5 lattice). The result
already names the hop (`executive_summary.unknown_hops`) and its reason list.

**Likely causes, in the engine's order.** Stale policy; unit facts (`ACTIVE_MEMBER_UNPROVEN`,
`RELATIONSHIP_INCONSISTENT`); policy binding (`POLICY_BINDING_UNPROVEN`, `POLICY_BINDING_AMBIGUOUS`, and
`POLICY_CONTEXT_UNPROVEN` for every Check Point VSID — design §5 amendment, no proven VSID↔VS-target relation);
rule-level `UNKNOWN` (install-on unresolved, earlier sections not analysed — the A0 hygiene items); route taints
(`ROUTE_ACTIVE_UNKNOWN`, `ROUTE_SELECTION_AMBIGUOUS`, `ROUTE_TABLE_UNPARSEABLE`, `CP_ROUTE_AFTER_NAT_UNKNOWN`);
`CP_ACCESS_NAT_ORDER_UNKNOWN`; `security-zone` objects (D-6).

**Improvement.** None in the engine: each reason is a frozen fail-closed rule. The levers are upstream — the A0
"Repository impact" hygiene items, and new evidence for the VSID binding. First step: read that hop's masked reason
list and record which one it is.

## 5. Unresolvable next hops (not among the nine reasons)

`NextHopResolver` returns `Unreachable` when the next hop lies on none of the unit's segments (an unattached unit, or a
recursive next hop that is not on-link) and `Ambiguous` when several units hold it (`NEXT_HOP_UNREACHABLE` /
`NEXT_HOP_AMBIGUOUS`, both terminations). Resolving a recursive next hop through the unit's own table would assume RIB
recursion equals forwarding, which A0 §5b does not establish (the FIB read is A6, D-7). **Proposed** for A6 only.

## 6. Tests (implemented)

- `PathEngineGoldenTest`: `ambiguous-ingress` now traces both candidates and stays `UNKNOWN (AMBIGUOUS_INGRESS)`
  (one asymmetric, one allowed); new `ambiguous-ingress-candidates-agree` (SSH denied behind either gateway →
  `DENIED`, caveat, ingress still `AMBIGUOUS_INGRESS`); new `exit-destination-in-known-topology`; `exits-known-topology`
  carries `EXIT_VIA_DEFAULT_ROUTE`. Golden text updated by hand.
- `PathEngineTest`: `NO_ROUTE` on an unproven cluster reports `ACTIVE_MEMBER_UNPROVEN`; an empty table reports
  `ROUTE_TABLE_EMPTY` while a table without a covering route stays `NO_ROUTE`; a warp exit is named
  `EXIT_VIA_VSX_WARP`; unit addresses carry `SOURCE_IS_UNIT_ADDRESS` / `DESTINATION_IS_UNIT_ADDRESS` and leave the
  verdict unchanged, a host address stays plain.

## 7. Validation and follow-up

- Status stays `AUTOMATED_VALIDATED` (design §7: A6 is the real-environment gate).
- After deployment, re-run the nine queries as aiview and record, per query, only the verdict, the reason and the new
  notes/warnings (relationship level, masked). Then run the recommended host-address set (§0.1).
- UI: `pathWords.ts` has no words yet for the new codes; the screen shows an unknown code as the code (fail closed).
  Adding the words is a separate UI change with its own frontend tests.
