package com.securityexpert.nexus.ui2.worker.compliance.model;

public record FrameworkMapping(
        ComplianceFramework framework,
        String reference,
        String version,
        String profile,
        String mappingStrength
) {
    public static FrameworkMapping satisfies(ComplianceFramework framework, String reference, String version) {
        return new FrameworkMapping(framework, reference, version, "Default", "SATISFIES");
    }

    public static FrameworkMapping stricterThan(ComplianceFramework framework, String reference, String version) {
        return new FrameworkMapping(framework, reference, version, "Default", "STRICTER_THAN");
    }

    public static FrameworkMapping contributesTo(ComplianceFramework framework, String reference, String version) {
        return new FrameworkMapping(framework, reference, version, "Default", "CONTRIBUTES_TO");
    }
}
