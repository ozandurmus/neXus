package com.securityexpert.nexus.ui2.worker.configuration.core;

import java.util.List;
import java.util.Objects;

public record ConfigSection(
        String id,
        String label,
        int count,
        List<ConfigSetting> settings) {

    public ConfigSection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        settings = settings == null ? List.of() : List.copyOf(settings);
    }
}
