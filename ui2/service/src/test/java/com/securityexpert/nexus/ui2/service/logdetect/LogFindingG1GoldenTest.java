package com.securityexpert.nexus.ui2.service.logdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.PendingChunk;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.logdetect.LogDetectionService.PassSummary;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadFixture;

/**
 * Golden case G1 (design section 10, P1 check): a masked synthetic replica of the trigger case. 45 EDAC corrected-error
 * lines on one slot label in 4.4 hours, machine-check lines after each of two boots, no shutdown sequence, no UE, no OOM.
 * Through the real read path (encrypted chunk, {@code LogMasking}) the engine must raise an ATTENTION finding for the CE
 * burst and for the machine checks, none for the boot anchors, and store no line text anywhere.
 */
class LogFindingG1GoldenTest {
    private static final String SLOT = "CPU_SrcID#1_MC#0_Chan#2_DIMM#0";
    private static final String JOB = "job-g1";
    private static final DateTimeFormatter GAIA = DateTimeFormatter.ofPattern("MMM d HH:mm:ss yyyy", Locale.ENGLISH);
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    private static String line(LocalDateTime t, String text) {
        return GAIA.format(t) + " edge-fw-01 " + text + "\n";
    }

    /** The synthetic chunk: everything in device-local time of 2026-11-24. */
    static String g1Text() {
        StringBuilder sb = new StringBuilder();
        LocalDateTime base = DAY.atStartOfDay();
        sb.append(line(base.plusMinutes(5), "sshd[311]: Accepted publickey for admin from 192.0.2.7 port 51000"));
        for (int i = 0; i < 45; i++) {
            sb.append(line(base.plusMinutes(30 + 6L * i), "kernel: EDAC MC0: 1 CE memory read error on " + SLOT
                    + " (channel:2 slot:0 page:0x" + Integer.toHexString(0x1000 + i) + " offset:0x0 grain:32 syndrome:0x0 - area:DRAM err_code:0001:0090 socket:1 imc:0 rank:0 bank:1)"));
        }
        for (LocalDateTime boot : new LocalDateTime[] {base.plusHours(1), base.plusHours(3).plusMinutes(30)}) {
            sb.append(line(boot.plusSeconds(5), "kernel: Linux version 3.10.0 (builder@build-host) (gcc version 4.8.5) #1 SMP"));
            sb.append(line(boot.plusSeconds(40), "kernel: [Hardware Error]: Machine check events logged"));
            sb.append(line(boot.plusSeconds(41), "kernel: [Hardware Error]: CPU 3: Machine Check: 0 Bank 7: 9c00000000000000"));
        }
        sb.append(line(base.plusHours(9), "cpd[777]: session from 198.51.100.9 closed"));
        return sb.toString();
    }

    private static final class Rig {
        final SystemLogReadFixture fixture;
        final InMemoryLogDetectionRepository repo = new InMemoryLogDetectionRepository();
        final LogDetectionService service;

        Rig(Path dir, String text) throws Exception {
            fixture = new SystemLogReadFixture(dir);
            fixture.chunk("chunk-g1", SystemLogReadFixture.bytes(text));
            repo.pending.add(new PendingChunk("chunk-g1", SystemLogReadFixture.DEVICE, "cp_messages", JOB, DAY));
            service = new LogDetectionService(repo, fixture.service(),
                    new HardwareMarkers(new LogKeyToken(SystemLogReadFixture.PRIVACY_KEY)));
        }
    }

