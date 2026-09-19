package com.securityexpert.nexus.ui2.worker.configuration.core;

import java.util.Objects;

public record ConfigHighlight(
        String label,
        String value,
        String section,
        String sectionLabel) {

    public ConfigHighlight {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(sectionLabel, "sectionLabel");
    }
}
