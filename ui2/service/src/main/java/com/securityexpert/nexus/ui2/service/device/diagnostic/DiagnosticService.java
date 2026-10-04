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

/** Server-owned approved diagnostic reads; callers select a gate and target, never command text. */
@Service
public final class DiagnosticService {
    private static final Pattern PORT = Pattern.compile("[A-Za-z0-9_.-]{1,31}");
    private static final Duration MAX_INVENTORY_AGE = Duration.ofHours(24);

    public record Preview(String templateId, String deviceId, String target, String port, String command,
            int gateRevision, int timeoutSeconds, String retry, String frequency) {
    }

    public record TargetOption(String deviceId, String target, String vendor,
            String cluster, List<String> virtualSystems, List<java.util.Map<String,Object>> commands) {
    }

    private static final java.util.Map<String, String> DESCRIPTIONS = java.util.Map.ofEntries(
            java.util.Map.entry("fgt_get_system_status", "System version and status"),
            java.util.Map.entry("fgt_get_system_ha_status", "High availability status"),
            java.util.Map.entry("fmg_ssh_fmnetwork_interface_detail", "Network interface details"),
            java.util.Map.entry("asa_show_version", "System version"),
            java.util.Map.entry("asa_show_failover_this_host", "Local failover status"),
            java.util.Map.entry("asa_show_route", "Routing table"),
            java.util.Map.entry("cp_spark_show_diag", "System diagnostics"),
            java.util.Map.entry("cp_spark_show_software_version", "Software version"),
            java.util.Map.entry("cp_configuration_show_version_all", "System version"),
            java.util.Map.entry("cp_inventory_vsx_stat_v", "Virtual systems"),
            java.util.Map.entry("cp_inventory_cphaprob_stat", "Cluster member status"),
            java.util.Map.entry("cp_inventory_cphaprob_a_if", "Cluster interfaces"),
            java.util.Map.entry("cp_inventory_vsid_cphaprob_stat", "Virtual system cluster status"),
            java.util.Map.entry("cp_inventory_vsid_cphaprob_a_if", "Virtual system cluster interfaces"),
            java.util.Map.entry("cp_inventory_vsid_addr_and_route", "Virtual system addresses and routes"),
            java.util.Map.entry("cp_inventory_vsid_fw_getifs_and_route", "Virtual system interfaces and routes"),
            java.util.Map.entry("cp_inventory_vsid_ip_addr_show_state_only", "Virtual system interface state"),
            java.util.Map.entry("cp_inventory_ip_route_show_table_all", "Routing tables"),
            java.util.Map.entry("cp_inventory_ip_addr_show_v4", "IPv4 interface addresses"),
            java.util.Map.entry("cp_inventory_ip_addr_show_v6", "IPv6 interface addresses"));

    private static String description(String gateId, String command) {
        return gateId == null ? command : DESCRIPTIONS.getOrDefault(gateId, command);
    }

    private final DeviceRepository devices;
    private final com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository platformFacts;
    private final DeviceInventoryRepository inventory;
    private final JobAdmissionService admission;
    private final JobRecordDao jobs;
    private final TopologyNamePseudonymizer names;
    private final GateRegistryPort gates;
    private final com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore outputStore;
    private final com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver identities;
    private final com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker ipMasker;

    @org.springframework.beans.factory.annotation.Autowired
    public DiagnosticService(DeviceRepository devices, DeviceInventoryRepository inventory,
            JobAdmissionService admission, JobRecordDao jobs, TopologyNamePseudonymizer names, GateRegistryPort gates,
            com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess store,
            com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver identities,
            com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker ipMasker,
            com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository platformFacts) {
        this.devices = devices;
        this.platformFacts = platformFacts;
        this.inventory = inventory;
        this.admission = admission;
        this.jobs = jobs;
        this.names = names;
        this.gates = gates;
        this.outputStore=store.storeOrNull(); this.identities=identities; this.ipMasker=ipMasker;
    }

    DiagnosticService(DeviceRepository devices, DeviceInventoryRepository inventory,
            JobAdmissionService admission, JobRecordDao jobs, TopologyNamePseudonymizer names, GateRegistryPort gates,
            com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess store,
            com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver identities,
            com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker ipMasker) {
        this(devices, inventory, admission, jobs, names, gates, store, identities, ipMasker,
                com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository.NONE);
    }

    private String modelOrPlatform(String deviceId, String model) {
        return platformFacts.find(deviceId).flatMap(f -> f.platformFamily())
                .filter("gaia_embedded"::equals).orElse(model);
    }

    private boolean sshTarget(String deviceId) {
        return devices.find(deviceId).filter(d -> d.permitsReadCollection()).isPresent()
            && devices.findEndpointByDeviceId(deviceId).filter(e -> "ssh_exec".equals(e.transportKind())).isPresent();
    }

