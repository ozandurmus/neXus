package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.policy.*;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.*;
import java.util.concurrent.*;

class PolicyBulkheadTest {
    @Test void atMostNHeavyComputationsRunAndTheRestAreRefusedPolicyBusy() throws Exception {
        var bulkhead = new PolicyBulkhead(2, 50);
        var inside = new CountDownLatch(2); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) pool.submit(() -> bulkhead.run(() -> {
                inside.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return org.springframework.http.ResponseEntity.ok().build();
            }));
            assertTrue(inside.await(10, TimeUnit.SECONDS));
            assertEquals(0, bulkhead.available());
            var refused = bulkhead.run(() -> { throw new AssertionError("must not run"); });
            assertEquals(503, refused.getStatusCode().value());
            assertEquals(Map.of("error", "POLICY_BUSY"), refused.getBody());
            assertEquals("5", refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            assertTrue(refused.getHeaders().getCacheControl().contains("no-store"));
            assertEquals(1, bulkhead.rejected());
            release.countDown();
            pool.shutdown(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(2, bulkhead.available(), "permits come back, also when the work throws");
            assertThrows(IllegalStateException.class, () -> bulkhead.run(() -> { throw new IllegalStateException(); }));
            assertEquals(2, bulkhead.available());
        } finally { pool.shutdownNow(); }
    }

    @Test void saturatedHeavyEndpointsAnswer503ButMetadataEndpointsStayCheapAndOpen() throws Exception {
        var query = mock(PolicyQueryService.class);
        var bulkhead = new PolicyBulkhead(1, 20);
        var controller = new PolicyController(query, bulkhead);
        var meta = new PolicySnapshot.Metadata("p1", "s1", "MGR-ALPHA", "PAN", "c1", "DOM-ALPHA", "POL-ALPHA", "2026-10-09T12:00:00Z", "a1", List.of());
        var snapshot = new PolicySnapshot(meta, List.of(), Map.of());
        var inside = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(query.find("p1")).thenAnswer(call -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return Optional.of(snapshot);
        });
        when(query.hygiene(any(), anyInt(), anyString(), anyString(), anyInt())).thenReturn(new PolicyResponse(Map.of("rows", List.of())));
        when(query.tree("", "", "")).thenReturn(new PolicyResponse(Map.of("sources", List.of())));
        when(query.exists("p1")).thenReturn(true);
        when(query.history("p1", "", 0)).thenReturn(new PolicyResponse(Map.of("revisions", List.of(), "page", 0)));
        var pool = Executors.newSingleThreadExecutor();
        try {
            var running = pool.submit(() -> controller.hygiene("p1", 0, "", "", 90));
            assertTrue(inside.await(10, TimeUnit.SECONDS));
            var request = new MockHttpServletRequest();
            for (var refused : List.of(controller.hygiene("p1", 0, "", "", 90), controller.rule("p1", "r1", 90),
                    controller.hygieneCsv("p1", "", "", 90, request), controller.filteredPage("p1", 0, "", "", 90, request),
                    controller.objects("d", "s", 0, "", "", "", request), controller.usage("d", "u", "s", 0),
                    controller.duplicates("d", "s", 0, "", request), controller.installation("d", "s", 0, "", "", request),
                    controller.unused("d", "s", 0), controller.gateways("d", "s", 0), controller.hits("d", "s", 0))) {
                assertEquals(503, refused.getStatusCode().value());
                assertEquals(Map.of("error", "POLICY_BUSY"), refused.getBody());
                assertNotNull(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            }
            // Validation still answers 400 first, and cheap metadata endpoints never queue behind heavy work.
            assertEquals(400, controller.hygiene("p1", -1, "", "", 90).getStatusCode().value());
            assertEquals(200, controller.tree("", "", "").getStatusCode().value());
            assertEquals(200, controller.history("p1", "", 0).getStatusCode().value());
            verify(query, never()).find(argThat(id -> !id.equals("p1")));
            release.countDown();
            assertEquals(200, running.get(10, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals(1, bulkhead.available());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void historyUsesExistenceNotTheFullPolicyAndMissingPolicyIs404() {
        var query = mock(PolicyQueryService.class);
        var controller = new PolicyController(query, new PolicyBulkhead());
        when(query.exists("p1")).thenReturn(true);
        when(query.history("p1", "", 0)).thenReturn(new PolicyResponse(Map.of("revisions", List.of(), "page", 0)));
        assertEquals(200, controller.history("p1", "", 0).getStatusCode().value());
        assertEquals(404, controller.history("missing", "", 0).getStatusCode().value());
        verify(query, never()).find(anyString());
    }

    @Test void theBusyAnswerSurvivesAiviewMaskingUnchanged() {
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(new byte[32]), new TopologyNamePseudonymizer(new byte[32]));
        var servlet = new MockHttpServletRequest();
        servlet.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        var response = new ServletServerHttpResponse(new MockHttpServletResponse());
        var body = PolicyBulkhead.busy().getBody();
        var masked = advice.beforeBodyWrite(body, null, null, null, new ServletServerHttpRequest(servlet), response);
        assertEquals(Map.of("error", "POLICY_BUSY"), masked);
        assertEquals("true", response.getHeaders().getFirst(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER));
    }
}
