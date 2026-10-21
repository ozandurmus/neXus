package com.securityexpert.nexus.ui2.jobs.transport;

import java.io.IOException;
import java.time.Duration;

/**
 * One raw interactive shell channel on an already open, trusted {@link TransportSession}: one login shell on the
 * device for as long as the channel is open (docs/design/CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md). It moves bytes
 * only: no prompt detection, no framing, no interpretation -- the caller frames its own commands. Never used
 * concurrently.
 */
public interface ShellChannel extends AutoCloseable {

    /**
     * Sends {@code line} followed by a newline. The adapter checks endpoint admission and job cancellation first, as it
     * does before every {@code exec}. {@code line} must not contain CR or LF.
     */
    void sendLine(String line) throws IOException;

    /**
     * Reads at most {@code buffer.length} bytes, waiting at most {@code wait} for the first one. Returns the number of
     * bytes read, {@code 0} when nothing arrived in time, or {@code -1} when the channel is closed.
     */
    int read(byte[] buffer, Duration wait) throws IOException;

    /** Closes the channel; the device's shell ends. Idempotent, never throws. */
    @Override
    void close();
}
