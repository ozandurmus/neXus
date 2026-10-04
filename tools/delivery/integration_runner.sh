#!/usr/bin/env bash
# Runs only in the integration Job. All raw diagnostics stay in its emptyDir.
set -Eeuo pipefail
exec 2>/tmp/integration-setup.log
trap 'echo "INTEGRATION: ERROR (runner/setup failed; raw output withheld)"; exit 2' ERR
cd /workspace/ui2
mkdir -p "$GRADLE_USER_HOME"
# Like the image's update-ca-trust, merge anchors without replacing existing roots.
# Use one JVM and a writable private store; the runner cannot update system trust.
cat > /tmp/IntegrationTrustStore.java <<'JAVA'
import java.nio.file.*;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.util.HexFormat;

class IntegrationTrustStore {
    public static void main(String[] args) throws Exception {
        // Public cacerts integrity password, supplied by the runner. No private keys.
        char[] password = args[3].toCharArray();
        var store = KeyStore.getInstance(Path.of(args[0]).toFile(), password);
        var factory = CertificateFactory.getInstance("X.509");
        var digest = MessageDigest.getInstance("SHA-256");
        int imported = 0, skipped = 0;
        try (var bundles = Files.newDirectoryStream(Path.of(args[1]))) {
            for (var bundle : bundles) {
                if (!Files.isRegularFile(bundle)) continue;
                try (var input = Files.newInputStream(bundle)) {
                    for (var cert : factory.generateCertificates(input)) {
                        if (store.getCertificateAlias(cert) != null) {
                            skipped++;
                            continue;
                        }
                        String alias = "corp-" + HexFormat.of().formatHex(digest.digest(cert.getEncoded()));
                        if (store.containsAlias(alias)) throw new IllegalStateException("CA alias conflict");
                        store.setCertificateEntry(alias, cert);
                        imported++;
                    }
                }
            }
        }
        try (var output = Files.newOutputStream(Path.of(args[2]))) {
            store.store(output, password);
        }
        // Reopen the written store: an unreadable or empty store must stop setup.
        if (KeyStore.getInstance(Path.of(args[2]).toFile(), password).size() == 0)
            throw new IllegalStateException("Empty trust store");
        System.out.println("CA import: imported=" + imported + "; skipped=" + skipped);
    }
}
JAVA
java /tmp/IntegrationTrustStore.java "$JAVA_HOME/lib/security/cacerts" /run/corp-ca /tmp/cacerts changeit
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/tmp/cacerts -Duser.home=$HOME"
# Wrapper and daemon both use the host build's proxy system properties.
for option in ${GRADLE_OPTS:-}; do
  case "$option" in
    -Dhttp.proxyHost=*|-Dhttp.proxyPort=*|-Dhttps.proxyHost=*|-Dhttps.proxyPort=*)
      printf 'systemProp.%s\n' "${option#-D}" >> "$GRADLE_USER_HOME/gradle.properties" ;;
  esac
done
status=0
./gradlew --no-daemon --console=plain -PfrontendPrebuilt=true :integration-tests:test --rerun-tasks >/tmp/gradle.log 2>&1 || status=$?
# A separate JVM emits counters, safe identifiers and bounded, sanitized failure messages.
trap - ERR
java /workspace/tools/delivery/IntegrationSummary.java "$status" integration-tests/build/test-results/test
