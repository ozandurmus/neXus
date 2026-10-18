# PO decision record — 2026-10-18: aiview may run Packet Analysis (masked view unchanged)

**Status: RATIFIED — PO DECISION (chat, question form, 2026-10-18).** Recorded by the engineering session from the
orchestrator's brief. It amends item 6 of
`PO_DECISION_RECORD_2026_10_15_PACKETBREAKER_PORTED_INTO_NEXUS.md` ("`aiview`: masked findings and summaries only")
for the one purpose below, and adds one sentence to the AIView masking law in `AGENTS.md`.

## 1. Decision

The aiview persona (`role:replay_viewer`) is granted `packet_analysis_run`, so the orchestrator can run the first
production analysis itself: create a case, upload captures (and host telemetry), and request an analysis.

- The view stays masked. Every response aiview receives passes through the masked projection, including the
  acknowledgements of the new writes.
- aiview gets **no raw view**. `packet_analysis_raw_view` stays `security_admin` only: no capture download, no engine
  table or report download, no raw field differences, no payload bytes.

## 2. What aiview holds after this change

| Action | aiview | Notes |
|---|---|---|
| `packet_analysis_read` | yes (unchanged) | case list and detail, run, masked summary, detail views, expert report workbook (masked), findings export |
| `packet_analysis_run` | **yes (new)** | create case, upload capture, upload and align host telemetry, request analysis, save topology, delete |
| `packet_analysis_raw_view` | **no** | stays `security_admin` only; aiview is masked even if it were also bound to `security_admin` |

## 3. Limits that come with the grant

1. **Own cases only.** A replay-viewer session may upload to, analyse, edit or delete only a case it created itself
   (`NOT_CASE_OWNER` otherwise). Operators and administrators are not restricted by this rule; deleting a case remains
   owner-scoped for everyone except `security_admin`.
2. **Machine session.** The aiview machine session (`aiview-e2e`) stays read-only for PUT and DELETE. Its POST allowance
   (already granted to diagnostics, readiness and policy collection) now also covers `packet_analysis_run`, so it can
   create a case, upload a capture and request an analysis. Saving a topology, changing a clock alignment and deleting
   a telemetry file or a case need a human aiview session.
3. **Same technical limits as every role.** Per-file size, total store cap, two parallel uploads, one analysis at a
   time, 7-day retention. The grant adds no quota exception.
4. **Acknowledgements are masked.** The case created, the capture uploaded (format and size only, no digest) and the
   telemetry file attached or aligned come back with pseudonymous names and no creator, uploader or digest.
5. **No other action changes.** Failover execution, approvals, schedules, the Data Sanitizer and every other action
   stay closed to aiview.

## 4. Where it is enforced and tested

- `ActionRegistry`: `PACKET_ANALYSIS_RUN` and `PACKET_ANALYSIS_RUN_ROLES` name `role:replay_viewer`.
- `GateChain`: the machine-session POST allowance names `packet_analysis_run`.
- `PacketAnalysisController`: run authority no longer excludes the masked session; raw authority still does; the
  own-case rule; masked acknowledgements.
- Tests: `PacketAnalysisApiTest` (route reach, masked acknowledgements per route, own-case rule, raw refusals),
  `MachineGateTest`, `AiviewDiagnosticReadOnlyTest` (exact allow-list of the actions that name the replay viewer),
  the vitest role-gating tests for the Packet Analysis screen.

## 5. Review

Revoke by removing `RoleToken.REPLAY_VIEWER` from the two `ActionRegistry` entries and from the `GateChain` POST
allowance; the allow-list test in `AiviewDiagnosticReadOnlyTest` then needs the same one-line change.
