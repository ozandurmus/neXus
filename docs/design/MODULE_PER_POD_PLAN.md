# Module-per-pod migration plan

Status: APPROVED — direction approved by PO 2026-10-05; not a device-command contract.
Shared runtime semantics: [MODULE_POD_RUNTIME_CONTRACT.md](MODULE_POD_RUNTIME_CONTRACT.md) (FROZEN).
Date: 2026-10-05. PO decision: `module_per_pod_split` (2026-09-22), reaffirmed 2026-10-05.
Source baseline: `b8472c07c0cfe739c7bf2367117e9f677347becb`, branch `sa/module-per-pod-plan`.

## 1. Decision, evidence and acceptance

Give policy, backup, inventory/discovery, failover, diagnostics, configuration,
compliance and scheduler/notifications independent Deployments. Keep the API/UI
service and the existing image/build. Start with policy so a long collection does
not block an unrelated module deployment. Do not add a broker, dependencies,
device commands, collector changes or separate module images.

The brief supplies these operational observations: 30–55 minute CP policy runs;
60 GB RAM, approximately 56 GB free, 16 cores; worker usage 60–190 MB;
PostgreSQL `max_connections=100`, 24 connections currently used. These are
planning inputs, not measurements made in this lane. `CURRENT_STATE.md` records
an older schema-91 checkpoint; source inspection, not that snapshot, establishes
the inventory below. No host, database, device or deployment was contacted.

Phase 1 acceptance is this source-backed plan and local validation only.
Implementation acceptance later requires disjoint ownership, unchanged evidence
and authorization behavior, global safety budgets, independent drain/rollback,
masked e2e after every deploy, and approved module-specific real-environment
checks. This document grants none of those future execution permissions.

### Source notation

Paths below are relative to the repository. To keep tables readable:

- `W/` = `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/`
- `J/` = `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/`
- `P/` = `ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/`
- `S/` = `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/`

Symbols are provided as stable lookup points; observations apply to the pinned
source, not an asserted live image digest.

## 2. Complete executable job inventory

`W/confirm/WorkerClaimLoop.ELIGIBLE_CAPABILITY_IDS` contains **37** capabilities.
All are claimed today by `ui2-worker`, through ten claim loops. None is claimed
by the current configuration or compliance pod. Their current roles start
HTTP servers only (`/api/v1/parse` and compliance catalog/evaluate respectively).

`J/admission/PersistenceJobAdmissionRepository` writes `job_type` equal to
`capability_id`; policy and failover writers do the same. The actual claim
predicate is on **capability_id**, not job_type. Preserve this distinction.
The table enumerates every eligible value, without wildcard admission.

| Future owner | Exact job_type / capability_id values | Today's executor / admission evidence |
| --- | --- | --- |
| policy (2) | `cp_policy_collect`, `pan_policy_collect` | `W/policy/PolicyCollectionJobExecutor`; `P/policy/PolicyCollectionRepository.enqueue` |
| backup (9) | `cp_gateway_backup`, `cp_spark_sftp_backup`, `cp_gaia_snapshot`, `pan_device_state_backup`, `cp_mds_export`, `https_vendor_backup`, `rdw_cc_config_backup`, `asa_config_backup`, `fgt_config_backup` | `W/backup/BackupJobExecutor`, specialized executors wired by `Ui2WorkerMain`; `J/admission/BackupCapabilityIds.ALL` |
| inventory/discovery: enrollment (5) | `device_confirm_check_point`, `device_confirm_palo_alto`, `device_confirm_https`, `device_confirm_cisco_asa`, `device_confirm_fortigate` | `W/confirm/ConfirmJobExecutor`, `W/backup/https/HttpsVendorConfirmJobExecutor`; `ConfirmCapabilityIds.ALL` |
| inventory/discovery: inventory (5) | `cp_inventory_collect`, `pan_inventory_collect`, `https_inventory_collect`, `asa_inventory_collect`, `fgt_inventory_collect` | `W/inventory/InventoryJobExecutor`, `W/backup/https/HttpsInventoryJobExecutor`; `InventoryCapabilityIds.ALL` |
| inventory/discovery: discovery (5) | `cp_discovery_enumerate`, `pan_discovery_enumerate`, `rdw_discovery_enumerate`, `fmg_discovery_enumerate`, `bcmc_discovery_enumerate` | `W/discovery/DiscoveryJobExecutor`; `DiscoveryCapabilityIds.ALL` |
| failover (4) | `cp_failover_readiness`, `pan_failover_readiness`, `cp_cluster_failover`, `pan_cluster_failover` | `W/failover/CpFailoverJobExecutor`, `PanFailoverJobExecutor`; `P/JooqCpFailoverRepository` job insertion |
| diagnostics (2) | `diagnostic_read`, `fmg_interface_detail` | `W/diagnostic/DiagnosticJobExecutor`, `W/backup/fortinet/FortiManagerDiagnosticJobExecutor`; diagnostic admission in `JobAdmissionService` / `JooqJobRecordDao` |
| configuration (5) | `cp_configuration_collect`, `pan_configuration_collect`, `fgt_configuration_collect`, `asa_configuration_collect`, `proxysg_configuration_collect` | `W/configuration/ConfigurationJobExecutor`; `ConfigurationCapabilityIds.ALL` |
| compliance (0) | No durable job_type in the claim set | HTTP evaluation server plus service-side orchestration/cache |
| scheduler/notifications (0) | No durable job_type in the claim set | Producers, periodic local maintenance and forwarding; not a wildcard consumer |

