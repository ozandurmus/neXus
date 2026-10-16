package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyConvert;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import com.securityexpert.nexus.ui2.packetanalysis.store.PacketColumns;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One tshark field record to one {@code packets} row (the reference {@code parse_packet}).
 *
 * <p>Every value keeps the reference's runtime type, because the row is written as CSV text with the reference's
 * {@code str()} rules and loaded by DuckDB's {@code read_csv}: integers are {@link Long}, floats {@link Double},
 * flags {@link Boolean}, absent values {@code null}, and empty strings stay {@code ""} (which the load turns into
 * NULL, exactly as it does for the reference).
 */
public final class PacketParser {

    private static final Set<String> TUNNELS = Set.of("vxlan", "geneve", "gre");
    private static final HexFormat HEX = HexFormat.of();

    private PacketParser() {
    }

    /** The first comma-separated occurrence of a field ({@code d.get(k, "").split(",")[0]}). */
    static String first(Map<String, String> d, String k) {
        String v = d.getOrDefault(k, "");
        int comma = v.indexOf(',');
        return comma < 0 ? v : v.substring(0, comma);
    }

    /** The reference {@code number(value, default)}: True/False, 0x-hex or decimal; empty gives the default. */
    static Long number(String value, Long dflt) {
        if (value.equals("True") || value.equals("False")) {
            return value.equals("True") ? 1L : 0L;
        }
        if (value.startsWith("0x")) {
            return Long.valueOf(PyConvert.parseInt(value, 16));
        }
        return value.isEmpty() ? dflt : Long.valueOf(PyConvert.parseInt(value));
    }

    static long number(String value) {
        return number(value, 0L);
    }

    private static String or(String a, String b) {
        return a.isEmpty() ? b : a;
    }

