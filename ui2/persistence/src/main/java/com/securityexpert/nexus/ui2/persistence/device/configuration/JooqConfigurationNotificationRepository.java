package com.securityexpert.nexus.ui2.persistence.device.configuration;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jooq.Record;
import org.jooq.Result;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** jOOQ-backed {@link ConfigurationNotificationRepository} (migration V16, 14G CG-7d). */
public final class JooqConfigurationNotificationRepository implements ConfigurationNotificationRepository {

    private static final String PATH_DELIMITER = "\n";

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqConfigurationNotificationRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public void record(String deviceId, String runId, String summary, List<String> overridePaths,
            String actorFingerprint, String actionId) {
        auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            dsl.execute("insert into configuration_notification(notification_id, device_id, run_id, kind, summary, "
                    + "override_paths) values ({0}, {1}, {2}, {3}, {4}, {5})",
                    UUID.randomUUID().toString(), deviceId, runId,
                    ConfigurationNotification.KIND_CONFIGURATION_OVERRIDE_DETECTED, summary,
                    String.join(PATH_DELIMITER, overridePaths));
            return null;
        });
    }

    @Override
    public List<ConfigurationNotification> listRecent(int limit) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select notification_id, device_id, run_id, kind, summary, "
                    + "override_paths, created_at, read_at from configuration_notification "
                    + "order by created_at desc limit {0}", limit);
            List<ConfigurationNotification> result = new ArrayList<>();
            for (Record row : rows) {
                String paths = row.get("override_paths", String.class);
                List<String> overridePaths = paths == null || paths.isBlank() ? List.of()
                        : List.of(paths.split(PATH_DELIMITER));
                result.add(new ConfigurationNotification(row.get("notification_id", String.class),
                        row.get("device_id", String.class), row.get("run_id", String.class),
                        row.get("kind", String.class), row.get("summary", String.class), overridePaths,
                        row.get("created_at", Timestamp.class).toInstant(),
                        Optional.ofNullable(row.get("read_at", Timestamp.class)).map(Timestamp::toInstant)));
            }
            return List.copyOf(result);
        });
    }
}
