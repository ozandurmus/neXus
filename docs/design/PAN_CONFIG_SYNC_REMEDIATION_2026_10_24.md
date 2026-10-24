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
| R10 | That run's check 1 recorded the source as `active` and the target as `passive`. | `ROLES_CHANGED` |
| R11 | No open failover or readiness run on the unit, and no other open config sync on it. | `RUN_ALREADY_ACTIVE`, `CONFIG_SYNC_ALREADY_ACTIVE` |
| R12 | No open failover incident on the cluster or on either member. | `OPEN_INCIDENT` |

The job is recorded under the administrator's manual-admission grant (V168 columns: role, business hours,
acknowledgement), so it is claimable at once, outside the job windows, like every administrator's manual job.

### 3.2 In the job, before any device contact

| # | Precondition | Stop code |
| --- | --- | --- |
| W1 | Mutation switch on. | `FAILOVER_MUTATION_DISABLED` |
| W2 | The request is at most 30 minutes old when the worker starts it. | `CONFIG_SYNC_REQUEST_EXPIRED` |
| W3 | The job still owns its lease (epoch, module owner); R7, R11 (other runs) and R12 hold again. | `OWNERSHIP_LOST`, `MEMBER_SET_CHANGED`, `RUN_ALREADY_ACTIVE`, `OPEN_INCIDENT` |
| W4 | Business hours: a job submitted outside business hours without an acknowledgement is refused if business hours have begun since (the failover's first-write rule). | `BUSINESS_HOURS_CONFIRM_REQUIRED` |
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
read once more immediately before the transport is invoked (`MUTATION_DISABLED_BEFORE_SEND`; the attempt is then
recorded NOT_SENT). The write goes to the source (active) member's endpoint only; the target member is never sent a
write.

## 4. Result handling

The op response is parsed for its root `status` attribute only; its text is never stored or shown.

| Response | Record | Job | Readiness re-run |
| --- | --- | --- | --- |
| HTTP 200, `<response status="success">` | `DONE`, `CONFIG_SYNC_REQUESTED` | `COMPLETED` | yes |
| HTTP 200, `<response status="error">` | `STOPPED`, `DEVICE_REPORTED_ERROR` | `FAILED` | yes |
| no reply, transport failure, other HTTP status, other or oversized body | `STOPPED`, `OUTCOME_UNKNOWN` | `OUTCOME_UNKNOWN` | yes |
| stop after the fresh read (F1-F4) | `STOPPED`, the code | `FAILED` | yes |
| stop before contact (W1-W5, R*) | `STOPPED`, the code | `FAILED` | no |

Nothing is ever retried automatically. A new attempt is a new request by an administrator, which needs a new fresh
readiness with check 4 FAIL.

The readiness re-run is submitted by the job itself, in the same transaction that ends the record, for the same unit,
in the initiator's name and under the initiator's grant (its role; its business-hours acknowledgement when the request
had one). It waits 15 s after the reply first, without contacting a device, so the peer has time to apply the
configuration. If it cannot be admitted (for example business hours began after a request made outside them), the
record keeps the refusal in `followup_refusal` and the screen offers the normal "Run pre-checks".

An uncertain outcome does not open a failover incident: the write does not move traffic or HA roles. Its uncertainty is
recorded on the record, its step attempt and its job (`OUTCOME_UNKNOWN`), all in the audit log, and the readiness re-run
shows the real state.

## 5. Storage and audit

`pan_config_sync` (V178): one row per request, with opaque references only (cluster ref, the two member ids, job id,
readiness run ids), the initiator fingerprint and role, business-hours facts, state, outcome, the device status word
(`success`/`error`) and timestamps. No device output, address or name. An audit trigger (`fn_audit_capture`) records
every insert and update; the request, the admissions, the send preparation and the end are each audited transactions
(`pan_config_sync_request`, `pan_config_sync_admission`, `pan_config_sync_send`, `pan_config_sync_end`). A trigger
closes a record whose job ends without the worker closing it (`JOB_ENDED_WITHOUT_RESULT`, or `OUTCOME_UNKNOWN` once a
send was prepared). Readiness and failover admission refuse a unit while it has an open config sync.

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
