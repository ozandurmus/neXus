package com.securityexpert.nexus.ui2.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.system.DiskHeadroomPolicy;
import com.securityexpert.nexus.ui2.service.system.DiskLevel;
import com.securityexpert.nexus.ui2.service.system.DiskVolumeReading;

/** Crossing and de-duplication of the disk headroom alert: once per crossing, again after recovery, no flood. */
class DiskHeadroomAlertsTest {

    private static final DiskHeadroomPolicy POLICY = DiskHeadroomPolicy.DEFAULT;
    private static final NotificationSettings SETTINGS = new NotificationSettings(true, "192.0.2.10", 514, "udp", 16,
            false, null, 25, false, null, null, false, false, null);

    private final Map<String, DiskLevel> recorded = new HashMap<>();
    private final List<String> lines = new ArrayList<>();
    private final List<SyslogSender.Severity> severities = new ArrayList<>();
    private boolean failSend;

    private final DiskHeadroomAlerts.StateStore store = new DiskHeadroomAlerts.StateStore() {
        @Override
        public DiskLevel get(String volume) {
            return recorded.get(volume);
        }

        @Override
        public void put(String volume, DiskLevel level) {
            recorded.put(volume, level);
        }
    };

    private final NotificationForwarder.SyslogSink sink = (settings, severity, msgId, message) -> {
        if (failSend) throw new IOException("synthetic send failure");
        assertEquals("DISK_HEADROOM", msgId);
        severities.add(severity);
        lines.add(message);
    };

    private static DiskVolumeReading reading(String name, int percent) {
        return DiskVolumeReading.measured(name, 1000, 1000 - percent * 10, 1000 - percent * 10, POLICY);
    }

    private int pass(DiskVolumeReading... readings) {
        return DiskHeadroomAlerts.process(SETTINGS, POLICY, List.of(readings), store, sink);
    }

    @Test
    void decideMapsEachTransitionToOneEvent() {
        assertEquals(DiskHeadroomAlerts.Event.WARNING_CROSSED, DiskHeadroomAlerts.decide(null, DiskLevel.WARNING));
        assertEquals(DiskHeadroomAlerts.Event.CRITICAL_CROSSED, DiskHeadroomAlerts.decide(DiskLevel.OK, DiskLevel.CRITICAL));
        assertEquals(DiskHeadroomAlerts.Event.CRITICAL_CROSSED, DiskHeadroomAlerts.decide(DiskLevel.WARNING, DiskLevel.CRITICAL));
        assertEquals(DiskHeadroomAlerts.Event.NONE, DiskHeadroomAlerts.decide(DiskLevel.CRITICAL, DiskLevel.WARNING));
        assertEquals(DiskHeadroomAlerts.Event.RECOVERED, DiskHeadroomAlerts.decide(DiskLevel.WARNING, DiskLevel.OK));
        assertEquals(DiskHeadroomAlerts.Event.RECOVERED, DiskHeadroomAlerts.decide(DiskLevel.CRITICAL, DiskLevel.OK));
        assertEquals(DiskHeadroomAlerts.Event.NONE, DiskHeadroomAlerts.decide(DiskLevel.OK, DiskLevel.OK));
        assertEquals(DiskHeadroomAlerts.Event.NONE, DiskHeadroomAlerts.decide(null, DiskLevel.OK));
        assertEquals(DiskHeadroomAlerts.Event.NONE, DiskHeadroomAlerts.decide(DiskLevel.WARNING, DiskLevel.UNKNOWN));
    }

    @Test
    void aHealthyVolumeSendsAndRecordsNothing() {
        assertEquals(0, pass(reading("root", 72)));
        assertTrue(lines.isEmpty());
        assertTrue(recorded.isEmpty());
    }

    @Test
    void crossingWarningAlertsOnceAndRepeatedPassesStaySilent() {
        assertEquals(1, pass(reading("root", 81)));
        assertEquals(List.of(SyslogSender.Severity.WARNING), severities);
        assertEquals("disk headroom WARNING: volume root is 81% used (warning threshold 80%)", lines.get(0));
        for (int i = 0; i < 50; i++) {
            assertEquals(0, pass(reading("root", 81 + (i % 3))));
        }
        assertEquals(1, lines.size());
        assertEquals(DiskLevel.WARNING, recorded.get("root"));
    }

