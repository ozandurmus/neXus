package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.CpObjectInventory;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import java.util.*;

/**
 * Derived only from stored, parsed snapshots. Identifiers are never normalized.
 *
 * <p>Where-used counts are computed once per index (one pass over every rule, then bounded group
 * traversal) and are a lower bound unless proven complete. A zero count is only proven when the
 * management's own unused-object report confirms it; every other gap stays UNKNOWN with a reason
 * from {@link #REASONS}.
 */
final class PolicyDomainIndex {
    static final String NO_RULES = "No rulebase is collected for this domain.";
    static final String RULES_INCOMPLETE = "Rule collection is incomplete for this domain.";
    static final String UNRESOLVED = "Some rule or group references could not be resolved.";
    static final String DYNAMIC = "Dynamic group membership is not evaluated.";
    static final String PAN_RULEBASES = "NAT, decryption and other non-security rulebases are not collected.";
    static final String PAN_OTHER_GROUPS = "References from other device groups are not counted.";
    static final String CP_OUTSIDE = "VPN, gateway topology and threat prevention references are not collected.";
    static final String UNUSED_NOT_COLLECTED = "Management unused-object report is not collected.";
    static final String NOT_IN_INVENTORY = "Object is not in the collected management inventory.";
    static final String MANAGEMENT_IN_USE = "Management reports the object in use outside collected rules.";
    static final String CONTRADICTION = "Management reports the object unused, but stored references exist.";
    static final String TAG_REFERENCES = "Tag references are not counted.";
    /** Fixed vocabulary: the only reason texts that may pass the AIView projection. */
    static final Set<String> REASONS = Set.of(NO_RULES, RULES_INCOMPLETE, UNRESOLVED, DYNAMIC, PAN_RULEBASES, PAN_OTHER_GROUPS,
        CP_OUTSIDE, UNUSED_NOT_COLLECTED, NOT_IN_INVENTORY, MANAGEMENT_IN_USE, CONTRADICTION, TAG_REFERENCES);

    final List<Map<String, Object>> objects = new ArrayList<>();
    final List<Map<String, Object>> duplicates = new ArrayList<>();
    final Map<String, List<Map<String, Object>>> rules = new HashMap<>(), groups = new HashMap<>();
    final Map<String, String> aliases = new HashMap<>();
    /** Domain-inventory rows by vendor uid (excluding snapshot-only objects), for resolving dictionary misses. */
    private final Map<String, Map<String, Object>> inventoryRows = new HashMap<>();
    final List<CpObjectInventory> inventories;
    final List<PolicySnapshot> snapshots;

