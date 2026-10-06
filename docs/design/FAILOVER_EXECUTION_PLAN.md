# From readiness to one controlled failover pilot

**Status: DRAFT — planning only; not an execution, command, migration or contract approval.**
Baseline: `7b5b8361`, inspected 2026-10-06. This document changes no runtime behavior.
Recommendation: retain the current CP/PAN worker pipeline, close its safety gaps,
and run one attended classic CP HA pilot, presented as `CLS-ROMEO-01`.
That pseudonym is illustrative, not enrollment or a security identity.
Keep all mutation disabled until the release checklist below is satisfied.

## 1. Evidence boundary and current-state inventory

The PO brief reports 3,348 `failover_run` rows, all `READINESS`; empty
`failover_approval` and `failover_schedules`; latest CP readiness 17 READY /
3 NOT_READY / 2 UNKNOWN and PAN 13 / 2 / 1. These are supplied live observations,
not independently verified here. They prove neither execution nor rollback.
Empty execution history also does not prove that mutation endpoints are disabled.
`CURRENT_STATE.md` and `AI_HANDOVER.md` still describe an older schema-91 checkpoint;
they cannot establish this lane's execution readiness. No host/device was contacted.

Source abbreviations below are repository-relative directories; filenames and
method names identify the inspected implementation, not a claimed test result:

| Prefix | Directory |
| --- | --- |
| S | `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/` |
| A | `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/` |
| W | `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/failover/` |
| J | `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/` |
| P | `ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/` |
| M | `ui2/service/src/main/resources/db/migration/` |
| F | `ui2/frontend/src/screens/` |

Tests mirror these packages under each module's `src/test/java/`.
DONE below means the bounded capability exists in source with identified tests;
PARTIAL means incomplete safety/integration; MISSING means absent in the inspected
path. No execution stage is claimed REAL_ENV_VALIDATED or operationally DONE.

| Stage | Status | Existing evidence and remaining gap |
| --- | --- | --- |
| Readiness collection and display | DONE (implementation; live use per brief) | S/`CpReadinessScheduler.java`, S/`CpFailoverService.java#requestReadiness`, W/`CpFailoverJobExecutor.java`, W/`PanFailoverJobExecutor.java`, M/`V109__cp_failover_readiness.sql`, F/`HaReadinessList.tsx`. READINESS forbids writes. Current worker tests explicitly cover this; displayed READY is not execution permission. |
| Request and unit selection | PARTIAL | A/`CpFailoverController.java`, S/`CpFailoverService.java#request`, P/`JooqCpFailoverRepository.java#request`, F/`CpFailoverPanel.tsx`: typed unit, role, inventory roles, approval, trust and unique active-unit checks; audited job submission exists. No shared pilot/quarantine admission fence on this worker path. |
| Approval | PARTIAL | S/`CpFailoverService.java#approve`, repository approval/revoke methods and M/`V101__cp_failover_execution.sql` implement authenticated admin windows. Same admin may start under the current CP contract; this is not independent four-eyes. Generic A/`FailoverAuthorizationController.java` still accepts `approver_id` from one caller. |
| Recheck | PARTIAL | Workers collect both members afresh at execution start; the cached inventory is only request admission. `windowValid` runs before that lengthy collection, not again immediately before DOWN/SUSPEND and UP/FUNCTIONAL. Request-time full check binding, revocation/expiry races and mutation-boundary freshness remain open. |
| Execute | PARTIAL | Both workers perform gated writes, pre-contact step-attempt recording and bounded switch polling. P/`JooqCpFailoverRepository.java` persists runs/audit. Generic S/`FailoverExecutionService.java` is a separate path: its default J/`execution/CheckPointClusterXLExecutor.java` and `PaloAltoHaExecutor.java` use synthetic dispatch/observation defaults. Do not mistake these for production transports. |
| Verify and return to standby/passive | PARTIAL | Worker `checks`, `waitFor`, `stop` implement pre/post checks and both expected roles; worker tests cover happy path, switch timeout and post-check failure. Generic return verification now rejects dual-active but still allows incompatible known-role pairs. Crash/transport uncertainty and post-return health need closure across all reachable paths. |
| Quarantine and crash recovery | PARTIAL overall; MISSING in CP/PAN admission | S/`DurableQuarantineStore.java` and generic execution/schedule services exist. Workers stop with FAILED/OUTCOME_UNKNOWN but do not engage that store; STOPPED leaves the active-unit uniqueness fence. Another request can therefore be admitted without incident clearance. Reconciliation is not atomic or owner-fenced. |
| Rollback / failback | MISSING by design as an automatic action | No automatic reversal is permitted. Healthy return-to-standby/passive is implemented; a traffic failback is a new approved operation. A reviewed recovery procedure and uncertain-outcome handling are pilot prerequisites. |

