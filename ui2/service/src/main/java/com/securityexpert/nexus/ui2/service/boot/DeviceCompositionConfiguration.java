package com.securityexpert.nexus.ui2.service.boot;

import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.ConfigurationCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.ConfirmCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.DiscoveryCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.InventoryCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.admission.PersistenceJobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.capability.PersistenceGateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.PersistenceDeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.discovery.DiscoveryRunReadPort;
import com.securityexpert.nexus.ui2.jobs.discovery.PersistenceDiscoveryRunReadPort;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDiscoveryMatchRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqCredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceDiscoveryMatchRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationNotificationRepository;
import com.securityexpert.nexus.ui2.persistence.device.configuration.DeviceConfigurationRepository;
import com.securityexpert.nexus.ui2.persistence.device.configuration.JooqConfigurationNotificationRepository;
import com.securityexpert.nexus.ui2.persistence.device.configuration.JooqDeviceConfigurationRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.JooqDeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRunRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.JooqDiscoveryRunRepository;
import com.securityexpert.nexus.ui2.persistence.gates.JooqGateRegistryDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.service.device.DeviceAddSingleService;
import com.securityexpert.nexus.ui2.service.device.DeviceDeletionService;
import com.securityexpert.nexus.ui2.service.device.DeviceQueryService;
import com.securityexpert.nexus.ui2.service.device.DeviceRegistrationService;
import com.securityexpert.nexus.ui2.service.device.backup.BackupCollectService;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationCollectService;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationQueryService;
import com.securityexpert.nexus.ui2.service.device.inventory.InventoryCollectService;
import com.securityexpert.nexus.ui2.service.device.inventory.InventoryQueryService;
import com.securityexpert.nexus.ui2.service.discovery.DiscoveryRunService;

/**
 * Composition root for single-device add and its two read routes (WORKER.md
 * "Composition"): wires {@link JobAdmissionService} for the first time in
 * {@code service} (SESSION_START baseline: "No JobAdmissionService bean is
 * wired in the service composition today") alongside the {@code devices}/
 * {@code jobs} read/list beans the new controller needs.
 *
 * <p>{@link #deviceCapabilityRegistry} duplicates {@code worker}'s own
 * {@code ConfirmCapabilities}/{@code InventoryCapabilities} builders rather
 * than depending on them: {@code service} may never import a {@code
 * worker} class (DIR-2). The enrollment confirm capabilities stay the
 * gate-free connect/disconnect placeholder they always were (unchanged by
 * NXS-LOCAL-0164, out of this movement's scope); the two inventory
 * capabilities now carry the real 14D CF-3/14E PF-1 literals with real
 * gate references, resolved against the real {@code gate_registry} table
 * (V15-seeded) through {@link PersistenceGateRegistryPort} -- the same
 * literal set {@code worker.inventory.InventoryReadPlan}/{@code
 * InventoryCapabilities} carry, duplicated here because {@code service}
 * cannot import {@code worker}. Keep all three in sync if any capability's
 * id/vendor/transport/literal set ever changes.</p>
 */
@Configuration
public class DeviceCompositionConfiguration {

    // Mirrors worker.inventory.InventoryReadPlan.CHECK_POINT_PHYSICAL_READS
    // exactly (14D CF-3, CF-2's bare/physical form) -- kept here only
    // because DIR-2 forbids importing that worker class from service.
    private static final List<String> CHECK_POINT_PHYSICAL_READS = List.of(
            "fw getifs", "ip -4 addr show", "ip -4 route show table all", "cphaprob stat", "cphaprob -a if", "vsx stat -v");

    // Mirrors worker.inventory.InventoryReadPlan.PALO_ALTO_BASE_STEPS exactly (14E PF-1).
    private static final List<String> PALO_ALTO_BASE_STEPS = List.of(
            "<show><system><info/></system></show>", "<show><high-availability><state/></high-availability></show>",
            "<show><interface>all</interface></show>", "<show><routing><route/></routing></show>");

    // Mirrors worker.configuration.ConfigurationReadPlan.CHECK_POINT_IDENTITY_READS + CP_SHOW_CONFIGURATION (14G CG-1).
    private static final List<String> CHECK_POINT_CONFIGURATION_READS = List.of(
            "clish -c 'show hostname'", "clish -c 'show version all'", "clish -c 'cpstat os -f hw_info'",
            "clish -c 'show configuration'");

