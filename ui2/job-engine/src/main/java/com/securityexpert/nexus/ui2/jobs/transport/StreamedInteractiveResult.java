package com.securityexpert.nexus.ui2.jobs.transport;

/**
 * How one streamed interactive command ended. {@code lines} and {@code bytes} count what reached the sink (bytes are
 * UTF-8 of the lines, one terminator each); {@code pagesAnswered} counts the pager prompts answered in session.
 */
public record StreamedInteractiveResult(Kind kind, long lines, long bytes, int pagesAnswered) {

    public enum Kind {
        /** The prompt came back. */
        COMPLETED,
        /** The whole answer was one short CLI refusal (a vendor "invalid input" or "unknown action" text). */
        CLI_ERROR,
        /** The deadline passed before the prompt came back; the shell is closed. */
        TIMED_OUT,
        /** The channel closed before the prompt came back. */
        SESSION_LOST,
        /** The sink threw: the command was interrupted and the shell closed. */
        SINK_ABORTED,
        /** The command was never sent. */
        NOT_SENT
    }
}
