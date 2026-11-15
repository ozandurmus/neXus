package com.securityexpert.nexus.ui2.service.lifecyclesource;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleVendorRepository;

class LifecycleSourceServiceTest {

    /** An in-memory repository with the V219 replace semantics. */
    static final class Memory implements LifecycleVendorRepository {
        final List<VendorEntry> entries = new ArrayList<>();
        final List<Upload> uploads = new ArrayList<>();
        final List<String> actors = new ArrayList<>();
        final Map<String, FeedRun> runs = new java.util.LinkedHashMap<>();

        @Override public List<VendorEntry> entries() { return List.copyOf(entries); }

        @Override public void replaceFeedDocument(String source, String ref, List<VendorEntry> rows) {
            entries.removeIf(e -> e.origin().equals("FEED") && e.source().equals(source) && e.documentRef().equals(ref));
            rows.forEach(r -> entries.add(withTime(r)));
        }

        @Override public void storeUpload(Upload upload, String source, List<String> refs, List<VendorEntry> rows, String actor) {
            uploads.add(upload);
            actors.add(actor);
            entries.removeIf(e -> e.origin().equals("OPERATOR_PAGE") && e.source().equals(source) && refs.contains(e.documentRef()));
            rows.forEach(r -> entries.add(withTime(r)));
        }

        private static VendorEntry withTime(VendorEntry e) {
            return new VendorEntry(e.entryId(), e.origin(), e.source(), e.uploadId(), e.vendor(), e.kind(), e.productKey(), e.productLabel(),
                    e.endOfSale(), e.endOfEngineering(), e.endOfSupport(), e.endOfSecuritySupport(), e.endOfExtendedSupport(),
                    e.monthOnlyFields(), e.parseState(), e.parseNote(), e.documentRef(), e.sourceUrl(), e.contentSha256(), Instant.now());
        }

        @Override public List<Upload> latestUploads(int limit) { return List.copyOf(uploads); }

        @Override public void openRun(String runId, String source) {
            runs.putIfAbsent(runId, new FeedRun(runId, source, Instant.now(), null, null, 0, 0, 0, 0, 0, null, null, null));
        }

        @Override public void addToRun(String runId, String source, int received, int ignored, int rejected, int stored, int review) {
            var r = runs.get(runId);
            runs.put(runId, new FeedRun(runId, source, r.startedAt(), null, null, r.documentsReceived() + received, r.documentsIgnored() + ignored,
                    r.rejected() + rejected, r.entriesStored() + stored, r.entriesNeedingReview() + review, null, null, null));
        }

        @Override public void closeRun(String runId, String source, String outcome, Integer fetched, Integer failed, String reason) {
            var r = runs.getOrDefault(runId, new FeedRun(runId, source, Instant.now(), null, null, 0, 0, 0, 0, 0, null, null, null));
            runs.put(runId, new FeedRun(runId, source, r.startedAt(), Instant.now(), outcome, r.documentsReceived(), r.documentsIgnored(),
                    r.rejected(), r.entriesStored(), r.entriesNeedingReview(), fetched, failed, reason));
        }

        @Override public Optional<FeedRun> run(String runId, String source) { return Optional.ofNullable(runs.get(runId)); }

        @Override public List<FeedRun> latestRuns() { return List.copyOf(runs.values()); }

        @Override public List<FeedRun> lastGoodRuns() {
            return runs.values().stream().filter(r -> "OK".equals(r.outcome()) || "PARTIAL".equals(r.outcome())).toList();
        }
    }

    private static final String URL = "https://www.cisco.com/c/en/us/products/collateral/security/firepower-ngfw/firepower-4100-series-eol.html";

