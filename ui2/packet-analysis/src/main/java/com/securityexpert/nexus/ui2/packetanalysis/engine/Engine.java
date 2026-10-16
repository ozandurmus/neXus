package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.store.MemoryBudget;
import com.securityexpert.nexus.ui2.packetanalysis.store.MemoryPlan;
import com.securityexpert.nexus.ui2.packetanalysis.store.SpillGuard;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;

/** Runs the core {@link StageGraph} on a reference-ingested project database and returns the report tree. */
public final class Engine {
    public static final String VERSION = "nexus-packet-analysis-core 0.1 (reference 0.3.10)";

    /** Tables materialized into the parity database after the stage with the same snapshot name. */
    public static final Map<String, List<String>> SNAPSHOTS = new LinkedHashMap<>();

    static {
        SNAPSHOTS.put("selection", List.of("obs", "analysis_scope", "fragment_groups", "fragment_members", "wire_observations"));
        SNAPSHOTS.put("prepare", List.of("obs", "tuple_lookup", "index_scope", "analysis_scope", "sequence_models",
                "calibration", "nat_candidates", "nat_point_tuples", "span_duplicates", "client_tuples"));
        SNAPSHOTS.put("align", List.of("obs", "clock_values"));
        SNAPSHOTS.put("matching", List.of("obs", "session_map"));
        SNAPSHOTS.put("bytes", List.of("obs", "byte_flows", "byte_obs", "byte_links", "byte_missing", "byte_recoveries",
                "observation_matches"));
        SNAPSHOTS.put("flow_filters", List.of("flow_filters", "observation_matches", "delivery_rejections", "delivery_candidates"));
        SNAPSHOTS.put("core", List.of("obs", "events", "flow_summary", "index_scope", "analysis_scope"));
        SNAPSHOTS.put("det_a", List.of("device_proofs", "events", "flow_summary", "segment_buckets", "bucket_obs",
                "session_syns", "session_replies", "session_acks", "session_lifecycle", "session_points", "session_buckets",
                "lifecycle_window", "handshake_stops", "burst_clocks", "burst_wire", "microburst_windows", "burst_baseline",
                "burst_candidates", "microbursts", "security_sessions", "security_cohorts", "security_points",
                "security_fanout", "pressure_baseline", "pressure_spikes", "host_edges", "host_transit", "host_path_series",
                "host_bursts", "cycle_points", "cycle_windows", "cycle_series", "cycle_flow_ports", "cycle_periods",
                "cycle_edges", "cycle_pairs", "cycle_top", "cycle_top_points", "cycle_degradations", "delivery_positions",
                "delivery_events", "delivery_series", "adjacent_match_rates", "copy_trains", "aggregate_boundaries",
                "aggregate_cycles", "aggregate_added", "aggregate_copies", "aggregate_host_clocks", "aggregate_host_packets",
                "cycle_details", "cycle_flow_detail", "sess_obs", "sess_flows"));
    }

    /** DuckDB memory limit in MiB for every connection when set (the end-to-end entry sets it from its budget). */
    public static final String MEMORY_LIMIT_PROPERTY = MemoryPlan.MEMORY_LIMIT_PROPERTY;

    /**
     * The report, the full ordered finding list ({@code export_findings}; the report keeps the first 200) and the
     * stages that ran, in order ({@code (stub)} marks a detector not in this engine version).
     */
    public record Result(Map<String, Object> report, List<Map<String, Object>> findings, List<String> stageLog) {
    }

    private Engine() {
    }

