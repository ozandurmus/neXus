package com.securityexpert.nexus.ui2.service.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryIngestService;
import com.securityexpert.nexus.ui2.service.advisorystore.AdvisorySources;

/**
 * Ingest endpoints of the advisory fetch workload (docs/design/ADVISORY_MATCHING_CONTRACT.md section 4). Served on the
 * internal port 8086 only ({@code MachinePortConfiguration}); the main port answers 404 for every {@code /internal/**}
 * path. Authenticated by the fetcher's own token in {@code X-Nexus-Advisory-Token}, compared by SHA-256 digest in constant
 * time against {@code UI2_ADVISORY_FETCH_TOKEN_SHA256}; with that variable unset or malformed both endpoints answer 404.
 * This token authorises nothing else and the e2e machine token does not authorise these endpoints. The routes are
 * explicitly ungated in {@code SecurityWebMvcConfig} for the same reason as the machine session route: they are
 * authenticated by their own token, not by a session.
 *
 * <p>Responses carry no stored data: 204, or an error status with a fixed code word (contract section 4). The body of an
 * ingest call is the raw document, read with a hard cap of 16 MiB (contract section 4): a larger declared length is refused
 * before any byte is read, and an undeclared length is cut off at the cap. Tomcat applies no body limit to a raw POST (its
 * {@code maxPostSize} governs form posts only), so no server setting changes for this endpoint or any other.</p>
 */
@RestController
public final class AdvisoryIngestController {

    static final String TOKEN_HEADER = "X-Nexus-Advisory-Token";
    private static final Pattern RUN_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final byte[] expectedDigest;
    private final AdvisoryIngestService ingest;

    public AdvisoryIngestController(@Value("${UI2_ADVISORY_FETCH_TOKEN_SHA256:}") String digest, AdvisoryIngestService ingest) {
        byte[] parsed;
        try { parsed = digest.matches("[0-9a-f]{64}") ? HexFormat.of().parseHex(digest) : null; }
        catch (IllegalArgumentException ignored) { parsed = null; }
        this.expectedDigest = parsed;
        this.ingest = ingest;
    }

    @PostMapping("/internal/advisories/{source}")
    public ResponseEntity<Map<String, String>> document(@PathVariable String source,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = "X-Advisory-Url", required = false) String url,
            @RequestHeader(value = "X-Advisory-Run", required = false) String run, HttpServletRequest request) throws IOException {
        if (expectedDigest == null) return ResponseEntity.notFound().build();
        if (!tokenAccepted(token)) return refused(403, "ADVISORY_TOKEN_REFUSED");
        if (!AdvisorySources.known(source)) return refused(404, "ADVISORY_SOURCE_UNKNOWN");
        if (!AdvisorySources.documentUrlAllowed(source, url)) return refused(400, "ADVISORY_URL_REFUSED");
        if (run == null || !RUN_ID.matcher(run).matches()) return refused(400, "ADVISORY_RUN_INVALID");
        if (!ingest.coreAvailable()) return refused(503, "ADVISORY_CORE_MISSING");
        if (request.getContentLengthLong() > AdvisoryIngestService.MAX_DOCUMENT_BYTES) return refused(413, "ADVISORY_BODY_TOO_LARGE");
        byte[] body = readBounded(request, AdvisoryIngestService.MAX_DOCUMENT_BYTES);
        if (body == null) return refused(413, "ADVISORY_BODY_TOO_LARGE");
        if (body.length == 0) return refused(400, "ADVISORY_BODY_EMPTY");
        return answer(ingest.ingest(source, run, url, body));
    }

    @PostMapping("/internal/advisories/{source}/complete")
    public ResponseEntity<Map<String, String>> complete(@PathVariable String source,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = "X-Advisory-Run", required = false) String run, HttpServletRequest request) throws IOException {
        if (expectedDigest == null) return ResponseEntity.notFound().build();
        if (!tokenAccepted(token)) return refused(403, "ADVISORY_TOKEN_REFUSED");
        if (!AdvisorySources.known(source)) return refused(404, "ADVISORY_SOURCE_UNKNOWN");
        if (run == null || !RUN_ID.matcher(run).matches()) return refused(400, "ADVISORY_RUN_INVALID");
        if (request.getContentLengthLong() > AdvisoryIngestService.MAX_COMPLETE_BYTES) return refused(413, "ADVISORY_BODY_TOO_LARGE");
        byte[] body = readBounded(request, AdvisoryIngestService.MAX_COMPLETE_BYTES);
        if (body == null) return refused(413, "ADVISORY_BODY_TOO_LARGE");
        return answer(ingest.complete(source, run, body));
    }

    private static ResponseEntity<Map<String, String>> answer(AdvisoryIngestService.Outcome outcome) {
        return outcome.code() == null ? ResponseEntity.noContent().build() : refused(outcome.status(), outcome.code());
    }

    private boolean tokenAccepted(String token) {
        byte[] actual = token == null ? new byte[32] : sha256(token);
        return MessageDigest.isEqual(expectedDigest, actual) && token != null;
    }

    /** At most {@code max} bytes of the body, or null when the body is longer. Never holds more than {@code max + 1} bytes. */
    private static byte[] readBounded(HttpServletRequest request, int max) throws IOException {
        try (var in = request.getInputStream()) {
            byte[] data = in.readNBytes(max + 1);
            return data.length > max ? null : data;
        }
    }

    private static ResponseEntity<Map<String, String>> refused(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }

    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
