package com.securityexpert.nexus.ui2.worker.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;
import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * A Radware Cyber Controller's device list ({@code GET /mgmt/system/config/itemlist/alldevices}, V67) as discovery
 * candidates (PO, 2026-09-24: the DefensePro devices under the Cyber Controller, "not in neXus", import).
 *
 * <p>Field names measured on 10.13 (children, deleted, deviceVersion, formFactor, highAvailabilityPriorityEnum,
 * managementIp, name, ormId, parentOrmId, status, supportTemplate, treeType, type); their <em>values</em> are
 * MEASURE FIRST, so nothing here depends on one: a node with a management address is a device, the nearest node
 * above it without one is its site (shown as the domain), {@code ormId} is the opaque stable identifier (never parsed),
 * and only a type naming DefensePro is importable -- neXus backs up DefensePro through the Cyber Controller; anything
 * else it lists is shown, never imported.</p>
 */
public final class RadwareDiscoveryCandidateMapper {

    public static final String KIND_PREFIX = "RADWARE_";

    private RadwareDiscoveryCandidateMapper() {
    }

    public static List<DiscoveryCandidateRecord> map(String runId, JsonNode list) {
        List<DiscoveryCandidateRecord> out = new ArrayList<>();
        walk(runId, list, Optional.empty(), out);
        return out;
    }

    private static void walk(String runId, JsonNode node, Optional<String> site, List<DiscoveryCandidateRecord> out) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> walk(runId, child, site, out));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Optional<String> address = text(node, "managementIp");
        Optional<String> name = text(node, "name");
        boolean deleted = node.path("deleted").asBoolean(false);
        Optional<String> ormId = text(node, "ormId");
        if (address.isPresent() && ormId.isPresent() && !deleted) {
            String kind = kindOf(text(node, "type").orElse("UNKNOWN"));
            out.add(new DiscoveryCandidateRecord(OpaqueId.random().value(), runId, "radware", ormId.get(), site, kind,
                    name.orElse(address.get()), address, Optional.empty(), Optional.empty(), Optional.empty(),
                    text(node, "formFactor"), text(node, "deviceVersion"), text(node, "status"),
                    kind.contains("DEFENSEPRO"), Optional.empty()));
        }
        // A node without a management address groups what is under it (a site): its name becomes their domain.
        Optional<String> childSite = address.isEmpty() && name.isPresent() ? name : site;
        node.fields().forEachRemaining(e -> {
            if (e.getValue().isContainerNode()) {
                walk(runId, e.getValue(), childSite, out);
            }
        });
    }

    /** {@code DefensePro} -> {@code RADWARE_DEFENSEPRO}; the vendor's own word, upper-cased, never interpreted. */
    static String kindOf(String type) {
        String k = type.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        return KIND_PREFIX + (k.isEmpty() ? "UNKNOWN" : k);
    }

    public static Map<String, Integer> outcomeSummary(List<DiscoveryCandidateRecord> records) {
        Map<String, Integer> counts = new TreeMap<>();
        for (DiscoveryCandidateRecord r : records) {
            counts.merge(r.kind(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * The categorical values the list carries (type, status, form factor, HA priority, tree type) -- never names or
     * addresses -- for the first measurement record.
     */
    public static Map<String, java.util.Set<String>> categoricalValues(JsonNode list) {
        Map<String, java.util.Set<String>> values = new TreeMap<>();
        collect(list, values, 0);
        return values;
    }

    private static void collect(JsonNode node, Map<String, java.util.Set<String>> values, int depth) {
        if (node == null || depth > 20) {
            return;
        }
        if (node.isObject()) {
            for (String field : List.of("type", "status", "formFactor", "highAvailabilityPriorityEnum", "treeType")) {
                text(node, field).ifPresent(v -> values.computeIfAbsent(field, f -> new java.util.TreeSet<>()).add(v));
            }
        }
        node.forEach(child -> collect(child, values, depth + 1));
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isContainerNode()) {
            return Optional.empty();
        }
        String s = v.asText().strip();
        return s.isEmpty() ? Optional.empty() : Optional.of(s);
    }
}