    public AdmissionResult submitRead(String deviceId, String gateId, String parameter, String requestId, String actor) {
        return submitRead(deviceId, gateId, parameter, requestId, actor, false);
    }

    public AdmissionResult submitRead(String deviceId, String gateId, String parameter, String requestId, String actor,
            boolean masked) {
        if (actor == null || !sshTarget(deviceId))
            return new AdmissionResult.Refused(masked ? "DIAGNOSTIC_RUN_NOT_PERMITTED" : "DIAGNOSTIC_UNAVAILABLE",
                    "Command or transport is not approved for this device");
        var device = devices.find(deviceId).orElseThrow();
        var read = com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead.resolve(device.vendorHint(),device.role(),
            modelOrPlatform(deviceId, devices.findSummary(deviceId).flatMap(s -> s.observedModel()).orElse(null)),gateId,parameter,gates);
        if (masked && read.isEmpty())
            return new AdmissionResult.Refused("DIAGNOSTIC_RUN_NOT_PERMITTED", "Only approved read gates are permitted");
        if (outputStore == null)
            return new AdmissionResult.Refused("DIAGNOSTIC_UNAVAILABLE", "Output store unavailable");
        return submitResolved(deviceId, read, requestId, actor);
    }

    private AdmissionResult submitResolved(String deviceId,
            Optional<com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead.Read> read, String requestId, String actor) {
        if (read.isEmpty()) return new AdmissionResult.Refused("DIAGNOSTIC_UNAVAILABLE", "Command unavailable");
        try { UUID.fromString(requestId); } catch (RuntimeException invalid) {
            return new AdmissionResult.Refused("INVALID_REQUEST_ID", "Request ID must be a UUID");
        }
        var result=jobs.insertDiagnosticRead(UUID.randomUUID().toString(),"diagnostic:"+requestId,
            deviceId,read.get().gateId(),read.get().command(),actor);
        return switch(result.kind()) {
            case "ADMITTED" -> new AdmissionResult.Admitted(result.jobId());
            case "DEDUPLICATED" -> new AdmissionResult.Deduplicated(result.jobId());
            default -> new AdmissionResult.Refused(result.kind(),"Diagnostic request refused");
        };
    }

    public java.util.List<java.util.Map<String,Object>> history(String deviceId,int page,boolean masked) {
        return jobs.diagnosticHistory(deviceId,Math.min(Math.max(0,page),100_000)*50).stream()
            .map(j -> summary(j,masked)).toList();
    }

    private java.util.Map<String,Object> summary(JobRecordDao.DiagnosticJob job,boolean masked) {
        var result=new java.util.LinkedHashMap<String,Object>();
        var device = devices.findSummary(job.targetDeviceId());
        String target=device.flatMap(d->d.observedHostname()).orElse("Unknown");
        result.put("jobId",job.jobId()); result.put("targetDeviceId",job.targetDeviceId());
        result.put("target",masked ? names.maskDeviceName(target,device.flatMap(d->d.clusterMemberRef()).orElse(null)) : target);
        result.put("command",job.command()); result.put("state",job.state()); result.put("submittedAt",job.submittedAt());
        result.put("gateId",job.gateId());
        result.put("description",description(job.gateId(),job.command()));
        result.put("startedAt",job.startedAt());
        result.put("finishedAt",job.finishedAt());
        result.put("durationMs",job.startedAt() == null ? null : Math.max(0,
                Duration.between(job.startedAt(),job.finishedAt() == null ? Instant.now() : job.finishedAt()).toMillis()));
        String actor=identities.resolve(job.actor()).map(r -> r.localIdentityName()).orElse(job.actor());
        result.put("actor",masked ? names.maskSerial(job.actor()) : actor);
        result.put("exitStatus",job.exitStatus());
        return result;
    }

