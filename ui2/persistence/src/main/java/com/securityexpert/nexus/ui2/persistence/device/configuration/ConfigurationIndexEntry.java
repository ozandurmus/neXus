package com.securityexpert.nexus.ui2.persistence.device.configuration;

import java.util.Optional;

/**
 * One {@code device_configuration_index} row (13F CF-1, 14G CG-6): a
 * section/category count within one context. {@code source} carries Palo
 * Alto's per-element provenance (CG-7a: {@code tpl}/{@code dg}/{@code
 * shared}/{@code local}) counted per category (CG-7b); empty for Check
 * Point, which has no such attribute.
 */
public record ConfigurationIndexEntry(String context, String section, Optional<String> source, int entryCount) {

    public ConfigurationIndexEntry {
        source = source == null ? Optional.empty() : source;
    }
}
