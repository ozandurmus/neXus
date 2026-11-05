<!-- Argon consultation 2026-11-05T06:53:19Z, model gemini-4-argon-high, effort high; unaltered output -->

I have read `AGENTS.md`, `roles/PO.md`, `AI_START_HERE.md`, `CURRENT_STATE.md`, `project/QUEUE.md`, `docs/design/NOTIFICATION_ROUTING_CONTRACT.md`, `docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md`, and `docs/design/UI_REVIEW_2026_11_05_SYNTHESIS.md` (along with the Argon and Fable review inputs for item `C6` and the target navigation rail).

I am now inspecting the Java service controllers, query services, persistence repositories, Flyway schema migrations (`V1`–`V189`), RBAC `ActionRegistry`, masking layer, and notification/artefact subsystems to inventory every reportable table, API, and service and design the Reports area architecture.
### `SESSION START`
- **Role**: `ENGINEER` / `ARCHITECT` (Read-Only Design Consultation)
- **Movement Type**: `ARCHITECTURE`
- **Recommended Reasoning Tier**: `Medium` (cross-subsystem data inventory, UX, RBAC/masking, and storage architecture inside existing frozen domain contracts; no new network-device commands)
- **Active Scope**: Design analysis for a top-level **Reports** workspace (`#reports`) and predefined reports catalogue (`R1–R3`), addressing the Product Owner request of `2026-11-05` and item `C6` (*Executive deliverable: one-click weekly summary*) of `docs/design/UI_REVIEW_2026_11_05_SYNTHESIS.md`.

