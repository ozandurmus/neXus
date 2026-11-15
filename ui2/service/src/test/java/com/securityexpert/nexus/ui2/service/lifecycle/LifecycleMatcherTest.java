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
        return new VendorEntry(origin + ":" + key + ":" + ageDays + ":" + support, origin, "cisco_eol", "OPERATOR_PAGE".equals(origin) ? "up-12345678" : null,
                vendor, kind, key, key + " (EOL00001)", null, null, "PARSED".equals(state) ? support : null, null, null, List.of(), state,
                "PARSED".equals(state) ? null : "MILESTONE_CONFLICT", "EOL00001", "https://www.cisco.com/c/en/us/products/collateral/security/x/y.html",
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
}
