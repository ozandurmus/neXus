package com.securityexpert.nexus.ui2.identity.ldap;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;

/**
 * A synthetic Active Directory shaped in-memory directory (UnboundID InMemoryDirectoryServer) for the
 * service-bind tests. All names and passwords are synthetic ({@code example.local}).
 *
 * <p>The in-memory server does not implement AD's extensible matching rules, so an interceptor evaluates them
 * here, without touching production code: {@code (member:1.2.840.113556.1.4.1941:=X)} becomes
 * {@code (|(member=X)(member=G1)...)} over every group that transitively contains X, and
 * {@code (userAccountControl:1.2.840.113556.1.4.803:=2)} becomes {@code (userAccountControl=514)}.</p>
 */
final class ActiveDirectoryFixture implements AutoCloseable {
    static final String BASE_DN = "DC=example,DC=local";
    static final String USERS_OU = "OU=People,DC=example,DC=local";
    static final String GROUPS_OU = "OU=Groups,DC=example,DC=local";
    static final String ADMIN_GROUP = "CN=nexus-admins,OU=Groups,DC=example,DC=local";
    static final String USERS_GROUP = "CN=nexus-users,OU=Groups,DC=example,DC=local";
    static final String SERVICE_DN = "CN=svc-nexus,OU=People,DC=example,DC=local";

    enum Listener { PLAIN, TLS_DNS_LOCALHOST, TLS_IP_LOOPBACK }

    final InMemoryDirectoryServer server;
    final char[] servicePassword;

    private ActiveDirectoryFixture(InMemoryDirectoryServer server, char[] servicePassword) {
        this.server = server;
        this.servicePassword = servicePassword;
    }

