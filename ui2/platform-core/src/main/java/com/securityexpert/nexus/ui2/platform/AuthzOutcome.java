package com.securityexpert.nexus.ui2.platform;

/**
 * The four {@code D7} authorization outcomes, precisely (C3 §5.1). Never a
 * fifth value, never a silent downgrade of {@link #AUTHZ_NOT_EVALUATED} to
 * {@link #NO_APPLICABLE_AUTHORITY} ({@code LD-2}'s banned transition,
 * {@code AG-4}).
 */
public enum AuthzOutcome {
    /** Token bound, actor's resolved group set contains the bound group reference. Proceeds. */
    PERMITTED,
    /** The action declares no required role token at all. Proceeds. */
    NO_APPLICABLE_AUTHORITY,
    /** Token bound to at least one row, actor's resolved group set does not contain it. Refuses. */
    DENIED,
    /** No active binding exists for the token, or the actor's group set is stale/missing. Refuses. */
    AUTHZ_NOT_EVALUATED;

    public boolean proceeds() {
        return this == PERMITTED || this == NO_APPLICABLE_AUTHORITY;
    }
}
