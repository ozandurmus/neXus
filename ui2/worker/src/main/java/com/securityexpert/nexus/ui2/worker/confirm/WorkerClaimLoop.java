package com.securityexpert.nexus.ui2.worker.confirm;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.ConfigurationCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.ConfirmCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.DiscoveryCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.InventoryCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRow;
import com.securityexpert.nexus.ui2.worker.backup.BackupJobExecutor;
import com.securityexpert.nexus.ui2.worker.backup.BackupRequest;
import com.securityexpert.nexus.ui2.worker.configuration.ConfigurationJobExecutor;
import com.securityexpert.nexus.ui2.worker.configuration.ConfigurationRequest;
import com.securityexpert.nexus.ui2.worker.discovery.DiscoveryJobExecutor;
import com.securityexpert.nexus.ui2.worker.inventory.InventoryJobExecutor;
import com.securityexpert.nexus.ui2.worker.inventory.InventoryRequest;

/**
 * The worker's claim loop (contract scope "worker runtime": "claim the next
 * admitted job through the C2 lease, heartbeat, execute... release").
 * Bounded concurrency of 1 (CURRENT_STATE "concurrency budget stays at 1
 * per vendor") -- this class claims and fully runs one job to completion
 * before ever claiming another, by construction (no thread pool, no
 * concurrent {@link #claimAndExecuteOnce}).
 */
public final class WorkerClaimLoop {

    private static final List<String> ELIGIBLE_CAPABILITY_IDS = List.of(
            ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT, ConfirmCapabilityIds.DEVICE_CONFIRM_PALO_ALTO,
            InventoryCapabilityIds.CP_INVENTORY_COLLECT, InventoryCapabilityIds.PAN_INVENTORY_COLLECT,
            ConfigurationCapabilityIds.CP_CONFIGURATION_COLLECT, ConfigurationCapabilityIds.PAN_CONFIGURATION_COLLECT,
            DiscoveryCapabilityIds.CP_DISCOVERY_ENUMERATE, DiscoveryCapabilityIds.PAN_DISCOVERY_ENUMERATE,
            BackupCapabilityIds.CP_GAIA_BACKUP_LOCAL);
    private static final int DEFAULT_SSH_PORT = 22;

    private final JobLeaseRepository leaseRepository;
    private final JobRecordDao jobRecordDao;
    private final DeviceRepository deviceRepository;
    private final ConfirmJobExecutor confirmJobExecutor;
    private final InventoryJobExecutor inventoryJobExecutor;
    private final ConfigurationJobExecutor configurationJobExecutor;
    private final DiscoveryJobExecutor discoveryJobExecutor;
    private final BackupJobExecutor backupJobExecutor;
    private final String workerId;
    private final Duration leaseDuration;
    private final String checkPointTrustRuleRef;
    private final String paloAltoTrustRuleRef;
    /** BK-11: the distinct backup credential reference, never {@code device.credentialReferenceId()} -- empty means unconfigured (fails closed inside {@code BackupCapabilityExecutor}). */
    private final Optional<String> backupCredentialRef;

    /** WORKER.md "one worker process serves both vendors" (0159): the same claim loop now also serves every job kind. */
    public WorkerClaimLoop(JobLeaseRepository leaseRepository, JobRecordDao jobRecordDao,
            DeviceRepository deviceRepository, ConfirmJobExecutor confirmJobExecutor,
            InventoryJobExecutor inventoryJobExecutor, ConfigurationJobExecutor configurationJobExecutor,
            DiscoveryJobExecutor discoveryJobExecutor, BackupJobExecutor backupJobExecutor, String workerId,
            Duration leaseDuration, String checkPointTrustRuleRef, String paloAltoTrustRuleRef,
            Optional<String> backupCredentialRef) {
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.jobRecordDao = Objects.requireNonNull(jobRecordDao, "jobRecordDao");
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.confirmJobExecutor = Objects.requireNonNull(confirmJobExecutor, "confirmJobExecutor");
        this.inventoryJobExecutor = Objects.requireNonNull(inventoryJobExecutor, "inventoryJobExecutor");
        this.configurationJobExecutor = Objects.requireNonNull(configurationJobExecutor, "configurationJobExecutor");
        this.discoveryJobExecutor = Objects.requireNonNull(discoveryJobExecutor, "discoveryJobExecutor");
        this.backupJobExecutor = Objects.requireNonNull(backupJobExecutor, "backupJobExecutor");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.checkPointTrustRuleRef = Objects.requireNonNull(checkPointTrustRuleRef, "checkPointTrustRuleRef");
        this.paloAltoTrustRuleRef = Objects.requireNonNull(paloAltoTrustRuleRef, "paloAltoTrustRuleRef");
        this.backupCredentialRef = Objects.requireNonNull(backupCredentialRef, "backupCredentialRef");
    }

    private static final System.Logger LOGGER = System.getLogger(WorkerClaimLoop.class.getName());

