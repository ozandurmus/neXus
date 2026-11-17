package com.securityexpert.nexus.ui2.worker.failover;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;

/** The three vendors' parsers of the information check "Traffic and ARP", and the shared status / comparison rules. Synthetic data only. */
class TrafficArpParsersTest {
    // ---- Cisco ASA ---------------------------------------------------------------------------------------------------

    @Test void asaSumsTheAggregatedPhysicalSectionWhenPresent() {
        assertEquals(Optional.of(3000L), AsaFailoverChecks.trafficBytesPerSecond(AsaFixtures.traffic(true)));
    }
    @Test void asaSumsEveryInterfaceWhenThereIsNoAggregatedSection() {
        // 488 + 300 + 100 + 60: only the "1 minute" lines, never the 5 minute lines.
        assertEquals(Optional.of(948L), AsaFailoverChecks.trafficBytesPerSecond(AsaFixtures.traffic(false)));
    }
    @Test void asaTrafficIsUnknownWhenUnparsable() {
        assertTrue(AsaFailoverChecks.trafficBytesPerSecond(null).isEmpty());
        assertTrue(AsaFailoverChecks.trafficBytesPerSecond("").isEmpty());
        assertTrue(AsaFailoverChecks.trafficBytesPerSecond("outside:\n\treceived (in 5.0 secs):\n").isEmpty());
        // Input and output lines that do not pair up are not guessed at.
        assertTrue(AsaFailoverChecks.trafficBytesPerSecond("\t1 minute input rate 9 pkts/sec,  488 bytes/sec\n").isEmpty());
        assertTrue(AsaFailoverChecks.trafficBytesPerSecond("\t1 minute input rate 9 pkts/sec,  many bytes/sec\n\t1 minute output rate 1 pkts/sec,  2 bytes/sec\n").isEmpty());
    }
    @Test void asaTrafficZeroIsAZeroNotUnknown() {
        assertEquals(Optional.of(0L), AsaFailoverChecks.trafficBytesPerSecond(
            "\t1 minute input rate 0 pkts/sec,  0 bytes/sec\n\t1 minute output rate 0 pkts/sec,  0 bytes/sec\n"));
    }
    @Test void asaArpEntriesOnTheSameLineOrTheNextOne() {
        assertEquals(Optional.of(15), AsaFailoverChecks.arpEntries("Number of ARP entries:\nASA : 15\n\nNumber of ARP receive overflow : 0\n"));
        assertEquals(Optional.of(7), AsaFailoverChecks.arpEntries("Number of ARP entries in ASA: 7\nNumber of ARP free blocks : 4\n"));
        assertEquals(Optional.of(0), AsaFailoverChecks.arpEntries("Number of ARP entries:\nASA : 0\n"));
    }
    @Test void asaArpIsUnknownWhenTheLineIsAbsentOrUnparsable() {
        assertTrue(AsaFailoverChecks.arpEntries(null).isEmpty());
        assertTrue(AsaFailoverChecks.arpEntries("").isEmpty());
        assertTrue(AsaFailoverChecks.arpEntries("Number of ARP receive overflow : 0\nNumber of ARP free blocks : 4\n").isEmpty());
        assertTrue(AsaFailoverChecks.arpEntries("Number of ARP entries:\nASA : many\n").isEmpty());
        assertTrue(AsaFailoverChecks.arpEntries("Number of ARP entries:\n").isEmpty());
    }

    // ---- FortiGate ---------------------------------------------------------------------------------------------------

    private static final String PERFORMANCE = "CPU states: 0% user 0% system 0% nice 100% idle 0% iowait 0% irq 0% softirq\n"
        + "Average network usage: 12 / 8 kbps in 1 minute, 11 / 7 kbps in 10 minutes, 10 / 6 kbps in 30 minutes\n"
        + "Maximal network usage: 99 / 99 kbps in 1 minute, 99 / 99 kbps in 10 minutes, 99 / 99 kbps in 30 minutes\n"
        + "Average sessions: 1234 sessions in 1 minute, 1200 sessions in 10 minutes, 1100 sessions in 30 minutes\n";

