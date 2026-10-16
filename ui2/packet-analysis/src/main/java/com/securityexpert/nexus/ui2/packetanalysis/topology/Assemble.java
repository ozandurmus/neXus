package com.securityexpert.nexus.ui2.packetanalysis.topology;

import static com.securityexpert.nexus.ui2.packetanalysis.topology.Py.dict;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single- and multi-path topology assembly, the analysis hand-off and confirmation ({@code topology/assemble.py}).
 *
 * <p>Results are the reference dicts ({@code status}, {@code ambiguous}, {@code confirmed}, {@code badge},
 * {@code reason}, {@code topology}, {@code inferences}, {@code warnings}, and {@code multi_path}/{@code groups} for
 * several client paths), in the reference key order.
 */
public final class Assemble {

    static final String BADGE_READY = "Suggested topology — review & confirm";

    /** The core analysis the assembled topology is handed to; returns the report dict. */
    @FunctionalInterface
    public interface Analyzer {
        Map<String, Object> analyze(ProjectDb project, Object topology);
    }

    private Assemble() {
    }

    private static Map<String, Object> ambiguous(Topology base, String reason, List<Object> warnings,
            List<Object> inferences) {
        return dict("status", "ambiguous", "ambiguous", true, "confirmed", false, "reason", reason,
                "topology", base.dump(), "inferences", inferences == null ? new ArrayList<>() : new ArrayList<>(inferences),
                "warnings", warnings);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> raw) {
        return (List<Map<String, Object>>) (List<?>) raw.getOrDefault("items", List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<String> captureIds(Map<String, Object> item) {
        return (List<String>) (List<?>) item.getOrDefault("capture_ids", List.of());
    }

    private record Classified(List<String> clientHosts, List<String> serverHosts, Map<String, String> clockDomainOf,
            List<List<String>> units, String error) {
    }

    /** {@code _classify_units_and_clocks}: host captures, stage-pair units, shared clock domains, single units. */
    private static Classified classifyUnitsAndClocks(Map<String, Object> raw, List<String> readyIds,
            Map<String, Map<String, Object>> ttlByCid, Map<String, Point> pointsByCid, Map<String, String> names,
            List<Object> inferences) {
        Set<String> hostCids = new HashSet<>();
        List<String> clientHosts = new ArrayList<>();
        List<String> serverHosts = new ArrayList<>();
        for (Map<String, Object> item : items(raw)) {
            if (!"host_capture".equals(item.get("kind")) || !pointsByCid.containsKey(captureIds(item).get(0))) {
                continue;
            }
            String cid = captureIds(item).get(0);
            hostCids.add(cid);
            Map<String, Object> tinfo = ttlByCid.get(cid);
            Point pt = pointsByCid.get(cid);
            pt.hostCapture = true;
            pt.stageCapture = false;
            pt.side = "both";
            if (Py.gt(Py.or(tinfo.get("fwd_super"), Py.i(0)), Py.or(tinfo.get("rev_super"), Py.i(0)))) {
                pt.kind = "Client";
                clientHosts.add(cid);
            } else {
                pt.kind = "Server";
                serverHosts.add(cid);
            }
            inferences.add(item);
        }
        if (clientHosts.size() > 1 || serverHosts.size() > 1) {
            return new Classified(null, null, null, null,
                    "Multiple host offload captures detected on the same endpoint side; assign host order manually.");
        }
        Set<String> stageUsed = new HashSet<>();
        List<List<String>> stageUnits = new ArrayList<>();
        for (Map<String, Object> item : items(raw)) {
            if (!"stage_pair".equals(item.get("kind")) || !wireOnly(captureIds(item), pointsByCid, hostCids)) {
                continue;
            }
            String rx = captureIds(item).get(0);
            String tx = captureIds(item).get(1);
            if (stageUsed.contains(rx) || stageUsed.contains(tx)) {
                continue;
            }
            stageUsed.add(rx);
            stageUsed.add(tx);
            stageUnits.add(List.of(rx, tx));
            String dev = Suggest.deviceStem(names.get(rx), names.get(tx));
            for (String[] cs : new String[][] {{rx, "ingress"}, {tx, "egress"}}) {
                Point pt = pointsByCid.get(cs[0]);
                pt.stageCapture = true;
                pt.side = cs[1];
                pt.device = dev;
                pt.kind = "Firewall";
            }
            inferences.add(item);
        }
        Map<String, Set<String>> adj = new HashMap<>();
        for (Map<String, Object> item : items(raw)) {
            if (!"shared_clock".equals(item.get("kind")) || !wireOnly(captureIds(item), pointsByCid, hostCids)) {
                continue;
            }
            String a = captureIds(item).get(0);
            String b = captureIds(item).get(1);
            adj.computeIfAbsent(a, k -> new HashSet<>()).add(b);
            adj.computeIfAbsent(b, k -> new HashSet<>()).add(a);
            inferences.add(item);
        }
        Set<String> visited = new HashSet<>();
        List<Set<String>> components = new ArrayList<>();
        for (String cid : readyIds) {
            if (adj.containsKey(cid) && !visited.contains(cid)) {
                Set<String> comp = Groups.component(cid, adj, visited);
                if (comp.size() >= 2) {
                    components.add(comp);
                }
            }
        }
        components.sort((x, y) -> Py.compareStr(Py.sortedStr(x).get(0), Py.sortedStr(y).get(0)));
        Map<String, String> clockDomainOf = new LinkedHashMap<>();
        for (int idx = 0; idx < components.size(); idx++) {
            String domain = components.size() == 1 ? "appliance" : "appliance_" + (idx + 1);
            for (String cid : components.get(idx)) {
                pointsByCid.get(cid).clockDomain = domain;
                clockDomainOf.put(cid, domain);
            }
        }
        for (String cid : hostCids) {
            pointsByCid.get(cid).clockDomain = null;
        }
        List<List<String>> units = new ArrayList<>(stageUnits);
        for (String cid : readyIds) {
            if (!hostCids.contains(cid) && !stageUsed.contains(cid)) {
                Point pt = pointsByCid.get(cid);
                pt.stageCapture = false;
                pt.side = "both";
                units.add(List.of(cid));
            }
        }
        return new Classified(clientHosts, serverHosts, clockDomainOf, units, null);
    }

    private static boolean wireOnly(List<String> cids, Map<String, Point> pointsByCid, Set<String> hostCids) {
        for (String cid : cids) {
            if (!pointsByCid.containsKey(cid) || hostCids.contains(cid)) {
                return false;
            }
        }
        return true;
    }

    /** {@code _assemble_single_topology(db, base_model, captures, raw_suggestions, all_captures)}. */
    static Map<String, Object> assembleSingleTopology(ProjectDb.Db db, Topology base,
            List<Map<String, Object>> captures, Map<String, Object> raw, List<Map<String, Object>> allCaptures) {
        List<String> readyIds = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> c : captures) {
            readyIds.add(Groups.id(c));
            names.put(Groups.id(c), Groups.name(c));
        }
        Set<String> readySet = new HashSet<>(readyIds);
        List<Object> warnings = new ArrayList<>();
        for (Map<String, Object> s : items(raw)) {
            if (Py.truthy(s.get("warning")) && readySet.containsAll(captureIds(s))) {
                warnings.add(s.get("warning"));
            }
        }
        List<Object> inferences = new ArrayList<>();
        if (captures.size() < 2) {
            return ambiguous(base, "At least two ready captures are required to assemble a path.", warnings, null);
        }
        Map<String, Point> allPoints = Groups.defaultPoints(
                allCaptures == null || allCaptures.isEmpty() ? captures : allCaptures, base.points);
        Map<String, Point> pointsByCid = new LinkedHashMap<>();
        for (String cid : readyIds) {
            pointsByCid.put(cid, allPoints.get(cid).copy());
        }
        Suggest.CidrResult cidr = Suggest.inferClientCidrs(db, names, readyIds);
        if (cidr.error() != null || cidr.cidrs().isEmpty()) {
            String excl = Groups.captureExclusionReason(db, captures, readyIds);
            String reason = excl != null ? excl : cidr.error() != null ? cidr.error()
                    : "Client direction could not be inferred unambiguously.";
            return ambiguous(base, reason, warnings, null);
        }
        inferences.add(cidr.inference());
        base = base.copy();
        base.clientCidrs = new ArrayList<>(cidr.cidrs());
        List<String> clientIps = new ArrayList<>();
        for (String c : cidr.cidrs()) {
            clientIps.add(c.split("/")[0]);
        }
        Order.Stats stats = Order.queryTtlAndPairStats(db, readyIds, clientIps);
        Map<String, Map<String, Object>> ttl = stats.ttlByCid();
        List<String> missing = new ArrayList<>();
        for (String cid : readyIds) {
            if (!ttl.containsKey(cid) || (Py.intOr0(ttl.get(cid).get("fwd_n")) == 0
                    && Py.intOr0(ttl.get(cid).get("rev_n")) == 0)) {
                missing.add(cid);
            }
        }
        if (!missing.isEmpty()) {
            String excl = Groups.captureExclusionReason(db, captures, missing);
            List<String> missingNames = new ArrayList<>();
            for (String cid : missing) {
                missingNames.add(names.get(cid));
            }
            String msg = excl != null ? excl : "Capture(s) " + String.join(", ", missingNames)
                    + " share no directional TCP traffic with the rest of the folder.";
            return ambiguous(base, msg, warnings, inferences);
        }
        Classified cl = classifyUnitsAndClocks(raw, readyIds, ttl, pointsByCid, names, inferences);
        if (cl.error() != null) {
            return ambiguous(base, cl.error(), warnings, inferences);
        }
        List<List<String>> units = cl.units();
        Map<Integer, Integer> wins = new LinkedHashMap<>();
        for (int i = 0; i < units.size(); i++) {
            wins.put(i, 0);
        }
        Map<Order.UnitPair, List<Map<String, Object>>> hopRefs = new HashMap<>();
        List<Order.UnitPair> conflicting = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            for (int j = i + 1; j < units.size(); j++) {
                Order.Comparison cmp = Order.compareUnits(units.get(i), units.get(j), ttl, cl.clockDomainOf(),
                        stats.pairStats(), names);
                if (cmp.order() == 0) {
                    return ambiguous(base, cmp.error() != null ? cmp.error()
                            : "Path order between captures is ambiguous.", warnings, inferences);
                }
                if (cmp.usConflict()) {
                    conflicting.add(new Order.UnitPair(i, j));
                }
                if (cmp.order() < 0) {
                    wins.merge(i, 1, Integer::sum);
                    hopRefs.put(new Order.UnitPair(i, j), cmp.refs());
                } else {
                    wins.merge(j, 1, Integer::sum);
                    List<Map<String, Object>> rev = new ArrayList<>(cmp.refs());
                    java.util.Collections.reverse(rev);
                    hopRefs.put(new Order.UnitPair(j, i), rev);
                }
            }
        }
        if (new HashSet<>(wins.values()).size() != units.size()) {
            return ambiguous(base, "Pairwise TTL/timestamp comparisons do not form a consistent linear path order.",
                    warnings, inferences);
        }
        return finalizeOrderedTopology(base, units, wins, hopRefs, conflicting, cl, ttl, stats.pairStats(),
                pointsByCid, allPoints, names, cidr.cidrs(), inferences, warnings);
    }

