package com.securityexpert.nexus.ui2.sanitizer.pcap;

import java.util.ArrayList;
import java.util.List;

/**
 * A native decoder for exactly the headers PacketBreaker's anonymizer keeps (its {@code HEADERS} set plus TCP, UDP,
 * ICMP and ICMPv6), producing what tshark's PDML gives the reference for them: each protocol's position and size and
 * the address, checksum and length fields the anonymizer reads. Decoding follows tshark's choices -- VXLAN on UDP
 * 4789, Geneve on 6081, ERSPAN II/III on GRE 0x88BE/0x22EB, the IPv4/IPv6 header quoted in an ICMP error, no L4
 * after a non-first fragment, the payload bounded by the IP total length -- and stops at the first protocol outside
 * the set, whose bytes are then payload.
 *
 * <p>{@link Variant#NEXUS} additionally decodes 802.1ad/QinQ tags, keeps IPv6 routing-header addresses addressable
 * and never refuses a frame; {@link Variant#REFERENCE} is the parity decoder.</p>
 */
final class Layers {

    enum Variant { REFERENCE, NEXUS }

    record Field(String name, int pos, int size) {
    }

    static final class Layer {
        final String name;
        final int pos;
        int size;
        int hdrLen = -1;
        int ipLen = -1;
        int plen = -1;
        int checksum = -1;
        boolean quoted;
        final List<Field> fields = new ArrayList<>();

        Layer(String name, int pos, int size) {
            this.name = name;
            this.pos = pos;
            this.size = size;
        }

        boolean isL4() {
            return name.equals("tcp") || name.equals("udp") || name.equals("icmp") || name.equals("icmpv6");
        }

        boolean isNetwork() {
            return name.equals("ip") || name.equals("ipv6") || name.equals("arp");
        }

        @Override
        public String toString() {
            return name + "@" + pos + "+" + size;
        }
    }

    /** Link types this decoder starts from. */
    static boolean supportedLinkType(int linkType) {
        return switch (linkType) {
            case 0, 1, 12, 14, 101, 108, 113, 228, 229, 276 -> true;
            default -> false;
        };
    }

    private final byte[] f;
    private final Variant variant;
    private final List<Layer> out = new ArrayList<>();

    private Layers(byte[] frame, Variant variant) {
        this.f = frame;
        this.variant = variant;
    }

    static List<Layer> decode(byte[] frame, int linkType, Variant variant) {
        Layers d = new Layers(frame, variant);
        try {
            d.link(linkType);
        } catch (IndexOutOfBoundsException truncated) {
            // A header cut by the snap length ends decoding; the caller sees what was decoded.
        }
        return d.out;
    }

    private int u8(int at) {
        return f[at] & 0xff;
    }

    private int u16(int at) {
        return ((f[at] & 0xff) << 8) | (f[at + 1] & 0xff);
    }

    private boolean has(int at, int n) {
        return at >= 0 && at + n <= f.length;
    }

    private void link(int linkType) {
        switch (linkType) {
            case 1 -> eth(0, f.length);
            case 113 -> sll(0);
            case 276 -> sll2(0);
            case 0 -> nullLink(0, false);
            case 108 -> nullLink(0, true);
            case 101, 12, 14, 228, 229 -> rawIp(0, f.length);
            default -> {
            }
        }
    }

    private void rawIp(int pos, int limit) {
        if (!has(pos, 1)) return;
        int version = u8(pos) >> 4;
        if (version == 4) ip(pos, limit, false);
        else if (version == 6) ipv6(pos, limit, false);
    }

    private void eth(int pos, int limit) {
        Layer l = add("eth", pos, 14);
        if (has(pos, 6)) l.fields.add(new Field("eth.dst", pos, 6));
        if (has(pos + 6, 6)) l.fields.add(new Field("eth.src", pos + 6, 6));
        if (!has(pos + 12, 2)) return;
        ethertype(u16(pos + 12), pos + 14, limit);
    }

    private void ethertype(int type, int pos, int limit) {
        switch (type) {
            case 0x0800 -> ip(pos, limit, false);
            case 0x86dd -> ipv6(pos, limit, false);
            case 0x0806 -> arp(pos);
            case 0x8100 -> vlan(pos, limit);
            case 0x88a8, 0x9100 -> {
                if (variant == Variant.NEXUS) vlan(pos, limit);
            }
            default -> {
            }
        }
    }

    private void vlan(int pos, int limit) {
        add("vlan", pos, 4);
        if (!has(pos + 2, 2)) return;
        ethertype(u16(pos + 2), pos + 4, limit);
    }

    private void sll(int pos) {
        Layer l = add("sll", pos, 16);
        if (has(pos + 4, 2) && u16(pos + 4) == 6 && has(pos + 6, 6)) l.fields.add(new Field("sll.src.eth", pos + 6, 6));
        if (!has(pos + 14, 2)) return;
        ethertype(u16(pos + 14), pos + 16, f.length);
    }

