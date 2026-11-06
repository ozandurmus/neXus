package com.securityexpert.nexus.ui2.worker.policy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Field-presence counters for the {@code show-gateways-and-servers details-level full} listing (PO decision 2026-11-06,
 * VSX VIP prefixes from management data, stage 1: measure before binding). Key NAMES and counts only: no value, no
 * name, no address is read into the result. A key that is not a closed schema token reads {@code ?}; an object type
 * that is not a closed type token reads {@code OTHER}. Depth is two levels below an object, arrays counted per element.
 */
final class CpGatewayFieldPresence {
    private CpGatewayFieldPresence() {}
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Pattern TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,39}");
    private static final int MAX_DEPTH = 3;

    /** One line per object type, sorted: {@code type=<T> objects=<n> keys=<path>:<count>,...}. */
    static List<String> summarize(List<JsonNode> nodes) {
        Map<String, int[]> objects = new TreeMap<>();
        Map<String, Map<String, int[]>> keys = new TreeMap<>();
        for (JsonNode node : nodes) {
            if (node == null || !node.isObject()) continue;
            String raw = node.path("type").asText("");
            String type = TYPE.matcher(raw).matches() ? raw : "OTHER";
            objects.computeIfAbsent(type, t -> new int[1])[0]++;
            walk(node, "", 1, keys.computeIfAbsent(type, t -> new TreeMap<>()));
        }
        return objects.entrySet().stream().map(e -> "type=" + e.getKey() + " objects=" + e.getValue()[0] + " keys="
                + String.join(",", keys.get(e.getKey()).entrySet().stream().map(k -> k.getKey() + ":" + k.getValue()[0]).toList()))
            .toList();
    }

    private static void walk(JsonNode object, String prefix, int depth, Map<String, int[]> out) {
        object.fieldNames().forEachRemaining(name -> {
            String key = prefix + (KEY.matcher(name).matches() ? name : "?");
            JsonNode value = object.get(name);
            out.computeIfAbsent(key, k -> new int[1])[0]++;
            if (depth >= MAX_DEPTH || key.endsWith("?")) return;
            if (value.isObject()) walk(value, key + ".", depth + 1, out);
            else if (value.isArray()) for (JsonNode element : value) if (element.isObject()) walk(element, key + "[].", depth + 1, out);
        });
    }
}
