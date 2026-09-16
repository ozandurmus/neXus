package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialStoreComposition;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialResolver;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshExecTransport;

/**
 * The Product-Owner-runnable entry point this movement's deliverable
 * requires: no HTTP endpoint (PO_DECISION_RECORD_2026_09_13D §4), one
 * discovery run against one management server, printed as counts and shapes
 * only ({@link DiscoveryRunReport}, AC-8). Every argument is required and
 * none has a baked-in default -- CS-6b's "never a numeric port literal"
 * applies to this whole package, so the management port and the optional
 * configured channel port both come from the command line.
 *
 * <p>The SSH credential is resolved through the credential store
 * (2026-09-14 PO decision record CS-1..CS-5, SB-16): {@code
 * --credential-ref} is a {@code credential_references} id, resolved by
 * {@link StoreBackedSshCredentialResolver} against the same database the
 * service uses and the same envelope key file {@code
 * UI2_CREDENTIAL_STORE_KEY_FILE} names. Both are read once, at startup,
 * failing closed (never a fallback to an environment-variable credential --
 * SB-16: "refuse before contact when the reference cannot be resolved").
 * Discovery host-key authorization is read from the same persisted C10 store as the worker.</p>
 *
 * <p>Invocation:</p>
 * <pre>
 * UI2_DB_URL=... UI2_DB_APP_USER_FILE=... UI2_DB_APP_PASSWORD_FILE=... UI2_CREDENTIAL_STORE_KEY_FILE=... \
 *   java -cp worker.jar:... com.securityexpert.nexus.ui2.worker.discovery.cp.DiscoveryRunnerMain \
 *   --management-host &lt;host&gt; --management-ssh-port &lt;port&gt; \
 *   --credential-ref &lt;opaque-ref&gt; \
 *   --channel-observation-interval-seconds &lt;n&gt; [--configured-channel-port &lt;port&gt;]
 * </pre>
 */
public final class DiscoveryRunnerMain {

    private DiscoveryRunnerMain() {
    }

    public static void main(String[] args) {
        Arguments arguments = Arguments.parse(args);
        SshCredentialResolver credentialResolver = storeBackedCredentialResolver();
        DeviceTransport transport =
                new SshExecTransport(credentialResolver, new com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver(
                        new com.securityexpert.nexus.ui2.persistence.discovery.JooqManagementEndpointSshTrustRepository(
                                com.securityexpert.nexus.ui2.persistence.TransactionBoundaryFactory.fromJdbc(
                                        requireEnv("UI2_DB_URL"),
                                        SecretFile.readRequired(Path.of(requireEnv("UI2_DB_APP_USER_FILE")), "discovery.db_user"),
                                        SecretFile.readRequired(Path.of(requireEnv("UI2_DB_APP_PASSWORD_FILE")), "discovery.db_password"))),
                        ref -> Optional.empty()));
        ManagementPlaneEnumerationAdapter adapter =
                new ManagementPlaneEnumerationAdapter(transport, credentialResolver);

        ManagementPlaneEnumerationRequest request = new ManagementPlaneEnumerationRequest(
                arguments.managementHost, arguments.managementSshPort, arguments.credentialRef,
                com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver.scopeRef(
                        arguments.managementHost, arguments.managementSshPort),
                Duration.ofSeconds(arguments.channelObservationIntervalSeconds), arguments.configuredChannelPort);

        ManagementPlaneEnumerationResult result = adapter.run(request);
        System.out.print(DiscoveryRunReport.render(result));
    }

    /**
     * SB-16: the database connection and the envelope key file are both
     * required and neither has a fallback -- a missing/empty
     * {@code UI2_CREDENTIAL_STORE_KEY_FILE} or a missing DB env var fails
     * this call, and therefore {@code main}, before any device is contacted.
     */
    private static SshCredentialResolver storeBackedCredentialResolver() {
        String jdbcUrl = requireEnv("UI2_DB_URL");
        String dbUser = SecretFile.readRequired(Path.of(requireEnv("UI2_DB_APP_USER_FILE")), "credential_store.db_user");
        String dbPassword =
                SecretFile.readRequired(Path.of(requireEnv("UI2_DB_APP_PASSWORD_FILE")), "credential_store.db_password");
        String credentialStoreKeyBase64 =
                SecretFile.readRequired(Path.of(requireEnv("UI2_CREDENTIAL_STORE_KEY_FILE")), "credential_store_key");
        CredentialStoreComposition.ResolverComponents resolverComponents =
                CredentialStoreComposition.resolverComponents(jdbcUrl, dbUser, dbPassword, credentialStoreKeyBase64);
        return new StoreBackedSshCredentialResolver(resolverComponents.credentialReferenceRepository(),
                resolverComponents.credentialRepository(), resolverComponents.cipher());
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }

    private record Arguments(
            String managementHost,
            int managementSshPort,
            String credentialRef,
            long channelObservationIntervalSeconds,
            Optional<Integer> configuredChannelPort) {

        static Arguments parse(String[] args) {
            Map<String, String> flags = new HashMap<>();
            for (int i = 0; i + 1 < args.length; i += 2) {
                flags.put(args[i], args[i + 1]);
            }
            return new Arguments(
                    require(flags, "--management-host"),
                    Integer.parseInt(require(flags, "--management-ssh-port")),
                    require(flags, "--credential-ref"),
                    Long.parseLong(require(flags, "--channel-observation-interval-seconds")),
                    Optional.ofNullable(flags.get("--configured-channel-port")).map(Integer::parseInt));
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
