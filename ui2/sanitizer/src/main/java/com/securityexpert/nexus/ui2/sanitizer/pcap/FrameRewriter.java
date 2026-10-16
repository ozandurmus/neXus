package com.securityexpert.nexus.ui2.sanitizer.pcap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.securityexpert.nexus.ui2.sanitizer.core.Addresses;
import com.securityexpert.nexus.ui2.sanitizer.core.Counts;
import com.securityexpert.nexus.ui2.sanitizer.core.IpPermutation;
import com.securityexpert.nexus.ui2.sanitizer.core.MacMapper;
import com.securityexpert.nexus.ui2.sanitizer.core.MacText;
import com.securityexpert.nexus.ui2.sanitizer.core.Mappers;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenResolver;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenSink;
import com.securityexpert.nexus.ui2.sanitizer.core.VaultKey;
import com.securityexpert.nexus.ui2.sanitizer.core.VaultKind;
import com.securityexpert.nexus.ui2.sanitizer.pcap.Layers.Field;
import com.securityexpert.nexus.ui2.sanitizer.pcap.Layers.Layer;

/**
 * One frame at a time: which header bytes are kept, which addresses are rewritten, which checksums follow.
 *
 * <ul>
 *   <li>{@link #reference} -- PacketBreaker {@code sanitize_packet} (frozen 5b608e6), byte for byte: header spans
 *       must cover a contiguous prefix, an incomplete L4 or tunnel header refuses the capture, MACs always and IPs
 *       on request are mapped with PacketBreaker's mappers, checksums are updated by RFC 1624 (which also keeps a
 *       bad/offloaded checksum's syndrome), and the payload is zeroed or cut.</li>
 *   <li>{@link #nexus} -- the neXus mode (DATA_SANITIZER_CONTRACT.md §1): the same header model with the neXus
 *       prefix-preserving IP permutation (special ranges kept), OUI-keeping MACs, ARP and quoted inner headers,
 *       ICMP redirect gateways and IPv6 routing-header addresses; bytes after the last header zeroed with the
 *       captured and original lengths kept; DNS messages kept with every name replaced by its same-length
 *       pseudonym and A/AAAA data mapped. It never refuses a frame: what it cannot decode is zeroed.</li>
 *   <li>{@link #revert} -- the vault's reverse of a neXus-sanitized frame: addresses and DNS names that are in
 *       the vault get their originals back, checksums follow; the zeroed payload stays zero.</li>
 * </ul>
 */
final class FrameRewriter {

    enum Mode { REFERENCE, NEXUS, REVERT }

    static final Set<String> HEADERS = Set.of("eth", "ethertype", "vlan", "ip", "ipv6", "ipv6.hopopts", "ipv6.dstopts",
            "ipv6.routing", "ipv6.fraghdr", "gre", "erspan", "vxlan", "geneve", "sll", "sll2", "null", "loop", "arp");
    static final Set<String> IP_FIELDS = Set.of("ip.src", "ip.dst", "ipv6.src", "ipv6.dst", "arp.src.proto_ipv4",
            "arp.dst.proto_ipv4");
    static final Set<String> MAC_FIELDS = Set.of("eth.src", "eth.dst", "arp.src.hw_mac", "arp.dst.hw_mac", "sll.src.eth");

    static final String PACKETS = "packets";
    static final String PAYLOAD_BYTES_ZEROED = "payload_bytes_zeroed";
    static final String DNS_NAMES = "dns_name";
    static final String FRAMES_LINK_ONLY = "frames_without_ip";

    private final Mode mode;
    private final boolean headersOnly;
    private final boolean mapIps;
    private final IpPermutation ip;
    private final MacMapper mac;
    private final DnsRewriter dns;
    private final TokenSink sink;
    private final Counts counts;
    private Map<VaultKey, String> resolved = Map.of();

    private FrameRewriter(Mode mode, boolean headersOnly, boolean mapIps, IpPermutation ip, MacMapper mac,
            DnsRewriter dns, TokenSink sink, Counts counts) {
        this.mode = mode;
        this.headersOnly = headersOnly;
        this.mapIps = mapIps;
        this.ip = ip;
        this.mac = mac;
        this.dns = dns;
        this.sink = sink == null ? TokenSink.DISCARD : sink;
        this.counts = counts == null ? new Counts() : counts;
    }

