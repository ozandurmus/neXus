package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.service.policy.PolicyInstallMonitoringService;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * Operations &gt; Policy installation — Phase 1 (P1: Read-Only Monitoring) controller
 * ({@code docs/design/OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md}, FROZEN).
 *
 * <p>Exposes a single read-only {@code GET} route. Any authenticated role may read; {@code aiview}
 * receives the masked projection ({@link PrivacyMaskingResponseBodyAdvice#isReplayViewer}).</p>
 */
@RestController
public final class PolicyInstallMonitoringController {

    private final PolicyInstallMonitoringService monitoring;

    public PolicyInstallMonitoringController(PolicyInstallMonitoringService monitoring) {
        this.monitoring = Objects.requireNonNull(monitoring, "monitoring");
    }

    @GetMapping("/api/v2/operations/policy-install/status")
    public ResponseEntity<?> status(
            @RequestParam(defaultValue = "") String vendor,
            @RequestParam(defaultValue = "") String state,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            HttpServletRequest request
    ) {
        var req = new PolicyInstallMonitoringService.Request(vendor, state, q, page);
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(monitoring.status(req, masked));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidFilters(IllegalArgumentException invalid) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", "INVALID_POLICY_INSTALL_FILTERS"));
    }
}
