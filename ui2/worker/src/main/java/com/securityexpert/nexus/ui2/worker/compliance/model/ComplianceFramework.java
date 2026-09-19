package com.securityexpert.nexus.ui2.worker.compliance.model;

public enum ComplianceFramework {
    CIS("CIS Benchmark", "Center for Internet Security"),
    PCI_DSS("PCI-DSS", "Payment Card Industry Data Security Standard"),
    NIST_800_53("NIST SP 800-53", "National Institute of Standards and Technology"),
    FINANCIAL_BASELINE("Financial Baseline", "Banking & Fintech Hardening Baseline"),
    COBIT("COBIT 2019", "Control Objectives for Information Technologies");

    private final String displayName;
    private final String description;

    ComplianceFramework(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }
}
