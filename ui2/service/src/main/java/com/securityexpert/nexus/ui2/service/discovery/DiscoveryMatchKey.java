package com.securityexpert.nexus.ui2.service.discovery;

import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;

/**
 * RD-3's match key, computed once so {@link DiscoveryRunService} writes and
 * reads the identical value on both sides of the comparison: {@code
 * devices.discovery_match_key} at import time, and the lookup key RD-5's
 * three-outcome check queries by. Opaque (identity law): never parsed back
 * apart, only compared for equality.
 *
 * <ul>
 *   <li>Check Point: {@code (owning domain, stable identifier)} -- RD-3
 *       first bullet.</li>
 *   <li>Palo Alto: the serial alone -- RD-3 second bullet.</li>
 * </ul>
 */
final class DiscoveryMatchKey {

    private DiscoveryMatchKey() {
    }

    static String of(DiscoveryCandidateRecord candidate) {
        return switch (candidate.vendor()) {
            case "check_point" -> "check_point|" + candidate.owningDomain().orElse("") + "|"
                    + candidate.stableIdentifier();
            case "palo_alto" -> "palo_alto|" + candidate.stableIdentifier();
            // Radware discovery (2026-09-24): the Cyber Controller's own opaque device id (ormId), never parsed.
            case "radware" -> "radware|" + candidate.stableIdentifier();
            default -> throw new IllegalArgumentException("unsupported vendor: " + candidate.vendor());
        };
    }
}