    final Map<String, Integer> objectCounts = new LinkedHashMap<>();
    /** Domain-wide reasons why no stored count in this index can be proven complete. */
    final List<String> usageGaps;
    /** Number of passes over the rulebase while building usage; guards the single-pass algorithm. */
    int rulePasses;
    PolicyDomainIndex(List<CpObjectInventory> inventories, List<PolicySnapshot> snapshots) {
        this(consume -> inventories.forEach(consume), snapshots);
    }
    /** Retain derived lookup state, not the full inventory JSON or duplicate parsed item lists. */
    PolicyDomainIndex(java.util.function.Consumer<java.util.function.Consumer<CpObjectInventory>> stream,
            List<PolicySnapshot> snapshots) {
        this.snapshots = List.copyOf(snapshots);
        var items = new LinkedHashMap<String, CpObjectInventory.Item>();
        var unused = new HashSet<String>();
        boolean[] unusedState = {false};
        var states = new LinkedHashMap<String, CpObjectInventory>();
        var gateways = new ArrayList<CpObjectInventory.Item>();
        stream.accept(inventory -> {
            objectCounts.merge(inventory.type(), inventory.objects().size(), Integer::sum);
            states.put(inventory.type(), new CpObjectInventory(inventory.sourceId(), inventory.containerId(), inventory.type(),
                inventory.collectedAt(), inventory.status(), inventory.reason(), List.of(), inventory.pages(), inventory.seconds()));
            if (inventory.type().equals("unused-objects")) {
                if (inventory.status().equals("RESOLVED")) {
                    unusedState[0] = true; inventory.objects().forEach(o -> unused.add(o.uid()));
                }
            } else if (inventory.type().equals("gateways-and-servers")) gateways.addAll(inventory.objects());
            else inventory.objects().forEach(o -> items.put(o.uid(), o));
        });
        var gatewayState = states.get("gateways-and-servers");
        if (gatewayState != null) states.put(gatewayState.type(), new CpObjectInventory(gatewayState.sourceId(), gatewayState.containerId(),
            gatewayState.type(), gatewayState.collectedAt(), gatewayState.status(), gatewayState.reason(), gateways, gatewayState.pages(), gatewayState.seconds()));
        this.inventories = List.copyOf(states.values());
        boolean unusedKnown = unusedState[0];
        var inventoryUids = Set.copyOf(items.keySet());
        var statuses = new HashMap<String, String>();
        items.values().forEach(o -> { aliases.put(o.id(), o.uid()); aliases.put(o.uid(), o.uid()); });
        for (var snapshot : snapshots) items.values().forEach(o -> aliases.put(ref(snapshot.metadata().id(), "object", o.uid()), o.uid()));
        // Snapshot-only objects (including PAN) use their existing opaque ID as their lookup reference.
        for (var snapshot : snapshots) for (var object : snapshot.objects().values()) {
            if (aliases.containsKey(object.id())) continue;
            aliases.put(object.id(), object.id());
            items.putIfAbsent(object.id(), new CpObjectInventory.Item(object.id(), object.id(), object.name(),
                object.type(), object.members(), object.values(), object.schedule(), List.of()));
            statuses.put(object.id(), object.status());
        }
        boolean[] unresolved = {false}, dynamic = {false};
        for (var object : items.values()) {
            if ("DYNAMIC".equals(statuses.get(object.uid()))) dynamic[0] = true;
            for (String member : referencedMembers(object)) {
                groups.computeIfAbsent(member, k -> new ArrayList<>()).add(Map.of("id", object.id(), "uid", object.uid(), "name", object.name(), "type", object.type()));
                if (!items.containsKey(aliases.getOrDefault(member, member))) unresolved[0] = true;
            }
        }
        rulePasses++;
        for (var snapshot : snapshots) for (var section : snapshot.sections()) for (var rule : section.rules()) {
            var refs = new ArrayDeque<String>();
            for (var cell : List.of(rule.source(), rule.destination(), rule.service(), rule.application())) refs.addAll(cell.refs());
            for (String field : List.of("time", "schedule", "install-on", "vpn", "content", "inline-layer", "translated-source", "translated-destination", "translated-service")) refs.addAll(rule.extras().getOrDefault(field, List.of()));
            var visited = new HashSet<String>();
            while (!refs.isEmpty()) {
                String id = refs.removeFirst();
                String uid = aliases.getOrDefault(id, id);
                if (!visited.add(uid)) continue;
                if (items.containsKey(uid)) rules.computeIfAbsent(uid, k -> new ArrayList<>()).add(Map.of(
                    "policyId", snapshot.metadata().id(), "policyName", snapshot.metadata().name(),
                    "layerRef", section.id(), "layerName", rule.extras().getOrDefault("layer-name", List.of()).stream().findFirst().orElse(section.name()), "ruleId", rule.id(), "number", rule.number()));
                var object = items.get(uid);
                if (object != null) {
                    refs.addAll(referencedMembers(object));
                    String status = statuses.get(uid);
                    // Unresolved, unsupported or precedence-UNKNOWN objects may hide members that are never reached.
                    if ("DYNAMIC".equals(status)) dynamic[0] = true;
                    else if (status != null && !status.equals("RESOLVED")) unresolved[0] = true;
                } else if (snapshot.objects().containsKey(id)) refs.addAll(snapshot.objects().get(id).members());
                else unresolved[0] = true;
            }
        }
        var gaps = new LinkedHashSet<String>();
        var vendors = new HashSet<String>();
        snapshots.forEach(s -> vendors.add(s.metadata().vendor()));
        if (snapshots.isEmpty()) gaps.add(NO_RULES);
        if (snapshots.stream().anyMatch(s -> !s.failures().isEmpty())) gaps.add(RULES_INCOMPLETE);
        if (unresolved[0]) gaps.add(UNRESOLVED);
        if (dynamic[0]) gaps.add(DYNAMIC);
        if (vendors.contains("PAN")) { gaps.add(PAN_RULEBASES); gaps.add(PAN_OTHER_GROUPS); }
        if (vendors.contains("CP") || (snapshots.isEmpty() && !inventoryUids.isEmpty())) gaps.add(CP_OUTSIDE);
        this.usageGaps = List.copyOf(gaps);
        var byValue = new LinkedHashMap<String, List<CpObjectInventory.Item>>();
        for (var object : items.values()) {
            String normalized = normalized(object);
            if (normalized != null) byValue.computeIfAbsent(normalized, k -> new ArrayList<>()).add(object);
        }
        var duplicateIds = new HashMap<String, String>();
        for (var equivalent : byValue.values()) {
            if (equivalent.stream().map(CpObjectInventory.Item::name).distinct().count() < 2) continue;
            String id = ref(equivalent.stream().map(CpObjectInventory.Item::id).sorted().toArray(String[]::new));
            equivalent.forEach(o -> duplicateIds.put(o.uid(), id));
            duplicates.add(Map.of("id", id, "objects", equivalent.stream().map(o -> Map.of(
                "id", o.id(), "uid", o.uid(), "name", o.name(), "type", o.type())).toList()));
        }
        for (var object : items.values()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", object.id()); row.put("uid", object.uid()); row.put("name", object.name()); row.put("type", object.type());
            row.put("values", object.values()); row.put("members", object.members()); row.put("schedule", object.schedule());
            row.put("status", statuses.getOrDefault(object.uid(), "RESOLVED"));
            int ruleCount = rules.getOrDefault(object.uid(), List.of()).size();
            int groupCount = groups.getOrDefault(object.uid(), List.of()).size();
            Boolean reported = unusedKnown && inventoryUids.contains(object.uid()) ? unused.contains(object.uid()) : null;
            var unusedReasons = new ArrayList<String>();
            if (reported == null) unusedReasons.add(unusedKnown ? NOT_IN_INVENTORY : UNUSED_NOT_COLLECTED);
            else if (reported && ruleCount + groupCount > 0) { reported = null; unusedReasons.add(CONTRADICTION); }
            // Only the management's complete unused report, agreeing with zero stored references, proves zero.
            boolean complete = Boolean.TRUE.equals(reported);
            var usageReasons = new LinkedHashSet<String>();
            if (!complete) {
                if (Boolean.FALSE.equals(reported) && ruleCount + groupCount == 0) usageReasons.add(MANAGEMENT_IN_USE);
                usageReasons.addAll(unusedReasons);
                usageReasons.addAll(usageGaps);
                if (object.type().equals("tag")) usageReasons.add(TAG_REFERENCES);
            }
            row.put("unused", reported);
            row.put("unusedReasons", List.copyOf(unusedReasons));
            boolean group = Set.of("group", "address-group", "service-group", "time-group").contains(object.type());
            row.put("emptyGroup", group && object.members().isEmpty());
            row.put("singleMember", group && new HashSet<>(object.members()).size() == 1);
            row.put("duplicateId", duplicateIds.get(object.uid()));
            row.put("ruleCount", ruleCount);
            row.put("groupCount", groupCount);
            row.put("usageComplete", complete);
            row.put("usageReasons", List.copyOf(usageReasons));
            objects.add(Collections.unmodifiableMap(row));
        }
        for (var row : objects) if (inventoryUids.contains((String) row.get("uid"))) inventoryRows.put((String) row.get("uid"), row);
    }