    /** PacketBreaker's anonymizer; {@code key} is the reference key, or {@code null} for its default MAC key. */
    static FrameRewriter reference(boolean headersOnly, byte[] key, boolean mapIps) {
        byte[] k = key != null ? key : sha256("PacketBreaker MAC masking v1");
        if (k.length < 32) throw new IllegalArgumentException("Anonymization key must contain at least 32 random bytes");
        return new FrameRewriter(Mode.REFERENCE, headersOnly, mapIps, IpPermutation.reference(k), MacMapper.reference(k),
                null, null, null);
    }

    static FrameRewriter nexus(Mappers mappers, TokenSink sink, Counts counts) {
        return new FrameRewriter(Mode.NEXUS, false, true, mappers.ip(), mappers.mac(),
                new DnsRewriter(mappers, sink, counts), sink, counts);
    }

    static FrameRewriter revert(Counts counts) {
        return new FrameRewriter(Mode.REVERT, false, true, null, null, new DnsRewriter(null, null, counts), null, counts);
    }

    Mode mode() {
        return mode;
    }

    /** Revert: the vault keys a batch of frames needs. */
    Set<VaultKey> keys(List<byte[]> frames, List<Integer> linkTypes) {
        Set<VaultKey> keys = new LinkedHashSet<>();
        for (int i = 0; i < frames.size(); i++) {
            byte[] raw = frames.get(i);
            List<Layer> layers = Layers.decode(raw, linkTypes.get(i), Layers.Variant.NEXUS);
            for (Layer l : layers) {
                for (Field f : l.fields) {
                    if (f.pos() + f.size() > raw.length) continue;
                    byte[] value = Arrays.copyOfRange(raw, f.pos(), f.pos() + f.size());
                    if (MAC_FIELDS.contains(f.name()) && f.size() == 6) keys.add(new VaultKey(VaultKind.MAC, MacText.canonical(value)));
                    else if (f.size() == 4 || f.size() == 16) keys.add(new VaultKey(VaultKind.ADDRESS, Addresses.format(value)));
                }
            }
            for (Layer l : layers) {
                if (l.name.equals("icmp") && l.pos + 8 <= raw.length && (raw[l.pos] & 0xff) == 5) {
                    keys.add(new VaultKey(VaultKind.ADDRESS, Addresses.format(Arrays.copyOfRange(raw, l.pos + 4, l.pos + 8))));
                }
            }
            int[] message = dnsMessage(layers, raw);
            if (message != null) dns.collectKeys(raw, message[0], message[1], keys);
        }
        return keys;
    }

    void resolved(Map<VaultKey, String> originals) {
        this.resolved = originals;
    }

    byte[] rewrite(byte[] raw, int linkType) {
        counts.add(PACKETS);
        return switch (mode) {
            case REFERENCE -> referenceFrame(raw, linkType);
            case NEXUS -> nexusFrame(raw, linkType);
            case REVERT -> revertFrame(raw, linkType);
        };
    }

    // ---- reference -----------------------------------------------------------------------------------------

    private record Checksum(Layer layer, Layer network, int pos) {
    }

