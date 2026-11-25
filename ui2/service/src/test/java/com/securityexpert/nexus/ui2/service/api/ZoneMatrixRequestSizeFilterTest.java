package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.service.boot.ZoneMatrixConfiguration;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixContent;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * ZM-40 (ZONE_MATRIX_CONTRACT.md section 3.1, 8.5): a body over the byte limit on a zone matrix write or evaluate route is
 * refused with 413 before Jackson parses it, whether the length was declared or the body is chunked, and is never cut.
 * The limit is derived from the contract caps; the arithmetic is recomputed here.
 */
class ZoneMatrixRequestSizeFilterTest {
    private static final int LIMIT = ZoneMatrixRequestSizeFilter.MAX_REQUEST_BYTES;
    private static final String PLANTED = "PLANTED-BODY-MARKER-198.51.100.7";
    private final ZoneMatrixRequestSizeFilter filter = new ZoneMatrixRequestSizeFilter();

    // ---------------------------------------------------------------------------------------- the derivation

    @Test
    void theLimitIsDerivedFromTheContractCapsWithAMargin() {
        // zone request: blocks + name + description (JSON worst case 6 bytes per character, 3 bytes per array element)
        long zone = (long) ZoneMatrixContent.MAX_BLOCKS_PER_ZONE * (49 + 3)
                + (long) (ZoneMatrixContent.MAX_NAME + ZoneMatrixContent.MAX_DESCRIPTION) * 6;
        assertEquals(10_000L * 52 + 564 * 6, zone);
        // cell request: services + rationale + regulation reference
        long entry = (8 + 128) * 6 + 3;
        long cell = (long) ZoneMatrixContent.MAX_SERVICES_PER_CELL * entry + 2L * ZoneMatrixContent.MAX_TEXT * 6;
        assertEquals(1_000L * 819 + 24_000, cell);
        // service set: entries and a name
        long set = (long) ZoneMatrixContent.MAX_ENTRIES_PER_SET * entry + ZoneMatrixContent.MAX_NAME * 6L;
        long largest = Math.max(zone, Math.max(cell, set));
        assertEquals(cell, largest, "the cell request is the largest single request");
        assertEquals(largest + 1_024, ZoneMatrixRequestSizeFilter.LARGEST_LEGAL_REQUEST_BYTES, "the constant uses the same arithmetic");
        assertEquals(1_048_576, LIMIT);
        assertTrue(LIMIT >= ZoneMatrixRequestSizeFilter.LARGEST_LEGAL_REQUEST_BYTES * 12L / 10,
                "the limit keeps at least a 20% margin over the largest legal request; a raised cap must revisit the limit");
    }

    // ---------------------------------------------------------------------------------------- declared length

    @Test
    void aDeclaredBodyUnderAndExactlyAtTheLimitPassesAndOneByteOverIsRefusedUnread() throws Exception {
        for (int size : new int[]{0, 100, LIMIT - 1, LIMIT}) {
            var chain = new MockFilterChain();
            var response = new MockHttpServletResponse();
            var request = new MockHttpServletRequest("POST", "/api/v2/zone-matrix/draft/cells");
            request.setContent(new byte[size]);
            filter.doFilter(request, response, chain);
            assertNotNull(chain.getRequest(), "size " + size + " reaches the controller");
            assertEquals(200, response.getStatus());
        }
        var counting = new CountingRequest("POST", "/api/v2/zone-matrix/draft/cells", LIMIT + 1L, new InfiniteStream(PLANTED));
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();
        filter.doFilter(counting, response, chain);
        assertNull(chain.getRequest(), "an over-limit request never reaches the controller");
        assertRefused(response);
        assertEquals(0, counting.stream.read.get(), "a declared length over the limit is refused without reading a byte");
    }

    // ---------------------------------------------------------------------------------------- chunked

    @Test
    void aChunkedBodyIsCountedWhileReadAndRefusedTheMomentItPassesTheLimit() throws Exception {
        var endless = new CountingRequest("POST", "/api/v2/zone-matrix/draft/zones", -1, new InfiniteStream(PLANTED));
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();
        filter.doFilter(endless, response, chain);
        assertNull(chain.getRequest());
        assertRefused(response);
        assertEquals(LIMIT + 1L, endless.stream.read.get(), "reading stops at exactly one byte past the limit");
    }

