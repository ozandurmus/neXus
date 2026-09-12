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
     * carrying a {@code trg_audit_*} trigger (V1's eight, V2's
     * {@code role_bindings}/{@code sessions}, V4's {@code job_step_attempt},
     * {@code job_reconciliation} and {@code gate_registry}).
     */
    private static final Map<String, Set<String>> AUDITED_IN_FULL = Map.ofEntries(
            Map.entry("cp_inventory_projection", Set.of(
                    "collected_at", "device_id", "endpoint_id", "ha_state", "job_id",
                    "product_version", "projection_id", "provenance_id")),
            Map.entry("credential_references", Set.of(
                    "created_at", "credential_reference_id", "purpose")),
            Map.entry("devices", Set.of(
                    "created_at", "credential_reference_id", "device_id", "disabled",
                    "enrollment_state", "is_test_target", "registration_source", "vendor_hint")),
            Map.entry("endpoints", Set.of(
                    "created_at", "device_id", "endpoint_id", "transport_kind")),
            Map.entry("gate_registry", Set.of(
                    "action_class", "canonical_command_key", "created_at", "gate_id",
                    "max_frequency", "platform_role_scope", "retry_rule",
                    "safe_telemetry_fields", "secret_output_risk", "session_reuse_rule",
                    "shell_context", "sign_off_state", "source_document_pointer", "timeout_s",
                    "transport_kind", "unsupported_behavior_ref", "vendor")),
            Map.entry("job_reconciliation", Set.of(
                    "job_id", "reconciled_outcome", "recorded_at", "recorded_by")),
            Map.entry("job_step_attempt", Set.of(
                    "action_class", "attempt_id", "attempt_number", "created_at",
                    "error_class", "fingerprint_sha256", "job_id", "lease_epoch",
                    "matched_expectation", "mutation_boundary_crossed", "outcome",
                    "output_bytes", "output_lines", "sent_at", "step_index", "step_kind")),
            Map.entry("job_steps", Set.of("job_id", "job_step_id", "step_index")),
            Map.entry("jobs", Set.of(
                    "action_class", "capability_id", "finished_at", "idempotency_key",
                    "job_id", "job_type", "last_heartbeat_at", "lease_epoch",
                    "lease_expires_at", "lease_worker_id", "outcome", "precheck_results",
                    "reconciliation_ref", "state", "submitted_at",
                    "submitted_by_actor_fingerprint", "target_device_id", "terminal_reason")),
            Map.entry("provenance_records", Set.of(
                    "capability_version", "capture_artifact_id", "collected_at",
                    "fingerprint_sha256", "parser_version", "provenance_id", "run_id",
                    "sanitized_fragment", "source_location", "step_id")),
            Map.entry("role_bindings", Set.of(
                    "binding_id", "created_at", "created_by_actor_fingerprint",
                    "group_reference_key_id", "revoked_at", "revoked_by_actor_fingerprint",
                    "role_token")),
            Map.entry("secrets_metadata", Set.of(
                    "backend_kind", "component", "created_at", "purpose", "rotated_at",
                    "rotation_policy_ref", "secret_id")),
            Map.entry("sessions", Set.of(
                    "absolute_expires_at", "actor_fingerprint", "created_at", "end_reason",
                    "ended_by_actor_fingerprint", "idle_deadline_at", "last_seen_at",
                    "session_id", "state", "superseded_by_session_id")));

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
