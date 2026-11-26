package com.securityexpert.nexus.ui2.service.staticroute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.BeforePathObservation;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.CompiledPreviewStep;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.PreCheckVerdict;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.PreviewPlanSet;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ValidationException;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ValidatedRequest;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress;
import com.securityexpert.nexus.ui2.persistence.staticroute.StaticRoutePreviewRepository;
import com.securityexpert.nexus.ui2.persistence.staticroute.StaticRoutePreviewRepository.CreateSpec;
import com.securityexpert.nexus.ui2.persistence.staticroute.StaticRoutePreviewRepository.PreviewRow;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * Service for Operations static-route Phase R0 previews ({@code POST /api/v2/operations/static-route/preview},
 * {@code GET /api/v2/operations/static-route/preview}, {@code GET /api/v2/operations/static-route/preview/{id}}).
 *
 * <p>Validates requests per Contract §5.3, enforces idempotency-key payload binding, evaluates Stage A pre-checks
 * against stored inventory, compiles the deterministic forward and inverse command plans from integer-rebuilt IPv4
 * fields, persists the preview row, and admits one {@code static_route_preview} CLASS_0 read job for live Stage B
 * verification. Zero write commands or write routes exist in Phase R0.
 */
public final class StaticRoutePreviewService {

    public static final String PREVIEW_BADGE = "Preview only -- nothing is sent to the device";
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("\\A[A-Za-z0-9_.:-]{8,128}\\z");
    private static final Set<String> ACTIVE_JOB_STATES = Set.of("REQUESTED", "CLAIMED", "EXECUTING");

    private static final TypeReference<List<PreCheckVerdict>> PRECHECK_LIST_TYPE = new TypeReference<>() { };
    private static final TypeReference<BeforePathObservation> BEFORE_PATH_TYPE = new TypeReference<>() { };
    private static final TypeReference<PreviewPlanSet> PLAN_SET_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<CompiledPreviewStep>> STEP_LIST_TYPE = new TypeReference<>() { };

    private final StaticRoutePreviewRepository repository;
    private final DeviceRepository devices;
    private final DeviceInventoryRepository inventory;
    private final JobAdmissionService admission;
    private final JobWindowPolicy windows;
    private final TopologyNamePseudonymizer pseudonymizer;
    private final ObjectMapper json = new ObjectMapper();