Enrollment belongs with inventory/discovery because onboarding is confirm →
inventory → configuration, and confirmation already shares vendor transports.
Keep its unique DRAFT-device admission exception. The legacy FortiManager
diagnostic belongs with diagnostics despite its package location. Snapshots and
MDS export belong with backup despite not ending in `_backup`.

Restore is disabled in the supplied product checkpoint; no restore job appears
in the current worker claim set. Keep it disabled. A future authorized restore
belongs to backup/recovery, but this split must not invent a restore allow-list
entry. `pan_set_config_read` is an internal backup capability, explicitly absent
from `BackupCapabilityIds.ALL`, not an independently submitted job. Script
execution is likewise not an eligible job in this source set. Historical or
unknown database job types remain unclaimed and observable, never silently
assigned to the fallback worker.

### Important boundaries already present

- Discovery already queues a **separate ledgered policy job**:
  `Ui2WorkerMain` wires `DiscoveryJobExecutor.withPolicyCollection` to
  `PolicyCollectionJobExecutor.afterDiscovery`, which calls `enqueue` after
  the discovery session closes. Keep the admission callback in inventory,
  composing only its enqueue/gate dependencies; do not run a collector there.
- The policy automatic six-hour domain allowance is persisted in
  `PolicyCollectionRepository.beginDomain`. Preserve it across pod changes.
- Configuration parsing and compliance evaluation already use dedicated HTTP
  pods, but collection and warmup/persistence still belong elsewhere. Merely
  renaming Deployments would not complete their module ownership.
- `S/failover/FailoverExecutionService` also exposes a separate execution
  service, with a process-local fleet semaphore, through the execution/schedule
  controllers. `FailoverScheduleService` performs startup recovery. Do not
  assume the four queue types describe every failover control path; preserve
  and test these entry points when moving execution ownership.

## 3. Scheduled and background work inventory

All cron times below use `Europe/Istanbul`. Entries are source schedules;
enabled settings and last successful live runs are UNKNOWN in this lane.

