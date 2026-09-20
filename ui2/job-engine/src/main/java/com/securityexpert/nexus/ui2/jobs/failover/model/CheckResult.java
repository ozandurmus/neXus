package com.securityexpert.nexus.ui2.jobs.failover.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable outcome of an individual pre-flight check evaluation.
 */
public record CheckResult(
    String checkId,
    String name,
    String category,
    CheckStatus status,
    EnforcementPolicy enforcement,
    String summary,
    String remediationCode,
    Instant observedAt
) {
    public CheckResult {
        Objects.requireNonNull(checkId, "checkId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(enforcement, "enforcement must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        if (observedAt == null) {
            observedAt = Instant.now();
        }
    }

    public boolean isBlockingFailure() {
        return enforcement == EnforcementPolicy.BLOCKING && status != CheckStatus.PASS;
    }

    public static CheckResult pass(String checkId, String name, String category, EnforcementPolicy enforcement, String summary) {
        return new CheckResult(checkId, name, category, CheckStatus.PASS, enforcement, summary, null, Instant.now());
    }

    public static CheckResult fail(String checkId, String name, String category, EnforcementPolicy enforcement, String summary, String remediationCode) {
        return new CheckResult(checkId, name, category, CheckStatus.FAIL, enforcement, summary, remediationCode, Instant.now());
    }

    public static CheckResult warning(String checkId, String name, String category, String summary, String remediationCode) {
        return new CheckResult(checkId, name, category, CheckStatus.WARNING, EnforcementPolicy.ADVISORY, summary, remediationCode, Instant.now());
    }

    public static CheckResult insufficientEvidence(String checkId, String name, String category, EnforcementPolicy enforcement, String summary, String remediationCode) {
        return new CheckResult(checkId, name, category, CheckStatus.INSUFFICIENT_EVIDENCE, enforcement, summary, remediationCode, Instant.now());
    }

    public static CheckResult unsupported(String checkId, String name, String category, String summary, String remediationCode) {
        return new CheckResult(checkId, name, category, CheckStatus.UNSUPPORTED, EnforcementPolicy.BLOCKING, summary, remediationCode, Instant.now());
    }
}
