package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Golden topology fixtures (PATH_ANALYSIS_DESIGN.md §4 / §7 A3): one synthetic estate (documentation addresses,
 * pseudonym hostnames) built without and with overrides. A mismatch writes the actual rendering next to the build
 * output ({@code build/topology-golden/}) so a reviewer can diff it; a golden file is only ever updated by hand.
 */
class TopologyBuilderGoldenTest {

    @Test
    void estateWithoutOverridesMatchesItsGolden() throws IOException {
        var estate = TopologyFixtures.resource("estate.json");
        TopologyGraph graph = TopologyBuilder.build(TopologyFixtures.input(TopologyFixtures.devices(estate), List.of()));
        assertGolden("estate.golden.txt", TopologyFixtures.render(graph));
    }

    @Test
    void estateWithOverridesMatchesItsGolden() throws IOException {
        var estate = TopologyFixtures.resource("estate.json");
        var overrides = TopologyFixtures.overrides(TopologyFixtures.resource("overrides.json"));
        TopologyGraph graph = TopologyBuilder.build(TopologyFixtures.input(TopologyFixtures.devices(estate), overrides));
        assertGolden("estate-with-overrides.golden.txt", TopologyFixtures.render(graph));
    }

    private static void assertGolden(String name, String actual) throws IOException {
        String expected = TopologyFixtures.text(name);
        if (expected == null || !expected.equals(actual)) {
            Path out = Path.of("build", "topology-golden", name + ".actual");
            Files.createDirectories(out.getParent());
            Files.writeString(out, actual);
        }
        assertNotNull(expected, "missing golden " + name);
        assertEquals(expected, actual);
    }
}
