package com.securityexpert.nexus.ui2.service.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.tomcat.util.http.fileupload.FileUpload;
import org.apache.tomcat.util.http.fileupload.UploadContext;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.CaptureRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.CaseRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.Outcome;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.RunRow;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisPaths;
import com.securityexpert.nexus.ui2.service.packetanalysis.PacketCaptureStore;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Packet Analysis API (PO decision record 2026-10-15 §5/§6). The route map gates every route
 * ({@code packet_analysis_read}, {@code packet_analysis_run}, {@code packet_analysis_raw_view}); this controller
 * then decides which projection a reader receives:
 *
 * <ul>
 *   <li>the replay viewer (aiview) always gets the masked projection -- pseudonymous case and capture names, no
 *       digest, no creator, and the engine's masked summary instead of the report -- whatever else it holds;</li>
 *   <li>run and raw-view roles get the full case view and the full report; only raw-view roles download.</li>
 * </ul>
 *
 * <p>Uploads stream into the analysis volume after the gates (the multipart resolver and the interceptor never
 * parse this body), one file per request. Every upload, analysis request, full-report view, download and deletion
 * is ledgered (and audited) before it takes effect or before its first byte leaves the service.</p>
 */
@RestController
public final class PacketAnalysisController {

    private static final String BASE = "/api/v2/packet-analysis";
    private static final int TITLE_MAX = 120;
    private static final int NAME_MAX = 200;

    public record CreateCase(String title, Boolean payload_zeroing) {
    }

    private final PacketAnalysisRepository repository;
    private final PacketCaptureStore store;
    private final RbacEvaluator rbac;
    private final TopologyNamePseudonymizer names;
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore uploads = new Semaphore(2);

    public PacketAnalysisController(PacketAnalysisRepository repository, PacketCaptureStore store, RbacEvaluator rbac,
            TopologyNamePseudonymizer names) {
        this.repository = repository;
        this.store = store;
        this.rbac = rbac;
        this.names = names;
    }

    // ---- cases ---------------------------------------------------------------------------------------------

