# PO decision record — 2026-10-20: administrators' manual jobs are not held to the job windows; business-hours confirmation

**Status: RATIFIED — PO DECISION (chat, 2026-10-20), relayed by the orchestrator.** Recorded by the engineering session
from the orchestrator's brief. It **supersedes in part** the job-window decision of 2026-10-08 ("all device jobs,
automatic and manual, including failover, only in the 00/06/12/18 Europe/Istanbul windows"): the part that held
**manual submissions by `security_admin` and `operation_admin`** to the windows. Everything else of that decision
stands. The 2026-10-08 decision has no record of its own in `docs/design/`: it lives in commit `e19f1315` ("Enforce
shared Istanbul windows for device job admission and scheduling"), in
`ui2/platform-core/.../platform/JobWindowPolicy.java` and in `ui2/job-engine/.../jobs/window/JobWindowPolicyTest.java`.
This record is therefore also the first written statement of what remains of it (section 4).

## 1. Decision (PO, intent)

> The six-hour windows were set for inventory / configuration / data collection / backup. Operational actions are
> under administrator authority; closing them to an administrator makes no sense. Block nothing for an administrator;
> only warn during the working day: "we are in business hours — are you sure?"

Answers the orchestrator obtained:

- **Business hours** = every day (weekends included), **08:00–20:00 Europe/Istanbul** (GMT+3).
- The bypass applies to **administrator roles only**: `security_admin` and `operation_admin`.
- **Operator and aiview** (`replay_viewer`, including the aiview machine session) manual submissions **stay
  window-gated**, unchanged (`409 OUTSIDE_JOB_WINDOW`).
- **Scheduled and automatic jobs keep the windows exactly as before.**

## 2. What the product does now

### 2.1 Admission

A manual submission is an HTTP request (screen or CLI). After the gate chain has authenticated and authorized it,
`ManualAdmissionInterceptor` asks `AdminWindowBypass` whether the actor holds `security_admin` or `operation_admin`
and is not a replay viewer (the root administrator counts as an administrator). If so, it opens a per-request,
per-thread grant (`ManualAdmission.Grant`: actor, role, acknowledgement) and always closes it when the request
completes. While the grant is open, `JobWindowPolicy` never refuses that actor's job for the window:

| Actor | Inside a window | Outside every window |
|---|---|---|
| `security_admin`, `operation_admin`, outside business hours | admitted | admitted (no confirmation) |
| `security_admin`, `operation_admin`, inside business hours, no acknowledgement | `409 BUSINESS_HOURS_CONFIRM_REQUIRED`, nothing admitted | same |
| `security_admin`, `operation_admin`, inside business hours, acknowledged | admitted | admitted |
| operator, aiview (`replay_viewer`, machine session) | admitted | `409 OUTSIDE_JOB_WINDOW` (unchanged; an acknowledgement changes nothing) |
| every scheduler and worker (`system:*`) | admitted | refused / not produced (unchanged) |

Inside business hours the administrator is asked even when a window is open (12:00 and 18:00 windows fall inside
business hours): the warning is about the working day, not the window.

The grant is a thread-local and is never inherited by another thread, so a scheduler, a worker or an asynchronous task
never sees one. A grant only counts for the actor who holds it (`ManualAdmission.currentFor`): a job submitted for any
other actor in the same request keeps the window. A check against an explicit time (a scheduled failover's start time,
`requireOpen(Instant)`) and every `isOpen` read the schedulers use ignore grants.

### 2.2 The acknowledgement

- **Flag:** request header `X-Nexus-Business-Hours-Ack: true`, or the query parameter `business_hours_ack=true`. It is
  read from the request line and headers only, never from the body (so a body-less "Collect now" POST and a multipart
  upload carry it the same way, and no body is read before the handler). This replaces the body field
  `"business_hours_ack": true` suggested in the brief, which many of these routes (body-less POSTs) cannot carry.
- **Refusal:** `409` with `{"code": "BUSINESS_HOURS_CONFIRM_REQUIRED", "message": "Business hours (08:00–20:00 GMT+3).
  This sends commands to the device. Continue?", "business_hours_start": "08:00", "business_hours_end": "20:00",
  "zone": "Europe/Istanbul", "ack_header": "X-Nexus-Business-Hours-Ack", "ack_parameter": "business_hours_ack"}`.
- **Screen:** the API client shows the confirm dialog ("Business hours" / the message above / Cancel, Continue) and, on
  Continue, repeats the same request once with the header. A Continue covers the requests of the next 60 seconds, so a
  bulk readiness run asks once rather than once per cluster; every job still records its own acknowledgement. Cancel
  admits nothing ("Not started: business hours were not confirmed."). The PO's Turkish wording ("Mesai saatindeyiz
  (08:00–20:00). Bu işlem cihaza komut gönderir. Emin misiniz?") is rendered in the product language (English).
- **CLI:** `--business-hours-ack` anywhere on the command line sends the header for that run; without it the CLI prints
  `BUSINESS_HOURS_CONFIRM_REQUIRED: <message> Nothing was submitted; repeat the command with --business-hours-ack to
  confirm.` New parity commands: `readiness-run <clusterId> <unitId> [check_point|palo_alto]` and
  `policy-collect <sourceId> [CHANGED_ONLY|FULL] [domainRef]`; every other submission command (and `api`) takes the
  flag too.
- **Hours:** `BusinessHours` (platform-core) is the single source of truth: default `08:00-20:00`, overridable with
  `UI2_BUSINESS_HOURS=HH:mm-HH:mm`; evaluated in `JobWindowPolicy.ZONE`, start inclusive, end exclusive.

### 2.3 What is recorded (V168)

`jobs` gains four nullable columns, written by `ManualAdmissionRecord` in the admission's own audited transaction (so
`fn_audit_capture` puts them on the audit ledger; all four are audited in full):

