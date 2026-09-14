package com.securityexpert.nexus.ui2.persistence.device.configuration;

import java.util.List;
import java.util.Optional;

/**
 * The Configuration screen's own read shape (WORKER.md "Service"): one
 * device's primary run (Check Point {@code show_configuration} / Palo Alto
 * {@code effective_running}) plus every supplementary run recorded
 * alongside it in the same job (Palo Alto's {@code active}/{@code merged}).
 */
public record ConfigurationRunView(String deviceId, Optional<ConfigurationRun> primaryRun,
        List<ConfigurationRun> supplementaryRuns) {

    public ConfigurationRunView {
        primaryRun = primaryRun == null ? Optional.empty() : primaryRun;
        supplementaryRuns = supplementaryRuns == null ? List.of() : List.copyOf(supplementaryRuns);
    }
}
