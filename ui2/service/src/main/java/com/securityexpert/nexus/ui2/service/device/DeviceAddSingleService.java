package com.securityexpert.nexus.ui2.service.device;
// 14I MS-1

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.ConfirmCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
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
            "palo_alto", new VendorMapping("pan_xml_api", ConfirmCapabilityIds.DEVICE_CONFIRM_PALO_ALTO),
            // V64: vendors reached over HTTPS (VENDOR_BACKUP_CONTRACTS_2026_09_22.md §1, §4)
            "infoblox", new VendorMapping("https", ConfirmCapabilityIds.DEVICE_CONFIRM_HTTPS),
            "radware", new VendorMapping("https", ConfirmCapabilityIds.DEVICE_CONFIRM_HTTPS),
            // PO 2026-09-25: a Symantec (Blue Coat) Management Center, over its REST API on 8082.
            "bluecoat", new VendorMapping("https", ConfirmCapabilityIds.DEVICE_CONFIRM_HTTPS),
            // PO 2026-09-25: Cisco ASA over SSH (CISCO_ASA_CONTRACT.md), role gateway.
            "cisco_asa", new VendorMapping("ssh_exec", ConfirmCapabilityIds.DEVICE_CONFIRM_CISCO_ASA));

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
    private final DeviceRepository deviceRepository;
    /** V64: a device's second secret (Radware's export passphrase), set in the same transaction as the device. */
    private com.securityexpert.nexus.ui2.persistence.device.DeviceSecretReferenceRepository secrets;

    /** Radware only: the backup is refused without the export passphrase, so the device is refused without it too. */
    static final String REASON_EXPORT_PASSPHRASE_REQUIRED = "export_passphrase_required";

    /** V77: the onboarding flow row, started in the same transaction as the device and its confirm job. */
    private com.securityexpert.nexus.ui2.persistence.device.DeviceOnboardingRepository onboarding =
            com.securityexpert.nexus.ui2.persistence.device.DeviceOnboardingRepository.NONE;

    public DeviceAddSingleService withOnboarding(com.securityexpert.nexus.ui2.persistence.device.DeviceOnboardingRepository onboarding) {
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        return this;
    }

    public DeviceAddSingleService withSecrets(com.securityexpert.nexus.ui2.persistence.device.DeviceSecretReferenceRepository secrets) {
        this.secrets = secrets;
        return this;
    }

    public DeviceAddSingleService(TransactionBoundary transactionBoundary,
            DeviceRegistrationService deviceRegistrationService, JobAdmissionService jobAdmissionService,
            DeviceRepository deviceRepository) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.deviceRegistrationService =
                Objects.requireNonNull(deviceRegistrationService, "deviceRegistrationService");
        this.jobAdmissionService = Objects.requireNonNull(jobAdmissionService, "jobAdmissionService");
        this.deviceRepository = deviceRepository;
    }

    public DeviceAddSingleService(TransactionBoundary transactionBoundary,
            DeviceRegistrationService deviceRegistrationService, JobAdmissionService jobAdmissionService) {
        this(transactionBoundary, deviceRegistrationService, jobAdmissionService, null);
    }

    public Outcome retryConfirm(String deviceId, String actorFingerprint) {
        if (deviceRepository == null) {
            throw new IllegalStateException("deviceRepository must be provided to retryConfirm");
        }
        Optional<DeviceRecord> deviceOpt = deviceRepository.find(deviceId);
        if (deviceOpt.isEmpty()) {
            return new Outcome.ValidationFailed("DEVICE_NOT_FOUND");
        }
        DeviceRecord device = deviceOpt.get();
        if (device.enrollmentState() != com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState.DRAFT) {
            return new Outcome.ValidationFailed("DEVICE_NOT_DRAFT");
        }
        VendorMapping mapping = VENDOR_MAPPINGS.get(device.vendorHint());
        if (mapping == null) {
            return new Outcome.ValidationFailed(DeviceRegistrationService.REASON_VENDOR_HINT_INVALID);
        }
        String idempotencyKey = deviceId + ":confirm:" + UUID.randomUUID();
        AdmissionResult admission = jobAdmissionService.submit(mapping.capabilityId(), deviceId, idempotencyKey,
                actorFingerprint, ActionRegistry.DEVICE_REGISTER);
        Outcome outcome = switch (admission) {
            case AdmissionResult.Admitted admitted -> new Outcome.Admitted(deviceId, admitted.jobId());
            case AdmissionResult.Deduplicated deduplicated -> new Outcome.Admitted(deviceId, deduplicated.jobId());
            case AdmissionResult.Refused refused -> new Outcome.AdmissionRefused(refused.code(), refused.reason());
        };
        // A retried confirm restarts the device's onboarding flow at the identity step (V77).
        if (outcome instanceof Outcome.Admitted admitted) {
            onboarding.find(deviceId).ifPresent(flow -> onboarding.start(deviceId, flow.source(), admitted.jobId(),
                    actorFingerprint, ActionRegistry.DEVICE_REGISTER));
        }
        return outcome;
    }

    public Outcome addSingle(String actorFingerprint, String role, String address, String vendor, String credentialReferenceId) {
        return addSingle(actorFingerprint, role, address, vendor, credentialReferenceId, Optional.empty());
    }

    /**
     * One transaction for the device, its export passphrase and its confirm job (PO, 2026-09-24): a refusal at any
     * step leaves no device behind -- a half-added device with no passphrase was the alternative.
     */
    public Outcome addSingle(String actorFingerprint, String role, String address, String vendor, String credentialReferenceId,
            Optional<String> exportPassphraseReferenceId) {
        VendorMapping mapping = VENDOR_MAPPINGS.get(vendor);
        if (mapping == null) {
            return new Outcome.ValidationFailed(DeviceRegistrationService.REASON_VENDOR_HINT_INVALID);
        }
        Optional<String> passphrase = exportPassphraseReferenceId.filter(s -> !s.isBlank());
        // A DefensePro carries the passphrase that encrypts its keys; a Cyber Controller (management server) does not.
        boolean defensePro = "radware".equals(vendor) && "appliance".equals(role);
        if (defensePro && passphrase.isEmpty()) {
            return new Outcome.ValidationFailed(REASON_EXPORT_PASSPHRASE_REQUIRED);
        }
        if (passphrase.isPresent() && (!defensePro || secrets == null)) {
            return new Outcome.ValidationFailed(REASON_EXPORT_PASSPHRASE_REQUIRED);
        }

        try {
            return transactionBoundary.inTransaction(dsl -> runInTransaction(actorFingerprint, role, address, vendor,
                    credentialReferenceId, mapping, "manual_registration", Optional.empty(), Optional.empty(),
                    Optional.empty(), ActionRegistry.DEVICE_REGISTER, passphrase));
        } catch (ValidationFailedSignal signal) {
            return new Outcome.ValidationFailed(signal.reasonCode);
        } catch (AdmissionRefusedSignal signal) {
            return new Outcome.AdmissionRefused(signal.code, signal.reason);
        }
    }

    /**
     * 14F import §2: "the same register-DRAFT-then-admit-confirm step
     * {@code POST /devices/add-single} uses" -- {@link DiscoveryRunService}'s
     * own per-candidate import call, one call (and one transaction) per
     * selected candidate, so a refusal on one candidate never rolls back a
     * sibling candidate already imported in the same request (AC-3:
     * "refused per row, not per request").
     */
    public Outcome addFromDiscoveryImport(String actorFingerprint, String role, String address, String vendor,
            String credentialReferenceId, Optional<String> clusterMemberRef, Optional<String> virtualSystemRef,
            Optional<String> discoveryMatchKey, String actionId) {
        return addFromDiscoveryImport(actorFingerprint, role, address, vendor, credentialReferenceId, clusterMemberRef,
                virtualSystemRef, discoveryMatchKey, actionId, Optional.empty());
    }

    /** As above; a DefensePro import carries its export passphrase, set in the same transaction (or no device). */
    public Outcome addFromDiscoveryImport(String actorFingerprint, String role, String address, String vendor,
            String credentialReferenceId, Optional<String> clusterMemberRef, Optional<String> virtualSystemRef,
            Optional<String> discoveryMatchKey, String actionId, Optional<String> exportPassphraseReferenceId) {
        VendorMapping mapping = VENDOR_MAPPINGS.get(vendor);
        if (mapping == null) {
            return new Outcome.ValidationFailed(DeviceRegistrationService.REASON_VENDOR_HINT_INVALID);
        }
        Optional<String> passphrase = exportPassphraseReferenceId.filter(v -> !v.isBlank());
        boolean defensePro = "radware".equals(vendor) && "appliance".equals(role);
        if (defensePro && passphrase.isEmpty()) {
            return new Outcome.ValidationFailed(REASON_EXPORT_PASSPHRASE_REQUIRED);
        }
        if (passphrase.isPresent() && (!defensePro || secrets == null)) {
            return new Outcome.ValidationFailed(REASON_EXPORT_PASSPHRASE_REQUIRED);
        }

        try {
            return transactionBoundary.inTransaction(dsl -> runInTransaction(actorFingerprint, role, address, vendor,
                    credentialReferenceId, mapping, "discovery_import", clusterMemberRef, virtualSystemRef,
                    discoveryMatchKey, actionId, passphrase));
        } catch (ValidationFailedSignal signal) {
            return new Outcome.ValidationFailed(signal.reasonCode);
        } catch (AdmissionRefusedSignal signal) {
            return new Outcome.AdmissionRefused(signal.code, signal.reason);
        }
    }

    private Outcome runInTransaction(String actorFingerprint, String role, String address, String vendor,
            String credentialReferenceId, VendorMapping mapping, String registrationSource,
            Optional<String> clusterMemberRef, Optional<String> virtualSystemRef, Optional<String> discoveryMatchKey,
            String actionId, Optional<String> exportPassphraseReferenceId) {
        // PO rule (2026-09-22): never a duplicate entry -- one address, one device.
        Optional.ofNullable(deviceRepository).flatMap(repository -> repository.findDeviceIdByEndpointAddress(address)).ifPresent(existing -> {
            throw new AdmissionRefusedSignal("DUPLICATE_ADDRESS",
                    "a device already exists at this address (device " + existing + "); delete it first if it must be re-added");
        });
        DeviceRegistrationService.Outcome registration = deviceRegistrationService.register(actorFingerprint, role, vendor,
                mapping.transportKind(), address, credentialReferenceId, false, registrationSource, clusterMemberRef,
                virtualSystemRef, discoveryMatchKey, actionId);
        if (registration instanceof DeviceRegistrationService.Outcome.ValidationFailed failed) {
            throw new ValidationFailedSignal(failed.reasonCode());
        }
        DeviceRegistrationService.Outcome.Registered registered =
                (DeviceRegistrationService.Outcome.Registered) registration;
        if (exportPassphraseReferenceId.isPresent()) {
            secrets.set(registered.deviceId(), com.securityexpert.nexus.ui2.persistence.device.DeviceSecretReferenceRepository.EXPORT_PASSPHRASE,
                    exportPassphraseReferenceId.get(), actorFingerprint, actionId);
        }

        AdmissionResult admission = jobAdmissionService.submit(mapping.capabilityId(), registered.deviceId(),
                registered.deviceId(), actorFingerprint, actionId);
        String jobId = switch (admission) {
            case AdmissionResult.Admitted admitted -> admitted.jobId();
            case AdmissionResult.Deduplicated deduplicated -> deduplicated.jobId();
            case AdmissionResult.Refused refused -> throw new AdmissionRefusedSignal(refused.code(), refused.reason());
        };
        onboarding.start(registered.deviceId(), registrationSource, jobId, actorFingerprint, actionId);
        return new Outcome.Admitted(registered.deviceId(), jobId);
    }
}
