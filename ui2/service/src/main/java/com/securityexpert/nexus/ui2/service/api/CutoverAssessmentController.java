package com.securityexpert.nexus.ui2.service.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.policy.CutoverRepository;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.CutoverPathTargets;
import com.securityexpert.nexus.ui2.service.policy.*;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.service.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.tomcat.util.http.fileupload.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Cutover segments and pre-cutover impact assessments (ACI segmentation design section 4, step 1). Reads stored
 * policies, the held topology and operator-uploaded traffic-log CSV files only; no device or API contact. Each flow's
 * target firewall comes from its computed path (PO 2026-11-14) unless the request names an operator override, which is
 * labelled on every result. Writes need
 * {@code cutover_assessment_write} (security_admin, onboarding_admin); reads {@code cutover_assessment_read}, the
 * aiview persona masked through {@link CutoverPrivacy}.
 */
@RestController
public final class CutoverAssessmentController {
    static final String BASE = "/api/v2/cutover";
    static final Duration TIME_LIMIT = Duration.ofMinutes(10);
    private final CutoverAssessmentService service;
    private final CutoverPathTargets paths;
    private final RbacEvaluator rbac;
    private final TopologyNamePseudonymizer names;
    private final SubnetPreservingIpMasker ips;
    private final PolicyBulkhead heavy;
    private final CutoverFlowRetention retention;
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore uploads = new Semaphore(1);

    public CutoverAssessmentController(CutoverAssessmentService service, CutoverPathTargets paths, RbacEvaluator rbac,
            TopologyNamePseudonymizer names, SubnetPreservingIpMasker ips, PolicyBulkhead heavy, CutoverFlowRetention retention) {
        this.service = service; this.paths = paths; this.rbac = rbac; this.names = names; this.ips = ips; this.heavy = heavy;
        this.retention = retention;
    }

    @GetMapping(BASE + "/segments")
    public ResponseEntity<?> segments(HttpServletRequest request) {
        return ok(request, Map.of("segments", service.segments(), "canManage", canManage(request)));
    }

