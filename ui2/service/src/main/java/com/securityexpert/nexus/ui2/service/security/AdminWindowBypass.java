package com.securityexpert.nexus.ui2.service.security;

import java.time.Instant;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * Who holds the administrator's manual-submission window bypass (PO decision 2026-10-20): an actor permitted
 * {@code security_admin} or {@code operation_admin} who is not a replay viewer (aiview and its machine session keep
 * the windows; the root administrator, permitted every token by the root authority, is not a replay viewer). An RBAC
 * evaluation that fails resolves to no bypass, so the request keeps the window gate.
 */
public final class AdminWindowBypass {

    private final RbacEvaluator rbac;

    public AdminWindowBypass(RbacEvaluator rbac) {
        this.rbac = java.util.Objects.requireNonNull(rbac, "rbac");
    }

    /** {@link ManualAdmission#SECURITY_ADMIN}, {@link ManualAdmission#OPERATION_ADMIN}, or empty. */
    public Optional<String> role(String actorFingerprint) {
        if (actorFingerprint == null || actorFingerprint.isBlank()) return Optional.empty();
        try {
            Instant now = Instant.now();
            var replay = rbac.evaluate(actorFingerprint, Optional.of(RoleToken.REPLAY_VIEWER), now);
            if (replay.outcome() == AuthzOutcome.PERMITTED
                    && replay.authority().filter(RbacEvaluator.ROOT_AUTHORITY::equals).isEmpty()) return Optional.empty();
            if (permitted(actorFingerprint, RoleToken.SECURITY_ADMIN, now)) return Optional.of(ManualAdmission.SECURITY_ADMIN);
            if (permitted(actorFingerprint, RoleToken.OPERATION_ADMIN, now)) return Optional.of(ManualAdmission.OPERATION_ADMIN);
            return Optional.empty();
        } catch (RuntimeException notEvaluable) {
            return Optional.empty();
        }
    }

    private boolean permitted(String actor, String token, Instant now) {
        return rbac.evaluate(actor, Optional.of(token), now).outcome() == AuthzOutcome.PERMITTED;
    }
}
