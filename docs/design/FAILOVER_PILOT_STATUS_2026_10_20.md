# Failover pilot status and preparation — 2026-10-20

**Status: DRAFT — status review and pilot preparation only.** Not an execution, command, migration, contract or
schedule approval. It changes no runtime behaviour.
Baseline: `origin/main` at `f05e7ced` (2026-10-20). Read-only repository analysis: no host, database or device
was contacted. Live database state is therefore not verified here; §4.2 lists the read-only checks that establish it.
Inputs: [FAILOVER_EXECUTION_PLAN.md](FAILOVER_EXECUTION_PLAN.md) (DRAFT 2026-10-06),
[PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md](PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md)
(RATIFIED), [FAILOVER_EXECUTION_CP_CONTRACT.md](FAILOVER_EXECUTION_CP_CONTRACT.md) and
[FAILOVER_EXECUTION_PAN_CONTRACT.md](FAILOVER_EXECUTION_PAN_CONTRACT.md) (FROZEN), Java source and tests,
migrations V101–V143/V162, `project/QUEUE.md`, `git log --since=2026-10-06` on the failover paths.

**Bottom line.**
- The Check Point classic ClusterXL path is implemented end to end, and live manifests turn mutation on. It has
  never been run against a real device.
- Five P0 items block a safe pilot (§3). Three are small code changes, one is evidence collection, and one
  (P0-5) is the window change being built in another lane.
- Palo Alto execution cannot be reached by design: its POST routes are unmapped and a test enforces that. It also
  does not read preemption. A PAN pair is not eligible for this pilot.
- VSX execution stops before contacting any device because the VS-context `cpstat` gate is missing.
- **Window change (PO decision 2026-10-20, relayed to this lane):**
  - The 00/06/12/18 job windows apply only to scheduled periodic jobs (inventory, configuration, log collection,
    backups).
  - Manual operational actions by `security_admin`/`operation_admin`, including readiness and failover, are never
    window-blocked. Between 08:00 and 20:00 Europe/Istanbul, every day, they require an explicit "business hours"
    acknowledgement instead.
  - Manual submissions by operator and aiview stay window-gated.
  - A separate lane is implementing this. At this baseline no decision record exists in the repository and the code
    still window-gates failover.
  - This document treats the failover window gate as being removed, not as a prerequisite. Every other safeguard
    stays.

Source prefixes as in the plan: S = `ui2/service/.../service/failover/`, A = `ui2/service/.../service/api/`,
W = `ui2/worker/.../worker/failover/`, J = `ui2/job-engine/.../jobs/failover/`,
P = `ui2/persistence/.../persistence/`, M = `ui2/service/src/main/resources/db/migration/`, F = `ui2/frontend/src/screens/`.

## 1. Plan PR status (all ten)

Commits are on `main`; commit metadata records no PR numbers. "Dormant" means the code is merged but sits on the
generic manual/scheduled route, which PR 1 disabled and which the pilot does not use.

