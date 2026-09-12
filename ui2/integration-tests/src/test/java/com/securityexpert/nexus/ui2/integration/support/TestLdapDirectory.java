package com.securityexpert.nexus.ui2.integration.support;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;

/**
 * A real, in-process LDAP directory server for the identity tests in
 * {@code identity/} — {@code com.unboundid.ldap.listener.InMemoryDirectoryServer},
 * shipped by the same UnboundID LDAP SDK the repository already depends on
 * for {@code ldap-adapter} (contract §4). This is not a stub, a mock, or a
 * fake protocol implementation: it accepts a real TCP connection on
 * {@code 127.0.0.1}, speaks the real LDAP wire protocol, evaluates a real
 * {@code SimpleBindRequest} against a real password, and answers a real
 * {@code SearchRequest} against real directory entries — everything
 * {@link com.securityexpert.nexus.ui2.identity.ldap.UnboundIdOperatorBindAdapter}
 * does against a corporate directory, over the identical SDK client classes.
 *
 * <p>Every DN, username, password and group here is a synthetic test value
 * invented for this harness (AGENTS.md "no real credentials, no real
 * directory data"). Schema enforcement is disabled
 * ({@link InMemoryDirectoryServerConfig#setSchema}, {@code null}) so the
 * harness can use a minimal, made-up {@code member}-bearing group shape
 * without shipping a full corporate LDAP schema — the adapter under test
 * only ever reads the {@code member} attribute and the entry DN, both of
 * which behave identically with or without schema checking.</p>
 *
 * <p><b>Fail closed, like {@link Ui2PostgresFixture}:</b> if the in-memory
 * server cannot start, {@link #start()} throws. There is no fallback, no
 * skip, and no faked directory.</p>
 */
public final class TestLdapDirectory implements AutoCloseable {

    public static final String BASE_DN = "dc=example,dc=com";
    public static final String PEOPLE_BASE_DN = "ou=people,dc=example,dc=com";
    public static final String GROUPS_BASE_DN = "ou=groups,dc=example,dc=com";
    public static final String BIND_DN_TEMPLATE = "cn=%s,ou=people,dc=example,dc=com";

    private final InMemoryDirectoryServer server;

    private TestLdapDirectory(InMemoryDirectoryServer server) {
        this.server = server;
    }

    /** Starts a fresh, empty (but base-structure-seeded) in-memory directory listening on a free local port. */
    public static TestLdapDirectory start() {
        try {
            InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
            // Schema-less: this harness's entries use a minimal made-up
            // shape (see class Javadoc), never real corporate schema.
            config.setSchema(null);
            config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test-ldap", 0));
            InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
            server.startListening();
            server.add(new Entry(BASE_DN, new Attribute("objectClass", "top", "domain"),
                    new Attribute("dc", "example")));
            server.add(new Entry(PEOPLE_BASE_DN, new Attribute("objectClass", "top", "organizationalUnit"),
                    new Attribute("ou", "people")));
            server.add(new Entry(GROUPS_BASE_DN, new Attribute("objectClass", "top", "organizationalUnit"),
                    new Attribute("ou", "groups")));
            return new TestLdapDirectory(server);
        } catch (LDAPException e) {
            throw new IllegalStateException(
                    "could not start the in-process test LDAP directory (UnboundID InMemoryDirectoryServer): "
                            + e.getResultCode(), e);
        }
    }

    public String host() {
        return "127.0.0.1";
    }

    public int port() {
        return server.getListenPort();
    }

    /** One person entry under {@link #PEOPLE_BASE_DN}. {@code password} is a synthetic test value, never logged. */
    public String addPerson(String cn, String password) {
        String dn = String.format(BIND_DN_TEMPLATE, cn);
        try {
            server.add(new Entry(dn,
                    new Attribute("objectClass", "top", "person"),
                    new Attribute("cn", cn),
                    new Attribute("sn", cn),
                    new Attribute("userPassword", password)));
        } catch (LDAPException e) {
            throw new IllegalStateException("could not seed test directory person entry: " + e.getResultCode(), e);
        }
        return dn;
    }

    /**
     * One group entry under {@link #GROUPS_BASE_DN} whose {@code member}
     * attribute lists the given member DNs — the exact shape
     * {@code UnboundIdOperatorBindAdapter#readGroupReferences} searches
     * for ({@code Filter.createEqualityFilter("member", bindDn)}).
     */
    public String addGroup(String cn, String... memberDns) {
        String dn = "cn=" + cn + "," + GROUPS_BASE_DN;
        try {
            Entry entry = new Entry(dn,
                    new Attribute("objectClass", "top", "groupOfNames"),
                    new Attribute("cn", cn));
            if (memberDns.length > 0) {
                entry.addAttribute(new Attribute("member", memberDns));
            }
            server.add(entry);
        } catch (LDAPException e) {
            throw new IllegalStateException("could not seed test directory group entry: " + e.getResultCode(), e);
        }
        return dn;
    }

    /**
     * Stops accepting connections and closes every existing one — used by
     * the directory-unreachable tests to prove a real connectivity failure
     * (never a stubbed one) against the adapter under test.
     */
    public void stopListening() {
        server.shutDown(true);
    }

    @Override
    public void close() {
        server.shutDown(true);
    }
}
