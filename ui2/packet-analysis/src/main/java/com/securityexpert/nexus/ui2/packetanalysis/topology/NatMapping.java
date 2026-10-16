package com.securityexpert.nexus.ui2.packetanalysis.topology;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A confirmed NAT tuple pair ({@code topology/models.py NatMapping}); tuples are stored as compact JSON text. */
public record NatMapping(String pointA, String pointB, String tupleA, String tupleB) {

    private static final Set<String> PROTOCOLS = Set.of("TCP", "UDP", "ICMP", "ICMPv6", "GRE");

    /** {@code NatMapping.model_validate(data)}: tuples are checked and re-encoded with {@code (",", ":")}. */
    public static NatMapping validate(Object data) {
        Map<String, Object> d = Fields.object(data, "NatMapping");
        String a = Fields.str(d, "point_a", null, true, 0, Integer.MAX_VALUE);
        String b = Fields.str(d, "point_b", null, true, 0, Integer.MAX_VALUE);
        String ta = tuple(Fields.str(d, "tuple_a", null, true, 0, Integer.MAX_VALUE));
        String tb = tuple(Fields.str(d, "tuple_b", null, true, 0, Integer.MAX_VALUE));
        return new NatMapping(a, b, ta, tb);
    }

    private static String tuple(String value) {
        Object parsed = PyJson.loads(value);
        if (!(parsed instanceof List<?> parts) || parts.size() != 5) {
            throw new IllegalArgumentException(
                    "Value error, NAT tuple must be [protocol, source IP, source port, destination IP, destination port]");
        }
        if (!(parts.get(0) instanceof String proto) || !PROTOCOLS.contains(proto)) {
            throw new IllegalArgumentException("Value error, Unsupported NAT protocol");
        }
        for (int i : new int[] {1, 3}) {
            if (!(parts.get(i) instanceof String ip)) {
                throw new IllegalArgumentException("Value error, " + Py.repr(parts.get(i))
                        + " does not appear to be an IPv4 or IPv6 address");
            }
            IpNetworks.address(ip);
        }
        for (int i : new int[] {2, 4}) {
            if (!(parts.get(i) instanceof PyInt p) || p.value() < 0 || p.value() > 65535) {
                throw new IllegalArgumentException("Value error, Ports must be integers between 0 and 65535");
            }
        }
        return compact(parts);
    }

    /** {@code json.dumps(parts, separators=(",", ":"))}. */
    private static String compact(List<?> parts) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(PyJson.dumps(parts.get(i)));
        }
        return out.append(']').toString();
    }

    public Map<String, Object> dump() {
        return Py.dict("point_a", pointA, "point_b", pointB, "tuple_a", tupleA, "tuple_b", tupleB);
    }
}