    private byte[] referenceFrame(byte[] raw, int linkType) {
        List<Layer> protocols = Layers.decode(raw, linkType, Layers.Variant.REFERENCE);
        List<int[]> keep = new ArrayList<>();
        List<Checksum> checksums = new ArrayList<>();
        Layer network = null;
        boolean transport = false;
        for (int index = 0; index < protocols.size(); index++) {
            Layer p = protocols.get(index);
            int pos = p.pos;
            int size = p.size;
            if (HEADERS.contains(p.name)) {
                int following = Integer.MAX_VALUE;
                for (int j = index + 1; j < protocols.size(); j++) {
                    if (protocols.get(j).pos > pos) following = Math.min(following, protocols.get(j).pos);
                }
                if (following != Integer.MAX_VALUE) size = Math.min(size, following - pos);
                switch (p.name) {
                    case "ip" -> {
                        size = p.hdrLen;
                        network = p;
                    }
                    case "ipv6" -> {
                        size = 40;
                        network = p;
                    }
                    case "arp" -> {
                        network = p;
                        transport = true;
                    }
                    default -> {
                    }
                }
                keep.add(new int[] {pos, pos + size});
            } else if (p.isL4()) {
                if (p.name.equals("tcp")) {
                    if (p.hdrLen < 0) {
                        if (!p.quoted || size < 8) throw new PcapException("Incomplete TCP header; cannot safely anonymize");
                        size = 8;
                    } else {
                        size = p.hdrLen;
                    }
                } else {
                    size = 8;
                }
                keep.add(new int[] {pos, pos + size});
                transport = true;
            } else {
                continue;
            }
            if (p.checksum >= 0) checksums.add(new Checksum(p, network, p.checksum));
        }
        if (keep.isEmpty() || network == null || !transport) {
            throw new PcapException("Unsupported or incomplete L4/tunnel header; cannot safely anonymize this frame");
        }
        int end = 0;
        for (int[] k : keep) end = Math.max(end, k[1]);
        if (end > raw.length) throw new PcapException("Truncated protocol header; cannot safely anonymize this frame");
        boolean[] covered = new boolean[raw.length];
        byte[] output = new byte[raw.length];
        for (int[] k : keep) {
            for (int i = Math.max(0, k[0]); i < k[1]; i++) covered[i] = true;
            if (k[1] > k[0]) System.arraycopy(raw, k[0], output, k[0], k[1] - k[0]);
        }
        for (int i = 0; i < end; i++) {
            if (!covered[i]) throw new PcapException("Unknown bytes between decoded headers; anonymization refused");
        }
        for (Layer l : protocols) {
            for (Field f : l.fields) {
                if (f.size() == 0 || f.pos() + f.size() > end || !allCovered(covered, f.pos(), f.size())) continue;
                byte[] value = Arrays.copyOfRange(raw, f.pos(), f.pos() + f.size());
                if (MAC_FIELDS.contains(f.name()) && f.size() == 6) {
                    System.arraycopy(mac.map(value), 0, output, f.pos(), 6);
                } else if (mapIps && (IP_FIELDS.contains(f.name()) || f.name().endsWith(".addr") || f.name().endsWith(".address"))
                        && (f.size() == 4 || f.size() == 16)) {
                    System.arraycopy(ip.map(value), 0, output, f.pos(), f.size());
                }
            }
        }
        checksums(raw, output, checksums);
        return headersOnly ? Arrays.copyOf(output, end) : output;
    }

    private static boolean allCovered(boolean[] covered, int pos, int size) {
        for (int i = pos; i < pos + size; i++) if (!covered[i]) return false;
        return true;
    }

    private void checksums(byte[] raw, byte[] output, List<Checksum> checksums) {
        for (int c = checksums.size() - 1; c >= 0; c--) {
            Checksum cs = checksums.get(c);
            Layer p = cs.layer();
            Layer net = cs.network();
            String name = p.name;
            int start = p.pos;
            int last = raw.length;
            List<int[]> pseudo = new ArrayList<>();
            if (name.equals("ip")) {
                last = start + p.hdrLen;
            } else if (net != null) {
                int npos = net.pos;
                if (net.name.equals("ip")) {
                    last = npos + net.ipLen;
                    if (name.equals("tcp") || name.equals("udp")) pseudo.add(new int[] {npos + 12, npos + 20});
                } else if (net.name.equals("ipv6")) {
                    last = npos + 40 + net.plen;
                    if (name.equals("tcp") || name.equals("udp") || name.equals("icmpv6")) pseudo.add(new int[] {npos + 8, npos + 40});
                } else {
                    continue; // a checksum behind ARP cannot occur
                }
            }
            int pos = cs.pos();
            if (name.equals("udp") && pos + 2 <= raw.length && raw[pos] == 0 && raw[pos + 1] == 0) continue;
            adjustChecksum(raw, output, pos, start, last, pseudo);
            if (name.equals("udp") && pos + 2 <= output.length && output[pos] == 0 && output[pos + 1] == 0) {
                output[pos] = (byte) 0xff;
                output[pos + 1] = (byte) 0xff;
            }
        }
    }

    /** RFC 1624 incremental update, exactly as PacketBreaker's {@code adjust_checksum}. */
    static void adjustChecksum(byte[] old, byte[] now, int pos, int start, int end, List<int[]> pseudo) {
        if (pos + 2 > now.length) return;
        long value = (~word(old, pos, pos + 2)) & 0xffff;
        List<int[]> ranges = new ArrayList<>();
        ranges.add(new int[] {start, Math.min(end, old.length)});
        ranges.addAll(pseudo);
        for (int[] r : ranges) {
            int a = r[0];
            int b = r[1];
            for (int i = a; i < b; i += 2) {
                if (i == pos) continue;
                int before = word(old, i, Math.min(i + 2, b));
                int after = word(now, i, Math.min(i + 2, b));
                value += ((~before) & 0xffff) + after;
            }
        }
        while ((value >> 16) != 0) value = (value & 0xffff) + (value >> 16);
        int result = (int) ((~value) & 0xffff);
        now[pos] = (byte) (result >> 8);
        now[pos + 1] = (byte) result;
    }

