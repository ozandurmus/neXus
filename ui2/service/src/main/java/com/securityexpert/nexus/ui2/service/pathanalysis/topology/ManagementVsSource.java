package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.time.Instant;
import java.util.List;

/** Loads the management Virtual System facts a topology build may use; empty when none are provable. */
@FunctionalInterface
public interface ManagementVsSource {

    ManagementVsSource NONE = builtAt -> List.of();

    List<TopologyInput.ManagementVs> load(Instant builtAt);
}