Existing regression evidence to extend: `CpFailoverServiceTest`,
`CpFailoverSecurityRouteTest`, `CpFailoverMaskingTest`, `JooqCpFailoverRepositoryTest`,
worker `CpFailoverJobExecutorTest` / `PanFailoverJobExecutorTest`, and
`ui2/integration-tests/src/test/java/com/securityexpert/nexus/ui2/integration/failover/CpFailoverPlainSqlTest.java`.
These were inspected, not executed in this documentation lane.

### Authority and backlog reconciliation

- [CP contract](FAILOVER_EXECUTION_CP_CONTRACT.md), including amendments 10–16,
  and [PAN contract](FAILOVER_EXECUTION_PAN_CONTRACT.md) are FROZEN. The
  [2026-09-27 decision](PO_DECISION_RECORD_2026_09_27_CP_FAILOVER_EXECUTION.md)
  supersedes classic-only release scope: VSX is per VS, not chassis-wide;
  returning the former active to standby is not failback.
- [OP.2.0](../history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md)
  supplies durable mutation-boundary, no-retry and quarantine requirements.
  The historical [change-review package](../history/builds/op2_c_change_management_review_package_draft.md)
  does not prove stakeholder sign-off. `history/utils/failover/preflight_readiness.py`
  is historical evidence, not Java authority.
- [Readiness draft](FAILOVER_READINESS_CHECKS_CP_PAN_DRAFT.md),
  [Java portability analysis](FAILOVER_READINESS_IN_THE_JAVA_PRODUCT_2026_09_15.md),
  [architecture roadmap](FAILOVER_ENGINE_ARCHITECTURE.md) and
  [old PAN A/A finding](PAN_ACTIVE_ACTIVE_FAILOVER_UNIT_GAP_2026_09_12.md)
  cannot override the later frozen contracts or authorize new reads.
- `project/QUEUE.md` still lists all ten brief items as P1/planned. The gate
  backlog predates V101/V102/V103/V104/V110/V111: verify exact gate parity before
  closing it; do not add duplicate commands. PAN A/A exclusion and reciprocal
  identity checks now exist, but the broader reciprocal role claim remains open.
  ASA/Fortinet cluster views are independent work, not a CP pilot dependency.

## 2. Exact P0 closure set and today's gaps

The eight-item list is quoted verbatim from
[Codex final review, Required P0 Closure Set](CODEX_FAILOVER_ENGINE_FINAL_REVIEW.md):

> 1. Recompute and verify the baseline digest before trusting `baseline_json`.
> 2. Make startup reconciliation plus quarantine atomic or safely retryable/idempotent.
> 3. Enforce valid `successful × DeliveryCertainty` combinations.
> 4. Replace return-to-service’s single-member role check with vendor-safe, two-sided affirmative verification.
> 5. Prevent silent key regeneration after key loss; persist `key_id` and algorithm version per schedule.
> 6. Make schedule state, grant use, ledger attribution, and execution-boundary transitions transactionally coherent.
> 7. Fix ledger canonicalization, timestamp precision, and multi-instance chain serialization.
> 8. Close multi-instance booking/dispatch concurrency through database locking/constraints or a demonstrated enforced-singleton deployment gate.

