package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRun;
import com.securityexpert.nexus.ui2.service.discovery.DiscoveryRunService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * The three 14F section 3 routes: {@code POST /discovery/runs}, {@code GET
 * /discovery/runs/{run_id}}, {@code POST /discovery/runs/{run_id}/import}.
 * Gated by {@link GateChainInterceptor} through {@code SecurityWebMvcConfig}
 * -- this class performs no authorization check of its own. JSON keys are
 * exactly 14F section 3 and DR-1/DR-2's own field names.
 */
@RestController
public final class DiscoveryController {

    public record StartRequest(
            @JsonProperty("management_address") String managementAddress,
            @JsonProperty("vendor") String vendor,
            @JsonProperty("credential_reference_id") String credentialReferenceId) {
    }

    public record ImportRequest(
            @JsonProperty("candidate_ids") List<String> candidateIds,
            @JsonProperty("credential_reference_id") String credentialReferenceId,
            @JsonProperty("export_passphrase_credential_reference_id") String exportPassphraseCredentialReferenceId) {
    }

    public record TrustRequest(
            @JsonProperty("management_address") String managementAddress,
            @JsonProperty("management_port") int managementPort,
            @JsonProperty("key_algorithm") String keyAlgorithm,
            @JsonProperty("fingerprint_sha256") String fingerprint,
            @JsonProperty("observed_at") java.time.Instant observedAt,
            @JsonProperty("independently_verified") boolean independentlyVerified) {
    }

    public record PanTrustRequest(
            @JsonProperty("management_address") String managementAddress,
            @JsonProperty("management_port") int managementPort,
            @JsonProperty("fingerprint_sha256") String fingerprint,
            @JsonProperty("observed_at") java.time.Instant observedAt,
            @JsonProperty("explicitly_confirmed") boolean explicitlyConfirmed) {
    }

    private final com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointSshTrustService trustService;
    private final com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointPanCertTrustService panTrustService;
    private final DiscoveryRunService discoveryRunService;

    public DiscoveryController(DiscoveryRunService discoveryRunService,
            com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointSshTrustService trustService,
            com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointPanCertTrustService panTrustService) {
        this.discoveryRunService = discoveryRunService;
        this.trustService = trustService;
        this.panTrustService = panTrustService;
    }

    @PostMapping("/discovery/ssh-trust/enroll")
    public ResponseEntity<Map<String, Object>> enrollTrust(@RequestBody TrustRequest request, HttpServletRequest servletRequest) {
        return authorizeTrust(request, servletRequest, false);
    }

    @PostMapping("/discovery/ssh-trust/re-enroll")
    public ResponseEntity<Map<String, Object>> reEnrollTrust(@RequestBody TrustRequest request, HttpServletRequest servletRequest) {
        return authorizeTrust(request, servletRequest, true);
    }

    @PostMapping("/discovery/pan-trust/enroll")
    public ResponseEntity<Map<String, Object>> enrollPanTrust(@RequestBody PanTrustRequest request,
            HttpServletRequest servletRequest) {
        return authorizePanTrust(request, servletRequest, false);
    }

    @PostMapping("/discovery/pan-trust/re-enroll")
    public ResponseEntity<Map<String, Object>> reEnrollPanTrust(@RequestBody PanTrustRequest request,
            HttpServletRequest servletRequest) {
        return authorizePanTrust(request, servletRequest, true);
    }

    private ResponseEntity<Map<String, Object>> authorizeTrust(TrustRequest request, HttpServletRequest servletRequest,
            boolean reEnroll) {
        var outcome = trustService.enroll(actingUser(servletRequest), request.managementAddress(), request.managementPort(),
                request.keyAlgorithm(), request.fingerprint(), request.observedAt(), request.independentlyVerified(), reEnroll);
        return ResponseEntity.status(outcome == com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointSshTrustService.Outcome.MATCH
                ? HttpStatus.OK : HttpStatus.CONFLICT).body(Map.of("relationship", outcome.name()));
    }

    private ResponseEntity<Map<String, Object>> authorizePanTrust(PanTrustRequest request,
            HttpServletRequest servletRequest, boolean reEnroll) {
        var outcome = panTrustService.enroll(actingUser(servletRequest), request.managementAddress(),
                request.managementPort(), request.fingerprint(), request.observedAt(),
                request.explicitlyConfirmed(), reEnroll);
        return ResponseEntity.status(outcome == com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointPanCertTrustService.Outcome.MATCH
                ? HttpStatus.OK : HttpStatus.CONFLICT).body(Map.of("relationship", outcome.name()));
    }