    static ActiveDirectoryFixture start(Listener listener) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.setSchema(null);
        String password = UUID.randomUUID().toString();
        config.addAdditionalBindCredentials(SERVICE_DN, password);
        AdMatchingRules rules = new AdMatchingRules();
        config.addInMemoryOperationInterceptor(rules);
        config.setListenerConfigs(switch (listener) {
            case PLAIN -> InMemoryListenerConfig.createLDAPConfig("plain", InetAddress.getLoopbackAddress(), 0, null);
            case TLS_DNS_LOCALHOST -> tls(Tls.material().dnsLeaf());
            case TLS_IP_LOOPBACK -> tls(Tls.material().ipLeaf());
        });
        InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
        rules.server = server;
        server.startListening();
        server.add(new Entry(BASE_DN, new Attribute("objectClass", "top", "domain"), new Attribute("dc", "example")));
        server.add(new Entry(USERS_OU, new Attribute("objectClass", "top", "organizationalUnit"), new Attribute("ou", "People")));
        server.add(new Entry(GROUPS_OU, new Attribute("objectClass", "top", "organizationalUnit"), new Attribute("ou", "Groups")));
        server.add(group(ADMIN_GROUP, "nexus-admins"));
        server.add(group(USERS_GROUP, "nexus-users"));
        return new ActiveDirectoryFixture(server, password.toCharArray());
    }

    private static InMemoryListenerConfig tls(SSLContext context) throws LDAPException {
        return InMemoryListenerConfig.createLDAPSConfig("tls", InetAddress.getLoopbackAddress(), 0,
                context.getServerSocketFactory(), context.getSocketFactory(), false, false);
    }

    private static Entry group(String dn, String cn) {
        return new Entry(dn, new Attribute("objectClass", "top", "group"), new Attribute("cn", cn));
    }

    int port() {
        return server.getListenPort();
    }

    /** An enabled AD user with a synthetic password; returns its DN. */
    String addUser(String samAccountName, String password, String... groups) throws LDAPException {
        String dn = "CN=" + samAccountName + "," + USERS_OU;
        server.add(new Entry(dn, new Attribute("objectClass", "top", "person", "organizationalPerson", "user"),
                new Attribute("cn", samAccountName), new Attribute("sAMAccountName", samAccountName), new Attribute("objectCategory", "person"),
                new Attribute("userAccountControl", "512"), new Attribute("userPassword", password)));
        for (String group : groups) addMember(group, dn);
        return dn;
    }

    String addGroup(String cn, String... members) throws LDAPException {
        String dn = "CN=" + cn + "," + GROUPS_OU;
        server.add(group(dn, cn));
        for (String member : members) addMember(dn, member);
        return dn;
    }

    void addMember(String group, String member) throws LDAPException {
        server.modify(group, new Modification(ModificationType.ADD, "member", member));
    }

    void removeMember(String group, String member) throws LDAPException {
        server.modify(group, new Modification(ModificationType.DELETE, "member", member));
    }

    void disable(String userDn) throws LDAPException {
        server.modify(userDn, new Modification(ModificationType.REPLACE, "userAccountControl", "514"));
    }

    DirectoryProfile profile(List<String> servers, boolean ldaps, DirectoryProfile.TrustMode mode, String pem) {
        return new DirectoryProfile(DirectoryProfile.DEFAULT_PROFILE_ID, servers, port(), BASE_DN, ADMIN_GROUP,
                USERS_GROUP, SERVICE_DN, 3, ldaps, mode, pem);
    }

    @Override
    public void close() {
        server.shutDown(true);
    }

    /** Evaluates the two AD matching rules the production code sends. */
    static final class AdMatchingRules extends InMemoryOperationInterceptor {
        volatile InMemoryDirectoryServer server;

        @Override
        public void processSearchRequest(InMemoryInterceptedSearchRequest request) throws LDAPException {
            com.unboundid.ldap.sdk.ReadOnlySearchRequest original = request.getRequest();
            Filter rewritten = rewrite(original.getFilter());
            if (rewritten != original.getFilter()) {
                SearchRequest copy = original.duplicate();
                copy.setFilter(rewritten);
                request.setRequest(copy);
            }
        }

        private Filter rewrite(Filter filter) throws LDAPException {
            switch (filter.getFilterType()) {
                case Filter.FILTER_TYPE_AND, Filter.FILTER_TYPE_OR -> {
                    Filter[] parts = filter.getComponents();
                    Filter[] out = new Filter[parts.length];
                    boolean changed = false;
                    for (int i = 0; i < parts.length; i++) {
                        out[i] = rewrite(parts[i]);
                        changed |= out[i] != parts[i];
                    }
                    if (!changed) return filter;
                    return filter.getFilterType() == Filter.FILTER_TYPE_AND ? Filter.createANDFilter(out) : Filter.createORFilter(out);
                }
                case Filter.FILTER_TYPE_NOT -> {
                    Filter inner = rewrite(filter.getNOTComponent());
                    return inner == filter.getNOTComponent() ? filter : Filter.createNOTFilter(inner);
                }
                case Filter.FILTER_TYPE_EXTENSIBLE_MATCH -> {
                    if (DirectoryConnection.IN_CHAIN.equals(filter.getMatchingRuleID())) {
                        String member = filter.getAssertionValue();
                        List<Filter> any = new ArrayList<>();
                        any.add(Filter.createEqualityFilter("member", member));
                        for (String group : containing(member)) any.add(Filter.createEqualityFilter("member", group));
                        return Filter.createORFilter(any);
                    }
                    if (DirectoryConnection.BIT_AND.equals(filter.getMatchingRuleID())
                            && "userAccountControl".equals(filter.getAttributeName()) && "2".equals(filter.getAssertionValue())) {
                        return Filter.createEqualityFilter("userAccountControl", "514");
                    }
                    throw new LDAPException(com.unboundid.ldap.sdk.ResultCode.INAPPROPRIATE_MATCHING);
                }
                default -> {
                    return filter;
                }
            }
        }

        /** Every group that contains {@code dn} directly or through nested groups. */
        private Set<String> containing(String dn) throws LDAPException {
            Set<String> found = new LinkedHashSet<>();
            List<String> frontier = new ArrayList<>(List.of(dn));
            while (!frontier.isEmpty()) {
                String next = frontier.remove(0);
                for (SearchResultEntry entry : server.search(BASE_DN, SearchScope.SUB,
                        Filter.createEqualityFilter("member", next)).getSearchEntries()) {
                    if (found.add(entry.getDN())) frontier.add(entry.getDN());
                }
            }
            return found;
        }
    }

    /** One synthetic TLS set per test JVM: a CA, two leaves it signed, and an unrelated CA. */
    record Tls(String caPem, String otherCaPem, String dnsLeafPem, SSLContext dnsLeaf, SSLContext ipLeaf) {
        private static Tls material;

        static synchronized Tls material() throws Exception {
            if (material != null) return material;
            Path dir = Files.createTempDirectory("ad-fixture-tls");
            Path pin = dir.resolve("pin");
            String pinValue = UUID.randomUUID().toString();
            Files.writeString(pin, pinValue, StandardCharsets.UTF_8);
            Path ca = dir.resolve("ca.p12");
            keytool(dir, pin, "-genkeypair", "-alias", "ca", "-dname", "CN=Synthetic AD Test CA", "-keyalg", "RSA",
                    "-keysize", "2048", "-validity", "30", "-ext", "bc=ca:true", "-ext", "ku=keyCertSign",
                    "-keystore", ca.toString(), "-storetype", "PKCS12");
            keytool(dir, pin, "-exportcert", "-alias", "ca", "-rfc", "-keystore", ca.toString(), "-file", dir.resolve("ca.pem").toString());
            Path other = dir.resolve("other.p12");
            keytool(dir, pin, "-genkeypair", "-alias", "other", "-dname", "CN=Unrelated Test CA", "-keyalg", "RSA",
                    "-keysize", "2048", "-validity", "30", "-ext", "bc=ca:true", "-ext", "ku=keyCertSign",
                    "-keystore", other.toString(), "-storetype", "PKCS12");
            keytool(dir, pin, "-exportcert", "-alias", "other", "-rfc", "-keystore", other.toString(), "-file", dir.resolve("other.pem").toString());
            SSLContext dns = leaf(dir, pin, pinValue, ca, "dns", "CN=localhost", "san=dns:localhost");
            SSLContext ip = leaf(dir, pin, pinValue, ca, "ip", "CN=loopback", "san=ip:127.0.0.1");
            material = new Tls(Files.readString(dir.resolve("ca.pem")), Files.readString(dir.resolve("other.pem")),
                    Files.readString(dir.resolve("dns.pem")), dns, ip);
            return material;
        }

        private static SSLContext leaf(Path dir, Path pin, String pinValue, Path ca, String name, String dname, String san)
                throws Exception {
            Path store = dir.resolve(name + ".p12");
            keytool(dir, pin, "-genkeypair", "-alias", "server", "-dname", dname, "-keyalg", "RSA", "-keysize", "2048",
                    "-ext", san, "-keystore", store.toString(), "-storetype", "PKCS12");
            keytool(dir, pin, "-certreq", "-alias", "server", "-keystore", store.toString(), "-file", dir.resolve(name + ".csr").toString());
            keytool(dir, pin, "-gencert", "-alias", "ca", "-keystore", ca.toString(), "-infile", dir.resolve(name + ".csr").toString(),
                    "-outfile", dir.resolve(name + ".pem").toString(), "-rfc", "-ext", san,
                    "-ext", "ku=digitalSignature,keyEncipherment", "-ext", "eku=serverAuth", "-validity", "30");
            keytool(dir, pin, "-exportcert", "-alias", "ca", "-rfc", "-keystore", ca.toString(), "-file", dir.resolve(name + "-ca.pem").toString());
            keytool(dir, pin, "-importcert", "-noprompt", "-alias", "ca", "-file", dir.resolve(name + "-ca.pem").toString(), "-keystore", store.toString());
            keytool(dir, pin, "-importcert", "-alias", "server", "-file", dir.resolve(name + ".pem").toString(), "-keystore", store.toString());
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(store)) {
                keys.load(in, pinValue.toCharArray());
            }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, pinValue.toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), null, null);
            return context;
        }

        private static void keytool(Path dir, Path pin, String... args) throws Exception {
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
            command.addAll(List.of(args));
            command.addAll(List.of("-storepass:file", pin.toString()));
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("keytool.log").toFile())).start();
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool failed: " + args[0]);
            }
        }
    }
}