| Item | Status at baseline | Exact remaining closure |
| --- | --- | --- |
| 1 | PARTIAL | S/`FailoverScheduleService.java#dispatchScheduledExecution` now calls J/`schedule/BaselineSnapshotSummary.java#digestMatchesContent`; `FailoverIntegrityClosureTest` covers tampering. `mapRow` still ignores the separate `baseline_digest` column. Verify column/JSON/recomputed equality and cluster binding before use. Booking still reads latest report and baseline separately; missing baseline dimensions/windowed counters need correction. |
| 2 | PARTIAL | S/`FailoverScheduleService.java#reconcileOrphanedAttemptsOnStartup` updates status, appends ledger, then engages quarantine separately, without live-owner fencing. Make recovery repeatable and atomic with quarantine; include manual and CP/PAN worker attempts. |
| 3 | DONE in generic result type; PARTIAL end to end | J/`execution/FailoverCommandResult.java` rejects null and inconsistent certainty; `FailoverIntegrityClosureTest` covers combinations. Workers use raw transport outcomes and `writeInFlight`; prove equivalent fail-closed classification through durable attempt recording, including timeouts and recording failures. |
| 4 | PARTIAL | S/`FailoverExecutionService.java` rejects dual-active/unknown peer, but both known passive/standby roles can still pass. Require action/vendor-specific pairs, independently bound identities and healthy postconditions. Workers have expected-role polling, but no complete post-return health battery or real execution evidence. |
| 5 | PARTIAL | S/`FailoverKeyManagementService.java` adds a provisioning marker; `FailoverKeyLossTest` covers key loss with marker retained. Losing/mis-mounting both files still permits generation. Explicit initialization, restrictive permissions, durable key source, and per-schedule key/version are absent; M/V31 has no such columns. |
| 6 | PARTIAL | Generic schedule/grant/lease/ledger transitions span transactions; manual results remain process-local. CP/PAN admission/state changes are individually audited transactions, but run state, attempt certainty, ownership and quarantine are not one durable transition. Persist the boundary before send; never promise an atomic database/device transaction. |
| 7 | MISSING (review remediation) | S/`FailoverScheduleLedger.java#computeHash` still uses delimiter concatenation, `Instant` before DB precision normalization and a JVM monitor for tip allocation. Require framed encoding, canonical storage precision, DB serialization and append-only enforcement. An unkeyed chain is not protection against a privileged DB writer. |
| 8 | PARTIAL | M/V101 + V103 and repository admission enforce one active run per vendor/unit. Generic booking and dispatch use process-local locks. Neither proves fleet/member exclusion across replicas and overlapping VS units; stale owners must lose authority before another can send. |

The [Claude final review](CLAUDE_FAILOVER_ENGINE_FINAL_REVIEW.md) concurs but
contains **twelve**, not eight, mandatory items. Its quoted item labels are:

> 1. **FR-P0.2** — `RETURN_TO_SERVICE`: vendor-safe two-sided pair assertion; dual-active must quarantine, never succeed.
> 2. **FR-P0.13** — catch-all quarantine + record for any throwable after the mutation boundary.
> 3. **FR-P0.1** — recompute and constant-time verify the baseline digest before trusting `baseline_json`; verify `clusterRef`.
> 4. **FR-P0.3** — enforce `successful × DeliveryCertainty` as a closed state machine with a fail-closed default.
> 5. **FR-P0.6** — separate key initialization from runtime resolution; never regenerate when schedules exist; 0600 permissions; explicit durable path; persist `key_id`/`alg_version` per schedule.
> 6. **FR-P0.8 / FR-P0.11** — ownership-fenced reconciliation; quarantine before status flip.
> 7. **FR-P0.9** — durable execution records and crash recovery for the manual Phase C path.
> 8. **FR-P0.12** — remove the CAS-bypassing acknowledge overload; append-only per-incident quarantine.
> 9. **FR-P0.7** — demonstrate (or build) two independently authenticated acts for every dual-control gate; complete principal canonicalization with NFKC + confusable rejection.
> 10. **FR-P0.4 / FR-P0.5 / FR-P0.14** — single evidence pass at booking with a freshness bound; missing baseline dimensions `NOT_EVALUABLE`; correct windowed-counter semantics.
> 11. **FR-P0.10** — database-level exclusion for booking overlap and execution concurrency, or a demonstrated and enforced singleton deployment gate recorded as a contract.
> 12. **FR-P0.2 (crypto)** — canonical temporal precision normalized before signing and storing; `OffsetDateTime` binding.

