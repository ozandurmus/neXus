package com.securityexpert.nexus.ui2.worker.compliance.model;

public record VendorBinding(
        String vendor,
        String platformFamily,
        EvidenceRequirement evidenceRequirement,
        AssertionRule assertion
) {
}
