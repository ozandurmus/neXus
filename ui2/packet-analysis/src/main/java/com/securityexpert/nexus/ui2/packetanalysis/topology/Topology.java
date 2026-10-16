package com.securityexpert.nexus.ui2.packetanalysis.topology;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The case topology ({@code topology/models.py Topology}): analysis settings, capture points, forward/reverse paths,
 * client CIDRs, NAT mappings and clock overrides.
 *
 * <p>The scalar settings are kept in declaration order in {@link #settings} (values are {@link PyNum}, {@link String}
 * or {@code null}), so {@link #dump()} reproduces {@code model_dump()} key order and the int-or-float type of each
 * value (defaults are not validated in the reference, so {@code cycle_stall_ms} dumps as {@code 100} until a
 * validated value replaces it).
 */
public final class Topology {

    private enum Kind { FLOAT, INT, OPT_INT, OPT_STR, OPT_FLOAT, RANGES }

    private record Spec(String name, Kind kind, Object dflt, Double ge, Double le, boolean finite) {
    }

    /** The scalar fields before {@code points}, then the ones after {@code clock_overrides}. */
    private static final List<Spec> HEAD = List.of(
            new Spec("cycle_stall_ms", Kind.FLOAT, new PyInt(100), 1.0, 60000.0, true),
            new Spec("cycle_top_n", Kind.INT, new PyInt(20), 1.0, 100.0, false),
            new Spec("rtp_port_ranges", Kind.RANGES, null, null, null, false),
            new Spec("rtp_clock_rate", Kind.OPT_INT, null, 1000.0, 192000.0, false),
            new Spec("rtp_jitter_ms", Kind.FLOAT, new PyInt(2), 0.1, 1000.0, true),
            new Spec("udp_timeout_ms", Kind.FLOAT, new PyInt(2000), 100.0, 60000.0, true),
            new Spec("security_half_open_floor", Kind.INT, new PyInt(20), 2.0, 100000.0, false),
            new Spec("security_scan_window_seconds", Kind.FLOAT, new PyInt(5), 1.0, 300.0, true),
            new Spec("security_scan_targets", Kind.INT, new PyInt(20), 2.0, 100000.0, false),
            new Spec("security_scan_failed_ratio", Kind.FLOAT, new PyFloat(0.8), 0.5, 1.0, true),
            new Spec("security_syn_rate", Kind.FLOAT, new PyInt(50), 1.0, 1000000.0, true),
            new Spec("security_completion_ratio", Kind.FLOAT, new PyFloat(0.2), 0.0, 0.5, true),
            new Spec("security_min_sources", Kind.INT, new PyInt(20), 2.0, 100000.0, false),
            new Spec("security_sustained_buckets", Kind.INT, new PyInt(3), 2.0, 1000.0, false),
            new Spec("session_timeout_seconds", Kind.FLOAT, new PyInt(10), 1.0, 300.0, true),
            new Spec("microburst_window_ms", Kind.FLOAT, new PyInt(1), 1.0, 10.0, true),
            new Spec("dns_timeout_ms", Kind.FLOAT, new PyInt(2000), 100.0, 60000.0, true),
            new Spec("bucket_seconds", Kind.FLOAT, new PyInt(1), 0.1, 3600.0, true),
            new Spec("report_timezone", Kind.OPT_STR, null, null, null, false));
    private static final List<Spec> TAIL = List.of(
            new Spec("min_eligible_ratio", Kind.FLOAT, new PyFloat(0.9), 0.0, 1.0, true),
            new Spec("duplicate_us", Kind.FLOAT, new PyInt(20), 0.0, 100.0, true),
            new Spec("stall_ms", Kind.FLOAT, new PyInt(200), 1.0, 60000.0, false),
            new Spec("match_window_ms", Kind.FLOAT, new PyInt(2000), 1.0, 60000.0, false),
            new Spec("start", Kind.OPT_FLOAT, null, null, null, true),
            new Spec("end", Kind.OPT_FLOAT, null, null, null, true));

    /** Scalar settings by name, in declaration order (head fields, then tail fields). */
    public final Map<String, Object> settings = new LinkedHashMap<>();
    public List<Point> points = new ArrayList<>();
    public List<String> forward = new ArrayList<>();
    public List<String> reverse = new ArrayList<>();
    public List<String> clientCidrs = new ArrayList<>(List.of("10.0.0.0/8"));
    public List<NatMapping> natMappings = new ArrayList<>();
    public Map<String, ClockOverride> clockOverrides = new LinkedHashMap<>();

    /** {@code Topology()}: every field at its default. */
    public Topology() {
        for (Spec s : HEAD) {
            settings.put(s.name, s.kind == Kind.RANGES ? new ArrayList<>() : s.dflt);
        }
        for (Spec s : TAIL) {
            settings.put(s.name, s.dflt);
        }
    }

    /** {@code Topology.model_validate(data)}, including the model validator's path and stage normalization. */
    public static Topology validate(Object data) {
        Map<String, Object> d = Fields.object(data, "Topology");
        Topology t = new Topology();
        for (Spec s : HEAD) {
            if (d.containsKey(s.name)) {
                t.settings.put(s.name, scalar(s, d.get(s.name)));
            }
        }
        for (Spec s : TAIL) {
            if (d.containsKey(s.name)) {
                t.settings.put(s.name, scalar(s, d.get(s.name)));
            }
        }
        if (d.containsKey("points")) {
            List<?> pts = list(d.get("points"), "points");
            if (pts.size() > 64) {
                throw new IllegalArgumentException("points: List should have at most 64 items");
            }
            t.points = new ArrayList<>();
            for (Object p : pts) {
                t.points.add(Point.validate(p));
            }
        }
        if (d.containsKey("forward")) {
            t.forward = strList(d.get("forward"), "forward");
        }
        if (d.containsKey("reverse")) {
            t.reverse = strList(d.get("reverse"), "reverse");
        }
        if (d.containsKey("client_cidrs")) {
            t.clientCidrs = strList(d.get("client_cidrs"), "client_cidrs");
        }
        if (d.containsKey("nat_mappings")) {
            List<?> nm = list(d.get("nat_mappings"), "nat_mappings");
            if (nm.size() > 10000) {
                throw new IllegalArgumentException("nat_mappings: List should have at most 10000 items");
            }
            t.natMappings = new ArrayList<>();
            for (Object m : nm) {
                t.natMappings.add(NatMapping.validate(m));
            }
        }
        if (d.containsKey("clock_overrides")) {
            Map<String, Object> co = Fields.object(d.get("clock_overrides"), "dict");
            t.clockOverrides = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : co.entrySet()) {
                t.clockOverrides.put(e.getKey(), ClockOverride.validate(e.getValue()));
            }
        }
        t.modelValidator();
        return t;
    }

    private static Object scalar(Spec s, Object v) {
        switch (s.kind) {
            case FLOAT:
                return Fields.boundedFloat(one(s.name, v), s.name, null, s.ge, s.le, s.finite);
            case OPT_FLOAT:
                return v == null ? null : Fields.boundedFloat(one(s.name, v), s.name, null, s.ge, s.le, s.finite);
            case INT:
                return Fields.intField(one(s.name, v), s.name, 0, s.ge.longValue(), s.le.longValue());
            case OPT_INT:
                return v == null ? null
                        : Fields.intField(one(s.name, v), s.name, 0, s.ge.longValue(), s.le.longValue());
            case OPT_STR:
                if (v != null && !(v instanceof String)) {
                    throw new IllegalArgumentException(s.name + ": Input should be a valid string");
                }
                return v;
            case RANGES: {
                List<?> items = list(v, s.name);
                if (items.size() > 16) {
                    throw new IllegalArgumentException(s.name + ": List should have at most 16 items");
                }
                List<Object> out = new ArrayList<>();
                for (Object item : items) {
                    List<?> pair = list(item, s.name);
                    if (pair.size() != 2) {
                        throw new IllegalArgumentException(s.name + ": Tuple should have 2 items");
                    }
                    out.add(PyTuple.of(Fields.toInt(s.name, pair.get(0)), Fields.toInt(s.name, pair.get(1))));
                }
                return out;
            }
            default:
                throw new IllegalStateException();
        }
    }

    private static Map<String, Object> one(String name, Object v) {
        Map<String, Object> m = new HashMap<>();
        m.put(name, v);
        return m;
    }

    private static List<?> list(Object v, String name) {
        if (v instanceof List<?> l) {
            return l;
        }
        if (v instanceof PyTuple t) {
            return t.items();
        }
        throw new IllegalArgumentException(name + ": Input should be a valid list");
    }

    private static List<String> strList(Object v, String name) {
        List<String> out = new ArrayList<>();
        for (Object item : list(v, name)) {
            if (!(item instanceof String s)) {
                throw new IllegalArgumentException(name + ": Input should be a valid string");
            }
            out.add(s);
        }
        return out;
    }

    private void modelValidator() {
        Map<String, String> domains = new LinkedHashMap<>();
        for (Point p : points) {
            if (p.clockDomain != null && !p.clockDomain.isEmpty()) {
                if (domains.containsKey(p.captureId) && !domains.get(p.captureId).equals(p.clockDomain)) {
                    throw new IllegalArgumentException(
                            "Value error, One capture file cannot belong to conflicting clock domains");
                }
                domains.put(p.captureId, p.clockDomain);
            }
        }
        for (String domain : new HashSet<>(domains.values())) {
            List<PyTuple> corrections = new ArrayList<>();
            for (Map.Entry<String, String> e : domains.entrySet()) {
                if (e.getValue().equals(domain) && clockOverrides.containsKey(e.getKey())) {
                    ClockOverride o = clockOverrides.get(e.getKey());
                    PyTuple c = PyTuple.of(o.offsetMs(), o.driftPpm());
                    if (!Py.contains(corrections, c)) {
                        corrections.add(c);
                    }
                }
            }
            if (corrections.size() > 1) {
                throw new IllegalArgumentException("Value error, Shared clock domain has conflicting clock corrections");
            }
        }
        Set<String> stageDevices = new HashSet<>();
        for (Point p : points) {
            if (p.stageCapture) {
                stageDevices.add(p.device);
            }
        }
        if (!stageDevices.isEmpty()) {
            Map<String, Point> byId = new HashMap<>();
            for (Point p : points) {
                byId.put(p.id, p);
            }
            for (String id : concat(forward, reverse)) {
                if (!byId.containsKey(id)) {
                    throw new IllegalArgumentException("Value error, Paths must contain existing capture points");
                }
            }
            for (Point p : points) {
                if (stageDevices.contains(p.device)) {
                    p.stageCapture = true;
                    if (!"ingress".equals(p.side) && !"egress".equals(p.side)) {
                        throw new IllegalArgumentException(
                                "Value error, Stage captures require rx/ingress or tx/egress side");
                    }
                }
            }
            forward = stageOrder(forward, byId, stageDevices);
            List<String> rev = reverse.isEmpty() ? reversed(forward) : reverse;
            reverse = stageOrder(rev, byId, stageDevices);
        }
        @SuppressWarnings("unchecked")
        List<PyTuple> ranges = (List<PyTuple>) settings.get("rtp_port_ranges");
        for (PyTuple r : ranges) {
            long a = ((PyInt) r.items().get(0)).value();
            long b = ((PyInt) r.items().get(1)).value();
            if (!(1 <= a && a <= b && b <= 65535)) {
                throw new IllegalArgumentException("Value error, RTP port ranges must be ordered UDP ports 1..65535");
            }
        }
        Object tz = settings.get("report_timezone");
        if (tz instanceof String zone && !zone.isEmpty()) {
            try {
                ZoneId.of(zone);
            } catch (DateTimeException e) {
                throw new IllegalArgumentException("Value error, Unknown IANA time zone", e);
            }
        }
        List<String> ids = new ArrayList<>();
        for (Point p : points) {
            ids.add(p.id);
        }
        if (ids.size() != new HashSet<>(ids).size()) {
            throw new IllegalArgumentException("Value error, Capture point IDs must be unique");
        }
        for (List<String> path : List.of(forward, reverse)) {
            if (path.size() != new HashSet<>(path).size() || !ids.containsAll(path)) {
                throw new IllegalArgumentException(
                        "Value error, Paths must contain existing capture points once; cycles are unsupported");
            }
        }
        Map<String, Set<List<String>>> paloalto = new HashMap<>();
        Map<String, Set<String>> checkpoint = new HashMap<>();
        for (Point p : points) {
            if ("paloalto".equals(p.vendor)) {
                paloalto.computeIfAbsent(p.captureId, k -> new HashSet<>())
                        .add(java.util.Arrays.asList(p.device, p.vendorStage));
            }
            if ("checkpoint".equals(p.vendor)) {
                checkpoint.computeIfAbsent(p.captureId, k -> new HashSet<>()).add(p.device);
            }
        }
        if (paloalto.values().stream().anyMatch(s -> s.size() > 1)) {
            throw new IllegalArgumentException("Value error, Each Palo Alto stage file must have one node/stage tag");
        }
        if (checkpoint.values().stream().anyMatch(s -> s.size() > 1)) {
            throw new IllegalArgumentException("Value error, A fw monitor file must map to inspection points of one node");
        }
        for (String cidr : clientCidrs) {
            IpNetworks.network(cidr);
        }
        if (clientCidrs.isEmpty()) {
            throw new IllegalArgumentException("Value error, At least one client CIDR is required to define direction");
        }
        Object start = settings.get("start");
        Object end = settings.get("end");
        if (start != null && end != null && Py.ge(start, end)) {
            throw new IllegalArgumentException("Value error, Time window start must precede end");
        }
    }

    /** Within each run of one device, a stage device's ingress point comes first (a stable sort). */
    private static List<String> stageOrder(List<String> path, Map<String, Point> byId, Set<String> stageDevices) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < path.size()) {
            String device = byId.get(path.get(i)).device;
            int j = i;
            while (j < path.size() && byId.get(path.get(j)).device.equals(device)) {
                j++;
            }
            List<String> block = new ArrayList<>(path.subList(i, j));
            if (stageDevices.contains(device)) {
                List<String> ingress = new ArrayList<>();
                List<String> other = new ArrayList<>();
                for (String id : block) {
                    ("ingress".equals(byId.get(id).side) ? ingress : other).add(id);
                }
                block = concat(ingress, other);
            }
            result.addAll(block);
            i = j;
        }
        return result;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    static List<String> reversed(List<String> l) {
        List<String> out = new ArrayList<>(l);
        java.util.Collections.reverse(out);
        return out;
    }

    /** {@code model_copy(deep=True)}. */
    public Topology copy() {
        Topology t = new Topology();
        t.settings.clear();
        for (Map.Entry<String, Object> e : settings.entrySet()) {
            Object v = e.getValue();
            t.settings.put(e.getKey(), v instanceof List<?> l ? new ArrayList<>(l) : v);
        }
        t.points = new ArrayList<>();
        for (Point p : points) {
            t.points.add(p.copy());
        }
        t.forward = new ArrayList<>(forward);
        t.reverse = new ArrayList<>(reverse);
        t.clientCidrs = new ArrayList<>(clientCidrs);
        t.natMappings = new ArrayList<>(natMappings);
        t.clockOverrides = new LinkedHashMap<>(clockOverrides);
        return t;
    }

    /** {@code model_dump()} in field order. */
    public Map<String, Object> dump() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Spec s : HEAD) {
            Object v = settings.get(s.name);
            m.put(s.name, v instanceof List<?> l ? new ArrayList<>(l) : v);
        }
        List<Object> pts = new ArrayList<>();
        for (Point p : points) {
            pts.add(p.dump());
        }
        m.put("points", pts);
        m.put("forward", new ArrayList<Object>(forward));
        m.put("reverse", new ArrayList<Object>(reverse));
        m.put("client_cidrs", new ArrayList<Object>(clientCidrs));
        List<Object> nm = new ArrayList<>();
        for (NatMapping n : natMappings) {
            nm.add(n.dump());
        }
        m.put("nat_mappings", nm);
        Map<String, Object> co = new LinkedHashMap<>();
        for (Map.Entry<String, ClockOverride> e : clockOverrides.entrySet()) {
            co.put(e.getKey(), e.getValue().dump());
        }
        m.put("clock_overrides", co);
        for (Spec s : TAIL) {
            m.put(s.name, settings.get(s.name));
        }
        return m;
    }
}
