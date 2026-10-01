package com.securityexpert.nexus.ui2.worker.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.util.*;

/** Offline mapper. Callers supply ordered access-layer pages and dictionaries; no transport or command creation. */
public final class CheckPointPolicyMapper {
    public PolicySnapshot map(Metadata metadata, List<JsonNode> pages) {
        Map<String, JsonNode> dictionary = new LinkedHashMap<>();
        for (JsonNode page : pages) for (JsonNode object : page.path("objects-dictionary")) {
            String uid = object.path("uid").asText();
            if (!uid.isEmpty()) dictionary.put(uid, object);
        }
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        for (var entry : dictionary.entrySet()) object(metadata, entry.getKey(), dictionary, objects);
        Map<String, List<JsonNode>> layers = new LinkedHashMap<>();
        Set<String> inline = new HashSet<>();
        for (JsonNode page : pages) {
            String layer = page.path("uid").asText();
            if (layer.isEmpty() || !page.path("rulebase").isArray()) throw new IllegalArgumentException("Invalid policy layer");
            layers.computeIfAbsent(layer, key -> new ArrayList<>()).add(page);
            inlineLayers(page.path("rulebase"), inline, 0);
        }
        for (List<JsonNode> layerPages : layers.values()) {
            int next = 1;
            for (JsonNode page : layerPages) {
                if (page.has("from") && page.path("total").asInt() > 0) {
                    if (page.path("from").asInt() != next) throw new IllegalArgumentException("Incomplete policy pages");
                    next = page.path("to").asInt() + 1;
                }
            }
            JsonNode last = layerPages.get(layerPages.size() - 1);
            if (last.has("total") && last.path("total").asInt() != next - 1)
                throw new IllegalArgumentException("Incomplete policy pages");
        }
        List<Section> sections = new ArrayList<>();
        for (String layer : layers.keySet()) if (!inline.contains(layer))
            layer(metadata, layer, null, layers, dictionary, objects, sections, new HashSet<>());
        if (!layers.isEmpty() && sections.isEmpty()) throw new IllegalArgumentException("Cyclic inline layers");
        return new PolicySnapshot(metadata, sections, objects);
    }
    private static void inlineLayers(JsonNode nodes, Set<String> inline, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Policy nesting limit exceeded");
        for (JsonNode node : nodes) {
            if (node.has("inline-layer")) inline.add(node.path("inline-layer").asText());
            if (node.has("rulebase")) inlineLayers(node.path("rulebase"), inline, depth + 1);
        }
    }
    private void layer(Metadata meta, String uid, String parent, Map<String, List<JsonNode>> layers,
            Map<String, JsonNode> dict, Map<String, PolicyObject> objects, List<Section> sections, Set<String> path) {
        if (!path.add(uid) || path.size() > 32) throw new IllegalArgumentException("Cyclic or excessive inline layers");
        if (!layers.containsKey(uid)) {
            sections.add(new Section(ref(meta.id(), uid, parent == null ? "" : parent), "Inline layer unavailable", "CP access layer", parent, List.of()));
        } else for (JsonNode page : layers.get(uid)) {
            walk(meta, page.path("rulebase"), ref(meta.id(), uid, parent == null ? "" : parent),
                    page.path("name").asText("Access layer"), parent, layers, dict, objects, sections, path, 0);
        }
        path.remove(uid);
    }
    private void walk(Metadata meta, JsonNode nodes, String section, String name, String parent,
            Map<String, List<JsonNode>> layers, Map<String, JsonNode> dict, Map<String, PolicyObject> objects,
            List<Section> sections, Set<String> path, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Policy nesting limit exceeded");
        List<Rule> pending = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (node.path("type").asText().equals("access-section")) {
                flush(sections, section, name, parent, pending);
                walk(meta, node.path("rulebase"), ref(section, node.path("uid").asText()), node.path("name").asText("Section"),
                        parent, layers, dict, objects, sections, path, depth + 1);
                continue;
            }
            if (!node.path("type").asText().equals("access-rule")) throw new IllegalArgumentException("Unsupported access rule entry");
            String uuid = node.path("uid").asText();
            if (uuid.isEmpty()) throw new IllegalArgumentException("Missing rule identity");
            String ruleId = ref(section, uuid);
            Map<String, List<String>> extras = new LinkedHashMap<>();
            for (String field : List.of("install-on", "vpn", "time", "content", "inline-layer")) {
                List<String> values = texts(node.path(field));
                if (!values.isEmpty()) extras.put(field, values.stream().map(value -> object(meta, value, dict, objects)).toList());
            }
            for (String field : List.of("accounting", "alert", "per-connection", "per-session"))
                if (node.path("track").has(field)) extras.put("track-" + field, List.of(node.path("track").path(field).asText()));
            pending.add(new Rule(ruleId, uuid, node.path("rule-number").asInt(0), node.path("name").asText(""),
                    node.path("enabled").isBoolean() ? node.path("enabled").asBoolean() : null,
                    cell(meta, node, "source", dict, objects), cell(meta, node, "destination", dict, objects),
                    cell(meta, node, "service", dict, objects), new Cell(List.of(), false),
                    resolve(node.path("action"), dict), resolve(node.path("track").path("type"), dict), node.path("comments").asText(""), extras));
            if (node.has("inline-layer")) {
                flush(sections, section, name, parent, pending);
                layer(meta, node.path("inline-layer").asText(), ruleId, layers, dict, objects, sections, path);
            }
        }
        flush(sections, section, name, parent, pending);
        if (nodes.isEmpty()) sections.add(new Section(section, name, "CP access layer", parent, List.of()));
    }
    private static void flush(List<Section> sections, String id, String name, String parent, List<Rule> pending) {
        if (pending.isEmpty()) return;
        sections.add(new Section(ref(id, pending.get(0).uuid()), name, "CP access layer", parent, pending));
        pending.clear();
    }
    private Cell cell(Metadata meta, JsonNode rule, String field, Map<String, JsonNode> dict, Map<String, PolicyObject> objects) {
        return new Cell(texts(rule.path(field)).stream().map(value -> object(meta, value, dict, objects)).toList(),
                rule.path(field + "-negate").asBoolean(false));
    }
    private String object(Metadata meta, String uid, Map<String, JsonNode> dict, Map<String, PolicyObject> objects) {
        return object(meta, uid, dict, objects, 0);
    }
    private String object(Metadata meta, String uid, Map<String, JsonNode> dict, Map<String, PolicyObject> objects, int depth) {
        String id = ref(meta.id(), "object", uid);
        if (objects.containsKey(id)) return id;
        if (depth > 32) throw new IllegalArgumentException("Policy object nesting limit exceeded");
        JsonNode node = dict.get(uid);
        if (node == null) {
            objects.put(id, new PolicyObject(id, uid, "unresolved", List.of(), List.of(), "UNRESOLVED"));
            return id;
        }
        String nativeType = node.path("type").asText();
        String type = switch (nativeType) {
            case "CpmiAnyObject" -> "any";
            case "host", "network", "address-range" -> "address";
            case "group", "group-with-exclusion" -> "group";
            case "service-tcp", "service-udp", "service-icmp" -> "service";
            case "service-group" -> "service-group";
            default -> "unresolved";
        };
        String name = type.equals("any") ? "ANY" : node.path("name").asText(uid);
        objects.put(id, new PolicyObject(id, name, type, List.of(), List.of(), "UNRESOLVED")); // cycle guard
        List<String> members = texts(node.path("members")).stream().map(member -> object(meta, member, dict, objects, depth + 1)).toList();
        List<String> values = new ArrayList<>();
        if (nativeType.startsWith("service-") && !nativeType.equals("service-group"))
            values.add("protocol: " + nativeType.substring("service-".length()));
        for (String field : List.of("ipv4-address", "ipv6-address", "subnet4", "mask-length4", "subnet6", "mask-length6", "port", "icmp-type", "icmp-code", "ip-address-first", "ip-address-last"))
            if (node.has(field)) values.add(field + ": " + node.path(field).asText());
        String status = type.equals("unresolved") || nativeType.equals("group-with-exclusion") ? "UNSUPPORTED" : "RESOLVED";
        objects.put(id, new PolicyObject(id, name, type, members, values, status));
        return id;
    }
    private static String resolve(JsonNode node, Map<String, JsonNode> dict) {
        if (node.isObject()) return node.path("name").asText("UNKNOWN");
        return dict.containsKey(node.asText()) ? dict.get(node.asText()).path("name").asText("UNKNOWN") : "UNKNOWN";
    }
    private static List<String> texts(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        List<String> result = new ArrayList<>();
        if (node.isArray()) node.forEach(value -> result.add(value.isObject() ? value.path("uid").asText() : value.asText()));
        else result.add(node.isObject() ? node.path("uid").asText() : node.asText());
        return result;
    }
}
