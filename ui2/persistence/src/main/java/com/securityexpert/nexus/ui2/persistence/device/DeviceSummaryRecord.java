package com.securityexpert.nexus.ui2.persistence.device;
// 14I MS-1

import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * One {@code GET /devices} row (adminApi.ts's {@code DeviceSummary}): a
 * device plus its observed facts, flattened -- never {@code address_ref}
 * (contract §7's sensitive-field list).
 */
public record DeviceSummaryRecord(
        String deviceId,
        String role,
        String vendorHint,
        DeviceEnrollmentState enrollmentState,
        Optional<String> observedHostname,
        Optional<String> observedModel,
        Optional<String> observedSoftwareVersion,
        Optional<String> observedHaRole,
        Optional<String> clusterMemberRef) {
}
