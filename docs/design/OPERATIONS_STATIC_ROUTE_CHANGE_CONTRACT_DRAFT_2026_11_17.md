# Operations — static route add and delete on firewalls (design contract)

## Status

**DRAFT -- DO NOT IMPLEMENT.** Written 2026-11-17 against `origin/main` (`5e15072a`). No device or host was contacted while writing this document. Every command literal in §3 is a candidate proposal only; no command, database migration, `ActionRegistry` entry, or `gate_registry` row is approved. Implementation requires: (1) Product Owner resolution of the open questions in §6, (2) a multi-model decision-council review of the mutation boundary (`GOV_PO_ROLE_MIGRATION.md` §7 trigger (b)), (3) a ratifying PO decision record for the `AGENTS.md` / `docs/AI_DEVELOPMENT_PROTOCOL.md` routing-write boundary, and (4) the Product Owner's literal-by-literal command-gate sign-off.

---

## 1. Scope and boundaries

### 1.1 In scope (Wave 1)
A job under **Operations › Static routes** that adds or deletes **exactly one IPv4 static route per job** (one destination IPv4 prefix and one next-hop IPv4 gateway address, plus egress interface and administrative distance/metric/priority where supported) on an enrolled firewall or HA cluster:

| Vendor | Target unit in neXus | Routing context (`routing_context`) | Management / HA reach model |
| --- | --- | --- | --- |
| **Check Point** | Standalone Gaia gateway, ClusterXL HA cluster (both members enrolled), or VSX Virtual System (`cluster + VSID`) | Non-VSX: `gaia-default` (VS0). VSX: physical `VS0` or one Virtual System `VS<VSID>` (see §3.1 VSX authority constraint and Q2) | SSH (`SSH_EXEC` / `SSH_INTERACTIVE`), Expert landing invoking Gaia Clish via `clish -c` (or direct Clish landing where detected). ClusterXL does **not** sync Gaia OS static routes across members (`MEMBER_SPECIFIC` per `AGENTS.md`); jobs targeting a ClusterXL cluster execute on both members sequentially (Standby first, then Active) |
| **Palo Alto Networks** | Standalone PAN-OS firewall or active-passive HA pair (direct firewall HTTPS XML API) | Named `virtual-router` (legacy engine) or `logical-router` VRF `default` (Advanced Routing Engine, `PATH_ANALYSIS_VENDOR_SEMANTICS.md` §5a); Panorama template / stack provenance checked per §3.2 and Q3 | Direct firewall `PAN_XML_API` (`POST /api/`). On an active-passive HA pair, the write and commit target the verified **Active** member only; HA running-config sync replicates to the passive member |
| **Cisco ASA** | Standalone routed-mode ASA or Active/Standby failover pair, single-context or multi-context | `single` (default context) or named security context via `changeto context <CTX_NAME>` (`A0-ASA-19`) | `SSH_INTERACTIVE` privilege-15 session (`show curpriv` = 15, space-paged without `terminal pager 0`, V233). On a failover pair, commands run on the verified **Active** unit (matched by serial in `ha_pair_identity`, V209) and replicate automatically to Standby |
| **Fortinet FortiGate** | Standalone FortiGate or 2-member FGCP active-passive cluster, single-VDOM or multi-VDOM | `root` (single-VDOM) or named VDOM via `config vdom` → `edit <VDOM_NAME>` (`FORTINET_CONTRACT.md`) | `SSH_INTERACTIVE` session to the standalone unit or verified FGCP **Primary**. FGCP automatically synchronizes `config router static` changes to the Secondary upon `end` |

### 1.2 Out of scope (deferred to later waves)
- **Dynamic routing**: BGP, OSPF, RIP, EIGRP, PIM/multicast, route redistribution, prefix lists, route maps, BFD.
- **Policy-based routing (PBR)**: Check Point PBR rules, PAN PBF rules, ASA policy-route maps, FortiGate `config router policy`.
- **IPv6 static routes**: Gaia `set ipv6 static-route`, PAN `routing-table/ipv6/static-route`, ASA `ipv6 route`, FortiGate `config router static6` (Wave 2 candidate once IPv4 is `REAL_ENV_VALIDATED`).
- **Special route types**: Blackhole / reject / discard / `null0`, next-VR (`next-vr`), VTI/tunnel-only unnumbered routes without a next-hop IP, SLA/object-tracked routes (`track <id>` / `ping on`), SD-WAN member/zone virtual routes (`set sdwan-zone`).
- **Batch multi-route edits**: Strictly one prefix + one next-hop add or delete per job.
- **Cisco FTD (FMC REST)** and **FortiManager ADOM script pushes** (separate transport/authority models).

---

## 2. Action class, RBAC, four-eyes approval, job windows, ledger, and idempotency

### 2.1 Action class proposal (`CLASS 2` vs `CLASS 1` / `CLASS 3`)
- **Why not `CLASS_1_RECOVERY_WRITE` (`recovery-write`)**: In `ActionClass.java` and `RECOVERY_OPERATIONAL_WRITE_LEDGER.md`, Class 1 is reserved for bounded-resource backup and support-bundle archive lifecycle writes (`add backup local`, `delete backup`, `asa_backup_archive`, `cp_sb_*`) that never modify packet forwarding or committed device configuration (`PO_DECISION_RECORD_2026_09_14K_A_BACKUP_IS_NOT_A_RECOVERY_WRITE.md`).
- **Why `CLASS 2` operational controls + `CLASS_3_CONFIGURATION_WRITE` (or `CLASS_2_OPERATIONAL_STATE_CHANGE`) enum binding**:
  - Adding or deleting a static route mutates the live forwarding table (RIB/FIB) immediately **and** persists across reboots via `save config` / `commit` / `write memory` / FortiOS `end`.
  - **Recommendation**:
    1. Apply the full **Class 2 (`FAILOVER_EXECUTION_PLAN.md`) operational-safety envelope**: four-eyes approval, fresh Stage B live pre-checks immediately before the write, durable pre-send dispatch recording (`mutation_possible = true`), zero automatic retries, and durable target quarantine on `OUTCOME_UNKNOWN`.
    2. In `ActionClass.java` and `gate_registry`, classify the write/save/commit gate rows as **`CLASS_3_CONFIGURATION_WRITE` (`configuration-write`)** — consistent with `pan_failover_config_sync` (V178, `PO_DECISION_RECORD_2026_10_24_PAN_CONFIG_SYNC.md` §4), which is the existing precedent for a persistent configuration write on `ActionClass.java` — **or** as **`CLASS_2_OPERATIONAL_STATE_CHANGE` (`operational-state-change`)** if the PO prefers grouping all Operations-tab forwarding mutations under Class 2 (see Open Question Q1).
    3. Exempt only the exact approved `gate_id` + `canonical_command_key` pairs in `GateRow.violatesWriteMarkerDenylist`, gated behind a dedicated runtime kill-switch `NEXUS_STATIC_ROUTE_MUTATION_ENABLED` (default `false`).

