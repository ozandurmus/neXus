package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobCancellationRepository;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

@RestController
public final class JobCancellationController {
    private final JobCancellationRepository jobs;
    @org.springframework.beans.factory.annotation.Autowired
    public JobCancellationController(TransactionBoundary tx) { jobs = new JobCancellationRepository(tx); }

    public JobCancellationController(JobCancellationRepository jobs) { this.jobs = jobs; }

    @PostMapping("/api/v2/jobs/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id, HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        return jobs.request(id, actor).<ResponseEntity<?>>map(state -> ResponseEntity.accepted()
            .cacheControl(CacheControl.noStore()).body(Map.of("jobId", id, "state", state, "cancelRequested", true)))
            .orElseGet(() -> ResponseEntity.status(409).cacheControl(CacheControl.noStore())
                .body(Map.of("error", "JOB_NOT_CANCELLABLE")));
    }
}
