# Load balancers in neXus — F5 BIG-IP and Citrix ADC (NetScaler) architecture

**Status: DRAFT — DO NOT FREEZE.** Architecture proposal only. No code, migration, gate row or device command is
authorized by this document. Every API request and CLI command below is **PROPOSED**: each exact request needs its own
Product Owner approval through the network-device command gate (`docs/AI_DEVELOPMENT_PROTOCOL.md`; `AGENTS.md` PO
Amendment 2026-09-26) and its own gate migration before any code issues it. Every vendor semantic marked `UNKNOWN` stays
`UNKNOWN` until official documentation is read for it and a real-environment measurement corroborates it.

- Date: 2026-11-05. Movement: `ARCHITECTURE` (document only). No device or live system was contacted.
- Input: PO decision D-9 (2026-11-05): the estate runs **both** F5 BIG-IP and Citrix ADC (NetScaler).
- Masking: every example uses masked pseudonyms (`LB-KILO-03`, `FW-TANGO-04`) and documentation addresses
  (`192.0.2.0/24`, `198.51.100.0/24`). The `LB-*` prefix is illustrative; no LB pseudonym kind exists yet (§8.3).

## 0. Authority and contradictions (report, not reconciled)

| # | Finding | Effect on this draft |
|---|---|---|
| C-1 | D-9 is answered only in the task brief (`docs/cloud-briefs/lb-architecture.md`, `docs/cloud-briefs/path-phase-b.md`). The ratified record `PO_DECISION_RECORD_2026_11_03_PATH_ANALYSIS_PHASE_A.md` still lists D-9 as **open**, and `PATH_ANALYSIS_DESIGN.md` §8 (FROZEN) says the same. A cloud brief is not a decision record. | This draft takes "both vendors" as its planning assumption. Before anything here becomes a contract, the PO should append the D-9 answer (both vendors, date, scope) to the 2026-11-03 record or write a new record. |
| C-2 | `PATH_ANALYSIS_DESIGN_FABLE.md` §1.4/§4.6 (DRAFT) reserves the LB node kind and tables. `PATH_ANALYSIS_DESIGN.md` (FROZEN) points to those sections as the B/C hooks, but does not freeze their content. | §6 reuses the reserved shapes as a starting point and changes them where the vendor semantics need it (persistence, iRules/policies, member state). Those changes are proposals, not amendments to a frozen document. |
| C-3 | `PATH_ANALYSIS_DESIGN_FABLE.md` §4.6 says down pool members are "skipped". Member availability is a runtime observation taken at collection time, not configured intent (`AGENTS.md`, evidence laws). | §6.4 proposes that down members are listed as their own branch with `MEMBER_UNAVAILABLE_AT_COLLECTION`, never silently dropped. |
| C-4 | Vendor documentation sites were not reachable from the drafting environment (`clouddocs.f5.com`, `techdocs.f5.com` and `developer-docs.netscaler.com` failed DNS through the session proxy). Search found the pages listed in §11, but their content was not read in full. | Every endpoint and field below is marked **PROPOSED, docs-to-read**. Freezing needs the A0-style vendor-semantics pass (the `PATH_ANALYSIS_VENDOR_SEMANTICS.md` method) done from the documentation itself. |

## 1. Objective and scope

**Objective.** Bring F5 BIG-IP (LTM) and Citrix ADC (NetScaler) into neXus following the same path every existing vendor
took: enrolment → confirm → inventory → backup → configuration → compliance → path hop. Every step stays read-only,
except a backup creation that removes itself afterwards (class 1, BW-2, §5).

**In scope.** BIG-IP LTM (virtual servers, pools, nodes, SNAT, self IPs, VLANs, route domains, traffic groups, device
trust); Citrix ADC load-balancing and content-switching virtual servers, services and service groups, SNIP/VIP/RNAT,
VLANs, HA pair; UCS and `ns.conf` backup; path hops.

**Out of scope (explicitly).**
- BIG-IP modules other than LTM: GTM/DNS, APM, ASM/Advanced WAF, AFM. Their presence is reported as a fact only
  (`sys provision`); their objects are `NOT_COLLECTED`.
- Citrix Gateway/AAA, GSLB, NetScaler clusters (CLIP), SDX/VPX hypervisor management, ADM/Console as a management plane.
- BIG-IQ as a management plane. It could later play the part Panorama plays for Palo Alto, but that needs its own decision.
- Any write: no pool-member enable/disable, no failover, no config sync, no restore. Failover authority stays Java and
  CP/PAN-only (`AGENTS.md`, architectural invariants).
- iControl REST `/mgmt/tm/util/*` (bash, unix-ls and similar) and NITRO `nsrunningconfig` writes. These run arbitrary
  commands or change state. The gate rows must list them as **never issued** (§3.4).

## 2. How existing vendors are integrated (what an LB integration copies)

Verified in source on `cloud-base/1105` (latest migration **V189**):

| Layer | Existing pattern | Closest analog for an LB |
|---|---|---|
| Enrolment | `DeviceAddSingleService.VENDOR_MAPPINGS` (vendor → transport, confirm capability); roles `gateway / management_server / appliance` (`chk_devices_role`, V65); `DeviceRegistrationService.APPLIANCE_VENDORS = infoblox, radware, pulse_secure`. | Radware/Infoblox: `https` transport, role `appliance`. |
| Transport + trust | `HttpsDeviceClient` (no redirects, same-host paths only, a fresh TLS context per request); `CertificatePinningTrustManager` + `https_endpoint_cert_trust` (V112: trust on first use, WARN/REFUSE, strict per device). SSH host-key trust (V24) has a vendor CHECK limited to `CHECK_POINT`. | HTTPS with the V112 pin. TOFU applies automatically to every vendor except `palo_alto`. |
| Session | `HttpsDeviceCalls.login(...)` returns a session cookie; Radware `CcSession` is AutoCloseable and always logs out; `CyberControllers.oneAtATime` serializes logins per host:port. | iControl REST token login and NITRO login/logout (§3). |
| Gate | `gate_registry` (V4) 16 columns; fixture `gate_registry_fixture.yaml`; gate rows inserted by Flyway with `ON CONFLICT (gate_id) DO NOTHING` and an actor fingerprint; a test checks fixture against SQL (e.g. `PathValidationGateTest`). The HTTPS executors issue a **closed constant request set** (`HttpsVendorPlan`) that matches migrated rows; they do no runtime `GateResolver` lookup. | Same, with new `HttpsVendorPlan` keys or vendor executors (ASA/Fortinet pattern). |
| Capability ids | `<prefix>_<area>_<verb>`; HTTPS appliances share `device_confirm_https`, `https_inventory_collect`, `https_vendor_backup`; per-vendor ids exist where the run shape differs (`rdw_cc_config_backup`, `asa_*`, `fgt_*`). | Proposed `f5_*` / `adc_*` ids (§9). |
| Inventory | CP/PAN: `InventoryCapabilityExecutor` + `InventoryVendor` enum; others: `HttpsVendorExecutor.inventory()` → `device_interface`, `device_interface_address` (role CHECK `member / cluster_virtual`, V13), `device_route` (+V184 columns), `devices.observed_*`, `ha_pair_claim` / `ha_unit_token` (V96). | FortiGate/ASA shape plus new LB tables. |
| Backup | `VENDOR_BACKUP_CONTRACTS_2026_09_22.md` (FROZEN) §0.3 seven-step run shape; `ArtefactStoreCipher` (AES-256-GCM per artefact, wrapped data key); `backup_artefact` vendor CHECK (V64); export passphrase as a second secret in `device_secret_reference`. | Radware getcfg (passphrase-protected keys) and CP `add backup` (create → fetch → delete by name). |
| Configuration | `ConfigParseResult` → sections/settings, secret lines withheld as `<keyword> [withheld]`; vendor CHECK V86; `ConfigurationVendor` enum. | `FortiGateConfigProcessor` / `AsaConfigProcessor`. |
| Compliance | `ComplianceControl` → `VendorBinding(vendor, platformFamily, EvidenceRequirement(sectionId, settingKey, requiredEvidenceId, gateEntryId), AssertionRule)`; `Ui2ComplianceServer` dispatch by vendor header; `UNKNOWN + EVIDENCE_MISSING`. | `FortinetAsaComplianceCatalog`. |
| Path | `TopologyBuilder` filters to `check_point / palo_alto`; `PathEngine` marks other vendors `UNSUPPORTED_NODE_VENDOR`; `NatApplied(status, reason, applied, rules, undecidableRules)`; UNKNOWN taints the branch. | New `HopSemantics` strategy for node kind `lb_virtual_server` (§6). |
| Masking (aiview) | `PrivacyMaskingResponseBodyAdvice` masks **by JSON key**. Keys with no rule get only IP masking and already-known estate-name replacement. | New name keys need explicit rules, or LB names leak to aiview (§8.3). |

