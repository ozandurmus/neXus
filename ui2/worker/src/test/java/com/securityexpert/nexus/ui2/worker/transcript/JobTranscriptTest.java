package com.securityexpert.nexus.ui2.worker.transcript;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JobTranscriptTest {
    private static String contents(JobTranscript transcript) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transcript.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test void ordersEntriesAndClearsScope() throws Exception {
        JobTranscript transcript = new JobTranscript();
        try (JobTranscriptScope ignored = JobTranscriptScope.open(transcript)) {
            JobTranscriptScope.add("ssh", "command", "show synthetic");
            JobTranscriptScope.add("ssh", "answer", "synthetic answer");
        }
        JobTranscriptScope.add("ssh", "answer", "outside");
        String text = contents(transcript);
        assertEquals(2, transcript.size());
        assertTrue(text.contains("\"seq\":1"));
        assertTrue(text.contains("\"seq\":2"));
        assertTrue(text.indexOf("show synthetic") < text.indexOf("synthetic answer"));
        assertFalse(text.contains("outside"));
        assertNull(JobTranscriptScope.current());
    }

    @Test void capsAt64MbWithOneNote() throws Exception {
        JobTranscript transcript = new JobTranscript();
        transcript.add("ssh", "answer", "x".repeat(64 * 1024 * 1024));
        transcript.add("ssh", "answer", "later");
        String text = contents(transcript);
        assertEquals(1, transcript.size());
        assertTrue(text.contains("transcript truncated at 64 MB"));
        assertFalse(text.contains("later"));
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 64 * 1024 * 1024);
    }

    @Test void replacesSentCredentialsAndDropsCredentialHeaders() {
        String secret = "synthetic-secret";
        assertFalse(JobTranscript.safeJson("{\"nested\":{\"password\":\"" + secret
                + "\",\"token\":\"" + secret + "\"},\"plain\":\"visible\"}").contains(secret));
        assertTrue(JobTranscript.safeJson("{\"password\":\"" + secret + "\"}").contains("[credential]"));
        assertFalse(JobTranscript.safeForm(Map.of("passwd", secret, "name", "synthetic")).contains(secret));
        assertFalse(JobTranscript.withoutSentSecrets("echo " + secret,
                "{\"nested\":{\"password\":\"" + secret + "\"}}", "application/json").contains(secret));
        assertFalse(JobTranscript.safePath("/api?api_key=" + secret).contains(secret));
        assertFalse(JobTranscript.safePath("https://user:" + secret + "@192.0.2.10/api").contains(secret));
        assertFalse(JobTranscript.safeHeaders(Map.of("Authorization", List.of(secret), "Set-Cookie", List.of(secret),
                "X-CSRF-Token", List.of(secret), "X-API-Key", List.of(secret), "X-PAN-KEY", List.of(secret),
                "Content-Type", List.of("text/plain")))
                .contains(secret));
        assertTrue(JobTranscript.safeHeaders(Map.of("Content-Type", List.of("text/plain"))).contains("Content-Type"));
        assertFalse(JobTranscript.safeResponseBody("application/json", "{\"session\":\"" + secret
                + "\",\"configuration\":\"visible\"}").contains(secret));
        assertFalse(JobTranscript.safeResponseBody("text/html", "<input name='xsauth' value='" + secret + "'>")
                .contains(secret));
    }
}
