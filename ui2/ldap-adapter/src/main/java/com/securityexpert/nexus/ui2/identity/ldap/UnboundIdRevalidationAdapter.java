package com.securityexpert.nexus.ui2.identity.ldap;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import com.unboundid.ldap.sdk.LDAPException;
import com.securityexpert.nexus.ui2.platform.*;

/**
 * Re-reads a signed-in directory principal's standing with the service account (design §6): the entry still
 * exists, is an enabled AD user, and is in the administrators or users group. One-shot connections, no pool.
 */
public final class UnboundIdRevalidationAdapter implements LdapRevalidationPort {
    /** The session is revoked: the principal is gone, disabled, or in neither configured group. */
    public static final String ACCESS_GROUP_LOST = "access_group_lost";

    private final DirectoryProfile profile;
    private final DirectoryTrustPolicy trust;
    private final Supplier<char[]> servicePassword;

    public UnboundIdRevalidationAdapter(DirectoryProfile profile, DirectoryTrustPolicy trust,
            Supplier<char[]> servicePassword) {
        this.profile = Objects.requireNonNull(profile);
        this.trust = Objects.requireNonNull(trust);
        this.servicePassword = Objects.requireNonNull(servicePassword);
        trust.snapshot();
    }

    @Override public boolean directoryPostureEnabled() { return true; }

    @Override public Result<Set<String>> revalidate(String actorFingerprint, String bindDn) {
        Result<DirectoryObservation> result = revalidatePrincipal(bindDn);
        if (result instanceof Result.Err<DirectoryObservation> err) return Result.err(err.code(), err.message());
        // Legacy callers cannot publish a principal-less freshness observation.
        return Result.err("directory_identity_not_proven", "typed directory observation required");
    }

    @Override public Result<DirectoryObservation> revalidatePrincipal(String principalReference) {
        if (principalReference == null || principalReference.isBlank()) {
            return Result.err("directory_unavailable", DirectoryReason.SETTINGS_INVALID.code());
        }
        try {
            DirectoryTrustPolicy.Snapshot snapshot = trust.snapshot();
            DirectoryConnection.Opened service = DirectoryConnection.serviceBound(profile, trust, snapshot, servicePassword);
            Set<String> groups = new LinkedHashSet<>();
            try {
                if (!DirectoryConnection.exists(service.connection(), profile, principalReference, true)) {
                    return Result.err(ACCESS_GROUP_LOST, DirectoryReason.USER_NOT_FOUND.code());
                }
                if (DirectoryConnection.member(service.connection(), profile, profile.adminGroupDn(), principalReference)) {
                    groups.add(profile.adminGroupDn());
                }
                if (DirectoryConnection.member(service.connection(), profile, profile.usersGroupDn(), principalReference)) {
                    groups.add(profile.usersGroupDn());
                }
            } finally {
                trust.release(service.connection());
            }
            if (groups.isEmpty()) return Result.err(ACCESS_GROUP_LOST, DirectoryReason.NOT_IN_GROUP.code());
            DirectoryObservation observation = new DirectoryObservation(profile.profileId(), principalReference, groups,
                    write -> trust.publish(snapshot, write));
            if (!observation.publication().ifCurrent(() -> { })) {
                return Result.err("directory_unavailable", DirectoryReason.SETTINGS_INVALID.code());
            }
            return Result.ok(observation);
        } catch (DirectoryFailure failure) {
            return Result.err("directory_unavailable", failure.reason().code());
        } catch (LDAPException e) {
            return Result.err("directory_unavailable", DirectoryConnection.afterAnswer(e).code());
        } catch (RuntimeException e) {
            return Result.err("directory_unavailable", DirectoryReason.DIRECTORY_ERROR.code());
        }
    }
}
