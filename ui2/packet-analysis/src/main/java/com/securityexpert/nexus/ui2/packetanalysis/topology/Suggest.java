package com.securityexpert.nexus.ui2.packetanalysis.topology;

import static com.securityexpert.nexus.ui2.packetanalysis.topology.Py.dict;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stage-pair, shared-clock, host-capture and client-CIDR suggestion ({@code topology/suggest.py}).
 */
public final class Suggest {

    static final Set<String> INGRESS_TOKENS = Set.of("in", "rx", "ingress", "inside", "recv", "receive");
    static final Set<String> EGRESS_TOKENS = Set.of("out", "tx", "egress", "outside", "send", "transmit");
    private static final Pattern LOWER_SPLIT = Pattern.compile("[^a-z0-9]+");
    private static final Pattern ANY_SPLIT = Pattern.compile("[^A-Za-z0-9]+");
    private static final Pattern CASE_TOKEN = Pattern.compile("case\\d*");

    private Suggest() {
    }

    /** {@code Path(name).stem}. */
    static String stem(String path) {
        String name = PyPaths.name(path);
        int i = name.lastIndexOf('.');
        return (0 < i && i < name.length() - 1) ? name.substring(0, i) : name;
    }

    private static List<String> tokens(Pattern split, String s) {
        List<String> out = new ArrayList<>();
        for (String t : split.split(s, -1)) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** {@code _name_direction}: ingress/egress from file-name tokens, or null. */
    static String nameDirection(String name) {
        List<String> tokens = tokens(LOWER_SPLIT, stem(name).toLowerCase(Locale.ROOT));
        boolean hasIn = tokens.stream().anyMatch(
                t -> INGRESS_TOKENS.contains(t) || (t.length() > 2 && (t.endsWith("rx") || t.endsWith("ingress"))));
        boolean hasOut = tokens.stream().anyMatch(
                t -> EGRESS_TOKENS.contains(t) || (t.length() > 2 && (t.endsWith("tx") || t.endsWith("egress"))));
        if (hasIn && !hasOut) {
            return "ingress";
        }
        if (hasOut && !hasIn) {
            return "egress";
        }
        return null;
    }

    /** {@code _device_stem}: the common leading name tokens of a stage pair, direction words removed. */
    static String deviceStem(String nameA, String nameB) {
        List<String> ta = cleanTokens(nameA);
        List<String> tb = cleanTokens(nameB);
        List<String> common = new ArrayList<>();
        for (int i = 0; i < Math.min(ta.size(), tb.size()); i++) {
            if (ta.get(i).toLowerCase(Locale.ROOT).equals(tb.get(i).toLowerCase(Locale.ROOT))) {
                common.add(ta.get(i));
            } else {
                break;
            }
        }
        String base;
        if (!common.isEmpty()) {
            base = String.join("_", common);
        } else {
            String joined = String.join("_", ta);
            base = joined.isEmpty() ? stem(nameA) : joined;
        }
        return Py.len(base) == 1 ? base.toUpperCase(Locale.ROOT) : base;
    }

    private static List<String> cleanTokens(String name) {
        List<String> out = new ArrayList<>();
        for (String t : tokens(ANY_SPLIT, stem(name))) {
            String lower = t.toLowerCase(Locale.ROOT);
            if (!INGRESS_TOKENS.contains(lower) && !EGRESS_TOKENS.contains(lower)
                    && !CASE_TOKEN.matcher(lower).matches()) {
                out.add(t);
            }
        }
        return out;
    }

    /** {@code _mismatch_warning}: names say the opposite direction of what the packets show. */
    static String mismatchWarning(String ingressObserved, String egressObserved) {
        String dirIn = nameDirection(ingressObserved);
        String dirOut = nameDirection(egressObserved);
        if ("ingress".equals(dirOut) && !"ingress".equals(dirIn)) {
            return "File names suggest " + egressObserved + " -> " + ingressObserved + ", packets show "
                    + ingressObserved + " -> " + egressObserved + " (names may describe interfaces, not direction)";
        }
        return null;
    }

    static Map<String, Object> ref(String captureId, Map<String, String> names, Object frame) {
        return dict("capture_id", captureId, "file", names.get(captureId), "frame", frame,
                "display_filter", "frame.number == " + Py.str(frame));
    }

    static Map<String, String> captureNames(ProjectDb.Db db) {
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> r : db.rows("SELECT id,name FROM captures", List.of())) {
            names.put((String) r.get("id"), (String) r.get("name"));
        }
        return names;
    }

