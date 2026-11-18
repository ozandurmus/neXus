package com.securityexpert.nexus.ui2.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Every packet-analysis path is built from generated ids only and stays under the volume root. */
class PacketAnalysisPathsTest {
    private static final Path ROOT = Path.of("/analysis-volume");
    private static final String CASE = "0f8b1c2e-3d4a-4b5c-8d6e-7f8091a2b3c4";
    private static final String KEY = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d.pcap";

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