    @PostMapping(BASE + "/segments")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!canManage(request)) return error(403, "ADMIN_REQUIRED");
        try {
            String id = service.createSegment(segmentInput(body, Set.of("name", "blocks", "peerBlocks")), actor(request));
            return status(request, 201, Map.of("segmentId", id));
        } catch (IllegalArgumentException invalid) { return invalid(invalid); }
    }

    @GetMapping(BASE + "/segments/{id}")
    public ResponseEntity<?> segment(@PathVariable String id, HttpServletRequest request) {
        if (!identifier(id)) return error(400, "INVALID_ID");
        return service.segment(id).<ResponseEntity<?>>map(segment -> {
            var out = new LinkedHashMap<>(segment);
            out.put("assessments", service.assessments(id)); out.put("canManage", canManage(request));
            return ok(request, out);
        }).orElseGet(() -> error(404, "SEGMENT_NOT_FOUND"));
    }

    @PutMapping(BASE + "/segments/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!canManage(request)) return error(403, "ADMIN_REQUIRED");
        if (!identifier(id)) return error(400, "INVALID_ID");
        try {
            if (!(body.get("revision") instanceof Integer revision)) throw new IllegalArgumentException("SEGMENT_REVISION: the revision you edited is required");
            var outcome = service.updateSegment(id, revision, segmentInput(body, Set.of("name", "blocks", "peerBlocks", "revision")), actor(request));
            return switch (outcome) {
                case UPDATED -> ok(request, Map.of("segmentId", id, "revision", revision + 1));
                case NOT_FOUND -> error(404, "SEGMENT_NOT_FOUND");
                case REVISION_CONFLICT -> error(409, "SEGMENT_REVISION_CONFLICT: the segment changed since you read it");
                case NAME_TAKEN -> error(400, "SEGMENT_NAME_TAKEN: another segment has this name");
            };
        } catch (IllegalArgumentException invalid) { return invalid(invalid); }
    }

    @DeleteMapping(BASE + "/segments/{id}")
    public ResponseEntity<?> deleteSegment(@PathVariable String id, HttpServletRequest request) {
        if (!canManage(request)) return error(403, "ADMIN_REQUIRED");
        if (!identifier(id)) return error(400, "INVALID_ID");
        return service.deleteSegment(id, actor(request)) ? ok(request, Map.of("deleted", true)) : error(404, "SEGMENT_NOT_FOUND");
    }

    /** Multipart: a {@code spec} JSON field first, then one {@code file} part per mapping in the spec, in order. */
    @PostMapping(path = BASE + "/segments/{id}/assessments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> assess(@PathVariable String id, HttpServletRequest request) {
        if (!canManage(request)) return error(403, "ADMIN_REQUIRED");
        if (!identifier(id)) return error(400, "INVALID_ID");
        if (!uploads.tryAcquire()) return error(429, "ASSESSMENT_BUSY: retry after the current assessment completes");
        var permit = heavy.tryEnter();
        if (permit == null) { uploads.release(); return PolicyBulkhead.busy(); }
        try {
            var upload = new FileUpload();
            upload.setSizeMax((OfflineCsv.MAX_BYTES + 65_536) * CutoverAssessment.MAX_UPLOADS); upload.setFileSizeMax(OfflineCsv.MAX_BYTES);
            upload.setFileCountMax(CutoverAssessment.MAX_UPLOADS + 1); upload.setHeaderEncoding("UTF-8");
            var parts = upload.getItemIterator(new UploadContext() {
                public String getCharacterEncoding() { return "UTF-8"; }
                public String getContentType() { return request.getContentType(); }
                public long contentLength() { return request.getContentLengthLong(); }
                public InputStream getInputStream() throws IOException { return request.getInputStream(); }
            });
            if (!parts.hasNext()) return error(400, "MULTIPART_SPEC_FIRST: send spec, then files");
            var specPart = parts.next();
            if (!specPart.isFormField() || !specPart.getFieldName().equals("spec")) return error(400, "MULTIPART_SPEC_FIRST: send spec, then files");
            byte[] specBytes;
            try (var stream = specPart.openStream()) { specBytes = stream.readNBytes(65_537); }
            if (specBytes.length > 65_536) return error(400, "SPEC_INVALID: spec is too large");
            CutoverAssessmentService.Spec spec;
            try { spec = json.readValue(new String(specBytes, StandardCharsets.UTF_8), new TypeReference<CutoverAssessmentService.Spec>() {}); }
            catch (IOException invalid) {
                return invalid.getCause() instanceof IllegalArgumentException cause ? invalid(cause) : error(400, "SPEC_INVALID: spec must be a JSON object");
            }
            Optional<CutoverPathTargets.Routed> routed = spec.targetOverride() == null ? paths.current() : Optional.empty();
            CutoverAssessmentService.Prepared prepared;
            try { prepared = service.prepare(id, spec, routed, routed.map(CutoverPathTargets.Routed::snapshotId).orElse(null)); }
            catch (NoSuchElementException missing) { return error(404, missing.getMessage()); }
            catch (IllegalStateException notBuilt) { return error(409, "TOPOLOGY_NOT_BUILT: no topology is held; rebuild it on the Trace screen or name a target override"); }
            int files = 0;
            while (parts.hasNext()) {
                var file = parts.next();
                if (file.isFormField() || !file.getFieldName().equals("file")) return error(400, "MULTIPART_PARTS: spec, then file parts only");
                if (files >= spec.mappings().size()) return error(400, "CSV_MAPPING: one mapping per uploaded file, 1 to 8 files");
                String name = file.getName() == null ? "" : file.getName().replace('\\', '/');
                name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                if (!(name.endsWith(".csv"))) return error(400, "CSV_FILENAME: upload .csv files");
                try (var stream = file.openStream()) { prepared.run().read(stream, new LinkedHashMap<>(spec.mappings().get(files))); }
                files++;
            }
            if (files != spec.mappings().size()) return error(400, "CSV_MAPPING: one mapping per uploaded file, 1 to 8 files");
            var result = prepared.run().finish(Instant.now().plus(TIME_LIMIT));
            String assessment = service.save(prepared, result, actor(request));
            return status(request, 201, Map.of("assessmentId", assessment, "readiness", result.readiness(), "status", result.status()));
        } catch (IllegalArgumentException invalid) {
            String message = invalid.getMessage();
            return message != null && message.startsWith("CSV_SIZE_LIMIT") ? error(413, message) : invalid(invalid);
        } catch (IOException invalid) {
            // Multipart exceptions may contain filenames or raw data: never echo or log them.
            return error(400, "CSV_UPLOAD_INVALID: UTF-8 CSV only, maximum 100 MB per file, valid multipart required");
        } finally { permit.close(); uploads.release(); }
    }

    @GetMapping(BASE + "/assessments/{id}")
    public ResponseEntity<?> assessment(@PathVariable String id, HttpServletRequest request) {
        if (!identifier(id)) return error(400, "INVALID_ID");
        return service.assessment(id, paths.currentSnapshotId()).<ResponseEntity<?>>map(found -> ok(request, found))
            .orElseGet(() -> error(404, "ASSESSMENT_NOT_FOUND"));
    }

    @GetMapping(BASE + "/assessments/{id}/flows")
    public ResponseEntity<?> flows(@PathVariable String id, @RequestParam(defaultValue = "FLOW") String kind,
            @RequestParam(required = false) String decision, @RequestParam(defaultValue = "0") int page, HttpServletRequest request) {
        if (!identifier(id)) return error(400, "INVALID_ID");
        if (!Set.of("FLOW", "MISSING_RULE").contains(kind)) return error(400, "INVALID_KIND");
        if (decision != null && !CutoverAssessment.DECISIONS.contains(decision)) return error(400, "INVALID_DECISION");
        if (page < 0 || page > 10_000) return error(400, "INVALID_PAGE");
        if (!service.assessmentExists(id)) return error(404, "ASSESSMENT_NOT_FOUND");
        var found = service.flows(id, kind, decision, page);
        // Q7 retention: an empty page of an assessment whose flow rows were removed says so, never "no flows".
        if (found.get("total") instanceof Number total && total.longValue() == 0 && retention.flowsExpired(id)) {
            var out = new LinkedHashMap<String, Object>(found);
            out.put("flowsExpired", true); out.put("retentionDays", CutoverFlowRetention.RETENTION_DAYS);
            return ok(request, out);
        }
        return ok(request, found);
    }

    @DeleteMapping(BASE + "/assessments/{id}")
    public ResponseEntity<?> deleteAssessment(@PathVariable String id, HttpServletRequest request) {
        if (!canManage(request)) return error(403, "ADMIN_REQUIRED");
        if (!identifier(id)) return error(400, "INVALID_ID");
        return service.deleteAssessment(id, actor(request)) ? ok(request, Map.of("deleted", true)) : error(404, "ASSESSMENT_NOT_FOUND");
    }

    private static CutoverAssessmentService.SegmentInput segmentInput(Map<String, Object> body, Set<String> allowed) {
        if (body == null || !allowed.containsAll(body.keySet())) throw new IllegalArgumentException("SEGMENT_FIELDS: name, blocks and peerBlocks only");
        if (!(body.get("name") instanceof String name)) throw new IllegalArgumentException("SEGMENT_NAME: 1 to 64 characters, no control characters");
        return new CutoverAssessmentService.SegmentInput(name, strings(body.get("blocks"), "blocks"), strings(body.get("peerBlocks"), "peerBlocks"));
    }
    private static List<String> strings(Object value, String field) {
        if (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String)))
            throw new IllegalArgumentException("SEGMENT_BLOCKS: " + field + " needs 1 to 4096 blocks");
        return list.stream().map(String.class::cast).toList();
    }
    private static boolean identifier(String id) { return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
    private boolean canManage(HttpServletRequest request) {
        String actor = actor(request);
        return actor != null && !PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)
            && rbac.evaluateAny(actor, Set.of(RoleToken.SECURITY_ADMIN, RoleToken.ONBOARDING_ADMIN), Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }
    private static String actor(HttpServletRequest request) { return (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE); }
    private ResponseEntity<?> ok(HttpServletRequest request, Map<String, Object> body) { return status(request, 200, body); }
    private ResponseEntity<?> status(HttpServletRequest request, int code, Map<String, Object> body) {
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore())
            .body(new CutoverResponse(masked, masked ? CutoverPrivacy.mask(body, names, ips, CutoverPathTargets.REASONS) : body));
    }
    /** Only named, fixed-form refusals are echoed; anything else is a generic code (never file content). */
    private static ResponseEntity<?> invalid(IllegalArgumentException invalid) {
        String message = invalid.getMessage();
        return error(400, message != null && message.matches("[A-Z][A-Z_]+: [A-Za-z0-9 ,;./()'-]+") ? message : "CUTOVER_INPUT_INVALID");
    }
    private static ResponseEntity<?> error(int code, String message) {
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(Map.of("error", message));
    }
}
