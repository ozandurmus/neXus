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
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqEstateNameRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogReadRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogSettingsRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateIdentity;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess;
import com.securityexpert.nexus.ui2.service.privacy.EstateDictionarySource;
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

    /** The estate dictionary the masked view and the Data Sanitizer share (one read of the device tables per TTL). */
    @Bean
    public EstateDictionarySource estateDictionarySource(HmacKeyProvider keys, DeviceRepository devices,
            DevicePlatformFactsRepository platformFacts, TopologyNamePseudonymizer names) {
        return new EstateDictionarySource(keys.getSecretKey(), estateIdentities(devices, platformFacts, names), NAMES_TTL);
    }

    /**
     * The estate names of the masked view ({@code logmask-v5}): administrator, policy, rule, object, zone,
     * virtual-system and MDS domain names, read from the policy and device tables (snapshots one row at a time, only
     * when changed) and refreshed in the background every 15 minutes.
     */
    @Bean
    public EstateNameCatalog estateNameCatalog(HmacKeyProvider keys, TransactionBoundary transactions) {
        return new EstateNameCatalog(keys.getSecretKey(), estateNameSource(new JooqEstateNameRepository(transactions)),
                EstateNameCatalog.DEFAULT_REFRESH);
    }

    /** The repository as the catalog's source (also used by the 2026-11-10 measurement program). */
    public static EstateNameCatalog.Source estateNameSource(JooqEstateNameRepository repository) {
        return new EstateNameCatalog.Source() {
            @Override
            public List<EstateNameCatalog.Named> flatNames() {
                List<EstateNameCatalog.Named> out = new ArrayList<>();
                for (JooqEstateNameRepository.FlatName f : repository.flatNames()) {
                    out.add(new EstateNameCatalog.Named(EstateNameCatalog.Kind.valueOf(f.category().name()), f.value()));
                }
                return out;
            }

            @Override
            public List<String> objectInventoryDocuments() {
                return repository.objectInventoryDocuments();
            }

            @Override
            public Map<String, String> policySnapshotVersions() {
                return repository.policySnapshotVersions();
            }

            @Override
            public Optional<String[]> policySnapshot(String policyId) {
                return repository.policySnapshot(policyId).map(d -> new String[] {d.metadataJson(), d.snapshotJson()});
            }
        };
    }

    /** Loads the estate names once the service is up, so the first masked page does not wait for the read. */
    @Bean
    public EstateNameWarmUp estateNameWarmUp(EstateNameCatalog catalog) {
        return new EstateNameWarmUp(catalog);
    }

    public static final class EstateNameWarmUp {
        private final EstateNameCatalog catalog;

        EstateNameWarmUp(EstateNameCatalog catalog) {
            this.catalog = catalog;
        }

        @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
        public void onApplicationReady() {
            catalog.refreshAsync();
        }
    }

    @Bean
    public LogMasking logMasking(HmacKeyProvider keys, DeviceRepository devices, TopologyNamePseudonymizer names,
            EstateDictionarySource estate, EstateNameCatalog estateNames) {
        return new LogMasking(keys.getSecretKey(), cached(SanitizerConfiguration.knownNames(devices, names), NAMES_TTL), estate,
                estateNames);
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

    /**
     * Operations > System health: the fleet counters over the stored sar texts, cpwd chunks, crash alerts and run outcomes
     * (docs/design/SYSTEM_HEALTH_SCREEN_2026_10_20.md). The threshold is the one documented default unless
     * {@code ui2.system-health.cpu-p95-threshold-percent} ({@code UI2_SYSTEM_HEALTH_CPU_P95_THRESHOLD_PERCENT}) says otherwise.
     */
    @Bean
    public SystemHealthService systemHealthService(SystemLogReadRepository repository, TransactionBoundary transactions,
            DeviceRepository devices, ArtefactStoreAccess artefacts,
            com.securityexpert.nexus.ui2.service.device.diagnostic.SystemLogCollectionService collection,
            @org.springframework.beans.factory.annotation.Value("${ui2.system-health.cpu-p95-threshold-percent:"
                    + SystemHealthService.DEFAULT_CPU_P95_THRESHOLD_PERCENT + "}") int thresholdPercent,
            @org.springframework.beans.factory.annotation.Value("${ui2.scheduling.enabled:false}") boolean schedulingEnabled) {
        return new SystemHealthService(repository, new JooqSystemLogCollectionRepository(transactions), collection::targets, devices,
                artefacts.storeOrNull(), thresholdPercent, schedulingEnabled);
    }

    /**
     * Keeps the System health snapshot warm: an initial computation at startup independent of
     * {@code ui2.scheduling.enabled} (uses no scheduler), plus a periodic refresh per snapshot TTL when scheduling is on.
     */
    @Bean
    public SystemHealthRefresher systemHealthRefresher(SystemHealthService service) {
        return new SystemHealthRefresher(service);
    }

    public static final class SystemHealthRefresher {
        private final SystemHealthService service;

        SystemHealthRefresher(SystemHealthService service) {
            this.service = service;
        }

        @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
        public void onApplicationReady() {
            refresh();
        }

        @org.springframework.scheduling.annotation.Scheduled(initialDelayString = "PT45S", fixedDelayString = "PT10M")
        public void refresh() {
            service.refreshAsync(java.time.Instant.now());
        }
    }

    /** The settings row's read and audited write (section 14, {@code /api/v2/config/system-logs}). */
    @Bean
    public SystemLogSettingsService systemLogSettingsService(TransactionBoundary transactions) {
        return new SystemLogSettingsService(new JooqSystemLogSettingsRepository(transactions));
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
