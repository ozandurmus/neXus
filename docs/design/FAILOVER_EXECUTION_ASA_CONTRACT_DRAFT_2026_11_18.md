# Cisco ASA Failover Execution Contract (Switchover of the Active Unit)

**Status: DRAFT -- DO NOT IMPLEMENT.** PO answers recorded in §11: Q1, Q2, Q5 (2026-11-18); Q4, Q6 approved and Q3
approved in principle (2026-11-23). The contract stays DRAFT until the Q3 decision council and code review
(`ASA_FAILOVER_Q3_COUNCIL_PACKET_2026_11_23.md`) are complete and the PO freezes it. Q7 (pilot pair) is not a freeze
blocker, only a first-production-run blocker.

> **Authority & Governance Notice (`AGENTS.md`):** This document is a **DRAFT** design contract (`CONTRACT` movement). A Cisco ASA failover switchover is a network-device **WRITE** (`ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE`). Per `AGENTS.md` ("Authority hierarchy", "Network action taxonomy", "Mandatory build lifecycle", and PO Amendment 2026-09-26), **no product code, database migration, or command-gate row may be implemented or signed off** until:
> 1. The decision council and code-review gates for the session-dropping write boundary are completed (`PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md` item 6),
> 2. The Product Owner answers the open questions in §10, approves the exact command literals and frequencies literal-by-literal, and freezes this contract (`FROZEN`), and
> 3. Stakeholders sign off the network-security / change-management review (`PRODUCT_DIRECTION_RECORD.md` item 368) before the first production execution.

This draft mirrors the shipped, `FROZEN` Check Point ([FAILOVER_EXECUTION_CP_CONTRACT.md](FAILOVER_EXECUTION_CP_CONTRACT.md)) and Palo Alto ([FAILOVER_EXECUTION_PAN_CONTRACT.md](FAILOVER_EXECUTION_PAN_CONTRACT.md)) execution contracts, the ratified estate execution rules ([PO_DECISION_RECORD_2026_09_27_CP_FAILOVER_EXECUTION.md](PO_DECISION_RECORD_2026_09_27_CP_FAILOVER_EXECUTION.md), [PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md](PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md), [PO_DECISION_RECORD_2026_10_20_FAILOVER_PILOT_P0.md](PO_DECISION_RECORD_2026_10_20_FAILOVER_PILOT_P0.md), [PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md](PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md)), and the shipped Cisco ASA Phase 0/1 readiness implementation (`AsaFailoverJobExecutor`, `AsaFailoverChecks`, `V209`, `V212`, `V222`, `V248`, `V249`). All topology examples use deterministic `aiview` masked pseudonyms (`CLS-TANGO-01`, `FW-TANGO-04`, `FW-TANGO-05`, `FW-FOXTROT-07`) and RFC 5737 documentation addresses (`192.0.2.x`).

---

## 1. Scope and Failover Unit Model

### 1.1 In Scope
1. **Active/Standby failover pairs in single context mode (enrolled today):**
   - Two enrolled `devices` rows (`vendor_hint = 'cisco_asa'`, `role = 'gateway'`, `permitsReadCollection = true`) sharing a corroborated `cluster_member_ref` of the form `asa-failover|<sha256-prefix>` derived from `ip address <active-ip> <mask> standby <standby-ip>` (`CiscoAsaPlan.failoverPairReference`).
   - Each member has a management endpoint with `transport_kind = 'ssh_exec'` (`192.0.2.10` for the active management address slot and `192.0.2.11` for the standby management address slot) and an active host-key trust record in `management_endpoint_ssh_trust`.
   - Both routed and transparent firewall modes are eligible, provided the pair operates in **Active/Standby** HA mode (`Failover On`, no failover group lines in `show failover`).
   - Includes Cisco Secure Firewall / Firepower 4100 and 9300 appliances running the **ASA software image** (identical ASA CLI and failover state machine; FXOS is not contacted).
2. **Active/Standby failover pairs in multiple context mode (system execution space — subject to PO decision Q2):**
   - In Cisco ASA multiple context mode (`show mode` -> `Security context mode: multiple`), Active/Standby failover is an appliance-wide operation: all security contexts fail over together as a single unit, and failover state/commands live in the **system execution space** (`changeto system`), never inside an individual security context.
   - An Active/Standby multiple-context pair is therefore **one** failover unit (`vs_id IS NULL`), unlike Check Point VSX where each Virtual System is its own failover unit (`FAILOVER_EXECUTION_CP_CONTRACT.md` §10).
   - *Current implementation gap:* all 5 enrolled ASA pairs in the estate today run in `single` context mode (`FAILOVER_FORTIGATE_ASA_DESIGN_DRAFT.md` §1), and `AsaFailoverJobExecutor.preamble` currently stops with `UNSUPPORTED_MODE` whenever `show mode` does not return `"single"`. Supporting multiple-context Active/Standby pairs requires issuing `changeto system` in the session preamble when `show mode` reports `"multiple"` and resolving the data-plane read limitation in §3.3 / Q2.

### 1.2 Out of Scope (Refused Fail-Closed Before Any Write)
- **Active/Active failover (`failover group 1` / `failover group 2`):** Refused with `UNSUPPORTED_MODE`. Active/Active mode (available only in multiple context mode) assigns contexts to independent failover groups that can be `Active` on different chassis simultaneously (`[no] failover active group <1|2>`). Representing, pre-checking, and switching individual failover groups requires per-group unit keys and group-scoped role/session verification, which are excluded from this contract (`AsaFailoverChecks.GROUP` already rejects any `show failover` output containing `Group <n>` or `Failover group`).
- **ASA clustering (`cluster group` / Spanned EtherChannel / Individual Interface mode):** Refused with `UNSUPPORTED_MODE` / `CLUSTER_NOT_ELIGIBLE`. ASA clustering is an N-node load-sharing architecture (`show cluster info`, control/data units) rather than a two-member Active/Standby failover pair; its health checks, state replication, and unit-disable semantics are completely distinct from `show failover`.
- **Cisco FTD (FMC / FDM / cdFMC):** Out of scope (`PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md` item 1). FTD high-availability switchover is driven through the FMC REST API (`PUT .../ftddevicehapairs/{id}`) or FTD clish, and the estate holds zero FTD devices.

---

## 2. Action Class, RBAC, Four-Eyes Approval, Job Windows, Ledger, and Concurrency

This contract reuses the exact authorization, approval, window, ledger, and concurrency machinery of Check Point and Palo Alto failover execution without weakening any gate:

