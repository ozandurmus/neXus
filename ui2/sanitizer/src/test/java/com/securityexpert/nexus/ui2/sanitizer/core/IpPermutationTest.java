package com.securityexpert.nexus.ui2.sanitizer.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.sanitizer.TestVault;

/** Property tests of the prefix-preserving permutation (DATA_SANITIZER_CONTRACT.md §2 "IP addresses"). */
class IpPermutationTest {

    private final IpPermutation ip = IpPermutation.nexus(SanitizerKey.of(TestVault.key()));
    private final Random random = new Random(20261016L);

    @Test
    void sharedPrefixLengthIsPreservedExactlyForRandomPairs() {
        for (int family : new int[] {4, 16}) {
            for (int i = 0; i < 4000; i++) {
                byte[] a = randomAddress(family);
                byte[] b = a.clone();
                int flipFrom = random.nextInt(family * 8);
                // Keep the first flipFrom bits, flip bit flipFrom, randomise the rest: shared prefix = flipFrom.
                b[flipFrom >> 3] ^= (byte) (0x80 >>> (flipFrom & 7));
                for (int bit = flipFrom + 1; bit < family * 8; bit++) {
                    if (random.nextBoolean()) b[bit >> 3] ^= (byte) (0x80 >>> (bit & 7));
                }
                assertEquals(sharedPrefix(a, b), sharedPrefix(ip.map(a), ip.map(b)),
                        "shared prefix length must survive exactly (family " + family + ")");
            }
        }
    }

    @Test
    void theMapIsABijectionOnASubnetAndDeterministic() {
        Set<String> seen = new HashSet<>();
        byte[] base = Addresses.parse("198.51.100.0");
        for (int i = 0; i < 256; i++) {
            byte[] a = base.clone();
            a[3] = (byte) i;
            byte[] mapped = ip.map(a);
            assertArrayEquals(mapped, IpPermutation.nexus(SanitizerKey.of(TestVault.key())).map(a));
            assertTrue(seen.add(Addresses.format(mapped)), "no two addresses share a pseudonym");
        }
        assertEquals(1, seen.stream().map(s -> s.substring(0, s.lastIndexOf('.'))).distinct().count(),
                "a /24 maps onto one /24");
    }

    @Test
    void keptRangesStayAndNothingElseLandsInThem() {
        for (String kept : new String[] {"0.0.0.0", "0.1.2.3", "127.0.0.1", "127.9.9.9", "224.0.0.5", "239.1.2.3",
                "240.0.0.1", "255.255.255.255", "255.255.255.0", "::", "::1", "ff02::1", "ff0e::abcd"}) {
            byte[] a = Addresses.parse(kept);
            assertArrayEquals(a, ip.map(a), kept);
            assertTrue(ip.isKept(a));
        }
        for (int i = 0; i < 20000; i++) {
            byte[] a = randomAddress(4);
            if (ip.isKept(a)) continue;
            assertFalse(ip.isKept(ip.map(a)), "an ordinary IPv4 address never maps into a kept range");
            byte[] v6 = randomAddress(16);
            if (ip.isKept(v6) || IpPermutation.isV4Mapped(v6)) continue;
            byte[] m6 = ip.map(v6);
            assertFalse(ip.isKept(m6));
            assertFalse(IpPermutation.isV4Mapped(m6), "nothing maps into ::ffff:0:0/96");
        }
    }

    @Test
    void linkLocalBlocksStayLinkLocalAndMappedV4FollowsTheV4Map() {
        byte[] ll = Addresses.parse("169.254.10.20");
        byte[] mapped = ip.map(ll);
        assertEquals((byte) 169, mapped[0]);
        assertEquals((byte) 254, mapped[1]);
        assertFalse(java.util.Arrays.equals(ll, mapped));
        byte[] v6ll = ip.map(Addresses.parse("fe80::1234"));
        assertEquals((byte) 0xfe, v6ll[0]);
        assertEquals(0x80, v6ll[1] & 0xc0);
        byte[] v4 = Addresses.parse("192.0.2.33");
        byte[] v4mapped = Addresses.parse("::ffff:192.0.2.33");
        assertArrayEquals(ip.map(v4), java.util.Arrays.copyOfRange(ip.map(v4mapped), 12, 16));
    }

    @Test
    void textForms() {
        assertEquals("2001:db8::1", Addresses.formatV6(Addresses.parse("2001:0DB8:0:0:0:0:0:1"), Addresses.V6Style.CANONICAL));
        assertEquals("2001:db8:0:1:1:1:1:1", Addresses.format(Addresses.parse("2001:db8:0:1:1:1:1:1")));
        assertEquals("::ffff:192.0.2.1", Addresses.formatV6(Addresses.parse("::ffff:192.0.2.1"), new Addresses.V6Style(false, false, true)));
        assertEquals("0000:0000:0000:0000:0000:ffff:192.0.2.1",
                Addresses.formatV6(Addresses.parse("::ffff:192.0.2.1"), Addresses.V6Style.of("0000:0000:0000:0000:0000:ffff:1.2.3.4")));
        for (String bad : new String[] {"1:2:3:4:5:6:7", "1::2::3", "12345::", ":1:2", "1.2.3", "1.2.3.4.5", "::ffff:1.2.3.256"}) {
            assertTrue(Addresses.parseV6(bad) == null && Addresses.parseV4(bad) == null, bad);
        }
    }

    private byte[] randomAddress(int family) {
        byte[] a = new byte[family];
        random.nextBytes(a);
        return a;
    }

    private static int sharedPrefix(byte[] a, byte[] b) {
        BigInteger x = new BigInteger(1, a).xor(new BigInteger(1, b));
        return a.length * 8 - x.bitLength();
    }
}
