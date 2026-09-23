package com.securityexpert.nexus.ui2.worker;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.jobs.capability.PersistenceGateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.device.PersistenceDeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.lease.PersistenceJobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.PersistenceJobStepAttemptRepository;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundaryFactory;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialStoreComposition;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRunRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.JooqDiscoveryRunRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupEndpointEligibilityRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupEndpointEligibilityRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.configuration.DeviceConfigurationRepository;
import com.securityexpert.nexus.ui2.persistence.device.configuration.JooqConfigurationNotificationRepository;
import com.securityexpert.nexus.ui2.persistence.device.configuration.JooqDeviceConfigurationRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.JooqDeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.gates.JooqGateRegistryDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobStepAttemptDao;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.worker.confirm.ConfirmCapabilities;
import com.securityexpert.nexus.ui2.worker.confirm.ConfirmCapabilityExecutor;
import com.securityexpert.nexus.ui2.worker.confirm.ConfirmJobExecutor;
import com.securityexpert.nexus.ui2.worker.confirm.PeerFollowResolver;
import com.securityexpert.nexus.ui2.worker.backup.BackupCapabilities;
import com.securityexpert.nexus.ui2.worker.backup.BackupCapabilityExecutor;
import com.securityexpert.nexus.ui2.worker.backup.BackupJobExecutor;
import com.securityexpert.nexus.ui2.worker.confirm.WorkerClaimLoop;
import com.securityexpert.nexus.ui2.worker.configuration.ConfigurationCapabilities;
import com.securityexpert.nexus.ui2.worker.configuration.ConfigurationCapabilityExecutor;
import com.securityexpert.nexus.ui2.worker.configuration.ConfigurationJobExecutor;
import com.securityexpert.nexus.ui2.worker.configuration.pan.PanoramaCrossCheckPort;
import com.securityexpert.nexus.ui2.worker.discovery.DiscoveryJobExecutor;
import com.securityexpert.nexus.ui2.worker.discovery.cp.MgmtCliEnumerationAdapter;
import com.securityexpert.nexus.ui2.worker.discovery.cp.StoreBackedSshCredentialResolver;
import com.securityexpert.nexus.ui2.worker.discovery.pan.EnvironmentPanTrustRuleResolver;
import com.securityexpert.nexus.ui2.worker.discovery.pan.PanoramaEnumerationAdapter;
import com.securityexpert.nexus.ui2.worker.discovery.pan.StoreBackedPanCredentialResolver;
import com.securityexpert.nexus.ui2.worker.inventory.InventoryCapabilities;
import com.securityexpert.nexus.ui2.worker.inventory.InventoryCapabilityExecutor;
import com.securityexpert.nexus.ui2.worker.inventory.InventoryJobExecutor;
import com.securityexpert.nexus.ui2.worker.transport.CompositeDeviceTransport;
import com.securityexpert.nexus.ui2.worker.transport.TransportRegistry;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshExecTransport;
import com.securityexpert.nexus.ui2.worker.transport.ssh.TrustRuleResolver;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanTrustRuleResolver;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanXmlApiTransport;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.TrustResolution;

/**
 * The worker process's own composition root and entry point (13D DS-1/DS-2:
 * "a capability may be its own deployable unit"; contract scope "worker
 * runtime"). Reads exactly the secret files the worker needs (the DB app
 * credential, the credential-store key) and nothing else, then runs {@link
 * com.securityexpert.nexus.ui2.worker.confirm.WorkerClaimLoop} until the
 * process is signalled to stop.
 *
 * <p><b>Packaging (NXS-LOCAL-0158 closed this gap):</b> the deployed image's
 * fixed {@code ENTRYPOINT} runs one boot jar; {@code :worker} reaches it
 * only through {@code :service}'s {@code runtimeOnly(project(":worker"))}
 * classpath entry (a build-script edge, never a {@code service} source
 * import of a {@code worker} class -- DIR-2 stays intact). {@link
 * com.securityexpert.nexus.ui2.platform.launch.Ui2Launcher} is the boot
 * jar's actual Spring Boot {@code Start-Class}; when
 * {@code args[0] == "worker"} it resolves this class by name and invokes
 * this {@code main} with the remaining arguments (deploy/ui2/
 * 52-worker-deployment.yaml's own {@code args: ["worker"]}).</p>
 */
public final class Ui2WorkerMain {

    private Ui2WorkerMain() {
    }

