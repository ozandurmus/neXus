# Check Point failover: resume the return step of a stopped run

**Status: RATIFIED — PO approval 2026-10-20 (relayed by the orchestrator, AskUserQuestion: "Hepsini onaylıyorum").** The framing (resume the same run's return step), the two field amendments (Q2 session rule on cp_failover_up, Q2b cphaprob stat read allowance), Q3 (resume allowed after a failed post-switch check), Q4 (30 min request expiry), Q5 (classic ClusterXL only), Q6 (incident release stays manual, security_admin with a reason) are approved as written below. Earlier text: No code, migration, gate row or device command is approved by this
document. It changes no runtime behaviour.

Trigger: PO decision 2026-10-20 ("Önce kurtarma eylemi yazılsın"). Before the pilot, neXus must be able to bring
back a member that it took down itself.

PO clarification 2026-10-20 (relayed by the coordinator):
- The approved flow is exactly:
  1. checks;
  2. `clusterXL_admin down`, then `clusterXL_admin up`, on the active member;
  3. failover verification;
  4. post-checks.
- "Recovery" is therefore **not a new action with a new purpose**. It is **Resume the return step** of the **same run**.
  It sends that run's own approved `clusterXL_admin up`, then continues with steps 3–4.

Inputs:
- [FAILOVER_EXECUTION_CP_CONTRACT.md](FAILOVER_EXECUTION_CP_CONTRACT.md) §1, §4 and §7 (FROZEN).
- [PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md](PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md)
  items 4–6 (RATIFIED).
- [FAILOVER_PILOT_STATUS_2026_10_20.md](FAILOVER_PILOT_STATUS_2026_10_20.md): P0-1, P0-2, P0-5, P1-1, P1-4, P1-5, §4.7
  (DRAFT).
- Migrations `V101`, `V136`–`V140`.
- `ui2/worker/.../worker/failover/CpFailoverJobExecutor.java`.

## 1. Purpose and trigger

**The situation.** A neXus failover run sent `clusterXL_admin down` to the verified active member, and the switch was
confirmed: the former standby reported ACTIVE and the former active reported DOWN. The run then stopped before its
`clusterXL_admin up`. Causes include:
- a post-switch check FAIL or UNKNOWN;
- `POLL_BUDGET_EXHAUSTED`;
- approval expiry or revocation between DOWN and UP (P1-1);
- owner loss or interruption after the confirmation;
- the mutation switch being off at UP.

The run is `STOPPED`. The V136 trigger opened an incident on the unit, and the former active is left DOWN by admin.
That is a cluster with no standby.

**The action.** "Resume return step" is a manual, admin-initiated continuation of that run:
1. A fresh read of the cluster state.
2. The run's own step 9: `clusterXL_admin up`, sent once, to the run's former active.
3. The run's own steps 10–11: return confirmation, then the post-return checks against the run's `pre` baseline.

**What it is not:**
- **Not automatic.** neXus never proposes, schedules or retries it. There is no automatic retry, failback or release
  (2026-10-06 item 5.7).
- **Not a failback.** Traffic stays on the new active; the former active rejoins as Standby, as in a clean run (PO
  record 2026-09-27 item 2).
- **Not an `RB.x` class 1 recovery write.** `clusterXL_admin up` is `CLASS_2_OPERATIONAL_STATE_CHANGE` (V101,
  `ActionClass`). Re-filing it as a "recovery write" would be a new purpose for an existing command. AGENTS.md would
  then require the command gate for that purpose ("Command presence in source != command approval"). This contract
  keeps the command's one purpose: the return step of the failover run that sent the DOWN.
- **Not for anything else.** Out of scope:
  - a member brought down by someone else;
  - a member whose DOWN was never confirmed (`FAILOVER_TIMEOUT`, `OUTCOME_UNCERTAIN` on DOWN);
  - a run whose UP may already have been sent;
  - a VSX Virtual System, Palo Alto, or any other vendor.

  These keep the out-of-band procedure in status §4.7.

## 2. Preconditions and refusal codes

### 2.1 Before any device contact (server-side, in one transaction under advisory lock `136,1`)

Each row is a precondition. Any one failing refuses the request with the code shown.

| # | Precondition | Refusal |
| --- | --- | --- |
| 1 | The caller's role is allowed (§5); the actor is taken from the authenticated session, never from the body. | `WRONG_ROLE` |
| 2 | The run exists and has `run_kind='FAILOVER'`, vendor `check_point`, `vs_id IS NULL` and `state='STOPPED'`. | `RESUME_RUN_NOT_ELIGIBLE` |
| 3 | The run's `FAILING_OVER` dispatch intent has `observation='CONFIRMED'` and `member_ref` = the run's former active. This proves neXus downed exactly this member. | `RESUME_DOWN_NOT_CONFIRMED` |
| 4 | The run has no `RETURNING` intent, or only one with `delivery='NOT_SENT'` (V140 proof that UP never left neXus). | `RESUME_RETURN_ALREADY_ATTEMPTED` |
| 5 | No earlier resume exists for this run, in any state. A resume is single-use. | `RESUME_ALREADY_USED` |
| 6 | An active `failover_quarantine` row exists with `execution_id = run_id`, and it equals the `expectedIncidentId` in the request. | `INCIDENT_NOT_OPEN` / `INCIDENT_MISMATCH` (409) |
| 7 | No *other* active incident covers the unit or either member. | `OPEN_INCIDENT` |
| 8 | No other non-terminal FAILOVER run or resume exists anywhere (fleet fence). No other unresolved dispatch intent exists. | `FLEET_MUTATION_ACTIVE` / `UNRESOLVED_DISPATCH` |
| 9 | The cluster's current two enrolled members equal the run's `target_member_ids`, and the recorded identities are unchanged since the run. | `MEMBERSHIP_CHANGED` |
| 10 | The mutation switch is on (service and worker). | `FAILOVER_MUTATION_DISABLED` (existing `FailoverMutationSwitch.DISABLED`) |
| 11 | A reason is given (non-empty, length-bounded). Between 08:00 and 20:00 Europe/Istanbul (server clock), the business-hours acknowledgement is present (§5). | `REASON_REQUIRED` / `BUSINESS_HOURS_ACK_REQUIRED` |
| 12 | The resume request is unexpired. Proposed lifetime: 30 min from creation to start (Q4). | `RESUME_REQUEST_EXPIRED` |

Rows 1, 8, 10 and 11 are rechecked:
- at worker start;
- inside the send transaction for UP, as `mutationAdmission` does today for a run.

### 2.2 At device contact, before UP

The worker opens one trusted SSH session per member and reads fresh.

**Connection:**
- Each member's host key must be trusted, and its presented identity must equal its recorded identity. Otherwise
  `TRUSTED_CONNECTION_REQUIRED` / `IDENTITY_NOT_VERIFIED`. A WARN-mode host-key mismatch is refused, as for every
  non-read job.
- The two members must be distinct observers. Otherwise `OBSERVERS_NOT_DISTINCT`.

**One fresh `cphaprob stat` per member** (`cp_inventory_cphaprob_stat`):
- The two maps must be reciprocal: each member lists both, and they agree.
- The run's former standby must be `ACTIVE` on both observers. Otherwise `RESUME_PEER_NOT_ACTIVE`.
- The run's former active must be `DOWN` on both observers. Otherwise `RESUME_MEMBER_NOT_DOWN`.
  - If it is already STANDBY, someone recovered it out of band. neXus sends nothing; the operator verifies and
    releases the incident (§4.3).
- The recovery mode must be `Active Up`. Otherwise `RETURN_PREEMPTION_POSSIBLE`. This depends on P0-2 retaining the
  recovery suffix; without P0-2 the resume is not offered.
- Output not recognised, or the maps are not reciprocal: `RESUME_STATE_UNKNOWN`.

Any refusal here stops the resume with nothing sent. The incident stays open.

## 3. Exact command: the run's own `cp_failover_up`, unchanged

There is **no new command row and no new purpose**. The resume sends the gate row that the run already resolved and
was approved for (V101; contract §4; 2026-10-06 item 6).

### 3.1 The existing gate row

Verbatim from `V101__cp_failover_execution.sql`, physical context; it is unchanged since:

| Field | Value |
| --- | --- |
| `gate_id` | `cp_failover_up` |
| vendor / platform / shell / transport | `check_point` / `cp_gaia_gateway` / `expert` / `SSH_EXEC` |
| `canonical_command_key` | `clusterXL_admin up` |
| `action_class` | `operational-state-change` (`CLASS_2_OPERATIONAL_STATE_CHANGE`) |
| `sign_off_state` | `SIGNED_OFF` |
| `timeout_s` | `30` |
| `retry_rule` | `none` |
| `max_frequency` | `once on former active per run` |
| `session_reuse_rule` | `one SSH session per member per run; commands serial` |
| `unsupported_behavior_ref` | `UNKNOWN` |
| `secret_output_risk` | `none` |
| `safe_telemetry_fields` | `[]` |
| `source_document_pointer` | `docs/design/FAILOVER_EXECUTION_CP_CONTRACT.md §4` |

### 3.2 Gate-format view of this use

| # | Item | Value |
| --- | --- | --- |
| 1 | Why required | Step 9 of the same run (return the former active to Standby), which the run did not reach. |
| 2 | Class | CLASS_2_OPERATIONAL_STATE_CHANGE. Through this flow only. Never console-submittable, never in a script run. |
| 3 | Vendor, shell, context | Check Point Gaia `cp_gaia_gateway`, Expert login shell. **Wire literal sent:** `bash -lc 'clusterXL_admin up'` (`CpFailoverJobExecutor`: `"bash -lc '"+command+"'"`, no PTY). The wrapper difference from the key is P1-5. |
| 4 | Timeout | 30 s (row value). |
| 5 | Retry | None. A lost or failed reply records `OUTCOME_UNCERTAIN` and stops, and is never resent. |
| 6 | Frequency | At most once per run, summed over the original run and its resume. Enforced by the dispatch intent `UNIQUE(run_id, step)`, step `RETURNING`. If a NOT_SENT `RETURNING` intent exists, the implementation narrows that unique key to intents with `delivery<>'NOT_SENT'`, so "may have been sent" stays at most one. |
| 7 | Session reuse | One new SSH session per member for the resume, commands serial, 2 s pause. The original run's sessions are closed. This is the one field the row's text does not cover (Q2). |
| 8 | Unsupported behaviour | Success is exit status 0, output ≤ 256 KiB, and no "command not found" or "unavailable" text. Anything else is `OUTCOME_UNCERTAIN`. The device state is then settled only by the verification reads (§4), never by a resend. |
| 9 | Secret-output risk | None expected. Output is not persisted or shown, only a success boolean. |
| 10 | Safe telemetry | `run_id`, `resume_id`, `gate_id`, opaque `member_ref`, dispatch nonce, delivery and observation enums, timestamps. No host, address or raw text. |

### 3.3 Reads the resume uses

All are existing gate rows with their existing purpose: failover verification of this run.
- `cp_inventory_cphaprob_stat`: one fresh read per member before UP, then polls.
- The post-return battery listed in status §2.1 step 5:
  - `cp_failover_tablestat`, `cp_inventory_cphaprob_a_if`, `cp_failover_arp`, `cp_failover_syncstat`,
    `cp_failover_fw_stat`;
  - `cp_policy_install_cpstat`, `cp_failover_pnotes`, `cp_failover_bonds`, `cp_failover_bond_members`;
  - `cp_failover_last_event`, `cp_failover_routing`, `cp_failover_traffic`.

The post-return phase was never run in the stopped run, so its "once post-return per member per failover run"
frequency holds.

`cphaprob stat` is the exception. Its V139 frequency is a 21-poll budget shared across DOWN and UP per run, and the
stopped run may have used it. The resume needs one fresh read per member plus at most 20 polls (Q2b).

**Rule (AGENTS.md).** Any new purpose for an existing command requires the PO's explicit approval of that exact row.
This contract claims none, and Q1 asks the PO to confirm that. If the PO instead regards the resume as a separate
purpose, the §3.1 row must be re-approved verbatim under that purpose before any code is written.

## 4. Verification after UP, outcome, incident closure

### 4.1 Return confirmation (step 10 of the run)

Poll `cphaprob stat` on both members every 3 s, at most 20 polls (60 s).

| Observation | Result |
| --- | --- |
| Both observers agree: former active `STANDBY`, former standby `ACTIVE`, reciprocal. | Confirmed: `confirmDispatch` sets the intent `CONFIRMED`. |
| Former active `ACTIVE`, or former standby `STANDBY` (preemption or role swap). | Immediate stop `UNEXPECTED_ROLES`. |
| No confirmation within the budget. | `RETURN_TIMEOUT`. |

### 4.2 Post-return checks (step 11 of the run)

The full battery runs as phase `post_return`, recorded under the **same `run_id`** and compared with that run's `pre`
baseline exactly as in a clean run:
- traffic ≥ 50 % of the baseline active;
- sync OK;
- policy continuity;
- routing;
- bonds;
- pnotes: none on either member;
- session continuity `NOT_EVALUATED`, never a pass.

### 4.3 Outcome

**Success.** The return is confirmed and every blocking post-return check is PASS. Then:
- The resume ends `DONE`, message `RETURN_VERIFIED`.
- The run's history shows: stopped at <step> → resumed by <actor> → return verified.
- The run row itself stays `STOPPED`. The V136 `FAILOVER_TERMINAL` invariant is kept, and the resume carries its own
  state (Q3).

**Failure.** Any refusal, timeout, `UNEXPECTED_ROLES`, `OUTCOME_UNCERTAIN` or blocking FAIL/UNKNOWN. Then:
- The resume ends `STOPPED` with that code, and nothing more is sent.
- The run's incident stays open.
- The `job_failure` notification fires.
- Recovery continues out of band (status §4.7). There is no second resume.

**Incident closure.** This is always a separate manual act. It is never automatic, even on success.
- After `RETURN_VERIFIED` (or after `RESUME_MEMBER_NOT_DOWN` with the member found STANDBY), the run view offers
  **Release incident**.
- Release uses the P0-1 route `POST /api/v2/cp-failover/runs/{runId}/incident/release`:
  - performed by a `security_admin`, with a reason and the expected incident id;
  - CAS through `ui2_release_failover_incident`;
  - audited.
- Until released, the unit and its members stay blocked for new runs.

## 5. RBAC, business hours, audit and ledger

### 5.1 Who may resume

| Role | May do |
| --- | --- |
| `security_admin` | Initiate and approve alone (`ADMIN_SINGLE`). |
| `operation_admin` | Initiate. A second, distinct, authenticated `operation_admin` must approve (`OPERATION_ADMIN_TWO_PERSON`; 2026-10-06 four-eyes rule; `SELF_APPROVAL` / `WRONG_INITIATOR` as V137). |
| operator, aiview, `replay_viewer` | Nothing: `WRONG_ROLE`. They see the masked view only. |

The approval is bound to `resume_id`, `run_id`, `expectedIncidentId`, the run's `target_member_ids`, the
`member_set_revision` and a single-use nonce. It is consumed once.

### 5.2 Business hours (PO 2026-10-20)

- A manual admin action has no job window.
- Between 08:00 and 20:00 Europe/Istanbul, every day, the initiator must tick the business-hours acknowledgement.
- The acknowledgement is stored server-side on the resume request and rechecked at admission. The two-person
  approver sees it.
- This uses the same predicate as the P0-5 lane.

### 5.3 Ledger and audit

Each step is a separate audited transaction:
1. Resume requested, with actor, reason, acknowledgement and expected incident id.
2. Approved (two-person only).
3. Job admitted (`resume_id`, job id, lease epoch).
4. One `job_step_attempt` pre-contact row per read.
5. **Before UP:** the `RETURNING` dispatch intent (nonce, owner, epoch, member, `cp_failover_up`) is committed, plus
   the audit row `failover_command_cp_failover_up`.
6. **After UP:** delivery (`REPLY_RECEIVED`/`UNKNOWN`), then observation (`CONFIRMED`/`OUTCOME_UNKNOWN`).
7. Each `post_return` check row.
8. The resume's terminal state.
9. Later, the incident release.

Proposed schema (implementation detail, Q3):
- A `failover_resume` table, append-only like V136/V137.
- The fleet fence counts a non-terminal resume like a non-terminal run.

## 6. UI and CLI

### 6.1 Operations › HA & readiness › unit › run view (`CpFailoverPanel`)

- A STOPPED run shows the button **Resume return step** only when the server reports `resumeEligible` (§2.1).
- Otherwise the button is replaced by the server's refusal code in plain words.
- The button appears only for an allowed role.

### 6.2 Confirm dialog (the only confirmation)

> **Resume the return step of this failover run?**
> Run `<runId>` on `<unit pseudonym>` stopped after `clusterXL_admin down` (`<stop reason>`).
> `<former active pseudonym>` is down by admin and `<peer pseudonym>` carries the traffic.
>
> neXus will first read the cluster state on both members. Only if `<peer>` is Active, `<former active>` is Down and
> the cluster is in Active Up mode, it sends `clusterXL_admin up` once to `<former active>`. Then it waits up to 60 s
> for it to rejoin as Standby and repeats the post-checks. Traffic stays on `<peer>`.
>
> Nothing is retried. If anything does not match, neXus stops and the incident stays open.
>
> Reason: [________] ☐ I acknowledge this runs during business hours (08:00–20:00 Europe/Istanbul). *(shown 08:00–20:00 only)*
>
> [Cancel] [Resume return step]

- With two-person approval, the second `operation_admin` sees the same text and **Approve resume**.
- During the run, the stepper shows: Reading cluster state → Returning former active to standby → Verifying final
  cluster health → Return verified, or the step where it stopped.

### 6.3 CLI

There is no failover CLI today (P1-6). The CLI-parity rule makes `nexus failover resume <runId> --incident <id>
--reason <text> [--business-hours-ack]` a P1 follow-up. It would call the same server routes with the same checks,
and print the same refusal codes.

### 6.4 Routes

- `POST /api/v2/cp-failover/runs/{runId}/resume-return`: create or start the resume.
- `POST /api/v2/cp-failover/runs/{runId}/resume-return/{resumeId}/approve`: second approval.
- Both are mapped in `ACTION_ID_BY_ROUTE` to a new action `cp_failover_resume_return`.

## 7. Tests the implementation must have

1. **Eligibility, one test per §2.1 row.** Each row refuses with its code, and no job, intent or device contact
   results. This includes:
   - DOWN intent not `CONFIRMED`;
   - a `RETURNING` intent that is not NOT_SENT;
   - a second resume;
   - a stale or other incident id → 409;
   - another run or resume in flight;
   - a membership change;
   - VSX / PAN;
   - switch off.
2. **Roles** (controller + route tests):
   - `security_admin` alone is admitted;
   - `operation_admin` alone stays pending, and a self-approval is `SELF_APPROVAL`;
   - operator, aiview and `replay_viewer` get `WRONG_ROLE`;
   - the actor comes from the session, never the body.
3. **Business hours:**
   - 07:59 and 20:00 need no acknowledgement;
   - 08:00 and 19:59 without it → `BUSINESS_HOURS_ACK_REQUIRED`;
   - the acknowledgement is rechecked at worker admission.
4. **Worker refusals.** Each fresh-read refusal sends **no** write:
   - peer not ACTIVE;
   - member already STANDBY (`RESUME_MEMBER_NOT_DOWN`);
   - Primary Up (`RETURN_PREEMPTION_POSSIBLE`);
   - non-reciprocal or unrecognised state;
   - identity or host-key mismatch.

   Assert that the transport exec log holds only reads.
5. **Exact literal.** The single write sent is `bash -lc 'clusterXL_admin up'` to the run's former active member and
   to no other member. The resolved gate id is `cp_failover_up`, class CLASS_2.
6. **Once per run:**
   - a second UP in the same run is impossible (PostgreSQL test on the intent unique key, including the NOT_SENT case);
   - a lost reply → `OUTCOME_UNCERTAIN`, no resend;
   - owner loss mid-resume → reconciliation records uncertainty, and the incident stays.
7. **Verification:**
   - STANDBY/ACTIVE confirmed → post-return battery under the same `run_id` against the `pre` baseline → `RETURN_VERIFIED`;
   - `UNEXPECTED_ROLES`, `RETURN_TIMEOUT` and a blocking post-return FAIL each stop, keep the incident and fire
     `job_failure`.
8. **No automatic release.** After `RETURN_VERIFIED` the incident is still active. Release succeeds only through the
   P0-1 route by `security_admin` with a reason and the right incident id.
9. **Invariants:**
   - the run row stays `STOPPED` (`FAILOVER_TERMINAL` not weakened);
   - the fleet fence refuses a new run during a resume;
   - audit rows exist for every step in §5.3;
   - the UI renders phase `post_return` (P0-3).
10. **Architecture:**
    - the write is unreachable from the console, script runs, readiness (`writeAllowed`) and the generic failover
      routes;
    - `:architecture-tests:test` passes.

## 8. Approval questions for the Product Owner

**Q1 — Framing.** Do you approve "Resume return step" as the continuation of the same stopped run?
- It sends that run's own `clusterXL_admin up` once, to the member that run downed, through the unchanged gate row
  `cp_failover_up`.
- It then runs that run's return verification and post-return checks.
- It is not a new action, not a new purpose, and not a failback.

**Q2 — The command row (verbatim, reused unchanged).** Please approve for this use:

`cp_failover_up | check_point | cp_gaia_gateway | expert | SSH_EXEC | clusterXL_admin up | operational-state-change | SIGNED_OFF | 30 s | retry none | once on former active per run | one SSH session per member per run; commands serial | UNKNOWN | secret none | telemetry []`
— wire literal sent: `bash -lc 'clusterXL_admin up'`, to the run's former active only, at most once per run counting
its resume.

The only text that does not cover the resume is `session_reuse_rule`. Proposed amended value, with no other field
changed:
`one SSH session per member per run, and one new session per member for its single resumed return; commands serial`.

**Q2b — The `cphaprob stat` read frequency** (`cp_inventory_cphaprob_stat`, same read, same purpose). Append to its
V139 `max_frequency`:
`; a resumed return adds one read per member before UP and at most 20 polls every 3 s after UP`.

**Q3 — Eligibility after a post-switch check failure.** Is resume allowed when the run stopped because a post-switch
check failed? The new active is then carrying traffic, but a check did not pass. Recommended: yes, it is your "stop
and warn, the operator decides" path.

Related implementation choice: the run row stays STOPPED and the resume carries its own state (recommended), rather
than reopening the run.

**Q4 — Request lifetime.** 30 min from request to start (proposed), and no approval window beyond that?

**Q5 — Scope.** Classic ClusterXL only first. VSX (`cp_failover_up_vsid`) only after VSX failover itself works
(status: VS `cpstat` gate missing).

**Q6 — Incident release.** It stays a separate manual act after a verified resume, never automatic (recommended, per
2026-10-06 item 5.7). Who may release (status Q1): `security_admin` with a reason only, or two `operation_admin`s as
well?

## 9. Finding recorded while drafting (not fixed here; docs only)

V140's `ui2_failover_run_guard` skips the incident when a run stops with `MUTATION_DISABLED_BEFORE_SEND`, a NOT_SENT
intent exists, and no other intent is unconfirmed. This happens when the switch is turned off at UP after a confirmed
DOWN. The former active is then left DOWN with **no incident**: nothing blocks the unit and nothing records the
member's state.
- The exemption should apply only when **no** intent was ever sent.
- This resume needs that incident to exist: precondition §2.1 row 6 refuses without it.
- Fix it in the P0 lane, with a PostgreSQL test.

## 10. Implementation record (lane/cloud-failover-resume, 2026-10-21)

Appended by the implementing lane; the ratified text above is unchanged. Automated validation only
(`IMPLEMENTED → AUTOMATED_VALIDATED` at most); nothing here is real-environment validated.

| Area | Where |
| --- | --- |
| Migration | `V170__failover_resume_return_step.sql`: append-only `failover_resume` (one row per run, `UNIQUE(run_id)`), audit trigger + redaction of `reason`/`message`, fleet fence both ways (`uq_failover_resume_fleet`, `failover_run_resume_fence`), `UNIQUE(run_id,step)` of the dispatch intents narrowed to `delivery<>'NOT_SENT'`, resume closed when its job ends, and the two Q2/Q2b gate-row field amendments (command string unchanged). |
| Store | `JooqCpFailoverRepository`: `resumeEligibility`, `requestResume`, `approveResume`, `resumeAdmission`, `prepareResumeDispatch`, `dispatchResume`, `resumeState`, `resumeCommand`, `preBaseline`; reconciliation and incident release are resume-aware. |
| Worker | `CpFailoverJobExecutor.resume`: same executor, job type `cp_cluster_failover` (idempotency key `cp_failover_resume:<resumeId>`). |
| Service / API | `CpFailoverService.resumeReturn` / `approveResume`; action `cp_failover_resume_return` (security_admin, operation_admin); run detail carries a `resume` block. |
| UI | `ResumeReturn` in `CpFailoverPanel` (button, confirm text of §6.2, stepper, plain-word codes). |

Choices where the contract left the mechanics open, or the code base forced a shape:

1. **Approve route.** The gate chain resolves exactly one wildcard path segment per route, so the second approval is
   `POST /api/v2/cp-failover/runs/{runId}/resume-return/approve` with `resumeId` in the body (not a second path
   variable). Both routes are unmapped under `/api/v2/pan-failover` (403).
2. **Business hours** (PO decision 2026-10-20 as refined by the orchestrator on 2026-10-21). The resume request carries
   its own acknowledgement (`businessHoursAck` in the body, so a remembered header from another confirmation never
   counts); a second approval made inside business hours carries the approver's own. Both are stored on the request.
   The resumed `clusterXL_admin up` is the run's return write: it is never refused because the clock entered business
   hours after admission; the worker rechecks only the stored record (row 11).
3. **Baseline.** Policy names and interface names never left the original run's memory (raw-evidence law), so the
   post-return comparison uses the run's stored pre rows: the former active's traffic rate, route count/default route,
   monitored-interface and traffic-selection counts, and both members' policy install times per source (`fw stat`,
   `cpstat`) compared as a set (any reinstall is `POLICY_NOT_MATCHED`). A missing baseline refuses before contact
   (`RESUME_BASELINE_UNAVAILABLE`).
4. **Row 9 identities.** "Unchanged since the run" is `recorded_identity_recorded_at` not later than the run's start,
   plus the worker's presented-identity check on both fresh sessions.
5. **Expiry.** 30 minutes from creation to the worker start; an expired request is spent (row 5, single use).
6. **Additional codes**, all fail closed before any write: `RUN_ALREADY_ACTIVE` (a readiness run on the unit),
   `TRUSTED_HOST_KEY_REQUIRED` (service pre-check, so the single resume is not spent on an untrusted key),
   `RESUME_BASELINE_UNAVAILABLE`, `RESUME_NOT_FOUND`, `RESUME_NOT_PENDING`, `WRONG_POLICY`, `RESUME_TERMINAL`,
   `RESUME_WRITE_NOT_BOUND`, `OWNERSHIP_LOST`.
7. **Not built:** the failover CLI (§6.3, P1 follow-up).
