package com.securityexpert.nexus.ui2.service.api;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.report.ReportRunStore.RunRow;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.report.ProjectionMode;
import com.securityexpert.nexus.ui2.service.report.ReportExecutor;
import com.securityexpert.nexus.ui2.service.report.ReportFormat;
import com.securityexpert.nexus.ui2.service.report.ReportJson;
import com.securityexpert.nexus.ui2.service.report.ReportParameters;
import com.securityexpert.nexus.ui2.service.report.ReportResponse;
import com.securityexpert.nexus.ui2.service.report.ReportService;
import com.securityexpert.nexus.ui2.service.report.ReportTemplate;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Reports R1 API (docs/design/REPORTS_R1_CONTRACT.md). The route map gates {@code report_read} (catalogue, preview,
 * history) and {@code report_generate} (generate, download); {@link ReportService} then checks the underlying domain
 * reads and the RAW projection ({@code report_raw_view}, never a replay viewer). Every JSON body is either projected
 * already or identity-free, and travels as a {@link ReportResponse}.
 */
@RestController
public final class ReportsController {

    private static final String BASE = "/api/v2/reports";

    public record ReportRequest(String template_id, String from, String to, String framework, String projection,
            String format) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReportService service;

    public ReportsController(ReportService service) {
        this.service = service;
    }