    @Test
    void aChunkedBodyAtTheLimitPassesUnchangedAndOneByteMoreIsRefused() throws Exception {
        byte[] atLimit = new byte[LIMIT];
        Arrays.fill(atLimit, (byte) 'a');
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();
        filter.doFilter(new CountingRequest("PUT", "/api/v2/zone-matrix/draft/zones/z-1", -1, new BytesStream(atLimit)), response, chain);
        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
        assertArrayEquals(atLimit, chain.getRequest().getInputStream().readAllBytes(), "the controller reads the very bytes sent");
        assertEquals(LIMIT, chain.getRequest().getContentLength());

        var chainReader = new MockFilterChain();
        filter.doFilter(new CountingRequest("POST", "/api/v2/zone-matrix/evaluate", -1, new BytesStream("{\"a\":1}".getBytes(StandardCharsets.UTF_8))),
                new MockHttpServletResponse(), chainReader);
        assertEquals("{\"a\":1}", chainReader.getRequest().getReader().readLine(), "a reader works too");

        byte[] over = Arrays.copyOf(atLimit, LIMIT + 1);
        var overChain = new MockFilterChain();
        var overResponse = new MockHttpServletResponse();
        filter.doFilter(new CountingRequest("POST", "/api/v2/zone-matrix/draft/cells", -1, new BytesStream(over)), overResponse, overChain);
        assertNull(overChain.getRequest());
        assertRefused(overResponse);
    }

