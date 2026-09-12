package com.securityexpert.nexus.ui2.identity.ldap;

/**
 * Thrown when TLS trust material is missing or unreadable at startup
 * (C3 §2.2): the component fails closed and refuses to start. The message
 * names only the failure category, never a file path or a credential value.
 */
public final class LdapStartupException extends RuntimeException {

    public LdapStartupException(String reason) {
        super("ldap operator-bind adapter failed to start: " + reason);
    }

    public LdapStartupException(String reason, Throwable cause) {
        super("ldap operator-bind adapter failed to start: " + reason, cause);
    }
}
