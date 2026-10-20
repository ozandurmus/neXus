package com.securityexpert.nexus.ui2.service.systemlog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counts process-restart lines in stored {@code cp_cpwd_elg} chunks (the Check Point watchdog log; DEVICE_SYSTEM_LOG_
 * PROGRAMME_CONTRACT.md lists it as "process restarts/crashes, high signal").
 *
 * <p><b>Line shape</b> (measured, findings section 3.3): {@code [<proc> <pid> <tid>]@<host>[<DD> <Mon> <HH:MM:SS>] <text>}
 * on every sampled line; the day, month and hour are read from that header to bucket restarts per device-local
 * hour. No year is printed, so a bucket is {@code <Mon> <DD> <HH>} of the device clock.</p>
 *
 * <p><b>Restart recognition, rule {@value #RULE_ID} -- PROVISIONAL.</b> The watchdog's text was masked in the
 * measurement and the repository holds no sample of a real restart line, so the rule is a vocabulary rule, not a
 * measured shape: a line whose text (after the header) contains the word {@code restart} in any form ({@code restart},
 * {@code restarted}, {@code restarting}, {@code restarts}), case-insensitive, is one restart line. A line that only
 * reports a termination ({@code terminated}, {@code exited}) is counted separately as a termination line and does not
 * add to the restart count, so a terminate-then-restart pair is one restart. The counts are reported with the rule id
 * so a reader can tell what was counted; a real-environment sample of {@code cpwd.elg} is what turns this into a
 * measured shape.</p>
 */
public final class CpwdRestartLines {
    public static final String RULE_ID = "cpwd-restart-v1";
    public static final String RULE_STATUS = "PROVISIONAL";
    public static final String RULE_TEXT = "a cpwd.elg line whose text contains the word \"restart\" (restart, restarted, restarting, "
            + "restarts; case-insensitive) is one process restart; termination-only lines are counted apart";

    private static final Pattern ELG_HEADER = Pattern.compile(
            "^\\[[^\\]]*\\]@[^\\s\\[\\]]*\\[(\\d{1,2}) ([A-Za-z]{3}) (\\d{2}):(\\d{2}):(\\d{2})\\]\\s*(.*)$", Pattern.DOTALL);
    private static final Pattern RESTART = Pattern.compile("\\brestart(?:s|ed|ing)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TERMINATION = Pattern.compile("\\b(?:terminated|exited)\\b", Pattern.CASE_INSENSITIVE);
    /** Only the first characters of a line are inspected, as the chunk's line-time parser does. */
    static final int MAX_LINE_CHARS = 4096;

    /**
     * The counts of one chunk: restart lines in all, termination-only lines, lines without the measured header, and
     * restart lines per device-local hour ({@code Mon DD HH}, insertion order = file order).
     */
    public record Summary(int restartLines, int terminationLines, int linesWithoutHeader, long lines, Map<String, Integer> restartsPerHour) {
        public Summary {
            restartsPerHour = Map.copyOf(restartsPerHour);
        }

        public static final Summary EMPTY = new Summary(0, 0, 0, 0, Map.of());

        /** The busiest hour's restart count, or 0. */
        public int maxRestartsInOneHour() {
            int max = 0;
            for (int n : restartsPerHour.values()) max = Math.max(max, n);
            return max;
        }

        /** This chunk's counts added to {@code other}'s (hour buckets merged by key). */
        public Summary plus(Summary other) {
            Map<String, Integer> hours = new LinkedHashMap<>(restartsPerHour);
            other.restartsPerHour.forEach((hour, n) -> hours.merge(hour, n, Integer::sum));
            return new Summary(restartLines + other.restartLines, terminationLines + other.terminationLines,
                    linesWithoutHeader + other.linesWithoutHeader, lines + other.lines, hours);
        }
    }

    private CpwdRestartLines() {
    }

    public static Summary count(InputStream in) throws IOException {
        int restarts = 0, terminations = 0, noHeader = 0;
        long lines = 0;
        Map<String, Integer> perHour = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1), 64 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines++;
                if (line.isEmpty()) continue;
                String head = line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) : line;
                Matcher m = ELG_HEADER.matcher(head);
                String text;
                String hour;
                if (m.matches()) {
                    hour = capitalise(m.group(2)) + " " + (m.group(1).length() == 1 ? "0" + m.group(1) : m.group(1)) + " " + m.group(3);
                    text = m.group(6);
                } else {
                    noHeader++;
                    hour = null;
                    text = head;
                }
                if (RESTART.matcher(text).find()) {
                    restarts++;
                    if (hour != null) perHour.merge(hour, 1, Integer::sum);
                } else if (TERMINATION.matcher(text).find()) {
                    terminations++;
                }
            }
        }
        return new Summary(restarts, terminations, noHeader, lines, perHour);
    }

    private static String capitalise(String mon) {
        return Character.toUpperCase(mon.charAt(0)) + mon.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
