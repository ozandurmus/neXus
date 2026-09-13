package com.securityexpert.nexus.ui2.platform;

/**
 * The authentication mechanism registry's own interface (C3A contract §2.1).
 * {@code local} and {@code ldap} are both implementations of this one shape;
 * adding a member (e.g. a later {@code radius}/{@code tacacs}) is adding an
 * implementation registered under a new {@link #mechanismId()}, never a
 * change to the login flow, session model, or RBAC model.
 */
public interface Mechanism {

    /** Closed vocabulary per C3A §2.1: {@code "local" | "ldap"} at Phase 1. */
    String mechanismId();

    /**
     * @param identity   the submitted username -- never logged as a literal
     *                    value beyond what the mechanism's own failure
     *                    reporting already permits
     * @param credential the submitted password, in a mutable buffer the
     *                    implementation must treat as consumed after this
     *                    call returns, regardless of outcome
     */
    AttemptOutcome attempt(String identity, char[] credential);
}