| Control | Reused Authority / Clause | Behavior for Cisco ASA (`vendor = 'cisco_asa'`) |
| --- | --- | --- |
| **Action class** | `AGENTS.md` "Network action taxonomy"; `ActionClass.java` | `ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE` (`operational-state-change` in `gate_registry` and `jobs.action_class`). Never console-submittable as a raw command (`No Browser -> device path`). |
| **Route actions & RBAC roles** | `ActionRegistry.java`; `SecurityWebMvcConfig.java`; `PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md` item 4 | Map `/api/v2/asa-failover/*` identically to `/api/v2/cp-failover/*` and `/api/v2/pan-failover/*`:<br>• `cp_failover_approve`: `security_admin` (alt: `operation_admin`)<br>• `cp_failover_start`: `operation_admin` (alt: `security_admin`)<br>• `cp_failover_incident_release`: `security_admin` only<br>• `cp_readiness_start`: `operator` (alt: `security_admin`, `operation_admin`, `replay_viewer`)<br>• `cp_failover_read`: `operator` (alt: `security_admin`, `operation_admin`, `viewer`, `replay_viewer`). |
| **AIView (`replay_viewer`) fence** | `AGENTS.md` "Mandatory AIView inspection & post-parsing masking law"; `CpFailoverService.replayViewer` | `aiview` (`role:replay_viewer`) may view masked readiness/run state and start read-only readiness runs (`cp_readiness_start`), but is unconditionally refused (`WRONG_ROLE`) on approval creation, second approval, failover start, schedule, and incident release — even if the principal also holds an admin role. |
| **Approval window & four-eyes policy** | `FAILOVER_EXECUTION_CP_CONTRACT.md` §1, §5; `PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md` item 4; `JooqCpFailoverRepository.createRequestApproval` / `approveRequest` (`V101`, `V135`) | • **`security_admin` (`ADMIN_SINGLE`):** A single authenticated `security_admin` (or root authority) may create the request-bound approval window (`window_from`, `window_until`, `reason` 1–500 chars) and start the run.<br>• **`operation_admin` (`OPERATION_ADMIN_TWO_PERSON`):** Strict four-eyes rule. One authenticated `operation_admin` initiates the approval request (`request_revision = 1`, `approved_by = NULL`); a second, **distinct** authenticated administrator (`actor != initiated_by`, else `SELF_APPROVAL`) must approve the exact `(request_id, revision, cluster_ref, member_set_revision)` before the initiator can start the run.<br>• Identities always come from the server-side authenticated session (`GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE`), never from a request body field. |
| **Business-hours confirmation & job windows** | `PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md` §2.1–§2.5 (`V168`); `JobWindowPolicy.java` | • **Scheduled device windows:** `00:00`, `06:00`, `12:00`, `18:00` `Europe/Istanbul` (60 min each).<br>• **Admin manual start bypass:** Manual failover starts by `security_admin` or `operation_admin` bypass the 6-hour windows (`window_bypass_role` recorded on `jobs`) and are claimable immediately.<br>• **Business-hours gate (`08:00–20:00 Europe/Istanbul` daily):** Any manual start inside business hours requires `X-Nexus-Business-Hours-Ack: true` (or `?business_hours_ack=true`), else `409 BUSINESS_HOURS_CONFIRM_REQUIRED`. Rechecked in `mutationAdmission` before the write: if a job submitted before `08:00` without acknowledgement reaches the write after `08:00`, it stops with `BUSINESS_HOURS_CONFIRM_REQUIRED` before any write is prepared.<br>• **Scheduled failover runs (`scheduledFor`):** Must fall inside both the approval window and a 6-hour job window (`CpFailoverService.startDue`). |
| **Pre/post ledger & audit** | `FAILOVER_EXECUTION_CP_CONTRACT.md` §4–§5; `FAILOVER_EXECUTION_PLAN.md` PR 4, PR 6 (`V101`, `V136`–`V140`) | • **Reads:** Before each CLI read, `job_step_attempt.insertPreContact` + `markBoundaryCrossed` + `store.command(runId, gateId)` commit; `writeOutcome` records `MATCHED` or `FAILED` after completion. Raw CLI output is never written to `job_transcript` (`JobTranscriptScope.withoutRecording`).<br>• **Write (`no failover active`):** `mutationAdmission(possibleSend=true)` sets `failover_run.mutation_possible = true`. `prepareDispatch` atomically commits `job_step_attempt` (`mutation_boundary_crossed = true`, `sent_at = now()`) and `failover_dispatch_intent` (`nonce`, `run_id`, `attempt_id`, `job_id`, `lease_epoch`, `owner_generation`, `owner_instance`, `step = 'FAILING_OVER'`, `member_ref`, `gate_id`, `delivery = 'MAY_HAVE_BEEN_SENT'`, `observation = 'NOT_OBSERVED'`). `dispatch` claims `dispatch_claimed = true` in a separate transaction before writing to the SSH channel. All tables fire `fn_audit_capture` into `audit_log`. |
| **Idempotency & no-replay** | `JooqCpFailoverRepository.requestBound`, `prepareDispatch`, `dispatch` | • Repeating `POST /api/v2/asa-failover/runs` with a consumed `request_id` returns the existing `run_id` without queuing a second job.<br>• Any existing `job_step_attempt` for `(job_id, step_index)` aborts with `PRIOR_ATTEMPT_NOT_REPLAYED`.<br>• `dispatch_claimed = true` is set once in its own committed transaction; a claimed or non-`NOT_OBSERVED` intent can never be sent again. |
| **Concurrency (one per unit, one per fleet)** | `V101` `uq_failover_run_active_unit`; `JooqCpFailoverRepository.admissionDecision` under `pg_advisory_xact_lock(136, 1)` | • **Per unit:** At most one non-terminal `failover_run` (`READINESS` or `FAILOVER`) per `(cluster_ref, coalesce(vs_id, ''))` (`RUN_ALREADY_ACTIVE`).<br>• **Fleet-wide:** At most one active `FAILOVER` run or resume across the entire estate (`FLEET_MUTATION_ACTIVE`). Any active `failover_quarantine` row on the cluster/members refuses with `OPEN_INCIDENT`; any unresolved dispatch intent anywhere in the estate refuses with `UNRESOLVED_DISPATCH`. |

---

## 3. Command Literals, Network-Device Command Gate, and Candidate Evaluation

### 3.1 Candidate Evaluation: `no failover active` (on Active) vs `failover active` (on Standby)

Both commands are documented in the **Cisco Secure Firewall ASA Series Command Reference, Commands A–H** (*`failover active`* chapter, privileged EXEC mode):
- `no failover active [group <group_id>]`: executed on the currently **Active** unit, forces it to relinquish the active role and transition to `Standby Ready` so its peer takes over as `Active`.
- `failover active [group <group_id>]`: executed on the currently **Standby Ready** unit, forces it to take over the `Active` role from its peer.

| Criterion | Candidate A: `no failover active` on verified Active (CHOSEN) | Candidate B: `failover active` on verified Standby (REJECTED) |
| --- | --- | --- |
| **PO Phase 0 design decision** | **Selected by the PO** on 2026-11-13 (`PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md` item 2: *"Aktifte `no failover active`"*). | Not selected for neXus (though seen in historical manual CLI history on `FW-FOXTROT-07`, `ASA_FAILOVER_MEASUREMENT_2026_11_13.md` §2 item 8). |
| **Cross-vendor execution symmetry** | Matches Check Point (`clusterXL_admin down` on Active) and Palo Alto (`<suspend/>` on Active): neXus always addresses the mutation to the verified Active member. | Inverts the target convention used by every other vendor in `ui2/worker/failover/`. |
| **Fail-safe behavior on peer degradation** | Relinquishes active state to the standby mate; aligns with step-down semantics after verifying the mate is `Standby Ready`. | Pulls the active role from the standby side; if a split-second link degradation occurs, an unconditional pull is more aggressive than a step-down. |
| **Management IP swap & SSH session drop** | Drops/stalls the active SSH session when the Active IP moves to the peer chassis. | **Also** drops/stalls the standby SSH session when the Standby IP moves to the former Active chassis. Neither candidate avoids the IP swap. |

**Decision for this draft:** neXus sends **`no failover active`** on the verified **Active** unit only. `failover active` is never gated and never sent.

