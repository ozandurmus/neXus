package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    @Override
    public Map<String, DeviceDiscoveryMatch> findByDiscoveryMatchKeys(Collection<String> discoveryMatchKeys) {
        List<String> keys = List.copyOf(new LinkedHashSet<>(discoveryMatchKeys));
        if (keys.isEmpty()) {
            return Map.of();
        }
        String placeholders = IntStream.range(0, keys.size()).mapToObj(i -> "{" + i + "}")
                .collect(Collectors.joining(", "));
        return transactionBoundary.inTransaction(dsl -> {
            Map<String, DeviceDiscoveryMatch> byKey = new LinkedHashMap<>();
            dsl.fetch("select d.discovery_match_key, d.device_id, d.cluster_member_ref, d.virtual_system_ref, "
                    + "e.address_ref from devices d join endpoints e on e.device_id = d.device_id "
                    + "where d.discovery_match_key in (" + placeholders + ")", (Object[]) keys.toArray(new String[0]))
                    .forEach(row -> byKey.put(row.get("discovery_match_key", String.class), toMatch(row)));
            return byKey;
        });
    }

    private static DeviceDiscoveryMatch toMatch(Record row) {
        return new DeviceDiscoveryMatch(
                row.get("device_id", String.class),
                row.get("address_ref", String.class),
                Optional.ofNullable(row.get("cluster_member_ref", String.class)),
                Optional.ofNullable(row.get("virtual_system_ref", String.class)));
    }
}
