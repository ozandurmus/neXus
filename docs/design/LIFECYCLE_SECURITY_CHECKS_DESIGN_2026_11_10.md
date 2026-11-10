# Lifecycle and security checks: license, Check Point Live Patch and vulnerability matching - design

## Status

**DRAFT - DO NOT IMPLEMENT (2026-11-10).** Design only. Nothing in this document approves a device command, a
schema, an outbound connection or a schedule. Every command literal in section 4 is a proposal that needs the Product
Owner's exact-literal approval (AGENTS.md "Network action taxonomy", amendment 2026-09-26) before any device call. No
command was sent to any device to write this document; live facts come from count-only database reads on 2026-11-10.

Terms used once and then kept:

- **License**: what the vendor installed on the box to enable a feature or a subscription (often with an expiry date).
- **Support contract**: the vendor support agreement for the box (RMA, software downloads). Often not on the box.
- **Advisory**: a vendor security bulletin for one or more CVEs, with affected and fixed versions.
- **Live Patch (CPLP)**: Check Point Live Patch, an in-memory mitigation of a running process, delivered outside the
  Jumbo Hotfix. The PO calls it "LivePatch".
- **Feed**: a machine-readable advisory source (JSON, CSAF or RSS).
- **Parse-scope extension**: reading one more field from the output of a command neXus already sends. By AGENTS.md
  this is not a new command.

## 1. The Product Owner's three questions

**"Can't you read license info from inside the firewalls?"** Yes, for most of the estate. Every vendor has a read-only
command or API call for it (section 4). Two limits: Cisco Smart Licensing has a compliance state, not a classic expiry
date; Radware has no publicly documented read (section 4.6). Support contracts are on the box only for Check Point
Spark, Fortinet and Palo Alto; for the others the date must be entered by hand.

**"Does neXus need an extra feature?"** A small extension, not a new product area. neXus already has a Lifecycle view
(`Devices > Lifecycle`, V144) with an operator-maintained end-of-support catalog and a license column. That column is
filled only for Infoblox today, and even there the expiry dates are lost (section 2, finding F2). What is missing:
per-vendor license reads, the Live Patch read, and an advisory store with a version matcher.

**"Can we add a check section for license, LivePatch and vulnerabilities across all products?"** Yes. The proposal
is one view, "Lifecycle and security", with four checks per device: support lifecycle (exists), licenses, patch level
(Jumbo take plus Live Patch) and known vulnerabilities (section 7). A useful first version needs **no new device
command at all** (section 9, slice S1).

One correction to an assumption, from Check Point's own article (sk185114): Live Patch is a mitigation for selected
CVEs only (six CVEs in six packages between 2026-07-08 and 2026-10-14), and Check Point states it does not replace the
Jumbo Hotfix. "Live Patch is enabled" therefore does not raise the patch level in general. It closes specific CVEs
until the Jumbo that carries the permanent fix is installed. The design shows it exactly that way (section 5).

## 2. What neXus reads today

### 2.1 Where the facts live

| Fact | Storage | Filled by |
|---|---|---|
| Model, software version, HA role | `devices.observed_model`, `observed_software_version`, `observed_ha_role` | every inventory run |
| Hotfix level, serial, platform family, content versions, uptime | `device_platform_facts` (V46), one row per device | Check Point and Palo Alto inventory only |
| End-of-sale / end-of-support dates | `lifecycle_catalog` (V144), entered or imported by a `security_admin` | operator; no vendor dates are shipped |
| Licenses | `infoblox_grid_summary.licenses` (V92) | Infoblox inventory only |
| Advisories, vulnerabilities, Live Patch, support contracts | none | not collected |

Code: inventory reads are in `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/inventory/`
(`InventoryReadPlan`, `InventoryCapabilityExecutor`); the HTTPS vendors (Infoblox, ProxySG via Management Center,
Radware, FortiManager) and the SSH vendors (FortiGate, ASA) are under `.../worker/backup/`. The Lifecycle view is
`service/lifecycle/LifecycleProjection.java`, `service/api/LifecycleController.java` and
`frontend/src/screens/LifecycleScreen.tsx`. Every device command is a row in `gate_registry`, seeded by a `V*.sql`
migration and mirrored in `ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml`
(288 rows today).

### 2.2 The estate, by what is known per device (live counts, 2026-11-10)

134 devices. "Patch precision" says whether the stored version is exact enough to compare with an advisory.

| Vendor / platform | Devices | Stored version | Patch precision today | License data today |
|---|---|---|---|---|
| Check Point gateways, Gaia R81.20 | 55 | `R81.20` | Jumbo take, all 55 (take 111: 1, 115: 4, 119: 30, 120: 12, 161: 8). Live Patch: not read | none |
| Check Point Multi-Domain Server, R82 | 1 | `R82` | Jumbo take 127. Live Patch: not read | `cplic print -x` goes into the encrypted backup bundle only; not parsed |
| Check Point Quantum Spark, R81.10.05 / .07 / .17 | 4 | version | firmware build for 3 of 4 | none |
| Check Point, enrollment `DRAFT` | 2 | UNKNOWN | UNKNOWN | none |
| Palo Alto firewalls | 38 | `11.1.10-h4` (9), `11.1.10-h7` (12), `11.1.13-h12` (11), `12.1.7-h5` (6) | exact (hotfix is part of the version) | none |
| Palo Alto Panorama | 1 | `12.1.10` | exact | none |
| Fortinet FortiGate | 9 | `v7.4.12 build2902` (7), `v7.4.13 build2933` (2) | exact (patch and build) | none |
| Fortinet FortiManager | 1 | `v7.4.11-build2804` | exact | none |
| Cisco ASA on Firepower | 12 | `9.18(4)22` (2), `9.18(4)50` (6), `9.22(3)200` (4) | exact for ASA; the FXOS version underneath is not read | none |
| Radware DefensePro | 4 | one four-part version of the 10.13 train (written out here it would look like an address to the privacy gate) | exact | none |
| Radware Cyber Controller | 1 | UNKNOWN | UNKNOWN | none |
| Symantec ProxySG | 4 | `SGOS 7.4.15.1 SWG Edition` | exact | none |
| Symantec Management Center | 1 | `4.1.3.2 (build 305750)` | exact | none |
| Infoblox grid | 1 | `WAPI 2.13.7` (the API schema version, **not** the NIOS version) | not usable for matching | 44 license rows, **no expiry on any row** |

### 2.3 Findings from the survey

- **F1. A Lifecycle view already exists.** `LifecycleProjection` already returns `license_status` (`NOT_COLLECTED` for
  every vendor but Infoblox) and `support_contract_status` (always `NOT_COLLECTED`). The new work extends this module;
  it does not start a second one.
