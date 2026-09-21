package com.securityexpert.nexus.ui2.jobs.transport;

/**
 * One {@code exec} step's request (contract §5: one-shot {@code exec_command}
 * channel). {@code pty} requests a pseudo-terminal on the exec channel --
 * default {@code false}, unchanged for every existing caller; some vendor
 * report commands (measured: Check Point's {@code cphaprob -a if}/
 * {@code cphaprob -a -m if} cluster-interface read) produce no output at all
 * over a plain, non-terminal exec channel and need one.
 */
public record ExecSpec(String command, boolean pty) {

    public ExecSpec(String command) {
        this(command, false);
    }
}
