package com.securityexpert.nexus.ui2.service.report;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LivePatchItem;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.Read;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogRepository.StoredInventory;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.BackupRow;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.ComplianceRow;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.DeviationRow;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleProjection;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleService;
import com.securityexpert.nexus.ui2.service.privacy.ReportProjectionMasker;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * Synthetic estate for the report tests. Raw names are obviously synthetic ({@code synthetic-raw-...}) and addresses
 * come from the documentation ranges, so a masking test can prove none of them reaches a MASKED file.
 */
final class ReportFixtures {

    static final Instant NOW = Instant.parse("2026-11-05T09:00:00Z");
    static final Instant FRESH = NOW.minus(Duration.ofHours(3));
    static final Instant STALE = NOW.minus(Duration.ofHours(30));

    static final String HOST_A = "synthetic-raw-gw-alpha-01";
    static final String HOST_B = "synthetic-raw-gw-alpha-02";
    static final String HOST_C = "synthetic-raw-edge-bravo";
    static final String HOST_D = "synthetic-raw-edge-charlie";
    static final String HOST_E = "synthetic-raw-mgmt-delta";
    static final String CLUSTER_1 = "synthetic-raw-cluster-alpha";
    static final String CLUSTER_2 = "synthetic-raw-cluster-echo";
    static final String IP_1 = "198.51.100.17";
    static final String IP_2 = "198.51.100.42";
    static final String SERIAL = "SYNTHSERIAL0042";

    /** Every raw value a MASKED file must not contain. */
    static final List<String> RAW_VALUES = List.of(HOST_A, HOST_B, HOST_C, HOST_D, HOST_E, CLUSTER_1, CLUSTER_2, IP_1, IP_2,
            SERIAL, "synthetic-raw");

    static DeviceSummaryRecord device(String id, String host, String vendor, String role, String cluster, boolean target,
            String ip) {
        return new DeviceSummaryRecord(id, role, vendor, DeviceEnrollmentState.ENROLLED, Optional.ofNullable(host),
                Optional.of("synthetic-model"), Optional.of("R81.20"), Optional.empty(), Optional.ofNullable(cluster),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(ip),
                Optional.ofNullable(ip), target, false);
    }

    static List<DeviceSummaryRecord> devices() {
        return List.of(
                device("dev-a", HOST_A, "check_point", "gateway", CLUSTER_1, true, IP_1),
                device("dev-b", HOST_B, "check_point", "gateway", CLUSTER_1, true, IP_2),
                device("dev-c", HOST_C, "palo_alto", "firewall", null, true, null),
                device("dev-d", HOST_D, "fortinet", "firewall", CLUSTER_2, false, null),
                device("dev-e", HOST_E, "check_point", "management_server", null, false, null));
    }

    static String item(String control, String title, String severity, String status, String framework, String ref) {
        return "{\"controlId\":\"" + control + "\",\"title\":\"" + title + "\",\"severity\":\"" + severity
                + "\",\"displayStatus\":\"" + status + "\",\"frameworks\":[{\"framework\":\"" + framework
                + "\",\"reference\":\"" + ref + "\"}]}";
    }

