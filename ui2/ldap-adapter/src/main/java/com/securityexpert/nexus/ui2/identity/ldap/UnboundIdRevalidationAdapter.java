package com.securityexpert.nexus.ui2.identity.ldap;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import com.unboundid.ldap.sdk.*;
import com.securityexpert.nexus.ui2.platform.*;

/** Disabled in corporate composition. One-shot connections retire credentials every cycle; no pool. */
public final class UnboundIdRevalidationAdapter implements LdapRevalidationPort {
    private final boolean directoryPostureEnabled;
    private final DirectoryProfile profile;
    private final DirectoryTrustPolicy trust;
    private final String serviceAccountBindDn;
    private final Path serviceAccountPasswordFile;

    public UnboundIdRevalidationAdapter(boolean enabled, DirectoryProfile profile, DirectoryTrustPolicy trust,
            String serviceAccountBindDn, Path serviceAccountPasswordFile) {
        this.directoryPostureEnabled = enabled;
        this.profile = profile;
        this.trust = trust;
        this.serviceAccountBindDn = serviceAccountBindDn;
        this.serviceAccountPasswordFile = serviceAccountPasswordFile;
        if (enabled) {
            if (profile == null || trust == null || serviceAccountBindDn == null || serviceAccountBindDn.isBlank()
                    || serviceAccountPasswordFile == null) throw new LdapStartupException("directory_profile_invalid");
            trust.snapshot();
        }
    }

    /** Disabled compatibility only: enabling without an explicit shared profile is refused. */
    public UnboundIdRevalidationAdapter(boolean enabled, String host, int port, Path caBundlePath,
            String serviceAccountBindDn, Path serviceAccountPasswordFile, String groupSearchBaseDn) {
        this(enabled, null, null, serviceAccountBindDn, serviceAccountPasswordFile);
    }

    @Override public boolean directoryPostureEnabled() { return directoryPostureEnabled; }

    @Override public Result<Set<String>> revalidate(String actorFingerprint, String bindDn) {
        Result<DirectoryObservation> result = revalidatePrincipal(bindDn);
        if (result instanceof Result.Err<DirectoryObservation> err) return Result.err(err.code(), "directory unavailable");
        // Legacy callers cannot publish a principal-less freshness observation.
        return Result.err("directory_identity_not_proven", "typed directory observation required");
    }

    @Override public Result<DirectoryObservation> revalidatePrincipal(String principalReference) {
        if (!directoryPostureEnabled) return Result.err("directory_posture_disabled", "revalidation disabled");
        char[] password = null;
        byte[] passwordBytes = null;
        LDAPConnection connection = null;
        try {
            DirectoryTrustPolicy.Snapshot snapshot = trust.snapshot();
            password = DirectoryTrustPolicy.readSecret(serviceAccountPasswordFile);
            passwordBytes = DirectoryTrustPolicy.passwordBytes(password);
            connection = DirectoryConnection.open(profile, trust, snapshot);
            try { connection.bind(new SimpleBindRequest(serviceAccountBindDn, passwordBytes)); }
            finally {
                Arrays.fill(password, '\0');
                Arrays.fill(passwordBytes, (byte) 0);
            }
            String principal = DirectoryConnection.principal(connection, principalReference);
            Set<String> groups = DirectoryConnection.groups(connection, profile, principal);
            if (!groups.contains(profile.accessGroupReference())) return Result.err("access_group_lost", "access refused");
            DirectoryObservation observation = new DirectoryObservation(profile.profileId(), principal, groups,
                    write -> trust.publish(snapshot, write));
            if (!observation.publication().ifCurrent(() -> { })) return Result.err("directory_unavailable", "directory unavailable");
            return Result.ok(observation);
        } catch (LDAPException | RuntimeException e) {
            return Result.err("directory_unavailable", "directory unavailable");
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (passwordBytes != null) Arrays.fill(passwordBytes, (byte) 0);
            if (connection != null) trust.release(connection);
        }
    }
}