    /**
     * The domain-inventory row a package-snapshot reference (object id or vendor uid) resolves to, or null.
     * Group members that the rulebase dictionary did not carry are resolved here; the inventory is the same domain's
     * management listing, collected separately, so callers label the result with its provenance.
     */
    Map<String, Object> inventoryObject(String reference) {
        String uid = aliases.get(reference);
        return uid == null ? null : inventoryRows.get(uid);
    }

    /** Per-viewer search/sort projection, computed once per index instead of once per comparison. */
    record Views(String[] text, String[] sortKey) {}
    private final Views[] views = new Views[2];
    int viewBuilds;
    synchronized Views views(boolean masked, java.util.function.UnaryOperator<Map<String, Object>> project) {
        int slot = masked ? 1 : 0;
        if (views[slot] == null) {
            viewBuilds++;
            String[] text = new String[objects.size()], sortKey = new String[objects.size()];
            for (int i = 0; i < objects.size(); i++) {
                var row = objects.get(i);
                var view = project.apply(row);
                text[i] = (view.getOrDefault("name", "") + " " + view.getOrDefault("policyName", "") + " " + view.getOrDefault("values", ""))
                    .toLowerCase(Locale.ROOT);
                sortKey[i] = String.valueOf(view.get("name")) + row.get("id");
            }
            views[slot] = new Views(text, sortKey);
        }
        return views[slot];
    }