    @Test
    void theTriggerCaseGivesAttentionFindingsForTheCeBurstAndTheMachineChecks(@TempDir Path dir) throws Exception {
        Rig rig = new Rig(dir, g1Text());
        PassSummary pass = rig.service.runOnce(10, Duration.ofMinutes(1));
        assertTrue(pass.ran());
        assertEquals(1, pass.chunksIndexed());

        FindingRow ce = finding(rig, "hw.edac.ce");
        assertEquals("ATTENTION", ce.severity());
        assertEquals(45, ce.eventCount());
        assertEquals(SLOT, ce.keyToken(), "one slot-label token, told apart from other slots by equality");
        assertEquals(DAY, ce.windowDay());
        assertEquals(DAY.atStartOfDay().plusMinutes(30), ce.windowStart());
        assertEquals(DAY.atStartOfDay().plusMinutes(30 + 6 * 44), ce.windowEnd());
        assertEquals("PROVISIONAL", ce.provenance());
        assertEquals("WEIGHT_AT_LEAST_0_5", ce.severityRule());
        assertTrue(ce.labels().contains("HISTORY_UNDER_7_DAYS"), "one day of history is labelled, not hidden");
        assertEquals("NOT_EVALUABLE", ce.coverageState());
        assertFalse(ce.chronic());
        assertFalse(ce.fleetBackground());
        assertFalse(ce.pointers().isEmpty());
        assertTrue(ce.pointers().size() <= 3 || ce.pointers().size() <= 40);

        FindingRow mce = finding(rig, "hw.mce");
        assertEquals("ATTENTION", mce.severity());
        assertEquals(4, mce.eventCount(), "two machine-check lines after each of the two boots");

        assertEquals(ce.incidentGroup(), mce.incidentGroup(), "both lie within the CE window, so one incident");
        assertTrue(rig.repo.findings.values().stream().noneMatch(f -> f.markerId().equals("kern.boot")),
                "boot banners are anchors, never findings");
        assertTrue(rig.repo.findings.values().stream().noneMatch(f -> f.markerId().equals("hw.edac.ue")
                || f.markerId().equals("kern.oom")), "no UE and no OOM line exists, so no such finding");
        assertEquals(2, rig.repo.events.stream().filter(e -> e.markerId().equals("kern.boot")).count(),
                "both boots are kept as anchors for the unexpected-reboot evidence");
        assertEquals(2, rig.repo.findings.size());
    }

    @Test
    void theWindowIsShownWithItsCoverageNeverAsHealthy(@TempDir Path dir) throws Exception {
        Rig rig = new Rig(dir, g1Text());
        rig.service.runOnce(10, Duration.ofMinutes(1));
        var coverage = rig.repo.coverage.values().iterator().next();
        assertEquals("NOT_EVALUABLE", coverage.state());
        assertEquals("HISTORY_UNDER_7_DAYS", coverage.reason());
        assertEquals(1, coverage.historyDays());
    }

    @Test
    void noLineTextOrIdentityReachesAnyStoredField(@TempDir Path dir) throws Exception {
        Rig rig = new Rig(dir, g1Text());
        rig.service.runOnce(10, Duration.ofMinutes(1));
        String stored = rig.repo.events + " " + rig.repo.findings + " " + rig.repo.states + " " + rig.repo.coverage;
        for (String forbidden : new String[] {"edge-fw-01", "192.0.2.7", "198.51.100.9", "memory read error", "Machine check",
                "Accepted publickey", "builder@build-host", "FW-TANGO-04"}) {
            assertFalse(stored.contains(forbidden), "stored fields must not carry line text: " + forbidden.length() + " chars");
        }
        // Every event row is a count with at most three pointers into the chunk.
        assertTrue(rig.repo.events.stream().allMatch(e -> e.pointers().size() <= 3 && !e.pointers().isEmpty()));
        assertTrue(rig.repo.events.stream().allMatch(e -> e.keyToken().matches("[A-Za-z0-9#_.:-]{0,64}")));
    }

