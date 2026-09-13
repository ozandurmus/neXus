package com.securityexpert.nexus.ui2.discovery.cp;

import java.util.Optional;

/**
 * Contract §6.4 field binding — the ONE isolated site where a role name is
 * associated with a concrete management-API field name (FB-1). Every rule
 * in this package (classification, host resolution, host linking, cluster
 * resolution) is written against the role names of {@link Role} only; none
 * of them, and no other production class, may reference a concrete field
 * name string.
 *
 * <p><b>FB-2/FB-3, discharged per the contract's FROZEN status block.</b>
 * The measurement of §2 was taken in a Product Owner session against a
 * live management server that no worker of this movement has. Every entry
 * below is therefore recorded {@link Status#UNVERIFIED}: a candidate field
 * name for a Product-Owner-run confirmation, never a measurement, and it
 * may not be cited as one. A role this movement cannot even propose a
 * candidate field for is recorded {@link Status#UNKNOWN} and carries no
 * field at all (FB-3) — none of the entries below currently need that
 * fallback, but the type supports it so a future, more cautious entry
 * never has to invent a field name it cannot justify.</p>
 */
public final class ManagementApiFieldBinding {

    /** The role names contract §§4 and 6 write their rules against. */
    public enum Role {
        PRODUCT_FLAG,
        VIRT_HOST_FLAG,
        VIRT_SYSTEM_FLAG,
        STABLE_IDENTIFIER,
        DISPLAY_NAME,
        OWN_ADDRESS,
        MANAGEMENT_ADDRESS,
        CLUSTER_REFERENCE_IDENTIFIER,
        CLUSTER_REFERENCE_DISPLAY_NAME,
        MODEL,
        SOFTWARE_VERSION,
        MANAGEMENT_PLANE_CONNECTION_STATE,

        /**
         * §T-2 domain enumeration: the key identifying one domain, not
         * covered by the original 12 roles because §2's measurement did not
         * enumerate domains. Added by the transport movement (FB-2).
         */
        DOMAIN_IDENTIFIER,

        /**
         * The management-plane session token a {@code login}-shaped command
         * returns and every subsequent command in the same run echoes back.
         * Not a role §4/§6 write a candidate-row rule against; it exists so
         * the adapter never contains this field-name literal outside this
         * class either (FB-2's "no other production class" is not qualified
         * to "no other candidate-row rule").
         */
        SESSION_IDENTIFIER,

        /** §7.4 connection table: the address column (CS-6a). */
        CONNECTION_TABLE_ADDRESS,

        /** §7.4 connection table: the port column, read only to derive CS-6b's channel port -- never hard-coded. */
        CONNECTION_TABLE_PORT,

        /** §7.4 connection table: the raw per-row channel-state column (CS-1, before CS-3/CS-6a reduction). */
        CONNECTION_TABLE_STATE
    }

    public enum Status {
        /** A candidate field name, pending Product-Owner-run confirmation against the live management server. */
        UNVERIFIED,
        /** No field name could be justified by the measured behaviour of §2; the field is not carried (FB-3). */
        UNKNOWN
    }

    private final Role role;
    private final String apiField;
    private final Status status;

    private ManagementApiFieldBinding(Role role, String apiField, Status status) {
        this.role = role;
        this.apiField = apiField;
        this.status = status;
    }

    private static final java.util.List<ManagementApiFieldBinding> ENTRIES = java.util.List.of(
            new ManagementApiFieldBinding(Role.PRODUCT_FLAG, "type", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.VIRT_HOST_FLAG, "chassis-role", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.VIRT_SYSTEM_FLAG, "hosted-instance-role", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.STABLE_IDENTIFIER, "uid", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.DISPLAY_NAME, "name", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.OWN_ADDRESS, "ipv4-address", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.MANAGEMENT_ADDRESS, "controlling-device-address", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.CLUSTER_REFERENCE_IDENTIFIER, "cluster-uid", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.CLUSTER_REFERENCE_DISPLAY_NAME, "cluster-name", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.MODEL, "hardware", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.SOFTWARE_VERSION, "version", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.MANAGEMENT_PLANE_CONNECTION_STATE, "sic-state", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.DOMAIN_IDENTIFIER, "uid", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.SESSION_IDENTIFIER, "sid", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.CONNECTION_TABLE_ADDRESS, "peer-ip", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.CONNECTION_TABLE_PORT, "peer-port", Status.UNVERIFIED),
            new ManagementApiFieldBinding(Role.CONNECTION_TABLE_STATE, "channel-state", Status.UNVERIFIED));

    public static ManagementApiFieldBinding forRole(Role role) {
        return ENTRIES.stream()
                .filter(e -> e.role == role)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no binding entry for role " + role));
    }

    public static java.util.List<ManagementApiFieldBinding> all() {
        return ENTRIES;
    }

    public Role role() {
        return role;
    }

    public Status status() {
        return status;
    }

    /** Empty when {@link #status()} is {@link Status#UNKNOWN} (FB-3: the field is not carried). */
    public Optional<String> apiField() {
        return status == Status.UNKNOWN ? Optional.empty() : Optional.of(apiField);
    }
}
