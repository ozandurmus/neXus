package com.securityexpert.nexus.ui2.worker.supportbundle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.HcpCopySource;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.SupportBundleRunStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;

/** Doubles shared by the Palo Alto, Cisco ASA and FortiGate support bundle executor tests (synthetic values only). */
final class BundleHarness {
    static final Instant NOW = Instant.parse("2026-11-16T01:00:00Z");
    static final UUID REQUEST = UUID.fromString("22222222-2222-4222-8222-222222222222");

    static final class Store implements SupportBundleRunStore {
        final RequestRow request;
        final List<ItemRow> items = new ArrayList<>();
        final Map<UUID, String> states = new HashMap<>(), codes = new HashMap<>(), details = new HashMap<>(), entryNames = new HashMap<>();
        final Map<UUID, BackupArtefactManifestRecord> manifests = new HashMap<>();
        final Map<UUID, List<String>> gates = new HashMap<>();
        String requestState, precheck;
        Instant expires;
        boolean queued = true;

        Store(String vendor, String scope, String... kinds) {
            request = new RequestRow(REQUEST, "device-1", vendor, scope, "NOW", NOW, null, "TAC case preparation", "ACTOR-ALPHA", NOW,
                    null, null, NOW.plus(Duration.ofDays(14)), null, null, 0, null, null, "job-1", "{}", "QUEUED", null);
            int ordinal = 1;
            for (String kind : kinds) {
                items.add(new ItemRow(UUID.randomUUID(), REQUEST, ordinal++, kind, null, "QUEUED", null, null, null, null, null, null,
                        "none", List.of(), null, null));
            }
        }
        UUID id(String kind) { return items.stream().filter(i -> i.kind().equals(kind)).findFirst().orElseThrow().id(); }
        String state(String kind) { return states.get(id(kind)); }
        String code(String kind) { return codes.get(id(kind)); }
        @Override public Optional<RequestRow> findByJob(String jobId) { return "job-1".equals(jobId) ? Optional.of(request) : Optional.empty(); }
        @Override public List<ItemRow> items(UUID requestId) { return items; }
        @Override public boolean markRunning(UUID requestId) { return queued; }
        @Override public void markItemRunning(UUID itemId) { states.put(itemId, "RUNNING"); }
        @Override public void finishItem(UUID itemId, String state, String code, String detail, List<String> gateIds) {
            states.put(itemId, state); codes.put(itemId, code); details.put(itemId, detail); gates.put(itemId, gateIds);
        }
        @Override public void completeItem(UUID itemId, BackupArtefactManifestRecord manifest, String state, String code,
                String entry, String ruleset, List<String> gateIds) {
            states.put(itemId, state); codes.put(itemId, code); manifests.put(itemId, manifest); entryNames.put(itemId, entry);
            gates.put(itemId, gateIds);
        }
        @Override public void finishRequest(UUID requestId, String state, String outcome, String precheckJson, Instant expiresAt) {
            requestState = state; precheck = precheckJson; expires = expiresAt;
        }
        @Override public Optional<HcpCopySource> newestStoredHcpReport(String deviceId, Instant notBefore) { return Optional.empty(); }
    }

    final JobLeaseRepository leases = mock(JobLeaseRepository.class);
    final JobStepAttemptRepository attempts = mock(JobStepAttemptRepository.class);
    final DeviceRepository devices = mock(DeviceRepository.class);
    final FileArtefactStore artefacts;
    final List<String> gateIds = new ArrayList<>();
    final List<String> classes = new ArrayList<>();
    List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(BundleHarness.class.getClassLoader()
            .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    String finalState, finalReason;

    BundleHarness(Path dir, String vendor, String role, String endpointKind, String summaryVersion) {
        artefacts = new FileArtefactStore(dir, ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
        when(devices.find("device-1")).thenReturn(Optional.of(new DeviceRecord("device-1", role, vendor, "manual",
                Instant.now(), false, DeviceEnrollmentState.ENROLLED, false, "credential-ref")));
        when(devices.findEndpointByDeviceId("device-1")).thenReturn(Optional.of(
                new EndpointRecord("endpoint-1", "device-1", endpointKind, "192.0.2.1", Instant.now())));
        when(devices.findSummary("device-1")).thenReturn(Optional.empty());
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString(), any())).thenAnswer(call -> {
            if (call.getArgument(2) == JobState.EXECUTING) { finalState = call.getArgument(3).toString(); finalReason = call.getArgument(6); }
            else if (call.getArgument(3) == JobState.REJECTED) { finalState = "REJECTED"; finalReason = call.getArgument(6); }
            return true;
        });
        when(attempts.findByJobAndStep(anyString(), anyInt())).thenReturn(List.of());
        AtomicInteger ids = new AtomicInteger();
        when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()))
                .thenAnswer(call -> { gateIds.add(call.getArgument(3)); classes.add(call.getArgument(4)); return "attempt-" + ids.incrementAndGet(); });
        when(attempts.markBoundaryCrossed(anyString(), anyLong())).thenReturn(true);
        when(attempts.writeOutcome(anyString(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);
    }

    GateRegistryPort gates() { return key -> rows.stream().filter(r -> r.key().equals(key)).toList(); }

    static HostnameFingerprint fingerprint() {
        return HostnameFingerprint.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
    }

    static Clock clock() {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return NOW; }
        };
    }

    byte[] read(BackupArtefactManifestRecord m) throws IOException {
        try (var in = artefacts.retrieve(new ArtefactRef(m.recoveryVolumePath()), m.wrappedDataKey(), false)) { return in.readAllBytes(); }
    }
}
