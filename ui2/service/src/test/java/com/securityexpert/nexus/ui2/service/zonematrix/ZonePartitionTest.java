package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

/** ZONE_MATRIX_CONTRACT.md sections 3.1 and 3.2 (ZM-1, ZM-29). Documentation ranges only. */
class ZonePartitionTest {
    @Test void blocksAreCanonicalOrRefused() {
        assertEquals("192.0.2.0/24", ZoneBlock.parse("192.0.2.0/24").toString());
        assertEquals("2001:db8::/32", ZoneBlock.parse("2001:0DB8:0000::/32").toString());
        assertEquals("2001:db8:0:1::/64", ZoneBlock.parse("2001:db8:0:1:0:0:0:0/64").toString());
        assertEquals("::/0", ZoneBlock.parse("::/0").toString());
        assertEquals("2001:db8::1/128", ZoneBlock.parse("2001:db8:0:0:0:0:0:1/128").toString());
        assertEquals("2001:0:0:1::/64", ZoneBlock.parse("2001:0:0:1:0:0:0:0/64").toString());
        for (String bad : List.of("192.0.2.7/24", "192.0.2.0", "192.0.2.0/33", "192.0.2.256/32", "192.0.02.0/24",
                "2001:db8::/129", "2001:db8::1/64", "2001:db8:::/48", "::ffff:192.0.2.1/128", "fe80::1%eth0/128", "", "/24"))
            assertThrows(IllegalArgumentException.class, () -> ZoneBlock.parse(bad), bad);
        assertEquals(ZoneBlock.Family.IPV6, ZoneBlock.address("2001:db8::5").family());
        assertThrows(IllegalArgumentException.class, () -> ZoneBlock.address("192.0.2.0/24"));
    }

    @Test void theLongestPrefixWinsAndTheRestIsUnassigned() {
        var p = ZonePartition.of(List.of(placed("app", "198.51.100.0/24"), placed("mgmt", "198.51.100.128/28"),
                placed("db", "203.0.113.0/25")));
        assertEquals("app", p.zoneOf(ZoneBlock.address("198.51.100.127")));
        assertEquals("mgmt", p.zoneOf(ZoneBlock.address("198.51.100.130")));
        assertEquals("app", p.zoneOf(ZoneBlock.address("198.51.100.144")));
        assertEquals("db", p.zoneOf(ZoneBlock.address("203.0.113.1")));
        assertNull(p.zoneOf(ZoneBlock.address("203.0.113.200")));
        assertNull(p.zoneOf(ZoneBlock.address("2001:db8::1")));
        var sizes = p.effectiveSizes();
        assertEquals(BigInteger.valueOf(240), sizes.get("app").get(ZoneBlock.Family.IPV4));
        assertEquals(BigInteger.valueOf(16), sizes.get("mgmt").get(ZoneBlock.Family.IPV4));
        assertEquals(BigInteger.ONE.shiftLeft(32).subtract(BigInteger.valueOf(256 + 128)), sizes.get(null).get(ZoneBlock.Family.IPV4));
        assertEquals(BigInteger.ONE.shiftLeft(128), sizes.get(null).get(ZoneBlock.Family.IPV6));
    }

    @Test void theSameBlockInTwoZonesIsRefusedAndWithinOneZoneIsReported() {
        var duplicate = assertThrows(ZonePartition.DuplicateBlock.class,
                () -> ZonePartition.of(List.of(placed("a", "192.0.2.0/24"), placed("b", "192.0.2.0/24"))));
        assertEquals(Set.of("a", "b"), Set.of(duplicate.firstZoneId, duplicate.secondZoneId));
        var p = ZonePartition.of(List.of(placed("a", "192.0.2.0/24"), placed("a", "192.0.2.0/24"), placed("a", "192.0.2.64/26")));
        assertEquals(2, p.redundant().size());
        assertEquals("a", p.zoneOf(ZoneBlock.address("192.0.2.70")));
    }