### 3.2 Multiple Context Mode: The `system` Execution Space Requirement
Per the **Cisco Secure Firewall ASA Series Command Reference, Commands A–H** (*`changeto`* chapter, privileged EXEC mode) and the **Cisco Secure Firewall ASA Series General Operations CLI Configuration Guide** (*High Availability and Scalability* / *Multiple Context Mode*):
- An administrator SSH login on a multiple-context ASA lands in the administrative context (e.g. `admin`, CLI prompt `<hostname>/<admin-context>#`), where `show mode` prints `Security context mode: multiple`.
- Appliance-wide failover commands (`no failover active`, `show failover`, `show failover state`, `show failover history`) are governed from the **system execution space**, entered via `changeto system` in privileged EXEC mode (which changes the prompt to `<hostname>#`).
- **Explicit `UNKNOWN`s for multiple context mode:**
  1. **`UNKNOWN` (data-plane reads in `system` execution space):** Per Cisco documentation already recorded in `PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_14_ASA_FORTIGATE.md` (`A0-ASA-19`), *"The system configuration does not include any network interfaces or network settings for itself."* Whether `show conn count` (Check 7), `show traffic` (Check 10), and `show arp statistics` (Check 10) return aggregated appliance counters, return `0`, or fail with `% Invalid input detected` inside the `system` execution space on ASA 9.18 / 9.22 is **unmeasured (`UNKNOWN`)** on the estate (zero multiple-context ASA pairs are enrolled today). See Open Question **Q2**.
  2. **`UNKNOWN` (`show failover history` reason string for `no failover active`):** On `FW-FOXTROT-07`, `show failover history` printed `Set by the config command (failover active)` on the unit becoming active and `Other unit wants me Standby (Set by the config command)` on the unit becoming standby (`ASA_FAILOVER_MEASUREMENT_2026_11_13.md` §4). Whether ASA uses the same reason strings when triggered by `no failover active` is **`UNKNOWN`** until the pilot measurement; no parser rule may depend on the reason text.
  3. **`UNKNOWN` (CLI prompt return vs immediate TCP drop on `no failover active`):** Whether the ASA returns the `#` prompt (or any confirmation/informational line) before moving the Active IP off the interface is **`UNKNOWN`** until the pilot run and is handled fail-closed by §4.2.

### 3.3 Network-Device Command Gate Table (`docs/AI_DEVELOPMENT_PROTOCOL.md`)

All commands below use `vendor = cisco_asa`, `platform_role_scope = cisco_asa_firewall`, `transport_kind = SSH_INTERACTIVE`, `retry_rule = none`, and `safe_telemetry_fields = []` (derived counts/enums/booleans only; raw output is stripped in memory and excluded from `job_transcript` via `JobTranscriptScope.withoutRecording`).

| Gate ID (Status today) | Literal (`canonical_command_key`) | Action Class & Shell/Context | Timeout | Max Frequency per Endpoint (Proposed for Execution) | Session Reuse & Unsupported Behavior | Secret-Output Risk & Official Cisco Citation |
| --- | --- | --- | --- | --- | --- | --- |
| **`asa_failover_inactive_switch`** *(NEW — not yet in DB)* | `no failover active` | `operational-state-change` (`CLASS_2`)<br>`privileged_exec` (single context, or `system` execution space after `changeto system`) | `30 s` | At most **once per failover execution run**, sent only to the verified Active member (`serialOurs == preActiveSerial`). Never sent in `READINESS` runs. | Reuses the Active member's `PRECHECK` SSH session after all 8 blocking pre-checks pass. If refused (`% Invalid input`, `Command authorization failed`) or pre-write failure -> `STOPPED` (`COMMAND_UNAVAILABLE` / `OUTCOME_UNKNOWN`). Never retried. | **None** (emits no configuration or secret).<br>*Citation:* Cisco Secure Firewall ASA Series Command Reference, Commands A–H, **`failover active`** (`no failover active`, privileged EXEC mode). |
| **`asa_failover_changeto_system`** *(NEW — only if PO includes multi-context in Q2; mirrors `asa_sb_changeto_system` V237)* | `changeto system` | `read` (`CLASS_0`)<br>`context` | `10 s` | Once per SSH session preamble on a member whose `show mode` reports `multiple` (at most `pre`, each switch-poll reconnect, and `post`). Never sent on `single` context mode. | Runs in the member's interactive SSH session immediately after `show mode` reports `multiple`; prompt is re-learned (`<hostname>#`). If refused or prompt retains `/context` -> `STOPPED` (`UNSUPPORTED_MODE`). | **None**.<br>*Citation:* Cisco Secure Firewall ASA Series Command Reference, Commands A–H, **`changeto`** (`changeto system`, privileged EXEC mode). |
| **`asa_show_curpriv`** *(SIGNED_OFF, V78)* | `show curpriv` | `read` (`CLASS_0`)<br>`privileged_exec` | `30 s` | Preamble of each SSH session (`pre`, switch-poll reconnects, `post`). *(Widen `max_frequency` from inventory/readiness to include failover execution.)* | First command in every ASA SSH session. Privilege `< 15` or CLI error -> `STOPPED` (`PRIVILEGE_NOT_15`). | **None** (`Current privilege level : 15`, reduced to int).<br>*Citation:* Cisco Secure Firewall ASA Series Command Reference, Commands S, **`show curpriv`**. |
| **`asa_show_mode`** *(SIGNED_OFF, V78)* | `show mode` | `read` (`CLASS_0`)<br>`privileged_exec` | `30 s` | Preamble of each SSH session (`pre`, switch-poll reconnects, `post`). *(Widen `max_frequency`.)* | Second command in every ASA SSH session. Unrecognised mode (or `multiple` if Q2 keeps single-only) -> `STOPPED` (`UNSUPPORTED_MODE`). | **None** (`Security context mode: single|multiple`, reduced to enum).<br>*Citation:* Cisco Secure Firewall ASA Series Command Reference, Commands S, **`show mode`**. |
| **`asa_failover_show_failover`** *(SIGNED_OFF for readiness, V212/V222)* | `show failover` | `read` (`CLASS_0`)<br>`privileged_exec` | `30 s` | **Twice** in `pre` (5 s apart), **once per poll** during `FAILING_OVER` switch confirmation (every 3 s, at most 40 polls / 120 s), and **twice** in `post` (5 s apart) per member. *(Widen `max_frequency` from readiness-only.)* | Runs in the member's interactive session. Refused, timed out, or unparsable -> check is `UNKNOWN` (blocks in `pre`, stops in `post`); during switch poll, transient drop waits for next poll up to 120 s cap. | Names interfaces, IP addresses, and serials; reduced in memory to role/link/interface counts, counter deltas, and `MATCH`/`MISMATCH` booleans.<br>*Citation:* Cisco Secure Firewall ASA Series Command Reference, Commands S, **`show failover`**. |
| **`asa_failover_show_failover_state`** *(SIGNED_OFF for readiness, V212/V222)* | `show failover state` | `read` (`CLASS_0`)<br>`privileged_exec` | `30 s` | Once in `pre` and once in `post` per member per run. *(Widen `max_frequency`.)* | Same session per phase (`pre`, `post`). Refused, timed out, or missing `====Configuration State===` -> Check 5 `UNKNOWN` (blocks/stops). | **None** (state words and historical failure timestamps; reduced to sync counts).<br>*Citation:* Cisco Secure Firewall ASA Series Command Reference, Commands S, **`show failover state`**. |
| **`asa_failover_show_failover_history`** *(SIGNED_OFF for readiness, V212/V222)* | `show failover history` | `read` (`CLASS_0`)<br>`privileged_exec` | `60 s` | Once in `pre` and once in `post` per member per run. *(Widen `max_frequency`.)* | Same session per phase (`pre`, `post`); space-paged (`<--- More --->` answered in session, `terminal pager 0` never sent per V233). Unparsable -> Check 9 `UNKNOWN` (informational). | **None** (timestamps, state transitions, reason words; reduced to entry count and newest timestamp age).<br>*Citation:* Cisco Secure Firewall ASA Series Command Reference, Commands S, **`show failover history`**. |
| **`asa_failover_show_conn_count`** *(SIGNED_OFF for readiness, V212/V222)* | `show conn count` | `read` (`CLASS_0`)<br>`privileged_exec` | `30 s` | Once in `pre` and once in `post` per member per run. *(Widen `max_frequency`.)* | Same session per phase (`pre`, `post`). Refused or unparsable -> Check 7 `UNKNOWN` (blocks/stops). *(Multi-context behavior in `system` space: `UNKNOWN`, see Q2.)* | **None** (`N in use, M most used`; reduced to integer `inUse`).<br>*Citation:* Cisco Secure Firewall ASA Series Command Reference, Commands S, **`show conn` (`show conn count`)**. |
| **`asa_failover_show_traffic`** *(SIGNED_OFF for readiness, V248/V249)* | `show traffic` | `read` (`CLASS_0`)<br>`privileged_exec` | `30 s` | Once in `pre` (on pre-switch Active) and once in `post` (on post-switch new Active) per run. *(Widen `max_frequency`.)* | Same session on the Active member of that phase, after the failover reads. Unparsable -> Check 10 `UNKNOWN`. *(Multi-context in `system` space: `UNKNOWN`, see Q2.)* | Names interfaces; reduced in memory to summed 1-minute input + output `bytes/sec`.<br>*Citation:* Cisco Secure Firewall ASA Series Command Reference, Commands S, **`show traffic`**. |
| **`asa_failover_show_arp_statistics`** *(SIGNED_OFF for readiness, V248/V249)* | `show arp statistics` | `read` (`CLASS_0`)<br>`privileged_exec` | `30 s` | Once in `pre` (on pre-switch Active) and once in `post` (on post-switch new Active) per run. *(Widen `max_frequency`.)* | Same session on the Active member of that phase. Unparsable -> Check 10 `UNKNOWN`. *(Multi-context in `system` space: `UNKNOWN`, see Q2.)* | **None** (counters only; reduced in memory to `Number of ARP entries` integer).<br>*Citation:* Cisco Secure Firewall ASA Series Command Reference, Commands S, **`show arp statistics`**. |