| PR | Status | Evidence | Missing |
| --- | --- | --- | --- |
| 1 Close alternate mutation entry points | **DONE** (automated) | `0326f009` default-deny `J/FailoverMutationSwitch`, generic execute/schedule refused, synthetic success removed; `e464d200` switch persisted in `deploy/ui2/50-service`/`52-worker` (`true`, PO 2026-10-08) and `false` in preview; `12c4e10f`; `f70fd27d` PostgreSQL proof of `GENERIC_FAILOVER_EXECUTION_DISABLED`. Generic `/api/v2/failover/**` routes are not in `SecurityWebMvcConfig.ACTION_ID_BY_ROUTE` → 403 `ACTION_MAPPING_REQUIRED`. | — |
| 2 Baseline integrity/temporal framing | **DONE, dormant** | `f1da6671` + M/`V133__failover_baseline_integrity.sql` (renumbered `b4b68642`), `FailoverScheduleIntegrityTest`. | Dispatch-time tamper assertions were removed with the dead path (`f70fd27d`). They must return if generic dispatch is ever re-enabled. |
| 3 Signing-key lifecycle | **DONE, dormant** | `149eeab1` + M/`V134__failover_signing_key_identity.sql`, `FailoverKeyLossTest`. | — |
| 4 Ledger/schedule transactions | **DONE, dormant** | `8253dd0f` + M/`V135`; `S/FailoverScheduleLedger` framed V2 hashes, `pg_advisory_xact_lock` tip serialization; `FailoverScheduleTransactionTest`. | Unkeyed chain (accepted limitation, plan Q4). |
| 5 Durable incidents + common admission | **PARTIAL** | `b6c76b79` M/`V136`: append-only `failover_quarantine`, `uq_failover_fleet_mutation`, run-guard trigger creates the incident on `STOPPED ∧ mutation_possible`, job-failure trigger, CAS `ui2_release_failover_incident`. `deff0589` sets incident precedence. Enrollment follows PO decision 3 (whole enrolled estate, `JooqDeviceRepository.enrolledFailoverMembers`); `FailoverPilotAllowlist` is used only on the generic path. | **No reachable, authenticated incident release** (P0-1). The only release route is generic `POST /api/v2/failover/{clusterRef}/quarantine/acknowledge`, which is unmapped (403) and takes a caller-supplied `approver_id`. `a1fcbec6` removed the release UI. |
| 6 Fenced attempts + crash reconciliation | **PARTIAL** | `4c85ccca` M/`V138` dispatch intents (single-use claim, owner/epoch/generation fence, `reconcileDispatches`); `7b6850c0` M/`V140` proves NOT_SENT; `FailoverIncidentAdmissionTest`. | A `FAILOVER` run whose job ends with `mutation_possible=false` (cancelled, rejected or expired before start) is never closed. It holds `uq_failover_fleet_mutation` and blocks the whole estate. `d0844d27` closed only READINESS runs and named this gap (P1-3). |
| 7 Approval + request-bound checks | **DONE** (automated) | `bbca0fc6` M/`V137`: `security_admin` → `ADMIN_SINGLE`, `operation_admin` → `OPERATION_ADMIN_TWO_PERSON` (`SELF_APPROVAL`, `WRONG_INITIATOR`), request id/revision/nonce/member-set binding, consumed once, rechecked at both write boundaries. `a1fcbec6` adds one warning confirmation and server-driven phases (F/`CpFailoverPanel.tsx`). | Approval-window length is unbounded (P1-1). The UI has no Revoke button (P1-2). |
| 8 Reciprocal identity/roles, mode admission | **DONE** (CP + PAN, automated) | `cb828b84` binds roles to verified member observations ([FAILOVER_RECIPROCAL_EVIDENCE.md](FAILOVER_RECIPROCAL_EVIDENCE.md)); `0dcac146` VS admission; `W/CpFailoverChecks.reciprocal`, distinct observers. | Recovery-mode admission belongs to PR 9 (below). |
| 9 Postconditions + return safety | **PARTIAL** | `616a104b` M/`V139`: phases `pre/post/post_return`, `cpstat -f policy fw` added, `fw tab -t connections` removed (session continuity `NOT_EVALUATED`), traffic measured at three points. `8e796489`/`453da2d7` M/`V143` bond membership; `4c22c785`, `1dad91d7` traffic reasons. | **Return preemption is not guarded** (P0-2). CP accepts `High Availability (Primary Up)` and labels without a recovery suffix. PAN never reads `preemptive`. Preemption is only detected *after* UP, as `UNEXPECTED_ROLES`. The VS cpstat gate `cp_policy_install_cpstat_vsid` is missing, so VSX fails closed. |
| 10 Masked pilot acceptance + closure evidence | **PARTIAL** | Masked flow `a1fcbec6`, `66c04da2`; `CpFailoverPanel.test.tsx`. | **The UI never shows post-return evidence** (P0-3): it filters phase `"final"` while workers write `"post_return"`. No P0-by-P0 closure record, no external review of closure and no real aiview navigation evidence (P0-4). |