    private static Map<String, Object> finalizeOrderedTopology(Topology base, List<List<String>> units,
            Map<Integer, Integer> wins, Map<Order.UnitPair, List<Map<String, Object>>> hopRefs,
            List<Order.UnitPair> conflicting, Classified cl, Map<String, Map<String, Object>> ttl,
            Map<Order.PairKey, Map<String, Object>> pairStats, Map<String, Point> pointsByCid,
            Map<String, Point> allPoints, Map<String, String> names, List<String> clientCidrs,
            List<Object> inferences, List<Object> warnings) {
        List<Integer> orderedIndices = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            orderedIndices.add(i);
        }
        orderedIndices.sort((x, y) -> Integer.compare(-wins.get(x), -wins.get(y)));
        List<List<String>> orderedUnits = new ArrayList<>();
        for (int idx : orderedIndices) {
            orderedUnits.add(units.get(idx));
        }
        inferences = Order.isolateConflictingClocks(units, conflicting, wins, cl.clockDomainOf(), pointsByCid, names,
                warnings, inferences);

        if (!orderedUnits.isEmpty() && !cl.serverHosts().isEmpty()) {
            String hCid = cl.serverHosts().get(0);
            List<String> lastUnit = orderedUnits.get(orderedUnits.size() - 1);
            String lastWire = lastUnit.get(lastUnit.size() - 1);
            Object hf = ttl.get(hCid).get("fwd_ttl");
            Object wf = ttl.get(lastWire).get("fwd_ttl");
            if (hf != null && wf != null && Py.gt(hf, wf)) {
                return dict("status", "ambiguous", "ambiguous", true, "confirmed", false,
                        "reason", "Host capture " + names.get(hCid) + " has higher forward TTL (" + Py.str(hf)
                                + ") than wire capture " + names.get(lastWire) + " (" + Py.str(wf) + ").",
                        "topology", base.dump(), "inferences", inferences, "warnings", warnings);
            }
        }
        List<String> forwardCids = new ArrayList<>(cl.clientHosts());
        for (List<String> u : orderedUnits) {
            forwardCids.addAll(u);
        }
        forwardCids.addAll(cl.serverHosts());
        List<String> reverseCids = new ArrayList<>(Topology.reversed(cl.serverHosts()));
        for (int k = orderedUnits.size() - 1; k >= 0; k--) {
            reverseCids.addAll(orderedUnits.get(k));
        }
        reverseCids.addAll(Topology.reversed(cl.clientHosts()));
        Order.checkAdjacentMismatchWarnings(orderedIndices, units, pairStats, names, warnings);

