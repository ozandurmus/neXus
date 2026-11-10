package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;
import com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorPlan;

/**
 * Infoblox {@code license:gridwide} (docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md section 2): one {@code LICENSE} per
 * entry from {@code type} (label), {@code expiry_date} (epoch seconds, read with
 * {@link HttpsVendorPlan#infobloxExpiryDate}), {@code expiration_status} and {@code limit} (detail). Member {@code grid}.
 * The {@code _ref} and any {@code key} field are never read.
 */
public final class InfobloxGridwideLicenseParser {

    public static final String READ_KIND = "infoblox_license_gridwide";
    public static final String MEMBER = "grid";
    private static final ObjectMapper JSON = new ObjectMapper();

    private InfobloxGridwideLicenseParser() {
    }

    /** Never throws. */
    public static LicenseParse parse(String raw) {
        try {
            return doParse(raw);
        } catch (java.io.IOException | RuntimeException | StackOverflowError e) {
            return raw == null || raw.isBlank() ? LicenseParse.of(raw, List.of(), 0) : LicenseParse.unparsed();
        }
    }

    private static LicenseParse doParse(String raw) throws java.io.IOException {
        if (raw == null || raw.isBlank()) {
            return LicenseParse.of(raw, List.of(), 0);
        }
        JsonNode root = JSON.readTree(raw.length() > LifecycleText.MAX_CHARS ? raw.substring(0, LifecycleText.MAX_CHARS) : raw);
        JsonNode array = root == null ? null : root.isArray() ? root : root.path("result");
        if (array == null || !array.isArray()) {
            return LicenseParse.unparsed();
        }
        List<LicenseItem> items = new ArrayList<>();
        int skipped = 0;
        for (JsonNode entry : array) {
            if (!entry.isObject()) {
                continue;
            }
            String label = LifecycleText.label(text(entry, "type"));
            if (label == null) {
                skipped++;
                continue;
            }
            LocalDate expires = null;
            String iso = HttpsVendorPlan.infobloxExpiryDate(entry.get("expiry_date")).orElse(null);
            if (iso != null) {
                try {
                    expires = LocalDate.parse(iso);
                } catch (DateTimeParseException e) {
                    expires = null;
                }
            }
            String status = text(entry, "expiration_status").strip().toUpperCase(Locale.ROOT);
            String state;
            if (status.equals("PERMANENT")) {
                state = "PERMANENT";
            } else if (status.equals("EXPIRED")) {
                state = "EXPIRED";
            } else if (expires != null) {
                state = "VALID";
            } else {
                state = "UNKNOWN";
            }
            String limit = LifecycleText.collapse(text(entry, "limit"));
            String detail = limit.isEmpty() ? null : LifecycleText.detail("limit=" + limit);
            items.add(new LicenseItem(READ_KIND, "LICENSE", label, state, expires, detail, MEMBER, "DIRECT_DEVICE", null));
        }
        if (items.isEmpty()) {
            return skipped > 0 ? new LicenseParse(ParseOutcome.UNPARSED, List.of(), skipped) : LicenseParse.empty(0);
        }
        return new LicenseParse(ParseOutcome.COLLECTED, items, skipped);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isValueNode() && !value.isNull() ? value.asText() : "";
    }
}