    @Test
    void crossingCriticalAlertsOnceMoreAndStaysSilentWhileCritical() {
        pass(reading("root", 85));
        assertEquals(1, pass(reading("root", 91)));
        assertEquals(SyslogSender.Severity.CRITICAL, severities.get(1));
        assertEquals("disk headroom CRITICAL: volume root is 91% used (critical threshold 90%)", lines.get(1));
        assertEquals(0, pass(reading("root", 95)));
        assertEquals(0, pass(reading("root", 99)));
        assertEquals(2, lines.size());
    }

    @Test
    void aJumpStraightToCriticalSendsOnlyTheCriticalLine() {
        assertEquals(1, pass(reading("root", 93)));
        assertEquals(List.of(SyslogSender.Severity.CRITICAL), severities);
    }

    @Test
    void recoveryReArmsTheAlert() {
        pass(reading("root", 92));
        assertEquals(1, pass(reading("root", 70)));
        assertEquals(SyslogSender.Severity.INFORMATIONAL, severities.get(1));
        assertEquals("disk headroom recovered: volume root is 70% used (below the warning threshold 80%)", lines.get(1));
        assertEquals(DiskLevel.OK, recorded.get("root"));
        assertEquals(1, pass(reading("root", 92)));
        assertEquals(3, lines.size());
        assertEquals(SyslogSender.Severity.CRITICAL, severities.get(2));
    }

    @Test
    void aVolumeHoveringOnTheThresholdDoesNotFlood() {
        int sent = 0;
        int[] wobble = {79, 80, 79, 80, 81, 79, 80, 78, 79, 80};
        for (int percent : wobble) {
            sent += pass(reading("root", percent));
        }
        assertEquals(1, sent); // one warning on the first crossing; nothing while it stays inside the hysteresis band
        sent = 0;
        for (int percent : new int[] {89, 90, 89, 90, 88, 90, 89}) {
            sent += pass(reading("root", percent));
        }
        assertEquals(1, sent); // one critical, not one per wobble
    }

    @Test
    void steppingDownFromCriticalToWarningSendsNothingButReArmsCritical() {
        pass(reading("root", 92));
        assertEquals(0, pass(reading("root", 85)));
        assertEquals(DiskLevel.WARNING, recorded.get("root"));
        assertEquals(1, pass(reading("root", 91)));
        assertEquals(SyslogSender.Severity.CRITICAL, severities.get(1));
    }

    @Test
    void aFailedSendRecordsNothingAndIsRetriedOnTheNextPass() {
        failSend = true;
        assertEquals(0, pass(reading("root", 85)));
        assertNull(recorded.get("root"));
        failSend = false;
        assertEquals(1, pass(reading("root", 85)));
        assertEquals(DiskLevel.WARNING, recorded.get("root"));
        assertEquals(0, pass(reading("root", 85)));
    }

    @Test
    void anUnmeasurableVolumeChangesNothing() {
        pass(reading("root", 85));
        assertEquals(0, pass(DiskVolumeReading.unknown("root")));
        assertEquals(DiskLevel.WARNING, recorded.get("root"));
        assertEquals(1, lines.size());
    }

    @Test
    void eachVolumeIsTrackedOnItsOwn() {
        assertEquals(2, pass(reading("root", 72), reading("artefact_store", 92), reading("database", 85)));
        assertEquals(List.of("disk headroom CRITICAL: volume artefact_store is 92% used (critical threshold 90%)",
                "disk headroom WARNING: volume database is 85% used (warning threshold 80%)"), lines);
        assertFalse(recorded.containsKey("root"));
        assertEquals(0, pass(reading("root", 72), reading("artefact_store", 92), reading("database", 85)));
        assertEquals(1, pass(reading("root", 72), reading("artefact_store", 60), reading("database", 85)));
    }

    @Test
    void theLineCarriesOnlyTheVolumeNameAndPercentages() {
        pass(reading("artefact_store", 91));
        assertFalse(lines.get(0).matches(".*(/|\\d+\\.\\d+\\.\\d+\\.\\d+).*"));
    }
}
