package com.securityexpert.nexus.ui2.jobs.transport;

/**
 * One {@code exec} step's request (contract §5: one-shot {@code exec_command}
 * channel). {@code pty} requests a pseudo-terminal on the exec channel --
 * default {@code false}, unchanged for every existing caller; some vendor
 * report commands (measured: Check Point's {@code cphaprob -a if}/
 * {@code cphaprob -a -m if} cluster-interface read) produce no output at all
 * over a plain, non-terminal exec channel and need one. {@code streamingExtensionMs}
 * allows one additional interactive wait while output is flowing; zero preserves
 * the existing fixed timeout. It never resends the command.
 */
public record ExecSpec(String command, boolean pty, long streamingExtensionMs) {

    public ExecSpec {
        if (streamingExtensionMs < 0 || streamingExtensionMs > 120_000)
            throw new IllegalArgumentException("STREAMING_EXTENSION_MUST_BE_0_TO_120000_MS");
    }

    public ExecSpec(String command, boolean pty) {
        this(command, pty, 0);
    }

    public ExecSpec(String command) {
        this(command, false);
    }
}
