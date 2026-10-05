package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.CpObjectInventory;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import java.util.*;

/** Derived only from stored, parsed snapshots. Identifiers are never normalized. */
final class PolicyDomainIndex {
    final List<Map<String, Object>> objects = new ArrayList<>();
    final List<Map<String, Object>> duplicates = new ArrayList<>();
    final Map<String, List<Map<String, Object>>> rules = new HashMap<>(), groups = new HashMap<>();
    final List<CpObjectInventory> inventories;
    final List<PolicySnapshot> snapshots;

    PolicyDomainIndex(List<CpObjectInventory> inventories, List<PolicySnapshot> snapshots) {
        this.inventories = List.copyOf(inventories); this.snapshots = List.copyOf(snapshots);
        var items = new LinkedHashMap<String, CpObjectInventory.Item>();
        var unused = new HashSet<String>();
        boolean unusedKnown = inventories.stream().anyMatch(i -> i.type().equals("unused-objects") && i.status().equals("RESOLVED"));
        for (var inventory : inventories) {
            if (inventory.type().equals("unused-objects")) {
                if (inventory.status().equals("RESOLVED")) inventory.objects().forEach(o -> unused.add(o.uid()));
            } else if (!inventory.type().equals("gateways-and-servers")) inventory.objects().forEach(o -> items.put(o.uid(), o));
        }
        var aliases = new HashMap<String, String>();
        items.values().forEach(o -> { aliases.put(o.id(), o.uid()); aliases.put(o.uid(), o.uid()); });
        for (var snapshot : snapshots) items.values().forEach(o -> aliases.put(ref(snapshot.metadata().id(), "object", o.uid()), o.uid()));
        for (var object : items.values()) for (String member : referencedMembers(object)) {
            groups.computeIfAbsent(member, k -> new ArrayList<>()).add(Map.of("id", object.id(), "uid", object.uid(), "name", object.name(), "type", object.type()));
        }
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
                    "layerRef", section.id(), "layerName", section.name(), "ruleId", rule.id(), "number", rule.number()));
                var object = items.get(uid);
                if (object != null) refs.addAll(referencedMembers(object));
                else if (snapshot.objects().containsKey(id)) refs.addAll(snapshot.objects().get(id).members());
            }
        }
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
            row.put("unused", unusedKnown ? unused.contains(object.uid()) : null);
            boolean group = Set.of("group", "service-group", "time-group").contains(object.type());
            row.put("emptyGroup", group && object.members().isEmpty());
            row.put("singleMember", group && new HashSet<>(object.members()).size() == 1);
            row.put("duplicateId", duplicateIds.get(object.uid()));
            row.put("ruleCount", rules.getOrDefault(object.uid(), List.of()).size());
            row.put("groupCount", groups.getOrDefault(object.uid(), List.of()).size());
            objects.add(Collections.unmodifiableMap(row));
        }
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
