package com.securityexpert.nexus.ui2.service.device;

import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;

/**
 * One {@code action_affordance} map entry (contract §6.1). {@code outcome}
 * is always present -- one of the four {@code E4} outcomes; {@code
 * authority}/{@code reasonCode} are present only where the evaluation
 * ({@code RbacEvaluator.Decision}) itself populates them (never fabricated
 * here).
 */
public record ActionAffordanceView(AuthzOutcome outcome, Optional<String> authority, Optional<String> reasonCode) {

    public ActionAffordanceView {
        java.util.Objects.requireNonNull(outcome, "outcome");
        java.util.Objects.requireNonNull(authority, "authority");
        java.util.Objects.requireNonNull(reasonCode, "reasonCode");
    }
}
