package com.securityexpert.nexus.ui2.jobs.transport;

/**
 * The raw, unparsed outcome of one {@code exec} call -- the transport
 * "reports what happened" (contract §5) and never interprets output beyond
 * what the caller already asked it to bound (byte cap). The parser
 * framework (§6), not the transport, turns this into a {@code
 * ParsedStepResult}.
 */
public sealed interface ExecResult {

    record Completed(String output, int exitStatus) implements ExecResult {
    }

    record TimedOut() implements ExecResult {
    }

    record ChannelFailed(String reason) implements ExecResult {
    }
}
