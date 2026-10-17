package com.securityexpert.nexus.ui2.service.sanitizer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.sanitizer.SanitizerRepository;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.privacy.HmacKeyProvider;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * Wires the Data Sanitizer (DATA_SANITIZER_CONTRACT.md §3): the engine keyed with the privacy HMAC key, the vault
 * sealed under the artefact-store envelope key ({@code UI2_ARTEFACT_STORE_KEY_FILE}; without it sanitize and revert
 * are refused rather than run without a vault), the inventory's device pseudonyms, the work area, limits and the
 * housekeeping that deletes expired results and prunes expired vaults.
 */
@Configuration(proxyBeanMethods = false)
public class SanitizerConfiguration {

    private static final Logger LOG = Logger.getLogger(SanitizerConfiguration.class.getName());

    @Bean
    public SanitizerRepository sanitizerRepository(TransactionBoundary transactions) {
        return new SanitizerRepository(transactions);
    }

    @Bean(destroyMethod = "")
    public SanitizerService sanitizerService(SanitizerRepository repository, HmacKeyProvider keys,
            Optional<DeviceRepository> devices, TopologyNamePseudonymizer names,
            @Value("${ui2.sanitizer.work-dir:${java.io.tmpdir}/nexus-sanitizer}") String workDir,
            @Value("${ui2.sanitizer.max-file-bytes:2147483648}") long maxBytes,
            @Value("${ui2.sanitizer.vault-retention-days:90}") int retentionDays,
            @Value("${ui2.sanitizer.output-ttl-minutes:60}") long outputTtlMinutes,
            @Value("${ui2.sanitizer.concurrent-jobs:2}") int concurrentJobs) {
        String keyFile = System.getenv().getOrDefault("UI2_ARTEFACT_STORE_KEY_FILE", "");
        VaultCrypto crypto = keyFile.isBlank() ? null : new VaultCrypto(keys.getSecretKey(),
                ArtefactStoreCipher.fromBase64Key(SecretFile.readRequired(Path.of(keyFile), "artefact_store_key")));
        ExecutorService executor = new ThreadPoolExecutor(concurrentJobs, concurrentJobs, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(4 * concurrentJobs), runnable -> {
                    Thread t = new Thread(runnable, "data-sanitizer");
                    t.setDaemon(true);
                    return t;
                });
        return new SanitizerService(repository, crypto, keys.getSecretKey(), knownNames(devices.orElse(null), names),
                Path.of(workDir), maxBytes, retentionDays, Duration.ofMinutes(outputTtlMinutes), executor);
    }

    @Bean
    public SanitizerHooks sanitizerHooks(
            @Value("${ui2.sanitizer.packet-analysis-anonymize-on-upload:false}") boolean packetAnalysisOnUpload,
            @Value("${ui2.sanitizer.log-export:false}") boolean logExport) {
        return new SanitizerHooks(packetAnalysisOnUpload, logExport);
    }

    /**
     * The inventory's device and cluster names with the pseudonyms the UI shows for them, read once per job (so a
     * device registered a minute ago is already covered). Names that are addresses are left to the address rules.
     */
    public static Supplier<KnownNames> knownNames(DeviceRepository devices, TopologyNamePseudonymizer names) {
        if (devices == null) return () -> KnownNames.NONE;
        return () -> {
            Map<String, String> map = new HashMap<>();
            devices.listAll().forEach(d -> {
                String cluster = d.clusterMemberRef().filter(s -> !s.isBlank()).orElse(null);
                d.observedHostname().filter(s -> !s.isBlank()).ifPresent(host -> {
                    String pseudonym = names.maskDeviceName(host, cluster);
                    if (!"Unknown".equals(pseudonym)) map.put(host.strip(), pseudonym);
                });
                if (cluster != null) map.put(cluster.strip(), names.maskClusterName(cluster));
            });
            return KnownNames.of(map);
        };
    }

    /** Housekeeping: interrupted jobs closed at start; results expire after the TTL; vaults after the retention. */
    @Configuration(proxyBeanMethods = false)
    static class Housekeeping {
        private final SanitizerService service;

        Housekeeping(SanitizerService service) {
            this.service = service;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void start() {
            try {
                service.start();
            } catch (Exception e) {
                LOG.warning("[SANITIZER] work area could not be prepared: " + e.getClass().getSimpleName());
            }
        }

        @Scheduled(fixedDelayString = "${ui2.sanitizer.housekeeping-ms:60000}", initialDelay = 60000)
        public void expireOutputs() {
            try {
                service.expireOutputs();
            } catch (Exception e) {
                LOG.warning("[SANITIZER] output expiry failed: " + e.getClass().getSimpleName());
            }
        }

        @Scheduled(fixedDelayString = "${ui2.sanitizer.vault-prune-ms:3600000}", initialDelay = 300000)
        public void pruneVaults() {
            try {
                int pruned = service.pruneVaults();
                if (pruned > 0) LOG.info("[SANITIZER] pruned " + pruned + " expired vaults");
            } catch (Exception e) {
                LOG.warning("[SANITIZER] vault prune failed: " + e.getClass().getSimpleName());
            }
        }
    }
}
