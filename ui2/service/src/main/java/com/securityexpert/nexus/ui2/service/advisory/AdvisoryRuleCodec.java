package com.securityexpert.nexus.ui2.service.advisory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The stored JSON form of a train's rules (contract section 5, column {@code rule}). The shape is written by hand and versioned:
 * {@code {"v":1,"rules":[{"k":"whole","status":"AFFECTED"}, {"k":"range","status":...,"from":...,"to":...,"toInclusive":...,
 * "changes":[{"at":...,"status":...}]}, {"k":"exact","status":...,"versions":[...]}]}}. No polymorphic typing is used, so the
 * stored text can never select a class. Reading anything this codec did not write yields an empty list and never throws.
 */
final class AdvisoryRuleCodec {

    private static final int FORMAT_VERSION = 1;
    private static final int MAX_RULES = 1000;
    private static final int MAX_ITEMS = 100_000;
    private static final int MAX_TEXT = 64;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdvisoryRuleCodec() {
    }

    static String toJson(List<AdvisoryModel.VersionRule> rules) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("v", FORMAT_VERSION);
        ArrayNode arr = root.putArray("rules");
        for (AdvisoryModel.VersionRule rule : rules) {
            ObjectNode n = arr.addObject();
            switch (rule) {
                case AdvisoryModel.WholeTrain w -> {
                    n.put("k", "whole");
                    n.put("status", w.status().name());
                }
                case AdvisoryModel.Range r -> {
                    n.put("k", "range");
                    n.put("status", r.status().name());
                    n.put("from", r.from());
                    n.put("to", r.to());
                    n.put("toInclusive", r.toInclusive());
                    ArrayNode ch = n.putArray("changes");
                    for (AdvisoryModel.ChangePoint c : r.changes()) {
                        ObjectNode cn = ch.addObject();
                        cn.put("at", c.at());
                        cn.put("status", c.status().name());
                    }
                }
                case AdvisoryModel.ExactVersions e -> {
                    n.put("k", "exact");
                    n.put("status", e.status().name());
                    ArrayNode vs = n.putArray("versions");
                    for (String v : new TreeSet<>(e.versions())) {
                        vs.add(v);
                    }
                }
            }
        }
        return root.toString();
    }

    static List<AdvisoryModel.VersionRule> fromJson(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject() || !root.path("v").isInt() || root.path("v").asInt() != FORMAT_VERSION) {
                return List.of();
            }
            JsonNode rules = root.get("rules");
            if (rules == null || !rules.isArray() || rules.size() > MAX_RULES) {
                return List.of();
            }
            List<AdvisoryModel.VersionRule> out = new ArrayList<>();
            for (JsonNode n : rules) {
                AdvisoryModel.VersionRule rule = readRule(n);
                if (rule == null) {
                    return List.of();
                }
                out.add(rule);
            }
            return List.copyOf(out);
        } catch (Exception | StackOverflowError e) {
            return List.of();
        }
    }

    private static AdvisoryModel.VersionRule readRule(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        AdvisoryModel.Status status = status(n.get("status"));
        String kind = n.path("k").isTextual() ? n.path("k").asText() : "";
        if (status == null) {
            return null;
        }
        switch (kind) {
            case "whole":
                return new AdvisoryModel.WholeTrain(status);
            case "range": {
                String from = optText(n, "from");
                String to = optText(n, "to");
                if (from == null && !n.path("from").isNull() || to == null && !n.path("to").isNull()) {
                    return null;
                }
                if (!n.path("toInclusive").isBoolean() || !n.path("changes").isArray() || n.get("changes").size() > MAX_ITEMS) {
                    return null;
                }
                List<AdvisoryModel.ChangePoint> changes = new ArrayList<>();
                for (JsonNode c : n.get("changes")) {
                    AdvisoryModel.Status cs = c == null ? null : status(c.get("status"));
                    String at = c == null ? null : text(c.get("at"));
                    if (cs == null || at == null) {
                        return null;
                    }
                    changes.add(new AdvisoryModel.ChangePoint(at, cs));
                }
                return new AdvisoryModel.Range(status, from, to, n.get("toInclusive").asBoolean(), changes);
            }
            case "exact": {
                JsonNode vs = n.get("versions");
                if (vs == null || !vs.isArray() || vs.size() > MAX_ITEMS) {
                    return null;
                }
                Set<String> versions = new TreeSet<>();
                for (JsonNode v : vs) {
                    String t = text(v);
                    if (t == null) {
                        return null;
                    }
                    versions.add(t);
                }
                return new AdvisoryModel.ExactVersions(status, versions);
            }
            default:
                return null;
        }
    }

    private static AdvisoryModel.Status status(JsonNode n) {
        if (n == null || !n.isTextual()) {
            return null;
        }
        try {
            return AdvisoryModel.Status.valueOf(n.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonNode n) {
        return n != null && n.isTextual() && !n.asText().isEmpty() && n.asText().length() <= MAX_TEXT ? n.asText() : null;
    }

    private static String optText(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        return n == null || n.isNull() ? null : text(n);
    }
}
