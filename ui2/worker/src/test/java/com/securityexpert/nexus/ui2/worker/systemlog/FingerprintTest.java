package com.securityexpert.nexus.ui2.worker.systemlog;

import static com.securityexpert.nexus.ui2.worker.systemlog.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FingerprintTest {
    private static final AnchorHasher HASHER = new HmacSha256AnchorHasher(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private static byte[] content(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ('a' + (i % 26));
        }
        return b;
    }

    @Test
    void theAnchorRegionIsAtMostSixtyFourBytesAndShorterNearTheFileStart() {
        assertEquals(64, Fingerprint.anchorLength(5000, CFG));
        assertEquals(64, Fingerprint.anchorLength(64, CFG));
        assertEquals(63, Fingerprint.anchorLength(63, CFG));
        assertEquals(0, Fingerprint.anchorLength(0, CFG));
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.anchorLength(-1, CFG));
    }

    @Test
    void hmacIsKeyedDeterministicAndHex() {
        byte[] data = content(64);
        String a = HASHER.hash(data);
        assertEquals(64, a.length());
        assertTrue(a.matches("[0-9a-f]{64}"));
        assertEquals(a, HASHER.hash(data.clone()));
        assertNotEquals(a, HASHER.hash(content(63)));
        AnchorHasher other = new HmacSha256AnchorHasher("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(a, other.hash(data));
        assertThrows(IllegalArgumentException.class, () -> new HmacSha256AnchorHasher(new byte[8]));
    }

    @Test
    void theAnchorOfTheBytesJustBeforeTheOffsetVerifiesTheRangeThatStartsThere() {
        byte[] file = content(10_000);
        long offset = 5_000;
        String stored = Fingerprint.nextAnchor(HASHER, Arrays.copyOfRange(file, 0, (int) offset), (int) offset, offset, CFG);
        // a later read starts K bytes early: file[offset-64 .. ]
        byte[] received = Arrays.copyOfRange(file, (int) offset - 64, 6_000);
        assertEquals(Fingerprint.Result.MATCH, Fingerprint.verify(HASHER, received, received.length, 64, stored));
    }

    @Test
    void aRewrittenFileFailsTheCheck() {
        byte[] file = content(10_000);
        String stored = Fingerprint.nextAnchor(HASHER, Arrays.copyOfRange(file, 0, 5000), 5000, 5000, CFG);
        byte[] rewritten = Arrays.copyOfRange(file, 4936, 6000);
        rewritten[10] ^= 0x01; // one bit differs inside the anchor region
        assertEquals(Fingerprint.Result.MISMATCH, Fingerprint.verify(HASHER, rewritten, rewritten.length, 64, stored));
    }

    @Test
    void aChangeOutsideTheAnchorRegionIsNotDetected_byDesign() {
        byte[] file = content(10_000);
        String stored = Fingerprint.nextAnchor(HASHER, Arrays.copyOfRange(file, 0, 5000), 5000, 5000, CFG);
        byte[] received = Arrays.copyOfRange(file, 4936, 6000);
        received[200] ^= 0x01; // payload bytes are new data, not covered by the anchor
        assertEquals(Fingerprint.Result.MATCH, Fingerprint.verify(HASHER, received, received.length, 64, stored));
    }

    @Test
    void fewerThanKBytesIsAShortReadNeverAMatch() {
        byte[] file = content(200);
        String stored = HASHER.hash(Arrays.copyOfRange(file, 0, 64));
        assertEquals(Fingerprint.Result.SHORT_READ, Fingerprint.verify(HASHER, file, 63, 64, stored));
        assertEquals(Fingerprint.Result.SHORT_READ, Fingerprint.verify(HASHER, new byte[0], 0, 64, stored));
        assertEquals(Fingerprint.Result.MATCH, Fingerprint.verify(HASHER, file, 64, 64, stored));
    }

    @Test
    void offsetZeroNeedsNoAnchorAndAMissingStoredAnchorCannotBeConfirmed() {
        assertEquals(Fingerprint.Result.MATCH, Fingerprint.verify(HASHER, new byte[0], 0, 0, null));
        assertEquals(Fingerprint.Result.MISMATCH, Fingerprint.verify(HASHER, content(64), 64, 64, null));
        assertNull(Fingerprint.nextAnchor(HASHER, new byte[0], 0, 0, CFG));
    }

    @Test
    void anAnchorNearTheStartCoversOnlyTheBytesThatExist() {
        byte[] file = content(100);
        String stored = Fingerprint.nextAnchor(HASHER, Arrays.copyOfRange(file, 0, 10), 10, 10, CFG);
        assertEquals(HASHER.hash(Arrays.copyOfRange(file, 0, 10)), stored);
        assertEquals(Fingerprint.Result.MATCH, Fingerprint.verify(HASHER, file, 50, 10, stored));
    }

    @Test
    void theNextAnchorNeedsTheContiguousWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> Fingerprint.nextAnchor(HASHER, content(30), 30, 5000, CFG));
        // a window longer than K uses its tail
        byte[] w = content(300);
        assertEquals(HASHER.hash(Arrays.copyOfRange(w, 236, 300)), Fingerprint.nextAnchor(HASHER, w, 300, 99999, CFG));
    }

    @Test
    void theAnchorOfAFirstRunComesFromTheLeadInPlusTheStoredBytes() {
        // a first run at offset 1000 reads 64 lead-in bytes, skips a partial line of 7, stores 40 bytes
        byte[] file = content(5000);
        byte[] range = Arrays.copyOfRange(file, 936, 936 + 64 + 47);
        long newOffset = 1000 + 47;
        String anchor = Fingerprint.nextAnchor(HASHER, range, range.length, newOffset, CFG);
        assertEquals(HASHER.hash(Arrays.copyOfRange(file, (int) newOffset - 64, (int) newOffset)), anchor);
    }
}
