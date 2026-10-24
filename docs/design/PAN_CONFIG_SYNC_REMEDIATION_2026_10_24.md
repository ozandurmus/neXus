# Palo Alto HA: remediate a failed "Configuration sync" readiness check

**Status:** IMPLEMENTED on `lane/pan-config-sync-remediation` under the PO decision of 2026-10-24
([PO_DECISION_RECORD_2026_10_24_PAN_CONFIG_SYNC.md](PO_DECISION_RECORD_2026_10_24_PAN_CONFIG_SYNC.md), RATIFIED).
Backed by automated validation only. Nothing here has been validated against a real firewall yet.

This feature sits next to the FROZEN [FAILOVER_EXECUTION_PAN_CONTRACT.md](FAILOVER_EXECUTION_PAN_CONTRACT.md). It does
not change that contract's unit, checks, commands, approvals or runs. It adds one remediation write, its own job and its
own record, and reuses the contract's readiness run to show the result.

## 1. Decision (verbatim)

The PO answered **"Onaylıyorum (Önerilen akış)"** ("I approve (the proposed flow)") on 2026-10-24 to this proposal:
when the PAN failover readiness check "Configuration sync" (check 4) is FAIL, show a remediation button that runs, on
the ACTIVE member only, exactly `request high-availability sync-to-remote running-config`.

Approved flow:

1. The button is visible only while check 4 is FAIL, and only to the admin roles (security_admin, operation_admin).
2. The command goes only to the active member, never from the passive to the active.
3. A confirmation dialog names the source (active) and the target (passive) member.
4. The business-hours confirmation (08:00-20:00 daily, `X-Nexus-Business-Hours-Ack`, the existing pattern).
5. An audit record.
6. Afterwards readiness for that unit re-runs automatically and the result is shown.

## 2. The exact literal

| Field | Value |
| --- | --- |
| CLI form (the PO's words) | `request high-availability sync-to-remote running-config` |
| Sent as | PAN XML API, `POST /api/`, `type=op`, form field `cmd` |
| `cmd` (gate `canonical_command_key`) | `<request><high-availability><sync-to-remote><running-config/></sync-to-remote></high-availability></request>` |
| Gate row | `pan_failover_config_sync` (V178), `palo_alto` / `pan_firewall` / `not_applicable` / `PAN_XML_API` |
| Action class | `configuration-write` (CLASS_3): it replaces the passive member's running configuration. The stricter class is chosen on purpose; `GateRow.violatesWriteMarkerDenylist` exempts this one exact row (gate id and literal), as it does the two failover writes. |
| Timeout / retry | 60 s / `none` (never retried, automatically or otherwise, by this job) |
| Frequency | once per job, on the member that is active in the job's own fresh HA-state read |
| Session | one API key per member per job (`keygen`), never stored |

No other command is added. The job's reads are the existing `pan_inventory_show_ha_state` read
(`<show><high-availability><state/></high-availability></show>`), once per member before the write; its
`max_frequency` is extended by V178 with this purpose.

## 3. Preconditions

### 3.1 At request (service and repository, in one audited transaction under advisory lock 136)

| # | Precondition | Refusal code |
| --- | --- | --- |
| R1 | Route `POST /api/v2/pan-failover/units/{unitId}/config-sync`, action `pan_failover_config_sync`: security_admin or operation_admin. The Check Point prefix has no mapping (`ACTION_MAPPING_REQUIRED`). | 403 at the gate chain |
| R2 | Not a replay viewer (aiview), whatever its other roles. | `WRONG_ROLE` |
| R3 | The unit is a Palo Alto active-passive unit (Check Point never). | `CONFIG_SYNC_NOT_SUPPORTED` |
| R4 | `NEXUS_FAILOVER_MUTATION_ENABLED` is true (service, repository and worker each check it). | `FAILOVER_MUTATION_DISABLED` |
| R5 | Inside business hours, the request carries `X-Nexus-Business-Hours-Ack: true` (or `?business_hours_ack=true`). | 409 `BUSINESS_HOURS_CONFIRM_REQUIRED` |
| R6 | The body names the source and target the caller saw (`sourceDeviceId`, `targetDeviceId`); nothing else is accepted. | `INVALID_REQUEST` |
| R7 | The cluster's two enrolled Palo Alto members are exactly the source and the target. | `MEMBER_SET_CHANGED` |
| R8 | Fresh readiness: the unit's newest finished readiness run ended within the last 60 minutes. | `READINESS_REQUIRED`, `READINESS_NOT_FRESH` |
| R9 | That run recorded check 4 FAIL. | `CONFIG_SYNC_NOT_FAILED` |
| R10 | That run's check 1 PASSED and recorded the source as `active` and the target as `passive` (a role from a failed or unknown check 1 row never counts). | `ROLES_CHANGED` |
| R11 | No open failover or readiness run or resume on the unit, no other open config sync on it, and no failover write on the cluster or a member that was sent and never observed. | `RUN_ALREADY_ACTIVE`, `CONFIG_SYNC_ALREADY_ACTIVE`, `UNRESOLVED_DISPATCH` |
| R12 | No open failover incident on the cluster or on either member. | `OPEN_INCIDENT` |

The job is recorded under the administrator's manual-admission grant (V168 columns: role, business hours,
acknowledgement), so it is claimable at once, outside the job windows, like every administrator's manual job. The
record keeps the acknowledgement whenever the administrator gave it, also just before 08:00 (the job row records it only
inside business hours).

