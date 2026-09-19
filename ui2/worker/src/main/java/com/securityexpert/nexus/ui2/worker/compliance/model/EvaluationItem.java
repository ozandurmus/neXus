package com.securityexpert.nexus.ui2.worker.compliance.model;

import java.util.List;

public record EvaluationItem(
        String controlId,
        String title,
        Severity severity,
        List<FrameworkMapping> frameworks,
        Verdict verdict,
        ReasonCode reasonCode,
        DisplayStatus displayStatus,
        String missingEvidenceId,
        String requiredGateEntry,
        String message,
        String observedValue
) {
}