    @Test void fgtPerformanceGivesTheOneMinuteRateAndSessions() {
        var parsed = FgtFailoverChecks.parsePerformance(PERFORMANCE);
        assertEquals(2500L, parsed.bytesPerSecond(), "(12 + 8) kbps x 125");
        assertEquals(1234L, parsed.averageSessions());
        assertEquals(1375L, FgtFailoverChecks.parsePerformance("Average network usage: 10.5 / 0.5 kbps in 1 minute\n").bytesPerSecond());
    }
    @Test void fgtPerformanceZeroAndMissingLines() {
        assertEquals(0L, FgtFailoverChecks.parsePerformance(PERFORMANCE.replace("12 / 8 kbps in 1 minute", "0 / 0 kbps in 1 minute")).bytesPerSecond());
        var missing = FgtFailoverChecks.parsePerformance("CPU states: 0% user\nAverage sessions: 5 sessions in 1 minute\n");
        assertNull(missing.bytesPerSecond());
        assertEquals(5L, missing.averageSessions());
        assertNull(FgtFailoverChecks.parsePerformance(null).bytesPerSecond());
        assertNull(FgtFailoverChecks.parsePerformance("Command fail. Return code -3\n").bytesPerSecond());
        assertNull(FgtFailoverChecks.parsePerformance("Average network usage: many / 8 kbps in 1 minute\n").bytesPerSecond());
    }
    @Test void fgtArpCountsEntryLinesOnly() {
        String arp = "Address           Age(min)   Hardware Addr      Interface\n"
            + "192.0.2.1         0          00:00:5e:00:53:01  port1\n192.0.2.2         3          00:00:5e:00:53:02  port1\n"
            + "192.0.2.3         10         00:00:5e:00:53:03  port2\n";
        assertEquals(3, FgtFailoverChecks.arpEntries(arp));
        assertEquals(0, FgtFailoverChecks.arpEntries("Address           Age(min)   Hardware Addr      Interface\n"));
        assertEquals(1, FgtFailoverChecks.arpEntries("192.0.2.9 1 00:00:5e:00:53:09 port3\n"));
    }
    @Test void fgtArpIsUnknownWhenNotRecognised() {
        assertNull(FgtFailoverChecks.arpEntries(null));
        assertNull(FgtFailoverChecks.arpEntries(""));
        assertNull(FgtFailoverChecks.arpEntries("Command fail. Return code -3\n"));
        assertNull(FgtFailoverChecks.arpEntries("unknown action 0\n"));
    }

    // ---- Palo Alto ---------------------------------------------------------------------------------------------------

    private static String counters(long n) {
        return "<response status=\"success\"><result><ifnet><entry><name>ethernet1/1</name><ibytes>7</ibytes><obytes>7</obytes></entry></ifnet><hw>"
            + "<entry><name>ethernet1/1</name><ibytes>" + (1_000_000 + n) + "</ibytes><obytes>" + (2_000_000 + n) + "</obytes></entry>"
            + "<entry><name>ethernet1/2</name><ibytes>" + (3_000_000 + n) + "</ibytes><obytes>" + (4_000_000 + n) + "</obytes></entry>"
            + "<entry><name>mgt</name><ibytes>" + (9_000_000 + 100 * n) + "</ibytes><obytes>5</obytes></entry>"
            + "<entry><name>ha2</name><ibytes>" + (8_000_000 + 100 * n) + "</ibytes><obytes>5</obytes></entry></hw></result></response>";
    }

    @Test void panRateIsTheForwardingHardwareDeltaPerSecond() {
        // Two forwarding interfaces, two directions, 10000 each: 40000 bytes over 5 s. mgt and ha2 are left out.
        assertEquals(8000L, PanFailoverChecks.trafficBytesPerSecond(counters(0), counters(10_000), 5_000_000_000L));
        assertEquals(0L, PanFailoverChecks.trafficBytesPerSecond(counters(0), counters(0), 5_000_000_000L), "no traffic is a zero, not unknown");
    }
    @Test void panRateIsUnknownOnAResetADecreaseOrAnUnusableSample() {
        assertNull(PanFailoverChecks.trafficBytesPerSecond(counters(10_000), counters(0), 5_000_000_000L), "a counter went down");
        assertNull(PanFailoverChecks.trafficBytesPerSecond(counters(0), counters(10_000), 0));
        assertNull(PanFailoverChecks.trafficBytesPerSecond(counters(0), "<response status=\"error\"/>", 5_000_000_000L));
        assertNull(PanFailoverChecks.trafficBytesPerSecond(null, counters(1), 5_000_000_000L));
        assertNull(PanFailoverChecks.trafficBytesPerSecond(counters(0), counters(1).replace("<name>ethernet1/2</name>", "<name>ethernet1/9</name>"), 5_000_000_000L),
            "an interface missing from one sample");
        assertNull(PanFailoverChecks.hardwareBytes(counters(0).replace("<ibytes>1000000</ibytes>", "<ibytes>many</ibytes>")));
        assertNull(PanFailoverChecks.hardwareBytes("<response status=\"success\"><result><hw><entry><name>mgt</name><ibytes>1</ibytes><obytes>1</obytes></entry></hw></result></response>"),
            "no forwarding interface at all");
        assertNull(PanFailoverChecks.hardwareBytes("<response status=\"success\"><result/></response>"));
    }
    @Test void panArpTotalElseEntryCount() {
        assertEquals(3, PanFailoverChecks.arpEntries("<response status=\"success\"><result><max>8192</max><total>3</total><entries/></result></response>"));
        assertEquals(2, PanFailoverChecks.arpEntries("<response status=\"success\"><result><entries><entry><ip>192.0.2.1</ip></entry><entry><ip>192.0.2.2</ip></entry></entries></result></response>"));
        assertEquals(0, PanFailoverChecks.arpEntries("<response status=\"success\"><result><total>0</total><entries/></result></response>"));
        assertNull(PanFailoverChecks.arpEntries("<response status=\"success\"><result><total>many</total></result></response>"));
        assertNull(PanFailoverChecks.arpEntries("<response status=\"success\"><result/></response>"));
        assertNull(PanFailoverChecks.arpEntries("<response status=\"error\"/>"));
        assertNull(PanFailoverChecks.arpEntries(null));
    }

