package com.securityexpert.nexus.ui2.packetanalysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.CaseRunner;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Evidence;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.ReportModel;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.BatchIngest;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.CaptureIngest;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.IngestOptions;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkRunner;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import com.securityexpert.nexus.ui2.packetanalysis.store.AnalysisFailure;
import com.securityexpert.nexus.ui2.packetanalysis.store.MemoryBudget;
import com.securityexpert.nexus.ui2.packetanalysis.store.MemoryPlan;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import com.securityexpert.nexus.ui2.packetanalysis.store.SpillGuard;
import com.securityexpert.nexus.ui2.packetanalysis.topology.AnalysisWindowGuard;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Assemble;
import com.securityexpert.nexus.ui2.packetanalysis.topology.CaseSetup;
import com.securityexpert.nexus.ui2.packetanalysis.topology.ProjectDb;
import com.securityexpert.nexus.ui2.packetanalysis.topology.PyJson;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Topology;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The whole Packet Analysis pipeline in one process, no network and no UI: capture files -> ingest (tshark) ->
 * topology (automatic case assembly, or a given analysis topology) -> engine -> report and FindingsExport v11.
 *
 * <pre>
 * PacketAnalysisMain --out DIR [--case-dir DIR] [--topology FILE [--confirm-suggested-nat]]
 *                    [--memory-mb N] [--duckdb-memory-mb N] [--cpus N] [--threads N]
 *                    [--max-packets-per-capture N] [--max-window-seconds N] [--max-temp-mb N]
 *                    [--prefilter | --no-prefilter] [--parallel-ingest] [--ingest-workers N] [--tshark PATH]
 *                    [--dump-tables DIR] [--deterministic-capture-ids]
 *                    INPUT...
 * </pre>
 *
 * <p>Without {@code --topology} the input is one capture folder and the run is the reference {@code create_case}
 * (default points for every capture, pre-filter on, sequential ingest, automatic assembly; every assembled path
 * group is analysed and the first ready group is the primary report). With {@code --topology} the inputs are
 * capture files (or folders) ingested in the given order with the pre-filter off (the reference {@code demo} /
 * {@code analyze} path); the topology names captures by file name or capture id. {@code --confirm-suggested-nat}
 * analyses once, confirms every NAT suggestion as a mapping and analyses again ({@code --confirm-demo-nat}).
 *
 * <p>Budget: {@code --memory-mb} is the memory the run may use (default: physical memory); the DuckDB memory limit
 * is a quarter of it, between 1 and 8 GiB, unless {@code --duckdb-memory-mb} sets it. {@code --cpus} is the CPU
 * budget (default: the processors the JVM sees); DuckDB threads default to {@code min(8, max(1, cpus - 1))} unless
 * {@code --threads} sets them. The ingest worker budget uses the same CPU and memory budget. {@code --memory-mb} is
 * also the container limit of the memory plan ({@link MemoryPlan}): the DuckDB limit is lowered to
 * {@code memory - JVM heap - reserve}. DuckDB spills to {@code CASE_DIR/.spill} up to {@code --max-temp-mb}
 * (default 40 GiB, less than the free space of the volume).
 *
 * <p>Analysis window guard ({@link AnalysisWindowGuard}): when the analysis range has more than
 * {@code --max-packets-per-capture} packets in one capture (default 150,000; 0 turns the packet budget off) or is
 * longer than {@code --max-window-seconds} (default off), the analysis is bounded to a deterministic window, recorded
 * in {@code run.json} as {@code analysis_window} and in the report as {@code analysis_window} (only when bounded). A
 * topology that has its own {@code start}/{@code end} is never changed.
 *
 * <p>Output directory: {@code report.json} (the stored report, {@code json.dumps(indent=2)} text),
 * {@code findings-export.json} (FindingsExport schema 11: findings, report snapshot with transport profiles, DNS
 * page and field differences, time-series buckets, flow filters), {@code capture-map.json} (capture id to file
 * name) and {@code run.json} (budget, timings, stage log). {@code --dump-tables} writes the final {@code obs},
 * {@code observation_matches}, {@code events}, {@code flow_summary} and {@code clock_values} tables as Parquet for
 * parity runs; {@code --deterministic-capture-ids} numbers captures 1, 2, ... as the reference golden tests do.
 *
 * <p>Exit status: 0 analysed, 1 failed, 2 usage error, 3 the assembly was ambiguous (no analysis; the case context
 * and suggestion are in {@code run.json}), 4 the run stopped at a resource limit ({@code ANALYSIS_MEMORY_LIMIT} or
 * {@code ANALYSIS_WINDOW_REQUIRED}; {@code run.json} has {@code failure.reason} and a message).
 */