### 2.2 Roles allowed (`ActionRegistry.java`)
| Action ID | Console submittable | Allowed roles (`RoleToken`) | `aiview` (`replay_viewer`) rule |
| --- | --- | --- | --- |
| `static_route_read` | `true` | `security_admin`, `operation_admin`, `operator`, `viewer`, `replay_viewer` | Masked projection only (`FW-TANGO-04`, `CLS-ROMEO-01`, masked prefixes/next-hops `192.0.2.x`); actor fingerprints, ticket reasons, and comments withheld |
| `static_route_request` | `true` | `security_admin` (see Q1 for `operation_admin`) | Never (`403 WRONG_ROLE`, even if the session also holds an admin token) |
| `static_route_approve` | `true` | `security_admin` | Never (`403 WRONG_ROLE`) |
| `static_route_execute` | `true` | `security_admin` (see Q1 for `operation_admin`) | Never (`403 WRONG_ROLE`) |
| `static_route_rollback` | `true` | `security_admin` | Never (`403 WRONG_ROLE`) |

### 2.3 Four-eyes approval (`requester != approver`)
1. **Two distinct authenticated principals**: A route change cannot be approved by the principal that requested it. Both `requested_by` and `approved_by` are populated exclusively from the server-side authenticated session principal (`NFKC`-normalized `actor_fingerprint`, never from a request-body field; `FAILOVER_EXECUTION_PLAN.md` FR-P0.7). Database constraint: `CHECK (approved_by IS NULL OR approved_by <> requested_by)`.
2. **Immutable request-digest binding**: At request creation, the service computes `request_digest = SHA-256(canonical_json(unit_id, member_device_ids, vendor, routing_context, operation, destination_cidr, nexthop_ip, interface_name, distance, metric, priority, route_name, device_comment, compiled_steps))`. The approval record binds to `(request_id, revision, request_digest, window_from, window_until)`.
3. **Window and expiry**: An unapproved request expires after **60 minutes**. An approval grants an execution window (`window_from` to `window_until`, max **24 hours**). The worker re-verifies immediately before crossing `mutation_possible` that the approval is not revoked, `now()` is within `[window_from, window_until]`, and the recomputed `request_digest` equals the approved digest.

### 2.4 Business-hours confirmation and job windows
Following `PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md` (V168) and `PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md` §3:
- **6-hour polling windows (`00:00 / 06:00 / 12:00 / 18:00 Europe/Istanbul`)**: Manual executions by `security_admin` carry `ManualAdmission.Grant` (`window_bypass_role = 'security_admin'`) and are claimable immediately outside the 6-hour collection windows. If the operator schedules the job for a future timestamp (`scheduled_for`), `scheduled_for` must fall inside the approved `[window_from, window_until]` range.
- **Business-hours guard (`08:00–20:00 Europe/Istanbul`, every day)**:
  - Submitting an immediate execution (or an approval that starts execution immediately) inside `08:00–20:00 Europe/Istanbul` without `X-Nexus-Business-Hours-Ack: true` (or `?business_hours_ack=true`) is refused with `409 BUSINESS_HOURS_CONFIRM_REQUIRED` before any job row is admitted.
  - **Pre-write recheck (`mutationAdmission`)**: If a request was submitted before `08:00` without business-hours acknowledgement and reaches the worker's pre-write fence at or after `08:00`, the worker aborts before the first write (`BUSINESS_HOURS_CONFIRM_REQUIRED`).
  - **Mid-flight completion exception (V168 §2.5 parity)**: Once `mutation_possible = true` has been committed (e.g., Check Point Standby member written, or route written and `save config` / `commit` / `write memory` next), the second member write and the save/commit step are **never** refused by the `08:00` boundary, preventing a half-applied or unsaved route.

### 2.5 Ledger before and after (durable mutation boundary)
Modeled on `PO_DECISION_RECORD_2026_09_22_SCHEDULED_DEVICE_WRITES_FROM_SCRIPTS.md` condition 3, `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §5, and `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3:
- **Tables**: `static_route_change_request` (one row per request, audited by `fn_audit_capture`) and `static_route_dispatch_ledger` (append-only — `REVOKE UPDATE, DELETE` from `ui2_app` or guarded state-transition trigger).
- **Before every write/save command**: Inside an audited transaction under a per-unit database advisory lock, the worker verifies lease ownership (`job_id`, `epoch`), sets `mutation_possible = true` on the request, and inserts a ledger entry recording: `request_id`, `job_id`, `step_no`, `phase` (`WRITE` | `SAVE` | `ROLLBACK_WRITE` | `ROLLBACK_SAVE`), `target_device_id` (opaque UUID), `vendor`, `routing_context`, `gate_id`, `command_sha256`, `requested_by`, `approved_by`, `credential_ref`, `delivery = 'IN_FLIGHT'`, and `prepared_at`.
- **Fail-closed on unreadable/unwritable ledger**: If the before-send ledger insert fails, the worker **must not** issue the command to the transport (`OperationalLedgerUnreadableError` equivalent).
- **After every write/save command**: The worker records `completed_at`, `delivery` (`REPLY_RECEIVED`, `TRANSPORT_TIMEOUT`, `SESSION_DROPPED`, `NOT_SENT_BEFORE_CONTACT`), `device_outcome` (`OK`, `DEVICE_ERROR`, `UNKNOWN`), and derived post-check status.
- **Quarantine on uncertainty**: Any `TRANSPORT_TIMEOUT` or `SESSION_DROPPED` after `IN_FLIGHT` sets the job and request to `OUTCOME_UNKNOWN` and opens a unit quarantine incident that blocks any subsequent route change or failover on that unit until a `security_admin` reconciles it via `JOB_RECONCILE`.