    private static Set<String> referencedMembers(CpObjectInventory.Item object) {
        var members = new LinkedHashSet<>(object.members());
        if (object.type().equals("group-with-exclusion")) for (String value : object.values())
            if (value.startsWith("include: ") || value.startsWith("except: ")) members.add(value.substring(value.indexOf(": ") + 2));
        return members;
    }

    /** Compare values before masking; incomplete or unfamiliar shapes cannot establish equivalence. */
    static String normalized(CpObjectInventory.Item object) {
        var values = new HashMap<String, String>();
        for (String value : object.values()) {
            int colon = value.indexOf(": ");
            if (colon > 0) values.put(value.substring(0, colon), value.substring(colon + 2).trim());
        }
        try {
            String type = object.type();
            if (Set.of("group", "service-group", "time-group").contains(type)) {
                return type + ":" + ref(new TreeSet<>(object.members()).toArray(String[]::new));
            }
            if (type.equals("host") || type.equals("network")) {
                String ip = values.get(type.equals("host") ? "ipv4-address" : "subnet4");
                if (ip == null || values.keySet().stream().anyMatch(k -> k.contains("6"))) return null;
                String mask = values.get("mask-length4");
                if (type.equals("network") && mask == null && values.containsKey("subnet-mask")) {
                    long n = PolicyRuleQuery.interval(values.get("subnet-mask"))[0];
                    long inverse = (~n) & 0xffffffffL;
                    if ((inverse & (inverse + 1)) != 0) return null;
                    mask = String.valueOf(Long.bitCount(n));
                }
                if (type.equals("network") && mask == null) return null;
                long[] interval = PolicyRuleQuery.interval(ip + "/" + (type.equals("host") ? "32" : mask));
                return type + ":" + interval[0] + ":" + interval[1];
            }
            if (type.equals("address-range")) {
                if (values.keySet().stream().anyMatch(k -> k.contains("6"))) return null;
                String first = values.getOrDefault("ipv4-address-first", values.get("ip-address-first"));
                String last = values.getOrDefault("ipv4-address-last", values.get("ip-address-last"));
                if (first == null || last == null) return null;
                long[] interval = PolicyRuleQuery.interval(first + "-" + last);
                return type + ":" + interval[0] + ":" + interval[1];
            }
            if (type.equals("service-tcp") || type.equals("service-udp") || type.equals("service-sctp")) {
                String port = values.get("port");
                if (port == null || !port.matches("[0-9, -]+")) return null;
                // Sorted, merged intervals normalize ordering and adjacent port ranges without guessing expressions.
                var ranges = new ArrayList<int[]>();
                for (String part : port.split(",", -1)) {
                    String[] endpoints = part.trim().split("-", -1);
                    if (endpoints.length > 2) return null;
                    int a = Integer.parseInt(endpoints[0].trim()), b = endpoints.length == 1 ? a : Integer.parseInt(endpoints[1].trim());
                    if (a < 0 || a > b || b > 65535) return null;
                    ranges.add(new int[]{a, b});
                }
                ranges.sort(Comparator.comparingInt(r -> r[0]));
                var merged = new ArrayList<String>(); int start = -1, end = -1;
                for (var range : ranges) {
                    if (start < 0) { start = range[0]; end = range[1]; }
                    else if (range[0] <= end + 1) end = Math.max(end, range[1]);
                    else { merged.add(start + "-" + end); start = range[0]; end = range[1]; }
                }
                merged.add(start + "-" + end);
                return type + ":" + merged + ":" + values.getOrDefault("source-port", "");
            }
        } catch (IllegalArgumentException invalid) { return null; }
        return null;
    }
}
