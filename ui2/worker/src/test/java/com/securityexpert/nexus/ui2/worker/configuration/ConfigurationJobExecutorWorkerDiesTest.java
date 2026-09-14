package com.securityexpert.nexus.ui2.worker.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobReconciler;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.worker.configuration.pan.PanoramaCrossCheckPort;

/**
 * <b>AC-1.</b> A worker that dies mid-contact leaves the job reconcilable
 * and records no run -- {@code configuration_collect} is a pure {@code
 * CLASS_0_READ} job, exactly like {@code
 * worker.inventory.InventoryJobExecutorWorkerDiesTest}'s own crash shape.
 */
class ConfigurationJobExecutorWorkerDiesTest {

    private static final String JOB_ID = "job-configuration-dies-1";
    private static final long LEASE_EPOCH = 3L;
    private static final String DEVICE_ID = "device-enrolled-cfg-2";

    private static final class CrashingDeviceTransport implements DeviceTransport {
        @Override
        public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            throw new RuntimeException("simulated worker process crash mid-device-contact");
        }

        @Override
        public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
            throw new UnsupportedOperationException("unreachable -- connect crashes first");
        }

        @Override
        public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void disconnect(TransportSession session) {
            throw new UnsupportedOperationException("unreachable -- connect crashes first");
        }
    }

    /** Never opened by a crashed run -- proves {@code recordRunCalled} stays false without needing a real filesystem root. */
    private static final class UnusedArtefactStore implements ArtefactStore {
        @Override
        public ArtefactHandle open(String deviceId, String jobId, String vendor, boolean gzip) {
            throw new UnsupportedOperationException("a crashed contact never opens an artefact");
        }

        @Override
        public java.io.InputStream retrieve(ArtefactRef ref, byte[] wrappedDataKey, boolean gzip) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    @Test
    void aWorkerThatDiesMidStepLeavesTheJobReconcilableAndRecordsNoRun() {
        ConfigurationJobExecutorFakes.FakeLeaseRepository leaseRepo =
                new ConfigurationJobExecutorFakes.FakeLeaseRepository(JOB_ID, LEASE_EPOCH, JobState.CLAIMED);
        ConfigurationJobExecutorFakes.FakeStepAttemptRepository attemptRepo =
                new ConfigurationJobExecutorFakes.FakeStepAttemptRepository();
        ConfigurationJobExecutorFakes.FakeDeviceEnrollmentReadPort devicePort =
                new ConfigurationJobExecutorFakes.FakeDeviceEnrollmentReadPort();
        ConfigurationJobExecutorFakes.FakeDeviceRepository deviceRepository =
                new ConfigurationJobExecutorFakes.FakeDeviceRepository();
        ConfigurationJobExecutorFakes.FakeDeviceConfigurationRepository configurationRepository =
                new ConfigurationJobExecutorFakes.FakeDeviceConfigurationRepository();
        ConfigurationJobExecutorFakes.FakeConfigurationNotificationRepository notificationRepository =
                new ConfigurationJobExecutorFakes.FakeConfigurationNotificationRepository();

        ConfigurationCapabilityExecutor capabilityExecutor = new ConfigurationCapabilityExecutor(
                new CrashingDeviceTransport(), ref -> { throw new IllegalStateException("not used by this test"); },
                new UnusedArtefactStore(), PanoramaCrossCheckPort.NONE);
        ConfigurationJobExecutor executor = new ConfigurationJobExecutor(leaseRepo, attemptRepo, devicePort,
                deviceRepository, configurationRepository, notificationRepository, capabilityExecutor,
                new RecordingManifestRepository(), testFingerprint(), System.getProperty("java.io.tmpdir"));
        ConfigurationRequest request =
                ConfigurationRequest.checkPoint(new ConnectionTarget("ep-3", "gw-c-host", 22), "cred-1", "trust-1");

        assertThrows(RuntimeException.class, () -> executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request, false));

        assertEquals(JobState.EXECUTING, leaseRepo.currentState, "CLAIMED->EXECUTING committed before contact; "
                + "no terminal transition was ever reached by the crashed run");
        assertEquals(1, attemptRepo.attempts.size(), "exactly the one pre-contact attempt row was written");
        assertTrue(attemptRepo.attempts.values().iterator().next().outcome().isEmpty(),
                "the crash happened before this attempt's outcome was ever written");
        assertFalse(attemptRepo.attempts.values().iterator().next().mutationBoundaryCrossed());
        assertFalse(configurationRepository.recordRunCalled, "no partial run is ever recorded");

        leaseRepo.expiredAllBoundaryNo = List.of(new ClaimedJob(JOB_ID, LEASE_EPOCH));
        JobReconciler reconciler = new JobReconciler(leaseRepo);

        JobReconciler.ReconciliationSummary summary = reconciler.reconcileOnce();

        assertEquals(1, summary.requeuedAllBoundaryNo());
        assertEquals(JobState.REQUESTED, leaseRepo.currentState);
        assertTrue(leaseRepo.transitions.contains("EXECUTING->REQUESTED"));
        assertFalse(configurationRepository.recordRunCalled, "still no run recorded after reconciliation");
    }

    /** NXS-LOCAL-0170 BK-16: the manifest row every artefact now carries; this test only needs it to exist. */
    private static final class RecordingManifestRepository
            implements com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository {
        int recorded;

        @Override
        public void record(com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord manifest,
                String actorFingerprint, String actionId) {
            recorded++;
        }

        @Override
        public java.util.Optional<PlaintextDigestSummary> findLatestPlaintextDigest(String deviceId,
                String artefactClass) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.List<BackupArtefactSummary> findByDevice(String deviceId, String artefactClass) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<BackupArtefactSummary> findAll(String artefactClass) {
            return java.util.List.of();
        }

        @Override
        public java.util.Optional<RetrievalManifest> findForRetrieval(String artefactId) {
            return java.util.Optional.empty();
        }
    }

    /** BK-16: the hostname is fingerprinted, never stored raw; any 32-byte key proves the shape. */
    private static com.securityexpert.nexus.ui2.platform.HostnameFingerprint testFingerprint() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        return com.securityexpert.nexus.ui2.platform.HostnameFingerprint.fromBase64Key(
                java.util.Base64.getEncoder().encodeToString(key));
    }

}
