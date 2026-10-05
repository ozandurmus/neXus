# Module pod runtime contract

Status: FROZEN
Approver: PO 2026-10-05
Direction: [MODULE_PER_POD_PLAN.md](MODULE_PER_POD_PLAN.md), §§2–8. Source baseline: `95783fa70506eda92548321f9e583b15a5bf9484` (local `origin/main`).

## 1. Scope and ownership

This contract freezes the eight migration steps, without authorizing deployment,
device contact, new commands or increased concurrency. Existing gates, evidence,
identity, encryption, RBAC and mutation rules remain authoritative. Retain one image.
Resource figures in the plan are guidance: modest requests, no CPU limits and
generous memory limits; measured sizing must preserve DB and device budgets.
Closed roles: `policy`, `backup`, `inventory`, `failover`, `diagnostics`,
`configuration`, `compliance`, `scheduler`, `general`. Unknown/conflicting roles
fail startup; compose only the selected role's executors and secret dependencies.

| Owner role | Exact job_type = capability_id (37 total) |
| --- | --- |
| policy (2) | `cp_policy_collect`, `pan_policy_collect` |
| backup (9) | `cp_gateway_backup`, `cp_spark_sftp_backup`, `cp_gaia_snapshot`, `pan_device_state_backup`, `cp_mds_export`, `https_vendor_backup`, `rdw_cc_config_backup`, `asa_config_backup`, `fgt_config_backup` |
| inventory: enrollment (5) | `device_confirm_check_point`, `device_confirm_palo_alto`, `device_confirm_https`, `device_confirm_cisco_asa`, `device_confirm_fortigate` |
| inventory: inventory (5) | `cp_inventory_collect`, `pan_inventory_collect`, `https_inventory_collect`, `asa_inventory_collect`, `fgt_inventory_collect` |
| inventory: discovery (5) | `cp_discovery_enumerate`, `pan_discovery_enumerate`, `rdw_discovery_enumerate`, `fmg_discovery_enumerate`, `bcmc_discovery_enumerate` |
| failover (4) | `cp_failover_readiness`, `pan_failover_readiness`, `cp_cluster_failover`, `pan_cluster_failover` |
| diagnostics (2) | `diagnostic_read`, `fmg_interface_detail` |
| configuration (5) | `cp_configuration_collect`, `pan_configuration_collect`, `fgt_configuration_collect`, `asa_configuration_collect`, `proxysg_configuration_collect` |
| compliance (0) | No durable claim types; evaluation and cache owner |
| scheduler (0) | No durable claim types; producers/notifications only |
| general (temporary) | Exactly the union of modules explicitly assigned to fallback below |

Admission validates the exact pair. Atomic claim SQL requires BOTH allow-lists,
`job_type = capability_id`, effective owner and non-draining ownership generation.
Preserve `UPDATE ... FOR UPDATE SKIP LOCKED`, existing priority/submitted_at/job_id
ordering, epoch-fenced writes, ten-minute job leases and existing heartbeats.
`ClaimStatementText.SQL` and `JooqJobLeaseDao.CLAIM_SQL` stay identical and tested.
Unknown/mismatched pairs stay unclaimed with an alert; no claim-then-filter.
Restore/scripts/internal `pan_set_config_read` gain no claims. Discovery enqueues
a separate ledgered policy job only after session close.
The temporary DB flag `fallback_enabled` is per module, false by default; true
assigns that module's complete table set to `general` and disables its dedicated
claim owner. Initially seed true ONLY for the explicit unmigrated remainder.
Only a PO-authorized release operator, through restricted deployment control,
may change it; no worker, scheduler, health probe or UI persona may auto-enable it.
Every change requires the §3 drain barrier, a compare-and-set generation change,
and a durable audit: module, old/new owner and flag, generation, actor fingerprint,
PO authorization reference, reason, snapshot id and time. No raw account names.
A failed module stays queued/alerted; fallback never means wildcard ownership.

## 2. Global endpoint admission