        List<Object> pathEvidence = new ArrayList<>();
        for (int k = 0; k + 1 < orderedIndices.size(); k++) {
            for (Map<String, Object> ref : hopRefs.getOrDefault(
                    new Order.UnitPair(orderedIndices.get(k), orderedIndices.get(k + 1)), List.of())) {
                if (!Py.contains(pathEvidence, ref)) {
                    pathEvidence.add(ref);
                }
            }
        }
        if (pathEvidence.isEmpty()) {
            for (String cid : forwardCids.subList(0, Math.min(2, forwardCids.size()))) {
                Object fr = Py.or(ttl.get(cid).get("fwd_frame"), ttl.get(cid).get("rev_frame"));
                if (Py.truthy(fr)) {
                    pathEvidence.add(Suggest.ref(cid, names, fr));
                }
            }
        }
        List<Point> orderedPoints = new ArrayList<>();
        for (int pos = 0; pos < forwardCids.size(); pos++) {
            Point pt = pointsByCid.get(forwardCids.get(pos));
            pt.x = PyNum.of(pos * 220L);
            pt.y = PyNum.of(120L);
            orderedPoints.add(pt);
        }
        Set<String> forwardSet = new LinkedHashSet<>(forwardCids);
        for (Map.Entry<String, Point> e : allPoints.entrySet()) {
            if (!forwardSet.contains(e.getKey())) {
                orderedPoints.add(e.getValue().copy());
            }
        }
        List<Object> forwardIds = new ArrayList<>();
        List<Object> forwardLabels = new ArrayList<>();
        Map<String, Object> forwardTtls = new LinkedHashMap<>();
        Map<String, Object> reverseTtls = new LinkedHashMap<>();
        for (String cid : forwardCids) {
            Point p = pointsByCid.get(cid);
            forwardIds.add(p.id);
            forwardLabels.add(p.label);
            forwardTtls.put(p.label, ttl.get(cid).get("fwd_ttl"));
        }
        for (String cid : forwardCids) {
            reverseTtls.put(pointsByCid.get(cid).label, ttl.get(cid).get("rev_ttl"));
        }
        List<Object> reverseIds = new ArrayList<>();
        List<Object> reverseLabels = new ArrayList<>();
        for (String cid : reverseCids) {
            reverseIds.add(pointsByCid.get(cid).id);
            reverseLabels.add(pointsByCid.get(cid).label);
        }
        inferences.add(dict("kind", "path_order", "capture_ids", new ArrayList<Object>(forwardCids),
                "forward", forwardIds, "reverse", reverseIds,
                "metrics", dict("forward_labels", forwardLabels, "reverse_labels", reverseLabels,
                        "forward_ttls", forwardTtls, "reverse_ttls", reverseTtls),
                "evidence", new ArrayList<>(pathEvidence.subList(0, Math.min(8, pathEvidence.size()))),
                "reason", "Forward and reverse path order assembled from TTL steps, microsecond transit deltas and "
                        + "host offload boundary."));
        Topology assembled = base.copy();
        assembled.points = orderedPoints;
        assembled.forward = new ArrayList<>();
        for (Object id : forwardIds) {
            assembled.forward.add((String) id);
        }
        assembled.reverse = new ArrayList<>();
        for (Object id : reverseIds) {
            assembled.reverse.add((String) id);
        }
        assembled.clientCidrs = new ArrayList<>(clientCidrs);
        return dict("status", "ready", "ambiguous", false, "confirmed", false, "badge", BADGE_READY,
                "reason", "Suggested topology assembled from stage pairs, shared clock domains, TTL steps, "
                        + "microsecond ordering and host offload evidence.",
                "topology", assembled.dump(), "inferences", inferences, "warnings", warnings);
    }

    /**
     * {@code assemble_topology(project, base_topology)}: {@code base} is a {@link Topology} (deep-copied), a dict
     * (validated), or null for the saved topology.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> assembleTopology(ProjectDb project, Object base) {
        Map<String, Object> raw = Suggest.suggestTopology(project);
        List<Object> warnings = new ArrayList<>((List<Object>) raw.getOrDefault("warnings", List.of()));
        List<Map<String, Object>> allCaptures = project.inventory();
        List<Map<String, Object>> captures = new ArrayList<>();
        for (Map<String, Object> c : allCaptures) {
            if ("ready".equals(c.get("state"))) {
                captures.add(c);
            }
        }
        try (ProjectDb.Db db = project.connect()) {
            Topology baseModel;
            if (base == null) {
                Object saved = db.get("topology", null);
                baseModel = Py.truthy(saved) ? Topology.validate(saved) : new Topology();
            } else if (base instanceof Topology t) {
                baseModel = t.copy();
            } else {
                baseModel = Topology.validate(base);
            }
            String early = Groups.captureExclusionReason(db, allCaptures, null);
            if (early != null) {
                return dict("status", "ambiguous", "ambiguous", true, "confirmed", false, "reason", early,
                        "topology", baseModel.dump(), "inferences", new ArrayList<>(), "warnings", warnings);
            }
            List<List<String>> groups = Groups.partitionClientGroups(db, captures, raw);
            if (groups != null && groups.size() >= 2) {
                return Groups.assembleMultiPathGroups(db, baseModel, captures, raw, groups, warnings,
                        Assemble::assembleSingleTopology);
            }
            Map<String, Object> res = assembleSingleTopology(db, baseModel, captures, raw, captures);
            List<Object> resWarnings = (List<Object>) res.get("warnings");
            for (Object w : warnings) {
                if (!resWarnings.contains(w)) {
                    resWarnings.add(0, w);
                }
            }
            return res;
        }
    }

    /**
     * {@code assemble_and_analyze(project, base_topology)}: assembles, stores the suggestion and hands every ready
     * path to {@code analyzer}; returns {@code suggested_topology}, {@code report} (and {@code groups}).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> assembleAndAnalyze(ProjectDb project, Object base, Analyzer analyzer) {
        try (ProjectDb.Db db = project.connect()) {
            Object current = db.get("topology", null);
            if (Py.truthy(current) && !Py.truthy(db.get("unconfirmed_topology", null))) {
                db.set("unconfirmed_topology", current);
            }
        }
        Map<String, Object> assembly = assembleTopology(project, base);
        try (ProjectDb.Db db = project.connect()) {
            db.set("suggested_topology", assembly);
        }
        if (Py.truthy(assembly.get("ambiguous"))) {
            return dict("suggested_topology", assembly, "report", null);
        }
        List<Map<String, Object>> groups = (List<Map<String, Object>>) assembly.get("groups");
        if (Py.truthy(groups) && groups.size() >= 2) {
            for (int k = groups.size() - 1; k >= 0; k--) {
                Map<String, Object> g = groups.get(k);
                if (Py.truthy(g.get("ambiguous"))) {
                    g.put("report", null);
                    continue;
                }
                Map<String, Object> gReport = analyzer.analyze(project, g.get("topology"));
                Map<String, Object> gMeta = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : g.entrySet()) {
                    if (!e.getKey().equals("report")) {
                        gMeta.put(e.getKey(), e.getValue());
                    }
                }
                gReport.put("suggested_topology", gMeta);
                g.put("report", gReport);
            }
            List<Map<String, Object>> ready = new ArrayList<>();
            for (Map<String, Object> g : groups) {
                if (!Py.truthy(g.get("ambiguous")) && Py.truthy(g.get("report"))) {
                    ready.add(g);
                }
            }
            Map<String, Object> groupReports = new LinkedHashMap<>();
            for (Map<String, Object> g : ready) {
                groupReports.put((String) g.get("name"), new LinkedHashMap<>((Map<String, Object>) g.get("report")));
            }
            Map<String, Object> primary = new LinkedHashMap<>((Map<String, Object>) ready.get(0).get("report"));
            primary.put("suggested_topology", assembly);
            primary.put("group_reports", groupReports);
            try (ProjectDb.Db db = project.connect()) {
                db.set("topology", ready.get(0).get("topology"));
                db.set("report", primary);
                db.set("suggested_topology", assembly);
            }
            return dict("suggested_topology", assembly, "report", primary, "groups", groups);
        }
        Map<String, Object> report = analyzer.analyze(project, assembly.get("topology"));
        report.put("suggested_topology", assembly);
        try (ProjectDb.Db db = project.connect()) {
            db.set("report", report);
            db.set("suggested_topology", assembly);
        }
        return dict("suggested_topology", assembly, "report", report);
    }

    /** {@code confirm_suggested_topology(project, group)}; IllegalArgumentException is the reference ValueError. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> confirmSuggestedTopology(ProjectDb project, String group) {
        try (ProjectDb.Db db = project.connect()) {
            Map<String, Object> suggested = (Map<String, Object>) db.get("suggested_topology", null);
            if (!Py.truthy(suggested) || Py.truthy(suggested.get("ambiguous"))) {
                throw new IllegalArgumentException("No assembled suggested topology is pending confirmation");
            }
            if (Py.truthy(suggested.get("groups"))) {
                List<Map<String, Object>> groups = new ArrayList<>();
                for (Object g : (List<Object>) suggested.get("groups")) {
                    groups.add(new LinkedHashMap<>((Map<String, Object>) g));
                }
                Set<String> targets = new HashSet<>();
                if (group != null) {
                    Map<String, Object> matched = null;
                    for (Map<String, Object> g : groups) {
                        if (group.equals(g.get("name"))) {
                            matched = matched == null ? g : matched;
                        }
                    }
                    if (matched == null) {
                        throw new IllegalArgumentException("Unknown suggested topology group: " + group);
                    }
                    if (Py.truthy(matched.get("ambiguous"))) {
                        throw new IllegalArgumentException("Suggested topology group " + group + " is ambiguous");
                    }
                    targets.add(group);
                } else {
                    for (Map<String, Object> g : groups) {
                        if (!Py.truthy(g.get("ambiguous"))) {
                            targets.add((String) g.get("name"));
                        }
                    }
                }
                for (Map<String, Object> g : groups) {
                    if (targets.contains(g.get("name"))) {
                        g.put("confirmed", true);
                        Object rep = g.get("report");
                        if (Py.truthy(rep) && ((Map<String, Object>) rep).get("suggested_topology") instanceof Map) {
                            Map<String, Object> r = new LinkedHashMap<>((Map<String, Object>) rep);
                            Map<String, Object> st = new LinkedHashMap<>(
                                    (Map<String, Object>) r.get("suggested_topology"));
                            st.put("confirmed", true);
                            r.put("suggested_topology", st);
                            g.put("report", r);
                        }
                    }
                }
                boolean anyReady = false;
                boolean allConfirmed = true;
                for (Map<String, Object> g : groups) {
                    if (!Py.truthy(g.get("ambiguous"))) {
                        anyReady = true;
                        allConfirmed &= Py.truthy(g.get("confirmed"));
                    }
                }
                suggested = new LinkedHashMap<>(suggested);
                suggested.put("groups", new ArrayList<Object>(groups));
                suggested.put("confirmed", anyReady && allConfirmed);
                if (group != null) {
                    Map<String, Object> chosen = null;
                    for (Map<String, Object> g : groups) {
                        if (chosen == null && group.equals(g.get("name"))) {
                            chosen = g;
                        }
                    }
                    suggested.put("topology", chosen.get("topology"));
                    suggested.put("inferences", chosen.get("inferences"));
                    db.set("topology", chosen.get("topology"));
                    if (Py.truthy(chosen.get("report"))) {
                        Map<String, Object> rep = new LinkedHashMap<>((Map<String, Object>) chosen.get("report"));
                        rep.put("suggested_topology", suggested);
                        rep.put("group_reports", groupReports(groups));
                        db.set("report", rep);
                    }
                }
            } else {
                suggested = new LinkedHashMap<>(suggested);
                suggested.put("confirmed", true);
            }
            db.set("suggested_topology", suggested);
            if (Py.truthy(suggested.get("confirmed"))) {
                db.set("unconfirmed_topology", null);
            }
            Object report = db.get("report", null);
            if (Py.truthy(report)) {
                Map<String, Object> rep = new LinkedHashMap<>((Map<String, Object>) report);
                rep.put("suggested_topology", suggested);
                if (Py.truthy(suggested.get("groups"))) {
                    List<Map<String, Object>> gs = (List<Map<String, Object>>) (List<?>) suggested.get("groups");
                    rep.put("group_reports", groupReports(gs));
                }
                db.set("report", rep);
            }
            return suggested;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> groupReports(List<Map<String, Object>> groups) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> g : groups) {
            if (Py.truthy(g.get("report"))) {
                out.put((String) g.get("name"), new LinkedHashMap<>((Map<String, Object>) g.get("report")));
            }
        }
        return out;
    }
}
