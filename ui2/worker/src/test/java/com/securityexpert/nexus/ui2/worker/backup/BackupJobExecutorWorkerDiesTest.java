package com.securityexpert.nexus.ui2.worker.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

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
import com.securityexpert.nexus.ui2.jobs.transport.FetchStreamResult;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;

/**
 * A worker that dies mid-contact leaves the job reconcilable and records no
 * manifest row -- mirrors {@code worker.configuration.
 * ConfigurationJobExecutorWorkerDiesTest}'s own crash shape.
 */
class BackupJobExecutorWorkerDiesTest {

    private static final String JOB_ID = "job-backup-dies-1";
    private static final long LEASE_EPOCH = 3L;
    private static final String DEVICE_ID = "device-pilot-2";

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
        public FetchStreamResult fetchStreaming(TransportSession session, FetchSpec spec, Duration timeout,
                OutputStream sink) {
            throw new UnsupportedOperationException("unreachable -- connect crashes first");
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

    /** Never opened by a crashed run. */
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
    void aWorkerThatDiesMidContactLeavesTheJobReconcilableAndRecordsNoManifest() {
        BackupJobExecutorFakes.FakeLeaseRepository leaseRepo =
                new BackupJobExecutorFakes.FakeLeaseRepository(JOB_ID, LEASE_EPOCH, JobState.CLAIMED);
        BackupJobExecutorFakes.FakeStepAttemptRepository attemptRepo = new BackupJobExecutorFakes.FakeStepAttemptRepository();
        BackupJobExecutorFakes.FakeDeviceEnrollmentReadPort devicePort = new BackupJobExecutorFakes.FakeDeviceEnrollmentReadPort();
        BackupJobExecutorFakes.FakeDeviceRepository deviceRepository = new BackupJobExecutorFakes.FakeDeviceRepository();
        deviceRepository.confirmFacts = Optional.of(BackupJobExecutorFakes.confirmFactsWithSoftwareVersion("R81.20"));
        BackupJobExecutorFakes.FakeBackupArtefactManifestRepository manifestRepository =
                new BackupJobExecutorFakes.FakeBackupArtefactManifestRepository();
        BackupJobExecutorFakes.FakeBackupEndpointEligibilityRepository eligibilityRepository =
                new BackupJobExecutorFakes.FakeBackupEndpointEligibilityRepository();

        BackupCapabilityExecutor capabilityExecutor = new BackupCapabilityExecutor(new CrashingDeviceTransport(),
                new UnusedArtefactStore(), 1L, Duration.ofMillis(5), Duration.ofSeconds(5));
        BackupJobExecutor executor = new BackupJobExecutor(leaseRepo, attemptRepo, devicePort, deviceRepository,
                capabilityExecutor, manifestRepository, eligibilityRepository, testFingerprint(),
                System.getProperty("java.io.tmpdir"));
        BackupRequest request = new BackupRequest(new ConnectionTarget("ep-4", "gw-d-host", 22),
                Optional.of("cred-backup-1"), "trust-1");

        assertThrows(RuntimeException.class, () -> executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request));

        assertEquals(JobState.EXECUTING, leaseRepo.currentState, "CLAIMED->EXECUTING committed before contact; "
                + "no terminal transition was ever reached by the crashed run");
        assertEquals(1, attemptRepo.attempts.size(), "exactly the one pre-contact attempt row was written");
        assertTrue(attemptRepo.attempts.values().iterator().next().outcome().isEmpty(),
                "the crash happened before this attempt's outcome was ever written");
        assertTrue(manifestRepository.recorded.isEmpty(), "no partial manifest is ever recorded");
        assertFalse(eligibilityRepository.isIneligible(DEVICE_ID));

        leaseRepo.expiredAllBoundaryNo = List.of(new ClaimedJob(JOB_ID, LEASE_EPOCH));
        JobReconciler reconciler = new JobReconciler(leaseRepo);

        JobReconciler.ReconciliationSummary summary = reconciler.reconcileOnce();

        assertEquals(1, summary.requeuedAllBoundaryNo());
        assertEquals(JobState.REQUESTED, leaseRepo.currentState);
        assertTrue(leaseRepo.transitions.contains("EXECUTING->REQUESTED"));
        assertTrue(manifestRepository.recorded.isEmpty(), "still no manifest recorded after reconciliation");
    }

    private static com.securityexpert.nexus.ui2.platform.HostnameFingerprint testFingerprint() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        return com.securityexpert.nexus.ui2.platform.HostnameFingerprint.fromBase64Key(
                java.util.Base64.getEncoder().encodeToString(key));
    }
}