    @PostMapping("/discovery/runs")
    public ResponseEntity<Map<String, Object>> start(@RequestBody StartRequest request,
            HttpServletRequest servletRequest) {
        String actorFingerprint = actingUser(servletRequest);
        DiscoveryRunService.StartOutcome outcome = discoveryRunService.start(actorFingerprint,
                request.managementAddress(), request.vendor(), request.credentialReferenceId());
        return switch (outcome) {
            case DiscoveryRunService.StartOutcome.Admitted admitted -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("run_id", admitted.runId());
                body.put("job_id", admitted.jobId());
                yield ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
            }
            case DiscoveryRunService.StartOutcome.ValidationFailed failed -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "VALIDATION_FAILED");
                body.put("reason_code", failed.reasonCode());
                yield ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
            }
            case DiscoveryRunService.StartOutcome.AdmissionRefused refused -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "ADMISSION_REFUSED");
                body.put("code", refused.code());
                body.put("reason", refused.reason());
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
        };
    }

    @GetMapping("/discovery/runs/{runId}")
    public ResponseEntity<Map<String, Object>> read(@PathVariable String runId, HttpServletRequest servletRequest) {
        String actorFingerprint = actingUser(servletRequest);
        DiscoveryRunService.ReadOutcome outcome = discoveryRunService.read(runId, actorFingerprint);
        if (outcome instanceof DiscoveryRunService.ReadOutcome.NotFound) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "NOT_FOUND");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        DiscoveryRunService.ReadOutcome.Found found = (DiscoveryRunService.ReadOutcome.Found) outcome;
        return ResponseEntity.ok(toRunBody(found.run(), found.candidates(), found.registryStateByCandidateId()));
    }

    @PostMapping("/discovery/runs/{runId}/import")
    public ResponseEntity<Map<String, Object>> importSelection(@PathVariable String runId,
            @RequestBody ImportRequest request, HttpServletRequest servletRequest) {
        String actorFingerprint = actingUser(servletRequest);
        List<String> candidateIds = request.candidateIds() == null ? List.of() : request.candidateIds();
        Optional<String> credentialOverride = Optional.ofNullable(request.credentialReferenceId());
        DiscoveryRunService.ImportOutcome outcome =
                discoveryRunService.importSelection(actorFingerprint, runId, candidateIds, credentialOverride,
                        Optional.ofNullable(request.exportPassphraseCredentialReferenceId()));
        return switch (outcome) {
            case DiscoveryRunService.ImportOutcome.Results results -> {
                // outcome and reason codes per candidate, never names or addresses
                java.util.logging.Logger.getLogger(DiscoveryController.class.getName()).info("[DISCOVERY_IMPORT] "
                        + results.results().stream().map(r -> r.outcome() + r.reason().map(x -> ":" + x).orElse(""))
                                .collect(java.util.stream.Collectors.groupingBy(x -> x, java.util.TreeMap::new, java.util.stream.Collectors.counting())));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("results", results.results().stream().map(DiscoveryController::toResultBody).toList());
                yield ResponseEntity.ok(body);
            }
            case DiscoveryRunService.ImportOutcome.RunNotFound notFound -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "NOT_FOUND");
                yield ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
            }
            case DiscoveryRunService.ImportOutcome.RunNotFinished notFinished -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "RUN_NOT_FINISHED");
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
            case DiscoveryRunService.ImportOutcome.CredentialNotFound credentialNotFound -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "VALIDATION_FAILED");
                body.put("reason_code", DiscoveryRunService.REASON_CREDENTIAL_REFERENCE_NOT_FOUND);
                yield ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
            }
        };
    }

    private static String actingUser(HttpServletRequest servletRequest) {
        return (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }

    private static Map<String, Object> toRunBody(DiscoveryRun run, List<DiscoveryCandidateRecord> candidates,
            Map<String, DiscoveryRunService.CandidateRegistryState> registryStateByCandidateId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", run.runId());
        body.put("vendor", run.vendor());
        body.put("state", run.state().name());
        body.put("job_id", run.jobId().orElse(null));
        body.put("outcome_summary", run.outcomeSummary().orElse(Map.of()));
        body.put("candidates", candidates.stream()
                .map(candidate -> toCandidateBody(candidate, registryStateByCandidateId.get(candidate.candidateId())))
                .toList());
        return body;
    }

    /**
     * {@code registry_state}/{@code existing_device_id} are the read-time
     * RD-5 projection (NXS-LOCAL-0173 AC-1) -- what the device registry says
     * right now. {@code import_outcome} stays exactly what it always was:
     * {@code null} until an import runs, then what that import actually did.
     * The two are never conflated.
     */
    private static Map<String, Object> toCandidateBody(DiscoveryCandidateRecord candidate,
            DiscoveryRunService.CandidateRegistryState registryState) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("candidate_id", candidate.candidateId());
        body.put("kind", candidate.kind());
        body.put("importable", candidate.importable());
        body.put("display_name", candidate.displayName());
        body.put("own_address", candidate.ownAddress().orElse(null));
        body.put("management_address", candidate.managementAddress().orElse(null));
        body.put("cluster_reference", candidate.clusterReference().orElse(null));
        body.put("parent_candidate_id", candidate.parentCandidateId().orElse(null));
        body.put("model", candidate.model().orElse(null));
        body.put("software_version", candidate.softwareVersion().orElse(null));
        body.put("connection_state", candidate.connectionState().orElse(null));
        body.put("import_outcome", candidate.importOutcome().orElse(null));
        body.put("registry_state", registryState.state());
        body.put("existing_device_id", registryState.existingDeviceId().orElse(null));
        return body;
    }

    private static Map<String, Object> toResultBody(DiscoveryRunService.CandidateImportResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("candidate_id", result.candidateId());
        body.put("outcome", result.outcome());
        body.put("device_id", result.deviceId().orElse(null));
        body.put("job_id", result.jobId().orElse(null));
        body.put("reason", result.reason().orElse(null));
        return body;
    }
}
