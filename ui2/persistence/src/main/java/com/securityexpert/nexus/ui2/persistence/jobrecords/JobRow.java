package com.securityexpert.nexus.ui2.persistence.jobrecords;

public record JobRow(
        String jobId,
        String capabilityId,
        String targetDeviceId,
        String actionClass,
        String state,
        String leaseWorkerId,
        long leaseEpoch,
        String outcome,
        String terminalReason) {
}