---

## 4. Target Connection, SSH Session Drop on Role Swap, and Reconnect Verification

### 4.1 Management IP Swap and Serial-Bound Identity Model
In a Cisco ASA Active/Standby pair (`CLS-TANGO-01`), the two enrolled device rows (`FW-TANGO-04` at `192.0.2.10` and `FW-TANGO-05` at `192.0.2.11`) represent the pair's **Active and Standby management address slots**, not fixed physical chassis (`PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md`):
1. When a switchover occurs, the **Active IP (`192.0.2.10`) moves to the new Active physical chassis**, and the **Standby IP (`192.0.2.11`) moves to the new Standby physical chassis**.
2. Across all 5 enrolled ASA pairs in the estate, both addresses present the **same SSH host key** (measured 2026-11-12, `FAILOVER_FORTIGATE_ASA_DESIGN_DRAFT.md` §10 item 2), so SSH host-key verification (`PersistedManagementEndpointTrustResolver`) succeeds on both addresses before and after the switch.
3. Physical units are identified exclusively by their **opaque hardware serial** (`Serial Number: Ours <serial>, Mate <serial>` in `show failover`, compared by exact string equality against `ha_pair_identity(serial_low, serial_high)`, V209) and their failover unit designator (`Primary` / `Secondary` in `This host: <Primary|Secondary> - <State>`).

### 4.2 Which Unit neXus Connects to for the Switch and What Happens to the SSH Session
1. **Pre-check binding (`PRECHECK`):** The worker opens one trusted SSH session to each enrolled endpoint (`endpoint_0` at `192.0.2.10`, `endpoint_1` at `192.0.2.11`), runs the preamble and `PRECHECK` reads, and binds in memory:
   - `preActiveEndpoint`: the endpoint whose `show failover` reported `This host: <preActiveUnitLabel> - Active`,
   - `preActiveSerial`: `serialOurs` reported on `preActiveEndpoint`,
   - `preStandbyEndpoint`: the peer endpoint whose `show failover` reported `This host: <preStandbyUnitLabel> - Standby Ready`,
   - `preStandbySerial`: `serialOurs` reported on `preStandbyEndpoint`.
2. **Sending the switch (`FAILING_OVER`):** The worker sends `no failover active` over the already-open, identity-verified SSH session on `preActiveEndpoint` (`192.0.2.10`).
3. **SSH session behavior when the role changes:** As soon as the physical unit `preActiveSerial` executes `no failover active`, it relinquishes `192.0.2.10` and assumes `192.0.2.11`, while `preStandbySerial` assumes `192.0.2.10`. Because the IP endpoints swap across the two physical units, **both open SSH sessions (`preActiveEndpoint` and `preStandbyEndpoint`) become invalid**: the active session either drops (`EOF` / TCP reset / channel timeout) before printing the next `#` prompt, or prints the prompt just before the IP moves and hangs on subsequent traffic.
4. **Required dispatch-state handling (`SESSION_DROPPED_AFTER_SEND`):**
   - Today's `JooqCpFailoverRepository.dispatch()` treats any non-completed `send.get()` as `delivery = 'UNKNOWN'` and immediately aborts the run to `STOPPED` / `OUTCOME_UNKNOWN` before polling (`ARGON_FAILOVER_FORTIGATE_ASA_REVIEW.md` §1a).
   - For ASA (`FailoverVendor.CISCO_ASA`), once the interactive SSH transport has flushed the bytes `no failover active\n` to the channel:
     - A failure *before* flushing bytes (e.g. `FailoverMutationSwitch` disabled or channel already closed) throws `NotSent`, recording `delivery = 'NOT_SENT'` and stopping cleanly with no incident.
     - A clean prompt return records `delivery = 'REPLY_RECEIVED'`.
     - A channel EOF, reset, or read timeout *after* the command bytes were flushed records `delivery = 'SESSION_DROPPED_AFTER_SEND'` and `job_step_attempt.outcome = 'SENT_AWAITING_OBSERVATION'` **without** calling `unknown(...)` yet.
     - Both `preActiveEndpoint` and `preStandbyEndpoint` SSH sessions are immediately closed (`ssh.disconnect`) in `finally` before polling begins.

### 4.3 How the Run Reconnects and Verifies the Switch
1. **Switch-confirmation poll loop:** After `dispatch()` returns (`REPLY_RECEIVED` or `SESSION_DROPPED_AFTER_SEND`), the worker polls **every `3 s`, up to `40` polls (`120 s` total budget)**:
   - On each poll, the worker opens a **new** trusted SSH session (`CONNECT = 30 s`) to each of the two enrolled endpoints (`192.0.2.10` and `192.0.2.11`), runs the preamble (`show curpriv`, `show mode`, and `changeto system` if multi-context), and runs one `show failover` read on each endpoint.
   - If either connection or `show failover` fails transiently while gratuitous ARPs and management IPs are moving (or while a unit is in `Just Active`, `Active Drain`, `Active Applying Config`, or `Active Config Applied`), the worker disconnects, increments `polls`, sleeps `3 s`, and retries on the next poll iteration.
2. **Two-sided serial-matched confirmation criteria:** A poll pass confirms the switch (`SWITCHED`) if and only if:
   - Observer 1 and Observer 2 report valid `show failover` outputs whose `(serialOurs, serialMate)` sets match `ha_pair_identity` and mirror each other (`AsaFailoverChecks.identity` is `PASS`, so the two endpoints answered from the two distinct physical units of the pair),
   - The observer whose `serialOurs == preStandbySerial` (now answering on `preActiveEndpoint`, `192.0.2.10`) reports `This host: <preStandbyUnitLabel> - Active` and `Other host: <preActiveUnitLabel> - Standby Ready`, and
   - The observer whose `serialOurs == preActiveSerial` (now answering on `preStandbyEndpoint`, `192.0.2.11`) reports `This host: <preActiveUnitLabel> - Standby Ready` and `Other host: <preStandbyUnitLabel> - Active`.
3. **Dispatch confirmation:** Once those three conditions hold in the same poll pass, `confirmDispatch(dispatch)` updates `failover_dispatch_intent` to `observation = 'CONFIRMED'` (where `delivery IN ('REPLY_RECEIVED', 'SESSION_DROPPED_AFTER_SEND') AND observation = 'NOT_OBSERVED'`), and the run advances to `SWITCHED` and then `POSTCHECK`. If all 40 polls (`120 s`) elapse without meeting these criteria, the worker aborts with `Stop("FAILOVER_TIMEOUT", 1)`, which transitions the run to `STOPPED` / `OUTCOME_UNKNOWN` and opens a `failover_quarantine` incident.

