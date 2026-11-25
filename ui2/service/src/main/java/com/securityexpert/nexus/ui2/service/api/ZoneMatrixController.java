package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixSummaryView;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Refused;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.View;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Zone matrix, delivery steps 1 and 2 (docs/design/ZONE_MATRIX_CONTRACT.md sections 3, 4, 6.1 and 10.1, FROZEN 2026-11-13). The gate
 * admits each route by its action (SecurityWebMvcConfig); this controller adds the second checks the contract names: the
 * blocks view needs {@code zone_matrix_zones_read}, the masked persona never sees or changes blocks, and the response
 * carries the caller's affordances so the screen computes no visibility of its own. Step 4b-2b adds the read-only
 * stored summary (sections 7.1 and 7.2): no route here mutates or deletes a run or a finding (ZM-37). Step 4b-2 adds the
 * on-demand trigger of section 5.1 ({@code POST /api/v2/zone-matrix/runs}): it starts a new durable run through the same
 * {@link ZoneMatrixRunService#submit} the publish and collection triggers use, and never changes or deletes an existing run.
 */
@RestController
public final class ZoneMatrixController {
    private final ZoneMatrixService service;
    private final ZoneMatrixRunService runs;
    private final RbacEvaluator rbac;
    private final ActionRegistry actions;

    public ZoneMatrixController(ZoneMatrixService service, ZoneMatrixRunService runs, RbacEvaluator rbac, ActionRegistry actions) {
        this.service = service; this.runs = runs; this.rbac = rbac; this.actions = actions;
    }

    public record ZoneRequest(Long revision, String name, String description, String role, java.util.List<String> blocks) {}
    public record RevisionRequest(Long revision) {}
    public record LookupRequest(String address, String version_id) {}
    public record CellRequest(Long revision, String source, String destination, String statement, java.util.List<String> services,
            String severity, String rationale, String regulation_reference) {}
    public record PairRequest(Long revision, String source, String destination) {}
    public record DefaultRequest(Long revision, String statement, String severity) {}
    public record ServiceSetRequest(Long revision, String name, java.util.List<String> entries) {}
    public record TemplateImportRequest(Long revision, Boolean replace) {}
    public record ConfirmRequest(Long revision, String source, String destination, String cell_sha256) {}
    public record EvaluateRequest(String policy_id, String version_id, Boolean include_witnesses) {}
    public record RunRequest(String policy_id, String version_id) {}

    @GetMapping("/api/v2/zone-matrix/versions")
    public ResponseEntity<?> versions(HttpServletRequest request) {
        return run(() -> Map.of("versions", service.versions(masked(request) ? View.MASKED : View.NAMES, reviewer(request)), "can", can(request)));
    }

    @GetMapping("/api/v2/zone-matrix/versions/{id}")
    public ResponseEntity<?> version(@PathVariable String id, @RequestParam(name = "blocks", defaultValue = "0") String blocks,
            HttpServletRequest request) {
        return run(() -> with(service.version(id, view(request, blocks), actor(request), reviewer(request)), request));
    }

    @GetMapping("/api/v2/zone-matrix/draft")
    public ResponseEntity<?> draft(@RequestParam(name = "blocks", defaultValue = "0") String blocks, HttpServletRequest request) {
        return run(() -> with(service.draft(view(request, blocks), actor(request), masked(request), reviewer(request)), request));
    }

    @PostMapping("/api/v2/zone-matrix/lookup")
    public ResponseEntity<?> lookup(@RequestBody LookupRequest body, HttpServletRequest request) {
        return run(() -> {
            if (masked(request)) throw readOnly();
            return service.lookup(actor(request), body == null ? null : body.address(), body == null ? null : body.version_id());
        });
    }

    @PostMapping("/api/v2/zone-matrix/draft/zones")
    public ResponseEntity<?> createZone(@RequestBody ZoneRequest body, HttpServletRequest request) {
        return run(() -> { writable(request); return service.createZone(actor(request), revision(body), input(body)); });
    }

    @PutMapping("/api/v2/zone-matrix/draft/zones/{zoneId}")
    public ResponseEntity<?> updateZone(@PathVariable String zoneId, @RequestBody ZoneRequest body, HttpServletRequest request) {
        return run(() -> { writable(request); return service.updateZone(actor(request), revision(body), zoneId, input(body)); });
    }

    @PostMapping("/api/v2/zone-matrix/draft/zones/{zoneId}/delete")
    public ResponseEntity<?> deleteZone(@PathVariable String zoneId, @RequestBody RevisionRequest body, HttpServletRequest request) {
        return run(() -> { writable(request); return service.deleteZone(actor(request), body == null ? null : body.revision(), zoneId); });
    }

    @PostMapping("/api/v2/zone-matrix/draft/discard")
    public ResponseEntity<?> discard(@RequestBody RevisionRequest body, HttpServletRequest request) {
        return run(() -> {
            writable(request);
            if (body == null || body.revision() == null) throw new Refused(400, "INVALID_REQUEST", "revision is required");
            return service.discardDraft(actor(request), body.revision());
        });
    }

    @GetMapping("/api/v2/zone-matrix/template")
    public ResponseEntity<?> template(HttpServletRequest request) { return run(() -> with(service.template(), request)); }

    @PostMapping("/api/v2/zone-matrix/draft/cells")
    public ResponseEntity<?> setCell(@RequestBody CellRequest body, HttpServletRequest request) {
        return run(() -> {
            writable(request);
            if (body == null) throw new Refused(400, "INVALID_CELL", "cell body missing");
            return service.setCell(actor(request), body.revision(), new ZoneMatrixService.CellInput(body.source(), body.destination(),
                    body.statement(), body.services(), body.severity(), body.rationale(), body.regulation_reference()));
        });
    }

    @PostMapping("/api/v2/zone-matrix/draft/cells/clear")
    public ResponseEntity<?> clearCell(@RequestBody PairRequest body, HttpServletRequest request) {
        return run(() -> {
            writable(request);
            if (body == null) throw new Refused(400, "INVALID_CELL", "cell body missing");
            return service.clearCell(actor(request), body.revision(), body.source(), body.destination());
        });
    }

    @PostMapping("/api/v2/zone-matrix/draft/default")
    public ResponseEntity<?> setDefault(@RequestBody DefaultRequest body, HttpServletRequest request) {
        return run(() -> {
            writable(request);
            if (body == null) throw new Refused(400, "INVALID_DEFAULT", "default body missing");
            return service.setDefault(actor(request), body.revision(), body.statement(), body.severity());
        });
    }

    @PostMapping("/api/v2/zone-matrix/draft/service-sets")
    public ResponseEntity<?> saveServiceSet(@RequestBody ServiceSetRequest body, HttpServletRequest request) {
        return run(() -> {
            writable(request);
            if (body == null) throw new Refused(400, "INVALID_SERVICE_SET", "service set body missing");
            return service.saveServiceSet(actor(request), body.revision(), body.name(), body.entries());
        });
    }

    @PostMapping("/api/v2/zone-matrix/draft/service-sets/delete")
    public ResponseEntity<?> deleteServiceSet(@RequestBody ServiceSetRequest body, HttpServletRequest request) {
        return run(() -> {
            writable(request);
            if (body == null) throw new Refused(400, "INVALID_SERVICE_SET", "service set body missing");
            return service.deleteServiceSet(actor(request), body.revision(), body.name());
        });
    }

    @PostMapping("/api/v2/zone-matrix/draft/template-import")
    public ResponseEntity<?> importTemplate(@RequestBody TemplateImportRequest body, HttpServletRequest request) {
        return run(() -> {
            writable(request);
            return service.importTemplate(actor(request), body == null ? null : body.revision(), body != null && Boolean.TRUE.equals(body.replace()));
        });
    }

    @PostMapping("/api/v2/zone-matrix/draft/cells/confirm-reference")
    public ResponseEntity<?> confirmReference(@RequestBody ConfirmRequest body, HttpServletRequest request) {
        return run(() -> {
            if (body == null) throw new Refused(400, "INVALID_CONFIRMATION", "confirmation body missing");
            return service.confirmReference(actor(request), masked(request), body.revision(), body.source(), body.destination(),
                    body.cell_sha256());
        });
    }

    @PostMapping("/api/v2/zone-matrix/draft/publish")
    public ResponseEntity<?> publish(@RequestBody ZoneMatrixService.PublishRequest body, HttpServletRequest request) {
        return run(() -> service.publish(actor(request), masked(request), body));
    }

    @GetMapping("/api/v2/zone-matrix/policies")
    public ResponseEntity<?> policies(HttpServletRequest request) {
        boolean masked = masked(request);
        return run(() -> with(service.policies(masked ? View.MASKED : View.NAMES,
                !masked && permitted(request, ActionRegistry.POLICY_READ)), request));
    }

    @PostMapping("/api/v2/zone-matrix/evaluate")
    public ResponseEntity<?> evaluate(@RequestBody(required = false) EvaluateRequest body, HttpServletRequest request) {
        boolean masked = masked(request);
        return run(() -> with(service.evaluate(
                actor(request),
                masked ? View.MASKED : View.NAMES,
                masked,
                !masked && permitted(request, ActionRegistry.ZONE_MATRIX_ZONES_READ),
                !masked && permitted(request, ActionRegistry.POLICY_READ),
                body == null ? null : body.policy_id(),
                body == null ? null : body.version_id(),
                body != null && Boolean.TRUE.equals(body.include_witnesses())), request));
    }

    /**
     * Step 4b-2, section 5.1 "On demand (zone_matrix_evaluate), same code path": starts a durable run of one stored policy
     * against the active version or a named published version, on the run service's own bounded pool. Answers 202 with the
     * run id, whether a run of that policy was already running (then its id is returned), and the run state. The answer
     * carries ids and a state only; the result is read later from the stored summary, which shows COMPLETE runs only.
     * Refusals are the ones {@link ZoneMatrixRunService#submit} throws. The masked persona never starts a run.
     */
    @PostMapping("/api/v2/zone-matrix/runs")
    public ResponseEntity<?> startRun(@RequestBody(required = false) RunRequest body, HttpServletRequest request) {
        ResponseEntity<?> answer = run(() -> {
            if (masked(request)) throw new Refused(403, "READ_ONLY_PERSONA", "the masked persona cannot start a zone matrix run");
            ZoneMatrixRunService.Submission submission = runs.submit(body == null ? null : body.policy_id(),
                    body == null ? null : body.version_id());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("run_id", submission.runId());
            out.put("already_running", submission.alreadyRunning());
            out.put("state", submission.state());
            return out;
        });
        return answer.getStatusCode().value() == 200 ? ResponseEntity.accepted().body(answer.getBody()) : answer;
    }

    /**
     * Step 4b-2b: the stored summary of the latest {@code COMPLETE} run of each policy (Violations and Coverage tabs).
     * Read-only. The masked persona, and any caller without {@code policy_read}, get names through the masked
     * projection; no caller gets blocks, addresses or witness data here.
     */
    @GetMapping("/api/v2/zone-matrix/summary")
    public ResponseEntity<?> summary(@RequestParam(name = "policy_id", required = false) String policyId,
            @RequestParam(name = "outcome", required = false) String outcome,
            @RequestParam(name = "limit", required = false) String limit, HttpServletRequest request) {
        boolean masked = masked(request);
        return run(() -> {
            ZoneMatrixSummaryView.Query.checkParameterNames(request.getParameterMap());
            var query = ZoneMatrixSummaryView.Query.parse(policyId, outcome, limit);
            var labels = service.summaryLabels(masked ? View.MASKED : View.NAMES,
                    !masked && permitted(request, ActionRegistry.POLICY_READ));
            return with(ZoneMatrixSummaryView.project(runs.storedSummary(), labels, query), request);
        });
    }

    private View view(HttpServletRequest request, String blocks) {
        if (masked(request)) return View.MASKED;
        if (!"1".equals(blocks)) return View.NAMES;
        if (!permitted(request, ActionRegistry.ZONE_MATRIX_ZONES_READ))
            throw new Refused(403, "ZONE_BLOCKS_NOT_PERMITTED", "viewing zone blocks needs zone_matrix_zones_read");
        return View.BLOCKS;
    }

    /** Editors and publishers review drafts; the masked persona never does. */
    private boolean reviewer(HttpServletRequest request) {
        return !masked(request) && (permitted(request, ActionRegistry.ZONE_MATRIX_EDIT) || permitted(request, ActionRegistry.ZONE_MATRIX_PUBLISH));
    }

    private void writable(HttpServletRequest request) { if (masked(request)) throw readOnly(); }

    private static Long revision(ZoneRequest body) { return body == null ? null : body.revision(); }

    private static ZoneMatrixService.ZoneInput input(ZoneRequest body) {
        if (body == null) throw new Refused(400, "INVALID_ZONE", "zone body missing");
        return new ZoneMatrixService.ZoneInput(body.name(), body.description(), body.role(), body.blocks());
    }

    private Map<String, Object> with(Map<String, Object> body, HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>(body);
        out.put("can", can(request));
        return out;
    }

    /** The caller's affordances, from the same actions the gate uses. */
    private Map<String, Boolean> can(HttpServletRequest request) {
        boolean masked = masked(request);
        Map<String, Boolean> out = new LinkedHashMap<>();
        out.put("view_blocks", !masked && permitted(request, ActionRegistry.ZONE_MATRIX_ZONES_READ));
        out.put("edit", !masked && permitted(request, ActionRegistry.ZONE_MATRIX_EDIT));
        out.put("publish", !masked && permitted(request, ActionRegistry.ZONE_MATRIX_PUBLISH));
        out.put("confirm_reference", !masked && permitted(request, ActionRegistry.ZONE_MATRIX_REFERENCE_CONFIRM));
        out.put("evaluate", !masked && permitted(request, ActionRegistry.ZONE_MATRIX_EVALUATE));
        return out;
    }

    private boolean permitted(HttpServletRequest request, String actionId) {
        var action = actions.find(actionId).orElseThrow();
        return rbac.evaluateAny(actor(request), action.requiredRoleTokens(), Instant.now()).outcome().proceeds();
    }

    private static ResponseEntity<?> run(Supplier<Object> work) {
        try {
            return ResponseEntity.ok(work.get());
        } catch (Refused refused) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", refused.code);
            body.put("reason", refused.getMessage());
            body.putAll(refused.details);
            return ResponseEntity.status(refused.status).body(body);
        }
    }

    private static Refused readOnly() { return new Refused(403, "READ_ONLY_PERSONA", "the masked persona cannot change or look up zones"); }

    private static String actor(HttpServletRequest request) { return (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE); }

    private static boolean masked(HttpServletRequest request) {
        return PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
    }
}