- **F2. The Infoblox license expiry is dropped (defect).** The gated read `GET /wapi/v<ver>/member:license` asks for
  `type,kind,expiry_date,hwid`, but the parser keeps `expiry_date` only when it is JSON text. The WAPI documentation
  types `expiry_date` as a timestamp (a number). The latest stored summary confirms it: 44 rows with the keys
  `kind`, `member`, `type` and nothing else. The Lifecycle screen therefore shows every Infoblox license as UNKNOWN.
  The fix is a parse-scope change inside an approved read.
- **F3. Infoblox version is not the NIOS version.** `WAPI 2.13.7` cannot be compared with a NIOS advisory.
- **F4. License text already arrives in commands neXus sends, and is ignored.** FortiGate `get system status`
  (`License Status` line), ASA `show version` (license block; to be confirmed from a real output shape), Check Point
  `cpinfo -y all` (the Live Patch package line, section 5). Reading these is a parse-scope extension.
- **F5. `cplic print -x` is approved only as a backup artefact on the management server.** By the evidence law
  "command presence in source != command approval", it cannot be reused for a license check, and `-x` prints license
  signatures, which a check must never read.
- **F6. `show system info` is retired on Palo Alto firewalls** (`PO_DECISION_RECORD_2026_10_21_PAN_SHOW_SYSTEM_INFO_RETIRED.md`,
  RATIFIED; cause given later: a PAN-OS 12 defect). Any new firewall read must respect that decision: its own
  switch, first measured on one PAN-OS 11.1 firewall, and a PO choice whether PAN-OS 12 firewalls get it at all.
- **F7. No pod is free of device credentials.** `ui2-service`, `ui2-worker` and `ui2-policy` all mount the
  credential-store key. A component that fetches from the internet would share a pod with that key (section 6.5).

## 3. The four checks

Each device (each cluster member is its own evidence entity) gets four independent results. A check that could not be
evaluated says why; it never turns green by default (AGENTS.md "UNKNOWN / fail-closed law").

| Check | Question | Source | States |
|---|---|---|---|
| Support lifecycle | Is hardware or software past, or near, end of support? | `lifecycle_catalog` (exists) | `EXPIRED`, `HIGH` (<180 d), `MEDIUM` (<365 d), `LOW`, `UNKNOWN` |
| Licenses | Is any license or subscription expired or about to expire? Is the box in compliance? | new device reads (section 4), operator-entered support contracts | `EXPIRED`, `EXPIRING` (<60 d), `OUT_OF_COMPLIANCE`, `OK`, `NOT_COLLECTED`, `UNSUPPORTED`, `UNKNOWN` |
| Patch level | Which exact build, Jumbo take and Live Patch package is running? | existing reads plus Live Patch (section 5) | the value, plus `PRECISE` / `IMPRECISE` / `UNKNOWN` |
| Known vulnerabilities | Which published advisories apply to this exact version? | advisory store and matcher (section 6) | per advisory: section 6.4; per device: worst open finding, plus feed coverage |

## 4. Device reads: command literals for approval

All rows are read-only (action class `read`), run inside the session the inventory already opens (no new credential,
no new connection, no new schedule), once per inventory run, timeout 60 s, no retry. A refused, unsupported or failed
read sets that check to `UNSUPPORTED` or `UNKNOWN` and never fails the inventory. Each new read gets its own gate row
and its own worker switch, so it can be turned off without a ship.

Evidence grades in the tables:

- **A** - the exact literal and its meaning are on an official vendor page opened on 2026-11-10.
- **B** - the command is official, but something load-bearing is not: the exact API form, the output fields, or
  whether it only reads local state. One confirmation step is named.
- **C** - no official evidence found. Not proposed.

"Kept" is what neXus stores. Everything else is parsed in memory and discarded (AGENTS.md "Raw-evidence law").

### 4.1 Check Point

| # | Platform | Exact literal | Grade | Official evidence | Kept | Never read or stored |
|---|---|---|---|---|---|---|
| CP-1 | R81.20 gateways, R82 MDS (Expert) | `bash -lc 'cplic print'` (MDS: `cplic print` in the interactive Expert shell, as for `cpinfo`) | A | R81.20 Security Management Administration Guide, CLI, "cplic print" (sc1.checkpoint.com/documents/R81.20/WebAdminGuides/EN/CP_R81.20_SecurityManagement_AdminGuide/Content/Topics-SECMG/CLI/cplic-print.htm): prints the installed licenses of the local server, local and central; columns Host, Expiration, Features | per license: expiry date (or `never`), the feature/SKU tokens, a license count | the Host column (an address), the `CK-` certificate key inside Features, any signature. `-x` (adds the signature) and `-F` (writes a file) are never used |
| CP-2 | R81.20 gateways, R82 MDS (Expert) | `bash -lc 'cplp list'` (MDS: `cplp list`) | A | sk185114 "Check Point Live Patch (CPLP)" (support.checkpoint.com/results/sk/sk185114): lists all Live Patches known to the gateway; columns ID (PATCH:PROC), STATUS, MODE, PIDS, INSTALLED, COMMENT | per patch: the PATCH:PROC id, STATUS, MODE, INSTALLED time, the sk numbers in COMMENT | PIDS |
| CP-3 | R81.20 gateways, R82 MDS | none: parse-scope extension of the approved `cpinfo -y all` (gates `cp_identity_cpinfo_hotfixes`, `cp_identity_cpinfo_hotfixes_mds`) | A | sk185114: `cpinfo -y all \| grep URGENT` verifies the installed CPLP package, a line of the form `BUNDLE_URGENT_SECURITY_UPDATE_<version>_AUTOUPDATE Take: <n>` | Live Patch package take | - |
| CP-4 | Quantum Spark R81.10.x (Gaia Embedded) | `show license` | A | Quantum Spark R81.10.X CLI Reference, "show license" (sc1.checkpoint.com/documents/SMB_R81.10.X/CLI/EN/Content/Topics/show-license.htm): shows installed licenses and contract coverage | per license and per contract row: expiry date, SKU | host address, contract ID |

Notes:

- `cplic print` does not show support contracts on Gaia in the official page. A contract table in its output is
  reported by users only (grade C). The parser keeps one if present and otherwise reports `NOT_COLLECTED`.
- `cplic print` and `cplp list` are the documented commands. The `bash -lc '...'` wrapper is the login-shell form
  already approved for `cpinfo` (over the bare exec channel the Check Point environment is not loaded); the literal
  sent is the one in the table.
- On a VSX gateway both reads run once in the VS0 context (`bash -lc 'vsenv 0 && ...'`, the existing CF-2 wrapper).
  Whether Live Patch state differs per virtual system is UNKNOWN; sk185114 lists "Traditional VSX members" as
  supported and says nothing else.
- Live Patch is **not supported on Spark** (sk185114). The four Spark devices show `UNSUPPORTED`, not a gap.
- sk185114 also names `hcp -r "CVE Exposure"`, Check Point's own on-box exposure report. It runs a HealthCheck
  suite and writes a report, so it is more than a read. Not proposed; a candidate to cross-check the matcher later.