---

## 5. Preconditions

### 5.1 Request-Time Admission Preconditions (Server-Side, Before Queuing a Job)
Checked atomically in `CpFailoverService.request` and `JooqCpFailoverRepository.requestBound` under `pg_advisory_xact_lock(136, 1)`:
1. **Mutation switch & vendor availability:** `FailoverMutationSwitch` is enabled (`FAILOVER_MUTATION_DISABLED`), and `FailoverVendor.CISCO_ASA` execution is enabled.
2. **Role, request binding, approval window & four-eyes:** Caller holds `security_admin` (`ADMIN_SINGLE`) or `operation_admin` (`OPERATION_ADMIN_TWO_PERSON` with second distinct admin approval already recorded), `warningConfirmed = true`, `execution_nonce` and `request_revision = 1` match, approval is not revoked (`APPROVAL_REVOKED`), and `window_from <= now < window_until` (`OUTSIDE_WINDOW` / `WINDOW_EXPIRED`).
3. **Business-hours confirmation / job window:** Inside `08:00–20:00 Europe/Istanbul`, `X-Nexus-Business-Hours-Ack: true` is present (`BUSINESS_HOURS_CONFIRM_REQUIRED`); for a scheduled run, `scheduledFor` falls inside a 6-hour job window.
4. **Two eligible, SSH-trusted members & unchanged member set:** Exactly two enrolled `cisco_asa` members share `cluster_ref`, match the approval's `member_set_revision` (`MEMBER_SET_CHANGED`), have completed inventory (`INVENTORY_REQUIRED`), and both endpoints have active SSH host-key trust (`TRUSTED_HOST_KEY_REQUIRED`).
5. **No active run, incident, or unresolved dispatch:** No open run on the unit (`RUN_ALREADY_ACTIVE`), no active `failover_quarantine` incident on the cluster or either member (`OPEN_INCIDENT`), no estate-wide unresolved dispatch (`UNRESOLVED_DISPATCH`), and no other fleet failover/resume in flight (`FLEET_MUTATION_ACTIVE`).
6. **Latest readiness run `READY` and fresh (PO decision Q4, approved 2026-11-23, §11):**
   - Mirroring `PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md` §3.1 R8, the unit's most recent finished `READINESS` run must have `outcome = 'READY'`, all blocking checks `1..8` `PASS`, and `finished_at` within the last **60 minutes** (refused with `READINESS_REQUIRED` if no readiness run exists, `READINESS_NOT_READY` if `NOT_READY` or `UNKNOWN`, or `READINESS_NOT_FRESH` if `finished_at < now - 60 min`).
   - *Invariant (`AGENTS.md`, `FAILOVER_EXECUTION_CP_CONTRACT.md` §13):* A fresh, `READY` standalone readiness run is a request-admission gate only; it **never** substitutes for the execution job's own fresh `PRECHECK` pass below.

### 5.2 Worker Pre-Check Preconditions (`PRECHECK` Phase, Immediately Before the Write)
Before preparing any dispatch intent, `AsaFailoverJobExecutor` verifies all gate rows (`COMMAND_GATE_UNAVAILABLE`), connects to both endpoints (`TRUSTED_CONNECTION_REQUIRED`), verifies privilege 15 (`PRIVILEGE_NOT_15`) and context mode (`UNSUPPORTED_MODE`), and executes the full readiness read battery. **Every blocking check (1–8) must evaluate to `PASS`:**

| Check # | Name | Read(s) | `PRECHECK` PASS Precondition (Any `FAIL` or `UNKNOWN` Stops Before Write) |
| --- | --- | --- | --- |
| **1** | Failover state and roles *(blocking)* | `show failover` | Both observers report `Failover On`, zero failover group lines (`groupLines == false`), views reciprocally mirrored (`a.thisHost == b.otherHost && b.thisHost == a.otherHost`), and across the pair **one unit is `Active` and the other is `Standby Ready`**. |
| **2** | Pair identity *(blocking identity gate)* | `show failover` + `ha_pair_identity` | Both observers report non-blank `Serial Number: Ours <s1>, Mate <s2>`; `a.serialOurs == b.serialMate`, `a.serialMate == b.serialOurs`, `a.serialOurs != a.serialMate`; and both serials equal the recorded `ha_pair_identity(serial_low, serial_high)` set by exact string equality (`IDENTITY_MATCH`). |
| **3** | Failover and state links *(blocking)* | `show failover` | Both `Failover LAN Interface: ... (up)` and stateful `Link : ... (up)` are `up` on both observers (`linksUp == 4`). |
| **4** | Monitored interfaces *(blocking)* | `show failover` | Every monitored interface under both hosts on both observers is `Normal (Monitored)` (`bad == 0`, `unrecognised == 0`, `monitoredNormal >= 1`). |
| **5** | Configuration sync *(blocking)* | `show failover state` | Both observers have `====Configuration State===` with `Sync Done` / `Sync Done - STANDBY` (or `Sync Skipped`, ASA 9.18+ Config Sync Optimization), `notDone == 0`, `unrecognised == 0`. |
| **6** | Stateful replication *(blocking)* | `show failover` (2 samples, 5 s apart) + `show conn count` | Neither `xerr` nor `rerr` increased between sample 1 and sample 2 on any row of either observer (`increasedCounters == 0`); if `activeInUse > 0`, Active's cumulative `General.xmit > 0` and Standby's cumulative `General.rcv > 0`. |
| **7** | Sessions carried *(blocking)* | `show conn count` | Both counts parse (`N in use, M most used`). If `activeInUse == 0`, `PASS`; if `< 10,000`, `|active - standby| < 2,000` or `standby / active >= 50%`; if `>= 10,000`, `standby / active >= 80%`. Recorded as `preActiveInUse` baseline. |
| **8** | Version parity *(blocking)* | `show failover` | `Version: Ours <v>, Mate <v>` present, exact-string-equal on each observer, and mirrored across both observers (`VERSIONS_MATCH`). |
| **9** | Last failover *(informational)* | `show failover history` | Newest entry (last timestamp in oldest-first list) parsed: `PASS` if `> 6 h` old, `WARN` if `<= 6 h` old, `UNKNOWN` if empty/unresolvable. Never blocks `PRECHECK`. |
| **10** | Traffic and ARP *(baseline)* | `show traffic`, `show arp statistics` (on Active) | Summed 1-min input + output `bytes/sec` and `Number of ARP entries` recorded on the pre-switch Active unit as the `pre` baseline for `POSTCHECK`. Never blocks `PRECHECK`. |

---

## 6. Post-Checks, Expected Role Swap, Timings, and Outcome Classification

### 6.1 Post-Checks (`POSTCHECK` Phase)
Once the switch-confirmation poll loop (§4.3) confirms the role swap and calls `confirmDispatch()`, the worker transitions the run to `POSTCHECK` and re-runs the full read battery on both reconnected endpoints (`show failover` twice 5 s apart, `show failover state`, `show failover history`, `show conn count` on both members; `show traffic` and `show arp statistics` on the **new Active** member, whose `serialOurs == preStandbySerial`):

