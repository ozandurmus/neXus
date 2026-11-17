package com.securityexpert.nexus.ui2.persistence.device;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqDeviceInstallEventRepository implements DeviceInstallEventRepository {

    private static final String SELECT = "select e.device_id, d.observed_hostname, e.kind, e.product_version, e.jumbo_take, "
            + "e.installed_at_local, e.file_name, e.first_observed_at from device_install_event e "
            + "left join devices d on d.device_id = e.device_id ";

    private final TransactionBoundary transactionBoundary;

    public JooqDeviceInstallEventRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public int record(String deviceId, List<Observed> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        return transactionBoundary.inTransaction(dsl -> {
            int written = 0;
            for (Observed e : events) {
                written += dsl.execute("insert into device_install_event(device_id, kind, product_version, jumbo_take, "
                        + "installed_at_local, file_name, first_observed_at, last_observed_at) "
                        + "values ({0}, {1}, {2}, {3}, {4}, {5}, now(), now()) "
                        + "on conflict (device_id, file_name, installed_at_local) do update set last_observed_at = now(), "
                        + "jumbo_take = coalesce(excluded.jumbo_take, device_install_event.jumbo_take)",
                        deviceId, e.kind().name(), e.productVersion(), e.jumboTake().orElse(null),
                        e.installedAtLocal(), e.fileName());
            }
            return written;
        });
    }

    @Override
    public List<Event> forDevice(String deviceId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(SELECT + "where e.device_id = {0} "
                + "order by e.installed_at_local desc, e.event_id desc", deviceId).stream()
                .map(JooqDeviceInstallEventRepository::toEvent).toList());
    }

    @Override
    public List<Event> installedSince(LocalDateTime sinceLocal, int limit) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(SELECT + "where e.installed_at_local >= {0} "
                + "order by e.installed_at_local desc, e.event_id desc limit {1}", sinceLocal,
                Math.max(1, limit)).stream().map(JooqDeviceInstallEventRepository::toEvent).toList());
    }

    private static Event toEvent(Record row) {
        return new Event(row.get("device_id", String.class),
                Optional.ofNullable(row.get("observed_hostname", String.class)),
                Kind.valueOf(row.get("kind", String.class)),
                row.get("product_version", String.class),
                Optional.ofNullable(row.get("jumbo_take", Integer.class)),
                row.get("installed_at_local", LocalDateTime.class),
                row.get("file_name", String.class),
                row.get("first_observed_at", Timestamp.class).toInstant());
    }
}