    /** Python {@code int.from_bytes(data[i:j].ljust(2, b"\0"), "big")}: bytes past the end count as zero. */
    private static int word(byte[] data, int i, int j) {
        int hi = i < data.length && i < j ? data[i] & 0xff : 0;
        int lo = i + 1 < data.length && i + 1 < j ? data[i + 1] & 0xff : 0;
        return (hi << 8) | lo;
    }

    // ---- neXus ---------------------------------------------------------------------------------------------

    /** Header spans of the neXus model: like the reference, clamped to the captured bytes, never refused. */
    private record Model(List<Layer> layers, List<int[]> keep, List<Checksum> checksums, int end) {
    }

    private Model model(byte[] raw, int linkType) {
        List<Layer> protocols = Layers.decode(raw, linkType, Layers.Variant.NEXUS);
        List<int[]> keep = new ArrayList<>();
        List<Checksum> checksums = new ArrayList<>();
        Layer network = null;
        for (int index = 0; index < protocols.size(); index++) {
            Layer p = protocols.get(index);
            int size;
            if (p.name.equals("ip")) {
                size = Math.max(p.hdrLen, 0);
                network = p;
            } else if (p.name.equals("ipv6")) {
                size = 40;
                network = p;
            } else if (p.name.equals("arp")) {
                size = p.size;
                network = p;
            } else if (p.name.equals("tcp")) {
                size = p.hdrLen >= 0 ? p.hdrLen : Math.min(p.size, p.quoted ? 8 : 20);
            } else if (p.isL4()) {
                size = 8;
            } else {
                size = p.size;
            }
            int from = Math.min(p.pos, raw.length);
            int to = Math.min(p.pos + size, raw.length);
            if (to > from) keep.add(new int[] {from, to});
            if (p.checksum >= 0 && (network != null || p.name.equals("ip"))) checksums.add(new Checksum(p, network, p.checksum));
        }
        int end = 0;
        for (int[] k : keep) end = Math.max(end, k[1]);
        return new Model(protocols, keep, checksums, end);
    }

    private byte[] nexusFrame(byte[] raw, int linkType) {
        Model m = model(raw, linkType);
        byte[] output = new byte[raw.length];
        long kept = 0;
        boolean[] covered = new boolean[raw.length];
        for (int[] k : m.keep()) {
            System.arraycopy(raw, k[0], output, k[0], k[1] - k[0]);
            for (int i = k[0]; i < k[1]; i++) covered[i] = true;
        }
        for (Layer l : m.layers()) {
            for (int[] z : l.zeroSpans) {
                int zFrom = Math.max(0, Math.min(z[0], output.length));
                int zTo = Math.max(zFrom, Math.min(z[1], output.length));
                Arrays.fill(output, zFrom, zTo, (byte) 0);
                for (int i = zFrom; i < zTo; i++) covered[i] = false;
            }
        }
        boolean hasIp = false;
        for (Layer l : m.layers()) {
            if (l.isNetwork()) hasIp = true;
            for (Field f : l.fields) {
                if (f.pos() + f.size() > raw.length || !allCovered(covered, f.pos(), f.size())) continue;
                byte[] value = Arrays.copyOfRange(raw, f.pos(), f.pos() + f.size());
                if (MAC_FIELDS.contains(f.name()) && f.size() == 6) {
                    byte[] mapped = mac.map(value);
                    System.arraycopy(mapped, 0, output, f.pos(), 6);
                    if (!Arrays.equals(mapped, value)) {
                        sink.record(new VaultKey(VaultKind.MAC, MacText.canonical(mapped)), MacText.canonical(value));
                        counts.add("mac");
                    }
                } else if (f.size() == 4 || f.size() == 16) {
                    mapAddress(value, output, f.pos());
                }
            }
            // ICMP redirect: the gateway address sits inside the eight kept ICMP bytes.
            if (l.name.equals("icmp") && l.pos + 8 <= raw.length && (raw[l.pos] & 0xff) == 5) {
                mapAddress(Arrays.copyOfRange(raw, l.pos + 4, l.pos + 8), output, l.pos + 4);
            }
        }
        if (!hasIp) counts.add(FRAMES_LINK_ONLY);
        int[] message = dnsMessage(m.layers(), raw);
        if (message != null) {
            byte[] copy = Arrays.copyOfRange(raw, message[0], message[1]);
            if (dns.sanitize(copy)) {
                System.arraycopy(copy, 0, output, message[0], copy.length);
                for (int i = message[0]; i < message[1]; i++) covered[i] = true;
            }
        }
        for (boolean c : covered) if (c) kept++;
        counts.add(PAYLOAD_BYTES_ZEROED, raw.length - kept);
        checksums(raw, output, m.checksums());
        return output;
    }

