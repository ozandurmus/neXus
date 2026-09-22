package com.securityexpert.nexus.ui2.worker.backup.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

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
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiStreamHandler;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiStreamOutcome;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;

/** Measured live, 2026-09-22: two "completed" Palo Alto backups were 108-byte XML error responses
 * -- the credential reference id had been sent as the API key, and whatever the device answered
 * was stored as a V1 backup. */
class PaloAltoBackupExecutorTest {

    private static final byte[] GZIP_HEADER_ARCHIVE = {(byte) 0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, 3, 1, 2, 3, 4, 5};

    private static final class FakeTransport implements DeviceTransport {
        final List<XmlApiSpec> calls = new ArrayList<>();
        byte[] exportBody = GZIP_HEADER_ARCHIVE;
        int exportStatus = 200;

        boolean sshReachable = true;
        final List<String> shellCommands = new ArrayList<>();
        String setFormatText = "set deviceconfig system hostname fw\nset deviceconfig system dns-setting servers primary 192.0.2.53\n"
                + "set network interface ethernet ethernet1/1 layer3 ip 192.0.2.1/24\nset vsys vsys1 zone trust\n"
                + "set rulebase security rules allow-out from trust\nset mgt-config users admin permissions role-based superuser yes\n";

        @Override public ConnectResult connect(ConnectionTarget t, ConnectSpec s, Duration d) {
            assertEquals(22, t.port(), "the set-format read opens the device's own SSH port");
            assertEquals("fw", t.host(), "the SSH host is the API host without scheme");
            assertEquals("cred-ref-1", s.credentialRef(), "the device's collection credential opens the CLI");
            return sshReachable ? new ConnectResult.Authenticated(() -> "ssh-1") : new ConnectResult.AuthenticationFailed("NO_ROUTE");
        }
        @Override public ExecResult execInteractive(TransportSession s, ExecSpec e, Duration d) {
            shellCommands.add(e.command());
            return "show config running".equals(e.command()) ? new ExecResult.Completed(setFormatText, 0)
                    : new ExecResult.ChannelFailed("empty output");
        }
        @Override public ExecResult exec(TransportSession s, ExecSpec e, Duration d) { throw new UnsupportedOperationException(); }
        @Override public FetchResult fetch(TransportSession s, FetchSpec f, Duration d) { throw new UnsupportedOperationException(); }
        @Override public void disconnect(TransportSession s) { }

        @Override
        public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
            calls.add(spec);
            if ("keygen".equals(spec.type())) {
                assertEquals("backup-user", spec.formParams().get("user"), "keygen must use the resolved credential");
                return new XmlApiResult.Completed(200, "<response status=\"success\"><result><key>K-1</key></result></response>");
            }
            assertEquals("K-1", spec.headers().get("X-PAN-KEY"), "every later call carries the generated key");
            return new XmlApiResult.Completed(200, "<response status=\"success\"><result><config/></result></response>");
        }

