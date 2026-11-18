# Operations — static route add and delete on firewalls (design contract)

## Status

**DRAFT -- DO NOT IMPLEMENT.** Written 2026-11-17 against `origin/main` (`5e15072a`); revised 2026-11-18 after the role-diverse decision-council review (`GOV_PO_ROLE_MIGRATION.md` §7 trigger (b)). No device or host was contacted while writing or revising this document. Every command or XML literal in §3 is a candidate proposal marked **`UNKNOWN` until verified per estate software version** (`debug cli` / Clish `?` / CLI help), with the exact verified literal and parsed/written fields committed alongside this contract before any phase freeze. No code, database migration, `ActionRegistry` entry, or `gate_registry` row is approved by this draft. Implementation requires:
1. Product Owner resolution of the remaining open decisions in §6.2,
2. A **ratified Product Owner decision record** amending `docs/AI_DEVELOPMENT_PROTOCOL.md` (lines ~210 and ~234–235) and `AGENTS.md` ("Network action taxonomy") as specified in §1.4, and
3. The Product Owner's literal-by-literal command-gate sign-off per phased rollout stage (`R0` → `R1` → `R2`, §1.3).

---

## 1. Scope, phased rollout, and constitutional boundary

### 1.1 In scope (Wave 1)
A job under **Operations › Static routes** that adds or deletes **exactly one IPv4 static route per job** (one non-default destination IPv4 prefix and one next-hop IPv4 gateway address, plus egress interface and administrative distance/metric/priority where supported) on an enrolled firewall or HA cluster:

| Vendor | Target unit in neXus | Routing context (`routing_context`) | Management / HA reach model |
| --- | --- | --- | --- |
| **Check Point** | Standalone Gaia gateway or ClusterXL HA cluster (both members enrolled, non-Cloning-Group mode). VSX Virtual Systems (`VS<VSID>`) are refused (`ROUTING_CONTEXT_UNSUPPORTED`) until measured | `gaia-default` (VS0 non-VSX) | SSH (`SSH_EXEC`), Expert landing invoking Gaia Clish via `clish -c` (or direct Clish landing where detected; **no `bash -lc` anywhere** per the shell normalization contract). ClusterXL does **not** sync Gaia OS static routes across members (`MEMBER_SPECIFIC` per `AGENTS.md`); jobs targeting a ClusterXL cluster execute on both members sequentially (Standby first, then Active) |
| **Palo Alto Networks** | Standalone PAN-OS firewall or active-passive HA pair (direct firewall HTTPS XML API) where neither the virtual router nor the route is Panorama-template-sourced (`src="tpl"`) | Named legacy `virtual-router` (`src != "tpl"`). PAN-OS Advanced Routing Engine (`ARE` / `logical-router`) is **refused (`ROUTING_CONTEXT_UNSUPPORTED`)** until estate presence and XML schema are measured | Direct firewall `PAN_XML_API` (`POST /api/`), using dedicated neXus admin account `<ADMIN_USER>`. On an active-passive HA pair, the write and `action=partial` commit target the verified **Active** member only; post-check polls HA running-config sync until `synchronized` |
| **Cisco ASA** | Standalone routed-mode ASA or Active/Standby failover pair, single-context or multi-context | `single` (default context) or named security context via `changeto context <CTX_NAME>` (`A0-ASA-19`) | `SSH_INTERACTIVE` privilege-15 session (`show curpriv` = 15, space-paged without `terminal pager 0`, V233), with strict per-step prompt verification (`UNEXPECTED_PROMPT` aborts immediately). On a failover pair, commands run on the verified **Active** unit (matched by serial in `ha_pair_identity`, V209) and replicate automatically to Standby |
| **Fortinet FortiGate** | Standalone FortiGate or 2-member FGCP active-passive cluster, single-VDOM or multi-VDOM, **not managed by FortiManager** | `root` (single-VDOM) or existing verified VDOM via `config vdom` → `edit <VDOM_NAME>` (`FORTINET_CONTRACT.md`) | `SSH_INTERACTIVE` session to the standalone unit or verified FGCP **Primary** after confirming central management is disabled/absent and `<VDOM_NAME>` already exists. FGCP automatically synchronizes `config router static` changes to the Secondary upon `end` |

### 1.2 Out of scope (prohibited or deferred)
- **Default route (`0.0.0.0/0`)**: Unconditionally forbidden (`DEFAULT_ROUTE_FORBIDDEN`) for both `ADD` and `DELETE`. There is no `allow_default_route` override.
- **Dynamic routing**: BGP, OSPF, RIP, EIGRP, PIM/multicast, route redistribution, prefix lists, route maps, BFD.
- **Policy-based routing (PBR)**: Check Point PBR rules, PAN PBF rules, ASA policy-route maps, FortiGate `config router policy`.
- **IPv6 static routes**: Gaia `set ipv6 static-route`, PAN `routing-table/ipv6/static-route`, ASA `ipv6 route`, FortiGate `config router static6`.
- **Special route types and Check Point extended route attributes**: Blackhole / reject / discard / `null0`, `logical` next-hop, `ping` next-hop, Check Point routes carrying `rank`, `scopelocal`, or `ping` attributes (unless captured and restored identically; refused in Wave 1 with `UNSUPPORTED_ROUTE_ATTRIBUTE`), next-VR (`next-vr`), VTI/tunnel-only unnumbered routes without a next-hop IP, SLA/object-tracked routes (`track <id>`), SD-WAN member/zone virtual routes (`set sdwan-zone`).
- **ClusterXL Cloning Group Mode**: Refused unconditionally (`CLONING_GROUP_UNSUPPORTED`).
- **Centrally managed routing contexts**: Panorama template-sourced virtual routers or static routes (`src="tpl"` → `PANORAMA_TEMPLATE_MANAGED`), FortiManager-managed FortiGates (`CENTRAL_MANAGEMENT_CONFLICT`), Check Point VSX Virtual Systems (`VS<VSID>` → `ROUTING_CONTEXT_UNSUPPORTED`), and PAN-OS ARE `logical-router` (`ROUTING_CONTEXT_UNSUPPORTED` until measured).
- **Scheduled / timed execution**: No `scheduled_for` timestamp is permitted for Class 3 static-route writes.
- **Batch multi-route edits**: Strictly one prefix + one next-hop add or delete per job.
- **Cisco FTD (FMC REST)** and **FortiManager ADOM script pushes**.

### 1.3 Phased delivery plan (`R0` → `R1` → `R2`)
Per the 2026-11-18 decision-council review, delivery is split into three sequential, gated phases:
1. **Phase `R0` (Read gates + command-plan preview only, zero writes)**:
   - Gate and implement only the `CLASS_0` read commands (static-route config reads, PAN `<check><pending-changes/></check>`, FortiGate central-management and VDOM-existence reads, Check Point anti-spoofing / Cloning Group checks), Stage A/B pre-check evaluation, and deterministic command-plan + inverse-plan preview (`static_route_read` / preview).
   - Verify every candidate literal in §3 against actual estate software versions (`debug cli` / Clish `?` help) and commit the exact verified literal and field schema next to this contract before advancing to `R1`.
   - Zero `CLASS_3` write gates, zero write transport dispatch, `mutation_possible` always `false`.
2. **Phase `R1` (One vendor, one pilot unit until `REAL_ENV_VALIDATED`)**:
   - Enable `CLASS_3_CONFIGURATION_WRITE` static-route add/delete for **one vendor** on **one designated pilot unit** only (enforced by per-unit allowlist + DB kill switch + env hard lock).
   - No second unit or second vendor may be enabled until the pilot unit completes `ADD`, post-check, `DELETE`, rollback, ledger audit, `operations_change` mail notification, and follow-up collection in the real environment (`REAL_ENV_VALIDATED`) with Product Owner sign-off.
3. **Phase `R2` (Remaining vendors one at a time)**:
   - Onboard the remaining vendors **strictly one vendor at a time**, each requiring its own `R0` literal verification committed alongside the contract and its own single-unit pilot `REAL_ENV_VALIDATED` gate before widening target scope.

### 1.4 Constitutional boundary and required amendments before implementation
Under `AGENTS.md` ("Authority hierarchy" item 1 and "Network action taxonomy") and `docs/AI_DEVELOPMENT_PROTOCOL.md`, static-route configuration writes are **prohibited at the current repository baseline** until a ratified Product Owner decision record amends the governing files:
1. **Mandatory ratified PO decision record**: Before any implementation PR (beyond `R0` read-only measurement approved through the standard `CLASS_0` command gate), the Product Owner must ratify a dedicated decision record (to be created; working title "static route configuration writes", dated on ratification).
2. **Required textual amendments**: That decision record must explicitly amend:
   - `docs/AI_DEVELOPMENT_PROTOCOL.md` line ~210 (*"No new class 2, 3 or 4 command at the current product maturity"*),
   - `docs/AI_DEVELOPMENT_PROTOCOL.md` lines ~234–235 (*"Prohibited at current maturity (taxonomy classes 2-4): firewall configuration writes, policy install, commit, reboot/shutdown, forced failover, interface/routing change, credential change and automatic remediation"*), and
   - `AGENTS.md` (*"Network action taxonomy"*),
   carving out bounded, interactive, four-eyes-approved, ledgered `CLASS_3_CONFIGURATION_WRITE` static-route operations under this contract once frozen.
3. **Four-eyes governance rule replacing the failover single-approver rule**: The ratifying PO decision record must state explicitly that, unlike failover execution (`FAILOVER_EXECUTION_PLAN.md`, where a single `security_admin` can approve and execute), static-route actions enforce **four-eyes separation of duties across roles**:
   - **`operation_admin` requests** (`static_route_request`, recorded as `requested_by`),
   - **`security_admin` approves** (`static_route_approve`, recorded as `approved_by`),
   - **`requester != approver`** (`requested_by <> approved_by`, enforced in service logic and database `CHECK` constraint), and
   - **Executor is recorded and constrained** (`executed_by`, `executed_at` on the request and dispatch ledger, with `executed_by IN (requested_by, approved_by)` — only the requesting `operation_admin` or the approving `security_admin` may trigger execution).

---

## 2. Action class, RBAC, four-eyes approval, change control, ledger, kill switch, and mutual exclusion

### 2.1 Action class (`CLASS_3_CONFIGURATION_WRITE` with Class 2 operational safety envelope)
- **Resolved classification (`CLASS_3_CONFIGURATION_WRITE` / `configuration-write`)**:
  1. Adding or deleting a static route mutates the live forwarding table (RIB/FIB) immediately **and** persists across reboots via `save config` / `commit` / `write memory` / FortiOS `end`.
  2. Per the 2026-11-18 council decision, all static-route write, save, commit, and candidate-revert gate rows use **`CLASS_3_CONFIGURATION_WRITE` (`configuration-write`)** in `ActionClass.java` and `gate_registry`, following the V178 precedent (`pan_failover_config_sync`, `PO_DECISION_RECORD_2026_10_24_PAN_CONFIG_SYNC.md` §4).
  3. Every execution is wrapped in the full **Class 2 (`FAILOVER_EXECUTION_PLAN.md`) operational-safety envelope**: four-eyes approval, fresh Stage B live pre-checks immediately before the write, durable pre-send dispatch recording (`mutation_possible = true`), zero automatic retries, before/after route-table SHA-256 digests, and durable target quarantine on `OUTCOME_UNKNOWN`.
  4. Only the exact approved `gate_id` + `canonical_command_key` pairs are exempted in `GateRow.violatesWriteMarkerDenylist`, gated behind both the **database kill switch** (`operations_static_route_write_enabled`) and the **environment hard lock** (`NEXUS_STATIC_ROUTE_MUTATION_ENABLED`, default `false`).

