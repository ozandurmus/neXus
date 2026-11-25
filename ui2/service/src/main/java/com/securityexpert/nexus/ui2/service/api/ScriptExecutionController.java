package com.securityexpert.nexus.ui2.service.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRunRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptSecretFinding;
import com.securityexpert.nexus.ui2.persistence.script.ScriptVersionRecord;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.CreateScriptCommand;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.NewVersionCommand;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.Outcome;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.OutputOutcome;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.RunOutcome;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.ScriptDetails;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.UpdateSettingsCommand;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.ScriptExecutionJob;
import com.securityexpert.nexus.ui2.service.device.DeviceWorkspaceAffordanceEvaluator;
import com.securityexpert.nexus.ui2.service.script.ScriptSchedule;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Operations › Script Execution REST routes, slice 1 ({@code SCRIPT_EXECUTION_CONTRACT.md} sections 3.1, 3.6, 3.7
 * and Amendment 2026-11-18 in {@code PO_DECISION_RECORD_2026_11_18_SCRIPT_EXECUTION_NO_REFUSALS.md}).
 *
 * <p>No route on this controller ever returns a stored script body, {@code artefact_ref}, {@code key_id} or
 * {@code wrapped_data_key} — not even to {@code role:security_admin}. Read routes return metadata only. For the
 * {@code aiview} ({@code role:replay_viewer}) persona, actor fingerprints and egress hostnames/addresses are masked,
 * and every state-changing endpoint refuses the replay viewer as defence in depth.</p>
 */
@RestController
public final class ScriptExecutionController {

    public record CreateScriptRequest(
            @JsonProperty("name") String name,
            @JsonProperty("file_name") @JsonAlias("fileName") String fileName,
            @JsonProperty("kind") String kind,
            @JsonProperty("body") @JsonAlias("content") String body,
            @JsonProperty("content_base64") @JsonAlias({"contentBase64", "body_base64", "bodyBase64"}) String contentBase64,
            @JsonProperty("schedule") String schedule,
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("timeout_s") @JsonAlias({"timeout", "timeoutSeconds"}) Integer timeoutSeconds,
            @JsonProperty("egress") List<String> egress,
            @JsonProperty("credential_refs") @JsonAlias("credentialRefs") List<String> credentialRefs,
            @JsonProperty("notify") @JsonAlias("notifyMode") String notifyMode,
            @JsonProperty("run_as") @JsonAlias("runAs") String runAs,
            @JsonProperty("notify_mail_on_failure_only") @JsonAlias("notifyMailOnFailureOnly")
            Boolean notifyMailOnFailureOnly) {

        /** Without the V266 "mail on failure only" option. */
        public CreateScriptRequest(String name, String fileName, String kind, String body, String contentBase64,
                String schedule, Boolean enabled, Integer timeoutSeconds, List<String> egress,
                List<String> credentialRefs, String notifyMode, String runAs) {
            this(name, fileName, kind, body, contentBase64, schedule, enabled, timeoutSeconds, egress, credentialRefs,
                    notifyMode, runAs, null);
        }
    }

    public record NewVersionRequest(
            @JsonProperty("file_name") @JsonAlias("fileName") String fileName,
            @JsonProperty("kind") String kind,
            @JsonProperty("body") @JsonAlias("content") String body,
            @JsonProperty("content_base64") @JsonAlias({"contentBase64", "body_base64", "bodyBase64"}) String contentBase64,
            @JsonProperty("credential_refs") @JsonAlias("credentialRefs") List<String> credentialRefs) {
    }

    public record PatchSettingsRequest(
            @JsonProperty("schedule") String schedule,
            @JsonProperty("clear_schedule") @JsonAlias("clearSchedule") Boolean clearSchedule,
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("timeout_s") @JsonAlias({"timeout", "timeoutSeconds"}) Integer timeoutSeconds,
            @JsonProperty("egress") List<String> egress,
            @JsonProperty("credential_refs") @JsonAlias("credentialRefs") List<String> credentialRefs,
            @JsonProperty("notify") @JsonAlias("notifyMode") String notifyMode,
            @JsonProperty("run_as") @JsonAlias("runAs") String runAs,
            @JsonProperty("notify_mail_on_failure_only") @JsonAlias("notifyMailOnFailureOnly")
            Boolean notifyMailOnFailureOnly) {

        /** Without the V266 "mail on failure only" option (left unchanged). */
        public PatchSettingsRequest(String schedule, Boolean clearSchedule, Boolean enabled, Integer timeoutSeconds,
                List<String> egress, List<String> credentialRefs, String notifyMode, String runAs) {
            this(schedule, clearSchedule, enabled, timeoutSeconds, egress, credentialRefs, notifyMode, runAs, null);
        }
    }

