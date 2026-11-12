<!-- Argon consultation 2026-11-12T21:01:14Z, model gemini-4-argon-high, effort high; unaltered output -->

**Role:** `REVIEWER` (`READ_ONLY_AUDIT`) — **Verdict:** `CHANGES_REQUIRED`

Review of [FAILOVER_FORTIGATE_ASA_DESIGN_DRAFT.md](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/docs/design/FAILOVER_FORTIGATE_ASA_DESIGN_DRAFT.md) against [FAILOVER_EXECUTION_CP_CONTRACT.md](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/docs/design/FAILOVER_EXECUTION_CP_CONTRACT.md), [FAILOVER_EXECUTION_PAN_CONTRACT.md](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/docs/design/FAILOVER_EXECUTION_PAN_CONTRACT.md), [PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/docs/design/PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md), and the live failover codebase.

---

### 1. Safety & Switch Flow

#### (a) ASA Management IP Swap & Session Drop on Write — **UNSAFE / BROKEN AS SPECIFIED**
Two fatal mismatches exist between Draft §5.1/§6 and the live code:

1. **Reply-less writes immediately abort and quarantine the run:**
   Draft §5.1 and §6 claim that when `no failover active` drops the SSH session, *"the dispatch is recorded `MAY_HAVE_BEEN_SENT` and confirmed only by observation, as today."* **That is not how the repository works.**
   - In [JooqCpFailoverRepository.java](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/JooqCpFailoverRepository.java#L830-L853), `MAY_HAVE_BEEN_SENT` exists only while `send.get()` is on the stack. If `send.get()` throws (session drop) or returns `false` (no clean prompt), lines 841–847 set `delivery='UNKNOWN'` and call `unknown(...)` ([L891-L908](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/JooqCpFailoverRepository.java#L891-L908)), which **immediately terminates the run as `STOPPED`/`OUTCOME_UNKNOWN` and opens a V136 quarantine/incident** before polling even begins.
   - Furthermore, `confirmDispatch()` ([L855-L862](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/JooqCpFailoverRepository.java#L855-L862)) enforces `WHERE delivery='REPLY_RECEIVED' AND observation='NOT_OBSERVED'`. It is SQL-impossible to confirm a dispatch whose `delivery != 'REPLY_RECEIVED'`.
   - **Fix:** If Phase 1 proves ASA drops the SSH channel before returning the prompt, `dispatch()`/`confirmDispatch()` must support an explicit `SESSION_DROP_EXPECTED` / `DELIVERY_UNCONFIRMED_AWAITING_OBS` state that defers `unknown(...)` until the post-switch observation budget expires.

2. **Permanent `device_id` ↔ Serial / SSH Host-Key Inversion after Switch:**
   - Every `device_id` binds 1:1 to an `endpoints` row (IP) and stores `devices.recorded_identity_primary` (serial) plus endpoint SSH trust ([JooqDeviceRepository.java](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/device/JooqDeviceRepository.java#L280-L332), [CpFailoverJobExecutor.java](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/failover/CpFailoverJobExecutor.java#L423-L449)).
   - Because ASA has no `RETURNING` step, the Active and Standby IPs **remain permanently swapped across the physical chassis** after `DONE`.
   - Even if the executor relaxes identity matching during the switch poll, after `DONE` row `A` (Active IP) sits on Physical Chassis 2 while `devices.recorded_identity_primary` on row `A` still holds Chassis 1's serial (and if per-unit SSH host keys differ, SSH trust fails immediately on reconnect). Every subsequent readiness, inventory, or backup run on both ASA devices will fail with `IDENTITY_NOT_VERIFIED` (`identity_mismatch_state = 'OPEN'`).
   - **Fix:** Test in Phase 1 whether ASA shares host keys across the pair, and define how `devices.recorded_identity_primary` / `ha_unit_token` reconcile when active/standby IPs move across chassis.

#### (b) FortiGate Primary-Only Evidence & Q2
- **Readiness via single cluster IP violates [AGENTS.md](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/AGENTS.md) Evidence Law** (*"A member's report about its peer != independent peer observation"*). If the secondary's management plane is wedged while FGCP heartbeats still answer, primary-only `get system ha status` reports green.
- **Data-model blocker:** [CpFailoverService.java](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/CpFailoverService.java#L137-L140) and [JooqCpFailoverRepository.java](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/JooqCpFailoverRepository.java#L260-L263) (`enrolledFailoverMembers` [L472](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/JooqCpFailoverRepository.java#L472)) hard-require **two enrolled `device_id`s** (`members.size() != 2` → `CLUSTER_NOT_ELIGIBLE`). A 1-device cluster cannot enter `failover_approval`, `failover_run.target_member_ids`, or `failover_quarantine`.
- **Q2 Answer:** Require **Option 2 (enroll per-member `ha-mgmt-interfaces` as two `devices` rows)**. Reject `execute ha manage <id> <admin>` for switch execution: it uses a dynamic cluster index (not a stable identity), injects interactive credentials inside an existing shell, dies when the primary drops during switch, and cannot corroborate split-brain.

#### (c) FortiGate F1 (`diagnose sys ha reset-uptime`) vs F2 (`execute ha failover set/unset`)
- **Choose F1, reject F2:** Under `override disable`, F2's `execute ha failover unset 1` triggers an immediate re-election that **flips traffic back to the old primary** (causing a second outage), whereas leaving `set 1` latched leaves the cluster in an artificial test state.
- **Missing F1 edge case in Check 8:** In FortiOS FGCP (`override disable`), HA election only prefers uptime when `|uptime_A - uptime_B| > ha-uptime-diff-margin` (default 300 s); within the margin, election falls through to **member priority, then serial number**. If the secondary's HA uptime is `<= ha-uptime-diff-margin` (e.g., recent reboot) and the primary has higher priority or serial, `diagnose sys ha reset-uptime` is a **silent no-op or immediate flap-back**. Check 8 must verify `secondary_ha_uptime > ha-uptime-diff-margin` **and** record both members' configured priorities.

---

### 2. Fit with Existing State Machine, Fences, Quarantine, Resume & Phase 0 Refactor

- **Skipping `RETURNING`:** Valid in `failover_run` (`FAILING_OVER → SWITCHED → POSTCHECK → DONE`), provided `dispatch()`/`confirmDispatch()` is fixed for session-dropping writes (see 1a).
- **Resume (`failover_resume`):** [JooqCpFailoverRepository.java](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/JooqCpFailoverRepository.java#L1147) restricts resume to `vendor = 'check_point'`. Draft §2 must state that `failover_resume` remains rejected (`RESUME_RUN_NOT_ELIGIBLE`) for `cisco_asa` and `fortinet` since neither has a `RETURNING` step.
- **Phase 0 Refactor Gaps (missed in Draft §6):**
  1. **SSH Trust Gate:** [CpFailoverService.java](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/CpFailoverService.java#L424) (`submitReadiness`) and [L605](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/CpFailoverService.java#L605) (`startDue`) gate `requireTrusted(member)` on `if ("check_point".equals(vendor))` because PAN uses HTTPS. ASA and FortiGate use SSH and **must** call `requireTrusted(member)` (`descriptor.requiresSshTrust()`).
  2. **DB Function `ui2_job_module(capability_id)`:** [JooqCpFailoverRepository.java](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/JooqCpFailoverRepository.java#L744) (`owns()`) checks `ui2_job_module(j.capability_id) = 'failover'`. Without a Flyway migration adding `asa_cluster_failover`, `asa_failover_readiness`, `fgt_cluster_failover`, and `fgt_failover_readiness` to `ui2_job_module` (and `failover_run`/`failover_approval` vendor constraints), every worker write will fail with `DISPATCH_OWNER_LOST`.
  3. **Summary lateral query:** `summaryMembers()` ([L141](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/JooqCpFailoverRepository.java#L141)) hardcodes a `pan_supported` lateral check that needs generalization.

---

### 3. Readiness Checks & Command Literals

- **Wrong PASS Rule — ASA Check 5 (Stateful replication):** Requiring active `xmit` to increase across a 5-second window will **false-FAIL on low-traffic pairs during quiet maintenance windows**. Match PAN's rule ([FAILOVER_EXECUTION_PAN_CONTRACT.md](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/docs/design/FAILOVER_EXECUTION_PAN_CONTRACT.md#L64)): `err == 0` in the delta window and cumulative `xmit > 0` / `rcv > 0` (when `conn count > 0`).
- **Wrong PASS Rule — ASA/FGT Check 6 (Session count 80%):** On nearly idle pairs (e.g., 4 sessions), losing 1 session drops below 80%. Include the low-count floor from [FAILOVER_EXECUTION_CP_CONTRACT.md](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/docs/design/FAILOVER_EXECUTION_CP_CONTRACT.md#L229-L237) (`pre == 0` or small-count floor).
- **FortiGate VDOM Context Gap (Check 6 & F1):** When VDOMs are enabled, `diagnose sys ha reset-uptime` requires `config global`, and per-VDOM `diagnose sys session stat` requires `config vdom` / `edit <vdom>`. Neither `config global`, `config vdom`, nor a VDOM-enumeration read appears in the literal table (§4.3).
- **Redundant/Unverified FortiGate Literals (R-F3, R-F4):** `get system ha status` in FortiOS 7.4 already prints `override`, member priorities, and HA uptime/reset counts. Verify in Phase 1 before approving `diagnose sys ha dump-by group` and `execute ha failover status`.
- **Gate Registry Reuse & Poll Budgets:** Reusing existing inventory/backup gates (`asa_show_version`, `asa_show_mode`, `fgt_get_system_status`, `fgt_get_system_ha_status`, `fgt_show_full_configuration_system_ha`) for 4-hourly readiness and failover requires updating their `max_frequency`, `transport_kind`, and `source_document_pointer` in [gate_registry_fixture.yaml](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml), plus explicit poll-budget caps (120 s / 3 s = 40 polls per member).

---

### 4. Phase Ordering, Value & FTD Scope

- **Prioritize ASA (5 pairs); defer FortiGate switch execution (Phase 3):** Building a custom FGCP switch executor for **1 cluster** that lacks enrolled per-member management interfaces has poor ROI and forces single-endpoint workarounds. Deliver Phase 0 + ASA (Phases 1–2) first. Gate FortiGate work on enrolling both members' `ha-mgmt-interface` endpoints; if not possible, drop FortiGate switch execution entirely.
- **FTD is correctly out of scope:** Zero FTD devices exist in the estate, and FTD HA requires FMC REST APIs. Firepower 4100/9300 running the ASA OS image is rightly in scope (identical ASA CLI).

---

### 5. Top 5 Required Changes Before PO Approval (Ranked)

1. **Redesign `dispatch()` / `confirmDispatch()` for session-dropping writes (ASA §5.1):** Support `SESSION_DROP_EXPECTED` in [JooqCpFailoverRepository.java](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/JooqCpFailoverRepository.java#L830-L862) so an SSH drop during `no failover active` enters observation polling instead of immediately aborting to `OUTCOME_UNKNOWN` and quarantining the pair.
2. **Solve ASA post-switch `device_id` ↔ serial & SSH host-key swap (§3.1 / §5.1):** Define how `devices.recorded_identity_primary`, `ha_unit_token`, and SSH host keys behave when Active/Standby management IPs move permanently to the opposite physical chassis after failover.
3. **Mandate 2-device `ha-mgmt-interface` enrollment for FortiGate (Q2) or descope FortiGate switch (Phase 3):** Do not weaken the two-member independent observation law or the 2-device repository invariant (`enrolledFailoverMembers`) for a single cluster.
4. **Fix Phase 0 scope to include `requireTrusted` for SSH vendors and DB migration `ui2_job_module`:** Ensure `cisco_asa` and `fortinet` enforce SSH host-key trust in [CpFailoverService.java](file:///Users/OzanDur/Codo/nexus-fofgasa-1112/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/CpFailoverService.java#L424) and register their 4 capability IDs in `ui2_job_module`.
5. **Correct Check 5 / Check 6 / Check 8 PASS rules & VDOM framing:** Remove the positive 5-second `xmit` delta requirement on ASA Check 5, add low-session-count floors on Check 6, enforce `secondary_uptime > ha-uptime-diff-margin` + priority recording on FortiGate Check 8, and specify `config global` / VDOM framing literals.