    @GetMapping(BASE + "/catalogue")
    public ResponseEntity<Object> catalogue(HttpServletRequest request) {
        String actor = actor(request);
        boolean masked = masked(request);
        List<Map<String, Object>> templates = new ArrayList<>();
        for (ReportTemplate t : ReportTemplate.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("template_id", t.id());
            m.put("title", t.title());
            m.put("audience", t.audience());
            m.put("question", t.question());
            m.put("formats", List.of("PDF", "XLSX", "CSV"));
            m.put("framework_parameter", t.frameworkParameter());
            m.put("missing_reads", service.missingDomainReads(actor, t));
            templates.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templates", templates);
        body.put("frameworks", ReportParameters.FRAMEWORKS);
        body.put("permissions", permissions(request));
        body.put("retention_days", ReportService.RETENTION.toDays());
        return ok(body, true);
    }

    @PostMapping(BASE + "/preview")
    public ResponseEntity<Object> preview(@RequestBody ReportRequest body, HttpServletRequest request) {
        try {
            ReportTemplate template = template(body);
            ReportParameters parameters = ReportParameters.parse(template, body.from(), body.to(), body.framework(),
                    service.now());
            var doc = service.preview(actor(request), masked(request), template, parameters, projection(body));
            return ok(ReportJson.toMap(doc), "MASKED".equals(doc.projection()));
        } catch (ReportParameters.Invalid e) {
            return error(400, e.code(), e.getMessage());
        } catch (ReportService.Refusal e) {
            return error(e.status(), e.code(), e.getMessage());
        } catch (ReportExecutor.Busy e) {
            return error(503, "REPORTS_BUSY", e.getMessage());
        }
    }

    @PostMapping(BASE + "/runs")
    public ResponseEntity<Object> generate(@RequestBody ReportRequest body, HttpServletRequest request) {
        try {
            ReportTemplate template = template(body);
            ReportFormat format = ReportFormat.parse(body.format())
                    .orElseThrow(() -> new ReportService.Refusal(400, "INVALID_FORMAT", "Format is PDF, XLSX or CSV"));
            ReportParameters parameters = ReportParameters.parse(template, body.from(), body.to(), body.framework(),
                    service.now());
            RunRow row = service.submit(actor(request), masked(request), template, parameters, projection(body), format);
            return ResponseEntity.status(202).cacheControl(CacheControl.noStore())
                    .body(new ReportResponse(run(row, actor(request)), true));
        } catch (ReportParameters.Invalid e) {
            return error(400, e.code(), e.getMessage());
        } catch (ReportService.Refusal e) {
            return error(e.status(), e.code(), e.getMessage());
        }
    }

    @GetMapping(BASE + "/runs")
    public ResponseEntity<Object> history(@RequestParam(required = false, defaultValue = "100") int limit,
            HttpServletRequest request) {
        String actor = actor(request);
        List<Map<String, Object>> rows = service.history(actor, masked(request), limit).stream()
                .map(r -> run(r, actor)).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runs", rows);
        body.put("permissions", permissions(request));
        return ok(body, true);
    }

    @GetMapping(BASE + "/runs/{runId}")
    public ResponseEntity<Object> runDetail(@PathVariable String runId, HttpServletRequest request) {
        UUID id = uuid(runId);
        if (id == null) return error(400, "INVALID_ID", "Run ids are opaque UUIDs");
        try {
            return ok(run(service.run(actor(request), masked(request), id), actor(request)), true);
        } catch (ReportService.Refusal e) {
            return error(e.status(), e.code(), e.getMessage());
        }
    }

    @GetMapping(BASE + "/runs/{runId}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String runId, HttpServletRequest request) {
        UUID id = uuid(runId);
        if (id == null) return streamError(400, "INVALID_ID", "Run ids are opaque UUIDs");
        ReportService.Download download;
        try {
            download = service.download(actor(request), masked(request), id);
        } catch (ReportService.Refusal e) {
            return streamError(e.status(), e.code(), e.getMessage());
        }
        InputStream stream = download.stream();
        StreamingResponseBody body = out -> {
            try (InputStream in = stream) {
                in.transferTo(out);
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, download.contentType());
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"");
        headers.set("X-Content-Type-Options", "nosniff");
        if ("MASKED".equals(download.run().projectionMode())) headers.set(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER, "true");
        if (download.bytes() >= 0) headers.setContentLength(download.bytes());
        return ResponseEntity.ok().headers(headers).cacheControl(CacheControl.noStore()).body(body);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private Map<String, Object> run(RunRow r, String actor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", r.runId().toString());
        m.put("template_id", r.templateId());
        m.put("title", ReportTemplate.fromId(r.templateId()).map(ReportTemplate::title).orElse("UNKNOWN"));
        m.put("state", r.state());
        m.put("projection", r.projectionMode());
        m.put("format", r.format());
        m.put("parameters", parse(r.parametersJson()));
        m.put("data_as_of", parse(r.dataAsOfJson()));
        m.put("plaintext_bytes", r.plaintextBytes());
        m.put("error_code", r.errorCode());
        m.put("requested_at", r.requestedAt() == null ? null : r.requestedAt().toString());
        m.put("finished_at", r.finishedAt() == null ? null : r.finishedAt().toString());
        m.put("expires_at", r.expiresAt() == null ? null : r.expiresAt().toString());
        m.put("downloadable", service.downloadable(r));
        // The requester is an actor fingerprint; the screen only needs to know whether it was this session.
        m.put("mine", actor != null && actor.equals(r.requestedBy()));
        return m;
    }

    private static Object parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> permissions(HttpServletRequest request) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("masked", masked(request));
        p.put("raw_view", service.mayRaw(actor(request), masked(request)));
        return p;
    }

    private static ReportTemplate template(ReportRequest body) {
        if (body == null) throw new ReportService.Refusal(400, "INVALID_TEMPLATE", "A template is required");
        return ReportTemplate.fromId(body.template_id())
                .orElseThrow(() -> new ReportService.Refusal(400, "INVALID_TEMPLATE", "Unknown report template"));
    }

    private static ProjectionMode projection(ReportRequest body) {
        return ProjectionMode.parse(body.projection())
                .orElseThrow(() -> new ReportService.Refusal(400, "INVALID_PROJECTION", "Projection is MASKED or RAW"));
    }

    private static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equals(value.toLowerCase(java.util.Locale.ROOT)) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean masked(HttpServletRequest request) {
        return PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
    }

    private static String actor(HttpServletRequest request) {
        return (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }

    private static ResponseEntity<Object> ok(Object body, boolean masked) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ReportResponse(body, masked));
    }

    /** A refusal on the download route: JSON, but through the streaming return type the route declares. */
    private static ResponseEntity<StreamingResponseBody> streamError(int status, String code, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("reason", reason);
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = ("{\"error\":\"" + code + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        byte[] payload = bytes;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(out -> out.write(payload));
    }

    private static ResponseEntity<Object> error(int status, String code, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("reason", reason);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ReportResponse(body, true));
    }
}