    // ---- the shared status and comparison ----------------------------------------------------------------------------

    private static final Optional<TrafficArpCheck.Previous> NO_PREVIOUS = Optional.empty();

    @Test void bothPositivePasses() {
        var out = TrafficArpCheck.evaluate(new TrafficArpCheck.Reading(5000L, 12, null), NO_PREVIOUS);
        assertEquals("PASS", out.status());
        assertEquals(5000L, out.evidence().get("trafficBytesPerSecond"));
        assertEquals(12, out.evidence().get("arpEntries"));
        assertFalse(out.evidence().containsKey("previousArpEntries"), "no earlier run, no comparison");
    }
    @Test void aZeroOrAMissingNumberIsUnknownWithTheNumbersKept() {
        assertEquals("TRAFFIC_ZERO", TrafficArpCheck.evaluate(new TrafficArpCheck.Reading(0L, 4, null), NO_PREVIOUS).reason());
        assertEquals("ARP_ZERO", TrafficArpCheck.evaluate(new TrafficArpCheck.Reading(10L, 0, null), NO_PREVIOUS).reason());
        assertEquals("TRAFFIC_AND_ARP_ZERO", TrafficArpCheck.evaluate(new TrafficArpCheck.Reading(0L, 0, null), NO_PREVIOUS).reason());
        var partial = TrafficArpCheck.evaluate(new TrafficArpCheck.Reading(null, 4, null), NO_PREVIOUS);
        assertEquals("UNKNOWN", partial.status());
        assertEquals("TRAFFIC_UNAVAILABLE", partial.reason());
        assertEquals(4, partial.evidence().get("arpEntries"));
        assertEquals("ARP_UNAVAILABLE", TrafficArpCheck.evaluate(new TrafficArpCheck.Reading(9L, null, null), NO_PREVIOUS).reason());
        assertEquals("TRAFFIC_AND_ARP_UNAVAILABLE", TrafficArpCheck.evaluate(TrafficArpCheck.Reading.NONE, NO_PREVIOUS).reason());
    }
    @Test void ratiosAgainstTheEarlierRunAreFlaggedBelowHalf() {
        var out = TrafficArpCheck.evaluate(new TrafficArpCheck.Reading(4000L, 10, null),
            Optional.of(new TrafficArpCheck.Previous(10_000, 30)));
        assertEquals("PASS", out.status(), "a drop is flagged, never failed");
        assertEquals(0.4, out.evidence().get("trafficRatio"));
        assertEquals(true, out.evidence().get("trafficDropped"));
        assertEquals(0.333, out.evidence().get("arpRatio"));
        assertEquals(true, out.evidence().get("arpDropped"));
        var exactlyHalf = TrafficArpCheck.evaluate(new TrafficArpCheck.Reading(5000L, 15, null),
            Optional.of(new TrafficArpCheck.Previous(10_000, 30)));
        assertEquals(false, exactlyHalf.evidence().get("trafficDropped"), "the tolerance is >= 0.50 like Check Point's");
        assertEquals(false, exactlyHalf.evidence().get("arpDropped"));
    }
    @Test void aZeroEarlierBaselineGivesNoRatio() {
        var out = TrafficArpCheck.evaluate(new TrafficArpCheck.Reading(100L, 5, null), Optional.of(new TrafficArpCheck.Previous(0, 0)));
        assertEquals(0L, out.evidence().get("previousTrafficBytesPerSecond"));
        assertFalse(out.evidence().containsKey("trafficRatio"));
        assertFalse(out.evidence().containsKey("arpDropped"));
    }
    @Test void thePreviousRowIsReadFromTheRepositoryAndOnlyPlainNumbersCount() {
        var store = org.mockito.Mockito.mock(JooqCpFailoverRepository.class);
        java.util.function.Consumer<String> stored = derived -> org.mockito.Mockito.when(store.previousReadinessTraffic("run", 10))
            .thenReturn(derived == null ? Optional.empty() : Optional.of(new JooqCpFailoverRepository.Check("pre", "m", null, 10, "PASS", derived, java.time.Instant.EPOCH)));
        stored.accept(null);
        assertTrue(TrafficArpCheck.previous(store, "run", 10).isEmpty());
        stored.accept("{\"trafficBytesPerSecond\":2500,\"arpEntries\":3}");
        assertEquals(Optional.of(new TrafficArpCheck.Previous(2500, 3)), TrafficArpCheck.previous(store, "run", 10));
        stored.accept("{\"trafficBytesPerSecond\":\"2500\",\"arpEntries\":3}");
        assertTrue(TrafficArpCheck.previous(store, "run", 10).isEmpty());
        stored.accept("not json");
        assertTrue(TrafficArpCheck.previous(store, "run", 10).isEmpty());
    }
}