- Never to be sent (they change state or contact the vendor): `cplic contract put/del`, `contract_util mgmt`,
  `contract_util download`, `fetch license`, every `autoupdatercli ...` form.
- Not proposed (grade C, no official output description): `contract_util summary`, `cpstat os -f licensing`,
  `installed_jumbo_take`, `cplic print -p`.

### 4.2 Palo Alto

neXus reaches PAN-OS only through the XML API. The CLI commands are official; the XML form of an operational command
mirrors the CLI words (official method: "Use the CLI to Find XML API Syntax", PAN-OS 11.1 XML API guide), but no
official page prints these two XML bodies. Confirmation step for grade B: read the body in the firewall's own API
browser (`https://<firewall>/api`, Operational Commands > request > license > info), which sends nothing through neXus.

| # | Platform | Exact literal (XML API, `type=op`) | Grade | Official evidence | Kept | Never read or stored |
|---|---|---|---|---|---|---|
| PA-1 | firewalls 11.1 / 12.1, Panorama 12.1 | `<request><license><info/></license></request>` | B | CLI `request license info`: "Show the licenses installed on the device" (CLI Cheat Sheet: Device Management, docs.paloaltonetworks.com); KB "What are the CLI Commands to Verify Device and Support License?" (knowledgebase.paloaltonetworks.com, kA10g000000Cm0BCAS) | per entry: feature name, issued date, expiry date, expired flag | serial, authcode (both reported in the reply by users; assume present) |
| PA-2 | firewalls, Panorama | `<request><support><info/></support></request>` | B | same KB article: `request support info` shows the support information | support level, expiry date | any account or contact field |
| PA-3 | Panorama only | `<request><batch><license><info/></license></batch></request>` | C | none. Panorama's GUI shows each managed firewall's license state and expiry (Panorama Administrator's Guide, "Manage Licenses and Updates"), and Panorama refreshes it from the license server daily; the XML read is user-reported only | - | - |

Notes:

- Never to be sent: `request license fetch`, `request support check` (both contact Palo Alto), `request batch license
  refresh`, `request batch license offline refresh`.
- No official page states that PA-1 and PA-2 read only local state. The cheat-sheet wording ("installed on the
  device") supports it; it stays an assumption until measured.
- **Choice for the PO (D2).** PA-1 on 38 firewalls is direct evidence but a new firewall command, six of them on
  PAN-OS 12 (F6). PA-3 would read all 38 from Panorama in one call without touching a firewall, but it has no
  official evidence, is a manager's cached view (lower evidence grade), and may be up to a day old.

### 4.3 Fortinet

| # | Platform | Exact literal | Grade | Official evidence | Kept | Never read or stored |
|---|---|---|---|---|---|---|
| FG-1 | FortiGate 7.4 | none: parse-scope extension of the approved `get system status` (gate `fgt_get_system_status`) | A | Fortinet Technical Tip "Explaining get system status command output" (community.fortinet.com): `License Status: Valid`, `Release Version Information: GA` | license status word, release type | - |
| FG-2 | FortiGate 7.4 (global context on multi-VDOM units) | `diagnose test update info contract` | B | named for checking the firmware (FMWR) contract expiry in the FortiOS 7.4.0 Administration Guide, "Preventing FortiGates with an expired support contract from upgrading to a major or minor firmware release" (docs.fortinet.com/document/fortigate/7.4.0/administration-guide/299518; title verified, body not readable by the fetch tool); output described in Fortinet Technical Tip "Checking the FortiCare support contract expiry" | the `System contracts` list: contract code and expiry date per line (for example FMWR, COMP, ENHN, FURL, SPAM) | account ID, company, user ID (the longer `diagnose test update info` prints them; the `contract` form is chosen to avoid that block; the parser drops them if present) |
| FG-3 | FortiGate 7.4 | `diagnose autoupdate versions` | B | Fortinet Technical Tips only | per engine/database: version, contract expiry date, last update | - |
| FM-1 | FortiManager 7.4 (SSH) | `diagnose fmupdate dbcontract` | B | FortiManager 7.4.4 CLI Reference, "fmupdate" (docs.fortinet.com/document/fortimanager/7.4.4/cli-reference/998187/fmupdate): dumps the subscriber contract, optionally for one device serial | per managed device (matched in memory to the device, then tokenised): contract code and expiry | serial, AccountID, Company, UserID, the raw contract data block |

Notes:

- Confirmation step for FG-2 and FG-3: the official 7.4 CLI reference does not describe the output, and no page says
  the data is the local cache. Both are measure-first reads on one FortiGate.
- `diagnose` commands need an administrator profile that allows diagnostics. Whether the neXus account has it is
  UNKNOWN; a refusal reads `UNSUPPORTED`.
- **Choice for the PO (D3).** FG-2 per FortiGate (direct evidence, nine devices) or FM-1 once on FortiManager (one
  read for all nine, but a manager's stored copy, which is only as fresh as FortiManager's FortiGuard contact).
  Recommendation: FG-2 first; FM-1 only if FG-2 is refused.
- Never to be sent: `execute update-now`, any `diagnose debug ...` form.
- Not proposed (grade C): `get system fortiguard-service status`, `diagnose forticare ...`, the FortiManager JSON-RPC
  license fields (the JSON API reference is behind a Fortinet developer login).

### 4.4 Cisco ASA on Firepower

| # | Platform | Exact literal | Grade | Official evidence | Kept | Never read or stored |
|---|---|---|---|---|---|---|
| AS-1 | ASA 9.18 / 9.22 | none: parse-scope extension of the approved `show version` (gate `asa_show_version`) | B | license block in `show version`: known output shape, no official page opened; confirm from one stored output shape | license mode, licensed-feature lines | serial number |
| AS-2 | ASA 9.18 / 9.22 | `show license all` | A | Cisco, "ASA Smart License Registration and Troubleshooting" (cisco.com/c/en/us/support/docs/security/adaptive-security-appliance-asa-software/215920-asa-smart-license-registration-and-troub.html) | registration status, registration expiry, authorization status, communication deadline, compliance status, entitlement names | Smart Account and Virtual Account names, UDI (PID and serial), hostname lines |

Notes:

- Smart Licensing has no classic expiry date. The check reports the compliance state (`AUTHORIZED`, `OUT OF
  COMPLIANCE`, `AUTH EXPIRED`, evaluation mode) plus the registration-expiry and communication-deadline dates.
- A standby unit answers "Not applicable in standby state" (same document). That reads `OK (standby, licensed
  through the active unit)`, not a failure.
- On the 4100-series chassis (8 of the 12 devices) the license registration lives in the chassis software (FXOS),
  which neXus does not manage. The ASA shows entitlements and compliance only.
- In multiple-context mode the read belongs in the system execution space; which context is correct is UNKNOWN from
  documentation and is part of the first measurement.
- No on-box read exists for the hardware support contract. It is operator-entered (section 4.8).

### 4.5 Symantec ProxySG and Management Center

neXus reaches the ProxySGs only through Management Center's REST API. That API has a documented license read, so no
CLI text needs to be pushed to a ProxySG.

| # | Platform | Exact literal | Grade | Official evidence | Kept | Never read or stored |
|---|---|---|---|---|---|---|
| MC-1 | Management Center 4.1, per managed device | `GET /api/devices/{uuid}/license` | A | Management Center 4.1 API, "Return the license information for a specific device" (techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/api/Paths/getdevicelicense.html) | `licenseStatus`, `trialPeriodExpirationDate`, per component: `componentName`, `activationDate`, `expirationDate`, `validity` | `serialNumber` (device and component), `partNumber`, `name` |
| MC-2 | Management Center 4.1 | `GET /api/system/version` | A | same API reference, "getversion": `version`, `buildNumber` | version, build | - |
| SG-1 | ProxySG via Management Center | `PUT /api/devices/{uuid}/command "show licenses"` | B | SGOS 7.4 CLI Reference, Standard mode `show`: `show licenses` "Displays license information"; output fields not documented | fallback only, if MC-1 returns nothing for ProxySG devices | - |

Notes:

- The documented example for MC-1 is a different device type; the ProxySG reply shape is confirmed by the first
  measurement. This is a manager's view of the device (management-plane evidence), stated as such on screen.
- Management Center's own license has no documented API path: `NOT_COLLECTED`.
- Never to be sent: `show sources license-key` (may print the key), `licensing update-key`, `licensing request-key`.

### 4.6 Radware DefensePro and Cyber Controller

No command is proposed. The public REST reference (webhelp.radware.com/vision/REST/10_3_0) documents the calls neXus
already uses and a `License` structure with feature flags and license strings, but no expiry field and no read for
subscription dates. The CLI references and the user guides are behind the Radware customer portal.

- Radware licenses and subscriptions: `NOT_COLLECTED` until the PO supplies the DefensePro and Cyber Controller
  guides from the customer portal (D11). The dates can be entered by hand meanwhile (section 4.8).
- The Cyber Controller version is UNKNOWN today; whether `GET /mgmt/system/config/itemlist/alldevices` (already
  approved) carries it is unverified.

### 4.7 Infoblox

| # | Platform | Exact literal | Grade | Official evidence | Kept | Never read or stored |
|---|---|---|---|---|---|---|
| IB-1 | grid manager (WAPI) | none new: fix the parser of the approved `GET /wapi/v<ver>/member:license` (finding F2) and request `expiration_status,limit,limit_context` as well | A | WAPI 2.13.7 object reference, `member:license`: `expiry_date` is a timestamp; `expiration_status` is one of `EXPIRED`, `EXPIRING_SOON`, `EXPIRING_VERY_SOON`, `NOT_EXPIRED`, `PERMANENT`, `DELETED` | type, kind, expiry date, expiration status, limit | `key` (the license string) is never requested; `hwid` is used in memory to name the member, as today |
| IB-2 | grid manager (WAPI) | `GET /wapi/v<ver>/license:gridwide?_return_fields=type,expiry_date,expiration_status,limit` | A | WAPI 2.13.7 object reference, `license:gridwide` | the same fields | `key` |
| IB-3 | grid manager (WAPI) | `GET /wapi/v<ver>/upgradestatus?type=GRID` | B | WAPI 2.13.7 object reference, `upgradestatus`: `current_version` is returned by default; "Parameter type is required for object searches"; values GRID, GROUP, PNODE, VNODE; the object is read-only | `current_version` (the NIOS version), `hotfixes` | member names |

Notes: the WAPI reference was read on a public mirror of the vendor-generated documentation (ipam.illinois.edu/wapidoc);
the grid serves the same pages at `/wapidoc/`, which neXus already fetches. IB-3's exact query string is built from
the documented rules and has no printed example: measure first. Infoblox has no API object for the support contract.

### 4.8 What stays manual

Support contracts and anything a vendor does not expose are entered by a `security_admin` in a contract register
(same pattern as the lifecycle catalog: manual entry or file import, audited). The hand-built Excel report can be the
first import. A manual date is labelled `OPERATOR_ENTERED` on screen and never mixed with a device-read date.

### 4.9 Summary for approval

| Group | Rows | What the PO is asked |
|---|---|---|
| No approval needed (parse-scope) | CP-3, FG-1, IB-1, AS-1 | none; listed for transparency. IB-1 adds three return fields to an approved read |
| Ready for exact-literal approval (grade A) | CP-1, CP-2, CP-4, AS-2, MC-1, MC-2, IB-2 | approve each literal; the first run on each platform is a single-device measurement with the sanitised output shape shown to the PO |
| Approval after one confirmation step (grade B) | PA-1, PA-2, FG-2, FG-3, FM-1, SG-1, IB-3 | approve as measure-first reads on one device each (amendment 2026-09-26), or wait for the named confirmation |
| Not proposed (grade C) | PA-3, Radware, the items listed as "not proposed" | nothing now |

## 5. Check Point Live Patch

Facts, all from sk185114 (opened 2026-11-10):

- Supported on R81.20, R82, R82.10 and R82.20: gateways, ClusterXL members, traditional VSX, management servers
  and Multi-Domain servers. Not supported on Spark appliances.
- It patches running processes in memory. A reboot or process restart does not remove it; active patches are
  re-applied automatically.
- It "does not replace" the Hotfix or Jumbo Hotfix Accumulator; the Jumbo stays the long-term remediation.
- The package is delivered by the automatic updater and is cumulative over a rolling window. Its level is the
  `BUNDLE_URGENT_SECURITY_UPDATE_..._AUTOUPDATE Take: <n>` line of `cpinfo -y all`. The current package on
  2026-11-10 is take 30 (2026-10-14).
- `cplp list` shows each patch with a STATUS: `armed` (active in the running process), `ready` (installed, applied
  when the process starts), `reverted` (removed), `jumbofix` (the installed Jumbo is newer than the patch, which is
  therefore not applied), `unsupp` (the patch does not cover the installed build), `nopatch` (none for this version
  or platform), `nolib` (the component is not installed).
- The article's own table maps each package take to dates and CVEs, and the COMMENT column of `cplp list` cites the
  sk article of the CVE a patch covers.

What neXus stores: the package take (CP-3, no new command) and, once CP-2 is approved, one row per patch with its
status. What the patch-level check shows: `R81.20 · Jumbo take 119 · Live Patch package take 30 · 4 armed, 0 ready`.

How a Live Patch counts in the vulnerability check (in this order, first match wins):

| Evidence | Verdict | Colour |
|---|---|---|
| Jumbo take is at or above the take that carries the permanent fix | `FIXED` | green |
| `cplp list` has a patch that cites the advisory's sk article, STATUS `armed` | `MITIGATED_BY_LIVE_PATCH` (permanent fix still pending) | green with a marker |
| the same patch with STATUS `ready` | `MITIGATION_PENDING` (not yet active in the process) | amber |
| the same patch with STATUS `jumbofix` while the take comparison says affected | `INCONSISTENT` (shown, never resolved silently) | amber |
| the same patch with STATUS `nolib` | `NOT_APPLICABLE` (component not installed) | grey |
| the same patch `reverted`, `unsupp` or `nopatch` | `AFFECTED` | red |
| no `cplp list` evidence, but the package take is at or above the take that introduced the patch (per the sk185114 table) | `MITIGATION_PACKAGE_PRESENT` (the package is installed; per-process state not read) | amber |
| no Live Patch evidence at all, and the PO's statement of 2026-11-10 that Live Patch is enabled on all Check Point firewalls | `AFFECTED_BY_JUMBO_LIVE_PATCH_UNVERIFIED` | amber, never green |
| none of the above | `AFFECTED` | red |

A mitigated advisory stays on the "permanent fix pending" list with the target Jumbo take, so upgrade planning still
sees it. An advisory for which Check Point published no Live Patch is unaffected by any of this.

Why this matters on this estate (an illustration, not a verdict): the sk185114 row of 2026-09-14 names R81.20
Jumbo take 146 as its threshold; 47 of the 55 R81.20 gateways run take 120 or lower and 8 run take 161.

The PO's statement is not yet a repository record. It should be recorded as a short decision record so the last
amber state has a citable basis; it is replaced by real evidence as soon as CP-3 ships.

## 6. Vulnerability matching

### 6.1 Principles

1. **Match locally.** neXus downloads whole public advisory sets and compares them with stored versions inside
   neXus. No request to a vendor or public service ever carries a device version, model, serial or name. This rules
   out per-version query APIs (for example the Cisco version query) as the normal path.
2. **Vendor first.** The vendor's own advisory decides affected and fixed versions. Public databases only add
   signals (known exploitation) or fill in where a vendor has no feed.
3. **Never green by default.** "No advisory matched" is shown together with the feed's age and coverage. A stale or
   missing feed reads `NOT_EVALUATED`, not "no vulnerabilities".
4. **Version only, stated as such.** Many advisories apply only when a feature is enabled. The first version judges
   the version alone and labels the result "affected by version; exposure depends on configuration".

### 6.2 Sources per vendor

| Vendor | Primary source | Format and access | Version ranges | Fixed version | Mode |
|---|---|---|---|---|---|
| Palo Alto | security.paloaltonetworks.com: `/rss.xml` for new items and `/json/<CVE>` for the vendor's current copy; the full set is listed from the CVE Program bulk data (assigner Palo Alto). The site's `/api/v1/products/PAN-OS/<version>/advisories` call is not used: it carries a version in the request (principle 1) | CVE JSON 5; no authentication (fetched 2026-11-10) | per release train, hotfix-exact (`lessThan`, `changes[]`) | yes, in `changes[]` | automatic |
| Fortinet | PSIRT RSS `filestore.fortinet.com/fortiguard/rss/ir.xml`; each item links its CSAF file | RSS 2.0 plus CSAF 2.0; no authentication. The CSAF file name contains a title slug, so it is read from the RSS item, not built | text-encoded per branch (for example `FortiOS >=7.4.0\|<=7.4.9`) | remediation text ("Upgrade to 7.4.10 or above") | automatic, with a strict parser; an advisory with an empty product tree goes to curation |
| Cisco | public CSAF `www.cisco.com/.well-known/csaf/` (provider metadata and index fetched 2026-11-10) | CSAF 2.0; no authentication | how ASA versions are listed in the CSAF product tree is UNKNOWN until the first parser spike | "first fixed release" is offered by the openVuln API and the Software Checker; whether CSAF carries it is UNKNOWN | automatic for affected; fixed release may need curation or openVuln (D5) |
| Cisco (optional) | openVuln API `apix.cisco.com/security/advisories/v2/` | JSON; OAuth2 client credentials from a Cisco account; rate limits not published | per-version query (conflicts with principle 1) or whole-product listing | yes | only on a PO decision (D5) |
| Check Point | CVE records published by Check Point as CVE Numbering Authority, the sk article of each CVE, the Jumbo release notes, sk185114 for Live Patch | CVE JSON 5 from the CVE Program; no Check Point feed was found | free text: in CVE-2024-24919 the affected version is one sentence, not a range | in the sk article and the Jumbo release notes | advisory arrives automatically; the fix rule (fixed Jumbo take per train, Live Patch sk reference and package take) is entered by a `security_admin` (section 6.4) |
| Infoblox | NVD entries for `cpe:2.3:o:infoblox:nios` | NVD JSON; thin coverage (10 CVEs in total on 2026-11-10); vendor KB needs a login | present where NVD has them | partial | NVD plus manual entry |
| Symantec ProxySG, Management Center | Broadcom advisories portal and KB articles; format not verified | UNKNOWN | UNKNOWN | UNKNOWN | CVE-record watch plus manual entry |
| Radware | no public advisory feed found | - | - | - | CVE-record watch plus manual entry |
| All (signal) | CISA Known Exploited Vulnerabilities catalog (JSON) | public; the CVE Program record also carries the CISA exploitation flag (seen in CVE-2024-24919) | - | - | marks an advisory "exploited in the wild" |
| All (inbox) | CVE Program bulk list `github.com/CVEProject/cvelistV5` (daily baseline, hourly deltas) | CVE JSON 5; public | as good as the vendor made them | sometimes | "a new CVE names a product you run" for every vendor, including those matched by hand |

EPSS (an exploitation-likelihood score, `api.first.org`) is a possible later signal. It is left out of the first
version: one more source, and the known-exploited flag answers the urgent question.

### 6.3 Version handling

One comparator per product, each a strict parser. A string the parser does not fully understand is `UNKNOWN`; it is
never trimmed or guessed into shape.

| Product key | Example stored value | Compared as |
|---|---|---|
| `paloalto:pan-os` (firewall and Panorama) | `11.1.10-h7` | train `11.1`; then maintenance; then hotfix number (no hotfix sorts first) |
| `checkpoint:gaia` (gateway, MDS) | `R81.20` + `Jumbo Take 119` + Live Patch facts | train `R81.20`; then Jumbo take; Live Patch per section 5 |
| `checkpoint:spark` | `R81.10.17 Build 721` | version; then build |
| `fortinet:fortios`, `fortinet:fortimanager` | `v7.4.12 build2902` | train `7.4`; then patch; build as a cross-check |
| `cisco:asa` | `9.18(4)50` | `9.18.4.50`, the dotted form Cisco uses in advisories; train `9.18` |
| `broadcom:sgos`, `broadcom:management-center` | `SGOS 7.4.15.1`, `4.1.3.2` | four numeric parts |
| `radware:defensepro` | four-part version, 10.13 train | four numeric parts |
| `infoblox:nios` | (from IB-3) | numeric parts; build suffix as a tiebreak |

Range evaluation follows the CVE JSON 5 rules (`version`, `lessThan`, `lessThanOrEqual`, `changes[]`) per train:

- A device is `AFFECTED` only when its version falls inside an explicit affected range.
- It is `NOT_AFFECTED` only when the advisory names its product and train and the version falls outside every
  affected range, or inside a stated unaffected range.
- A product named without its train, an unparsed range or an unparsed device version gives `NOT_EVALUABLE` with the
  reason.

Known coverage gaps, shown on screen as gaps: FXOS under ASA (version not read), NIOS (until IB-3), Cyber Controller
(version UNKNOWN), the two Check Point devices still in `DRAFT`.

### 6.4 Verdicts, curation and disposition

Per device and advisory: `AFFECTED`, `FIXED`, `NOT_AFFECTED`, `NOT_EVALUABLE`, `NEEDS_CURATION`, plus the Check Point
Live Patch verdicts of section 5.

**Curation.** Where a vendor publishes no machine-readable fixed version (Check Point always; Cisco, Infoblox,
Broadcom and Radware sometimes), the advisory arrives as `NEEDS_CURATION`. A `security_admin` enters a fix rule from
the vendor article: product, train, fixed version or fixed Jumbo take, and for Check Point the Live Patch sk reference
and package take. The rule records who entered it, when, and the source URL, and is audited. For Check Point the
volume is small (six Live Patch CVEs in four months, a handful of Jumbo security fixes per take). Text patterns such
as "Jumbo Take 141 or below" may be offered as a pre-filled suggestion; a suggestion is never applied without
confirmation.

**Disposition.** A `security_admin` can mark a finding `ACCEPTED_RISK`, `NOT_EXPOSED_BY_CONFIGURATION`,
`MITIGATED_OTHERWISE` or `PLANNED` (with a due date), with a reason and an expiry. The verdict itself does not
change; the disposition is shown next to it and is audited. It concerns managed devices only and has nothing to do
with the scan gates for the neXus product images.

Clusters: each member is judged alone; a cluster row shows the worst member.

### 6.5 How the feeds get in, and how often

Two modes; the PO chooses (D4).

- **Offline bundle (recommended first).** A script in the repository (`tools/advisory/`) runs on any
  internet-connected workstation, downloads the sources of section 6.2, and writes one JSON bundle with a SHA-256.
  A `security_admin` uploads it on the "Catalog and sources" tab. neXus validates it (schema, size cap, hash),
  stores the advisories and re-evaluates. neXus itself makes no outbound connection. This matches finding F7: no
  pod with device credentials talks to the internet.
- **Online refresh (later, optional).** A fetch job reads the same fixed list of URLs through the enterprise proxy,
  GET only, no request body, no estate data. Because every current pod holds the credential-store key, this needs
  either an accepted exception or a small fetch-only pod with no device route and no credential key. The ratified
  container principle (`PO_DECISION_RECORD_2026_11_05_ANALYSIS_CONTAINER_DIRECTION.md`) lists load isolation, failure
  isolation and deploy cadence as justifications, not a trust boundary; this document does not stretch it and leaves
  the choice to the PO. Whether HOST-A has any permitted route out is UNKNOWN.

Refresh cadence:

| What | When |
|---|---|
| Device facts (version, Jumbo take, Live Patch, licenses) | every inventory run; no schedule of their own (schedules are off in development mode) |
| Advisory bundle, offline | weekly, and after the vendors' fixed publication days (Fortinet: the second Tuesday of the month, per its PSIRT policy; the other vendors' days are added as they are confirmed), and on any out-of-cycle alert |
| Advisory feeds, online | once a day, with conditional GET; own on/off switch, independent of other schedules |
| Re-evaluation | after every feed load and every inventory run (a database computation; no device contact) |
| Feed age shown on screen | amber after 7 days, red after 30 days; red also sets every "no findings" to `NOT_EVALUATED` |

Advisory content is untrusted input: strict schema validation, text rendered as text (never as HTML), links shown
only for an allow-list of vendor hosts.

### 6.6 Acceptance oracle

The PO's hand research of 2026-11-10 is the first acceptance test. With the feeds loaded, the matcher must reproduce
it from stored facts alone: the Palo Alto advisory CVE-2026-109618 affects every installed PAN-OS version; the
Fortinet October hardening release affects FortiOS 7.4.12 and FortiManager 7.4.11; ASA 9.18 has exploited
high-severity CVEs; Infoblox has CVE-2026-107510 (reported `NOT_EVALUABLE` until the NIOS version is read). A
difference is investigated before anything ships.

## 7. Data model (proposal; migration numbers are assigned at implementation)

| Table | One row per | Main columns | Notes |
|---|---|---|---|
| `device_license_read` | device | outcome (`COLLECTED`, `UNSUPPORTED`, `REFUSED`, `FAILED`, `NOT_APPROVED`), reason, source gate, observed_at | so that "no rows" is never read as "no licenses" |
| `device_license_item` | device and license item, replaced on each run | item key, label, category (`PLATFORM`, `SUBSCRIPTION`, `SUPPORT_CONTRACT`, `SMART_ENTITLEMENT`, `EVALUATION`), state, expires_on, evidence grade (`DIRECT_DEVICE`, `MANAGER_REPORTED`), source gate, observed_at | no key, signature, authcode, serial, bound address, contract or account id |
| `support_contract_entry` | operator entry | device or model scope, level label, expires_on, note, source (`IMPORT`, `MANUAL`), entered_by | audited; note and entered_by under redaction tier 2, as in `lifecycle_catalog` |
| `device_live_patch` | device | package take, bundle name, read state, observed_at | Check Point only |
| `device_live_patch_item` | device and patch | patch id (PATCH:PROC), status, mode, installed_at, sk references | Check Point only, after CP-2 |
| `advisory` | advisory | id, vendor, source, source URL, title, severity, CVSS, CVE ids, published, updated, exploited flag and date, content hash | public data |
| `advisory_affected_range` | advisory, product and train | product key, train, range bounds, status, fixed version | parsed from the feed |
| `advisory_fix_rule` | curated rule | advisory, product key, train, fixed version or Jumbo take, Live Patch sk reference, Live Patch package take, source URL, curated_by, curated_at | audited |
| `advisory_feed_run` | load | source, mode (`BUNDLE`, `ONLINE`), bundle hash, counts, outcome, actor, loaded_at | drives the feed-age display |
| `device_vulnerability_finding` | device and advisory | verdict, basis, observed version and patch level, feed run, evaluated_at | recomputed; history kept for trend (D12) |
| `vulnerability_disposition` | finding or advisory scope | disposition, reason, due date, expiry, actor | audited |

The existing `infoblox_grid_summary.licenses` stays as it is for the grid summary panel; the Lifecycle projection
reads `device_license_item` for every vendor, Infoblox included. `LifecycleProjection.licenses` loses its
Infoblox-only branch.

## 8. Where it runs, permissions, and the view

**Runtime.** Device reads are extra steps of the existing inventory capability in the worker. Matching and the
bundle import run in `ui2-service`: the data is small (134 devices, a few hundred advisories), so no new container
is justified by the container principle. If the planned analysis container exists by then, the matcher can move
there without a design change.

**Permissions.** Viewing follows the Devices plane, as Lifecycle does today. Bundle upload, fix rules, the contract
register and dispositions are `security_admin` actions in `ActionRegistry`, next to the existing
`lifecycle_catalog_write`. The aiview persona sees the view through the masked projection; advisory text is public
and is not masked; device names, member names and operator notes are masked as today.

**View.** `Devices > Lifecycle` becomes `Devices > Lifecycle and security` (same route), with four tabs:

1. **Overview.** Summary cards (past end of support, licenses expiring within 60 days, devices with an exploited
   open advisory, devices not evaluated, feed age), then one row per device or cluster:

   | Device / cluster | Vendor | Version and patch | Support lifecycle | Licenses | Vulnerabilities | Evidence age |
   |---|---|---|---|---|---|---|
   | CLS-ROMEO-01 | Check Point | R81.20 · take 119 · Live Patch 30 | LOW | OK (nearest 2027-03-01) | 2 mitigated by Live Patch, 0 affected | 1 d |
   | FW-TANGO-04 | Palo Alto | 11.1.10-h7 | MEDIUM | NOT_COLLECTED | 1 affected (exploited) | 5 d |

   Default sort: what needs action soonest (exploited and affected first, then expired, then nearest date).
2. **Vulnerabilities.** One row per advisory: severity, exploited flag, affected device count, fixed version, state
   (`NEEDS_CURATION` first for a `security_admin`). Opening a row lists the devices with their verdict and basis.
3. **Licenses.** A timeline of expiry dates, grouped by vendor, with the evidence grade of each date.
4. **Catalog and sources.** The existing end-of-support catalog, the contract register, the bundle upload and the
   feed status table.

The device workspace gets the same four checks as a compact panel. The Reports area gets a "Lifecycle and security"
report (spreadsheet), which replaces the hand-built Excel. The UI lane follows the standing process for UI work
(design, implementation, effectiveness review).

Every value on screen carries its evidence line: which read or feed, and when. A check that is not collected says
which approval or source is missing.

## 9. Delivery slices, size and risk

| Slice | Content | Device commands | Size | Risk |
|---|---|---|---|---|
| S1 | Infoblox expiry fix (F2); Live Patch package take from `cpinfo` (CP-3); FortiGate license status (FG-1); advisory store, offline bundle, comparators and matcher for Palo Alto, Fortinet and Cisco ASA; Check Point advisories with curated fix rules; known-exploited flag; view with the four checks | none new | large: about five lanes (schema and repositories, comparators and matcher, bundle builder and three feed parsers, view, report) | medium: no device impact; the risk is a wrong verdict. Countered by the oracle of section 6.6, fixtures shaped like real advisories, and `NOT_EVALUABLE` as the default |
| S2 | License reads, one vendor at a time: CP-1, CP-4, AS-2, MC-1, MC-2, IB-2, then the grade B rows as they are confirmed | each approved literal, first on one device | medium per vendor | low to medium: read-only, but new commands on production firewalls; each has a switch |
| S3 | `cplp list` (CP-2) and the full Live Patch verdicts | CP-2 | small | low |
| S4 | NIOS version (IB-3), Radware (after the vendor guides), online refresh if chosen, contract register import, notifications for expiring licenses and newly exploited advisories | IB-3 | medium | low |

S1 already gives a vulnerability verdict for 121 of the 134 devices without sending one new command. The other 13
show as named gaps: Infoblox (1, no NIOS version), Radware (5) and the ProxySG family (5), which have no usable feed
and rely on the CVE inbox and manual entry, and the two Check Point devices still in `DRAFT`.

Standing gates apply to every slice: the frozen contract before implementation (this document, revised after the PO
decisions, becomes the contract), a dry run of each migration on the live database, architecture tests, Java lane
tests, real-environment validation per vendor under the aiview persona.

## 10. Privacy and gating

- **Nothing identifying is stored from a license read.** License keys, signatures, certificate keys, authcodes,
  serials, bound addresses, contract ids, account ids, company names and user ids are parsed past in memory and
  dropped. Where a grouping is useful (the same contract on several devices), an HMAC token made with the existing
  privacy key is stored, never the id.
- **Flags that reveal secrets are excluded by construction**: `cplic print` without `-x`, WAPI without `key`,
  `diagnose test update info contract` in place of the longer form.
- **No raw output is persisted**, including in job transcripts and diagnostic history. The gate rows carry
  `secret_output_risk` text naming what the output contains, and the reads are excluded from the transcript store.
  Parser diagnostics log the output shape only (line counts, field names, digits replaced), as
  `CheckPointPlatformFactsParser.noJumboShape` does today.
- **Every new read is `NOT_APPROVED` until the PO approves its exact literal.** Approval of one row does not approve
  another. The gate migration quotes the PO's words, as V194 does. The first execution of each literal is one device,
  with the sanitised shape reported back before the read is enabled for the platform.
- **Outbound traffic carries no estate data.** Offline mode has no outbound traffic at all.
- **Evidence grade is visible.** A date reported by a manager (Management Center, FortiManager, Panorama) or typed
  by an operator is labelled and never presented as a direct device read.
- Chat, commits and pull requests keep using masked names; database checks for this feature return counts and opaque
  ids only.

## 11. UNKNOWN

| # | Unknown | How it is resolved |
|---|---|---|
| U1 | Exact XML bodies and reply fields of PA-1 and PA-2; whether they only read local state | the firewall's API browser, then one measured read |
| U2 | Whether the new Palo Alto reads are safe on PAN-OS 12 (the defect behind F6 is not described in the repository) | PO knowledge; start on PAN-OS 11.1 |
| U3 | Output shape and locality of FG-2, FG-3, FM-1; whether the neXus FortiGate account may run `diagnose` | one measured read |
| U4 | ASA: correct context in multiple-context mode; shape of `show license all` on the 4100 series; the license block of `show version` | one measured read per platform type |
| U5 | MC-1 reply shape for a ProxySG; whether `show licenses` shows expiry dates | one measured read |
| U6 | Radware license, subscription and version reads; Radware and Broadcom advisory formats | vendor guides from the customer portals (D11) |
| U7 | IB-3 query form; whether `upgradestatus` names the NIOS version as the advisories do | one measured read |
| U8 | How Cisco CSAF lists ASA versions and first fixed releases | parser spike on public files; no device involved |
| U9 | Whether Check Point offers any machine-readable advisory feed | not found on 2026-11-10; ask the vendor account team |
| U10 | Live Patch state per virtual system on VSX; behaviour of `cplp list` on the MDS | one measured read each |
| U11 | Whether HOST-A has a permitted outbound route | only matters for online refresh |
| U12 | Rate limits and terms of use of the vendor feeds | none stated on the pages read; the bundle builder uses conditional GET and a low request rate |

## 12. Decisions requested from the Product Owner

| # | Decision | Recommendation |
|---|---|---|
| D1 | Approve the grade A literals of section 4.9, each by its exact text | approve; first run on one device per platform |
| D2 | Palo Alto: new reads on firewalls (PA-1, PA-2), and whether PAN-OS 12 firewalls are included | approve as measure-first on one PAN-OS 11.1 firewall; decide PAN-OS 12 after the result |
| D3 | Fortinet: per FortiGate (FG-2) or through FortiManager (FM-1) | FG-2 first |
| D4 | Advisory intake: offline bundle or online refresh | offline bundle first |
| D5 | Cisco: public CSAF only, or openVuln API credentials from the Cisco account | CSAF first; decide after the parser spike (U8) |
| D6 | Live Patch counts as "mitigated, permanent fix pending", not "fixed"; record the 2026-11-10 statement as a decision record | yes to both |
| D7 | Fix rules for Check Point (and other vendors without a feed) are curated by a `security_admin` | yes |
| D8 | Support contracts come from an operator-maintained register; first import from the Excel report | yes |
| D9 | The view is `Devices > Lifecycle and security`, replacing Lifecycle | yes |
| D10 | Dispositions (accepted risk and the others) are allowed, `security_admin` only, with an expiry | yes |
| D11 | The PO supplies the Radware and Broadcom customer-portal guides | needed for U6 |
| D12 | Finding history is kept for trend reporting | keep 400 days |
| D13 | Start S1 now (no new device command) | yes |

## 13. Product Owner answers so far

| Date | Asked | Answer (verbatim) | Recorded effect |
|---|---|---|---|
| 2026-11-10 | The Turkish summary of this document: the parse-scope rows, the seven grade A literals, the seven grade B literals, the decisions of section 12, and whether to fix finding F2 now | "ok abi çalıştırtırabilsin aynı zamanda da hatayı düzelt" | **F2 / IB-1:** fixed on branch `fix/infoblox-license-expiry-1110` (parser reads the WAPI timestamp; the same read also returns `expiration_status`; a permanent license is `PERMANENT`), shipped through the normal queue. **Device reads:** the answer allows neXus to run the proposed reads but names no row or group. No gate row is authored from it. The PO is asked which rows it covers: the grade A literals only, or the grade B measure-first reads as well |

Until that scope is confirmed, every new read of section 4 stays `NOT_APPROVED`.

## 14. Evidence register

Pages opened on 2026-11-10. "Summarised" means the page was read through a summarising fetch tool; the exact strings
must be re-read at contract freeze.

| Topic | Source |
|---|---|
| Check Point Live Patch | support.checkpoint.com/results/sk/sk185114 (summarised) |
| Check Point `cplic print` | sc1.checkpoint.com/documents/R81.20/WebAdminGuides/EN/CP_R81.20_SecurityManagement_AdminGuide/Content/Topics-SECMG/CLI/cplic-print.htm (summarised) |
| Check Point Spark `show license` | sc1.checkpoint.com/documents/SMB_R81.10.X/CLI/EN/Content/Topics/show-license.htm (summarised) |
| Check Point installed Jumbo | support.checkpoint.com/results/sk/sk98028; Jumbo release notes at sc1.checkpoint.com/documents/Jumbo_HFA/R81.20/All_Resolved_Issues.html (redirect target seen, not read) |
| Check Point CVE record shape | cveawg.mitre.org/api/cve/CVE-2024-24919 (assigner `checkpoint`; free-text version; CISA exploitation data present) |
| Palo Alto CLI commands | docs.paloaltonetworks.com, "CLI Cheat Sheet: Device Management"; knowledgebase.paloaltonetworks.com article kA10g000000Cm0BCAS (search result) |
| Palo Alto XML API form | docs.paloaltonetworks.com/pan-os/11-1/pan-os-panorama-api/get-started-with-the-pan-os-xml-api/explore-the-api/use-the-cli-to-find-xml-api-syntax (search result) |
| Palo Alto Panorama license view | docs.paloaltonetworks.com/panorama/administration/manage-licenses-and-updates |
| Palo Alto advisories | security.paloaltonetworks.com/json/CVE-2024-3400, /api/v1/products, /rss.xml (fetched) |
| Fortinet contract read | docs.fortinet.com/document/fortigate/7.4.0/administration-guide/299518 (title only); Fortinet Technical Tips on community.fortinet.com |
| FortiManager `fmupdate` | docs.fortinet.com/document/fortimanager/7.4.4/cli-reference/998187/fmupdate |
| Fortinet advisories | filestore.fortinet.com/fortiguard/rss/ir.xml and one linked CSAF file (fetched); fortiguard.com/psirt_policy |
| Cisco ASA licensing | cisco.com/c/en/us/support/docs/security/adaptive-security-appliance-asa-software/215920-asa-smart-license-registration-and-troub.html; ASA 9.18 general configuration guide, Smart Licensing chapter |
| Cisco advisories | www.cisco.com/.well-known/csaf/provider-metadata.json and index.txt (fetched); developer.cisco.com/docs/psirt |
| Management Center API | techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/management-center/4-1/api/ (getdevicelicense, getdevice, getversion, executecommand) |
| SGOS `show licenses` | techdocs.broadcom.com/us/en/symantec-security-software/web-and-network-security/edge-swg/7-4/index2/_standard/show.html |
| Radware REST | webhelp.radware.com/vision/REST/10_3_0/index.html (partly read) |
| Infoblox WAPI | ipam.illinois.edu/wapidoc/objects/member.license.html, license.gridwide.html, upgradestatus.html (public mirror of the vendor-generated WAPI 2.13.7 reference) |
| Infoblox in NVD | services.nvd.nist.gov/rest/json/cves/2.0 with `virtualMatchString=cpe:2.3:o:infoblox:nios` (fetched) |
| CVE Program bulk data | github.com/CVEProject/cvelistV5 |
| Known exploited catalog | cisa.gov known-exploited-vulnerabilities catalog (the JSON feed returned 403 to the fetch tool; the flag was verified through the CVE record above) |
