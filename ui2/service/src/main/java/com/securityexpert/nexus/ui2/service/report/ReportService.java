package com.securityexpert.nexus.ui2.service.report;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.report.ReportRunStore;
import com.securityexpert.nexus.ui2.persistence.report.ReportRunStore.RunRow;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService;

/**
 * Reports R1 (docs/design/REPORTS_R1_CONTRACT.md, FROZEN): catalogue, on-screen preview, generated files, history and
 * download. Order of a file build: read stored evidence, build one document, project it once (MASKED or RAW), render,
 * check the 25 MiB cap, store encrypted, record SUCCEEDED (one audit row). Order of a download: authorize, check the
 * run and its retention, write the download audit row, and only then open the encrypted file.
 *
 * <p>ZM-01, the zone matrix evidence report (ZONE_MATRIX_CONTRACT.md 3.4, 7.3), follows the same order. Its content
 * follows the caller's screen view ({@link ReportBuilder.Caller}); its opt-in blocks appendix is refused with
 * {@code BLOCKS_NOT_PERMITTED} (403) before anything is built or stored unless the request is RAW and the caller holds
 * {@code report_raw_view} and {@code zone_matrix_zones_read} and is not a replay viewer (ZM-38).</p>
 */
public final class ReportService {

    private static final Logger LOG = Logger.getLogger(ReportService.class.getName());
    public static final Duration RETENTION = Duration.ofDays(30);
    public static final String SYSTEM_ACTOR = "system:reports";

    /** A refused request: HTTP status, a stable code and a reason the screen shows. */
    public static final class Refusal extends RuntimeException {
        private final int status;
        private final String code;

