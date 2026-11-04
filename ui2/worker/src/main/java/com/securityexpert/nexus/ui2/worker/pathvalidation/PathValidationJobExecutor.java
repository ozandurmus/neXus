package com.securityexpert.nexus.ui2.worker.pathvalidation;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.admission.CheckPointSparkModelHint;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.HopPlan;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation.Check;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation.Result;
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
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.pathvalidation.PathValidationRepository;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;

/**
 * Path analysis stage A6: one {@code path_validation} job = one computed hop on its own device. The worker reads the hop's
 * plan (built by the service from a path result, never from a browser), validates every value again and checks the
 * VSID, virtual router and zones exist in the device's stored inventory, resolves each gate against the live registry,
 * then sends the approved literal(s) through the existing controlled transport (trusted SSH for Check Point, the
 * existing API-key path for Palo Alto), once each, never retried. Each answer is parsed in memory to the minimum the
 * comparison needs and discarded; only MATCH / MISMATCH / NOT_EVALUABLE, per-check enums and reason codes are recorded,
 * fenced to the job's lease, and the plan is cleared in that same statement (raw-evidence law). No transcript is opened
 * and the bounded exec keeps the command and the answer out of the SSH transcript and logs.
 */
public final class PathValidationJobExecutor {

    static final String ACTOR = "system:worker";
    private static final Pattern KEY = Pattern.compile("<key>\\s*([^<\\s]+)\\s*</key>");

    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceRepository devices;
    private final DeviceInventoryRepository inventory;
    private final DevicePlatformFactsRepository platformFacts;
    private final DeviceTransport ssh;
    private final DeviceTransport pan;
    private final PanCredentialResolver panCredentials;
    private final GateRegistryPort gates;
    private final PathValidationRepository validations;
    private final ObjectMapper json = new ObjectMapper();

