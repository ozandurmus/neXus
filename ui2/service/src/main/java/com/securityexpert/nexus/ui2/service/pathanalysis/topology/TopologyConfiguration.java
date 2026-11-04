package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;

/** Path analysis stage A3 composition: the V186 repository and the in-memory topology service. */
@Configuration
public class TopologyConfiguration {

    @Bean
    public TopologyRepository topologyRepository(TransactionBoundary transactionBoundary) {
        return new JooqTopologyRepository(transactionBoundary);
    }

    @Bean
    public TopologyService topologyService(DeviceRepository deviceRepository,
            DeviceInventoryRepository deviceInventoryRepository, TopologyRepository topologyRepository) {
        return new TopologyService(deviceRepository, deviceInventoryRepository, topologyRepository, Clock.systemUTC());
    }

    /**
     * System-actor rebuild at startup and after an inventory change (see {@link TopologyAutoRebuild}). Independent of
     * {@code ui2.scheduling.enabled}: it uses no scheduler. {@code ui2.topology.auto-rebuild.enabled=false} turns it off.
     */
    @Bean
    @ConditionalOnProperty(name = "ui2.topology.auto-rebuild.enabled", havingValue = "true", matchIfMissing = true)
    public TopologyAutoRebuild topologyAutoRebuild(TopologyService topologyService,
            @Value("${ui2.topology.auto-rebuild.check-interval:PT1M}") Duration checkInterval,
            @Value("${ui2.topology.auto-rebuild.min-gap:PT5M}") Duration minGap) {
        // Created here, not as a bean, so Spring does not manage (and shut down) it; the thread is a daemon.
        return new TopologyAutoRebuild(topologyService, Clock.systemUTC(), Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "topology-auto-rebuild");
            thread.setDaemon(true);
            return thread;
        }), checkInterval, minGap);
    }
}
