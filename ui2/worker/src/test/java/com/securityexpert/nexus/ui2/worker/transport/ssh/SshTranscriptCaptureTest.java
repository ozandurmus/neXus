package com.securityexpert.nexus.ui2.worker.transport.ssh;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.PromptAnswer;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscript;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

class SshTranscriptCaptureTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void scpRecordsFileSizeHashAndTornTransfer(boolean torn) throws Exception {
        var session = mock(com.jcraft.jsch.Session.class);
        var channel = mock(com.jcraft.jsch.ChannelExec.class);
        when(session.openChannel("exec")).thenReturn(channel);
        when(channel.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        byte[] reply = (torn ? "C0644 4 synthetic.tgz\nabc" : "C0644 3 synthetic.tgz\nabc\0")
                .getBytes(StandardCharsets.UTF_8);
        when(channel.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(reply));
        var transport = new SshExecTransport(ref -> { throw new AssertionError("no credential lookup"); },
                ref -> java.util.Optional.empty());
        JobTranscript transcript = new JobTranscript();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (JobTranscriptScope ignored = JobTranscriptScope.open(transcript)) {
            var ssh = new SshTransportSession("synthetic", session);
            if (torn) assertThrows(java.io.IOException.class, () -> transport.scpFetch(ssh, "/synthetic.tgz",
                    size -> sink, 100, java.time.Duration.ofSeconds(1)));
            else assertEquals(3, transport.scpFetch(ssh, "/synthetic.tgz", size -> sink, 100, java.time.Duration.ofSeconds(1)));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transcript.writeTo(out);
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("file=/synthetic.tgz"));
        assertTrue(text.contains("size=3"));
        assertTrue(text.contains("sha256=ba7816bf"));
        assertTrue(text.contains(torn ? "stream ended after 3 of 4 bytes" : "status=completed"));
        assertTrue(text.contains("durationMs="));
        verify(channel).disconnect();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void sftpRecordsSuccessfulAndFailedTransfers(boolean failed) throws Exception {
        var session = mock(com.jcraft.jsch.Session.class);
        var channel = mock(com.jcraft.jsch.ChannelSftp.class);
        when(session.openChannel("sftp")).thenReturn(channel);
        when(channel.lstat("/synthetic.tgz")).thenReturn(mock(com.jcraft.jsch.SftpATTRS.class));
        doAnswer(call -> {
            java.io.OutputStream target = call.getArgument(1);
            target.write("abc".getBytes(StandardCharsets.UTF_8));
            if (failed) throw new com.jcraft.jsch.SftpException(4, "synthetic transfer failure");
            return null;
        }).when(channel).get(eq("/synthetic.tgz"), any(java.io.OutputStream.class));
        var transport = new SshExecTransport(ref -> { throw new AssertionError("no credential lookup"); },
                ref -> java.util.Optional.empty());
        JobTranscript transcript = new JobTranscript();
        try (JobTranscriptScope ignored = JobTranscriptScope.open(transcript)) {
            var result = transport.fetchStreaming(new SshTransportSession("synthetic", session),
                    new com.securityexpert.nexus.ui2.jobs.transport.FetchSpec("/synthetic.tgz", 100),
                    java.time.Duration.ofSeconds(1), new ByteArrayOutputStream());
            if (failed) assertInstanceOf(com.securityexpert.nexus.ui2.jobs.transport.FetchStreamResult.Failed.class, result);
            else assertInstanceOf(com.securityexpert.nexus.ui2.jobs.transport.FetchStreamResult.Fetched.class, result);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transcript.writeTo(out);
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("file=/synthetic.tgz"));
        assertTrue(text.contains("size=3"));
        assertTrue(text.contains("sha256=ba7816bf"));
        assertTrue(text.contains(failed ? "synthetic transfer failure" : "status=completed"));
    }

    @Test void recordsExitCodeTimingAndFailureReason() throws Exception {
        JobTranscript transcript = new JobTranscript();
        try (JobTranscriptScope ignored = JobTranscriptScope.open(transcript)) {
            SshExecTransport.recordStatus(new ExecResult.Completed("refused", 7), System.nanoTime());
            SshExecTransport.recordStatus(new ExecResult.ChannelFailed("synthetic channel failure"), System.nanoTime());
            SshExecTransport.recordStatus(new ExecResult.TimedOut(), System.nanoTime());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transcript.writeTo(out);
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("exit=7"));
        assertTrue(text.contains("durationMs="));
        assertTrue(text.contains("synthetic channel failure"));
        assertTrue(text.contains("timed out"));
    }

    @Test void capturesAnswerAndCredentialPlaceholderOnlyInsideScope() throws Exception {
        JobTranscript transcript = new JobTranscript();
        SshExecTransport.recordAnswer(new ExecResult.Completed("outside", 0));
        InteractiveShellSession.recordPromptReply("synthetic-secret".toCharArray());
        try (JobTranscriptScope ignored = JobTranscriptScope.open(transcript)) {
            JobTranscriptScope.add("ssh", "command", "show synthetic");
            InteractiveShellSession.recordPromptReply("synthetic-secret".toCharArray());
            SshExecTransport.recordAnswer(new ExecResult.Completed("synthetic answer synthetic-secret", 0),
                    java.util.List.of(new PromptAnswer("assword:", "synthetic-secret".toCharArray())));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transcript.writeTo(out);
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("show synthetic"));
        assertTrue(text.contains("synthetic answer"));
        assertTrue(text.contains("[credential]"));
        assertFalse(text.contains("outside"));
        assertFalse(text.contains("synthetic-secret"));
    }
}
