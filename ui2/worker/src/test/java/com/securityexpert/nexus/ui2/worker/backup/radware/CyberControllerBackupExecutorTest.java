package com.securityexpert.nexus.ui2.worker.backup.radware;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.worker.backup.BackupRequest;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;

class CyberControllerBackupExecutorTest {
    @TempDir Path temp;

    private BackupResult run(FakeTransport transport) {
        var store = new FileArtefactStore(temp.resolve("store"),
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
        var executor = new CyberControllerBackupExecutor(transport, store, temp, "192.0.2.20",
                ref -> "synthetic-receiver-value".toCharArray());
        return executor.collect(new BackupRequest(new ConnectionTarget("synthetic", "192.0.2.10", 22),
                Optional.of("synthetic-credential"), "synthetic-trust"), Optional.of("synthetic-receiver"),
                "synthetic-device", "synthetic-job");
    }

    @Test void completedRunDeletesOnlyThreeExactLeftovers() {
        var transport = new FakeTransport(3);
        assertInstanceOf(BackupResult.Completed.class, run(transport));
        assertEquals(transport.leftovers, transport.deleted.subList(1, transport.deleted.size()));
        assertEquals(transport.own, transport.deleted.get(0));
        assertEquals(1, transport.listReads);
    }

    @Test void cleanupStopsAtFirstUnconfirmedDeleteAndKeepsCompleted() {
        var transport = new FakeTransport(3);
        transport.failAt = 2;
        assertInstanceOf(BackupResult.Completed.class, run(transport));
        assertEquals(3, transport.deleted.size()); // Current run, one success, one refusal.
    }

    @Test void unexpectedCleanupFailureKeepsCompleted() {
        var transport = new FakeTransport(3);
        transport.throwAt = 1;
        assertInstanceOf(BackupResult.Completed.class, run(transport));
        assertEquals(2, transport.deleted.size());
    }

    @Test void noMoreThanTwentyLeftoversAreAttempted() {
        var transport = new FakeTransport(25);
        assertInstanceOf(BackupResult.Completed.class, run(transport));
        assertEquals(21, transport.deleted.size());
    }

    @Test void failedExportNeverCleansLeftovers() {
        var transport = new FakeTransport(3);
        transport.export = false;
        assertInstanceOf(BackupResult.SubmitRefused.class, run(transport));
        assertEquals(List.of(transport.own), transport.deleted);
    }

    @Test void failedOwnCleanupNeverCleansLeftovers() {
        var transport = new FakeTransport(3);
        transport.failAt = 0;
        assertInstanceOf(BackupResult.CleanupFailed.class, run(transport));
        assertEquals(List.of(transport.own), transport.deleted);
    }

    private final class FakeTransport implements DeviceTransport {
        record Session(String sessionId) implements TransportSession { }
        final List<String> leftovers;
        final List<String> deleted = new ArrayList<>();
        String own;
        int listReads;
        int failAt = -1;
        int throwAt = -1;
        boolean export = true;

        FakeTransport(int count) {
            leftovers = IntStream.range(0, count).mapToObj(i -> "nexus-" + String.format("%016x", i)).toList();
        }

        @Override public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            return new ConnectResult.Authenticated(new Session("synthetic"));
        }

        @Override public ExecResult execInteractive(TransportSession session, ExecSpec spec, Duration timeout) {
            if (spec.command().startsWith("system backup config create ")) {
                own = spec.command().substring("system backup config create ".length());
                return new ExecResult.Completed("Created.", 0);
            }
            assertEquals(CyberControllerBackupPlan.LIST, spec.command());
            listReads++;
            String listing = own + " 2 date\n";
            for (String leftover : leftovers) listing += leftover + " 2 date\n";
            listing += "nexus-XYZ 2 date\nmynexus-0123456789abcdef 2 date\nnexus-0123456789abcdef0 2 date\n"
                    + "nexus-0123456789abcdef.tgz 2 date\n";
            return new ExecResult.Completed(listing, 0);
        }

        @Override public ExecResult execInteractiveAnswering(TransportSession session, ExecSpec spec,
                List<PromptAnswer> answers, Duration timeout) {
            if (spec.command().startsWith("system backup config export ")) {
                if (!export) return new ExecResult.Completed("Export refused.", 1);
                try { Files.write(temp.resolve(own.substring(6) + ".tgz.tar"), new byte[2048]); }
                catch (IOException e) { throw new IllegalStateException(e); }
                return new ExecResult.Completed(CyberControllerBackupPlan.EXPORT_DONE, 0);
            }
            assertTrue(spec.command().startsWith("system backup config delete "));
            assertEquals("(Y/N)?", answers.get(0).promptSuffix());
            assertArrayEquals(new char[] {'y'}, answers.get(0).reply());
            deleted.add(spec.command().substring("system backup config delete ".length()));
            int index = deleted.size() - 1;
            if (index == throwAt) throw new IllegalStateException("synthetic failure");
            return new ExecResult.Completed(index == failAt ? "Refused." : CyberControllerBackupPlan.DELETE_DONE, 0);
        }

        @Override public ExecResult exec(TransportSession s, ExecSpec spec, Duration t) { throw new AssertionError(); }
        @Override public FetchResult fetch(TransportSession s, FetchSpec spec, Duration t) { throw new AssertionError(); }
        @Override public XmlApiResult xmlApiCall(ApiTarget a, XmlApiSpec spec, Duration t) { throw new AssertionError(); }
        @Override public void disconnect(TransportSession s) { }
    }
}
