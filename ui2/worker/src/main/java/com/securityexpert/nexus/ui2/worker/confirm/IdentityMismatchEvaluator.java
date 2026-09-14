package com.securityexpert.nexus.ui2.worker.confirm;

import java.util.Objects;
import java.util.Optional;

/**
 * 13F section 2 / contract EC-6..EC-9: compares a presented identity
 * against the recorded baseline and decides the outcome. Never performed
 * against the management plane -- this movement's confirm has none to
 * compare against (V12's own migration comment) -- only against the
 * device's own previously recorded identity (EC-5).
 *
 * <p>The strict-refuse posture (EC-8, 13F ID-M3) is a caller-supplied
 * property, default {@code false}; this evaluator does not read it from
 * anywhere itself, so no default can silently drift.</p>
 */
public final class IdentityMismatchEvaluator {

    private IdentityMismatchEvaluator() {
    }

    public enum Decision {
        /** EC-5: first contact -- nothing recorded yet to compare against. This confirm establishes the baseline. */
        NO_BASELINE,
        /** The presented identity matches the recorded baseline exactly. */
        MATCH,
        /** EC-6/EC-7: connects anyway, surfaced as a visible warning, audited, continues to ENROLLED. */
        WARN_AND_CONTINUE,
        /** EC-8: the strict-refuse posture is on -- this contact's outcome is a refusal, never a silent continue. */
        REFUSE
    }

    public static Decision evaluate(Optional<PresentedIdentity> recordedIdentity, PresentedIdentity presentedIdentity,
            boolean strictRefuseEnabled) {
        Objects.requireNonNull(recordedIdentity, "recordedIdentity");
        Objects.requireNonNull(presentedIdentity, "presentedIdentity");
        if (recordedIdentity.isEmpty()) {
            return Decision.NO_BASELINE;
        }
        // EC-9: comparison only, never a silent re-baseline -- this method never returns the recorded
        // identity or mutates it; the caller decides separately whether to persist a mismatch marker.
        boolean matches = recordedIdentity.get().primary().equals(presentedIdentity.primary());
        if (matches) {
            return Decision.MATCH;
        }
        return strictRefuseEnabled ? Decision.REFUSE : Decision.WARN_AND_CONTINUE;
    }
}