    /** {@code suggest_topology(project)}. */
    public static Map<String, Object> suggestTopology(ProjectDb project) {
        try (ProjectDb.Db db = project.connect()) {
            Map<String, String> names = captureNames(db);
            List<Map<String, Object>> pairs = db.rows(ProjectDb.sql("suggest_stage_pairs", Map.of()), List.of());
            List<Object> items = new ArrayList<>();
            List<Object> warnings = new ArrayList<>();
            for (Map<String, Object> p : pairs) {
                List<Object> refs = new ArrayList<>();
                for (String k : List.of("a", "b")) {
                    refs.add(ref((String) p.get(k), names, p.get("frame_" + k)));
                }
                items.add(dict("kind", "shared_clock", "capture_ids", new ArrayList<Object>(List.of(p.get("a"), p.get("b"))),
                        "metrics", p, "evidence", refs,
                        "reason", "Consistent microsecond-scale deltas in both tuple directions; small clock offset "
                                + "remains indistinguishable from transit. Confirm the source clock."));
                List<?> deltas = (List<?>) p.get("deltas_us");
                Object ttlSteps = p.get("ttl_steps");
                int sign;
                if (Py.ge(min(deltas), Py.i(0)) && Py.eq(ttlSteps, List.of(Py.i(1), Py.i(1)))) {
                    sign = 1;
                } else if (Py.le(max(deltas), Py.i(0)) && Py.eq(ttlSteps, List.of(Py.i(-1), Py.i(-1)))) {
                    sign = -1;
                } else {
                    sign = 0;
                }
                if (sign != 0) {
                    String a = (String) p.get("a");
                    String b = (String) p.get("b");
                    List<Object> ids = sign > 0 ? new ArrayList<>(List.of(a, b)) : new ArrayList<>(List.of(b, a));
                    List<Object> orderedRefs = new ArrayList<>(refs);
                    if (sign < 0) {
                        java.util.Collections.reverse(orderedRefs);
                    }
                    String warn = mismatchWarning(names.get((String) ids.get(0)), names.get((String) ids.get(1)));
                    if (warn != null && !warnings.contains(warn)) {
                        warnings.add(warn);
                    }
                    Map<String, Object> stageItem = dict("kind", "stage_pair", "capture_ids", ids, "metrics", p,
                            "evidence", orderedRefs,
                            "reason", "Same rx→tx order in both directions, one TTL step and microsecond-scale "
                                    + "transit. Confirm one device.");
                    if (warn != null) {
                        stageItem.put("warning", warn);
                    }
                    items.add(stageItem);
                }
            }
            for (Map<String, Object> h : db.rows(ProjectDb.sql("suggest_host_captures", Map.of()), List.of())) {
                String cid = (String) h.get("capture_id");
                items.add(dict("kind", "host_capture", "capture_ids", new ArrayList<Object>(List.of(cid)),
                        "metrics", h, "evidence", new ArrayList<Object>(List.of(ref(cid, names, h.get("frame")))),
                        "reason", "TCP super-frames and a 64/128 source TTL suggest host offload. Virtual/middlebox "
                                + "captures can look similar; confirmation required."));
            }
            return dict("items", items, "warnings", warnings, "requires_confirmation", true,
                    "note", "Suggestions do not alter the saved topology. NAT/translation without shared unique "
                            + "packet identities remains unknown.");
        }
    }

    private static Object min(List<?> values) {
        Object best = values.get(0);
        for (Object v : values.subList(1, values.size())) {
            if (Py.lt(v, best)) {
                best = v;
            }
        }
        return best;
    }

    private static Object max(List<?> values) {
        Object best = values.get(0);
        for (Object v : values.subList(1, values.size())) {
            if (Py.gt(v, best)) {
                best = v;
            }
        }
        return best;
    }

    /** {@code _infer_client_cidrs} result: CIDRs, the inference item, or an error reason. */
    record CidrResult(List<String> cidrs, Map<String, Object> inference, String error) {
    }

    private static String hostCidr(String ip) {
        return ip.contains(":") ? ip + "/128" : ip + "/32";
    }

