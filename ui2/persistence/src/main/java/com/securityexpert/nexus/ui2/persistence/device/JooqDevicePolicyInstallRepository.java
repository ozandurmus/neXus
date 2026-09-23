package com.securityexpert.nexus.ui2.persistence.device;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqDevicePolicyInstallRepository implements DevicePolicyInstallRepository {

    private static final String COLUMNS = "device_id, policy_name, installed_at_text, installed_at, source_read, observed_at";

    private final TransactionBoundary transactionBoundary;

    public JooqDevicePolicyInstallRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public void record(DevicePolicyInstall install) {
        if (install.isEmpty()) {
            return;
        }
        transactionBoundary.inTransaction(dsl -> dsl.execute(
                "insert into device_policy_install(device_id, policy_name, installed_at_text, installed_at, source_read, observed_at) "
                        + "values ({0}, {1}, {2}, {3}, {4}, now()) on conflict (device_id) do update set "
                        + "policy_name = excluded.policy_name, installed_at_text = excluded.installed_at_text, "
                        + "installed_at = excluded.installed_at, source_read = excluded.source_read, observed_at = now()",
                install.deviceId(), install.policyName().orElse(null), install.installedAtText().orElse(null),
                install.installedAt().map(i -> OffsetDateTime.ofInstant(i, ZoneOffset.UTC)).orElse(null), install.sourceRead()));
    }

    @Override
    public Optional<DevicePolicyInstall> find(String deviceId) {
        return transactionBoundary.inTransaction(dsl -> dsl
                .fetch("select " + COLUMNS + " from device_policy_install where device_id = {0}", deviceId)
                .stream().findFirst().map(JooqDevicePolicyInstallRepository::toInstall));
    }

    @Override
    public Map<String, DevicePolicyInstall> findAll() {
        return transactionBoundary.inTransaction(dsl -> {
            Map<String, DevicePolicyInstall> out = new LinkedHashMap<>();
            for (Record r : dsl.fetch("select " + COLUMNS + " from device_policy_install")) {
                DevicePolicyInstall p = toInstall(r);
                out.put(p.deviceId(), p);
            }
            return out;
        });
    }

    private static DevicePolicyInstall toInstall(Record r) {
        return new DevicePolicyInstall(r.get("device_id", String.class), Optional.ofNullable(r.get("policy_name", String.class)),
                Optional.ofNullable(r.get("installed_at_text", String.class)),
                Optional.ofNullable(r.get("installed_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant),
                r.get("source_read", String.class),
                Optional.ofNullable(r.get("observed_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant));
    }
}
