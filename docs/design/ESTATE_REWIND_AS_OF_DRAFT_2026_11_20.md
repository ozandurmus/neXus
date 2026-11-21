# Estate Rewind ("As of T") — Historical Evidence Audit and Backend Design

**Status:** `DRAFT` — for Product Owner review and approval. Not implementation authority beyond the first read-only backend slice (`GET /api/v2/estate/as-of?at=<ISO>`) authorized by the 2026-11-20 Rewind brief. Zero new or changed network-device commands, gate rows, polling frequencies, or device write paths are proposed or authorized here.

**Design context:** Overwatch bold round (`opus-bold`: one Estate wall of units questioned through lenses, plus a header evidence clock that can be turned back to read stored evidence as of a past moment `T` and compare `Moment A -> Moment B`).

---

## 1. Core Semantics and Constitutional Invariants

1. **Stored evidence only:** Rewind reads PostgreSQL rows (and, where a future slice explicitly reads stored text, existing encrypted artefacts). Rewind **never** contacts a network device, queues a job, or triggers an external evaluation.
2. **Strict causal cutoff (`<= T`):** "As of `T`" means *the newest stored observation whose evidence timestamp is at or before `T`*. If a unit has no observation at or before `T`, or if a single-row table overwrote its pre-`T` observation with a different post-`T` state, the answer as of `T` MUST be `UNKNOWN` — never the next read after `T` (`AGENTS.md` "UNKNOWN / fail-closed law").
3. **Age is measured relative to `T`:** At rewound moment `T`, an observation recorded at `E <= T` has age `T - E`. A readiness run or backup that was fresh at `T` (`T - E <= 24 h`) was fresh then even if `now() - E` is weeks old today; conversely, an observation already older than 24 h at `T` is marked `STALE` as of `T`.
4. **Warn, do not refuse, on policy grounds (PO 2026-11-18):** Syntactically invalid timestamps (`at` missing or not an ISO-8601 instant with offset) return `400 TIME_INVALID`. Valid timestamps in the future (`at > now`) or earlier than operational retention windows are **not** refused: the endpoint answers from stored evidence `<= at`, emits a structured entry in `warnings`, and reports `UNKNOWN` per unit where history does not reach.
5. **Mandatory AIView masking (`AGENTS.md`):** Raw calculations join on opaque `device_id` and raw `cluster_member_ref` internally, then project deterministic pseudonyms (`FW-TANGO-04`, `CLS-ROMEO-01`, `UNKNOWN-HOST-xxxxxx`) for `role:replay_viewer` (`aiview`), with `Cache-Control: no-cache, private` and `Vary: Cookie` so browser caches never cross sessions.

---

## 2. Audit of Stored Tables: Timestamped History vs. Overwrite-in-Place (Schema V1..V253)

Every persistent table in `ui2/service/src/main/resources/db/migration/V1..V253` falls into one of four temporal shapes:
- **Append-only**: every run/event inserts a new timestamped row; historical state `<= T` is preserved until a pruner (if any) deletes rows.
- **Append-only manifest + tombstone ledger**: manifest rows stay permanently while a separate ledger records when underlying disk bytes were pruned (`backup_artefact` + `artefact_retention_ledger`).
- **Stretch-compressed**: consecutive identical observations update `last_seen_at` on the open row and changes open a new row (`device_software_version_history`).
- **Overwrite-in-place (`ON CONFLICT DO UPDATE` / `UPDATE`)**: only the latest state per key lives in the table; earlier values exist only if an `audit_log` trigger records redacted row transitions.

### 2.1 Estate Enrollment, Identity, and Discovery

| Table (Migration) | Key Timestamp Column(s) | Storage Shape | Retention Limit | Audited (`audit_log`) | Notes for "As of `T`" |
|---|---|---|---|---|---|
| `devices` (V1, V3, V12, V21, V39, V65, V96) | `created_at`, `recorded_identity_recorded_at`, `identity_mismatch_detected_at` | Overwrite-in-place per `device_id` (hard-deleted on `deleteDevice`) | Unbounded while enrolled | Yes (`trg_audit_devices`, sensitive cols CLASS 2 redacted) | `created_at <= T` proves enrollment by `T`. Later flips of `enrollment_state`, `disabled`, `backup_target`, or `cluster_member_ref` overwrite the row; exact historical flag replay requires `audit_log`. |
| `endpoints` (V1) | `created_at` | Overwrite/replace per `device_id` | Unbounded while enrolled | Yes (`trg_audit_endpoints`) | Transport kind (`ssh`, `pan_xml_api`, `https`) is stable in practice. |
| `device_onboarding` (V77) | `started_at`, `updated_at`, `completed_at` | Overwrite per `device_id` | Unbounded while enrolled | Yes (`trg_audit_device_onboarding`) | Latest onboarding run only. |
| `device_identity_check` (V165) | `checked_at` | Overwrite per `device_id` (`ON CONFLICT DO UPDATE`) | Latest 1 row/device | No | Current transport identity verdict (`MATCH`/`MISMATCH`/`FIRST_USE`) only; prior checks not kept. |
| `ha_pair_identity` (V209) | `recorded_at`, `last_checked_at` | Overwrite per `cluster_member_ref` | Latest 1 row/pair | No | ASA/FortiGate pair serial-match state; latest check only. |
| `discovery_run` / `discovery_candidate` (V14) | `created_at`, `started_at`, `finished_at` | Append per run, swept | **24 hours** (`JooqDiscoveryRunRepository.MAX_RUN_AGE_HOURS = 24`), keeping the latest `FINISHED` run per management server | `discovery_acknowledgement` audited (V58) | Historical discovery candidates older than 24 h (except latest `FINISHED` per manager) are deleted. |

### 2.2 Jobs and Execution Ledger