    /** DuckDB threads: {@code -Dpacketanalysis.duckdb.threads} (parity runs use 1), else the reference default. */
    static int threads() {
        String configured = System.getProperty("packetanalysis.duckdb.threads");
        if (configured != null && !configured.isBlank()) {
            return Integer.parseInt(configured.strip());
        }
        return Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    public static Connection open(Path database) throws SQLException {
        return open(database, threads());
    }

    public static Connection open(Path database, int threads) throws SQLException {
        Properties props = new Properties();
        props.setProperty("threads", Integer.toString(threads));
        configureMemory(props, database);
        props.setProperty("preserve_insertion_order", "false");
        return SpillGuard.track(DriverManager.getConnection("jdbc:duckdb:" + database.toAbsolutePath(), props));
    }

    public static Connection openReadOnly(Path database) throws SQLException {
        return openReadOnly(database, threads());
    }

    public static Connection openReadOnly(Path database, int threads) throws SQLException {
        Properties props = new Properties();
        props.setProperty("duckdb.read_only", "true");
        props.setProperty("threads", Integer.toString(threads));
        configureMemory(props, database);
        props.setProperty("preserve_insertion_order", "false");
        return SpillGuard.track(DriverManager.getConnection("jdbc:duckdb:" + database.toAbsolutePath(), props));
    }

    /**
     * The memory limit and the spill location of every engine connection: the same values as the case's other
     * connections ({@link MemoryPlan}). The limit is never left to DuckDB's own default (80% of the machine's memory,
     * which in a container is the host's), and a query that exceeds it spills to the case's {@code .spill} directory
     * on the analysis volume up to the bounded spill size.
     */
    private static void configureMemory(Properties props, Path database) {
        int dflt = MemoryBudget.memoryLimitMib(null, MemoryPlan.containerBytes());
        props.setProperty("memory_limit", MemoryPlan.memoryLimitMib(null, dflt) + "MiB");
        MemoryPlan.applySpill(props, MemoryPlan.spillDirectory(database.toAbsolutePath().getParent()));
    }

    public static Map<String, Object> analyze(Db db, Topology topology, Path parityDb) {
        return run(db, topology, parityDb, null).report();
    }

    /** As {@link #analyze(Db, Topology, Path)}; {@code afterStage} (may be null) sees the context after each stage. */
    static Map<String, Object> analyze(Db db, Topology topology, Path parityDb,
            BiConsumer<StageGraph.Stage, EngineContext> afterStage) {
        return run(db, topology, parityDb, afterStage).report();
    }

    /** The report, the full finding list and the stage log (the end-to-end entry writes all three). */
    public static Result run(Db db, Topology topology, Path parityDb) {
        return run(db, topology, parityDb, null);
    }

    static Result run(Db db, Topology topology, Path parityDb, BiConsumer<StageGraph.Stage, EngineContext> afterStage) {
        if (topology.forward.size() < 2) {
            List<String> drops = new ArrayList<>();
            for (Topology.Point p : topology.points) {
                if (p.vendor().equals("paloalto") && "drop".equals(p.vendorStage())) {
                    drops.add(p.id());
                }
            }
            if (drops.isEmpty()) {
                throw new IllegalArgumentException("Connect at least two capture points in a forward path");
            }
            if (topology.forward.isEmpty()) {
                topology = topology.withForward(List.of(drops.get(0)));
            }
        }
        String parity = null;
        if (parityDb != null) {
            db.exec("ATTACH '" + parityDb.toAbsolutePath().toString().replace("'", "''") + "' AS parity");
            parity = "parity";
        }
        EngineContext ctx = new EngineContext(db, topology, parity);
        db.exec(Sql.get("report", "preserve_order"));
        for (StageGraph.Stage stage : StageGraph.core().stages()) {
            stage.body().accept(ctx);
            ctx.stageLog.add(stage.name());
            if (stage.snapshot() != null) {
                ctx.snapshot(stage.snapshot(), SNAPSHOTS.get(stage.snapshot()));
            }
            if (afterStage != null) {
                afterStage.accept(stage, ctx);
            }
        }
        String generatedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Map<String, Object> report = CoreReport.assemble(ctx, VERSION, generatedAt);
        if (parity != null) {
            db.exec("DETACH parity");
        }
        return new Result(report, ctx.findings, List.copyOf(ctx.stageLog));
    }
}