    private static CidrResult inferFromSyn(ProjectDb.Db db, Map<String, String> names, List<String> readyIds,
            String placeholders) {
        List<Object> params = new ArrayList<>(readyIds);
        params.addAll(readyIds);
        List<Map<String, Object>> synRows = db.rows(
                ProjectDb.sql("cidrs_syn_direction", Map.of("placeholders", placeholders)), params);
        if (synRows.isEmpty()) {
            return null;
        }
        Set<String> clientIps = new LinkedHashSet<>();
        Set<String> serverIps = new LinkedHashSet<>();
        for (Map<String, Object> r : synRows) {
            if ((Py.toInt(r.get("flags")) & 18) == 2) {
                clientIps.add((String) r.get("src"));
                serverIps.add((String) r.get("dst"));
            } else {
                clientIps.add((String) r.get("dst"));
                serverIps.add((String) r.get("src"));
            }
        }
        if (clientIps.isEmpty() || intersects(clientIps, serverIps)) {
            return null;
        }
        List<String> cidrs = new ArrayList<>();
        for (String ip : Py.sortedStr(clientIps)) {
            cidrs.add(hostCidr(ip));
        }
        Map<String, Object> first = synRows.get(0);
        String firstCid = (String) first.get("capture_id");
        Map<String, Object> inference = dict("kind", "client_cidrs", "method", "syn_direction",
                "capture_ids", new ArrayList<Object>(List.of(firstCid)), "client_cidrs", new ArrayList<Object>(cidrs),
                "metrics", dict("method", "syn_direction",
                        "client_ips", new ArrayList<Object>(Py.sortedStr(clientIps)),
                        "server_ips", new ArrayList<Object>(Py.sortedStr(serverIps)),
                        "handshake_observations", Py.i(synRows.size())),
                "evidence", new ArrayList<Object>(List.of(ref(firstCid, names, first.get("frame")))),
                "reason", "Client CIDR(s) " + String.join(", ", cidrs) + " inferred from TCP SYN/SYN-ACK direction.");
        return new CidrResult(cidrs, inference, null);
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String x : a) {
            if (b.contains(x)) {
                return true;
            }
        }
        return false;
    }

    private record Endpoint(String ip, long port) {
    }

    /** {@code _infer_client_cidrs(db, names, ready_ids)}. */
    static CidrResult inferClientCidrs(ProjectDb.Db db, Map<String, String> names, List<String> readyIds) {
        String placeholders = ProjectDb.placeholders(readyIds.size());
        CidrResult syn = inferFromSyn(db, names, readyIds, placeholders);
        if (syn != null) {
            return syn;
        }
        List<Object> params = new ArrayList<>(readyIds);
        params.addAll(readyIds);
        List<Map<String, Object>> flowRows = db.rows(
                ProjectDb.sql("cidrs_flow_exemplars", Map.of("placeholders", placeholders)), params);
        if (flowRows.isEmpty()) {
            return new CidrResult(List.of(), null, "No shared TCP flows observed across at least two captures.");
        }
        Map<Endpoint, Set<Endpoint>> peers = new HashMap<>();
        for (Map<String, Object> r : flowRows) {
            Endpoint a = new Endpoint((String) r.get("src"), Py.toInt(r.get("sport")));
            Endpoint b = new Endpoint((String) r.get("dst"), Py.toInt(r.get("dport")));
            peers.computeIfAbsent(a, k -> new java.util.HashSet<>()).add(b);
            peers.computeIfAbsent(b, k -> new java.util.HashSet<>()).add(a);
        }
        Set<String> clientIps = new LinkedHashSet<>();
        Set<String> serverIps = new LinkedHashSet<>();
        Set<Long> serverPorts = new LinkedHashSet<>();
        for (Map<String, Object> r : flowRows) {
            Endpoint a = new Endpoint((String) r.get("src"), Py.toInt(r.get("sport")));
            Endpoint b = new Endpoint((String) r.get("dst"), Py.toInt(r.get("dport")));
            int degA = peers.get(a).size();
            int degB = peers.get(b).size();
            Endpoint server;
            Endpoint client;
            if (degA > degB && degA >= 2) {
                server = a;
                client = b;
            } else if (degB > degA && degB >= 2) {
                server = b;
                client = a;
            } else {
                long pa = a.port;
                long pb = b.port;
                if (pa == pb) {
                    return new CidrResult(List.of(), null,
                            "Ambiguous client direction: no TCP SYN/SYN-ACK and identical TCP ports.");
                }
                Endpoint low = pa < pb ? a : b;
                Endpoint high = pa < pb ? b : a;
                long lowP = low.port;
                long highP = high.port;
                if ((lowP < 1024 && highP >= 1024) || (lowP <= 10000 && highP >= 10000 && highP >= 2 * lowP)) {
                    server = low;
                    client = high;
                } else {
                    return new CidrResult(List.of(), null, "Ambiguous client direction: no TCP SYN/SYN-ACK and ports ("
                            + pa + ", " + pb + ") do not separate service from ephemeral.");
                }
            }
            clientIps.add(client.ip);
            serverIps.add(server.ip);
            serverPorts.add(server.port);
        }
        if (clientIps.isEmpty() || intersects(clientIps, serverIps)) {
            return new CidrResult(List.of(), null,
                    "Ambiguous client direction: endpoint IPs act as both client and server.");
        }
        List<String> cidrs = new ArrayList<>();
        for (String ip : Py.sortedStr(clientIps)) {
            cidrs.add(hostCidr(ip));
        }
        List<Long> ports = new ArrayList<>(serverPorts);
        java.util.Collections.sort(ports);
        List<Object> portValues = new ArrayList<>();
        for (Long p : ports) {
            portValues.add(Py.i(p));
        }
        Map<String, Object> first = flowRows.get(0);
        String firstCid = (String) first.get("capture_id");
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("method", "server_port_heuristic");
        metrics.put("client_ips", new ArrayList<Object>(Py.sortedStr(clientIps)));
        metrics.put("server_ips", new ArrayList<Object>(Py.sortedStr(serverIps)));
        metrics.put("server_ports", portValues);
        metrics.put("shared_flows", Py.i(flowRows.size()));
        Map<String, Object> inference = dict("kind", "client_cidrs", "method", "server_port_heuristic",
                "capture_ids", new ArrayList<Object>(List.of(firstCid)), "client_cidrs", new ArrayList<Object>(cidrs),
                "metrics", metrics,
                "evidence", new ArrayList<Object>(List.of(ref(firstCid, names, first.get("frame")))),
                "reason", "Client CIDR(s) " + String.join(", ", cidrs)
                        + " inferred from server-port heuristic (service port(s) " + Py.repr(portValues) + ").");
        return new CidrResult(cidrs, inference, null);
    }
}