| Table (Migration) | Key Timestamp Column(s) | Storage Shape | Retention Limit | Audited (`audit_log`) | Notes for "As of `T`" |
|---|---|---|---|---|---|
| `jobs` (V1, V4, V90, V91, V97, V128, V168) | `submitted_at`, `finished_at`, `last_heartbeat_at`, `business_hours_ack_at` | Append per `job_id`; mutable `state` until terminal | **Unbounded** (deleted only when owning device is deleted) | Yes (`trg_audit_jobs`) | Terminal outcome for `finished_at <= T` is exact. Jobs with `submitted_at <= T < finished_at` (or `finished_at IS NULL`) were open/in-flight at `T`. |
| `job_step_attempt` (V4) | `created_at`, `sent_at` | Append per `attempt_id` | **Unbounded** (deleted with device) | Yes (`trg_audit_job_step_attempt`) | Exact command-send (`mutation_boundary_crossed`) and response counters per step. |
| `job_reconciliation` (V4) | `recorded_at` | Overwrite per `job_id` | **Unbounded** | Yes (`trg_audit_job_reconciliation`) | Human reconciliation of `OUTCOME_UNKNOWN` jobs (`recorded_at <= T` is exact). |

### 2.3 Failover Readiness and Execution

| Table (Migration) | Key Timestamp Column(s) | Storage Shape | Retention Limit | Audited (`audit_log`) | Notes for "As of `T`" |
|---|---|---|---|---|---|
| `failover_run` (V101, V103, V109, V136, V137, V212, V247) | `scheduled_for`, `started_at`, `finished_at` | Append per `run_id`; mutable `state`/`step` until `DONE` or `STOPPED` | **Unbounded** (never pruned) | Yes (`trg_audit_failover_run`) | `run_kind = 'READINESS'` with `state IN ('DONE','STOPPED')` and `finished_at <= T` answers unit readiness (`READY` / `NOT_READY` / `UNKNOWN`) **exactly** for Check Point (ClusterXL + VSX chassis/VS), Palo Alto, Cisco ASA, and single-address FortiGate FGCP. |
| `failover_check_result` (V101..V169) | `observed_at` | Append-only per `(run_id, phase, member_ref, check_no)` | **Unbounded** (never pruned) | Yes (`trg_audit_failover_check_result`) | Exact per-check (`1..15`), per-member `PASS`/`FAIL`/`WARN`/`UNKNOWN`/`NOT_EVALUATED` and derived JSON counters as of `observed_at`. |
| `failover_approval` (V101, V137) | `approved_at`, `window_from`, `window_until`, `revoked_at`, `warning_confirmed_at` | Append per `approval_id` (`revoked_at` set once) | **Unbounded** | Yes (`trg_audit_failover_approval`) | Exact approval window active at `T`: `approved_at <= T AND window_from <= T < window_until AND (revoked_at IS NULL OR revoked_at > T)`. |
| `failover_quarantine` (V31, V136, V169) | `quarantined_at`, `acknowledged_at` | Append per `(cluster_ref, execution_id)` | **Unbounded** | Yes (`trg_audit_failover_quarantine`) | Open incident at `T`: `quarantined_at <= T AND (acknowledged_at IS NULL OR acknowledged_at > T)`. |
| `failover_resume` (V170) / `pan_config_sync` (V178) | `requested_at`, `started_at`, `finished_at` | Append per `resume_id` / `sync_id` | **Unbounded** | Yes | Exact remediation/return-step history. |
| `failover_schedule_ledger` (V31, V135) | `created_at` | Immutable hash-chained append-only (DB trigger blocks UPDATE/DELETE/TRUNCATE) | **Unbounded** | Immutable ledger | Exact schedule transition history. |

### 2.4 Backups, Artefact Vault, and Size Anomalies

| Table (Migration) | Key Timestamp Column(s) | Storage Shape | Retention Limit | Audited (`audit_log`) | Notes for "As of `T`" |
|---|---|---|---|---|---|
| `backup_artefact` (V17, V18, V30, V34, V64, V160, V190, V230) | `created_at`, `expires_at` | **Append-only manifest** (rows remain after disk pruning; deleted only on explicit admin orphan delete V70 or `deleteDevice(..., REMOVE)`) | **Manifest rows: unbounded.** Ciphertext files on disk: `backup_policy.backup_retention_days` (**7 days** live per PO 2026-10-12; column default 30 d; sole remaining backup, `backup_baseline`, and size-anomaly pins always kept); weekly snapshots: `snapshot_retention_depth` (default 2); config archives: `configuration_retention_days` (default 30 d, latest kept); reports: 30 d (`expires_at`); support bundles: `expires_at` + 24 h warning | Yes (`trg_audit_backup_artefact`) | Joined with `artefact_retention_ledger`, reconstructs **both** whether a backup existed on the recovery volume at `T` **and** whether its ciphertext is still on disk today. |
| `artefact_retention_ledger` (V17, V34) | `occurred_at` | **Append-only** (`event IN ('created','removed')`) | **Unbounded** | Yes (`trg_audit_artefact_retention_ledger`) | Archive `a` was live on the recovery volume at `T` iff `a.created_at <= T` and no `event = 'removed'` row for `a.artefact_id` has `occurred_at <= T`. |
| `backup_deviation_record` (V30, V180, V189) | `detected_at`, `resolved_at` | Append per `deviation_id`; `resolved_at` set once when accepted | **Unbounded** | Yes (`trg_audit_backup_deviation_record`) | Open size anomaly as of `T` is exact: `deviation_class = 'size_anomaly' AND detected_at <= T AND (resolved_at IS NULL OR resolved_at > T)`. |
| `backup_baseline` (V44) | `set_at` | Overwrite per `device_id` | Current baseline only | Yes (`trg_audit_backup_baseline`) | Prior baseline selections require `audit_log`. |
| `backup_artefact_content_listing` / `entry` (V41) | `listed_at` | Deleted when archive is pruned (`JooqRetentionQueries.recordRemoved`) | Matches live disk archive | No | Entry listing available only while archive bytes remain unpruned. |

### 2.5 Configuration, Saved-State, Member Drift, and Compliance

