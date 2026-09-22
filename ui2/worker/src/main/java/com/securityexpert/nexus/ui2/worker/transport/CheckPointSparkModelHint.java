package com.securityexpert.nexus.ui2.worker.transport;

import java.util.Optional;
import java.util.Set;

/**
 * Discovery-known Check Point Quantum Spark/Gaia Embedded model tokens (ported verbatim from the
 * pre-Java product's own evidence-driven classification), shared by {@code
 * ConfirmCapabilityExecutor} and {@code InventoryCapabilityExecutor} -- both choose which
 * already-approved channel (exec vs. interactive shell) to try first for a device whose model is
 * already known before this device contact ever runs (e.g. a Check Point Management Server's own
 * "hardware" field, joined in by {@code JooqDeviceRepository.DEVICE_SUMMARY_SELECT} before any
 * confirm or inventory job runs), never to alter which commands are sent.
 */
public final class CheckPointSparkModelHint {

    private static final Set<String> SPARK_MODEL_TOKENS = Set.of(
            "1500", "1530", "1550", "1570", "1590", "1600", "1800", "1900", "2000");

    private CheckPointSparkModelHint() {
    }

    /** True when a discovery-sourced model hint (management-plane observation, never confirmed
     * evidence -- see the Evidence laws) already names a Quantum Spark/Gaia Embedded appliance. */
    public static boolean isKnownSparkModel(Optional<String> modelHint) {
        return modelHint.map(model -> SPARK_MODEL_TOKENS.stream().anyMatch(model::contains)).orElse(false);
    }
}
