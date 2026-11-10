# Lifecycle: storing and showing license, contract and Live Patch facts - contract

## Status

**FROZEN - 2026-11-10.** Frozen by the engineering session for implementation, under the Product Owner's request of
2026-11-10 to make these reads usable (relayed by the PO session the same day: "freeze the relevant design section
as a contract, then write the parsers and storage from the measured formats, then the UI"). It freezes sections 3,
4.8, 5.2 and 7 of the draft `LIFECYCLE_SECURITY_CHECKS_DESIGN_2026_11_10.md` as far as they concern licenses,
contracts and Live Patch. Advisory matching (section 6 of the draft) is out of scope and stays a draft.

This contract sends nothing new to any device. The literals stay those of
`LIFECYCLE_READS_GROUP_A_COMMAND_GATE_ENTRIES.md` and `LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md`, behind the
same worker switch, which stays **off**. Switching the reads on for the whole estate is a separate Product Owner
decision. Layouts are those of `LIFECYCLE_READS_MEASUREMENTS_2026_11_10.md`.

## 1. Storage (V200)

| Table | Row | Replaced when |
|---|---|---|
| `device_lifecycle_read` | device and read kind: outcome (`COLLECTED`, `EMPTY`, `UNPARSED`, `REFUSED`, `FAILED`, `SKIPPED`), a reason token, item count, time | every attempt |
| `device_license_item` | one license, subscription, support contract or Smart Licensing fact: category, label, state, expiry date, detail, member, evidence grade | all rows of the device and read kind, on a `COLLECTED` read only |
| `device_live_patch_item` | one Check Point Live Patch row: patch id, status, mode, processes running and total, install time, sk and CVE references | all rows of the device, on a `COLLECTED` read only |

A read that fails, is refused or cannot be parsed does not erase the last good answer; its read row says so and how
old the stored items are. The Live Patch package take stays where it is (`device_platform_facts.content_versions`).

**Never stored, never logged:** license keys, signatures, certificate keys, authcodes, serial numbers, bound
addresses, contract identifiers, account identifiers, company, user and contact fields, and the raw answer itself.
The answer is parsed in memory and dropped. `label` and `detail` are constrained by the schema to a plain character
set; a parser that cannot produce a clean label stores nothing for that line.

## 2. What each read becomes

`state` at write time: `PERMANENT` when the device says the item never expires; `EXPIRED` when the device itself
says so; `VALID` with `expires_on` when it gives a date (the view decides "expired" and "expiring" from the date);
the Smart Licensing states for ASA; `UNKNOWN` when a line is understood but its date or status is not.

| Read kind | Parsed from | Items |
|---|---|---|
| `cp_cplic_print`, `cp_spark_show_license` | the lines after the `Host Expiration Features` header: second token `never` or a date written day-month-year without separators; then the `Contract Coverage:` table rows `# \| ID \| Expiration \| SKU` | one `LICENSE` per license line (label: the first feature token; detail: the feature count); one `SUPPORT_CONTRACT` per contract row (label: the SKU). Host, `CK-` key, contract ID and the continuation lines are not read. A leading `vsenv` context line is skipped |
| `cp_cplp_list` | the rows after the `ID(PATCH:PROC) STATUS MODE PIDS INSTALLED COMMENT` header | one Live Patch row each: patch id, status (closed set, else `UNKNOWN`), mode, running/total, install time, and the `sk<digits>` and `CVE-<year>-<digits>` tokens of the comment |
| `asa_show_license_all` | Smart Agent layout: the `Status:` lines under `Registration:` and `License Authorization:`; module layout: `Compliance status:` and `Overall licensed status:` | one `SMART_LICENSING` item, label `Smart Licensing`: `EVAL_EXPIRED` (with the date after "on" when present), `UNREGISTERED`, `OUT_OF_COMPLIANCE`, `EXPIRED` (authorization expired), `VALID` (authorized or in compliance), `NOT_APPLICABLE` (standby), else `UNKNOWN`; detail: the status words. Account names, UDI, hostname lines are not read |
| `mc_device_license` | `components[]`: `componentName`, `expirationDate`, `validity` | one `LICENSE` per component, evidence `MANAGER_REPORTED`, stored on the Management Center's device row with the managed device's name as member (masked on screen as managed device names are today). Serial and part numbers are not read |
| `infoblox_license_gridwide` | `type`, `expiry_date` (epoch seconds), `expiration_status`, `limit` | one `LICENSE` per entry, member `grid` |
| `pan_license_info` | `result/licenses/entry`: `feature`, `expires`, `expired` | one `SUBSCRIPTION` per entry. `serial`, `authcode` and `description` are not read |
| `pan_support_info` | `result/SupportInfoResponse/Support`: `ExpiryDate`, `SupportLevel` | one `SUPPORT_CONTRACT`. The contact block and links are not read |
| `fgt_update_info_contract` | the lines between `System contracts:` and `Account contracts:`: `CODE,weekday month day year` | one item per code: `SUPPORT_CONTRACT` for `HDWR`, `ENHN`, `COMP`, `SPRT`, `FMWR`, else `SUBSCRIPTION`; label: the code. Nothing outside that block is read |
| `fgt_autoupdate_versions` | each block: its title line, `Contract Expiry Date:`, `Result:` | one `SUBSCRIPTION` per block with a date (label: the title; detail: the result words); a block with `n/a` is `UNKNOWN` |

Measured but not parsed in this contract (their shape line is still logged when switched on): `mc_system_version`
(already the identity read), `mc_show_licenses` (the API read above gives the same components in structured form),
`fmg_fmupdate_dbcontract` (needs a serial-to-device match that is not stored for Fortinet), and
`infoblox_upgradestatus_grid` (does not return the version; the changed request waits for the PO).

Outcome: an answer with at least one item (or, for `cplp list`, a recognised header) is `COLLECTED`; a blank answer
`EMPTY`; an answer without any recognisable line `UNPARSED`; a device refusal `REFUSED`; a timeout or transport
error `FAILED`; a read not sent by rule `SKIPPED`, with the rule's token as reason.

Dates: each parser accepts only the formats listed for it (English month names). A date it cannot read gives
`UNKNOWN` for that item; no date is guessed.

## 3. Worker

The executors hand each answer to a result sink at the point where they already build the shape line. The sink
parses, stores through `DeviceLifecycleRepository` and drops the answer. A parser or storage failure is logged by
exception class only and never changes the inventory result, the shape line, or another read. With the switch off
the sink is never called and nothing is written.

## 4. View

`Devices > Lifecycle` becomes `Devices > Lifecycle and security` (decision D9). Per device:

- **Licenses:** every stored item, for every vendor, with category, expiry, days remaining, state and evidence grade
  (`MANAGER_REPORTED` is labelled). `license_status` is `COLLECTED` when at least one read of the device was
  collected, `NOT_COLLECTED` when no read was ever attempted, otherwise `UNKNOWN` with the read outcomes.
  `support_contract_status` is `COLLECTED` when a support contract item exists. The existing Infoblox member
  licenses stay as they are and are shown together with the grid-wide ones.
- **License problems:** an item that is `EXPIRED`, `EVAL_EXPIRED`, `UNREGISTERED` or `OUT_OF_COMPLIANCE`, or whose
  date has passed, marks the device; the summary counts such devices.
- **Live Patch (Check Point):** the package take, the count of patches by status, and the list with references.
  `ready` rows are shown as "installed, not active". Vulnerability verdicts are not part of this contract.

The masked persona sees member and device names through the existing pseudonym projection; labels, states, dates
and patch identifiers are vendor vocabulary and are shown as they are.

## 5. Tests

- Parser fixtures follow the measured layouts (synthetic values in those layouts), including the lines that must be
  ignored: host addresses, keys, contract identifiers, account blocks, serial and authcode elements. Each parser test
  asserts that none of those values appears in any produced item.
- Repository tests on PostgreSQL: replace-on-collected, keep-on-failure, schema checks rejecting an unclean label.
- Executor tests: switch off: no sink call; switch on: the sink receives the answer once, and a throwing sink
  leaves the inventory outcome unchanged.
- Projection and screen tests for each state, both evidence grades, and the masked persona.

## Amendment A-2026-11-10: the Infoblox NIOS version

Section 2 listed `infoblox_upgradestatus_grid` as measured but not parsed, because the read did not return the
version. With the PO's approval of the changed request (`LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md`, amendment
A-2026-11-10) it is parsed: the `current_version` string of the grid entry is stored as the platform fact
`nios_version` of the grid manager's device row (`device_platform_facts.content_versions`, source read
`infoblox_upgradestatus_grid`) and shown on the device's platform identity panel as "NIOS version". Nothing else of
the answer is read. An answer without a usable version writes nothing and leaves the last recorded version in place.
The device's software version field keeps showing the WAPI schema version; replacing it is not part of this change.
