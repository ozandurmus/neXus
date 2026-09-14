package com.securityexpert.nexus.ui2.worker.configuration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ChangeState;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationReadKind;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationRun;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.worker.configuration.cp.CheckPointGaiaConfigProcessor;
import com.securityexpert.nexus.ui2.worker.configuration.pan.GeneratedPanConfigInputStream;
import com.securityexpert.nexus.ui2.worker.configuration.pan.PaloAltoConfigStreamProcessor;
import com.securityexpert.nexus.ui2.worker.configuration.pan.PanoramaCrossCheckPort;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;

/**
 * <b>AC-1, the decisive test.</b> An admitted {@code configuration_collect}
 * job is claimed and, over a fake transport and a real {@link
 * FileArtefactStore} (a temp directory, never a real device or a shared
 * artefact root), records exactly what 14G CG-3/CG-6 describe -- for Check
 * Point's one {@code show_configuration} run, and separately for Palo
 * Alto's three read kinds sharing one job id. Mirrors {@code
 * worker.inventory.InventoryJobExecutorEndToEndTest}'s own shape; proves
 * the executor's wiring against the already-unit-tested processors
 * ({@code CheckPointGaiaConfigProcessorTest}, {@code
 * PaloAltoConfigStreamProcessorTest}), never a second implementation of
 * either.
 */
class ConfigurationJobExecutorEndToEndTest {

    private static final String JOB_ID = "job-configuration-1";
    private static final long LEASE_EPOCH = 7L;
    private static final String DEVICE_ID = "device-enrolled-cfg-1";

    private static ArtefactStore newArtefactStore(Path tempDir) {
        String base64Key = Base64.getEncoder().encodeToString(new byte[32]);
        return new FileArtefactStore(tempDir, ArtefactStoreCipher.fromBase64Key(base64Key));
    }

    /** A few hundred {@code set} lines behind a {@code #} header, five of them secret-bearing (14G CG-3). */
    private static String checkPointRawConfiguration() {
        StringBuilder text = new StringBuilder();
        text.append("#\n# Configuration of gw-a\n# Language version: 20.0\n#\n");
        for (int i = 0; i < 295; i++) {
            text.append("set arp static-arp ip-address 10.0.").append(i / 256).append('.').append(i % 256)
                    .append(" hw-address 00:11:22:33:44:").append(String.format("%02x", i % 256)).append('\n');
        }
        for (int i = 0; i < 5; i++) {
            text.append("set user admin").append(i).append(" password-hash abcdef").append(i).append('\n');
        }
        return text.toString();
    }

    @Test
    void checkPointRecordsOneRunWithTheCanonicalHashWithheldCountSectionIndexAndBothArtefacts(@TempDir Path tempDir)
            throws Exception {
        String rawConfig = checkPointRawConfiguration();
        CheckPointGaiaConfigProcessor.Processed expected = CheckPointGaiaConfigProcessor.process(rawConfig);

        Map<String, String> outputByCommand = Map.of(
                ConfigurationReadPlan.CP_SHOW_HOSTNAME, "gw-a\n",
                ConfigurationReadPlan.CP_SHOW_VERSION_ALL, "Version R81.20\n",
                ConfigurationReadPlan.CP_CPSTAT_OS_HW_INFO, "Model Quantum\n",
                ConfigurationReadPlan.CP_SHOW_CONFIGURATION, rawConfig);
        ScriptedCheckPointConfigTransport transport = new ScriptedCheckPointConfigTransport(outputByCommand);

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
        ArtefactStore artefactStore = newArtefactStore(tempDir);

        ConfigurationCapabilityExecutor capabilityExecutor = new ConfigurationCapabilityExecutor(transport,
                ref -> { throw new IllegalStateException("not used"); }, artefactStore, PanoramaCrossCheckPort.NONE);
        ConfigurationJobExecutor executor = new ConfigurationJobExecutor(leaseRepo, attemptRepo, devicePort,
                deviceRepository, configurationRepository, notificationRepository, capabilityExecutor,
                new RecordingManifestRepository(), testFingerprint(), tempDir.toString());

        ConfigurationRequest request =
                ConfigurationRequest.checkPoint(new ConnectionTarget("ep-1", "gw-a-host", 22), "cred-1", "trust-1");

        JobOutcome outcome = executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request, false);

        assertTrue(outcome instanceof JobOutcome.Completed, "expected Completed, got " + outcome);
        assertTrue(leaseRepo.transitions.contains("CLAIMED->EXECUTING"));
        assertTrue(leaseRepo.transitions.contains("EXECUTING->COMPLETED"));
        assertTrue(configurationRepository.recordRunCalled);
        assertEquals(1, configurationRepository.recordedRuns.size(), "Check Point records exactly one run");

        ConfigurationRun run = configurationRepository.recordedRuns.get(0);
        assertEquals(DEVICE_ID, run.deviceId());
        assertEquals(JOB_ID, run.jobId());
        assertEquals("check_point", run.vendor());
        assertEquals(ConfigurationReadKind.SHOW_CONFIGURATION, run.readKind());
        assertTrue(run.primary());
        assertEquals(ChangeState.FIRST_RUN, run.changeState(), "no previous run for this device existed");

        assertEquals(expected.canonicalHash(), run.canonicalHash(), "AC-1: the canonical hash over set lines only");
        assertEquals(expected.withheldLineCount(), run.withheldLineCount());
        assertEquals(5, run.withheldLineCount(), "the five password-hash lines");
        assertEquals(expected.index(), run.index(), "AC-1: the section index");
        assertEquals(expected.sanitizedText(), run.sanitizedText().orElseThrow(), "artefact #1: the sanitized view");

