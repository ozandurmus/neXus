# Lifecycle reads, group A - device command gate entries

## Status

**RATIFIED - PO approved the grade A rows exactly as written (chat, 2026-11-10; AGENTS.md amendment 2026-09-26).**
The proposal is `LIFECYCLE_SECURITY_CHECKS_DESIGN_2026_11_10.md` section 4 (a DRAFT; this record, not the draft, is
the authority for the literals below). Asked which rows an earlier general answer covered, the PO answered (verbatim):
"Yalnız A grubu". The approval covers these literals only. Grade B and grade C rows of the proposal are not approved.

## Scope of this record

Eleven gate rows for seven approved reads. A read with more than one row has one row per form the proposal names: the
plain form, the form for a VSX gateway (the existing CF-2 wrapper, `vsenv 0`), and the form for a management server
(typed in the interactive Expert shell, as its `cpinfo` read is since V195).

All rows: action class `read`; sent inside the session or client the inventory run already holds (no new credential,
connection or schedule); once per device per inventory run; no retry; a refused, unsupported, empty or failed read
leaves the check `UNKNOWN` or `UNSUPPORTED` and never fails the inventory.

Each read is behind a worker switch that is off by default (`UI2_LIFECYCLE_READS`, `UI2_LIFECYCLE_READ_DEVICES`). The
first execution of each literal is one device per platform; only the masked output shape is logged. Nothing from
these reads is stored until the parsers are written from the measured shapes.

## Entries

Columns: 1 purpose; 2 class; 3 vendor, platform scope, shell, transport; 4 timeout; 5 retry; 6 frequency; 7 session
reuse; 8 unsupported behaviour; 9 secret-output risk; 10 safe telemetry.

| # | Gate id | Read | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `cp_lifecycle_cplic_print` | `bash -lc 'cplic print'` | installed licenses with expiry (proposal CP-1) | CLASS_0_READ | Check Point, `cp_gaia_gateway`, Expert, `SSH_EXEC` | 60 s | none | once per device per inventory run | the inventory session | empty or unparsed output: license check UNKNOWN | the Host column (an address) and the `CK-` certificate key in Features: parsed past, never stored or logged. `-x` and `-F` are never used | outcome, line count, license count, masked shape |
| 2 | `cp_lifecycle_cplic_print_vsx` | `bash -lc 'vsenv 0 && cplic print'` | entry 1 on a VSX gateway | CLASS_0_READ | as entry 1 | 60 s | none | as entry 1 | as entry 1 | as entry 1 | as entry 1 | as entry 1 |
| 3 | `cp_lifecycle_cplic_print_mds` | `cplic print` | entry 1 on a management server | CLASS_0_READ | Check Point, `cp_multi_domain_server`, Expert, `SSH_EXEC` | 60 s | none | once per management server per inventory run | the inventory session (interactive Expert shell) | as entry 1 | as entry 1 | as entry 1 |
| 4 | `cp_lifecycle_cplp_list` | `bash -lc 'cplp list'` | Check Point Live Patch state per patch (proposal CP-2) | CLASS_0_READ | Check Point, `cp_gaia_gateway`, Expert, `SSH_EXEC` | 60 s | none | once per device per inventory run | the inventory session | command not found or empty output: Live Patch state UNKNOWN | none expected; the PIDS column is not kept | outcome, line count, patch count, status counts, masked shape |
| 5 | `cp_lifecycle_cplp_list_vsx` | `bash -lc 'vsenv 0 && cplp list'` | entry 4 on a VSX gateway | CLASS_0_READ | as entry 4 | 60 s | none | as entry 4 | as entry 4 | as entry 4 | as entry 4 | as entry 4 |
| 6 | `cp_lifecycle_cplp_list_mds` | `cplp list` | entry 4 on a management server | CLASS_0_READ | Check Point, `cp_multi_domain_server`, Expert, `SSH_EXEC` | 60 s | none | once per management server per inventory run | the inventory session (interactive Expert shell) | as entry 4 | as entry 4 | as entry 4 |
| 7 | `cp_lifecycle_spark_show_license` | `show license` | installed licenses and contract coverage on a Quantum Spark (proposal CP-4) | CLASS_0_READ | Check Point, `gaia_embedded`, clish, `SSH_EXEC` | 60 s | none | once per device per inventory run | the inventory session (one clish session) | error or empty output: license check UNKNOWN | host address and contract ID: parsed past, never stored or logged | outcome, line count, masked shape |
| 8 | `asa_lifecycle_show_license_all` | `show license all` | Smart Licensing registration, authorization and compliance state (proposal AS-2) | CLASS_0_READ | Cisco ASA, `cisco_asa_firewall`, privileged EXEC, `SSH_EXEC` | 60 s | none | once per device per inventory run | one session per device per run | error, "Not applicable in standby state" or unparsed output: reported as such, never a failure | Smart Account and Virtual Account names, UDI (product id and serial), hostname lines: parsed past, never stored or logged | outcome, line count, masked shape |
| 9 | `bluecoat_mc_device_license` | `GET /api/devices/{uuid}/license` | license status and component expiry of a managed device, as Management Center reports it (proposal MC-1) | CLASS_0_READ | Symantec, `bluecoat_management_center`, not applicable, `HTTPS` | 60 s | none | once per managed device per inventory run | one client per run | HTTP error or unexpected shape: license check UNKNOWN | `serialNumber` (device and component), `partNumber`, `name`: never stored or logged | outcome, HTTP status, component count, field names |
| 10 | `bluecoat_mc_system_version` | `GET /api/system/version` | Management Center's own version and build (proposal MC-2) | CLASS_0_READ | as entry 9 | 60 s | none | once per inventory run | one client per run | HTTP error: version stays as observed today | none | outcome, HTTP status, field names |
| 11 | `infoblox_license_gridwide` | `GET /wapi/v<ver>/license:gridwide` | grid-wide licenses with expiry (proposal IB-2); return fields `type,expiry_date,expiration_status,limit` | CLASS_0_READ | Infoblox, `infoblox_grid_manager`, not applicable, `HTTPS` | 60 s | none | once per inventory | one client per run | failed object: null, as the other grid summary objects | `key` (the license string) is never requested | count, field names |

`{uuid}` in entry 9 is the device identifier Management Center itself returned from the approved `GET /api/devices`
read in the same run, validated with the existing identifier pattern before it is put in the path.

## Evidence

Official documentation for each literal is listed in `LIFECYCLE_SECURITY_CHECKS_DESIGN_2026_11_10.md` sections 4.1,
4.4, 4.5, 4.7 and 14: the Check Point R81.20 Security Management Administration Guide ("cplic print"), sk185114
("Check Point Live Patch (CPLP)", `cplp list`), the Quantum Spark R81.10.X CLI Reference ("show license"), Cisco's
"ASA Smart License Registration and Troubleshooting" (`show license all`), the Management Center 4.1 API reference
(getdevicelicense, getversion) and the Infoblox WAPI 2.13.7 object reference (`license:gridwide`).

## Not covered

- Parse-scope extensions need no row: the Live Patch package line of the approved `cpinfo -y all` read, and the
  Infoblox `member:license` parser fix.
- Never to be sent, on any platform: `cplic print -x`, `cplic contract put/del`, `contract_util ...`, `fetch license`,
  `autoupdatercli ...`, `show sources license-key`, `licensing update-key`, `licensing request-key`.