    public PathValidationJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceInventoryRepository inventory, DevicePlatformFactsRepository platformFacts, DeviceTransport ssh,
            DeviceTransport pan, PanCredentialResolver panCredentials, GateRegistryPort gates,
            PathValidationRepository validations) {
        this.leases = leases;
        this.attempts = attempts;
        this.devices = devices;
        this.inventory = inventory;
        this.platformFacts = platformFacts;
        this.ssh = ssh;
        this.pan = pan;
        this.panCredentials = panCredentials;
        this.gates = gates;
        this.validations = validations;
    }

    /** A step that could not be ledgered or a lease that was lost: the job stops; nothing further is sent. */
    private static final class Abort extends RuntimeException {
        final JobState state;
        final String code;

        Abort(JobState state, String code) {
            super(code, null, false, false);
            this.state = state;
            this.code = code;
        }
    }

    private record Ready(HopPlan plan, DeviceRecord device, Map<Check, GateResolution.Known> gates, ApiTarget panTarget) {
    }

    public void execute(String jobId, long epoch, String deviceId, String host, int port, String credentialRef) {
        try {
            Optional<PathValidationRepository.Pending> pending = validations.pendingForJob(jobId);
            Object checked = pending.isEmpty() ? "HOP_NOT_PENDING" : admit(pending.get(), deviceId);
            if (checked instanceof String refusal) {
                validations.abandonJob(jobId, refusal);
                leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.REJECTED, ACTOR,
                        "path_validation_claim_check", refusal);
                return;
            }
            Ready ready = (Ready) checked;
            if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "path_validation_start")) {
                return;
            }
            try {
                if (!attempts.findByJobAndStep(jobId, 0).isEmpty()) {
                    throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
                }
                List<HopComparison.Outcome> outcomes = HopPlan.CHECK_POINT.equals(ready.plan().vendor())
                        ? checkPoint(jobId, epoch, ready, host, port, credentialRef)
                        : paloAlto(jobId, epoch, ready, credentialRef);
                Result result = HopComparison.hop(outcomes);
                if (!validations.recordOutcome(jobId, epoch, result.name(), checksJson(outcomes),
                        HopComparison.reasons(outcomes))) {
                    throw new Abort(JobState.OUTCOME_UNKNOWN, "VALIDATION_NOT_RECORDED");
                }
                finish(jobId, epoch, JobState.COMPLETED, "VALIDATION_RECORDED");
            } catch (Abort abort) {
                finish(jobId, epoch, abort.state, abort.code);
            } catch (Exception failure) {
                com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(failure);
                if (failure instanceof com.securityexpert.nexus.ui2.worker.JobCancellationScope.Cancelled cancelled) {
                    throw cancelled;
                }
                finish(jobId, epoch, JobState.OUTCOME_UNKNOWN, "PATH_VALIDATION_OUTCOME_UNCERTAIN");
            }
        } finally {
            // Whatever ended the job, the plan never outlives it (no-op when the outcome was recorded).
            validations.abandonJob(jobId, "JOB_ENDED_WITHOUT_OUTCOME");
        }
    }

    /** Every pre-contact check; a refusal code, or what the run needs. */
    private Object admit(PathValidationRepository.Pending pending, String deviceId) {
        HopPlan plan;
        try {
            plan = json.readValue(pending.planJson(), HopPlan.class);
        } catch (Exception unreadable) {
            return "PLAN_UNREADABLE";
        }
        Optional<String> invalid = plan.invalid();
        if (invalid.isPresent()) return invalid.get();
        if (!deviceId.equals(pending.deviceId()) || !deviceId.equals(plan.deviceId())) return "PLAN_DEVICE_MISMATCH";
        Optional<DeviceRecord> device = devices.find(deviceId);
        if (device.isEmpty() || !device.get().permitsReadCollection()) return "DEVICE_NOT_ELIGIBLE";
        if (!"gateway".equals(device.get().role()) || !plan.vendor().equals(device.get().vendorHint()))
            return "DEVICE_NOT_ELIGIBLE";
        boolean checkPoint = HopPlan.CHECK_POINT.equals(plan.vendor());
        var endpoint = devices.findEndpointByDeviceId(deviceId).filter(e -> deviceId.equals(e.deviceId()))
                .filter(e -> (checkPoint ? "ssh_exec" : "pan_xml_api").equalsIgnoreCase(e.transportKind()));
        if (endpoint.isEmpty()) return "TRANSPORT_NOT_ELIGIBLE";
        if (checkPoint) {
            boolean embedded = platformFacts.find(deviceId).flatMap(f -> f.platformFamily())
                    .filter("gaia_embedded"::equals).isPresent()
                    || CheckPointSparkModelHint.isKnownSparkModel(devices.findSummary(deviceId).flatMap(s -> s.observedModel()));
            if (embedded) return "PLATFORM_NOT_SUPPORTED";
        }
        Optional<InventoryRun> run = inventory.findLatestRun(deviceId);
        if (run.isEmpty()) return "INVENTORY_UNAVAILABLE";
        Optional<InventoryContext> context = run.get().contexts().stream()
                .filter(c -> c.context().equals(plan.context())).findFirst();
        if (context.isEmpty()) return "CONTEXT_NOT_IN_INVENTORY";
        if (!checkPoint) {
            List<InventoryInterface> interfaces = context.get().interfaces();
            if (plan.checks().contains(Check.PAN_FIB) && interfaces.stream()
                    .noneMatch(i -> i.routingContext().filter(plan.virtualRouter()::equals).isPresent()))
                return "VIRTUAL_ROUTER_NOT_IN_INVENTORY";
            List<String> zones = new ArrayList<>();
            if (plan.checks().contains(Check.PAN_SECURITY)) zones.addAll(List.of(plan.fromZone(), plan.toZone()));
            if (plan.checks().contains(Check.PAN_NAT)) zones.addAll(List.of(plan.fromZone(), plan.natToZone()));
            for (String zone : zones) {
                if (interfaces.stream().noneMatch(i -> i.zone().filter(zone::equals).isPresent())) return "ZONE_NOT_IN_INVENTORY";
            }
        }
        Map<Check, GateResolution.Known> resolved = new java.util.EnumMap<>(Check.class);
        for (Check check : plan.checks()) {
            PathValidation.Gate gate = switch (check) {
                case CP_ROUTE -> plan.vsid() == null ? PathValidation.CP_ROUTE_GET : PathValidation.CP_ROUTE_GET_VSX;
                case PAN_FIB -> PathValidation.PAN_FIB_LOOKUP;
                case PAN_SECURITY -> PathValidation.PAN_SECURITY_POLICY_MATCH;
                case PAN_NAT -> PathValidation.PAN_NAT_POLICY_MATCH;
            };
            Optional<GateResolution.Known> known = PathValidation.resolve(gate, gates);
            if (known.isEmpty()) return "GATE_UNAVAILABLE";
            resolved.put(check, known.get());
        }
        var e = endpoint.get();
        return new Ready(plan, device.get(), resolved, new ApiTarget(e.endpointId(), e.addressRef()));
    }

    private List<HopComparison.Outcome> checkPoint(String jobId, long epoch, Ready ready, String host, int port,
            String credentialRef) {
        HopPlan plan = ready.plan();
        GateResolution.Known gate = ready.gates().get(Check.CP_ROUTE);
        String command = PathValidation.cpRouteGet(plan.routeDestination(), Optional.ofNullable(plan.vsid()));
        String attempt = preContact(jobId, epoch, 0, gate);
        TransportSession session = null;
        try {
            var connected = ssh.connect(new ConnectionTarget(UUID.randomUUID().toString(), host, port),
                    new ConnectSpec(credentialRef, PersistedManagementEndpointTrustResolver.scopeRef(host, port), Optional.empty()),
                    Duration.ofSeconds(30));
            if (!(connected instanceof ConnectResult.Authenticated authenticated)) {
                outcome(attempt, epoch, "CONNECTION_FAILED", 0);
                return List.of(new HopComparison.Outcome(Check.CP_ROUTE, Result.NOT_EVALUABLE, "CONNECTION_FAILED"));
            }
            session = authenticated.session();
            ExecResult result = ssh.exec(session, new ExecSpec(command, true, 0, PathValidation.OUTPUT_LIMIT_BYTES),
                    Duration.ofSeconds(gate.timeoutS()));
            if (!(result instanceof ExecResult.Completed completed)) {
                outcome(attempt, epoch, "OUTPUT_UNAVAILABLE", 0);
                return List.of(new HopComparison.Outcome(Check.CP_ROUTE, Result.NOT_EVALUABLE, "OUTPUT_UNAVAILABLE"));
            }
            var parsed = MatcherAnswers.routeGet(completed.output(), plan.routeDestination());
            outcome(attempt, epoch, parsed.isPresent() ? null : "ANSWER_NOT_RECOGNISED", bytes(completed.output()));
            return List.of(HopComparison.cpRoute(plan.expect(), parsed));
        } finally {
            if (session != null) ssh.disconnect(session);
        }
    }

    private List<HopComparison.Outcome> paloAlto(String jobId, long epoch, Ready ready, String credentialRef) {
        HopPlan plan = ready.plan();
        Optional<String> key = apiKey(ready.panTarget(), credentialRef);
        List<HopComparison.Outcome> outcomes = new ArrayList<>();
        int step = 0;
        for (Check check : plan.checks()) {
            if (key.isEmpty()) {
                outcomes.add(new HopComparison.Outcome(check, Result.NOT_EVALUABLE, "AUTH_UNAVAILABLE"));
                continue;
            }
            String command = switch (check) {
                case PAN_FIB -> PathValidation.panFibLookup(plan.virtualRouter(), plan.routeDestination());
                case PAN_SECURITY -> PathValidation.panSecurityPolicyMatch(plan.fromZone(), plan.toZone(), plan.source(),
                        plan.destination(), plan.protocol(), plan.port());
                case PAN_NAT -> PathValidation.panNatPolicyMatch(plan.fromZone(), plan.natToZone(), plan.source(),
                        plan.destination(), plan.protocol(), plan.port());
                case CP_ROUTE -> throw new Abort(JobState.FAILED, "PLAN_CHECKS_INVALID");
            };
            GateResolution.Known gate = ready.gates().get(check);
            String attempt = preContact(jobId, epoch, step++, gate);
            XmlApiResult result = pan.xmlApiCall(ready.panTarget(), new XmlApiSpec("POST", "op", "", "direct_firewall",
                    Map.of("cmd", command), Map.of("X-PAN-KEY", key.get())), Duration.ofSeconds(gate.timeoutS()));
            String body = result instanceof XmlApiResult.Completed c && c.httpStatus() == 200 ? c.body() : null;
            if (body == null || body.length() > PathValidation.OUTPUT_LIMIT_BYTES) {
                String reason = body == null ? "API_UNAVAILABLE" : "ANSWER_TOO_LARGE";
                outcome(attempt, epoch, reason, 0);
                outcomes.add(new HopComparison.Outcome(check, Result.NOT_EVALUABLE, reason));
                continue;
            }
            HopComparison.Outcome compared = switch (check) {
                case PAN_FIB -> HopComparison.panFib(plan.expect(), MatcherAnswers.fibLookup(body));
                case PAN_SECURITY -> HopComparison.panSecurity(plan.expect(), MatcherAnswers.matchedRules(body));
                default -> HopComparison.panNat(plan.expect(), MatcherAnswers.matchedRules(body));
            };
            outcome(attempt, epoch, "ANSWER_NOT_RECOGNISED".equals(compared.reason()) ? compared.reason() : null, bytes(body));
            outcomes.add(compared);
        }
        return outcomes;
    }

    /** The existing PAN authentication path (as inventory and the log measurement use it); the key stays in memory. */
    private Optional<String> apiKey(ApiTarget target, String credentialRef) {
        try {
            var credential = panCredentials.resolve(credentialRef);
            var result = pan.xmlApiCall(target, new XmlApiSpec("POST", "keygen", "", "",
                    Map.of("user", credential.username(), "password", new String(credential.password())), Map.of()),
                    Duration.ofSeconds(PathValidation.TIMEOUT_S));
            String body = result instanceof XmlApiResult.Completed c && c.httpStatus() == 200 ? c.body() : "";
            Matcher match = KEY.matcher(body == null ? "" : body);
            return body != null && body.contains("status=\"success\"") && match.find()
                    ? Optional.of(match.group(1)) : Optional.empty();
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

    private void outcome(String attempt, long epoch, String failure, long bytes) {
        if (!attempts.writeOutcome(attempt, epoch, failure == null ? "MATCHED" : "EXPECTATION_UNMET", failure,
                failure == null, bytes, 0L, null)) {
            throw new Abort(JobState.OUTCOME_UNKNOWN, "ATTEMPT_RECORD_FAILED");
        }
    }

    private String checksJson(List<HopComparison.Outcome> outcomes) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        for (HopComparison.Outcome o : outcomes) {
            rows.add(Map.of("check", o.check().name(), "result", o.result().name(), "reason", o.reason()));
        }
        return json.writeValueAsString(rows);
    }

    private static long bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private void finish(String jobId, long epoch, JobState state, String reason) {
        leases.transitionState(jobId, epoch, JobState.EXECUTING, state, ACTOR, "path_validation_finished", reason);
    }
}