    private void sll2(int pos) {
        Layer l = add("sll", pos, 20);
        if (has(pos + 11, 1) && u8(pos + 11) == 6 && has(pos + 12, 6)) l.fields.add(new Field("sll.src.eth", pos + 12, 6));
        if (!has(pos, 2)) return;
        ethertype(u16(pos), pos + 20, f.length);
    }

    private void nullLink(int pos, boolean networkOrder) {
        add(networkOrder ? "loop" : "null", pos, 4);
        if (!has(pos, 4)) return;
        long be = ((long) u16(pos) << 16) | u16(pos + 2);
        long le = ((long) u8(pos + 3) << 24) | ((long) u8(pos + 2) << 16) | ((long) u8(pos + 1) << 8) | u8(pos);
        long family = networkOrder ? be : (le <= 0xffff ? le : be);
        if (family == 2) ip(pos + 4, f.length, false);
        else if (family == 24 || family == 28 || family == 30) ipv6(pos + 4, f.length, false);
    }

    private void arp(int pos) {
        if (!has(pos, 8)) {
            add("arp", pos, 28);
            return;
        }
        int ptype = u16(pos + 2);
        int h = u8(pos + 4);
        int p = u8(pos + 5);
        Layer l = add("arp", pos, 8 + 2 * h + 2 * p);
        if (h == 6) {
            l.fields.add(new Field("arp.src.hw_mac", pos + 8, 6));
            l.fields.add(new Field("arp.dst.hw_mac", pos + 8 + h + p, 6));
        }
        if (p == 4 && ptype == 0x0800) {
            l.fields.add(new Field("arp.src.proto_ipv4", pos + 8 + h, 4));
            l.fields.add(new Field("arp.dst.proto_ipv4", pos + 8 + 2 * h + p, 4));
        }
    }

    private void ip(int pos, int limit, boolean quoted) {
        Layer l = add("ip", pos, 20);
        l.quoted = quoted;
        int ihl = (u8(pos) & 0x0f) * 4;
        l.hdrLen = ihl;
        l.size = ihl;
        if (has(pos + 2, 2)) l.ipLen = u16(pos + 2);
        if (has(pos + 10, 2)) l.checksum = pos + 10;
        if (has(pos + 12, 4)) l.fields.add(new Field("ip.src", pos + 12, 4));
        if (has(pos + 16, 4)) l.fields.add(new Field("ip.dst", pos + 16, 4));
        if ((u8(pos) >> 4) != 4 || ihl < 20 || !has(pos + 6, 4)) return;
        int fragmentOffset = ((u8(pos + 6) & 0x1f) << 8) | u8(pos + 7);
        if (fragmentOffset != 0) return;
        int innerLimit = l.ipLen >= ihl ? Math.min(limit, pos + l.ipLen) : limit;
        transport(u8(pos + 9), pos + ihl, innerLimit, quoted);
    }

    private void ipv6(int pos, int limit, boolean quoted) {
        Layer l = add("ipv6", pos, 40);
        l.quoted = quoted;
        if (has(pos + 4, 2)) l.plen = u16(pos + 4);
        if (has(pos + 8, 16)) l.fields.add(new Field("ipv6.src", pos + 8, 16));
        if (has(pos + 24, 16)) l.fields.add(new Field("ipv6.dst", pos + 24, 16));
        if (!has(pos + 6, 1)) return;
        int innerLimit = l.plen >= 0 ? Math.min(limit, pos + 40 + l.plen) : limit;
        int next = u8(pos + 6);
        int at = pos + 40;
        while (true) {
            if (next == 0 || next == 60 || next == 43) {
                if (!has(at, 2)) {
                    add(next == 0 ? "ipv6.hopopts" : next == 60 ? "ipv6.dstopts" : "ipv6.routing", at, 8);
                    return;
                }
                int size = (u8(at + 1) + 1) * 8;
                Layer ext = add(next == 0 ? "ipv6.hopopts" : next == 60 ? "ipv6.dstopts" : "ipv6.routing", at, size);
                if (next == 43 && has(at + 2, 2)) {
                    int type = u8(at + 2);
                    int first = type == 4 ? at + 8 : (type == 0 || type == 2) ? at + 8 : -1;
                    if (first > 0) {
                        for (int a = first; a + 16 <= at + size; a += 16) ext.fields.add(new Field("ipv6.routing.addr", a, 16));
                    }
                }
                next = u8(at);
                at += size;
            } else if (next == 44) {
                add("ipv6.fraghdr", at, 8);
                if (!has(at, 4)) return;
                int offset = u16(at + 2) >> 3;
                next = u8(at);
                at += 8;
                if (offset != 0) return;
            } else {
                break;
            }
        }
        transport(next, at, innerLimit, quoted);
    }