`project/QUEUE.md` still lists `failover_p0_closure_set` as in progress ("integration gate"). The older P1 failover
items (`failover_recheck_on_request`, `failover_peer_reciprocal_agreement_check`,
`failover_pilot_fence_is_compiled_in`, …) predate these commits and need re-triage. Open decisions
`op_four_eyes` and `op_continuity_tolerance` are answered by the 2026-10-06 record. `D-V7b` (CP configured recovery
read surface) and `D-V2` (PAN `preemptive` vocabulary) remain open, and P0-2 depends on them.

## 2. End-to-end operator path today

### 2.1 Check Point ClusterXL pair (classic HA, physical context)

| # | Step | Code | Gates / commands | Enforced | Broken / untested |
| --- | --- | --- | --- | --- | --- |
| 0 | Enablement | `FailoverMutationSwitch.fromEnvironment()` in service, worker and repository | env `NEXUS_FAILOVER_MUTATION_ENABLED=true` (manifests) | Default deny. Refused at request, at worker start, at gate resolution and inside the send callback (`NotSent`). | Changing it needs a pod restart, and a restart mid-run is owner loss (incident). It is not an abort lever. |
| 1 | Readiness | S/`CpReadinessScheduler` (each 00/06/12/18 slot, sequential per unit), S/`CpFailoverService.requestReadiness`, W/`CpFailoverJobExecutor` (READINESS kind) | Same read battery as step 5 except `cpstat` | Read only (`writeAllowed`). Trusted host key required (`requireTrusted`). Admin manual readiness becomes windowless; the scheduled pass stays at the slots. | Advisory only: no request reads it (correct per "readiness ≠ authorization"). Evidence stores mode `HA`, not the `(Active Up)/(Primary Up)` suffix. |
| 2 | Unit selection | F/`OperationsScreen` → `CpFailoverPanel`; S/`units`/`resolveUnits` | — | Two enrolled readable CP members with inventory. VSX requires a common VSID. | — |
| 3 | Approval | A/`CpFailoverController POST /approvals` → S/`createApprovalRequest` → P/`createRequestApproval`; two-person: `POST /approvals/{id}/approve` → P/`approveRequest` | Route action `cp_failover_approve` | Today `windows.requireOpen()`. After the 2026-10-20 lane: no window for admin roles, business-hours acknowledgement 08:00–20:00. Server-side actor. `ADMIN_SINGLE` is approved at creation. Two-person needs a distinct second actor. Member-set revision binding; advisory lock 136. | Window length unbounded (P1-1). |
| 4 | Start / admission | `POST /runs` → S/`request` → P/`requestBound` → `admit` | Route action `cp_failover_start`; job `cp_cluster_failover`, idempotency key `kind:runId` | Initiator only, nonce, `warningConfirmed`, approval valid, one run per unit, `OPEN_INCIDENT`, `UNRESOLVED_DISPATCH`, `FLEET_MUTATION_ACTIVE` + unique index. Today also the job window, in `request`, `requestBound` and `admit`. Being replaced by the business-hours acknowledgement. | The window removal must reach all three call sites (P0-5). |
| 5 | Claim + pre-checks | `WorkerClaimLoop` (today the claim is window-gated in `JooqJobLeaseDao.claim`; it must be lifted for admin-submitted failover/readiness jobs, P0-5; lease heartbeat) → W/`execute` → `checks("pre")` | Per member, `bash -lc '<cmd>'` in Expert: `cphaprob stat` (`cp_inventory_cphaprob_stat`), `cphaprob tablestat` (`cp_failover_tablestat`), `cphaprob -a if` (`cp_inventory_cphaprob_a_if`), `arp -an` (`cp_failover_arp`, info), `cphaprob syncstat` (`cp_failover_syncstat`), `fw stat` (`cp_failover_fw_stat`), `cpstat -f policy fw` (`cp_policy_install_cpstat`), `cphaprob -ia list` (`cp_failover_pnotes`), `cphaprob show_failover` (`cp_failover_last_event`, info), `cpstat os -f routing` (`cp_failover_routing`), `cphaprob show_bond [ "<bond>" ]` (`cp_failover_bonds`, `cp_failover_bond_members`), `cat /proc/net/dev` ×2, 5 s apart (`cp_failover_traffic`) | Approval window (`windowValid`), `mutationAdmission`, every gate id resolved exactly, pinned host key and recorded identity = presented identity (a WARN-mode mismatch is refused for non-read jobs), distinct observers, reciprocal member maps, any FAIL/UNKNOWN blocking check stops before a write, `PRIOR_ATTEMPT_NOT_REPLAYED`, one pre-contact `job_step_attempt` per read. | Primary Up is admitted (P0-2). Baseline traffic of 0 gives UNKNOWN and a stop (intended). |
| 6 | DOWN | W/`command(formerActive, DOWN)` → `requireMutationAdmission` → P/`prepareDispatch` → P/`dispatch` | `bash -lc 'clusterXL_admin down'` on the verified active (`cp_failover_down`, 30 s, no retry) | Admission rechecked. The intent and attempt are committed before the send. Single-use claim. Approval/owner/member set rechecked inside the send transaction. A lost reply records `OUTCOME_UNKNOWN` plus an incident. | **Never executed on a device.** Exit/output of the wrapped command has not been measured. |
| 7 | Switch confirmation | W/`waitFor` → P/`confirmDispatch` | `cphaprob stat` both members, every 3 s, a budget of 21 polls shared with step 10 | Former standby ACTIVE and former active DOWN, reciprocal. | Budget not measured on real devices. |
| 8 | Post-switch checks | `checks("post")` | Same reads. Sync and routing on the new active only. pnotes expect only `ADMIN_DOWN` on the former active. Traffic: new active ≥ 50 % of the baseline active rate. | Any blocking FAIL/UNKNOWN stops the run with the former active left DOWN (by contract: "stop, change nothing more"). | The 50 % / 5 s window has not been measured on real traffic. A false FAIL leaves one member DOWN (§4.7). |
| 9 | UP | `command(formerActive, UP)` | `bash -lc 'clusterXL_admin up'` (`cp_failover_up`) | As in step 6, with a second admission. | As in step 6. Approval expiry between DOWN and UP refuses UP (P1-1). |
| 10 | Return confirmation | `waitFor(... ACTIVE, STANDBY)` | `cphaprob stat` polls | Former active STANDBY and new active still ACTIVE. Preemption gives `UNEXPECTED_ROLES`. | Primary Up preempts here (P0-2). |
| 11 | Post-return checks | `checks("post_return")` + final role read | Same reads | Traffic, sync, policy, pnotes and routing compared with the pre baseline. | **UI does not render this phase** (P0-3). |
| 12 | Done | P/`workerState("DONE")` | — | Refused while any dispatch intent is unconfirmed (`UNRESOLVED_DISPATCH`). | — |
| 13 | Stop / recovery | P/`workerState("STOPPED")` → `unknown()` + V136 trigger; `reconcileDispatches` on owner loss; mail `job_failure` route for `OUTCOME_UNKNOWN` | — | No automatic retry, failback or release. The incident blocks the unit and members. Unresolved intents block the fleet. | **The incident cannot be released through the product** (P0-1). neXus has no action that brings a DOWN member back (§4.7). |

