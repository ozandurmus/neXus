package com.securityexpert.nexus.ui2.worker.configuration.core;

import java.util.Objects;
import java.util.Optional;

public record ConfigSetting(
        String setting,
        String value,
        String origin,
        Optional<String> context) {

    public ConfigSetting {
        Objects.requireNonNull(setting, "setting");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(context, "context");
    }

    public static ConfigSetting of(String setting, String value, String origin, String context) {
        return new ConfigSetting(setting, value, origin, Optional.ofNullable(context));
    }
}
