package com.securityexpert.nexus.ui2.worker.transcript;

/** Thread-bound only for the duration of one backup job. */
public final class JobTranscriptScope implements AutoCloseable {
    private static final ThreadLocal<JobTranscript> ACTIVE = new ThreadLocal<>();

    private JobTranscriptScope(JobTranscript transcript) {
        if (ACTIVE.get() != null) throw new IllegalStateException("transcript scope already active");
        ACTIVE.set(transcript);
    }

    public static JobTranscriptScope open(JobTranscript transcript) { return new JobTranscriptScope(transcript); }
    public static JobTranscript current() { return ACTIVE.get(); }

    public static void add(String channel, String kind, String text) {
        JobTranscript transcript = ACTIVE.get();
        if (transcript != null) {
            try { transcript.add(channel, kind, text); }
            catch (RuntimeException ignored) { /* Recording must never change a device job's outcome. */ }
        }
    }

    @Override public void close() { ACTIVE.remove(); }
}
