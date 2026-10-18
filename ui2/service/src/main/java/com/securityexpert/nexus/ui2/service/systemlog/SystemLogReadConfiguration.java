package com.securityexpert.nexus.ui2.service.systemlog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactRetrievalRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogReadRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess;
import com.securityexpert.nexus.ui2.service.privacy.HmacKeyProvider;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking.EstateIdentity;
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
    public LogMasking logMasking(HmacKeyProvider keys, DeviceRepository devices, DevicePlatformFactsRepository platformFacts,
            TopologyNamePseudonymizer names) {
        return new LogMasking(keys.getSecretKey(), cached(SanitizerConfiguration.knownNames(devices, names), NAMES_TTL),
                estateIdentities(devices, platformFacts, names), NAMES_TTL);
    }

    /**
     * The identity values the service knows for the masked projection's estate dictionary: every device's serial
     * ({@code device_platform_facts}), recorded and presented first-contact identities, management address, interface
     * addresses (HA and sync interfaces included), host name and cluster name. {@link LogMasking} digests them and
     * drops the list; it is never cached or logged here.
     */
    static Supplier<List<EstateIdentity>> estateIdentities(DeviceRepository devices, DevicePlatformFactsRepository platformFacts,
            TopologyNamePseudonymizer names) {
        return () -> {
            List<EstateIdentity> out = new ArrayList<>();
            Map<String, DevicePlatformFacts> facts = platformFacts == null ? Map.of() : platformFacts.findAll();
            for (DeviceSummaryRecord d : devices.listAll()) {
                String cluster = d.clusterMemberRef().filter(s -> !s.isBlank()).orElse(null);
                d.observedHostname().filter(s -> !s.isBlank())
                        .ifPresent(host -> out.add(EstateIdentity.name(host, names.maskDeviceName(host, cluster))));
                if (cluster != null) out.add(EstateIdentity.name(cluster, names.maskClusterName(cluster)));
                d.managementIp().ifPresent(address -> out.add(EstateIdentity.address(address)));
                d.ipAddresses().ifPresent(list -> {
                    for (String address : list.split("[\\s,;]+")) {
                        if (!address.isBlank()) out.add(EstateIdentity.address(address));
                    }
                });
                DevicePlatformFacts platform = facts.get(d.deviceId());
                if (platform != null) platform.serialNumber().ifPresent(serial -> out.add(EstateIdentity.serial(serial)));
                Optional<DeviceConfirmFacts> confirm = devices.findConfirmFacts(d.deviceId());
                confirm.ifPresent(c -> {
                    c.recordedIdentityPrimary().ifPresent(v -> out.add(EstateIdentity.serial(v)));
                    c.recordedIdentitySecondary().ifPresent(v -> out.add(EstateIdentity.serial(v)));
                    c.identityMismatchPresentedPrimary().ifPresent(v -> out.add(EstateIdentity.serial(v)));
                    c.identityMismatchPresentedSecondary().ifPresent(v -> out.add(EstateIdentity.serial(v)));
                });
            }
            return out;
        };
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
