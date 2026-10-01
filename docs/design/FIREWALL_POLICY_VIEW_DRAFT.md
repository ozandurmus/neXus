# Firewall Policy View — Collection and Presentation

**Status: DRAFT — for Product Owner review; not implementation or command authority.**
Date: 2026-10-01. Source baseline: `91e7275`, lane `sa/policy-view-design`.
Scope: design only; no code, migration, gate row, device contact, or deployment.

## 1. Goal, non-goals, and existing evidence

Recommend a **Policy & Objects** view that answers: what rules were collected, where
they apply, what their objects mean, what changed, and which findings merit review.
Start with Check Point management policy and Palo Alto effective running policy.
Reuse UI2 collection orchestration, parsing, storage boundaries, RBAC and Material 3.
Do not introduce another collector service, credential store, search cluster or graph DB.

This feature is read-only toward devices: never push/install policy, edit rules or
objects, issue SET/commit/publish, reset counters, or generate/apply remediation.
No backup creation/export is triggered to obtain rules. No traffic replay, packet
injection, live policy-match command, routing simulator or change-ticket workflow.
Authentication/session teardown is transport lifecycle, not policy modification.

Evidence vocabulary: **SOURCE-VERIFIED** means present in this checkout; **RECORDED
MEASUREMENT** means an earlier repository observation, not a new measurement;
**PROPOSED** is a design choice; **UNKNOWN** means not established. All numbers for
new limits, cadence, performance and effort below are proposals/estimates. Actual
fleet coverage, versions, sizes, permissions and throughput are UNKNOWN in this task.

### 1.1 What neXus already has

| Vendor / plane | SOURCE-VERIFIED collection and projection | Rules/objects available; missing work |
|---|---|---|
| Check Point Gaia gateways / VSX | `ConfigurationReadPlan` reads host-level `clish -c 'show configuration'`; sanitized sections plus encrypted artefact. | Gaia settings are not the management access/NAT rulebase. No security rules can be inferred from section counts. VSX repetition does not obtain per-VS policy. |
| Check Point management / MDS | `Ui2WorkerMain` wires `MgmtCliEnumerationAdapter`: management API through SSH `mgmt_cli`, domains and gateways/servers. `ManagementTreeService` reads stored discovery candidates. | Management topology objects exist; access layers, access/NAT rules, general address/service objects and package bindings are not collected by this adapter. Current discovery uses fixed `limit 500`; do not reuse it as a completeness guarantee. |
| Check Point MDS export | `MdsExportScheduler`, `MdsExportExecutor` and `MdsExportPlan` run whole-server/domain backup and retain an artefact. | The archive may contain management databases; decoded policy coverage is UNKNOWN. No normalized rule reader exists in this path. Export takes a database lock and includes controlled writes: never repurpose it as policy collection. |
| Check Point Spark | Separate `QuantumSparkBackupPlan` exists; Gaia behavior is not proof of Spark policy behavior. | Centrally managed versus locally managed mode and rule/object coverage are UNKNOWN. A backup capability does not establish a policy reader. |
| Palo Alto firewall | Existing XML API reads ACTIVE, EFFECTIVE_RUNNING and MERGED. Effective running is streamed into an encrypted artefact and a category/source index. | RECORDED MEASUREMENT: effective running contained per-vsys address/group, service/group, application, schedule and rulebase categories. `PaloAltoConfigStreamProcessor` counts categories; it does not normalize rules or resolve members. Exact security/NAT ordering and completeness remain UNKNOWN. |
| Panorama | Discovery/management tree supports managed-device topology. `PanoramaAssignmentReducer` and a configuration gate record exist. | Assignment/provenance is not a pre/post rulebase. Current worker wiring uses `PanoramaCrossCheckPort.NONE`; no working rule provenance cross-check may be claimed. Actual estate Panorama management and policy API coverage are UNKNOWN. |
| Fortinet / FortiGate | `ConfigurationCapabilityExecutor` directly reads top-level `show` over SSH, with selected full system-section supplements; artefact plus curated projection. | Full input can contain firewall policy/objects within its authorized VDOM scope; actual coverage is UNKNOWN. `FortiGateConfigurationAllowlist` removes these from Configuration. FortiManager topology is not effective firewall policy. Parse before that allowlist in a future separate policy projection. |
| Cisco ASA | `CiscoAsaExecutor.configuration` reads `more system:running-config` over SSH; refuses multiple-context mode. | Full running text can contain ACLs, objects, object-groups, bindings and NAT. `AsaConfigProcessor` intentionally excludes policy families. No normalized ACL reader or hit observations. Multi-context remains UNSUPPORTED in this path. |
| Radware Cyber Controller (CC) | Controller backup CLI and managed-device inventory/config-export routes exist in `CyberControllerBackupPlan` / `HttpsVendorPlan`. | Backups are opaque and may contain highly sensitive material. No verified security-rule/object parser in the inspected configuration path. CC policy versus managed product protection policy is UNKNOWN; do not label CC an ordinary firewall. |

This is a source inventory, not certification of deployed collection coverage. Existing
processor tests explicitly keep policy blocks out of Fortinet/ASA Configuration;
PAN streaming tests establish bounded indexing, not security-rule interpretation.
Key source anchors: [query service](../../ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/device/configuration/ConfigurationQueryService.java),
[collection executor](../../ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/ConfigurationCapabilityExecutor.java),
[management tree](../../ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/management/ManagementTreeService.java),
[MDS export](../../ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/backup/cp/MdsExportExecutor.java),
[RBAC](../../ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/ActionRegistry.java),
[masking](../../ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/privacy/PrivacyMaskingResponseBodyAdvice.java),
[gate model](../../ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/gates/GateRowData.java)
and [compliance UI](../../ui2/frontend/src/screens/ComplianceScreen.tsx).

