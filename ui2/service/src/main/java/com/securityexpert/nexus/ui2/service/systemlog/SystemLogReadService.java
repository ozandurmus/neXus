package com.securityexpert.nexus.ui2.service.systemlog;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.UUID;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactRetrievalRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository.ChunkRef;
import com.securityexpert.nexus.ui2.service.device.diagnostic.SystemLogCollectionService;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * The read side of the system-log programme (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md sections 5, 9, 12, 13 Q10 and 14):
 * sources, chunk index, the text of one chunk, gaps, crash and dump files, and the HCP archives.
 *
 * <ul>
 *   <li><b>Metadata</b> comes from the index tables only and never carries a name in clear (names are HMACs in the
 *       tables; the rows used here do not even select them).</li>
 *   <li><b>Text</b> is decrypted through the artefact store, one bounded page at a time. Every page is run through
 *       {@link LogMasking} unless the caller asked for unmasked text <em>and</em> holds
 *       {@code system_log_read_unmasked} (the controller decides that; this class refuses an unmasked request that
 *       was not permitted). An unmasked page writes its ledger row first: if the row cannot be written, no byte is
 *       decrypted. The text is already secret-redacted on write (section 12); "unmasked" means without the aiview
 *       projection of addresses and names, not without redaction.</li>
 *   <li><b>Bounds.</b> One response carries at most {@link #MAX_PAGE_BYTES} bytes of stored text. A page always ends on
 *       a line boundary and starts on one, so no token is split between two masked pages; a line longer than the hard
 *       maximum cannot be paged and is refused.</li>
 *   <li><b>Management servers</b> are not collected while {@code include_management_servers} is off (section 2.3), and
 *       a vendor outside Check Point and Palo Alto is never collected. Neither is an error of the read: a device read
 *       answers <em>200</em> with the route's usual shape empty and an explicit {@code collection_state}
 *       ({@code DISABLED_ON_MANAGEMENT} or {@code VENDOR_UNSUPPORTED}) with the stable {@code code} and a sentence. A
 *       GET never answers 409 for "does not apply"; a bad id stays 400 and an unknown device 404. The text of a chunk of
 *       such a device (stored while the setting was on) is 404 with the same code.</li>
 * </ul>
 */
public final class SystemLogReadService {
    /** The default size of one text page. */
    public static final int DEFAULT_PAGE_BYTES = 64 * 1024;
    /** The hard maximum of stored text in one response, whatever the caller asks for. */
    public static final int MAX_PAGE_BYTES = 256 * 1024;
    private static final int MIN_PAGE_BYTES = 1024;
    /** Masking can lengthen a page (pseudonyms); beyond this the page is refused rather than sent. */
    private static final int MAX_RESPONSE_BYTES = 4 * MAX_PAGE_BYTES;
    /** A non-baseline crash or dump file first seen within this many days is shown as new. */
    public static final int NEW_CRASH_WINDOW_DAYS = 7;
    /** Reason text of the ledger row of an unmasked view (no log text, only the page coordinates). */
    static final String UNMASKED_DESTINATION = "api";
    private static final Pattern DAY = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern CHUNK_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    /** The sources a chunk can belong to (V160 check on system_log_chunk.source_key). */
    public static final Set<String> CHUNK_SOURCES = Set.of("cp_messages", "cp_fwd_elg", "cp_cpd_elg", "cp_cpwd_elg",
            "cp_fwk_elg", "cp_mq_mng_elg", "cp_spike_log", "cp_spike_csv", "pan_system", "pan_config");
    private static final System.Logger LOG = System.getLogger(SystemLogReadService.class.getName());

    /** Why a read was refused: the HTTP status, a stable code and a sentence. */
    public record Refusal(int status, String code, String message) {
    }

    /** A JSON-shaped answer, or the refusal. */
    public record Read(Map<String, Object> body, Refusal refusal) {
        static Read ok(Map<String, Object> body) {
            return new Read(body, null);
        }

        static Read refused(int status, String code, String message) {
            return new Read(null, new Refusal(status, code, message));
        }

        static Read refused(Refusal refusal) {
            return new Read(null, refusal);
        }

        public boolean isOk() {
            return refusal == null;
        }
    }

    /** One text request. {@code unmaskedPermitted} is the caller's right to the unmasked view, decided by the route's controller. */
    public record TextRequest(String chunkId, long offset, int limit, boolean unmaskedRequested, boolean unmaskedPermitted,
            String actor) {
    }

    /** One page of text and what a client needs to ask for the next one. */
    public record TextPage(byte[] text, boolean masked, String sourceKey, long offset, long nextOffset, boolean eof,
            long lines, long chunkBytes, int pageLimit) {
    }

    /** The answer to a text request. */
    public record Text(TextPage page, Refusal refusal) {
        public boolean isOk() {
            return refusal == null;
        }
    }

    private final SystemLogReadRepository repository;
    private final SystemLogCollectionRepository settings;
    private final DeviceRepository devices;
    private final ArtefactStore store; // null when the service has no store mounted
    private final LogMasking masking;
    private final BackupArtefactRetrievalRepository ledger;

    public SystemLogReadService(SystemLogReadRepository repository, SystemLogCollectionRepository settings,
            DeviceRepository devices, ArtefactStore store, LogMasking masking, BackupArtefactRetrievalRepository ledger) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.store = store;
        this.masking = Objects.requireNonNull(masking, "masking");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    // ---------------------------------------------------------------- the device gate

    /** Why a device is not read for system logs although it exists: a state, its stable code and a sentence. */
    record NotApplicable(String state, String code, String message) {
    }

    /**
     * The answer for a device the read routes do not serve, or empty when the device is read normally: 400 for a bad id,
     * 404 for an unknown device, and, for a device where collection does not apply, a 200 body made of
     * {@code emptyShape} (the route's usual fields, empty) plus {@code collection_state}, {@code code} and
     * {@code message}.
     */
    private Optional<Read> deviceGate(String deviceId, Consumer<Map<String, Object>> emptyShape) {
        if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
            return Optional.of(Read.refused(400, "DEVICE_ID_INVALID", "The device id is not a valid opaque identifier."));
        }
        Optional<DeviceRecord> device = devices.find(deviceId);
        if (device.isEmpty()) return Optional.of(Read.refused(404, "DEVICE_NOT_FOUND", "No such device."));
        return notApplicable(device.get()).map(na -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("device_id", deviceId);
            emptyShape.accept(body);
            body.put("collection_state", na.state());
            body.put("code", na.code());
            body.put("message", na.message());
            return Read.ok(body);
        });
    }

    private Optional<NotApplicable> notApplicable(DeviceRecord device) {
        if ("management_server".equals(device.role()) && !settings.settings().includeManagementServers()) {
            return Optional.of(new NotApplicable("DISABLED_ON_MANAGEMENT", "SYSTEM_LOG_DISABLED_ON_MANAGEMENT",
                    "System-log collection is disabled on management servers (setting system_log.include_management_servers is off)."));
        }
        if (!SystemLogCollectionService.isSupportedVendor(device.vendorHint())) {
            return Optional.of(new NotApplicable("VENDOR_UNSUPPORTED", "SYSTEM_LOG_VENDOR_UNSUPPORTED",
                    "System-log collection covers Check Point and Palo Alto gateways only."));
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- sources

    public Read sources(String deviceId, boolean canViewUnmasked) {
        var gate = deviceGate(deviceId, body -> {
            body.put("can_view_unmasked", canViewUnmasked);
            body.put("default_page_bytes", DEFAULT_PAGE_BYTES);
            body.put("max_page_bytes", MAX_PAGE_BYTES);
            body.put("last_job", null);
            body.put("sources", List.of());
        });
        if (gate.isPresent()) return gate.get();
        var rows = repository.sources(deviceId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", deviceId);
        body.put("collection_state", rows.isEmpty() ? "NO_COLLECTION_YET" : "COLLECTED");
        body.put("can_view_unmasked", canViewUnmasked);
        body.put("default_page_bytes", DEFAULT_PAGE_BYTES);
        body.put("max_page_bytes", MAX_PAGE_BYTES);
        body.put("last_job", repository.lastJob(deviceId).map(job -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("job_id", job.jobId());
            m.put("at", iso(job.at()));
            m.put("outcomes", job.outcomes());
            m.put("window_from", job.windowFrom());
            m.put("window_to", job.windowTo());
            m.put("window_time_basis", "DEVICE_LOCAL");
            return m;
        }).orElse(null));
        List<Map<String, Object>> sources = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source_key", row.sourceKey());
            m.put("status", status(row.lastOutcome()));
            m.put("last_outcome", row.lastOutcome());
            m.put("last_run_at", iso(row.lastRunAt()));
            m.put("last_job_id", row.lastJobId());
            m.put("last_bytes_read", row.lastBytesRead());
            m.put("last_bytes_stored", row.lastBytesStored());
            m.put("pending_bytes", row.pendingBytes());
            m.put("fast_rotating", row.fastRotating());
            m.put("retention_horizon_seconds", row.retentionHorizonSeconds());
            if (row.hasCursor()) {
                Map<String, Object> cursor = new LinkedHashMap<>();
                cursor.put("offset_bytes", row.offsetBytes());
                cursor.put("device_epoch_at_read", row.deviceEpochAtRead());
                cursor.put("pan_last_receive_time", row.panLastReceiveTime());
                cursor.put("updated_at", iso(row.cursorUpdatedAt()));
                m.put("cursor", cursor);
            } else {
                m.put("cursor", null);
            }
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("chunks", row.liveChunks());
            stored.put("bytes", row.liveBytes());
            stored.put("lines", row.liveLines());
            stored.put("newest_chunk_at", iso(row.newestChunkAt()));
            m.put("stored", stored);
            sources.add(m);
        }
        body.put("sources", sources);
        return Read.ok(body);
    }

    /** The status pill of a source: the three states that need attention by name, OK, or the outcome word; no run is UNKNOWN. */
    static String status(String lastOutcome) {
        if (lastOutcome == null) return "UNKNOWN";
        return switch (lastOutcome) {
            case "COLLECTED", "NO_NEW_DATA" -> "OK";
            default -> lastOutcome;
        };
    }

    // ---------------------------------------------------------------- chunks

    public Read chunks(String deviceId, String source, String day, int limit) {
        var gate = deviceGate(deviceId, body -> {
            body.put("source", null);
            body.put("day", null);
            body.put("truncated", false);
            body.put("chunks", List.of());
        });
        if (gate.isPresent()) return gate.get();
        String sourceKey = source == null || source.isBlank() ? null : source;
        if (sourceKey != null && !CHUNK_SOURCES.contains(sourceKey)) {
            return Read.refused(400, "SOURCE_INVALID", "Unknown source key.");
        }
        String dayText = day == null || day.isBlank() ? null : day.strip();
        if (dayText != null && !validDay(dayText)) {
            return Read.refused(400, "DAY_INVALID", "The day must be a calendar date, yyyy-MM-dd.");
        }
        int bounded = Math.max(1, Math.min(limit, 1000));
        var rows = new ArrayList<>(repository.chunks(deviceId, Optional.ofNullable(sourceKey), Optional.ofNullable(dayText), bounded));
        boolean truncated = rows.size() >= bounded;
        // The index answers newest first; a day is read in the order it was collected.
        if (dayText != null) java.util.Collections.reverse(rows);
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunk_id", row.chunkId());
            m.put("source_key", row.sourceKey());
            m.put("job_id", row.jobId());
            m.put("collected_at", iso(row.collectedAt()));
            m.put("log_day", row.logDay());
            m.put("start_offset", row.startOffset());
            m.put("end_offset", row.endOffset());
            m.put("bytes", row.bytes());
            m.put("lines", row.lines());
            m.put("first_line_time", row.firstLineTime());
            m.put("last_line_time", row.lastLineTime());
            m.put("line_time_basis", "DEVICE_LOCAL");
            m.put("redaction_count", row.redactionCount());
            m.put("redaction_rule_set", row.redactionRuleSet());
            m.put("flags", row.flags());
            m.put("stored_bytes", row.ciphertextBytes());
            m.put("outcome", row.outcome());
            m.put("pruned", row.prunedAt() != null);
            m.put("prune_reason", row.pruneReason());
            chunks.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", deviceId);
        body.put("source", sourceKey);
        body.put("day", dayText);
        body.put("truncated", truncated);
        body.put("chunks", chunks);
        return Read.ok(body);
    }

    private static boolean validDay(String day) {
        if (!DAY.matcher(day).matches()) return false;
        try {
            LocalDate.parse(day);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- text

    public Text text(TextRequest request) {
        if (request.chunkId() == null || !CHUNK_ID.matcher(request.chunkId()).matches()) {
            return refusedText(400, "CHUNK_ID_INVALID", "The chunk id is not a valid opaque identifier.");
        }
        if (request.offset() < 0) return refusedText(400, "OFFSET_INVALID", "The offset must not be negative.");
        if (request.unmaskedRequested() && !request.unmaskedPermitted()) {
            return refusedText(403, "SYSTEM_LOG_UNMASKED_NOT_PERMITTED",
                    "The unmasked view needs the system_log_read_unmasked action; this session is limited to the masked view.");
        }
        Optional<ChunkRef> found = repository.findChunk(request.chunkId());
        if (found.isEmpty()) return refusedText(404, "CHUNK_NOT_FOUND", "No such chunk.");
        ChunkRef chunk = found.get();
        Optional<DeviceRecord> device = devices.find(chunk.deviceId());
        if (device.isPresent()) {
            var na = notApplicable(device.get());
            // The chunk belongs to a device that is no longer read: it is not served, and a GET does not answer 409 for that.
            if (na.isPresent()) return refusedText(404, na.get().code(), na.get().message());
        }
        if (chunk.prunedAt() != null) {
            return refusedText(410, "SYSTEM_LOG_CHUNK_PRUNED", "The chunk's bytes were removed by retention.");
        }
        if (store == null) {
            return refusedText(503, "SYSTEM_LOG_STORE_UNAVAILABLE",
                    "The service has no artefact store mounted (UI2_ARTEFACT_STORE_KEY_FILE / UI2_ARTEFACT_STORE_ROOT).");
        }
        if (chunk.artefactRef() == null || chunk.wrappedDataKey() == null) {
            return refusedText(404, "SYSTEM_LOG_ARTEFACT_MISSING", "The chunk's artefact is not recorded.");
        }
        int limit = request.limit() <= 0 ? DEFAULT_PAGE_BYTES : Math.max(MIN_PAGE_BYTES, Math.min(request.limit(), MAX_PAGE_BYTES));
        boolean masked = !request.unmaskedRequested();
        if (!masked) {
            // The ledger row comes first: no byte is decrypted for a view that is not on record.
            try {
                ledger.record(UUID.randomUUID().toString(), chunk.artefactId(),
                        "system_log_unmasked_view chunk=" + chunk.chunkId() + " offset=" + request.offset() + " limit=" + limit,
                        UNMASKED_DESTINATION, request.actor(), ActionRegistry.SYSTEM_LOG_READ_UNMASKED);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "unmasked system-log view refused: ledger row not written chunk={0} cause={1}",
                        chunk.chunkId(), e.getClass().getSimpleName());
                return refusedText(503, "SYSTEM_LOG_AUDIT_UNAVAILABLE",
                        "The unmasked view was refused because its ledger row could not be written.");
            }
        }
        Window window;
        try {
            window = readWindow(chunk, request.offset(), limit);
        } catch (BadWindow e) {
            return new Text(null, e.refusal);
        } catch (NoSuchFileException e) {
            return refusedText(404, "SYSTEM_LOG_ARTEFACT_MISSING", "The chunk's encrypted bytes are not on the artefact volume.");
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "system-log chunk unreadable chunk={0} cause={1}", chunk.chunkId(),
                    e.getClass().getSimpleName());
            return refusedText(500, "SYSTEM_LOG_CHUNK_UNREADABLE", "The chunk could not be decrypted.");
        }
        byte[] shown = masked ? masking.session().maskChunk(window.bytes) : window.bytes;
        byte[] utf8 = toUtf8(shown);
        if (utf8.length > MAX_RESPONSE_BYTES) {
            return refusedText(500, "SYSTEM_LOG_PAGE_TOO_LARGE", "The page exceeded the response bound after masking.");
        }
        return new Text(new TextPage(utf8, masked, chunk.sourceKey(), request.offset(), request.offset() + window.bytes.length,
                window.eof, countLines(window.bytes), chunk.bytes(), limit), null);
    }

    private static Text refusedText(int status, String code, String message) {
        return new Text(null, new Refusal(status, code, message));
    }

    private record Window(byte[] bytes, boolean eof) {
    }

    private static final class BadWindow extends Exception {
        final Refusal refusal;

        BadWindow(int status, String code, String message) {
            super(code);
            this.refusal = new Refusal(status, code, message);
        }
    }

    /**
     * The stored text from {@code offset}: at most {@code limit} bytes ending on a line terminator, or the rest of the
     * chunk. A start offset other than 0 must follow a line terminator (an offset comes from the previous page).
     */
    private Window readWindow(ChunkRef chunk, long offset, int limit) throws IOException, BadWindow {
        try (InputStream in = new BufferedInputStream(
                store.retrieve(new ArtefactRef(chunk.artefactRef()), chunk.wrappedDataKey(), true), 64 * 1024)) {
            if (offset > 0) {
                try {
                    in.skipNBytes(offset - 1);
                } catch (EOFException e) {
                    throw new BadWindow(416, "OFFSET_BEYOND_CHUNK", "The offset is beyond the end of the chunk.");
                }
                int previous = in.read();
                if (previous < 0) throw new BadWindow(416, "OFFSET_BEYOND_CHUNK", "The offset is beyond the end of the chunk.");
                if (previous != '\n') {
                    throw new BadWindow(400, "OFFSET_NOT_ON_LINE_BOUNDARY",
                            "The offset must be a next_offset returned by an earlier page.");
                }
            }
            byte[] window = in.readNBytes(limit);
            int lookahead = in.read();
            if (lookahead < 0) return new Window(window, true);
            int lf = lastLineFeed(window);
            if (lf >= 0) return new Window(Arrays.copyOf(window, lf + 1), false);
            // The first line is longer than the window: look for its end up to the hard maximum.
            ByteArrayOutputStream line = new ByteArrayOutputStream(MAX_PAGE_BYTES);
            line.write(window);
            line.write(lookahead);
            int room = MAX_PAGE_BYTES - line.size(); // negative when the window already was the hard maximum
            if (lookahead == '\n') {
                if (room >= 0) return new Window(line.toByteArray(), false);
                throw tooLong();
            }
            if (room < 0) throw tooLong();
            byte[] more = room > 0 ? in.readNBytes(room) : new byte[0];
            for (byte b : more) {
                line.write(b);
                if (b == '\n') return new Window(line.toByteArray(), false);
            }
            // No terminator within the hard maximum: either the chunk ended inside this last line, or the line is too long.
            if (more.length < room || (room == 0 && in.read() < 0)) return new Window(line.toByteArray(), true);
            throw tooLong();
        }
    }

    private static BadWindow tooLong() {
        return new BadWindow(422, "SYSTEM_LOG_LINE_TOO_LONG", "A line is longer than the hard page maximum and cannot be paged.");
    }

    private static int lastLineFeed(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') return i;
        }
        return -1;
    }

    private static long countLines(byte[] bytes) {
        if (bytes.length == 0) return 0;
        long lines = 0;
        for (byte b : bytes) {
            if (b == '\n') lines++;
        }
        return bytes[bytes.length - 1] == '\n' ? lines : lines + 1;
    }

    /** UTF-8 when the page is valid UTF-8, otherwise ISO-8859-1 (one byte, one character), re-encoded as UTF-8. */
    static byte[] toUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return bytes;
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- gaps

    public Read gaps(String deviceId, int limit) {
        Optional<String> device = Optional.ofNullable(deviceId).filter(id -> !id.isBlank());
        if (device.isPresent()) {
            var gate = deviceGate(device.get(), body -> {
                body.put("truncated", false);
                body.put("gaps", List.of());
            });
            if (gate.isPresent()) return gate.get();
        }
        int bounded = Math.max(1, Math.min(limit, 1000));
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (var row : repository.gaps(device, bounded)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gap_id", row.gapId());
            m.put("device_id", row.deviceId());
            m.put("source_key", row.sourceKey());
            m.put("job_id", row.jobId());
            m.put("kind", row.kind());
            m.put("known_lost_bytes", row.knownLostBytes());
            m.put("pending_bytes", row.pendingBytes());
            m.put("extent_unknown", row.extentUnknown());
            m.put("window_start_epoch", row.windowStartEpoch());
            m.put("window_end_epoch", row.windowEndEpoch());
            m.put("recorded_at", iso(row.createdAt()));
            gaps.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", device.orElse(null));
        body.put("truncated", gaps.size() >= bounded);
        body.put("gaps", gaps);
        return Read.ok(body);
    }

    // ---------------------------------------------------------------- crash and dump files

    public Read crashFiles(String deviceId, int limit, Instant now) {
        Optional<String> device = Optional.ofNullable(deviceId).filter(id -> !id.isBlank());
        if (device.isPresent()) {
            var gate = deviceGate(device.get(), body -> {
                Map<String, Integer> none = new LinkedHashMap<>();
                for (String state : List.of("NEW", "PRESENT", "PRESENT_BEFORE_MONITORING", "REMOVED")) none.put(state, 0);
                body.put("new_window_days", NEW_CRASH_WINDOW_DAYS);
                body.put("counts", none);
                body.put("truncated", false);
                body.put("files", List.of());
                body.put("alerts", List.of());
            });
            if (gate.isPresent()) return gate.get();
        }
        int bounded = Math.max(1, Math.min(limit, 1000));
        Instant newSince = now.minus(Duration.ofDays(NEW_CRASH_WINDOW_DAYS));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String state : List.of("NEW", "PRESENT", "PRESENT_BEFORE_MONITORING", "REMOVED")) counts.put(state, 0);
        List<Map<String, Object>> files = new ArrayList<>();
        for (var row : repository.crashEntries(device, bounded)) {
            String state = row.removedAt() != null ? "REMOVED"
                    : row.baseline() ? "PRESENT_BEFORE_MONITORING"
                    : row.firstSeenAt() != null && row.firstSeenAt().isAfter(newSince) ? "NEW" : "PRESENT";
            counts.merge(state, 1, Integer::sum);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entry_id", row.entryId());
            m.put("device_id", row.deviceId());
            m.put("directory", row.directory());
            m.put("process", row.process());
            m.put("size", row.size());
            m.put("mtime_epoch", row.mtime());
            m.put("state", state);
            m.put("first_seen_at", iso(row.firstSeenAt()));
            m.put("removed_at", iso(row.removedAt()));
            files.add(m);
        }
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (var row : repository.crashAlerts(device, 50)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("alert_id", row.alertId());
            m.put("device_id", row.deviceId());
            m.put("process", row.process());
            m.put("file_count", row.fileCount());
            m.put("newest_mtime_epoch", row.newestMtime());
            m.put("job_id", row.jobId());
            m.put("recorded_at", iso(row.createdAt()));
            m.put("notified_at", iso(row.notifiedAt()));
            alerts.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", device.orElse(null));
        body.put("new_window_days", NEW_CRASH_WINDOW_DAYS);
        body.put("counts", counts);
        body.put("truncated", files.size() >= bounded);
        body.put("files", files);
        body.put("alerts", alerts);
        return Read.ok(body);
    }

    // ---------------------------------------------------------------- HCP

    /**
     * The HCP archives collected from one device: metadata only (name time, size, device mtime, integrity status).
     * TODO(system-log phase B read): the contract also names a masked member listing of each archive. That needs a tar
     * reader over the decrypted archive and masked member names (the clear name exists only inside the encrypted
     * artefact); neither exists in the service yet, so the listing is reported as {@code NOT_AVAILABLE}.
     */
    public Read hcp(String deviceId) {
        var gate = deviceGate(deviceId, body -> {
            body.put("member_listing", "NOT_AVAILABLE");
            body.put("files", List.of());
        });
        if (gate.isPresent()) return gate.get();
        List<Map<String, Object>> files = new ArrayList<>();
        for (var row : repository.hcpFiles(deviceId, 50)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file_id", row.fileId());
            m.put("name_time", row.nameTime());
            m.put("name_time_basis", "DEVICE_LOCAL");
            m.put("size", row.size());
            m.put("mtime_epoch", row.mtime());
            m.put("status", row.status());
            m.put("flags", row.flags());
            m.put("collected_at", iso(row.collectedAt()));
            m.put("expires_at", iso(row.expiresAt()));
            m.put("pruned", row.prunedAt() != null);
            m.put("stored_bytes", row.ciphertextBytes());
            files.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", deviceId);
        body.put("member_listing", "NOT_AVAILABLE");
        body.put("files", files);
        return Read.ok(body);
    }

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }
}