```sql
-- Proposed schema sketch (DRAFT -- DO NOT IMPLEMENT; requires BEGIN/ROLLBACK dry run before any rollout)
CREATE TABLE static_route_change_request (
    request_id                  UUID PRIMARY KEY,
    idempotency_key             UUID NOT NULL UNIQUE,
    unit_id                     TEXT NOT NULL,
    vendor                      TEXT NOT NULL CHECK (vendor IN ('check_point', 'palo_alto', 'cisco_asa', 'fortinet')),
    routing_context             TEXT NOT NULL,
    operation                   TEXT NOT NULL CHECK (operation IN ('ADD', 'DELETE')),
    destination_cidr            TEXT NOT NULL,
    nexthop_ip                  TEXT NOT NULL,
    interface_name              TEXT,
    distance                    INTEGER,
    metric                      INTEGER,
    priority                    INTEGER,
    route_name                  TEXT,
    device_comment              TEXT,
    reason                      TEXT NOT NULL CHECK (char_length(btrim(reason)) >= 8),
    allow_default_route         BOOLEAN NOT NULL DEFAULT FALSE,
    request_digest              TEXT NOT NULL,
    inverse_plan_json           JSONB NOT NULL,
    requested_by                TEXT NOT NULL,
    requested_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by                 TEXT,
    approved_at                 TIMESTAMPTZ,
    window_from                 TIMESTAMPTZ,
    window_until                TIMESTAMPTZ,
    revoked_by                  TEXT,
    revoked_at                  TIMESTAMPTZ,
    job_id                      UUID,
    state                       TEXT NOT NULL CHECK (state IN (
        'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'EXPIRED', 'QUEUED',
        'PRECHECK', 'EXECUTING', 'SAVING', 'POSTCHECK', 'ROLLING_BACK',
        'COMPLETED', 'STOPPED', 'OUTCOME_UNKNOWN'
    )),
    mutation_possible           BOOLEAN NOT NULL DEFAULT FALSE,
    outcome_code                TEXT,
    submitted_in_business_hours BOOLEAN,
    business_hours_ack_by       TEXT,
    business_hours_ack_at       TIMESTAMPTZ,
    CONSTRAINT static_route_four_eyes_chk CHECK (approved_by IS NULL OR approved_by <> requested_by)
);

CREATE TABLE static_route_dispatch_ledger (
    ledger_id        BIGSERIAL PRIMARY KEY,
    request_id       UUID NOT NULL REFERENCES static_route_change_request(request_id),
    job_id           UUID NOT NULL,
    step_no          INTEGER NOT NULL,
    phase            TEXT NOT NULL CHECK (phase IN ('WRITE', 'SAVE', 'ROLLBACK_WRITE', 'ROLLBACK_SAVE')),
    target_device_id UUID NOT NULL,
    vendor           TEXT NOT NULL,
    routing_context  TEXT NOT NULL,
    gate_id          TEXT NOT NULL,
    command_sha256   TEXT NOT NULL,
    requested_by     TEXT NOT NULL,
    approved_by      TEXT NOT NULL,
    credential_ref   TEXT NOT NULL,
    delivery         TEXT NOT NULL CHECK (delivery IN (
        'IN_FLIGHT', 'REPLY_RECEIVED', 'TRANSPORT_TIMEOUT', 'SESSION_DROPPED', 'NOT_SENT_BEFORE_CONTACT'
    )),
    device_outcome   TEXT NOT NULL CHECK (device_outcome IN ('PENDING', 'OK', 'DEVICE_ERROR', 'UNKNOWN')),
    prepared_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    UNIQUE (request_id, step_no)
);
-- Privileges: GRANT SELECT, INSERT, UPDATE ON static_route_change_request TO ui2_app;
-- Append-only ledger: GRANT SELECT, INSERT ON static_route_dispatch_ledger TO ui2_app; (only delivery/device_outcome/completed_at transition via SECURITY DEFINER function or second append row)
```

### 2.6 Idempotency key and concurrency fence
- **Client idempotency header**: `POST /api/v2/operations/static-routes` requires `Idempotency-Key: <UUIDv4>`. Retrying the same key with the same payload returns the existing request record (`200`/`201`); reusing the key with a different payload returns `409 IDEMPOTENCY_KEY_PAYLOAD_MISMATCH`.
- **Natural active-prefix fence**: A partial unique index on `static_route_change_request (unit_id, routing_context, destination_cidr)` where `state IN ('PENDING_APPROVAL', 'APPROVED', 'QUEUED', 'PRECHECK', 'EXECUTING', 'SAVING', 'POSTCHECK', 'ROLLING_BACK', 'OUTCOME_UNKNOWN')` guarantees at most one active or unresolved change per `(unit_id, routing_context, destination_cidr)`.
- **Endpoint lease**: Uses the exclusive per-endpoint `CONFIGURATION_WRITE` lease purpose (V178, `PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md` §3.4) so a route change never collides with another write, backup, or failover on the same device.

---

## 3. Per-vendor candidate command literals, HA behaviour, and save/commit

All candidate rows have **retry = `none`**, **max frequency = `once per member per approved job`**, **session reuse = `one session/API-key per member per job`**, **secret-output risk = `none`** (parsers extract only derived status/counts/match booleans in memory and discard raw text per `AGENTS.md` Raw-evidence law). Placeholders (`<DEST_CIDR>`, `<DEST_IP>`, `<NETMASK>`, `<NEXTHOP_IP>`, `<IF_NAME>`, `<VR>`, `<LR>`, `<VSID>`, `<CTX_NAME>`, `<VDOM_NAME>`, `<SEQ_NUM>`, `<ROUTE_NAME>`, `<PRIORITY>`, `<DISTANCE>`, `<METRIC>`, `<SAFE_COMMENT>`) are validated server-side against §5.1 rules before command assembly; free text never reaches a device.

### 3.1 Check Point (Gaia Clish — Standalone, ClusterXL Members, VSX)

| Phase | Candidate command literal (`canonical_command_key`) | Shell / context / transport | Timeout | Official vendor source |
| --- | --- | --- | --- | --- |
| **READ (RIB)** | `ip -4 route show table all` (existing gate `cp_inventory_ip_route_show_table_all`); VSX VS: `bash -lc 'vsenv <VSID> && ip -4 route show table all'` (existing gate `cp_inventory_vsid_ip_route_show_table_all`, V187) | `expert` / `SSH_EXEC` | 30 s | Linux iproute2 (`CP_INVENTORY_COMMAND_GATE_ENTRIES.md` entries 3, 23; Check Point does not document `ip route` shape) |
| **READ (Clish config)** | `clish -c "show static-route"` (or `clish -c "show route destination <DEST_CIDR>"`) | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Check Point R81.20 & R82 Gaia Administration Guide, *Routing › Configuring Static Routes (IPv4)* (`show static-route`) |
| **ADD** | `clish -c 'set static-route <DEST_CIDR> nexthop gateway address <NEXTHOP_IP> on'` (with optional priority `1..8`: `... address <NEXTHOP_IP> priority <PRIORITY> on`) | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Check Point R81.20 & R82 Gaia Admin Guide (`set static-route <Dest> nexthop gateway address <GW> [priority <1-8>] {on\|off}`) |
| **ADD (comment, optional)** | `clish -c 'set static-route <DEST_CIDR> comment "<SAFE_COMMENT>"'` | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Check Point R81.20 & R82 Gaia Admin Guide (`set static-route <Dest> comment {"<Text>"\|off}`) |
| **DELETE** | `clish -c 'set static-route <DEST_CIDR> nexthop gateway address <NEXTHOP_IP> off'` (removes that specific next-hop; see Q2c on whether `set static-route <DEST_CIDR> off` is also needed) | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Check Point R81.20 & R82 Gaia Admin Guide (`set static-route ... nexthop gateway address <GW> off`) |
| **SAVE** | `clish -c "save config"` | `expert_via_clish_c` / `SSH_EXEC` | 60 s | Check Point R81.20 & R82 Gaia Admin Guide, *Configuration Lock and Saving Configuration* (`save config`) |