    private static byte[] bulletin(String support) {
        return ("<html><head><title>End-of-Sale and End-of-Life Announcement for the Cisco Firepower 4100 Series</title></head><body>"
                + "<p>EOL00042</p><table><tr><th>Milestone</th><th>Definition</th><th>Date</th></tr>"
                + "<tr><td>End-of-Sale Date: HW</td><td>x</td><td>January 6, 2026</td></tr>"
                + "<tr><td>Last Date of Support: HW</td><td>x</td><td>" + support + "</td></tr></table>"
                + "<table><tr><th>End-of-Sale Product Part Number</th><th>Product Description</th></tr>"
                + "<tr><td>FPR-4115-K9</td><td>x</td></tr><tr><td>FPR-4125-K9</td><td>x</td></tr></table></body></html>")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aFetchedBulletinReplacesItsOwnEntriesAndTheRunIsCounted() {
        var memory = new Memory();
        var service = new LifecycleSourceService(memory);
        assertEquals(LifecycleSourceService.Outcome.STORED, service.ingest("cisco_eol", "run-00000001", URL, bulletin("January 31, 2031")));
        assertEquals(2, memory.entries.size());
        assertEquals("FEED:cisco_eol:EOL00042:HARDWARE:FPR4115", memory.entries.getFirst().entryId());
        assertEquals(URL, memory.entries.getFirst().sourceUrl());
        assertEquals(LifecycleSourceService.Outcome.STORED, service.ingest("cisco_eol", "run-00000001", URL, bulletin("31/01/2031")));
        assertEquals(2, memory.entries.size(), "the amended bulletin replaces, it does not add");
        assertTrue(memory.entries.stream().allMatch(e -> "NEEDS_REVIEW".equals(e.parseState()) && e.endOfSupport() == null));
        assertEquals(LifecycleSourceService.Outcome.STORED, service.ingest("cisco_eol", "run-00000001", URL,
                "<html><title>Cisco listing</title></html>".getBytes(StandardCharsets.UTF_8)));
        var run = memory.runs.get("run-00000001");
        assertEquals(3, run.documentsReceived());
        assertEquals(1, run.documentsIgnored());
        assertEquals(4, run.entriesStored());
        assertEquals(2, run.entriesNeedingReview());
        assertEquals(LifecycleSourceService.Outcome.STORED, service.complete("cisco_eol", "run-00000001",
                "{\"documents_fetched\":85,\"documents_failed\":0,\"reason\":null}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("OK", memory.runs.get("run-00000001").outcome());
        assertEquals(LifecycleSourceService.Outcome.RUN_CLOSED, service.ingest("cisco_eol", "run-00000001", URL, bulletin("January 31, 2031")));
        assertEquals(LifecycleSourceService.Outcome.COMPLETE_INVALID, service.complete("cisco_eol", "run-00000002",
                "{\"documents_fetched\":\"x\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void runOutcomesFollowTheAdvisoryRules() {
        assertEquals("FAILED", LifecycleSourceService.outcome(5, 0, 5, 0, "TLS_VALIDATION_FAILED"));
        assertEquals("FAILED", LifecycleSourceService.outcome(0, 0, 0, 1, null));
        assertEquals("OK", LifecycleSourceService.outcome(0, 0, 2, 0, null));
        assertEquals("PARTIAL", LifecycleSourceService.outcome(3, 0, 4, 1, null));
        assertEquals("OK", LifecycleSourceService.outcome(3, 0, 3, 0, null));
    }

    @Test
    void anUploadedPageIsParsedStoredUnderItsActorAndReplacesTheEarlierUpload() {
        var memory = new Memory();
        var service = new LifecycleSourceService(memory);
        String header = "<tr><th>Appliance Product/Model</th><th>End of Sale</th><th>End of Engineering Support</th><th>End of Support</th></tr>";
        var first = service.upload("checkpoint_lifecycle", "<html><body><table>" + header
                + "<tr><td>6600 Appliance</td><td>Oct-2026</td><td>Oct-2029</td><td>Oct-2031</td></tr>"
                + "<tr><td>3600 Appliance</td><td>N/A</td><td>Feb 2030</td><td>Feb 2032</td></tr></table></body></html>", "synthetic-actor");
        assertNull(first.error());
        assertEquals(2, first.parsed());
        assertEquals(List.of("synthetic-actor"), memory.actors);
        var e = memory.entries.stream().filter(x -> x.productKey().equals("6600")).findFirst().orElseThrow();
        assertEquals("OPERATOR_PAGE", e.origin());
        assertEquals("checkpoint_lifecycle", e.source());
        assertEquals(first.uploadId(), e.uploadId());
        assertEquals("https://www.checkpoint.com/support-services/support-life-cycle-policy/", e.sourceUrl());
        assertEquals(List.of("end_of_sale", "end_of_engineering", "end_of_support"), e.monthOnlyFields());
        var second = service.upload("checkpoint_lifecycle", "<html><body><table>" + header
                + "<tr><td>6600 Appliance</td><td>Oct-2026</td><td>Oct-2029</td><td>Oct-2031</td></tr></table></body></html>", "synthetic-actor");
        assertNull(second.error());
        assertEquals(1, memory.entries.size(), "a model no longer on the page is no longer in the catalog");
        assertEquals(2, memory.uploads.size(), "every upload stays in the audited history");
    }

    @Test
    void anUploadThatIsNotTheChosenPageStoresNothing() {
        var memory = new Memory();
        var service = new LifecycleSourceService(memory);
        assertEquals("PAGE_FORM_UNKNOWN", service.upload("fortinet_portal", "<html></html>", "synthetic-actor").error());
        assertEquals("PAGE_EMPTY", service.upload("checkpoint_lifecycle", " ", "synthetic-actor").error());
        assertEquals("PAGE_NOT_RECOGNISED", service.upload("paloalto_hardware_eol", "<html><table><tr><td>x</td></tr></table></html>",
                "synthetic-actor").error());
        assertEquals("PAGE_TOO_LARGE", service.upload("checkpoint_lifecycle", "x".repeat(LifecycleSourceService.MAX_UPLOAD_CHARS + 1),
                "synthetic-actor").error());
        assertTrue(memory.uploads.isEmpty());
        assertTrue(memory.entries.isEmpty());
    }

    @Test
    void theSourcesViewNamesAgesFormsAndReviewWithoutTheUploaderForTheMaskedViewer() {
        var memory = new Memory();
        var service = new LifecycleSourceService(memory);
        service.upload("checkpoint_lifecycle", "<html><body><table><tr><th>Appliance Product/Model</th><th>End of Sale</th>"
                + "<th>End of Engineering Support</th><th>End of Support</th></tr><tr><td>6600</td><td>Oct-26</td><td>Oct-2029</td><td>Oct-2031</td></tr>"
                + "</table></body></html>", "synthetic-actor");
        var view = service.sources(Instant.now(), true);
        @SuppressWarnings("unchecked") var feeds = (List<Map<String, Object>>) view.get("feeds");
        assertEquals("cisco_eol", feeds.getFirst().get("source"));
        assertEquals("RED", feeds.getFirst().get("age_state"), "never loaded is red");
        @SuppressWarnings("unchecked") var uploads = (List<Map<String, Object>>) view.get("uploads");
        assertEquals("[REDACTED]", uploads.getFirst().get("uploaded_by"));
        @SuppressWarnings("unchecked") var review = (List<Map<String, Object>>) view.get("needs_review");
        assertEquals("DATE_UNREADABLE", review.getFirst().get("parse_note"));
        @SuppressWarnings("unchecked") var forms = (List<Map<String, Object>>) view.get("page_forms");
        assertEquals(4, forms.size());
        assertFalse(view.toString().contains("synthetic-actor"));
    }

    private static final String CP_PAGE = "https://www.checkpoint.com/support-services/support-life-cycle-policy/";
    private static final String PA_HW = "https://www.paloaltonetworks.com/services/support/end-of-life-announcements/hardware-end-of-life-dates";

    @Test
    void theCheckPointAndPaloAltoPagesAreReadOnlineWithTheUploadParsersAndAChangedPageIsReported() {
        var memory = new Memory();
        var service = new LifecycleSourceService(memory);
        String cp = "<html><body><table><tr><th>Appliance Product/Model</th><th>End of Sale</th><th>End of Engineering Support</th>"
                + "<th>End of Support</th></tr><tr><td>6600 Appliance</td><td>Oct-2026</td><td>Oct-2029</td><td>Oct-2031</td></tr></table></body></html>";
        assertEquals(LifecycleSourceService.Outcome.STORED, service.ingest("checkpoint_lifecycle", "run-00000003", CP_PAGE, cp.getBytes(StandardCharsets.UTF_8)));
        var e = memory.entries.getFirst();
        assertEquals("FEED:checkpoint_lifecycle:checkpoint-appliance-lifecycle:HARDWARE:6600", e.entryId());
        assertEquals(CP_PAGE, e.sourceUrl());
        String pa = "<html><body><table><tr><th>End-of-Sale Product</th><th>End-of-Sale Date</th><th>End-of-Life Date</th></tr>"
                + "<tr><td>PA-3200 Series (PA-3220, PA-3250)</td><td>August 31, 2023</td><td>August 31, 2028</td></tr></table></body></html>";
        assertEquals(LifecycleSourceService.Outcome.STORED, service.ingest("paloalto_eol", "run-00000004", PA_HW, pa.getBytes(StandardCharsets.UTF_8)));
        assertTrue(memory.entries.stream().anyMatch(x -> x.entryId().equals("FEED:paloalto_eol:paloalto-hardware-eol:HARDWARE:PA-3220")));

        assertEquals(LifecycleSourceService.Outcome.STORED, service.ingest("checkpoint_lifecycle", "run-00000005", CP_PAGE,
                "<html><body>redesigned page</body></html>".getBytes(StandardCharsets.UTF_8)));
        var run = memory.runs.get("run-00000005");
        assertEquals(1, run.rejected(), "a page that no longer has the tables is rejected, not ignored");
        assertEquals(0, run.documentsIgnored());
        service.complete("checkpoint_lifecycle", "run-00000005", "{\"documents_fetched\":1,\"documents_failed\":0,\"reason\":null}"
                .getBytes(StandardCharsets.UTF_8));
        assertEquals("PARTIAL", memory.runs.get("run-00000005").outcome());
        assertEquals("SOME_ENTRIES_REJECTED", memory.runs.get("run-00000005").reason());
        assertEquals(1, memory.entries.stream().filter(x -> x.source().equals("checkpoint_lifecycle")).count(), "the earlier entries stay and age");
    }

    @Test
    void onlyCiscoBulletinAddressesAreAcceptedFromTheFetcher() {
        assertTrue(LifecycleSources.feedDocumentAllowed("cisco_eol", URL));
        for (String refused : List.of("http://www.cisco.com/c/en/us/products/collateral/security/a/b.html",
                "https://www.cisco.com/c/en/us/products/security/asa-firepower-services/eos-eol-notice-listing.html",
                "https://www.cisco.com/c/en/us/products/collateral/security/a/b.pdf",
                "https://www.cisco.com/c/en/us/products/collateral/security/a/b.html?x=1",
                "https://www.cisco.com/c/en/us/products/collateral/security/../b.html",
                "https://www.cisco.com.example.org/c/en/us/products/collateral/security/a/b.html",
                "https://www.paloaltonetworks.com/c/en/us/products/collateral/security/a/b.html")) {
            assertFalse(LifecycleSources.feedDocumentAllowed("cisco_eol", refused), refused);
        }
        assertFalse(LifecycleSources.feedDocumentAllowed("paloalto_eol", URL));
        assertTrue(LifecycleSources.feedDocumentAllowed("checkpoint_lifecycle", CP_PAGE));
        assertTrue(LifecycleSources.feedDocumentAllowed("paloalto_eol", PA_HW));
        assertTrue(LifecycleSources.feedDocumentAllowed("paloalto_eol",
                "https://www.paloaltonetworks.com/services/support/end-of-life-announcements/end-of-life-summary"));
        for (String refused : List.of("https://www.checkpoint.com/support-services/", CP_PAGE + "x", "https://www.checkpoint.com/support-services/support-life-cycle-policy",
                "https://www.paloaltonetworks.com/services/support/end-of-life-announcements/end-of-life-policy")) {
            assertFalse(LifecycleSources.feedDocumentAllowed("checkpoint_lifecycle", refused) || LifecycleSources.feedDocumentAllowed("paloalto_eol", refused), refused);
        }
        assertFalse(LifecycleSources.feedDocumentAllowed("checkpoint_lifecycle", PA_HW), "each page belongs to its own source");
        assertEquals("firepower-4100-series-eol", LifecycleSources.pathRef(URL));
    }
}
