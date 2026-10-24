# PAN-OS 12 command alternatives for four firewall reads

**Status: DRAFT — for Product Owner approval. Update 2026-10-21 (same day): the PO approved GR-1 (§3.1, `type=version`, "Tüm PAN-OS sürümleri") and the HA-state source for failover check 7 (§3.2, "Evet, HA durumundan al"); both are implemented and recorded in `PO_DECISION_RECORD_2026_10_21_PAN_TYPE_VERSION_AND_CHECK7.md`. Update 2026-10-24: the PO approved GR-7 (`show session meter` for failover check 6 on PAN-OS 12+) and GR-4 (configured interfaces from effective-running on PAN-OS 12+), switch value `replace_12`; implemented and recorded in `PO_DECISION_RECORD_2026_10_24_PAN_OS12_REPLACEMENT_READS.md` (V179, `FAILOVER_EXECUTION_PAN_CONTRACT.md` §10). Confirmed on that date: neXus sends neither `show counter interface all` nor the per-interface `show interface ha1-a` / `ha1-b` / `management` reads (no gate row, no code path), and HA link state comes from `show high-availability state` (`peer-info/conn-ha1`, `conn-ha1-backup`, `conn-ha2`, `conn-ha2-backup`), so nothing replaces them. Everything else below remains DRAFT.** This document is research and
design only. It approves no command, no gate row, no parser and no contract
change. Every proposed command below is a *candidate*; nothing here may be
cited as approval (AGENTS.md, Authority hierarchy item 2; Contract-status
law). No device, HOST-A or database was touched to write it.

Date: 2026-10-21. Branch `docs/pan-os12-alternatives` (from `origin/main`
`87efd9b7`).

## 0. The request and the short answer

Product Owner, 2026-10-21: four Palo Alto firewall XML API reads are said to be
defective on PAN-OS 12 and later — `<show><system><info/></system></show>`,
`<show><interface>all</interface></show>` (and per-interface `ha1-a`, `ha1-b`,
`management`), `<show><session><info/></session></show>`,
`<show><counter><interface>all</interface></counter></show>` — "Panorama'dan
zaten çekiyoruz; cihazın kendi API'leri kullanılabilir mi?"

Short answer:

1. **The defect is UNKNOWN in public vendor sources.** No PAN-OS 12.1 or 12.2
   known or addressed issue names any of the four commands (§1). The closest
   official matches are SNMP-path defects on interface status and counters in
   12.1 — including one on the HA1-A interface — and a class of 12.1.x XML API
   operational-command failures, fixed in 12.1.5–12.1.8, that name other
   commands. The PO's source for the claim is needed (Q1).
2. **neXus issues only two of the four**, plus `show session info` on the
   failover path. `show counter interface all` and the per-interface
   `ha1-a` / `ha1-b` / `management` reads are **not issued anywhere** on
   `origin/main` (§2.5) — there is nothing to replace.
3. **Identity has a clean direct-device alternative:** the documented XML API
   request `type=version` (serial, model, PAN-OS version, multi-vsys) (§3.1).
4. **Version parity and content versions for HA members** can come from the
   already-approved, already-issued `show high-availability state` read
   (`local-info` / `peer-info` version fields) — a parse-scope extension, no
   new command, but a FROZEN failover contract amendment (§3.2).
5. **Uptime, family and content versions for display** can come from Panorama
   `show devices all`, labelled as Panorama-reported (management-plane grade,
   never an identity gate or a failover check); firewalls not managed by, or
   not connected to, the Panorama stay UNKNOWN (§3.3).
6. **Interfaces:** the configured part (names, static addresses, zones, vsys,
   virtual router, HA interface ports) is already in `effective-running`
   (§3.4). **Runtime link state has no documented, defect-free alternative**;
   it stays UNKNOWN except for HA-monitored links (from HA state), unless a
   measurement shows the defect is limited to the `all` form (§3.5).
7. **Session count for failover check 6 has no documented equivalent.** The
   candidates (`show session meter`, `show running resource-monitor`) measure
   different things. Without a working `show session info`, check 6 becomes
   UNKNOWN, which blocks readiness under the frozen contract — a PO decision
   (§3.6, Q6).
8. **Measure first.** One masked shape measurement of the existing three reads
   on one PAN-OS 12.x firewall would say whether, and how, they fail. Several
   alternatives may then be unnecessary (§5, M0).

## 1. Defect evidence

### 1.1 What was searched

