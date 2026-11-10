package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;

/**
 * Symantec Management Center {@code GET /api/devices/{uuid}/license} (docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md
 * section 2): one {@code LICENSE} per entry of {@code components[]} from {@code componentName}, {@code expirationDate}
 * (ISO {@code yyyy-MM-dd}, a longer timestamp read by its first ten characters) and {@code validity}. Evidence grade
 * {@code MANAGER_REPORTED}; the member is the managed device's name from the same device list. Serial and part numbers
 * are never read.
 */
public final class ManagementCenterLicenseParser {

    public static final String READ_KIND = "mc_device_license";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MEMBER_MAX = 255;

    private ManagementCenterLicenseParser() {
    }

    /** {@code member}: the managed device's display name (null when unknown). Never throws. */
    public static LicenseParse parse(String member, String raw) {
        try {
            return doParse(member, raw);
        } catch (java.io.IOException | RuntimeException | StackOverflowError e) {
            return raw == null || raw.isBlank() ? LicenseParse.of(raw, List.of(), 0) : LicenseParse.unparsed();
        }
    }

    private static LicenseParse doParse(String member, String raw) throws java.io.IOException {
        if (raw == null || raw.isBlank()) {
            return LicenseParse.of(raw, List.of(), 0);
        }
        JsonNode root = JSON.readTree(raw.length() > LifecycleText.MAX_CHARS ? raw.substring(0, LifecycleText.MAX_CHARS) : raw);
        JsonNode components = root == null ? null : root.path("components");
        if (components == null || !components.isArray()) {
            return LicenseParse.unparsed();
        }
        String memberRef = memberRef(member);
        List<LicenseItem> items = new ArrayList<>();
        int skipped = 0;
        for (JsonNode component : components) {
            if (!component.isObject()) {
                continue;
            }
            String label = LifecycleText.label(text(component, "componentName"));
            if (label == null) {
                skipped++;
                continue;
            }
            String validity = LifecycleText.collapse(text(component, "validity"));
            String validityLower = validity.toLowerCase(Locale.ROOT);
            String when = text(component, "expirationDate");
            LocalDate expires = LifecycleText.isoDate(when);
            String state;
            if (validityLower.contains("expired") || validityLower.equals("invalid")) {
                state = "EXPIRED";
            } else if (expires != null) {
                state = "VALID";
            } else if (LifecycleText.isNever(when) || validityLower.contains("perpetual") || validityLower.contains("permanent")) {
                state = "PERMANENT";
            } else {
                state = "UNKNOWN";
            }
            String detail = validity.isEmpty() ? null : LifecycleText.detail("validity=" + validity);
            items.add(new LicenseItem(READ_KIND, "LICENSE", label, state, expires, detail, memberRef, "MANAGER_REPORTED", null));
        }
        if (items.isEmpty()) {
            return skipped > 0 ? new LicenseParse(ParseOutcome.UNPARSED, List.of(), skipped) : LicenseParse.empty(0);
        }
        return new LicenseParse(ParseOutcome.COLLECTED, items, skipped);
    }

    private static String memberRef(String member) {
        String m = LifecycleText.collapse(member);
        if (m.isEmpty()) {
            return null;
        }
        return m.length() > MEMBER_MAX ? m.substring(0, MEMBER_MAX) : m;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isValueNode() && !value.isNull() ? value.asText() : "";
    }
}