| Table (Migration) | Key Timestamp Column(s) | Storage Shape | Retention Limit | Audited (`audit_log`) | Notes for "As of `T`" |
|---|---|---|---|---|---|
| `device_configuration_run` (V16, V86) | `collected_at`, `created_at` | **Append-only** per `run_id` (`is_primary`, `read_kind`, `canonical_hash`, `change_state`, `sanitized_text`) | **Unbounded** in DB (deleted only with device) | Yes (`trg_audit_device_configuration_run`) | Exact configuration run history (`collected_at <= T`), including `canonical_hash`, `raw_bytes`, `change_state`, and child `device_configuration_index` / `device_configuration_override` / `configuration_run_deviation_summary` rows. |
| `configuration_artefact` (V16, V86) | `created_at` | Append-only per `artefact_ref` | **Unbounded** in DB; recovery-volume `backup_artefact` (`class='configuration'`) pruned after 30 d keeping latest | Yes (`trg_audit_configuration_artefact`) | Metadata and `canonical_hash` persist; raw ciphertext older than 30 d may be pruned from recovery store. |
| `asa_config_relation` (V250, V253) | `derived_at` | Keyed by `backup_artefact.artefact_id` (`MATCH`/`MISMATCH`/`NOT_EVALUABLE`, `differing_keywords`) | **Unbounded** (one row per ASA backup manifest from V253 onward) | No | Exact ASA running-vs-startup relation for any backup `created_at <= T` collected since V253; backups before V253 have no row (`UNKNOWN`). |
| `cluster_member_diff` (V52, V55) | `computed_at` | Bounded ring per `cluster_ref` | **Latest 2 rows per cluster** (`ClusterDiffTask` deletes rows older than `LIMIT 2`) | No | **Not historical** beyond 1 prior transition; arbitrary `T` cannot read `cluster_member_diff` directly (though `device_configuration_run.sanitized_text` could recompute it if retained). |
| `compliance_evaluation` (V59) | `evaluated_at` | **Overwrite-in-place** (`PRIMARY KEY (device_id)`, `ON CONFLICT DO UPDATE`); refreshed every 6 h by `ComplianceWarmup` (`MAX_AGE = 6 h`) | **Latest 1 row per firewall** | No (unaudited derived cache) | Exact when `evaluated_at <= T` (matching `cfg_at_T`), and deterministic when `cfg_at_T.collected_at <= T` has `cfg_at_T.canonical_hash = compliance_evaluation.canonical_hash` under unchanged `rules_fingerprint`. **Becomes `UNKNOWN` once a firewall's configuration changes after `T`** (overwriting the single row) or if `rules_fingerprint` changed without evaluation history. |

### 2.6 Inventory, Platform Facts, Software History, Lifecycle, and Exposure

| Table (Migration) | Key Timestamp Column(s) | Storage Shape | Retention Limit | Audited (`audit_log`) | Notes for "As of `T`" |
|---|---|---|---|---|---|
| `device_inventory_run` + `device_interface` / `address` / `device_route` / `device_inventory_ha` (V13, V20, V148, V184, V209) | `collected_at`, `created_at` | **Append-only** per `run_id` | **Unbounded** (deleted only with device) | Yes (`trg_audit_device_inventory_*`) | **Exact** runtime interface, IPv4/IPv6, route, zone, VRF, and HA-role snapshot as of `collected_at <= T`! Note: row volume grows ~4 runs/device/day. |
| `device_platform_facts` (V46) | `observed_at` | **Overwrite-in-place** per `device_id` (`serial_number`, `hotfix_level`, `platform_family`, `content_versions`, `uptime_text`) | Latest 1 row/device | No | Overwritten on each read; hotfix history is captured separately in `device_software_version_history` (from V243 onward). |
| `device_software_version_history` (V243, V244) | `first_seen_at`, `last_seen_at`, `previous_last_seen_at` | **Stretch-compressed history** (`[first_seen_at, last_seen_at]` per `(software_version, hotfix_level)`) | **Unbounded** | No | Answers software version + Jumbo hotfix at `T` when a stretch covers `first_seen_at <= T <= last_seen_at`; inside an upgrade gap `(previous_last_seen_at, first_seen_at)`, version at `T` is bounded to `{previous, new}` (`APPROXIMATE` / `UNKNOWN` inside gap). Backfilled from surviving `discovery_candidate` rows at V243; hotfix levels before V243 were not backfilled. |
| `device_install_event` (V246) | `installed_at_local`, `first_observed_at`, `last_observed_at` | Append/upsert per `(device_id, file_name, installed_at_local)` | **Unbounded** | No | Check Point Gaia base/Jumbo install log history (`DEVICE_LOCAL` clock). |
| `device_lifecycle_read` / `device_license_item` / `device_live_patch_item` (V200) | `observed_at` | **Delete + replace / upsert** per `(device_id, read_kind)` on every collection | Latest 1 read/device | No | **No history (`UNKNOWN` for `T` before latest `observed_at`).** |
| `advisory` / `advisory_product_rule` / `known_exploited_cve` / `checkpoint_jumbo_*` (V205, V214) | `loaded_at`, `published_on`, `updated_on`, `date_added` | **Upsert / truncate-replace** (`known_exploited_cve` deletes all & reloads; `checkpoint_jumbo_statement` deletes per train & reloads; `advisory` upserts by `advisory_id`) | Latest catalog only (`advisory_feed_run` keeps run log) | No | `published_on <= T` and `known_exploited_cve.date_added <= T` approximate what was published/KEV-listed by date `T`, **not** what neXus had ingested or how vendor rules looked before a later advisory update. |
| `lifecycle_catalog` (V144) / `lifecycle_vendor_entry` (V219) | `imported_at`, `confirmed_at` | Upsert / document replace | Current catalog (`lifecycle_feed_run` / `lifecycle_catalog_upload` keep run logs) | Uploads audited | Hardware/software EoS/EoL dates are static vendor product facts, but catalog membership at `T` is mutable. |

### 2.7 Policy, Changes, Topology, Zone Matrix, and Operations Previews

