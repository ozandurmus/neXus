package com.securityexpert.nexus.ui2.worker.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.supportbundle.CpSupportBundle;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchStatus;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
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
import com.securityexpert.nexus.ui2.platform.TarStream;

/**
 * The {@code cp_support_bundle} executor against a scripted device (no socket, host or device contact), the real
 * encrypting artefact store and an in-memory run store. Every value is synthetic (FW-TANGO-04, 192.0.2.x).
 */
class CpSupportBundleJobExecutorTest {
    private static final Instant NOW = Instant.parse("2026-11-16T01:00:00Z");
    private static final UUID REQUEST = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String HCP_NAME = "hcp_report_fwtango04_15_11_26_03_00.tar.gz";

    // ------------------------------------------------------------------ doubles

    static final class Device implements DeviceTransport {
        final List<String> commands = new ArrayList<>();
        final List<String> fetches = new ArrayList<>();
        final Map<String, ExecResult> answers = new HashMap<>();
        byte[] hcp;
        boolean connectFails;

        @Override public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            return connectFails ? new ConnectResult.AuthenticationFailed("synthetic") : new ConnectResult.Authenticated(() -> "s1");
        }
        @Override public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
            commands.add(spec.command());
            assertTrue(spec.outputLimitBytes() > 0, "every read is bounded");
            ExecResult answer = answers.get(spec.command());
            if (answer == null) throw new AssertionError("unexpected command " + spec.command());
            return answer;
        }
        @Override public RangedFetchResult fetchBounded(TransportSession session, RangedFetchSpec spec, Duration timeout,
                OutputStream sink, String nameClass) {
            fetches.add(spec.remotePath());
            if (hcp == null) return new RangedFetchResult(RangedFetchStatus.FILE_MISSING, 0, spec.maxBytes(), 0, OptionalLong.empty(), false, "SFTP_STATUS_2");
            int n = (int) Math.min(hcp.length, spec.maxBytes());
            try { sink.write(hcp, 0, n); } catch (IOException e) { throw new AssertionError(e); }
            boolean eof = n == hcp.length && n < spec.maxBytes();
            return new RangedFetchResult(eof ? RangedFetchStatus.END_OF_FILE : RangedFetchStatus.CAP_REACHED, 0, spec.maxBytes(), n,
                    OptionalLong.of(hcp.length), eof, "");
        }
        @Override public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) { throw new AssertionError("fetch"); }
        @Override public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) { throw new AssertionError("xml"); }
        @Override public void disconnect(TransportSession session) { }
    }

    static final class Store implements SupportBundleRunStore {
        final RequestRow request = new RequestRow(REQUEST, "device-1", "check_point", "cp_gaia_gateway", "NOW", NOW, null,
                "TAC case preparation", "ACTOR-ALPHA", NOW, null, null, NOW.plus(Duration.ofDays(14)), null, null, 0, null,
                null, "job-1", "{}", "QUEUED", null);
        final List<ItemRow> items = new ArrayList<>();
        final Map<UUID, String> states = new HashMap<>();
        final Map<UUID, String> codes = new HashMap<>();
        final Map<UUID, String> details = new HashMap<>();
        final Map<UUID, BackupArtefactManifestRecord> manifests = new HashMap<>();
        final Map<UUID, String> entryNames = new HashMap<>();
        String requestState, precheck;
        Instant expires;
        boolean queued = true;
        HcpCopySource copySource;

        Store(String... kinds) {
            int ordinal = 1;
            for (String kind : kinds) {
                items.add(new ItemRow(UUID.randomUUID(), REQUEST, ordinal++, kind, null, "QUEUED", null, null, null, null, null,
                        null, "none", List.of(), null, null));
            }
        }
        UUID id(String kind) { return items.stream().filter(i -> i.kind().equals(kind)).findFirst().orElseThrow().id(); }
        @Override public Optional<RequestRow> findByJob(String jobId) { return "job-1".equals(jobId) ? Optional.of(request) : Optional.empty(); }
        @Override public List<ItemRow> items(UUID requestId) { return items; }
        @Override public boolean markRunning(UUID requestId) { return queued; }
        @Override public void markItemRunning(UUID itemId) { states.put(itemId, "RUNNING"); }
        @Override public void finishItem(UUID itemId, String state, String code, String detail, List<String> gateIds) {
            states.put(itemId, state); codes.put(itemId, code); details.put(itemId, detail);
        }
        @Override public void completeItem(UUID itemId, BackupArtefactManifestRecord manifest, String state, String code,
                String archiveEntryName, String ruleset, List<String> gateIds) {
            states.put(itemId, state); codes.put(itemId, code); manifests.put(itemId, manifest); entryNames.put(itemId, archiveEntryName);
        }
        @Override public void finishRequest(UUID requestId, String state, String outcome, String precheckJson, Instant expiresAt) {
            requestState = state; precheck = precheckJson; expires = expiresAt;
        }
        @Override public Optional<HcpCopySource> newestStoredHcpReport(String deviceId, Instant notBefore) { return Optional.ofNullable(copySource); }
    }

    final class Harness {
        final JobLeaseRepository leases = mock(JobLeaseRepository.class);
        final JobStepAttemptRepository attempts = mock(JobStepAttemptRepository.class);
        final DeviceRepository devices = mock(DeviceRepository.class);
        final Device device = new Device();
        final FileArtefactStore artefacts;
        final List<String> gateIds = new ArrayList<>();
        List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(CpSupportBundleJobExecutorTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        String finalState, finalReason;

        Harness(Path dir, String role) {
            artefacts = new FileArtefactStore(dir, ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
            when(devices.find("device-1")).thenReturn(Optional.of(new DeviceRecord("device-1", role, "check_point", "manual",
                    Instant.now(), false, DeviceEnrollmentState.ENROLLED, false, "credential-ref")));
            when(devices.findEndpointByDeviceId("device-1")).thenReturn(Optional.of(
                    new EndpointRecord("endpoint-1", "device-1", "ssh_exec", "192.0.2.1", Instant.now())));
            when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
            when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString(), any())).thenAnswer(call -> {
                if (call.getArgument(2) == JobState.EXECUTING) { finalState = call.getArgument(3).toString(); finalReason = call.getArgument(6); }
                else if (call.getArgument(3) == JobState.REJECTED) { finalState = "REJECTED"; finalReason = call.getArgument(6); }
                return true;
            });
            when(attempts.findByJobAndStep(anyString(), anyInt())).thenReturn(List.of());
            AtomicInteger ids = new AtomicInteger();
            when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()))
                    .thenAnswer(call -> { gateIds.add(call.getArgument(3)); return "attempt-" + ids.incrementAndGet(); });
            when(attempts.markBoundaryCrossed(anyString(), anyLong())).thenReturn(true);
            when(attempts.writeOutcome(anyString(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);
        }

        void run(Store store) {
            GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
            Clock clock = new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return NOW; }
            };
            new CpSupportBundleJobExecutor(leases, attempts, devices, device, gates, store, artefacts,
                    HostnameFingerprint.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])), clock)
                    .execute("job-1", 1, "device-1", "192.0.2.1", 22, "credential-ref");
        }

        byte[] read(BackupArtefactManifestRecord m) throws IOException {
            try (var in = artefacts.retrieve(new ArtefactRef(m.recoveryVolumePath()), m.wrappedDataKey(), false)) { return in.readAllBytes(); }
        }
    }

    private static ExecResult ok(String output) { return new ExecResult.Completed(output, 0); }

    private static void script(Device d) {
        d.answers.put(CpSupportBundle.Exec.SHOW_VERSION_ALL.command(), ok("Product version Check Point Gaia R82\nOS build 392\n"));
        d.answers.put(CpSupportBundle.Exec.SHOW_ASSET_ALL.command(), ok("Platform: Open Server\nSerial Number: SYNTH-0001\n"));
        d.answers.put(CpSupportBundle.Exec.CPHAPROB_STAT.command(), ok("Cluster Mode: High Availability (Active Up)\n"));
        d.answers.put(CpSupportBundle.Exec.CPU_MULTI.command(), ok(
                "|CPU#|User Time(%)|System Time(%)|Idle Time(%)|Usage(%)|\n|   1|           5|             3|           92|       8|\n|   2|          30|            10|           40|      60|\n"));
        d.answers.put(CpSupportBundle.Exec.DF_VAR_LOG.command(), ok(
                "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/sda3 41152736 8000000 31000000 21% /var/log\n"));
        d.answers.put(CpSupportBundle.Exec.CPDATA_PRESENT.command(), new ExecResult.Completed("", 1));
    }

    private static byte[] gz(byte[] raw) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) { g.write(raw); }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void gaiaExtrasStoresFiveScrubbedFilesAndRecordsThePrechecks(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        script(h.device);
        h.device.answers.put(CpSupportBundle.Exec.SHOW_VERSION_ALL.command(), ok("Product version R82\nsnmp community synthetic-secret-1\n"));
        var store = new Store("cp_gaia_extras");
        h.run(store);
        UUID item = store.id("cp_gaia_extras");
        assertEquals("COMPLETED", store.states.get(item));
        var entries = TarStream.readAll(new GZIPInputStream(new ByteArrayInputStream(h.read(store.manifests.get(item)))), 1 << 20);
        assertEquals(List.of("items/cp_gaia_extras/show-version-all.txt", "items/cp_gaia_extras/show-asset-all.txt",
                "items/cp_gaia_extras/cphaprob-stat.txt", "items/cp_gaia_extras/multi-cpu.txt", "items/cp_gaia_extras/df-var-log.txt"),
                entries.stream().map(TarStream.Entry::name).toList());
        String all = entries.stream().map(e -> new String(e.bytes(), StandardCharsets.UTF_8)).reduce("", String::concat);
        assertFalse(all.contains("synthetic-secret-1"), "secrets are scrubbed before encryption");
        assertTrue(all.contains("Product version"));
        assertTrue(store.precheck.contains("\"cpu_max_core_pct\":60"), store.precheck);
        assertTrue(store.precheck.contains("\"disk_free_bytes\":31744000000") || store.precheck.contains("disk_free_bytes"), store.precheck);
        assertEquals("COMPLETED", store.requestState);
        assertEquals(NOW.plus(Duration.ofDays(14)), store.expires);
        assertEquals("support_bundle", store.manifests.get(item).artefactClass());
        assertEquals(Optional.of(NOW.plus(Duration.ofDays(14))), store.manifests.get(item).expiresAt());
        assertEquals("COMPLETED", h.finalState);
    }

    @Test
    void everyCommandIsAnApprovedLiteralWithTheNoLoginShellPrefixAndNoCpdataRun(@TempDir Path dir) {
        var h = new Harness(dir, "gateway");
        script(h.device);
        var store = new Store("cpdata_collector_present", "cp_gaia_extras");
        h.run(store);
        assertEquals(6, h.device.commands.size());
        for (String command : h.device.commands) {
            assertTrue(command.startsWith(CpSupportBundle.CP_ENV), command);
            assertFalse(command.contains("bash -lc") || command.contains("--login"), command);
            assertFalse(command.contains("cpdata_collector --run"), command);
        }
        assertEquals(List.of("cp_sb_cpdata_collector_present", "cp_sb_show_version_all", "cp_sb_show_asset_all", "cp_sb_cphaprob_stat",
                "cp_sb_cpu_multi", "cp_sb_df_var_log"), h.gateIds);
    }

    @Test
    void cpdataCollectorPresenceIsRecordedAndTheToolIsNeverRun(@TempDir Path dir) {
        var h = new Harness(dir, "gateway");
        script(h.device);
        h.device.answers.put(CpSupportBundle.Exec.CPDATA_PRESENT.command(), ok("/opt/CPsuite-R82/fw1/bin/cpdata_collector\n"));
        var store = new Store("cpdata_collector_present");
        h.run(store);
        UUID item = store.id("cpdata_collector_present");
        assertEquals("COMPLETED", store.states.get(item));
        assertEquals("PRESENT", store.codes.get(item));
        assertTrue(store.manifests.isEmpty(), "a presence read stores no artefact");
        assertEquals(1, h.device.commands.size());
    }

    @Test
    void absentToolIsAbsentNotAnError(@TempDir Path dir) {
        var h = new Harness(dir, "gateway");
        script(h.device);
        var store = new Store("cpdata_collector_present");
        h.run(store);
        assertEquals("ABSENT", store.codes.get(store.id("cpdata_collector_present")));
        assertEquals("COMPLETED", store.requestState);
    }

    @Test
    void hcpLatestWithAFreshVaultCopyContactsNoDevice(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        script(h.device);
        byte[] report = gz("synthetic hcp report".getBytes(StandardCharsets.UTF_8));
        ArtefactStore.ArtefactMetadata meta;
        try (var handle = h.artefacts.open("device-1", "older-job", "check_point", false)) { handle.sink().write(report); meta = handle.finish(); }
        var store = new Store("cp_hcp_latest");
        store.copySource = new HcpCopySource(meta.ref().value(), meta.wrappedDataKey(), report.length);
        h.run(store);
        UUID item = store.id("cp_hcp_latest");
        assertEquals("COMPLETED", store.states.get(item));
        assertEquals("VAULT_COPY", store.codes.get(item));
        assertEquals(0, h.device.commands.size(), "no SSH command");
        assertEquals(0, h.device.fetches.size(), "no SFTP read");
        assertEquals("items/cp_hcp_latest/hcp_report.tar.gz", store.entryNames.get(item));
        assertEquals(new String(report, StandardCharsets.ISO_8859_1), new String(h.read(store.manifests.get(item)), StandardCharsets.ISO_8859_1));
    }

    @Test
    void hcpLatestOtherwiseListsThenFetchesTheNewestNameAndKeepsNoHostInTheEntryName(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        script(h.device);
        String older = "hcp_report_fwtango04_14_11_26_03_00.tar.gz";
        h.device.answers.put(CpSupportBundle.Exec.LS_HCP_LAST.command(), ok("total 8\n"
                + "-rw-r--r--. 1 1234 " + (1 << 20) + " 1790000000 " + older + "\n"
                + "-rw-r--r--. 1 1234 4096 1790086400 " + HCP_NAME + "\n"
                + "-rw-r--r--. 1 1234 10 1790086500 notes.txt\n"));
        h.device.hcp = gz("synthetic hcp report".getBytes(StandardCharsets.UTF_8));
        var store = new Store("cp_hcp_latest");
        h.run(store);
        UUID item = store.id("cp_hcp_latest");
        assertEquals("COMPLETED", store.states.get(item));
        assertEquals(List.of("/var/log/hcp/last/" + HCP_NAME), h.device.fetches);
        assertEquals("items/cp_hcp_latest/hcp_report_15_11_26_03_00.tar.gz", store.entryNames.get(item));
        assertFalse(store.entryNames.get(item).contains("fwtango"));
        assertEquals(List.of("cp_sb_ls_hcp_last", "cp_sb_sftp_get_hcp"), h.gateIds);
    }

    @Test
    void anArchiveThatIsNotGzipIsRefusedAndNothingIsStored(@TempDir Path dir) {
        var h = new Harness(dir, "gateway");
        script(h.device);
        h.device.answers.put(CpSupportBundle.Exec.LS_HCP_LAST.command(), ok("-rw-r--r--. 1 1234 100 1790086400 " + HCP_NAME + "\n"));
        h.device.hcp = "<html>not gzip</html>".getBytes(StandardCharsets.UTF_8);
        var store = new Store("cp_hcp_latest");
        h.run(store);
        UUID item = store.id("cp_hcp_latest");
        assertEquals("FAILED", store.states.get(item));
        assertEquals("NOT_GZIP", store.codes.get(item));
        assertTrue(store.manifests.isEmpty());
        assertEquals("FAILED", store.requestState);
    }

    @Test
    void aListedSizeOverTheBoundIsTooLargeAndNeverFetched(@TempDir Path dir) {
        var h = new Harness(dir, "gateway");
        script(h.device);
        h.device.answers.put(CpSupportBundle.Exec.LS_HCP_LAST.command(), ok("-rw-r--r--. 1 1234 " + (20L << 20) + " 1790086400 " + HCP_NAME + "\n"));
        var store = new Store("cp_hcp_latest");
        h.run(store);
        assertEquals("TOO_LARGE", store.states.get(store.id("cp_hcp_latest")));
        assertTrue(h.device.fetches.isEmpty());
    }

    @Test
    void aFailedCommandLeavesTheOthersAndTheRequestIsPartial(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        script(h.device);
        h.device.answers.put(CpSupportBundle.Exec.CPHAPROB_STAT.command(), new ExecResult.TimedOut());
        var store = new Store("cp_gaia_extras");
        h.run(store);
        UUID item = store.id("cp_gaia_extras");
        assertEquals("PARTIAL", store.states.get(item));
        var entries = TarStream.readAll(new GZIPInputStream(new ByteArrayInputStream(h.read(store.manifests.get(item)))), 1 << 20);
        assertEquals(4, entries.size());
    }

    @Test
    void aGateThatIsNotSignedOffRejectsTheJobBeforeAnyContact(@TempDir Path dir) {
        var h = new Harness(dir, "gateway");
        script(h.device);
        h.rows = h.rows.stream().filter(r -> !r.gateId().equals("cp_sb_show_asset_all")).toList();
        var store = new Store("cp_gaia_extras");
        h.run(store);
        assertEquals("REJECTED", h.finalState);
        assertTrue(h.device.commands.isEmpty());
        assertEquals("FAILED", store.requestState);
    }

    @Test
    void aManagementServerIsRejectedInThisStep(@TempDir Path dir) {
        var h = new Harness(dir, "management_server");
        script(h.device);
        var store = new Store("cp_gaia_extras");
        h.run(store);
        assertEquals("REJECTED", h.finalState);
        assertTrue(h.device.commands.isEmpty());
    }

    @Test
    void aCancelledRequestRunsNothing(@TempDir Path dir) {
        var h = new Harness(dir, "gateway");
        script(h.device);
        var store = new Store("cp_gaia_extras");
        store.queued = false;
        h.run(store);
        assertTrue(h.device.commands.isEmpty());
        assertNull(store.requestState);
    }

    @Test
    void connectionFailureFailsEveryItemWithoutTextFromTheDevice(@TempDir Path dir) {
        var h = new Harness(dir, "gateway");
        h.device.connectFails = true;
        var store = new Store("cp_gaia_extras", "cp_hcp_latest");
        h.run(store);
        assertEquals("FAILED", store.requestState);
        assertEquals("CONNECTION_FAILED", store.codes.get(store.id("cp_gaia_extras")));
        assertEquals("FAILED", h.finalState);
    }
}
