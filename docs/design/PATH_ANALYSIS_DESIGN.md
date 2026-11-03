# Path analysis and topology — merged design

Status: DRAFT — DO NOT FREEZE. Merge of two independent designs and their cross-reviews, 2026-11-03.
Not implementation authority until A0 (vendor semantics) is frozen and the PO decisions in §8 are recorded.

Inputs (all unaltered, in this directory):
- `PATH_ANALYSIS_ARCHITECTURE_BRIEF.md` — the brief.
- `PATH_ANALYSIS_DESIGN_FABLE.md` — Fable design.
- `PATH_ANALYSIS_DESIGN_ARGON.md` (session log) and `PATH_ANALYSIS_DESIGN_ARGON_ARTIFACT.md` — Argon design.
- `PATH_ANALYSIS_CROSS_REVIEW_FABLE.md` (Fable on Argon), `PATH_ANALYSIS_CROSS_REVIEW_ARGON.md` (Argon on Fable).

Where this document says "see F§x" / "see A§x" it adopts that section of the Fable / Argon design as written,
with the corrections listed here. Where they disagree, §6 records the resolution.

## 1. Starting point (corrected brief)

The brief understated what exists. Both designs verified independently:
- Runtime interfaces, addresses (`member` / `cluster_virtual`), zones (PAN), MACs, HA roles and runtime routes per
  context are already collected by `inventory_collect` and persisted (`V13`, `V17`, `V20`, `V29`, `V148`, `V171`),
  indexed for IP search (`V127`), and redacted in audit (`V153`).
- NAT rulebases (CP `CP NAT rulebase`, PAN pre/post NAT) are already in `PolicySnapshot`; NAT is skipped by
  `PolicySnapshot.outsideSecurityEvaluation` today.
- `PolicyLookup` / `PolicyNormalization` are a budgeted three-valued first-match engine with CP ordered + inline
  layers, PAN pre/post phases, `from`/`to`/`install-on` placement checks.

Phase A is therefore "bind existing evidence into a graph and a hop engine, close parser gaps, add NAT evaluation",
not "build collection". No new credential boundary in A.

## 2. Gaps Phase A must close first (verified in code by at least one design and not refuted)

| # | Gap | Where |
|---|---|---|
| G1 | PAN routes: active flag, ECMP flag, metric discarded; inactive RIB routes would be routed over. Whether `show routing route` returns inactive routes is an ASSUMPTION for A0 | `PaloAltoRouteParser.java:95-96` |
| G2 | PAN interface → virtual router parsed but not persisted | `PaloAltoInterfaceParser.java:62,87`; `InventoryInterface.java:31` |
| G3 | Local (non-Panorama) PAN mapper maps only security rules: no NAT, no default rules | `PanoramaPolicyMapper.java:127` |
| G4 | CP `blackhole` stored as a route without a kind; ECMP `nexthop` continuation lines dropped | `CheckPointIpRouteParser.java:60-71` |
| G5 | VSX cluster-member VS addresses come from `cphaprob -a if` bare, without prefix, recorded as `member` though they are cluster addresses; only the connected route in the same VS can give the prefix | `InventoryCapabilityExecutor.java:846-855,344` |
| G6 | Per-VS route read is `ip -4 route show` (main table only), physical is `table all` | `InventoryReadPlan.java:104,146` |
| G7 | CP install-on binding: `PolicyCollectionRepository.targets` does not read `policy_package_target.target_uid`; `install-on` extras hold `PolicySnapshot.ref(meta.id(),"object",uid)`, not the raw uid; `PolicyLookup` returns `target context unproven` (UNKNOWN) instead of skipping a rule installed on a different gateway | `PolicyCollectionRepository.java:226-232`; `CheckPointPolicyMapper.java`; `PolicySnapshot.java:81-83`; `PolicyLookup.java:203-206` |
| G8 | PAN NAT and security `target-selectors` / `target/negate` not honoured per device | `PanoramaPolicyMapper.java:183,225-227` |
| G9 | Masked addresses (`SubnetPreservingIpMasker`) hash each prefix independently: LPM cannot run on masked values; aiview has no way to enter a query | `SubnetPreservingIpMasker.java:172,179` |
| G10 | PAN Advanced Routing Engine (logical routers) not read by `show routing route`; needs a new gated read (ASSUMPTION for A0) | F§2.2 |

