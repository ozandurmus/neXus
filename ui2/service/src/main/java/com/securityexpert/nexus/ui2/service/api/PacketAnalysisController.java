package com.securityexpert.nexus.ui2.service.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.CaseQueries;
import com.securityexpert.nexus.ui2.packetanalysis.engine.CaseRunner;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.privacy.PayloadMasker;
import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReport;
import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportOptions;
import com.securityexpert.nexus.ui2.packetanalysis.export.ReportMasker;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.CaptureRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.CaseRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.Outcome;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.RunRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.TelemetryRow;
import com.securityexpert.nexus.ui2.packetanalysis.telemetry.Alignment;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisPaths;
import com.securityexpert.nexus.ui2.service.packetanalysis.PacketAnalysisCorrelationService;
import com.securityexpert.nexus.ui2.service.packetanalysis.PacketCaptureStore;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.ReportProjectionMasker;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
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
    private static final long COMPARE_MAX_BYTES = 64L * 1024L * 1024L;

    public record CreateCase(String title, Boolean payload_zeroing) {
    }

    /**
     * The clock alignment of one telemetry file. A missing or blank {@code time_zone} means "not confirmed" (UTC is
     * assumed and the report says so); {@code offset_seconds} is added to the file's time; {@code anchor_local}
     * ({@code yyyy-MM-dd HH:mm:ss}, in the file's zone) is the time of the first line for files that carry no date or time.
     */
    public record TelemetryAlignment(String time_zone, Integer offset_seconds, String anchor_local) {
    }

    private final PacketAnalysisRepository repository;
    private final PacketCaptureStore store;
    private final RbacEvaluator rbac;
    private final TopologyNamePseudonymizer names;
    private final SubnetPreservingIpMasker addresses;
    private final PacketAnalysisCorrelationService correlation;
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore uploads = new Semaphore(2);

    public PacketAnalysisController(PacketAnalysisRepository repository, PacketCaptureStore store, RbacEvaluator rbac,
            TopologyNamePseudonymizer names) {
        this(repository, store, rbac, names, PacketAnalysisCorrelationService.empty(repository, store), null);
    }

    public PacketAnalysisController(PacketAnalysisRepository repository, PacketCaptureStore store, RbacEvaluator rbac,
            TopologyNamePseudonymizer names, SubnetPreservingIpMasker addresses) {
        this(repository, store, rbac, names, PacketAnalysisCorrelationService.empty(repository, store), addresses);
    }

    public PacketAnalysisController(PacketAnalysisRepository repository, PacketCaptureStore store, RbacEvaluator rbac,
            TopologyNamePseudonymizer names, PacketAnalysisCorrelationService correlation) {
        this(repository, store, rbac, names, correlation, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PacketAnalysisController(PacketAnalysisRepository repository, PacketCaptureStore store, RbacEvaluator rbac,
            TopologyNamePseudonymizer names, PacketAnalysisCorrelationService correlation,
            SubnetPreservingIpMasker addresses) {
        this.repository = repository;
        this.store = store;
        this.rbac = rbac;
        this.names = names;
        this.addresses = addresses;
        this.correlation = correlation != null ? correlation : PacketAnalysisCorrelationService.empty(repository, store);
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
        limits.put("max_telemetry_bytes", store.maxTelemetryBytes());
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
        List<Map<String, Object>> telemetry = new ArrayList<>();
        for (TelemetryRow item : repository.telemetry(caseId)) telemetry.add(telemetryView(item, masked));
        body.put("telemetry", telemetry);
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

    // ---- host telemetry (server-side text files next to the captures) --------------------------------------

    /**
     * Attach one host telemetry text file (sar, vmstat, pidstat, tc/softnet snapshots, syslog) to a case. Same gates as a
     * capture upload, streamed to the analysis volume with the same cap and retention; the content is sniffed (never the
     * name) and the result says what it was recognised as. The upload carries no request parameters (the body is never
     * parsed by the container); the clock alignment is set afterwards with {@code PUT /telemetry/{id}}, and until then
     * the report assumes UTC and says so.
     */
    @PostMapping(path = BASE + "/cases/{caseId}/telemetry", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadTelemetry(@PathVariable String caseId, HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(caseId)) return invalidId();
        if (!canRun(request)) return error(HttpStatus.FORBIDDEN, "RUN_ROLE_REQUIRED", "Uploading needs an operator-level role");
        if (repository.findCase(caseId).isEmpty()) return caseNotFound();
        if (!uploads.tryAcquire()) return error(HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_BUSY", "Two uploads are already running; retry when one finishes");
        PacketCaptureStore.StoredTelemetry stored = null;
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
            if (!parts.hasNext()) return error(HttpStatus.BAD_REQUEST, "TELEMETRY_FILE_REQUIRED", "Send one file part named file");
            var file = parts.next();
            if (file.isFormField() || !"file".equals(file.getFieldName()) || file.getName() == null)
                return error(HttpStatus.BAD_REQUEST, "TELEMETRY_FILE_REQUIRED", "Send one file part named file");
            String displayName = displayName(file.getName());
            if (displayName == null)
                return error(HttpStatus.BAD_REQUEST, "TELEMETRY_NAME_INVALID", "The file name must have 1 to 200 printable characters");
            try (InputStream in = file.openStream()) {
                stored = store.writeTelemetry(caseId, in, request.getContentLengthLong());
            }
            if (parts.hasNext()) {
                store.deleteTelemetry(caseId, stored.storageKey());
                return error(HttpStatus.BAD_REQUEST, "MULTIPART_PARTS", "Exactly one file part is allowed");
            }
            TelemetryRow row = new TelemetryRow(stored.telemetryId(), caseId, displayName, stored.sizeBytes(), stored.sha256(),
                    stored.storageKey(), stored.detectedFormat(), stored.recognitionNote(), null, 0, null, actor(request),
                    Instant.now(), null, null);
            Outcome outcome = repository.addTelemetry(row, actor(request));
            if (outcome != Outcome.DONE) {
                store.deleteTelemetry(caseId, stored.storageKey());
                return caseNotFound();
            }
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(telemetryView(row, false));
        } catch (PacketCaptureStore.Refusal refusal) {
            return switch (refusal.code()) {
                case PacketCaptureStore.TOO_LARGE -> error(HttpStatus.PAYLOAD_TOO_LARGE, refusal.code(),
                        "A telemetry file may be at most " + store.maxTelemetryBytes() + " bytes");
                case PacketCaptureStore.STORE_FULL -> error(HttpStatus.INSUFFICIENT_STORAGE, refusal.code(),
                        "The analysis store would pass its " + store.maxStoreBytes() + "-byte cap; delete a case first");
                default -> error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, PacketCaptureStore.NOT_A_TEXT_FILE,
                        "Only text files are accepted as host telemetry (a binary file was sent)");
            };
        } catch (IOException | RuntimeException invalid) {
            // Multipart and IO errors may carry file names or bytes: never echo or log them.
            if (stored != null) {
                try { store.deleteTelemetry(caseId, stored.storageKey()); } catch (IOException ignored) { }
            }
            return error(HttpStatus.BAD_REQUEST, "TELEMETRY_UPLOAD_INVALID", "The upload did not complete; send one text file part");
        } finally {
            uploads.release();
        }
    }

    /** Sets the clock alignment of one attached file (the time zone the user confirms, a clock offset, a start time). */
    @PutMapping(BASE + "/telemetry/{telemetryId}")
    public ResponseEntity<Map<String, Object>> alignTelemetry(@PathVariable String telemetryId,
            @RequestBody(required = false) TelemetryAlignment input, HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(telemetryId)) return invalidId();
        if (!canRun(request)) return error(HttpStatus.FORBIDDEN, "RUN_ROLE_REQUIRED", "Changing the alignment needs an operator-level role");
        if (input == null) return error(HttpStatus.BAD_REQUEST, "ALIGNMENT_INVALID", "Send time_zone, offset_seconds and anchor_local");
        Alignment alignment;
        try {
            alignment = Alignment.of(input.time_zone(), input.offset_seconds() == null ? 0 : input.offset_seconds(), input.anchor_local());
        } catch (IllegalArgumentException invalid) {
            return error(HttpStatus.BAD_REQUEST, "ALIGNMENT_INVALID", invalid.getMessage());
        }
        Outcome outcome = repository.setTelemetryAlignment(telemetryId, alignment.zoneConfirmed() ? alignment.zone().getId() : null,
                alignment.offsetSeconds(), alignment.anchorLocal(), actor(request));
        if (outcome != Outcome.DONE) return telemetryNotFound();
        return repository.findTelemetry(telemetryId).map(row -> ok(telemetryView(row, false))).orElseGet(PacketAnalysisController::telemetryNotFound);
    }

    @DeleteMapping(BASE + "/telemetry/{telemetryId}")
    public ResponseEntity<Map<String, Object>> deleteTelemetry(@PathVariable String telemetryId, HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(telemetryId)) return invalidId();
        if (!canRun(request)) return error(HttpStatus.FORBIDDEN, "RUN_ROLE_REQUIRED", "Deleting a telemetry file needs an operator-level role");
        Optional<TelemetryRow> found = repository.findTelemetry(telemetryId);
        if (found.isEmpty()) return telemetryNotFound();
        TelemetryRow row = found.get();
        Outcome outcome;
        try {
            outcome = repository.deleteTelemetry(telemetryId, actor(request), () -> {
                try {
                    store.deleteTelemetry(row.caseId(), row.storageKey());
                } catch (IOException failed) {
                    throw new java.io.UncheckedIOException(failed);
                }
            });
        } catch (java.io.UncheckedIOException failed) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "STORE_DELETE_FAILED", "The file could not be removed; nothing was deleted");
        }
        return outcome == Outcome.DONE ? ok(Map.of("deleted", true, "telemetry_id", telemetryId)) : telemetryNotFound();
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
        Path caseDb = PacketAnalysisPaths.projectDuckDb(store.root(), run.caseId());
        if (Files.isRegularFile(caseDb)) {
            try {
                correlation.correlateAndStore(run.caseId());
            } catch (RuntimeException ignored) {
            }
        }
        Map<String, Object> report;
        try {
            repository.recordAccess(run.caseId(), null, run.jobId(), "REPORT_VIEWED", actor(request), Files.size(file));
            report = json.readValue(file.toFile(), new TypeReference<Map<String, Object>>() { });
        } catch (IOException missing) {
            return error(HttpStatus.GONE, "REPORT_MISSING", "The report file is not on the analysis volume");
        }
        body.put("projection", "full");
        // Run roles without the raw-view action read the full report shape with payload-derived text tokenized.
        body.put("payload_masked", !canRaw(request));
        body.put("report", canRaw(request) ? report : maskPayload(report, request));
        return ok(body);
    }

    // ---- read-only case DuckDB detail views & topology -----------------------------------------------------

    @FunctionalInterface
    private interface CaseDbQuery {
        Map<String, Object> query(Db db, CaseRow row);
    }

    @GetMapping(BASE + "/cases/{caseId}/state")
    public ResponseEntity<Map<String, Object>> caseState(@PathVariable String caseId, HttpServletRequest request) {
        if (PacketAnalysisPaths.isId(caseId)) {
            Optional<CaseRow> found = repository.findCase(caseId);
            if (found.isPresent() && !isCaseAnalyzing(found.get())
                    && Files.isRegularFile(PacketAnalysisPaths.projectDuckDb(store.root(), caseId))) {
                try {
                    correlation.correlateAndStore(caseId);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return withReadOnlyCaseDb(caseId, request, (db, row) -> {
            Map<String, Object> state = new LinkedHashMap<>(CaseQueries.state(db,
                    masked(request) ? names.maskPolicyName("packet_case", row.title()) : row.title()));
            Object storedCorr = CaseQueries.settingGet(db, "nexus_correlation");
            @SuppressWarnings("unchecked")
            Map<String, Object> corr = storedCorr instanceof Map<?, ?> m
                    ? CaseRunner.toStandardMap((Map<String, Object>) m)
                    : correlation.computeCorrelation(caseId, db);
            state.put("nexus_correlation", corr);
            if (state.get("report") instanceof Map<?, ?> rep) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mutableReport = new LinkedHashMap<>((Map<String, Object>) rep);
                mutableReport.put("nexus_correlation", corr);
                state.put("report", mutableReport);
            }
            state.put("case_id", caseId);
            state.put("permissions", permissions(request));
            return state;
        });
    }

    @GetMapping(BASE + "/cases/{caseId}/flows")
    public ResponseEntity<Map<String, Object>> flows(
            @PathVariable String caseId,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "50") long limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "filter_by", defaultValue = "") String filterBy,
            @RequestParam(defaultValue = "") String proto,
            @RequestParam(defaultValue = "bytes") String sort,
            @RequestParam(required = false) Double start,
            @RequestParam(required = false) Double end,
            @RequestParam(required = false) List<String> point,
            HttpServletRequest request) {
        String effectiveSearch = !search.isBlank() ? search : q;
        String effectiveFilter = !filterBy.isBlank() ? filterBy : proto;
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.flowPage(
                db, Math.max(0L, offset), clampLimit(limit, 1L, 200L),
                effectiveSearch, effectiveFilter, sort, start, end, point));
    }

    @GetMapping(BASE + "/cases/{caseId}/frames")
    public ResponseEntity<Map<String, Object>> frames(
            @PathVariable String caseId,
            @RequestParam(required = false) String flow,
            @RequestParam(name = "flow_id", required = false) String flowId,
            @RequestParam(required = false) String point,
            @RequestParam(required = false) List<Long> frames,
            @RequestParam(defaultValue = "50") long limit,
            HttpServletRequest request) {
        String effectiveFlow = (flow != null && !flow.isBlank()) ? flow : flowId;
        boolean rawAllowed = canRaw(request) && !masked(request);
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.framesPage(
                db, effectiveFlow, point, frames, clampLimit(limit, 1L, 200L), rawAllowed));
    }

    @GetMapping(BASE + "/cases/{caseId}/diagnosis")
    public ResponseEntity<Map<String, Object>> diagnosis(
            @PathVariable String caseId,
            @RequestParam(required = false) String flow,
            @RequestParam(name = "flow_id", required = false) String flowId,
            HttpServletRequest request) {
        String effectiveFlow = (flow != null && !flow.isBlank()) ? flow : flowId;
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.diagnosis(db, effectiveFlow));
    }

    @GetMapping(BASE + "/cases/{caseId}/charts")
    public ResponseEntity<Map<String, Object>> charts(
            @PathVariable String caseId,
            @RequestParam(required = false) String flow,
            @RequestParam(name = "flow_id", required = false) String flowId,
            @RequestParam(required = false) String point,
            @RequestParam(defaultValue = "forward") String direction,
            @RequestParam(required = false) Double start,
            @RequestParam(required = false) Double end,
            @RequestParam(defaultValue = "2000") long limit,
            @RequestParam(defaultValue = "false") boolean local,
            HttpServletRequest request) {
        String effectiveFlow = (flow != null && !flow.isBlank()) ? flow : flowId;
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.charts(
                db, effectiveFlow, point, direction, start, end, clampLimit(limit, 1L, 5000L), local));
    }

    @GetMapping(BASE + "/cases/{caseId}/ladder")
    public ResponseEntity<Map<String, Object>> ladder(
            @PathVariable String caseId,
            @RequestParam(required = false) String flow,
            @RequestParam(name = "flow_id", required = false) String flowId,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "100") long limit,
            @RequestParam(required = false) Double start,
            @RequestParam(required = false) Double end,
            HttpServletRequest request) {
        String effectiveFlow = (flow != null && !flow.isBlank()) ? flow : flowId;
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.ladder(
                db, effectiveFlow, Math.max(0L, offset), clampLimit(limit, 1L, 200L), start, end));
    }

    @GetMapping(BASE + "/cases/{caseId}/waterfall")
    public ResponseEntity<Map<String, Object>> waterfall(
            @PathVariable String caseId,
            @RequestParam(required = false) String flow,
            @RequestParam(name = "flow_id", required = false) String flowId,
            @RequestParam(name = "request_index", defaultValue = "0") int requestIndex,
            @RequestParam(required = false) Double start,
            @RequestParam(required = false) Double end,
            HttpServletRequest request) {
        String effectiveFlow = (flow != null && !flow.isBlank()) ? flow : flowId;
        int safeReqIdx = Math.max(0, Math.min(99, requestIndex));
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.waterfall(
                db, effectiveFlow, start, end, safeReqIdx));
    }

    @GetMapping(BASE + "/cases/{caseId}/timeseries")
    public ResponseEntity<Map<String, Object>> timeseries(@PathVariable String caseId, HttpServletRequest request) {
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.timeseriesPage(db));
    }

    @GetMapping(BASE + "/cases/{caseId}/topology/overlay")
    public ResponseEntity<Map<String, Object>> topologyOverlay(
            @PathVariable String caseId,
            @RequestParam(required = false) Double start,
            @RequestParam(required = false) Double end,
            HttpServletRequest request) {
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.topologyOverlay(db, start, end));
    }

    @GetMapping(BASE + "/cases/{caseId}/topology/inspect")
    public ResponseEntity<Map<String, Object>> topologyInspect(
            @PathVariable String caseId,
            @RequestParam(required = false) String point,
            @RequestParam(required = false) String segment,
            @RequestParam(required = false) Double start,
            @RequestParam(required = false) Double end,
            HttpServletRequest request) {
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.topologyInspect(db, point, segment, start, end));
    }

    @GetMapping(BASE + "/cases/{caseId}/findings")
    public ResponseEntity<Map<String, Object>> findings(
            @PathVariable String caseId,
            @RequestParam(required = false) Double start,
            @RequestParam(required = false) Double end,
            HttpServletRequest request) {
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.findingsPage(db, start, end));
    }

    @GetMapping(BASE + "/cases/{caseId}/events")
    public ResponseEntity<Map<String, Object>> events(
            @PathVariable String caseId,
            @RequestParam(name = "a", required = false) String pointA,
            @RequestParam(name = "b", required = false) String pointB,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "50") long limit,
            @RequestParam(required = false) Double start,
            @RequestParam(required = false) Double end,
            HttpServletRequest request) {
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.eventPage(
                db, pointA, pointB, direction, Math.max(0L, offset), clampLimit(limit, 1L, 200L), start, end));
    }

    @GetMapping(BASE + "/cases/{caseId}/dns")
    public ResponseEntity<Map<String, Object>> dns(
            @PathVariable String caseId,
            @RequestParam(required = false) String point,
            @RequestParam(required = false) String resolver,
            @RequestParam(required = false) Double start,
            @RequestParam(required = false) Double end,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "100") long limit,
            HttpServletRequest request) {
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.dnsPage(
                db, point, resolver, start, end, Math.max(0L, offset), clampLimit(limit, 1L, 200L)));
    }

    @GetMapping(BASE + "/cases/{caseId}/field-diff")
    public ResponseEntity<Map<String, Object>> fieldDiff(
            @PathVariable String caseId,
            @RequestParam(name = "packet_key", required = false) String packetKey,
            @RequestParam(defaultValue = "200") int limit,
            HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(caseId)) return invalidId();
        if (!canRaw(request)) return error(HttpStatus.FORBIDDEN, "RAW_VIEW_ROLE_REQUIRED",
                "Inspecting raw packet field differences requires a raw-view role");
        return withReadOnlyCaseDb(caseId, request, true, (db, row) -> {
            // The ledger CHECK constraint (V154) allows RAW_VIEWED for every raw packet-content view.
            repository.recordAccess(caseId, null, row.latestJobId(), "RAW_VIEWED", actor(request), 0L);
            if (packetKey != null && !packetKey.isBlank()) {
                return CaseQueries.packetDiff(db, packetKey);
            }
            return CaseQueries.fieldDiffs(db, CaseQueries.loadTopology(db), null, Math.max(1, Math.min(500, limit)));
        });
    }

    @GetMapping(BASE + "/cases/{caseId}/export/findings")
    public ResponseEntity<Map<String, Object>> exportFindings(
            @PathVariable String caseId,
            @RequestParam(name = "findings_only", defaultValue = "false") boolean findingsOnly,
            HttpServletRequest request) {
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.exportData(db, findingsOnly));
    }

    /** Representative frames of one TCP lifecycle bucket (reference {@code /api/sessions/evidence}). */
    @GetMapping(BASE + "/cases/{caseId}/sessions/evidence")
    public ResponseEntity<Map<String, Object>> sessionEvidence(
            @PathVariable String caseId,
            @RequestParam String point,
            @RequestParam double start,
            @RequestParam double end,
            HttpServletRequest request) {
        if (!Double.isFinite(start) || !Double.isFinite(end))
            return error(HttpStatus.BAD_REQUEST, "QUERY_INVALID", "start and end must be finite numbers");
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.sessionEvidence(db, point, start, end));
    }

    /** Reference template of the saved report (reference {@code /api/reference/template}). */
    @GetMapping(BASE + "/cases/{caseId}/reference/template")
    public ResponseEntity<Map<String, Object>> referenceTemplate(@PathVariable String caseId, HttpServletRequest request) {
        return withReadOnlyCaseDb(caseId, request, (db, row) -> CaseQueries.referenceTemplate(db));
    }

    /**
     * Compares the saved report with a reference template and/or a previous analysis export (reference
     * {@code /api/compare}). Read-only: nothing is stored.
     */
    @PostMapping(BASE + "/cases/{caseId}/compare")
    public ResponseEntity<Map<String, Object>> compare(
            @PathVariable String caseId,
            @RequestBody(required = false) Map<String, Object> payload,
            HttpServletRequest request) {
        if (request.getContentLengthLong() > COMPARE_MAX_BYTES)
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "COMPARE_INPUT_TOO_LARGE", "Comparison input exceeds 64 MiB");
        if (payload == null || !Set.of("reference", "against").containsAll(payload.keySet()))
            return error(HttpStatus.BAD_REQUEST, "QUERY_INVALID", "Choose reference and/or against");
        return withReadOnlyCaseDb(caseId, request,
                (db, row) -> CaseQueries.compare(db, payload.get("reference"), payload.get("against")));
    }

    /**
     * Report export as an offline HTML page or the FindingsExport JSON (reference {@code /api/export}), or the
     * engine tables as a CSV ZIP or an XLSX workbook (reference {@code /api/engine/export}). These files carry raw
     * addresses and payload-derived values that the masked projection cannot rewrite, so only raw-view roles
     * download them, and the download is ledgered before the first byte leaves the service.
     */
    @GetMapping(BASE + "/cases/{caseId}/export/download")
    public ResponseEntity<byte[]> exportDownload(
            @PathVariable String caseId,
            @RequestParam(defaultValue = "html") String format,
            HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(caseId)) return bytesError(HttpStatus.BAD_REQUEST, "INVALID_ID");
        if (!Set.of("html", "json", "csv", "xlsx").contains(format)) return bytesError(HttpStatus.BAD_REQUEST, "FORMAT_INVALID");
        if (!canRaw(request)) return bytesError(HttpStatus.FORBIDDEN, "RAW_VIEW_ROLE_REQUIRED");
        Optional<CaseRow> found = repository.findCase(caseId);
        if (found.isEmpty()) return bytesError(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND");
        CaseRow row = found.get();
        if (isCaseAnalyzing(row)) return bytesError(HttpStatus.CONFLICT, "ANALYSIS_RUNNING");
        Path dbPath = PacketAnalysisPaths.projectDuckDb(store.root(), caseId);
        if (!Files.isRegularFile(dbPath)) return bytesError(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY");
        byte[] content;
        try (var conn = Engine.openReadOnly(dbPath);
             var db = new Db(conn)) {
            content = switch (format) {
                case "csv", "xlsx" -> CaseQueries.exportTables(db, format);
                default -> CaseQueries.exportReport(db, format).getBytes(StandardCharsets.UTF_8);
            };
        } catch (IllegalArgumentException notReady) {
            return bytesError(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY");
        } catch (Exception failed) {
            return bytesError(HttpStatus.INTERNAL_SERVER_ERROR, "CASE_DB_READ_FAILED");
        }
        try {
            repository.recordAccess(caseId, null, row.latestJobId(), "RAW_VIEWED", actor(request), (long) content.length);
        } catch (RuntimeException unrecorded) {
            return bytesError(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIT_REFUSED");
        }
        String contentType = switch (format) {
            case "html" -> "text/html; charset=utf-8";
            case "json" -> "application/json";
            case "csv" -> "application/zip";
            default -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        };
        String extension = "csv".equals(format) ? "zip" : format;
        // The file name carries the opaque id only, never the case title.
        return ResponseEntity.ok().header("Content-Type", contentType)
                .header("Content-Disposition", "attachment; filename=\"packet-analysis-" + caseId.substring(0, 8)
                        + ("csv".equals(format) || "xlsx".equals(format) ? "-tables." : "-report.") + extension + "\"")
                .header("Cache-Control", "no-store").body(content);
    }

    /**
     * The expert report workbook (Özet, Zaman Çizelgesi, Hop Analizi, Örnek Olaylar, Yöntem ve Veri), generated
     * deterministically from the saved analysis. Read-grade like the findings export, with the privacy projection decided
     * here: raw-view roles get the engine values, every other reader (the replay viewer included) gets the masked
     * projection -- pseudonymous names, flows by ordinal, no digests. {@code start}/{@code end} (epoch seconds) select
     * the event window explicitly; without them the engine's first onset splits before and after. The download is
     * ledgered before the first byte leaves the service.
     */
    @GetMapping(BASE + "/cases/{caseId}/export/expert-report")
    public ResponseEntity<byte[]> exportExpertReport(
            @PathVariable String caseId,
            @RequestParam(name = "start", required = false) Double start,
            @RequestParam(name = "end", required = false) Double end,
            @RequestParam(name = "top", defaultValue = "25") int top,
            HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(caseId)) return bytesError(HttpStatus.BAD_REQUEST, "INVALID_ID");
        boolean raw = canRaw(request) && !masked(request);
        ExpertReportOptions options;
        try {
            options = new ExpertReportOptions(start, end, top, raw ? ReportMasker.NONE : projectionMasker(), expertSources(caseId),
                    expertTelemetry(caseId));
        } catch (IllegalArgumentException invalid) {
            return bytesError(HttpStatus.BAD_REQUEST, "QUERY_INVALID");
        }
        Optional<CaseRow> found = repository.findCase(caseId);
        if (found.isEmpty()) return bytesError(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND");
        CaseRow row = found.get();
        // The heading names the case; a masked reader gets the same pseudonym the case view shows, never the raw title.
        options = options.withCaseTitle(raw ? row.title() : names.maskPolicyName("packet_case", row.title()));
        if (isCaseAnalyzing(row)) return bytesError(HttpStatus.CONFLICT, "ANALYSIS_RUNNING");
        Path dbPath = PacketAnalysisPaths.projectDuckDb(store.root(), caseId);
        if (!Files.isRegularFile(dbPath)) return bytesError(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY");
        byte[] workbook;
        try (var conn = Engine.openReadOnly(dbPath);
             var db = new Db(conn)) {
            workbook = ExpertReport.generate(db, options);
        } catch (IllegalArgumentException refused) {
            boolean notReady = refused.getMessage() != null && refused.getMessage().startsWith("Run analysis");
            return bytesError(notReady ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST,
                    notReady ? "ANALYSIS_NOT_READY" : "WINDOW_OUT_OF_RANGE");
        } catch (Exception failed) {
            return bytesError(HttpStatus.INTERNAL_SERVER_ERROR, "CASE_DB_READ_FAILED");
        }
        try {
            repository.recordAccess(caseId, null, row.latestJobId(), raw ? "RAW_VIEWED" : "REPORT_VIEWED", actor(request),
                    (long) workbook.length);
        } catch (RuntimeException unrecorded) {
            return bytesError(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIT_REFUSED");
        }
        // The file name carries the opaque id only, never the case title.
        return ResponseEntity.ok().header("Content-Type", ExpertReport.CONTENT_TYPE)
                .header("Content-Disposition", "attachment; filename=\"packet-analysis-" + caseId.substring(0, 8)
                        + "-expert-report.xlsx\"")
                .header(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER, raw ? "false" : "true")
                .header("Cache-Control", "no-store").body(workbook);
    }

    private ReportMasker projectionMasker() {
        return new ReportProjectionMasker(names, addresses);
    }

    private List<ExpertReportOptions.HostTelemetry> expertTelemetry(String caseId) {
        List<ExpertReportOptions.HostTelemetry> files = new ArrayList<>();
        for (TelemetryRow item : repository.telemetry(caseId)) {
            files.add(new ExpertReportOptions.HostTelemetry(item.telemetryId(), item.displayName(), item.sizeBytes(), item.sha256(),
                    item.timeZone(), item.offsetSeconds(), item.anchorLocal(), () -> store.openTelemetry(caseId, item.storageKey())));
        }
        return files;
    }

    private List<ExpertReportOptions.SourceCapture> expertSources(String caseId) {
        List<ExpertReportOptions.SourceCapture> sources = new ArrayList<>();
        for (CaptureRow capture : repository.captures(caseId)) {
            sources.add(new ExpertReportOptions.SourceCapture(capture.displayName(), capture.sizeBytes(), capture.sha256()));
        }
        return sources;
    }

    @GetMapping(BASE + "/cases/{caseId}/correlation")
    public ResponseEntity<Map<String, Object>> correlation(
            @PathVariable String caseId,
            HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(caseId)) return invalidId();
        Optional<CaseRow> found = repository.findCase(caseId);
        if (found.isEmpty()) return caseNotFound();
        CaseRow row = found.get();
        if (isCaseAnalyzing(row)) {
            return error(HttpStatus.CONFLICT, "ANALYSIS_RUNNING",
                    "Case is currently being analysed; detail views are unavailable while a writer is active");
        }
        Path dbPath = PacketAnalysisPaths.projectDuckDb(store.root(), caseId);
        if (!Files.isRegularFile(dbPath)) {
            return error(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY",
                    "No completed analysis database is available for this case");
        }
        try {
            Map<String, Object> result = correlation.correlateAndStore(caseId);
            Map<String, Object> normalized = CaseRunner.toStandardMap(result);
            if (masked(request) || !canRaw(request)) {
                stripRawPayloadFields(normalized);
                return ok(maskPayload(normalized, request));
            }
            return ok(normalized);
        } catch (IllegalArgumentException badQuery) {
            return error(HttpStatus.BAD_REQUEST, "QUERY_INVALID", badQuery.getMessage());
        } catch (Exception failed) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "CASE_DB_READ_FAILED",
                    "Could not read the case analysis database");
        }
    }

    @PutMapping(BASE + "/cases/{caseId}/topology")
    public ResponseEntity<Map<String, Object>> saveTopology(
            @PathVariable String caseId,
            @RequestBody(required = false) Map<String, Object> rawTopology,
            HttpServletRequest request) {
        if (!PacketAnalysisPaths.isId(caseId)) return invalidId();
        if (!canRun(request)) return error(HttpStatus.FORBIDDEN, "RUN_ROLE_REQUIRED", "Updating topology needs an operator-level role");
        Optional<CaseRow> found = repository.findCase(caseId);
        if (found.isEmpty()) return caseNotFound();
        if (isCaseAnalyzing(found.get())) {
            return error(HttpStatus.CONFLICT, "ANALYSIS_RUNNING",
                    "Case is currently being analysed; topology cannot be modified while a writer is active");
        }
        if (rawTopology == null) {
            return error(HttpStatus.BAD_REQUEST, "TOPOLOGY_INVALID", "A topology JSON object is required");
        }
        Path dbPath = PacketAnalysisPaths.projectDuckDb(store.root(), caseId);
        if (!Files.isRegularFile(dbPath)) {
            return error(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY", "No completed analysis database is available for this case");
        }
        try {
            Map<String, Object> updated = new LinkedHashMap<>(CaseRunner.saveTopologyAndAnalyze(
                    PacketAnalysisPaths.caseDir(store.root(), caseId), rawTopology));
            try {
                Map<String, Object> corr = correlation.correlateAndStore(caseId);
                updated.put("nexus_correlation", corr);
                if (updated.get("report") instanceof Map<?, ?> rep) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mutableReport = new LinkedHashMap<>((Map<String, Object>) rep);
                    mutableReport.put("nexus_correlation", corr);
                    updated.put("report", mutableReport);
                }
            } catch (RuntimeException ignored) {
            }
            return ok(canRaw(request) ? updated : maskPayload(updated, request));
        } catch (IllegalArgumentException invalid) {
            return error(HttpStatus.BAD_REQUEST, "TOPOLOGY_INVALID", invalid.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> withReadOnlyCaseDb(
            String caseId, HttpServletRequest request, CaseDbQuery query) {
        return withReadOnlyCaseDb(caseId, request, false, query);
    }

    private ResponseEntity<Map<String, Object>> withReadOnlyCaseDb(
            String caseId, HttpServletRequest request, boolean keepRawPrefix, CaseDbQuery query) {
        if (!PacketAnalysisPaths.isId(caseId)) return invalidId();
        Optional<CaseRow> found = repository.findCase(caseId);
        if (found.isEmpty()) return caseNotFound();
        CaseRow row = found.get();
        if (isCaseAnalyzing(row)) {
            return error(HttpStatus.CONFLICT, "ANALYSIS_RUNNING",
                    "Case is currently being analysed; detail views are unavailable while a writer is active");
        }
        Path dbPath = PacketAnalysisPaths.projectDuckDb(store.root(), caseId);
        if (!Files.isRegularFile(dbPath)) {
            return error(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY",
                    "No completed analysis database is available for this case");
        }
        try (var conn = Engine.openReadOnly(dbPath);
             var db = new Db(conn)) {
            Map<String, Object> rawResult = query.query(db, row);
            Map<String, Object> normalized = CaseRunner.toStandardMap(rawResult);
            if (!keepRawPrefix || masked(request) || !canRaw(request)) {
                stripRawPayloadFields(normalized);
            }
            return ok(masked(request) || !canRaw(request) ? maskPayload(normalized, request) : normalized);
        } catch (IllegalArgumentException badQuery) {
            return error(HttpStatus.BAD_REQUEST, "QUERY_INVALID", badQuery.getMessage());
        } catch (Exception failed) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "CASE_DB_READ_FAILED",
                    "Could not read the case analysis database");
        }
    }

    private boolean isCaseAnalyzing(CaseRow row) {
        if (Set.of("QUEUED", "ANALYZING", "RUNNING", "REQUESTED").contains(row.state())) {
            return true;
        }
        for (RunRow run : repository.runs(row.caseId())) {
            if (Set.of("QUEUED", "ANALYZING", "RUNNING", "REQUESTED").contains(run.state())) {
                return true;
            }
        }
        return false;
    }

    private static long clampLimit(long limit, long min, long max) {
        return Math.max(min, Math.min(max, limit));
    }

    @SuppressWarnings("unchecked")
    private static void stripRawPayloadFields(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> mutable = (Map<String, Object>) map;
            if (mutable.containsKey("prefix")) {
                mutable.put("prefix", null);
            }
            if (mutable.containsKey("payload_hex")) {
                mutable.put("payload_hex", null);
            }
            for (Object value : mutable.values()) {
                stripRawPayloadFields(value);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                stripRawPayloadFields(item);
            }
        }
    }

    /**
     * The payload-masked projection for every session without the raw-view action: HTTP request lines, hosts, TLS
     * SNI and certificate names, SIP identities, DNS questions and vendor trailer text become deterministic keyed
     * tokens ({@link PayloadMasker}; field inventory in {@code ui2/packet-analysis/README.md}). The replay viewer also
     * gets capture-derived names masked here; its addresses and device names are rewritten afterwards by
     * {@link PrivacyMaskingResponseBodyAdvice}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> maskPayload(Map<String, Object> body, HttpServletRequest request) {
        return (Map<String, Object>) PayloadMasker.mask(body, payloadTokens(), masked(request));
    }

    private PayloadMasker.Tokens payloadTokens() {
        return new PayloadMasker.Tokens() {
            @Override public String token(String kind, String raw) { return names.maskPayloadToken(kind, raw); }
            @Override public String captureName(String raw) { return names.maskDeviceName(raw, null); }
            @Override public String interfaceName(String raw) { return names.maskPolicyName("interface", raw); }
        };
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

    private Map<String, Object> telemetryView(TelemetryRow row, boolean masked) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("telemetry_id", row.telemetryId());
        view.put("display_name", masked ? names.maskPolicyName("packet_telemetry", row.displayName()) : row.displayName());
        view.put("size_bytes", row.sizeBytes());
        view.put("detected_format", row.detectedFormat());
        view.put("recognized", !"unknown".equals(row.detectedFormat()));
        view.put("recognition_note", row.recognitionNote());
        view.put("time_zone", row.timeZone());
        view.put("zone_confirmed", row.timeZone() != null);
        view.put("offset_seconds", row.offsetSeconds());
        view.put("anchor_local", row.anchorLocal());
        if (!masked) {
            view.put("sha256", row.sha256());
            view.put("uploaded_by", row.uploadedBy());
        }
        view.put("uploaded_at", String.valueOf(row.uploadedAt()));
        view.put("aligned_at", row.alignedAt() == null ? null : row.alignedAt().toString());
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

    private static ResponseEntity<Map<String, Object>> telemetryNotFound() {
        return error(HttpStatus.NOT_FOUND, "TELEMETRY_NOT_FOUND", "No telemetry file with that id (cases are deleted 7 days after creation)");
    }

    private static ResponseEntity<StreamingResponseBody> streamError(HttpStatus status, String code) {
        byte[] payload = ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status).header("Content-Type", "application/json").body(out -> out.write(payload));
    }

    private static ResponseEntity<byte[]> bytesError(HttpStatus status, String code) {
        byte[] payload = ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status).header("Content-Type", "application/json")
                .header("Cache-Control", "no-store").body(payload);
    }
}