Items 1/3/4/5/6/10/11/12 map to the table above. Additional open gaps are:
post-boundary persistence/error paths beyond guarded executor calls (item 2),
process-local generic execution records (7), mutable per-cluster quarantine and
acknowledgement without an explicitly supplied incident ID (8), and caller-supplied
second-actor strings (9). These are PARTIAL or MISSING controls, not closed findings.
The review's requested four-eyes policy differs from the later CP contract's
same-admin allowance; the PO must choose and record the pilot policy (question 2).
Review-required missing-evidence discipline must also preserve later informational
ARP/flap rows; it must not silently make every informational row blocking.

## 3. Ordered, independently shippable PR sequence

All PRs retain mutation OFF until the last release gate; no dependencies added.
Numbers express order, not authorization to implement/push/deploy. Before any new
schema/security semantics, the engineering owner resolves the PO questions and
records the necessary successor decisions; this document freezes nothing.
Each PR extends existing tests first. Existing rows in
`ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml`
and migrations are reused. **No new device commands are proposed for this pilot.**
Any missing exact key/context/purpose/frequency requires separate PO approval and
a matching new migration + fixture row before implementation, never a guessed row.

### PR 1 — Close alternative mutation entry points (S, high risk)
- Scope/files: S/`FailoverConfiguration.java`, generic execution/schedule services,
  A/`FailoverExecutionController.java`, `FailoverScheduleController.java`, and
  J/`execution/CheckPointClusterXLExecutor.java` / `PaloAltoHaExecutor.java`.
  Add the same default-deny mutation switch to S/`CpFailoverService.java` and
  both W executors; readiness remains available. Remove production synthetic-success defaults. Keep
  generic manual/scheduled routes disabled through this pilot; no transport rewrite.
- Tests: controller/worker/`FailoverExecutionTest`, architecture test proving
  every disabled path cannot dispatch or report synthetic success; readiness unchanged.
- Commands/gates: none. Migration: none. Preview/e2e: mocked denial tests;
  masked read-only readiness navigation remains successful.

### PR 2 — Baseline integrity and temporal framing (M, high risk; P0 1, part of 7)
- Scope/files: S/`FailoverScheduleService.java`, J/`schedule/BaselineSnapshotSummary.java`,
  `FailoverDriftEngine.java`, `FailoverScheduleEnvelope.java`. Recompute all digest
  copies; bind cluster; use one fresh evidence pass; reject missing required fields;
  fix rolling-counter semantics and normalize time before signing/storage.
- Tests: `FailoverIntegrityClosureTest`, `FailoverScheduleServiceTest`, new PostgreSQL
  round-trip cases in integration-tests for nanoseconds, tampering and missing fields.
- Commands/gates: none; use existing producers. Migration: version/invalidate
  incompatible stored baselines explicitly, with safe abort; do not re-sign them.
  Preview/e2e: sanitized abort reasons from fixtures; scheduled writes stay OFF.

### PR 3 — Explicit signing-key lifecycle (S, high risk; P0 5)
- Scope/files: S/`FailoverKeyManagementService.java`, `FailoverScheduleService.java`,
  schedule record/envelope mapping. Explicit bootstrap separate from runtime lookup;
  missing storage/key fails closed; persist key ID/algorithm; restrict file access.