`LocalFirewallPolicyService` is an offline fallback only when no Panorama exists in the estate; it is not an
out-of-sync fallback (correction to F§4.4). A Panorama-managed firewall marked out of sync → hop `UNKNOWN`.

## 3. Data model

Adopt F§1.1 relational model (`topology_snapshot`, `topology_node`, `topology_segment`, `topology_attachment`,
`topology_conflict`, `topology_link_override`, `path_query_audit`) with these changes:
- Routes stay in `device_route` and are not copied. Additive columns on `device_route`: `is_active`, `is_ecmp`,
  `metric`, `next_hop_kind` (`IP | CONNECTED | DISCARD | NEXT_VR`). On `device_interface`: `routing_context`,
  `evidence_grade` (`RUNTIME | CONFIG_DERIVED`). (A§1.2, migration number = next free, ≥ V184.)
- `policy_target_binding(device_id, context, policy_id, install_on_ref)` populated at policy collection, where
  `install_on_ref = PolicySnapshot.ref(meta.id(), "object", target_uid)`; corroborated by `cpstat -f policy fw`.
- Override record from A§1.2: `link_kind`, optional `assumed_subnet`, mandatory `rationale` (masked for aiview).
- No JSONB graph blob (it cannot be redacted per field and is re-captured by the audit trigger). A small manifest
  of opaque run/policy ids only.
- Every new address- or name-bearing column registered in `audit_redaction_policy` (tier 2).
- All node, segment and cloud ids are opaque. No address or name inside an id.
- Identity: cluster unit = enrolled `cluster_member_ref` + HA facts; VIP equality corroborates, never identifies.
- B/C/D reserved node kinds and tables: F§1.4.

## 4. Topology construction

F§3 steps with A§3 additions: `HA_ROUTE_DRIFT` conflict when active/standby FIBs differ; `/30`–`/31` segments drawn as
a direct edge; PAN HA pairs attach through `member` addresses (PAN has no `cluster_virtual` role); CP VIP prefix
from the connected route in the same context; VSX `wrp*` links stay cloud segments until a measured fixture proves the
pairing (Virtual Switch is L2, only a Virtual Router VS is an L3 hop). Duplicate private prefixes are never auto-joined.
Overrides that no longer match are reported, never dropped. Gaia non-main tables → hop flag `PBR_PRESENT`, main table
evaluated.

## 5. Path engine

Query, ingress, output contract: F§4.1, F§4.2, F§4.7, plus A's `executive_summary` block. IPv4 only. Source port not
modelled. Inferred ingress is labelled `INFERRED`.

Per-hop order (every step ASSUMPTION until A0 freezes it from official vendor documentation):
- **Check Point:** access rulebase matched on the pre-NAT tuple (drop here stops the hop) → NAT first match (manual
  then automatic as collected; destination translated on the client side by default, the property being shown on the
  hop as an assumption until collected) → route lookup on the post-DNAT destination → source NAT for the next hop.
  `security-zone` objects in a rule → `UNKNOWN` until the gateway topology parse (decision D-6) exists. Implied rules →
  `CP_IMPLIED_RULES_NOT_MODELED` note.
- **Palo Alto:** ingress zone → route lookup on the original destination (provisional egress zone) → NAT first match
  (pre → local → post, honouring `target-selectors`/`target/negate`) → second route lookup after DNAT → security on
  pre-NAT addresses with post-NAT egress zone, honouring `target-selectors`. Default rules: evaluate collected
  `PAN_PRE_DEFAULT`/`PAN_POST_DEFAULT` override sections when present for that scope; otherwise `UNKNOWN`
  (never an assumed vendor default). `application-default` services: decision D-5.

Verdict lattice:
- A hop that is `UNKNOWN` taints the branch; tracing continues; downstream hops are marked `CONDITIONAL`; the overall
  verdict is `UNKNOWN` unless every upstream hop is decided. `DENIED` overall only when the blocking hop and every
  hop before it are decided.
- ECMP: up to 8 branches per hop, 16 in total, `ecmpTruncated` flag. Mixed branch verdicts → `PARTIAL`.
- Return path: computed. If the return crosses a stateful unit the forward path did not (or skips one it did),
  the result is `ASYMMETRIC` and the overall verdict cannot be `ALLOWED` (it becomes `UNKNOWN` with an
  "out-of-state drop likely" warning). `DENIED` for asymmetry only when the out-of-state property is collected
  evidence (later work).
