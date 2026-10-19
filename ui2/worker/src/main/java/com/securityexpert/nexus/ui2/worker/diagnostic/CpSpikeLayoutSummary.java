package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.jobs.systemlog.CpSpikeLayoutReview;

/**
 * The in-memory half of the Spike Detective layout review (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §8.3; PO approval
 * 2026-10-19): it reads the C4 parent listing, picks the ONE directory C5 may list, and reduces the C5 listing to a
 * summary of counts, sizes and name CLASSES. Lines are classified by shape, never by position, so the login-shell
 * banner that 25 of 53 gateways print first (findings §3.2) is ignored and only counted.
 *
 * <p>A data directory is an entry of type {@code d} with link count 2 (§8.3: all 6,334 measured data directories had
 * link count 2). A plain file is an entry of type {@code -}. Every other shape is counted, never selected.</p>
 *
 * <p>Nothing returned by {@link #summary} holds a name, a path, a host or a line: names become classes through the
 * closed vocabulary of the phase A summaries ({@link CpSyslogMeasurementSummary#classify}: known stems stay, digit runs
 * become {@code N}/{@code NN}/{@code NNN}/{@code N+}/{@code YYYYMMDD}/{@code EPOCH}, other words become {@code X}). The
 * one chosen directory name exists only inside {@link Selection}, which the executor keeps in memory to build C5.</p>
 */
final class CpSpikeLayoutSummary {
    static final String SCHEMA = "cp_spike_layout_review_v1";
    static final String PARENT = "/var/log/spike_detective";
    /** §3.4: spike data per directory (PENDING); reported against the members of the contract's two forms. */
    static final long SPIKE_DATA_BOUND_BYTES = 16L * 1024 * 1024;
    private static final Pattern TOTAL = Pattern.compile("^total [0-9]{1,18}$");
    private static final Pattern LS_ERROR = Pattern.compile("^ls: cannot (?:access|open directory) .*$");
    private static final Pattern NO_SUCH = Pattern.compile("(?i)no such file or directory");
    private static final Pattern DENIED = Pattern.compile("(?i)permission denied");
    /** §8.3 member forms, unverified until this review: {@code perf_cpu_<N>.log} and {@code profiling_summary.elg}. */
    private static final Pattern PERF_CPU = Pattern.compile("^perf_cpu_[0-9]{1,6}\\.log$");
    private static final Pattern PROFILING = Pattern.compile("^profiling_summary\\.elg$");

    private CpSpikeLayoutSummary() {}

    /** How a listing read: present (with its entries), absent, or unreadable (typed reason). */
    enum State { PRESENT, ABSENT, UNKNOWN }

    record Listing(State state, String reason, List<CpLsListing.Entry> entries, int ignoredLines, int rejectedLines,
            int exitStatus) {
    }

    /** One listing by shape. An {@code ls} error line or an empty output with a non-zero exit is ABSENT. */
    static Listing parse(String output, int exitStatus, String section) {
        String text = output == null ? "" : output;
        boolean total = false, missing = false, denied = false;
        int errors = 0;
        for (String raw : text.split("\\R", -1)) {
            String line = raw.strip();
            if (TOTAL.matcher(line).matches()) total = true;
            if (LS_ERROR.matcher(line).matches()) {
                errors++;
                missing |= NO_SUCH.matcher(line).find();
                denied |= DENIED.matcher(line).find();
            }
        }
        CpLsListing.Result parsed = CpLsListing.parse(text, section);
        // An ls error line is shape-matched as "ignored" by the entry parser; it is not a banner line.
        int ignored = Math.max(0, parsed.ignoredLines() - errors);
        List<CpLsListing.Entry> entries = parsed.in(section);
        if (denied && entries.isEmpty())
            return new Listing(State.UNKNOWN, "PERMISSION_DENIED", List.of(), ignored, parsed.rejectedLines(), exitStatus);
        if (missing && entries.isEmpty() || !total && entries.isEmpty() && exitStatus != 0 && parsed.rejectedLines() == 0)
            return new Listing(State.ABSENT, null, List.of(), ignored, parsed.rejectedLines(), exitStatus);
        if (!total && entries.isEmpty())
            return new Listing(State.UNKNOWN, "LISTING_UNREADABLE", List.of(), ignored, parsed.rejectedLines(), exitStatus);
        return new Listing(State.PRESENT, null, entries, ignored, parsed.rejectedLines(), exitStatus);
    }

