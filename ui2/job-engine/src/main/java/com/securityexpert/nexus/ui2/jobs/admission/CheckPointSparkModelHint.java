package com.securityexpert.nexus.ui2.jobs.admission;

import java.util.Optional;
import java.util.Set;

/**
 * Discovery-known Check Point Quantum Spark/Gaia Embedded model tokens (ported verbatim from the
 * pre-Java product's own evidence-driven classification), plus the directly observed
 * {@code gaia_embedded} platform marker, shared by {@code
 * ConfirmCapabilityExecutor} and {@code InventoryCapabilityExecutor} -- both choose which
 * already-approved channel (exec vs. interactive shell) to try first for a device whose model is
 * already known before this device contact runs.
 */
public final class CheckPointSparkModelHint {

    private static final Set<String> SPARK_MODEL_TOKENS = Set.of(
            "1500", "1530", "1550", "1570", "1590", "1600", "1800", "1900", "2000");

    private CheckPointSparkModelHint() {
    }

    /** A known model hint or a directly observed Gaia Embedded platform marker. */
    public static boolean isKnownSparkModel(Optional<String> modelHint) {
        return modelHint.map(model -> "gaia_embedded".equals(model)
                || SPARK_MODEL_TOKENS.stream().anyMatch(model::contains)).orElse(false);
    }
}
