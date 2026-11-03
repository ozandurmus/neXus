<!-- Fable consultation 2026-11-03T12:54:35Z, model claude-fable-5-1; unaltered output -->

# Cross-review of the Argon path-analysis design — Fable, 2026-11-03

Status: review input for the merge session, not implementation authority. Every claim below was checked against the worktree at `design/path-analysis-1103`; where I could not verify, I say so. No live system was contacted, no device data read.

Reviewed: `docs/design/PATH_ANALYSIS_DESIGN_ARGON_ARTIFACT.md`. My own design (`PATH_ANALYSIS_DESIGN_FABLE.md`) is referred to as "mine" and is not restated.

---

## 1. Claims in the Argon design that are wrong or unverified against the code

| # | Argon claim | What the code says | Impact |
|---|---|---|---|
| 1.1 | Check Point management policy collection runs over the "HTTPS Web Services API" (§2.2, row 3) | The gate rows are `mgmt_cli -r true -d '<DOMAIN>' -f json show-packages …` with `transport_kind = SSH_EXEC` on the MDS (`V116__cp_policy_reads.sql:5`); the collector builds `-f json show-access-rulebase …` command strings and pages them by `limit/offset` (`CheckPointPolicyCollector.java:813-818`) | Wrong transport. No credential-boundary change, but pagination, timeouts and "details-level" behave as mgmt_cli over SSH, not as REST |
| 1.2 | Check Point inventory command list and gate ids: `fw vsx stat -v`, `cphaprob state`, `ip -6 route show table all`, gate ids `cp.ssh.*`, per-VS reads via `ip netns exec CTX0000<vsid>` | Actual constants: `vsx stat -v` (`InventoryReadPlan.java:82`), `cphaprob stat` (`:76`), `ip -4 route show table all` (`:72`), no IPv6 route read anywhere (V15 has only `cp_inventory_ip_addr_show_v6`, `V15__inventory_command_gate_entries.sql:38`). Gate ids are `cp_inventory_*` (`V15:34-102`). Per-VS reads are `bash -lc 'vsenv <vsid> && fw getifs && ip -4 route show'` (`InventoryReadPlan.java:104,146`) | Four of seven listed commands are not what the product sends. Note the per-VS route read has no `table all`, so a VS context only ever yields the main table |
| 1.3 | G7(2)/G8(1): `PolicyLookup` "does not check `rule.installOn()`" and "ignores `from`/`to` zones" | `PolicyLookup.java:196-207` evaluates `from`, `to` and `install-on` placements, returns `zone or target required` when the query omits them, and binds install-on by id equality at `:203-205` | The whole "extend PolicyLookup with fail-closed zone matching and install-on filtering" item in Stage A2 is already built. What is missing is different: the device↔install-on object-id binding (`PolicySnapshot.Target` has only `deviceId, name, context, syncStatus`, `PolicySnapshot.java:52`) |
| 1.4 | `PolicyLookup.locate` (L103-169) implements ordered layers | No method `locate`; ordered layers are `decide` at `PolicyLookup.java:157-178`. The semantics Argon describes (any layer DENIED → DENIED, all ALLOWED → ALLOWED) are correct | Citation error only |
| 1.5 | G1: "Line 113 strips `A` and `E` flags" and `show routing route` "returns the full RIB" | File is 106 lines; the qualifier skip is `PaloAltoRouteParser.java:95-96`. The parser indeed records no active flag, no metric, no next-hop kind. Whether the response contains inactive routes is **not established anywhere in the repository** (the 14E PP-2 comments at `:14-29` describe flag tokenisation only) | The gap is real; the premise is an ASSUMPTION that belongs in vendor-documentation research, not stated as fact |
| 1.6 | G4: "`V171` retires `show interface all` on PAN-OS 12+"; PAN-OS 12 plan lists `<show><high-availability><interface>ha1…` and `<show><config><running/>` as inventory reads; both PAN rows list `<show><system><info/>` as step 1 | `V171` blocks `pan_inventory_show_system_info` (`V171__pan_firewall_command_retirement.sql:14-20`) and adds `not_collected` for three reads that a worker switch may turn off (`:22-26`). `InventoryReadPlan.java:163-168` has only system-info, jobs-all, HA state, interface-all, routing-route. The configured-interface fallback parses the already-stored configuration artefact (`PaloAltoConfiguredInterfaceParser.java:43-49,69-72`), not a new device read | The "already approved in gate registry" column is wrong for three of four PAN-OS 12 entries, and step 1 of both PAN rows is a BLOCKED command |
| 1.7 | New migration "`V172`" | `V172__pan_firewall_type_version_identity.sql` exists; head is `V183` | Numbering collision; also "active migrations through V171 / baseline V167" is stale |
| 1.8 | G5 fix: "copy `/prefix` from the interface's `member` address onto `cluster_virtual` VIPs" for VSX cluster members | On a cluster-member VS there is no member address at all: `toParsedInterfaces` records the `cphaprob -a if` address bare, as `ROLE_MEMBER`, with no prefix (`InventoryCapabilityExecutor.java:846-855`), and `mergeVirtualAddresses` is only reached on the physical context (`:344`). The only prefix source on that path is the connected route in the same VS | The fix as written cannot work; the connected-route route is the only one. Separately: on a cluster-member VS the stored "member" address is in fact the cluster address — a role-vocabulary violation neither design noticed. The path engine must treat VS-context `member` rows on cluster members as VIPs |
| 1.9 | G9: `SubnetPreservingIpMasker` supports `unmaskIpOrCidr`/`unmaskHostname`; `TopologyNamePseudonymizer.maskInterfaceName` | No `unmask*` method exists in either class; `TopologyNamePseudonymizer`'s public surface is `maskClusterName`, `maskDeviceName`, `maskManagedDeviceName`, `maskDomainName`, `maskVirtualSystem`, `maskPolicyName`, `maskSerial`, `maskPayloadToken`, `maskText` (`:92-378`). The hashing claim is correct (`SubnetPreservingIpMasker.java:172`) | The reverse resolver is new code with a privacy decision attached (§7), not an extension |
| 1.10 | aiview "may trigger an on-demand `cp_inventory_collect` … per PO decisions 2026-10-04 and 2026-10-20" | AGENTS.md grants aiview CLASS_0 diagnostic reads, readiness runs, policy collection and packet analysis. The 2026-10-20 record only says operator and aiview manual submissions stay window-gated (`PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md:22-23`) | Inventory collection by aiview is not an existing permission; it is a PO decision |
| 1.11 | RBAC via `RbacEvaluator` with tokens `TOPOLOGY_PATH_READ` etc. | Live authority is `ActionRegistry` (`register(new ActionDescriptor(…))`, `ActionRegistry.java:213-220`) with snake_case action ids and default role tokens | Shape error; also see §7 for the breadth of "canonical view" |
| 1.12 | "`show-gateways-and-servers` already approved" | Correct: `V124__cp_policy_object_inventory.sql:15`. But `CpObjectInventoryParser.java:8` deliberately drops topology, so resolving CP `security-zone` objects is a parse-scope extension, and whether the object dictionary exposes zone↔interface binding is unverified | Right command, unverified payload |
| 1.13 | `device_inventory_run(… virtual_systems, not_collected)` cited to V13 | `virtual_systems` is `V29`, `not_collected` is `V171` | Minor |
| 1.14 | "Pattern established in `EstateMap.tsx`" for 3-level semantic zoom | `EstateMap.tsx` contains no zoom, grouping or collapse logic | Unverified; the estate explorer has no precedent in the frontend |
| 1.15 | Performance figures: `prepare` ≈120 ms on 16k rules, 48 bytes per trie node, <50 ms per 10-hop query, <120 MiB heap | Nothing in the repository measures these. `PolicyLookup.prepare` is already a normalise-once API (`:54-55`); "resolves groups recursively on every call" is only true for callers that do not keep `Prepared` | Unmeasured; keep as targets, not facts |

