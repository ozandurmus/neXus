package com.securityexpert.nexus.ui2.identity.ldap;

import java.util.LinkedHashSet;
import java.util.Set;
import com.unboundid.ldap.sdk.*;
import com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest;
import com.unboundid.util.ssl.HostNameSSLSocketVerifier;

/** Shared verified transport and bounded existing membership projection. */
final class DirectoryConnection {
    private DirectoryConnection() { }

    static LDAPConnection open(DirectoryProfile profile, DirectoryTrustPolicy trust,
            DirectoryTrustPolicy.Snapshot snapshot) throws LDAPException {
        if (!trust.compatible(profile)) throw new LdapStartupException("directory_profile_mismatch");
        LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setFollowReferrals(false);
        options.setAutoReconnect(false);
        options.setBindWithDNRequiresPassword(true);
        options.setConnectTimeoutMillis(5000);
        options.setResponseTimeoutMillis(5000);
        options.setSSLSocketVerifier(new HostNameSSLSocketVerifier(true, false));
        LDAPConnection connection = new LDAPConnection(
                profile.transport() == DirectoryProfile.Transport.LDAPS ? snapshot.socketFactory()
                        : javax.net.SocketFactory.getDefault(), options);
        try {
            connection.connect(profile.host(), profile.port());
            if (profile.transport() == DirectoryProfile.Transport.STARTTLS) {
                ExtendedResult upgrade = connection.processExtendedOperation(new StartTLSExtendedRequest(snapshot.socketFactory()));
                if (upgrade.getResultCode() != ResultCode.SUCCESS) throw new LDAPException(ResultCode.CONNECT_ERROR);
            }
            trust.register(snapshot, connection);
            return connection;
        } catch (LDAPException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    static String principal(LDAPConnection connection, String reference) throws LDAPException {
        if (reference == null || reference.isBlank()) throw new LDAPException(ResultCode.INSUFFICIENT_ACCESS_RIGHTS);
        SearchRequest request = new SearchRequest(reference, SearchScope.BASE,
                Filter.createPresenceFilter("objectClass"), "1.1");
        request.setSizeLimit(2);
        SearchResult result = connection.search(request);
        if (result.getEntryCount() != 1 || result.getReferenceCount() != 0
                || !reference.equals(result.getSearchEntries().get(0).getDN())) {
            throw new LDAPException(ResultCode.INSUFFICIENT_ACCESS_RIGHTS);
        }
        return reference;
    }

    static Set<String> groups(LDAPConnection connection, DirectoryProfile profile, String reference) throws LDAPException {
        SearchRequest request = new SearchRequest(profile.groupSearchBaseDn(), SearchScope.SUB,
                Filter.createEqualityFilter("member", reference), "1.1");
        request.setSizeLimit(1000);
        SearchResult result = connection.search(request);
        if (result.getReferenceCount() != 0) throw new LDAPException(ResultCode.REFERRAL);
        Set<String> groups = new LinkedHashSet<>();
        for (SearchResultEntry entry : result.getSearchEntries()) groups.add(entry.getDN());
        return Set.copyOf(groups);
    }
}