    static boolean dataDirectory(CpLsListing.Entry e) {
        return e.type() == 'd' && e.links() == 2;
    }

    /**
     * The C4 decision. {@code chosen} is the newest settled data directory (mtime at least
     * {@link CpSpikeLayoutReview#SETTLE_SECONDS} older than {@code clockEpoch}); {@code nameAccepted} is false when its
     * name is outside the §8.3 safe form, and C5 is then never sent. Ties on mtime are broken by name, so the choice is
     * deterministic.
     */
    record Selection(Optional<CpLsListing.Entry> chosen, boolean nameAccepted, int unsettledNewerSkipped) {
        Optional<String> directoryCommand() {
            return chosen.filter(c -> nameAccepted).flatMap(c -> CpSpikeLayoutReview.directoryCommand(c.name()));
        }
    }

    static Selection select(Listing parent, long clockEpoch) {
        List<CpLsListing.Entry> directories = new ArrayList<>(parent.entries().stream()
                .filter(CpSpikeLayoutSummary::dataDirectory).toList());
        directories.sort(Comparator.comparingLong(CpLsListing.Entry::mtime).reversed()
                .thenComparing(CpLsListing.Entry::name));
        int skipped = 0;
        for (CpLsListing.Entry e : directories) {
            if (e.mtime() > clockEpoch - CpSpikeLayoutReview.SETTLE_SECONDS) { skipped++; continue; }
            return new Selection(Optional.of(e), CpSpikeLayoutReview.safeDirectoryName(e.name()), skipped);
        }
        return new Selection(Optional.empty(), false, skipped);
    }

