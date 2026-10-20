# PO decision record — 2026-10-20: failover pilot P0 closure (incident release, return safety, final checks)

**Status: RATIFIED — PO DECISION (chat, 2026-10-20, relayed by the orchestrator).** Recorded by the engineering
session on `lane/failover-p0`. It answers open decisions 1 and 2 of
[FAILOVER_PILOT_STATUS_2026_10_20.md](FAILOVER_PILOT_STATUS_2026_10_20.md) §5 and closes P0-1, P0-2 and P0-3 of its §3
in code. A fourth item (recovery-contract review §9) was added by the orchestrator during the lane; see §4. It grants
no new device command, no execution approval and no change to the job window (the window is the separate
`lane/admin-window-bypass`, P0-5). Automated validation only; nothing here is real-environment validated.

## 1. Incident release (P0-1)

**Decision.** A failover-API "Release incident" action: `security_admin` only, a mandatory reason, the actor taken from
the server-side session (never from the request body), audited, and recorded on the incident (who, when, reason).
The request-body approver path of the old generic route is removed for failover incidents. The UI offers a "Release
incident" button with a reason dialog wherever an incident blocks a unit; operator and aiview see the incident and the
role note, not the button. The estate-wide block from unresolved sends is releasable the same way.

**Implementation.**

| Part | Where |
| --- | --- |
| Route | `POST /api/v2/cp-failover/runs/{runId}/incident/release`, action `cp_failover_incident_release` (`ActionRegistry`, `security_admin` only, no alternative role), mapped in `SecurityWebMvcConfig.ACTION_ID_BY_ROUTE`. Not mapped under `/api/v2/pan-failover` (403 `ACTION_MAPPING_REQUIRED`). |
| Read | `GET /api/v2/{cp,pan}-failover/incidents` (`cp_failover_read`): every open incident plus every stopped run whose unresolved send fences the estate, with `canRelease` and the role note. Opaque references; names pass the masking advice. |
| Body | `{"reason": "..."}` only. Any other field (for example `approver_id` or `actor`) is refused with 400 `INVALID_REQUEST`, not ignored. Reason: 1–500 characters after trimming, else 409 `RELEASE_REASON_REQUIRED`. |
| Actor | `GateChainInterceptor` session fingerprint. `CpFailoverService.mayReleaseIncident`: `security_admin` (root administrator included), never a replay viewer even when it also holds `security_admin`. |
| Store | `JooqCpFailoverRepository.releaseIncident`, one audited transaction (`failover_incident_release`, correlation = run id) under advisory locks 136 and 138. |
| Recorded on the incident | V136 columns via `ui2_release_failover_incident`: `acknowledged_by` = actor, `acknowledged_at` = now, `review_notes` = reason. `second_approver_id` holds the same actor: the ratified policy is one `security_admin`, and the V136 guard requires the column. |
| Audit | V169 `trg_audit_failover_quarantine` (AFTER UPDATE; the V136 guard admits only the release update). `review_notes` is redacted in `audit_log` (tier 2); the other columns are classified in full (`AuditPresentationAllowlist`). |
| Generic route | `POST /api/v2/failover/{clusterRef}/quarantine/acknowledge` now returns 410 `GENERIC_INCIDENT_RELEASE_REMOVED` without reading the body or calling any service; it also stays unmapped (403 at the gate). `FailoverExecutionService.acknowledgeQuarantine` is removed. |
| UI | `FailoverIncidentsPanel` on Operations → HA & readiness (estate-wide) and inside each expanded unit of `CpFailoverPanel` (that unit's incidents plus estate-wide sends). Button + reason dialog for `canRelease`; otherwise the role note. |
| CLI | There is no failover CLI (`ui2/cli` has session and sanitizer commands only), so there is no CLI parity to add; P1-6 of the status document stays open. |

**Release semantics (codes, all 409 except as noted).**

- `RELEASED` (200): every active incident row of that run is released.
- `INCIDENT_NOT_FOUND`: the run has neither an active incident nor an unresolved send.
- `RUN_NOT_TERMINAL`: the run is not DONE/STOPPED.
- `RUN_STILL_OWNED`: the run's job still holds an unexpired CLAIMED/EXECUTING lease, or a dispatch is in progress.
- `WRONG_ROLE`, `RELEASE_REASON_REQUIRED`: refused before the store is touched.

**Estate-wide block.** `admissionDecision` refuses every failover with `UNRESOLVED_DISPATCH` while any intent has
`delivery<>'NOT_SENT'` and either `observation='NOT_OBSERVED'`, or `OUTCOME_UNKNOWN` with an active incident on its
run. Releasing the incident clears the second form. The first form survived an incident release (for example a job
that failed before the worker observed its write, before reconciliation ran). The same release action now also moves
that run's `NOT_OBSERVED` intents to `OUTCOME_UNKNOWN` (and their `job_step_attempt` rows to `OUTCOME_UNKNOWN`), which
clears the fence. The release records uncertainty; it never marks a write CONFIRMED and never makes it replayable
(`dispatch` refuses a claimed or observed intent). Such a run is listed with reason `UNRESOLVED_DISPATCH` even when it
has no active incident.

## 2. Return-step safety (P0-2)

**Decision.** Refuse before the first write unless both members report the ClusterXL recovery mode "(Active Up)".
"(Primary Up)" or an unknown mode is refused with a clear code. Record the observed mode in the readiness evidence as a
check row "Recovery mode" so the PO can choose a suitable cluster in the UI.

**Source of the mode — no new command.** The mode is the suffix of the single `Cluster Mode:` line of `cphaprob stat`,
the check-1 read already issued under gate `cp_inventory_cphaprob_stat` (`cp_inventory_vsid_cphaprob_stat` in a VS
context) in the same session, timeout and frequency. Before this change `CpFailoverChecks.state` matched the label
against `High Availability(?: \((?:Active|Primary) Up\))?…` and discarded the suffix. It now keeps it
(`CpFailoverChecks.recoveryMode`, `State.recoveryMode`): exactly one case-sensitive `(Active Up)` → `ACTIVE_UP`,
exactly one `(Primary Up)` → `PRIMARY_UP`, anything else (absent, repeated, other text, unparsed state) → `UNKNOWN`.
This is a parse-scope extension of an approved read (AGENTS.md, network action taxonomy). The measured, sanitized
fixtures of 2026-09-30 (`readiness_measured_gateway.json`, `_vs0`, `_vs1`, `failover_stat_*.txt`) all print
`(Active Up)`; no measured `(Primary Up)` output exists, so that branch is fixture-constructed (UNKNOWN fails closed
either way).

**Behaviour.**

- Check 15 "Recovery mode" is recorded per member in phase `pre` only, by readiness and by failover runs
  (`CpFailoverJobExecutor.recoveryMode`): PASS for `ACTIVE_UP`, FAIL for `PRIMARY_UP` (`RETURN_PREEMPTION_POSSIBLE`),
  UNKNOWN otherwise (`RECOVERY_MODE_UNRECOGNIZED`). Evidence: `{"recoveryMode": …, "reason": …}`. Blocking.
- A FAILOVER run stops right after the check-1 rows, before any further read and before any dispatch intent, with
  outcome/message `CLUSTER_RECOVERY_MODE_NOT_ACTIVE_UP` and failed check 15; `mutation_possible` stays false, so no
  incident is created.
- A readiness run records the rows and continues its battery; Primary Up makes it NOT_READY, an unknown mode UNKNOWN.
- The unit views (`/units`, `/summary`) carry `recoveryMode`: `ACTIVE_UP` only when both members' latest readiness rows
  say so, `PRIMARY_UP` if either does, else `UNKNOWN`. The panel shows "Recovery mode: Active Up / Primary Up / Unknown".
- Schema: V169 widens `failover_check_result_check_no_check` to include 15.

## 3. Final post-checks in the UI (P0-3)

`CpFailoverPanel.tsx` grouped checks by phase `"final"`, while the worker writes `"post_return"` (V139), so final
post-checks were shown as "Not evaluated". The panel now shows phases `post_return` and `final` (the older name, still
accepted) under "Final post-checks" and counts both in the DONE evidence summary. `CpFailoverCheck.phase` includes
`post_return`; step `POST_RETURN` maps to "Verifying final cluster health".

## 4. Mutation switch off at UP after a sent DOWN (recovery-contract review §9)

**Finding** (`docs/design/CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md` §9 on `lane/failover-recovery-contract`): the V140
run guard skipped the incident whenever a run stopped with `MUTATION_DISABLED_BEFORE_SEND`, had a `NOT_SENT` intent and
no *unconfirmed* sent intent. After a DOWN that was sent and confirmed, a switch-off before UP met that condition, so the
former active stayed DOWN with no incident and nothing blocking the unit.

**Fix (V169 §3).** `ui2_failover_run_guard` keeps the exemption only when no write of the run was ever sent (every
intent of the run is `NOT_SENT`). Any stop after a sent write opens the incident like every other stop past the first
write. A switch-off before DOWN still stops cleanly without an incident.

## 5. Migration

`V169__failover_pilot_p0_release_and_recovery_mode.sql` (check 15 constraint; release audit trigger and redaction;
run-guard fix). V168 is taken by `lane/admin-window-bypass` (`V168__admin_manual_window_bypass.sql`, uncommitted in its
worktree at the time of writing), so V169 must be applied after V168: ship that lane first, or renumber this file if
the order changes. Dry-run in BEGIN/ROLLBACK on the live database before rollout.

## 6. Tests

- Worker: `CpFailoverChecksTest.recoveryModeIsReadFromTheClusterModeLine`;
  `CpFailoverJobExecutorTest.recoveryModeOtherThanActiveUpIsRefusedBeforeTheFirstWrite` (Primary Up and no suffix,
  readiness and failover: no intent prepared, no DOWN/UP, no incident) and
  `activeUpOnBothMembersIsRecordedAndAdmitsTheRun`; check counts updated for the two check-15 rows (command counts
  unchanged: no new command).
- Service: `CpFailoverIncidentReleaseTest` (roles, reason, refusal codes, opaque unit ids),
  `CpFailoverIncidentRouteTest` (real gate chain: security_admin only, session actor, body refusal, pan prefix and
  generic route refused, every reader sees incidents and the role note), `FailoverExecutionControllerTest`
  (generic release refused), `CpFailoverControllerTest` (unit recovery mode), `ReadinessCheckViewTest` (check 15).
- PostgreSQL (compiled here, run on HOST-A): `FailoverIncidentReleaseTest` — switch-off at UP after a confirmed DOWN
  opens an incident, its release is recorded and audited with the reason redacted, the unit is admitted again; a stop
  before any send still opens none; unresolved sends fence the estate until the same release records them as
  uncertain; a live run is refused; check 15 is storable. `AuditCoverageCompletenessTest` and
  `AuditRedactionPolicyTest` registers updated for `failover_quarantine`.
- Frontend: `CpFailoverPanel.test.tsx` (backend phase names, recovery mode, incident role note),
  `FailoverIncidentsPanel.test.tsx`, `OperationsScreen.test.tsx`.
