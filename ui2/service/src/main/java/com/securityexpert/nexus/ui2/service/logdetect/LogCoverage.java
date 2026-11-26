package com.securityexpert.nexus.ui2.service.logdetect;

import java.util.Map;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.RunFacts;

/**
 * The coverage state of one (device, source, collection job) (design section 7.4). A window that cannot be evaluated
 * is UNKNOWN in the operator's reading and is never shown as healthy: every non-{@code EVALUATED} state names why.
 */
public final class LogCoverage {
    public static final String EVALUATED = "EVALUATED";
    public static final String BASELINE_LEARNING = "BASELINE_LEARNING";
    public static final String NOT_EVALUABLE = "NOT_EVALUABLE";
    public static final String GAP = "GAP";
    public static final String NOT_COLLECTED = "NOT_COLLECTED";
    public static final String MASKING_UNAVAILABLE = "MASKING_UNAVAILABLE";
    public static final String UNPARSED_HIGH = "UNPARSED_HIGH";
    public static final String UNSUPPORTED = "UNSUPPORTED";
    /**
     * The window's chunks were indexed but the findings of a device-day it feeds could not be evaluated (a store or read
     * error during evaluation). UNKNOWN to the reader, never "no findings"; the next pass evaluates the day again.
     */
    public static final String EVALUATION_FAILED = "EVALUATION_FAILED";
    /** More than this share of unparsed lines makes the window UNPARSED_HIGH (PROVISIONAL, design 7.4). */
    public static final double UNPARSED_SHARE_LIMIT = 0.05;
    /** The ratified 7-day and 14-day history thresholds of the 2026-11-11 record (design 7.4). */
    public static final int NOT_EVALUABLE_BELOW_DAYS = 7;
    public static final int BASELINE_LEARNING_BELOW_DAYS = 14;

    /** A state and the closed-vocabulary reason that explains it. */
    public record State(String state, String reason) {
    }

    private LogCoverage() {
    }

    /** True for the states in which the window was evaluated (the others are UNKNOWN to the reader). */
    public static boolean evaluated(String state) {
        return EVALUATED.equals(state) || BASELINE_LEARNING.equals(state) || NOT_EVALUABLE.equals(state);
    }

    /**
     * @param analysed whether P1 analyses the source at all
     * @param run the run outcome, the gap kinds and the chunk count of the (device, job, source)
     * @param indexStatuses status of each indexed chunk of the window ({@code status -> chunks})
     * @param lines indexed lines of the window; {@code unparsed} of them without a readable time
     * @param historyDays distinct days with an indexed chunk, all time
     */
    public static State of(boolean analysed, RunFacts run, Map<String, Integer> indexStatuses, long lines, long unparsed,
            int historyDays) {
        if (!analysed) return new State(UNSUPPORTED, "SOURCE_NOT_ANALYSED_IN_P1");
        if (indexStatuses.containsKey("MASKING_UNAVAILABLE")) return new State(MASKING_UNAVAILABLE, "ESTATE_DICTIONARY_UNAVAILABLE");
        String outcome = run.outcome();
        if (outcome == null) return new State(NOT_COLLECTED, "NO_RUN_RECORD");
        if (outcome.equals("BACKLOG") || outcome.equals("GAP")) return new State(GAP, "RUN_" + outcome);
        if (!outcome.equals("COLLECTED") && !outcome.equals("NO_NEW_DATA")) return new State(NOT_COLLECTED, "RUN_" + outcome);
        if (!run.gapKinds().isEmpty()) return new State(GAP, "GAP_" + run.gapKinds().get(0));
        for (String bad : new String[] {"UNREADABLE", "LINE_TOO_LONG", "LINE_COUNT_MISMATCH", "STORE_UNAVAILABLE"}) {
            if (indexStatuses.containsKey(bad)) return new State(UNPARSED_HIGH, "CHUNK_" + bad);
        }
        if (indexStatuses.containsKey("PRUNED")) return new State(NOT_COLLECTED, "EVIDENCE_PRUNED_BEFORE_INDEX");
        if (indexStatuses.containsKey("NOT_APPLICABLE")) return new State(UNSUPPORTED, "DEVICE_NOT_APPLICABLE");
        if (lines > 0 && (double) unparsed / lines > UNPARSED_SHARE_LIMIT) return new State(UNPARSED_HIGH, "UNPARSED_OVER_5_PERCENT");
        return historyState(historyDays);
    }

    /** The history-tier coverage state once collection, masking and parsing checks have passed. */
    public static State historyState(int historyDays) {
        if (historyDays < NOT_EVALUABLE_BELOW_DAYS) return new State(NOT_EVALUABLE, "HISTORY_UNDER_7_DAYS");
        if (historyDays < BASELINE_LEARNING_BELOW_DAYS) return new State(BASELINE_LEARNING, "HISTORY_7_TO_13_DAYS");
        return new State(EVALUATED, "LAYER_A_ONLY");
    }
}
