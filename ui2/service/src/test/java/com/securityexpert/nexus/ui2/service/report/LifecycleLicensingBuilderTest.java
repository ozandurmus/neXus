package com.securityexpert.nexus.ui2.service.report;

import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.NOW;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.RAW_VALUES;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.context;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.kpis;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.rows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LivePatchItem;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.Read;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogRepository.StoredInventory;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleProjection;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleService.FleetSnapshot;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.PlaneAsOf;

/**
 * RPT-17 Lifecycle and licensing unit, masking and renderer tests
 * ({@code docs/design/REPORTS_RPT17_LIFECYCLE_ADDENDUM_2026_11_24.md}). Masked synthetic names only
 * ({@code FW-TANGO-04}, {@code CLS-ROMEO-01}, {@code 192.0.2.x}).
 */
class LifecycleLicensingBuilderTest {

    private static final LocalDate TODAY = NOW.atZone(com.securityexpert.nexus.ui2.platform.JobWindowPolicy.ZONE_ID).toLocalDate();
    private static final Instant FRESH_AT = NOW.minus(Duration.ofHours(2));
    private static final Instant STALE_AT = NOW.minus(Duration.ofHours(30));

    private static final String TANGO = "FW-TANGO-04";
    private static final String JULIET = "FW-JULIET-06";
    private static final String BRAVO = "FW-BRAVO-02";
    private static final String ECHO = "FW-ECHO-08";
    private static final String CLS_ROMEO = "CLS-ROMEO-01";

    private static DeviceSummaryRecord dev(String id, String host, String vendor, String role, String cluster,
            String version) {
        return new DeviceSummaryRecord(id, role, vendor, DeviceEnrollmentState.ENROLLED, Optional.ofNullable(host),
                Optional.of("Check Point 6600"), Optional.ofNullable(version), Optional.empty(),
                Optional.ofNullable(cluster), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("192.0.2.10"), Optional.of("192.0.2.10"), false, false);
    }

    private static LicenseItem item(String kind, String category, String label, String state, LocalDate expires,
            Instant at) {
        return new LicenseItem(kind, category, label, state, expires, null, null, "DIRECT_DEVICE", at);
    }

    private static Read read(String id, String kind, String outcome, String reason, Instant at) {
        return new Read(id, kind, outcome, reason, 1, at);
    }

    private static Map<String, Object> vulnEvaluated(int affected, int exploited, int crit, int high,
            Instant loadedAt, long ageDays) {
        Map<String, Object> compared = new LinkedHashMap<>();
        compared.put("source", "checkpoint");
        compared.put("advisories", 140);
        compared.put("loaded_at", loadedAt.toString());
        compared.put("age_days", ageDays);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("state", "EVALUATED");
        v.put("reason", null);
        v.put("affected", affected);
        v.put("affected_exploited", exploited);
        v.put("affected_critical_high", crit + high);
        v.put("affected_critical", crit);
        v.put("affected_high", high);
        v.put("compared", compared);
        return v;
    }