### 2.2 Palo Alto HA pair (active/passive)

Same skeleton in W/`PanFailoverJobExecutor`, with XML API keygen per peer and identity checked as `localSerial` =
recorded identity.
- Reads: `pan_inventory_show_ha_state`, `pan_failover_session_sync`, `pan_failover_session_info`,
  `pan_inventory_show_system_info`.
- Writes: `pan_failover_suspend`, `pan_failover_functional` (30 s, no retry), with the same dispatch-intent fence.
- Checks 1–7: roles, relationship, HA links, config sync, session sync, sessions carried, version parity.

Today it breaks before step 3. `POST /api/v2/pan-failover/{approvals,approvals/*/revoke,runs}` are deliberately
absent from `ACTION_ID_BY_ROUTE`, which `CpFailoverSecurityRouteTest` asserts, so they return 403
`ACTION_MAPPING_REQUIRED`. Only readiness is reachable. Further gaps:
- `PanFailoverChecks.parse` never reads `preemptive`. With preemption enabled, `functional` can pass as `passive`
  and preempt after the hold timer, after neXus has reported DONE.
- `active-passive` admission relies on inventory HA facts only.
- The path has never run against a device. TLS verification is off by PO decision 2026-09-21, so peer identity rests
  on the serial in the response.

### 2.3 What is enforced, in one place

