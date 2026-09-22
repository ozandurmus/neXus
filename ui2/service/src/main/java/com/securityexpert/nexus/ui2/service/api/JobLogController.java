package com.securityexpert.nexus.ui2.service.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService;
import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.Facets;
import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.JobPage;
import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.JobQuery;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Jobs screen (Product Owner P0, 2026-09-22): the whole history, filtered
 * and paged on the server, plus a CSV export of the same filter. All three
 * routes are {@code job_log_read} posture reads.
 */
@RestController
public final class JobLogController {

    private final JobLogQueryService jobLogQueryService;
    private final PrivacyMaskingResponseBodyAdvice masking;

    public JobLogController(JobLogQueryService jobLogQueryService, PrivacyMaskingResponseBodyAdvice masking) {
        this.jobLogQueryService = jobLogQueryService;
        this.masking = masking;
    }

    @GetMapping("/api/v2/jobs")
    public ResponseEntity<JobPage> listJobs(@RequestParam(required = false) String state,
            @RequestParam(name = "job_type", required = false) String jobType,
            @RequestParam(name = "device_id", required = false) String deviceId,
            @RequestParam(required = false) String since, @RequestParam(required = false) String until,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        Optional<JobQuery> query = parse(state, jobType, deviceId, since, until, q, page, pageSize);
        if (query.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(jobLogQueryService.query(query.get()));
    }

    @GetMapping("/api/v2/jobs/stats")
    public JobLogQueryService.Stats stats() {
        return jobLogQueryService.stats();
    }

    @GetMapping("/api/v2/jobs/facets")
    public Facets facets() {
        return jobLogQueryService.facets();
    }

    @GetMapping(value = "/api/v2/jobs/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(required = false) String state,
            @RequestParam(name = "job_type", required = false) String jobType,
            @RequestParam(name = "device_id", required = false) String deviceId,
            @RequestParam(required = false) String since, @RequestParam(required = false) String until,
            @RequestParam(required = false) String q, HttpServletRequest request) {
        Optional<JobQuery> query = parse(state, jobType, deviceId, since, until, q, 1, 1);
        if (query.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        String csv = jobLogQueryService.exportCsv(query.get());
        // The body advice masks objects, not a byte body: the AIView persona's CSV is masked here, as text.
        if (PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)) {
            csv = (String) masking.maskObject(csv, null);
        }
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"nexus-jobs-" + Instant.now().toString().replace(":", "") + ".csv\"")
                .header("Cache-Control", "no-store")
                .body(body);
    }

    private static Optional<JobQuery> parse(String state, String jobType, String deviceId, String since, String until,
            String q, int page, int pageSize) {
        try {
            return Optional.of(new JobQuery(Optional.ofNullable(state), Optional.ofNullable(jobType),
                    Optional.ofNullable(deviceId), instant(since), instant(until), Optional.ofNullable(q), page, pageSize));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<Instant> instant(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Instant.parse(value.strip()));
    }
}
