package com.securityexpert.nexus.ui2.service.device;

import java.util.Map;
import java.util.Objects;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.ConfirmCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * {@code POST /devices/add-single} (WORKER.md "Routes"): derives {@code
 * transport_kind} from {@code vendor}, registers the {@code DRAFT} device
 * through {@link DeviceRegistrationService}, then admits the vendor's
 * enrollment-confirm capability (PO_DECISION_RECORD_2026_09_14B EC-J1)
 * through {@link JobAdmissionService} -- both in the one transaction {@link
 * #transactionBoundary} opens.
 *
 * <p>{@link DeviceRegistrationService#register} and {@link
 * JobAdmissionService#submit} each open their own {@code TransactionBoundary}
 * call internally, but every repository behind them shares this
 * application's one singleton {@link TransactionBoundary} (and so the one
 * underlying jOOQ {@code DSLContext}); jOOQ nests a {@code
 * transactionResult} call issued while another is already open on the same
 * thread/{@code Configuration} as a SAVEPOINT of the outer transaction, so
 * throwing out of {@link #runInTransaction} rolls the whole thing back --
 * the draft included. This is what keeps a refused admission from leaving
 * an orphan {@code DRAFT} row (WORKER.md risk: "two transactions... orphan
 * DRAFT rows").</p>
 */
public final class DeviceAddSingleService {

    public sealed interface Outcome {
        record Admitted(String deviceId, String jobId) implements Outcome {
        }

        record ValidationFailed(String reasonCode) implements Outcome {
        }

        record AdmissionRefused(String code, String reason) implements Outcome {
        }
    }

    /** DA-1/DA-2's closed vendor vocabulary, mapped to B1-4's {@code transport_kind} and the confirm capability it admits. */
    private record VendorMapping(String transportKind, String capabilityId) {
    }

    private static final Map<String, VendorMapping> VENDOR_MAPPINGS = Map.of(
            "check_point", new VendorMapping("ssh_exec", ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT),
            "palo_alto", new VendorMapping("pan_xml_api", ConfirmCapabilityIds.DEVICE_CONFIRM_PALO_ALTO));

    /** Unwinds {@link #runInTransaction} to roll back the whole outer transaction on a validation refusal. */
    private static final class ValidationFailedSignal extends RuntimeException {
        private final String reasonCode;

        ValidationFailedSignal(String reasonCode) {
            super(reasonCode, null, false, false);
            this.reasonCode = reasonCode;
        }
    }

    /** Unwinds {@link #runInTransaction} to roll back the whole outer transaction on an admission refusal. */
    private static final class AdmissionRefusedSignal extends RuntimeException {
        private final String code;
        private final String reason;

        AdmissionRefusedSignal(String code, String reason) {
            super(code, null, false, false);
            this.code = code;
            this.reason = reason;
        }
    }

    private final TransactionBoundary transactionBoundary;
    private final DeviceRegistrationService deviceRegistrationService;
    private final JobAdmissionService jobAdmissionService;

    public DeviceAddSingleService(TransactionBoundary transactionBoundary,
            DeviceRegistrationService deviceRegistrationService, JobAdmissionService jobAdmissionService) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.deviceRegistrationService =
                Objects.requireNonNull(deviceRegistrationService, "deviceRegistrationService");
        this.jobAdmissionService = Objects.requireNonNull(jobAdmissionService, "jobAdmissionService");
    }

    public Outcome addSingle(String actorFingerprint, String address, String vendor, String credentialReferenceId) {
        VendorMapping mapping = VENDOR_MAPPINGS.get(vendor);
        if (mapping == null) {
            return new Outcome.ValidationFailed(DeviceRegistrationService.REASON_VENDOR_HINT_INVALID);
        }

        try {
            return transactionBoundary.inTransaction(dsl -> runInTransaction(actorFingerprint, address, vendor,
                    credentialReferenceId, mapping));
        } catch (ValidationFailedSignal signal) {
            return new Outcome.ValidationFailed(signal.reasonCode);
        } catch (AdmissionRefusedSignal signal) {
            return new Outcome.AdmissionRefused(signal.code, signal.reason);
        }
    }

    private Outcome runInTransaction(String actorFingerprint, String address, String vendor,
            String credentialReferenceId, VendorMapping mapping) {
        DeviceRegistrationService.Outcome registration = deviceRegistrationService.register(actorFingerprint, vendor,
                mapping.transportKind(), address, credentialReferenceId, false);
        if (registration instanceof DeviceRegistrationService.Outcome.ValidationFailed failed) {
            throw new ValidationFailedSignal(failed.reasonCode());
        }
        DeviceRegistrationService.Outcome.Registered registered =
                (DeviceRegistrationService.Outcome.Registered) registration;

        AdmissionResult admission = jobAdmissionService.submit(mapping.capabilityId(), registered.deviceId(),
                registered.deviceId(), actorFingerprint, ActionRegistry.DEVICE_REGISTER);
        String jobId = switch (admission) {
            case AdmissionResult.Admitted admitted -> admitted.jobId();
            case AdmissionResult.Deduplicated deduplicated -> deduplicated.jobId();
            case AdmissionResult.Refused refused -> throw new AdmissionRefusedSignal(refused.code(), refused.reason());
        };
        return new Outcome.Admitted(registered.deviceId(), jobId);
    }
}
