package com.securityexpert.nexus.ui2.service.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskHeadroomPolicyTest {

    private static final DiskHeadroomPolicy POLICY = DiskHeadroomPolicy.DEFAULT;

    @Test
    void defaultsAreEightyAndNinetyPercentWithTwoPointsOfHysteresis() {
        assertEquals(80, POLICY.warningPercent());
        assertEquals(90, POLICY.criticalPercent());
        assertEquals(2, POLICY.hysteresisPercent());
    }

    @Test
    void classifyAppliesTheThresholdsInclusively() {
        assertEquals(DiskLevel.OK, POLICY.classify(0));
        assertEquals(DiskLevel.OK, POLICY.classify(72));
        assertEquals(DiskLevel.OK, POLICY.classify(79));
        assertEquals(DiskLevel.WARNING, POLICY.classify(80));
        assertEquals(DiskLevel.WARNING, POLICY.classify(89));
        assertEquals(DiskLevel.CRITICAL, POLICY.classify(90));
        assertEquals(DiskLevel.CRITICAL, POLICY.classify(100));
    }

    @Test
    void thresholdsAreConfigurable() {
        DiskHeadroomPolicy tight = new DiskHeadroomPolicy(60, 75, 1);
        assertEquals(DiskLevel.WARNING, tight.classify(60));
        assertEquals(DiskLevel.CRITICAL, tight.classify(75));
    }

    @Test
    void invalidThresholdsAreRefusedAndFallBackToDefaultsWithAComplaint() {
        assertThrows(IllegalArgumentException.class, () -> new DiskHeadroomPolicy(90, 80, 2));
        assertThrows(IllegalArgumentException.class, () -> new DiskHeadroomPolicy(80, 80, 2));
        assertThrows(IllegalArgumentException.class, () -> new DiskHeadroomPolicy(0, 90, 0));
        assertThrows(IllegalArgumentException.class, () -> new DiskHeadroomPolicy(80, 101, 2));
        assertThrows(IllegalArgumentException.class, () -> new DiskHeadroomPolicy(80, 90, -1));
        assertThrows(IllegalArgumentException.class, () -> new DiskHeadroomPolicy(80, 90, 80));
        List<String> complaints = new ArrayList<>();
        assertEquals(DiskHeadroomPolicy.DEFAULT, DiskHeadroomPolicy.orDefault(95, 50, 2, complaints::add));
        assertEquals(1, complaints.size());
        assertTrue(complaints.get(0).contains("using the defaults"));
        assertEquals(new DiskHeadroomPolicy(70, 85, 3), DiskHeadroomPolicy.orDefault(70, 85, 3, complaints::add));
        assertEquals(1, complaints.size());
    }

    @Test
    void settleHoldsALevelUntilUsageFallsBelowItsHysteresisBand() {
        // Rising: no memory needed.
        assertEquals(DiskLevel.OK, POLICY.settle(null, 79));
        assertEquals(DiskLevel.WARNING, POLICY.settle(DiskLevel.OK, 80));
        assertEquals(DiskLevel.CRITICAL, POLICY.settle(DiskLevel.WARNING, 90));
        assertEquals(DiskLevel.CRITICAL, POLICY.settle(DiskLevel.OK, 95));
        // Falling out of critical: held through 88, released at 87, to WARNING and not straight to OK.
        assertEquals(DiskLevel.CRITICAL, POLICY.settle(DiskLevel.CRITICAL, 88));
        assertEquals(DiskLevel.WARNING, POLICY.settle(DiskLevel.CRITICAL, 87));
        assertEquals(DiskLevel.WARNING, POLICY.settle(DiskLevel.CRITICAL, 78));
        assertEquals(DiskLevel.OK, POLICY.settle(DiskLevel.CRITICAL, 77));
        // Falling out of warning: held through 78, released at 77.
        assertEquals(DiskLevel.WARNING, POLICY.settle(DiskLevel.WARNING, 79));
        assertEquals(DiskLevel.WARNING, POLICY.settle(DiskLevel.WARNING, 78));
        assertEquals(DiskLevel.OK, POLICY.settle(DiskLevel.WARNING, 77));
        // Not held by an UNKNOWN memory.
        assertEquals(DiskLevel.OK, POLICY.settle(DiskLevel.UNKNOWN, 79));
    }

    @Test
    void usedPercentFollowsDfAndRoundsUp() {
        // total 1000, free 300 (used 700), usable 280 (root-reserved 20): df shows 700 / 980 = 71.4 -> 72.
        DiskVolumeReading reading = DiskVolumeReading.measured("root", 1000, 300, 280, POLICY);
        assertEquals(72, reading.usedPercent());
        assertEquals(DiskLevel.OK, reading.level());
        assertEquals(80, DiskVolumeReading.measured("root", 1000, 200, 200, POLICY).usedPercent());
        assertEquals(DiskLevel.WARNING, DiskVolumeReading.measured("root", 1000, 200, 200, POLICY).level());
        assertEquals(DiskLevel.CRITICAL, DiskVolumeReading.measured("root", 1000, 99, 99, POLICY).level());
        assertEquals(100, DiskVolumeReading.measured("root", 1000, 0, 0, POLICY).usedPercent());
    }

    @Test
    void anUnmeasurableVolumeIsUnknownNotFull() {
        DiskVolumeReading zero = DiskVolumeReading.measured("root", 0, 0, 0, POLICY);
        assertEquals(DiskLevel.UNKNOWN, zero.level());
        assertNull(zero.usedPercent());
        assertEquals(DiskLevel.OK, DiskLevel.worst(DiskLevel.OK, DiskLevel.UNKNOWN));
        assertEquals(DiskLevel.CRITICAL, DiskLevel.worst(DiskLevel.UNKNOWN, DiskLevel.CRITICAL));
        assertEquals(DiskLevel.CRITICAL, DiskLevel.worst(DiskLevel.WARNING, DiskLevel.CRITICAL));
    }

    @Test
    void payloadNamesEachVolumeWithItsPercentageAndTheWorstLevel(@TempDir Path dir) throws IOException {
        Path artefacts = Files.createDirectory(dir.resolve("artefacts"));
        Path missing = dir.resolve("not-mounted");
        Path broken = Files.createDirectory(dir.resolve("broken"));
        Map<String, Path> volumes = new LinkedHashMap<>();
        volumes.put("root", dir);
        volumes.put("artefact_store", artefacts);
        volumes.put("database", missing);
        volumes.put("broken_volume", broken);
        DiskHeadroomService service = new DiskHeadroomService(POLICY, volumes, path -> {
            if (path.equals(dir)) return new long[] {1000, 280, 280};
            if (path.equals(artefacts)) return new long[] {1000, 70, 70};
            throw new IOException("synthetic read failure");
        });
        List<DiskVolumeReading> readings = service.measure();
        assertEquals(List.of("root", "artefact_store", "broken_volume"), readings.stream().map(DiskVolumeReading::name).toList());
        Map<String, Object> payload = DiskHeadroomService.payload(POLICY, readings);
        assertEquals("critical", payload.get("level"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("volumes");
        assertEquals(72, rows.get(0).get("used_percent"));
        assertEquals("ok", rows.get(0).get("level"));
        assertEquals(93, rows.get(1).get("used_percent"));
        assertEquals("critical", rows.get(1).get("level"));
        assertEquals("unknown", rows.get(2).get("level"));
        assertNull(rows.get(2).get("used_percent"));
        assertEquals(Map.of("warning_percent", 80, "critical_percent", 90), payload.get("thresholds"));
    }

    @Test
    void anUnknownVolumeAloneNeverMakesTheOverallLevelBad(@TempDir Path dir) {
        DiskHeadroomService service = new DiskHeadroomService(POLICY, Map.of("root", dir), path -> {
            throw new IOException("synthetic read failure");
        });
        assertEquals("ok", service.payload().get("level"));
    }
}