    @Test
    void anEmptyChunkedBodyPassesAsAnEmptyBody() throws Exception {
        var chain = new MockFilterChain();
        filter.doFilter(new CountingRequest("POST", "/api/v2/zone-matrix/evaluate", -1, new BytesStream(new byte[0])),
                new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        assertEquals(0, chain.getRequest().getInputStream().readAllBytes().length);
    }

    // ---------------------------------------------------------------------------------------- untouched

    @Test
    void getRequestsAndOtherRoutesAreUntouched() throws Exception {
        var get = new CountingRequest("GET", "/api/v2/zone-matrix/draft", LIMIT + 1L, new InfiniteStream(PLANTED));
        var getChain = new MockFilterChain();
        var getResponse = new MockHttpServletResponse();
        filter.doFilter(get, getResponse, getChain);
        assertSame(get, getChain.getRequest(), "a GET is passed on as it came");
        assertEquals(200, getResponse.getStatus());

        for (String path : new String[]{"/api/v2/devices", "/api/v2/zone-matrixx/draft", "/internal/advisories/x"}) {
            var other = new CountingRequest("POST", path, LIMIT + 1L, new InfiniteStream(PLANTED));
            var chain = new MockFilterChain();
            filter.doFilter(other, new MockHttpServletResponse(), chain);
            assertSame(other, chain.getRequest(), path + " belongs to another module");
        }
    }

    @Test
    void theRefusalNamesTheLimitAndEchoesNothingOfTheBody() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new CountingRequest("POST", "/api/v2/zone-matrix/draft/cells", LIMIT + 5L, new InfiniteStream(PLANTED)),
                response, new MockFilterChain());
        assertRefused(response);
    }

    private static void assertRefused(MockHttpServletResponse response) throws Exception {
        assertEquals(413, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        String body = response.getContentAsString();
        assertTrue(body.contains("\"error\":\"ZONE_MATRIX_REQUEST_TOO_LARGE\""), body);
        assertTrue(body.contains(Integer.toString(LIMIT)), "the message names the limit in bytes");
        assertFalse(body.contains("PLANTED") || body.contains("198.51.100"), "nothing of the body is echoed");
    }

    // ---------------------------------------------------------------------------------------- registration

    @Test
    void theRegistrationCoversTheZoneMatrixUrlPatternAndNothingElseAndRunsAfterTheSecurityChain() {
        var registration = new ZoneMatrixConfiguration().zoneMatrixRequestSizeFilter();
        assertInstanceOf(ZoneMatrixRequestSizeFilter.class, registration.getFilter());
        assertEquals(List.of("/api/v2/zone-matrix/*"), new ArrayList<>(registration.getUrlPatterns()));
        assertTrue(registration.getOrder() > -100, "after the Spring Security filter chain (order -100)");
    }

    // ---------------------------------------------------------------------------------------- through MVC

    @Test
    void aWriteRouteRefusesAnOversizedBodyBeforeParsingAndStillAcceptsTheLargestLegalRequests() throws Exception {
        var service = mock(ZoneMatrixService.class);
        when(service.discardDraft(any(), anyLong())).thenReturn(Map.of("discarded", true));
        when(service.setCell(any(), any(), any())).thenReturn(Map.of("revision", 8));
        when(service.createZone(any(), any(), any())).thenReturn(Map.of("revision", 9));
        var controller = new ZoneMatrixController(service, mock(ZoneMatrixRunService.class), mock(RbacEvaluator.class), new ActionRegistry());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build();

        // an in-bound request reaches the service
        mvc.perform(post("/api/v2/zone-matrix/draft/discard").requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":7}"))
                .andExpect(status().isOk());
        verify(service).discardDraft("actor-1", 7L);

        // the largest legal cell request (1,000 longest service entries, every character escaped) is under the limit and passes
        String cell = worstCaseCellJson();
        assertTrue(cell.length() <= ZoneMatrixRequestSizeFilter.LARGEST_LEGAL_REQUEST_BYTES, "built " + cell.length());
        assertTrue(cell.length() > 800_000);
        mvc.perform(post("/api/v2/zone-matrix/draft/cells").requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1")
                        .contentType(MediaType.APPLICATION_JSON).content(cell))
                .andExpect(status().isOk());

        // the largest legal zone request (10,000 blocks of the longest text) passes
        String blocks = "\"" + "0".repeat(49) + "\",";
        String zone = "{\"revision\":1,\"name\":\"Z-1\",\"blocks\":[" + blocks.repeat(ZoneMatrixContent.MAX_BLOCKS_PER_ZONE - 1) + blocks.substring(0, blocks.length() - 1) + "]}";
        assertTrue(zone.length() <= ZoneMatrixRequestSizeFilter.LARGEST_LEGAL_REQUEST_BYTES);
        mvc.perform(post("/api/v2/zone-matrix/draft/zones").requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1")
                        .contentType(MediaType.APPLICATION_JSON).content(zone))
                .andExpect(status().isOk());

        // one byte over the limit is refused 413 before Jackson: the service is never called, however well-formed the JSON
        String padding = " ".repeat(LIMIT + 1 - "{\"revision\":7}".length());
        verifyRefused(mvc, "{\"revision\":7}" + padding);
        // and so is a body that is not JSON at all and over the limit (it is not parsed, so it is not a 400)
        verifyRefused(mvc, PLANTED.repeat(LIMIT / PLANTED.length() + 2));
        verify(service, never()).discardDraft("actor-1", 8L);
    }

    /**
     * Step 4b-2: the on-demand run route ({@code POST /api/v2/zone-matrix/runs}) is under the same limit: an over-limit body,
     * declared or chunked, is refused 413 unread and never reaches the run service; an in-bound body passes unchanged.
     */
    @Test
    void theRunRouteIsUnderTheSameLimitDeclaredAndChunked() throws Exception {
        String route = "/api/v2/zone-matrix/runs";
        var inBound = new MockHttpServletRequest("POST", route);
        inBound.setContent(new byte[LIMIT]);
        var inChain = new MockFilterChain();
        var inResponse = new MockHttpServletResponse();
        filter.doFilter(inBound, inResponse, inChain);
        assertNotNull(inChain.getRequest(), "a body at the limit reaches the controller");
        assertEquals(200, inResponse.getStatus());

        var declared = new CountingRequest("POST", route, LIMIT + 1L, new InfiniteStream(PLANTED));
        var declaredChain = new MockFilterChain();
        var declaredResponse = new MockHttpServletResponse();
        filter.doFilter(declared, declaredResponse, declaredChain);
        assertNull(declaredChain.getRequest());
        assertRefused(declaredResponse);
        assertEquals(0, declared.stream.read.get(), "refused without reading a byte");

        var chunked = new CountingRequest("POST", route, -1, new InfiniteStream(PLANTED));
        var chunkedChain = new MockFilterChain();
        var chunkedResponse = new MockHttpServletResponse();
        filter.doFilter(chunked, chunkedResponse, chunkedChain);
        assertNull(chunkedChain.getRequest());
        assertRefused(chunkedResponse);
        assertEquals(LIMIT + 1L, chunked.stream.read.get(), "reading stops at exactly one byte past the limit");
    }

    /**
     * Through MVC: an over-limit run request is 413 before parsing and the run service is never called; a missing body gets
     * the same 400 {@code INVALID_POLICY} that {@code /evaluate} gives for a missing policy.
     */
    @Test
    void theRunRouteRefusesAnOversizedBodyBeforeParsingAndAMissingBodyLikeEvaluate() throws Exception {
        var mapper = new ObjectMapper();
        var clock = Clock.fixed(Instant.parse("2026-11-25T09:00:00Z"), ZoneOffset.UTC);
        var zones = new ZoneMatrixService(mock(ZoneMatrixRepository.class), new ZoneMatrixService.Principals() {
            @Override public boolean holdsComplianceAdmin(String actor) { return false; }
            @Override public boolean isRoot(String actor) { return false; }
        }, mapper, clock);
        var runStore = mock(ZoneMatrixRunRepository.class);
        var rbac = mock(RbacEvaluator.class);
        when(rbac.evaluateAny(anyString(), anySet(), any(Instant.class))).thenReturn(
                new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty()));
        try (var runService = new ZoneMatrixRunService(runStore, mock(ZoneMatrixRepository.class), null, mapper, clock, Duration.ofSeconds(30))) {
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new ZoneMatrixController(zones, runService, rbac, new ActionRegistry()))
                    .addFilters(filter).build();
            String head = "{\"policy_id\":\"pol-1\"}";
            var result = mvc.perform(post("/api/v2/zone-matrix/runs").requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1")
                            .contentType(MediaType.APPLICATION_JSON).content(head + " ".repeat(LIMIT + 1 - head.length())))
                    .andExpect(status().isPayloadTooLarge()).andReturn();
            assertTrue(result.getResponse().getContentAsString().contains(ZoneMatrixRequestSizeFilter.CODE));
            verify(runStore, never()).start(any());
            verify(runStore, never()).runningRunOf(any());

            for (String route : new String[]{"/api/v2/zone-matrix/runs", "/api/v2/zone-matrix/evaluate"}) {
                mvc.perform(post(route).requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error").value("INVALID_POLICY"));
            }
            verify(runStore, never()).start(any());
        }
    }

    private static void verifyRefused(MockMvc mvc, String body) throws Exception {
        var result = mvc.perform(post("/api/v2/zone-matrix/draft/discard").requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPayloadTooLarge()).andReturn();
        String answer = result.getResponse().getContentAsString();
        assertTrue(answer.contains("ZONE_MATRIX_REQUEST_TOO_LARGE") && answer.contains(Integer.toString(LIMIT)));
        assertFalse(answer.contains("PLANTED"));
    }

    private static String worstCaseCellJson() {
        StringBuilder entry = new StringBuilder("\"");
        for (char c : ("app:pan:" + "a".repeat(128)).toCharArray()) entry.append(String.format("\\u%04x", (int) c));
        entry.append('"');
        String text = "\"" + "\\u0072".repeat(ZoneMatrixContent.MAX_TEXT) + "\"";
        StringBuilder out = new StringBuilder("{\"revision\":1,\"source\":\"UNASSIGNED\",\"destination\":\"UNASSIGNED\",\"statement\":\"ALLOW_LISTED\",\"services\":[");
        for (int i = 0; i < ZoneMatrixContent.MAX_SERVICES_PER_CELL; i++) out.append(i == 0 ? "" : ",").append(entry);
        return out.append("],\"severity\":\"LOW\",\"rationale\":").append(text).append(",\"regulation_reference\":").append(text).append('}').toString();
    }

    // ---------------------------------------------------------------------------------------- helpers

    /** A request whose declared length and body stream are set apart (a chunked request declares no length). */
    private static final class CountingRequest extends MockHttpServletRequest {
        final CountingStream stream;
        private final long declared;

        CountingRequest(String method, String uri, long declared, CountingStream stream) {
            super(method, uri);
            this.declared = declared;
            this.stream = stream;
        }

        @Override public long getContentLengthLong() { return declared; }
        @Override public int getContentLength() { return (int) Math.min(declared, Integer.MAX_VALUE); }
        @Override public ServletInputStream getInputStream() { return stream; }
    }

    private abstract static class CountingStream extends ServletInputStream {
        final AtomicLong read = new AtomicLong();
        @Override public boolean isFinished() { return false; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { }
        abstract int next() throws IOException;
        @Override public int read() throws IOException { int b = next(); if (b >= 0) read.incrementAndGet(); return b; }
        @Override public int read(byte[] target, int off, int len) throws IOException {
            int n = 0;
            while (n < len) {
                int b = next();
                if (b < 0) break;
                target[off + n++] = (byte) b;
                read.incrementAndGet();
            }
            return n == 0 && len > 0 ? -1 : n;
        }
    }

    /** A body that never ends: the planted marker repeated. */
    private static final class InfiniteStream extends CountingStream {
        private final byte[] marker;
        private long position;
        InfiniteStream(String marker) { this.marker = marker.getBytes(StandardCharsets.UTF_8); }
        @Override int next() { return marker[(int) (position++ % marker.length)]; }
    }

    private static final class BytesStream extends CountingStream {
        private final ByteArrayInputStream in;
        BytesStream(byte[] bytes) { in = new ByteArrayInputStream(bytes); }
        @Override int next() { return in.read(); }
    }
}
