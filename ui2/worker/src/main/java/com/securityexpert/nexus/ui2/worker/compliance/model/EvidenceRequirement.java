package com.securityexpert.nexus.ui2.worker.compliance.model;

public record EvidenceRequirement(
        String sectionId,
        String settingKey,
        String requiredEvidenceId,
        String gateEntryId,
        String description
) {
    public static EvidenceRequirement of(String sectionId, String settingKey, String requiredEvidenceId, String gateEntryId) {
        return new EvidenceRequirement(sectionId, settingKey, requiredEvidenceId, gateEntryId, "");
    }
}
