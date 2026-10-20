package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;

/**
 * {@code UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md} §6 — proves that
 * {@code V5}'s redaction actually removes declared values from
 * {@code audit_log}, that the digest form preserves the audit properties §2
 * claims for it, and that an unclassified column is a build failure rather
 * than a silent default.
 *
 * <p>The defect this guards against was measured, not theorised: before
 * {@code V5}, inserting one {@code sessions} row placed that row's
 * {@code csrf_secret} value verbatim into {@code audit_log.after_state},
 * where {@code ui2_app} read it with a plain {@code SELECT}.</p>
 *
 * <p>No test in this class writes a real secret. Every value is a synthetic
 * token, and no assertion message echoes a value — only the relationship
 * (contract §2; {@code AGENTS.md} sensitive identity reporting law).</p>
 */
class AuditRedactionPolicyTest {

    /**
     * Columns of audited tables that are persisted in full, by decision.
     * Together with {@code audit_redaction_policy} this classifies every
     * column of every audited table exactly once (contract §4). A column in
     * neither set fails {@link #everyAuditedColumnIsClassifiedExactlyOnce()}.
     *
     * <p>This register is deliberately explicit rather than derived: adding a
     * column to an audited table must force a decision in the same change.</p>
     */
    private static final Set<String> AUDITED_IN_FULL = Set.of(
            // artefact_retention_ledger
            "artefact_retention_ledger.artefact_id", "artefact_retention_ledger.event",
            "artefact_retention_ledger.ledger_id", "artefact_retention_ledger.occurred_at",
            "artefact_retention_ledger.retention_tier",
            // backup_artefact
            "backup_artefact.artefact_class", "backup_artefact.artefact_id",
            "backup_artefact.backup_type", "backup_artefact.ciphertext_bytes",
            "backup_artefact.ciphertext_sha256", "backup_artefact.created_at",
            "backup_artefact.deviation_state", "backup_artefact.device_id",
            "backup_artefact.expires_at", "backup_artefact.key_id",
            "backup_artefact.plaintext_bytes", "backup_artefact.plaintext_sha256",
            "backup_artefact.retention_tier", "backup_artefact.software_version",
            "backup_artefact.validation", "backup_artefact.vendor",
            // backup_artefact_deletion_request
            "backup_artefact_deletion_request.artefact_id",
            "backup_artefact_deletion_request.completed_at",
            "backup_artefact_deletion_request.outcome",
            "backup_artefact_deletion_request.requested_at",
            "backup_artefact_deletion_request.requested_by_actor_fingerprint",
            // backup_artefact_retrieval
            "backup_artefact_retrieval.actor_fingerprint", "backup_artefact_retrieval.artefact_id",
            "backup_artefact_retrieval.retrieval_id", "backup_artefact_retrieval.retrieved_at",
            // backup_baseline
            "backup_baseline.artefact_id", "backup_baseline.device_id", "backup_baseline.set_at",
            // backup_endpoint_ineligibility
            "backup_endpoint_ineligibility.device_id", "backup_endpoint_ineligibility.marked_at",
            // backup_policy
            "backup_policy.backup_retention_days", "backup_policy.configuration_retention_days", // V164
            "backup_policy.created_at",
            "backup_policy.daily_backup_cron", "backup_policy.last_scheduled_run_at",
            "backup_policy.major_alert_enabled", "backup_policy.policy_id",
            "backup_policy.schedule_enabled", "backup_policy.snapshot_retention_depth",
            "backup_policy.updated_at", "backup_policy.weekly_snapshot_cron",
            // configuration_artefact
            "configuration_artefact.artefact_ref", "configuration_artefact.ciphertext_bytes",
            "configuration_artefact.ciphertext_sha256", "configuration_artefact.compression",
            "configuration_artefact.created_at", "configuration_artefact.device_id",
            "configuration_artefact.job_id", "configuration_artefact.key_id",
            "configuration_artefact.plaintext_bytes", "configuration_artefact.plaintext_sha256",
            "configuration_artefact.vendor",
            // configuration_notification
            "configuration_notification.created_at", "configuration_notification.device_id",
            "configuration_notification.kind", "configuration_notification.notification_id",
            "configuration_notification.read_at", "configuration_notification.run_id",
            // configuration_run_deviation_entry
            "configuration_run_deviation_entry.entry_id", "configuration_run_deviation_entry.kind",
            "configuration_run_deviation_entry.new_count",
            "configuration_run_deviation_entry.old_count",
            "configuration_run_deviation_entry.section", "configuration_run_deviation_entry.source",
            "configuration_run_deviation_entry.summary_id",
            // configuration_run_deviation_summary
            "configuration_run_deviation_summary.created_at",
            "configuration_run_deviation_summary.run_id",
            "configuration_run_deviation_summary.status",
            "configuration_run_deviation_summary.summary_id",
            // cp_inventory_projection
            "cp_inventory_projection.collected_at", "cp_inventory_projection.device_id",
            "cp_inventory_projection.endpoint_id", "cp_inventory_projection.ha_state",
            "cp_inventory_projection.job_id", "cp_inventory_projection.product_version",
            "cp_inventory_projection.projection_id", "cp_inventory_projection.provenance_id",
            // credential_references
            "credential_references.created_at", "credential_references.credential_reference_id",
            "credential_references.purpose",
            // credentials
            "credentials.allows_check_point", "credentials.allows_palo_alto",
            "credentials.created_at", "credentials.created_by_actor_fingerprint",
            "credentials.credential_id", "credentials.envelope_key_id", "credentials.kind",
            "credentials.secret_set_at", "credentials.snmp_auth_protocol",
            "credentials.snmp_priv_protocol", "credentials.snmp_security_level",
            // device_configuration_index
            "device_configuration_index.entry_count", "device_configuration_index.index_id",
            "device_configuration_index.run_id", "device_configuration_index.section",
            "device_configuration_index.source",
            // device_configuration_override
            "device_configuration_override.category", "device_configuration_override.override_id",
            "device_configuration_override.run_id",
            // device_configuration_run
            "device_configuration_run.canonical_hash", "device_configuration_run.change_state",
            "device_configuration_run.collected_at", "device_configuration_run.created_at",
            "device_configuration_run.device_id", "device_configuration_run.is_primary",
            "device_configuration_run.job_id", "device_configuration_run.raw_bytes",
            "device_configuration_run.raw_hash", "device_configuration_run.read_kind",
            "device_configuration_run.run_id", "device_configuration_run.vendor",
            "device_configuration_run.withheld_line_count",
            // device_interface
            "device_interface.interface_id", "device_interface.kind", "device_interface.run_id",
            "device_interface.state", "device_interface.vlan_id",
            // device_interface_address
            "device_interface_address.address_id", "device_interface_address.family",
            "device_interface_address.interface_id", "device_interface_address.role",
            // device_inventory_ha
            "device_inventory_ha.cluster_mode", "device_inventory_ha.ha_id",
            "device_inventory_ha.role", "device_inventory_ha.run_id", "device_inventory_ha.source",
            // device_inventory_run
            "device_inventory_run.collected_at", "device_inventory_run.context_count",
            "device_inventory_run.created_at", "device_inventory_run.device_id",
            "device_inventory_run.job_id", "device_inventory_run.run_id",
            // device_onboarding
            "device_onboarding.completed_at", "device_onboarding.device_id",
            "device_onboarding.skipped", "device_onboarding.source", "device_onboarding.started_at",
            "device_onboarding.state", "device_onboarding.step", "device_onboarding.step_job_id",
            "device_onboarding.updated_at",
            // device_route
            "device_route.protocol", "device_route.route_id", "device_route.run_id",
            // device_secret_reference
            "device_secret_reference.credential_reference_id", "device_secret_reference.device_id",
            "device_secret_reference.purpose", "device_secret_reference.set_at",
            "device_secret_reference.set_by_actor_fingerprint",
            // devices
            "devices.backup_target", "devices.created_at", "devices.credential_reference_id",
            "devices.device_id", "devices.disabled", "devices.enrollment_state",
            "devices.https_certificate_strict", "devices.identity_mismatch_detected_at",
            "devices.identity_mismatch_state", "devices.is_test_target", "devices.observed_ha_role",
            "devices.observed_model", "devices.observed_software_version",
            "devices.peer_follow_outcome", "devices.recorded_identity_recorded_at",
            "devices.registration_source", "devices.role", "devices.ssh_identity_unverified",
            "devices.vendor_hint",
            // discovery_acknowledgement
            "discovery_acknowledgement.acknowledged_at",
            "discovery_acknowledgement.acknowledged_by_actor_fingerprint",
            "discovery_acknowledgement.discovery_match_key",
            // endpoints
            "endpoints.created_at", "endpoints.device_id", "endpoints.endpoint_id",
            "endpoints.transport_kind",
            // failover_approval
            "failover_approval.approval_id", "failover_approval.approved_at",
            "failover_approval.approved_by", "failover_approval.cluster_ref",
            "failover_approval.execution_nonce", "failover_approval.initiated_by",
            "failover_approval.member_set_revision", "failover_approval.operation",
            "failover_approval.policy", "failover_approval.policy_version",
            "failover_approval.request_revision", "failover_approval.revoked_at",
            "failover_approval.revoked_by", "failover_approval.target_member_ids",
            "failover_approval.unit_ref", "failover_approval.vendor", "failover_approval.vs_id",
            "failover_approval.warning_confirmed_at", "failover_approval.window_from",
            "failover_approval.window_until",
            // failover_check_result
            "failover_check_result.check_no", "failover_check_result.derived",
            "failover_check_result.member_ref", "failover_check_result.observed_at",
            "failover_check_result.phase", "failover_check_result.result_id",
            "failover_check_result.run_id", "failover_check_result.status",
            "failover_check_result.vs_id",
            // failover_quarantine (V169: the release update is audited)
            "failover_quarantine.acknowledged_at", "failover_quarantine.acknowledged_by",
            "failover_quarantine.active", "failover_quarantine.cluster_ref",
            "failover_quarantine.execution_id", "failover_quarantine.quarantined_at",
            "failover_quarantine.quarantined_member_ids", "failover_quarantine.reason",
            "failover_quarantine.second_approver_id",
            // failover_run
            "failover_run.approval_id", "failover_run.cluster_ref", "failover_run.failed_check",
            "failover_run.finished_at", "failover_run.job_id", "failover_run.mutation_possible",
            "failover_run.outcome", "failover_run.request_revision", "failover_run.requested_by",
            "failover_run.run_id", "failover_run.run_kind", "failover_run.scheduled_for",
            "failover_run.started_at", "failover_run.state", "failover_run.step",
            "failover_run.target_member_ids", "failover_run.vendor", "failover_run.vs_id",
            // gate_registry
            "gate_registry.action_class", "gate_registry.canonical_command_key",
            "gate_registry.created_at", "gate_registry.gate_id", "gate_registry.max_frequency",
            "gate_registry.platform_role_scope", "gate_registry.retry_rule",
            "gate_registry.safe_telemetry_fields", "gate_registry.secret_output_risk",
            "gate_registry.session_reuse_rule", "gate_registry.shell_context",
            "gate_registry.sign_off_state", "gate_registry.source_document_pointer",
            "gate_registry.timeout_s", "gate_registry.transport_kind",
            "gate_registry.unsupported_behavior_ref", "gate_registry.vendor",
            // https_endpoint_cert_trust
            "https_endpoint_cert_trust.authorized_at", "https_endpoint_cert_trust.first_seen_at",
            "https_endpoint_cert_trust.management_port", "https_endpoint_cert_trust.not_after",
            "https_endpoint_cert_trust.status", "https_endpoint_cert_trust.trust_entry_id",
            // job_reconciliation
            "job_reconciliation.job_id", "job_reconciliation.reconciled_outcome",
            "job_reconciliation.recorded_at", "job_reconciliation.recorded_by",
            // job_step_attempt
            "job_step_attempt.action_class", "job_step_attempt.attempt_id",
            "job_step_attempt.attempt_number", "job_step_attempt.created_at",
            "job_step_attempt.error_class", "job_step_attempt.fingerprint_sha256",
            "job_step_attempt.job_id", "job_step_attempt.lease_epoch",
            "job_step_attempt.matched_expectation", "job_step_attempt.mutation_boundary_crossed",
            "job_step_attempt.outcome", "job_step_attempt.output_bytes",
            "job_step_attempt.output_lines", "job_step_attempt.sent_at",
            "job_step_attempt.step_index", "job_step_attempt.step_kind",
            // job_steps
            "job_steps.job_id", "job_steps.job_step_id", "job_steps.step_index",
            // jobs
            "jobs.action_class", "jobs.admission_deferrals", "jobs.admission_not_before",
            "jobs.business_hours_ack_at", "jobs.business_hours_ack_by",
            "jobs.cancel_requested", "jobs.capability_id", "jobs.diagnostic_exit_status",
            "jobs.diagnostic_gate_id", "jobs.diagnostic_gate_revision",
            "jobs.diagnostic_line_count", "jobs.diagnostic_port", "jobs.diagnostic_shape_id",
            "jobs.diagnostic_status_token", "jobs.diagnostic_viewed_at",
            "jobs.diagnostic_viewed_by", "jobs.finished_at", "jobs.idempotency_key", "jobs.job_id",
            "jobs.job_type", "jobs.last_heartbeat_at", "jobs.lease_epoch", "jobs.lease_expires_at",
            "jobs.lease_owner_generation", "jobs.lease_worker_id", "jobs.outcome",
            "jobs.precheck_results", "jobs.reconciliation_ref", "jobs.ssh_identity_unverified",
            "jobs.state", "jobs.submitted_at", "jobs.submitted_by_actor_fingerprint",
            "jobs.submitted_in_business_hours",
            "jobs.target_device_id", "jobs.target_kind", "jobs.target_ref", "jobs.terminal_reason",
            "jobs.window_bypass_role",
            // lifecycle_catalog
            "lifecycle_catalog.catalog_id", "lifecycle_catalog.end_of_engineering",
            "lifecycle_catalog.end_of_sale", "lifecycle_catalog.end_of_support",
            "lifecycle_catalog.imported_at", "lifecycle_catalog.kind", "lifecycle_catalog.product",
            "lifecycle_catalog.source", "lifecycle_catalog.vendor",
            // local_credentials
            "local_credentials.algorithm_id", "local_credentials.created_at",
            "local_credentials.created_by_actor_fingerprint", "local_credentials.enabled",
            "local_credentials.failed_attempt_count", "local_credentials.local_identity_id",
            "local_credentials.locked_until", "local_credentials.memory_cost_kib",
            "local_credentials.must_change_password", "local_credentials.parallelism",
            "local_credentials.password_set_at", "local_credentials.time_cost",
            "local_credentials.updated_at",
            // management_endpoint_pan_cert_trust
            "management_endpoint_pan_cert_trust.authorized_at",
            "management_endpoint_pan_cert_trust.management_port",
            "management_endpoint_pan_cert_trust.observed_at",
            "management_endpoint_pan_cert_trust.status",
            "management_endpoint_pan_cert_trust.superseded_by",
            "management_endpoint_pan_cert_trust.trust_entry_id",
            "management_endpoint_pan_cert_trust.vendor",
            // management_endpoint_ssh_trust
            "management_endpoint_ssh_trust.authorized_at",
            "management_endpoint_ssh_trust.key_algorithm",
            "management_endpoint_ssh_trust.management_port",
            "management_endpoint_ssh_trust.observed_at", "management_endpoint_ssh_trust.status",
            "management_endpoint_ssh_trust.superseded_by",
            "management_endpoint_ssh_trust.trust_entry_id", "management_endpoint_ssh_trust.vendor",
            // notification_routes
            "notification_routes.enabled", "notification_routes.last_sent_at",
            "notification_routes.type", "notification_routes.updated_at",
            "notification_routes.updated_by", "notification_routes.watermark_audit",
            "notification_routes.watermark_time",
            // notification_settings
            "notification_settings.audit_watermark",
            "notification_settings.forward_audit_to_syslog",
            "notification_settings.job_failure_watermark",
            "notification_settings.notify_job_failure", "notification_settings.settings_id",
            "notification_settings.smtp_enabled", "notification_settings.smtp_port",
            "notification_settings.smtp_starttls", "notification_settings.syslog_enabled",
            "notification_settings.syslog_facility", "notification_settings.syslog_port",
            "notification_settings.syslog_protocol", "notification_settings.updated_at",
            "notification_settings.updated_by",
            // packet_analysis_* (V154; title, display_name, sha256 and storage_key are tier-2 redacted)
            "packet_analysis_case.case_id", "packet_analysis_case.created_at", "packet_analysis_case.created_by",
            "packet_analysis_case.expires_at", "packet_analysis_case.payload_zeroing", "packet_analysis_case.state",
            "packet_analysis_case.total_bytes",
            "packet_analysis_capture.capture_format", "packet_analysis_capture.capture_id",
            "packet_analysis_capture.case_id", "packet_analysis_capture.size_bytes",
            "packet_analysis_capture.uploaded_at", "packet_analysis_capture.uploaded_by",
            // packet_analysis_host_telemetry (V158; display_name, sha256, storage_key and anchor_local are tier-2 redacted)
            "packet_analysis_host_telemetry.aligned_at", "packet_analysis_host_telemetry.aligned_by",
            "packet_analysis_host_telemetry.case_id", "packet_analysis_host_telemetry.detected_format",
            "packet_analysis_host_telemetry.offset_seconds", "packet_analysis_host_telemetry.recognition_note",
            "packet_analysis_host_telemetry.size_bytes", "packet_analysis_host_telemetry.telemetry_id",
            "packet_analysis_host_telemetry.time_zone", "packet_analysis_host_telemetry.uploaded_at",
            "packet_analysis_host_telemetry.uploaded_by",
            "packet_analysis_run.case_id", "packet_analysis_run.engine_version", "packet_analysis_run.failure_reason",
            "packet_analysis_run.finished_at", "packet_analysis_run.job_id", "packet_analysis_run.masked_summary",
            "packet_analysis_run.requested_at", "packet_analysis_run.requested_by", "packet_analysis_run.started_at",
            "packet_analysis_run.state",
            "packet_analysis_ledger.actor_fingerprint", "packet_analysis_ledger.bytes", "packet_analysis_ledger.capture_id",
            "packet_analysis_ledger.case_id", "packet_analysis_ledger.event", "packet_analysis_ledger.job_id",
            "packet_analysis_ledger.ledger_id", "packet_analysis_ledger.occurred_at",
            // panorama_assignment
            "panorama_assignment.assignment_id", "panorama_assignment.computed_at",
            // policy_offline_analysis
            "policy_offline_analysis.analysis_id", "policy_offline_analysis.created_at",
            "policy_offline_analysis.policy_id", "policy_offline_analysis.uploader",
            // provenance_records
            "provenance_records.capability_version", "provenance_records.capture_artifact_id",
            "provenance_records.collected_at", "provenance_records.fingerprint_sha256",
            "provenance_records.parser_version", "provenance_records.provenance_id",
            "provenance_records.run_id", "provenance_records.sanitized_fragment",
            "provenance_records.source_location", "provenance_records.step_id",
            // rbac_permissions
            "rbac_permissions.action", "rbac_permissions.id",
            // rbac_role_permissions
            "rbac_role_permissions.permission_id", "rbac_role_permissions.role_id",
            // rbac_roles
            "rbac_roles.id", "rbac_roles.is_system", "rbac_roles.name", "rbac_roles.token_string",
            // role_bindings
            "role_bindings.binding_id", "role_bindings.binding_kind", "role_bindings.created_at",
            "role_bindings.created_by_actor_fingerprint", "role_bindings.directory_profile_id",
            "role_bindings.group_reference_key_id", "role_bindings.revoked_at",
            "role_bindings.revoked_by_actor_fingerprint", "role_bindings.role_token",
            // sanitize_* (V156; wrapped_data_key, lookup_key and sealed_original are tier-1 redacted)
            "sanitize_job.job_id", "sanitize_job.created_by", "sanitize_job.created_at", "sanitize_job.state",
            "sanitize_job.file_type", "sanitize_job.input_bytes", "sanitize_job.output_bytes", "sanitize_job.counts",
            "sanitize_job.failure_reason", "sanitize_job.finished_at", "sanitize_job.output_state",
            "sanitize_job.vault_entries", "sanitize_job.vault_expires_at", "sanitize_job.vault_pruned_at", "sanitize_job.key_id",
            "sanitize_vault_entry.entry_id", "sanitize_vault_entry.job_id", "sanitize_vault_entry.entry_kind",
            "sanitize_revert_job.revert_id", "sanitize_revert_job.created_by", "sanitize_revert_job.created_at",
            "sanitize_revert_job.scope", "sanitize_revert_job.source_job_id", "sanitize_revert_job.state",
            "sanitize_revert_job.file_type", "sanitize_revert_job.input_bytes", "sanitize_revert_job.output_bytes",
            "sanitize_revert_job.counts", "sanitize_revert_job.failure_reason", "sanitize_revert_job.finished_at",
            "sanitize_revert_job.output_state",
            "sanitize_ledger.ledger_id", "sanitize_ledger.job_id", "sanitize_ledger.revert_id", "sanitize_ledger.event",
            "sanitize_ledger.actor_fingerprint", "sanitize_ledger.entries", "sanitize_ledger.occurred_at",
            // secrets_metadata
            "secrets_metadata.backend_kind", "secrets_metadata.component",
            "secrets_metadata.created_at", "secrets_metadata.purpose",
            "secrets_metadata.rotated_at", "secrets_metadata.rotation_policy_ref",
            "secrets_metadata.secret_id",
            // sessions
            "sessions.absolute_expires_at", "sessions.actor_fingerprint", "sessions.created_at",
            "sessions.end_reason", "sessions.ended_by_actor_fingerprint",
            "sessions.idle_deadline_at", "sessions.last_seen_at", "sessions.machine",
            "sessions.session_id", "sessions.state", "sessions.superseded_by_session_id",
            // ssh_host_key_observation
            "ssh_host_key_observation.connection_allowed", "ssh_host_key_observation.device_id",
            "ssh_host_key_observation.endpoint_id", "ssh_host_key_observation.first_seen",
            "ssh_host_key_observation.key_algorithm", "ssh_host_key_observation.last_job_id",
            "ssh_host_key_observation.last_seen", "ssh_host_key_observation.management_port",
            "ssh_host_key_observation.observation_id", "ssh_host_key_observation.seen_count",
            "ssh_host_key_observation.status", "ssh_host_key_observation.strict_refusal",
            // ssh_trust_settings
            "ssh_trust_settings.mode", "ssh_trust_settings.settings_id",
            // device_crash_alert (V160)
            "device_crash_alert.alert_id", "device_crash_alert.created_at", "device_crash_alert.device_id",
            "device_crash_alert.file_count", "device_crash_alert.job_id", "device_crash_alert.newest_mtime",
            "device_crash_alert.notified_at",
            // device_crash_entry (V160)
            "device_crash_entry.baseline", "device_crash_entry.device_id", "device_crash_entry.directory",
            "device_crash_entry.entry_id", "device_crash_entry.first_seen_at", "device_crash_entry.first_seen_job",
            "device_crash_entry.last_seen_job", "device_crash_entry.mtime", "device_crash_entry.removed_at",
            "device_crash_entry.size",
            // system_log_chunk (V160)
            "system_log_chunk.bytes", "system_log_chunk.chunk_id", "system_log_chunk.ciphertext_bytes",
            "system_log_chunk.collected_at", "system_log_chunk.device_id", "system_log_chunk.end_offset",
            "system_log_chunk.first_line_time", "system_log_chunk.flags", "system_log_chunk.inode",
            "system_log_chunk.job_id", "system_log_chunk.last_line_time", "system_log_chunk.line_time_basis",
            "system_log_chunk.lines", "system_log_chunk.log_day",
            "system_log_chunk.prune_reason", "system_log_chunk.pruned_at", "system_log_chunk.redaction_count",
            "system_log_chunk.redaction_rule_set", // V161
            "system_log_chunk.source_key", "system_log_chunk.start_offset",
            // system_log_cursor (V160)
            "system_log_cursor.cursor_key", "system_log_cursor.device_epoch_at_read", "system_log_cursor.device_id",
            "system_log_cursor.fast_rotating", "system_log_cursor.inode", "system_log_cursor.offset_bytes",
            "system_log_cursor.pan_boundary_seqnos", "system_log_cursor.pan_last_receive_time",
            "system_log_cursor.retention_horizon_seconds", "system_log_cursor.source_key",
            "system_log_cursor.updated_at", "system_log_cursor.updated_job_id",
            // system_log_file (V160)
            "system_log_file.ciphertext_bytes", "system_log_file.collected_at", "system_log_file.device_id",
            "system_log_file.expires_at", "system_log_file.file_id", "system_log_file.flags",
            "system_log_file.job_id", "system_log_file.kind", "system_log_file.mtime", "system_log_file.name_time",
            "system_log_file.pruned_at", "system_log_file.size", "system_log_file.status",
            // system_log_gap (V160)
            "system_log_gap.created_at", "system_log_gap.device_id", "system_log_gap.extent_unknown",
            "system_log_gap.gap_id", "system_log_gap.job_id", "system_log_gap.kind",
            "system_log_gap.known_lost_bytes", "system_log_gap.pending_bytes", "system_log_gap.source_key",
            "system_log_gap.window_end_epoch", "system_log_gap.window_start_epoch",
            // system_log_run (V160)
            "system_log_run.bytes_read", "system_log_run.bytes_stored", "system_log_run.created_at",
            "system_log_run.device_id", "system_log_run.duration_ms", "system_log_run.job_id",
            "system_log_run.outcome", "system_log_run.reads_issued", "system_log_run.run_id",
            "system_log_run.source_key",
            // system_log_settings (V160)
            "system_log_settings.device_cap_bytes", "system_log_settings.hcp_retention_days",
            "system_log_settings.include_management_servers", "system_log_settings.ledger_retention_days",
            "system_log_settings.raw_retention_days", "system_log_settings.settings_id",
            "system_log_settings.spike_data_cap_bytes", "system_log_settings.spike_data_enabled",
            "system_log_settings.spike_data_retention_days", "system_log_settings.updated_at",
            "system_log_settings.updated_by",
            // system_log_measurement (V151): counts, field names, formats and enum-like values only
            "system_log_measurement.device_id", "system_log_measurement.job_id",
            "system_log_measurement.measured_at", "system_log_measurement.measurement_id",
            "system_log_measurement.source", "system_log_measurement.summary",
            "system_log_measurement.vendor");

