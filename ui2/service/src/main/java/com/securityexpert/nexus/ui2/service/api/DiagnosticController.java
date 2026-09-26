package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.service.device.diagnostic.DiagnosticService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/** Typed class-0 diagnostic jobs; command text never originates in a client request. */
@RestController
public final class DiagnosticController {
    private final DiagnosticService service;

    public DiagnosticController(DiagnosticService service) {
        this.service = service;
    }

    @GetMapping("/api/v2/diagnostics/targets")
    public ResponseEntity<?> targets() {
        return ResponseEntity.ok(Map.of("targets", service.targets()));
    }

    @GetMapping("/api/v2/diagnostics/ports")
    public ResponseEntity<?> ports(@RequestParam("device_id") String deviceId) {
        return ResponseEntity.ok(Map.of("ports", service.ports(deviceId)));
    }

    @GetMapping("/api/v2/diagnostics/preview")
    public ResponseEntity<?> preview(@RequestParam("device_id") String deviceId, @RequestParam String port) {
        return service.preview(deviceId, port).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "DIAGNOSTIC_TARGET_UNAVAILABLE")));
    }

    @PostMapping("/api/v2/diagnostics")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> request, HttpServletRequest servletRequest) {
        if (!request.keySet().equals(Set.of("device_id", "port", "request_id"))
                || !(request.get("device_id") instanceof String deviceId)
                || !(request.get("port") instanceof String port)
                || !(request.get("request_id") instanceof String requestId)) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_DIAGNOSTIC_REQUEST"));
        }
        String actor = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        AdmissionResult result = service.submit(deviceId, port, requestId, actor);
        return switch (result) {
            case AdmissionResult.Admitted admitted -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("job_id", admitted.jobId()));
            case AdmissionResult.Deduplicated deduplicated -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("job_id", deduplicated.jobId()));
            case AdmissionResult.Refused refused -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", refused.code()));
        };
    }

    @GetMapping("/api/v2/diagnostics/{jobId}")
    public ResponseEntity<?> result(@PathVariable String jobId) {
        return service.result(jobId).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "NOT_FOUND")));
    }
}
