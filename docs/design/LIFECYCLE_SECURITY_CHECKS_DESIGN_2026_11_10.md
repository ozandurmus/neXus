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

Two facts about Live Patch from Check Point's own article (sk185114). It covers selected CVEs only (six CVEs in six
packages between 2026-07-08 and 2026-10-14), so "Live Patch is enabled" does not raise the patch level in general:
a CVE without a Live Patch is still judged by the Jumbo take. And for a CVE that has one, an active patch is shown as
**Fixed (Live Patch)** (PO decision D6, section 5.1), with the vendor's 12-week Jumbo deadline shown beside it.

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
- **Decided (D2, 2026-11-10):** PA-1 and PA-2 directly on the firewalls, measured first on one PAN-OS 11.1
  firewall; PAN-OS 12 firewalls only after that result. PA-3 (through Panorama) stays unproposed.

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
- **Decided (D3, 2026-11-10):** FG-2 on each FortiGate first; FM-1 through FortiManager only if FG-2 is refused.
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

Searched twice on 2026-11-10; the second pass read the public REST references in full (PO question, D11). The PO is
right that documentation is public. What is public does not contain a license or subscription expiry read.

**Public and read** (no sign-in): the Cyber Controller REST API references at
`webhelp.radware.com/vision/REST/<version>/index.html` (versions up to 10.11.1; 10.12 and 10.13 pages do not exist
there), Cyber Controller release notes, a few knowledge-base articles.

| What | Finding |
|---|---|
| Device software version | `GET /mgmt/system/config/itemlist/alldevices` (already approved and sent) returns `deviceVersion` per device: a parse-scope extension, no new read |
| Device license | `GET /mgmt/system/config/tree/device/byip/{ip}` is documented (REST reference 10.11.0, "device information, device properties, and status"). Its `License` structure has feature flags, `throughput`, and three license strings. **It has no expiry date.** The strings are license keys and must never be read into storage or logs |
| Subscription expiry (signature updates, attacker feed, geolocation) | no field, endpoint or scalar in any public page |
| Single device values ("scalars") | only the write form is documented (`PUT .../config`); the read form is not |
| Cyber Controller's own version and license | no REST path; no public CLI reference |
| Security advisories | knowledge-base articles on `support.radware.com`; without sign-in only the title and product list show ("Partial content displayed, please Sign In"). NVD has no product entry for DefensePro or Cyber Controller, so matching by product name there does not work |

**Behind the customer portal sign-in:** the DefensePro and Cyber Controller user guides and CLI references, the full
text of the advisories, and anything about subscription expiry.

Candidate read, not proposed for approval yet:

| # | Platform | Exact literal | Grade | Official evidence | Kept | Never read or stored |
|---|---|---|---|---|---|---|
| RD-1 | Cyber Controller, per DefensePro | `GET /mgmt/system/config/tree/device/byip/{ip}` | B | Cyber Controller 10.11.0 REST API reference, `Device` / `DeviceSetup` / `License` definitions | feature flags, `throughput`, software and driver version | the three license strings, access settings |

RD-1 gives a throughput and feature picture, not a date. It is worth an approval only if that picture is wanted. The
expiry dates need either the gated guides (to find the documented read) or the manual register. Radware advisories
can only be followed by hand or with a portal sign-in.

### 4.7 Infoblox

| # | Platform | Exact literal | Grade | Official evidence | Kept | Never read or stored |
|---|---|---|---|---|---|---|
| IB-1 | grid manager (WAPI) | none new: fix the parser of the approved `GET /wapi/v<ver>/member:license` (finding F2) and request `expiration_status,limit,limit_context` as well | A | WAPI 2.13.7 object reference, `member:license`: `expiry_date` is a timestamp; `expiration_status` is one of `EXPIRED`, `EXPIRING_SOON`, `EXPIRING_VERY_SOON`, `NOT_EXPIRED`, `PERMANENT`, `DELETED` | type, kind, expiry date, expiration status, limit | `key` (the license string) is never requested; `hwid` is used in memory to name the member, as today |
| IB-2 | grid manager (WAPI) | `GET /wapi/v<ver>/license:gridwide?_return_fields=type,expiry_date,expiration_status,limit` | A | WAPI 2.13.7 object reference, `license:gridwide` | the same fields | `key` |
| IB-3 | grid manager (WAPI) | `GET /wapi/v<ver>/upgradestatus?type=GRID` | B | WAPI 2.13.7 object reference, `upgradestatus`: `current_version` is returned by default; "Parameter type is required for object searches"; values GRID, GROUP, PNODE, VNODE; the object is read-only | `current_version` (the NIOS version), `hotfixes` | member names |

