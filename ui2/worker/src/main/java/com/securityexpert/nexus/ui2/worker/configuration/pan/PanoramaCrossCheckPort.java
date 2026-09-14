package com.securityexpert.nexus.ui2.worker.configuration.pan;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationOverride;

/**
 * 14G CG-7c's cross-check step: given a device's serial and its {@code
 * src=local} override candidates (from {@link PaloAltoConfigStreamProcessor}),
 * names the Panorama-side template/device-group definition for each path
 * that one actually exists for, using {@link PanoramaAssignmentReducer}'s
 * own assignment index and defined-path sets. Never the source of record
 * (WORKER.md invariant) -- only a naming step over overrides the device's
 * own read already found.
 */
public interface PanoramaCrossCheckPort {

    /** Returns {@code overrides} with {@code panoramaSource} filled in wherever a Panorama-side definition was found. */
    List<ConfigurationOverride> nameOverrideSources(String deviceSerial, List<ConfigurationOverride> overrides);

    /** No live Panorama target exists yet (14F DR-4): every override is returned unnamed. */
    PanoramaCrossCheckPort NONE = (deviceSerial, overrides) -> overrides;

    /** Backs a cross-check from one already-reduced {@link PanoramaAssignmentReducer.Reduced} result (fixture-driven, CG-7c). */
    static PanoramaCrossCheckPort fromReduced(PanoramaAssignmentReducer.Reduced reduced) {
        return (deviceSerial, overrides) -> {
            Optional<PanoramaAssignmentReducer.Assignment> assignment =
                    Optional.ofNullable(reduced.assignmentsBySerial().get(deviceSerial));
            if (assignment.isEmpty()) {
                return overrides;
            }
            List<String> candidateKeys = new java.util.ArrayList<>();
            assignment.get().templates().forEach(t -> candidateKeys.add("template:" + t));
            assignment.get().deviceGroups().forEach(dg -> candidateKeys.add("device-group:" + dg));

            return overrides.stream()
                    .map(override -> {
                        for (String key : candidateKeys) {
                            var definedPaths = reduced.definedPaths().get(key);
                            if (definedPaths != null && definedPaths.contains(override.elementPath())) {
                                return new ConfigurationOverride(override.context(), override.category(),
                                        override.elementPath(), Optional.of(key));
                            }
                        }
                        return override;
                    })
                    .toList();
        };
    }
}
