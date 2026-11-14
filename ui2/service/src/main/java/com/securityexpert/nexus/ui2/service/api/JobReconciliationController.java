package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobReconciliationRepository;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * C2 section 3.5: records the human decision that closes an OUTCOME_UNKNOWN job. The body carries the outcome and
 * the evidence only; the actor is the session. Never contacts a device.
 */
@RestController
public final class JobReconciliationController {
    private final JobReconciliationRepository jobs;
    @org.springframework.beans.factory.annotation.Autowired
    public JobReconciliationController(TransactionBoundary tx) { jobs = new JobReconciliationRepository(tx); }

    public JobReconciliationController(JobReconciliationRepository jobs) { this.jobs = jobs; }

    @PostMapping("/api/v2/jobs/{id}/reconcile")
    public ResponseEntity<?> reconcile(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (body == null || !java.util.Set.of("outcome", "evidence").containsAll(body.keySet())
                || !(body.get("outcome") instanceof String outcome) || !(body.get("evidence") instanceof String evidence))
            return refuse(400, "JOB_RECONCILIATION_INVALID");
        String result;
        try { result = jobs.record(id, outcome, evidence.strip(), actor); }
        catch (IllegalArgumentException invalid) { return refuse(400, "JOB_RECONCILIATION_INVALID"); }
        if (JobReconciliationRepository.RECONCILED.equals(result))
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("jobId", id, "state", "RECONCILED", "reconciledOutcome", outcome));
        return refuse(JobReconciliationRepository.NOT_FOUND.equals(result) ? 404 : 409, result);
    }

    private static ResponseEntity<?> refuse(int status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", code));
    }
}
