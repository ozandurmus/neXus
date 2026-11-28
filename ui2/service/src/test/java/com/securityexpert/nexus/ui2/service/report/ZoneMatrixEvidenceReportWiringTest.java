package com.securityexpert.nexus.ui2.service.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.BackupRow;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.ComplianceRow;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.DeviationRow;
import com.securityexpert.nexus.ui2.persistence.report.ReportRunStore;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Revision;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.ResolvedFinding;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixContent;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceBlocksAppendix;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceFixture;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReportInputs;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredSummary;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixSummaryView;

/**
 * Zone matrix delivery step 6b-2: ZM-01 wired into Reports R1 (ZONE_MATRIX_CONTRACT.md 3.4, 7.3, 8.2, 10.1 step 6;
 * {@code ZM-28}, {@code ZM-31}, {@code ZM-32}, {@code ZM-38}). Drives the real {@link ReportService} (direct executor,
 * encrypted file store, in-memory run store with the V190 audit rows) over the real zone matrix services and stores.
 * Synthetic names and documentation ranges only.
 */
class ZoneMatrixEvidenceReportWiringTest {

    private static final Instant NOW = Instant.parse("2026-11-25T08:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SECADMIN = "synthetic-secadmin";
    private static final String COMPLIANCE = "synthetic-compliance";
    private static final String VIEWER = "synthetic-viewer";
    private static final String AIVIEW = "synthetic-aiview";
    private static final String AIVIEW_SECADMIN = "synthetic-aiview-secadmin";
    private static final String OPERATOR = "synthetic-operator";
    /** Holds report_raw_view but, by the mock below, not zone_matrix_zones_read (no real role does today). */
    private static final String RAW_NO_ZONES = "synthetic-raw-no-zones";

    private static final Map<String, Set<String>> ROLES = Map.of(
            SECADMIN, Set.of(RoleToken.SECURITY_ADMIN),
            COMPLIANCE, Set.of(RoleToken.COMPLIANCE_ADMIN),
            VIEWER, Set.of(RoleToken.VIEWER),
            AIVIEW, Set.of(RoleToken.REPLAY_VIEWER),
            AIVIEW_SECADMIN, Set.of(RoleToken.REPLAY_VIEWER, RoleToken.SECURITY_ADMIN),
            OPERATOR, Set.of(RoleToken.OPERATOR),
            RAW_NO_ZONES, Set.of(RoleToken.SECURITY_ADMIN));

    @TempDir Path root;
    private InMemoryReportRunStore runs;
    private FileArtefactStore store;
    private ZoneMatrixEvidenceFixture zm;
    private RecordingSource source;
    private ReportService service;

    @BeforeEach
    void setUp() {
        runs = new InMemoryReportRunStore();
        store = ReportServiceTest.store(root);
        zm = new ZoneMatrixEvidenceFixture(NOW);
        source = new RecordingSource(ZoneMatrixEvidenceReportBuilder.Source.of(zm.matrixService(), zm.runService(),
                zm.versions()));
        service = new ReportService(new ZoneMatrixOnlyData(source), runs, store, ReportFixtures.masker(),
                new ReportExecutor(Runnable::run, 2, Duration.ofSeconds(1)), new ReportAccess(rbac(), new ActionRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        zm.close();
    }

    // ------------------------------------------------------------------ fixture

    private static RbacEvaluator rbac() {
        RbacEvaluator rbac = mock(RbacEvaluator.class);
        var permitted = new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.of("test"), Optional.empty(), Optional.empty());
        var denied = new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty());
        Set<String> zonesRead = new ActionRegistry().find(ActionRegistry.ZONE_MATRIX_ZONES_READ).orElseThrow()
                .requiredRoleTokens();
        when(rbac.evaluateAny(anyString(), anySet(), any())).thenAnswer(call -> {
            Set<?> wanted = call.getArgument(1);
            if (RAW_NO_ZONES.equals(call.getArgument(0)) && wanted.equals(zonesRead)) return denied;
            return ROLES.getOrDefault(call.getArgument(0), Set.of()).stream().anyMatch(wanted::contains) ? permitted : denied;
        });
        return rbac;
    }

    /** The production source, recording the period of every resolved-in-period read (ZM-32). */
    private static final class RecordingSource implements ZoneMatrixEvidenceReportBuilder.Source {
        private final ZoneMatrixEvidenceReportBuilder.Source delegate;
        final List<Instant[]> periods = new ArrayList<>();

        RecordingSource(ZoneMatrixEvidenceReportBuilder.Source delegate) {
            this.delegate = delegate;
        }

        @Override
        public StoredSummary storedSummary() {
            return delegate.storedSummary();
        }

        @Override
        public List<ResolvedFinding> resolvedInPeriod(Instant from, Instant to) {
            periods.add(new Instant[] {from, to});
            return delegate.resolvedInPeriod(from, to);
        }

        @Override
        public ZoneMatrixSummaryView.Labels labels(boolean masked, boolean policyRead) {
            return delegate.labels(masked, policyRead);
        }

        @Override
        public List<Revision> revisions(String versionId) {
            return delegate.revisions(versionId);
        }

        @Override
        public List<ZoneMatrixContent.Zone> auditedBlocks(String actor, String versionId) {
            return delegate.auditedBlocks(actor, versionId);
        }
    }

    /** ZM-01 reads only the zone matrix source; every other plane is empty. */
    private static final class ZoneMatrixOnlyData implements ReportData {
        private final ZoneMatrixEvidenceReportBuilder.Source zoneMatrix;

        ZoneMatrixOnlyData(ZoneMatrixEvidenceReportBuilder.Source zoneMatrix) {
            this.zoneMatrix = zoneMatrix;
        }

        @Override public List<DeviceSummaryRecord> devices() { return List.of(); }
        @Override public List<ComplianceRow> complianceEvaluations() { return List.of(); }
        @Override public Map<String, Instant> newestInventory() { return Map.of(); }
        @Override public Map<String, Instant> newestConfiguration() { return Map.of(); }
        @Override public List<BackupRow> latestBackups(Instant from, Instant to) { return List.of(); }
        @Override public List<DeviationRow> sizeDeviations(Instant from, Instant to) { return List.of(); }
        @Override public List<HaUnit> haUnits(String actor) { return List.of(); }
        @Override public ZoneMatrixEvidenceReportBuilder.Source zoneMatrixEvidence() { return zoneMatrix; }
    }

    private static ReportParameters params(Boolean appendix) {
        return ReportParameters.parse(ReportTemplate.ZM_01, null, null, null, null, null, appendix, NOW);
    }

    private ReportDocument preview(String actor, boolean replayViewer, ProjectionMode mode, Boolean appendix) {
        return service.preview(actor, replayViewer, ReportTemplate.ZM_01, params(appendix), mode);
    }

    private ReportRunStore.RunRow submit(String actor, boolean replayViewer, ProjectionMode mode, ReportFormat format,
            Boolean appendix) {
        return service.submit(actor, replayViewer, ReportTemplate.ZM_01, params(appendix), mode, format);
    }

    private byte[] download(String actor, boolean replayViewer, UUID runId) throws Exception {
        try (InputStream in = service.download(actor, replayViewer, runId).stream()) {
            return in.readAllBytes();
        }
    }

    private static String json(ReportDocument doc) throws Exception {
        return JSON.writeValueAsString(ReportJson.toMap(doc));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Raw bytes and extracted text of a file, plus the same with whitespace removed (a PDF wraps long names). */
    private static String scan(ReportFormat format, byte[] bytes) throws Exception {
        String scan = ReportMaskingBytesTest.scan(format, bytes);
        return scan + "\n" + scan.replaceAll("\\s+", "");
    }

    private static void assertNoPlantedNameOrAddress(String text, String what) {
        for (String name : ZoneMatrixEvidenceFixture.PLANTED_NAMES) {
            assertFalse(text.contains(name), what + " leaks a planted name");
        }
        for (String address : ZoneMatrixEvidenceFixture.ADDRESS_MARKERS) {
            assertFalse(text.contains(address), what + " leaks an address");
        }
    }

    private static ReportService.Refusal refusal(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(ReportService.Refusal.class, call);
    }

    private static boolean hasSection(ReportDocument doc, String id) {
        return doc.sections().stream().anyMatch(s -> s.id().equals(id));
    }

    private static List<String> findingRow(ReportDocument doc, int ruleNumber) {
        return ReportFixtures.rows(doc, "findings").stream().filter(r -> r.get(1).contains("(rule #" + ruleNumber + ")"))
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ catalogue, parameters, migration

    @Test
    void zm01IsInTheCatalogueAsCsvAndPdfOnlyAndXlsxIsRefusedBeforeAnyRun() {
        assertEquals(ReportTemplate.ZM_01, ReportTemplate.fromId("ZM-01").orElseThrow());
        assertEquals(List.of(ActionRegistry.ZONE_MATRIX_READ), ReportTemplate.ZM_01.domainReadActions());
        assertEquals(List.of(ReportFormat.PDF, ReportFormat.CSV), ReportTemplate.ZM_01.fileFormatsOrdered());
        assertTrue(ReportBuilder.forTemplate(ReportTemplate.ZM_01) instanceof ZoneMatrixEvidenceReportBuilder);
        assertEquals("INVALID_FORMAT", refusal(() -> submit(SECADMIN, false, ProjectionMode.MASKED, ReportFormat.XLSX, null))
                .code());
        assertTrue(runs.rows.isEmpty(), "a refused format stores no run");
        for (ReportTemplate t : ReportTemplate.values()) {
            if (t != ReportTemplate.ZM_01) {
                assertEquals(List.of(ReportFormat.PDF, ReportFormat.XLSX, ReportFormat.CSV), t.fileFormatsOrdered(), t.id());
            }
        }
    }

    @Test
    void zoneMatrixParametersAreValidatedForZm01AndRefusedOnEveryOtherTemplate() {
        ReportParameters defaults = params(null);
        assertFalse(defaults.includeBlocksAppendix(), "the blocks appendix is opt-in");
        assertFalse(defaults.asMap().containsKey(ReportParameters.INCLUDE_BLOCKS_APPENDIX));
        ReportParameters filtered = ReportParameters.parse(ReportTemplate.ZM_01, null, null, null,
                ZoneMatrixEvidenceFixture.POLICY_ID, "VIOLATION", true, NOW);
        assertEquals(ZoneMatrixEvidenceFixture.POLICY_ID, filtered.policyId());
        assertEquals("VIOLATION", filtered.outcome());
        assertEquals("true", filtered.asMap().get(ReportParameters.INCLUDE_BLOCKS_APPENDIX));
        assertEquals("INVALID_OUTCOME", assertThrows(ReportParameters.Invalid.class, () -> ReportParameters.parse(
                ReportTemplate.ZM_01, null, null, null, null, "COMPLIANT_PLEASE", null, NOW)).code());
        assertEquals("INVALID_POLICY_ID", assertThrows(ReportParameters.Invalid.class, () -> ReportParameters.parse(
                ReportTemplate.ZM_01, null, null, null, " ", null, null, NOW)).code());
        for (ReportTemplate t : ReportTemplate.values()) {
            if (t == ReportTemplate.ZM_01) continue;
            assertEquals("INVALID_PARAMETER", assertThrows(ReportParameters.Invalid.class, () -> ReportParameters.parse(
                    t, null, null, null, "P-CUR", null, null, NOW)).code(), t.id());
            assertEquals("INVALID_PARAMETER", assertThrows(ReportParameters.Invalid.class, () -> ReportParameters.parse(
                    t, null, null, null, null, "VIOLATION", null, NOW)).code(), t.id());
            assertEquals("INVALID_PARAMETER", assertThrows(ReportParameters.Invalid.class, () -> ReportParameters.parse(
                    t, null, null, null, null, null, true, NOW)).code(), t.id());
            assertFalse(ReportParameters.parse(t, null, null, null, null, null, false, NOW).includeBlocksAppendix());
        }
        assertEquals("UNKNOWN_POLICY", refusal(() -> service.preview(SECADMIN, false, ReportTemplate.ZM_01,
                ReportParameters.parse(ReportTemplate.ZM_01, null, null, null, "P-NOT-THERE", null, null, NOW),
                ProjectionMode.RAW)).code());
    }

    @Test
    void v277AdmitsEveryCatalogueTemplateIdExpandSafe() throws Exception {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V277__reports_zone_matrix_evidence.sql")) {
            assertNotNull(in, "V277 is on the classpath");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (ReportTemplate t : ReportTemplate.values()) {
            assertTrue(sql.contains("'" + t.id() + "'"), "report_run.template_id admits " + t.id());
        }
        assertTrue(sql.contains("ADD CONSTRAINT chk_report_run_template_id"));
        assertTrue(sql.contains(") NOT VALID;"), "added NOT VALID (expand-safe)");
        assertTrue(sql.contains("VALIDATE CONSTRAINT chk_report_run_template_id"));
        assertFalse(sql.contains("barrier-release"), "not a barrier release");
        assertFalse(sql.toUpperCase(java.util.Locale.ROOT).contains("CREATE TABLE"), "no new table");
        assertFalse(sql.toUpperCase(java.util.Locale.ROOT).contains("ADD COLUMN"), "no new column");
    }

    // ------------------------------------------------------------------ ZM-28

    @Test
    void zm28TheStoredFileSha256EqualsTheManifestHashOfItsAuditRowAndTheCsvGuardsAPlantedFormula() throws Exception {
        for (ReportFormat format : List.of(ReportFormat.CSV, ReportFormat.PDF)) {
            var row = submit(SECADMIN, false, ProjectionMode.RAW, format, null);
            assertEquals("SUCCEEDED", row.state(), format.name());
            assertEquals("ZM-01", row.templateId());
            var manifest = runs.artefacts.get(row.artefactId());
            var generate = runs.audit.stream().filter(a -> a.runId().equals(row.runId())
                    && ReportRunStore.ACTION_GENERATE.equals(a.action())).toList();
            assertEquals(1, generate.size(), "one generate audit row");
            assertEquals(manifest.artefactId(), generate.get(0).artefactId(), "the audit row names the stored file");
            assertEquals("ZM-01", generate.get(0).templateId());
            assertEquals("RAW", generate.get(0).projection());

            int before = runs.audit.size();
            byte[] bytes = download(SECADMIN, false, row.runId());
            assertEquals(manifest.plaintextSha256(), sha256(bytes), format + ": stored file sha256 = manifest hash");
            assertEquals(bytes.length, row.plaintextBytes().longValue());
            assertEquals(before + 1, runs.audit.size());
            var audit = runs.audit.get(runs.audit.size() - 1);
            assertEquals(ReportRunStore.ACTION_DOWNLOAD, audit.action());
            assertEquals(manifest.artefactId(), audit.artefactId());

            if (format == ReportFormat.CSV) {
                String csv = new String(bytes, StandardCharsets.UTF_8);
                assertTrue(csv.startsWith("\"Report\",\"ZM-01 "), csv.substring(0, 40));
                assertTrue(csv.contains("\"'" + ZoneMatrixEvidenceFixture.FORMULA_REFERENCE + "\""),
                        "the planted =cmd reference is escaped with a leading quote");
                assertFalse(csv.contains("\"" + ZoneMatrixEvidenceFixture.FORMULA_REFERENCE), "never a bare formula");
            } else {
                assertTrue(new String(bytes, StandardCharsets.ISO_8859_1).startsWith("%PDF"));
            }
        }
        assertTrue(runs.events.contains("audit-download"));
    }

    // ------------------------------------------------------------------ ZM-31

    @Test
    void zm31MaskedPreviewAndFilesForAiviewCarryNoPlantedNameAddressActorOrReference() throws Exception {
        ReportDocument doc = preview(AIVIEW, true, ProjectionMode.MASKED, null);
        assertEquals("MASKED", doc.projection());
        String text = json(doc);
        assertNoPlantedNameOrAddress(text, "aiview preview");
        assertTrue(text.contains("ZONE-"), "zone names are pseudonyms, not blanks");
        assertTrue(text.contains("USER-"), "actors are tokens");
        assertTrue(text.contains(ZoneMatrixEvidenceReportInputs.WITHHELD_REFERENCE), "references are withheld");
        assertFalse(hasSection(doc, ZoneMatrixEvidenceBlocksAppendix.SECTION_ID));

        for (ReportFormat format : List.of(ReportFormat.CSV, ReportFormat.PDF)) {
            var row = submit(AIVIEW, true, ProjectionMode.MASKED, format, null);
            assertEquals("SUCCEEDED", row.state());
            assertEquals("MASKED", row.projectionMode());
            String scan = scan(format, download(AIVIEW, true, row.runId()));
            assertNoPlantedNameOrAddress(scan, "aiview " + format);
            assertTrue(scan.contains("MASKED"), "the projection is stamped in the file");
        }

        // Non-vacuity: the same scan sees the planted names in a RAW file of a security_admin (the PDF check uses the
        // names printed in its wide two-column table, which a narrow column cannot wrap mid-name).
        var rawCsv = submit(SECADMIN, false, ProjectionMode.RAW, ReportFormat.CSV, null);
        String csvScan = scan(ReportFormat.CSV, download(SECADMIN, false, rawCsv.runId()));
        for (String name : List.of(ZoneMatrixEvidenceFixture.ZONE_A_NAME, ZoneMatrixEvidenceFixture.RULE_NAME,
                ZoneMatrixEvidenceFixture.POLICY_NAME, ZoneMatrixEvidenceFixture.PUBLISHER)) {
            assertTrue(csvScan.contains(name), "CSV RAW scan must see " + name + ", or the masked check proves nothing");
        }
        var rawPdf = submit(SECADMIN, false, ProjectionMode.RAW, ReportFormat.PDF, null);
        String pdfScan = scan(ReportFormat.PDF, download(SECADMIN, false, rawPdf.runId()));
        for (String name : List.of(ZoneMatrixEvidenceFixture.PUBLISHER, ZoneMatrixEvidenceFixture.EDITOR)) {
            assertTrue(pdfScan.contains(name), "PDF RAW scan must see " + name + ", or the masked check proves nothing");
        }
    }

    @Test
    void zm31TheContentFollowsTheCallersViewAndRawNeedsReportRawViewNeverAiview() throws Exception {
        // A caller without report_raw_view gets the masked view and is refused RAW.
        String viewer = json(preview(VIEWER, false, ProjectionMode.MASKED, null));
        assertNoPlantedNameOrAddress(viewer, "viewer MASKED preview");
        assertTrue(viewer.contains("ZONE-"));
        int builds = source.periods.size();
        assertEquals("RAW_VIEW_REFUSED", refusal(() -> preview(VIEWER, false, ProjectionMode.RAW, null)).code());
        assertEquals(403, refusal(() -> submit(VIEWER, false, ProjectionMode.RAW, ReportFormat.CSV, null)).status());

        // aiview never gets RAW, even bound to an admin role as well.
        for (String actor : List.of(AIVIEW, AIVIEW_SECADMIN)) {
            var refused = refusal(() -> preview(actor, true, ProjectionMode.RAW, null));
            assertEquals(403, refused.status());
            assertEquals("RAW_VIEW_REFUSED", refused.code());
            assertEquals("RAW_VIEW_REFUSED", refusal(() -> submit(actor, true, ProjectionMode.RAW, ReportFormat.PDF, null))
                    .code());
        }

        // Without zone_matrix_read the report is refused before anything is built or stored.
        var missing = refusal(() -> preview(OPERATOR, false, ProjectionMode.MASKED, null));
        assertEquals(403, missing.status());
        assertEquals("DOMAIN_READ_REQUIRED", missing.code());
        assertEquals("DOMAIN_READ_REQUIRED", refusal(() -> submit(OPERATOR, false, ProjectionMode.MASKED, ReportFormat.CSV,
                null)).code());
        assertTrue(runs.rows.isEmpty(), "no refused request stored a run");
        assertEquals(builds, source.periods.size(), "no refused request read zone matrix evidence");

        // security_admin asking MASKED gets the masked view; asking RAW gets names, editors without the publish row.
        assertNoPlantedNameOrAddress(json(preview(SECADMIN, false, ProjectionMode.MASKED, null)), "secadmin MASKED");
        ReportDocument raw = preview(SECADMIN, false, ProjectionMode.RAW, null);
        assertEquals("RAW", raw.projection());
        String rawText = json(raw);
        assertTrue(rawText.contains(ZoneMatrixEvidenceFixture.ZONE_A_NAME));
        assertTrue(rawText.contains(ZoneMatrixEvidenceFixture.CONFIRMED_REFERENCE));
        var editors = ReportFixtures.rows(raw, "matrix-version").stream().filter(r -> r.get(0).equals("Editors"))
                .findFirst().orElseThrow();
        assertEquals(ZoneMatrixEvidenceFixture.EDITOR, editors.get(1));
        for (String address : ZoneMatrixEvidenceFixture.ADDRESS_MARKERS) {
            assertFalse(rawText.contains(address), "no block or witness address without the opt-in appendix");
        }
        assertTrue(zm.accessKinds().isEmpty(), "no blocks were read");
    }

    // ------------------------------------------------------------------ ZM-38

    @Test
    void zm38TheBlocksAppendixIsRefusedForAiviewMaskedRequestsAndCallersMissingARightBeforeAnythingIsBuilt() {
        record Attempt(String actor, boolean replayViewer, ProjectionMode mode) {}
        List<Attempt> refused = List.of(
                new Attempt(AIVIEW, true, ProjectionMode.MASKED),
                new Attempt(AIVIEW, true, ProjectionMode.RAW),
                new Attempt(AIVIEW_SECADMIN, true, ProjectionMode.MASKED),
                new Attempt(AIVIEW_SECADMIN, true, ProjectionMode.RAW),
                new Attempt(SECADMIN, false, ProjectionMode.MASKED),
                new Attempt(RAW_NO_ZONES, false, ProjectionMode.RAW),
                new Attempt(COMPLIANCE, false, ProjectionMode.RAW),
                new Attempt(VIEWER, false, ProjectionMode.RAW));
        for (Attempt a : refused) {
            var preview = refusal(() -> preview(a.actor(), a.replayViewer(), a.mode(), true));
            assertEquals(403, preview.status(), a.toString());
            assertEquals(ZoneMatrixEvidenceBlocksAppendix.BLOCKS_NOT_PERMITTED, preview.code(), a.toString());
            for (ReportFormat format : List.of(ReportFormat.CSV, ReportFormat.PDF)) {
                var submit = refusal(() -> submit(a.actor(), a.replayViewer(), a.mode(), format, true));
                assertEquals(403, submit.status(), a.toString());
                assertEquals(ZoneMatrixEvidenceBlocksAppendix.BLOCKS_NOT_PERMITTED, submit.code(), a.toString());
            }
        }
        assertTrue(runs.rows.isEmpty(), "nothing stored");
        assertTrue(runs.audit.isEmpty(), "no run reached a terminal state");
        assertTrue(source.periods.isEmpty(), "nothing built");
        assertTrue(zm.accessKinds().isEmpty(), "no block was read");

        // The builder refuses on its own as well, before any read, if it is ever reached without the rights.
        var direct = new ReportBuilder.Context(ReportTemplate.ZM_01, params(true), NOW, SECADMIN,
                new ReportBuilder.Caller(ProjectionMode.MASKED, false, true, true, true));
        assertEquals(ZoneMatrixEvidenceBlocksAppendix.BLOCKS_NOT_PERMITTED, refusal(() -> new ZoneMatrixEvidenceReportBuilder()
                .build(direct, new ZoneMatrixOnlyData(source))).code());
        assertTrue(source.periods.isEmpty());
    }

    @Test
    void zm38TheBlocksAppendixIsAddedOnlyForRawWithReportRawViewAndZonesReadAndTheViewIsOnRecord() throws Exception {
        ReportDocument without = preview(SECADMIN, false, ProjectionMode.RAW, false);
        assertFalse(hasSection(without, ZoneMatrixEvidenceBlocksAppendix.SECTION_ID), "opt-in: absent by default");
        assertTrue(zm.accessKinds().isEmpty());

        ReportDocument with = preview(SECADMIN, false, ProjectionMode.RAW, true);
        assertTrue(hasSection(with, ZoneMatrixEvidenceBlocksAppendix.SECTION_ID));
        assertEquals(ZoneMatrixEvidenceBlocksAppendix.SECTION_ID, with.sections().get(with.sections().size() - 1).id(),
                "the appendix comes last");
        var blocks = ReportFixtures.rows(with, ZoneMatrixEvidenceBlocksAppendix.TABLE_ID);
        assertTrue(blocks.stream().anyMatch(r -> r.get(0).equals(ZoneMatrixEvidenceFixture.ZONE_A_NAME)
                && r.get(2).equals(ZoneMatrixEvidenceFixture.BLOCK_A)), blocks.toString());
        assertTrue(blocks.stream().anyMatch(r -> r.get(0).equals(ZoneMatrixEvidenceFixture.ZONE_B_NAME)
                && r.get(2).equals(ZoneMatrixEvidenceFixture.BLOCK_B)), blocks.toString());
        assertTrue(with.notices().contains(ZoneMatrixEvidenceReportBuilder.APPENDIX_NOTICE));
        assertEquals(List.of("BLOCKS_VIEW"), zm.accessKinds(), "the block view is on record");

        var row = submit(SECADMIN, false, ProjectionMode.RAW, ReportFormat.CSV, true);
        assertEquals("SUCCEEDED", row.state());
        assertTrue(row.parametersJson().contains("\"" + ReportParameters.INCLUDE_BLOCKS_APPENDIX + "\":\"true\""),
                "the opt-in is in the run's audited parameters");
        String csv = new String(download(SECADMIN, false, row.runId()), StandardCharsets.UTF_8);
        assertTrue(csv.contains(ZoneMatrixEvidenceBlocksAppendix.SECTION_TITLE));
        assertTrue(csv.contains(ZoneMatrixEvidenceFixture.BLOCK_A));
        assertEquals(List.of("BLOCKS_VIEW", "BLOCKS_VIEW"), zm.accessKinds());

        // A stored appendix run is not downloadable without the zone blocks right; the refusal writes no audit row.
        int audits = runs.audit.size();
        var refused = refusal(() -> download(RAW_NO_ZONES, false, row.runId()));
        assertEquals(403, refused.status());
        assertEquals(ZoneMatrixEvidenceBlocksAppendix.BLOCKS_NOT_PERMITTED, refused.code());
        assertEquals(audits, runs.audit.size());
        assertNotEquals(0, download(SECADMIN, false, row.runId()).length);
    }

    // ------------------------------------------------------------------ ZM-32

    @Test
    void zm32ThePeriodBoundsTheResolvedInPeriodReadAndFindingsResolvedWithinItAreListedWithTheirDates() {
        ReportParameters week = params(null);
        ReportDocument doc = service.preview(SECADMIN, false, ReportTemplate.ZM_01, week, ProjectionMode.RAW);
        assertEquals(1, source.periods.size(), "one resolved-in-period read per build");
        assertEquals(week.windowStart(), source.periods.get(0)[0]);
        assertNull(source.periods.get(0)[1], "no upper bound: findings resolved after the period end are not dropped");

        List<String> open = findingRow(doc, ZoneMatrixEvidenceFixture.OPEN_RULE);
        assertNotNull(open, "the open finding is listed");
        assertEquals("OPEN", open.get(9));
        List<String> resolved = findingRow(doc, ZoneMatrixEvidenceFixture.RESOLVED_IN_PERIOD_RULE);
        assertNotNull(resolved, "a finding resolved within the period is listed");
        assertEquals("RESOLVED", resolved.get(9));
        for (int column : List.of(10, 11, 12, 13)) {
            assertNotEquals("UNKNOWN", resolved.get(column), "first, first-any-version, last and resolved dates");
        }
        assertEquals("RULE_CHANGED", resolved.get(14));
        assertNull(findingRow(doc, ZoneMatrixEvidenceFixture.RESOLVED_BEFORE_PERIOD_RULE),
                "a finding resolved before the period is not listed");
        String header = ReportFixtures.rows(doc, "header").stream().filter(r -> r.get(0).startsWith("Period from"))
                .findFirst().orElseThrow().get(1);
        assertEquals(week.windowStart().toString(), header);

        // An earlier period: the read is bounded by it, and nothing of the fixture falls inside it.
        ReportParameters earlier = ReportParameters.parse(ReportTemplate.ZM_01, "2026-10-01", "2026-10-10", null, null,
                null, null, NOW);
        ReportDocument old = service.preview(SECADMIN, false, ReportTemplate.ZM_01, earlier, ProjectionMode.RAW);
        assertEquals(earlier.windowStart(), source.periods.get(1)[0]);
        assertNull(source.periods.get(1)[1]);
        assertTrue(ReportFixtures.rows(old, "findings").isEmpty(), ReportFixtures.rows(old, "findings").toString());
    }

    @Test
    void zm32AFindingOpenDuringThePeriodButResolvedAfterItsEndIsListedAndOneResolvedBeforeItsStartIsNot() {
        // The fixture's "before" finding is first observed 2026-10-24 and resolved 2026-10-26.
        ReportParameters during = ReportParameters.parse(ReportTemplate.ZM_01, "2026-10-20", "2026-10-25", null, null,
                null, null, NOW);
        ReportDocument doc = service.preview(SECADMIN, false, ReportTemplate.ZM_01, during, ProjectionMode.RAW);
        List<String> later = findingRow(doc, ZoneMatrixEvidenceFixture.RESOLVED_BEFORE_PERIOD_RULE);
        assertNotNull(later, "a finding open in the period and resolved after its end is listed");
        assertEquals("RESOLVED", later.get(9));
        assertNull(findingRow(doc, ZoneMatrixEvidenceFixture.RESOLVED_IN_PERIOD_RULE));

        ReportParameters after = ReportParameters.parse(ReportTemplate.ZM_01, "2026-11-01", "2026-11-10", null, null,
                null, null, NOW);
        ReportDocument later2 = service.preview(SECADMIN, false, ReportTemplate.ZM_01, after, ProjectionMode.RAW);
        assertNull(findingRow(later2, ZoneMatrixEvidenceFixture.RESOLVED_BEFORE_PERIOD_RULE),
                "a finding resolved before the period start is not listed");
    }
}
