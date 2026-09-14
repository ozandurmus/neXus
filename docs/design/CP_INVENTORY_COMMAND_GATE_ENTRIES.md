# Check Point inventory — command gate entries

## Status

**APPROVED — PRODUCT OWNER, 2026-09-14; COMMANDS RUN BY THE PRODUCT OWNER ON
HARDWARE (SIGNED_OFF); PARSER BINDINGS UNVERIFIED UNTIL THE FIRST LIVE RUN
FROM THE PRODUCT.** This document is the network-device command gate record
(`docs/AI_DEVELOPMENT_PROTOCOL.md`) for the Check Point inventory reads
`PO_DECISION_RECORD_2026_09_14D_CP_INVENTORY_MEASURED_FORMS.md` (FROZEN)
CF-3 fixes, in the CF-2 login-shell forms those reads are actually issued
in. The Product Owner ran every literal below on real hardware (one
ClusterXL member, two VSX hosts) on 2026-09-14 and approved the read-only
inventory command set for live runs from the product in that same session
("Direk calisalim, baseline olusmus olsun"). That hardware run is the
command-level approval this document records; the parser binding against
the live transport stays unverified until the first live run reports its
counts, exactly as the discovery movement's own gate entries record the
same distinction on the same day. No write of any class is approved here.

## The entries

Ten items per entry, in the gate's order: 1 why required; 2 class; 3
vendor / platform / shell / context; 4 timeout; 5 retry; 6 maximum
frequency per endpoint; 7 session reuse; 8 unsupported behaviour; 9
secret-bearing output risk; 10 safe telemetry. Every entry's landing shell
is Expert (CF-1: reads run as typed, no `clish -c`, no `expert` step).

### Physical context — bare form (non-VSX gateway, CF-2)

| # | Read | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `ip -details -4 addr show` | CF-3 physical interface/address read | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per device per run | one session per device per run | no interfaces configured is a result, not an error | none | interface/address counts; no raw output persisted |
| 2 | `ip -6 addr show` | CF-3 physical IPv6 address read | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per device per run | one session per device per run | no IPv6 configured is a result, not an error | none | address counts; no raw output persisted |
| 3 | `ip -4 route show table all` | CF-3 physical route table read | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per device per run | one session per device per run | an empty table is a result, not an error | none | route counts; no raw output persisted |
| 4 | `cphaprob stat` | CF-3 physical HA/cluster role read | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per device per run | one session per device per run | a standalone gateway reporting no cluster is a result, not an error | none | HA role token; not persisted (14C D-4 names no HA-role column) |
| 5 | `cphaprob -a -m if` | CF-3 physical cluster-VIP read | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per device per run | one session per device per run | no cluster virtual interfaces is a result, not an error | none | cluster VIP counts; never mixed with member addresses |
| 6 | `vsx stat -v` | CF-4 VSX detection by text; always issued bare, first, before any other read's form is chosen | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per device per run | one session per device per run | `VSX is not supported on this platform`, exit 0, is a result, not an error (CF-4) | none | VSID set (digits only); no raw output persisted |

### Physical context — VSX-wrapped form (CF-2: `bash -lc 'vsenv 0 && <read>'`)

| # | Read | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 7 | `bash -lc 'vsenv 0 && ip -details -4 addr show'` | CF-2: the only non-interactive form that resolves `vsenv`, used on a VSX host so a fresh session's landing context is never assumed | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per device per run | one session per device per run | same as entry 1 | none | same as entry 1 |
| 8 | `bash -lc 'vsenv 0 && ip -6 addr show'` | CF-2, VSX host | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per device per run | one session per device per run | same as entry 2 | none | same as entry 2 |
| 9 | `bash -lc 'vsenv 0 && ip -4 route show table all'` | CF-2, VSX host | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per device per run | one session per device per run | same as entry 3 | none | same as entry 3 |
| 10 | `bash -lc 'vsenv 0 && cphaprob stat'` | CF-2, VSX host | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per device per run | one session per device per run | same as entry 4 | none | same as entry 4 |
| 11 | `bash -lc 'vsenv 0 && cphaprob -a -m if'` | CF-2, VSX host | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per device per run | one session per device per run | same as entry 5 | none | same as entry 5 |

### Per virtual system (CF-2/CF-3: `bash -lc 'vsenv <VSID> && ...'`, `<VSID>` the one substituted digits-only parameter)

| # | Read | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 12 | `bash -lc 'vsenv <VSID> && ip -4 addr show && ip -4 route show'` | CF-3 per-virtual-system address/route read; `<VSID>` validated as digits-only before substitution (`InventoryReadPlan.checkPointVsidSteps`), never interpolated from unvalidated input | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per virtual system per run | one session per device per run, VS0 never re-entered | an address-less/route-less virtual system is a result, not an error | none | per-VS interface/address/route counts; no raw output persisted |
| 13 | `bash -lc 'vsenv <VSID> && cphaprob -a -m if'` | CF-3: this read is new against 14C — it is where a virtual system's own cluster addresses are; `<VSID>` validated as digits-only | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per virtual system per run | one session per device per run | no cluster virtual interfaces for this VS is a result, not an error | none | per-VS cluster VIP counts; never mixed with member addresses |
| 14 | `bash -lc 'vsenv <VSID> && cphaprob stat'` | CF-3 per-virtual-system HA role read; `<VSID>` validated as digits-only | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | once per virtual system per run | one session per device per run | same standalone-result rule as entry 4 | none | per-VS HA role token; not persisted (14C D-4) |

## Executor order (14D §3)

`vsx stat -v` (entry 6) always runs first, bare, to decide VSX by text
before any other physical read's form (bare vs. entries 7–11) is chosen;
the per-virtual-system entries (12–14) run last, one VSID at a time, VS0
never re-entered.

## Cross-references

- `PO_DECISION_RECORD_2026_09_14D_CP_INVENTORY_MEASURED_FORMS.md` (FROZEN) — CF-1..CF-4, the source of every literal above.
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` (FROZEN) §3.2 — the ten-item gate row shape.
- `docs/design/DEVICE_FIRST_CONTACT_COMMAND_GATE_ENTRIES.md` — the sibling document this one follows the shape of.
- `docs/design/PO_DECISION_RECORD_2026_09_13F_COLLECTION_TRANSPORT_AND_IDENTITY_DECISIONS.md` (FROZEN) §1, §4, §5 — one session per device per run; warn-and-continue on identity mismatch.
