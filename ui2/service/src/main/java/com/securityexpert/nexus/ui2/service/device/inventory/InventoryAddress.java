package com.securityexpert.nexus.ui2.service.device.inventory;

/**
 * READ CONTRACT shared by NXS-LOCAL-0159 and NXS-LOCAL-0160: one interface
 * address, exactly as both {@code GET /devices/{id}/inventory} and
 * {@code GET /clusters/{cluster_member_ref}/inventory} spell it. {@code
 * role} is {@code "member"} (the collecting device's own address) or
 * {@code "cluster_virtual"} (a VIP) -- never any other value (14C D-5, 13F
 * CL-2: VIPs are data on the cluster row, never a member address).
 *
 * <p>Placeholder shape for NXS-LOCAL-0159's eventual {@code
 * persistence.device.inventory.InventoryAddress} (migration V13, additive)
 * -- WORKER.md: "work against your own in-memory fake... shaped exactly as
 * the contract; then rebase... and drop the fake from main code."</p>
 */
public record InventoryAddress(String address, String family, String role) {

    public static final String FAMILY_IPV4 = "ipv4";
    public static final String FAMILY_IPV6 = "ipv6";
    public static final String ROLE_MEMBER = "member";
    public static final String ROLE_CLUSTER_VIRTUAL = "cluster_virtual";
}
