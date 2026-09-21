# Device first contact — command gate entries

## Status

**APPROVED — PRODUCT OWNER, 2026-09-14, FOR PURPOSE AND CLASS; COMMAND FORMS
UNVERIFIED UNTIL MEASURED.** This document is the network-device command gate
record (`docs/AI_DEVELOPMENT_PROTOCOL.md`) for the reads the enrollment
confirm of `DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` (FROZEN) EC-11 performs,
and for the one further read `PO_DECISION_RECORD_2026_09_14_DEVICE_ADD_ENTRY_
PEER_FOLLOW_AND_CREDENTIAL_STORE.md` (FROZEN) PF-1 adds. The Product Owner
approved each entry's **purpose** and its **class 0 (read)** standing. The
exact command form each entry carries is what the collection measurement
briefs (`CP_COLLECTION_MEASUREMENT_BRIEF_2026_09_13.md`,
`PAN_COLLECTION_MEASUREMENT_BRIEF_2026_09_13.md`) measure; until a form is
confirmed on the live device it is `UNVERIFIED`, is bound at one site in the
implementation, and may be corrected there without reopening this approval.
No write of any class is approved here.

## The entries

Ten items per entry, in the gate's order: 1 why required; 2 class; 3
vendor / platform / shell / context; 4 timeout; 5 retry; 6 maximum frequency
per endpoint; 7 session reuse; 8 unsupported behaviour; 9 secret-bearing
output risk; 10 safe telemetry.

| # | Read | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Check Point identity read — software version (form, corrected 2026-09-21 per this entry's own "may be corrected at this site" clause, matching the pre-Java product's own real-fleet-proven probe `checkpoint/direct_ssh_probe.py`: `show version all`, then `show version`, then `clish -c "show version all"`, then `clish -c "show version"`, each tried on failure/timeout/blank output; the exec channel requests a PTY) | EC-11: the confirm's one identity read; DA-2: the device's facts are read, never typed | CLASS_0_READ | Check Point Gaia, `ssh_exec`, the landing shell (Expert or Clish per `AGENTS.md` Check Point) | 30 s per attempt | none; a failure ends the confirm with its outcome | once per confirm, at most once per operator add action | the one session the confirm opens (T-1 of the discovery contracts, `13F` one-session rule) | non-parseable output → identity `UNKNOWN`, row still created under `13F` §2 | none expected; output is version text | version string carried; no raw output persisted |
| 2 | Check Point HA / cluster role and peer naming (form `UNVERIFIED`: `cphaprob stat`) | PF-1: learn whether the device is a member and which peer it names | CLASS_0_READ | Check Point Gaia, same session as entry 1 | 30 s | none | once per confirm | same session | on a standalone device the command reports no cluster; that is a result, not an error | none expected | member/standalone token and the peer reference; addresses compared locally, never printed |
| 3 | Palo Alto identity read (form `UNVERIFIED`: `type=op`, `cmd=<show><system><info/></system></show>`) | EC-11; DA-2 | CLASS_0_READ | PAN-OS firewall, HTTPS XML API, key in header, credentials in POST body | 30 s | none | once per confirm | the one key-generation session per confirm | non-XML or error envelope → identity `UNKNOWN`, row still created | none expected | serial compared locally against discovery (`MATCH`/`MISMATCH`), never printed; model and version carried |
| 4 | Palo Alto HA state and peer (form `UNVERIFIED`: `type=op`, `cmd=<show><high-availability><state/></high-availability></show>`) | PF-1 | CLASS_0_READ | PAN-OS firewall, same key as entry 3 | 30 s | none | once per confirm | same session | HA disabled → no peer; a result, not an error | none expected | peer serial and peer management address used locally to contact the peer (PF-1); never printed |

## Peer follow is one more contact, not a scan

PF-1's follow-up contact repeats entries 1–2 or 3–4 **once**, against the one
peer the first device named, with the same credential, and stops (PF-3,
PF-5). No entry here authorizes a second hop, an address probe, or any
command outside this table.

## Cross-references

- `DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` (FROZEN) §4, EC-11, EC-12.
- `PO_DECISION_RECORD_2026_09_14_DEVICE_ADD_ENTRY_PEER_FOLLOW_AND_CREDENTIAL_STORE.md` (FROZEN) §1, §2.
- `PO_DECISION_RECORD_2026_09_13F_COLLECTION_TRANSPORT_AND_IDENTITY_DECISIONS.md` (FROZEN) §2 — warn and continue on mismatch.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — the gate's ten items.