| Source | Coverage | Result for the four commands |
| --- | --- | --- |
| PAN-OS 12.1 Release Notes, full PDF (`https://docs.paloaltonetworks.com/content/dam/techdocs/en_US/pdf/ngfw/ngfw-release-notes-12-1.pdf`; HTML root `https://docs.paloaltonetworks.com/ngfw/release-notes/12-1`) | Known and Addressed Issues 12.1.2 through 12.1.10-h2, Changes to Default Behavior | 0 hits for `show system info`, `show interface all`, `show session info`, `show counter`, `show high-availability` |
| PAN-OS 12.2 Release Notes, full PDF (`https://docs.paloaltonetworks.com/content/dam/techdocs/en_US/pdf/ngfw/ngfw-release-notes-12-2.pdf`) | all published 12.2 issue lists | 0 hits |
| PAN-OS 11.2 Release Notes, full PDF (`https://docs.paloaltonetworks.com/content/dam/techdocs/en_US/pdf/pan-os/11-2/pan-os-release-notes/pan-os-release-notes.pdf`) | baseline comparison | one `show interface all` known issue (stale routes visible for an inherited interface, workaround: reconfigure the interface) — 11.2, not 12, and not a failure of the read |
| CLI changes, 12.1 / 12.2 (`https://docs.paloaltonetworks.com/ngfw/pan-os-cli-quick-start/cli-changes/deleted-show-commands-12-1`, `.../deleted-show-commands-12-2`, `.../new-show-commands-12-1`) | removed/added commands | Configuration-mode `show` paths only (`show network …`, `show deviceconfig …`, `show template …`); **no operational command is listed as removed** |
| Web search: LIVEcommunity, Reddit, KB, GitHub (queries: "PAN-OS 12.1 show system info XML API bug", "PAN-OS 12.1 show interface all API slow/error/timeout", "12.1 show session info / show counter interface API", "PAN-OS 12.1 upgrade api show system info broken") | non-official | no report found |

### 1.2 Closest official matches (none names the four commands)

Interface status and counters reported **through SNMP** (not the XML API),
PAN-OS 12.1:

| ID | Symptom (paraphrased) | Fixed in |
| --- | --- | --- |
| PAN-300555 | HA configurations: the HA1-A interface reported an incorrect SNMP down value while physically up on the active firewall | 12.1.5 |
| PAN-310402 | SNMP returned down for HSCI and logging interfaces while up; their counters were all zero | 12.1.5 |
| PAN-302387 | PA-7500: SNMP reported HSCI interfaces down; counters all zero | 12.1.5 |
| PAN-312157 | during a commit the firewall intermittently stopped sending SNMP messages, so interface counters stopped updating briefly | 12.1.10 (also listed in 12.2) |
| PAN-317177 | DHCP-client mode: SNMP process restarted after a commit, giving false interface-flap notifications | 12.1.7 |

The PO's mention of `ha1-a` and interface counters matches PAN-300555 and
PAN-312157 closely. **If the observed defect came from an SNMP-based monitor,
it is not a neXus XML API defect** (Q1).

XML API operational-command failures in 12.1 that name **other** commands:

| ID | Symptom (paraphrased) | Fixed in |
| --- | --- | --- |
| PAN-303737, PAN-300906 | XML API commands failed with "Method not found (policy_xml)" in `dagger.log` (session-distribution and inline-cloud-proxy commands) | 12.1.5 |
| PAN-311412 | `show advanced-routing resource` failed through the XML API with an error | 12.1.6 |
| PAN-320420 | `show running resource-monitor ingress-backlogs` API call returned an unexpected error | 12.1.8 |

So the *class* of defect is real on early 12.1.x builds: operational commands
failing only when run through the XML API. None of the listed IDs is one of the
four commands.

Changes to Default Behavior, 12.1: the only operational output change found is
`show system resources` (process names truncated to 8 characters after a
12.1.2 kernel upgrade). It is not one of the four commands.

### 1.3 Verdict

**Defect identity: UNKNOWN.** No PAN-ID, affected-version range, symptom or
fixed-in version can be stated for any of the four commands from official
sources. The design below therefore treats "the read fails or returns
unusable output on PAN-OS ≥ 12" as a hypothesis to be confirmed by
measurement M0 (§5). It does not assume a failure mode (error envelope,
timeout, missing field, wrong value, or device load).

## 2. Current use in neXus (`origin/main` `87efd9b7`)

All four PAN firewall reads are XML API `type=op` POSTs with the key in the
`X-PAN-KEY` header (`PanXmlApiTransport`). One gate row is resolved by
canonical command key, so a single row covers every job that sends the same
literal.

### 2.1 `<show><system><info/></system></show>` — gate `pan_inventory_show_system_info` (firewall scope)

| Job / site | Fields read | Feeds |
| --- | --- | --- |
| Enrolment confirm — `DeviceFirstContactCommandSet.PAN_IDENTITY_READ` (gate document `DEVICE_FIRST_CONTACT_COMMAND_GATE_ENTRIES.md` entry 3) → `PaloAltoConfirmReadParser` | `serial` | presented identity → recorded identity baseline (`recordedIdentityPrimary`); self-reference for HA peer corroboration |
| | `hostname`, `model`, `sw-version` | observed facts on the device record |
| Inventory — `InventoryReadPlan.PALO_ALTO_BASE_STEPS[0]`, `InventoryCapabilityExecutor` (~line 474) → `PaloAltoSystemInfoParser` | `serial` | identity gate (`IdentityMismatchEvaluator`: warn-and-continue by default, refuse under strict posture). **An empty serial or an error envelope fails the whole inventory run** (`ConnectFailed`) |
| | `family`, `app-version`, `threat-version`, `av-version`, `wildfire-version`, `url-filtering-version`, `uptime` | `PlatformFactsRead.paloAlto` → device platform facts (Inventory screen, global search) |
| | `hostname`, `model`, `sw-version` | `ObservedIdentity` on the device record |
| Configuration — `ConfigurationReadPlan.PAN_SHOW_SYSTEM_INFO`, `ConfigurationCapabilityExecutor` (~line 368) | `serial` | identity gate (an empty serial warns and continues under the default posture); serial passed to the Panorama name-override cross-check |
| Failover and readiness — `PanFailoverJobExecutor` `SYSTEM`, every pre-, post- and post-return check | `system/sw-version`, `app-version`, `threat-version` on both peers | **check 7 "Version parity"** (`PanFailoverChecks.versions`): any field missing → UNKNOWN, which blocks |

