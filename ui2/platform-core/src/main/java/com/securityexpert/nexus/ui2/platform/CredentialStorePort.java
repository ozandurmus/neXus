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

    /** CS-1's closed vocabulary. */
    enum CredentialKind {
        SSH_PASSWORD("ssh_password"),
        SSH_PRIVATE_KEY("ssh_private_key"),
        API_PASSWORD("api_password");

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
            Instant secretSetAt) {
    }

    sealed interface ReplaceSecretResult {
        record Ok(CredentialView view) implements ReplaceSecretResult {
        }

        record NotFound() implements ReplaceSecretResult {
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
     * CS-1/CS-2: {@code secret} (and {@code passphrase}, only meaningful for
     * {@link CredentialKind#SSH_PRIVATE_KEY}) are consumed -- the caller must
     * consider both zeroed after this call returns. Also creates the
     * {@code credential_references} row every existing consumer of the
     * reference model needs (backend_pointer = this credential's own id).
     */
    CredentialView create(String actingAdminActorFingerprint, String displayName, CredentialKind kind,
            String username, boolean allowsCheckPoint, boolean allowsPaloAlto, char[] secret,
            Optional<char[]> passphrase);

    List<CredentialView> list();

    /** CS-2: overwrites the encrypted secret (and passphrase); the caller must consider both zeroed after this call returns. */
    ReplaceSecretResult replaceSecret(String actingAdminActorFingerprint, String credentialId, char[] secret,
            Optional<char[]> passphrase);

    /** CS-4: refuses with {@link DeleteResult.CredentialInUse} while a device still uses this credential's reference. */
    DeleteResult delete(String actingAdminActorFingerprint, String credentialId);
}
