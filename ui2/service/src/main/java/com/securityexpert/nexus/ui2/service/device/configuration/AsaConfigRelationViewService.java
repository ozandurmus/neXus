package com.securityexpert.nexus.ui2.service.device.configuration;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.securityexpert.nexus.ui2.persistence.device.AsaConfigRelationReader;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;

/**
 * The Backups / Devices "Saved config differs" read: the stored saved-vs-running relation of each Cisco ASA's newest
 * backup (V250, V253). Read-only over derived counts; no device command, no configuration text. The hostname sits next
 * to the device id so the replay viewer gets the Devices screen's own pseudonym.
 */
public final class AsaConfigRelationViewService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;

    private final Supplier<List<AsaConfigRelationReader.Latest>> source;
    private final Supplier<List<DeviceSummaryRecord>> devices;

    public AsaConfigRelationViewService(Supplier<List<AsaConfigRelationReader.Latest>> source,
            Supplier<List<DeviceSummaryRecord>> devices) {
        this.source = Objects.requireNonNull(source, "source");
        this.devices = Objects.requireNonNull(devices, "devices");
    }

    /** One body row per device with a relation; MISMATCH rows first. */
    public List<Map<String, Object>> rows() {
        List<AsaConfigRelationReader.Latest> latest = source.get();
        if (latest.isEmpty()) {
            return List.of();
        }
        Map<String, DeviceSummaryRecord> inventory = new java.util.HashMap<>();
        for (DeviceSummaryRecord d : devices.get()) {
            inventory.put(d.deviceId(), d);
        }
        List<AsaConfigRelationReader.Latest> sorted = new ArrayList<>(latest);
        sorted.sort(Comparator.comparing((AsaConfigRelationReader.Latest l) -> !"MISMATCH".equals(l.relation()))
                .thenComparing(AsaConfigRelationReader.Latest::deviceId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AsaConfigRelationReader.Latest l : sorted) {
            DeviceSummaryRecord d = inventory.get(l.deviceId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("device_id", l.deviceId());
            item.put("hostname", d == null ? null : d.observedHostname().orElse(null));
            item.put("cluster_member_ref", d == null ? null : d.clusterMemberRef().orElse(null));
            item.put("relation", l.relation());
            item.put("lines_differing", l.linesDiffering());
            item.put("differing_keywords", new java.util.TreeMap<>(l.differingKeywords()));
            item.put("backup_created_at", l.backupCreatedAt() == null ? null : TIMESTAMP.format(l.backupCreatedAt()));
            item.put("derived_at", l.derivedAt() == null ? null : TIMESTAMP.format(l.derivedAt()));
            out.add(item);
        }
        return out;
    }
}
