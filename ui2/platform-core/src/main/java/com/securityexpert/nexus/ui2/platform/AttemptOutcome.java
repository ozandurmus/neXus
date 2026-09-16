package com.securityexpert.nexus.ui2.platform;

/**
 * The mechanism-agnostic result of one {@link Mechanism#attempt} call
 * (C3A contract §2.1). The login handler branches on {@link #getClass()}
 * of this sealed type, never on {@code mechanismId}, which is what makes
 * outcome reporting mechanism-independent (§2.3).
 */
public sealed interface AttemptOutcome {

    /** @param resolvedActorFingerprint present only on success (§2.1) */
    record Success(String resolvedActorFingerprint, DirectoryObservation directoryObservation) implements AttemptOutcome {
        public Success(String resolvedActorFingerprint) { this(resolvedActorFingerprint, null); }
        @Override public String toString() { return "Success[redacted]"; }
    }

    /** A closed-vocabulary reason, per mechanism -- never surfaced past the HTTP layer (§2.3, §5.3). */
    record Refused(String reasonCode) implements AttemptOutcome {
    }

    /** Local has no such state (§5); ldap uses it for a directory outage (C3 §2.2). */
    record MechanismUnavailable(String reasonCode) implements AttemptOutcome {
    }

    static AttemptOutcome success(String resolvedActorFingerprint) {
        return new Success(resolvedActorFingerprint);
    }

    static AttemptOutcome refused(String reasonCode) {
        return new Refused(reasonCode);
    }

    static AttemptOutcome mechanismUnavailable(String reasonCode) {
        return new MechanismUnavailable(reasonCode);
    }
}
