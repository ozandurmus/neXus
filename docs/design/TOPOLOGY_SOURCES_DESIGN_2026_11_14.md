# Topology sources - Cisco ACI APIC, Citrix ADC and F5 BIG-IP as read-only inputs to path drawing

## Status

DRAFT - 2026-11-14. This design narrows `LOAD_BALANCER_ARCHITECTURE_2026_11_05.md` (DRAFT - DO NOT FREEZE) from a
managed-device integration to a read-only topology source, and trims the APIC read list of
`ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md` (DRAFT) to what path drawing needs.

Design only; not implementation authority (AGENTS.md "Contract-status law"). Written without any device, APIC, load
balancer or host contact. Every request in section 3 is a proposal: nothing is gated, implemented or run until the
Product Owner approves each exact literal (AGENTS.md "Network action taxonomy", 2026-09-26 amendment; decision D-7 of
`PO_DECISION_RECORD_2026_11_03_PATH_ANALYSIS_PHASE_A.md`). Every vendor semantic marked UNKNOWN stays UNKNOWN until an
official source and a measured sample settle it. Examples use documentation addresses (`192.0.2.0/24`,
`198.51.100.0/24`, `203.0.113.0/24`) and illustrative pseudonyms only.

## 0. Decision, inputs and what this changes

**PO decision 2026-11-14** (relayed by the PO session; no decision record exists yet, step T0 writes one): Cisco ACI
APIC, Citrix ADC (NetScaler) and F5 BIG-IP become **topology sources** for path drawing (Trace / path analysis).
neXus does **not** manage them and keeps **no inventory** of them. It reads only routing, interface address and VIP
(virtual server -> pool / backend) tables, plus the ACI policy-based redirect (PBR) and service-graph definitions, so
that a computed path crosses the fabric redirect and the load balancers correctly.

| Document | Status | Effect of this design |
| --- | --- | --- |
| `LOAD_BALANCER_ARCHITECTURE_2026_11_05.md` | DRAFT - DO NOT FREEZE | Narrowed. Its L1 (enrolment, identity, HA, inventory, UCS / `ns.conf` backup), L2 (configuration plane, compliance catalog) and the 23 + 19 request rows F1-F23 / N1-N19 are **not** pursued. Its §6 path rules are the starting point of section 5 here, re-checked against sources. |
| `ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md` | DRAFT | Its capability 3 use (b), the redirect overlay for Trace, is delivered through this class. R0-R13 are trimmed in 3.1. Its use (a), zone and segment suggestions, and its capabilities 1 and 2 are unchanged and may later read the same stored data. Its `aci_fabric` / `aci_*` tables and `aci_read*` rights (§6.4-6.5) are replaced by the generic ones in section 2 if the PO accepts this design (contradiction X-2). |
| `PATH_ANALYSIS_PHASE_B_ARCHITECTURE_2026_11_05.md` | DRAFT | B5 (load-balancer hops) takes its data from the topology source reads instead of LB L1/L2. Its semantics bullets are re-used with their sources. |
| `PATH_ANALYSIS_DESIGN.md` | FROZEN | Unchanged: graph in memory (D-1), verdict lattice, ECMP bounds, return-path rule. New node kinds and hop types (section 5) need an amendment to it before implementation. |
| `PATH_ANALYSIS_VENDOR_SEMANTICS.md` (A0) | FROZEN | Unchanged. Section 4 here is the A0-style input for a separate freeze of load-balancer and ACI PBR semantics. |

Contradictions found (reported, not reconciled; AGENTS.md "Authority hierarchy"):

| # | Finding | Handling here |
| --- | --- | --- |
| X-1 | D-9 ("which load balancers") is still **open** in the ratified 2026-11-03 record and in `PATH_ANALYSIS_DESIGN.md` §8; the LB architecture draft took "both vendors" from a cloud brief only. The 2026-11-14 decision names both vendors. | T0 records the 2026-11-14 decision and closes D-9 in the same record. |
| X-2 | The ACI design proposes its own tables, rights and run states for the APIC. A second, generic set here would duplicate them. | One generic class (section 2) for all three kinds; the ACI design's §6.4-6.5 are superseded only if the PO accepts Q1. |
| X-3 | The LB architecture draft keys every LB table on `device_id` (an enrolled device). A topology source is not a device. | Section 2.5 keys on `topology_source_id`. Nothing is added to `devices`. |

Vocabulary: the brief's "NOT_DECIDED" maps to the path engine's existing overall verdict **`UNKNOWN` with a named
reason**. The frozen lattice (`PATH_ANALYSIS_DESIGN.md` §5) has no separate `NOT_DECIDED`; that word belongs to the ACI
design's capability-1 verdicts and is not reused in Trace.

## 1. How Trace builds a path today (repository facts)

- The graph is built in memory from stored inventory only, for `check_point` and `palo_alto` devices
  (`TopologyBuilder.build()`, `TopologyBuilder.java:126-130`). Node kinds are `ROUTING_CONTEXT`, `CLUSTER_UNIT` and
  `CLOUD`; `lb_virtual_server` and `aci_vrf` are reserved names with no implementation (`TopologyGraph.java:38-39`).
- Segments come from interface address prefixes; a route's next hop resolves to a unit on that segment, to a cloud
  (unheld segment) or to nothing (`NextHopResolver`). A path that reaches a cloud ends `EXITS_KNOWN_TOPOLOGY` (overall
  `UNKNOWN`) or `ALLOWED_TO_EDGE` (D-4).
- A unit of any other vendor is `UNSUPPORTED_NODE_VENDOR` (`PathEngine.java:372-377`).
- Per hop: policy evaluation (CP or PAN order), NAT, route lookup on the translated destination; ECMP fans out (8 per
  hop, 16 in total).
- Return path: from each unit attached to the delivered segment, walk back by routing, undoing forward NAT at the
  units that applied it. A return that crosses a stateful unit the forward path did not, or skips one it did, is
  `ASYMMETRIC` -> overall `UNKNOWN` with `OUT_OF_STATE_DROP_LIKELY` (`PathEngine.returnPath`, `walkBack`).

Effect today, which this design closes:

1. **Load balancers are invisible.** A firewall route whose next hop is an LB self IP / SNIP lands in a cloud. A
   destination that is a VIP is never translated to its pool members, so the path to the real server is not drawn,
   and the server-side firewall (if any) is never evaluated.
2. **The ACI fabric is a cloud.** Bridge-domain gateways and L3Out interfaces are not units; any route into the
   fabric exits the known topology.