    private final ScriptExecutionService service;
    private final TopologyNamePseudonymizer names;
    private final SubnetPreservingIpMasker ipMasker;
    private DeviceWorkspaceAffordanceEvaluator affordances;

    @Autowired
    public ScriptExecutionController(
            ScriptExecutionService service,
            Optional<TopologyNamePseudonymizer> names,
            Optional<SubnetPreservingIpMasker> ipMasker,
            Optional<ActionRegistry> actions,
            Optional<RbacEvaluator> rbac,
            Optional<AuthzDecisionRepository> decisions) {
        this(service, names.orElse(null), ipMasker.orElse(null));
        if (actions.isPresent() && rbac.isPresent() && decisions.isPresent()) {
            this.affordances = new DeviceWorkspaceAffordanceEvaluator(actions.get(), rbac.get(), decisions.get());
        }
    }

    /** Test seam: the affordance evaluator behind {@code can_write} / {@code can_run}. */
    void useAffordances(DeviceWorkspaceAffordanceEvaluator evaluator) {
        this.affordances = evaluator;
    }

    /**
     * {@code can_write} / {@code can_run} / {@code can_read_output} for the caller, from the same RBAC decision the routes
     * use (the screen hides what the caller cannot do; the routes still decide). The replay viewer never writes or runs.
     */
    private Map<String, Boolean> permissions(HttpServletRequest request) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        out.put("can_write", false);
        out.put("can_run", false);
        out.put("can_read_output", false);
        String actor = actor(request);
        if (affordances == null || actor == null || actor.isBlank() || isReplayViewer(request)) {
            return out;
        }
        var permitted = affordances.evaluate(
                List.of(ActionRegistry.SCRIPT_WRITE, ActionRegistry.SCRIPT_RUN, ActionRegistry.SCRIPT_OUTPUT_READ),
                (String) request.getAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE), actor, "scripts",
                java.time.Instant.now());
        out.put("can_write", permitted.get(ActionRegistry.SCRIPT_WRITE).outcome() == AuthzOutcome.PERMITTED);
        out.put("can_run", permitted.get(ActionRegistry.SCRIPT_RUN).outcome() == AuthzOutcome.PERMITTED);
        out.put("can_read_output", permitted.get(ActionRegistry.SCRIPT_OUTPUT_READ).outcome() == AuthzOutcome.PERMITTED);
        return out;
    }

    public ScriptExecutionController(
            ScriptExecutionService service,
            TopologyNamePseudonymizer names,
            SubnetPreservingIpMasker ipMasker) {
        this.service = service;
        this.names = names;
        this.ipMasker = ipMasker;
    }

    @GetMapping("/api/v2/scripts")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        boolean masked = isReplayViewer(request);
        Map<String, Boolean> permissions = permissions(request);
        boolean runnerAvailable = service.runnerAvailable();
        List<ScriptDetails> scripts = service.list();
        List<Map<String, Object>> items = new ArrayList<>(scripts.size());
        for (ScriptDetails details : scripts) {
            Map<String, Object> item = toScriptMap(details, false, masked);
            item.put("can_run", permissions.get("can_run"));
            item.put("runner_available", runnerAvailable);
            items.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scripts", items);
        response.put("masked", masked);
        response.putAll(permissions);
        response.put("runner_available", runnerAvailable);
        response.put("run_as_options", ScriptExecutionJob.RUN_AS_ALLOWLIST);
        response.put("schedule_zone", ScriptSchedule.zone().getId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @GetMapping("/api/v2/scripts/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("id") String id, HttpServletRequest request) {
        if (!isSafeId(id)) {
            return error(400, "INVALID_SCRIPT_ID", "script_id must be a valid identifier");
        }
        boolean masked = isReplayViewer(request);
        Optional<ScriptDetails> found = service.findById(id);
        if (found.isEmpty()) {
            return error(404, "SCRIPT_NOT_FOUND", "No script with id '" + id + "'");
        }
        Map<String, Object> item = toScriptMap(found.get(), true, masked);
        item.putAll(permissions(request));
        item.put("runner_available", service.runnerAvailable());
        item.put("run_as_options", ScriptExecutionJob.RUN_AS_ALLOWLIST);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(item);
    }

    @PostMapping("/api/v2/scripts")
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody(required = false) CreateScriptRequest body,
            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> viewerRefusal = refuseReplayViewerOrAnonymous(request);
        if (viewerRefusal != null) {
            return viewerRefusal;
        }
        if (body == null) {
            return error(400, "BODY_REQUIRED", "Request body is required");
        }
        DecodedBody decoded = decodeBody(body.body(), body.contentBase64());
        if (decoded.error != null) {
            return decoded.error;
        }
        CreateScriptCommand command = new CreateScriptCommand(
                body.name(),
                body.fileName(),
                body.kind(),
                decoded.bytes,
                body.schedule(),
                body.enabled(),
                body.timeoutSeconds(),
                body.egress(),
                body.credentialRefs(),
                body.notifyMode(),
                body.runAs(),
                body.notifyMailOnFailureOnly());
        Outcome outcome = service.create(command, actor(request));
        return toOutcomeResponse(outcome, HttpStatus.CREATED);
    }

    @PostMapping("/api/v2/scripts/{id}/versions")
    public ResponseEntity<Map<String, Object>> addVersion(
            @PathVariable("id") String id,
            @RequestBody(required = false) NewVersionRequest body,
            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> viewerRefusal = refuseReplayViewerOrAnonymous(request);
        if (viewerRefusal != null) {
            return viewerRefusal;
        }
        if (!isSafeId(id)) {
            return error(400, "INVALID_SCRIPT_ID", "script_id must be a valid identifier");
        }
        if (body == null) {
            return error(400, "BODY_REQUIRED", "Request body is required");
        }
        DecodedBody decoded = decodeBody(body.body(), body.contentBase64());
        if (decoded.error != null) {
            return decoded.error;
        }
        NewVersionCommand command = new NewVersionCommand(
                body.fileName(),
                body.kind(),
                decoded.bytes,
                body.credentialRefs());
        Outcome outcome = service.addVersion(id, command, actor(request));
        return toOutcomeResponse(outcome, HttpStatus.CREATED);
    }

    @PatchMapping("/api/v2/scripts/{id}")
    public ResponseEntity<Map<String, Object>> updateSettings(
            @PathVariable("id") String id,
            @RequestBody(required = false) PatchSettingsRequest body,
            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> viewerRefusal = refuseReplayViewerOrAnonymous(request);
        if (viewerRefusal != null) {
            return viewerRefusal;
        }
        if (!isSafeId(id)) {
            return error(400, "INVALID_SCRIPT_ID", "script_id must be a valid identifier");
        }
        if (body == null) {
            return error(400, "BODY_REQUIRED", "Request body is required");
        }
        boolean clearSchedule = Boolean.TRUE.equals(body.clearSchedule())
                || (body.schedule() != null && body.schedule().isBlank());
        UpdateSettingsCommand command = new UpdateSettingsCommand(
                Optional.ofNullable(body.schedule()),
                clearSchedule,
                Optional.ofNullable(body.enabled()),
                Optional.ofNullable(body.timeoutSeconds()),
                Optional.ofNullable(body.egress()),
                Optional.ofNullable(body.credentialRefs()),
                Optional.ofNullable(body.notifyMode()),
                Optional.ofNullable(body.runAs()),
                Optional.ofNullable(body.notifyMailOnFailureOnly()));
        Outcome outcome = service.updateSettings(id, command, actor(request));
        return toOutcomeResponse(outcome, HttpStatus.OK);
    }

    @PostMapping("/api/v2/scripts/{id}/enable")
    public ResponseEntity<Map<String, Object>> enable(@PathVariable("id") String id, HttpServletRequest request) {
        return setEnabled(id, true, request);
    }

    @PostMapping("/api/v2/scripts/{id}/disable")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable("id") String id, HttpServletRequest request) {
        return setEnabled(id, false, request);
    }

    @PostMapping("/api/v2/scripts/{id}/run")
    public ResponseEntity<Map<String, Object>> runNow(@PathVariable("id") String id, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> viewerRefusal = refuseReplayViewerOrAnonymous(request);
        if (viewerRefusal != null) {
            return viewerRefusal;
        }
        if (!isSafeId(id)) {
            return error(400, "INVALID_SCRIPT_ID", "script_id must be a valid identifier");
        }
        RunOutcome outcome = service.runNow(id, actor(request));
        return switch (outcome) {
            case RunOutcome.RunSubmitted submitted -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toRunMap(submitted.run(), false, false));
            case RunOutcome.Refused refused -> error(refused.status(), refused.code(), refused.message());
        };
    }

    @GetMapping("/api/v2/scripts/{id}/runs")
    public ResponseEntity<Map<String, Object>> listRuns(@PathVariable("id") String id, HttpServletRequest request) {
        if (!isSafeId(id)) {
            return error(400, "INVALID_SCRIPT_ID", "script_id must be a valid identifier");
        }
        boolean masked = isReplayViewer(request);
        if (service.findById(id).isEmpty()) {
            return error(404, "SCRIPT_NOT_FOUND", "No script with id '" + id + "'");
        }
        List<ScriptRunRecord> runs = service.listRuns(id, 100);
        List<Map<String, Object>> items = new ArrayList<>(runs.size());
        for (ScriptRunRecord r : runs) {
            items.add(toRunMap(r, masked, false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("script_id", id);
        out.put("runs", items);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }

    @GetMapping("/api/v2/scripts/runs/{runId}")
    public ResponseEntity<Map<String, Object>> getRun(@PathVariable("runId") String runId, HttpServletRequest request) {
        if (!isSafeId(runId)) {
            return error(400, "INVALID_RUN_ID", "run_id must be a valid identifier");
        }
        boolean masked = isReplayViewer(request);
        Optional<ScriptRunRecord> found = service.findRun(runId);
        if (found.isEmpty()) {
            return error(404, "SCRIPT_RUN_NOT_FOUND", "No script run with id '" + runId + "'");
        }
        ScriptRunRecord run = found.get();
        boolean canReadOutput = canReadOutputForRun(request, masked);
        if (canReadOutput && run.outputTail().isPresent()) {
            if (!service.auditOutputRead(run.jobId(), actor(request))) {
                return error(500, "SCRIPT_OUTPUT_AUDIT_FAILED", "Could not record the output read audit row");
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toRunMap(run, masked, canReadOutput));
    }

    @GetMapping("/api/v2/scripts/runs/{runId}/output")
    public ResponseEntity<?> downloadRunOutput(@PathVariable("runId") String runId, HttpServletRequest request) {
        if (!isSafeId(runId)) {
            return error(400, "INVALID_RUN_ID", "run_id must be a valid identifier");
        }
        String actor = actor(request);
        if (actor == null || actor.isBlank()) {
            return error(403, "AUTHENTICATION_REQUIRED", "An authenticated session is required");
        }
        boolean masked = isReplayViewer(request);
        if (masked) {
            return error(403, "FORBIDDEN_FOR_VIEWER", "Script output download is not permitted for the replay viewer");
        }
        OutputOutcome outcome = service.downloadRunOutput(runId, actor);
        return switch (outcome) {
            case OutputOutcome.OutputReady ready -> {
                String text = new String(ready.plaintextBytes(), StandardCharsets.UTF_8);
                yield ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(text);
            }
            case OutputOutcome.Refused refused -> error(refused.status(), refused.code(), refused.message());
        };
    }

    private ResponseEntity<Map<String, Object>> setEnabled(String id, boolean enabled, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> viewerRefusal = refuseReplayViewerOrAnonymous(request);
        if (viewerRefusal != null) {
            return viewerRefusal;
        }
        if (!isSafeId(id)) {
            return error(400, "INVALID_SCRIPT_ID", "script_id must be a valid identifier");
        }
        Outcome outcome = service.setEnabled(id, enabled, actor(request));
        return toOutcomeResponse(outcome, HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> toOutcomeResponse(Outcome outcome, HttpStatus okStatus) {
        return switch (outcome) {
            case Outcome.Ok ok -> ResponseEntity.status(okStatus)
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toScriptMap(ok.details(), true, false));
            case Outcome.Refused refused -> error(refused.status(), refused.code(), refused.message());
        };
    }

    private record DecodedBody(byte[] bytes, ResponseEntity<Map<String, Object>> error) {
    }

    private static DecodedBody decodeBody(String plainBody, String base64Body) {
        if (base64Body != null && !base64Body.isBlank()) {
            try {
                return new DecodedBody(Base64.getDecoder().decode(base64Body.strip()), null);
            } catch (IllegalArgumentException e) {
                return new DecodedBody(null, error(400, "INVALID_BASE64", "content_base64 is not valid Base64"));
            }
        }
        if (plainBody != null) {
            return new DecodedBody(plainBody.getBytes(StandardCharsets.UTF_8), null);
        }
        return new DecodedBody(new byte[0], null);
    }

    private Map<String, Object> toScriptMap(ScriptDetails details, boolean includeVersions, boolean masked) {
        ScriptRecord s = details.script();
        List<ScriptVersionRecord> versions = details.versions();
        ScriptVersionRecord current = versions.stream()
                .filter(v -> v.version() == s.currentVersion())
                .findFirst()
                .orElse(versions.isEmpty() ? null : versions.get(0));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("script_id", s.scriptId());
        out.put("name", s.name());
        out.put("kind", s.kind());
        out.put("current_version", s.currentVersion());
        out.put("sha256", current == null ? null : current.sha256());
        out.put("size_bytes", current == null ? 0L : current.sizeBytes());
        out.put("secret_findings", toSecretFindingsMap(current == null ? List.of() : current.secretFindings()));
        out.put("enabled", s.enabled());
        out.put("schedule", s.schedule().orElse(null));
        out.put("next_five_fires", details.nextFiveFires().stream().map(Instant::toString).toList());
        out.put("timeout_s", s.timeoutSeconds());
        out.put("egress", masked ? maskEgressList(s.egress()) : s.egress());
        out.put("egress_device_targets", s.egressDeviceTargets());
        out.put("credential_refs", s.credentialRefs());
        out.put("notify", s.notifyMode());
        out.put("notify_mail_on_failure_only", s.notifyMailOnFailureOnly());
        out.put("run_as", maskRunAs(s.runAs(), masked));
        out.put("created_by_actor_fingerprint", maskActor(s.createdByActorFingerprint(), masked));
        out.put("created_at", s.createdAt().toString());
        out.put("updated_at", s.updatedAt().toString());
        out.put("warnings", details.warnings());
        // Outcome token and finish time of the latest FINISHED run only; null/null when none finished (never invented).
        Optional<ScriptRunRecord> lastRun = service.lastFinishedRun(s.scriptId());
        out.put("last_run_outcome", lastRun.flatMap(ScriptRunRecord::outcome).orElse(null));
        out.put("last_run_at", lastRun.flatMap(ScriptRunRecord::finishedAt).map(Instant::toString).orElse(null));
        if (includeVersions) {
            List<Map<String, Object>> versionList = new ArrayList<>(versions.size());
            for (ScriptVersionRecord v : versions) {
                Map<String, Object> vm = new LinkedHashMap<>();
                vm.put("version", v.version());
                vm.put("sha256", v.sha256());
                vm.put("size_bytes", v.sizeBytes());
                vm.put("secret_findings", toSecretFindingsMap(v.secretFindings()));
                vm.put("credential_refs", v.credentialRefs());
                vm.put("egress_device_targets", v.egressDeviceTargets());
                vm.put("created_by", maskActor(v.createdBy(), masked));
                vm.put("created_at", v.createdAt().toString());
                versionList.add(vm);
            }
            out.put("versions", versionList);
        }
        return out;
    }

    private boolean canReadOutputForRun(HttpServletRequest request, boolean masked) {
        if (masked || affordances == null) {
            return false;
        }
        String actor = actor(request);
        if (actor == null || actor.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(permissions(request).get("can_read_output"));
    }

    private Map<String, Object> toRunMap(ScriptRunRecord r, boolean masked, boolean includeOutputTail) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job_id", r.jobId());
        out.put("run_id", r.jobId());
        out.put("script_id", r.scriptId());
        out.put("version", r.version());
        out.put("state", r.state());
        out.put("outcome", r.outcome().orElse(null));
        out.put("trigger_kind", r.triggerKind());
        out.put("scheduled_slot", r.scheduledSlot().map(Instant::toString).orElse(null));
        out.put("run_as", maskRunAs(r.runAs(), masked));
        out.put("requested_by", maskActor(r.requestedBy(), masked));
        out.put("requested_at", r.requestedAt().toString());
        out.put("started_at", r.startedAt().map(Instant::toString).orElse(null));
        out.put("finished_at", r.finishedAt().map(Instant::toString).orElse(null));
        out.put("duration_ms", r.durationMs().orElse(null));
        out.put("exit_code", r.exitCode().orElse(null));
        out.put("stdout_bytes", r.stdoutBytes());
        out.put("stderr_bytes", r.stderrBytes());
        out.put("stdout_truncated", r.stdoutTruncated());
        out.put("stderr_truncated", r.stderrTruncated());
        out.put("has_output", r.outputArtefactRef().isPresent());
        out.put("output_sha256", r.outputSha256().orElse(null));
        out.put("output_size_bytes", r.outputSizeBytes().orElse(null));
        String tail = (!masked && includeOutputTail) ? r.outputTail().orElse(null) : null;
        out.put("output_tail", tail);
        out.put("egress", masked ? maskEgressList(r.egressDeclared()) : r.egressDeclared());
        out.put("egress_device_targets", r.egressDeviceTargets());
        out.put("credential_refs_used", r.credentialRefsUsed());
        out.put("failure_reason", r.failureReason().orElse(null));
        out.put("notification", r.notification().map(n -> {
            Map<String, Object> nm = new LinkedHashMap<>();
            nm.put("syslog", n.syslog());
            nm.put("syslog_code", n.syslogCode().orElse(null));
            nm.put("smtp", n.smtp());
            nm.put("smtp_code", n.smtpCode().orElse(null));
            nm.put("notified_at", n.notifiedAt().toString());
            return nm;
        }).orElse(null));
        return out;
    }

    private static Map<String, Object> toSecretFindingsMap(List<ScriptSecretFinding> findings) {
        List<ScriptSecretFinding> list = findings == null ? List.of() : findings;
        List<Integer> lineNumbers = list.stream().map(ScriptSecretFinding::line).distinct().sorted().toList();
        List<Map<String, Object>> items = new ArrayList<>(list.size());
        for (ScriptSecretFinding f : list) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("line", f.line());
            item.put("pattern_class", f.patternClass());
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("line_numbers", lineNumbers);
        out.put("findings", items);
        return out;
    }

    private String maskActor(String actor, boolean masked) {
        if (!masked || actor == null || actor.isBlank()) {
            return actor;
        }
        if (actor.startsWith("system:") || actor.startsWith("migration:")) {
            return actor;
        }
        return names != null ? names.identityTokens().user(actor.strip()) : "USR-MASKED";
    }

    private String maskRunAs(String runAs, boolean masked) {
        if (!masked || runAs == null || runAs.isBlank() || runAs.startsWith("role:")) {
            return runAs;
        }
        return maskActor(runAs, true);
    }

    private List<String> maskEgressList(List<String> egress) {
        if (egress == null || egress.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(egress.size());
        for (String item : egress) {
            int colon = item.lastIndexOf(':');
            if (colon <= 0) {
                out.add(item);
                continue;
            }
            String host = item.substring(0, colon);
            String port = item.substring(colon + 1);
            String maskedHost = ipMasker != null ? ipMasker.maskText(host) : "192.0.2.1";
            if (maskedHost.equals(host) && names != null) {
                maskedHost = names.maskDomainName(host);
            }
            out.add(maskedHost + ":" + port);
        }
        return List.copyOf(out);
    }

    private static ResponseEntity<Map<String, Object>> refuseReplayViewerOrAnonymous(HttpServletRequest request) {
        String actor = actor(request);
        if (actor == null || actor.isBlank()) {
            return error(403, "AUTHENTICATION_REQUIRED", "An authenticated security_admin is required");
        }
        if (isReplayViewer(request)) {
            return error(403, "FORBIDDEN_FOR_VIEWER", "Script mutations are not permitted for the replay viewer");
        }
        return null;
    }

    private static boolean isReplayViewer(HttpServletRequest request) {
        return request != null && PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
    }

    private static String actor(HttpServletRequest request) {
        return request == null ? null : (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }

    private static boolean isSafeId(String id) {
        return id != null && !id.isBlank() && id.length() <= 128 && id.matches("[A-Za-z0-9._:-]+");
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("code", code);
        body.put("reason", message);
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.valueOf(status))
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