| Table (Migration) | Key Timestamp Column(s) | Storage Shape | Retention Limit | Audited (`audit_log`) | Notes for "As of `T`" |
|---|---|---|---|---|---|
| `policy_snapshot` (V115) / `cp_policy_object_inventory` (V124) / `cp_policy_json_chunk` (V126) | `collected_at` | **Overwrite-in-place** per `policy_id` / `(source_id, domain_ref, object_type)` | Latest complete snapshot only | No | Full rulebase and object graphs are kept **only for the latest snapshot**; full rulebase evaluation ("Can A reach B?") at past `T` is `UNKNOWN` once a newer snapshot overwrites `policy_snapshot`. |
| `policy_rule_history` (V120, V145, V150) | `collected_at`, `changed_on` | **Append-only** rule diff rows (`added`/`removed`/`modified`, `changes` JSONB) | **Unbounded** (cascades only if `policy_snapshot` row deleted) | Trigger-populated from `policy_snapshot` | Exact history of **rule revisions** observed `<= T` (`collected_at <= T`). |
| `policy_revision` / `policy_change_evidence` (V163, V208) | `observed_at`, `change_time` | Append-only per `(job_id, container_ref)` / `evidence_id` | **Unbounded** | No | Exact container revision counters (`observed_at <= T`); V163 change-evidence readers are off by default except Panorama actor-link trial. |
| `device_policy_install` (V60) | `installed_at`, `observed_at` | **Overwrite-in-place** (`PRIMARY KEY (device_id)`) | Latest 1 row/gateway | No | **Overwrites earlier installs.** If `installed_at <= T` and `observed_at >= T`, that install was already on the gateway at `T` *only if* no other install happened in `(T, observed_at]` — exact if `observed_at <= T`, **`UNKNOWN` once a post-`T` install overwrites `installed_at > T`**. |
| `topology_snapshot` / `topology_conflict` (V186) | `built_at` | Bounded ring | **Latest `keep` snapshots** (default small ring; `DELETE ... OFFSET keep` on rebuild) | Overrides audited (`topology_link_override` keeps `created_at`, `revoked_at`) | Summary header & conflicts only for last `keep` builds; full topology graph at `T` would have to be rebuilt from `device_inventory_run` (`<= T`) + `topology_link_override` (`created_at <= T AND (revoked_at IS NULL OR revoked_at > T)`). |
| `zone_matrix_version` / `zone_matrix_revision` / `zone_matrix_reference_confirmation` (V216, V220) | `created_at`, `published_at`, `occurred_at`, `confirmed_at` | **Append-only** revisions (trigger-enforced append-only) | **Unbounded** | Yes (`tr_audit_zone_matrix_revision`) | **Exact** zone matrix definition & published version as of `T` (`published_at <= T`, newest `number`), though stored policy evaluation against it at `T` requires historical `policy_snapshot` (`UNKNOWN` if policy changed since `T`). |
| `cutover_segment` / `cutover_assessment` (V217) | `created_at`, `updated_at` | Assessments append-only until manually deleted; segments mutable | Manual deletion | Yes | Past stored assessments with `created_at <= T` are exact snapshots of that run. |
| `path_validation` (V188) / `static_route_preview` (V252) | `requested_at`, `completed_at` | Append-only per `validation_id` / `preview_id` | **Unbounded** | No / job audited | Point-in-time operational read records (`completed_at <= T`). |

### 2.8 System Logs, Health, Cases, Sanitizer, Support Bundles, Reports, and Audit

| Table (Migration) | Key Timestamp Column(s) | Storage Shape | Retention Limit | Audited (`audit_log`) | Notes for "As of `T`" |
|---|---|---|---|---|---|
| `system_log_chunk` / `system_log_file` (V160, V161) | `collected_at`, `log_day`, `expires_at`, `pruned_at` | Append-only metadata; encrypted files pruned (`pruned_at` set) | Raw chunks & `cp_sar_daily`: **`raw_retention_days` (default 30 d)** OR earlier under **2 GiB/device cap** (`RETENTION_SHORTENED_BY_CAP`); HCP: **7 d**; Spike data: **30 d / 512 MiB cap** | Yes (`trg_audit_system_log_*`) | Within unpruned window (`pruned_at IS NULL`), `cp_sar_daily` and `cp_cpwd_elg` exist on disk, **but** recomputing 7-day p95 CPU & cpwd restarts for an arbitrary `T` requires decrypting and parsing multi-MiB artefacts per device (high I/O cost). Beyond 30 d or cap, bytes are gone (`UNKNOWN`). |
| `system_log_run` / `system_log_gap` / `device_crash_alert` / `device_crash_entry` (V160) | `created_at`, `first_seen_at`, `removed_at` | Append-only (crash entry upserts `removed_at`) | **`ledger_retention_days` (default 180 d)** (`SystemLogRetentionPruner`) | Yes | Crash alerts and log-collection outcomes are exact within 180 days (`created_at <= T`). |
| `packet_analysis_case` / `capture` / `run` (V154, V158) | `created_at`, `expires_at`, `finished_at` | Hard-deleted at expiry (`packet_analysis_ledger` keeps `CASE_PRUNED` event) | **7 days** (`expires_at = created_at + 7 d`, `PacketAnalysisRetentionPruner`) | Yes | Cases older than 7 d are deleted; only `packet_analysis_ledger` remains. |
| `sanitize_job` / `sanitize_vault_entry` / `sanitize_ledger` (V156) | `created_at`, `vault_expires_at`, `vault_pruned_at` | Job & ledger rows stay; `sanitize_vault_entry` deleted at `vault_expires_at` | Output file temporary; vault entries pruned at `vault_expires_at`; job/ledger unbounded | Yes | Job counts stay; reversible vault entries expire. |
| `support_bundle_request` / `item` / `upload` (V230, V240) | `requested_at`, `finished_at`, `expires_at` | Request/item/upload rows stay (`state = 'EXPIRED'`); artefact bytes pruned 24 h after expiry warning | Bundle bytes: `expires_at` + warning; metadata rows: **unbounded** | Yes | Metadata history is exact; bundle download expires. |
| `report_run` (V190) | `requested_at`, `finished_at`, `expires_at` | `report_run` rows stay; report artefact pruned after `expires_at` | **30 days** (`ReportService.RETENTION = 30 d`) for artefact bytes; `report_run` rows unbounded | Yes (`trg_audit_report_run`) | `data_as_of_json` stays on `report_run`; file expires after 30 d. |
| `audit_log` (V1, V5, V7, V153) | `occurred_at` | **Append-only** trigger log (redacted per `audit_redaction_policy`) | **Unbounded** since V5 (V5 cleared pre-redaction rows once in 2026-09) | Self | Exact platform mutation audit trail, **but** lacks an `(occurred_at)` index today (see §5.2). |
| `authz_decisions` (V2) | `decided_at` | Append-only | **Unbounded** | Separate security ledger | Exact RBAC permit/deny history. |