### 2.2 Roles allowed (`ActionRegistry.java`)
| Action ID | Console submittable | Allowed roles (`RoleToken`) | Four-eyes / principal rule | `aiview` (`replay_viewer`) rule |
| --- | --- | --- | --- | --- |
| `static_route_read` | `true` | `security_admin`, `operation_admin`, `operator`, `viewer`, `replay_viewer` | Read-only | Masked projection only (`FW-TANGO-04`, `CLS-ROMEO-01`, masked prefixes/next-hops `192.0.2.x`); actor fingerprints, ticket refs, reasons, and comments withheld |
| `static_route_request` | `true` | `operation_admin` | Populates write-once `requested_by` (`operation_admin` principal) | Never (`403 WRONG_ROLE`, even if the session also holds an admin token) |
| `static_route_approve` | `true` | `security_admin` | Populates write-once `approved_by` (`security_admin` principal); requires `approved_by <> requested_by` | Never (`403 WRONG_ROLE`) |
| `static_route_execute` | `true` | `operation_admin`, `security_admin` | Populates write-once `executed_by`; **must equal `requested_by` or `approved_by`** (`executed_by IN (requested_by, approved_by)`) | Never (`403 WRONG_ROLE`) |
| `static_route_rollback` | `true` | `operation_admin`, `security_admin` | Creates inverse rollback request (see §4.3 and open PO decision PO-1 / `Q4b` for 60-minute emergency rollback approval rule) | Never (`403 WRONG_ROLE`) |
| `static_route_kill_switch_off` | `true` | `operation_admin`, `security_admin` | Any admin may turn the DB kill switch **OFF** immediately with a mandatory reason | Never (`403 WRONG_ROLE`) |
| `static_route_kill_switch_on` | `true` | `security_admin` | Only `security_admin` may turn the DB kill switch **ON** with a mandatory reason | Never (`403 WRONG_ROLE`) |

### 2.3 Four-eyes approval (`operation_admin` requests, `security_admin` approves, executor recorded)
1. **Role-separated principals (`requester != approver`, executor in `{requester, approver}`)**:
   - A route change is requested by an authenticated `operation_admin` (`requested_by`) and approved by a distinct authenticated `security_admin` (`approved_by`).
   - Execution (`static_route_execute`) records `executed_by` and `executed_at`, and requires `executed_by` to be either `requested_by` or `approved_by`.
   - All three identity fields (`requested_by`, `approved_by`, `executed_by`) are populated exclusively from the server-side authenticated session principal (`NFKC`-normalized `actor_fingerprint`, never from a request-body field; `FAILOVER_EXECUTION_PLAN.md` FR-P0.7) and are **write-once** in PostgreSQL.
2. **Immutable request-digest and approved command-hash set binding**:
   - At request creation, the service compiles the canonical step plan from validated fields and computes:
     - `request_digest = SHA-256(canonical_json(unit_id, member_device_ids, vendor, routing_context, operation, destination_cidr, nexthop_ip, interface_name, distance, metric, priority, route_name, device_comment, change_ticket_ref, gate_version, compiled_steps))`, and
     - `approved_command_sha256_set`: the ordered list of `SHA-256(compiled_command)` hashes for every step in the forward and candidate-revert plan.
   - The approval record binds to `(request_id, revision, request_digest, approved_command_sha256_set, window_from, window_until)`.
3. **Approval expiry and send-time principal/role re-verification**:
   - An unapproved request expires automatically after **60 minutes** (`state = 'EXPIRED'`).
   - An approval grants an interactive execution window (`window_from` to `window_until`, maximum **24 hours**, non-extendable).
   - **No `scheduled_for`**: Timed or deferred background execution of a Class 3 static-route write is prohibited. Execution must be triggered interactively while `now()` is within `[window_from, window_until]`.
   - **Send-time re-check**: Immediately before crossing `mutation_possible = true` on each step, the worker re-verifies against the live authority that:
     1. `now()` is within `[window_from, window_until]` and `revoked_at IS NULL`,
     2. `requested_by` is still active and holds `operation_admin`,
     3. `approved_by` is still active, is distinct from `requested_by`, and holds `security_admin`, and
     4. `executed_by` is still active, equals `requested_by` or `approved_by`, and holds `operation_admin` or `security_admin`.

### 2.4 Business-hours confirmation, DB kill switch, and environment hard lock
1. **Business-hours guard (`08:00–20:00 Europe/Istanbul`, every day)**:
   - Following `PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md` (V168) and `PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md` §3, manual execution carries immediate manual admission (`ManualAdmission.Grant`), never waiting for the 6-hour polling windows (`00:00 / 06:00 / 12:00 / 18:00 Europe/Istanbul`), and **never accepts `scheduled_for`**.
   - Submitting `static_route_execute` inside `08:00–20:00 Europe/Istanbul` without `X-Nexus-Business-Hours-Ack: true` (or `?business_hours_ack=true`) is refused with `409 BUSINESS_HOURS_CONFIRM_REQUIRED` before any job row is admitted. When acknowledged, `submitted_in_business_hours = true`, `business_hours_ack_by`, and `business_hours_ack_at` are recorded write-once on both the request and the ledger.
   - **Pre-write recheck (`mutationAdmission`)**: If `static_route_execute` was invoked just before `08:00` without business-hours acknowledgement and reaches the worker's pre-write fence at or after `08:00`, the worker aborts before the first write (`BUSINESS_HOURS_CONFIRM_REQUIRED`).
   - **Mid-flight completion exception (V168 §2.5 parity)**: Once `mutation_possible = true` has been committed (e.g., Check Point Standby member written, or route written and `save config` / `commit` / `write memory` next), the second member write and the save/commit step are **never** refused by the `08:00` boundary, preventing a half-applied or unsaved route.
2. **Dual kill switch (DB runtime switch + environment hard lock)**:
   - **Environment hard lock (`NEXUS_STATIC_ROUTE_MUTATION_ENABLED`, default `false`)**: Hard deployment ceiling. When `false`, the service and worker refuse all static-route write admissions and dispatches regardless of database state.
   - **Database kill switch (`operations_static_route_write_enabled`, default `OFF`)**: Audited runtime switch checked at request creation, approval, job admission, and immediately before crossing `mutation_possible = true`:
     - **Turn OFF**: Any `operation_admin` or `security_admin` may flip the switch to `OFF` immediately with a mandatory non-empty `reason` (`char_length(btrim(reason)) >= 8`).
     - **Turn ON**: **Only `security_admin`** may flip the switch to `ON`, with a mandatory non-empty `reason` and audit log entry, and only when the environment hard lock is `true`.

### 2.5 Change-control ledger, write-once identities, append-only retention, and `operations_change` mail route
Modeled on `PO_DECISION_RECORD_2026_09_22_SCHEDULED_DEVICE_WRITES_FROM_SCRIPTS.md` condition 3, `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §5, and `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3:
- **Required change-ticket reference and reason**: Every request requires both a structured `change_ticket_ref` (`\A[A-Za-z0-9_.:/-]{3,64}\z`) and a human justification `reason` (`8..255` printable chars).
- **Recorded ledger metadata**:
  - Actor identities and timestamps: `requested_by`, `requested_at`, `approved_by`, `approved_at`, `executed_by`, `executed_at`, plus `business_hours_ack` (`submitted_in_business_hours`, `business_hours_ack_by`, `business_hours_ack_at`).
  - Gate provenance and canonical parameters: `gate_id`, `gate_version`, `canonical_params_json`, `request_digest`, `command_sha256`.
  - Route-table digests: `pre_route_table_sha256` (SHA-256 of the canonicalized static-route table read in Stage B immediately before mutation) and `post_route_table_sha256` (SHA-256 of the canonicalized static-route table read in post-check after save/commit).
- **Write-once identity and digest enforcement**: A `BEFORE UPDATE` trigger on `static_route_change_request` enforces that `requested_by`, `requested_at`, `approved_by`, `approved_at`, `executed_by`, `executed_at`, `business_hours_ack_by`, `business_hours_ack_at`, `change_ticket_ref`, `request_digest`, `approved_command_sha256_set`, and `pre_route_table_sha256` cannot be modified once set from `NULL` to non-`NULL`.
- **Append-only ledger and bank retention**: `static_route_dispatch_ledger` is strictly append-only (`REVOKE UPDATE, DELETE, TRUNCATE ON static_route_dispatch_ledger FROM ui2_app`; step completion is recorded as an immutable `STEP_COMPLETED` append row referencing `(request_id, step_no)` or via a transition-guarded `SECURITY DEFINER` function that never alters pre-send columns). Rows are retained for the bank's mandatory regulatory retention period (open PO decision PO-3).
- **Fail-closed on unreadable/unwritable ledger**: If the before-send ledger insert fails, the worker **must not** issue the command to the transport (`OperationalLedgerUnreadableError` equivalent).
- **Quarantine on uncertainty**: Any `TRANSPORT_TIMEOUT` or `SESSION_DROPPED` after `IN_FLIGHT` sets the job and request to `OUTCOME_UNKNOWN` and opens a unit quarantine incident that blocks any subsequent route change or failover on that unit until a `security_admin` reconciles it via `JOB_RECONCILE`.
- **Mandatory `operations_change` mail notification on every terminal outcome**:
  - A new notification route **`operations_change`** emits an email on **every terminal state** (`COMPLETED`, `STOPPED`, `OUTCOME_UNKNOWN`, `REJECTED`, `REVOKED`, `EXPIRED`).
  - **Strict masking and DLP contract**: The notification payload and template include **only** the masked unit/member pseudonyms (`FW-TANGO-04`, `CLS-ROMEO-01`), `vendor`, `operation` (`ADD` | `DELETE`), `request_id`, `change_ticket_ref`, actor role/token summaries, timestamps, before/after route-table digest match status, and `outcome_code`. It **never** includes destination IPv4 prefixes, next-hop IP addresses, interface names, `device_comment`, `reason` prose, or raw CLI/XML output.

