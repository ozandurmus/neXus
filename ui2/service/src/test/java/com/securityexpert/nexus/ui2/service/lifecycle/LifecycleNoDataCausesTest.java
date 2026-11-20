package com.securityexpert.nexus.ui2.service.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The reasons a device gets no lifecycle data, and the model/version spellings that used to be among them (synthetic strings). */
class LifecycleNoDataCausesTest {

    private static Map<String, Object> noSource(String vendor, String kind, String observed) {
        return LifecycleMatcher.match(vendor, kind, false, "HARDWARE".equals(kind) ? observed : null, observed,
                LifecycleMatcher.Catalog.operatorOnly(List.of()));
    }

    @Test void checkPointModelWithPlatformSuffixHasAKey() {
        assertEquals("6600", LifecycleKeys.model("CHECKPOINT", "Check Point 6600 (CPAP-SG6600)"));
        assertEquals("SMART-1 5150", LifecycleKeys.model("CHECKPOINT", "Smart-1 5150 (ST-4150-00)"));
        assertEquals("6600", LifecycleKeys.model("CHECKPOINT", "Quantum 6600 Appliance"));
        assertEquals("1595R", LifecycleKeys.model("CHECKPOINT", "Quantum Spark 1595R"));
        assertEquals("6600", LifecycleKeys.model("CHECKPOINT", "CPAP-SG6600"));
        assertNull(LifecycleKeys.model("CHECKPOINT", "Check Point 1570/1590 Appliances"));
    }

    @Test void checkPointVersionSpellingsReachTheirTrain() {
        assertEquals("R81.20", LifecycleKeys.deviceTrain("CHECKPOINT", false, null, "Gaia R81.20"));
        assertEquals("R80.20", LifecycleKeys.deviceTrain("CHECKPOINT", false, null, "R80.20.M2"));
        assertEquals("R81.20", LifecycleKeys.deviceTrain("CHECKPOINT", false, null, "R81.20 (Take 99)"));
        assertEquals("R81.20", LifecycleKeys.deviceTrain("CHECKPOINT", false, null, "R81.20, Take 99"));
        assertNull(LifecycleKeys.deviceTrain("CHECKPOINT", false, null, "Gaia unknown"));
    }

    @Test void fortinetVersionSpellingsReachTheirTrain() {
        assertEquals("FortiOS 7.2", LifecycleKeys.deviceTrain("FORTINET", false, "FortiGate-60F", "v7.2.5,build1517,230724 (GA.M)"));
        assertEquals("FortiOS 7.0", LifecycleKeys.deviceTrain("FORTINET", false, "FortiGate-60F", "v7.0.12 (build 523)"));
        assertEquals("FortiOS 7.4", LifecycleKeys.deviceTrain("FORTINET", false, "FortiGate-60F", "FortiOS v7.4.1"));
        assertEquals("FortiManager 7.4", LifecycleKeys.deviceTrain("FORTINET", true, "FortiManager-400G", "v7.4.3-build2587 240124 (GA.M)"));
        assertNull(LifecycleKeys.deviceTrain("FORTINET", false, null, "7.4 beta"));
    }

    @Test void ciscoSpellingsReachTheirKey() {
        assertEquals("ASA 9.16", LifecycleKeys.deviceTrain("CISCO_ASA", false, null, "Cisco Adaptive Security Appliance Software Version 9.16(4)57"));
        assertEquals("ASA 9.18", LifecycleKeys.deviceTrain("CISCO_ASA", false, null, "Version 9.18(4)22"));
        assertEquals("ASA5585", LifecycleKeys.model("CISCO_ASA", "ASA5585-SSP-20"));
        assertEquals("ASA5506", LifecycleKeys.model("CISCO_ASA", "ASA5506-X with FirePOWER services"));
        assertEquals("ASA5525", LifecycleKeys.model("CISCO_ASA", "ASA5525X"));
    }

    @Test void everyReasonHasAStableCode() {
        assertEquals("MODEL_MISSING", noSource("CHECKPOINT", "HARDWARE", null).get("reason_code"));
        assertEquals("VERSION_MISSING", noSource("CHECKPOINT", "SOFTWARE", " ").get("reason_code"));
        assertEquals("MODEL_FORM_UNRECOGNISED", noSource("CHECKPOINT", "HARDWARE", "Example Appliance").get("reason_code"));
        assertEquals("VERSION_FORM_UNRECOGNISED", noSource("CHECKPOINT", "SOFTWARE", "unknown").get("reason_code"));
        assertEquals("NO_SOURCE_LOADED", noSource("CHECKPOINT", "HARDWARE", "6600").get("reason_code"));
        assertEquals("NO_SOURCE_LOADED", noSource("RADWARE", "SOFTWARE", "ExampleOS-1.2.4").get("reason_code"));
    }

    @Test void virtualAndOpenServerPlatformsSayWhy() {
        for (String model : List.of("Open Server", "FortiGate-VM64", "PA-VM", "ASAv", "VMware Virtual Platform")) {
            var vendor = model.startsWith("Forti") ? "FORTINET" : model.startsWith("PA") ? "PALOALTO" : model.startsWith("ASA") ? "CISCO_ASA" : "CHECKPOINT";
            var result = noSource(vendor, "HARDWARE", model);
            assertEquals("NO_LIFECYCLE_DATA", result.get("status"), model);
            assertEquals("VIRTUAL_PLATFORM", result.get("reason_code"), model);
        }
        assertEquals("MODEL_FORM_UNRECOGNISED", noSource("CHECKPOINT", "HARDWARE", "Example Appliance").get("reason_code"));
    }

    @Test void theRowReasonNamesEveryPartThatIsNotMatched() {
        var hw = noSource("CHECKPOINT", "HARDWARE", "6600");
        var sw = noSource("CHECKPOINT", "SOFTWARE", "unknown");
        assertEquals("Hardware: " + hw.get("reason") + "; Software: " + sw.get("reason"),
                LifecycleProjection.rowReason("NOT_EVALUATED", hw, sw));
        assertEquals("NO_SOURCE_LOADED", LifecycleProjection.rowReasonCode("NOT_EVALUATED", hw, sw));
    }
}
