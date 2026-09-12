package com.securityexpert.nexus.ui2.platform;

import java.util.Set;

/**
 * The re-validation adapter port (C3 §2.1/§4.4). Shared across modules,
 * declared in {@code platform-core} (adjudication F10); implemented by
 * {@code ldap-adapter}'s {@code UnboundIdRevalidationAdapter}.
 *
 * <p><b>Inert while {@code directory_posture_enabled = false}</b> (C3
 * §4.4.3): no implementation may open a connection while disabled — this is
 * this movement's own responsibility, not merely documentation (AC-12).</p>
 */
public interface LdapRevalidationPort {

    boolean directoryPostureEnabled();

    /**
     * Re-reads an actor's group membership from the directory service
     * account. Never called while {@link #directoryPostureEnabled()} is
     * {@code false} — callers must check first; an implementation may also
     * defensively refuse.
     */
    Result<Set<String>> revalidate(String actorFingerprint, String bindDn);
}
