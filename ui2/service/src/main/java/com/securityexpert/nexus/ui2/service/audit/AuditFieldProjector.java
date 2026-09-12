package com.securityexpert.nexus.ui2.service.audit;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The server-side field projection of {@code UI2_0_B1_08_AUDIT_LOGS_SCREEN_
 * CONTRACT.md} §3.2/§3.3, over one {@code before_state}/{@code after_state}
 * snapshot of one audited table.
 *
 * <p>No caller of this class may reach a {@code sha256:} digest through it:
 * {@link #project} never copies a redacted column's raw JSON value into its
 * result, only the policy's {@code tier}/{@code reason}
 * ({@link AuditFieldProjection#redacted}); {@link #compareChangeStates}
 * reports only {@link AuditChangeState}, never the compared values. This is
 * the whole point of §3.2's decision and is proved, not merely asserted, by
 * the projector holding no field or method that returns a redacted value.</p>
 */
public final class AuditFieldProjector {

    private final AuditRedactionPolicy policy;

    public AuditFieldProjector(AuditRedactionPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Projects one snapshot. Returns an empty map for a {@code null}
     * snapshot (an {@code INSERT} row's {@code before_state}, or a
     * {@code DELETE} row's {@code after_state}) — there is nothing to
     * project when the row side does not exist.
     *
     * <p>Contract §3.3: a snapshot key outside the union of the committed
     * allowlist ({@link AuditPresentationAllowlist}) and the redaction
     * policy projects as {@link AuditFieldState#UNCLASSIFIED}, and when that
     * happens every key that would otherwise have projected as
     * {@link AuditFieldState#PRESENT} is withheld and replaced by its own
     * {@code UNCLASSIFIED} marker naming {@code tableName} and that key's
     * column name. {@code REDACTED}, {@code NULL} and {@code ABSENT} keys
     * are unaffected by the refusal.</p>
     */
    public Map<String, AuditFieldProjection> project(String tableName, JsonNode rawSnapshot) {
        if (rawSnapshot == null || rawSnapshot.isNull()) {
            return Map.of();
        }

        Set<String> schemaColumns = schemaColumns(tableName);
        Map<String, AuditFieldProjection> result = new LinkedHashMap<>();
        boolean anyUnclassified = false;

        for (String column : schemaColumns) {
            if (!rawSnapshot.has(column)) {
                result.put(column, AuditFieldProjection.absent());
                continue;
            }
            JsonNode value = rawSnapshot.get(column);
            if (policy.isRedacted(tableName, column)) {
                if (value == null || value.isNull()) {
                    result.put(column, AuditFieldProjection.ofNull());
                } else {
                    AuditRedactionPolicyEntry entry = policy.entryFor(tableName, column)
                            .orElseThrow(() -> new IllegalStateException(
                                    "redacted column reported without a policy entry: "
                                            + tableName + "." + column));
                    result.put(column, AuditFieldProjection.redacted(entry.tier(), entry.reason()));
                }
            } else if (AuditPresentationAllowlist.isPresentInFull(tableName, column)) {
                result.put(column, (value == null || value.isNull())
                        ? AuditFieldProjection.ofNull()
                        : AuditFieldProjection.present(value));
            }
            // A schema column that is in neither set cannot occur: schemaColumns
            // is exactly the union of the allowlist and the policy for this table.
        }

        Iterator<String> fieldNames = rawSnapshot.fieldNames();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            if (!schemaColumns.contains(key)) {
                result.put(key, AuditFieldProjection.unclassified(tableName, key));
                anyUnclassified = true;
            }
        }

        if (anyUnclassified) {
            for (Map.Entry<String, AuditFieldProjection> entry : result.entrySet()) {
                if (entry.getValue().state() == AuditFieldState.PRESENT) {
                    entry.setValue(AuditFieldProjection.unclassified(tableName, entry.getKey()));
                }
            }
        }

        return Map.copyOf(result);
    }

    /**
     * Contract §3.2: for an {@code UPDATE} row, the per-key
     * {@code CHANGED}/{@code UNCHANGED}/{@code NOT_EVALUABLE} comparison,
     * computed by comparing the raw (already-redacted-at-storage) before/
     * after JSON values of each schema column. For a redacted column this
     * compares the two {@code sha256:} digests as opaque strings — the
     * exact property {@code B1-2a} §2 calls "value changed between two audit
     * rows" — without this method ever returning either digest.
     *
     * <p>{@code NOT_EVALUABLE} is reported whenever either side is absent
     * (a {@code null} snapshot, or a key missing from one side), never
     * defaulted to {@code UNCHANGED}.</p>
     */
    public Map<String, AuditChangeState> compareChangeStates(String tableName, JsonNode before, JsonNode after) {
        Set<String> schemaColumns = schemaColumns(tableName);
        Map<String, AuditChangeState> result = new LinkedHashMap<>();

        for (String column : schemaColumns) {
            boolean beforeHas = before != null && !before.isNull() && before.has(column);
            boolean afterHas = after != null && !after.isNull() && after.has(column);
            if (!beforeHas || !afterHas) {
                result.put(column, AuditChangeState.NOT_EVALUABLE);
                continue;
            }
            JsonNode beforeValue = before.get(column);
            JsonNode afterValue = after.get(column);
            result.put(column, Objects.equals(beforeValue, afterValue)
                    ? AuditChangeState.UNCHANGED
                    : AuditChangeState.CHANGED);
        }
        return Map.copyOf(result);
    }

    /** The union of the allowlist's and the policy's columns for one table. */
    private Set<String> schemaColumns(String tableName) {
        Set<String> columns = new TreeSet<>(AuditPresentationAllowlist.columnsOf(tableName));
        columns.addAll(policy.columnsFor(tableName));
        return columns;
    }
}