### 1.2 Ground-truth references and authority limits

- [14C collection design](PO_DECISION_RECORD_2026_09_14C_INVENTORY_AND_CONFIGURATION_COLLECTION_DESIGN.md)
  and [14G measured forms](PO_DECISION_RECORD_2026_09_14G_CONFIGURATION_COLLECTION_MEASURED_FORMS.md)
  are FROZEN; 14G supersedes 14C's per-VS Gaia configuration repetition.
- [CP configuration gate](CP_CONFIGURATION_COMMAND_GATE_ENTRIES.md),
  [PAN configuration gate](PAN_CONFIGURATION_API_ROUTE_GATE_ENTRIES.md) and
  [policy-install-time gate](POLICY_INSTALL_TIME_COMMAND_GATE_ENTRIES.md) concern
  their existing purposes. Install time/name is not rule content or installation proof
  for a newly read management revision. The last record retains older conditional
  approval wording despite its APPROVED status; no expanded authority is inferred.
- [CP findings](CP_CONFIGURATION_MEASUREMENT_FINDINGS_2026_09_14.md) and
  [PAN findings](PAN_CONFIGURATION_MEASUREMENT_FINDINGS_2026_09_14.md) are DRAFT
  observations, not new command authority. PAN recorded 11.8 MB effective running,
  220 KB merged and 84.8 MB Panorama configuration; these are samples, not maxima.
- [Configuration microservice architecture](UI2_CONFIGURATION_MICROSERVICE_ARCHITECTURE.md)
  is a CONSENSUS DRAFT; reuse its implemented parser boundary, not its status as authority.
- [Design language](UI2_INVENTORY_AND_CONFIGURATION_DESIGN_LANGUAGE.md) is FROZEN:
  operational-unit rows, member evidence, freshness, coverage and provenance stay.
  D-UI2 excludes VS nodes from Configuration and reserves them for Policy & Objects.
  Its claim that PAN VSYS carry no configuration conflicts with 14G's per-vsys
  categories and recorded measurements. **PO clarification required**; this draft
  does not reconcile or amend those statements and leaves Configuration unchanged.
- [PAN compliance review](CLAUDE_PALO_ALTO_COMPLIANCE_REVIEW.md) is review evidence:
  allowlist extraction, source specificity, explicit unknowns and privacy constraints
  inform this design; no benchmark compliance claim follows from a risk badge.
- [Privacy contract](../../PRIVACY_AND_DATA_HANDLING.md) and
  [constitution](../../AGENTS.md) govern all evidence and AIView inspection.

## 2. Policy meaning, authoritative sources and candidate reads

**Every new read below is a CANDIDATE — needs measurement + PO sign-off.** These
are a review catalog, not executable instructions or approved gate rows. Existing
reads are identified for possible parse-scope reuse; this draft authorizes no run.
All placeholders are opaque stored references resolved server-side, never browser
command/XPath fragments. Version-specific syntax, permission scope and response
semantics must be measured. No undocumented endpoint is invented for an UNKNOWN.

### 2.1 Check Point: management policy, not Gaia configuration

Authoritative configured intent: owning management server/domain, package, ordered
access layers and inline layers; NAT is a distinct package rulebase. Link install-on
targets by opaque management UIDs and established enrollment mappings, never names.
Management publication and gateway enforcement are separate observations. Global
domain inheritance, implied/cleanup rules and target applicability must be explicit.

Recommend the existing authenticated SSH management transport invoking `mgmt_cli`
(Management API), extending its closed read vocabulary. This avoids introducing a
parallel HTTPS credential path. For review, API operation names below identify exact
logical reads; the equivalent HTTPS endpoints are `POST /web_api/<operation>` only
if a later transport decision explicitly authorizes HTTPS and its login/logout path.

| ID | Candidate Management API operation and parameters | Purpose / UNKNOWN |
|---|---|---|
| CP-1 | `show-packages`, `details-level=full, limit=100, offset=<offset>`; `show-package`, `uid=<package_uid>, details-level=full` | Package access-layer order and installation targets; returned shape/version UNKNOWN. |
| CP-2 | `show-access-layers`, `details-level=full, limit=100, offset=<offset>`; `show-access-layer`, `uid=<layer_uid>, details-level=full` | Layer properties, implicit cleanup, shared/inline references. |
| CP-3 | `show-access-rulebase`, `uid=<layer_uid>, details-level=full, use-object-dictionary=true, limit=100, offset=<offset>` | Access rules and sections; repeat for referenced inline layers, preserving each parent occurrence. |
| CP-4 | `show-objects`, `details-level=full, limit=100, offset=<offset>`; `show-object`, `uid=<object_uid>, details-level=full` | Per-domain objects and bounded missing-reference resolution; dictionary presence is not closure completeness. |
| CP-5 | `show-nat-rulebase`, `package=<package_ref>, details-level=full, use-object-dictionary=true, limit=100, offset=<offset>` | Automatic/manual NAT, original/translated fields and install-on. Accepted package reference form UNKNOWN. |
| CP-6 | CP-3 plus `show-hits=true`, `hits-settings.from-date=<start>`, `hits-settings.to-date=<end>`, `hits-settings.target=<target_ref>` | Optional usage measurement only; aggregation, time bounds, last-hit fields and per-target completeness UNKNOWN. |

Example candidate CLI shape: `mgmt_cli -r true -d <domain_uid> -f json
show-access-rulebase uid <layer_uid> details-level full use-object-dictionary true
limit 100 offset <offset>` (one command when serialized by the worker). Existing
discovery's local management authentication is not approval of these operations;
`-r true` must not be described as a least-privilege credential. P0 must establish
permission restrictions and exact shell argument binding before adopting this shape.
No SID file, raw output file, publish or configuration write is proposed.

