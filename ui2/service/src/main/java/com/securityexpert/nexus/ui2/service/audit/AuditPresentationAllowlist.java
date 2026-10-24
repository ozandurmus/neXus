package com.securityexpert.nexus.ui2.service.audit;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The committed presentation classification resource
 * {@code UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md} §3.3 requires:
 * "one entry per audited table listing the columns that are auditable in
 * full." Together with {@code audit_redaction_policy} (read at request time
 * by {@link AuditRedactionPolicy}), this classifies every column of every
 * audited table exactly once. A column in neither is
 * {@link AuditFieldState#UNCLASSIFIED}.
 *
 * <p>This is a declarative, hand-maintained register — deliberately not
 * derived from the live schema — so that adding a column to an audited
 * table forces an explicit decision in the same change
 * ({@code AuditPresentationAllowlistMatchesLiveSchemaTest} proves it stays
 * equal to the live column set minus the policy's columns; contract §8 test
 * 13, AC-4).</p>
 *
 * <p>No DDL and no new grant exists for this resource (contract §3.3 item
 * 1) — it is application-side classification only.</p>
 */
public final class AuditPresentationAllowlist {

    /**
     * {@code table_name -> columns auditable in full}, for every table
     * carrying a {@code trg_audit_*} trigger. Every other column of those
     * tables is in {@code audit_redaction_policy} (V5, V17, V91, V97, V144,
     * V153). Identifiers, enums, timestamps, counters, booleans, content
     * digests, key ids and actor fingerprints are audited in full; operational
     * identity, locations, device-derived content and free text are redacted.
     */
    private static final Map<String, Set<String>> AUDITED_IN_FULL = Map.ofEntries(
            Map.entry("artefact_retention_ledger", Set.of(
                    "artefact_id", "event", "ledger_id", "occurred_at", "retention_tier")),
            Map.entry("backup_artefact", Set.of(
                    "artefact_class", "artefact_id", "backup_type", "ciphertext_bytes",
                    "ciphertext_sha256", "created_at", "deviation_state", "device_id", "expires_at",
                    "key_id", "plaintext_bytes", "plaintext_sha256", "retention_tier",
                    "software_version", "validation", "vendor")),
            Map.entry("backup_artefact_deletion_request", Set.of(
                    "artefact_id", "completed_at", "outcome", "requested_at",
                    "requested_by_actor_fingerprint")),
            Map.entry("backup_artefact_retrieval", Set.of(
                    "actor_fingerprint", "artefact_id", "retrieval_id", "retrieved_at")),
            Map.entry("backup_baseline", Set.of(
                    "artefact_id", "device_id", "set_at")),
            Map.entry("backup_endpoint_ineligibility", Set.of(
                    "device_id", "marked_at")),
            Map.entry("backup_policy", Set.of(
                    "backup_retention_days", "configuration_retention_days", "created_at", "daily_backup_cron",
                    "last_scheduled_run_at", "major_alert_enabled", "policy_id", "schedule_enabled",
                    "snapshot_retention_depth", "updated_at", "weekly_snapshot_cron")),
            Map.entry("configuration_artefact", Set.of(
                    "artefact_ref", "ciphertext_bytes", "ciphertext_sha256", "compression",
                    "created_at", "device_id", "job_id", "key_id", "plaintext_bytes",
                    "plaintext_sha256", "vendor")),
            Map.entry("configuration_notification", Set.of(
                    "created_at", "device_id", "kind", "notification_id", "read_at", "run_id")),
            Map.entry("configuration_run_deviation_entry", Set.of(
                    "entry_id", "kind", "new_count", "old_count", "section", "source", "summary_id")),
            Map.entry("configuration_run_deviation_summary", Set.of(
                    "created_at", "run_id", "status", "summary_id")),
            Map.entry("cp_inventory_projection", Set.of(
                    "collected_at", "device_id", "endpoint_id", "ha_state", "job_id",
                    "product_version", "projection_id", "provenance_id")),
            Map.entry("credential_references", Set.of(
                    "created_at", "credential_reference_id", "purpose")),
            Map.entry("credentials", Set.of(
                    "allows_check_point", "allows_palo_alto", "created_at",
                    "created_by_actor_fingerprint", "credential_id", "envelope_key_id", "kind",
                    "secret_set_at", "snmp_auth_protocol", "snmp_priv_protocol",
                    "snmp_security_level")),
            Map.entry("device_configuration_index", Set.of(
                    "entry_count", "index_id", "run_id", "section", "source")),
            Map.entry("device_configuration_override", Set.of(
                    "category", "override_id", "run_id")),
            Map.entry("device_configuration_run", Set.of(
                    "canonical_hash", "change_state", "collected_at", "created_at", "device_id",
                    "is_primary", "job_id", "raw_bytes", "raw_hash", "read_kind", "run_id",
                    "vendor", "withheld_line_count")),
            Map.entry("device_interface", Set.of(
                    "interface_id", "kind", "run_id", "state", "vlan_id")),
            Map.entry("device_interface_address", Set.of(
                    "address_id", "family", "interface_id", "role")),
            Map.entry("device_inventory_ha", Set.of(
                    "cluster_mode", "ha_id", "role", "run_id", "source")),
            Map.entry("device_inventory_run", Set.of(
                    "collected_at", "context_count", "created_at", "device_id", "job_id", "not_collected", "run_id")),
            Map.entry("device_onboarding", Set.of(
                    "completed_at", "device_id", "skipped", "source", "started_at", "state", "step",
                    "step_job_id", "updated_at")),
            Map.entry("device_route", Set.of(
                    "protocol", "route_id", "run_id")),
            Map.entry("device_secret_reference", Set.of(
                    "credential_reference_id", "device_id", "purpose", "set_at",
                    "set_by_actor_fingerprint")),
            Map.entry("devices", Set.of(
                    "backup_target", "created_at", "credential_reference_id", "device_id",
                    "disabled", "enrollment_state", "https_certificate_strict",
                    "identity_mismatch_detected_at", "identity_mismatch_state", "is_test_target",
                    "observed_ha_role", "observed_model", "observed_software_version",
                    "peer_follow_outcome", "recorded_identity_recorded_at", "registration_source",
                    "role", "ssh_identity_unverified", "vendor_hint")),
            Map.entry("discovery_acknowledgement", Set.of(
                    "acknowledged_at", "acknowledged_by_actor_fingerprint", "discovery_match_key")),
            Map.entry("endpoints", Set.of(
                    "created_at", "device_id", "endpoint_id", "transport_kind")),
            Map.entry("failover_approval", Set.of(
                    "approval_id", "approved_at", "approved_by", "cluster_ref", "execution_nonce",
                    "initiated_by", "member_set_revision", "operation", "policy", "policy_version",
                    "request_revision", "revoked_at", "revoked_by", "target_member_ids", "unit_ref",
                    "vendor", "vs_id", "warning_confirmed_at", "window_from", "window_until")),
            Map.entry("failover_check_result", Set.of(
                    "check_no", "derived", "member_ref", "observed_at", "phase", "result_id",
                    "run_id", "status", "vs_id")),
            Map.entry("failover_quarantine", Set.of(
                    "acknowledged_at", "acknowledged_by", "active", "cluster_ref", "execution_id",
                    "quarantined_at", "quarantined_member_ids", "reason", "second_approver_id")),
            Map.entry("failover_resume", Set.of(
                    "approval_ack_at", "approval_ack_by", "approval_nonce", "approved_at", "approved_by",
                    "approved_in_business_hours", "business_hours_ack_at", "business_hours_ack_by", "cluster_ref",
                    "expected_incident_id", "expires_at", "failed_check", "finished_at", "former_active_ref",
                    "initiated_by", "job_id", "member_set_revision", "mutation_possible", "outcome", "peer_ref",
                    "policy", "requested_at", "requested_in_business_hours", "resume_id", "run_id", "started_at",
                    "state", "step", "target_member_ids", "vendor")),
            Map.entry("failover_run", Set.of(
                    "approval_id", "cluster_ref", "failed_check", "finished_at", "job_id",
                    "mutation_possible", "outcome", "request_revision", "requested_by", "run_id",
                    "run_kind", "scheduled_for", "started_at", "state", "step", "target_member_ids",
                    "vendor", "vs_id")),
            // pan_config_sync (V178): opaque references, codes, booleans and timestamps only; no free text.
            Map.entry("pan_config_sync", Set.of(
                    "business_hours_ack_at", "cluster_ref", "device_status", "finished_at",
                    "followup_readiness_run_id", "followup_refusal", "initiated_by", "initiator_role", "job_id",
                    "member_set_revision", "mutation_possible", "outcome", "readiness_run_id", "requested_at",
                    "requested_in_business_hours", "source_member_ref", "started_at", "state", "sync_id",
                    "target_member_ref", "vendor")),
            Map.entry("gate_registry", Set.of(
                    "action_class", "canonical_command_key", "created_at", "gate_id",
                    "max_frequency", "platform_role_scope", "retry_rule", "safe_telemetry_fields",
                    "secret_output_risk", "session_reuse_rule", "shell_context", "sign_off_state",
                    "source_document_pointer", "timeout_s", "transport_kind",
                    "unsupported_behavior_ref", "vendor")),
            Map.entry("https_endpoint_cert_trust", Set.of(
                    "authorized_at", "first_seen_at", "management_port", "not_after", "status",
                    "trust_entry_id")),
            Map.entry("job_reconciliation", Set.of(
                    "job_id", "reconciled_outcome", "recorded_at", "recorded_by")),
            Map.entry("job_step_attempt", Set.of(
                    "action_class", "attempt_id", "attempt_number", "created_at", "error_class",
                    "fingerprint_sha256", "job_id", "lease_epoch", "matched_expectation",
                    "mutation_boundary_crossed", "outcome", "output_bytes", "output_lines",
                    "sent_at", "step_index", "step_kind")),
            Map.entry("job_steps", Set.of(
                    "job_id", "job_step_id", "step_index")),
            Map.entry("jobs", Set.of(
                    "action_class", "admission_deferrals", "admission_not_before",
                    "business_hours_ack_at", "business_hours_ack_by",
                    "cancel_requested", "capability_id", "diagnostic_exit_status",
                    "diagnostic_gate_id", "diagnostic_gate_revision", "diagnostic_line_count",
                    "diagnostic_port", "diagnostic_shape_id", "diagnostic_status_token",
                    "diagnostic_viewed_at", "diagnostic_viewed_by", "finished_at",
                    "idempotency_key", "job_id", "job_type", "last_heartbeat_at", "lease_epoch",
                    "lease_expires_at", "lease_owner_generation", "lease_worker_id", "outcome",
                    "precheck_results", "reconciliation_ref", "ssh_identity_unverified", "state",
                    "submitted_at", "submitted_by_actor_fingerprint", "submitted_in_business_hours",
                    "target_device_id", "target_kind", "target_ref", "terminal_reason",
                    "window_bypass_role")),
            Map.entry("lifecycle_catalog", Set.of(
                    "catalog_id", "end_of_engineering", "end_of_sale", "end_of_support",
                    "imported_at", "kind", "product", "source", "vendor")),
            Map.entry("local_credentials", Set.of(
                    "algorithm_id", "created_at", "created_by_actor_fingerprint", "enabled",
                    "failed_attempt_count", "local_identity_id", "locked_until", "memory_cost_kib",
                    "must_change_password", "parallelism", "password_set_at", "time_cost",
                    "updated_at")),
            Map.entry("management_endpoint_pan_cert_trust", Set.of(
                    "authorized_at", "management_port", "observed_at", "status", "superseded_by",
                    "trust_entry_id", "vendor")),
            Map.entry("management_endpoint_ssh_trust", Set.of(
                    "authorized_at", "key_algorithm", "management_port", "observed_at", "status",
                    "superseded_by", "trust_entry_id", "vendor")),
            Map.entry("notification_routes", Set.of(
                    "enabled", "last_sent_at", "type", "updated_at", "updated_by",
                    "watermark_audit", "watermark_time")),
            Map.entry("notification_settings", Set.of(
                    "audit_watermark", "forward_audit_to_syslog", "job_failure_watermark",
                    "notify_job_failure", "settings_id", "smtp_enabled", "smtp_port",
                    "smtp_starttls", "syslog_enabled", "syslog_facility", "syslog_port",
                    "syslog_protocol", "updated_at", "updated_by")),
            // V154: title, display_name, sha256 and storage_key are tier-2 redacted.
            Map.entry("packet_analysis_capture", Set.of(
                    "capture_format", "capture_id", "case_id", "size_bytes", "uploaded_at",
                    "uploaded_by")),
            Map.entry("packet_analysis_case", Set.of(
                    "case_id", "created_at", "created_by", "expires_at", "payload_zeroing", "state",
                    "total_bytes")),
            // V158: display_name, sha256, storage_key and anchor_local are tier-2 redacted.
            Map.entry("packet_analysis_host_telemetry", Set.of(
                    "aligned_at", "aligned_by", "case_id", "detected_format", "offset_seconds", "recognition_note",
                    "size_bytes", "telemetry_id", "time_zone", "uploaded_at", "uploaded_by")),
            Map.entry("packet_analysis_ledger", Set.of(
                    "actor_fingerprint", "bytes", "capture_id", "case_id", "event", "job_id",
                    "ledger_id", "occurred_at")),
            Map.entry("packet_analysis_run", Set.of(
                    "case_id", "engine_version", "failure_reason", "finished_at", "job_id",
                    "masked_summary", "requested_at", "requested_by", "started_at", "state")),
            Map.entry("panorama_assignment", Set.of(
                    "assignment_id", "computed_at")),
            Map.entry("policy_offline_analysis", Set.of(
                    "analysis_id", "created_at", "policy_id", "uploader")),
            Map.entry("provenance_records", Set.of(
                    "capability_version", "capture_artifact_id", "collected_at",
                    "fingerprint_sha256", "parser_version", "provenance_id", "run_id",
                    "sanitized_fragment", "source_location", "step_id")),
            Map.entry("rbac_permissions", Set.of(
                    "action", "id")),
            Map.entry("rbac_role_permissions", Set.of(
                    "permission_id", "role_id")),
            Map.entry("rbac_roles", Set.of(
                    "id", "is_system", "name", "token_string")),
            Map.entry("role_bindings", Set.of(
                    "binding_id", "binding_kind", "created_at", "created_by_actor_fingerprint",
                    "directory_profile_id", "group_reference_key_id", "revoked_at",
                    "revoked_by_actor_fingerprint", "role_token")),
            // V156: wrapped_data_key, lookup_key and sealed_original are tier-1 redacted.
            Map.entry("sanitize_job", Set.of(
                    "counts", "created_at", "created_by", "failure_reason", "file_type", "finished_at",
                    "input_bytes", "job_id", "key_id", "output_bytes", "output_state", "state",
                    "vault_entries", "vault_expires_at", "vault_pruned_at")),
            Map.entry("sanitize_ledger", Set.of(
                    "actor_fingerprint", "entries", "event", "job_id", "ledger_id", "occurred_at",
                    "revert_id")),
            Map.entry("sanitize_revert_job", Set.of(
                    "counts", "created_at", "created_by", "failure_reason", "file_type", "finished_at",
                    "input_bytes", "output_bytes", "output_state", "revert_id", "scope", "source_job_id",
                    "state")),
            Map.entry("sanitize_vault_entry", Set.of(
                    "entry_id", "entry_kind", "job_id")),
            Map.entry("secrets_metadata", Set.of(
                    "backend_kind", "component", "created_at", "purpose", "rotated_at",
                    "rotation_policy_ref", "secret_id")),
            Map.entry("sessions", Set.of(
                    "absolute_expires_at", "actor_fingerprint", "created_at", "end_reason",
                    "ended_by_actor_fingerprint", "idle_deadline_at", "last_seen_at", "machine",
                    "session_id", "state", "superseded_by_session_id")),
            Map.entry("ssh_host_key_observation", Set.of(
                    "connection_allowed", "device_id", "endpoint_id", "first_seen", "key_algorithm",
                    "last_job_id", "last_seen", "management_port", "observation_id", "seen_count",
                    "status", "strict_refusal")),
            Map.entry("ssh_trust_settings", Set.of(
                    "mode", "settings_id")),
            // V160 (system-log phase B): identifiers, enums, counters, timestamps and content digests. Name and
            // log-byte digests, wrapped keys, artefact locations and device-derived detail are in audit_redaction_policy.
            Map.entry("device_crash_alert", Set.of(
                    "alert_id", "created_at", "device_id", "file_count", "job_id", "newest_mtime", "notified_at")),
            Map.entry("device_crash_entry", Set.of(
                    "baseline", "device_id", "directory", "entry_id", "first_seen_at", "first_seen_job",
                    "last_seen_job", "mtime", "removed_at", "size")),
            Map.entry("system_log_chunk", Set.of(
                    "bytes", "chunk_id", "ciphertext_bytes", "collected_at", "device_id", "end_offset",
                    "first_line_time", "flags", "inode", "job_id", "last_line_time", "line_time_basis", "lines",
                    "log_day", "prune_reason", "pruned_at", "redaction_count",
                    "redaction_rule_set", "source_key", "start_offset")),
            Map.entry("system_log_cursor", Set.of(
                    "cursor_key", "device_epoch_at_read", "device_id", "fast_rotating", "inode", "offset_bytes",
                    "pan_boundary_seqnos", "pan_last_receive_time", "retention_horizon_seconds", "source_key",
                    "updated_at", "updated_job_id")),
            Map.entry("system_log_file", Set.of(
                    "ciphertext_bytes", "collected_at", "device_id", "expires_at", "file_id", "flags", "job_id",
                    "kind", "mtime", "name_time", "pruned_at", "size", "status")),
            Map.entry("system_log_gap", Set.of(
                    "created_at", "device_id", "extent_unknown", "gap_id", "job_id", "kind", "known_lost_bytes",
                    "pending_bytes", "source_key", "window_end_epoch", "window_start_epoch")),
            Map.entry("system_log_run", Set.of(
                    "bytes_read", "bytes_stored", "created_at", "device_id", "duration_ms", "job_id", "outcome",
                    "reads_issued", "run_id", "source_key")),
            Map.entry("system_log_settings", Set.of(
                    "device_cap_bytes", "hcp_retention_days", "include_management_servers", "ledger_retention_days",
                    "raw_retention_days", "settings_id", "spike_data_cap_bytes", "spike_data_enabled",
                    "spike_data_retention_days", "updated_at", "updated_by")),
            // V151: summaries are counts, field names, formats and enum-like values only -- nothing to redact.
            Map.entry("system_log_measurement", Set.of(
                    "device_id", "job_id", "measured_at", "measurement_id", "source", "summary",
                    "vendor")));

    private AuditPresentationAllowlist() {
    }

    /** Whether {@code table.column} is auditable in full by this register. */
    public static boolean isPresentInFull(String tableName, String columnName) {
        Set<String> columns = AUDITED_IN_FULL.get(tableName);
        return columns != null && columns.contains(columnName);
    }

    /** The full-render column set of one table, in a stable (sorted) order. */
    public static Set<String> columnsOf(String tableName) {
        Set<String> columns = AUDITED_IN_FULL.get(tableName);
        return columns == null ? Set.of() : new TreeSet<>(columns);
    }

    public static Set<String> auditedTables() {
        return new TreeSet<>(AUDITED_IN_FULL.keySet());
    }
}
