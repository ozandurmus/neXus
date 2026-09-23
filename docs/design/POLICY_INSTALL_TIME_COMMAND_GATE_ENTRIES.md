# Last policy installed — command gate entries (Check Point, Palo Alto)

## Status

**APPROVED — PRODUCT OWNER, 2026-09-23 ("onaylıyorum"); FIELD BINDINGS UNVERIFIED UNTIL THE MEASUREMENT
SAMPLES ARE RECORDED BELOW.** (Was: DRAFT — PENDING PRODUCT OWNER GATE APPROVAL AND A FIRST MEASUREMENT.) Requested by the Product Owner on
2026-09-23: "cihazlarda config ekranında Last time policy installed bilgisi istiyorum; bunu da ana ekrana
ekleyelim (Overview)". This document records, for `docs/AI_DEVELOPMENT_PROTOCOL.md`'s network-device command gate,
the ten items for each read the feature needs. **It authorizes nothing until the Product Owner approves it.** Every
field binding below is `UNVERIFIED` until the first measurement run records the real output shape (memory rule:
measurement record before contract); where this draft names an output field it is the expected shape, marked
MEASURE FIRST, never an established semantic.

## What the product will show

- **Configuration screen, per device (and per virtual system on VSX):** "Policy installed" = the time the gateway
  reports for its currently installed security policy, and the policy name. Shown as observed, with the read time.
- **Overview:** no threshold (Product Owner, 2026-09-23: installs run every weekday evening; he wants the dates, and
  will mail the stale ones from his own nightly query). Overview shows the install dates as a distribution (today,
  yesterday, older) with the oldest date; no "outdated" word.
- **When:** the reads ride the inventory collection, which runs for the whole fleet every evening at 23:00
  Europe/Istanbul (after the weekday evening installs). The stored fields (device, policy name, install time as
  reported and parsed, read time) are what the Product Owner's mail query reads.
- UNKNOWN, never a guessed time, when the read fails or the field is absent.

## Why these reads (and not the management server)

The management server (MDS / Panorama) knows when it *pushed* a policy; the gateway knows what it is *running*.
Evidence law: management-plane observation != direct-device runtime truth. The product therefore reads the time
from the gateway itself, in the same session the inventory collection already opens — no new credential path
(diagnostic-path law).

## The entries

Ten items per entry: 1 why required; 2 class; 3 vendor / platform / shell / context; 4 timeout; 5 retry;
6 maximum frequency per endpoint; 7 session reuse; 8 unsupported behaviour; 9 secret-bearing output risk;
10 safe telemetry.

| # | Read | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `cpstat -f policy fw` | installed policy name and install time on a Check Point gateway | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec`; on VSX once per virtual system after the already-gated `vsenv <VSID>` | 30 s | none | once per device (per VS on VSX) per inventory run | the inventory run's existing session | non-zero exit or no install-time line → UNKNOWN for that device/VS, the run continues | none expected (policy name, install time, counters) — MEASURE FIRST that no rule text or object name beyond the policy name is printed | policy name, install time; raw output never persisted |
| 2 | XML API `type=op`, `cmd=<show><jobs><all/></jobs></show>` | the latest finished commit job (local commit or Panorama push) on a Palo Alto firewall | CLASS_0_READ | Palo Alto PAN-OS firewall, HTTPS XML API, `PanXmlApiTransport`, POST `/api/`, key in the `X-PAN-KEY` header | 30 s | none | once per device per inventory run | the inventory run's existing key | non-2xx, malformed or DOCTYPE-bearing response → UNKNOWN; no commit job in the list → UNKNOWN (the job list is bounded; an old commit may have rolled off — MEASURE FIRST how many jobs are kept) | job entries carry the committing user name — the parser keeps only type, status, finish time; the user name is never stored or shown | latest commit finish time and job type (Commit / CommitAll-push); raw response never persisted |

## MEASURE FIRST (the first live run must record, names and counts only)

1. Check Point: the exact label of the install-time line and its date format on R81.10 / R81.20 (and on the SMB
   1570/1590 appliances, which may land in Clish rather than Expert — capability, not identity); whether `cpstat`
   inside `vsenv` returns the VS's own policy.
2. Palo Alto: whether a Panorama push appears in the firewall's own job list and under which job type; how many
   jobs the list keeps; the finish-time format.
3. For both: that the output carries no secret-bearing field (item 9).

## What is not proposed

No read on the management server, no policy content, no rule counts, no write of any class.
