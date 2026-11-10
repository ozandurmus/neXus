package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Infoblox {@code upgradestatus?type=GRID&_return_fields=current_version}: the grid's running NIOS version
 * (docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md, amendment A-2026-11-10). Only {@code current_version} is read; the
 * object reference and any other field are ignored. A value that is not a plain version string gives nothing.
 */
public final class InfobloxUpgradeStatusParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern VERSION = Pattern.compile("[0-9][A-Za-z0-9._+-]{0,59}");

    private InfobloxUpgradeStatusParser() {
    }

    /** The {@code current_version} of the first entry that has one. Never throws. */
    public static Optional<String> currentVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null) {
                return Optional.empty();
            }
            if (root.isObject() && root.path("result").isArray()) {
                root = root.path("result");
            }
            if (root.isObject()) {
                return version(root);
            }
            if (root.isArray()) {
                for (JsonNode entry : root) {
                    Optional<String> version = version(entry);
                    if (version.isPresent()) {
                        return version;
                    }
                }
            }
            return Optional.empty();
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException | StackOverflowError e) {
            return Optional.empty();
        }
    }

    private static Optional<String> version(JsonNode entry) {
        JsonNode value = entry == null ? null : entry.get("current_version");
        if (value == null || !value.isTextual()) {
            return Optional.empty();
        }
        String text = value.asText().strip();
        return VERSION.matcher(text).matches() ? Optional.of(text) : Optional.empty();
    }
}