Notes: the WAPI reference was read on a public mirror of the vendor-generated documentation (ipam.illinois.edu/wapidoc);
the grid serves the same pages at `/wapidoc/`, which neXus already fetches. IB-3's exact query string is built from
the documented rules and has no printed example: measure first. Infoblox has no API object for the support contract.

### 4.8 Support contracts: devices first, vendor portals second, manual last (PO question, D8)

The PO asked why contract dates would be typed by hand. They are not, wherever a device or a vendor API can give
them. The order is:

**1. From the device (the reads of this section).**

| Vendor | What the device read gives |
|---|---|
| Check Point Spark | licenses and the contract coverage table with expiry (CP-4) |
| Check Point Gaia | license expiry (CP-1). A contract table in the same output is user-reported only; the first measurement shows whether it is there |
| Palo Alto | license and subscription expiry (PA-1), support level and expiry (PA-2) |
| Fortinet | FortiCare support, firmware and FortiGuard service contract dates (FG-2) |
| Cisco ASA | Smart Licensing registration and compliance (AS-2). The hardware support contract is not on the box |
| ProxySG | license components with expiry, through Management Center (MC-1) |
| Infoblox | license expiry per member and grid-wide (IB-1, IB-2). No maintenance contract object |
| Radware | section 4.6 |

**2. From the vendor's customer portal, where it has a documented API** (searched 2026-11-10). Every one of these
needs a new kind of credential in neXus (a portal API account, not a device account) and sends device serial numbers
to the vendor, which already holds them. Each is its own decision.

| Vendor | API | What it returns | Credential | State |
|---|---|---|---|---|
| Fortinet | FortiCare Registration API v3, `POST https://support.fortinet.com/ES/api/registration/v3/products/list` (Fortinet Technical Tip "API how to retrieve list of registered units") | per registered unit: entitlements and contract terms with start and end dates, warranty | a FortiCloud IAM API user with the "FortiCare Registration API" permission profile; OAuth token from `customerapiauth.fortinet.com` | documented by Fortinet; usable |
| Cisco | Support APIs, "Serial Number to Information" (coverage end date, warranty end date by serial) | per serial: coverage status and end date | Cisco API Console application; access is tied to the customer's support contract programme | exists, but the official page could not be opened without an account; needs someone with the Cisco login to confirm |
| Palo Alto | Licensing API at `api.paloaltonetworks.com` | license dates | API key from the Customer Support Portal | documented for VM-Series and software firewalls only; nothing found for hardware firewalls, where PA-1 and PA-2 already give the dates |
| Check Point | none found for contracts (the Infinity Portal has an API, no documented contracts endpoint) | - | - | not found |
| Infoblox, Broadcom, Radware | none found | - | - | not found |

**3. The manual register**, only for what neither a device nor an API gives: on today's evidence the Cisco hardware
support contract (unless the Cisco API is confirmed), Check Point Gaia support contracts (unless `cplic print` shows
them), Infoblox maintenance, ProxySG hardware support and Radware. A manual date is labelled `OPERATOR_ENTERED` and
never mixed with a device-read date. The hand-built Excel can be imported once to fill it.

### 4.9 Approval state (2026-11-10)

| Group | Rows | State |
|---|---|---|
| No approval needed (parse-scope) | CP-3, FG-1, IB-1, AS-1 | IB-1 is fixed and shipped separately; CP-3 is in the group A lane |
| Grade A | CP-1, CP-2, CP-4, AS-2, MC-1, MC-2, IB-2 | **approved** by the PO in this session ("Yalnız A grubu"); gate rows in `LIFECYCLE_READS_GROUP_A_COMMAND_GATE_ENTRIES.md`; measure-first |
| Grade B, Palo Alto and FortiGate | PA-1, PA-2 (one PAN-OS 11.1 firewall first; PAN-OS 12 after the result), FG-2 | approved by the PO as decisions D2 and D3, relayed by the PO session (section 13) |
| Grade B, the rest | FG-3, FM-1, SG-1, IB-3 | the PO session reads the PO's closing "onayla" as approving them, measure-first; the PO is asked to confirm this in one line before a gate row is written |
| Grade C | PA-3, Radware | not proposed |

