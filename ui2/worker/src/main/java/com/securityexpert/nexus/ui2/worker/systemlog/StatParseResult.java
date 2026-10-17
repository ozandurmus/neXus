package com.securityexpert.nexus.ui2.worker.systemlog;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The parsed C1 output (contract section 4.2), immutable. No line text is kept: only the parsed records and counters,
 * so nothing in this object can carry a host name or a stray device line into a log or a database row.
 *
 * @param deviceEpoch the device clock ({@code date +%s}), or null when no device-clock line was found
 * @param files every well-formed stat line, in output order; a path that appeared twice is dropped as ambiguous
 * @param leadingNonDataLines non-data lines before the device-clock line (the banner of 25 of 53 gateways)
 * @param ignoredLines non-data lines after it that are neither a stat line nor a stat error line
 * @param errorLines {@code stat:} error lines (an unmatched glob prints one)
 * @param malformedLines lines that look like a stat record (start with {@code /}) but are not well formed, plus
 *        the extra occurrences of a duplicated path; none of them is applied
 * @param malformedFamilies families a malformed line could belong to; their plan is {@code UNKNOWN}
 * @param absentFamilies families an error line reports as "No such file or directory"
 * @param errorFamilies families an error line reports with any other text (fail closed: not {@code ABSENT})
 */
public record StatParseResult(
        Long deviceEpoch,
        List<FileStat> files,
        int leadingNonDataLines,
        int ignoredLines,
        int errorLines,
        int malformedLines,
        Set<SourceFamily> malformedFamilies,
        Set<SourceFamily> absentFamilies,
        Set<SourceFamily> errorFamilies) {

    public StatParseResult {
        files = List.copyOf(files);
        malformedFamilies = copy(malformedFamilies);
        absentFamilies = copy(absentFamilies);
        errorFamilies = copy(errorFamilies);
    }

    private static Set<SourceFamily> copy(Set<SourceFamily> in) {
        return in.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(in));
    }

    public boolean hasDeviceClock() {
        return deviceEpoch != null;
    }

    /**
     * True when the output can be planned from: there is a device clock, and at least one stat or stat-error line
     * (an output of only a clock is an unreadable output, not eight absent sources).
     */
    public boolean usable() {
        return deviceEpoch != null && !(files.isEmpty() && errorLines == 0);
    }

    /** Every stat line whose file name is covered by the family glob, in output order. */
    public List<FileStat> membersOf(SourceFamily family) {
        List<FileStat> out = new ArrayList<>();
        for (FileStat file : files) {
            if (family.covers(file.baseName())) {
                out.add(file);
            }
        }
        return out;
    }
}
