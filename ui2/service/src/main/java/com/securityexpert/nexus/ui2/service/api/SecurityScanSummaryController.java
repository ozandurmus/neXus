package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.notification.SecurityScanReport;

/**
 * Administration > Platform status > Security scans: the latest daily scan's counts and its HIGH/CRITICAL/MEDIUM list
 * (tool, rule id, path or image/package, status, fix flag). Read-only; {@code security_scan_summary_read}. The body holds
 * no device data; replay-viewer responses still pass through {@code PrivacyMaskingResponseBodyAdvice}.
 */
@RestController
public final class SecurityScanSummaryController {

    private final SecurityScanReport report;

    public SecurityScanSummaryController(SecurityScanReport report) {
        this.report = report;
    }

    @GetMapping("/api/v2/security/scan-summary/latest")
    public Map<String, Object> latest() {
        return report.latest();
    }
}
