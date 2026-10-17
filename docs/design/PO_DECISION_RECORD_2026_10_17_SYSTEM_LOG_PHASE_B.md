# PO decision record — 2026-10-17: system-log phase B (incremental collection and storage)

**Status: RATIFIED — PO APPROVED (chat, 2026-10-17, about 05:00 Europe/Istanbul).** Recorded by the engineering
session from the Product Owner's explicit chat answer, quoted below, after the read method was explained in chat.

## 1. The Product Owner's words

> "Okuma kısmı, sunucuya çekip tail head orda yapsak nolur daha verimli olur bence. Komutlar ok. Diğer konularda
> önerilerin ok"

## 2. Interpretation (agreed with the PO in chat)

The orchestrator explained that copying whole files from the device on every run would multiply the transfer, and
proposed the efficient form of the PO's idea. The PO's intent, as agreed: **do the cutting on the neXus side and run
nothing for reading on the device.** That means a ranged read from the stored byte offset, not a whole-file copy and
not a `tail | head` shell pipeline on the device. "Komutlar ok" approves the command table. "Diğer konularda önerilerin
ok" takes the document's own recommendation for every remaining open question.

## 3. Decisions

| # | Decision |
|---|---|
| Q1 | The incremental read is an **SFTP ranged read**: the file is opened over the existing SSH session's SFTP subsystem, read from the stored byte offset up to the cap, and closed. Line-boundary trimming, fingerprint (anchor) checks and rotation logic run in neXus. No shell command runs on the device for reading. The earlier stdout/stderr transport problem of the `tail | head` form no longer exists |
| Q2 | All 13 new commands are **APPROVED**, exactly as written: Check Point C1–C8 (C2 as the SFTP ranged read; C5 stays conditional on M5 gate 3) and Palo Alto P1–P5 |
| Q3 | First run takes the last 8 MiB of each live file only, no rotated history, recorded as an `INITIAL_BASELINE` gap |
| Q4 | At the per-device cap the oldest chunks are pruned early, ledgered and shown. New collection does not stop |
| Q5 | The notification type `device_crash_file` is added (amends the FROZEN notification routing contract and its V100 check). Rotation-loss gaps do not notify; they show on screen and in the daily summary |
| Q6 | HCP archive export stays blocked until the Data Sanitizer handles tar.gz |
| Q7 | PAN config-log `before-/after-change-detail` is stored with secret redaction, not dropped |
| Q8 | VSX: VS0 only for now |
| Q9 | `.gz` rotated files: the unread remainder is a reported gap. Nothing is decompressed on the device |
| Q10 | `operation_admin`/`security_admin` may view unmasked lines in the product, audited per view. aiview is always masked |

## 4. The C4 amendment this needs (named part of the decision)

`UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` §2.3 describes `sftp_get` as a whole-file fetch of one path.
The ranged read gives that existing step kind an optional read-only byte range `{start_offset, max_bytes}`, both
computed by neXus. The step-kind set stays closed, `sftp_put` stays refused, and the gate-reference rule is unchanged
(the path was printed by the gated C1 stat in the same run, so the fetch is `NOT_APPLICABLE`). The approval covers this
one purpose, the system-log incremental read, and no other source or capability. The wording is in the phase B
contract §4.3. The edit to the FROZEN C4 text is made by the implementation PR.

## 5. What is not decided here

The parameters marked `PENDING-M1`, `PENDING-M5` and `PENDING-M3M4` (read caps, per-source and per-device budgets, the
2 GiB device cap, rotation mode and sibling naming, PAN page size and bounds, and similar) are **not** set by this
record. They are set from the measurement findings record before implementation freezes them. The PAN vendor
semantics (`seqno` contiguity, `dir=forward`, the `query` syntax) stay **UNKNOWN** until an official documentation
citation is recorded.

Management servers (MDS) stay default off and are not part of this approval. No log data leaves the product
un-anonymised; the raw-evidence law exception of the 2026-10-15 record still applies, unchanged.

## 6. Pointers

- Contract: [DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md](DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md) — §4.3 (the read and the C4
  amendment), §10 (command table), §11 (parameters from measurements), §13 (answers).
- Phase A: [DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md](DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md).
- Earlier record: [PO_DECISION_RECORD_2026_10_15_INTERFACE_MAC_ZONE_AND_SYSTEM_LOGS.md](PO_DECISION_RECORD_2026_10_15_INTERFACE_MAC_ZONE_AND_SYSTEM_LOGS.md)
  (§3: phase B approved in direction; raw-evidence exception).