Verified correct and worth crediting: G3 (local PAN mapper maps only `rulebase/security`, `PanoramaPolicyMapper.java:127`, NAT/default rules only at `:75-78`), G6 both halves (`CheckPointIpRouteParser.java:60-65` keeps the blackhole destination without a kind; `:66-71` drops `nexthop` continuation lines), G8(2) (`PacketAnalysisCorrelationService.java:1221-1224`), `POLICY_STALE_THRESHOLD` (`:81`), G2 (router name parsed `PaloAltoInterfaceParser.java:62,87` and consumed only for vsys attribution `InventoryCapabilityExecutor.java:585-602`; `InventoryInterface` has no routing-table field, `:31-32`), `JobAdmissionService` refusal codes (`:83,104`), job-window zone (`JobWindowPolicy.java:18`), `@xyflow/react` already a dependency (`PacketTopologyTab.tsx:14`).

---

## 2. Vendor-semantic errors and false-verdict risks

**False ALLOWED**

- **PAN default rules from "architectural defaults".** Argon §4.2 step 4 applies intrazone-allow when no default-rule section is in the snapshot but `failures()` is empty. Collection completeness proves the reads succeeded, not that no `default-security-rules` override exists; for local firewalls the mapper does not collect them at all (G3). `PolicyLookup.java:13-14` already returns `UNKNOWN` here for exactly this reason. Keep `UNKNOWN` until the override section is collected for that scope.
- **Config-derived PAN-OS 12 interfaces with runtime routes.** Mixing grade-C zones with runtime FIB is acceptable only if the hop is badged; Argon does badge it. But an intrazone decision that relies on a config-derived zone equality should not reach `ALLOWED` without the override collected — same point as above, compounded.
- **CP automatic NAT order.** Argon states `show-nat-rulebase` returns manual and automatic rules "in exact evaluation order". The relative order of automatic static vs automatic hide rules, and the effect of the "translate destination on client side" and "automatic ARP" properties, are not in the repository. ASSUMPTION for A0; neither design may freeze it from memory.
- **Cluster pairing by VIP equality.** Stage 1 pairs devices that share a `cluster_virtual` address string. Address-text equality is evidence, not identity; the enrolled `cluster_member_ref` is the identity. Two unrelated clusters re-using a VIP in different sites would be collapsed into one unit and their routing tables merged.