```sql
-- Proposed schema sketch (DRAFT -- DO NOT IMPLEMENT; requires BEGIN/ROLLBACK dry run before any rollout)
CREATE TABLE static_route_change_request (
    request_id                  UUID PRIMARY KEY,
    idempotency_key             UUID NOT NULL UNIQUE,
    unit_id                     TEXT NOT NULL,
    management_domain_id        TEXT,
    vendor                      TEXT NOT NULL CHECK (vendor IN ('check_point', 'palo_alto', 'cisco_asa', 'fortinet')),
    routing_context             TEXT NOT NULL,
    operation                   TEXT NOT NULL CHECK (operation IN ('ADD', 'DELETE')),
    destination_cidr            TEXT NOT NULL CHECK (destination_cidr <> '0.0.0.0/0'),
    nexthop_ip                  TEXT NOT NULL,
    interface_name              TEXT,
    distance                    INTEGER,
    metric                      INTEGER,
    priority                    INTEGER,
    route_name                  TEXT,
    device_comment              TEXT,
    change_ticket_ref           TEXT NOT NULL CHECK (char_length(btrim(change_ticket_ref)) BETWEEN 3 AND 64),
    reason                      TEXT NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 8 AND 255),
    gate_version                TEXT NOT NULL,
    canonical_params_json       JSONB NOT NULL,
    request_digest              TEXT NOT NULL,
    approved_command_sha256_set JSONB NOT NULL,
    inverse_plan_json           JSONB NOT NULL,
    pre_route_table_sha256      TEXT,
    post_route_table_sha256     TEXT,
    requested_by                TEXT NOT NULL,
    requested_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by                 TEXT,
    approved_at                 TIMESTAMPTZ,
    executed_by                 TEXT,
    executed_at                 TIMESTAMPTZ,
    window_from                 TIMESTAMPTZ,
    window_until                TIMESTAMPTZ,
    revoked_by                  TEXT,
    revoked_at                  TIMESTAMPTZ,
    job_id                      UUID,
    state                       TEXT NOT NULL CHECK (state IN (
        'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'REVOKED', 'EXPIRED', 'QUEUED',
        'PRECHECK', 'EXECUTING', 'SAVING', 'POSTCHECK', 'ROLLING_BACK',
        'COMPLETED', 'STOPPED', 'OUTCOME_UNKNOWN'
    )),
    mutation_possible           BOOLEAN NOT NULL DEFAULT FALSE,
    outcome_code                TEXT,
    submitted_in_business_hours BOOLEAN,
    business_hours_ack_by       TEXT,
    business_hours_ack_at       TIMESTAMPTZ,
    CONSTRAINT static_route_four_eyes_chk CHECK (approved_by IS NULL OR approved_by <> requested_by),
    CONSTRAINT static_route_executor_chk CHECK (executed_by IS NULL OR executed_by = requested_by OR executed_by = approved_by)
);

CREATE TABLE static_route_dispatch_ledger (
    ledger_id               BIGSERIAL PRIMARY KEY,
    request_id              UUID NOT NULL REFERENCES static_route_change_request(request_id),
    job_id                  UUID NOT NULL,
    step_no                 INTEGER NOT NULL,
    event_type              TEXT NOT NULL CHECK (event_type IN ('PRE_SEND', 'POST_SEND')),
    phase                   TEXT NOT NULL CHECK (phase IN ('WRITE', 'SAVE', 'CANDIDATE_REVERT', 'ROLLBACK_WRITE', 'ROLLBACK_SAVE')),
    target_device_id        UUID NOT NULL,
    management_domain_id    TEXT,
    vendor                  TEXT NOT NULL,
    routing_context         TEXT NOT NULL,
    gate_id                 TEXT NOT NULL,
    gate_version            TEXT NOT NULL,
    canonical_params_json   JSONB NOT NULL,
    command_sha256          TEXT NOT NULL,
    requested_by            TEXT NOT NULL,
    requested_at            TIMESTAMPTZ NOT NULL,
    approved_by             TEXT NOT NULL,
    approved_at             TIMESTAMPTZ NOT NULL,
    executed_by             TEXT NOT NULL,
    executed_at             TIMESTAMPTZ NOT NULL,
    business_hours_ack      BOOLEAN NOT NULL DEFAULT FALSE,
    business_hours_ack_by   TEXT,
    business_hours_ack_at   TIMESTAMPTZ,
    pre_route_table_sha256  TEXT NOT NULL,
    post_route_table_sha256 TEXT,
    credential_ref          TEXT NOT NULL,
    delivery                TEXT NOT NULL CHECK (delivery IN (
        'IN_FLIGHT', 'REPLY_RECEIVED', 'TRANSPORT_TIMEOUT', 'SESSION_DROPPED', 'NOT_SENT_BEFORE_CONTACT'
    )),
    device_outcome          TEXT NOT NULL CHECK (device_outcome IN ('PENDING', 'OK', 'DEVICE_ERROR', 'UNKNOWN')),
    recorded_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (request_id, step_no, event_type)
);
-- Privileges: GRANT SELECT, INSERT, UPDATE ON static_route_change_request TO ui2_app; (with write-once BEFORE UPDATE trigger on identity/digest columns)
-- Append-only ledger: GRANT SELECT, INSERT ON static_route_dispatch_ledger TO ui2_app; REVOKE UPDATE, DELETE, TRUNCATE ON static_route_dispatch_ledger FROM ui2_app;
```

### 2.6 Idempotency key and bidirectional per-device & per-management-domain concurrency fence
- **Client idempotency header**: `POST /api/v2/operations/static-routes` requires `Idempotency-Key: <UUIDv4>`. Retrying the same key with the same payload returns the existing request record (`200`/`201`); reusing the key with a different payload returns `409 IDEMPOTENCY_KEY_PAYLOAD_MISMATCH`.
- **Natural active-prefix fence**: A partial unique index on `static_route_change_request (unit_id, routing_context, destination_cidr)` where `state IN ('PENDING_APPROVAL', 'APPROVED', 'QUEUED', 'PRECHECK', 'EXECUTING', 'SAVING', 'POSTCHECK', 'ROLLING_BACK', 'OUTCOME_UNKNOWN')` guarantees at most one active or unresolved change per `(unit_id, routing_context, destination_cidr)`.
- **One write per device AND per management domain, enforced symmetrically from both sides**:
  - Static-route execution acquires an exclusive lock across **both** the target device/unit (`unit_id` and every member `device_id`) **and** its `management_domain_id` (Check Point MDS/Security Management domain, Panorama management domain, or FortiManager domain).
  - **Bidirectional enforcement across five conflicting job families**:
    1. **Device backup** (`add backup local`, `asa_backup_archive`, scheduled/manual backup runs),
    2. **Check Point MDS export / management collection**,
    3. **Policy collection** (`policy_collect`),
    4. **Failover readiness** (`failover_readiness`), and
    5. **Failover execution / config sync** (`failover_run`, `pan_failover_config_sync`).
  - **Both sides check the shared lock**:
    - A static-route job refuses to enter `PRECHECK` / `EXECUTING` (`409 UNIT_OR_DOMAIN_BUSY`) if any of the five families above holds an active lease/job on the same `device_id`, `unit_id`, or `management_domain_id`.
    - Conversely, admission and worker claim for backup, MDS export, policy collection, failover readiness, and failover execution check for any active static-route request in `('PRECHECK', 'EXECUTING', 'SAVING', 'POSTCHECK', 'ROLLING_BACK', 'OUTCOME_UNKNOWN')` on that `device_id`, `unit_id`, or `management_domain_id` and defer/refuse until the static-route change completes (and until any `OUTCOME_UNKNOWN` quarantine is reconciled).

---

## 3. Per-vendor candidate command literals, HA behaviour, and save/commit

**Mandatory `UNKNOWN` verification and shell-normalization rules across all vendors**:
1. **Every literal in §3 is marked `UNKNOWN` until verified per estate software version**: Before freezing any vendor for Phase `R1`/`R2`, each candidate command / XML payload must be verified in Phase `R0` against the exact estate OS versions (`debug cli` on PAN-OS, Clish `?` help on Check Point Gaia, `?` help on Cisco ASA, CLI help on FortiOS), and the **exact verified command literal and parsed/written field list must be committed alongside this contract**.
2. **No `bash -lc` anywhere**: Per the repository shell-normalization contract, no gate row or worker transport invocation may wrap commands in `bash -lc`.
3. **Gate `platform_role_scope` alignment**: Every candidate gate row uses the exact role scope registered in `gate_registry_fixture.yaml`: **`cp_gaia_gateway`**, **`pan_firewall`**, **`cisco_asa_firewall`**, and **`fortigate`**.
4. **No retries, raw output discarded**: All candidate rows have **retry = `none`**, **max frequency = `once per member per approved job`**, **session reuse = `one session/API-key per member per job`**, **secret-output risk = `none`** (parsers extract only derived status, route-table SHA-256 digests, and match booleans in memory and discard raw text per `AGENTS.md` Raw-evidence law).

### 3.1 Check Point (Gaia Clish — Standalone and Non-Cloning ClusterXL Members; `cp_gaia_gateway`)

| Phase | Candidate command literal (`canonical_command_key`) | Verification status | Shell / context / transport | Timeout | Official vendor source |
| --- | --- | --- | --- | --- | --- |
| **READ (RIB)** | `ip -4 route show table all` (existing gate `cp_inventory_ip_route_show_table_all`; **no `bash -lc`**) | `VERIFIED` (V187) | `expert` / `SSH_EXEC` | 30 s | Linux iproute2 (`CP_INVENTORY_COMMAND_GATE_ENTRIES.md` entry 3) |
| **READ (Clish config)** | `clish -c "show route static"` | `UNKNOWN` (verify per Gaia R81.20/R82 build in `R0`) | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Check Point R81.20 & R82 Gaia Administration Guide, *Routing › Viewing Static Routes* (`show route static`, never `show static-route`) |
| **ADD** | `clish -c 'set static-route <DEST_CIDR> nexthop gateway address <NEXTHOP_IP> on'` (with optional priority `1..8`: `... address <NEXTHOP_IP> priority <PRIORITY> on`) | `UNKNOWN` (verify via Clish `?` per Gaia build in `R0`) | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Check Point R81.20 & R82 Gaia Admin Guide (`set static-route <Dest> nexthop gateway address <GW> [priority <1-8>] {on\|off}`) |
| **ADD (comment, optional)** | `clish -c 'set static-route <DEST_CIDR> comment "<SAFE_COMMENT>"'` | `UNKNOWN` (verify via Clish `?` per Gaia build in `R0`) | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Check Point R81.20 & R82 Gaia Admin Guide (`set static-route <Dest> comment {"<Text>"\|off}`) |
| **DELETE** | `clish -c 'set static-route <DEST_CIDR> nexthop gateway address <NEXTHOP_IP> off'` (**never** bare `set static-route <DEST_CIDR> off`) | `UNKNOWN` (verify via Clish `?` per Gaia build in `R0`) | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Check Point R81.20 & R82 Gaia Admin Guide (`set static-route <Dest> nexthop gateway address <GW> off`) |
| **SAVE** | `clish -c "save config"` | `UNKNOWN` (verify per Gaia build in `R0`) | `expert_via_clish_c` / `SSH_EXEC` | 60 s | Check Point R81.20 & R82 Gaia Admin Guide, *Configuration Lock and Saving Configuration* (`save config`) |

