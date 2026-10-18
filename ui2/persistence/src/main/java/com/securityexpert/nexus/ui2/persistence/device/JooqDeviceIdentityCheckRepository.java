package com.securityexpert.nexus.ui2.persistence.device;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqDeviceIdentityCheckRepository implements DeviceIdentityCheckRepository {

    private static final String SELECT = "select d.device_id, d.identity_mismatch_state, c.transport, c.verdict, c.checked_at "
            + "from devices d left join device_identity_check c on c.device_id = d.device_id";

    private final TransactionBoundary transactionBoundary;

    public JooqDeviceIdentityCheckRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public Optional<DeviceIdentityCheck> find(String deviceId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(SELECT + " where d.device_id = {0}", deviceId)
                .stream().findFirst().map(JooqDeviceIdentityCheckRepository::toCheck));
    }

    @Override
    public Map<String, DeviceIdentityCheck> findAll() {
        return transactionBoundary.inTransaction(dsl -> {
            Map<String, DeviceIdentityCheck> out = new LinkedHashMap<>();
            for (Record r : dsl.fetch(SELECT)) {
                DeviceIdentityCheck c = toCheck(r);
                out.put(c.deviceId(), c);
            }
            return out;
        });
    }

    static DeviceIdentityCheck toCheck(Record r) {
        return new DeviceIdentityCheck(r.get("device_id", String.class),
                Optional.ofNullable(r.get("transport", String.class)),
                Optional.ofNullable(r.get("verdict", String.class)),
                Optional.ofNullable(r.get("checked_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant),
                "OPEN".equals(r.get("identity_mismatch_state", String.class)));
    }
}