Not affected by this document: the Panorama-scope row
`pan_panorama_show_system_info` (the PO's report is about firewalls). Failover
**identity** already comes from `show high-availability state`
`local-info/serial-num`, not from system info.

### 2.2 `<show><interface>all</interface></show>` — gate `pan_inventory_show_interface_all`

Inventory only (`InventoryCapabilityExecutor` ~line 495, `PaloAltoInterfaceParser`):

| Element | Feeds |
| --- | --- |
| `ifnet/entry`: `name`, `vsys`, `ip`, `addr/entry@name`, `addr6/entry@name`, `tag`, `fwd` (virtual router), `zone`, `state` | logical interfaces per vsys context; addresses; VLAN id; virtual-router → vsys mapping, which also places routes under a vsys; zone; the "virtual systems" display string |
| `hw/entry`: `name`, `state`, `mac` | physical ports and link state (`physical` context); a logical interface without its own state inherits its parent port's state (PO 2026-09-21); MAC (V148) |
| empty interfaces **and** empty routes | the run fails (`no_interfaces_or_routes_discovered`) |

### 2.3 `<show><session><info/></session></show>` — gate `pan_failover_session_info`

Failover and readiness only. `num-active` (or legacy `active-sessions`) on
both peers feeds **check 6 "Sessions carried"**: pre-check records the count;
post-check requires the new active's count to be at least 80 % of the former
active's (`FAILOVER_EXECUTION_PAN_CONTRACT.md` §6, FROZEN). No throughput,
packet-rate or connection-rate field is parsed on the PAN path today.

### 2.4 Already-issued reads relevant to the alternatives

| Read | Gate | Where issued | Fields already present (measured 2026-09-14, `PAN_INVENTORY_MEASUREMENT_FINDINGS_2026_09_14.md` §5) |
| --- | --- | --- | --- |
| `<show><high-availability><state/></high-availability></show>` | `pan_inventory_show_ha_state` | confirm, inventory, failover (every phase, both peers) | `local-info`: `serial-num`, `platform-model`, `build-rel`, content versions and compatibility flags; `peer-info`: the same family; `conn-ha1`/`conn-ha1-backup`/`conn-ha2`; `link-monitoring/groups/entry/interface/entry` name and status |
| `<show><config><effective-running/></config></show>` | `pan_configuration_effective_running` | configuration job | full effective configuration, including template-pushed values |
| Panorama `<show><devices><all></all></devices></show>` | `PAN_DISCOVERY_API_ROUTE_GATE_ENTRIES.md` (closed two-route set in `PanoramaApiRoutes`) | discovery runs only | measured presence on every entry (`PAN_DISCOVERY_MEASUREMENT_FINDINGS_2026_09_13.md` F-12): serial, hostname, model, family, software version, uptime, multi-vsys flag, connection state and timestamp, about a dozen content-version elements. Parsed today: only the discovery roles in `PanoramaApiFieldBinding` |

### 2.5 Commands named by the PO that neXus does not issue

Searched `ui2/**`, `scripts/**`, `history/**` (Java, YAML, SQL, Python),
`gate_registry_fixture.yaml`, every migration, and the git history of
`origin/main` (`git log -S`):

- `<show><counter><interface>all</interface></counter></show>` — **no source,
  no gate row, never issued.**
- `<show><interface>ha1-a</interface></show>`, `ha1-b`, `management` — **no
  source, no gate row, never issued.** HA link state on the failover path comes
  from `show high-availability state` (`conn-ha1`, `conn-ha1-backup`,
  `conn-ha2`), which the PO did not report as defective.

Nothing needs replacing for these. If the PO saw them fail, they were issued
by another tool (Q1).

## 3. Alternatives, field by field

Evidence grades follow the AGENTS.md evidence laws: **D** = direct,
identity-verifiable firewall read; **M** = management-plane (Panorama)
observation; **C** = configuration intent (not runtime truth); **U** = UNKNOWN.

### 3.1 Identity and version: `type=version` (direct firewall, documented)

Official: "type=version — Show the PAN-OS version, serial number, and model
number" (`https://docs.paloaltonetworks.com/ngfw/api/pan-os-xml-api-request-types-and-actions`,
current NGFW API guide). The documented response
(`https://docs.paloaltonetworks.com/content/techdocs/en_US/pan-os/10-0/pan-os-panorama-api/pan-os-xml-api-request-types/get-version-info-api.html`,
an end-of-life version page; no current-version sample page was found) is
`response/result/{sw-version, multi-vsys, model, serial}`.

