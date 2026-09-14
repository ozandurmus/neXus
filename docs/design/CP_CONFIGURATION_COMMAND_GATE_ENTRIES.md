# Check Point configuration collection — command gate entries

## Status

**APPROVED — PRODUCT OWNER, 2026-09-14; COMMANDS RUN BY THE PRODUCT OWNER ON
HARDWARE (SIGNED_OFF); PARSER BINDINGS UNVERIFIED UNTIL THE FIRST LIVE RUN
FROM THE PRODUCT.** This document is the network-device command gate record
(`docs/AI_DEVELOPMENT_PROTOCOL.md`) for the Check Point configuration reads
`PO_DECISION_RECORD_2026_09_14G_CONFIGURATION_COLLECTION_MEASURED_FORMS.md`
(FROZEN) CG-1 fixes. It follows `CP_INVENTORY_COMMAND_GATE_ENTRIES.md`'s own
shape (movement NXS-LOCAL-0164) exactly. No write of any class is approved
here.

## The entries

Ten items per entry, in the gate's order: 1 why required; 2 class; 3
vendor / platform / shell / context; 4 timeout; 5 retry; 6 maximum
frequency per endpoint; 7 session reuse; 8 unsupported behaviour; 9
secret-bearing output risk; 10 safe telemetry. Every entry's landing shell
is Expert, issued through `clish -c` (CG-1: unlike the inventory reads,
these are `clish` commands, not raw Expert-shell binaries).

| # | Read | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `clish -c 'show hostname'` | CG-1 identity refresh | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per device per run | one session per device per run | an empty hostname is a result, not an error | none | hostname string; not persisted beyond the identity-refresh read |
| 2 | `clish -c 'show version all'` | CG-1 identity refresh | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per device per run | one session per device per run | a field this parser does not recognize is a result, not an error | none | version string; not persisted beyond the identity-refresh read |
| 3 | `clish -c 'cpstat os -f hw_info'` | CG-1 identity refresh (serial, model) | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per device per run | one session per device per run | a field this parser does not recognize is a result, not an error | none | serial/model fields; not persisted beyond the identity-refresh read |
| 4 | `clish -c 'show configuration'` | CG-1: the one Gaia configuration read this movement collects; no per-virtual-system repetition (Gaia configuration is host-level, measured identical inside `vsenv`, 14G CG-1) | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 60 s | none | once per device per run | one session per device per run, no per-virtual-system repetition | the raw text carries a `#` header whose timestamp changes every read (CG-2: excluded from the canonical hash) | the read carries secret-bearing lines (CG-3 keyword list) — withheld in the sanitized view, encrypted at rest in the artefact store | section/entry counts only; the sanitized view withholds secret-bearing lines; the raw copy is never logged |

## Executor order (14G CG-1)

Entries 1–3 (identity refresh) run first, in that order; entry 4 (the
configuration read) runs last, once per device, never repeated per
virtual system.

## Secret handling (14G CG-3)

Entry 4's sanitized view withholds any `set` line containing one of:
`password`, `passwd`, `secret`, `community`, `auth-key`, `private-key`,
`pre-shared`, `psk`, `credential`, `token` (case-insensitive substring
match) — see `worker.configuration.cp.CheckPointGaiaConfigProcessor`. The
withheld-line count is shown on the Configuration screen; the withheld
lines themselves never reach the sanitized view, only the encrypted raw
artefact.

## Cross-references

- `PO_DECISION_RECORD_2026_09_14G_CONFIGURATION_COLLECTION_MEASURED_FORMS.md` (FROZEN) — CG-1..CG-11, the source of every literal above.
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` (FROZEN) §3.2 — the ten-item gate row shape.
- `docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md` — the sibling document this one follows the shape of.
- `docs/design/PO_DECISION_RECORD_2026_09_13F_COLLECTION_TRANSPORT_AND_IDENTITY_DECISIONS.md` (FROZEN) §1, §4, §5 — one session per device per run; warn-and-continue on identity mismatch.
