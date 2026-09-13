package com.securityexpert.nexus.ui2.service.security;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;

/**
 * Self-service password change (C3A contract §4): "callable by the
 * identity itself (current password re-verified first, exactly as a login
 * attempt is)". There is no forced change anywhere in this class or
 * elsewhere in this movement (§6.2's deferral) -- this is the ability, not
 * a compulsion.
 *
 * <p>The current-password re-check reuses {@link LocalCredentialVerification}
 * (the identical constant-cost comparison a login attempt performs) but
 * never touches {@link LocalMechanism}'s own lockout counters: a wrong
 * "current password" here is a distinct action from a login attempt, and
 * this movement's lockout (§5) is specified for the login endpoint only.
 * The {@code role:security_admin}-administers-another-identity path §4
 * also describes is not implemented by this class -- see SESSION_CLOSE.</p>
 */
public final class PasswordChangeService {

    public sealed interface Result {
        record Ok() implements Result {
        }

        /** Current password did not verify -- the same non-revealing shape as a login refusal (§5.3's spirit). */
        record InvalidCredentials() implements Result {
        }

        /** PW-1/PW-2/PW-3 (§4); {@code violationCode} is a closed vocabulary, safe to surface. */
        record PolicyViolation(String violationCode) implements Result {
        }
    }

    private final LocalCredentialsRepository repository;

    public PasswordChangeService(LocalCredentialsRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Result changePassword(String localIdentityName, char[] currentPassword, char[] newPassword) {
        Optional<LocalCredentialRecord> row = repository.findByName(localIdentityName);
        boolean currentMatches = LocalCredentialVerification.matches(row, currentPassword);
        if (row.isEmpty() || !currentMatches) {
            return new Result.InvalidCredentials();
        }
        LocalCredentialRecord record = row.get();

        Optional<String> violation = LocalPasswordPolicy.violation(newPassword, record.localIdentityName());
        if (violation.isPresent()) {
            return new Result.PolicyViolation(violation.get());
        }

        Argon2PasswordHasher.Verifier newVerifier =
                Argon2PasswordHasher.hash(newPassword, Argon2PasswordHasher.DEFAULT_PARAMETERS);
        String actorFingerprint = LocalMechanism.actorFingerprintFor(record.localIdentityId());
        repository.changePassword(record.localIdentityId(), newVerifier, actorFingerprint);
        return new Result.Ok();
    }
}