    // Mirrors worker.configuration.ConfigurationReadPlan's Palo Alto literals (14G CG-4).
    private static final List<String> PALO_ALTO_CONFIGURATION_READS = List.of(
            "<show><system><info/></system></show>", "type=config&action=show&xpath=/config",
            "<show><config><effective-running/></config></show>", "<show><config><merged/></config></show>");

    // Mirrors worker.backup.BackupReadPlan's exec/poll literals exactly (14H
    // section 5 entries 1, 2, 3, 6, 7 -- entry 4 is governed but not issued,
    // entry 5's SFTP fetch is gate_not_applicable, neither is a step here).
    private static final List<String> CHECK_POINT_BACKUP_LITERALS = List.of("clish -c \"show diskspace\"",
            "clish -c \"add backup local\"", "clish -c \"show backup status\"", "sha256sum <name>",
            "clish -c \"delete backup <name>\"");

    @Bean
    public DeviceRepository deviceRepository(TransactionBoundary transactionBoundary) {
        return new JooqDeviceRepository(transactionBoundary);
    }

    @Bean
    public com.securityexpert.nexus.ui2.service.privacy.PseudonymRegistry pseudonymRegistry(TransactionBoundary transactionBoundary) {
        return new com.securityexpert.nexus.ui2.service.privacy.JooqPseudonymRegistry(transactionBoundary);
    }

    @Bean
    public com.securityexpert.nexus.ui2.persistence.device.DeviceSecretReferenceRepository deviceSecretReferenceRepository(
            TransactionBoundary transactionBoundary) {
        return new com.securityexpert.nexus.ui2.persistence.device.JooqDeviceSecretReferenceRepository(transactionBoundary);
    }

    @Bean
    public com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstallRepository devicePolicyInstallRepository(
            TransactionBoundary transactionBoundary) {
        return new com.securityexpert.nexus.ui2.persistence.device.JooqDevicePolicyInstallRepository(transactionBoundary);
    }

    @Bean
    public com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository devicePlatformFactsRepository(
            TransactionBoundary transactionBoundary) {
        return new com.securityexpert.nexus.ui2.persistence.device.JooqDevicePlatformFactsRepository(transactionBoundary);
    }

    @Bean
    public CredentialReferenceRepository credentialReferenceRepository(TransactionBoundary transactionBoundary) {
        return new JooqCredentialReferenceRepository(transactionBoundary);
    }

    @Bean
    public DeviceRegistrationService deviceRegistrationService(DeviceRepository deviceRepository,
            CredentialReferenceRepository credentialReferenceRepository) {
        return new DeviceRegistrationService(deviceRepository, credentialReferenceRepository);
    }

    @Bean
    public JobRecordDao jobRecordDao(TransactionBoundary transactionBoundary) {
        return new JooqJobRecordDao(transactionBoundary);
    }

    @Bean
    public GateRegistryPort gateRegistryPort(TransactionBoundary transactionBoundary) {
        return new PersistenceGateRegistryPort(new JooqGateRegistryDao(transactionBoundary));
    }

    @Bean
    public CapabilityRegistry deviceCapabilityRegistry(GateRegistryPort gateRegistryPort) {
        return CapabilityRegistry.of(List.of(
                confirmCapability(ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT, "check_point", "cp_gaia_gateway",
                        TransportKind.SSH_EXEC),
                confirmCapability(ConfirmCapabilityIds.DEVICE_CONFIRM_PALO_ALTO, "palo_alto", "pan_firewall",
                        TransportKind.PAN_XML_API),
                // V64: HTTPS vendors -- confirm and backup run by the worker's HttpsVendorExecutor over the closed
                // request set in HttpsVendorPlan (gate rows V64); placeholder steps, as for discovery below.
                confirmCapability(ConfirmCapabilityIds.DEVICE_CONFIRM_HTTPS, "https_vendor", "https_appliance", TransportKind.HTTPS),
                confirmCapability(BackupCapabilityIds.HTTPS_VENDOR_BACKUP, "https_vendor", "https_appliance", TransportKind.HTTPS),
                checkPointInventoryCapability(gateRegistryPort),
                paloAltoInventoryCapability(gateRegistryPort),
                checkPointConfigurationCapability(gateRegistryPort),
                paloAltoConfigurationCapability(gateRegistryPort),
                checkPointBackupCapability(gateRegistryPort),
                paloAltoBackupCapability(gateRegistryPort),
                paloAltoSetConfigCapability(gateRegistryPort),
                checkPointMdsExportCapability(gateRegistryPort),
                // 14F DR-1: admitted through JobAdmissionService#submitForRun
                // against a discovery_run row, never a device -- same
                // gate-free placeholder shape (StepExecutor never runs
                // either capability's own step list; the worker's
                // DiscoveryJobExecutor drives the closed command set
                // instead, mirroring the two above).
                confirmCapability(DiscoveryCapabilityIds.CP_DISCOVERY_ENUMERATE, "check_point",
                        "cp_multi_domain_server", TransportKind.SSH_EXEC),
                confirmCapability(DiscoveryCapabilityIds.PAN_DISCOVERY_ENUMERATE, "palo_alto", "panorama",
                        TransportKind.PAN_XML_API)));
    }

