package com.securityexpert.nexus.ui2.worker.compliance.model;

import java.util.List;

/** Operator reference text only; never an executable remediation primitive. */
public record ComplianceGuidance(String summary, List<String> steps, String cli,
        List<String> references, String caution) {
    public ComplianceGuidance {
        steps = steps == null ? List.of() : List.copyOf(steps);
        references = references == null ? List.of() : List.copyOf(references);
        if (cli != null && !cli.isBlank() && references.isEmpty()) {
            throw new IllegalArgumentException("CLI guidance requires a vendor documentation reference");
        }
    }
}
