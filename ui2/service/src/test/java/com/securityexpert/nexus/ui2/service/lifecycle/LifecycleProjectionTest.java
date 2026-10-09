package com.securityexpert.nexus.ui2.service.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogEntry;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

class LifecycleProjectionTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static LifecycleCatalogEntry entry(String kind, String product, LocalDate support) {
        return new LifecycleCatalogEntry("synthetic-" + kind, "CHECKPOINT", kind, product, null, support, null,
                "MANUAL", "Synthetic vendor publication", "synthetic-actor", Instant.EPOCH);
    }
    static DeviceSummaryRecord device(String id, String vendor, String cluster) {
        return new DeviceSummaryRecord(id, "gateway", vendor, DeviceEnrollmentState.ENROLLED,
                Optional.of("FW-TANGO-04"), Optional.of("Example Appliance"), Optional.of("R81.20 Jumbo Hotfix Take 99"),
                Optional.empty(), Optional.ofNullable(cluster));
    }

    private static Map<String, Object> project(DeviceSummaryRecord d, List<LifecycleCatalogEntry> entries, String licenses, LocalDate today) {
        return LifecycleProjection.device(d, entries,
                new com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogRepository.StoredInventory(
                        d.observedModel().orElse(null), d.observedSoftwareVersion().orElse(null), licenses), today);
    }

    @Test void onlyApprovedCpAndPanTrainNormalizationsAreUsed() {
        assertEquals("R81.20", LifecycleProjection.train("CHECKPOINT", "R81.20 Jumbo Hotfix Take 99"));
        assertEquals("R80.40", LifecycleProjection.train("CHECKPOINT", "R80.40"));
        assertEquals("10.2", LifecycleProjection.train("PALOALTO", "10.2.8-h3"));
        assertEquals("11.1", LifecycleProjection.train("PALOALTO", "11.1.2"));
        assertEquals("10.2", LifecycleProjection.train("PALOALTO", "PAN-OS 10.2"));
        assertNull(LifecycleProjection.train("FORTINET", "7.4.1"));
        assertNull(LifecycleProjection.train("PALOALTO", "10.2.invalid"));
        assertNull(LifecycleProjection.train("CHECKPOINT", "unknown"));
    }

    @Test void emptyCatalogAndAmbiguousMatchesNeverGuess() {
        var result = project(device("device-1", "check_point", null), List.of(), null, TODAY);
        assertEquals("NO_LIFECYCLE_DATA", result.get("status"));
        assertEquals("UNKNOWN", result.get("risk"));
        var duplicate = entry("HARDWARE", "Example Appliance", TODAY);
        var ambiguous = LifecycleProjection.match("CHECKPOINT", "HARDWARE", "Example Appliance", List.of(duplicate, duplicate));
        assertEquals("NO_LIFECYCLE_DATA", ambiguous.get("status"));
        assertEquals("Ambiguous catalog matches", ambiguous.get("reason"));
        var software = LifecycleProjection.match("PALOALTO", "SOFTWARE", "10.2.8-h3", List.of(
                pan("10.2"), pan("PAN-OS 10.2")));
        assertEquals("NO_LIFECYCLE_DATA", software.get("status"));
    }
    private static LifecycleCatalogEntry pan(String product) {
        return new LifecycleCatalogEntry(product, "PALOALTO", "SOFTWARE", product, null, TODAY, null, "MANUAL", "", "synthetic-actor", Instant.EPOCH);
    }

    @Test void modelComparisonIsExactAndKnownDatesDoNotFabricateLowRisk() {
        var catalog = List.of(entry("HARDWARE", "Example Appliance", TODAY.plusDays(400)), entry("SOFTWARE", "R81.20", null));
        var result = project(device("device-1", "check_point", null), catalog, null, TODAY);
        assertEquals("MATCHED", result.get("status"));
        assertEquals("UNKNOWN", result.get("risk"));
        assertEquals("NO_LIFECYCLE_DATA", LifecycleProjection.match("CHECKPOINT", "HARDWARE", "example appliance", catalog).get("status"));
    }

    @Test void otherVendorsMatchOnlyTheExactStoredSoftwareLabel() {
        var exact = new LifecycleCatalogEntry("row-1", "FORTINET", "SOFTWARE", "ExampleOS-1.2.3", null, TODAY,
                null, "MANUAL", "", "synthetic-actor", Instant.EPOCH);
        assertEquals("version exact", LifecycleProjection.match("FORTINET", "SOFTWARE", "ExampleOS-1.2.3", List.of(exact)).get("basis"));
        assertEquals("NO_LIFECYCLE_DATA", LifecycleProjection.match("FORTINET", "SOFTWARE", "ExampleOS-1.2.4", List.of(exact)).get("status"));
    }

    @Test void riskBoundariesAndOverlappingSummaryWindowsAreExplicit() {
        for (int days : new int[]{-1, 0, 179, 180, 364, 365}) {
            var result = project(device("device-1", "check_point", null), List.of(
                    entry("HARDWARE", "Example Appliance", TODAY.plusDays(days)), entry("SOFTWARE", "R81.20", TODAY.plusDays(500))), null, TODAY);
            assertEquals(days < 0 ? "EXPIRED" : days < 180 ? "HIGH" : days < 365 ? "MEDIUM" : "LOW", result.get("risk"));
            var summary = LifecycleService.summary(List.of(result));
            assertEquals(days >= 0 && days <= 180 ? 1L : 0L, summary.get("within_180_days"));
            assertEquals(days >= 0 && days <= 365 ? 1L : 0L, summary.get("within_365_days"));
        }
    }

    @Test void storedInfobloxFieldsAreProjectedAndNonDatesRemainUnknown() {
        var licenses = LifecycleProjection.licenses("INFOBLOX", """
                [{"type":"DNS","kind":"subscription","member":"FW-TANGO-04","expiry_date":"2026-10-20"},
                 {"type":"DHCP","expiry_date":"Never"},{"type":"DNS","expiry_date":"2026-02-30"}]
                """, TODAY);
        assertEquals("2026-10-20", licenses.getFirst().get("expiry"));
        assertEquals(11L, licenses.getFirst().get("days_remaining"));
        assertEquals("UNKNOWN", licenses.get(1).get("status"));
        assertEquals("Never", licenses.get(1).get("label"));
        assertEquals("UNKNOWN", licenses.get(2).get("status"));
        assertEquals("UNKNOWN", LifecycleProjection.licenses("INFOBLOX", "broken", TODAY).getFirst().get("status"));
    }

    @Test void otherVendorsHaveNoCollectedLicensesOrSupportContractSemantics() {
        for (String vendor : List.of("check_point", "palo_alto", "fortinet", "bluecoat", "radware", "cisco_asa", "pulse_secure")) {
            var result = project(device("device-1", vendor, null), List.of(), "[{\"expiry_date\":\"2030-01-01\"}]", TODAY);
            assertEquals(List.of(), result.get("licenses"));
            assertEquals("NOT_COLLECTED", result.get("license_status"));
            assertEquals("NOT_COLLECTED", result.get("support_contract_status"));
        }
    }

    @Test void clusterMembersRemainSeparateRowsWithTheirOwnEvidenceIds() {
        var first = project(device("member-a", "check_point", "CLS-ROMEO-01"), List.of(), null, TODAY);
        var second = project(device("member-b", "check_point", "CLS-ROMEO-01"), List.of(), null, TODAY);
        assertNotEquals(first.get("device_id"), second.get("device_id"));
        assertEquals(first.get("cluster_member_ref"), second.get("cluster_member_ref"));
        assertEquals(2, LifecycleService.summary(List.of(first, second)).get("devices"));
        assertEquals(2L, LifecycleService.summary(List.of(first, second)).get("no_lifecycle_data"));
    }
}
