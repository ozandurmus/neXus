package com.securityexpert.nexus.ui2.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class NotificationUnitsTest {

    private static NotificationSettings settings(boolean syslog, String host, boolean smtp, String from, String to) {
        return new NotificationSettings(syslog, host, 514, "udp", 16, smtp, "relay.example.test", 25, true, from, to,
                false, false, null);
    }

    @Test
    void syslogLinesAreRfc5424WithPriorityFromFacilityAndSeverity() {
        String line = SyslogSender.format(16, SyslogSender.Severity.ERROR, Instant.parse("2026-09-22T20:00:00Z"),
                "ui2-service-abc", "JOB_FAILED", "job failed\nsecond line");
        assertEquals("<131>1 2026-09-22T20:00:00Z ui2-service-abc nexus - JOB_FAILED - job failed second line", line);
    }

    @Test
    void settingsAreRefusedWhenAnEnabledChannelIsIncomplete() {
        assertTrue(settings(true, "", false, null, null).problems().contains("syslog is enabled but has no host"));
        assertTrue(settings(false, null, true, "nexus@example.test", "").problems().get(0).startsWith("SMTP is enabled"));
        assertTrue(settings(false, null, false, null, "not-an-address").problems().contains("not an e-mail address: not-an-address"));
        assertEquals(List.of(), settings(true, "192.0.2.10", true, "nexus@example.test", "a@example.test, b@example.test").problems());
    }

    @Test
    void recipientsSplitOnCommasSemicolonsAndSpace() {
        assertEquals(List.of("a@example.test", "b@example.test", "c@example.test"),
                settings(false, null, false, null, "a@example.test; b@example.test c@example.test").recipients());
    }
}
