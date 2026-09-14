package com.securityexpert.nexus.ui2.service.discovery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.DiscoveryCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDiscoveryMatch;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDiscoveryMatchRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRun;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRunRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRunState;
import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.service.device.DeviceAddSingleService;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * 14F section 2/3: the three discovery routes' own service, over the
 * {@code discovery_run} job target ({@code DiscoveryCapabilityIds}, {@link
 * JobAdmissionService#submitForRun}) and the import mapping
 * {@code DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md} §2-§3 fixes (IM-1..IM-10,
 * RD-1..RD-10). {@link #start} mirrors {@link DeviceAddSingleService}'s own
 * one-transaction register-then-admit shape, over a run row instead of a
 * device row. {@link #importSelection} calls {@link
 * DeviceAddSingleService#addFromDiscoveryImport} once per resolved
 * candidate, each in its own transaction, so one candidate's refusal never
 * rolls back a sibling's already-completed import (AC-3).
 */
public final class DiscoveryRunService {

    public sealed interface StartOutcome {
        record Admitted(String runId, String jobId) implements StartOutcome {
        }

        record ValidationFailed(String reasonCode) implements StartOutcome {
        }

        record AdmissionRefused(String code, String reason) implements StartOutcome {
        }
    }

    public sealed interface ReadOutcome {
        record Found(DiscoveryRun run, List<DiscoveryCandidateRecord> candidates) implements ReadOutcome {
        }

        record NotFound() implements ReadOutcome {
        }
    }

    /** {@code outcome} is one of {@code new}/{@code already_imported}/{@code conflicting}/{@code refused} (RD-5, AC-3). */
    public record CandidateImportResult(String candidateId, String outcome, Optional<String> deviceId,
            Optional<String> jobId, Optional<String> reason) {
    }

    public sealed interface ImportOutcome {
        record Results(List<CandidateImportResult> results) implements ImportOutcome {
        }

        record RunNotFound() implements ImportOutcome {
        }

        /** 409: import only ever acts on a {@code FINISHED} run's candidate set. */
        record RunNotFinished() implements ImportOutcome {
        }

        record CredentialNotFound() implements ImportOutcome {
        }
    }

    public static final String REASON_VENDOR_INVALID = "vendor_hint_invalid";
    public static final String REASON_ADDRESS_INVALID = "management_address_invalid";
    public static final String REASON_CREDENTIAL_REFERENCE_NOT_FOUND = "credential_reference_not_found";

    private static final Map<String, String> START_CAPABILITY_BY_VENDOR = Map.of(
            "check_point", DiscoveryCapabilityIds.CP_DISCOVERY_ENUMERATE,
            "palo_alto", DiscoveryCapabilityIds.PAN_DISCOVERY_ENUMERATE);

    /** CP-DISCOVERY K-5/K-6/K-7: a cluster object, never importable itself -- RD-1 expands a selection of it to its members. */
    private static final Set<String> CP_CLUSTER_KINDS =
            Set.of("VIRTUALIZATION_CLUSTER", "VIRTUAL_SYSTEM_CLUSTER", "PLAIN_HIGH_AVAILABILITY_CLUSTER");
    /** CP-DISCOVERY K-4/K-9: a virtual system -- IM-1's {@code virtual_system_ref} modifier kinds. */
    private static final Set<String> CP_VIRTUAL_SYSTEM_KINDS = Set.of("STANDALONE_VIRTUAL_SYSTEM", "VIRTUAL_SYSTEM_MEMBER");
    /** {@code worker.discovery.PaloAltoDiscoveryCandidateMapper.KIND_VIRTUAL_SYSTEM} -- literal here, {@code service} never depends on {@code worker} (DIR-2). */
    private static final String PAN_VIRTUAL_SYSTEM_KIND = "PALO_ALTO_VIRTUAL_SYSTEM";

    private static final class AdmissionRefusedSignal extends RuntimeException {
        private final String code;
        private final String reason;

        AdmissionRefusedSignal(String code, String reason) {
            super(code, null, false, false);
            this.code = code;
            this.reason = reason;
        }
    }

    private final TransactionBoundary transactionBoundary;
    private final DiscoveryRunRepository discoveryRunRepository;
    private final JobAdmissionService jobAdmissionService;
    private final CredentialReferenceRepository credentialReferenceRepository;
    private final DeviceAddSingleService deviceAddSingleService;
    private final DeviceDiscoveryMatchRepository deviceDiscoveryMatchRepository;

    public DiscoveryRunService(TransactionBoundary transactionBoundary, DiscoveryRunRepository discoveryRunRepository,
            JobAdmissionService jobAdmissionService, CredentialReferenceRepository credentialReferenceRepository,
            DeviceAddSingleService deviceAddSingleService, DeviceDiscoveryMatchRepository deviceDiscoveryMatchRepository) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.discoveryRunRepository = Objects.requireNonNull(discoveryRunRepository, "discoveryRunRepository");
        this.jobAdmissionService = Objects.requireNonNull(jobAdmissionService, "jobAdmissionService");
        this.credentialReferenceRepository =
                Objects.requireNonNull(credentialReferenceRepository, "credentialReferenceRepository");
        this.deviceAddSingleService = Objects.requireNonNull(deviceAddSingleService, "deviceAddSingleService");
        this.deviceDiscoveryMatchRepository =
                Objects.requireNonNull(deviceDiscoveryMatchRepository, "deviceDiscoveryMatchRepository");
    }

    /** 14F DR-1: {@code POST /discovery/runs} -- creates the run row and admits the discovery capability against it, in one transaction. */
    public StartOutcome start(String actorFingerprint, String managementAddress, String vendor,
            String credentialReferenceId) {
        String capabilityId = START_CAPABILITY_BY_VENDOR.get(vendor);
        if (capabilityId == null) {
            return new StartOutcome.ValidationFailed(REASON_VENDOR_INVALID);
        }
        if (managementAddress == null || managementAddress.isBlank() || containsWhitespace(managementAddress)) {
            return new StartOutcome.ValidationFailed(REASON_ADDRESS_INVALID);
        }
        if (credentialReferenceId == null || !credentialReferenceRepository.exists(credentialReferenceId)) {
            return new StartOutcome.ValidationFailed(REASON_CREDENTIAL_REFERENCE_NOT_FOUND);
        }

        try {
            return transactionBoundary.inTransaction(dsl -> startInTransaction(actorFingerprint, managementAddress,
                    vendor, credentialReferenceId, capabilityId));
        } catch (AdmissionRefusedSignal signal) {
            return new StartOutcome.AdmissionRefused(signal.code, signal.reason);
        }
    }

    private StartOutcome startInTransaction(String actorFingerprint, String managementAddress, String vendor,
            String credentialReferenceId, String capabilityId) {
        String runId = OpaqueId.random().value();
        discoveryRunRepository.createRun(runId, vendor, managementAddress, credentialReferenceId, actorFingerprint,
                actorFingerprint, ActionRegistry.DISCOVERY_RUN_START);

        AdmissionResult admission = jobAdmissionService.submitForRun(capabilityId, runId, runId, actorFingerprint,
                ActionRegistry.DISCOVERY_RUN_START);
        String jobId = switch (admission) {
            case AdmissionResult.Admitted admitted -> admitted.jobId();
            case AdmissionResult.Deduplicated deduplicated -> deduplicated.jobId();
            case AdmissionResult.Refused refused -> throw new AdmissionRefusedSignal(refused.code(), refused.reason());
        };
        discoveryRunRepository.setJobId(runId, jobId, actorFingerprint, ActionRegistry.DISCOVERY_RUN_START);
        return new StartOutcome.Admitted(runId, jobId);
    }

    /** 14F section 3: {@code GET /discovery/runs/{run_id}} -- sweeps expired runs first (DR-3's read-time sweep). */
    public ReadOutcome read(String runId, String actorFingerprint) {
        discoveryRunRepository.sweepExpired(Instant.now(), actorFingerprint, ActionRegistry.DISCOVERY_RUN_READ);
        Optional<DiscoveryRun> run = discoveryRunRepository.findRun(runId);
        if (run.isEmpty()) {
            return new ReadOutcome.NotFound();
        }
        return new ReadOutcome.Found(run.get(), discoveryRunRepository.listCandidates(runId));
    }

    /** 14F section 2: {@code POST /discovery/runs/{run_id}/import}. */
    public ImportOutcome importSelection(String actorFingerprint, String runId, List<String> candidateIds,
            Optional<String> credentialReferenceIdOverride) {
        Optional<DiscoveryRun> runOpt = discoveryRunRepository.findRun(runId);
        if (runOpt.isEmpty()) {
            return new ImportOutcome.RunNotFound();
        }
        DiscoveryRun run = runOpt.get();
        if (run.state() != DiscoveryRunState.FINISHED) {
            return new ImportOutcome.RunNotFinished();
        }

        String credentialReferenceId = credentialReferenceIdOverride.orElse(run.credentialReferenceId());
        if (!credentialReferenceRepository.exists(credentialReferenceId)) {
            return new ImportOutcome.CredentialNotFound();
        }

        Map<String, DiscoveryCandidateRecord> byId = discoveryRunRepository.listCandidates(runId).stream()
                .collect(Collectors.toMap(DiscoveryCandidateRecord::candidateId, c -> c));

        List<CandidateImportResult> results = new ArrayList<>();
        Set<String> alreadyProcessed = new LinkedHashSet<>();
        for (String selectedId : candidateIds) {
            DiscoveryCandidateRecord candidate = byId.get(selectedId);
            if (candidate == null) {
                results.add(new CandidateImportResult(selectedId, "refused", Optional.empty(), Optional.empty(),
                        Optional.of("candidate_not_found_in_run")));
                continue;
            }
            if (CP_CLUSTER_KINDS.contains(candidate.kind())) {
                // RD-1: "selecting a cluster selects its members" -- the cluster object itself is never importable.
                List<DiscoveryCandidateRecord> members = byId.values().stream()
                        .filter(m -> m.parentCandidateId().map(selectedId::equals).orElse(false))
                        .toList();
                if (members.isEmpty()) {
                    results.add(new CandidateImportResult(selectedId, "refused", Optional.empty(), Optional.empty(),
                            Optional.of("cluster_has_no_members_in_this_run")));
                    continue;
                }
                for (DiscoveryCandidateRecord member : members) {
                    if (alreadyProcessed.add(member.candidateId())) {
                        results.add(importOneCandidate(actorFingerprint, member, credentialReferenceId));
                    }
                }
                continue;
            }
            if (alreadyProcessed.add(candidate.candidateId())) {
                results.add(importOneCandidate(actorFingerprint, candidate, credentialReferenceId));
            }
        }
        return new ImportOutcome.Results(results);
    }

    private CandidateImportResult importOneCandidate(String actorFingerprint, DiscoveryCandidateRecord candidate,
            String credentialReferenceId) {
        if (!candidate.importable()) {
            return new CandidateImportResult(candidate.candidateId(), "refused", Optional.empty(), Optional.empty(),
                    Optional.of("not_importable_kind:" + candidate.kind()));
        }

        Optional<String> addressRef = endpointAddress(candidate);
        if (addressRef.isEmpty()) {
            return new CandidateImportResult(candidate.candidateId(), "refused", Optional.empty(), Optional.empty(),
                    Optional.of("candidate_missing_address"));
        }

        boolean isVirtualSystem = isVirtualSystemKind(candidate);
        Optional<String> virtualSystemRef = isVirtualSystem ? Optional.of(candidate.stableIdentifier()) : Optional.empty();
        Optional<String> clusterMemberRef = isVirtualSystem ? Optional.empty() : candidate.clusterReference();

        String matchKey = DiscoveryMatchKey.of(candidate);
        Optional<DeviceDiscoveryMatch> existing = deviceDiscoveryMatchRepository.findByDiscoveryMatchKey(matchKey);
        if (existing.isPresent()) {
            DeviceDiscoveryMatch match = existing.get();
            boolean matches = match.addressRef().equals(addressRef.get())
                    && match.clusterMemberRef().equals(clusterMemberRef)
                    && match.virtualSystemRef().equals(virtualSystemRef);
            String outcome = matches ? "already_imported" : "conflicting";
            discoveryRunRepository.markImportOutcome(candidate.candidateId(), outcome, actorFingerprint,
                    ActionRegistry.DISCOVERY_RUN_IMPORT);
            return new CandidateImportResult(candidate.candidateId(), outcome, Optional.of(match.deviceId()),
                    Optional.empty(), Optional.empty());
        }

        DeviceAddSingleService.Outcome outcome = deviceAddSingleService.addFromDiscoveryImport(actorFingerprint,
                addressRef.get(), candidate.vendor(), credentialReferenceId, clusterMemberRef, virtualSystemRef,
                Optional.of(matchKey), ActionRegistry.DISCOVERY_RUN_IMPORT);
        return switch (outcome) {
            case DeviceAddSingleService.Outcome.Admitted admitted -> {
                discoveryRunRepository.markImportOutcome(candidate.candidateId(), "new", actorFingerprint,
                        ActionRegistry.DISCOVERY_RUN_IMPORT);
                yield new CandidateImportResult(candidate.candidateId(), "new", Optional.of(admitted.deviceId()),
                        Optional.of(admitted.jobId()), Optional.empty());
            }
            case DeviceAddSingleService.Outcome.ValidationFailed failed -> new CandidateImportResult(
                    candidate.candidateId(), "refused", Optional.empty(), Optional.empty(),
                    Optional.of(failed.reasonCode()));
            case DeviceAddSingleService.Outcome.AdmissionRefused refused -> new CandidateImportResult(
                    candidate.candidateId(), "refused", Optional.empty(), Optional.empty(),
                    Optional.of(refused.code()));
        };
    }

    /** IM-7: Check Point keys the endpoint off the candidate's management address; Palo Alto off its own address. */
    private static Optional<String> endpointAddress(DiscoveryCandidateRecord candidate) {
        return "check_point".equals(candidate.vendor()) ? candidate.managementAddress() : candidate.ownAddress();
    }

    private static boolean isVirtualSystemKind(DiscoveryCandidateRecord candidate) {
        return CP_VIRTUAL_SYSTEM_KINDS.contains(candidate.kind()) || PAN_VIRTUAL_SYSTEM_KIND.equals(candidate.kind());
    }

    private static boolean containsWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }
}