- **HA behaviour (ClusterXL)**: Gaia static routes are member-local (`MEMBER_SPECIFIC`). For a ClusterXL cluster, the worker verifies via fresh `cphaprob stat` that the two members are `Active` and `Standby`, then executes `ADD`/`DELETE` + `save config` + post-check on the **Standby** member first. Only after the Standby member passes post-checks does the worker execute `ADD`/`DELETE` + `save config` + post-check on the **Active** member.
- **Database lock handling**: If `clish -c` returns a Gaia database-locked error (`Database is locked`), the job fails closed (`GAIA_DB_LOCKED`) before mutation; it does **not** issue `lock database override` automatically.
- **VSX Virtual System (`VS<VSID>`) constraint (`UNKNOWN` / PO decision Q2)**:
  1. Per Check Point R81.20/R82 VSX Administration Guide (*Configuring Static Routes for Virtual Devices*), static routes for Virtual Systems are centrally stored in the Security Management database (configured via SmartConsole or `vsx_provisioning_tool -s localhost ... -o add route vd <VS> destination <DEST_CIDR> next_hop <NEXTHOP_IP>`), unless VSX Direct Routing / local Clish routing is used.
  2. Per neXus's hardware measurement on 2026-09-14 (`CP_CONFIGURATION_MEASUREMENT_FINDINGS_2026_09_14.md` §3, `PO_DECISION_RECORD_2026_09_14G` CG-1), running `clish -c` inside `vsenv <VSID>` still accesses the host (`VS0`) Gaia database, and piping `set virtual-system <VSID>` into non-interactive `clish` on stdin produced no output (`only vsxid <N>`). Therefore, a per-VS static route via Clish requires either an interactive Clish session (`set virtual-system <VSID>` → `set static-route ...` → `save config`) on both VSX members or `vsx_provisioning_tool` on the management server, and whether interactive Clish `set static-route` survives a subsequent VSX topology push on this estate is **`UNKNOWN`** until measured in the lab (Q2).

### 3.2 Palo Alto Networks (Direct Firewall XML API — Virtual Router / Logical Router)

| Phase | Candidate request literal (`canonical_command_key`) | Shell / context / transport | Timeout | Official vendor source |
| --- | --- | --- | --- | --- |
| **READ (RIB)** | `type=op&cmd=<show><routing><route/></routing></show>` (existing gate `pan_inventory_show_routing_route`) | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS XML API Operational Command Reference; `PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md` entry 4 |
| **READ (config VR)** | `type=config&action=get&xpath=/config/devices/entry[@name='localhost.localdomain']/network/virtual-router/entry[@name='<VR>']/routing-table/ip/static-route` | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS XML API Configuration Reference (`type=config&action=get`); PAN-OS Networking Admin Guide (*Static Routes*) |
| **READ (config ARE LR)** | `type=config&action=get&xpath=/config/devices/entry[@name='localhost.localdomain']/network/logical-router/entry[@name='<LR>']/vrf/entry[@name='default']/routing-table/ip/static-route` | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS 10.2+/11.1 Advanced Routing Engine docs (`PA-ARE`, `PA-LR`); exact XML XPath under ARE is **`UNKNOWN`** until measured on an ARE firewall |
| **READ (pending changes)** | `type=op&cmd=<check><pending-changes/></check>` | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS XML API Operational Command Reference (`check pending-changes`) |
| **ADD (VR)** | `type=config&action=set&xpath=/config/devices/entry[@name='localhost.localdomain']/network/virtual-router/entry[@name='<VR>']/routing-table/ip/static-route/entry[@name='<ROUTE_NAME>']&element=<destination><DEST_CIDR></destination><nexthop><ip-address><NEXTHOP_IP></ip-address></nexthop><interface><IF_NAME></interface><metric><METRIC></metric><admin-dist><DISTANCE></admin-dist>` (optional leaves included only when set) | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS XML API Configuration Reference (`type=config&action=set`); PAN-OS XPath schema for `virtual-router/.../static-route`. Whether a `<description>` leaf exists under `static-route/entry` across PAN-OS 10.x–12.x is **`UNKNOWN`** |
| **DELETE (VR)** | `type=config&action=delete&xpath=/config/devices/entry[@name='localhost.localdomain']/network/virtual-router/entry[@name='<VR>']/routing-table/ip/static-route/entry[@name='<ROUTE_NAME>']` | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS XML API Configuration Reference (`type=config&action=delete`) |
| **COMMIT** | `type=commit&cmd=<commit><partial><admin><member><ADMIN_USER></member></admin></partial></commit>` (or full `<commit></commit>` only if `<check><pending-changes/></check>` returned `no`; see Q3b) | `not_applicable` / `PAN_XML_API` | 120 s | PAN-OS XML API Commit Configuration Reference (`type=commit`, partial admin-scoped commit) |
| **READ (commit job poll)** | `type=op&cmd=<show><jobs><id><JOB_ID></id></jobs></show>` (polled every 3 s up to 120 s until `<status>FIN</status>`) | `not_applicable` / `PAN_XML_API` | 30 s | PAN-OS XML API Operational Command Reference (`show jobs id`) |

- **HA behaviour (Active-Passive)**: Before `set`/`delete`, the worker runs a fresh `<show><high-availability><state/></high-availability></show>` on both members, verifies both serials, confirms the target is `active` and the peer is `passive`, and confirms `running-config` sync is `synchronized` (`PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md` §3.3). Commands are sent only to the Active member. When HA config auto-sync is enabled, committing on Active automatically syncs the static route to Passive.
- **Panorama-managed templates question (CG-7a..CG-7d, Q3a)**: On a Panorama-managed firewall, `<show><config><effective-running/></config></show>` marks template-pushed elements with `src="tpl"`. A local firewall `action=delete` cannot delete an un-overridden `src="tpl"` route (`PANORAMA_TEMPLATE_MANAGED`), and a local `action=set` on a template-managed `virtual-router` creates a `src="local"` override that triggers neXus's override notification (`PO_DECISION_RECORD_2026_09_14G` §2a) and risks being overwritten if a Panorama administrator pushes with *Force Template Values*. Pre-check inspects `src` and enforces the PO's policy (Q3a).

### 3.3 Cisco ASA (Single-Context and Multi-Context, Routed Mode)

| Phase | Candidate command literal (`canonical_command_key`) | Shell / context / transport | Timeout | Official vendor source |
| --- | --- | --- | --- | --- |
| **READ (mode & priv)** | `show curpriv`, `show mode`, `show version`, `show failover`, `show failover state` (existing gates in V78 / V210) | `privileged_exec` / `SSH_INTERACTIVE` | 30 s | Cisco ASA Command Reference (`CISCO_ASA_CONTRACT.md`, `PO_DECISION_RECORD_2026_11_14_ASA_FAILOVER_READINESS_READS.md`) |
| **CONTEXT SWITCH (multi-context only)** | `changeto context <CTX_NAME>` | `context` / `SSH_INTERACTIVE` | 30 s | Cisco ASA 9.22 General Operations CLI Config Guide (*Multiple Context Mode*, `A0-ASA-19`); Command Reference `A–H` (`changeto`) |
| **READ (RIB & config)** | `show route` (existing gate `asa_show_route`) and `show running-config route` (new candidate read gate; outputs `route ...` lines only, zero secret risk) | `privileged_exec` (inside target context) / `SSH_INTERACTIVE` | 30 s | Cisco ASA Command Reference `S` (`show route`, `show running-config route`) |
| **ADD** | `configure terminal` → `route <IF_NAME> <DEST_IP> <NETMASK> <NEXTHOP_IP> <DISTANCE>` → `end` (`<DISTANCE>` default `1`, range `1..255`; ASA `route` has no CLI comment parameter) | `config_mode` (inside target context) / `SSH_INTERACTIVE` | 30 s | Cisco ASA Command Reference `I–R` (`route if_name dest_ip mask gateway_ip [distance] [track track_id]`) |
| **DELETE** | `configure terminal` → `no route <IF_NAME> <DEST_IP> <NETMASK> <NEXTHOP_IP>` → `end` | `config_mode` (inside target context) / `SSH_INTERACTIVE` | 30 s | Cisco ASA Command Reference `I–R` (`no route if_name dest_ip mask gateway_ip`) |
| **SAVE** | `write memory` | `privileged_exec` (inside target context) / `SSH_INTERACTIVE` | 60 s | Cisco ASA Command Reference `T–Z` (`write memory`) |