Every pod and every entry point (including service-side failover) must acquire
a DB-backed lease for the actual `(endpoint_ref, purpose_class)` BEFORE opening
SSH/HTTPS/SFTP or any other device transport, including authentication/reconnect.
Reuse a live session's permit until close; each parallel session needs a permit.
Purpose is server-selected: `ENROLLMENT`, `INVENTORY`, `DISCOVERY`, `POLICY`,
`BACKUP`, `CONFIGURATION`, `DIAGNOSTIC`, `FAILOVER_READINESS`, `FAILOVER_EXECUTION`.
This is neither ActionClass nor sensitivity class; it grants no command permission.
Resolve opaque endpoint references only through proven registry relationships;
manager contact consumes the manager's budget even for child-initiated work.
Aliases/purposes share budgets; unproven sharing blocks contact as NOT_EVALUABLE.
Limits preserve today's proven restrictions; no independent per-pod multipliers:

- Ordinary endpoint sessions: one across ALL purposes. Controller backup,
  inventory and confirm share the existing one-at-a-time manager restriction.
- Existing approved CP parallel policy path only: at most four sessions at that
  endpoint AND four CP policy sessions fleet-wide, including fallback. Policy
  sessions may share with policy only; they exclude ordinary sessions and vice
  versa. Retain configured 1–4 maximum, adaptive reduction, pacing and deadlines.
- Other vendor/path limits that are stricter still apply. The ordinary default
  is conservative serialization; source has no proven universal endpoint cap.
- Atomic fleet admission remains at most ten CLAIMED/EXECUTING device jobs;
  retain the existing CP/PAN inventory cap of ten and per-role plan caps.
  Failover execution additionally requires one durable fleet execution permit
  and existing cluster/member locks across queued AND service execution paths.

Acquire all required endpoint/fleet transport permits in one short transaction,
serialized by DB advisory transaction locks in stable key order. Check compatible
occupancy across purposes, job epoch/owner generation and drain state atomically;
never hold a connection, transaction or advisory lock during device work.
Fleet job slots are counted/fenced in the atomic claim transaction, not granted
independently by pods. DB failure denies new claims/contact; local semaphores
may further restrict capacity but can never replace shared admission.
Endpoint waiters use FIFO `(requested_at, request_id)` across purposes; no later
compatible waiter bypasses an earlier incompatible one. Claims retain existing
job priorities; FIFO applies once endpoint admission is requested. Cancellations
remove waiters; stale waiters expire after 60 s without renewal. Preserve the
original ticket on a bounded retry, with at most one live ticket per job/session.
Admission waits at most 30 s per turn; contention records ADMISSION_WAIT, not a
device failure. Before contact, return CLAIMED to REQUESTED under its epoch,
with a 10 s not-before retry and no attempt/failure-budget consumption. Already
executing multi-unit jobs checkpoint before yielding; never requeue an uncertain
mutation. Queue/permit waits do not alter device health or reset rate limits.
Lease TTL is 60 s using DB time, renewed every 10 s while transport is owned.
Grant/renew/release uses a unique token plus incrementing permit epoch, bound to
the job epoch (or fenced service operation) and ownership generation. Validate
before every open/command; stale holders cannot renew, release successors or publish.
Renewal/ownership loss stops commands, closes transports and retains checkpoints.
Expired leases are reclaimed transactionally, separately from job recovery.
TTL cannot fence a remote operation: reclaim capacity only with confirmed
closure or a proven transport/operation termination bound. Otherwise quarantine
the slot (still counted), report UNKNOWN and require authorized reconciliation;
never start a second mutation merely because a timestamp expired. Pre-cutover
crash tests must prove closure/bounds for the existing transports; absent proof
blocks that path's rollout, not this rule. No device command is added to prove it.

Minimal additive schema sketch (names normative, storage types implementer-owned):

- `module_runtime_control`: module PK, effective_owner, fallback_enabled,
  generation, drain_requested, drain_generation, drain_ack_at, owner_instance,
  owner_heartbeat_at; CHECK closed role/flag combinations. Claim/control changes
  lock the same row, so drain and claim cannot race. One active owner generation.
- `endpoint_admission`: request_id PK (ordered ticket), endpoint_ref, purpose_class,
  job_id/job_epoch OR operation_ref/operation_epoch, session_ref, owner_role,
  owner_instance, owner_generation, lease_token, permit_epoch, state
  (WAITING/LEASED/QUARANTINED), requested_at, not_before, heartbeat_at, expires_at.
  CHECK exactly one job/operation identity, valid purpose/state and positive epoch;
  UNIQUE live operation/session ticket; indexes `(endpoint_ref, state, requested_at,
  request_id)`, `(state, expires_at)` and `(owner_role, owner_generation, state)`.