No grade B gate row exists yet. None is written before the confirmation above.

## 5. Check Point Live Patch

Facts, all from sk185114 (opened 2026-11-10, re-read for exact wording the same day):

- Supported on R81.20, R82, R82.10 and R82.20: gateways, ClusterXL members, traditional VSX, management servers
  and Multi-Domain servers. Not supported on Spark appliances.
- It patches running processes in memory. A reboot or process restart does not remove it; active patches are
  re-applied automatically.
- The package is delivered by the automatic updater. Its level is the
  `BUNDLE_URGENT_SECURITY_UPDATE_..._AUTOUPDATE Take: <n>` line of `cpinfo -y all`. The current package on
  2026-11-10 is take 30 (2026-10-14).
- `cplp list` shows each patch with a STATUS: `armed` (active in the running process), `ready` (installed, applied
  when the process starts), `reverted` (removed), `jumbofix` (the installed Jumbo is newer than the patch, which is
  therefore not applied), `unsupp` (the patch does not cover the installed build), `nopatch` (none for this version
  or platform), `nolib` (the component is not installed).
- The article's own table maps each package take to dates and CVEs, and the COMMENT column of `cplp list` cites the
  sk article of the CVE a patch covers. The CVE record of a Check Point CVE links the same sk article, so a patch
  row can be tied to its CVE without anyone typing it.

### 5.1 Is a Live Patch a fix? (PO question, D6)

What the vendor's article says, in its own words:

- For: it "closes critical vulnerabilities in the field by applying targeted, in-memory fixes"; a live patch is "an
  in-memory fix designed to protect customers immediately against a critical vulnerability".
- Against: CPLP "is a built-in mitigation delivery service"; "It does not replace the Hotfix or Jumbo Hotfix
  Accumulator"; the Jumbo remains "the recommended long-term remediation option".
- The one concrete consequence: "CPLP retains live patches for 12 weeks", and the customer is asked to install the
  fixing Hotfix or Jumbo within that time. An active protection is never removed. But once a patch has aged out of
  the package, a gateway whose installed Jumbo does not fix that CVE blocks every newer package: it keeps what it
  has and **receives no new live patches** until the fixing Jumbo is installed.

There is no statement that a Live Patch is lost on reboot or protects less. So the earlier wording "mitigated,
permanent fix pending" is dropped, as the PO decided: an active Live Patch for a CVE is shown as **Fixed (Live
Patch)**. The 12-week rule is kept as what it is, a deadline, not a vulnerability state (section 5.3).

### 5.2 Verdicts

What neXus stores: the package take (CP-3, no new command) and one row per patch with its status (CP-2, approved).
What the patch-level check shows: `R81.20 · Jumbo take 119 · Live Patch package take 30 · 4 armed, 0 ready`.

For one device and one Check Point CVE, first match wins:

| Evidence | Verdict | Colour |
|---|---|---|
| Jumbo take is at or above the take whose release notes list the CVE | `FIXED` (Jumbo) | green |
| `cplp list` has a patch that cites the CVE's sk article, STATUS `armed` | `FIXED_BY_LIVE_PATCH` | green |
| the same patch with STATUS `jumbofix` | `FIXED` (the patch itself reports that the installed Jumbo carries the fix); if the take comparison disagrees, `INCONSISTENT` is shown instead | green / amber |
| the same patch with STATUS `ready` | `LIVE_PATCH_NOT_ACTIVE` (installed; applied when the process starts) | amber |
| the same patch with STATUS `nolib` | `NOT_APPLICABLE` (component not installed) | grey |
| the same patch `reverted`, `unsupp` or `nopatch` | `AFFECTED` | red |
| no `cplp list` result for the device yet, package take at or above the take that introduced the patch | `LIVE_PATCH_PACKAGE_PRESENT` (per-patch state not read yet) | amber |
| a CVE with no Live Patch, Jumbo take below the fixing take | `AFFECTED` | red |

### 5.3 The Jumbo deadline

For every `FIXED_BY_LIVE_PATCH` finding neXus also shows "Jumbo take N due by <date>", where the date is the Live
Patch release date from sk185114 plus 12 weeks. After that date the finding stays `FIXED_BY_LIVE_PATCH`; what changes
is a separate warning on the device: "may no longer receive new Live Patches (sk185114)".