    private void mapAddress(byte[] value, byte[] output, int pos) {
        byte[] mapped = ip.map(value);
        System.arraycopy(mapped, 0, output, pos, mapped.length);
        if (!Arrays.equals(mapped, value)) {
            sink.record(new VaultKey(VaultKind.ADDRESS, Addresses.format(mapped)), Addresses.format(value));
            counts.add(value.length == 4 ? "ipv4" : "ipv6");
        }
    }

    /** {start, end} of a whole DNS message carried by the frame's (outer, unquoted) UDP or TCP, or null. */
    private static int[] dnsMessage(List<Layer> layers, byte[] raw) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer l = layers.get(i);
            if (l.quoted || !(l.name.equals("udp") || l.name.equals("tcp"))) continue;
            if (l.pos + 8 > raw.length) return null;
            int sport = ((raw[l.pos] & 0xff) << 8) | (raw[l.pos + 1] & 0xff);
            int dport = ((raw[l.pos + 2] & 0xff) << 8) | (raw[l.pos + 3] & 0xff);
            if (!isDnsPort(sport) && !isDnsPort(dport)) return null;
            if (l.name.equals("udp")) {
                int length = ((raw[l.pos + 4] & 0xff) << 8) | (raw[l.pos + 5] & 0xff);
                int end = Math.min(raw.length, l.pos + Math.max(length, 8));
                return end - (l.pos + 8) >= 12 ? new int[] {l.pos + 8, end} : null;
            }
            if (l.hdrLen < 20) return null;
            int start = l.pos + l.hdrLen;
            if (start + 2 > raw.length) return null;
            int length = ((raw[start] & 0xff) << 8) | (raw[start + 1] & 0xff);
            // Only a message wholly inside this segment; a split one stays zeroed.
            return length >= 12 && start + 2 + length <= raw.length ? new int[] {start + 2, start + 2 + length} : null;
        }
        return null;
    }

    private static boolean isDnsPort(int port) {
        return port == 53 || port == 5353 || port == 5355;
    }

    // ---- revert --------------------------------------------------------------------------------------------

    private byte[] revertFrame(byte[] raw, int linkType) {
        Model m = model(raw, linkType);
        byte[] output = raw.clone();
        for (Layer l : m.layers()) {
            for (Field f : l.fields) {
                if (f.pos() + f.size() > raw.length) continue;
                byte[] value = Arrays.copyOfRange(raw, f.pos(), f.pos() + f.size());
                String original;
                byte[] bytes = null;
                if (MAC_FIELDS.contains(f.name()) && f.size() == 6) {
                    original = resolved.get(new VaultKey(VaultKind.MAC, MacText.canonical(value)));
                    if (original != null) {
                        try {
                            bytes = MacText.parse(original);
                        } catch (IllegalArgumentException ignored) {
                            bytes = null;
                        }
                    }
                } else if (f.size() == 4 || f.size() == 16) {
                    original = resolved.get(new VaultKey(VaultKind.ADDRESS, Addresses.format(value)));
                    if (original != null) {
                        try {
                            bytes = Addresses.parse(original);
                        } catch (IllegalArgumentException ignored) {
                            bytes = null;
                        }
                    }
                }
                if (bytes != null && bytes.length == f.size()) {
                    System.arraycopy(bytes, 0, output, f.pos(), bytes.length);
                    counts.add("reverted");
                }
            }
            if (l.name.equals("icmp") && l.pos + 8 <= raw.length && (raw[l.pos] & 0xff) == 5) {
                String original = resolved.get(new VaultKey(VaultKind.ADDRESS,
                        Addresses.format(Arrays.copyOfRange(raw, l.pos + 4, l.pos + 8))));
                byte[] bytes = original == null ? null : Addresses.parseV4(original);
                if (bytes != null) System.arraycopy(bytes, 0, output, l.pos + 4, 4);
            }
        }
        int[] message = dnsMessage(m.layers(), raw);
        if (message != null) dns.revert(output, message[0], message[1], resolved);
        checksums(raw, output, m.checksums());
        return output;
    }

    private static byte[] sha256(String text) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
