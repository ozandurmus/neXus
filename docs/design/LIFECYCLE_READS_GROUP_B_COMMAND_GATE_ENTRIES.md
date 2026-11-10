# Lifecycle reads, group B - device command gate entries

## Status

**RATIFIED - PO approved the grade B rows as measure-first reads (chat, 2026-11-10; AGENTS.md amendment
2026-09-26).** The proposal is `LIFECYCLE_SECURITY_CHECKS_DESIGN_2026_11_10.md` sections 4.2, 4.3, 4.5 and 4.7 (a
DRAFT; this record, not the draft, is the authority for the literals below). Two answers make up the approval:

- In the PO session, relayed to the engineering session: "d2, onayla d3,onayla onayla" (D2: the Palo Alto reads,
  first on one PAN-OS 11.1 firewall, PAN-OS 12 after the result; D3: the FortiGate contract read first).
- In the engineering session, asked whether the remaining four grade B commands are approved as well, each measured
  on one device (FortiGate `diagnose autoupdate versions`, FortiManager `diagnose fmupdate dbcontract`, ProxySG
  `show licenses`, Infoblox `upgradestatus`): "Evet, dördü de".

The approval covers these literals only, each as a measure-first read. Grade C items are not approved.

## Scope of this record

Seven gate rows. All rows: action class `read`; sent inside the session or client the inventory run already holds
(no new credential, connection or schedule); once per device per inventory run; no retry; a refused, unsupported,
empty or failed read leaves the check `UNKNOWN` or `UNSUPPORTED` and never fails the inventory.

Every read is behind the worker switch of group A (`UI2_LIFECYCLE_READS`, `UI2_LIFECYCLE_READ_DEVICES`), off by
default. The first execution of each literal is one device; only the masked output shape is logged; nothing is
stored until the parsers are written from the measured shapes.

**Palo Alto limit (D2).** Rows 1 and 2 are sent only to a firewall whose stored PAN-OS major version is below 12.
A PAN-OS 12 firewall, a firewall whose version is unknown or unparsed, and Panorama get neither read
(`SKIPPED:pan_os_12_or_unknown`, `SKIPPED:panorama`). Widening this needs the PO's decision after the first result
(`PO_DECISION_RECORD_2026_10_21_PAN_SHOW_SYSTEM_INFO_RETIRED.md` is the background).

## Entries

Columns: 1 purpose; 2 class; 3 vendor, platform scope, shell, transport; 4 timeout; 5 retry; 6 frequency; 7 session
reuse; 8 unsupported behaviour; 9 secret-output risk; 10 safe telemetry.

| # | Gate id | Read | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `pan_lifecycle_license_info` | `<request><license><info/></license></request>` | installed licenses and subscriptions with expiry (proposal PA-1) | CLASS_0_READ | Palo Alto, `pan_firewall`, not applicable, `PAN_XML_API` (`type=op`) | 60 s | none | once per firewall per inventory run | the one key-generation session per run | an error reply or an unexpected shape: license check UNKNOWN | device serial and authcode in the reply: parsed past, never stored or logged | outcome, reply status, entry count, element names |
| 2 | `pan_lifecycle_support_info` | `<request><support><info/></support></request>` | support level and expiry (proposal PA-2) | CLASS_0_READ | as entry 1 | 60 s | none | as entry 1 | as entry 1 | as entry 1 | account, contact and link fields: never stored or logged | outcome, reply status, element names |
| 3 | `fgt_lifecycle_update_info_contract` | `diagnose test update info contract` | FortiCare support, firmware and FortiGuard service contract dates (proposal FG-2) | CLASS_0_READ | Fortinet, `fortigate`, top level or global, `SSH_EXEC` | 60 s | none | once per device per inventory run | one session per device per run | refused (administrator profile without diagnostics) or unparsed: contract check UNSUPPORTED or UNKNOWN | account id, company, user id, serial if printed: parsed past, never stored or logged | outcome, line count, masked shape |
| 4 | `fgt_lifecycle_autoupdate_versions` | `diagnose autoupdate versions` | per engine and database: version, contract expiry, last update (proposal FG-3) | CLASS_0_READ | as entry 3 | 60 s | none | as entry 3 | as entry 3 | as entry 3 | none expected | outcome, line count, masked shape |
| 5 | `fmg_lifecycle_fmupdate_dbcontract` | `diagnose fmupdate dbcontract` | the contract data FortiManager holds for its managed devices (proposal FM-1) | CLASS_0_READ | Fortinet, `fortimanager`, cli, `SSH_EXEC` | 60 s | none | once per inventory | one SSH session per inventory | refused or unreadable: UNKNOWN, logged | device serials, AccountID, Company, UserID, the raw contract data block: parsed past, never stored or logged | outcome, line count, masked shape |
| 6 | `bluecoat_mc_command_show_licenses` | `PUT /api/devices/{uuid}/command "show licenses"` | license components of a ProxySG, read through Management Center (proposal SG-1) | CLASS_0_READ | Symantec, `bluecoat_management_center`, standard mode via Management Center, `HTTPS` | 120 s | none | at most `UI2_LIFECYCLE_MC_LICENSE_MAX` ProxySGs per inventory run | one client per run | status other than success, or unparsed text: license check UNKNOWN | serial number if printed: parsed past, never stored or logged | outcome, HTTP status, line count, masked shape |
| 7 | `infoblox_upgradestatus_grid` | `GET /wapi/v<ver>/upgradestatus?type=GRID` | the running NIOS version of the grid (proposal IB-3) | CLASS_0_READ | Infoblox, `infoblox_grid_manager`, not applicable, `HTTPS` | 60 s | none | once per inventory | one client per run | failed object: NIOS version stays UNKNOWN | member names if present: never stored or logged | count, field names |

Entry 6 uses the 120 s of the existing Management Center command rows (`bluecoat_mc_command_show_version`); the
command text is the fixed literal `show licenses` and nothing else is ever put in the body. `{uuid}` is taken from
the approved `GET /api/devices` read of the same run and validated before it enters the path.

## Evidence

Listed per row in `LIFECYCLE_SECURITY_CHECKS_DESIGN_2026_11_10.md` sections 4.2, 4.3, 4.5, 4.7 and 14. These are
grade B: the command is official, but its exact API form, output or locality is not fully documented. That is why
each is a measure-first read on one device.

## Never to be sent

`request license fetch`, `request support check`, `request batch license refresh`, `execute update-now`, any
`diagnose debug ...` form, `show sources license-key`, `licensing update-key`, `licensing request-key`.
