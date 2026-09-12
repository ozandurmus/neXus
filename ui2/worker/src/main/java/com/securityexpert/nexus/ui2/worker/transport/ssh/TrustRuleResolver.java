package com.securityexpert.nexus.ui2.worker.transport.ssh;

import java.util.Optional;

/**
 * Resolves a capability's {@code trust_rule_ref} to the expected host key
 * fingerprint (contract §5: "verifies the host key against the
 * capability's trust_rule_ref before any command is sent"). {@code empty}
 * means no trust rule is registered for this ref -- {@link
 * HostKeyVerifier} treats that as a definite rejection (fail-closed,
 * AGENTS.md "Check Point" note: "the adapter never defaults to accept-any
 * or trust-on-first-use"), never as "skip verification."
 */
public interface TrustRuleResolver {

    Optional<String> resolveExpectedFingerprint(String trustRuleRef);
}
