package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyConvert;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFormat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import com.securityexpert.nexus.ui2.packetanalysis.store.Db;
import com.securityexpert.nexus.ui2.packetanalysis.store.PacketColumns;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import com.securityexpert.nexus.ui2.packetanalysis.store.Sql;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Ingest one capture file into a project's {@code packets} table (the reference {@code ingest}).
 *
 * <p>Flow: identity check (a ready capture with the same identity is returned as cached) → container metadata →
 * {@code captures} row in state {@code ingesting} → tshark field export → rows parsed and committed in batches
 * through a CSV file and DuckDB {@code read_csv} (the reference load path, so type conversion is DuckDB's own) →
 * checkpoint after every batch → summary statistics and warnings into the capture inventory → state {@code ready}.
 * A failure marks the capture {@code error}, a cancellation {@code cancelled}; committed batches stay.
 */
public final class CaptureIngest {

    /** Progress callback; receives the reference progress keyword arguments. */
    @FunctionalInterface
    public interface Progress {
        Progress NONE = state -> { };

        void update(Map<String, Object> state);
    }

    private static final Pattern TAIL_ERROR = Pattern.compile(
            "cut short|short read|truncat(?:ed|ion).*(?:packet|record|block)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DAMAGED = Pattern.compile("damaged|corrupt|block length",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final String CANCELLED = "Ingest cancelled; committed frames can be resumed";
    private static final int CAPLEN_INDEX = PacketColumns.index("caplen");

    private static volatile Supplier<String> idSource = CaptureIngest::randomId;

    private CaptureIngest() {
    }

    /** {@code uuid.uuid4().hex}: the id of a new capture. */
    public static String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Replaces the capture id source (parity runs only: the reference golden tests patch {@code uuid4} with a
     * counter, {@code uuid.UUID(int=n).hex}); {@code null} restores random ids.
     */
    public static void setIdSource(Supplier<String> source) {
        idSource = source == null ? CaptureIngest::randomId : source;
    }

    /** The tshark arguments after the binary, before the per-field {@code -e} options. */
    static List<String> baseCommand(String binary, Path capture) {
        return new ArrayList<>(List.of(binary, "-n", "-l", "-r", capture.toString(),
                "-o", "tcp.relative_sequence_numbers:FALSE",
                "-o", "frame.generate_md5_hash:TRUE",
                "-o", "tcp.desegment_tcp_streams:FALSE",
                "-o", "ip.defragment:FALSE",
                "-o", "ipv6.defragment:FALSE",
                "-T", "fields",
                "-E", "separator=/t",
                "-E", "quote=d",
                "-E", "occurrence=a"));
    }

    /** Reference truthiness of a JSON-decoded value. */
    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Long l) {
            return l != 0;
        }
        if (v instanceof Double d) {
            return d != 0.0;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof List<?> l) {
            return !l.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    private static Map<String, Object> state(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** {@code Path(path).expanduser().resolve()}. */
    static Path resolve(Path path) {
        String s = path.toString();
        if (s.equals("~") || s.startsWith("~/")) {
            path = Path.of(System.getProperty("user.home") + s.substring(1));
        }
        Path abs = path.toAbsolutePath().normalize();
        try {
            return abs.toRealPath();
        } catch (IOException e) {
            return abs;
        }
    }

    private record Stat(long size, long mtimeNs) {
        static Stat of(Path p) throws IOException {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
            return new Stat(a.size(), a.lastModifiedTime().to(TimeUnit.NANOSECONDS));
        }
    }

    private static Map<String, Object> inventory(Object text) {
        Object v = PyJson.loads((String) text);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = v instanceof Map<?, ?> ? (Map<String, Object>) v : new LinkedHashMap<>();
        return m;
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? "" : e.getMessage();
    }

    /**
     * Ordered registration for a parallel batch: {@link #await} runs right before the capture id is chosen and the
     * capture row is first written, {@link #done} right after (and, idempotently, when the file needs no new row).
     * A batch chains the turns in input order, so capture ids from the id source and the {@code captures} storage
     * order (read without ORDER BY by the analysis) follow the input order instead of worker scheduling.
     */
    public interface Turn {
        Turn NONE = new Turn() {
            @Override
            public void await() {
            }

            @Override
            public void done() {
            }
        };

        void await();

        void done();
    }

    /** Ingest one capture; returns its capture id. */
    public static String ingest(ProjectStore project, Path capture, IngestOptions o, Cancellation cancel,
            Progress progress) {
        return ingest(project, capture, o, cancel, progress, Turn.NONE);
    }

    /** As {@link #ingest(ProjectStore, Path, IngestOptions, Cancellation, Progress)} with a registration turn. */
    public static String ingest(ProjectStore project, Path capture, IngestOptions o, Cancellation cancel,
            Progress progress, Turn turn) {
        cancel = cancel == null ? Cancellation.NONE : cancel;
        progress = progress == null ? Progress.NONE : progress;
        Path path = resolve(capture);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Capture file does not exist");
        }
        if (o.prefixBytes() < 8 || o.prefixBytes() > 4096) {
            throw new IllegalArgumentException("Payload prefix must be between 8 and 4096 bytes");
        }
        Stat stat;
        try {
            stat = Stat.of(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String readFilter = o.readFilter() == null || o.readFilter().isEmpty() ? null : o.readFilter();
        Object[] existing;
        boolean f5Trailer;
        Object sourceMetadata = o.sourceMetadata();
        String identity;
        try (Db db = project.connect()) {
            List<Object[]> rows = db.query(Sql.load("ingest/existing_capture"), path.toString());
            existing = rows.isEmpty() ? null : rows.get(0);
            f5Trailer = o.f5Trailer() != null ? o.f5Trailer()
                    : existing != null && truthy(inventory(existing[4]).get("f5_fields"));
            if (sourceMetadata == null && existing != null) {
                sourceMetadata = inventory(existing[4]).get("source_metadata");
            }
            List<Object> base = Arrays.asList(path.toString(), stat.size(), stat.mtimeNs(),
                    (long) TsharkFields.PARSER_VERSION, (long) o.prefixBytes(), o.checkpointUuid(), sourceMetadata,
                    f5Trailer);
            List<Object> full = new ArrayList<>(base);
            if (readFilter != null) {
                full.add(readFilter);
            }
            identity = PyJson.dumps(full);
            String unfilteredIdentity = PyJson.dumps(base);
            if (existing != null && "ready".equals(existing[2])) {
                Map<String, Object> existingInv = inventory(existing[4]);
                if (identity.equals(existing[1]) || (readFilter != null
                        && truthy(existingInv.get("prefilter_skipped")) && unfilteredIdentity.equals(existing[1]))) {
                    progress.update(state("state", "cached", "frames", existing[3]));
                    return (String) existing[0];
                }
            }
        }
        String binary = TsharkRunner.find(o.tshark());
        progress.update(state("state", "scanning metadata", "frames", 0L));
        Map<String, Object> info;
        try {
            info = CaptureMetadata.read(path, cancel);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        info.put("source_metadata", sourceMetadata);
        info.put("vendor_fields_version", 1L);
        info.put("f5_fields", f5Trailer);
        if (Long.valueOf(0L).equals(info.get("frame_limit"))) {
            throw new IllegalArgumentException("Capture contains zero usable frames");
        }
        Instant now = Instant.now();
        double timestampUpper = (now.getEpochSecond() + now.getNano() / 1e9) + 86400;
        turn.await();
        String cid = existing != null ? (String) existing[0] : idSource.get();
        long checkpoint = existing != null && identity.equals(existing[1]) && !"stale".equals(existing[2])
                ? ((Number) existing[3]).longValue() : 0;
        try (Db db = project.connect()) {
            if (checkpoint == 0) {
                db.execute(Sql.load("ingest/delete_packets"), cid);
                db.execute(Sql.load("ingest/delete_excluded"), cid);
            }
            db.execute(Sql.load("ingest/upsert_capture"), cid, path.toString(), path.getFileName().toString(),
                    identity, "ingesting", checkpoint, PyJson.dumps(info), null);
            ProjectStore.set(db, "report", null);
        } finally {
            turn.done();
        }
        List<String> cmd = baseCommand(binary, path);
        List<String> activeFields = new ArrayList<>(TsharkFields.BASE_FIELDS);
        if (f5Trailer) {
            cmd.addAll(List.of("--enable-protocol", "f5ethtrailer"));
            activeFields.addAll(TsharkFields.F5_FIELDS);
            activeFields.addAll(TsharkFields.HTTP_FIELDS);
        }
        if ("snoop".equals(info.get("format"))) {
            activeFields.addAll(TsharkFields.CHECKPOINT_FIELDS);
            cmd.addAll(List.of("-o", "eth.interpret_as_fw1_monitor:TRUE", "-o", "fw1.iflist_with_chain:TRUE",
                    "-o", "fw1.with_uuid:" + (o.checkpointUuid() ? "TRUE" : "FALSE")));
            info.put("checkpoint_uuid", o.checkpointUuid());
        }
        if (info.get("frame_limit") != null) {
            cmd.addAll(List.of("-c", info.get("frame_limit").toString()));
        }
        if (readFilter != null) {
            cmd.addAll(List.of("-Y", readFilter));
        }
        for (String field : activeFields) {
            cmd.addAll(List.of("-e", field));
        }
        Run run = new Run(project, cid, stat, o, cancel, progress, checkpoint, path);
        String skipWarning;
        Path temp;
        try {
            temp = Files.createTempDirectory(project.path(), "ingest-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            run.batchPath = temp.resolve("batch.csv");
            skipWarning = run.execute(cmd, activeFields, info, timestampUpper, readFilter);
            if (skipWarning == null) {
                return cid;
            }
        } finally {
            deleteTree(temp);
        }
        return ingest(project, capture, o.withPrefilter(null, run.totalRecords, skipWarning), cancel, progress);
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // best effort, like TemporaryDirectory cleanup
                }
            });
        } catch (IOException e) {
            // best effort
        }
    }

    /** The mutable state of one tshark pass. */
    private static final class Run {
        final ProjectStore project;
        final String cid;
        final Stat stat;
        final IngestOptions o;
        final Cancellation cancel;
        final Progress progress;
        final long checkpoint;
        final Path path;
        Path batchPath;
        final List<Object[]> batch = new ArrayList<>();
        final List<Object[]> excluded = new ArrayList<>();
        long last;
        long seen;
        Long totalRecords;

        Run(ProjectStore project, String cid, Stat stat, IngestOptions o, Cancellation cancel, Progress progress,
                long checkpoint, Path path) {
            this.project = project;
            this.cid = cid;
            this.stat = stat;
            this.o = o;
            this.cancel = cancel;
            this.progress = progress;
            this.checkpoint = checkpoint;
            this.last = checkpoint;
            this.path = path;
        }

        void flush() {
            if (batch.isEmpty() && excluded.isEmpty()) {
                return;
            }
            try (Writer w = new BufferedWriter(Files.newBufferedWriter(batchPath, StandardCharsets.UTF_8))) {
                PyCsv.writeRow(w, PacketColumns.NAMES.toArray(), ',');
                for (Object[] row : batch) {
                    PyCsv.writeRow(w, row, ',');
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            try (Db db = project.connect(true)) {
                db.begin();
                try {
                    if (!batch.isEmpty()) {
                        db.execute(Sql.load("ingest/load_batch").replace("{columns}",
                                PacketColumns.columnsStructLiteral()), batchPath.toString(), "");
                    }
                    if (!excluded.isEmpty()) {
                        db.executeMany(Sql.load("ingest/insert_excluded"), excluded);
                    }
                    db.execute(Sql.load("ingest/update_checkpoint"), last, cid);
                    db.commit();
                } catch (RuntimeException e) {
                    db.rollback();
                    throw e;
                }
            }
            batch.clear();
            excluded.clear();
            progress.update(state("state", "ingesting", "frames", last, "bytes_total", stat.size(),
                    "bytes_observed", seen));
        }

        /** Runs tshark and finishes the capture; returns the pre-filter skip warning when a retry is needed. */
        String execute(List<String> cmd, List<String> activeFields, Map<String, Object> info, double timestampUpper,
                String readFilter) {
            int prefix = o.prefixBytes();
            try (TsharkRunner proc = TsharkRunner.start(cmd, cancel, o.timeout())) {
                try {
                    PyCsv.CsvReader reader = new PyCsv.CsvReader(proc.stdout(), '\t');
                    List<String> values;
                    while ((values = reader.next()) != null) {
                        if (cancel.isSet()) {
                            throw new IngestCancelledException(CANCELLED);
                        }
                        if (values.isEmpty()) {
                            continue;
                        }
                        long frame = PacketParser.number(values.get(0));
                        if (frame <= checkpoint) {
                            continue;
                        }
                        last = frame;
                        Double observedTs;
                        try {
                            observedTs = values.size() > 1 ? PyConvert.parseFloat(values.get(1)) : null;
                        } catch (IllegalArgumentException e) {
                            observedTs = null;
                        }
                        String reason = TimestampRules.reason(observedTs, timestampUpper);
                        if (reason != null) {
                            excluded.add(new Object[] {cid, frame, reason,
                                observedTs != null && Double.isFinite(observedTs) ? observedTs : null});
                            if (excluded.size() >= o.batchSize()) {
                                flush();
                            }
                            continue;
                        }
                        Object[] packet = PacketParser.parse(values, cid, prefix, activeFields);
                        seen += (Long) packet[CAPLEN_INDEX];
                        batch.add(packet);
                        if (batch.size() >= o.batchSize()) {
                            flush();
                        }
                    }
                    int returnCode = proc.waitFor();
                    if (cancel.isSet()) {
                        throw new IngestCancelledException(CANCELLED);
                    }
                    if (proc.timedOut()) {
                        throw new IllegalArgumentException("tshark timed out after " + o.timeout());
                    }
                    String stderr = proc.stderrHead(8000);
                    boolean tailError = TAIL_ERROR.matcher(stderr).find()
                            || (truthy(info.get("damaged_tail")) && DAMAGED.matcher(stderr).find());
                    if (returnCode != 0 && !(last > 0 && tailError)) {
                        throw new IllegalArgumentException("tshark failed: " + stderr);
                    }
                    flush();
                    Stat now = Stat.of(path);
                    if (now.size() != stat.size() || now.mtimeNs() != stat.mtimeNs()) {
                        throw new IllegalArgumentException(
                                "Capture changed during ingestion; attach it again to rebuild");
                    }
                    return finish(info, tailError, readFilter);
                } catch (RuntimeException | IOException e) {
                    try (Db db = project.connect()) {
                        db.execute(Sql.load("ingest/mark_failed"),
                                e instanceof IngestCancelledException ? "cancelled" : "error", message(e), cid);
                    }
                    if (e instanceof IOException io) {
                        throw new UncheckedIOException(io);
                    }
                    throw (RuntimeException) e;
                }
            }
        }

        private String finish(Map<String, Object> info, boolean tailError, String readFilter) {
            try (Db db = project.connect()) {
                Object[] r = db.query(Sql.load("ingest/summary"), cid).get(0);
                long count = (Long) r[2];
                if (count == 0 && readFilter == null) {
                    throw new IllegalArgumentException("Capture contains zero usable frames");
                }
                List<Object> warnings = new ArrayList<>();
                if (tailError || truthy(info.get("damaged_tail"))) {
                    warnings.add("File ends mid-packet; last partial record ignored; " + count + " packets usable");
                }
                info.put("warnings", warnings);
                if (truthy(info.get("zero_tail_bytes"))) {
                    double mb = ((Long) info.get("zero_tail_bytes")).doubleValue() / 1_000_000;
                    warnings.add("Zero-filled tail ignored: " + info.get("zero_tail_records") + " records / "
                            + PyFormat.format(mb, ".2f")
                            + " MB (likely preallocated or copied while still being written)");
                }
                Map<String, Object> reasons = new LinkedHashMap<>();
                for (Object[] row : db.query(Sql.load("ingest/excluded_reasons"), cid)) {
                    reasons.put((String) row[0], row[1]);
                }
                info.put("timestamp_excluded_counts", reasons);
                long excludedTotal = reasons.values().stream().mapToLong(v -> (Long) v).sum();
                if (!reasons.isEmpty()) {
                    StringBuilder b = new StringBuilder();
                    reasons.keySet().stream().sorted().forEach(k -> {
                        if (b.length() > 0) {
                            b.append(", ");
                        }
                        b.append(k).append(": ").append(reasons.get(k));
                    });
                    warnings.add("Excluded " + excludedTotal + " packets with invalid capture timestamps: " + b);
                }
                String skipWarning = null;
                if (readFilter != null) {
                    long total = o.prefilterTotal() != null ? o.prefilterTotal()
                            : truthy(info.get("complete_records")) ? (Long) info.get("complete_records") : 0L;
                    if (info.get("frame_limit") != null) {
                        total = Math.min(total, (Long) info.get("frame_limit"));
                    }
                    totalRecords = total;
                    long retained = count + excludedTotal;
                    long prefilterExcluded = Math.max(0, total - retained);
                    if (total > 0 && retained * 10 < total) {
                        skipWarning = "Ingest pre-filter skipped: reference IP pairs matched " + retained + " of "
                                + total + " frames (<10%); kept all frames";
                    } else if (prefilterExcluded > 0) {
                        info.put("prefilter_total", total);
                        info.put("prefilter_retained", retained);
                        info.put("prefilter_excluded", prefilterExcluded);
                        warnings.add("Ingest pre-filter kept " + retained + " of " + total
                                + " frames matching IP pairs in other captures (" + prefilterExcluded + " excluded)");
                    } else {
                        info.put("prefilter_excluded", 0L);
                    }
                } else if (o.prefilterWarning() != null && !o.prefilterWarning().isEmpty()) {
                    long total = o.prefilterTotal() != null ? o.prefilterTotal()
                            : truthy(info.get("complete_records")) ? (Long) info.get("complete_records")
                            : count + excludedTotal;
                    info.put("prefilter_total", total);
                    info.put("prefilter_retained", count + excludedTotal);
                    info.put("prefilter_excluded", 0L);
                    info.put("prefilter_skipped", true);
                    warnings.add(o.prefilterWarning());
                }
                if (skipWarning != null) {
                    return skipWarning;
                }
                List<Object> stages = new ArrayList<>();
                for (Object[] row : db.query(Sql.load("ingest/vendor_stages"), cid)) {
                    stages.add(state("stage", row[0], "count", row[1]));
                }
                info.put("vendor_stages", stages);
                Map<String, Object> tunnels = new LinkedHashMap<>();
                for (Object[] row : db.query(Sql.load("ingest/tunnel_counts"), cid)) {
                    tunnels.put(row[0] == null ? "null" : (String) row[0], row[1]);
                }
                info.put("tunnel_counts", tunnels);
                if (sourceMetadataTruthy(info.get("source_metadata"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sm = (Map<String, Object>) info.get("source_metadata");
                    warnings.add("Fortinet text: " + sm.getOrDefault("skipped_lines", 0L) + " skipped lines; "
                            + sm.getOrDefault("skipped_packets", 0L) + " incomplete/unsupported packets skipped");
                    if (truthy(sm.get("relative_timestamps"))) {
                        warnings.add("Low clock confidence: relative text timestamps use a user-supplied start time");
                    }
                }
                info.put("timestamps_validated", true);
                info.put("records_read", last);
                info.put("start", r[0]);
                info.put("end", r[1]);
                info.put("packet_count", r[2]);
                info.put("duration", r[0] != null ? (Object) ((Double) r[1] - (Double) r[0]) : (Object) 0L);
                info.put("truncated", r[3]);
                info.put("possible_offload", r[4]);
                info.put("unsupported", r[5]);
                info.put("observed_max_caplen", r[6]);
                info.put("truncated_caplen_min", r[7]);
                info.put("truncated_caplen_max", r[8]);
                db.execute(Sql.load("ingest/mark_ready"), PyJson.dumps(info), cid);
                progress.update(state("state", "ready", "frames", last, "usable_packets", r[2], "warnings",
                        warnings));
                return null;
            }
        }

        private static boolean sourceMetadataTruthy(Object v) {
            return v instanceof Map<?, ?> && truthy(v);
        }
    }
}