- Tests: `FailoverKeyLossTest`, `FailoverKeyManagementServiceTest`, PostgreSQL reload;
  lose key and marker together, wrong mount, restart, unknown key/version.
- Commands/gates: none. Migration: add key/version columns, validated legacy backfill
  or abort incompatible records. Preview/e2e: fixture KEY_UNAVAILABLE, no raw key/path.

### PR 4 — Ledger and schedule transaction integrity (M, high risk; P0 6–8)
- Scope/files: S/`FailoverScheduleLedger.java`, `FailoverScheduleService.java`,
  `FailoverBookingAdmissionControl.java`; reuse database transaction infrastructure.
  Frame ledger fields, normalize timestamps, serialize chain/booking in DB,
  enforce append-only history and atomically record transitions/grant consumption.
- Tests: existing ledger/booking tests plus PostgreSQL competing bookings, simultaneous
  chain append, crash rollback and timestamp reload; no in-memory-only closure.
- Commands/gates: none. Migration: constraints, locking support and ledger format
  version; preserve old history with a versioned boundary. Preview/e2e: fixture
  conflict/abort views. Generic scheduling stays OFF regardless of passing tests.

### PR 5 — Durable incidents and common mutation admission (M, high risk; P0 2, 6, 8)
- Scope/files: S/`DurableQuarantineStore.java`, `FailoverExecutionService.java`,
  `CpFailoverService.java`, P/`JooqCpFailoverRepository.java`, both worker executors.
  Reuse durable quarantine for all routes; append per-incident history; exact incident
  CAS on release; quarantine blocks new work even when the failed run is STOPPED.
  Persist one disabled-by-default pilot enrollment using opaque unit/member IDs;
  replace J/`pilot/FailoverPilotAllowlist.java` constructor seeds; enforce in workers.
- Tests: repository/service/worker refusal tests; restart persistence, changed member,
  stale incident release, wrong unit, absent enrollment and all alternate routes.
- Commands/gates: none. Migration: incident history/enrollment storage and constraints.
  Preview/e2e: masked quarantine/pilot status; never expose enrolled raw identities.

### PR 6 — Fenced attempts and crash reconciliation (M, high risk; P0 2, 3, 6, 8)
- Scope/files: both W executors, P/`JooqCpFailoverRepository.java`,
  S/`FailoverScheduleService.java`, `FailoverExecutionService.java`; reuse job lease
  epochs/step attempts. Durably record dispatch boundary before send; serialize
  fleet/member ownership in DB, prevent stale-owner sends, reconcile without touching
  live-owned work. Atomically persist uncertainty/quarantine; never replay a write.
- Tests: PostgreSQL fault injection before/after each state/attempt/ledger update,
  send-with-lost-reply, runtime error, restart and second replica mid-dispatch;
  DB failure after send must block further admission until recovery.
- Commands/gates: none. Migration: durable generic manual attempt/result linkage,
  ownership/CAS fields and required constraints. Preview/e2e: fixture unknown outcome
  remains quarantined across refresh/restart. Generic routes remain disabled.

### PR 7 — Approval and request-bound fresh checks (M, high risk)
- Scope/files: A/`CpFailoverController.java`, S/`CpFailoverService.java`, P repository,
  both W executors; generic authorization/quarantine controllers remain fenced.
  Bind request, approval, unit/member set and fresh check pass; recheck window,
  revocation, enrollment, quarantine and ownership immediately before each write.
  Implement PO-selected independent approval policy using authenticated actor IDs,
  never a submitted second-actor name; preserve explicit same-admin contract unless amended.
- Tests: security/service/worker tests for forged approver, changed target, reused
  request, revoked/expired window during checks and before return, stale evidence.
- Commands/gates: none; existing read battery only, approved cadence/session bounds.
  Migration: request/approval/evidence binding as needed. Preview/e2e: synthetic role
  flows; aiview sees status but cannot approve, execute or schedule.

