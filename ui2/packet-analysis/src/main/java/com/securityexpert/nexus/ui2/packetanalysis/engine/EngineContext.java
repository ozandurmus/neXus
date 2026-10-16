package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * State passed between stages: what the reference {@code report.analyze} keeps in local variables. Every
 * field is written by exactly one stage (see {@link StageGraph}).
 */
public final class EngineContext {
    public final Db db;
    public Topology topology;
    public Map<String, Topology.Point> points;
    /** Optional parity database alias; null outside parity runs. */
    public final String parity;

    public List<String> natPoints = new ArrayList<>();
    public List<Map<String, Object>> suggestions = new ArrayList<>();
    public Map<String, ClockModel> models = new LinkedHashMap<>();
    public boolean byteActive;
    public List<Row> coverage = new ArrayList<>();
    public Double start;
    public Double end;
    public Double commonStart;
    public Double commonEnd;
    public Map<String, Map<String, Object>> inventories = new LinkedHashMap<>();
    public Map<String, String> captureNames = new LinkedHashMap<>();
    public boolean hasDeviceProofs;
    public List<Map<String, Object>> segments = new ArrayList<>();
    public List<Map<String, Object>> findings = new ArrayList<>();
    public List<Map<String, Object>> quality = new ArrayList<>();
    public List<Map<String, Object>> applicationStatus = new ArrayList<>();
    /** Report sections (and detector outputs other detectors read) produced by ported detector stages, by report key. */
    public final Map<String, Object> sections = new LinkedHashMap<>();
    /** Path detector outputs (integrity, fragments, proxies, TLS). */
    public final PathState path = new PathState();
    /** Report keys whose producing stage is not ported: key to the unknown reason (empty: every stage is ported). */
    public final Map<String, String> unknownSections = new LinkedHashMap<>();
    /** Stage-level engine notes (report {@code engine_notes}). */
    public final List<String> engineNotes = new ArrayList<>();
    public final List<String> stageLog = new ArrayList<>();
    /** Time and event detector lane state ({@link TimeEvents#of}). */
    public TimeEvents timeEvents;

    public EngineContext(Db db, Topology topology, String parity) {
        this.db = db;
        this.topology = topology;
        this.points = topology.pointMap();
        this.parity = parity;
    }

    /** Parity runs: materialize the named tables (those that exist) as {@code parity."<point>__<table>"}. */
    public void snapshot(String point, List<String> tables) {
        if (parity == null) {
            return;
        }
        for (String table : tables) {
            long n = db.count("SELECT count(*) FROM information_schema.tables WHERE table_name=? AND table_catalog<>?",
                    table, parity);
            if (n == 0) {
                continue;
            }
            db.exec("CREATE OR REPLACE TABLE " + parity + ".\"" + point + "__" + table + "\" AS SELECT * FROM \"" + table + "\"");
        }
    }
}
