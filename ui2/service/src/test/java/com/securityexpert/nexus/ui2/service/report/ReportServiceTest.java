package com.securityexpert.nexus.ui2.service.report;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.report.ReportRunStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Reports R1 contract "Tests": audit rows (one per generate, one per download, the download's before the first byte),
 * retention (30 days, expired files refused), the 25 MiB cap (fails, stores nothing), encrypted storage of class
 * {@code report}, and the bounded executor (at most two concurrent builds).
 */
class ReportServiceTest {

    static final Map<String, Set<String>> ROLES = Map.of(
            "synthetic-secadmin", Set.of(RoleToken.SECURITY_ADMIN),
            "synthetic-viewer", Set.of(RoleToken.VIEWER),
            "synthetic-aiview", Set.of(RoleToken.REPLAY_VIEWER),
            "synthetic-aiview-secadmin", Set.of(RoleToken.REPLAY_VIEWER, RoleToken.SECURITY_ADMIN),
            "synthetic-onboarding", Set.of(RoleToken.ONBOARDING_ADMIN));

    @TempDir Path root;
    InMemoryReportRunStore runs;
    FileArtefactStore store;
    ReportService service;

    static RbacEvaluator rbac() {
        RbacEvaluator rbac = mock(RbacEvaluator.class);
        var permitted = new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.of("test"), Optional.empty(), Optional.empty());
        var denied = new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty());
        when(rbac.evaluate(anyString(), any(), any())).thenAnswer(call -> {
            Optional<?> token = call.getArgument(1);
            return token.isPresent() && ROLES.getOrDefault(call.getArgument(0), Set.of()).contains(token.get()) ? permitted : denied;
        });
        when(rbac.evaluateAny(anyString(), anySet(), any())).thenAnswer(call -> {
            Set<?> wanted = call.getArgument(1);
            return ROLES.getOrDefault(call.getArgument(0), Set.of()).stream().anyMatch(wanted::contains) ? permitted : denied;
        });
        return rbac;
    }

    static FileArtefactStore store(Path root) {
        return new FileArtefactStore(root, ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
    }

    static ReportService service(InMemoryReportRunStore runs, FileArtefactStore store, ReportExecutor executor) {
        return new ReportService(new ReportFixtures.Data(), runs, store, ReportFixtures.masker(), executor,
                new ReportAccess(rbac(), new ActionRegistry()), Clock.fixed(Instant.now(), ZoneOffset.UTC));
    }

    static ReportExecutor direct() {
        return new ReportExecutor(Runnable::run, 2, Duration.ofSeconds(1));
    }

    @BeforeEach
    void setUp() {
        runs = new InMemoryReportRunStore();
        store = store(root);
        service = service(runs, store, direct());
    }

    private ReportParameters params(ReportTemplate t) {
        return ReportParameters.parse(t, null, null, null, service.now());
    }

    @Test
    void aGenerateWritesExactlyOneAuditRowWithTemplateFormatProjectionParametersAndArtefact() {
        var row = service.submit("synthetic-secadmin", false, ReportTemplate.RPT_03, params(ReportTemplate.RPT_03),
                ProjectionMode.MASKED, ReportFormat.CSV);
        assertEquals("SUCCEEDED", row.state());
        assertEquals(1, runs.audit.size());
        var audit = runs.audit.get(0);
        assertEquals(ReportRunStore.ACTION_GENERATE, audit.action());
        assertEquals("synthetic-secadmin", audit.actor());
        assertEquals("RPT-03", audit.templateId());
        assertEquals("CSV", audit.format());
        assertEquals("MASKED", audit.projection());
        assertTrue(audit.parametersJson().contains("\"from\""));
        assertEquals(row.artefactId(), audit.artefactId());
        assertTrue(runs.rows.get(row.runId()).dataAsOfJson().contains("\"plane\":\"backup\""));
    }

    @Test
    void theReportIsStoredEncryptedAsClassReportWithThirtyDayExpiry() throws IOException {
        var row = service.submit("synthetic-secadmin", false, ReportTemplate.RPT_03, params(ReportTemplate.RPT_03),
                ProjectionMode.MASKED, ReportFormat.CSV);
        var manifest = runs.artefacts.get(row.artefactId());
        assertEquals(ArtefactClass.REPORT, manifest.artefactClass());
        assertEquals(ArtefactClass.REPORT_VENDOR, manifest.vendor());
        assertEquals(ArtefactClass.REPORT_DEVICE_ID, manifest.deviceId());
        assertEquals(ReportArtefactWriter.RETENTION_TIER, manifest.retentionTier());
        assertEquals(row.requestedAt().plus(Duration.ofDays(30)), manifest.expiresAt().orElseThrow());
        assertEquals(row.requestedAt().plus(Duration.ofDays(30)), row.expiresAt());
        Path file = root.resolve(manifest.recoveryVolumePath());
        String onDisk = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(onDisk.contains("Backup coverage"), "the file at rest is ciphertext");
        try (InputStream in = store.retrieve(new com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef(
                manifest.recoveryVolumePath()), manifest.wrappedDataKey(), false)) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("Backup coverage"));
        }
    }

    @Test
    void aDownloadIsAuditedOnceBeforeTheFileIsOpened() throws IOException {
        var row = service.submit("synthetic-viewer", false, ReportTemplate.RPT_03, params(ReportTemplate.RPT_03),
                ProjectionMode.MASKED, ReportFormat.CSV);
        int before = runs.audit.size();
        var download = service.download("synthetic-viewer", false, row.runId());
        try (InputStream in = download.stream()) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).startsWith("\"Report\",\"RPT-03"));
        }
        assertEquals(before + 1, runs.audit.size());
        var audit = runs.audit.get(runs.audit.size() - 1);
        assertEquals(ReportRunStore.ACTION_DOWNLOAD, audit.action());
        assertEquals(row.artefactId(), audit.artefactId());
        assertEquals("CSV", audit.format());
        assertTrue(download.fileName().matches("nexus-RPT-03-\\d{4}-\\d{2}-\\d{2}-masked\\.csv"), download.fileName());
    }

    @Test
    void aDownloadWhoseAuditCannotBeWrittenIsRefusedWithNoByteRead() throws IOException {
        var row = service.submit("synthetic-secadmin", false, ReportTemplate.RPT_03, params(ReportTemplate.RPT_03),
                ProjectionMode.RAW, ReportFormat.CSV);
        // Remove the encrypted file: if the service opened it before the audit, the refusal would differ.
        Path file = root.resolve(runs.artefacts.get(row.artefactId()).recoveryVolumePath());
        Files.delete(file);
        runs.failAudit = true;
        var refusal = assertThrows(ReportService.Refusal.class, () -> service.download("synthetic-secadmin", false, row.runId()));
        assertEquals("AUDIT_REFUSED", refusal.code());
        assertFalse(runs.events.contains("audit-download"));
    }

    @Test
    void anExpiredOrRetentionRemovedReportIsRefusedAndHistoryShowsItNotDownloadable() {
        var row = service.submit("synthetic-viewer", false, ReportTemplate.RPT_03, params(ReportTemplate.RPT_03),
                ProjectionMode.MASKED, ReportFormat.CSV);
        runs.removed.add(row.artefactId());
        assertEquals("REPORT_EXPIRED", assertThrows(ReportService.Refusal.class,
                () -> service.download("synthetic-viewer", false, row.runId())).code());
        runs.removed.clear();
        runs.expire(row.runId());
        assertFalse(service.downloadable(runs.rows.get(row.runId())));
        assertEquals("REPORT_EXPIRED", assertThrows(ReportService.Refusal.class,
                () -> service.download("synthetic-viewer", false, row.runId())).code());
        assertEquals(1, runs.audit.size(), "no download audit row for a refused download");
    }

    @Test
    void aReportOverTheCapFailsWithReportTooLargeAndStoresNothing() throws IOException {
        service.withRenderer(new ReportRenderer() {
            @Override
            public ReportFormat format() {
                return ReportFormat.PDF;
            }

            @Override
            public byte[] render(ReportDocument document) {
                throw new TooLarge(MAX_BYTES);
            }
        });
        var row = service.submit("synthetic-secadmin", false, ReportTemplate.RPT_01, params(ReportTemplate.RPT_01),
                ProjectionMode.MASKED, ReportFormat.PDF);
        assertEquals("FAILED", row.state());
        assertEquals("REPORT_TOO_LARGE", row.errorCode());
        assertNull(row.artefactId());
        assertTrue(runs.artefacts.isEmpty());
        try (Stream<Path> files = Files.walk(root)) {
            assertEquals(0, files.filter(Files::isRegularFile).count(), "nothing reached the artefact store");
        }
        assertEquals(1, runs.audit.size());
        assertEquals("FAILED", runs.audit.get(0).state());
        var writer = new ReportArtefactWriter(store);
        assertThrows(ReportRenderer.TooLarge.class, () -> writer.write(java.util.UUID.randomUUID(),
                new byte[(int) ReportRenderer.MAX_BYTES + 1], Instant.now()));
    }

    @Test
    void withoutAnArtefactStoreGenerationIsRefusedButThePreviewWorks() {
        var noStore = service(runs, null, direct());
        assertEquals("STORE_UNAVAILABLE", assertThrows(ReportService.Refusal.class, () -> noStore.submit("synthetic-secadmin",
                false, ReportTemplate.RPT_03, params(ReportTemplate.RPT_03), ProjectionMode.MASKED, ReportFormat.PDF)).code());
        assertEquals("MASKED", noStore.preview("synthetic-secadmin", false, ReportTemplate.RPT_03, params(ReportTemplate.RPT_03),
                ProjectionMode.MASKED).projection());
        assertTrue(runs.rows.isEmpty(), "a preview is never stored");
    }

    @Test
    void theJsonFormatIsNotAFile() {
        assertEquals("INVALID_FORMAT", assertThrows(ReportService.Refusal.class, () -> service.submit("synthetic-secadmin", false,
                ReportTemplate.RPT_03, params(ReportTemplate.RPT_03), ProjectionMode.MASKED, ReportFormat.JSON)).code());
    }

    @Test
    void runsLeftOpenByARestartBecomeFailed() {
        var row = new ReportRunStore.RunRow(java.util.UUID.randomUUID(), "RPT-03", "QUEUED", "MASKED", "PDF", "{}", "{}",
                null, null, null, "synthetic-viewer", Instant.now(), null, Instant.now().plus(Duration.ofDays(30)));
        runs.insertQueued(row);
        assertEquals(1, service.recoverInterrupted());
        assertEquals("SERVICE_RESTARTED", runs.rows.get(row.runId()).errorCode());
        assertEquals(ReportService.SYSTEM_ACTOR, runs.audit.get(0).actor());
    }

    @Test
    void theExecutorRunsAtMostTwoBuildsAtOnceAndRefusesBeyondItsQueue() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var executor = new ReportExecutor(pool, ReportExecutor.MAX_CONCURRENT_BUILDS, Duration.ofMillis(100));
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
            for (int i = 0; i < 3; i++) {
                executor.submit(() -> {
                    peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    running.decrementAndGet();
                });
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(0, executor.availablePermits());
            assertThrows(ReportExecutor.Busy.class, () -> executor.runNow(() -> "preview"), "a third build waits, then is refused");
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, peak.get());
            assertEquals("preview", executor.runNow(() -> "preview"));
        } finally {
            pool.shutdownNow();
        }
        try (var real = new ReportExecutor()) {
            CountDownLatch hold = new CountDownLatch(1);
            int accepted = 0;
            try {
                for (int i = 0; i < ReportExecutor.MAX_CONCURRENT_BUILDS + ReportExecutor.QUEUE_CAPACITY + 1; i++) {
                    real.submit(() -> {
                        try {
                            hold.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    accepted++;
                }
            } catch (ReportExecutor.Busy expected) {
                // the bounded queue is full
            }
            hold.countDown();
            assertTrue(accepted <= ReportExecutor.MAX_CONCURRENT_BUILDS + ReportExecutor.QUEUE_CAPACITY);
        }
    }

    @Test
    void historyHidesRawRunsFromSessionsWithoutRawAuthority() {
        service.submit("synthetic-secadmin", false, ReportTemplate.RPT_03, params(ReportTemplate.RPT_03), ProjectionMode.RAW,
                ReportFormat.CSV);
        service.submit("synthetic-secadmin", false, ReportTemplate.RPT_03, params(ReportTemplate.RPT_03), ProjectionMode.MASKED,
                ReportFormat.CSV);
        assertEquals(2, service.history("synthetic-secadmin", false, 50).size());
        assertEquals(List.of("MASKED"), service.history("synthetic-aiview", true, 50).stream()
                .map(ReportRunStore.RunRow::projectionMode).toList());
        assertEquals(List.of("MASKED"), service.history("synthetic-viewer", false, 50).stream()
                .map(ReportRunStore.RunRow::projectionMode).toList());
    }

    @Test
    void previewAndFileUseTheSameProjection() throws IOException {
        var doc = service.preview("synthetic-aiview", true, ReportTemplate.RPT_02, params(ReportTemplate.RPT_02), ProjectionMode.MASKED);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ReportJson.toMap(doc));
        var row = service.submit("synthetic-aiview", true, ReportTemplate.RPT_02, params(ReportTemplate.RPT_02),
                ProjectionMode.MASKED, ReportFormat.CSV);
        String csv;
        try (InputStream in = service.download("synthetic-aiview", true, row.runId()).stream()) {
            csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String value : ReportFixtures.RAW_VALUES) {
            assertFalse(json.contains(value));
            assertFalse(csv.contains(value));
        }
        // Same clock, same data, same pseudonymizer: the on-screen document and the file are one projection.
        assertEquals(new String(service.renderer(ReportFormat.CSV).orElseThrow().render(doc), StandardCharsets.UTF_8), csv);
    }
}
