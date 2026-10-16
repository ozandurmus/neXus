package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Dns;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Evidence;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Onsets;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Sql;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Transport;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloatRepr;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Port of the reference {@code export.py}: versioned {@link FindingsExport} (v11) metadata export and
 * standalone, network-free offline HTML report.
 */
public final class Export {
    public static final String SCHEMA_NAME = "packetbreaker.findings";
    public static final long SCHEMA_VERSION = 11L;

    public static final Set<String> FINDING_TYPES = Set.of(
            "hold_release",
            "cycle_wait",
            "host_receive_delay",
            "host_receive_path_loss",
            "host_transmit_path_loss",
            "host_transmit_delay",
            "sip_rewritten",
            "sdp_media_mismatch",
            "one_way_audio",
            "rtp_loss",
            "rtp_jitter",
            "session_table_pressure",
            "scan_like_fanout",
            "syn_flood_like",
            "fragmentation",
            "recovered_loss",
            "impactful_loss",
            "unrecovered_loss",
            "handshake_blocked",
            "capture_miss",
            "unknown",
            "confirmed_device_drop",
            "reset_origin",
            "proxy_reset_originated",
            "proxy_reset_propagated",
            "tls_interception",
            "icmp_origin",
            "payload_modified",
            "downstream_packet",
            "mss_clamping",
            "mtu_black_hole",
            "ttl_path",
            "dscp_remark",
            "option_stripping",
            "capture_duplicate",
            "duplication",
            "asymmetric_routing",
            "reset_storm",
            "late_close_resets",
            "stream_reconnect",
            "sender_rate_drop",
            "receiver_rate_drop");

    public static final String HEATMAP_SCRIPT = "\n"
            + "'use strict';\n"
            + "const data=JSON.parse(document.getElementById('report-data').textContent);\n"
            + "const series=data.timeseries, segments=data.report.segments, canvas=document.getElementById('map'), ctx=canvas.getContext('2d');\n"
            + "const metric=document.getElementById('metric'), tip=document.getElementById('tip'), note=document.getElementById('metric-note');\n"
            + "const lookup=new Map(series.items.map(r=>[r.segment+':'+r.bucket,r]));\n"
            + "const count=series.bucket_count||0, left=270, cell=Math.max(0.02,Math.min(18,3000/Math.max(1,count))), height=28;\n"
            + "canvas.width=Math.ceil(left+cell*count+10); canvas.height=segments.length*height+40;\n"
            + "for(const [key,value] of Object.entries(series.metrics||{})){const option=document.createElement('option');option.value=key;option.textContent=value.label+' ('+value.unit+')';metric.append(option);}\n"
            + "metric.value='loss_percent';\n"
            + "function draw(){\n"
            + " ctx.clearRect(0,0,canvas.width,canvas.height);ctx.font='12px system-ui';\n"
            + " const key=metric.value, descriptor=series.metrics[key];note.textContent=descriptor?descriptor.tooltip:'';\n"
            + " let max=0;for(const r of series.items)if(typeof r[key]==='number')max=Math.max(max,r[key]);\n"
            + " segments.forEach((s,i)=>{ctx.fillStyle='#173d40';ctx.fillText(s.label+' \u00b7 '+s.direction,0,i*height+19,260);\n"
            + " for(let j=0;j<count;j++){const r=lookup.get(s.id+':'+j), value=r&&r[key];\n"
            + " ctx.fillStyle=!r||r.coverage==='not capturing'?'#b9c4c3':r.coverage!=='capturing'||value==null?'#e7cc96':`hsl(${170-160*Math.min(1,value/(max||1))} 55% 62%)`;\n"
            + " ctx.fillRect(left+j*cell,i*height+3,Math.max(cell-0.4,0.02),height-5);}}\n"
            + " );\n"
            + " ctx.fillStyle='#172a2d';for(const onset of (data.report.onsets?.items||[])){if(onset.metric!==key)continue;const i=segments.findIndex(s=>s.id===onset.segment);if(i<0)continue;const j=Math.floor((onset.time-series.start)/series.bucket_seconds);if(j<0||j>=count)continue;const x=left+j*cell,y=i*height;ctx.beginPath();ctx.moveTo(x,y+1);ctx.lineTo(x+6,y+9);ctx.lineTo(x-6,y+9);ctx.closePath();ctx.fill();}\n"
            + " if(count){ctx.fillText(new Date(series.start*1000).toISOString(),left,canvas.height-8);ctx.fillText(new Date((series.start+count*series.bucket_seconds)*1000).toISOString(),Math.max(left,canvas.width-195),canvas.height-8);}\n"
            + "}\n"
            + "metric.addEventListener('change',draw);\n"
            + "canvas.addEventListener('mousemove',event=>{const box=canvas.getBoundingClientRect(), x=(event.clientX-box.left)*canvas.width/box.width, y=(event.clientY-box.top)*canvas.height/box.height;\n"
            + "const i=Math.floor(y/height), j=Math.floor((x-left)/cell);if(i<0||i>=segments.length||j<0||j>=count)return;\n"
            + "const r=lookup.get(segments[i].id+':'+j), descriptor=series.metrics[metric.value];tip.textContent=segments[i].label+' \u00b7 '+segments[i].direction+'\\n'+new Date((series.start+j*series.bucket_seconds)*1000).toISOString()+'\\n'+(!r||r.coverage==='not capturing'?'not capturing':r[metric.value]==null?'unknown':r[metric.value].toFixed(3)+' '+descriptor.unit)+'\\n'+(r?.coverage||'not capturing')+' \u00b7 '+(r?.reason||'')+'\\n'+(descriptor?.tooltip||'');});\n"
            + "draw();\n";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile String echartsCache;
    private static volatile String incidentScriptCache;

