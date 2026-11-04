package com.securityexpert.nexus.ui2.service.pathanalysis.validation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.pathvalidation.JooqPathValidationRepository;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

/** Path analysis stage A6 composition: the V188 repository and the validation service over the shared admission. */
@Configuration
public class PathValidationConfiguration {

    @Bean
    public PathValidationService pathValidationService(TransactionBoundary transactionBoundary,
            JobAdmissionService jobAdmissionService, DeviceInventoryRepository deviceInventoryRepository) {
        return new PathValidationService(new JooqPathValidationRepository(transactionBoundary), jobAdmissionService,
                deviceInventoryRepository, JobWindowPolicy.SYSTEM);
    }
}