To be checked with the first measurement, not asserted: the first Live Patch was released on 2026-07-08, so its 12
weeks ended on 2026-09-30. 47 of the 55 R81.20 gateways run Jumbo take 120 or lower. If their Jumbo does not carry
that fix, their package take may be stuck below the current take 30. CP-3 shows the package take per device, and a
device whose take is behind the current one is flagged.

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
| Cisco openVuln API | not used (PO decision D5: public bulletins only) | - | - | - | - |
| Check Point | CVE records published by Check Point as CVE Numbering Authority, the sk article of each CVE, the Jumbo release notes, sk185114 for Live Patch | CVE JSON 5 from the CVE Program; no Check Point feed was found | free text: in CVE-2024-24919 the affected version is one sentence, not a range | in the sk article and the Jumbo release notes | advisory arrives automatically; neXus proposes the fix rule from the Jumbo release notes and sk185114, and a `security_admin` confirms it with one click (section 6.4) |
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

### 6.4 Verdicts, proposed fix rules and disposition

Per device and advisory: `AFFECTED`, `FIXED`, `NOT_AFFECTED`, `NOT_EVALUABLE`, `NEEDS_REVIEW`, plus the Check Point
Live Patch verdicts of section 5.

**Why a fix rule exists at all (PO question, D7).** To decide "is this device fixed" neXus needs one fact per
advisory: from which version on the fix is present. Palo Alto and Fortinet (and Cisco, to be confirmed) publish that
fact in a form a program can read, so neXus decides alone. Check Point publishes it as a sentence in an article and
as a row in the Jumbo release notes. Something has to turn that into "fixed from Jumbo take N". The first draft had a
person type it. That is replaced:

**neXus proposes, one click confirms.** For every advisory without a machine-readable fixed version, neXus builds
the fix rule itself from the vendor's official pages and shows it with the source lines it was read from:

| Vendor | What neXus reads to build the proposal |
|---|---|
| Check Point | the CVE record (names the sk article); the Jumbo release notes of each version (`sc1.checkpoint.com/documents/Jumbo_HFA/<version>/All_Resolved_Issues.html`: one table per take with ID, product, description; a security fix names its CVE and sk article), where the lowest take that lists the CVE is the fixing take; the sk185114 table for the Live Patch package take and date |
| Cisco | the CSAF file; if it lacks the first fixed release, the advisory page's fixed-release table |
| Infoblox, Broadcom, Radware | the CVE record and NVD ranges; the vendor advisory page where it is public (section 4.6 and U6) |

A `security_admin` confirms the proposal with one click, or corrects it. A proposal is applied only after that
confirmation; until then the advisory reads `NEEDS_REVIEW` with the proposed verdict shown next to it. Every rule
records its source URLs, who confirmed it and when, and is audited. If a page cannot be parsed, neXus says so and
proposes nothing; it never guesses a take. Web pages change their layout, so each page parser has a self-check (for
example: the newest take on the page must not be lower than the last one seen) and fails closed.

**Disposition.** A `security_admin` can mark a finding `ACCEPTED_RISK`, `NOT_EXPOSED_BY_CONFIGURATION`,
`MITIGATED_OTHERWISE` or `PLANNED` (with a due date), with a reason and an expiry (approved, D10). The verdict
itself does not change; the disposition is shown next to it and is audited. It concerns managed devices only and has
nothing to do with the scan gates for the neXus product images.

Clusters: each member is judged alone; a cluster row shows the worst member.

### 6.5 How the feeds get in, and how often

**Decision (D4, PO 2026-11-10): online, from the vendors' official pages, through the corporate proxy**, on two
conditions the PO set: the method is defined, and the source can be parsed. The offline bundle stays as the fallback
and uses the same parsers.

The fetcher is a separate, small workload, `ui2-advisory-fetch`, so that nothing that can reach the internet holds a
device credential (finding F7):

- **No secrets, no devices.** It does not mount the credential-store key or any other neXus key, has no database
  account, and its network policy allows only DNS, the corporate proxy, and one internal neXus endpoint.
- **Read-only, fixed targets.** GET requests only, no request body, to a host allow-list compiled into the image
  (table below). No request carries a device version, model, serial or name (principle 1). The same host list is
  given to the proxy team, so the proxy can enforce it too.