    @GetMapping(BASE + "/cases")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        boolean masked = masked(request);
        List<Map<String, Object>> cases = new ArrayList<>();
        for (CaseRow row : repository.listCases(200)) cases.add(caseView(row, masked));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cases", cases);
        body.put("permissions", permissions(request));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("max_file_bytes", store.maxFileBytes());
        limits.put("max_store_bytes", store.maxStoreBytes());
        limits.put("used_bytes", store.usedBytes());
        limits.put("retention_days", 7);
        body.put("limits", limits);
        return ok(body);
    }

    @PostMapping(BASE + "/cases")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) CreateCase input,
            HttpServletRequest request) {
        if (!canRun(request)) return error(HttpStatus.FORBIDDEN, "RUN_ROLE_REQUIRED", "Creating a case needs an operator-level role");
        String title = input == null || input.title() == null ? "" : input.title().strip();
        if (title.isEmpty() || title.length() > TITLE_MAX || title.chars().anyMatch(Character::isISOControl))
            return error(HttpStatus.BAD_REQUEST, "TITLE_INVALID", "A title of 1 to 120 printable characters is required");
        boolean zeroing = input.payload_zeroing() != null && input.payload_zeroing();
        CaseRow row = repository.createCase(UUID.randomUUID().toString(), title, actor(request), zeroing);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(caseView(row, false));
    }

    @GetMapping(BASE + "/cases/{caseId}")
    public ResponseEntity<Map<String, Object>> read(@PathVariable String caseId, HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(caseId)) return invalidId();
        Optional<CaseRow> row = repository.findCase(caseId);
        if (row.isEmpty()) return caseNotFound();
        boolean masked = masked(request);
        Map<String, Object> body = caseView(row.get(), masked);
        List<Map<String, Object>> captures = new ArrayList<>();
        for (CaptureRow capture : repository.captures(caseId)) captures.add(captureView(capture, masked));
        body.put("captures", captures);
        List<Map<String, Object>> runs = new ArrayList<>();
        for (RunRow run : repository.runs(caseId)) runs.add(runView(run, masked));
        body.put("runs", runs);
        body.put("permissions", permissions(request));
        return ok(body);
    }

    @DeleteMapping(BASE + "/cases/{caseId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String caseId, HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(caseId)) return invalidId();
        if (!canRun(request)) return error(HttpStatus.FORBIDDEN, "RUN_ROLE_REQUIRED", "Deleting a case needs an operator-level role");
        String actor = actor(request);
        Outcome outcome;
        try {
            outcome = repository.deleteCase(caseId, actor, canRaw(request) ? null : actor, () -> {
                try {
                    store.deleteCase(caseId);
                } catch (IOException failed) {
                    throw new java.io.UncheckedIOException(failed);
                }
            });
        } catch (java.io.UncheckedIOException failed) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "STORE_DELETE_FAILED", "The case files could not be removed; nothing was deleted");
        }
        return switch (outcome) {
            case DONE -> ok(Map.of("deleted", true, "case_id", caseId));
            case FORBIDDEN -> error(HttpStatus.FORBIDDEN, "NOT_CASE_OWNER", "Only the case's creator or a security administrator may delete it");
            case BUSY -> error(HttpStatus.CONFLICT, "ANALYSIS_ACTIVE", "An analysis of this case is queued or running");
            default -> caseNotFound();
        };
    }

    // ---- captures ------------------------------------------------------------------------------------------

    @PostMapping(path = BASE + "/cases/{caseId}/captures", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@PathVariable String caseId, HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(caseId)) return invalidId();
        if (!canRun(request)) return error(HttpStatus.FORBIDDEN, "RUN_ROLE_REQUIRED", "Uploading needs an operator-level role");
        Optional<CaseRow> row = repository.findCase(caseId);
        if (row.isEmpty()) return caseNotFound();
        if (Set.of("QUEUED", "ANALYZING").contains(row.get().state()))
            return error(HttpStatus.CONFLICT, "ANALYSIS_ACTIVE", "Wait until the queued or running analysis finishes");
        if (!uploads.tryAcquire()) return error(HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_BUSY", "Two uploads are already running; retry when one finishes");
        PacketCaptureStore.Stored stored = null;
        try {
            var upload = new FileUpload();
            upload.setFileCountMax(1);
            upload.setHeaderEncoding("UTF-8");
            var parts = upload.getItemIterator(new UploadContext() {
                public String getCharacterEncoding() { return "UTF-8"; }
                public String getContentType() { return request.getContentType(); }
                public long contentLength() { return request.getContentLengthLong(); }
                public InputStream getInputStream() throws IOException { return request.getInputStream(); }
            });
            if (!parts.hasNext()) return error(HttpStatus.BAD_REQUEST, "CAPTURE_FILE_REQUIRED", "Send one file part named file");
            var file = parts.next();
            if (file.isFormField() || !"file".equals(file.getFieldName()) || file.getName() == null)
                return error(HttpStatus.BAD_REQUEST, "CAPTURE_FILE_REQUIRED", "Send one file part named file");
            String displayName = displayName(file.getName());
            if (displayName == null)
                return error(HttpStatus.BAD_REQUEST, "CAPTURE_NAME_INVALID", "The file name must have 1 to 200 printable characters");
            try (InputStream in = file.openStream()) {
                stored = store.write(caseId, in, request.getContentLengthLong());
            }
            if (parts.hasNext()) {
                store.deleteCapture(caseId, stored.storageKey());
                return error(HttpStatus.BAD_REQUEST, "MULTIPART_PARTS", "Exactly one file part is allowed");
            }
            Outcome outcome = repository.addCapture(new CaptureRow(stored.captureId(), caseId, displayName, stored.format(),
                    stored.sizeBytes(), stored.sha256(), stored.storageKey(), actor(request), Instant.now()), actor(request));
            if (outcome != Outcome.DONE) {
                store.deleteCapture(caseId, stored.storageKey());
                return outcome == Outcome.BUSY
                        ? error(HttpStatus.CONFLICT, "ANALYSIS_ACTIVE", "An analysis of this case started during the upload")
                        : caseNotFound();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("capture_id", stored.captureId());
            body.put("format", stored.format());
            body.put("size_bytes", stored.sizeBytes());
            body.put("sha256", stored.sha256());
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
        } catch (PacketCaptureStore.Refusal refusal) {
            return switch (refusal.code()) {
                case PacketCaptureStore.TOO_LARGE -> error(HttpStatus.PAYLOAD_TOO_LARGE, refusal.code(),
                        "A capture may be at most " + store.maxFileBytes() + " bytes");
                case PacketCaptureStore.STORE_FULL -> error(HttpStatus.INSUFFICIENT_STORAGE, refusal.code(),
                        "The analysis store would pass its " + store.maxStoreBytes() + "-byte cap; delete a case first");
                default -> error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, PacketCaptureStore.NOT_A_CAPTURE,
                        "Only pcap and pcapng captures are accepted (checked by the file's own header)");
            };
        } catch (IOException | RuntimeException invalid) {
            // Multipart and IO errors may carry file names or bytes: never echo or log them.
            if (stored != null) {
                try { store.deleteCapture(caseId, stored.storageKey()); } catch (IOException ignored) { }
            }
            return error(HttpStatus.BAD_REQUEST, "CAPTURE_UPLOAD_INVALID", "The upload did not complete; send one pcap or pcapng file part");
        } finally {
            uploads.release();
        }
    }

    @GetMapping(BASE + "/captures/{captureId}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String captureId, HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(captureId)) return streamError(HttpStatus.BAD_REQUEST, "INVALID_ID");
        if (!canRaw(request)) return streamError(HttpStatus.FORBIDDEN, "RAW_VIEW_ROLE_REQUIRED");
        Optional<CaptureRow> capture = repository.findCapture(captureId);
        if (capture.isEmpty()) return streamError(HttpStatus.NOT_FOUND, "CAPTURE_NOT_FOUND");
        CaptureRow row = capture.get();
        InputStream in;
        try {
            // Ledger first: no byte leaves the service before the download is on record.
            repository.recordAccess(row.caseId(), row.captureId(), null, "CAPTURE_DOWNLOADED", actor(request), row.sizeBytes());
            in = store.open(row.caseId(), row.storageKey());
        } catch (IOException missing) {
            return streamError(HttpStatus.GONE, "CAPTURE_FILE_MISSING");
        } catch (RuntimeException unrecorded) {
            return streamError(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIT_REFUSED");
        }
        StreamingResponseBody body = out -> {
            try (InputStream source = in) {
                source.transferTo(out);
            }
        };
        // The file name carries the opaque id only, never the uploaded name.
        return ResponseEntity.ok().header("Content-Type", "application/vnd.tcpdump.pcap")
                .header("Content-Disposition", "attachment; filename=\"capture-" + row.captureId().substring(0, 8) + "."
                        + row.format() + "\"")
                .header("Content-Length", Long.toString(row.sizeBytes())).header("Cache-Control", "no-store").body(body);
    }

    // ---- analysis ------------------------------------------------------------------------------------------

    @PostMapping(BASE + "/cases/{caseId}/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@PathVariable String caseId, HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(caseId)) return invalidId();
        if (!canRun(request)) return error(HttpStatus.FORBIDDEN, "RUN_ROLE_REQUIRED", "Requesting an analysis needs an operator-level role");
        String jobId = UUID.randomUUID().toString();
        Outcome outcome = repository.requestAnalysis(caseId, jobId, actor(request));
        return switch (outcome) {
            case DONE -> ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                    .body(Map.of("job_id", jobId, "case_id", caseId, "state", "QUEUED"));
            case BUSY -> error(HttpStatus.CONFLICT, "ANALYSIS_ACTIVE", "An analysis of this case is already queued or running");
            case NO_CAPTURES -> error(HttpStatus.CONFLICT, "NO_CAPTURES", "Upload at least one capture first");
            default -> caseNotFound();
        };
    }

    @GetMapping(BASE + "/runs/{jobId}")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String jobId, HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(jobId)) return invalidId();
        return repository.findRun(jobId).map(run -> ok(runView(run, masked(request))))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "No analysis run with that id (or its case expired)"));
    }

    /** The full report for run and raw-view roles (ledgered); the masked summary for everyone else. */
    @GetMapping(BASE + "/runs/{jobId}/report")
    public ResponseEntity<Map<String, Object>> report(@PathVariable String jobId, HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(jobId)) return invalidId();
        Optional<RunRow> found = repository.findRun(jobId);
        if (found.isEmpty()) return error(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "No analysis run with that id (or its case expired)");
        RunRow run = found.get();
        if (!"COMPLETED".equals(run.state()))
            return error(HttpStatus.CONFLICT, "REPORT_NOT_READY", "The analysis is " + run.state());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", run.jobId());
        body.put("case_id", run.caseId());
        if (masked(request) || !(canRun(request) || canRaw(request))) {
            body.put("projection", "masked");
            body.put("summary", parse(run.maskedSummaryJson()));
            return ok(body);
        }
        var file = store.reportFile(run.caseId(), run.jobId());
        Map<String, Object> report;
        try {
            repository.recordAccess(run.caseId(), null, run.jobId(), "REPORT_VIEWED", actor(request), Files.size(file));
            report = json.readValue(file.toFile(), new TypeReference<Map<String, Object>>() { });
        } catch (IOException missing) {
            return error(HttpStatus.GONE, "REPORT_MISSING", "The report file is not on the analysis volume");
        }
        body.put("projection", "full");
        body.put("report", report);
        return ok(body);
    }

    // ---- projections ---------------------------------------------------------------------------------------

    private Map<String, Object> caseView(CaseRow row, boolean masked) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("case_id", row.caseId());
        view.put("title", masked ? names.maskPolicyName("packet_case", row.title()) : row.title());
        if (!masked) view.put("created_by", row.createdBy());
        view.put("created_at", String.valueOf(row.createdAt()));
        view.put("expires_at", String.valueOf(row.expiresAt()));
        view.put("state", row.state());
        view.put("total_bytes", row.totalBytes());
        view.put("capture_count", row.captureCount());
        view.put("payload_zeroing", row.payloadZeroing());
        view.put("latest_job_id", row.latestJobId());
        return view;
    }

    private Map<String, Object> captureView(CaptureRow row, boolean masked) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("capture_id", row.captureId());
        view.put("display_name", masked ? names.maskPolicyName("packet_capture", row.displayName()) : row.displayName());
        view.put("format", row.format());
        view.put("size_bytes", row.sizeBytes());
        if (!masked) {
            view.put("sha256", row.sha256());
            view.put("uploaded_by", row.uploadedBy());
        }
        view.put("uploaded_at", String.valueOf(row.uploadedAt()));
        return view;
    }

    private Map<String, Object> runView(RunRow run, boolean masked) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("job_id", run.jobId());
        view.put("case_id", run.caseId());
        view.put("state", run.state());
        if (!masked) view.put("requested_by", run.requestedBy());
        view.put("requested_at", String.valueOf(run.requestedAt()));
        view.put("started_at", run.startedAt() == null ? null : run.startedAt().toString());
        view.put("finished_at", run.finishedAt() == null ? null : run.finishedAt().toString());
        view.put("engine_version", run.engineVersion());
        view.put("failure_reason", run.failureReason());
        view.put("summary", parse(run.maskedSummaryJson()));
        return view;
    }

    private Object parse(String maskedSummary) {
        if (maskedSummary == null) return null;
        try {
            return json.readValue(maskedSummary.getBytes(StandardCharsets.UTF_8), new TypeReference<Map<String, Object>>() { });
        } catch (IOException unreadable) {
            return Map.of("verdict", "unknown", "reason", "SUMMARY_UNREADABLE");
        }
    }

    /** The uploaded name, reduced to its last path segment and to printable characters, or {@code null}. */
    static String displayName(String raw) {
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty() || name.length() > NAME_MAX || name.chars().anyMatch(Character::isISOControl)) return null;
        return name;
    }

    // ---- authorization helpers -----------------------------------------------------------------------------

    private Map<String, Object> permissions(HttpServletRequest request) {
        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("run", canRun(request));
        permissions.put("raw_view", canRaw(request));
        permissions.put("masked", masked(request));
        return permissions;
    }

    private static boolean masked(HttpServletRequest request) {
        return PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
    }

    private boolean canRun(HttpServletRequest request) {
        return holds(request, ActionRegistry.PACKET_ANALYSIS_RUN_ROLES);
    }

    private boolean canRaw(HttpServletRequest request) {
        return holds(request, ActionRegistry.PACKET_ANALYSIS_RAW_ROLES);
    }

    /** The replay viewer never holds a run or raw authority here, even if it were also bound to such a role. */
    private boolean holds(HttpServletRequest request, Set<String> roles) {
        String actor = actor(request);
        return actor != null && !masked(request)
                && rbac.evaluateAny(actor, roles, Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }

    private static String actor(HttpServletRequest request) {
        return (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
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

    private static ResponseEntity<Map<String, Object>> caseNotFound() {
        return error(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND", "No case with that id (cases are deleted 7 days after creation)");
    }

    private static ResponseEntity<StreamingResponseBody> streamError(HttpStatus status, String code) {
        byte[] payload = ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status).header("Content-Type", "application/json").body(out -> out.write(payload));
    }
}