- It is a separate XML API request type, not an operational command, so it
  is not handled the way `type=op` commands are. Whether it shares the
  unidentified 12.x defect is **UNKNOWN** until M1.
- It is a strict subset of the identity part of system info. It does not
  return `hostname`, `family`, `uptime` or content versions.
- Representation: the serial must be compared exactly as received after the
  same whitespace strip already applied by the current parser (identity law).
  That the two sources give byte-identical serials is **UNKNOWN** until M1
  compares them locally (MATCH/MISMATCH only).

**Recommendation:** replace system info with `type=version` at the identity
step of confirm, inventory and configuration — for all PAN-OS versions, so
there is one code path and no branch decided by the very read being replaced.
Grade D. New gate row GR-1.

### 3.2 Content versions and version parity: `show high-availability state` (direct, already approved)

For HA members, each firewall reports its own `local-info` versions
(`build-rel`, content versions, compatibility flags — presence measured
2026-09-14). The failover executor already reads HA state on both peers in
every phase, and already verifies identity from `local-info/serial-num`.

- **Failover check 7:** compare each peer's own `local-info` PAN-OS and
  app/threat versions, read on that peer. This keeps "each peer's own facts"
  (the readiness draft PAN-05 wording) and the D grade. The vendor's own
  `*-compat` flags are a candidate additional signal; their vocabulary is
  **UNKNOWN** (no official field reference found; field presence is not
  semantic proof).
- That `build-rel` is the same value as system info's `sw-version` is
  **UNKNOWN** until M2. Official documentation of the HA-state XML fields was
  not found. The vendor's HA overview states the peers should run the same
  PAN-OS and content versions (`https://docs.paloaltonetworks.com/pan-os/9-1/pan-os-admin/high-availability/ha-overview`).
- **Inventory platform facts** (app/threat/av versions) for HA members:
  from the same HA-state read inventory already issues. Standalone firewalls:
  HA state has no `local-info`, so this source does not exist for them.

This is a parse-scope extension (same command, session, timeout and
frequency), so no new gate row. It is still a **new purpose** for the read
(evidence law "command presence in source != command approval") and it amends
FROZEN `FAILOVER_EXECUTION_PAN_CONTRACT.md` §6 check 7. Both need PO approval
(GR-2, Q5).

### 3.3 Uptime, family and content versions for display: Panorama `show devices all` (management plane)

Official: "Query a Firewall from Panorama (API)"
(`https://docs.paloaltonetworks.com/content/techdocs/en_US/ngfw/api/pan-os-xml-api-use-cases/query-a-firewall-from-panorama-api.html`)
documents `<show><devices><all></all></devices></show>` and
`<show><devices><connected></connected></devices></show>`, with entry
elements `serial`, `connected`, `hostname`, `ip-address`, `uptime`, `family`,
`model`, `sw-version`, `app-version`, `av-version`, `wildfire-version`,
`threat-version`, `url-filtering-version`, `multi-vsys`, `vsys/…` (sample
marked truncated, with no `ha` element shown). The estate measurement (F-12)
confirms presence on every entry.

Limits:

- **Grade M, never D.** "Management-plane observation != direct-device
  runtime truth." It may fill display fields under a "reported by Panorama"
  label. It must never be the identity gate, never feed a failover check (the
  readiness draft PAN-05 already says "only Panorama inventory" → UNKNOWN),
  and never be joined to a firewall by hostname. The join key is the serial,
  compared exactly.
- **Coverage gaps.** Firewalls not managed by any enrolled Panorama, or
  managed by a Panorama neXus does not enrol: no data → UNKNOWN. Entries with
  `connected` not affirmative (1 of 39 at the 2026-09-13 measurement): values
  are last-known, freshness **UNKNOWN** → shown as stale or UNKNOWN, never as
  current.
- **Semantics of `uptime`** (firewall system uptime, or something
  Panorama-side) is not defined in the documentation: **UNKNOWN** until M3
  compares it, as a bucket, against system info on a firewall where system
  info works.
- The same page documents `&target=<serial>` to proxy an op command through
  Panorama. That runs the **same** command on the firewall, so it is not an
  alternative if the defect is on the firewall. Not proposed.

The discovery route set is closed to two requests in its own capability, so
using this read in Panorama inventory needs its own Panorama-scope gate row
(GR-3). The discovery row is unchanged.

### 3.4 Interfaces, configured part: `effective-running` (already collected)

Already in the configuration job's `effective-running` (and already projected
in part by `ConfigurationProjection`'s "Interfaces" and "High Availability"
sections):

| Field | Config path (PAN-OS configuration schema) |
| --- | --- |
| interface names, sub-interfaces, VLAN tag | `devices/entry/network/interface/{ethernet,aggregate-ethernet,vlan,loopback,tunnel}/entry[/layer3/units/entry]`, `tag` |
| static IPv4/IPv6 addresses | `…/layer3/ip/entry@name`, `…/layer3/ipv6/address/entry@name` |
| vsys membership | `vsys/entry/import/network/interface/member` |
| zone | `vsys/entry/zone/entry/network/{layer3,layer2,virtual-wire,tap}/member` |
| virtual router / logical router | `network/virtual-router/entry/interface/member` (or `network/logical-router/…` under advanced routing) |
| HA interface ports (HA1, HA1 backup, HA2) | `deviceconfig/high-availability/interface/{ha1,ha1-backup,ha2,ha2-backup}/port` |