| Current code / owner | Trigger and current behavior | Final owner |
| --- | --- | --- |
| `S/device/inventory/NightlyInventoryScheduler` / service | 23:00 inventory submission; 23:30 retry its own failed inventory jobs once | scheduler producer → inventory |
| `S/management/DiscoveryRefreshScheduler` / service | 01:00 manager discovery refresh | scheduler producer → inventory/discovery |
| `S/device/backup/MdsExportScheduler` / service | 02:00 MDS export, only following a completed operator export | scheduler producer → backup |
| `S/device/backup/CyberControllerBackupScheduler` / service | 03:00 controller backup | scheduler producer → backup |
| `S/boot/DeviceCompositionConfiguration.BackupScheduleTrigger` → `BackupScheduler.tick` / service | Initial 30 s, fixed delay 60 s; enabled policy cron, six-hour catch-up; persisted slot claim | scheduler producer → backup; preserve disabled settings |
| `S/device/onboarding/OnboardingFlowScheduler.tick` / service | Initial 20 s, fixed delay 5 s; advances onboarding dependencies | scheduler producer → inventory then configuration |
| `S/failover/CpReadinessScheduler.run` / service | Every 5 s; first pass after min(10 min, cadence), default cadence 4 h, unit pause 2 s, scheduler run cap 10 min; covers CP/PAN readiness targets | scheduler producer → failover; retain cadence and admission |
| `S/failover/CpFailoverService.startDue` / service | Initial/fixed delay 3 s; validates due PLANNED runs and inserts executable jobs | scheduler producer → failover; preserve execution authorization checks |
| `S/overview/ClusterDiffTask` / service | Startup and every 300 s; compares stored sanitized configuration, persists derived difference summary | configuration |
| `S/compliance/ComplianceWarmup` / service | Startup and every 60 s; changed configuration/rules trigger evaluation/cache refresh | compliance |
| `S/notification/NotificationForwarder.tick` / service | Every 60 s; syslog audit/failure forwarding and SMTP routes; persisted watermarks, advisory transaction lock; caps 500 audit events / 200 failures per syslog pass | scheduler/notifications |
| `W/Ui2WorkerMain` → `JobReconciler.reconcileOnce` / worker | Initial 10 s, every 30 s; global lease-expiry reconciliation | scheduler; fallback retains it until transfer |
| `W/Ui2WorkerMain` → `OrphanArtefactPurger.runOnce` / worker | Initial 20 s, every 60 s; deletion requests | backup, singleton fenced task |
| `W/Ui2WorkerMain` → `RetentionPruningService.prune` / worker | Initial 120 s, every 3600 s; backup and configuration snapshot retention | backup/storage maintenance, singleton fenced task |
| `W/confirm/WorkerClaimLoop.scheduleHeartbeat` / worker | Per claimed job; max(5 s, lease/3), currently 200 s for a 10-minute lease | every owning job pod; not a central scheduler task |
| `S/failover/FailoverScheduleService.reconcileOrphanedAttemptsOnStartup` / service | ApplicationReady startup recovery, not a recurring dispatch timer | failover owner; must not interpret another live pod's work as orphaned |

The policy collectors also create per-run deadline/stream timers. They travel
with policy; they are not additional recurring business schedules.
`ui2/scheduler/.../DueScheduleEvaluator.java` is an interface, not a running
scheduler. Existing schedule controllers do not imply another periodic loop.

Use one scheduler/notifications pod: both activities coordinate durable records
and do not execute device transport. Module-local evaluation and storage cleanup
stay with their owners. Disable both startup listeners and periodic triggers in
the old composition when transferring them. Simply setting
`ui2.scheduling.enabled=false` is insufficient evidence: not all components
carry that conditional, and startup listeners can run independently.

## 4. Role selection, atomic claims and unchanged recovery

Reuse `ui2/platform-core/.../platform/launch/Ui2Launcher`: `worker` selects
`Ui2WorkerMain`, passing remaining arguments. That class currently recognizes
`configuration` and `compliance` via args or `NEXUS_WORKLOAD_ROLE`; otherwise it
builds all executors, secrets and ten loops. Add a closed role mapping there:
policy, backup, inventory, failover, diagnostics, configuration, compliance,
scheduler, and transitional general/fallback. Reject unknown/conflicting role
configuration rather than silently starting the broad worker.

The minimal implementation seam is `WorkerClaimLoop` construction and
`claimAndExecuteOnce`: supply a fixed eligible set from the table, instead of
the static all-capability set. Compose only that role's executors and secret
dependencies. Keep the same jar and existing executor methods. Do not instantiate
the complete worker and rely only on a claim filter to obtain least privilege.

Add a matching `job_type` allow-list predicate inside the atomic claim SQL,
alongside the existing capability filter, and validate their mapping at
admission/dispatch. This makes malformed job_type/capability pairs fail closed.
Pass the additional set through `J/lease/JobLeaseRepository`,
`PersistenceJobLeaseRepository`, `P/jobrecords/JobLeaseDao` and
`JooqJobLeaseDao.claimNext`. Update `J/lease/ClaimStatementText.SQL` and
`JooqJobLeaseDao.CLAIM_SQL` identically; their source-equality test is mandatory.
Do not claim globally and filter afterward: that would strand/steal jobs.

One committed mapping should feed claim composition, deploy targeting, queue
metrics and tests. Fallback owns **only the explicit unmigrated remainder**.
It is not a catch-all or automatic substitute for a failed module. A migrated
module outage leaves its jobs queued and raises an alert.

Preserve the existing single-statement `UPDATE … FOR UPDATE SKIP LOCKED`,
`lease_worker_id`, incrementing `lease_epoch`, ten-minute lease, heartbeat,
cancellation and epoch-fenced result writes. Preserve priority ordering within
each eligible set. `J/executor/JobReconciler` behavior must remain:

- Expired CLAIMED with no attempt → REQUESTED.
- Expired EXECUTING with all mutation boundaries false → REQUESTED; epoch ≥5
  fails rather than loops indefinitely.