    private static Capability confirmCapability(String capabilityId, String vendor, String platformRoleScope,
            TransportKind transportKind) {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilitySpec spec = new CapabilitySpec(capabilityId, vendor, platformRoleScope, transportKind,
                MaturityState.CAP_OFFLINE, List.of(connect), List.of(disconnect), "UNKNOWN", List.of(), false);
        return new CapabilityRegistryLoader(key -> List.of()).load(spec);
    }

    /** NXS-LOCAL-0164: 14D CF-3's physical reads, in CF-2's bare form, each gated (docs/design/CP_INVENTORY_COMMAND_GATE_ENTRIES.md). */
    private static Capability checkPointInventoryCapability(GateRegistryPort gateRegistryPort) {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        List<CapabilityStep> steps = new java.util.ArrayList<>();
        steps.add(connect);
        for (String read : CHECK_POINT_PHYSICAL_READS) {
            steps.add(new CapabilityStep(StepKind.EXEC, "expert", read, false, Optional.empty(), Optional.empty(),
                    Optional.empty()));
        }
        CapabilitySpec spec = new CapabilitySpec(InventoryCapabilityIds.CP_INVENTORY_COLLECT, "check_point",
                "cp_gaia_gateway", TransportKind.SSH_EXEC, MaturityState.CAP_VALIDATED, steps, List.of(disconnect),
                "14D", List.of(), false);
        return new CapabilityRegistryLoader(gateRegistryPort).load(spec);
    }

    /** NXS-LOCAL-0164: 14E PF-1's four unscoped requests, each gated (docs/design/PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md). */
    private static Capability paloAltoInventoryCapability(GateRegistryPort gateRegistryPort) {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        List<CapabilityStep> steps = new java.util.ArrayList<>();
        steps.add(connect);
        for (String request : PALO_ALTO_BASE_STEPS) {
            steps.add(new CapabilityStep(StepKind.XML_API_CALL, "not_applicable", request, false, Optional.empty(),
                    Optional.empty(), Optional.empty()));
        }
        CapabilitySpec spec = new CapabilitySpec(InventoryCapabilityIds.PAN_INVENTORY_COLLECT, "palo_alto",
                "pan_firewall", TransportKind.PAN_XML_API, MaturityState.CAP_VALIDATED, steps, List.of(disconnect),
                "14E", List.of(), false);
        return new CapabilityRegistryLoader(gateRegistryPort).load(spec);
    }

    /** NXS-LOCAL-0165: 14G CG-1's identity-refresh + configuration reads, each gated (docs/design/CP_CONFIGURATION_COMMAND_GATE_ENTRIES.md). */
    private static Capability checkPointConfigurationCapability(GateRegistryPort gateRegistryPort) {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        List<CapabilityStep> steps = new java.util.ArrayList<>();
        steps.add(connect);
        for (String read : CHECK_POINT_CONFIGURATION_READS) {
            steps.add(new CapabilityStep(StepKind.EXEC, "expert", read, false, Optional.empty(), Optional.empty(),
                    Optional.empty()));
        }
        CapabilitySpec spec = new CapabilitySpec(ConfigurationCapabilityIds.CP_CONFIGURATION_COLLECT, "check_point",
                "cp_gaia_gateway", TransportKind.SSH_EXEC, MaturityState.CAP_VALIDATED, steps, List.of(disconnect),
                "14G", List.of(), false);
        return new CapabilityRegistryLoader(gateRegistryPort).load(spec);
    }

