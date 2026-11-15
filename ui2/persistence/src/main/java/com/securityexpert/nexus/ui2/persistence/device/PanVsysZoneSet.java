package com.securityexpert.nexus.ui2.persistence.device;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The zone names a Palo Alto firewall's effective-running configuration holds for one virtual system (V229), and when
 * that configuration was read. Empty zones (no interface member) are zones. {@code vsys} is the vsys entry name as the
 * configuration and Panorama give it ({@code vsys1}), not the interface context number.
 */
public record PanVsysZoneSet(String deviceId, String vsys, Set<String> zones, Instant readAt) {

    public static final int MAX_ZONES = 1024;

    public PanVsysZoneSet {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(vsys, "vsys");
        Objects.requireNonNull(readAt, "readAt");
        if (deviceId.isBlank() || vsys.isBlank() || vsys.length() > 128) {
            throw new IllegalArgumentException("device and vsys must be set");
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String zone : Objects.requireNonNull(zones, "zones")) {
            if (zone == null || zone.isBlank() || zone.length() > 128) {
                throw new IllegalArgumentException("a zone name must be 1..128 characters");
            }
            sorted.add(zone);
        }
        if (sorted.size() > MAX_ZONES) {
            throw new IllegalArgumentException("too many zones");
        }
        zones = java.util.Collections.unmodifiableSet(sorted);
    }
}
