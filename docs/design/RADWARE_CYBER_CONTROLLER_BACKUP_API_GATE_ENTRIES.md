# Radware DefensePro backup through Cyber Controller — API gate entries

## Status

**APPROVED — PRODUCT OWNER, 2026-09-24** ("AD useri bu işi yapar": the four calls, run with the existing AD
account's credential-store entry; implemented as V67). **FIELD BINDINGS UNVERIFIED UNTIL THE FIRST MEASUREMENT.**
(Was: DRAFT — PENDING PRODUCT OWNER GATE APPROVAL AND A FIRST MEASUREMENT.) Requested by the Product Owner on
2026-09-24 ("cyber controllerdan alma şansı varsa burdan alalım"). The estate has one Radware Cyber Controller
(reported version 10.13.0-3, build 5) managing several DefensePro appliances. This document records, for
`docs/AI_DEVELOPMENT_PROTOCOL.md`'s network-device command gate, the ten items for each call. **It authorizes nothing
until the Product Owner approves it.** Field bindings are `UNVERIFIED` until the first live run records the real
response shape (memory rule: measurement record before contract).

## Evidence

- Radware Cyber Controller REST API reference **10.3.0** (Sept 2023), webhelp.radware.com/vision/REST/10_3_0 — the
  newest public reference found; no 10.13 reference is published (the 10_13_0 path answers 404). Every endpoint below
  is from 10.3.0; its presence and shape on 10.13 is **MEASURE FIRST**.
- Radware Vision REST reference 5.1.0 carries the same `getcfg` call — the call is stable across Vision 5.x and
  Cyber Controller 10.x.
- Radware's own public tooling (github.com/Radware/DP_config_analyzer, DP-Attack-Analyzer) logs in with a JSON body
  `{"username", "password"}` and calls `getcfg?saveToDb=false&includePrivateKeys=...&passphrase=` — supporting,
  not normative.
- Why not the device directly: the direct path (`/dynamic/File/Configuration/ReceivefromDevice`, V64) is documented
  by Radware only for AppDirector; its DefensePro form fields come from a BackBox trail, and DefensePro 8.x removed
  on-device web management. The direct path stays as the fallback for a DefensePro no Cyber Controller manages.

## What the product will do

1. The Cyber Controller is enrolled as a device: vendor `radware`, role `management_server`, transport `https`, a
   credential-store reference to a Cyber Controller account (a dedicated least-privilege account is recommended; the
   role that permits `getcfg` is not documented — MEASURE FIRST).
2. A DefensePro appliance enrolled in neXus whose management address the Cyber Controller lists is backed up through
   the Cyber Controller; otherwise through the V64 direct path.
3. Private keys are included, encrypted with the device's export-passphrase credential (V64's per-device secret),
   exactly as the direct path does. `saveToDb=false` always: the file streams to neXus and nothing is written to the
   Cyber Controller's own per-device backup slots (5–10 per device), so the customer's own backups are never evicted.
4. No lock/unlock call, no configuration change, no call beyond the four below.
5. (PO, 2026-09-24, "Gayet uygun devam".) A DefensePro the Cyber Controller lists is **confirmed** by that listing —
   management-plane evidence, labelled "listed by Cyber Controller" on the device, never presented as a direct read;
   the device's own HTTPS read only when no enrolled Cyber Controller lists it. Calls 1, 2 and 4 serve that confirm.

## The entries

Ten items per entry: 1 why required; 2 class; 3 vendor / platform / context; 4 timeout; 5 retry;
6 maximum frequency; 7 session reuse; 8 unsupported behaviour; 9 secret-bearing output risk; 10 safe telemetry.

| # | Call | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `POST /mgmt/system/user/login` (JSON `username`, `password`) | open a session; the response sets `JSESSIONID` | CLASS_0_READ (authentication) | Radware Cyber Controller, HTTPS, TLS not verified (internal estate, as V64) | 30 s | none | once per job | the job's own session only | non-2xx, or no `JSESSIONID` → `authentication_failed`, nothing further is called | request carries the password (from the store, in memory only); response body not persisted | HTTP status, session obtained yes/no |
| 2 | `GET /mgmt/system/config/itemlist/alldevices` | confirm (identity/reachability) and map a DefensePro's management address to the Cyber Controller's device list | CLASS_0_READ | same | 60 s | none | once per confirm, once per backup job | same session | non-2xx → confirm failed; the DefensePro not listed → backup falls back to the direct path, stated in the job reason | device names and addresses (CLASS 1 identity); parsed to counts and a match yes/no; never persisted raw | device count by type, target listed yes/no |
| 3 | `GET /mgmt/device/byip/{deviceIp}/config/getcfg?saveToDb=false&includePrivateKeys=true&passphrase=<from store>` | the DefensePro configuration file | CLASS_0_READ | same; `{deviceIp}` = the DefensePro's enrolled management address | 600 s | none | once per device per backup job | same session | non-2xx, empty or HTML body → backup failed with the HTTP status; no retry | **secret-bearing** (the configuration, private keys encrypted with the passphrase): streamed into the encrypted artefact store, digested, never logged; the passphrase travels in the query string over TLS and is never logged | bytes, SHA-256, HTTP status |
| 4 | `POST /mgmt/system/user/logout` (V68; V67 carried `/mgmt/system/config/itemlist/systemuser/logout`, which answered 405) | close the session | CLASS_0_READ | same | 30 s | none | once per job, always (also after a failure) | same session | failure ignored (the session expires on its own) | none | status |

## MEASURE FIRST (the first live run records, names and counts only)

1. That calls 1–4 exist on 10.13 with these paths, and the login body field names.
2. The `alldevices` response shape: where the management address and device type sit.
3. The `getcfg` response: content type, a text configuration or an archive, typical size; that the passphrase in the
   query string is accepted as documented.
4. Which Cyber Controller role is enough for calls 2–3.

## Measurement record — first live run, 2026-09-24 (Cyber Controller 10.13.0-3 build 5)

Commands and fields exactly as run (worker log `[CYBER_CONTROLLER]`, names and counts only):

- Call 1, `POST /mgmt/system/user/login`, JSON body `{"username", "password"}`: **HTTP 200, `JSESSIONID` set.** The
  AD account (credential store) is accepted.
- Call 2, `GET /mgmt/system/config/itemlist/alldevices`: **HTTP 200, 1,261 bytes.** Field names across the objects:
  `children, deleted, deviceVersion, formFactor, highAvailabilityPriorityEnum, managementIp, name, ormId, parentOrmId,
  status, supportTemplate, treeType, type`. So the device list carries per device: name, management address, type,
  software version, form factor, status and an HA priority — the discovery tree and inventory fields can bind to
  these once a value sample (types and enum values only) is recorded.
- Call 4, `POST /mgmt/system/config/itemlist/systemuser/logout`: **HTTP 405.** Corrected in V68 to the reference's
  "Server Logout", `POST /mgmt/system/user/logout` (the earlier path was a transcription error on my part, not a
  10.13 change).
- Call 3 (`getcfg`): not yet run — needs a DefensePro backup.

## Not in this document

- **Cyber Controller's own backup** (`system backup config|full create/export` on its CLI, SFTP export). Documented
  CLI-only; it needs an SSH session to the Cyber Controller's restricted CLI and an SFTP destination. A separate
  entry once the DefensePro path is measured.
- A "DefensePro on the Cyber Controller, not in neXus" warning (as for MDS/Panorama) — call 2 makes it possible; a
  later UI item.