- **One way in.** It hands each fetched document to `ui2-service` through an internal ingest endpoint, with its own
  token. `ui2-service` treats the content as untrusted: schema validation, size caps, text never rendered as HTML.
  The fetcher can write advisories in; it can read nothing out.
- **Own switch and schedule**, independent of every other schedule (collection schedules stay off in development
  mode).

The container principle (`PO_DECISION_RECORD_2026_11_05_ANALYSIS_CONTAINER_DIRECTION.md`) asks for a measured need
before a new container and does not list a trust boundary among its reasons. This workload exists for exactly that
reason, on the PO's decision D4; the decision record that freezes this design must say so explicitly.

Defined method per source:

| Source | Host | What is fetched | Parser | State on 2026-11-10 |
|---|---|---|---|---|
| Palo Alto advisories | `security.paloaltonetworks.com` | `/rss.xml`, then `/json/<CVE>` per item | CVE JSON 5 | fetched; parseable |
| Fortinet PSIRT | `filestore.fortinet.com` | `/fortiguard/rss/ir.xml`, then the CSAF file each item links | RSS, CSAF 2.0 with text-encoded ranges | fetched; parseable; an empty product tree goes to review |
| Cisco advisories (D5: public bulletins only) | `www.cisco.com` | `/.well-known/csaf/index.txt`, then the ASA-related files | CSAF 2.0 | index fetched; version listing to be confirmed (U8) |
| Check Point | `support.checkpoint.com`, `sc1.checkpoint.com` | sk185114; the Jumbo release notes per version; the sk article of a CVE | HTML tables | fetched; R81.20 release notes confirmed parseable, R82 to be confirmed |
| Check Point, Infoblox, Broadcom, Radware CVE records | CVE Program (`cveawg.mitre.org` or the `cvelistV5` repository) | records by vendor | CVE JSON 5 | fetched for one Check Point record |
| Known-exploited flag | `www.cisa.gov` | the catalog JSON | JSON | the flag is also inside the CVE record |

The last two rows are official public registries, not vendor pages. The PO's condition names vendor pages, so they
are listed as decision D14. Without them, Check Point CVEs are discovered from the Jumbo release notes and sk185114
only, and there is no "exploited in the wild" flag.

Needed from the enterprise side before this can run: the proxy address and port, how the proxy authenticates a
client (none, user and password, or Kerberos), whether it inspects TLS (then its CA must be trusted by the fetcher),
and the allow-list request for the hosts above.

Refresh cadence:

| What | When |
|---|---|
| Device facts (version, Jumbo take, Live Patch, licenses) | every inventory run; no schedule of their own |
| Advisory sources, online | once a day, with conditional GET and a low request rate |
| Offline bundle (fallback) | on demand, uploaded by a `security_admin` |
| Re-evaluation | after every feed load and every inventory run (a database computation; no device contact) |
| Feed age shown on screen | amber after 7 days, red after 30 days; red also sets every "no findings" to `NOT_EVALUATED` |

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
| `advisory_fix_rule` | proposed or confirmed rule | advisory, product key, train, fixed version or Jumbo take, Live Patch sk reference, Live Patch package take and release date, state (`PROPOSED`, `CONFIRMED`, `REJECTED`), source URLs and the lines read, proposed_at, confirmed_by, confirmed_at | built by neXus, confirmed by a `security_admin`; audited |
| `advisory_feed_run` | load | source, mode (`BUNDLE`, `ONLINE`), bundle hash, counts, outcome, actor, loaded_at | drives the feed-age display |
| `device_vulnerability_finding` | device and advisory | verdict, basis, observed version and patch level, feed run, evaluated_at | recomputed; history kept for trend (D12) |
| `vulnerability_disposition` | finding or advisory scope | disposition, reason, due date, expiry, actor | audited |

The existing `infoblox_grid_summary.licenses` stays as it is for the grid summary panel; the Lifecycle projection
reads `device_license_item` for every vendor, Infoblox included. `LifecycleProjection.licenses` loses its
Infoblox-only branch.

## 8. Where it runs, permissions, and the view