- Unconfirmed mutation boundary crossed → OUTCOME_UNKNOWN, never requeue.
- Expired cancellation follows the existing cancellation branch.

Separate global capacity leases below are admission controls, not replacements
for this job state machine. Shared schema/admission/drain semantics are frozen in
[MODULE_POD_RUNTIME_CONTRACT.md](MODULE_POD_RUNTIME_CONTRACT.md); implementation
must satisfy that contract before split device owners are enabled.

## 5. Resources and connection budgets

### Current source settings

`S/boot/DatabaseConfiguration.dataSource`: Hikari max **20**, min idle **2**.
`W/WorkerDatabasePool.create`: max **10**, min idle **2**. Both default to
10 s connection timeout, 30 min max lifetime and 60 s leak detection.
Worker reads Java system properties; service reads Spring Environment under
`ui2.db.pool.*`. Committed manifests do not override these pool sizes. Current
HTTP-only configuration/compliance mains construct **no database pool**.
Therefore source-configured application maximum is **30**, not 40 or 60;
24 observed connections is utilization, not the maximum. Live overrides are
unverified. Set explicit per-role properties, including worker JVM `-D` values,
rather than assuming Spring environment binding works in the plain worker.

Committed requests/limits: service and worker each 500m/1Gi → 4 CPU/8Gi;
configuration 100m/256Mi → 1 CPU/2Gi; compliance 100m/256Mi → 1 CPU/4Gi
(`deploy/ui2/{50-service,52-worker,54-configuration,55-compliance}-deployment.yaml`).

### Proposed initial envelopes (one replica each)

These numbers are sizing guidance, not strict requirements or measured peak
guarantees: use modest requests, no CPU limits and generous memory limits.
Low idle worker RSS does not establish policy parsing or backup peak memory.
DB connection and device concurrency budgets remain safety constraints.

| Pod | CPU request / limit | Memory request / limit | DB maximum / min idle | Initial claim loops |
| --- | --- | --- | --- | --- |
| service | 500m / none | 1Gi / 8Gi | 16 / 2 | 0 |
| policy | 500m / none | 512Mi / 4Gi | 6 / 1 | 1, internal CP session cap ≤4 |
| backup | 250m / none | 256Mi / 2Gi | 4 / 1 | 2 |
| inventory/discovery | 250m / none | 256Mi / 2Gi | 6 / 1 | 2 |
| failover | 100m / none | 256Mi / 1Gi | 4 / 1 | 1 |
| diagnostics | 100m / none | 256Mi / 1Gi | 3 / 1 | 1 |
| configuration (collection + parser + diff) | 250m / none | 512Mi / 2Gi | 4 / 1 | 2 |
| compliance (evaluation + warmup persistence) | 100m / none | 256Mi / 4Gi | 3 / 1 | 0 |
| scheduler/notifications | 100m / none | 256Mi / 1Gi | 4 / 1 | 0 |
| fallback (temporary) | 250m / none | 256Mi / 2Gi | 10 / 2 | up to 10, shared global cap |

Final DB maximum **50**, min idle **10**. Worst transitional maximum **60**,
min idle **12**, leaving 40 of 100 for migrations, administration, transient
connections and failure investigation. Apply the service reduction 20→16 before
the final set of pods is enabled; verify pool wait/timeout telemetry first.
No unchecked second replica/surge: that multiplies the budget. Use Recreate
for job pods and serialize controlled replacements; include any service surge
in preflight, or also use its existing Recreate strategy. Build integration uses
its separate test database, never this production connection allowance.

Final CPU requests total 2.15 cores; memory requests 3.5Gi and limits 25Gi.
Including fallback: 2.4 requested cores, 3.75Gi requests, 27Gi memory limits.
Do not set CPU limits; reserve host capacity for PostgreSQL, k3s and builds
through modest requests and verify resource pressure/latency before increasing
parallelism. Memory figures remain generous starting guidance, not fixed ceilings.
Keep JVM heap/native-memory room within limits. Benchmark pool waits under
heartbeat plus persistence load; never hold a DB connection for a
30–55 minute device operation to enforce a semaphore.

### Shared safety budgets: prevent multiplication

