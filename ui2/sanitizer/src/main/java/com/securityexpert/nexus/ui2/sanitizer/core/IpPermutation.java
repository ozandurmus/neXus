package com.securityexpert.nexus.ui2.sanitizer.core;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;

/**
 * Keyed prefix-preserving address permutation (DATA_SANITIZER_CONTRACT.md §2): the Crypto-PAn tree construction with
 * HMAC-SHA256 as its PRF. Output bit {@code n} is input bit {@code n} XOR {@code f(n, prefix_n)}, where
 * {@code f = HMAC(key, "ip" || len || n || prefix_n)[0] >>> 7} and {@code prefix_n} is the input's first {@code n}
 * bits -- byte for byte the message PacketBreaker's {@code Mapper.ip} uses, so {@link #reference(byte[])} with no
 * special ranges <em>is</em> that function. Two addresses sharing exactly an n-bit prefix map to two addresses
 * sharing exactly an n-bit prefix, and the map is a bijection on each family.
 *
 * <p>Special ranges ({@link #nexus(byte[])}): a {@code KEEP} range maps onto itself point for point (loopback,
 * unspecified, multicast, limited broadcast and the reserved space that holds netmasks); a {@code KEEP_PREFIX} range
 * maps onto itself as a block while its interior is permuted (link-local; IPv4-mapped IPv6, whose interior is the
 * IPv4 permutation). Both are realised by forcing {@code f = 0} on the tree nodes that lead into the range, which
 * keeps the map prefix-preserving and bijective -- so no ordinary address can ever be mapped into a kept range. The
 * price is that the leading bits shared with a kept range survive (an address keeps its rough first-octet class).</p>
 */
public final class IpPermutation {

    /** A range the permutation must not move: {@code keepInterior} keeps every address, otherwise only the block. */
    public record Range(byte[] prefix, int bits, boolean keepInterior) {
        public Range {
            prefix = prefix.clone();
        }
    }

    public static final List<Range> NEXUS_V4 = List.of(
            keep("0.0.0.0", 8),       // "this network", 0.0.0.0, wildcard masks
            keep("127.0.0.0", 8),     // loopback
            keep("224.0.0.0", 4),     // multicast
            keep("240.0.0.0", 4),     // reserved: limited broadcast and netmasks /4../32
            block("169.254.0.0", 16)); // link-local: stays link-local, host part permuted
    public static final List<Range> NEXUS_V6 = List.of(
            keep("::", 128),          // unspecified
            keep("::1", 128),         // loopback
            keep("ff00::", 8),        // multicast
            block("fe80::", 10),      // link-local
            block("::ffff:0:0", 96)); // IPv4-mapped: interior goes through the IPv4 permutation

    private static final int CACHE = 1 << 16;

