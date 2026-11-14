# Cisco ASA and FortiGate policy collection and model - design (Phase A)

## Status

DRAFT - 2026-11-14. Design and measurement plan only; not implementation authority (AGENTS.md "Contract-status law").
Written without any device contact and without reading any device configuration text. The database facts in section
3 are counts, byte sizes and presence booleans from the live ui2 database (AIView mentality); no name, address or
configuration line was read or printed. The vendor semantics are an amendment to the frozen
`PATH_ANALYSIS_VENDOR_SEMANTICS.md` (A0): `PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_14_ASA_FORTIGATE.md`
(FROZEN 2026-11-14; ids `A0-ASA-n`, `A0-FG-n` below). The PO's answers to section 10 are recorded in
`PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md` (Q1 "Evet, başla"; ASA first). Since this draft was
written, the quote re-check resolved two gaps listed in section 3: the ASA port and protocol literal tables are
official (A0-ASA-15, HIGH) and a FortiOS 7.4 address with no `subnet` line defaults to `0.0.0.0 0.0.0.0` (A0-FG-24).

Trigger: PO decision 2026-11-14 (relayed by the PO session): Cisco ASA and FortiGate firewalls get policy collection
and a policy model like Check Point and Palo Alto, so Trace/topology, policy hygiene, the zone matrix and the ACI
pre-cutover impact analysis can evaluate them. The ACI design (`ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md`,
DRAFT, unmerged branch `docs/aci-segmentation-design-1113`) answers such targets today with
`NOT_EVALUABLE (VENDOR_POLICY_NOT_COLLECTED)`; this design is the separate build its Q8 names.

## 1. Answer in brief

- **No new device command is needed.** Both vendors' complete policy is already collected, encrypted, every day, by
  two existing reads: Cisco ASA `more system:running-config` (gate `asa_more_system_running_config`) and FortiGate
  `show` at the top level (gate `fgt_show_configuration`). Each is stored twice: in the backup bundle
  (`running-config.txt`, `fortigate.conf`) and as the configuration-plane artefact (`device_configuration_run`,
  `read_kind = show_configuration`). The configuration-plane artefact is the one to parse: it is a single encrypted
  text object, and the Palo Alto local-firewall policy already follows exactly this route
  (`LocalFirewallPolicyService` decrypts the latest `effective_running` artefact and maps it to `PolicySnapshot`).
- **Completeness for this estate:** all 12 ASAs are single context in routed mode, so the stored text is the whole
  policy (ACLs, `access-group` bindings, objects and groups, NAT in all three sections, interfaces with `nameif` and
  `security-level`, `same-security-traffic`). All 9 FortiGates (FortiOS 7.4) store every VDOM in one `show`; none uses
  central NAT, NGFW policy-based mode, zones, proxy inspection or transparent mode. The one structural gap is
  FortiOS `show` omitting default values: the parser must apply a cited FortiOS 7.4 defaults table, and any absent
  setting without a cited default makes that rule UNKNOWN (section 4.3).
- **What this needs from the PO:** approval to use the two existing reads' output for a new purpose (the policy model)
  as a parse-scope extension (Q1); the A0 amendment (Q2); and the decisions in section 10. Optional later reads
  (hit counts, `packet-tracer` corroboration) are listed with literals in section 6 but are **not** requested now.
- **Delivery:** measurement harness first (counts only, in-pod, no raw text leaves the pod), then the ASA parser and
  mapper, the FortiGate parser and mapper, then normalization, lookup, hygiene, Trace and zone-matrix wiring
  (section 8). About 9 steps; the first value (ASA and FortiGate policy visible and searchable) lands at step 4.

## 2. How Check Point and Palo Alto work today (what the new vendors plug into)

Summarised from source (paths under `ui2/`):

- **Model:** `platform-core/.../policy/PolicySnapshot.java`: `Metadata` (vendor string, in practice `"CP"`/`"PAN"`;
  container = CP domain / PAN device group / PAN vsys), ordered `Section`s of `Rule`s, an object dictionary of
  `PolicyObject`s (`status` RESOLVED / UNRESOLVED / DYNAMIC / UNSUPPORTED / UNKNOWN), `Cell(refs, negated)`, open
  `extras` (zones, install-on, NAT translations, time). NAT rules are ordinary `Rule`s in sections marked outside
  security evaluation. Snapshots are "management intent, never evidence of install".