### PR 8 — Reciprocal identity/roles and mode admission (S, high risk)
- Scope/files: W/`CpFailoverChecks.java`, `PanFailoverChecks.java`, both executors,
  S/`CpFailoverService.java`. CP already compares both membership maps and distinct
  local IDs; PAN checks reciprocal serial relationships but does not retain peer-role
  claims. Bind observations to verified endpoints and require reciprocal role agreement.
  Exclude actual load sharing/A/A; do not infer VSLS from the measured CP mode label.
- Tests: missing/opposed peer claims, observer mix-up, unequal opaque identifiers,
  both active, unsupported modes, VS context isolation and chassis-only evidence.
- Commands/gates: none if existing output proves all fields; otherwise STOP for
  official semantics/PO command review. Migration: none unless minimal derived
  evidence shape requires one. Preview/e2e: masked UNKNOWN/reason fixtures.

### PR 9 — Affirmative postconditions and return safety (M, high risk; P0 4)
- Scope/files: W checks/executors, S/`FailoverExecutionService.java`,
  J/`execution/TwoSidedObservation.java`. Require exact vendor/action role pairs,
  safe peer health and policy/session/routing continuity; verify return does not
  move traffic back. Any unproven result quarantines and stops further commands.
- Tests: extend both worker suites and `FailoverExecutionTest`: both passive, both
  active, unknown peer, wrong identity, asymmetric recovery, transport timeout,
  failed continuity and unexpected preemption. Preserve current informational rows.
- Commands/gates: no new literal; extra post-return samples may exceed current
  frequency gates. Obtain PO approval of exact repeat scope and migration/fixture
  frequency amendment before enabling extra reads. Migration otherwise none.
  Preview/e2e: fixture success/stop/quarantine stepper; no automatic failback control.

### PR 10 — Masked pilot acceptance and closure evidence (S, medium risk)
- Scope/files: F/`CpFailoverPanel.tsx`, `HaReadinessList.tsx`, controller masked
  projections only where new refusal/quarantine states require display; relevant
  frontend tests and `ui2/integration-tests/` fault/acceptance cases.
  Engineering owner records P0-by-P0 evidence, succession and remaining backlog;
  schedule and nonpilot mutation remain OFF. No new platform or dashboard.
- Tests: full frontend commands below, Java module/integration/architecture gates,
  privacy; repeat external review through real repository consultation tooling.
  No unresolved P0 may be labeled closed merely because its endpoint is disabled.
- Commands/gates: none. Migration: none. Preview/e2e: synthetic masked previews;
  after separately authorized deploy, real aiview navigation must have zero page/API
  4xx. Expected authorization refusals belong to separate API tests, not that navigation.
  Pilot follows only after PO accepts closure and separately authorizes the exact run.

Validation for implementation PRs: named affected tests first, then, in the authorized
container environment:
`cd ui2 && ./gradlew :job-engine:test :persistence:test :service:test :worker:test :integration-tests:test :architecture-tests:test`.
Frontend changes: `cd ui2/frontend && npx tsc --noEmit -p . && npx vitest run && npm run build`;
use worktree cache paths if needed, no installs. Every PR runs
`python3 tools/privacy/repository_privacy_check.py` and `git diff --check`.
New tables require `GRANT SELECT, INSERT, UPDATE, DELETE ON <table> TO ui2_app;`
plus audit/constraint enforcement; append-only ledgers still refuse UPDATE/DELETE.
This docs-only lane runs document checks/privacy, not Gradle or device tests.

## 4. Mandatory first-execution invariants and release checklist

- **Fail closed:** every blocking check must be affirmatively PASS from the same
  request/execution workflow. Missing, stale, unsupported or unproven evidence blocks;
  collection failure stays UNKNOWN, never a fabricated healthy/bad device verdict.
  CP informational ARP/flap checks and deferred CPS retain their frozen treatment.