---

## 3. Classification of Estate Lenses and Product Views "As of `T`"

### 3.1 Estate Wall Lenses (Opus-bold 8 Lenses)

| Lens | Answerability as of `T` | Exact Scope & Conditions | Where History Becomes Approximate or `UNKNOWN` |
|---|---|---|---|
| **1. Failover** (`failover_run`, `failover_check_result`) | **EXACT** (included in Slice 1) | Every cluster/VSX unit's newest `READINESS` run with `state IN ('DONE','STOPPED') AND finished_at <= T` gives the exact `READY` / `NOT_READY` / `UNKNOWN` outcome, `failed_check`, pre-check rows, and staleness at `T` (`T - finished_at > 24 h`), with unbounded retention. | `UNKNOWN` before a unit's first readiness run (`finished_at <= T` absent) or if the device was enrolled after `T` (`devices.created_at > T`). Approximate only if `devices.cluster_member_ref` changed after `T` without replaying `audit_log`. |
| **2. Backups** (`backup_artefact`, `artefact_retention_ledger`, `backup_deviation_record`) | **EXACT** (included in Slice 1) | `a.created_at <= T` plus `NOT EXISTS (removed ledger with occurred_at <= T)` proves whether a valid backup archive was on the recovery volume at `T`, its exact byte size and age `T - created_at`, whether it is still on disk today, and whether a `size_anomaly` was open at `T` (`detected_at <= T < coalesce(resolved_at, infinity)`). Works even when `T` is older than the 7-day file retention because manifests and ledger timestamps are never deleted by pruning. | `UNKNOWN` for devices enrolled after `T`. Approximate if `devices.backup_target` was toggled between `T` and `now()` (unless replayed from `audit_log`). Downloading the archive bytes from `T` is impossible once pruned (`still_stored_now = false`). |
| **3. Controls / Compliance** (`compliance_evaluation` + `device_configuration_run`) | **EXACT** for unchanged configs & `evaluated_at <= T`; **`UNKNOWN`** when overwritten (included in Slice 1) | Exact when `compliance_evaluation.evaluated_at <= T` and matches `cfg_T` (`device_configuration_run` `is_primary AND collected_at <= T`). Because `ComplianceWarmup` refreshes `evaluated_at` every 6 h (`MAX_AGE = 6 h`) on unchanged configs, a firewall whose `cfg_T.canonical_hash` equals `compliance_evaluation.canonical_hash` is also answered with `basis = MATCHING_CONFIG_HASH` and `evidence_at = cfg_T.collected_at` (pure function under unchanged `rules_fingerprint`). | **`UNKNOWN` (`EVALUATION_OVERWRITTEN`)** whenever a firewall's configuration changed after `T` (`cfg_T.canonical_hash <> compliance_evaluation.canonical_hash`), because V59 keeps only one row per `device_id`. Also `APPROXIMATE` if the compliance rule catalog (`rules_fingerprint`) changed between `T` and `evaluated_at`. Full historical exactness requires an append-only `compliance_evaluation_history` table (§5.2). |
| **4. Evidence age** (`device_inventory_run`, `device_configuration_run`) | **EXACT** (database-ready) | `max(collected_at) WHERE collected_at <= T` per `device_id` on `device_inventory_run` and `device_configuration_run` is exact for all time (both tables are append-only and unbounded). Age at `T` = `T - max(collected_at)`. | `UNKNOWN` (`never` as of `T`) when no run has `collected_at <= T`, or `ENROLLED_AFTER_MOMENT` when `devices.created_at > T`. |
| **5. Exposure** (`device_software_version_history` + `advisory` + `known_exploited_cve`) | **APPROXIMATE** (not exact) | Software version at `T` is known from `device_software_version_history` (`first_seen_at <= T <= last_seen_at`, post-V243 with hotfix). Combining that version with `advisory.published_on <= date(T)` and `known_exploited_cve.date_added <= date(T)` approximates "what was known on date `T`". | **Not exact** for three reasons: (a) `advisory_product_rule`, `known_exploited_cve`, and `checkpoint_jumbo_statement` are overwritten/replaced on ingest — vendor silent edits or parser rule fixes after `T` change historical verdicts; (b) inside an upgrade observation gap `(previous_last_seen_at, first_seen_at)` the exact switchover instant is unknown; (c) pre-V243 Check Point Jumbo hotfix stretches were not backfilled; (d) `device_license_item` and `device_live_patch_item` overwrite in place (`UNKNOWN` in the past). |
| **6. Policy installed** (`device_policy_install`, `policy_rule_history`, `asa_config_relation`) | **PARTIAL / APPROXIMATE** (`UNKNOWN` after any later install) | **Cisco ASA** (`SAVED`/`UNSAVED`) IS exact as of `T` for post-V253 backups (`backup_artefact.created_at <= T` joined to `asa_config_relation` and `device_configuration_run.collected_at <= T`). **FortiGate direct** HA checksum parity IS exact via `failover_check_result` (`observed_at <= T`). **Check Point & Palo Alto** are exact *only* when `device_policy_install.observed_at <= T` (or `installed_at <= T` and still current, with `policy_rule_history` `<= T`). | **Check Point & Palo Alto become `UNKNOWN`** the moment a gateway installs a policy *after* `T`, because `device_policy_install` (`V60`) has `PRIMARY KEY (device_id)` and overwrites `policy_name` and `installed_at`. Also `cp_policy_object_inventory` and `policy_snapshot.metadata->targets` hold only the latest management snapshot. |
| **7. Health** (`system_log_file`, `system_log_chunk`, `device_crash_alert`, `system_log_run`) | **APPROXIMATE / EXPENSIVE within 30 d; `UNKNOWN` beyond 30 d or cap** | `device_crash_alert` (`created_at <= T`) and `system_log_run` (`created_at <= T`) are exact in SQL for **180 days**. Check Point `cp_sar_daily` and `cp_cpwd_elg` artefacts exist for **up to 30 days** (unless evicted earlier by the 2 GiB/device cap). | Recomputing `SystemHealthService` for an arbitrary `T` requires decrypting and parsing 7 days of `sar` and `cpwd` artefacts per Check Point gateway on demand (tens of MiB, seconds of CPU/IO; live `SystemHealthService` only caches the *current* 7-day window in memory). Beyond 30 d (or cap eviction), artefacts are pruned (`UNKNOWN`). Palo Alto has no `sar`/`cpwd` health facts (`NOT_APPLICABLE` / `UNKNOWN`). |
| **8. Worst of all** (composite lens) | **BOUNDED BY COMPONENT LENSES** | Exact over the Slice 1 triad (`Failover`, `Backups`, `Controls` where evidenced); carries `UNKNOWN` wherever any constituent lens is `UNKNOWN` (`CONCEPT.md`: "`UNKNOWN` outranks `Fine`"). | Cannot fold `Exposure`, `Health`, or `Policy installed` into an exact historical composite until their overwrite/cost gaps (§5.2) are closed. |

