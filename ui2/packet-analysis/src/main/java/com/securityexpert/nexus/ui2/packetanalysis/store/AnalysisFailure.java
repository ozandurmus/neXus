package com.securityexpert.nexus.ui2.packetanalysis.store;

import java.util.Locale;

/**
 * Classifies why an analysis stopped. A run that reaches a memory limit must end as a clean, explained failure,
 * never as a killed process: DuckDB raises an out-of-memory error when a query needs more than {@code memory_limit}
 * and cannot spill (the {@code max_temp_directory_size} is reached, or the operator cannot spill), and the JVM raises
 * {@link OutOfMemoryError} when the heap is exhausted. Both are the same condition for the user: the analysis is too
 * large for the configured budget.
 */
public final class AnalysisFailure {

    /** The analysis needed more memory (including spill space) than its budget allows. */
    public static final String MEMORY_LIMIT = "ANALYSIS_MEMORY_LIMIT";
    /** The analysis range exceeds the budget and no bounded window fits it. */
    public static final String WINDOW_REQUIRED = "ANALYSIS_WINDOW_REQUIRED";
    /** Any other engine failure. */
    public static final String ENGINE_FAILED = "ENGINE_FAILED";

    /** What the user is told when the memory limit was reached. */
    public static final String MEMORY_LIMIT_MESSAGE = "The analysis needs more memory than its budget allows. "
            + "Narrow the analysis window (start and end time) or analyse fewer or smaller captures, then run it again.";
    /** What the user is told when no bounded window fits. */
    public static final String WINDOW_REQUIRED_MESSAGE = "The capture range is larger than the analysis budget and no "
            + "bounded window covers at least two capture points. Choose an analysis window (start and end time) "
            + "that covers the incident, then run it again.";

    private static final int MAX_DEPTH = 24;

    private AnalysisFailure() {
    }

    /** True when {@code failure} or one of its causes is a DuckDB or JVM out-of-memory condition. */
    public static boolean isMemoryLimit(Throwable failure) {
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof OutOfMemoryError) {
                return true;
            }
            if (t instanceof AnalysisLimitException e && MEMORY_LIMIT.equals(e.reason())) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("out of memory error")) {
                return true;
            }
        }
        return false;
    }

    /** The reason code for a failure: an explicit limit, a memory limit, else {@value #ENGINE_FAILED}. */
    public static String reasonOf(Throwable failure) {
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof AnalysisLimitException e) {
                return e.reason();
            }
        }
        return isMemoryLimit(failure) ? MEMORY_LIMIT : ENGINE_FAILED;
    }

    /** The user-facing message for a reason code; {@code null} for {@value #ENGINE_FAILED}. */
    public static String messageFor(String reason) {
        return switch (reason) {
            case MEMORY_LIMIT -> MEMORY_LIMIT_MESSAGE;
            case WINDOW_REQUIRED -> WINDOW_REQUIRED_MESSAGE;
            default -> null;
        };
    }

    /**
     * Turns a memory-limit failure into the typed exception; any other failure is returned unchanged. Call as
     * {@code throw AnalysisFailure.classify(e)}.
     */
    public static RuntimeException classify(RuntimeException failure) {
        if (failure instanceof AnalysisLimitException) {
            return failure;
        }
        if (isMemoryLimit(failure)) {
            return new AnalysisLimitException(MEMORY_LIMIT, MEMORY_LIMIT_MESSAGE, failure);
        }
        return failure;
    }
}
