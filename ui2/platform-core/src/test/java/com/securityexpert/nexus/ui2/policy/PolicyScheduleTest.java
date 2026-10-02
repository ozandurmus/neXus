package com.securityexpert.nexus.ui2.policy;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;

class PolicyScheduleTest {
    private PolicySchedule parse(String content) {
        return PolicySchedule.panorama(PolicyXml.parse("<entry><schedule-type>" + content + "</schedule-type></entry>").getDocumentElement());
    }
    @Test void dailyWeeklyAndMultipleNonRecurringIntervalsPreserveGaps() {
        var daily = parse("<recurring><daily><member>08:00-18:00</member></daily></recurring>");
        assertEquals("active", daily.status(Instant.parse("2026-10-02T12:00:00Z")));
        assertEquals("upcoming", daily.status(Instant.parse("2026-10-02T20:00:00Z")));
        var weekly = parse("<recurring><weekly><monday><member>08:00-18:00</member></monday></weekly></recurring>");
        assertEquals(List.of(1), weekly.windows().get(0).days());
        assertEquals("upcoming", weekly.status(Instant.parse("2026-10-02T12:00:00Z")));
        var once = parse("<non-recurring><member>2026/10/01@00:00-2026/10/02@00:00</member><member>2026/10/04@00:00-2026/10/05@00:00</member></non-recurring>");
        assertEquals("upcoming", once.status(Instant.parse("2026-10-03T12:00:00Z")));
        assertEquals("active", once.status(Instant.parse("2026-10-04T12:00:00Z")));
        assertEquals("expired", once.status(Instant.parse("2026-10-06T12:00:00Z")));
        assertTrue(once.expiring(Instant.parse("2026-10-03T12:00:00Z")));
        assertFalse(once.timezoneKnown());
    }
    @Test void monthlyDeviceTimezoneAndUnknownShapes() {
        var monthly = new PolicySchedule("recurring", null, null, List.of(new PolicySchedule.Window(List.of(), List.of(2, 15), "08:00", "18:00")), "Europe/Istanbul", true);
        assertEquals("active", monthly.status(Instant.parse("2026-10-02T05:00:00Z")));
        assertEquals("upcoming", monthly.status(Instant.parse("2026-10-03T05:00:00Z")));
        assertEquals("unknown", parse("<unsupported/>").status(Instant.now()));
        assertEquals("unknown", parse("<recurring><daily><member>invalid</member></daily></recurring>").status(Instant.now()));
    }
    @Test void storedLocalProjectionRetainsScheduleButDropsUnrelatedConfiguration() {
        var xml = "<config><shared><schedule><entry name='OBJ-TIME-01'><schedule-type><recurring><daily><member>08:00-18:00</member></daily></recurring></schedule-type></entry></schedule><unrelated>withheld</unrelated></shared></config>";
        var parsed = PolicyXml.parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)), true);
        assertEquals(1, PolicyXml.selectRelative(parsed.getDocumentElement(), "shared/schedule/entry").size());
        assertTrue(PolicyXml.selectRelative(parsed.getDocumentElement(), "shared/unrelated").isEmpty());
    }
}