- **DELETE safety rule (next-hop-scoped `off` only)**: Check Point `DELETE` **must** issue `set static-route <DEST_CIDR> nexthop gateway address <NEXTHOP_IP> off` and **must never** issue bare `set static-route <DEST_CIDR> off` (which deletes the entire destination prefix across all ECMP next-hops and strips prefix-level attributes).
- **Refusal of extended / special next-hops and route attributes**: If `clish -c "show route static"` shows that `<DEST_CIDR>` carries a `logical`, `blackhole`, `reject`, or `ping` next-hop, or carries non-default `rank`, `scopelocal`, or `ping` attributes, Stage A/B pre-check **refuses (`UNSUPPORTED_ROUTE_ATTRIBUTE`)** unless a future contract revision explicitly captures and restores those attributes in the inverse plan.
- **ClusterXL Cloning Group refusal**: Before any write on a ClusterXL cluster, Stage B inspects `cphaprob stat`; if **Cloning Group** mode is detected, the job refuses immediately (`CLONING_GROUP_UNSUPPORTED`).
- **Anti-spoofing "defined by routes" hazard warning**: When any interface in the target routing context has Check Point anti-spoofing topology configured as *"defined by routes"* (or when anti-spoofing topology mode is `UNKNOWN`), Stage A preview and Stage B pre-check surface `CP_ANTISPOOFING_DEFINED_BY_ROUTES_WARNING`, requiring explicit confirmation from both `requested_by` and `approved_by`.
- **HA behaviour (ClusterXL non-cloning)**: Gaia static routes are member-local (`MEMBER_SPECIFIC`). The worker verifies via fresh `cphaprob stat` that the two members are `Active` and `Standby`, then executes `ADD`/`DELETE` + `save config` + post-check on the **Standby** member first. Only after Standby passes post-checks does the worker execute `ADD`/`DELETE` + `save config` + post-check on the **Active** member. If Standby succeeds and Active fails before send, the worker follows **Option RB-A** (§4.3): stop immediately (`PARTIAL_CLUSTER_APPLY`) and surface a one-click gated rollback for Standby.
- **Database lock handling**: If `clish -c` returns `Database is locked`, the job fails closed (`GAIA_DB_LOCKED`) before mutation; it **never** issues `lock database override`.
- **VSX Virtual Systems (`VS<VSID>`)**: Refused (`ROUTING_CONTEXT_UNSUPPORTED`) in Wave 1. No `bash -lc` command form may be proposed or used.

### 3.2 Palo Alto Networks (Direct Firewall XML API — Legacy Virtual Router only; `pan_firewall`)

| Phase | Candidate request literal (`canonical_command_key`) | Verification status | Shell / context / transport | Timeout | Official vendor source |
| --- | --- | --- | --- | --- | --- |
| **READ (RIB)** | `type=op&cmd=<show><routing><route/></routing></show>` (existing gate `pan_inventory_show_routing_route`) | `VERIFIED` | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS XML API Operational Command Reference; `PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md` entry 4 |
| **READ (effective-running provenance)** | `type=op&cmd=<show><config><effective-running/></config></show>` (existing gate `pan_config_show_effective_running`) | `VERIFIED` | `not_applicable` / `PAN_XML_API` | 60 s | PAN-OS XML API Operational Reference; verifies `src != "tpl"` on both `<VR>` and the static route |
| **READ (running VR static routes)** | `type=config&action=show&xpath=/config/devices/entry[@name='localhost.localdomain']/network/virtual-router/entry[@name='<VR>']/routing-table/ip/static-route` (**`action=show` running config, never `action=get` candidate**) | `UNKNOWN` (verify via `debug cli` per PAN-OS version in `R0`) | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS XML API Configuration Reference (`type=config&action=show`) |
| **READ (pending changes)** | `type=op&cmd=<check><pending-changes/></check>` (**must return `<result>no</result>`**) | `UNKNOWN` (verify per PAN-OS version in `R0`) | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS XML API Operational Command Reference (`check pending-changes`) |
| **ADD (VR)** | `type=config&action=set&xpath=/config/devices/entry[@name='localhost.localdomain']/network/virtual-router/entry[@name='<VR>']/routing-table/ip/static-route/entry[@name='<ROUTE_NAME>']&element=<destination><DEST_CIDR></destination><nexthop><ip-address><NEXTHOP_IP></ip-address></nexthop><interface><IF_NAME></interface><metric><METRIC></metric><admin-dist><DISTANCE></admin-dist>` (built with XML encoder; optional leaves included only when set) | `UNKNOWN` (verify exact element schema per PAN-OS version in `R0`) | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS XML API Configuration Reference (`type=config&action=set`) |
| **DELETE (VR)** | `type=config&action=delete&xpath=/config/devices/entry[@name='localhost.localdomain']/network/virtual-router/entry[@name='<VR>']/routing-table/ip/static-route/entry[@name='<ROUTE_NAME>']` | `UNKNOWN` (verify per PAN-OS version in `R0`) | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS XML API Configuration Reference (`type=config&action=delete`) |
| **CANDIDATE REVERT (on pre-commit abort)** | `type=op&cmd=<revert><config/></revert>` (gated, ledgered candidate revert run only when `pending-changes` was verified `no` at job start and the job aborts after `set`/`delete` before `commit`) | `UNKNOWN` (verify exact XML form via `debug cli` per PAN-OS version in `R0`) | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS XML API Operational Command Reference (`revert config`) |
| **COMMIT (partial, admin-scoped)** | `type=commit&action=partial&cmd=<commit><partial><admin><member><ADMIN_USER></member></admin></partial></commit>` (scoped to dedicated neXus admin `<ADMIN_USER>`) | `UNKNOWN` (verify `action=partial` + XML structure per PAN-OS version in `R0`) | `not_applicable` / `PAN_XML_API` | 120 s | PAN-OS XML API Commit Configuration Reference (`type=commit&action=partial`) |
| **READ (commit job poll)** | `type=op&cmd=<show><jobs><id><JOB_ID></id></jobs></show>` (polled every 3 s up to 120 s until `<status>FIN</status>`) | `UNKNOWN` (verify per PAN-OS version in `R0`) | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS XML API Operational Command Reference (`show jobs id`) |
| **READ (HA state & config sync poll)** | `type=op&cmd=<show><high-availability><state/></high-availability></show>` (existing gate `pan_inventory_show_ha_state`; polled in post-check until running-config sync is `synchronized`) | `VERIFIED` | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS XML API Operational Reference (`PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md`) |

- **Running config reads only (`action=show`, never `action=get`)**: Both Stage B pre-check and post-check read the active running configuration via `type=config&action=show`, never `action=get` (which reads uncommitted candidate config).
- **Mandatory `pending-changes = no` AND dedicated-admin `action=partial` commit**: Stage B pre-check requires `<check><pending-changes/></check>` to return `<result>no</result>` (`PENDING_CHANGES_PRESENT` refuses even though partial commit is used). Commit always uses `type=commit&action=partial&cmd=<commit><partial><admin><member><ADMIN_USER></member></admin></partial></commit>` scoped to the dedicated neXus admin account `<ADMIN_USER>`.
- **Gated, ledgered candidate revert on pre-commit abort**: If `action=set` or `action=delete` is sent (so `mutation_possible = true` on candidate config) and the job aborts or fails validation *before* `type=commit&action=partial` is issued, the worker executes the gated, ledgered `pan_route_revert_config` (`phase = 'CANDIDATE_REVERT'`) to restore the clean candidate configuration (safe because `<check><pending-changes/></check>` was verified `no` before the job began) and verifies `<check><pending-changes/></check>` returns `no`.
- **Panorama template provenance refusal (`src="tpl"`)**: Stage A and Stage B inspect `effective-running`. If **either** the target `virtual-router` entry **or** the target `static-route` entry has Panorama template provenance (`src="tpl"`), the job refuses unconditionally (`PANORAMA_TEMPLATE_MANAGED`). Local overrides on template-sourced VRs are never created.
- **ARE `logical-router` refusal**: Any target or routing context using PAN-OS Advanced Routing Engine (`logical-router`) is refused (`ROUTING_CONTEXT_UNSUPPORTED`) until estate presence is decided (PO-5) and its XML schema is measured in `R0`.
- **HA behaviour (Active-Passive) and post-commit HA sync polling**: Before `set`/`delete`, the worker runs a fresh `<show><high-availability><state/></high-availability></show>` on both members, verifies both serials, confirms the target is `active` and the peer is `passive`, and confirms `running-config` sync is `synchronized`. After `type=commit&action=partial` finishes `FIN`/`OK` on Active, post-check polls `<show><high-availability><state/></high-availability></show>` until running-config synchronization returns to `synchronized` within the bounded poll window (or fails with `HA_CONFIG_OUT_OF_SYNC`).

### 3.3 Cisco ASA (Single-Context and Multi-Context, Routed Mode; `cisco_asa_firewall`)

| Phase | Candidate command literal (`canonical_command_key`) | Verification status | Shell / context / transport | Timeout | Official vendor source |
| --- | --- | --- | --- | --- | --- |
| **READ (mode & priv)** | `show curpriv`, `show mode`, `show version`, `show failover`, `show failover state` (existing gates in V78 / V210) | `VERIFIED` (V210) | `privileged_exec` / `SSH_INTERACTIVE` | 30 s | Cisco ASA Command Reference (`CISCO_ASA_CONTRACT.md`, `PO_DECISION_RECORD_2026_11_14_ASA_FAILOVER_READINESS_READS.md`) |
| **CONTEXT SWITCH (multi-context only)** | `changeto context <CTX_NAME>` | `UNKNOWN` (verify prompt transition per ASA version in `R0`) | `context` / `SSH_INTERACTIVE` | 30 s | Cisco ASA 9.22 General Operations CLI Config Guide (*Multiple Context Mode*, `A0-ASA-19`) |
| **READ (RIB & config)** | `show route` (existing gate `asa_show_route`) and `show running-config route` | `UNKNOWN` (`show running-config route` verified in `R0`) | `privileged_exec` (inside target context) / `SSH_INTERACTIVE` | 30 s | Cisco ASA Command Reference `S` (`show route`, `show running-config route`) |
| **ADD** | `configure terminal` → `route <IF_NAME> <DEST_IP> <NETMASK> <NEXTHOP_IP> <DISTANCE>` → `end` (`<DISTANCE>` default `1`, range `1..255`; ASA `route` has no CLI comment parameter) | `UNKNOWN` (verify per ASA version in `R0`) | `config_mode` (inside target context) / `SSH_INTERACTIVE` | 30 s | Cisco ASA Command Reference `I–R` (`route if_name dest_ip mask gateway_ip [distance]`) |
| **DELETE** | `configure terminal` → `no route <IF_NAME> <DEST_IP> <NETMASK> <NEXTHOP_IP>` → `end` | `UNKNOWN` (verify per ASA version in `R0`) | `config_mode` (inside target context) / `SSH_INTERACTIVE` | 30 s | Cisco ASA Command Reference `I–R` (`no route if_name dest_ip mask gateway_ip`) |
| **SAVE** | `write memory` | `UNKNOWN` (verify confirmation output per ASA version in `R0`) | `privileged_exec` (inside target context) / `SSH_INTERACTIVE` | 60 s | Cisco ASA Command Reference `T–Z` (`write memory`) |

- **Strict prompt verification (`UNEXPECTED_PROMPT` abort)**: The interactive ASA SSH driver validates the exact expected prompt after every line (`#` in privileged exec, `<hostname>/<CTX_NAME>#` after `changeto context <CTX_NAME>`, `(config)#` after `configure terminal`, and `#` after `end`). If any step returns an unexpected prompt, remains in a sub-mode, prompts for interactive confirmation (`[confirm]`, `?`), or emits `ERROR:` / `WARNING:` / `Invalid input`, the worker **aborts immediately (`UNEXPECTED_PROMPT` / `DEVICE_ERROR`)** before sending any further command or `write memory`.
- **HA behaviour (Active/Standby Failover)**: Verified via `show version` (serial matched against `ha_pair_identity`, V209) + `show failover` (`This host: ... - Active`, mate `Standby Ready`) + `show failover state` (`Sync Done` / in-sync state). Commands run on the **Active** unit and replicate automatically to Standby. **`write standby` is strictly prohibited**.

