package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The on-screen projection: a projected {@link ReportDocument} as plain maps and lists (stable key order, ISO-8601
 * times, no masker reference). The preview response and {@code data_as_of_json} both come from here.
 */
public final class ReportJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ReportJson() {
    }

    public static Map<String, Object> toMap(ReportDocument d) {
        ReportRenderer.requireProjected(d);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template_id", d.templateId());
        m.put("title", d.title());
        m.put("audience", d.audience());
        m.put("question", d.question());
        m.put("generated_at", iso(d.generatedAt()));
        m.put("projection", d.projection());
        m.put("parameters", d.parameters());
        m.put("data_as_of", d.dataAsOf().stream().map(ReportJson::plane).toList());
        m.put("notices", d.notices());
        List<Map<String, Object>> sections = new ArrayList<>();
        for (ReportDocument.Section s : d.sections()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.id());
            sm.put("title", s.title());
            sm.put("data_as_of", s.dataAsOf().stream().map(ReportJson::plane).toList());
            sm.put("kpis", s.kpis().stream().map(k -> {
                Map<String, Object> km = new LinkedHashMap<>();
                km.put("label", k.label());
                km.put("value", k.value());
                km.put("tone", k.tone());
                return km;
            }).toList());
            sm.put("tables", s.tables().stream().map(t -> {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("id", t.id());
                tm.put("title", t.title());
                tm.put("columns", t.columns().stream().map(c -> Map.of("key", c.key(), "label", c.label())).toList());
                tm.put("rows", t.rows().stream().map(row -> row.stream().map(c -> {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("text", ReportRenderer.show(c.text()));
                    cm.put("kind", c.kind().name());
                    return cm;
                }).toList()).toList());
                tm.put("empty_text", t.emptyText());
                return tm;
            }).toList());
            sm.put("notes", s.notes());
            sections.add(sm);
        }
        m.put("sections", sections);
        return m;
    }

    public static Map<String, Object> plane(ReportDocument.PlaneAsOf p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plane", p.plane());
        m.put("newest", iso(p.newest()));
        m.put("oldest", iso(p.oldest()));
        m.put("covered", p.covered());
        m.put("total", p.total());
        m.put("stale", p.stale());
        m.put("unit", p.unit());
        return m;
    }

    /** {@code report_run.data_as_of_json}: per-plane times and coverage counts only (no identity). */
    public static String dataAsOfJson(List<ReportDocument.PlaneAsOf> planes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planes", planes.stream().map(ReportJson::plane).toList());
        try {
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String iso(Instant at) {
        return at == null ? null : at.toString();
    }
}