3. **PBR is ignored.** A redirected flow is drawn along the routing-table path, which can cross a different firewall
   than the one the fabric actually sends it to (the problem the ACI design's capability 3 (b) names).

## 2. The topology source class

### 2.1 Definition and boundary

A **topology source** is a registered API endpoint from which neXus reads a small, fixed set of forwarding facts for
path drawing. It is not a device in any other sense.

| Plane | Managed device (CP, PAN, ASA, FortiGate, ...) | Topology source (APIC, Citrix ADC, F5 BIG-IP) |
| --- | --- | --- |
| Row in `devices`, device lists, device counts, estate home, Reports | yes | **no** |
| Inventory (identity, serial, version, HA role, interfaces as inventory) | yes | **no** |
| Backup, configuration plane, compliance, lifecycle catalog, system logs | yes | **no** |
| Failover, readiness, recovery writes, scheduled script writes | per contract | **never** (no write path exists) |
| Discovery runs, device events, notifications about the device | yes | **no** (only read-run failure, 2.6) |
| Path drawing input | yes (inventory routes and interfaces) | **yes** (its own read, 2.5) |

Enforced, not only stated: an architecture test (acceptance `TS-1`) pins that no capability, job type or controller
outside the topology-source reader can take a `topology_source_id`, and that the reader's request set equals the
approved literal set (`TS-2`).

Kinds: `ACI_APIC`, `CITRIX_ADC`, `F5_BIGIP`. A new kind needs its own design and literal approval.

### 2.2 Registration

| Field | Content | Notes |
| --- | --- | --- |
| `topology_source_id` | opaque id | never derived from an address or name |
| `kind` | `ACI_APIC` / `CITRIX_ADC` / `F5_BIGIP` | CHECK constraint |
| `label` | operator-chosen display label | presentation only, never a join key; masked for aiview (2.7) |
| `site` | operator-chosen site label (for example "DC1") | groups sources for Q3; masked |
| endpoints | 1..3 `address:port` rows, ordered | APIC cluster members, or the two units of an LB HA pair. The first endpoint that answers serves the whole run; the run records which one by ordinal only |
| `credential_reference_id` | reference into `credential_references` (V11) | the secret never leaves the credential store; no new secret table |
| TLS trust | the existing `https_endpoint_cert_trust` (V112) pin per `address:port` | see 2.3 |
| `scope` | kind-specific selectors: APIC tenants/VRFs (all by default), F5 partitions (`Common` by default), Citrix partitions (default partition only, 3.2) | narrows what is read; an unread scope makes hops `UNKNOWN (TOPOLOGY_SOURCE_SCOPE_NOT_READ)` |
| `read_mode` | `MANUAL` or `SCHEDULED` (Q5) | |
| `enabled` | boolean | a disabled source keeps its last data, marked stale (2.6) |

An LB HA pair is **one** source with two endpoints. Configuration objects read from whichever unit answers are taken as
the pair's configuration; per-unit (non-floating) addresses are kept only for the unit that answered. Whether the two
units' configurations are in sync is not read (that would be an HA/inventory read); every hop through a pair carries
the caveat `LB_PAIR_SYNC_NOT_READ`. This is a deliberate trade-off against the "no inventory" decision (Q6).

### 2.3 Transport, TLS trust and session

- HTTPS from the worker that already runs device jobs, through `HttpsDeviceClient` (no redirects, same-host paths
  only). No browser-to-source path (AGENTS.md architectural invariant).
- TLS: the V112 trust-on-first-use pin with `security_admin` authorization of the first fingerprint, `REFUSE` on a
  mismatch by default. The 2026-09-21 Palo Alto "TLS verification disabled" decision does **not** extend to topology
  sources. Verification against the bank CA instead of a pin is Q4.
- Session: one login per run, token or cookie in memory only, logout in a `finally` path, logins serialized per
  `address:port` (the Radware `oneAtATime` precedent). No token refresh: a run that outlives its token fails and is
  retried by the operator or the next schedule.

### 2.4 Credentials

- One dedicated read-only account per source (per APIC cluster, per LB pair), owned by the bank's network team, with
  the least role that can GET the listed objects (section 3 names the vendor role per kind: APIC `read-all`, Citrix
  `read-only` command policy, F5 Auditor; their exact capabilities over the API are confirmed by the team, Q2).
- The password sits in the existing encrypted credential store; only `credential_reference_id` is on the source row.
  Failure alerts use the V207 pattern (a 12-hex digest of the reference, never the reference).
- A source whose login is refused is `FAILED (AUTH_FAILED)`; nothing stored is replaced.

### 2.5 Stored data (minimum)

Raw responses are parsed in memory and discarded (AGENTS.md "Raw-evidence law"). Only the rows below are stored, all
keyed on `topology_source_id` and the read run, replaced per object class only when that class read COMPLETE. NULL means
UNKNOWN in every new column. Every address- and name-bearing column is registered in `audit_redaction_policy`
(tier 2), as `PATH_ANALYSIS_DESIGN.md` §3 requires for topology columns.

| Table | Rows | Kept columns | Not kept |
| --- | --- | --- | --- |
| `topology_source` / `topology_source_endpoint` | registration (2.2) | as 2.2 | - |
| `topology_source_read_run` | one per run | start, end, state (`COMPLETE`, `PARTIAL`, `FAILED`), endpoint ordinal, per-class state and row count, failure code | response bodies, error texts from the source |
| `ts_routing_context` | one per VRF / route domain / traffic domain | opaque key, kind, vendor id (route-domain id, traffic-domain id, VRF key) | names beyond the masked label |
| `ts_route` | configured routes | context, destination prefix, next-hop kind (`IP`, `DISCARD`, `INTERFACE`, `UNKNOWN`), next-hop address, `evidence_grade = CONFIG_DERIVED` | metrics, route names |
| `ts_address` | addresses the source owns on the data plane | context, address, prefix length, role (`LB_SELF`, `LB_FLOATING`, `LB_SNIP`, `LB_VIP`, `ACI_BD_GATEWAY`, `ACI_L3OUT_IF`), VLAN or BD key | management addresses (NSIP, APIC/F5 management): read only to exclude them, never stored |
| `ts_virtual_server` | F5 virtual servers, Citrix LB / CS vservers | context, destination address and mask, port or any, protocol, kind (`STANDARD`, `FORWARDING`, `OTHER`, `UNKNOWN`), enabled, translate-address / translate-port, source-translation mode and set key, programmable-logic presence flags, name hash + masked label | profiles, persistence settings, certificate names, iRule / policy bodies |
| `ts_backend` | pool members / service group members / services | virtual server key, member address, port, context | member names beyond the masked label, health, weights, priority groups |
| `ts_address_set` | F5 SNAT pools, Citrix SNIP sets per traffic domain | key, addresses | names |
| `ts_aci_subnet` | BD, EPG and external-EPG subnets | VRF key, owner key (EPG / external EPG), canonical network block, gateway form, scope flags | - |
| `ts_aci_redirect` | redirect statements (5.1) | VRF key, consumer and provider selector keys, filter entries (protocol, ports), direction, redirect destination addresses, hashing setting, threshold and down action, rendered state | PBR destination MACs (compared, never stored) |

Names: an object name (virtual server, pool, tenant, EPG) is stored as a keyed hash (the existing HMAC key) for joins
and, in one name-bearing column per table, as the raw name for the raw view; Q7 offers the stricter token-only form.

Retention: only the last COMPLETE read per class is kept, plus run records for 90 days (Q8). Deleting a source deletes
its rows; the migration states it.

### 2.6 Read run, failure behaviour, freshness

- One run per source at a time; runs of different sources may overlap, at most two at once (stability over speed).
- No retry inside a run. Per-request timeout 30 s, response cap 16 MiB, run ceiling 15 minutes (the ACI design's
  figures, applied to all kinds); over-cap or timed-out class -> that class `FAILED` or `TRUNCATED`, previous data
  kept, run `PARTIAL`. A truncated class is never used in part.
- A class whose total count changes between pages is `INCONSISTENT`; previous data kept.
- An attribute value outside the known vocabulary makes that object `UNKNOWN`, never the nearest value.
- **Freshness**: every path hop built from a source carries the source's last COMPLETE read time. When it is older than
  the threshold (proposal: 24 hours after a scheduled read was due, Q5), the hop is `UNKNOWN (TOPOLOGY_SOURCE_STALE)`
  and the last-known translation or redirect is still drawn as context, exactly like `STALE_OR_OUT_OF_SYNC_POLICY`.
  A source never read COMPLETE gives `UNKNOWN (TOPOLOGY_SOURCE_NOT_READ)`.
- Rebuild: the topology fingerprint gains the source run ids, so the existing debounced auto rebuild
  (`PATH_ANALYSIS_DESIGN.md` §5 amendment 2026-11-04) picks up a new COMPLETE read without a person acting.
- A failed run raises the existing credential-failure or job-failure notification for `security_admin` only; it never
  appears as a device alert.

### 2.7 Masking

- aiview and every masked role see source labels as pseudonyms (new prefixes in the estate dictionary: `TSRC-` for a
  source, `VS-` virtual server, `POOL-`, `TEN-`, `VRF-`, `BD-`, `EPG-`, `EXT-`, `L3O-`, `CTR-`, `SGR-`, `PBR-`) and addresses through
  `SubnetPreservingIpMasker`. Raw names and addresses only with the raw-view right.
- The masking advice works by JSON key (`PrivacyMaskingResponseBodyAdvice`), so every new key gets its rule **in the same
  change** that exposes it. Until then the masked projection withholds source object lists and shows counts only
  (fail-closed, the LB architecture draft §8.3 rule).
- A name that embeds an address (an F5 node named after its address, `/Common/192.0.2.10`) is pseudonymized **and** its
  embedded address masked.
- Logs and run records carry class names, counts and failure codes only (the Radware `ccDevices` precedent).

### 2.8 Rights (`ActionRegistry`)

| Action | Roles | Notes |
| --- | --- | --- |
| `topology_source_read` | security_admin, onboarding_admin, operation_admin, viewer; replay_viewer masked | registration, run state, counts; object lists masked per 2.7 |
| `topology_source_manage` | security_admin | register, edit endpoints and scope, set credential reference, authorize TLS pin, enable / disable, delete |
| `topology_source_run` | security_admin, onboarding_admin; replay_viewer only if the PO extends the 2026-10-04 CLASS_0 allowance (Q9) | start a read run now |
| path results | unchanged `path_analysis_read` | hop detail follows today's masking |

### 2.9 Audit

- Registration rows are captured by the existing audit trigger (`fn_audit_capture`), address and name columns redacted
  at tier 2.
- Each run writes its `topology_source_read_run` row with actor (`system:topology-source-read` for scheduled runs).
- Each path query keeps writing one masked `authz_decisions` row (D-2); when a hop used a topology source, the row
  records the source pseudonym and the run id, not the translated addresses.

### 2.10 CLI parity

Every screen action has a CLI equivalent (PO P1 rule): `nexus topology-source list|show|add|edit|pin|enable|disable|
delete|run|runs`. Same RBAC, same masking.

## 3. Read literals (proposals for PO approval)

### 3.0 Rules common to every kind

- Method `GET` only, except the session login and logout rows. JSON responses. HTTPS. Credentials in a body or header,
  never in a URL.
- Parameters in angle brackets are filled only by code: `<N>` page number generated by the loop, `<USER>` /
  `<SECRET>` from the credential store, `<TOKEN>` from the login response. No value comes from a user or a page.
- Per request: timeout 30 s, response cap 16 MiB, no retry. Per run: at most 2 requests per second, ceiling 15
  minutes, at most 200 pages per class.
- "Expected size" is **UNKNOWN until the T3 measurement** for every row; nothing in the bank's estate has been
  counted. The measurement records row count, response bytes and page count per literal, never values.
- "Fields kept" lists everything stored; every other field is dropped in memory. "Masked" is what aiview and masked
  roles see instead of the value (2.7).
- Confidence of the literal's form (path, parameter and field names): **HIGH** seen on an official reference page read
  2026-11-14; **MEDIUM** official page seen only in a search excerpt, or an older version, or a community page;
  **UNKNOWN** not found. Field *meaning* is graded separately in section 4.

### 3.1 Cisco APIC REST API (trim of the ACI design's R0-R13)

Read role: a dedicated local or remote APIC user in security domain `all` with role `read-all`, read privilege only,
as recorded in the ACI design §6.2. Paging: `page=<N>` (zero-based) and `page-size=500` with `order-by=<class>.dn|asc`
so that pages are stable (APIC-REST). Class and attribute names below were checked against Cisco's published model
reference (APIC-MIM, the "latest" model as published on 2026-11-14; the model version is not stated in the files read,
so every name is re-checked against the bank's APIC version, Q11). Every parent-child pair used in `rsp-subtree-class` below
was checked against the model's containment (`containedBy`) on the same date.

| # | Exact literal | Purpose | Fields kept | Masked | Form |
| --- | --- | --- | --- | --- | --- |
| A0a | `POST /api/aaaLogin.json` body `{"aaaUser":{"attributes":{"name":"<USER>","pwd":"<SECRET>"}}}` | session start | token (memory only) | - | HIGH (APIC-REST) |
| A0b | `POST /api/aaaLogout.json` body `{"aaaUser":{"attributes":{"name":"<USER>"}}}` | session end | none | - | HIGH (APIC-REST) |
| A1 | `GET /api/class/fvCtx.json?order-by=fvCtx.dn\|asc&page=<N>&page-size=500` | VRFs | `dn` (as keyed hash + masked label), `pcEnfPref` (`enforced` default / `unenforced`) | `VRF-` pseudonym | HIGH (MIM) |
| A2 | `GET /api/class/fvBD.json?rsp-subtree=children&rsp-subtree-class=fvSubnet,fvRsCtx&order-by=fvBD.dn\|asc&page=<N>&page-size=500` | bridge domains, their gateway subnets and VRF | BD `dn`, `unicastRoute`; `fvSubnet` `ip`, `scope`, `virtual`; `fvRsCtx` `tDn` | `BD-` pseudonym, subnet masked | HIGH (MIM) |
| A3 | `GET /api/class/fvAEPg.json?rsp-subtree=children&rsp-subtree-class=fvRsBd,fvRsProv,fvRsCons,fvSubnet&order-by=fvAEPg.dn\|asc&page=<N>&page-size=500` | EPGs: BD, provided / consumed contracts, EPG subnets | `dn`, `pcEnfPref`, `prefGrMemb`, `isAttrBasedEPg`; relation `tDn`s; subnet `ip` | `EPG-`, `CTR-` pseudonyms | HIGH (MIM) |
| A4 | `GET /api/class/l3extOut.json?rsp-subtree=children&rsp-subtree-class=l3extRsEctx&order-by=l3extOut.dn\|asc&page=<N>&page-size=500` | L3Outs and their VRF | `dn`; `l3extRsEctx` `tDn` | `L3O-` pseudonym | HIGH (MIM) |
| A5 | `GET /api/class/l3extInstP.json?rsp-subtree=children&rsp-subtree-class=l3extSubnet,fvRsProv,fvRsCons&order-by=l3extInstP.dn\|asc&page=<N>&page-size=500` | external EPGs, their subnets, contracts | `dn`, `prefGrMemb`; `l3extSubnet` `ip`, `scope` (`import-security`, `export-rtctrl`, ...); relation `tDn`s | `EXT-` pseudonym, subnet masked | HIGH (MIM) |
| A6 | `GET /api/class/l3extRsPathL3OutAtt.json?rsp-subtree=children&rsp-subtree-class=l3extMember&order-by=l3extRsPathL3OutAtt.dn\|asc&page=<N>&page-size=500` | L3Out interface addresses (where a firewall's next hop lands) | `dn`, `addr`, `ifInstT` (`ext-svi`, `l3-port`, `sub-interface`); `l3extMember` `side`, `addr` | addresses masked; leaf path `tDn` hashed, never shown | HIGH (MIM) |
| A7 | `GET /api/class/ipRouteP.json?rsp-subtree=children&rsp-subtree-class=ipNexthopP&order-by=ipRouteP.dn\|asc&page=<N>&page-size=500` | L3Out static routes | `dn`, `ip`; `ipNexthopP` `nhAddr`, `type` | masked | HIGH (MIM) |
| A8 | `GET /api/class/vzBrCP.json?rsp-subtree=full&rsp-subtree-class=vzSubj,vzRsSubjFiltAtt,vzRsSubjGraphAtt,vzInTerm,vzOutTerm,vzRsFiltAtt&order-by=vzBrCP.dn\|asc&page=<N>&page-size=500` | contracts, subjects, filters, attached service graph, per-direction terms | `dn`, `scope`; subject `revFltPorts`; `vzRsSubjFiltAtt` `tDn`, `action`; `vzRsSubjGraphAtt` `tDn`; per-direction `vzRsFiltAtt` `tDn` under `vzInTerm` / `vzOutTerm` | `CTR-` pseudonym | HIGH (MIM) |
| A9 | `GET /api/class/vzEntry.json?order-by=vzEntry.dn\|asc&page=<N>&page-size=500` | filter entries | `dn`, `etherT`, `prot`, `dFromPort`, `dToPort`, `sFromPort`, `sToPort` | - | HIGH (MIM) |
| A10 | `GET /api/class/vzAny.json?rsp-subtree=children&rsp-subtree-class=vzRsAnyToProv,vzRsAnyToCons&order-by=vzAny.dn\|asc&page=<N>&page-size=500` | VRF-wide contracts | `dn`, `prefGrMemb`; relation `tDn`s | - | HIGH (MIM) |
| A11 | `GET /api/class/vnsAbsGraph.json?rsp-subtree=children&rsp-subtree-class=vnsAbsNode&order-by=vnsAbsGraph.dn\|asc&page=<N>&page-size=500` | service graph templates and their nodes | `dn`; node `dn`, `funcType` (`GoTo`, `GoThrough`, `L1`, `L2`, `None`), `routingMode` (`Redirect`, `unspecified`) | `SGR-` pseudonym | HIGH (MIM) |
| A12 | `GET /api/class/vnsLDevCtx.json?rsp-subtree=full&rsp-subtree-class=vnsLIfCtx,vnsRsLIfCtxToSvcRedirectPol,vnsRsLIfCtxToBD,vnsRsLDevCtxToLDev&order-by=vnsLDevCtx.dn\|asc&page=<N>&page-size=500` | device selection: per connector, which redirect policy and BD | `dn`, `ctrctNameOrLbl`, `graphNameOrLbl`, `nodeNameOrLbl`; `vnsLIfCtx` `connNameOrLbl`; relation `tDn`s | pseudonyms | HIGH (MIM) |
| A13 | `GET /api/class/vnsSvcRedirectPol.json?rsp-subtree=children&rsp-subtree-class=vnsRedirectDest&order-by=vnsSvcRedirectPol.dn\|asc&page=<N>&page-size=500` | PBR policies and destinations | `dn`, `hashingAlgorithm`, `resilientHashEnabled`, `thresholdEnable`, `thresholdDownAction`, `destType`; destination `ip`, `ip2` (`mac` compared in memory, never stored) | `PBR-` pseudonym, addresses masked | HIGH (MIM) |
| A14 | `GET /api/class/vnsGraphInst.json?rsp-subtree=children&rsp-subtree-class=vnsNodeInst&order-by=vnsGraphInst.dn\|asc&page=<N>&page-size=500` | rendered graph instances | `dn`, `configSt` (`applied`, `applying`, `failed-to-apply`, `none`); node `dn`, `funcType`, `routingMode` | - | HIGH (MIM) |

Dropped from the ACI design's list: R1 `fvTenant` (the tenant is part of every `dn`) and R12 `vnsLDevVip` (the
firewall is bound by PBR destination address, never by device object). Added for path drawing: A4, A6, A7 (fabric
routing toward the firewalls). Kept out deliberately: endpoints (`fvCEp`, `fvIp`), endpoint security groups
(`fvESg`, Q12), faults, events, health, audit logs, subscriptions, and the leaf runtime routing tables (`uribv4Route`,
`uribv4Nexthop`: they exist in the model as non-configurable runtime classes, are per leaf and large, and are a later
option under Q10).

### 3.2 Citrix ADC (NetScaler) NITRO API

Read role: a dedicated system user bound to the built-in `read-only` command policy (NS-CMDPOL, HIGH for the policy
name). Whether that policy governs NITRO GETs in the same way as CLI `show` commands is UNKNOWN; the team confirms it
on the measurement run (Q2). Field names were read on the NITRO 14.1 reference pages (NITRO-REF, "Citrix ADC 14.1 NITRO
API Reference", last published 2026-10-30). Paging: `pagesize=<500>&pageno=<N>` and `attrs=` field selection are HIGH
(NITRO-OPS). Whether paging combines with `bulkbindings=yes` is UNKNOWN; binding reads are proposed unpaged under the
16 MiB cap and get a paged form only if T3 shows the need. Partitions: only the default partition is read; switching
partition over NITRO is UNKNOWN (not found on NITRO-BASIC), so N11 only counts partitions.

| # | Exact literal | Purpose | Fields kept | Masked | Form |
| --- | --- | --- | --- | --- | --- |
| N0a | `POST /nitro/v1/config/login` body `{"login":{"username":"<USER>","password":"<SECRET>"}}` | session start (201, `NITRO_AUTH_TOKEN` cookie) | token (memory only) | - | HIGH (NITRO-BASIC) |
| N0b | `POST /nitro/v1/config/logout` body `{"logout":{}}` | session end | none | - | HIGH (NITRO-BASIC) |
| N1 | `GET /nitro/v1/config/nsip?attrs=ipaddress,netmask,type,td&pagesize=500&pageno=<N>` | ADC-owned addresses | `ipaddress`, `netmask`, `type` (`SNIP`, `VIP` kept; `NSIP`, `HostIP`, `CLIP` read only to exclude them; `GSLBsiteIP` dropped), `td` | masked | HIGH |
| N2 | `GET /nitro/v1/config/route?attrs=network,netmask,gateway,vlan,td,routetype&pagesize=500&pageno=<N>` | routes per traffic domain | `network`, `netmask`, `gateway` (null = null-interface route), `vlan`, `td`, `routetype` | masked | HIGH |
| N3 | `GET /nitro/v1/config/nsmode` | global modes | `usip`, `usnip`, `l3` (read-only booleans) | - | HIGH |
| N4 | `GET /nitro/v1/config/lbvserver?attrs=name,ipv46,ippattern,ipmask,port,servicetype,td,m,curstate,backupvserver,listenpolicy&pagesize=500&pageno=<N>` | LB virtual servers | `name` (hash + label), `ipv46`, `ippattern`, `ipmask`, `port`, `servicetype`, `td`, `m` (`IP` / `MAC`), `curstate` only as "DISABLED or not", presence of `backupvserver` and `listenpolicy` (booleans; the values are dropped) | `VS-` pseudonym, address masked | HIGH |
| N5 | `GET /nitro/v1/config/lbvserver_binding?bulkbindings=yes` | per virtual server: bound services, service groups, and which policy kinds are bound | per vserver: service and service-group names (hash); one presence boolean per policy-binding kind (`lbvserver_responderpolicy_binding`, `lbvserver_rewritepolicy_binding`, `lbvserver_lbpolicy_binding`, `lbvserver_spilloverpolicy_binding`, and every other `*policy_binding` kind) | - | HIGH (get-all with `bulkbindings` shown on the page) |
| N6 | `GET /nitro/v1/config/servicegroup?attrs=servicegroupname,servicetype,usip,td&pagesize=500&pageno=<N>` | service groups | `servicegroupname` (hash), `servicetype`, `usip`, `td` | `POOL-` pseudonym | HIGH |
| N7 | `GET /nitro/v1/config/servicegroup_servicegroupmember_binding?bulkbindings=yes` | service-group members | `servicegroupname` (hash), `ip`, `port`, `servername` (hash, to join N9) | addresses masked | HIGH |
| N8 | `GET /nitro/v1/config/service?attrs=name,ip,servername,port,servicetype,usip,td&pagesize=500&pageno=<N>` | single services | `name` (hash), `ip`, `servername` (hash), `port`, `servicetype`, `usip` (unset = inherits global), `td` | masked | HIGH |
| N9 | `GET /nitro/v1/config/server?attrs=name,ipaddress,domain,td&pagesize=500&pageno=<N>` | server objects behind members | `name` (hash), `ipaddress`, presence of `domain` (boolean: a DNS-named server has no fixed address -> member `UNKNOWN`), `td` | masked; the domain name is never stored | HIGH |
| N10 | `GET /nitro/v1/config/csvserver?attrs=name,ipv46,port,servicetype,td&pagesize=500&pageno=<N>` | content-switching virtual servers (VIPs whose target is chosen by policy) | `name` (hash), `ipv46`, `port`, `servicetype`, `td` | `VS-` pseudonym, address masked | HIGH |
| N10b | `GET /nitro/v1/config/csvserver_lbvserver_binding?bulkbindings=yes` | the default LB vserver of each CS vserver (drawn as a `CONDITIONAL` branch) | `name` (hash), `lbvserver` (hash) | - | fields HIGH; bulk form UNKNOWN (optional row) |
| N11 | `GET /nitro/v1/config/nspartition?count=yes` | whether admin partitions exist (count only) | the count | - | HIGH |

Not proposed: `rnat`, `inat` (server-initiated and routed traffic stays `LB_ROUTED` UNKNOWN), `nshardware`,
`nsversion`, `hanode`, `nsconfig`, `nsrunningconfig`, any `stat/*` resource (member and vserver health), SSL objects.

### 3.3 F5 BIG-IP iControl REST

Read role: a dedicated user with the **Auditor** role (read-only on all configuration and all partitions; F5-ROLES,
MEDIUM: search excerpt of the 15.0 guide). From BIG-IP 21.0.0.3 non-admin roles other than a listed few may need the
`iControl_REST_API_User` role for REST (MEDIUM, release-note excerpt); the team confirms on the measurement run.
Object and field names were read on the iControl REST API reference pages (F5-API, HIGH). Paging `$top` / `$skip` and
field selection `$select` are MEDIUM (F5 K01444423, K18432457: search excerpts, shown on non-LTM paths); this proposal
therefore uses `$select` only where T3 confirms it works and pages only if T3 shows the need. Whether a collection GET
returns objects of every partition, and the `%<id>` route-domain suffix form inside addresses, are UNKNOWN until T3.

| # | Exact literal | Purpose | Fields kept | Masked | Form |
| --- | --- | --- | --- | --- | --- |
| F0a | `POST /mgmt/shared/authn/login` body `{"username":"<USER>","password":"<SECRET>","loginProviderName":"tmos"}` | session start; token sent as `X-F5-Auth-Token` | token (memory only) | - | MEDIUM (F5 K04326056 excerpt); `loginProviderName` for remote (LDAP / RADIUS / TACACS) accounts UNKNOWN |
| F0b | `DELETE /mgmt/shared/authz/tokens/<TOKEN>` | session end | none | - | UNKNOWN (community pages only). If T3 cannot confirm it, the token is left to expire (default lifetime 1200 s, MEDIUM) and F0b is dropped |
| F1 | `GET /mgmt/tm/net/self?$select=name,partition,address,vlan,floating,trafficGroup` | self IPs | `address` (with prefix), `vlan` (hash), `floating`, `trafficGroup` (hash) | masked | HIGH (`$select` MEDIUM) |
| F2 | `GET /mgmt/tm/net/route?$select=name,partition,network,gw,tmInterface` | static routes | `network`, `gw`, `tmInterface` (hash); a route with neither is next-hop kind `UNKNOWN` | masked | HIGH (`$select` MEDIUM) |
| F3 | `GET /mgmt/tm/net/route-domain?$select=name,partition,id,vlans` | route domains (routing contexts) and their VLANs | `id`, `vlans` (hash) | - | HIGH (`$select` MEDIUM) |
| F4 | `GET /mgmt/tm/ltm/virtual?expandSubcollections=true` | virtual servers | `name` and `partition` (hash + label), `destination`, `mask`, `source`, `ipProtocol`, `pool` (hash), `sourceAddressTranslation` `type` and `pool` (hash), `translateAddress`, `translatePort`, `vlansEnabled` / `vlansDisabled`, `vlans` (hash), `enabled` / `disabled`, presence of `rules` and of the `policies` subcollection (booleans; names and bodies dropped); the virtual-server type flags (ip-forward, l2-forward, reject in tmsh) under their JSON names once T3 shows them | `VS-` pseudonym, addresses masked | HIGH (subcollection expansion HIGH; type-flag JSON names UNKNOWN) |
| F5 | `GET /mgmt/tm/ltm/pool?expandSubcollections=true` | pools and members | pool `name` (hash), `allowSnat` if present (UNKNOWN name, T3); member `name` (`node:port`, hash), `address`, port taken from `name` | `POOL-` pseudonym, addresses masked; member `state` and `session` dropped (health is not read) | HIGH |
| F6 | `GET /mgmt/tm/ltm/snatpool` | SNAT pools | `name` (hash), `members` (references to translations) | - | HIGH |
| F7 | `GET /mgmt/tm/ltm/snat-translation?$select=name,partition,address` | SNAT translation addresses | `name` (hash), `address` | masked | HIGH (`$select` MEDIUM) |

Not proposed: `ltm virtual-address`, `ltm node` (member addresses come with F5), `ltm snat` and `ltm nat` (stand-alone
translations of non-virtual-server traffic; such traffic stays `LB_ROUTED` UNKNOWN), `ltm rule` / `ltm policy`
bodies, `sys *` (version, hardware, provision), `cm *` (HA, sync), any `stats` path, UCS, `util/*`.

### 3.4 Never issued (pinned by `TS-2`)

APIC: any `POST`, `PUT` or `DELETE` except A0a / A0b; `/api/mo/...` writes; websocket subscriptions; any
`rsp-subtree-include` of faults, health, stats or audit logs. NITRO: any `POST`, `PUT` or `DELETE` except N0a / N0b;
any `?action=`; `/nitro/v1/stat/*`; `nsrunningconfig`, `systemfile`, `systembackup`, `save`, `sync`, `force`.
iControl REST: any `POST`, `PUT`, `PATCH` or `DELETE` except F0a / F0b; `/mgmt/tm/util/*`; `/mgmt/shared/*` except
F0a / F0b; `/mgmt/tm/sys/*`; any `stats` path.

## 4. Path semantics (A0 style; input to the T1 freeze)

Grades as in A0: **HIGH** stated directly on a current official page; **MEDIUM** older page, direct implication, an
official page seen only in a search excerpt, or a default value whose behaviour is not described; **LOW** indirect;
**UNKNOWN** not found. Read 2026-11-14 unless a row says otherwise; no device contacted. Source keys in 4.5. What a row
does not establish stays UNKNOWN and becomes a reason code in 5.4.

### 4.1 Common load-balancer rules (design rules, not vendor claims)

- Member **health is not read** (PO decision 2026-11-14). Every configured member is a possible destination; the hop
  carries `LB_MEMBER_HEALTH_NOT_READ`. Persistence, load-balancing method and weights decide *which* member a given
  connection gets, never *whether* a member is possible, so none of them is read.
- A load balancer is not modelled as a policy enforcer. A decided virtual-server hop is `ALLOWED` with
  `LB_NOT_A_POLICY_ENFORCER`; any packet-filter feature on the LB (F5 AFM, Citrix ACLs) is outside the reads.
- Anything per-request programmable (F5 iRules, LTM policies; Citrix content switching, responder, rewrite and other
  bound policies, backup vservers, listen policies) can change the member, the translation or drop the packet, so its
  **presence** alone makes the hop `UNKNOWN (LB_PROGRAMMABLE_LOGIC)`. Bodies are never read or interpreted.
- A configured default is never assumed: every field the hop needs is read; a missing field is UNKNOWN.

### 4.2 F5 BIG-IP

| Id | Claim | Source | Grade | Used as |
| --- | --- | --- | --- | --- |
| F5-P1 | When a connection matches several virtual servers, precedence is by destination (host before network before any), then source (host, network, any), then port (specific before `*`); 18 ordered classes. Neither address order nor configuration order matters. | F5-K14800 (BIG-IP 11.3.0 and later, updated 2023-02-21) | HIGH | candidate selection in 5.3 step 1 |
| F5-P2 | Two candidates in the same F5-P1 class (for example two network destinations of different mask that both contain the address) | not stated on F5-K14800 | UNKNOWN | `LB_VS_MATCH_AMBIGUOUS` |
| F5-S1 | `source-address-translation type` is one of `automap`, `lsn`, `snat`, `none`; `pool` names the SNAT or LSN pool | F5-TMSH-VS (v16) | HIGH | source mode; `lsn` -> `LB_SOURCE_TRANSLATION_UNKNOWN` (LSN not modelled) |
| F5-S2 | Automap chooses a translation address from self IPs; floating self IPs on the egress VLAN are preferred; several floating self IPs are alternated; without a floating self IP on the egress VLAN the chosen address may not be the intended one | F5-K7820 (updated 2026-05-07) | HIGH | source set = floating self IPs on the egress VLAN; none there -> `LB_SOURCE_TRANSLATION_UNKNOWN` |
| F5-S3 | A SNAT pool's members are SNAT translation objects, each with an `address` | F5-API (`ltm snatpool`, `ltm snat-translation`) | HIGH | source set = those addresses |
| F5-S4 | A pool setting can stop source translation that the virtual server enables ("Allow SNAT") | F5-K13590401 (search excerpt, via Phase B draft) | MEDIUM | `allowSnat` read if T3 finds it; else caveat `LB_POOL_SNAT_SETTING_NOT_READ` |
| F5-S5 | An iRule can override the virtual server's source translation (`snat` command) | F5-IRULE-SNAT (search excerpt, via Phase B draft) | MEDIUM | covered by `LB_PROGRAMMABLE_LOGIC` |
| F5-T1 | `translate-address` enabled / disabled; disabled is used to load balance "connections to any address" (no destination change); the tmsh default is stated as disabled | F5-TMSH-VS | HIGH | read per virtual server; disabled -> destination stays (`LB_NO_DESTINATION_TRANSLATION`) |
| F5-V1 | `vlans-enabled` limits the virtual server to the listed VLANs; `vlans-disabled` (the default) excludes them | F5-TMSH-VS | HIGH (that an empty disabled list means "all VLANs" is MEDIUM) | ingress VLAN check in 5.3 step 1 |
| F5-V2 | Virtual-server types `ip-forward`, `l2-forward`, `reject` exist | F5-TMSH-VS | HIGH for existence; JSON field names UNKNOWN | `FORWARDING` -> the LB routes the packet (`LB_ROUTED`); `reject` -> `UNKNOWN` until its packet behaviour is frozen |
| F5-R1 | A stand-alone SNAT object can pass traffic not destined for a virtual server | F5-K7820 | HIGH | traffic crossing a BIG-IP without a virtual-server match is `LB_ROUTED_TRAFFIC_SEMANTICS_UNKNOWN` (stand-alone SNATs are not read) |
| F5-R2 | How BIG-IP treats a packet matching no virtual server, SNAT or NAT | not found on an official page read | UNKNOWN | same |
| F5-D1 | Route domains are routing contexts with their own VLANs; the `%<id>` address suffix form | F5-API (`net route-domain` `id`, `vlans`) for the object; suffix form not shown | HIGH / UNKNOWN | context per route domain; suffix parsing only after T3 measures it |

### 4.3 Citrix ADC (NetScaler)

| Id | Claim | Source | Grade | Used as |
| --- | --- | --- | --- | --- |
| C-T1 | ADC-owned address types (14.1): `SNIP` (used to talk to servers and for the ADC's own packets), `VIP` (virtual server address), `NSIP`, `HostIP`, `GSLBsiteIP`, `CLIP`; default `SNIP`. `MIP` is not a 14.1 type | NITRO-REF `nsip` | HIGH (14.1); older-version types UNKNOWN | `LB_SNIP` attachments; `NSIP` / `CLIP` / `HostIP` never attach |
| C-M1 | Virtual server redirection mode `m`: `IP` changes the destination IP to the server's; `MAC` changes only the destination MAC | NITRO-REF `lbvserver` | HIGH | `IP` -> member address; `MAC` -> destination unchanged (`LB_NO_DESTINATION_TRANSLATION`) and the return is not expected through the ADC (`LB_DSR_LIKE_MODE` caveat) |
| C-M2 | `ippattern` / `ipmask` make a wildcard virtual server, mutually exclusive with `ipv46` | NITRO-REF `lbvserver` | HIGH | candidate match by pattern and mask |
| C-M3 | Precedence when a specific and a wildcard virtual server both match | not found | UNKNOWN | `LB_VS_MATCH_AMBIGUOUS` |
| C-B1 | `backupvserver` receives requests if the primary is DOWN or at its spillover threshold | NITRO-REF `lbvserver` | HIGH | presence -> `LB_PROGRAMMABLE_LOGIC` (the member set depends on runtime state) |
| C-CS1 | A content-switching virtual server switches to `targetvserver` per policy; `lbvserver` is the default binding | NITRO-REF `csvserver_lbvserver_binding` | HIGH | CS hop `UNKNOWN (LB_PROGRAMMABLE_LOGIC)`; the default LB vserver drawn `CONDITIONAL` |
| C-U1 | Service / service-group `usip`: with `NO` (the default) a MIP or SNIP is the source toward the server; with `YES` the client address is kept; a service created without the parameter inherits the global Use Source IP mode | NITRO-REF `servicegroup`, `service` | HIGH | per member source mode; global mode from N3 |
| C-U2 | Global `usip`, `usnip`, `l3` flags (labels "Use Source IP", "Use Subnet IP", "Layer 3 mode (ip forwarding)") | NITRO-REF `nsmode` | HIGH for fields; meaning of `usnip` beyond its label MEDIUM | `usip` default for services without their own value; `usnip` = false with `usip` = false -> `LB_SOURCE_TRANSLATION_UNKNOWN` |
| C-U3 | Which SNIP is used when several exist in the traffic domain | not found | UNKNOWN | source carried as the set of SNIPs of that traffic domain |
| C-R1 | Route `gateway` may be null for a null-interface route; `vlan` can be the gateway; `td` default 0 | NITRO-REF `route` | HIGH | next-hop kinds `IP`, `DISCARD`, `INTERFACE` |
| C-R2 | Whether a GET of `route` returns configured routes only or the runtime table (connected, dynamic) | `routetype` lists CONNECTED, STATIC, DYNAMIC, OSPF, ISIS, RIP, BGP but its text describes route removal | UNKNOWN | rows stored `CONFIG_DERIVED`; T3 measures which `routetype` values appear |
| C-D1 | A server object may be defined by `domain` instead of `ipaddress` | NITRO-REF `server` | HIGH | DNS-named member -> branch `UNKNOWN (LB_MEMBER_ADDRESS_DYNAMIC)` |
| C-P1 | Admin partitions exist; how a NITRO session reads a non-default partition | partition switch not found on NITRO-BASIC | UNKNOWN | default partition only; count > 0 -> `TOPOLOGY_SOURCE_SCOPE_NOT_READ` for addresses not found |

### 4.4 Cisco ACI policy-based redirect

| Id | Claim | Source | Grade | Used as |
| --- | --- | --- | --- | --- |
| A-N1 | A service-graph node with `routingMode = Redirect` is a PBR node; `funcType` is one of `GoTo`, `GoThrough`, `L1`, `L2`, `None` | APIC-MIM `vnsAbsNode`, `vnsNodeInst` | HIGH (values) | redirect statements only from `Redirect` nodes; `L1` / `L2` destination types -> `FABRIC_POLICY_NOT_MODELLED` |
| A-D1 | A redirect policy has destinations with `ip`, `ip2`, `mac`; `destType` default `L3` | APIC-MIM `vnsSvcRedirectPol`, `vnsRedirectDest` | HIGH | binding to an enrolled firewall by `ip` (ACI design §6.7) |
| A-H1 | `hashingAlgorithm` is `sip-dip-prototype` (default), `sip` or `dip`; `resilientHashEnabled` default no | APIC-MIM | HIGH (values, defaults) | recorded per policy |
| A-H2 | The default hash tuple is source IP, destination IP and protocol; "you need to make sure that incoming and return traffic are redirected to the same PBR node" (symmetric PBR, which needs Nexus 9300-EX / -FX or later leaves); with source-only hashing in one direction the return must use destination-only | APIC-PBR | HIGH (stated); leaf hardware not read | after T1: both directions on compatible settings (both default, or `sip` paired with `dip`) count as symmetric with the caveat `PBR_SYMMETRY_HARDWARE_NOT_READ`; incompatible settings -> `ASYMMETRIC`; until T1 every multi-destination redirect carries `PBR_HASH_SYMMETRY_UNVERIFIED` |
| A-LB1 | A stateful firewall (no address translation) is inserted by PBR for both directions; a load balancer (or any translating device) is inserted by unidirectional PBR, the return being assured because the device owns the destination (VIP or translated) address of the other direction | APIC-PBR | HIGH | a one-direction redirect to an enrolled firewall -> `ASYMMETRIC`; to an LB source -> symmetric only when the LB translates the source to an address it owns (5.5) |
| A-T1 | `thresholdEnable` default no; `thresholdDownAction` is `permit` (default), `deny` or `bypass` | APIC-MIM | HIGH (values, defaults) | caveats `PBR_DOWN_ACTION_PERMIT` / `PBR_DOWN_ACTION_BYPASS` when threshold tracking is on |
| A-T2 | With `permit`, traffic is forwarded without redirect when destinations are down; `bypass` skips to the next node | ACI design §6.7 citing the APIC L4-L7 services deployment guide 5.3 | HIGH (as recorded there) | the firewall may be skipped at runtime -> those caveats are taints (5.4) |
| A-R1 | Subject `revFltPorts` (default yes) "enables the filter to apply on both ingress and egress traffic"; per-direction filters hang under `vzInTerm` / `vzOutTerm` | APIC-MIM `vzSubj`, containment of `vzRsFiltAtt` | HIGH | reverse direction of a redirect statement |
| A-R2 | Which connector (consumer, provider) redirects in which direction: a device-selection policy's logical-interface context `connNameOrLbl` carries its own redirect policy relation | APIC-MIM `vnsLIfCtx`, `vnsRsLIfCtxToSvcRedirectPol` | HIGH (structure); per-direction behaviour MEDIUM (ACI design §6.7) | return through the same firewall only when both connectors redirect to the same device |
| A-E1 | `pcEnfPref` is `enforced` (default) or `unenforced` on VRFs and EPGs | APIC-MIM `fvCtx`, `fvAEPg` | HIGH (values); "unenforced means no contracts, so no redirect" MEDIUM | unenforced VRF -> no redirect statements, note `FABRIC_VRF_UNENFORCED` |
| A-G1 | Rendered graph instance `configSt`: `applied`, `applying`, `failed-to-apply`, `none` | APIC-MIM `vnsGraphInst` | HIGH (values) | a redirect is drawn only when its instance is `applied`; otherwise `FABRIC_POLICY_NOT_MODELLED` |
| A-L1 | External EPG subnet `scope` values include `import-security`, `export-rtctrl`, `import-rtctrl`, `shared-rtctrl`, `shared-security` | APIC-MIM `l3extSubnet` | HIGH (values); classification meaning MEDIUM | external selector of a redirect; `0.0.0.0/0` never auto-placed |
| A-I1 | L3Out interface `addr` and, for SVIs on a vPC, per-side `l3extMember` `addr` | APIC-MIM `l3extRsPathL3OutAtt`, `l3extMember` | HIGH (structure) | `ACI_L3OUT_IF` attachments |
| A-RT1 | Leaf runtime routing tables exist as non-configurable classes `uribv4Route` / `uribv4Nexthop` | APIC-MIM | HIGH (existence) | not read now; fabric routing stays `CONFIG_DERIVED` (Q10) |
| A-V1 | The class and attribute set of the bank's APIC version | the model read is "latest"; version not stated in the files read | UNKNOWN | re-check per version before the gate (Q11) |

### 4.5 Sources

Read 2026-11-14 in a browser or by fetch unless marked "excerpt" (search-result summary of an official page, not
loaded) or "via" (as recorded by an earlier draft, not re-read).

- APIC-REST: Cisco APIC REST API Configuration Guide, 4.2(x) and later, "Using the REST API":
  https://www.cisco.com/c/en/us/td/docs/dcn/aci/apic/all/apic-rest-api-configuration-guide/cisco-apic-rest-api-configuration-guide-42x-and-later/m_using_the_rest_api.html
- APIC-MIM: Cisco APIC management information model reference, per-class metadata
  `https://pubhub.devnetcloud.com/media/model-doc-latest/docs/doc/jsonmeta/<package>/<Class>.json` (for example
  `.../jsonmeta/vns/SvcRedirectPol.json`); browsable at https://pubhub.devnetcloud.com/media/model-doc-latest/docs/app/index.html
- APIC-PBR: Cisco ACI Policy-Based Redirect Service Graph Design white paper (c11-739971), searched sections read:
  https://www.cisco.com/c/en/us/solutions/collateral/data-center-virtualization/application-centric-infrastructure/white-paper-c11-739971.html
- NITRO-OPS: NetScaler NITRO 14.1, "Performing NetScaler resource operations" (paging, `attrs`, `filter`, `view`, `count`):
  https://developer-docs.netscaler.com/us/en/adc-nitro-api/current-release/performing-netscaler-resource-operations.html
- NITRO-BASIC: NetScaler NITRO 14.1, "Performing basic Citrix ADC operations" (login, logout, `X-NITRO-USER`):
  https://developer-docs.netscaler.com/us/en/adc-nitro-api/current-release/performing-basic-netscaler-operations.html
- NITRO-REF: NetScaler NITRO 14.1 API reference, configuration resources
  `https://developer-docs.netscaler.com/us/en/adc-nitro-api/current-release/api-reference/configuration/<resource>.html`
  (read: `lbvserver`, `lbvserver_binding`, `lbvserver_servicegroup_binding`, `servicegroup`,
  `servicegroup_servicegroupmember_binding`, `service`, `server`, `nsip`, `route`, `nsmode`, `csvserver`,
  `csvserver_lbvserver_binding`, `nspartition`, `rnat`, `nstrafficdomain`)
- NS-CMDPOL: NetScaler, "User, user groups, and command policies":
  https://docs.netscaler.com/us/en/citrix-adc/current-release/system/authentication-and-authorization-for-system-user/user-usergroups-command-policies.html
- F5-API: F5 iControl REST API reference `https://clouddocs.f5.com/api/icontrol-rest/APIRef_tm_<module>_<object>.html`
  (read: `net_route`, `net_self`, `net_route-domain`, `ltm_virtual`, `ltm_pool`, `ltm_pool_members`, `ltm_snatpool`,
  `ltm_snat`, `ltm_snat-translation`, `ltm_nat`, `ltm_virtual-address`)
- F5-TMSH-VS: tmsh reference v16, `ltm virtual`: https://clouddocs.f5.com/cli/tmsh-reference/v16/modules/ltm/ltm_virtual.html
- F5-K14800: Order of precedence for virtual server matching (11.3.0 and later): https://my.f5.com/manage/s/article/K14800
- F5-K7820: Overview of SNAT features: https://my.f5.com/manage/s/article/K7820
- F5 K04326056 (token authentication), K01444423 (`$top` / `$skip`), K18432457 (`$select`): excerpt;
  https://my.f5.com/manage/s/article/K04326056, https://my.f5.com/manage/s/article/K01444423,
  https://my.f5.com/manage/s/article/K18432457
- F5-ROLES: BIG-IP 15.0 user roles: excerpt;
  https://techdocs.f5.com/en-us/bigip-15-0-0/big-ip-systems-user-account-administration/user-roles.html
- F5-K13590401 (pool Allow SNAT) and F5-IRULE-SNAT (iRule `snat`): via `PATH_ANALYSIS_PHASE_B_ARCHITECTURE_2026_11_05.md`
  sources.

## 5. How Trace uses topology sources

### 5.1 Graph additions

| Kind | Built from | Attaches through | Notes |
| --- | --- | --- | --- |
| `LB_UNIT` (one per source and routing context) | `ts_address`, `ts_route` | `LB_SELF`, `LB_FLOATING`, `LB_SNIP` addresses, the same segment inference as firewall interfaces | stateful unit for the return-path check; management addresses never attach |
| VIP attachment on `LB_UNIT` | `ts_virtual_server` destination | `/32` (or the virtual server's mask) on the LB node | a VIP inside a self / SNIP subnet joins that segment; otherwise it is reachable only by an upstream route to the LB (`VIP_REACHABILITY_UNPROVEN` caveat when none) |
| `FABRIC_VRF` (one per ACI VRF) | `ts_aci_subnet`, L3Out interface addresses and static routes | BD gateway subnets (`ACI_BD_GATEWAY`), L3Out interface addresses (`ACI_L3OUT_IF`) | a routing node, **not** stateful unless a contract-enforcement model is added later; an unenforced VRF is noted |
| redirect statements | `ts_aci_redirect` | bound to an enrolled firewall unit by PBR destination address | `MATCH` only when exactly one enrolled unit holds the address (ACI design §6.7); `AMBIGUOUS` / `MISSING` otherwise |

Duplicate private prefixes are never auto-joined (unchanged rule); a source address equal to an enrolled firewall
address is a conflict (`TOPOLOGY_SOURCE_ADDRESS_CONFLICT`), not a join.

### 5.2 Hop types

| Hop kind in the result | When | Own verdict |
| --- | --- | --- |
| `FIREWALL` (today's `ROUTING_CONTEXT` / `CLUSTER_UNIT`) | unchanged | policy evaluation, unchanged |
| `LB_VIRTUAL_SERVER` | destination and L4 match a virtual server on an `LB_UNIT` | `ALLOWED` with caveat `LB_NOT_A_POLICY_ENFORCER`, or `UNKNOWN` with a reason from 5.4; NAT shape `LB_TRANSLATED` |
| `LB_ROUTED` | the packet crosses an `LB_UNIT` without matching a virtual server | `UNKNOWN (LB_ROUTED_TRAFFIC_SEMANTICS_UNKNOWN)` until the vendor's handling of non-VIP traffic is frozen (4.2 / 4.3) |
| `FABRIC_ROUTED` | the packet crosses a `FABRIC_VRF` by its configured subnets and routes | `ALLOWED` with caveats `FABRIC_CONFIG_DERIVED_ROUTING` and `FABRIC_CONTRACTS_NOT_EVALUATED` (an ACI contract is a permit list; it is not evaluated in this design) |
| `FABRIC_REDIRECT` | a redirect statement matches source selector, destination selector and service | marker hop; the next hop is the bound firewall's interface on the redirect connector; caveat `FABRIC_REDIRECT_FROM_APIC_INTENT` |

### 5.3 Hop evaluation

**LB virtual server hop** (on a packet arriving at an `LB_UNIT` context):

1. Candidate virtual servers: enabled, destination/mask contains the packet destination, port equal or any, protocol
   equal or any, in the packet's context and (where read) on the ingress VLAN. Zero -> `LB_ROUTED` path. One -> step 2.
   Several -> the vendor precedence rule (4.2 F5-P1 / 4.3) if frozen, else `UNKNOWN (LB_VS_MATCH_AMBIGUOUS)` with every
   candidate listed.
2. Programmable logic present (F5 iRule or LTM policy reference; Citrix content-switching front, responder / rewrite /
   other bound policies, backup vserver) -> the hop is `UNKNOWN (LB_PROGRAMMABLE_LOGIC)`; the default members are still
   drawn as `CONDITIONAL` branches.
3. Fan-out: one branch per configured backend member, bounded by the existing limits (8 per hop, 16 in total,
   `ecmpTruncated`). Health is **not read** (PO decision): every configured member is a branch and the hop carries
   `LB_MEMBER_HEALTH_NOT_READ`. Persistence does not change the set of possible members and is not read.
4. Destination: member address and port when address translation is on (F5 `translateAddress` / Citrix by
   construction); otherwise the VIP stays (`LB_NO_DESTINATION_TRANSLATION`).
5. Source: per 4.2 / 4.3: unchanged, a single address, or an **address set** (automap with several eligible self IPs,
   a SNAT pool, several SNIPs). A set is carried as a set on the branch, never one chosen address. Mode unknown ->
   `UNKNOWN (LB_SOURCE_TRANSLATION_UNKNOWN)`.
6. Route lookup in the LB context on the member address (configured routes only: `ROUTE_ACTIVE_UNKNOWN` is a caveat
   here, not a taint, because every LB route is `CONFIG_DERIVED`; Q10 decides whether that is acceptable or whether LB
   hops stay `UNKNOWN` until a runtime-table read is approved).

**Fabric hop**: longest-prefix match in the VRF over BD subnets (deliver to the BD segment), external-EPG subnets and
L3Out static routes (forward to the L3Out next hop, which binds to an enrolled firewall interface by address when one
holds it). A prefix learned dynamically on an L3Out (BGP / OSPF) is not read: a destination covered only by an
external-EPG subnet with no static route ends `UNKNOWN (FABRIC_DYNAMIC_ROUTE_NOT_READ)`.

**Redirect**: applied before the fabric routing step, as in the ACI design §6.7: the redirect replaces the fabric
segment with consumer connector -> bound firewall hop (normal policy evaluation with that interface's zone) -> provider
connector -> destination.

### 5.4 When a path becomes UNKNOWN (closed reason list)

A hop with one of these reasons taints its branch; tracing continues; downstream hops are `CONDITIONAL`; the overall
verdict is `UNKNOWN` unless every branch is decided (frozen lattice, unchanged).

| Reason | Trigger |
| --- | --- |
| `TOPOLOGY_SOURCE_NOT_READ` | the path crosses a source with no COMPLETE read of the class it needs |
| `TOPOLOGY_SOURCE_STALE` | that read is older than the threshold (2.6) |
| `TOPOLOGY_SOURCE_SCOPE_NOT_READ` | the address falls in a partition, tenant or VRF outside the source's read scope |
| `LB_VS_MATCH_AMBIGUOUS` | several virtual servers match and precedence is not frozen |
| `LB_PROGRAMMABLE_LOGIC` | iRule, LTM policy, content switching, responder / rewrite or other bound policy present |
| `LB_SOURCE_TRANSLATION_UNKNOWN` | source translation mode unmapped, or depends on an unread setting (Citrix USIP per service, F5 pool "allow SNAT") |
| `LB_ROUTED_TRAFFIC_SEMANTICS_UNKNOWN` | traffic crosses an LB without a virtual-server match |
| `LB_CONTEXT_UNPROVEN` | the route domain / traffic domain of the ingress cannot be determined |
| `FABRIC_DYNAMIC_ROUTE_NOT_READ` | destination reachable in the fabric only by a dynamically learned route |
| `FABRIC_POLICY_NOT_MODELLED` | preferred group, intra-EPG contract, `vzAny` redirect corner cases, copy service, multi-site, endpoint security groups, L1 / L2 PBR destinations, a graph instance not `applied` |
| `LB_MEMBER_ADDRESS_DYNAMIC` | a Citrix member defined by DNS name instead of an address (4.3 C-D1) |
| `FABRIC_REDIRECT_BINDING` + `AMBIGUOUS` / `MISSING` | PBR destination held by several or no enrolled firewall |
| `PBR_HASH_SYMMETRY_UNVERIFIED` | several redirect destinations; symmetric return to the same one is not proven (4.4) |
| `PBR_DOWN_ACTION_PERMIT` / `PBR_DOWN_ACTION_BYPASS` | threshold tracking with a down action that skips the firewall (the path depends on runtime tracking state, which is not read) |
| `TOPOLOGY_SOURCE_ADDRESS_CONFLICT` | a source address equals an enrolled firewall address |

Caveats (a verdict carrying one is never plain, but they do not taint): `LB_NOT_A_POLICY_ENFORCER`,
`LB_MEMBER_HEALTH_NOT_READ`, `LB_PAIR_SYNC_NOT_READ`, `LB_NO_DESTINATION_TRANSLATION`, `LB_DSR_LIKE_MODE`,
`LB_POOL_SNAT_SETTING_NOT_READ`, `LB_RETURN_DEPENDS_ON_SERVER_GATEWAY`, `VIP_REACHABILITY_UNPROVEN`,
`FABRIC_CONFIG_DERIVED_ROUTING`, `FABRIC_CONTRACTS_NOT_EVALUATED`, `FABRIC_REDIRECT_FROM_APIC_INTENT`,
`FABRIC_VRF_UNENFORCED`, `PBR_SYMMETRY_HARDWARE_NOT_READ`.

### 5.5 Asymmetric detection

The return walk is unchanged in mechanism; topology sources add stateful units and translations to it.

- `LB_UNIT` is a stateful unit (`stateKey`): a return that skips an LB the forward path crossed is `ASYMMETRIC` ->
  overall `UNKNOWN` + `OUT_OF_STATE_DROP_LIKELY`.
- **With source translation** (F5 automap / SNAT pool, Citrix SNIP): the reply's destination is an LB-owned
  address. If that address lies in a segment the LB attaches to, the walk reaches the LB by connected routing and undoes
  the translation (symmetric by construction). If it is a set, every address is walked; the return is `SYMMETRIC` only
  when every one returns to the LB. A SNAT address outside every LB-attached segment needs an upstream route; without
  one the return is `INCOMPLETE` (`RETURN_PATH_UNPROVEN`).
- **Without source translation** (F5 SNAT none, Citrix USIP): the server replies to the client address. The servers are
  not enrolled, so their default gateway is not known. The walk starts, as today, at every unit attached to the
  member's segment. If the LB is the only unit there, the return crosses it. If another unit (a firewall, the fabric)
  is also attached, at least one return branch skips the LB -> `ASYMMETRIC` is reported for that branch and the
  overall verdict cannot be `ALLOWED`. The hop shows `LB_RETURN_DEPENDS_ON_SERVER_GATEWAY`. This is the classic
  one-arm-without-SNAT failure and is exactly what the bank's engineers check by hand.
- **Fabric redirect**: the return is modelled through the same firewall only when the redirect applies in the reverse
  direction too and both connectors redirect to the same device (ACI design §6.7; 4.4 A-R1, A-R2). A one-direction
  redirect to a firewall -> `ASYMMETRIC`. A one-direction redirect to a load balancer is the vendor's own design and is
  symmetric only when that LB translates the source to an address it owns (4.4 A-LB1, then the SNAT rule above).
  Several destinations -> `PBR_HASH_SYMMETRY_UNVERIFIED` until T1 freezes 4.4 A-H2; after T1, compatible hashing in
  both directions is symmetric with `PBR_SYMMETRY_HARDWARE_NOT_READ`, incompatible hashing is `ASYMMETRIC`.
- A redirect on the forward path combined with a non-redirected (routing-table) return through a different firewall is
  detected by the same `stateKey` comparison and named `ASYMMETRIC_RETURN` with the fabric hop listed.

### 5.6 Validation

No device-side matcher is proposed for load balancers or the APIC (no read-only "explain this flow" request was found in
the sources of 4.5). LB and fabric hops are `NOT_EVALUABLE` in path validation (A6) until such a read is found, gated and approved. Real
environment validation (T9) compares drawn paths with the bank network team's documented paths for PO-chosen samples.

### 5.7 Output

The path view (Fable lane) shows LB and fabric hops with their own icon and a per-branch member tab; translated tuples
per branch; source-set rendering for SNAT sets; the source pseudonym and its last read time on every hop it fed. The
executive summary names the topology sources used and any stale one.

## 6. Delivery plan

Each step is its own PR with targeted tests; network-facing steps stay `AUTOMATED_VALIDATED` until T9.

| Step | Content | New reads | Depends on | Size (engineer-days) |
| --- | --- | --- | --- | --- |
| T0 | PO decision record (2026-11-14 decision, D-9 closed); PO answers to section 7; approval of the literal tables 3.1-3.3 **in principle** | none | PO | - |
| T1 | Vendor-semantics freeze (A0 style) for section 4: F5, Citrix, ACI PBR; amendment to `PATH_ANALYSIS_DESIGN.md` for node kinds, hop kinds and reasons | none | T0 | 3 |
| T2 | Topology source registry: migration (dry-run BEGIN/ROLLBACK on the live database), RBAC actions, TLS pin reuse, credential reference, masking rules, CLI, screen (Fable), architecture tests `TS-1`, `TS-2` (empty literal set) | none | T0 | 5 |
| T3 | Measurement record per kind: one PO-approved run of each literal against one PO-chosen source, sanitized counts and field-name presence only, committed next to the gate document | the approved literals, once | T0 approval of exact literals; T2 | 1 + team time |
| T4 | F5 reader: gate rows (migration + fixture), read job, parsing to 2.5 tables, failure behaviour, masking tests | F5 rows | T3 | 4 |
| T5 | Citrix reader, same shape | Citrix rows | T3 | 4 |
| T6 | APIC reader (trimmed list), same shape | APIC rows | T3 | 5 |
| T7 | Trace: `LB_UNIT`, `LB_VIRTUAL_SERVER`, `LB_ROUTED`, source sets, return-path additions, golden tests | none | T1, T4 or T5 | 6 |
| T8 | Trace: `FABRIC_VRF`, `FABRIC_ROUTED`, redirect overlay (ACI design §6.7 and 5.3 here) | none | T1, T6 | 6 |
| T9 | Real-environment validation: PO-chosen sample flows through each kind, compared with the team's documented paths (masked) | none | T7, T8 | 2 + PO time |

T4 and T5 can run in parallel lanes (disjoint files) once T2 and T3 are in; T7 can start with whichever LB vendor lands
first. Total about 36 engineer-days plus PO and team time.

Smallest valuable first step after T0-T2: **T5 or T4 for one LB pair in front of a known application**, because the
VIP-to-member translation is the change most visible in today's Trace results.

## 7. Questions for the PO

| # | Question | Recommendation |
| --- | --- | --- |
| Q1 | Accept one generic "topology source" class for all three kinds, replacing the ACI design's APIC-specific tables and rights (X-2)? | Yes |
| Q2 | Credentials: will the network team create one dedicated read-only account per APIC cluster and per LB pair (APIC `read-all` in domain `all`; F5 Auditor; Citrix `read-only` command policy), and confirm the role can GET every listed object and nothing else? Password login first, APIC certificate login as later hardening? | Yes; password first |
| Q3 | Which sources first: which APIC cluster(s), which LB pairs (a short list of PO-chosen pairs in front of known applications), which F5 partitions, Citrix partitions, route domains and traffic domains? | One APIC (the phase-2 fabric), two LB pairs (one per vendor) |
| Q4 | TLS: pin on first use with security_admin authorization (as other HTTPS appliances), or verification against the bank CA? | Pin first; CA verification when the team confirms the certificates are CA-issued |
| Q5 | Read frequency: manual only, or scheduled once a day inside an admission window plus manual? Staleness threshold 24 h after a missed scheduled read? | Daily in the 06:00 window plus manual; 24 h |
| Q6 | LB HA pairs: read one unit (whichever answers) and carry `LB_PAIR_SYNC_NOT_READ`, or add one sync-status read per pair (a small inventory-like read)? | One unit, caveat |
| Q7 | Object names (virtual servers, pools, tenants, EPGs): store raw names in redacted columns for the raw view, or keyed hash only (labels come from pseudonyms; raw view shows no name)? | Raw names in redacted columns |
| Q8 | Retention: last COMPLETE read per class, run records 90 days? | Yes |
| Q9 | May aiview start a topology-source read run (CLASS_0, masked), as for other CLASS_0 reads? | Yes, masked |
| Q10 | LB and fabric routes are configuration (`CONFIG_DERIVED`), not runtime tables. Accept them as caveats on LB / fabric hops, or keep those hops `UNKNOWN` until runtime-table reads are approved? | Accept as caveats; revisit after T9 |
| Q11 | Version facts: will the team state the APIC, BIG-IP and ADC software versions (needed to check class and attribute names), instead of neXus reading them? | Yes, team states them |
| Q12 | Are endpoint security groups, preferred groups, multi-site or vzAny redirects in use on the fabric? Do any LBs run dynamic routing, DSR, or Citrix cluster (CLIP) mode? | Ask the team; each "yes" is an explicit `UNKNOWN` until modelled |
| Q13 | Approve in principle the literal tables 3.1-3.3, so T3 measurement can be planned (each exact literal still comes back for approval at gate time)? | Yes |

## 8. Acceptance criteria (for the contract that follows)

- `TS-1` No capability, job, controller or report other than the topology-source reader and the path engine takes a
  `topology_source_id`; a source never appears in device lists, counts, inventory, backup, compliance, lifecycle,
  failover, system logs or discovery (architecture test).
- `TS-2` The reader issues only the approved literal set (pinned list) and no method other than GET apart from the
  session login / logout.
- `TS-3` No raw response is stored; no member health, persistence, profile, certificate or iRule / policy body is
  parsed into storage.
- `TS-4` A failed, partial, truncated or inconsistent class never replaces earlier data; the affected hops are
  `TOPOLOGY_SOURCE_STALE` or `TOPOLOGY_SOURCE_NOT_READ`.
- `TS-5` aiview and masked roles see pseudonyms and masked addresses only, on every new key (test per surface);
  embedded addresses in names are masked.
- `TS-6` A VIP hop with several matching virtual servers, programmable logic, or an unknown source-translation mode is
  never a decided hop.
- `TS-7` A source-translation set is carried as a set; no single address is chosen from it.
- `TS-8` A return that skips an LB or firewall the forward path crossed (or the reverse) is `ASYMMETRIC` and never a
  clean `ALLOWED`; a one-direction redirect likewise.
- `TS-9` A PBR destination held by zero or several enrolled firewalls is `MISSING` / `AMBIGUOUS`; never bound by name.
- `TS-10` TLS mismatch refuses before any credential is sent; the token never leaves memory; logout runs on every
  path.
- `TS-11` Every screen action has a CLI equivalent with the same RBAC and masking.

## 9. Risks

| Risk | Effect | Mitigation |
| --- | --- | --- |
| Vendor semantics guessed (VS precedence, SNAT defaults, routed traffic on an LB, PBR symmetry) | wrong drawn path presented as fact | T1 freeze; every unfrozen item is a named `UNKNOWN` reason (5.4) |
| Read-only role can still reach command-execution endpoints (F5 `/mgmt/tm/util/*`, NITRO writes) | a defect could change a device | closed literal set (`TS-2`), never-issued list (3.4), least role confirmed by the team (Q2) |
| Large estates (thousands of virtual servers, APIC objects) | slow runs, load on controllers | paging, response caps, run ceiling, two runs at most at once, no retry |
| Configuration-only routes differ from forwarding state | drawn path wrong when dynamic routing dominates | `CONFIG_DERIVED` caveats; `FABRIC_DYNAMIC_ROUTE_NOT_READ`; Q10 |
| Names leak through key-based masking | raw application names to aiview | rules ship with the exposing change; fail-closed withholding |
| Scope creep back to managed devices | inventory, backup and compliance arrive by the back door | `TS-1`; any further read needs its own literal approval |

## Cross-references

- `docs/design/PATH_ANALYSIS_DESIGN.md` (FROZEN)
- `docs/design/PATH_ANALYSIS_VENDOR_SEMANTICS.md` (FROZEN)
- `docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md` (RATIFIED)
- `docs/design/PATH_ANALYSIS_PHASE_B_ARCHITECTURE_2026_11_05.md` (DRAFT)
- `docs/design/LOAD_BALANCER_ARCHITECTURE_2026_11_05.md` (DRAFT - DO NOT FREEZE)
- `docs/design/ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md` (DRAFT)
- `docs/design/PO_DECISION_RECORD_2026_11_03_PATH_ANALYSIS_PHASE_A.md` (RATIFIED)
