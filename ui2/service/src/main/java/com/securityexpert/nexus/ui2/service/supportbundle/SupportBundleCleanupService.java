package com.securityexpert.nexus.ui2.service.supportbundle;

import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore.CleanupTarget;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * "Remove leftover work directory" (PO approved 2026-11-16). A Check Point bundle run whose recorded work directory was not
 * cleaned (an outcome unknown, a failed removal) can have exactly that directory removed by an administrator who confirms.
 * It admits one job whose only device command is the already approved C20 literal for the directory name recorded in
 * {@code work_dir_path}; no operator text reaches a command. Recorded and audited on the request.
 */
public final class SupportBundleCleanupService {

    private final DeviceRepository devices;
    private final JobAdmissionService admission;
    private final JooqSupportBundleUploadStore store;

    public SupportBundleCleanupService(DeviceRepository devices, JobAdmissionService admission, JooqSupportBundleUploadStore store) {
        this.devices = devices; this.admission = admission; this.store = store;
    }

    public SupportBundleService.Outcome cleanup(UUID requestId, String actor, String reason, boolean confirmed) {
        if (!confirmed) return new SupportBundleService.Outcome.Refused(400, "CONFIRMATION_REQUIRED",
                "Confirm that the leftover work directory may be removed from the device");
        if (reason == null || reason.strip().length() < SupportBundleService.MIN_REASON_LENGTH
                || reason.strip().length() > SupportBundleService.MAX_REASON_LENGTH) {
            return new SupportBundleService.Outcome.Refused(400, "REASON_REQUIRED", "A reason of 8 to 500 characters is required");
        }
        Optional<CleanupTarget> target = store.cleanupTarget(requestId);
        if (target.isEmpty()) {
            return new SupportBundleService.Outcome.Refused(409, "CLEANUP_NOT_ELIGIBLE",
                    "Only a finished request with a recorded work directory that was not cleaned, and no cleanup under way, qualifies");
        }
        Optional<DeviceRecord> device = devices.find(target.get().deviceId());
        Optional<SupportBundleService.VendorPlan> plan = device.isEmpty() ? Optional.empty()
                : SupportBundleService.planFor(device.get().vendorHint(), device.get().role());
        if (plan.isEmpty() || !"check_point".equals(target.get().vendor())) {
            return new SupportBundleService.Outcome.Refused(409, "CLEANUP_NOT_AVAILABLE", "The device is not a Check Point gateway or management server in the inventory");
        }
        if (!store.markCleanupRequested(requestId, actor, reason.strip())) {
            return new SupportBundleService.Outcome.Refused(409, "CLEANUP_NOT_ELIGIBLE", "The cleanup was requested meanwhile or is no longer needed");
        }
        AdmissionResult result;
        try {
            result = admission.submit(plan.get().capability(), target.get().deviceId(),
                    "support-bundle-cleanup:" + requestId + ":" + UUID.randomUUID(), actor,
                    ActionRegistry.SUPPORT_BUNDLE_CLEANUP_WORKDIR, ActionClass.CLASS_1_RECOVERY_WRITE);
        } catch (RuntimeException refused) {
            store.failCleanupRequest(requestId, actor, "ADMISSION_REFUSED");
            throw refused;
        }
        return switch (result) {
            case AdmissionResult.Admitted admitted -> accepted(requestId, admitted.jobId(), actor);
            case AdmissionResult.Deduplicated deduplicated -> accepted(requestId, deduplicated.jobId(), actor);
            case AdmissionResult.Refused refused -> {
                store.failCleanupRequest(requestId, actor, "ADMISSION_REFUSED");
                yield new SupportBundleService.Outcome.Refused(409, refused.code(), refused.reason());
            }
        };
    }

    private SupportBundleService.Outcome accepted(UUID requestId, String jobId, String actor) {
        store.attachCleanupJob(requestId, jobId, actor);
        return new SupportBundleService.Outcome.Accepted(requestId, jobId, false);
    }
}