    /** {@code str.strip(chars)} for a single char. */
    static String strip(String s, char c) {
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == c) {
            a++;
        }
        while (b > a && s.charAt(b - 1) == c) {
            b--;
        }
        return s.substring(a, b);
    }

    /** {@code s[:n]} by code points. */
    static String head(String s, int n) {
        if (s.length() <= n) {
            return s;
        }
        if (s.codePointCount(0, s.length()) <= n) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** {@code bytes.fromhex(s)}: ASCII whitespace allowed between byte pairs, anything else raises ValueError. */
    static byte[] fromHex(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() / 2);
        int i = 0;
        int n = s.length();
        while (true) {
            while (i < n && isSpace(s.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int top = hexDigit(s.charAt(i));
            int bot = i + 1 < n ? hexDigit(s.charAt(i + 1)) : -1;
            if (top < 0) {
                throw new IllegalArgumentException(
                        "non-hexadecimal number found in fromhex() arg at position " + i);
            }
            if (bot < 0) {
                throw new IllegalArgumentException(
                        "non-hexadecimal number found in fromhex() arg at position " + (i + 1));
            }
            out.write((top << 4) | bot);
            i += 2;
        }
        return out.toByteArray();
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0b || c == '\f';
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    static String sha256Hex(byte[] data) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> protocols(String framed) {
        return Set.copyOf(Arrays.asList(framed.split(":", -1)));
    }

    /** The reference {@code tuple_id}: compact JSON of the 5-tuple. */
    static String tupleId(String proto, String src, long sport, String dst, long dport) {
        return PyJson.dumpsCompact(Arrays.asList(proto, src, sport, dst, dport));
    }

    /**
     * Parse one record. {@code values} pairs with {@code fields} position by position (extra values or fields are
     * ignored, a repeated field name keeps its last value). Malformed numbers raise IllegalArgumentException.
     */
    public static Object[] parse(List<String> values, String captureId, int prefixBytes, List<String> fields) {
        Map<String, String> d = new LinkedHashMap<>();
        int n = Math.min(values.size(), fields.size());
        for (int i = 0; i < n; i++) {
            d.put(fields.get(i), values.get(i));
        }
        String src = or(first(d, "ip.src"), first(d, "ipv6.src"));
        String dst = or(first(d, "ip.dst"), first(d, "ipv6.dst"));
        String proto = !first(d, "tcp.srcport").isEmpty() ? "TCP"
                : !first(d, "udp.srcport").isEmpty() ? "UDP"
                : !first(d, "icmp.type").isEmpty() ? "ICMP"
                : !first(d, "icmpv6.type").isEmpty() ? "ICMPv6" : "OTHER";
        Map<String, Object> quoted = new LinkedHashMap<>();
        for (String k : List.of("src", "dst", "sport", "dport", "seq")) {
            quoted.put(k, null);
        }
        String outerProto = or(first(d, "ip.proto"), first(d, "ipv6.nxt"));
        if (outerProto.equals("1") || outerProto.equals("58")) {
            for (String name : List.of("src", "dst")) {
                String ip = d.get("ip." + name);
                String raw = ip != null && !ip.isEmpty() ? ip : d.get("ipv6." + name);
                String[] addresses = (raw == null ? "" : raw).split(",", -1);
                quoted.put(name, addresses.length > 1 ? addresses[1] : null);
            }
            quoted.put("sport", number(first(d, "tcp.srcport"), null));
            quoted.put("dport", number(first(d, "tcp.dstport"), null));
            quoted.put("seq", number(first(d, "tcp.seq_raw"), null));
            proto = outerProto.equals("1") ? "ICMP" : "ICMPv6";
            // tshark also decodes the quoted transport inside ICMP errors. Keep it out of outer identity.
            for (String field : new ArrayList<>(d.keySet())) {
                if (field.startsWith("tcp.") || field.startsWith("udp.")) {
                    d.put(field, "");
                }
            }
        }
        long sport = number(or(first(d, "tcp.srcport"), first(d, "udp.srcport")));
        long dport = number(or(first(d, "tcp.dstport"), first(d, "udp.dstport")));
        long seq = number(first(d, "tcp.seq_raw"));
        long ack = number(first(d, "tcp.ack_raw"));
        long flags = number(first(d, "tcp.flags"));
        long length = proto.equals("TCP") ? number(first(d, "tcp.len"))
                : proto.equals("UDP") ? Math.max(0, number(first(d, "udp.length")) - 8)
                : number(or(first(d, "ip.len"), first(d, "ipv6.plen")));
        String fullHex = or(first(d, "tcp.payload"), first(d, "udp.payload")).replace(":", "");
        String payload = head(fullHex, prefixBytes * 2);
        long ipid = number(or(first(d, "ip.id"), first(d, "ipv6.flow")));
        List<Object> identity = Arrays.asList(proto, ipid, seq, ack, flags, length, first(d, "icmp.ident"),
                first(d, "icmp.seq"), first(d, "icmp.type"), first(d, "icmpv6.type"));
        long wirelen = number(first(d, "frame.len"));
        long caplen = number(first(d, "frame.cap_len"));
        String frameProtocols = first(d, "frame.protocols");
        Set<String> protocolSet = protocols(frameProtocols);
        boolean tunnel = protocolSet.stream().anyMatch(TUNNELS::contains);
        String unsupported = "";
        if (!List.of("TCP", "UDP", "ICMP", "ICMPv6").contains(proto)) {
            unsupported = "Transport identity unsupported in Phase 1";
        }
        boolean fragmented = false;
        for (String x : List.of("ip.flags.mf", "ip.frag_offset", "ipv6.fraghdr.offset", "ipv6.fraghdr.more")) {
            if (number(first(d, x)) != 0) {
                fragmented = true;
                break;
            }
        }
        if (fragmented) {
            unsupported = "Fragmented packet; reassembly correlation is not available in Phase 1";
        }
        if (length > 9000 && !proto.equals("TCP")) {
            unsupported = "Possible offload super-frame; byte-range correlation is not available in Phase 1";
        }
        if (proto.equals("UDP") && strip(payload, '0').isEmpty()) {
            unsupported = "UDP payload unavailable or all-zero; identity is insufficient";
        }
        if (tunnel) {
            unsupported = "Tunnel encapsulation; decapsulation selection is not available in Phase 1";
        }

        Object[] row = new Object[PacketColumns.NAMES.size()];
        int i = 0;
        row[i++] = captureId;
        row[i++] = number(first(d, "frame.number"));
        row[i++] = PyConvert.parseFloat(first(d, "frame.time_epoch"));
        row[i++] = number(first(d, "frame.interface_id"));
        row[i++] = src;
        row[i++] = dst;
        row[i++] = sport;
        row[i++] = dport;
        row[i++] = proto;
        row[i++] = ipid;
        row[i++] = seq;
        row[i++] = ack;
        row[i++] = flags;
        row[i++] = length;
        row[i++] = wirelen;
        row[i++] = caplen;
        row[i++] = payload;
        row[i++] = payload.isEmpty() ? "" : sha256Hex(fromHex(payload));
        row[i++] = sha256Hex(PyJson.dumps(identity).getBytes(StandardCharsets.UTF_8));
        row[i++] = tupleId(proto, src, sport, dst, dport);
        row[i++] = tupleId(proto, dst, dport, src, sport);
        row[i++] = number(first(d, "tcp.stream"), -1L);
        row[i++] = number(or(first(d, "ip.ttl"), first(d, "ipv6.hlim")));
        row[i++] = number(or(first(d, "ip.dsfield.dscp"), first(d, "ipv6.tclass.dscp")));
        row[i++] = number(first(d, "tcp.options.mss_val"));
        row[i++] = number(first(d, "tcp.window_size_value"));
        row[i++] = !first(d, "tcp.analysis.retransmission").isEmpty()
                || !first(d, "tcp.analysis.fast_retransmission").isEmpty();
        row[i++] = !first(d, "tcp.analysis.fast_retransmission").isEmpty();
        row[i++] = !first(d, "tcp.analysis.spurious_retransmission").isEmpty();
        row[i++] = !first(d, "tcp.analysis.out_of_order").isEmpty();
        row[i++] = !first(d, "tcp.analysis.ack_lost_segment").isEmpty();
        row[i++] = !first(d, "tcp.analysis.zero_window").isEmpty();
        String rtt = first(d, "tcp.analysis.ack_rtt");
        row[i++] = rtt.isEmpty() ? null : (Object) PyConvert.parseFloat(rtt);
        row[i++] = unsupported;
        row[i++] = first(d, "frame.md5_hash");
        row[i++] = number(first(d, "icmp.ident"), null);
        row[i++] = number(first(d, "icmp.seq"), null);
        row[i++] = number(or(first(d, "icmp.type"), first(d, "icmpv6.type")), null);
        row[i++] = number(first(d, "dns.id"), null);
        row[i++] = number(first(d, "dns.flags.response")) != 0;
        row[i++] = VendorFields.decoded(d);
        row[i++] = pathFields(d, protocolSet, tunnel, quoted, proto, length, caplen, wirelen, fullHex);
        return row;
    }

    private static String pathFields(Map<String, String> d, Set<String> protocolSet, boolean tunnel,
            Map<String, Object> quoted, String proto, long length, long caplen, long wirelen, String fullHex) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("transport_index", 1L);
        if (protocolSet.contains("sip")) {
            p.put("sip_probe", 1L);
        }
        if (tunnel) {
            String hint = "unknown";
            for (String k : List.of("erspan", "vxlan", "geneve", "gre")) {
                if (protocolSet.contains(k)) {
                    hint = k;
                    break;
                }
            }
            p.put("tunnel_hint", hint);
        }
        p.put("dns_index", 1L);
        p.put("dns_name", emptyToNull(first(d, "dns.qry.name")));
        p.put("dns_type", number(first(d, "dns.qry.type"), null));
        p.put("dns_class", number(first(d, "dns.qry.class"), null));
        p.put("dns_questions", number(first(d, "dns.count.queries"), null));
        p.put("dns_rcode", number(first(d, "dns.flags.rcode"), null));
        p.put("http_method", emptyToNull(first(d, "http.request.method")));
        p.put("window_full", !first(d, "tcp.analysis.window_full").isEmpty());
        p.put("duplicate_ack", !first(d, "tcp.analysis.duplicate_ack").isEmpty());
        p.put("zero_probe", !first(d, "tcp.analysis.zero_window_probe").isEmpty());
        p.put("bytes_in_flight", number(first(d, "tcp.analysis.bytes_in_flight"), null));
        String rto = first(d, "tcp.analysis.rto");
        p.put("rto_seconds", rto.isEmpty() ? null : (Object) PyConvert.parseFloat(rto));
        p.put("rto_frame", number(first(d, "tcp.analysis.rto_frame"), null));
        p.put("sack_left", number(first(d, "tcp.options.sack_le"), null));
        p.put("sack_right", number(first(d, "tcp.options.sack_re"), null));
        p.put("quoted", quoted);
        p.put("tls_record_type", number(first(d, "tls.record.content_type"), null));
        p.put("window_scale", number(first(d, "tcp.options.wscale.shift"), null));
        boolean complete = caplen == wirelen;
        p.put("tcp_options", complete ? first(d, "tcp.options") : null);
        p.put("sack_permitted", complete ? (Object) !first(d, "tcp.options.sack_perm").isEmpty() : null);
        p.put("timestamp_value", number(first(d, "tcp.options.timestamp.tsval"), null));
        p.put("timestamp_echo", number(first(d, "tcp.options.timestamp.tsecr"), null));
        p.put("df", !first(d, "ip.src").isEmpty() ? (Object) (number(first(d, "ip.flags.df")) != 0) : null);
        p.put("icmp_type", number(or(first(d, "icmp.type"), first(d, "icmpv6.type")), null));
        p.put("icmp_code", number(or(first(d, "icmp.code"), first(d, "icmpv6.code")), null));
        p.put("icmp_mtu", number(or(first(d, "icmp.mtu"), first(d, "icmpv6.mtu")), null));
        p.put("payload_sha256", length != 0 && (proto.equals("TCP") || proto.equals("UDP"))
                ? sha256Hex(fromHex(fullHex)) : null);
        p.put("payload_complete", complete && (length == 0 || !strip(fullHex, '0').isEmpty())
                && fullHex.codePointCount(0, fullHex.length()) == length * 2);
        return PyJson.dumpsCompact(p);
    }

    private static String emptyToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
