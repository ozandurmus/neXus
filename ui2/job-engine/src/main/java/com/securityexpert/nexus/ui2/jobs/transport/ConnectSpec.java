package com.securityexpert.nexus.ui2.jobs.transport;

import java.util.Optional;

/**
 * Contract §5: {@code connect}'s expectation under {@code ssh_exec} is
 * authentication success plus an optional banner match -- never a
 * shell-prompt regex. {@code trustRuleRef} is checked before any command is
 * sent (host-key verification, §5); {@code credentialRef} is the opaque
 * {@code execution_credential_ref}, never a raw secret.
 */
public record ConnectSpec(String credentialRef, String trustRuleRef, Optional<String> bannerRegex) {
}
