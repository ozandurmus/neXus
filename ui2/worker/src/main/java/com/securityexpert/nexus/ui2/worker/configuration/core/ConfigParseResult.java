package com.securityexpert.nexus.ui2.worker.configuration.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;

public record ConfigParseResult(
        ConfigVendor vendor,
        Optional<String> canonicalHash,
        int withheldLineCount,
        String sanitizedText,
        List<ConfigurationIndexEntry> index,
        List<ConfigSection> sections,
        List<ConfigHighlight> highlights,
        int totalSettingsCount) {

    public ConfigParseResult {
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(canonicalHash, "canonicalHash");
        Objects.requireNonNull(sanitizedText, "sanitizedText");
        index = index == null ? List.of() : List.copyOf(index);
        sections = sections == null ? List.of() : List.copyOf(sections);
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
    }
}