**False DENIED**

- **`BLOCKED_ASYMMETRIC_STATEFUL_DROP` as the default asymmetric verdict.** PAN `tcp-reject-non-syn` can be disabled and zone protection has asymmetric-path options; Check Point's out-of-state drop is a global property that asymmetric estates often relax. Neither property is collected. The only defensible default is `ASYMMETRIC` + `UNKNOWN`; `BLOCKED` requires the property as collected evidence.
- **Downstream DENY after an UNKNOWN hop becomes "BLOCKED with caveat".** If the unknown hop is unknown because a NAT rule's object did not resolve, the tuple presented downstream is speculative, so the downstream DENY is conditional. Continue tracing (good), but the overall verdict stays `UNKNOWN` and downstream hops are marked `CONDITIONAL`.
- **Treating every route as FIB on PAN before G1 is fixed.** Argon's own R1. Until `is_active` exists, a PAN route lookup can select an inactive route and produce `DROPPED_NO_ROUTE`/wrong egress. Stage ordering must make A1 a hard precondition of A4.

**Model errors**

- **VSX warp links as `NEXT_VR`.** A Virtual Switch is L2: the next hop is another VS's interface address on a shared segment, which the normal next-hop resolver handles. Only a Virtual Router VS is an L3 hop with its own table. Classifying `wrp*` as an inter-table transition conflates the two. Keep it a segment (cloud until a fixture proves the pairing) in A.
- **`cluster_virtual` on PAN.** Step 5 resolves `dynamic-ip-and-port interface-address` to "`hostIp` or `cluster_virtual` VIP". The `cluster_virtual` role is Check Point-only by contract (`V13__device_inventory.sql:54-57`); PAN active/passive shares the interface address. Vocabulary conflation.
- **Source-port modelling.** `FlowHeader5Tuple.srcPort` defaults to 1024-65535 but `PolicyLookup.Query` has no source port (`:20`) and neither NAT mapper records one. Drop it from A or mark it unevaluated.
- **Ingress by reverse-path LPM.** Acceptable as an ingress heuristic; it must be labelled `INFERRED` and never reused as an anti-spoofing claim.

