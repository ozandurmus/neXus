package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The container walker against reference vectors (tools/packet_analysis/parity/l1_metadata_vectors.py): synthetic
 * pcap/pcapng/snoop files, every truncation, zero tails and seeded bit flips. The JSON text must be identical; a
 * reference ValueError must be an IllegalArgumentException.
 */
class MetadataVectorsTest {

    @TempDir
    Path dir;

    @Test
    void walkerMatchesTheReferenceOnEveryVector() throws IOException {
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/packetanalysis/ingest/metadata_vectors.json.gz")) {
            if (in == null) {
                fail("missing metadata_vectors.json.gz");
            }
            cases = new ObjectMapper().readTree(new GZIPInputStream(in));
        }
        assertTrue(cases.size() > 1000);
        Path file = dir.resolve("capture");
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cases) {
            Files.write(file, HexFormat.of().parseHex(c.get("hex").asText()));
            String name = c.get("name").asText();
            try {
                String actual = PyJson.dumps(CaptureMetadata.read(file, Cancellation.NONE));
                if (c.has("error")) {
                    failures.add(name + ": expected " + c.get("error").asText() + ", got " + actual);
                } else if (!actual.equals(c.get("result").asText())) {
                    failures.add(name + ": expected " + c.get("result").asText() + ", got " + actual);
                }
            } catch (IllegalArgumentException e) {
                if (!c.has("error") || !c.get("error").asText().equals("ValueError")) {
                    failures.add(name + ": unexpected " + e);
                }
            }
        }
        assertEquals(List.of(), failures.subList(0, Math.min(10, failures.size())), failures.size() + " failures");
    }
}
