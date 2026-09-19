package com.securityexpert.nexus.ui2.worker.compliance.model;

import java.util.List;

public record EvaluationResult(
        String deviceId,
        String vendor,
        int totalAssigned,
        int passCount,
        int failCount,
        int dataUnavailableCount,
        double observedCompliance, // PASS / (PASS + FAIL) * 100
        double evidenceCoverage,    // (PASS + FAIL) / Total * 100
        double assuredCompliance,   // PASS / Total * 100
        List<EvaluationItem> items
) {
}
