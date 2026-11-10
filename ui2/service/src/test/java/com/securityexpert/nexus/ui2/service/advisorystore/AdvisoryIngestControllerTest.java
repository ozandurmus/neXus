package com.securityexpert.nexus.ui2.service.advisorystore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.*;
import com.securityexpert.nexus.ui2.service.api.AdvisoryIngestController;
import com.securityexpert.nexus.ui2.service.api.MachineSessionController;
import com.securityexpert.nexus.ui2.service.api.SessionCookieWriter;
import com.securityexpert.nexus.ui2.service.security.LocalRoleTokenResolver;
import com.securityexpert.nexus.ui2.service.security.LoginFlow;

/** Contract section 4: token, address allow-list, run id, size cap, run accounting, code-word-only answers. */
class AdvisoryIngestControllerTest {
    private static final String URL = "https://security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories";
    private static final String RUN = "run-0000001";
    // Built at run time: no token-shaped literal in the source (gitleaks).
    private final String fetchToken = "fetch" + "-" + java.util.UUID.randomUUID();
    private final String machineToken = "machine" + "-" + java.util.UUID.randomUUID();

    private final Env env = new Env();

    private static final class Env {
        final AdvisoryTestSupport.MemoryRepository repo = new AdvisoryTestSupport.MemoryRepository();
        final AdvisoryTestSupport.FakeCore core = new AdvisoryTestSupport.FakeCore();
    }
    private final AdvisoryIngestService service = new AdvisoryIngestService(env.repo, () -> Optional.of(env.core));

