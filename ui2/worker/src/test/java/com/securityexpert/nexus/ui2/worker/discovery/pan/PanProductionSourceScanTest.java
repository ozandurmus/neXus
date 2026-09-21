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
 * binding isolation (AC-5), the PAN validity-only TLS boundary, and T-7's
 * "nothing written to disk" property (AC-6). Mirrors cp's {@code FieldBindingIsolationTest} and
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

    /** PAN TLS accepts any chain but still rejects missing, malformed, or expired certificates. */
    @Test
    void tlsProductionSourceUsesValidityOnlyAndNoEnrollmentOrPin() throws IOException {
        List<String> forbidden = List.of(
                "CaBundlePath", "PinnedFingerprint", "pinnedFingerprint", "findActiveFingerprint",
                "PAN_DISCOVERY_TRUST_CA_BUNDLE_PATH", "PAN_DISCOVERY_TRUST_PINNED_FINGERPRINT_SHA256");
        List<String> violations = new ArrayList<>();
        String transportSource;
        StringBuilder combined = new StringBuilder();
        for (Path packageDir : List.of(discoveryPanMainSourceDir(), xmlApiTransportMainSourceDir())) {
            try (Stream<Path> files = Files.walk(packageDir)) {
                for (Path p : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String content = Files.readString(p);
                    combined.append(content);
                    for (String pattern : forbidden) {
                        if (content.contains(pattern)) {
                            violations.add(p.getFileName() + " contains forbidden pattern \"" + pattern + "\"");
                        }
                    }
                }
            }
        }
        transportSource = combined.toString();
        assertTrue(violations.isEmpty(), "legacy PAN trust path found: " + violations);
        assertTrue(transportSource.contains("checkServerTrusted"), "expected a checkServerTrusted implementation");
        assertTrue(transportSource.contains("certificate.checkValidity()"),
                "expected every presented certificate to have its validity period checked");
        assertTrue(transportSource.contains("throw new CertificateException"),
                "the trust manager must be able to reject a certificate, not merely declare a check method");
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
