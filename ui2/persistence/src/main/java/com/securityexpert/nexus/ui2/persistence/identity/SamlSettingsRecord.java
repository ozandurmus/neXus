package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * The one {@code saml_settings} row (V241). Everything in it is public SAML data except {@code spPrivateKeyEncrypted},
 * which is AES-256-GCM ciphertext under the credential store key and never leaves the server. Equality compares the
 * ciphertext by content so a reload is detected only when a value really changed.
 */
public record SamlSettingsRecord(boolean enabled, String spBaseUrl, String spEntityId, String idpEntityId, String idpSsoUrl,
        String idpSsoBinding, String idpSigningCertPem, String groupClaimAttribute, String adminGroupValue,
        String usersGroupValue, int clockSkewSeconds, boolean signAuthnRequests, String spCertPem,
        byte[] spPrivateKeyEncrypted, String spPrivateKeyKeyId, Instant updatedAt, String updatedBy) {

    public SamlSettingsRecord {
        spPrivateKeyEncrypted = spPrivateKeyEncrypted == null ? null : spPrivateKeyEncrypted.clone();
    }

    @Override
    public byte[] spPrivateKeyEncrypted() {
        return spPrivateKeyEncrypted == null ? null : spPrivateKeyEncrypted.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SamlSettingsRecord o)) return false;
        return enabled == o.enabled && clockSkewSeconds == o.clockSkewSeconds && signAuthnRequests == o.signAuthnRequests
                && Objects.equals(spBaseUrl, o.spBaseUrl) && Objects.equals(spEntityId, o.spEntityId)
                && Objects.equals(idpEntityId, o.idpEntityId) && Objects.equals(idpSsoUrl, o.idpSsoUrl)
                && Objects.equals(idpSsoBinding, o.idpSsoBinding) && Objects.equals(idpSigningCertPem, o.idpSigningCertPem)
                && Objects.equals(groupClaimAttribute, o.groupClaimAttribute)
                && Objects.equals(adminGroupValue, o.adminGroupValue) && Objects.equals(usersGroupValue, o.usersGroupValue)
                && Objects.equals(spCertPem, o.spCertPem) && Arrays.equals(spPrivateKeyEncrypted, o.spPrivateKeyEncrypted)
                && Objects.equals(spPrivateKeyKeyId, o.spPrivateKeyKeyId) && Objects.equals(updatedAt, o.updatedAt)
                && Objects.equals(updatedBy, o.updatedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, spBaseUrl, spEntityId, idpEntityId, idpSsoUrl, idpSsoBinding, idpSigningCertPem,
                groupClaimAttribute, adminGroupValue, usersGroupValue, clockSkewSeconds, signAuthnRequests, spCertPem,
                Arrays.hashCode(spPrivateKeyEncrypted), spPrivateKeyKeyId, updatedAt, updatedBy);
    }

    @Override
    public String toString() {
        return "SamlSettingsRecord[redacted]";
    }
}