- **Mappers:** `CheckPointPolicyMapper` (Management API JSON) and `PanoramaPolicyMapper` (Panorama XML; `local()`
  maps a firewall's stored `effective-running` per vsys). No ASA or FortiGate policy parser exists.
- **Normalization:** `service/.../policy/PolicyNormalization.java` resolves cells into selectors (ipv4/ipv6/tcp/udp/
  icmp ranges); hard-gated to `CP`/`PAN` (`:231`, `:357`); terminal actions hard-coded (`:247`); an unknown extra key,
  `enabled == null`, time/user/content constraints -> `Unknown`.
- **Engines:** `PolicyLookup` (first match, CP layers and inline layers, PAN pre/local/post phases; anything else
  "unsupported evaluation scope", `:130`); `HopPolicyEvaluator` and `HopNatEvaluator` (path analysis; other vendors
  `UNSUPPORTED_VENDOR` / `NAT_UNSUPPORTED_VENDOR`); `PathPolicyBinder`, `PathEngine`, `TopologyBuilder` filter to
  `check_point`/`palo_alto`; `PolicyHygiene` and `PolicyRuleMetrics` carry PAN-only zone logic.
- **Collection gate:** `worker/.../policy/PolicyCollectionJobExecutor.java` accepts only `check_point`/`palo_alto`
  (`POLICY_SOURCE_NOT_ELIGIBLE`). The ASA/FortiGate route here does not use it (no policy collection job; the
  configuration collection already brings the text).
- **Storage:** `policy_snapshot` (V115) has no vendor column; `policy_revision` / `policy_change_evidence` (V163) check
  vendor in (`palo_alto`, `check_point`) - only the change-evidence feature needs that widened, later.
- **Zone matrix:** no code on `main`; DRAFT contract on branch `docs/zone-matrix-contract-1113`.

## 3. Artefact findings (what is already collected)

Database counts, 2026-11-14 (enrolled, not disabled; masked; no content read):

| Fact | Cisco ASA | FortiGate |
|---|---|---|
| Enrolled gateways | 12 (10 in 5 HA pairs by `ha_pair_claim`, 2 single) | 9 (+1 FortiManager, out of scope); 1 with a pair claim |
| Software | 9.18 (8), 9.22 (4) | FortiOS 7.4 (all) |
| Read issued | `more system:running-config` (privileged exec, 180 s, once per run) | `show` at top level (600 s), plus `show full-configuration system global/interface/ha` spliced in |
| Configuration-plane artefact (latest) | 12 of 12, collected 2026-11-13, 189 KB to 2.6 MB | 9 of 9, collected 2026-11-13, 464 KB to 2.8 MB |
| Collection frequency | about 23 runs per device in 30 days | about 23 runs per device in 30 days |
| Backup bundle member | `running-config.txt`, same sizes as above (12 of 12, 2026-11-13) | `fortigate.conf` 309 KB to 2.6 MB (9 of 9) |
| Context | single context on 12 of 12 (the configuration collector refuses multiple context and all 12 have runs; `show-mode.txt` is 29 bytes on all 12) | 7 single-VDOM, 2 multi-VDOM with 3 VDOMs each |
| Firewall mode | routed on 12 of 12 (no `firewall transparent`) | no `opmode transparent` in any stored VDOM settings |
| Interfaces | `nameif` and `security-level` present on 12 of 12; no IPv6 interface address | interfaces stored per VDOM |
| NAT style | sections 1-3 in the text (counts: measurement) | central NAT on 0 of 9 (VIP + policy NAT mode) |
| Other mode facts | - | NGFW policy-based 0 of 9; `config system zone` 0 of 9; proxy inspection 0 of 9 |

Notes on these numbers:
- The FortiGate mode facts come from the allowlisted sanitized projection (`system settings`, `system zone` and
  interfaces are in the allowlist), counted by pattern, never printed. Because `show` omits defaults, "absent" there
  means "at default" (central NAT off, profile-based mode), which is the documented default only where A0-FG-5/21 say
  so; the measurement (section 7) re-counts this from the full text.
- The ASA sanitized projection keeps only system, management, logging, failover, interface and routing families (PO
  2026-09-27); `access-list`, `object`, `object-group`, `nat`, `access-group`, `same-security-traffic` are not in it.
  The FortiGate projection likewise drops every `firewall` block. Neither projection can feed the policy model; the
  encrypted raw artefact can.

**Completeness verdict per item (from the stored text, single context / all VDOMs):**

| Needed for the model | ASA artefact | FortiGate artefact |
|---|---|---|
| Rules in order | `access-list <name> extended ...` lines, configuration order (A0-ASA-1) | `config firewall policy` edits; order = sequence (A0-FG-1), printed order to be confirmed by the measurement |
| Rule binding | `access-group ... in/out interface <nameif>` and `global` (A0-ASA-2/3) | `srcintf`/`dstintf` in each policy (A0-FG-9) |
| Addresses, groups | `object network`, `object-group network`, `names`/`name` aliases, inline host/mask/range | `config firewall address`, `addrgrp`, `address6`, `addrgrp6` per VDOM |
| Services | `object service`, `object-group service/protocol/icmp-type`, inline `eq/lt/gt/neq/range`, port names | `firewall service custom`, `service group` |
| NAT | `nat (...)` sections 1 and 3, object NAT section 2 (A0-ASA-17) | `firewall vip`, `vipgrp`, `ippool`, policy `nat`/`ippool`; `central-snat-map` (unused) |
| Interfaces / zones | `interface` blocks with `nameif`, `security-level`, address (A0-ASA-4/5) | `system interface` (full form spliced), `system zone` (none) |
| Defaults | absent = default (A0-ASA-20); `same-security-traffic` absent = disabled | absent = default **only with a cited 7.4 default** (A0-FG-5/6); otherwise UNKNOWN |
| Implicit rules | implicit deny / security-level default are vendor behaviour, not text (A0-ASA-1/3/4) | implicit deny is vendor behaviour (A0-FG-3) |
| Time / identity | `time-range`, `user`, `security-group` in ACEs -> UNKNOWN | `schedule` other than `always`, `groups`/`users`, ISDB -> UNKNOWN |
| Hit counts | not in configuration text | not in configuration text |

Gaps: (a) FortiOS defaults (handled by the defaults table + UNKNOWN); (b) ASA port-name table and `port-object` shape
(A0-ASA-14/15; to cite or measure); (c) FortiGate predefined services and the meaning of a `subnet`-less address
(A0-FG-24/27; measured); (d) hit counts (no read; optional, section 6); (e) a future multiple-context ASA (none today;
literal sketched in section 6, not proposed).

## 4. Vendor semantics (summary of the A0 amendment)

### 4.1 Cisco ASA

- Per ACL: top-down, first match, implicit deny (A0-ASA-1).
- Per packet, inbound: the ingress interface's `in` ACL, then the `global` ACL, then the implicit deny; the implicit
  deny moves after the global rules when a global ACL exists (A0-ASA-3). Outbound: the egress interface's `out` ACL
  (A0-ASA-2). Both directions must permit.
- No inbound ACL and no global ACL: higher-to-lower security level permitted (A0-ASA-4a, HIGH); lower-to-higher denied
  is LOW (A0-ASA-4b); whether a bound inbound ACL removes the higher-to-lower permit is UNKNOWN (A0-ASA-4c, treated as
  "the bound list's implicit deny applies", corroborate with `packet-tracer`).
- Same security level: denied before any ACL unless `same-security-traffic permit inter-interface` /
  `intra-interface` (A0-ASA-5).
- ACLs match **real** (untranslated) addresses (A0-ASA-6): unlike PAN (pre-NAT addresses, post-NAT zone), the ASA ACL
  sees the server's real address after destination un-translation and the client's real address before source
  translation. The 9.x order of ACL, un-translation and route lookup is UNKNOWN (A0-ASA-18); matching itself does not
  depend on it, egress-interface selection does.
- NAT: section 1 manual first match in order; section 2 object NAT by quantity of real addresses, IP, name; section 3
  `after-auto` first match (A0-ASA-17).
- `inactive` -> disabled; `time-range`, `user`, `security-group`, FQDN objects, port names (until cited) -> UNKNOWN /
  DYNAMIC (A0-ASA-8, 13, 15, 16). Control-plane ACLs are out of the transit model (A0-ASA-9).

### 4.2 FortiGate

- Per VDOM, one `firewall policy` list, sequence order, first match (A0-FG-1/2, MEDIUM), implicit deny (A0-FG-3, HIGH).
- Match: `srcintf`/`dstintf` (interfaces, zones, SD-WAN zones; `any`), `srcaddr`/`dstaddr` (+`*-negate`), `service`
  (+`service-negate`), `schedule`, users/groups, ISDB (A0-FG-4/8/9/12).
- Packet order: VIP DNAT before routing, policy lookup, SNAT on exit (A0-FG-17, MEDIUM, 7.6/6.4 guides).
- VIP policies: "priority over typical firewall policies" (A0-FG-18) with no stated evaluation meaning, and whether a
  VIP dstaddr matches the external address is not stated (A0-FG-19): both are UNKNOWN and produce UNKNOWN verdicts
  where they would change the answer.
- Zones: intrazone default deny (A0-FG-10); none configured in the estate.

### 4.3 FortiOS defaults table (load-bearing)

`show` prints only non-default settings (A0-FG-6, MEDIUM, repository evidence). The parser therefore carries a
versioned defaults table, keyed by FortiOS minor (7.4 today), each entry citing the CLI reference page:
`firewall policy`: `action deny`, `status enable`, `nat disable`, `srcaddr-negate`/`dstaddr-negate`/`service-negate
disable`, `match-vip` (conflicting sources, A0-FG-20 -> UNKNOWN when it matters), `internet-service disable`;
`firewall address`: `type ipmask`; others added only with a citation. A setting the model needs that is absent and has
no cited default makes the rule (or object) UNKNOWN, counted per VDOM; it never falls back to an assumed value. A unit
on a FortiOS minor without a table -> its snapshot is marked `FGT_DEFAULTS_UNPROVEN` and every rule depending on an
absent setting is UNKNOWN.

## 5. Mapping into the normalized model

### 5.1 Common

- **Source:** the latest `show_configuration` configuration artefact per enrolled ASA/FortiGate gateway, decrypted in
  the service, parsed in memory, discarded (raw-evidence law). Cached by artefact reference + collection time, like
  `LocalFirewallPolicyService`. No new table in the first steps; snapshots are derived on read.
- **Metadata:** `vendor` = `"ASA"` / `"FGT"` (new strings beside `"CP"`/`"PAN"`); `sourceId` = device id;
  `containerId` = `ref(deviceId, context)` with context `single` (ASA) or the VDOM name (FortiGate; `root` on a
  single-VDOM unit); `artefactRef` = `ref("configuration-artefact", <ref>)`; one `Target(deviceId, name, context,
  IN_SYNC)` - the running configuration is the enforced configuration of that unit (A0 evidence law: still
  "configured intent", not runtime truth).
- **HA pairs:** each enrolled member yields its own snapshot. Where `ha_pair_identity` is MATCH and the policy-relevant
  canonical hash of both members is equal, hygiene and the zone matrix count the pair once; a hash MISMATCH is shown
  as a finding (`HA_POLICY_DIVERGENT`), never silently merged.
- **Objects:** inline literals become RESOLVED synthetic objects (as PAN literal IPs do); names stay opaque
  identifiers (identity law: no case folding; ASA object names are case-sensitive per configuration text, a
  normalisation is not assumed).

### 5.2 Cisco ASA -> `PolicySnapshot`

| ASA construct | Normalized form |
|---|---|
| `access-group A in interface X` | `Section(source="ASA access-group", name="X in", ...)` holding ACL A's ACEs; `extras.from=[X]`, `extras.to=[any]` on each rule |
| `access-group A out interface Y` | `Section(name="Y out")`; `extras.from=[any]`, `extras.to=[Y]` |
| `access-group A global` | `Section(name="global")`; `extras.from=[any]`, `extras.to=[any]` |
| ACL bound to several interfaces | one section per binding, rule ids `ref(device, binding, acl, index)`; hygiene runs once per ACL (the order inside an ACL is the same everywhere) |
| ACL not bound by any `access-group` | not a section; reported by hygiene as `ACL_NOT_BOUND` (not enforced as an access rule, A0-ASA-2) |
| ACE | `Rule(number = position in the ACL counting ACEs only, name = "A:n", enabled = !inactive, source, destination, service cells, application = empty, action = "permit"/"deny", log, comment = preceding remark lines)` |
| `time-range`, `user`, `security-group`, `object-group-user` | `extras.time`, `extras.user`, `extras.security-group` -> normalization `Unknown` (as CP time/user) |
| object / object-group network, `name` aliases, `host`, `a.b.c.d m.m.m.m`, `range`, `any`/`any4`/`any6` | address objects with `ip-netmask`/`ip-range` values; non-contiguous mask -> UNSUPPORTED; `fqdn` -> DYNAMIC; `any6` -> IPv6 universe (unresolved by the IPv4 engine) |
| service / protocol / icmp-type objects and groups, inline `eq/lt/gt/neq/range`, `tcp-udp`, `ip` | service objects with `protocol/tcp\|udp/port` values (`neq` = two ranges); `ip` = any protocol; port names unresolved until A0-ASA-15 is cited |
| NAT section 1 / 2 / 3 | three sections `ASA NAT section 1/2/3` (outside security evaluation); section 2 ordered by A0-ASA-17 keys; translations in the existing NAT extras keys |
| interfaces | `extras` on a synthetic per-snapshot default section `ASA security-level default` carrying, per interface, `nameif` and `security-level`; `same-security-traffic` flags |
| control-plane access groups, EtherType/webtype/standard ACLs | listed in a non-evaluated section (`ASA not evaluated`) with the reason, not modelled |

### 5.3 FortiGate -> `PolicySnapshot` (one per VDOM)

| FortiGate construct | Normalized form |
|---|---|
| `config firewall policy` | `Section(source="FGT firewall policy", name="<vdom> policy")`, rules in printed (sequence) order |
| policy edit | `Rule(id = ref(device, vdom, policyid), uuid = set uuid, number = position, name = set name else policyid, enabled = status (default enable), source = srcaddr(+srcaddr6, negate), destination = dstaddr(+dstaddr6, negate), service (+negate), application = empty in profile-based mode, action = accept/deny (default deny), ipsec -> UNKNOWN)` |
| `srcintf`/`dstintf` | `extras.from` / `extras.to` (interface, zone or SD-WAN zone names, `any`); zone -> member interfaces at evaluation |
| `schedule` other than `always`, `groups`/`users`, `internet-service*`, `application` lists in policy-based mode | `extras.time` / `extras.user` / ISDB -> `Unknown`; DYNAMIC destination/service |
| implicit deny | fixed section `FGT implicit deny` with one DENY rule matching any (A0-FG-3), like PAN default rules |
| zone `intrazone allow` | synthetic ALLOW rule for that zone pair in the implicit section, before the deny (A0-FG-10) |
| VIP / VIP group | NAT section `FGT VIP` (outside security evaluation): original destination `extip`(+port), translated `mappedip`(+port); in the address dictionary the VIP object resolves to `extip` with a `vip-mapped` value kept for A0-FG-19 parallel evaluation |
| policy `nat enable`, `ippool`, `poolname` | NAT extras on the rule (`source-translation-type`, `translated-source`) |
| `central-snat-map` | section `FGT central SNAT` (unused in the estate; `UNSUPPORTED` until measured) |
| address types | A0-FG-23: ipmask/iprange RESOLVED; fqdn/geography/dynamic/route-tag DYNAMIC; wildcard/mac UNSUPPORTED; interface-subnet resolved from the stored interface address (MEDIUM) |
| services | `tcp/udp/sctp-portrange` -> ranges with optional source ports (A0-S1 family); ICMP type/code; IP protocol number; undefined referenced service -> UNRESOLVED |
| `local-in-policy`, `proxy-policy`, `security-policy`, multicast/DoS policies | non-evaluated section with reason (A0-FG-13/14/15) |

### 5.4 What does not fit (engine changes, not data changes)

1. **ASA interface-chain evaluation.** CP has ordered layers, PAN one rulebase with zones. ASA needs a per-packet chain
   chosen by ingress and egress interface: `[X in ++ global ++ implicit deny]` then `[Y out]`, each "accept continues"
   - the CP ordered-layer combinator fits once the lookup selects sections by interface. `PolicyLookup.entries()` and
   `PolicyNormalization.EvaluationScope` get an `ASA` branch; the query's `sourceZone`/`destinationZone` carry the
   ingress/egress `nameif`.
2. **Security-level default.** A computed default (A0-ASA-4/5), not a rule in the text; ASSUMPTION parts surface as
   UNKNOWN reasons (`ASA_SECURITY_LEVEL_DEFAULT_UNPROVEN`).
3. **ASA real-address matching.** `HopPolicyEvaluator` needs an ASA order: NAT un-translate destination, evaluate ACLs
   on the real tuple, then source translation; egress interface from the post-un-translation route (ASSUMPTION per
   A0-ASA-18).
4. **FortiGate VIP priority and VIP match address.** UNKNOWN semantics (A0-FG-18/19) -> parallel evaluation with an
   UNKNOWN verdict on disagreement, as CP's `CP_ACCESS_NAT_ORDER_UNPROVEN`.
5. **New terminal action strings** (`permit`, `accept` - `deny` exists) and new extras keys (`from`/`to` for ASA/FGT
   placements, `security-group`, ISDB) in `PolicyNormalization`'s allowlists.
6. **Placement keys.** FortiGate placements are interface/zone sets with `any`, like PAN zones: hygiene's PAN zone
   superset logic generalises to an "interface placement" flag rather than `vendor == PAN`.
7. **Port and protocol universe.** ASA `ip` / FortiGate `ALL` mean any protocol; the resolver models only tcp/udp/icmp
   families today. Any-protocol is representable as `any`; a protocol-number service (e.g. GRE) stays unresolved.
8. **Hit counts.** None for these vendors without a new read: `PolicyRuleMetrics` unused-rule findings show "no hit
   data" (UNKNOWN), never "unused".
9. **Change evidence.** `policy_revision`/`policy_change_evidence` vendor checks (V163) exclude these vendors; the
   configuration plane's own change detection (`change_state`) already exists and is enough for the first steps.

## 6. New reads

**None is needed for the policy model.** The two reads already issued carry everything section 3 lists. The use of
their output for the policy model is a parse-scope extension (same command, session, timeout, frequency: AGENTS.md
"Network action taxonomy"), but the evidence law says a command's output may not be used for a new purpose without
going through the gate for that purpose; Q1 asks the PO to record that purpose, and the two gate rows'
`source_document_pointer` get this design's reference when it is frozen. No `safe_telemetry_fields` change: the policy
parser logs counts only.

Optional, **not requested now** (for the PO's later choice; each would need its own gate row and PO exact-literal
approval, CLASS 0 read, aiview-runnable only after approval):

| # | Purpose | Literal | Context / session | Expected size | Masking |
|---|---|---|---|---|---|
| R1 | ASA rule hit counts (hygiene "unused") | `show access-list` | privileged exec, the existing ASA shell, after the configuration read, once per configuration run, 300 s, no retry | UNKNOWN: the output expands object groups per ACE; with 0.2-2.6 MB configurations it can reach tens of MB. First run records byte and line counts only; cap 64 MB, fail closed | keep per ACE only `acl name`, line number, `hitcnt=`; addresses discarded in memory |
| R2 | ASA corroboration of A0-ASA-4c/18 (A6-style) | `packet-tracer input <nameif> tcp <src> <sport> <dst> <dport> detailed` | privileged exec, one call per PO-chosen masked sample, max 20 per run, 60 s | about 5-15 KB per call | keep phase names and `Result:`/`Action:` lines; addresses masked |
| R3 | FortiGate default-proof fallback if the defaults table cannot be cited | `show full-configuration firewall policy` (and `firewall address`), inside `config vdom` / `edit <vdom>` / `end` on multi-VDOM units | the existing FortiGate shell (`fgt_context_moves`), once per configuration run, 600 s | UNKNOWN; full form prints every setting per policy (estimate 50-150 lines per policy) | same withheld-secret filter as `fgt_show_configuration` |

A multiple-context ASA would need `changeto context <name>` + `more system:running-config` per context (A0-ASA-19);
none exists in the estate, so it is not proposed.

## 7. Measurement plan (anonymized, counts only)

Purpose: learn the real shapes the parsers must handle and size the UNKNOWN parts, **before** the parser contract is
frozen (measurement-record-before-contract), without anyone reading configuration text.

### 7.1 Method

- Harness `scripts/measurement/asa_fgt_policy_shape/` modelled on `scripts/measurement/log_masking_risk/`: a Java class
  compiled against the pod's own `service.jar`, run inside `ui2-service` with `PropertiesLauncher`, using the pod's
  artefact-store keys to decrypt the latest `show_configuration` artefact of each ASA/FortiGate gateway (the
  `LocalFirewallPolicyService` query with `vendor_hint in ('cisco_asa','fortinet')`).
- The text is tokenised in memory and discarded. Output is a tab-separated file whose every field must match
  `[a-z0-9_.]+`: vendor-keyword counters, enum values, integers. Device ids are replaced by an ordinal per run
  (`asa_01` ...); VDOM, ACL, object, interface names never leave the JVM. `System.out`/`err` are silenced; the pod-side
  `show_safe.sh` filter drops any line with another character before the file is read.
- Vocabulary for keyword counters is a closed list of vendor keywords (the ASA command words and FortiOS `set` keys in
  section 7.2); an unknown keyword is counted as `other` with its length only, never echoed.
- No device contact, no new command, no database write. Run once by the engineer with the PO session's go; outside
  ship/e2e windows (memory rule: `/app/tmp` is lost on a rollout).
- Result is committed as a MEASUREMENT RECORD (`ASA_FGT_POLICY_SHAPE_MEASUREMENT_<date>.md`), the same form as
  `ASA_FAILOVER_MEASUREMENT_2026_11_13.md`.

### 7.2 Counters

Cisco ASA, per device ordinal:
- `access_list_names`, `aces_total`, `aces_extended`, `aces_standard`, `aces_ethertype`, `aces_webtype`, `remarks`,
  `aces_inactive`, `aces_time_range`, `aces_user`, `aces_security_group`, `aces_object_group_ref`, `aces_inline_host`,
  `aces_inline_mask`, `aces_any`/`any4`/`any6`, `aces_ipv6_literal`, `aces_port_name` (count) and the distinct port-name
  keywords used (Cisco port-name vocabulary only), `aces_neq`/`lt`/`gt`/`range`, `aces_protocol_ip`/`tcp`/`udp`/
  `icmp`/`other`.
- `access_group_in`, `access_group_out`, `access_group_global` (0/1), `access_group_control_plane`,
  `acl_bound_count`, `acl_unbound_count`, `acl_bound_multi` (bound to more than one interface).
- `object_network_host/subnet/range/fqdn/nat_only`, `object_service`, `object_group_network/service/protocol/
  icmp_type/user/security`, `group_object_nesting_max`, `group_cycles`, `port_object_lines`, `service_object_lines`,
  `names_enabled` (0/1), `name_lines`, `non_contiguous_masks`.
- `nat_section1`, `nat_section2`, `nat_section3`, `nat_static`, `nat_dynamic`, `nat_twice`, `nat_identity`
  (`no-proxy-arp` / identical real-mapped), `nat_interface_pat`.
- `interfaces_nameif`, `security_levels_distinct`, `security_level_ties` (interfaces sharing a level),
  `same_security_inter` (0/1), `same_security_intra` (0/1), `zone_lines` (ASA traffic zones).
- Parser health: `lines_total`, `lines_policy_relevant`, `lines_unparsed` (by first keyword class), `ha_pair_hash_equal`
  (per pair: MATCH / MISMATCH of the policy-relevant canonical hash).

FortiGate, per device ordinal and VDOM ordinal:
- `policies`, `policy_order_vs_id` (`ascending` / `not_ascending`: does the printed order equal ascending `policyid`?
  answers whether A0-FG-1's sequence differs from ID order in practice), `action_absent`, `action_accept`,
  `action_deny`, `action_ipsec`, `status_disable`, `srcaddr_negate`, `dstaddr_negate`, `service_negate`,
  `srcintf_any`, `dstintf_any`, `intf_multi`, `schedule_not_always`, `groups_or_users`, `internet_service`,
  `application_lists`, `nat_enable`, `ippool_enable`, `match_vip_set`, `vip_in_dstaddr`, `uuid_present`,
  `addr6_cells`.
- `addresses_by_type` (ipmask/iprange/fqdn/geography/wildcard/dynamic/interface_subnet/mac/route_tag/absent_type),
  `ipmask_without_subnet`, `addrgrp`, `addrgrp_nesting_max`, `address6`, `services_custom_by_protocol`,
  `portrange_forms` (`dst`, `dst_range`, `dst_src`, multiple), `service_groups`, `services_referenced_undefined`,
  `vips`, `vip_portforward`, `vipgrp`, `ippools`, `central_snat_rules`, `zones`, `zone_intrazone_allow`.
- Mode facts per VDOM: `central_nat`, `ngfw_mode`, `inspection_mode`, `opmode` (enum values only).
- Other policy tables present (0/1): `local_in_policy`, `proxy_policy`, `security_policy`, `multicast_policy`,
  `dos_policy`, `policy6_legacy`.
- Parser health as for ASA.

### 7.3 What the counters decide

| Counter | Decision it informs |
|---|---|
| `aces_port_name`, distinct port names | whether the Cisco port-name table must be cited before step 2 (A0-ASA-15) |
| `port_object_lines` | whether `port-object` parsing is needed (A0-ASA-14) |
| `access_group_global`, `acl_bound_multi`, `security_level_ties`, `same_security_*` | how often A0-ASA-3/4/5 paths occur; ASSUMPTION exposure of A0-ASA-4c |
| `aces_time_range`/`user`/`security_group`, `object_network_fqdn` | expected UNKNOWN share for ASA |
| `policy_order_vs_id` | whether printed order may be ID-sorted (if `ascending` everywhere, the order question stays open: MEDIUM) |
| `action_absent`, `ipmask_without_subnet`, `services_referenced_undefined` | size of the FortiOS defaults dependency (A0-FG-5/6/24/27); whether R3 is worth asking for |
| `vip_in_dstaddr`, `match_vip_set` | exposure to A0-FG-18/19/20 UNKNOWNs |
| `ha_pair_hash_equal` | pair de-duplication rule (5.1) |

## 8. Delivery plan (small steps)

Each step is one PR, tests first, Java tests (`lanetest.sh`) and architecture tests before ship. Fixtures are
synthetic (`192.0.2.x`, masked names), never real configuration.

| Step | What | Depends on | Visible result |
|---|---|---|---|
| 0 | Measurement harness + one in-pod run + MEASUREMENT RECORD (section 7) | PO go for the run (Q1) | counts in the record |
| 1 | `AsaConfigPolicyParser` (pure, in `platform-core/.../policy/asa/`): ACLs, access groups, objects/groups, names, NAT sections, interfaces/security levels, same-security flags; unparsed-line counters | step 0 record | unit tests only |
| 2 | `AsaPolicyMapper` -> `PolicySnapshot` (section 5.2); `LocalFirewallPolicyService` generalised to a vendor-keyed source list (`palo_alto` effective_running, `cisco_asa`/`fortinet` show_configuration) | 1 | none yet (service only) |
| 3 | `FortiGateConfigPolicyParser` + versioned defaults table (FortiOS 7.4, each entry cited) + `FortiGatePolicyMapper` (section 5.3) | step 0 record, A0 amendment (Q2) | none yet |
| 4 | Policy screens list ASA/FortiGate snapshots (masked for aiview); rule search; rule counts and UNKNOWN reasons (Fable UI lane) | 2, 3 | ASA/FortiGate rules visible and searchable |
| 5 | `PolicyNormalization`: vendors `ASA`/`FGT`, actions, extras allowlist, placements; resolver additions (ASA masks, `neq/lt/gt`, `tcp-udp`, FortiOS port ranges, any-protocol) | 2, 3 | none (engine) |
| 6 | `PolicyLookup`/`EvaluationScope`: ASA interface chain + security-level default; FortiGate single rulebase + implicit deny + zones; VIP parallel evaluation | 5 | policy lookup answers for ASA/FortiGate |
| 7 | `PolicyHygiene`/`PolicyRuleMetrics`: per-ACL / per-VDOM shadowing and redundancy; `ACL_NOT_BOUND`; interface-placement generalisation; pair de-duplication | 5 | hygiene findings for ASA/FortiGate |
| 8 | Trace: `HopPolicyEvaluator`/`HopNatEvaluator` ASA and FortiGate orders; `PathPolicyBinder`/`PathEngine`/`TopologyBuilder` vendor sets; ASA `nameif`/FortiGate interface binding from inventory | 6 | Trace hops through ASA/FortiGate |
| 9 | Zone matrix (when its contract is frozen) and the ACI pre-cutover analysis consume the new snapshots (interface/zone axes) | 6, 7, zone-matrix contract | ACI capability 1 evaluates ASA/FortiGate targets |

Real-environment validation (separate gate, after step 6): 20 masked lookups per vendor chosen by the PO, compared
with the device's own answer (R2 `packet-tracer` on ASA; a FortiGate equivalent to be researched and proposed then),
recorded like path analysis A6. Without it the build stays AUTOMATED_VALIDATED.

Sizing (engineer estimate, not measured): steps 1-3 about 6-8 engineer-days, 4 about 2, 5-7 about 5, 8 about 3-4,
9 depends on the zone-matrix contract. Risk: medium (no device contact; the semantic UNKNOWNs are explicit).

## 9. Risks

- **FortiOS defaults.** A wrong default silently changes verdicts. Mitigation: cited table per minor, UNKNOWN when
  absent, measurement of exposure, R3 as fallback.
- **ASA security-level default (A0-ASA-4b/4c).** LOW/UNKNOWN parts are load-bearing for interfaces without inbound
  ACLs; verdicts that depend on them carry an UNKNOWN reason until `packet-tracer` corroboration.
- **VIP semantics (A0-FG-18/19).** Parallel evaluation can return UNKNOWN for many VIP-published services; the
  measurement sizes it.
- **Quote verification.** The amendment's quotes came through a summarising reader; they are re-read on the raw page
  before freeze.
- **Privacy.** Rules carry addresses and object names, like CP/PAN snapshots: same masked aiview projection; the
  measurement emits counts only.
- **Pod memory.** Largest artefacts are about 2.8 MB of text; parsing is linear. The cache follows the existing
  weight-bounded `LocalFirewallPolicyService` cache.

## 10. Questions for the PO

| # | Question | Recommendation |
|---|---|---|
| Q1 | Approve using the output of the two existing reads (ASA `more system:running-config`, FortiGate `show`) for the policy model, as a parse-scope extension with no new command, and approve the step-0 in-pod measurement (counts only)? | Yes |
| Q2 | Approve the A0 amendment draft for implementation once its HIGH quotes are re-checked on the raw pages, with its UNKNOWNs kept UNKNOWN? | Yes, after the quote re-check |
| Q3 | ASA interfaces without an inbound ACL: show the security-level default as ALLOWED/DENIED (A0-ASA-4a HIGH for high-to-low) and keep A0-ASA-4b/4c parts UNKNOWN until `packet-tracer` evidence? | Yes |
| Q4 | Policy screens will show ASA ACL and FortiGate policy content (masked for aiview), which the 2026-09-27 configuration allowlist kept out of the Configuration screen. Agree that the Policy area shows it, like CP/PAN rules? | Yes |
| Q5 | HA pairs: count a pair once in hygiene and the zone matrix when both members' policy hashes match, and flag `HA_POLICY_DIVERGENT` otherwise? | Yes |
| Q6 | Hit counts (R1) and device corroboration (R2/R3): leave for later, decide after the step-0 measurement? | Later |
| Q7 | Order: ASA first (5 of the 6 HA pairs in this scope, and the larger configurations), then FortiGate? Or FortiGate first if the ACI phase-2 targets are FortiGates? | ASA first unless the ACI targets say otherwise |
| Q8 | Which ASA/FortiGate units are ACI redirect targets in phases 2/3 (masked names)? This decides which units the real-environment validation samples come from. | PO / network team input |
