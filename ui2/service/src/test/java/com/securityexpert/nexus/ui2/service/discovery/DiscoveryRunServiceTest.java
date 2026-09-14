package com.securityexpert.nexus.ui2.service.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.admission.ConfirmCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.DiscoveryCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.jobs.discovery.PersistenceDiscoveryRunReadPort;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRecord;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDiscoveryMatch;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDiscoveryMatchRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRun;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRunRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRunState;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.device.DeviceAddSingleService;
import com.securityexpert.nexus.ui2.service.device.DeviceRegistrationService;

/**
 * AC-1 (start), AC-3 (import: new / already_imported / conflicting /
 * refused-per-row, RD-1 cluster expansion), over fakes -- no database, no
 * worker. AC-2's own worker-fake-run half is {@code worker}'s own {@code
 * DiscoveryJobExecutorEndToEndTest}; this suite covers the service's own
 * read of whatever the repository already holds.
 */
class DiscoveryRunServiceTest {

    private static final String ACTOR = "actor-onboarding-1";
    private static final String CREDENTIAL_REF = "cred-ref-1";
    private static final String MANAGEMENT_ADDRESS = "10.0.0.9";

    private static final class DirectTransactionBoundary implements TransactionBoundary {
        @Override
        public <T> T inTransaction(Function<DSLContext, T> work) {
            return work.apply(null);
        }
    }

    private static final class FakeDiscoveryRunRepository implements DiscoveryRunRepository {
        final Map<String, DiscoveryRun> runs = new HashMap<>();
        final Map<String, List<DiscoveryCandidateRecord>> candidatesByRun = new HashMap<>();
        final Map<String, String> importOutcomes = new HashMap<>();

        @Override
        public void createRun(String runId, String vendor, String managementAddress, String credentialReferenceId,
                String requestedByActorFingerprint, String actorFingerprint, String actionId) {
            runs.put(runId, new DiscoveryRun(runId, vendor, managementAddress, credentialReferenceId,
                    requestedByActorFingerprint, DiscoveryRunState.REQUESTED, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty()));
        }

        @Override
        public boolean setJobId(String runId, String jobId, String actorFingerprint, String actionId) {
            DiscoveryRun run = runs.get(runId);
            if (run == null || run.jobId().isPresent()) {
                return false;
            }
            runs.put(runId, new DiscoveryRun(run.runId(), run.vendor(), run.managementAddress(),
                    run.credentialReferenceId(), run.requestedByActorFingerprint(), run.state(), Optional.of(jobId),
                    run.startedAt(), run.finishedAt(), run.outcomeSummary()));
            return true;
        }

