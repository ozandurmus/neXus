package com.securityexpert.nexus.ui2.worker.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The zone names of each virtual system of a Palo Alto firewall, parsed from its stored effective-running configuration
 * (V229), and when that configuration was collected. A virtual system with no zone has an empty list; one that could not
 * be read in full is absent.
 */
public record PanZoneRead(Instant readAt, Map<String, List<String>> zoneNamesByVsys) {
    public PanZoneRead {
        Objects.requireNonNull(readAt, "readAt");
        zoneNamesByVsys = Map.copyOf(Objects.requireNonNull(zoneNamesByVsys, "zoneNamesByVsys"));
    }
}
