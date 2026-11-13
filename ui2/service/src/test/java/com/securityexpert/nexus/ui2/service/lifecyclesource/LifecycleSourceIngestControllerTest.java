package com.securityexpert.nexus.ui2.service.lifecyclesource;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.service.api.LifecycleSourceIngestController;

/** Contract section 3.1: the fetcher's token, the bulletin address list, the run id, the size cap, code-word answers. */
class LifecycleSourceIngestControllerTest {

    private static final String URL = "https://www.cisco.com/c/en/us/products/collateral/security/firepower-ngfw/firepower-4100-series-eol.html";
    private static final String RUN = "run-0000001";
    // Built at run time: no token-shaped literal in the source (gitleaks).
    private final String token = "fetch" + "-" + java.util.UUID.randomUUID();
    private final LifecycleSourceServiceTest.Memory memory = new LifecycleSourceServiceTest.Memory();
    private final LifecycleSourceService service = new LifecycleSourceService(memory);

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MockHttpServletRequest post(byte[] body) {
        var request = new MockHttpServletRequest("POST", "/internal/lifecycle-sources/cisco_eol");
        request.setLocalPort(8086);
        request.setContent(body);
        return request;
    }

    private static final byte[] BULLETIN = ("<html><head><title>End-of-Sale and End-of-Life Announcement for the Cisco Firepower 4100</title></head>"
            + "<body>EOL00042<table><tr><th>Milestone</th><th>Date</th></tr><tr><td>Last Date of Support: HW</td><td>January 31, 2031</td></tr></table>"
            + "<table><tr><th>End-of-Sale Product Part Number</th></tr><tr><td>FPR-4115-K9</td></tr></table></body></html>").getBytes(StandardCharsets.UTF_8);

    @Test
    void withoutTheDigestBothEndpointsAreNotFoundAndAWrongTokenStoresNothing() throws Exception {
        var unset = new LifecycleSourceIngestController("", service);
        assertEquals(404, unset.document("cisco_eol", token, URL, RUN, post(BULLETIN)).getStatusCode().value());
        assertEquals(404, unset.complete("cisco_eol", token, RUN, post(new byte[] {1})).getStatusCode().value());
        var c = new LifecycleSourceIngestController(digest(token), service);
        assertEquals(403, c.document("cisco_eol", "other-" + token, URL, RUN, post(BULLETIN)).getStatusCode().value());
        assertEquals(403, c.document("cisco_eol", null, URL, RUN, post(BULLETIN)).getStatusCode().value());
        assertTrue(memory.runs.isEmpty());
        assertTrue(memory.entries.isEmpty());
    }

    @Test
    void onlyTheKnownSourceABulletinAddressAndAValidRunAreAccepted() throws Exception {
        var c = new LifecycleSourceIngestController(digest(token), service);
        assertEquals(404, c.document("paloalto_eol", token, URL, RUN, post(BULLETIN)).getStatusCode().value());
        assertEquals("LIFECYCLE_URL_REFUSED", c.document("cisco_eol", token, "https://www.example.org/x.html", RUN, post(BULLETIN)).getBody().get("error"));
        assertEquals("LIFECYCLE_RUN_INVALID", c.document("cisco_eol", token, URL, "x", post(BULLETIN)).getBody().get("error"));
        assertEquals("LIFECYCLE_BODY_EMPTY", c.document("cisco_eol", token, URL, RUN, post(new byte[0])).getBody().get("error"));
        var tooLarge = post(new byte[LifecycleSourceService.MAX_DOCUMENT_BYTES + 1]);
        assertEquals(413, c.document("cisco_eol", token, URL, RUN, tooLarge).getStatusCode().value());
        assertTrue(memory.entries.isEmpty());
        assertEquals(204, c.document("cisco_eol", token, URL, RUN, post(BULLETIN)).getStatusCode().value());
        assertEquals(1, memory.entries.size());
        assertEquals(204, c.complete("cisco_eol", token, RUN, post("{\"documents_fetched\":3,\"documents_failed\":0,\"reason\":null}"
                .getBytes(StandardCharsets.UTF_8))).getStatusCode().value());
        var closed = c.document("cisco_eol", token, URL, RUN, post(BULLETIN));
        assertEquals(409, closed.getStatusCode().value());
        assertEquals("LIFECYCLE_RUN_CLOSED", closed.getBody().get("error"));
    }
}