    public static void main(String[] args) {
        if ("configuration".equalsIgnoreCase(System.getenv("NEXUS_WORKLOAD_ROLE"))
                || (args.length > 0 && "configuration".equalsIgnoreCase(args[0]))) {
            try {
                com.securityexpert.nexus.ui2.worker.configuration.server.Ui2ConfigurationMain.main(args);
                return;
            } catch (Exception e) {
                System.err.println("Failed to start ui2-configuration microservice: " + e.getMessage());
                System.exit(1);
            }
        }
        if ("compliance".equalsIgnoreCase(System.getenv("NEXUS_WORKLOAD_ROLE"))
                || (args.length > 0 && "compliance".equalsIgnoreCase(args[0]))) {
            try {
                com.securityexpert.nexus.ui2.worker.compliance.server.Ui2ComplianceMain.main(args);
                return;
            } catch (Exception e) {
                System.err.println("Failed to start ui2-compliance microservice: " + e.getMessage());
                System.exit(1);
            }
        }

        String jdbcUrl = requireEnv("UI2_DB_URL");
        String dbUser = SecretFile.readRequired(Path.of(requireEnv("UI2_DB_APP_USER_FILE")), "worker.db_user");
        String dbPassword = SecretFile.readRequired(Path.of(requireEnv("UI2_DB_APP_PASSWORD_FILE")), "worker.db_password");
        String credentialStoreKeyBase64 =
                SecretFile.readRequired(Path.of(requireEnv("UI2_CREDENTIAL_STORE_KEY_FILE")), "credential_store_key");
        String artefactStoreKeyBase64 =
                SecretFile.readRequired(Path.of(requireEnv("UI2_ARTEFACT_STORE_KEY_FILE")), "artefact_store_key");
        Path artefactStoreRoot = Path.of(System.getenv().getOrDefault("UI2_ARTEFACT_STORE_ROOT", "/var/lib/ui2/artefacts"));
        String hostnameFingerprintKeyBase64 = SecretFile.readRequired(
                Path.of(requireEnv("UI2_HOSTNAME_FINGERPRINT_KEY_FILE")), "hostname_fingerprint_key");
        String checkPointTrustRuleRef = System.getenv().getOrDefault("UI2_CP_TRUST_RULE_REF", "utils.cp_ssh_trust");
        String paloAltoTrustRuleRef =
                System.getenv().getOrDefault("UI2_PAN_TRUST_RULE_REF", "utils.pan_xml_api_trust");

        TransactionBoundary transactionBoundary = TransactionBoundaryFactory.fromJdbc(jdbcUrl, dbUser, dbPassword);

        CredentialStoreComposition.ResolverComponents resolverComponents =
                CredentialStoreComposition.resolverComponents(jdbcUrl, dbUser, dbPassword, credentialStoreKeyBase64);

        JobLeaseRepository leaseRepository = new PersistenceJobLeaseRepository(new JooqJobLeaseDao(transactionBoundary));
        JobStepAttemptRepository attemptRepository =
                new PersistenceJobStepAttemptRepository(new JooqJobStepAttemptDao(transactionBoundary));
        JooqDeviceRepository deviceRepository = new JooqDeviceRepository(transactionBoundary);
        PersistenceDeviceEnrollmentReadPort deviceEnrollmentReadPort = new PersistenceDeviceEnrollmentReadPort(deviceRepository);

        TrustRuleResolver enrolledDeviceTrustRuleResolver = trustRuleRef -> Optional.ofNullable(
                System.getenv("UI2_" + trustRuleRef.toUpperCase(java.util.Locale.ROOT).replace('.', '_')
                        + "_FINGERPRINT"));
        boolean allowTofu = !"false".equalsIgnoreCase(System.getenv("UI2_SSH_ALLOW_TOFU"));
        TrustRuleResolver trustRuleResolver = new com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver(
                new com.securityexpert.nexus.ui2.persistence.discovery.JooqManagementEndpointSshTrustRepository(transactionBoundary),
                enrolledDeviceTrustRuleResolver, allowTofu);
        StoreBackedSshCredentialResolver sshCredentialResolver =
                new StoreBackedSshCredentialResolver(resolverComponents.credentialReferenceRepository(),
                        resolverComponents.credentialRepository(), resolverComponents.cipher());
        SshExecTransport sshTransport = new SshExecTransport(sshCredentialResolver, trustRuleResolver);
        PanTrustRuleResolver panTrustRuleResolver = EnvironmentPanTrustRuleResolver.INSTANCE;
        PanXmlApiTransport panTransport = new PanXmlApiTransport(paloAltoTrustRuleRef, panTrustRuleResolver);

        // WORKER.md "Both vendors in one worker": one composite DeviceTransport
        // routes ssh_exec to sshTransport and pan_xml_api to panTransport; the
        // startup check (AC-11) fails fast if either capability's transport
        // kind were ever left unregistered.
        PersistenceGateRegistryPort gateRegistry = new PersistenceGateRegistryPort(new JooqGateRegistryDao(transactionBoundary));
        var capabilities = new java.util.ArrayList<com.securityexpert.nexus.ui2.capability.Capability>();
        capabilities.addAll(ConfirmCapabilities.all());
        capabilities.addAll(InventoryCapabilities.all(gateRegistry));
        capabilities.addAll(ConfigurationCapabilities.all(gateRegistry));
        capabilities.addAll(BackupCapabilities.all(gateRegistry));
        capabilities.addAll(com.securityexpert.nexus.ui2.worker.discovery.DiscoveryCapabilities.all());
        CapabilityRegistry capabilityRegistry = CapabilityRegistry.of(capabilities);
        TransportRegistry transportRegistry = WorkerBootstrap.buildTransportRegistry(sshTransport, panTransport);
        WorkerBootstrap.boot(capabilityRegistry, transportRegistry);
        CompositeDeviceTransport compositeTransport = new CompositeDeviceTransport(transportRegistry);

        StoreBackedPanCredentialResolver panCredentialResolver =
                new StoreBackedPanCredentialResolver(resolverComponents.credentialReferenceRepository(),
                        resolverComponents.credentialRepository(), resolverComponents.cipher());

        ConfirmCapabilityExecutor checkPointConfirmExecutor =
                new ConfirmCapabilityExecutor(compositeTransport, panCredentialResolver);
        ConfirmJobExecutor confirmJobExecutor = new ConfirmJobExecutor(leaseRepository, attemptRepository,
                deviceEnrollmentReadPort, deviceRepository, checkPointConfirmExecutor,
                new PeerFollowResolver(checkPointConfirmExecutor));

        DeviceInventoryRepository deviceInventoryRepository = new JooqDeviceInventoryRepository(transactionBoundary);
        InventoryCapabilityExecutor inventoryCapabilityExecutor =
                new InventoryCapabilityExecutor(compositeTransport, panCredentialResolver);
        InventoryJobExecutor inventoryJobExecutor = new InventoryJobExecutor(leaseRepository, attemptRepository,
                deviceEnrollmentReadPort, deviceRepository, deviceInventoryRepository, inventoryCapabilityExecutor,
                new com.securityexpert.nexus.ui2.persistence.device.JooqDevicePlatformFactsRepository(transactionBoundary))
                .withPolicyInstallRepository(
                        new com.securityexpert.nexus.ui2.persistence.device.JooqDevicePolicyInstallRepository(transactionBoundary));

        ArtefactStore artefactStore =
                new FileArtefactStore(artefactStoreRoot, ArtefactStoreCipher.fromBase64Key(artefactStoreKeyBase64));
        DeviceConfigurationRepository deviceConfigurationRepository =
                new JooqDeviceConfigurationRepository(transactionBoundary);
        BackupArtefactManifestRepository backupArtefactManifestRepository =
                new JooqBackupArtefactManifestRepository(transactionBoundary);
        HostnameFingerprint hostnameFingerprint = HostnameFingerprint.fromBase64Key(hostnameFingerprintKeyBase64);
        JooqConfigurationNotificationRepository configurationNotificationRepository =
                new JooqConfigurationNotificationRepository(transactionBoundary);
        ConfigurationCapabilityExecutor configurationCapabilityExecutor = new ConfigurationCapabilityExecutor(
                compositeTransport, panCredentialResolver, artefactStore, PanoramaCrossCheckPort.NONE);
        ConfigurationJobExecutor configurationJobExecutor = new ConfigurationJobExecutor(leaseRepository,
                attemptRepository, deviceEnrollmentReadPort, deviceRepository, deviceConfigurationRepository,
                configurationNotificationRepository, configurationCapabilityExecutor, backupArtefactManifestRepository,
                hostnameFingerprint, artefactStoreRoot.toString());

        // 14H BK-11: the distinct backup credential reference (a
        // credential_references id, resolved through the same
        // StoreBackedSshCredentialResolver stack -- never the device's own
        // collection credential). Empty means unconfigured: the executor
        // fails closed before any device contact, and BackupCollectService
        // (service module) refuses admission for the same reason.
        Optional<String> backupCredentialRef =
                Optional.ofNullable(System.getenv("UI2_CP_BACKUP_CREDENTIAL_REF")).filter(value -> !value.isBlank());
        long backupFreeSpaceThresholdBytes = Long.parseLong(
                System.getenv().getOrDefault("UI2_BACKUP_FREE_SPACE_THRESHOLD_BYTES", "104857600"));
        Duration backupPollInterval = Duration.ofSeconds(
                Long.parseLong(System.getenv().getOrDefault("UI2_BACKUP_POLL_INTERVAL_SECONDS", "10")));
        Duration backupRunDeadline = Duration.ofSeconds(
                Long.parseLong(System.getenv().getOrDefault("UI2_BACKUP_RUN_DEADLINE_SECONDS", "1800")));
        BackupEndpointEligibilityRepository backupEndpointEligibilityRepository =
                new JooqBackupEndpointEligibilityRepository(transactionBoundary);
        BackupCapabilityExecutor backupCapabilityExecutor = new BackupCapabilityExecutor(compositeTransport,
                artefactStore, backupFreeSpaceThresholdBytes, backupPollInterval, backupRunDeadline,
                new com.securityexpert.nexus.ui2.persistence.artefact.JooqDeviceArchiveLedger(transactionBoundary));
        com.securityexpert.nexus.ui2.worker.backup.cp.CheckPointSnapshotExecutor snapshotExecutor =
                new com.securityexpert.nexus.ui2.worker.backup.cp.CheckPointSnapshotExecutor(compositeTransport,
                        artefactStore, backupFreeSpaceThresholdBytes, backupPollInterval, backupRunDeadline);
        com.securityexpert.nexus.ui2.worker.backup.pan.PaloAltoBackupExecutor paloAltoBackupExecutor =
                new com.securityexpert.nexus.ui2.worker.backup.pan.PaloAltoBackupExecutor(compositeTransport, artefactStore, panCredentialResolver);
        com.securityexpert.nexus.ui2.worker.backup.diff.SemanticDeviationEngine deviationEngine =
                new com.securityexpert.nexus.ui2.worker.backup.diff.SemanticDeviationEngine();

        com.securityexpert.nexus.ui2.persistence.artefact.content.ArchiveContentListingService contentListing =
                new com.securityexpert.nexus.ui2.persistence.artefact.content.ArchiveContentListingService(artefactStore,
                        new com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactEntryRepository(
                                transactionBoundary));
        BackupJobExecutor backupJobExecutor = new BackupJobExecutor(leaseRepository, attemptRepository,
                deviceEnrollmentReadPort, deviceRepository, backupCapabilityExecutor, snapshotExecutor,
                paloAltoBackupExecutor, deviationEngine, null, backupArtefactManifestRepository,
                backupEndpointEligibilityRepository, hostnameFingerprint, artefactStoreRoot.toString(), contentListing);
        // V61: MDS export -- mds_backup of the whole server; its run time on this estate is still to be measured, so
        // the deadline is generous and configurable (UI2_MDS_EXPORT_RUN_DEADLINE_SECONDS).
        backupJobExecutor.withMdsExportExecutor(new com.securityexpert.nexus.ui2.worker.backup.cp.MdsExportExecutor(compositeTransport,
                artefactStore,
                Long.parseLong(System.getenv().getOrDefault("UI2_MDS_EXPORT_MIN_FREE_BYTES", String.valueOf(20L * 1024 * 1024 * 1024))),
                Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("UI2_MDS_EXPORT_POLL_INTERVAL_SECONDS", "30"))),
                Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("UI2_MDS_EXPORT_RUN_DEADLINE_SECONDS", "14400")))));

        DiscoveryRunRepository discoveryRunRepository = new JooqDiscoveryRunRepository(transactionBoundary);
        MgmtCliEnumerationAdapter checkPointDiscoveryAdapter =
                new MgmtCliEnumerationAdapter(compositeTransport, sshCredentialResolver);
        PanoramaEnumerationAdapter paloAltoDiscoveryAdapter =
                new PanoramaEnumerationAdapter(compositeTransport, panCredentialResolver, panTrustRuleResolver);
        DiscoveryJobExecutor discoveryJobExecutor = new DiscoveryJobExecutor(leaseRepository, attemptRepository,
                discoveryRunRepository, checkPointDiscoveryAdapter, paloAltoDiscoveryAdapter);

        JobRecordDao jobRecordDao = new JooqJobRecordDao(transactionBoundary);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(10);
        java.util.List<WorkerClaimLoop> claimLoops = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            WorkerClaimLoop claimLoop = new WorkerClaimLoop(leaseRepository, jobRecordDao, deviceRepository,
                    confirmJobExecutor, inventoryJobExecutor, configurationJobExecutor, discoveryJobExecutor,
                    backupJobExecutor, "worker-" + UUID.randomUUID(), Duration.ofMinutes(10), checkPointTrustRuleRef,
                    paloAltoTrustRuleRef, backupCredentialRef);
            claimLoops.add(claimLoop);
            executor.submit(() -> claimLoop.runUntilInterrupted(Duration.ofSeconds(2)));
        }

        // Graceful drain on SIGTERM (a rollout): stop claiming, finish what is running, up to
        // UI2_WORKER_DRAIN_SECONDS (default 840 s -- the deployment's terminationGracePeriodSeconds
        // is 900). Measured live 2026-09-22: without this, a rollout mid fleet-backup left jobs
        // EXECUTING on a dead lease until the reconciler requeued them and failed others with a
        // closed SSH session.
        long drainSeconds = Long.parseLong(System.getenv().getOrDefault("UI2_WORKER_DRAIN_SECONDS", "840"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.Logger log = System.getLogger(Ui2WorkerMain.class.getName());
            log.log(System.Logger.Level.INFO, "[WORKER_DRAIN] stop requested; finishing in-flight jobs (up to "
                    + drainSeconds + " s)");
            claimLoops.forEach(WorkerClaimLoop::requestStop);
            executor.shutdown();
            try {
                boolean drained = executor.awaitTermination(drainSeconds, java.util.concurrent.TimeUnit.SECONDS);
                log.log(System.Logger.Level.INFO, "[WORKER_DRAIN] " + (drained ? "all in-flight jobs finished"
                        : "drain window elapsed with jobs still running; the reconciler will requeue them"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "worker-drain"));

        com.securityexpert.nexus.ui2.jobs.executor.JobReconciler reconciler =
                new com.securityexpert.nexus.ui2.jobs.executor.JobReconciler(leaseRepository);
        java.util.concurrent.ScheduledExecutorService reconcilerExecutor =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "job-reconciler");
                    t.setDaemon(true);
                    return t;
                });
        reconcilerExecutor.scheduleWithFixedDelay(() -> {
            try {
                reconciler.reconcileOnce();
            } catch (Throwable t) {
                System.getLogger(Ui2WorkerMain.class.getName())
                        .log(System.Logger.Level.WARNING, "Periodic job reconciliation error: " + t.getMessage(), t);
            }
        }, 10, 30, java.util.concurrent.TimeUnit.SECONDS);

        // V44: retention pruning against the operator's policy row, hourly. Until now the executor was
        // composed with a null pruning service, so the 14-day horizon the screen showed never ran.
        com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository backupPolicyRepository =
                new com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupPolicyRepository(transactionBoundary);
        com.securityexpert.nexus.ui2.worker.backup.retention.RetentionPruningService pruningService =
                new com.securityexpert.nexus.ui2.worker.backup.retention.RetentionPruningService(
                        new com.securityexpert.nexus.ui2.worker.backup.retention.JooqRetentionStorePort(
                                new com.securityexpert.nexus.ui2.persistence.artefact.JooqRetentionQueries(transactionBoundary),
                                artefactStoreRoot));
        reconcilerExecutor.scheduleWithFixedDelay(() -> {
            try {
                backupPolicyRepository.find().ifPresent(policy -> {
                    var summary = pruningService.prune(
                            new com.securityexpert.nexus.ui2.worker.backup.retention.RetentionPruningService.PruningPolicy(
                                    policy.backupRetentionDays(), policy.snapshotRetentionDepth()));
                    if (summary.backupsPruned() > 0 || summary.snapshotsPruned() > 0) {
                        System.getLogger(Ui2WorkerMain.class.getName()).log(System.Logger.Level.INFO,
                                "[RETENTION] pruned backups=" + summary.backupsPruned() + " snapshots=" + summary.snapshotsPruned()
                                        + " bytes=" + summary.bytesReclaimed() + " (retention " + policy.backupRetentionDays()
                                        + " d, depth " + policy.snapshotRetentionDepth() + ")");
                    }
                });
            } catch (Throwable t) {
                System.getLogger(Ui2WorkerMain.class.getName())
                        .log(System.Logger.Level.WARNING, "Retention pruning error: " + t.getMessage(), t);
            }
        }, 120, 3600, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }
}
