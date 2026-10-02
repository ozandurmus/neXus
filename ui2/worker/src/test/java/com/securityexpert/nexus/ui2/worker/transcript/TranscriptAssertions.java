package com.securityexpert.nexus.ui2.worker.transcript;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

public final class TranscriptAssertions {
    private TranscriptAssertions() {}

    public static <T> T capture(Supplier<T> run, String... steps) {
        JobTranscript transcript = new JobTranscript();
        T result;
        try (JobTranscriptScope ignored = JobTranscriptScope.open(transcript)) {
            result = run.get();
        }
        assertNull(JobTranscriptScope.current(), "scope must not leak into the next job");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> transcript.writeTo(out));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.size() > 1, "a backup needs meaningful steps, not an empty artefact");
        for (String step : steps) assertTrue(text.contains(step), "missing transcript step: " + step);
        assertFalse(text.contains("wrappedDataKey"));
        return result;
    }
}