    public StaticRoutePreviewService(
            StaticRoutePreviewRepository repository,
            DeviceRepository devices,
            DeviceInventoryRepository inventory,
            JobAdmissionService admission,
            JobWindowPolicy windows,
            TopologyNamePseudonymizer pseudonymizer) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.windows = Objects.requireNonNull(windows, "windows");
        this.pseudonymizer = Objects.requireNonNull(pseudonymizer, "pseudonymizer");
    }

    public static final class ConflictException extends RuntimeException {
        private final String code;

        public ConflictException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static final class NotFoundException extends RuntimeException {
        private final String code;

        public NotFoundException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public record PreviewSubmissionRequest(
            String idempotencyKey,
            String unitId,
            String vendor,
            String routingContext,
            String operation,
            String destinationPrefix,
            String nextHopIp,
            String interfaceName,
            Integer distance,
            Integer metric,
            Integer priority,
            String routeName,
            String deviceComment,
            String representativeHost,
            String changeTicketRef,
            String reason) {
    }

    public record SubmissionOutcome(boolean deduplicated, Map<String, Object> body) {
    }

    private record ResolvedUnit(
            String unitId,
            String targetDeviceId,
            String vendor,
            String rawLabel,
            Optional<String> clusterRef,
            List<String> memberDeviceIds,
            Optional<InventoryRun> latestInventory,
            List<String> protectedControlPlaneIps) {
    }

    public SubmissionOutcome submitPreview(PreviewSubmissionRequest raw, String actor, boolean masked) {
        if (raw == null) {
            throw new ValidationException("INVALID_REQUEST", "Request body is required.");
        }
        if (raw.idempotencyKey() == null || !IDEMPOTENCY_KEY_PATTERN.matcher(raw.idempotencyKey()).matches()) {
            throw new ValidationException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required and must match [A-Za-z0-9_.:-]{8,128}.");
        }

        ResolvedUnit unit = resolveTargetUnit(raw.unitId(), raw.vendor());
        ValidatedRequest validated = StaticRoutePreview.validateRequest(
                unit.unitId(),
                unit.vendor(),
                raw.routingContext(),
                raw.operation(),
                raw.destinationPrefix(),
                raw.nextHopIp(),
                raw.interfaceName(),
                raw.distance(),
                raw.metric(),
                raw.priority(),
                raw.routeName(),
                raw.deviceComment(),
                raw.representativeHost(),
                raw.changeTicketRef(),
                raw.reason());

        String payloadHash = computeRequestPayloadHash(validated);
        Optional<PreviewRow> existing = repository.findByIdempotencyKey(raw.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.get().requestPayloadSha256().equals(payloadHash)) {
                throw new ConflictException("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH",
                        "Idempotency-Key was already used with different static-route preview parameters.");
            }
            return new SubmissionOutcome(true, formatPreviewRow(settleIfEnded(existing.get()), masked));
        }

        windows.requireOpenFor(actor);

        List<PreCheckVerdict> stageAChecks = evaluateStageAPreChecks(validated, unit);
        PreviewPlanSet compiledPlans = StaticRoutePreview.compilePreviewPlans(
                validated, unit.memberDeviceIds(), Optional.empty(), Optional.empty());

        String previewId = StaticRoutePreview.PREVIEW_ID_PREFIX + UUID.randomUUID();
        String preChecksJson = writeJson(stageAChecks);
        String commandPlanJson = writeJson(compiledPlans);
        String inversePlanJson = writeJson(compiledPlans.inverseSteps());

        repository.create(new CreateSpec(
                previewId,
                raw.idempotencyKey(),
                payloadHash,
                actor,
                validated.unitId(),
                unit.targetDeviceId(),
                validated.vendor(),
                validated.routingContext(),
                validated.operation(),
                validated.prefix().canonicalCidr(),
                validated.nextHopIp(),
                validated.interfaceName(),
                validated.distance(),
                validated.metric(),
                validated.priority(),
                validated.routeName(),
                validated.deviceComment(),
                validated.representativeHost(),
                validated.changeTicketRef(),
                validated.reason(),
                preChecksJson,
                commandPlanJson,
                inversePlanJson));

        // If any Stage A check failed (e.g. nextHopIp not on connected subnet in stored inventory), record Stage A stop
        // without contacting the device when stored inventory already proved a hard violation; if inventory is absent or
        // Stage A passed, admit the live Stage B read job.
        Optional<PreCheckVerdict> stageAFail = stageAChecks.stream()
                .filter(c -> "FAIL".equals(c.status()))
                .findFirst();
        if (stageAFail.isPresent()) {
            repository.abandonById(previewId, stageAFail.get().reasonCode());
        } else {
            try {
                AdmissionResult adm = admission.submit(
                        StaticRoutePreview.CAPABILITY,
                        unit.targetDeviceId(),
                        "static-route-preview:" + previewId,
                        actor,
                        ActionRegistry.STATIC_ROUTE_PREVIEW);
                switch (adm) {
                    case AdmissionResult.Admitted admitted -> {
                        if (!repository.attachJob(previewId, admitted.jobId())) {
                            repository.abandonById(previewId, "JOB_NOT_BOUND");
                        }
                    }
                    case AdmissionResult.Deduplicated dup -> {
                        if (!repository.attachJob(previewId, dup.jobId())) {
                            repository.abandonById(previewId, "JOB_NOT_BOUND");
                        }
                    }
                    case AdmissionResult.Refused refused ->
                            repository.abandonById(previewId, refused.code());
                }
            } catch (RuntimeException failure) {
                repository.abandonById(previewId, "ADMISSION_FAILED");
                throw failure;
            }
        }

        PreviewRow saved = repository.find(previewId).orElseThrow();
        return new SubmissionOutcome(false, formatPreviewRow(saved, masked));
    }

    public Optional<Map<String, Object>> getPreview(String previewId, boolean masked) {
        if (!StaticRoutePreview.isValidPreviewId(previewId)) {
            return Optional.empty();
        }
        return repository.find(previewId)
                .map(this::settleIfEnded)
                .map(row -> formatPreviewRow(row, masked));
    }

    public Map<String, Object> listPreviewsAndTargets(boolean masked) {
        List<PreviewRow> recent = repository.listRecent(25);
        List<Map<String, Object>> previews = new ArrayList<>(recent.size());
        for (PreviewRow row : recent) {
            previews.add(formatPreviewRow(settleIfEnded(row), masked));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("preview_badge", PREVIEW_BADGE);
        response.put("mutation_possible", false);
        response.put("targets", listEligibleTargets(masked));
        response.put("previews", previews);
        return response;
    }

    private ResolvedUnit resolveTargetUnit(String unitId, String vendorOverride) {
        if (unitId == null || unitId.isBlank()) {
            throw new ValidationException("INVALID_UNIT_ID", "unitId is required.");
        }
        if (StaticRoutePreview.VENDOR_FORTINET.equalsIgnoreCase(vendorOverride)) {
            throw new ValidationException("FORTIMANAGER_MANAGED",
                    "FortiGate is out of scope for local static-route operations (FortiManager-managed).");
        }

        // 1. Check if unitId is a direct deviceId
        Optional<DeviceRecord> directDevice = devices.find(unitId);
        if (directDevice.isPresent()) {
            DeviceRecord dev = directDevice.get();
            if (StaticRoutePreview.VENDOR_FORTINET.equalsIgnoreCase(dev.vendorHint())) {
                throw new ValidationException("FORTIMANAGER_MANAGED",
                        "FortiGate is out of scope for local static-route operations (FortiManager-managed).");
            }
            if (!StaticRoutePreview.SUPPORTED_VENDORS.contains(dev.vendorHint())) {
                throw new ValidationException("VENDOR_NOT_SUPPORTED",
                        "Unsupported vendor for static-route preview: " + dev.vendorHint());
            }
            if (vendorOverride != null && !vendorOverride.isBlank() && !dev.vendorHint().equals(vendorOverride)) {
                throw new ValidationException("VENDOR_MISMATCH",
                        "Supplied vendor does not match target device vendor.");
            }
            if (!"gateway".equals(dev.role()) || !dev.permitsReadCollection()) {
                throw new ValidationException("DEVICE_NOT_ELIGIBLE",
                        "Target device is not an active read-eligible gateway.");
            }
            Optional<DeviceSummaryRecord> summary = devices.findSummary(unitId);
            Optional<String> clusterRef = summary.flatMap(DeviceSummaryRecord::clusterMemberRef);
            String label = summary.flatMap(DeviceSummaryRecord::observedHostname).orElse(unitId);
            List<String> memberIds = new ArrayList<>();
            Set<String> protectedIps = new LinkedHashSet<>();
            devices.findEndpointByDeviceId(unitId)
                    .map(e -> EndpointAddress.host(e.addressRef()))
                    .filter(h -> h != null && !h.isBlank())
                    .ifPresent(protectedIps::add);

            if (clusterRef.isPresent()) {
                List<DeviceSummaryRecord> members = new ArrayList<>(devices.findMembersByClusterRef(clusterRef.get()));
                members.sort(Comparator
                        .comparing((DeviceSummaryRecord m) ->
                                m.observedHaRole().map(r -> "STANDBY".equalsIgnoreCase(r) ? 0 : 1).orElse(1))
                        .thenComparing(DeviceSummaryRecord::deviceId));
                for (DeviceSummaryRecord m : members) {
                    memberIds.add(m.deviceId());
                    devices.findEndpointByDeviceId(m.deviceId())
                            .map(e -> EndpointAddress.host(e.addressRef()))
                            .filter(h -> h != null && !h.isBlank())
                            .ifPresent(protectedIps::add);
                }
            }
            if (memberIds.isEmpty()) {
                memberIds.add(unitId);
            }
            return new ResolvedUnit(
                    unitId,
                    unitId,
                    dev.vendorHint(),
                    label,
                    clusterRef,
                    List.copyOf(memberIds),
                    inventory.findLatestRun(unitId),
                    List.copyOf(protectedIps));
        }

        // 2. Otherwise check if unitId is a clusterRef
        List<DeviceSummaryRecord> clusterMembers = new ArrayList<>(devices.findMembersByClusterRef(unitId));
        if (clusterMembers.isEmpty()) {
            throw new NotFoundException("TARGET_NOT_FOUND", "Unknown target unit or device: " + unitId);
        }
        String vendor = clusterMembers.get(0).vendorHint();
        if (StaticRoutePreview.VENDOR_FORTINET.equalsIgnoreCase(vendor)) {
            throw new ValidationException("FORTIMANAGER_MANAGED",
                    "FortiGate is out of scope for local static-route operations (FortiManager-managed).");
        }
        if (!StaticRoutePreview.SUPPORTED_VENDORS.contains(vendor)) {
            throw new ValidationException("VENDOR_NOT_SUPPORTED", "Unsupported cluster vendor: " + vendor);
        }
        if (vendorOverride != null && !vendorOverride.isBlank() && !vendor.equals(vendorOverride)) {
            throw new ValidationException("VENDOR_MISMATCH", "Supplied vendor does not match cluster vendor.");
        }

        clusterMembers.sort(Comparator
                .comparing((DeviceSummaryRecord m) ->
                        m.observedHaRole().map(r -> "STANDBY".equalsIgnoreCase(r) ? 0 : 1).orElse(1))
                .thenComparing(DeviceSummaryRecord::deviceId));

        DeviceSummaryRecord primaryTarget = clusterMembers.stream()
                .filter(m -> m.observedHaRole().map(r -> "ACTIVE".equalsIgnoreCase(r) || "PRIMARY".equalsIgnoreCase(r)).orElse(false))
                .findFirst()
                .orElse(clusterMembers.get(0));

        Optional<DeviceRecord> targetDev = devices.find(primaryTarget.deviceId());
        if (targetDev.isEmpty() || !targetDev.get().permitsReadCollection()) {
            throw new ValidationException("DEVICE_NOT_ELIGIBLE", "Cluster target member is not eligible for reads.");
        }

        List<String> memberIds = new ArrayList<>(clusterMembers.size());
        Set<String> protectedIps = new LinkedHashSet<>();
        for (DeviceSummaryRecord m : clusterMembers) {
            memberIds.add(m.deviceId());
            devices.findEndpointByDeviceId(m.deviceId())
                    .map(e -> EndpointAddress.host(e.addressRef()))
                    .filter(h -> h != null && !h.isBlank())
                    .ifPresent(protectedIps::add);
        }
        return new ResolvedUnit(
                unitId,
                primaryTarget.deviceId(),
                vendor,
                unitId,
                Optional.of(unitId),
                List.copyOf(memberIds),
                inventory.findLatestRun(primaryTarget.deviceId()),
                List.copyOf(protectedIps));
    }

    private List<PreCheckVerdict> evaluateStageAPreChecks(ValidatedRequest req, ResolvedUnit unit) {
        List<PreCheckVerdict> checks = new ArrayList<>(6);

        // Check 1: TARGET_REACHABLE_AND_READY (Stage A enrollment & endpoint check)
        checks.add(new PreCheckVerdict(
                1,
                "TARGET_REACHABLE_AND_READY",
                "STAGE_A",
                "PASS",
                "TARGET_ENROLLED",
                "Target unit is enrolled and read-eligible; live device session will be verified in Stage B."));

        // Check 2: IDENTITY_HA_AND_CENTRAL_MGMT_VERIFIED (Stage A context & inventory check)
        Optional<InventoryRun> invOpt = unit.latestInventory();
        if (invOpt.isEmpty()) {
            checks.add(new PreCheckVerdict(
                    2, "IDENTITY_HA_AND_CENTRAL_MGMT_VERIFIED", "STAGE_A", "UNKNOWN",
                    "INVENTORY_UNAVAILABLE",
                    "No stored inventory run for target; live Stage B reads will verify routing context and HA state."));
            checks.add(new PreCheckVerdict(
                    3, "DEFAULT_ROUTE_GUARD", "STAGE_A", "PASS", "NON_DEFAULT_PREFIX",
                    "Destination prefix is non-default."));
            checks.add(new PreCheckVerdict(
                    4, "ROUTE_STATE_AND_ATTRIBUTE_PRECONDITION", "STAGE_A", "UNKNOWN",
                    "INVENTORY_UNAVAILABLE", "Stored routing table unavailable; live Stage B reads will verify."));
            checks.add(new PreCheckVerdict(
                    5, "NEXTHOP_ON_CONNECTED_SUBNET", "STAGE_A", "UNKNOWN",
                    "INVENTORY_UNAVAILABLE", "Stored interface inventory unavailable; live Stage B reads will verify."));
            checks.add(new PreCheckVerdict(
                    6, "CONTROL_PLANE_AND_OVERLAP_GUARD", "STAGE_A",
                    StaticRoutePreview.VENDOR_CHECK_POINT.equals(req.vendor()) ? "WARN" : "UNKNOWN",
                    StaticRoutePreview.VENDOR_CHECK_POINT.equals(req.vendor())
                            ? "CP_ANTISPOOFING_DEFINED_BY_ROUTES_WARNING" : "INVENTORY_UNAVAILABLE",
                    "Live Stage B RIB reads will verify control-plane and overlap guards."));
            return checks;
        }

        InventoryRun inv = invOpt.get();
        String expectedInvCtx = StaticRoutePreview.VENDOR_CISCO_ASA.equals(req.vendor())
                && !StaticRoutePreview.ASA_SINGLE_CONTEXT.equals(req.routingContext())
                ? req.routingContext()
                : InventoryContext.PHYSICAL;

        Optional<InventoryContext> ctxOpt = inv.contexts().stream()
                .filter(c -> expectedInvCtx.equals(c.context()) || InventoryContext.PHYSICAL.equals(c.context()))
                .findFirst();

        if (ctxOpt.isEmpty()) {
            checks.add(new PreCheckVerdict(
                    2, "IDENTITY_HA_AND_CENTRAL_MGMT_VERIFIED", "STAGE_A", "FAIL",
                    "ROUTING_CONTEXT_NOT_FOUND",
                    "Requested routing context was not found in stored inventory."));
        } else if (StaticRoutePreview.VENDOR_PALO_ALTO.equals(req.vendor())) {
            boolean vrKnown = ctxOpt.get().interfaces().stream()
                    .anyMatch(i -> i.routingContext().filter(req.routingContext()::equals).isPresent())
                    || ctxOpt.get().routes().stream()
                    .anyMatch(r -> r.routeTable().filter(req.routingContext()::equals).isPresent());
            if (!vrKnown && !ctxOpt.get().interfaces().isEmpty()) {
                checks.add(new PreCheckVerdict(
                        2, "IDENTITY_HA_AND_CENTRAL_MGMT_VERIFIED", "STAGE_A", "FAIL",
                        "ROUTING_CONTEXT_NOT_FOUND",
                        "Virtual router '" + req.routingContext() + "' was not found in stored inventory."));
            } else {
                checks.add(new PreCheckVerdict(
                        2, "IDENTITY_HA_AND_CENTRAL_MGMT_VERIFIED", "STAGE_A", "PASS",
                        "STAGE_A_CONTEXT_PRESENT",
                        "Routing context exists in stored inventory; live Stage B reads will verify HA and template provenance."));
            }
        } else {
            checks.add(new PreCheckVerdict(
                    2, "IDENTITY_HA_AND_CENTRAL_MGMT_VERIFIED", "STAGE_A", "PASS",
                    "STAGE_A_CONTEXT_PRESENT",
                    "Routing context exists in stored inventory; live Stage B reads will verify HA state."));
        }

        // Check 3: DEFAULT_ROUTE_GUARD
        checks.add(new PreCheckVerdict(
                3, "DEFAULT_ROUTE_GUARD", "STAGE_A", "PASS", "NON_DEFAULT_PREFIX",
                "Destination prefix is non-default."));

        List<InventoryInterface> interfaces = ctxOpt.map(InventoryContext::interfaces).orElse(List.of());
        List<InventoryRoute> routes = ctxOpt.map(InventoryContext::routes).orElse(List.of());
        if (StaticRoutePreview.VENDOR_PALO_ALTO.equals(req.vendor())) {
            routes = routes.stream()
                    .filter(r -> r.routeTable().isEmpty() || req.routingContext().equals(r.routeTable().get()))
                    .toList();
        }

        // Check 4: ROUTE_STATE_AND_ATTRIBUTE_PRECONDITION (Stage A against stored RIB)
        String targetCidr = req.prefix().canonicalCidr();
        String targetNh = req.nextHopIp();
        boolean inStoredRib = routes.stream().anyMatch(r ->
                targetCidr.equals(r.destination()) && r.nextHop().filter(targetNh::equals).isPresent());
        if (StaticRoutePreview.OP_ADD.equals(req.operation())) {
            checks.add(new PreCheckVerdict(
                    4, "ROUTE_STATE_AND_ATTRIBUTE_PRECONDITION", "STAGE_A",
                    inStoredRib ? "FAIL" : "PASS",
                    inStoredRib ? "ROUTE_ALREADY_EXISTS" : "STAGE_A_ROUTE_ABSENT",
                    inStoredRib
                            ? "Static route already exists in stored routing table."
                            : "Route is absent in stored inventory; Stage B will verify live static-route config and RIB."));
        } else {
            checks.add(new PreCheckVerdict(
                    4, "ROUTE_STATE_AND_ATTRIBUTE_PRECONDITION", "STAGE_A",
                    inStoredRib ? "PASS" : "UNKNOWN",
                    inStoredRib ? "STAGE_A_ROUTE_PRESENT" : "PENDING_LIVE_CONFIG_READ",
                    inStoredRib
                            ? "Target route found in stored routing table; Stage B will verify live static-route config."
                            : "Stage B will verify live static-route configuration."));
        }

        // Check 5 & Check 6 against stored interfaces and routes
        checks.add(evaluateStageAConnectedSubnet(req, interfaces, routes));
        checks.add(evaluateStageAControlPlaneAndOverlap(req, interfaces, routes, unit.protectedControlPlaneIps()));
        return checks;
    }

    private static PreCheckVerdict evaluateStageAConnectedSubnet(
            ValidatedRequest req,
            List<InventoryInterface> interfaces,
            List<InventoryRoute> routes) {
        int nhInt = req.nextHopInt();
        String matchedIf = null;
        boolean anySubnetSeen = false;

        for (InventoryInterface iface : interfaces) {
            if (StaticRoutePreview.VENDOR_PALO_ALTO.equals(req.vendor())
                    && iface.routingContext().isPresent()
                    && !req.routingContext().equals(iface.routingContext().get())) {
                continue;
            }
            for (InventoryAddress addr : iface.addresses()) {
                if (!InventoryAddress.FAMILY_IPV4.equalsIgnoreCase(addr.family())) {
                    continue;
                }
                int slash = addr.address().indexOf('/');
                if (slash <= 0) {
                    continue;
                }
                try {
                    int localInt = StaticRoutePreview.parseIpv4ToInt(addr.address().substring(0, slash), "IP");
                    int len = Integer.parseInt(addr.address().substring(slash + 1));
                    anySubnetSeen = true;
                    if (localInt == nhInt) {
                        return new PreCheckVerdict(
                                5, "NEXTHOP_ON_CONNECTED_SUBNET", "STAGE_A", "FAIL",
                                "NEXTHOP_IS_LOCAL_INTERFACE_IP",
                                "nextHopIp equals local interface IP on " + iface.name() + ".");
                    }
                    if (!InventoryInterface.STATE_UP.equalsIgnoreCase(iface.state())) {
                        continue;
                    }
                    int mask = StaticRoutePreview.netmaskFromPrefixLen(len);
                    int net = localInt & mask;
                    if (StaticRoutePreview.prefixContains(net, len, nhInt)) {
                        if (len <= 30 && (nhInt == net || nhInt == (net | ~mask))) {
                            return new PreCheckVerdict(
                                    5, "NEXTHOP_ON_CONNECTED_SUBNET", "STAGE_A", "FAIL",
                                    "NEXTHOP_NOT_ON_CONNECTED_SUBNET",
                                    "nextHopIp equals subnet network or broadcast address.");
                        }
                        if (matchedIf == null || (req.interfaceName() != null && req.interfaceName().equalsIgnoreCase(iface.name()))) {
                            matchedIf = iface.name();
                        }
                    }
                } catch (RuntimeException ignored) {
                    // skip unparseable address
                }
            }
        }

        if (!anySubnetSeen) {
            return new PreCheckVerdict(
                    5, "NEXTHOP_ON_CONNECTED_SUBNET", "STAGE_A", "UNKNOWN",
                    "CONNECTED_SUBNETS_UNAVAILABLE",
                    "No interface IPv4 subnets in stored inventory; Stage B will evaluate against live RIB.");
        }
        if (matchedIf == null) {
            return new PreCheckVerdict(
                    5, "NEXTHOP_ON_CONNECTED_SUBNET", "STAGE_A", "FAIL",
                    "NEXTHOP_NOT_ON_CONNECTED_SUBNET",
                    "nextHopIp does not lie on any operational (UP) connected subnet in stored inventory.");
        }
        if (req.interfaceName() != null && !req.interfaceName().equalsIgnoreCase(matchedIf)) {
            return new PreCheckVerdict(
                    5, "NEXTHOP_ON_CONNECTED_SUBNET", "STAGE_A", "FAIL",
                    "NEXTHOP_INTERFACE_MISMATCH",
                    "nextHopIp belongs to interface " + matchedIf + ", not " + req.interfaceName() + ".");
        }
        return new PreCheckVerdict(
                5, "NEXTHOP_ON_CONNECTED_SUBNET", "STAGE_A", "PASS",
                "NEXTHOP_CONNECTED_VERIFIED",
                "nextHopIp lies on operational connected subnet via " + matchedIf + ".");
    }

    private static PreCheckVerdict evaluateStageAControlPlaneAndOverlap(
            ValidatedRequest req,
            List<InventoryInterface> interfaces,
            List<InventoryRoute> routes,
            List<String> protectedIps) {

        int destNet = req.prefix().networkInt();
        int destLen = req.prefix().prefixLen();

        for (InventoryInterface iface : interfaces) {
            if (StaticRoutePreview.VENDOR_PALO_ALTO.equals(req.vendor())
                    && iface.routingContext().isPresent()
                    && !req.routingContext().equals(iface.routingContext().get())) {
                continue;
            }
            for (InventoryAddress addr : iface.addresses()) {
                int slash = addr.address().indexOf('/');
                if (slash <= 0) {
                    continue;
                }
                try {
                    int ipInt = StaticRoutePreview.parseIpv4ToInt(addr.address().substring(0, slash), "IP");
                    int len = Integer.parseInt(addr.address().substring(slash + 1));
                    int netInt = ipInt & StaticRoutePreview.netmaskFromPrefixLen(len);
                    if (StaticRoutePreview.prefixesOverlap(destNet, destLen, netInt, len)) {
                        return new PreCheckVerdict(
                                6, "CONTROL_PLANE_AND_OVERLAP_GUARD", "STAGE_A", "FAIL",
                                "OVERLAPS_CONNECTED_SUBNET",
                                "destinationPrefix overlaps connected subnet on " + iface.name() + ".");
                    }
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
        }

        for (String cpIp : protectedIps) {
            try {
                int cpInt = StaticRoutePreview.parseIpv4ToInt(cpIp, "IP");
                if (StaticRoutePreview.prefixContains(destNet, destLen, cpInt)) {
                    return new PreCheckVerdict(
                            6, "CONTROL_PLANE_AND_OVERLAP_GUARD", "STAGE_A", "FAIL",
                            "MANAGEMENT_LOCKOUT_RISK",
                            "destinationPrefix covers a protected management/control-plane endpoint.");
                }
            } catch (RuntimeException ignored) {
                // non-IPv4 hostname
            }
        }

        if (StaticRoutePreview.VENDOR_CHECK_POINT.equals(req.vendor())) {
            return new PreCheckVerdict(
                    6, "CONTROL_PLANE_AND_OVERLAP_GUARD", "STAGE_A", "WARN",
                    "CP_ANTISPOOFING_DEFINED_BY_ROUTES_WARNING",
                    "Check Point anti-spoofing topology mode is UNKNOWN in R0; requires explicit confirmation if anti-spoofing is defined by routes.");
        }
        return new PreCheckVerdict(
                6, "CONTROL_PLANE_AND_OVERLAP_GUARD", "STAGE_A", "PASS",
                "STAGE_A_OVERLAP_SAFE",
                "Stage A control-plane and connected-subnet overlap checks passed.");
    }

    private List<Map<String, Object>> listEligibleTargets(boolean masked) {
        List<DeviceSummaryRecord> summaries = devices.listAll();
        List<Map<String, Object>> targets = new ArrayList<>();
        for (DeviceSummaryRecord s : summaries) {
            if (!"gateway".equals(s.role())
                    || !StaticRoutePreview.SUPPORTED_VENDORS.contains(s.vendorHint())
                    || s.enrollmentState() != DeviceEnrollmentState.ENROLLED) {
                continue;
            }
            List<String> contexts = new ArrayList<>();
            List<String> interfaces = new ArrayList<>();
            Optional<InventoryRun> inv = inventory.findLatestRun(s.deviceId());
            if (StaticRoutePreview.VENDOR_CHECK_POINT.equals(s.vendorHint())) {
                contexts.add(StaticRoutePreview.CP_DEFAULT_CONTEXT);
            } else if (StaticRoutePreview.VENDOR_CISCO_ASA.equals(s.vendorHint())) {
                contexts.add(StaticRoutePreview.ASA_SINGLE_CONTEXT);
            }
            if (inv.isPresent()) {
                for (InventoryContext ctx : inv.get().contexts()) {
                    if (StaticRoutePreview.VENDOR_CISCO_ASA.equals(s.vendorHint())
                            && !InventoryContext.PHYSICAL.equals(ctx.context())
                            && !contexts.contains(ctx.context())) {
                        contexts.add(ctx.context());
                    }
                    for (InventoryInterface iface : ctx.interfaces()) {
                        String ifName = masked
                                ? pseudonymizer.maskPolicyName("interface", iface.name())
                                : iface.name();
                        if (!interfaces.contains(ifName)) {
                            interfaces.add(ifName);
                        }
                        if (StaticRoutePreview.VENDOR_PALO_ALTO.equals(s.vendorHint())
                                && iface.routingContext().isPresent()) {
                            String vr = masked
                                    ? pseudonymizer.maskPolicyName("vrf", iface.routingContext().get())
                                    : iface.routingContext().get();
                            if (!contexts.contains(vr)) {
                                contexts.add(vr);
                            }
                        }
                    }
                    if (StaticRoutePreview.VENDOR_PALO_ALTO.equals(s.vendorHint())) {
                        for (InventoryRoute r : ctx.routes()) {
                            if (r.routeTable().isPresent() && !r.routeTable().get().isBlank()) {
                                String vr = masked
                                        ? pseudonymizer.maskPolicyName("vrf", r.routeTable().get())
                                        : r.routeTable().get();
                                if (!contexts.contains(vr)) {
                                    contexts.add(vr);
                                }
                            }
                        }
                    }
                }
            }
            if (StaticRoutePreview.VENDOR_PALO_ALTO.equals(s.vendorHint()) && contexts.isEmpty()) {
                contexts.add(masked ? pseudonymizer.maskPolicyName("vrf", "default") : "default");
            }

            String rawName = s.observedHostname().orElse(s.deviceId());
            String displayLabel = masked
                    ? pseudonymizer.maskDeviceName(rawName, s.clusterMemberRef().orElse(null), s.deviceId(), null)
                    : rawName;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("unit_id", s.deviceId());
            item.put("device_id", s.deviceId());
            item.put("cluster_id", masked ? s.clusterMemberRef().map(pseudonymizer::maskClusterName).orElse(null) : s.clusterMemberRef().orElse(null));
            item.put("target_label", displayLabel);
            item.put("vendor", s.vendorHint());
            item.put("ha_role", s.observedHaRole().orElse("STANDALONE"));
            item.put("routing_contexts", contexts);
            item.put("interfaces", interfaces);
            targets.add(item);
        }
        return targets;
    }

    private PreviewRow settleIfEnded(PreviewRow row) {
        if ((StaticRoutePreviewRepository.STATE_QUEUED.equals(row.state())
                || StaticRoutePreviewRepository.STATE_RUNNING.equals(row.state()))
                && row.jobId().isPresent()
                && row.jobState().filter(ACTIVE_JOB_STATES::contains).isEmpty()) {
            repository.abandonById(row.previewId(), "JOB_ENDED_WITHOUT_OUTCOME");
            return repository.find(row.previewId()).orElse(row);
        }
        return row;
    }

    private Map<String, Object> formatPreviewRow(PreviewRow row, boolean masked) {
        Optional<DeviceSummaryRecord> summary = devices.findSummary(row.targetDeviceId());
        String rawName = summary.flatMap(DeviceSummaryRecord::observedHostname).orElse(row.targetDeviceId());
        String clusterRef = summary.flatMap(DeviceSummaryRecord::clusterMemberRef).orElse(null);
        String targetLabel = masked
                ? pseudonymizer.maskDeviceName(rawName, clusterRef, row.targetDeviceId(), null)
                : rawName;

        List<PreCheckVerdict> preChecks = readJson(row.preChecksJson(), PRECHECK_LIST_TYPE, List.of());
        BeforePathObservation beforePath = row.beforePathJson()
                .map(j -> readJson(j, BEFORE_PATH_TYPE, null))
                .orElse(null);
        PreviewPlanSet planSet = readJson(row.commandPlanJson(), PLAN_SET_TYPE, null);
        List<CompiledPreviewStep> inverseSteps = readJson(row.inversePlanJson(), STEP_LIST_TYPE, List.of());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preview_id", row.previewId());
        out.put("idempotency_key", row.idempotencyKey());
        out.put("requested_at", row.requestedAt().toString());
        out.put("completed_at", row.completedAt().map(Object::toString).orElse(null));
        out.put("requested_by", masked ? null : row.requestedBy());
        out.put("unit_id", masked && devices.find(row.unitId()).isEmpty() ? pseudonymizer.maskClusterName(row.unitId()) : row.unitId());
        out.put("target_device_id", row.targetDeviceId());
        out.put("target_label", targetLabel);
        out.put("vendor", row.vendor());
        out.put("routing_context", row.routingContext());
        out.put("operation", row.operation());
        out.put("destination_prefix", row.destinationCidr());
        out.put("next_hop_ip", row.nextHopIp());
        out.put("interface_name", row.interfaceName().orElse(null));
        out.put("distance", row.distance().orElse(null));
        out.put("metric", row.metric().orElse(null));
        out.put("priority", row.priority().orElse(null));
        out.put("route_name", row.routeName().orElse(null));
        out.put("device_comment", masked ? null : row.deviceComment().orElse(null));
        out.put("representative_host", row.representativeHost());
        out.put("change_ticket_ref", masked ? null : row.changeTicketRef().orElse(null));
        out.put("reason", masked ? null : row.reason().orElse(null));
        out.put("job_id", row.jobId().orElse(null));
        out.put("job_state", row.jobState().orElse(null));
        out.put("state", row.state());
        out.put("verdict", row.verdict());
        out.put("stop_code", row.stopCode().orElse(null));
        out.put("mutation_possible", false);
        out.put("preview_badge", PREVIEW_BADGE);
        out.put("pre_route_table_sha256", row.preRouteTableSha256().orElse(null));

        List<Map<String, Object>> checkList = new ArrayList<>(preChecks.size());
        for (PreCheckVerdict c : preChecks) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("check_number", c.checkNumber());
            cm.put("check_id", c.checkId());
            cm.put("stage", c.stage());
            cm.put("status", c.status());
            cm.put("reason_code", c.reasonCode());
            cm.put("detail", c.detail());
            checkList.add(cm);
        }
        out.put("pre_checks", checkList);

        if (beforePath != null) {
            Map<String, Object> bp = new LinkedHashMap<>();
            bp.put("representative_host", beforePath.representativeHost());
            bp.put("verdict", beforePath.verdict());
            bp.put("reason_code", beforePath.reasonCode());
            bp.put("selected_prefix", beforePath.selectedPrefix());
            bp.put("next_hop_ip", beforePath.nextHopIp());
            bp.put("egress_interface", beforePath.egressInterface());
            bp.put("protocol", beforePath.protocol());
            bp.put("digest", beforePath.digest());
            out.put("before_path", bp);
        } else {
            out.put("before_path", null);
        }

        if (planSet != null) {
            Map<String, Object> cp = new LinkedHashMap<>();
            cp.put("gate_version", planSet.gateVersion());
            cp.put("request_digest", planSet.requestDigest());
            cp.put("mutation_possible", false);
            cp.put("note", PREVIEW_BADGE);
            cp.put("forward_steps", formatSteps(planSet.forwardSteps(), masked));
            cp.put("approved_command_sha256_set", planSet.approvedCommandSha256Set());
            cp.put("inverse_steps", formatSteps(planSet.inverseSteps(), masked));
            cp.put("inverse_command_sha256_set", planSet.inverseCommandSha256Set());
            out.put("command_plan", cp);
        } else {
            out.put("command_plan", null);
        }
        out.put("inverse_plan", formatSteps(inverseSteps, masked));
        return out;
    }

    private List<Map<String, Object>> formatSteps(List<CompiledPreviewStep> steps, boolean masked) {
        if (steps == null) {
            return List.of();
        }
        List<Map<String, Object>> list = new ArrayList<>(steps.size());
        for (CompiledPreviewStep s : steps) {
            Optional<DeviceSummaryRecord> sum = devices.findSummary(s.memberDeviceId());
            String rawMemberName = sum.flatMap(DeviceSummaryRecord::observedHostname).orElse(s.memberDeviceId());
            String clusterRef = sum.flatMap(DeviceSummaryRecord::clusterMemberRef).orElse(null);
            String memberLabel = masked
                    ? pseudonymizer.maskDeviceName(rawMemberName, clusterRef, s.memberDeviceId(), null)
                    : rawMemberName;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step_no", s.stepNo());
            m.put("phase", s.phase());
            m.put("member_device_id", s.memberDeviceId());
            m.put("member_label", memberLabel);
            m.put("command_text", s.commandText());
            m.put("command_sha256", s.commandSha256());
            list.add(m);
        }
        return list;
    }

    private static String computeRequestPayloadHash(ValidatedRequest req) {
        String canonical = String.join("|",
                req.unitId(),
                req.vendor(),
                req.routingContext(),
                req.operation().toUpperCase(Locale.ROOT),
                req.prefix().canonicalCidr(),
                req.nextHopIp(),
                Objects.toString(req.interfaceName(), ""),
                Objects.toString(req.distance(), ""),
                Objects.toString(req.metric(), ""),
                Objects.toString(req.priority(), ""),
                Objects.toString(req.routeName(), ""),
                Objects.toString(req.deviceComment(), ""),
                req.representativeHost(),
                Objects.toString(req.changeTicketRef(), ""),
                Objects.toString(req.reason(), ""));
        return StaticRoutePreview.sha256Hex(canonical);
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize static-route preview JSON", e);
        }
    }

    private <T> T readJson(String raw, TypeReference<T> type, T fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return json.readValue(raw, type);
        } catch (Exception e) {
            return fallback;
        }
    }
}
