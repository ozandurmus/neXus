# Log detection engine, phase P1 — implementation record

**Status: IMPLEMENTATION RECORD (not a contract).** The contract is
[LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md](LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md) (FROZEN) with
[PO_DECISION_RECORD_2026_11_24_LOG_DETECTION_ENGINE.md](PO_DECISION_RECORD_2026_11_24_LOG_DETECTION_ENGINE.md). This record states what P1
built, where it chose within the design, and what stays open. It adds no device command, outbound connection or credential.

## What P1 delivers (design section 10, row P1)

| Design part | Where | State |
|---|---|---|
| Masking-first indexer (8.2) | `SystemLogReadService.scanMasked`, `LogEvidenceExtractor` | Decrypts a chunk in memory through the existing artefact store, masks it with one `LogMasking` session, hands lines to the matcher with their raw byte position. A failed mask is `MASKING_UNAVAILABLE`, never unmasked processing |
| Layer A markers (3.2) | `HardwareMarkers` (list version `markers-p1-1`) | Nine markers, all `PROVISIONAL`. `kern.boot` is an anchor, not a finding |
| Severity (3.1, 7.1) | `LogSeverity` | PAN `severity` weights; syslog weights exist for a stored priority (none is read yet: `cp_messages` has none). A `PROVISIONAL` weight of 1.0 is capped to ATTENTION with label `PROVISIONAL_CAP`; a statistical finding is never CRITICAL |
| Noise control (3.4) | `LogFindingEvaluator` | Dedup per (device, marker, key, day); incident grouping at 10 minutes; chronic (80 % of at least 7 usable days, no rate change); fleet background (half of a cohort of at least 10); hardware exemption |
| Coverage (7.4) | `LogCoverage`, `log_coverage` | Per (device, source, collection job). Sources other than `cp_messages` and `pan_system` are `UNSUPPORTED` |
| Finding record, review (7.2, 7.3) | `log_finding`, `log_finding_action`, `LogFindingReadService` | Layer A fields; acknowledgement is audited and raised again on a severity rise (recorded as a `REOPEN` action); later evidence is a new day's finding, which starts OPEN |
| Storage (8.3) | `V272__log_detection_p1.sql` | `log_detection_settings`, `log_index_state`, `log_event_count`, `log_coverage`, `log_finding`, `log_finding_action`. No free-text column (schema test) |
| Placement (8.1 option a) | `LogDetectionService`, `LogDetectionConfiguration` | In ui2-service, a bounded pass (40 chunks, 2 minutes) every 5 minutes when scheduling is on; chunks of a job are offered once its `system_log_run` rows exist (window closed) |
| Shadow (10) | `log_detection_settings` | The switch is OFF by default. `shadow_until` marks findings as shadow; the panel says so |
| Screens and CLI (9) | `LogFindingsPanel`, `LogFindingChip`, CLI `log-*` commands | Panel above the device Logs panel; budget counters at `/api/v2/log-findings/budget` |

## Choices made inside the design

- **Provenance cap.** Section 7.1 gives CRITICAL to S = 1.0 markers only with `MEASURED` or `VENDOR_DOC` provenance, and section 3.2 keeps
  every marker `PROVISIONAL` until the census and a citation. P1 therefore raises ATTENTION for `hw.edac.ue`, `kern.panic`, `fs.readonly` and PAN
  `critical`, labelled `PROVISIONAL_CAP`. Raising a marker's provenance is a marker-list version change.
- **Slot label.** `hw.edac.*` keeps the label as the key token only when it has the closed form `CPU_SrcID#n_(MC|Ha)#n_Chan#n_DIMM#n`
  (letters, digits, `#`, `_`); any other word becomes a keyed opaque token (`k-` plus 12 hex, HMAC under the privacy key). Equality only; no part is parsed.
- **Process names.** The OOM-killed process name is always a keyed opaque token (`k-` plus 12 hex), never the name: it is device free text, it can carry
  characters outside the `key_token` CHECK (`g++`), and aiview reads `key_token`. Block-device names keep their closed form (`[a-z0-9]{1,16}`).
  Every key the matchers produce fits `^[A-Za-z0-9#_.:-]{0,64}$` (tested), so a line can never make an evidence insert fail.
- **One bad chunk.** `LogDetectionService` isolates each chunk: a failure while reading, extracting or storing is recorded as `UNREADABLE` in
  `log_index_state` (evidence rows of the chunk removed, retried after the 15-minute interval of `pendingChunks`) and the pass continues. Only the
  exception class is logged. The window then has an `UNPARSED_HIGH` / `CHUNK_UNREADABLE` coverage row, never a healthy one.
