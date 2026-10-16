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
        IpPermutation ref = IpPermutation.reference(TestVault.key());
        for (int family : new int[] {4, 16}) {
            int minSubnetBits = family == 4 ? 8 : 10;
            for (int i = 0; i < 4000; i++) {
                byte[] a = randomUnicastAddress(family);
                byte[] b = a.clone();
                int flipFrom = random.nextInt(family * 8);
                // Keep the first flipFrom bits, flip bit flipFrom, randomise the rest: shared prefix = flipFrom.
                b[flipFrom >> 3] ^= (byte) (0x80 >>> (flipFrom & 7));
                for (int bit = flipFrom + 1; bit < family * 8; bit++) {
                    if (random.nextBoolean()) b[bit >> 3] ^= (byte) (0x80 >>> (bit & 7));
                }
                assertEquals(sharedPrefix(a, b), sharedPrefix(ref.map(a), ref.map(b)),
                        "reference mode preserves shared prefix across all bits");
                if ( isSpecial(b) ) continue;
                int actual = sharedPrefix(ip.map(a), ip.map(b));
                if (flipFrom >= minSubnetBits) {
                    assertEquals(flipFrom, actual,
                            "nexus mode preserves exact shared subnet prefix length (family " + family + ")");
                } else {
                    assertTrue(actual < minSubnetBits,
                            "addresses in distinct top-level blocks stay in distinct top-level blocks");
                }
            }
        }
    }

    @Test
    void unicastFirstOctetIsUniformlyDistributedAndLeaksNoPrefixBits() {
        // 1. Across all 222 unicast IPv4 first octets (1..126, 128..223), mapping must be a bijection on /8s
        //    and must not pin RFC 1918 / classful leading bits.
        Set<Integer> outputOctets = new HashSet<>();
        int[] bucketCounts = new int[8]; // 8 buckets across the 256 octet space
        int samples = 22200;
        for (int i = 0; i < samples; i++) {
            byte[] a = randomUnicastAddress(4);
            byte[] mapped = ip.map(a);
            int firstOctet = mapped[0] & 0xff;
            assertFalse(ip.isKept(mapped));
            outputOctets.add(firstOctet);
            bucketCounts[firstOctet >>> 5]++;
        }
        assertEquals(222, outputOctets.size(), "all 222 unicast first octets must be reachable");
        // Verify that Class A (bit 0 = 0) can map to >= 128 (bit 0 = 1) and vice versa across keys/prefixes
        int classAToHigh = 0;
        int classCToLow = 0;
        for (int octet = 1; octet <= 126; octet++) {
            byte[] mapped = ip.map(new byte[] {(byte) octet, 1, 2, 3});
            if ((mapped[0] & 0x80) != 0) classAToHigh++;
        }
        for (int octet = 192; octet <= 223; octet++) {
            byte[] mapped = ip.map(new byte[] {(byte) octet, (byte) 168, 1, 1});
            if ((mapped[0] & 0x80) == 0) classCToLow++;
        }
        assertTrue(classAToHigh > 20, "Class A (0xxxxxxx) must not be pinned to bit 0 == 0, got " + classAToHigh);
        assertTrue(classCToLow > 5, "Class C (110xxxxx) must not be pinned to 192..223, got " + classCToLow);
        // Chi-squared goodness-of-fit against expected unicast octets per 32-octet bucket:
        // Buckets 0..3 have {31, 32, 32, 31} unicast octets; buckets 4..6 have {32, 32, 32}; bucket 7 (224..255) has 0.
        int[] validPerBucket = {31, 32, 32, 31, 32, 32, 32, 0};
        double chiSq = 0.0;
        for (int b = 0; b < 7; b++) {
            double expected = samples * (validPerBucket[b] / 222.0);
            double diff = bucketCounts[b] - expected;
            chiSq += (diff * diff) / expected;
        }
        assertEquals(0, bucketCounts[7], "no unicast address lands in 224..255");
        assertTrue(chiSq < 24.0, "first octet distribution must be uniform across unicast space, chiSq=" + chiSq);
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

    private byte[] randomUnicastAddress(int family) {
        while (true) {
            byte[] a = randomAddress(family);
            if (!isSpecial(a)) return a;
        }
    }

    private boolean isSpecial(byte[] a) {
        if (ip.isKept(a)) return true;
        if (a.length == 4) {
            return (a[0] & 0xff) == 169 && (a[1] & 0xff) == 254;
        }
        return IpPermutation.isV4Mapped(a) || ((a[0] & 0xff) == 0xfe && (a[1] & 0xc0) == 0x80);
    }

    private static int sharedPrefix(byte[] a, byte[] b) {
        BigInteger x = new BigInteger(1, a).xor(new BigInteger(1, b));
        return a.length * 8 - x.bitLength();
    }
}