| Source evidence | Current scope / hazard | Required global behavior |
| --- | --- | --- |
| `Ui2WorkerMain` fixed pool / ten loops | Ten jobs per process, not per fleet; copying to every pod multiplies this | One DB-serialized fleet budget of at most ten active claimed/executing device jobs, including fallback; module caps above are maxima, not reserved capacity |
| `JooqJobLeaseDao.CLAIM_SQL` advisory lock `294611` | Already limits CP/PAN inventory active jobs to ten globally; does not cover every inventory vendor | Retain exact existing cap and add the fleet admission ceiling without weakening this predicate |
| `CheckPointPolicyCollector.configuredMaxSessions`, `CpPolicyParallelCollection` | `UI2_POLICY_CP_MAX_SESSIONS` defaults to 4 (valid 1–4); each collection owns its pool/sessions and adaptive limit (starts at ≤2). This is **not** an established fleet semaphore | Cap CP policy SSH sessions at four across all pods/runs, including fallback and rolling overlap; preserve adaptive reduction, command pacing and deadlines |
| `CyberControllers.oneAtATime`, called by backup, HTTPS inventory and confirm | Static per-host/port ReentrantLock, hence JVM-local; splitting backup/inventory allows concurrent management logins | DB-backed endpoint permit of one across all these paths; use the actual contacted manager's opaque endpoint reference, not the managed child's identity |
| SSH transport and general claim loop | No universal cross-pod per-endpoint session limiter found | Add shared endpoint admission before transport open, covering all module paths and aliases via proven endpoint identity. Default serialize ordinary endpoint sessions; CP's existing approved parallel policy path keeps its ≤4 budget. Unproven sharing/identity means fail closed, not an invented equivalence |
| `S/failover/FailoverExecutionService.fleetConcurrencySemaphore` | Semaphore(1) is process-local; booking-window checks alone cannot fence independent execution owners | One durable fleet execution permit plus existing cluster/member locks, authz and quarantine; cover both queued and service execution entry points |
| `P/jobrecords/JooqJobRecordDao` diagnostic admission | Persisted active/recent target checks, including one-minute rate limit | Keep atomic DB admission and do not reset on pod restart |
| Policy automatic allowance, backup slot claim, notification advisory lock/watermarks | Already durable/global | Reuse them; do not replace with per-pod timers or local caches |
| Reconciler, retention, startup warmup/diff and other schedulers | Blindly cloning composition duplicates maintenance or delivery | One fenced task owner; preserve durable idempotency, protect startup callbacks too |

Use short PostgreSQL transactions with row/advisory serialization to acquire
capacity and endpoint permits; for transport sessions use renewable lease rows
carrying opaque resource reference, holder job/epoch, expiry and fencing token.
Acquire multiple resources in a fixed order; if unavailable, do not open a
session and do not spin. Avoid claiming a job merely to occupy scarce capacity
while it waits. Fleet job admission should be checked in the atomic claim path.

Refresh permits while work is active and release only after session close.
DB loss or lost ownership stops new contact and closes owned sessions; expiry
alone must not permit a second mutating operation while a prior owner could
still run. Require fenced ownership checks before contact, bounded transport
timeouts and conservative quarantine/UNKNOWN when closure cannot be proved.
Never let semaphore recovery bypass mutation-boundary reconciliation. A future
lease-table migration must include `GRANT SELECT, INSERT, UPDATE, DELETE ON
<table> TO ui2_app;` and receive the required schema/security review.

Global limiter support must run in fallback **before** enabling a second
network-capable module. An old binary unaware of the limiter cannot safely run
beside new owners. No replica increase or concurrency tuning is implicit in
this refactor.

## 6. Independent build/deploy and least privilege

### Existing deploy coupling and required change points

`scripts/hosta_deploy.sh` is only a compatibility wrapper.
`tools/delivery/hosta_deploy.sh` rejects **any** CLAIMED/EXECUTING job before
launching the build, watches service/worker pod names and a global completion
marker. `deploy/ui2-image-build/run_build.sh` updates service/compliance, waits
up to four hours on **all** active jobs, then updates worker/configuration.
This is the actual source of cross-module blocking.

Add an explicit validated module target list to both canonical scripts. Build
the same immutable image once; retain the previous digest per Deployment.
Update and monitor only the selected Deployments. Untargeted modules retain
their digest and keep working. For shared ABI/schema changes, declare the
dependent targets and compatibility window explicitly; single image does not
mean every module must be replaced together.

A module deployment must first disable claims for its owner through a
DB-backed drain flag/ack checked by the atomic claim path, then wait for that
module's CLAIMED/EXECUTING jobs and transport permits to reach zero. For legacy
rows use the exact job allow-list as well as known worker instance ownership;
do not guess ownership from pod-name substrings. Verify that no claims can race
the zero count. Only then replace the pod. New jobs may queue during the drain.
Never make readiness failure alone the mechanism that stops claims.