- **HA behaviour (Active/Standby Failover)**: Verified via `show version` (serial matched against `ha_pair_identity`, V209) + `show failover` (`This host: ... - Active`, mate `Standby Ready`) + `show failover state` (`Sync Done`). Per Cisco ASA High Availability and Scalability Configuration Guide, `route`, `no route`, and `write memory` executed on the **Active** unit are automatically replicated in real time to the Standby unit and written to Standby flash. **`write standby` is strictly prohibited** (Cisco documents that `write standby` clears the standby configuration and forces a bulk re-sync, causing interface flaps and CPU spikes).

### 3.4 FortiGate (FortiOS 7.4 — Single-VDOM and Multi-VDOM)

| Phase | Candidate command literal (`canonical_command_key`) | Shell / context / transport | Timeout | Official vendor source |
| --- | --- | --- | --- | --- |
| **CONTEXT SWITCH (multi-VDOM)** | `config vdom` → `edit <VDOM_NAME>` … `end` (existing gate `fgt_context_moves`, V80) | `context` / `SSH_INTERACTIVE` | 30 s | FortiOS 7.4 Administration Guide (*VDOM Overview*, `FORTINET_CONTRACT.md`) |
| **READ (RIB & config)** | `get router info routing-table all` (existing gate `fgt_get_routing_table`) and `show router static` (new candidate read gate; returns `edit <seq-num>` blocks, zero secret risk) | `vdom` (or `top` on single-VDOM) / `SSH_INTERACTIVE` | 30 s | FortiOS 7.4.7 CLI Reference (*`get router info routing-table`*, *`config router static`*) |
| **ADD** | `config router static` → `edit 0` → `set dst <DEST_IP> <NETMASK>` → `set gateway <NEXTHOP_IP>` → `set device "<IF_NAME>"` → `set distance <DISTANCE>` → `set priority <PRIORITY>` → `set comment "<SAFE_COMMENT>"` → `next` → `end` (`edit 0` auto-assigns the next free sequence ID) | `vdom` (or `top`) / `SSH_INTERACTIVE` | 30 s | FortiOS 7.4.7 CLI Reference, *`config router static`* (`edit 0`, `dst`, `gateway`, `device`, `distance` default `10` `[1..255]`, `priority` default `1` `[1..65535]`, `comment` max 255 chars) |
| **DELETE** | `config router static` → `delete <SEQ_NUM>` → `end` (`<SEQ_NUM>` resolved uniquely from fresh `show router static` in Stage B pre-check) | `vdom` (or `top`) / `SSH_INTERACTIVE` | 30 s | FortiOS 7.4.7 CLI Reference, *`config router static`* (`delete <seq-num>`) |
| **SAVE** | Auto-saved on `end` when `cfg-save` is `automatic` (FortiOS default; verified via `show full-configuration system global`, existing gate `fgt_show_full_configuration_system_global`) | `top_or_global` / `SSH_INTERACTIVE` | — | FortiOS 7.4.7 CLI Reference, *`config system global`* (`set cfg-save {automatic\|manual\|revert}`, default `automatic`) |

- **HA behaviour (FGCP Active-Passive)**: Fresh `get system status` + `get system ha status` + `diagnose sys ha checksum cluster` (V80 / V247) verify the session is on the `Primary` unit and the cluster is `in-sync`. FortiOS FGCP automatically synchronizes `config router static` changes to the Secondary when `end` is executed. Immediately after `ADD`, `show router static` is re-read to record the allocated `<SEQ_NUM>` on the ledger for deterministic rollback.

### 3.5 Candidate network-device command gate summary (`docs/AI_DEVELOPMENT_PROTOCOL.md` fields 1–10)
All rows below have **retry = `none`**, **max frequency = `once per member per approved job`**, **session reuse = `one session/API-key per member per job`**, **secret-output risk = `none`**, and **safe telemetry = `derived status, route-match boolean, and route count only; raw output discarded`**.

| Candidate `gate_id` | Vendor / `platform_role_scope` | Class (`ActionClass.id`) | Shell / transport | Timeout | Unsupported behavior |
| --- | --- | --- | --- | --- | --- |
| `cp_route_show_static_route` | `check_point` / `cp_gaia_gateway` | `read` | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Empty static route list is a valid result; Clish lock returns `GAIA_DB_LOCKED` |
| `cp_route_set_static_route_on` | `check_point` / `cp_gaia_gateway` | `configuration-write` (or `operational-state-change`, Q1) | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Non-zero exit or Clish syntax/lock error aborts before `save config` |
| `cp_route_set_static_route_comment` | `check_point` / `cp_gaia_gateway` | `configuration-write` | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Optional; skipped when `deviceComment` is empty |
| `cp_route_set_static_route_off` | `check_point` / `cp_gaia_gateway` | `configuration-write` | `expert_via_clish_c` / `SSH_EXEC` | 30 s | Non-zero exit or Clish error aborts before `save config` |
| `cp_route_save_config` | `check_point` / `cp_gaia_gateway` | `configuration-write` | `expert_via_clish_c` / `SSH_EXEC` | 60 s | Non-zero exit marks step `DEVICE_ERROR` and opens incident |
| `pan_route_get_vr_static_route` | `palo_alto` / `pan_firewall` | `read` | `not_applicable` / `PAN_XML_API` | 30 s | Missing VR returns `ROUTING_CONTEXT_NOT_FOUND` |
| `pan_route_check_pending_changes` | `palo_alto` / `pan_firewall` | `read` | `not_applicable` / `PAN_XML_API` | 30 s | `<result>yes</result>` refuses unless partial admin-scoped commit is active |
| `pan_route_set_vr_static_route` | `palo_alto` / `pan_firewall` | `configuration-write` | `not_applicable` / `PAN_XML_API` | 30 s | `status != "success"` aborts without issuing `type=commit` |
| `pan_route_delete_vr_static_route` | `palo_alto` / `pan_firewall` | `configuration-write` | `not_applicable` / `PAN_XML_API` | 30 s | `status != "success"` aborts without issuing `type=commit` |
| `pan_route_commit_partial_admin` | `palo_alto` / `pan_firewall` | `configuration-write` | `not_applicable` / `PAN_XML_API` | 120 s | Missing `job` ID or `status="error"` fails closed |
| `pan_route_show_job_id` | `palo_alto` / `pan_firewall` | `read` | `not_applicable` / `PAN_XML_API` | 30 s | Polled up to 120 s; `result != "OK"` after `FIN` marks `DEVICE_ERROR` |
| `asa_route_changeto_context` | `cisco_asa` / `asa_gateway` | `read` | `context` / `SSH_INTERACTIVE` | 30 s | Issued only in `multiple` mode; prompt mismatch aborts before config mode |
| `asa_route_show_running_route` | `cisco_asa` / `asa_gateway` | `read` | `privileged_exec` / `SSH_INTERACTIVE` | 30 s | Empty output means zero static routes configured in this context |
| `asa_route_add_static` | `cisco_asa` / `asa_gateway` | `configuration-write` | `config_mode` / `SSH_INTERACTIVE` | 30 s | `ERROR:` or `WARNING:` line in CLI response aborts before `write memory` |
| `asa_route_delete_static` | `cisco_asa` / `asa_gateway` | `configuration-write` | `config_mode` / `SSH_INTERACTIVE` | 30 s | `ERROR: Cannot remove route` aborts before `write memory` |
| `asa_route_write_memory` | `cisco_asa` / `asa_gateway` | `configuration-write` | `privileged_exec` / `SSH_INTERACTIVE` | 60 s | Requires `[OK]` confirmation in response |
| `fgt_route_show_router_static` | `fortinet` / `fgt_gateway` | `read` | `vdom` / `SSH_INTERACTIVE` | 30 s | Parses `edit <seq-num>` blocks; ambiguous match refuses `ROUTE_MATCH_AMBIGUOUS` |
| `fgt_route_add_static_edit0` | `fortinet` / `fgt_gateway` | `configuration-write` | `vdom` / `SSH_INTERACTIVE` | 30 s | `Command fail.` or `object check operator error` aborts and marks `DEVICE_ERROR` |
| `fgt_route_delete_static_seq` | `fortinet` / `fgt_gateway` | `configuration-write` | `vdom` / `SSH_INTERACTIVE` | 30 s | `Command fail.` marks `DEVICE_ERROR` |