    private static String digest(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private AdvisoryIngestController controller() throws Exception { return new AdvisoryIngestController(digest(fetchToken), service); }

    private static MockHttpServletRequest post(byte[] body) {
        var request = new MockHttpServletRequest("POST", "/internal/advisories/paloalto");
        request.setLocalPort(8086);
        request.setContent(body);
        return request;
    }

    private int send(AdvisoryIngestController c, String token, String url, String run, byte[] body) throws Exception {
        return c.document("paloalto", token, url, run, post(body)).getStatusCode().value();
    }

    @Test void withoutTheEnvironmentDigestBothEndpointsAreNotFound() throws Exception {
        for (String unset : new String[] {"", "not-a-digest", "A".repeat(64)}) {
            var c = new AdvisoryIngestController(unset, service);
            assertEquals(404, send(c, fetchToken, URL, RUN, new byte[] {1}));
            assertEquals(404, c.complete("paloalto", fetchToken, RUN, post(new byte[] {1})).getStatusCode().value());
        }
    }

    @Test void aWrongOrMissingTokenIsRefusedAndNothingIsStored() throws Exception {
        env.core.parsers.put("paloalto", (u, d) -> new ParseResult(List.of(), List.of(), 0));
        var c = controller();
        assertEquals(403, send(c, "other-" + fetchToken, URL, RUN, new byte[] {1}));
        assertEquals(403, send(c, null, URL, RUN, new byte[] {1}));
        assertEquals(403, send(c, "", URL, RUN, new byte[] {1}));
        assertEquals(403, c.complete("paloalto", "wrong", RUN, post("{}".getBytes(StandardCharsets.UTF_8))).getStatusCode().value());
        assertTrue(env.repo.runs.isEmpty());
        assertEquals("ADVISORY_TOKEN_REFUSED", c.document("paloalto", null, URL, RUN, post(new byte[] {1})).getBody().get("error"));
    }

    @Test void theE2eMachineTokenDoesNotOpenIngestAndTheFetchTokenDoesNotOpenTheMachineSession() throws Exception {
        env.core.parsers.put("paloalto", (u, d) -> new ParseResult(List.of(), List.of(), 0));
        // Each endpoint is configured with its own digest, as the deployment does with two secrets.
        var ingest = new AdvisoryIngestController(digest(fetchToken), service);
        assertEquals(403, send(ingest, machineToken, URL, RUN, new byte[] {1}), "machine token on ingest");
        assertEquals(204, send(ingest, fetchToken, URL, RUN, new byte[] {1}));
        var sessions = mock(SessionRepository.class);
        var machine = new MachineSessionController(digest(machineToken), mock(LocalCredentialsRepository.class),
                mock(LocalRoleTokenResolver.class), sessions, new LoginFlow(sessions, Duration.ofMinutes(30), Duration.ofHours(10)),
                new SessionCookieWriter(true));
        assertEquals(403, machine.login(fetchToken, new MockHttpServletResponse()).getStatusCode().value(), "fetch token on machine session");
        verify(sessions, never()).createMachineActive(anyString(), anyString(), anyString(), any(), any(), any());
        // Even with identical configuration the digests would be separate secrets; a shared value is a deployment error.
        assertNotEquals(digest(fetchToken), digest(machineToken));
    }

    @Test void theAddressRunIdAndSourceAreChecked() throws Exception {
        env.core.parsers.put("paloalto", (u, d) -> new ParseResult(List.of(), List.of(), 0));
        var c = controller();
        assertEquals(400, send(c, fetchToken, "https://evil.example.test/api/v1/products/PAN-OS/advisories", RUN, new byte[] {1}));
        assertEquals(400, send(c, fetchToken, "http://security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories", RUN, new byte[] {1}));
        assertEquals(400, send(c, fetchToken, null, RUN, new byte[] {1}));
        assertEquals(400, send(c, fetchToken, URL, "short", new byte[] {1}));
        assertEquals(400, send(c, fetchToken, URL, "bad run id with spaces", new byte[] {1}));
        assertEquals(400, send(c, fetchToken, URL, null, new byte[] {1}));
        assertEquals(404, c.document("checkpoint", fetchToken, URL, RUN, post(new byte[] {1})).getStatusCode().value());
        assertEquals(400, c.complete("paloalto", fetchToken, "short", post(new byte[] {1})).getStatusCode().value());
        assertTrue(env.repo.runs.isEmpty());
    }

    @Test void theBodyIsCappedAt16MiBAndEmptyIsRefused() throws Exception {
        env.core.parsers.put("paloalto", (u, d) -> new ParseResult(List.of(), List.of(), 0));
        var c = controller();
        int cap = AdvisoryIngestService.MAX_DOCUMENT_BYTES;
        assertEquals(16 * 1024 * 1024, cap);
        assertEquals(204, send(c, fetchToken, URL, RUN, new byte[cap]));
        assertEquals(413, send(c, fetchToken, URL, "run-0000002", new byte[cap + 1]));
        // A declared length over the cap is refused before a byte is read.
        var declared = post(new byte[] {1}) ;
        declared.addHeader("Content-Length", cap + 1);
        var stream = spy(declared);
        doReturn((long) cap + 1).when(stream).getContentLengthLong();
        assertEquals(413, c.document("paloalto", fetchToken, URL, "run-0000003", stream).getStatusCode().value());
        verify(stream, never()).getInputStream();
        assertEquals(400, send(c, fetchToken, URL, "run-0000004", new byte[0]));
        assertEquals(413, c.complete("paloalto", fetchToken, RUN, post(new byte[5000])).getStatusCode().value());
        assertNull(env.repo.runs.get("run-0000002|paloalto"), "a refused body opens no run");
    }

    @Test void aMissingCoreAnswers503WithTheCodeWordAndNoParserAnswers503() throws Exception {
        var noCore = new AdvisoryIngestController(digest(fetchToken), new AdvisoryIngestService(env.repo, () -> Optional.empty()));
        var response = noCore.document("paloalto", fetchToken, URL, RUN, post(new byte[] {1}));
        assertEquals(503, response.getStatusCode().value());
        assertEquals("ADVISORY_CORE_MISSING", response.getBody().get("error"));
        var c = controller();
        assertEquals(503, send(c, fetchToken, URL, RUN, new byte[] {1}), "core present, parser for the source absent");
    }

    @Test void documentsAreStoredAndTheRunIsClosedWithTheFetchersCounts() throws Exception {
        env.core.parsers.put("paloalto", (u, d) -> new ParseResult(List.of(
                AdvisoryTestSupport.advisory("paloalto", "CVE-2026-0001", Severity.HIGH,
                        ParseState.PARSED, List.of(), List.of())), List.of(), 0));
        var c = controller();
        var ok = c.document("paloalto", fetchToken, URL, RUN, post("doc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(204, ok.getStatusCode().value());
        assertNull(ok.getBody(), "no stored data in the answer");
        assertEquals(1, env.repo.advisories.size());
        var done = c.complete("paloalto", fetchToken, RUN, post("{\"documents_fetched\":1,\"documents_failed\":0,\"reason\":null}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(204, done.getStatusCode().value());
        assertEquals("OK", env.repo.run(RUN, "paloalto").orElseThrow().outcome());
        assertEquals(400, c.complete("paloalto", fetchToken, "run-0000009", post("nope".getBytes(StandardCharsets.UTF_8))).getStatusCode().value());
    }
}