Policy jobs may outlast today's 840 s worker shutdown drain / 900 s pod grace;
MDS export has a default 14,400 s deadline. Normal rollout waits before SIGTERM.
On a wait timeout, preserve that module's old digest and report BLOCKED for
that target; unrelated targets may complete. Do not force-kill or silently
requeue to achieve a deployment deadline. Preserve existing crash semantics.
HTTP-only modules need in-flight request drain, not a fabricated job count;
after configuration/compliance ownership expands, account for their background
work as well. Scheduler drains its ticks, not jobs it already submitted.

Keep Recreate/one replica initially. Per-target result reporting must include
old/new digest, readiness, active job count, drain state and e2e outcome.
Remove hard-coded service/worker-only monitoring and the assumption that one
`Done.` line proves all selected modules succeeded. Run the existing in-cluster
masked e2e after every deployment; any page/API 4xx fails it. Local workers do
not execute this deployment procedure.

### Secrets, storage and network matrix

Create one ServiceAccount per module, default token automount off. Only the
service status reader needs pod/metrics read RBAC; today
`deploy/ui2/54-service-status-rbac.yaml` binds that permission to `default`,
which is too broad for the split. Bind it to the service account instead.
Use projected file references, never secret values in manifests or this plan.

| Owner | Required access after selective composition |
| --- | --- |
| service | Application DB; existing auth/role/privacy/credential administration keys; artefact key and **read-only** artefact volume for authorized downloads; HTTP to module APIs; required directory/auth services; status-reader RBAC |
| policy | DB; device credential resolver, approved trust material/corporate CA; manager SSH/API egress; artefact encryption key and **write** volume for existing policy transcripts |
| backup | DB; credential/trust and backup-specific key references; artefact encryption key and **write/delete** volume; existing backup/SFTP receiver inbox; only approved device/receiver egress |
| inventory/discovery | DB; credential/trust and hostname fingerprint dependencies actually used; device/manager transport egress; no blanket backup inbox or artefact write mount |
| failover | DB; strict trust/credentials and required failover authorization material; approved device egress; preserve signatures, deadlines and quarantine; no backup inbox |
| diagnostics | DB; credentials/strict trust and platform-facts access; artefact encryption key and **write** volume for encrypted diagnostic output; approved device egress |
| configuration | DB plus collection credentials/trust, artefact key and **write** volume for existing configuration snapshots; parser API; cluster-diff access to stored sanitized evidence |
| compliance | Existing evaluator requires no DB or secrets. Moving warmup/persistence adds bounded DB access, masked/stored facts and required internal APIs; no device credentials/egress or backup inbox |
| scheduler/notifications | DB, notification settings/relay access and necessary admission/signature verification dependencies only; SMTP/syslog and internal module API egress; no device transport or artefact write mount |

Policy `withTranscript`, generic `DiagnosticJobExecutor` and configuration
snapshot storage mean **backup is not the only artefact writer**. Keep encrypted
storage semantics intact. Kubernetes mounts cannot enforce application-level
write-versus-delete distinctions on the same writable PVC: module access is a
coarse boundary until the existing store supports narrower paths/permissions.
Record that limitation rather than claiming strict storage isolation. Preserve
single-host volume placement/access-mode constraints; do not assume these pods
can move to arbitrary nodes. The SFTP receiver is an existing infrastructure
dependency, not a new device path introduced by this plan.

Add explicit ingress/egress NetworkPolicies per module: DNS, application DB,
exact internal service ports, and only required configured destination classes.
Retain the current machine-port restriction in
`deploy/ui2/56-service-internal.yaml` (e2e/security-scan access) while tightening
other paths. That file is not a complete module egress policy today. Preserve
`corp-ca` trust, non-root, read-only root filesystem, dropped capabilities,
seccomp and bounded writable mounts. Exact destination values remain local
deployment configuration, never repository literals. Validate CNI enforcement
in the authorized environment; a manifest alone is not proof of isolation.

## 7. Migration: one PR per module, policy first

PRs below are future proposals, not authorization to push, create PRs or deploy.
Keep changes in coherent module increments; do not introduce an independent
platform rewrite. Shared safety/drain groundwork is part of the first policy
PR because independent ownership cannot safely precede it. Sizes are relative
engineering effort, not promises: S = small composition/manifest adjustment,
M = several coordinated code paths, L = shared concurrency/security work.

### Common steps and acceptance for every PR

1. Add the explicit role mapping, selective composition, pool/resource settings,
   probes, ServiceAccount/mounts/policies and deploy target for that module.
