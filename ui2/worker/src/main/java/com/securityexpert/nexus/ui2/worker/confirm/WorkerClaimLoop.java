package com.securityexpert.nexus.ui2.worker.confirm;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.admission.ConfirmCapabilityIds;
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
            InventoryCapabilityIds.CP_INVENTORY_COLLECT, InventoryCapabilityIds.PAN_INVENTORY_COLLECT);
    private static final int DEFAULT_SSH_PORT = 22;

    private final JobLeaseRepository leaseRepository;
    private final JobRecordDao jobRecordDao;
    private final DeviceRepository deviceRepository;
    private final ConfirmJobExecutor confirmJobExecutor;
    private final InventoryJobExecutor inventoryJobExecutor;
    private final String workerId;
    private final Duration leaseDuration;
    private final String checkPointTrustRuleRef;
    private final String paloAltoTrustRuleRef;

    /** WORKER.md "one worker process serves both vendors" (0159): the same claim loop now also serves both job kinds. */
    public WorkerClaimLoop(JobLeaseRepository leaseRepository, JobRecordDao jobRecordDao,
            DeviceRepository deviceRepository, ConfirmJobExecutor confirmJobExecutor,
            InventoryJobExecutor inventoryJobExecutor, String workerId, Duration leaseDuration,
            String checkPointTrustRuleRef, String paloAltoTrustRuleRef) {
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.jobRecordDao = Objects.requireNonNull(jobRecordDao, "jobRecordDao");
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.confirmJobExecutor = Objects.requireNonNull(confirmJobExecutor, "confirmJobExecutor");
        this.inventoryJobExecutor = Objects.requireNonNull(inventoryJobExecutor, "inventoryJobExecutor");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.checkPointTrustRuleRef = Objects.requireNonNull(checkPointTrustRuleRef, "checkPointTrustRuleRef");
        this.paloAltoTrustRuleRef = Objects.requireNonNull(paloAltoTrustRuleRef, "paloAltoTrustRuleRef");
    }

    /** Runs until the thread is interrupted, sleeping {@code pollInterval} whenever the queue was empty. */
    public void runUntilInterrupted(Duration pollInterval) {
        while (!Thread.currentThread().isInterrupted()) {
            boolean claimed = claimAndExecuteOnce();
            if (!claimed) {
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
        ClaimedJob claimed = claim.get();
        JobRow job = jobRecordDao.find(claimed.jobId())
                .orElseThrow(() -> new IllegalStateException("claimed job has no row: " + claimed.jobId()));
        DeviceRecord device = deviceRepository.find(job.targetDeviceId())
                .orElseThrow(() -> new IllegalStateException("claimed job targets an unknown device: " + job.targetDeviceId()));
        EndpointRecord endpoint = deviceRepository.findEndpointByDeviceId(job.targetDeviceId())
                .orElseThrow(() -> new IllegalStateException("claimed job's device has no endpoint: " + job.targetDeviceId()));

        if (InventoryCapabilityIds.isInventoryCapability(job.capabilityId())) {
            InventoryRequest inventoryRequest = buildInventoryRequest(job.capabilityId(), endpoint.endpointId(),
                    endpoint.addressRef(), device.credentialReferenceId());
            inventoryJobExecutor.execute(claimed.jobId(), claimed.leaseEpoch(), job.targetDeviceId(), inventoryRequest,
                    false);
            return true;
        }

        ConfirmRequest primaryRequest = buildRequest(job.capabilityId(), endpoint.endpointId(), endpoint.addressRef(),
                device.credentialReferenceId());
        var peerRequestFactory = peerRequestFactoryFor(job.capabilityId(), device.credentialReferenceId());

        confirmJobExecutor.execute(claimed.jobId(), claimed.leaseEpoch(), job.targetDeviceId(), primaryRequest,
                peerRequestFactory, false);
        return true;
    }

    private InventoryRequest buildInventoryRequest(String capabilityId, String endpointId, String addressRef,
            String credentialRef) {
        if (InventoryCapabilityIds.CP_INVENTORY_COLLECT.equals(capabilityId)) {
            return InventoryRequest.checkPoint(new ConnectionTarget(endpointId, hostOf(addressRef), portOf(addressRef)),
                    credentialRef, checkPointTrustRuleRef);
        }
        if (InventoryCapabilityIds.PAN_INVENTORY_COLLECT.equals(capabilityId)) {
            return InventoryRequest.paloAlto(new ApiTarget(endpointId, addressRef), credentialRef);
        }
        throw new IllegalStateException("claimed job for an inventory capability the worker does not recognize: "
                + capabilityId);
    }

    private PeerFollowResolver.ConfirmRequestFactory peerRequestFactoryFor(String capabilityId, String credentialRef) {
        return managementAddress -> buildRequest(capabilityId, "peer", managementAddress, credentialRef);
    }

    private ConfirmRequest buildRequest(String capabilityId, String endpointId, String addressRef,
            String credentialRef) {
        if (ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT.equals(capabilityId)) {
            return ConfirmRequest.checkPoint(new ConnectionTarget(endpointId, hostOf(addressRef), portOf(addressRef)),
                    credentialRef, checkPointTrustRuleRef);
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
