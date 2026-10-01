package com.securityexpert.nexus.ui2.platform;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The credential store (2026-09-14 PO decision record section 3, CS-1..CS-5):
 * device and management-plane credentials created in the product's own
 * interface (and CLI), encrypted at rest, never returned by anything. The
 * HTTP API and the CLI share this exact interface and its one
 * implementation, mirroring {@link LocalIdentityAdministrationPort}'s own
 * pattern exactly so the two paths cannot drift.
 *
 * <p>A credential's secret material is never a field, a return type, or a
 * {@code toString()} target anywhere on this interface -- only
 * {@link #create} and {@link #replaceSecret} ever see it, as a caller
 * -supplied array the implementation must zero once it is encrypted.</p>
 */
public interface CredentialStorePort {

    /** Stored credential kinds. */
    enum CredentialKind {
        SSH_PASSWORD("ssh_password"),
        SSH_PRIVATE_KEY("ssh_private_key"),
        API_PASSWORD("api_password"),
        SNMP_V1_V2C("snmp_v1_v2c"),
        SNMP_V3("snmp_v3");

        private final String wireValue;

        CredentialKind(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static CredentialKind fromWireValue(String wireValue) {
            for (CredentialKind kind : values()) {
                if (kind.wireValue.equals(wireValue)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unknown credential kind: " + wireValue);
        }
    }

    /** Non-secret SNMP v3 configuration. Secrets use the existing encrypted slots. */
    record SnmpSettings(String securityLevel, String authProtocol, String privProtocol) {
        public SnmpSettings {
            if (!java.util.Set.of("noAuthNoPriv", "authNoPriv", "authPriv").contains(
                    securityLevel == null ? "" : securityLevel)) {
                throw new IllegalArgumentException("invalid SNMP security level");
            }
            boolean auth = !securityLevel.equals("noAuthNoPriv");
            boolean privacy = securityLevel.equals("authPriv");
            if (auth != (authProtocol != null) || privacy != (privProtocol != null)
                    || (auth && !java.util.Set.of("SHA-256", "SHA-384", "SHA-512", "SHA-224", "SHA1", "MD5")
                            .contains(authProtocol))
                    || (privacy && !java.util.Set.of("AES-128", "AES-192", "AES-256", "DES").contains(privProtocol))) {
                throw new IllegalArgumentException("invalid SNMP protocols for security level");
            }
        }
    }

    /**
     * CS-1/CS-3: exactly the fields an operator may see. Never an encrypted
     * secret, an encrypted passphrase, or the envelope key id.
     */
    record CredentialView(
            String credentialId,
            String credentialReferenceId,
            String displayName,
            CredentialKind kind,
            String username,
            boolean allowsCheckPoint,
            boolean allowsPaloAlto,
            Instant createdAt,
            Instant secretSetAt,
            SnmpSettings snmp) {
        public CredentialView(String credentialId, String credentialReferenceId, String displayName,
                CredentialKind kind, String username, boolean allowsCheckPoint, boolean allowsPaloAlto,
                Instant createdAt, Instant secretSetAt) {
            this(credentialId, credentialReferenceId, displayName, kind, username, allowsCheckPoint,
                    allowsPaloAlto, createdAt, secretSetAt, null);
        }
    }

    sealed interface ReplaceSecretResult {
        record Ok(CredentialView view) implements ReplaceSecretResult {
        }

        record NotFound() implements ReplaceSecretResult {
        }

        record PassphraseNotAllowed() implements ReplaceSecretResult {
        }
    }

    sealed interface DeleteResult {
        record Ok() implements DeleteResult {
        }

        record NotFound() implements DeleteResult {
        }

        /**
         * CS-4: the distinct, non-identity-bearing refusal for deleting a
         * credential a {@code credential_references} row still points at
         * (through it, a device).
         */
        record CredentialInUse() implements DeleteResult {
        }
    }

    /**
     * SNMP uses secret for community/authentication and passphrase for privacy.
     * CS-1/CS-2: {@code secret} (and {@code passphrase}, used for an SSH private-key passphrase or SNMP v3
     * privacy secret) are consumed -- the caller must
     * consider both zeroed after this call returns. Also creates the
     * {@code credential_references} row every existing consumer of the
     * reference model needs (backend_pointer = this credential's own id).
     */
    default CredentialView create(String actingAdminActorFingerprint, String displayName, CredentialKind kind,
            String username, boolean allowsCheckPoint, boolean allowsPaloAlto, char[] secret,
            Optional<char[]> passphrase) {
        return create(actingAdminActorFingerprint, displayName, kind, username, allowsCheckPoint,
                allowsPaloAlto, secret, passphrase, null);
    }

    CredentialView create(String actor, String displayName, CredentialKind kind, String username,
            boolean allowsCheckPoint, boolean allowsPaloAlto, char[] secret, Optional<char[]> passphrase,
            SnmpSettings snmp);

    List<CredentialView> list();

    /** CS-2: overwrites the encrypted secret (and passphrase); the caller must consider both zeroed after this call returns. */
    default ReplaceSecretResult replaceSecret(String actingAdminActorFingerprint, String credentialId, char[] secret,
            Optional<char[]> passphrase) {
        return replaceSecret(actingAdminActorFingerprint, credentialId, secret, passphrase, null, null);
    }

    ReplaceSecretResult replaceSecret(String actor, String credentialId, char[] secret,
            Optional<char[]> passphrase, String username, SnmpSettings snmp);

    /** CS-4: refuses with {@link DeleteResult.CredentialInUse} while a device still uses this credential's reference. */
    DeleteResult delete(String actingAdminActorFingerprint, String credentialId);
}
