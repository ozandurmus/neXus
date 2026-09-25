package com.securityexpert.nexus.ui2.worker.backup.https;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The Cyber Controller's {@code alldevices} answer walked as a tree ({@code children}), for inventory (PO 2026-09-25):
 * the controller's own name and version, the managed devices' names, and the node/tree type counts for measurement.
 */
final class CyberControllerTree {

    private final List<JsonNode> nodes = new ArrayList<>();
    private final Map<String, Integer> typeCounts = new TreeMap<>();
    private final Map<String, Integer> treeTypeCounts = new TreeMap<>();
    private final String dialledHost;
    private Optional<JsonNode> own = Optional.empty();
    private String ownNameRule = "none";

    private CyberControllerTree(String dialledHost) {
        this.dialledHost = dialledHost == null ? "" : dialledHost.trim();
    }

    static CyberControllerTree walk(JsonNode root, String dialledHost) {
        CyberControllerTree t = new CyberControllerTree(dialledHost);
        t.visit(root, 0);
        t.chooseOwn();
        return t;
    }

    private void visit(JsonNode n, int depth) {
        if (n == null || depth > 12) {
            return;
        }
        if (n.isArray()) {
            for (JsonNode c : n) {
                visit(c, depth + 1);
            }
            return;
        }
        if (!n.isObject()) {
            return;
        }
        boolean deviceLike = n.has("name") || n.has("managementIp") || n.has("type") || n.has("treeType");
        if (deviceLike) {
            nodes.add(n);
            typeCounts.merge(text(n, "type").orElse("(none)"), 1, Integer::sum);
            treeTypeCounts.merge(text(n, "treeType").orElse("(none)"), 1, Integer::sum);
        }
        n.fields().forEachRemaining(e -> {
            if (e.getValue().isContainerNode()) {
                visit(e.getValue(), depth + 1);
            }
        });
    }

    private void chooseOwn() {
        if (!dialledHost.isEmpty()) {
            own = nodes.stream().filter(n -> text(n, "managementIp").map(ip -> ip.trim().equalsIgnoreCase(dialledHost)).orElse(false)).findFirst();
            if (own.isPresent()) {
                ownNameRule = "managementIp equals the dialled address";
                return;
            }
        }
        own = nodes.stream().filter(n -> {
            String t = (text(n, "type").orElse("") + " " + text(n, "treeType").orElse("")).toLowerCase(Locale.ROOT);
            return t.contains("controller") || t.contains("vision") || t.contains("cyber");
        }).findFirst();
        if (own.isPresent()) {
            ownNameRule = "type names a controller";
            return;
        }
        List<JsonNode> roots = nodes.stream().filter(n -> !n.hasNonNull("parentOrmId") || text(n, "parentOrmId").orElse("").isBlank()
                || "null".equals(text(n, "parentOrmId").orElse(""))).toList();
        if (roots.size() == 1 && text(roots.get(0), "managementIp").isEmpty()) {
            own = Optional.of(roots.get(0));
            ownNameRule = "the single root node";
        }
    }

    Optional<String> ownName() {
        return own.flatMap(n -> text(n, "name")).filter(v -> !v.isBlank());
    }

    Optional<String> ownVersion() {
        return own.flatMap(n -> text(n, "deviceVersion")).filter(v -> !v.isBlank());
    }

    /** Every node with a management address other than the controller's own, by name, sorted; DefensePros here. */
    List<String> managedNames() {
        List<String> out = new ArrayList<>();
        for (JsonNode n : nodes) {
            if (own.isPresent() && own.get() == n) {
                continue;
            }
            if (text(n, "managementIp").filter(v -> !v.isBlank()).isEmpty()) {
                continue;
            }
            if (n.path("deleted").asBoolean(false)) {
                continue;
            }
            text(n, "name").filter(v -> !v.isBlank()).ifPresent(v -> out.add(v.trim()));
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(out);
    }

    /** The managed device whose management address is {@code address}, if the controller lists it. */
    Optional<JsonNode> deviceAt(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        return nodes.stream().filter(n -> text(n, "managementIp").map(ip -> ip.trim().equalsIgnoreCase(address.trim())).orElse(false))
                .filter(n -> !n.path("deleted").asBoolean(false)).findFirst();
    }

    static Optional<String> field(JsonNode n, String name) {
        return text(n, name).filter(v -> !v.isBlank());
    }

    int nodeCount() {
        return nodes.size();
    }

    Map<String, Integer> typeCounts() {
        return typeCounts;
    }

    Map<String, Integer> treeTypeCounts() {
        return treeTypeCounts;
    }

    String ownNameRule() {
        return ownNameRule;
    }

    private static Optional<String> text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) && n.get(field).isValueNode() ? Optional.of(n.get(field).asText()) : Optional.empty();
    }
}
