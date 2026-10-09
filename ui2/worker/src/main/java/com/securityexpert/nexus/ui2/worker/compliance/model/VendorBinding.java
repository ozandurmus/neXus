package com.securityexpert.nexus.ui2.worker.compliance.model;

public record VendorBinding(
        String vendor,
        String platformFamily,
        EvidenceRequirement evidenceRequirement,
        AssertionRule assertion,
        ComplianceGuidance guidance
) {
    public VendorBinding(String vendor, String platformFamily, EvidenceRequirement evidenceRequirement,
            AssertionRule assertion) {
        this(vendor, platformFamily, evidenceRequirement, assertion, null);
    }
}
