package com.securityexpert.nexus.ui2.identity.ldap;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.unboundid.ldap.listener.*;
import com.unboundid.ldap.listener.interceptor.*;
import com.unboundid.ldap.sdk.*;
import com.securityexpert.nexus.ui2.platform.*;

/** Generated synthetic CA/leaf chains and isolated SDK directory; no corporate data. */
class DirectoryTlsTest {
    @TempDir static Path tmp;
    static Path pinFile;
    static char[] pin;
    static Path caPem, jks, pkcs12, valid, expired, untrusted;
    static final String USER = "cn=ordinary,dc=example,dc=com";
    static final String GROUP = "cn=access,ou=groups,dc=example,dc=com";
    static byte[] credential;

    @BeforeAll static void certificates() throws Exception {
        pin = UUID.randomUUID().toString().toCharArray();
        pinFile = tmp.resolve("store-pin");
        Files.writeString(pinFile, java.nio.CharBuffer.wrap(pin));
        credential = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        Path root = tmp.resolve("root.p12");
        caPem = tmp.resolve("ca.pem");
        keytool("-genkeypair", "-alias", "ca", "-dname", "CN=Synthetic CA", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-ext", "bc=ca:true", "-ext", "ku=keyCertSign", "-keystore", root.toString(), "-storetype", "PKCS12");
        keytool("-exportcert", "-alias", "ca", "-rfc", "-keystore", root.toString(), "-file", caPem.toString());
        jks = tmp.resolve("trust.jks"); pkcs12 = tmp.resolve("trust.p12");
        for (Path trust : List.of(jks, pkcs12)) keytool("-importcert", "-noprompt", "-alias", "ca", "-file", caPem.toString(),
                "-keystore", trust.toString(), "-storetype", trust == jks ? "JKS" : "PKCS12");
        valid = leaf("valid", root, false);
        expired = leaf("expired", root, true);
        untrusted = tmp.resolve("untrusted.p12");
        keytool("-genkeypair", "-alias", "server", "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-ext", "san=dns:localhost", "-keystore", untrusted.toString(), "-storetype", "PKCS12");
    }

    @AfterAll static void clear() { Arrays.fill(pin, '\0'); Arrays.fill(credential, (byte) 0); }

    static Path leaf(String name, Path root, boolean expired) throws Exception {
        Path store = tmp.resolve(name + ".p12"), csr = tmp.resolve(name + ".csr"), signed = tmp.resolve(name + ".pem");
        keytool("-genkeypair", "-alias", "server", "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048",
                "-ext", "san=dns:localhost", "-keystore", store.toString(), "-storetype", "PKCS12");
        keytool("-certreq", "-alias", "server", "-keystore", store.toString(), "-file", csr.toString());
        List<String> args = new ArrayList<>(List.of("-gencert", "-alias", "ca", "-keystore", root.toString(), "-infile", csr.toString(),
                "-outfile", signed.toString(), "-rfc", "-ext", "san=dns:localhost", "-ext", "ku=digitalSignature,keyEncipherment",
                "-ext", "eku=serverAuth", "-validity", expired ? "1" : "365"));
        if (expired) args.addAll(List.of("-startdate", "-10d"));
        keytool(args.toArray(String[]::new));
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", caPem.toString(), "-keystore", store.toString());
        keytool("-importcert", "-alias", "server", "-file", signed.toString(), "-keystore", store.toString());
        return store;
    }

    static void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        command.addAll(List.of("-storepass:file", pinFile.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(tmp.resolve("keytool.log").toFile()).start();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "synthetic certificate generation timeout");
        assertEquals(0, process.exitValue(), "synthetic certificate generation failed");
    }

    static SSLContext serverContext(Path path) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(path)) { keys.load(in, pin); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, pin);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context;
    }

    static final class Server implements AutoCloseable {
        final InMemoryDirectoryServer server;
        final AtomicInteger binds = new AtomicInteger(), searches = new AtomicInteger();
        boolean suppressProof, denyRead;
        Server(Path certificate, DirectoryProfile.Transport transport, boolean upgrade) throws Exception {
            SSLContext context = serverContext(certificate);
            InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
            config.setSchema(null);
            config.setAuthenticationRequiredOperationTypes(OperationType.SEARCH);
            config.addAdditionalBindCredentials("cn=service,dc=example,dc=com", credential);
            config.setListenerConfigs(transport == DirectoryProfile.Transport.LDAPS
                    ? InMemoryListenerConfig.createLDAPSConfig("test", InetAddress.getLoopbackAddress(), 0,
                        context.getServerSocketFactory(), context.getSocketFactory(), false, false)
                    : InMemoryListenerConfig.createLDAPConfig("test", InetAddress.getLoopbackAddress(), 0,
                        upgrade ? context.getSocketFactory() : null));
            config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
                @Override public void processSimpleBindRequest(InMemoryInterceptedSimpleBindRequest request) { binds.incrementAndGet(); }
                @Override public void processSimpleBindResult(InMemoryInterceptedSimpleBindResult result) {
                    if (suppressProof && result.getResult().getResultCode() == ResultCode.SUCCESS) {
                        result.setResult(new BindResult(result.getResult().getMessageID(), ResultCode.SUCCESS, null, null, null, new Control[0]));
                    }
                }
                @Override public void processSearchRequest(InMemoryInterceptedSearchRequest request) throws LDAPException {
                    searches.incrementAndGet();
                    if (denyRead) throw new LDAPException(ResultCode.INSUFFICIENT_ACCESS_RIGHTS);
                }
            });
            server = new InMemoryDirectoryServer(config);
            server.add(new Entry("dc=example,dc=com", new Attribute("objectClass", "domain"), new Attribute("dc", "example")));
            server.add(new Entry(USER, new Attribute("objectClass", "person"), new Attribute("cn", "ordinary"),
                    new Attribute("sn", "ordinary"), new Attribute("userPassword", credential)));
            server.add(new Entry("ou=groups,dc=example,dc=com", new Attribute("objectClass", "organizationalUnit"), new Attribute("ou", "groups")));
            server.add(new Entry(GROUP, new Attribute("objectClass", "groupOfNames"), new Attribute("cn", "access"), new Attribute("member", USER)));
            server.startListening();
        }
        @Override public void close() { server.shutDown(true); }
    }

    static DirectoryProfile profile(Server server, DirectoryProfile.Transport transport, String host,
            DirectoryProfile.Format format, Path material) {
        return new DirectoryProfile("synthetic", host, server.server.getListenPort(), transport, material, format,
                format == DirectoryProfile.Format.PEM ? null : pinFile, "cn=%s,dc=example,dc=com", "ou=groups,dc=example,dc=com", GROUP);
    }
    static char[] userPassword() {
        var decoded = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(credential));
        char[] result = new char[decoded.remaining()]; decoded.get(result);
        Arrays.fill(decoded.array(), '\0'); return result;
    }

    @Test void usableFormatsAndPinBuffers() throws Exception {
        try (Server server = new Server(valid, DirectoryProfile.Transport.LDAPS, true)) {
            for (DirectoryProfile.Format format : DirectoryProfile.Format.values()) {
                Path material = format == DirectoryProfile.Format.PEM ? caPem : format == DirectoryProfile.Format.JKS ? jks : pkcs12;
                var profile = profile(server, DirectoryProfile.Transport.LDAPS, "localhost", format, material);
                char[] supplied = pin.clone();
                assertNotNull(DirectoryTrustPolicy.load(profile, ignored -> supplied));
                if (format != DirectoryProfile.Format.PEM) assertArrayEquals(new char[supplied.length], supplied);
                if (format != DirectoryProfile.Format.PEM) {
                    char[] wrong = UUID.randomUUID().toString().toCharArray();
                    assertThrows(LdapStartupException.class, () -> DirectoryTrustPolicy.load(profile, ignored -> wrong));
                    assertArrayEquals(new char[wrong.length], wrong);
                    assertThrows(LdapStartupException.class, () -> DirectoryTrustPolicy.load(profile, ignored -> new char[0]));
                }
                var trust = new DirectoryTrustPolicy(profile, () -> { });
                char[] password = userPassword();
                assertInstanceOf(Result.Ok.class, UnboundIdOperatorBindAdapter.create(profile, trust).bind("ordinary", password));
                assertArrayEquals(new char[password.length], password);
            }
            for (Path material : List.of(tmp.resolve("missing"), Files.write(tmp.resolve("empty"), new byte[0]),
                    Files.writeString(tmp.resolve("malformed"), "invalid synthetic material"), tmp.resolve("valid.pem"))) {
                var profile = profile(server, DirectoryProfile.Transport.LDAPS, "localhost", DirectoryProfile.Format.PEM, material);
                assertThrows(LdapStartupException.class, () -> new DirectoryTrustPolicy(profile, () -> { }));
            }
            for (DirectoryProfile.Format format : List.of(DirectoryProfile.Format.JKS, DirectoryProfile.Format.PKCS12)) {
                Path emptyStore = tmp.resolve("empty-" + format);
                KeyStore empty = KeyStore.getInstance(format.name()); empty.load(null, pin);
                try (var out = Files.newOutputStream(emptyStore)) { empty.store(out, pin); }
                var emptyProfile = profile(server, DirectoryProfile.Transport.LDAPS, "localhost", format, emptyStore);
                assertThrows(LdapStartupException.class, () -> DirectoryTrustPolicy.load(emptyProfile));
                var missingPin = new DirectoryProfile("synthetic", "localhost", server.server.getListenPort(), DirectoryProfile.Transport.LDAPS,
                        format == DirectoryProfile.Format.JKS ? jks : pkcs12, format, tmp.resolve("missing-pin"),
                        "cn=%s,dc=example,dc=com", "ou=groups,dc=example,dc=com", GROUP);
                assertThrows(LdapStartupException.class, () -> DirectoryTrustPolicy.load(missingPin));
            }
            Path expiredAnchorStore = tmp.resolve("expired-anchor.p12"), expiredAnchor = tmp.resolve("expired-anchor.pem");
            keytool("-genkeypair", "-alias", "ca", "-dname", "CN=Synthetic Expired CA", "-keyalg", "RSA", "-keysize", "2048",
                    "-startdate", "-10d", "-validity", "1", "-ext", "bc=ca:true", "-ext", "ku=keyCertSign",
                    "-keystore", expiredAnchorStore.toString(), "-storetype", "PKCS12");
            keytool("-exportcert", "-alias", "ca", "-rfc", "-keystore", expiredAnchorStore.toString(), "-file", expiredAnchor.toString());
            var expiredProfile = profile(server, DirectoryProfile.Transport.LDAPS, "localhost", DirectoryProfile.Format.PEM, expiredAnchor);
            assertThrows(LdapStartupException.class, () -> DirectoryTrustPolicy.load(expiredProfile));
            var wrongFormat = profile(server, DirectoryProfile.Transport.LDAPS, "localhost", DirectoryProfile.Format.JKS, pkcs12);
            assertThrows(LdapStartupException.class, () -> new DirectoryTrustPolicy(wrongFormat, () -> { }));
            var keyEntries = profile(server, DirectoryProfile.Transport.LDAPS, "localhost", DirectoryProfile.Format.PKCS12, valid);
            assertThrows(LdapStartupException.class, () -> new DirectoryTrustPolicy(keyEntries, () -> { }));
            var privateKey = profile(server, DirectoryProfile.Transport.LDAPS, "localhost", DirectoryProfile.Format.PEM,
                    Files.writeString(tmp.resolve("key.pem"), Files.readString(caPem) + "-----BEGIN PRIVATE KEY-----\ninvalid\n-----END PRIVATE KEY-----"));
            assertThrows(LdapStartupException.class, () -> new DirectoryTrustPolicy(privateKey, () -> { }));
        }
    }

    @Test void bothAdaptersBothTransportsHandshakeAndTrustRejectionMatrix() throws Exception {
        for (DirectoryProfile.Transport transport : DirectoryProfile.Transport.values()) {
            for (Path certificate : List.of(valid, expired, untrusted)) {
                for (String host : List.of("localhost", "127.0.0.1")) {
                    try (Server server = new Server(certificate, transport, true)) {
                        var profile = profile(server, transport, host, DirectoryProfile.Format.PEM, caPem);
                        var trust = new DirectoryTrustPolicy(profile, () -> { });
                        boolean acceptable = certificate == valid && host.equals("localhost");
                        var bind = UnboundIdOperatorBindAdapter.create(profile, trust).bind("ordinary", userPassword());
                        assertEquals(acceptable, bind instanceof Result.Ok, "operator TLS matrix");
                        Path serviceSecret = tmp.resolve("service-password");
                        Files.write(serviceSecret, credential);
                        var adapter = new UnboundIdRevalidationAdapter(true, profile, trust, "cn=service,dc=example,dc=com", serviceSecret);
                        var refreshed = adapter.revalidatePrincipal(USER);
                        assertEquals(acceptable, refreshed instanceof Result.Ok, "revalidation TLS matrix");
                        assertEquals(acceptable ? 2 : 0, server.binds.get());
                        assertEquals(acceptable ? 4 : 0, server.searches.get());
                    }
                }
            }
        }
        try (Server server = new Server(valid, DirectoryProfile.Transport.STARTTLS, false)) {
            var profile = profile(server, DirectoryProfile.Transport.STARTTLS, "localhost", DirectoryProfile.Format.PEM, caPem);
            var trust = new DirectoryTrustPolicy(profile, () -> { });
            assertInstanceOf(Result.Err.class, UnboundIdOperatorBindAdapter.create(profile, trust).bind("ordinary", userPassword()));
            var revalidate = new UnboundIdRevalidationAdapter(true, profile, trust, "cn=service,dc=example,dc=com", pinFile);
            assertInstanceOf(Result.Err.class, revalidate.revalidatePrincipal(USER));
            assertEquals(0, server.binds.get()); assertEquals(0, server.searches.get());
        }
    }

    @Test void referralsNeverForwardCredentialsAndDisabledMechanismCallsNothing() throws Exception {
        for (DirectoryProfile.Transport transport : DirectoryProfile.Transport.values()) {
            try (Server server = new Server(valid, transport, true)) {
                server.server.add(new Entry("cn=referral,ou=groups,dc=example,dc=com",
                        new Attribute("objectClass", "referral"), new Attribute("cn", "referral"),
                        new Attribute("ref", "ldap://127.0.0.1:1/dc=example,dc=com")));
                var profile = profile(server, transport, "localhost", DirectoryProfile.Format.PEM, caPem);
                var trust = new DirectoryTrustPolicy(profile, () -> { });
                var operator = UnboundIdOperatorBindAdapter.create(profile, trust);
                assertInstanceOf(Result.Err.class, operator.bind("ordinary", userPassword()));
                assertEquals(1, server.binds.get()); assertEquals(2, server.searches.get());
                char[] password = userPassword();
                assertInstanceOf(AttemptOutcome.MechanismUnavailable.class, new LdapMechanism(operator, false).attempt("ordinary", password));
                assertArrayEquals(new char[password.length], password);
                assertEquals(1, server.binds.get());
            }
        }
    }

    @Test void ordinaryProofFailuresPasswordClearingAndRotation() throws Exception {
        try (Server server = new Server(valid, DirectoryProfile.Transport.STARTTLS, true)) {
            var profile = profile(server, DirectoryProfile.Transport.STARTTLS, "localhost", DirectoryProfile.Format.PEM, caPem);
            AtomicInteger invalidations = new AtomicInteger();
            var trust = new DirectoryTrustPolicy(profile, invalidations::incrementAndGet);
            var operator = UnboundIdOperatorBindAdapter.create(profile, trust);
            var result = (Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>) operator.bind("ordinary", userPassword());
            assertEquals(USER, result.value().observation().principalReference());
            assertFalse(result.toString().contains(USER));
            assertEquals(1, server.binds.get()); // No service-account substitution.
            server.suppressProof = true;
            char[] password = userPassword();
            assertInstanceOf(Result.Err.class, operator.bind("ordinary", password));
            assertArrayEquals(new char[password.length], password);
            server.suppressProof = false; server.denyRead = true;
            assertInstanceOf(Result.Err.class, operator.bind("ordinary", userPassword()));
            server.denyRead = false;
            password = "incorrect synthetic credential".toCharArray();
            assertInstanceOf(Result.Err.class, operator.bind("ordinary", password));
            assertArrayEquals(new char[password.length], password);
            int calls = server.binds.get();
            assertInstanceOf(Result.Err.class, operator.bind("ordinary", new char[0]));
            assertEquals(calls, server.binds.get());
            var snapshot = trust.snapshot();
            try (LDAPConnection old = DirectoryConnection.open(profile, trust, snapshot)) {
                trust.reload(profile);
                assertFalse(old.isConnected());
                assertFalse(trust.publish(snapshot, () -> fail("retired trust cannot publish")));
            }
            assertFalse(result.value().observation().publication().ifCurrent(() -> fail("old proof cannot publish")));
            var broken = profile(server, DirectoryProfile.Transport.STARTTLS, "localhost", DirectoryProfile.Format.PEM, tmp.resolve("missing-reload"));
            assertThrows(LdapStartupException.class, () -> trust.reload(broken));
            assertInstanceOf(Result.Err.class, operator.bind("ordinary", userPassword()));
            assertEquals(calls, server.binds.get());
            trust.reload(profile); trust.withdraw();
            assertThrows(LdapStartupException.class, trust::snapshot);
            assertTrue(invalidations.get() >= 4);
            var disabled = new UnboundIdRevalidationAdapter(false, profile, trust, null, tmp.resolve("missing-secret"));
            assertInstanceOf(Result.Err.class, disabled.revalidatePrincipal(USER));
            assertEquals(calls, server.binds.get());
        }
    }
}