### 3.4 FortiGate (FortiOS 7.4 — Single-VDOM and Multi-VDOM; `fortigate`)

| Phase | Candidate command literal (`canonical_command_key`) | Verification status | Shell / context / transport | Timeout | Official vendor source |
| --- | --- | --- | --- | --- | --- |
| **READ (central management & VDOM list)** | `get system status`, `get system central-management` (or `show system central-management`), `get system vdom-property` (or global VDOM list read) | `UNKNOWN` (verify exact central-mgmt and VDOM-list read literals per FortiOS build in `R0`) | `top_or_global` / `SSH_INTERACTIVE` | 30 s | FortiOS 7.4.7 CLI Reference (*`system central-management`*, *`system vdom-property`*) |
| **CONTEXT SWITCH (multi-VDOM)** | `config vdom` → `edit <VDOM_NAME>` … `end` (existing gate `fgt_context_moves`, V80; **issued only after pre-check proves `<VDOM_NAME>` already exists**) | `VERIFIED` (V80/V250) | `context` / `SSH_INTERACTIVE` | 30 s | FortiOS 7.4 Administration Guide (*VDOM Overview*, `FORTINET_CONTRACT.md`) |
| **READ (RIB & config)** | `get router info routing-table all` (existing gate `fgt_get_routing_table`) and `show router static` | `UNKNOWN` (`show router static` verified in `R0`) | `vdom` (or `top` on single-VDOM) / `SSH_INTERACTIVE` | 30 s | FortiOS 7.4.7 CLI Reference (*`get router info routing-table`*, *`config router static`*) |
| **ADD** | `config router static` → `edit 0` → `set dst <DEST_IP> <NETMASK>` → `set gateway <NEXTHOP_IP>` → `set device "<IF_NAME>"` → `set distance <DISTANCE>` → `set priority <PRIORITY>` → `set comment "<SAFE_COMMENT>"` → `next` → `end` (`edit 0` auto-assigns the next free sequence ID) | `UNKNOWN` (verify per FortiOS build in `R0`) | `vdom` (or `top`) / `SSH_INTERACTIVE` | 30 s | FortiOS 7.4.7 CLI Reference, *`config router static`* |
| **DELETE** | `config router static` → `delete <SEQ_NUM>` → `end` (`<SEQ_NUM>` resolved uniquely from fresh `show router static` in Stage B pre-check) | `UNKNOWN` (verify per FortiOS build in `R0`) | `vdom` (or `top`) / `SSH_INTERACTIVE` | 30 s | FortiOS 7.4.7 CLI Reference, *`config router static`* (`delete <seq-num>`) |
| **SAVE** | Auto-saved on `end` when `cfg-save` is `automatic` (verified via `show full-configuration system global`, existing gate `fgt_show_full_configuration_system_global`) | `VERIFIED` (V80) | `top_or_global` / `SSH_INTERACTIVE` | — | FortiOS 7.4.7 CLI Reference, *`config system global`* (`set cfg-save {automatic\|manual\|revert}`) |

- **Refuse if FortiManager-managed (`CENTRAL_MANAGEMENT_CONFLICT`)**: Stage B pre-check reads central-management state first; if the FortiGate is managed by FortiManager (`type fortimanager` / active FMG central management), the job refuses immediately (`CENTRAL_MANAGEMENT_CONFLICT`) before any VDOM entry or route write.
- **VDOM-existence check and prompt verification after `edit <VDOM_NAME>`**: Because `config vdom` → `edit <VDOM_NAME>` in FortiOS **creates a new VDOM** if `<VDOM_NAME>` does not exist, the worker **must**:
  1. Read the existing VDOM list on the device first and confirm `<VDOM_NAME>` is an already-existing VDOM (`ROUTING_CONTEXT_NOT_FOUND` if absent), and
  2. Immediately after `config vdom` → `edit <VDOM_NAME>`, verify that the CLI prompt matches the expected VDOM context prompt (`(<VDOM_NAME>) #`) and that no VDOM-creation message (`current vf=...`) was emitted before issuing any subsequent read or write (`UNEXPECTED_PROMPT` aborts immediately).
- **HA behaviour (FGCP Active-Passive)**: Fresh `get system status` + `get system ha status` + `diagnose sys ha checksum cluster` (V80 / V247) verify the session is on the `Primary` unit and the cluster is `in-sync`. FGCP automatically synchronizes `config router static` changes to the Secondary when `end` is executed. Immediately after `ADD`, `show router static` is re-read to record the allocated `<SEQ_NUM>` and `post_route_table_sha256` on the ledger.

### 3.5 Candidate network-device command gate summary (`docs/AI_DEVELOPMENT_PROTOCOL.md` fields 1–10)
All rows below are **`UNKNOWN` until verified per estate software version in Phase `R0`**, have **retry = `none`**, **max frequency = `once per member per approved job`**, **session reuse = `one session/API-key per member per job`**, **secret-output risk = `none`**, and **safe telemetry = `derived status, route-match boolean, route count, and route-table SHA-256 digest only; raw output discarded`**.

| Candidate `gate_id` | Vendor / `platform_role_scope` | Class (`ActionClass.id`) | Phase | Shell / transport | Timeout | Unsupported / error behavior |
| --- | --- | --- | --- | --- | --- | --- |
| `cp_route_show_route_static` | `check_point` / `cp_gaia_gateway` | `read` | `R0` | `expert_via_clish_c` / `SSH_EXEC` | 30 s | `clish -c "show route static"`; flags `logical`/`blackhole`/`reject`/`ping`/`rank`/`scopelocal` as `UNSUPPORTED_ROUTE_ATTRIBUTE`; Clish lock returns `GAIA_DB_LOCKED` |
| `cp_route_set_static_route_on` | `check_point` / `cp_gaia_gateway` | `configuration-write` | `R1`/`R2` | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Non-zero exit or Clish syntax/lock error aborts before `save config` |
| `cp_route_set_static_route_comment` | `check_point` / `cp_gaia_gateway` | `configuration-write` | `R1`/`R2` | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Optional; skipped when `deviceComment` is empty |
| `cp_route_set_static_route_nexthop_off` | `check_point` / `cp_gaia_gateway` | `configuration-write` | `R1`/`R2` | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Always `set static-route <DEST_CIDR> nexthop gateway address <NEXTHOP_IP> off` (never bare `... off`); error aborts before `save config` |
| `cp_route_save_config` | `check_point` / `cp_gaia_gateway` | `configuration-write` | `R1`/`R2` | `expert_via_clish_c` / `SSH_EXEC` | 60 s | Non-zero exit marks step `DEVICE_ERROR` and opens incident |
| `pan_route_show_vr_static_route` | `palo_alto` / `pan_firewall` | `read` | `R0` | `not_applicable` / `PAN_XML_API` | 30 s | `type=config&action=show` (running config, never `action=get`); missing VR returns `ROUTING_CONTEXT_NOT_FOUND` |
| `pan_route_check_pending_changes` | `palo_alto` / `pan_firewall` | `read` | `R0` | `not_applicable` / `PAN_XML_API` | 30 s | `<result>yes</result>` refuses unconditionally (`PENDING_CHANGES_PRESENT`) |
| `pan_route_set_vr_static_route` | `palo_alto` / `pan_firewall` | `configuration-write` | `R1`/`R2` | `not_applicable` / `PAN_XML_API` | 30 s | `status != "success"` triggers ledgered `pan_route_revert_config` and aborts without `commit` |
| `pan_route_delete_vr_static_route` | `palo_alto` / `pan_firewall` | `configuration-write` | `R1`/`R2` | `not_applicable` / `PAN_XML_API` | 30 s | `status != "success"` triggers ledgered `pan_route_revert_config` and aborts without `commit` |
| `pan_route_revert_config` | `palo_alto` / `pan_firewall` | `configuration-write` | `R1`/`R2` | `not_applicable` / `PAN_XML_API` | 30 s | Gated, ledgered (`CANDIDATE_REVERT`) revert of uncommitted candidate config on pre-commit abort |
| `pan_route_commit_partial_admin` | `palo_alto` / `pan_firewall` | `configuration-write` | `R1`/`R2` | `not_applicable` / `PAN_XML_API` | 120 s | `type=commit&action=partial` scoped to `<ADMIN_USER>`; missing `job` ID or `status="error"` fails closed |
| `pan_route_show_job_id` | `palo_alto` / `pan_firewall` | `read` | `R0` | `not_applicable` / `PAN_XML_API` | 30 s | Polled up to 120 s; `result != "OK"` after `FIN` marks `DEVICE_ERROR` |
| `asa_route_changeto_context` | `cisco_asa` / `cisco_asa_firewall` | `read` | `R0` | `context` / `SSH_INTERACTIVE` | 30 s | Issued only in `multiple` mode; any unexpected prompt aborts (`UNEXPECTED_PROMPT`) |
| `asa_route_show_running_route` | `cisco_asa` / `cisco_asa_firewall` | `read` | `R0` | `privileged_exec` / `SSH_INTERACTIVE` | 30 s | Empty output means zero static routes configured in this context; unexpected prompt aborts |
| `asa_route_add_static` | `cisco_asa` / `cisco_asa_firewall` | `configuration-write` | `R1`/`R2` | `config_mode` / `SSH_INTERACTIVE` | 30 s | Any unexpected prompt, `ERROR:`, or `WARNING:` line aborts before `write memory` |
| `asa_route_delete_static` | `cisco_asa` / `cisco_asa_firewall` | `configuration-write` | `R1`/`R2` | `config_mode` / `SSH_INTERACTIVE` | 30 s | Any unexpected prompt or `ERROR: Cannot remove route` aborts before `write memory` |
| `asa_route_write_memory` | `cisco_asa` / `cisco_asa_firewall` | `configuration-write` | `R1`/`R2` | `privileged_exec` / `SSH_INTERACTIVE` | 60 s | Requires `[OK]` confirmation and expected `#` prompt in response |
| `fgt_route_check_central_mgmt_and_vdoms` | `fortinet` / `fortigate` | `read` | `R0` | `top_or_global` / `SSH_INTERACTIVE` | 30 s | Refuses `CENTRAL_MANAGEMENT_CONFLICT` if FortiManager-managed; refuses `ROUTING_CONTEXT_NOT_FOUND` if `<VDOM_NAME>` does not exist |
| `fgt_route_show_router_static` | `fortinet` / `fortigate` | `read` | `R0` | `vdom` / `SSH_INTERACTIVE` | 30 s | Verifies VDOM prompt after `edit <VDOM_NAME>` first; parses `edit <seq-num>` blocks |
| `fgt_route_add_static_edit0` | `fortinet` / `fortigate` | `configuration-write` | `R1`/`R2` | `vdom` / `SSH_INTERACTIVE` | 30 s | Verifies VDOM prompt before write; `Command fail.` or prompt mismatch aborts and marks `DEVICE_ERROR` |
| `fgt_route_delete_static_seq` | `fortinet` / `fortigate` | `configuration-write` | `R1`/`R2` | `vdom` / `SSH_INTERACTIVE` | 30 s | Verifies VDOM prompt before write; `Command fail.` marks `DEVICE_ERROR` |

