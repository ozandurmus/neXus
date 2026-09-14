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
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.admission.ConfirmCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.admission.PersistenceJobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.PersistenceDeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqCredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.service.device.DeviceAddSingleService;
import com.securityexpert.nexus.ui2.service.device.DeviceQueryService;
import com.securityexpert.nexus.ui2.service.device.DeviceRegistrationService;

/**
 * Composition root for single-device add and its two read routes (WORKER.md
 * "Composition"): wires {@link JobAdmissionService} for the first time in
 * {@code service} (SESSION_START baseline: "No JobAdmissionService bean is
 * wired in the service composition today") alongside the {@code devices}/
 * {@code jobs} read/list beans the new controller needs.
 *
 * <p>{@link #confirmCapabilityRegistry} duplicates {@code worker}'s own
 * {@code ConfirmCapabilities} placeholder builder rather than depending on
 * it: {@code service} may never import a {@code worker} class (DIR-2), so
 * the same two-capability, gate-free placeholder (a step list with no gate
 * reference -- neither side's confirm executor runs through {@code
 * StepExecutor} or these steps at all) is built here from {@code
 * capability-registry} types alone. Keep both in sync if either
 * capability's id/vendor/transport ever changes.</p>
 */
@Configuration
public class DeviceCompositionConfiguration {

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
    public CapabilityRegistry confirmCapabilityRegistry() {
        return CapabilityRegistry.of(List.of(
                confirmCapability(ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT, "check_point", "cp_gaia_gateway",
                        TransportKind.SSH_EXEC),
                confirmCapability(ConfirmCapabilityIds.DEVICE_CONFIRM_PALO_ALTO, "palo_alto", "pan_firewall",
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

    @Bean
    public DeviceEnrollmentReadPort deviceEnrollmentReadPort(DeviceRepository deviceRepository) {
        return new PersistenceDeviceEnrollmentReadPort(deviceRepository);
    }

    @Bean
    public JobAdmissionRepository jobAdmissionRepository(JobRecordDao jobRecordDao) {
        return new PersistenceJobAdmissionRepository(jobRecordDao);
    }

    @Bean
    public JobAdmissionService jobAdmissionService(CapabilityRegistry confirmCapabilityRegistry,
            DeviceEnrollmentReadPort deviceEnrollmentReadPort, JobAdmissionRepository jobAdmissionRepository) {
        return new JobAdmissionService(confirmCapabilityRegistry, deviceEnrollmentReadPort, jobAdmissionRepository);
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
}