    @Test void partitionEqualsBruteForceLongestPrefixOnRandomBlocks() {
        Random random = new Random(20261114);
        for (int round = 0; round < 200; round++) {
            List<ZonePartition.Placed> placed = new ArrayList<>();
            Set<ZoneBlock> used = new HashSet<>();
            for (int i = 0; i < 12; i++) {
                int prefix = 20 + random.nextInt(13);
                long base = 0xC0000200L & ~0xFFFFL | random.nextInt(1 << 16);
                long mask = prefix == 32 ? -1L : ~((1L << (32 - prefix)) - 1);
                ZoneBlock block = new ZoneBlock(ZoneBlock.Family.IPV4, BigInteger.valueOf(base & mask & 0xFFFFFFFFL), prefix);
                if (used.add(block)) placed.add(new ZonePartition.Placed("z" + random.nextInt(4), block));
            }
            ZonePartition partition = ZonePartition.of(placed);
            List<ZonePartition.Segment> segments = partition.segments(ZoneBlock.Family.IPV4);
            assertEquals(BigInteger.ZERO, segments.get(0).start());
            assertEquals(ZoneBlock.Family.IPV4.max(), segments.get(segments.size() - 1).end());
            for (int k = 1; k < segments.size(); k++)
                assertEquals(segments.get(k - 1).end().add(BigInteger.ONE), segments.get(k).start(), "segments are contiguous");
            for (int probe = 0; probe < 300; probe++) {
                long address = 0xC0000000L | random.nextInt(1 << 18);
                ZoneBlock a = new ZoneBlock(ZoneBlock.Family.IPV4, BigInteger.valueOf(address), 32);
                ZonePartition.Placed best = null;
                for (var p : placed) if (p.block().contains(a) && (best == null || p.block().prefix() > best.block().prefix())) best = p;
                assertEquals(best == null ? null : best.zoneId(), partition.zoneOf(a), "round " + round);
            }
        }
    }

    @Test void movesListAddressesThatChangeOwner() {
        var before = ZonePartition.of(List.of(placed("app", "198.51.100.0/24")));
        var after = ZonePartition.of(List.of(placed("app", "198.51.100.0/24"), placed("mgmt", "198.51.100.0/28"),
                placed("db", "203.0.113.0/30")));
        var moves = ZonePartition.moves(before, after);
        assertTrue(moves.contains(new ZonePartition.Move("app", "mgmt", ZoneBlock.Family.IPV4, BigInteger.valueOf(16))));
        assertTrue(moves.contains(new ZonePartition.Move(null, "db", ZoneBlock.Family.IPV4, BigInteger.valueOf(4))));
        assertEquals(2, moves.size());
        assertTrue(ZonePartition.moves(after, after).isEmpty());
    }

    @Test void canonicalJsonIsStableAndHashesTheSameContent() {
        var a = new ZoneMatrixContent(List.of(
                new ZoneMatrixContent.Zone("00000000-0000-4000-8000-000000000002", "Z-DB", "", "DATABASE",
                        List.of(ZoneBlock.parse("203.0.113.0/25"), ZoneBlock.parse("192.0.2.0/24"))),
                new ZoneMatrixContent.Zone("00000000-0000-4000-8000-000000000001", "Z-APP \"main\"", "line\nnext", null, List.of())));
        var b = new ZoneMatrixContent(List.of(a.zones().get(1), a.zones().get(0)));
        assertEquals(a.canonicalJson(), b.canonicalJson());
        assertEquals(a.sha256(), b.sha256());
        assertTrue(a.canonicalJson().startsWith("{\"schema\":2,\"zones\":[{\"id\":\"00000000-0000-4000-8000-000000000001\""));
        assertTrue(a.canonicalJson().endsWith(",\"matrix\":{\"default_statement\":\"DENY_ALL\",\"default_severity\":\"MEDIUM\","
                + "\"template_origin\":null,\"service_sets\":[],\"cells\":[]}}"));
        var parsed = ZoneMatrixContent.parse(a.canonicalJson(), new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals(a, parsed);
        assertEquals(a.sha256(), parsed.sha256());
        // step 1 stored schema 1 (zones only); it still reads, with the initial matrix
        String schemaOne = "{\"schema\":1,\"zones\":[{\"id\":\"00000000-0000-4000-8000-000000000001\",\"name\":\"Z-APP\","
                + "\"description\":\"\",\"role\":null,\"blocks\":[\"192.0.2.0/24\"]}]}";
        var old = ZoneMatrixContent.parse(schemaOne, new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals(ZoneMatrixContent.Matrix.initial(), old.matrix());
        assertEquals("Z-APP", old.zones().get(0).name());
    }

    private static ZonePartition.Placed placed(String zone, String block) { return new ZonePartition.Placed(zone, ZoneBlock.parse(block)); }
}
