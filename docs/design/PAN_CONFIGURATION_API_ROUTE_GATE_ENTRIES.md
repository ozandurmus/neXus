# Palo Alto configuration collection — API route gate entries

## Status

**APPROVED — PRODUCT OWNER, 2026-09-14; CALLS RUN BY THE PRODUCT OWNER ON
HARDWARE (SIGNED_OFF); PARSER BINDINGS UNVERIFIED UNTIL THE FIRST LIVE RUN
FROM THE PRODUCT.** This document is the network-device command gate record
(`docs/AI_DEVELOPMENT_PROTOCOL.md`) for the Palo Alto configuration reads
`PO_DECISION_RECORD_2026_09_14G_CONFIGURATION_COLLECTION_MEASURED_FORMS.md`
(FROZEN) CG-4 fixes. It follows `PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md`'s
own shape (movement NXS-LOCAL-0164) exactly. No write of any class is
approved here.

## The entries

Ten items per entry, in the gate's order: 1 why required; 2 class; 3
vendor / platform / shell / context; 4 timeout; 5 retry; 6 maximum
frequency per endpoint; 7 session reuse; 8 unsupported behaviour; 9
secret-bearing output risk; 10 safe telemetry.

| # | Read | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `<show><system><info/></system></show>` | CG-4 identity refresh (serial) — **shares `gate_id pan_inventory_show_system_info`** (identical canonical key: same vendor/scope/shell/transport/literal as `PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md` entry 1); no second row exists for it | CLASS_0_READ | Palo Alto PAN-OS, `pan_firewall`, not applicable, `pan_xml_api` | 30 s | none | once per device per run | the one key-generation session per run | non-XML or error envelope is a result, not an error | none | serial only; not persisted beyond the identity-refresh read |
| 2 | `type=config&action=show&xpath=/config` | CG-4 local active configuration (small, 5 KB measured) | CLASS_0_READ | Palo Alto PAN-OS, `pan_firewall`, not applicable, `pan_xml_api` | 30 s | none | once per device per run | the one key-generation session per run | the read carries secret-bearing leaves — withheld in the view, encrypted at rest | none | recorded as its own read kind (`active`); raw bytes never logged |
| 3 | `<show><config><effective-running/></config></show>` | CG-4 the configuration of record; 11.8 MB measured, stable across reads | CLASS_0_READ | Palo Alto PAN-OS, `pan_firewall`, not applicable, `pan_xml_api` | 120 s | none | once per device per run | the one key-generation session per run | the read carries secret-bearing leaves — withheld in the view, encrypted at rest; response streamed end to end (CG-5), never held whole | none | category index per vsys, `src` provenance counts only; raw bytes streamed straight into the encrypted, gzip-compressed artefact store |
| 4 | `<show><config><merged/></config></show>` | CG-4 the device-local overlay (220 KB measured) | CLASS_0_READ | Palo Alto PAN-OS, `pan_firewall`, not applicable, `pan_xml_api` | 60 s | none | once per device per run | the one key-generation session per run | the read carries secret-bearing leaves — withheld in the view, encrypted at rest | none | recorded as its own read kind (`merged`); raw bytes never logged |
| 5 | `type=config&action=show&xpath=/config` (against Panorama, not a firewall) | CG-7 Panorama's own configuration for the assignment reducer; 84.8 MB measured — **target wiring later, 14F DR-4; no capability issues this literal yet** | CLASS_0_READ | Palo Alto Panorama, `panorama`, not applicable, `pan_xml_api` | 120 s | none | once per Panorama per run | the one key-generation session per run | response streamed end to end, never held whole; document not stored (CG-7) | none | assignment index only (device serial → template stack / templates / device groups); no raw bytes retained |

## Executor order (14G CG-4)

Entry 1 (identity refresh) runs first; entries 2–4 run in that order
(active, effective-running, merged). Entry 5 is recorded for gate
completeness only — Panorama is never a live target at this movement
(14F DR-4 management-server rows are the prerequisite).

## Secret handling (CG-6)

The sanitized index view never carries a secret-bearing leaf (keys,
passwords, pre-shared keys, certificates) — only structural category/entry
counts per vsys and per `src` provenance (`tpl`/`dg`/`shared`/`local`,
CG-7a). See `worker.configuration.pan.PaloAltoConfigStreamProcessor`.

## Cross-references

- `PO_DECISION_RECORD_2026_09_14G_CONFIGURATION_COLLECTION_MEASURED_FORMS.md` (FROZEN) — CG-1..CG-11, the source of every literal above.
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` (FROZEN) §3.2 — the ten-item gate row shape.
- `docs/design/PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md` — the sibling document this one follows the shape of.
- `docs/design/PO_DECISION_RECORD_2026_09_13F_COLLECTION_TRANSPORT_AND_IDENTITY_DECISIONS.md` (FROZEN) §1, §4, §5 — one session per device per run; warn-and-continue on identity mismatch.
