package com.securityexpert.nexus.ui2.service.security;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.identity.LocalAuthModeRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RootIdentityRepository;

/**
 * The administrator-facing side of the local-account scope ({@code GET/PUT /api/v2/config/local-auth-mode}, migration
 * plan Step 3). The switch to {@code BREAK_GLASS_ONLY} is an explicit, audited write and refuses anything that could lock
 * the acting administrator or everyone else out:
 * <ul>
 *   <li>the acting administrator must be a break-glass identity (the root identity) or a directory (LDAP/SAML)
 *       administrator; a local administrator who is not the root identity would cut off their own next sign-in;</li>
 *   <li>the root identity must exist, be enabled and not be locked, so a break-glass way in is certain to remain;</li>
 * </ul>
 * Turning it back to {@code ALL_LOCAL} is always allowed. Sessions that are already open are not ended.
 */
public final class LocalAuthModeService {

    public record Refusal(int status, String code, String message) {
    }

    public record Result(Map<String, Object> body, Refusal refusal) {
        public boolean isOk() {
            return refusal == null;
        }
    }

    private final LocalAuthModeRepository repository;
    private final LocalAuthPolicy policy;
    private final RootIdentityRepository root;
    private final LocalCredentialsRepository credentials;
    private final LocalIdentityResolver identities;
    private final java.util.function.Supplier<Instant> now;

    public LocalAuthModeService(LocalAuthModeRepository repository, LocalAuthPolicy policy, RootIdentityRepository root,
            LocalCredentialsRepository credentials, LocalIdentityResolver identities) {
        this(repository, policy, root, credentials, identities, Instant::now);
    }

    LocalAuthModeService(LocalAuthModeRepository repository, LocalAuthPolicy policy, RootIdentityRepository root,
            LocalCredentialsRepository credentials, LocalIdentityResolver identities, java.util.function.Supplier<Instant> now) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.root = Objects.requireNonNull(root, "root");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.now = now;
    }

    public Map<String, Object> view(boolean canWrite) {
        return view(repository.read(), canWrite);
    }

    public Result update(String actorFingerprint, Map<String, Object> body) {
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            return refused(403, "LOCAL_AUTH_MODE_NOT_PERMITTED", "No authenticated session.");
        }
        if (body == null || !(body.get("mode") instanceof String mode) || body.size() != 1) {
            return refused(400, "LOCAL_AUTH_MODE_INVALID", "The body must be exactly {\"mode\": \"ALL_LOCAL\" or \"BREAK_GLASS_ONLY\"}.");
        }
        if (!LocalAuthModeRepository.ALL_LOCAL.equals(mode) && !LocalAuthModeRepository.BREAK_GLASS_ONLY.equals(mode)) {
            return refused(400, "LOCAL_AUTH_MODE_INVALID", "mode must be ALL_LOCAL or BREAK_GLASS_ONLY.");
        }
        if (LocalAuthModeRepository.BREAK_GLASS_ONLY.equals(mode)) {
            Refusal lockout = lockoutRisk(actorFingerprint);
            if (lockout != null) {
                return new Result(null, lockout);
            }
        }
        var stored = repository.write(actorFingerprint, mode);
        policy.invalidate();
        return new Result(view(stored, true), null);
    }

    private Refusal lockoutRisk(String actorFingerprint) {
        Optional<String> rootId = root.rootLocalIdentityId();
        if (rootId.isEmpty()) {
            return new Refusal(409, "LOCAL_AUTH_MODE_NO_BREAK_GLASS",
                    "BREAK_GLASS_ONLY was not enabled: there is no root (break-glass) identity to fall back on.");
        }
        Optional<LocalCredentialRecord> rootRecord = credentials.findById(rootId.get());
        if (rootRecord.isEmpty() || !rootRecord.get().enabled() || rootRecord.get().isLocked(now.get())) {
            return new Refusal(409, "LOCAL_AUTH_MODE_NO_BREAK_GLASS",
                    "BREAK_GLASS_ONLY was not enabled: the root (break-glass) identity is missing, disabled or locked.");
        }
        Optional<LocalCredentialRecord> localActor = identities.resolve(actorFingerprint);
        if (localActor.isPresent() && !localActor.get().localIdentityId().equals(rootId.get())) {
            return new Refusal(409, "LOCAL_AUTH_MODE_ACTOR_WOULD_LOCK_OUT",
                    "BREAK_GLASS_ONLY was not enabled: you are signed in with a local account that this mode refuses. "
                            + "Enable it as the root identity or as a directory (LDAP or SAML) administrator.");
        }
        return null;
    }

    static Map<String, Object> view(LocalAuthModeRepository.Row row, boolean canWrite) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", row.mode());
        body.put("modes", List.of(LocalAuthModeRepository.ALL_LOCAL, LocalAuthModeRepository.BREAK_GLASS_ONLY));
        body.put("break_glass_accounts", List.of("nexusadmin (root identity)", LocalAuthPolicy.VERIFICATION_ACCOUNT));
        body.put("updated_at", row.updatedAt() == null ? null : row.updatedAt().toString());
        body.put("updated_by", row.updatedBy());
        body.put("can_write", canWrite);
        return body;
    }

    private static Result refused(int status, String code, String message) {
        return new Result(null, new Refusal(status, code, message));
    }
}
