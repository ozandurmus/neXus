package com.securityexpert.nexus.ui2.service.advisory;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Runs the Palo Alto parser over the vendor's full PAN-OS set when {@code ADVISORY_SAMPLES_DIR} (system property or environment)
 * names the directory that holds {@code pan_panos_adv.json}; skipped otherwise. Prints the counts the contract's acceptance asks for.
 */
class PaloAltoFullSampleTest {

    @Test
    void parsesTheFullVendorSet() throws Exception {
        String dir = System.getProperty("ADVISORY_SAMPLES_DIR", System.getenv("ADVISORY_SAMPLES_DIR"));
        assumeTrue(dir != null && !dir.isBlank(), "ADVISORY_SAMPLES_DIR not set");
        Path file = Path.of(dir, "pan_panos_adv.json");
        assumeTrue(Files.isRegularFile(file), "sample file missing");
        byte[] doc = Files.readAllBytes(file);
        int total = new ObjectMapper().readTree(doc).get("data").size();

        AdvisoryModel.ParseResult result = new PaloAltoAdvisoryParser().parse("https://security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories", doc);

        Map<String, Integer> notes = new TreeMap<>();
        int parsed = 0;
        int withRules = 0;
        for (AdvisoryModel.ParsedAdvisory a : result.advisories()) {
            if (a.parseState() == AdvisoryModel.ParseState.PARSED) {
                parsed++;
            } else {
                notes.merge(a.parseNote(), 1, Integer::sum);
            }
            if (!a.rules().isEmpty()) {
                withRules++;
            }
        }
        JsonNode data = new ObjectMapper().readTree(doc).get("data");
        int withList = 0;
        for (JsonNode r : data) {
            if (r.path("containers").path("cna").has("x_affectedList")) {
                withList++;
            }
        }
        int selfCheckFailed = notes.getOrDefault("SELF_CHECK_FAILED", 0);
        System.out.println("ADVISORY-SAMPLE records=" + total + " returned=" + result.advisories().size() + " rejected=" + result.rejected()
                + " PARSED=" + parsed + " NEEDS_REVIEW=" + notes + " withPanOsRules=" + withRules);
        System.out.println("ADVISORY-SAMPLE x_affectedList present=" + withList + " selfCheckFailed=" + selfCheckFailed);
        for (AdvisoryModel.ParsedAdvisory a : result.advisories()) {
            if (a.parseState() == AdvisoryModel.ParseState.NEEDS_REVIEW) {
                System.out.println("ADVISORY-SAMPLE review " + a.advisoryId() + " " + a.parseNote());
            }
        }
        assertTrue(total == result.advisories().size() + result.rejected());
    }
}