    /** Runs until the thread is interrupted, sleeping {@code pollInterval} whenever the queue was empty. */
    public void runUntilInterrupted(Duration pollInterval) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                boolean claimed = claimAndExecuteOnce();
                if (!claimed) {
                    try {
                        Thread.sleep(pollInterval.toMillis());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } catch (Throwable t) {
                LOGGER.log(System.Logger.Level.ERROR, "Unexpected error in worker claim loop: " + t.getMessage(), t);
                try {
                    Thread.sleep(pollInterval.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** @return {@code true} if a job was claimed and run to some outcome; {@code false} if the queue was empty. */
    public boolean claimAndExecuteOnce() {
        Optional<ClaimedJob> claim = leaseRepository.claimNext(workerId, ELIGIBLE_CAPABILITY_IDS, leaseDuration);
        if (claim.isEmpty()) {
            return false;
        }
        long claimMs = System.currentTimeMillis();
        ClaimedJob claimed = claim.get();
        Optional<JobRow> jobOpt = jobRecordDao.find(claimed.jobId());
        if (jobOpt.isEmpty()) {
            LOGGER.log(System.Logger.Level.WARNING, "Claimed job has no row: " + claimed.jobId());
            return true;
        }
        JobRow job = jobOpt.get();
        LOGGER.log(System.Logger.Level.INFO,
                "[WORKER_CLAIM] Worker {0} claimed job {1} ({2}) for device {3} leaseEpoch={4}",
                workerId, claimed.jobId(), job.capabilityId(), job.targetDeviceId(), claimed.leaseEpoch());

        // 14F DR-1: a discovery job's target is a discovery_run row, not a device -- dispatched before any
        // device/endpoint resolution below, which would otherwise crash on a job with no target_device_id.
        if (DiscoveryCapabilityIds.isDiscoveryCapability(job.capabilityId())) {
            try {
                discoveryJobExecutor.execute(claimed.jobId(), claimed.leaseEpoch(), job.targetRef());
            } finally {
                long totalJobTime = System.currentTimeMillis() - claimMs;
                LOGGER.log(System.Logger.Level.INFO,
                        "[WORKER_RELEASE] Worker {0} finished discovery job {1} in {2}ms",
                        workerId, claimed.jobId(), totalJobTime);
            }
            return true;
        }

        Optional<DeviceRecord> deviceOpt = deviceRepository.find(job.targetDeviceId());
        if (deviceOpt.isEmpty()) {
            LOGGER.log(System.Logger.Level.WARNING, "Claimed job targets an unknown device: " + job.targetDeviceId());
            leaseRepository.transitionState(claimed.jobId(), claimed.leaseEpoch(), com.securityexpert.nexus.ui2.jobs.JobState.CLAIMED,
                    com.securityexpert.nexus.ui2.jobs.JobState.FAILED, "system:worker", "claim_device_check",
                    "claimed job targets an unknown device: " + job.targetDeviceId());
            return true;
        }
        DeviceRecord device = deviceOpt.get();

        Optional<EndpointRecord> endpointOpt = deviceRepository.findEndpointByDeviceId(job.targetDeviceId());
        if (endpointOpt.isEmpty()) {
            LOGGER.log(System.Logger.Level.WARNING, "Claimed job device has no endpoint: " + job.targetDeviceId());
            leaseRepository.transitionState(claimed.jobId(), claimed.leaseEpoch(), com.securityexpert.nexus.ui2.jobs.JobState.CLAIMED,
                    com.securityexpert.nexus.ui2.jobs.JobState.FAILED, "system:worker", "claim_endpoint_check",
                    "claimed job's device has no endpoint: " + job.targetDeviceId());
            return true;
        }
        EndpointRecord endpoint = endpointOpt.get();

        try {
            if (InventoryCapabilityIds.isInventoryCapability(job.capabilityId())) {
                InventoryRequest inventoryRequest = buildInventoryRequest(job.capabilityId(), endpoint.endpointId(),
                        endpoint.addressRef(), device.credentialReferenceId());
                inventoryJobExecutor.execute(claimed.jobId(), claimed.leaseEpoch(), job.targetDeviceId(), inventoryRequest,
                        false);
                return true;
            }

            if (ConfigurationCapabilityIds.isConfigurationCapability(job.capabilityId())) {
                ConfigurationRequest configurationRequest = buildConfigurationRequest(job.capabilityId(),
                        endpoint.endpointId(), endpoint.addressRef(), device.credentialReferenceId());
                configurationJobExecutor.execute(claimed.jobId(), claimed.leaseEpoch(), job.targetDeviceId(),
                        configurationRequest, false);
                return true;
            }

            if (BackupCapabilityIds.isBackupCapability(job.capabilityId())) {
                // BK-11: the distinct backup credential, never device.credentialReferenceId() (the collection credential).
                String trustRuleRef = com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver.scopeRef(
                        hostOf(endpoint.addressRef()), portOf(endpoint.addressRef()));
                BackupRequest backupRequest = new BackupRequest(
                        new ConnectionTarget(endpoint.endpointId(), hostOf(endpoint.addressRef()), portOf(endpoint.addressRef())),
                        backupCredentialRef, trustRuleRef);
                backupJobExecutor.execute(claimed.jobId(), claimed.leaseEpoch(), job.targetDeviceId(), backupRequest);
                return true;
            }

            ConfirmRequest primaryRequest = buildRequest(job.capabilityId(), endpoint.endpointId(), endpoint.addressRef(),
                    device.credentialReferenceId());
            var peerRequestFactory = peerRequestFactoryFor(job.capabilityId(), device.credentialReferenceId());

            confirmJobExecutor.execute(claimed.jobId(), claimed.leaseEpoch(), job.targetDeviceId(), primaryRequest,
                    peerRequestFactory, false);
            return true;
        } finally {
            long totalJobTime = System.currentTimeMillis() - claimMs;
            LOGGER.log(System.Logger.Level.INFO,
                    "[WORKER_RELEASE] Worker {0} finished job {1} in {2}ms",
                    workerId, claimed.jobId(), totalJobTime);
        }
    }

    private InventoryRequest buildInventoryRequest(String capabilityId, String endpointId, String addressRef,
            String credentialRef) {
        if (InventoryCapabilityIds.CP_INVENTORY_COLLECT.equals(capabilityId)) {
            String trustRuleRef = com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver.scopeRef(
                    hostOf(addressRef), portOf(addressRef));
            return InventoryRequest.checkPoint(new ConnectionTarget(endpointId, hostOf(addressRef), portOf(addressRef)),
                    credentialRef, trustRuleRef);
        }
        if (InventoryCapabilityIds.PAN_INVENTORY_COLLECT.equals(capabilityId)) {
            return InventoryRequest.paloAlto(new ApiTarget(endpointId, addressRef), credentialRef);
        }
        throw new IllegalStateException("claimed job for an inventory capability the worker does not recognize: "
                + capabilityId);
    }

    private ConfigurationRequest buildConfigurationRequest(String capabilityId, String endpointId, String addressRef,
            String credentialRef) {
        if (ConfigurationCapabilityIds.CP_CONFIGURATION_COLLECT.equals(capabilityId)) {
            String trustRuleRef = com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver.scopeRef(
                    hostOf(addressRef), portOf(addressRef));
            return ConfigurationRequest.checkPoint(new ConnectionTarget(endpointId, hostOf(addressRef), portOf(addressRef)),
                    credentialRef, trustRuleRef);
        }
        if (ConfigurationCapabilityIds.PAN_CONFIGURATION_COLLECT.equals(capabilityId)) {
            return ConfigurationRequest.paloAlto(new ApiTarget(endpointId, addressRef), credentialRef);
        }
        throw new IllegalStateException("claimed job for a configuration capability the worker does not recognize: "
                + capabilityId);
    }

    private PeerFollowResolver.ConfirmRequestFactory peerRequestFactoryFor(String capabilityId, String credentialRef) {
        if (ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT.equals(capabilityId)) {
            return managementAddress -> {
                String trustRuleRef = com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver.scopeRef(
                        managementAddress, DEFAULT_SSH_PORT);
                return ConfirmRequest.checkPoint(
                        new ConnectionTarget(UUID.randomUUID().toString(), managementAddress, DEFAULT_SSH_PORT),
                        credentialRef, trustRuleRef);
            };
        }
        return managementAddress -> buildRequest(capabilityId, "peer", managementAddress, credentialRef);
    }

    private ConfirmRequest buildRequest(String capabilityId, String endpointId, String addressRef,
            String credentialRef) {
        if (ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT.equals(capabilityId)) {
            String trustRuleRef = com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver.scopeRef(
                    hostOf(addressRef), portOf(addressRef));
            return ConfirmRequest.checkPoint(new ConnectionTarget(endpointId, hostOf(addressRef), portOf(addressRef)),
                    credentialRef, trustRuleRef);
        }
        if (ConfirmCapabilityIds.DEVICE_CONFIRM_PALO_ALTO.equals(capabilityId)) {
            return ConfirmRequest.paloAlto(new ApiTarget(endpointId, addressRef), credentialRef);
        }
        throw new IllegalStateException("claimed job for a capability the worker does not recognize: " + capabilityId);
    }

    private static String hostOf(String addressRef) {
        int colon = addressRef.lastIndexOf(':');
        return colon < 0 ? addressRef : addressRef.substring(0, colon);
    }

    private static int portOf(String addressRef) {
        int colon = addressRef.lastIndexOf(':');
        if (colon < 0) {
            return DEFAULT_SSH_PORT;
        }
        try {
            return Integer.parseInt(addressRef.substring(colon + 1));
        } catch (NumberFormatException notAPort) {
            return DEFAULT_SSH_PORT;
        }
    }
}
