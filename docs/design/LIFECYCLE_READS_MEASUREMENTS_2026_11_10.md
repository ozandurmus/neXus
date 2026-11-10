# Lifecycle reads - first measurements (2026-11-10)

## Status

**MEASUREMENT RECORD (2026-11-10).** What was sent, to how many devices, and what the masked output shapes showed.
It records evidence; it approves nothing. The approved literals are in
`LIFECYCLE_READS_GROUP_A_COMMAND_GATE_ENTRIES.md` and `LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md`.

## How it was run

- Worker settings for the run: `UI2_LIFECYCLE_READS` = all fourteen read kinds, `UI2_LIFECYCLE_READ_DEVICES` = eleven
  device ids (one per platform, chosen by a count-only database query, standby members preferred),
  `UI2_LIFECYCLE_MC_LICENSE_MAX=1`. A second run named one more Palo Alto firewall with the two Palo Alto kinds.
- Each device's ordinary inventory was started by the masked persona. All twelve inventory jobs ended `COMPLETED`.
- Only the `[LIFECYCLE_MEASURE]` shape lines were read from the worker log. Nothing was stored.
- Afterwards the three settings were removed from `deployment/ui2-worker`. Checked: no `UI2_LIFECYCLE*` name on the
  deployment and none in the running pod's environment.

## Outcomes (counts)

| Vendor | Read | Devices | Answered | Refused or failed | Skipped by rule |
|---|---|---|---|---|---|
| Check Point | `cplic print` (gateway, VSX gateway, management server forms) | 3 | 3 | 0 | 1 (Spark: gets `show license` only) |
| Check Point | `cplp list` (same three forms) | 3 | 3 | 0 | 1 (Spark) |
| Check Point | Spark `show license` | 1 | 1 | 0 | 1 (management server) |
| Cisco ASA | `show license all` | 2 | 2 | 0 | 0 |
| Symantec | Management Center `GET /api/system/version` | 1 | 1 | 0 | 0 |
| Symantec | Management Center `GET /api/devices/{uuid}/license` | 1 | 1 | 0 | 0 |
| Symantec | ProxySG `show licenses` through Management Center | 1 | 1 | 0 | 0 |
| Infoblox | `license:gridwide` | 1 | 1 | 0 | 0 |
| Infoblox | `upgradestatus?type=GRID` | 1 | 1 | 0 | 0 |
| Fortinet | FortiGate `diagnose test update info contract` | 1 | 1 | 0 | 0 |
| Fortinet | FortiGate `diagnose autoupdate versions` | 1 | 1 | 0 | 0 |
| Fortinet | FortiManager `diagnose fmupdate dbcontract` | 1 | 1 | 0 | 0 |
| Palo Alto | `request license info`, `request support info` | 2 | 2 reads on 1 firewall | 0 | 2 reads on 1 firewall (PAN-OS 12 rule) |

Nineteen reads were answered. None was refused, timed out or failed. No inventory result changed.

## What the shapes show

Shapes carry structure only (keywords from a closed list, everything else as a length class or a character-class
pattern), so the notes below describe layout, not values.

**Check Point `cplic print` (Gaia gateway, VSX gateway, management server) and Spark `show license`.**
- A header `Host Expiration Features`, then one line per license: an address, `never` or a date of the form
  day-month-year without separators, feature tokens, and a `CK-` key at the end.
- **A `Contract Coverage:` table follows on all four devices**, with the columns `#`, `ID`, `Expiration`, `SKU` and
  a continuation line listing the covered features. The official `cplic print` page does not mention it; it is now
  measured. Check Point support contract dates can therefore be read from the device.
- On a VSX gateway the output starts with the context line of `vsenv 0`; the parser must skip it.

**Check Point `cplp list`.**
- Header `ID(PATCH:PROC) STATUS MODE PIDS INSTALLED COMMENT`, a rule line, one line per patch, a closing note.
- Status values seen: `armed`, `ready`, `jumbofix`. The management server (R82, Jumbo take 127) shows every patch as
  `jumbofix`. The two gateways show a mix of `armed` and `ready`, and `jumbofix` on the higher Jumbo take.
- The COMMENT column carries sk numbers and, on most lines, **CVE identifiers directly**. A patch row can be tied to
  its CVE without the sk185114 table.
- `ready` rows exist on both gateways: the patch is installed and waits for its process. The parser needs the
  `PIDS` running-of-total value to tell "process not running on this device" from "not yet applied".

**Live Patch package take (from the approved `cpinfo -y all`, no new command).** Stored for the three Gaia devices
of this run: take 30 on all three, the current package, including a gateway on Jumbo take 119. The concern that
gateways on an old Jumbo may have stopped receiving packages is not confirmed on this sample; the fleet-wide answer
comes with the next full Check Point inventory. The Spark has none, as documented.