1. **Check 1 (Expected role swap — blocking):** Both observers report `Failover On` and mirrored views; the unit with **`serialOurs == preStandbySerial` MUST be `Active`**, and the unit with **`serialOurs == preActiveSerial` MUST be `Standby Ready`**. Any other role pair (e.g., revert to `preActiveSerial`, dual-active, or `Failed` / `Cold Standby`) fails Check 1 (`UNEXPECTED_ROLES`).
2. **Checks 2, 3, 5, 6, 8 (blocking):** Same PASS rules as `PRECHECK` (§5.2), evaluated on the post-switch observations (with Check 8 additionally verifying version continuity against `pre`, and Check 6 verifying `General.xmit > 0` on the new Active and `General.rcv > 0` on the new Standby).
3. **Check 4 (Monitored interfaces — blocking):** Same PASS rule as `PRECHECK`, plus the total `monitoredNormal` count across both hosts must not have decreased from `PRECHECK`.
4. **Check 7 (Post-switch sessions carried — blocking):**
   - **Carry across the switch (new Active vs `preActiveInUse`):** Applies the low-volume-aware tolerance (`AsaFailoverChecks.sessions` / `FAILOVER_EXECUTION_CP_CONTRACT.md` §3, §16): if `preActiveInUse == 0`, `PASS`; if `preActiveInUse < 10,000`, `PASS` when `|newActiveInUse - preActiveInUse| < 2,000` OR `newActiveInUse * 100 >= preActiveInUse * 50`; if `preActiveInUse >= 10,000`, `PASS` when `newActiveInUse * 100 >= preActiveInUse * 80`.
   - **Replication to the new Standby (new Standby vs new Active):** Evaluated by the same tolerance so post-switch stateful sync to the new Standby is proven.
5. **Check 9 (Last failover — informational):** Recorded in `post`; a timestamp within the last 6 hours (`WARN` / `WITHIN_6H`) is expected after a switchover and never blocks.
6. **Check 10 (Post-switch Traffic and ARP on the new Active — see Q5):** Compares `newActiveTrafficBytesPerSecond` and `newActiveArpEntries` against the `pre` phase Active baseline (`preTrafficBytesPerSecond`, `preArpEntries`) using `TrafficArpCheck`:
   - `trafficRatio = newActiveTraffic / preTraffic` (flagged `trafficDropped = true` if `< 0.50` when `preTraffic > 0`),
   - `arpRatio = newActiveArp / preArp` (flagged `arpDropped = true` if `< 0.80` / `< 0.50` when `preArp > 0`).
   - *Enforcement (PO decision Q5):* Option A (mirror Check Point Check 8) makes a post-switch traffic drop below `50%` (when `preTraffic > 0`) blocking in `POSTCHECK` while keeping ARP informational (`FAILOVER_EXECUTION_CP_CONTRACT.md` §3, §15); Option B keeps Check 10 informational in both phases and relies on Check 7 (`show conn count`) as the blocking session-continuity gate.

### 6.2 Timings Summary
| Parameter | Value | Constant / Reference |
| --- | --- | --- |
| SSH connect timeout / preamble timeout | `30 s` / `30 s` (`10 s` for `changeto system`) | `AsaFailoverJobExecutor.CONNECT`, `PREAMBLE` |
| Pause between consecutive CLI commands on a member | `2 s` (configurable) | `commandPause` (`FAILOVER_EXECUTION_CP_CONTRACT.md` §13) |
| Gap between sample 1 and sample 2 of `show failover` | `5 s` | `AsaFailoverJobExecutor.SAMPLE_GAP` |
| Read timeouts (`show failover`, `state`, `conn count`, `traffic`, `arp` / `history`) | `30 s` / `60 s` | `V212`, `V248` |
| Write timeout (`no failover active`) | `30 s` | Proposed gate `asa_failover_inactive_switch` |
| Switch-confirmation poll interval and maximum budget | Every `3 s`, up to `40` polls (`120 s` max) | `FAILOVER_FORTIGATE_ASA_DESIGN_DRAFT.md` §5.1, §10 item 5; PO decision Q6 (approved 2026-11-23, §11) |

### 6.3 Classification of `SUCCEEDED` / `FAILED` / `UNKNOWN`
| Scenario | `failover_run.state` | `failover_run.outcome` & `message` | `jobs.state` | `failover_quarantine` Incident? |
| --- | --- | --- | --- | --- |
| All `PRECHECK` 1–8 `PASS` -> `no failover active` sent -> serial-verified role swap confirmed within `120 s` -> all `POSTCHECK` blocking checks `PASS` | `DONE` (`step = 'DONE'`) | `outcome = 'SUCCEEDED'`<br>`message = 'NO_PROBLEMS_FOUND'` | `COMPLETED` | **No** (clean completion; roles stay swapped). |
| Any failure or refusal **before** `mutation_possible = true` (or `NotSent` before bytes leave neXus): e.g. `PRECHECK` check 1–8 `FAIL` or `UNKNOWN`, `WINDOW_EXPIRED`, `TRUSTED_CONNECTION_REQUIRED`, `PRIVILEGE_NOT_15`, `UNSUPPORTED_MODE`, `BUSINESS_HOURS_CONFIRM_REQUIRED`, `MUTATION_DISABLED_BEFORE_SEND` | `STOPPED` (`step = 'PRECHECK'` or `'FAILING_OVER'`, `mutation_possible = false`) | `outcome = <stop_code>` (e.g. `CHECK_NOT_READY`, `COMMAND_UNAVAILABLE`, `MUTATION_DISABLED_BEFORE_SEND`)<br>`failed_check = <1..8 or null>` | `FAILED` | **No** (device state was never mutated). |
| Any failure, timeout, or unparsable/failed check **after** `mutation_possible = true`: e.g. `FAILOVER_TIMEOUT` (120 s poll budget exhausted), `UNEXPECTED_ROLES` (split-brain or revert), `POSTCHECK` check `FAIL` or `UNKNOWN`, `COMMAND_UNAVAILABLE` in `POSTCHECK`, `DISPATCH_OWNER_LOST`, or worker crash | `STOPPED` (`mutation_possible = true`) | `outcome = 'OUTCOME_UNKNOWN'`<br>`message = <stop_code>`<br>`failed_check = <1..10 or null>` | `OUTCOME_UNKNOWN` | **Yes — active incident opened automatically** (`V136` / `V169`), fencing the unit (and fleet if dispatch observation is unconfirmed). |

---

## 7. Recovery, Failback, and Point of No Return

1. **Point of no return:**
   - The mutation linearization boundary in `JooqCpFailoverRepository.prepareDispatch` + flushing `no failover active` to the SSH channel in `dispatch` is the **point of no return**.
   - Prior to that point (`mutation_possible = false`, or `NotSent`), any stop is a clean pre-mutation abort. Once `no failover active` is written to the SSH channel, neXus never cancels, reverses, or replays the write automatically.
2. **Behavior when a post-check (or switch confirmation) fails — stop and report, never automatic retry:**
   - Per `FAILOVER_EXECUTION_CP_CONTRACT.md` §1 step 7, `PO_DECISION_RECORD_2026_09_27_CP_FAILOVER_EXECUTION.md` item 2, and `PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md` item 5.7, if the switch poll times out or any blocking `POSTCHECK` fails or returns `UNKNOWN`, neXus **stops immediately**, sends no further command, marks the run `STOPPED` (`outcome = 'OUTCOME_UNKNOWN'`), opens an active `failover_quarantine` incident, and notifies via the `job_failure` route.
3. **Why "Resume return step" (`failover_resume`) is not applicable to Cisco ASA:**
   - Check Point (`clusterXL_admin down` -> `DOWN`) and Palo Alto (`<suspend/>` -> `suspended`) leave the former active unit in an administratively disabled state until a second write (`clusterXL_admin up` / `<functional/>`) returns it to standby/passive.
   - Cisco ASA `no failover active` transitions the former active unit directly to `Standby Ready` in a single step; there is no `RETURNING` phase and no second write. `FailoverVendor.CISCO_ASA.resumeSupported()` stays `false`, and `/runs/{runId}/resume-return` continues to refuse `cisco_asa` (`RESUME_RUN_NOT_ELIGIBLE`).
