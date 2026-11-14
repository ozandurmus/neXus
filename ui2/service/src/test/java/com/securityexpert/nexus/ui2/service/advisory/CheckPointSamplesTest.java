package com.securityexpert.nexus.ui2.service.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The parser over the real public Check Point documents (ADVISORY_MATCHING_CONTRACT.md amendment A-2026-11-13, A.11). Runs
 * only when {@code CHECKPOINT_SAMPLES_DIR} names a folder holding the resolved-issues pages as
 * {@code <train>_All_Resolved_Issues.html} and CVE records as {@code cve_<CVE id>.json}; the documents are not part of the
 * repository. It prints counts only.
 */
class CheckPointSamplesTest {

    @Test
    void theRealPagesAndRecordsParseIntoKnownForms() throws IOException {
        String dir = System.getenv("CHECKPOINT_SAMPLES_DIR");
        assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "CHECKPOINT_SAMPLES_DIR not set");
        CheckPointAdvisoryParser parser = new CheckPointAdvisoryParser();
        List<AdvisoryModel.JumboStatement> statements = new ArrayList<>();
        for (String train : CheckPointGaiaComparator.COVERED_TRAINS) {
            Path page = Path.of(dir, train + "_All_Resolved_Issues.html");
            if (!Files.exists(page)) {
                continue;
            }
            var result = parser.parse("https://sc1.checkpoint.com/documents/Jumbo_HFA/" + train + "/All_Resolved_Issues.html",
                    Files.readAllBytes(page));
            assertEquals(train, result.jumboTrain(), train);
            Map<String, Long> kinds = result.jumboStatements().stream().collect(Collectors.groupingBy(AdvisoryModel.JumboStatement::kind,
                    TreeMap::new, Collectors.counting()));
            System.out.println("[CP_SAMPLES] page " + train + " statements=" + result.jumboStatements().size() + " kinds=" + kinds
                    + " cves=" + result.jumboStatements().stream().map(AdvisoryModel.JumboStatement::cveId).distinct().count()
                    + " rejected=" + result.rejected());
            assertTrue(!result.jumboStatements().isEmpty(), train);
            assertEquals(0, result.rejected(), "every CVE line of the real page is read: " + train);
            statements.addAll(result.jumboStatements());
        }
        try (var files = Files.list(Path.of(dir))) {
            for (Path record : files.filter(p -> p.getFileName().toString().matches("cve_CVE-.*\\.json")).sorted().toList()) {
                var result = parser.parse("https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/x.json", Files.readAllBytes(record),
                        cve -> statements.stream().filter(s -> s.cveId().equals(cve)).toList());
                assertEquals(1, result.advisories().size(), record.getFileName().toString());
                var a = result.advisories().getFirst();
                assertNotNull(a.parseState());
                assertEquals(AdvisoryModel.ParseState.PARSED, a.parseState(), "the real records of 2026-11-13 are known forms: "
                        + record.getFileName());
                System.out.println("[CP_SAMPLES] record state=" + a.parseState() + " note=" + a.parseNote() + " rules="
                        + a.rules().stream().map(r -> r.productKey().substring(11) + "/" + r.train() + "/"
                                + r.rules().stream().map(v -> v.getClass().getSimpleName()).collect(Collectors.joining("+")))
                                .collect(Collectors.joining(",")));
            }
        }
    }
}