    /**
     * Column-name fragments that mark a value as secret, credential-bearing or
     * device-identity-bearing. A column whose name contains one of these and
     * is not in {@code audit_redaction_policy} fails
     * {@link #noSensitivelyNamedColumnEscapesTheRedactionPolicy()} — an
     * independent second gate, so that adding a new sensitive column to the
     * register above is not enough to persist it.
     */
    private static final List<String> SENSITIVE_NAME_FRAGMENTS = List.of(
            "secret", "password", "passwd", "credential", "token", "encrypted",
            "private_key", "pointer", "address", "evidence", "captured");

    /** Register entries exempt from the name heuristic, each by decision. */
    private static final Set<String> NAME_HEURISTIC_EXEMPT = Set.of(
            // An opaque role name, not a bearer token (C3 §3.2).
            "role_bindings.role_token",
            // A risk classification produced by the command gate, not output.
            "gate_registry.secret_output_risk",
            // The identifier of a secret record, never the secret itself.
            "secrets_metadata.secret_id",
            // A pointer to a document, not to a credential.
            "gate_registry.source_document_pointer",
            // Identifiers of a credential reference, never the credential.
            "credential_references.credential_reference_id",
            "devices.credential_reference_id",
            // A digest, already non-reversible.
            "job_step_attempt.fingerprint_sha256",
            "provenance_records.fingerprint_sha256",
            // Identifiers of a credential or credential reference, never the credential.
            "credentials.credential_id",
            "device_secret_reference.credential_reference_id",
            // Timestamps and a flag about a secret, never the secret (the narrow captures keep them).
            "credentials.secret_set_at",
            "local_credentials.password_set_at",
            "local_credentials.must_change_password",
            // The identifier of an address row, never the address (which is redacted).
            "device_interface_address.address_id",
            // A closed status enum (V90: UP/DOWN/OTHER/ABSENT), not a bearer token.
            "jobs.diagnostic_status_token",
            // A role name (role:<name>), not a bearer token -- as role_bindings.role_token.
            "rbac_roles.token_string");

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("audit_redaction");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    /**
     * Contract §6.1/§6.2: the value never reaches {@code audit_log}, a digest
     * stands in its place, and the digest still distinguishes two different
     * values while matching two equal ones — so change detection and equality
     * comparison survive redaction.
     */
    @Test
    void sessionCsrfSecretIsReplacedByADigestThatStillDetectsChange() throws SQLException {
        String tokenA = "synthetic-token-" + Ui2Rows.opaqueId("a");
        String tokenB = "synthetic-token-" + Ui2Rows.opaqueId("b");

        try (Connection app = fixture.appConnection()) {
            String s1 = insertSession(app, "actor-redact-1", tokenA);
            String s2 = insertSession(app, "actor-redact-2", tokenB);
            String s3 = insertSession(app, "actor-redact-3", tokenA);

            assertEquals(0L, Ui2Rows.count(app,
                            "SELECT count(*) FROM audit_log WHERE after_state::text LIKE '%" + tokenA
                                    + "%' OR before_state::text LIKE '%" + tokenA + "%'"),
                    "no audit row may contain the csrf_secret value");
            assertEquals(0L, Ui2Rows.count(app,
                            "SELECT count(*) FROM audit_log WHERE after_state::text LIKE '%" + tokenB
                                    + "%' OR before_state::text LIKE '%" + tokenB + "%'"),
                    "no audit row may contain the second csrf_secret value either");

            String d1 = auditedValue(app, s1, "csrf_secret");
            String d2 = auditedValue(app, s2, "csrf_secret");
            String d3 = auditedValue(app, s3, "csrf_secret");

            assertNotNull(d1, "the column must still be present in the snapshot, as a digest");
            assertTrue(d1.startsWith("sha256:"), "the stand-in must be a labelled sha256 digest");
            assertEquals(7 + 64, d1.length(), "a sha256 hex digest is 64 characters");
            assertNotEquals(d1, d2, "two different values must produce two different digests");
            assertEquals(d1, d3, "the same value twice must produce the same digest");
        }
    }

