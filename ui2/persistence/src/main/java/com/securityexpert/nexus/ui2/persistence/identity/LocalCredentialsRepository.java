package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;

/**
 * {@code local_credentials} persistence port (C3A contract §3 placement,
 * §5). Every mutation implicitly goes through
 * {@link com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary}
 * (F3): the table carries the narrow, allowlisted audit trigger
 * {@code fn_audit_capture_local_credentials()} (§8), so a mutating call here
 * always needs an actor fingerprint and action id.
 */
public interface LocalCredentialsRepository {

    /** Reserved actor for a failed attempt: the attempt is unverified, so it is never attributed to the identity itself (§8). */
    String SYSTEM_LOCAL_LOGIN_ATTEMPT = "system:local_login_attempt";

    String ACTION_CREDENTIAL_CREATE = "local_credential_create";
    String ACTION_LOGIN_FAILURE = "local_login_failure";
    String ACTION_LOGIN_SUCCESS = "local_login_success";
    String ACTION_PASSWORD_CHANGE = "local_password_change";

    Optional<LocalCredentialRecord> findByName(String localIdentityName);

    Optional<LocalCredentialRecord> findById(String localIdentityId);

    /** Creates a new row (bootstrap seeding, §6, or a future user-management screen out of this movement's scope). */
    String create(String localIdentityId, String localIdentityName, Argon2PasswordHasher.Verifier verifier,
            String createdByActorFingerprint);

    /**
     * Increments {@code failed_attempt_count} and sets {@code locked_until}
     * when the threshold is reached in the same statement (§5.1/§5.2) --
     * one atomic UPDATE, so a concurrent failure cannot race past the
     * threshold unlocked.
     */
    void recordFailedAttempt(String localIdentityId, Instant now, int lockoutThreshold, Duration lockoutDuration);

    /**
     * Resets the counter and clears the lockout on a correct-password
     * attempt (§5.2), attributed to the now-verified identity's own
     * fingerprint -- unlike a failure, a success is attributable to itself.
     */
    void recordSuccessfulLogin(String localIdentityId, Instant now, String actorFingerprint);

    /** Overwrites verifier/salt/parameters (§4/§3.2's rehash-shape); attributed to the identity's own fingerprint. */
    void changePassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
            String changedByActorFingerprint);
}