2. Keep the new role disabled initially. Install a compatible fallback that
   understands global permits and role drains. Drain just the migrating set;
   disable its old owner; confirm zero active jobs/permits; enable the new owner.
   For the first policy cutover, pause/drain policy claims on fallback without
   imposing that delay on later unrelated deployments. Never overlap owners.
3. Test every allow-list positive/negative, unknown and mismatched pair, no
   double claim, cancellation, lease expiry, mutation uncertainty, bounded
   pool use and shared permits with two simulated processes. Test selective
   deployment with a long unrelated synthetic job, a zero-count claim race,
   timeout retaining the old digest, and rollback.
4. Run targeted Java tests, affected module regression and mandatory privacy /
   deployment-manifest gates in the authorized container workflow. Shared
   claim/lease changes require broader job-engine/persistence/worker regression.
   Then masked in-cluster e2e (any 4xx fails), followed by the approved live
   checks listed below. No fake terminal success if live evidence is absent.
5. Rollback: disable/drain the new owner, verify terminal/UNKNOWN disposition,
   scale it down, restore the previous compatible digest and only then restore
   fallback ownership. Queued jobs retain IDs/epochs/idempotency keys. Never
   roll back schema destructively or replay uncertain writes. Retain additive
   schema during the compatibility window. A pre-limiter old worker requires
   stopping/draining all split device owners first, not ordinary module rollback.

The first compatibility upgrade still replaces the original all-purpose
worker: stop its admission and let **all of its existing jobs** finish before
that one-time replacement. There is no safe shortcut around an already-running
policy/export on the old binary. Module-only waits become possible after this
bootstrap; do not promise that the very first upgrade avoids the old drain.

| PR / size, risk | Module-specific implementation | Verification beyond common gates | Rollback detail |
| --- | --- | --- | --- |
| 1 Policy / L, high | Role/claim mapping, shared admission/endpoint permits, drain handshake and target-aware scripts; keep discovery enqueue callback; isolate collectors and transcripts | CP/PAN policy fixtures, automatic six-hour allowance, partial/cancelled snapshots and transcript readback. Authorized live CP long run while an unrelated deployment completes without changing policy pod/job epoch; PAN collection parity; combined session count ≤4 | Drain policy; preserve snapshots/transcripts; restore policy to compatible fallback; do not remove permits |
| 2 Backup / M, high | Move all nine types, retention/orphan cleanup and receiver mounts; protect shared manager login across backup and inventory | Artifact digest/decryption/download parity, retention safety, serialized controller sessions. Authorized representative existing Gaia, Spark SFTP, PAN, MDS and HTTPS backup paths; verify no duplicate start and long export survives unrelated deploy. No restore execution | Keep encrypted volume/receiver references; move singleton cleanup back only after new owner stops |
| 3 Inventory/discovery / M, medium | Move 15 values including confirms; preserve onboarding and enqueue-only policy dependency; global manager endpoint permits | DRAFT confirm restriction; inventory priority/cap; discovery → policy job dependency. Authorized representative manager discovery and enrolled inventory counts/relations match masked evidence; no cross-pod concurrent controller login | Restore only these 15 values to fallback; preserve run IDs, imported identities and queued policy jobs |
| 4 Diagnostics / S–M, medium | Move both diagnostic types and encrypted output path; preserve admission and one-minute target checks | Gated fixtures, masking and output download authorization; separately PO-approved exact read on one synthetic/masked target scope; denied or duplicate requests cause no contact | Return both types together; retain output history and persisted rate limits |
| 5 Failover / L, high | Move four queue types, fence all execution entry points and fleet capacity; move execution-owned startup recovery safely; keep readiness/execution permissions distinct | Crash matrix, stale epoch, quarantine, token replay/deadline and cross-owner exclusion tests. Authorized CP/PAN readiness checks; any real failover execution needs its own exact PO approval and maintenance evidence, otherwise explicitly UNVERIFIED | Stop admission, drain safely; retain quarantine and signed schedule records; never replay OUTCOME_UNKNOWN; restore compatible owner only |
| 6 Configuration / M, medium | Extend existing configuration role to own five collect types plus parser and ClusterDiffTask; disable old background owner; preserve API | Vendor parser fixtures, configuration/alignment separation, snapshot and diff parity; authorized configured-state collection and masked refresh UI; existing internal parser clients still work | Stop collection/background tasks first, restore HTTP/parser-compatible digest and old ownership; retain snapshots |
| 7 Compliance / M, medium | Keep current evaluator API; move warmup/persistence orchestration and authoritative cache ownership out of service; keep service as authenticated facade | Rule/config invalidation, first-load/refresh and framework result parity; authorized masked Compliance/Overview e2e over stored evidence; no device contact required | Re-enable exactly one prior warmup/cache owner; preserve persisted evaluations and API compatibility |
| 8 Scheduler/notifications / L, high | Compose restricted background role using existing Spring dependencies without starting the full API/collectors; move producer timers and global reconciler; disable old startup/timer beans; preserve watermarks and slot claims | Controlled clock tests for every row in §3, restart/catch-up and duplicate delivery boundaries. Authorized observation of due-run admissions and existing relay delivery; no newly enabled schedules. Stop scheduler and prove missing-owner alert, then recover without duplicate device jobs | Disable new timers, restore old producers/reconciler under single-owner fencing; keep watermarks/slots. Final cutover removes fallback Deployment after its allow-list and scheduled duties are empty |

