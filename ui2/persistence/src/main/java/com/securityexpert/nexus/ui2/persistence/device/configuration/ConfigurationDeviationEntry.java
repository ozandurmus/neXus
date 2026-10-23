package com.securityexpert.nexus.ui2.persistence.device.configuration;

/**
 * One row of a structural deviation summary (14I DV-2): a section that
 * gained, lost, or altered entries between two successive configuration
 * runs. Carries only the section name, its context, and the two entry
 * counts -- never a configuration value.
 *
 * <p>{@code oldCount} is absent (0) when the section is {@code ADDED};
 * {@code newCount} is absent (0) when the section is {@code REMOVED}.
 * Both are present and unequal when the section is {@code RECOUNTED}, and
 * both equal when the section is {@code MODIFIED}.</p>
 */
public record ConfigurationDeviationEntry(
        String context,
        String section,
        java.util.Optional<String> source,
        DeviationKind kind,
        int oldCount,
        int newCount) {

    /** The direction of the structural change for this section. */
    public enum DeviationKind {
        /** Section appears only in the new index (not in the predecessor). */
        ADDED,
        /** Section appears only in the old index (absent from the new run). */
        REMOVED,
        /** Section appears in both indexes with a different entry count. */
        RECOUNTED,
        /**
         * Section keeps its entry count but its sanitized lines differ (V176; Check Point only, computed by the
         * worker at record time from the previous run's sanitized text). Counts only; never a value.
         */
        MODIFIED
    }
}
