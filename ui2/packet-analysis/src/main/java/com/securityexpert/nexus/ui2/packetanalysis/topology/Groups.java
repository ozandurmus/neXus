package com.securityexpert.nexus.ui2.packetanalysis.topology;

import static com.securityexpert.nexus.ui2.packetanalysis.topology.Py.dict;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Multi-path client groups, default point layout and capture exclusion reasons ({@code topology/groups.py}).
 * Captures are the {@code Project.inventory()} dicts ({@code id}, {@code name}, {@code state}, {@code inventory}).
 */
public final class Groups {

    private static final Pattern STEM_CHARS = Pattern.compile("[^A-Za-z0-9_.-]+");

    private Groups() {
    }

    static String id(Map<String, Object> capture) {
        return (String) capture.get("id");
    }

    static String name(Map<String, Object> capture) {
        return (String) capture.get("name");
    }

    private static String stripUnderscores(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == '_') {
            a++;
        }
        while (b > a && s.charAt(b - 1) == '_') {
            b--;
        }
        return s.substring(a, b);
    }

    /** {@code _default_points(captures, existing_points)}: one point per capture, file-stem ids when unique. */
    public static Map<String, Point> defaultPoints(List<Map<String, Object>> captures, List<Point> existing) {
        Map<String, Point> byCid = new HashMap<>();
        for (Point p : existing == null ? List.<Point>of() : existing) {
            if (p.captureId != null && !p.captureId.isEmpty()) {
                byCid.put(p.captureId, p.copy());
            }
        }
        List<String> stems = new ArrayList<>();
        for (Map<String, Object> c : captures) {
            stems.add(stripUnderscores(STEM_CHARS.matcher(Suggest.stem(name(c))).replaceAll("_")));
        }
        boolean uniqueStems = new HashSet<>(stems).size() == captures.size() && stems.stream().noneMatch(String::isEmpty);
        Map<String, Point> result = new LinkedHashMap<>();
        for (int i = 0; i < captures.size(); i++) {
            Map<String, Object> c = captures.get(i);
            String cid = id(c);
            if (byCid.containsKey(cid)) {
                Point pt = byCid.get(cid);
                if (uniqueStems && pt.id.equals("p_" + cid) && !pt.label.contains("/")) {
                    pt.id = stems.get(i);
                    pt.label = stems.get(i);
                    if (pt.device.equals(name(c))) {
                        pt.device = stems.get(i);
                    }
                }
                result.put(cid, pt);
            } else {
                String pid = uniqueStems ? stems.get(i) : "p_" + cid;
                String label = uniqueStems ? stems.get(i) : Py.prefix(name(c), 100);
                result.put(cid, Point.of(pid, label, label, cid, Py.i((i % 4) * 240L), Py.i((i / 4) * 180L)));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> inventoryOf(Map<String, Object> capture) {
        Object inv = capture.get("inventory");
        return Py.truthy(inv) ? (Map<String, Object>) inv : Map.of();
    }

    /** {@code _capture_exclusion_reason(db, captures, candidate_ids)}; null when no capture explains ambiguity. */
    public static String captureExclusionReason(ProjectDb.Db db, List<Map<String, Object>> captures,
            List<String> candidateIds) {
        Map<String, Map<String, Object>> byId = new HashMap<>();
        List<String> allIds = new ArrayList<>();
        for (Map<String, Object> c : captures) {
            if (Py.truthy(c.get("id"))) {
                byId.put(id(c), c);
                allIds.add(id(c));
            }
        }
        List<String> checkIds = candidateIds != null ? candidateIds : allIds;
        List<String> emptyMsgs = new ArrayList<>();
        List<String> mostlyMsgs = new ArrayList<>();
        for (String cid : checkIds) {
            Map<String, Object> c = byId.get(cid);
            if (c == null) {
                continue;
            }
            String name = name(c);
            Map<String, Object> inv = inventoryOf(c);
            long prefilterExcluded = Py.intOr0(inv.get("prefilter_excluded"));
            long tsExcluded = 0;
            Object counts = Py.or(inv.get("timestamp_excluded_counts"), Map.of());
            for (Object v : ((Map<?, ?>) counts).values()) {
                tsExcluded += Py.toInt(v);
            }
            long totalPackets = Py.intOr0(
                    db.fetchOne("SELECT count(*) FROM packets WHERE capture_id=?", List.of(cid)).get(0));
            long uniqueTcp = Py.intOr0(db.fetchOne(ProjectDb.sql("exclusion_unique_tcp", Map.of()), List.of(cid)).get(0));
            long nonMatchable = totalPackets - uniqueTcp;
            long excludedTotal = prefilterExcluded + tsExcluded + nonMatchable;
            long rawTotal = Math.max(Py.intOr0(inv.get("prefilter_total")), prefilterExcluded + tsExcluded + totalPackets);
            if (totalPackets == 0 || "error".equals(c.get("state"))) {
                if (prefilterExcluded > 0) {
                    emptyMsgs.add(name + " is empty (" + prefilterExcluded + " of " + rawTotal
                            + " frames excluded by pre-filter)");
                } else if (tsExcluded > 0) {
                    emptyMsgs.add(name + " is empty (" + tsExcluded + " frames excluded due to invalid timestamps)");
                } else {
                    emptyMsgs.add(name + " is empty (0 usable frames)");
                }
            } else if (candidateIds != null || uniqueTcp == 0) {
                if (rawTotal > 0 && (uniqueTcp == 0 || excludedTotal > uniqueTcp)) {
                    mostlyMsgs.add(name + " is mostly excluded (" + excludedTotal + " of " + rawTotal
                            + " frames excluded; " + uniqueTcp + " usable TCP frame(s) remain)");
                }
            }
        }
        if (!emptyMsgs.isEmpty()) {
            return "Capture(s) " + String.join("; ", emptyMsgs) + "; path order is ambiguous.";
        }
        if (!mostlyMsgs.isEmpty()) {
            return "Capture(s) " + String.join("; ", mostlyMsgs) + "; path order is ambiguous.";
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Set<String> hostCids(Map<String, Object> rawSuggestions, java.util.Collection<String> within) {
        Set<String> hosts = new HashSet<>();
        for (Object o : (List<Object>) rawSuggestions.getOrDefault("items", List.of())) {
            Map<String, Object> s = (Map<String, Object>) o;
            if ("host_capture".equals(s.get("kind"))) {
                String cid = (String) ((List<Object>) s.get("capture_ids")).get(0);
                if (within.contains(cid)) {
                    hosts.add(cid);
                }
            }
        }
        return hosts;
    }

    /**
     * {@code _partition_client_groups}: when host captures are shared by otherwise disconnected wire captures,
     * the capture id lists of each group (host captures included); null for a single path.
     */
    public static List<List<String>> partitionClientGroups(ProjectDb.Db db, List<Map<String, Object>> captures,
            Map<String, Object> rawSuggestions) {
        List<String> readyIds = new ArrayList<>();
        for (Map<String, Object> c : captures) {
            readyIds.add(id(c));
        }
        Set<String> hosts = hostCids(rawSuggestions, readyIds);
        List<String> nonHost = new ArrayList<>();
        for (String cid : readyIds) {
            if (!hosts.contains(cid)) {
                nonHost.add(cid);
            }
        }
        if (hosts.isEmpty() || nonHost.size() < 2) {
            return null;
        }
        List<Object> params = new ArrayList<>(readyIds);
        params.addAll(readyIds);
        List<Map<String, Object>> flowRows = db.rows(ProjectDb.sql("groups_shared_flows",
                Map.of("placeholders", ProjectDb.placeholders(readyIds.size()))), params);
        Map<String, Set<String>> flows = new HashMap<>();
        for (String cid : readyIds) {
            flows.put(cid, new HashSet<>());
        }
        for (Map<String, Object> r : flowRows) {
            flows.get((String) r.get("capture_id")).add((String) r.get("canon"));
        }
        for (String cid : nonHost) {
            if (flows.get(cid).isEmpty()) {
                return null;
            }
        }
        Map<String, Set<String>> adj = new HashMap<>();
        for (String cid : nonHost) {
            adj.put(cid, new HashSet<>());
        }
        for (int i = 0; i < nonHost.size(); i++) {
            for (int j = i + 1; j < nonHost.size(); j++) {
                String a = nonHost.get(i);
                String b = nonHost.get(j);
                if (intersects(flows.get(a), flows.get(b))) {
                    adj.get(a).add(b);
                    adj.get(b).add(a);
                }
            }
        }
        Set<String> visited = new HashSet<>();
        List<List<String>> components = new ArrayList<>();
        for (String cid : nonHost) {
            if (visited.contains(cid)) {
                continue;
            }
            Set<String> comp = component(cid, adj, visited);
            List<String> ordered = new ArrayList<>();
            for (String c : nonHost) {
                if (comp.contains(c)) {
                    ordered.add(c);
                }
            }
            components.add(ordered);
        }
        if (components.size() < 2) {
            return null;
        }
        List<List<String>> groups = new ArrayList<>();
        for (List<String> comp : components) {
            Set<String> compFlows = new HashSet<>();
            for (String c : comp) {
                compFlows.addAll(flows.get(c));
            }
            List<String> sharedHosts = new ArrayList<>();
            for (String h : readyIds) {
                if (hosts.contains(h) && intersects(compFlows, flows.get(h))) {
                    sharedHosts.add(h);
                }
            }
            if (sharedHosts.isEmpty()) {
                return null;
            }
            Set<String> ids = new HashSet<>(comp);
            ids.addAll(sharedHosts);
            List<String> group = new ArrayList<>();
            for (String cid : readyIds) {
                if (ids.contains(cid)) {
                    group.add(cid);
                }
            }
            groups.add(group);
        }
        return groups;
    }

    /** The connected component of {@code start}, marking visited nodes (iterative depth-first search). */
    static Set<String> component(String start, Map<String, Set<String>> adj, Set<String> visited) {
        Set<String> comp = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (!visited.add(cur)) {
                continue;
            }
            comp.add(cur);
            for (String n : adj.getOrDefault(cur, Set.of())) {
                if (!visited.contains(n)) {
                    stack.push(n);
                }
            }
        }
        return comp;
    }

    static boolean intersects(Set<String> a, Set<String> b) {
        for (String x : a) {
            if (b.contains(x)) {
                return true;
            }
        }
        return false;
    }

    /** The single-path assembler {@code assemble_multi_path_groups} calls for each group. */
    @FunctionalInterface
    interface SingleAssembler {
        Map<String, Object> assemble(ProjectDb.Db db, Topology base, List<Map<String, Object>> captures,
                Map<String, Object> rawSuggestions, List<Map<String, Object>> allCaptures);
    }

    /** {@code assemble_multi_path_groups}: assembles each client group and combines them. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> assembleMultiPathGroups(ProjectDb.Db db, Topology base,
            List<Map<String, Object>> captures, Map<String, Object> rawSuggestions, List<List<String>> groupCidLists,
            List<Object> warnings, SingleAssembler single) {
        Map<String, Map<String, Object>> byCid = new LinkedHashMap<>();
        for (Map<String, Object> c : captures) {
            byCid.put(id(c), c);
        }
        Set<String> hosts = hostCids(rawSuggestions, byCid.keySet());
        List<Map<String, Object>> groups = new ArrayList<>();
        Set<String> seenCidrs = new HashSet<>();
        List<Object> combined = new ArrayList<>(warnings);
        for (List<String> gCids : groupCidLists) {
            List<Map<String, Object>> gCaptures = new ArrayList<>();
            for (String cid : gCids) {
                gCaptures.add(byCid.get(cid));
            }
            Map<String, Object> gRes = single.assemble(db, base, gCaptures, rawSuggestions, captures);
            for (Object w : (List<Object>) gRes.getOrDefault("warnings", List.of())) {
                if (!combined.contains(w)) {
                    combined.add(w);
                }
            }
            Map<String, Object> gTopo = (Map<String, Object>) gRes.get("topology");
            List<String> gCidrs = new ArrayList<>();
            for (Object c : (List<Object>) Py.or(gTopo.get("client_cidrs"), List.of())) {
                gCidrs.add((String) c);
            }
            Set<String> overlap = new HashSet<>(seenCidrs);
            overlap.retainAll(gCidrs);
            if (!overlap.isEmpty()) {
                return dict("status", "ambiguous", "ambiguous", true, "confirmed", false,
                        "reason", "Disconnected capture groups share client CIDR(s) "
                                + Py.repr(new ArrayList<Object>(Py.sortedStr(overlap)))
                                + "; path order between them is ambiguous.",
                        "topology", base.dump(), "inferences", gRes.getOrDefault("inferences", new ArrayList<>()),
                        "warnings", combined);
            }
            seenCidrs.addAll(gCidrs);
            String groupName;
            if (!gCidrs.isEmpty()) {
                groupName = String.join(", ", gCidrs);
            } else {
                List<String> names = new ArrayList<>();
                for (String cid : gCids) {
                    if (!hosts.contains(cid)) {
                        names.add(name(byCid.get(cid)));
                    }
                }
                groupName = String.join(", ", names);
            }
            boolean amb = Py.truthy(gRes.get("ambiguous"));
            groups.add(dict("name", groupName, "client_cidrs", new ArrayList<Object>(gCidrs),
                    "capture_ids", new ArrayList<Object>(gCids), "status", gRes.get("status"), "ambiguous", amb,
                    "confirmed", false,
                    "badge", amb ? "Suggested topology ambiguous — manual input required"
                            : "Suggested topology — review & confirm",
                    "reason", gRes.get("reason"), "topology", gTopo,
                    "inferences", gRes.getOrDefault("inferences", new ArrayList<>()),
                    "warnings", gRes.getOrDefault("warnings", new ArrayList<>())));
        }
        groups.sort((a, b) -> Py.compareStr((String) a.get("name"), (String) b.get("name")));
        List<Map<String, Object>> ready = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            if (!Py.truthy(g.get("ambiguous"))) {
                ready.add(g);
            }
        }
        if (ready.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map<String, Object> g : groups) {
                parts.add(g.get("name") + ": " + Py.str(g.get("reason")));
            }
            return dict("status", "ambiguous", "ambiguous", true, "confirmed", false, "multi_path", true,
                    "reason", String.join("; ", parts), "topology", base.dump(), "inferences", new ArrayList<>(),
                    "warnings", combined, "groups", new ArrayList<Object>(groups));
        }
        List<String> readyNames = new ArrayList<>();
        for (Map<String, Object> g : ready) {
            readyNames.add((String) g.get("name"));
        }
        return dict("status", "ready", "ambiguous", false, "confirmed", false, "multi_path", true,
                "badge", "Suggested topology — review & confirm",
                "reason", "Assembled " + ready.size() + " of " + groups.size() + " independent client paths ("
                        + String.join(", ", readyNames) + ") sharing host capture.",
                "topology", ready.get(0).get("topology"), "inferences", ready.get(0).get("inferences"),
                "warnings", combined, "groups", new ArrayList<Object>(groups));
    }
}
