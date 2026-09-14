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
import com.securityexpert.nexus.ui2.jobs.admission.ConfirmCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.InventoryCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.admission.PersistenceJobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.capability.PersistenceGateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.PersistenceDeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqCredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.JooqDeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.gates.JooqGateRegistryDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.service.device.DeviceAddSingleService;
import com.securityexpert.nexus.ui2.service.device.DeviceQueryService;
import com.securityexpert.nexus.ui2.service.device.DeviceRegistrationService;
import com.securityexpert.nexus.ui2.service.device.inventory.InventoryCollectService;
import com.securityexpert.nexus.ui2.service.device.inventory.InventoryQueryService;

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
            "ip -details -4 addr show", "ip -6 addr show", "ip -4 route show table all", "cphaprob stat",
            "cphaprob -a -m if", "vsx stat -v");

    // Mirrors worker.inventory.InventoryReadPlan.PALO_ALTO_BASE_STEPS exactly (14E PF-1).
    private static final List<String> PALO_ALTO_BASE_STEPS = List.of(
            "<show><system><info/></system></show>", "<show><high-availability><state/></high-availability></show>",
            "<show><interface>all</interface></show>", "<show><routing><route/></routing></show>");

    @Bean
    public DeviceRepository deviceRepository(TransactionBoundary transactionBoundary) {
        return new JooqDeviceRepository(transactionBoundary);
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
                checkPointInventoryCapability(gateRegistryPort),
                paloAltoInventoryCapability(gateRegistryPort)));
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

    @Bean
    public DeviceEnrollmentReadPort deviceEnrollmentReadPort(DeviceRepository deviceRepository) {
        return new PersistenceDeviceEnrollmentReadPort(deviceRepository);
    }

    @Bean
    public JobAdmissionRepository jobAdmissionRepository(JobRecordDao jobRecordDao) {
        return new PersistenceJobAdmissionRepository(jobRecordDao);
    }

    @Bean
    public JobAdmissionService jobAdmissionService(CapabilityRegistry deviceCapabilityRegistry,
            DeviceEnrollmentReadPort deviceEnrollmentReadPort, JobAdmissionRepository jobAdmissionRepository) {
        return new JobAdmissionService(deviceCapabilityRegistry, deviceEnrollmentReadPort, jobAdmissionRepository);
    }

    @Bean
    public DeviceAddSingleService deviceAddSingleService(TransactionBoundary transactionBoundary,
            DeviceRegistrationService deviceRegistrationService, JobAdmissionService jobAdmissionService) {
        return new DeviceAddSingleService(transactionBoundary, deviceRegistrationService, jobAdmissionService);
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
            DeviceInventoryRepository deviceInventoryRepository) {
        return new InventoryQueryService(deviceRepository, jobRecordDao, deviceInventoryRepository);
    }

    @Bean
    public InventoryCollectService inventoryCollectService(DeviceRepository deviceRepository,
            JobAdmissionService jobAdmissionService) {
        return new InventoryCollectService(deviceRepository, jobAdmissionService);
    }
}
