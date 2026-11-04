package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.time.Clock;

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
}
