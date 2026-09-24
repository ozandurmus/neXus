package com.securityexpert.nexus.ui2.worker.backup.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
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
import com.securityexpert.nexus.ui2.jobs.transport.FetchStreamResult;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.worker.backup.BackupRequest;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;

class MdsExportExecutorTest {

    private static final String JOB = "3f2a9c1e-0000-4000-8000-00000000abcd";
    private static final String DIR = "/var/log/nexus-mds-3f2a9c1e-0000-4000-8000-00000000abcd";
    private static final byte[] BUNDLE = "mds-export-bundle".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private Scripted transport;
    private MdsExportExecutor executor;
    private final BackupRequest request = new BackupRequest(new ConnectionTarget("t", "192.0.2.10", 22), Optional.of("cred"), "trust");

    @BeforeEach
    void setUp() {
        transport = new Scripted();
        executor = new MdsExportExecutor(transport,
                new FileArtefactStore(tempDir, ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]))),
                1024L * 1024, Duration.ofMillis(1), Duration.ofMillis(300));
    }

    @Test
    void theWorkDirectoryIsBuiltFromTheJobIdOnly() {
        assertEquals(DIR, MdsExportPlan.workDir(JOB));
        assertEquals("/var/log/nexus-mds-abcdef12-f", MdsExportPlan.workDir("ABCDEF12;rm -rf /")); // only [a-f0-9-] survives
    }

    @Test
    void aCompletedExportFetchesVerifiesAndRemovesTheWorkDirectory() {
        BackupResult r = executor.collect(request, "dev", JOB);
        assertTrue(r instanceof BackupResult.Completed, String.valueOf(r));
        assertTrue(transport.issued.contains(String.format(MdsExportPlan.MDS_BACKUP_START, DIR)));
        assertTrue(transport.issued.contains("rm -rf " + DIR + " " + DIR + ".tgz"));
        assertEquals(DIR + ".tgz", transport.fetchedPath);
    }

    @Test
    void aNonZeroExitCodeIsARefusalAndCleansUp() {
        transport.rc = "1";
        BackupResult r = executor.collect(request, "dev", JOB);
        assertTrue(r instanceof BackupResult.SubmitRefused, String.valueOf(r));
        assertTrue(transport.issued.stream().anyMatch(c -> c.startsWith("rm -rf " + DIR)));
    }

    @Test
    void noExitCodeBeforeTheDeadlineIsOutcomeUnknownAndLeavesTheDirectory() {
        transport.rc = null; // mds_backup still running
        BackupResult r = executor.collect(request, "dev", JOB);
        assertTrue(r instanceof BackupResult.OutcomeUnknown, String.valueOf(r));
        assertFalse(transport.issued.stream().anyMatch(c -> c.startsWith("rm -rf")), "never removed while mds_backup may run");
    }

    @Test
    void aDigestMismatchDeletesNothing() {
        transport.digest = "0".repeat(64);
        BackupResult r = executor.collect(request, "dev", JOB);
        assertTrue(r instanceof BackupResult.DigestMismatch, String.valueOf(r));
        assertFalse(transport.issued.stream().anyMatch(c -> c.startsWith("rm -rf")));
    }

    @Test
    void tooLittleFreeSpaceRefusesBeforeAnythingIsCreated() {
        transport.dfAvailableKb = "10";
        BackupResult r = executor.collect(request, "dev", JOB);
        assertTrue(r instanceof BackupResult.InsufficientFreeSpace, String.valueOf(r));
        assertFalse(transport.issued.stream().anyMatch(c -> c.startsWith("mkdir")));
    }

    @Test
    void aTimedOutStartIsPolledNeverCleanedUpUnderARunningBackup() {
        transport.startTimesOut = true;
        BackupResult r = executor.collect(request, "dev", JOB);
        assertTrue(r instanceof BackupResult.Completed, "a timed-out start that did start is followed to its exit code: " + r);
        transport.rc = null;
        transport.issued.clear();
        BackupResult unknown = executor.collect(request, "dev", JOB);
        assertTrue(unknown instanceof BackupResult.OutcomeUnknown, String.valueOf(unknown));
        assertFalse(transport.issued.stream().anyMatch(c -> c.startsWith("rm -rf")));
    }

    private static final class Scripted implements DeviceTransport {
        final List<String> issued = new ArrayList<>();
        String rc = "0";
        String dfAvailableKb = "52428800";
        String digest;
        String fetchedPath;
        boolean startTimesOut;

        Scripted() {
            try {
                digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BUNDLE));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        record S(String sessionId) implements TransportSession {
        }

        @Override
        public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            return new ConnectResult.Authenticated(new S("s"));
        }

        @Override
        public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
            String c = spec.command();
            issued.add(c);
            if (startTimesOut && c.contains("mds_backup -b")) {
                return new ExecResult.TimedOut();
            }
            if (c.equals(MdsExportPlan.DF_VAR_LOG)) {
                return new ExecResult.Completed("Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/x 100000000 1 "
                        + dfAvailableKb + " 1% /var/log\n", 0);
            }
            if (c.startsWith("cat ") && c.endsWith("mds_backup.rc")) {
                return rc == null ? new ExecResult.Completed("cat: no such file", 1) : new ExecResult.Completed(rc + "\n", 0);
            }
            if (c.startsWith("ls ")) {
                return new ExecResult.Completed("mdsstat.txt\ncplic.txt\n2026-09-23_02-00.mdsbk.tgz\n", 0);
            }
            if (c.startsWith("sha256sum ")) {
                return new ExecResult.Completed(digest + "  " + DIR + ".tgz\n", 0);
            }
            return new ExecResult.Completed("", 0);
        }

        @Override
        public FetchStreamResult fetchStreaming(TransportSession session, FetchSpec spec, Duration timeout, OutputStream sink) {
            fetchedPath = spec.remotePath();
            try {
                sink.write(BUNDLE);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return new FetchStreamResult.Fetched(BUNDLE.length);
        }

        @Override
        public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void disconnect(TransportSession session) {
        }
    }
}