- **Job window:**
  - *Today:* request, approval creation, admission and worker claim need the first 60 min of a 00/06/12/18
    Europe/Istanbul slot (`JobWindowPolicy`, `UI2_JOB_WINDOW_MINUTES` default 60). A running job is not interrupted.
  - *After the PO decision of 2026-10-20:* no window for `security_admin`/`operation_admin` manual readiness and
    failover. A server-side business-hours acknowledgement is required 08:00–20:00. Operator/aiview manual readiness
    and the scheduled readiness pass stay windowed.
- **Approval window:** checked at worker start, in every `mutationAdmission`, in `prepareDispatch`, and immediately
  before each send.
- **Roles:**
  - `security_admin` may approve and start alone.
  - `operation_admin` needs a second distinct authenticated approver.
  - aiview/`replay_viewer` get `WRONG_ROLE` and see only the masked view.
  - Actors always come from the session (`GateChainInterceptor`).
- **Four-eyes:** enforced for `operation_admin` (`SELF_APPROVAL`). Incident release has no four-eyes path (P0-1).
- **Quarantine:** an incident is created atomically on any stop after `mutation_possible`. It blocks the unit and its
  members (`OPEN_INCIDENT`). Unresolved intents block the fleet.
- **Idempotency:** job idempotency key, consume-once request, `PRIOR_ATTEMPT_NOT_REPLAYED`, single-use dispatch
  claim, no blind retry.
- **Ledger:** every state change, command (`failover_command_<gate>`) and dispatch is an audited transaction, plus
  `job_step_attempt`. The hash-chained schedule ledger serves only the disabled generic path.

## 3. Gaps that block a safe pilot

### P0 — fix before the pilot

| Id | Gap | Smallest change | Test |
| --- | --- | --- | --- |
| P0-1 | After any stop past DOWN, the incident blocks the unit, and unresolved intents can block the fleet. No reachable route releases it. The generic route is unmapped and trusts a submitted `approver_id`. | Add `POST /api/v2/cp-failover/runs/{runId}/incident/release` mapped to `cp_failover_approve`. Body: reason and expected incident id. Server-side actor(s) per PO policy (Q2). It calls `ui2_release_failover_incident(cluster, runId, …)` (CAS on the exact incident). Add a "Release incident" button on a stopped run. Delete or keep unmapped the generic acknowledge route. *Interim, if the PO prefers:* a reviewed `docs/operations/sql/` release statement, run only with the PO's per-use DB-write OK. | Controller and route tests: unmapped generic route stays 403; aiview refused; wrong or stale incident id → 409; release unblocks the next `requestBound`. PostgreSQL test in `FailoverIncidentAdmissionTest`. |
| P0-2 | UP can preempt. CP accepts `High Availability (Primary Up)` or no recovery suffix. Council N5 required refusing before DOWN. | In W/`CpFailoverChecks.state`, retain the recovery suffix. For a FAILOVER run, stop before DOWN with `RETURN_PREEMPTION_POSSIBLE` (status UNKNOWN) unless both members report `(Active Up)`. Record the suffix in check 1 evidence so readiness shows it. No new command. | `CpFailoverChecksTest` (Active Up, Primary Up and no-suffix fixtures). `CpFailoverJobExecutorTest`: Primary Up → STOPPED, `prepareDispatch` never called, `mutation_possible=false`. |
| P0-3 | Post-return evidence is invisible. F/`CpFailoverPanel.tsx` filters phase `"final"`; the backend writes `"post_return"`. The test fixture uses `"final"`. | Use `"post_return"` in the phase list and the DONE summary. Fix the fixture. | `CpFailoverPanel.test.tsx` with backend phase names. |
| P0-4 | No closure evidence for the shipped code. Commit bodies record "Java suites remain unverified" for several PRs. | Docs/evidence only: on the deployed commit, run `cd ui2 && ./gradlew :job-engine:test :persistence:test :service:test :worker:test :integration-tests:test :architecture-tests:test` and the frontend checks. Record P0-by-P0 status (plan §2) and an external review through `scripts/consult_*.py`. Do aiview navigation of Operations → HA & readiness with zero 4xx. | The listed suites green; review output stored in `docs/design/`. |
| P0-5 | The window change for admin manual actions (PO 2026-10-20) is being built in a separate lane. The failover path checks the window in four places: S/`request` and `createApprovalRequest`, P/`requestBound` and `admit`, and the worker claim in `JooqJobLeaseDao`. A partial change can let a request pass and leave its job **queued until the next slot**, where it would run unattended inside a still-valid approval window. | The lane, not this one: one role-aware predicate used at all four sites. Store a server-side business-hours acknowledgement on `failover_approval`, bound to the request revision like `warning_confirmed_at`, and recheck it in `mutationAdmission` for runs started 08:00–20:00. Operator/aiview submissions stay windowed. Record a PO decision record before the code merges. | Service, repository and claim tests: an admin request outside the slots is admitted and claimed at once; 08:00–20:00 without the acknowledgement → refused; operator/aiview still `OUTSIDE_JOB_WINDOW`; the scheduled readiness pass unchanged. A PostgreSQL test where the admin job is claimed outside a slot. |