        public Refusal(int status, String code, String reason) {
            super(reason);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    /** A download ready to stream; the caller owns and closes {@code stream}. */
    public record Download(RunRow run, String fileName, String contentType, long bytes, InputStream stream) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReportData data;
    private final ReportRunStore runs;
    private final ArtefactStore store;
    private final ReportArtefactWriter writer;
    private final ReportDocumentMasker masker;
    private final ReportExecutor executor;
    private final ReportAccess access;
    private final Clock clock;
    private final Map<ReportFormat, ReportRenderer> renderers = new EnumMap<>(ReportFormat.class);

    public ReportService(ReportData data, ReportRunStore runs, ArtefactStore storeOrNull, ReportDocumentMasker masker,
            ReportExecutor executor, ReportAccess access, Clock clock) {
        this.data = Objects.requireNonNull(data, "data");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.store = storeOrNull;
        this.writer = new ReportArtefactWriter(storeOrNull);
        this.masker = Objects.requireNonNull(masker, "masker");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.access = Objects.requireNonNull(access, "access");
        this.clock = Objects.requireNonNull(clock, "clock");
        for (ReportRenderer r : List.of(new PdfReportRenderer(), new XlsxReportRenderer(), new CsvReportRenderer())) {
            renderers.put(r.format(), r);
        }
    }

    // ---- authorization --------------------------------------------------------------------------------------

    /** The projection this caller gets: a replay viewer is always MASKED; RAW needs {@code report_raw_view}. */
    ProjectionMode authorize(String actor, boolean replayViewer, ReportTemplate template, ProjectionMode requested) {
        return authorize(actor, replayViewer, template, null, requested);
    }

    /**
     * As {@link #authorize(String, boolean, ReportTemplate, ProjectionMode)}, and a requested ZM-01 blocks appendix
     * needs a RAW projection, {@code report_raw_view} and {@code zone_matrix_zones_read}, never a replay viewer (ZM-38).
     */
    ProjectionMode authorize(String actor, boolean replayViewer, ReportTemplate template, ReportParameters parameters,
            ProjectionMode requested) {
        List<String> missing = access.missingDomainReads(actor, template);
        if (!missing.isEmpty()) {
            throw new Refusal(403, "DOMAIN_READ_REQUIRED", "This report needs read access to: " + String.join(", ", missing));
        }
        if (parameters != null && parameters.includeBlocksAppendix()
                && !access.mayBlocksAppendix(actor, replayViewer, requested)) {
            throw ZoneMatrixEvidenceReportBuilder.blocksNotPermitted();
        }
        if (requested == ProjectionMode.RAW && !access.mayRaw(actor, replayViewer)) {
            throw new Refusal(403, "RAW_VIEW_REFUSED", replayViewer
                    ? "A masked (AIView) session never receives a RAW report"
                    : "A RAW report needs the report_raw_view action");
        }
        return requested;
    }

    public boolean mayRaw(String actor, boolean replayViewer) {
        return access.mayRaw(actor, replayViewer);
    }

    /** The caller's authorised view for a build; only ZM-01 reads the rights, so only ZM-01 evaluates them. */
    ReportBuilder.Caller caller(String actor, boolean replayViewer, ReportTemplate template, ProjectionMode mode) {
        if (!template.zoneMatrixParameters()) return new ReportBuilder.Caller(mode, replayViewer, false, false, false);
        return new ReportBuilder.Caller(mode, replayViewer, access.holds(actor, ActionRegistry.POLICY_READ),
                access.holds(actor, ActionRegistry.ZONE_MATRIX_ZONES_READ), access.mayRaw(actor, replayViewer));
    }

    public List<String> missingDomainReads(String actor, ReportTemplate template) {
        return access.missingDomainReads(actor, template);
    }

    // ---- preview --------------------------------------------------------------------------------------------

    /** The on-screen preview: built and projected now, never stored. */
    public ReportDocument preview(String actor, boolean replayViewer, ReportTemplate template, ReportParameters parameters,
            ProjectionMode requested) {
        ProjectionMode mode = authorize(actor, replayViewer, template, parameters, requested);
        ReportBuilder.Caller caller = caller(actor, replayViewer, template, mode);
        Instant now = clock.instant();
        try {
            return executor.runNow(() -> masker.project(ReportBuilder.forTemplate(template)
                    .build(new ReportBuilder.Context(template, parameters, now, actor, caller), data), mode));
        } catch (ZoneMatrixService.Refused e) {
            throw new Refusal(e.status, e.code, e.getMessage());
        }
    }

    // ---- generate -------------------------------------------------------------------------------------------

    public RunRow submit(String actor, boolean replayViewer, ReportTemplate template, ReportParameters parameters,
            ProjectionMode requested, ReportFormat format) {
        if (format == ReportFormat.JSON || !template.fileFormats().contains(format)) {
            throw new Refusal(400, "INVALID_FORMAT", "This report is generated as "
                    + String.join(" or ", template.fileFormatsOrdered().stream().map(Enum::name).toList()));
        }
        ProjectionMode mode = authorize(actor, replayViewer, template, parameters, requested);
        ReportBuilder.Caller caller = caller(actor, replayViewer, template, mode);
        if (!writer.available()) {
            throw new Refusal(503, "STORE_UNAVAILABLE", "The service has no artefact store mounted");
        }
        Instant now = clock.instant();
        RunRow row = new RunRow(UUID.randomUUID(), template.id(), "QUEUED", mode.name(), format.name(),
                json(parameters.asMap()), "{}", null, null, null, actor, now, null, now.plus(RETENTION));
        runs.insertQueued(row);
        try {
            executor.submit(() -> execute(row, template, parameters, mode, format, caller));
        } catch (ReportExecutor.Busy busy) {
            runs.markFailed(row.runId(), "REPORTS_BUSY", "{}", actor);
            throw new Refusal(503, "REPORTS_BUSY", busy.getMessage());
        }
        return runs.find(row.runId()).orElse(row);
    }

    /** One file build; every failure ends the run FAILED with a code (the terminal transition is the audit row). */
    void execute(RunRow row, ReportTemplate template, ReportParameters parameters, ProjectionMode mode, ReportFormat format,
            ReportBuilder.Caller caller) {
        String dataAsOf = "{}";
        try {
            runs.markRunning(row.runId());
            ReportDocument doc = ReportBuilder.forTemplate(template)
                    .build(new ReportBuilder.Context(template, parameters, clock.instant(), row.requestedBy(), caller), data);
            dataAsOf = ReportJson.dataAsOfJson(doc.dataAsOf());
            ReportDocument projected = masker.project(doc, mode);
            byte[] bytes = renderers.get(format).render(projected);
            var manifest = writer.write(row.runId(), bytes, row.expiresAt());
            runs.markSucceeded(row.runId(), manifest, dataAsOf, row.requestedBy());
        } catch (ReportRenderer.TooLarge e) {
            fail(row, "REPORT_TOO_LARGE", dataAsOf);
        } catch (ReportArtefactWriter.StoreUnavailable e) {
            fail(row, "STORE_UNAVAILABLE", dataAsOf);
        } catch (com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore.QuotaExceededException e) {
            // The store refused the write (and audited it): the run ends with the quota reason, nothing is stored.
            LOG.log(Level.WARNING, "[REPORTS] storing run " + row.runId() + " refused: " + e.getMessage());
            fail(row, "STORE_QUOTA_EXCEEDED", dataAsOf);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[REPORTS] storing run " + row.runId() + " failed: " + e.getMessage());
            fail(row, "STORE_FAILED", dataAsOf);
        } catch (Refusal e) {
            fail(row, e.code(), dataAsOf);
        } catch (ZoneMatrixService.Refused e) {
            // Codes only (section 8.2): never a zone, block or rule name in the log or the run row.
            fail(row, e.code, dataAsOf);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "[REPORTS] building run " + row.runId() + " failed", e);
            fail(row, "BUILD_FAILED", dataAsOf);
        }
    }

    private void fail(RunRow row, String code, String dataAsOf) {
        try {
            runs.markFailed(row.runId(), code, dataAsOf, row.requestedBy());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "[REPORTS] could not record the failure of run " + row.runId(), e);
        }
    }

    // ---- history and download -------------------------------------------------------------------------------

