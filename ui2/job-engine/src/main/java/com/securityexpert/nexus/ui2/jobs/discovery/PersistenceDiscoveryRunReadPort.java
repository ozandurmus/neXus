package com.securityexpert.nexus.ui2.jobs.discovery;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRunRepository;

/**
 * {@link DiscoveryRunReadPort} adapter over {@code persistence}'s {@link
 * DiscoveryRunRepository} -- mirrors {@code
 * jobs.device.PersistenceDeviceEnrollmentReadPort}'s pattern for the run
 * target kind.
 */
public final class PersistenceDiscoveryRunReadPort implements DiscoveryRunReadPort {

    private final DiscoveryRunRepository discoveryRunRepository;

    public PersistenceDiscoveryRunReadPort(DiscoveryRunRepository discoveryRunRepository) {
        this.discoveryRunRepository = Objects.requireNonNull(discoveryRunRepository, "discoveryRunRepository");
    }

    @Override
    public Optional<DiscoveryRunSnapshot> findRun(String runId) {
        return discoveryRunRepository.findRun(runId)
                .map(run -> new DiscoveryRunSnapshot(run.runId(), run.state().name()));
    }
}