Correct and agreeing with mine (still ASSUMPTION until A0 documentation research): PAN pre-NAT addresses + post-NAT zones in security policy, NAT `to` zone = pre-NAT route zone, second route lookup after DNAT, CP access rulebase on pre-NAT tuple, Panorama pre → local → post NAT precedence, stateful reversal of forward NAT on the return path.

---

## 3. Argon ideas better than mine — adopt

1. **G3 local PAN NAT and default-rule mapping.** Verified gap. Without it every unmanaged PAN firewall has no NAT at all in the path engine. Belongs in A1.
2. **Persisted FIB metadata on `device_route`: `is_active`, `metric`, `next_hop_kind` (`DISCARD`, `NEXT_VR`, `CONNECTED`, `IP`).** My design kept routes uncopied but had no active flag; on PAN I would have routed over inactive routes. Adopt, as an additive migration at V184 or later.
3. **`evidence_grade` persisted on `device_interface`.** I carried it only in hop output; the run already knows `not_collected`, so persisting it is cheap and makes the grade queryable.
4. **Continue tracing past an UNKNOWN hop with a verdict lattice** (with the §2 correction). Materially better UX than stopping.
5. **Implied-rules caveat (`CP_IMPLIED_RULES_NOT_MODELED`) and App-ID caveat wording.** `PolicyLookup` already yields `application constraint` → `UNKNOWN`; Argon's badge explains it to the user.
6. **`HA_ROUTE_DRIFT` between active and standby member FIBs** as a named conflict.
7. **Bipartite segment node model with /30-/31 rendered as a direct edge.** Same model as mine, better presentation rule.
8. **Richer override record** (`link_kind`, `assumed_subnet`, mandatory `rationale`) and `SUPPRESS_LINK`.
9. **Tier-3 cross-validation against packet-analysis correlation fixtures.** Free regression signal from existing data; not a substitute for the real-environment gate.
10. **Explicit scale envelope and `executive_summary` block** in the response.
11. **Registering every new address-bearing column in `audit_redaction_policy`** (`V5__audit_redaction_policy.sql:19`, tiers 1-2). I omitted this for `topology_segment.prefix`.

---

## 4. Ideas in mine that Argon misses and that matter

1. **Policy-binding gap.** Rules with a specific `install-on` return `target context unproven` unless `query.target` equals the placement object id (`PolicyLookup.java:203-205`); `Target` carries no object uid. Both designs need a `policy_target_binding(device_id, context, policy_id, install_on_object_id)` populated at collection time, plus runtime corroboration from `cpstat -f policy fw` (`InventoryReadPlan.java:89`). Argon assumes the binding exists.
2. **PAN Advanced Routing Engine.** Logical routers are not read by `show routing route`; the correct read is a new command needing a gate row. Argon is silent; such devices would silently have empty tables.
3. **Gaia PBR tables.** `table all` returns non-main tables; the engine must evaluate `main` and flag PBR presence. Argon has `route_table` but no selection rule.
4. **Real-environment validation path.** `test security-policy-match`, `test nat-policy-match`, `test routing fib-lookup`, `ip route get` are new read-only commands that need gate rows and PO approval under the 2026-09-26 amendment. Without this stage the feature can never leave `AUTOMATED_VALIDATED`.
5. **A0 vendor-documentation stage before freeze.** Argon asserts "per official PAN-OS Day-in-the-Life semantics" without a research deliverable; the vendor-semantics law requires one for safety-critical ordering.
6. **Self-masking response type** (pattern of `IpSearchResponse`, `PrivacyMaskingResponseBodyAdvice.java:62-70`) and reuse of key names the advice already masks, so the policy sub-object is not masked twice and no key escapes. See §7.
7. **`path_query_audit` with masked query + verdict only**, via `AuthzDecisionRepository` as `PolicyLookupController.java:50-57` does. Argon audits overrides but not queries.
8. **Query rate limit** in the shape of `PolicyLookupController.java:80-88`.
9. **Overrides that no longer match any id are reported, never silently dropped.**
10. **Opaque ids for cloud/segment nodes.** Argon names clouds `cloud:198.51.100.254`; ids are not masked, so the raw next hop leaks through every id field.