### 3.2 Broader Product Views Under Rewind

| View / Screen | As-of `T` Classification | Evidence Basis and Boundary |
|---|---|---|
| **Brief (Shift deltas `A -> B`)** | **EXACT** for Failover, Backups, Inventory/Config reads, Jobs, Rule diffs, Crash alerts; **PARTIAL** for Compliance/Install | Comparing `as-of(A)` vs `as-of(B)` plus counting `jobs`, `policy_rule_history`, `device_configuration_run`, and `failover_run` transitions in `(A, B]` works directly from stored tables. |
| **Changes river & Policy rule revisions** | **EXACT** within retention | `ChangeFeedService` already accepts `[from, to)` over `policy_rule_history` (unbounded), `jobs` (unbounded), `device_crash_alert` (180 d), and `pan_config` / `cp_cpwd_elg` chunks (30 d / 2 GiB cap). `device_policy_install` only contributes the latest install per gateway. |
| **Dossier — Interfaces & Routes** | **EXACT** (unbounded) | `device_inventory_run` (`collected_at <= T` latest `run_id`) -> `device_interface`, `device_interface_address`, `device_route`, `device_inventory_ha` reconstructs the exact runtime network state at `T`. |
| **Dossier — Configuration diff** | **EXACT** metadata (unbounded); text within retention | `device_configuration_run` (`collected_at <= T`) + `device_configuration_index` / `override` / `configuration_run_deviation_entry` are unbounded; full configuration archive bytes in `backup_artefact` (`class='configuration'`) are kept for 30 days (plus latest). |
| **Ask — Can A reach B? / Deciding rule / Object usage / Cutover** | **`UNKNOWN` in the past** (live only today) | `policy_snapshot` and `cp_policy_object_inventory` overwrite in place per package/domain. While `device_inventory_run` could rebuild historical L3 routing topology at `T`, Hop policy/NAT matching cannot reconstruct a historical rulebase + object dictionary once `policy_snapshot` is overwritten. |
| **Ask — Zone Matrix definition** | **EXACT** definition; **`UNKNOWN`** historical policy evaluation | `zone_matrix_revision` is append-only (`occurred_at <= T`), so the matrix version active at `T` is exact; evaluating stored firewall policies against it as of `T` is `UNKNOWN` once `policy_snapshot` moves forward. |
| **Cases / Sanitizer / Support bundles / Reports** | **POINT-IN-TIME RECORDS** (not estate state) | Cases expire entirely at **7 days**; generated report files expire at **30 days**; support bundle files expire after `expires_at` + 24 h warning (`report_run` and `support_bundle_request` metadata rows remain). |

---

## 4. Retention Limits Summary Per Table

| Horizon | Tables / Artefacts | Enforcing Mechanism |
|---|---|---|
| **24 hours** (latest `FINISHED` per manager kept) | `discovery_run`, `discovery_candidate` (`ON DELETE CASCADE`) | `JooqDiscoveryRunRepository.sweepExpired` (`MAX_RUN_AGE_HOURS = 24`) |
| **Latest 1 row per key (overwrite)** | `devices`, `compliance_evaluation`, `device_platform_facts`, `device_policy_install`, `device_identity_check`, `ha_pair_identity`, `device_lifecycle_read`, `device_license_item`, `device_live_patch_item`, `policy_snapshot`, `cp_policy_object_inventory`, `advisory`, `known_exploited_cve`, `backup_baseline` | `INSERT ... ON CONFLICT DO UPDATE` or `DELETE` + `INSERT` on collection |
| **Latest 2 rows per cluster** | `cluster_member_diff` | `ClusterDiffTask.refresh` (`LIMIT 2` per `cluster_ref`) |
| **Latest `keep` builds** | `topology_snapshot`, `topology_conflict` (`ON DELETE CASCADE`) | `JooqTopologyRepository.recordSnapshot` (`OFFSET keep`) |
| **7 days (bytes)**; manifest rows unbounded | Standard daily backups in `backup_artefact` (`class='backup'`, `backup_type='standard'`) — sole backup, baseline, and open size-anomaly last-good + newest-bad pinned | `RetentionPruningService` hourly (`backup_policy.backup_retention_days`, live = 7 d per PO 2026-10-12); writes `removed` to `artefact_retention_ledger` |
| **7 days (hard delete)** | `packet_analysis_case`, `packet_analysis_capture`, `packet_analysis_run`, `packet_analysis_host_telemetry`, HCP files (`cp_hcp_report`) | `PacketAnalysisRetentionPruner` (`expires_at`); `SystemLogRetentionPruner` (`hcp_retention_days = 7`) |
| **Depth 2 per device (bytes)** | Weekly backup snapshots (`backup_type='snapshot'`) | `RetentionPruningService` (`snapshot_retention_depth = 2`) |
| **30 days (bytes)** | Configuration archives (`class='configuration'`, latest kept), generated reports (`class='report'`), system-log chunks & `cp_sar_daily` (`raw_retention_days = 30` + **2 GiB/device cap**), `cp_spike_data` (`30 d` + **512 MiB/device cap**) | `RetentionPruningService`; `SystemLogRetentionPruner` |
| **180 days (rows)** | `system_log_run`, `system_log_gap`, `device_crash_alert`, removed `device_crash_entry` | `SystemLogRetentionPruner` (`ledger_retention_days = 180`) |
| **Unbounded (until device delete)** | `jobs`, `job_steps`, `job_step_attempt`, `failover_run`, `failover_check_result`, `failover_approval`, `failover_quarantine`, `backup_artefact` (manifest rows), `artefact_retention_ledger`, `backup_deviation_record`, `device_inventory_run` (+ child interface/route/HA tables), `device_configuration_run` (+ index/override/deviation tables), `asa_config_relation`, `device_software_version_history`, `device_install_event`, `policy_rule_history`, `policy_revision`, `zone_matrix_revision`, `audit_log`, `authz_decisions` | No automatic row pruner. Growth watch: `device_route` / `device_interface` (~4 runs/day x 136 devices) and `failover_check_result` (~182 k rows as of 2026-11-14) are the fastest-growing relational tables. |

