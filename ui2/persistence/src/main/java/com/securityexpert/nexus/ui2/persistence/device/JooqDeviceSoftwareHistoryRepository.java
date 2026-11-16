package com.securityexpert.nexus.ui2.persistence.device;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqDeviceSoftwareHistoryRepository implements DeviceSoftwareHistoryRepository {

    private static final String SELECT = "select h.device_id, d.observed_hostname, h.vendor, h.software_version, "
            + "h.hotfix_level, h.first_seen_at, h.last_seen_at, h.previous_software_version, h.previous_hotfix_level, "
            + "h.previous_last_seen_at, h.source from device_software_version_history h "
            + "left join devices d on d.device_id = h.device_id ";

    private final TransactionBoundary transactionBoundary;

    public JooqDeviceSoftwareHistoryRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public List<Stretch> forDevice(String deviceId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(SELECT + "where h.device_id = {0} "
                + "order by h.first_seen_at desc, h.history_id desc", deviceId).stream().map(JooqDeviceSoftwareHistoryRepository::toStretch).toList());
    }

    @Override
    public List<Stretch> changesSince(Instant since, int limit) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(SELECT + "where h.first_seen_at >= {0} "
                + "and (h.previous_software_version is not null or h.previous_hotfix_level is not null) "
                + "order by h.first_seen_at desc, h.history_id desc limit {1}", Timestamp.from(since), Math.max(1, limit))
                .stream().map(JooqDeviceSoftwareHistoryRepository::toStretch).toList());
    }

    private static Stretch toStretch(Record row) {
        return new Stretch(row.get("device_id", String.class),
                Optional.ofNullable(row.get("observed_hostname", String.class)),
                Optional.ofNullable(row.get("vendor", String.class)),
                row.get("software_version", String.class),
                Optional.ofNullable(row.get("hotfix_level", String.class)),
                row.get("first_seen_at", Timestamp.class).toInstant(),
                row.get("last_seen_at", Timestamp.class).toInstant(),
                Optional.ofNullable(row.get("previous_software_version", String.class)),
                Optional.ofNullable(row.get("previous_hotfix_level", String.class)),
                Optional.ofNullable(row.get("previous_last_seen_at", Timestamp.class)).map(Timestamp::toInstant),
                row.get("source", String.class));
    }
}
