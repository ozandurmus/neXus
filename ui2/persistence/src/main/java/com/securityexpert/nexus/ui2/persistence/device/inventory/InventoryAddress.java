package com.securityexpert.nexus.ui2.persistence.device.inventory;

import java.util.Objects;

/**
 * One {@code device_interface_address} row (14C D-4). {@code role} is
 * either {@code "member"} or {@code "cluster_virtual"} -- 14C §3's rule
 * that a cluster virtual address (read from {@code cphaprob -a if}) and
 * a member address (read from {@code ip addr}) never mix is enforced by
 * the parser that builds this record, not by this type; this record only
 * carries whichever role the caller already decided.
 */
public record InventoryAddress(String addressId, String address, String family, String role) {

    public static final String FAMILY_IPV4 = "ipv4";
    public static final String FAMILY_IPV6 = "ipv6";
    public static final String ROLE_MEMBER = "member";
    public static final String ROLE_CLUSTER_VIRTUAL = "cluster_virtual";

    public InventoryAddress {
        Objects.requireNonNull(addressId, "addressId");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(role, "role");
    }
}
