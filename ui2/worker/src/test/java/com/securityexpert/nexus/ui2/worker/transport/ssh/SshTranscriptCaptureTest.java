package com.securityexpert.nexus.ui2.worker.transport.ssh;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.PromptAnswer;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscript;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

class SshTranscriptCaptureTest {
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