    private Export() {
    }

    /** Reference {@code Finding} validation and canonical field ordering. */
    public static final class Finding {
        private static final List<String> FIELD_ORDER = List.of(
                "id",
                "type",
                "tooltip",
                "device",
                "evidence_stage",
                "hop",
                "direction",
                "time_range",
                "metrics",
                "evidence_refs",
                "confidence",
                "severity",
                "summary",
                "cause");
        private static final Set<String> ALLOWED_KEYS = Set.copyOf(FIELD_ORDER);

        private Finding() {
        }

        public static Map<String, Object> fromEngineFinding(Map<String, Object> f) {
            Map<String, Object> candidate = new LinkedHashMap<>();
            for (String k : List.of("id", "type", "hop", "direction", "time_range", "metrics", "confidence",
                    "severity", "summary", "cause")) {
                if (!f.containsKey(k)) {
                    throw new IllegalArgumentException("Missing finding field: " + k);
                }
                candidate.put(k, f.get(k));
            }
            if (!f.containsKey("evidence") && !f.containsKey("evidence_refs")) {
                throw new IllegalArgumentException("Missing finding field: evidence");
            }
            candidate.put("evidence_refs", f.containsKey("evidence") ? f.get("evidence") : f.get("evidence_refs"));
            candidate.put("tooltip", f.get("tooltip"));
            candidate.put("device", f.get("device"));
            candidate.put("evidence_stage", f.get("evidence_stage"));
            return validate(candidate);
        }

