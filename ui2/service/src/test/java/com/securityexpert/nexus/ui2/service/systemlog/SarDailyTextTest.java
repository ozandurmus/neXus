package com.securityexpert.nexus.ui2.service.systemlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The sar text parser over synthetic texts in the measured shape (findings section 4.1: a {@code Linux ... (N CPU)} first
 * line, a blank line, a 12-column 24-hour header, one {@code all} row and one row per CPU per sample). The host in the
 * first line is a documentation name; every value is invented.
 */
class SarDailyTextTest {
    private static final String HEADER = "Linux 3.10.0-synthetic (gw.example.invalid)\t10/19/2026\t_x86_64_\t(4 CPU)\n\n";
    private static final String CPU_COLUMNS = "%s CPU  %%usr %%nice %%sys %%iowait %%steal %%irq %%soft %%guest %%gnice %%idle\n";

    /** One sample: the {@code all} row with the given idle and four CPU rows that must be ignored. */
    private static String sample(String time, double idleAll) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.ROOT, "%s all 10.00 0.00 5.00 0.10 0.00 0.50 1.00 0.00 0.00 %.2f\n", time, idleAll));
        for (int cpu = 0; cpu < 4; cpu++) {
            sb.append(String.format(java.util.Locale.ROOT, "%s %d 50.00 0.00 5.00 0.10 0.00 0.50 1.00 0.00 0.00 %.2f\n", time, cpu, 1.0 + cpu));
        }
        return sb.toString();
    }

    private static SarDailyText.Summary parse(String text) throws Exception {
        return SarDailyText.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void theP95IsNearestRankOverTheAllRowsAndPerCpuRowsAreIgnored() throws Exception {
        StringBuilder sb = new StringBuilder(HEADER).append(String.format(CPU_COLUMNS, "00:00:01"));
        // 20 samples: busy 5, 10, ..., 100 (idle 95 .. 0). Nearest rank p95 of 20 = rank 19 = busy 95.
        for (int i = 1; i <= 20; i++) sb.append(sample(String.format("%02d:10:01", i - 1), 100 - 5 * i));
        sb.append("Average: all 10.00 0.00 5.00 0.10 0.00 0.50 1.00 0.00 0.00 50.00\n");

        var s = parse(sb.toString());

        assertTrue(s.headerSeen());
        assertEquals(4, s.cpuCount());
        assertEquals(20, s.cpuSamples());
        assertEquals(95.0, s.cpuBusyP95(), 0.001);
        assertEquals(100.0, s.cpuBusyMax(), 0.001);
        assertEquals(0, s.memSamples());
        assertNull(s.memUsedP95(), "no memory section, no memory figure");
        assertEquals(0, s.unparsedRows());
    }

    @Test
    void aMemorySectionGivesTheMemusedP95AndSectionsAreSeparatedByBlankLines() throws Exception {
        String text = HEADER + String.format(CPU_COLUMNS, "00:00:01") + sample("00:10:01", 80) + sample("00:20:01", 70)
                + "Average: all 1 2 3 4 5 6 7 8 9 75.00\n\n"
                + "00:00:01 kbmemfree kbavail kbmemused %memused kbbuffers kbcached kbcommit %commit kbactive kbinact kbdirty\n"
                + "00:10:01 1000 2000 3000 61,50 10 20 30 40 50 60 70\n"
                + "00:20:01 1000 2000 3000 64.00 10 20 30 40 50 60 70\n"
                + "00:30:01 1000 2000 3000 62.00 10 20 30 40 50 60 70\n"
                + "Average: 1000 2000 3000 62.50 10 20 30 40 50 60 70\n";

        var s = parse(text);

        assertEquals(2, s.cpuSamples());
        assertEquals(30.0, s.cpuBusyP95(), 0.001);
        assertEquals(3, s.memSamples());
        assertEquals(64.0, s.memUsedP95(), 0.001, "a decimal comma is read like a point");
    }

    @Test
    void aRowWhoseShapeDoesNotMatchItsHeaderIsCountedNotGuessed() throws Exception {
        String text = HEADER + String.format(CPU_COLUMNS, "00:00:01") + sample("00:10:01", 90)
                + "00:20:01 all 1 2 3\n" // too few columns
                + "00:30:01 all 10.00 0.00 5.00 0.10 0.00 0.50 1.00 0.00 0.00 n/a\n" // idle not a number
                + "00:40:01 all 10.00 0.00 5.00 0.10 0.00 0.50 1.00 0.00 0.00 140.00\n"; // outside 0..100

        var s = parse(text);

        assertEquals(1, s.cpuSamples());
        assertEquals(3, s.unparsedRows());
        assertEquals(10.0, s.cpuBusyP95(), 0.001);
    }

    @Test
    void aTextWithoutTheMeasuredFirstLineOrWithoutSamplesIsEmptyNotZero() throws Exception {
        var empty = parse("");
        assertFalse(empty.headerSeen());
        assertEquals(0, empty.cpuSamples());
        assertNull(empty.cpuBusyP95());

        var headerOnly = parse(HEADER + String.format(CPU_COLUMNS, "00:00:01"));
        assertTrue(headerOnly.headerSeen());
        assertNull(headerOnly.cpuBusyP95());
        assertEquals(-1, SarDailyText.cpuCountOf("Linux something without the cpu suffix"));
    }

    @Test
    void p95IsOneOfTheSamples() {
        assertEquals(3.0, SarDailyText.p95(List.of(3.0)), 0.0);
        assertEquals(2.0, SarDailyText.p95(List.of(2.0, 1.0)), 0.0, "rank ceil(1.9) = 2 of 2");
        assertEquals(100.0, SarDailyText.p95(List.of(1.0, 2.0, 100.0)), 0.0);
        assertNull(SarDailyText.p95(List.of()));
    }
}