### 3.2 In the job, before any device contact

| # | Precondition | Stop code |
| --- | --- | --- |
| W1 | Mutation switch on. | `FAILOVER_MUTATION_DISABLED` |
| W2 | The request is at most 30 minutes old when the worker starts it. | `CONFIG_SYNC_REQUEST_EXPIRED` |
| W3 | The job still owns its lease (epoch, module owner, not cancelled); R7, R11 and R12 hold again. | `OWNERSHIP_LOST`, `MEMBER_SET_CHANGED`, `RUN_ALREADY_ACTIVE`, `UNRESOLVED_DISPATCH`, `OPEN_INCIDENT` |
| W4 | Business hours: inside them, a request whose record carries no acknowledgement is refused before the write. | `BUSINESS_HOURS_CONFIRM_REQUIRED` |
| W5 | Both gate rows resolve KNOWN with their expected gate ids and classes. | `COMMAND_GATE_UNAVAILABLE` |

### 3.3 In the job, the fresh HA-state read immediately before sending

Both members are reachable: each answers `keygen` and the HA-state read with `status="success"`
(`AUTH_UNAVAILABLE`, `COMMAND_UNAVAILABLE`). Each member's own serial equals its recorded identity
(`IDENTITY_NOT_VERIFIED`). Then, from these two reads only:

| # | Precondition | Stop code |
| --- | --- | --- |
| F1 | Both members report mode active-passive and reciprocal roles (the readiness check 1 rule). | `HA_STATE_UNKNOWN` |
| F2 | The bound source is `active` and the bound target is `passive`. Any other combination (roles flipped, both active, suspended) refuses. | `ROLES_CHANGED` |
| F3 | Check 4 evaluated on these reads is FAIL (`PanFailoverChecks.syncEvidence`): PASS refuses, UNKNOWN refuses. | `CONFIG_ALREADY_SYNCHRONIZED`, `CONFIG_SYNC_STATE_UNKNOWN` |
| F4 | Neither member reports `synchronization in progress`. | `CONFIG_SYNC_IN_PROGRESS` |

