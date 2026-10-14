package com.securityexpert.nexus.ui2.service.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.service.policy.*;
import com.securityexpert.nexus.ui2.service.security.*;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.tomcat.util.http.fileupload.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

@RestController
public final class OfflineAnalysisController {
    private final PolicyQueryService policies;
    private final OfflineAnalysisService analyses;
    private final RbacEvaluator rbac;
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore uploads = new Semaphore(1);
    public OfflineAnalysisController(PolicyQueryService policies, OfflineAnalysisService analyses, RbacEvaluator rbac) {
        this.policies = policies; this.analyses = analyses; this.rbac = rbac;
    }
    @GetMapping("/api/v2/policy/policies/{id}/offline-analyses")
    public ResponseEntity<?> list(@PathVariable String id, @RequestParam(defaultValue = "0") int page, HttpServletRequest request) {
        if (page < 0 || page > 10000) return error(400, "INVALID_PAGE");
        return ok(Map.of("analyses", analyses.list(id, page), "canManage", canManage(request)));
    }
    @GetMapping("/api/v2/policy/offline-analyses/{id}")
    public ResponseEntity<?> read(@PathVariable String id) {
        return analyses.find(id).<ResponseEntity<?>>map(OfflineAnalysisController::ok).orElseGet(() -> error(404, "ANALYSIS_NOT_FOUND"));
    }
    @DeleteMapping("/api/v2/policy/offline-analyses/{id}")
    public ResponseEntity<?> delete(@PathVariable String id, HttpServletRequest request) {
        if (!canManage(request)) return error(403, "ADMIN_REQUIRED");
        return analyses.delete(id, actor(request)) ? ok(Map.of("deleted", true)) : error(404, "ANALYSIS_NOT_FOUND");
    }
    @PostMapping(path = "/api/v2/policy/policies/{id}/offline-analyses", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@PathVariable String id, HttpServletRequest request) {
        if (!canManage(request)) return error(403, "ADMIN_REQUIRED");
        if (!uploads.tryAcquire()) return error(429, "ANALYSIS_BUSY: retry after the current upload completes");
        try {
            var snapshot = policies.find(id); if (snapshot.isEmpty()) return error(404, "POLICY_NOT_FOUND");
            var upload = new FileUpload(); upload.setSizeMax(OfflineCsv.MAX_BYTES + 65536); upload.setFileSizeMax(OfflineCsv.MAX_BYTES);
            upload.setFileCountMax(2); upload.setHeaderEncoding("UTF-8");
            var parts = upload.getItemIterator(new UploadContext() {
                public String getCharacterEncoding() { return "UTF-8"; }
                public String getContentType() { return request.getContentType(); }
                public long contentLength() { return request.getContentLengthLong(); }
                public InputStream getInputStream() throws IOException { return request.getInputStream(); }
            });
            if (!parts.hasNext()) return error(400, "MULTIPART_MAPPING_FIRST: send mapping, then file");
            var mappingPart = parts.next();
            if (!mappingPart.isFormField() || !mappingPart.getFieldName().equals("mapping")) return error(400, "MULTIPART_MAPPING_FIRST: send mapping, then file");
            byte[] mappingBytes;
            try (var stream = mappingPart.openStream()) { mappingBytes = stream.readNBytes(16385); }
            if (mappingBytes.length > 16384) return error(400, "CSV_MAPPING: mapping is too large");
            Map<String, String> mapping;
            try { mapping = json.readValue(new String(mappingBytes, StandardCharsets.UTF_8), new TypeReference<Map<String, String>>() {}); }
            catch (IOException invalid) { return error(400, "CSV_MAPPING: mapping must be a JSON object of field/header names"); }
            if (!parts.hasNext()) return error(400, "CSV_FILE_REQUIRED");
            var file = parts.next();
            if (file.isFormField() || !file.getFieldName().equals("file")) return error(400, "CSV_FILE_REQUIRED");
            String filename = file.getName();
            if (filename == null) return error(400, "CSV_FILE_REQUIRED");
            filename = filename.replace('\\', '/'); filename = filename.substring(filename.lastIndexOf('/') + 1);
            if (filename.isBlank() || filename.length() > 200 || filename.chars().anyMatch(Character::isISOControl)
                    || !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) return error(400, "CSV_FILENAME: upload a .csv file with a name of at most 200 characters");
            Map<String, Object> result;
            try (var stream = file.openStream()) { result = OfflineAnalysis.analyze(snapshot.get(), stream, mapping); }
            if (parts.hasNext()) return error(400, "MULTIPART_PARTS: exactly mapping and one file are allowed");
            String analysis = analyses.save(id, actor(request), filename, result);
            return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(new PolicyResponse(Map.of("id", analysis)));
        } catch (IllegalArgumentException invalid) {
            String message = invalid.getMessage();
            return error(message != null && message.startsWith("CSV_SIZE_LIMIT") ? 413 : 400,
                message != null && message.matches("(?:CSV_[A-Z_]+|SNAPSHOT_IDENTITY|AGGREGATE_LIMIT): [A-Za-z0-9 ,;./()-]+") ? message : "CSV_INVALID");
        } catch (IOException invalid) {
            // Multipart exceptions may contain filenames or raw data: never echo or log them.
            return error(400, "CSV_UPLOAD_INVALID: UTF-8 CSV only, maximum 100 MB, valid multipart required");
        } finally { uploads.release(); }
    }
    private boolean canManage(HttpServletRequest request) {
        String actor = actor(request);
        return actor != null && !PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)
            && rbac.evaluateAny(actor, Set.of(RoleToken.SECURITY_ADMIN, RoleToken.ONBOARDING_ADMIN), Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }
    private static String actor(HttpServletRequest request) { return (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE); }
    private static ResponseEntity<?> ok(Map<String, Object> body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PolicyResponse(body)); }
    private static ResponseEntity<?> error(int code, String message) { return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(Map.of("error", message)); }
}
