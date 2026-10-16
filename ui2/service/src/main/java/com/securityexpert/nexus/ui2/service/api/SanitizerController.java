package com.securityexpert.nexus.ui2.service.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.tomcat.util.http.fileupload.FileItemStream;
import org.apache.tomcat.util.http.fileupload.FileUpload;
import org.apache.tomcat.util.http.fileupload.UploadContext;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.sanitizer.SanitizerRepository.JobRow;
import com.securityexpert.nexus.ui2.persistence.sanitizer.SanitizerRepository.RevertRow;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.sanitizer.SanitizerService;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Data Sanitizer API (docs/design/DATA_SANITIZER_CONTRACT.md §3.1). The route map gates every route with
 * {@code data_sanitize_run} or {@code data_sanitize_revert}; this controller then keeps each job to its creator
 * (security administrators see every job and may revert against every vault) and refuses the replay viewer
 * outright. Uploads are streamed into the sanitizer's work area after the gates -- the multipart resolver and the
 * interceptor never parse these bodies. Responses carry ids, states, sizes and counts per kind, never a value from
 * the file; downloads use generated names only.
 */
@RestController
public final class SanitizerController {

    private static final String BASE = "/api/v2/sanitize";
    private static final java.util.regex.Pattern ID =
            java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final SanitizerService service;
    private final RbacEvaluator rbac;
    private final ObjectMapper json = new ObjectMapper();

    public SanitizerController(SanitizerService service, RbacEvaluator rbac) {
        this.service = service;
        this.rbac = rbac;
    }

    // ---- sanitize ------------------------------------------------------------------------------------------