**Runtime.** Device reads are extra steps of the existing inventory capability in the worker. Advisory fetching is
the separate `ui2-advisory-fetch` workload of section 6.5. Matching and the
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
   (`NEEDS_REVIEW` first for a `security_admin`, with the proposed fix rule and a confirm button). Opening a row lists the devices with their verdict and basis.
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
| S0 (running) | Infoblox expiry fix (F2), shipped on its own; group A reads behind a switch, measure-first (shape only, nothing stored); Live Patch package take from `cpinfo` (CP-3) | the approved group A literals, one device per platform first | medium | low: off by default; cannot change an inventory result |
| S1 | Advisory store; `ui2-advisory-fetch` with the offline fallback; comparators and matcher for Palo Alto, Fortinet and Cisco ASA; Check Point advisories with proposed fix rules; view with the four checks | none new | large: about five lanes (schema and repositories, comparators and matcher, fetcher and parsers, view, report) | medium: no device impact; the risk is a wrong verdict. Countered by the oracle of section 6.6, fixtures shaped like real advisories, and `NOT_EVALUABLE` as the default |
| S2 | Parsers and storage for the group A reads, written from the measured shapes; Live Patch verdicts and the Jumbo deadline; licenses on the view | none new | medium per vendor | low |
| S3 | Grade B reads, measure-first on one device each, as far as they are approved (section 13) | the approved grade B literals | medium | low to medium: new commands on production firewalls; each has a switch |
| S4 | Contract dates from vendor portals where an API exists (section 4.8), the manual register for the rest, notifications for expiring licenses and newly exploited advisories, the report | none | medium | low; a portal API needs its own credential and decision |

S1 gives a vulnerability verdict for 121 of the 134 devices without sending one new command. The other 13
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
- **Outbound traffic carries no estate data and comes from a workload without secrets.** The fetcher of section
  6.5 holds no device credential and no database account, and requests fixed public URLs only.
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
| U6 | Radware subscription and license expiry reads; Cyber Controller version and license; full text of Radware advisories; Broadcom advisory format | the gated Radware guides (someone with the portal sign-in), section 4.6; Broadcom portal to be read at S1 |
| U7 | IB-3 query form; whether `upgradestatus` names the NIOS version as the advisories do | one measured read |
| U8 | How Cisco CSAF lists ASA versions and first fixed releases | parser spike on public files; no device involved |
| U9 | Whether Check Point offers any machine-readable advisory feed | not found on 2026-11-10; the HTML pages of section 6.5 are used instead |
| U10 | Live Patch state per virtual system on VSX; behaviour of `cplp list` on the MDS | one measured read each |
| U11 | The corporate proxy: address, client authentication, TLS inspection and its CA, host allow-list procedure; whether HOST-A may reach it | enterprise network team, through the PO |
| U12 | Rate limits and terms of use of the vendor feeds | none stated on the pages read; the bundle builder uses conditional GET and a low request rate |

## 12. Decisions

