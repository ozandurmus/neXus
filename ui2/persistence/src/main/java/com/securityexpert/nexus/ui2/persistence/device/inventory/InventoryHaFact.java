package com.securityexpert.nexus.ui2.persistence.device.inventory;

import java.util.Objects;
import java.util.Optional;

/**
 * One {@code device_inventory_ha} row (migration V17, 14C D-3/D-4, 14D
 * PR-4): one context's HA role within a run. {@link #source()} names which
 * read produced it -- the Check Point physical context's own {@code
 * cphaprob stat}, the per-VSID role the same read's VSLS table yields (no
 * per-VSID re-read), or Palo Alto's {@code show high-availability state}.
 */
public record InventoryHaFact(String haId, String context, String role, Optional<String> clusterMode, String source) {

    public static final String SOURCE_CP_CPHAPROB_STAT = "cp_cphaprob_stat";
    public static final String SOURCE_CP_VSLS_TABLE = "cp_vsls_table";
    public static final String SOURCE_PAN_HIGH_AVAILABILITY_STATE = "pan_high_availability_state";

    public InventoryHaFact {
        Objects.requireNonNull(haId, "haId");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(role, "role");
        clusterMode = clusterMode == null ? Optional.empty() : clusterMode;
        Objects.requireNonNull(source, "source");
    }
}
