package com.securityexpert.nexus.ui2.worker.backup.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.worker.backup.BackupRequest;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscript;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

class QuantumSparkBackupExecutorTest {
    @TempDir Path temp;
    private static final String RECEIVER_SECRET = "SyntheticReceiverSecret42";
    private final BackupRequest request = new BackupRequest(
            new ConnectionTarget("endpoint", "192.0.2.10", 22), Optional.of("backup-credential"), "trust");

    @Test
    void uploadIsBundledAndBothInlineSecretsAreAbsentFromTranscript() throws Exception {
        Path inbox = Files.createDirectory(temp.resolve("cc-inbox"));
        Path otherUpload = Files.writeString(inbox.resolve("other-run.tgz"), "unrelated upload");
        ArtefactStore store = store();
        FakeTransport transport = new FakeTransport(inbox, true);
        QuantumSparkBackupExecutor executor = new QuantumSparkBackupExecutor(transport, store, inbox, "192.0.2.20",
                ref -> RECEIVER_SECRET.toCharArray(), Duration.ZERO);
        JobTranscript transcript = new JobTranscript();
        BackupResult result;
        try (JobTranscriptScope ignored = JobTranscriptScope.open(transcript)) {
            result = executor.collect(request, Optional.of("receiver-credential"), "device", "job");
        }
        assertTrue(result instanceof BackupResult.Completed, String.valueOf(result));
        assertEquals(2, transport.logReads);
        assertEquals(1, transport.pushes);
        assertEquals("unrelated upload", Files.readString(otherUpload));
        try (var files = Files.list(inbox)) { assertEquals(1, files.count()); }
        ByteArrayOutputStream transcriptBytes = new ByteArrayOutputStream();
        transcript.writeTo(transcriptBytes);
        String text = transcriptBytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("[credential]"));
        assertFalse(text.contains(RECEIVER_SECRET));
        assertFalse(text.contains(transport.encryptionPassword));
        assertFalse(text.contains("192.0.2.20"));

        var metadata = ((BackupResult.Completed) result).artefact();
        try (var in = new GZIPInputStream(store.retrieve(metadata.ref(), metadata.wrappedDataKey(), false))) {
            String bundle = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(bundle.contains("spark-settings.zip"));
            assertTrue(bundle.contains("manifest.txt"));
            assertTrue(bundle.contains("restore-password.txt"));
            assertTrue(bundle.contains(transport.encryptionPassword));
        }
    }

    @Test
    void absentUploadRefusesAndLeavesInboxClean() throws Exception {
        Path inbox = Files.createDirectory(temp.resolve("in"));
        FakeTransport transport = new FakeTransport(inbox, false);
        QuantumSparkBackupExecutor executor = new QuantumSparkBackupExecutor(transport, store(), inbox, "192.0.2.20",
                ref -> RECEIVER_SECRET.toCharArray(), Duration.ZERO);
        BackupResult result = executor.collect(request, Optional.of("receiver-credential"), "device", "job");
        assertTrue(result instanceof BackupResult.SubmitRefused, String.valueOf(result));
        assertTrue(((BackupResult.SubmitRefused) result).reason().startsWith("the appliance did not upload the backup"));
        assertFalse(((BackupResult.SubmitRefused) result).reason().contains(RECEIVER_SECRET));
        try (var files = Files.list(inbox)) { assertEquals(0, files.count()); }
        assertEquals(2, transport.logReads);
    }

    private ArtefactStore store() {
        return new FileArtefactStore(temp.resolve("store"),
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
    }

    private static final class FakeTransport implements DeviceTransport {
        record Session(String sessionId) implements TransportSession { }
        private final Path inbox;
        private final boolean upload;
        int logReads;
        int pushes;
        String encryptionPassword;

        FakeTransport(Path inbox, boolean upload) { this.inbox = inbox; this.upload = upload; }

        @Override public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            return new ConnectResult.Authenticated(new Session("fake"));
        }

        @Override public ExecResult execInteractive(TransportSession session, ExecSpec spec, Duration timeout) {
            String command = spec.command();
            JobTranscriptScope.add("ssh", "command", JobTranscript.safeSshCommand(command));
            if (command.equals(QuantumSparkBackupPlan.LOG)) {
                logReads++;
                String answer = "synthetic log entry";
                JobTranscriptScope.add("ssh", "answer", JobTranscript.safeSshAnswer(command, answer));
                return new ExecResult.Completed(answer, 0);
            }
            pushes++;
            Matcher filename = Pattern.compile(" filename ([0-9a-f]{16}) ").matcher(command);
            Matcher password = Pattern.compile("file-encryption on password ([A-Za-z0-9]{24}) ").matcher(command);
            assertTrue(filename.find());
            assertTrue(password.find());
            assertTrue(command.contains("username "
                    + com.securityexpert.nexus.ui2.worker.backup.radware.CyberControllerBackupExecutor.RECEIVER_USER
                    + " password " + RECEIVER_SECRET));
            encryptionPassword = password.group(1);
            if (upload) {
                byte[] zip = new byte[2048];
                zip[0] = 'P'; zip[1] = 'K'; zip[2] = 3; zip[3] = 4;
                try { Files.write(inbox.resolve(filename.group(1) + ".zip"), zip); }
                catch (IOException e) { throw new IllegalStateException(e); }
            }
            String answer = "synthetic response " + encryptionPassword + " " + RECEIVER_SECRET;
            JobTranscriptScope.add("ssh", "answer", JobTranscript.safeSshAnswer(command, answer));
            return new ExecResult.Completed(answer, 0);
        }

        @Override public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) { throw new AssertionError(); }
        @Override public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) { throw new AssertionError(); }
        @Override public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) { throw new AssertionError(); }
        @Override public void disconnect(TransportSession session) { }
    }
}