> Full plan artifact also saved to: [reports_design_plan.md](file:///Users/OzanDur/.gemini/antigravity-cli/brain/1316be75-0d33-4ca0-9049-7e051201bddc/reports_design_plan.md)

---

# Design Analysis: neXus Reports Area & Predefined Reports Catalogue

## Executive Summary

neXus already collects, parses, and persists operational, configuration, compliance, policy, backup, failover, lifecycle, path, diagnostic, and audit evidence across **12 product areas** (schema through [`V189__backup_size_anomaly_notification.sql`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/persistence/src/main/resources/db/migration/V189__backup_size_anomaly_notification.sql)). Today, however, exports are scattered across single-table buttons (`/hygiene.csv`, `/changes.csv`, `/job-logs/export.csv`, Packet Analysis [`ExpertReport`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/packet-analysis/src/main/java/com/securityexpert/nexus/ui2/packetanalysis/export/ExpertReport.java) `.xlsx`, and a client-side JSON download on Compliance). As highlighted in `UI_REVIEW_2026_11_05_SYNTHESIS.md` (`C6` / `F-2`), there is no unified **Reports** screen, no executive weekly summary deliverable (PDF/e-mail), and no scheduled report delivery.

**Every report in the 16-report catalogue below can be generated 100% from data already stored in PostgreSQL and the encrypted [`FileArtefactStore`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/artefact/FileArtefactStore.java) — zero new network-device commands are required.**

---

## 1. Inventory of Reportable Data

Each row below maps a neXus product area to its existing PostgreSQL tables, backend services/repositories, and REST APIs from source, along with its **Evidence Grade** (`Direct device read`, `Manager-derived`, or `Computed`) and mandatory **Freshness & `UNKNOWN` / Fail-Closed Caveats** (`AGENTS.md`).

| # | Product Area | PostgreSQL Tables & Artefact Stores | Backend Services & REST APIs | Evidence Grade | Freshness & `UNKNOWN` / Fail-Closed Caveats (`AGENTS.md`) | New Device Command Needed? |
|---|---|---|---|---|---|---|
| **1** | **Overview & Fleet Posture** | `devices`, `device_inventory_run`, `device_configuration_run`, `backup_artefact` (+ `backup_retention_ledger`), `jobs`, `device_platform_facts`, `cluster_member_diff`, `device_policy_install`, `compliance_evaluation` | [`OverviewService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/overview/OverviewService.java) (`GET /api/v2/overview`) | **Computed** over direct-read & manager-derived stores | Each plane carries its own newest timestamp (`evidence.inventory`, `configuration`, `backup`, `jobs`, `platform_facts`) and per-device `inventory_age` bucket (`lt24h`, `h24_72`, `gt72h`, `never`). Per `UI_REVIEW_2026_11_05_SYNTHESIS.md` (`B1`), freshness must follow one unified scale: `<24 h` (`FRESH`), `24–72 h` (`STALE / WARN`), `>72 h` or `NEVER` (`CRITICAL / UNKNOWN`). | **No** |
| **2** | **Compliance & Framework Assurance** | `compliance_evaluation` (`V59`), `device_configuration_run` (`canonical_hash`, `sanitized_text`), `devices` (`role != 'management_server'`) | [`ComplianceController`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/ComplianceController.java), [`ComplianceService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/compliance/ComplianceService.java), `ui2-compliance` (`:8085`) (`GET /compliance/overview`, `GET /compliance/controls`, `GET /api/v2/compliance/controls/{id}`, `GET /devices/{id}/compliance`) | **Computed** over **Direct device read** (`SHOW_CONFIGURATION` for CP/FGT/ASA; `ACTIVE`/`EFFECTIVE_RUNNING` for PAN) | Evaluations cache by `(device_id, canonical_hash, rules_fingerprint)` with a 6h backstop. Outcomes: `PASS`, `FAIL`, `UNAVAILABLE` (`NOT_APPLICABLE` excluded from denominator). Per `UI_REVIEW_2026_11_05_SYNTHESIS.md` (`B4`), reports **must never collapse `UNAVAILABLE` into `FAIL` without separation**: always state both **Evidenced Pass Rate** (`pass / (pass + fail)`) and **Evidence Coverage** (`(pass + fail) / applicable_total`), alongside **Assured Pass Rate** (`pass / applicable_total`). Management servers are excluded by design. | **No** |
| **3** | **Backups, Archive Index & Size/Log Anomalies** | `backup_artefact` (`artefact_class='backup'`), `backup_retention_ledger` ([`JooqRetentionQueries.andNotRemoved`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/artefact/JooqRetentionQueries.java)), `backup_baseline` (`V44`), `backup_policy` (`V44`), `backup_artefact_entry` (`V49`), `backup_deviation_record` (`V180`, `V189`), `device_configuration_run` (`deviation_summary`), encrypted blobs in `FileArtefactStore` | [`BackupController`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/BackupController.java), [`BackupDeviationController`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/BackupDeviationController.java), [`BackupDeviationService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/backup/BackupDeviationService.java), [`BackupSizeAnomalyService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/backup/BackupSizeAnomalyService.java), [`JooqBackupSizeAnomalyRepository`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/backup/JooqBackupSizeAnomalyRepository.java) (`GET /backups`, `GET /api/v2/backups/deviations`, `GET /api/v2/backups/policies`, `GET /api/v2/backups/targets`) | **Direct device / manager read** (binary archive + manifest) + **Computed** anomaly classes | Daily fleet schedule runs at `02:00 Europe/Istanbul` (`>26 h` without a backup is stale). Three persisted binary anomaly classes in `backup_deviation_record`: (1) `size_anomaly` (growth $\ge 5\times$ or shrink $\ge 50\%$ vs. device's own last 5 good runs; pins `previous_artefact_id` until `security_admin` accepts), (2) `fleet_large` (standard backup $\ge 10\times$ vendor median and $\ge 1\text{ GiB}$), (3) `firewall_logs` (Check Point standard backup with $\ge 100\text{ MiB}$ under `opt/CPsuite-*/fw1/log/` in `backup_artefact_entry` — reports `log_bytes`, `total_bytes`, `percent` only, never file names). Config-run drift in `BackupDeviationService` explicitly enumerates `not_compared` reasons (`no_read_in_window`, `first_read_only`, `not_computable`, `vendor_not_categorised`). | **No** |
| **4** | **HA Readiness & Controlled Failover** | `cp_failover_readiness` (`V162`, `V177`), `cp_failover_approval` / `failover_request_approval` (`V137`), `cp_failover_run` (`V133–V143`, `V170`), `failover_quarantine` (`V136`, `V169`), `failover_dispatch_intent` (`V138`), `failover_schedule` (`V135`), `device_inventory_ha_fact` | [`CpFailoverController`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/CpFailoverController.java), [`PreflightService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/PreflightService.java), `FailoverExecutionService`, `PanConfigSyncService` (`GET /api/v2/cp-failover/summary`, `GET /api/v2/pan-failover/summary`, `GET /api/v2/cp-failover/units`, `GET /api/v2/failover/preflight/{clusterRef}`, `GET /api/v2/failover/schedules`) | **Direct device read** (bilateral member runtime queries) + **Computed** 15-check pre-flight battery | Readiness observations older than `24 h` degrade to `WARN (Stale)` and cannot authorize failover execution. Unmeasured or single-sided peer checks fail closed as `INSUFFICIENT_EVIDENCE` / `UNKNOWN`. VSX physical chassis (`VSX_CHASSIS`) is readiness-only; each Virtual System (`VIRTUAL_SYSTEM`) is reported as an independent operational unit. Active `failover_quarantine` incidents block all automated actions. | **No** |
| **5** | **Policy Hygiene, Permissiveness & Rule Changes** | `policy_snapshot`, `cp_policy_object_inventory`, `policy_chunk` (`V115–V126`), `policy_rule_history` (`V132`, `V145`, `V150`), `device_policy_install` (`V60`), `policy_change_evidence` (`V163`), `policy_offline_analysis` (`V146`) | [`PolicyController`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/PolicyController.java), [`PolicyQueryService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyQueryService.java), [`PolicyHygiene`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyHygiene.java), [`PolicyChangesService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyChangesService.java), [`PolicySnapshotRepository`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/policy/PolicySnapshotRepository.java) (`GET /api/v2/policy/tree`, `/policies/{id}/hygiene`, `/domains/{id}/unused`, `/domains/{id}/objects/duplicates`, `/domains/{id}/installation`, `/api/v2/policy/changes`) | **Manager-derived** (`MANAGEMENT` packages from CP MDS / Panorama = configured intent) + **Direct device read** (`LOCAL_FIREWALL` rules, hit counters, `device_policy_install`) + **Computed** (`PolicyHygiene`, `PolicyRuleMetrics`) | **Evidence Law**: Management policy snapshots prove *configured intent*, never gateway enforcement (`device_policy_install` bridges the two). `PolicyHygiene` classifies rules into `shadowed`, `conflict` (redundant/overlapping), `disabled`, `unused` (0 hits), `expired`, `broad`, and **`unknown`**. Whenever a rule references unresolved objects, `application-default` / custom services, inline layer jumps, uncollected hit windows, or hits `RULE_WORK`/`TOTAL_WORK` complexity bounds, it is placed in `unknown` with an explicit reason code (`PolicyHygiene.REASONS`). Reports must display the **`UNKNOWN` rule share (%)** prominently alongside findings. | **No** |
| **6** | **Configuration, Cluster Member Diff & Change Feed** | `device_configuration_run` (`read_kind`, `is_primary`, `canonical_hash`, `change_state`, `deviation_summary`), `device_configuration_index` (`context`, `section`, `entry_count`, `section_hash`), `cluster_member_diff`, `configuration_notification` | [`ConfigurationQueryService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/configuration/ConfigurationQueryService.java), [`ChangeFeedService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/change/ChangeFeedService.java) (`GET /configuration`, `GET /devices/{id}/configuration`, `GET /api/v2/changes`) | **Direct device read** + **Computed** section hash diffs & 7-source timeline | `ChangeFeedService` correlates 7 stored sources (`policy_rule_history`, `pan_config`, `device_policy_install`, `policy_change_evidence`, `jobs`, `cp_cpwd_elg`, `device_crash_alert`). Caveat: In `cluster_member_diff`, Check Point ClusterXL member-specific sections (interfaces, cluster-member IPs, hostname) currently cause raw section diffs on 38/39 clusters (`cluster_diff_member_specific_tuning` backlog item); reports must label raw section diffs as `RAW_SECTION_DIFF (Includes Member-Specific Settings)` until expected-state filtering lands. | **No** |
| **7** | **Inventory, Discovery & Management Hierarchy** | `devices`, `endpoints`, `device_inventory_run`, `device_inventory_interface` (`V148`, `V184`), `device_inventory_route` (`V184`, `V187`), `device_inventory_ha_fact`, `device_platform_facts`, `device_identity_check` (`V165`), `discovery_run`, `discovery_candidate` | [`InventoryQueryService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/inventory/InventoryQueryService.java), `DeviceQueryService`, `ManagementTreeService`, `DiscoveryRunService` (`GET /devices`, `GET /devices/{id}/inventory`, `GET /clusters/{ref}/inventory`, `GET /api/v2/management-tree`, `GET /discovery/runs`) | **Direct device read** (`device_inventory_*`, `device_platform_facts`) + **Manager-derived** (`discovery_candidate` from CP MDS, Panorama, FortiManager, Infoblox, Symantec MC) | Management-plane discovery (`discovery_candidate`) is **not** direct-device truth: candidates with `enrollment_state != 'ENROLLED'` or without a completed `device_inventory_run` have `UNKNOWN` direct runtime state. Reports must separate enrolled directly-verified devices from manager-discovered unenrolled candidates and excluded devices (`excluded_from_inventory = true`). | **No** |
| **8** | **Software & Hardware Lifecycle (EoL / EoS)** | `lifecycle_catalog` (`V144`: `vendor`, `kind` `HARDWARE`/`SOFTWARE`, `product`, `end_of_sale`, `end_of_support`, `end_of_engineering`, `source`), joined with `devices` & `device_platform_facts` (`hotfix_level`) | [`LifecycleController`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/LifecycleController.java), [`LifecycleService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/lifecycle/LifecycleService.java), [`LifecycleProjection`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/lifecycle/LifecycleProjection.java) (`GET /api/v2/lifecycle`, `GET /devices/{id}/lifecycle`, `GET /api/v2/lifecycle/catalog`) | **Computed** join of **Direct device read** (`observed_model`, `observed_software_version`, `hotfix_level`) against operator-maintained `lifecycle_catalog` | Per `LifecycleProjection`, when a device's model or software version has no row in `lifecycle_catalog`, status is explicitly `NO_LIFECYCLE_DATA` (never assumed supported). When `observed_model` or `observed_software_version` is missing, status is `UNKNOWN`. `support_contract_status` is always `NOT_COLLECTED` (neXus does not query vendor support portals). Infoblox license expiry is `COLLECTED` from grid summary; all other vendors report `license_status = NOT_COLLECTED`. | **No** |
| **9** | **Path Analysis & Live Hop Validation** | `topology_snapshot`, `topology_override` (`V186`), `path_validation`, `path_validation_hop` (`V188`: `path_verdict`, `hops_not_validated`, per-hop `result` `MATCH`/`MISMATCH`/`NOT_EVALUABLE`/`PENDING`, `checks_json`, `reasons`), `authz_decisions` | [`PathAnalysisController`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/PathAnalysisController.java), [`PathValidationController`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/PathValidationController.java), [`PathValidationService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/topology/PathValidationService.java), [`TopologyController`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/TopologyController.java) (`GET /api/v2/topology`, `POST /api/v2/path-analysis/query`, `GET /api/v2/path-analysis/validate/{id}`) | **Computed** (`A5a` graph model over stored routes & policy snapshots) + **Direct device read** (`A6` CLASS_0 live route/rule matcher in `path_validation_hop`) | Model path verdicts (`A5a`) reflect stored route tables (`device_inventory_route`) and policy snapshots as of their collection timestamps; if any hop lacks routes, VRF context (`V184`), or policy rules, the hop is `INCOMPLETE` / `NOT_EVALUABLE`. Live validation (`A6`) records per-hop `MATCH`, `MISMATCH`, or `NOT_EVALUABLE` (e.g., when a gateway has no management mapping or live read failed) and records `hops_not_validated`. | **No** (reports read stored `path_validation` & `topology_snapshot` rows) |
| **10** | **System Logs, Health (CPU/Memory/Restarts) & Crash Alerts** | `system_log_run`, `system_log_chunk` (`V160`), `system_log_gap`, `device_crash_alert` (`V160`), `system_log_hcp`, `system_log_spike_dir`, `system_log_sar`, `cp_cpview`, `system_log_unmasked_access_ledger` | [`SystemLogReadService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/systemlog/SystemLogReadService.java), [`SystemHealthService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/systemlog/SystemHealthService.java) (`GET /api/v2/system-health`, `GET /api/v2/devices/{id}/system-logs/...`) | **Direct device read** (collected daily during `02:00–05:00` window) + **Computed** 7-day p95 CPU/Memory (`SarDailyText`) & restart rates (`CpwdRestartLines`) | Only Check Point and Palo Alto gateways are collected (`VENDOR_UNSUPPORTED` for others; `DISABLED_ON_MANAGEMENT` when management server collection is off). Palo Alto has no `sar` (`cpu/mem = NOT_APPLICABLE`). Crash files present at baseline (`is_baseline = true`) never alarm; only post-baseline files in `device_crash_alert` trigger `CRITICAL`. File names are stored as HMAC digests only (`AGENTS.md` Raw-evidence law). | **No** |
| **11** | **Packet Analysis Cases & Captures** | `packet_analysis_case`, `packet_analysis_capture`, `packet_analysis_run`, `packet_analysis_telemetry`, `packet_analysis_ledger` (`V173–V176`), per-case DuckDB (`project.duckdb`) | [`PacketAnalysisController`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/PacketAnalysisController.java), [`ExpertReport`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/packet-analysis/src/main/java/com/securityexpert/nexus/ui2/packetanalysis/export/ExpertReport.java) (`GET /api/v2/packet-analysis/cases`, `GET /api/v2/packet-analysis/cases/{id}/report.xlsx`) | **Computed** over operator-uploaded PCAP/telemetry files + correlated policy/interface metadata | Case-scoped offline analysis; never contacts devices. Unconfirmed telemetry timezones default to UTC with an explicit caveat. Under `aiview` (`role:replay_viewer`), case/capture titles are pseudonymized, digests/creators are omitted, and only the masked summary projection is accessible (`PACKET_ANALYSIS_RAW_VIEW` is `security_admin` only). | **No** |
| **12** | **Audit, Access/RBAC, Trust & Job/Collection Health** | `audit_log`, `authz_decisions`, `sessions`, `local_identities`, `rbac_roles`, `role_bindings`, `directory_settings`, `notification_settings`, `notification_routes` (`V189`), `ssh_host_key_trust` (`V147`), `https_appliance_certificate` (`V108`), `jobs`, `endpoint_admission`, `runtime_task_lease` | [`AuditLogQueryService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/audit/AuditLogQueryService.java), [`JobLogQueryService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/job/JobLogQueryService.java), [`SystemStatusService`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/system/SystemStatusService.java) (`GET /api/v2/audit-logs`, `GET /api/v2/job-logs`, `GET /api/v2/job-logs/stats`, `GET /api/v2/ssh-trust`, `GET /api/v2/system/status`) | **Computed / Platform ledger** (direct platform state + device SSH/TLS trust verification) | `audit_log` intentionally omits raw `snapshot` JSON from list projections (`AuditLogQueryService`). `jobs` tracks `ssh_identity_unverified` (`V165`) and `terminal_reason`. Pod CPU/memory metrics in `SystemStatusService` require the in-cluster Kubernetes service account token (`available = false` with explicit reason when absent). | **No** |

---

## 2. Predefined Reports Catalogue (16 Reports)

The catalogue below defines **16 predefined reports** grouped into 4 families (`Executive & Assurance`, `Resilience & Backups`, `Policy, Configuration & Path`, and `Asset, Lifecycle & Platform Governance`). All 11 required topics from the prompt are covered, plus 5 high-value reports backed by existing tables (Backup Archive Content & Config Drift, System Health & Crash/Dump Digest, Discovery & Unenrolled Assets, SSH/TLS Trust & Identity Assurance, and Packet Analysis Case Portfolio).

### Universal Catalogue Rules
- **Masking Behaviour (`AGENTS.md` AIView Law)**:
  - **Under `aiview` (`role:replay_viewer`) or when `projection=MASKED` is selected**: Every device hostname, cluster name, management domain, virtual system, and policy package is replaced deterministically via [`TopologyNamePseudonymizer`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/privacy/TopologyNamePseudonymizer.java) (e.g., `FW-TANGO-04`, `CLS-ROMEO-01`, `DOM-PAPA-01`), every IP/CIDR passes through [`SubnetPreservingIpMasker`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/privacy/SubnetPreservingIpMasker.java) (`192.0.2.x`), and actor/serial/digest fields follow [`ReportProjectionMasker`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/privacy/ReportProjectionMasker.java).
  - **Raw Projection (`projection=RAW`)**: Only available to authenticated human principals whose role holds `report_raw_view` plus the underlying domain's read/raw capability (**never** `role:replay_viewer`). Every generated report file stamps its projection mode (`MASKED (AIView)` vs. `RAW — RESTRICTED`) in the header, footer, and filename (`...-masked.pdf` vs. `...-raw.pdf`).
- **Mandatory `"Data as of"` & Coverage Banner**: Every report renders a top metadata block showing: `Report Generated At (UTC & Europe/Istanbul)`, `Data As Of (oldest/newest underlying collection timestamp per plane)`, `Freshness Distribution (<24h / 24-72h / >72h / Never)`, and `UNKNOWN / Not Evaluated Share (%)`.

---

### RPT-01: Executive Weekly Fleet & Security Posture Summary (`C6`)
- **Audience**: `Executive` (CISO, Head of Network Security, IT Risk Leadership)
- **Question Answered**: *"What is the current health, assurance coverage, backup resilience, compliance posture, and week-over-week risk movement of our firewall and network security estate—and how much of the estate is backed by fresh (<24 h) evidence?"*
- **Sections**:
  1. **Executive Evidence Trust Banner**: Total monitored estate (devices, HA clusters, virtual systems) broken down by unified evidence freshness (`<24 h Fresh`, `24–72 h Stale`, `>72 h Critical`, `Never Collected`).
  2. **Compliance Headline by Primary Frameworks**: Side-by-side scorecard for primary frameworks (`CIS Benchmark`, `NIST SP 800-53`, and `Financial Baseline` if promoted per `B4`) showing **Evidenced Pass %**, **Evidence Coverage %**, and week-over-week delta.
  3. **Backup & Recovery Assurance**: 7-day backup success rate, devices missing recent backups (`>26 h`), and active unacknowledged backup anomalies (`size_anomaly`, `fleet_large`, `firewall_logs`).
  4. **HA & Failover Readiness Summary**: Cluster count by readiness state (`READY`, `WARN / Stale`, `BLOCKED`, `QUARANTINED`, `INSUFFICIENT_EVIDENCE`).
  5. **Policy & Change Velocity (7-Day Window)**: Total rule changes, policy installations, major configuration deviations, and top 5 most permissive/risky policy packages.
  6. **Software Lifecycle & Platform Alerts**: Count of devices past End-of-Support (`EoS`), devices with `NO_LIFECYCLE_DATA`, and new post-baseline crash/dump alerts (`device_crash_alert`) in the last 7 days.
- **Data Sources**: `OverviewService`, `ComplianceService`, `BackupDeviationService`, `BackupSizeAnomalyService`, `CpFailoverService`, `ChangeFeedService`, `LifecycleService`, `SystemHealthService`.
- **Filters**: `date_range` (default: last 7 days), `vendor` (All, Check Point, Palo Alto, Fortinet, Cisco ASA, Infoblox, Symantec), `management_domain`.
- **Output Formats**: `On-screen`, `PDF` (2–3 page executive briefing layout), `XLSX` (multi-sheet summary workbook).
- **Schedulable**: **Yes** (Default preset: Weekly Monday `07:30 Europe/Istanbul` via e-mail route `scheduled_report_delivery`).
- **Masking Behaviour**: `aiview` masked by default (`FW-TANGO-04`, `CLS-ROMEO-01`); scheduled e-mail defaults to `MASKED` unless explicitly configured as `RAW` by `security_admin`.

---

### RPT-02: Framework Compliance & Evidence Coverage Report
- **Audience**: `Auditor` / `Security Engineering` / `Executive`
- **Question Answered**: *"How does our fleet comply with CIS Benchmark, NIST SP 800-53, PCI-DSS v4.0.1, and Financial Baseline controls, which specific controls are failing, and which controls cannot be evaluated due to missing evidence (`UNAVAILABLE`)?"*
- **Sections**:
  1. **Framework Assurance Matrix**: Primary frameworks (`CIS Benchmark`, `NIST SP 800-53`) and secondary frameworks (`PCI-DSS v4.0.1`, `Financial Baseline`) with explicit 3-metric split (`Evidenced Pass %`, `Evidence Coverage %`, `Assured Pass %`) and excluded management-server count.
  2. **Control-by-Control Breakdown**: Control ID, title, severity (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`), vendor applicability, `PASS` count, `FAIL` count, `UNAVAILABLE` count, and remediation guidance.
  3. **Evidence Gap Register (`UNAVAILABLE` Analysis)**: Devices and controls where configuration evidence is missing, stale (`>24 h`), or unsupported by the current read kind (`SHOW_CONFIGURATION` vs. `EFFECTIVE_RUNNING`).
  4. **Per-Device Compliance Scorecard**: Sortable table of devices with pass/fail/unavailable counts, configuration `canonical_hash` (truncated/masked for `aiview`), and configuration timestamp.
- **Data Sources**: `ComplianceService`, `compliance_evaluation`, `device_configuration_run`, `devices`.
- **Filters**: `framework` (`CIS Benchmark`, `NIST SP 800-53`, `PCI-DSS v4.0.1`, `Financial Baseline`, or `All`), `vendor`, `cluster`, `severity`, `status` (`FAIL`, `UNAVAILABLE`, `PASS`), `freshness` (`<24h`, `>24h`).
- **Output Formats**: `On-screen`, `PDF`, `CSV`, `XLSX`.
- **Schedulable**: **Yes** (Weekly or Monthly auditor pack).
- **Masking Behaviour**: `aiview` masks device hostnames (`FW-JULIET-06`) and redacts sanitized config snippets (`ReportProjectionMasker`); raw device hostnames shown only to roles with `report_raw_view` + `compliance_read` outside `replay_viewer`.

---

### RPT-03: Backup Coverage, Retention & Size/Log Anomaly Report
- **Audience**: `Operations` / `Auditor`
- **Question Answered**: *"Are all backup-targeted devices completing daily encrypted backups within policy, are retention rules met, and which backups exhibit suspicious size jumps/shrinks (`size_anomaly`), excessive size (`fleet_large`), or embedded firewall logs (`firewall_logs`)?"*
- **Sections**:
  1. **Fleet Backup Coverage & Freshness**: Targeted (`backup_target = true`) vs. excluded devices; backups completed in last `26 h`, `26–72 h`, `>72 h`, and `NEVER`; backup policy schedule status (`backup_policy`).
  2. **Unaccepted Size Deviations (`size_anomaly`)**: Devices where the latest backup grew $\ge 5\times$ or shrank $\ge 50\%$ vs. the device's own last 5 good runs (`JooqBackupSizeAnomalyRepository`), showing `current_bytes`, `previous_bytes`, ratio, pinned baseline artefact timestamp, and acceptance status (`accepted_at` / `accepted_by`).
  3. **Fleet-Wide Oversized Backups (`fleet_large`)**: Standard backups $\ge 10\times$ vendor median and $\ge 1\text{ GiB}$, with vendor median comparison and storage footprint impact.
  4. **Embedded Firewall Logs Inside Backups (`firewall_logs`)**: Check Point standard backups carrying $\ge 100\text{ MiB}$ under `opt/CPsuite-*/fw1/log/` (`log_bytes`, `total_bytes`, `percent` of archive — zero raw file names exposed per `V180` privacy law).
  5. **Retention & Baseline Ledger**: Pinned golden baselines (`backup_baseline`) and total encrypted bytes stored per vendor.
- **Data Sources**: `BackupDeviationService`, `BackupSizeAnomalyService`, `backup_artefact`, `backup_retention_ledger`, `backup_deviation_record`, `backup_baseline`, `backup_policy`.
- **Filters**: `date_range`, `vendor`, `anomaly_class` (`size_anomaly`, `fleet_large`, `firewall_logs`, `missing_backup`), `include_accepted` (`true`/`false`).
- **Output Formats**: `On-screen`, `PDF`, `CSV`, `XLSX`.
- **Schedulable**: **Yes** (Daily `06:00 Europe/Istanbul` after the `02:00` backup window, or Weekly).
- **Masking Behaviour**: `aiview` masks device names and replaces `accepted_by` actor usernames with deterministic actor tokens; archive log paths are already aggregated to byte/percentage counters only.

---

### RPT-04: Backup Archive Index & Configuration Section Drift Report
- **Audience**: `Operations` / `Security Engineering`
- **Question Answered**: *"Which devices experienced major or minor configuration section changes across recent backup/configuration runs, and what is the structural composition of stored backup archives?"*
- **Sections**:
  1. **7-Day Configuration Deviation Summary**: Devices classified as `MAJOR` deviation, `MINOR` deviation, `NO_CHANGE`, or `NOT_COMPARED` (`BackupDeviationService`).
  2. **Honest `NOT_COMPARED` Breakdown**: Explicit enumeration of why uncompared devices could not be diffed (`no_read_in_window`, `first_read_only`, `not_computable`, `vendor_not_categorised`).
  3. **Changed Configuration Sections**: Per-device breakdown of modified configuration sections (`device_configuration_index`: `context`, `section`, `entry_count` delta) without dumping raw secret lines.
  4. **Archive Structural Metrics**: Entry counts, top-level directory byte distribution (`backup_artefact_entry`), and `validation_level` across recent backups.
- **Data Sources**: `BackupDeviationService`, `device_configuration_run`, `device_configuration_index`, `backup_artefact_entry`.
- **Filters**: `date_range` (default 7 days), `vendor`, `cluster`, `deviation_level` (`MAJOR`, `MINOR`, `NOT_COMPARED`).
- **Output Formats**: `On-screen`, `PDF`, `CSV`, `XLSX`.
- **Schedulable**: **Yes**.
- **Masking Behaviour**: `aiview` masks device/cluster names and VS contexts; raw file paths in `backup_artefact_entry` are summarized by top-level component directory under `aiview`.

---

### RPT-05: HA Cluster & Controlled Failover Readiness Report
- **Audience**: `Operations` / `Executive`
- **Question Answered**: *"Which Check Point (ClusterXL / VSX) and Palo Alto HA pairs are verified ready for failover right now, which clusters are blocked or stale, and what is the audit trail of recent failover executions and active quarantines?"*
- **Sections**:
  1. **Fleet HA Readiness Matrix**: All HA clusters (`CLS-ROMEO-01`, etc.) and VSX Virtual Systems (`VIRTUAL_SYSTEM` units separated from `VSX_CHASSIS`), showing operational unit type, member roles (`ACTIVE`/`STANDBY`), readiness verdict (`READY`, `WARN`, `BLOCKED`, `INSUFFICIENT_EVIDENCE`), and observation age.
  2. **Pre-Flight Check Failure Breakdown**: Detailed table of failing or `UNKNOWN` checks from `cp_failover_readiness` (interface link parity, sync state, table counts, routing parity, peer bilateral corroboration, PAN config sync state).
  3. **Active Quarantines & Stop Codes**: Any cluster currently in `failover_quarantine` (`V136`/`V169`), incident reason, stop code, and quarantine age.
  4. **Failover Execution & Approval History**: Historical `cp_failover_run` and `failover_request_approval` records within the selected window (pre-flight outcome, dual-control approvers, execution duration, post-check verification).
- **Data Sources**: `CpFailoverService`, `PreflightService`, `PanConfigSyncService`, `cp_failover_readiness`, `cp_failover_run`, `failover_quarantine`, `failover_request_approval`, `failover_schedule`.
- **Filters**: `date_range`, `vendor` (`Check Point`, `Palo Alto`), `cluster`, `readiness_state` (`READY`, `WARN`, `BLOCKED`, `QUARANTINED`, `INSUFFICIENT_EVIDENCE`), `unit_type` (`CLUSTER`, `VSX_CHASSIS`, `VIRTUAL_SYSTEM`).
- **Output Formats**: `On-screen`, `PDF`, `CSV`, `XLSX`.
- **Schedulable**: **Yes** (Pre-maintenance window readiness report or Weekly).
- **Masking Behaviour**: `aiview` masks cluster names (`CLS-ROMEO-01`), member hostnames (`FW-TANGO-04`), Virtual System names, and approver identities; member serial comparisons are reported strictly as relational tokens (`MATCH` / `MISMATCH` / `MISSING`) per `AGENTS.md` Sensitive Identity Reporting Law.

---

### RPT-06: Policy Hygiene, Unused Rules & `UNKNOWN` Coverage Report
- **Audience**: `Security Engineering` / `Auditor`
- **Question Answered**: *"Across our management domains and local gateway policies, how many rules are shadowed, redundant/conflicting, unused (zero-hit), expired, disabled, or overly broad—and what exact percentage of rules could not be statically proven (`UNKNOWN`) and why?"*
- **Sections**:
  1. **Evidence & `UNKNOWN` Honesty Banner**: Total rules analysed per policy package, explicitly splitting **Evaluated Rules** vs. **`UNKNOWN` Rules (count & %)**, and distinguishing **Manager-Derived Intent** (`MANAGEMENT` snapshot) from **Gateway-Installed Enforcement** (`device_policy_install` / `LOCAL_FIREWALL`).
  2. **`UNKNOWN` Reason Breakdown (`PolicyHygiene.REASONS`)**: Exact count and share of rules marked `UNKNOWN` due to: unresolved object references, `application-default` / custom protocol ports, inline layer jumps, uncollected hit-count coverage window, or complexity budget truncation (`RULE_WORK` / `TOTAL_WORK`).
  3. **Hygiene Findings Summary by Package**: Per-domain / per-package counts of `shadowed`, `conflict` (redundant/overlapping), `unused` (0 hits in observed window), `expired`, `disabled`, and `broad` (high/very-high permissiveness via `PolicyRuleMetrics`).
  4. **Duplicate & Unused Policy Objects**: Summary from `/api/v2/policy/domains/{id}/objects/duplicates` and `/unused` (`cp_policy_object_inventory`).
  5. **Actionable Rule Remediation Register**: Top candidate rules for cleanup (rule UID/number, package, layer, hygiene class, shadowing/conflicting peer rule number, hit count, last hit timestamp).
- **Data Sources**: `PolicyQueryService`, `PolicyHygiene`, `PolicyRuleMetrics`, `PolicySnapshotRepository`, `policy_snapshot`, `cp_policy_object_inventory`, `device_policy_install`.
- **Filters**: `vendor` (`Check Point`, `Palo Alto`, `Fortinet`, `Cisco ASA`), `management_domain`, `policy_id`, `hygiene_class` (`shadowed`, `conflict`, `unused`, `expired`, `disabled`, `broad`, `unknown`), `permissiveness` (`Very High`, `High`).
- **Output Formats**: `On-screen`, `PDF`, `CSV`, `XLSX` (extends existing `/hygiene.csv` into a multi-package executive + engineering report).
- **Schedulable**: **Yes** (Weekly or Monthly).
- **Masking Behaviour**: `aiview` masks domain names (`DOM-PAPA-01`), policy package names (`POL-ALPHA-01`), rule names/comments, object names, and IP literals inside rule summaries (`192.0.2.x`).

---

### RPT-07: Policy Change, Rule History & Installation Drift Report
- **Audience**: `Security Engineering` / `Auditor` / `Operations`
- **Question Answered**: *"Which firewall rules were added, modified, or deleted during the period, who changed them, and are there management policy changes that have not been installed onto target gateways (or gateway policies older than the latest management revision)?"*
- **Sections**:
  1. **Policy Change Summary**: Count of rules `ADDED`, `MODIFIED`, and `DELETED` across domains/packages (`PolicyChangesService`, `policy_rule_history` excluding `initial_capture = true`).
  2. **Management-to-Gateway Installation Alignment**: Cross-check of latest `policy_snapshot` revision against `device_policy_install` per target gateway, highlighting gateways running stale or unverified policy installations.
  3. **Rule-Level Audit Trail**: Chronological table of rule changes (`changed_on`, `changed_by`, domain, package, `rule_key`, `change_type`, field-level diff summary from `policy_rule_history.changes` and `policy_change_evidence`).
- **Data Sources**: `PolicyChangesService`, `PolicyChangeRepository`, `policy_rule_history`, `policy_change_evidence`, `device_policy_install`, `policy_snapshot`.
- **Filters**: `date_range`, `vendor`, `management_domain`, `policy_id`, `change_type` (`ADDED`, `MODIFIED`, `DELETED`), `changed_by`.
- **Output Formats**: `On-screen`, `PDF`, `CSV`, `XLSX`.
- **Schedulable**: **Yes** (Daily or Weekly change-control review).
- **Masking Behaviour**: `aiview` masks `changed_by` administrator names, domain/package/gateway names, and IP/object values inside the `changes` diff JSON.

---

### RPT-08: Configuration Change, Cluster Member Diff & Cross-Plane Drift Report
- **Audience**: `Operations` / `Security Engineering`
- **Question Answered**: *"What OS/platform configuration changes occurred across the fleet, how do they correlate with jobs, policy installs, and process restarts, and which HA clusters have member-to-member configuration discrepancies?"*
- **Sections**:
  1. **Correlated Multi-Source Change Timeline**: Unified 7-source timeline from `ChangeFeedService` (`device_configuration_run`, `policy_rule_history`, `pan_config`, `device_policy_install`, `policy_change_evidence`, `jobs`, `cp_cpwd_elg`, `device_crash_alert`).
  2. **Device Configuration Hash & Section Changes**: Devices whose primary `canonical_hash` changed during the window, with modified section names (`device_configuration_index`).
  3. **HA Cluster Member Parity (`cluster_member_diff`)**: Per-cluster comparison (`comparable`, `diff_section_count`, `diff_setting_count`, `diff_sections`), carrying an explicit caveat banner distinguishing expected `MEMBER_SPECIFIC` sections (interface IPs, hostname, cluster priority) from true operational drift (`cluster_diff_member_specific_tuning`).
- **Data Sources**: `ConfigurationQueryService`, `ChangeFeedService`, `device_configuration_run`, `device_configuration_index`, `cluster_member_diff`.
- **Filters**: `date_range`, `vendor`, `cluster`, `device_id`, `change_source`.
- **Output Formats**: `On-screen`, `PDF`, `CSV`, `XLSX`.
- **Schedulable**: **Yes**.
- **Masking Behaviour**: `aiview` masks all device/cluster identities, admin names in `pan_config` logs, and redacts raw configuration lines (`ReportProjectionMasker`).

---

### RPT-09: Software & Hardware Lifecycle (EoS / EoL) & Hotfix Posture Report
- **Audience**: `Executive` / `Operations` / `Auditor`
- **Question Answered**: *"Which network security appliances and OS versions have passed or are approaching End-of-Sale (`EoSale`), End-of-Engineering (`EoE`), or End-of-Support (`EoS`), what hotfix levels are deployed, and where is catalog coverage missing (`NO_LIFECYCLE_DATA`)?"*
- **Sections**:
  1. **Lifecycle Risk Buckets**: Fleet counts classified by `LifecycleProjection` into: `PAST_EOS` (Critical), `EOS_WITHIN_180_DAYS` (Warning), `EOS_WITHIN_365_DAYS` (Upcoming), `SUPPORTED`, `NO_LIFECYCLE_DATA` (Catalog Gap), and `UNKNOWN` (Missing Inventory Read).
  2. **Explicit Contract & License Caveat Block**: States clearly that `support_contract_status` is `NOT_COLLECTED` across all vendors and `license_status` is `COLLECTED` only for Infoblox grid members (`NOT_COLLECTED` elsewhere).
  3. **Hardware & Software Milestone Table**: Per-device model, software version, observed Jumbo Hotfix Accumulator / patch level (`device_platform_facts.hotfix_level`), matched `lifecycle_catalog` dates (`end_of_sale`, `end_of_engineering`, `end_of_support`), and days remaining.
  4. **Software Version & Hotfix Fragmentation**: Grouping by `(vendor, model, software_version, hotfix_level)` to highlight estate standardization gaps (e.g., R81.10 vs R81.20 Take disparities within the same cluster or domain).
- **Data Sources**: `LifecycleService`, `LifecycleProjection`, `lifecycle_catalog`, `devices`, `device_platform_facts`.
- **Filters**: `vendor`, `cluster`, `kind` (`HARDWARE`, `SOFTWARE`), `lifecycle_state` (`PAST_EOS`, `APPROACHING_EOS`, `SUPPORTED`, `NO_LIFECYCLE_DATA`, `UNKNOWN`).
- **Output Formats**: `On-screen`, `PDF`, `CSV`, `XLSX`.
- **Schedulable**: **Yes** (Monthly capacity & budget planning).
- **Masking Behaviour**: `aiview` masks device and cluster names (`FW-TANGO-04`) while keeping hardware models (`Check Point 16000`, `PA-5220`), OS versions (`R81.20`, `PAN-OS 10.2`), and milestone dates visible (per `AGENTS.md` vendor identifiers / models are non-sensitive).

---

### RPT-10: Fleet Asset, Virtual System & Interface/Routing Inventory Report
- **Audience**: `Operations` / `Auditor`
- **Question Answered**: *"What is our complete verified inventory of physical appliances, HA clusters, VSX Virtual Systems, interfaces, VRF/routing contexts, and route table scales—and how fresh is each device's inventory read?"*
- **Sections**:
  1. **Estate Topology & Role Breakdown**: Enrolled devices by vendor, role (`gateway`, `cluster_member`, `vsx_gateway`, `management_server`, `ddi_appliance`, `proxy`), HA membership (`cluster_member_ref`), and inventory age (`<24h`, `24–72h`, `>72h`, `never`).
  2. **Virtual System (VSX / VDOM / VSYS) Density**: Breakdown of virtual systems per physical chassis (`devices.virtual_systems`), interface counts, and route counts per routing context (`V184`).
  3. **Interface & Routing Table Scale**: Per-device interface count (`UP` vs `DOWN` from `device_inventory_interface`), VLAN/zone count, static/connected/dynamic route count (`device_inventory_route`), and `excluded_from_inventory` register.
- **Data Sources**: `InventoryQueryService`, `ClusterInventoryMerger`, `ManagementTreeService`, `devices`, `device_inventory_run`, `device_inventory_interface`, `device_inventory_route`, `device_inventory_ha_fact`, `device_platform_facts`.
- **Filters**: `vendor`, `role`, `cluster`, `freshness`, `include_excluded` (`true`/`false`).
- **Output Formats**: `On-screen`, `PDF`, `CSV`, `XLSX`.
- **Schedulable**: **Yes** (Weekly or Monthly CMDB/Auditor export).
- **Masking Behaviour**: `aiview` masks device names, cluster refs, interface descriptions, and IP/prefix columns via `TopologyNamePseudonymizer` and `SubnetPreservingIpMasker`; raw serial numbers are **never** printed in `aiview` (replaced by opaque `device_id` or relational `MATCH` token).

---

### RPT-11: Discovery & Unenrolled Managed Assets Gap Report
- **Audience**: `Operations` / `Security Engineering`
- **Question Answered**: *"Which firewalls, gateways, or appliances are known to our management servers (MDS, Panorama, FortiManager, Infoblox Grid, Symantec MC) in `discovery_candidate`, but are not yet enrolled, missing endpoints, or failing direct collection in neXus?"*
- **Sections**:
  1. **Manager-vs-Direct Coverage Gap**: Total candidates discovered per management server (`discovery_run`, `discovery_candidate`) vs. enrolled devices (`enrollment_state = 'ENROLLED'`) vs. unmonitored/pending candidates.
  2. **Unenrolled & Unreachable Candidate Register**: Candidates found via management discovery that lack direct SSH/API endpoints or have `NEVER` completed a direct `device_inventory_run` (highlighting where management-plane observation exists without direct-device truth).
  3. **Excluded Asset Audit**: Devices explicitly marked `excluded_from_inventory = true` or `backup_target = false` with reason/role context.
- **Data Sources**: `DiscoveryRunService`, `ManagementTreeService`, `discovery_run`, `discovery_candidate`, `devices`, `endpoints`.
- **Filters**: `vendor`, `management_server`, `enrollment_state`.
- **Output Formats**: `On-screen`, `PDF`, `CSV`, `XLSX`.
- **Schedulable**: **Yes** (Weekly).
- **Masking Behaviour**: `aiview` masks candidate hostnames, management IPs (`192.0.2.x`), and manager names.

---

### RPT-12: Path Analysis & Live Hop Validation Evidence Report
- **Audience**: `Security Engineering` / `Auditor` / `Operations`
- **Question Answered**: *"For critical traffic flows queried and validated in neXus, what was the end-to-end path verdict across routing and firewall policy hops (`A5a`), and did live CLASS_0 device matcher validation (`A6`) confirm `MATCH`, `MISMATCH`, or `NOT_EVALUABLE` on each gateway hop?"*
- **Sections**:
  1. **Path Validation Summary**: List of path queries and live validation runs (`path_validation`, `authz_decisions`) in the selected window, showing 5-tuple query parameters, topology snapshot timestamp (`topology_snapshot_id`), overall `path_verdict` (`PERMITTED`, `DENIED`, `PARTIAL`, `INCOMPLETE`), and `hops_not_validated` count.
  2. **Hop-by-Hop Model vs. Live Device Evidence (`path_validation_hop`)**: For each hop along the path: device/VS pseudonym, ingress/egress interface & zone, routed next-hop (`A5a`), matched policy rule (`A5a`), live device validation result (`MATCH`, `MISMATCH`, `NOT_EVALUABLE`, `PENDING`), and structured reason codes (`reasons`).
  3. **Topology Override & Stale Snapshot Disclosures**: Active manual topology overrides (`topology_override` `V186`) and age of each hop's underlying route/policy snapshot at the time of evaluation.
- **Data Sources**: `PathValidationService`, `PathValidationRepository`, `TopologyService`, `path_validation`, `path_validation_hop`, `topology_snapshot`, `topology_override`, `authz_decisions`.
- **Filters**: `date_range`, `validation_id` (optional specific run or all runs in window), `path_verdict`, `hop_result` (`MISMATCH`, `NOT_EVALUABLE`, `MATCH`).
- **Output Formats**: `On-screen`, `PDF` (change-ticket / audit evidence pack), `CSV`, `XLSX`.
- **Schedulable**: **No** (On-demand / per-validation run or date-window export; saved to History).
- **Masking Behaviour**: `aiview` masks source/destination IPs (`192.0.2.x`), hop device/cluster/VS names, interface names, and matched rule names.

---

### RPT-13: Device System Health, CPU/Memory p95 & Crash/Dump Digest
- **Audience**: `Operations`
- **Question Answered**: *"Which Check Point and Palo Alto gateways are struggling with sustained high CPU/memory p95, repeated `cpwd` process restarts, or new post-baseline crash/core dumps (`device_crash_alert`), and are there gaps in daily system-log collection?"*
- **Sections**:
  1. **Fleet Health State Summary (`SystemHealthService`)**: Devices classified by deterministic worst-fact rule (`CRITICAL > ATTENTION > UNKNOWN > NORMAL`) over the 7-day window.
  2. **New Crash & Core Dump Alerts (`device_crash_alert`)**: Post-baseline crash files detected in the window (`first_seen_at`, `device_id`, `source_key`, `file_bytes`, `device_mtime` — file names are never stored in clear and never printed, per `V160` and `AGENTS.md`).
  3. **CPU & Memory 7-Day p95 Outliers (`SarDailyText`)**: Check Point gateways exceeding the CPU p95 threshold (`85%`) for $\ge 3$ consecutive days (`CRITICAL`) or $1\text{–}2$ days (`ATTENTION`), plus `%memused` p95; Palo Alto explicitly marked `NOT_APPLICABLE` for `sar`.
  4. **Process Restart Spikes (`CpwdRestartLines`) & Log Gaps (`system_log_gap`)**: Gateways with $\ge 3$ `cpwd` restarts in an hour, plus any rotation/collection gaps recorded in `system_log_gap`.
- **Data Sources**: `SystemHealthService`, `SystemLogReadService`, `system_log_run`, `system_log_chunk`, `system_log_gap`, `device_crash_alert`, `system_log_sar`.
- **Filters**: `date_range` (default 7 days), `vendor` (`Check Point`, `Palo Alto`), `health_state` (`CRITICAL`, `ATTENTION`, `UNKNOWN`, `NORMAL`).
- **Output Formats**: `On-screen`, `PDF`, `CSV`, `XLSX`.
- **Schedulable**: **Yes** (Daily `06:30 Europe/Istanbul` after the `02:00–05:00` system-log collection window, or Weekly).
- **Masking Behaviour**: `aiview` masks device names; crash filenames are already HMAC-only in the database.

---

### RPT-14: Security Audit, RBAC Access & Unmasked-View Ledger Report
- **Audience**: `Auditor` / `Executive` (CISO / Internal Audit)
- **Question Answered**: *"Who accessed or modified neXus settings, credentials, failover approvals, or device states, how are RBAC roles and LDAP/local identities bound, and every time an operator requested an unmasked system-log or raw packet-analysis view, when and why did it happen?"*
- **Sections**:
  1. **Administrative & Mutating Action Summary (`audit_log`)**: Breakdown of audited actions by `action_id`, `operation` (`INSERT`, `UPDATE`, `DELETE`, `EXECUTE`), target `table_name`, and `actor_fingerprint`.
  2. **High-Privilege & Sensitive Access Ledger**: Dedicated section combining:
     - `system_log_unmasked_access_ledger` (every unmasked system-log page read);
     - `packet_analysis_ledger` (every raw packet analysis report view/download);
     - `backup_size_anomaly_accept` decisions;
     - Failover approvals and executions (`failover_request_approval`).
  3. **Identity, Role Binding & Session Posture**: Summary of active `rbac_roles`, `role_bindings` (LDAP group vs. local identity counts), `directory_settings` status, and session revocation/expiry counts (`sessions`).
- **Data Sources**: `AuditLogQueryService`, `audit_log`, `authz_decisions`, `system_log_unmasked_access_ledger`, `packet_analysis_ledger`, `local_identities`, `rbac_roles`, `role_bindings`, `sessions`.
- **Filters**: `date_range`, `action_id`, `table_name`, `actor_fingerprint`.
- **Output Formats**: `On-screen`, `PDF`, `CSV`, `XLSX`.
- **Schedulable**: **Yes** (Weekly or Monthly security audit deliverable).
- **Masking Behaviour**: `aiview` shows `actor_fingerprint` tokens and masks target row identifiers (`ReportProjectionMasker`); raw actor usernames are visible only to roles holding `report_raw_view` + `audit_log_read` outside `aiview`.

---

### RPT-15: Job Execution, Collection Health & Platform Storage Capacity Report
- **Audience**: `Operations`
- **Question Answered**: *"Are scheduled and on-demand collection jobs succeeding across all capability planes, which devices are failing collection (and why), are jobs completing within their maintenance windows, and how much encrypted artefact and database storage is neXus consuming?"*
- **Sections**:
  1. **Job Execution Scorecard (`JobLogQueryService`)**: Total jobs in window by `job_type` / `capability_id`, `state`, and `outcome` (`SUCCEEDED`, `FAILED`, `CANCELLED`, `TIMED_OUT`), with p50/p95 duration.
  2. **Failing Devices & Terminal Reasons**: Top devices with repeated collection failures (`terminal_reason`, `ssh_identity_unverified = true` blocks from `V165`, endpoint admission quarantines from `endpoint_admission`).
  3. **Job Window Utilization**: Compliance of background collectors with configured `job_windows` (`02:00–05:00 Europe/Istanbul`).
  4. **Platform Storage & Pod Health (`SystemStatusService`)**: Encrypted artefact store footprint (`backup_artefact` bytes, system-log chunk bytes, packet-analysis volume bytes, free filesystem bytes), PostgreSQL database size, and microservice module health (`available` or explicit reason if K8s token is absent).
- **Data Sources**: `JobLogQueryService`, `SystemStatusService`, `jobs`, `endpoint_admission`, `runtime_task_lease`, `backup_artefact`.
- **Filters**: `date_range`, `job_type`, `outcome`, `vendor`, `device_id`.
- **Output Formats**: `On-screen`, `PDF`, `CSV`, `XLSX`.
- **Schedulable**: **Yes** (Daily or Weekly operations health digest).
- **Masking Behaviour**: `aiview` masks `target_device_id` / hostnames and actor fingerprints.

---

### RPT-16: SSH Host-Key Trust, Device Identity & Appliance TLS Posture Report
- **Audience**: `Security Engineering` / `Auditor`
- **Question Answered**: *"Are all SSH endpoints pinned and verified against `ssh_host_key_trust` (`V147`) and `device_identity_check` (`V165`), were any jobs blocked due to host-key mismatch or unverified identity, and what is the expiry/trust state of appliance HTTPS certificates (`V108`)?"*
- **Sections**:
  1. **SSH Host-Key Trust Coverage (`ssh_host_key_trust`)**: Endpoints with pinned host keys (`VERIFIED`), pending/unpinned endpoints, and key algorithm distribution (`ssh-ed25519`, `rsa-sha2-256`, etc.).
  2. **Device Identity Verification (`device_identity_check` & `jobs.ssh_identity_unverified`)**: Results of transport-to-device identity checks (`MATCH`, `MISMATCH`, `UNVERIFIED`) and count of jobs refused by the `V165` fail-closed identity gate.
  3. **Appliance & Infoblox Certificate Lifecycle**: Installed neXus appliance certificate status (`https_appliance_certificate`) and collected device/grid certificate expiry windows where available.
- **Data Sources**: `SshHostKeyController` / `SshHostKeyTrustRepository`, `HttpsCertificateController`, `ssh_host_key_trust`, `device_identity_check`, `https_appliance_certificate`, `jobs`.
- **Filters**: `vendor`, `trust_state` (`VERIFIED`, `MISMATCH`, `UNPINNED`), `date_range`.
- **Output Formats**: `On-screen`, `PDF`, `CSV`, `XLSX`.
- **Schedulable**: **Yes** (Monthly).
- **Masking Behaviour**: `aiview` masks device names and endpoint IPs (`192.0.2.x`), and reports host-key fingerprints as relational status (`PINNED_MATCH` / `MISMATCH` / `MISSING`) without echoing raw key material per `AGENTS.md` Sensitive Identity Reporting Law.

---

## 3. Reports Screen UX

### 3.1 Left-Rail Placement
In [`ui2/frontend/src/App.tsx`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/frontend/src/App.tsx) and [`NAVIGATION_INFORMATION_ARCHITECTURE.md`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md), the left rail currently has 5 groups (`Overview`, `Inventory`, `Operations`, `Assurance`, `Administration`), while `UI_REVIEW_2026_11_05_SYNTHESIS.md` (`A1`/`C1`) proposes tightening the rail to 4 groups (`Overview`, `Estate`, `Operations`, `Administration`).
- **Recommended Placement**: Place **Reports** (`#reports`) as a **first-class item under `Assurance`** (immediately below `Compliance`, or under `Overview` if the 4-group consolidation merges `Assurance`), **and** add a **"Weekly Executive Summary"** one-click action button in the top-right header of the **Overview** screen (`#overview`) that opens `#reports?template=RPT-01&autorun=preview` (directly fulfilling item `C6` of `UI_REVIEW_2026_11_05_SYNTHESIS.md`).

### 3.2 Workspace Layout (4 Sub-Tabs inside `#reports`)

```
+--------------------------------------------------------------------------------------------------+
| Reports                                                    [Persona Badge: AIView Masked | Raw]  |
| Predefined operational, compliance, resilience, and executive deliverables from stored evidence. |
|                                                                                                  |
| [ 1. Report Catalogue ]   [ 2. Run & Preview ]   [ 3. Generated History (14) ]   [ 4. Schedules ]|
+--------------------------------------------------------------------------------------------------+
```

1. **Tab 1: Report Catalogue (`Catalogue Grid`)**
   - Filterable by **Family** (`All`, `Executive & Assurance`, `Resilience & Backups`, `Policy, Config & Path`, `Asset, Lifecycle & Platform`) and **Audience** (`Executive`, `Auditor`, `Operations`, `Security Engineering`).
   - Each card shows:
     - Report ID & Title (`RPT-01 · Executive Weekly Fleet & Security Posture Summary`);
     - Audience pill + format badges (`Screen`, `PDF`, `CSV`, `XLSX`) + `Schedulable` badge;
     - The exact 1-sentence question the report answers;
     - **Live Evidence Freshness Indicator** for its underlying planes (e.g., `Config: 4h ago (Fresh) · Backups: 5h ago (Fresh) · Lifecycle: 12% No Catalog Data`);
     - Primary actions: **[ Preview & Run ]** and **[ Schedule... ]**.

2. **Tab 2: Run & Preview (`Parameter Bar + Live On-Screen Preview + Export Bar`)**
   - **Parameter Bar**:
     - `Report Template` selector (`RPT-01` .. `RPT-16`);
     - Contextual filters driven by the template's metadata: `Date Range` (`Last 24h`, `Last 7d`, `Last 30d`, `Custom`), `Vendor` multi-select, `Management Domain / Cluster` selector, `Framework` selector (`CIS Benchmark`, `NIST SP 800-53`, `PCI-DSS v4.0.1`, `Financial Baseline`);
     - `Projection Mode` toggle: Locked to `MASKED (AIView)` for `role:replay_viewer`; toggleable between `MASKED (Recommended for sharing)` and `RAW (Restricted)` for roles holding `report_raw_view`;
     - Action buttons: **[ Run On-Screen Preview ]**, **[ Export PDF ]**, **[ Export XLSX ]**, **[ Export CSV ]**.
   - **Mandatory `"Data As Of"` & Trust Header (Top of Every Preview and Exported File)**:
     ```
     +--------------------------------------------------------------------------------------------+
     | DATA AS OF & EVIDENCE COVERAGE STAMP                                                       |
     | Generated: 2026-11-05 09:40 UTC (12:40 Europe/Istanbul) | Projection: MASKED (AIView)      |
     | Underlying Evidence Age: Inventory max 3h (94% <24h, 6% >72h) | Config max 4h | Backups 7h |
     | Fail-Closed Disclosure: 14 of 198 devices (7.1%) lack <24h config evidence -> UNAVAILABLE  |
     +--------------------------------------------------------------------------------------------+
     ```
   - **On-Screen Preview Body**: Renders the structured report sections (KPI summary cards, breakdown tables, explicit `UNKNOWN` / `NOT_COMPARED` callout boxes) using deterministic numeric templates (no generative prose).

3. **Tab 3: Generated History (`Saved Report Artefacts`)**
   - Lists previously generated PDF/XLSX/CSV report runs stored in `report_run` (default 30-day retention).
   - Columns: `Generated At`, `Report Name`, `Trigger` (`Manual` vs `Scheduled`), `Parameters Summary`, `Data As Of`, `Projection` (`MASKED` / `RAW`), `Format & Size`, `Expires At`, and **[ Download ]** / **[ Delete ]**.
   - **Strict Masking Gate on History**: An `aiview` (`role:replay_viewer`) session can only see and download history rows whose `projection_mode = 'MASKED'`; `RAW` artefacts are hidden and return `403 Forbidden` if requested by `aiview`.

4. **Tab 4: Schedules & Delivery (`Cron + Notification Route Reuse`)**
   - Lists configured recurring schedules (`report_schedule`: e.g., *"Weekly Executive Summary — Mondays 07:30 Europe/Istanbul — PDF (MASKED)"*).
   - **Recipient & Route Reuse**: Reuses [`notification_settings`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/NotificationSettingsController.java) and [`SmtpRelaySender`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/notification/SmtpRelaySender.java) via a new `notification_routes` event type (`scheduled_report_delivery`), with optional schedule-specific recipient addresses validated by the same recipient validator.

### 3.3 Empty & Stale States
- **Empty State (No Evidence Collected Yet)**: If a report is run when a required plane has zero rows (e.g., no `path_validation` runs in the selected 7-day window, or no `lifecycle_catalog` entries imported), the report still renders its header and sections with an explicit `NO_EVIDENCE_IN_WINDOW` banner explaining which collection or catalog import is empty—never a blank screen or silent `0%` / `100%` pass rate.
- **Stale State (`>24 h` / `>72 h` Evidence)**: Following `UI_REVIEW_2026_11_05_SYNTHESIS.md` (`B1`), whenever any device included in a report has primary evidence older than `24 h`, a prominent warning banner (`STALE_EVIDENCE_PRESENT: N devices have evidence >24h old; M devices >72h old`) is rendered at the top of both the on-screen preview and the exported PDF/XLSX/CSV.

---

## 4. Architecture

### 4.1 Where Reports Run: Service vs. Worker (`Admission & Job Windows`)
- **Zero-Network Invariant**: Because predefined reports query **only PostgreSQL and the local encrypted `FileArtefactStore`** (never opening SSH/HTTPS connections to network devices), report generation is a **pure local read/compute operation**.
- **Execution Placement**:
  - **On-Screen Preview & Fast CSV Exports (<2 s)**: Executed synchronously inside `ui2-service` on a bounded `ReportExecutionPool` (`Semaphore(4)` concurrent report builds, read-only database transaction `SET TRANSACTION READ ONLY`, statement timeout `30s`).
  - **PDF / XLSX Artefact Generation & Scheduled Reports**: Submitted as a lightweight local platform task (`report_run` state `QUEUED -> RUNNING -> SUCCEEDED / FAILED`) executed by an in-service bounded background executor (`nexus-report-worker`, 2 threads).
  - **Job Window Independence**: Because report generation does **not** touch `endpoint_admission` or network devices, it is **exempt from [`JobWindowPolicy`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/common/src/main/java/com/securityexpert/nexus/ui2/platform/JobWindowPolicy.java) (`02:00–05:00`)**—operators and executives can generate reports at any hour of the business day without contending with device-collection permits.
  - **Scheduled Report Timing**: Default cron presets for daily operational reports (`RPT-03`, `RPT-13`, `RPT-15`) are scheduled at **`06:00–07:30 Europe/Istanbul`**, immediately *after* the `02:00–05:00` backup and system-log collection windows finish, guaranteeing fresh `<4 h` evidence.

### 4.2 Storage of Generated Reports (Encryption, Retention, Size Limits)
- **Encrypted Artefact Storage (`FileArtefactStore` + `ArtefactStoreCipher`)**:
  - Extend [`ArtefactClass`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/artefact/ArtefactClass.java) with `public static final String REPORT = "report";`.
  - Every persisted report file (`.pdf`, `.xlsx`, `.csv`) is written through the existing AES-256-GCM [`ArtefactStoreCipher`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/artefact/ArtefactStoreCipher.java) into `FileArtefactStore` (`backup_artefact` row with `artefact_class = 'report'`, `device_id = 'platform:reports'`, `plaintext_bytes`, `plaintext_sha256`, `ciphertext_bytes`, `ciphertext_sha256`, `key_id`).
- **Retention & Bounds**:
  - Default retention: **30 days** for manual and daily scheduled reports; **90 days** for weekly/monthly executive & auditor reports, enforced by the existing `RetentionJanitor` (`backup_retention_ledger`).
  - Size cap: Hard cap of **25 MiB** per generated report file (`MAX_REPORT_BYTES = 25 * 1024 * 1024`); e-mail attachments capped at **10 MiB** (if a scheduled report exceeds 10 MiB, the e-mail sends the executive summary body + a link to `#reports?runId=...` instead of overflowing the SMTP relay).

### 4.3 PDF, XLSX & CSV Rendering Without Heavy New Dependencies
Inspecting [`ui2/gradle/libs.versions.toml`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/gradle/libs.versions.toml) confirms what is already on the classpath:
- **XLSX**: `org.apache.poi:poi-ooxml:5.3.0` is **already in `libs.versions.toml`** and used by [`ExpertReport`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/packet-analysis/src/main/java/com/securityexpert/nexus/ui2/packetanalysis/export/ExpertReport.java) in `ui2/packet-analysis`. We reuse the exact deterministic multi-sheet workbook pattern (`SXSSFWorkbook` / `XSSFWorkbook` with fixed styles, cover sheet with `"Data as of"` metadata, and data sheets) with **zero new dependencies**.
- **CSV**: Pure Java RFC 4180 streaming writer with **spreadsheet formula-injection sanitization** (prefixing any cell starting with `=`, `+`, `-`, `@`, `\t`, `\r` with a single quote `'`), matching `/hygiene.csv` and `/changes.csv`.
- **PDF (Two Options — Zero Heavy Native/Chromium Dependencies)**:
  - **Option A (Zero New External Jar — Pure Java Minimal PDF 1.4 Page Writer)**: A self-contained ~450-line deterministic PDF 1.4 generator (`NexusPdfReportWriter`) in `ui2-service` using built-in Type1 fonts (`Helvetica`, `Helvetica-Bold`, `Courier`) or an embedded Apache-2.0 TrueType font (`Inter`/`LiberationSans`), rendering structured executive header banners, KPI boxes, horizontal stacked bars, pagination footers (`Page X of Y | Projection: MASKED | Data as of ...`), and auto-wrapped tables. Zero third-party library risk, zero native binary dependencies, deterministic byte output.
  - **Option B (Single Pure-Java Library — `com.github.librepdf:openpdf:2.0.3`, LGPL/MPL)**: If rich multi-page table row-spanning is desired with less layout code, `openpdf` is a single ~2.5 MiB pure-Java jar with no native dependencies.
  - **Recommendation**: Use **Option A** (or Option B if PO approves adding one pure-Java jar to `libs.versions.toml`), and **explicitly reject headless Chromium/Playwright/wkhtmltopdf** in the runtime container to preserve container security and memory bounds.

### 4.4 RBAC Actions (`ActionRegistry`) & Audit Trail
Add 4 actions to [`ActionRegistry.java`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/ActionRegistry.java):
1. `report_read` (`CLASS_0`): View the `#reports` catalogue, run on-screen previews, and list generated report history (granted to `security_admin`, `network_operator`, `auditor`, `executive_viewer`, and `replay_viewer` / `aiview`).
2. `report_generate` (`CLASS_0`): Generate and download a persisted report artefact (`PDF`, `XLSX`, `CSV`) (granted to `security_admin`, `network_operator`, `auditor`, `executive_viewer`, and `replay_viewer` — where `replay_viewer` is strictly forced to `projection_mode = 'MASKED'`).
3. `report_raw_view` (`CLASS_1`): Generate or download a report with `projection_mode = 'RAW'` (granted **only** to `security_admin` and authorized human operational roles; **never** granted to `replay_viewer` / `aiview`).
4. `report_schedule_manage` (`CLASS_1`): Create, update, enable/disable, or delete scheduled report deliveries and recipients (granted to `security_admin`).

**Per-Report Underlying Permission Check & Ledgering**:
- Generating a report also verifies that the caller holds the read action for the underlying domain (e.g., `RPT-14` Audit Report requires `audit_log_read`).
- Every report generation and download writes a row to `audit_log` (recording `template_id`, `projection_mode`, `format`, `parameters_json`, and `artefact_id`). Whenever a `RAW` report is generated or downloaded, it is recorded in `audit_log` before the first byte is streamed.

### 4.5 Masking in Reports (`The Masked Projection`)
- **Single Masking Pipeline Before Rendering**:
  - Every report builder produces a canonical `ReportDocument` model (`ReportHeader`, `EvidenceFreshnessStamp`, `List<ReportSection>`, `List<ReportTable>`).
  - Before the `ReportDocument` is handed to the JSON serializer (on-screen preview), `NexusPdfReportWriter` (PDF), `NexusXlsxReportWriter` (XLSX), or `NexusCsvReportWriter` (CSV), it passes through `ReportDocumentMasker` (an extension of [`ReportProjectionMasker`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/privacy/ReportProjectionMasker.java) using [`TopologyNamePseudonymizer`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/privacy/TopologyNamePseudonymizer.java) and [`SubnetPreservingIpMasker`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/privacy/SubnetPreservingIpMasker.java)).
  - Additionally, all JSON responses from `/api/v2/reports/**` remain protected by [`PrivacyMaskingResponseBodyAdvice`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/privacy/PrivacyMaskingResponseBodyAdvice.java) for `role:replay_viewer`.
  - Because masking runs on the structured `ReportDocument` **before** PDF/XLSX/CSV byte rendering, a `MASKED` PDF or XLSX file is guaranteed to contain only deterministic pseudonyms (`FW-TANGO-04`, `CLS-ROMEO-01`, `192.0.2.x`) and zero raw hostnames, serials, or customer IPs.

### 4.6 Mail Delivery Extension (`SmtpRelaySender`)
- Currently, [`SmtpRelaySender`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/notification/SmtpRelaySender.java) sends `text/plain; charset=UTF-8` bodies via raw SMTP `DATA`.
- In Phase `R2`, extend `SmtpRelaySender` with a `sendWithAttachment(...)` overload that formats a standard **RFC 2045/2046 `multipart/mixed` MIME message** (plain-text executive summary body + Base64-encoded PDF/XLSX attachment ≤10 MiB, using pure JDK `java.util.Base64.getMimeEncoder()`), requiring **zero `jakarta.mail` dependency**.
- Add `'scheduled_report_delivery'` to the `notification_routes_event_type_check` constraint (following the exact pattern of [`V189__backup_size_anomaly_notification.sql`](file:///Users/OzanDur/Codo/nexus-reportsdesign-1105/ui2/persistence/src/main/resources/db/migration/V189__backup_size_anomaly_notification.sql)).

### 4.7 Schema Changes (`V190__reports_engine_and_schedules.sql`)
Only **2 new tables** plus the `notification_routes` check-constraint update are required:

```sql
-- V190__reports_engine_and_schedules.sql

CREATE TABLE report_schedule (
    schedule_id            UUID PRIMARY KEY,
    template_id            VARCHAR(64)  NOT NULL, -- e.g. 'RPT-01'
    name                   VARCHAR(160) NOT NULL,
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    cron_expression        VARCHAR(64)  NOT NULL, -- evaluated in Europe/Istanbul
    formats                VARCHAR(64)  NOT NULL DEFAULT 'PDF', -- 'PDF', 'XLSX', 'CSV', or 'PDF,XLSX'
    projection_mode        VARCHAR(16)  NOT NULL DEFAULT 'MASKED'
                           CHECK (projection_mode IN ('MASKED', 'RAW')),
    parameters_json        TEXT         NOT NULL DEFAULT '{}',
    use_notification_route BOOLEAN      NOT NULL DEFAULT TRUE,
    custom_recipients      TEXT,
    created_by             VARCHAR(128) NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_run_at            TIMESTAMPTZ,
    next_run_at            TIMESTAMPTZ
);

CREATE TABLE report_run (
    run_id                 UUID PRIMARY KEY,
    template_id            VARCHAR(64)  NOT NULL,
    schedule_id            UUID REFERENCES report_schedule(schedule_id) ON DELETE SET NULL,
    trigger_type           VARCHAR(16)  NOT NULL CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    state                  VARCHAR(16)  NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    projection_mode        VARCHAR(16)  NOT NULL CHECK (projection_mode IN ('MASKED', 'RAW')),
    format                 VARCHAR(16)  NOT NULL CHECK (format IN ('JSON', 'PDF', 'XLSX', 'CSV')),
    parameters_json        TEXT         NOT NULL DEFAULT '{}',
    data_as_of_json        TEXT         NOT NULL DEFAULT '{}', -- per-plane timestamps & coverage %
    artefact_id            VARCHAR(128), -- references backup_artefact(artefact_id) where artefact_class='report'
    plaintext_bytes        BIGINT,
    error_code             VARCHAR(64),
    error_message          TEXT,
    requested_by           VARCHAR(128) NOT NULL,
    requested_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at            TIMESTAMPTZ,
    expires_at             TIMESTAMPTZ  NOT NULL,
    email_delivery_state   VARCHAR(32)  CHECK (email_delivery_state IN ('NOT_REQUESTED', 'SENT', 'FAILED', 'SKIPPED_NO_ROUTE'))
);

CREATE INDEX idx_report_run_requested_at ON report_run (requested_at DESC);
CREATE INDEX idx_report_run_template_id  ON report_run (template_id, requested_at DESC);

-- Extend notification_routes event_type constraint to include 'scheduled_report_delivery'
ALTER TABLE notification_routes DROP CONSTRAINT notification_routes_event_type_check;
ALTER TABLE notification_routes ADD CONSTRAINT notification_routes_event_type_check CHECK (
    event_type IN (
        'configuration_major_deviation',
        'configuration_minor_deviation',
        'backup_failure',
        'policy_change',
        'high_risk_rule',
        'failover_readiness_degraded',
        'failover_execution_outcome',
        'ha_state_change',
        'device_crash_file',
        'backup_size_anomaly',
        'scheduled_report_delivery'
    )
);

INSERT INTO notification_routes (event_type, enabled, recipient_addresses, updated_at, updated_by)
VALUES ('scheduled_report_delivery', FALSE, '', NOW(), 'system:v190')
ON CONFLICT (event_type) DO NOTHING;
```

---

## 5. Phasing, Effort & Risks

| Phase | Scope & Deliverables | Effort | Key Risks & Mitigations |
|---|---|---|---|
| **Phase R1: Core Reports Workspace & Initial 4 High-Impact Reports** | - Left-rail `#reports` container (`Catalogue`, `Run & Preview`, `Generated History` tabs) + Overview quick-action button (`C6`)<br>- `ReportDocument` canonical model, `"Data as of"` & `UNKNOWN` coverage banner, `ReportDocumentMasker` (`MASKED` vs `RAW`)<br>- Pure-Java `NexusPdfReportWriter`, POI `NexusXlsxReportWriter`, formula-safe `NexusCsvReportWriter`<br>- Encrypted storage in `FileArtefactStore` (`ArtefactClass.REPORT`), `V190` schema (`report_run`), `ActionRegistry` gates<br>- **4 Initial Predefined Reports**:<br>  1. `RPT-01`: Executive Weekly Fleet & Security Posture Summary (`C6`)<br>  2. `RPT-02`: Framework Compliance & Evidence Coverage Report (`CIS`, `NIST`, `PCI-DSS`, `Financial Baseline` with honest `UNAVAILABLE` split)<br>  3. `RPT-03`: Backup Coverage, Retention & Size/Log Anomaly Report (`size_anomaly`, `fleet_large`, `firewall_logs`)<br>  4. `RPT-06`: Policy Hygiene, Unused Rules & `UNKNOWN` Coverage Report | **M** | - **Risk**: PDF table pagination/wrapping glitches on long policy or control names.<br>  *Mitigation*: Fixed column width budgets, deterministic line wrapping, and automated golden-render tests in `ui2-service`.<br>- **Risk**: Sensitive data leakage into `MASKED` PDF/XLSX.<br>  *Mitigation*: Apply `ReportDocumentMasker` to the canonical `ReportDocument` *before* any format renderer runs; add privacy gate test asserting zero unmasked hostnames/IPs in generated PDF/XLSX/CSV bytes. |
| **Phase R2: Scheduled Reports, E-mail Delivery & Next 5 Operational Reports** | - `Schedules & Delivery` tab in `#reports` + `report_schedule` cron runner (`Europe/Istanbul`)<br>- `SmtpRelaySender` MIME `multipart/mixed` attachment support + `scheduled_report_delivery` route in `notification_routes`<br>- **5 Additional Reports**:<br>  5. `RPT-05`: HA Cluster & Controlled Failover Readiness Report<br>  6. `RPT-07`: Policy Change, Rule History & Installation Drift Report<br>  7. `RPT-08`: Configuration Change, Cluster Member Diff & Cross-Plane Drift Report<br>  8. `RPT-09`: Software & Hardware Lifecycle (EoS / EoL) & Hotfix Posture Report<br>  9. `RPT-10`: Fleet Asset, Virtual System & Interface/Routing Inventory Report | **M** | - **Risk**: Large XLSX/PDF attachments rejected by corporate SMTP relay size limits.<br>  *Mitigation*: Enforce 10 MiB attachment ceiling; if exceeded, attach the compact Executive Summary PDF and include a link to download the full XLSX from `#reports` History.<br>- **Risk**: Scheduled report e-mailed to broad distribution lists with raw identities.<br>  *Mitigation*: Default `report_schedule.projection_mode` to `MASKED` unless `security_admin` explicitly unlocks `RAW` with a warning confirmation. |
| **Phase R3: Complete Catalogue (Remaining 7 Reports) & Executive Trend Deltas** | - **Remaining 7 Predefined Reports**:<br>  10. `RPT-04`: Backup Archive Index & Configuration Section Drift Report<br>  11. `RPT-11`: Discovery & Unenrolled Managed Assets Gap Report<br>  12. `RPT-12`: Path Analysis & Live Hop Validation Evidence Report<br>  13. `RPT-13`: Device System Health, CPU/Memory p95 & Crash/Dump Digest<br>  14. `RPT-14`: Security Audit, RBAC Access & Unmasked-View Ledger Report<br>  15. `RPT-15`: Job Execution, Collection Health & Platform Storage Capacity Report<br>  16. `RPT-16`: SSH Host-Key Trust, Device Identity & Appliance TLS Posture Report<br>- Week-over-week snapshot comparison (comparing current `RPT-01` run against the previous week's stored `report_run.data_as_of_json`) | **M–L** | - **Risk**: Heavy multi-domain queries (`RPT-13` `SystemHealthService` sar parsing or `RPT-06` fleet-wide policy hygiene) slowing down interactive requests.<br>  *Mitigation*: Reuse `SystemHealthService`'s cached in-memory snapshot and run fleet-wide report builds on the bounded `nexus-report-worker` pool with read-only jOOQ transactions. |

---

## 6. Open Questions for the Product Owner

1. **Primary vs. Secondary Compliance Frameworks in `RPT-01` & `RPT-02` (`B4` alignment)**:
   The prompt specifies `CIS Benchmark` and `NIST SP 800-53` as primary and `PCI-DSS v4.0.1` + `Financial Baseline` as secondary. Should `Financial Baseline` remain secondary in the `RPT-01` Executive Weekly Summary, or should `RPT-01` show a 3-column primary headline (`CIS`, `NIST`, `Financial Baseline`) with `PCI-DSS` in the secondary breakdown?
2. **Left-Rail Placement (`Assurance > Reports` vs. Top-Level Group)**:
   Should **Reports** live under the existing **Assurance** rail section (next to `Compliance`), or should it be placed directly under **Overview** when the left rail is consolidated per `UI_REVIEW_2026_11_05_SYNTHESIS.md` (`A1`)?
3. **Default Projection Mode for Scheduled E-mail Reports**:
   Should scheduled e-mail reports (`R2`) always be forced to `MASKED (AIView)` because e-mail leaves the appliance boundary, or may a `security_admin` configure a schedule to send `RAW` attachments to internal corporate recipients?
4. **PDF Rendering Choice (`Zero-Dependency PDF Writer` vs. `OpenPDF`)**:
   Do you prefer the zero-dependency pure-Java PDF 1.4 writer inside `ui2-service` (no new Gradle library), or are you comfortable adding `com.github.librepdf:openpdf` (single pure-Java jar, no native dependencies) for richer multi-page table layout?
5. **Phase `R1` Starter Quartet**:
   Does the proposed `R1` starter set of 4 reports (`RPT-01` Executive Weekly Summary, `RPT-02` Framework Compliance, `RPT-03` Backup Coverage & Size/Log Anomalies, and `RPT-06` Policy Hygiene) match your immediate priority, or would you swap `RPT-06` for `RPT-05` (HA/Failover Readiness) or `RPT-09` (Software Lifecycle / EoL) in `R1`?

---

### `SESSION CLOSE`
- **Outcome**: Read-only design consultation completed (`READ_ONLY_AUDIT` / `ARCHITECTURE`); no repository files were modified.
- **Next Recommended Movement**: `ARCHITECTURE` (`Low–Medium` reasoning tier) — freeze the `REPORTS_CONTRACT.md` design contract once the Product Owner resolves the 5 open questions above, then proceed to `Phase R1` implementation (`IMPLEMENTATION`, `Medium` tier).

---

<!-- Appended by the engineer (PO assistant), not part of Argon's unaltered output above. -->
## Engineer decisions on the open questions (2026-11-05; PO delegated model and design calls)

1. **Frameworks:** RPT-01/RPT-02 headline CIS and NIST (PO decision 2026-11-05); PCI-DSS and Financial Baseline are
   secondary.
2. **Placement:** Reports is a top-level rail entry directly under Overview, because it is executive-facing (C6). It
   is not placed under Assurance.
3. **E-mail:** scheduled e-mail reports are always MASKED in R2, because e-mail leaves the appliance boundary. RAW
   reports can only be generated and downloaded in-app by roles with `report_raw_view`.
4. **PDF:** OpenPDF (a single pure-Java jar) instead of a hand-written ~450-line PDF writer. A home-made PDF writer is
   long-term maintenance and correctness risk. The jar goes through the existing dependency and CVE scan before
   merge. Headless browsers are rejected, as Argon recommends.
5. **R1 set:** RPT-01, RPT-02, RPT-03 and **RPT-05** (HA/failover readiness, which serves the failover pilot).
   RPT-06 (policy hygiene) moves to R2: about 85% of rules are still UNKNOWN, so a hygiene report today would mostly
   say "not known". It ships once the UNKNOWN share drops.
6. **Placement versus the analysis-container direction:** report builds run on the bounded pool in ui2-service, as
   designed. When the analysis container lands, heavy builds (policy hygiene, path evidence) move with it.

Status: DRAFT design. R1 implementation goes to Sonnet lanes in the next PO session.
