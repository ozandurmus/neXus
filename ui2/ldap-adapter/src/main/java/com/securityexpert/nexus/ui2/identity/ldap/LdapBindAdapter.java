package com.securityexpert.nexus.ui2.identity.ldap;

import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;

import com.securityexpert.nexus.ui2.platform.Result;

/**
 * UnboundID-backed operator-bind adapter (C3 §2, §4.4). This module is an
 * identity adapter only: it never depends on {@code service}, {@code
 * worker}, or {@code scheduler} (DIR-5), and it exposes no web controller.
 */
public final class LdapBindAdapter {

    private final String host;
    private final int port;

    public LdapBindAdapter(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public Result<Boolean> bind(String bindDn, String password) {
        try (LDAPConnection connection = new LDAPConnection(host, port)) {
            connection.bind(bindDn, password);
            return Result.ok(true);
        } catch (LDAPException e) {
            return Result.err("ldap_bind_failed", "operator bind was rejected");
        }
    }
}