4. **Failback (switching the active role back to the original physical unit):**
   - **Command symmetry:** Because `no failover active` always instructs the *current* `Active` unit to step down to `Standby Ready` so its `Standby Ready` peer takes over, **failback uses the exact same command (`no failover active`)** sent to the *new* Active physical unit (`preStandbySerial`, which now answers on the Active management IP `192.0.2.10`). No second write literal (`failover active`) is needed.
   - **When failback is offered and who approves it:**
     - **After a `SUCCEEDED` (`DONE`) run:** Offered immediately via the standard **Start failover** button in the UI. It requires a new request-bound approval window (`security_admin` single-admin or `operation_admin` four-eyes second approval), business-hours confirmation if inside `08:00–20:00 Europe/Istanbul`, and a fresh `PRECHECK` pass where all checks 1–8 `PASS` (with `preStandbySerial` now verified as the starting `Active` and `preActiveSerial` as `Standby Ready`).
     - **After a `STOPPED` (`OUTCOME_UNKNOWN`) run:** While the run's `failover_quarantine` incident is active, all new failover admissions on the pair are refused with `OPEN_INCIDENT` (and estate-wide if the dispatch was not observed, `UNRESOLVED_DISPATCH`). Failback is **not offered** until:
       1. An operator or administrator runs a read-only **Run readiness check** (which remains allowed while an incident is open) or inspects the pair out of band to confirm both units' state,
       2. A **`security_admin`** (never `operation_admin`, `operator`, or `aiview`) explicitly executes **Release incident** (`cp_failover_incident_release`, with a mandatory 1–500 character reason recorded in `failover_quarantine` / `job_reconciliation` and `audit_log`, per `PO_DECISION_RECORD_2026_10_20_FAILOVER_PILOT_P0.md` item 1), and
       3. A fresh readiness check evaluates to `READY`, after which a new approved failover run may be started under normal `security_admin` / four-eyes `operation_admin` approval.

---

## 8. UI Flow under Operations › HA and Readiness

All UI verification, screenshots, and PO sign-offs run exclusively under the masked `aiview` persona (`AGENTS.md` AIView law), displaying deterministic pseudonyms (`CLS-TANGO-01`, `FW-TANGO-04`, `FW-TANGO-05`).

1. **Operations › HA and readiness list (`HaReadinessList.tsx`):**
   - Enrolled ASA pairs appear alongside Check Point and Palo Alto clusters with vendor badge `Cisco ASA`, status chip (`Ready` / `Not ready` / `Unknown`), current Active and Standby member labels, primary reason, and readiness evaluation age (`READINESS_STALE_AFTER_MS = 24 h` visual indicator).
   - Row action and bulk action **"Run readiness check"** remain available to `operator`, `operation_admin`, `security_admin`, and `aiview` (inside job windows for `operator`/`aiview`; with admin window bypass and `08:00–20:00` business-hours prompt for admins).
2. **Detail drawer (`OperationsScreen.tsx` -> `CpFailoverPanel.tsx`):**
   - Clicking an ASA cluster row opens the right-hand `HA readiness detail` drawer and renders `CpFailoverPanel` with `vendor="cisco_asa"` (backed by `/api/v2/asa-failover/{units,summary,approvals,runs,incidents}`), replacing the current readiness-only placeholder notice.
   - **Unit card header (`UnitPanel`):** Shows `CLS-TANGO-01`, `Cisco ASA · Mode: Active/Standby · Readiness age: <relative age>`. (The Check Point-only `Recovery mode: Active Up` badge is omitted for `cisco_asa`, just as it is omitted for `palo_alto`.)
   - **Incidents banner (`FailoverIncidentsPanel`):** Displays any active incident or unresolved dispatch on the unit, offering **Release incident** (with mandatory reason dialog) to `security_admin` and showing `"Only a security_admin can release a failover incident."` to other roles.
   - **Approval & Start controls (hidden when `masked === true` for `aiview`):**
     - **Start failover** button opens the **"Start manual failover"** dialog showing:
       - `Target: CLS-TANGO-01`
       - ASA-specific swap description: *"Intended role swap: current Active unit transitions to Standby Ready (`no failover active`); current Standby Ready unit becomes Active. Management IP addresses move with the Active/Standby roles; there is no separate return step."*
       - `Window ends` (`Europe/Istanbul`) and `Reason` (1–500 chars), plus `OK / Start`.
     - For `security_admin` (`ADMIN_SINGLE`), `OK / Start` creates the request-bound approval and immediately submits `POST /api/v2/asa-failover/runs` (prompting for `X-Nexus-Business-Hours-Ack` if between `08:00–20:00 Europe/Istanbul`).
     - For `operation_admin` (`OPERATION_ADMIN_TWO_PERSON`), `OK / Start` records the request (`Awaiting second approval`); a second distinct administrator clicks **`Approve request revision 1`**, after which the initiator starts the run.
   - **Live run stepper & check comparison (`FailoverRun`):**
     - Displays the 5-step horizontal stepper (omitting `RETURNING` and `POST_RETURN`, which do not exist on ASA):
       `Checking readiness` (`PRECHECK`) -> `Failing over` (`FAILING_OVER`) -> `Role change confirmed` (`SWITCHED`) -> `Checking traffic and cluster after switch` (`POSTCHECK`) -> `Failover verified` (`DONE`).
     - Renders two check tables via `ReadinessChecksTable`: **Pre-checks** (`phase = "pre"`, checks 1–10) and **Post-switch checks** (`phase = "post"`, checks 1–10), plus `Last confirmed roles` per member. No `ResumeReturn` card is rendered for `cisco_asa`.

---

## 9. Real-Environment Validation Plan (One Attended Pilot Pair)

Per `AGENTS.md` ("Automated validation and real-environment validation are separate gates"), ASA failover execution cannot be marked `REAL_ENV_VALIDATED` or `DONE` until an attended pilot run succeeds on a real ASA pair:

1. **Pilot target & prerequisites:**
   - **Target pair:** One quiet, PO-selected enrolled Active/Standby pair — recommended candidate: `FW-FOXTROT-07` (masked cluster `CLS-TANGO-01`, members `FW-TANGO-04` at `192.0.2.10` and `FW-TANGO-05` at `192.0.2.11`), which passed the one-time read-only measurement on 2026-11-13 (`ASA_FAILOVER_MEASUREMENT_2026_11_13.md`: 0 config changes in 14 days, 5 monitored interfaces `Normal`, `Sync Done`, ~4,778 active / ~3,801 standby connections).
   - **Window & sign-off:** Scheduled maintenance window outside `08:00–20:00 Europe/Istanbul` (or with PO business-hours confirmation), with stakeholder change-management sign-off (`DR item 368`), PO live attendance, and a named network recovery owner standing by.
   - **Pre-deploy database dry-run:** Run the new gate/vendor migration in `BEGIN ... ROLLBACK` on the live PostgreSQL database on `HOST-A` before deployment.
2. **Pilot execution steps:**
   - **Step 1 (Pre-flight readiness under `aiview`):** Trigger an on-demand readiness run on `CLS-TANGO-01` from Operations › HA & readiness; confirm `READY` with all checks `1..8` `PASS` and Checks 9–10 populated.
   - **Step 2 (First switchover — Primary -> Secondary):** Authenticated admin initiates **Start failover** on `CLS-TANGO-01`. Observe live transition `PRECHECK -> FAILING_OVER -> SWITCHED -> POSTCHECK -> DONE (SUCCEEDED)`.
   - **Step 3 (Verify swapped steady state):** Confirm from the run detail and a follow-up standalone readiness run that `Secondary` is now `Active` (answering on `192.0.2.10`), `Primary` is now `Standby Ready` (answering on `192.0.2.11`), `ha_pair_identity` Check 2 is `PASS` (`IDENTITY_MATCH`), Check 5 is `Sync Done` (or `Sync Skipped`), and Check 7 / Check 10 show connections and traffic carried on the new Active.
   - **Step 4 (Controlled failback — Secondary -> Primary):** Within the same maintenance window, initiate a second approved failover run on `CLS-TANGO-01` to move `Active` back to `Primary`, verifying full symmetry and restoring the pre-maintenance active unit.
