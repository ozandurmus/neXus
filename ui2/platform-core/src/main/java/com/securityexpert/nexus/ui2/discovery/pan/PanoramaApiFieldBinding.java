package com.securityexpert.nexus.ui2.discovery.pan;

import java.util.List;
import java.util.Optional;

/**
 * The ONE isolated field-binding site for this package (mirrors cp's
 * {@code ManagementApiFieldBinding}): the only place a role name is
 * associated with a concrete Panorama response element path. Every rule in
 * this package (identity, virtual-system resolution, HA pairing, liveness
 * vocabulary) is written against the role names of {@link Role} only; no
 * other production class in this package may reference a concrete element
 * name string ({@code ManagementApiFieldBindingTest}'s isolation check has
 * a direct analogue for this package).
 *
 * <p>Every entry below is {@link Status#UNVERIFIED}. This movement has no
 * live Panorama session (§1: no transport); §11 check 14 is what would
 * confirm an entry against a live response, and it is unrun at the time of
 * writing. A role this movement cannot even propose a candidate element
 * path for is {@link Status#UNKNOWN} and carries no path at all — none of
 * the roles below currently need that fallback.</p>
 */
public final class PanoramaApiFieldBinding {

    /** The role names this package's rules are written against — §4 through §9. */
    public enum Role {
        STABLE_IDENTIFIER,
        DISPLAY_NAME,
        DEVICE_TYPE_MARKER,
        OWN_IPV4_ADDRESS,
        OWN_IPV6_ADDRESS,
        PEER_SERIAL_REFERENCE,
        CONNECTION_STATE,
        CONNECTION_TIMESTAMP,
        CERTIFICATE_STATUS,
        CERTIFICATE_EXPIRATION,
        VIRTUAL_SYSTEM_STABLE_IDENTIFIER,
        VIRTUAL_SYSTEM_DISPLAY_NAME,
        VIRTUAL_SYSTEM_SHARED_POLICY_ONE,
        VIRTUAL_SYSTEM_SHARED_POLICY_TWO,
        VIRTUAL_SYSTEM_SHARED_POLICY_THREE,

        /**
         * The key-generation response's key element (T-1). Not a role §4
         * through §9 write a candidate-row rule against; it exists so the
         * transport movement never contains this element-name literal
         * outside this class either (FB-2's "no other production class" is
         * not qualified to "no other candidate-row rule"). Added by the
         * transport movement.
         */
        KEY_GENERATION_RESPONSE_KEY,

        /**
         * The enumeration response's device-entry container: where the
         * repeated per-device entries live, relative to the response root.
         * Added by the transport movement (FB-2) -- the original 15 roles
         * covered only fields *within* one entry, not where to find the
         * entries themselves.
         */
        DEVICE_ENTRY_CONTAINER,

        /**
         * The nested virtual-system-entry container within one device
         * entry (VS-1). Added by the transport movement (FB-2) for the same
         * reason as {@link #DEVICE_ENTRY_CONTAINER}.
         */
        VIRTUAL_SYSTEM_ENTRY_CONTAINER
    }

    public enum Status {
        /** A candidate element path, pending Product-Owner-run confirmation against a live Panorama response (check 14). */
        UNVERIFIED,
        /** No element path could be justified by the measurement transcribed into the contract; the role carries no path (FB-3 analogue). */
        UNKNOWN
    }

    private final Role role;
    private final String apiFieldPath;
    private final Status status;

    private PanoramaApiFieldBinding(Role role, String apiFieldPath, Status status) {
        this.role = role;
        this.apiFieldPath = apiFieldPath;
        this.status = status;
    }

    private static final List<PanoramaApiFieldBinding> ENTRIES = List.of(
            new PanoramaApiFieldBinding(Role.STABLE_IDENTIFIER, "serial", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.DISPLAY_NAME, "hostname", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.DEVICE_TYPE_MARKER, "type", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.OWN_IPV4_ADDRESS, "ip-address", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.OWN_IPV6_ADDRESS, "ipv6-address", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.PEER_SERIAL_REFERENCE, "ha/peer/serial", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.CONNECTION_STATE, "connected", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.CONNECTION_TIMESTAMP, "last-connect-time", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.CERTIFICATE_STATUS, "certificate-status", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.CERTIFICATE_EXPIRATION, "certificate-expiry", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.VIRTUAL_SYSTEM_STABLE_IDENTIFIER, "vsys/entry/uniqid", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.VIRTUAL_SYSTEM_DISPLAY_NAME, "vsys/entry/display-name", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.VIRTUAL_SYSTEM_SHARED_POLICY_ONE, "vsys/entry/shared-policy-status", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.VIRTUAL_SYSTEM_SHARED_POLICY_TWO, "vsys/entry/shared-policy-md5sum", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.VIRTUAL_SYSTEM_SHARED_POLICY_THREE, "vsys/entry/shared-policy-version", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.KEY_GENERATION_RESPONSE_KEY, "response/result/key", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.DEVICE_ENTRY_CONTAINER, "response/result/devices/entry", Status.UNVERIFIED),
            new PanoramaApiFieldBinding(Role.VIRTUAL_SYSTEM_ENTRY_CONTAINER, "vsys/entry", Status.UNVERIFIED));

    public static PanoramaApiFieldBinding forRole(Role role) {
        return ENTRIES.stream()
                .filter(e -> e.role == role)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no binding entry for role " + role));
    }

    public static List<PanoramaApiFieldBinding> all() {
        return ENTRIES;
    }

    public Role role() {
        return role;
    }

    public Status status() {
        return status;
    }

    /** Empty when {@link #status()} is {@link Status#UNKNOWN} — the role carries no path. */
    public Optional<String> apiFieldPath() {
        return status == Status.UNKNOWN ? Optional.empty() : Optional.of(apiFieldPath);
    }
}
