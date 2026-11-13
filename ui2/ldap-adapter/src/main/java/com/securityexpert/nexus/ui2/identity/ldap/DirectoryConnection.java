package com.securityexpert.nexus.ui2.identity.ldap;

import java.util.Arrays;
import java.util.function.Supplier;

import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.DereferencePolicy;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.SimpleBindRequest;

/** Connections and the bounded directory reads of the service-bind model (design §4, §6, §7). */
final class DirectoryConnection {
    /** LDAP_MATCHING_RULE_IN_CHAIN: transitive (nested) group membership, evaluated by the domain controller. */
    static final String IN_CHAIN = "1.2.840.113556.1.4.1941";
    /** LDAP_MATCHING_RULE_BIT_AND, used to exclude disabled accounts (userAccountControl bit 2) at revalidation. */
    static final String BIT_AND = "1.2.840.113556.1.4.803";
    static final int MAX_USER_NAME = 256;

    private DirectoryConnection() { }

    /**
     * Makes sure the TLS handshake is complete inside {@code connect()}, exactly once. UnboundID's connect thread
     * has normally completed it already; calling {@code startHandshake()} again on an established TLS 1.2 socket
     * starts a client-initiated renegotiation, which a Windows domain controller refuses by resetting the
     * connection, so every LDAPS operation after connect failed with "server down" (field defect 2026-11-12).
     * {@code getSession()} completes a pending handshake and is a no-op on an established one; a failed handshake
     * yields the null cipher suite instead of an exception.
     */
    private static final com.unboundid.util.ssl.SSLSocketVerifier HANDSHAKE_NOW = new com.unboundid.util.ssl.SSLSocketVerifier() {
        @Override
        public void verifySSLSocket(String host, int port, javax.net.ssl.SSLSocket socket) throws LDAPException {
            javax.net.ssl.SSLSession session = socket.getSession();
            if (session == null || !session.isValid() || "SSL_NULL_WITH_NULL_NULL".equals(session.getCipherSuite())) {
                throw new LDAPException(ResultCode.CONNECT_ERROR, "tls handshake failed",
                        new javax.net.ssl.SSLHandshakeException("tls handshake failed"));
            }
        }
    };

    record Opened(LDAPConnection connection, int serverIndex) { }

    /**
     * Connects to the servers in order and binds the service account on the first one that completes TCP and TLS.
     * A server that fails before answering (connect error, TLS failure, timeout) is skipped; a server that answers
     * the bind with an LDAP result is not, and a refused service bind is {@code service_bind_failed}.
     */
    static Opened serviceBound(DirectoryProfile profile, DirectoryTrustPolicy trust, DirectoryTrustPolicy.Snapshot snapshot,
            Supplier<char[]> servicePassword) throws DirectoryFailure {
        DirectoryReason last = DirectoryReason.CONNECT_FAILED;
        for (int index = 0; index < profile.servers().size(); index++) {
            LDAPConnection connection;
            try {
                connection = connectTo(profile, trust, snapshot, index);
            } catch (DirectoryFailure failure) {
                last = failure.reason();
                continue;
            }
            char[] password = null;
            byte[] passwordBytes = null;
            boolean keep = false;
            try {
                password = servicePassword.get();
                if (password == null || password.length == 0) throw new DirectoryFailure(DirectoryReason.SERVICE_BIND_FAILED);
                passwordBytes = DirectoryTrustPolicy.passwordBytes(password);
                connection.bind(new SimpleBindRequest(profile.bindDn(), passwordBytes));
                keep = true;
                return new Opened(connection, index);
            } catch (LDAPException e) {
                DirectoryReason reason = classify(e);
                if (reason == null) throw new DirectoryFailure(bindRefusal(e, DirectoryReason.SERVICE_BIND_FAILED));
                last = reason;
            } catch (RuntimeException e) {
                throw new DirectoryFailure(DirectoryReason.SERVICE_BIND_FAILED);
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (passwordBytes != null) Arrays.fill(passwordBytes, (byte) 0);
                if (!keep) trust.release(connection);
            }
        }
        throw new DirectoryFailure(last);
    }

