package com.securityexpert.nexus.ui2.service.systemlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.platform.SystemLogCensusFeed;

/**
 * The service half of the census over a real encrypted artefact store in a temp directory: the chunk is decrypted, every
 * line is masked, a line whose identity literal survived is dropped and counted, and only masked text comes back.
 * Documentation addresses and example names only.
 */
class SystemLogCensusFeedProviderTest {

    private static SystemLogCensusFeedProvider.Feed feed(SystemLogReadFixture fx, BiFunction<String, String, String> leak) {
        return new SystemLogCensusFeedProvider.Feed(null, fx.store, fx.masking, Map.of(SystemLogReadFixture.DEVICE, "FW-TANGO-04"), leak);
    }

    private static SystemLogCensusFeed.Chunk meta(SystemLogReadFixture fx, String chunkId) {
        var ref = fx.repository.findChunk(chunkId).orElseThrow();
        return new SystemLogCensusFeed.Chunk(chunkId, ref.deviceId(), ref.sourceKey(), "2026-11-24", ref.artefactRef(),
                ref.wrappedDataKey(), ref.bytes());
    }

    @Test
    void aChunkIsDecryptedMaskedAndReturnedAsMaskedLinesOnly(@TempDir Path dir) throws Exception {
        SystemLogReadFixture fx = new SystemLogReadFixture(dir);
        fx.chunk("c1", SystemLogReadFixture.bytes("Nov 24 10:00:00 2026 edge-fw-01 sshd[1]: from 192.0.2.17 ok\r\n\r\n"
                + "Nov 24 10:00:01 2026 edge-fw-01 kernel: EDAC MC0: 1 CE memory read error\n"));
        var masked = feed(fx, (raw, m) -> null).read(meta(fx, "c1"), 1 << 20);
        assertEquals(2, masked.lines().size(), "blank lines are skipped");
        String all = String.join("\n", masked.lines());
        assertFalse(all.contains("192.0.2.17"), all);
        assertFalse(all.contains("edge-fw-01"), all);
        assertTrue(all.contains("FW-TANGO-04"), all);
        assertTrue(all.contains("EDAC MC0"), all);
        assertEquals(0, masked.guardRejected());
        assertFalse(masked.truncated());
    }

    @Test
    void aLineFlaggedByTheLeakCheckIsDroppedAndCounted(@TempDir Path dir) throws Exception {
        SystemLogReadFixture fx = new SystemLogReadFixture(dir);
        fx.chunk("c1", SystemLogReadFixture.bytes("keep this line\ndrop this line\nkeep too\n"));
        var masked = feed(fx, (raw, m) -> raw.startsWith("drop") ? "ipv4" : null).read(meta(fx, "c1"), 1 << 20);
        assertEquals(2, masked.lines().size());
        assertEquals(1, masked.guardRejected());
    }

    @Test
    void theLeakCheckReceivesTheRawAndTheMaskedLine(@TempDir Path dir) throws Exception {
        SystemLogReadFixture fx = new SystemLogReadFixture(dir);
        fx.chunk("c1", SystemLogReadFixture.bytes("peer 192.0.2.17 up\n"));
        String[] seen = new String[2];
        feed(fx, (raw, m) -> {
            seen[0] = raw;
            seen[1] = m;
            return null;
        }).read(meta(fx, "c1"), 1 << 20);
        assertEquals("peer 192.0.2.17 up", seen[0]);
        assertFalse(seen[1].contains("192.0.2.17"));
    }

    @Test
    void anOversizedChunkIsCutAtALineBoundary(@TempDir Path dir) throws Exception {
        SystemLogReadFixture fx = new SystemLogReadFixture(dir);
        fx.chunk("c1", SystemLogReadFixture.hundredByteLines(50));
        var masked = feed(fx, (raw, m) -> null).read(meta(fx, "c1"), 1050);
        assertTrue(masked.truncated());
        assertEquals(10, masked.lines().size());
    }

    @Test
    void thePseudonymAndRuleSetComeFromTheFeed(@TempDir Path dir) {
        SystemLogReadFixture fx = new SystemLogReadFixture(dir);
        var feed = feed(fx, (raw, m) -> null);
        assertEquals("FW-TANGO-04", feed.devicePseudonym(SystemLogReadFixture.DEVICE));
        assertEquals(LogMasking.RULE_SET, feed.maskRuleSet());
    }
}
