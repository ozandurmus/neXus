package com.securityexpert.nexus.ui2.worker.discovery.pan;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationResult;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialStoreComposition;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanXmlApiTransport;

/**
 * The Product-Owner-runnable entry point this movement's deliverable
 * requires: no HTTP endpoint of our own, one discovery run against one
 * Panorama, printed as counts and shapes only ({@link PanDiscoveryRunReport},
 * AC-8). Every argument is required and none has a baked-in default. Mirrors
 * cp's {@code DiscoveryRunnerMain}, including how it resolves its credential
 * through the credential store (2026-09-14 PO decision record CS-1..CS-5,
 * SB-16).
 *
 * <p>Invocation:</p>
 * <pre>
 * UI2_DB_URL=... UI2_DB_APP_USER_FILE=... UI2_DB_APP_PASSWORD_FILE=... UI2_CREDENTIAL_STORE_KEY_FILE=... \
 *   PAN_DISCOVERY_TRUST_CA_BUNDLE_PATH=... (or PAN_DISCOVERY_TRUST_PINNED_FINGERPRINT_SHA256=...) \
 *   java -cp worker.jar:... com.securityexpert.nexus.ui2.worker.discovery.pan.PanDiscoveryRunnerMain \
 *   --panorama-host &lt;host&gt; --panorama-port &lt;port&gt; \
 *   --credential-ref &lt;opaque-ref&gt; --trust-rule-ref &lt;opaque-ref&gt;
 * </pre>
 */
public final class PanDiscoveryRunnerMain {

    private PanDiscoveryRunnerMain() {
    }

    public static void main(String[] args) {
        Arguments arguments = Arguments.parse(args);
        PanCredentialResolver credentialResolver = storeBackedCredentialResolver();
        DeviceTransport transport = new PanXmlApiTransport(arguments.trustRuleRef, EnvironmentPanTrustRuleResolver.INSTANCE);
        PanoramaEnumerationAdapter adapter =
                new PanoramaEnumerationAdapter(transport, credentialResolver, EnvironmentPanTrustRuleResolver.INSTANCE);

        PanoramaEnumerationRequest request = new PanoramaEnumerationRequest(
                arguments.panoramaHost, arguments.panoramaPort, arguments.credentialRef, arguments.trustRuleRef);

        PanoramaEnumerationResult result = adapter.run(request);
        System.out.print(PanDiscoveryRunReport.render(result));
    }

    /**
     * SB-16: the database connection and the envelope key file are both
     * required and neither has a fallback -- a missing/empty
     * {@code UI2_CREDENTIAL_STORE_KEY_FILE} or a missing DB env var fails
     * this call, and therefore {@code main}, before any device is contacted.
     */
    private static PanCredentialResolver storeBackedCredentialResolver() {
        String jdbcUrl = requireEnv("UI2_DB_URL");
        String dbUser = SecretFile.readRequired(Path.of(requireEnv("UI2_DB_APP_USER_FILE")), "credential_store.db_user");
        String dbPassword =
                SecretFile.readRequired(Path.of(requireEnv("UI2_DB_APP_PASSWORD_FILE")), "credential_store.db_password");
        String credentialStoreKeyBase64 =
                SecretFile.readRequired(Path.of(requireEnv("UI2_CREDENTIAL_STORE_KEY_FILE")), "credential_store_key");
        CredentialStoreComposition.ResolverComponents resolverComponents =
                CredentialStoreComposition.resolverComponents(jdbcUrl, dbUser, dbPassword, credentialStoreKeyBase64);
        return new StoreBackedPanCredentialResolver(resolverComponents.credentialReferenceRepository(),
                resolverComponents.credentialRepository(), resolverComponents.cipher());
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }

    private record Arguments(String panoramaHost, int panoramaPort, String credentialRef, String trustRuleRef) {

        static Arguments parse(String[] args) {
            Map<String, String> flags = new HashMap<>();
            for (int i = 0; i + 1 < args.length; i += 2) {
                flags.put(args[i], args[i + 1]);
            }
            return new Arguments(
                    require(flags, "--panorama-host"),
                    Integer.parseInt(require(flags, "--panorama-port")),
                    require(flags, "--credential-ref"),
                    require(flags, "--trust-rule-ref"));
        }

        private static String require(Map<String, String> flags, String name) {
            String value = flags.get(name);
            if (value == null) {
                throw new IllegalArgumentException("missing required argument " + name);
            }
            return value;
        }
    }
}
