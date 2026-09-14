package com.securityexpert.nexus.ui2.persistence.device.configuration;

import java.util.Optional;

/**
 * One {@code device_configuration_override} row (14G CG-7a/CG-7b): a
 * {@code src=local} element path, never its value. {@code panoramaSource}
 * names the template/device-group definition the cross-check (CG-7c) found
 * for the same path, or is empty when no Panorama-side definition was
 * found for it.
 */
public record ConfigurationOverride(String context, String category, String elementPath, Optional<String> panoramaSource) {

    public ConfigurationOverride {
        panoramaSource = panoramaSource == null ? Optional.empty() : panoramaSource;
    }
}
