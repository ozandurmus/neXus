package com.securityexpert.nexus.ui2.worker.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.util.*;

/**
 * Offline mapper. Callers supply ordered access-layer pages and dictionaries, and optionally an object-inventory lookup;
 * no transport or command creation.
 */
public final class CheckPointPolicyMapper {
    /**
     * An inventory object older than this, relative to the snapshot's own collection time, is not used: the reference stays
     * UNRESOLVED. The domain inventory is refreshed at most every 24 h (UI2_POLICY_CP_OBJECTS_EVERY=86400); 48 h tolerates
     * one missed refresh and nothing more (docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md, amendment 2026-11-04).
     */
    public static final java.time.Duration INVENTORY_MAX_AGE = java.time.Duration.ofHours(48);
    /** The object fields the mapper keeps as values, from a dictionary object or an inventory object alike. */
    static final List<String> VALUE_FIELDS = List.of("ipv4-address", "ipv6-address", "subnet4", "mask-length4", "subnet6", "mask-length6",
        "port", "icmp-type", "icmp-code", "ipv4-address-first", "ipv4-address-last", "ipv6-address-first", "ipv6-address-last",
        "ip-address-first", "ip-address-last");

    /** Exact-uid object lookup in the policy's own domain inventory (then the MDS Global domain's); null when absent. */
    public interface InventoryLookup { InventoryObject find(String uid); }
    /**
     * One object of a complete stored inventory, as an object node with the dictionary's field names (uid, name, type,
     * members, {@link #VALUE_FIELDS}); a time object carries the schedule its inventory parse produced from the same reply.
     */
    public record InventoryObject(String uid, JsonNode node, com.securityexpert.nexus.ui2.policy.PolicySchedule schedule, java.time.Instant collectedAt) {}

    /** Where an object node comes from: the rulebase dictionary first, then (optionally) the domain inventory. */
    private static final class Sources {
        final Map<String, JsonNode> dictionary = new LinkedHashMap<>();
        final InventoryLookup inventory;
        final java.time.Instant notBefore;
        int resolved;
        java.time.Instant oldest;
        Sources(InventoryLookup inventory, String collectedAt) {
            this.inventory = inventory;
            java.time.Instant at = null;
            try { at = inventory == null ? null : java.time.Instant.parse(collectedAt); }
            catch (RuntimeException unparseable) { /* No snapshot time: no inventory age can be judged; fail closed. */ }
            this.notBefore = at == null ? null : at.minus(INVENTORY_MAX_AGE);
        }
        /** Only an inventory object whose uid equals the reference exactly and whose inventory is within the age bound. */
        InventoryObject inventory(String uid) {
            if (inventory == null || notBefore == null) return null;
            InventoryObject found = inventory.find(uid);
            if (found == null || found.collectedAt() == null || found.node() == null || !uid.equals(found.uid())
                    || !uid.equals(found.node().path("uid").asText(null)) || found.collectedAt().isBefore(notBefore)) return null;
            return found;
        }
        void used(InventoryObject found) {
            resolved++;
            if (oldest == null || found.collectedAt().isBefore(oldest)) oldest = found.collectedAt();
        }
    }

