# Operations > Automatic Firewall Policy Installation — Design & Contract Draft

## Status

**P1 superseded by [OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md](OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md) (FROZEN); P2-P4 remain DRAFT** (`DRAFT -- DO NOT IMPLEMENT` for P2–P4, 2026-11-17; updated 2026-11-18 per [PO_DECISION_RECORD_2026_11_18_POLICY_INSTALL_P1_MONITORING.md](PO_DECISION_RECORD_2026_11_18_POLICY_INSTALL_P1_MONITORING.md)). For P2–P4, this document remains a design and contract draft only (`AGENTS.md` "Contract-status law") and authorizes no P2–P4 product code, no schema migration, no network-device command, and no change to `ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml`. Every candidate command literal in §2.5 is **UNAPPROVED** and every unmeasured vendor field is marked **`UNKNOWN`** (`AGENTS.md` "UNKNOWN / fail-closed law", "Vendor semantics law").

Trigger: Product Owner request (2026-11-17, translated):
> *"Another item under the Operations tab: automatic firewall policy installation. One screen for all vendors, schedule, verify beforehand, detect the log on failure and notify by mail, monitor from one screen which ones are installed and which are not, switch it off and on from one screen."*

### Constitutional and Governance Boundary

Installing a firewall policy is a live traffic-changing network-device write (`ActionClass.CLASS_4_POLICY_DEPLOYMENT`, persisted id `"policy-deployment"` in `ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/platform/ActionClass.java`). Under `AGENTS.md` ("Network action taxonomy") and `docs/AI_DEVELOPMENT_PROTOCOL.md` ("Approval boundaries"), class 2–4 writes are prohibited at current maturity unless lifted by an explicit Product Owner constitutional amendment and a frozen contract. Furthermore, [PO_DECISION_RECORD_2026_09_22_SCHEDULED_DEVICE_WRITES_FROM_SCRIPTS.md](PO_DECISION_RECORD_2026_09_22_SCHEDULED_DEVICE_WRITES_FROM_SCRIPTS.md) explicitly states:
> *"What does not change: no write is console-submittable in the sense of 'typed and sent'; the browser still only submits typed intent; class 2+ (policy changes, failover) stays outside this amendment and needs its own record."*

Accordingly, delivery is split into four independently shippable phases:
1. **Phase 1 (P1 — Read-Only Monitoring):** Uses existing `CLASS_0_READ` evidence (plus optional read-only gate additions) and can ship under current product maturity once its contract freezes.
2. **Phase 2 (P2 — Verify Only, No Install):** Issues management-plane validation tasks (`verify-policy`, `commit-all <validate-only/>`, `securityconsole/install/preview`) that do not modify gateway state or live traffic, but create server-side tasks on management appliances. Each literal requires PO command-gate sign-off and a one-target measurement pass (PO Amendment 2026-09-26).
3. **Phases 3 and 4 (P3 Manual Install, P4 Scheduled Install):** Execute `CLASS_4_POLICY_DEPLOYMENT` writes and require:
   - (a) a ratified PO decision record amending `AGENTS.md` to permit bounded class-4 policy installation under the five conditions of the 2026-09-22 scheduled-write amendment plus four-eyes revision approval,
   - (b) multi-model council review (`GOV_PO_ROLE_MIGRATION.md` §7), and
   - (c) literal-by-literal PO command-gate approval before implementation.

---

## 1. Vendor Map: Where a Policy Is Installed From

Per `AGENTS.md` ("Evidence laws"): *Management-plane observation != direct-device runtime truth*, and *Configuration intent != runtime truth*. For every vendor, "what management says it pushed" and "what the gateway reports it is enforcing" are kept as distinct columns and corroborated, never collapsed.

### 1.1 Check Point (Management / MDS Domain)

