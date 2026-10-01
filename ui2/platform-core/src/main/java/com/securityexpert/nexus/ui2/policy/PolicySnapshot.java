package com.securityexpert.nexus.ui2.policy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Parsed management intent, never evidence that a target installed this policy. */
public record PolicySnapshot(Metadata metadata, List<Section> sections, Map<String, PolicyObject> objects) {
    public PolicySnapshot {
        sections = List.copyOf(sections);
        objects = Map.copyOf(objects);
    }
    public record Metadata(String id, String sourceId, String sourceName, String vendor,
            String containerId, String containerName, String name, String collectedAt,
            String artefactRef, List<Target> targets) {
        public Metadata { targets = List.copyOf(targets); }
    }
    /** Enrolled device IDs when matched; otherwise scoped opaque management-target references, never serial-derived joins. */
    public record Target(String deviceId, String name, String context, String syncStatus) {}
    public record Section(String id, String name, String source, String parentRuleId, List<Rule> rules) {
        public Section { rules = List.copyOf(rules); }
    }
    public record Rule(String id, String uuid, int number, String name, Boolean enabled,
            Cell source, Cell destination, Cell service, Cell application, String action,
            String log, String comment, Map<String, List<String>> extras) {
        public Rule { extras = Map.copyOf(extras); }
    }
    public record Cell(List<String> refs, boolean negated) {
        public Cell { refs = List.copyOf(refs); }
    }
    public record PolicyObject(String id, String name, String type, List<String> members,
            List<String> values, String status) {
        public PolicyObject { members = List.copyOf(members); values = List.copyOf(values); }
    }
    /** Length-prefix each identity component; no case/numeric normalization or ambiguous concatenation. */
    public static String ref(String... parts) {
        StringBuilder key = new StringBuilder();
        for (String part : parts) key.append(part.length()).append(':').append(part);
        return UUID.nameUUIDFromBytes(key.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }
}
