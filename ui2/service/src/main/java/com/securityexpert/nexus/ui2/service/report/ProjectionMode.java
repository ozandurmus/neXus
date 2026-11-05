package com.securityexpert.nexus.ui2.service.report;

import java.util.Optional;

/** V190 {@code report_run.projection_mode}. MASKED is the default and the only projection a replay viewer gets. */
public enum ProjectionMode {
    MASKED, RAW;

    public static Optional<ProjectionMode> parse(String value) {
        if (value == null || value.isBlank()) return Optional.of(MASKED);
        for (ProjectionMode m : values()) {
            if (m.name().equals(value)) return Optional.of(m);
        }
        return Optional.empty();
    }
}