---

## 4. Pre-checks, post-checks, and rollback

### 4.1 Pre-checks (Stage A against stored inventory at request/preview; Stage B live on device before `mutation_possible = true`)
Every Stage B check must return **`PASS`** from the live device read in the job's own session; any `FAIL` or `UNKNOWN` stops the job with `mutation_possible = false` and zero writes sent:

| # | Check ID | Condition required for `PASS` | Refusal / stop code |
| --- | --- | --- | --- |
| 1 | `TARGET_REACHABLE_AND_READY` | SSH / XML-API session established; host key trusted (`requireTrusted`); privilege verified (`show curpriv` = 15 on ASA); no active failover, config-sync, restore, or route job on the unit; on PAN `<check><pending-changes/></check>` is `no` (unless partial admin-scoped commit is used); on FGT `cfg-save` is `automatic`. | `TARGET_UNREACHABLE`, `TRUST_REQUIRED`, `UNIT_BUSY`, `PENDING_CHANGES_PRESENT` |
| 2 | `IDENTITY_AND_HA_VERIFIED` | Live device serial equals recorded identity (`MATCH`). On HA clusters: CP both members reachable and `Active`/`Standby`; PAN both members reachable, target is `active`, HA config `synchronized`; ASA serial in `ha_pair_identity`, target is `Active`, mate `Standby Ready`, `Sync Done`; FGT target is `Primary`, cluster `in-sync`. | `IDENTITY_NOT_VERIFIED`, `HA_STATE_NOT_READY`, `HA_CONFIG_OUT_OF_SYNC` |
| 3 | `DEFAULT_ROUTE_GUARD` | Destination prefix is **not** `0.0.0.0/0` (`default`), unless the request carries `allow_default_route = true` (if permitted by PO policy in Q5) and both requester and approver ticked the explicit default-route warning. | `DEFAULT_ROUTE_FORBIDDEN` |
| 4 | `ROUTE_STATE_PRECONDITION` | **ADD**: No static route with `(destination_cidr, nexthop_ip)` (or PAN `route_name`) exists in either the configured static-route table or the live RIB. **DELETE**: The exact static route `(destination_cidr, nexthop_ip, interface)` exists in the configured static-route table (and for FortiGate resolves to **exactly one** `<seq-num>`; 0 matches → `ROUTE_NOT_FOUND`, >1 matches → `ROUTE_MATCH_AMBIGUOUS`; for PAN `src != "tpl"`). | `ROUTE_ALREADY_EXISTS`, `ROUTE_NOT_FOUND`, `ROUTE_MATCH_AMBIGUOUS`, `PANORAMA_TEMPLATE_MANAGED` |
| 5 | `NEXTHOP_ON_CONNECTED_SUBNET` | `<NEXTHOP_IP>` lies strictly inside an operational (`UP`) directly `connected` IPv4 subnet in the same `routing_context`, does not equal the firewall's own local interface IP, network address, or broadcast address, and belongs to `<IF_NAME>` when `<IF_NAME>` is supplied. | `NEXTHOP_NOT_ON_CONNECTED_SUBNET`, `NEXTHOP_IS_LOCAL_INTERFACE_IP`, `NEXTHOP_INTERFACE_MISMATCH` |
| 6 | `NO_CONNECTED_OR_SPECIFIC_OVERLAP` | (a) `<DEST_CIDR>` does not cover the firewall's management IP, HA sync subnets, or the neXus worker route (`MANAGEMENT_LOCKOUT_RISK`). (b) `<DEST_CIDR>` does not equal or overlap a directly `connected` subnet in the same `routing_context` (`OVERLAPS_CONNECTED_SUBNET`). (c) On **ADD**, no equal or more-specific route (`existing_prefix_len >= dest_prefix_len` where `existing_prefix` is a subset of `<DEST_CIDR>`) already exists in the live RIB (`OVERLAPS_EXISTING_OR_MORE_SPECIFIC_ROUTE`). | `MANAGEMENT_LOCKOUT_RISK`, `OVERLAPS_CONNECTED_SUBNET`, `OVERLAPS_EXISTING_OR_MORE_SPECIFIC_ROUTE` |

### 4.2 Post-checks (live on device after SAVE / COMMIT)
1. **`POST_CONFIG_AND_RIB_STATE` (blocking)**:
   - **ADD**: The static route `(destination_cidr, nexthop_ip)` is present in the configured static-route read (`show static-route` / PAN `xpath=.../static-route` / `show running-config route` / `show router static`) **and** installed in the live RIB (`ip -4 route show table all` on both CP members; PAN `<show><routing><route/></routing></show>` with `A S`; ASA `show route`; FGT `get router info routing-table all` with `S`).
   - **DELETE**: The static route is absent from both the configured static-route table and live static RIB entries.
2. **`POST_HA_CONTINUITY` (blocking on HA units)**: HA roles have not flipped, and HA config sync is healthy (CP both members match; PAN `synchronized`; ASA `Sync Done`; FGT `in-sync`).
3. **`POST_NEXTHOP_RESOLUTION` (informational / optional)**: Checks whether the next-hop or destination resolves as expected via already-gated reads (CP `ip -4 route get <DST>` V187; PAN `<test><routing><fib-lookup>` V187; FGT `get system arp` V248; ASA `show route`). Active ICMP ping from the firewall is not issued unless the PO gates a per-vendor ping literal (Q6).

