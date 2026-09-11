package com.securityexpert.nexus.ui2.service.device;

import java.util.Set;

import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * The manual registration flow (B1-4b contract §4). The role check itself
 * ({@code role:onboarding_admin}, C3 §4.1) already ran in {@link
 * com.securityexpert.nexus.ui2.service.security.GateChain}'s {@code E4}
 * before this class is ever reached (the action id this class uses,
 * {@link ActionRegistry#DEVICE_REGISTER}, is seeded there requiring that
 * exact token, and no other) -- this class performs only §4's own
 * "Validated" checks and the write, never a second role check.
 *
 * <p>Writes exactly two rows ({@code devices}, {@code endpoints}), both in
 * {@link DeviceRepository#registerDraft}'s one audited transaction, and
 * touches no other table -- in particular nothing {@code C4}/{@code C7}
 * would consult for write eligibility (contract §4 "Write-capability
 * limit", AC-6, test 6): registration grants read collection only.</p>
 */
public final class DeviceRegistrationService {

    /** Contract §4 "Validated": the only transport B1-4 implements at B1 scope. */
    private static final Set<String> IMPLEMENTED_TRANSPORTS = Set.of("ssh_exec");

    public sealed interface Outcome {
        record Registered(String deviceId, String endpointId) implements Outcome {
        }

        record ValidationFailed(String reasonCode) implements Outcome {
        }
    }

    public static final String REASON_UNSUPPORTED_TRANSPORT = "unsupported_transport_kind";
    public static final String REASON_CREDENTIAL_REFERENCE_NOT_FOUND = "credential_reference_not_found";
    public static final String REASON_ADDRESS_REF_INVALID = "address_ref_invalid";
    public static final String REASON_VENDOR_HINT_INVALID = "vendor_hint_invalid";

    private final DeviceRepository deviceRepository;
    private final CredentialReferenceRepository credentialReferenceRepository;

    public DeviceRegistrationService(DeviceRepository deviceRepository,
            CredentialReferenceRepository credentialReferenceRepository) {
        this.deviceRepository = deviceRepository;
        this.credentialReferenceRepository = credentialReferenceRepository;
    }

    /**
     * @param registeringActorFingerprint the registering session's opaque
     *                                     actor fingerprint (contract §4
     *                                     "Audited")
     */
    public Outcome register(String registeringActorFingerprint, String vendorHint, String transportKind,
            String addressRef, String credentialReferenceId, boolean isTestTarget) {
        if (vendorHint == null || vendorHint.isBlank()) {
            return new Outcome.ValidationFailed(REASON_VENDOR_HINT_INVALID);
        }
        if (!IMPLEMENTED_TRANSPORTS.contains(transportKind)) {
            return new Outcome.ValidationFailed(REASON_UNSUPPORTED_TRANSPORT);
        }
        if (addressRef == null || addressRef.isBlank() || containsWhitespace(addressRef)) {
            return new Outcome.ValidationFailed(REASON_ADDRESS_REF_INVALID);
        }
        if (credentialReferenceId == null || !credentialReferenceRepository.exists(credentialReferenceId)) {
            return new Outcome.ValidationFailed(REASON_CREDENTIAL_REFERENCE_NOT_FOUND);
        }

        // Opaque, application-generated identifiers -- never derived from
        // addressRef or vendorHint (identity law, contract §7/§8 test 9).
        String deviceId = OpaqueId.random().value();
        String endpointId = OpaqueId.random().value();

        DeviceDraft draft = new DeviceDraft(deviceId, vendorHint, "manual_registration", isTestTarget,
                credentialReferenceId, endpointId, transportKind, addressRef);
        deviceRepository.registerDraft(draft, registeringActorFingerprint, ActionRegistry.DEVICE_REGISTER);
        return new Outcome.Registered(deviceId, endpointId);
    }

    private static boolean containsWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }
}