---

## 5. Query Cost and Index Analysis

### 5.1 Slice 1 (`GET /api/v2/estate/as-of?at=<ISO>`) — Existing Indexes Suffice (Zero Migration)

At current estate scale (~136 enrolled devices, ~40 clusters, ~9 k `failover_run` rows, ~50 k `jobs` rows, ~2 k `backup_artefact` rows), every query in Slice 1 executes inside a single `READ ONLY` transaction (`statement_timeout = 15000 ms`) using **already-shipped indexes**:

1. **Device summary + enrollment timestamps** (`devices` + `JooqDeviceRepository.listAll()`):
   - Uses `devices_pkey` (136 rows) and V218 lookup indexes (`idx_discovery_candidate_match_key_domain`, `idx_discovery_candidate_match_key`, `idx_discovery_candidate_display_name_c`, `idx_jobs_target_device_submitted`). Cost: ~15–25 ms.
2. **Failover readiness as of `at`**:
   ```sql
   SELECT DISTINCT ON (vendor, cluster_ref, coalesce(vs_id, ''))
       run_id, vendor, cluster_ref, vs_id, state, outcome, finished_at, failed_check, message
   FROM failover_run
   WHERE run_kind = 'READINESS' AND state IN ('DONE', 'STOPPED') AND finished_at <= :at
   ORDER BY vendor, cluster_ref, coalesce(vs_id, ''), finished_at DESC, run_id DESC;
   ```
   - Covered directly by **V218** partial composite index `idx_failover_run_readiness_finished ON failover_run (vendor, cluster_ref, (coalesce(vs_id, '')), finished_at DESC) WHERE run_kind = 'READINESS' AND state IN ('DONE', 'STOPPED')`. Cost: < 5 ms.
3. **Latest live backup at `at` + current storage check**:
   ```sql
   SELECT DISTINCT ON (a.device_id)
       a.device_id, a.artefact_id, a.created_at, a.plaintext_bytes,
       coalesce(a.backup_type, 'standard') AS backup_type, a.deviation_state,
       NOT EXISTS (SELECT 1 FROM artefact_retention_ledger l_now
                   WHERE l_now.artefact_id = a.artefact_id AND l_now.event = 'removed') AS still_stored_now
   FROM backup_artefact a
   WHERE a.artefact_class = 'backup' AND a.created_at <= :at
     AND NOT EXISTS (SELECT 1 FROM artefact_retention_ledger l
                     WHERE l.artefact_id = a.artefact_id AND l.event = 'removed' AND l.occurred_at <= :at)
   ORDER BY a.device_id, a.created_at DESC, a.artefact_id DESC;
   ```
   - Covered by **V17** `idx_backup_artefact_device_id (device_id, created_at)` and `idx_artefact_retention_ledger_artefact_id (artefact_id)`. Cost: < 5 ms.
4. **Open backup size anomalies at `at`**:
   - Covered by **V30** `idx_backup_deviation_device_id (device_id, detected_at)`. Cost: < 2 ms.
5. **Primary configuration run at `at` + stored compliance evaluations**:
   - `device_configuration_run` (`WHERE is_primary AND collected_at <= :at ORDER BY device_id, collected_at DESC`) is covered by **V52** partial index `idx_device_configuration_run_primary (device_id, collected_at DESC) WHERE is_primary`.
   - `compliance_evaluation` is a full scan of `<= 136` PK rows. Cost: < 5 ms.

**Total Slice 1 DB cost:** ~25–40 ms per uncached request; **no V260 index migration is needed for Slice 1**.

### 5.2 Needed Indexes and Schema Additions for Slice 2+ (Future Frozen Contract)

When the Product Owner freezes full Rewind (`Moment A -> Moment B` transitions, timeline scrubber density histogram, and exact historical Compliance / Policy-install / Health lenses), the following schema additions are recommended:

1. **Append-only `compliance_evaluation_history` table (High value):**
   - Today V59 `compliance_evaluation` overwrites on `(device_id)`, turning pre-change evaluations `UNKNOWN` once a firewall's config changes.
   - Add `compliance_evaluation_history (history_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, device_id TEXT NOT NULL, canonical_hash TEXT NOT NULL, rules_fingerprint TEXT NOT NULL, first_evaluated_at TIMESTAMPTZ NOT NULL, last_evaluated_at TIMESTAMPTZ NOT NULL, pass_count INT NOT NULL, fail_count INT NOT NULL, no_evidence_count INT NOT NULL, critical_fail_count INT NOT NULL, UNIQUE (device_id, canonical_hash, rules_fingerprint))` with index `(device_id, first_evaluated_at DESC)`. Stretch-compressing identical `(device_id, canonical_hash, rules_fingerprint)` evaluations adds ~136 rows baseline + ~1 row per actual config/catalog change (~few thousand rows/year, < 1 MiB) and makes compliance pass/fail counts **100% exact** across config and rule-catalog changes without storing duplicate JSONB blobs.
2. **Stretch-compressed `device_policy_install_history` table (Medium value):**
   - Mirror V243 `device_software_version_history` for `(device_id, policy_name, installed_at)` with `first_observed_at` / `last_observed_at` and index `(device_id, first_observed_at DESC)` so post-`T` policy installs stop erasing pre-`T` gateway install states.