### 4.3 Rollback (inverse command) — automatic vs approval-gated
- **Inverse plan compilation**: Every request pre-computes its exact inverse command before approval:
  - For `ADD`, the inverse is `DELETE` of `(destination_cidr, nexthop_ip, interface, route_name / captured FGT <seq-num>)` + `SAVE/COMMIT`.
  - For `DELETE`, Stage B pre-check captures the full attributes of the existing route (`distance`, `metric`, `priority`, `comment`, `route_name`) before deleting it, so the inverse `ADD` + `SAVE/COMMIT` restores the identical route entry.
- **When rollback is NEVER automatic (constitutional invariant)**:
  - Per `AGENTS.md` ("No automatic (unscheduled-trigger, non-ledgered) network-device write/change operation is permitted") and `FAILOVER_EXECUTION_PLAN.md` §4, if any write or commit ends in **`OUTCOME_UNKNOWN`** (timeout, dropped session) or if a single-target write/commit finishes with a failed post-check, the worker **STOPS**, records the state, opens an incident if uncertain, and **never** issues an automatic inverse write.
- **Candidate automatic vs approval-gated boundaries (PO decision Q4)**:
  1. **Palo Alto pre-commit abort (automatic / safe)**: If `action=set` or `action=delete` fails or pre-commit check fails *before* `<commit>` is sent, no `<commit>` is issued (or candidate config is reverted prior to commit); live forwarding was never touched.
  2. **Check Point ClusterXL split-member partial apply** (Standby write+save `REPLY_RECEIVED`, Active failed *before* its write was sent):
     - **Option RB-A (Strict manual — recommended default)**: Stop immediately (`PARTIAL_CLUSTER_APPLY`), open an incident naming Standby as modified and Active as unmodified, and surface a one-click **"Revert Standby Member"** rollback action requiring `security_admin` confirmation.
     - **Option RB-B (Pre-approved same-job revert of Standby)**: Because the inverse command on Standby is part of the four-eyes-signed `request_digest`, automatically revert Standby within the same job *only* when Standby's write is `REPLY_RECEIVED` and Active's write is proven `NOT_SENT_BEFORE_CONTACT`.
  3. **Post-execution manual rollback**: Any `COMPLETED` or `STOPPED` job exposes a **"Rollback route change"** button in Operations that creates a pre-filled inverse request (`DELETE` for a prior `ADD`, `ADD` for a prior `DELETE`), gated by four-eyes approval (or single-`security_admin` emergency approval within 60 minutes if chosen in Q4).

---

## 5. UI flow under Operations and field validation rules

### 5.1 Placement in `OperationsScreen.tsx`
`OperationsScreen.tsx` (`TAB_SLUGS`) gains a fourth tab: **`"routes"`** (**Static routes**, URL `?screen=operations&tab=routes`), alongside `HA and readiness`, `Jobs`, and `Device health`. Under `aiview` (`role:replay_viewer`), the tab renders the masked request history and execution steppers using `TopologyNamePseudonymizer` (`FW-TANGO-04`, `CLS-ROMEO-01`) and synthetic prefixes (`192.0.2.0/24`), with all authoring/approval/execution buttons hidden and refused by the server.

### 5.2 Form fields and server-enforced validation rules
The browser submits typed JSON intent only; no CLI string, XPath, or shell fragment is ever accepted from the client (`AGENTS.md` "No Browser → device path").

