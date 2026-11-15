package com.securityexpert.nexus.ui2.jobs.transport;

import java.io.IOException;

/**
 * Receives the output of {@link DeviceTransport#execInteractiveStreaming} one finished line at a time, without the
 * line terminator, with terminal control sequences, pager prompts and the command echo already removed. Throwing
 * {@link IOException} (a size bound, a redaction failure) stops the command: the transport interrupts the device
 * command, closes the shell and reports {@link StreamedInteractiveResult.Kind#SINK_ABORTED}.
 */
@FunctionalInterface
public interface LineSink {

    void line(String line) throws IOException;
}