| # | Decision | State on 2026-11-10 |
|---|---|---|
| D1 | The grade A literals | approved ("Yalnız A grubu") |
| D2 | Palo Alto reads on firewalls | approved: one PAN-OS 11.1 firewall first, PAN-OS 12 after the result |
| D3 | Fortinet: per FortiGate or through FortiManager | approved: per FortiGate first |
| D4 | Advisory intake | online from the vendors' official pages through the corporate proxy, if the method is defined and the source parseable (section 6.5); offline bundle as fallback |
| D5 | Cisco source | public bulletins (CSAF); no openVuln credentials |
| D6 | How a Live Patch counts | an active Live Patch for a CVE is `FIXED_BY_LIVE_PATCH`; the vendor's 12-week Jumbo rule is shown as a deadline (section 5) |
| D7 | Fix rules where the vendor has no machine-readable fixed version | neXus builds the rule from the vendor pages; a `security_admin` confirms with one click (section 6.4). Proposed in answer to the PO's question; awaits a yes |
| D8 | Support contracts | devices first, vendor portal APIs second, manual register last (section 4.8). Whether to use the Fortinet portal API (and Cisco's, once confirmed) is open: it needs a portal API account |
| D9 | The view replaces Lifecycle as `Devices > Lifecycle and security` | approved |
| D10 | Dispositions, `security_admin` only, with an expiry | approved |
| D11 | Radware and Broadcom documentation | searched again (section 4.6) |
| D12 | Finding history | approved: 400 days |
| D13 | Start the no-new-command work | under way (slice S0); S1 follows the contract freeze |
| D14 | May the fetcher also read the two official public registries (the CVE Program and the CISA known-exploited catalog), which are not vendor pages? | open; recommended yes: they supply the Check Point CVE list and the "exploited in the wild" flag |
| D15 | The remaining grade B rows (FG-3, FM-1, SG-1, IB-3) | awaiting the PO's one-line confirmation (section 4.9) |

## 13. Product Owner answers so far

| Date | Asked | Answer (verbatim) | Recorded effect |
|---|---|---|---|
| 2026-11-10 | The Turkish summary of this document: the parse-scope rows, the seven grade A literals, the seven grade B literals, the decisions of section 12, and whether to fix finding F2 now | "ok abi çalıştırtırabilsin aynı zamanda da hatayı düzelt" | **F2 / IB-1:** fixed on branch `fix/infoblox-license-expiry-1110` (parser reads the WAPI timestamp; the same read also returns `expiration_status`; a permanent license is `PERMANENT`), shipped through the normal queue. **Device reads:** the answer allows neXus to run the proposed reads but names no row or group. No gate row is authored from it. The PO is asked which rows it covers: the grade A literals only, or the grade B measure-first reads as well |
| 2026-11-10 | "Which rows does the approval cover?" (options: grade A only; grade A and B) | "Yalnız A grubu" (grade A only) | **Approved, exactly as written in section 4:** CP-1, CP-2, CP-4, AS-2, MC-1, MC-2, IB-2, with the forms section 4.1 names for a VSX gateway and a management server. Gate rows: `LIFECYCLE_READS_GROUP_A_COMMAND_GATE_ENTRIES.md`. **Not approved:** every grade B row (PA-1, PA-2, FG-2, FG-3, FM-1, SG-1, IB-3) and every grade C item; they stay `NOT_APPROVED` |
| 2026-11-10 | The open decisions of section 12, asked in the PO session and relayed to this session by it | "Nexus proxy üzerinden internete çıkabilir bununla ilgili metod belli ise , ve parse edebilyorsa üreticinin resmi sayfalarından internete çıkabilir D4 için, d5, herkese açık bülten, d6, neden hafifletildi kalıcı düzeltme diyoruz ? livepatch verisini okumak 2 komut. bunu parse etmek de çok kolay, direk fixlendiğini göremiyor muyuz açıkların ? d7, anlamadım ? d8,bunu anlamadım neden elle giriyorum ? Portalden kontratları çekemez miyiz d9, olur d10, olur d11, neden bulamadığını anlamadım KB'ler ve guideler açık olmalı d12, olur d2, onayla d3,onayla onayla" | D2, D3, D4, D5, D9, D10, D12 decided as section 12 states. D6: the design now shows an active Live Patch as fixed (section 5.1 gives the vendor's wording and the one concrete consequence). D7 and D8: answered in sections 6.4 and 4.8. D11: section 4.6. The closing "onayla" is read by the PO session as approving the remaining grade B rows; this session records that reading and asks the PO to confirm it directly (D15) |

The approval covers the literals. It does not approve a schema or the rest of this draft. Each approved read is first
sent to one device per platform with only its masked output shape logged (section 10); storage and parsing follow
from the measured shapes.

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
| Radware REST | webhelp.radware.com/vision/REST/10_11_0/index.html and 10_3_0 (read in full); support.radware.com knowledge-base articles (partial without sign-in) |
| Vendor portal contract APIs | Fortinet Technical Tip "API how to retrieve list of registered units" (community.fortinet.com); developer.cisco.com/site/support-apis/docs (intro only); docs.paloaltonetworks.com VM-Series Licensing API |
| Check Point Jumbo release notes | sc1.checkpoint.com/documents/Jumbo_HFA/R81.20/All_Resolved_Issues.html (first part read: one table per take, CVE and sk named per security fix) |
| Infoblox WAPI | ipam.illinois.edu/wapidoc/objects/member.license.html, license.gridwide.html, upgradestatus.html (public mirror of the vendor-generated WAPI 2.13.7 reference) |
| Infoblox in NVD | services.nvd.nist.gov/rest/json/cves/2.0 with `virtualMatchString=cpe:2.3:o:infoblox:nios` (fetched) |
| CVE Program bulk data | github.com/CVEProject/cvelistV5 |
| Known exploited catalog | cisa.gov known-exploited-vulnerabilities catalog (the JSON feed returned 403 to the fetch tool; the flag was verified through the CVE record above) |