        @Override
        public <T> XmlApiStreamOutcome<T> xmlApiCallStreaming(ApiTarget target, XmlApiSpec spec, Duration timeout, XmlApiStreamHandler<T> handler) {
            calls.add(spec);
            assertEquals("K-1", spec.headers().get("X-PAN-KEY"));
            try {
                return new XmlApiStreamOutcome.Completed<>(exportStatus, handler.handle(new ByteArrayInputStream(exportBody)));
            } catch (IOException e) {
                return new XmlApiStreamOutcome.Failed<>(e.getMessage());
            }
        }
    }

    private static final class MemoryStore implements ArtefactStore {
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        boolean finished;
        boolean closed;

        @Override
        public ArtefactHandle open(String deviceId, String jobId, String vendor, boolean gzip) {
            return new ArtefactHandle() {
                @Override public OutputStream sink() { return sink; }
                @Override public ArtefactMetadata finish() {
                    finished = true;
                    return new ArtefactMetadata(new ArtefactRef("art-1"), "sha", sink.size(), "csha", sink.size() + 28, "none", "k", new byte[0]);
                }
                @Override public void close() { closed = true; }
            };
        }

        @Override
        public InputStream retrieve(ArtefactRef ref, byte[] wrappedDataKey, boolean gzip) { throw new UnsupportedOperationException(); }
    }

    private static PaloAltoBackupExecutor executor(FakeTransport transport, MemoryStore store) {
        return new PaloAltoBackupExecutor(transport, store, ref -> new PanCredentialMaterial("backup-user", "pw".toCharArray()));
    }

    @Test
    void generatesAKeyFromTheResolvedCredentialAndStoresAGzipExport() throws IOException {
        FakeTransport transport = new FakeTransport();
        MemoryStore store = new MemoryStore();

        var result = executor(transport, store).executeBackup(new ApiTarget("ep-1", "https://fw"), "cred-ref-1", "dev-1", "job-1", "");

        assertTrue(result.success(), String.valueOf(result.errorMessage()));
        assertTrue(store.finished);
        assertEquals("keygen", transport.calls.get(0).type());
        assertNotNull(result.artefactId());
        assertEquals(PanSetConfigReader.COMMANDS, transport.shellCommands, "the four gated CLI reads, in order, once");
        // The stored artefact is one gzip tar bundle: the device-state export, byte for byte, and the set-format text.
        List<com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository.Entry> members =
                com.securityexpert.nexus.ui2.persistence.artefact.content.TarEntryLister.list(new ByteArrayInputStream(store.sink.toByteArray()));
        assertEquals(List.of(PaloAltoBackupExecutor.BUNDLE_DEVICE_STATE, PaloAltoBackupExecutor.BUNDLE_RUNNING_CONFIG_SET),
                members.stream().map(m -> m.path()).toList());
        assertEquals(GZIP_HEADER_ARCHIVE.length, members.get(0).bytes(), "the whole archive, gzip magic included, is the first member");
        assertEquals(transport.setFormatText.getBytes(StandardCharsets.UTF_8).length, members.get(1).bytes());
    }

    @Test
    void anXmlErrorAnsweredToTheExportIsARefusalNeverAStoredBackup() {
        FakeTransport transport = new FakeTransport();
        transport.exportBody = "<response status = 'error' code = '403'><result><msg>Type [export] not authorized for user role</msg></result></response>"
                .getBytes(StandardCharsets.UTF_8);
        MemoryStore store = new MemoryStore();

        var result = executor(transport, store).executeBackup(new ApiTarget("ep-1", "https://fw"), "cred-ref-1", "dev-1", "job-1", "");

        assertFalse(result.success());
        assertFalse(store.finished, "nothing is finished into the store");
        assertTrue(result.errorMessage().contains("code 403"), result.errorMessage());
        assertTrue(result.errorMessage().contains("not authorized"), result.errorMessage());
    }

    @Test
    void withoutACredentialResolverEveryBackupIsRefusedBeforeAnyDeviceContact() {
        FakeTransport transport = new FakeTransport();
        MemoryStore store = new MemoryStore();

        var result = new PaloAltoBackupExecutor(transport, store).executeBackup(new ApiTarget("ep-1", "https://fw"), "cred-ref-1", "dev-1", "job-1", "");

        assertFalse(result.success());
        assertTrue(transport.calls.isEmpty());
    }

    @Test
    void anUnreachableCliFailsTheRunWithItsReasonAndStoresNothing() {
        FakeTransport transport = new FakeTransport();
        transport.sshReachable = false;
        MemoryStore store = new MemoryStore();

        var result = executor(transport, store).executeBackup(new ApiTarget("ep-1", "https://fw"), "cred-ref-1", "dev-1", "job-1", "");

        assertFalse(result.success());
        assertFalse(store.finished, "half a bundle is never stored");
        assertTrue(result.errorMessage().startsWith("pan_set_config_unavailable:"), result.errorMessage());
    }

    @Test
    void aCliAnswerThatIsNotAConfigurationFailsTheRun() {
        FakeTransport transport = new FakeTransport();
        transport.setFormatText = "Unknown command: show config running\n";
        MemoryStore store = new MemoryStore();

        var result = executor(transport, store).executeBackup(new ApiTarget("ep-1", "https://fw"), "cred-ref-1", "dev-1", "job-1", "");

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("set-lines"), result.errorMessage());
    }
}