Then, in one audited transaction under lock 136, W1, W3 and W4 are checked again, the record moves to `SYNCING`,
`mutation_possible` becomes true and the write's step attempt is committed (`mutation_boundary_crossed`). The switch is
read once more immediately before the transport is invoked (`MUTATION_DISABLED_BEFORE_SEND`). A cancellation or an
endpoint-lease refusal raised by the transport before it sends anything is `NOT_SENT_BEFORE_CONTACT`. In both not-sent
cases the attempt is recorded NOT_SENT and `mutation_possible` falls back to false together with that terminal outcome
(the only fallback the V178 guard allows). The write goes to the source (active) member's endpoint only; the target
member is never sent a write.

### 3.4 Endpoint admission

The job's endpoint leases use their own purpose, `CONFIGURATION_WRITE`: one lease per member endpoint, exclusive like
every non-policy purpose. The job never takes the estate-wide `fleet.failover.execution` task lease and never holds the
fleet failover fence. Mutual exclusion on the cluster is advisory lock 136 with the R11/R12 checks above, in the
request, at worker start and immediately before the write. Endpoint leases are released when the job closes its
transports, whatever the outcome; a `CONFIGURATION_WRITE` lease left QUARANTINED (an expired heartbeat) is released,
audited (`QUARANTINE_RELEASED_SYNC_SETTLED`), by the sweep every admission, heartbeat and drain barrier runs, as soon as
its job and record have ended, even while its owner instance is alive. An uncertain outcome therefore never holds a
lease that blocks a module drain (the 2026-10-22 incident).

## 4. Result handling

The op response is parsed for its root `status` attribute only; its text is never stored or shown.

| Response | Record | Job | Readiness re-run |
| --- | --- | --- | --- |
| HTTP 200, `<response status="success">` | `DONE`, `CONFIG_SYNC_REQUESTED` | `COMPLETED` | yes |
| HTTP 200, `<response status="error">` | `STOPPED`, `DEVICE_REPORTED_ERROR` | `FAILED` | yes |
| no reply, transport failure, other HTTP status, other or oversized body | `STOPPED`, `OUTCOME_UNKNOWN` | `OUTCOME_UNKNOWN` | yes |
| stop after the fresh read (F1-F4) | `STOPPED`, the code | `FAILED` | yes |
| prepared but not sent (`MUTATION_DISABLED_BEFORE_SEND`, `NOT_SENT_BEFORE_CONTACT`) | `STOPPED`, the code | `FAILED` | no |
| stop before contact (W1-W5, R*) | `STOPPED`, the code | `FAILED` | no |

Once the write may have left neXus, the recorded reply decides the outcome: `success` -> DONE, `error` ->
DEVICE_REPORTED_ERROR, anything else OUTCOME_UNKNOWN. The reply and the end are bound to the job's epoch while it is
EXECUTING, never to its cancel flag or its lease clock; a cancel request after the send changes nothing. If the job ends
some other way first (cancelled, reconciled), the V178 job-end trigger closes the record from the recorded reply. The
post-send steps run outside the pre-send handling: no failure after the send (recording the reply, the end, the re-run)
can replace the outcome with a pre-send code; a failed end is retried once, and a job that still could not be ended with
its record is ended alone with the matching state (COMPLETED, FAILED or OUTCOME_UNKNOWN), never left EXECUTING.

Nothing is ever retried automatically. A new attempt is a new request by an administrator, which needs a new fresh
readiness with check 4 FAIL.

The readiness re-run is submitted by the job itself, in its own audited transaction after the record has ended
(`pan_config_sync_followup`), for the same unit, in the initiator's name and under the initiator's grant (its role). It
is never refused for business hours: inside them its job carries the initiator's acknowledgement when the request had
one, else the follow-up's own (`business_hours_ack_by = system:pan-config-sync-followup`), as the follow-up of an
administrator's admitted write. It waits 15 s after the reply first, without contacting a device, so the peer has time
to apply the configuration. If it cannot be admitted (another run started on the unit) or fails, the record keeps the
code in `followup_refusal` (never in its outcome) and the screen offers the normal "Run pre-checks".

An uncertain outcome does not open a failover incident: the write does not move traffic or HA roles. Its uncertainty is
recorded on the record, its step attempt and its job (`OUTCOME_UNKNOWN`), all in the audit log, and the readiness re-run
shows the real state.

