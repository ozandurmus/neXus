package com.securityexpert.nexus.ui2.identity.ldap;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustStoreTrustManager;

import com.securityexpert.nexus.ui2.platform.LdapOperatorBindPort;
import com.securityexpert.nexus.ui2.platform.PrincipalFingerprint;
import com.securityexpert.nexus.ui2.platform.Result;

/**
 * UnboundID-backed operator-bind adapter (C3 §2.1, §2.2, §2.3). One-shot
 * {@code SimpleBindRequest} per login, over a TLS connection verified
 * against the corporate CA bundle; opened, bind attempted, group set read,
 * connection closed immediately — never pooled across logins.
 *
 * <p>{@code ldap-adapter} depends only on {@code platform-core} (DIR-5): no
 * web-framework type appears anywhere in this class.</p>
 */
public final class UnboundIdOperatorBindAdapter implements LdapOperatorBindPort {

    private final String host;
    private final int port;
    private final SSLSocketFactory sslSocketFactory;
    private final String groupSearchBaseDn;
    private final String bindDnTemplate;

    private UnboundIdOperatorBindAdapter(String host, int port, SSLSocketFactory sslSocketFactory,
            String groupSearchBaseDn, String bindDnTemplate) {
        this.host = host;
        this.port = port;
        this.sslSocketFactory = sslSocketFactory;
        this.groupSearchBaseDn = groupSearchBaseDn;
        this.bindDnTemplate = bindDnTemplate;
    }

    /**
     * Reads the corporate CA bundle and builds the TLS socket factory at
     * construction time. Per C3 §2.2: an unreadable trust-material bundle
     * fails closed here — this method throws {@link LdapStartupException}
     * rather than returning a lazily-failing adapter, so a composition root
     * that calls this at startup refuses to start.
     *
     * @param bindDnTemplate    e.g. {@code "cn=%s,ou=people,dc=example,dc=com"};
     *                           {@code %s} is replaced with the submitted
     *                           username — never logged as a literal DN
     * @param groupSearchBaseDn the base DN searched for {@code member}-class
     *                           group entries after a successful bind
     */
    public static UnboundIdOperatorBindAdapter create(String host, int port, Path caBundlePath,
            String bindDnTemplate, String groupSearchBaseDn) {
        // TrustStoreTrustManager validates the file lazily (only on first
        // TLS handshake) -- C3 §2.2 requires a hard failure before any
        // network call, so this checks readability eagerly, here.
        if (!java.nio.file.Files.isReadable(caBundlePath)) {
            throw new LdapStartupException("TLS trust material unreadable");
        }
        SSLSocketFactory socketFactory;
        try {
            SSLUtil sslUtil = new SSLUtil(new TrustStoreTrustManager(caBundlePath.toString()));
            socketFactory = sslUtil.createSSLSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new LdapStartupException("TLS trust material unreadable", e);
        }
        return new UnboundIdOperatorBindAdapter(host, port, socketFactory, groupSearchBaseDn, bindDnTemplate);
    }

    /** Test-only constructor: an unverified {@link SocketFactory} for a test LDAP directory (C3 §4.4.3). */
    static UnboundIdOperatorBindAdapter forTestDirectory(String host, int port, SocketFactory plainSocketFactory,
            String bindDnTemplate, String groupSearchBaseDn) {
        return new UnboundIdOperatorBindAdapter(host, port, null, groupSearchBaseDn, bindDnTemplate)
                .withSocketFactory(plainSocketFactory);
    }

    private SocketFactory testSocketFactory;

    private UnboundIdOperatorBindAdapter withSocketFactory(SocketFactory factory) {
        this.testSocketFactory = factory;
        return this;
    }

    @Override
    public Result<OperatorBindOutcome> bind(String username, char[] password) {
        // C3 §2.2: the RFC 4513 unauthenticated-bind trap -- refused before
        // any LDAP call, same generic surface as a real bind failure.
        if (password == null || password.length == 0) {
            return Result.err(FailureCodes.EMPTY_PASSWORD, "operator bind refused before any directory call");
        }
        if (username == null || username.isBlank()) {
            zero(password);
            return Result.err(FailureCodes.INVALID_CREDENTIALS, "operator bind rejected");
        }

        String bindDn = String.format(bindDnTemplate, username);
        byte[] passwordBytes = toUtf8Bytes(password);
        LDAPConnection connection = null;
        try {
            connection = openConnection();
            connection.bind(new SimpleBindRequest(bindDn, passwordBytes));
            Set<String> groupReferences = readGroupReferences(connection, bindDn);
            return Result.ok(new OperatorBindOutcome(PrincipalFingerprint.of(bindDn), groupReferences));
        } catch (LDAPException e) {
            if (isConnectivityFailure(e.getResultCode())) {
                return Result.err(FailureCodes.DIRECTORY_UNAVAILABLE, "directory unreachable");
            }
            // C3 §2.2/SR-D7: never distinguishes unknown-identity from
            // wrong-password -- one generic outcome for every bind failure.
            return Result.err(FailureCodes.INVALID_CREDENTIALS, "operator bind rejected");
        } catch (java.security.GeneralSecurityException e) {
            return Result.err(FailureCodes.DIRECTORY_UNAVAILABLE, "directory unreachable");
        } finally {
            zero(password);
            Arrays.fill(passwordBytes, (byte) 0);
            if (connection != null) {
                connection.close();
            }
        }
    }

    private LDAPConnection openConnection() throws LDAPException, GeneralSecurityException {
        LDAPConnectionOptions options = new LDAPConnectionOptions();
        SocketFactory factory = testSocketFactory != null ? testSocketFactory : sslSocketFactory;
        return new LDAPConnection(factory, options, host, port);
    }

    private static boolean isConnectivityFailure(ResultCode resultCode) {
        return resultCode == ResultCode.CONNECT_ERROR
                || resultCode == ResultCode.SERVER_DOWN
                || resultCode == ResultCode.TIMEOUT
                || resultCode == ResultCode.UNAVAILABLE;
    }

    private Set<String> readGroupReferences(LDAPConnection connection, String bindDn) throws LDAPException {
        SearchRequest request = new SearchRequest(groupSearchBaseDn, SearchScope.SUB,
                Filter.createEqualityFilter("member", bindDn));
        SearchResult result = connection.search(request);
        Set<String> groupReferences = new LinkedHashSet<>();
        for (SearchResultEntry entry : result.getSearchEntries()) {
            groupReferences.add(entry.getDN());
        }
        return groupReferences;
    }

    /**
     * Converts a password held in a {@code char[]} directly to UTF-8 bytes,
     * never allocating a {@code String} at any point (C3 §2.3,
     * architecture-tested by {@code PasswordNeverInStringTest}).
     */
    private static byte[] toUtf8Bytes(char[] password) {
        java.nio.charset.CharsetEncoder encoder = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            ByteBuffer buffer = encoder.encode(CharBuffer.wrap(password));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (java.nio.charset.CharacterCodingException e) {
            return new byte[0];
        }
    }

    private static void zero(char[] password) {
        Arrays.fill(password, '\0');
    }
}
