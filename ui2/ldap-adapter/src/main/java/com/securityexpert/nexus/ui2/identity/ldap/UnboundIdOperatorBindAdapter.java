package com.securityexpert.nexus.ui2.identity.ldap;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.securityexpert.nexus.ui2.platform.*;

/**
 * Active Directory service-bind sign-in (design LDAP_SERVICE_BIND_DESIGN_2026_11_11 §4): service bind, one
 * sAMAccountName match below the domain DN, group membership under the service account, then the user's own bind
 * against the matched entry. A refusal carries {@link FailureCodes#INVALID_CREDENTIALS}, an unreachable or
 * misconfigured directory {@link FailureCodes#DIRECTORY_UNAVAILABLE}; the message is the {@link DirectoryReason}
 * code and nothing else.
 */
public final class UnboundIdOperatorBindAdapter implements LdapOperatorBindPort {
    private final DirectoryProfile profile;
    private final DirectoryTrustPolicy trust;
    private final Supplier<char[]> servicePassword;

    public UnboundIdOperatorBindAdapter(DirectoryProfile profile, DirectoryTrustPolicy trust,
            Supplier<char[]> servicePassword) {
        this.profile = Objects.requireNonNull(profile);
        this.trust = Objects.requireNonNull(trust);
        this.servicePassword = Objects.requireNonNull(servicePassword);
        trust.snapshot();
    }

    @Override
    public Result<OperatorBindOutcome> bind(String username, char[] password) {
        byte[] passwordBytes = null;
        try {
            if (password == null || password.length == 0 || username == null || username.isBlank()
                    || username.length() > DirectoryConnection.MAX_USER_NAME) {
                return refused(DirectoryReason.USER_BIND_FAILED);
            }
            DirectoryTrustPolicy.Snapshot snapshot = trust.snapshot();
            DirectoryConnection.Opened service = DirectoryConnection.serviceBound(profile, trust, snapshot, servicePassword);
            boolean admin;
            boolean users;
            String userDn;
            try {
                try {
                    userDn = DirectoryConnection.findUser(service.connection(), profile, username);
                } catch (LDAPException e) {
                    return failed(DirectoryConnection.afterAnswer(e));
                }
                // The user's own bind comes before any group query, so a wrong password costs the domain
                // controller no nested-group walk (Argon review finding 8).
                LDAPConnection own = DirectoryConnection.connectTo(profile, trust, snapshot, service.serverIndex());
                try {
                    passwordBytes = DirectoryTrustPolicy.passwordBytes(password);
                    own.bind(new SimpleBindRequest(userDn, passwordBytes));
                } catch (LDAPException e) {
                    DirectoryReason reason = DirectoryConnection.classify(e);
                    return failed(reason != null ? reason
                            : DirectoryConnection.bindRefusal(e, DirectoryReason.USER_BIND_FAILED));
                } finally {
                    Arrays.fill(password, '\0');
                    if (passwordBytes != null) Arrays.fill(passwordBytes, (byte) 0);
                    trust.release(own);
                }
                try {
                    admin = DirectoryConnection.member(service.connection(), profile, profile.adminGroupDn(), userDn);
                    users = DirectoryConnection.member(service.connection(), profile, profile.usersGroupDn(), userDn);
                } catch (LDAPException e) {
                    return failed(DirectoryConnection.afterAnswer(e));
                }
            } finally {
                trust.release(service.connection());
            }
            // Reported only after the user's own bind succeeded: a wrong password never reveals membership.
            if (!admin && !users) return refused(DirectoryReason.NOT_IN_GROUP);
            Set<String> groups = new LinkedHashSet<>();
            if (admin) groups.add(profile.adminGroupDn());
            if (users) groups.add(profile.usersGroupDn());
            DirectoryObservation observation = new DirectoryObservation(profile.profileId(), userDn, groups,
                    write -> trust.publish(snapshot, write));
            if (!observation.publication().ifCurrent(() -> { })) return failed(DirectoryReason.SETTINGS_INVALID);
            return Result.ok(new OperatorBindOutcome(PrincipalFingerprint.of(userDn), Set.copyOf(groups), observation));
        } catch (DirectoryFailure failure) {
            return failed(failure.reason());
        } catch (RuntimeException e) {
            return failed(DirectoryReason.DIRECTORY_ERROR);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (passwordBytes != null) Arrays.fill(passwordBytes, (byte) 0);
        }
    }

    private static Result<OperatorBindOutcome> refused(DirectoryReason reason) {
        return Result.err(FailureCodes.INVALID_CREDENTIALS, reason.code());
    }

    private static Result<OperatorBindOutcome> failed(DirectoryReason reason) {
        return reason.refusesTheUser() ? refused(reason) : Result.err(FailureCodes.DIRECTORY_UNAVAILABLE, reason.code());
    }
}
