package com.securityexpert.nexus.ui2.service.script;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.credential.CredentialRecord;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialRepository;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRecord;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;

/**
 * The credential-store side of Script Execution ({@code SCRIPT_EXECUTION_CONTRACT.md} Amendment 2026-11-18 item 5 and
 * Amendment 2026-11-18 (b) item 4): the display name of a declared reference (for aliases and their warnings) and, only
 * for {@link ScriptRunDispatchService}, the decrypted value of a reference a run's version declares. Nothing else in the
 * service reaches a script credential's value.
 */
public interface ScriptCredentialSource {

    /** A resolved declared credential. {@link #toString()} never prints the secret. */
    record Resolved(String refId, String displayName, String username, String secret) {
        public Resolved {
            Objects.requireNonNull(refId, "refId");
            displayName = displayName == null ? "" : displayName;
            username = username == null ? "" : username;
            Objects.requireNonNull(secret, "secret");
        }

        @Override
        public String toString() {
            return "Resolved[refId=" + refId + ", displayName=" + displayName + ", secret=[REDACTED]]";
        }
    }

    Optional<String> displayName(String refId);

    Optional<Resolved> resolve(String refId);

    /** Backed by the credential store: reference -> backend pointer -> credential row, decrypted with the store key. */
    static ScriptCredentialSource fromStore(
            CredentialReferenceRepository references, CredentialRepository credentials, CredentialStoreCipher cipher) {
        Objects.requireNonNull(credentials, "credentials");
        return new ScriptCredentialSource() {
            private Optional<CredentialRecord> record(String refId) {
                if (refId == null || refId.isBlank()) {
                    return Optional.empty();
                }
                String trimmed = refId.strip();
                String credentialId = references == null ? trimmed : references.find(trimmed)
                        .map(CredentialReferenceRecord::backendPointer)
                        .orElse(trimmed);
                return credentials.findById(credentialId);
            }

            @Override
            public Optional<String> displayName(String refId) {
                return record(refId).map(CredentialRecord::displayName);
            }

            @Override
            public Optional<Resolved> resolve(String refId) {
                if (cipher == null) {
                    return Optional.empty();
                }
                return record(refId).map(c -> new Resolved(refId.strip(), c.displayName(), c.username(),
                        cipher.decrypt(c.encryptedSecret())));
            }
        };
    }
}
