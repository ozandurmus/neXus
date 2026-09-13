package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;

/**
 * {@code local_credentials} persistence port (C3A contract §3 placement,
 * §5; extended by 13G section 4 for local identity administration). Every
 * mutation implicitly goes through
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
    /** V9 (NXS-LOCAL-0152): the audited action id {@link #markMustChangePassword} runs under. */
    String ACTION_MUST_CHANGE_PASSWORD_SEED = "local_credential_must_change_password_seed";
    /** 13G LIA-3.2: an administrative reset, distinct from an identity's own self-service change. */
    String ACTION_ADMIN_PASSWORD_RESET = "local_credential_admin_password_reset";
    /** 13G LIA-3.5/3.6. */
    String ACTION_DISABLE = "local_credential_disable";
    String ACTION_ENABLE = "local_credential_enable";

    Optional<LocalCredentialRecord> findByName(String localIdentityName);

    Optional<LocalCredentialRecord> findById(String localIdentityId);

    /** 13G section 3: every row, for the local identity administration list view. */
    List<LocalCredentialRecord> findAll();

    /**
     * Every row (NXS-LOCAL-0152's own need: resolving a session's opaque
     * {@code actor_fingerprint} back to the local identity behind it, since
     * the fingerprint is a one-way hash of {@code "local:" + local_identity_id}
     * -- see {@link com.securityexpert.nexus.ui2.platform.PrincipalFingerprint} --
     * and is never itself a lookup key on this table). This table holds at
     * most a handful of rows (bootstrap identities plus any operator-created
     * via the CLI), so an in-memory scan by the caller is the right size
     * trade-off; the returned rows are compared in-process and never
     * serialized to an external caller.
     */
    List<LocalCredentialRecord> findAll();

    /**
     * BOOT-1/BOOT-2 (C3B contract §2): true when the table holds at least
     * one row, seeded or operator-created. First-boot seeding's whole gate
     * is this single boolean -- a partially-seeded table (one bootstrap row
     * present, the other missing) still answers {@code true}, so seeding is
     * skipped entirely rather than attempting to create only the missing
     * identity (BOOT-2's decisive rule: never risk rewriting a row an
     * operator has already touched).
     */
    boolean anyExist();

    /**
     * Creates a new row (bootstrap seeding, §6, or 13G local identity
     * administration). {@code createdByActorFingerprint} is both this
     * mutation's audit actor and the persisted {@code created_by_actor_fingerprint}
     * (13G section 4). The created row is {@code enabled}, has
     * {@code password_set_at} = now, and {@code must_change_password} = true
     * (13G LIA-3.3).
     */
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

    /**
     * Overwrites verifier/salt/parameters (§4/§3.2's rehash-shape); attributed
     * to the identity's own fingerprint. V9 (NXS-LOCAL-0152, AC-2): the same
     * {@code UPDATE} statement also clears {@code must_change_password}, so
     * the flag is cleared in the same transaction that writes the new
     * verifier -- never a separate call that could commit independently.
     */
    /** Overwrites verifier/salt/parameters (§4/§3.2's rehash-shape) for an identity's own self-service change; attributed to the identity's own fingerprint. Updates {@code password_set_at}; does not touch {@code must_change_password} (owned by the parallel movement's own enforcement path). */
    void changePassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
            String changedByActorFingerprint);

    /**
     * V9 (NXS-LOCAL-0152, BOOT-5a): marks a freshly-created row as still
     * holding its seeded password. Called only by
     * {@link com.securityexpert.nexus.ui2.persistence.identity.FirstBootIdentityRoleBindingSeeder},
     * immediately after {@link #create}, inside the very same outer
     * transaction -- every other caller of {@link #create} (the CLI
     * bootstrap path) never calls this, and its rows keep the schema
     * default ({@code false}).
     */
    void markMustChangePassword(String localIdentityId, String actorFingerprint);
     * 13G LIA-3.2/LIA-3.3: an administrative reset. Overwrites
     * verifier/salt/parameters, sets {@code password_set_at} = now and
     * {@code must_change_password} = true, attributed to the acting
     * administrator (distinct from {@link #changePassword}, which is
     * self-service and attributed to the identity itself).
     */
    void adminSetPassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
            String settingAdminActorFingerprint);

    /** 13G LIA-3.5/LIA-3.6: sets {@code enabled}, attributed to the acting administrator. */
    void setEnabled(String localIdentityId, boolean enabled, String actingAdminActorFingerprint);
}
