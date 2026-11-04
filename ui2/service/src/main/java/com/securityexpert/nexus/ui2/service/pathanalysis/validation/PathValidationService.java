package com.securityexpert.nexus.ui2.service.pathanalysis.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.pathvalidation.PathValidationRepository;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * Stage A6: turns a path result the server has just computed into one {@code path_validation} job per plannable hop
 * (at most {@link PathValidation#MAX_HOPS}) and reads the recorded outcome back. The caller never supplies a command,
 * gate, context, zone, virtual router or rule: they come from the result ({@link PathValidationPlanner}).
 *
 * <p>Admission goes through the shared {@link JobAdmissionService} (capability eligibility, device enrollment,
 * idempotency, the job windows with the administrator grant, and the module claim model of V188), exactly as the other
 * diagnostic reads. The window is checked before anything is written, so a refused request leaves no plan behind; a hop
 * whose job is not admitted becomes NOT_EVALUABLE with the refusal code and its plan is cleared at once.</p>
 */
public final class PathValidationService {

    /** A request that has been planned but not yet written: the controller audits it first. */
    public record Prepared(String validationId, PathResult result, PathValidationPlanner.Plan plan) {
    }

    private static final Set<String> ACTIVE = Set.of("REQUESTED", "CLAIMED", "EXECUTING");
    private static final TypeReference<List<Map<String, Object>>> CHECKS = new TypeReference<>() { };

    private final PathValidationRepository repository;
    private final JobAdmissionService admission;
    private final DeviceInventoryRepository inventory;
    private final JobWindowPolicy windows;
    private final ObjectMapper json = new ObjectMapper();

    public PathValidationService(PathValidationRepository repository, JobAdmissionService admission,
            DeviceInventoryRepository inventory, JobWindowPolicy windows) {
        this.repository = repository;
        this.admission = admission;
        this.inventory = inventory;
        this.windows = windows;
    }

    /** Plans the validation (reads only); empty when {@code branchId} names no branch of the result. */
    public Optional<Prepared> prepare(PathResult result, Optional<String> branchId, String actor) {
        windows.requireOpenFor(actor);
        return PathValidationPlanner.plan(result, branchId, inventory::findLatestRun)
                .map(plan -> new Prepared("pv-" + UUID.randomUUID(), result, plan));
    }

    /** Writes the request and its hops, then admits one job per plannable hop. */
    public Map<String, Object> submit(Prepared prepared, String actor) {
        windows.requireOpenFor(actor);
        List<PathValidationRepository.NewHop> rows = new ArrayList<>();
        for (PathValidationPlanner.PlannedHop hop : prepared.plan().hops()) {
            Optional<String> planJson = hop.plan().map(this::write);
            rows.add(new PathValidationRepository.NewHop(hop.ordinal(), hop.branchId(), hop.hopIndex(), hop.nodeId(),
                    hop.vendor(), hop.deviceId(), planJson,
                    planJson.isPresent() ? PathValidationRepository.PENDING : PathValidationRepository.NOT_EVALUABLE,
                    hop.reasons()));
        }
        repository.create(prepared.validationId(), actor, prepared.result().topologySnapshotId(),
                prepared.result().verdict(), prepared.plan().hopsNotValidated(), rows);
        List<PathValidationPlanner.PlannedHop> runnable = prepared.plan().hops().stream()
                .filter(h -> h.plan().isPresent()).toList();
        for (int i = 0; i < runnable.size(); i++) {
            PathValidationPlanner.PlannedHop hop = runnable.get(i);
            try {
                AdmissionResult result = admission.submit(PathValidation.CAPABILITY, hop.deviceId().orElseThrow(),
                        "path-validation:" + prepared.validationId() + ":" + hop.ordinal(), actor,
                        ActionRegistry.PATH_ANALYSIS_VALIDATE);
                switch (result) {
                    case AdmissionResult.Admitted admitted -> {
                        if (!repository.attachJob(prepared.validationId(), hop.ordinal(), admitted.jobId()))
                            repository.abandon(prepared.validationId(), hop.ordinal(), "JOB_NOT_BOUND");
                    }
                    case AdmissionResult.Deduplicated duplicate ->
                            repository.abandon(prepared.validationId(), hop.ordinal(), "JOB_NOT_BOUND");
                    case AdmissionResult.Refused refused ->
                            repository.abandon(prepared.validationId(), hop.ordinal(), code(refused.code()));
                }
            } catch (RuntimeException failure) {
                // e.g. the window closed between hops: nothing of this request may stay pending with its plan.
                for (PathValidationPlanner.PlannedHop rest : runnable.subList(i, runnable.size()))
                    repository.abandon(prepared.validationId(), rest.ordinal(), "ADMISSION_FAILED");
                throw failure;
            }
        }
        return read(prepared.validationId()).orElseThrow();
    }

    /**
     * The recorded outcome: enums, reason codes and opaque references only. A hop still PENDING whose job has ended
     * without an outcome (cancelled, expired, reconciled) is settled here: NOT_EVALUABLE, plan cleared.
     */
    public Optional<Map<String, Object>> read(String validationId) {
        if (validationId == null || !validationId.matches("pv-[0-9a-f-]{36}")) {
            return Optional.empty();
        }
        Optional<PathValidationRepository.Validation> found = repository.find(validationId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        boolean settled = false;
        for (var hop : found.get().hops()) {
            if (PathValidationRepository.PENDING.equals(hop.result())
                    && (hop.jobId().isEmpty() || hop.jobState().filter(ACTIVE::contains).isEmpty())) {
                repository.abandon(validationId, hop.ordinal(), "JOB_ENDED_WITHOUT_OUTCOME");
                settled = true;
            }
        }
        PathValidationRepository.Validation v = settled ? repository.find(validationId).orElseThrow() : found.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("validation_id", v.validationId());
        body.put("submitted_at", v.submittedAt().toString());
        body.put("topology_snapshot_id", v.topologySnapshotId());
        body.put("path_verdict", v.pathVerdict());
        body.put("result", overall(v.hops()));
        body.put("hops_not_validated", v.hopsNotValidated());
        List<Map<String, Object>> hops = new ArrayList<>();
        for (var hop : v.hops()) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("ordinal", hop.ordinal());
            h.put("branch_id", hop.branchId());
            h.put("hop_index", hop.hopIndex());
            h.put("node_id", hop.nodeId());
            h.put("vendor", hop.vendor());
            h.put("device_id", hop.deviceId().orElse(null));
            h.put("job_id", hop.jobId().orElse(null));
            h.put("job_state", hop.jobState().orElse(null));
            h.put("result", hop.result());
            h.put("checks", checks(hop.checksJson()));
            h.put("reasons", hop.reasons());
            h.put("evaluated_at", hop.evaluatedAt().map(Object::toString).orElse(null));
            hops.add(h);
        }
        body.put("hops", hops);
        body.put("limitations", "A MATCH means the device's own read-only matcher agreed with the computed hop for this "
                + "sample; it proves neither other flows nor observed traffic. NOT_EVALUABLE is never a mismatch.");
        return Optional.of(body);
    }

    static String overall(List<PathValidationRepository.Hop> hops) {
        if (hops.stream().anyMatch(h -> "MISMATCH".equals(h.result()))) return "MISMATCH";
        if (hops.stream().anyMatch(h -> PathValidationRepository.PENDING.equals(h.result()))) return "PENDING";
        if (hops.stream().anyMatch(h -> "MATCH".equals(h.result()))) return "MATCH";
        return "NOT_EVALUABLE";
    }

    private List<Map<String, Object>> checks(String text) {
        try {
            return text == null ? List.of() : json.readValue(text, CHECKS);
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    private String write(Object plan) {
        try {
            return json.writeValueAsString(plan);
        } catch (Exception e) {
            throw new IllegalStateException("PLAN_NOT_SERIALIZABLE");
        }
    }

    /** A refusal code as a stored reason; anything outside the reason vocabulary is generic. */
    private static String code(String refusal) {
        return refusal != null && refusal.matches("[A-Z0-9_]{1,64}") ? refusal : "ADMISSION_REFUSED";
    }
}