    /** NXS-LOCAL-0165: 14G CG-4's four requests, each gated (docs/design/PAN_CONFIGURATION_API_ROUTE_GATE_ENTRIES.md). */
    private static Capability paloAltoConfigurationCapability(GateRegistryPort gateRegistryPort) {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        List<CapabilityStep> steps = new java.util.ArrayList<>();
        steps.add(connect);
        for (String request : PALO_ALTO_CONFIGURATION_READS) {
            steps.add(new CapabilityStep(StepKind.XML_API_CALL, "not_applicable", request, false, Optional.empty(),
                    Optional.empty(), Optional.empty()));
        }
        CapabilitySpec spec = new CapabilitySpec(ConfigurationCapabilityIds.PAN_CONFIGURATION_COLLECT, "palo_alto",
                "pan_firewall", TransportKind.PAN_XML_API, MaturityState.CAP_VALIDATED, steps, List.of(disconnect),
                "14G", List.of(), false);
        return new CapabilityRegistryLoader(gateRegistryPort).load(spec);
    }

    /**
     * NXS-LOCAL-0175: 14H section 5's gated literals, mirroring {@code
     * worker.backup.BackupCapabilities.checkPoint} exactly (docs/design/
     * CP_BACKUP_COMMAND_GATE_ENTRIES.md). {@link #CHECK_POINT_BACKUP_LITERALS}
     * carries entries 1, 2, 3, 6, 7 in order -- entry 3 (index 2) is the one
     * {@code poll} kind step; entry 5 (the SFTP fetch) is declared {@code
     * gate_not_applicable} per C4 section 2.3's sftp_get/prior-step rule,
     * not looped from the literal list.
     */
    /** Mirrors {@code worker.backup.BackupCapabilities.paloAltoSetConfig} exactly (V43 gate rows). */
    private static Capability paloAltoSetConfigCapability(GateRegistryPort gateRegistryPort) {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        List<CapabilityStep> steps = new java.util.ArrayList<>();
        steps.add(connect);
        for (String command : List.of("set cli scripting-mode on", "set cli pager off",
                "set cli config-output-format set", "show config running")) {
            steps.add(new CapabilityStep(StepKind.EXEC, "operational", command, false, Optional.empty(),
                    Optional.empty(), Optional.empty()));
        }
        CapabilitySpec spec = new CapabilitySpec(BackupCapabilityIds.PAN_SET_CONFIG_READ, "palo_alto",
                "pan_firewall", TransportKind.SSH_EXEC, MaturityState.CAP_VALIDATED, List.copyOf(steps),
                List.of(disconnect), "14H", List.of(), false);
        return new CapabilityRegistryLoader(gateRegistryPort).load(spec);
    }

    /** Mirrors {@code worker.backup.BackupCapabilities.checkPointMdsExport} and {@code MdsExportPlan.LITERALS} exactly (V61). */
    static final List<String> MDS_EXPORT_LITERALS = List.of("clish -c 'show version all'", "df -P /var/log", "mkdir -p %s",
            "bash -lc 'mdsstat' > %s/mdsstat.txt 2>&1", "clish -c 'show configuration' > %s/gaia_config.txt",
            "bash -lc 'cplic print -x' > %s/cplic.txt 2>&1", "netstat -rn > %s/netstat.txt", "uname -a > %s/uname.txt",
            "cd /var/log && setsid nohup bash -lc '$CPMDIR/scripts/mds_backup -b -l -d %1$s > %1$s/mds_backup.log 2>&1; echo $? > %1$s/mds_backup.rc' </dev/null >/dev/null 2>&1 &",
            "cat %s/mds_backup.rc", "ls %s", "cd %1$s && tar -czf %1$s.tgz .", "sha256sum %s.tgz", "rm -rf %1$s %1$s.tgz");

