package com.securityexpert.nexus.ui2.service.advisorystore;

import static com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.*;
import static com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.PRODUCT_PAN_OS;
import com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryIngestService.Outcome;

class AdvisoryIngestServiceTest {
    private static final String RUN = "run-0000001";
    private static final String URL = "https://security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories";

    private final MemoryRepository repo = new MemoryRepository();
    private final FakeCore core = new FakeCore();
    private final AdvisoryIngestService service = new AdvisoryIngestService(repo, () -> Optional.of(core));

    private static ParsedAdvisory pan(String id, ParseState state) {
        return advisory("paloalto", id, Severity.HIGH, state, List.of("CVE-2026-0001"),
                List.of(below(PRODUCT_PAN_OS, "11.1", 5, "Upgrade to 11.1.5")));
    }

    @Test void parsedAdvisoriesReplaceStoredOnesAndTheRunCountsThem() {
        core.parsers.put("paloalto", (url, doc) -> new ParseResult(List.of(pan("CVE-2026-0001", ParseState.PARSED),
                pan("CVE-2026-0002", ParseState.NEEDS_REVIEW)), List.of(), 3));
        assertEquals(Outcome.STORED, service.ingest("paloalto", RUN, URL, "doc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, repo.advisories.size());
        var stored = repo.advisories.get("paloalto:CVE-2026-0001");
        assertEquals("HIGH", stored.severity());
        assertEquals(64, stored.contentSha256().length());
        assertEquals("Upgrade to 11.1.5", stored.rules().getFirst().fixedText());
        var run = repo.run(RUN, "paloalto").orElseThrow();
        assertEquals(1, run.documentsReceived());
        assertEquals(2, run.advisoriesStored());
        assertEquals(1, run.advisoriesNeedingReview());
        assertEquals(3, run.rejected());
        // The same advisory id in a later document replaces the stored row.
        core.parsers.put("paloalto", (url, doc) -> new ParseResult(List.of(pan("CVE-2026-0001", ParseState.NEEDS_REVIEW)), List.of(), 0));
        service.ingest("paloalto", RUN, URL, "doc2".getBytes(StandardCharsets.UTF_8));
        assertEquals("NEEDS_REVIEW", repo.advisories.get("paloalto:CVE-2026-0001").parseState());
        assertEquals(2, repo.run(RUN, "paloalto").orElseThrow().documentsReceived());
    }

    @Test void valuesOutsideTheStoreRulesAreCleanedOrTheAdvisoryIsRejected() {
        var longTitle = new ParsedAdvisory("paloalto:CVE-2026-0003", "paloalto", "T".repeat(500), Severity.LOW, new java.math.BigDecimal("11.0"),
                List.of("CVE-2026-0003", "not-a-cve"), LocalDate.of(2026, 1, 1), null, "http://insecure.example.test/x", ParseState.PARSED,
                "bad note!", List.of(new ProductRule(PRODUCT_PAN_OS, "11.1", List.of(new WholeTrain(Status.AFFECTED)), "F".repeat(300))));
        var foreignId = new ParsedAdvisory("fortinet:FG-IR-26-1", "fortinet", "x", Severity.LOW, null, List.of(), null, null,
                URL, ParseState.PARSED, null, List.of());
        var badProduct = advisory("paloalto", "CVE-2026-0004", Severity.LOW, ParseState.PARSED, List.of(),
                List.of(new ProductRule("Bad Product!", "11.1", List.of(new WholeTrain(Status.AFFECTED)), null)));
        core.parsers.put("paloalto", (url, doc) -> new ParseResult(List.of(longTitle, foreignId, badProduct), List.of(), 0));
        service.ingest("paloalto", RUN, URL, new byte[] {1});
        assertEquals(1, repo.advisories.size());
        var a = repo.advisories.get("paloalto:CVE-2026-0003");
        assertEquals(300, a.title().length());
        assertNull(a.cvssScore(), "a score outside 0..10 is dropped");
        assertEquals(List.of("CVE-2026-0003"), a.cveIds());
        assertEquals(URL, a.sourceUrl(), "a non-https link falls back to the validated document address");
        assertEquals("UNSPECIFIED_NOTE", a.parseNote());
        assertEquals(200, a.rules().getFirst().fixedText().length());
        assertEquals(2, repo.run(RUN, "paloalto").orElseThrow().rejected());
    }

    @Test void knownExploitedIsReplacedOnlyByTheCatalogSourceAndOnlyWhenItYieldedEntries() {
        var entry = new KnownExploited("CVE-2026-0001", LocalDate.of(2026, 2, 1), true);
        var kevUrl = "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json";
        core.parsers.put("cisa_kev", (url, doc) -> new ParseResult(List.of(), List.of(entry), 0));
        core.parsers.put("paloalto", (url, doc) -> new ParseResult(List.of(), List.of(entry), 0));
        service.ingest("paloalto", RUN, URL, new byte[] {1});
        assertEquals(0, repo.kevReplacements, "another source never replaces the catalog");
        service.ingest("cisa_kev", RUN, kevUrl, new byte[] {1});
        assertEquals(1, repo.kev.size());
        core.parsers.put("cisa_kev", (url, doc) -> ParseResult.empty());
        service.ingest("cisa_kev", "run-0000002", kevUrl, new byte[] {1});
        assertEquals(1, repo.kev.size(), "an empty or unreadable catalog keeps the last flag set");
        assertEquals(1, repo.kevReplacements);
    }

    @Test void aMissingCoreOrParserAndAThrowingParserStoreNothing() {
        assertEquals(Outcome.CORE_MISSING, new AdvisoryIngestService(repo, () -> Optional.empty()).ingest("paloalto", RUN, URL, new byte[] {1}));
        assertEquals(Outcome.PARSER_MISSING, service.ingest("paloalto", RUN, URL, new byte[] {1}));
        core.parsers.put("paloalto", (url, doc) -> { throw new IllegalStateException("document text must not be logged"); });
        assertEquals(Outcome.STORED, service.ingest("paloalto", RUN, URL, new byte[] {1}));
        assertTrue(repo.advisories.isEmpty());
        var run = repo.run(RUN, "paloalto").orElseThrow();
        assertEquals(1, run.rejected());
    }

    @Test void aStoreFailureAnswersWithACodeWordOnly() {
        core.parsers.put("paloalto", (url, doc) -> new ParseResult(List.of(pan("CVE-2026-0001", ParseState.PARSED)), List.of(), 0));
        var failing = new AdvisoryIngestService(new MemoryRepository() {
            @Override public void upsertAdvisories(List<StoredAdvisory> list) { throw new IllegalStateException("db down"); }
        }, () -> Optional.of(core));
        assertEquals(Outcome.STORE_FAILED, failing.ingest("paloalto", RUN, URL, new byte[] {1}));
    }

    private static byte[] body(String json) { return json.getBytes(StandardCharsets.UTF_8); }

    @Test void completeClosesTheRunAsOkPartialOrFailed() {
        core.parsers.put("paloalto", (url, doc) -> new ParseResult(List.of(pan("CVE-2026-0001", ParseState.PARSED)), List.of(), 0));
        service.ingest("paloalto", "run-ok-0001", URL, new byte[] {1});
        assertEquals(Outcome.STORED, service.complete("paloalto", "run-ok-0001", body("{\"documents_fetched\":1,\"documents_failed\":0,\"reason\":null}")));
        assertEquals("OK", repo.run("run-ok-0001", "paloalto").orElseThrow().outcome());

        service.ingest("paloalto", "run-part-01", URL, new byte[] {1});
        service.complete("paloalto", "run-part-01", body("{\"documents_fetched\":3,\"documents_failed\":2,\"reason\":\"FETCH_TIMEOUT\"}"));
        var partial = repo.run("run-part-01", "paloalto").orElseThrow();
        assertEquals("PARTIAL", partial.outcome());
        assertEquals("FETCH_TIMEOUT", partial.reason());
        assertEquals(2, partial.documentsFailed());

        service.complete("paloalto", "run-fail-01", body("{\"documents_fetched\":0,\"documents_failed\":1,\"reason\":\"TLS_VALIDATION_FAILED\"}"));
        var failed = repo.run("run-fail-01", "paloalto").orElseThrow();
        assertEquals("FAILED", failed.outcome());
        assertEquals("TLS_VALIDATION_FAILED", failed.reason());
        service.complete("paloalto", "run-fail-02", body("{\"documents_fetched\":0,\"documents_failed\":0,\"reason\":null}"));
        assertEquals("NOTHING_RECEIVED", repo.run("run-fail-02", "paloalto").orElseThrow().reason());
    }

    @Test void rejectedEntriesMakeTheRunPartial() {
        core.parsers.put("paloalto", (url, doc) -> new ParseResult(List.of(pan("CVE-2026-0001", ParseState.PARSED)), List.of(), 2));
        service.ingest("paloalto", RUN, URL, new byte[] {1});
        service.complete("paloalto", RUN, body("{\"documents_fetched\":1,\"documents_failed\":0,\"reason\":null}"));
        assertEquals("PARTIAL", repo.run(RUN, "paloalto").orElseThrow().outcome());
    }

    @Test void aClosedRunTakesNoMoreDocumentsAndClosingTwiceIsHarmless() {
        core.parsers.put("paloalto", (url, doc) -> new ParseResult(List.of(pan("CVE-2026-0001", ParseState.PARSED)), List.of(), 0));
        service.ingest("paloalto", RUN, URL, new byte[] {1});
        service.complete("paloalto", RUN, body("{\"documents_fetched\":1,\"documents_failed\":0,\"reason\":null}"));
        assertEquals(Outcome.RUN_CLOSED, service.ingest("paloalto", RUN, URL, new byte[] {1}));
        assertEquals(Outcome.STORED, service.complete("paloalto", RUN, body("{\"documents_fetched\":9,\"documents_failed\":9,\"reason\":null}")));
        assertEquals("OK", repo.run(RUN, "paloalto").orElseThrow().outcome());
    }

    @Test void aMalformedCloseBodyChangesNothing() {
        for (String bad : new String[] {"", "[]", "{}", "{\"documents_fetched\":-1,\"documents_failed\":0}",
                "{\"documents_fetched\":\"1\",\"documents_failed\":0}", "{\"documents_fetched\":1,\"documents_failed\":0,\"reason\":\"lower case\"}",
                "{\"documents_fetched\":1,\"documents_failed\":0,\"reason\":5}", "not json"}) {
            assertEquals(Outcome.COMPLETE_INVALID, service.complete("paloalto", RUN, body(bad)), bad);
        }
        assertTrue(repo.runs.isEmpty());
    }
}
