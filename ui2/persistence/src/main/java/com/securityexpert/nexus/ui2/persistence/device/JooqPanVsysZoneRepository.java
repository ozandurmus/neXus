package com.securityexpert.nexus.ui2.persistence.device;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqPanVsysZoneRepository implements PanVsysZoneRepository {

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary audited;

    public JooqPanVsysZoneRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.audited = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public int replace(String deviceId, Instant readAt, List<PanVsysZoneSet> sets, String actorFingerprint, String actionId) {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(readAt, "readAt");
        Set<String> vsysNames = new LinkedHashSet<>();
        for (PanVsysZoneSet set : sets) {
            if (!set.deviceId().equals(deviceId) || !vsysNames.add(set.vsys())) {
                throw new IllegalArgumentException("one set per virtual system of this device");
            }
        }
        OffsetDateTime at = OffsetDateTime.ofInstant(readAt, ZoneOffset.UTC);
        return audited.inTransaction(actorFingerprint, actionId, db -> {
            int changed = 0;
            Map<String, Record> stored = new LinkedHashMap<>();
            // A device that is not enrolled (any more) has no stored sets; its rows go with it (ON DELETE CASCADE).
            if (db.fetch("select 1 from devices where device_id = {0}", deviceId).isEmpty()) {
                return 0;
            }
            for (Record row : db.fetch("select vsys, zone_names, read_at from pan_vsys_zone_set where device_id = {0} for update", deviceId)) {
                stored.put(row.get("vsys", String.class), row);
            }
            for (var entry : stored.entrySet()) {
                OffsetDateTime storedAt = entry.getValue().get("read_at", OffsetDateTime.class);
                if (storedAt != null && storedAt.toInstant().isAfter(readAt)) {
                    return 0; // a newer read is already stored: this one is older, keep it
                }
            }
            for (var entry : stored.entrySet()) {
                if (!vsysNames.contains(entry.getKey())) {
                    changed += db.execute("delete from pan_vsys_zone_set where device_id = {0} and vsys = {1}", deviceId, entry.getKey());
                }
            }
            for (PanVsysZoneSet set : sets) {
                Record row = stored.get(set.vsys());
                String[] names = set.zones().toArray(String[]::new);
                if (row == null) {
                    changed += db.execute("insert into pan_vsys_zone_set(device_id, vsys, zone_names, read_at) values ({0}, {1}, {2}::text[], {3})",
                            deviceId, set.vsys(), names, at);
                    continue;
                }
                String[] storedNames = row.get("zone_names", String[].class);
                OffsetDateTime storedAt = row.get("read_at", OffsetDateTime.class);
                boolean same = java.util.Arrays.equals(storedNames, names) && storedAt != null && storedAt.toInstant().equals(readAt);
                if (!same) {
                    changed += db.execute("update pan_vsys_zone_set set zone_names = {2}::text[], read_at = {3} where device_id = {0} and vsys = {1}",
                            deviceId, set.vsys(), names, at);
                }
            }
            return changed;
        });
    }

    @Override
    public Map<String, Map<String, PanVsysZoneSet>> findForDevices(Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = deviceIds.stream().filter(Objects::nonNull).distinct().toList();
        return transactionBoundary.inTransaction(db -> {
            Map<String, Map<String, PanVsysZoneSet>> out = new LinkedHashMap<>();
            for (Record row : db.fetch("select device_id, vsys, zone_names, read_at from pan_vsys_zone_set "
                    + "where device_id = any({0}::text[]) order by device_id, vsys", (Object) ids.toArray(String[]::new))) {
                String deviceId = row.get("device_id", String.class);
                String[] names = row.get("zone_names", String[].class);
                out.computeIfAbsent(deviceId, k -> new LinkedHashMap<>()).put(row.get("vsys", String.class),
                        new PanVsysZoneSet(deviceId, row.get("vsys", String.class), new java.util.HashSet<>(java.util.Arrays.asList(names == null ? new String[0] : names)),
                                row.get("read_at", OffsetDateTime.class).toInstant()));
            }
            return out;
        });
    }
}
