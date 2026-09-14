package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;
import com.securityexpert.nexus.ui2.platform.LocalIdentityAdministrationPort;
import com.securityexpert.nexus.ui2.platform.LocalPrincipalFingerprint;
import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * The {@link LocalIdentityAdministrationPort} implementation (13G
 * {@code LIA-1}..{@code LIA-3}): the one class the HTTP API ({@code service}
 * module) and the CLI ({@code cli} module, via a {@code job-engine}
 * composition helper) both construct, so the two paths share the exact same
 * domain logic and cannot drift (13G {@code LIA-2}).
 */
public final class LocalIdentityAdministration implements LocalIdentityAdministrationPort {

    /** 13G LIA-3.6: the audit action id for ending a disabled identity's active session. */
    static final String ACTION_DISABLE_ENDS_SESSION = "local_identity_disable_ends_session";

    private final LocalCredentialsRepository localCredentialsRepository;
    private final SessionRepository sessionRepository;
    private final SecurityAdminLockoutGuard securityAdminLockoutGuard;
    private final RootIdentityRepository rootIdentityRepository;

    public LocalIdentityAdministration(LocalCredentialsRepository localCredentialsRepository,
            SessionRepository sessionRepository, SecurityAdminLockoutGuard securityAdminLockoutGuard) {
        this.localCredentialsRepository = Objects.requireNonNull(localCredentialsRepository, "localCredentialsRepository");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.securityAdminLockoutGuard = Objects.requireNonNull(securityAdminLockoutGuard, "securityAdminLockoutGuard");
        this.rootIdentityRepository = null;
    }

    public LocalIdentityAdministration(LocalCredentialsRepository localCredentialsRepository,
            SessionRepository sessionRepository, SecurityAdminLockoutGuard securityAdminLockoutGuard,
            RootIdentityRepository rootIdentityRepository) {
        this.localCredentialsRepository = Objects.requireNonNull(localCredentialsRepository, "localCredentialsRepository");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.securityAdminLockoutGuard = Objects.requireNonNull(securityAdminLockoutGuard, "securityAdminLockoutGuard");
        this.rootIdentityRepository = Objects.requireNonNull(rootIdentityRepository, "rootIdentityRepository");
    }

    @Override
    public LocalIdentityView create(String actingAdminActorFingerprint, String localIdentityName,
            char[] initialPassword) {
        String localIdentityId = OpaqueId.random().value();
        // 13G LIA-3.2: computed through the existing helper, under C3A's
        // existing default parameters; initialPassword is never persisted,
        // logged, audited or returned itself.
        Argon2PasswordHasher.Verifier verifier =
                Argon2PasswordHasher.hash(initialPassword, Argon2PasswordHasher.DEFAULT_PARAMETERS);
        java.util.Arrays.fill(initialPassword, '\0');
        localCredentialsRepository.create(localIdentityId, localIdentityName, verifier, actingAdminActorFingerprint);
        return toView(localCredentialsRepository.findById(localIdentityId)
                .orElseThrow(() -> new IllegalStateException("local identity vanished immediately after creation")));
    }

    @Override
    public List<LocalIdentityView> list() {
        return localCredentialsRepository.findAll().stream().map(LocalIdentityAdministration::toView).toList();
    }

    @Override
    public MutationResult setPassword(String actingAdminActorFingerprint, String localIdentityId,
            char[] newPassword) {
        Optional<LocalCredentialRecord> existing = localCredentialsRepository.findById(localIdentityId);
        if (existing.isEmpty()) {
            java.util.Arrays.fill(newPassword, '\0');
            return new MutationResult.NotFound();
        }
        Argon2PasswordHasher.Verifier verifier =
                Argon2PasswordHasher.hash(newPassword, Argon2PasswordHasher.DEFAULT_PARAMETERS);
        java.util.Arrays.fill(newPassword, '\0');
        localCredentialsRepository.adminSetPassword(localIdentityId, verifier, actingAdminActorFingerprint);
        return new MutationResult.Ok(toView(localCredentialsRepository.findById(localIdentityId).orElseThrow()));
    }

    @Override
    public MutationResult disable(String actingAdminActorFingerprint, String localIdentityId) {
        Optional<LocalCredentialRecord> existing = localCredentialsRepository.findById(localIdentityId);
        if (existing.isEmpty()) {
            return new MutationResult.NotFound();
        }
        if (rootIdentityRepository != null && rootIdentityRepository.rootLocalIdentityId()
                .filter(localIdentityId::equals).isPresent()) {
            return new MutationResult.LastSecurityAdminRefused();
        }
        if (existing.get().enabled()
                && !securityAdminLockoutGuard.anyEnabledSecurityAdminRemainsIfLocalIdentityDisabled(localIdentityId)) {
            return new MutationResult.LastSecurityAdminRefused();
        }
        localCredentialsRepository.setEnabled(localIdentityId, false, actingAdminActorFingerprint);
        // 13G LIA-3.6: the identity's active session, if any, ends now --
        // never left to idle/absolute expiry.
        String disabledIdentityActorFingerprint = LocalPrincipalFingerprint.forLocalIdentity(localIdentityId);
        sessionRepository.findActiveByActor(disabledIdentityActorFingerprint)
                .ifPresent(session -> sessionRepository.revoke(session.sessionId(), actingAdminActorFingerprint,
                        ACTION_DISABLE_ENDS_SESSION));
        return new MutationResult.Ok(toView(localCredentialsRepository.findById(localIdentityId).orElseThrow()));
    }

    @Override
    public MutationResult enable(String actingAdminActorFingerprint, String localIdentityId) {
        Optional<LocalCredentialRecord> existing = localCredentialsRepository.findById(localIdentityId);
        if (existing.isEmpty()) {
            return new MutationResult.NotFound();
        }
        // AC-4 / BOOT-2's spirit: re-enabling restores authentication
        // without resetting the credential -- no verifier/salt touch here.
        localCredentialsRepository.setEnabled(localIdentityId, true, actingAdminActorFingerprint);
        return new MutationResult.Ok(toView(localCredentialsRepository.findById(localIdentityId).orElseThrow()));
    }

    private static LocalIdentityView toView(LocalCredentialRecord record) {
        return new LocalIdentityView(record.localIdentityId(), record.localIdentityName(), record.enabled(),
                record.mustChangePassword(), record.createdAt(), record.passwordSetAt());
    }
}