- `runtime_task_lease`: task_key PK, owner_role, owner_instance, owner_generation,
  token, epoch, state (LEASED/QUARANTINED), heartbeat_at, expires_at; index expires_at.
  Reuse for singleton ticks and fleet failover execution (including §2 quarantine). Endpoint rows provide
  the fleet CP session count under the same grant lock; no duplicate counter.
- Add a jobs admission not-before timestamp for bounded contention deferral.
  Use existing audit storage for ownership/drain/lease changes; additive migration
  requires `GRANT SELECT, INSERT, UPDATE, DELETE ON <table> TO ui2_app;` per new table.

Metrics: role/purpose queue depth and oldest age, permit occupancy/quarantine,
wait duration/timeouts, stale waiters, expiry/reclaims, heartbeat age/loss and
owner conflicts; fleet job/session occupancy and DB pool wait/timeouts. Labels
contain only roles, purpose classes and opaque instance ids, never endpoints,
principals or device identities. Alert on capacity breach, lost owner/heartbeat,
quarantine and bounded-drain expiry; do not report admission waits as unhealthy.

## 3. Drain and recovery

Normal replacement first persists drain_requested and increments drain_generation;
claims serialize with that write. The matching owner stops claims and new units,
acks that generation, then finishes or durably checkpoints its current unit.
Continue job/permit heartbeats through session close, release permits, and report
zero matching jobs, transports, ticks and HTTP requests before replacement.
Use owner records plus exact capability sets for legacy rows, never pod names.
Deploy waits ONLY for its module; drain ack plus zero counts forms the fenced
barrier. New work may queue; unrelated modules retain their digest and continue.

SIGTERM invokes the same handshake: stop claiming, finish/checkpoint within the
840 s application drain budget inside 900 s pod grace, close/release, then exit.
Do not release a permit while its transport is still open. At grace expiry,
unclosed permits expire/quarantine under §2; executing jobs follow JobReconciler:
CLAIMED without attempt → REQUESTED; EXECUTING with all mutation boundaries false
→ REQUESTED (epoch ≥5 fails); unconfirmed mutation → OUTCOME_UNKNOWN, never
requeue; preserve the existing cancellation branch. No invented terminal success.
Policy retains published units and resumes only unfinished units for the same
request/source version, with epoch-fenced idempotent publication. Checkpoint
storage exists; resumability must be implemented/tested, not assumed from it.

Normal rollout waits before SIGTERM; policy/export may exceed grace. A bounded
deploy wait timeout leaves the old digest intact, reports BLOCKED and
clears drain only by audited control to resume that owner; unrelated targets may
complete. HTTP owners drain requests/background work; scheduler drains its ticks,
not the jobs it submitted. First upgrade of a pre-contract general binary alone
requires draining ALL its work; module-only replacement begins after that upgrade.

## 4. Single scheduler ownership

Preserve plan §3 cadence, timezone, settings, watermarks and catch-up; every task
below has exactly one effective owner, including startup listeners.

| Task key / source symbol | Final owner |
| --- | --- |
| `NightlyInventoryScheduler` (23:00 / 23:30 retry) | scheduler |
| `DiscoveryRefreshScheduler` | scheduler |
| `MdsExportScheduler` | scheduler |
| `CyberControllerBackupScheduler` | scheduler |
| `BackupScheduleTrigger` / `BackupScheduler.tick` | scheduler |
| `OnboardingFlowScheduler.tick` | scheduler |
| `CpReadinessScheduler.run` | scheduler |
| `CpFailoverService.startDue` | scheduler |
| `ClusterDiffTask` (startup + periodic) | configuration |
| `ComplianceWarmup` (startup + periodic) | compliance |
| `NotificationForwarder.tick` | scheduler |
| `JobReconciler.reconcileOnce` | scheduler |
| `OrphanArtefactPurger.runOnce` | backup |
| `RetentionPruningService.prune` | backup |
| `WorkerClaimLoop.scheduleHeartbeat` | claiming job's owner, keyed by job id/epoch |
| `FailoverScheduleService.reconcileOrphanedAttemptsOnStartup` | failover |

