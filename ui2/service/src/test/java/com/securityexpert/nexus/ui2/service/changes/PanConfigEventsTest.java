package com.securityexpert.nexus.ui2.service.changes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Palo Alto config-log JSON Lines (the measured shape) become user change events; nothing is invented for a bad line. */
class PanConfigEventsTest {

    @Test
    void anEditEntryBecomesAUserEventWithItsCommandPathResultAndPreviewDiff() {
        String line = FeedFixtures.panConfigLine("2026/10/17 02:14:09", "alice", "edit", " vsys  vsys1 rulebase security rules app-backup-allow",
                "Submitted", "<action>deny</action>", "<action>allow</action>");
        PanConfigEvents.Parsed parsed = PanConfigEvents.parse(List.of(line), FeedFixtures.PAN_DEVICE, "chunk-1", FeedFixtures.plain());
        assertEquals(1, parsed.events().size());
        assertEquals(0, parsed.unparsed());
        ChangeEvent e = parsed.events().get(0);
        assertEquals(ChangeEvent.Kind.USER, e.kind());
        assertEquals("pan_config", e.source());
        assertEquals("alice", e.actor());
        assertEquals(FeedFixtures.PAN_HOST, e.deviceName());
        assertEquals("palo_alto", e.vendor());
        assertEquals("DEVICE_LOCAL", e.timeBasis());
        assertEquals("2026/10/17 02:14:09", e.deviceTime());
        // Ordered as Europe/Istanbul (GMT+3): 02:14:09 local is 23:14:09 UTC the day before.
        assertEquals(Instant.parse("2026-10-16T23:14:09Z"), e.time());
        assertTrue(e.what().startsWith("edit vsys"), e.what());
        assertTrue(e.detail().contains("result Submitted") && e.detail().contains("seqno 41"), e.detail());
        assertEquals(List.of("-", "+"), e.diff().stream().map(ChangeEvent.DiffLine::op).toList());
        assertEquals("<action>deny</action>", e.diff().get(0).text());
        assertEquals("?screen=inventory&device_id=dev-pan-1&tab=logs", e.links().device());
        assertEquals("pan_config:chunk-1:1", e.key());
    }

    @Test
    void commitAllWithoutPathHasNoDiffAndAFailedResultIsBad() {
        String ok = FeedFixtures.panConfigLine("2026/10/17 02:15:00", "bob", "commit-all", null, "Succeeded", null, null);
        String failed = FeedFixtures.panConfigLine("2026/10/17 02:16:00", "bob", "commit", null, "Failed", null, null);
        PanConfigEvents.Parsed parsed = PanConfigEvents.parse(List.of(ok, failed), FeedFixtures.PAN_DEVICE, "c", FeedFixtures.plain());
        assertEquals(2, parsed.events().size());
        assertEquals("commit-all", parsed.events().get(0).what());
        assertTrue(parsed.events().get(0).diff().isEmpty());
        assertEquals(ChangeEvent.Tone.NEUTRAL, parsed.events().get(0).tone());
        assertEquals(ChangeEvent.Tone.BAD, parsed.events().get(1).tone());
    }

    @Test
    void aLineThatIsNotAnEntryIsCountedNotInvented() {
        List<String> lines = List.of("", "not json", "{\"cmd\":\"edit\"}", "{\"receive_time\":\"yesterday\",\"cmd\":\"edit\"}");
        PanConfigEvents.Parsed parsed = PanConfigEvents.parse(lines, FeedFixtures.PAN_DEVICE, "c", FeedFixtures.plain());
        assertTrue(parsed.events().isEmpty());
        assertEquals(4, parsed.unparsed());
    }

    @Test
    void theMaskedViewShowsAUserTokenAPseudonymAndMaskedPathText() {
        String line = FeedFixtures.panConfigLine("2026/10/17 02:14:09", "alice", "set", " vsys  vsys1 address  host-edge-fw-01",
                "Submitted", null, "192.0.2.77");
        ChangeEvent e = PanConfigEvents.parse(List.of(line), FeedFixtures.PAN_DEVICE, "c", FeedFixtures.masked()).events().get(0);
        assertTrue(e.actor().startsWith("USR-"), e.actor());
        assertNotEquals("alice", e.actor());
        assertNotEquals(FeedFixtures.PAN_HOST, e.deviceName());
        assertFalse(e.deviceName().contains("edge-fw"), e.deviceName());
        assertFalse(e.toJson().toString().contains("192.0.2.77"), "the preview address must be masked");
        assertFalse(e.toJson().toString().contains("alice"));
    }

    @Test
    void anUnknownDeviceIsNamedByItsIdInThePlainViewAndByATokenInTheMaskedView() {
        String line = FeedFixtures.panConfigLine("2026/10/17 02:14:09", "", "commit-all", null, "Succeeded", null, null);
        ChangeEvent plain = PanConfigEvents.parse(List.of(line), "dev-unlisted", "c", FeedFixtures.plain()).events().get(0);
        assertEquals("dev-unlisted", plain.deviceName());
        assertEquals("UNKNOWN", plain.actor());
        assertNull(plain.diff().isEmpty() ? null : plain.diff());
        ChangeEvent masked = PanConfigEvents.parse(List.of(line), "dev-unlisted", "c", FeedFixtures.masked()).events().get(0);
        assertTrue(masked.deviceName().startsWith("DEV-"), masked.deviceName());
        assertFalse(masked.deviceName().contains("unlisted"));
    }
}
