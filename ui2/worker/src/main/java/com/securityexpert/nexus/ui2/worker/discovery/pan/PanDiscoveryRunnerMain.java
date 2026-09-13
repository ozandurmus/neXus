package com.securityexpert.nexus.ui2.worker.discovery.pan;

import java.util.HashMap;
import java.util.Map;

import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationResult;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanXmlApiTransport;

/**
 * The Product-Owner-runnable entry point this movement's deliverable
 * requires: no HTTP endpoint of our own, no persistence, one discovery run
 * against one Panorama, printed as counts and shapes only ({@link
 * PanDiscoveryRunReport}, AC-8). Every argument is required and none has a
 * baked-in default. Mirrors cp's {@code DiscoveryRunnerMain}.
 *
 * <p>Invocation:</p>
 * <pre>
 * PAN_DISCOVERY_USERNAME=... PAN_DISCOVERY_PASSWORD=... \
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
        DeviceTransport transport = new PanXmlApiTransport(arguments.trustRuleRef, EnvironmentPanCredentialAndTrustResolvers.INSTANCE);
        PanoramaEnumerationAdapter adapter = new PanoramaEnumerationAdapter(
                transport, EnvironmentPanCredentialAndTrustResolvers.INSTANCE, EnvironmentPanCredentialAndTrustResolvers.INSTANCE);

        PanoramaEnumerationRequest request = new PanoramaEnumerationRequest(
                arguments.panoramaHost, arguments.panoramaPort, arguments.credentialRef, arguments.trustRuleRef);

        PanoramaEnumerationResult result = adapter.run(request);
        System.out.print(PanDiscoveryRunReport.render(result));
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