- **One failed evaluation.** Evaluation is isolated per device-day. A device-day whose evaluation throws (a store or read error) marks the coverage
  rows of the jobs whose evidence lies on that day `EVALUATION_FAILED` / `EVALUATION_FAILED`, so the reader counts the window UNKNOWN and never
  shows "no findings" for it; the pass goes on with the other device-days. Those chunks are INDEXED and no longer pending, so every pass first takes
  up the windows in that state (`failedEvaluations`), writes their coverage again and evaluates their days again. `EVALUATION_FAILED` is a coverage
  state the frozen section 7.4 table does not list; it was added by the second review round (2026-11-24) as an UNKNOWN state with its own reason,
  and V272 (not yet deployed) was changed in place. Residual: a process that dies between the coverage write and the evaluation of a pass still
  leaves the window's coverage written without findings until a later pass touches the day.
- **Review columns.** `upsertFinding` writes the evidence part only; on conflict it never writes `review_state`, `review_by` or `review_at`, so an
  acknowledgement made between the engine's read and its write survives. The one engine path that changes an existing review is `reopenFinding`,
  which sets OPEN (review_by and review_at cleared) and writes the `REOPEN` row in the same audited transaction.
- **Reopen.** Section 7.3 raises an acknowledged finding on a severity rise or on evidence more than 24 hours later. A finding is one per device,
  marker, key token and device-local day (design 3.4 rule 1), so evidence of another day is a new finding that starts OPEN, and evidence of the same
  day cannot be more than 24 hours after the evidence it was acknowledged on. P1 therefore has no time comparison (it could never be true) and
  reopens on a severity rise only; the reopen is stored in `log_finding_action` as `REOPEN` by the system actor `system:log-detection`, audited as
  `log_finding_reopen`. "Score doubles" waits for layer B.
- **Fleet background across passes.** Evaluation runs after a pass has indexed its whole batch and written its coverage, and covers every device
  with evidence on a touched day, not only the devices of the batch. A backlog larger than one pass therefore cannot leave an early device with a
  verdict made on a partial cohort: the later pass that completes the cohort evaluates it again.
- **Day basis.** Findings use the device-local line day. Coverage and the usable-day baseline use the chunk's `log_day` (Europe/Istanbul date of
  collection), because that is what the phase B index stores. The two can differ for a chunk collected just after midnight; the finding takes the
  coverage rows of its day and the next.
- **Cohort.** Devices with an evaluable coverage row for the source on the day (vendor and source, PO decision 10). A cohort under 10 gives no prevalence judgement.
- **Score.** `100 x S x R x C` needs layer B. P1 stores `S` only; `R`, `C` and the score are null in the finding shape.
- **Budget.** `GET /api/v2/log-findings/budget` counts ATTENTION-or-higher findings per device-week and per fleet-day against the PROVISIONAL
  numbers; with no coverage it answers `evaluable: false`, never "within budget". It filters nothing. The window is the 28 days that end today
  (four 7-day weeks, today in the last one).
- **Chips.** `/chips` reads the open findings page by page (1000 per page, at most 50 pages) and answers `truncated: true` with a note when the bound
  is reached, so a per-device count is never silently short.
- **Actions.** The five finding reads (`/api/v2/log-findings`, `/chips`, `/coverage`, `/budget`, `/{id}`) use `system_log_read`, so the aiview action set
  pinned by `AiviewDiagnosticReadOnlyTest` is unchanged (design 8.4: aiview reads findings, never acknowledges). The acknowledgement is the new
  `log_finding_acknowledge` (operation_admin, security_admin) and the switch the new `log_detection_settings_read/write` (security_admin); neither is open to aiview.
- **Audit.** The two operator-written tables carry `trg_audit_*`; the four derived tables are system-job writes listed in
  `AuditCoverageCompletenessTest.NOT_MUTATION_BEARING` with that reason.

## Not built in P1 (by design)

Layer B (catalog, rarity, rate, silence, boot burst, score), layer C cards, suppression and reclassification (7.3), the Devices-list column
(the chip data route and component exist; the list stays unchanged during the shadow period), fleet rare-events view, catalog admin, notifications
(decision 7), P0 census tooling.

## Open items for the next steps

1. The 14-day shadow run is a switch away: `log-detection-set enabled=true shadow_days=14` (security_admin). Nothing is analysed until then.
2. P0 census: marker counts per device, `cp_messages` priority presence and the `cp_fwk_elg` level tokens. Until the census and a citation, no marker leaves `PROVISIONAL`.
3. `kern.boot` and the shutdown-sequence templates need a labelled planned-reboot sample before `card.reboot.unexpected` (P3).
4. The real-environment check of P1 (replay of G1 on the stored pool, shadow volume against the budget, a database check that no new table holds line text) is
   a separate PO-run gate; automated validation here uses a masked synthetic replica of G1 through the real read path.