---

## 5. Disagreements the merged design must resolve

| # | Topic | Argon | Fable | Recommended resolution and why |
|---|---|---|---|---|
| 5.1 | Zone/target filtering in the rule engine | Build it in A2 | Already present | Resolved by code (§1.3): drop from A2; add the binding table instead |
| 5.2 | Verdict after an UNKNOWN hop | Continue; downstream DENY → `BLOCKED` with caveat | Overall never `ALLOWED` if any hop UNKNOWN | Continue tracing; overall `UNKNOWN`; downstream hops `CONDITIONAL`; `BLOCKED` only when every upstream hop is decided. Prevents false DENIED from a speculative tuple |
| 5.3 | Asymmetric return | `BLOCKED_ASYMMETRIC_STATEFUL_DROP` | Warning | `ASYMMETRIC` + `UNKNOWN`; `BLOCKED` only if the out-of-state property is collected (new management read, PO decision) |
| 5.4 | PAN default rules | Architectural default when snapshot complete | `UNKNOWN` | `UNKNOWN` until `default-security-rules` are collected for that scope (G3 for local, already present for Panorama); then verdict from the collected override |
| 5.5 | Topology persistence shape | `topology_snapshot.graph_json` JSONB | Relational nodes/segments/attachments with opaque FKs | Relational. `audit_redaction_policy` is per column; a JSONB blob of addresses is re-captured by `fn_audit_capture` on every insert and cannot be classified field by field. Keep a small `manifest_json` of opaque run/policy ids only |
| 5.6 | aiview query input | Reverse pseudonym resolver | Not addressed | Needed, otherwise aiview cannot ask anything. Implement as a bounded server-side map built while masking the current snapshot; HMAC collisions are acknowledged in `SubnetPreservingIpMasker.java:179`, so a masked value that resolves to more than one raw value returns `AMBIGUOUS_PSEUDONYM`. Record as a PO decision: it turns the masked view into a queryable oracle of structure (not of values) |
| 5.7 | RBAC breadth | Operator gets canonical view; aiview may trigger inventory collection; canonical export for admins | `security_admin` default, aiview masked | Follow the `PACKET_ANALYSIS_RAW_VIEW` precedent: canonical view `security_admin` (optionally `operation_admin`), operator masked, aiview masked; no new collection rights for aiview without a PO decision; export masked only in A |
| 5.8 | VSX warp | `NEXT_VR` | Cloud until measured | Fable for A; revisit in D with a measured fixture |
| 5.9 | Cluster pairing key | VIP equality | `cluster_member_ref` + HA facts | Enrolled ref is identity; VIP equality corroborates only (identity law) |
| 5.10 | ECMP/branch limits | 8 ECMP branches | 4 fan-out / 16 branches | 8 fan-out per hop, 16 total branches, `ecmpTruncated` flag |
| 5.11 | Frontend scope in A | Path trace + estate explorer with semantic zoom | Path-local diagram only | Brief puts the topology map UI in D. Estate explorer out of A |
| 5.12 | Stage ordering | Parsers → engine → builder → path → API → UI | A0 docs → parsers → builder → engine → API/UI → validation gates | Insert A0 first and a validation-gate stage last; make A1 a hard precondition of the path engine |
| 5.13 | Migration number | V172 | — | Next free number at implementation time (≥ V184) |

---

## 6. Scope and size realism of Argon's Phase A plan

Argon totals roughly 25-27 engineering days across A1-A6. Assessment:

