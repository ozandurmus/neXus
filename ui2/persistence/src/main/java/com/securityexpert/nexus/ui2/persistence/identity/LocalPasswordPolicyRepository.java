package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Instant;
import java.util.List;

import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;

/**
 * Storage for the configurable local password policy: the single settings row (one audited write under
 * {@link #WRITE_ACTION}) and the retired-verifier history the reuse rule checks against.
 */
public interface LocalPasswordPolicyRepository {
    /** The audit action id of every settings write. */
    String WRITE_ACTION = "local_password_policy_write";

    /** The row as stored; {@code updatedAt}/{@code updatedBy} are null before the row was ever written. */
    record Row(LocalPasswordPolicySettings settings, Instant updatedAt, String updatedBy) {
    }

    Row read();

    /** Replaces the row in one audited transaction ({@code app.actor_fingerprint} = the caller) and answers it as stored. */
    Row write(String actorFingerprint, LocalPasswordPolicySettings settings);

    /** The {@code limit} most recently retired verifiers of one identity, newest first. */
    List<Argon2PasswordHasher.Verifier> recentRetired(String localIdentityId, int limit);

    /** Keeps {@code retired} as a former password and drops all but the newest {@code keep} for that identity. */
    void retire(String localIdentityId, Argon2PasswordHasher.Verifier retired, int keep);
}
