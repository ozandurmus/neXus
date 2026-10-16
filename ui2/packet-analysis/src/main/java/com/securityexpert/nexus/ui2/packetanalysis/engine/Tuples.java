package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tuple keys and address arithmetic as the reference computes them: {@code ingest.tuple_id} (compact JSON
 * five-tuple), {@code selection.reverse_tuple}, {@code ipaddress.ip_address} / {@code ip_network(strict=False)}
 * membership (a version mismatch is "not contained", an unparsable address raises).
 */
public final class Tuples {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Tuples() {
    }

    public static List<Object> parse(String key) {
        try {
            JsonNode n = JSON.readTree(key);
            if (n == null || !n.isArray()) {
                throw new IllegalArgumentException("Tuple is not a list");
            }
            List<Object> out = new ArrayList<>();
            for (JsonNode p : n) {
                out.add(p.isNull() ? null
                        : p.isIntegralNumber() ? (Object) p.asLong()
                        : p.isNumber() ? (Object) p.asDouble()
                        : p.isTextual() ? (Object) p.asText() : (Object) p.toString());
            }
            return out;
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String tupleId(Object proto, Object src, Object sport, Object dst, Object dport) {
        return PyJsonWriter.compact(Arrays.asList(proto, src, sport, dst, dport));
    }

    public static String reverseTuple(String key) {
        List<Object> t = parse(key);
        if (t.size() != 5) {
            throw new IllegalArgumentException("too many/few values to unpack");
        }
        return tupleId(t.get(0), t.get(3), t.get(4), t.get(1), t.get(2));
    }

    /** An address as (version, value). */
    public record Address(int version, BigInteger value) {
    }

    /** A network as (version, network address, prefix length). */
    public record Network(int version, BigInteger network, int prefix) {
        public boolean contains(Address a) {
            if (a.version() != version) {
                return false;
            }
            int bits = version == 4 ? 32 : 128;
            BigInteger mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
                    .xor(BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE));
            return a.value().and(mask).equals(network);
        }
    }

    /** {@code ipaddress.ip_address(text)}; IllegalArgumentException (ValueError) when it is not an address. */
    public static Address address(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(value + " does not appear to be an IPv4 or IPv6 address");
        }
        Long v4 = ipv4(text);
        if (v4 != null) {
            return new Address(4, BigInteger.valueOf(v4));
        }
        if (text.contains(":") && text.matches("[0-9A-Fa-f:.]+")) {
            try {
                byte[] raw = InetAddress.getByName("[" + text + "]").getAddress();
                if (raw.length == 4) {
                    // Java folds IPv4-mapped literals to IPv4; the reference keeps them IPv6.
                    byte[] mapped = new byte[16];
                    mapped[10] = (byte) 0xff;
                    mapped[11] = (byte) 0xff;
                    System.arraycopy(raw, 0, mapped, 12, 4);
                    raw = mapped;
                }
                return new Address(6, new BigInteger(1, raw));
            } catch (UnknownHostException e) {
                // fall through
            }
        }
        throw new IllegalArgumentException("'" + text + "' does not appear to be an IPv4 or IPv6 address");
    }

    private static Long ipv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        long v = 0;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(c -> c >= '0' && c <= '9')
                    || (p.length() > 1 && p.charAt(0) == '0')) {
                return null;
            }
            int octet = Integer.parseInt(p);
            if (octet > 255) {
                return null;
            }
            v = v * 256 + octet;
        }
        return v;
    }

    /** {@code ipaddress.ip_network(text, strict=False)}. */
    public static Network network(String text) {
        String[] parts = text.split("/", -1);
        Address a = address(parts[0]);
        int bits = a.version() == 4 ? 32 : 128;
        int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : bits;
        if (prefix < 0 || prefix > bits || parts.length > 2) {
            throw new IllegalArgumentException(text + " has invalid prefix length");
        }
        BigInteger mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
                .xor(BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE));
        return new Network(a.version(), a.value().and(mask), prefix);
    }

    public static List<Network> networks(List<String> cidrs) {
        List<Network> out = new ArrayList<>();
        for (String c : cidrs) {
            out.add(network(c));
        }
        return out;
    }

    public static boolean inAny(Address a, List<Network> nets) {
        for (Network n : nets) {
            if (n.contains(a)) {
                return true;
            }
        }
        return false;
    }
}
