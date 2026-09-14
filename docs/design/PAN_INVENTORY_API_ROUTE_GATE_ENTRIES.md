# Palo Alto inventory — API route gate entries

## Status

**APPROVED — PRODUCT OWNER, 2026-09-14; COMMANDS RUN BY THE PRODUCT OWNER ON
HARDWARE (SIGNED_OFF); PARSER BINDINGS UNVERIFIED UNTIL THE FIRST LIVE RUN
FROM THE PRODUCT.** This document is the network-device command gate record
(`docs/AI_DEVELOPMENT_PROTOCOL.md`) for the Palo Alto inventory requests
`PO_DECISION_RECORD_2026_09_14E_PAN_INVENTORY_MEASURED_FORMS.md` (FROZEN)
PF-1 fixes. The Product Owner ran every request below against one
multi-vsys PAN-OS firewall on 2026-09-14 and approved the read-only
inventory request set for live runs from the product in that same session.
That hardware run is the command-level approval this document records; the
parser binding against the live transport stays unverified until the first
live run reports its counts. `type=keygen` (credentials in the body) is the
session-establishment exchange this table's `connect` step models — it
carries no command literal of its own and is not gated as a read (C4 §3.3
step 1: `connect` is `NOT_APPLICABLE`). No write of any class is approved
here. PF-2: no per-vsys (`&vsys=<id>`) form is approved or issued — the
unscoped interface read already carries a `vsys` leaf per logical
interface, and the route list is byte-identical whether or not `&vsys=`
is present.

## The entries

Ten items per entry, in the gate's order: 1 why required; 2 class; 3
vendor / platform / shell / context; 4 timeout; 5 retry; 6 maximum
frequency per endpoint; 7 session reuse; 8 unsupported behaviour; 9
secret-bearing output risk; 10 safe telemetry. Every entry is an HTTPS XML
API `type=op` request, key in the request header, never in the URL; shell
context is not applicable (no shell is opened for an XML API call).

| # | Read | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `cmd=<show><system><info/></system></show>` | PF-1 identity/system-info read, unscoped | CLASS_0_READ | PAN-OS firewall, `pan_firewall`, XML API, `pan_xml_api` | 30 s | none | once per device per run | the one key-generation session per run | non-XML or error envelope is a result, not an error (identity `UNKNOWN`, row still created) | none | serial compared locally against the recorded baseline (`MATCH`/`MISMATCH`), never printed; model/version carried |
| 2 | `cmd=<show><high-availability><state/></high-availability></show>` | PF-1 HA state read, unscoped | CLASS_0_READ | PAN-OS firewall, `pan_firewall`, XML API, `pan_xml_api` | 30 s | none | once per device per run | same session | HA disabled → no peer; a result, not an error | none | HA state/peer facts; not persisted by this movement (14C D-4 names no HA column) |
| 3 | `cmd=<show><interface>all</interface></show>` | PF-1 interface read, unscoped (PF-2: no `&vsys=<id>` narrowing) | CLASS_0_READ | PAN-OS firewall, `pan_firewall`, XML API, `pan_xml_api` | 30 s | none | once per device per run | same session | a firewall with no configured interfaces beyond defaults is a result, not an error | none | interface/address counts per vsys and physical port counts; no raw XML persisted |
| 4 | `cmd=<show><routing><route/></routing></show>` | PF-1 route table read, unscoped (PF-2: no `&vsys=<id>` narrowing; byte-identical to the unscoped form) | CLASS_0_READ | PAN-OS firewall, `pan_firewall`, XML API, `pan_xml_api` | 30 s | none | once per device per run | same session | an empty route table is a result, not an error | none | route counts per virtual router/vsys; no raw XML persisted |

## PF-1's exact order

Identity refresh, HA state, interfaces, routes — each once, unscoped, key
in the request body or header, never in the URL (`InventoryReadPlan.PALO_ALTO_BASE_STEPS`).

## Cross-references

- `PO_DECISION_RECORD_2026_09_14E_PAN_INVENTORY_MEASURED_FORMS.md` (FROZEN) — PF-1..PF-3, PM-1..PM-4, PP-1..PP-4, the source of every request above.
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` (FROZEN) §3.2 — the ten-item gate row shape.
- `docs/design/DEVICE_FIRST_CONTACT_COMMAND_GATE_ENTRIES.md` — the sibling document this one follows the shape of.
- `docs/design/PO_DECISION_RECORD_2026_09_13F_COLLECTION_TRANSPORT_AND_IDENTITY_DECISIONS.md` (FROZEN) §1, §4, §5 — warn-and-continue on identity mismatch.