---

## 4. Pre-checks, hazards, post-checks, and rollback

### 4.1 Pre-checks (Stage A against stored inventory at request/preview; Stage B live on device before `mutation_possible = true`)
Every Stage B check must return **`PASS`** from the live device read in the job's own session; any `FAIL` or `UNKNOWN` stops the job with `mutation_possible = false` and zero writes sent:

| # | Check ID | Condition required for `PASS` | Refusal / stop code |
| --- | --- | --- | --- |
| 1 | `TARGET_REACHABLE_AND_READY` | Both env hard lock (`NEXUS_STATIC_ROUTE_MUTATION_ENABLED=true`) and DB kill switch (`ON`) active; SSH / XML-API session established; host key trusted (`requireTrusted`); privilege verified (`show curpriv` = 15 on ASA) and every CLI prompt matches expected state; no active backup, MDS export, policy collection, failover readiness, failover run, config-sync, restore, or route job on the unit **or its management domain**; on PAN `<check><pending-changes/></check>` is `no`; on FGT `cfg-save` is `automatic`. | `KILL_SWITCH_ACTIVE`, `TARGET_UNREACHABLE`, `TRUST_REQUIRED`, `UNEXPECTED_PROMPT`, `UNIT_OR_DOMAIN_BUSY`, `PENDING_CHANGES_PRESENT` |
| 2 | `IDENTITY_HA_AND_CENTRAL_MGMT_VERIFIED` | Live device serial equals recorded identity (`MATCH`). On CP: non-VSX (`gaia-default`), both ClusterXL members reachable and `Active`/`Standby`, and **not** in ClusterXL Cloning Group mode (`CLONING_GROUP_UNSUPPORTED`). On PAN: legacy `virtual-router` (not ARE `logical-router`), neither VR nor route has `src="tpl"` (`PANORAMA_TEMPLATE_MANAGED`), both HA members reachable, target is `active`, HA config `synchronized`. On ASA: serial in `ha_pair_identity`, target is `Active`, mate `Standby Ready`, `Sync Done`. On FGT: **not** FortiManager-managed (`CENTRAL_MANAGEMENT_CONFLICT`), `<VDOM_NAME>` verified to exist before `edit <VDOM_NAME>` and VDOM prompt verified after `edit <VDOM_NAME>`, target is `Primary`, cluster `in-sync`. | `IDENTITY_NOT_VERIFIED`, `HA_STATE_NOT_READY`, `HA_CONFIG_OUT_OF_SYNC`, `CLONING_GROUP_UNSUPPORTED`, `PANORAMA_TEMPLATE_MANAGED`, `CENTRAL_MANAGEMENT_CONFLICT`, `ROUTING_CONTEXT_UNSUPPORTED`, `ROUTING_CONTEXT_NOT_FOUND` |
| 3 | `DEFAULT_ROUTE_GUARD` | Destination prefix is **unconditionally not `0.0.0.0/0`** (`default`) for both `ADD` and `DELETE`. | `DEFAULT_ROUTE_FORBIDDEN` |
| 4 | `ROUTE_STATE_AND_ATTRIBUTE_PRECONDITION` | **ADD**: No static route with `(destination_cidr, nexthop_ip)` (or PAN `route_name`) exists in either the configured static-route table (`show route static` / PAN `action=show` / `show running-config route` / `show router static`) or the live RIB. **DELETE**: The exact static route `(destination_cidr, nexthop_ip, interface)` exists in the configured static-route table (for FortiGate resolves to **exactly one** `<seq-num>`; 0 matches → `ROUTE_NOT_FOUND`, >1 matches → `ROUTE_MATCH_AMBIGUOUS`; for PAN `src != "tpl"`). **Check Point attribute guard**: Target prefix does not carry `logical`, `blackhole`, `reject`, or `ping` next-hops or `rank`, `scopelocal`, or `ping` attributes (`UNSUPPORTED_ROUTE_ATTRIBUTE`). Records `pre_route_table_sha256`. | `ROUTE_ALREADY_EXISTS`, `ROUTE_NOT_FOUND`, `ROUTE_MATCH_AMBIGUOUS`, `UNSUPPORTED_ROUTE_ATTRIBUTE`, `PANORAMA_TEMPLATE_MANAGED` |
| 5 | `NEXTHOP_ON_CONNECTED_SUBNET` | `<NEXTHOP_IP>` lies strictly inside an operational (`UP`) directly `connected` IPv4 subnet in the same `routing_context`, does not equal the firewall's own local interface IP, network address, or broadcast address, and belongs to `<IF_NAME>` when `<IF_NAME>` is supplied. | `NEXTHOP_NOT_ON_CONNECTED_SUBNET`, `NEXTHOP_IS_LOCAL_INTERFACE_IP`, `NEXTHOP_INTERFACE_MISMATCH` |
| 6 | `CONTROL_PLANE_AND_OVERLAP_GUARD` | **(a) Control-plane best-path protection (enforced for BOTH `ADD` and `DELETE`)**: Evaluating Longest Prefix Match across the live RIB before and after the candidate `ADD` or `DELETE` must **never remove, shadow, or alter the active best path** to: (i) the firewall's management IP/subnet, (ii) the neXus service/worker address(es), (iii) the HA peer / control / sync addresses, (iv) the central management server(s) (Check Point Security Management / MDS, Panorama, FortiManager), or (v) configured log servers / syslog / SmartEvent / Panorama log collectors (`MANAGEMENT_LOCKOUT_RISK`). **(b) Connected subnet overlap**: `<DEST_CIDR>` does not equal or overlap a directly `connected` subnet in the same `routing_context` (`OVERLAPS_CONNECTED_SUBNET`). **(c) Specific overlap on ADD**: On `ADD`, no equal or more-specific route (`existing_prefix_len >= dest_prefix_len` where `existing_prefix` is a subset of `<DEST_CIDR>`) already exists in the live RIB (`OVERLAPS_EXISTING_OR_MORE_SPECIFIC_ROUTE`). **(d) Check Point anti-spoofing warning**: On Check Point, if any affected interface uses anti-spoofing topology *"defined by routes"* (or `UNKNOWN`), raises `CP_ANTISPOOFING_DEFINED_BY_ROUTES_WARNING` requiring explicit requester + approver confirmation. | `MANAGEMENT_LOCKOUT_RISK`, `OVERLAPS_CONNECTED_SUBNET`, `OVERLAPS_EXISTING_OR_MORE_SPECIFIC_ROUTE`, `CP_ANTISPOOFING_DEFINED_BY_ROUTES_WARNING` |

### 4.2 Post-checks (live on device after SAVE / COMMIT)
1. **`POST_CONFIG_AND_RIB_STATE` (blocking)**:
   - **ADD**: The static route `(destination_cidr, nexthop_ip)` is present in the running configured static-route read (`clish -c "show route static"` / PAN `type=config&action=show&xpath=.../static-route` / `show running-config route` / `show router static`) **and** installed in the live RIB (`ip -4 route show table all` on both CP members; PAN `<show><routing><route/></routing></show>` with `A S`; ASA `show route`; FGT `get router info routing-table all` with `S`).
   - **DELETE**: The static route is absent from both the running configured static-route table and live static RIB entries.
   - **Route-table digest**: Computes and records `post_route_table_sha256` on the request and dispatch ledger, verifying that **only** the single target route entry changed relative to `pre_route_table_sha256`.
2. **`POST_HA_CONTINUITY_AND_SYNC` (blocking on HA units)**:
   - HA roles have not flipped, and HA configuration sync is verified healthy:
     - Check Point: both members match `post_route_table_sha256` and remain `Active`/`Standby`.
     - Palo Alto: polls `<show><high-availability><state/></high-availability></show>` until running-config sync returns to `synchronized` within the bounded poll window.
     - Cisco ASA: `show failover state` confirms `Sync Done` (or in-sync state).
     - FortiGate: `diagnose sys ha checksum cluster` confirms `in-sync`.
3. **`POST_NEXTHOP_RESOLUTION` (informational, existing read gates only)**:
   - Checks next-hop FIB/ARP resolution via already-gated reads (CP `ip -4 route get <DST>` V187; PAN `<test><routing><fib-lookup>` V187; FGT `get system arp` V248; ASA `show route`). No active ICMP ping is issued.

### 4.3 Rollback (inverse command) — council-resolved boundaries and open PO decision `Q4b`
- **Inverse plan compilation**: Every request pre-computes its exact inverse command and `inverse_command_sha256_set` before approval:
  - For `ADD`, the inverse is `DELETE` of `(destination_cidr, nexthop_ip, interface, route_name / captured FGT <seq-num>)` + `SAVE/COMMIT`.
  - For `DELETE`, Stage B pre-check captures the full attributes of the existing route (`distance`, `metric`, `priority`, `comment`, `route_name`) before deleting it, so the inverse `ADD` + `SAVE/COMMIT` restores the identical route entry.
- **When rollback is NEVER automatic (constitutional invariant)**:
  - Per `AGENTS.md` ("No automatic (unscheduled-trigger, non-ledgered) network-device write/change operation is permitted") and `FAILOVER_EXECUTION_PLAN.md` §4, if any live write or commit ends in **`OUTCOME_UNKNOWN`** (timeout, dropped session) or if a single-target write/commit finishes with a failed post-check, the worker **STOPS**, records the state, opens a quarantine incident if uncertain, and **never** issues an automatic live inverse write.
- **Palo Alto pre-commit abort (gated, ledgered candidate revert)**:
  - If PAN `action=set` or `action=delete` fails or pre-commit check aborts *before* `type=commit&action=partial` is sent, live forwarding was never touched; the worker executes the gated, ledgered `pan_route_revert_config` (`phase = 'CANDIDATE_REVERT'`) to discard the uncommitted candidate change and verifies `<check><pending-changes/></check>` returns `no`.
- **Check Point ClusterXL split-member partial apply — Option RB-A selected by council**:
  - If the Standby member write+save completes (`REPLY_RECEIVED`) and the Active member aborts *before* its write is sent (`NOT_SENT_BEFORE_CONTACT`), the worker enforces **Option RB-A (strict manual stop)**:
    - Stop immediately with `state = 'STOPPED'`, `outcome_code = 'PARTIAL_CLUSTER_APPLY'`,
    - Open an operational incident naming the Standby member pseudonym as modified and the Active member pseudonym as unmodified, and
    - Surface a pre-populated **"Revert Standby Member"** rollback action in Operations. Automatic same-job Standby revert (former RB-B) is rejected.