        assertEquals(1, configurationRepository.recordedArtefacts.size());
        var artefactRecord = configurationRepository.recordedArtefacts.get(0);
        assertEquals(run.artefactRef(), artefactRecord.artefactRef());
        try (InputStream retrieved = artefactStore.retrieve(new ArtefactRef(artefactRecord.artefactRef()), artefactRecord.wrappedDataKey(), false)) {
            assertArrayEquals(rawConfig.getBytes(StandardCharsets.UTF_8), retrieved.readAllBytes(),
                    "artefact #2: the untouched raw bytes, round-tripped through encryption");
        }
    }

    @Test
    void paloAltoRecordsThreeRunsSharingOneJobIdWithTheCategoryIndexOverridesAndTheGzipEncryptedArtefact(
            @TempDir Path tempDir) throws Exception {
        int localOverrideCount = 5_000; // ~40 bytes/entry -> a couple MB of generated XML (still "multi-megabyte").
        int otherSourceCount = 100;

        String activeOutput = "<response><result><config><devices><entry name=\"localhost.localdomain\"/></devices>"
                + "</config></result></response>";
        String mergedOutput = "<response><result><config><devices><entry name=\"localhost.localdomain\"/></devices>"
                + "</config></result></response>";
        Map<String, String> outputByOpCmd = Map.of(
                ConfigurationReadPlan.PAN_SHOW_SYSTEM_INFO,
                "<response><result><system><serial>0011223344</serial></system></result></response>",
                ConfigurationReadPlan.PAN_MERGED, mergedOutput);
        ScriptedPaloAltoConfigTransport transport = new ScriptedPaloAltoConfigTransport(outputByOpCmd, activeOutput,
                () -> new GeneratedPanConfigInputStream(localOverrideCount, otherSourceCount));

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
        ArtefactStore artefactStore = newArtefactStore(tempDir);

        ConfigurationCapabilityExecutor capabilityExecutor = new ConfigurationCapabilityExecutor(transport,
                ref -> new PanCredentialMaterial("user", "pw".toCharArray()), artefactStore, PanoramaCrossCheckPort.NONE);
        ConfigurationJobExecutor executor = new ConfigurationJobExecutor(leaseRepo, attemptRepo, devicePort,
                deviceRepository, configurationRepository, notificationRepository, capabilityExecutor,
                new RecordingManifestRepository(), testFingerprint(), tempDir.toString());

        ConfigurationRequest request = ConfigurationRequest.paloAlto(new ApiTarget("ep-2", "https://fw.example"), "cred-2");

        JobOutcome outcome = executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request, false);

        assertTrue(outcome instanceof JobOutcome.Completed, "expected Completed, got " + outcome);
        assertTrue(configurationRepository.recordRunCalled);
        assertEquals(3, configurationRepository.recordedRuns.size(), "active, effective_running and merged");
        assertTrue(configurationRepository.recordedRuns.stream().allMatch(r -> r.jobId().equals(JOB_ID)),
                "AC-1: all three runs share one job id");
        assertTrue(configurationRepository.recordedRuns.stream().allMatch(r -> r.vendor().equals("palo_alto")));

        List<String> readKinds = configurationRepository.recordedRuns.stream().map(ConfigurationRun::readKind).toList();
        assertEquals(List.of(ConfigurationReadKind.ACTIVE, ConfigurationReadKind.EFFECTIVE_RUNNING,
                ConfigurationReadKind.MERGED), readKinds);

        ConfigurationRun active = configurationRepository.recordedRuns.get(0);
        ConfigurationRun effectiveRunning = configurationRepository.recordedRuns.get(1);
        ConfigurationRun merged = configurationRepository.recordedRuns.get(2);
        assertTrue(effectiveRunning.primary(), "CG-11: effective-running is the primary read kind");
        assertTrue(!active.primary() && !merged.primary(), "active and merged are supplementary");
        assertEquals(effectiveRunning.rawHash(), effectiveRunning.canonicalHash(),
                "no CG-2 header to strip for a structured XML read: canonical hash equals raw hash");

        PaloAltoConfigStreamProcessor.Processed expected =
                PaloAltoConfigStreamProcessor.process(new GeneratedPanConfigInputStream(localOverrideCount, otherSourceCount));
        assertEquals(expected.index(), effectiveRunning.index(), "AC-1: the category index per vsys");
        assertEquals(expected.overrides(), effectiveRunning.overrides(), "AC-1: the override rows for src=local elements");
        assertEquals(localOverrideCount, effectiveRunning.overrides().size());
        assertTrue(effectiveRunning.overrides().stream().allMatch(o -> o.category().equals("address")));

        var effectiveRunningArtefact = configurationRepository.recordedArtefacts.get(1);
        assertEquals("gzip", effectiveRunningArtefact.compression());
        byte[] expectedBytes = new GeneratedPanConfigInputStream(localOverrideCount, otherSourceCount).readAllBytes();
        try (InputStream retrieved = artefactStore.retrieve(new ArtefactRef(effectiveRunningArtefact.artefactRef()),
                effectiveRunningArtefact.wrappedDataKey(), true)) {
            assertArrayEquals(expectedBytes, retrieved.readAllBytes(),
                    "AC-1: the gzip+encrypted artefact round-trips the untouched streamed bytes");
        }

        assertEquals(0, active.withheldLineCount());
        assertEquals(0, effectiveRunning.withheldLineCount());
        assertEquals(0, merged.withheldLineCount());
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