    @Test
    void theLinesAreReadOnlyThroughTheMaskedPath(@TempDir Path dir) throws Exception {
        Rig rig = new Rig(dir, g1Text());
        // The inventory name is masked to its pseudonym before the matcher sees the line.
        List<String> seen = new java.util.ArrayList<>();
        var scan = rig.fixture.service().scanMasked("chunk-g1", (offset, length, masked) -> seen.add(masked));
        assertEquals(com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService.ScanStatus.OK, scan.status());
        assertEquals(scan.lines(), seen.size());
        assertTrue(seen.stream().noneMatch(l -> l.contains("edge-fw-01")), "the host is masked");
        assertTrue(seen.stream().anyMatch(l -> l.contains("FW-TANGO-04")));
        assertTrue(seen.stream().noneMatch(l -> l.contains("192.0.2.7")), "addresses are masked");
    }

    @Test
    void rawOffsetsPointAtTheStoredBytesOfEachLine(@TempDir Path dir) throws Exception {
        String text = g1Text();
        Rig rig = new Rig(dir, text);
        List<long[]> spans = new java.util.ArrayList<>();
        rig.fixture.service().scanMasked("chunk-g1", (offset, length, masked) -> spans.add(new long[] {offset, length}));
        String[] lines = text.split("\n");
        assertEquals(lines.length, spans.size());
        long expected = 0;
        for (int i = 0; i < lines.length; i++) {
            assertEquals(expected, spans.get(i)[0]);
            assertEquals(lines[i].length(), spans.get(i)[1]);
            expected += lines[i].length() + 1;
        }
    }

    @Test
    void whenMaskingIsUnavailableNothingIsProcessedUnmaskedAndTheWindowIsUnknown(@TempDir Path dir) throws Exception {
        Rig rig = new Rig(dir, g1Text());
        rig.fixture.masking = new LogMasking(SystemLogReadFixture.PRIVACY_KEY, () -> KnownNames.NONE, () -> {
            throw new IllegalStateException("database down");
        }, Duration.ofMinutes(1));
        var service = new LogDetectionService(rig.repo, rig.fixture.service(),
                new HardwareMarkers(new LogKeyToken(SystemLogReadFixture.PRIVACY_KEY)));
        PassSummary pass = service.runOnce(10, Duration.ofMinutes(1));
        assertEquals(1, pass.chunksRefused());
        assertEquals("MASKING_UNAVAILABLE", rig.repo.states.get("chunk-g1").status());
        assertTrue(rig.repo.events.isEmpty());
        assertTrue(rig.repo.findings.isEmpty());
        assertEquals("MASKING_UNAVAILABLE", rig.repo.coverage.values().iterator().next().state());
    }

    @Test
    void reindexingTheSameChunkIsIdempotent(@TempDir Path dir) throws Exception {
        Rig rig = new Rig(dir, g1Text());
        rig.service.runOnce(10, Duration.ofMinutes(1));
        var eventsBefore = List.copyOf(rig.repo.events);
        var findingsBefore = Map.copyOf(rig.repo.findings);
        rig.repo.states.clear(); // the chunk is pending again, as after a catalog or masking version change
        rig.service.runOnce(10, Duration.ofMinutes(1));
        assertEquals(eventsBefore.size(), rig.repo.events.size());
        assertEquals(eventsBefore.stream().map(e -> e.eventId()).sorted().toList(),
                rig.repo.events.stream().map(e -> e.eventId()).sorted().toList(), "the same chunk gives the same row ids");
        assertEquals(findingsBefore.keySet(), rig.repo.findings.keySet());
    }

    @Test
    void theSwitchOffMeansNothingHappens(@TempDir Path dir) throws Exception {
        Rig rig = new Rig(dir, g1Text());
        rig.repo.settings = new com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.Settings(false, null, null, null);
        PassSummary pass = rig.service.runOnce(10, Duration.ofMinutes(1));
        assertFalse(pass.ran());
        assertEquals("DISABLED", pass.reason());
        assertTrue(rig.repo.events.isEmpty());
        assertTrue(rig.repo.states.isEmpty());
    }

    private static FindingRow finding(Rig rig, String marker) {
        FindingRow f = rig.repo.findings.values().stream().filter(x -> x.markerId().equals(marker)).findFirst().orElse(null);
        assertNotNull(f, "a finding for " + marker);
        return f;
    }
}