    private final byte[] key;
    private final List<Range> v4Ranges;
    private final List<Range> v6Ranges;
    private final boolean mapV4Interior;
    private final ThreadLocal<Mac> mac;
    private final Map<String, byte[]> cache = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > CACHE;
        }
    };

    private IpPermutation(byte[] key, List<Range> v4Ranges, List<Range> v6Ranges, boolean mapV4Interior) {
        this.key = key.clone();
        this.v4Ranges = v4Ranges;
        this.v6Ranges = v6Ranges;
        this.mapV4Interior = mapV4Interior;
        this.mac = ThreadLocal.withInitial(() -> SanitizerKey.mac(this.key));
    }

    /** The neXus mapping: the {@code ip} sub-key of the privacy key, with the special ranges above. */
    public static IpPermutation nexus(SanitizerKey key) {
        return new IpPermutation(key.subKey("ip"), NEXUS_V4, NEXUS_V6, true);
    }

    /** Test seam: the neXus construction over an explicit raw key. */
    public static IpPermutation nexus(byte[] rawKey) {
        return new IpPermutation(rawKey, NEXUS_V4, NEXUS_V6, true);
    }

    /** PacketBreaker's {@code Mapper(key).ip}: the raw key, no special ranges (parity mode). */
    public static IpPermutation reference(byte[] rawKey) {
        return new IpPermutation(rawKey, List.of(), List.of(), false);
    }

    /** Maps a 4- or 16-byte address; the input is never modified. */
    public byte[] map(byte[] address) {
        if (address.length != 4 && address.length != 16) {
            throw new IllegalArgumentException("an address has 4 or 16 bytes");
        }
        String cacheKey = java.util.HexFormat.of().formatHex(address);
        synchronized (cache) {
            byte[] hit = cache.get(cacheKey);
            if (hit != null) {
                return hit.clone();
            }
        }
        byte[] result;
        if (address.length == 16 && mapV4Interior && isV4Mapped(address)) {
            result = address.clone();
            byte[] inner = permute(Arrays.copyOfRange(address, 12, 16), v4Ranges);
            System.arraycopy(inner, 0, result, 12, 4);
        } else {
            result = permute(address, address.length == 4 ? v4Ranges : v6Ranges);
        }
        synchronized (cache) {
            cache.put(cacheKey, result.clone());
        }
        return result;
    }

    /** True when the address lies in a range this permutation keeps point for point. */
    public boolean isKept(byte[] address) {
        for (Range range : address.length == 4 ? v4Ranges : v6Ranges) {
            if (range.keepInterior() && matches(address, range.prefix(), range.bits())) {
                return true;
            }
        }
        if (address.length == 16 && mapV4Interior && isV4Mapped(address)) {
            return isKept(Arrays.copyOfRange(address, 12, 16));
        }
        return false;
    }

    static boolean isV4Mapped(byte[] a) {
        for (int i = 0; i < 10; i++) {
            if (a[i] != 0) return false;
        }
        return (a[10] & 0xff) == 0xff && (a[11] & 0xff) == 0xff;
    }

    private byte[] permute(byte[] original, List<Range> ranges) {
        int bits = original.length * 8;
        byte[] prefix = new byte[original.length];
        byte[] result = new byte[original.length];
        byte[] message = new byte[4 + original.length];
        message[0] = 'i';
        message[1] = 'p';
        message[2] = (byte) original.length;
        Mac m = mac.get();
        for (int n = 0; n < bits; n++) {
            int bit = (original[n >> 3] >> (7 - (n & 7))) & 1;
            int flip = 0;
            if (!forced(prefix, n, ranges)) {
                message[3] = (byte) n;
                System.arraycopy(prefix, 0, message, 4, prefix.length);
                m.update(message);
                flip = (m.doFinal()[0] & 0xff) >>> 7;
            }
            if ((bit ^ flip) != 0) {
                result[n >> 3] |= (byte) (0x80 >>> (n & 7));
            }
            if (bit != 0) {
                prefix[n >> 3] |= (byte) (0x80 >>> (n & 7));
            }
        }
        return result;
    }

    /** f is forced to 0 on a node whose prefix leads into a kept block, or lies inside a point-kept range. */
    private static boolean forced(byte[] prefix, int n, List<Range> ranges) {
        for (Range range : ranges) {
            if (n < range.bits()) {
                if (matches(prefix, range.prefix(), n)) return true;
            } else if (range.keepInterior() && matches(prefix, range.prefix(), range.bits())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(byte[] value, byte[] prefix, int bits) {
        for (int i = 0; i < bits; i++) {
            int a = (value[i >> 3] >> (7 - (i & 7))) & 1;
            int b = (prefix[i >> 3] >> (7 - (i & 7))) & 1;
            if (a != b) return false;
        }
        return true;
    }

    private static Range keep(String address, int bits) {
        return new Range(Addresses.parse(address), bits, true);
    }

    private static Range block(String address, int bits) {
        return new Range(Addresses.parse(address), bits, false);
    }
}