Use runtime_task_lease (60 s TTL, 10 s heartbeat) for each singleton task; acquire
under an advisory transaction lock. Every DB effect/enqueue verifies token/epoch
in its transaction. Per-job heartbeats use the existing job fence instead.
Keep notification transaction locking/watermarks and existing delivery guarantees;
a lease does not create exactly-once SMTP/syslog delivery. Losing ownership stops
further sends/ticks. Failover startup recovery checks durable liveness, not process
startup alone. Policy deadline timers remain local to the owning run.
Until transfer, the exact current owner in plan §3 remains sole owner. Disable
BOTH old startup listeners and timers, drain, transfer the fenced owner, then
enable the new role. No simultaneous old/new scheduler; fallback flag alone
never transfers service timers. Audit that transfer with the same snapshot id.

## 5. Snapshot and rollback

Before EVERY step, including compatibility bootstrap, capture a complete release
snapshot through existing release_snapshot tooling; record its exact snapshot id,
immutable old/new digests, schema version, ownership generations, fallback flags
and task owners in the private release record. Reject incomplete/checksum-invalid
snapshots. Preview the rollback and prove additive-schema compatibility before
cutover; never use a moving `latest` reference for a recorded step's rollback.

To return a module: drain/fence its new owner, account for leases and UNKNOWN
outcomes, stop it, restore its snapshot-compatible digest/configuration, then
atomically set fallback_enabled=true for ONLY that module and clear the general
owner's matching drain. Restore transferred background duties under §4 fencing.
Verify exclusive claims and queued progress. Keep job IDs, epochs, idempotency
keys, published units, encrypted artifacts, rate limits and quarantine records.
Never restore DB data, drop additive schema or replay uncertain writes.

Existing rollback tooling restores a release and checks global in-flight jobs;
it is not yet module-selective. Targeted rollback/control recording is a first-step
implementation prerequisite. A pre-limiter binary cannot run beside split owners:
full rollback to it requires all split device owners drained/stopped. Otherwise
use a compatible general worker; keep it available through the compatibility
window. Remove fallback only after its claim set and scheduled duties are empty.

## 6. Acceptance per migration step

For EACH row: run targeted Java and affected regression in the authorized container
workflow, claim-SQL equality, deployment-manifest and repository privacy gates.
Preview the exact candidate commit before production: positive/negative ownership,
malformed pairs, concurrent claims, FIFO/timeouts without health failure, shared
caps across two processes, crash/DB-loss/stale epochs, cancellation, drain race,
long unrelated job continuity, checkpoint resume and snapshot-based rollback.
Production requires separate deployment authority, a fresh snapshot, masked aiview
e2e after EVERY deploy (any page/API 4xx fails), owner/budget/pool/lease telemetry,
and the approved module evidence below. Unavailable evidence stays UNVERIFIED.

| Step | Additional preview proof | Production acceptance (separate contact approval) |
| --- | --- | --- |
| 1 Policy + shared groundwork | CP/PAN fixtures, six-hour allowance, partial units/transcripts, resume and compatible fallback | Long CP run survives unrelated deploy without epoch change; PAN parity; combined CP sessions ≤4 |
| 2 Backup | All nine types; digest/decryption/download, retention and manager serialization | Representative existing backup paths and long export continuity; no duplicate start or restore execution |
| 3 Inventory/discovery | All 15 types, DRAFT enrollment, priority/cap and enqueue-only policy dependency | Masked inventory/discovery relationships match; manager sessions serialized |
| 4 Diagnostics | Both types, gate/masking/download RBAC and persisted one-minute admission | Exact separately approved read; duplicate/denied requests make no contact |
| 5 Failover | Four types AND service execution path; fleet fence, crash/quarantine/token/deadline matrix | Approved CP/PAN readiness; execution remains UNVERIFIED without exact separate approval |
| 6 Configuration | Five collectors, parser API/diff parity and intent/runtime separation | Approved configured-state collection and masked refresh; internal clients remain compatible |
| 7 Compliance | Warmup/cache ownership, invalidation, first-load/framework parity | Masked Compliance/Overview over stored evidence; no device contact |
| 8 Scheduler/notifications | Every §4 task with controlled clock, restart/catch-up, no duplicate admissions | Due-run/relay observation; missing-owner alert/recovery; no new schedules enabled |

Stop progression on any owner overlap, budget breach, stale-owner contact, DB pool
exhaustion, privacy/egress violation, e2e failure or rollback/drain failure. Test
CNI/PVC isolation and measured memory/DB demand in the authorized environment.
This documentation lane executes none of these deployments or device checks.
