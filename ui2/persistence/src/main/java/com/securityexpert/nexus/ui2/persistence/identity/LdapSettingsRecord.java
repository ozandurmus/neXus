package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The one {@code ldap_settings} row (V205). {@code bindPasswordEncrypted} is AES-256-GCM ciphertext under the
 * credential store key; it never leaves the server. Equality compares the ciphertext by content so a reload is
 * detected only when a value really changed.
 */
public record LdapSettingsRecord(boolean enabled, String serverType, List<String> servers, int port, String domainDn,
        String adminGroupDn, String usersGroupDn, String bindDn, byte[] bindPasswordEncrypted, String bindPasswordKeyId,
        int connectTimeoutSeconds, boolean ldaps, String trustMode, String trustPem, Instant updatedAt, String updatedBy) {

    public LdapSettingsRecord {
        servers = servers == null ? List.of() : List.copyOf(servers);
        bindPasswordEncrypted = copy(bindPasswordEncrypted);
    }

    private static byte[] copy(byte[] bytes) {
        return bytes == null ? null : bytes.clone();
    }

    @Override
    public byte[] bindPasswordEncrypted() {
        return copy(bindPasswordEncrypted);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof LdapSettingsRecord o)) return false;
        return enabled == o.enabled && port == o.port && connectTimeoutSeconds == o.connectTimeoutSeconds
                && ldaps == o.ldaps && Objects.equals(serverType, o.serverType) && servers.equals(o.servers)
                && Objects.equals(domainDn, o.domainDn) && Objects.equals(adminGroupDn, o.adminGroupDn)
                && Objects.equals(usersGroupDn, o.usersGroupDn) && Objects.equals(bindDn, o.bindDn)
                && Arrays.equals(bindPasswordEncrypted, o.bindPasswordEncrypted)
                && Objects.equals(bindPasswordKeyId, o.bindPasswordKeyId) && Objects.equals(trustMode, o.trustMode)
                && Objects.equals(trustPem, o.trustPem) && Objects.equals(updatedAt, o.updatedAt)
                && Objects.equals(updatedBy, o.updatedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, port, connectTimeoutSeconds, ldaps, serverType, servers, domainDn, adminGroupDn,
                usersGroupDn, bindDn, Arrays.hashCode(bindPasswordEncrypted), bindPasswordKeyId, trustMode, trustPem,
                updatedAt, updatedBy);
    }

    @Override
    public String toString() {
        return "LdapSettingsRecord[redacted]";
    }
}
