package com.securityexpert.nexus.ui2.worker.backup.https;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;

/**
 * The inventory job for vendors reached over HTTPS (PO 2026-09-25: "backup is backup, inventory is inventory"):
 * claim-time eligibility, one read attempt, one {@link InventoryRun} with the contexts, member facts and names the
 * read produced, and the device's observed facts refreshed from the identity the read presented.
 */
public final class HttpsInventoryJobExecutor {

    private static final System.Logger LOG = System.getLogger(HttpsInventoryJobExecutor.class.getName());
    private static final String ACTOR = "system:worker";

    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceEnrollmentReadPort enrollment;
    private final DeviceRepository devices;
    private final DeviceInventoryRepository inventory;
    private final HttpsVendorExecutor executor;

    public HttpsInventoryJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceEnrollmentReadPort enrollment,
            DeviceRepository devices, DeviceInventoryRepository inventory, HttpsVendorExecutor executor) {
        this.leases = Objects.requireNonNull(leases);
        this.attempts = Objects.requireNonNull(attempts);
        this.enrollment = Objects.requireNonNull(enrollment);
        this.devices = Objects.requireNonNull(devices);
        this.inventory = Objects.requireNonNull(inventory);
        this.executor = Objects.requireNonNull(executor);
    }

    public JobOutcome execute(String jobId, long leaseEpoch, String deviceId, String vendor, Target target, String credentialRef) {
        long start = System.currentTimeMillis();
        var e = enrollment.findEnrollment(deviceId);
        if (e.isEmpty() || !e.get().permitsReadCollection()) {
            leases.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.REJECTED, ACTOR, "inventory_claim_time_device_check",
                    "DEVICE_NOT_ELIGIBLE_AT_CLAIM_TIME");
            return new JobOutcome.Rejected("DEVICE_NOT_ELIGIBLE_AT_CLAIM_TIME");
        }
        if (!leases.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "inventory_claim_to_executing")) {
            return new JobOutcome.ZombieStopped();
        }
        String attemptId = attempts.insertPreContact(jobId, leaseEpoch, 0, "INVENTORY_COLLECT_READ",
                com.securityexpert.nexus.ui2.platform.ActionClass.CLASS_0_READ.id(), 1);
        if (attemptId == null) {
            return new JobOutcome.ZombieStopped();
        }
        HttpsVendorExecutor.InventoryOutcome outcome;
        if ("radware".equals(vendor)) {
            // A DefensePro is inventoried through the Cyber Controller that lists it (its own REST reads are not gated).
            outcome = new HttpsVendorExecutor.InventoryOutcome.Failed("no enrolled Cyber Controller lists this DefensePro");
            for (CyberControllers.Ref cc : CyberControllers.enrolled(devices)) {
                outcome = executor.inventoryDefenseProViaCyberController(cc.target(), cc.credentialRef(), target.host());
                if (outcome instanceof HttpsVendorExecutor.InventoryOutcome.Completed) {
                    break;
                }
            }
        } else if ("bluecoat_proxysg".equals(vendor)) {
            // A ProxySG is read through the enrolled Management Center that lists it (V82).
            outcome = new HttpsVendorExecutor.InventoryOutcome.Failed("no enrolled Management Center lists this ProxySG");
            for (CyberControllers.Ref mc : CyberControllers.managers(devices, "bluecoat")) {
                var viaMc = executor.inventoryProxySgViaManagementCenter(mc.target(), mc.credentialRef(), target.host());
                if (viaMc.isPresent()) {
                    outcome = viaMc.get();
                    break;
                }
            }
        } else {
            outcome = executor.inventory(vendor, target, credentialRef);
        }
        boolean completed = outcome instanceof HttpsVendorExecutor.InventoryOutcome.Completed;
        if (!attempts.writeOutcome(attemptId, leaseEpoch, completed ? "MATCHED" : "EXPECTATION_UNMET", null, 0L, 0L,
                outcome.getClass().getSimpleName())) {
            return new JobOutcome.ZombieStopped();
        }
        long ms = System.currentTimeMillis() - start;
        if (!(outcome instanceof HttpsVendorExecutor.InventoryOutcome.Completed done)) {
            String reason = switch (outcome) {
                case HttpsVendorExecutor.InventoryOutcome.AuthenticationFailed a -> "authentication_failed: " + a.reason();
                case HttpsVendorExecutor.InventoryOutcome.Failed f -> "connect_failed: " + f.reason();
                default -> "unknown";
            } + " (after " + ms + "ms)";
            LOG.log(System.Logger.Level.WARNING, "[JOB_FAILED] HTTPS inventory job {0} ({1}): {2}", jobId, vendor, reason);
            leases.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR, "inventory_failed", reason);
            return new JobOutcome.Failed(reason);
        }
        try {
            inventory.recordRun(new InventoryRun(UUID.randomUUID().toString(), deviceId, jobId, Instant.now(), done.contexts().size(),
                    done.contexts(), List.of(), done.virtualSystems(), done.members()), ACTOR, "inventory_completed");
        } catch (RuntimeException writeFailed) {
            String reason = "inventory_run_write_failed: " + writeFailed.getMessage() + " (after " + ms + "ms)";
            leases.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR, "inventory_failed", reason);
            return new JobOutcome.Failed(reason);
        }
        // PO 2026-09-25: observed facts follow every read.
        try {
            devices.refreshObservedFacts(deviceId, done.identity().name(), done.identity().model(), done.identity().version(), ACTOR,
                    "inventory_identity_refresh");
        } catch (RuntimeException refreshFailed) {
            LOG.log(System.Logger.Level.WARNING, "[OBSERVED_FACTS_REFRESH_FAILED] HTTPS inventory job {0}: {1}", jobId, refreshFailed.getMessage());
        }
        LOG.log(System.Logger.Level.INFO, "[JOB_COMPLETED] HTTPS inventory job {0} ({1}) in {2}ms: {3} context(s), {4} member(s)",
                jobId, vendor, ms, done.contexts().size(), done.members().size());
        leases.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.COMPLETED, ACTOR, "inventory_completed", "completed in " + ms + "ms");
        return new JobOutcome.Completed();
    }
}
