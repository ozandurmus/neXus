package com.securityexpert.nexus.ui2.worker.systemlog;

import java.util.Set;

/**
 * Per-run feedback the executor passes back to the pure planner when it re-plans a source.
 *
 * @param deviceBytesAlreadyPlanned payload bytes of the device budget already spent by earlier sources this run
 * @param anchorMismatchPaths paths whose anchor check failed at the cursor offset in this run (an anchor result
 *        comes from a read, so the pure planner learns it only by being called again)
 */
public record PlanContext(long deviceBytesAlreadyPlanned, Set<String> anchorMismatchPaths) {

    public PlanContext {
        if (deviceBytesAlreadyPlanned < 0) {
            throw new IllegalArgumentException("deviceBytesAlreadyPlanned must be non-negative");
        }
        anchorMismatchPaths = Set.copyOf(anchorMismatchPaths);
    }

    public static PlanContext fresh() {
        return new PlanContext(0, Set.of());
    }

    public PlanContext withMismatch(String path) {
        java.util.HashSet<String> next = new java.util.HashSet<>(anchorMismatchPaths);
        next.add(path);
        return new PlanContext(deviceBytesAlreadyPlanned, next);
    }

    public PlanContext withDeviceBytes(long bytes) {
        return new PlanContext(bytes, anchorMismatchPaths);
    }
}
