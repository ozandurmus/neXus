# Check Point gateway backup — command gate entries

## Status

**APPROVED — PRODUCT OWNER, 2026-09-14; SIGNED_OFF FOR THE ALLOWLISTED PILOT
DEVICE ONLY.** This document is the network-device command gate record
(`docs/AI_DEVELOPMENT_PROTOCOL.md`) for the seven literals
`PO_DECISION_RECORD_2026_09_14H_BACKUP_STEP_SCOPE_PILOT_DEVICE_AND_THE_
ASYNCHRONOUS_TRUTH.md` (FROZEN) section 5 authorizes. It follows
`CP_CONFIGURATION_COMMAND_GATE_ENTRIES.md`'s own shape exactly. Every entry
below is scoped to **the one Product-Owner-named pilot device** (BK-1); a
backup job against any other device is refused before any of these commands
is ever issued (`worker.backup.BackupCollectService`'s own allowlist check,
never a gate concern). No write of any class is approved against any other
device by this document.

`14H` section 2 establishes that `add backup local` is asynchronous — it
returns once the job is queued, not once the archive exists — which is why
entry 2 below is a submit, not a blocking wait, and why entry 3 (`show
backup status`) is polled by the job executor rather than issued once. The
`900`-second blocking-exec profile `BACKUP_RECOVERY_CONTRACTS.md` §7.3 point
4 froze for the Python line is not carried here (`14H` §2): it measures
nothing under the asynchronous contract and is superseded for this
executor by the submit-then-poll shape below.

## The entries

Ten items per entry, in the gate's order: 1 why required; 2 class; 3
vendor / platform / shell / context; 4 timeout; 5 retry; 6 maximum
frequency per endpoint; 7 session reuse; 8 unsupported behaviour; 9
secret-bearing output risk; 10 safe telemetry. Every Gaia command is
issued as `clish -c "<command>"` from the Expert landing shell (`14H`
BK-2) — the double-quoted `clish -c` wrapper is part of the literal itself,
not a shell-context annotation layered on top.

