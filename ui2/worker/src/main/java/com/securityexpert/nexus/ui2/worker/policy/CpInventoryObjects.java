package com.securityexpert.nexus.ui2.worker.policy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.CpObjectInventory;
import com.securityexpert.nexus.ui2.policy.PolicySchedule;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * The stored Check Point object inventory of one collection run's management source, read only for references the
 * rulebase objects-dictionary did not carry (no device command: the inventory reads are already collected).
 * <p>A domain's inventory is read lazily, on its first dictionary miss, once per run, and kept as a compact projection
 * (uid, name, type, members, the mapper's value fields, schedule) instead of the stored documents. Each object-type
 * document is used only when it is the published manifest's complete listing (status RESOLVED), names the same source and
 * domain, and its whole chunk set reads back; otherwise none of that type is used (fail closed). Projections are kept for at
 * most {@link #MAX_RETAINED_OBJECTS} objects across domains: beyond that the least recently used domain is dropped and
 * read again if it is needed again, so memory stays bounded at the cost of a second read. The MDS Global domain, once
 * read, is never dropped (every customer domain falls back to it), and neither is the domain just read; so the footprint
 * is at most the larger of the bound and Global plus one domain.
 * <p>A customer domain's miss is looked up in that domain first, then in the MDS Global domain when the run's domain
 * listing names one; uids are compared exactly, never normalized.
 */
final class CpInventoryObjects {
    /** Bounded projection footprint across all cached domains (roughly a few hundred bytes per object). */
    static final int MAX_RETAINED_OBJECTS = 400_000;
    /** The listing of objects the management reports as unused repeats objects of the other types; it is not a source. */
    private static final Set<String> NOT_A_SOURCE = Set.of("unused-objects");
    /** Items are read one at a time from a streaming parser; the document's own end is checked explicitly. */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** One retained object; members and values in their stored order. */
    private record Entry(String uid, String name, String type, List<String> members, List<String> values,
            PolicySchedule schedule, Instant collectedAt) {}
    private record Domain(Map<String, Entry> objects, Set<String> ambiguous) {
        int size() { return objects.size(); }
    }

    private final PolicyCollectionRepository repository;
    private final String sourceId;
    private final Supplier<Instant> clock;
    private final int maxRetained;
    private final LinkedHashMap<String, Domain> domains = new LinkedHashMap<>(8, 0.75f, true);
    private String globalDomain;
    private int retained;
    int domainReads, rejectedTypes;

    CpInventoryObjects(PolicyCollectionRepository repository, String sourceId) {
        this(repository, sourceId, Instant::now, MAX_RETAINED_OBJECTS);
    }
    CpInventoryObjects(PolicyCollectionRepository repository, String sourceId, Supplier<Instant> clock, int maxRetained) {
        this.repository = repository; this.sourceId = sourceId; this.clock = clock; this.maxRetained = maxRetained;
    }

    /** Whether resolution from the inventory is on; an operator can turn it off without a code change. */
    static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("ui2.policy.cp.resolve-from-inventory",
            System.getenv().getOrDefault("UI2_POLICY_CP_RESOLVE_FROM_INVENTORY", "true")));
    }

    /**
     * Record the MDS Global domain from the run's show-domains listing, recognized exactly as the collector already
     * recognizes it for its inventory reads ({@link CheckPointPolicyCollector#isGlobalDomain}).
     */
    synchronized void domains(com.fasterxml.jackson.databind.JsonNode listing) {
        for (var domain : listing.path("objects")) {
            String uid = domain.path("uid").asText(""), name = domain.path("name").asText("");
            if (!uid.isEmpty() && CheckPointPolicyCollector.isGlobalDomain(name)) {
                // Two domains that both look like Global cannot say which one is: no fallback at all.
                String ref = com.securityexpert.nexus.ui2.policy.PolicySnapshot.ref(sourceId, uid);
                globalDomain = globalDomain == null ? ref : globalDomain.equals(ref) ? ref : "";
            }
        }
    }

    /** The lookup for one policy's domain (container reference). */
    CheckPointPolicyMapper.InventoryLookup forDomain(String container) {
        return uid -> find(container, uid);
    }

    synchronized CheckPointPolicyMapper.InventoryObject find(String container, String uid) {
        if (uid == null || uid.isEmpty()) return null;
        var found = lookup(container, uid, container);
        // Reading Global for this fallback must not drop the customer domain it serves.
        if (found == null && globalDomain != null && !globalDomain.isEmpty() && !globalDomain.equals(container)) found = lookup(globalDomain, uid, container);
        return found;
    }

    private CheckPointPolicyMapper.InventoryObject lookup(String container, String uid, String serving) {
        Domain domain = domain(container, serving);
        if (domain.ambiguous().contains(uid)) return null;
        Entry entry = domain.objects().get(uid);
        if (entry == null || !entry.uid().equals(uid)) return null;
        ObjectNode node = JSON.createObjectNode();
        node.put("uid", entry.uid());
        if (!entry.name().isEmpty()) node.put("name", entry.name());
        node.put("type", entry.type());
        if (!entry.members().isEmpty()) entry.members().forEach(node.putArray("members")::add);
        for (String value : entry.values()) {
            int colon = value.indexOf(": ");
            node.put(value.substring(0, colon), value.substring(colon + 2));
        }
        return new CheckPointPolicyMapper.InventoryObject(entry.uid(), node, entry.schedule(), entry.collectedAt());
    }

    private Domain domain(String container, String serving) {
        Domain cached = domains.get(container);
        if (cached != null) return cached;
        Domain loaded = load(container);
        domains.put(container, loaded);
        retained += loaded.size();
        // Keep the newest domain even when it alone exceeds the bound, and never drop the Global domain: every customer
        // domain's miss falls back to Global, so evicting either of the pair would reload it on the very next miss
        // (customer <-> Global thrash). Drop the least recently used other domains; the pair may overshoot the bound.
        var oldest = domains.entrySet().iterator();
        while (retained > maxRetained && oldest.hasNext()) {
            var evicted = oldest.next();
            String key = evicted.getKey();
            if (key.equals(container) || key.equals(serving) || pinned(key)) continue;
            retained -= evicted.getValue().size();
            oldest.remove();
        }
        return loaded;
    }

    /** The run's Global domain is never evicted once read. */
    private boolean pinned(String container) {
        return globalDomain != null && !globalDomain.isEmpty() && globalDomain.equals(container);
    }

    private Domain load(String container) {
        domainReads++;
        Map<String, Entry> objects = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        int types = 0, rejected = 0;
        Instant notBefore = clock.get().minus(CheckPointPolicyMapper.INVENTORY_MAX_AGE);
        try {
            int[] counts = new int[2];
            repository.readInventories(sourceId, container, document -> {
                if (NOT_A_SOURCE.contains(document.objectType())) return;
                // An inventory already too old for any snapshot of this run is not decoded at all.
                if (document.collectedAt() == null || document.collectedAt().isBefore(notBefore)) { counts[1]++; return; }
                List<Entry> parsed = parse(document, container);
                if (parsed == null) { counts[1]++; return; }
                counts[0]++;
                for (Entry entry : parsed) {
                    Entry previous = objects.putIfAbsent(entry.uid(), entry);
                    if (previous != null && !previous.equals(entry)) ambiguous.add(entry.uid());
                }
            });
            types = counts[0]; rejected = counts[1];
        } catch (RuntimeException unavailable) {
            // The inventory could not be read at all: every miss in this domain stays UNRESOLVED.
            objects.clear(); ambiguous.clear(); types = 0; rejected = -1;
        }
        rejectedTypes += Math.max(rejected, 0);
        String note = "[CP_INVENTORY_OBJECTS] domainRead=" + domainReads + " usableTypes=" + types
            + " unusableTypes=" + (rejected < 0 ? "UNAVAILABLE" : Integer.toString(rejected)) + " objects=" + objects.size()
            + " ambiguous=" + ambiguous.size();
        com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note", note);
        System.getLogger(CpInventoryObjects.class.getName()).log(System.Logger.Level.INFO, note);
        return new Domain(objects, ambiguous);
    }

    /** The type's projected objects, or null when this document must not be used (incomplete, foreign, unreadable). */
    private List<Entry> parse(PolicyCollectionRepository.InventoryDocument document, String container) {
        List<Entry> entries = new ArrayList<>();
        String status = null, source = null, domain = null, type = null;
        try (JsonParser parser = JSON.getFactory().createParser(document.document())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                switch (field) {
                    case "status" -> status = value == JsonToken.VALUE_STRING ? parser.getText() : null;
                    case "sourceId" -> source = value == JsonToken.VALUE_STRING ? parser.getText() : null;
                    case "containerId" -> domain = value == JsonToken.VALUE_STRING ? parser.getText() : null;
                    case "type" -> type = value == JsonToken.VALUE_STRING ? parser.getText() : null;
                    case "objects" -> {
                        if (value != JsonToken.START_ARRAY) return null;
                        while (parser.nextToken() == JsonToken.START_OBJECT) {
                            Entry entry = entry(JSON.readValue(parser, CpObjectInventory.Item.class), document.collectedAt());
                            if (entry == null) return null;
                            entries.add(entry);
                        }
                        if (parser.currentToken() != JsonToken.END_ARRAY) return null;
                    }
                    default -> parser.skipChildren();
                }
            }
            if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null) return null;
        } catch (java.io.IOException | RuntimeException unreadable) {
            // A missing or corrupt chunk, or an unexpected shape: discard everything read from this type.
            return null;
        }
        if (!"RESOLVED".equals(status) || !sourceId.equals(source) || !container.equals(domain)
                || !document.objectType().equals(type)) return null;
        return entries;
    }

    private static Entry entry(CpObjectInventory.Item item, Instant collectedAt) {
        if (item == null || item.uid() == null || item.uid().isEmpty() || item.type() == null) return null;
        List<String> values = new ArrayList<>();
        for (String value : item.values() == null ? List.<String>of() : item.values()) {
            int colon = value.indexOf(": ");
            if (colon > 0 && CheckPointPolicyMapper.VALUE_FIELDS.contains(value.substring(0, colon))) values.add(value);
        }
        return new Entry(item.uid(), item.name() == null ? "" : item.name(), item.type(),
            item.members() == null ? List.of() : List.copyOf(item.members()), List.copyOf(values),
            "time".equals(item.type()) ? item.schedule() : null, collectedAt);
    }
}