        @SuppressWarnings("unchecked")
        public static Map<String, Object> validate(Map<String, Object> raw) {
            if (raw == null) {
                throw new IllegalArgumentException("Finding must be an object");
            }
            for (String k : raw.keySet()) {
                if (!ALLOWED_KEYS.contains(k)) {
                    throw new IllegalArgumentException("Extra inputs are not permitted: " + k);
                }
            }
            String id = requireString(raw, "id");
            String type = requireString(raw, "type");
            if (!FINDING_TYPES.contains(type)) {
                throw new IllegalArgumentException("Unsupported finding type: " + type);
            }
            String tooltip = nullableString(raw.get("tooltip"), "tooltip");
            String device = nullableString(raw.get("device"), "device");
            String evidenceStage = nullableString(raw.get("evidence_stage"), "evidence_stage");
            String hop = requireString(raw, "hop");
            String direction = requireString(raw, "direction");
            Object trObj = raw.get("time_range");
            if (!(trObj instanceof List<?> trList) || trList.size() != 2) {
                throw new IllegalArgumentException("time_range must be a 2-element list");
            }
            List<Double> timeRange = new ArrayList<>(2);
            for (Object elem : trList) {
                if (elem == null) {
                    timeRange.add(null);
                } else if (elem instanceof Number n && !(elem instanceof Boolean)) {
                    timeRange.add(n.doubleValue());
                } else {
                    throw new IllegalArgumentException("time_range elements must be float or null");
                }
            }
            if (!(raw.get("metrics") instanceof Map<?, ?> metrics)) {
                throw new IllegalArgumentException("metrics must be an object");
            }
            if (!(raw.get("evidence_refs") instanceof List<?> evidenceRefs)) {
                throw new IllegalArgumentException("evidence_refs must be a list");
            }
            for (Object ref : evidenceRefs) {
                if (!(ref instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("evidence_refs items must be objects");
                }
            }
            String confidence = requireString(raw, "confidence");
            if (!"supported".equals(confidence) && !"unknown".equals(confidence)) {
                throw new IllegalArgumentException("Unsupported confidence: " + confidence);
            }
            String severity = requireString(raw, "severity");
            String summary = requireString(raw, "summary");
            String cause = requireString(raw, "cause");

            if ("confirmed_device_drop".equals(type)
                    && (device == null || device.isEmpty() || evidenceStage == null || evidenceStage.isEmpty())) {
                throw new IllegalArgumentException("Confirmed device drops require device and evidence_stage");
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("type", type);
            out.put("tooltip", tooltip);
            out.put("device", device);
            out.put("evidence_stage", evidenceStage);
            out.put("hop", hop);
            out.put("direction", direction);
            out.put("time_range", timeRange);
            out.put("metrics", new LinkedHashMap<>((Map<String, Object>) metrics));
            out.put("evidence_refs", new ArrayList<>(evidenceRefs));
            out.put("confidence", confidence);
            out.put("severity", severity);
            out.put("summary", summary);
            out.put("cause", cause);
            return out;
        }
    }

    /** Reference {@code FindingsExport} v11 validation and JSON schema metadata. */
    public static final class FindingsExport {
        private static final Set<String> ALLOWED_KEYS = Set.of(
                "schema_name",
                "schema_version",
                "engine_version",
                "exported_at",
                "findings",
                "report",
                "timeseries",
                "flow_filters");

        private FindingsExport() {
        }

        @SuppressWarnings("unchecked")
        public static Map<String, Object> validate(Map<String, Object> raw) {
            if (raw == null) {
                throw new IllegalArgumentException("FindingsExport must be an object");
            }
            for (String k : raw.keySet()) {
                if (!ALLOWED_KEYS.contains(k)) {
                    throw new IllegalArgumentException("Extra inputs are not permitted: " + k);
                }
            }
            Object schemaName = raw.getOrDefault("schema_name", SCHEMA_NAME);
            if (!SCHEMA_NAME.equals(schemaName)) {
                throw new IllegalArgumentException("Invalid schema_name: " + schemaName);
            }
            Object sv = raw.getOrDefault("schema_version", SCHEMA_VERSION);
            if (sv instanceof Boolean || !(sv instanceof Number n) || n.longValue() != SCHEMA_VERSION) {
                throw new IllegalArgumentException("Invalid schema_version: " + sv);
            }
            String engineVersion = requireString(raw, "engine_version");
            String exportedAt = requireString(raw, "exported_at");
            if (!(raw.get("findings") instanceof List<?> rawFindings)) {
                throw new IllegalArgumentException("findings must be a list");
            }
            List<Map<String, Object>> findings = new ArrayList<>(rawFindings.size());
            for (Object f : rawFindings) {
                if (!(f instanceof Map<?, ?> fm)) {
                    throw new IllegalArgumentException("Each finding must be an object");
                }
                findings.add(Finding.validate((Map<String, Object>) fm));
            }
            if (!(raw.get("report") instanceof Map<?, ?> report)) {
                throw new IllegalArgumentException("report must be an object");
            }
            if (!(raw.get("timeseries") instanceof Map<?, ?> timeseries)) {
                throw new IllegalArgumentException("timeseries must be an object");
            }
            if (!(raw.get("flow_filters") instanceof List<?> flowFilters)) {
                throw new IllegalArgumentException("flow_filters must be a list");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("schema_name", SCHEMA_NAME);
            out.put("schema_version", SCHEMA_VERSION);
            out.put("engine_version", engineVersion);
            out.put("exported_at", exportedAt);
            out.put("findings", findings);
            out.put("report", new LinkedHashMap<>((Map<String, Object>) report));
            out.put("timeseries", new LinkedHashMap<>((Map<String, Object>) timeseries));
            out.put("flow_filters", new ArrayList<>(flowFilters));
            return out;
        }

        /** JSON schema metadata including the {@code confirmed_device_drop} provenance rule. */
        public static Map<String, Object> modelJsonSchema() {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("if", Map.of(
                    "properties", Map.of("type", Map.of("const", "confirmed_device_drop")),
                    "required", List.of("type")));
            rule.put("then", Map.of(
                    "required", List.of("device", "evidence_stage"),
                    "properties", Map.of(
                            "device", Map.of("type", "string", "minLength", 1L),
                            "evidence_stage", Map.of("type", "string", "minLength", 1L))));
            Map<String, Object> findingDef = new LinkedHashMap<>();
            findingDef.put("type", "object");
            findingDef.put("additionalProperties", false);
            findingDef.put("allOf", List.of(rule));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("title", "FindingsExport");
            schema.put("type", "object");
            schema.put("$defs", Map.of("Finding", findingDef));
            return schema;
        }
    }

    public static Map<String, Object> exportData(Db db) {
        return exportData(db, false);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> exportData(Db db, boolean findingsOnly) {
        Map<String, Object> report = (Map<String, Object>) getSetting(db, "report");
        if (!FieldDiff.truthy(report)) {
            throw new IllegalArgumentException("Run analysis before exporting");
        }
        List<Map<String, Object>> findings = (List<Map<String, Object>>) getSetting(db, "export_findings");
        if (findings == null) {
            long findingCount = ((Number) report.get("finding_count")).longValue();
            List<Map<String, Object>> reportFindings = (List<Map<String, Object>>) report.get("findings");
            if (findingCount > reportFindings.size()) {
                throw new IllegalArgumentException("Re-run analysis to export all findings from this older report");
            }
            findings = reportFindings;
        }
        List<Map<String, Object>> exportedFindings = new ArrayList<>(findings.size());
        for (Map<String, Object> f : findings) {
            exportedFindings.add(Finding.fromEngineFinding(f));
        }
        String exportedAt = OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'"));
        if (findingsOnly) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("schema_name", SCHEMA_NAME);
            out.put("schema_version", SCHEMA_VERSION);
            out.put("engine_version", String.valueOf(report.get("engine_version")));
            out.put("findings", exportedFindings);
            return out;
        }
        Object rawTopology = getSetting(db, "topology");
        Topology topology = Topology.parse(MAPPER.valueToTree(rawTopology));
        Map<String, Object> snapshot = new LinkedHashMap<>(report);
        snapshot.put("transport", Transport.exportProfiles(db, topology, report, 200));
        snapshot.put("dns", Dns.page(db, topology, report, null, null, null, null, 0L, 200L));
        snapshot.remove("findings");
        snapshot.put("field_diffs", FieldDiff.fieldDiffs(db, topology, null, 200));

        Map<String, Object> timeseries = new LinkedHashMap<>(
                (Map<String, Object>) report.getOrDefault("timeseries", Map.of()));
        timeseries.put("items", db.rows(Sql.get("export", "timeseries_items")));

        List<Db.Row> flowFilters = db.rows(Sql.get("export", "flow_filters"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_name", SCHEMA_NAME);
        out.put("schema_version", SCHEMA_VERSION);
        out.put("engine_version", String.valueOf(report.get("engine_version")));
        out.put("exported_at", exportedAt);
        out.put("findings", exportedFindings);
        out.put("report", snapshot);
        out.put("timeseries", timeseries);
        out.put("flow_filters", flowFilters);
        return out;
    }

    public static Map<String, Object> exportData(Path projectPath) {
        return exportData(projectPath, false);
    }

    public static Map<String, Object> exportData(Path projectPath, boolean findingsOnly) {
        try (Db db = openDb(projectPath)) {
            return exportData(db, findingsOnly);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> exportData(ProjectStore project) {
        return exportData(project.path(), false);
    }

    public static Map<String, Object> exportData(ProjectStore project, boolean findingsOnly) {
        return exportData(project.path(), findingsOnly);
    }

    @SuppressWarnings("unchecked")
    public static String htmlReport(Map<String, Object> data) {
        Map<String, Object> report = (Map<String, Object>) data.get("report");
        List<Map<String, Object>> findingList = (List<Map<String, Object>>) data.getOrDefault("findings", List.of());

        StringBuilder findingsSb = new StringBuilder();
        for (int i = 0; i < findingList.size(); i++) {
            Map<String, Object> f = findingList.get(i);
            findingsSb.append("<article id=\"finding-").append(i).append("\"><h3>")
                    .append(h(f.get("type"))).append(" \u00b7 ").append(h(f.get("hop"))).append("</h3><p title=\"")
                    .append(h(f.get("tooltip"))).append("\">").append(h(f.get("summary"))).append("</p><p>")
                    .append(h(orEmpty(f.get("device")))).append(" ").append(h(orEmpty(f.get("evidence_stage"))))
                    .append("</p><p>Confidence: ").append(h(f.get("confidence"))).append("; severity: ")
                    .append(h(f.get("severity"))).append("</p>")
                    .append(details(f.get("metrics"), "Metrics"))
                    .append("<ul>").append(refs((List<Map<String, Object>>) f.getOrDefault("evidence_refs", List.of())))
                    .append("</ul></article>");
        }
        String findings = findingsSb.toString();

        StringBuilder segmentsSb = new StringBuilder();
        for (Map<String, Object> s : (List<Map<String, Object>>) report.getOrDefault("segments", List.of())) {
            Object headlineOrReason = FieldDiff.truthy(s.get("headline")) ? s.get("headline") : s.get("reason");
            segmentsSb.append("<article><h3>").append(h(s.get("label"))).append(" \u00b7 ").append(h(s.get("direction")))
                    .append("</h3><p>").append(h(headlineOrReason)).append("</p>")
                    .append(details(s, "Segment metrics and uncertainty")).append("</article>");
        }
        String segments = segmentsSb.toString();

        Map<String, Object> onset = report.get("onsets") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        List<Map<String, Object>> onsetItems = onset.get("items") instanceof List<?> l
                ? (List<Map<String, Object>>) l
                : List.of();
        Map<String, Object> onsetDirections = onset.get("directions") instanceof Map<?, ?> d
                ? (Map<String, Object>) d
                : Map.of();
        StringBuilder onsetsSb = new StringBuilder();
        for (Map<String, Object> o : Onsets.orderedOnsets(onsetItems, onsetDirections)) {
            String cls = "capture_signal".equals(o.get("scope")) ? "symptom" : "primary";
            List<Map<String, Object>> ev = o.get("evidence") instanceof List<?> l
                    ? (List<Map<String, Object>>) l
                    : List.of();
            onsetsSb.append("<article class='").append(cls).append("'><h3>")
                    .append(h(o.get("segment"))).append(" \u00b7 ").append(h(o.get("metric")))
                    .append("</h3><p>").append(h(o.get("explanation"))).append("</p>")
                    .append(details(o.get("time_labels"), "Onset time"))
                    .append("<ul>").append(refs(ev)).append("</ul></article>");
        }
        String onsets = onsetsSb.toString();

        Map<String, Object> fieldDiffsMap = report.get("field_diffs") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        List<Map<String, Object>> diffItems = fieldDiffsMap.get("items") instanceof List<?> l
                ? (List<Map<String, Object>>) l
                : List.of();
        StringBuilder differencesSb = new StringBuilder();
        for (Map<String, Object> item : diffItems) {
            differencesSb.append("<article><h3>").append(h(item.get("location"))).append("</h3><p>")
                    .append(h(item.get("tooltip")))
                    .append("</p><table><thead><tr><th>Field</th><th>Before</th><th>After</th><th>Status</th></tr></thead><tbody>");
            Map<String, Object> fields = (Map<String, Object>) item.get("fields");
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                Map<String, Object> field = (Map<String, Object>) e.getValue();
                differencesSb.append("<tr><th>").append(h(e.getKey())).append("</th><td>")
                        .append(h(field.get("before"))).append("</td><td>")
                        .append(h(field.get("after"))).append("</td><td>")
                        .append(h(field.get("status"))).append("</td></tr>");
            }
            differencesSb.append("</tbody></table><ul>")
                    .append(refs((List<Map<String, Object>>) item.get("evidence")))
                    .append("</ul></article>");
        }
        String differences = differencesSb.toString();

        Map<String, Object> microburstsMap = report.get("microbursts") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        List<Map<String, Object>> burstItems = microburstsMap.get("items") instanceof List<?> l
                ? (List<Map<String, Object>>) l
                : List.of();
        StringBuilder burstCardsSb = new StringBuilder();
        for (Map<String, Object> b : burstItems) {
            burstCardsSb.append("<article><h3>").append(h(b.get("point"))).append(" \u00b7 ")
                    .append(h(b.get("peak_mbps"))).append(" Mbps</h3><p>Duration ")
                    .append(h(b.get("duration_ms"))).append(" ms; cause unknown.</p>")
                    .append(details(b.get("loss_associations"), "Nearby next-segment loss onset \u2014 corroboration only"))
                    .append("<ul>").append(refs((List<Map<String, Object>>) b.get("evidence")))
                    .append("</ul></article>");
        }
        String burstCards = burstCardsSb.toString();

        Map<String, Object> transportMap = report.get("transport") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        List<Map<String, Object>> transportItems = transportMap.get("items") instanceof List<?> l
                ? (List<Map<String, Object>>) l
                : List.of();
        StringBuilder transportSb = new StringBuilder();
        for (Map<String, Object> d : transportItems) {
            transportSb.append("<article><h3>").append(h(d.get("flow"))).append(" \u00b7 ")
                    .append(h(d.get("direction"))).append(" \u00b7 ").append(h(d.get("classification")))
                    .append("</h3><p title=\"").append(h(d.get("tooltip"))).append("\">")
                    .append(h(d.get("reason"))).append("</p>")
                    .append(details(d.get("metrics"), "Transport evidence metrics"))
                    .append("<ul>").append(refs((List<Map<String, Object>>) d.get("evidence")))
                    .append("</ul></article>");
        }

        Map<String, Object> dnsMap = report.get("dns") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        List<Map<String, Object>> dnsItems = dnsMap.get("items") instanceof List<?> l
                ? (List<Map<String, Object>>) l
                : List.of();
        StringBuilder dnsSb = new StringBuilder();
        for (Map<String, Object> d : dnsItems) {
            dnsSb.append("<article><h3>").append(h(d.get("question"))).append(" \u00b7 ")
                    .append(h(d.get("resolver"))).append(" \u00b7 ").append(h(d.get("status")))
                    .append("</h3><p title=\"").append(h(d.get("tooltip"))).append("\">")
                    .append(h(d.get("reason"))).append("</p><p>")
                    .append(h(d.get("location"))).append("; latency ").append(h(d.get("latency_ms")))
                    .append(" ms</p><ul>").append(refs((List<Map<String, Object>>) d.get("evidence")))
                    .append("</ul></article>");
        }

        String encoded = PyJsonWriter.compactStrict(data)
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026");

        boolean hasIncident = FieldDiff.truthy(report.get("incident"));
        String incidentLibrary = hasIncident ? loadEcharts().replace("</script", "<\\/script") : "";
        String incidentScript = hasIncident ? loadIncidentScript() : "";

        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; base-uri 'none'; form-action 'none'\">\n"
                + "<title>PacketBreaker offline report</title><style>\n"
                + "body{font:15px system-ui,sans-serif;color:#173d40;background:#f3f7f6;max-width:1200px;margin:auto;padding:24px}h1,h2,h3{line-height:1.3}article,section{background:white;padding:18px;margin:16px 0;border:1px solid #d7e3df;border-radius:8px}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f3f7f6;padding:12px}article.symptom{background:#f4f6f5;color:#667772;font-size:0.9em}summary,select{cursor:pointer}canvas{display:block}.scroll{overflow:auto}nav a{margin-right:18px;color:#176b68}small{color:#526b6a}li{margin:10px 0}#tip{white-space:pre-wrap;min-height:4em}\n"
                + "</style></head><body><h1>PacketBreaker \u00b7 offline report</h1>\n"
                + "<p>Engine " + h(data.get("engine_version")) + " \u00b7 findings schema " + data.get("schema_version")
                + " \u00b7 exported " + h(data.get("exported_at")) + "</p>\n"
                + "<p>This file contains capture metadata, addresses and evidence filters. It embeds no packet payloads. Treat it with the same care as an investigation summary.</p>\n"
                + "<nav><a href=\"#summary\">Summary</a><a href=\"#onset\">Onset</a><a href=\"#heatmap\">Heatmap</a><a href=\"#findings\">Findings</a><a href=\"#segments\">Segments</a><a href=\"#filters\">Flow filters</a></nav>\n"
                + "<section id=\"summary\"><h2>" + h(report.get("verdict")) + "</h2><p>" + h(onset.get("summary"))
                + "</p>" + details(report.get("window"), "Analysis window (Unix seconds)")
                + details(report.get("clocks"), "Clock uncertainty")
                + details(report.getOrDefault("limitations", List.of()), "Interpretation limits") + "</section>\n"
                + "<section id=\"incident\"><h2>Incident timeline and engine visualisations</h2><noscript>Enable JavaScript for the six interactive charts; engine data and evidence remain available below.</noscript></section>\n"
                + "<section id=\"transport\"><h2>Network or application?</h2>" + transportSb + "</section>\n"
                + "<section id=\"onset\"><h2>Onset</h2>"
                + (!onsets.isEmpty() ? onsets : "<p>No supported onset detected. See per-segment status.</p>")
                + details(onset.getOrDefault("segments", onset), "Per-segment onset status") + "</section>\n"
                + "<section id=\"heatmap\"><h2>Path \u00d7 time</h2><label>Metric <select id=\"metric\"></select></label><p id=\"metric-note\"></p><p>Grey = not capturing; amber = unknown/partial. Black markers = detected onsets. Hover for time, value and coverage.</p><div class=\"scroll\"><canvas id=\"map\" aria-label=\"Path by time heatmap\"></canvas></div><p id=\"tip\" role=\"status\"></p><noscript>Enable JavaScript for the offline heatmap; summary, findings and evidence remain readable.</noscript></section>\n"
                + "<section id=\"findings\"><h2>Findings (" + findingList.size() + ")</h2>"
                + (!findings.isEmpty() ? findings : "<p>No findings.</p>") + "</section>\n"
                + "<section id=\"segments\"><h2>Segments</h2>" + segments + "</section>\n"
                + "<section id=\"proxies\"><h2>Full-proxy requests and TCP legs</h2>"
                + details(report.getOrDefault("proxies", Map.of()), "Request pairings, dwell uncertainty, evidence and independent-leg classifications")
                + "</section>\n"
                + "<section id=\"tls\"><h2>TLS interception evidence</h2>"
                + details(report.getOrDefault("tls", List.of()), "Visible certificate chains and unknown encrypted handshakes")
                + details(report.getOrDefault("application_status", List.of()), "Application decoder status")
                + "</section>\n"
                + "<section id=\"cycles\"><h2>Opaque TCP cycles</h2>"
                + details(report.getOrDefault("cycles", Map.of()), "Per-second, flow/port, RX/TX components and top-cycle evidence")
                + details(report.getOrDefault("delivery", Map.of()), "Physical delay versus retry delivery")
                + "</section>\n"
                + "<section id=\"host-path\"><h2>Host receive/transmit path and local response</h2>"
                + details(report.getOrDefault("host_path", Map.of()), "Separate boundary latency, local service, RX/TX shares and event periods")
                + "</section>\n"
                + "<section id=\"sip\"><h2>SIP / SDP</h2>"
                + details(report.getOrDefault("sip", Map.of()), "Expected and undeclared rewriting, actual media tuples and one-way audio evidence")
                + "</section>\n"
                + "<section id=\"rtp\"><h2>RTP streams</h2>"
                + details(report.getOrDefault("rtp", Map.of()), "Gaps, RFC 3550 jitter, duplicates, reordering and one-direction observations")
                + "</section>\n"
                + "<section id=\"udp\"><h2>UDP transactions</h2>"
                + details(report.getOrDefault("udp", Map.of()), "Turnaround, no-response-expected, unanswered/unknown and frame filters")
                + "</section>\n"
                + "<section id=\"dns\"><h2>DNS turnaround and deadline evidence</h2>"
                + details(dnsMap.getOrDefault("summary", List.of()), "Per-resolver/point latency distribution")
                + dnsSb + "</section>\n"
                + "<section id=\"sessions\"><h2>TCP session lifecycle</h2>"
                + details(report.getOrDefault("security", Map.of()), "Heuristic thresholds and quality notes")
                + details(report.getOrDefault("sessions", Map.of()), "Per-point lifecycle, half-open buckets and failed-handshake frame filters")
                + "</section>\n"
                + "<section id=\"microbursts\"><h2>Capture microbursts</h2><p>" + h(microburstsMap.get("note")) + "</p>"
                + details(microburstsMap.getOrDefault("points", List.of()), "Peak rate, duration and frequency by capture point")
                + burstCards + "</section>\n"
                + "<section id=\"field-diffs\"><h2>Matched packet field differences</h2>"
                + h(fieldDiffsMap.get("note")) + differences + "</section>\n"
                + "<section id=\"filters\"><h2>Per-file flow filters</h2>"
                + details(data.get("flow_filters"), "Copy filters for original or re-saved files") + "</section>\n"
                + "<script id=\"report-data\" type=\"application/json\">" + encoded + "</script><script>"
                + HEATMAP_SCRIPT + "\n;" + incidentLibrary + "\n;" + incidentScript + "</script></body></html>";
    }

    public static String exportReport(Db db, String format) {
        Map<String, Object> data = exportData(db, false);
        if ("json".equals(format)) {
            return PyJsonWriter.dumps(data, true, false, false, false) + "\n";
        }
        if ("html".equals(format)) {
            return htmlReport(data);
        }
        throw new IllegalArgumentException("Export format must be html or json");
    }

    public static String exportReport(Path projectPath, String format) {
        try (Db db = openDb(projectPath)) {
            return exportReport(db, format);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String exportReport(ProjectStore project, String format) {
        return exportReport(project.path(), format);
    }

    private static String h(Object value) {
        String s;
        if (value == null) {
            s = "unknown";
        } else if (value instanceof Double d) {
            s = PyFloatRepr.repr(d);
        } else if (value instanceof Boolean b) {
            s = b ? "True" : "False";
        } else {
            s = String.valueOf(value);
        }
        return escapeHtml(s);
    }

    static String escapeHtml(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#x27;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String details(Object value, String title) {
        return "<details><summary>" + h(title) + "</summary><pre>"
                + h(PyJsonWriter.dumps(value, true, false, true, false))
                + "</pre></details>";
    }

    private static String refs(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> e : items) {
            sb.append("<li><strong>").append(h(e.get("file"))).append(" \u00b7 frame ")
                    .append(h(e.get("frame"))).append("</strong>")
                    .append(details(e, "Frame, content and flow filters")).append("</li>");
        }
        return sb.toString();
    }

    private static String orEmpty(Object v) {
        return FieldDiff.truthy(v) ? String.valueOf(v) : "";
    }

    private static String requireString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return s;
    }

    private static String nullableString(Object v, String key) {
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException(key + " must be a string or null");
        }
        return s;
    }

    private static String loadEcharts() {
        String cached = echartsCache;
        if (cached == null) {
            cached = loadGzResource("/packetanalysis/export/echarts-offline.js.gz");
            echartsCache = cached;
        }
        return cached;
    }

    private static String loadIncidentScript() {
        String cached = incidentScriptCache;
        if (cached == null) {
            cached = loadGzResource("/packetanalysis/export/incident-offline.js.gz");
            incidentScriptCache = cached;
        }
        return cached;
    }

    private static String loadGzResource(String resource) {
        try (InputStream in = Export.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            try (GZIPInputStream gz = new GZIPInputStream(in)) {
                return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Db openDb(Path projectOrDb) throws SQLException {
        Path dbFile = Files.isDirectory(projectOrDb)
                ? projectOrDb.resolve("project.duckdb")
                : projectOrDb;
        if (!Files.exists(dbFile)) {
            // Initialize empty project directory if it does not exist yet
            ProjectStore store = ProjectStore.open(
                    projectOrDb.getFileName().toString().endsWith(".duckdb") ? projectOrDb.getParent() : projectOrDb,
                    ProjectStore.Settings.defaults(null));
            dbFile = store.path().resolve("project.duckdb");
        }
        return new Db(Engine.open(dbFile));
    }

    public static Object getSetting(Db db, String key) {
        if (!db.tableExists("settings")) {
            return null;
        }
        Object[] row = db.one("SELECT value FROM settings WHERE key=?", key);
        return row == null || row[0] == null ? null : Evidence.loads((String) row[0]);
    }

    public static void setSetting(Db db, String key, Object value) {
        db.exec("CREATE TABLE IF NOT EXISTS settings(key VARCHAR PRIMARY KEY, value VARCHAR)");
        db.exec("INSERT OR REPLACE INTO settings VALUES (?, ?)", key, PyJsonWriter.dumps(value, false));
    }
}
