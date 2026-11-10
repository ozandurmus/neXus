package com.securityexpert.nexus.ui2.identity.ldap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;

/**
 * "Test connection" (design §7): for each server, TCP + TLS + service bind; then, on the first server that
 * accepted the service bind, the domain DN and both group DNs exist, and an optional test user's match count and
 * group membership. Never a user bind, never an attribute value: the report holds outcomes only.
 */
public final class DirectoryProbe {
    private DirectoryProbe() { }

    /** {@code outcome} is {@code ok} or a {@link DirectoryReason} code. {@code server} is the admin's own input. */
    public record ServerOutcome(String server, String outcome) { }

    /**
     * {@code userMatch}: {@code found}, {@code not_found}, {@code ambiguous}, {@code not_run} (no test user, or no
     * server accepted the service bind) or {@code error}. Membership fields are null when not evaluated.
     */
    public record Report(List<ServerOutcome> servers, boolean serviceBindOk, Boolean domainDnExists,
            Boolean adminGroupExists, Boolean usersGroupExists, String userMatch, Boolean inAdminGroup,
            Boolean inUsersGroup) {
        public Report { servers = List.copyOf(servers); }
    }

    /** The service password is consumed: zeroed before this returns. */
    public static Report run(DirectoryProfile profile, char[] servicePassword, String testUser) {
        try {
            List<ServerOutcome> outcomes = new ArrayList<>();
            int firstGood = -1;
            for (int index = 0; index < profile.servers().size(); index++) {
                DirectoryProfile single = only(profile, index);
                DirectoryTrustPolicy singleTrust = new DirectoryTrustPolicy(single, () -> { });
                try {
                    DirectoryConnection.Opened opened = DirectoryConnection.serviceBound(single, singleTrust,
                            singleTrust.snapshot(), () -> servicePassword.clone());
                    singleTrust.release(opened.connection());
                    outcomes.add(new ServerOutcome(profile.servers().get(index), "ok"));
                    if (firstGood < 0) firstGood = index;
                } catch (DirectoryFailure failure) {
                    outcomes.add(new ServerOutcome(profile.servers().get(index), failure.reason().code()));
                }
            }
            if (firstGood < 0) return new Report(outcomes, false, null, null, null, "not_run", null, null);
            DirectoryProfile chosen = only(profile, firstGood);
            DirectoryTrustPolicy chosenTrust = new DirectoryTrustPolicy(chosen, () -> { });
            DirectoryConnection.Opened opened;
            try {
                opened = DirectoryConnection.serviceBound(chosen, chosenTrust, chosenTrust.snapshot(),
                        () -> servicePassword.clone());
            } catch (DirectoryFailure failure) {
                return new Report(outcomes, false, null, null, null, "not_run", null, null);
            }
            LDAPConnection connection = opened.connection();
            try {
                boolean domain = DirectoryConnection.exists(connection, chosen, chosen.domainDn(), false);
                boolean adminGroup = DirectoryConnection.exists(connection, chosen, chosen.adminGroupDn(), false);
                boolean usersGroup = DirectoryConnection.exists(connection, chosen, chosen.usersGroupDn(), false);
                if (testUser == null || testUser.isBlank()) {
                    return new Report(outcomes, true, domain, adminGroup, usersGroup, "not_run", null, null);
                }
                if (testUser.length() > DirectoryConnection.MAX_USER_NAME) {
                    return new Report(outcomes, true, domain, adminGroup, usersGroup, "not_found", null, null);
                }
                String userDn;
                try {
                    userDn = DirectoryConnection.findUser(connection, chosen, testUser);
                } catch (DirectoryFailure failure) {
                    String match = failure.reason() == DirectoryReason.USER_AMBIGUOUS ? "ambiguous" : "not_found";
                    return new Report(outcomes, true, domain, adminGroup, usersGroup, match, null, null);
                }
                return new Report(outcomes, true, domain, adminGroup, usersGroup, "found",
                        DirectoryConnection.member(connection, chosen, chosen.adminGroupDn(), userDn),
                        DirectoryConnection.member(connection, chosen, chosen.usersGroupDn(), userDn));
            } catch (LDAPException e) {
                return new Report(outcomes, true, null, null, null, "error", null, null);
            } finally {
                chosenTrust.release(connection);
            }
        } finally {
            Arrays.fill(servicePassword, '\0');
        }
    }

    private static DirectoryProfile only(DirectoryProfile profile, int index) {
        return new DirectoryProfile(profile.profileId(), List.of(profile.servers().get(index)), profile.port(),
                profile.domainDn(), profile.adminGroupDn(), profile.usersGroupDn(), profile.bindDn(),
                profile.timeoutSeconds(), profile.ldaps(), profile.trustMode(), profile.trustPem());
    }
}
