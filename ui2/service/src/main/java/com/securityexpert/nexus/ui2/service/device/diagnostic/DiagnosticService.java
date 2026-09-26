package com.securityexpert.nexus.ui2.service.device.diagnostic;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.capability.GateResolution;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/** One server-owned FortiManager diagnostic template; callers provide a target and one inventoried port, never a command. */
@Service
public final class DiagnosticService {
    private static final Pattern PORT = Pattern.compile("[A-Za-z0-9_.-]{1,31}");
    private static final Duration MAX_INVENTORY_AGE = Duration.ofHours(24);

    public record Preview(String templateId, String deviceId, String target, String port, String command,
            int gateRevision, int timeoutSeconds, String retry, String frequency) {
    }

    public record TargetOption(String deviceId, String target) {
    }

    private final DeviceRepository devices;
    private final DeviceInventoryRepository inventory;
    private final JobAdmissionService admission;
    private final JobRecordDao jobs;
    private final TopologyNamePseudonymizer names;
    private final GateRegistryPort gates;

    public DiagnosticService(DeviceRepository devices, DeviceInventoryRepository inventory,
            JobAdmissionService admission, JobRecordDao jobs, TopologyNamePseudonymizer names, GateRegistryPort gates) {
        this.devices = devices;
        this.inventory = inventory;
        this.admission = admission;
        this.jobs = jobs;
        this.names = names;
        this.gates = gates;
    }

    public Optional<Preview> preview(String deviceId, String port) {
        if (port == null || !PORT.matcher(port).matches() || !ports(deviceId).contains(port) || !gateReady()) {
            return Optional.empty();
        }
        String label = devices.findSummary(deviceId).flatMap(s -> s.observedHostname())
                .map(name -> names.maskDeviceName(name, null)).orElse("Unknown");
        return Optional.of(new Preview(JobAdmissionService.FMG_INTERFACE_DETAIL, deviceId, label, port,
                "diagnose fmnetwork interface detail " + port, 90, 60, "none", "one per target per minute"));
    }

    private boolean gateReady() {
        try {
            var resolved = GateResolver.resolve(new CanonicalCommandKey("fortinet", "fortimanager", "cli", "SSH_EXEC",
                    "diagnose fmnetwork interface detail <interface>"),
                    Optional.of(com.securityexpert.nexus.ui2.platform.ActionClass.CLASS_0_READ), gates);
            return resolved instanceof GateResolution.Known known
                    && "fmg_ssh_fmnetwork_interface_detail".equals(known.gateId()) && known.timeoutS() == 60;
        } catch (RuntimeException invalidGate) {
            return false;
        }
    }

    public List<String> ports(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return List.of();
        }
        var device = devices.find(deviceId);
        if (device.isEmpty() || !device.get().permitsReadCollection()
                || !"fortinet".equals(device.get().vendorHint()) || !"management_server".equals(device.get().role())) {
            return List.of();
        }
        var run = inventory.findLatestRun(deviceId);
        if (run.isEmpty() || run.get().collectedAt().isBefore(Instant.now().minus(MAX_INVENTORY_AGE))) {
            return List.of();
        }
        return run.get().contexts().stream().filter(c -> InventoryContext.PHYSICAL.equals(c.context()))
                .flatMap(c -> c.interfaces().stream()).filter(i -> InventoryInterface.KIND_PHYSICAL.equals(i.kind())
                        && PORT.matcher(i.name()).matches()).map(InventoryInterface::name).distinct().toList();
    }

    public List<TargetOption> targets() {
        return devices.listAll().stream().filter(d -> "fortinet".equals(d.vendorHint())
                        && "management_server".equals(d.role())
                        && devices.find(d.deviceId()).filter(r -> r.permitsReadCollection()).isPresent())
                .map(d -> new TargetOption(d.deviceId(), d.observedHostname()
                        .map(name -> names.maskDeviceName(name, null)).orElse("Unknown"))).toList();
    }

    public AdmissionResult submit(String deviceId, String port, String requestId, String actor) {
        if (preview(deviceId, port).isEmpty()) {
            return new AdmissionResult.Refused("DIAGNOSTIC_TARGET_UNAVAILABLE", "target or inventoried port unavailable");
        }
        if (requestId == null) {
            return new AdmissionResult.Refused("INVALID_REQUEST_ID", "request ID must be a UUID");
        }
        try {
            UUID.fromString(requestId);
        } catch (IllegalArgumentException e) {
            return new AdmissionResult.Refused("INVALID_REQUEST_ID", "request ID must be a UUID");
        }
        return admission.submitFmgInterfaceDetail(deviceId, port, "fmg-diagnostic:" + requestId,
                actor, ActionRegistry.FMG_DIAGNOSTIC_RUN);
    }

    public Optional<JobRecordDao.DiagnosticJob> result(String jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        try {
            UUID.fromString(jobId);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        return jobs.findDiagnostic(jobId);
    }
}