public final class PacketAnalysisMain {

    static final List<String> L2_TABLES = List.of("obs", "observation_matches", "events", "flow_summary", "clock_values");
    private static final long MIB = 1024L * 1024L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PacketAnalysisMain() {
    }

    /** Parsed command line. */
    public record Options(Path out, Path caseDir, Path topology, boolean confirmSuggestedNat, Long memoryMb,
            Integer duckdbMemoryMb, Integer cpus, Integer threads, Boolean prefilter, boolean parallelIngest,
            Integer ingestWorkers, String tshark, Path dumpTables, boolean deterministicCaptureIds, Long maxPacketsPerCapture,
            Long maxWindowSeconds, Long maxTempMb, List<Path> inputs) {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Runs the pipeline; returns the exit status. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println("usage: PacketAnalysisMain --out DIR [--case-dir DIR] [--topology FILE [--confirm-suggested-nat]] "
                    + "[--memory-mb N] [--duckdb-memory-mb N] [--cpus N] [--threads N] "
                    + "[--max-packets-per-capture N] [--max-window-seconds N] [--max-temp-mb N] [--prefilter|--no-prefilter] "
                    + "[--parallel-ingest] [--ingest-workers N] [--tshark PATH] [--dump-tables DIR] "
                    + "[--deterministic-capture-ids] INPUT...");
            return 2;
        }
        try {
            Map<String, Object> summary = execute(options);
            out.println(PyJson.dumps(summary.get("result")));
            return Boolean.TRUE.equals(summary.get("analysed")) ? 0 : 3;
        } catch (RuntimeException e) {
            String reason = AnalysisFailure.reasonOf(e);
            if (AnalysisFailure.ENGINE_FAILED.equals(reason)) {
                err.println("packet analysis failed: " + e);
                return 1;
            }
            err.println("packet analysis stopped: " + reason + " - " + AnalysisFailure.messageFor(reason));
            return 4;
        }
    }

    static Options parse(String[] args) {
        Path out = null;
        Path caseDir = null;
        Path topology = null;
        boolean confirm = false;
        Long memoryMb = null;
        Integer duckdbMemoryMb = null;
        Integer cpus = null;
        Integer threads = null;
        Boolean prefilter = null;
        boolean parallel = false;
        Integer workers = null;
        String tshark = null;
        Path dump = null;
        boolean deterministic = false;
        Long maxPackets = null;
        Long maxSeconds = null;
        Long maxTemp = null;
        List<Path> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--") && Set.of("--out", "--case-dir", "--topology", "--memory-mb", "--duckdb-memory-mb",
                    "--cpus", "--threads", "--ingest-workers", "--tshark", "--dump-tables", "--max-packets-per-capture",
                    "--max-window-seconds", "--max-temp-mb").contains(a)
                    && i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + a);
            }
            switch (a) {
                case "--out" -> out = Path.of(args[++i]);
                case "--case-dir" -> caseDir = Path.of(args[++i]);
                case "--topology" -> topology = Path.of(args[++i]);
                case "--confirm-suggested-nat" -> confirm = true;
                case "--memory-mb" -> memoryMb = positive(a, Long.parseLong(args[++i]));
                case "--duckdb-memory-mb" -> duckdbMemoryMb = (int) (long) positive(a, Long.parseLong(args[++i]));
                case "--cpus" -> cpus = (int) (long) positive(a, Long.parseLong(args[++i]));
                case "--threads" -> threads = (int) (long) positive(a, Long.parseLong(args[++i]));
                case "--prefilter" -> prefilter = Boolean.TRUE;
                case "--no-prefilter" -> prefilter = Boolean.FALSE;
                case "--parallel-ingest" -> parallel = true;
                case "--ingest-workers" -> workers = (int) (long) positive(a, Long.parseLong(args[++i]));
                case "--tshark" -> tshark = args[++i];
                case "--dump-tables" -> dump = Path.of(args[++i]);
                case "--deterministic-capture-ids" -> deterministic = true;
                case "--max-packets-per-capture" -> maxPackets = nonNegative(a, Long.parseLong(args[++i]));
                case "--max-window-seconds" -> maxSeconds = nonNegative(a, Long.parseLong(args[++i]));
                case "--max-temp-mb" -> maxTemp = positive(a, Long.parseLong(args[++i]));
                default -> {
                    if (a.startsWith("--")) {
                        throw new IllegalArgumentException("unknown option " + a);
                    }
                    inputs.add(Path.of(a));
                }
            }
        }
        if (out == null) {
            throw new IllegalArgumentException("--out is required");
        }
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("no capture input given");
        }
        if (topology == null && (inputs.size() != 1 || !Files.isDirectory(inputs.get(0)))) {
            throw new IllegalArgumentException("without --topology the input must be exactly one capture folder");
        }
        if (confirm && topology == null) {
            throw new IllegalArgumentException("--confirm-suggested-nat needs --topology");
        }
        return new Options(out, caseDir == null ? out.resolve("case") : caseDir, topology, confirm, memoryMb,
                duckdbMemoryMb, cpus, threads, prefilter, parallel, workers, tshark, dump, deterministic, maxPackets,
                maxSeconds, maxTemp, inputs);
    }

    private static long nonNegative(String option, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(option + " must not be negative");
        }
        return value;
    }

    private static long positive(String option, long value) {
        if (value < 1) {
            throw new IllegalArgumentException(option + " must be positive");
        }
        return value;
    }

    /** The pipeline; returns {@code result} (printed) and {@code analysed}. */
    public static Map<String, Object> execute(Options o) {
        long started = System.nanoTime();
        long totalMemory = o.memoryMb() != null ? o.memoryMb() * MIB : MemoryBudget.systemTotalMemoryBytes();
        int cpus = o.cpus() != null ? o.cpus() : Runtime.getRuntime().availableProcessors();
        int threads = o.threads() != null ? o.threads() : MemoryBudget.defaultThreads(cpus);
        // The run's memory plan: --memory-mb is the container limit the DuckDB limit must fit in with the JVM heap.
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(ProjectStore.THREADS_PROPERTY, Integer.toString(threads));
        if (o.memoryMb() != null) {
            properties.put(MemoryPlan.CONTAINER_PROPERTY, Long.toString(o.memoryMb()));
        }
        Map<String, String> previous = new LinkedHashMap<>();
        for (String key : List.of(ProjectStore.THREADS_PROPERTY, Engine.MEMORY_LIMIT_PROPERTY, MemoryPlan.CONTAINER_PROPERTY,
                MemoryPlan.MAX_TEMP_PROPERTY, AnalysisWindowGuard.MAX_PACKETS_PROPERTY,
                AnalysisWindowGuard.MAX_SECONDS_PROPERTY)) {
            previous.put(key, System.getProperty(key));
        }
        System.setProperty(ProjectStore.THREADS_PROPERTY, Integer.toString(threads));
        if (o.memoryMb() != null) {
            System.setProperty(MemoryPlan.CONTAINER_PROPERTY, Long.toString(o.memoryMb()));
        }
        // After the container limit is set: the DuckDB limit is lowered to what fits (memory plan), then recorded.
        int duckdbMemory = MemoryPlan.memoryLimitMib(o.duckdbMemoryMb(),
                MemoryBudget.memoryLimitMib(o.duckdbMemoryMb(), totalMemory));
        System.setProperty(Engine.MEMORY_LIMIT_PROPERTY, Integer.toString(duckdbMemory));
        if (o.maxTempMb() != null) {
            System.setProperty(MemoryPlan.MAX_TEMP_PROPERTY, Long.toString(o.maxTempMb()));
        }
        if (o.maxPacketsPerCapture() != null) {
            System.setProperty(AnalysisWindowGuard.MAX_PACKETS_PROPERTY, Long.toString(o.maxPacketsPerCapture()));
        }
        if (o.maxWindowSeconds() != null) {
            System.setProperty(AnalysisWindowGuard.MAX_SECONDS_PROPERTY, Long.toString(o.maxWindowSeconds()));
        }
        boolean caseMode = o.topology() == null;
        boolean prefilter = o.prefilter() != null ? o.prefilter() : caseMode;
        if (o.deterministicCaptureIds()) {
            AtomicLong counter = new AtomicLong();
            CaptureIngest.setIdSource(() -> String.format("%032x", counter.incrementAndGet()));
        }
        try {
            return SpillGuard.run(o.caseDir(),
                    () -> pipeline(o, started, totalMemory, cpus, threads, duckdbMemory, caseMode, prefilter));
        } catch (RuntimeException failure) {
            String reason = AnalysisFailure.reasonOf(failure);
            if (!AnalysisFailure.ENGINE_FAILED.equals(reason)) {
                recordFailure(o, reason, totalMemory, cpus, threads, duckdbMemory, started);
            }
            throw failure;
        } finally {
            CaptureIngest.setIdSource(null);
            for (Map.Entry<String, String> e : previous.entrySet()) {
                restore(e.getKey(), e.getValue());
            }
        }
    }

    /** A run that stopped at a resource limit still leaves {@code run.json}: the reason, what to do, the budget. */
    private static void recordFailure(Options o, String reason, long totalMemory, int cpus, int threads, int duckdbMemory,
            long started) {
        try {
            mkdirs(o.out());
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("analysed", false);
            run.put("failure", dict("reason", reason, "message", AnalysisFailure.messageFor(reason)));
            run.put("budget", budget(totalMemory, duckdbMemory, cpus, threads));
            run.put("seconds", dict("total", PyNum.of(seconds(started))));
            write(o.out().resolve("run.json"), PyJson.dumps(run, 2) + "\n");
        } catch (RuntimeException ignored) {
            // The exit status and the message carry the reason when the record cannot be written.
        }
    }

    private static Map<String, Object> budget(long totalMemory, int duckdbMemory, int cpus, int threads) {
        return dict("memory_mb", PyNum.of(totalMemory / MIB), "duckdb_memory_limit_mb", PyNum.of(duckdbMemory),
                "cpus", PyNum.of(cpus), "duckdb_threads", PyNum.of(threads),
                "jvm_heap_mb", PyNum.of(Runtime.getRuntime().maxMemory() / MIB),
                "memory_plan", MemoryPlan.describe());
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pipeline(Options o, long started, long totalMemory, int cpus, int threads,
            int duckdbMemory, boolean caseMode, boolean prefilter) {
        mkdirs(o.out());
        mkdirs(o.caseDir());
        if (o.dumpTables() != null) {
            mkdirs(o.dumpTables());
        }
        Path folder = caseMode ? o.inputs().get(0) : null;
        List<Path> files = new ArrayList<>();
        if (caseMode) {
            files.addAll(CaseSetup.capturePaths(folder));
        } else {
            for (Path p : o.inputs()) {
                if (Files.isDirectory(p)) {
                    files.addAll(BatchIngest.capturePaths(p));
                } else {
                    files.add(p);
                }
            }
        }

        // 1. Ingest.
        long t0 = System.nanoTime();
        ProjectStore store = ProjectStore.open(o.caseDir(), new ProjectStore.Settings(o.duckdbMemoryMb(), totalMemory, threads));
        IngestOptions ingestOptions = IngestOptions.defaults().withTshark(TsharkRunner.find(o.tshark()));
        BatchIngest.Settings settings = new BatchIngest.Settings(ingestOptions, o.parallelIngest(), o.ingestWorkers(),
                prefilter, cpus, totalMemory);
        Map<String, Object> latest = new LinkedHashMap<>();
        String warning = null;
        try {
            BatchIngest.ingestMany(store, files, settings, null, null, state -> {
                synchronized (latest) {
                    latest.putAll(state);
                }
            });
        } catch (IllegalArgumentException e) {
            if (!caseMode) {
                throw e;
            }
            warning = e.getMessage(); // create_case records the ingest ValueError and continues
        }
        double ingestSeconds = seconds(t0);

        // 2. Topology / case setup and 3. engine (the analyzer runs inside the assembly in case mode).
        ProjectDb project = new ProjectDb(o.caseDir());
        Map<String, String> captureNames = new LinkedHashMap<>();
        try (ProjectDb.Db db = project.connect()) {
            for (Map<String, Object> r : db.rows("SELECT id,name FROM captures ORDER BY name,id", List.of())) {
                captureNames.put((String) r.get("id"), (String) r.get("name"));
            }
        }
        Analyzer analyzer = new Analyzer(o.caseDir().resolve("project.duckdb"), o.dumpTables());
        long t1 = System.nanoTime();
        Map<String, Object> caseContext = null;
        if (caseMode) {
            List<Map<String, Object>> fileStates = latest.get("files") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();
            caseContext = CaseSetup.createCase(project, folder, new CaseSetup.IngestOutcome(warning, fileStates), prefilter,
                    true, analyzer);
        } else {
            Map<String, Object> topology = bind((Map<String, Object>) PyJson.loads(read(o.topology())), captureNames);
            Map<String, Object> first = analyzer.analyze(project, topology);
            if (o.confirmSuggestedNat()) {
                List<Object> mappings = new ArrayList<>();
                for (Object s : (List<Object>) first.get("nat_suggestions")) {
                    Map<String, Object> suggestion = (Map<String, Object>) s;
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (String k : List.of("point_a", "point_b", "tuple_a", "tuple_b")) {
                        m.put(k, suggestion.get(k));
                    }
                    mappings.add(m);
                }
                topology.put("nat_mappings", mappings);
                analyzer.analyze(project, topology);
            }
        }
        double setupSeconds = Math.round((seconds(t1) - analyzer.seconds) * 1e3) / 1e3;

        // 4. Report and FindingsExport.
        long t2 = System.nanoTime();
        Map<String, Object> report;
        Map<String, Object> export = null;
        Object suggested;
        try (ProjectDb.Db db = project.connect()) {
            report = (Map<String, Object>) db.get("report", null);
            suggested = db.get("suggested_topology", null);
        }
        if (report != null) {
            export = findingsExport(o.caseDir().resolve("project.duckdb"));
        }
        Map<String, Object> captureMap = new LinkedHashMap<>(captureNames);
        write(o.out().resolve("capture-map.json"), PyJson.dumps(captureMap, 2) + "\n");
        if (report != null) {
            write(o.out().resolve("report.json"), PyJson.dumps(report, 2) + "\n");
            write(o.out().resolve("findings-export.json"), PyJsonWriter.dumps(export, true) + "\n");
        }
        double exportSeconds = seconds(t2);

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("mode", caseMode ? "case" : "topology");
        run.put("inputs", files.stream().map(p -> p.getFileName().toString()).toList());
        run.put("case_dir", o.caseDir().toAbsolutePath().toString());
        run.put("prefilter", prefilter);
        run.put("budget", budget(totalMemory, duckdbMemory, cpus, threads));
        run.put("analysis_window", analyzer.window == null ? null : analyzer.window.toMap());
        run.put("analyses", PyNum.of(analyzer.runs));
        run.put("seconds", dict("ingest", PyNum.of(ingestSeconds), "case_setup", PyNum.of(setupSeconds),
                "engine", PyNum.of(analyzer.seconds), "export", PyNum.of(exportSeconds), "total", PyNum.of(seconds(started))));
        run.put("stage_log", analyzer.stageLog);
        run.put("ingest_warning", warning);
        run.put("analysed", report != null);
        if (report != null) {
            run.put("unknown_sections", report.get("unknown_sections"));
            run.put("engine_notes", report.get("engine_notes"));
        } else {
            run.put("case_context", caseContext);
            run.put("suggested_topology", suggested);
        }
        write(o.out().resolve("run.json"), PyJson.dumps(run, 2) + "\n");
        Map<String, Object> result = dict("out", o.out().toAbsolutePath().toString(), "analysed", report != null,
                "verdict", report == null ? null : report.get("verdict"), "seconds", PyNum.of(seconds(started)));
        return dict("result", result, "analysed", report != null);
    }

    /** {@code bind_capture_ids}: point capture ids and clock override keys given as file names -> capture ids. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> bind(Map<String, Object> topology, Map<String, String> captureNames) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : captureNames.entrySet()) {
            byName.putIfAbsent(e.getValue(), e.getKey());
        }
        Map<String, Object> t = new LinkedHashMap<>(topology);
        List<Object> points = new ArrayList<>();
        for (Object p : (List<Object>) t.getOrDefault("points", List.of())) {
            Map<String, Object> point = new LinkedHashMap<>((Map<String, Object>) p);
            Object cid = point.get("capture_id");
            if (cid instanceof String s && byName.containsKey(s)) {
                point.put("capture_id", byName.get(s));
            }
            points.add(point);
        }
        t.put("points", points);
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) t.getOrDefault("clock_overrides", Map.of())).entrySet()) {
            overrides.put(byName.getOrDefault(e.getKey(), e.getKey()), e.getValue());
        }
        t.put("clock_overrides", overrides);
        return t;
    }

    /**
     * {@code analysis.report.analyze(project, topology)} with the core engine: validate the topology, run the stage
     * graph on its own connection, then store {@code topology}, {@code export_findings} and {@code report}.
     */
    static final class Analyzer implements Assemble.Analyzer {
        private final Path database;
        private final Path dumpTables;
        int runs;
        double seconds;
        AnalysisWindowGuard.Decision window;
        List<Object> stageLog = new ArrayList<>();

        Analyzer(Path database, Path dumpTables) {
            this.database = database;
            this.dumpTables = dumpTables;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> analyze(ProjectDb project, Object topologyValue) {
            long t = System.nanoTime();
            Topology validated = Topology.validate(topologyValue);
            AnalysisWindowGuard.Decision decision = AnalysisWindowGuard.apply(project, validated,
                    AnalysisWindowGuard.Budget.fromSystemProperties());
            Map<String, Object> dumped = validated.dump();
            com.securityexpert.nexus.ui2.packetanalysis.engine.Topology topology = engineTopology(
                    modelView((Map<String, Object>) Evidence.loads(PyJson.dumps(dumped))));
            try (ProjectDb.Db db = project.connect()) {
                db.set("report", null);
            }
            Engine.Result result;
            try (Db db = new Db(Engine.open(database))) {
                result = Engine.run(db, topology, null);
                if (dumpTables != null) {
                    dump(db, dumpTables);
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // Strict typed binding of the report (ReportModel) gives the reference text; the topology stage works
            // on the reference value model, so the stored values are that text decoded.
            Map<String, Object> report = (Map<String, Object>) PyJson.loads(ReportModel.toJson(result.report()));
            if (decision.guarded()) {
                report.put("analysis_window", decision.toMap());
            }
            window = decision;
            Object findings = PyJson.loads(PyJsonWriter.compact(result.findings()));
            try (ProjectDb.Db db = project.connect()) {
                db.set("topology", dumped);
                db.set("export_findings", findings);
                db.set("report", report);
            }
            runs++;
            seconds += seconds(t);
            stageLog = new ArrayList<>(result.stageLog());
            return report;
        }
    }

    /**
     * The final analysis tables as Parquet (persistent tables and the TEMP {@code clock_values}) plus
     * {@code schema.json} with the table column types (Parquet has no HUGEINT, so the file alone loses them).
     */
    static void dump(Db db, Path dir) {
        Map<String, Object> schema = new LinkedHashMap<>();
        for (String table : L2_TABLES) {
            long n = db.count("SELECT count(*) FROM (SELECT table_name FROM duckdb_tables() UNION ALL "
                    + "SELECT view_name FROM duckdb_views()) WHERE table_name=?", table);
            Path target = dir.resolve(table + ".parquet");
            try {
                Files.deleteIfExists(target);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (n > 0) {
                List<Object> columns = new ArrayList<>();
                for (Object[] c : db.tuples("DESCRIBE \"" + table + "\"")) {
                    columns.add(List.of(c[0], c[1]));
                }
                schema.put(table, columns);
                db.exec("COPY (SELECT * FROM \"" + table + "\") TO '" + target.toAbsolutePath().toString().replace("'", "''")
                        + "' (FORMAT parquet)");
            }
        }
        write(dir.resolve("schema.json"), PyJson.dumps(schema, 2) + "\n");
    }

    /**
     * {@code export.export_data(project)}: FindingsExport schema 11 through the product path
     * ({@link CaseQueries#exportData}, the call the neXus service stores and serves), so the corpus verifies what
     * ships.
     */
    static Map<String, Object> findingsExport(Path database) {
        try (Db db = new Db(Engine.open(database))) {
            return CaseRunner.prepareAndExport(db);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static com.securityexpert.nexus.ui2.packetanalysis.engine.Topology engineTopology(Object raw) {
        try {
            return com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.parse(
                    MAPPER.readTree(PyJsonWriter.compact(raw)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Float-typed fields of the reference topology models (topology/models.py) with an int default. */
    static final Set<String> TOPOLOGY_FLOATS = Set.of("cycle_stall_ms", "rtp_jitter_ms", "udp_timeout_ms",
            "security_scan_window_seconds", "security_syn_rate", "session_timeout_seconds", "microburst_window_ms",
            "dns_timeout_ms", "bucket_seconds", "duplicate_us", "stall_ms", "match_window_ms");

    /**
     * The analysed topology model as the engine reads it. The engine takes an analysis input the way the reference
     * model validates one: a given number of a float field becomes a float, an absent one keeps the field's int
     * default. The reference analyses the model object itself, where an int in a float field can only be an
     * unvalidated default (pydantic does not validate defaults), so those entries are left out and the engine applies
     * the same default; every other value is passed as dumped. (The export path reads the stored topology through
     * the model validator, as the reference does, and uses the dump unchanged.)
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> modelView(Map<String, Object> dumped) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : dumped.entrySet()) {
            if (!(TOPOLOGY_FLOATS.contains(e.getKey()) && e.getValue() instanceof Long)) {
                out.put(e.getKey(), e.getValue());
            }
        }
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) dumped.getOrDefault("clock_overrides", Map.of())).entrySet()) {
            Map<String, Object> o = new LinkedHashMap<>((Map<String, Object>) e.getValue());
            if (o.get("drift_ppm") instanceof Long) {
                o.remove("drift_ppm");
            }
            overrides.put(e.getKey(), o);
        }
        if (dumped.containsKey("clock_overrides")) {
            out.put("clock_overrides", overrides);
        }
        return out;
    }

    private static Map<String, Object> dict(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static double seconds(long since) {
        return Math.round((System.nanoTime() - since) / 1e6) / 1e3;
    }

    private static void mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path p, String text) {
        try {
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