- **Post-execution manual rollback within 60 minutes — Open PO Decision `Q4b` (PO-1 in §6.2)**:
  - Any `COMPLETED` or `PARTIAL_CLUSTER_APPLY` request exposes a **"Rollback route change"** action using the pre-compiled inverse plan. Two council positions are recorded for Product Owner decision (`Q4b` / PO-1):
    - **Council Position A (Pre-signed inverse by one `security_admin` within 60 minutes)**: Because the exact inverse command and its `inverse_command_sha256_set` were already reviewed and co-signed under four-eyes (`operation_admin` requester + `security_admin` approver) in the original `request_digest`, allow a single `security_admin` to execute that pre-signed inverse plan within **60 minutes** of completion (provided the current live route-table digest still equals `post_route_table_sha256`) to minimize outage MTTR. After 60 minutes, or if the route table has drifted, require a full new four-eyes request + approval cycle.
    - **Council Position B (Full four-eyes required for every rollback)**: Require a full four-eyes cycle (`operation_admin` requests rollback, distinct `security_admin` approves rollback, `requester != approver`, executor in `{requester, approver}`) even within 60 minutes, treating every rollback as an independent Class 3 configuration write.

---

## 5. Command & principal integrity, UI flow under Operations, and validation rules

### 5.1 Command compilation, injection defenses, and send-time integrity invariants
The browser submits typed JSON intent only; no CLI string, XPath, or shell fragment is ever accepted from the client (`AGENTS.md` "No Browser → device path"). The backend enforces six defense-in-depth invariants:
1. **Whole-string anchor regex validation (`\A...\z`)**: Every string identifier (`routingContext`, `interfaceName`, `routeName`, `deviceComment`, `changeTicketRef`) is matched with `\A...\z` (never `^...$`, which in multiline mode can pass a trailing `\n`).
2. **IPv4 addresses and prefixes rebuilt from integers**: `destinationPrefix` and `nextHopIp` are parsed into 32-bit unsigned integers and prefix length `M` (`1..32`), verified against network/mask invariants, and **rebuilt from integer octets** (`String.format(Locale.ROOT, "%d.%d.%d.%d/%d", ...)` and derived dotted netmask `%d.%d.%d.%d`). The raw client string is discarded before command compilation.
3. **PAN-OS XML built with an XML encoder**: All PAN-OS XPath key predicates and XML `<element>` / `<commit>` payloads are constructed through a streaming XML encoder (`javax.xml.stream.XMLStreamWriter` or strict XML element builder) with full attribute/character escaping — never string templates or concatenation.
4. **Dedicated `<ADMIN_USER>` validated from credential store**: The PAN-OS admin username injected into `<commit><partial><admin><member><ADMIN_USER></member></admin></partial></commit>` is read from the server-side credential store binding for that target (never from request parameters) and validated against `\A[A-Za-z0-9_.-]{1,31}\z`.
5. **Worker recompilation and approved command-hash set verification**:
   - The worker **never** executes pre-rendered command strings stored in a job payload or database column.
   - At execution time, the worker loads the structured columns of `static_route_change_request`, **recompiles** the exact per-step command/XML literals from canonical fields, recomputes `request_digest`, and computes `command_sha256 = SHA-256(compiled_command)` for every step.
   - Before any step is written to the ledger or sent to the transport, the worker verifies that the recomputed `request_digest` equals the approved `request_digest` **and** that `command_sha256` is present in `approved_command_sha256_set` (`COMMAND_HASH_NOT_IN_APPROVED_SET` aborts immediately with `mutation_possible = false`).
6. **Send-time principal, role, and expiry re-verification**: Immediately before crossing `mutation_possible = true`, the worker re-checks that the approval has not expired (`now() <= window_until`, `revoked_at IS NULL`), the DB kill switch and env hard lock are both active, and `requested_by` (`operation_admin`), `approved_by` (`security_admin`, `<> requested_by`), and `executed_by` (`IN (requested_by, approved_by)`) still hold their required active roles.

### 5.2 Placement in `OperationsScreen.tsx`
`OperationsScreen.tsx` (`TAB_SLUGS`) gains a fourth tab: **`"routes"`** (**Static routes**, URL `?screen=operations&tab=routes`), alongside `HA and readiness`, `Jobs`, and `Device health`. Under `aiview` (`role:replay_viewer`), the tab renders the masked request history and execution steppers using `TopologyNamePseudonymizer` (`FW-TANGO-04`, `CLS-ROMEO-01`) and synthetic prefixes (`192.0.2.0/24`), with all authoring/approval/execution/kill-switch buttons hidden and refused by the server.

