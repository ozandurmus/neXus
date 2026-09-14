package com.securityexpert.nexus.ui2.worker.backup;

import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;

/**
 * One {@code cp_gateway_backup} attempt's inputs (14H BK-9: Check Point
 * gateway only). {@code credentialRef} is BK-11's distinct backup identity
 * -- never {@code device.credentialReferenceId()}, the collection
 * credential -- resolved once at worker startup (an env-backed, per-vendor
 * configuration, mirroring {@code checkPointTrustRuleRef}); empty means the
 * backup credential was never configured, which {@link
 * BackupCapabilityExecutor#collect} refuses before any device contact.
 */
public record BackupRequest(ConnectionTarget connectionTarget, Optional<String> credentialRef, String trustRuleRef) {
}