- Exit into a cloud: `EXITS_KNOWN_TOPOLOGY` → `UNKNOWN`, or `ALLOWED_TO_EDGE` when the user asks "up to the edge"
  (decision D-4).
- Stale or out-of-sync policy on a hop: hop `UNKNOWN (STALE_OR_OUT_OF_SYNC_POLICY)`, last-known rule shown as context.

B/C hooks: F§4.6 / A§1.5 (`HopSemantics` strategy per node kind).

## 6. Disagreements and resolutions

| Topic | Resolution |
|---|---|
| CP step order | Access on pre-NAT first (Argon); client-side destination translation caveat (Fable) |
| Asymmetric return | Never clean `ALLOWED`; `UNKNOWN` + warning; `DENIED` only with collected property (merge of both) |
| UNKNOWN mid-path | Taint and continue (Argon); overall `UNKNOWN`, downstream `CONDITIONAL` (Fable) |
| PAN default rules | Use collected override sections; else `UNKNOWN` (both reviews converge) |
| Rule-engine zone/install-on | Placement checks exist (Fable correct); install-on skip semantics and binding are broken (Argon correct) → fix in A2 |
| Persistence | Relational (both reviews agree) |
| Cluster identity | `cluster_member_ref`, VIP corroborates only |
| VSX warp | Cloud segment in A |
| Estate map UI | Phase D; A ships the path view with a path-local diagram only |
| aiview query input | Server-side, in-memory, per-snapshot reverse map; ambiguous pseudonym → refused (decision D-3) |

## 7. Stage plan (Phase A)

| Stage | Content | Days |
|---|---|---|
| A0 | Vendor documentation research (CP and PAN NAT/route/policy order, PAN RIB flags, advanced routing, Gaia multipath shape); freeze `PATH_ANALYSIS_VENDOR_SEMANTICS.md` | 3 |
| A1 | Parser/persistence gaps G1, G2, G4, G5, G6 (+ G10 gate row if approved), migration, redaction registration; masked fixture samples (PO-approved reads) | 6 |
| A2 | Rule-engine fixes: G3, G7, G8, install-on skip semantics, NAT evaluator over CP/PAN NAT sections, `policy_target_binding` | 6 |
| A3 | Topology builder, conflicts, overrides API | 5 |
| A4 | Path engine, verdict lattice, ECMP, return path, golden tests | 7 |
| A5a | API, RBAC actions in `ActionRegistry`, self-masking response, aiview reverse map, query audit, rate limit | 4 |
| A5b | Path screen (Fable builds UI), render harness, privacy gate | 4 |
| A6 | Real-environment validation: gated read-only matchers (`test security-policy-match`, `test nat-policy-match`, `test routing fib-lookup`, `ip route get`) on PO-chosen samples | 2 + PO time |

Total ≈ 37 engineer-days. A1 is a hard precondition of A4. Status stays `AUTOMATED_VALIDATED` until A6.

## 8. PO decisions required

| # | Decision | Recommendation |
|---|---|---|
| D-1 | Persist derived topology (relational, opaque ids + segment prefixes) and the new route/interface columns; values masked for aiview and redacted in audit | Approve |
| D-2 | Path results: do not store; store only masked query + verdict in the audit trail | Approve for A; result history is Phase D |
| D-3 | aiview may enter path queries using masked values, resolved server-side in memory per snapshot (structure becomes queryable for aiview, raw values never shown) | Approve |
| D-4 | Offer an "up to the edge" mode (`ALLOWED_TO_EDGE`) for paths that leave known topology | Approve, clearly labelled |
| D-5 | PAN `application-default` rules: match on the application's default ports with an "App-ID assumed" caveat (hop cannot be plain `ALLOWED`) vs. always `UNKNOWN` | Match with caveat |
| D-6 | CP gateway topology / anti-spoofing / security-zone resolution from the already-gated `show-gateways-and-servers` (parse-scope extension) | Defer to after first results |
| D-7 | New read-only device commands: PAN advanced-routing route read (A1), and the A6 validation matchers; each needs exact-command approval | Approve in principle; exact commands brought one by one |
| D-8 | RBAC: raw view `security_admin` (optionally `operation_admin`), everyone else masked; overrides `security_admin` only; no new collection rights for aiview | Approve |
| D-9 | Phase B LB vendor (F5 assumed; Radware already in inventory) and Phase C APIC read-only account | PO input |