### 5.3 Form fields and server-enforced validation rules
| Field | Required | Validation rule (enforced in frontend form and backend service with `\A...\z` anchors) |
| --- | --- | --- |
| `operation` | Yes | Closed enum: `ADD` \| `DELETE`. |
| `unitId` | Yes | Opaque UUID / `cluster_ref` of an enrolled, non-disabled firewall or HA cluster (`check_point`, `palo_alto`, `cisco_asa`, `fortinet`). |
| `routingContext` | Yes | Selected from stored inventory contexts for `unitId`: CP `gaia-default` only (`VSID` refused in Wave 1); PAN legacy `virtual-router` name (`\A[A-Za-z0-9_.-]{1,63}\z`, `src != "tpl"`, ARE `logical-router` refused); ASA `single` or context name (`\A[A-Za-z0-9_.-]{1,64}\z`); FGT existing VDOM name (`\A[A-Za-z0-9_.-]{1,31}\z`). |
| `destinationPrefix` | Yes | Canonical IPv4 CIDR `A.B.C.D/M` with `M` in `1..32`, parsed to 32-bit int + prefix length and rebuilt from integer octets. **`0.0.0.0/0` is unconditionally rejected (`DEFAULT_ROUTE_FORBIDDEN`)**. Host bits after mask must be `0` (e.g., `192.0.2.5/24` rejected; must be `192.0.2.0/24`). Rejects loopback (`127.0.0.0/8`), RFC 3927 link-local, Class D multicast, limited broadcast, and non-default unspecified (`0.0.0.0/1..32`). Server derives dotted netmask from integer mask for ASA/FGT. |
| `nextHopIp` | Yes | Unicast IPv4 `A.B.C.D`, parsed to 32-bit int and rebuilt from integer octets. Rejects `0.0.0.0`, loopback, link-local, multicast, broadcast, any local interface IP on `routingContext`, and any IP outside the connected UP subnets of `routingContext`. |
| `interfaceName` | ASA, FGT: **Yes**; PAN: **Optional**; CP: **Omitted** | Must match an existing `UP` interface in stored inventory for `(unitId, routingContext)` (`\A[A-Za-z0-9_./:-]{1,48}\z`) whose connected subnet contains `nextHopIp`. |
| `distance` | Optional | Integer administrative distance: PAN `1..255` (default `10`), ASA `1..255` (default `1`), FGT `1..255` (default `10`). Rejected if supplied for Check Point. |
| `metric` / `priority` | Optional | Integer: CP `priority` `1..8`; PAN `metric` `1..65535` (default `10`); FGT `priority` `1..65535` (default `1`); rejected on ASA. |
| `routeName` | PAN only | On `ADD`: optional `\A[A-Za-z0-9_.-]{1,31}\z` (defaults to deterministic `nxs-<yyyymmdd>-<hash6>`). On `DELETE`: resolved from the selected existing static route in running config (`src != "tpl"`). |
| `deviceComment` | Optional (CP, FGT) | `\A[A-Za-z0-9 _.:/-]{0,80}\z` (strict ASCII allowlist; quotes, `$`, `` ` ``, `\`, `;`, `<`, `>`, `&`, newlines, and control chars rejected). Disabled in UI for ASA and PAN. |
| `changeTicketRef` | Yes | Structured change-management ticket reference (`\A[A-Za-z0-9_.:/-]{3,64}\z`, stored write-once in `static_route_change_request.change_ticket_ref` and included in `operations_change` notifications; never sent to the device). |
| `reason` | Yes | Operational justification (`8..255` printable characters, no control chars, stored in `static_route_change_request.reason` and audit log; never sent to the device or included in email notifications). |

### 5.4 Operator workflow (Preview → Four-Eyes Approval → Execution Log → Result)
1. **Draft & Stage A Preview (`operation_admin`)**: The requesting `operation_admin` fills the form (including `changeTicketRef` and `reason`) and clicks **Preview & check**. The server runs Stage A pre-checks against stored inventory/config, compiles the exact per-member command plan and inverse rollback plan from integer-rebuilt IPv4 values, and returns:
   - Stage A pre-check table (`PASS` / `FAIL` / `UNKNOWN` per check in §4.1, including connected-subnet match, control-plane best-path check 6a for `ADD`/`DELETE`, Panorama `src="tpl"` check, and Check Point anti-spoofing warning if applicable),
   - **Exact Command Preview Card** (read-only monospace list of the exact compiled literals per target member, `gate_version`, `request_digest`, and `approved_command_sha256_set`),
   - **Submit for four-eyes approval** button (enabled only when all blocking Stage A checks pass).
2. **Approval Card (`security_admin`, `approved_by <> requested_by`)**: A `security_admin` (distinct from `requested_by`) opens the pending request in the **Pending approvals** queue, reviews the requester, `changeTicketRef`, `reason`, Stage A checks, exact command preview, and rollback plan, sets the approval window (`window_from` / `window_until`, max 24 h), and clicks **Approve** (or **Reject**).
3. **Interactive Execute & Business-Hours Check (`executed_by IN (requested_by, approved_by)`)**: Inside the approved `[window_from, window_until]` window, either the requesting `operation_admin` or the approving `security_admin` clicks **Execute route change** (confirming the `08:00–20:00 Europe/Istanbul` business-hours prompt if inside business hours; no `scheduled_for` timer exists).
4. **Live Execution Stepper, Ledger Drawer & Mail Notification**:
   - Stepper: `Queued` → `Live pre-checks` → `Writing route` → `Saving / committing` → `Live post-checks & HA sync` → `Completed` (or `Stopped — <code & phase>`).
   - **Execution Log Table**: One row per append-only ledger event showing member pseudonym, phase, `gate_id`, `gate_version`, `command_sha256`, delivery state (`IN_FLIGHT` / `REPLY_RECEIVED` / `OUTCOME_UNKNOWN`), `pre_route_table_sha256` / `post_route_table_sha256`, derived check status (`PASS` / `FAIL` / `UNKNOWN`), and timestamps. Zero raw CLI/XML output is stored or rendered.
   - **Terminal Notification & Follow-up**: On every terminal outcome, neXus sends a masked summary via the `operations_change` mail route and (on `COMPLETED`) enqueues a follow-up inventory/configuration collection job once the unit/domain write lock is released.

### 5.5 Proposed REST API routes and CLI parity (`nexus-cli`)
| Route / CLI subcommand | Action (`ActionRegistry`) | Purpose |
| --- | --- | --- |
| `POST /api/v2/operations/static-routes/preview` | `static_route_request` | Run Stage A pre-checks on stored inventory and return compiled command preview + `request_digest` (no DB write, no device contact; Phase `R0`) |
| `POST /api/v2/operations/static-routes` (`Idempotency-Key` required) | `static_route_request` | Create `PENDING_APPROVAL` request by `operation_admin`, bound to `change_ticket_ref`, `request_digest`, `approved_command_sha256_set`, and compiled inverse plan (`nexus-cli route-request ...`) |
| `POST /api/v2/operations/static-routes/{requestId}/approve` | `static_route_approve` | Record four-eyes approval by `security_admin` (`approved_by <> requested_by`) and approval window (`nexus-cli route-approve <requestId> ...`) |
| `POST /api/v2/operations/static-routes/{requestId}/execute` | `static_route_execute` | Admit immediate interactive worker job (`executed_by IN (requested_by, approved_by)`), enforcing `X-Nexus-Business-Hours-Ack` during `08:00–20:00 Europe/Istanbul` (`nexus-cli route-execute <requestId> [--business-hours-ack]`) |
| `POST /api/v2/operations/static-routes/{requestId}/rollback` | `static_route_rollback` | Create pre-populated inverse rollback request referencing `requestId` (`nexus-cli route-rollback <requestId>`) |
| `POST /api/v2/operations/static-routes/kill-switch` | `static_route_kill_switch_off` / `static_route_kill_switch_on` | Flip the DB kill switch `OFF` (any admin with reason) or `ON` (`security_admin` only with reason) |
| `GET /api/v2/operations/static-routes` & `GET .../{requestId}` | `static_route_read` | List requests and read execution stepper + append-only ledger rows (`aiview` receives masked projection only) |

---

## 6. Decision-council resolutions (2026-11-18) and remaining open Product Owner decisions

### 6.1 Questions resolved by the decision council (2026-11-18)
1. **`Q1a` — Action class (`[RESOLVED BY COUNCIL 2026-11-18]`)**:
   - Static-route write/save/commit/revert gate rows are classified as **`CLASS_3_CONFIGURATION_WRITE` (`configuration-write`)** (matching V178 `pan_failover_config_sync`), wrapped in the full Class 2 operational-safety envelope. Timed execution (`scheduled_for`) is removed. Rollout is phased as **`R0`** (read gates + command-plan preview, no write) → **`R1`** (one vendor, one pilot unit until `REAL_ENV_VALIDATED`) → **`R2`** (other vendors one at a time).
2. **`Q1b` — Role split, four-eyes rule, and constitutional amendment (`[RESOLVED BY COUNCIL 2026-11-18]`)**:
   - Before any implementation, a ratified PO decision record must amend `docs/AI_DEVELOPMENT_PROTOCOL.md` (lines ~210 and ~234–235) and `AGENTS.md` ("Network action taxonomy") and establish that for static-route actions **four-eyes separation of duties** (`operation_admin` requests, `security_admin` approves, `requester != approver`, and `executed_by` is recorded and must be either `requested_by` or `approved_by`) replaces the failover single-approver rule.
3. **`Q2a` & `Q2b` — Check Point literals, VSX, Cloning Group, anti-spoofing, and DELETE form (`[RESOLVED BY COUNCIL 2026-11-18]`)**:
   - Clish config read uses documented **`clish -c "show route static"`** (never `show static-route`).
   - Check Point `DELETE` issues **only** `clish -c 'set static-route <DEST_CIDR> nexthop gateway address <NEXTHOP_IP> off'`, **never** bare `set static-route <DEST_CIDR> off`, followed by `clish -c "save config"`.
   - Routes carrying `logical`, `blackhole`, `reject`, or `ping` next-hops or `rank`, `scopelocal`, or `ping` attributes are refused (`UNSUPPORTED_ROUTE_ATTRIBUTE`) (or must have those attributes captured and restored identically).
   - ClusterXL **Cloning Group** mode is refused (`CLONING_GROUP_UNSUPPORTED`); interfaces with anti-spoofing *"defined by routes"* trigger `CP_ANTISPOOFING_DEFINED_BY_ROUTES_WARNING`; VSX (`VS<VSID>`) is refused (`ROUTING_CONTEXT_UNSUPPORTED`); no `bash -lc` is used anywhere.
4. **`Q3a` & `Q3b` — Palo Alto template provenance, partial commit, `action=show`, candidate revert, and HA sync (`[RESOLVED BY COUNCIL 2026-11-18]`)**:
   - Refuse (`PANORAMA_TEMPLATE_MANAGED`) whenever either the `virtual-router` or the route has template provenance (`src="tpl"`).
   - Pre- and post-checks read running config with **`action=show`**, never `action=get` (candidate).
   - Require `<check><pending-changes/></check>` = `no` before candidate modification **and** commit via `type=commit&action=partial&cmd=<commit><partial><admin><member><ADMIN_USER></member></admin></partial></commit>` scoped to a dedicated neXus admin user.
   - Add a gated, ledgered candidate revert (`pan_route_revert_config`) for an aborted job before commit; post-check polls HA config sync until `synchronized`; refuse ARE `logical-router` (`ROUTING_CONTEXT_UNSUPPORTED`) until measured.
5. **`Q4a` — Check Point ClusterXL split-member partial apply (`[RESOLVED BY COUNCIL 2026-11-18]`)**:
   - **Option RB-A (strict manual stop)** is selected: if Standby succeeds and Active aborts before its write is sent, the worker stops immediately (`PARTIAL_CLUSTER_APPLY`), opens an incident, and offers a gated rollback action for Standby. Automatic same-job Standby revert (RB-B) is rejected.
6. **`Q5` — Default route (`0.0.0.0/0`) and control-plane path protection (`[RESOLVED BY COUNCIL 2026-11-18]`)**:
   - Default route `0.0.0.0/0` is **unconditionally forbidden (`DEFAULT_ROUTE_FORBIDDEN`)**; `allow_default_route` is dropped. Check 6a (`MANAGEMENT_LOCKOUT_RISK`) applies to **both `ADD` and `DELETE`** and prohibits removing or degrading the best path to management, neXus, the HA peer, MDS/Panorama/FortiManager, or log servers.
7. **`Q6a` & `Q6b` — Post-checks, change control, notifications, kill switch, and mutual exclusion (`[RESOLVED BY COUNCIL 2026-11-18]`)**:
   - Post-checks rely on running-config reads, live RIB reads, before/after route-table SHA-256 digests (`pre_route_table_sha256` / `post_route_table_sha256`), and HA sync polling (no new ICMP ping gate).
   - Every terminal outcome emits a masked notification on the new **`operations_change`** mail route (masked pseudonyms only; no prefixes, next-hops, comments, reasons, or raw output).
   - DB kill switch (`OFF` by any admin with reason, `ON` only by `security_admin`) + env hard lock; one active write per device and per management domain, enforced from both sides against backup, MDS export, policy collection, readiness, and failover.

### 6.2 Remaining open Product Owner decisions (required before contract freeze)
Only the following five true Product Owner / estate decisions remain open:

1. **PO-1 (`Q4b`) — Emergency post-execution rollback approval within 60 minutes**:
   - **Council Position A (Single `security_admin` within 60 minutes using pre-signed inverse plan)**: Because the exact inverse command and its `inverse_command_sha256_set` were already co-signed by `operation_admin` + `security_admin` in the original four-eyes `request_digest`, allow a single `security_admin` to trigger that pre-signed inverse rollback within **60 minutes** of `COMPLETED` / `PARTIAL_CLUSTER_APPLY` (provided the live route-table digest matches `post_route_table_sha256`) for rapid incident recovery; require full four-eyes after 60 minutes.
   - **Council Position B (Full four-eyes for every rollback)**: Require a new four-eyes request + approval (`operation_admin` requests, `security_admin` approves, `requester != approver`, executor in `{requester, approver}`) for every rollback regardless of elapsed time.
2. **PO-2 — Count of `security_admin` (and `operation_admin`) identities in the estate roster**:
   - How many distinct human principals hold `operation_admin` and `security_admin` in Active Directory today, and does the roster guarantee at least one `operation_admin` and one distinct `security_admin` across shifts without shared accounts (or is a break-glass policy required when only one `security_admin` is on duty)?
3. **PO-3 — Bank audit retention period for the route-change ledger**:
   - What mandatory retention period (e.g., 5 years vs. 10 years) applies to `static_route_change_request` and the append-only `static_route_dispatch_ledger` under the bank's regulatory and internal-audit retention policy?
4. **PO-4 — FortiManager central-management scope across the FortiGate estate**:
   - Are all enrolled FortiGate units managed by FortiManager (which Wave 1 refuses with `CENTRAL_MANAGEMENT_CONFLICT`), or are there standalone / non-FortiManager FortiGate units in scope for Phase `R2` after `R0` central-management measurement?
5. **PO-5 (`Q3c`) — Palo Alto Advanced Routing Engine (`ARE` / `logical-router`) presence**:
   - Do any of the 34 estate Palo Alto firewalls run the PAN-OS Advanced Routing Engine (`logical-router`, refused as `ROUTING_CONTEXT_UNSUPPORTED` in Wave 1), or are all 34 firewalls using legacy `virtual-router`?

## Appendix -- Path confirmation (PO 2026-11-18)

PO: "confirm the route took effect: before the change, look at which route the traffic uses; after the route is added, look at the new one."

- For every ADD and DELETE, the job runs a path lookup for a representative host inside the destination prefix (default: the first usable
  address; the requester may name another inside the prefix) **before** the write and **after** the save/commit (and, on clusters, on the
  member that forwards traffic).
- Recorded each time: selected prefix, next hop, egress interface, source protocol/route type. Before/after digests go to the ledger,
  the result page and the `operations_change` mail.
- Verdict: `PATH_CONFIRMED` (ADD: the new static route is the selected route; DELETE: the deleted route is no longer selected),
  `PATH_UNCHANGED` (the route is present/absent in the table but the selection did not change, e.g. a more specific or lower-distance route
  wins), `UNKNOWN` (lookup unavailable or unparsed). `PATH_UNCHANGED` and `UNKNOWN` are post-check failures: the job stops, mails, and
  offers the one-click rollback (RB-A); it never rolls back on its own.
- Candidate read literals (NEW reads, UNKNOWN until verified on each estate version, each needs the PO's literal approval; no `bash -lc`):
  Check Point `show route destination <host>` (Gaia clish; to be confirmed) or `ip route get <host>` in the non-login shell form;
  Palo Alto `<test><routing><fib-lookup><virtual-router>VR</virtual-router><ip>HOST</ip></fib-lookup></routing></test>`;
  Cisco ASA `show route <host>` (in the context); FortiGate `get router info routing-table details <host>` (inside the VDOM).

## Appendix -- PO answers (chat 2026-11-18, ~01:20)

- **Rollback approval (Q4b): single approver.** One `security_admin` may run the pre-signed inverse of a completed change (its digest is part of
  the original approval) within 60 minutes of completion, recorded as `ROLLBACK` with executor and reason and mailed; after 60 minutes a rollback
  is a new four-eyes request. (PO: "Geri alma tek kişi".)
- **security_admin count:** the PO did not give a number ("salla"). Assumption for design: at least two named `security_admin` identities exist so
  four-eyes can be met; if only one exists at rollout, four-eyes blocks every change -- verify the real count before R1 and raise it then.
- **Log retention:** ledgers and their mail/audit evidence are kept with no deletion path (append-only, no cascade on device delete); no time limit
  set by neXus. (PO: "log saklama devam eder".)
- **FortiManager: the FortiGates are FortiManager-managed.** (PO: "fortimanager evet".) Consequence: a local route write on a FortiGate would be
  overwritten by the next FortiManager install, so FortiGate stays out of R1/R2 for local writes (refused `FORTIMANAGER_MANAGED`); a FortiGate
  route change, if wanted, is a separate design through FortiManager (device-level static route in the FMG device database plus install), not part
  of this contract.