    /** The C4 part: counts of each entry shape. */
    static Map<String, Object> parentView(Listing parent, long clockEpoch) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("state", parent.state().name());
        if (parent.reason() != null) view.put("reason", parent.reason());
        int data = 0, otherDirs = 0, plain = 0, plainOneLink = 0, twoLinkNonDir = 0, others = 0, unsettled = 0, future = 0;
        for (CpLsListing.Entry e : parent.entries()) {
            if (dataDirectory(e)) {
                data++;
                if (e.mtime() > clockEpoch - CpSpikeLayoutReview.SETTLE_SECONDS) unsettled++;
                if (e.mtime() > clockEpoch) future++;
            } else if (e.type() == 'd') {
                otherDirs++;
            } else if (e.type() == '-') {
                plain++;
                if (e.links() == 1) plainOneLink++;
                if (e.links() == 2) twoLinkNonDir++;
            } else {
                others++;
                if (e.links() == 2) twoLinkNonDir++;
            }
        }
        view.put("entryCount", parent.entries().size());
        view.put("dataDirectories", data);
        view.put("plainFiles", plain);
        view.put("plainFilesLinkCountOne", plainOneLink);
        view.put("otherDirectories", otherDirs);
        view.put("linkCountTwoNotDirectory", twoLinkNonDir);
        view.put("otherEntries", others);
        view.put("dataDirectoriesUnsettled", unsettled);
        view.put("dataDirectoriesMtimeAheadOfClock", future);
        view.put("ignoredLines", parent.ignoredLines());
        view.put("rejectedLines", parent.rejectedLines());
        view.put("exitStatus", parent.exitStatus());
        return view;
    }

    /** The C5 part: member count, member name classes with per-class count and size min/max/sum, the age class. */
    static Map<String, Object> directoryView(CpLsListing.Entry chosen, Listing members, long clockEpoch,
            CpSyslogMeasurementSummary.KeyedTokens tokens) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("nameClass", CpSyslogMeasurementSummary.classify(chosen.name(), tokens).clazz());
        view.put("ageClass", ageClass(clockEpoch - chosen.mtime()));
        if (members == null) return view;
        view.put("state", members.state().name());
        if (members.reason() != null) view.put("reason", members.reason());
        Map<String, long[]> classes = new TreeMap<>();   // count, min, max, sum
        int files = 0, dirs = 0, links = 0, others = 0, perfCpu = 0, profiling = 0, otherForms = 0;
        long fileBytes = 0, contractBytes = 0;
        for (CpLsListing.Entry e : members.entries()) {
            String prefix = e.type() == 'd' ? "dir:" : e.type() == 'l' ? "link:" : e.type() == '-' ? "" : "other:";
            String key = prefix + CpSyslogMeasurementSummary.classify(e.name(), tokens).clazz();
            if (!classes.containsKey(key) && classes.size() >= CpSyslogMeasurementSummary.MAX_CLASSES)
                key = CpSyslogMeasurementSummary.OTHER;
            long[] agg = classes.computeIfAbsent(key, k -> new long[] {0, Long.MAX_VALUE, Long.MIN_VALUE, 0});
            agg[0]++;
            agg[1] = Math.min(agg[1], e.size());
            agg[2] = Math.max(agg[2], e.size());
            agg[3] += e.size();
            switch (e.type()) {
                case '-' -> {
                    files++;
                    fileBytes += e.size();
                    if (PERF_CPU.matcher(e.name()).matches()) { perfCpu++; contractBytes += e.size(); }
                    else if (PROFILING.matcher(e.name()).matches()) { profiling++; contractBytes += e.size(); }
                    else otherForms++;
                }
                case 'd' -> { dirs++; otherForms++; }
                case 'l' -> { links++; otherForms++; }
                default -> { others++; otherForms++; }
            }
        }
        view.put("memberCount", members.entries().size());
        view.put("files", files);
        view.put("directories", dirs);
        view.put("links", links);
        view.put("otherEntries", others);
        view.put("fileBytes", fileBytes);
        Map<String, Object> byClass = new LinkedHashMap<>();
        classes.forEach((name, agg) -> {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("count", agg[0]);
            stats.put("minBytes", agg[1]);
            stats.put("maxBytes", agg[2]);
            stats.put("bytes", agg[3]);
            byClass.put(name, stats);
        });
        view.put("nameClasses", byClass);
        Map<String, Object> forms = new LinkedHashMap<>();
        forms.put("perfCpuLog", perfCpu);
        forms.put("profilingSummaryElg", profiling);
        forms.put("otherMembers", otherForms);
        view.put("contractForms", forms);
        view.put("contractFormBytes", contractBytes);
        view.put("contractFormBytesWithinBound", contractBytes <= SPIKE_DATA_BOUND_BYTES);
        view.put("ignoredLines", members.ignoredLines());
        view.put("rejectedLines", members.rejectedLines());
        view.put("exitStatus", members.exitStatus());
        return view;
    }

    /** Age of the chosen directory against the clock, as a class (a settled directory is at least 10 minutes old). */
    static String ageClass(long ageSeconds) {
        if (ageSeconds < 0) return "AHEAD_OF_CLOCK";
        if (ageSeconds < 600) return "UNDER_10M";
        if (ageSeconds < 3_600) return "10M_TO_1H";
        if (ageSeconds < 6 * 3_600) return "1H_TO_6H";
        if (ageSeconds < 24 * 3_600) return "6H_TO_24H";
        if (ageSeconds < 7 * 86_400) return "1D_TO_7D";
        if (ageSeconds < 30 * 86_400) return "7D_TO_30D";
        return "30D_OR_MORE";
    }

    /** The common head of every summary row. */
    static Map<String, Object> head(String status, String outcome, long clockEpoch) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", status);
        summary.put("schema", SCHEMA);
        summary.put("outcome", outcome);
        summary.put("measuredAtEpoch", clockEpoch);
        // No approved command of this review prints the device clock; settling and age use the neXus clock.
        summary.put("ageBasis", "NEXUS_CLOCK");
        return summary;
    }
}