    /** History: a session without RAW authority (every replay viewer) sees only MASKED runs. */
    public List<RunRow> history(String actor, boolean replayViewer, int limit) {
        boolean raw = access.mayRaw(actor, replayViewer);
        List<RunRow> out = new ArrayList<>();
        for (RunRow r : runs.list(limit)) {
            if (raw || "MASKED".equals(r.projectionMode())) out.add(r);
        }
        return out;
    }

    public RunRow run(String actor, boolean replayViewer, UUID runId) {
        RunRow r = runs.find(runId).orElseThrow(() -> new Refusal(404, "RUN_NOT_FOUND", "No such report run"));
        if ("RAW".equals(r.projectionMode()) && !access.mayRaw(actor, replayViewer)) {
            // Not even its existence: a RAW run is invisible to a session that may not download it.
            throw new Refusal(404, "RUN_NOT_FOUND", "No such report run");
        }
        return r;
    }

    public boolean downloadable(RunRow r) {
        return "SUCCEEDED".equals(r.state()) && r.artefactId() != null && r.expiresAt() != null
                && r.expiresAt().isAfter(clock.instant());
    }

    /** Authorize, check retention, audit, then open: no byte is read before the audit row is written. */
    public Download download(String actor, boolean replayViewer, UUID runId) {
        RunRow r = runs.find(runId).orElseThrow(() -> new Refusal(404, "RUN_NOT_FOUND", "No such report run"));
        ReportTemplate template = ReportTemplate.fromId(r.templateId())
                .orElseThrow(() -> new Refusal(404, "RUN_NOT_FOUND", "No such report run"));
        if ("RAW".equals(r.projectionMode()) && !access.mayRaw(actor, replayViewer)) {
            throw new Refusal(403, "RAW_VIEW_REFUSED", replayViewer
                    ? "A masked (AIView) session never receives a RAW report"
                    : "A RAW report needs the report_raw_view action");
        }
        List<String> missing = access.missingDomainReads(actor, template);
        if (!missing.isEmpty()) {
            throw new Refusal(403, "DOMAIN_READ_REQUIRED", "This report needs read access to: " + String.join(", ", missing));
        }
        if (template.zoneMatrixParameters() && blocksAppendix(r) && !access.mayBlocksAppendix(actor, replayViewer,
                ProjectionMode.parse(r.projectionMode()).orElse(ProjectionMode.MASKED))) {
            throw ZoneMatrixEvidenceReportBuilder.blocksNotPermitted();
        }
        if (!"SUCCEEDED".equals(r.state()) || r.artefactId() == null) {
            throw new Refusal(409, "RUN_NOT_READY", "The report has no file (state " + r.state() + ")");
        }
        if (!downloadable(r)) throw new Refusal(410, "REPORT_EXPIRED", "The report is past its 30-day retention");
        var location = runs.artefact(r.artefactId())
                .orElseThrow(() -> new Refusal(410, "REPORT_EXPIRED", "The report file was removed by retention"));
        if (store == null) throw new Refusal(503, "STORE_UNAVAILABLE", "The service has no artefact store mounted");
        try {
            runs.auditDownload(r.runId(), actor);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "[REPORTS] download audit row could not be written; download refused", e);
            throw new Refusal(503, "AUDIT_REFUSED", "The download could not be audited, so it was refused");
        }
        try {
            InputStream stream = store.retrieve(new ArtefactRef(location.recoveryVolumePath()), location.wrappedDataKey(), false);
            ReportFormat format = ReportFormat.parse(r.format()).orElse(ReportFormat.CSV);
            return new Download(r, fileName(r, format), format.contentType(), location.plaintextBytes(), stream);
        } catch (IOException e) {
            throw new Refusal(500, "STORE_FAILED", "The report file could not be read");
        }
    }

    /** Whether a stored ZM-01 run carries the blocks appendix; unreadable parameters count as carrying it (fail closed). */
    static boolean blocksAppendix(RunRow r) {
        try {
            return "true".equals(JSON.readTree(r.parametersJson()).path(ReportParameters.INCLUDE_BLOCKS_APPENDIX).asText());
        } catch (Exception e) {
            return true;
        }
    }

    /** {@code nexus-RPT-01-2026-11-05-masked.pdf}: template, generation day, projection. No identity. */
    static String fileName(RunRow r, ReportFormat format) {
        String day = r.requestedAt().atZone(com.securityexpert.nexus.ui2.platform.JobWindowPolicy.ZONE_ID).toLocalDate().toString();
        return "nexus-" + r.templateId() + "-" + day + "-" + r.projectionMode().toLowerCase(java.util.Locale.ROOT) + "."
                + format.extension();
    }

    /** Marks runs a stopped service left open as FAILED (SERVICE_RESTARTED). */
    public int recoverInterrupted() {
        return runs.failUnfinished("SERVICE_RESTARTED", SYSTEM_ACTOR);
    }

    /** Test seam: replaces the renderer of one format (size-cap and failure tests). */
    ReportService withRenderer(ReportRenderer renderer) {
        renderers.put(renderer.format(), renderer);
        return this;
    }

    public Optional<ReportRenderer> renderer(ReportFormat format) {
        return Optional.ofNullable(renderers.get(format));
    }

    public Instant now() {
        return clock.instant();
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
