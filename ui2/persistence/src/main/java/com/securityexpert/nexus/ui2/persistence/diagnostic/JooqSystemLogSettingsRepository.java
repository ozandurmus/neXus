package com.securityexpert.nexus.ui2.persistence.diagnostic;

import java.time.OffsetDateTime;
import java.util.Objects;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository.Settings;

/**
 * The {@code system_log_settings} row over jOOQ. The write is one {@link AuditedTransactionBoundary} transaction under
 * {@link #WRITE_ACTION}, so the V160 audit trigger records the actor and the action with the old and new values; the
 * row is read back inside the same transaction so the answer is what the database stored (its {@code now()},
 * the CHECK bounds having passed).
 */
public final class JooqSystemLogSettingsRepository implements SystemLogSettingsRepository {
    static final String SELECT = "select raw_retention_days, include_management_servers, device_cap_bytes, hcp_retention_days, "
            + "spike_data_retention_days, spike_data_cap_bytes, ledger_retention_days, spike_data_enabled, updated_at, "
            + "updated_by from system_log_settings where settings_id = 'default'";
    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public JooqSystemLogSettingsRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    @Override
    public Row read() {
        return transactions.inTransaction(JooqSystemLogSettingsRepository::select);
    }

    @Override
    public Row write(String actorFingerprint, Settings s) {
        Objects.requireNonNull(s, "settings");
        return audited.inTransaction(actorFingerprint, WRITE_ACTION, dsl -> {
            // V160 seeds the row; the upsert keeps the write correct on a database where the seed was removed.
            dsl.execute("insert into system_log_settings(settings_id, raw_retention_days, device_cap_bytes, hcp_retention_days, "
                            + "spike_data_retention_days, spike_data_cap_bytes, ledger_retention_days, "
                            + "include_management_servers, spike_data_enabled, updated_at, updated_by) "
                            + "values ('default', {0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, now(), {8}) "
                            + "on conflict (settings_id) do update set raw_retention_days = excluded.raw_retention_days, "
                            + "device_cap_bytes = excluded.device_cap_bytes, hcp_retention_days = excluded.hcp_retention_days, "
                            + "spike_data_retention_days = excluded.spike_data_retention_days, "
                            + "spike_data_cap_bytes = excluded.spike_data_cap_bytes, "
                            + "ledger_retention_days = excluded.ledger_retention_days, "
                            + "include_management_servers = excluded.include_management_servers, "
                            + "spike_data_enabled = excluded.spike_data_enabled, updated_at = now(), "
                            + "updated_by = excluded.updated_by",
                    s.rawRetentionDays(), s.deviceCapBytes(), s.hcpRetentionDays(), s.spikeDataRetentionDays(),
                    s.spikeDataCapBytes(), s.ledgerRetentionDays(), s.includeManagementServers(), s.spikeDataEnabled(),
                    actorFingerprint);
            return select(dsl);
        });
    }

    private static Row select(DSLContext dsl) {
        return dsl.fetchOptional(SELECT).map(JooqSystemLogSettingsRepository::row)
                .orElse(new Row(Settings.DEFAULTS, null, null));
    }

    private static Row row(Record r) {
        var settings = new Settings(r.get("raw_retention_days", Integer.class),
                Boolean.TRUE.equals(r.get("include_management_servers", Boolean.class)),
                r.get("device_cap_bytes", Long.class), r.get("hcp_retention_days", Integer.class),
                r.get("spike_data_retention_days", Integer.class), r.get("spike_data_cap_bytes", Long.class),
                r.get("ledger_retention_days", Integer.class),
                Boolean.TRUE.equals(r.get("spike_data_enabled", Boolean.class)));
        OffsetDateTime updatedAt = r.get("updated_at", OffsetDateTime.class);
        return new Row(settings, updatedAt == null ? null : updatedAt.toInstant(), r.get("updated_by", String.class));
    }
}