**Cisco ASA `show license all`.** Two different layouts, as the vendor document describes:
- 4100-series module (standby): `Smart licensing enabled`, `Compliance status: In compliance`, `Overall licensed
  status: Not applicable in standby state`, `No entitlements in use`, serial number, license mode, then the
  licensed-features table.
- Appliance (active): the Smart Agent layout with `Registration: Status`, `License Authorization: Status`, `License
  Usage` entries, product information.
- **Finding:** on the measured active appliance the registration status reads `UNREGISTERED` and the authorization
  status reads `EVAL EXPIRED` with a date; both license usage entries read `EVAL EXPIRED`. This is the device's own
  statement and is exactly what the license check is meant to surface. Whether it is expected (for example an
  offline reservation that reports this way) is for the PO to judge; neXus stores nothing about it yet.

**Symantec Management Center.**
- `GET /api/system/version`: `name`, `version`, `id`, `buildNumber` (all strings).
- `GET /api/devices/{uuid}/license` for a ProxySG: top-level `licenseStatus` and `trialPeriodExpirationDate` are
  null; `components` has 12 entries with `componentName`, `serialNumber`, `productDescription`, `partNumber`,
  `activationDate`, `expirationDate`, `validity`. Eight undocumented field names appeared (masked); all eight
  were null.
- `show licenses` through the command call: status `SUCCESS`; the reply lists the same 12 components with
  `Valid`, an activation date and an `Expiration date` each, plus appliance-level lines. Both routes give component
  expiry; the API route is structured and is the one to parse.

**Infoblox.**
- `license:gridwide`: 4 entries with `type`, `expiry_date` (a number, as with `member:license`), `expiration_status`,
  `limit`.
- `upgradestatus?type=GRID`: 1 entry with state fields (`grid_state`, `upgrade_state`, `ha_status`, `steps`, ...).
  **It does not return `current_version` by default**, contrary to the reading of the WAPI reference. The NIOS
  version needs the same call with `_return_fields=current_version` added. That is a change to the request and is
  put to the PO before it is sent.

**Fortinet.**
- `diagnose test update info contract`: a block of dated diagnostic lines, then `System contracts:` with one line
  per contract code and a date (weekday, month, day, year), then `Account contracts:` and a `Support contract:` line
  with `pending_registration` and `got_contract_info`. Thirty-two contract lines on the measured unit; ten codes are
  on the parser's closed list, the others were masked as unknown codes and must be added by name.
- `diagnose autoupdate versions`: one block per engine or database with `Version`, `Contract Expiry Date`, `Last
  Updated ...`, `Last Update Attempt`, `Result`. Results seen include `No Updates`, `Updates Installed`,
  `Connectivity failure` and `Unauthorized`; some blocks have `Contract Expiry Date: n/a`.
- FortiManager `diagnose fmupdate dbcontract`: one block per managed device (serial and type), with `AccountID`,
  `Industry`, `Company`, a contract count, contract lines and a raw data line. The account, company and user fields
  are present on every block and must never be stored.

**Palo Alto (one PAN-OS 11.1 firewall).**
- `<request><license><info/></license></request>`: `response status=success`, `result/licenses/entry` with
  `feature`, `description`, `serial`, `issued`, `expires`, `expired`, `authcode`. The XML body is valid as written.
  The reply does contain the serial and the authcode; the parser must drop both.
- `<request><support><info/></support></request>`: `response status=success`,
  `result/SupportInfoResponse/Support` with `ExpiryDate`, `SupportLevel`, `SupportDescription` and a contact block,
  plus links. The contact block must not be stored.
- The first firewall chosen was stored as PAN-OS 11.1 but answered `type=version` with a PAN-OS 12 version in this
  run. The rule "only below PAN-OS 12, judged by the version read in the same run" skipped both reads, as designed.
  The stored version of that firewall was five days old.

## Consequences

1. Every approved literal works as written on the measured platforms. No literal needs changing except the
   Infoblox version read (one added return field).
2. Parsers can now be written from measured layouts (slice S2). Fixtures must follow these layouts, not the
   documentation examples.
3. Check Point contract dates come from the device; the manual register is not needed for them.
4. The ASA finding and the full Check Point Live Patch picture are the first real results of the check and should
   be shown to the PO as soon as storage exists.

## Follow-up (2026-11-10): the Infoblox version read

The PO approved adding `current_version` to the return fields of the `upgradestatus` read ("Evet, ekle", relayed by
the PO session; `LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md`, amendment A-2026-11-10). The request is now
`GET /wapi/v<ver>/upgradestatus?type=GRID&_return_fields=current_version`. Its first answer on the grid is recorded
here once it has been measured.
