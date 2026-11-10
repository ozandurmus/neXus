package com.securityexpert.nexus.ui2.service.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LivePatchItem;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.Read;
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

    @Test void aPermanentInfobloxLicenseIsNotUnknownAndHasNoDate() {
        var licenses = LifecycleProjection.licenses("INFOBLOX", """
                [{"type":"GRID","kind":"Static","member":"grid","expiration_status":"PERMANENT"},
                 {"type":"DNS","kind":"Static","member":"grid","expiry_date":"2026-10-20","expiration_status":"EXPIRING_SOON"}]
                """, TODAY);
        assertEquals("PERMANENT", licenses.getFirst().get("status"));
        assertEquals("Permanent", licenses.getFirst().get("label"));
        assertEquals(null, licenses.getFirst().get("expiry"));
        assertEquals(null, licenses.getFirst().get("days_remaining"));
        assertEquals("DATED", licenses.get(1).get("status"));
        assertEquals("2026-10-20", licenses.get(1).get("expiry"));
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

    private static final Instant SEEN = Instant.parse("2026-10-09T01:00:00Z");
    private static LicenseItem item(String kind, String category, String label, String state, LocalDate expires, String member, String grade) {
        return new LicenseItem(kind, category, label, state, expires, "synthetic detail", member, grade, SEEN);
    }
    private static Read read(String kind, String outcome) { return new Read("device-1", kind, outcome, "TOKEN", 1, SEEN); }
    private static Map<String, Object> projectStored(String vendor, LicenseItem[] items, Read[] reads, LivePatchItem[] patches, DevicePlatformFacts facts) {
        return LifecycleProjection.device(device("device-1", vendor, null), List.of(),
                new com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogRepository.StoredInventory(
                        "Example Appliance", "R81.20 Jumbo Hotfix Take 99", null), TODAY,
                new LifecycleProjection.Stored(List.of(reads), List.of(items), List.of(patches), facts));
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> device) { return (List<Map<String, Object>>) device.get("licenses"); }

    @Test void everyStoredRowStatusIsProjectedWithItsKeys() {
        var result = projectStored("check_point", new LicenseItem[]{
                item("cp_cplic_print", "LICENSE", "CPSB-FW", "PERMANENT", null, null, "DIRECT_DEVICE"),
                item("cp_cplic_print", "LICENSE", "CPSB-IPS", "EXPIRED", TODAY.plusDays(5), null, "DIRECT_DEVICE"),
                item("cp_cplic_print", "LICENSE", "CPSB-AV", "VALID", TODAY.minusDays(3), null, "DIRECT_DEVICE"),
                item("cp_cplic_print", "LICENSE", "CPSB-URLF", "VALID", TODAY.plusDays(30), null, "DIRECT_DEVICE"),
                item("asa_show_license_all", "SMART_LICENSING", "Smart Licensing", "UNREGISTERED", null, null, "DIRECT_DEVICE"),
                item("cp_cplic_print", "LICENSE", "CPSB-X", "UNKNOWN", null, null, "DIRECT_DEVICE")},
                new Read[]{read("cp_cplic_print", "COLLECTED")}, new LivePatchItem[0], null);
        var r = rows(result);
        assertEquals(List.of("PERMANENT", "EXPIRED", "EXPIRED", "DATED", "UNREGISTERED", "UNKNOWN"), r.stream().map(x -> x.get("status")).toList());
        assertEquals("Permanent", r.get(0).get("label")); assertNull(r.get(0).get("expiry")); assertNull(r.get(0).get("days_remaining"));
        assertTrue(r.get(0).containsKey("days_remaining")); assertFalse(r.get(0).containsKey("member"));
        assertEquals(-3L, r.get(2).get("days_remaining")); assertEquals(30L, r.get(3).get("days_remaining"));
        assertEquals("2026-11-08", r.get(3).get("expiry")); assertEquals("2026-11-08", r.get(3).get("label"));
        assertEquals("CPSB-URLF", r.get(3).get("name")); assertEquals("LICENSE", r.get(3).get("kind")); assertEquals("LICENSE", r.get(3).get("category"));
        assertEquals("DIRECT_DEVICE", r.get(3).get("evidence")); assertEquals("cp_cplic_print", r.get(3).get("source"));
        assertEquals(SEEN.toString(), r.get(3).get("observed_at")); assertEquals("synthetic detail", r.get(3).get("detail"));
        assertEquals("UNKNOWN", r.get(5).get("label"));
        assertEquals(true, result.get("license_problem"));
        assertEquals(List.of(Map.of("label", "CPSB-IPS", "status", "EXPIRED"), Map.of("label", "CPSB-AV", "status", "EXPIRED"),
                Map.of("label", "Smart Licensing", "status", "UNREGISTERED")), result.get("license_problems"));
        // The next expiry that is still ahead, not the oldest expired entry.
        assertEquals("2026-10-14", ((Map<?, ?>) result.get("nearest_license")).get("expiry"));
    }

    @Test void smartLicensingProblemStatesMarkTheDeviceAndAtMostFiveAreListed() {
        for (String state : List.of("EVAL_EXPIRED", "UNREGISTERED", "OUT_OF_COMPLIANCE")) {
            var result = projectStored("cisco_asa", new LicenseItem[]{item("asa_show_license_all", "SMART_LICENSING", "Smart Licensing", state, null, null, "DIRECT_DEVICE")},
                    new Read[]{read("asa_show_license_all", "COLLECTED")}, new LivePatchItem[0], null);
            assertEquals(true, result.get("license_problem")); assertEquals(state, rows(result).getFirst().get("status"));
        }
        for (String state : List.of("VALID", "NOT_APPLICABLE", "NOT_LICENSED")) {
            assertEquals(false, projectStored("cisco_asa", new LicenseItem[]{item("asa_show_license_all", "SMART_LICENSING", "Smart Licensing", state, null, null, "DIRECT_DEVICE")},
                    new Read[]{read("asa_show_license_all", "COLLECTED")}, new LivePatchItem[0], null).get("license_problem"));
        }
        var many = new LicenseItem[7];
        for (int i = 0; i < many.length; i++) many[i] = item("cp_cplic_print", "LICENSE", "L" + i, "EXPIRED", null, null, "DIRECT_DEVICE");
        assertEquals(5, ((List<?>) projectStored("check_point", many, new Read[0], new LivePatchItem[0], null).get("license_problems")).size());
    }

    @Test void managerReportedRowsKeepTheirMemberAndEvidenceGradeAndInfobloxRowsStayListedFirst() {
        var result = LifecycleProjection.device(device("device-1", "infoblox", null), List.of(),
                new com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogRepository.StoredInventory(null, null,
                        "[{\"type\":\"DNS\",\"kind\":\"subscription\",\"member\":\"FW-TANGO-04\",\"expiry_date\":\"2026-10-20\"}]"), TODAY,
                new LifecycleProjection.Stored(List.of(read("infoblox_license_gridwide", "COLLECTED")), List.of(
                        item("infoblox_license_gridwide", "LICENSE", "Grid", "VALID", TODAY.plusDays(90), "grid", "DIRECT_DEVICE"),
                        item("mc_device_license", "LICENSE", "Web Filter", "VALID", TODAY.plusDays(10), "FW-BRAVO-02", "MANAGER_REPORTED")),
                        List.of(), null));
        var r = rows(result);
        assertEquals(3, r.size());
        assertEquals("DNS", r.get(0).get("name")); assertFalse(r.get(0).containsKey("evidence"));
        assertEquals("grid", r.get(1).get("member")); assertEquals("MANAGER_REPORTED", r.get(2).get("evidence"));
        assertEquals("FW-BRAVO-02", r.get(2).get("member"));
        assertEquals("COLLECTED", result.get("license_status"));
    }

    @Test void licenseStatusIsCollectedNotCollectedOrUnknownFromTheReads() {
        var none = projectStored("fortinet", new LicenseItem[0], new Read[0], new LivePatchItem[0], null);
        assertEquals("NOT_COLLECTED", none.get("license_status")); assertEquals(List.of(), none.get("license_reads"));
        var failed = projectStored("fortinet", new LicenseItem[0], new Read[]{read("fgt_update_info_contract", "FAILED")}, new LivePatchItem[0], null);
        assertEquals("UNKNOWN", failed.get("license_status"));
        assertEquals(List.of(Map.of("kind", "fgt_update_info_contract", "outcome", "FAILED", "reason", "TOKEN", "item_count", 1,
                "observed_at", SEEN.toString())), failed.get("license_reads"));
        var mixed = projectStored("fortinet", new LicenseItem[0], new Read[]{read("a", "FAILED"), read("b", "COLLECTED")}, new LivePatchItem[0], null);
        assertEquals("COLLECTED", mixed.get("license_status"));
        // a Live Patch read alone is not a license read
        var onlyPatch = projectStored("check_point", new LicenseItem[0], new Read[]{read("cp_cplp_list", "COLLECTED")}, new LivePatchItem[0], null);
        assertEquals("NOT_COLLECTED", onlyPatch.get("license_status")); assertEquals(List.of(), onlyPatch.get("license_reads"));
    }

    @Test void supportContractStatusFollowsAContractItem() {
        assertEquals("COLLECTED", projectStored("check_point", new LicenseItem[]{item("cp_cplic_print", "SUPPORT_CONTRACT", "CPAP-X", "VALID", TODAY.plusDays(1), null, "DIRECT_DEVICE")},
                new Read[0], new LivePatchItem[0], null).get("support_contract_status"));
        assertEquals("NOT_COLLECTED", projectStored("check_point", new LicenseItem[]{item("cp_cplic_print", "LICENSE", "L", "VALID", null, null, "DIRECT_DEVICE")},
                new Read[0], new LivePatchItem[0], null).get("support_contract_status"));
    }

    private static LivePatchItem patch(String id, String status) {
        return new LivePatchItem(id, status, "mode", 2, 3, java.time.LocalDateTime.of(2026, 9, 1, 8, 30), List.of("sk000000", "CVE-2026-0001"), SEEN);
    }

    @Test void livePatchBlockCountsStatusesAndCarriesTakesAndOnlyCheckPointHasIt() {
        var facts = new DevicePlatformFacts("device-1", Optional.empty(), Optional.of("Jumbo Hotfix Take 99"), Optional.empty(),
                Map.of("live_patch_package_take", "42"), Optional.empty(), "synthetic", Optional.empty());
        var result = projectStored("check_point", new LicenseItem[0], new Read[]{read("cp_cplp_list", "COLLECTED")}, new LivePatchItem[]{
                patch("PATCH-A", "armed"), patch("PATCH-B", "ready"), patch("PATCH-C", "ready"), patch("PATCH-D", "jumbofix"), patch("PATCH-E", "reverted")}, facts);
        @SuppressWarnings("unchecked") var live = (Map<String, Object>) result.get("live_patch");
        assertEquals("42", live.get("package_take")); assertEquals("Jumbo Hotfix Take 99", live.get("jumbo"));
        assertEquals("COLLECTED", live.get("read_outcome")); assertEquals(SEEN.toString(), live.get("observed_at"));
        assertEquals(1L, live.get("armed")); assertEquals(2L, live.get("ready")); assertEquals(1L, live.get("jumbofix")); assertEquals(1L, live.get("other"));
        @SuppressWarnings("unchecked") var items = (List<Map<String, Object>>) live.get("items");
        assertEquals(Map.of("patch", "PATCH-A", "status", "armed", "mode", "mode", "running", 2, "total", 3,
                "installed_at", "2026-09-01T08:30", "refs", List.of("sk000000", "CVE-2026-0001"), "idle", false), items.getFirst());
        assertEquals(0L, live.get("idle")); assertEquals(false, live.get("missing"));
        for (String vendor : List.of("palo_alto", "fortinet", "infoblox", "cisco_asa"))
            assertNull(projectStored(vendor, new LicenseItem[0], new Read[0], new LivePatchItem[0], facts).get("live_patch"));
        var noTake = projectStored("check_point", new LicenseItem[0], new Read[0], new LivePatchItem[0], null);
        @SuppressWarnings("unchecked") var empty = (Map<String, Object>) noTake.get("live_patch");
        assertNull(empty.get("package_take")); assertNull(empty.get("jumbo")); assertNull(empty.get("read_outcome"));
        assertEquals(0L, empty.get("ready")); assertEquals(List.of(), empty.get("items"));
    }

    @Test void aReadyPatchWhoseProcessIsNotRunningIsIdleNotPending() {
        var idle = new LivePatchItem("PATCH-I", "ready", "mode", 0, 2, null, List.of(), SEEN);
        var unknown = new LivePatchItem("PATCH-U", "ready", "mode", null, null, null, List.of(), SEEN);
        var result = projectStored("check_point", new LicenseItem[0], new Read[]{read("cp_cplp_list", "COLLECTED")},
                new LivePatchItem[]{idle, unknown, patch("PATCH-B", "ready"), patch("PATCH-A", "armed")}, null);
        @SuppressWarnings("unchecked") var live = (Map<String, Object>) result.get("live_patch");
        assertEquals(1L, live.get("idle"), "0 running processes: nothing to patch");
        assertEquals(2L, live.get("ready"), "a running process, or an unknown count, stays pending");
        assertEquals(1L, live.get("armed")); assertEquals(0L, live.get("other"));
        @SuppressWarnings("unchecked") var items = (List<Map<String, Object>>) live.get("items");
        assertEquals(List.of(true, false, false, false), items.stream().map(x -> x.get("idle")).toList());
    }

    @Test void aGaiaDeviceWhosePatchListWasAskedForAndGaveNothingAndHasNoPackageIsMissingLivePatch() {
        for (String outcome : List.of("EMPTY", "UNPARSED")) {
            var gaia = new DevicePlatformFacts("device-1", Optional.empty(), Optional.of("R81.20 Jumbo Take 119"), Optional.empty(),
                    Map.of(), Optional.empty(), "synthetic", Optional.empty());
            var result = projectStored("check_point", new LicenseItem[0], new Read[]{read("cp_cplp_list", outcome)}, new LivePatchItem[0], gaia);
            assertEquals(true, ((Map<?, ?>) result.get("live_patch")).get("missing"), outcome);
        }
        var spark = new DevicePlatformFacts("device-1", Optional.empty(), Optional.empty(), Optional.of("gaia_embedded"),
                Map.of(), Optional.empty(), "synthetic", Optional.empty());
        assertEquals(false, ((Map<?, ?>) projectStored("check_point", new LicenseItem[0], new Read[]{read("cp_cplp_list", "EMPTY")},
                new LivePatchItem[0], spark).get("live_patch")).get("missing"), "Live Patch is not supported on a Spark");
        var withTake = new DevicePlatformFacts("device-1", Optional.empty(), Optional.empty(), Optional.empty(),
                Map.of("live_patch_package_take", "30"), Optional.empty(), "synthetic", Optional.empty());
        assertEquals(false, ((Map<?, ?>) projectStored("check_point", new LicenseItem[0], new Read[]{read("cp_cplp_list", "FAILED")},
                new LivePatchItem[0], withTake).get("live_patch")).get("missing"));
        assertEquals(false, ((Map<?, ?>) projectStored("check_point", new LicenseItem[0], new Read[0], new LivePatchItem[0], null)
                .get("live_patch")).get("missing"), "never asked: not collected, not missing");
    }

    @Test void onlyTheLatestEntryOfALabelCountsAndEarlierContractsBesideAValidOneAreHistory() {
        // What a Check Point gateway lists: the same contract SKU three times (two expired, one current), an older SKU
        // that expired, and a subscription whose renewed entry is valid.
        var result = projectStored("check_point", new LicenseItem[]{
                item("cp_cplic_print", "SUPPORT_CONTRACT", "CPES-SS-PREMIUM", "VALID", TODAY.minusDays(800), null, "DIRECT_DEVICE"),
                item("cp_cplic_print", "SUPPORT_CONTRACT", "CPES-SS-PREMIUM", "VALID", TODAY.plusDays(400), null, "DIRECT_DEVICE"),
                item("cp_cplic_print", "SUPPORT_CONTRACT", "CPES-SS-PREMIUM", "VALID", TODAY.minusDays(30), null, "DIRECT_DEVICE"),
                item("cp_cplic_print", "SUPPORT_CONTRACT", "CPES-SS-STANDARD", "VALID", TODAY.minusDays(900), null, "DIRECT_DEVICE"),
                item("pan_license_info", "SUBSCRIPTION", "Threat Prevention", "EXPIRED", TODAY.minusDays(10), null, "DIRECT_DEVICE"),
                item("pan_license_info", "SUBSCRIPTION", "Threat Prevention", "VALID", TODAY.plusDays(90), null, "DIRECT_DEVICE")},
                new Read[]{read("cp_cplic_print", "COLLECTED")}, new LivePatchItem[0], null);
        var r = rows(result);
        assertEquals(3, r.size(), "one row per category and label");
        assertEquals(List.of("CPES-SS-PREMIUM", "CPES-SS-STANDARD", "Threat Prevention"), r.stream().map(x -> x.get("name")).toList());
        assertEquals(List.of("DATED", "EXPIRED", "DATED"), r.stream().map(x -> x.get("status")).toList());
        assertEquals(List.of(2, 0, 1), r.stream().map(x -> x.get("earlier_entries")).toList());
        assertEquals(TODAY.plusDays(400).toString(), r.get(0).get("expiry"));
        assertEquals(false, result.get("license_problem"), "an expired earlier contract beside a valid one is history");
        assertEquals(List.of(), result.get("license_problems"));
        assertEquals(TODAY.plusDays(90).toString(), ((Map<?, ?>) result.get("nearest_license")).get("expiry"));

        // No contract of the device is valid any more: one problem, named once.
        var expired = projectStored("check_point", new LicenseItem[]{
                item("cp_cplic_print", "SUPPORT_CONTRACT", "CPES-SS-PREMIUM", "VALID", TODAY.minusDays(30), null, "DIRECT_DEVICE"),
                item("cp_cplic_print", "SUPPORT_CONTRACT", "CPES-SS-STANDARD", "VALID", TODAY.minusDays(900), null, "DIRECT_DEVICE"),
                item("cp_cplic_print", "LICENSE", "CPSB-FW", "PERMANENT", null, null, "DIRECT_DEVICE")},
                new Read[]{read("cp_cplic_print", "COLLECTED")}, new LivePatchItem[0], null);
        assertEquals(true, expired.get("license_problem"));
        assertEquals(List.of(Map.of("label", "Support contract", "status", "EXPIRED")), expired.get("license_problems"));

        // A subscription whose latest entry has expired is a problem; a permanent entry of the same label wins over a date.
        var sub = projectStored("palo_alto", new LicenseItem[]{
                item("pan_license_info", "SUBSCRIPTION", "Logging Service", "EXPIRED", TODAY.minusDays(100), null, "DIRECT_DEVICE"),
                item("pan_license_info", "SUBSCRIPTION", "Web proxy license", "VALID", TODAY.minusDays(5), null, "DIRECT_DEVICE"),
                item("pan_license_info", "SUBSCRIPTION", "Web proxy license", "PERMANENT", null, null, "DIRECT_DEVICE")},
                new Read[]{read("pan_license_info", "COLLECTED")}, new LivePatchItem[0], null);
        assertEquals(List.of(Map.of("label", "Logging Service", "status", "EXPIRED")), sub.get("license_problems"));
        assertEquals(List.of("EXPIRED", "PERMANENT"), rows(sub).stream().map(x -> x.get("status")).toList());
    }

    @Test void summaryCountsProblemsCollectedAndNotActiveAcrossAllVendors() {
        var problem = projectStored("fortinet", new LicenseItem[]{item("fgt_update_info_contract", "SUBSCRIPTION", "FMWR", "EXPIRED", null, null, "DIRECT_DEVICE"),
                item("fgt_update_info_contract", "SUBSCRIPTION", "AV", "VALID", TODAY.plusDays(20), null, "DIRECT_DEVICE")},
                new Read[]{read("fgt_update_info_contract", "COLLECTED")}, new LivePatchItem[0], null);
        var cp = projectStored("check_point", new LicenseItem[0], new Read[]{read("cp_cplp_list", "COLLECTED")},
                new LivePatchItem[]{patch("PATCH-B", "ready")}, null);
        var plain = project(device("device-9", "palo_alto", null), List.of(), null, TODAY);
        var summary = LifecycleService.summary(List.of(problem, cp, plain));
        assertEquals(1L, summary.get("license_problems")); assertEquals(1L, summary.get("licenses_collected"));
        assertEquals(1L, summary.get("live_patch_not_active")); assertEquals(1L, summary.get("licenses_within_60_days"));
        assertEquals(3, summary.get("devices"));
    }

    @Test void aDeviceWithNothingStoredEqualsTheOldPayloadPlusEmptyNewKeys() {
        var old = project(device("device-1", "check_point", null), List.of(), null, TODAY);
        var withEmpty = projectStored("check_point", new LicenseItem[0], new Read[0], new LivePatchItem[0], null);
        assertEquals(old, withEmpty);
        assertEquals(List.of(), old.get("licenses")); assertEquals("NOT_COLLECTED", old.get("license_status"));
        assertEquals("NOT_COLLECTED", old.get("support_contract_status")); assertEquals(false, old.get("license_problem"));
        assertEquals(List.of(), old.get("license_problems")); assertEquals(List.of(), old.get("license_reads"));
        assertNull(old.get("nearest_license"));
        assertTrue(old.containsKey("live_patch"));
        assertNull(project(device("device-1", "palo_alto", null), List.of(), null, TODAY).get("live_patch"));
    }
}
