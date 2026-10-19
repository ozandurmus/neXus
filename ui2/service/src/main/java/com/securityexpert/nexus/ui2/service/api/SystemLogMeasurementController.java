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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.device.diagnostic.SystemLogMeasurementService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * System-log programme phase A measurement summaries (Palo Alto M3/M4 and the numeric summaries of the Check Point
 * {@code cp_syslog_measure_*} diagnostic reads; optional {@code vendor} and {@code source} filters). The GET is a {@code policy_read} (aiview reads it
 * through the masking response advice); the start (security_admin, or aiview for this read-only measurement:
 * PO 2026-10-04 and 2026-10-16) admits one job per enrolled Palo Alto firewall inside the shared job windows (outside a window: 409 {@code OUTSIDE_JOB_WINDOW}).
 */
@RestController
public final class SystemLogMeasurementController {
    private static final java.util.regex.Pattern DEVICE_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private final SystemLogMeasurementService service;

    public SystemLogMeasurementController(SystemLogMeasurementService service) {
        this.service = service;
    }

    @GetMapping("/api/v2/diagnostics/system-log-measurements")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(value = "device_id", required = false) String deviceId,
            @RequestParam(value = "vendor", required = false) String vendor,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("measurements", service.list(Optional.ofNullable(deviceId), Optional.ofNullable(vendor),
                        Optional.ofNullable(source), limit)));
    }

    /**
     * The one-off Spike Detective layout review of one Check Point gateway (body {@code {"device_id": "<opaque id>"}};
     * phase B contract section 8.3, PO approval 2026-10-19). 202 when admitted or already admitted in this window, 409
     * with the refusal code otherwise (outside a window: 409 {@code OUTSIDE_JOB_WINDOW}). The result is one
     * {@code system_log_measurement} row, source {@code cp_spike_layout_review}, read through the GET above.
     */
    @PostMapping("/api/v2/diagnostics/system-log-measurements/spike-layout-review")
    public ResponseEntity<Map<String, Object>> spikeLayoutReview(@RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "SYSLOG_MEASURE_NOT_PERMITTED"));
        }
        String deviceId = body == null ? null : body.get("device_id");
        if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("code", "DEVICE_ID_INVALID"));
        }
        var outcome = service.startSpikeLayoutReview(deviceId, actor);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("outcome", outcome.outcome());
        response.put("job_id", outcome.jobId());
        response.put("code", outcome.code());
        return ResponseEntity.status("REFUSED".equals(outcome.outcome()) ? HttpStatus.CONFLICT : HttpStatus.ACCEPTED)
                .body(response);
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