    private void transport(int protocol, int pos, int limit, boolean quoted) {
        if (pos >= f.length) return;
        switch (protocol) {
            case 6 -> tcp(pos, limit, quoted);
            case 17 -> udp(pos, limit, quoted);
            case 1 -> icmp(pos, limit, quoted);
            case 58 -> icmpv6(pos, limit, quoted);
            case 47 -> gre(pos, limit);
            case 4 -> ip(pos, limit, quoted);
            case 41 -> ipv6(pos, limit, quoted);
            default -> {
            }
        }
    }

    private void tcp(int pos, int limit, boolean quoted) {
        int available = Math.min(limit, f.length) - pos;
        Layer l = add("tcp", pos, Math.max(0, available));
        l.quoted = quoted;
        if (available >= 13) {
            l.hdrLen = (u8(pos + 12) >> 4) * 4;
            l.size = l.hdrLen;
        }
        if (available >= 18) l.checksum = pos + 16;
    }

    private void udp(int pos, int limit, boolean quoted) {
        Layer l = add("udp", pos, 8);
        l.quoted = quoted;
        if (!has(pos, 8)) return;
        l.checksum = pos + 6;
        if (quoted) return;
        int length = u16(pos + 4);
        int innerLimit = length >= 8 ? Math.min(limit, pos + length) : limit;
        int sport = u16(pos);
        int dport = u16(pos + 2);
        if (dport == 4789 || sport == 4789) vxlan(pos + 8, innerLimit);
        else if (dport == 6081 || sport == 6081) geneve(pos + 8, innerLimit);
    }

    private void icmp(int pos, int limit, boolean quoted) {
        Layer l = add("icmp", pos, 8);
        l.quoted = quoted;
        if (has(pos + 2, 2)) l.checksum = pos + 2;
        if (quoted || !has(pos, 1)) return;
        int type = u8(pos);
        if ((type == 3 || type == 4 || type == 5 || type == 11 || type == 12) && has(pos + 8, 1)) {
            if ((u8(pos + 8) >> 4) == 4) ip(pos + 8, limit, true);
        }
    }

    private void icmpv6(int pos, int limit, boolean quoted) {
        Layer l = add("icmpv6", pos, 8);
        l.quoted = quoted;
        if (has(pos + 2, 2)) l.checksum = pos + 2;
        if (quoted || !has(pos, 1)) return;
        int type = u8(pos);
        if (type >= 1 && type <= 4 && has(pos + 8, 1) && (u8(pos + 8) >> 4) == 6) ipv6(pos + 8, limit, true);
    }

    private void gre(int pos, int limit) {
        if (!has(pos, 4)) {
            add("gre", pos, 4);
            return;
        }
        int flags = u16(pos);
        int size = 4 + ((flags & 0xC000) != 0 ? 4 : 0) + ((flags & 0x2000) != 0 ? 4 : 0) + ((flags & 0x1000) != 0 ? 4 : 0);
        Layer l = add("gre", pos, size);
        if ((flags & 0x8000) != 0 && has(pos + 4, 2)) l.checksum = pos + 4;
        if ((flags & 0x7) != 0) return;
        int type = u16(pos + 2);
        int next = pos + size;
        switch (type) {
            case 0x0800 -> ip(next, limit, false);
            case 0x86dd -> ipv6(next, limit, false);
            case 0x6558 -> eth(next, limit);
            case 0x88be -> {
                if ((flags & 0x1000) != 0) {
                    add("erspan", next, 8);
                    eth(next + 8, limit);
                } else if (variant == Variant.NEXUS) {
                    eth(next, limit);
                }
            }
            case 0x22eb -> {
                int size3 = 12 + (has(next + 11, 1) && (u8(next + 11) & 1) != 0 ? 8 : 0);
                add("erspan", next, size3);
                eth(next + size3, limit);
            }
            default -> {
            }
        }
    }

    private void vxlan(int pos, int limit) {
        add("vxlan", pos, 8);
        eth(pos + 8, limit);
    }

    private void geneve(int pos, int limit) {
        int size = 8 + (has(pos, 1) ? (u8(pos) & 0x3f) * 4 : 0);
        add("geneve", pos, size);
        if (!has(pos + 2, 2)) return;
        switch (u16(pos + 2)) {
            case 0x6558 -> eth(pos + size, limit);
            case 0x0800 -> ip(pos + size, limit, false);
            case 0x86dd -> ipv6(pos + size, limit, false);
            default -> {
            }
        }
    }

    private Layer add(String name, int pos, int size) {
        Layer l = new Layer(name, pos, size);
        out.add(l);
        return l;
    }
}
