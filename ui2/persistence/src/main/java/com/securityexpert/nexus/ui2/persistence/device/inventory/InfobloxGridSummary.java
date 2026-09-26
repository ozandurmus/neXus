package com.securityexpert.nexus.ui2.persistence.device.inventory;

/** Nullable counts mean an individual WAPI object could not be measured. JSON contains only display fields. */
public record InfobloxGridSummary(Integer dnsViews, boolean dnsViewsAtLeast, Integer authZones,
        boolean authZonesAtLeast, Integer dhcpNetworks, boolean dhcpNetworksAtLeast,
        Integer dhcpRanges, boolean dhcpRangesAtLeast, String topNetworksJson, String licensesJson) {
}
