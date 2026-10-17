package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser of the {@code ls -lgo --time-style=+%s <dir>/...} outputs of C3, C6 and C7
 * (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 4.2 "Leading lines", section 7, section 8). Lines are classified by
 * shape, never by position: a directory header ({@code /path/:}, printed when more than one directory is listed), a
 * {@code total N} line, an entry ({@code <mode> <links> <size> <epoch> <name>}), and anything else (the login-shell
 * banner of 25 of 53 gateways) is ignored and counted. The exec channel returns standard output only, so a missing
 * directory shows as a missing header (or, for one directory, an empty output), never as an error line.
 *
 * <p>Nothing here keeps a line: the result holds parsed entries only. Names stay in memory for the run and are stored
 * by the executor as HMACs.</p>
 */
final class CpLsListing {
    private static final Pattern HEADER = Pattern.compile("^(/[^:\\s][^:]*):$");
    private static final Pattern TOTAL = Pattern.compile("^total [0-9]{1,18}$");
    private static final Pattern ENTRY = Pattern.compile(
            "^([-dlbcpsD])[rwxsStT-]{9}[.+@]?\\s+([0-9]{1,9})\\s+([0-9]{1,18})\\s+([0-9]{1,11})\\s+(.+)$");

    private CpLsListing() {}

    /**
     * @param section the listed directory (no trailing slash) the entry was printed under
     * @param type {@code -} regular file, {@code d} directory, {@code l} link, other ls type letters as printed
     */
    record Entry(String section, char type, long links, long size, long mtime, String name) {
        boolean regularFile() { return type == '-'; }
    }

    /**
     * @param entries every entry, in output order
     * @param sections the directories a header named (empty for a one-directory listing)
     * @param ignoredLines non-data lines
     * @param rejectedLines entry-shaped lines whose name is unusable (contains {@code /}, is {@code .}/{@code ..}, or
     *        numbers beyond 63 bits); none of them is applied
     */
    record Result(List<Entry> entries, Set<String> sections, int ignoredLines, int rejectedLines) {
        List<Entry> in(String section) {
            return entries.stream().filter(e -> section.equals(e.section())).toList();
        }
    }

    /** @param defaultSection the directory entries belong to before any header (a one-directory listing) */
    static Result parse(String output, String defaultSection) {
        List<Entry> entries = new ArrayList<>();
        Set<String> sections = new LinkedHashSet<>();
        String current = defaultSection;
        int ignored = 0, rejected = 0;
        if (output != null) {
            for (String raw : output.split("\\R", -1)) {
                String line = raw.strip();
                if (line.isEmpty()) continue;
                Matcher header = HEADER.matcher(line);
                if (header.matches()) {
                    current = trimSlash(header.group(1));
                    sections.add(current);
                    continue;
                }
                if (TOTAL.matcher(line).matches()) continue;
                Matcher entry = ENTRY.matcher(line);
                if (!entry.matches()) {
                    ignored++;
                    continue;
                }
                String name = entry.group(5);
                int arrow = name.indexOf(" -> ");
                if (entry.group(1).charAt(0) == 'l' && arrow > 0) name = name.substring(0, arrow);
                try {
                    if (name.contains("/") || name.equals(".") || name.equals("..") || current == null)
                        throw new NumberFormatException("name");
                    entries.add(new Entry(current, entry.group(1).charAt(0), Long.parseLong(entry.group(2)),
                            Long.parseLong(entry.group(3)), Long.parseLong(entry.group(4)), name));
                } catch (NumberFormatException unusable) {
                    rejected++;
                }
            }
        }
        return new Result(List.copyOf(entries), Set.copyOf(sections), ignored, rejected);
    }

    static String trimSlash(String path) {
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