- **Authorization:** recorded PO pilot approval and stakeholder change/security
  sign-off precede one bounded window; recommended two authenticated humans.
  Readiness, command gates, enrollment and approval are separate predicates.
  aiview remains read-only for execution/approval/schedules; UI evidence stays masked.
- **Recheck and reciprocity:** collect both identity-verified members for the request;
  collect again at execution if queued, changed or no longer coherent. Revalidate all
  authority immediately before each write. Each member's peer identity and role must
  agree with the other's independent observation; no display-name joins.
- **Units/modes:** first proposed pilot is classic CP HA. VSX remains supported scope
  under the later PO decision, but pilot admission excludes it until per-VS acceptance;
  every later VS step uses that opaque VSID on both physical members. No chassis-role
  inference or numeric identity normalization. Actual VSLS/load sharing and PAN A/A
  cannot pass A/P readiness. The text `Virtual System Load Sharing` alone is not proof
  of actual VSLS; unresolved mode semantics block. PAN vsys are never failover units.
- **Mutation boundary:** one enrolled unit, verified two-member set, one active fleet
  mutation owner; durable before-send record, fencing and no blind retry. Audit/state
  storage failure, expired/revoked approval, owner loss or changed identity stops sends.
- **Automatic stop:** any blocking FAIL/UNKNOWN, peer disagreement, unexpected role,
  split brain, missing observation, timeout, uncertain delivery, failed post-check or
  failed audit persistence stops subsequent writes and notifies the operator.
  After a crossed/uncertain boundary, quarantine survives restart and blocks all routes;
  no automatic retry, reschedule, release or compensation. Recovery never steals a
  live owner's work; uncertain owner/dispatch state is not permission to send.
- **Postconditions/recovery:** use frozen CP/PAN checks and tolerances; independently
  prove the former standby active and former active down/suspended, then required
  continuity. Only successful checks permit return to standby/passive; prove the new
  active remains active afterward. Unexpected preemption is a stop. Failback needs a
  new typed, approved request and fresh checks; uncertainty needs human-led diagnosis,
  incident-specific release and a reviewed recovery procedure, not another blind write.
- **Release evidence:** all P0 closures independently reviewed; DB crash/race tests
  green; gate migration/fixture parity; masked UI acceptance; signed change review;
  exact pilot enrollment/window and recovery owner recorded. Then one supervised run
  via the application. Save only masked statuses/relationships and audit references.
  Success does not enable another unit, vendor or unattended schedule automatically.

## 5. PO decisions still needed

1. **First pilot and route?** Recommend one classic CP HA unit (`CLS-ROMEO-01` in
   masked reporting), attended/manual through the existing worker pipeline. Keep
   generic and scheduled execution disabled; VSX/PAN follow their own acceptance.
2. **Approval policy?** Recommend two independently authenticated humans for pilot
   start and quarantine release. Record a pilot-specific amendment because the current
   CP contract expressly allows the approving admin to start; do not silently override it.
3. **Containment?** Recommend DB-backed, default-empty enrollment and one fleet mutation
   at a time, enforced at service and worker. Real environment classification must be
   truthful; never label a production pilot LAB_PILOT to bypass the old fence.
4. **Integrity threat model?** Recommend protect against schedule DB edits without
   signing-key access; separately protect the key and audit checkpoint. Do not claim
   an unkeyed local chain survives a privileged DB rewrite. Accept the closure design
   before cryptographic/storage changes; no new dependency is preapproved here.
5. **Evidence and gate decisions?** Recommend reuse only the frozen check battery,
   defer CPS, and approve exact post-return read frequency if added. Resolve unproven
   reciprocal/mode/preemption semantics with official documentation and separately
   approved sanitized measurements; no guessed command or threshold.
6. **Window, stop and recovery ownership?** Recommend one explicitly named change
   window, no automatic failback, and an available recovery decision-maker. Record
   stakeholder sign-off, expiry behavior and incident-release procedure before the
   first production execution. This plan and its local commit grant none of these.