        @Override
        public Optional<DiscoveryRun> findRun(String runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public boolean markRunning(String runId, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean markFinished(String runId, Map<String, Integer> outcomeSummary, String actorFingerprint,
                String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean markFailed(String runId, String reasonClass, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void replaceCandidates(String runId, List<DiscoveryCandidateRecord> candidates,
                String actorFingerprint, String actionId) {
            candidatesByRun.put(runId, new ArrayList<>(candidates));
        }

        @Override
        public List<DiscoveryCandidateRecord> listCandidates(String runId) {
            return candidatesByRun.getOrDefault(runId, List.of());
        }

        @Override
        public Optional<DiscoveryCandidateRecord> findCandidate(String candidateId) {
            return candidatesByRun.values().stream().flatMap(List::stream)
                    .filter(c -> c.candidateId().equals(candidateId)).findFirst();
        }

        @Override
        public boolean markImportOutcome(String candidateId, String importOutcome, String actorFingerprint,
                String actionId) {
            importOutcomes.put(candidateId, importOutcome);
            return true;
        }

        @Override
        public int sweepExpired(Instant now, String actorFingerprint, String actionId) {
            return 0;
        }

        void seedFinishedRun(String runId, String vendor, List<DiscoveryCandidateRecord> candidates) {
            runs.put(runId, new DiscoveryRun(runId, vendor, MANAGEMENT_ADDRESS, CREDENTIAL_REF, ACTOR,
                    DiscoveryRunState.FINISHED, Optional.of("job-" + runId), Optional.of(Instant.now()),
                    Optional.of(Instant.now()), Optional.of(Map.of())));
            candidatesByRun.put(runId, candidates);
        }
    }

    private static final class FakeDeviceDiscoveryMatchRepository implements DeviceDiscoveryMatchRepository {
        final Map<String, DeviceDiscoveryMatch> byMatchKey = new HashMap<>();

        @Override
        public Optional<DeviceDiscoveryMatch> findByDiscoveryMatchKey(String discoveryMatchKey) {
            return Optional.ofNullable(byMatchKey.get(discoveryMatchKey));
        }
    }

    private static final class FakeCredentialReferenceRepository implements CredentialReferenceRepository {
        @Override
        public boolean exists(String credentialReferenceId) {
            return CREDENTIAL_REF.equals(credentialReferenceId);
        }

        @Override
        public Optional<CredentialReferenceRecord> find(String credentialReferenceId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class FakeDeviceRepository implements DeviceRepository {
        final List<DeviceDraft> registered = new ArrayList<>();
        final Map<String, DeviceRecord> byId = new HashMap<>();

        @Override
        public Optional<DeviceRecord> find(String deviceId) {
            return Optional.ofNullable(byId.get(deviceId));
        }

        @Override
        public Optional<EndpointRecord> findEndpoint(String endpointId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<EndpointRecord> findEndpointByDeviceId(String deviceId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId) {
            registered.add(draft);
            byId.put(draft.deviceId(), new DeviceRecord(draft.deviceId(), draft.vendorHint(),
                    draft.registrationSource(), Instant.now(), draft.isTestTarget(), DeviceEnrollmentState.DRAFT,
                    false, draft.credentialReferenceId()));
            return draft.deviceId();
        }

        @Override
        public boolean transitionEnrollmentState(String deviceId, DeviceEnrollmentState fromState,
                DeviceEnrollmentState toState, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean withdrawDraft(String deviceId, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean setDisabled(String deviceId, boolean disabled, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean recordConfirmSuccess(String deviceId, DeviceConfirmFacts facts, String actorFingerprint,
                String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<DeviceConfirmFacts> findConfirmFacts(String deviceId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public List<DeviceSummaryRecord> listAll() {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class InMemoryJobAdmissionRepository implements JobAdmissionRepository {
        final Map<String, String> jobsByIdempotencyKey = new HashMap<>();

        @Override
        public Optional<String> createRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
                String targetDeviceId, String actionClassId, String actorFingerprint, String actionId) {
            if (jobsByIdempotencyKey.containsKey(idempotencyKey)) {
                return Optional.empty();
            }
            jobsByIdempotencyKey.put(idempotencyKey, jobId);
            return Optional.of(jobId);
        }

        @Override
        public Optional<String> createRequestedIfAbsentForRun(String jobId, String idempotencyKey,
                String capabilityId, String targetRunId, String actionClassId, String actorFingerprint,
                String actionId) {
            if (jobsByIdempotencyKey.containsKey(idempotencyKey)) {
                return Optional.empty();
            }
            jobsByIdempotencyKey.put(idempotencyKey, jobId);
            return Optional.of(jobId);
        }

        @Override
        public Optional<String> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(jobsByIdempotencyKey.get(idempotencyKey));
        }
    }

    private static Capability gateFreeCapability(String capabilityId, String vendor, TransportKind transportKind) {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilitySpec spec = new CapabilitySpec(capabilityId, vendor, "role", transportKind, MaturityState.CAP_OFFLINE,
                List.of(connect), List.of(disconnect), "UNKNOWN", List.of(), false);
        return new CapabilityRegistryLoader(key -> List.of()).load(spec);
    }

    private static CapabilityRegistry capabilityRegistry() {
        return CapabilityRegistry.of(List.of(
                gateFreeCapability(ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT, "check_point", TransportKind.SSH_EXEC),
                gateFreeCapability(ConfirmCapabilityIds.DEVICE_CONFIRM_PALO_ALTO, "palo_alto", TransportKind.PAN_XML_API),
                gateFreeCapability(DiscoveryCapabilityIds.CP_DISCOVERY_ENUMERATE, "check_point", TransportKind.SSH_EXEC),
                gateFreeCapability(DiscoveryCapabilityIds.PAN_DISCOVERY_ENUMERATE, "palo_alto", TransportKind.PAN_XML_API)));
    }

    private static DeviceEnrollmentReadPort readPortOver(FakeDeviceRepository devices) {
        return deviceId -> Optional.ofNullable(devices.byId.get(deviceId))
                .map(d -> new DeviceEnrollmentSnapshot(d.deviceId(), d.enrollmentState(), d.disabled()));
    }

    private record Fixture(DiscoveryRunService service, FakeDiscoveryRunRepository runs, FakeDeviceRepository devices,
            FakeDeviceDiscoveryMatchRepository matches) {
    }

    private static Fixture fixture() {
        FakeDiscoveryRunRepository runs = new FakeDiscoveryRunRepository();
        FakeDeviceRepository devices = new FakeDeviceRepository();
        FakeDeviceDiscoveryMatchRepository matches = new FakeDeviceDiscoveryMatchRepository();
        FakeCredentialReferenceRepository credentials = new FakeCredentialReferenceRepository();
        JobAdmissionService jobAdmissionService = new JobAdmissionService(capabilityRegistry(), readPortOver(devices),
                new PersistenceDiscoveryRunReadPort(runs), new InMemoryJobAdmissionRepository());
        DeviceRegistrationService registrationService = new DeviceRegistrationService(devices, credentials);
        DeviceAddSingleService addSingleService =
                new DeviceAddSingleService(new DirectTransactionBoundary(), registrationService, jobAdmissionService);
        DiscoveryRunService service = new DiscoveryRunService(new DirectTransactionBoundary(), runs,
                jobAdmissionService, credentials, addSingleService, matches);
        return new Fixture(service, runs, devices, matches);
    }

    private static DiscoveryCandidateRecord candidate(String runId, String candidateId, String vendor,
            String stableIdentifier, String kind, boolean importable, Optional<String> clusterReference,
            Optional<String> parentCandidateId, String address) {
        return new DiscoveryCandidateRecord(candidateId, runId, vendor, stableIdentifier, Optional.of("global"),
                kind, "display-" + candidateId, Optional.of(address), Optional.of(address), clusterReference,
                parentCandidateId, Optional.empty(), Optional.empty(), Optional.empty(), importable, Optional.empty());
    }

    @Test
    void startCreatesRunAndAdmitsJobInOneTransaction() {
        Fixture fx = fixture();

        DiscoveryRunService.StartOutcome outcome =
                fx.service.start(ACTOR, MANAGEMENT_ADDRESS, "check_point", CREDENTIAL_REF);

        assertTrue(outcome instanceof DiscoveryRunService.StartOutcome.Admitted, "expected Admitted, got " + outcome);
        DiscoveryRunService.StartOutcome.Admitted admitted = (DiscoveryRunService.StartOutcome.Admitted) outcome;
        DiscoveryRun run = fx.runs.findRun(admitted.runId()).orElseThrow();
        assertEquals(DiscoveryRunState.REQUESTED, run.state());
        assertEquals(Optional.of(admitted.jobId()), run.jobId());
    }

    @Test
    void startWithUnknownVendorIsValidationFailed() {
        Fixture fx = fixture();

        DiscoveryRunService.StartOutcome outcome = fx.service.start(ACTOR, MANAGEMENT_ADDRESS, "cisco", CREDENTIAL_REF);

        assertTrue(outcome instanceof DiscoveryRunService.StartOutcome.ValidationFailed, "got " + outcome);
        assertTrue(fx.runs.runs.isEmpty(), "no run row for an unknown vendor");
    }

    @Test
    void readReturnsNotFoundForUnknownRun() {
        Fixture fx = fixture();

        DiscoveryRunService.ReadOutcome outcome = fx.service.read("no-such-run", ACTOR);

        assertTrue(outcome instanceof DiscoveryRunService.ReadOutcome.NotFound, "got " + outcome);
    }

    @Test
    void readReturnsTheFinishedRunAndItsCandidates() {
        Fixture fx = fixture();
        fx.runs.seedFinishedRun("run-1", "check_point", List.of(
                candidate("run-1", "cand-1", "check_point", "uid-1", "STANDALONE_PRODUCT_GATEWAY", true,
                        Optional.empty(), Optional.empty(), "10.1.1.1")));

        DiscoveryRunService.ReadOutcome outcome = fx.service.read("run-1", ACTOR);

        assertTrue(outcome instanceof DiscoveryRunService.ReadOutcome.Found, "got " + outcome);
        DiscoveryRunService.ReadOutcome.Found found = (DiscoveryRunService.ReadOutcome.Found) outcome;
        assertEquals(DiscoveryRunState.FINISHED, found.run().state());
        assertEquals(1, found.candidates().size());
    }

    @Test
    void importOfANewCandidateCreatesADraftDeviceWithDiscoveryImportSource() {
        Fixture fx = fixture();
        DiscoveryCandidateRecord gateway = candidate("run-1", "cand-1", "check_point", "uid-1",
                "STANDALONE_PRODUCT_GATEWAY", true, Optional.empty(), Optional.empty(), "10.1.1.1");
        fx.runs.seedFinishedRun("run-1", "check_point", List.of(gateway));

        DiscoveryRunService.ImportOutcome outcome =
                fx.service.importSelection(ACTOR, "run-1", List.of("cand-1"), Optional.empty());

        assertTrue(outcome instanceof DiscoveryRunService.ImportOutcome.Results, "got " + outcome);
        List<DiscoveryRunService.CandidateImportResult> results =
                ((DiscoveryRunService.ImportOutcome.Results) outcome).results();
        assertEquals(1, results.size());
        assertEquals("new", results.get(0).outcome());
        assertTrue(results.get(0).deviceId().isPresent());
        assertEquals(1, fx.devices.registered.size());
        assertEquals("discovery_import", fx.devices.registered.get(0).registrationSource());
        assertEquals(Optional.of("check_point|global|uid-1"), fx.devices.registered.get(0).discoveryMatchKey());
    }

    @Test
    void importOfAnAlreadyImportedCandidateWritesNoNewDevice() {
        Fixture fx = fixture();
        DiscoveryCandidateRecord gateway = candidate("run-1", "cand-1", "check_point", "uid-1",
                "STANDALONE_PRODUCT_GATEWAY", true, Optional.empty(), Optional.empty(), "10.1.1.1");
        fx.runs.seedFinishedRun("run-1", "check_point", List.of(gateway));
        fx.matches.byMatchKey.put("check_point|global|uid-1",
                new DeviceDiscoveryMatch("dev-existing", "10.1.1.1", Optional.empty(), Optional.empty()));

        DiscoveryRunService.ImportOutcome outcome =
                fx.service.importSelection(ACTOR, "run-1", List.of("cand-1"), Optional.empty());

        List<DiscoveryRunService.CandidateImportResult> results =
                ((DiscoveryRunService.ImportOutcome.Results) outcome).results();
        assertEquals("already_imported", results.get(0).outcome());
        assertEquals(Optional.of("dev-existing"), results.get(0).deviceId());
        assertTrue(fx.devices.registered.isEmpty(), "already_imported performs no write (RD-5)");
    }

    @Test
    void importOfAConflictingCandidateIsReportedNeverAutoResolved() {
        Fixture fx = fixture();
        DiscoveryCandidateRecord gateway = candidate("run-1", "cand-1", "check_point", "uid-1",
                "STANDALONE_PRODUCT_GATEWAY", true, Optional.empty(), Optional.empty(), "10.1.1.1");
        fx.runs.seedFinishedRun("run-1", "check_point", List.of(gateway));
        // Same match key, different recorded address -- RD-5 conflicting.
        fx.matches.byMatchKey.put("check_point|global|uid-1",
                new DeviceDiscoveryMatch("dev-existing", "10.9.9.9", Optional.empty(), Optional.empty()));

        DiscoveryRunService.ImportOutcome outcome =
                fx.service.importSelection(ACTOR, "run-1", List.of("cand-1"), Optional.empty());

        List<DiscoveryRunService.CandidateImportResult> results =
                ((DiscoveryRunService.ImportOutcome.Results) outcome).results();
        assertEquals("conflicting", results.get(0).outcome());
        assertTrue(fx.devices.registered.isEmpty());
    }

    @Test
    void importingANonImportableCandidateIsRefusedPerRowNotPerRequest() {
        Fixture fx = fixture();
        DiscoveryCandidateRecord gateway = candidate("run-1", "cand-1", "check_point", "uid-1",
                "STANDALONE_PRODUCT_GATEWAY", true, Optional.empty(), Optional.empty(), "10.1.1.1");
        DiscoveryCandidateRecord unclassified = candidate("run-1", "cand-2", "check_point", "uid-2", "UNCLASSIFIED",
                false, Optional.empty(), Optional.empty(), "10.1.1.2");
        fx.runs.seedFinishedRun("run-1", "check_point", List.of(gateway, unclassified));

        DiscoveryRunService.ImportOutcome outcome =
                fx.service.importSelection(ACTOR, "run-1", List.of("cand-1", "cand-2"), Optional.empty());

        List<DiscoveryRunService.CandidateImportResult> results =
                ((DiscoveryRunService.ImportOutcome.Results) outcome).results();
        Map<String, DiscoveryRunService.CandidateImportResult> byId = new LinkedHashMap<>();
        results.forEach(r -> byId.put(r.candidateId(), r));
        assertEquals("new", byId.get("cand-1").outcome(), "the sibling refusal must not affect this row");
        assertEquals("refused", byId.get("cand-2").outcome());
        assertEquals(1, fx.devices.registered.size());
    }

    @Test
    void selectingAClusterExpandsToItsMembers() {
        Fixture fx = fixture();
        DiscoveryCandidateRecord cluster = candidate("run-1", "cand-cluster", "check_point", "uid-cluster",
                "VIRTUALIZATION_CLUSTER", false, Optional.empty(), Optional.empty(), "10.1.1.100");
        DiscoveryCandidateRecord member1 = candidate("run-1", "cand-m1", "check_point", "uid-m1",
                "PHYSICAL_VIRTUALIZATION_CHASSIS_MEMBER", true, Optional.of("cluster-ref-1"),
                Optional.of("cand-cluster"), "10.1.1.1");
        DiscoveryCandidateRecord member2 = candidate("run-1", "cand-m2", "check_point", "uid-m2",
                "PHYSICAL_VIRTUALIZATION_CHASSIS_MEMBER", true, Optional.of("cluster-ref-1"),
                Optional.of("cand-cluster"), "10.1.1.2");
        fx.runs.seedFinishedRun("run-1", "check_point", List.of(cluster, member1, member2));

        DiscoveryRunService.ImportOutcome outcome =
                fx.service.importSelection(ACTOR, "run-1", List.of("cand-cluster"), Optional.empty());

        List<DiscoveryRunService.CandidateImportResult> results =
                ((DiscoveryRunService.ImportOutcome.Results) outcome).results();
        assertEquals(2, results.size(), "the cluster's own row is never imported, only its members");
        assertTrue(results.stream().allMatch(r -> "new".equals(r.outcome())));
        assertEquals(2, fx.devices.registered.size());
        assertTrue(fx.devices.registered.stream()
                .allMatch(d -> d.clusterMemberRef().equals(Optional.of("cluster-ref-1"))));
    }

    @Test
    void importOnANotYetFinishedRunIs409() {
        Fixture fx = fixture();
        fx.runs.createRun("run-1", "check_point", MANAGEMENT_ADDRESS, CREDENTIAL_REF, ACTOR, ACTOR, "test");

        DiscoveryRunService.ImportOutcome outcome =
                fx.service.importSelection(ACTOR, "run-1", List.of("cand-1"), Optional.empty());

        assertTrue(outcome instanceof DiscoveryRunService.ImportOutcome.RunNotFinished, "got " + outcome);
    }
}