    private static Capability checkPointMdsExportCapability(GateRegistryPort gateRegistryPort) {
        List<CapabilityStep> steps = new java.util.ArrayList<>();
        steps.add(new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false, Optional.empty(), Optional.empty(), Optional.empty()));
        for (String literal : MDS_EXPORT_LITERALS) {
            steps.add(new CapabilityStep(literal.startsWith("cat ") ? StepKind.POLL : StepKind.EXEC, "expert", literal, false,
                    Optional.empty(), Optional.empty(), Optional.empty()));
        }
        steps.add(new CapabilityStep(StepKind.SFTP_GET, "not_applicable", null, true, Optional.empty(), Optional.empty(), Optional.empty()));
        CapabilitySpec spec = new CapabilitySpec(BackupCapabilityIds.CP_MDS_EXPORT, "check_point", "cp_multi_domain_server",
                TransportKind.SSH_EXEC, MaturityState.CAP_VALIDATED, List.copyOf(steps),
                List.of(new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false, Optional.empty(), Optional.empty(), Optional.empty())),
                "14H", List.of(), false);
        return new CapabilityRegistryLoader(gateRegistryPort).load(spec);
    }

    /** Mirrors {@code worker.backup.BackupCapabilities.paloAlto} exactly (V40 gate rows). */
    private static Capability paloAltoBackupCapability(GateRegistryPort gateRegistryPort) {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        List<CapabilityStep> steps = List.of(connect,
                new CapabilityStep(StepKind.XML_API_CALL, "not_applicable", "type=config&action=show", false,
                        Optional.empty(), Optional.empty(), Optional.empty()),
                new CapabilityStep(StepKind.XML_API_CALL, "not_applicable", "type=export&category=device-state", false,
                        Optional.empty(), Optional.empty(), Optional.empty()));
        CapabilitySpec spec = new CapabilitySpec(BackupCapabilityIds.PAN_DEVICE_STATE_BACKUP, "palo_alto",
                "pan_firewall", TransportKind.PAN_XML_API, MaturityState.CAP_VALIDATED, steps, List.of(disconnect),
                "14H", List.of(), false);
        return new CapabilityRegistryLoader(gateRegistryPort).load(spec);
    }

    private static Capability checkPointBackupCapability(GateRegistryPort gateRegistryPort) {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep sftpGet = new CapabilityStep(StepKind.SFTP_GET, "not_applicable", null, true, Optional.empty(),
                Optional.empty(), Optional.empty());
        List<CapabilityStep> steps = List.of(connect,
                // cp_configuration_show_version_all: the run's own version read (C7 §3.3); mirrors BackupCapabilities.checkPoint.
                new CapabilityStep(StepKind.EXEC, "expert", "clish -c 'show version all'", false,
                        Optional.empty(), Optional.empty(), Optional.empty()),
                new CapabilityStep(StepKind.EXEC, "expert", CHECK_POINT_BACKUP_LITERALS.get(0), false,
                        Optional.empty(), Optional.empty(), Optional.empty()),
                // cp_backup_df_var_log (V40): the Expert fallback entry 1 names; mirrors BackupCapabilities.checkPoint.
                new CapabilityStep(StepKind.EXEC, "expert", "df -P /var/log", false,
                        Optional.empty(), Optional.empty(), Optional.empty()),
                new CapabilityStep(StepKind.EXEC, "expert", CHECK_POINT_BACKUP_LITERALS.get(1), false,
                        Optional.empty(), Optional.empty(), Optional.empty()),
                new CapabilityStep(StepKind.POLL, "expert", CHECK_POINT_BACKUP_LITERALS.get(2), false,
                        Optional.empty(), Optional.empty(), Optional.empty()),
                sftpGet,
                new CapabilityStep(StepKind.EXEC, "expert", CHECK_POINT_BACKUP_LITERALS.get(3), false,
                        Optional.empty(), Optional.empty(), Optional.empty()),
                new CapabilityStep(StepKind.EXEC, "expert", CHECK_POINT_BACKUP_LITERALS.get(4), false,
                        Optional.empty(), Optional.empty(), Optional.empty()));
        CapabilitySpec spec = new CapabilitySpec(BackupCapabilityIds.CP_GAIA_BACKUP_LOCAL, "check_point",
                "cp_gaia_gateway", TransportKind.SSH_EXEC, MaturityState.CAP_VALIDATED, steps, List.of(disconnect),
                "14H", List.of(), false);
        return new CapabilityRegistryLoader(gateRegistryPort).load(spec);
    }

    @Bean
    public DeviceEnrollmentReadPort deviceEnrollmentReadPort(DeviceRepository deviceRepository) {
        return new PersistenceDeviceEnrollmentReadPort(deviceRepository);
    }

    @Bean
    public JobAdmissionRepository jobAdmissionRepository(JobRecordDao jobRecordDao) {
        return new PersistenceJobAdmissionRepository(jobRecordDao);
    }

    @Bean
    public com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository managementEndpointSshTrustRepository(
            TransactionBoundary transactionBoundary) {
        return new com.securityexpert.nexus.ui2.persistence.discovery.JooqManagementEndpointSshTrustRepository(transactionBoundary);
    }

    @Bean
    public com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointSshTrustService managementEndpointSshTrustService(
            com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository repository) {
        return new com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointSshTrustService(repository);
    }

    @Bean
    public com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointPanCertTrustRepository managementEndpointPanCertTrustRepository(
            TransactionBoundary transactionBoundary) {
        return new com.securityexpert.nexus.ui2.persistence.discovery.JooqManagementEndpointPanCertTrustRepository(transactionBoundary);
    }

    @Bean
    public com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointPanCertTrustService managementEndpointPanCertTrustService(
            com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointPanCertTrustRepository repository) {
        return new com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointPanCertTrustService(repository);
    }

    @Bean
    public DiscoveryRunRepository discoveryRunRepository(TransactionBoundary transactionBoundary) {
        return new JooqDiscoveryRunRepository(transactionBoundary);
    }

    @Bean
    public DiscoveryRunReadPort discoveryRunReadPort(DiscoveryRunRepository discoveryRunRepository) {
        return new PersistenceDiscoveryRunReadPort(discoveryRunRepository);
    }

    @Bean
    public DeviceDiscoveryMatchRepository deviceDiscoveryMatchRepository(TransactionBoundary transactionBoundary) {
        return new JooqDeviceDiscoveryMatchRepository(transactionBoundary);
    }

    /** 14F DR-1: the four-arg constructor so {@code submitForRun} can resolve a real {@code discovery_run} row -- one bean serves both {@link JobAdmissionService#submit} and {@link JobAdmissionService#submitForRun} callers. */
    @Bean
    public JobAdmissionService jobAdmissionService(CapabilityRegistry deviceCapabilityRegistry,
            DeviceEnrollmentReadPort deviceEnrollmentReadPort, DiscoveryRunReadPort discoveryRunReadPort,
            JobAdmissionRepository jobAdmissionRepository) {
        return new JobAdmissionService(deviceCapabilityRegistry, deviceEnrollmentReadPort, discoveryRunReadPort,
                jobAdmissionRepository);
    }

    @Bean
    public DeviceAddSingleService deviceAddSingleService(TransactionBoundary transactionBoundary,
            DeviceRegistrationService deviceRegistrationService, JobAdmissionService jobAdmissionService,
            DeviceRepository deviceRepository) {
        return new DeviceAddSingleService(transactionBoundary, deviceRegistrationService, jobAdmissionService,
                deviceRepository);
    }

    @Bean
    public DeviceDeletionService deviceDeletionService(DeviceRepository deviceRepository) {
        return new DeviceDeletionService(deviceRepository);
    }

    @Bean
    public DeviceQueryService deviceQueryService(DeviceRepository deviceRepository, JobRecordDao jobRecordDao) {
        return new DeviceQueryService(deviceRepository, jobRecordDao);
    }

    @Bean
    public DeviceInventoryRepository deviceInventoryRepository(TransactionBoundary transactionBoundary) {
        return new JooqDeviceInventoryRepository(transactionBoundary);
    }

    @Bean
    public InventoryQueryService inventoryQueryService(DeviceRepository deviceRepository, JobRecordDao jobRecordDao,
            DeviceInventoryRepository deviceInventoryRepository,
            com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer topologyNamePseudonymizer) {
        return new InventoryQueryService(deviceRepository, jobRecordDao, deviceInventoryRepository, topologyNamePseudonymizer);
    }

    @Bean
    public InventoryCollectService inventoryCollectService(DeviceRepository deviceRepository,
            JobAdmissionService jobAdmissionService) {
        return new InventoryCollectService(deviceRepository, jobAdmissionService);
    }

    @Bean
    public DeviceConfigurationRepository deviceConfigurationRepository(TransactionBoundary transactionBoundary) {
        return new JooqDeviceConfigurationRepository(transactionBoundary);
    }

    @Bean
    public ConfigurationNotificationRepository configurationNotificationRepository(
            TransactionBoundary transactionBoundary) {
        return new JooqConfigurationNotificationRepository(transactionBoundary);
    }

    @Bean
    public ConfigurationQueryService configurationQueryService(DeviceRepository deviceRepository,
            DeviceConfigurationRepository deviceConfigurationRepository) {
        return new ConfigurationQueryService(deviceRepository, deviceConfigurationRepository);
    }

    @Bean
    public ConfigurationCollectService configurationCollectService(DeviceRepository deviceRepository,
            JobAdmissionService jobAdmissionService) {
        return new ConfigurationCollectService(deviceRepository, jobAdmissionService);
    }

    @Bean
    public BackupArtefactManifestRepository backupArtefactManifestRepository(TransactionBoundary transactionBoundary) {
        return new JooqBackupArtefactManifestRepository(transactionBoundary);
    }

    @Bean
    public com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository backupArtefactEntryRepository(
            TransactionBoundary transactionBoundary) {
        return new com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactEntryRepository(transactionBoundary);
    }

    @Bean
    public com.securityexpert.nexus.ui2.service.device.backup.BackupCompareService backupCompareService(
            BackupArtefactManifestRepository manifestRepository,
            com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository entryRepository) {
        return new com.securityexpert.nexus.ui2.service.device.backup.BackupCompareService(manifestRepository, entryRepository);
    }

    /**
     * PO decision record 2026-09-22 (backup HTTP download with RBAC): the
     * service decrypts a backup artefact for {@code POST /backups/{id}/download}.
     * The artefact-store key and volume are mounted on the service pod for
     * that one purpose; when either env var is absent the download route
     * answers 503 {@code ARTEFACT_STORE_NOT_MOUNTED} rather than failing at boot.
     */
    @Bean
    public com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository backupPolicyRepository(
            TransactionBoundary transactionBoundary) {
        return new com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupPolicyRepository(transactionBoundary);
    }

    @Bean
    public com.securityexpert.nexus.ui2.persistence.artefact.BackupBaselineRepository backupBaselineRepository(
            TransactionBoundary transactionBoundary) {
        return new com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupBaselineRepository(transactionBoundary);
    }

    /** V44: the cron-driven fleet backup; {@link BackupScheduleTrigger} calls it once a minute. */
    @Bean
    public com.securityexpert.nexus.ui2.service.device.backup.BackupScheduler backupScheduler(
            com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository policyRepository,
            BackupCollectService collectService) {
        return new com.securityexpert.nexus.ui2.service.device.backup.BackupScheduler(policyRepository, collectService);
    }

    @Bean
    public BackupScheduleTrigger backupScheduleTrigger(
            com.securityexpert.nexus.ui2.service.device.backup.BackupScheduler scheduler) {
        return new BackupScheduleTrigger(scheduler);
    }

    /** The one-minute tick behind {@link com.securityexpert.nexus.ui2.service.device.backup.BackupScheduler}. */
    public static final class BackupScheduleTrigger {
        private static final java.util.logging.Logger LOG =
                java.util.logging.Logger.getLogger(BackupScheduleTrigger.class.getName());
        private final com.securityexpert.nexus.ui2.service.device.backup.BackupScheduler scheduler;

        BackupScheduleTrigger(com.securityexpert.nexus.ui2.service.device.backup.BackupScheduler scheduler) {
            this.scheduler = scheduler;
        }

        @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
        public void tick() {
            try {
                scheduler.tick(java.time.Instant.now());
            } catch (RuntimeException e) {
                LOG.warning("[BACKUP_SCHEDULE] tick failed: " + e.getMessage());
            }
        }
    }

    /** The service's artefact store, or {@code storeOrNull() == null} when the pod has no key/volume mounted. */
    public record ArtefactStoreAccess(com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore storeOrNull) {
    }

    @Bean
    public ArtefactStoreAccess artefactStoreAccess() {
        String keyFile = System.getenv().getOrDefault("UI2_ARTEFACT_STORE_KEY_FILE", "");
        String root = System.getenv().getOrDefault("UI2_ARTEFACT_STORE_ROOT", "");
        if (keyFile.isBlank() || root.isBlank()) {
            return new ArtefactStoreAccess(null);
        }
        String keyBase64 = com.securityexpert.nexus.ui2.platform.SecretFile.readRequired(
                java.nio.file.Path.of(keyFile), "artefact_store_key");
        return new ArtefactStoreAccess(new com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore(
                java.nio.file.Path.of(root),
                com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher.fromBase64Key(keyBase64)));
    }

    @Bean
    public com.securityexpert.nexus.ui2.service.device.backup.BackupDownloadService backupDownloadService(
            BackupArtefactManifestRepository manifestRepository, TransactionBoundary transactionBoundary,
            ArtefactStoreAccess artefactStoreAccess) {
        return new com.securityexpert.nexus.ui2.service.device.backup.BackupDownloadService(
                artefactStoreAccess.storeOrNull(), manifestRepository,
                new com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactRetrievalRepository(
                        transactionBoundary));
    }

    /**
     * V41 "List now": lists an artefact stored before content listing existed (or whose listing
     * failed) on the service, through the same lister the worker runs after every backup. Null
     * when the pod has no store, and the route then answers 503.
     */
    @Bean
    public com.securityexpert.nexus.ui2.service.device.backup.BackupRelistService backupRelistService(
            BackupArtefactManifestRepository manifestRepository,
            com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository entryRepository,
            ArtefactStoreAccess artefactStoreAccess) {
        com.securityexpert.nexus.ui2.persistence.artefact.content.ArchiveContentListingService lister =
                artefactStoreAccess.storeOrNull() == null ? null
                        : new com.securityexpert.nexus.ui2.persistence.artefact.content.ArchiveContentListingService(
                                artefactStoreAccess.storeOrNull(), entryRepository);
        return new com.securityexpert.nexus.ui2.service.device.backup.BackupRelistService(manifestRepository, lister);
    }

    /**
     * 14H BK-1: the pilot-device allowlist, an env-backed configuration
     * list -- {@code UI2_BACKUP_PILOT_DEVICE_IDS}, comma-separated,
     * empty by default (WORKER.md: "empty means every backup is refused").
     * Read the same way {@code Ui2WorkerMain}'s own trust-rule-ref env
     * vars are, since neither carries a secret.
     */
    @Bean
    public BackupCollectService backupCollectService(DeviceRepository deviceRepository,
            JobAdmissionService jobAdmissionService) {
        java.util.Set<String> allowlist = parseCsvEnv("UI2_BACKUP_PILOT_DEVICE_IDS");
        boolean backupCredentialConfigured = !System.getenv().getOrDefault("UI2_CP_BACKUP_CREDENTIAL_REF", "").isBlank();
        return new BackupCollectService(deviceRepository, jobAdmissionService, allowlist, backupCredentialConfigured);
    }

    private static java.util.Set<String> parseCsvEnv(String name) {
        String raw = System.getenv().getOrDefault(name, "");
        java.util.Set<String> values = new java.util.LinkedHashSet<>();
        for (String value : raw.split(",")) {
            String trimmed = value.strip();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    @Bean
    public DiscoveryRunService discoveryRunService(TransactionBoundary transactionBoundary,
            DiscoveryRunRepository discoveryRunRepository, JobAdmissionService jobAdmissionService,
            CredentialReferenceRepository credentialReferenceRepository, DeviceAddSingleService deviceAddSingleService,
            DeviceDiscoveryMatchRepository deviceDiscoveryMatchRepository) {
        return new DiscoveryRunService(transactionBoundary, discoveryRunRepository, jobAdmissionService,
                credentialReferenceRepository, deviceAddSingleService, deviceDiscoveryMatchRepository);
    }

    @Bean
    public com.securityexpert.nexus.ui2.service.compliance.ComplianceService complianceService(
            ConfigurationQueryService configurationQueryService,
            DeviceRepository deviceRepository,
            com.securityexpert.nexus.ui2.persistence.TransactionBoundary transactionBoundary) {
        return new com.securityexpert.nexus.ui2.service.compliance.ComplianceService(configurationQueryService, deviceRepository,
                new com.securityexpert.nexus.ui2.service.compliance.JooqComplianceEvaluationStore(transactionBoundary));
    }
}
