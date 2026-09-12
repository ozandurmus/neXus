package com.securityexpert.nexus.ui2.persistence.gates;

import java.util.List;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link GateRegistryDao}. {@link #findByCanonicalKey} never
 * ranks or limits its result -- it returns every matching row, exactly as
 * many as exist, so {@code GateResolver} (capability-registry) is the only
 * place "zero / one / more than one" is interpreted (C4 §3.3).
 */
public final class JooqGateRegistryDao implements GateRegistryDao {

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqGateRegistryDao(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = transactionBoundary;
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public List<GateRowData> findByCanonicalKey(String vendor, String platformRoleScope, String shellContext,
            String transportKind, String canonicalCommandKey) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select * from gate_registry where vendor = {0} and platform_role_scope = {1} "
                        + "and shell_context = {2} and transport_kind = {3} and canonical_command_key = {4}",
                vendor, platformRoleScope, shellContext, transportKind, canonicalCommandKey)
                .stream().map(JooqGateRegistryDao::toRow).toList());
    }

    @Override
    public void upsert(GateRowData row) {
        String telemetryJson = toJsonArray(row.safeTelemetryFields());
        auditedTransactionBoundary.inTransaction("system:worker", "gate_registry_seed", dsl -> dsl.execute(
                "insert into gate_registry(gate_id, vendor, platform_role_scope, shell_context, transport_kind, "
                        + "canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, "
                        + "max_frequency, session_reuse_rule, unsupported_behavior_ref, secret_output_risk, "
                        + "safe_telemetry_fields, source_document_pointer) "
                        + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, {11}, {12}, {13}, "
                        + "{14}::jsonb, {15}) "
                        + "on conflict (gate_id) do update set vendor = excluded.vendor, "
                        + "platform_role_scope = excluded.platform_role_scope, "
                        + "shell_context = excluded.shell_context, transport_kind = excluded.transport_kind, "
                        + "canonical_command_key = excluded.canonical_command_key, "
                        + "action_class = excluded.action_class, sign_off_state = excluded.sign_off_state, "
                        + "timeout_s = excluded.timeout_s, retry_rule = excluded.retry_rule, "
                        + "max_frequency = excluded.max_frequency, session_reuse_rule = excluded.session_reuse_rule, "
                        + "unsupported_behavior_ref = excluded.unsupported_behavior_ref, "
                        + "secret_output_risk = excluded.secret_output_risk, "
                        + "safe_telemetry_fields = excluded.safe_telemetry_fields, "
                        + "source_document_pointer = excluded.source_document_pointer",
                row.gateId(), row.vendor(), row.platformRoleScope(), row.shellContext(), row.transportKind(),
                row.canonicalCommandKey(), row.actionClass(), row.signOffState(), row.timeoutS(), row.retryRule(),
                row.maxFrequency(), row.sessionReuseRule(), row.unsupportedBehaviorRef(), row.secretOutputRisk(),
                telemetryJson, row.sourceDocumentPointer()));
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private static GateRowData toRow(Record row) {
        return new GateRowData(
                row.get("gate_id", String.class),
                row.get("vendor", String.class),
                row.get("platform_role_scope", String.class),
                row.get("shell_context", String.class),
                row.get("transport_kind", String.class),
                row.get("canonical_command_key", String.class),
                row.get("action_class", String.class),
                row.get("sign_off_state", String.class),
                row.get("timeout_s", Integer.class),
                row.get("retry_rule", String.class),
                row.get("max_frequency", String.class),
                row.get("session_reuse_rule", String.class),
                row.get("unsupported_behavior_ref", String.class),
                row.get("secret_output_risk", String.class),
                List.of(),
                row.get("source_document_pointer", String.class));
    }
}