**Design consequence.** Both vendors fit the HTTPS-appliance lane: a REST API over the existing client, V112
certificate pinning, and a login/logout session. Neither needs an SSH path. tmsh and NetScaler CLI literals appear below
only as the documented equivalents that the PO measurement can use to cross-check; this draft does **not** propose
an SSH transport for either vendor. The diagnostic-path law (`AGENTS.md`) argues against opening a second
credential path when REST already answers the question.

## 3. Proposed read-only requests per vendor

All rows: `CLASS_0_READ` unless stated otherwise, HTTPS, credential in a header or body and never in the URL, one session per run,
retry `none`, frequency `once per device per run` (inventory nightly, configuration per its schedule). Timeouts are
starting proposals to be measured. "Docs" refers to §11.

### 3.1 F5 BIG-IP — iControl REST (TMOS)

Session (connect step; carries no command literal, modelled like PAN `type=keygen`):
- `POST /mgmt/shared/authn/login` with body `{username, password, loginProviderName}` → token, sent afterwards as
  header `X-F5-Auth-Token`. Close the session with `DELETE /mgmt/shared/authz/tokens/<token>` (docs F-2, F-3: located on
  F5's community site; the official iControl REST User Guide section on token auth must be read before freeze).
  The `loginProviderName` value for the estate's remote-auth (LDAP/RADIUS/TACACS) is **UNKNOWN**.

| # | Proposed request (GET unless noted) | tmsh equivalent (for cross-check only) | Purpose | Phase | Secret risk |
|---|---|---|---|---|---|
| F1 | `/mgmt/tm/sys/version` | `show sys version` | software version/build (confirm, identity) | L1 | none |
| F2 | `/mgmt/tm/sys/hardware` | `show sys hardware` | platform/model; chassis serial (compared locally, §4.1) | L1 | serial (identity) |
| F3 | `/mgmt/tm/sys/global-settings?$select=hostname` | `list sys global-settings hostname` | observed hostname (presentation only) | L1 | none |
| F4 | `/mgmt/tm/sys/provision` | `list sys provision` | which modules are provisioned (LTM present? others → `NOT_COLLECTED`) | L1 | none |
| F5 | `/mgmt/tm/cm/device` | `list cm device` | device-trust members, failover state per device, chassis id | L1 | serial-like ids |
| F6 | `/mgmt/tm/cm/traffic-group/stats` | `show cm traffic-group` | active/standby **per traffic group** (docs F-6, community mapping) | L1 | none |
| F7 | `/mgmt/tm/cm/failover-status` | `show cm failover-status` | local failover status (docs F-6) | L1 | none |
| F8 | `/mgmt/tm/cm/sync-status` | `show cm sync-status` | config-sync status of the local device (docs F-4, F-5) | L1 | none |
| F9 | `/mgmt/tm/net/vlan` | `list net vlan` | VLANs, tags, interfaces | L1 | none |
| F10 | `/mgmt/tm/net/self` | `list net self` | self IPs (non-floating and floating), VLAN, traffic group | L1 | addresses |
| F11 | `/mgmt/tm/net/route-domain` | `list net route-domain` | route domains (L3 contexts, `%ID`) | L1 | none |
| F12 | `/mgmt/tm/net/route` | `list net route` | static routes (configured intent; runtime FIB is **UNKNOWN**, §6.6) | L1 | addresses |
| F13 | `/mgmt/tm/auth/partition` | `list auth partition` | administrative partitions to enumerate | L1 | none |
| F14 | `/mgmt/tm/ltm/virtual?expandSubcollections=true` | `list ltm virtual all-properties` | virtual servers: destination, mask, ip-protocol, source, pool, SNAT setting, translate-address/port, VLAN list, profiles, persistence, rules, policies, enabled | L1 | names, addresses |
| F15 | `/mgmt/tm/ltm/virtual-address` | `list ltm virtual-address` | VIP objects, traffic group, ARP | L1 | addresses |
| F16 | `/mgmt/tm/ltm/pool?expandSubcollections=true` | `list ltm pool members` | pools, LB method, members (address:port), priority groups, monitors | L1 | names, addresses |
| F17 | `/mgmt/tm/ltm/pool/<~partition~pool>/members/stats` (per pool) | `show ltm pool <p> members` | member availability/enabled state at collection time. Bug ID1088981: use the partition-qualified path (docs F-7). No single call covers every partition (community, docs F-8), so this is one request per pool, capped (§3.3). | L1 | names, addresses |
| F18 | `/mgmt/tm/ltm/node` | `list ltm node` | nodes (member addresses; node names often embed an address or hostname) | L1 | names, addresses |
| F19 | `/mgmt/tm/ltm/snatpool`, `/mgmt/tm/ltm/snat`, `/mgmt/tm/ltm/snat-translation`, `/mgmt/tm/ltm/nat` | `list ltm snatpool` and the others | source translation objects | L2 | addresses |
| F20 | `/mgmt/tm/ltm/persistence/*` (profile collections) | `list ltm persistence` | persistence profile type and settings (path: decides UNKNOWN, §6.3) | L2 | cookie names/secrets possible → withheld |
| F21 | `/mgmt/tm/ltm/rule?$select=name,partition` | `list ltm rule` (names only) | iRule **names and references only**. The TCL body is not fetched in L1/L2 (§7.2). | L2 | names |
| F22 | `/mgmt/tm/ltm/policy?expandSubcollections=true` | `list ltm policy` | LTM policies (forward actions can select a pool → UNKNOWN in path) | L2 | names |
| F23 | `/mgmt/tm/sys/ucs` | `list sys ucs` | list existing UCS archives (pre-check and post-cleanup check) | L1 | file names |

Backup (class 1, BW-2; see §5.1): F-B1 create, F-B2 download, F-B3 delete.

### 3.2 Citrix ADC — NITRO REST (`/nitro/v1/...`)

Session (connect step): `POST /nitro/v1/config/login` with `{"login":{"username","password","timeout"}}` →
`NITRO_AUTH_TOKEN` cookie; close the session with `POST /nitro/v1/config/logout`. NITRO also accepts `X-NITRO-USER`/`X-NITRO-PASS`
headers per request (docs C-2). This draft proposes the session form (one login per run) so that credentials cross the
wire once. Admin partitions are switched with a partition-scoped session. The exact mechanism (`switch ns partition` /
NITRO equivalent) is **UNKNOWN**. Until it is read and approved, a partitioned ADC is collected for the default partition only and
reports `PARTITIONS_NOT_COLLECTED`.

| # | Proposed request (GET) | CLI equivalent (cross-check only) | Purpose | Phase | Secret risk |
|---|---|---|---|---|---|
| N1 | `/nitro/v1/config/nsversion` | `show ns version` | version/build | L1 | none |
| N2 | `/nitro/v1/config/nshardware` | `show ns hardware` | platform, serial / host id (compared locally) | L1 | serial |
| N3 | `/nitro/v1/config/nshostname` | `show ns hostName` | observed hostname (presentation only) | L1 | none |
| N4 | `/nitro/v1/config/hanode` and `/nitro/v1/stat/hanode` | `show ha node` | HA node ids, peer address, state; `hacurmasterstate` (read-only field; docs C-6, C-7) | L1 | peer address |
| N5 | `/nitro/v1/config/nsip` | `show ns ip` | NSIP / SNIP / VIP / MIP addresses with type, traffic domain, VLAN | L1 | addresses |
| N6 | `/nitro/v1/config/vlan` (+ `vlan_interface_binding`, `vlan_nsip_binding` with `bulkbindings`) | `show vlan` | VLANs, tags, bound interfaces and IP subnets | L1 | addresses |
| N7 | `/nitro/v1/config/route` | `show route` | routes (configured vs. runtime semantics **UNKNOWN**) | L1 | addresses |
| N8 | `/nitro/v1/config/nstrafficdomain` | `show ns trafficDomain` | traffic domains (L3 contexts) | L1 | none |
| N9 | `/nitro/v1/config/lbvserver` | `show lb vserver` | LB vservers: `ipv46`, `port`, `servicetype`, `lbmethod`, `persistencetype`, state (docs C-1) | L1 | names, addresses |
| N10 | `/nitro/v1/stat/lbvserver` | `stat lb vserver` | runtime state / health at collection time | L1 | names |
| N11 | `/nitro/v1/config/lbvserver_servicegroup_binding?bulkbindings=yes`, `lbvserver_service_binding?bulkbindings=yes` | `show lb vserver <v>` | vserver → service group / service. `get (all)` works only with `bulkbindings` (docs C-3, C-4). | L1 | names |
| N12 | `/nitro/v1/config/servicegroup`, `servicegroup_servicegroupmember_binding?bulkbindings=yes`, `/nitro/v1/config/service`, `/nitro/v1/config/server` | `show serviceGroup`, `show service`, `show server` | members (address:port), member state | L1 | names, addresses |
| N13 | `/nitro/v1/config/csvserver` (+ `csvserver_cspolicy_binding?bulkbindings=yes`, `csvserver_lbvserver_binding`) | `show cs vserver` | content-switching vservers. Policy-selected target → UNKNOWN in path (§6.3). | L2 | names, addresses |
| N14 | `/nitro/v1/config/rnat` | `show rnat` | reverse NAT for server-initiated traffic | L2 | addresses |
| N15 | `/nitro/v1/config/nsmode` | `show ns mode` | global modes that decide source-IP behavior (USIP/USNIP: **field semantics UNKNOWN until read**) | L2 | none |
| N16 | `/nitro/v1/config/nsconfig` | `show ns config` | last saved / last changed time ("unsaved running config" signal; **field names UNKNOWN**) | L2 | none |
| N17 | `/nitro/v1/config/nspartition` | `show ns partition` | admin partitions to enumerate | L1 | none |
| N18 | `/nitro/v1/config/nsrunningconfig` | `show ns runningConfig` | running config text for the configuration plane (§7) | L2 | **secret-bearing** (encrypted secrets, §5.3) |
| N19 | `/nitro/v1/config/systembackup` (GET list) | `show system backup` | list existing backups (pre-check and post-cleanup check; docs C-8) | L1 | file names |

Backup (class 1, BW-2; see §5.2): N-B1 create, N-B2 download, N-B3 delete.

### 3.3 Bounds that apply to both vendors

- Response cap per request (proposed 16 MiB for collection reads, the same order as Radware `ccDevices` at 8 MiB).
  An over-cap response is `COLLECTION_FAILED (RESPONSE_TOO_LARGE)`, never truncated and parsed.
- Per-pool stats (F17) are capped per run (proposed 2,000 pools). If an estate has more, the run reports
  `MEMBER_STATE_PARTIAL` and those members carry `UNKNOWN` state.
- Pagination: NITRO `pagesize`/`pageno` and iControl `$top`/`$skip` are **UNKNOWN** in exact behavior. L1 measures whether
  the estate sizes need them before any paged form is gated.
- Concurrency: one session per device. Logins are serialized per host:port (Radware `oneAtATime` precedent) until the
  vendors' session limits are measured.
- No polling loop except the backup task poll in §5.

### 3.4 Never issued (must be listed in the gate document)

iControl REST: any `POST/PUT/PATCH/DELETE` except the session login/logout and the three backup rows; `/mgmt/tm/util/*`;
`/mgmt/shared/*` except authn/authz token handling and the UCS download path; `tmsh run`, `load`, `save sys config`.
NITRO: any `POST/PUT/DELETE` except login/logout and the three backup rows; `?action=` forms other than the backup
create; `/nitro/v1/config/systemfile` writes; `save ns config`; `sync ha files`; `force ha failover`.

## 4. Evidence model

Principle: Inventory = runtime/operational observation; Configuration = configured state; neither is used as proof of
the other (`AGENTS.md`). Every new column treats NULL as `UNKNOWN`. Every address- or name-bearing column is registered
in `audit_redaction_policy` (tier 2), as the path-analysis rule requires (`PATH_ANALYSIS_DESIGN.md` §3).

### 4.1 Device identity and HA (L1)

| Fact | F5 source | Citrix source | Storage | Notes |
|---|---|---|---|---|
| model, version, hostname | F1–F3 | N1–N3 | `devices.observed_*` (V12) | hostname is presentation identity, never a join key |
| serial | F2 / F5 | N2 | `device_platform_facts.serial_number` (V46) | compared locally against the recorded identity (`MATCH/MISMATCH`), never printed; opaque string, no normalization (identity law) |
| HA role | F5–F7 | N4 | `devices.observed_ha_role` + new `device_inventory_ha` sources `f5_cm_traffic_group`, `adc_hanode` (widen V20 CHECK) | **F5 is active/standby per traffic group, not per device.** A device can be active for one group and standby for another. Device-level role is `MIXED` when its traffic groups differ; per-group facts go to `lb_traffic_group_state`. |
| pair claim | F5 device-group membership | N4 peer node | `devices.ha_pair_claim` / `ha_unit_token` (V96) | F5: hash of the sorted chassis ids of the sync-failover group (FortiGate precedent). Citrix: hash of the sorted node addresses (ASA precedent). One side's claim stays one-sided until the peer's own read corroborates it in the same pass (`peer_follow_outcome`). Choice of identity input is **PO decision Q-4**. |
| config sync | F8 | N4 (sync state field **UNKNOWN**) | `lb_ha_sync_state` | sync "in sync" ≠ pair health ≠ failover readiness |

### 4.2 Network attachment (L1) — feeds topology

- **VLANs** → `device_interface` kind `vlan`, `vlan_id` (V20) and the routing context = route domain / traffic domain.
- **Self IPs / SNIPs** → `device_interface_address` with **new roles** (widen the V13 CHECK): `lb_self` (F5 non-floating
  self, Citrix SNIP), `lb_floating` (F5 floating self in a traffic group), `lb_vip` (F5 virtual address / Citrix VIP),
  `lb_mgmt` (F5 management / Citrix NSIP, never a data-plane attachment). The existing `member / cluster_virtual` roles are
  unchanged.
- **Route domains / traffic domains** → `device_interface.routing_context` and `device_route.context` (V184). F5
  `%ID` suffixes are parsed as context, never stripped from an address and discarded. Route domain 0 and the default traffic
  domain are recorded explicitly, not implied.
- **Routes** → `device_route` with `evidence_grade = CONFIG_DERIVED` for F12/N7 until a runtime-table read is approved.
  `is_active` stays NULL (UNKNOWN).

### 4.3 Load-balancing objects (L1, new tables)

Shapes adapted from `PATH_ANALYSIS_DESIGN_FABLE.md` §1.4. Opaque ids; names are stored and masked on the way out.

```
lb_virtual_server(id, device_id, inventory_run_id, vendor_kind {F5_VIRTUAL | ADC_LB_VSERVER | ADC_CS_VSERVER},
  partition, routing_context, name, vip_address, vip_prefix_len, port, port_any, ip_protocol, service_type,
  source_match, vlan_scope {ALL | ENABLED_ON | DISABLED_ON | UNKNOWN}, vlan_refs[],
  vs_type {STANDARD | FORWARDING_IP | FORWARDING_L2 | REJECT | PERFORMANCE_L4 | OTHER | UNKNOWN},
  enabled, runtime_state, default_pool_id, translate_address, translate_port,
  snat_mode {NONE | AUTOMAP | POOL | VENDOR_DEFAULT | UNKNOWN}, snat_pool_id,
  persistence_kind, has_irules, has_policies, has_cs_policies, backup_vserver_id, evidence_grade)
lb_pool(id, device_id, inventory_run_id, partition, name, lb_method, min_active_members, has_priority_groups)
lb_pool_member(id, pool_id, node_name, address, port, routing_context, admin_state, monitor_state,
  priority_group, ratio_or_weight, state_collected_at)
lb_snat_pool(id, device_id, inventory_run_id, name, addresses[])
lb_traffic_group_state(device_id, inventory_run_id, traffic_group, local_state, collected_at)   -- F5 only
lb_object_reference(vs_id, kind {IRULE | LTM_POLICY | CS_POLICY | PERSISTENCE_PROFILE | RESPONDER | REWRITE}, name, priority)
```

- `vendor_kind`, `vs_type` and `snat_mode` values map vendor fields. **Every mapping is UNKNOWN until A0-style reading**.
  An unmapped vendor value is stored as `OTHER/UNKNOWN` with the raw enum name in a bounded text column, never
  guessed into a known value.
- `snat_mode = VENDOR_DEFAULT` exists because the two vendors differ in their defaults. On BIG-IP a virtual server
  without a SNAT setting does not translate the source (to be confirmed, docs F-1). On Citrix ADC the source address
  used toward the server depends on global modes (USIP/USNIP, N15) and per-service settings. neXus never assumes either
  default. Unresolved means `UNKNOWN`.
- Member `monitor_state` / `admin_state` are runtime observations with `state_collected_at`. They are never configured intent.
- Storage follows decision D-1 (hybrid): these are inventory rows replaced per run, the same as `device_route`. The
  topology graph is still built in memory. Retention follows the inventory run retention, and device deletion removes
  the rows (the migration says so).

### 4.4 Persisted address values (PO decision Q-1)

Pool-member, VIP and SNAT addresses are new persisted CLASS 2 values from a new source (`PATH_ANALYSIS_DESIGN_FABLE.md`
P-4). Recommendation: the V13 treatment (values persisted, masked on the way out for aiview, tier-2 audit redaction),
decided by the PO when the gate document is approved. Alternative (Argon artifact options B/C): token-only or
memory-only. That alternative costs global IP search and path explainability.

## 5. Backup — UCS and `ns.conf`, with a sensitive-data plan

Both follow `VENDOR_BACKUP_CONTRACTS_2026_09_22.md` §0.3 (connect → identity read, refuse on mismatch → produce → streamed
encrypted fetch → verify → clean up by exact name → manifest/listing/retention/deviation) and its §0.4 failure vocabulary
(`outcome_unknown` is never folded into `failed`).

**Class.** Creating a device-side archive is a write. Under `PO_DECISION_RECORD_2026_09_14K` (FROZEN) a backup creation
that removes itself is **BW-2: class 1, its own gate, its own contract, its own audit, not a recovery write**. The
precedents are `rdw_cc_backup_create` and the CP `add backup local` rows. Restore stays disabled (`UI2_0_C7`).

### 5.1 F5 UCS (PROPOSED)

| Row | Proposed request | Class | Notes |
|---|---|---|---|
| F-B1 | `POST /mgmt/tm/sys/ucs` `{"command":"save","name":"nexus-<opaque>.ucs","options":[{"passphrase":"…"}]}` (tmsh `save sys ucs <name> passphrase <p>`) | recovery-write (BW-2) | name is neXus-generated and opaque; passphrase from the device's `export_passphrase` second secret (Radware precedent), never in a URL or log; **body shape and option names UNKNOWN** (docs F-9) |
| F-B2 | `GET /mgmt/shared/file-transfer/ucs-downloads/<name>` (chunked `Content-Range`) | read | **path and chunking semantics UNKNOWN** until read; streamed into `ArtefactStore` with a byte cap |
| F-B3 | `DELETE /mgmt/tm/sys/ucs/<name>` (tmsh `delete sys ucs <name>`) | recovery-write (BW-2) | deletes only the exact name it created; post-check with F23 |

Verification: the device-side digest command is **UNKNOWN** for REST. Without one, verify size and gzip-tar format
(`VENDOR_BACKUP_CONTRACTS` §0.3 step 5). A tmsh `sha256sum` would need SSH or `util/bash`, both excluded.

What a UCS holds (docs F-9, F-10, F-11): the configuration, SSL certificates **and private keys**, user accounts, and
passwords/passphrases encrypted with the source system's master key. It is a gzipped tar. A `no-private-key` variant
exists per community material; its exact form is **UNKNOWN**. Excluding keys lowers sensitivity and weakens recovery value.
**PO decision Q-2.**

### 5.2 Citrix ADC backup (PROPOSED)

| Row | Proposed request | Class | Notes |
|---|---|---|---|
| N-B1 | `POST /nitro/v1/config/systembackup?action=create` `{"systembackup":{"filename":"nexus-<opaque>","level":"basic\|full"}}` | recovery-write (BW-2) | `level` semantics (basic vs full; whether full includes keys/certs) **UNKNOWN** (docs C-8) |
| N-B2 | download of `/var/ns_sys_backup/<name>.tgz` via `GET /nitro/v1/config/systemfile?args=filename:<name>.tgz,filelocation:%2Fvar%2Fns_sys_backup` (base64 body) | read | **path, size limit and encoding UNKNOWN**. The base64 body is decoded in a stream and capped. |
| N-B3 | `DELETE /nitro/v1/config/systembackup/<name>.tgz` | recovery-write (BW-2) | exact-name delete; post-check with N19 |

Alternative without a device-side write: N18 (`nsrunningconfig`) text, or a `systemfile` read of the saved
`/nsconfig/ns.conf`. Both are pure reads (class 0) but capture configuration only: no certificates, keys or other
files. Recommendation: L1 ships the **class-0 `ns.conf` read** first (smaller blast radius). `systembackup` follows
only if the PO wants a full appliance archive. **PO decision Q-3.** Note that the saved `ns.conf` and the running
configuration can differ (unsaved changes, N16). Each artefact records which one it is.

### 5.3 Sensitive-data handling plan

| Risk | Handling |
|---|---|
| Private keys in UCS / full systembackup | Stored only as an envelope-encrypted artefact (`ArtefactStoreCipher`, per-artefact AES-256-GCM key, wrapped master key). Never unpacked into the database. Download needs the existing RBAC (`PO_DECISION_RECORD_2026_09_22_BACKUP_HTTP_DOWNLOAD_WITH_RBAC.md`). UCS additionally carries the device-side passphrase. |
| Reversibly encrypted secrets in `ns.conf` (`-encrypted -encryptmethod ENCMTHD_n [-kek]`, docs C-9). Third-party tools show they can be decrypted with the appliance key material (docs C-10, non-official). | Treated as **CLASS 3-adjacent**: the artefact is encrypted at rest. The configuration-plane text withholds every line carrying `-encrypted`, `-password`, `-passphrase`, `-secret`, `-key` arguments, SNMP communities and LDAP/RADIUS bind secrets as `<keyword> [withheld]` (Fortinet/ASA precedent). The withheld count is shown. No AI/support export of the raw text. |
| Master-key-encrypted passwords in BIG-IP config (`$M$…`) and iRule bodies that may embed credentials | Same withholding rule. iRule bodies are not collected in L1/L2 (F21 names only); a later L2b read of bodies needs its own gate and a secret-scan step before persistence. |
| Content listing (`backup_artefact_entry`, V41) exposes file names: partition names, certificate/key file names (often the service's DNS name) | Listing stored as today. The aiview projection masks names through the pseudonymizer and shows key files as `KEY-<token>`. **New masking rules required (§8.3).** |
| Certificates' subject/SAN (customer DNS names) | Not parsed in L1. If certificate expiry is wanted for compliance (§7.3), only `not_after`, key size and algorithm are kept, never the subject or SAN. |
| Raw REST JSON responses | Parsed in memory into the §4 fields, then discarded (raw-evidence law). Debug logging records field names and counts only (Radware `ccDevices` precedent). |
| Session tokens | Held in memory only, never logged. Explicit logout/delete at the end of every run (Radware `CcSession` precedent). |

## 6. Load balancers as path-analysis hops (L3)

### 6.1 Topology attachment

- Each LB device (or HA unit, §4.1) becomes a node of the new kind `lb_unit`, one routing context per route domain /
  traffic domain. It attaches to segments through `lb_self` / `lb_floating` addresses, the same subnet inference as for
  firewall interfaces. `lb_mgmt` addresses never attach.
- VIPs (`lb_vip`) are `/32` attachments on the LB node (Fable §1.4). A VIP inside a self-IP subnet joins that segment.
  A VIP outside every connected subnet is reachable only through routes on upstream devices pointing at the LB.
  If no such route exists, the hop reports `VIP_REACHABILITY_UNPROVEN` (caveat, not a denial).
- F5 forwarding (IP) virtual servers and wildcard virtual servers make the LB a router for matching traffic. Phase L3
  treats a match on one as `LB_FORWARDING_VS` → the LB routes with its own route table (CONFIG_DERIVED → `ROUTE_ACTIVE_UNKNOWN`).
- The path engine's vendor filter (`TopologyBuilder.build()` lines 128-129, `PathEngine` 348-355) is widened to
  `f5_bigip` and `citrix_adc` only behind the L3 switch. Before L3, LB devices stay `UNSUPPORTED_NODE_VENDOR` as today.

### 6.2 VIP → pool translation as a NAT-like hop

When the packet reaches an `lb_unit` and its destination equals a VIP in that context:

1. **Virtual server match.** Candidate VS = destination address/mask, port (or any), protocol, source match, VLAN scope
   (ingress VLAN must be enabled), enabled state. Precedence when several match (BIG-IP's most-specific virtual
   server selection; Citrix's equivalent) is **UNKNOWN until A0 reads it**. Several candidates → hop `UNKNOWN
   (LB_VS_MATCH_AMBIGUOUS)`, all candidates listed. A disabled or REJECT VS → `DENIED` only when the vendor semantics of
   that state are frozen; until then `UNKNOWN (LB_VS_STATE_SEMANTICS_UNKNOWN)`.
2. **Pool selection.** Default pool (F5) / bound service group or services (Citrix). The selection is **decided** only if
   none of the following is attached: iRule, LTM policy with a forward action, content-switching policy,
   responder/rewrite redirect, persistence that can override the pool, a backup vserver / fallback that can take
   traffic. Any of these makes the hop `UNKNOWN (LB_POOL_SELECTION_DYNAMIC)` with the deciding object names listed.
   Members of the default pool are still shown as `CONDITIONAL` branches.
3. **Member fan-out.** One branch per member, bounded by the existing `maxBranches` (8 per hop, 16 total,
   `ecmpTruncated`). Destination becomes `member.address:member.port` if `translate_address/port` is on (F5).
   Otherwise the destination stays the VIP (`LB_NO_DESTINATION_TRANSLATION`). Member state:
   - available at collection → normal branch;
   - unavailable/disabled at collection → branch kept and marked `MEMBER_UNAVAILABLE_AT_COLLECTION`, verdict `UNKNOWN`
     (runtime state can change; not a denial, see C-3);
   - state not collected → `UNKNOWN (MEMBER_STATE_UNKNOWN)`.
   Persistence does not change *which* members are possible, only which one a given client gets. So with persistence and
   no dynamic selection the fan-out stays valid and the hop gets the caveat `LB_PERSISTENCE_PRESENT`.
4. **Source translation.** `NONE` → source unchanged. `AUTOMAP` → source = an egress self IP / SNIP of the egress
   VLAN. If more than one qualifies, the source is `UNKNOWN (LB_SNAT_ADDRESS_AMBIGUOUS)`, which is a set rather than a
   guessed value. `POOL` → source ∈ SNAT pool addresses (set). `VENDOR_DEFAULT` / `UNKNOWN` → source `UNKNOWN` and the
   hop note `LB_SOURCE_TRANSLATION_UNKNOWN`.
5. **Second route lookup** in the LB context on the post-translation destination (member) to find the egress segment.

Output reuses the existing `NatApplied` shape with a new status value `LB_TRANSLATED`:
`NatApplied(status=LB_TRANSLATED|NO_TRANSLATION|UNKNOWN, reason, applied={vs, pool, member, snat}, rules=[vs id],
undecidableRules=[iRule/policy refs])`. `packetOut` per branch is the translated tuple.

### 6.3 What makes an LB hop UNKNOWN (closed list, proposed reason codes)

| Reason | Trigger |
|---|---|
| `LB_POOL_SELECTION_DYNAMIC` | iRule, LTM policy forward action, CS policy, responder/rewrite redirect, persistence override, backup/fallback vserver |
| `LB_VS_MATCH_AMBIGUOUS` | more than one VS matches and precedence semantics are not frozen |
| `LB_VS_STATE_SEMANTICS_UNKNOWN` | disabled / reject / down VS whose packet behavior is not frozen |
| `LB_SOURCE_TRANSLATION_UNKNOWN` | SNAT mode default or unmapped; Citrix USIP/USNIP not collected |
| `LB_SNAT_ADDRESS_AMBIGUOUS` | automap with several candidate self IPs |
| `MEMBER_STATE_UNKNOWN` / `MEMBER_UNAVAILABLE_AT_COLLECTION` | §6.2 step 3 |
| `LB_CONTEXT_UNPROVEN` | route domain / traffic domain of the ingress cannot be determined |
| `LB_PARTITION_NOT_COLLECTED` | VIP falls in a partition that was not enumerated |
| `LB_MODULE_NOT_MODELLED` | traffic hits an object owned by an out-of-scope module (APM/ASM/AFM/GSLB) |

The verdict lattice is unchanged: an UNKNOWN LB hop taints its branch, tracing continues, and downstream hops are `CONDITIONAL`.

### 6.4 Access verdict at the LB

An LB is not modelled as a security policy enforcer in L3. A matched, decided VS gives hop `ownVerdict = ALLOWED` with the
caveat `LB_NOT_A_POLICY_ENFORCER`. A destination on the LB that matches no VS gives `UNKNOWN (LB_NO_VS_MATCH)`, not
`DENIED`, because whether the device drops or forwards it depends on vendor default behavior not yet read. F5 AFM and
Citrix ACLs are out of scope. F5 AFM presence is detected through F4 and listed as a caveat; no Citrix ACL presence read is proposed yet (gap).

### 6.5 Return path

The LB is a stateful unit. With SNAT (`AUTOMAP`/`POOL`), the reply goes to the SNAT address, which routes back to the LB.
Without SNAT, the reply must route back through the LB (member default gateway). If the computed return walk does not
cross the LB, the result is `ASYMMETRIC` → overall `UNKNOWN` with `OUT_OF_STATE_DROP_LIKELY`, reusing today's rule.
Reverse translation at the LB undoes the forward VIP/SNAT mapping (`ReturnHop.reverseTranslated`).

### 6.6 Data the hop needs vs. what L1/L2 collect

| Needed | Source | Phase |
|---|---|---|
| VIP, port, protocol, VLAN scope, enabled | F14/F15, N9/N13 | L1 |
| Pool/members/state | F16–F18, N11/N12, N10 | L1 |
| SNAT mode and pools | F14/F19, N14/N15 | L2 |
| iRule/policy/persistence presence | F14 refs, F20–F22, N13 | L2 |
| Routes per context | F12, N7 (CONFIG_DERIVED; runtime FIB read is a later gate) | L1 |
| Self IPs / SNIPs per VLAN | F10, N5/N6 | L1 |

### 6.7 Validation (A6 analog)

Device-side validation of an LB hop (a matcher that asks the device which VS/member a tuple hits) is **not proposed**.
Neither vendor's read-only "explain this flow" command has been read in documentation. If one exists, it needs its
own gate under D-7 (approved in principle only; each command separately).

## 7. Configuration plane and compliance hooks (L2)

### 7.1 Configuration plane

- Read: F14–F22 JSON (F5) normalized into `ConfigParseResult` sections (`virtual`, `pool`, `snat`, `persistence`,
  `profiles`, `sys`, `auth`, `ntp`, `syslog`, `snmp`, `httpd`, `sshd`). Citrix: N18 running config text, parsed into the
  same section/setting shape (`AsaConfigProcessor` style), with secret lines withheld (§5.3).
- `ConfigurationVendor` gains `F5_BIGIP`, `CITRIX_ADC`. The vendor CHECKs on `device_configuration_run` /
  `configuration_artefact` (V86) and `backup_artefact` (V64) are widened by a new migration (≥ V190).
- Deviation: the existing index deviation (`ADDED / REMOVED / RECOUNTED / modified`) works per section/setting unchanged.
- F5 `sync-status` (F8) and Citrix "unsaved config" (N16) are **alignment signals**. They go to the Alignment plane,
  never into the configured-state record.

### 7.2 Compliance hooks

New catalog `LoadBalancerComplianceCatalog` with `VendorBinding`s for `f5_bigip` / `citrix_adc`; `Ui2ComplianceServer`
dispatch gains both vendors. Each control's `EvidenceRequirement.gateEntryId` names the proposed gate row, so a missing
read surfaces as `UNKNOWN + EVIDENCE_MISSING (requiredGateEntry=…)`. Candidate controls (all need source-backed
guidance before inclusion; none is asserted here as vendor best practice):

| Candidate control | Evidence | Notes |
|---|---|---|
| Management access restricted (httpd/sshd allow lists; Citrix NSIP management access) | F5 `sys httpd`, `sys sshd`; N18 | config read must be added to F-rows (not yet listed): **gap** |
| Remote auth configured, local admin limited | F5 `auth`; N18 `systemuser` / auth policies | withheld secrets → `UNKNOWN` if the assertion needs the value |
| NTP / syslog / SNMPv3 configured | F5 `sys ntp`, `sys syslog`, `sys snmp`; N18 | community strings withheld |
| HA configured and in sync | F5–F8; N4 | Inventory/Alignment evidence, not configuration. Bind as a separate operational check. |
| Weak TLS on client-side profiles (protocols/ciphers) | F5 `ltm profile client-ssl`; Citrix `sslvserver`/`sslprofile` | needs extra reads (L2b gate) |
| Certificate expiry | F5 `sys file ssl-cert` (`not_after` only); Citrix `sslcertkey` | subject/SAN never stored (§5.3) |
| Virtual servers without a monitor on their pool | F16, N12 | runtime-derived; an LB-object hygiene check, not a peer comparison |
| Unused / orphan pools and VIPs | F14/F16, N9/N12 | hygiene; informational |

Management servers are never evaluated (existing rule). `PROVEN_UNSUPPORTED` is used only with documentation evidence.

## 8. Platform integration changes (summary)

### 8.1 Enrolment and schema

- Vendor keys `f5_bigip`, `citrix_adc` (lower-case, matching existing keys) in `VENDOR_MAPPINGS` → `https`; role
  `appliance`, added to `APPLIANCE_VENDORS`. Whether an LB should instead get a new role `load_balancer` (widening
  `chk_devices_role`) is **PO decision Q-5**. Recommendation: `appliance`, avoiding a role migration across all role-aware code.
- Widen: `backup_artefact` (V64), configuration (V86), `lifecycle_catalog` (V144, `F5`, `CITRIX`),
  `device_inventory_ha.source` (V20), `device_interface_address.role` (V13), `path_validation_hop` (V188) only when L3
  needs it.
- Frontend: `AddDeviceDialog.tsx` `VENDOR_LABEL` / `HTTPS_VENDORS`.
- Port: HTTPS 443 default (`httpsPortOf`); F5 single-NIC / non-443 management ports are configurable per endpoint.

### 8.2 Capability ids and gates (proposed names)

`device_confirm_https` (reused; confirm = F1–F3 / N1–N3), `f5_inventory_collect`, `adc_inventory_collect`,
`f5_ucs_backup`, `adc_config_backup` (class-0 ns.conf) and optionally `adc_system_backup`, `f5_configuration_collect`,
`adc_configuration_collect`. Gate ids `f5_<area>_<object>` / `adc_<area>_<object>`; `platform_role_scope` `f5_bigip` /
`citrix_adc`; `transport_kind 'HTTPS'`; `canonical_command_key 'GET /mgmt/tm/ltm/virtual'` form (Radware precedent).
Rows start as `DRAFTED` and become `SIGNED_OFF` only after the PO hardware run (PAN 2026-09-14 precedent:
`SIGNED_OFF_PENDING_HARDWARE_CONFIRMATION` until then).

### 8.3 Masking (aiview) — must land with L1, not after

The masking advice works by key name, so every new key needs a rule **before** the API exposes it:

| New key | Rule |
|---|---|
| `virtual_server`, `pool`, `snat_pool`, `traffic_group`, `partition`, `service_group`, `irule`, `ltm_policy`, `cs_policy`, `persistence_profile` | new pseudonym kinds (`LBVS-`, `POOL-`, …) in `TopologyNamePseudonymizer`, uniqueness through `pseudonym_registry` |
| `node_name`, `server_name` (often a hostname or an embedded address, e.g. `/Common/192.0.2.10`) | pseudonym **and** address masking of any embedded address |
| `vip_address`, `member_address`, `snat_address`, `self_ip` | `SubnetPreservingIpMasker` (route through existing `address` key semantics) |
| certificate/key file names in backup listings | `KEY-` / `CERT-` tokens |

Fail-closed default for L1: until those rules ship, the aiview projection **withholds** the LB object lists entirely
(counts only). Infoblox precedent (`InventoryController` line 92) shows the alternative of routing a vendor field onto an existing key.

## 9. Phasing

Each phase follows `SCOPE → AUDIT → CONTRACT → IMPLEMENT → TARGETED_TEST → REGRESSION → HUMAN_REAL_ENV → STATE_UPDATE →
HANDOVER`. No phase is `DONE` without real-environment evidence from both vendors.

| Phase | Content | Contract and gate prerequisites | Exit evidence |
|---|---|---|---|
| **L0** (docs) | Record D-9 (C-1); A0-style vendor-semantics pass for both vendors from official docs; PO answers Q-1…Q-8 | none (document only) | `LB_VENDOR_SEMANTICS.md` FROZEN; D-9 record |
| **L1 — inventory + backup** | Enrolment, confirm, inventory (§4.1–4.3 except SNAT/persistence), F5 UCS (BW-2) or Citrix `ns.conf` read, masking rules (§8.3) | Gate documents `F5_BIGIP_API_ROUTE_GATE_ENTRIES.md`, `CITRIX_ADC_NITRO_GATE_ENTRIES.md` (10-item gate format, as `PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md`); a PO hardware run per request; gate migration ≥ V190; vendor-check migration; `VENDOR_BACKUP_CONTRACTS` addendum for UCS/systembackup | scripted-transport tests (happy, refusal, truncated, cleanup failure), parser tests on masked measured output, fixture-vs-SQL gate test, first live run per vendor, render harness + privacy gate green |
| **L2 — configuration** | Configuration plane (§7.1), SNAT/persistence/iRule/policy references, compliance catalog (§7.2) | additional gate rows (F19–F22, N13–N16, N18; management/TLS reads for compliance); config processor secret-withholding tests | withheld-line tests against measured masked samples; compliance UNKNOWN paths covered |
| **L3 — path hop** | `lb_unit` node kind, `HopSemantics` LB strategy, §6 rules, UI branch tabs, return path | amendment to `PATH_ANALYSIS_DESIGN.md` (or a Phase-B contract) freezing §6; L2 data present | engine unit tests per reason code; one end-to-end masked path through each vendor in the real environment, compared against operator knowledge (no device-side matcher) |

L1 for F5 and Citrix can proceed in parallel; they share the HTTPS lane and masking work. Recommended order inside L1:
inventory before backup, so identity refusal (§0.3 step 2) has a recorded identity to compare against.

## 10. Risks and open questions

### 10.1 Risks

| # | Risk | Mitigation |
|---|---|---|
| R-1 | Vendor semantics guessed from general knowledge (VS precedence, SNAT defaults, member states) | L0 A0-style pass; UNKNOWN reason codes (§6.3) for everything not frozen |
| R-2 | LB object names leak to aiview through the key-based masking default | §8.3 rules ship with L1; fail-closed withholding until then |
| R-3 | UCS / full systembackup contain private keys | envelope encryption, passphrase, RBAC download, no unpacking; Q-2 on `no-private-key` |
| R-4 | `ns.conf` reversibly encrypted secrets | artefact-only storage, line withholding, no AI/support export |
| R-5 | iControl REST exposes command execution (`/mgmt/tm/util/bash`); an API account may be able to write | closed request set; never-issued list (§3.4); a read-only role (BIG-IP Guest/Auditor; Citrix read-only command policy). Exact role capabilities are **UNKNOWN**, so verifying them is a PO pre-L1 task. Note that UCS creation may not be permitted to a read-only role, which forces a role decision. |
| R-6 | Session exhaustion / control-plane load on the LB | one session per run, serialized logins, response caps, per-pool stats cap; no polling except backup |
| R-7 | Per-traffic-group HA misreported as a device role | `MIXED` role plus per-group facts; pair existence ≠ pair health |
| R-8 | Large estates (thousands of VS/pools) exceed caps | measured in L1; `MEMBER_STATE_PARTIAL`, never silent truncation |
| R-9 | Path explosion from large pools | existing branch caps; `ecmpTruncated`; members summarized beyond the cap |
| R-10 | Admin partitions / traffic domains not enumerated → missing VIPs read as "no VS" | `LB_PARTITION_NOT_COLLECTED`; absence ≠ evidence of absence |
| R-11 | TLS: appliances present self-signed management certificates | V112 TOFU pin with WARN/STRICT per device, existing behavior; PAN-only TLS exception (2026-09-21) does **not** extend to LBs |

### 10.2 Open questions for the Product Owner

- **Q-1** Persist pool-member/VIP/SNAT addresses as values (V13 treatment) or as tokens only? (§4.4)
- **Q-2** UCS with or without private keys? Passphrase source = per-device `export_passphrase` second secret? (§5.1)
- **Q-3** Citrix: class-0 `ns.conf` read only, or also the class-1 `systembackup` full archive? (§5.2)
- **Q-4** HA pair identity input: F5 chassis ids vs. device-trust names; Citrix node addresses vs. host ids. (§4.1)
- **Q-5** Role `appliance` vs. a new `load_balancer` role. (§8.1)
- **Q-6** Is BIG-IQ or Citrix ADM present, and should either later act as a management plane (Panorama analog), or stay out?
- **Q-7** Which accounts and roles exist on the estate (read-only API user; remote-auth login provider name for iControl
  REST)? Can that role create/delete a UCS or systembackup?
- **Q-8** Are admin partitions / route domains / traffic domains in use, and are NetScaler clusters (CLIP) or SDX
  instances in the estate? (They change L1 scope.)
- **Q-9** Should iRule bodies ever be collected (for path explainability), given credential-embedding risk? (§5.3)

## 11. Documentation references

Search on 2026-11-05 located the pages below. **Their bodies were not read from the drafting environment** (C-4), so
each claim drawn from them is a search-result statement to confirm in L0. Community and third-party pages are labelled;
they are not official documentation and cannot freeze a semantic.

F5 BIG-IP:
- F-1 iControl REST API reference (root): https://clouddocs.f5.com/api/icontrol-rest/ — official; not reachable from the drafting environment.
- F-2 iControl REST token management (F5 community article): https://community.f5.com/kb/technicalarticles/icontrol-rest-authentication-token-management/287462 — community.
- F-3 Token-based authentication (F5 community, "Demystifying iControl REST Part 6"): https://community.f5.com/kb/technicalarticles/demystifying-icontrol-rest-part-6-token-based-authentication/286793 — community.
- F-4 APIRef `tm cm sync-status`: https://clouddocs.f5.com/api/icontrol-rest/APIRef_tm_cm_sync-status.html — official.
- F-5 tmsh reference `cm sync-status` (v16): https://clouddocs.f5.com/cli/tmsh-reference/v16/modules/cm/cm_sync-status.html — official.
- F-6 REST equivalents of `show cm traffic-group` / `failover-status` (F5 community answer): https://community.f5.com/discussions/technicalforum/rest-api-equivalent-for-tmsh-show-cm-device-group-sync-failover/272457 — community.
- F-7 Bug ID1088981 (partition-qualified pool member stats path): https://cdn.f5.com/product/bugtracker/ID1088981.html — official bug tracker.
- F-8 Pool member status across partitions (DevCentral thread): https://community.f5.com/t/icontrol-rest-getting-pool-member-status/41694 — community.
- F-9 Working with UCS archives (BIG-IP 16.0, Secure Vault administration): https://techdocs.f5.com/en-us/bigip-16-0-0/big-ip-secure-vault-administration/working-with-ucs-archives.html — official.
- F-10 Best practices for UCS restore operations (BIG-IP 15.1): https://techdocs.f5.com/en-us/bigip-15-1-0/big-ip-secure-vault-administration/working-with-ucs-archives/best-practices-ucs-installation.html — official.
- F-11 Inspecting a UCS file (gzipped tar; extends K4423): https://community.f5.com/kb/technicalarticles/inspecting-a-ucs-file-from-a-compromised-big-ip/330994 — community. K4423 (UCS overview) and K13132 (backup/restore) are referenced there; their URLs were not located.

Citrix ADC (NetScaler):
- C-1 NITRO `lbvserver` (13.1): https://developer-docs.netscaler.com/en-us/adc-nitro-api/13-1/configuration/lb/lbvserver — official; not reachable from the drafting environment.
- C-2 Basic load balancing use case (NITRO 13; auth headers/cookie): https://developer-docs.netscaler.com/en-us/adc-nitro-api/13/usecases/basic-load-balancing-usecase — official.
- C-3 `lbvserver_servicegroup_binding` (get all needs `bulkbindings`): https://citrix-landing-page.readthedocs-hosted.com/projects/citrix-adc-nitro-api-reference/en/latest/configuration/lb/lbvserver_servicegroup_binding/ — readthedocs-hosted reference; provenance to confirm in L0.
- C-4 `lbvserver_service_binding`: https://citrix-landing-page.readthedocs-hosted.com/projects/citrix-adc-nitro-api-reference/en/latest/configuration/lb/lbvserver_service_binding/ — same.
- C-5 `lbvserver_servicegroupmember_binding` (12.1): https://developer-docs.netscaler.com/en-us/adc-nitro-api/12-1/configuration/load-balancing/lbvserver_servicegroupmember_binding/lbvserver_servicegroupmember_binding — official.
- C-6 `hanode` statistics (13; `hacurmasterstate`): https://developer-docs.netscaler.com/en-us/adc-nitro-api/13/statistics/ha/hanode — official.
- C-7 `hanode` configuration (13.1): https://developer-docs.netscaler.com/en-us/adc-nitro-api/13-1/configuration/ha/hanode — official.
- C-8 `systembackup`: https://citrix-landing-page.readthedocs-hosted.com/projects/citrix-adc-nitro-api-reference/en/latest/configuration/system/systembackup/ — readthedocs-hosted; provenance to confirm.
- C-9 System user password encryption method / hash type (CTX552350; `encrypted`, `encryptmethod`, `kek` keywords): https://support.citrix.com/external/article/CTX552350/system-user-password-encryption-method-o.html — official support article.
- C-10 ns.conf secret decryption (third-party, shows that config secrets are reversibly encrypted): https://www.rapid7.com/db/modules/auxiliary/admin/citrix/citrix_netscaler_config_decrypt/ — third-party; used only as a risk signal, never as a semantic source.

Not located (must be found and read in L0): BIG-IP virtual server precedence; SNAT default behavior; pool member
state vocabulary; UCS REST create/download/delete bodies; read-only role capabilities. Citrix: USIP/USNIP semantics,
`nsconfig` saved/changed fields, partition-scoped NITRO sessions, `systemfile` download limits, `systembackup` level
contents, read-only command policy capabilities.

## 12. Repository sources consulted

`AGENTS.md`; `ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml` (Radware rows
~1547–1706, ASA from ~1707, FortiGate from ~1915); `GateRow.java`, `GateResolver.java`, `TransportKind.java`; migrations
V4, V12, V13, V20, V24, V46, V64, V65, V67–V69, V78–V81, V86, V96, V112, V144, V171, V184–V188 (latest V189);
`DeviceAddSingleService.java`, `DeviceRegistrationService.java`; `HttpsDeviceClient.java`, `CertificatePinningTrustManager.java`,
`HttpsVendorPlan.java`, `HttpsVendorExecutor.java`, `CyberControllers.java`; `InventoryCapabilityIds`, `BackupCapabilityIds`,
`ConfirmCapabilityIds`, `DiscoveryCapabilityIds`; `ArtefactStoreCipher.java`, `RetentionPruningService.java`;
`ConfigParseResult.java`, `FortiGateConfigProcessor.java`, `AsaConfigProcessor.java`; `ComplianceControl` / `Ui2ComplianceServer.java`;
`PathEngine.java`, `TopologyBuilder.java`, `PathResult.java`, `HopNatEvaluator.java`, `PathReasons.java`;
`PrivacyMaskingResponseBodyAdvice.java`, `TopologyNamePseudonymizer.java`. Design documents: `PATH_ANALYSIS_DESIGN.md` (FROZEN),
`PATH_ANALYSIS_VENDOR_SEMANTICS.md` (FROZEN), `PO_DECISION_RECORD_2026_11_03_PATH_ANALYSIS_PHASE_A.md` (RATIFIED),
`PATH_ANALYSIS_DESIGN_FABLE.md` (DRAFT), `PATH_ANALYSIS_DESIGN_ARGON_ARTIFACT.md` (no status), `VENDOR_BACKUP_CONTRACTS_2026_09_22.md`
(FROZEN), `PO_DECISION_RECORD_2026_09_14K_A_BACKUP_IS_NOT_A_RECOVERY_WRITE.md` (FROZEN), `PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md`
(APPROVED), `FORTINET_CONTRACT.md` (FROZEN), `CISCO_ASA_CONTRACT.md` (FROZEN), `PRIVACY_AND_DATA_HANDLING.md`.
