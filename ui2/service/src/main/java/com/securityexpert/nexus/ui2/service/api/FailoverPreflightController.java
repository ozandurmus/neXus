package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.PreflightReport;
import com.securityexpert.nexus.ui2.service.failover.PreflightService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for Failover Pre-Flight Readiness Inspection (Phase A).
 * Read-only capability, fully accessible under the aiview inspection persona.
 */
@RestController
@RequestMapping("/api/v2/failover")
public class FailoverPreflightController {

    private final PreflightService preflightService;

    public FailoverPreflightController(PreflightService preflightService) {
        this.preflightService = preflightService;
    }

    @GetMapping("/checks")
    public ResponseEntity<Map<String, Object>> listChecks() {
        List<PreflightService.PreflightCheckSummary> checks = preflightService.listRegisteredChecks();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total_checks", checks.size());
        body.put("checks", checks);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{clusterRef}/preflight")
    public ResponseEntity<Map<String, Object>> getLatestPreflight(@PathVariable String clusterRef) {
        PreflightReport report = preflightService.getLatestReport(clusterRef);
        return ResponseEntity.ok(serializeReport(report));
    }

    @PostMapping("/{clusterRef}/preflight")
    public ResponseEntity<Map<String, Object>> triggerPreflight(@PathVariable String clusterRef) {
        PreflightReport report = preflightService.evaluateCluster(clusterRef);
        return ResponseEntity.ok(serializeReport(report));
    }

    private Map<String, Object> serializeReport(PreflightReport report) {
        Map<String, Object> body = new LinkedHashMap<>();
        String clusterId = report.clusterId();
        // C-6 Remediation: Guarantee opaque cluster ID (UUID format) to eliminate raw topology leakage
        String opaqueId = isUuid(clusterId)
            ? clusterId
            : UUID.nameUUIDFromBytes(clusterId.getBytes(StandardCharsets.UTF_8)).toString();
        body.put("cluster_id", opaqueId);
        body.put("masked_cluster_name", report.maskedClusterName());
        body.put("vendor", report.vendor());
        body.put("ha_mode", report.haMode());
        body.put("overall_verdict", report.overallVerdict().name());
        body.put("generated_at", report.generatedAt().toString());
        body.put("total_checks", report.totalChecks());
        body.put("pass_count", report.passCount());
        body.put("fail_count", report.failCount());
        body.put("warning_count", report.warningCount());
        body.put("blocking_failure_count", report.blockingFailureCount());

        List<Map<String, Object>> checksJson = report.checks().stream().map(this::serializeCheck).toList();
        body.put("checks", checksJson);
        return body;
    }

    private Map<String, Object> serializeCheck(CheckResult check) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("check_id", check.checkId());
        map.put("name", check.name());
        map.put("category", check.category());
        map.put("status", check.status().name());
        map.put("enforcement", check.enforcement().name());
        map.put("summary", check.summary());
        map.put("remediation_code", check.remediationCode());
        map.put("observed_at", check.observedAt().toString());
        return map;
    }

    private static boolean isUuid(String value) {
        if (value == null || value.length() != 36) return false;
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