### P1 — should fix (runbook mitigation given)

- **P1-1 Approval window bounds.** If the window expires between DOWN and UP, UP is refused and one member stays
  DOWN. A long window lets a job queued behind a dead worker run unattended whenever the worker returns, now at any
  hour. Fix: in S/`createApprovalRequest`,
  require `until − now` between 30 and 120 min. Test in `CpFailoverServiceTest`. *Mitigation:* set "Window ends" to
  start + 60 min.
- **P1-2 Abort lever.** `revokeCpFailover` exists in `adminApi.ts` but no button calls it. Add "Revoke approval"
  on an active run, gated on `canApprove`. A revoke before DOWN stops cleanly; a revoke after DOWN prevents UP and
  leaves recovery to the operator. Test in `CpFailoverPanel.test.tsx`.
- **P1-3 Orphan FAILOVER run.** If the job is cancelled, rejected or expires before the worker starts, the run stays
  QUEUED and blocks the fleet. Fix: extend the V162 trigger to `run_kind='FAILOVER' AND NOT mutation_possible`.
  PostgreSQL test as in `ReadinessOrphanCloseTest`. *Mitigation:* never cancel the pilot job from Jobs.
- **P1-4 No neXus recovery step.** When the run stops with the former active DOWN, `clusterXL_admin up` must be run
  manually out of band (§4.7). Option: a typed "return former active" request on the same gate and fence. This
  needs a PO decision (Q3).
- **P1-5 Gate/literal parity.** `cp_failover_down`/`up` are keyed `clusterXL_admin down|up`, but neXus sends
  `bash -lc '…'`, the login-shell wrapper measured for reads on 2026-09-30. Record the wrapper in the gate rows'
  shell/session note. No new command.
- **P1-6 CLI parity.** There is no failover CLI (readiness, run detail, release).
- **P1-7 Traffic tolerance.** The 50 % / 5 s comparison is unmeasured on real traffic. Record the observed
  baseline/post ratio from the pilot before widening use.

### P2 — later

- Revoke the rejected `cp_failover_connections[_vsid]` gate rows, which are still `SIGNED_OFF`.
- Remove the dead `possibleSend=true` branch of `mutationAdmission`.
- VSX: approve `cp_policy_install_cpstat_vsid`, then accept per VS.
- PAN pilot (Q5) needs three changes: map its POST routes, refuse before suspend when `preemptive=yes` on either peer
  (resolve D-V2), and apply the P0-3 fix (shared code).
