package com.securityexpert.nexus.ui2.service.security;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalPasswordPolicyRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalPasswordPolicySettings;
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
        record PolicyViolation(String violationCode, String message) implements Result {
            public PolicyViolation(String violationCode) {
                this(violationCode, LocalPasswordPolicy.describe(violationCode, LocalPasswordPolicySettings.DEFAULTS));
            }
        }
    }

    private final LocalCredentialsRepository repository;
    /** {@code null}: no configurable policy exists, behaviour is exactly the original. */
    private final LocalPasswordPolicyRepository policyRepository;

    public PasswordChangeService(LocalCredentialsRepository repository) {
        this(repository, null);
    }

    public PasswordChangeService(LocalCredentialsRepository repository, LocalPasswordPolicyRepository policyRepository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.policyRepository = policyRepository;
    }

    public Result changePassword(String localIdentityName, char[] currentPassword, char[] newPassword) {
        if (currentPassword == null) {
            return new Result.InvalidCredentials();
        }
        Optional<LocalCredentialRecord> row = repository.findByName(localIdentityName);
        boolean currentMatches = LocalCredentialVerification.matches(row, currentPassword);
        if (row.isEmpty() || !currentMatches) {
            return new Result.InvalidCredentials();
        }
        LocalCredentialRecord record = row.get();

        LocalPasswordPolicySettings policy = policyRepository == null
                ? LocalPasswordPolicySettings.DEFAULTS : policyRepository.read().settings();
        Optional<String> violation = LocalPasswordPolicy.violation(
                newPassword == null ? new char[0] : newPassword, record.localIdentityName(), policy);
        if (violation.isPresent()) {
            return new Result.PolicyViolation(violation.get(), LocalPasswordPolicy.describe(violation.get(), policy));
        }
        if (policy.enabled() && policy.historyCount() > 0 && reused(record, newPassword, policy.historyCount())) {
            return new Result.PolicyViolation("password_reused", LocalPasswordPolicy.describe("password_reused", policy));
        }

        Argon2PasswordHasher.Verifier newVerifier =
                Argon2PasswordHasher.hash(newPassword, Argon2PasswordHasher.DEFAULT_PARAMETERS);
        String actorFingerprint = LocalMechanism.actorFingerprintFor(record.localIdentityId());
        if (policy.enabled() && policy.historyCount() > 0) {
            policyRepository.retire(record.localIdentityId(), record.toVerifier(), policy.historyCount());
        }
        repository.changePassword(record.localIdentityId(), newVerifier, actorFingerprint);
        return new Result.Ok();
    }

    /** The new password equals the current one or one of the retired ones, together the last {@code count}. */
    private boolean reused(LocalCredentialRecord current, char[] newPassword, int count) {
        if (Argon2PasswordHasher.verify(newPassword, current.toVerifier())) {
            return true;
        }
        for (Argon2PasswordHasher.Verifier retired : policyRepository.recentRetired(current.localIdentityId(), count - 1)) {
            if (Argon2PasswordHasher.verify(newPassword, retired)) {
                return true;
            }
        }
        return false;
    }
}
