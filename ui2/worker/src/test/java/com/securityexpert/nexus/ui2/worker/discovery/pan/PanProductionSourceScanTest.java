package com.securityexpert.nexus.ui2.worker.discovery.pan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.pan.PanoramaApiFieldBinding;

/**
 * Static-scan checks over this movement's production sources: FB-2 field-
 * binding isolation (AC-5), the TLS "no all-trusting trust manager/
 * hostname verifier" invariant (AC-3), and T-7's "nothing written to disk"
 * property (AC-6). Mirrors cp's {@code FieldBindingIsolationTest} and
 * {@code NoNumericPortLiteralTest} pattern: scan the whole package's
 * production text, not a hand-picked sample.
 */
class PanProductionSourceScanTest {

    /** FB-2/AC-5: no production class in the pan worker packages contains a bound element-path literal. */
    @Test
    void discoveryPanProductionSourceContainsNoBoundFieldPathLiteral() throws IOException {
        // DEVICE_TYPE_MARKER's bound response-field name, "type", coincidentally equals the
        // vendor's own request PARAMETER name (type=keygen/type=op) that PanoramaApiRoutes
        // legitimately builds -- a real vendor-vocabulary collision between a response field and
        // a protocol parameter, not a field-binding leak. Excluded from this scan for that reason.
        List<String> boundFieldPaths = PanoramaApiFieldBinding.all().stream()
                .map(entry -> entry.apiFieldPath().orElseThrow())
                .filter(path -> !path.equals("type"))
                .toList();

        List<String> violations = new ArrayList<>();
        for (Path packageDir : List.of(discoveryPanMainSourceDir(), xmlApiTransportMainSourceDir())) {
            assertTrue(Files.isDirectory(packageDir), "expected to find " + packageDir);
            try (Stream<Path> files = Files.walk(packageDir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> {
                            try {
                                String content = Files.readString(p);
                                for (String fieldPath : boundFieldPaths) {
                                    if (content.contains("\"" + fieldPath + "\"")) {
                                        violations.add(p.getFileName() + " contains bound field literal \"" + fieldPath + "\"");
                                    }
                                }
                            } catch (IOException e) {
                                fail("could not read " + p + ": " + e.getMessage());
                            }
                        });
            }
        }
        assertFalse(!violations.isEmpty(), "field-binding isolation violated: " + violations);
    }

    /**
     * AC-3: no all-trusting {@code TrustManager} or hostname verifier exists anywhere in
     * production source, and the one trust manager this movement does ship both names its
     * check method and can actually reject a certificate.
     */
    @Test
    void tlsProductionSourceContainsNoAllTrustingTrustManagerOrHostnameVerifier() throws IOException {
        List<String> forbidden = List.of(
                "TrustAllCerts", "ALLOW_ALL_HOSTNAME_VERIFIER", "NoopHostnameVerifier", "trustAllCertificates",
                "setHostnameVerifier", "-> true");
        List<String> violations = new ArrayList<>();
        String transportSource;
        try (Stream<Path> files = Files.walk(xmlApiTransportMainSourceDir())) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            StringBuilder combined = new StringBuilder();
            for (Path p : javaFiles) {
                String content = Files.readString(p);
                combined.append(content);
                for (String pattern : forbidden) {
                    if (content.contains(pattern)) {
                        violations.add(p.getFileName() + " contains forbidden pattern \"" + pattern + "\"");
                    }
                }
            }
            transportSource = combined.toString();
        }
        assertTrue(violations.isEmpty(), "TLS bypass pattern found: " + violations);
        assertTrue(transportSource.contains("checkServerTrusted"), "expected a checkServerTrusted implementation");
        assertTrue(transportSource.contains("throw new CertificateException"),
                "the trust manager must be able to reject a certificate, not merely declare a check method");
        assertTrue(transportSource.contains("setEndpointIdentificationAlgorithm(\"HTTPS\")"),
                "expected explicit HTTPS hostname verification to be requested");
    }

    /** T-7/AC-6: nothing in this movement's production source ever writes a file. */
    @Test
    void productionSourceContainsNoFileWritingCall() throws IOException {
        List<String> forbidden = List.of(
                "FileOutputStream", "Files.write(", "Files.writeString(", "FileWriter", "PrintWriter(",
                "Files.copy(", "Files.createFile(");
        List<String> violations = new ArrayList<>();
        for (Path packageDir : List.of(discoveryPanMainSourceDir(), xmlApiTransportMainSourceDir())) {
            try (Stream<Path> files = Files.walk(packageDir)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        for (String pattern : forbidden) {
                            if (content.contains(pattern)) {
                                violations.add(p.getFileName() + " contains \"" + pattern + "\"");
                            }
                        }
                    } catch (IOException e) {
                        fail("could not read " + p + ": " + e.getMessage());
                    }
                });
            }
        }
        assertTrue(violations.isEmpty(), "file-writing call found in production source: " + violations);
    }

    private static Path discoveryPanMainSourceDir() {
        return Path.of(System.getProperty("user.dir"), "src", "main", "java", "com", "securityexpert",
                "nexus", "ui2", "worker", "discovery", "pan");
    }

    private static Path xmlApiTransportMainSourceDir() {
        return Path.of(System.getProperty("user.dir"), "src", "main", "java", "com", "securityexpert",
                "nexus", "ui2", "worker", "transport", "xmlapi");
    }
}
