package com.securityexpert.nexus.ui2.identity.ldap;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustStoreTrustManager;

import com.securityexpert.nexus.ui2.platform.LdapRevalidationPort;
import com.securityexpert.nexus.ui2.platform.Result;
import com.securityexpert.nexus.ui2.platform.SecretFile;

/**
 * The {@code DIRECTORY-POSTURE} (D-6) re-validation adapter (C3 §2.1,
 * §4.4). A small bounded connection pool (min 0, max 4, tunable), idle
 * connections recycled after 60s. <b>Inert while
 * {@code directoryPostureEnabled} is {@code false}</b> (C3 §4.4.3, AC-12):
 * the constructor opens no pool at all unless enabled, and
 * {@link #revalidate} refuses defensively even if somehow called while
 * disabled.
 *
 * <p>The service-account password is never cached for process lifetime —
 * re-read from its {@code _FILE} mount on every cycle (C3 §4.4.2), so
 * rotation applies without a restart.</p>
 */
public final class UnboundIdRevalidationAdapter implements LdapRevalidationPort {

    private static final int MIN_POOL_SIZE = 0;
    private static final int MAX_POOL_SIZE = 4;
    private static final long IDLE_RECYCLE_MILLIS = 60_000L;

    private final boolean directoryPostureEnabled;
    private final String host;
    private final int port;
    private final Path caBundlePath;
    private final String serviceAccountBindDn;
    private final Path serviceAccountPasswordFile;
    private final String groupSearchBaseDn;

    private final AtomicReference<LDAPConnectionPool> pool = new AtomicReference<>();

    public UnboundIdRevalidationAdapter(boolean directoryPostureEnabled, String host, int port,
            Path caBundlePath, String serviceAccountBindDn, Path serviceAccountPasswordFile,
            String groupSearchBaseDn) {
        this.directoryPostureEnabled = directoryPostureEnabled;
        this.host = host;
        this.port = port;
        this.caBundlePath = caBundlePath;
        this.serviceAccountBindDn = serviceAccountBindDn;
        this.serviceAccountPasswordFile = serviceAccountPasswordFile;
        this.groupSearchBaseDn = groupSearchBaseDn;
        // C3 §4.4.3: while disabled, no code path opens a connection pool
        // at all -- the pool field simply stays null for this adapter's
        // entire lifetime.
    }

    @Override
    public boolean directoryPostureEnabled() {
        return directoryPostureEnabled;
    }

    @Override
    public Result<Set<String>> revalidate(String actorFingerprint, String bindDn) {
        if (!directoryPostureEnabled) {
            // Defensive fail-closed: this adapter must never be invoked
            // while disabled, but if it is, it never opens a connection.
            return Result.err("directory_posture_disabled", "re-validation adapter is inert while disabled");
        }
        LDAPConnectionPool connectionPool;
        try {
            connectionPool = poolOrOpen();
        } catch (GeneralSecurityException | LDAPException e) {
            return Result.err("directory_unavailable", "service-account bind unavailable");
        }

        // Re-read the service-account password fresh on every cycle (C3
        // §4.4.2) -- never cached across cycles, so rotation applies
        // without a restart.
        String password = SecretFile.readRequired(serviceAccountPasswordFile, "ldap_service_account");
        LDAPConnection connection = null;
        try {
            connection = connectionPool.getConnection();
            connection.bind(serviceAccountBindDn, password);
            SearchRequest request = new SearchRequest(groupSearchBaseDn, SearchScope.SUB,
                    Filter.createEqualityFilter("member", bindDn));
            SearchResult result = connection.search(request);
            Set<String> groupReferences = new LinkedHashSet<>();
            for (SearchResultEntry entry : result.getSearchEntries()) {
                groupReferences.add(entry.getDN());
            }
            connectionPool.releaseConnection(connection);
            connection = null;
            return Result.ok(groupReferences);
        } catch (LDAPException e) {
            // A failed connection is discarded, never retried on the same
            // handle (C3 §2.1).
            if (connection != null) {
                connectionPool.releaseDefunctConnection(connection);
                connection = null;
            }
            return Result.err("directory_unavailable", "re-validation bind or search failed");
        } finally {
            if (connection != null) {
                connectionPool.releaseConnection(connection);
            }
        }
    }

    private LDAPConnectionPool poolOrOpen() throws GeneralSecurityException, LDAPException {
        LDAPConnectionPool existing = pool.get();
        if (existing != null) {
            return existing;
        }
        SSLUtil sslUtil = new SSLUtil(new TrustStoreTrustManager(caBundlePath.toString()));
        LDAPConnection seed = new LDAPConnection(sslUtil.createSSLSocketFactory(), host, port);
        LDAPConnectionPool created = new LDAPConnectionPool(seed, MIN_POOL_SIZE, MAX_POOL_SIZE);
        created.setMaxConnectionAgeMillis(IDLE_RECYCLE_MILLIS);
        if (pool.compareAndSet(null, created)) {
            return created;
        }
        created.close();
        return pool.get();
    }
}