3. **Pre-aggregated daily health summary row `device_health_daily` (Medium value):**
   - Store parsed `SarDailyText` (`cpu_p95`, `mem_p95`, `samples`) and daily `cpwd` restart counts at collection time keyed by `(device_id, log_day)`, retained 180 days alongside `system_log_run`. Makes the Health lens O(1) SQL as of any day `T` without decrypting multi-MiB `sar` files on HTTP threads.
4. **Time indexes for `audit_log` and timeline scrubber:**
   - `audit_log` currently has `(table_name, row_pk)`, `(correlation_run_id)`, and `(actor_fingerprint)` — **no index on `occurred_at`**. Even `AuditLogQueryService.recent()` (`ORDER BY occurred_at DESC LIMIT 100`) seq-scans `audit_log`. Add:
     - `CREATE INDEX idx_audit_log_occurred_at ON audit_log (occurred_at DESC);`
     - `CREATE INDEX idx_audit_log_table_occurred_at ON audit_log (table_name, occurred_at DESC) WHERE table_name = 'devices';` (enables < 2 ms exact replay of `enrollment_state`, `disabled`, `backup_target`, and `cluster_member_ref` as of `T`).
   - If `artefact_retention_ledger` exceeds 100 k rows, add partial index `idx_artefact_retention_ledger_removed ON artefact_retention_ledger (artefact_id, occurred_at) WHERE event = 'removed'`.

---

## 6. Slice 1 Read-Only Endpoint (`GET /api/v2/estate/as-of?at=<ISO>`)

### 6.1 Route, RBAC, and Caching

- **Route:** `GET /api/v2/estate/as-of?at=<ISO-8601>`
- **Action gate:** `ActionRegistry.DEVICE_READ` (open to any authenticated session, including `aiview` / `role:replay_viewer` and read-only machine sessions; zero mutations).
- **Headers:** `Cache-Control: no-cache, private`, `Vary: Cookie`, and `X-Nexus-Masked: true` (via `PrivacyMaskingResponseBodyAdvice` on `EstateAsOfResponse`) when `aiview` is active.
- **Input handling:**
  - Missing/blank/non-ISO-8601 `at` (or timestamp without zone/offset) -> `400 Bad Request` (`code: "TIME_INVALID"`).
  - `at > generated_at` -> `200 OK` with warning `AT_IN_FUTURE` (warn, do not refuse).

### 6.2 Per-Unit Lenses and `UNKNOWN` Rules

Each active enrolled unit (`devices.enrollment_state = 'ENROLLED' AND NOT disabled`) returns:
- Identity projection: `device_id`, `hostname` (masked to `FW-...` / `UNKNOWN-HOST-...` for `aiview`), `vendor`, `role`, `model`, `cluster_id` (opaque UUID), `cluster_member_ref` (masked to `CLS-...` for `aiview`), and `enrolled_at_moment` (`devices.created_at <= at`).
- When `enrolled_at_moment = false`, all three lenses return `state: "UNKNOWN"`, `reason: "ENROLLED_AFTER_MOMENT"`, `evidence_at: null` so post-`at` enrollments never fabricate historical failures.
- **`failover`**: `outcome` (`READY` | `NOT_READY` | `UNKNOWN` | `NOT_APPLICABLE`), `verdict` (`READY` | `STALE` | `NOT_READY` | `UNKNOWN` | `NOT_APPLICABLE`, with 24 h staleness relative to `at`), `stale`, `failed_check`, `reason`, sub-unit breakdown (`ready_sub_units`, `stale_sub_units`, `not_ready_sub_units`, `unknown_sub_units` for Check Point VSX clusters), `evidence_at` (`finished_at <= at`), `age_seconds` (`at - evidence_at`).
- **`backup`**: `state` (`PRESENT` | `MISSING` | `UNKNOWN` | `NOT_APPLICABLE`), `verdict` (`OK` | `STALE` | `ANOMALY` | `MISSING` | `UNKNOWN` | `NOT_APPLICABLE`), `backup_target`, `present`, `stale` (`> 24 h` at `at`), `older_than_7d` (`> 7 d` at `at`), `open_size_anomaly` (`detected_at <= at` and unresolved at `at`), `still_stored_now`, `plaintext_bytes`, `backup_type`, `reason`, `evidence_at` (`created_at <= at`), `age_seconds`.
- **`compliance`**: `state` (`EVIDENCED` | `UNKNOWN` | `NOT_APPLICABLE`), `verdict` (`PASS` | `FAIL` | `NO_EVIDENCE` | `UNKNOWN` | `NOT_APPLICABLE`), `basis` (`EVALUATED_AT_OR_BEFORE` | `MATCHING_CONFIG_HASH` | `null`), `pass_count`, `fail_count`, `no_evidence_count`, `critical_fail_count`, `evaluated_at`, `reason` (`NOT_EVALUATED` | `NO_EVIDENCE_BEFORE_AT` | `EVALUATION_OVERWRITTEN` | `EVALUATION_NOT_MATCHING_CONFIG_AT_MOMENT` | `NOT_COMPLIANCE_TARGET` | `ENROLLED_AFTER_MOMENT`), `evidence_at` (`cfg_at.collected_at` or `evaluated_at`), `age_seconds`.

---

## 7. Open Product Owner Decisions Before Freezing Full Rewind

1. **Compliance history persistence:** Approve `compliance_evaluation_history` (§5.2 item 1) so post-`T` configuration edits and compliance catalog updates never turn earlier compliance snapshots into `EVALUATION_OVERWRITTEN`?
2. **Staleness threshold per lens on the Rewind wall:** Keep 24 h (`Freshness.STALE_AFTER`) for Failover and Backup report parity while surfacing `older_than_7d` for the 7-day attention rule, or unify on per-lens thresholds?
3. **Deleted devices in historical Estate views:** Should a device deleted after `T` be reconstructed from `audit_log` (`table_name = 'devices'`) as a ghost tile at `T`, or should Rewind scope strictly to currently enrolled units plus an explicit deleted-units counter?
4. **Inventory / configuration retention horizon:** Both `device_inventory_run` (with routes/interfaces) and `device_configuration_run` are currently unbounded in PostgreSQL; choose an explicit retention horizon (e.g. 90 or 180 days for child interface/route rows, keeping run headers longer) before multi-month growth impacts table size.