| Column | Meaning |
|---|---|
| `window_bypass_role` | `security_admin` or `operation_admin`: the job was admitted under the administrator's grant |
| `submitted_in_business_hours` | whether it was submitted inside business hours |
| `business_hours_ack_by` | inside business hours: the actor fingerprint that acknowledged the warning |
| `business_hours_ack_at` | inside business hours: when |

Checks: the acknowledgement's actor and time come together; a bypass job submitted inside business hours always carries
the acknowledgement (if business hours begin between the admission check and the record, the admission is refused with
`BUSINESS_HOURS_CONFIRM_REQUIRED` and rolls back). Every scheduled, automatic, operator and aiview job keeps all four
`NULL`.

### 2.4 Claim

A job with `window_bypass_role` set is claimable at any hour (`WHEN window_bypass_role IS NOT NULL THEN true` in the one
claim statement, `JooqJobLeaseDao.CLAIM_SQL` = `ClaimStatementText.SQL`). Every other job keeps the window end (and the
02:00 scheduled-backup branch is unchanged). Outside every window the worker first runs one cheap read ("is a bypass
job queued for my capabilities", partial index `jobs_window_bypass_requested`) and issues the claim statement only when
one is: an administrator's job never waits for the next slot, and nothing else is claimed.

### 2.5 Failover — every other safeguard unchanged

Only the window gate changes. The four places the failover path checked the window behave as follows:

| Site | Before | After |
|---|---|---|
| `CpFailoverService.createApprovalRequest` | window | administrator: no window and no acknowledgement (sends nothing to a device); everyone else: window |
| `CpFailoverService.request` / `JooqCpFailoverRepository.requestBound` (immediate start) | window | administrator: no window; acknowledgement inside business hours; the repository re-checks inside the same call |
| `requestBound` / `CpFailoverService.request` (scheduled start, `scheduledFor` set) | window now + window at the scheduled time | administrator: no window now, **window at the scheduled time kept**; started by `startDue` (scheduler, window) as before |
| `JooqCpFailoverRepository.admit` (the job insert) | window | the creating actor's grant: no window; records the grant and acknowledgement on the job row (2.3) |
| `JooqJobLeaseDao.claimNext` (worker claim) | window | bypass job: claimable at once, never deferred to the next slot; scheduled start: window |
| `startDue` (scheduled failover start) | window | window, whatever thread calls it |

**Recheck before each device write.** `JooqCpFailoverRepository.mutationAdmission` — the fence every `clusterXL_admin
down/up` (Check Point) and suspend/resume (Palo Alto) write passes first, after the ownership check and before the
dispatch is authorized — now also reads the job row: a bypass job **without** an acknowledgement (it was submitted
outside business hours) is refused with `BUSINESS_HOURS_CONFIRM_REQUIRED` once business hours have begun, and the run
stops before the write. An acknowledged job, and a window-admitted (scheduled) job, are unaffected.

Unchanged: four-eyes for `operation_admin` (`OPERATION_ADMIN_TWO_PERSON`), `security_admin` single approval, request
binding (request id, revision, nonce, warning confirmation, policy, member set), approval window
(`window_from`/`window_until`, `OUTSIDE_WINDOW` there is the approval's own window, not the job window), quarantine and
open incidents, unresolved dispatch fences, readiness evidence collected afresh by the job, idempotency, the mutation
switch, the trusted host key requirement. Readiness ≠ authorization still holds.

## 3. Gates changed (manual submissions)

| Submission | Where the window was enforced | After |
|---|---|---|
| Collect now: inventory, configuration (one device, collect-all) | `JooqJobRecordDao.insertRequestedIfAbsent` (`requireDeviceJobOpen`) | admin bypass + record |
| Manual backup (one device, collect-all, run) | same | admin bypass + record (the 02:00 scheduled backup is the backup scheduler's actor, unchanged) |
| Discovery run, enrollment confirm / onboarding retry (manual) | `insertRequestedIfAbsent` / `insertRequestedIfAbsentForRun` | admin bypass + record |
| Policy collection (`POST /api/v2/policy/sources/{id}/collect`) | `insertRequestedIfAbsentForRun` via `PolicyCollectionRepository.enqueue` | admin bypass + record |
| Diagnostics (`fmg_interface_detail`, `diagnostic_read`) | `insertDiagnostic`, `insertDiagnosticRead` | admin bypass + record; the per-device one-minute limit and in-flight refusal are unchanged |
| HA readiness run (Check Point, Palo Alto) | `CpFailoverService.submitReadiness`, `JooqCpFailoverRepository.requestReadiness`, `admit` | admin bypass + record; operator and aiview unchanged |
| Failover approval request, start | section 2.5 | section 2.5 |
| Spike Detective layout review (one-off) | `SystemLogMeasurementService.startSpikeLayoutReview` | admin bypass (slot-keyed idempotency kept) |
| Packet Analysis | never window-gated (stored data) | unchanged |
| Frontend: `JobButton`, `M3Button deviceJob`, backup row menus, policy menu items, HA readiness row actions | `useGate` / `useJobWindow().open` | enabled for an administrator (`admin_window_bypass` from `GET /api/v2/job-window`); operator and aiview keep "Next window: HH:MM" |

`GET /api/v2/job-window` adds `business_hours: {start, end, inside}` and `admin_window_bypass` (this session).

## 4. What stays window-bound (not changed by this decision)

- Every scheduler and automatic producer: nightly/6-hourly inventory, configuration refresh, discovery refresh,
  scheduled readiness, scheduled failover start (`startDue`), onboarding flow advance, the automatic policy job queued
  after a discovery, system-log scheduled trigger, and the 02:00 scheduled backup window.
- Onboarding: an administrator's add/retry is admitted at once, but the following steps are advanced by the onboarding
  scheduler inside the windows, as before.
- **System-log collection and measurement starts (`syslog-collect-start`, `syslog-measure-start`) stay window-gated for
  every role, administrators included.** `DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md` (APPROVED 2026-10-17) §3.1 says "Manual
  starts obey the same windows", and its approved command table sets the commands' frequency per job window (e.g. C1/C3
  "once per device per job window (≤ 4/day)"). Changing that frequency is a network-device command gate change that this
  decision does not make (AGENTS.md, Network action taxonomy). **Open PO question:** should administrators' manual
  system-log starts also bypass the windows (which raises those commands' maximum frequency)?
- **Policy completeness side-readers** (`CpShowChangesReader`, `PanoramaConfigLogReader`, the P-1 NAT hit measurement)
  still run only in a policy job that started inside a window; in an administrator's job outside a window they record
  `UNKNOWN OUTSIDE_JOB_WINDOW` in the transcript and the collection itself completes. Their approved frequency is also
  set per job window (`POLICY_COLLECTION_COMPLETENESS_PLAN.md` §6, "≤ 4 pages per Panorama per job window").

## 5. Validation

Automated only (`IMPLEMENTED → AUTOMATED_VALIDATED`); real-environment validation is a separate gate. Tests:
`AdminManualAdmissionPolicyTest` (job-engine: role × window × business hours × acknowledgement, scheduler/explicit-time
checks ignore grants, grant for another actor, hours), `AdminManualAdmissionDaoTest` and
`FailoverBusinessHoursRecheckTest` (persistence: Collect now, configuration, backup, policy collection, diagnostics,
readiness, failover start, scheduled start, claim branch, record and recheck), `CpFailoverServiceTest` (readiness and
failover start matrices, approval request, scheduled readiness), `ManualAdmissionInterceptorTest`,
`JobWindowAdviceTest`, `JobWindowControllerTest`, system-log service tests (service), `NexusSessionCliTest` (CLI), and
`JobWindow.test.tsx`, `BusinessHoursConfirm.test.tsx`, `HaReadinessList.test.tsx` (frontend). The Postgres
integration suite (V168 against a live schema, `AuditRedactionPolicyTest`) was updated but not run in the engineering
lane (no container runtime); V168 needs the BEGIN/ROLLBACK dry run on the live database before rollout.