    static String evaluation(String... items) {
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    /** A synthetic in-memory {@link ReportData}; every list is mutable so a test can shape it. */
    static final class Data implements ReportData {
        List<DeviceSummaryRecord> devices = new ArrayList<>(ReportFixtures.devices());
        List<ComplianceRow> compliance = new ArrayList<>(List.of(
                new ComplianceRow("dev-a", FRESH, evaluation(
                        item("CP-1", "Syslog server " + IP_1 + " on " + HOST_A, "CRITICAL", "FAIL", "CIS", "1.2.3"),
                        item("CP-2", "SSH timeout", "LOW", "PASS", "NIST_800_53", "AC-12"),
                        item("CP-3", "NTP", "MEDIUM", "DATA_UNAVAILABLE", "NIST_800_53", "AU-8"),
                        item("CP-4", "Telnet off", "HIGH", "NOT_APPLICABLE", "CIS", "2.1"))),
                new ComplianceRow("dev-b", FRESH, evaluation(
                        item("CP-1", "Syslog server " + IP_1 + " on " + HOST_A, "CRITICAL", "PASS", "CIS", "1.2.3"),
                        item("CP-2", "SSH timeout", "LOW", "FAIL", "NIST_800_53", "AC-12"))),
                new ComplianceRow("dev-e", FRESH, evaluation(item("CP-9", "Mgmt", "LOW", "FAIL", "CIS", "9.9")))));
        Map<String, Instant> inventory = new LinkedHashMap<>(Map.of("dev-a", FRESH, "dev-b", FRESH, "dev-c", STALE,
                "dev-e", FRESH));
        Map<String, Instant> configuration = new LinkedHashMap<>(Map.of("dev-a", FRESH, "dev-b", STALE));
        List<BackupRow> backups = new ArrayList<>(List.of(
                new BackupRow("dev-a", "art-a", FRESH, 50L * 1024 * 1024, "check_point", 7),
                new BackupRow("dev-b", "art-b", STALE, 3L * 1024 * 1024 * 1024, "check_point", 2)));
        List<DeviationRow> deviations = new ArrayList<>(List.of(
                new DeviationRow("dv-1", "dev-b", "art-b", "size_anomaly", NOW.minus(Duration.ofDays(2)), null,
                        "{\"direction\":\"growth\",\"plaintext_bytes\":3221225472,\"median_bytes\":52428800,\"ratio\":61.4}"),
                new DeviationRow("dv-2", "dev-b", "art-b", "fleet_large", NOW.minus(Duration.ofDays(2)), null,
                        "{\"plaintext_bytes\":3221225472,\"median_bytes\":52428800,\"ratio\":61.4}"),
                new DeviationRow("dv-3", "dev-b", "art-b", "firewall_logs", NOW.minus(Duration.ofDays(2)), null,
                        "{\"log_bytes\":2147483648,\"total_bytes\":3221225472,\"percent\":66.7}")));
        List<HaUnit> ha = new ArrayList<>(List.of(
                new HaUnit(CLUSTER_1, "check_point", null, false, "READY", FRESH, null, null)));
        LifecycleService.FleetSnapshot lifecycle = defaultLifecycle(devices);

        @Override
        public List<DeviceSummaryRecord> devices() {
            return devices;
        }

        @Override
        public List<ComplianceRow> complianceEvaluations() {
            return compliance;
        }

        @Override
        public Map<String, Instant> newestInventory() {
            return inventory;
        }

        @Override
        public Map<String, Instant> newestConfiguration() {
            return configuration;
        }

        @Override
        public List<BackupRow> latestBackups(Instant from, Instant to) {
            return backups;
        }

        @Override
        public List<DeviationRow> sizeDeviations(Instant from, Instant to) {
            return deviations;
        }

        @Override
        public List<HaUnit> haUnits(String actor) {
            return ha;
        }

        @Override
        public LifecycleService.FleetSnapshot lifecycleSnapshot(LocalDate today, Instant now) {
            return lifecycle;
        }
    }

    static LifecycleService.FleetSnapshot defaultLifecycle(List<DeviceSummaryRecord> devices) {
        LocalDate today = NOW.atZone(JobWindowPolicy.ZONE_ID).toLocalDate();
        var readsA = List.of(
                new Read("dev-a", "cp_cplic_print", "COLLECTED", "OK", 2, FRESH),
                new Read("dev-a", "cp_cplp_list", "COLLECTED", "OK", 2, FRESH));
        var itemsA = List.of(
                new LicenseItem("cp_cplic_print", "LICENSE", "CPSB-IPS", "EXPIRED", today.minusDays(4), null, null,
                        "DIRECT_DEVICE", FRESH),
                new LicenseItem("cp_cplic_print", "SUBSCRIPTION", "CPSB-URLF", "VALID", today.plusDays(15), null, null,
                        "DIRECT_DEVICE", FRESH));
        var patchesA = List.of(
                new LivePatchItem("LP-1", "armed", "norm", 2, 2, null, List.of("CVE-2026-0001"), FRESH),
                new LivePatchItem("LP-2", "ready", "norm", 2, 2, null, List.of(), FRESH));
        var pfA = new DevicePlatformFacts("dev-a", Optional.empty(), Optional.of("R81.20 Jumbo Take 119"),
                Optional.empty(), Map.of("live_patch_package_take", "42"), Optional.empty(), "cp", Optional.of(FRESH));

        var readsB = List.of(new Read("dev-b", "cp_cplic_print", "COLLECTED", "OK", 1, STALE));
        var itemsB = List.of(new LicenseItem("cp_cplic_print", "SUPPORT_CONTRACT", "CPES-SS-PREMIUM", "VALID",
                today.plusDays(60), null, null, "DIRECT_DEVICE", STALE));
        var readsC = List.of(new Read("dev-c", "pan_license_info", "FAILED", "TIMEOUT", 0, STALE));

        Map<String, List<Read>> readsByDevice = Map.of("dev-a", readsA, "dev-b", readsB, "dev-c", readsC);
        Map<String, List<LicenseItem>> itemsByDevice = Map.of("dev-a", itemsA, "dev-b", itemsB);
        Map<String, List<LivePatchItem>> patchesByDevice = Map.of("dev-a", patchesA);
        Map<String, DevicePlatformFacts> factsByDevice = Map.of("dev-a", pfA);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DeviceSummaryRecord d : devices) {
            var row = LifecycleProjection.device(d, List.of(),
                    new StoredInventory(d.observedModel().orElse(null), d.observedSoftwareVersion().orElse(null), null),
                    today, new LifecycleProjection.Stored(readsByDevice.get(d.deviceId()), itemsByDevice.get(d.deviceId()),
                            patchesByDevice.get(d.deviceId()), factsByDevice.get(d.deviceId())));
            if ("dev-a".equals(d.deviceId())) {
                row.put("vulnerabilities", Map.of("state", "EVALUATED", "affected", 2, "affected_exploited", 1,
                        "affected_critical_high", 2, "affected_critical", 1, "affected_high", 1,
                        "compared", Map.of("source", "checkpoint", "advisories", 120,
                                "loaded_at", FRESH.toString(), "age_days", 0L)));
            } else if ("dev-b".equals(d.deviceId())) {
                row.put("vulnerabilities", Map.of("state", "EVALUATED", "affected", 1, "affected_exploited", 0,
                        "affected_critical_high", 1, "affected_critical", 0, "affected_high", 1,
                        "compared", Map.of("source", "checkpoint", "advisories", 120,
                                "loaded_at", FRESH.toString(), "age_days", 0L)));
            } else {
                row.put("vulnerabilities", Map.of("state", "NOT_EVALUATED", "reason", "SOURCE_STALE",
                        "affected", 0, "affected_exploited", 0, "affected_critical_high", 0));
            }
            rows.add(row);
        }
        return new LifecycleService.FleetSnapshot(rows, readsByDevice, factsByDevice);
    }

    static ReportBuilder.Context context(ReportTemplate template) {
        return new ReportBuilder.Context(template, ReportParameters.parse(template, null, null, null, NOW), NOW,
                "synthetic-actor");
    }

    static ReportDocumentMasker masker() {
        return new ReportDocumentMasker(new ReportProjectionMasker(new TopologyNamePseudonymizer(new byte[32]),
                new SubnetPreservingIpMasker(new byte[32])));
    }

    /** All cell texts of one table, row by row. */
    static List<List<String>> rows(ReportDocument doc, String tableId) {
        for (ReportDocument.Section s : doc.sections()) {
            for (ReportDocument.Table t : s.tables()) {
                if (t.id().equals(tableId)) return t.rows().stream().map(r -> r.stream().map(ReportDocument.Cell::text).toList()).toList();
            }
        }
        throw new AssertionError("no table " + tableId);
    }

    static Map<String, String> kpis(ReportDocument doc, String sectionId) {
        Map<String, String> out = new LinkedHashMap<>();
        doc.sections().stream().filter(s -> s.id().equals(sectionId)).findFirst().orElseThrow()
                .kpis().forEach(k -> out.put(k.label(), k.value()));
        return out;
    }

    private ReportFixtures() {
    }
}