| Field | Required | Validation rule (enforced in frontend form and backend service) |
| --- | --- | --- |
| `operation` | Yes | Closed enum: `ADD` \| `DELETE`. |
| `unitId` | Yes | Opaque UUID / `cluster_ref` of an enrolled, non-disabled firewall or HA cluster (`check_point`, `palo_alto`, `cisco_asa`, `fortinet`). |
| `routingContext` | Yes | Selected from stored inventory contexts for `unitId`: CP `gaia-default` or digits-only `VSID` (`1..4095`); PAN `virtual-router`/`logical-router` name (`^[A-Za-z0-9_.-]{1,63}$`); ASA `single` or context name (`^[A-Za-z0-9_.-]{1,64}$`); FGT VDOM name (`^[A-Za-z0-9_.-]{1,31}$`). |
| `destinationPrefix` | Yes | Canonical IPv4 CIDR `A.B.C.D/M` with `M` in `1..32` (`0.0.0.0/0` rejected unless `allowDefaultRoute = true`). Host bits after mask must be `0` (e.g., `192.0.2.5/24` rejected; must be `192.0.2.0/24`). Rejects loopback (`127.0.0.0/8`), RFC 3927 link-local, Class D multicast, limited broadcast, and non-default unspecified (`0.0.0.0/1..32`). Server derives dotted netmask for ASA/FGT. |
| `nextHopIp` | Yes | Unicast IPv4 `A.B.C.D`. Rejects `0.0.0.0`, loopback, link-local, multicast, broadcast, any local interface IP on `routingContext`, and any IP outside the connected UP subnets of `routingContext`. |
| `interfaceName` | ASA, FGT: **Yes**; PAN: **Optional**; CP: **Omitted** | Must match an existing `UP` interface in stored inventory for `(unitId, routingContext)` (`^[A-Za-z0-9_./:-]{1,48}$`) whose connected subnet contains `nextHopIp`. |
| `distance` | Optional | Integer administrative distance: PAN `1..255` (default `10`), ASA `1..255` (default `1`), FGT `1..255` (default `10`). Rejected if supplied for Check Point (or mapped to `priority` `1..8` only when explicitly labelled `priority`). |
| `metric` / `priority` | Optional | Integer: CP `priority` `1..8`; PAN `metric` `1..65535` (default `10`); FGT `priority` `1..65535` (default `1`); rejected on ASA. |
| `routeName` | PAN only | On `ADD`: optional `^[A-Za-z0-9_.-]{1,31}$` (defaults to deterministic `nxs-<yyyymmdd>-<hash6>`). On `DELETE`: resolved from the selected existing static route in inventory. |
| `deviceComment` | Optional (CP, FGT) | `^[A-Za-z0-9 _.:/-]{0,80}$` (ASCII allowlist; quotes, `$`, `` ` ``, `\`, `;`, `<`, `>`, `&`, control chars rejected). Disabled in UI for ASA (unsupported by ASA CLI) and PAN (until `<description>` schema support is verified). |
| `reason` | Yes | Ticket reference and operational justification (`8..255` printable characters, stored in `static_route_change_request.reason` and audit log; never sent to the device). |

### 5.3 Operator workflow (Preview → Four-Eyes Approval → Execution Log → Result)
1. **Draft & Stage A Preview**: `security_admin` #1 fills the form and clicks **Preview & check**. The server runs Stage A pre-checks against stored inventory/config, compiles the exact per-member command plan and inverse rollback plan, and returns:
   - Stage A pre-check table (`PASS` / `FAIL` / `UNKNOWN` per check in §4.1, including connected-subnet match and Panorama `src` check),
   - **Exact Command Preview Card** (read-only monospace list of the exact candidate literals per target member, plus the `request_digest`),
   - **Submit for four-eyes approval** button (enabled only when all blocking Stage A checks pass).
2. **Approval Card (`requester != approver`)**: `security_admin` #2 opens the pending request in the **Pending approvals** queue, reviews the requester, ticket reason, Stage A checks, exact command preview, and rollback plan, sets the approval window (`from` / `until`), and clicks **Approve** (or **Reject**).
3. **Execute & Business-Hours Check**: Inside the approved window, an authorized admin clicks **Execute route change** (confirming the `08:00–20:00 Europe/Istanbul` business-hours prompt if inside business hours).
4. **Live Execution Stepper & Ledger Drawer**:
   - Stepper: `Queued` → `Live pre-checks` → `Writing route` → `Saving / committing` → `Live post-checks` → `Completed` (or `Stopped — <code & phase>`).
   - **Execution Log Table**: One row per ledger step showing member pseudonym, phase, `gate_id`, delivery state (`NOT_SENT` / `REPLY_RECEIVED` / `OUTCOME_UNKNOWN`), derived check status (`PASS` / `FAIL` / `UNKNOWN`), and timestamps. Zero raw CLI/XML output is stored or rendered.
   - **Result & Rollback**: Shows final `COMPLETED` / `FAILED` / `OUTCOME_UNKNOWN` badge, before/after route presence summary, and the **Create rollback request** button.

### 5.4 Proposed REST API routes and CLI parity (`nexus-cli`)
| Route / CLI subcommand | Action (`ActionRegistry`) | Purpose |
| --- | --- | --- |
| `POST /api/v2/operations/static-routes/preview` | `static_route_request` | Run Stage A pre-checks on stored inventory and return compiled command preview + `request_digest` (no DB write, no device contact) |
| `POST /api/v2/operations/static-routes` (`Idempotency-Key` required) | `static_route_request` | Create `PENDING_APPROVAL` request bound to `request_digest` and compiled inverse plan (`nexus-cli route-request ...`) |
| `POST /api/v2/operations/static-routes/{requestId}/approve` | `static_route_approve` | Record four-eyes approval (`approved_by <> requested_by`) and approval window (`nexus-cli route-approve <requestId> ...`) |
| `POST /api/v2/operations/static-routes/{requestId}/execute` | `static_route_execute` | Admit worker job inside approved window, enforcing `X-Nexus-Business-Hours-Ack` during `08:00–20:00 Europe/Istanbul` (`nexus-cli route-execute <requestId> [--business-hours-ack]`) |
| `POST /api/v2/operations/static-routes/{requestId}/rollback` | `static_route_rollback` | Create pre-populated inverse rollback request referencing `requestId` (`nexus-cli route-rollback <requestId>`) |
| `GET /api/v2/operations/static-routes` & `GET .../{requestId}` | `static_route_read` | List requests and read execution stepper + ledger rows (`aiview` receives masked projection only) |

---

## 6. Open questions for the Product Owner and the decision council

1. **Q1 — Action class and role split (`operation_admin` vs `security_admin`)**:
   - **(a)** In `ActionClass.java` / `gate_registry`, should static-route write/save rows use `CLASS_3_CONFIGURATION_WRITE` (`configuration-write`, matching `pan_failover_config_sync` V178) or `CLASS_2_OPERATIONAL_STATE_CHANGE` (`operational-state-change`, matching failover)?
   - **(b)** Should all four write actions (`request`, `approve`, `execute`, `rollback`) be restricted strictly to `security_admin` (with two distinct `security_admin` users for four-eyes), or may an `operation_admin` create the request (`static_route_request`) and execute it once a `security_admin` has approved it (`static_route_approve`)?
2. **Q2 — Check Point VSX Virtual System (`VS<VSID>`) static-route mechanism and ClusterXL delete form**:
   - **(a)** Because `clish -c` inside `vsenv <VSID>` reads/writes `VS0` (measured 2026-09-14, `CG-1`) and Check Point documents VSX static routes as Management-provisioned (`vsx_provisioning_tool` / SmartConsole) unless VSX Direct Routing is used, should Wave 1 support Check Point non-VSX / ClusterXL first and defer VSX `VS<VSID>` until a one-off lab measurement confirms whether interactive Clish (`set virtual-system <VSID>` → `set static-route ...` → `save config`) or `vsx_provisioning_tool` on the MDS is the estate's standard?
   - **(b)** On Check Point DELETE, should neXus issue `set static-route <DEST_CIDR> nexthop gateway address <NEXTHOP_IP> off` (removes only the matched next-hop, preserving other ECMP next-hops if any) or `set static-route <DEST_CIDR> off` (removes the entire prefix entry when only one next-hop exists)?
3. **Q3 — Palo Alto Panorama-managed Virtual Routers, partial commit, and ARE Logical Routers**:
   - **(a)** When a Palo Alto firewall's `virtual-router` is managed by a Panorama Template / Template Stack, should neXus **refuse** direct-firewall static-route writes (`PANORAMA_TEMPLATE_MANAGED`) to avoid creating `src="local"` template overrides (`CG-7a`), or allow `ADD` on the direct firewall with an explicit local-override warning while refusing `DELETE` of `src="tpl"` routes?
   - **(b)** For the PAN-OS commit step, do you approve `<commit><partial><admin><member><ADMIN_USER></member></admin></partial></commit>` (commits only the neXus admin account's changes) AND/OR requiring `<check><pending-changes/></check>` to be `no` before modifying the candidate config?
   - **(c)** Do any estate Palo Alto firewalls run the **Advanced Routing Engine (ARE / `logical-router`)**, or are all 34 firewalls using legacy `virtual-router` in Wave 1?
4. **Q4 — Rollback policy on partial ClusterXL apply and emergency post-execution rollback**:
   - **(a)** If a Check Point ClusterXL job succeeds on the Standby member (`REPLY_RECEIVED`) and aborts *before* sending the write to the Active member (`NOT_SENT_BEFORE_CONTACT`), should the worker stop for manual one-click rollback (**Option RB-A**, strict constitutional default) or automatically run the pre-approved inverse command on the Standby member within the same job (**Option RB-B**)?
   - **(b)** After a completed route change, does a manual rollback within 60 minutes still require full four-eyes approval (`requester != approver`), or may a single `security_admin` execute the pre-computed inverse rollback?
5. **Q5 — Default route (`0.0.0.0/0`) and overlapping less-specific routes**:
   - Should `0.0.0.0/0` (`default`) be **unconditionally prohibited** in Wave 1 (`DEFAULT_ROUTE_FORBIDDEN`), or allowed when an explicit `allow_default_route` warning checkbox is confirmed by both the requester and the approver?
6. **Q6 — Post-check reachability probe and automatic inventory/configuration refresh**:
   - **(a)** Should the post-check rely solely on RIB/config presence (`ip -4 route show table all` / `show routing route` / `show route` / `get router info routing-table all`) plus already-gated FIB/ARP reads, or do you want a new per-vendor ICMP `ping` gate literal proposed for next-hop probing?
   - **(b)** After a static route job finishes `COMPLETED`, should the worker automatically enqueue a follow-up `device_inventory_collect` + `device_configuration_collect` job for the modified unit (matching `pan_config_sync_followup` in V178) so Inventory, Configuration, and Path Analysis reflect the new route immediately?