    public PolicySnapshot map(Metadata metadata, List<JsonNode> pages) {
        return map(metadata, pages, List.of());
    }
    public PolicySnapshot map(Metadata metadata, List<JsonNode> pages, List<JsonNode> natPages) {
        return map(metadata, pages, natPages, null);
    }
    /**
     * With an inventory lookup, a reference the objects-dictionary does not carry (a rule cell or a group member at any
     * depth) is taken from the stored inventory, parsed by the same code path, and counted in the metadata's
     * {@link InventoryProvenance}. Without one (null) the metadata is unchanged and such references stay UNRESOLVED.
     */
    public PolicySnapshot map(Metadata metadata, List<JsonNode> pages, List<JsonNode> natPages, InventoryLookup inventory) {
        Sources dictionary = new Sources(inventory, metadata.collectedAt());
        for (JsonNode page : java.util.stream.Stream.concat(pages.stream(), natPages.stream()).toList()) for (JsonNode object : page.path("objects-dictionary")) {
            String uid = object.path("uid").asText();
            if (!uid.isEmpty()) dictionary.dictionary.put(uid, object);
        }
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        for (String uid : List.copyOf(dictionary.dictionary.keySet())) object(metadata, uid, dictionary, objects);
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
        Map<Section, String> keys = new IdentityHashMap<>();
        for (String layer : layers.keySet()) if (!inline.contains(layer))
            layer(metadata, layer, null, layers, dictionary, objects, sections, keys, new HashSet<>());
        if (!layers.isEmpty() && sections.isEmpty()) throw new IllegalArgumentException("Cyclic inline layers");
        for (JsonNode page : natPages) nat(metadata, page.path("rulebase"), ref(metadata.id(), "nat"),
                "NAT", dictionary, objects, sections, keys, 0);
        Metadata mapped = inventory == null ? metadata : metadata.withInventoryProvenance(new InventoryProvenance(dictionary.resolved,
            dictionary.oldest == null ? null : dictionary.oldest.toString()));
        // A section that spans several API pages is repeated on each page; join those fragments (ids and rule ids stay as mapped).
        return new PolicySnapshot(mapped, PolicySnapshot.mergeAdjacent(sections, keys::get), objects);
    }
    private void nat(Metadata meta, JsonNode nodes, String section, String name, Sources dict,
            Map<String, PolicyObject> objects, List<Section> sections, Map<Section, String> keys, int depth) {
        if (depth > 32 || !nodes.isArray()) throw new IllegalArgumentException("Invalid NAT rulebase");
        List<Rule> rules = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (node.path("type").asText().equals("nat-section")) {
                if (!rules.isEmpty()) {
                    add(sections, keys, section, new Section(ref(section, rules.get(0).uuid()), name, "CP NAT rulebase", null, rules));
                    rules.clear();
                }
                nat(meta, node.path("rulebase"), ref(section, node.path("uid").asText()), node.path("name").asText("NAT section"),
                        dict, objects, sections, keys, depth + 1);
                continue;
            }
            String uid = node.path("uid").asText();
            if (!node.path("type").asText().equals("nat-rule") || uid.isEmpty())
                throw new IllegalArgumentException("Unsupported NAT entry");
            Map<String, List<String>> extras = new LinkedHashMap<>();
            for (String field : List.of("translated-source", "translated-destination", "translated-service", "install-on"))
                extras.put(field, texts(node.path(field)).stream().map(value -> object(meta, value, dict, objects)).toList());
            if (node.path("auto-generated").isBoolean()) extras.put("auto-generated", List.of(node.path("auto-generated").asText()));
            rules.add(new Rule(ref(section, uid), uid, node.path("rule-number").asInt(), node.path("name").asText(""),
                    node.path("enabled").isBoolean() ? node.path("enabled").asBoolean() : null,
                    cell(meta, node, "original-source", dict, objects), cell(meta, node, "original-destination", dict, objects),
                    cell(meta, node, "original-service", dict, objects), new Cell(List.of(), false),
                    node.path("method").asText("UNKNOWN"), "UNKNOWN", node.path("comments").asText(""), extras));
        }
        if (!rules.isEmpty()) add(sections, keys, section, new Section(ref(section, rules.get(0).uuid()), name, "CP NAT rulebase", null, rules));
        if (nodes.isEmpty()) add(sections, keys, section, new Section(section, name, "CP NAT rulebase", null, List.of()));
    }
    private static void inlineLayers(JsonNode nodes, Set<String> inline, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Policy nesting limit exceeded");
        for (JsonNode node : nodes) {
            if (node.has("inline-layer")) inline.add(CheckPointPolicyCollector.inlineUid(node.path("inline-layer")));
            if (node.has("rulebase")) inlineLayers(node.path("rulebase"), inline, depth + 1);
        }
    }
    private void layer(Metadata meta, String uid, String parent, Map<String, List<JsonNode>> layers,
            Sources dict, Map<String, PolicyObject> objects, List<Section> sections, Map<Section, String> keys, Set<String> path) {
        if (!path.add(uid) || path.size() > 32) throw new IllegalArgumentException("Cyclic or excessive inline layers");
        if (!layers.containsKey(uid)) {
            sections.add(new Section(ref(meta.id(), uid, parent == null ? "" : parent), "Inline layer unavailable", "CP access layer", parent, List.of()));
        } else for (JsonNode page : layers.get(uid)) {
            walk(meta, page.path("rulebase"), ref(meta.id(), uid, parent == null ? "" : parent),
                    page.path("name").asText("Access layer"), page.path("name").asText("Access layer"), parent, layers, dict, objects, sections, keys, path, 0);
        }
        path.remove(uid);
    }
    private void walk(Metadata meta, JsonNode nodes, String section, String name, String layerName, String parent,
            Map<String, List<JsonNode>> layers, Sources dict, Map<String, PolicyObject> objects,
            List<Section> sections, Map<Section, String> keys, Set<String> path, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Policy nesting limit exceeded");
        List<Rule> pending = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (node.path("type").asText().equals("access-section")) {
                flush(sections, keys, section, name, parent, pending);
                walk(meta, node.path("rulebase"), ref(section, node.path("uid").asText()), node.path("name").asText("Section"),
                        layerName, parent, layers, dict, objects, sections, keys, path, depth + 1);
                continue;
            }
            if (!node.path("type").asText().equals("access-rule")) throw new IllegalArgumentException("Unsupported access rule entry");
            String uuid = node.path("uid").asText();
            String ruleName = node.path("name").asText("");
            if (uuid.isEmpty() && ruleName.isEmpty()) throw new IllegalArgumentException("Missing rule identity");
            String ruleId = ref(section, uuid.isEmpty() ? ruleName : uuid);
            Map<String, List<String>> extras = new LinkedHashMap<>();
            for (String field : List.of("install-on", "vpn", "time", "content", "inline-layer")) {
                List<String> values = texts(node.path(field));
                if (!values.isEmpty()) extras.put(field, values.stream().map(value -> object(meta, value, dict, objects)).toList());
            }
            for (String field : List.of("accounting", "alert", "per-connection", "per-session"))
                if (node.path("track").has(field)) extras.put("track-" + field, List.of(node.path("track").path(field).asText()));
            extras.put("layer-name", List.of(layerName));
            if (!node.has("time")) extras.put("time-uncollected", List.of("true"));
            String modified = modifiedTime(node.path("meta-info").path("last-modify-time"));
            if (modified != null) extras.put("last-modified", List.of(modified));
            if (node.path("meta-info").has("last-modifier")) extras.put("last-modifier", List.of(node.path("meta-info").path("last-modifier").asText()));
            pending.add(new Rule(ruleId, uuid, node.path("rule-number").asInt(0), node.path("name").asText(""),
                    node.path("enabled").isBoolean() ? node.path("enabled").asBoolean() : null,
                    cell(meta, node, "source", dict, objects), cell(meta, node, "destination", dict, objects),
                    cell(meta, node, "service", dict, objects), new Cell(List.of(), false),
                    resolve(node.path("action"), dict), resolve(node.path("track").path("type"), dict), node.path("comments").asText(""), extras, PolicyHitCounts.checkPoint(node.path("hits"), meta.collectedAt())));
            if (node.has("inline-layer")) {
                flush(sections, keys, section, name, parent, pending);
                layer(meta, CheckPointPolicyCollector.inlineUid(node.path("inline-layer")), ruleId, layers, dict, objects, sections, keys, path);
            }
        }
        flush(sections, keys, section, name, parent, pending);
        if (nodes.isEmpty()) add(sections, keys, section, new Section(section, name, "CP access layer", parent, List.of()));
    }
    /**
     * A rule's vendor modification time as a UTC instant, so the change report can date the revision instead of
     * falling back to the collection time. CP returns {posix (milliseconds, as CpDomainReuse reads publish-time),
     * iso-8601 (minute precision; the offset may lack a colon, e.g. +0300)}. When both parse they must agree within
     * the ISO minute, otherwise the time stays absent (UNKNOWN). A zone-less value is kept as a local date only.
     */
    static String modifiedTime(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        String iso = node.isObject() ? node.path("iso-8601").asText("") : node.asText("");
        java.time.Instant fromIso = null;
        try { fromIso = java.time.OffsetDateTime.parse(iso.replaceFirst("([+-][0-9]{2})([0-9]{2})$", "$1:$2")).toInstant(); }
        catch (RuntimeException notAnInstant) { /* Zone-less or unsupported ISO text. */ }
        JsonNode posix = node.path("posix");
        if (posix.isIntegralNumber() && posix.canConvertToLong() && posix.longValue() > 0) {
            var fromPosix = java.time.Instant.ofEpochMilli(posix.longValue());
            if (fromIso != null && Math.abs(java.time.Duration.between(fromIso, fromPosix).toSeconds()) >= 60) return null;
            return fromPosix.toString();
        }
        if (fromIso != null) return fromIso.toString();
        try { return com.securityexpert.nexus.ui2.policy.PolicySchedule.date(iso); }
        catch (RuntimeException invalid) { return null; /* Unsupported metadata remains absent. */ }
    }
    private static void add(List<Section> sections, Map<Section, String> keys, String logicalRef, Section section) {
        sections.add(section);
        keys.put(section, logicalRef);
    }
    private static void flush(List<Section> sections, Map<Section, String> keys, String id, String name, String parent, List<Rule> pending) {
        if (pending.isEmpty()) return;
        add(sections, keys, id, new Section(ref(id, pending.get(0).uuid().isEmpty() ? pending.get(0).id() : pending.get(0).uuid()), name, "CP access layer", parent, pending));
        pending.clear();
    }
    private Cell cell(Metadata meta, JsonNode rule, String field, Sources dict, Map<String, PolicyObject> objects) {
        return new Cell(texts(rule.path(field)).stream().map(value -> object(meta, value, dict, objects)).toList(),
                rule.path(field + "-negate").asBoolean(false));
    }
    private String object(Metadata meta, String uid, Sources dict, Map<String, PolicyObject> objects) {
        return object(meta, uid, dict, objects, 0);
    }
    private String object(Metadata meta, String uid, Sources dict, Map<String, PolicyObject> objects, int depth) {
        String id = ref(meta.id(), "object", uid);
        if (objects.containsKey(id)) return id;
        if (depth > 32) throw new IllegalArgumentException("Policy object nesting limit exceeded");
        JsonNode node = dict.dictionary.get(uid);
        // An inventory object's members are one level deeper; beyond the nesting limit the reference stays UNRESOLVED
        // instead of failing the whole mapping.
        InventoryObject inventory = node == null && depth < 32 ? dict.inventory(uid) : null;
        if (node == null && inventory == null) {
            objects.put(id, new PolicyObject(id, uid, "unresolved", List.of(), List.of(), "UNRESOLVED"));
            return id;
        }
        if (inventory != null) {
            node = inventory.node();
            dict.used(inventory);
        }
        String nativeType = node.path("type").asText();
        String type = type(nativeType);
        String name = type.equals("any") ? "ANY" : node.path("name").asText(uid);
        objects.put(id, new PolicyObject(id, name, type, List.of(), List.of(), "UNRESOLVED")); // cycle guard
        List<String> members = texts(node.path("members")).stream().map(member -> object(meta, member, dict, objects, depth + 1)).toList();
        List<String> values = new ArrayList<>();
        if (nativeType.startsWith("service-") && !nativeType.equals("service-group"))
            values.add("protocol: " + nativeType.substring("service-".length()));
        // Address ranges (and multicast address ranges) carry ipv4-address-first/-last and ipv6-address-first/-last in
        // the Check Point object representation; ip-address-first/-last is the generic request parameter of
        // add-address-range. Reading only the generic name left every dictionary range without values (2026-10-20).
        for (String field : VALUE_FIELDS)
            if (node.has(field)) values.add(field + ": " + node.path(field).asText());
        if (nativeType.equals("access-role")) values.addAll(accessRoleNetworks(meta, node.path("networks"), dict, objects, depth));
        String status = com.securityexpert.nexus.ui2.policy.CpObjectTypes.status(nativeType);
        // Keep the vendor class of an unmodelled object so the remaining gap can be counted by type (no names, no values).
        if (status.equals("UNSUPPORTED") && type.equals("unresolved"))
            values.add("native-type: " + (nativeType.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") ? nativeType : "UNKNOWN"));
        objects.put(id, new PolicyObject(id, name, type, members, values, status, nativeType.equals("time") ? (inventory != null ? inventory.schedule() : CheckPointSchedule.parse(node)) : null));
        return id;
    }
    /**
     * The networks component of an access role, one {@code networks: <object reference>} value per network, or
     * {@code networks: any}. Check Point Management API reference, show-access-role reply field "networks"
     * (https://sc1.checkpoint.com/documents/latest/APIs/index.html#web/show-access-role): "Collection of Network objects
     * identified by the name or UID that can access the system." The access role stays DYNAMIC (its users, machines and
     * remote access clients are enforcement-time identity and are neither parsed nor stored); only the network
     * references are kept, as opaque object references resolved through the same dictionary as every other reference.
     * The rulebase read already returns it: show-access-rulebase details-level full use-object-dictionary true, whose
     * objects-dictionary "Level of details in the output corresponds to the number of details for search".
     * <p>The reply's representation of "any" is not documented and no real-shaped fixture holds one, so every shape is
     * read fail-closed: the text "any" or an element of type CpmiAnyObject is {@code networks: any}; an element with a
     * uid (or a uid text) is that object; a missing field, null, an empty list or an element without a uid stores
     * nothing at all (no bound). Shadowing reads a bound only from values it fully resolves.
     */
    private List<String> accessRoleNetworks(Metadata meta, JsonNode networks, Sources dict, Map<String, PolicyObject> objects, int depth) {
        if (networks.isMissingNode() || networks.isNull()) return List.of();
        List<String> out = new ArrayList<>();
        Iterable<JsonNode> elements = networks.isArray() ? networks : List.of(networks);
        for (JsonNode element : elements) {
            String uid = element.isObject() ? element.path("uid").asText("") : element.isTextual() ? element.asText() : "";
            if (element.isObject() && element.path("type").asText("").equals("CpmiAnyObject") || element.isTextual() && uid.equals("any")) out.add("networks: any");
            else if (uid.isEmpty()) return List.of();
            else out.add("networks: " + object(meta, uid, dict, objects, depth + 1));
        }
        return out;
    }
    static final Set<String> DYNAMIC_TYPES = com.securityexpert.nexus.ui2.policy.CpObjectTypes.DYNAMIC_TYPES;
    static final Set<String> GATEWAY_TYPES = com.securityexpert.nexus.ui2.policy.CpObjectTypes.GATEWAY_TYPES;
    /** Native dictionary type to the viewer's object class; see {@link com.securityexpert.nexus.ui2.policy.CpObjectTypes}. */
    static String type(String nativeType) { return com.securityexpert.nexus.ui2.policy.CpObjectTypes.type(nativeType); }
    private static String resolve(JsonNode node, Sources dict) {
        if (node.isObject()) return node.path("name").asText("UNKNOWN");
        return dict.dictionary.containsKey(node.asText()) ? dict.dictionary.get(node.asText()).path("name").asText("UNKNOWN") : "UNKNOWN";
    }
    private static List<String> texts(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        List<String> result = new ArrayList<>();
        if (node.isArray()) node.forEach(value -> result.add(value.isObject() ? value.path("uid").asText() : value.asText()));
        else result.add(node.isObject() ? node.path("uid").asText() : node.asText());
        return result;
    }
}