    @PostMapping(path = BASE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> sanitize(HttpServletRequest request) {
        if (!canRun(request)) return error(HttpStatus.FORBIDDEN, "RUN_ROLE_REQUIRED", "Sanitizing needs operator, operation_admin or security_admin");
        try {
            Upload upload = upload(request, Set.of());
            if (upload == null) return error(HttpStatus.BAD_REQUEST, "FILE_REQUIRED", "Send one file part named file");
            String jobId;
            try (InputStream in = upload.file().openStream()) {
                jobId = service.startSanitize(actor(request), in);
            }
            return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                    .body(Map.of("job_id", jobId, "state", "RUNNING"));
        } catch (SanitizerService.Refusal refusal) {
            return refusal(refusal);
        } catch (IOException | RuntimeException invalid) {
            // Multipart and IO errors may carry file names or bytes: never echo or log them.
            return error(HttpStatus.BAD_REQUEST, "UPLOAD_INVALID", "The upload did not complete; send one file part named file");
        }
    }

    @GetMapping(BASE + "/jobs")
    public ResponseEntity<Map<String, Object>> jobs(HttpServletRequest request) {
        // Every session reads what it may do (all false for aiview); the screen renders that and decides nothing.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("permissions", permissions(request));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("max_file_bytes", service.maxBytes());
        limits.put("vault_retention_days", service.retentionDays());
        limits.put("output_ttl_minutes", service.outputTtl().toMinutes());
        limits.put("vault_available", service.vaultAvailable());
        body.put("limits", limits);
        return ok(body);
    }

    @GetMapping(BASE + "/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> job(@PathVariable String jobId, HttpServletRequest request) {
        if (!ID.matcher(jobId).matches()) return invalidId();
        if (!canRun(request)) return error(HttpStatus.FORBIDDEN, "RUN_ROLE_REQUIRED", "Sanitizer access needs a run role");
        try {
            return ok(jobView(service.visibleJob(jobId, actor(request), isSecurityAdmin(request))));
        } catch (SanitizerService.Refusal refusal) {
            return refusal(refusal);
        }
    }

    @GetMapping(BASE + "/jobs/{jobId}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String jobId, HttpServletRequest request) {
        if (!ID.matcher(jobId).matches()) return streamError(HttpStatus.BAD_REQUEST, "INVALID_ID");
        if (!canRun(request)) return streamError(HttpStatus.FORBIDDEN, "RUN_ROLE_REQUIRED");
        try {
            JobRow row = service.visibleJob(jobId, actor(request), isSecurityAdmin(request));
            if (!"SUCCEEDED".equals(row.state())) return streamError(HttpStatus.CONFLICT, "JOB_NOT_READY");
            InputStream in = service.openOutput(jobId, actor(request), isSecurityAdmin(request));
            return stream(in, "sanitized-" + jobId.substring(0, 8), row.fileType(), row.outputBytes());
        } catch (SanitizerService.Refusal refusal) {
            return streamError(status(refusal.code()), refusal.code());
        } catch (IOException missing) {
            return streamError(HttpStatus.GONE, SanitizerService.OUTPUT_GONE);
        }
    }

    // ---- revert --------------------------------------------------------------------------------------------

    @PostMapping(path = BASE + "/revert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> revert(HttpServletRequest request) {
        if (!canRevert(request)) return error(HttpStatus.FORBIDDEN, "REVERT_ROLE_REQUIRED", "Reverting needs security_admin or the sanitize job's creator");
        try {
            Upload upload = upload(request, Set.of("source_job_id", "scope"));
            if (upload == null) return error(HttpStatus.BAD_REQUEST, "FILE_REQUIRED", "Send one file part named file");
            String source = upload.fields().get("source_job_id");
            if (source != null && !source.isBlank() && !ID.matcher(source).matches()) return invalidId();
            boolean admin = isSecurityAdmin(request);
            SanitizerService.Scope scope = source != null && !source.isBlank() ? SanitizerService.Scope.ONE_JOB
                    : "all".equals(upload.fields().get("scope")) ? SanitizerService.Scope.ALL_JOBS
                    : admin ? SanitizerService.Scope.ALL_JOBS : SanitizerService.Scope.OWN_JOBS;
            String revertId;
            try (InputStream in = upload.file().openStream()) {
                revertId = service.startRevert(actor(request), in, scope, scope == SanitizerService.Scope.ONE_JOB ? source : null, admin);
            }
            return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                    .body(Map.of("revert_id", revertId, "state", "RUNNING", "scope", scope.name()));
        } catch (SanitizerService.Refusal refusal) {
            return refusal(refusal);
        } catch (IOException | RuntimeException invalid) {
            return error(HttpStatus.BAD_REQUEST, "UPLOAD_INVALID", "The upload did not complete; send one file part named file");
        }
    }

    @GetMapping(BASE + "/reverts/{revertId}")
    public ResponseEntity<Map<String, Object>> revertJob(@PathVariable String revertId, HttpServletRequest request) {
        if (!ID.matcher(revertId).matches()) return invalidId();
        if (!canRevert(request)) return error(HttpStatus.FORBIDDEN, "REVERT_ROLE_REQUIRED", "Revert access needs a revert role");
        try {
            return ok(revertView(service.visibleRevert(revertId, actor(request), isSecurityAdmin(request))));
        } catch (SanitizerService.Refusal refusal) {
            return refusal(refusal);
        }
    }

    @GetMapping(BASE + "/reverts/{revertId}/download")
    public ResponseEntity<StreamingResponseBody> revertDownload(@PathVariable String revertId, HttpServletRequest request) {
        if (!ID.matcher(revertId).matches()) return streamError(HttpStatus.BAD_REQUEST, "INVALID_ID");
        if (!canRevert(request)) return streamError(HttpStatus.FORBIDDEN, "REVERT_ROLE_REQUIRED");
        try {
            RevertRow row = service.visibleRevert(revertId, actor(request), isSecurityAdmin(request));
            if (!"SUCCEEDED".equals(row.state())) return streamError(HttpStatus.CONFLICT, "JOB_NOT_READY");
            InputStream in = service.openRevertOutput(revertId, actor(request), isSecurityAdmin(request));
            return stream(in, "reverted-" + revertId.substring(0, 8), row.fileType(), row.outputBytes());
        } catch (SanitizerService.Refusal refusal) {
            return streamError(status(refusal.code()), refusal.code());
        } catch (IOException missing) {
            return streamError(HttpStatus.GONE, SanitizerService.OUTPUT_GONE);
        }
    }

    // ---- views ---------------------------------------------------------------------------------------------

    private Map<String, Object> jobView(JobRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("job_id", row.jobId());
        view.put("state", row.state());
        view.put("file_type", row.fileType());
        view.put("created_at", String.valueOf(row.createdAt()));
        view.put("finished_at", row.finishedAt() == null ? null : row.finishedAt().toString());
        view.put("input_bytes", row.inputBytes());
        view.put("output_bytes", row.outputBytes());
        view.put("counts", parse(row.countsJson()));
        view.put("failure_reason", row.failureReason());
        view.put("output_state", row.outputState());
        view.put("vault_entries", row.vaultEntries());
        view.put("vault_expires_at", String.valueOf(row.vaultExpiresAt()));
        view.put("vault_pruned", row.vaultPrunedAt() != null);
        return view;
    }

    private Map<String, Object> revertView(RevertRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("revert_id", row.revertId());
        view.put("state", row.state());
        view.put("scope", row.scope());
        view.put("source_job_id", row.sourceJobId());
        view.put("file_type", row.fileType());
        view.put("created_at", String.valueOf(row.createdAt()));
        view.put("finished_at", row.finishedAt() == null ? null : row.finishedAt().toString());
        view.put("input_bytes", row.inputBytes());
        view.put("output_bytes", row.outputBytes());
        view.put("counts", parse(row.countsJson()));
        view.put("failure_reason", row.failureReason());
        view.put("output_state", row.outputState());
        return view;
    }

    private Object parse(String counts) {
        if (counts == null) return Map.of();
        try {
            return json.readValue(counts, new TypeReference<Map<String, Long>>() { });
        } catch (IOException unreadable) {
            return Map.of();
        }
    }

    // ---- multipart -----------------------------------------------------------------------------------------

    private record Upload(Map<String, String> fields, FileItemStream file) {
    }

    /** Small form fields first (only {@code allowedFields}), then exactly one file part named {@code file}. */
    private static Upload upload(HttpServletRequest request, Set<String> allowedFields) throws IOException {
        FileUpload upload = new FileUpload();
        upload.setFileCountMax(1 + allowedFields.size());
        upload.setHeaderEncoding("UTF-8");
        var parts = upload.getItemIterator(new UploadContext() {
            public String getCharacterEncoding() { return "UTF-8"; }
            public String getContentType() { return request.getContentType(); }
            public long contentLength() { return request.getContentLengthLong(); }
            public InputStream getInputStream() throws IOException { return request.getInputStream(); }
        });
        Map<String, String> fields = new LinkedHashMap<>();
        while (parts.hasNext()) {
            FileItemStream part = parts.next();
            if (part.isFormField()) {
                if (!allowedFields.contains(part.getFieldName())) return null;
                try (InputStream in = part.openStream()) {
                    byte[] value = in.readNBytes(129);
                    if (value.length > 128) return null;
                    fields.put(part.getFieldName(), new String(value, StandardCharsets.UTF_8).strip().toLowerCase(Locale.ROOT));
                }
                continue;
            }
            return "file".equals(part.getFieldName()) ? new Upload(fields, part) : null;
        }
        return null;
    }

    // ---- authorization -------------------------------------------------------------------------------------

    private Map<String, Object> permissions(HttpServletRequest request) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("run", canRun(request));
        p.put("revert", canRevert(request));
        p.put("revert_any_job", canRevert(request) && isSecurityAdmin(request));
        return p;
    }

