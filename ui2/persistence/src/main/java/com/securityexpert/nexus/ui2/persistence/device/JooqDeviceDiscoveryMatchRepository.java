package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** jOOQ-backed {@link DeviceDiscoveryMatchRepository}. */
public final class JooqDeviceDiscoveryMatchRepository implements DeviceDiscoveryMatchRepository {

    private final TransactionBoundary transactionBoundary;

    public JooqDeviceDiscoveryMatchRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = transactionBoundary;
    }

    @Override
    public Optional<DeviceDiscoveryMatch> findByDiscoveryMatchKey(String discoveryMatchKey) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select d.device_id, d.cluster_member_ref, d.virtual_system_ref, e.address_ref "
                        + "from devices d join endpoints e on e.device_id = d.device_id "
                        + "where d.discovery_match_key = {0}", discoveryMatchKey)
                .stream().findFirst().map(JooqDeviceDiscoveryMatchRepository::toMatch));
    }

    private static DeviceDiscoveryMatch toMatch(Record row) {
        return new DeviceDiscoveryMatch(
                row.get("device_id", String.class),
                row.get("address_ref", String.class),
                Optional.ofNullable(row.get("cluster_member_ref", String.class)),
                Optional.ofNullable(row.get("virtual_system_ref", String.class)));
    }
}
