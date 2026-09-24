package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Optional;

/** A device's second secrets (V64), by purpose: the credential-store reference, never the value. */
public interface DeviceSecretReferenceRepository {

    String EXPORT_PASSPHRASE = "export_passphrase";
    /** V69: a Radware Cyber Controller's SFTP receiver credential (HOST-A's chrooted nexus-cc). */
    String BACKUP_RECEIVER = "backup_receiver";

    Optional<String> find(String deviceId, String purpose);

    void set(String deviceId, String purpose, String credentialReferenceId, String actorFingerprint, String actionId);

    DeviceSecretReferenceRepository NONE = new DeviceSecretReferenceRepository() {
        @Override
        public Optional<String> find(String deviceId, String purpose) {
            return Optional.empty();
        }

        @Override
        public void set(String deviceId, String purpose, String credentialReferenceId, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("no device secret store in this composition");
        }
    };
}