## 5. Storage and audit

`pan_config_sync` (V178): one row per request, with opaque references only (cluster ref, the two member ids, job id,
readiness run ids), the initiator fingerprint and role, business-hours facts, state, outcome, the device status word
(`success`/`error`) and timestamps. No device output, address or name. An audit trigger (`fn_audit_capture`) records
every insert and update; the request, the admissions, the send preparation and the end are each audited transactions
(`pan_config_sync_request`, `pan_config_sync_admission`, `pan_config_sync_send`, `pan_config_sync_reply`,
`pan_config_sync_end`, `pan_config_sync_followup`). A trigger closes a record whose job ends without the worker closing
it: from the recorded reply when there is one, else `OUTCOME_UNKNOWN` once a send was prepared, else
`JOB_ENDED_WITHOUT_RESULT`. Readiness and failover admission refuse a unit while it has an open config sync.

## 6. Surfaces

- **Service:** `POST /api/v2/pan-failover/units/{unitId}/config-sync` (body `clusterId`, `sourceDeviceId`,
  `targetDeviceId`), `GET /api/v2/pan-failover/config-syncs/{syncId}` (failover readers; the Check Point prefix answers `CONFIG_SYNC_NOT_SUPPORTED`). The unit list and the summary
  carry `configSync`: `offered` (check 4 FAIL in the newest readiness), `canRun` (admin, not masked, offered), `refusal`
  (the server's reason when it would refuse now), the source and target member ids, and the latest record.
- **CLI:** `nexus-cli failover-config-sync <clusterId> <unitId> --source <deviceId> --target <deviceId>
  [--business-hours-ack]`, and `failover-config-sync-status <syncId>`.
- **UI:** a "Sync configuration to passive" button in the check 4 row of the readiness table (Operations HA readiness
  list and the Failover tab), shown only when the server says `canRun`. The dialog names the source (active) and the
  target (passive), shows the command, and asks for confirmation; the business-hours question is the shared one. Progress,
  outcome and the refreshed readiness are shown in place.

## 7. Not in scope

Check Point (no route, `CONFIG_SYNC_NOT_SUPPORTED`). Virtual systems. Any write to the passive member. Polling the
device for the sync's completion (the readiness re-run is the evidence). A sync from the passive to the active.

## 8. Review response (Argon, 2026-10-24)

[ARGON_REVIEW_2026_10_24_PAN_CONFIG_SYNC.md](ARGON_REVIEW_2026_10_24_PAN_CONFIG_SYNC.md), verdict NO-GO. The coordinator
decided P0-1 option 1 (a per-endpoint purpose, no fleet singleton). Every finding and its fix:

| Finding | Fix | Tests |
| --- | --- | --- |
| P0-1 fleet lease quarantined by an uncertain outcome | `WorkerClaimLoop.purposeOf`: `pan_failover_config_sync` -> `CONFIGURATION_WRITE` (checked before the failover rule), so `EndpointRuntime.acquireFleet` is a no-op and no `fleet.failover.execution` lease is taken. V178 adds the purpose to the `endpoint_admission` check, `ui2_config_sync_lease_settled`, `ui2_release_settled_config_sync_leases` (called from `ui2_release_orphan_quarantines`, V174 body otherwise unchanged) and the audit reason `QUARANTINE_RELEASED_SYNC_SETTLED`. Cluster exclusion: lock 136 + `pan_config_sync`, `failover_run`, `failover_resume`, `failover_dispatch_intent` checks (§3.4). | `PanConfigSyncFenceTest.theConfigSyncNeverTakesTheFleetFailoverLease`; `PanConfigSyncLifecycleTest` (quarantined lease of an ended job released with its owner alive; no fleet lease); `PanConfigSyncSchemaTest` |
| P0-2 cancel during send/settle hid or rewrote the reply | `recordReply` binds to the attempt's job and epoch only; `finish` to the job's epoch while EXECUTING (`jobHeld`, no `cancel_requested`, no lease clock). The job-end trigger closes a record from its recorded reply (success -> DONE, error -> DEVICE_REPORTED_ERROR). | `JooqPanConfigSyncRepositoryTest.theEndBindsToTheJobsEpochOnlyNeverItsCancelFlag`; `PanConfigSyncLifecycleTest` (cancel during the write; job cancelled before the end) |
| P0-3 post-send steps inside the pre-send catch; re-run refused at 08:00 rolled back the end | `execute` ends its pre-send `try` at the preparation; `afterSend` sends, records, ends (one retry; fallback ends the job alone with the matching state) and then re-runs readiness in a separate transaction (`followUp`), whose failure is kept as `followup_refusal` (`followUpFailed`). The re-run runs under the initiator's grant with an acknowledgement (initiator's, or `system:pan-config-sync-followup`), so it is never refused for business hours. | `PanConfigSyncJobExecutorTest.nothingAfterTheSendCanReplaceItsOutcomeWithAPreSendCode`, `...NeverLeftExecuting...`; `JooqPanConfigSyncRepositoryTest.theReRunIsTheInitiatorsAndIsNeverRefusedForBusinessHours` |
| P1-1 roles taken from a failed check 1 | `EVIDENCE_SQL` and `PanConfigSyncService.view` read a role only from a check 1 row with status PASS. | `JooqPanConfigSyncRepositoryTest.theUnitFenceCovers...`; `PanConfigSyncServiceTest.rolesCountOnlyFromAPassedCheckOne...` |
| P1-2 unresolved dispatch / resume not fenced; view ignored open runs | `unitRefusal` adds open resume (`RUN_ALREADY_ACTIVE`) and sent-never-observed failover writes on the cluster or a member (`UNRESOLVED_DISPATCH`); the view reads the same (`unitBusy`, only when check 4 failed). | same as P1-1 |
| P1-3 `mutation_possible` stuck after a not-sent write; pre-contact aborts treated as uncertain; uncertain fallback FAILED | Not-sent outcomes clear the flag with the terminal state (guard allows only that); `Cancelled`/`AdmissionWait`/`LeaseLost` from the transport are `NOT_SENT_BEFORE_CONTACT`; not-sent outcomes get no re-run; the fallback uses OUTCOME_UNKNOWN for an uncertain write. | `PanConfigSyncJobExecutorTest.aCancelOrLeaseRefusalBeforeContact...`, `aSwitchTurnedOffAfterThePreparation...`; `JooqPanConfigSyncRepositoryTest.aWriteThatMayHaveBeenSent...` |
| P1-4 acknowledgement before 08:00 dropped | The record stores `business_hours_ack_at` whenever the grant carried the acknowledgement; W4 and the re-run use the record's. | `JooqPanConfigSyncRepositoryTest.anAcknowledgementGivenJustBeforeEight...` |
| P2-1 UI poll errors reported as a failed request; open sync not followed after reload | The request and the follow-up are separate; reads are retried (five misses at most) and never shown as "Request failed"; an open `configSync.latest` is shown and followed on load; a new unit starts clean. | vitest `PanConfigSync.test.tsx` (two new cases) |
| P2-2 keygen body unbounded, status unanchored | `keygen` and the HA-state read use the bounded, root-anchored `deviceStatus`. | `PanConfigSyncJobExecutorTest.deviceStatusReadsTheRootElementOnly` |
| P2-3 missing tests | Items 1-6 as above; item 5 `anIdentityOrPairingMismatchInTheFreshReadSendsNothing`; item 7 `PanConfigSyncRouteTest.aMissingOrWrongCsrfTokenOrAMissingOriginIsRefusedBeforeTheService` (the gate chain checks the CSRF token and the presence of `Origin`; a cross-origin value check is not a gate-chain feature today and is not added here). | as listed |
