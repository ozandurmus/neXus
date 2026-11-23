package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.CutoverSegmentCaveat;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathPolicyBinder;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathPseudonymMap;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathPseudonymResolver;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathQueryService;

/**
 * ACI cutover record Q9: a Trace whose source or destination lies in a stored cutover segment carries
 * {@code FABRIC_REDIRECT_NOT_MODELLED}; nothing else changes. Runs over the A4 synthetic chain estate (the route test's
 * estate), the production binder and a stub segment source.
 */
class PathQueryCutoverCaveatTest {
    private static final String SEGMENT_ID = "seg-0001";
    private static final String SEGMENT = "{\"segmentId\":\"" + SEGMENT_ID + "\",\"name\":\"SEG-ALPHA\",\"revision\":1,"
            + "\"blocks\":[\"198.51.100.16/28\"],\"peerBlocks\":[\"203.0.113.192/28\"],\"createdBy\":\"synthetic\"}";
    private static final String SRC = "198.51.100.20", DST = "203.0.113.200";

    private static PathQueryService service(PathAnalysisRouteTest.Estate estate, Supplier<List<String>> segments) {
        return new PathQueryService(estate.topology, new PathPolicyBinder(estate.catalog),
                new PathPseudonymResolver(PathPseudonymMap.over(estate.ips, estate.names)), estate.ips, estate.names,
                Clock.fixed(PathAnalysisRouteTest.NOW, ZoneOffset.UTC), segments);
    }

    private static PathResult computed(PathQueryService service, String src, String dst, boolean replayViewer) {
        var request = new PathQueryService.Request(src, dst, "tcp", 443, Optional.empty(), Optional.empty(), false);
        return ((PathQueryService.Computed) service.query(request, replayViewer)).result();
    }

    private static List<String> hopReasons(PathResult r) {
        return r.branches().stream().flatMap(b -> b.hops().stream()).flatMap(h -> h.reasons().stream()).toList();
    }

    @Test
    void aSourceInsideASegmentAddsTheCaveatAndNothingElseChanges() {
        var estate = new PathAnalysisRouteTest.Estate("chain.json").built();
        PathResult base = computed(service(estate, List::of), SRC, DST, false);
        PathResult hit = computed(service(estate, () -> List.of(SEGMENT)), SRC, DST, false);
        assertEquals(base.verdict(), hit.verdict());
        assertEquals(base.reason(), hit.reason());
        assertEquals(base.branches(), hit.branches());
        assertEquals(hopReasons(base), hopReasons(hit));
        assertEquals(base.warnings(), hit.warnings());
        assertEquals(base.blockingHop(), hit.blockingHop());
        assertTrue(hit.caveats().contains(CutoverSegmentCaveat.FABRIC_REDIRECT_NOT_MODELLED));
        assertFalse(base.caveats().contains(CutoverSegmentCaveat.FABRIC_REDIRECT_NOT_MODELLED));
        assertFalse(hit.plain());
    }

    @Test
    void aDestinationInsideASegmentAddsTheCaveat() {
        var estate = new PathAnalysisRouteTest.Estate("chain.json").built();
        String dstSegment = SEGMENT.replace("198.51.100.16/28", "203.0.113.200/29");
        PathResult hit = computed(service(estate, () -> List.of(dstSegment)), SRC, DST, false);
        assertTrue(hit.caveats().contains(CutoverSegmentCaveat.FABRIC_REDIRECT_NOT_MODELLED));
        assertFalse(hit.plain());
    }

    @Test
    void anAddressOutsideEveryBlockIsIdenticalToToday() {
        var estate = new PathAnalysisRouteTest.Estate("chain.json").built();
        String elsewhere = SEGMENT.replace("198.51.100.16/28", "192.0.2.0/24");
        PathResult base = computed(service(estate, List::of), SRC, DST, false);
        assertEquals(base, computed(service(estate, () -> List.of(elsewhere)), SRC, DST, false));
        assertEquals(base, computed(service(estate, List::of), SRC, DST, false));
    }

    @Test
    void aPeerBlockAddressIsNotCaveated() {
        var estate = new PathAnalysisRouteTest.Estate("chain.json").built();
        PathResult base = computed(service(estate, List::of), SRC, DST, false);
        // DST lies in the peer block 203.0.113.192/28 only.
        String peerOnly = SEGMENT.replace("198.51.100.16/28", "192.0.2.0/24");
        assertEquals(base, computed(service(estate, () -> List.of(peerOnly)), SRC, DST, false));
    }

    @Test
    void aFailedSegmentReadAddsTheUnreadableWarningAndNoCaveat() {
        var estate = new PathAnalysisRouteTest.Estate("chain.json").built();
        PathResult base = computed(service(estate, List::of), SRC, DST, false);
        PathResult failed = computed(service(estate, () -> {
            throw new IllegalStateException("synthetic read failure");
        }), SRC, DST, false);
        assertTrue(failed.warnings().contains(CutoverSegmentCaveat.CUTOVER_SEGMENTS_UNREADABLE));
        assertEquals(base.caveats(), failed.caveats());
        assertEquals(base.verdict(), failed.verdict());
        assertEquals(base.plain(), failed.plain());
    }

    @Test
    void anUnparseableStoredBlockAddsTheUnreadableWarning() {
        var estate = new PathAnalysisRouteTest.Estate("chain.json").built();
        String broken = SEGMENT.replace("198.51.100.16/28", "not-a-block");
        PathResult result = computed(service(estate, () -> List.of(broken)), SRC, DST, false);
        assertTrue(result.warnings().contains(CutoverSegmentCaveat.CUTOVER_SEGMENTS_UNREADABLE));
        assertFalse(result.caveats().contains(CutoverSegmentCaveat.FABRIC_REDIRECT_NOT_MODELLED));
    }

    @Test
    void theReplayViewerResultCarriesTheCodeOnly() {
        var estate = new PathAnalysisRouteTest.Estate("chain.json").built();
        String src = estate.shown("198.51.100.1/26", SRC), dst = estate.shown("203.0.113.192/26", DST);
        PathResult base = computed(service(estate, List::of), src, dst, true);
        PathResult hit = computed(service(estate, () -> List.of(SEGMENT)), src, dst, true);
        assertEquals(base.verdict(), hit.verdict());
        assertTrue(hit.caveats().contains(CutoverSegmentCaveat.FABRIC_REDIRECT_NOT_MODELLED));
        assertFalse(hit.plain());
        List<String> added = new java.util.ArrayList<>(hit.caveats());
        added.removeAll(base.caveats());
        assertEquals(List.of(CutoverSegmentCaveat.FABRIC_REDIRECT_NOT_MODELLED), added);
        assertEquals(base.warnings(), hit.warnings());
        String text = hit.toString();
        for (String leak : List.of("SEG-ALPHA", SEGMENT_ID, "198.51.100.16/28", "203.0.113.192/28", "synthetic\"")) {
            assertFalse(text.contains(leak), leak);
        }
    }
}