3. **Masked evidence to capture (AIView mentality — no raw values):**
   - `aiview` screenshots of the HA & readiness row, the approval dialog, the 5-step `FailoverRun` stepper at `DONE`, and the `Pre-checks` / `Post-switch checks` derived tables for both the switchover and failback runs.
   - Derived database/log facts only: `run_id`, `job_id`, `approval_id`, `failover_dispatch_intent.delivery` (`REPLY_RECEIVED` vs `SESSION_DROPPED_AFTER_SEND`), `observation` (`CONFIRMED`), number of switch-confirmation polls and elapsed seconds to `SWITCHED`, and per-check `(check_no, phase, status, reason)` tuples. **Never** capture raw CLI transcripts, real serial numbers, real management IPs, or unmasked hostnames.

---

## 10. Open Questions for the Product Owner

1. **Q1 (Write literal and read frequency sign-off):** Do you approve the single state-changing command literal **`no failover active`** (`privileged_exec`, sent once per run to the verified Active unit, `CLASS_2_OPERATIONAL_STATE_CHANGE`, timeout `30 s`, retry `none`, gate id `asa_failover_inactive_switch`) and widening `max_frequency` on the six already-approved ASA readiness reads (`show failover`, `show failover state`, `show failover history`, `show conn count`, `show traffic`, `show arp statistics`) plus preamble reads (`show curpriv`, `show mode`) to cover the `PRECHECK`, switch-confirmation polling (`show failover` every `3 s` up to `40` polls / `120 s`), and `POSTCHECK` phases of an execution run?
2. **Q2 (Single-context vs multiple-context scope in Release 1):** All 5 enrolled ASA pairs today run in `single` context mode, and `AsaFailoverJobExecutor` currently refuses `multiple` context mode. Should we **(a) keep Release 1 scoped to single-context mode** (matching shipped Phase 1 readiness), or **(b) include multiple-context mode (`changeto system`) now**? If (b), because the ASA `system` execution space has no data-plane interfaces or connections of its own (`A0-ASA-19`), should Check 7 (`show conn count`) and Check 10 (`show traffic`, `show arp statistics`) be recorded as `NOT_EVALUATED` in multiple-context mode, or should neXus iterate `changeto context <name>` across contexts?
3. **Q3 (Session-dropping write dispatch state):** Because `no failover active` immediately moves the Active management IP to the peer chassis, the SSH session sending the write may drop before returning a CLI prompt. Do you approve adding the `SESSION_DROPPED_AFTER_SEND` delivery state to `failover_dispatch_intent` (confirmed only when two-sided serial-verified `show failover` polling proves the role swap within `120 s`, and otherwise quarantined as `OUTCOME_UNKNOWN`), subject to the decision-council and code-review gates (`PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md` item 6)?
4. **Q4 (Request-time readiness freshness bound):** Should `POST /api/v2/asa-failover/runs` require the unit's latest standalone readiness run to be `READY` and finished within the last **60 minutes** (`READINESS_REQUIRED` / `READINESS_NOT_FRESH`, matching `PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md` R8) in addition to the execution worker's mandatory fresh `PRECHECK` battery, or rely solely on the execution worker's fresh `PRECHECK` battery as current CP/PAN failover execution does?
5. **Q5 (Post-check Traffic and ARP enforcement — Check 10):** In `POSTCHECK` on the new Active unit, should a traffic rate (`show traffic`) below `50%` of the `pre` Active baseline **(a) block the run (`FAIL` -> `STOPPED` / `OUTCOME_UNKNOWN` + incident, matching Check Point Check 8) while ARP (`show arp statistics`) stays informational**, or **(b) keep both Traffic and ARP informational (`WARN`)** while Check 7 (`show conn count` session carry) serves as the blocking continuity gate?
6. **Q6 (Switch-confirmation poll budget):** Do you approve the **120-second poll budget** (`show failover` every `3 s`, up to `40` polls per member across new SSH connections, vs `60 s` / `21` polls on CP/PAN) to accommodate TCP/SSH reconnects across the swapped management IPs and ASA's `Active Drain -> Active Applying Config -> Active` transition?
7. **Q7 (Pilot pair and maintenance window):** Do you approve `FW-FOXTROT-07` (masked cluster `CLS-TANGO-01`) as the first attended pilot pair once the contract is frozen and implemented, and which maintenance window should be targeted?

## 11. PO answers (chat 2026-11-18, ~13:50)

- **Q1 approved:** "Onaylıyorum" -- the write literal `no failover active` (gate `asa_failover_inactive_switch`, CLASS_2, privileged
  EXEC, 30 s, retry none, once per execution run on the verified Active unit) and the widened `max_frequency` of the signed-off ASA reads
  for PRECHECK, switch-confirmation polling and POSTCHECK, as stated in §3.3 and Q1. The gate row is written when the contract is frozen.
- **Q2:** Release 1 is **single context mode only** ("Sadece single"); multiple context mode is refused `UNSUPPORTED_MODE` as readiness does.
- **Q5:** **(b) informational only** ("Sadece uyarı"): post-switch Traffic and ARP (Check 10) are `WARN` when below the pre baseline;
  Check 7 (`show conn count` session carry) is the blocking continuity gate.
- Still open: Q3 (session-dropping dispatch state, needs the decision council and code review per the 2026-11-13 phase-0 record), Q4
  (request-time readiness freshness bound), Q6 (120 s poll budget), Q7 (pilot pair and window). The contract stays DRAFT until those are
  answered.

## 11.1 PO answers (chat 2026-11-23, "Üçü de öneri gibi")

The PO accepted the recommendation on all three remaining questions in one answer. The 2026-11-18 "still open" line
above is superseded by this section for Q3, Q4 and Q6.

- **Q3 approved in principle:** add the delivery state `SESSION_DROPPED_AFTER_SEND` to `failover_dispatch_intent` for a
  `no failover active` write whose SSH session drops after the command bytes were written (§4.2 item 4). Such a write
  is confirmed **only** by the two-sided, serial-verified `show failover` polling of §4.3 (both observers answer from
  the two distinct physical units of `ha_pair_identity`, the unit with `preStandbySerial` is `Active` and the unit with
  `preActiveSerial` is `Standby Ready`, in the same poll pass); if the poll budget ends without that, the run stops
  `OUTCOME_UNKNOWN` with an incident exactly as today. This is **not** an implementation authorization: per
  `PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md` item 6 the session-drop dispatch state still goes
  through the decision council and the code-review plugin before it is built or this contract is frozen. Council input:
  `ASA_FAILOVER_Q3_COUNCIL_PACKET_2026_11_23.md`. No code, migration or gate row is written until then.
- **Q4 approved:** `POST /api/v2/asa-failover/runs` requires the unit's latest finished standalone readiness run to be
  `READY` and finished at most **60 minutes** before the request (refused `READINESS_REQUIRED` when none exists,
  `READINESS_NOT_READY` when `NOT_READY` / `UNKNOWN`, `READINESS_NOT_FRESH` when older), as written in §5.1 item 6. This
  is **in addition to** the execution worker's own fresh `PRECHECK` battery (§5.2), never a substitute for it.
- **Q6 approved:** the switch-confirmation poll budget is **120 s**: `show failover` on both endpoints every `3 s`, at
  most `40` polls per member, each poll over a new trusted SSH session (§4.3, §6.2). Budget exhausted ->
  `FAILOVER_TIMEOUT` -> `STOPPED` / `OUTCOME_UNKNOWN` + incident (§6.3).
- **Q7:** the pilot pair (and its maintenance window) is chosen by the PO later. It is **not** a freeze blocker; it
  blocks only the first production run (§9). `FW-FOXTROT-07` / `CLS-TANGO-01` in §9 stays a recommendation, not a
  decision.
- **Remaining before freeze:** the Q3 decision council and code review, then the PO freeze of this contract.
