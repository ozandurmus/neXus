package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.BackupRow;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.DeviationRow;

/**
 * Backup coverage over the backup targets ({@code devices.backup_target}) and the three size-alert classes of
 * {@code backup_deviation_record}. Only sizes, ratios and percentages are read from an alert's details; the free-text
 * summary column is never read (it may name the device).
 */
final class BackupFacts {

    static final List<String> CLASSES = List.of("size_anomaly", "fleet_large", "firewall_logs");

    record Target(DeviceSummaryRecord device, BackupRow latest) {
        boolean hasArchive() {
            return latest != null;
        }
    }

    record Alert(DeviationRow row, DeviceSummaryRecord device, String detail) {
        boolean open() {
            return row.resolvedAt() == null;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    final List<Target> targets = new ArrayList<>();
    final List<Alert> alerts = new ArrayList<>();

    BackupFacts(List<DeviceSummaryRecord> devices, List<BackupRow> latest, List<DeviationRow> deviations) {
        Map<String, BackupRow> byDevice = new LinkedHashMap<>();
        for (BackupRow row : latest) byDevice.put(row.deviceId(), row);
        Map<String, DeviceSummaryRecord> deviceById = new LinkedHashMap<>();
        for (DeviceSummaryRecord d : devices) deviceById.put(d.deviceId(), d);
        devices.stream().filter(DeviceSummaryRecord::backupTarget)
                .sorted(java.util.Comparator.comparing(BackupFacts::sortName))
                .forEach(d -> targets.add(new Target(d, byDevice.get(d.deviceId()))));
        for (DeviationRow row : deviations) {
            if (!CLASSES.contains(row.deviationClass())) continue;
            alerts.add(new Alert(row, deviceById.get(row.deviceId()), detail(row)));
        }
    }

    static String sortName(DeviceSummaryRecord d) {
        return d.observedHostname().orElse("") + "\u0000" + d.deviceId();
    }

    int withoutArchive() {
        return (int) targets.stream().filter(t -> !t.hasArchive()).count();
    }

    int staleArchives(Instant now) {
        return (int) targets.stream().filter(t -> t.hasArchive() && Freshness.stale(t.latest().createdAt(), now)).count();
    }

    int openSizeAnomalies() {
        return (int) alerts.stream().filter(a -> "size_anomaly".equals(a.row().deviationClass()) && a.open()).count();
    }

    long count(String deviationClass) {
        return alerts.stream().filter(a -> deviationClass.equals(a.row().deviationClass())).count();
    }

    /** Sizes and ratios only, from the stored details JSON. */
    static String detail(DeviationRow row) {
        JsonNode d;
        try {
            d = row.detailsJson() == null ? null : JSON.readTree(row.detailsJson());
        } catch (Exception e) {
            d = null;
        }
        if (d == null || !d.isObject()) return "UNKNOWN";
        return switch (row.deviationClass()) {
            case "size_anomaly" -> d.path("direction").asText("UNKNOWN") + ": " + bytes(d, "plaintext_bytes")
                    + " against a median of " + bytes(d, "median_bytes") + " (ratio " + d.path("ratio").asText("UNKNOWN") + ")";
            case "fleet_large" -> bytes(d, "plaintext_bytes") + ", " + d.path("ratio").asText("UNKNOWN")
                    + "x the vendor median of " + bytes(d, "median_bytes");
            case "firewall_logs" -> bytes(d, "log_bytes") + " of firewall logs in " + bytes(d, "total_bytes") + " ("
                    + d.path("percent").asText("UNKNOWN") + "%)";
            default -> "UNKNOWN";
        };
    }

    static String bytes(JsonNode d, String field) {
        if (!d.path(field).canConvertToLong()) return "UNKNOWN";
        return bytes(d.path(field).asLong());
    }

    /** Binary units, one decimal: "1.5 GiB". */
    static String bytes(long value) {
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double v = value;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return u == 0 ? value + " B" : (Math.round(v * 10) / 10.0) + " " + units[u];
    }
}