- **Where installed from:** Multi-Domain Server (`cp_multi_domain_server`, R82 JHF take 127 in this estate per [INCIDENT_2026_11_11_MDS_FORK_STORM.md](INCIDENT_2026_11_11_MDS_FORK_STORM.md)), scoped per domain (`-d '<DOMAIN>'`). Policy packages belong to MDS domains; gateways, ClusterXL pairs, and VSX virtual systems are installation targets ([PO_DECISION_RECORD_2026_10_01_CP_POLICY_READS.md](PO_DECISION_RECORD_2026_10_01_CP_POLICY_READS.md)).
- **Pre-install verification (`verify-policy`):**
  - Literal: `mgmt_cli -r true -d '<DOMAIN>' -f json verify-policy policy-package '<PKG>'`
  - Reference: [Check Point Management API v1.9 — `verify-policy`](https://sc1.checkpoint.com/documents/latest/APIs/index.html#cli/verify-policy~v1.9).
  - Semantics: Runs management policy integrity and compilation checks against the currently published state of `<PKG>` and returns `{ "task-id": "<UID>" }`. Documented parameters take only `policy-package` (name or UID); there is no `revision` parameter on `verify-policy`.
- **Policy installation (`install-policy`):**
  - Literal: `mgmt_cli -r true -d '<DOMAIN>' -f json install-policy policy-package '<PKG>' targets.1 '<TARGET_UID>' access true threat-prevention false install-on-all-cluster-members-or-fail true revision '<SESSION_UID>'`
  - Reference: [Check Point Management API v1.9 — `install-policy`](https://sc1.checkpoint.com/documents/latest/APIs/index.html#cli/install-policy~v1.9).
  - Semantics: Compiles and pushes `<PKG>` to the specified target UID (cluster object UID or standalone gateway UID), returning `{ "task-id": "<UID>" }`. `install-on-all-cluster-members-or-fail true` (default `true` in v1.9) aborts cluster installation if any member fails compilation or installation. `revision '<SESSION_UID>'` pins the install to a specific published session UID rather than whatever session was published most recently.
  - **`UNKNOWN` (measure first):**
    1. Whether `revision '<SESSION_UID>'` is honored for MDS domain packages on R82 JHF take 127 when newer sessions have been published in the same domain, and whether it opens a temporary database lock.
    2. Whether the PO wants `threat-prevention` installed alongside `access` (§6 Q3).
- **Task status and failure log (`show-task`):**
  - Literal: `mgmt_cli -r true -d '<DOMAIN>' -f json show-task task-id '<UID>' details-level full`
  - Reference: [Check Point Management API v1.9 — `show-task`](https://sc1.checkpoint.com/documents/latest/APIs/index.html#cli/show-task~v1.9); already gated as `cp_policy_show_task` in [POLICY_COLLECTION_COMPLETENESS_PLAN.md](POLICY_COLLECTION_COMPLETENESS_PLAN.md).
  - Semantics: Returns `tasks[].status` (`in progress` | `succeeded` | `failed` | `partially succeeded`), `progress-percentage`, and `task-details[]`.
  - **`UNKNOWN` (measure first):** Exact JSON field paths inside `task-details[]` for verifier/compiler error lines and per-gateway sub-task failure messages (`status-description`, `error-warnings`, `blocking-errors`, or `stagesInfo[]`) on R82.
- **Install history and current installed state:**
  - *Management plane (existing reads):* `show-packages` (`cp_policy_packages`), `show-gateways-and-servers` (`cp_policy_gateways_and_servers` in `cp_policy_object_inventory`, joined in `ui2/service/.../policy/PolicyQueryService.java`), `show-last-published-session` (`cp_policy_last_published_session`), and `show-changes` (`cp_policy_show_changes`). As documented in `ui2/frontend/src/screens/PolicyDomainTabs.tsx`, Check Point's management inventory often leaves `policyInstallations[].installed` null (`UNKNOWN`), so management alone never proves gateway installation.
  - *Direct gateway runtime truth (existing reads):* `cpstat -f policy fw` (gates `cp_policy_install_cpstat` and `cp_policy_install_cpstat_vsenv0`, approved in [POLICY_INSTALL_TIME_COMMAND_GATE_ENTRIES.md](POLICY_INSTALL_TIME_COMMAND_GATE_ENTRIES.md)), parsed by `CheckPointPolicyParser.java` into `device_policy_install` (`policy_name`, `installed_at`, `installed_at_text`, `observed_at`). On non-VS0 virtual systems (`vsenv <VSID>`), a per-VS `cpstat -f policy fw` gate is proposed in §2.5 (`GI-1`).

### 1.2 Palo Alto Networks (Panorama)

- **Where installed from:** Panorama (`panorama`, `PAN_XML_API`, `PanXmlApiTransport`; 1 Panorama managing 54 device groups and 38 firewalls / 128 VSYS in this estate per [POLICY_COLLECTION_COMPLETENESS_PLAN.md](POLICY_COLLECTION_COMPLETENESS_PLAN.md)).
- **Two-stage Panorama model (local commit vs device-group push):**
  - On Panorama, editing objects/rules modifies the *candidate* configuration; `type=commit&cmd=<commit></commit>` commits candidate changes to Panorama's *running* configuration; `type=commit&action=all&cmd=<commit-all>...</commit-all>` pushes Panorama's committed running configuration to managed firewalls ([PAN-OS Panorama XML API — Commit Configuration](https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-panorama-api/pan-os-xml-api-request-types/commit-configuration-api)).
  - **Design rule:** neXus **never** executes `type=commit` on Panorama's candidate configuration (which would commit unreviewed edits by human administrators currently working in the Panorama web UI). neXus only validates and pushes already-committed Panorama device-group configurations via `type=commit&action=all` (`commit-all`).
- **Pre-push validation (`commit-all` with `<validate-only/>`):**
  - Literal: `type=commit&action=all&cmd=<commit-all><shared-policy><validate-only/><device-group><entry name='<DG>'><devices><entry name='<SERIAL>'/></devices></entry></device-group></shared-policy></commit-all>`
  - Semantics: Enqueues a Panorama validation job for `<DG>` (and target firewall serials) without activating changes on the firewalls; returns `<response status="success"><result><job><ID></job></result></response>`.
  - **`UNKNOWN` (measure first):** Exact XML schema nesting of `<validate-only/>` alongside `<device-group>` and `<devices>` on Panorama 12.1 (`debug cli on` verification required before freeze).
- **Push to device group (`commit-all`):**
  - Literal: `type=commit&action=all&cmd=<commit-all><shared-policy><device-group><entry name='<DG>'><devices><entry name='<SERIAL>'/></devices></entry></device-group><include-template><no/></include-template></shared-policy></commit-all>`
  - **`UNKNOWN` (measure first):**
    1. Whether `<include-template><yes/></include-template>` is required when a DG rule references a newly added template zone/interface (§6 Q4).
    2. Whether Panorama 12.1 accepts multiple `<entry name='<SERIAL>'/>` nodes under `<devices>` for both members of an HA pair in one `commit-all` call.
- **Commit-all job status and install history:**
  - *Panorama job status & history:* `type=op&cmd=<show><jobs><id><JOB_ID></id></jobs></show>` (polls a running validate/push job) and `type=op&cmd=<show><jobs><all/></jobs></show>` on `platform_role_scope: panorama` (new canonical keys per C4 §3.3). Returns `<job><type>CommitAll</type><status>FIN|ACT|PEND</status><result>OK|FAIL</result><details><line>...</line></details></job>`.
  - *Panorama DG sync status (existing read):* `type=op&cmd=<show><devicegroups/></show>` (gate `pan_policy_devicegroups`, [PO_DECISION_RECORD_2026_10_01_PAN_POLICY_READS.md](PO_DECISION_RECORD_2026_10_01_PAN_POLICY_READS.md)), which reports per-member shared-policy synchronization (`IN_SYNC` | `OUT_OF_SYNC` | `UNKNOWN`), plus Panorama config log (`pan_policy_config_log_enqueue`, V208 actor join).
  - *Direct firewall runtime truth (existing read):* `type=op&cmd=<show><jobs><all/></jobs></show>` on `pan_firewall` (gate `pan_policy_install_show_jobs`, [POLICY_INSTALL_TIME_COMMAND_GATE_ENTRIES.md](POLICY_INSTALL_TIME_COMMAND_GATE_ENTRIES.md)), recording the latest finished `Commit` / `CommitAll` timestamp on the firewall in `device_policy_install`.

### 1.3 FortiGate (FortiManager or Direct)

- **Estate context:** 9 enrolled FortiGate gateways (FortiOS 7.4) and 1 enrolled FortiManager (`fortimanager`), per [ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md](ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md).
- **FortiManager JSON-RPC API (`POST /jsonrpc`, [Fortinet FNDN FortiManager JSON API Reference](https://fndn.fortinet.net)):**
  - *Install status read:* `get /dvmdb/adom/<adom>/device` (existing gate `fmg_jsonrpc_dvmdb_adom_device`) and candidate read `exec /securityconsole/package/status` (`GI-3`). **`UNKNOWN`:** Whether the existing `dvmdb/adom/<adom>/device` response already carries `conf_status` (device DB sync) and `db_status` (policy package sync) for the 9 FortiGates, or whether those 9 FortiGates are managed directly rather than through the enrolled FortiManager.
  - *Preview / verify:* `exec /securityconsole/install/preview` with `{"adom":"<adom>","pkg":"<pkg>","scope":[{"name":"<dev>","vdom":"<vdom>"}],"flags":["json"]}`, polled via `get /task/task/<id>` and read via `exec /securityconsole/preview/result`. **`UNKNOWN`:** Whether `install/preview` on the estate's FMG firmware requires a prior `install/package` with `"flags":["preview"]` and an ADOM workspace lock (`dvmdb/adom/<adom>/workspace/lock`).
  - *Install package:* `exec /securityconsole/install/package` with `{"adom":"<adom>","pkg":"<pkg>","scope":[{"name":"<dev>","vdom":"<vdom>"}],"flags":["nonblocking"]}`, returning `"task": <id>`.
  - *Task status & failure logs:* `get /task/task/<id>` and `get /task/task/<id>/line` (`percent`, `state`, and per-device `detail` log lines).
- **Direct FortiGate (no FortiManager package assignment):**
  - Under FortiOS 7.4 default `set cfg-save automatic` ([FortiOS 7.4 CLI Reference — `config system global`](https://docs.fortinet.com/document/fortigate/7.4.0/cli-reference)), every CLI/GUI policy change is immediately active in the kernel and written to NVRAM; there is no separate "policy package installation" step. Direct FortiGates without a FortiManager package assignment are therefore **P1 read-only status only** (`INSTALLED` when HA cluster checksums match via `fgt_failover_checksum_cluster` and configuration collection succeeded; `OUT_OF_DATE` when `HA_POLICY_DIVERGENT` per [PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md](PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md)).

### 1.4 Cisco ASA (No Central Policy Install — Read-Only Status Only)

- **Estate context:** 12 enrolled Cisco ASA appliances (versions 9.18 and 9.22, 10 in 5 HA pairs and 2 standalone, all single-context routed mode per [ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md](ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md)), with no central policy manager (CSM/FMC).
- **What "installed" means on Cisco ASA:**
  - On Cisco ASA ([Cisco ASA 9.18 CLI Configuration Guide](https://www.cisco.com/c/en/us/td/docs/security/asa/asa918/configuration/general/asa-918-general-config.html)), there is no staged policy package or compilation step: every `access-list`, `access-group`, `nat`, and `object-group` statement in `system:running-config` is immediately enforced on live traffic in RAM.
  - Persistence across a reload requires saving `running-config` to `nvram:startup-config` (`write memory` / `copy running-config startup-config`).
  - On an HA pair (active/standby), configuration is replicated from active to standby; if replication breaks, the two members' `running-config` policy sections diverge (`HA_POLICY_DIVERGENT`, [PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md](PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md)). (Cross-site DR config push between independent ASAs is covered by the scheduled script-execution track of [PO_DECISION_RECORD_2026_09_22_SCHEDULED_DEVICE_WRITES_FROM_SCRIPTS.md](PO_DECISION_RECORD_2026_09_22_SCHEDULED_DEVICE_WRITES_FROM_SCRIPTS.md), not policy package installation.)
- **Proposal:** **Read-only status in P1 only; permanently excluded from P2, P3, and P4.**
  - Using the already-approved reads `more system:running-config` (gate `asa_more_system_running_config`) and `show startup-config` (gate `asa_show_startup_config`), neXus compares the normalized policy/NAT/object sections of `running-config` against `startup-config` (and active vs standby on HA pairs) in memory:
    - `INSTALLED` (`RUNNING_SAVED_MATCH`): `running-config` policy equals `startup-config` policy, and HA peer policy matches (if paired).
    - `OUT_OF_DATE` (`UNSAVED_RUNNING_CHANGES` or `HA_POLICY_DIVERGENT`): `running-config` has unsaved policy edits relative to `startup-config`, or HA members' policies diverge.
    - `UNKNOWN`: either configuration artefact is missing, failed collection, or is older than the staleness threshold (48 h).

---

## 2. Phasing (Each Phase Shippable Alone)

### 2.1 Phase 1 (P1): Read-Only Monitoring Across All Vendors

- **Goal:** One unified screen under `Operations > Policy installation` showing every policy package / device group / firewall target across Check Point, Palo Alto, FortiGate, and Cisco ASA, without issuing any write or validation command.
- **Row states (closed vocabulary):**
  - `INSTALLED`: Management reports the target assigned/in-sync **and** direct gateway runtime evidence (`device_policy_install` for CP/PAN; running==startup + HA match for ASA/direct FGT) corroborates that `gateway.installed_at >= container.last_published_or_changed_at` (within a configurable clock-skew tolerance, default 120 s) and `gateway.policy_name == package.name` (CP).
  - `OUT_OF_DATE`: Management container has a published revision (`cp_policy_domain_run.signal` / `cp_policy_show_changes` / `policy_revision.observed_at`) newer than `device_policy_install.installed_at`, or Panorama `<show><devicegroups/></show>` reports `OUT_OF_SYNC`, or ASA/FGT shows `UNSAVED_RUNNING_CHANGES` / `HA_POLICY_DIVERGENT`.
  - `NOT_INSTALLED`: Target is assigned to a package/DG in management discovery, but `device_policy_install` reports no policy installed or a different `policy_name`.
  - `UNKNOWN`: Direct gateway read (`device_policy_install`) is missing/failed, management snapshot is incomplete, or target is an unmatched management reference with no enrolled `device_id` ([PO_DECISION_RECORD_2026_10_01_CP_POLICY_READS.md](PO_DECISION_RECORD_2026_10_01_CP_POLICY_READS.md)). If management claims `installed=true` or `IN_SYNC` while direct gateway evidence is missing, the row displays `UNKNOWN (MANAGEMENT_ONLY)` — never `INSTALLED` (`AGENTS.md` "Evidence laws").
- **Reads used in P1:**
  - *Existing approved reads (no new gate needed to ship P1 baseline):* `cp_policy_packages`, `cp_policy_gateways_and_servers`, `cp_policy_last_published_session`, `cp_policy_show_changes`, `cp_policy_show_task`, `cp_policy_install_cpstat`, `cp_policy_install_cpstat_vsenv0`, `pan_policy_devicegroups`, `pan_policy_config_log_enqueue`, `pan_policy_log_job_get`, `pan_policy_install_show_jobs`, `asa_more_system_running_config`, `asa_show_startup_config`, `fgt_show_configuration`, `fgt_failover_checksum_cluster`, `fmg_jsonrpc_dvmdb_adom_device`.
  - *Optional new P1 READ gates (for full VSX per-VS and Panorama/FMG install-job history, §2.5):* `GI-1` (`cpstat -f policy fw` inside `vsenv <VSID>`), `GI-2` (`<show><jobs><all/></jobs></show>` on `panorama`), `GI-3` (`POST /jsonrpc exec /securityconsole/package/status` on `fortimanager`).

### 2.2 Phase 2 (P2): Verify Only (On-Demand & Scheduled, No Install)

- **Goal:** Verify/validate policy packages before any installation window — either on-demand from the screen (`Verify now`) or on a schedule — parse any verification failure into a sanitized summary, store it in the ledger, and notify by mail.
- **Behavior:**
  - Dispatches `GI-4` (`verify-policy` on CP MDS + `cp_policy_show_task` polling), `GI-5` (`commit-all <validate-only/>` on Panorama + `GI-6` `<show><jobs><id>` polling), or `GI-7` (`securityconsole/install/preview` on FMG + task polling).
  - Never installs or pushes to gateways. Cisco ASA and direct FortiGates are marked `NOT_APPLICABLE`.
  - On `FAILED`, the worker parses the task/job error lines in memory, extracts only safe structured tokens (step name, vendor error code, rule number/UID, opaque object/layer reference, failure category), discards the raw vendor output (`AGENTS.md` "Raw-evidence law"), appends a `policy_verify_ledger` row, and triggers the `policy_install` mail route (§4.2).

### 2.3 Phase 3 (P3): Manual Install with Four-Eyes Approval, Pre-Verify & Post-Check

- **Goal:** Operator-initiated, human-approved policy installation from the single screen with strict pre-verify, diff preview, business-hours confirmation, post-install traffic sanity check, and failure log mail.
- **End-to-end flow:**
  1. **Diff preview & request (`requester`):** An administrator (`security_admin` or `operation_admin`) opens a target row (`OUT_OF_DATE` or `NOT_INSTALLED`) and inspects the **exact preview of what will be installed**:
     - Built from stored `policy_revision` and `policy_rule_history` (`PolicyChangesService.java`) between the snapshot active at `device_policy_install.installed_at` and the current collected snapshot (`rules_added`, `rules_modified`, `rules_removed`, `nat_rules`, `objects_added/modified/removed`, plus the rule-by-rule diff rows and resolved change actors).
     - If no complete snapshot exists for the current management state, or if the management server's live `show-last-published-session` / DG content hash is newer than the latest collected neXus snapshot (`UNCOLLECTED_MANAGEMENT_CHANGES`), the request is **refused fail-closed** until a policy collection run completes — neXus never installs blind changes it has not collected and diffed.
     - The request binds `(source_id, container_id, policy_id, pinned_revision_uid, pinned_snapshot_sha256, target_device_ids, requester_fingerprint, expires_at)`.
  2. **Four-eyes approval (`approver != requester`):** A second authenticated administrator (`approver_fingerprint != requester_fingerprint`, NFKC-normalized principal check per [FAILOVER_EXECUTION_PLAN.md](FAILOVER_EXECUTION_PLAN.md) FR-P0.7) reviews the same pinned `pinned_snapshot_sha256` diff and approves. Self-approval is rejected (`409 SELF_APPROVAL_FORBIDDEN`).
  3. **Business-hours confirmation:** Inside `08:00–20:00 Europe/Istanbul` (every day, [PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md](PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md)), starting the approved install returns `409 BUSINESS_HOURS_CONFIRM_REQUIRED` unless `X-Nexus-Business-Hours-Ack: true` is supplied for that single request (never batched across targets). Rechecked at worker claim time and at the pre-send mutation fence (`Argon review 2026-10-20 P2-1`).
  4. **Mandatory pre-verify & revision-drift fence:** Inside the worker job, before any write command is sent:
     - Re-reads `show-last-published-session` (CP) or the DG XML hash (PAN) to prove the management state still matches `pinned_revision_uid` / `pinned_snapshot_sha256`. On mismatch, aborts before send (`ABORTED_REVISION_DRIFT`).
     - Checks HA cluster state on target members (`cphaprob stat` / PAN HA state) to confirm both cluster members are `UP` (`ACTIVE`/`STANDBY`) and synchronized.
     - Captures baseline traffic counters (`cat /proc/net/dev` two samples 5 s apart on CP; `<show><counter><interface>all</interface></counter></show>` and `<show><session><meter/></session></show>` on PAN).
     - Runs P2 pre-verify (`verify-policy` / `commit-all <validate-only/>`) if no passing verify for `pinned_snapshot_sha256` completed within the last 15 minutes. If pre-verify fails, aborts before send (`ABORTED_PRE_VERIFY_FAILED`), captures the failure log, and mails.
  5. **Install execution (`CLASS_4_POLICY_DEPLOYMENT`):** Records `markBoundaryCrossed` and a `device_remote_hold` row before sending `GI-8` (`install-policy`) or `GI-9` (`commit-all`). Polls task/job to terminal state.
  6. **Post-check (install status + traffic sanity):**
     - Re-runs direct gateway policy read (`cpstat -f policy fw` / PAN firewall `<show><jobs><all/></jobs></show>`) on every target member to confirm `installed_at` advanced and `policy_name` matches.
     - Re-runs HA state and traffic counters (5 s sample) on the active member(s), applying the established readiness threshold (`post_bps >= 0.50 * baseline_bps` when baseline traffic was above minimum floor, `CpFailoverChecks.TRAFFIC_MIN_RATIO`, plus zero HA role loss).
     - On install failure, partial cluster install, `OUTCOME_UNKNOWN`, or post-check traffic drop, records an open incident/hold on the target, captures sanitized failure details, and sends an immediate mail notification.

### 2.4 Phase 4 (P4): Scheduled Automatic Install with Guardrails & Switches

- **Goal:** Per-package/target scheduled installation controlled from the same screen, bounded by six hard guardrails.
- **Switches (two levels, both audited with mandatory 5–500 char reason and business-hours ack per [PO_DECISION_RECORD_2026_11_10_SCHEDULED_JOB_SWITCHES.md](PO_DECISION_RECORD_2026_11_10_SCHEDULED_JOB_SWITCHES.md)):**
  - **Global master switch:** `scheduled_job_family` row `policy_install` (seeded `OFF` by default) + deployment env `UI2_POLICY_INSTALL_ENABLED=false` (hard lock). Switching OFF cancels all `REQUESTED` scheduled install jobs (`SCHEDULED_FAMILY_SWITCHED_OFF`) in the same transaction.
  - **Per-row switch (`policy_install_schedule`):** Per `(source_id, container_id, policy_id, target_ref)` toggle (`enabled: true|false`), schedule cadence (e.g. weekday evenings at `21:00 Europe/Istanbul`), named credential-store reference with write permission, and `approved_revision_uid` / `approved_snapshot_sha256`.
- **Six mandatory P4 guardrails (missing any one refuses execution before device contact):**
  1. **Human-approved revision only:** A scheduled run **never** installs an unapproved "latest" policy. It only installs a `pinned_revision_uid` + `pinned_snapshot_sha256` that received four-eyes human approval (`requester != approver`) for that target. If management has since moved to a newer unapproved revision, the scheduled run refuses (`ABORTED_UNAPPROVED_REVISION_PRESENT`) and mails an alert.
  2. **Fresh pre-verify right before install:** Executes P2 verification immediately before `install-policy` / `commit-all` in the same job run.
  3. **Never during job windows or business hours:** Scheduled installs are **forbidden** inside the `00:00 / 06:00 / 12:00 / 18:00 Europe/Istanbul` device polling windows (60 min each), **forbidden** inside the `02:00–02:59` backup/MDS-export window, and **forbidden** inside `08:00–20:00` business hours. Default admission window: **`21:00–22:30 Europe/Istanbul`** (after business hours end at 20:00 and before the 23:00 inventory policy-install read noted in [POLICY_INSTALL_TIME_COMMAND_GATE_ENTRIES.md](POLICY_INSTALL_TIME_COMMAND_GATE_ENTRIES.md)).
  4. **Auto-disable after N failures:** If a target experiences **1 install/post-check failure** (or **2 consecutive pre-verify failures**), its per-row `enabled` switch is automatically flipped to `false` (`disabled_reason = AUTO_DISABLED_FAILURE_THRESHOLD`), quarantined from further scheduled runs until a `security_admin` reconciles and re-enables it with a reason.
  5. **Mail on every outcome:** Unlike collection jobs (which mail on failure only), scheduled policy installation mails on **every** terminal outcome (`SUCCEEDED`, `ABORTED_PRE_VERIFY`, `ABORTED_REVISION_DRIFT`, `FAILED`, `OUTCOME_UNKNOWN`).
  6. **Emergency kill switch for all:** A prominent `Disable all & cancel queued` button on the `Operations > Policy installation` header sets the global family switch `OFF`, cancels all queued jobs, and trips the pre-send mutation fence for any job that has not yet crossed `markBoundaryCrossed`.

### 2.5 Candidate Network-Device Command Gate Entries (DRAFT — UNAPPROVED)

Per `docs/AI_DEVELOPMENT_PROTOCOL.md` §"Network-device command gate", every new command requires ten gate fields. None of the rows below is approved or added to `gate_registry_fixture.yaml` in this draft.

#### `GI-1` — Check Point VSX Per-VS Policy Install Read (P1)
- **Literal:** `bash -lc 'vsenv <VSID> && cpstat -f policy fw'` *(or normalized non-login prefix per CP shell contract)*
- **(1) Why:** Corroborate installed policy name and install timestamp per non-0 VSX virtual system (`cp_policy_install_cpstat_vsenv0` covers VS0 only).
- **(2) Class:** `CLASS_0_READ` | **(3) Context:** `check_point` / `cp_vsx_gateway` / `expert` / `SSH_EXEC`
- **(4) Timeout:** 30 s | **(5) Retry:** none | **(6) Max frequency:** once per VS per inventory run or P3/P4 post-check
- **(7) Session reuse:** existing inventory or post-check SSH session
- **(8) Unsupported behavior:** non-zero exit or missing line → `UNKNOWN` for that VS
- **(9) Secret-output risk:** policy name (masked for `aiview`); raw output discarded in memory
- **(10) Safe telemetry:** `outcome`, `vsId`, `elapsedMs`

#### `GI-2` — Panorama CommitAll Job History Read (P1/P2/P3)
- **Literal:** `type=op&cmd=<show><jobs><all/></jobs></show>`
- **(1) Why:** Read `CommitAll` and validate job history on Panorama (currently gated only on `pan_firewall`).
- **(2) Class:** `CLASS_0_READ` | **(3) Context:** `palo_alto` / `panorama` / `not_applicable` / `PAN_XML_API`
- **(4) Timeout:** 30 s | **(5) Retry:** none | **(6) Max frequency:** once per Panorama per 6 h + on-demand refresh
- **(7) Session reuse:** existing Panorama API key | **(8) Unsupported behavior:** non-2xx or malformed XML → `UNKNOWN`
- **(9) Secret-output risk:** admin usernames, DG names, serials; parse in memory, mask for `aiview`, discard raw XML
- **(10) Safe telemetry:** `outcome`, `jobCount`, `resultBytes`

#### `GI-3` — FortiManager Package Status Read (P1)
- **Literal:** `POST /jsonrpc exec /securityconsole/package/status`
- **(1) Why:** Read ADOM policy package installation status per managed FortiGate (`MEASURE FIRST` if `dvmdb/adom/<adom>/device` already suffices).
- **(2) Class:** `CLASS_0_READ` | **(3) Context:** `fortinet` / `fortimanager` / `not_applicable` / `HTTPS`
- **(4) Timeout:** 60 s | **(5) Retry:** none | **(6) Max frequency:** once per ADOM per 6 h
- **(7) Session reuse:** existing FMG JSON-RPC session | **(8) Unsupported behavior:** status code != 0 → `UNKNOWN`
- **(9) Secret-output risk:** device and package names (masked for `aiview`) | **(10) Safe telemetry:** `outcome`, `pkgCount`

#### `GI-4` — Check Point MDS Pre-Install Policy Verification (P2/P3/P4)
- **Literal:** `mgmt_cli -r true -d '<DOMAIN>' -f json verify-policy policy-package '<PKG>'`
- **(1) Why:** Verify policy package integrity before installation; returns `task-id` polled via existing `cp_policy_show_task`.
- **(2) Class:** `CLASS_0_READ` (server-side validation task; zero gateway mutation) | **(3) Context:** `check_point` / `cp_multi_domain_server` / `expert` / `SSH_EXEC`
- **(4) Timeout:** 60 s enqueue; 10 min total poll ceiling | **(5) Retry:** none | **(6) Max frequency:** ≤ 4/day scheduled per package + manual admin verify
- **(7) Session reuse:** 1 dedicated SSH session per verify job | **(8) Unsupported behavior:** non-zero exit or missing `task-id` → `VERIFY_FAILED`
- **(9) Secret-output risk:** rule/object names in verifier errors; parse in memory to rule numbers + error codes, mask names
- **(10) Safe telemetry:** `outcome`, `elapsedMs`, `errorCount`, `warningCount`

#### `GI-5` & `GI-6` — Panorama Device-Group Validate-Only & Job Poll (P2/P3/P4)
- **Literals:**
  - `GI-5`: `type=commit&action=all&cmd=<commit-all><shared-policy><validate-only/><device-group><entry name='<DG>'><devices><entry name='<SERIAL>'/></devices></entry></device-group></shared-policy></commit-all>`
  - `GI-6`: `type=op&cmd=<show><jobs><id><JOB_ID></id></jobs></show>`
- **(1) Why:** Validate Panorama DG policy against target firewall(s) without pushing (`GI-5`), and poll the resulting job (`GI-6`).
- **(2) Class:** `CLASS_0_READ` | **(3) Context:** `palo_alto` / `panorama` / `not_applicable` / `PAN_XML_API`
- **(4) Timeout:** 60 s enqueue (`GI-5`); 30 s per poll (`GI-6`, up to 10 min for validate, 20 min for install) | **(5) Retry:** none
- **(6) Max frequency:** `GI-5` ≤ 4/day/DG scheduled + manual; `GI-6` every 5 s, ≤ 240 polls per job
- **(7) Session reuse:** 1 in-memory Panorama API key per job | **(8) Unsupported behavior:** non-FIN at ceiling → `OUTCOME_UNKNOWN`
- **(9) Secret-output risk:** serials, admin names, error lines; sanitize in memory, never persist raw XML
- **(10) Safe telemetry:** `outcome`, `jobStatus`, `jobResult`, `polls`, `elapsedMs`

#### `GI-7` — FortiManager Install Preview (P2/P3/P4)
- **Literal:** `POST /jsonrpc exec /securityconsole/install/preview, /securityconsole/preview/result, get /task/task/<id>`
- **(1) Why:** Validate FortiManager policy package for target device/VDOM before installation.
- **(2) Class:** `CLASS_0_READ` | **(3) Context:** `fortinet` / `fortimanager` / `not_applicable` / `HTTPS`
- **(4) Timeout:** 60 s per call; 10 min poll ceiling | **(5) Retry:** none | **(6) Max frequency:** ≤ 4/day/package + manual
- **(7) Session reuse:** 1 JSON-RPC session per run | **(8) Unsupported behavior:** status != 0 or task timeout → `VERIFY_FAILED`
- **(9) Secret-output risk:** preview output contains raw FortiOS CLI lines; parse status/error count in memory, never store raw CLI
- **(10) Safe telemetry:** `outcome`, `taskState`, `elapsedMs`

#### `GI-8` — Check Point MDS Policy Installation (P3/P4)
- **Literal:** `mgmt_cli -r true -d '<DOMAIN>' -f json install-policy policy-package '<PKG>' targets.1 '<TARGET_UID>' access true threat-prevention false install-on-all-cluster-members-or-fail true revision '<SESSION_UID>'`
- **(1) Why:** Install human-approved policy revision onto explicit target gateway or cluster (polled via `cp_policy_show_task`).
- **(2) Class:** `CLASS_4_POLICY_DEPLOYMENT` | **(3) Context:** `check_point` / `cp_multi_domain_server` / `expert` / `SSH_EXEC`
- **(4) Timeout:** 60 s enqueue; 20 min poll ceiling | **(5) Retry:** **never** (fail-closed after `markBoundaryCrossed`)
- **(6) Max frequency:** at most once per target per 24 h scheduled (`policy_install_ledger` enforced); manual P3 with fresh four-eyes approval
- **(7) Session reuse:** 1 dedicated SSH session per install job
- **(8) Unsupported behavior:** non-zero exit, task failure, or poll timeout → `FAILED` / `OUTCOME_UNKNOWN` + `device_remote_hold` + auto-disable + mail
- **(9) Secret-output risk:** target/rule names in task errors; sanitize in memory, mask for `aiview`
- **(10) Safe telemetry:** `outcome`, `elapsedMs`, `memberCount`, `succeededMembers`

#### `GI-9` — Panorama Device-Group Policy Push (P3/P4)
- **Literal:** `type=commit&action=all&cmd=<commit-all><shared-policy><device-group><entry name='<DG>'><devices><entry name='<SERIAL>'/></devices></entry></device-group><include-template><no/></include-template></shared-policy></commit-all>`
- **(1) Why:** Push committed Panorama DG policy to explicit target firewall serial(s) (polled via `GI-6`).
- **(2) Class:** `CLASS_4_POLICY_DEPLOYMENT` | **(3) Context:** `palo_alto` / `panorama` / `not_applicable` / `PAN_XML_API`
- **(4) Timeout:** 60 s enqueue; 20 min poll ceiling | **(5) Retry:** **never** (fail-closed after `markBoundaryCrossed`)
- **(6) Max frequency:** at most once per target per 24 h scheduled (`policy_install_ledger` enforced); manual P3 with fresh four-eyes approval
- **(7) Session reuse:** 1 dedicated Panorama API key per install job
- **(8) Unsupported behavior:** non-FIN at ceiling or device push error → `FAILED` / `OUTCOME_UNKNOWN` + `device_remote_hold` + auto-disable + mail
- **(9) Secret-output risk:** serials and error text; sanitize in memory, mask for `aiview`
- **(10) Safe telemetry:** `outcome`, `elapsedMs`, `deviceCount`, `succeededDevices`

#### `GI-10` — FortiManager Policy Package Install (P3/P4, Conditional on §6 Q6)
- **Literal:** `POST /jsonrpc exec /securityconsole/install/package, get /task/task/<id>, get /task/task/<id>/line`
- **(1) Why:** Install approved FortiManager ADOM policy package onto explicit FortiGate device/VDOM scope.
- **(2) Class:** `CLASS_4_POLICY_DEPLOYMENT` | **(3) Context:** `fortinet` / `fortimanager` / `not_applicable` / `HTTPS`
- **(4) Timeout:** 60 s enqueue; 20 min poll ceiling | **(5) Retry:** **never** (fail-closed after `markBoundaryCrossed`)
- **(6) Max frequency:** at most once per target per 24 h scheduled; manual P3 with fresh four-eyes approval
- **(7) Session reuse:** 1 dedicated JSON-RPC session per install job
- **(8) Unsupported behavior:** status != 0, task failure, or poll timeout → `FAILED` / `OUTCOME_UNKNOWN` + `device_remote_hold` + auto-disable + mail
- **(9) Secret-output risk:** device/VDOM names and CLI log lines; sanitize in memory, mask for `aiview`
- **(10) Safe telemetry:** `outcome`, `elapsedMs`, `taskPercent`, `taskState`

---

## 3. Per-Phase Mechanics: RBAC, Ledger, Concurrency, Timeouts, Success & Rollback

### 3.1 Phase Governance Matrix

| Dimension | P1: Read-Only Monitoring | P2: Verify Only | P3: Manual Install (Approved) | P4: Scheduled Install |
| --- | --- | --- | --- | --- |
| **`ActionClass`** | `CLASS_0_READ` (`"read"`) | `CLASS_0_READ` (validation task; zero gateway mutation) | `CLASS_4_POLICY_DEPLOYMENT` (`"policy-deployment"`) | `CLASS_4_POLICY_DEPLOYMENT` (`"policy-deployment"`) |
| **`ActionRegistry` actions & RBAC roles** | `policy_install_read`: all signed-in roles (`security_admin`, `operation_admin`, `compliance_admin`, `operator`, `aiview` always masked) | `policy_verify_start`: `security_admin`, `operation_admin` (never `aiview`, never `operator`) | `policy_install_request`, `policy_install_approve`, `policy_install_start`: `security_admin`, `operation_admin` with mandatory `requester != approver` (never `aiview`) | `policy_install_schedule_set`, `policy_install_kill_switch`: `security_admin` only (or `operation_admin` for OFF/kill switch; §6 Q2); never `aiview` |
| **Durable ledger ([RECOVERY_OPERATIONAL_WRITE_LEDGER.md](RECOVERY_OPERATIONAL_WRITE_LEDGER.md))** | Reads `device_policy_install`, `policy_snapshot`, `policy_revision` | Append-only `policy_verify_ledger` (`entity_id`, `policy_id`, `revision_uid`, `actor`, `outcome`, `error_summary`) | Append-only `policy_install_ledger` + `device_remote_hold`. Written **before** send (`markBoundaryCrossed`) and updated on finish. **Unreadable ledger → fail closed (`OperationalLedgerUnreadableError`), zero device contact.** | Same append-only `policy_install_ledger` + 24 h per-target ceiling check inside admission lock; attributes run to the approving human actors + credential digest, never to `"scheduler"` alone ([2026-09-22 record](PO_DECISION_RECORD_2026_09_22_SCHEDULED_DEVICE_WRITES_FROM_SCRIPTS.md)) |
| **Idempotency** | Stateless read projection | If a `PASS` verify already exists for `(policy_id, revision_uid, target_set)` within 15 min, returns cached pass unless `force=true` | If target's direct `device_policy_install` + management sync already match `pinned_revision_uid`, skips with `SKIPPED_ALREADY_AT_REVISION`. **Never retries after `markBoundaryCrossed`.** | Same as P3, plus 24 h operational-write ledger ceiling per `(target_ref, policy_id)`: at most 1 scheduled install per 24 h |
| **Concurrency & MDS Fork-Storm Exclusion** | Stored DB reads only | At most **1** active verify/install job per management server (`source_id`) and per MDS domain (`container_id`). Blocked if `device_remote_hold` is open on `source_id`. | Hard DB advisory/lease lock per `source_id` and per target cluster: **at most 1 install per management domain at a time**. Refused if any `cp_mds_export`, `cp_gaia_backup`, `cp_policy_collect`, `pan_policy_collect`, `cp_cluster_failover`, `pan_cluster_failover`, or `device_remote_hold` is active on the management server or target cluster ([MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md](MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md)). | Same hard exclusion as P3, plus scheduled only in `21:00–22:30 Europe/Istanbul` (never overlaps 00/06/12/18 collection windows or the 02:00 backup window). Serial execution across domains on the same MDS. |
| **Timeouts** | HTTP query ≤ 5 s | Connect 30 s; enqueue 60 s; poll every 5 s up to **10 min** ceiling | Connect 30 s; pre-verify 10 min; install enqueue 60 s; poll every 5 s up to **20 min** ceiling; post-check 60 s; total job deadline **35 min** | Same as P3 (**20 min** install poll ceiling; if still running at 20 min, ends `OUTCOME_UNKNOWN`, keeps `device_remote_hold`, trips auto-disable, and mails immediately — applying the 2026-11-11 4-hour ceiling lesson) |
| **What counts as success** | N/A | Management task status `succeeded` / `FIN+OK` with 0 blocking errors | (1) Pre-verify `PASS`, (2) management install task `succeeded`/`OK` for **every** member of the target cluster, (3) direct gateway `cpstat`/job read confirms updated `installed_at`, and (4) post-check HA state + traffic sanity (`>= 50%` baseline bps) pass on active member(s) | Same 4 conditions as P3 |

### 3.2 Rollback: What Is and Is Not Possible Per Vendor

Following the constitutional failover principle ([FAILOVER_EXECUTION_PLAN.md](FAILOVER_EXECUTION_PLAN.md)): **No automatic (unattended) rollback is ever executed on failure.** An automated rollback during a partial failure or lost management session can compound an outage or overwrite out-of-band emergency fixes. Instead, on failure neXus holds/quarantines the target, mails the sanitized failure digest, and exposes a human-approved P3 rollback action only where the vendor supports a safe, target-scoped mechanism:

1. **Check Point (MDS Domain):**
   - **What IS possible (target-scoped):** Check Point Management API v1.9 `install-policy` supports `revision '<PREVIOUS_SESSION_UID>'` ([Check Point Management API v1.9 — `install-policy`](https://sc1.checkpoint.com/documents/latest/APIs/index.html#cli/install-policy~v1.9)). Because neXus records the previously installed `session_uid` in `policy_install_ledger`, an administrator can trigger a P3 manual install pinned to `<PREVIOUS_SESSION_UID>` for that specific package and target cluster, without altering the current MDS domain database or other packages. (**`UNKNOWN`:** Must be lab-verified on R82 MDS before P3 freeze.)
   - **What is NOT possible / prohibited:** Check Point's `revert-to-revision` API command rolls back the entire domain database, permanently deletes all newer revisions across all packages in that domain, and forcefully disconnects all active SmartConsole administrators. `revert-to-revision` is **strictly prohibited** in neXus. Similarly, gateway-local `fw unloadlocal` (which strips all security policy) is **strictly prohibited**.
2. **Palo Alto Networks (Panorama):**
   - **What is NOT cleanly possible on Panorama:** Panorama has **no** native XML API command to "push revision *N-1* of Device Group *X* to Firewall *Y* without changing Panorama's own candidate/running configuration." Running `<load><config><version><N></version></config></load>` on Panorama replaces Panorama's entire candidate configuration across all 54 device groups and templates globally, risking cross-estate collateral damage if committed.
   - **What IS possible (two options for PO decision, §6 Q5):**
     - *Option PAN-R1 (Firewall-side named snapshot before push):* Before `commit-all`, neXus instructs each target firewall member to save its current local running configuration to a named on-box file (`<save><config><to>nexus-pre-push.xml</to></config></save>`). However, a local revert on a Panorama-managed firewall only reverts locally-residing config or leaves Panorama's pushed shared-policy out of sync unless Panorama also pushes the prior rule state.
     - *Option PAN-R2 (No automated Panorama rollback; operator reverts in Panorama UI):* neXus records the exact rule/object diff from `policy_rule_history` in the failure mail and UI drawer so the administrator can revert the offending rule in Panorama and push through P3.
3. **FortiGate (FortiManager):**
   - **What IS possible:** FortiManager maintains ADOM policy package revision history (`/dvmdb/adom/<adom>/revision`), but reverting a package to an older revision mutates the ADOM database before re-installing via `securityconsole/install/package`. Until measured, FMG rollback is manual in FortiManager UI.
4. **Cisco ASA:** Not applicable (P1 read-only monitoring only).

### 3.3 Proposed Durable Ledger & Schedule Schema Shapes (P2–P4, No Migration in This Draft)

To satisfy `PO_DECISION_RECORD_2026_09_22_SCHEDULED_DEVICE_WRITES_FROM_SCRIPTS.md` (named target, named actor & credential ref, pre/post ledgered, gated command, explicit `writes: allowed` switch) and `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` (append-only, fail-closed on unreadable):

- **`policy_install_approval` (P3/P4):** `(approval_id, source_id, container_id, policy_id, target_ref, target_device_ids JSONB, pinned_revision_uid, pinned_snapshot_sha256, requester_fingerprint, requested_at, approver_fingerprint, approved_at, expires_at, revoked_by, revoked_at)` with `CHECK (requester_fingerprint <> approver_fingerprint)`.
- **`policy_install_schedule` (P4):** `(source_id, container_id, policy_id, target_ref, enabled BOOLEAN DEFAULT false, writes_allowed BOOLEAN DEFAULT false, credential_ref_digest TEXT, approved_approval_id UUID, cron_slot TEXT DEFAULT '21:00', consecutive_verify_failures INT DEFAULT 0, consecutive_install_failures INT DEFAULT 0, disabled_reason TEXT, updated_by TEXT, updated_at TIMESTAMPTZ)` — audited via `fn_audit_capture`.
- **`policy_install_ledger` (P2/P3/P4, insert-only):** `(ledger_id BIGSERIAL, run_kind TEXT CHECK (run_kind IN ('VERIFY','MANUAL_INSTALL','SCHEDULED_INSTALL')), job_id TEXT, source_id TEXT, container_id TEXT, policy_id TEXT, target_ref TEXT, target_device_ids JSONB, pinned_revision_uid TEXT, pinned_snapshot_sha256 TEXT, approval_id UUID, requester_fingerprint TEXT, approver_fingerprint TEXT, credential_ref_digest TEXT, boundary_crossed_at TIMESTAMPTZ, finished_at TIMESTAMPTZ, outcome TEXT, pre_verify_status TEXT, post_check_status TEXT, traffic_ratio NUMERIC, sanitized_error_summary TEXT)` — indexed on `(target_ref, policy_id, boundary_crossed_at DESC)` for the 24 h ceiling check.

---

## 4. The Single Screen and Notification Mail Content

### 4.1 Screen Design: `Operations > Policy installation` (`?screen=operations&tab=policy-install`)

In `ui2/frontend/src/screens/OperationsScreen.tsx`, `TAB_SLUGS` gains `"policy-install"` (`"Policy installation"`), sitting alongside `"HA and readiness"`, `"Jobs"`, and `"Device health"`. The existing `Policy > Installation` tab (`PolicyDomainTabs.tsx`) remains as the domain-scoped read view and links each target to `?screen=operations&tab=policy-install&policy_id=<id>&device_id=<id>`.

- **Header KPIs & Global Controls:**
  - Four `MetricCard`s: **Installed & current** (`N of M targets`), **Out of date / Not installed** (`count`), **Verify or install failures (24 h)** (`count`), and **Scheduled targets active** (`count` + next window `21:00 GMT+3`).
  - **Global Scheduled Install Switch:** Shows `ON` / `OFF` / `LOCKED (UI2_POLICY_INSTALL_ENABLED=false)` with last actor (`USR-...` in `aiview`) and timestamp. Toggling requires `security_admin`, a 5–500 character reason, and business-hours acknowledgement inside `08:00–20:00`.
  - **Emergency Kill Switch (`Disable all & cancel queued`):** One-click red tonal action that flips the global switch `OFF` and cancels all `REQUESTED` policy-install jobs.
- **Filters:**
  - `Vendor`: `All` | `Check Point` | `Palo Alto` | `FortiGate` | `Cisco ASA`
  - `Install status`: `All` | `INSTALLED` | `OUT_OF_DATE` | `NOT_INSTALLED` | `UNKNOWN`
  - `Last run`: `All` | `VERIFY_FAILED` | `INSTALL_FAILED` | `HOLD_UNCERTAIN`
  - `Schedule`: `All` | `Enabled` | `Disabled` | `Auto-disabled`
  - `Management / Domain`: dropdown of enrolled management containers (`MDS` domains, `Panorama` DGs, `FMG` ADOMs)
  - `Search`: free-text filter (evaluated **after** `PolicyPrivacy.mask` projection so `aiview` cannot probe raw names, matching `PolicyChangesService.java`).
- **Table Columns (one row per `(policy_package_or_dg, target_unit)`):**
  1. **Target (`Gateway / Cluster`):** Pseudonymized in `aiview` (`CLS-ROMEO-01`, `FW-TANGO-04`), linked to Inventory; shows HA member count (`2 members`).
  2. **Vendor:** `Check Point` | `Palo Alto` | `FortiGate` | `Cisco ASA`.
  3. **Container & Policy Package / DG:** Domain/ADOM + package/DG name (masked in `aiview` via `TopologyNamePseudonymizer`).
  4. **Installation Status:** Chip (`INSTALLED`, `OUT_OF_DATE`, `NOT_INSTALLED`, `UNKNOWN (MANAGEMENT_ONLY)`, `UNKNOWN`) with hover explanation of the evidence sources.
  5. **Gateway Installed At:** Timestamp from `device_policy_install.installed_at` + relative age + observed policy name.
  6. **Management Changed At & Actor:** Latest revision timestamp from `policy_revision` / `cp_policy_domain_run` / `pan_policy_config_log_enqueue` + actor (`USR-xxxx` in `aiview`, `AMBIGUOUS` when >1 admin edited before commit, or `UNKNOWN`).
  7. **Approved Revision:** Badge showing whether the pending management revision has four-eyes approval (`APPROVED by 2 admins`, `PENDING_APPROVAL`, `DRIFTED`, or `—`).
  8. **Last Verify:** `PASS` / `FAIL` / `RUNNING` / `—` + timestamp + drawer link on failure.
  9. **Last Install:** `SUCCEEDED` / `FAILED` / `OUTCOME_UNKNOWN (HOLD)` / `—` + timestamp + log drawer link.
  10. **Auto-Install Switch (P4):** Per-row switch (`ON` | `OFF` | `AUTO_DISABLED` | `N/A` for ASA/direct FGT) + schedule label (`Weekdays 21:00`).
  11. **Actions:** `Verify now` (P2), `Preview diff` (P3), `Request install` / `Approve` / `Install now` (P3, role- and state-gated), `Release hold` (`security_admin` when `HOLD_UNCERTAIN`).

### 4.2 Failure Log Detection and Notification Mail Content

`NotificationRoute.java` and `NotificationMailRouter.java` gain one new route type:
- **Route key:** `"policy_install"` — title `"Policy installation and verification"`.
- **When triggered:**
  - P2: any on-demand or scheduled verification ending `FAILED` or `OUTCOME_UNKNOWN`.
  - P3: any manual installation ending `FAILED`, `ABORTED_PRE_VERIFY_FAILED`, `ABORTED_REVISION_DRIFT`, `FAILED_POST_CHECK_TRAFFIC`, or `OUTCOME_UNKNOWN`.
  - P4: **every** scheduled installation attempt (both `SUCCEEDED` and any failure/abort/auto-disable), plus whenever a per-row switch is auto-disabled after *N* failures.
- **Strict privacy & AIView masking (`AGENTS.md` "Mandatory AIView inspection & post-parsing masking law"):**
  - Outbound mail passes all device names, cluster names, package/DG names, and error summaries through `NotificationIdentity.java` (`masked` by default per V193).
  - **Never included in mail or database:** credentials, session tokens, management IPs, serial numbers, raw vendor CLI/JSON/XML payloads, unmasked customer hostnames (in masked mode), or raw rule comments/addresses.
- **Example mail digest format (masked mode, default):**
  ```text
  Subject: neXus – Policy installation and verification: 2 event(s)

  2026-11-17T21:14:08Z  CLS-ROMEO-01 (check_point, 2 members)  pkg=PKG-ALPHA-02  action=SCHEDULED_INSTALL  state=ABORTED_PRE_VERIFY_FAILED  reason="verify-policy failed: 2 compiler errors (rule #142 object ref unresolved; NAT rule #19 port conflict); auto-install switch set to AUTO_DISABLED"  /operations?tab=policy-install&run=7f3a9c12
  2026-11-17T21:18:42Z  FW-TANGO-04 (palo_alto)  dg=DG-BRAVO-01  action=SCHEDULED_INSTALL  state=SUCCEEDED  reason="pre-verify PASS; commit-all job 4812 FIN/OK; gateway install_at updated; post-check traffic 94% of baseline"  /operations?tab=policy-install&run=9b21e044
  ```

---

## 5. Risks and Mitigations

| # | Risk | Vendor / Mechanism | Mitigation in This Contract |
| --- | --- | --- | --- |
| **R1** | **Installing someone else's unpublished or half-finished work** | On Check Point, an admin may have published a partial session in the same domain (`show-last-published-session` advanced) without intending to install yet; on Panorama, an admin may have committed Stage 1 (`type=commit`) to Panorama while still editing a multi-part change, or left uncommitted candidate changes; on FortiManager, an ADOM workspace may be unlocked mid-edit. | (1) neXus **never** runs `publish` on CP or `type=commit` (Stage 1) on Panorama; it only acts on already-published/committed revisions. (2) Both P3 and P4 require four-eyes human approval pinned to an exact `(pinned_revision_uid, pinned_snapshot_sha256)`. (3) Immediately before sending the write command, the worker re-verifies that the management server's latest revision/hash still equals `pinned_revision_uid`; if another admin published/committed a newer session in the meantime, the job aborts (`ABORTED_REVISION_DRIFT`) with zero gateway contact. |
| **R2** | **Installing on the wrong targets (including Check Point's `ALL` target hazard)** | In Check Point packages, `installation-targets` can be set to `"ALL"` (seen in `PolicyQueryService.java`), which pushes the package to every gateway/cluster in the domain if `targets` is omitted! On Panorama, `commit-all` for a `<device-group>` without `<devices>` pushes to every firewall in the DG (up to dozens of firewalls). | (1) Every `install-policy` (`GI-8`) and `commit-all` (`GI-9`) literal **mandatory-scopes** explicit target UIDs (`targets.1 '<TARGET_UID>'`) or explicit firewall serials (`<devices><entry name='<SERIAL>'/></devices>`). A package/DG-wide unscoped push is prohibited in the command builder. (2) Target UIDs/serials are resolved solely from verified stored discovery bindings (`device_id`), never from display names (`AGENTS.md` "Evidence laws"). |
| **R3** | **Partial cluster install (split policy across HA members)** | One cluster member accepts the new policy while its peer fails compilation or transport, leaving the HA pair with mismatched policies (`PolicyParityCheck` failure) or causing ClusterXL / PAN HA state degradation. | (1) Pre-check verifies both HA members are reachable, healthy, and in expected `ACTIVE`/`STANDBY` roles before install starts. (2) CP `install-policy` sets `install-on-all-cluster-members-or-fail true`. (3) Post-check independently queries `cpstat -f policy fw` / PAN job status on **both** cluster members; if only one member updated, the run ends `FAILED_PARTIAL_CLUSTER`, raises an incident hold, and mails immediately. |
| **R4** | **Management lock contention & MDS fork-storm / resource exhaustion** | Running `verify-policy` / `install-policy` concurrently with `cp_mds_export` (`mds_backup`), `cp_policy_collect` (up to 4 SSH sessions), or another domain's install can exhaust MDS CPU/threads or hit database lock contention ([INCIDENT_2026_11_11_MDS_FORK_STORM.md](INCIDENT_2026_11_11_MDS_FORK_STORM.md)). | (1) Non-login shell normalization (`. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV;`) applies to every CP command. (2) DB-enforced mutual exclusion: at most **1** active verify/install across an MDS/Panorama at a time, and zero overlap with `cp_mds_export`, `cp_gaia_backup`, `device_remote_hold`, or `cp_policy_collect`/`pan_policy_collect`. (3) Scheduled installs run only in `21:00–22:30 Europe/Istanbul`, well outside the `00/06/12/18` collection windows and the `02:00` backup window. |

---

## 6. Numbered Open Questions for the PO and the Decision Council

1. **Constitutional Class-4 Amendment (PO & Council):** Do you approve drafting a PO Decision Record to amend `AGENTS.md` ("Network action taxonomy") and `docs/AI_DEVELOPMENT_PROTOCOL.md` ("Approval boundaries") to lift the blanket prohibition on `ActionClass.CLASS_4_POLICY_DEPLOYMENT` specifically for P3/P4 bounded policy installation under the guardrails in §2.3–§2.4?
2. **Approval Role Policy (`requester != approver`, PO):** Should four-eyes approval (`requester != approver`) be mandatory for **both** `security_admin` and `operation_admin` on policy installations (recommended, matching the Zone Matrix publish rule of 2026-11-13), or may a single `security_admin` self-approve in P3 as in the 2026-10-06 failover pilot record?
3. **Check Point Policy Types & `revision` Parameter (PO & Lab Measurement):**
   - (a) Should Check Point `install-policy` (`GI-8`) install **Access Control + NAT only** (`access true threat-prevention false`), or also install **Threat Prevention** (`threat-prevention true`)?
   - (b) May we run one read/verify measurement on a non-production/pilot MDS domain to confirm the `show-task` error JSON shape and verify whether `install-policy ... revision '<SESSION_UID>'` is supported on R82?
4. **Panorama Template Push Scope (`<include-template>`, PO):** When Panorama pushes a Device Group policy (`GI-9`), should `<include-template>` be fixed to `<no/>` (shared/DG policy and objects only, safest), fixed to `<yes/>` (also pushes template/template-stack network settings), or selectable per DG schedule?
5. **Panorama Rollback Strategy (PO & Council):** Because Panorama cannot natively roll back a single Device Group without affecting global candidate config (§3.2), do you prefer **Option PAN-R2** (no automated Panorama rollback; failure mail + rule diff guides manual revert in Panorama UI) or **Option PAN-R1** (save a pre-push local config snapshot on each target firewall before `commit-all`, adding a firewall-side write gate)?
6. **FortiGate / FortiManager Scope in P1–P4 (PO):** Are the 9 enrolled FortiGate firewalls policy-managed through the 1 enrolled FortiManager (making them eligible for FMG P1–P4 once measured), or are their policies managed directly on the FortiGate appliances (making them P1 read-only status only, like Cisco ASA)?
7. **Cisco ASA Read-Only Confirmation (PO):** Confirm that Cisco ASA stays **P1 read-only status only** (`running-config` vs `startup-config` + HA pair policy parity from existing reads, with zero `write memory` or config push on this screen)?
8. **Scheduled Install Maintenance Window (PO):** Is **`21:00–22:30 Europe/Istanbul`** (outside `08:00–20:00` business hours, outside `00/06/12/18` collection windows, outside `02:00` backups, and before the `23:00` inventory policy-install read) the right default window for P4 scheduled installations?
9. **Auto-Disable Threshold `N` (PO):** Confirm the P4 circuit-breaker threshold: auto-disable a target's schedule switch after **`N = 1` install/post-check failure** and after **`N = 2` consecutive pre-verify failures**?
10. **VSX Per-VS Policy Install Read (`GI-1`, PO):** On Check Point VSX gateways, `cp_policy_install_cpstat_vsenv0` currently reads `cpstat -f policy fw` in `vsenv 0` only. Do you approve adding `GI-1` (`vsenv <VSID> && cpstat -f policy fw`) in P1 so each virtual system's installed policy name and time are corroborated individually?
