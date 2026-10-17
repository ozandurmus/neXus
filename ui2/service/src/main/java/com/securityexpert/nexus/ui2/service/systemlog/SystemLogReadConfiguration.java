package com.securityexpert.nexus.ui2.service.systemlog;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactRetrievalRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogReadRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess;
import com.securityexpert.nexus.ui2.service.privacy.HmacKeyProvider;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.sanitizer.SanitizerConfiguration;

/**
 * Wires the system-log read side (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 14): the index queries, the aiview
 * masking projection (the Data Sanitizer's own rules under the product's privacy key, with the inventory's device
 * names as the sanitizer reads them) and the service that decrypts one page of a chunk.
 */
@Configuration(proxyBeanMethods = false)
public class SystemLogReadConfiguration {
    /** The inventory names are read for every page; a device registered a minute ago is still covered. */
    static final Duration NAMES_TTL = Duration.ofSeconds(30);

    @Bean
    public SystemLogReadRepository systemLogReadRepository(TransactionBoundary transactions) {
        return new JooqSystemLogReadRepository(transactions);
    }

    @Bean
    public LogMasking logMasking(HmacKeyProvider keys, DeviceRepository devices, TopologyNamePseudonymizer names) {
        return new LogMasking(keys.getSecretKey(), cached(SanitizerConfiguration.knownNames(devices, names), NAMES_TTL));
    }

    @Bean
    public SystemLogReadService systemLogReadService(SystemLogReadRepository repository, TransactionBoundary transactions,
            DeviceRepository devices, ArtefactStoreAccess artefacts, LogMasking masking) {
        return new SystemLogReadService(repository, new JooqSystemLogCollectionRepository(transactions), devices,
                artefacts.storeOrNull(), masking, new JooqBackupArtefactRetrievalRepository(transactions));
    }

    static Supplier<KnownNames> cached(Supplier<KnownNames> source, Duration ttl) {
        return new Supplier<>() {
            private volatile KnownNames value;
            private volatile long expiresAtNanos;

            @Override
            public KnownNames get() {
                long now = System.nanoTime();
                KnownNames current = value;
                if (current != null && now - expiresAtNanos < 0) return current;
                KnownNames fresh = source.get();
                value = fresh;
                expiresAtNanos = now + ttl.toNanos();
                return fresh;
            }
        };
    }
}
