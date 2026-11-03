# Architecture brief — network topology and source-to-destination path analysis

Status: design session input, 2026-11-03. Requested by the Product Owner (PO).
Two independent designs are produced from this brief (Fable, Argon), then cross-reviewed, merged, and the
irreversible parts go to the decision council. No code is written in this session.

## 1. Goal

Give neXus the capability that differentiates Tufin: answer "can traffic from A to B on port/protocol P flow,
which devices does it cross, and exactly which rule / NAT / load-balancer translation / fabric contract decides
at each hop?", and draw the topology that answer lives on.

Tufin's edge, as the PO names it:
- It reads load balancers (VIPs, pools, members, SNAT) and maps VIP → real servers inside the path.
- It reads Cisco ACI (tenants, VRFs, bridge domains, EPGs, contracts, filters, L3Outs) and evaluates contracts.

## 2. What neXus already has (read the code; do not assume)

- Policy collection and normalized snapshots for Check Point management (access + NAT layers, inline layers,
  objects) and Palo Alto Panorama / local firewall:
  `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/policy/CheckPointPolicyMapper.java`,
  `CheckPointPolicyCollector.java`, `ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/policy/PanoramaPolicyMapper.java`,
  `PolicySnapshot.java` (model), `CpObjectInventory.java`.
- A rule query engine (match rules against source/destination/service expressions):
  `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyRuleQuery.java`, `PolicyLookup.java`,
  `PolicyQueryService.java`, hit counts (`PolicyHitCounts`), hygiene (`PolicyHygiene.java`).
- Global IP search: `ui2/integration-tests/.../search/GlobalIpSearchTest.java` and its service code.
- Device configuration collection/parsing (Gaia config incl. interface and static route lines, FortiGate):
  `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/`.
- A job engine with capability gates, CLASS_0 read-only diagnostics, per-device rate limiting and job windows:
  `ui2/job-engine/`, gate registry migrations under `ui2/service/src/main/resources/db/migration/`.
- Masked projection for the aiview persona; RBAC roles; frontend in React/MUI (`ui2/frontend/src/screens/`).
- Inventory of devices (Check Point gateways/clusters/VSX, management servers, Palo Alto, FortiGate, others).

What it does NOT have: runtime routing tables, interface/zone inventory as a model, NAT evaluation, topology
graph, load-balancer or ACI connectors, router/L3 switch connectors, a path simulator, a topology UI.

## 3. Constitutional constraints (AGENTS.md — binding)

- Raw-evidence law: parse minimum semantics in memory, discard raw responses. Path analysis needs routes,
  interface addresses, NAT and LB member addresses as *values* — this conflicts with today's default. The design
  must state exactly which derived values are persisted, where, under which RBAC role, with what masking for
  aiview, and propose this as a PO decision (it is NOT decided).
- Sensitive identity reporting law and identity law (identifiers opaque; no invented equivalences).
- Diagnostic-path law: reuse the existing authenticated transport and job engine; any new credential boundary
  (F5 iControl REST, APIC REST, router SSH) needs its own security/command review.
- Read-only only: all collection is CLASS_0. No device changes.
- UNKNOWN / fail-closed: a path hop that cannot be evaluated is UNKNOWN, never assumed allowed or denied.
- Vendor semantics law: Check Point / Palo Alto behaviour must follow vendor semantics (NAT order, rule
  matching, implicit cleanup, inline layers, zones, VSX contexts, clusters).

## 4. Phasing the PO accepted in principle

| Phase | Scope |
|---|---|
| A | Check Point + Palo Alto only: collect interfaces/zones, runtime routing table, NAT; single-VRF path analysis across firewalls using the existing rule engine |
| B | First load-balancer connector (vendor still to be confirmed by the PO; assume F5 BIG-IP as the reference) |
| C | Cisco ACI connector via APIC |
| D | Routers/L3 switches, multi-VRF, topology map UI, change design ("what rule where to open this") |

Design Phase A in implementable detail. Design B/C/D only to the level of: where they plug in, what the model
must already accommodate so A is not thrown away, and the main risks.

## 5. What each design must contain

1. Data model: devices, interfaces, zones, subnets, VRFs/routing contexts, routes, NAT rules, links, and how
   B/C extend it (LB virtual server → pool → member, SNAT; ACI tenant/VRF/BD/EPG/contract/filter/L3Out).
   Versioning/snapshots and freshness per source.
2. Collection: for Check Point (gateways, clusters, VSX virtual systems) and Palo Alto — the exact read-only
   commands or API calls for interfaces, routes and NAT, which existing transport carries them, job/gate shape,
   cadence, rate limits.
3. Topology construction: inferring links from shared subnets, handling clusters/VIPs, unknown segments
   ("clouds"), manual link overrides, conflicts.
4. Path engine: hop-by-hop algorithm — ingress determination, longest-prefix route lookup per routing context,
   NAT (pre/post rule order per vendor), rule evaluation via the existing engine, LB and ACI hooks, return path /
   asymmetry, loops, ECMP, UNKNOWN propagation. Output contract (per-hop verdict + deciding rule/translation).
5. UX: how the user asks the question, how the answer and the path are shown, "who blocks this", how the map
   is drawn at enterprise scale (thousands of devices), and what the aiview (masked) view shows.
6. Privacy/storage proposal with options for the PO (see §3), plus RBAC.
7. Scale and performance (16k-rule policies exist; hundreds of firewalls), caching, recomputation triggers.
8. Testing strategy: fixtures, vendor-semantic golden cases, how correctness is proven before claims are shown.
9. Risks, open questions, and a stage plan for Phase A with rough size per stage.

## 6. Output

Write one Markdown design document. Be concrete; cite repository files you read. Mark every assumption you
could not verify in the code as ASSUMPTION. Do not read real device data; do not contact any live system.