| # | Literal | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `clish -c "show diskspace"` (fallback `df -P /var/log`) | BK-6: the free-space precondition read the local heuristic threshold compares against — never presented as a vendor requirement | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none; a failure ends the run before any write is attempted | once per run, before the submit | one session held for the whole run (submit through delete) | **CONFIRM-ON-HARDWARE** (BK-7): vendor documentation does not support the Clish form; the pilot run records which form answered — if Clish fails, the Expert fallback `df -P /var/log` becomes primary and a successor record says so | none expected; output is capacity text | free bytes reported; not persisted beyond the precondition check |
| 2 | `clish -c "add backup local"` | BK-5: the submit half of the asynchronous backup — the archive name is read **only** from this command's own output, never guessed or listed | **CLASS_1_RECOVERY_WRITE** | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 60 s (queued acknowledgement only, per `14H` §2 — never the whole backup duration) | **never auto-retried**: a second submission after this one already queued would start a second concurrent backup (BK-8 also documents that a backup and a snapshot cannot run concurrently; the device's own refusal is reported as the failure reason) | once per run | one session held for the whole run | a vendor refusal (snapshot in progress, an open management client) is reported as the failure reason, never retried (BK-8) | the run's own device confirms no secret is echoed by this command | queued/refused outcome only; the archive name is carried forward for entries 3–7, never logged raw |
| 3 | `clish -c "show backup status"` | BK-5: polled on a fixed interval until a terminal state or the run's own deadline; a run that never reaches terminal ends `OUTCOME_UNKNOWN` and deletes nothing | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s per poll | none — a poll that times out is one more poll, not a retry of a failed attempt; the run's own deadline (not this entry) bounds the total | polled on a fixed interval for the duration of one run only | same session as entry 2 | non-terminal output is a normal mid-run poll result, not an error | none expected; output is status text | terminal/non-terminal token only |
| 4 | `clish -c "show backups"` | governance completeness for `14H` §5's closed seven-literal table; not issued by this movement's own executor flow (which resolves the archive name from entry 2's own output, never a listing) — reserved for a later corroborating-read or listing feature, mirroring the Panorama-own-configuration gate row precedent in `docs/design/PAN_CONFIGURATION_API_ROUTE_GATE_ENTRIES.md` | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 30 s | none | at most once per run, if ever issued | same session as entry 2 | an empty list is a result, not an error | none expected | archive name/timestamp list; not persisted beyond a future corroborating read |
| 5 | SFTP read of the archive path entry 2's own output named | BK-3: the archive is transferred by SFTP, streamed directly into the artefact store, never buffered whole in memory | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, `ssh_exec` (SFTP subsystem of the same session) | 900 s (bounded by `FetchSpec.maxBytes`, not by this timeout alone) | none — a failed fetch fails the run; nothing is deleted on the device (invariant) | once per run | same session as entry 2 | a path other than the exact one entry 2 named is never read (no pattern, no listing) | the archive itself may carry secret-bearing configuration lines — encrypted at rest in the artefact store immediately on receipt | byte count and received-side digest only; the archive's own bytes are never logged |
| 6 | `sha256sum <name>` (device-side digest; not a Gaia/Clish command — issued bare from Expert) | BK-3: the digest computed **on the device** is compared with the digest of the bytes actually received before anything is deleted; on mismatch the run fails, the device-side copy is left in place, and the run says which side differed | CLASS_0_READ | Check Point Gaia, `cp_gaia_gateway`, Expert (bare, not `clish -c` — `sha256sum` is not a Gaia command), `ssh_exec` | 60 s | none | once per run | same session as entry 2 | a digest tool that is not on the device's `PATH` is a failure, not a silent skip; the comparison never proceeds without both sides | none expected; output is a hex digest | the device-side digest only; never the archive bytes |
| 7 | `clish -c "delete backup <name>"` (fallback `rm -f -- <exact path>`) | BK-7: deletion targets only the exact archive name this run created — never a pattern, never a name derived from a listing — and only after both digests (entry 6, and the received bytes') match | **CLASS_1_RECOVERY_WRITE** | Check Point Gaia, `cp_gaia_gateway`, Expert, `ssh_exec` | 60 s | **may be retried once** (the only literal in this table with a non-`none` retry): a failed delete is attempted a second time before the run records `cleanup_failed` | at most twice per run (the one attempt and its one retry) | same session as entry 2 | **CONFIRM-ON-HARDWARE** (BK-7): vendor documentation does not support the Clish form; the pilot run records which form answered — if Clish fails, the Expert fallback `rm -f -- <exact path>` becomes primary and a successor record says so; a failed delete after the retry records `cleanup_failed` and marks the endpoint ineligible, never a silent success | none expected | success/failure token; the exact archive name deleted is carried in-run only, never logged raw |

## Entry 5's gate is `NOT_APPLICABLE`, by C4's own rule

`docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
(FROZEN) §2.3's `sftp_get`/`scp_get` row declares gate resolution
`NOT_APPLICABLE` for "a fetch of a path already produced by a gated prior
step" — exactly entry 5's own shape here (the path fetched is exactly the
one entry 2's own submit output named). `cp_gateway_backup.yaml`'s `sftp_get`
step therefore declares `gate_not_applicable: true` and carries no
`gate_registry` row of its own; entries 1, 2, 3, 4, 6 and 7 each seed
exactly one `SIGNED_OFF` row (the next free migration).

## Executor order (14H BK-5, BK-3, BK-7)

Entry 1 (free space) runs first; entry 2 (submit) runs once; entry 3 (poll)
repeats until a terminal state or the run deadline; on a terminal
`succeeded` state, entry 5 (SFTP fetch) then entry 6 (device-side digest)
run, and only once the device digest matches the digest of the bytes
received does entry 7 (delete) run. Entry 4 is governed but not issued by
this movement's own flow (see entry 4's own note above). A run that never
reaches a terminal state at entry 3 ends `OUTCOME_UNKNOWN` and never reaches
entries 5–7 — nothing is deleted on the device.

## Cross-references

- `docs/design/PO_DECISION_RECORD_2026_09_14H_BACKUP_STEP_SCOPE_PILOT_DEVICE_AND_THE_ASYNCHRONOUS_TRUTH.md` (FROZEN) — BK-1..BK-18, section 5's own table, the source of every literal above.
- `docs/design/PO_DECISION_RECORD_2026_09_14I_ARTEFACT_LOCATION_OPERATOR_RETRIEVAL_DEVIATION_AND_MANAGEMENT_SERVERS.md` (FROZEN) — AL-1..AL-4, DV-1..DV-4, OR-1..OR-5.
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` (FROZEN) §2.3, §3.2 — the ten-item gate row shape and the `sftp_get`/prior-step rule.
- `docs/design/CP_CONFIGURATION_COMMAND_GATE_ENTRIES.md` — the sibling document this one follows the shape of.
- `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.3, §7.7, §7.8 — the earlier, blocking-exec-era gate rows (`rb3b_add_backup_local`, `rb3b_freespace_read`, `rb3b_delete_backup_local`) this document does not edit or reuse: their canonical command keys are single-quoted (`clish -c 'add backup local'`) and, for the submit/delete pair, timed for the superseded 900-second blocking profile; this document's own double-quoted literals (`14H` BK-2) are distinct canonical keys and seed distinct, new `gate_registry` rows rather than colliding with or replacing them.
