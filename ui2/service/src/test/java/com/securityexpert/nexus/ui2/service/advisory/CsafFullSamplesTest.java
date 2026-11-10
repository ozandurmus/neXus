package com.securityexpert.nexus.ui2.service.advisory;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.*;

/**
 * Runs the Fortinet and Cisco parsers over the full public sample files when {@code ADVISORY_SAMPLES_DIR} names the
 * directory that holds them (skipped otherwise). Prints one line per file.
 */
class CsafFullSamplesTest {

    private static Path dir() {
        String env = System.getenv("ADVISORY_SAMPLES_DIR");
        assumeTrue(env != null && !env.isBlank(), "ADVISORY_SAMPLES_DIR not set");
        return Path.of(env);
    }

    private static String summary(ParseResult result) {
        StringBuilder sb = new StringBuilder("advisories=" + result.advisories().size() + " rejected=" + result.rejected());
        for (ParsedAdvisory a : result.advisories()) {
            int releases = 0;
            int rules = 0;
            for (ProductRule r : a.rules()) {
                for (VersionRule v : r.rules()) {
                    rules++;
                    if (v instanceof ExactVersions e) {
                        releases += e.versions().size();
                    }
                }
            }
            sb.append(" | ").append(a.advisoryId()).append(" state=").append(a.parseState()).append(" note=").append(a.parseNote())
                    .append(" severity=").append(a.severity()).append(" score=").append(a.cvssScore()).append(" cves=").append(a.cveIds().size())
                    .append(" trains=").append(a.rules().size()).append(" versionRules=").append(rules).append(" exactReleases=").append(releases);
        }
        return sb.toString();
    }

    @Test
    void fortinetSamples() throws IOException {
        Path d = dir();
        FortinetAdvisoryParser parser = new FortinetAdvisoryParser();
        for (String f : new String[] {"fortinet_rss.xml", "fortinet_csaf_1.json", "fortinet_csaf_2.json"}) {
            ParseResult result = parser.parse("u", Files.readAllBytes(d.resolve(f)));
            System.out.println("SAMPLE " + f + ": " + summary(result));
            assertEquals(0, result.rejected(), f);
        }
        ParseResult r1 = parser.parse("u", Files.readAllBytes(d.resolve("fortinet_csaf_1.json")));
        assertEquals(ParseState.PARSED, r1.advisories().getFirst().parseState());
        assertEquals(ParseState.NEEDS_REVIEW, parser.parse("u", Files.readAllBytes(d.resolve("fortinet_csaf_2.json")))
                .advisories().getFirst().parseState());
    }

    @Test
    void ciscoSamples() throws IOException {
        Path d = dir();
        CiscoAdvisoryParser parser = new CiscoAdvisoryParser();
        ParseResult changes = parser.parse("u", Files.readAllBytes(d.resolve("cisco_changes.csv")));
        System.out.println("SAMPLE cisco_changes.csv: " + summary(changes));
        assertEquals(ParseResult.empty(), changes);
        for (String f : new String[] {"cisco_asa_1.json", "cisco_asa_2.json"}) {
            long t0 = System.nanoTime();
            ParseResult result = parser.parse("u", Files.readAllBytes(d.resolve(f)));
            System.out.println("SAMPLE " + f + ": " + summary(result) + " ms=" + (System.nanoTime() - t0) / 1_000_000);
            assertEquals(1, result.advisories().size(), f);
            assertEquals(0, result.rejected(), f);
            assertEquals(ParseState.PARSED, result.advisories().getFirst().parseState(), f);
            assertFalse(result.advisories().getFirst().rules().isEmpty(), f);
        }
    }
}
