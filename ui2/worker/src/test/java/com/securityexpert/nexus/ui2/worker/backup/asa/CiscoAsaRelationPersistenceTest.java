package com.securityexpert.nexus.ui2.worker.backup.asa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;
import com.securityexpert.nexus.ui2.worker.backup.asa.AsaConfigRelation.Result;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;

/** The backup hands only the derived relation (counts) to the sink, once per stored backup, with no new device read. */
class CiscoAsaRelationPersistenceTest {

    private static final String CLEAR_KEY = String.join("", "Qa", "7z", "Lp", "3x");

    private static final class Shell implements DeviceTransport {
        final Map<String, String> answers;
        final List<String> commands = new ArrayList<>();

        Shell(Map<String, String> answers) {
            this.answers = answers;
        }

        public ConnectResult connect(ConnectionTarget t, ConnectSpec s, Duration d) {
            return new ConnectResult.Authenticated(() -> "s-1");
        }

        public ExecResult execInteractive(TransportSession session, ExecSpec spec, Duration timeout) {
            commands.add(spec.command());
            String a = answers.get(spec.command());
            if (a == null) return new ExecResult.Completed("ERROR: Invalid input detected", 0);
            return new ExecResult.Completed(a, 0);
        }

        public void disconnect(TransportSession s) { }
        public ExecResult exec(TransportSession s, ExecSpec e, Duration d) { throw new AssertionError(); }
        public FetchResult fetch(TransportSession s, FetchSpec f, Duration d) { throw new AssertionError(); }
        public XmlApiResult xmlApiCall(ApiTarget t, XmlApiSpec s, Duration d) { throw new AssertionError(); }
    }

    private record Recorded(String deviceId, String artefactId, AsaConfigRelation relation) { }

    private static Map<String, String> answers(String running, String startup) {
        Map<String, String> m = new java.util.HashMap<>();
        m.put(CiscoAsaPlan.SHOW_CURPRIV, "Current privilege level : 15");
        m.put(CiscoAsaPlan.MORE_SYSTEM_RUNNING_CONFIG, running);
        if (startup != null) m.put(CiscoAsaPlan.SHOW_STARTUP_CONFIG, startup);
        m.put(CiscoAsaPlan.SHOW_VERSION, "Adaptive Security Appliance Software Version 9.22(1)\n");
        m.put(CiscoAsaPlan.SHOW_MODE, "Security context mode: single\n");
        return m;
    }

    private static Recorded run(Map<String, String> answers, Path dir, List<String> commandsOut) {
        var store = new FileArtefactStore(dir, ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
        var shell = new Shell(answers);
        List<Recorded> got = new ArrayList<>();
        var executor = new CiscoAsaExecutor(shell, store)
                .withConfigRelationSink((d, a, r) -> got.add(new Recorded(d, a, r)));
        BackupResult result = executor.backup(new Target("192.0.2.10", 22), "cred", "FW-TANGO-04", "job-1");
        assertTrue(result instanceof BackupResult.Partial, "no SCP client: text stored, archive missing");
        commandsOut.addAll(shell.commands);
        assertEquals(1, got.size());
        return got.get(0);
    }

    @Test
    void matchingConfigurationsAreRecordedAsMatchWithTheArtefactId(@TempDir Path dir) {
        String running = ": Saved\nASA Version 9.22(1)\nhostname FW-TANGO-04\nkey " + CLEAR_KEY + "\n";
        String startup = ": Saved\n: Written by enable_15\nASA Version 9.22(1)\nhostname FW-TANGO-04\nkey " + AsaConfigRelation.MASK + "\n";
        List<String> commands = new ArrayList<>();
        Recorded r = run(answers(running, startup), dir, commands);
        assertEquals("FW-TANGO-04", r.deviceId());
        assertFalse(r.artefactId().isBlank());
        assertEquals(Result.MATCH, r.relation().result());
        assertEquals(1, r.relation().linesEqualOnlyByMask());
        // no read beyond the existing four configuration reads (plus the privilege check)
        assertEquals(1, commands.stream().filter(c -> c.equals(CiscoAsaPlan.SHOW_STARTUP_CONFIG)).count());
        assertEquals(1, commands.stream().filter(c -> c.equals(CiscoAsaPlan.MORE_SYSTEM_RUNNING_CONFIG)).count());
    }

    @Test
    void aChangedLineIsRecordedAsMismatch(@TempDir Path dir) {
        String running = "ASA Version 9.22(1)\nhostname FW-TANGO-04\nsnmp-server enable\n";
        String startup = "ASA Version 9.22(1)\nhostname FW-TANGO-04\n";
        assertEquals(Result.MISMATCH, run(answers(running, startup), dir, new ArrayList<>()).relation().result());
    }

    @Test
    void aMissingStartupTextIsRecordedAsNotEvaluable(@TempDir Path dir) {
        String running = "ASA Version 9.22(1)\nhostname FW-TANGO-04\n";
        assertEquals(Result.NOT_EVALUABLE, run(answers(running, null), dir, new ArrayList<>()).relation().result());
    }

    @Test
    void aFailingSinkDoesNotChangeTheBackupResult(@TempDir Path dir) {
        var store = new FileArtefactStore(dir, ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
        var executor = new CiscoAsaExecutor(new Shell(answers("ASA Version 9.22(1)\n", "ASA Version 9.22(1)\n")), store)
                .withConfigRelationSink((d, a, r) -> { throw new IllegalStateException("synthetic"); });
        assertTrue(executor.backup(new Target("192.0.2.10", 22), "cred", "FW-TANGO-04", "job-1") instanceof BackupResult.Partial);
    }
}
