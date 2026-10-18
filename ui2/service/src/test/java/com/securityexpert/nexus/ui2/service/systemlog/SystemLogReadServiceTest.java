package com.securityexpert.nexus.ui2.service.systemlog;

import static com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadFixture.CHUNK;
import static com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadFixture.DEVICE;
import static com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadFixture.bytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService.TextRequest;

/**
 * The read side of the system-log programme: bounded, line-aligned pages of really decrypted synthetic chunks, the masked
 * projection by default, the audited unmasked view, and the management-server refusal. Synthetic data only
 * (documentation addresses, example names).
 */
class SystemLogReadServiceTest {
    @TempDir
    Path root;
    SystemLogReadFixture fx;

    @BeforeEach
    void setUp() {
        fx = new SystemLogReadFixture(root);
    }

    private static TextRequest masked(String chunk, long offset, int limit) {
        return new TextRequest(chunk, offset, limit, false, false, "actor-1");
    }

    private static TextRequest unmasked(String chunk, long offset, int limit, boolean permitted) {
        return new TextRequest(chunk, offset, limit, true, permitted, "actor-1");
    }

    private static String text(SystemLogReadService.Text t) {
        assertTrue(t.isOk(), () -> String.valueOf(t.refusal()));
        return new String(t.page().text(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ masking and the unmasked view

    @Test
    void aPageIsMaskedByDefaultAndWritesNoLedgerRow() throws Exception {
        fx.chunk(CHUNK, bytes("drop src 192.0.2.17 dst 198.51.100.5\ncluster edge-fw-01 failed over\n"));

        var page = fx.service().text(masked(CHUNK, 0, 0));

        String shown = text(page);
        assertTrue(page.page().masked());
        assertFalse(shown.contains("192.0.2.17"), shown);
        assertFalse(shown.contains("198.51.100.5"), shown);
        assertFalse(shown.contains("edge-fw-01"), shown);
        assertTrue(shown.contains("FW-TANGO-04"), shown);
        assertTrue(shown.contains("drop src "));
        assertTrue(fx.ledgerRows.isEmpty(), "a masked view is not an unmasked view");
    }

    @Test
    void anUnmaskedViewByAPermittedCallerShowsTheStoredTextAndWritesOneLedgerRowFirst() throws Exception {
        var ref = fx.chunk(CHUNK, bytes("drop src 192.0.2.17\nsecond 198.51.100.5\n"));

        var page = fx.service().text(unmasked(CHUNK, 0, 0, true));

        assertEquals("drop src 192.0.2.17\nsecond 198.51.100.5\n", text(page));
        assertFalse(page.page().masked());
        assertEquals(1, fx.ledgerRows.size());
        var row = fx.ledgerRows.get(0);
        assertEquals(ref.artefactId(), row.artefactId());
        assertEquals("actor-1", row.actor());
        assertEquals(ActionRegistry.SYSTEM_LOG_READ_UNMASKED, row.action());
        assertTrue(row.reason().contains(CHUNK) && row.reason().contains("offset=0"), row.reason());
        assertFalse(row.reason().contains("192.0.2.17"), "the ledger row carries page coordinates, never log text");
    }

    @Test
    void everyUnmaskedPageIsItsOwnLedgerRow() throws Exception {
        fx.chunk(CHUNK, SystemLogReadFixture.hundredByteLines(30));
        var first = fx.service().text(unmasked(CHUNK, 0, 1024, true));
        fx.service().text(unmasked(CHUNK, first.page().nextOffset(), 1024, true));
        assertEquals(2, fx.ledgerRows.size());
        assertTrue(fx.ledgerRows.get(1).reason().contains("offset=" + first.page().nextOffset()));
    }

    @Test
    void anUnmaskedRequestWithoutTheRightIsRefusedBeforeAnythingIsReadOrRecorded() throws Exception {
        fx.chunk(CHUNK, bytes("drop src 192.0.2.17\n"));

        var page = fx.service().text(unmasked(CHUNK, 0, 0, false));

        assertEquals(403, page.refusal().status());
        assertEquals("SYSTEM_LOG_UNMASKED_NOT_PERMITTED", page.refusal().code());
        assertEquals(0, fx.store.retrievals.get());
        assertTrue(fx.ledgerRows.isEmpty());
    }

    @Test
    void ifTheLedgerRowCannotBeWrittenNothingIsDecrypted() throws Exception {
        fx.chunk(CHUNK, bytes("drop src 192.0.2.17\n"));
        fx.ledgerFails = true;

        var page = fx.service().text(unmasked(CHUNK, 0, 0, true));

        assertEquals(503, page.refusal().status());
        assertEquals("SYSTEM_LOG_AUDIT_UNAVAILABLE", page.refusal().code());
        assertEquals(0, fx.store.retrievals.get(), "no byte may be decrypted for a view that is not on record");
    }

    @Test
    void aPageThatIsNotUtf8IsShownOneByteOneCharacterNotGarbled() throws Exception {
        fx.chunk(CHUNK, new byte[] {'a', ' ', (byte) 0xE9, ' ', 'z', '\n'});
        assertEquals("a é z\n", text(fx.service().text(unmasked(CHUNK, 0, 0, true))));
    }

    // ------------------------------------------------------------------ bounds and paging

    @Test
    void aPageNeverExceedsTheHardMaximumWhateverIsAskedAndPagesAreLineAligned() throws Exception {
        byte[] all = SystemLogReadFixture.hundredByteLines(4000); // 400,000 bytes
        fx.chunk(CHUNK, all);

        var first = fx.service().text(unmasked(CHUNK, 0, Integer.MAX_VALUE, true));

        assertTrue(first.page().text().length <= SystemLogReadService.MAX_PAGE_BYTES, "hard maximum");
        assertEquals(SystemLogReadService.MAX_PAGE_BYTES, first.page().pageLimit());
        assertFalse(first.page().eof());
        assertEquals(0, first.page().nextOffset() % 100, "ends on a line boundary");
        assertEquals(first.page().nextOffset(), first.page().text().length);
    }

    @Test
    void pagingReassemblesTheChunkExactlyAndEndsWithEof() throws Exception {
        byte[] all = SystemLogReadFixture.hundredByteLines(2500);
        fx.chunk(CHUNK, all);
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        long offset = 0;
        int pages = 0;
        while (true) {
            var page = fx.service().text(unmasked(CHUNK, offset, 64 * 1024, true));
            assertTrue(page.isOk(), String.valueOf(page.refusal()));
            joined.writeBytes(page.page().text());
            pages++;
            if (page.page().eof()) break;
            assertEquals(offset + page.page().text().length, page.page().nextOffset());
            offset = page.page().nextOffset();
            assertTrue(pages < 20);
        }
        assertArrayEquals(all, joined.toByteArray());
        assertTrue(pages >= 4, "250,000 bytes in 64 KiB pages");
    }

    @Test
    void theDefaultPageIsSixtyFourKibAndATinyLimitIsRaisedToTheMinimum() throws Exception {
        fx.chunk(CHUNK, SystemLogReadFixture.hundredByteLines(2500));
        assertEquals(SystemLogReadService.DEFAULT_PAGE_BYTES, fx.service().text(masked(CHUNK, 0, 0)).page().pageLimit());
        assertEquals(1024, fx.service().text(masked(CHUNK, 0, 5)).page().pageLimit());
    }

    @Test
    void aLineLongerThanThePageButWithinTheHardMaximumIsServedWhole() throws Exception {
        String longLine = "x".repeat(5000);
        fx.chunk(CHUNK, bytes(longLine + "\nafter\n"));
        var page = fx.service().text(unmasked(CHUNK, 0, 1024, true));
        assertEquals(longLine + "\n", text(page));
        assertFalse(page.page().eof());
        assertEquals("after\n", text(fx.service().text(unmasked(CHUNK, page.page().nextOffset(), 1024, true))));
    }

    @Test
    void aLineBeyondTheHardMaximumIsRefusedNotSplit() throws Exception {
        fx.chunk(CHUNK, bytes("x".repeat(SystemLogReadService.MAX_PAGE_BYTES + 10) + "\n"));
        var page = fx.service().text(masked(CHUNK, 0, SystemLogReadService.MAX_PAGE_BYTES));
        assertEquals(422, page.refusal().status());
        assertEquals("SYSTEM_LOG_LINE_TOO_LONG", page.refusal().code());
    }

    @Test
    void anOffsetInTheMiddleOfALineIsRefusedSoNoTokenIsSplitBetweenMaskedPages() throws Exception {
        fx.chunk(CHUNK, bytes("src 192.0.2.17 one\nsrc 192.0.2.18 two\n"));
        var page = fx.service().text(masked(CHUNK, 7, 0));
        assertEquals(400, page.refusal().status());
        assertEquals("OFFSET_NOT_ON_LINE_BOUNDARY", page.refusal().code());
        assertEquals(416, fx.service().text(masked(CHUNK, 9999, 0)).refusal().status());
        assertEquals(400, fx.service().text(masked(CHUNK, -1, 0)).refusal().status());
    }

    @Test
    void anOffsetAtTheEndReturnsAnEmptyEofPage() throws Exception {
        byte[] all = bytes("one\ntwo\n");
        fx.chunk(CHUNK, all);
        var page = fx.service().text(masked(CHUNK, all.length, 0));
        assertTrue(page.isOk());
        assertEquals(0, page.page().text().length);
        assertTrue(page.page().eof());
    }

    @Test
    void aFinalLineWithoutATerminatorIsServedAndCounted() throws Exception {
        var page = fixtureText("one\ntwo");
        assertEquals("one\ntwo", text(page));
        assertEquals(2, page.page().lines());
        assertTrue(page.page().eof());
    }

    private SystemLogReadService.Text fixtureText(String content) throws Exception {
        fx.chunk(CHUNK, bytes(content));
        return fx.service().text(unmasked(CHUNK, 0, 0, true));
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void aMissingPrunedOrUnstoredChunkIsRefusedWithItsOwnCode() throws Exception {
        when(fx.repository.findChunk("nope")).thenReturn(Optional.empty());
        assertEquals(404, fx.service().text(masked("nope", 0, 0)).refusal().status());
        assertEquals(400, fx.service().text(masked("bad id!", 0, 0)).refusal().status());

        var ref = fx.chunk(CHUNK, bytes("x\n"));
        when(fx.repository.findChunk(CHUNK)).thenReturn(Optional.of(new SystemLogReadRepository.ChunkRef(ref.chunkId(),
                ref.deviceId(), ref.sourceKey(), 2, 1, Instant.now(), ref.artefactId(), ref.artefactRef(), ref.wrappedDataKey())));
        assertEquals(410, fx.service().text(masked(CHUNK, 0, 0)).refusal().status());

        when(fx.repository.findChunk(CHUNK)).thenReturn(Optional.of(ref));
        var noStore = fx.serviceWithoutStore().text(masked(CHUNK, 0, 0));
        assertEquals(503, noStore.refusal().status());
        assertEquals("SYSTEM_LOG_STORE_UNAVAILABLE", noStore.refusal().code());
    }

    @Test
    void aTamperedArtefactFailsClosedAsUnreadable() throws Exception {
        var ref = fx.chunk(CHUNK, SystemLogReadFixture.hundredByteLines(10));
        Path file = root.resolve(ref.artefactRef());
        byte[] enc = java.nio.file.Files.readAllBytes(file);
        enc[enc.length - 3] ^= 0x55;
        java.nio.file.Files.write(file, enc);
        var page = fx.service().text(masked(CHUNK, 0, 0));
        assertEquals(500, page.refusal().status());
        assertEquals("SYSTEM_LOG_CHUNK_UNREADABLE", page.refusal().code());
    }

    @Test
    void aManagementServerAnswersTheDisabledStateOnEveryRouteWhileTheSettingIsOff() throws Exception {
        fx.device("mgmt-1", "management_server", "check_point");
        var service = fx.service();
        for (var read : List.of(service.sources("mgmt-1", false), service.chunks("mgmt-1", null, null, 10),
                service.hcp("mgmt-1"), service.gaps("mgmt-1", 10), service.crashFiles("mgmt-1", 10, Instant.now()))) {
            assertTrue(read.isOk(), "a GET never refuses with a conflict for a device where collection does not apply");
            assertEquals("DISABLED_ON_MANAGEMENT", read.body().get("collection_state"));
            assertEquals("SYSTEM_LOG_DISABLED_ON_MANAGEMENT", read.body().get("code"));
            assertEquals("mgmt-1", read.body().get("device_id"));
        }
        // The route's own list is present and empty, so a client reads it like any other answer.
        assertEquals(List.of(), service.sources("mgmt-1", false).body().get("sources"));
        assertEquals(List.of(), service.chunks("mgmt-1", null, null, 10).body().get("chunks"));
        assertEquals(List.of(), service.gaps("mgmt-1", 10).body().get("gaps"));
        assertEquals(List.of(), service.crashFiles("mgmt-1", 10, Instant.now()).body().get("files"));
        assertEquals(List.of(), service.hcp("mgmt-1").body().get("files"));
        // A chunk stored while the setting was on is not served while it is off: absent (404), not a conflict.
        var ref = fx.chunk("mgmt-chunk", bytes("x\n"));
        when(fx.repository.findChunk("mgmt-chunk")).thenReturn(Optional.of(new SystemLogReadRepository.ChunkRef("mgmt-chunk",
                "mgmt-1", "cp_messages", 2, 1, null, ref.artefactId(), ref.artefactRef(), ref.wrappedDataKey())));
        var text = service.text(masked("mgmt-chunk", 0, 0));
        assertEquals(404, text.refusal().status());
        assertEquals("SYSTEM_LOG_DISABLED_ON_MANAGEMENT", text.refusal().code());
        // The fleet-wide lists are not device reads and carry no management rows.
        assertTrue(service.gaps(null, 10).isOk());
        assertEquals(0, fx.store.retrievals.get() - 0, "nothing of the management server was decrypted");
    }

    @Test
    void whenThePoEnablesManagementServersTheyAreReadLikeAnyDevice() {
        fx.device("mgmt-1", "management_server", "check_point");
        when(fx.settings.settings()).thenReturn(new SystemLogCollectionRepository.Settings(30, true));
        when(fx.repository.sources("mgmt-1")).thenReturn(List.of());
        assertTrue(fx.service().sources("mgmt-1", false).isOk());
    }

    @Test
    void anUnknownDeviceIsRefusedAndAnUnsupportedVendorIsAnExplicitState() {
        fx.device("ib-1", "gateway", "infoblox");
        var service = fx.service();
        assertEquals(404, service.sources("ghost", false).refusal().status());
        var unsupported = service.sources("ib-1", false);
        assertTrue(unsupported.isOk());
        assertEquals("VENDOR_UNSUPPORTED", unsupported.body().get("collection_state"));
        assertEquals("SYSTEM_LOG_VENDOR_UNSUPPORTED", unsupported.body().get("code"));
        assertEquals(List.of(), unsupported.body().get("sources"));
        assertEquals(400, service.sources("bad id", false).refusal().status());
    }

    // ------------------------------------------------------------------ metadata

    @Test
    void sourcesCarryCursorStateStatusPendingBytesFastRotationAndTheHorizon() {
        var at = Instant.parse("2026-10-17T09:00:00Z");
        when(fx.repository.sources(DEVICE)).thenReturn(List.of(
                new SystemLogReadRepository.SourceRow("cp_messages", true, 4096L, 1_790_000_000L, null, true, 7200L, at,
                        "BACKLOG", at, "job-1", 9000L, 4096L, 500_000L, 3, 12_000, 120, at),
                new SystemLogReadRepository.SourceRow("cp_sar_daily", false, null, null, null, false, null, null,
                        "NO_NEW_DATA", at, "job-1", 0L, 0L, 0, 0, 0, 0, null),
                new SystemLogReadRepository.SourceRow("cp_fwk_elg", false, null, null, null, false, null, null,
                        null, null, null, null, null, 0, 0, 0, 0, null)));
        when(fx.repository.lastJob(DEVICE)).thenReturn(Optional.of(new SystemLogReadRepository.LastJob("job-1", at,
                Map.of("BACKLOG", 1, "NO_NEW_DATA", 1), "2026-10-17 08:00:00", "2026-10-17 08:59:59")));

        var read = fx.service().sources(DEVICE, true);

        assertTrue(read.isOk());
        assertEquals(true, read.body().get("can_view_unmasked"));
        assertEquals("COLLECTED", read.body().get("collection_state"));
        @SuppressWarnings("unchecked")
        var sources = (List<Map<String, Object>>) read.body().get("sources");
        assertEquals("BACKLOG", sources.get(0).get("status"));
        assertEquals(500_000L, sources.get(0).get("pending_bytes"));
        assertEquals(true, sources.get(0).get("fast_rotating"));
        assertEquals(7200L, sources.get(0).get("retention_horizon_seconds"));
        assertEquals(4096L, ((Map<?, ?>) sources.get(0).get("cursor")).get("offset_bytes"));
        assertEquals("OK", sources.get(1).get("status"));
        assertNull(sources.get(1).get("cursor"));
        assertEquals("UNKNOWN", sources.get(2).get("status"), "a source that never ran is UNKNOWN, not OK");
        @SuppressWarnings("unchecked")
        var lastJob = (Map<String, Object>) read.body().get("last_job");
        assertEquals("2026-10-17 08:00:00", lastJob.get("window_from"));
        assertEquals("DEVICE_LOCAL", lastJob.get("window_time_basis"));
    }

    @Test
    void aDeviceNeverCollectedReportsNoCollectionYet() {
        when(fx.repository.sources(DEVICE)).thenReturn(List.of());
        when(fx.repository.lastJob(DEVICE)).thenReturn(Optional.empty());
        var read = fx.service().sources(DEVICE, false);
        assertEquals("NO_COLLECTION_YET", read.body().get("collection_state"));
        assertNull(read.body().get("last_job"));
    }

    @Test
    void theStatusPillMapsOutcomesWithoutHidingAnythingThatNeedsAttention() {
        assertEquals("OK", SystemLogReadService.status("COLLECTED"));
        assertEquals("OK", SystemLogReadService.status("NO_NEW_DATA"));
        for (String attention : List.of("BACKLOG", "GAP", "UNKNOWN", "STORAGE_REFUSED", "TOO_LARGE", "ABSENT", "UNSUPPORTED")) {
            assertEquals(attention, SystemLogReadService.status(attention));
        }
        assertEquals("UNKNOWN", SystemLogReadService.status(null));
    }

    @Test
    void chunkRowsHaveSizesCountsTimesRedactionsAndOutcomeButNoNameInodeOrKey() {
        var at = Instant.parse("2026-10-17T09:00:00Z");
        when(fx.repository.chunks(DEVICE, Optional.of("cp_messages"), Optional.of("2026-10-17"), 200)).thenReturn(List.of(
                new SystemLogReadRepository.ChunkRow("c-2", "cp_messages", "job-2", at, "2026-10-17", 100, 200, 100, 4,
                        "2026-10-17 09:00:00", "2026-10-17 09:00:03", 2, "cp-text-v1", List.of("LINE_SPLIT"), 60, null, null, "COLLECTED"),
                new SystemLogReadRepository.ChunkRow("c-1", "cp_messages", "job-1", at.minusSeconds(3600), "2026-10-17", 0, 100,
                        100, 4, null, null, 0, "cp-text-v1", List.of(), 55, null, null, "GAP")));

        var read = fx.service().chunks(DEVICE, "cp_messages", "2026-10-17", 200);

        @SuppressWarnings("unchecked")
        var chunks = (List<Map<String, Object>>) read.body().get("chunks");
        assertEquals(List.of("c-1", "c-2"), chunks.stream().map(c -> c.get("chunk_id")).toList(), "a day reads in collection order");
        var c = chunks.get(1);
        assertEquals(100L, c.get("bytes"));
        assertEquals(4L, c.get("lines"));
        assertEquals(2, c.get("redaction_count"));
        assertEquals("COLLECTED", c.get("outcome"));
        assertEquals("2026-10-17 09:00:03", c.get("last_line_time"));
        assertEquals(List.of("LINE_SPLIT"), c.get("flags"));
        for (String forbidden : List.of("file_name_hmac", "file_name", "name", "inode", "artefact_ref", "wrapped_data_key",
                "plaintext_hmac", "anchor_hmac")) {
            assertFalse(c.containsKey(forbidden), forbidden);
        }
    }

    @Test
    void chunkFiltersAreValidated() {
        var service = fx.service();
        assertEquals("SOURCE_INVALID", service.chunks(DEVICE, "../etc", null, 10).refusal().code());
        assertEquals("DAY_INVALID", service.chunks(DEVICE, null, "2026-13-45", 10).refusal().code());
        assertEquals("DAY_INVALID", service.chunks(DEVICE, null, "yesterday", 10).refusal().code());
        assertTrue(service.chunks(DEVICE, null, null, 10).isOk());
    }

    @Test
    void gapsCarryKindBytesAndWindowButNeverANameOrDetail() {
        var at = Instant.parse("2026-10-17T09:00:00Z");
        when(fx.repository.gaps(Optional.of(DEVICE), 200)).thenReturn(List.of(new SystemLogReadRepository.GapRow("g-1", DEVICE,
                "cp_messages", "job-1", "ROTATED_AWAY", 1234, 0, true, 1_790_000_000L, 1_790_021_600L, at)));
        var read = fx.service().gaps(DEVICE, 200);
        @SuppressWarnings("unchecked")
        var gap = ((List<Map<String, Object>>) read.body().get("gaps")).get(0);
        assertEquals("ROTATED_AWAY", gap.get("kind"));
        assertEquals(1234L, gap.get("known_lost_bytes"));
        assertEquals(true, gap.get("extent_unknown"));
        assertFalse(gap.containsKey("detail"));
        assertFalse(gap.containsKey("file_names"));
    }

    @Test
    void crashFilesAreClassifiedNewPresentBeforeMonitoringPresentOrRemoved() {
        Instant now = Instant.parse("2026-10-17T12:00:00Z");
        when(fx.repository.crashEntries(Optional.of(DEVICE), 200)).thenReturn(List.of(
                new SystemLogReadRepository.CrashRow("e-new", DEVICE, "dump_usermode", "fwd", 5000, 1_790_000_300L, false,
                        now.minusSeconds(3600), null),
                new SystemLogReadRepository.CrashRow("e-old", DEVICE, "dump_usermode", "fwd", 5000, 1_780_000_000L, false,
                        now.minusSeconds(30L * 86400), null),
                new SystemLogReadRepository.CrashRow("e-base", DEVICE, "dump_usermode", "UNKNOWN", 5000, 1_700_000_000L, true,
                        now.minusSeconds(40L * 86400), null),
                new SystemLogReadRepository.CrashRow("e-gone", DEVICE, "crash", "kernel", 5000, 1_700_000_001L, false,
                        now.minusSeconds(3600), now.minusSeconds(60))));
        when(fx.repository.crashAlerts(Optional.of(DEVICE), 50)).thenReturn(List.of(
                new SystemLogReadRepository.CrashAlertRow("a-1", DEVICE, "fwd", 2, 1_790_000_300L, "job-1", now, null)));

        var read = fx.service().crashFiles(DEVICE, 200, now);

        @SuppressWarnings("unchecked")
        var files = (List<Map<String, Object>>) read.body().get("files");
        assertEquals(List.of("NEW", "PRESENT", "PRESENT_BEFORE_MONITORING", "REMOVED"),
                files.stream().map(f -> f.get("state")).toList());
        @SuppressWarnings("unchecked")
        var counts = (Map<String, Integer>) read.body().get("counts");
        assertEquals(1, counts.get("NEW"));
        assertEquals(7, read.body().get("new_window_days"));
        assertEquals(1, ((List<?>) read.body().get("alerts")).size());
        for (var f : files) {
            assertFalse(f.containsKey("name"));
            assertFalse(f.containsKey("name_hmac"));
        }
    }

    @Test
    void hcpFilesAreMetadataOnlyAndTheMemberListingIsReportedUnavailable() {
        var at = Instant.parse("2026-10-17T03:00:00Z");
        when(fx.repository.hcpFiles(DEVICE, 50)).thenReturn(List.of(new SystemLogReadRepository.FileRow("f-1", "cp_hcp_report",
                "2026-10-17 03:00:12", 4_956_000, 1_790_000_000L, "STORED", List.of("NAME_SHAPE_UNEXPECTED"), at,
                at.plusSeconds(7 * 86400), null, 4_900_000)));
        var read = fx.service().hcp(DEVICE);
        assertEquals("NOT_AVAILABLE", read.body().get("member_listing"));
        @SuppressWarnings("unchecked")
        var file = ((List<Map<String, Object>>) read.body().get("files")).get(0);
        assertEquals(4_956_000L, file.get("size"));
        assertEquals("2026-10-17 03:00:12", file.get("name_time"));
        assertEquals("STORED", file.get("status"));
        assertEquals(false, file.get("pruned"));
        assertFalse(file.containsKey("name"));
        assertNotNull(file.get("expires_at"));
    }
}
