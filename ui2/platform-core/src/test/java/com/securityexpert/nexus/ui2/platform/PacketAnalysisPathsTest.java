package com.securityexpert.nexus.ui2.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Every packet-analysis path is built from generated ids only and stays under the volume root. */
class PacketAnalysisPathsTest {
    private static final Path ROOT = Path.of("/analysis-volume");
    private static final String CASE = java.util.UUID.nameUUIDFromBytes("synthetic-case".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    // Built at runtime so no secret-shaped literal sits in the source (gitleaks generic-api-key).
    private static final String KEY = java.util.UUID.nameUUIDFromBytes("synthetic-capture".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".pcap";

    @Test
    void generatedIdsResolveUnderTheRoot() {
        Path file = PacketAnalysisPaths.captureFile(ROOT, CASE, KEY);
        assertEquals(ROOT.resolve("cases").resolve(CASE).resolve("captures").resolve(KEY), file);
        assertTrue(file.startsWith(ROOT));
    }

    @Test
    void traversalInACaseIdIsRefused() {
        for (String bad : new String[] {"../../etc", "..", CASE + "/../x", CASE + "/..", "/etc/passwd", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> PacketAnalysisPaths.caseDir(ROOT, bad), String.valueOf(bad));
        }
    }

    @Test
    void traversalInAStorageKeyIsRefused() {
        for (String bad : new String[] {"../" + KEY, "x/../" + KEY, KEY + "/../../x", "..\\" + KEY, KEY + "\u0000", "a.pcap", null}) {
            assertThrows(IllegalArgumentException.class, () -> PacketAnalysisPaths.captureFile(ROOT, CASE, bad), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> PacketAnalysisPaths.telemetryFile(ROOT, CASE, bad), String.valueOf(bad));
        }
    }

    @Test
    void traversalInAJobIdIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PacketAnalysisPaths.reportFile(ROOT, CASE, "../../../tmp"));
    }
}