    private static Map<String, Object> vulnState(String state, String reason) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("state", state);
        v.put("reason", reason);
        v.put("affected", 0);
        v.put("affected_exploited", 0);
        v.put("affected_critical_high", 0);
        v.put("compared", null);
        return v;
    }

    private static ReportDocument build(List<DeviceSummaryRecord> devices, FleetSnapshot snapshot,
            Map<String, Instant> newestInventory) {
        ReportFixtures.Data data = new ReportFixtures.Data();
        data.devices = new ArrayList<>(devices);
        data.inventory = new LinkedHashMap<>(newestInventory);
        data.lifecycle = snapshot;
        return new LifecycleLicensingBuilder().build(context(ReportTemplate.RPT_17), data);
    }

    private static PlaneAsOf plane(ReportDocument doc, String name) {
        return doc.dataAsOf().stream().filter(p -> p.plane().equals(name)).findFirst().orElseThrow();
    }

    private static String tone(ReportDocument doc, String sectionId, String label) {
        return doc.sections().stream().filter(s -> s.id().equals(sectionId)).findFirst().orElseThrow()
                .kpis().stream().filter(k -> k.label().equals(label)).findFirst().orElseThrow().tone();
    }

    // ---- 1. Licensing: expired, 30d, 90d, unknown, historical contract suppression -----------------------------

    @Test
    void licensingClassifiesExpired30d90dAndUnknownWhileOmittingPermanentDistantAndSupersededContracts() {
        var d1 = dev("dev-1", TANGO, "check_point", "gateway", CLS_ROMEO, "R81.20");
        var d2 = dev("dev-2", JULIET, "palo_alto", "firewall", null, "11.1.4");
        var d3 = dev("dev-3", BRAVO, "fortinet", "firewall", null, "7.4.3");

        List<Read> d1Reads = List.of(read("dev-1", "cp_cplic_print", "COLLECTED", "OK", FRESH_AT));
        List<LicenseItem> d1Items = List.of(
                // Historical support contract alongside a valid (>90d) support contract: omitted per LifecycleProjection.
                item("cp_cplic_print", "SUPPORT_CONTRACT", "CPES-SS-OLD", "VALID", TODAY.minusDays(400), FRESH_AT),
                item("cp_cplic_print", "SUPPORT_CONTRACT", "CPES-SS-CURRENT", "VALID", TODAY.plusDays(180), FRESH_AT),
                item("cp_cplic_print", "LICENSE", "CPSB-FW", "PERMANENT", null, FRESH_AT),
                item("cp_cplic_print", "LICENSE", "CPSB-IPS", "EXPIRED", TODAY.minusDays(5), FRESH_AT),
                item("cp_cplic_print", "SUBSCRIPTION", "CPSB-URLF", "VALID", TODAY.plusDays(30), FRESH_AT),
                item("cp_cplic_print", "SUBSCRIPTION", "CPSB-AV", "VALID", TODAY.plusDays(31), FRESH_AT),
                item("cp_cplic_print", "LICENSE", "CPSB-UNPARSED", "UNKNOWN", null, FRESH_AT));

        // d2 has ONLY expired support contracts -> Support contract IS expired and listed.
        List<Read> d2Reads = List.of(read("dev-2", "pan_license_info", "COLLECTED", "OK", FRESH_AT));
        List<LicenseItem> d2Items = List.of(
                item("pan_license_info", "SUPPORT_CONTRACT", "PAN-SVC-PREM", "VALID", TODAY.minusDays(10), FRESH_AT),
                item("pan_license_info", "SUBSCRIPTION", "Threat Prevention", "VALID", TODAY.plusDays(90), FRESH_AT),
                item("pan_license_info", "SUBSCRIPTION", "WildFire", "VALID", TODAY.plusDays(91), FRESH_AT));

        var row1 = LifecycleProjection.device(d1, List.of(), new StoredInventory("6600", "R81.20", null), TODAY,
                new LifecycleProjection.Stored(d1Reads, d1Items, List.of(), null));
        var row2 = LifecycleProjection.device(d2, List.of(), new StoredInventory("PA-5220", "11.1.4", null), TODAY,
                new LifecycleProjection.Stored(d2Reads, d2Items, List.of(), null));
        var row3 = LifecycleProjection.device(d3, List.of(), new StoredInventory("FG-200F", "7.4.3", null), TODAY,
                LifecycleProjection.Stored.EMPTY);

        var snapshot = new FleetSnapshot(List.of(row1, row2, row3),
                Map.of("dev-1", d1Reads, "dev-2", d2Reads), Map.of());
        ReportDocument doc = build(List.of(d1, d2, d3), snapshot, Map.of("dev-1", FRESH_AT, "dev-2", FRESH_AT));

        var k = kpis(doc, "licensing");
        assertEquals("2 of 3", k.get("Devices with license evidence"));
        assertEquals("2", k.get("Expired"), "CPSB-IPS and PAN-SVC-PREM; CPES-SS-OLD is superseded");
        assertEquals("1", k.get("Expiring within 30 days"), "CPSB-URLF at +30 days");
        assertEquals("3", k.get("Expiring within 90 days"), "CPSB-URLF (+30), CPSB-AV (+31), Threat Prevention (+90)");
        assertEquals("1", k.get("Unknown or not collected (devices)"), "d3 has no licence read");
        assertEquals("BAD", tone(doc, "licensing", "Expired"));
        assertEquals("WARN", tone(doc, "licensing", "Expiring within 30 days"));
        assertEquals("UNKNOWN", tone(doc, "licensing", "Unknown or not collected (devices)"));

        List<List<String>> items = rows(doc, "licensing_items");
        assertEquals(6, items.size());
        // Sorted: EXPIRED (-10d, -5d), WITHIN 30 DAYS (30d), WITHIN 90 DAYS (31d, 90d), UNKNOWN
        assertEquals(List.of(JULIET, "Palo Alto", "Support contract", "PAN-SVC-PREM", "EXPIRED",
                TODAY.minusDays(10).toString(), "-10", FRESH_AT.toString()), items.get(0));
        assertEquals(List.of(TANGO, "Check Point", "License", "CPSB-IPS", "EXPIRED",
                TODAY.minusDays(5).toString(), "-5", FRESH_AT.toString()), items.get(1));
        assertEquals(List.of(TANGO, "Check Point", "Subscription", "CPSB-URLF", "WITHIN 30 DAYS",
                TODAY.plusDays(30).toString(), "30", FRESH_AT.toString()), items.get(2));
        assertEquals(List.of(TANGO, "Check Point", "Subscription", "CPSB-AV", "WITHIN 90 DAYS",
                TODAY.plusDays(31).toString(), "31", FRESH_AT.toString()), items.get(3));
        assertEquals(List.of(JULIET, "Palo Alto", "Subscription", "Threat Prevention", "WITHIN 90 DAYS",
                TODAY.plusDays(90).toString(), "90", FRESH_AT.toString()), items.get(4));
        assertEquals(List.of(TANGO, "Check Point", "License", "CPSB-UNPARSED", "UNKNOWN",
                "UNKNOWN", "UNKNOWN", FRESH_AT.toString()), items.get(5));
    }

    // ---- 1b. Review fixes: UNKNOWN is never green, never silently dropped --------------------------------------

    @Test
    void unrecognisedLicenceStatusIsListedAsUnknownAndPartialCoverageKeepsZeroKpisUnknown() {
        var d1 = dev("dev-1", TANGO, "check_point", "gateway", null, "R81.20");
        var d2 = dev("dev-2", BRAVO, "fortinet", "firewall", null, "7.4.3");
        List<Read> reads = List.of(read("dev-1", "cp_cplic_print", "COLLECTED", "OK", FRESH_AT));
        List<LicenseItem> items = List.of(
                item("cp_cplic_print", "LICENSE", "CPSB-ODD", "SOMETHING_NEW", null, FRESH_AT));
        var row1 = LifecycleProjection.device(d1, List.of(), new StoredInventory("6600", "R81.20", null), TODAY,
                new LifecycleProjection.Stored(reads, items, List.of(), null));
        var row2 = LifecycleProjection.device(d2, List.of(), new StoredInventory("FG-200F", "7.4.3", null), TODAY,
                LifecycleProjection.Stored.EMPTY);
        var snapshot = new FleetSnapshot(List.of(row1, row2), Map.of("dev-1", reads), Map.of());
        ReportDocument doc = build(List.of(d1, d2), snapshot, Map.of("dev-1", FRESH_AT));

        var k = kpis(doc, "licensing");
        assertEquals("1 of 2", k.get("Devices with license evidence"));
        assertEquals("0", k.get("Expired"));
        assertEquals("UNKNOWN", tone(doc, "licensing", "Expired"), "0 of a partially covered fleet is not OK");
        assertEquals("UNKNOWN", tone(doc, "licensing", "Expiring within 30 days"));
        assertEquals("UNKNOWN", tone(doc, "licensing", "Expiring within 90 days"));
        List<List<String>> rows = rows(doc, "licensing_items");
        assertEquals(1, rows.size());
        assertEquals("UNKNOWN", rows.get(0).get(4), "an unrecognised stored status is UNKNOWN, not dropped");
    }

    @Test
    void checkPointWithStoredTakeButNoLivePatchReadIsUnknownNotStored() {
        var cp = dev("cp-9", TANGO, "check_point", "gateway", null, "R81.20");
        var pf = new DevicePlatformFacts("cp-9", Optional.empty(), Optional.of("Jumbo Take 119"), Optional.empty(),
                Map.of("live_patch_package_take", "42"), Optional.empty(), "cp", Optional.of(FRESH_AT));
        var row = LifecycleProjection.device(cp, List.of(), new StoredInventory("6600", "R81.20", null), TODAY,
                new LifecycleProjection.Stored(List.of(), List.of(), List.of(), pf));
        var snapshot = new FleetSnapshot(List.of(row), Map.of(), Map.of("cp-9", pf));
        ReportDocument doc = build(List.of(cp), snapshot, Map.of());
        List<List<String>> rows = rows(doc, "live_patch_hotfix");
        assertEquals(1, rows.size());
        assertEquals("UNKNOWN", rows.get(0).get(5));
    }

    // ---- 2. Advisories: CISA KEV, critical/high, and UNKNOWN / not-evaluated separation ------------------------

    @Test
    void advisoriesOrderExploitedFirstSeparateUnknownStatesAndNeverFoldUnevaluatedIntoClean() {
        var dKev = dev("dev-kev", TANGO, "check_point", "gateway", CLS_ROMEO, "R81.20");
        var dHigh = dev("dev-high", JULIET, "palo_alto", "firewall", null, "11.1.4");
        var dClean = dev("dev-clean", BRAVO, "fortinet", "firewall", null, "7.4.3");
        var dStale = dev("dev-stale", ECHO, "cisco_asa", "firewall", null, "9.18(4)");

        var pfKev = new DevicePlatformFacts("dev-kev", Optional.empty(), Optional.of("R81.20 Jumbo Take 99"),
                Optional.empty(), Map.of(), Optional.empty(), "cp", Optional.of(FRESH_AT));

        var rKev = LifecycleProjection.device(dKev, List.of(), new StoredInventory("6600", "R81.20", null), TODAY,
                new LifecycleProjection.Stored(List.of(), List.of(), List.of(), pfKev));
        rKev.put("vulnerabilities", vulnEvaluated(3, 1, 1, 1, FRESH_AT, 2L));

        var rHigh = LifecycleProjection.device(dHigh, List.of(), new StoredInventory("PA-5220", "11.1.4", null), TODAY);
        rHigh.put("vulnerabilities", vulnEvaluated(2, 0, 1, 1, FRESH_AT, 9L));

        // Evaluated with only medium/low advisories: evaluated, not matching KEV or critical/high.
        var rClean = LifecycleProjection.device(dClean, List.of(), new StoredInventory("FG-200F", "7.4.3", null), TODAY);
        rClean.put("vulnerabilities", vulnEvaluated(1, 0, 0, 0, FRESH_AT, 1L));

        var rStale = LifecycleProjection.device(dStale, List.of(), new StoredInventory("ASA-5516", "9.18(4)", null), TODAY);
        rStale.put("vulnerabilities", vulnState("NOT_EVALUATED", "SOURCE_STALE"));

        var snapshot = new FleetSnapshot(List.of(rKev, rHigh, rClean, rStale), Map.of(), Map.of("dev-kev", pfKev));
        ReportDocument doc = build(List.of(dKev, dHigh, dClean, dStale), snapshot,
                Map.of("dev-kev", FRESH_AT, "dev-high", FRESH_AT, "dev-clean", FRESH_AT, "dev-stale", FRESH_AT));

        var k = kpis(doc, "advisories");
        assertEquals("3 of 4", k.get("Devices evaluated"));
        assertEquals("1", k.get("Affected by CISA KEV (exploited)"));
        assertEquals("2", k.get("Affected by critical/high advisories"));
        assertEquals("1", k.get("Unknown or not evaluated (devices)"));
        assertEquals("9 d", k.get("Oldest advisory source age"));
        assertEquals("WARN", tone(doc, "advisories", "Oldest advisory source age"), "9 days > 7-day amber threshold");

        List<List<String>> affected = rows(doc, "advisory_affected");
        assertEquals(2, affected.size());
        assertEquals(List.of(TANGO, "Check Point", "R81.20 Jumbo Take 99", "EXPLOITED", "1", "1", "1", "2",
                FRESH_AT.toString()), affected.get(0));
        assertEquals(List.of(JULIET, "Palo Alto", "11.1.4", "CRITICAL_HIGH", "0", "1", "1", "2",
                FRESH_AT.toString()), affected.get(1));

        List<List<String>> unknown = rows(doc, "advisory_unknown");
        assertEquals(List.of(List.of(ECHO, "Cisco ASA", "9.18(4)", "NOT_EVALUATED", "SOURCE_STALE")), unknown);
    }

    @Test
    void partialAdvisoryCoverageWithZeroMatchesIsUnknownNeverOk() {
        var dClean = dev("dev-clean", BRAVO, "fortinet", "firewall", null, "7.4.3");
        var dStale = dev("dev-stale", ECHO, "cisco_asa", "firewall", null, "9.18(4)");
        var rClean = LifecycleProjection.device(dClean, List.of(), new StoredInventory("FG-200F", "7.4.3", null), TODAY);
        rClean.put("vulnerabilities", vulnEvaluated(1, 0, 0, 0, FRESH_AT, 1L));
        var rStale = LifecycleProjection.device(dStale, List.of(), new StoredInventory("ASA-5516", "9.18(4)", null), TODAY);
        rStale.put("vulnerabilities", vulnState("NOT_EVALUATED", "SOURCE_STALE"));
        var snapshot = new FleetSnapshot(List.of(rClean, rStale), Map.of(), Map.of());
        ReportDocument doc = build(List.of(dClean, dStale), snapshot, Map.of("dev-clean", FRESH_AT, "dev-stale", FRESH_AT));

        assertEquals("0", kpis(doc, "advisories").get("Affected by CISA KEV (exploited)"));
        assertEquals("UNKNOWN", tone(doc, "advisories", "Affected by CISA KEV (exploited)"));
        assertEquals("UNKNOWN", tone(doc, "advisories", "Affected by critical/high advisories"));
    }

    @Test
    void partialLivePatchCollectionWithZeroCountsIsUnknownNeverOk() {
        var cpOk = dev("cp-1", BRAVO, "check_point", "gateway", null, "R81.20");
        var cpNoRead = dev("cp-2", JULIET, "check_point", "gateway", null, "R81.20");
        var pf1 = new DevicePlatformFacts("cp-1", Optional.empty(), Optional.of("Jumbo Take 119"), Optional.empty(),
                Map.of("live_patch_package_take", "42"), Optional.empty(), "cp", Optional.of(FRESH_AT));
        var r1Reads = List.of(read("cp-1", "cp_cplp_list", "COLLECTED", "OK", FRESH_AT));
        var r1Patches = List.of(new LivePatchItem("LP-1", "armed", "norm", 2, 2, null, List.of(), FRESH_AT));
        var row1 = LifecycleProjection.device(cpOk, List.of(), new StoredInventory("6600", "R81.20", null), TODAY,
                new LifecycleProjection.Stored(r1Reads, List.of(), r1Patches, pf1));
        var row2 = LifecycleProjection.device(cpNoRead, List.of(), new StoredInventory("6600", "R81.20", null), TODAY,
                LifecycleProjection.Stored.EMPTY);
        var snapshot = new FleetSnapshot(List.of(row1, row2), Map.of("cp-1", r1Reads), Map.of("cp-1", pf1));
        ReportDocument doc = build(List.of(cpOk, cpNoRead), snapshot, Map.of());

        assertEquals("0", kpis(doc, "live_patch_hotfix").get("Check Point Live Patch not active"));
        assertEquals("UNKNOWN", tone(doc, "live_patch_hotfix", "Check Point Live Patch not active"));
        assertEquals("UNKNOWN", tone(doc, "live_patch_hotfix", "Check Point Live Patch package missing"));
    }

    // ---- 3. Live Patch & hotfix currency -----------------------------------------------------------------------

    @Test
    void livePatchAndHotfixReportsNotActiveIdleMissingUnknownAndNonCheckPointStoredHotfix() {
        var cpActive = dev("cp-1", BRAVO, "check_point", "gateway", null, "R81.20");
        var cpPending = dev("cp-2", JULIET, "check_point", "gateway", null, "R81.20");
        var cpMissing = dev("cp-3", TANGO, "check_point", "gateway", null, "R81.20");
        var panHotfix = dev("pan-1", ECHO, "palo_alto", "firewall", null, "11.1.4");
        var fgtPlain = dev("fgt-1", "FW-KILO-05", "fortinet", "firewall", null, "7.4.3");

        var pf1 = new DevicePlatformFacts("cp-1", Optional.empty(), Optional.of("Jumbo Take 119"), Optional.empty(),
                Map.of("live_patch_package_take", "42"), Optional.empty(), "cp", Optional.of(FRESH_AT));
        var pf2 = new DevicePlatformFacts("cp-2", Optional.empty(), Optional.of("Jumbo Take 115"), Optional.empty(),
                Map.of("live_patch_package_take", "40"), Optional.empty(), "cp", Optional.of(FRESH_AT));
        var pf3 = new DevicePlatformFacts("cp-3", Optional.empty(), Optional.of("Jumbo Take 110"), Optional.empty(),
                Map.of(), Optional.empty(), "cp", Optional.of(FRESH_AT));
        var pfPan = new DevicePlatformFacts("pan-1", Optional.empty(), Optional.of("HF-2026-11"), Optional.empty(),
                Map.of(), Optional.empty(), "pan", Optional.of(FRESH_AT));

        var r1Reads = List.of(read("cp-1", "cp_cplp_list", "COLLECTED", "OK", FRESH_AT));
        // Idle ready patch (pidsRunning == 0) is NOT pending.
        var r1Patches = List.of(
                new LivePatchItem("LP-1", "armed", "norm", 2, 2, null, List.of("CVE-2026-0001"), FRESH_AT),
                new LivePatchItem("LP-2", "ready", "norm", 0, 2, null, List.of(), FRESH_AT));
        var r2Reads = List.of(read("cp-2", "cp_cplp_list", "COLLECTED", "OK", FRESH_AT));
        var r2Patches = List.of(new LivePatchItem("LP-3", "ready", "norm", 2, 2, null, List.of(), FRESH_AT));
        var r3Reads = List.of(read("cp-3", "cp_cplp_list", "EMPTY", "OK", FRESH_AT));

        var row1 = LifecycleProjection.device(cpActive, List.of(), new StoredInventory("6600", "R81.20", null), TODAY,
                new LifecycleProjection.Stored(r1Reads, List.of(), r1Patches, pf1));
        var row2 = LifecycleProjection.device(cpPending, List.of(), new StoredInventory("6600", "R81.20", null), TODAY,
                new LifecycleProjection.Stored(r2Reads, List.of(), r2Patches, pf2));
        var row3 = LifecycleProjection.device(cpMissing, List.of(), new StoredInventory("6600", "R81.20", null), TODAY,
                new LifecycleProjection.Stored(r3Reads, List.of(), List.of(), pf3));
        var rowPan = LifecycleProjection.device(panHotfix, List.of(), new StoredInventory("PA-5220", "11.1.4", null),
                TODAY, new LifecycleProjection.Stored(List.of(), List.of(), List.of(), pfPan));
        var rowFgt = LifecycleProjection.device(fgtPlain, List.of(), new StoredInventory("FG-200F", "7.4.3", null),
                TODAY, LifecycleProjection.Stored.EMPTY);

        var snapshot = new FleetSnapshot(List.of(row1, row2, row3, rowPan, rowFgt),
                Map.of("cp-1", r1Reads, "cp-2", r2Reads, "cp-3", r3Reads),
                Map.of("cp-1", pf1, "cp-2", pf2, "cp-3", pf3, "pan-1", pfPan));
        ReportDocument doc = build(List.of(cpActive, cpPending, cpMissing, panHotfix, fgtPlain), snapshot, Map.of());

        var k = kpis(doc, "live_patch_hotfix");
        assertEquals("4 of 5", k.get("Devices with stored hotfix or Live Patch"));
        assertEquals("1", k.get("Check Point Live Patch not active"));
        assertEquals("1", k.get("Check Point Live Patch package missing"));
        assertEquals("1", k.get("Without stored hotfix or Live Patch"), "fgt-1 has no separate hotfix_level");

        List<List<String>> rows = rows(doc, "live_patch_hotfix");
        assertEquals(4, rows.size(), "3 Check Point gateways + 1 non-CP device with stored hotfix_level");
        assertEquals(List.of(BRAVO, "Check Point", "R81.20", "Jumbo Take 119", "take 42 · 1 armed", "COLLECTED",
                FRESH_AT.toString()), rows.get(0));
        assertEquals(List.of(ECHO, "Palo Alto", "11.1.4", "HF-2026-11", "NOT_APPLICABLE", "STORED",
                FRESH_AT.toString()), rows.get(1));
        assertEquals(List.of(JULIET, "Check Point", "R81.20", "Jumbo Take 115", "take 40 · 0 armed · 1 not active",
                "NOT ACTIVE", FRESH_AT.toString()), rows.get(2));
        assertEquals(List.of(TANGO, "Check Point", "R81.20", "Jumbo Take 110", "No Live Patch package", "MISSING",
                FRESH_AT.toString()), rows.get(3));
    }

    // ---- 4. Coverage: no read, failed read, stale read, newest read age, empty estate --------------------------

    @Test
    void coverageSeparatesNoReadFailedReadCurrentAndStaleAndReportsAgeOfNewestRead() {
        var dFresh = dev("d-fresh", BRAVO, "check_point", "gateway", null, "R81.20");
        var dStale = dev("d-stale", ECHO, "palo_alto", "firewall", null, "11.1.4");
        var dFailed = dev("d-failed", JULIET, "fortinet", "firewall", null, "7.4.3");
        var dNone = dev("d-none", TANGO, "cisco_asa", "firewall", null, "9.18(4)");

        var rFresh = List.of(
                read("d-fresh", "cp_cplic_print", "COLLECTED", "OK", FRESH_AT),
                read("d-fresh", "cp_cplp_list", "COLLECTED", "OK", FRESH_AT.minus(Duration.ofHours(1))));
        var rStale = List.of(read("d-stale", "pan_license_info", "COLLECTED", "OK", STALE_AT));
        var rFailed = List.of(read("d-failed", "fgt_update_info_contract", "FAILED", "SSH_TIMEOUT", STALE_AT));

        var snapshot = new FleetSnapshot(List.of(), Map.of("d-fresh", rFresh, "d-stale", rStale, "d-failed", rFailed),
                Map.of());
        ReportDocument doc = build(List.of(dFresh, dStale, dFailed, dNone), snapshot, Map.of());

        PlaneAsOf lp = plane(doc, "lifecycle");
        assertEquals(2, lp.covered());
        assertEquals(4, lp.total());
        assertEquals(1, lp.stale());
        assertEquals(FRESH_AT, lp.newest());
        assertEquals(STALE_AT, lp.oldest());

        var k = kpis(doc, "coverage");
        assertEquals("2 of 4", k.get("Devices with collected lifecycle read"));
        assertEquals("1", k.get("Devices with no lifecycle read"));
        assertEquals("1", k.get("Devices with failed or unparsed read"));
        assertEquals("2 h", k.get("Newest lifecycle read age"));
        assertEquals("1", k.get("Stale lifecycle reads (older than 24 h)"));
        assertEquals("OK", tone(doc, "coverage", "Newest lifecycle read age"));

        assertEquals(List.of(
                List.of(JULIET, "Fortinet", "firewall", "UNKNOWN", "fgt_update_info_contract: FAILED (SSH_TIMEOUT)",
                        STALE_AT.toString(), "30 h"),
                List.of(TANGO, "Cisco ASA", "firewall", "NO READ", "No lifecycle read recorded", "UNKNOWN", "UNKNOWN")),
                rows(doc, "no_lifecycle_read"));

        assertEquals(List.of(
                List.of(BRAVO, "Check Point", "CURRENT", "cp_cplic_print, cp_cplp_list", FRESH_AT.toString(), "2 h"),
                List.of(ECHO, "Palo Alto", "STALE", "pan_license_info", STALE_AT.toString(), "30 h")),
                rows(doc, "lifecycle_read_freshness"));
    }

    @Test
    void emptyEstateIsUnknownEverywhereAndEverySectionStatesDataAgeAndCoverage() {
        ReportDocument doc = build(List.of(), FleetSnapshot.EMPTY, Map.of());
        assertTrue(doc.notices().stream().anyMatch(n -> n.startsWith("NO EVIDENCE:")));
        assertNull(plane(doc, "lifecycle").newest());
        assertEquals("UNKNOWN", kpis(doc, "coverage").get("Newest lifecycle read age"));
        assertEquals(4, doc.sections().size());
        for (ReportDocument.Section s : doc.sections()) {
            assertFalse(s.dataAsOf().isEmpty(), s.id() + " must declare its PlaneAsOf");
            assertTrue(s.notes().stream().anyMatch(n -> n.startsWith("Data age and coverage (")),
                    s.id() + " must state data age and coverage in its notes");
            for (ReportDocument.Kpi kpi : s.kpis()) {
                assertFalse("OK".equals(kpi.tone()), s.id() + " KPI '" + kpi.label() + "' must not be OK without devices");
            }
        }
    }

    // ---- 5. Masking & PDF / XLSX / CSV renderers ---------------------------------------------------------------

    @Test
    void maskedProjectionRendersCleanPdfXlsxAndCsvWithAllFourSectionsAndZeroRawLeaks() throws Exception {
        ReportDocument unprojected = new LifecycleLicensingBuilder()
                .build(context(ReportTemplate.RPT_17), new ReportFixtures.Data());
        ReportDocument masked = ReportFixtures.masker().project(unprojected, ProjectionMode.MASKED);

        // PDF
        byte[] pdf = new PdfReportRenderer().render(masked);
        assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.ISO_8859_1));
        PdfReader reader = new PdfReader(pdf);
        StringBuilder pdfText = new StringBuilder();
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p <= reader.getNumberOfPages(); p++) pdfText.append(extractor.getTextFromPage(p)).append('\n');
        reader.close();
        for (String expected : List.of("RPT-17", "Lifecycle and licensing", "Projection: MASKED (AIView pseudonyms)",
                "Licences, subscriptions and support contracts", "Advisory exposure (CISA KEV and critical/high)",
                "Live Patch and hotfix currency", "Lifecycle read coverage and freshness",
                "EXPIRED", "WITHIN 30 DAYS", "EXPLOITED", "NO READ", "FW-")) {
            assertTrue(pdfText.toString().contains(expected), "PDF lacks: " + expected);
        }

        // XLSX
        byte[] xlsx1 = new XlsxReportRenderer().render(masked);
        byte[] xlsx2 = new XlsxReportRenderer().render(masked);
        assertArrayEquals(xlsx1, xlsx2, "XLSX output must be deterministic");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx1))) {
            assertEquals(7, wb.getNumberOfSheets(), "Summary + 6 tables across the 4 sections");
            assertEquals("Summary", wb.getSheetName(0));
            assertEquals("RPT-17 Lifecycle and licensing", wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            for (var sheet : wb) for (var row : sheet) for (var cell : row) {
                assertEquals(CellType.STRING, cell.getCellType());
            }
        }

        // CSV
        String csv = new String(new CsvReportRenderer().render(masked), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\"Report\",\"RPT-17 Lifecycle and licensing\""));
        assertTrue(csv.contains("\"Data as of: lifecycle\""));
        assertTrue(csv.contains("\"Data as of: inventory\""));
        assertTrue(csv.contains("FW-"));

        // Byte-level leak check across all three formats
        for (ReportFormat fmt : List.of(ReportFormat.PDF, ReportFormat.XLSX, ReportFormat.CSV)) {
            byte[] bytes = switch (fmt) {
                case PDF -> pdf;
                case XLSX -> xlsx1;
                case CSV -> csv.getBytes(StandardCharsets.UTF_8);
                default -> throw new AssertionError();
            };
            String scanned = ReportMaskingBytesTest.scan(fmt, bytes);
            for (String raw : RAW_VALUES) {
                assertFalse(scanned.contains(raw), "RPT-17 " + fmt + " leaked raw value: " + raw);
            }
        }
    }
}
