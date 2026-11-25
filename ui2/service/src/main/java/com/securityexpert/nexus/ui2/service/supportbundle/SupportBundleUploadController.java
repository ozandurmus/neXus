package com.securityexpert.nexus.ui2.service.supportbundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore.UploadRow;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.supportbundle.upload.SupportBundleUploadService;
import com.securityexpert.nexus.ui2.service.supportbundle.upload.UploadDestination;

/**
 * Vendor upload and work directory cleanup routes. The upload request carries the credential once, in the body; it is handed
 * to the upload job in a {@code char[]}, never stored, never echoed, never part of any response or audit row. aiview reads
 * the upload list masked (no actor, reason, case reference or remote account) and can do nothing else here.
 */
@RestController
public final class SupportBundleUploadController {

    /** {@code password} is a char[] so it can be wiped; the record's toString does not print it. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UploadBody_(@JsonProperty("destination") String destination, @JsonProperty("host") String host,
            @JsonProperty("port") Integer port, @JsonProperty("user") String user, @JsonProperty("password") char[] password,
            @JsonProperty("item_kinds") List<String> itemKinds, @JsonProperty("case_ref") String caseRef,
            @JsonProperty("reason") String reason, @JsonProperty("confirm") Boolean confirm) {
        @Override public String toString() { return "UploadBody_[redacted]"; }
    }

    public record CleanupBody(@JsonProperty("reason") String reason, @JsonProperty("confirm") Boolean confirm) {
    }

    private final SupportBundleUploadService uploads;
    private final SupportBundleCleanupService cleanup;
    private final com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore bundles;

    public SupportBundleUploadController(SupportBundleUploadService uploads, SupportBundleCleanupService cleanup,
            com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore bundles) {
        this.uploads = uploads; this.cleanup = cleanup; this.bundles = bundles;
    }

    @GetMapping("/api/v2/support-bundles/upload-destinations")
    public ResponseEntity<Map<String, Object>> destinations() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UploadDestination d : UploadDestination.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.id());
            row.put("label", d.label());
            row.put("method", d.method().name());
            row.put("vendor", d.vendor());
            row.put("host", d.fixedHost());
            row.put("port", d.defaultPort());
            row.put("generic", d.generic());
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("destinations", rows);
        body.put("manual_only_vendors", List.of("check_point"));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/v2/support-bundles/{id}/uploads")
    public ResponseEntity<Map<String, Object>> list(@PathVariable String id, HttpServletRequest http) {
        Optional<UUID> uuid = uuid(id);
        if (uuid.isEmpty()) return error(HttpStatus.BAD_REQUEST, "INVALID_ID", "Identifier must be a UUID");
        boolean viewer = isViewer(http);
        com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow bundle =
                viewer && bundles != null ? bundles.find(uuid.get()).orElse(null) : null;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UploadRow r : uploads.list(uuid.get())) rows.add(row(r, viewer, bundle));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uploads", rows);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/v2/support-bundles/{id}/uploads")
    public ResponseEntity<Map<String, Object>> upload(@PathVariable String id, @RequestBody(required = false) UploadBody_ body,
            HttpServletRequest http) {
        ResponseEntity<Map<String, Object>> refusal = refuseViewerOrAnonymous(http);
        if (refusal != null) return refusal;
        Optional<UUID> uuid = uuid(id);
        if (uuid.isEmpty()) return error(HttpStatus.BAD_REQUEST, "INVALID_ID", "Identifier must be a UUID");
        if (body == null) return error(HttpStatus.BAD_REQUEST, "BODY_REQUIRED", "A request body is required");
        SupportBundleUploadService.Outcome outcome = uploads.start(uuid.get(), actor(http),
                new SupportBundleUploadService.Spec(body.destination(), body.host(), body.port(), body.user(), body.password(),
                        body.itemKinds(), body.caseRef(), body.reason(), Boolean.TRUE.equals(body.confirm())));
        return switch (outcome) {
            case SupportBundleUploadService.Outcome.Started started ->
                    ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("upload_id", started.uploadId().toString(), "status", "RUNNING"));
            case SupportBundleUploadService.Outcome.Refused refused ->
                    error(HttpStatus.valueOf(refused.status()), refused.code(), refused.reason());
        };
    }

    @PostMapping("/api/v2/support-bundles/{id}/cleanup-workdir")
    public ResponseEntity<Map<String, Object>> cleanupWorkDir(@PathVariable String id, @RequestBody(required = false) CleanupBody body,
            HttpServletRequest http) {
        ResponseEntity<Map<String, Object>> refusal = refuseViewerOrAnonymous(http);
        if (refusal != null) return refusal;
        Optional<UUID> uuid = uuid(id);
        if (uuid.isEmpty()) return error(HttpStatus.BAD_REQUEST, "INVALID_ID", "Identifier must be a UUID");
        if (body == null) return error(HttpStatus.BAD_REQUEST, "BODY_REQUIRED", "A request body is required");
        SupportBundleService.Outcome outcome = cleanup.cleanup(uuid.get(), actor(http), body.reason(), Boolean.TRUE.equals(body.confirm()));
        return switch (outcome) {
            case SupportBundleService.Outcome.Accepted accepted ->
                    ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", id, "job_id", accepted.jobId(), "status", "ACCEPTED"));
            case SupportBundleService.Outcome.Refused refused ->
                    error(HttpStatus.valueOf(refused.status()), refused.code(), refused.reason());
        };
    }

    static Map<String, Object> row(UploadRow r, boolean viewer) {
        return row(r, viewer, null);
    }

    private static Map<String, Object> row(UploadRow r, boolean viewer,
            com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow bundle) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id().toString());
        m.put("request_id", r.requestId().toString());
        m.put("destination", r.destinationKind());
        // a vendor host is a fixed product constant; a host the administrator typed for a generic server is withheld from aiview
        boolean fixedVendorHost = UploadDestination.fromId(r.destinationKind()).map(d -> !d.generic()).orElse(false);
        m.put("destination_host", viewer && !fixedVendorHost ? null : r.destinationHost());
        m.put("destination_port", r.destinationPort());
        m.put("remote_name", r.remoteName());
        m.put("state", r.state());
        m.put("started_at", r.startedAt());
        m.put("finished_at", r.finishedAt());
        m.put("attempts", r.attempts());
        m.put("bytes_total", r.bytesTotal());
        m.put("bytes_sent", r.bytesSent());
        m.put("sha256", r.sha256());
        // For a generic (administrator-typed) or unrecognized destination, withhold remote_status and error_detail
        // just like destination_host; for a fixed vendor destination, scrub any echoed withheld upload/bundle references
        // and let PrivacyMaskingResponseBodyAdvice apply its free-text masking. error_code stays visible.
        m.put("remote_status", viewer ? (fixedVendorHost ? scrubWithheld(r.remoteStatus(), r, bundle) : null) : r.remoteStatus());
        m.put("error_code", r.errorCode());
        m.put("error_detail", viewer ? (fixedVendorHost ? scrubWithheld(r.errorDetail(), r, bundle) : null) : r.errorDetail());
        m.put("host_key_changed", r.hostKeyChanged());
        m.put("item_count", r.itemIds().size());
        if (!viewer) {
            m.put("requested_by", r.requestedBy());
            m.put("remote_user", r.remoteUser());
            m.put("case_ref", r.caseRef());
            m.put("reason", r.reason());
            m.put("host_key_fingerprint", r.hostKeyFingerprint());
        }
        return m;
    }

    private static final java.util.Set<String> FIXED_PROTOCOL_WORDS = java.util.Set.of("http", "https", "sftp", "scp");

    private static String scrubWithheld(String text, UploadRow r,
            com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow bundle) {
        if (text == null || text.isBlank()) {
            return text;
        }
        List<String> candidates = new ArrayList<>(8);
        addCandidate(candidates, r.remoteUser());
        addCandidate(candidates, r.caseRef());
        addCandidate(candidates, r.requestedBy());
        addCandidate(candidates, r.reason());
        if (bundle != null) {
            addCandidate(candidates, bundle.caseRef());
            addCandidate(candidates, bundle.requestedBy());
            addCandidate(candidates, bundle.reason());
            addCandidate(candidates, bundle.deletedBy());
        }
        if (candidates.isEmpty()) {
            return text;
        }
        candidates.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String result = text;
        for (String token : candidates) {
            String left = isAsciiAlnum(token.charAt(0)) ? "(?<![A-Za-z0-9])" : "";
            String right = isAsciiAlnum(token.charAt(token.length() - 1)) ? "(?![A-Za-z0-9])" : "";
            result = java.util.regex.Pattern.compile(
                            left + java.util.regex.Pattern.quote(token) + right,
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(result)
                    .replaceAll("[REDACTED]");
        }
        return result;
    }

    private static void addCandidate(List<String> out, String raw) {
        if (raw == null) return;
        String trimmed = raw.strip();
        if (trimmed.length() < 2) return;
        if (trimmed.length() == 2 && trimmed.chars().noneMatch(Character::isDigit)) return;
        if (FIXED_PROTOCOL_WORDS.contains(trimmed.toLowerCase(java.util.Locale.ROOT))) return;
        if (!out.contains(trimmed)) {
            out.add(trimmed);
        }
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static ResponseEntity<Map<String, Object>> refuseViewerOrAnonymous(HttpServletRequest http) {
        String actor = actor(http);
        if (actor == null || actor.isBlank()) return error(HttpStatus.FORBIDDEN, "AUTHENTICATION_REQUIRED", "An authenticated actor is required");
        if (isViewer(http)) return error(HttpStatus.FORBIDDEN, "FORBIDDEN_FOR_VIEWER", "This action is not available to the replay viewer");
        return null;
    }

    private static boolean isViewer(HttpServletRequest http) {
        return http != null && Boolean.TRUE.equals(http.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE));
    }

    private static String actor(HttpServletRequest http) {
        return http == null ? null : (String) http.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }

    private static Optional<UUID> uuid(String id) {
        try { return Optional.of(UUID.fromString(id)); } catch (RuntimeException e) { return Optional.empty(); }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("code", code);
        body.put("reason", reason);
        return ResponseEntity.status(status).body(body);
    }
}
