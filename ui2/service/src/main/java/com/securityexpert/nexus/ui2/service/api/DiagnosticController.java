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
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import org.springframework.web.server.ResponseStatusException;

/** Typed class-0 diagnostic jobs; command text never originates in a client request. */
@RestController
public final class DiagnosticController {
    private final DiagnosticService service;
    private final RbacEvaluator rbac;

    public DiagnosticController(DiagnosticService service, RbacEvaluator rbac) {
        this.service = service;
        this.rbac = rbac;
    }

    @GetMapping("/api/v2/diagnostics/targets")
    public ResponseEntity<?> targets(HttpServletRequest request) {
        requireReadAccess(request);
        return ResponseEntity.ok(Map.of("targets", service.targets(isMasked(request)),
                "canExecute", isAdministrator(request)));
    }

    @GetMapping("/api/v2/diagnostics/ports")
    public ResponseEntity<?> ports(@RequestParam("device_id") String deviceId, HttpServletRequest request) {
        requireReadAccess(request);
        return ResponseEntity.ok(Map.of("ports", service.ports(deviceId)));
    }

    @GetMapping("/api/v2/diagnostics/preview")
    public ResponseEntity<?> preview(@RequestParam("device_id") String deviceId, @RequestParam String port,
            HttpServletRequest request) {
        requireReadAccess(request);
        return service.preview(deviceId, port, isMasked(request)).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "DIAGNOSTIC_TARGET_UNAVAILABLE")));
    }

    @GetMapping("/api/v2/diagnostics/history")
    public ResponseEntity<?> history(@RequestParam(value="device_id",required=false) String deviceId,
            @RequestParam(value="page",defaultValue="0") int page,HttpServletRequest request) {
        requireReadAccess(request);
        return ResponseEntity.ok(Map.of("runs",service.history(deviceId,page,isMasked(request))));
    }

    @PostMapping("/api/v2/diagnostics")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> request, HttpServletRequest servletRequest) {
        if (request.keySet().equals(Set.of("device_id","command","request_id"))
                && request.get("device_id") instanceof String device && request.get("command") instanceof String command
                && request.get("request_id") instanceof String requestId) {
            String actor=(String)servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
            return admitted(service.submitRead(device,command,requestId,actor));
        }
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
    public ResponseEntity<?> result(@PathVariable String jobId, HttpServletRequest request) {
        requireReadAccess(request);
        return service.output(jobId,(String)request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE),isMasked(request)).<ResponseEntity<?>>map(body -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(body))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "NOT_FOUND")));
    }

    private static ResponseEntity<?> admitted(AdmissionResult result) {
        return switch(result) {
            case AdmissionResult.Admitted a -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("job_id",a.jobId()));
            case AdmissionResult.Deduplicated d -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("job_id",d.jobId()));
            case AdmissionResult.Refused r -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code",r.code()));
        };
    }

    private static boolean isMasked(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE));
    }

    private boolean isAdministrator(HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        return actor != null && rbac.evaluate(actor, java.util.Optional.of(RoleToken.SECURITY_ADMIN),
                java.time.Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }

    private void requireReadAccess(HttpServletRequest request) {
        if (request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE) == null
                || (!isMasked(request) && !isAdministrator(request))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DIAGNOSTIC_ACCESS_REQUIRED");
        }
    }
}
