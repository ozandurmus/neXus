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
    public record CollectionFailure(String layerRef, String reason, String layerName, int offset, String natUidProbe) {
        /**
         * The by-UID read outcome is an opaque enum, and only a name-not-unique NAT failure may carry one. A stored
         * legacy UID_ACCEPTED (the retired one-off probe) is no longer a failure outcome and reads back as absent.
         */
        public CollectionFailure {
            if (UID_ACCEPTED_LEGACY.equals(natUidProbe)) natUidProbe = null;
            if (natUidProbe != null && (!NAT_UID_PROBE_OUTCOMES.contains(natUidProbe) || !VENDOR_NAME_NOT_UNIQUE.equals(reason)))
                throw new IllegalArgumentException("POLICY_NAT_UID_PROBE_OUTCOME_INVALID");
        }
        public CollectionFailure(String layerRef, String reason, String layerName, int offset) {
            this(layerRef, reason, layerName, offset, null);
        }
        public CollectionFailure(String layerRef, String reason) { this(layerRef, reason, "", 0, null); }
    }
    /** The vendor answered with a name-not-unique API error; the part is explicitly UNKNOWN, not missing. */
    public static final String VENDOR_NAME_NOT_UNIQUE = "VENDOR_NAME_NOT_UNIQUE";
    /**
     * Outcomes of the Check Point NAT rulebase read by package UID that follow a name-not-unique answer and did not
     * succeed (a successful read is no failure at all). UID_ACCEPTED belonged to the retired one-off probe.
     */
    public static final String UID_REJECTED = "UID_REJECTED", NOT_EVALUABLE = "NOT_EVALUABLE", UID_ACCEPTED_LEGACY = "UID_ACCEPTED";
    public static final java.util.Set<String> NAT_UID_PROBE_OUTCOMES = java.util.Set.of(UID_REJECTED, NOT_EVALUABLE);
    /** Fixed list of reasons that mark a readable-but-unanswerable part as UNKNOWN instead of incomplete. */
    public static final java.util.Set<String> UNKNOWN_PART_REASONS = java.util.Set.of(VENDOR_NAME_NOT_UNIQUE);
    /** Only the collector's fixed NAT part may carry an UNKNOWN reason; every other failure stays a gap. */
    public static boolean unknownPart(CollectionFailure failure) {
        return failure != null && UNKNOWN_PART_REASONS.contains(failure.reason()) && "NAT".equals(failure.layerName());
    }
    /** Failures that leave every rule of the snapshot collected: hit counters or the object inventory only. */
    public static final java.util.Set<String> ADVISORY_REASONS = java.util.Set.of("POLICY_HITS_UNAVAILABLE", "POLICY_DB_INVENTORY_WRITE_FAILED");
    /**
     * Every rule of the policy is in the snapshot: each failure is advisory or an explicit UNKNOWN part. An in-progress
     * collection checkpoint (COLLECTION_PENDING) and a run that lost a layer (timeout, page error) are not rule-complete.
     * PolicySnapshotRepository applies the same test in SQL; keep the two in step.
     */
    public boolean ruleComplete() {
        return failures.stream().allMatch(f -> ADVISORY_REASONS.contains(f.reason()) || unknownPart(f));
    }
    /** Rules in every section, security and NAT. */
    public int ruleCount() {
        int n = 0;
        for (Section section : sections) n += section.rules().size();
        return n;
    }
    /** Section names the Panorama mapper gives to rulebases that are not security rules. */
    public static final String PAN_PRE_NAT = "Pre NAT rules", PAN_POST_NAT = "Post NAT rules",
            PAN_PRE_DEFAULT = "Pre default rules", PAN_POST_DEFAULT = "Post default rules";
    /**
     * Section names the local (non-Panorama) firewall mapper gives to the NAT and overridden default rules of one
     * virtual system's stored configuration (path analysis A2, gap G3). Like the Panorama names they are never security.
     */
    public static final String PAN_LOCAL_RULES = "Local rules", PAN_LOCAL_NAT = "Local NAT rules", PAN_LOCAL_DEFAULT = "Local default rules";
    private static final java.util.Set<String> PAN_NON_SECURITY_SECTIONS =
            java.util.Set.of(PAN_PRE_NAT, PAN_POST_NAT, PAN_PRE_DEFAULT, PAN_POST_DEFAULT, PAN_LOCAL_NAT, PAN_LOCAL_DEFAULT);
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
    /**
     * Joins adjacent sections that carry the same non-null key, name, source and parent into the first one (its id, the
     * rules concatenated in order). Check Point repeats an enclosing section on every API page it spans; non-adjacent
     * sections, and sections without a key, are never joined.
     */
    public static List<Section> mergeAdjacent(List<Section> sections, java.util.function.Function<Section, String> key) {
        List<Section> merged = new java.util.ArrayList<>();
        String last = null;
        for (Section section : sections) {
            String current = key.apply(section);
            Section previous = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (current != null && current.equals(last) && previous.name().equals(section.name())
                    && previous.source().equals(section.source()) && java.util.Objects.equals(previous.parentRuleId(), section.parentRuleId())) {
                List<Rule> rules = new java.util.ArrayList<>(previous.rules());
                rules.addAll(section.rules());
                merged.set(merged.size() - 1, new Section(previous.id(), previous.name(), previous.source(), previous.parentRuleId(), rules));
            } else merged.add(section);
            last = current;
        }
        return merged;
    }
    /** Stored Check Point snapshots predate the page merge and keep no logical section ref: join adjacent same-named CP fragments. */
    public PolicySnapshot withPageSplitsMerged() {
        if (metadata == null || !"CP".equals(metadata.vendor())) return this;
        // The access and NAT rulebases are independent and ordered only within themselves; the collector can interleave
        // their pages (access page, NAT, next access page). Keep each rulebase's own order, list NAT after access, then
        // join the page splits (2026-11-03: 5,761 access rules were cut off from their own section's first page).
        List<Section> ordered = new java.util.ArrayList<>(sections.stream().filter(s -> !"CP NAT rulebase".equals(s.source())).toList());
        ordered.addAll(sections.stream().filter(s -> "CP NAT rulebase".equals(s.source())).toList());
        List<Section> merged = mergeAdjacent(ordered, s -> s.source());
        return merged.size() == sections.size() && ordered.equals(sections) ? this : new PolicySnapshot(metadata, merged, objects, failures);
    }
    /**
     * Stored snapshots carry hit levels only where Check Point supplied them (zero|low|medium|high; the thresholds are the
     * vendor's and are not in this repository); Palo Alto device counters carry none. A missing level becomes an explicit
     * UNKNOWN, except that a complete device total of exactly zero with no recorded last hit is "zero".
     * Low, medium and high are never derived.
     */
    public PolicySnapshot withHitLevels() {
        boolean[] changed = {false};
        List<Section> out = sections.stream().map(s -> new Section(s.id(), s.name(), s.source(), s.parentRuleId(), s.rules().stream().map(r -> {
            HitCounts counts = r.hitCounts() == null ? null : r.hitCounts().withLevel();
            if (counts == r.hitCounts()) return r;
            changed[0] = true;
            return r.withHitCounts(counts);
        }).toList())).toList();
        return changed[0] ? new PolicySnapshot(metadata, out, objects, failures) : this;
    }
    /**
     * {@code inventoryProvenance} is null when the snapshot was mapped without an object inventory (Palo Alto, snapshots
     * stored before 2026-11-04, offline fixtures); otherwise it says how its Check Point objects were completed.
     */
    public record Metadata(String id, String sourceId, String sourceName, String vendor,
            String containerId, String containerName, String name, String collectedAt,
            String artefactRef, List<Target> targets, InventoryProvenance inventoryProvenance) {
        public Metadata(String id, String sourceId, String sourceName, String vendor, String containerId, String containerName,
                String name, String collectedAt, String artefactRef, List<Target> targets) {
            this(id, sourceId, sourceName, vendor, containerId, containerName, name, collectedAt, artefactRef, targets, null);
        }
        public Metadata { targets = List.copyOf(targets); }
        public Metadata withInventoryProvenance(InventoryProvenance provenance) {
            return new Metadata(id, sourceId, sourceName, vendor, containerId, containerName, name, collectedAt, artefactRef, targets, provenance);
        }
    }
    /**
     * Check Point objects that the rulebase objects-dictionary did not carry (group members, at any depth) and that were
     * resolved by exact uid from the stored object inventory of the same management domain, or of the MDS Global domain:
     * how many snapshot objects came from there, and the oldest inventory collection time used (null when none). The
     * inventory is a separate, earlier management read than the rulebase, so its age is part of the evidence.
     */
    public record InventoryProvenance(int resolvedObjects, String oldestCollectedAt) {}
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
        /**
         * Extras keep a fixed (sorted) key order: {@code Map.copyOf} iterates in an order that changes with every JVM start,
         * which made a rule's JSON key order and "the first failing field" of the shadow analysis restart-dependent
         * (2026-11-06 M9 measurement). Null keys and values are still refused, as by {@code Map.copyOf}.
         */
        public Rule { extras = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(Map.copyOf(extras))); }
        public Rule withHitCounts(HitCounts counts) {
            return new Rule(id, uuid, number, name, enabled, source, destination, service, application, action, log, comment, extras, counts);
        }
    }
    /** Null counters mean insufficient evidence, never zero. Breakdown uses opaque enrolled references. */
    public record HitCounts(Long hits, String firstHit, String lastHit, String source, String collectedAt,
            String level, List<FirewallHits> firewalls) {
        public HitCounts { firewalls = firewalls == null ? List.of() : List.copyOf(firewalls); }
        public static final String LEVEL_UNKNOWN = "UNKNOWN";
        /** Fills a missing level only; a vendor-supplied level is returned as is. */
        public HitCounts withLevel() {
            if (level != null) return this;
            String derived = "device".equals(source) && hits != null && hits == 0 && lastHit == null ? "zero" : LEVEL_UNKNOWN;
            return new HitCounts(hits, firstHit, lastHit, source, collectedAt, derived, firewalls);
        }
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
