package com.securityexpert.nexus.ui2.sanitizer.core;

import javax.crypto.Mac;

/**
 * MAC pseudonyms. {@link #nexus(SanitizerKey)} keeps the OUI (the vendor stays visible, DATA_SANITIZER_CONTRACT.md
 * §2) and replaces the NIC half with HMAC bytes; group addresses (broadcast, multicast, STP and the like: I/G bit
 * set) and the all-zero address describe protocol scope, not a device, and are kept. {@link #reference(byte[])} is
 * PacketBreaker's {@code Mapper.mac}: six HMAC bytes, locally administered, the I/G bit of the input kept.
 */
public final class MacMapper {

    private final boolean reference;
    private final ThreadLocal<Mac> mac;

    private MacMapper(byte[] key, boolean reference) {
        byte[] copy = key.clone();
        this.reference = reference;
        this.mac = ThreadLocal.withInitial(() -> SanitizerKey.mac(copy));
    }

    public static MacMapper nexus(SanitizerKey key) {
        return new MacMapper(key.subKey("mac"), false);
    }

    public static MacMapper reference(byte[] rawKey) {
        return new MacMapper(rawKey, true);
    }

    public byte[] map(byte[] address) {
        if (address.length != 6) throw new IllegalArgumentException("a MAC has 6 bytes");
        Mac m = mac.get();
        if (reference) {
            m.update(new byte[] {'m', 'a', 'c'});
            byte[] digest = m.doFinal(address);
            byte[] out = java.util.Arrays.copyOf(digest, 6);
            out[0] = (byte) ((out[0] & 252) | 2 | (address[0] & 1));
            return out;
        }
        if (isKept(address)) return address.clone();
        byte[] digest = m.doFinal(address);
        byte[] out = address.clone();
        out[3] = digest[0];
        out[4] = digest[1];
        out[5] = digest[2];
        return out;
    }

    /** Group (I/G) addresses and 00:00:00:00:00:00 are not device identities and stay as they are. */
    public boolean isKept(byte[] address) {
        if ((address[0] & 1) != 0) return true;
        for (byte b : address) {
            if (b != 0) return false;
        }
        return true;
    }
}