Limits: grade C — "configuration intent != runtime truth". DHCP or PPPoE
addresses, link state and MAC are not in configuration → UNKNOWN. Inventory
and Configuration are separate product planes, so these values must be
presented as **configured** interfaces (Configuration plane, or an Inventory
row labelled "configured, not observed"), never as observed runtime. This is
a parse-scope extension of an approved read with a new purpose (GR-4, Q8).
The exact element paths above are standard schema paths. Their presence in
this estate's effective-running is **UNVERIFIED** until checked against the
2026-09-14 configuration measurement (`PAN_CONFIGURATION_MEASUREMENT_FINDINGS_2026_09_14.md`)
or M3b.

### 3.5 Interfaces, runtime link state: no documented safe alternative

| Candidate | Evidence | Assessment |
| --- | --- | --- |
| HA-state `link-monitoring/groups/entry/interface/entry` (name, status) | measured 2026-09-14; already issued | **Recommended for HA members**, grade D, only for interfaces the PO configured as monitored (8 in the measured pair); no new command (GR-2 purpose) |
| HA-state `conn-ha1` / `conn-ha1-backup` / `conn-ha2` | already parsed for failover check 3 | covers the HA links the PO named (`ha1-a`, `ha1-b`) as link-connection status; already in use |
| `<show><interface>{name}</interface></show>`, one call per interface | the CLI accepts a name (the 11.2 notes cite `show interface management`); no XML field reference found | same command family as the suspected defect → **UNKNOWN whether affected**; N calls per run (frequency and device-load concern). Only if M0 shows `all` fails and M4 shows the named form works (GR-5) |
| `<show><interface>hardware</interface></show>` (physical ports only) | not found in official docs | literal and output **UNVERIFIED**; same family → measure (M4) before considering (GR-6) |
| `show system state` filters | undocumented internal tree | **rejected** — no vendor contract |
| PAN-OS REST API | the REST API manages objects and policies; operational commands remain in the XML API (`https://docs.paloaltonetworks.com/ngfw/api/get-started-with-the-pan-os-rest-api/access-the-rest-api`, `https://docs.paloaltonetworks.com/pan-os/10-1/pan-os-panorama-api/pan-os-xml-api-request-types/run-operational-mode-commands-api`) | **no runtime interface data** — rejected |
| SNMP IF-MIB | new transport and credential boundary (diagnostic-path law); 12.1 has several SNMP interface-status and counter defects (§1.2) | **rejected** |
| Panorama | `show devices` carries no per-interface runtime state | none |

**Without M0/M4 evidence, runtime link state, MAC and dynamic addresses on
PAN-OS ≥ 12 standalone firewalls are UNKNOWN.**

### 3.6 Session count for failover check 6

| Candidate | Official evidence | What it measures | Assessment |
| --- | --- | --- | --- |
| keep `<show><session><info/></session></show>` | "show session info … shows details about the sessions" (`https://docs.paloaltonetworks.com/ngfw/pan-os-cli-quick-start/use-the-cli/view-settings-and-statistics`) | active sessions | preferred if M0 shows it works on 12.x |
| `<show><session><meter></meter></session></show>` (XML literal **UNVERIFIED**) | vsys cheat sheet: per vsys, Maximum (per dataplane), Current, Throttled; the CLI needs `set system setting target-vsys` first; Current can exceed Maximum on multi-dataplane models (`https://docs.paloaltonetworks.com/ngfw/pan-os-cli-quick-start/cli-cheat-sheet-vsys`) | per-vsys current sessions where vsys session limits apply | that the sum over vsys equals the device's active-session count is **UNKNOWN**; behaviour on a single-vsys firewall is **UNKNOWN** (GR-7, M5) |
| `<show><running><resource-monitor><second><last>5</last></second></resource-monitor></running></show>` (XML literal **UNVERIFIED**) | KB "How to Interpret: show running resource-monitor" (`https://knowledgebase.paloaltonetworks.com/KCSArticleDetail?id=kA10g000000ClXwCAK`): session utilization % per dataplane | session-table **utilization percentage**, not a count | would turn the frozen 80 %-of-count rule into a percentage rule — a semantic change. A sibling sub-command had an XML API defect up to 12.1.8 (PAN-320420) (GR-8, M5) |
| HA `state-synchronization` counters (check 5, already read) | measured 2026-10-03 | sync message counters | proves sync activity, **not** session takeover. Not a substitute |
| Panorama | — | no session count in `show devices` | none |
| drop to UNKNOWN | — | — | under the frozen contract UNKNOWN blocks, so readiness on PAN-OS ≥ 12 is never READY → failover blocked there. A PO policy decision (Q6) |

### 3.7 Per-field summary