    public Optional<java.util.Map<String,Object>> output(String jobId,String actor,boolean masked) {
        return result(jobId).map(job -> {
            var answer=summary(job,masked);
            answer.put("lineCount",job.lineCount()); answer.put("shapeId",job.shapeId());
            answer.put("statusPresent",job.statusPresent()); answer.put("statusToken",job.statusToken());
            answer.put("masked",masked);
            answer.put("queuePosition","REQUESTED".equals(job.state())
                    ? jobs.diagnosticQueuePosition(jobId).orElse(null) : null);
            answer.put("terminalReason",jobs.find(jobId).map(r -> r.terminalReason())
                    .filter(r -> r.matches("[A-Z_]{1,100}")).orElse(null));
            String output=job.maskedOutput();
            var ref=jobs.diagnosticOutput(jobId,actor);
            if (ref.isPresent()) {
                if (outputStore==null) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"OUTPUT_STORE_UNAVAILABLE");
                try (var stream=outputStore.retrieve(new com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef(ref.get().reference()),ref.get().wrappedKey(),false)) {
                    output=new String(stream.readNBytes(com.securityexpert.nexus.ui2.platform.DiagnosticText.MAX_BYTES+256),java.nio.charset.StandardCharsets.UTF_8);
                    output=com.securityexpert.nexus.ui2.platform.DiagnosticText.scrubSecrets(output);
                    if (masked) {
                        var knownNames=new java.util.HashMap<String,String>();
                        devices.listAll().forEach(d -> d.observedHostname().ifPresent(n -> knownNames.put(n,names.maskDeviceName(n,d.clusterMemberRef().orElse(null)))));
                        output=com.securityexpert.nexus.ui2.platform.DiagnosticText.masked(output, knownNames, token -> {
                            if (knownNames.containsKey(token)) return knownNames.get(token);
                            if (token.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}(/([0-9]|[12][0-9]|3[0-2]))?")) {
                                String ip=ipMasker.maskText(token);
                                if (!ip.equals(token)) return ip;
                            }
                            return names.maskSerial(token);
                        });
                    }
                } catch (java.io.IOException failure) {
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"OUTPUT_READ_FAILED");
                }
            }
            answer.put("output",output); answer.put("maskedOutput",masked?output:null);
            answer.put("legacySummary",ref.isEmpty() && job.maskedOutput()!=null);
            return answer;
        });
    }

    public Optional<Preview> preview(String deviceId, String port) {
        return preview(deviceId, port, true);
    }

    public Optional<Preview> preview(String deviceId, String port, boolean masked) {
        if (port == null || !PORT.matcher(port).matches() || !ports(deviceId).contains(port) || !gateReady()) {
            return Optional.empty();
        }
        String label = devices.findSummary(deviceId).flatMap(s -> s.observedHostname())
                .map(name -> masked ? names.maskDeviceName(name, null) : name).orElse("Unknown");
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

    public List<TargetOption> targets(boolean masked) {
        // Measured 2026-09-30: resolving every gate row per device (81 devices x ~100 rows, one registry query each)
        // took 31 s. The registry answer depends only on the canonical key, and a device's command list only on
        // (vendor, role, model/platform): both are memoised for this one request.
        java.util.Map<com.securityexpert.nexus.ui2.capability.CanonicalCommandKey,
                List<com.securityexpert.nexus.ui2.capability.GateRow>> rowsByKey = new java.util.HashMap<>();
        com.securityexpert.nexus.ui2.capability.GateRegistryPort cachedGates =
                key -> rowsByKey.computeIfAbsent(key, gates::findByCanonicalKey);
        java.util.Map<String, List<java.util.Map<String, Object>>> commandsByProfile = new java.util.HashMap<>();
        var contextsByDevice = inventory.findLatestContextIdsByDevice();
        var platformsByDevice = platformFacts.findAll();
        return devices.listReadCollectionTargets("ssh_exec").stream()
                .map(d -> {
                    String model = Optional.ofNullable(platformsByDevice.get(d.deviceId()))
                            .flatMap(f -> f.platformFamily()).filter("gaia_embedded"::equals)
                            .orElse(d.observedModel().orElse(null));
                    String profile = d.vendorHint() + "|" + d.role() + "|" + model;
                    List<java.util.Map<String, Object>> commands = commandsByProfile.computeIfAbsent(profile, k ->
                            com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead.commands(d.vendorHint(), d.role(),
                                    model, cachedGates).stream().map(c -> java.util.Map.<String, Object>of(
                                    "gate_id", c.gateId(), "command_template", c.commandTemplate(), "description", description(c.gateId(), c.commandTemplate()), "timeout_s", c.timeoutS(), "runnable", true))
                                    .toList());
                    List<String> virtualSystems = virtualSystems(contextsByDevice.getOrDefault(d.deviceId(), List.of()));
                    if (virtualSystems.isEmpty()) commands = commands.stream()
                            .filter(c -> !((String) c.get("command_template")).contains("<VSID>")).toList();
                    return new TargetOption(d.deviceId(), d.observedHostname()
                            .map(name -> masked ? names.maskDeviceName(name, d.clusterMemberRef().orElse(null)) : name)
                            .orElse("Unknown"), d.vendorHint(),
                            d.clusterMemberRef().map(ref -> masked ? names.maskClusterName(ref) : ref).orElse(null),
                            virtualSystems, commands);
                }).toList();
    }

    private List<String> virtualSystems(List<String> contexts) {
        return contexts.stream()
                .filter(context -> !InventoryContext.PHYSICAL.equals(context))
                .filter(context -> PORT.matcher(context).matches()).distinct().sorted().toList();
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