Initial sum of proposed module loops is nine; the global ceiling stays ten
even during fallback overlap. Module isolation can change fairness, so verify
diagnostic latency during long policy/backup runs and ensure scheduler work
cannot starve heartbeat persistence. No HPA until global limits and budgets
are verified. The API remains the facade; this plan does not relocate every
read-only query/controller into another microservice.

## 8. Observability and stop conditions

Reuse Administration/System status (`S/api/SystemStatusController`) and existing
job/audit records. Add module and opaque instance labels to health/metrics;
never include endpoints, principals or device names as metric labels.

- Per pod: ready/live status, role, image digest, last successful claim-loop
  tick, drain flag/ack, pool active/idle/pending/timeout counts, memory and CPU.
  Long-running jobs are not failed liveness checks. Readiness includes DB and
  required composition; liveness measures loop/process progress, not device health.
- Per module: REQUESTED depth, oldest queued age, CLAIMED/EXECUTING count,
  completion/failure/UNKNOWN counts, heartbeat age, expired leases, requeues,
  permit occupancy/wait and last successful scheduled tick. HTTP-only owners
  report in-flight requests/evaluation age rather than invented queue jobs.
- Expected enabled modules come from the same deployment/ownership mapping.
  Alert if an enabled module has zero ready pods or no fresh owner heartbeat
  for two observation intervals (initial proposal: 30 s intervals / 60 s
  threshold), even with an empty queue. Suppress only a bounded declared drain;
  escalate when its timeout expires. Keep the status observation outside the
  scheduler so a dead scheduler/notification pod can still be detected.
- Distinguish absent consumer, blocked admission/permit and slow device job.
  Set queue-age thresholds per module after measurement; a normal 55-minute
  policy execution is not evidence of an unconsumed queue. Alert on any unknown
  job-to-module mapping, conflicting active owner or lost heartbeat.

Stop rollout on claim overlap, lease regression, multiplied transport budgets,
DB pool timeout/connection-budget breach, missing permitted egress, unmasked
output, any e2e 4xx, or inability to retain old digest on drain timeout.
Report aggregate relationships/counts only. Module failure must not quietly
activate a fallback or widen a command gate.

## 9. Validation and remaining evidence

Phase 1 adds no runtime code, manifests, dependencies, migrations, gate rows or
tests. Relevant existing tests were inspected: `WorkerDatabasePoolTest`,
`ClaimIsAtomicNoReadThenWriteTest`, `PolicyCollectionJobExecutorTest`, and the
deployment-manifest gate. Future regression anchors include
`JobReconcilerTest`, policy parallel-collection tests, scheduler tests and
`tools/tests/test_ui2_deployment_manifests.py`. Java execution is reserved for
the authorized container workflow; no Gradle task is specified for this
documentation lane. Frontend checks are not applicable because no frontend
source or assets change.

Phase 1 checks: document inventory/path/budget consistency, `git diff --check`,
`python3 tools/privacy/repository_privacy_check.py`, and the focused project
cross-authority consistency test. Record actual outcomes in the session close.
Project metadata and handover files remain owned by the engineering session,
unchanged under this brief.

Shared global capacity/endpoint lease and drain semantics are now approved in
[MODULE_POD_RUNTIME_CONTRACT.md](MODULE_POD_RUNTIME_CONTRACT.md). Remaining
implementation prerequisites: contract implementation and validation, including
module-selective rollback and policy resume; validated endpoint-identity sharing;
service/legacy failover entry-point integration; background cache ownership;
measured peak memory/pool demand; actual CNI/PVC behavior; and per-module live
acceptance. No new device command is needed by the proposed split. If later
implementation discovers one, stop for its exact gate authorization rather
than extending this plan's authority.
