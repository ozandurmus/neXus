package com.securityexpert.nexus.ui2.worker;

import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

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
        // Last-line masking of IPv4/IPv6 literals in java.util.logging output (NEXUS_LOG_RAW_ADDRESSES=1 is set by the live manifests, PO 2026-11-15).
        com.securityexpert.nexus.ui2.platform.logging.MaskingFormatter.install();
        com.securityexpert.nexus.ui2.platform.JobWindowPolicy.SYSTEM.logPolicy();
        String role = resolveRole(args, OverwatchEnv.get("NEXUS_WORKLOAD_ROLE"));
        // Metrics (docs/operations/METRICS.md): the scrape server starts only when UI2_METRICS_PORT is set.
        com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.startFromEnvironment(role);
        boolean policyFallback = Boolean.parseBoolean(System.getProperty("ui2.worker.claim-policy-fallback", "false"));
        boolean backupFallback = Boolean.parseBoolean(
                System.getProperty("ui2.worker.claim-backup-fallback", !"backup".equals(role) ? "true" : "false"));
        if ("configuration".equals(role)) {
            try {
                com.securityexpert.nexus.ui2.worker.configuration.server.Ui2ConfigurationMain.main(args);
                return;
            } catch (Exception e) {
                System.getLogger(Ui2WorkerMain.class.getName()).log(System.Logger.Level.ERROR,
                        "Failed to start ui2-configuration microservice: " + e.getMessage(), e);
                System.exit(1);
            }
        }
        if ("compliance".equals(role)) {
            try {
                com.securityexpert.nexus.ui2.worker.compliance.server.Ui2ComplianceMain.main(args);
                return;
            } catch (Exception e) {
                System.getLogger(Ui2WorkerMain.class.getName()).log(System.Logger.Level.ERROR,
                        "Failed to start ui2-compliance microservice: " + e.getMessage(), e);
                System.exit(1);
            }
        }

        // Kubelet probes (deploy/ui2 startupProbe/livenessProbe/readinessProbe): /livez and /readyz on UI2_HEALTH_PORT
        // (default 9405), independent of UI2_METRICS_ENABLED. Liveness reads in-process loop timestamps only; readiness
        // reads the outcome of the module heartbeat below. Started before the ownership wait so a waiting pod is live.
        com.securityexpert.nexus.ui2.worker.health.WorkerHealth health = com.securityexpert.nexus.ui2.worker.health.WorkerHealth.fromEnvironment(OverwatchEnv::get);
        com.securityexpert.nexus.ui2.worker.health.WorkerHealthServer.startFromEnvironment(OverwatchEnv::get, health);

        if (com.securityexpert.nexus.ui2.platform.PacketAnalysisJob.MODULE.equals(role)) {
            // The analysis worker needs the database and the analysis volume only: no credential store, no artefact
            // store, no device transport (decision record 2026-10-15 §4).
            startPacketAnalysis(health);
            return;
        }
        if (com.securityexpert.nexus.ui2.platform.ScriptExecutionJob.MODULE.equals(role)) {
            com.securityexpert.nexus.ui2.worker.scriptrunner.Ui2ScriptRunnerMain.run(health);
            return;
        }

        String jdbcUrl = requireEnv("UI2_DB_URL");
        String dbUser = SecretFile.readRequired(Path.of(requireEnv("UI2_DB_APP_USER_FILE")), "worker.db_user");
        String dbPassword = SecretFile.readRequired(Path.of(requireEnv("UI2_DB_APP_PASSWORD_FILE")), "worker.db_password");
        String credentialStoreKeyBase64 =
                SecretFile.readRequired(Path.of(requireEnv("UI2_CREDENTIAL_STORE_KEY_FILE")), "credential_store_key");
        String artefactStoreKeyBase64 =
                SecretFile.readRequired(Path.of(requireEnv("UI2_ARTEFACT_STORE_KEY_FILE")), "artefact_store_key");
        Path artefactStoreRoot = Path.of(OverwatchEnv.getOrDefault("UI2_ARTEFACT_STORE_ROOT", "/var/lib/ui2/artefacts"));
        // Every role, the policy pod included (PO decision 2026-10-18), needs the product fingerprint key: a missing
        // variable, file or empty value stops the pod at start (fail closed). The policy change evidence stores
        // admin identities as HMACs under this key, never as a masked length.
        String hostnameFingerprintKeyBase64 = SecretFile.readRequired(
                Path.of(requireEnv("UI2_HOSTNAME_FINGERPRINT_KEY_FILE")), "hostname_fingerprint_key");
        String checkPointTrustRuleRef = OverwatchEnv.getOrDefault("UI2_CP_TRUST_RULE_REF", "utils.cp_ssh_trust");
        String paloAltoTrustRuleRef =
                OverwatchEnv.getOrDefault("UI2_PAN_TRUST_RULE_REF", "utils.pan_xml_api_trust");

        if ("policy".equals(role)) System.setProperty("ui2.db.pool.maximum-pool-size", "6");
        if ("backup".equals(role)) {
            System.setProperty("ui2.db.pool.maximum-pool-size",
                    System.getProperty("ui2.db.pool.maximum-pool-size", "4"));
            System.setProperty("ui2.db.pool.minimum-idle",
                    System.getProperty("ui2.db.pool.minimum-idle", "1"));
        }
        var databasePool = WorkerDatabasePool.create(
                TransactionBoundaryFactory.jdbcDataSource(jdbcUrl, dbUser, dbPassword), System.getProperties());
        TransactionBoundary transactionBoundary = TransactionBoundaryFactory.fromDataSource(databasePool);

        CredentialStoreComposition.ResolverComponents resolverComponents =
                CredentialStoreComposition.resolverComponents(transactionBoundary, credentialStoreKeyBase64);

        JobLeaseRepository leaseRepository = new PersistenceJobLeaseRepository(new JooqJobLeaseDao(transactionBoundary));
        JobStepAttemptRepository attemptRepository =
                new PersistenceJobStepAttemptRepository(new JooqJobStepAttemptDao(transactionBoundary));
        JooqDeviceRepository deviceRepository = new JooqDeviceRepository(transactionBoundary);
        PersistenceDeviceEnrollmentReadPort deviceEnrollmentReadPort = new PersistenceDeviceEnrollmentReadPort(deviceRepository);

        TrustRuleResolver enrolledDeviceTrustRuleResolver = trustRuleRef -> Optional.ofNullable(
                OverwatchEnv.get("UI2_" + trustRuleRef.toUpperCase(java.util.Locale.ROOT).replace('.', '_')
                        + "_FINGERPRINT"));
        boolean allowTofu = !"false".equalsIgnoreCase(OverwatchEnv.get("UI2_SSH_ALLOW_TOFU"));
        TrustRuleResolver trustRuleResolver = new com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver(
                new com.securityexpert.nexus.ui2.persistence.discovery.JooqManagementEndpointSshTrustRepository(transactionBoundary),
                enrolledDeviceTrustRuleResolver, allowTofu);
        StoreBackedSshCredentialResolver sshCredentialResolver =
                new StoreBackedSshCredentialResolver(resolverComponents.credentialReferenceRepository(),
                        resolverComponents.credentialRepository(), resolverComponents.cipher());
        var hostKeyPolicy = new com.securityexpert.nexus.ui2.persistence.discovery.SshHostKeyPolicyStore(transactionBoundary);
        SshExecTransport sshTransport = new SshExecTransport(sshCredentialResolver, trustRuleResolver, false, hostKeyPolicy);
        SshExecTransport strictFailoverSsh = new SshExecTransport(sshCredentialResolver,
                new com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver(
                        new com.securityexpert.nexus.ui2.persistence.discovery.JooqManagementEndpointSshTrustRepository(transactionBoundary),
                        enrolledDeviceTrustRuleResolver, false), true, hostKeyPolicy);
        PanTrustRuleResolver panTrustRuleResolver = EnvironmentPanTrustRuleResolver.INSTANCE;
        PanXmlApiTransport panTransport = new PanXmlApiTransport(paloAltoTrustRuleRef, panTrustRuleResolver);

        var endpointAdmission = new com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository(transactionBoundary);
        com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.install(endpointAdmission);
        var moduleRuntime = new com.securityexpert.nexus.ui2.persistence.runtime.ModuleRuntimeRepository(transactionBoundary);
        String instance = role + "-" + UUID.randomUUID();
        WorkerOwnershipWait.await(role, () -> health.observeHeartbeat(() -> moduleRuntime.heartbeat(role, instance)));
        if ("policy".equals(role)) {
            if (!moduleRuntime.compatibleGeneralLive()) throw new IllegalStateException("COMPATIBLE_GENERAL_BOOTSTRAP_REQUIRED");
            startPolicy(transactionBoundary, databasePool, moduleRuntime, endpointAdmission, instance, leaseRepository,
                attemptRepository, deviceRepository, strictFailoverSsh, panTransport, panCredentialResolverFor(resolverComponents),
                artefactStoreRoot, artefactStoreKeyBase64, HostnameFingerprint.fromBase64Key(hostnameFingerprintKeyBase64),
                checkPointTrustRuleRef, paloAltoTrustRuleRef, policyFallback, health);
            return;
        }
        if ("backup".equals(role)) {
            if (!moduleRuntime.compatibleGeneralLive()) throw new IllegalStateException("COMPATIBLE_GENERAL_BOOTSTRAP_REQUIRED");
            startBackup(transactionBoundary, databasePool, moduleRuntime, endpointAdmission, instance, leaseRepository,
                attemptRepository, deviceRepository, deviceEnrollmentReadPort, sshTransport, panTransport,
                panCredentialResolverFor(resolverComponents), artefactStoreRoot, artefactStoreKeyBase64,
                HostnameFingerprint.fromBase64Key(hostnameFingerprintKeyBase64), checkPointTrustRuleRef,
                paloAltoTrustRuleRef, backupFallback, health);
            return;
        }

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
        capabilities.add(com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates.capability(gateRegistry));
        CapabilityRegistry capabilityRegistry = CapabilityRegistry.of(capabilities);
        TransportRegistry transportRegistry = WorkerBootstrap.buildTransportRegistry(sshTransport, panTransport);
        WorkerBootstrap.boot(capabilityRegistry, transportRegistry);
        CompositeDeviceTransport compositeTransport = new CompositeDeviceTransport(transportRegistry);

        StoreBackedPanCredentialResolver panCredentialResolver =
                new StoreBackedPanCredentialResolver(resolverComponents.credentialReferenceRepository(),
                        resolverComponents.credentialRepository(), resolverComponents.cipher());

        ConfirmCapabilityExecutor checkPointConfirmExecutor =
                new ConfirmCapabilityExecutor(compositeTransport, panCredentialResolver);
        var platformFactsRepository = new com.securityexpert.nexus.ui2.persistence.device.JooqDevicePlatformFactsRepository(transactionBoundary);
        ConfirmJobExecutor confirmJobExecutor = new ConfirmJobExecutor(leaseRepository, attemptRepository,
                deviceEnrollmentReadPort, deviceRepository, checkPointConfirmExecutor,
                new PeerFollowResolver(checkPointConfirmExecutor)).withPlatformFacts(platformFactsRepository);

        FileArtefactStore fileArtefactStore =
                new FileArtefactStore(artefactStoreRoot, ArtefactStoreCipher.fromBase64Key(artefactStoreKeyBase64))
                        .withStoreQuotas(new com.securityexpert.nexus.ui2.persistence.artefact.JooqArtefactStoreQuotaRepository(
                                transactionBoundary)::findAll)
                        .withRefusalRecorder(new com.securityexpert.nexus.ui2.persistence.artefact.JooqArtefactStoreRefusalRecorder(
                                transactionBoundary, "worker", com.securityexpert.nexus.ui2.platform.WorkerActor.RESERVED_ACTOR_FINGERPRINT));
        com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.registerArtefactStoreBytes(fileArtefactStore::cachedUsedBytes);
        ArtefactStore artefactStore = fileArtefactStore;
        DeviceInventoryRepository deviceInventoryRepository = new JooqDeviceInventoryRepository(transactionBoundary);
        // PO 2026-10-24: a PAN-OS 12 firewall's interfaces come from its newest stored effective-running configuration.
        InventoryCapabilityExecutor inventoryCapabilityExecutor = new InventoryCapabilityExecutor(compositeTransport,
                panCredentialResolver, com.securityexpert.nexus.ui2.jobs.pan.PanFirewallCommandSwitches.fromEnvironment(),
                com.securityexpert.nexus.ui2.worker.inventory.PanConfiguredInterfaceSource.stored(
                        new com.securityexpert.nexus.ui2.persistence.device.configuration.LatestEffectiveRunningReader(
                                transactionBoundary), artefactStore));
        // Lifecycle reads, groups A and B (PO 2026-11-10): measure-first, off unless UI2_LIFECYCLE_READS and UI2_LIFECYCLE_READ_DEVICES name them.
        com.securityexpert.nexus.ui2.worker.inventory.LifecycleReadSwitch lifecycleReads =
                com.securityexpert.nexus.ui2.worker.inventory.LifecycleReadSwitch.fromEnvironment();
        inventoryCapabilityExecutor.withLifecycleReads(lifecycleReads);
        // Contract LIFECYCLE_LICENSE_STORAGE_CONTRACT section 3: each answer is parsed in memory and stored as facts; the raw
        // answer is dropped. With the switch off no executor ever calls the sink.
        com.securityexpert.nexus.ui2.worker.inventory.LifecycleResultSink lifecycleResults =
                new com.securityexpert.nexus.ui2.worker.inventory.PersistingLifecycleResultSink(
                        new com.securityexpert.nexus.ui2.persistence.lifecycle.JooqDeviceLifecycleRepository(transactionBoundary),
                        platformFactsRepository);
        inventoryCapabilityExecutor.withLifecycleResults(lifecycleResults);
        InventoryJobExecutor inventoryJobExecutor = new InventoryJobExecutor(leaseRepository, attemptRepository,
                deviceEnrollmentReadPort, deviceRepository, deviceInventoryRepository, inventoryCapabilityExecutor,
                platformFactsRepository)
                .withPolicyInstallRepository(
                        new com.securityexpert.nexus.ui2.persistence.device.JooqDevicePolicyInstallRepository(transactionBoundary))
                // V229: the zone names of each virtual system, parsed from the configuration the interfaces come from.
                .withPanZoneRepository(
                        new com.securityexpert.nexus.ui2.persistence.device.JooqPanVsysZoneRepository(transactionBoundary))
                // V246: the Check Point base/Jumbo install dates from the device's install log.
                .withInstallEventRepository(
                        new com.securityexpert.nexus.ui2.persistence.device.JooqDeviceInstallEventRepository(transactionBoundary));

        DeviceConfigurationRepository deviceConfigurationRepository =
                new JooqDeviceConfigurationRepository(transactionBoundary);
        BackupArtefactManifestRepository backupArtefactManifestRepository =
                new JooqBackupArtefactManifestRepository(transactionBoundary);
        HostnameFingerprint hostnameFingerprint = HostnameFingerprint.fromBase64Key(hostnameFingerprintKeyBase64);
        JooqConfigurationNotificationRepository configurationNotificationRepository =
                new JooqConfigurationNotificationRepository(transactionBoundary);
        ConfigurationCapabilityExecutor configurationCapabilityExecutor = new ConfigurationCapabilityExecutor(
                compositeTransport, panCredentialResolver, artefactStore, PanoramaCrossCheckPort.NONE,
                com.securityexpert.nexus.ui2.worker.configuration.server.ConfigurationServiceClient.fromEnvironment(),
                deviceRepository);
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
                Optional.ofNullable(OverwatchEnv.get("UI2_CP_BACKUP_CREDENTIAL_REF")).filter(value -> !value.isBlank());
        long backupFreeSpaceThresholdBytes = Long.parseLong(
                OverwatchEnv.getOrDefault("UI2_BACKUP_FREE_SPACE_THRESHOLD_BYTES", "104857600"));
        Duration backupPollInterval = Duration.ofSeconds(
                Long.parseLong(OverwatchEnv.getOrDefault("UI2_BACKUP_POLL_INTERVAL_SECONDS", "10")));
        Duration backupRunDeadline = Duration.ofSeconds(
                Long.parseLong(OverwatchEnv.getOrDefault("UI2_BACKUP_RUN_DEADLINE_SECONDS", "1800")));
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
                paloAltoBackupExecutor, deviationEngine, backupArtefactManifestRepository,
                backupEndpointEligibilityRepository, hostnameFingerprint, artefactStoreRoot.toString(), contentListing);
        backupJobExecutor.withPlatformFacts(platformFactsRepository);
        backupJobExecutor.withManifestRecordedHook(asaRelationLinker(transactionBoundary));
        // V180: backup size anomaly at ingest; retention keeps the last good backup until a security_admin accepts it.
        backupJobExecutor.withSizeAnomalyCheck(
                new com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupSizeAnomalyRepository(transactionBoundary));
        // V207: the credential failure alert (first authentication failure of a backup credential; early stop; canary).
        var credentialFailureAlerts = new com.securityexpert.nexus.ui2.persistence.artefact.JooqCredentialFailureAlertRepository(transactionBoundary);
        backupJobExecutor.withCredentialAlerts(credentialFailureAlerts);
        // V64: vendors backed up over HTTPS (Infoblox, Radware) -- credentials resolved like the PAN API password.
        var httpsCertificateTrust = new com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateTrustRepository(transactionBoundary);
        com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorExecutor httpsVendorExecutor =
                new com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorExecutor(
                        new com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient((target, certificate) ->
                                httpsCertificateTrust.observe(target.host(), target.port(), certificate)), artefactStore, ref -> {
                            var m = panCredentialResolver.resolve(ref);
                            return new com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Credentials(m.username(), m.password());
                        });
        // Cisco ASA: SSH interactive shell, through the same vendor jobs (CISCO_ASA_CONTRACT.md).
        // Fortinet (FORTINET_CONTRACT.md): FortiGate over SSH, FortiManager over JSON-RPC.
        com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiManagerExecutor fortiManagerExecutor = httpsVendorExecutor.newFortiManagerExecutor()
                .withSsh(sshTransport).withLifecycleReads(lifecycleReads).withLifecycleResults(lifecycleResults);
        httpsVendorExecutor.withFortinet(new com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiGateExecutor(sshTransport, artefactStore)
                        .withSecrets(ref -> {
                            var m = panCredentialResolver.resolve(ref);
                            return new com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiGateExecutor.LoginSecret(m.username(), m.password());
                        })
                        .withLifecycleReads(lifecycleReads).withLifecycleResults(lifecycleResults),
                fortiManagerExecutor);
        httpsVendorExecutor.withLifecycleReads(lifecycleReads).withLifecycleResults(lifecycleResults);
        // PO 2026-11-24: the Management Center managed-device health read (GET /api/devices/status) -- off unless
        // UI2_MC_DEVICE_HEALTH_READ is set, and even then never sent while its gate row is absent (health recorded UNKNOWN).
        httpsVendorExecutor.withMcDeviceHealth(com.securityexpert.nexus.ui2.worker.backup.https.McDeviceHealthRead.fromEnvironment(gateRegistry));
        httpsVendorExecutor.withCiscoAsa(new com.securityexpert.nexus.ui2.worker.backup.asa.CiscoAsaExecutor(sshTransport, artefactStore, sshTransport::scpFetch)
                .withLifecycleReads(lifecycleReads).withLifecycleResults(lifecycleResults)
                .withConfigRelationSink(asaRelationSink(transactionBoundary))
                .withFailoverMeasure(com.securityexpert.nexus.ui2.worker.backup.asa.AsaFailoverMeasure.fromEnvironment()));
        configurationCapabilityExecutor.withHttpsVendorExecutor(httpsVendorExecutor);
        backupJobExecutor.withHttpsVendorExecutor(httpsVendorExecutor,
                new com.securityexpert.nexus.ui2.persistence.device.JooqDeviceSecretReferenceRepository(transactionBoundary));
        com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorConfirmJobExecutor httpsConfirmJobExecutor =
                new com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorConfirmJobExecutor(leaseRepository, attemptRepository,
                        deviceEnrollmentReadPort, deviceRepository, httpsVendorExecutor);
        // PO 2026-09-25: inventory for HTTPS vendors is its own job -- grid members, interfaces and routes (Infoblox), the
        // managed device list (Radware Cyber Controller); backups only back up.
        com.securityexpert.nexus.ui2.worker.backup.https.HttpsInventoryJobExecutor httpsInventoryJobExecutor =
                new com.securityexpert.nexus.ui2.worker.backup.https.HttpsInventoryJobExecutor(leaseRepository, attemptRepository,
                        deviceEnrollmentReadPort, deviceRepository, deviceInventoryRepository, httpsVendorExecutor)
                        .withFailoverIdentity(platformFactsRepository,
                                new com.securityexpert.nexus.ui2.persistence.device.JooqHaPairIdentityRepository(transactionBoundary));
        // V69: a Radware Cyber Controller's own configuration backup, pushed to HOST-A's chrooted SFTP receiver, whose
        // upload directory is mounted here (UI2_CC_INBOX_DIR) and whose address the Cyber Controller dials (UI2_CC_RECEIVER_HOST).
        String ccInbox = OverwatchEnv.get("UI2_CC_INBOX_DIR");
        backupJobExecutor.withCyberControllerBackupExecutor(new com.securityexpert.nexus.ui2.worker.backup.radware.CyberControllerBackupExecutor(
                compositeTransport, artefactStore, ccInbox == null || ccInbox.isBlank() ? null : java.nio.file.Path.of(ccInbox),
                OverwatchEnv.get("UI2_CC_RECEIVER_HOST"), ref -> panCredentialResolver.resolve(ref).password()));
        backupJobExecutor.withQuantumSparkBackupExecutor(new com.securityexpert.nexus.ui2.worker.backup.cp.QuantumSparkBackupExecutor(
                compositeTransport, artefactStore, ccInbox == null || ccInbox.isBlank() ? null : java.nio.file.Path.of(ccInbox),
                OverwatchEnv.get("UI2_CC_RECEIVER_HOST"), ref -> panCredentialResolver.resolve(ref).password()));
        // V61: MDS export -- mds_backup of the whole server. The wait for its exit code is capped at 45 min (PO
        // 2026-11-11, MDS fork storm fix contract C.1: five times the usual 8-9 min, 1.6 times the longest measured 28 min);
        // bundling, digest and fetch keep their own timeouts. UI2_MDS_EXPORT_RUN_DEADLINE_SECONDS overrides it.
        backupJobExecutor.withMdsExportExecutor(new com.securityexpert.nexus.ui2.worker.backup.cp.MdsExportExecutor(compositeTransport,
                artefactStore,
                Long.parseLong(OverwatchEnv.getOrDefault("UI2_MDS_EXPORT_MIN_FREE_BYTES", String.valueOf(20L * 1024 * 1024 * 1024))),
                Duration.ofSeconds(Long.parseLong(OverwatchEnv.getOrDefault("UI2_MDS_EXPORT_POLL_INTERVAL_SECONDS", "30"))),
                Duration.ofSeconds(Long.parseLong(OverwatchEnv.getOrDefault("UI2_MDS_EXPORT_RUN_DEADLINE_SECONDS", "2700")))));

        DiscoveryRunRepository discoveryRunRepository = new JooqDiscoveryRunRepository(transactionBoundary);
        var policyCollectionRepository = new com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository(transactionBoundary);
        var policyDeadline = Duration.ofSeconds(Long.parseLong(OverwatchEnv.getOrDefault("UI2_POLICY_RUN_DEADLINE_SECONDS", "7200")));
        var policyCollector = new com.securityexpert.nexus.ui2.worker.policy.CheckPointPolicyCollector(strictFailoverSsh, gateRegistry, policyCollectionRepository, policyDeadline);
        var completenessSettings = com.securityexpert.nexus.ui2.worker.policy.PolicyCompletenessSettings.fromEnvironment();
        var completenessEvidence = new com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository(transactionBoundary);
        var policyCollectionExecutor = new com.securityexpert.nexus.ui2.worker.policy.PolicyCollectionJobExecutor(
                leaseRepository, attemptRepository, discoveryRunRepository, policyCollectionRepository, policyCollector, gateRegistry)
                .withPanorama(policyPanorama(compositeTransport, gateRegistry, panCredentialResolver, policyCollectionRepository, policyDeadline,
                        completenessSettings, completenessEvidence, hostnameFingerprint,
                        new com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository(transactionBoundary)))
                .withCompleteness(policyCompleteness(transactionBoundary, completenessSettings, policyCollector, gateRegistry,
                        completenessEvidence, policyCollectionRepository, hostnameFingerprint));
        MgmtCliEnumerationAdapter checkPointDiscoveryAdapter =
                new MgmtCliEnumerationAdapter(compositeTransport, sshCredentialResolver);
        PanoramaEnumerationAdapter paloAltoDiscoveryAdapter =
                new PanoramaEnumerationAdapter(compositeTransport, panCredentialResolver, panTrustRuleResolver);
        DiscoveryJobExecutor discoveryJobExecutor = new DiscoveryJobExecutor(leaseRepository, attemptRepository,
                discoveryRunRepository, checkPointDiscoveryAdapter, paloAltoDiscoveryAdapter)
                .withPolicyCollection(policyCollectionExecutor::afterDiscovery)
                .withRadwareCyberController(httpsVendorExecutor)
                .withFortiManager(fortiManagerExecutor);

        JobRecordDao jobRecordDao = new JooqJobRecordDao(transactionBoundary);
        backupJobExecutor.withTranscript(artefactStore, jobRecordDao);
        policyCollectionExecutor.withTranscript(artefactStore, jobRecordDao);
        var diagnosticJobExecutor = new com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiManagerDiagnosticJobExecutor(
                leaseRepository, attemptRepository, deviceEnrollmentReadPort, deviceInventoryRepository,
                jobRecordDao, fortiManagerExecutor, gateRegistry);
        var genericDiagnosticExecutor = new com.securityexpert.nexus.ui2.worker.diagnostic.DiagnosticJobExecutor(
                leaseRepository, attemptRepository, deviceRepository, jobRecordDao, strictFailoverSsh, gateRegistry, artefactStore,
                platformFactsRepository, deviceInventoryRepository)
                .withSyslogMeasurement(new com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogMeasurementRepository(
                        transactionBoundary), hostnameFingerprint);
        var panSyslogMeasureExecutor = new com.securityexpert.nexus.ui2.worker.diagnostic.PanSyslogMeasureJobExecutor(
                leaseRepository, attemptRepository, deviceRepository, panTransport, panCredentialResolver, gateRegistry,
                new com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogMeasurementRepository(transactionBoundary));
        // System-log programme phase B (Palo Alto): incremental system/config log collection into the encrypted artefact store.
        var panSystemLogCollectExecutor = new com.securityexpert.nexus.ui2.worker.diagnostic.PanSystemLogCollectJobExecutor(
                leaseRepository, attemptRepository, deviceRepository, panTransport, panCredentialResolver, gateRegistry,
                new com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogCollectionRepository(transactionBoundary),
                artefactStore, new com.securityexpert.nexus.ui2.worker.diagnostic.SystemLogArtefactVolume.FileSystem(artefactStoreRoot),
                hostnameFingerprint);
        // System-log programme phase B (Check Point): one trusted SSH session per device, C1/C2/C3 every window, the
        // daily sar and HCP files in the 06:00 window. Lines are redacted (LogRedactor, rule set cp-text-v1) before encryption.
        var systemLogRepository = new com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogCollectionRepository(transactionBoundary);
        var systemLogVolume = new com.securityexpert.nexus.ui2.worker.diagnostic.SystemLogArtefactVolume.FileSystem(artefactStoreRoot);
        var cpSystemLogCollectExecutor = new com.securityexpert.nexus.ui2.worker.diagnostic.CpSystemLogCollectJobExecutor(
                leaseRepository, attemptRepository, deviceRepository, strictFailoverSsh, gateRegistry, systemLogRepository,
                artefactStore, systemLogVolume, hostnameFingerprint, platformFactsRepository,
                com.securityexpert.nexus.ui2.worker.diagnostic.ChunkRedactor.of(
                        new com.securityexpert.nexus.ui2.worker.systemlog.redaction.LogRedactor()));
        // Support bundle collection (V230, step S2): Check Point light items over the same trusted SSH session.
        var cpSupportBundleExecutor = new com.securityexpert.nexus.ui2.worker.supportbundle.CpSupportBundleJobExecutor(
                leaseRepository, attemptRepository, deviceRepository, strictFailoverSsh, gateRegistry,
                new com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore(transactionBoundary),
                artefactStore, hostnameFingerprint);
        // Support bundle collection, steps S3-S5 (V237): the Palo Alto tech support file over the XML API (the existing keygen),
        // the Cisco ASA and FortiGate session captures over the same trusted SSH session.
        var supportBundleStore = new com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore(transactionBoundary);
        var panSupportBundleExecutor = new com.securityexpert.nexus.ui2.worker.supportbundle.PanSupportBundleJobExecutor(
                leaseRepository, attemptRepository, deviceRepository, panTransport, panCredentialResolver, gateRegistry,
                supportBundleStore, artefactStore, hostnameFingerprint);
        var asaSupportBundleExecutor = new com.securityexpert.nexus.ui2.worker.supportbundle.AsaSupportBundleJobExecutor(
                leaseRepository, attemptRepository, deviceRepository, strictFailoverSsh, gateRegistry, supportBundleStore,
                artefactStore, hostnameFingerprint);
        var fgtSupportBundleExecutor = new com.securityexpert.nexus.ui2.worker.supportbundle.FgtSupportBundleJobExecutor(
                leaseRepository, attemptRepository, deviceRepository, strictFailoverSsh, gateRegistry, supportBundleStore,
                artefactStore, hostnameFingerprint);
        // The one-off Spike Detective layout review (contract section 8.3; PO approval 2026-10-19): C4 and one C5 on the
        // same trusted SSH session; only a numeric, name-class summary is stored (system_log_measurement).
        var cpSpikeLayoutReviewExecutor = new com.securityexpert.nexus.ui2.worker.diagnostic.CpSpikeLayoutReviewJobExecutor(
                leaseRepository, attemptRepository, deviceRepository, strictFailoverSsh, gateRegistry,
                new com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogMeasurementRepository(transactionBoundary),
                hostnameFingerprint, platformFactsRepository);
        // Path analysis stage A6: the approved vendor matchers for one computed hop (V188), trusted SSH or the PAN API key.
        var pathValidationExecutor = new com.securityexpert.nexus.ui2.worker.pathvalidation.PathValidationJobExecutor(
                leaseRepository, attemptRepository, deviceRepository, deviceInventoryRepository, platformFactsRepository,
                strictFailoverSsh, panTransport, panCredentialResolver, gateRegistry,
                new com.securityexpert.nexus.ui2.persistence.pathvalidation.JooqPathValidationRepository(transactionBoundary));
        // Operations static route phase R0 (V252): approved CLASS_0 reads, Stage B pre-checks, before-path lookup, and command preview.
        var staticRoutePreviewExecutor = new com.securityexpert.nexus.ui2.worker.staticroute.StaticRoutePreviewJobExecutor(
                leaseRepository, attemptRepository, deviceRepository, deviceInventoryRepository, platformFactsRepository,
                strictFailoverSsh, panTransport, panCredentialResolver, gateRegistry,
                new com.securityexpert.nexus.ui2.persistence.staticroute.JooqStaticRoutePreviewRepository(transactionBoundary));
        var credentialCanaryExecutor = new com.securityexpert.nexus.ui2.worker.backup.CredentialCanaryJobExecutor(
                leaseRepository, attemptRepository, deviceEnrollmentReadPort, compositeTransport,
                new com.securityexpert.nexus.ui2.worker.backup.CredentialFailureReporter(credentialFailureAlerts));
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(10);
        java.util.List<WorkerClaimLoop> claimLoops = new java.util.ArrayList<>();
        var failoverRecovery = new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository(transactionBoundary);
        failoverRecovery.reconcileDispatches();
        // CP failover one shell per member per pass (docs/design/CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md): off by default.
        var cpShellMode = com.securityexpert.nexus.ui2.worker.failover.CpFailoverShellMode.fromEnvironment();
        // The exec path keeps its own pause; NEXUS_CP_FAILOVER_COMMAND_PAUSE_MS only affects shell-path runs (review P1-6).
        Duration cpCommandPause = Duration.ofSeconds(Long.parseLong(
                OverwatchEnv.getOrDefault("UI2_FAILOVER_COMMAND_PAUSE_SECONDS", "2")));
        System.getLogger(Ui2WorkerMain.class.getName()).log(System.Logger.Level.INFO,
                "[CP_FAILOVER] " + cpShellMode.toString() + " execPauseMs=" + cpCommandPause.toMillis());
        // Cisco ASA failover readiness (PO_DECISION_REQUEST_2026_11_13_ASA_FAILOVER_READINESS.md): off unless
        // UI2_ASA_FAILOVER_READINESS=on, read once here; readiness only.
        var asaReadinessSwitch = com.securityexpert.nexus.ui2.jobs.failover.AsaFailoverReadinessSwitch.fromEnvironment();
        var haPairIdentities = new com.securityexpert.nexus.ui2.persistence.device.JooqHaPairIdentityRepository(transactionBoundary);
        System.getLogger(Ui2WorkerMain.class.getName()).log(System.Logger.Level.INFO,
                "[ASA_FAILOVER] readiness " + (asaReadinessSwitch.enabled() ? "on" : "off"));
        var fgtReadinessSwitch = com.securityexpert.nexus.ui2.jobs.failover.FgtFailoverReadinessSwitch.fromEnvironment();
        System.getLogger(Ui2WorkerMain.class.getName()).log(System.Logger.Level.INFO,
                "[FGT_FAILOVER] readiness " + (fgtReadinessSwitch.enabled() ? "on" : "off"));
        for (int i = 0; i < 10; i++) {
            var cpFailoverExecutor = new com.securityexpert.nexus.ui2.worker.failover.CpFailoverJobExecutor(
                    new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository(transactionBoundary),
                    deviceRepository, leaseRepository, attemptRepository, strictFailoverSsh, gateRegistry,
                    d -> Thread.sleep(d.toMillis()), cpCommandPause,
                    com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch.fromEnvironment(), cpShellMode);
            var panFailoverExecutor = new com.securityexpert.nexus.ui2.worker.failover.PanFailoverJobExecutor(
                    new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository(transactionBoundary),
                    deviceRepository, leaseRepository, attemptRepository, panTransport, panCredentialResolver, gateRegistry,
                    d -> Thread.sleep(d.toMillis()), Duration.ofSeconds(Long.parseLong(
                        OverwatchEnv.getOrDefault("UI2_FAILOVER_COMMAND_PAUSE_SECONDS", "2"))));
            var asaFailoverExecutor = new com.securityexpert.nexus.ui2.worker.failover.AsaFailoverJobExecutor(
                    new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository(transactionBoundary),
                    deviceRepository, leaseRepository, attemptRepository, strictFailoverSsh, gateRegistry,
                    haPairIdentities, asaReadinessSwitch);
            WorkerClaimLoop claimLoop = new WorkerClaimLoop(leaseRepository, jobRecordDao, deviceRepository,
                    confirmJobExecutor, inventoryJobExecutor, configurationJobExecutor, discoveryJobExecutor,
                    backupJobExecutor, instance, Duration.ofMinutes(10), checkPointTrustRuleRef,
                    paloAltoTrustRuleRef, backupCredentialRef)
                    .withHttpsConfirm(httpsConfirmJobExecutor)
                    .withHttpsInventory(httpsInventoryJobExecutor)
                    .withFortiManagerDiagnostic(diagnosticJobExecutor)
                    .withCpFailover(cpFailoverExecutor)
                    .withPanFailover(panFailoverExecutor)
                    .withAsaFailover(asaFailoverExecutor)
                    .withFgtFailover(new com.securityexpert.nexus.ui2.worker.failover.FgtFailoverJobExecutor(
                        new com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository(transactionBoundary),
                        deviceRepository, leaseRepository, attemptRepository, strictFailoverSsh, gateRegistry,
                        new com.securityexpert.nexus.ui2.persistence.device.JooqDevicePlatformFactsRepository(transactionBoundary),
                        fgtReadinessSwitch, Duration.ofSeconds(Long.parseLong(
                            OverwatchEnv.getOrDefault("UI2_FAILOVER_COMMAND_PAUSE_SECONDS", "2")))))
                    .withPanConfigSync(new com.securityexpert.nexus.ui2.worker.failover.PanConfigSyncJobExecutor(
                        new com.securityexpert.nexus.ui2.persistence.JooqPanConfigSyncRepository(transactionBoundary),
                        deviceRepository, leaseRepository, attemptRepository, panTransport, panCredentialResolver, gateRegistry))
                    .withDiagnosticReads(genericDiagnosticExecutor)
                    .withPanSyslogMeasure(panSyslogMeasureExecutor)
                    .withPanSystemLogCollect(panSystemLogCollectExecutor)
                    .withCpSystemLogCollect(cpSystemLogCollectExecutor)
                    .withCpSupportBundle(cpSupportBundleExecutor)
                    .withPanSupportBundle(panSupportBundleExecutor)
                    .withAsaSupportBundle(asaSupportBundleExecutor)
                    .withFgtSupportBundle(fgtSupportBundleExecutor)
                    .withSupportBundleAdmission(supportBundleStore)
                    .withCpSpikeLayoutReview(cpSpikeLayoutReviewExecutor)
                    .withCredentialCanary(credentialCanaryExecutor)
                    .withPathValidation(pathValidationExecutor)
                    .withStaticRoutePreview(staticRoutePreviewExecutor)
                    .withPolicyCollection(policyCollectionExecutor)
                    .withRole(role, policyFallback, backupFallback)
                    .withRuntimeAdmission(endpointAdmission, moduleRuntime)
                    .withLiveness(health.register("general-" + i));
            claimLoop.withPlatformFacts(platformFactsRepository);
            claimLoops.add(claimLoop);
            executor.submit(() -> claimLoop.runUntilInterrupted(Duration.ofSeconds(2)));
        }

        com.securityexpert.nexus.ui2.jobs.executor.JobReconciler reconciler =
                new com.securityexpert.nexus.ui2.jobs.executor.JobReconciler(leaseRepository);
        java.util.concurrent.ScheduledExecutorService reconcilerExecutor =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "job-reconciler");
                    t.setDaemon(true);
                    return t;
                });
        var ownerHeartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        ownerHeartbeat.scheduleWithFixedDelay(() -> {
            try {
                if (databasePool.getHikariPoolMXBean() != null) {
                    var stats = databasePool.getHikariPoolMXBean();
                    moduleRuntime.poolStats(role, instance, stats.getActiveConnections(), stats.getIdleConnections(),
                        stats.getThreadsAwaitingConnection(), WorkerDatabasePool.TIMEOUTS.sum(), WorkerDatabasePool.WAIT_NANOS.sum());
                }
                if (!health.observeHeartbeat(() -> moduleRuntime.heartbeat(role, instance))) {
                    health.markDraining();
                    claimLoops.forEach(WorkerClaimLoop::requestStop); executor.shutdown(); ownerHeartbeat.shutdown();
                }
            } catch (RuntimeException unavailable) {
                System.getLogger(Ui2WorkerMain.class.getName()).log(System.Logger.Level.WARNING, "MODULE_HEARTBEAT_UNAVAILABLE");
            }
        }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
        // SNMP device metrics (SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md): read-only SNMPv3 polling of the pilot vendors,
        // idle until an admin turns the fleet switch on (Administration > SNMP polling).
        var snmpPoller = com.securityexpert.nexus.ui2.worker.snmp.SnmpPollerRuntime.start(transactionBoundary, gateRegistry,
                resolverComponents);
        health.markStarted();

        // Graceful drain on SIGTERM (a rollout): stop claiming, finish what is running, up to
        // UI2_WORKER_DRAIN_SECONDS (default 840 s -- the deployment's terminationGracePeriodSeconds
        // is 900). Measured live 2026-09-22: without this, a rollout mid fleet-backup left jobs
        // EXECUTING on a dead lease until the reconciler requeued them and failed others with a
        // closed SSH session.
        long drainSeconds = Long.parseLong(OverwatchEnv.getOrDefault("UI2_WORKER_DRAIN_SECONDS", "840"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.Logger log = System.getLogger(Ui2WorkerMain.class.getName());
            log.log(System.Logger.Level.INFO, "[WORKER_DRAIN] stop requested; finishing in-flight jobs (up to "
                    + drainSeconds + " s)");
            // A draining pod stays live (the kubelet must not kill the drain) and leaves readiness.
            health.markDraining();
            claimLoops.forEach(WorkerClaimLoop::requestStop);
            snmpPoller.close();
            executor.shutdown();
            reconcilerExecutor.shutdown();
            try {
                long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(drainSeconds);
                boolean drained = executor.awaitTermination(drainSeconds, java.util.concurrent.TimeUnit.SECONDS);
                drained &= reconcilerExecutor.awaitTermination(Math.max(0, until-System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
                log.log(System.Logger.Level.INFO, "[WORKER_DRAIN] " + (drained ? "all in-flight jobs finished"
                        : "drain window elapsed with jobs still running; the reconciler will requeue them"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                ownerHeartbeat.shutdownNow();
                try { moduleRuntime.releaseOwnership(role, instance); }
                finally { databasePool.close(); }
            }
        }, "worker-drain"));

        reconcilerExecutor.scheduleWithFixedDelay(() -> {
            try {
                moduleRuntime.runTask(role, instance, "JobReconciler.reconcileOnce", () -> {
                    failoverRecovery.reconcileDispatches();
                    // A two-person resume request left unapproved past its 30 minutes becomes EXPIRED (bounded sweep);
                    // a sweep failure never holds back the job reconciler.
                    try { failoverRecovery.expireResumeRequests(); }
                    catch (RuntimeException sweep) {
                        System.getLogger(Ui2WorkerMain.class.getName())
                                .log(System.Logger.Level.WARNING, "Resume expiry sweep error: " + sweep.getMessage());
                    }
                    reconciler.reconcileOnce();
                });
            } catch (Throwable t) {
                System.getLogger(Ui2WorkerMain.class.getName())
                        .log(System.Logger.Level.WARNING, "Periodic job reconciliation error: " + t.getMessage(), t);
            }
        }, 10, 30, java.util.concurrent.TimeUnit.SECONDS);

        // V70: deletion requests for backups whose device no longer exists (Backups screen), carried out every minute.
        com.securityexpert.nexus.ui2.worker.backup.retention.OrphanArtefactPurger orphanPurger =
                new com.securityexpert.nexus.ui2.worker.backup.retention.OrphanArtefactPurger(
                        new com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactDeletionRequests(transactionBoundary), artefactStoreRoot);
        reconcilerExecutor.scheduleWithFixedDelay(() -> {
            try {
                moduleRuntime.runTask(role, instance, "OrphanArtefactPurger.runOnce", orphanPurger::runOnce);
            } catch (Throwable t) {
                System.getLogger(Ui2WorkerMain.class.getName())
                        .log(System.Logger.Level.WARNING, "Orphan backup purge error: " + t.getMessage(), t);
            }
        }, 20, 60, java.util.concurrent.TimeUnit.SECONDS);

        // V44: retention pruning against the operator's policy row, hourly (PO decision 2026-10-18: the policy row is
        // the only source of the days; backup days, snapshot depth, and from V164 the configuration days).
        com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository backupPolicyRepository =
                new com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupPolicyRepository(transactionBoundary);
        com.securityexpert.nexus.ui2.worker.backup.retention.RetentionPruningService pruningService =
                new com.securityexpert.nexus.ui2.worker.backup.retention.RetentionPruningService(
                        new com.securityexpert.nexus.ui2.worker.backup.retention.JooqRetentionStorePort(
                                new com.securityexpert.nexus.ui2.persistence.artefact.JooqRetentionQueries(transactionBoundary),
                                artefactStoreRoot, reportStoreRootOrNull()));
        reconcilerExecutor.scheduleWithFixedDelay(() -> {
            try {
                moduleRuntime.runTask(role, instance, "RetentionPruningService.prune", () -> backupPolicyRepository.find().ifPresent(policy -> {
                    var summary = pruningService.prune(
                            new com.securityexpert.nexus.ui2.worker.backup.retention.RetentionPruningService.PruningPolicy(
                                    policy.backupRetentionDays(), policy.snapshotRetentionDepth(),
                                    policy.configurationRetentionDays()));
                    if (summary.backupsPruned() > 0 || summary.snapshotsPruned() > 0 || summary.configurationsPruned() > 0
                            || summary.reportsPruned() > 0 || summary.supportBundlesPruned() > 0) {
                        System.getLogger(Ui2WorkerMain.class.getName()).log(System.Logger.Level.INFO,
                                "[RETENTION] pruned backups=" + summary.backupsPruned() + " snapshots=" + summary.snapshotsPruned()
                                        + " configurations=" + summary.configurationsPruned()
                                        + " reports=" + summary.reportsPruned()
                                        + " support_bundles=" + summary.supportBundlesPruned()
                                        + " bytes=" + summary.bytesReclaimed() + " (retention " + policy.backupRetentionDays()
                                        + " d, depth " + policy.snapshotRetentionDepth() + ", configuration "
                                        + policy.configurationRetentionDays() + " d)");
                    }
                }));
            } catch (Throwable t) {
                System.getLogger(Ui2WorkerMain.class.getName())
                        .log(System.Logger.Level.WARNING, "Retention pruning error: " + t.getMessage(), t);
            }
        }, 120, 3600, java.util.concurrent.TimeUnit.SECONDS);

        // System-log retention (phase B contract section 5.3): checked hourly, run once a day, whether or not
        // collection is enabled -- raw 30 days, HCP 7 days, the per-device cap with ledgered early deletion, ledger rows.
        var systemLogPruner = new com.securityexpert.nexus.ui2.worker.diagnostic.SystemLogRetentionPruner(
                new com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogRetentionRepository(transactionBoundary),
                new com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogCollectionRepository(transactionBoundary)::settings,
                artefactStoreRoot, java.time.Clock.systemUTC(),
                com.securityexpert.nexus.ui2.worker.diagnostic.SystemLogRetentionPruner.parseSpikeCapOverride(
                        OverwatchEnv.get(com.securityexpert.nexus.ui2.worker.diagnostic.SystemLogRetentionPruner.SPIKE_CAP_ENV)));
        reconcilerExecutor.scheduleWithFixedDelay(() -> {
            try {
                moduleRuntime.runTask(role, instance, "SystemLogRetentionPruner.runIfDue", systemLogPruner::runIfDue);
            } catch (Throwable t) {
                System.getLogger(Ui2WorkerMain.class.getName())
                        .log(System.Logger.Level.WARNING, "System-log retention error: " + t.getClass().getSimpleName());
            }
        }, 300, 3600, java.util.concurrent.TimeUnit.SECONDS);

        // V262 (PO 2026-11-23): unreferenced policy chunk generations, hourly, outside the job windows, on its own
        // thread so its paced batches never hold back the job reconciler. UI2_POLICY_CHUNK_GC=off stops it.
        if (!"off".equalsIgnoreCase(OverwatchEnv.getOrDefault("UI2_POLICY_CHUNK_GC", "on"))) {
            var chunkGc = new com.securityexpert.nexus.ui2.persistence.policy.PolicyChunkGarbageCollector(transactionBoundary,
                    policyChunkGcSettings());
            var chunkGcExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "policy-chunk-gc");
                t.setDaemon(true);
                return t;
            });
            chunkGcExecutor.scheduleWithFixedDelay(() -> {
                try {
                    if (com.securityexpert.nexus.ui2.platform.JobWindowPolicy.SYSTEM.isOpen()) return;
                    moduleRuntime.runTask(role, instance, "PolicyChunkGarbageCollector.runOnce", chunkGc::runOnce);
                } catch (Throwable t) {
                    System.getLogger(Ui2WorkerMain.class.getName())
                            .log(System.Logger.Level.WARNING, "Policy chunk collection error: " + t.getClass().getSimpleName());
                }
            }, 900, 3600, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** Batch size and count are tunable for the first pass; the 24 h safety age can only be raised. */
    static com.securityexpert.nexus.ui2.persistence.policy.PolicyChunkGarbageCollector.Settings policyChunkGcSettings() {
        var defaults = com.securityexpert.nexus.ui2.persistence.policy.PolicyChunkGarbageCollector.Settings.defaults();
        return new com.securityexpert.nexus.ui2.persistence.policy.PolicyChunkGarbageCollector.Settings(
                Duration.ofHours(Long.parseLong(OverwatchEnv.getOrDefault("UI2_POLICY_CHUNK_GC_SAFETY_HOURS",
                        String.valueOf(defaults.safetyAge().toHours())))),
                Integer.parseInt(OverwatchEnv.getOrDefault("UI2_POLICY_CHUNK_GC_BATCH_ROWS", String.valueOf(defaults.batchRows()))),
                Integer.parseInt(OverwatchEnv.getOrDefault("UI2_POLICY_CHUNK_GC_MAX_BATCHES", String.valueOf(defaults.maxBatches()))),
                defaults.pause(), defaults.statementTimeout());
    }

    private static com.securityexpert.nexus.ui2.worker.backup.asa.AsaConfigRelationSink asaRelationSink(TransactionBoundary tx) {
        var repository = new com.securityexpert.nexus.ui2.persistence.device.JooqAsaConfigRelationRepository(tx);
        return (deviceId, storageRef, r) -> repository.record(storageRef, deviceId, r.result().name(), r.linesCompared(),
                r.linesDiffering(), r.linesEqualOnlyByMask(), r.differingKeywords());
    }

    /** Re-keys the relation row of a stored backup to the manifest's artefact id once the manifest row exists. */
    private static java.util.function.BiConsumer<String, String> asaRelationLinker(TransactionBoundary tx) {
        var repository = new com.securityexpert.nexus.ui2.persistence.device.JooqAsaConfigRelationRepository(tx);
        return repository::linkManifest;
    }

    static String resolveRole(String[] args, String environment) {
        String argument = args.length == 0 ? null : args[0].toLowerCase(java.util.Locale.ROOT);
        String env = environment == null || environment.isBlank() ? null : environment.toLowerCase(java.util.Locale.ROOT);
        if (args.length > 1 || (argument != null && env != null && !argument.equals(env)))
            throw new IllegalArgumentException("CONFLICTING_WORKER_ROLE");
        String role = argument != null ? argument : env != null ? env : "general";
        if (!java.util.Set.of("general", "policy", "backup", "configuration", "compliance",
                com.securityexpert.nexus.ui2.platform.PacketAnalysisJob.MODULE,
                com.securityexpert.nexus.ui2.platform.ScriptExecutionJob.MODULE).contains(role))
            throw new IllegalArgumentException("UNKNOWN_WORKER_ROLE");
        return role;
    }

    private static StoreBackedPanCredentialResolver panCredentialResolverFor(CredentialStoreComposition.ResolverComponents components) {
        return new StoreBackedPanCredentialResolver(components.credentialReferenceRepository(), components.credentialRepository(), components.cipher());
    }

    /**
     * The Panorama policy collector with the approved completeness readers (P-1, P-2, P-3, P-4) attached. Every one is OFF
     * unless its trial setting names its target (PolicyCompletenessSettings); with no setting this equals the plain collector.
     */
    private static com.securityexpert.nexus.ui2.worker.policy.PanoramaPolicyCollector policyPanorama(
            com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport transport,
            com.securityexpert.nexus.ui2.capability.GateRegistryPort gates,
            com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver credentials,
            com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository policies, Duration deadline,
            com.securityexpert.nexus.ui2.worker.policy.PolicyCompletenessSettings settings,
            com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository evidence, HostnameFingerprint fingerprint,
            com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository actors) {
        var windows = com.securityexpert.nexus.ui2.platform.JobWindowPolicy.SYSTEM;
        var collector = new com.securityexpert.nexus.ui2.worker.policy.PanoramaPolicyCollector(transport, gates, credentials, policies, deadline)
            .withCompleteness(settings, windows, new com.securityexpert.nexus.ui2.worker.diagnostic.PanoramaConfigLogReader(
                transport, gates, evidence, policies, settings, windows, fingerprint));
        // The join needs the product key (rule references are keyed hashes); without it the screen keeps UNKNOWN.
        return fingerprint == null ? collector : collector.withActorResolver(
            new com.securityexpert.nexus.ui2.worker.diagnostic.PolicyChangeActorResolver(actors, fingerprint));
    }

    /** Revision rows per collection and the off-by-default Check Point change reader (C-1, C-2). */
    private static com.securityexpert.nexus.ui2.worker.policy.PolicyCompletenessCoordinator policyCompleteness(TransactionBoundary boundary,
            com.securityexpert.nexus.ui2.worker.policy.PolicyCompletenessSettings settings,
            com.securityexpert.nexus.ui2.worker.policy.CheckPointPolicyCollector collector,
            com.securityexpert.nexus.ui2.capability.GateRegistryPort gates,
            com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository evidence,
            com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository policies, HostnameFingerprint fingerprint) {
        return com.securityexpert.nexus.ui2.worker.policy.PolicyCompletenessCoordinator.create(settings,
            com.securityexpert.nexus.ui2.platform.JobWindowPolicy.SYSTEM, collector, gates, evidence, policies,
            new com.securityexpert.nexus.ui2.worker.policy.PolicyRevisionRecorder(
                new com.securityexpert.nexus.ui2.persistence.policy.PolicyRevisionRepository(boundary)), fingerprint);
    }

    private static void startPolicy(TransactionBoundary boundary, com.zaxxer.hikari.HikariDataSource pool,
            com.securityexpert.nexus.ui2.persistence.runtime.ModuleRuntimeRepository runtime,
            com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository admission, String instance,
            JobLeaseRepository leases, JobStepAttemptRepository attempts, JooqDeviceRepository devices,
            SshExecTransport ssh, PanXmlApiTransport pan, StoreBackedPanCredentialResolver credentials,
            Path root, String key, HostnameFingerprint fingerprint, String cpTrust, String panTrust, boolean fallback,
            com.securityexpert.nexus.ui2.worker.health.WorkerHealth health) {
        var gates = new PersistenceGateRegistryPort(new JooqGateRegistryDao(boundary));
        var runs = new JooqDiscoveryRunRepository(boundary);
        var policies = new com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository(boundary);
        var deadline = Duration.ofSeconds(Long.parseLong(OverwatchEnv.getOrDefault("UI2_POLICY_RUN_DEADLINE_SECONDS", "7200")));
        var store = new FileArtefactStore(root, ArtefactStoreCipher.fromBase64Key(key))
                .withStoreQuotas(new com.securityexpert.nexus.ui2.persistence.artefact.JooqArtefactStoreQuotaRepository(boundary)::findAll)
                .withRefusalRecorder(new com.securityexpert.nexus.ui2.persistence.artefact.JooqArtefactStoreRefusalRecorder(
                        boundary, "policy_worker", com.securityexpert.nexus.ui2.platform.WorkerActor.RESERVED_ACTOR_FINGERPRINT));
        com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.registerArtefactStoreBytes(store::cachedUsedBytes);
        var jobs = new JooqJobRecordDao(boundary);
        var transport = new CompositeDeviceTransport(WorkerBootstrap.buildTransportRegistry(ssh, pan));
        var cpCollector = new com.securityexpert.nexus.ui2.worker.policy.CheckPointPolicyCollector(ssh, gates, policies, deadline);
        var completenessSettings = com.securityexpert.nexus.ui2.worker.policy.PolicyCompletenessSettings.fromEnvironment();
        var completenessEvidence = new com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository(boundary);
        var policy = new com.securityexpert.nexus.ui2.worker.policy.PolicyCollectionJobExecutor(leases, attempts, runs, policies, cpCollector, gates)
            .withPanorama(policyPanorama(transport, gates, credentials, policies, deadline, completenessSettings, completenessEvidence, fingerprint,
                new com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository(boundary)))
            .withCompleteness(policyCompleteness(boundary, completenessSettings, cpCollector, gates, completenessEvidence, policies, fingerprint))
            .withTranscript(store, jobs);
        // Policy composition has no enrollment, backup, inventory, configuration or failover executors.
        var loop = WorkerClaimLoop.policy(leases, jobs, devices, policy, instance, cpTrust, panTrust, fallback)
            .withRuntimeAdmission(admission, runtime)
            .withLiveness(health.register("policy"));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        executor.submit(() -> loop.runUntilInterrupted(Duration.ofSeconds(2)));
        var heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleWithFixedDelay(() -> {
            try {
                if (pool.getHikariPoolMXBean() != null) {
                    var stats = pool.getHikariPoolMXBean();
                    runtime.poolStats("policy", instance, stats.getActiveConnections(), stats.getIdleConnections(),
                        stats.getThreadsAwaitingConnection(), WorkerDatabasePool.TIMEOUTS.sum(), WorkerDatabasePool.WAIT_NANOS.sum());
                }
                if (!health.observeHeartbeat(() -> runtime.heartbeat("policy", instance))) {
                    health.markDraining(); loop.requestStop(); executor.shutdown(); heartbeat.shutdown();
                }
            }
            catch (RuntimeException unavailable) {
                System.getLogger(Ui2WorkerMain.class.getName()).log(System.Logger.Level.WARNING, "MODULE_HEARTBEAT_UNAVAILABLE");
            }
        }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
        // Same knob as the general worker (default 840 s = terminationGracePeriodSeconds minus a margin); parsed at startup
        // so a malformed value fails here, never inside the shutdown hook.
        long drainSeconds = Long.parseLong(OverwatchEnv.getOrDefault("UI2_WORKER_DRAIN_SECONDS", "840"));
        health.markStarted();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            health.markDraining();
            loop.requestStop();
            executor.shutdown();
            try { executor.awaitTermination(drainSeconds, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally {
                heartbeat.shutdownNow();
                try { runtime.releaseOwnership("policy", instance); }
                finally { pool.close(); }
            }
        }, "policy-drain"));
    }

    private static void startBackup(TransactionBoundary boundary, com.zaxxer.hikari.HikariDataSource pool,
            com.securityexpert.nexus.ui2.persistence.runtime.ModuleRuntimeRepository runtime,
            com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository admission, String instance,
            JobLeaseRepository leases, JobStepAttemptRepository attempts, JooqDeviceRepository devices,
            PersistenceDeviceEnrollmentReadPort enrollment, SshExecTransport ssh, PanXmlApiTransport pan,
            StoreBackedPanCredentialResolver credentials, Path root, String key, HostnameFingerprint fingerprint,
            String cpTrust, String panTrust, boolean fallback, com.securityexpert.nexus.ui2.worker.health.WorkerHealth health) {
        var gates = new PersistenceGateRegistryPort(new JooqGateRegistryDao(boundary));
        var transportRegistry = WorkerBootstrap.buildTransportRegistry(ssh, pan);
        WorkerBootstrap.boot(CapabilityRegistry.of(BackupCapabilities.all(gates)), transportRegistry);
        var transport = new CompositeDeviceTransport(transportRegistry);
        var store = new FileArtefactStore(root, ArtefactStoreCipher.fromBase64Key(key));
        com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.registerArtefactStoreBytes(store::cachedUsedBytes);
        var platformFacts = new com.securityexpert.nexus.ui2.persistence.device.JooqDevicePlatformFactsRepository(boundary);
        var backupCredentialRef = Optional.ofNullable(OverwatchEnv.get("UI2_CP_BACKUP_CREDENTIAL_REF")).filter(v -> !v.isBlank());
        long freeSpaceThreshold = Long.parseLong(OverwatchEnv.getOrDefault("UI2_BACKUP_FREE_SPACE_THRESHOLD_BYTES", "104857600"));
        var pollInterval = Duration.ofSeconds(Long.parseLong(OverwatchEnv.getOrDefault("UI2_BACKUP_POLL_INTERVAL_SECONDS", "10")));
        var runDeadline = Duration.ofSeconds(Long.parseLong(OverwatchEnv.getOrDefault("UI2_BACKUP_RUN_DEADLINE_SECONDS", "1800")));
        var capabilityExecutor = new BackupCapabilityExecutor(transport, store, freeSpaceThreshold, pollInterval, runDeadline,
                new com.securityexpert.nexus.ui2.persistence.artefact.JooqDeviceArchiveLedger(boundary));
        var snapshotExecutor = new com.securityexpert.nexus.ui2.worker.backup.cp.CheckPointSnapshotExecutor(
                transport, store, freeSpaceThreshold, pollInterval, runDeadline);
        var panBackupExecutor = new com.securityexpert.nexus.ui2.worker.backup.pan.PaloAltoBackupExecutor(transport, store, credentials);
        var contentListing = new com.securityexpert.nexus.ui2.persistence.artefact.content.ArchiveContentListingService(
                store, new com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactEntryRepository(boundary));
        var backup = new BackupJobExecutor(leases, attempts, enrollment, devices, capabilityExecutor, snapshotExecutor,
                panBackupExecutor, new com.securityexpert.nexus.ui2.worker.backup.diff.SemanticDeviationEngine(),
                new JooqBackupArtefactManifestRepository(boundary),
                new JooqBackupEndpointEligibilityRepository(boundary), fingerprint, root.toString(), contentListing);
        backup.withPlatformFacts(platformFacts);
        backup.withSizeAnomalyCheck(new com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupSizeAnomalyRepository(boundary));
        var credentialAlerts = new com.securityexpert.nexus.ui2.persistence.artefact.JooqCredentialFailureAlertRepository(boundary);
        backup.withCredentialAlerts(credentialAlerts);
        var httpsTrust = new com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateTrustRepository(boundary);
        var httpsVendor = new com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorExecutor(
                new com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient(
                        (target, cert) -> httpsTrust.observe(target.host(), target.port(), cert)),
                store, ref -> {
                    var m = credentials.resolve(ref);
                    return new com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Credentials(m.username(), m.password());
                });
        httpsVendor.withFortinet(new com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiGateExecutor(ssh, store)
                .withSecrets(ref -> {
                    var m = credentials.resolve(ref);
                    return new com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiGateExecutor.LoginSecret(m.username(), m.password());
                }), httpsVendor.newFortiManagerExecutor().withSsh(ssh));
        httpsVendor.withCiscoAsa(new com.securityexpert.nexus.ui2.worker.backup.asa.CiscoAsaExecutor(ssh, store, ssh::scpFetch));
        backup.withHttpsVendorExecutor(httpsVendor,
                new com.securityexpert.nexus.ui2.persistence.device.JooqDeviceSecretReferenceRepository(boundary));
        String ccInbox = OverwatchEnv.get("UI2_CC_INBOX_DIR");
        Path inboxPath = ccInbox == null || ccInbox.isBlank() ? null : Path.of(ccInbox);
        backup.withCyberControllerBackupExecutor(new com.securityexpert.nexus.ui2.worker.backup.radware.CyberControllerBackupExecutor(
                transport, store, inboxPath, OverwatchEnv.get("UI2_CC_RECEIVER_HOST"), ref -> credentials.resolve(ref).password()));
        backup.withQuantumSparkBackupExecutor(new com.securityexpert.nexus.ui2.worker.backup.cp.QuantumSparkBackupExecutor(
                transport, store, inboxPath, OverwatchEnv.get("UI2_CC_RECEIVER_HOST"), ref -> credentials.resolve(ref).password()));
        backup.withMdsExportExecutor(new com.securityexpert.nexus.ui2.worker.backup.cp.MdsExportExecutor(transport, store,
                Long.parseLong(OverwatchEnv.getOrDefault("UI2_MDS_EXPORT_MIN_FREE_BYTES", String.valueOf(20L * 1024 * 1024 * 1024))),
                Duration.ofSeconds(Long.parseLong(OverwatchEnv.getOrDefault("UI2_MDS_EXPORT_POLL_INTERVAL_SECONDS", "30"))),
                Duration.ofSeconds(Long.parseLong(OverwatchEnv.getOrDefault("UI2_MDS_EXPORT_RUN_DEADLINE_SECONDS", "2700")))));
        var jobs = new JooqJobRecordDao(boundary);
        backup.withTranscript(store, jobs);
        var canary = new com.securityexpert.nexus.ui2.worker.backup.CredentialCanaryJobExecutor(
                leases, attempts, enrollment, transport,
                new com.securityexpert.nexus.ui2.worker.backup.CredentialFailureReporter(credentialAlerts));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var loops = new java.util.ArrayList<WorkerClaimLoop>();
        for (int i = 0; i < 2; i++) {
            var loop = WorkerClaimLoop.backup(leases, jobs, devices, backup, instance, Duration.ofMinutes(10),
                    cpTrust, panTrust, backupCredentialRef, fallback)
                .withCredentialCanary(canary)
                .withPlatformFacts(platformFacts)
                .withRuntimeAdmission(admission, runtime)
                .withLiveness(health.register("backup-" + i));
            loops.add(loop);
            executor.submit(() -> loop.runUntilInterrupted(Duration.ofSeconds(2)));
        }
        var maintenance = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "backup-maintenance");
            t.setDaemon(true);
            return t;
        });
        var orphanPurger = new com.securityexpert.nexus.ui2.worker.backup.retention.OrphanArtefactPurger(
                new com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactDeletionRequests(boundary), root);
        maintenance.scheduleWithFixedDelay(() -> {
            try {
                runtime.runTask("backup", instance, "OrphanArtefactPurger.runOnce", orphanPurger::runOnce);
            } catch (Throwable t) {
                System.getLogger(Ui2WorkerMain.class.getName())
                        .log(System.Logger.Level.WARNING, "Orphan backup purge error: " + t.getMessage(), t);
            }
        }, 20, 60, java.util.concurrent.TimeUnit.SECONDS);
        var backupPolicyRepository = new com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupPolicyRepository(boundary);
        var pruningService = new com.securityexpert.nexus.ui2.worker.backup.retention.RetentionPruningService(
                new com.securityexpert.nexus.ui2.worker.backup.retention.JooqRetentionStorePort(
                        new com.securityexpert.nexus.ui2.persistence.artefact.JooqRetentionQueries(boundary),
                        root, reportStoreRootOrNull()));
        maintenance.scheduleWithFixedDelay(() -> {
            try {
                runtime.runTask("backup", instance, "RetentionPruningService.prune", () -> backupPolicyRepository.find().ifPresent(policy -> {
                    var summary = pruningService.prune(
                            new com.securityexpert.nexus.ui2.worker.backup.retention.RetentionPruningService.PruningPolicy(
                                    policy.backupRetentionDays(), policy.snapshotRetentionDepth(),
                                    policy.configurationRetentionDays()));
                    if (summary.backupsPruned() > 0 || summary.snapshotsPruned() > 0 || summary.configurationsPruned() > 0
                            || summary.reportsPruned() > 0) {
                        System.getLogger(Ui2WorkerMain.class.getName()).log(System.Logger.Level.INFO,
                                "[RETENTION] pruned backups=" + summary.backupsPruned() + " snapshots=" + summary.snapshotsPruned()
                                        + " configurations=" + summary.configurationsPruned()
                                        + " reports=" + summary.reportsPruned()
                                        + " bytes=" + summary.bytesReclaimed() + " (retention " + policy.backupRetentionDays()
                                        + " d, depth " + policy.snapshotRetentionDepth() + ", configuration "
                                        + policy.configurationRetentionDays() + " d)");
                    }
                }));
            } catch (Throwable t) {
                System.getLogger(Ui2WorkerMain.class.getName())
                        .log(System.Logger.Level.WARNING, "Retention pruning error: " + t.getMessage(), t);
            }
        }, 120, 3600, java.util.concurrent.TimeUnit.SECONDS);
        var heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleWithFixedDelay(() -> {
            try {
                if (pool.getHikariPoolMXBean() != null) {
                    var stats = pool.getHikariPoolMXBean();
                    runtime.poolStats("backup", instance, stats.getActiveConnections(), stats.getIdleConnections(),
                        stats.getThreadsAwaitingConnection(), WorkerDatabasePool.TIMEOUTS.sum(), WorkerDatabasePool.WAIT_NANOS.sum());
                }
                if (!health.observeHeartbeat(() -> runtime.heartbeat("backup", instance))) {
                    health.markDraining();
                    loops.forEach(WorkerClaimLoop::requestStop); executor.shutdown(); maintenance.shutdown(); heartbeat.shutdown();
                }
            } catch (RuntimeException unavailable) {
                System.getLogger(Ui2WorkerMain.class.getName()).log(System.Logger.Level.WARNING, "MODULE_HEARTBEAT_UNAVAILABLE");
            }
        }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
        health.markStarted();
        long drainSeconds = Long.parseLong(OverwatchEnv.getOrDefault("UI2_WORKER_DRAIN_SECONDS", "840"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            health.markDraining();
            loops.forEach(WorkerClaimLoop::requestStop);
            executor.shutdown();
            maintenance.shutdown();
            try {
                long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(drainSeconds);
                executor.awaitTermination(drainSeconds, java.util.concurrent.TimeUnit.SECONDS);
                maintenance.awaitTermination(Math.max(0, until - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally {
                heartbeat.shutdownNow();
                try { runtime.releaseOwnership("backup", instance); }
                finally { pool.close(); }
            }
        }, "backup-drain"));
    }

    /**
     * The {@code packet_analysis} module (deploy/ui2/58-packet-analysis-deployment.yaml): owns the
     * {@code packet_analysis} module row, claims {@code packet_analysis_run} only, one analysis at a time, and runs
     * the 7-day retention pruner under a runtime task lease.
     */
    private static void startPacketAnalysis(com.securityexpert.nexus.ui2.worker.health.WorkerHealth health) {
        String role = com.securityexpert.nexus.ui2.platform.PacketAnalysisJob.MODULE;
        String dbUser = SecretFile.readRequired(Path.of(requireEnv("UI2_DB_APP_USER_FILE")), "worker.db_user");
        String dbPassword = SecretFile.readRequired(Path.of(requireEnv("UI2_DB_APP_PASSWORD_FILE")), "worker.db_password");
        Path root = Path.of(OverwatchEnv.getOrDefault("UI2_PACKET_ANALYSIS_ROOT", "/app/packet-analysis"));
        long pruneSeconds = Long.parseLong(OverwatchEnv.getOrDefault("UI2_PACKET_ANALYSIS_PRUNE_INTERVAL_SECONDS", "3600"));
        System.setProperty("ui2.db.pool.maximum-pool-size", System.getProperty("ui2.db.pool.maximum-pool-size", "4"));
        // Memory plan: container = JVM heap (JAVA_TOOL_OPTIONS) + DuckDB limit + reserve; a DuckDB limit that does not fit is lowered.
        com.securityexpert.nexus.ui2.worker.packetanalysis.PacketAnalysisResources.apply(System.getenv(), System.getProperties());
        System.getLogger(Ui2WorkerMain.class.getName()).log(System.Logger.Level.INFO,
                "[PACKET_ANALYSIS] memory plan: " + com.securityexpert.nexus.ui2.worker.packetanalysis.PacketAnalysisResources.plan());
        var pool = WorkerDatabasePool.create(
                TransactionBoundaryFactory.jdbcDataSource(requireEnv("UI2_DB_URL"), dbUser, dbPassword), System.getProperties());
        TransactionBoundary boundary = TransactionBoundaryFactory.fromDataSource(pool);
        var runtime = new com.securityexpert.nexus.ui2.persistence.runtime.ModuleRuntimeRepository(boundary);
        String instance = role + "-" + UUID.randomUUID();
        WorkerOwnershipWait.await(role, () -> health.observeHeartbeat(() -> runtime.heartbeat(role, instance)));

        var leases = new PersistenceJobLeaseRepository(new JooqJobLeaseDao(boundary));
        var attempts = new PersistenceJobStepAttemptRepository(new JooqJobStepAttemptDao(boundary));
        var repository = new com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository(boundary);
        var engine = new com.securityexpert.nexus.ui2.worker.packetanalysis.RealPacketAnalysisEngine();
        var executor = new com.securityexpert.nexus.ui2.worker.packetanalysis.PacketAnalysisJobExecutor(
                leases, attempts, repository, engine, root);
        var loop = new com.securityexpert.nexus.ui2.worker.packetanalysis.PacketAnalysisClaimLoop(
                leases, new JooqJobRecordDao(boundary), executor, instance, Duration.ofMinutes(10))
                .withLiveness(health.register(role));
        var pruner = new com.securityexpert.nexus.ui2.worker.packetanalysis.PacketAnalysisRetentionPruner(repository, root);

        // One analysis at a time per pod: a single claiming thread.
        var claims = java.util.concurrent.Executors.newSingleThreadExecutor();
        claims.submit(() -> loop.runUntilInterrupted(Duration.ofSeconds(5)));
        var scheduled = java.util.concurrent.Executors.newScheduledThreadPool(2);
        scheduled.scheduleWithFixedDelay(() -> {
            try {
                if (pool.getHikariPoolMXBean() != null) {
                    var stats = pool.getHikariPoolMXBean();
                    runtime.poolStats(role, instance, stats.getActiveConnections(), stats.getIdleConnections(),
                        stats.getThreadsAwaitingConnection(), WorkerDatabasePool.TIMEOUTS.sum(), WorkerDatabasePool.WAIT_NANOS.sum());
                }
                if (!health.observeHeartbeat(() -> runtime.heartbeat(role, instance))) {
                    health.markDraining(); loop.requestStop(); claims.shutdown();
                }
            } catch (RuntimeException unavailable) {
                System.getLogger(Ui2WorkerMain.class.getName()).log(System.Logger.Level.WARNING, "MODULE_HEARTBEAT_UNAVAILABLE");
            }
        }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
        scheduled.scheduleWithFixedDelay(() -> {
            try {
                runtime.runTask(role, instance, "PacketAnalysisRetentionPruner.runOnce", pruner::runOnce);
            } catch (Throwable t) {
                System.getLogger(Ui2WorkerMain.class.getName()).log(System.Logger.Level.WARNING,
                        "Packet analysis retention error: " + t.getClass().getSimpleName());
            }
        }, 60, pruneSeconds, java.util.concurrent.TimeUnit.SECONDS);
        health.markStarted();
        long drainSeconds = Long.parseLong(OverwatchEnv.getOrDefault("UI2_WORKER_DRAIN_SECONDS", "840"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            health.markDraining();
            loop.requestStop();
            claims.shutdown();
            try { claims.awaitTermination(drainSeconds, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally {
                scheduled.shutdownNow();
                try { runtime.releaseOwnership(role, instance); }
                finally { pool.close(); }
            }
        }, "packet-analysis-drain"));
    }

    private static String requireEnv(String name) {
        String value = OverwatchEnv.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }

    /** Reports R1: the report files' own volume (UI2_REPORT_STORE_ROOT); unset means they share the store root. */
    static Path reportStoreRootOrNull() {
        String root = OverwatchEnv.getOrDefault("UI2_REPORT_STORE_ROOT", "");
        return root.isBlank() ? null : Path.of(root);
    }
}
