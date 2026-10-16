package com.securityexpert.nexus.ui2.packetanalysis.store;

/**
 * A run that stopped at a resource limit it was configured to respect: the analysis memory limit
 * ({@link AnalysisFailure#MEMORY_LIMIT}) or a window the analysis needs but the input does not allow
 * ({@link AnalysisFailure#WINDOW_REQUIRED}). It carries a reason code (shown in the run ledger) and a message that
 * tells the user what to change. The message never quotes capture content, names or paths.
 */
public final class AnalysisLimitException extends RuntimeException {

    private final String reason;

    public AnalysisLimitException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /** The reason code, {@code [A-Z][A-Z0-9_]*}. */
    public String reason() {
        return reason;
    }
}
