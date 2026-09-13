package com.securityexpert.nexus.ui2.discovery.cp;

import java.util.List;
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
 * These entries were rebound to the {@code cpmiquerybin object} tree format
 * the Product Owner measured against a live multi-domain management server
 * (record {@code docs/design/
 * CP_DISCOVERY_MEASURED_METHOD_AND_COMMAND_HANDOVER_2026_09_13.md}, DRAFT --
 * evidence, not authority). Every entry below is still {@link
 * Status#UNVERIFIED}: a candidate field name for a Product-Owner-run
 * confirmation, never itself a measurement, and it may not be cited as one
 * -- record §10's closing line: "every entry stays UNVERIFIED until the
 * first live run of the aligned transport reports its counts". A nested
 * field is written as {@code "block/leaf"} ({@link #apiField()}); the
 * adapter splits on the one {@code '/'} to read the named block, then the
 * named leaf inside it -- never by position.</p>
 *
 * <p><b>Per-object-type scope (record §10's field-binding consequence).</b>
 * {@link Role#VIRT_HOST_FLAG} and {@link Role#VIRT_SYSTEM_FLAG} bind to a
 * DIFFERENT measured attribute per {@link ObjectType}, so each carries three
 * entries here, looked up with {@link #forRole(Role, ObjectType)}. Every
 * other role carries exactly one, unscoped, entry, looked up with {@link
 * #forRole(Role)}. Whether a role with three scoped entries still satisfies
 * FB-2's "exactly one field" is left to the contract's freeze owner; this
 * class only records the measured shape and does not amend the contract.</p>
 */
public final class ManagementApiFieldBinding {

    /** The role names contract §§4 and 6 write their rules against. */
    public enum Role {
        PRODUCT_FLAG,

        /** Scoped per {@link ObjectType} — see class javadoc. */
        VIRT_HOST_FLAG,

        /** Scoped per {@link ObjectType} — see class javadoc. */
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
         * T-2 domain enumeration: the identifier for one domain. The
         * measured domain-list utility returns one domain per line, not a
         * keyed record (record §10) — this entry is positional, not a map
         * lookup, and its {@link #apiField()} value documents that fact
         * rather than naming a literal the adapter looks up by key. Kept as
         * a bound role for FB-2 traceability, the same way the previous
         * transport's SESSION_IDENTIFIER role existed only so no other
         * production class needed its own field-name literal.
         */
        DOMAIN_IDENTIFIER,

        /**
         * §7.4 connection table: the foreign-address column of a {@code
         * netstat -an} row (record §4 item 6, awk {@code $5}). Positional,
         * like {@link #DOMAIN_IDENTIFIER} — {@link
         * NetstatConnectionTableParser} reads it by column index, not by
         * key; this entry documents the measured column, it is not looked
         * up through {@link #forRole(Role)}.
         */
        CONNECTION_TABLE_ADDRESS,

        /** §7.4 connection table: the port half of the same foreign-address column (CS-6b) — positional, never hard-coded. */
        CONNECTION_TABLE_PORT,

        /** §7.4 connection table: the state column of the same row (record §4 item 6, awk {@code $6}) — positional. */
        CONNECTION_TABLE_STATE
    }

    public enum Status {
        /** A candidate field name, pending Product-Owner-run confirmation against the live management server. */
        UNVERIFIED,
        /** No field name could be justified by the measured behaviour of the record; the field is not carried (FB-3). */
        UNKNOWN
    }

    private final Role role;
    private final Optional<ObjectType> objectType;
    private final String apiField;
    private final Status status;

    private ManagementApiFieldBinding(Role role, Optional<ObjectType> objectType, String apiField, Status status) {
        this.role = role;
        this.objectType = objectType;
        this.apiField = apiField;
        this.status = status;
    }

    private static ManagementApiFieldBinding unscoped(Role role, String apiField) {
        return new ManagementApiFieldBinding(role, Optional.empty(), apiField, Status.UNVERIFIED);
    }

    private static ManagementApiFieldBinding scoped(Role role, ObjectType objectType, String apiField) {
        return new ManagementApiFieldBinding(role, Optional.of(objectType), apiField, Status.UNVERIFIED);
    }

    private static final List<ManagementApiFieldBinding> ENTRIES = List.of(
            // record §10 addendum: PRODUCT is one field across every object type.
            unscoped(Role.PRODUCT_FLAG, "cp_products_installed"),

            // record §10 addendum: VIRT_HOST/VIRT_SYSTEM bind to a different attribute per object type.
            scoped(Role.VIRT_HOST_FLAG, ObjectType.GATEWAY, "vsx_netobj"),
            scoped(Role.VIRT_HOST_FLAG, ObjectType.CLUSTER, "vsx_cluster_netobj"),
            scoped(Role.VIRT_HOST_FLAG, ObjectType.MEMBER, "vsx_cluster_member"),
            scoped(Role.VIRT_SYSTEM_FLAG, ObjectType.GATEWAY, "vs_netobj"),
            scoped(Role.VIRT_SYSTEM_FLAG, ObjectType.CLUSTER, "vs_cluster_netobj"),
            scoped(Role.VIRT_SYSTEM_FLAG, ObjectType.MEMBER, "vs_cluster_member"),

            // record §10 row 4: the stable identifier lives inside the AdminInfo block, not at top level.
            unscoped(Role.STABLE_IDENTIFIER, "AdminInfo/chkpf_uid"),
            unscoped(Role.DISPLAY_NAME, "name"),
            unscoped(Role.OWN_ADDRESS, "ipaddr"),
            unscoped(Role.MANAGEMENT_ADDRESS, "mgmt_ip"),

            // record §5 row 4: the cluster reference's stable identifier and display name live inside cluster_object.
            unscoped(Role.CLUSTER_REFERENCE_IDENTIFIER, "cluster_object/chkpf_uid"),
            unscoped(Role.CLUSTER_REFERENCE_DISPLAY_NAME, "cluster_object/name"),

            unscoped(Role.MODEL, "appliance_type"),
            unscoped(Role.SOFTWARE_VERSION, "svn_version_name"),
            unscoped(Role.MANAGEMENT_PLANE_CONNECTION_STATE, "connection_state"),

            // Positional roles — see their Role javadoc; not a map-key lookup.
            unscoped(Role.DOMAIN_IDENTIFIER, "domain-list-line"),
            unscoped(Role.CONNECTION_TABLE_ADDRESS, "netstat-foreign-address"),
            unscoped(Role.CONNECTION_TABLE_PORT, "netstat-foreign-port"),
            unscoped(Role.CONNECTION_TABLE_STATE, "netstat-state-column"));

    /** For a role with exactly one, unscoped, entry. Throws for {@link Role#VIRT_HOST_FLAG}/{@link Role#VIRT_SYSTEM_FLAG} -- use {@link #forRole(Role, ObjectType)}. */
    public static ManagementApiFieldBinding forRole(Role role) {
        return ENTRIES.stream()
                .filter(e -> e.role == role && e.objectType.isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no unscoped binding entry for role " + role));
    }

    /** For a role scoped per {@link ObjectType} (falls back to an unscoped entry if the role has one instead). */
    public static ManagementApiFieldBinding forRole(Role role, ObjectType objectType) {
        return ENTRIES.stream()
                .filter(e -> e.role == role && (e.objectType.isEmpty() || e.objectType.get() == objectType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no binding entry for role " + role + " / " + objectType));
    }

    public static List<ManagementApiFieldBinding> all() {
        return ENTRIES;
    }

    public Role role() {
        return role;
    }

    /** Empty when this role has no unscoped entry — {@link Role#VIRT_HOST_FLAG}/{@link Role#VIRT_SYSTEM_FLAG}. */
    public Optional<ObjectType> objectType() {
        return objectType;
    }

    public Status status() {
        return status;
    }

    /** Empty when {@link #status()} is {@link Status#UNKNOWN} (FB-3: the field is not carried). */
    public Optional<String> apiField() {
        return status == Status.UNKNOWN ? Optional.empty() : Optional.of(apiField);
    }
}
