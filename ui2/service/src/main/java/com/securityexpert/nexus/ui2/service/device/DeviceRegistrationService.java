package com.securityexpert.nexus.ui2.service.device;
// 14I MS-1

import java.util.Optional;
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

    /**
     * Contract §4 "Validated": the transports this movement's worker actually
     * serves (NXS-LOCAL-0158 -- both vendors in one worker process, Check
     * Point over {@code ssh_exec} and Palo Alto over {@code pan_xml_api}).
     */
    private static final Set<String> IMPLEMENTED_TRANSPORTS = Set.of("ssh_exec", "pan_xml_api", "https");

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
    /** 14I MS-1: the role is not one of the two values the database will accept. */
    public static final String REASON_ROLE_INVALID = "role_invalid";
    /** Vendors enrolled with role "appliance" (V64/V65). */
    static final java.util.Set<String> APPLIANCE_VENDORS = java.util.Set.of("infoblox", "radware");

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
    public Outcome register(String registeringActorFingerprint, String role, String vendorHint, String transportKind,
            String addressRef, String credentialReferenceId, boolean isTestTarget) {
        return register(registeringActorFingerprint, role, vendorHint, transportKind, addressRef, credentialReferenceId,
                isTestTarget, "manual_registration", Optional.empty(), Optional.empty(), Optional.empty(),
                ActionRegistry.DEVICE_REGISTER);
    }

    /**
     * 14F import §2 / IM-9: the discovery-import registration flow -- the
     * same "Validated" checks and the same single audited write {@link
     * #register(String, String, String, String, String, boolean)} performs,
     * with a non-{@code manual_registration} {@code registrationSource}
     * and the target modifiers (IM-1..IM-10) discovery already resolved,
     * carried onto the new draft's own row rather than a second write
     * against an existing one (DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md
     * §10: "without specifying which shape" -- this movement's shape is
     * one row per imported candidate, carrying its own modifiers, per
     * {@link com.securityexpert.nexus.ui2.persistence.device.DeviceDraft}'s
     * own field javadoc).
     */
    public Outcome register(String registeringActorFingerprint, String role, String vendorHint, String transportKind,
            String addressRef, String credentialReferenceId, boolean isTestTarget, String registrationSource,
            Optional<String> clusterMemberRef, Optional<String> virtualSystemRef, Optional<String> discoveryMatchKey,
            String actionId) {
        if (role == null || (!role.equals("gateway") && !role.equals("management_server") && !role.equals("appliance"))) {
            return new Outcome.ValidationFailed(REASON_ROLE_INVALID);
        }
        if (vendorHint == null || vendorHint.isBlank()) {
            return new Outcome.ValidationFailed(REASON_VENDOR_HINT_INVALID);
        }
        // V65: "appliance" is the role of the vendors reached over HTTPS (Infoblox, Radware) and only theirs; V67: a
        // Radware management server is a Cyber Controller.
        boolean applianceVendor = APPLIANCE_VENDORS.contains(vendorHint);
        boolean roleFits = applianceVendor
                ? role.equals("appliance") || ("radware".equals(vendorHint) && role.equals("management_server"))
                : !role.equals("appliance");
        if (!roleFits) {
            return new Outcome.ValidationFailed(REASON_ROLE_INVALID);
        }
        if (!IMPLEMENTED_TRANSPORTS.contains(transportKind)) {
            return new Outcome.ValidationFailed(REASON_UNSUPPORTED_TRANSPORT);
        }
        String effectiveAddress = addressRef != null ? addressRef.trim() : null;
        if (effectiveAddress == null || effectiveAddress.isBlank() || containsWhitespace(effectiveAddress)) {
            return new Outcome.ValidationFailed(REASON_ADDRESS_REF_INVALID);
        }
        if (credentialReferenceId == null || !credentialReferenceRepository.exists(credentialReferenceId)) {
            return new Outcome.ValidationFailed(REASON_CREDENTIAL_REFERENCE_NOT_FOUND);
        }

        // Opaque, application-generated identifiers -- never derived from
        // addressRef or vendorHint (identity law, contract §7/§8 test 9).
        String deviceId = OpaqueId.random().value();
        String endpointId = OpaqueId.random().value();

        DeviceDraft draft = new DeviceDraft(deviceId, role, vendorHint, registrationSource, isTestTarget,
                credentialReferenceId, endpointId, transportKind, effectiveAddress, clusterMemberRef, virtualSystemRef,
                discoveryMatchKey);
        deviceRepository.registerDraft(draft, registeringActorFingerprint, actionId);
        return new Outcome.Registered(deviceId, endpointId);
    }

    private static boolean containsWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }
}