    /**
     * V153: the digest is taken over the value's UTF-8 bytes. V5's
     * {@code text::bytea} cast parsed backslash escapes, so a redacted value
     * containing a backslash made the audited write itself fail.
     */
    @Test
    void redactedValueWithABackslashIsDigestedOverItsUtf8Bytes() throws Exception {
        String token = "synthetic\\token-" + Ui2Rows.opaqueId("bs") + "-\u00fc";
        try (Connection app = fixture.appConnection()) {
            String sessionId = insertSession(app, "actor-redact-backslash", token);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("sha256:" + java.util.HexFormat.of().formatHex(digest),
                    auditedValue(app, sessionId, "csrf_secret"),
                    "the stand-in must be the sha256 of the value's UTF-8 bytes");
        }
    }

    /** Contract §6.3: a NULL redacted column stays NULL, distinguishably. */
    @Test
    void nullRedactedColumnStaysNullAndIsDistinguishableFromARedactedValue() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            String credentialReferenceId = Ui2Rows.insertCredentialReference(app);
            String deviceId = Ui2Rows.insertDevice(app, credentialReferenceId, "DRAFT");
            String endpointId = Ui2Rows.insertEndpoint(app, deviceId);

            // endpoints.address_ref is NOT NULL in V1, so the NULL case is
            // proved on a nullable redacted column instead: role_bindings'
            // group_reference_encrypted is nullable only in principle, so use
            // the one redacted column that is genuinely nullable here.
            String endpointDigest = auditedValueFor(app, "endpoints", endpointId, "address_ref");
            assertNotNull(endpointDigest, "a non-null redacted column must leave a digest");
            assertTrue(endpointDigest.startsWith("sha256:"));