    /** One connection to one configured server, TCP and TLS complete, not bound. */
    static LDAPConnection connectTo(DirectoryProfile profile, DirectoryTrustPolicy trust,
            DirectoryTrustPolicy.Snapshot snapshot, int index) throws DirectoryFailure {
        if (!trust.compatible(profile)) throw new DirectoryFailure(DirectoryReason.SETTINGS_INVALID);
        int millis = profile.timeoutSeconds() * 1000;
        LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setFollowReferrals(false);
        options.setBindWithDNRequiresPassword(true);
        options.setConnectTimeoutMillis(millis);
        options.setResponseTimeoutMillis(millis);
        options.setUseSynchronousMode(true);
        // Complete the TLS handshake inside connect(), so a TLS failure is a connect-time failure: it is
        // classified (tls_handshake_failed, tls_trust_failed or tls_name_mismatch) and the next server is tried
        // (Argon review finding 3).
        options.setSSLSocketVerifier(HANDSHAKE_NOW);
        LDAPConnection connection = new LDAPConnection(snapshot.socketFactory(), options);
        try {
            connection.connect(profile.servers().get(index), profile.port(), millis);
            trust.register(snapshot, connection);
            return connection;
        } catch (LDAPException e) {
            connection.close();
            DirectoryReason reason = classify(e);
            throw new DirectoryFailure(reason == null ? DirectoryReason.CONNECT_FAILED : reason);
        } catch (RuntimeException e) {
            connection.close();
            throw new DirectoryFailure(DirectoryReason.SETTINGS_INVALID);
        }
    }

    /**
     * The one user entry whose sAMAccountName equals the typed name, below the domain DN. The filter is built
     * from typed components, so the value is escaped per RFC 4515 and can never add a filter term.
     */
    static String findUser(LDAPConnection connection, DirectoryProfile profile, String userName)
            throws DirectoryFailure, LDAPException {
        return findUserEntry(connection, profile, userName).dn();
    }

    /** The matched entry's DN (the identity) and its sAMAccountName (display only; the typed name when the attribute is absent). */
    record FoundUser(String dn, String samAccountName) { }

