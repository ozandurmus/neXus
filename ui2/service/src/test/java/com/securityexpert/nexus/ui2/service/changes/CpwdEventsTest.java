package com.securityexpert.nexus.ui2.service.changes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** cpwd.elg lines in the measured elg header shape; only restart/termination lines become device events. */
class CpwdEventsTest {
    private static final Instant COLLECTED = Instant.parse("2026-10-17T03:05:00Z"); // 06:05 Europe/Istanbul, 17 Oct

    private static String elg(String day, String mon, String time, String text) {
        return "[cpwd 4242 4242]@gw-lab-07[" + day + " " + mon + " " + time + "] " + text;
    }

    @Test
    void aTerminatedAndRestartLineBecomesAWarnDeviceEventInDeviceTime() {
        List<String> lines = List.of(
                elg("17", "Oct", "05:41:12", "Process FWD terminated abnormally, restarting"),
                elg("17", "Oct", "05:41:14", "Process FWD started successfully (pid=31337)"),
                "some continuation text without a header",
                elg("17", "Oct", "05:58:40", "cpd: not responding, killing process"));
        CpwdEvents.Parsed parsed = CpwdEvents.parse(lines, FeedFixtures.CP_DEVICE, "chunk-9", COLLECTED, FeedFixtures.plain());
        assertEquals(4, parsed.lines());
        assertEquals(2, parsed.events().size(), "the plain 'started' line and the headerless line are not restart events");
        ChangeEvent e = parsed.events().get(0);
        assertEquals(ChangeEvent.Kind.DEVICE_EVENT, e.kind());
        assertEquals("cp_cpwd_elg", e.source());
        assertEquals("check_point", e.vendor());
        assertEquals(FeedFixtures.CP_HOST, e.deviceName());
        assertEquals("DEVICE_LOCAL", e.timeBasis());
        assertEquals("17 Oct 05:41:12", e.deviceTime());
        assertEquals(Instant.parse("2026-10-17T02:41:12Z"), e.time(), "ordered as Europe/Istanbul");
        assertEquals("cpwd: Process FWD terminated abnormally, restarting", e.what());
        assertEquals(ChangeEvent.Tone.WARN, e.tone());
        assertEquals("cp_cpwd_elg:chunk-9:1", e.key());
        assertEquals("cp_cpwd_elg:chunk-9:4", parsed.events().get(1).key());
    }

    @Test
    void aDayAfterTheCollectionDateBelongsToThePreviousYear() {
        List<String> lines = List.of(elg("28", "Dec", "23:59:59", "Process FWD terminated abnormally"));
        ChangeEvent e = CpwdEvents.parse(lines, FeedFixtures.CP_DEVICE, "c", COLLECTED, FeedFixtures.plain()).events().get(0);
        assertEquals(Instant.parse("2025-12-28T20:59:59Z"), e.time());
    }

    @Test
    void anImpossibleDateIsLeftOutNotGuessed() {
        List<String> lines = List.of(elg("31", "Feb", "01:00:00", "Process FWD terminated"), elg("1", "Xyz", "01:00:00", "restart"));
        assertTrue(CpwdEvents.parse(lines, FeedFixtures.CP_DEVICE, "c", COLLECTED, FeedFixtures.plain()).events().isEmpty());
    }

    @Test
    void theMaskedViewDoesNotRepeatTheHostOrAnAddressFromTheLine() {
        List<String> lines = List.of(elg("17", "Oct", "05:41:12", "Process FWD terminated; peer gw-lab-07 at 192.0.2.9 restart"));
        ChangeEvent e = CpwdEvents.parse(lines, FeedFixtures.CP_DEVICE, "c", COLLECTED, FeedFixtures.masked()).events().get(0);
        String json = e.toJson().toString();
        assertFalse(json.contains("192.0.2.9"), "address masked");
        assertFalse(e.deviceName().contains("gw-lab"), e.deviceName());
        assertTrue(e.what().startsWith("cpwd: Process FWD terminated"), e.what());
    }
}