| Field (consumer) | Today | Proposed on PAN-OS ≥ 12 | Grade | Command change |
| --- | --- | --- | --- | --- |
| serial — identity gate (confirm, inventory, configuration) | system info | `type=version` `serial` | D | GR-1 new |
| model, PAN-OS version (device record) | system info | `type=version` `model`, `sw-version` | D | GR-1 |
| multi-vsys | not used | `type=version` `multi-vsys` (optional) | D | GR-1 |
| hostname (presentation only, never identity) | system info | configured hostname `deviceconfig/system/hostname` (C), else Panorama `hostname` (M), else UNKNOWN | C/M | GR-4 / GR-3 |
| family | system info | Panorama `family`, else UNKNOWN | M | GR-3 |
| uptime | system info | Panorama `uptime` (semantics UNKNOWN until M3), else UNKNOWN | M/U | GR-3 |
| content versions — inventory display | system info | HA-state `local-info` (HA members, D); else Panorama (M); else UNKNOWN | D/M | GR-2 / GR-3 |
| version parity — failover check 7 | system info, both peers | HA-state `local-info` on each peer (D); Panorama never | D | GR-2 + contract amendment |
| interface names, static addresses, zones, vsys, virtual router, HA ports | interface all | effective-running, labelled configured | C | GR-4 |
| interface runtime link state, MAC, dynamic addresses | interface all | HA link-monitoring for monitored interfaces (D); otherwise UNKNOWN unless M4 permits GR-5/GR-6 | D/U | GR-2 (GR-5/6 conditional) |
| HA link state (HA1 / HA1 backup / HA2) | HA state `conn-*` | unchanged | D | none |
| active sessions — failover check 6 | session info | session info if M0 passes; else GR-7/GR-8 after M5 and a contract amendment; else UNKNOWN (blocks) | D/U | conditional |
| interface counters | not collected | not collected | — | none |

Inventory run-failure rule: if GR-1 is adopted, an unusable system-info read
on 12.x no longer fails the whole inventory run. If interface all fails on
12.x and GR-4 is used, the "no interfaces or routes" failure must not fire
just because runtime interfaces are UNKNOWN — implementation detail, out of
scope here.

## 4. Command gate table (candidates, none approved)

Ten gate items per `docs/AI_DEVELOPMENT_PROTOCOL.md` plus the C4 row fields.
All rows: vendor `palo_alto`, transport `PAN_XML_API` over HTTPS, key in the
`X-PAN-KEY` header (never the URL), shell context `not_applicable`, class
`CLASS_0_READ`, retry `none`. No row is class 1+ and none changes device
state.

| Gate id (proposed) | Scope | Exact command string (canonical key) | Why | Timeout | Max frequency | Session reuse | Unsupported behaviour | Secret-output risk | Safe telemetry | Relation to existing rows |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **GR-1** `pan_identity_version` | `pan_firewall` | `type=version` (form: `type=version`, no `cmd`) | identity and version at confirm, inventory, configuration | 30 s | once per device per run (confirm, inventory, configuration), at the step system info occupies today | the one key-generation key per run | error envelope, non-XML or missing `serial` → identity UNKNOWN; same consequence as today's empty serial (inventory refuses, configuration warns and continues, confirm records no baseline) | serial, model (sensitive identity; compared locally, masked to aiview) | serial `MATCH`/`MISMATCH`/`MISSING`; `sw-version` major.minor; `multi-vsys` on/off; model class | replaces `pan_inventory_show_system_info` at the identity step; `DEVICE_FIRST_CONTACT_COMMAND_GATE_ENTRIES.md` entry 3 needs an amendment (its closed literal set holds `cmd` strings, so `type=version` needs a representation there) |
| **GR-2** (no new row) purpose amendment of `pan_inventory_show_ha_state` | `pan_firewall` | `<show><high-availability><state/></high-availability></show>` (unchanged) | content versions and version parity from `local-info`/`peer-info`; link-monitoring interface status | unchanged (30 s) | unchanged | unchanged | HA disabled → no `local-info` → fields UNKNOWN (standalone) | none beyond today's (HA addresses stay unparsed) | version equality `MATCH`/`MISMATCH`/`MISSING`; monitored-link count up/down/other | parse-scope extension, no new gate entry (protocol); **amends FROZEN `FAILOVER_EXECUTION_PAN_CONTRACT.md` §6 check 7** |
| **GR-3** `pan_panorama_show_devices_all` | `panorama` | `<show><devices><all></all></devices></show>` (byte-identical to the discovery literal) | Panorama-reported display facts (uptime, family, content versions, hostname, connected) for the Panorama's managed firewalls | 60 s | once per Panorama inventory run | the Panorama inventory run's one key | non-XML/error → those facts UNKNOWN for every managed firewall; entries without an affirmative `connected` → stale/UNKNOWN | serials, hostnames, management addresses of every managed firewall (masked to aiview; raw response discarded) | entry count, connected count, per-field presence counts, join outcome per enrolled firewall (`MATCH`/`MISSING`) | new use of the read approved for discovery (`PAN_DISCOVERY_API_ROUTE_GATE_ENTRIES.md`); the discovery closed set and its row are unchanged |
| **GR-4** (no new row) purpose amendment of `pan_configuration_effective_running` | `pan_firewall` | `<show><config><effective-running/></config></show>` (unchanged) | configured interfaces, zones, vsys, virtual router, HA ports, hostname | unchanged | unchanged | unchanged | missing path → that field UNKNOWN | configured addresses and names (already handled by the configuration plane) | interface/zone/vsys counts | parse-scope extension; Configuration plane |
| **GR-5** `pan_inventory_show_interface_named` (conditional on M0 + M4) | `pan_firewall` | `<show><interface>{name}</interface></show>`, `{name}` = an interface name from the device's own effective-running, server-rendered, validated against `^[a-z0-9./-]{1,32}$`, never typed by a user | per-interface runtime state if `all` is the defective form | 30 s | once per configured physical port per inventory run, ceiling 64 per run; 2 s pause between calls (as the failover command pause) | the one key per run | error for a name → that interface UNKNOWN; no retry | addresses (masked to aiview) | state up/down/other per interface; counts | parameterised sibling of `pan_inventory_show_interface_all`; not proposed for approval before M4 |
| **GR-6** `pan_inventory_show_interface_hardware` (conditional on M4) | `pan_firewall` | `<show><interface>hardware</interface></show>` (literal **UNVERIFIED**) | physical port link state and MAC in one call | 30 s | once per device per run | the one key per run | error → physical state UNKNOWN | MAC (masked) | port count, up/down/other counts | sibling of `pan_inventory_show_interface_all` |
| **GR-7** `pan_failover_session_meter` (conditional on M5) | `pan_firewall` | `<show><session><meter></meter></session></show>` (literal **UNVERIFIED**) | check 6 alternative | 30 s | same as `pan_failover_session_info` | one key per peer per run | error, or no vsys entry → check 6 UNKNOWN | vsys names (masked) | per-vsys current-session sum, vsys count | alternative to `pan_failover_session_info`; needs a contract amendment |
| **GR-8** `pan_failover_resource_monitor` (conditional on M5) | `pan_firewall` | `<show><running><resource-monitor><second><last>5</last></second></resource-monitor></running></show>` (literal **UNVERIFIED**) | check 6 alternative (utilization %) | 30 s | same as `pan_failover_session_info` | one key per peer per run | error → check 6 UNKNOWN | none expected | session utilization % per dataplane (bounded integers) | alternative to `pan_failover_session_info`; changes check 6 semantics; needs a contract amendment |