            String evidence = auditedValueFor(app, "endpoints", endpointId, "transport_kind");
            assertNotNull(evidence, "a non-redacted column must keep its value");
            assertFalse(evidence.startsWith("sha256:"),
                    "a column outside the policy must not be digested");
        }
    }

    /**
     * Contract §6.4: every column named by §3 is proven redacted by an
     * assertion naming that table and column — not by a blanket scan that
     * would pass if the policy were empty.
     */
    @Test
    void everyDeclaredRedactionIsPresentInThePolicyWithItsTier() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            assertEquals(1, tierOf(app, "sessions", "csrf_secret"));
            assertEquals(1, tierOf(app, "role_bindings", "group_reference_encrypted"));
            assertEquals(2, tierOf(app, "credential_references", "backend_pointer"));
            assertEquals(2, tierOf(app, "secrets_metadata", "reference_pointer"));
            assertEquals(2, tierOf(app, "endpoints", "address_ref"));
            assertEquals(2, tierOf(app, "job_step_attempt", "captured_variables"));
            assertEquals(2, tierOf(app, "job_reconciliation", "evidence"));
            // V154: operator free text and the identity/location of raw evidence (decision record 2026-10-15 §5).
            assertEquals(2, tierOf(app, "packet_analysis_case", "title"));
            assertEquals(2, tierOf(app, "packet_analysis_capture", "display_name"));
            assertEquals(2, tierOf(app, "packet_analysis_capture", "sha256"));
            assertEquals(2, tierOf(app, "packet_analysis_capture", "storage_key"));
            // V156: the vault's key material and its two sealed/keyed columns (DATA_SANITIZER_CONTRACT.md §2).
            assertEquals(1, tierOf(app, "sanitize_job", "wrapped_data_key"));
            assertEquals(1, tierOf(app, "sanitize_vault_entry", "lookup_key"));
            assertEquals(1, tierOf(app, "sanitize_vault_entry", "sealed_original"));
            // V160: the system-log chunk and file keys are wrapped key material; names and bytes are digests.
            assertEquals(1, tierOf(app, "system_log_chunk", "wrapped_data_key"));
            assertEquals(1, tierOf(app, "system_log_file", "wrapped_data_key"));
            assertEquals(2, tierOf(app, "system_log_cursor", "anchor_hmac"));

            // Beyond §3's seven: V17, V91, V97, V144, V153, V154 and V156 declared further redactions. The policy
            // must hold exactly the declared set -- an extra entry is an undeclared redaction, a
            // missing one an unredacted column.
            java.util.Map<String, Integer> live = new java.util.TreeMap<>();
            try (Statement statement = app.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT table_name || '.' || column_name, tier FROM audit_redaction_policy")) {
                while (rs.next()) {
                    live.put(rs.getString(1), rs.getInt(2));
                }
            }
            assertEquals(new java.util.TreeMap<>(DECLARED_REDACTIONS), live,
                    "the policy must hold exactly the declared redactions with their tiers");
        }
    }

    /** Every declared redaction and its tier: V5 (contract §3), V17, V91, V97, V144, V153, V154, V156. */
    private static final java.util.Map<String, Integer> DECLARED_REDACTIONS = declaredRedactions(
            "backup_artefact.hostname_fingerprint", 2, "backup_artefact.recovery_volume_path", 2,
            "backup_artefact.virtual_system_ref", 2, "backup_artefact.wrapped_data_key", 1,
            "backup_artefact_deletion_request.reason", 2,
            "backup_artefact_retrieval.destination_path", 2, "backup_artefact_retrieval.reason", 2,
            "backup_endpoint_ineligibility.reason", 2, "configuration_notification.override_paths",
            2, "configuration_notification.summary", 2, "configuration_run_deviation_entry.context",
            2, "credential_references.backend_pointer", 2, "credentials.display_name", 2,
            "credentials.encrypted_passphrase", 1, "credentials.encrypted_secret", 1,
            "credentials.username", 2, "device_configuration_index.context", 2,
            "device_configuration_override.context", 2,
            "device_configuration_override.element_path", 2,
            "device_configuration_override.panorama_source", 2,
            "device_configuration_run.artefact_ref", 2, "device_configuration_run.sanitized_text",
            2, "device_interface.context", 2, "device_interface.mac_address", 2,
            "device_interface.name", 2, "device_interface.parent", 2, "device_interface.zone", 2,
            "device_interface_address.address", 2, "device_inventory_ha.context", 2,
            "device_inventory_run.virtual_systems", 2, "device_onboarding.reason", 2,
            "device_route.context", 2, "device_route.destination", 2, "device_route.interface", 2,
            "device_route.next_hop", 2, "device_route.route_table", 2, "devices.cluster_member_ref",
            2, "devices.discovery_match_key", 2, "devices.ha_pair_claim", 2,
            "devices.ha_unit_token", 2, "devices.identity_mismatch_presented_primary", 2,
            "devices.identity_mismatch_presented_secondary", 2, "devices.observed_hostname", 2,
            "devices.peer_follow_reason", 2, "devices.recorded_identity_primary", 2,
            "devices.recorded_identity_secondary", 2, "devices.virtual_system_ref", 2,
            "discovery_acknowledgement.reason", 2, "endpoints.address_ref", 2,
            "failover_approval.reason", 2, "failover_quarantine.review_notes", 2, "failover_run.message", 2,
            "https_endpoint_cert_trust.authorized_by", 2,
            "https_endpoint_cert_trust.fingerprint_sha256", 2,
            "https_endpoint_cert_trust.issuer_cn", 2,
            "https_endpoint_cert_trust.management_address", 2,
            "https_endpoint_cert_trust.subject_cn", 2, "job_reconciliation.evidence", 2,
            "job_step_attempt.captured_variables", 2, "jobs.diagnostic_command", 2,
            "jobs.diagnostic_masked_output", 2, "jobs.diagnostic_output_key", 1,
            "jobs.diagnostic_output_ref", 2, "jobs.transcript_artefact_key", 1,
            "jobs.transcript_artefact_ref", 2, "lifecycle_catalog.imported_by", 2,
            "lifecycle_catalog.note", 2, "local_credentials.local_identity_name", 2,
            "local_credentials.salt", 1, "local_credentials.verifier", 1,
            "management_endpoint_pan_cert_trust.authorized_by", 2,
            "management_endpoint_pan_cert_trust.fingerprint_sha256", 2,
            "management_endpoint_pan_cert_trust.management_address", 2,
            "management_endpoint_ssh_trust.authorized_by", 2,
            "management_endpoint_ssh_trust.fingerprint_sha256", 2,
            "management_endpoint_ssh_trust.management_address", 2, "notification_routes.last_error",
            2, "notification_routes.recipients", 2, "notification_settings.smtp_credential_ref", 2,
            "notification_settings.smtp_from", 2, "notification_settings.smtp_host", 2,
            "notification_settings.smtp_to", 2, "notification_settings.syslog_host", 2,
            "packet_analysis_capture.display_name", 2, "packet_analysis_capture.sha256", 2,
            "packet_analysis_capture.storage_key", 2, "packet_analysis_case.title", 2,
            "packet_analysis_host_telemetry.anchor_local", 2, "packet_analysis_host_telemetry.display_name", 2,
            "packet_analysis_host_telemetry.sha256", 2, "packet_analysis_host_telemetry.storage_key", 2,
            "panorama_assignment.device_groups", 2, "panorama_assignment.device_serial", 2,
            "panorama_assignment.template_stack", 2, "panorama_assignment.templates", 2,
            "policy_offline_analysis.aggregates", 2, "policy_offline_analysis.file_name", 2,
            "rbac_roles.description", 2, "role_bindings.group_reference_encrypted", 1,
            "sanitize_job.wrapped_data_key", 1, "sanitize_vault_entry.lookup_key", 1,
            "sanitize_vault_entry.sealed_original", 1,
            "secrets_metadata.reference_pointer", 2, "sessions.csrf_secret", 1,
            "ssh_host_key_observation.expected_fingerprint", 2,
            "ssh_host_key_observation.management_address", 2,
            "ssh_host_key_observation.presented_fingerprint", 2,
            // V160 (system-log phase B): name and log-byte digests, wrapped keys, artefact locations, device detail.
            "system_log_cursor.anchor_hmac", 2, "system_log_cursor.family_snapshot", 2,
            "system_log_chunk.file_name_hmac", 2, "system_log_chunk.artefact_ref", 2,
            "system_log_chunk.wrapped_data_key", 1, "system_log_gap.detail", 2,
            "system_log_file.remote_name_hmac", 2, "system_log_file.artefact_ref", 2,
            "system_log_file.wrapped_data_key", 1, "device_crash_entry.name_hmac", 2,
            "device_crash_entry.process", 2, "device_crash_alert.process", 2, "system_log_run.detail", 2,
            // V161: keyed content identities are redacted like the anchors.
            "system_log_chunk.plaintext_hmac", 2, "system_log_file.content_hmac", 2);

    private static java.util.Map<String, Integer> declaredRedactions(Object... pairs) {
        java.util.Map<String, Integer> declared = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (declared.put((String) pairs[i], (Integer) pairs[i + 1]) != null) {
                throw new IllegalStateException("declared twice: " + pairs[i]);
            }
        }
        return java.util.Map.copyOf(declared);
    }

    /**
     * Contract §4, the clause that matters more than the current list: every
     * column of every audited table is classified exactly once. A column in
     * neither {@code audit_redaction_policy} nor {@link #AUDITED_IN_FULL}
     * fails here, naming the table and column, so a new column forces a
     * decision in the same change.
     */
    @Test
    void everyAuditedColumnIsClassifiedExactlyOnce() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            Set<String> redacted = redactedColumns(app);
            List<String> unclassified = new ArrayList<>();
            List<String> classifiedTwice = new ArrayList<>();

            for (String column : auditedColumns(app)) {
                boolean inPolicy = redacted.contains(column);
                boolean inRegister = AUDITED_IN_FULL.contains(column);
                if (!inPolicy && !inRegister) {
                    unclassified.add(column);
                } else if (inPolicy && inRegister) {
                    classifiedTwice.add(column);
                }
            }

            assertEquals(List.of(), unclassified,
                    "every column of an audited table must be classified: either redacted in "
                            + "audit_redaction_policy, or listed in AUDITED_IN_FULL by decision");
            assertEquals(List.of(), classifiedTwice,
                    "a column must not be both redacted and audited in full");
        }
    }

    /**
     * A second, independent gate: a column whose name carries a
     * secret/credential/identity marker must be in the redaction policy.
     * Listing it in {@link #AUDITED_IN_FULL} is therefore not enough to
     * persist it — someone must either redact it or add an explicit,
     * reasoned exemption.
     */
    @Test
    void noSensitivelyNamedColumnEscapesTheRedactionPolicy() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            Set<String> redacted = redactedColumns(app);
            List<String> escaped = new ArrayList<>();

            for (String column : auditedColumns(app)) {
                if (redacted.contains(column) || NAME_HEURISTIC_EXEMPT.contains(column)) {
                    continue;
                }
                String name = column.substring(column.indexOf('.') + 1).toLowerCase(Locale.ROOT);
                for (String fragment : SENSITIVE_NAME_FRAGMENTS) {
                    if (name.contains(fragment)) {
                        escaped.add(column + " (matched '" + fragment + "')");
                        break;
                    }
                }
            }

            assertEquals(List.of(), escaped,
                    "a sensitively named column must be redacted, or exempted with a stated reason");
        }
    }

    /** Contract §6.7: the application cannot rewrite its own audit policy. */
    @Test
    void ui2AppCannotWriteTheRedactionPolicy() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            for (String sql : List.of(
                    "INSERT INTO audit_redaction_policy VALUES ('sessions', 'state', 1, 'x')",
                    "UPDATE audit_redaction_policy SET tier = 2 WHERE table_name = 'sessions'",
                    "DELETE FROM audit_redaction_policy")) {
                SQLException thrown = assertThrows(SQLException.class, () -> {
                    try (Statement statement = app.createStatement()) {
                        statement.execute(sql);
                    }
                }, "ui2_app must not be able to change the redaction policy");
                assertEquals("42501", thrown.getSQLState(),
                        "the refusal must be insufficient_privilege, not an incidental failure");
            }
        }
    }

    /**
     * Contract §5.3: redaction is applied <em>after</em> the context check,
     * never instead of it. V5 must not have weakened the fail-closed
     * behaviour V1 established.
     */
    @Test
    void auditContextMissingStillFailsClosedAfterV5() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            app.setAutoCommit(false);
            try {
                SQLException thrown = assertThrows(SQLException.class, () -> {
                    try (Statement statement = app.createStatement()) {
                        statement.execute(insertSessionSql(Ui2Rows.opaqueId("sess"),
                                "actor-no-context", "synthetic-token"));
                    }
                });
                assertTrue(thrown.getMessage().contains("audit_context_missing"),
                        "the refusal must still be audit_context_missing");
            } finally {
                app.rollback();
                app.setAutoCommit(true);
            }
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /** Every {@code table.column} of every table carrying a {@code trg_audit_*} trigger. */
    private static Set<String> auditedColumns(Connection connection) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT c.relname || '.' || a.attname "
                             + "FROM pg_trigger t "
                             + "JOIN pg_class c ON c.oid = t.tgrelid "
                             + "JOIN pg_attribute a ON a.attrelid = c.oid "
                             + "  AND a.attnum > 0 AND NOT a.attisdropped "
                             + "WHERE t.tgname LIKE 'trg_audit_%' AND NOT t.tgisinternal "
                             + "ORDER BY 1")) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        if (columns.isEmpty()) {
            fail("no audited columns were found — this test would pass vacuously");
        }
        return columns;
    }

    private static Set<String> redactedColumns(Connection connection) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT table_name || '.' || column_name FROM audit_redaction_policy")) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        return columns;
    }

    private static int tierOf(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT tier FROM audit_redaction_policy WHERE table_name = '" + table
                             + "' AND column_name = '" + column + "'")) {
            if (!rs.next()) {
                fail(table + "." + column + " is declared redacted by contract §3 but absent "
                        + "from audit_redaction_policy");
            }
            return rs.getInt(1);
        }
    }

    /** One key of the {@code after_state} snapshot of a {@code sessions} audit row. */
    private static String auditedValue(Connection connection, String sessionId, String key)
            throws SQLException {
        return auditedValueFor(connection, "sessions", sessionId, key);
    }

    private static String auditedValueFor(Connection connection, String table, String rowPk,
            String key) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT after_state ->> '" + key + "' FROM audit_log "
                             + "WHERE table_name = '" + table + "' AND row_pk = '" + rowPk
                             + "' ORDER BY audit_id LIMIT 1")) {
            if (!rs.next()) {
                fail("no audit row for " + table + " " + rowPk);
            }
            return rs.getString(1);
        }
    }

    private static String insertSession(Connection app, String actor, String csrfSecret)
            throws SQLException {
        String sessionId = Ui2Rows.opaqueId("sess");
        app.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, "harness.redaction");
            try (Statement statement = app.createStatement()) {
                statement.execute(insertSessionSql(sessionId, actor, csrfSecret));
            }
            app.commit();
        } catch (SQLException e) {
            app.rollback();
            throw e;
        } finally {
            app.setAutoCommit(true);
        }
        return sessionId;
    }

    private static String insertSessionSql(String sessionId, String actor, String csrfSecret) {
        Instant now = Instant.now();
        return "INSERT INTO sessions(session_id, actor_fingerprint, csrf_secret, state, "
                + "idle_deadline_at, absolute_expires_at) VALUES ('" + sessionId + "', '" + actor
                + "', '" + csrfSecret + "', 'ACTIVE', '"
                + now.plus(15, ChronoUnit.MINUTES) + "', '" + now.plus(8, ChronoUnit.HOURS) + "')";
    }
}