- **A1 (3-4 days)** covers six parser changes, a migration, redaction registration and tests. Plausible only if the Gaia multipath and VSX `cphaprob` fixtures already exist; they do not (ASSUMPTION in both designs). A masked live sample must be obtained before the parser freeze, which is PO time, not engineer time.
- **A2 (4-5 days)** over-counts by including zone/target filtering that exists (§1.3) and under-counts by omitting the binding table. The `RoaringBitmap` rule accelerator is premature optimisation: `PolicyLookup` is already budgeted and partitioned; measure a cached `Prepared` first.
- **A3-A4 (9-10 days)** are realistic for builder and engine, but A4 includes return-path and asymmetry analysis whose verdict semantics are unresolved (§5.3); size it as warning-only.
- **A5 (3 days)** adds reverse pseudonym resolution to two privacy classes with no existing reverse API. Three days is optimistic once collision handling and the zero-leakage test are included.
- **A6 (5 days)** for a path screen *plus* a three-level semantic-zoom estate map with server-side layout is not credible and is Phase D scope.
- **Missing entirely:** vendor-documentation research (≈3 days), the real-environment validation gates and PO-run comparison, advanced-routing read and gate, and policy-binding persistence.

Realistic Phase A with the merged scope: 30-35 engineer-days, status capped at `AUTOMATED_VALIDATED` until the PO-run comparison stage completes.

---

## 7. Privacy, raw-evidence and aiview masking concerns

**Argon design**

- **Response keys.** `operational_unit_name`, `members[].hostname`, `ingress.address`, `nat_decision.translated_source`, `matched_sources`, `route_matched.next_hop` depend on `PrivacyMaskingResponseBodyAdvice` recognising each key. Any key outside its list leaks for aiview. A self-masking response type removes that dependency; the Tier-4 zero-leakage test is necessary but should not be the only defence.
- **Raw values inside identifiers.** `cloud:198.51.100.254` and `rc:` ids derived from names are not masked. Use opaque ids; carry the value in a masked field.
- **`graph_json` JSONB.** Duplicates every address and hostname into a second table and into `audit_log` on each insert via the audit trigger pattern (`V13:98-112`). Column-tier redaction cannot classify fields inside a blob. Relational storage with opaque FKs avoids the duplication.
- **Reverse pseudonym resolver.** Server-side only, which is right, but it is a new de-pseudonymisation capability and must be a PO decision; its map must live in memory per snapshot, never persisted, and ambiguous collisions must refuse.
- **Object-name autocomplete for aiview** against `CpObjectInventory` would serve raw object names unless it serves `PolicyPrivacy`-masked names and reverse-resolves them.
- **Canonical export for `operation_admin`/`operator`.** A downloadable artefact with raw identities is exactly the "shareable artefact" the sensitive-identity law restricts; masked-only in A, canonical export a separate PO decision.
- **`query_id` suggests stored results.** If `PathAnalysisResponse` is persisted it contains raw addresses and rule names; the design does not say. Must be decided (my P-2).
- **aiview inventory-collect trigger** (§1.10) is a permission expansion presented as existing.

**Fable design (mine), self-critique**

- `topology_segment.prefix` and `device_interface.routing_context` are new persisted values; I did not state their `audit_redaction_policy` registration. Adopt Argon's practice.
- `topology_link_override.reason` free text may contain raw names; it must be hidden or masked for aiview. Neither design said so.
- I did not address how aiview enters a query; §5.6 resolves it.

**Both**

- Fixtures and previews use `192.0.2.x`, `198.51.100.x`, `203.0.113.x` only; Argon's example payload complies.
- Neither design persists raw vendor responses; the raw-evidence law is satisfied by both. The genuinely new persisted values are route metadata, interface grade/router name, segment prefixes and (Phase B/C) LB/ACI addresses, each a PO decision as both designs state.

---

Ürün Sahibi için kısa not: Çapraz inceleme yukarıda, İngilizce. En kritik düzeltmeler: Argon'un "PolicyLookup zone/install-on kontrolü yapmıyor" iddiası koda göre yanlış (zaten var), CP yönetim toplama HTTPS değil mgmt_cli/SSH, V171 `show interface all`'ı değil `show system info`'yu emekliye ayırıyor ve V172 numarası dolu. Argon'dan alınması gereken en değerli bulgular G3 (yerel PAN NAT eksik) ve rota FIB meta verisi (`is_active`). Birleştirmede çözülmesi gereken 13 madde §5'te.