    static FoundUser findUserEntry(LDAPConnection connection, DirectoryProfile profile, String userName)
            throws DirectoryFailure, LDAPException {
        // objectCategory=person excludes computer and trust accounts, which inherit objectClass=user in AD.
        Filter filter = Filter.createANDFilter(Filter.createEqualityFilter("objectCategory", "person"),
                Filter.createEqualityFilter("objectClass", "user"),
                Filter.createEqualityFilter("sAMAccountName", userName));
        SearchRequest request = new SearchRequest(profile.domainDn(), SearchScope.SUB, DereferencePolicy.NEVER, 2,
                profile.timeoutSeconds(), false, filter, "sAMAccountName");
        SearchResult result;
        try {
            result = connection.search(request);
        } catch (LDAPSearchException e) {
            if (e.getResultCode() == ResultCode.SIZE_LIMIT_EXCEEDED) throw new DirectoryFailure(DirectoryReason.USER_AMBIGUOUS);
            if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) throw new DirectoryFailure(DirectoryReason.USER_NOT_FOUND);
            throw e;
        }
        if (result.getEntryCount() == 0) throw new DirectoryFailure(DirectoryReason.USER_NOT_FOUND);
        if (result.getEntryCount() > 1) throw new DirectoryFailure(DirectoryReason.USER_AMBIGUOUS);
        com.unboundid.ldap.sdk.SearchResultEntry entry = result.getSearchEntries().get(0);
        String dn = entry.getDN();
        String sam = entry.getAttributeValue("sAMAccountName");
        // The bind target must be a real entry below the domain DN: never the null DN (an anonymous bind) and
        // never an entry of another naming context.
        try {
            DN parsed = new DN(dn);
            if (parsed.isNullDN() || !parsed.isDescendantOf(new DN(profile.domainDn()), false)) {
                throw new DirectoryFailure(DirectoryReason.USER_NOT_FOUND);
            }
        } catch (LDAPException e) {
            throw new DirectoryFailure(DirectoryReason.USER_NOT_FOUND);
        }
        return new FoundUser(dn, sam == null || sam.isBlank() ? userName : sam);
    }

    /** Transitive membership: the group itself answers whether the user is in it, directly or nested. */
    static boolean member(LDAPConnection connection, DirectoryProfile profile, String groupDn, String userDn)
            throws LDAPException {
        SearchRequest request = new SearchRequest(groupDn, SearchScope.BASE, DereferencePolicy.NEVER, 1,
                profile.timeoutSeconds(), false, Filter.createExtensibleMatchFilter("member", IN_CHAIN, false, userDn), "1.1");
        try {
            return connection.search(request).getEntryCount() == 1;
        } catch (LDAPSearchException e) {
            if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) return false;
            throw e;
        }
    }

    /** Whether an entry exists at {@code dn} (base search). {@code activeUser} also requires an enabled AD user. */
    static boolean exists(LDAPConnection connection, DirectoryProfile profile, String dn, boolean activeUser)
            throws LDAPException {
        Filter filter = activeUser
                ? Filter.createANDFilter(Filter.createEqualityFilter("objectCategory", "person"),
                        Filter.createEqualityFilter("objectClass", "user"), Filter.createNOTFilter(
                        Filter.createExtensibleMatchFilter("userAccountControl", BIT_AND, false, "2")))
                : Filter.createPresenceFilter("objectClass");
        SearchRequest request = new SearchRequest(dn, SearchScope.BASE, DereferencePolicy.NEVER, 1,
                profile.timeoutSeconds(), false, filter, "1.1");
        try {
            return connection.search(request).getEntryCount() == 1;
        } catch (LDAPSearchException e) {
            if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) return false;
            throw e;
        }
    }

    /**
     * A failure before the server answered: TLS, timeout or connect. Null when the server answered with an LDAP
     * result (the caller decides what that result means). Order matters: a certificate decision is more specific
     * than "the handshake failed", and a timeout inside the handshake is still a timeout.
     */
    static DirectoryReason classify(LDAPException e) {
        boolean nameMismatch = false, certificate = false, timeout = false, handshake = false;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof DirectoryTrustPolicy.NameMismatch) nameMismatch = true;
            else if (cause instanceof java.security.cert.CertificateException) certificate = true;
            else if (cause instanceof java.net.SocketTimeoutException) timeout = true;
            else if (cause instanceof javax.net.ssl.SSLException || inHandshake(cause)) handshake = true;
        }
        if (nameMismatch) return DirectoryReason.TLS_NAME_MISMATCH;
        if (certificate) return DirectoryReason.TLS_TRUST_FAILED;
        if (timeout) return DirectoryReason.TIMEOUT;
        if (handshake) return DirectoryReason.TLS_HANDSHAKE_FAILED;
        ResultCode code = e.getResultCode();
        if (code == ResultCode.TIMEOUT) return DirectoryReason.TIMEOUT;
        if (code == ResultCode.CONNECT_ERROR || code == ResultCode.SERVER_DOWN || code == ResultCode.LOCAL_ERROR
                || code == ResultCode.UNAVAILABLE) {
            return DirectoryReason.CONNECT_FAILED;
        }
        return null;
    }

    /**
     * Whether this throwable was raised inside a TLS handshake. UnboundID's connect thread runs
     * {@code SSLSocket.startHandshake()} itself, and a Windows domain controller that cannot negotiate (no shared
     * protocol version or cipher suite) resets the connection: a plain {@code SocketException} with no
     * SSLException around it. TCP had already connected, so it must not read as "could not connect". The public
     * API method name is the one stable marker of that phase.
     */
    private static boolean inHandshake(Throwable cause) {
        if (!(cause instanceof java.io.IOException)) return false;
        for (StackTraceElement frame : cause.getStackTrace()) {
            if ("startHandshake".equals(frame.getMethodName())) return true;
        }
        return false;
    }

    /** A failure after a server answered a search: its classified reason, or {@code search_failed}. */
    static DirectoryReason afterAnswer(LDAPException e) {
        DirectoryReason reason = classify(e);
        return reason == null ? DirectoryReason.SEARCH_FAILED : reason;
    }

    /**
     * A bind the server refused. Result 8 / 13 is AD's "LDAP signing or TLS required" over plain LDAP: reported
     * as {@code strong_auth_required} so the administrator turns LDAPS on instead of rotating a password.
     */
    static DirectoryReason bindRefusal(LDAPException e, DirectoryReason otherwise) {
        ResultCode code = e.getResultCode();
        if (code == ResultCode.STRONG_AUTH_REQUIRED || code == ResultCode.CONFIDENTIALITY_REQUIRED) {
            return DirectoryReason.STRONG_AUTH_REQUIRED;
        }
        return otherwise;
    }
}
