package com.securityexpert.nexus.ui2.worker.compliance.model;

import java.util.List;

public record ComplianceControl(
        String id,
        String title,
        String rationale,
        Severity severity,
        String controlPlane,
        String evaluationScope,
        List<FrameworkMapping> frameworks,
        List<VendorBinding> bindings
) {
    public VendorBinding findBinding(String vendor, String platformFamily) {
        if (bindings == null) return null;
        for (VendorBinding binding : bindings) {
            if (binding.vendor().equalsIgnoreCase(vendor) &&
                (platformFamily == null || binding.platformFamily().equalsIgnoreCase(platformFamily))) {
                return binding;
            }
        }
        return null;
    }
}
