package com.securityexpert.nexus.ui2.policy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Parsed management intent, never evidence that a target installed this policy. */
public record PolicySnapshot(Metadata metadata, List<Section> sections, Map<String, PolicyObject> objects, List<CollectionFailure> failures) {
    public PolicySnapshot(Metadata metadata, List<Section> sections, Map<String, PolicyObject> objects) {
        this(metadata, sections, objects, List.of());
    }
    public record CollectionFailure(String layerRef, String reason, String layerName, int offset) {
        public CollectionFailure(String layerRef, String reason) { this(layerRef, reason, "", 0); }
    }
    public PolicySnapshot {
        failures = failures == null ? List.of() : List.copyOf(failures);
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
            String log, String comment, Map<String, List<String>> extras, HitCounts hitCounts) {
        public Rule(String id, String uuid, int number, String name, Boolean enabled,
                Cell source, Cell destination, Cell service, Cell application, String action,
                String log, String comment, Map<String, List<String>> extras) {
            this(id, uuid, number, name, enabled, source, destination, service, application, action, log, comment, extras, null);
        }
        public Rule { extras = Map.copyOf(extras); }
        public Rule withHitCounts(HitCounts counts) {
            return new Rule(id, uuid, number, name, enabled, source, destination, service, application, action, log, comment, extras, counts);
        }
    }
    /** Null counters mean insufficient evidence, never zero. Breakdown uses opaque enrolled references. */
    public record HitCounts(Long hits, String firstHit, String lastHit, String source, String collectedAt,
            String level, List<FirewallHits> firewalls) {
        public HitCounts { firewalls = firewalls == null ? List.of() : List.copyOf(firewalls); }
    }
    public record FirewallHits(String deviceId, String context, Long hits, String firstHit, String lastHit,
            String createdAt, String modifiedAt, String collectedAt) {}
    public record Cell(List<String> refs, boolean negated) {
        public Cell { refs = List.copyOf(refs); }
    }
    public record PolicyObject(String id, String name, String type, List<String> members,
            List<String> values, String status, PolicySchedule schedule) {
        public PolicyObject(String id, String name, String type, List<String> members, List<String> values, String status) {
            this(id, name, type, members, values, status, null);
        }
        public PolicyObject { members = List.copyOf(members); values = List.copyOf(values); }
    }
    /** Length-prefix each identity component; no case/numeric normalization or ambiguous concatenation. */
    public static String ref(String... parts) {
        StringBuilder key = new StringBuilder();
        for (String part : parts) key.append(part.length()).append(':').append(part);
        return UUID.nameUUIDFromBytes(key.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }
}
