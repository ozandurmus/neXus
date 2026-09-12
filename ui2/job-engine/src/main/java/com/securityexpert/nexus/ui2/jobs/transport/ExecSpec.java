package com.securityexpert.nexus.ui2.jobs.transport;

/** One {@code exec} step's request (contract §5: one-shot {@code exec_command} channel). */
public record ExecSpec(String command) {
}