Pagination: validate `from/to/total` or the documented version equivalent on every
page; detect repeated UIDs, stalled offsets, missing pages and concurrent changes.
Never infer completion from a short page alone. A stable revision/session view across
pages is UNKNOWN; without one, mark consistency UNVERIFIED, avoid removals/diff proof,
and do not silently loop until an apparently stable snapshot appears. Start at 100
entries/page, one outstanding read and one domain session at a time; API maximum
page size and manager-wide session/rate limits are UNKNOWN. Count nested rules
separately from sections; deduplicate objects, not rule occurrences.

The [Management API reference](https://sc1.checkpoint.com/documents/latest/APIs/)
is the vendor reference; its dynamic endpoint schemas were not fully retrievable in
this review. Exact parameter compatibility remains a P0 check, not a validated claim.

VSX policy stays management-owned, with target VS references; never fetch the same
Gaia host configuration once per VS. For centrally managed Spark use that management
policy if membership is proven. For locally managed Spark defer support: candidate
SSH/Clish `show access-rule type incoming-internal-and-vpn` needs version-specific
argument and coverage verification; all-direction enumeration, NAT/object reads,
pagination and hit history remain UNKNOWN. See the
[Spark command reference](https://sc1.checkpoint.com/documents/Appliances/Quantum_Spark_R82.00.X/CLI/EN/Content/Topics/show-access-rule-type-incoming-internal-and-vpn.htm).
Do not extrapolate that reference to the installed version or management mode.

### 2.2 Palo Alto and Panorama

Authoritative actual configuration: firewall **EFFECTIVE_RUNNING**, scoped to device
and opaque vsys context. ACTIVE is local active configuration, not a replacement for
inherited policy; MERGED is separately labeled overlay evidence. Preserve security
and NAT rulebases, address/address-group, service/service-group, application/group/
filter, schedules, zones, users, tags and profile references. Dynamic membership is
not fully determined by configuration; current membership is UNKNOWN unless observed.

| ID | Existing read or candidate, HTTPS XML API via existing `PanXmlApiTransport`, POST `/api/` | Treatment |
|---|---|---|
| PA-1 | Existing `type=op`, `cmd=<show><config><effective-running/></config></show>` | Recommended rule extraction on the existing stream; no additional device call for this parse-scope extension. |
| PA-2 | Existing `type=config&action=show&xpath=/config`; existing `type=op`, `cmd=<show><config><merged/></config></show>` | Separate context/overlay evidence; never silently substitute for PA-1. |
| PA-3 | Panorama `type=config&action=show&xpath=/config` | Existing gate documentation, but new policy-purpose execution/coverage is not established; candidate only. Stream shared/device-group pre/post security and NAT plus scoped object definitions. |
| PA-4 | `type=op`, `cmd=<show><rule-hit-count><vsys><vsys-name><entry name="<vsys_ref>"><rule-base><entry name="security"><rules><all/></rules></entry></rule-base></entry></vsys-name></vsys></rule-hit-count></show>` | Candidate XML template, not literal XML; serializer binds the opaque name. Exact grammar/version, reset timestamps, UUID joins and counter availability UNKNOWN. |

No paginated config API is assumed for PA-1/PA-3: stream and bound response bytes,
depth and entry counts. Any later narrowed XPath or `target=` variant is a distinct
review item; it is not implicitly approved by the root read. Read pre/post source
from shared and device-group hierarchy only if measured. Missing `src` must remain
UNKNOWN for rule provenance, even though the current category index defaults it
to shared. Device rule UUID/hierarchy mapping back to Panorama remains UNKNOWN.

Represent pre-rules, local rules, post-rules and defaults in their measured effective
order; preserve ancestor order rather than sorting names. Vendor ordering reference:
[Manage the Rule Hierarchy](https://docs.paloaltonetworks.com/panorama/administration/manage-firewalls/manage-device-groups/manage-the-rule-hierarchy).
Do not add Panorama's rules again if already included in effective running. A separate
manager view represents intent; device evidence is required to claim actual content.
Page/rate maxima and measured resource impact are UNKNOWN; propose serial calls,
reused authentication and no retry inside a failed run. PAN's general concurrency
guidance is not permission to raise neXus concurrency; see
[vendor API load guidance](https://knowledgebase.paloaltonetworks.com/KCSArticleDetail?id=kA1Ki000000k9diKAA).

### 2.3 Fortinet / FortiGate

Authoritative configured policy: direct firewall, per VDOM; policy order is not
numeric policy-ID order. FortiManager intent requires separate provenance and cannot
prove installed state. Prefer extraction from the already-read SSH `show` bytes
before the curated Configuration projection. Omitted defaults need version proof;
the existing full system-section supplements do not prove firewall-policy defaults.

Candidate focused SSH reads, only if the existing stream is insufficient:
`show firewall policy`, `show firewall address`, `show firewall addrgrp`,
`show firewall service custom`, `show firewall service group`,
`show firewall schedule recurring`, `show firewall schedule onetime`,
`show firewall vip`, `show firewall vipgrp`, `show firewall ippool`,
`show firewall central-snat-map` in the approved VDOM context. Scope-switch syntax
requires its own review; never introduce `set`, save or a persistent pager change.
Policy-based NGFW, IPv6 variants, local-in/proxy policies, central NAT and dynamic
objects require separate capability labels; unsupported families must be visible.

CLI paging uses the existing interactive read handling; require a verified final
prompt and complete VDOM boundaries, not a timeout accepted as EOF. No API pagination
is invented and no new REST transport is recommended. Exact size/rate limits, VDOM
visibility and hit-count/last-hit read syntax are UNKNOWN; one session/read at a time.
Vendor references: [show command catalog](https://community.fortinet.com/fortigate-3/troubleshooting-tip-essential-show-commands-in-fortigate-205737)
and [FortiOS policy semantics](https://docs.fortinet.com/document/fortigate/7.4.0/administration-guide/656084/firewall-policy).
These references do not establish this estate's version-specific support.

### 2.4 Cisco ASA

Authoritative source: running configuration in one security context, with ordered
access-list entries, objects/groups, `access-group` interface/direction/global
bindings, time ranges and NAT sections. An unbound ACL is not an enforced interface
policy; some ACLs serve VPN, classification or other uses. Keep these roles distinct.

Reuse existing SSH `more system:running-config` for parsing before the curated
projection; this is particularly secret-bearing and must remain stream/private.
Candidate narrower reads if needed: `show running-config access-list`,
`show running-config object`, `show running-config object-group`,
`show running-config access-group`, `show running-config nat`,
`show running-config time-range`; optional `show access-list` for hit counts and
`show nat detail` for NAT order/counters. Each needs measurement + PO sign-off.
Prefer current approved session paging behavior, not a new device configuration.

No API pagination; completeness needs prompt/line/byte limits. Do not count expanded
object-group ACEs as independent authored rules. Hit counts are not last-hit history;
last-hit support, reset/HA semantics, maximum response and rate are UNKNOWN.
Multi-context collection is currently refused; do not silently switch contexts or
turn an admin/system context into a firewall policy. See
[ASA 9.20 access-rule semantics](https://www.cisco.com/c/en/us/td/docs/security/asa/asa920/configuration/firewall/asa-920-firewall-config/access-rules.html);
applicability to the measured device version must be established separately.

### 2.5 Radware CC

First decide the managed product and policy type (for example, protection policy
versus packet ACL), controller intent versus device enforcement, and supported
version. Existing HTTPS inventory `GET /mgmt/system/config/itemlist/alldevices`
is an inventory candidate for scope discovery only, not a security-policy endpoint.
No exact read-only policy/objects endpoint or command has been established: UNKNOWN.
Therefore P0 for Radware starts with official schema review; it stops before any
policy call until an exact reviewed read is available. Pagination, size/rate limits,
order, counters and last-hit are all UNKNOWN. Controller backup create/export and
secret-bearing managed-device config export are excluded from this viewer design.

## 3. Normalized rule and object model

Use typed DTOs within existing modules. Keep vendor meaning rather than flattening
all products into an IP/port ACL. Proposed records (not a migration specification):

| Record / fields | Meaning and invariant |
|---|---|
| Snapshot envelope | Internal UUID; job/run; vendor/version/API/parser/schema versions; manager/domain/device/context/package refs; collection start/end; read kind; evidence grade; consistency/completeness and withheld/unsupported counts. |
| Rule identity | Opaque native UID/UUID/ID plus owning authority/context and layer/rulebase; neXus reference distinct from display name. Position is not identity. Where no durable ID exists, continuity is UNKNOWN; fingerprints only suggest matches. |
| Placement | Rule type (security/NAT/section/default), ordinal/order key, section/layer refs, inline-layer parent occurrence and full path; preserve repeated use of a shared layer. |
| Label/state | Name, comment/description, enabled state with UNKNOWN distinct from false, vendor metadata timestamps with source and timezone. |
| Match | Typed source/destination expressions, service/protocol/source and destination port ranges, applications, users/groups, ingress/egress zones/interfaces, time/schedule and negation flags; Any, Missing and Unresolved are different variants. |
| Effect | Native action plus bounded normalized action; logging start/end, track mode/alert/log-forwarding/profile refs; do not equate all Accept actions or treat missing logging as disabled. |
| Applicability | Explicit install-on refs, package targets, inherited applicability and observed installed relationship; an unresolved target remains unresolved. |
| NAT | Separate rule refs and original/translated source/destination/service, section/order, automatic/manual, static/dynamic, bidirectional and exemption semantics; security linkage can be many-to-many or UNKNOWN. |
| Usage observation | Rule and target/context refs, count, vendor last-hit, observed_at, requested/actual window, aggregation kind, reset epoch, completeness and source. Nullable count is never zero; usage does not change the rule-content hash. |
| Typed extension | CP VPN/content/negation/cleanup/layer details; PAN rule type, App-ID, application-default, profiles and target negation; FortiOS inspection/NAT mode and profile refs; ASA ACL role, direction, inactive, time-range and NAT section. |

Extensions are versioned, allowlisted typed structures, not arbitrary vendor JSON.
An unfamiliar field creates an unsupported-field count/type/path classification;
sensitive values are withheld, never silently dropped or placed in a catch-all blob.
If it can affect matching/effect, semantic completeness is false and dependent
analysis is NOT_EVALUABLE. The UI states that loss explicitly.

Object identity is `(authority, context, native_ref, type)`. Resolve nested groups
recursively using memoization and a DFS visiting stack; detect a cycle on the current
path, not merely any repeated node. Preserve original group DAG and membership order
where meaningful. Missing refs, cycles, dynamic groups, FQDNs and external feeds carry
explicit unresolved reasons; never expand them to Any or resolve DNS from the agent.
Proposed limits: depth 32, 100k objects, 1m membership edges per policy scope; a limit
produces PARTIAL expansion and disables affected proofs. Avoid materializing every
IP in a CIDR or every Cartesian product. Actual estate needs are UNKNOWN.

## 4. Presentation and operator workflow

Use a table-first **Policy & Objects** page within the existing shell. Reuse the
management tree for navigation and opaque target links; show cluster as an operational
unit with member-evidence chips and nested policy contexts. Do not re-add VS nodes to
Configuration. Preserve inventory/configuration/alignment as distinct product planes.

Pattern references, inspected 2026-10-01: Tufin's
[Rule Viewer](https://forum.tufin.com/support/kc/tos5/Content/ST2/RuleViewer/RuleViewer.htm)
demonstrates searchable read-only rules, expandable groups and drill-down;
[Compare](https://forum.tufin.com/support/kc/R25-1/Content/Suite/comparing_revisions.htm)
demonstrates revision comparison. Opinnate's
[analysis overview](https://opinnate.com/capabilities/analyze-firewall-policies-and-usages/)
connects rules/objects, usage and findings. Adopt these interaction patterns, not
their layout, assets, proprietary query language, automation or claimed performance.

- **Header:** authority/domain/context, package/layer, source (`Management intent`
  or `Device effective`), collection interval, freshness and coverage (for example,
  `9,800 of 10,000 rules parsed; 200 not evaluable`). Never label manager intent live
  enforcement. Last-known-good is explicitly stale; a failed read is not zero rules.
- **Rulebase table:** sticky header, section/layer rows, original ordinals, disabled
  markers, source/destination/service/application/user/zone chips, action, logging,
  usage-window indicator and evidence-backed risk badges. Default vendor order;
  alternate sorting explicitly says it is not evaluation order. Server paging at
  100 rows, bounded chip previews and lazy group expansion; no 10k-row DOM.
- **Search:** IP/CIDR containment/overlap plus port/protocol; example
  `destination=192.0.2.25 protocol=tcp port=443`. Default result means **potential
  configured match**, not proven connectivity. Offer source/destination selection,
  negation handling and an explanation path through group members. Without source,
  zones, user, application, schedule and NAT context the missing dimensions remain
  UNKNOWN. No automatic DNS, topology walk or live policy-match query.
- **Filters:** vendor/authority/context/layer, changed/stale/partial, any-any,
  disabled, no-log, zero hits in a stated window, shadowed/redundant, expiring time
  objects, unresolved objects. Unknown logging/usage must not enter no-log/unused.
  Proposed expiry horizon: next 30 days in the object's declared timezone.
- **Detail drawer:** full normalized rule, typed vendor fields, group DAG, install
  targets, NAT links, source/read kind, timestamps, analysis premises and exclusions.
  Reuse Compliance's filter/table/drawer conventions and M3 tokens, with keyboard
  access, focus return, readable chip expansion and text labels beyond color.
- **Diff:** select two complete compatible collections, including last week; show
  added/removed/edited/moved rules side by side in original order. Compare content,
  placement and resolved-object hashes separately; indirect group-member changes
  highlight affected rules. A partial collection cannot establish removals. Version
  mismatch or ambiguous rule identity gives `Comparison unavailable` or an explicit
  candidate match, not a fabricated rename. Compare intent and effective views only
  as a separately labeled relationship, never as ordinary successive revisions.
- **Rule timeline:** link each observed change to the two snapshots, including
  object dependency changes and collection gaps. It is observation history; exact
  change time/author is UNKNOWN without separate authoritative audit evidence.
- **Risk/cleanup:** any-service, any-destination, permissive-to-internet and clear-text
  service badges explain the matched condition and limitations. Unused, shadowed,
  redundant and duplicate-object recommendations name evidence and coverage. No
  delete/disable/edit/apply action; never claim removal is safe from a badge alone.

AIView uses consistent pseudonyms in table, drawer, chips, search, diff and timeline:
`FW-TANGO-04`, `CLS-ROMEO-01`, invented `OBJ-ALPHA-01` and RFC 5737 example addresses.
Object names, comments, user references and FQDNs are sensitive too. For AIView search,
use opaque object selections or a scoped masked projection; do not inverse-map a
pseudonym from its spelling or treat masked CIDR arithmetic as a correctness oracle.
Masked search equivalence/collision behavior must be proven before exposing it.

## 5. Analysis engine and correctness limits

Run analysis server-side on a complete immutable snapshot, never in the browser and
never on masked approximations. Emit `(finding, rule_refs, algorithm_version,
snapshot_refs, premises, coverage, evidence, reason)` and distinguish PROVEN,
POTENTIAL, NOT_EVALUABLE and PARTIAL. Counts carry the evaluated-rule denominator.

### 5.1 Ordered semantics before optimization

| Vendor | Required evaluation partition and caveat |
|---|---|
| Check Point | First match within an ordered layer; Accept continues through later ordered layers, Drop terminates. Inline layers add parent match constraints and their own cleanup behavior. Never flatten layers into a single first-match list. |
| PAN | Measured effective pre/local/post/default sequence per vsys and policy type. App-ID, application-default, user, zone, schedule, negation and profiles are predicates/effects, not decorative columns. |
| FortiGate | First applicable policy in the measured VDOM/mode sequence, not smallest policy ID. Interface pairs, VIP/DNAT, central NAT and NGFW mode can alter applicability; do not generalize forward-policy semantics to local-in or proxy. |
| ASA | First matching ACE within its bound ACL/context/direction; separate implicit/default behavior and ACL purposes. NAT has its own ordering, including manual and object NAT, not the security ACL order. |
| Spark / Radware | No generic inheritance of another vendor's algorithm. Unmeasured mode/order yields NOT_EVALUABLE. |

CP ordering basis: [Ordered and Inline Layers](https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/CP_R82_SecurityManagement_AdminGuide/Content/Topics-SECMG/Ordered-Layers-and-Inline-Layers.htm).
PAN, Fortinet and ASA references are in §2. Vendor documentation informs the model;
the proven version/mode matrix still requires P0 evidence. Configuration analysis
does not prove handling of existing sessions, application evolution or end-to-end
reachability through routing, other firewalls and dynamic identity services.

### 5.2 Minimal sound algorithm

1. Partition by authority, enforcement context, rulebase, target and proven evaluation
   sequence. Resolve static groups and encode IP/port intervals plus all other known
   predicates. Retain negation; unresolved dimensions prevent a proof.
2. For enabled rule R, compute the part of its match set reachable at this sequence
   point. Subtract the union of earlier **terminal** match regions under the same
   context/path. Empty residual proves fully shadowed only if every dimension and
   preceding control-flow effect is known. Keep covering-rule refs as a witness;
   nonempty overlap is partial overlap, not full shadowing. A union can cover R even
   when no single predecessor does; do not use pairwise-only tests as full coverage.
3. Redundancy additionally requires equal observable disposition, logging, profiles,
   schedule and applicability. Restrict v1 proofs to fully covered equivalent rules;
   other opportunities stay POTENTIAL. Unknown earlier rules are barriers to proof;
   do not skip them to conclude safe behavior downstream.
4. Canonicalize static object membership/type/scope and hash it for duplicate
   candidates; confirm equality after hashing. Same members across different scopes,
   dynamic definitions, labels/ownership or update lifecycles do not prove safe merging.
5. Usage: recommend a 90-day complete observation window, subject to PO choice and
   vendor support. Zero observed hits only means no observed use within that window.
   Reset, reboot, HA switch, collection gaps, rule recreation or incomplete target
   aggregation invalidates continuity. No-log does not imply unused; no counter means
   UNKNOWN. Preserve disaster-recovery/seasonal rule caveats in every recommendation.
6. Proposed explainable permissiveness score for enabled permit rules only: +25
   any-source, +25 any-destination, +20 any-service, +20 verified Internet exposure,
   +10 clear-text service, capped at 100. This is a prioritization heuristic, not a
   compliance/probability score. Report known subtotal and missing conditions; no
   final score when a required dimension is unknown. Internet exposure needs approved
   zone/address classification; a public-looking address or zone name is insufficient.
   A port number suggests a clear-text service; it does not prove unencrypted payload.

### 5.3 10k-rule budget

Parsing/hashing is O(input bytes); object DAG traversal O(objects + edges). Naive
ordered all-pairs comparison is about 50 million pairs at 10k rules and set splitting
can grow far worse. Index candidates by context, protocol and address/port intervals;
keep a bounded residual set rather than expanding addresses. Proposed initial caps:
2m candidate comparisons, 100k residual fragments, 60 seconds and 256 MiB per analysis
job. On a cap, save partial findings with evaluated counts; unvisited rules are UNKNOWN.
These are unbenchmarked targets. A worker cannot advertise complete 10k-rule analysis
until synthetic dense-overlap, deep-group and adversarial fixtures pass the budget.
Keep the viewer available when analysis is partial; do not add a solver dependency
until measured workload demonstrates a need and a separate change authorizes it.

## 6. Storage, versioning, deduplication and retention

Recommend additive normalized policy storage in the existing persistence layer, not
a copy of full vendor text in `sanitized_text`. Design entities: snapshot manifest,
immutable rule/object content, ordered snapshot membership, dependency edges, usage
observations and derived findings. Future schema/security contracts and migrations
are prerequisites; none are created here. Any future table migration includes the
required `GRANT SELECT, INSERT, UPDATE, DELETE ON <table> TO ui2_app;`.

Record every completed collection even if unchanged. Scope dedup to the authority/
tenant and parser/schema version. Hash canonical typed content, preserving opaque
IDs and semantically ordered arrays; sort only proven sets. Keep order/placement
outside the rule-content hash so a move remains visible; track resolved dependency
hashes separately so object edits propagate to affected rules. Usage timestamps and
counters have independent records. A hash is not a rule identity or API authorization.

Stage a bounded snapshot; atomically publish only after required pages/contexts and
object references have coverage results. Persist partial manifests separately; never
replace last complete with a failed run. Across paginated managers, completeness and
revision consistency are distinct. Parser upgrades create a new derived version,
not a historical rewrite; incompatible versions block semantic diff.

Operational rule/object values are sensitive: encrypt approved normalized payloads
using existing encryption/key infrastructure and restrict derived search material to
the same authorization boundary. Prefer per-snapshot bounded in-memory search in P2
over a new plaintext address index. Key access, schema and retention need a frozen
security design before implementation. Unknown values do not enter arbitrary blobs.
Do not retain new raw API responses. Existing encrypted configuration/backup artefacts
stay under their own contracts; this viewer neither extends nor bypasses their access.

Proposed retention: 90 daily manifests, 12 weekly and 12 monthly checkpoints, latest
complete always retained; usage retains the approved observation window. PO must
ratify retention/storage budget. Reachability-based GC preserves content referenced
by any retained manifest and its analysis; do not delete a blob merely because one
snapshot expires. Cleanup affects neXus storage only, never a device.

Sizing example (planning assumptions, decimal MB, compression excluded): 10k rules
at 2 KB = 20 MB; 20k objects at 0.5 KB = 10 MB; 30k membership refs at 64 B = 1.92 MB
per manifest. With 1% content churn/day, 90 daily collections cost about
`30 + 89 × 0.3 + 90 × 1.92 = 229.5 MB` per scope, before usage/index/encryption and
database overhead. Budget 2–3× that, roughly 0.46–0.69 GB; 100 independent scopes
roughly 46–69 GB. Weekly/monthly survivors, backups and dense graphs add to this.
At 100% churn, content alone is 2.7 GB/scope for 90 days. Real size/churn UNKNOWN;
measure P0 bytes/rules/objects and avoid promising these as production capacity.

## 7. Collection cadence, load and failures

Reuse `JobAdmissionService`, durable job/run IDs, capability resolution, existing
transport/credential references and worker persistence. Parser service stays stateless
with no device or database credentials. Service serves authorized stored projections;
browser sends typed intent and opaque scope refs, never commands/endpoints/XPaths.

Recommended split: extend PAN's existing configuration read with a policy projection;
add a separately admitted CP management-policy job only after its contract/gates.
Keep distinct source scopes and freshness even if scheduling coordinates them. Do not
run one CP collection per gateway when they share the same management layer/package.

Proposed P1 cadence: manual pilot first, then once nightly in an approved quiet window;
no event-driven refresh or UI polling that contacts a device. Reuse PAN's existing
collection schedule rather than adding a second full effective-running read. MDS
export is currently scheduled at 02:00 Europe/Istanbul; inventory at 23:00 with a
23:30 failed-device retry. That inventory retry is not adopted for policy jobs. Actual
export duration and safe policy window are UNKNOWN. Require manager-level exclusion
against export and serialize domains; a named time alone does not prevent overlap.

| Resource | Proposed initial guard; measure before enabling |
|---|---|
| MDS API | One outstanding call and one domain session per manager; reuse within run, close on every exit. Count operator/other-client load too. Session ceiling, timeout and rate budget UNKNOWN. Propose 60 s/page and 15 min/scope with no automatic retry. |
| PAN API | One outstanding call per endpoint; preserve existing 30/120/60 s read deadlines for active/effective/merged and current gate frequency. Stream; fail on size/depth limits. Management CPU/API latency and safe combined schedule UNKNOWN. |
| CP gateways | No added gateway rulebase read or per-VS Gaia repetition. Reuse stored installation observations as separate evidence. Added CPU from the proposed CP policy stream falls on management; its measured impact is UNKNOWN. |
| FortiGate / ASA | P4 serial reuse of current config sessions; no concurrency increase, interactive paging bounded. Actual CLI response sizes and acceptable schedules UNKNOWN. |
| Snapshot limits | Propose 20k rules, 100k objects, 1m edges, 128 MiB decoded bytes/scope and 1,000 pages. Hard caps report PARTIAL; a larger scope requires measured redesign, not silent truncation or unlimited memory. |

`GateRowData` holds vendor/platform/shell/transport/canonical command key, action
class, sign-off, timeout, retry, frequency, session reuse, unsupported behavior,
secret risk, safe telemetry and source pointer. `GateRegistryPort`/`GateResolver`
resolve at load and execution claim time. New reads require exact approved gate rows
in both migration and fixture, plus capability binding; zero/ambiguous/unsigned matches
refuse execution. Parse-only reuse of the same command/session/timeout/frequency
needs no duplicate command row, but needs approved new policy/privacy semantics.
This lane adds zero rows. P0 command approval is per exact code/command/target/output
projection; one measured read does not authorize the next.

Failures: authentication/permission refusal, rate-limit/busy response, deadline,
mid-pagination mutation, truncation, malformed XML/JSON, missing context, unresolved
object, duplicate ID, worker crash or persistence failure produce distinct safe reasons.
No raw body in exceptions/logs. Do not restart a failed collection automatically;
release sessions, preserve last complete, expose age and retry only via a later
authorized job. A failed sub-scope cannot silently disappear from the denominator.

## 8. Security, privacy, RBAC and audit

Current `ActionRegistry`: configuration collection/text and global search require
`role:onboarding_admin`; general device reads are authenticated; backup retrieval
requires `role:backup_admin`; role administration requires `role:security_admin`.
These are separate grants, not interchangeable admin privileges. No current policy
permission is assumed. Recommend explicit new actions within this registry/gate chain:

| Proposed access | Recommended authority, subject to PO ratification |
|---|---|
| Full secret-free normalized policy text, objects, search, diff/history | `role:onboarding_admin` with explicitly authorized source/context scope. No inherited access merely from device_read or security_admin. |
| Masked policy view/search/diff/history | Dedicated masked read route/action for `role:replay_viewer`; no fallback to full text and no collection submission. |
| Submit approved read-only collection | `role:onboarding_admin` plus current capability/gate/enrollment/target admission checks. |
| Raw configuration/backup download | Never through policy routes; existing backup authority remains separate. No export in P1. |

Reuse existing role bindings; do not add a new role hierarchy. Whether present RBAC
can enforce per-domain scope adequately is UNKNOWN: an explicit authorization test
is a P1 prerequisite. Gate every drawer, object expansion, query, diff and historical
snapshot on the same source scope; an opaque UUID never bypasses authorization.
Audit actor reference, action, snapshot/job refs, scope and decision before sensitive
reads; never log raw search terms, IPs, rule text or comments. Do not fabricate vendor
change-author history from the neXus collection actor.

Use allowlist parsing before persistence. Harden XML against DTD/external entities;
bound JSON nesting/strings, decompression and group recursion. Render text as text,
never HTML; comments and names are untrusted input. Withhold credentials, certificates,
key blobs and unknown sensitive extensions even for a full-view operator. The existing
backup exception for encrypted raw bytes is not authorization for new policy retention.

Reuse `PrivacyMaskingResponseBodyAdvice`, `TopologyNamePseudonymizer` and
`SubnetPreservingIpMasker`, extending typed policy projection coverage explicitly.
Current generic masking knows selected field names and known topology strings; it is
not proof that arbitrary object names/comments/users/FQDNs or a new DTO are safe.
Pseudonyms must be stable across retained snapshots with namespace and key-version
control, collision detection and opaque joins. Mask NAT original/translated values,
install targets, group members, annotations, errors, query echoes and extensions.
Default AIView free-text comments to withheld unless a bounded safe projection exists.
Fail closed on unsupported output types; cache keys include persona and authorization
scope, and responses must not leak through shared caches, browser storage or URLs.

All agent/PO UI inspection uses AIView. Synthetic tests must prove no leaks across
table/drawer/search/diff/history/errors, including unfamiliar fields and mixed-role
sessions; numeric/format normalization of opaque identities is forbidden. Never send
policy data to external analysis services. Reuse existing transport trust controls;
this draft neither changes the environment's existing PAN TLS decision nor generalizes
it to another vendor or a new CP HTTPS path.

## 9. Delivery phases, measurement targets and effort

Estimates: one engineer, working days, existing infrastructure, no new dependency;
exclude PO/vendor waiting time and live-window availability. These are planning ranges,
not measured throughput or delivery commitments. Each implementation phase needs the
appropriate approved scope/contracts; none is authorized by this DRAFT.

| Phase | Size / effort / risk | Outcome and exit evidence |
|---|---|---|
| P0 measurement | M; 5–8 days for CP/PAN, 3–6 additional for later-vendor readiness; high semantic/privacy risk | Exact read review, official version docs, safe measured shapes/counts/order/windows, byte/load limits and synthetic equivalent fixtures. Ratify source/model/privacy/storage decisions before P1. No bulk fleet run. |
| P1 CP + PAN viewer | L; 12–18 days; high identity/privacy, medium UI risk | Complete gated collection, versioned snapshots, source badges, paged ordered rules, groups/drawer, RBAC and masking. PAN reuses effective-running; CP management API adapter. No false installed-state claim; no advanced analysis. |
| P2 search/diff/history | M; 7–10 days; medium/high correctness risk | IP/CIDR/protocol/port search with UNKNOWN dimensions; stable-ID/content/order/object-aware comparison and observation timeline. Synthetic 10k-rule latency/memory evidence; masked search equivalence verified. |
| P3 analysis | L; 10–15 days; high semantic/false-positive risk | Bounded shadow/redundancy proofs, usage-window evidence, duplicate candidates and explainable risk badges; negative fixtures for uncertain semantics and explicit analysis coverage. |
| P4 other vendors | L; FortiGate 6–10, ASA 5–8, Spark 4–7 days; Radware UNKNOWN until API/product discovery | Separate vendor/version acceptance. Reuse existing reads where sufficient; typed unsupported cases. Radware receives an estimate only after an exact read-only API and policy meaning are established. |

### P0 named test roles (invented masked labels, not actual targets or approval)

| Test role | Proposed reads and measurement question |
|---|---|
| `MGR-ALPHA-01`, one test MDS with two domains and one standalone management variant | CP-1..5 one at a time; CP-6 separately. Exercise >100 rules/objects, ordered+inline/shared layers, NAT, object cycles/missing refs, install targets and concurrent-edit detection. Record only shapes, counts and derived relationships. |
| `CLS-ROMEO-01`, test gateway/VSX policy targets | No new gateway read; compare stored install observations with management target mappings. Package association versus actual revision match remains UNKNOWN unless independent evidence proves it. |
| `FW-TANGO-04`, one test PAN multi-vsys firewall; `MGR-BRAVO-01`, optional test Panorama | PA-1 existing-stream extension; PA-2 source distinction; PA-3 only for proven manager presence; PA-4 separately. Measure inherited order, shared/local objects, UUID stability, defaults, NAT/profile links and counter reset/window semantics. |
| `FW-JULIET-06`, test FortiGate with two VDOMs | Existing `show` first; focused §2.3 reads only if separately approved. Measure VDOM coverage/default omission, groups, order, VIP/central NAT and absence of leakage. Runtime usage read remains UNKNOWN. |
| `FW-BRAVO-02`, test ASA in single context | Existing running-config first; §2.4 candidates only as needed and approved. Measure ACL binding, nested groups, time ranges, NAT sections and expanded-ACE counter aggregation. Separate multi-context support decision. |
| `CP-SPARK-TEST-01`, test Spark | Establish management mode/version from existing evidence, then management reuse or exact local candidate review. Enumeration/object/NAT completeness UNKNOWN. |
| `MGR-CHARLIE-01`, test Radware CC and one identified managed product | Documentation/schema review first; inventory route can establish scope after approval. No policy read until exact endpoint and read-only semantics are proven. |

Measurement reports include transport/version, granted scope, page/count/byte/time
metrics, field names, identity MATCH/MISMATCH and secret-withholding counts only.
Proposed test roles need PO mapping to actual enrolled opaque targets outside this
document. Load observation must use existing safe telemetry; an extra diagnostic
device command requires its own exact approval. No such device action occurred here.

Future acceptance: parser/order/identity/privacy tests, page failure and snapshot
atomicity tests, RBAC cross-domain denial and no-leak tests, object cycles/negation/
NAT/usage resets, 10k-rule dense cases, relevant Java tasks, frontend typecheck/tests/
build, repository privacy gate and AIView human review. Automated fixtures do not
replace real version-specific evidence. This document-only lane requires no new
runtime tests; its validation is section/source/link/scope review and privacy gate.

## 10. Open questions for the Product Owner

1. Approve CP management intent and PAN effective running as the distinct P1 sources?
2. Confirm Policy & Objects context navigation, and resolve the PAN VSYS wording
   conflict in Design Language D-UI2 versus 14G without changing Configuration here?
3. Which enrolled test scopes and exact individual P0 reads should receive approval?
4. Is Panorama present for the chosen pilot, and is CP Spark centrally or locally managed?
5. Approve onboarding_admin full-view scope and replay_viewer masked-only policy access?
6. Approve proposed retention, encrypted normalized storage and budget, or reduce them?
7. Which quiet window avoids MDS export, installs and other management API clients?
8. Use 90 days for unused-rule evidence and 30 days for impending schedule expiry?
9. Which approved zone classification defines Internet exposure; accept unscored UNKNOWN otherwise?
10. Which Radware managed product/policy is wanted, and should ASA multi-context remain deferred?

Recommended next movement: bounded P0 measurement design and PO review, High reasoning
for vendor/privacy decisions; implementation only after those decisions and exact gates.
