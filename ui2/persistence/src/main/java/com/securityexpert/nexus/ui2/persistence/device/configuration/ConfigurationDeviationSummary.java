package com.securityexpert.nexus.ui2.persistence.device.configuration;

import java.util.List;

/**
 * Structural deviation summary for a {@code changed} run (14I DV-2): which
 * sections gained, lost, or altered entry counts relative to the predecessor.
 *
 * <p>Status {@link Status#COMPUTED} is used when the predecessor's index was
 * available; {@link Status#NOT_COMPUTABLE} is used when the predecessor's
 * index was never stored, so the summary cannot be derived without treating a
 * missing predecessor as an empty one -- which violates AC-5.</p>
 *
 * <p>A run whose change state is {@code first_run} or {@code unchanged}
 * carries no summary ({@link ConfigurationRun#deviationSummary()} is
 * empty). Only a {@code changed} run carries one.</p>
 */
public record ConfigurationDeviationSummary(Status status, List<ConfigurationDeviationEntry> entries) {

    /** Whether the summary was derivable from the predecessor's stored index. */
    public enum Status {
        /** Predecessor index was available; {@code entries} is the complete diff. */
        COMPUTED,
        /**
         * Predecessor index was not stored; comparison is not possible without
         * treating absence as an empty index (AC-5 forbids that).
         */
        NOT_COMPUTABLE
    }

    public ConfigurationDeviationSummary {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** Convenience factory for the not-computable case. */
    public static ConfigurationDeviationSummary notComputable() {
        return new ConfigurationDeviationSummary(Status.NOT_COMPUTABLE, List.of());
    }
}
