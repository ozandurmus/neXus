package com.securityexpert.nexus.ui2.identity.ldap;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import javax.net.SocketFactory;
import com.unboundid.ldap.sdk.*;
import com.unboundid.ldap.sdk.controls.AuthorizationIdentityRequestControl;
import com.unboundid.ldap.sdk.controls.AuthorizationIdentityResponseControl;
import com.securityexpert.nexus.ui2.platform.*;

/** One own-credential bind; authenticated authorization identity is required, never inferred from login spelling. */
public final class UnboundIdOperatorBindAdapter implements LdapOperatorBindPort {
    private final DirectoryProfile profile;
    private final DirectoryTrustPolicy trust;

    private UnboundIdOperatorBindAdapter(DirectoryProfile profile, DirectoryTrustPolicy trust) {
        this.profile = profile;
        this.trust = trust;
    }

    public static UnboundIdOperatorBindAdapter create(DirectoryProfile profile, DirectoryTrustPolicy trust) {
        trust.snapshot();
        return new UnboundIdOperatorBindAdapter(profile, trust);
    }

    /** Compatibility construction is explicitly PEM/LDAPS; production uses a complete server-owned profile. */
    public static UnboundIdOperatorBindAdapter create(String host, int port, Path caBundlePath,
            String bindDnTemplate, String groupSearchBaseDn) {
        DirectoryProfile profile = new DirectoryProfile("legacy", host, port, DirectoryProfile.Transport.LDAPS,
                caBundlePath, DirectoryProfile.Format.PEM, null, bindDnTemplate, groupSearchBaseDn, "unconfigured-access");
        return create(profile, new DirectoryTrustPolicy(profile, () -> { }));
    }

    /** Negative preconnection fixtures only; no plaintext-success seam. */
    static UnboundIdOperatorBindAdapter forTestDirectory(String host, int port, SocketFactory ignored,
            String bindDnTemplate, String groupSearchBaseDn) {
        return new UnboundIdOperatorBindAdapter(null, null);
    }

    @Override
    public Result<OperatorBindOutcome> bind(String username, char[] password) {
        byte[] passwordBytes = null;
        LDAPConnection connection = null;
        boolean bound = false;
        try {
            if (password == null || password.length == 0 || username == null || username.isBlank()) {
                return Result.err(FailureCodes.INVALID_CREDENTIALS, "operator bind rejected");
            }
            if (profile == null) return Result.err(FailureCodes.DIRECTORY_UNAVAILABLE, "directory unavailable");
            DirectoryTrustPolicy.Snapshot snapshot = trust.snapshot();
            String bindDn = profile.bindDnTemplate().replace("%s", javax.naming.ldap.Rdn.escapeValue(username).toString());
            passwordBytes = DirectoryTrustPolicy.passwordBytes(password);
            connection = DirectoryConnection.open(profile, trust, snapshot);
            BindResult result;
            try {
                result = connection.bind(new SimpleBindRequest(bindDn, passwordBytes,
                        new AuthorizationIdentityRequestControl(true)));
                bound = true;
            } finally {
                Arrays.fill(password, '\0');
                Arrays.fill(passwordBytes, (byte) 0);
            }
            AuthorizationIdentityResponseControl proof = AuthorizationIdentityResponseControl.get(result);
            if (proof == null || proof.getAuthorizationID() == null || !proof.getAuthorizationID().startsWith("dn:")) {
                return Result.err(FailureCodes.DIRECTORY_UNAVAILABLE, "directory identity not proven");
            }
            String principal = DirectoryConnection.principal(connection, proof.getAuthorizationID().substring(3));
            Set<String> groups = DirectoryConnection.groups(connection, profile, principal);
            if (!groups.contains(profile.accessGroupReference())) {
                return Result.err(FailureCodes.INVALID_CREDENTIALS, "operator bind rejected");
            }
            DirectoryObservation observation = new DirectoryObservation(profile.profileId(), principal, groups,
                    write -> trust.publish(snapshot, write));
            if (!observation.publication().ifCurrent(() -> { })) {
                return Result.err(FailureCodes.DIRECTORY_UNAVAILABLE, "directory unavailable");
            }
            return Result.ok(new OperatorBindOutcome(PrincipalFingerprint.of(principal), groups, observation));
        } catch (LDAPException e) {
            if (!bound && e.getResultCode() == ResultCode.INVALID_CREDENTIALS) {
                return Result.err(FailureCodes.INVALID_CREDENTIALS, "operator bind rejected");
            }
            return Result.err(FailureCodes.DIRECTORY_UNAVAILABLE, "directory unavailable");
        } catch (RuntimeException e) {
            return Result.err(FailureCodes.DIRECTORY_UNAVAILABLE, "directory unavailable");
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (passwordBytes != null) Arrays.fill(passwordBytes, (byte) 0);
            if (connection != null) trust.release(connection);
        }
    }
}
