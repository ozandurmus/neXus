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
    /** The vendor answered with a name-not-unique API error; the part is explicitly UNKNOWN, not missing. */
    public static final String VENDOR_NAME_NOT_UNIQUE = "VENDOR_NAME_NOT_UNIQUE";
    /** Fixed list of reasons that mark a readable-but-unanswerable part as UNKNOWN instead of incomplete. */
    public static final java.util.Set<String> UNKNOWN_PART_REASONS = java.util.Set.of(VENDOR_NAME_NOT_UNIQUE);
    /** Only the collector's fixed NAT part may carry an UNKNOWN reason; every other failure stays a gap. */
    public static boolean unknownPart(CollectionFailure failure) {
        return failure != null && UNKNOWN_PART_REASONS.contains(failure.reason()) && "NAT".equals(failure.layerName());
    }
    /** Section names the Panorama mapper gives to rulebases that are not security rules. */
    public static final String PAN_PRE_NAT = "Pre NAT rules", PAN_POST_NAT = "Post NAT rules",
            PAN_PRE_DEFAULT = "Pre default rules", PAN_POST_DEFAULT = "Post default rules";
    private static final java.util.Set<String> PAN_NON_SECURITY_SECTIONS =
            java.util.Set.of(PAN_PRE_NAT, PAN_POST_NAT, PAN_PRE_DEFAULT, PAN_POST_DEFAULT);
    /**
     * True for a Palo Alto NAT or default-rules section. These are stored beside the security sections but are never
     * part of security evaluation (lookup, shadowing, hit joins). Only the vendor-fixed names of PAN sections count;
     * a Check Point section name is user-controlled and must never be classified by name.
     */
    public static boolean panNonSecurity(Metadata metadata, Section section) {
        return metadata != null && "PAN".equals(metadata.vendor()) && PAN_NON_SECURITY_SECTIONS.contains(section.name());
    }
    /** True for a Check Point NAT rulebase section or a Palo Alto NAT/default section: not a security rulebase. */
    public static boolean outsideSecurityEvaluation(Metadata metadata, Section section) {
        return "CP NAT rulebase".equals(section.source()) || panNonSecurity(metadata, section);
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
