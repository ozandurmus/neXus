package com.securityexpert.nexus.ui2.service.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogEntry;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleVendorRepository.VendorEntry;

class LifecycleMatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    private static VendorEntry vendor(String origin, String vendor, String kind, String key, LocalDate support, long ageDays, String state) {
        String source = "PALOALTO".equals(vendor) ? "paloalto_eol" : "CHECKPOINT".equals(vendor) ? "checkpoint_lifecycle" : "cisco_eol";
        String ref = "PALOALTO".equals(vendor) ? ("HARDWARE".equals(kind) ? "paloalto-hardware-eol" : "paloalto-panos-eol-summary") : "EOL00001";
        String url = "PALOALTO".equals(vendor)
                ? "https://www.paloaltonetworks.com/services/support/end-of-life-announcements/hardware-end-of-life-dates"
                : "https://www.cisco.com/c/en/us/products/collateral/security/x/y.html";
        return new VendorEntry(origin + ":" + key + ":" + ageDays + ":" + support, origin, source, "OPERATOR_PAGE".equals(origin) ? "up-12345678" : null,
                vendor, kind, key, key + " (" + ref + ")", null, null, "PARSED".equals(state) ? support : null, null, null, List.of(), state,
                "PARSED".equals(state) ? null : "MILESTONE_CONFLICT", ref, url,
                "0".repeat(64), NOW.minus(Duration.ofDays(ageDays)));
    }

    private static LifecycleCatalogEntry operator(String vendor, String kind, String product, LocalDate support, long ageDays) {
        return new LifecycleCatalogEntry("op-" + product, vendor, kind, product, null, support, null, "MANUAL", "synthetic note",
                "synthetic-actor", NOW.minus(Duration.ofDays(ageDays)));
    }

    private static Map<String, Object> match(String vendor, String kind, String observed, List<LifecycleCatalogEntry> op, List<VendorEntry> v) {
        return LifecycleMatcher.match(vendor, kind, false, "HARDWARE".equals(kind) ? observed : null, observed,
                new LifecycleMatcher.Catalog(op, v, NOW));
    }

    @Test
    void keysFollowTheWrittenRulesAndUnknownFormsGiveNoKey() {
        assertEquals("FPR4115", LifecycleKeys.model("CISCO_ASA", "FPR-4115"));
        assertEquals("FPR4115", LifecycleKeys.model("CISCO_ASA", "Cisco Firepower 4115 Threat Defense"));
        assertEquals("ASA5516", LifecycleKeys.model("CISCO_ASA", "ASA5516-X"));
        assertEquals("6600", LifecycleKeys.model("CHECKPOINT", "Check Point 6600"));
        assertEquals("1595R", LifecycleKeys.model("CHECKPOINT", "1595R Appliance"));
        assertNull(LifecycleKeys.model("CHECKPOINT", "Check Point 1570/1590 Appliances"), "two models in one text is not one key");
        assertEquals("PA-3220", LifecycleKeys.model("PALOALTO", "PA-3220"));
        assertEquals("PA-3220", LifecycleKeys.model("PALOALTO", "PAN-PA-3220-ZTP"));
        assertEquals("PA-5445", LifecycleKeys.model("PALOALTO", "PA-5445"));
        assertEquals("PA-5410", LifecycleKeys.model("PALOALTO", "PA-5410"));
        assertEquals("PA-3440", LifecycleKeys.model("PALOALTO", "PA-3440"));
        assertEquals("PA-5450", LifecycleKeys.model("PALOALTO", "PAN-PA-5450-AC-SYS"));
        assertEquals("M-200", LifecycleKeys.model("PALOALTO", "M-200"));
        assertEquals("M-300", LifecycleKeys.model("PALOALTO", "M-300"));
        assertEquals("M-600", LifecycleKeys.model("PALOALTO", "M-600"));
        assertEquals("M-700", LifecycleKeys.model("PALOALTO", "M-700"));
        assertEquals("M-600", LifecycleKeys.model("PALOALTO", "Panorama M-600"));
        assertEquals("Panorama", LifecycleKeys.model("PALOALTO", "Panorama"));
        assertEquals("Panorama", LifecycleKeys.model("PALOALTO", "Panorama VM"));
        assertEquals("Panorama", LifecycleKeys.model("PALOALTO", "Panorama-VM"));
        assertEquals("Panorama", LifecycleKeys.model("PALOALTO", "Panorama on VM"));
        assertEquals("Panorama", LifecycleKeys.model("PALOALTO", "Panorama (VM)"));
        assertEquals("Panorama", LifecycleKeys.model("PALOALTO", "PAN-PRA-VM"));
        assertEquals("FG600E", LifecycleKeys.model("FORTINET", "FortiGate-600E"));
        assertEquals("FG60F", LifecycleKeys.model("FORTINET", "FGT60F"));
        assertEquals("FMG400G", LifecycleKeys.model("FORTINET", "FortiManager-400G"));
        assertNull(LifecycleKeys.model("FORTINET", "FortiGate-VM64"));
        assertEquals("Example Appliance", LifecycleKeys.model("RADWARE", " Example  Appliance "), "unruled vendors keep the text");

        assertEquals("R81.20", LifecycleKeys.deviceTrain("CHECKPOINT", false, null, "R81.20"));
        assertEquals("R82", LifecycleKeys.deviceTrain("CHECKPOINT", true, null, "R82"));
        assertEquals("R81.10.XX", LifecycleKeys.deviceTrain("CHECKPOINT", false, null, "R81.10.05"), "Spark firmware has its own train");
        assertEquals("R81.10.XX", LifecycleKeys.catalogTrain("CHECKPOINT", "R81.10.xx"));
        assertEquals("PAN-OS 11.1", LifecycleKeys.deviceTrain("PALOALTO", false, null, "11.1.10-h7"));
        assertEquals("PAN-OS 12.1", LifecycleKeys.deviceTrain("PALOALTO", true, null, "12.1.10"));
        assertEquals("ASA 9.18", LifecycleKeys.deviceTrain("CISCO_ASA", false, null, "9.18(4)50"));
        assertEquals("ASA 9.22", LifecycleKeys.deviceTrain("CISCO_ASA", false, null, "9.22(3)200"));
        assertEquals("FortiOS 7.4", LifecycleKeys.deviceTrain("FORTINET", false, "FortiGate-600E", "v7.4.12 build2902"));
        assertEquals("FortiManager 7.4", LifecycleKeys.deviceTrain("FORTINET", true, null, "v7.4.11-build2804"));
        assertEquals("FortiManager 7.4", LifecycleKeys.deviceTrain("FORTINET", false, "FMG-VM64", "v7.4.11-build2804"));
        assertNull(LifecycleKeys.deviceTrain("PALOALTO", false, null, "11.1.10-c3"));
        assertNull(LifecycleKeys.catalogTrain("FORTINET", "7.4"), "a Fortinet train names its family");
    }

    @Test
    void aFreshVendorEntryMatchesWithItsProvenance() {
        var result = match("CISCO_ASA", "HARDWARE", "FPR-4115", List.of(), List.of(vendor("FEED", "CISCO_ASA", "HARDWARE", "FPR4115",
                LocalDate.of(2031, 1, 31), 3, "PARSED")));
        assertEquals("MATCHED", result.get("status"));
        assertEquals("model key · Cisco bulletin EOL00001 (online)", result.get("basis"));
        @SuppressWarnings("unchecked") var catalog = (Map<String, Object>) result.get("catalog");
        assertEquals("2031-01-31", catalog.get("end_of_support"));
        assertEquals(3L, catalog.get("age_days"));
        assertEquals("FEED", catalog.get("origin"));
    }

    @Test
    void aStaleSourceMakesThePartNotEvaluatedNeverMatched() {
        var stale = vendor("FEED", "CISCO_ASA", "SOFTWARE", "ASA 9.18", LocalDate.of(2027, 11, 30), 31, "PARSED");
        var result = match("CISCO_ASA", "SOFTWARE", "9.18(4)50", List.of(), List.of(stale));
        assertEquals("NOT_EVALUATED", result.get("status"));
        assertTrue(((String) result.get("reason")).contains("31 days ago (limit 30)"));
        assertNull(result.get("catalog"));
        var fresh30 = vendor("FEED", "CISCO_ASA", "SOFTWARE", "ASA 9.18", LocalDate.of(2027, 11, 30), 30, "PARSED");
        assertEquals("MATCHED", match("CISCO_ASA", "SOFTWARE", "9.18(4)50", List.of(), List.of(fresh30)).get("status"));
        var uploaded = vendor("OPERATOR_PAGE", "CISCO_ASA", "SOFTWARE", "ASA 9.18", LocalDate.of(2027, 11, 30), 100, "PARSED");
        assertEquals("MATCHED", match("CISCO_ASA", "SOFTWARE", "9.18(4)50", List.of(), List.of(uploaded)).get("status"), "an upload is fresh for 180 days");
        var oldUpload = vendor("OPERATOR_PAGE", "CISCO_ASA", "SOFTWARE", "ASA 9.18", LocalDate.of(2027, 11, 30), 181, "PARSED");
        assertEquals("NOT_EVALUATED", match("CISCO_ASA", "SOFTWARE", "9.18(4)50", List.of(), List.of(oldUpload)).get("status"));
    }

    @Test
    void reviewAndDisagreementGiveNoDates() {
        var review = vendor("FEED", "CISCO_ASA", "HARDWARE", "FPR2130", null, 1, "NEEDS_REVIEW");
        var result = match("CISCO_ASA", "HARDWARE", "FPR-2130", List.of(), List.of(review));
        assertEquals("NEEDS_REVIEW", result.get("status"));
        assertNull(result.get("catalog"));
        assertTrue(((String) result.get("reason")).contains("MILESTONE_CONFLICT"));
        var a = vendor("FEED", "CISCO_ASA", "HARDWARE", "FPR2130", LocalDate.of(2030, 1, 1), 1, "PARSED");
        var b = vendor("OPERATOR_PAGE", "CISCO_ASA", "HARDWARE", "FPR2130", LocalDate.of(2031, 1, 1), 1, "PARSED");
        assertEquals("NEEDS_REVIEW", match("CISCO_ASA", "HARDWARE", "FPR-2130", List.of(), List.of(a, b)).get("status"));
        var same = vendor("OPERATOR_PAGE", "CISCO_ASA", "HARDWARE", "FPR2130", LocalDate.of(2030, 1, 1), 1, "PARSED");
        var agreed = match("CISCO_ASA", "HARDWARE", "FPR-2130", List.of(), List.of(same, a));
        assertEquals("MATCHED", agreed.get("status"));
        assertTrue(((String) agreed.get("basis")).endsWith("(online)"), "the online entry is shown when both agree");
    }

    @Test
    void anOperatorEntryWinsAndAgesToo() {
        var feed = vendor("FEED", "CISCO_ASA", "SOFTWARE", "ASA 9.18", LocalDate.of(2027, 11, 30), 1, "PARSED");
        var op = operator("CISCO_ASA", "SOFTWARE", "9.18", LocalDate.of(2028, 6, 30), 10);
        var result = match("CISCO_ASA", "SOFTWARE", "9.18(4)22", List.of(op), List.of(feed));
        assertEquals("MATCHED", result.get("status"));
        assertEquals("version train · operator entry", result.get("basis"));
        @SuppressWarnings("unchecked") var catalog = (Map<String, Object>) result.get("catalog");
        assertEquals("2028-06-30", catalog.get("end_of_support"));
        var old = operator("CISCO_ASA", "SOFTWARE", "9.18", LocalDate.of(2028, 6, 30), 181);
        assertEquals("NOT_EVALUATED", match("CISCO_ASA", "SOFTWARE", "9.18(4)22", List.of(old), List.of(feed)).get("status"));
        var twin = operator("CISCO_ASA", "SOFTWARE", "ASA 9.18", LocalDate.of(2028, 6, 30), 1);
        assertEquals("Ambiguous catalog matches", match("CISCO_ASA", "SOFTWARE", "9.18(4)22", List.of(op, twin), List.of()).get("reason"));
    }

    @Test
    void absenceIsNoDataOnlyWhenASourceCoversTheVendorElseNotEvaluated() {
        var other = vendor("FEED", "CISCO_ASA", "HARDWARE", "FPR4115", LocalDate.of(2031, 1, 31), 1, "PARSED");
        var covered = match("CISCO_ASA", "HARDWARE", "FPR-3110", List.of(), List.of(other));
        assertEquals("NO_LIFECYCLE_DATA", covered.get("status"));
        assertEquals("No catalog match (no end-of-life bulletin names this model)", covered.get("reason"));
        var nothing = match("FORTINET", "HARDWARE", "FortiGate-600E", List.of(), List.of(other));
        assertEquals("NOT_EVALUATED", nothing.get("status"));
        assertTrue(((String) nothing.get("reason")).startsWith("No lifecycle source loaded for FORTINET hardware"));
        var unknownForm = match("CISCO_ASA", "HARDWARE", "Example Appliance", List.of(), List.of(other));
        assertEquals("NO_LIFECYCLE_DATA", unknownForm.get("status"));
        assertEquals("Model/version form not recognised for lifecycle matching", unknownForm.get("reason"));
        assertEquals("Model/version missing", match("CISCO_ASA", "HARDWARE", null, List.of(), List.of(other)).get("reason"));
    }

    @Test
    void theAgeRuleIsOffWithoutAClock() {
        var old = operator("CHECKPOINT", "HARDWARE", "6600", LocalDate.of(2031, 1, 1), 5000);
        var result = LifecycleMatcher.match("CHECKPOINT", "HARDWARE", false, "6600", "6600", LifecycleMatcher.Catalog.operatorOnly(List.of(old)));
        assertEquals("MATCHED", result.get("status"));
    }

    @Test
    void paloAltoCurrentHardwareModelsReportNotAnnouncedWhenVendorPageIsFresh() {
        var pa5250 = vendor("FEED", "PALOALTO", "HARDWARE", "PA-5250", LocalDate.of(2028, 7, 31), 0, "PARSED");
        var pa5220 = vendor("FEED", "PALOALTO", "HARDWARE", "PA-5220", LocalDate.of(2028, 7, 31), 0, "PARSED");
        var catalog = List.of(pa5250, pa5220);

        var matched5250 = match("PALOALTO", "HARDWARE", "PA-5250", List.of(), catalog);
        assertEquals("MATCHED", matched5250.get("status"));
        assertEquals("model key · Palo Alto end-of-life page (online)", matched5250.get("basis"));
        @SuppressWarnings("unchecked") var cat5250 = (Map<String, Object>) matched5250.get("catalog");
        assertEquals("2028-07-31", cat5250.get("end_of_support"));

        for (String currentModel : List.of("PA-5445", "PA-5410", "PA-3440", "M-300", "M-700")) {
            var res = match("PALOALTO", "HARDWARE", currentModel, List.of(), catalog);
            assertEquals("MATCHED", res.get("status"), currentModel);
            assertEquals("No end-of-life announced for this model on the vendor page (checked 2026-11-13)", res.get("basis"), currentModel);
            assertEquals("No end-of-life announced for this model on the vendor page (checked 2026-11-13)", res.get("reason"), currentModel);
            @SuppressWarnings("unchecked") var cat = (Map<String, Object>) res.get("catalog");
            assertNotNull(cat, currentModel);
            assertEquals("Not announced", cat.get("end_of_support"), currentModel);
            assertEquals("Not announced", cat.get("end_of_sale"), currentModel);
            assertEquals("Not announced", cat.get("end_of_engineering"), currentModel);
            assertEquals("paloalto_eol", cat.get("source"), currentModel);
        }
    }

    @Test
    void panoramaVirtualAndMSeriesHardwareAndSoftwareMatching() {
        var m200 = vendor("FEED", "PALOALTO", "HARDWARE", "M-200", LocalDate.of(2028, 11, 15), 0, "PARSED");
        var m600 = vendor("FEED", "PALOALTO", "HARDWARE", "M-600", LocalDate.of(2028, 11, 15), 0, "PARSED");
        var pan121 = vendor("FEED", "PALOALTO", "SOFTWARE", "PAN-OS 12.1", LocalDate.of(2028, 8, 28), 0, "PARSED");
        var catalog = new LifecycleMatcher.Catalog(List.of(), List.of(m200, m600, pan121), NOW);

        for (String vmForm : List.of("Panorama", "Panorama VM", "Panorama-VM", "Panorama on VM", "Panorama (VM)")) {
            var hwVm = LifecycleMatcher.match("PALOALTO", "HARDWARE", true, vmForm, vmForm, catalog);
            assertEquals("MATCHED", hwVm.get("status"), vmForm);
            assertEquals("Panorama", hwVm.get("key"), vmForm);
            @SuppressWarnings("unchecked") var hwCat = (Map<String, Object>) hwVm.get("catalog");
            assertEquals("Not applicable", hwCat.get("end_of_support"), vmForm);
            assertEquals("Not applicable", hwCat.get("end_of_sale"), vmForm);

            var swVm = LifecycleMatcher.match("PALOALTO", "SOFTWARE", true, vmForm, "12.1.10", catalog);
            assertEquals("MATCHED", swVm.get("status"), vmForm);
            assertEquals("PAN-OS 12.1", swVm.get("key"), vmForm);
            @SuppressWarnings("unchecked") var swCat = (Map<String, Object>) swVm.get("catalog");
            assertEquals("2028-08-28", swCat.get("end_of_support"), vmForm);
        }

        for (String listedM : List.of("M-200", "M-600")) {
            var hwListed = LifecycleMatcher.match("PALOALTO", "HARDWARE", true, listedM, listedM, catalog);
            assertEquals("MATCHED", hwListed.get("status"), listedM);
            assertEquals("model key · Palo Alto end-of-life page (online)", hwListed.get("basis"), listedM);
            @SuppressWarnings("unchecked") var cat = (Map<String, Object>) hwListed.get("catalog");
            assertEquals("2028-11-15", cat.get("end_of_support"), listedM);
        }

        for (String currentM : List.of("M-300", "M-700")) {
            var hwCurrent = LifecycleMatcher.match("PALOALTO", "HARDWARE", true, currentM, currentM, catalog);
            assertEquals("MATCHED", hwCurrent.get("status"), currentM);
            assertEquals("No end-of-life announced for this model on the vendor page (checked 2026-11-13)", hwCurrent.get("basis"), currentM);
            @SuppressWarnings("unchecked") var cat = (Map<String, Object>) hwCurrent.get("catalog");
            assertEquals("Not announced", cat.get("end_of_support"), currentM);
        }
    }
}
