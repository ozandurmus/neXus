package com.securityexpert.nexus.ui2.worker.configuration.core;

import java.util.Objects;
import java.util.Optional;

public record ConfigParseContext(
        String deviceId,
        ConfigVendor vendor,
        ConfigFormat format,
        String entityType,
        Optional<String> contextRef) {

    public ConfigParseContext {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(contextRef, "contextRef");
    }

    public static ConfigParseContext of(String deviceId, ConfigVendor vendor, ConfigFormat format) {
        return new ConfigParseContext(deviceId, vendor, format, "physical", Optional.empty());
    }
}