- Re-triage the stale failover QUEUE items.

## 4. Pilot runbook draft (Check Point, classic HA, one unit)

### 4.1 Unit the PO may pick

The unit must meet all of these:
- Classic HA, not VSX, not VSLS.
- Both members report `(Active Up)` (visible once P0-2 lands).
- Readiness READY.
- Last failover event more than 6 h ago.
- Steady, non-zero traffic on the monitored interfaces at the chosen time.
- No open host-key mismatch observation on either member.
- Owned by a team that can supply out-of-band access.

Refer to the unit only by its masked pseudonym.

### 4.2 Prerequisites

1. **Code:** P0-1…P0-5 merged and deployed. Each new migration dry-run in BEGIN/ROLLBACK first. Mutation switch
   `true` on service and worker.
2. **Time (PO's choice; no job window applies after P0-5):**
   - Avoid the first hour after 00/06/12/18. Scheduled inventory, configuration, log and readiness jobs then queue
     for the same endpoints (`EndpointAdmissionRepository`, one session per endpoint), so the failover connect can
     wait or fail before any write.
   - Inside 08:00–20:00 the business-hours acknowledgement is required, and that is the business-impact choice.
3. **Deploy freeze:** no ship, rollout or pod restart from 20 min before the chosen start until post-checks end.
4. **Read-only database checks** (counts and booleans only, aiview mentality), all must be zero or true:
   - active `failover_quarantine` rows;
   - `failover_dispatch_intent` rows not `CONFIRMED`/`NOT_SENT`;
   - non-terminal `run_kind='FAILOVER'` runs;
   - fresh `failover` module owner heartbeat;
   - no `FAILOVER` run ever reached `mutation_possible` (confirms that this is the first execution).
5. **Readiness:** a manual admin readiness run READY within 60 min before the start (windowless after P0-5). No
   policy install on the unit since. The run repeats the full battery anyway.
6. **Backups:** today's 02:00 configuration backup succeeded for both members, and the daily configuration read is
   current.
7. **Out of band:** a firewall engineer on the call with Expert SSH to both members outside neXus and HOST-A, plus
   console/LOM and SmartConsole cluster view. Application owners watching their services. Change record approved.
8. **People:** the PO as `security_admin` (single-admin path) or two `operation_admin` accounts. A recovery
   decision-maker named in the change record.

### 4.3 Steps (UI; there is no CLI)

1. At the chosen time, open Operations → HA & readiness as aiview. Confirm the unit shows Mode HA (Active Up after
   P0-2), no refusal reason and no running readiness. A start during a readiness run is refused with
   `RUN_ALREADY_ACTIVE`.
2. Sign in with the operating account. Open the same unit and expand it.
3. Press **Run readiness** and wait for READY (prerequisite 5).
4. Press **Start failover**. In the dialog check the target pseudonym, set **Window ends** = now + 60 min and enter
   a reason. Between 08:00 and 20:00, also tick the business-hours acknowledgement (P0-5). Press **OK / Start**. This
   is the only confirmation.
   - With `ADMIN_SINGLE` the run starts immediately.
   - With two-person approval the second `operation_admin` opens the unit and presses **Approve request revision 1**.
     The initiator's open page then starts the run, or the initiator presses Start again.
5. Watch the stepper: Checking readiness → Failing over → Role change confirmed → Checking traffic and cluster after
   switch → Returning former active to standby → Verifying final cluster health → Failover verified. The engineer
   watches the cluster state out of band in parallel.
6. Start no other failover. The fleet fence refuses one anyway.

### 4.4 What neXus sends

All commands run through the trusted SSH session, one at a time with a 2 s pause, as `bash -lc '<cmd>'` in Expert.

- **Reads (§2.1 step 5):** about 13 commands per member per phase, in three phases (pre, post-switch, post-return).
- **Polls:** `cphaprob stat` on both members during each transition.
- **Writes, exactly two, both on the former active:**
  1. `clusterXL_admin down`
  2. `clusterXL_admin up`, sent only after every post-switch check passes.
- Nothing is sent to the standby. Nothing is retried. No connection table is read.

### 4.5 Expected duration and impact

| Phase | Typical duration |
| --- | --- |
| Pre-checks | about 2 min |
| Switch | under 1 min |
| Post-switch checks | about 2 min |
| Return | under 1 min |
| Post-return checks | about 2 min |
| **Total** | **6–10 min** (estimate from the command count, not measured) |

The poll budget is 21 polls at 3 s plus command time, shared by both transitions. Traffic moves once, at DOWN.
Under Active Up, UP should not move traffic. State sync is verified before DOWN.

### 4.6 Abort criteria and levers

**Abort when any of these happens:**
- Monitoring shows service loss lasting more than the agreed seconds after DOWN.
- Out of band shows both members ACTIVE, or neither.
- No phase change for 5 min.
- The run is STOPPED.
- neXus or the database becomes unreachable.

**Levers:**
- **Before DOWN:** close the dialog, or revoke the approval (`POST /api/v2/cp-failover/approvals/{id}/revoke`
  until P1-2 adds a button). The run stops "before failover" and nothing is changed.
- **After DOWN:** revoking prevents UP, and recovery is then manual (§4.7).
- Do not restart pods or flip the mutation switch during a run. Both create an `OWNER_LOST` incident.

### 4.7 Recovery by outcome

| Outcome | State | Action |
| --- | --- | --- |
| Stopped before failover | Nothing changed | Fix the cause, then make a new request. |
| Stopped after mutation, former active DOWN, new active healthy | Engineer confirms out of band | Run `clusterXL_admin up` on the former active **out of band**. It returns as standby under Active Up. Record it in the change record, verify `cphaprob stat`, release the incident (P0-1). |
| New active not passing traffic | Service impact | Engineer runs `clusterXL_admin up` on the former active. If needed, `clusterXL_admin down` on the new active to move traffic back (manual emergency failback). Then release the incident. |
| `UNEXPECTED_ROLES` after UP | Probably preempted to the original roles | Verify roles and health out of band, then release the incident. |
| `OUTCOME_UNKNOWN` / owner lost | Delivery uncertain | Do not repeat through neXus. Establish the state out of band first, then decide. |

`clusterXL_admin down` without `-p` does not survive a reboot.

### 4.8 Post-checks

- The run is DONE and every blocking check in pre, post and post-return is PASS (visible after P0-3).
- Last confirmed roles are swapped: former standby ACTIVE, former active STANDBY.
- Out of band agrees, and there are no pnotes.
- Application owners confirm their services.
- Zero active incidents, and no `job_failure` mail.
- A readiness run after the pilot, and the next scheduled pass, are READY with the swapped roles.
- Record in the change record, through aiview: run id, phase durations, the post-switch traffic ratio, poll count.
- Success enables no further unit or vendor automatically.

## 5. Open PO decisions

1. **Incident release (P0-1).** Who may release: one `security_admin`, or two people? Build the product route
   before the pilot, or use a reviewed SQL statement with a per-use DB-write OK for the pilot only?
2. **Primary Up units (P0-2).** Refuse them, as recommended, or allow them with an expected-preemption return?
3. **Manual recovery (P1-4).** If the run stops with one member DOWN, may the firewall team run
   `clusterXL_admin up` out of band, or should neXus offer a typed "return former active" action?
4. **Approval window bounds (P1-1).** Recommended 30–120 min.
5. **Palo Alto.** Out of this pilot, as recommended, or schedule the route, preemption and acceptance work now?
6. **Mutation switch.** Keep it on for the whole estate between pilots (current state), or off except during an
   agreed pilot time (restart required)?
7. **Pilot acceptance.** Is one DONE run on one unit enough to enable general use, or are a second unit and a review
   required first?
8. **Business hours.** Is the first pilot run inside 08:00–20:00, with the acknowledgement and business-impact
   exposure that implies, or outside it with less staff present? Recommended: outside business hours and outside the
   first hour after a scheduled slot, with the firewall team and application owners booked.
