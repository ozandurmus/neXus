package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshExecTransport;

/**
 * The Product-Owner-runnable entry point this movement's deliverable
 * requires: no HTTP endpoint (PO_DECISION_RECORD_2026_09_13D §4), no
 * persistence, one discovery run against one management server, printed as
 * counts and shapes only ({@link DiscoveryRunReport}, AC-8). Every argument
 * is required and none has a baked-in default -- CS-6b's "never a numeric
 * port literal" applies to this whole package, so the management port and
 * the optional configured channel port both come from the command line.
 *
 * <p>Invocation:</p>
 * <pre>
 * CP_DISCOVERY_SSH_USERNAME=... CP_DISCOVERY_SSH_PASSWORD=... CP_DISCOVERY_TRUST_FINGERPRINT=... \
 *   java -cp worker.jar:... com.securityexpert.nexus.ui2.worker.discovery.cp.DiscoveryRunnerMain \
 *   --management-host &lt;host&gt; --management-ssh-port &lt;port&gt; \
 *   --credential-ref &lt;opaque-ref&gt; --trust-rule-ref &lt;opaque-ref&gt; \
 *   --channel-observation-interval-seconds &lt;n&gt; [--configured-channel-port &lt;port&gt;]
 * </pre>
 */
public final class DiscoveryRunnerMain {

    private DiscoveryRunnerMain() {
    }

    public static void main(String[] args) {
        Arguments arguments = Arguments.parse(args);
        DeviceTransport transport = new SshExecTransport(
                EnvironmentCredentialAndTrustResolvers.INSTANCE, EnvironmentCredentialAndTrustResolvers.INSTANCE);
        ManagementPlaneEnumerationAdapter adapter =
                new ManagementPlaneEnumerationAdapter(transport, EnvironmentCredentialAndTrustResolvers.INSTANCE);

        ManagementPlaneEnumerationRequest request = new ManagementPlaneEnumerationRequest(
                arguments.managementHost, arguments.managementSshPort, arguments.credentialRef, arguments.trustRuleRef,
                Duration.ofSeconds(arguments.channelObservationIntervalSeconds), arguments.configuredChannelPort);

        ManagementPlaneEnumerationResult result = adapter.run(request);
        System.out.print(DiscoveryRunReport.render(result));
    }

    private record Arguments(
            String managementHost,
            int managementSshPort,
            String credentialRef,
            String trustRuleRef,
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
                    require(flags, "--trust-rule-ref"),
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
