package com.securityexpert.nexus.ui2.worker.staticroute;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.admission.CheckPointSparkModelHint;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.BeforePathObservation;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.PreCheckVerdict;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.PreviewPlanSet;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ReadGate;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ValidatedRequest;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress;
import com.securityexpert.nexus.ui2.persistence.staticroute.StaticRoutePreviewRepository;
import com.securityexpert.nexus.ui2.persistence.staticroute.StaticRoutePreviewRepository.PreviewRow;
import com.securityexpert.nexus.ui2.worker.backup.asa.CiscoAsaExecutor;
import com.securityexpert.nexus.ui2.worker.backup.asa.CiscoAsaPlan;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.ConfiguredRoutesParse;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.CpClusterState;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.PanEffectiveRunningResult;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.PanHaResult;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.PanPendingChangesResult;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.PreCheckEvaluation;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.RibParse;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;

/**
 * Worker executor for {@code static_route_preview} (Phase R0: approved {@code CLASS_0} reads, Stage B pre-checks,
 * before-state path lookup, deterministic command-plan + inverse-plan preview; ZERO writes).
 *
 * <p>Authorities:
 * <ul>
 *   <li>{@code docs/design/OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md} (APPROVED by PO 2026-11-18)</li>
 *   <li>{@code docs/design/OPERATIONS_STATIC_ROUTE_CHANGE_CONTRACT_DRAFT_2026_11_17.md} (§1.3, §4.1, §5.1–5.4,
 *       Path-confirmation appendix)</li>
 * </ul>
 *
 * <p>Every read goes through the closed {@link ReadGate} enum, is ledgered in {@link JobStepAttemptRepository} with
 * {@code action_class = 'read'}, logs one masked shape line via {@link StaticRouteR0Parsers#formatMaskedShapeLine},
 * and discards the raw vendor response in memory (AGENTS.md Raw-evidence law). No write command or write gate exists.
 */
public final class StaticRoutePreviewJobExecutor {

    static final String ACTOR = "system:static-route-r0-worker";
    private static final System.Logger LOG = System.getLogger(StaticRoutePreviewJobExecutor.class.getName());
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern PAN_KEY = Pattern.compile("<key>\\s*([^<\\s]+)\\s*</key>");

    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceRepository devices;
    private final DeviceInventoryRepository inventory;
    private final DevicePlatformFactsRepository platformFacts;
    private final DeviceTransport ssh;
    private final DeviceTransport pan;
    private final PanCredentialResolver panCredentials;
    private final GateRegistryPort gates;
    private final StaticRoutePreviewRepository previews;
    private final Consumer<String> shapeSink;
    private final ObjectMapper json = new ObjectMapper();

    public StaticRoutePreviewJobExecutor(
            JobLeaseRepository leases,
            JobStepAttemptRepository attempts,
            DeviceRepository devices,
            DeviceInventoryRepository inventory,
            DevicePlatformFactsRepository platformFacts,
            DeviceTransport ssh,
            DeviceTransport pan,
            PanCredentialResolver panCredentials,
            GateRegistryPort gates,
            StaticRoutePreviewRepository previews) {
        this(leases, attempts, devices, inventory, platformFacts, ssh, pan, panCredentials, gates, previews,
                line -> LOG.log(System.Logger.Level.INFO, "{0}", line));
    }

    public StaticRoutePreviewJobExecutor(
            JobLeaseRepository leases,
            JobStepAttemptRepository attempts,
            DeviceRepository devices,
            DeviceInventoryRepository inventory,
            DevicePlatformFactsRepository platformFacts,
            DeviceTransport ssh,
            DeviceTransport pan,
            PanCredentialResolver panCredentials,
            GateRegistryPort gates,
            StaticRoutePreviewRepository previews,
            Consumer<String> shapeSink) {
        this.leases = Objects.requireNonNull(leases, "leases");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.platformFacts = Objects.requireNonNull(platformFacts, "platformFacts");
        this.ssh = Objects.requireNonNull(ssh, "ssh");
        this.pan = Objects.requireNonNull(pan, "pan");
        this.panCredentials = Objects.requireNonNull(panCredentials, "panCredentials");
        this.gates = Objects.requireNonNull(gates, "gates");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.shapeSink = Objects.requireNonNull(shapeSink, "shapeSink");
    }

    private static final class Abort extends RuntimeException {
        final JobState state;
        final String code;

        Abort(JobState state, String code) {
            super(code, null, false, false);
            this.state = state;
            this.code = code;
        }
    }

    private record AdmittedPreview(
            PreviewRow row,
            ValidatedRequest request,
            DeviceRecord device,
            EndpointRecord endpoint,
            Map<ReadGate, GateResolution.Known> resolvedGates,
            List<InventoryInterface> contextInterfaces,
            List<String> memberDeviceIds,
            List<String> protectedControlPlaneIps) {
    }

    private record VendorCollectionResult(
            PreCheckVerdict check1TargetReady,
            PreCheckVerdict check2IdentityHaMgmt,
            ConfiguredRoutesParse configuredRoutes,
            RibParse ribParse,
            BeforePathObservation beforePath,
            Optional<String> panAdminUser) {
    }

