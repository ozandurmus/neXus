package com.securityexpert.nexus.ui2.service.system;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One measured volume. {@code usedPercent} follows {@code df}: used / (used + usable), rounded up, so the figure an
 * operator sees on the host is the figure that is compared with the thresholds. {@code null} figures mean the volume
 * could not be measured (level UNKNOWN).
 */
public record DiskVolumeReading(String name, Long totalBytes, Long usableBytes, Integer usedPercent, DiskLevel level) {

    public static DiskVolumeReading measured(String name, long totalBytes, long freeBytes, long usableBytes,
            DiskHeadroomPolicy policy) {
        long used = totalBytes - freeBytes;
        long denominator = used + usableBytes;
        if (totalBytes <= 0 || used < 0 || usableBytes < 0 || denominator <= 0) {
            return unknown(name);
        }
        int percent = (int) Math.min(100, (used * 100 + denominator - 1) / denominator);
        return new DiskVolumeReading(name, totalBytes, usableBytes, percent, policy.classify(percent));
    }

    public static DiskVolumeReading unknown(String name) {
        return new DiskVolumeReading(name, null, null, null, DiskLevel.UNKNOWN);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("total_bytes", totalBytes);
        row.put("usable_bytes", usableBytes);
        row.put("used_percent", usedPercent);
        row.put("level", level.token());
        return row;
    }
}
