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

## Implementation record (2026-11-10)

- Branch `feat/lifecycle-reads-group-b-1110` (on top of the group A branch): the seven rows are seeded by V199 and
  mirrored in `gate_registry_fixture.yaml`; the reads use the group A switch and shape mechanism. All paths run in
  the worker.
- Palo Alto guard: the same rule as the switched firewall reads (`PanFirewallCommandSwitches`, mode `BELOW_12`),
  applied to the version the `type=version` read of the same run returned. Unknown, unparsed or 12 and later:
  `SKIPPED:pan_os_12_or_unknown`. Panorama: `SKIPPED:panorama`.
- FortiGate: on a multi-VDOM unit the two reads are sent inside `config global`, once each; there is no second
  attempt in another context. A refusal reads `ERROR:CliRefusal`.
- FortiManager: the read uses the SSH session the inventory already opens for `get system interface`. Where that
  session is not opened, the read is not sent (`SKIPPED:no_ssh_session`).
- ProxySG: the command goes to ProxySG-type devices only, at most `UI2_LIFECYCLE_MC_LICENSE_MAX` per run.
- Shapes: a mixed token is logged as its character-class pattern (`~9-9-9` for a date, `~a9a9` for a serial) or a
  length class; a Fortinet contract code is named only when it is in a closed list, any other upper-case token is
  `U<len>`; XML and JSON shapes name only documented element and field names.
- V199 must be dry-run (BEGIN/ROLLBACK) on the live ui2 database before rollout.

## Amendment A-2026-11-10: Infoblox `upgradestatus` returns the version only on request

**Product Owner approval (chat, 2026-11-10, asked in the PO session and relayed by it to the engineering session).**
Question: "Infoblox sürüm okumasına current_version alanı eklensin mi?" Answer (verbatim): "Evet, ekle".

- **What changes:** entry 7 (`infoblox_upgradestatus_grid`) is sent with one return field. The exact request is
  `GET /wapi/v<ver>/upgradestatus?type=GRID&_return_fields=current_version`.
- **What does not change:** the object (`upgradestatus`), the search (`type=GRID`), the method, the gate row and its
  canonical key (`GET /wapi/v<ver>/upgradestatus?type=GRID`; as with the other Infoblox rows, return fields are not
  part of the key), the 60 s timeout, no retry, once per inventory, the worker switch. No other field is requested.
- **Why:** the first measurement (`LIFECYCLE_READS_MEASUREMENTS_2026_11_10.md`) showed that the grid answers
  `type=GRID` without `current_version` unless it is asked for.
- **Evidence:** Infoblox WAPI 2.13.7 object reference, object `upgradestatus`, field `current_version` ("The current
  version", a read-only string) and the `_return_fields` option of every WAPI read; read on the public mirror of the
  vendor-generated reference (ipam.illinois.edu/wapidoc/objects/upgradestatus.html), the same pages the grid itself
  serves under `/wapidoc/`. The object allows no create, modify or delete.
- **What is kept:** the version string, as the platform fact `nios_version` of the grid manager's device row. The
  object reference in the answer (it names a member) is not read.
