package com.securityexpert.nexus.ui2.worker.transport.ssh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSyslogRangedRead;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchStatus;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscript;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

/**
 * The SFTP ranged read of {@link SshExecTransport#fetchRange} (phase B contract §4.3) against a fake at the JSch
 * channel boundary: the repository has no in-process SFTP server fixture, so {@link ChannelSftp} is a mock whose
 * {@code get(path, monitor, skip)} behaves as JSch 0.1.55 does -- it reports the stat size to the monitor, then
 * serves file bytes from {@code skip}, signalling end of file with {@code -1}.
 */
class SshRangedFetchTest {

    /** A synthetic expanded $FWDIR path; the transcript and results must never carry its directory. */
    private static final String PATH = "/opt/synthetic-host-fw01/fw1/log/fwd.elg.1";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final Session session = mock(Session.class);
    private final ChannelSftp channel = mock(ChannelSftp.class);
    private final SshExecTransport transport = new SshExecTransport(
            ref -> { throw new AssertionError("no credential lookup"); }, ref -> java.util.Optional.empty());

    private static byte[] file(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) ('a' + i % 26);
        return bytes;
    }

    /** A remote file as JSch's InputStream would serve it; records the largest single read request. */
    private static final class RemoteFile extends InputStream {
        private final byte[] content;
        private final long servedLimit;
        private long position;
        long delivered;
        int largestRequest;

        RemoteFile(byte[] content, long skip, long servedLimit) {
            this.content = content;
            this.position = skip;
            this.servedLimit = servedLimit;
        }

        @Override
        public int read() {
            throw new AssertionError("byte-at-a-time reads are not expected");
        }

        @Override
        public int read(byte[] b, int off, int len) {
            largestRequest = Math.max(largestRequest, len);
            long end = Math.min(content.length, servedLimit);
            if (position >= end) return -1;
            int n = (int) Math.min(len, end - position);
            System.arraycopy(content, (int) position, b, off, n);
            position += n;
            delivered += n;
            return n;
        }
    }

    private void serve(byte[] content, long reportedSize, long servedLimit) throws Exception {
        when(session.openChannel("sftp")).thenReturn(channel);
        when(channel.get(eq(PATH), any(SftpProgressMonitor.class), anyLong())).thenAnswer(call -> {
            SftpProgressMonitor monitor = call.getArgument(1);
            monitor.init(SftpProgressMonitor.GET, PATH, "??", reportedSize);
            return new RemoteFile(content, call.getArgument(2), servedLimit);
        });
    }

    private RangedFetchResult read(long start, long max, OutputStream sink) {
        return transport.fetchRange(new SshTransportSession("synthetic", session), new RangedFetchSpec(PATH, start, max),
                TIMEOUT, sink);
    }

    @Test
    void readsExactlyTheRequestedRangeFromTheOffset() throws Exception {
        byte[] content = file(1_000);
        serve(content, 1_000, Long.MAX_VALUE);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RangedFetchResult result = read(100, 200, sink);
        assertEquals(RangedFetchStatus.CAP_REACHED, result.status());
        assertEquals(100, result.startOffset());
        assertEquals(200, result.bytesRequested());
        assertEquals(200, result.bytesReceived());
        assertEquals(1_000, result.sizeAtOpen().getAsLong());
        assertFalse(result.eofReached());
        assertArrayEquals(Arrays.copyOfRange(content, 100, 300), sink.toByteArray());
        verify(channel).get(eq(PATH), any(SftpProgressMonitor.class), eq(100L));
        verify(channel).disconnect();
        verify(session, never()).openChannel("exec");
        verify(session, never()).openChannel("shell");
        verify(session, never()).disconnect();
    }

    @Test
    void endOfFileInsideTheRangeIsReportedWithTheExactCount() throws Exception {
        byte[] content = file(1_000);
        serve(content, 1_000, Long.MAX_VALUE);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RangedFetchResult result = read(936, 8_388_672, sink);
        assertEquals(RangedFetchStatus.END_OF_FILE, result.status());
        assertEquals(64, result.bytesReceived());
        assertTrue(result.eofReached());
        assertTrue(result.bytesUsable());
        assertArrayEquals(Arrays.copyOfRange(content, 936, 1_000), sink.toByteArray());
    }

    @Test
    void readingFromExactlyTheEndIsAnEmptyEndOfFile() throws Exception {
        serve(file(500), 500, Long.MAX_VALUE);
        RangedFetchResult result = read(500, 64, new ByteArrayOutputStream());
        assertEquals(RangedFetchStatus.END_OF_FILE, result.status());
        assertEquals(0, result.bytesReceived());
    }

    @Test
    void sizeBelowTheOffsetAtOpenIsNotStorable() throws Exception {
        serve(file(50), 50, Long.MAX_VALUE);
        RangedFetchResult result = read(100, 200, new ByteArrayOutputStream());
        assertEquals(RangedFetchStatus.SIZE_BELOW_OFFSET, result.status());
        assertEquals(0, result.bytesReceived());
        assertEquals(50, result.sizeAtOpen().getAsLong());
        assertFalse(result.bytesUsable());
        assertEquals("UNKNOWN", result.status().forcedSourceOutcome());
    }

    @Test
    void fileThatShrinksDuringTheReadIsAShortRead() throws Exception {
        // The server reported 1,000 bytes at open, but the file ends at 500 while it is being read.
        serve(file(1_000), 1_000, 500);
        RangedFetchResult result = read(100, 800, new ByteArrayOutputStream());
        assertEquals(RangedFetchStatus.SHORT_READ, result.status());
        assertEquals(400, result.bytesReceived());
        assertTrue(result.eofReached());
        assertFalse(result.bytesUsable());
    }

    @Test
    void theFullCapStreamsThroughABoundedBufferAndRequestAheadBytesAreDropped() throws Exception {
        long cap = CpSyslogRangedRead.MAX_READ_BYTES;
        byte[] content = file((int) cap + 1_000_000); // the device holds far more than the cap
        when(session.openChannel("sftp")).thenReturn(channel);
        RemoteFile[] remote = new RemoteFile[1];
        when(channel.get(eq(PATH), any(SftpProgressMonitor.class), anyLong())).thenAnswer(call -> {
            ((SftpProgressMonitor) call.getArgument(1)).init(SftpProgressMonitor.GET, PATH, "??", content.length);
            remote[0] = new RemoteFile(content, 0, Long.MAX_VALUE);
            return remote[0];
        });
        CountingSink sink = new CountingSink();
        RangedFetchResult result = read(0, cap, sink);
        assertEquals(RangedFetchStatus.CAP_REACHED, result.status());
        assertEquals(cap, result.bytesReceived());
        assertEquals(cap, sink.count);
        assertEquals(cap, remote[0].delivered, "never asked the stream for a byte past the cap");
        assertTrue(remote[0].largestRequest <= SshExecTransport.RANGE_BUFFER_BYTES);
        assertTrue(sink.largestWrite <= SshExecTransport.RANGE_BUFFER_BYTES, "no whole-range buffering");
    }

    private static final class CountingSink extends OutputStream {
        long count;
        int largestWrite;

        @Override public void write(int b) { count++; largestWrite = Math.max(largestWrite, 1); }

        @Override public void write(byte[] b, int off, int len) { count += len; largestWrite = Math.max(largestWrite, len); }
    }

    @Test
    void missingFileAndPermissionDeniedAreTypedAndCarryNoDeviceText() throws Exception {
        when(session.openChannel("sftp")).thenReturn(channel);
        when(channel.get(anyString(), any(SftpProgressMonitor.class), anyLong()))
                .thenThrow(new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "synthetic-device-text No such file"))
                .thenThrow(new SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "synthetic-device-text denied"))
                .thenThrow(new SftpException(ChannelSftp.SSH_FX_OP_UNSUPPORTED, "synthetic-device-text"))
                .thenThrow(new SftpException(ChannelSftp.SSH_FX_FAILURE, "synthetic-device-text"));
        JobTranscript transcript = new JobTranscript();
        List<RangedFetchResult> results;
        try (JobTranscriptScope ignored = JobTranscriptScope.open(transcript)) {
            results = List.of(read(0, 10, new ByteArrayOutputStream()), read(0, 10, new ByteArrayOutputStream()),
                    read(0, 10, new ByteArrayOutputStream()), read(0, 10, new ByteArrayOutputStream()));
        }
        assertEquals(List.of(RangedFetchStatus.FILE_MISSING, RangedFetchStatus.PERMISSION_DENIED,
                RangedFetchStatus.SUBSYSTEM_UNAVAILABLE, RangedFetchStatus.TRANSPORT_FAILED),
                results.stream().map(RangedFetchResult::status).toList());
        assertEquals(List.of("SFTP_STATUS_2", "SFTP_STATUS_3", "SFTP_STATUS_8", "SFTP_STATUS_4"),
                results.stream().map(RangedFetchResult::reasonCode).toList());
        String text = transcriptText(transcript);
        assertFalse(text.contains("synthetic-device-text"));
        assertFalse(text.contains("synthetic-host-fw01"), "the transcript carries the base name only");
        assertTrue(text.contains("range read file=fwd.elg.1"));
        assertTrue(text.contains("status=FILE_MISSING"));
    }

    @Test
    void subsystemUnavailableWhenTheSftpChannelCannotOpen() throws Exception {
        when(session.openChannel("sftp")).thenThrow(new JSchException("synthetic-device-text subsystem request failed"));
        RangedFetchResult result = read(0, 10, new ByteArrayOutputStream());
        assertEquals(RangedFetchStatus.SUBSYSTEM_UNAVAILABLE, result.status());
        assertEquals("JSchException", result.reasonCode());

        ChannelSftp refusing = mock(ChannelSftp.class);
        org.mockito.Mockito.doReturn(refusing).when(session).openChannel("sftp");
        doAnswer(call -> { throw new JSchException("synthetic-device-text"); }).when(refusing).connect(org.mockito.ArgumentMatchers.anyInt());
        assertEquals(RangedFetchStatus.SUBSYSTEM_UNAVAILABLE, read(0, 10, new ByteArrayOutputStream()).status());
    }

    @Test
    void aStalledReadTimesOutAndClosesTheChannel() throws Exception {
        when(session.openChannel("sftp")).thenReturn(channel);
        CountDownLatch closed = new CountDownLatch(1);
        doAnswer(call -> { closed.countDown(); return null; }).when(channel).disconnect();
        when(channel.get(eq(PATH), any(SftpProgressMonitor.class), anyLong())).thenAnswer(call -> {
            ((SftpProgressMonitor) call.getArgument(1)).init(SftpProgressMonitor.GET, PATH, "??", 1_000);
            return new InputStream() {
                boolean first = true;
                @Override public int read() { throw new AssertionError(); }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (first) { first = false; b[off] = 'x'; return 1; }
                    try {
                        if (!closed.await(10, TimeUnit.SECONDS)) throw new AssertionError("watchdog never fired");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    throw new IOException("inputstream is closed");
                }
            };
        });
        long started = System.nanoTime();
        RangedFetchResult result = transport.fetchRange(new SshTransportSession("synthetic", session),
                new RangedFetchSpec(PATH, 0, 100), Duration.ofMillis(200), new ByteArrayOutputStream());
        assertEquals(RangedFetchStatus.TIMEOUT, result.status());
        assertEquals(1, result.bytesReceived());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
    }

    @Test
    void aFailingSinkIsNotADeviceFailure() throws Exception {
        serve(file(1_000), 1_000, Long.MAX_VALUE);
        OutputStream broken = new OutputStream() {
            @Override public void write(int b) throws IOException { throw new IOException("disk full"); }
            @Override public void write(byte[] b, int off, int len) throws IOException { throw new IOException("disk full"); }
        };
        RangedFetchResult result = read(0, 100, broken);
        assertEquals(RangedFetchStatus.SINK_FAILED, result.status());
        assertEquals(0, result.bytesReceived());
        verify(channel).disconnect();
    }

    @Test
    void unsafePathsAreRefusedBeforeAnyChannelOpens() throws Exception {
        for (String path : new String[] {"relative/messages", "/var/log/*", "/var/log/../../etc/shadow", "/var/log/a b"}) {
            RangedFetchResult result = transport.fetchRange(new SshTransportSession("synthetic", session),
                    new RangedFetchSpec(path, 0, 10), TIMEOUT, new ByteArrayOutputStream());
            assertEquals(RangedFetchStatus.REFUSED, result.status());
            assertEquals("PATH_UNSAFE", result.reasonCode());
        }
        verify(session, never()).openChannel(anyString());
    }

    @Test
    void gateRefusalThroughTheSystemLogEntryPointTouchesNoChannel() throws Exception {
        String base = "/opt/synthetic-host-fw01/fw1/log/fwd.elg";
        Set<String> stat = Set.of(base, PATH, base + ".gz");
        var request = new CpSyslogRangedRead.ReadRequest("cp_gaia_gateway", CpSyslogRangedRead.Family.CP_FWD_ELG,
                base, stat, base + ".gz", 0, 64, CpSyslogRangedRead.MAX_READ_BYTES);
        GateRow row = new GateRow(CpSyslogRangedRead.GATE_ID, "check_point", "cp_gaia_gateway", "not_applicable", "SFTP_GET",
                CpSyslogRangedRead.COMMAND_KEY, ActionClass.CLASS_0_READ, SignOffState.SIGNED_OFF, 60, "none", "", "",
                "", "", List.of(), "test fixture for V160");
        var session = new SshTransportSession("synthetic", this.session);
        RangedFetchResult gz = CpSyslogRangedRead.read(transport, session, key -> List.of(row), request,
                new ByteArrayOutputStream());
        assertEquals(RangedFetchStatus.REFUSED, gz.status());
        assertEquals("NAME_NOT_READ_ELIGIBLE", gz.reasonCode());
        RangedFetchResult noGate = CpSyslogRangedRead.read(transport, session, key -> List.of(),
                new CpSyslogRangedRead.ReadRequest("cp_gaia_gateway", CpSyslogRangedRead.Family.CP_FWD_ELG, base, stat,
                        PATH, 0, 64, CpSyslogRangedRead.MAX_READ_BYTES), new ByteArrayOutputStream());
        assertEquals("GATE_NOT_KNOWN", noGate.reasonCode());
        verify(this.session, never()).openChannel(anyString());

        // The same request with the signed-off row and an eligible name reaches the SFTP channel.
        serve(file(1_000), 1_000, Long.MAX_VALUE);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RangedFetchResult ok = CpSyslogRangedRead.read(transport, session, key -> List.of(row),
                new CpSyslogRangedRead.ReadRequest("cp_gaia_gateway", CpSyslogRangedRead.Family.CP_FWD_ELG, base, stat,
                        PATH, 936, 128, CpSyslogRangedRead.MAX_READ_BYTES), sink);
        assertEquals(RangedFetchStatus.END_OF_FILE, ok.status());
        assertEquals(64, sink.size());
    }

    @Test
    void theBoundedWholeFileFetchNamesOnlyItsClassAndKeepsTheBound() throws Exception {
        byte[] content = file(300);
        serve(content, 300, Long.MAX_VALUE);
        JobTranscript transcript = new JobTranscript();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RangedFetchResult whole, capped;
        try (JobTranscriptScope ignored = JobTranscriptScope.open(transcript)) {
            whole = transport.fetchBounded(new SshTransportSession("synthetic", session), new RangedFetchSpec(PATH, 0, 1_000),
                    TIMEOUT, sink, "cp_hcp_report");
            capped = transport.fetchBounded(new SshTransportSession("synthetic", session), new RangedFetchSpec(PATH, 0, 101),
                    TIMEOUT, new ByteArrayOutputStream(), "cp_hcp_report");
        }
        assertEquals(RangedFetchStatus.END_OF_FILE, whole.status());
        assertArrayEquals(content, sink.toByteArray());
        assertEquals(RangedFetchStatus.CAP_REACHED, capped.status(), "a file past the bound is never cut silently");
        assertEquals(101, capped.bytesReceived());
        String text = transcriptText(transcript);
        assertTrue(text.contains("bounded fetch class=cp_hcp_report"));
        assertFalse(text.contains("fwd.elg"), "no file name");
        assertFalse(text.contains("synthetic-host-fw01"), "no path");
    }

    private static String transcriptText(JobTranscript transcript) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transcript.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }
}
