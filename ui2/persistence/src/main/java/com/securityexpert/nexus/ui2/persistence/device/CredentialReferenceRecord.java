package com.securityexpert.nexus.ui2.persistence.device;

import java.time.Instant;

/**
 * A read view of one {@code credential_references} row (C1 §3.2, §6;
 * contract §6/§8 test 4). Structurally cannot carry a secret value: the
 * only fields here are the opaque id, the closed-vocabulary {@code
 * purpose}, the opaque {@code backendPointer} (a routing detail, itself
 * never the secret -- C1 §3.2), and a timestamp. No field, getter, or
 * {@code toString()} on this type can expose anything beyond those four
 * values, because there is nothing else here to expose.
 */
public record CredentialReferenceRecord(
        String credentialReferenceId,
        String purpose,
        String backendPointer,
        Instant createdAt) {
}
