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

import com.securityexpert.nexus.ui2.service.lifecyclesource.LifecycleSourceService;
import com.securityexpert.nexus.ui2.service.lifecyclesource.LifecycleSources;

/**
 * Ingest endpoints for the lifecycle catalog's online source (docs/design/LIFECYCLE_CATALOG_SOURCES_CONTRACT.md section 3.1),
 * the same shape as {@link AdvisoryIngestController}: internal port 8086 only, the advisory-fetch workload's own token in
 * {@code X-Nexus-Advisory-Token} compared by SHA-256 digest in constant time against
 * {@code UI2_ADVISORY_FETCH_TOKEN_SHA256} (404 for both endpoints when it is unset or malformed), a hard 16 MiB body cap,
 * and responses that carry no stored data. That token authorises these two endpoints and the advisory ones, all of which
 * only take public documents in; nothing else.
 */
@RestController
public final class LifecycleSourceIngestController {

    private static final Pattern RUN_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final byte[] expectedDigest;
    private final LifecycleSourceService ingest;

    public LifecycleSourceIngestController(@Value("${UI2_ADVISORY_FETCH_TOKEN_SHA256:}") String digest, LifecycleSourceService ingest) {
        byte[] parsed;
        try { parsed = digest.matches("[0-9a-f]{64}") ? HexFormat.of().parseHex(digest) : null; }
        catch (IllegalArgumentException ignored) { parsed = null; }
        this.expectedDigest = parsed;
        this.ingest = ingest;
    }

    @PostMapping("/internal/lifecycle-sources/{source}")
    public ResponseEntity<Map<String, String>> document(@PathVariable String source,
            @RequestHeader(value = AdvisoryIngestController.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = "X-Advisory-Url", required = false) String url,
            @RequestHeader(value = "X-Advisory-Run", required = false) String run, HttpServletRequest request) throws IOException {
        if (expectedDigest == null) return ResponseEntity.notFound().build();
        if (!tokenAccepted(token)) return refused(403, "LIFECYCLE_TOKEN_REFUSED");
        if (!LifecycleSources.feedKnown(source)) return refused(404, "LIFECYCLE_SOURCE_UNKNOWN");
        if (!LifecycleSources.feedDocumentAllowed(source, url)) return refused(400, "LIFECYCLE_URL_REFUSED");
        if (run == null || !RUN_ID.matcher(run).matches()) return refused(400, "LIFECYCLE_RUN_INVALID");
        if (request.getContentLengthLong() > LifecycleSourceService.MAX_DOCUMENT_BYTES) return refused(413, "LIFECYCLE_BODY_TOO_LARGE");
        byte[] body = readBounded(request, LifecycleSourceService.MAX_DOCUMENT_BYTES);
        if (body == null) return refused(413, "LIFECYCLE_BODY_TOO_LARGE");
        if (body.length == 0) return refused(400, "LIFECYCLE_BODY_EMPTY");
        return answer(ingest.ingest(source, run, url, body));
    }

    @PostMapping("/internal/lifecycle-sources/{source}/complete")
    public ResponseEntity<Map<String, String>> complete(@PathVariable String source,
            @RequestHeader(value = AdvisoryIngestController.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = "X-Advisory-Run", required = false) String run, HttpServletRequest request) throws IOException {
        if (expectedDigest == null) return ResponseEntity.notFound().build();
        if (!tokenAccepted(token)) return refused(403, "LIFECYCLE_TOKEN_REFUSED");
        if (!LifecycleSources.feedKnown(source)) return refused(404, "LIFECYCLE_SOURCE_UNKNOWN");
        if (run == null || !RUN_ID.matcher(run).matches()) return refused(400, "LIFECYCLE_RUN_INVALID");
        if (request.getContentLengthLong() > LifecycleSourceService.MAX_COMPLETE_BYTES) return refused(413, "LIFECYCLE_BODY_TOO_LARGE");
        byte[] body = readBounded(request, LifecycleSourceService.MAX_COMPLETE_BYTES);
        if (body == null) return refused(413, "LIFECYCLE_BODY_TOO_LARGE");
        return answer(ingest.complete(source, run, body));
    }

    private static ResponseEntity<Map<String, String>> answer(LifecycleSourceService.Outcome outcome) {
        return outcome.code() == null ? ResponseEntity.noContent().build() : refused(outcome.status(), outcome.code());
    }

    private boolean tokenAccepted(String token) {
        byte[] actual = token == null ? new byte[32] : sha256(token);
        return MessageDigest.isEqual(expectedDigest, actual) && token != null;
    }

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
