package com.securityexpert.nexus.ui2.persistence.report;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.jooq.DSLContext;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqRetentionQueries;

/**
 * The stored evidence the R1 reports read (Reports R1 contract "Data sources": the neXus database only, no device
 * command, no new collection). Every read runs in its own read-only transaction with a statement timeout (contract
 * "Execution"). Rows carry opaque device ids, timestamps, counters and stored JSON; names are joined later from the
 * device list and masked before any renderer sees them.
 */
public final class JooqReportReadRepository {

    /** Statement timeout of one report read. */
    public static final int STATEMENT_TIMEOUT_MS = 30_000;

    public record ComplianceRow(String deviceId, Instant evaluatedAt, String resultJson) {
    }

    /** A device's newest live standard backup, and how many live backups it has inside the window. */
    public record BackupRow(String deviceId, String artefactId, Instant createdAt, long plaintextBytes, String vendor,
            int backupsInWindow) {
    }

    /** One {@code backup_deviation_record} row of the three size classes (V180, V189). */
    public record DeviationRow(String deviationId, String deviceId, String artefactId, String deviationClass,
            Instant detectedAt, Instant resolvedAt, String detailsJson) {
    }

    private final TransactionBoundary tx;

    public JooqReportReadRepository(TransactionBoundary tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    /** Read-only, bounded by {@link #STATEMENT_TIMEOUT_MS}; both settings are the transaction's first statements. */
    <T> T readOnly(Function<DSLContext, T> work) {
        return tx.inTransaction(dsl -> {
            dsl.execute("set transaction read only");
            dsl.execute("set local statement_timeout = " + STATEMENT_TIMEOUT_MS);
            return work.apply(dsl);
        });
    }

    public List<ComplianceRow> complianceEvaluations() {
        return readOnly(dsl -> dsl.fetch("select device_id, evaluated_at, result::text as result from compliance_evaluation "
                + "order by device_id")
                .map(r -> new ComplianceRow(r.get("device_id", String.class),
                        r.get("evaluated_at", Timestamp.class).toInstant(), r.get("result", String.class))));
    }

    /** Newest inventory collection per device. */
    public Map<String, Instant> newestInventory() {
        return newestPerDevice("device_inventory_run");
    }

    /** Newest configuration read per device. */
    public Map<String, Instant> newestConfiguration() {
        return newestPerDevice("device_configuration_run");
    }

    private Map<String, Instant> newestPerDevice(String table) {
        if (!"device_inventory_run".equals(table) && !"device_configuration_run".equals(table)) {
            throw new IllegalArgumentException("unknown evidence table");
        }
        return readOnly(dsl -> {
            Map<String, Instant> out = new LinkedHashMap<>();
            dsl.fetch("select device_id, max(collected_at) as newest from " + table + " group by device_id order by device_id")
                    .forEach(r -> {
                        Timestamp t = r.get("newest", Timestamp.class);
                        if (t != null) out.put(r.get("device_id", String.class), t.toInstant());
                    });
            return out;
        });
    }

    /** Per device: the newest live standard backup and the live backups taken in {@code [from, to)}. */
    public List<BackupRow> latestBackups(Instant from, Instant to) {
        return readOnly(dsl -> dsl.fetch("select * from (select a.device_id, a.artefact_id, a.created_at, a.plaintext_bytes, "
                + "a.vendor, count(*) filter (where a.created_at >= {0} and a.created_at < {1}) over (partition by a.device_id) "
                + "as in_window, row_number() over (partition by a.device_id order by a.created_at desc, a.artefact_id desc) "
                + "as rank from backup_artefact a where a.artefact_class = 'backup' "
                + "and coalesce(a.backup_type, 'standard') = 'standard'" + JooqRetentionQueries.andNotRemoved("a")
                + ") b where b.rank = 1 order by b.device_id", Timestamp.from(from), Timestamp.from(to))
                .map(r -> new BackupRow(r.get("device_id", String.class), r.get("artefact_id", String.class),
                        r.get("created_at", Timestamp.class).toInstant(), r.get("plaintext_bytes", Long.class),
                        r.get("vendor", String.class), r.get("in_window", Integer.class))));
    }

    /**
     * The size alerts (classes {@code size_anomaly}, {@code fleet_large}, {@code firewall_logs}) detected in
     * {@code [from, to)}, plus every size anomaly still unresolved whatever its age (it still pins a last good backup).
     */
    public List<DeviationRow> sizeDeviations(Instant from, Instant to) {
        return readOnly(dsl -> dsl.fetch("select deviation_id, device_id, artefact_id, deviation_class, detected_at, "
                + "resolved_at, details::text as details from backup_deviation_record "
                + "where deviation_class in ('size_anomaly', 'fleet_large', 'firewall_logs') "
                + "and ((detected_at >= {0} and detected_at < {1}) or (deviation_class = 'size_anomaly' and resolved_at is null)) "
                + "order by detected_at desc, deviation_id", Timestamp.from(from), Timestamp.from(to))
                .map(r -> new DeviationRow(r.get("deviation_id", String.class), r.get("device_id", String.class),
                        r.get("artefact_id", String.class), r.get("deviation_class", String.class),
                        r.get("detected_at", Timestamp.class).toInstant(),
                        r.get("resolved_at", Timestamp.class) == null ? null : r.get("resolved_at", Timestamp.class).toInstant(),
                        r.get("details", String.class))));
    }
}
