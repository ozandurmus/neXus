package com.securityexpert.nexus.ui2.service.zonematrix;

import java.math.BigInteger;
import java.util.Comparator;

/**
 * One IPv4 or IPv6 CIDR block of a zone (ZONE_MATRIX_CONTRACT.md section 3.1, FROZEN 2026-11-13).
 *
 * <p>Parsed without any name resolution. A block must be canonical: host bits zero ({@code 192.0.2.7/24} is refused, not
 * corrected). A bare address is not accepted; a single address is written {@code /32} or {@code /128}. IPv6 text is
 * accepted in any valid RFC 4291 form without an embedded IPv4 part and written back in the RFC 5952 form.</p>
 */
public record ZoneBlock(Family family, BigInteger start, int prefix) {
    public enum Family {
        IPV4(32), IPV6(128);
        public final int bits;
        Family(int bits) { this.bits = bits; }
        public BigInteger max() { return BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE); }
    }

    public static final Comparator<ZoneBlock> ORDER = Comparator.comparing(ZoneBlock::family)
            .thenComparing(ZoneBlock::start).thenComparingInt(ZoneBlock::prefix);

    public ZoneBlock {
        if (prefix < 0 || prefix > family.bits) throw new IllegalArgumentException("prefix length out of range");
        if (!start.and(hostMask(family, prefix)).equals(BigInteger.ZERO)) throw new IllegalArgumentException("host bits are set");
    }

    /** The last address of the block. */
    public BigInteger end() { return start.or(hostMask(family, prefix)); }

    /** Number of addresses in the block. */
    public BigInteger size() { return BigInteger.ONE.shiftLeft(family.bits - prefix); }

    public boolean contains(ZoneBlock other) {
        return family == other.family && prefix <= other.prefix && start.compareTo(other.start) <= 0 && end().compareTo(other.end()) >= 0;
    }

    private static BigInteger hostMask(Family family, int prefix) {
        return BigInteger.ONE.shiftLeft(family.bits - prefix).subtract(BigInteger.ONE);
    }

    /** Parses one canonical block, or throws {@link IllegalArgumentException} with a short reason (never echoing input). */
    public static ZoneBlock parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 49) throw new IllegalArgumentException("not a CIDR block");
        int slash = text.indexOf('/');
        if (slash < 0 || slash != text.lastIndexOf('/')) throw new IllegalArgumentException("a block needs exactly one prefix length, for example /24 or /128");
        String address = text.substring(0, slash), length = text.substring(slash + 1);
        if (!length.matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("invalid prefix length");
        int prefix = Integer.parseInt(length);
        Family family = address.indexOf(':') >= 0 ? Family.IPV6 : Family.IPV4;
        BigInteger start = family == Family.IPV4 ? ipv4(address) : ipv6(address);
        if (prefix > family.bits) throw new IllegalArgumentException("prefix length out of range");
        if (!start.and(hostMask(family, prefix)).equals(BigInteger.ZERO))
            throw new IllegalArgumentException("host bits are set; write the network address of the block");
        return new ZoneBlock(family, start, prefix);
    }

    /** Parses one address (no prefix) as a {@code /32} or {@code /128} block; used by the address lookup. */
    public static ZoneBlock address(String text) {
        if (text == null || text.isEmpty() || text.length() > 45 || text.indexOf('/') >= 0) throw new IllegalArgumentException("not an address");
        return text.indexOf(':') >= 0 ? new ZoneBlock(Family.IPV6, ipv6(text), 128) : new ZoneBlock(Family.IPV4, ipv4(text), 32);
    }

    private static BigInteger ipv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("invalid IPv4 address");
        long value = 0;
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("invalid IPv4 address");
            int octet = Integer.parseInt(part);
            if (octet > 255) throw new IllegalArgumentException("invalid IPv4 address");
            value = value << 8 | octet;
        }
        return BigInteger.valueOf(value);
    }

    private static BigInteger ipv6(String text) {
        if (!text.matches("[0-9A-Fa-f:]+")) throw new IllegalArgumentException("invalid IPv6 address (embedded IPv4 and zone ids are not accepted)");
        int gap = text.indexOf("::");
        if (gap >= 0 && text.indexOf("::", gap + 1) >= 0) throw new IllegalArgumentException("invalid IPv6 address");
        String[] head, tail;
        if (gap >= 0) {
            head = groups(text.substring(0, gap));
            tail = groups(text.substring(gap + 2));
            if (head.length + tail.length > 7) throw new IllegalArgumentException("invalid IPv6 address");
        } else {
            head = groups(text);
            tail = new String[0];
            if (head.length != 8) throw new IllegalArgumentException("invalid IPv6 address");
        }
        BigInteger value = BigInteger.ZERO;
        for (String group : head) value = value.shiftLeft(16).or(group(group));
        value = value.shiftLeft(16 * (8 - head.length - tail.length));
        for (String group : tail) value = value.shiftLeft(16).or(group(group));
        return value;
    }

    private static String[] groups(String part) {
        if (part.isEmpty()) return new String[0];
        String[] groups = part.split(":", -1);
        for (String group : groups) if (group.isEmpty()) throw new IllegalArgumentException("invalid IPv6 address");
        return groups;
    }

    private static BigInteger group(String group) {
        if (group.length() > 4) throw new IllegalArgumentException("invalid IPv6 address");
        return BigInteger.valueOf(Integer.parseInt(group, 16));
    }

    /** Canonical text: dotted quad, or RFC 5952 IPv6 (lower case, longest zero run of two or more groups compressed). */
    public static String addressText(Family family, BigInteger value) {
        if (family == Family.IPV4) {
            long v = value.longValueExact();
            return (v >>> 24) + "." + ((v >>> 16) & 255) + "." + ((v >>> 8) & 255) + "." + (v & 255);
        }
        int[] groups = new int[8];
        for (int i = 7; i >= 0; i--) { groups[i] = value.and(BigInteger.valueOf(0xffff)).intValue(); value = value.shiftRight(16); }
        int bestStart = -1, bestLength = 0;
        for (int i = 0; i < 8; ) {
            if (groups[i] != 0) { i++; continue; }
            int j = i;
            while (j < 8 && groups[j] == 0) j++;
            if (j - i > bestLength) { bestStart = i; bestLength = j - i; }
            i = j;
        }
        if (bestLength < 2) bestStart = -1;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) { out.append(i == 0 ? "::" : ":"); i += bestLength - 1; continue; }
            out.append(Integer.toHexString(groups[i]));
            if (i < 7) out.append(':');
        }
        return out.toString();
    }

    @Override public String toString() { return addressText(family, start) + "/" + prefix; }
}
