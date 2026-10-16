package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.device.diagnostic.SystemLogMeasurementService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * System-log programme phase A (M3/M4) measurement summaries. The GET is a {@code policy_read} (aiview reads it
 * through the masking response advice); the start (security_admin, or aiview for this read-only measurement:
 * PO 2026-10-04 and 2026-10-16) admits one job per enrolled Palo Alto firewall inside the shared job windows (outside a window: 409 {@code OUTSIDE_JOB_WINDOW}).
 */
@RestController
public final class SystemLogMeasurementController {
    private final SystemLogMeasurementService service;

    public SystemLogMeasurementController(SystemLogMeasurementService service) {
        this.service = service;
    }

    @GetMapping("/api/v2/diagnostics/system-log-measurements")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(value = "device_id", required = false) String deviceId,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("measurements", service.list(Optional.ofNullable(deviceId), limit)));
    }

    @PostMapping("/api/v2/diagnostics/system-log-measurements/start")
    public ResponseEntity<Map<String, Object>> start(HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "SYSLOG_MEASURE_NOT_PERMITTED"));
        }
        var outcome = service.startAll(actor);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enrolled_firewalls", outcome.enrolledFirewalls());
        body.put("admitted", outcome.admitted());
        body.put("deduplicated", outcome.deduplicated());
        body.put("refused", outcome.refused());
        body.put("refusal_codes", outcome.refusalCodes());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }
}
