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
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
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
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.worker.confirm.ConfirmCapabilities;
import com.securityexpert.nexus.ui2.worker.confirm.ConfirmCapabilityExecutor;
import com.securityexpert.nexus.ui2.worker.confirm.ConfirmJobExecutor;
import com.securityexpert.nexus.ui2.worker.confirm.PeerFollowResolver;
import com.securityexpert.nexus.ui2.worker.confirm.WorkerClaimLoop;
import com.securityexpert.nexus.ui2.worker.configuration.ConfigurationCapabilities;
import com.securityexpert.nexus.ui2.worker.configuration.ConfigurationCapabilityExecutor;
import com.securityexpert.nexus.ui2.worker.configuration.ConfigurationJobExecutor;
import com.securityexpert.nexus.ui2.worker.configuration.pan.PanoramaCrossCheckPort;
import com.securityexpert.nexus.ui2.worker.discovery.DiscoveryJobExecutor;
import com.securityexpert.nexus.ui2.worker.discovery.cp.ManagementPlaneEnumerationAdapter;
import com.securityexpert.nexus.ui2.worker.discovery.cp.StoreBackedSshCredentialResolver;
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
        String jdbcUrl = requireEnv("UI2_DB_URL");
        String dbUser = SecretFile.readRequired(Path.of(requireEnv("UI2_DB_APP_USER_FILE")), "worker.db_user");
        String dbPassword = SecretFile.readRequired(Path.of(requireEnv("UI2_DB_APP_PASSWORD_FILE")), "worker.db_password");
        String credentialStoreKeyBase64 =
                SecretFile.readRequired(Path.of(requireEnv("UI2_CREDENTIAL_STORE_KEY_FILE")), "credential_store_key");
        String artefactStoreKeyBase64 =
                SecretFile.readRequired(Path.of(requireEnv("UI2_ARTEFACT_STORE_KEY_FILE")), "artefact_store_key");
        Path artefactStoreRoot = Path.of(System.getenv().getOrDefault("UI2_ARTEFACT_STORE_ROOT", "/var/lib/ui2/artefacts"));
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

        TrustRuleResolver trustRuleResolver = trustRuleRef -> Optional.ofNullable(
                System.getenv("UI2_" + trustRuleRef.toUpperCase(java.util.Locale.ROOT).replace('.', '_')
                        + "_FINGERPRINT"));
        StoreBackedSshCredentialResolver sshCredentialResolver =
                new StoreBackedSshCredentialResolver(resolverComponents.credentialReferenceRepository(),
                        resolverComponents.credentialRepository(), resolverComponents.cipher());
        SshExecTransport sshTransport = new SshExecTransport(sshCredentialResolver, trustRuleResolver);
        // Same UI2_<TRUSTRULEREF>_FINGERPRINT env convention ssh_exec's
        // trustRuleResolver above already reads (WORKER.md: "reads no new
        // secret; trust-rule env names stay as they are") reinterpreted as a
        // pinned TLS certificate fingerprint rather than an SSH host key one.
        PanTrustRuleResolver panTrustRuleResolver = trustRuleRef -> {
            String fingerprint = System.getenv("UI2_" + trustRuleRef.toUpperCase(java.util.Locale.ROOT)
                    .replace('.', '_') + "_FINGERPRINT");
            return fingerprint == null || fingerprint.isBlank()
                    ? new TrustResolution.Unresolved()
                    : new TrustResolution.PinnedFingerprint(fingerprint);
        };
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
                deviceEnrollmentReadPort, deviceRepository, deviceInventoryRepository, inventoryCapabilityExecutor);

        ArtefactStore artefactStore =
                new FileArtefactStore(artefactStoreRoot, ArtefactStoreCipher.fromBase64Key(artefactStoreKeyBase64));
        DeviceConfigurationRepository deviceConfigurationRepository =
                new JooqDeviceConfigurationRepository(transactionBoundary);
        JooqConfigurationNotificationRepository configurationNotificationRepository =
                new JooqConfigurationNotificationRepository(transactionBoundary);
        ConfigurationCapabilityExecutor configurationCapabilityExecutor = new ConfigurationCapabilityExecutor(
                compositeTransport, panCredentialResolver, artefactStore, PanoramaCrossCheckPort.NONE);
        ConfigurationJobExecutor configurationJobExecutor = new ConfigurationJobExecutor(leaseRepository,
                attemptRepository, deviceEnrollmentReadPort, deviceRepository, deviceConfigurationRepository,
                configurationNotificationRepository, configurationCapabilityExecutor);

        DiscoveryRunRepository discoveryRunRepository = new JooqDiscoveryRunRepository(transactionBoundary);
        ManagementPlaneEnumerationAdapter checkPointDiscoveryAdapter =
                new ManagementPlaneEnumerationAdapter(compositeTransport, sshCredentialResolver);
        PanoramaEnumerationAdapter paloAltoDiscoveryAdapter =
                new PanoramaEnumerationAdapter(compositeTransport, panCredentialResolver, panTrustRuleResolver);
        DiscoveryJobExecutor discoveryJobExecutor = new DiscoveryJobExecutor(leaseRepository, attemptRepository,
                discoveryRunRepository, checkPointDiscoveryAdapter, paloAltoDiscoveryAdapter);

        JobRecordDao jobRecordDao = new JooqJobRecordDao(transactionBoundary);
        WorkerClaimLoop claimLoop = new WorkerClaimLoop(leaseRepository, jobRecordDao, deviceRepository,
                confirmJobExecutor, inventoryJobExecutor, configurationJobExecutor, discoveryJobExecutor,
                "worker-" + UUID.randomUUID(), Duration.ofSeconds(60), checkPointTrustRuleRef, paloAltoTrustRuleRef);

        claimLoop.runUntilInterrupted(Duration.ofSeconds(2));
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }
}
