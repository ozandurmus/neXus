package com.securityexpert.nexus.ui2.service.staticroute;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.staticroute.JooqStaticRoutePreviewRepository;
import com.securityexpert.nexus.ui2.persistence.staticroute.StaticRoutePreviewRepository;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * Spring composition for Phase R0 static-route previews (V252).
 */
@Configuration
public class StaticRoutePreviewConfiguration {

    @Bean
    public StaticRoutePreviewRepository staticRoutePreviewRepository(TransactionBoundary transactionBoundary) {
        return new JooqStaticRoutePreviewRepository(transactionBoundary);
    }

    @Bean
    public StaticRoutePreviewService staticRoutePreviewService(
            StaticRoutePreviewRepository staticRoutePreviewRepository,
            DeviceRepository deviceRepository,
            DeviceInventoryRepository deviceInventoryRepository,
            JobAdmissionService jobAdmissionService,
            TopologyNamePseudonymizer topologyNamePseudonymizer) {
        return new StaticRoutePreviewService(
                staticRoutePreviewRepository,
                deviceRepository,
                deviceInventoryRepository,
                jobAdmissionService,
                JobWindowPolicy.SYSTEM,
                topologyNamePseudonymizer);
    }
}
