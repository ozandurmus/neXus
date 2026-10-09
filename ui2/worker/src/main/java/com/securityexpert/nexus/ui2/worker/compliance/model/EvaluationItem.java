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
        String observedValue,
        String rationale,
        ComplianceGuidance guidance
) {
    public EvaluationItem(String controlId, String title, Severity severity, List<FrameworkMapping> frameworks,
            Verdict verdict, ReasonCode reasonCode, DisplayStatus displayStatus, String missingEvidenceId,
            String requiredGateEntry, String message, String observedValue) {
        this(controlId, title, severity, frameworks, verdict, reasonCode, displayStatus, missingEvidenceId,
                requiredGateEntry, message, observedValue, null, null);
    }

    public EvaluationItem withGuidance(ComplianceControl control, VendorBinding binding) {
        return new EvaluationItem(controlId, title, severity, frameworks, verdict, reasonCode, displayStatus,
                missingEvidenceId, requiredGateEntry, message, observedValue, control.rationale(),
                binding == null ? null : binding.guidance());
    }
}
