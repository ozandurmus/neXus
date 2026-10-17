package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;

/**
 * Synthetic outputs only, shaped like Gaia {@code stat -c "%i %s %Y"}, {@code stat -c "%n %s %Y"},
 * {@code ls -lgo --time-style=+%s}, {@code tail}, {@code head} and {@code tar -tzvf}. Host FW-TANGO-04, addresses
 * 192.0.2.x and the daemon name "secrethost" must never reach a summary.
 */
class CpSyslogMeasurementSummaryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NOW = 1_760_700_000L;
    private static final String BANNER = "Last login for FW-TANGO-04 admin from 192.0.2.77\n";
    private static final HostnameFingerprint KEY = HostnameFingerprint.fromBase64Key(
            Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8)));

    private static CpSyslogMeasurementSummary.KeyedTokens tokens(String device) {
        return new CpSyslogMeasurementSummary.KeyedTokens() {
            @Override public String inode(long inode) { return "i_" + KEY.of("inode|" + device + "|" + inode).substring(0, 16); }
            @Override public String word(String word) { return KEY.of("word|" + word).substring(0, 10); }
        };
    }

    private static Map<String, Object> run(String gate, String output) {
        return CpSyslogMeasurementSummary.summarize(gate, output, 0, NOW, tokens("device-1"));
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> list(Object o) { return (List<Map<String, Object>>) o; }

    @Test void everyMeasurementGateHasAParser() {
        for (String gate : List.of("stat", "stat_ext", "ls_sa", "ls_spike", "ls_hcp", "ls_crash", "tail_messages", "tail_fwd",
                "tail_cpd", "tail_cpwd", "tail_fwk", "tail_spike", "tail_spike_csv", "head_sar", "hcp_listing")) {
            assertTrue(CpSyslogMeasurementSummary.handles("cp_syslog_measure_" + gate), gate);
        }
        assertFalse(CpSyslogMeasurementSummary.handles("cp_failover_syncstat"));
        assertEquals("UNKNOWN", run("cp_failover_syncstat", "x").get("status"));
    }

    // ---- stat ---------------------------------------------------------------------------------------------------

    private static final String STAT = "131075 48210 1760699400\n262201 20971520 1760650000\n"
            + "524300 0 1760000000\n786433 8123456 1760699999\n12 66520 1760690000\n";

    @Test void orderedStatSkipsABannerAndKeepsSizesMtimesAndOpaqueInodes() {
        var summary = run("cp_syslog_measure_stat", BANNER + STAT);
        assertEquals("MEASURED", summary.get("status"));
        assertEquals("stat", summary.get("kind"));
        assertEquals(1, map(summary).get("ignoredLines"));
        var files = list(summary.get("files"));
        assertEquals(List.of("messages", "messages.1", "fwd.elg", "cpd.elg", "cpwd.elg"),
                files.stream().map(f -> f.get("class")).toList());
        assertEquals(48210L, files.get(0).get("sizeBytes"));
        assertEquals(20971520L, files.get(1).get("sizeBytes"));
        assertEquals(8123456L, files.get(3).get("sizeBytes"));
        assertEquals(1760699400L, files.get(0).get("mtimeEpoch"));
        assertEquals(NOW - 1760699400L, files.get(0).get("ageSeconds"));
        assertEquals("NEXUS_CLOCK", summary.get("ageBasis"));
        assertNull(summary.get("deviceClockEpoch"));
        String inode = (String) files.get(0).get("inode");
        assertTrue(inode.matches("i_[0-9a-f]{16}"), inode);
        assertFalse(JSON_OF(summary).contains("131075"));
        // equal inode on the same device, equal token; another device, another token
        assertEquals(inode, run("cp_syslog_measure_stat", STAT).get("files") instanceof List<?> l ? ((Map<?, ?>) l.get(0)).get("inode") : null);
        var other = CpSyslogMeasurementSummary.summarize("cp_syslog_measure_stat", STAT, 0, NOW, tokens("device-2"));
        assertNotEquals(inode, list(other.get("files")).get(0).get("inode"));
    }

    @Test void orderedStatUsesADeviceClockLineWhenOneIsPrinted() {
        var summary = run("cp_syslog_measure_stat", "1760699500\n" + STAT);
        assertEquals(1760699500L, summary.get("deviceClockEpoch"));
        assertEquals("DEVICE_CLOCK", summary.get("ageBasis"));
        assertEquals(100L, list(summary.get("files")).get(0).get("ageSeconds"));
    }

    @Test void aMissingFileTakesItsSlotAndAWrongLineCountIsUnknown() {
        String missing = "131075 48210 1760699400\n262201 20971520 1760650000\n"
                + "stat: cannot stat '/opt/CPsuite-R81.20/fw1/log/fwd.elg': No such file or directory\n"
                + "786433 8123456 1760699999\n12 66520 1760690000\n";
        var summary = run("cp_syslog_measure_stat", missing);
        var files = list(summary.get("files"));
        assertEquals(false, files.get(2).get("present"));
        assertEquals("NO_SUCH_FILE", files.get(2).get("reason"));
        assertEquals("fwd.elg", files.get(2).get("class"));
        assertEquals(true, files.get(3).get("present"));
        var broken = run("cp_syslog_measure_stat", "131075 48210 1760699400\n");
        assertEquals("UNKNOWN", broken.get("status"));
        assertEquals("SLOT_COUNT_MISMATCH", broken.get("reason"));
        assertEquals(5, broken.get("expectedSlots"));
    }

    @Test void namedStatClassifiesPathsByBaseNameAndNeverKeepsThem() {
        String out = BANNER
                + "/opt/CPsuite-R81.20/fw1/log/fwk.elg 91234567 1760699000\n"
                + "/opt/CPsuite-R81.20/fw1/log/mq_mng.elg 55718 1760698000\n"
                + "stat: cannot stat '/var/log/hcp.elg': No such file or directory\n"
                + "/var/log/spike_detective/spike_detective.log 6853 1760690000\n"
                + "/var/log/spike_detective/spike_detective.csv 18496 1760699900\n";
        var summary = run("cp_syslog_measure_stat_ext", out);
        var files = list(summary.get("files"));
        assertEquals(List.of("fwk.elg", "mq_mng.elg", "hcp.elg", "spike_detective.log", "spike_detective.csv"),
                files.stream().map(f -> f.get("class")).toList());
        assertEquals(91234567L, files.get(0).get("sizeBytes"));
        assertEquals(false, files.get(2).get("present"));
        assertNull(files.get(0).get("inode"));
        assertFalse(JSON_OF(summary).contains("CPsuite"));
        assertFalse(JSON_OF(summary).contains("/"));
    }

    // ---- ls -------------------------------------------------------------------------------------------------------

    private static String lsLine(char type, long links, long size, long mtime, String name) {
        return (type == 'd' ? "drwxr-xr-x" : "-rw-r--r--") + " " + links + " " + size + " " + mtime + " " + name;
    }

    @Test void sarListingGivesCountsSizesMediansAndDayShapedNameClasses() {
        List<String> lines = new ArrayList<>(List.of("Welcome FW-TANGO-04", "total 98164"));
        for (int day = 1; day <= 30; day++) {
            lines.add(lsLine('f', 1, 6_000_000L + day, 1_760_000_000L + day * 86400L, String.format("sa%02d", day)));
            lines.add(lsLine('f', 1, 9_000_000L + day, 1_760_000_000L + day * 86400L, String.format("sar%02d", day)));
        }
        var summary = run("cp_syslog_measure_ls_sa", String.join("\n", lines) + "\n");
        assertEquals("ls", summary.get("kind"));
        assertEquals(60L, summary.get("entryCount"));
        var sa = map(map(summary.get("sections")).get("sa"));
        assertEquals(60, sa.get("entryCount"));
        assertEquals(60, sa.get("files"));
        assertEquals(98164L, sa.get("totalBlocksKiB"));
        assertEquals(6_000_001L, sa.get("minFileBytes"));
        assertEquals(9_000_030L, sa.get("maxFileBytes"));
        assertEquals(1_760_000_000L + 86400L, sa.get("oldestMtimeEpoch"));
        assertEquals(1_760_000_000L + 30 * 86400L, sa.get("newestMtimeEpoch"));
        var classes = map(sa.get("nameClasses"));
        assertEquals(java.util.Set.of("saNN", "sarNN"), classes.keySet());
        assertEquals(30L, map(classes.get("saNN")).get("count"));
        assertEquals(6_000_015L, map(classes.get("saNN")).get("medianBytes"));
        assertEquals(1, ((Number) sa.get("maxSiblings")).intValue() > 0 ? 1 : 0);
        assertEquals(false, sa.get("anyGz"));
        assertFalse(JSON_OF(summary).contains("FW-TANGO"));
    }

    @Test void spikeListingCountsRotationSiblingsAndGzipAndDirectories() {
        String out = "total 4988\n"
                + lsLine('f', 1, 18496, 1760699900L, "spike_detective.csv") + "\n"
                + lsLine('f', 1, 4400000, 1760699000L, "spike_detective.csv.1") + "\n"
                + lsLine('f', 1, 900000, 1760600000L, "spike_detective.csv.2.gz") + "\n"
                + lsLine('f', 1, 60099, 1760690000L, "spike_detective.log") + "\n"
                + lsLine('f', 1, 5000000, 1760500000L, "spike_detective.log.1") + "\n"
                + lsLine('d', 2, 57, 1760100000L, "1760100000") + "\n"
                + lsLine('d', 2, 57, 1760200000L, "1760200000") + "\n"
                + lsLine('d', 2, 114, 1760300000L, "20261015") + "\n";
        var spike = map(map(run("cp_syslog_measure_ls_spike", out).get("sections")).get("spike_detective"));
        assertEquals(8, spike.get("entryCount"));
        assertEquals(5, spike.get("files"));
        assertEquals(3, spike.get("dirs"));
        assertEquals(true, spike.get("anyGz"));
        assertEquals(3, spike.get("maxSiblings"));
        assertEquals(3, spike.get("rotatedFiles"));
        var families = map(spike.get("families"));
        assertEquals(3, map(families.get("spike_detective.csv")).get("files"));
        assertEquals(1, map(families.get("spike_detective.csv")).get("gz"));
        assertEquals(2, map(families.get("spike_detective.log")).get("files"));
        var classes = map(spike.get("nameClasses"));
        assertTrue(classes.containsKey("spike_detective.csv.N.gz"), classes.keySet().toString());
        assertTrue(classes.containsKey("spike_detective.log.N"));
        assertEquals(2L, map(classes.get("dir:EPOCH")).get("count"));
        assertEquals(1L, map(classes.get("dir:YYYYMMDD")).get("count"));
        assertEquals(57L, spike.get("minFileBytes") == null ? null : 57L);
    }

    @Test void crashAndUsermodeSectionsAreSplitAndProcessNamesAreAllowlistedOrKeyed() {
        String out = "/var/log/crash/:\ntotal 0\n\n/var/log/dump/usermode/:\ntotal 34312\n"
                + lsLine('f', 1, 22535, 1760000100L, "fwd.4711.core.gz") + "\n"
                + lsLine('f', 1, 55838000, 1760000200L, "secrethost.4712.core.gz") + "\n"
                + lsLine('f', 1, 152, 1760000300L, "cpd.4713.core") + "\n";
        var summary = run("cp_syslog_measure_ls_crash", out);
        var sections = map(summary.get("sections"));
        assertEquals(java.util.Set.of("crash", "usermode"), sections.keySet());
        assertEquals(0, map(sections.get("crash")).get("entryCount"));
        var usermode = map(sections.get("usermode"));
        assertEquals(3, usermode.get("entryCount"));
        var classes = map(usermode.get("nameClasses")).keySet();
        assertTrue(classes.contains("fwd.N.core.gz"), classes.toString());
        assertTrue(classes.contains("cpd.N.core"));
        String keyed = classes.stream().filter(c -> c.startsWith("p_")).findFirst().orElseThrow();
        assertTrue(keyed.matches("p_[0-9a-f]{10}\\.N\\.core\\.gz"), keyed);
        assertFalse(JSON_OF(summary).contains("secrethost"));
        assertEquals(true, usermode.get("anyGz"));
    }

    @Test void aMissingDirectoryIsAnAbsentSectionNotAnError() {
        var summary = run("cp_syslog_measure_ls_hcp",
                "ls: cannot access '/var/log/hcp/last/': No such file or directory\n");
        assertEquals("MEASURED", summary.get("status"));
        var hcp = map(map(summary.get("sections")).get("hcp_last"));
        assertEquals(false, hcp.get("present"));
        assertEquals(0, hcp.get("entryCount"));
        assertEquals("UNKNOWN", run("cp_syslog_measure_ls_hcp", "").get("status"));
        assertEquals("NO_LISTING_LINES", run("cp_syslog_measure_ls_hcp", "").get("reason"));
        assertEquals(0L, map(map(run("cp_syslog_measure_ls_hcp", "total 0\n").get("sections")).get("hcp_last")).get("entryCount") instanceof Integer i ? (long) i : -1L);
    }

    // ---- tail / head ------------------------------------------------------------------------------------------------

    @Test void tailOfMessagesCountsLinesShapesAndTheLongestLine() {
        StringBuilder out = new StringBuilder(BANNER);
        for (int i = 0; i < 20; i++) out.append("Oct 17 06:0").append(i % 10).append(":01 2026 FW-TANGO-04 clish[777]: user admin from 192.0.2.5\n");
        var summary = run("cp_syslog_measure_tail_messages", out.toString());
        assertEquals(21, summary.get("lineCount"));
        assertEquals(Map.of("NONE", 1, "SYSLOG_MON_DD_TIME_YEAR", 20), summary.get("timestampShapes"));
        assertEquals(out.toString().lines().mapToInt(String::length).max().getAsInt(), summary.get("maxLineLength"));
        assertFalse(JSON_OF(summary).contains("clish"));
    }

    @Test void elgFwkAndIsoShapesAreTold() {
        String out = "[fwd 4711 1234]@FW-TANGO-04[17 Oct 06:05:01] text\n[fwd 4711 1234]@FW-TANGO-04 no time here\n"
                + "[17 Oct 06:05:01][1];[3];[192.0.2.1:80 -> 192.0.2.2:443] [INFO]: x\n2026-10-17 06:05:01 hello\n"
                + "1760699000 epoch\n\n06:05:01 only time\nplain text\ncarry on at 06:05:01 inside\n";
        var summary = run("cp_syslog_measure_tail_fwk", out);
        assertEquals(9, summary.get("lineCount"));
        assertEquals(1, summary.get("blankLines"));
        assertEquals(Map.of("ELG_HEADER_WITH_TIME", 1, "ELG_HEADER_NO_TIME", 1, "BRACKET_DD_MON_TIME", 1, "ISO_DATE_TIME", 1,
                "EPOCH_SECONDS", 1, "TIME_FIRST", 1, "NONE", 1, "TIME_INSIDE", 1), summary.get("timestampShapes"));
    }

    @Test void spikeCsvGivesColumnCountsAndOnlyTheFirstTokenShape() {
        String out = "1760699000,cpu,12,FW-TANGO-04,30,55,40,1\n1760699100,cpu,13,x,31,56,41,0\n1760699200,mem,1,y,2,3,4,0\n";
        var summary = run("cp_syslog_measure_tail_spike_csv", out);
        assertEquals(Map.of("8", 3), summary.get("commaFieldCounts"));
        assertEquals(Map.of("9", 3), summary.get("firstTokenShapes"));
        var log = run("cp_syslog_measure_tail_spike", "Fri_Oct_17_06:05:01_2026 info: type: cpu, id: 7\n10.1.2.3 info: x, y\n");
        assertEquals(Map.of("Aa_Aa_9_9:9:9_9", 1, "ADDRESS_LIKE", 1), log.get("firstTokenShapes"));
        assertFalse(JSON_OF(log).contains("10.1.2.3"));
    }

    @Test void sarHeadGivesCpuCountColumnsAndRows() {
        String out = "Linux 3.10.0 (FW-TANGO-04) \t10/16/2026 \t_x86_64_\t(8 CPU)\n\n"
                + "00:00:01        CPU     %user     %nice   %system   %iowait    %steal     %idle\n"
                + "00:10:01        all      0.50      0.00      0.30      0.00      0.00     99.20\n"
                + "00:10:01          0      0.60      0.00      0.30      0.00      0.00     99.10\n"
                + "00:10:01          1      0.40      0.00      0.30      0.00      0.00     99.30\n";
        var sar = map(run("cp_syslog_measure_head_sar", out).get("sar"));
        assertEquals(8, sar.get("cpuCount"));
        assertEquals(8, sar.get("headerColumns"));
        assertEquals(false, sar.get("meridiemClock"));
        assertEquals(3, sar.get("sampleRows"));
        assertEquals(2, sar.get("perCpuRows"));
    }

    @Test void anAbsentTailTargetIsCountedNotKept() {
        var summary = run("cp_syslog_measure_tail_fwk", "tail: cannot open '/opt/CPsuite-R81.20/fw1/log/fwk.elg' for reading: No such file or directory\n");
        assertEquals(1, summary.get("errorLines"));
        assertEquals(true, summary.get("absent"));
        assertFalse(JSON_OF(summary).contains("CPsuite"));
        var empty = run("cp_syslog_measure_tail_fwd", "");
        assertEquals("MEASURED", empty.get("status"));
        assertEquals(0, empty.get("lineCount"));
    }

    // ---- hcp archive listing -------------------------------------------------------------------------------------

    @Test void hcpArchiveListingGivesMemberSizesAndTimesButNoNames() {
        String out = "-rw-r--r-- root/root 7273 2026-10-17 03:00 hcp_report_FW-TANGO-04_17_10_26_03_00/summary.json\n"
                + "-rw-r--r-- root/root 6357 2026-10-17 03:00 hcp_report_FW-TANGO-04_17_10_26_03_00/hcp_report.txt\n"
                + "-rw-r--r-- root/root 4400000 2026-10-17 03:01 hcp_report_FW-TANGO-04_17_10_26_03_00/details.json\n";
        var summary = run("cp_syslog_measure_hcp_listing", out);
        assertEquals(3, summary.get("memberCount"));
        assertEquals(7273L + 6357L + 4400000L, summary.get("memberBytes"));
        assertEquals(7273L, summary.get("medianMemberBytes"));
        assertEquals(6357L, summary.get("minMemberBytes"));
        assertEquals(4400000L, summary.get("maxMemberBytes"));
        assertEquals(60L, (Long) summary.get("newestMemberLocalEpoch") - (Long) summary.get("oldestMemberLocalEpoch"));
        assertFalse(JSON_OF(summary).contains("FW-TANGO"));
        var failed = run("cp_syslog_measure_hcp_listing", "ls: cannot access '/var/log/hcp/last/*.tar.gz': No such file or directory\n"
                + "tar: Cannot open: No such file or directory\ntar: Error is not recoverable: exiting now\n");
        assertEquals(0, failed.get("memberCount"));
        assertEquals(true, failed.get("absent"));
    }

    // ---- name classes -------------------------------------------------------------------------------------------------

    @Test void nameClassesReduceEveryUnknownWordAndDigitRun() {
        var t = tokens("device-1");
        assertEquals("messages.N", CpSyslogMeasurementSummary.classify("messages.1", t).clazz());
        assertEquals("messages.N.gz", CpSyslogMeasurementSummary.classify("messages.2.gz", t).clazz());
        assertEquals("messages-YYYYMMDD.gz", CpSyslogMeasurementSummary.classify("messages-20261017.gz", t).clazz());
        assertEquals("perf_cpu_NN.log", CpSyslogMeasurementSummary.classify("perf_cpu_12.log", t).clazz());
        assertEquals("sarNN", CpSyslogMeasurementSummary.classify("sar05", t).clazz());
        assertEquals("hcp_report_X_NN_NN_NN_NN_NN.tar.gz",
                CpSyslogMeasurementSummary.classify("hcp_report_gw01_17_10_26_03_00.tar.gz", t).clazz().replace("XNN", "X"));
        var host = CpSyslogMeasurementSummary.classify("FW-TANGO-04", t).clazz();
        assertFalse(host.toLowerCase().contains("tango"));
        assertEquals("fw-X-NN", host);
        assertEquals("OTHER", CpSyslogMeasurementSummary.classify("", t).clazz());
        assertEquals("LONG_NAME", CpSyslogMeasurementSummary.classify("a".repeat(200), t).clazz());
    }

    // ---- privacy ------------------------------------------------------------------------------------------------------

    private static final Pattern SAFE_STRING = Pattern.compile("[A-Za-z0-9_.+?~:-]{0,100}");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{6,}");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");

    private static String JSON_OF(Object summary) {
        try { return JSON.writeValueAsString(summary); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void assertClean(Object node, String where) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> { assertClean(String.valueOf(k), where + "." + k); assertClean(v, where + "." + k); });
        } else if (node instanceof Iterable<?> items) {
            for (Object item : items) assertClean(item, where + "[]");
        } else if (node instanceof String text) {
            assertTrue(SAFE_STRING.matcher(text).matches(), where + " holds a string outside the closed alphabet: " + text);
            assertFalse(text.contains("/"), where);
            assertTrue(text.matches("[ip]_[0-9a-f]{10,16}(?:\\..*)?") || !LONG_DIGITS.matcher(text).find(),
                    where + " holds a digit run of 6 or more: " + text);
            assertFalse(IPV4.matcher(text).find(), where + " holds an address: " + text);
            assertFalse(text.toLowerCase().contains("tango") || text.contains("secrethost") || text.contains("192.0.2"), where);
        }
    }

    @Test void noSummaryHoldsAPathAHostLikeTokenAnAddressOrARawInode() {
        String hostile = "/opt/CPsuite-R81.20/fw1/log/FW-TANGO-04.elg 5 6\n";
        Map<String, String> fixtures = new java.util.LinkedHashMap<>();
        fixtures.put("cp_syslog_measure_stat", BANNER + STAT);
        fixtures.put("cp_syslog_measure_stat_ext", BANNER + hostile + "stat: cannot stat '/var/log/FW-TANGO-04/hcp.elg': No such file or directory\n");
        fixtures.put("cp_syslog_measure_ls_sa", "total 1\n" + lsLine('f', 1, 6_000_001L, 1_760_000_000L, "FW-TANGO-04.192.0.2.5.log") + "\n"
                + lsLine('d', 2, 57, 1_760_000_000L, "secrethost") + "\n" + lsLine('f', 1, 5, 1_760_000_000L, "secrethost.4712.core.gz") + "\n");
        fixtures.put("cp_syslog_measure_ls_crash", "/var/log/crash/FW-TANGO-04:\ntotal 0\n/var/log/dump/usermode/:\n"
                + lsLine('f', 1, 5, 1_760_000_000L, "secrethost.4712.core.gz") + "\n");
        fixtures.put("cp_syslog_measure_tail_messages", BANNER + "Oct 17 06:00:01 2026 FW-TANGO-04 sshd[1]: from 192.0.2.5\n");
        fixtures.put("cp_syslog_measure_tail_spike", "FW-TANGO-04 info: a, b\n192.0.2.9 info: c\n");
        fixtures.put("cp_syslog_measure_hcp_listing", "-rw-r--r-- root/root 5 2026-10-17 03:00 FW-TANGO-04/192.0.2.5.json\n");
        fixtures.put("cp_syslog_measure_head_sar", "Linux 3.10 (FW-TANGO-04) x (8 CPU)\n");
        fixtures.put("cp_syslog_measure_tail_fwk", "tail: cannot open '/opt/FW-TANGO-04' : No such file or directory\n");
        for (var fixture : fixtures.entrySet()) {
            var summary = run(fixture.getKey(), fixture.getValue());
            assertClean(summary, fixture.getKey());
            String json = JSON_OF(summary);
            for (String rawInode : List.of("131075", "262201", "524300", "786433")) assertFalse(json.contains(rawInode), fixture.getKey());
        }
    }

    @Test void anUnreadableOutputNeverThrows() {
        assertEquals("MEASURED", run("cp_syslog_measure_tail_fwd", "\u0000\u0001� binary ￿\n").get("status"));
        var tooBig = run("cp_syslog_measure_stat_ext", "/x " + "9".repeat(40) + " 1\n");
        assertEquals("UNKNOWN", tooBig.get("status"));
    }
}