Existing row consequence: if GR-1 is adopted, `pan_inventory_show_system_info`
is no longer issued at the identity step. If GR-2 is adopted for check 7, it is
no longer issued by failover. Whether it is kept for PAN-OS < 12 platform facts
(uptime, family) or retired depends on Q4 and Q7. The Panorama-scope system
info row is unchanged.

## 5. Measurement plan (one read-only measurement per command, before any parser)

Mechanism: a closed, CAP_OFFLINE measurement capability modelled on
`PanSyslogMeasure` / `PanNatHitMeasure`. Each command is resolved against its
own signed-off gate row before sending. The raw response stays in memory only.
The kept summary is presence booleans, counts, bounded enums, a capped sorted
list of element names (≤ 40, shape-checked `[a-z0-9-]{1,40}`), the response
`status` attribute, an error-code class, an HTTP status, and an elapsed-time
bucket (<1 s, 1–5 s, 5–30 s, timeout). No value, serial, name or address is
kept. The summary goes to the job transcript note under the aiview masked
projection. Target: one PAN-OS 12.x firewall chosen by the PO, named only by
its aiview pseudonym (plus one HA peer for M2). Run outside the
00/06/12/18 Europe/Istanbul job windows. One command per target per minute.
Every measurement command needs the PO's exact prior approval (PO Amendment
2026-09-26); approving one does not approve the next.

