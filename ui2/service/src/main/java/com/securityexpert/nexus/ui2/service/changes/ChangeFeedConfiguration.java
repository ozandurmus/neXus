package com.securityexpert.nexus.ui2.service.changes;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.changes.JooqChangeFeedRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess;
import com.securityexpert.nexus.ui2.service.policy.PolicyChangesService;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;

/** Wires Operations > Change feed: the read-only repository, the chunk reader over the artefact store, both projections. */
@Configuration(proxyBeanMethods = false)
public class ChangeFeedConfiguration {

    @Bean
    public ChangeFeedService changeFeedService(TransactionBoundary transactions, PolicyChangesService policy,
            ArtefactStoreAccess artefacts, DeviceRepository devices, TopologyNamePseudonymizer names, LogMasking masking) {
        ChunkTextReader reader = new ChunkTextReader(artefacts.storeOrNull());
        return new ChangeFeedService(new JooqChangeFeedRepository(transactions), policy, reader,
                () -> new DefaultProjection(false, inventory(devices), names, null, null),
                () -> {
                    LogMasking.Session session = null;
                    RuntimeException failure = null;
                    try {
                        session = masking.session();
                    } catch (RuntimeException e) {
                        failure = e; // fail closed: the text sources say UNAVAILABLE, nothing raw is shown
                    }
                    return new DefaultProjection(true, inventory(devices), names, session, failure);
                });
    }

    private static List<DeviceSummaryRecord> inventory(DeviceRepository devices) {
        return devices.listAll();
    }
}