    public void execute(String jobId, long epoch, String deviceId, String host, int port, String credentialRef) {
        try {
            Optional<PreviewRow> pending = previews.findByJob(jobId);
            Object admitted = pending.isEmpty() ? "PREVIEW_NOT_FOUND" : admit(pending.get(), deviceId, host);
            if (admitted instanceof String refusal) {
                previews.abandonByJob(jobId, refusal);
                leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.REJECTED, ACTOR,
                        "static_route_preview_claim_check", refusal);
                return;
            }
            AdmittedPreview ready = (AdmittedPreview) admitted;
            if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR,
                    "static_route_preview_start")) {
                return;
            }
            previews.markRunning(jobId, epoch);

            try {
                if (!attempts.findByJobAndStep(jobId, 0).isEmpty()) {
                    throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
                }
                VendorCollectionResult collected = switch (ready.request().vendor()) {
                    case StaticRoutePreview.VENDOR_CHECK_POINT ->
                            collectCheckPoint(jobId, epoch, ready, host, port, credentialRef);
                    case StaticRoutePreview.VENDOR_PALO_ALTO ->
                            collectPaloAlto(jobId, epoch, ready, credentialRef);
                    case StaticRoutePreview.VENDOR_CISCO_ASA ->
                            collectCiscoAsa(jobId, epoch, ready, host, port, credentialRef);
                    default -> throw new Abort(JobState.FAILED, "VENDOR_NOT_SUPPORTED");
                };

                PreCheckEvaluation evaluation = StaticRouteR0Parsers.evaluatePreChecks(
                        "STAGE_B",
                        ready.request(),
                        collected.check1TargetReady(),
                        collected.check2IdentityHaMgmt(),
                        collected.configuredRoutes(),
                        collected.ribParse(),
                        ready.contextInterfaces(),
                        ready.protectedControlPlaneIps());

                // Recompile deterministic command & inverse plans with captured existing route attributes (for DELETE)
                PreviewPlanSet plans = StaticRoutePreview.compilePreviewPlans(
                        ready.request(),
                        ready.memberDeviceIds(),
                        evaluation.matchedExistingRoute(),
                        collected.panAdminUser());

                String overallVerdict = evaluation.overallVerdict();
                String stopCode = evaluation.stopCode();
                if (!"OBSERVED".equals(collected.beforePath().verdict())) {
                    if (StaticRouteR0Parsers.STATUS_PASS.equals(overallVerdict)) {
                        overallVerdict = StaticRouteR0Parsers.STATUS_UNKNOWN;
                        stopCode = collected.beforePath().reasonCode();
                    }
                }

                String preChecksJson = json.writeValueAsString(evaluation.checks());
                String beforePathJson = json.writeValueAsString(collected.beforePath());
                String forwardPlanJson = json.writeValueAsString(plans);
                String inversePlanJson = json.writeValueAsString(plans.inverseSteps());

                if (!previews.recordOutcome(
                        jobId,
                        epoch,
                        StaticRoutePreviewRepository.STATE_DONE,
                        overallVerdict,
                        stopCode,
                        evaluation.preRouteTableSha256().orElse(null),
                        preChecksJson,
                        beforePathJson,
                        forwardPlanJson,
                        inversePlanJson)) {
                    throw new Abort(JobState.OUTCOME_UNKNOWN, "PREVIEW_OUTCOME_NOT_RECORDED");
                }
                finish(jobId, epoch, JobState.COMPLETED,
                        stopCode != null ? stopCode : "PREVIEW_" + overallVerdict);
            } catch (Abort abort) {
                previews.abandonByJob(jobId, abort.code);
                finish(jobId, epoch, abort.state, abort.code);
            } catch (Exception failure) {
                com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(failure);
                if (failure instanceof com.securityexpert.nexus.ui2.worker.JobCancellationScope.Cancelled cancelled) {
                    throw cancelled;
                }
                previews.abandonByJob(jobId, "PREVIEW_EXECUTION_UNCERTAIN");
                finish(jobId, epoch, JobState.OUTCOME_UNKNOWN, "PREVIEW_EXECUTION_UNCERTAIN");
            }
        } finally {
            previews.abandonByJob(jobId, "JOB_ENDED_WITHOUT_OUTCOME");
        }
    }

    private Object admit(PreviewRow row, String deviceId, String host) {
        if (!deviceId.equals(row.targetDeviceId())) {
            return "TARGET_DEVICE_MISMATCH";
        }
        ValidatedRequest validated;
        try {
            validated = StaticRoutePreview.validateRequest(
                    row.unitId(),
                    row.vendor(),
                    row.routingContext(),
                    row.operation(),
                    row.destinationCidr(),
                    row.nextHopIp(),
                    row.interfaceName().orElse(null),
                    row.distance().orElse(null),
                    row.metric().orElse(null),
                    row.priority().orElse(null),
                    row.routeName().orElse(null),
                    row.deviceComment().orElse(null),
                    row.representativeHost(),
                    row.changeTicketRef().orElse(null),
                    row.reason().orElse(null));
        } catch (StaticRoutePreview.ValidationException invalid) {
            return invalid.code();
        }

        Optional<DeviceRecord> deviceOpt = devices.find(deviceId);
        if (deviceOpt.isEmpty() || !deviceOpt.get().permitsReadCollection()) {
            return "DEVICE_NOT_ELIGIBLE";
        }
        DeviceRecord device = deviceOpt.get();
        if (!"gateway".equals(device.role()) || !validated.vendor().equals(device.vendorHint())) {
            return "DEVICE_NOT_ELIGIBLE";
        }

        String expectedTransport = StaticRoutePreview.VENDOR_PALO_ALTO.equals(validated.vendor())
                ? "pan_xml_api" : "ssh_exec";
        Optional<EndpointRecord> endpointOpt = devices.findEndpointByDeviceId(deviceId)
                .filter(e -> deviceId.equals(e.deviceId()))
                .filter(e -> expectedTransport.equalsIgnoreCase(e.transportKind()));
        if (endpointOpt.isEmpty()) {
            return "TRANSPORT_NOT_ELIGIBLE";
        }

        if (StaticRoutePreview.VENDOR_CHECK_POINT.equals(validated.vendor())) {
            boolean embedded = platformFacts.find(deviceId).flatMap(f -> f.platformFamily())
                    .filter("gaia_embedded"::equals).isPresent()
                    || CheckPointSparkModelHint.isKnownSparkModel(
                            devices.findSummary(deviceId).flatMap(DeviceSummaryRecord::observedModel));
            if (embedded) {
                return "PLATFORM_NOT_SUPPORTED";
            }
        }

        List<ReadGate> requiredGates = switch (validated.vendor()) {
            case StaticRoutePreview.VENDOR_CHECK_POINT -> List.of(
                    ReadGate.R0_CP_4_CPHAPROB_STAT,
                    ReadGate.R0_CP_1_SHOW_ROUTE_STATIC,
                    ReadGate.R0_CP_3_IP_ROUTE_SHOW_TABLE_ALL,
                    ReadGate.R0_CP_2_IP_ROUTE_GET);
            case StaticRoutePreview.VENDOR_PALO_ALTO -> List.of(
                    ReadGate.R0_PAN_3_CHECK_PENDING_CHANGES,
                    ReadGate.R0_PAN_5_SHOW_HA_STATE,
                    ReadGate.R0_PAN_4_EFFECTIVE_RUNNING,
                    ReadGate.R0_PAN_1_SHOW_VR_STATIC_ROUTE,
                    ReadGate.R0_PAN_6_SHOW_ROUTING_ROUTE,
                    ReadGate.R0_PAN_2_FIB_LOOKUP);
            case StaticRoutePreview.VENDOR_CISCO_ASA -> {
                List<ReadGate> asaGates = new ArrayList<>(List.of(
                        ReadGate.R0_ASA_2_SHOW_CURPRIV,
                        ReadGate.R0_ASA_1_SHOW_MODE,
                        ReadGate.R0_ASA_4_SHOW_RUNNING_ROUTE,
                        ReadGate.R0_ASA_5_SHOW_ROUTE,
                        ReadGate.R0_ASA_6_SHOW_ROUTE_HOST));
                if (!StaticRoutePreview.ASA_SINGLE_CONTEXT.equals(validated.routingContext())) {
                    asaGates.add(ReadGate.R0_ASA_3_CHANGETO_CONTEXT);
                }
                yield List.copyOf(asaGates);
            }
            default -> List.of();
        };

        Map<ReadGate, GateResolution.Known> resolved = new EnumMap<>(ReadGate.class);
        for (ReadGate g : requiredGates) {
            Optional<GateResolution.Known> k = StaticRoutePreview.resolve(g, gates);
            if (k.isEmpty()) {
                return "GATE_UNAVAILABLE";
            }
            resolved.put(g, k.get());
        }

        List<InventoryInterface> contextInterfaces = List.of();
        Optional<InventoryRun> latestInventory = inventory.findLatestRun(deviceId);
        if (latestInventory.isPresent()) {
            String invCtxName = StaticRoutePreview.VENDOR_CISCO_ASA.equals(validated.vendor())
                    && !StaticRoutePreview.ASA_SINGLE_CONTEXT.equals(validated.routingContext())
                    ? validated.routingContext()
                    : InventoryContext.PHYSICAL;
            contextInterfaces = latestInventory.get().contexts().stream()
                    .filter(c -> invCtxName.equals(c.context()) || InventoryContext.PHYSICAL.equals(c.context()))
                    .findFirst()
                    .map(InventoryContext::interfaces)
                    .orElse(List.of());
        }

        Set<String> protectedIps = new LinkedHashSet<>();
        if (host != null && !host.isBlank()) {
            protectedIps.add(host);
        }
        String endpHost = EndpointAddress.host(endpointOpt.get().addressRef());
        if (endpHost != null && !endpHost.isBlank()) {
            protectedIps.add(endpHost);
        }

        List<String> memberIds = new ArrayList<>();
        List<DeviceSummaryRecord> clusterMembers = new ArrayList<>(devices.findMembersByClusterRef(validated.unitId()));
        if (!clusterMembers.isEmpty()) {
            // Sort standby first on Check Point per Contract §3.1, else deterministic by deviceId
            clusterMembers.sort(Comparator
                    .comparing((DeviceSummaryRecord m) ->
                            m.observedHaRole().map(r -> "STANDBY".equalsIgnoreCase(r) ? 0 : 1).orElse(1))
                    .thenComparing(DeviceSummaryRecord::deviceId));
            for (DeviceSummaryRecord m : clusterMembers) {
                memberIds.add(m.deviceId());
                devices.findEndpointByDeviceId(m.deviceId())
                        .map(e -> EndpointAddress.host(e.addressRef()))
                        .filter(h -> h != null && !h.isBlank())
                        .ifPresent(protectedIps::add);
            }
        }
        if (memberIds.isEmpty()) {
            memberIds.add(deviceId);
        }

        return new AdmittedPreview(
                row,
                validated,
                device,
                endpointOpt.get(),
                resolved,
                contextInterfaces,
                List.copyOf(memberIds),
                List.copyOf(protectedIps));
    }

    // ---- 1. Check Point Gaia Execution ----

    private VendorCollectionResult collectCheckPoint(
            String jobId,
            long epoch,
            AdmittedPreview ready,
            String host,
            int port,
            String credentialRef) {

        ValidatedRequest req = ready.request();
        TransportSession session = null;
        try {
            ConnectResult connected = ssh.connect(
                    new ConnectionTarget(ready.endpoint().endpointId(), host, port),
                    new ConnectSpec(credentialRef, PersistedManagementEndpointTrustResolver.scopeRef(host, port), Optional.empty()),
                    CONNECT_TIMEOUT);
            if (!(connected instanceof ConnectResult.Authenticated auth)) {
                String code = connected instanceof ConnectResult.HostKeyRejected ? "TRUST_REQUIRED" : "TARGET_UNREACHABLE";
                return unreachableResult(req, code, "SSH connection could not be established.");
            }
            session = auth.session();
            PreCheckVerdict check1 = new PreCheckVerdict(
                    1, StaticRouteR0Parsers.CHECK_1_TARGET_READY, "STAGE_B",
                    StaticRouteR0Parsers.STATUS_PASS, "SSH_SESSION_READY",
                    "Trusted SSH session established.");

            // Step 0: R0-CP-4 cphaprob stat
            Optional<String> haOut = runSshExecRead(
                    jobId, epoch, 0, session,
                    ready.resolvedGates().get(ReadGate.R0_CP_4_CPHAPROB_STAT),
                    ReadGate.R0_CP_4_CPHAPROB_STAT.template());
            CpClusterState clusterState = StaticRouteR0Parsers.parseCpClusterState(haOut.orElse(null));
            logShape(req.vendor(), ReadGate.R0_CP_4_CPHAPROB_STAT.gateId(), haOut.orElse(null),
                    !StaticRouteR0Parsers.STATUS_UNKNOWN.equals(clusterState.status()));

            PreCheckVerdict check2 = new PreCheckVerdict(
                    2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B",
                    clusterState.status(), clusterState.reasonCode(),
                    "Check Point HA/cluster mode check: " + clusterState.reasonCode());

            // Step 1: R0-CP-1 clish -c 'show route static'
            Optional<String> staticOut = runSshExecRead(
                    jobId, epoch, 1, session,
                    ready.resolvedGates().get(ReadGate.R0_CP_1_SHOW_ROUTE_STATIC),
                    ReadGate.R0_CP_1_SHOW_ROUTE_STATIC.template());
            ConfiguredRoutesParse configRoutes = StaticRouteR0Parsers.parseCpStaticRoutes(staticOut.orElse(null));
            logShape(req.vendor(), ReadGate.R0_CP_1_SHOW_ROUTE_STATIC.gateId(), staticOut.orElse(null),
                    StaticRouteR0Parsers.STATUS_PASS.equals(configRoutes.status()));

            // Step 2: R0-CP-3 ip -4 route show table all
            Optional<String> ribOut = runSshExecRead(
                    jobId, epoch, 2, session,
                    ready.resolvedGates().get(ReadGate.R0_CP_3_IP_ROUTE_SHOW_TABLE_ALL),
                    ReadGate.R0_CP_3_IP_ROUTE_SHOW_TABLE_ALL.template());
            RibParse ribParse = StaticRouteR0Parsers.parseCpRib(ribOut.orElse(null));
            logShape(req.vendor(), ReadGate.R0_CP_3_IP_ROUTE_SHOW_TABLE_ALL.gateId(), ribOut.orElse(null),
                    StaticRouteR0Parsers.STATUS_PASS.equals(ribParse.status()));

            // Step 3: R0-CP-2 ip -4 route get <DST>
            String routeGetCmd = StaticRoutePreview.cpIpRouteGet(req.representativeHost());
            Optional<String> getOut = runSshExecRead(
                    jobId, epoch, 3, session,
                    ready.resolvedGates().get(ReadGate.R0_CP_2_IP_ROUTE_GET),
                    routeGetCmd);
            BeforePathObservation beforePath = StaticRouteR0Parsers.parseCpRouteGet(
                    getOut.orElse(null), req.representativeHost(), ribParse.routes());
            logShape(req.vendor(), ReadGate.R0_CP_2_IP_ROUTE_GET.gateId(), getOut.orElse(null),
                    "OBSERVED".equals(beforePath.verdict()));

            return new VendorCollectionResult(check1, check2, configRoutes, ribParse, beforePath, Optional.empty());
        } finally {
            if (session != null) {
                ssh.disconnect(session);
            }
        }
    }

    // ---- 2. Palo Alto Networks Execution ----

    private VendorCollectionResult collectPaloAlto(
            String jobId,
            long epoch,
            AdmittedPreview ready,
            String credentialRef) {

        ValidatedRequest req = ready.request();
        ApiTarget apiTarget = new ApiTarget(ready.endpoint().endpointId(), ready.endpoint().addressRef());
        Optional<PanAuth> authOpt = resolvePanAuth(apiTarget, credentialRef);
        if (authOpt.isEmpty()) {
            return unreachableResult(req, "TARGET_UNREACHABLE", "Palo Alto XML API keygen failed or timed out.");
        }
        String apiKey = authOpt.get().apiKey();
        Optional<String> adminUser = authOpt.get().adminUser();

        // Step 0: R0-PAN-3 <check><pending-changes/></check>
        Optional<String> pendingXml = runPanOpRead(
                jobId, epoch, 0, apiTarget, apiKey,
                ready.resolvedGates().get(ReadGate.R0_PAN_3_CHECK_PENDING_CHANGES),
                "<check><pending-changes/></check>",
                StaticRoutePreview.OUTPUT_LIMIT_BYTES);
        PanPendingChangesResult pendingChanges = StaticRouteR0Parsers.parsePanPendingChanges(pendingXml.orElse(null));
        logShape(req.vendor(), ReadGate.R0_PAN_3_CHECK_PENDING_CHANGES.gateId(), pendingXml.orElse(null),
                !StaticRouteR0Parsers.STATUS_UNKNOWN.equals(pendingChanges.status()));

        PreCheckVerdict check1 = new PreCheckVerdict(
                1, StaticRouteR0Parsers.CHECK_1_TARGET_READY, "STAGE_B",
                pendingChanges.status(), pendingChanges.reasonCode(),
                "Palo Alto pending-changes check: " + pendingChanges.reasonCode());

        // Step 1: R0-PAN-5 <show><high-availability><state/></high-availability></show>
        Optional<String> haXml = runPanOpRead(
                jobId, epoch, 1, apiTarget, apiKey,
                ready.resolvedGates().get(ReadGate.R0_PAN_5_SHOW_HA_STATE),
                ReadGate.R0_PAN_5_SHOW_HA_STATE.template(),
                StaticRoutePreview.OUTPUT_LIMIT_BYTES);
        PanHaResult haResult = StaticRouteR0Parsers.parsePanHaState(haXml.orElse(null));
        logShape(req.vendor(), ReadGate.R0_PAN_5_SHOW_HA_STATE.gateId(), haXml.orElse(null),
                !StaticRouteR0Parsers.STATUS_UNKNOWN.equals(haResult.status()));

        // Step 2: R0-PAN-4 <show><config><effective-running/></config></show>
        Optional<String> effXml = runPanOpRead(
                jobId, epoch, 2, apiTarget, apiKey,
                ready.resolvedGates().get(ReadGate.R0_PAN_4_EFFECTIVE_RUNNING),
                ReadGate.R0_PAN_4_EFFECTIVE_RUNNING.template(),
                StaticRoutePreview.EFFECTIVE_RUNNING_LIMIT_BYTES);
        PanEffectiveRunningResult effResult = StaticRouteR0Parsers.parsePanEffectiveRunning(
                effXml.orElse(null), req.routingContext(), req.prefix().canonicalCidr(), req.routeName());
        logShape(req.vendor(), ReadGate.R0_PAN_4_EFFECTIVE_RUNNING.gateId(), effXml.orElse(null),
                !StaticRouteR0Parsers.STATUS_UNKNOWN.equals(effResult.status()));

        PreCheckVerdict check2 = combinePanCheck2(haResult, effResult);

        // Step 3: R0-PAN-1 type=config&action=show&xpath=.../static-route
        String xpath = StaticRoutePreview.panShowVrStaticRouteXpath(req.routingContext());
        Optional<String> vrRoutesXml = runPanConfigShowRead(
                jobId, epoch, 3, apiTarget, apiKey,
                ready.resolvedGates().get(ReadGate.R0_PAN_1_SHOW_VR_STATIC_ROUTE),
                xpath,
                StaticRoutePreview.OUTPUT_LIMIT_BYTES);
        ConfiguredRoutesParse configRoutes = StaticRouteR0Parsers.parsePanVrStaticRoutes(vrRoutesXml.orElse(null));
        logShape(req.vendor(), ReadGate.R0_PAN_1_SHOW_VR_STATIC_ROUTE.gateId(), vrRoutesXml.orElse(null),
                StaticRouteR0Parsers.STATUS_PASS.equals(configRoutes.status()));

        // Step 4: R0-PAN-6 <show><routing><route/></routing></show>
        Optional<String> ribXml = runPanOpRead(
                jobId, epoch, 4, apiTarget, apiKey,
                ready.resolvedGates().get(ReadGate.R0_PAN_6_SHOW_ROUTING_ROUTE),
                ReadGate.R0_PAN_6_SHOW_ROUTING_ROUTE.template(),
                StaticRoutePreview.OUTPUT_LIMIT_BYTES);
        RibParse ribParse = StaticRouteR0Parsers.parsePanRib(ribXml.orElse(null), req.routingContext());
        logShape(req.vendor(), ReadGate.R0_PAN_6_SHOW_ROUTING_ROUTE.gateId(), ribXml.orElse(null),
                StaticRouteR0Parsers.STATUS_PASS.equals(ribParse.status()));

        // Step 5: R0-PAN-2 <test><routing><fib-lookup>...
        String fibCmd = StaticRoutePreview.panFibLookupCmd(req.routingContext(), req.representativeHost());
        Optional<String> fibXml = runPanOpRead(
                jobId, epoch, 5, apiTarget, apiKey,
                ready.resolvedGates().get(ReadGate.R0_PAN_2_FIB_LOOKUP),
                fibCmd,
                StaticRoutePreview.OUTPUT_LIMIT_BYTES);
        BeforePathObservation beforePath = StaticRouteR0Parsers.parsePanFibLookup(
                fibXml.orElse(null), req.representativeHost(), ribParse.routes());
        logShape(req.vendor(), ReadGate.R0_PAN_2_FIB_LOOKUP.gateId(), fibXml.orElse(null),
                "OBSERVED".equals(beforePath.verdict()));

        return new VendorCollectionResult(check1, check2, configRoutes, ribParse, beforePath, adminUser);
    }

    private static PreCheckVerdict combinePanCheck2(PanHaResult ha, PanEffectiveRunningResult eff) {
        if (StaticRouteR0Parsers.STATUS_FAIL.equals(eff.status())) {
            return new PreCheckVerdict(
                    2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B",
                    StaticRouteR0Parsers.STATUS_FAIL, eff.reasonCode(),
                    "Palo Alto effective-running check failed: " + eff.reasonCode());
        }
        if (StaticRouteR0Parsers.STATUS_FAIL.equals(ha.status())) {
            return new PreCheckVerdict(
                    2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B",
                    StaticRouteR0Parsers.STATUS_FAIL, ha.reasonCode(),
                    "Palo Alto HA state check failed: " + ha.reasonCode());
        }
        if (StaticRouteR0Parsers.STATUS_UNKNOWN.equals(eff.status())) {
            return new PreCheckVerdict(
                    2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B",
                    StaticRouteR0Parsers.STATUS_UNKNOWN, eff.reasonCode(),
                    "Palo Alto effective-running check returned UNKNOWN.");
        }
        if (StaticRouteR0Parsers.STATUS_UNKNOWN.equals(ha.status())) {
            return new PreCheckVerdict(
                    2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B",
                    StaticRouteR0Parsers.STATUS_UNKNOWN, ha.reasonCode(),
                    "Palo Alto HA state check returned UNKNOWN.");
        }
        return new PreCheckVerdict(
                2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B",
                StaticRouteR0Parsers.STATUS_PASS, "PAN_VR_AND_HA_VERIFIED",
                "Virtual router provenance (src != tpl) and HA state verified.");
    }

    // ---- 3. Cisco ASA Execution ----

    private VendorCollectionResult collectCiscoAsa(
            String jobId,
            long epoch,
            AdmittedPreview ready,
            String host,
            int port,
            String credentialRef) {

        ValidatedRequest req = ready.request();
        TransportSession session = null;
        try {
            ConnectResult connected = ssh.connect(
                    new ConnectionTarget(ready.endpoint().endpointId(), host, port),
                    new ConnectSpec(credentialRef, PersistedManagementEndpointTrustResolver.scopeRef(host, port), Optional.empty()),
                    CONNECT_TIMEOUT);
            if (!(connected instanceof ConnectResult.Authenticated auth)) {
                String code = connected instanceof ConnectResult.HostKeyRejected ? "TRUST_REQUIRED" : "TARGET_UNREACHABLE";
                return unreachableResult(req, code, "Cisco ASA SSH connection could not be established.");
            }
            session = auth.session();
            int step = 0;

            // Step 0: R0-ASA-2 show curpriv
            Optional<String> privOut = runAsaInteractiveRead(
                    jobId, epoch, step++, session,
                    ready.resolvedGates().get(ReadGate.R0_ASA_2_SHOW_CURPRIV),
                    CiscoAsaPlan.SHOW_CURPRIV);
            Optional<Integer> privLevel = privOut.flatMap(CiscoAsaPlan::parsePrivilege);
            logShape(req.vendor(), ReadGate.R0_ASA_2_SHOW_CURPRIV.gateId(), privOut.orElse(null), privLevel.isPresent());
            if (privLevel.isEmpty() || privLevel.get() < 15) {
                PreCheckVerdict c1 = new PreCheckVerdict(
                        1, StaticRouteR0Parsers.CHECK_1_TARGET_READY, "STAGE_B",
                        privLevel.isEmpty() ? StaticRouteR0Parsers.STATUS_UNKNOWN : StaticRouteR0Parsers.STATUS_FAIL,
                        "UNEXPECTED_PROMPT",
                        "Cisco ASA privilege level 15 could not be verified.");
                return earlyStopResult(req, c1,
                        new PreCheckVerdict(2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B",
                                StaticRouteR0Parsers.STATUS_UNKNOWN, "NOT_REACHED", "Stopped at privilege check."));
            }

            // Step 1: R0-ASA-1 show mode
            Optional<String> modeOut = runAsaInteractiveRead(
                    jobId, epoch, step++, session,
                    ready.resolvedGates().get(ReadGate.R0_ASA_1_SHOW_MODE),
                    CiscoAsaPlan.SHOW_MODE);
            String modeWord = modeOut.map(CiscoAsaPlan::contextModeWord).orElse("unknown");
            logShape(req.vendor(), ReadGate.R0_ASA_1_SHOW_MODE.gateId(), modeOut.orElse(null),
                    !"unknown".equals(modeWord));
            if ("unknown".equals(modeWord)) {
                PreCheckVerdict c1 = new PreCheckVerdict(
                        1, StaticRouteR0Parsers.CHECK_1_TARGET_READY, "STAGE_B",
                        StaticRouteR0Parsers.STATUS_UNKNOWN, "MODE_UNKNOWN",
                        "Cisco ASA show mode output was not recognised.");
                return earlyStopResult(req, c1,
                        new PreCheckVerdict(2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B",
                                StaticRouteR0Parsers.STATUS_UNKNOWN, "MODE_UNKNOWN", "Cisco ASA context mode is UNKNOWN."));
            }

            if ("single".equals(modeWord)) {
                if (!StaticRoutePreview.ASA_SINGLE_CONTEXT.equals(req.routingContext())) {
                    PreCheckVerdict c1 = new PreCheckVerdict(
                            1, StaticRouteR0Parsers.CHECK_1_TARGET_READY, "STAGE_B",
                            StaticRouteR0Parsers.STATUS_PASS, "ASA_SINGLE_MODE_READY", "Privilege 15 verified.");
                    PreCheckVerdict c2 = new PreCheckVerdict(
                            2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B",
                            StaticRouteR0Parsers.STATUS_FAIL, "ROUTING_CONTEXT_NOT_FOUND",
                            "Device is in single-context mode, but non-single context was requested.");
                    return earlyStopResult(req, c1, c2);
                }
            } else {
                // Device is in multiple-context mode: changeto context <CTX_NAME> is required
                if (StaticRoutePreview.ASA_SINGLE_CONTEXT.equals(req.routingContext())) {
                    PreCheckVerdict c1 = new PreCheckVerdict(
                            1, StaticRouteR0Parsers.CHECK_1_TARGET_READY, "STAGE_B",
                            StaticRouteR0Parsers.STATUS_PASS, "ASA_MULTIPLE_MODE_READY", "Privilege 15 verified.");
                    PreCheckVerdict c2 = new PreCheckVerdict(
                            2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B",
                            StaticRouteR0Parsers.STATUS_FAIL, "ROUTING_CONTEXT_UNSUPPORTED",
                            "Device is in multiple-context mode; a named security context is required.");
                    return earlyStopResult(req, c1, c2);
                }
                String changetoCmd = StaticRoutePreview.asaChangetoContext(req.routingContext());
                Optional<String> changetoOut = runAsaInteractiveRead(
                        jobId, epoch, step++, session,
                        ready.resolvedGates().get(ReadGate.R0_ASA_3_CHANGETO_CONTEXT),
                        changetoCmd);
                ssh.resyncPrompt(session, Duration.ofMillis(500));
                String promptCandidate = session.interactivePrompt()
                        .orElseGet(() -> changetoOut.orElse(""));
                boolean promptVerified = StaticRouteR0Parsers.verifyAsaContextPrompt(promptCandidate, req.routingContext())
                        || StaticRouteR0Parsers.verifyAsaContextPrompt(changetoOut.orElse(""), req.routingContext());
                logShape(req.vendor(), ReadGate.R0_ASA_3_CHANGETO_CONTEXT.gateId(), promptCandidate, promptVerified);
                if (changetoOut.isEmpty() || !promptVerified) {
                    // Per R0-ASA-3: any prompt other than <host>/<CTX_NAME># aborts with UNEXPECTED_PROMPT; nothing further is sent.
                    PreCheckVerdict c1 = new PreCheckVerdict(
                            1, StaticRouteR0Parsers.CHECK_1_TARGET_READY, "STAGE_B",
                            StaticRouteR0Parsers.STATUS_FAIL, "UNEXPECTED_PROMPT",
                            "Prompt after changeto context did not match <host>/" + req.routingContext() + "#.");
                    PreCheckVerdict c2 = new PreCheckVerdict(
                            2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B",
                            StaticRouteR0Parsers.STATUS_FAIL, "UNEXPECTED_PROMPT",
                            "Context switch aborted before route reads.");
                    return earlyStopResult(req, c1, c2);
                }

                // Re-check show curpriv after context switch per R0-ASA-2
                Optional<String> ctxPrivOut = runAsaInteractiveRead(
                        jobId, epoch, step++, session,
                        ready.resolvedGates().get(ReadGate.R0_ASA_2_SHOW_CURPRIV),
                        CiscoAsaPlan.SHOW_CURPRIV);
                Optional<Integer> ctxPriv = ctxPrivOut.flatMap(CiscoAsaPlan::parsePrivilege);
                if (ctxPriv.isEmpty() || ctxPriv.get() < 15) {
                    PreCheckVerdict c1 = new PreCheckVerdict(
                            1, StaticRouteR0Parsers.CHECK_1_TARGET_READY, "STAGE_B",
                            StaticRouteR0Parsers.STATUS_FAIL, "UNEXPECTED_PROMPT",
                            "Privilege level inside context is below 15.");
                    PreCheckVerdict c2 = new PreCheckVerdict(
                            2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B",
                            StaticRouteR0Parsers.STATUS_FAIL, "UNEXPECTED_PROMPT",
                            "Privilege level inside context is below 15.");
                    return earlyStopResult(req, c1, c2);
                }
            }

            PreCheckVerdict check1 = new PreCheckVerdict(
                    1, StaticRouteR0Parsers.CHECK_1_TARGET_READY, "STAGE_B",
                    StaticRouteR0Parsers.STATUS_PASS, "ASA_SESSION_READY",
                    "Privilege 15 and expected prompt verified.");
            PreCheckVerdict check2 = new PreCheckVerdict(
                    2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B",
                    StaticRouteR0Parsers.STATUS_PASS, "ASA_CONTEXT_VERIFIED",
                    "Cisco ASA context mode (" + modeWord + ") verified.");

            // Next step: R0-ASA-4 show running-config route
            Optional<String> runRouteOut = runAsaInteractiveRead(
                    jobId, epoch, step++, session,
                    ready.resolvedGates().get(ReadGate.R0_ASA_4_SHOW_RUNNING_ROUTE),
                    ReadGate.R0_ASA_4_SHOW_RUNNING_ROUTE.template());
            ConfiguredRoutesParse configRoutes = StaticRouteR0Parsers.parseAsaRunningConfigRoutes(runRouteOut.orElse(null));
            logShape(req.vendor(), ReadGate.R0_ASA_4_SHOW_RUNNING_ROUTE.gateId(), runRouteOut.orElse(null),
                    StaticRouteR0Parsers.STATUS_PASS.equals(configRoutes.status()));

            // Next step: R0-ASA-5 show route
            Optional<String> ribOut = runAsaInteractiveRead(
                    jobId, epoch, step++, session,
                    ready.resolvedGates().get(ReadGate.R0_ASA_5_SHOW_ROUTE),
                    ReadGate.R0_ASA_5_SHOW_ROUTE.template());
            RibParse ribParse = StaticRouteR0Parsers.parseAsaRib(ribOut.orElse(null));
            logShape(req.vendor(), ReadGate.R0_ASA_5_SHOW_ROUTE.gateId(), ribOut.orElse(null),
                    StaticRouteR0Parsers.STATUS_PASS.equals(ribParse.status()));

            // Next step: R0-ASA-6 show route <HOST>
            String showHostCmd = StaticRoutePreview.asaShowRouteHost(req.representativeHost());
            Optional<String> hostRouteOut = runAsaInteractiveRead(
                    jobId, epoch, step, session,
                    ready.resolvedGates().get(ReadGate.R0_ASA_6_SHOW_ROUTE_HOST),
                    showHostCmd);
            BeforePathObservation beforePath = StaticRouteR0Parsers.parseAsaRouteHost(
                    hostRouteOut.orElse(null), req.representativeHost());
            logShape(req.vendor(), ReadGate.R0_ASA_6_SHOW_ROUTE_HOST.gateId(), hostRouteOut.orElse(null),
                    "OBSERVED".equals(beforePath.verdict()));

            return new VendorCollectionResult(check1, check2, configRoutes, ribParse, beforePath, Optional.empty());
        } finally {
            if (session != null) {
                ssh.disconnect(session);
            }
        }
    }

    // ---- Transport & Attempt Ledger Helpers (Read-only, zero transcript recording) ----

    private Optional<String> runSshExecRead(
            String jobId,
            long epoch,
            int step,
            TransportSession session,
            GateResolution.Known gate,
            String command) {
        String attempt = preContact(jobId, epoch, step, gate);
        ExecResult result = JobTranscriptScope.withoutRecording(() ->
                ssh.exec(session, new ExecSpec(command, true, 0, StaticRoutePreview.OUTPUT_LIMIT_BYTES),
                        Duration.ofSeconds(gate.timeoutS())));
        if (!(result instanceof ExecResult.Completed completed) || completed.output() == null) {
            writeAttemptOutcome(attempt, epoch, "OUTPUT_UNAVAILABLE", 0);
            return Optional.empty();
        }
        String out = completed.output();
        writeAttemptOutcome(attempt, epoch, null, bytes(out));
        return Optional.of(out);
    }

    private Optional<String> runAsaInteractiveRead(
            String jobId,
            long epoch,
            int step,
            TransportSession session,
            GateResolution.Known gate,
            String command) {
        String attempt = preContact(jobId, epoch, step, gate);
        ExecResult result = JobTranscriptScope.withoutRecording(() ->
                ssh.execInteractive(session, new ExecSpec(command, true), Duration.ofSeconds(gate.timeoutS())));
        if (result instanceof ExecResult.ChannelFailed failed && "empty output".equals(failed.reason())) {
            writeAttemptOutcome(attempt, epoch, null, 0);
            return Optional.of("");
        }
        if (!(result instanceof ExecResult.Completed completed)
                || completed.output() == null
                || CiscoAsaPlan.isCliError(completed.output())
                || completed.output().length() > StaticRoutePreview.OUTPUT_LIMIT_BYTES) {
            writeAttemptOutcome(attempt, epoch, "COMMAND_UNAVAILABLE", 0);
            return Optional.empty();
        }
        String stripped = CiscoAsaExecutor.stripEcho(completed.output(), command);
        writeAttemptOutcome(attempt, epoch, null, bytes(stripped));
        return Optional.of(stripped);
    }

    private Optional<String> runPanOpRead(
            String jobId,
            long epoch,
            int step,
            ApiTarget target,
            String apiKey,
            GateResolution.Known gate,
            String cmdXml,
            int maxBytes) {
        String attempt = preContact(jobId, epoch, step, gate);
        XmlApiResult result = JobTranscriptScope.withoutRecording(() ->
                pan.xmlApiCall(target,
                        new XmlApiSpec("POST", "op", "", "direct_firewall", Map.of("cmd", cmdXml), Map.of("X-PAN-KEY", apiKey)),
                        Duration.ofSeconds(gate.timeoutS())));
        String body = result instanceof XmlApiResult.Completed c && c.httpStatus() == 200 ? c.body() : null;
        if (body == null || body.length() > maxBytes) {
            writeAttemptOutcome(attempt, epoch, body == null ? "API_UNAVAILABLE" : "ANSWER_TOO_LARGE", 0);
            return Optional.empty();
        }
        writeAttemptOutcome(attempt, epoch, null, bytes(body));
        return Optional.of(body);
    }

    private Optional<String> runPanConfigShowRead(
            String jobId,
            long epoch,
            int step,
            ApiTarget target,
            String apiKey,
            GateResolution.Known gate,
            String xpath,
            int maxBytes) {
        String attempt = preContact(jobId, epoch, step, gate);
        // Strictly action="show" (active running config), never action="get" (candidate config)
        XmlApiResult result = JobTranscriptScope.withoutRecording(() ->
                pan.xmlApiCall(target,
                        new XmlApiSpec("POST", "config", "show", "direct_firewall",
                                Map.of("xpath", xpath), Map.of("X-PAN-KEY", apiKey)),
                        Duration.ofSeconds(gate.timeoutS())));
        String body = result instanceof XmlApiResult.Completed c && c.httpStatus() == 200 ? c.body() : null;
        if (body == null || body.length() > maxBytes) {
            writeAttemptOutcome(attempt, epoch, body == null ? "API_UNAVAILABLE" : "ANSWER_TOO_LARGE", 0);
            return Optional.empty();
        }
        writeAttemptOutcome(attempt, epoch, null, bytes(body));
        return Optional.of(body);
    }

    private record PanAuth(String apiKey, Optional<String> adminUser) {
    }

    private Optional<PanAuth> resolvePanAuth(ApiTarget target, String credentialRef) {
        try {
            var credential = panCredentials.resolve(credentialRef);
            var result = JobTranscriptScope.withoutRecording(() ->
                    pan.xmlApiCall(target,
                            new XmlApiSpec("POST", "keygen", "", "",
                                    Map.of("user", credential.username(), "password", new String(credential.password())),
                                    Map.of()),
                            CONNECT_TIMEOUT));
            String body = result instanceof XmlApiResult.Completed c && c.httpStatus() == 200 ? c.body() : "";
            Matcher match = PAN_KEY.matcher(body == null ? "" : body);
            if (body != null && body.contains("status=\"success\"") && match.find()) {
                Optional<String> user = Optional.ofNullable(credential.username())
                        .filter(u -> !u.isBlank() && u.matches("\\A[A-Za-z0-9_.-]{1,31}\\z"));
                return Optional.of(new PanAuth(match.group(1), user));
            }
            return Optional.empty();
        } catch (RuntimeException unavailable) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unavailable);
            if (unavailable instanceof com.securityexpert.nexus.ui2.worker.JobCancellationScope.Cancelled cancelled) {
                throw cancelled;
            }
            return Optional.empty();
        }
    }

    private String preContact(String jobId, long epoch, int step, GateResolution.Known gate) {
        if (!attempts.findByJobAndStep(jobId, step).isEmpty()) {
            throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
        }
        String attempt = attempts.insertPreContact(jobId, epoch, step, gate.gateId(), "read", 1);
        if (attempt == null || !attempts.markBoundaryCrossed(attempt, epoch)) {
            throw new Abort(JobState.OUTCOME_UNKNOWN, "PRE_CONTACT_UNCERTAIN");
        }
        return attempt;
    }

    private void writeAttemptOutcome(String attempt, long epoch, String failure, long bytes) {
        if (!attempts.writeOutcome(
                attempt,
                epoch,
                failure == null ? "MATCHED" : "EXPECTATION_UNMET",
                failure,
                failure == null,
                bytes,
                0L,
                null)) {
            throw new Abort(JobState.OUTCOME_UNKNOWN, "ATTEMPT_RECORD_FAILED");
        }
    }

    private void logShape(String vendor, String gateId, String rawOutput, boolean parsed) {
        String shapeLine = StaticRouteR0Parsers.formatMaskedShapeLine(vendor, gateId, rawOutput, parsed);
        try {
            shapeSink.accept(shapeLine);
        } catch (RuntimeException ignored) {
            // Shape sink never alters preview execution.
        }
    }

    private static VendorCollectionResult unreachableResult(ValidatedRequest req, String code, String detail) {
        PreCheckVerdict c1 = new PreCheckVerdict(
                1, StaticRouteR0Parsers.CHECK_1_TARGET_READY, "STAGE_B",
                StaticRouteR0Parsers.STATUS_FAIL, code, detail);
        PreCheckVerdict c2 = new PreCheckVerdict(
                2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B",
                StaticRouteR0Parsers.STATUS_UNKNOWN, code, "Target unreachable.");
        return earlyStopResult(req, c1, c2);
    }

    private static VendorCollectionResult earlyStopResult(
            ValidatedRequest req,
            PreCheckVerdict c1,
            PreCheckVerdict c2) {
        return new VendorCollectionResult(
                c1,
                c2,
                new ConfiguredRoutesParse(StaticRouteR0Parsers.STATUS_UNKNOWN, c1.reasonCode(), List.of(), null),
                new RibParse(StaticRouteR0Parsers.STATUS_UNKNOWN, c1.reasonCode(), List.of()),
                new BeforePathObservation(req.representativeHost(), StaticRouteR0Parsers.STATUS_UNKNOWN,
                        c1.reasonCode(), null, null, null, null, null),
                Optional.empty());
    }

    private static long bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private void finish(String jobId, long epoch, JobState state, String reason) {
        leases.transitionState(jobId, epoch, JobState.EXECUTING, state, ACTOR, "static_route_preview_finished", reason);
    }
}