| # | Command(s) | Gate row it runs under | Confirms |
| --- | --- | --- | --- |
| M0 | `<show><system><info/></system></show>`, `<show><interface>all</interface></show>`, `<show><session><info/></session></show>` — once each | existing rows (as ad hoc diagnostics) | whether and how each fails on 12.x: HTTP/status/error class, elapsed bucket, element-name set, `serial`/`ifnet`/`hw`/`num-active` presence. **Decides whether §3 is needed at all** |
| M1 | `type=version` | GR-1 (measurement-only approval) | element-name set; `serial` local MATCH against the recorded baseline; `sw-version` major |
| M2 | `<show><high-availability><state/></high-availability></show>` on both peers of one 12.x pair | existing row; the existing readiness `ReadinessShapeLog` may already hold this shape (aiview may start readiness runs) | presence of `local-info`/`peer-info` `build-rel`, `app-version`, `threat-version`, `av-version`, `*-compat`; link-monitoring entry count and status-token set; on a firewall where system info works, local equality `build-rel` == `sw-version` (MATCH/MISMATCH only) |
| M3 | Panorama `<show><devices><all></all></devices></show>` | GR-3 (measurement-only approval) | per-entry element-name set; connected count; join outcome per enrolled firewall; `uptime` bucket compared against system info on one firewall where it works |
| M3b | none (existing configuration snapshot) | — | presence and counts of the §3.4 paths in one stored effective-running, via the masked projection |
| M4 (only if M0 shows interface all fails) | `<show><interface>ethernet1/1</interface></show>` (one physical port from the device's own configuration), then `<show><interface>hardware</interface></show>` | GR-5, GR-6 (measurement-only) | whether the named and hardware forms work where `all` fails; state token set; field names |
| M5 (only if M0 shows session info fails) | `<show><session><meter></meter></session></show>`, then the resource-monitor literal | GR-7, GR-8 (measurement-only) | whether each answers on the XML API; element names; vsys-entry count; utilization integer range |

Per the "measurement record before contract" rule, the exact commands, field
list and summary format of each measurement are committed next to this
document before the run (a `scripts/measurement/` record or the capability's
own source), not left to the transport.

## 6. Exact approval questions for the Product Owner

- **Q1 — defect source.** Where was the PAN-OS 12 defect seen: a PAN-ID or TAC
  case, a neXus job outcome, Panorama, or another monitoring tool (SNMP)? On
  which 12.x builds, and with which symptom (error, timeout, missing field,
  wrong value, firewall load)? The official lists name none of the four
  commands. The SNMP issues PAN-300555 (HA1-A down over SNMP) and PAN-312157
  (interface counters stop during commits) match the `ha1-a` and counter
  mention.
- **Q2 — M0.** Approve one measurement run of the three existing reads (§5 M0)
  on one PAN-OS 12.x firewall you name by aiview pseudonym, masked summary
  only?
- **Q3 — M1/M2/M3.** Approve the measurements M1 (`type=version`), M2 (HA
  state on one 12.x pair) and M3 (Panorama `show devices all`), each separately?
- **Q4 — identity.** Approve GR-1 `type=version` as the identity read for
  confirm, inventory and configuration on **all** PAN-OS versions (one path,
  recommended), or on PAN-OS ≥ 12 only? Keep or retire system info for
  uptime/family on PAN-OS < 12?
- **Q5 — version parity.** Amend FROZEN `FAILOVER_EXECUTION_PAN_CONTRACT.md`
  §6 check 7 to read each peer's own versions from `show high-availability
  state` (already read every phase) instead of system info, and use the same
  read for inventory content versions on HA members (GR-2)?
- **Q6 — sessions carried (check 6) on PAN-OS ≥ 12 if session info is
  broken.** Choose one: (a) measure GR-7/GR-8 and amend the contract to the one
  that proves equivalent; (b) keep check 6 and accept UNKNOWN → readiness NOT
  READY and failover blocked on 12.x; (c) make check 6 advisory (non-blocking)
  for 12.x only, disclosed on the screen.
- **Q7 — Panorama facts.** Approve GR-3 (Panorama-scope `show devices all` in
  Panorama inventory) for display-only uptime, family, content versions and
  hostname, labelled "reported by Panorama", with firewalls outside an
  enrolled Panorama, or not connected, shown as UNKNOWN or stale?
- **Q8 — interfaces.** On PAN-OS ≥ 12, accept configured interfaces (GR-4,
  labelled configured) plus HA link-monitoring state, with other runtime link
  state, MAC and dynamic addresses UNKNOWN? Or approve M4 to test the per-name
  and hardware forms first?
- **Q9 — counters and per-interface HA reads.** Confirm that
  `show counter interface all` and `show interface ha1-a/ha1-b/management` stay
  out of neXus (not issued today; no consumer)?

## 7. Risk and size

- Highest risk: check 6/7 changes touch the failover readiness path
  (mutation-adjacent). A council or code-review run is recommended on the
  implementing PR, per the PO's review-tooling rule.
- Size if all are approved: GR-1 (four call sites plus the first-contact
  entry), GR-2 parser (failover and inventory), GR-3 Panorama inventory step
  plus join, GR-4 configuration projection — about one Sonnet-tier coding lane
  per row after the measurements. GR-5 to GR-8 only on evidence.
- Doing nothing is also valid until Q1 and M0 answer whether the defect exists
  for the neXus XML API path at all.

## Cross-references

- `docs/AI_DEVELOPMENT_PROTOCOL.md` — network-device command gate.
- `PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md`, `DEVICE_FIRST_CONTACT_COMMAND_GATE_ENTRIES.md`, `PAN_DISCOVERY_API_ROUTE_GATE_ENTRIES.md`, `PAN_CONFIGURATION_API_ROUTE_GATE_ENTRIES.md` — the existing rows.
- `FAILOVER_EXECUTION_PAN_CONTRACT.md` (FROZEN) §3, §6, §8; `FAILOVER_READINESS_CHECKS_CP_PAN_DRAFT.md` PAN-05.
- `PAN_INVENTORY_MEASUREMENT_FINDINGS_2026_09_14.md` §4–§5; `PAN_DISCOVERY_MEASUREMENT_FINDINGS_2026_09_13.md` F-8, F-12.
- `FMG_SINGLE_COMMAND_DIAGNOSTIC_CLI.md` and `PanNatHitMeasure` — masked measurement precedent.