    private boolean canRun(HttpServletRequest request) {
        return holds(request, ActionRegistry.DATA_SANITIZE_RUN_ROLES);
    }

    /** Revert is held by every run role; for anyone but a security administrator it is limited to their own jobs. */
    private boolean canRevert(HttpServletRequest request) {
        return holds(request, ActionRegistry.DATA_SANITIZE_RUN_ROLES);
    }

    private boolean isSecurityAdmin(HttpServletRequest request) {
        return holds(request, ActionRegistry.DATA_SANITIZE_REVERT_ANY_JOB_ROLES);
    }

    /** The replay viewer never holds a sanitizer authority, even if it were also bound to such a role. */
    private boolean holds(HttpServletRequest request, Set<String> roles) {
        String actor = actor(request);
        return actor != null && !PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)
                && rbac.evaluateAny(actor, roles, Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }

    private static String actor(HttpServletRequest request) {
        return (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }

    // ---- responses -----------------------------------------------------------------------------------------

    private static ResponseEntity<StreamingResponseBody> stream(InputStream in, String name, String fileType, Long bytes) {
        String extension = switch (fileType == null ? "" : fileType) {
            case "pcap" -> "pcap";
            case "pcapng" -> "pcapng";
            case "xlsx" -> "xlsx";
            case "json" -> "json";
            case "csv" -> "csv";
            default -> "txt";
        };
        StreamingResponseBody body = out -> {
            try (InputStream source = in) {
                source.transferTo(out);
            }
        };
        var response = ResponseEntity.ok().header("Content-Type", "application/octet-stream")
                .header("Content-Disposition", "attachment; filename=\"" + name + "." + extension + "\"")
                .header("Cache-Control", "no-store");
        if (bytes != null) response = response.header("Content-Length", Long.toString(bytes));
        return response.body(body);
    }

    private static HttpStatus status(String code) {
        return switch (code) {
            case SanitizerService.TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case SanitizerService.BUSY -> HttpStatus.TOO_MANY_REQUESTS;
            case SanitizerService.VAULT_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case SanitizerService.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SanitizerService.NOT_OWNER -> HttpStatus.FORBIDDEN;
            case SanitizerService.OUTPUT_GONE -> HttpStatus.GONE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static ResponseEntity<Map<String, Object>> refusal(SanitizerService.Refusal refusal) {
        return error(status(refusal.code()), refusal.code(), refusal.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("reason", reason);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Map<String, Object>> invalidId() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_ID", "Identifiers are opaque UUIDs");
    }

    private static ResponseEntity<StreamingResponseBody> streamError(HttpStatus status, String code) {
        byte[] payload = ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status).header("Content-Type", "application/json").body(out -> out.write(payload));
    }

}
