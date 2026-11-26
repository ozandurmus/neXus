package com.securityexpert.nexus.ui2.service.logdetect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.CatalogRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.TemplateRow;
import com.securityexpert.nexus.ui2.platform.logtemplate.TemplateMiner;

/**
 * The diff between two catalog versions (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md section 6.5): templates added,
 * removed, merged and split, each with its line share; class and severity changes; marker list and normaliser changes;
 * the mapping old id to new id(s). Deterministic; template ids and counts only, never a template's words.
 *
 * <p>Mapping: an old template maps to the new templates its match form matches in the new frozen catalog, and a new
 * template maps back to the old templates its match form matches in the old one (the same frozen lookup as online
 * matching, {@link TemplateMiner#match}). An old template with two or more new partners is <b>split</b>; a new template
 * with two or more old partners is a <b>merge</b>; a template with no partner is <b>removed</b> / <b>added</b>. Where a
 * split cannot be mapped back to counts (the raw chunks have expired) the history of the new ids is
 * {@code UNKNOWN_BEFORE} the new version's training window start (recorded here, applied by the reader).</p>
 */
public final class LogCatalogDiff {
    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private LogCatalogDiff() {
    }

    /** The diff as a JSON object; {@code previous} null gives the first version's diff (every template added). */
    public static String json(CatalogRow previous, List<TemplateRow> oldTemplates, CatalogRow next, List<TemplateRow> newTemplates,
            TemplateMiner.Params params) {
        try {
            return JSON.writeValueAsString(diff(previous, oldTemplates, next, newTemplates, params));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("diff not serialisable", e);
        }
    }

    public static Map<String, Object> diff(CatalogRow previous, List<TemplateRow> oldTemplates, CatalogRow next,
            List<TemplateRow> newTemplates, TemplateMiner.Params params) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from_version", previous == null ? null : previous.catalogVersion());
        out.put("to_version", next.catalogVersion());
        Map<String, TemplateRow> oldById = byId(oldTemplates);
        Map<String, TemplateRow> newById = byId(newTemplates);
        LogTemplateMatcher oldMatcher = new LogTemplateMatcher("old", oldTemplates, params);
        LogTemplateMatcher newMatcher = new LogTemplateMatcher("new", newTemplates, params);
        Map<String, Set<String>> oldToNew = new TreeMap<>();
        Map<String, Set<String>> newToOld = new TreeMap<>();
        for (TemplateRow o : oldById.values()) {
            if (newById.containsKey(o.templateId())) link(oldToNew, newToOld, o.templateId(), o.templateId());
            newMatcher.match(o.sourceKey(), o.matchForm()).ifPresent(n -> link(oldToNew, newToOld, o.templateId(), n.templateId()));
        }
        for (TemplateRow n : newById.values()) {
            oldMatcher.match(n.sourceKey(), n.matchForm()).ifPresent(o -> link(oldToNew, newToOld, o.templateId(), n.templateId()));
        }
        Map<String, Long> oldTotals = totals(oldTemplates);
        Map<String, Long> newTotals = totals(newTemplates);
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> merged = new ArrayList<>();
        List<Map<String, Object>> split = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        for (TemplateRow n : newById.values()) {
            Set<String> partners = newToOld.getOrDefault(n.templateId(), Set.of());
            if (partners.isEmpty()) added.add(entry(n, newTotals));
            if (partners.size() >= 2) {
                Map<String, Object> m = entry(n, newTotals);
                m.put("from", new ArrayList<>(partners));
                merged.add(m);
            }
            TemplateRow o = oldById.get(n.templateId());
            if (o != null && (!o.templateClass().equals(n.templateClass()) || o.severityWeight() != n.severityWeight()
                    || !java.util.Objects.equals(o.markerId(), n.markerId()))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("template_id", n.templateId());
                m.put("class_from", o.templateClass());
                m.put("class_to", n.templateClass());
                m.put("s_from", o.severityWeight());
                m.put("s_to", n.severityWeight());
                m.put("marker_from", o.markerId());
                m.put("marker_to", n.markerId());
                changed.add(m);
            }
        }
        for (TemplateRow o : oldById.values()) {
            Set<String> partners = oldToNew.getOrDefault(o.templateId(), Set.of());
            if (partners.isEmpty()) removed.add(entry(o, oldTotals));
            if (partners.size() >= 2) {
                Map<String, Object> m = entry(o, oldTotals);
                m.put("into", new ArrayList<>(partners));
                m.put("history", "UNKNOWN_BEFORE");
                split.add(m);
            }
        }
        out.put("added", added);
        out.put("removed", removed);
        out.put("merged", merged);
        out.put("split", split);
        out.put("class_changes", changed);
        Map<String, Object> mapping = new LinkedHashMap<>();
        for (var e : oldToNew.entrySet()) mapping.put(e.getKey(), new ArrayList<>(e.getValue()));
        out.put("mapping", mapping);
        out.put("marker_list", Map.of("from", previous == null ? "" : manifestValue(previous, "marker_list_version"),
                "to", manifestValue(next, "marker_list_version")));
        out.put("normaliser", Map.of("from", previous == null ? "" : manifestValue(previous, "normaliser_version"),
                "to", manifestValue(next, "normaliser_version")));
        out.put("masking_rule_set", Map.of("from", previous == null ? "" : manifestValue(previous, "masking_rule_set"),
                "to", manifestValue(next, "masking_rule_set")));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("templates_from", oldTemplates.size());
        counts.put("templates_to", newTemplates.size());
        counts.put("added", added.size());
        counts.put("removed", removed.size());
        counts.put("merged", merged.size());
        counts.put("split", split.size());
        counts.put("class_changes", changed.size());
        out.put("counts", counts);
        return out;
    }

    private static void link(Map<String, Set<String>> oldToNew, Map<String, Set<String>> newToOld, String oldId, String newId) {
        oldToNew.computeIfAbsent(oldId, k -> new TreeSet<>()).add(newId);
        newToOld.computeIfAbsent(newId, k -> new TreeSet<>()).add(oldId);
    }

    private static Map<String, TemplateRow> byId(List<TemplateRow> rows) {
        Map<String, TemplateRow> m = new TreeMap<>();
        for (TemplateRow r : rows) m.put(r.templateId(), r);
        return m;
    }

    private static Map<String, Long> totals(List<TemplateRow> rows) {
        Map<String, Long> m = new TreeMap<>();
        for (TemplateRow r : rows) m.merge(r.sourceKey(), r.lineCount(), Long::sum);
        return m;
    }

    private static Map<String, Object> entry(TemplateRow t, Map<String, Long> totals) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template_id", t.templateId());
        m.put("source_key", t.sourceKey());
        long total = totals.getOrDefault(t.sourceKey(), 0L);
        m.put("line_share", total == 0 ? 0.0 : Math.round(1_000_000.0 * t.lineCount() / total) / 1_000_000.0);
        return m;
    }

    private static String manifestValue(CatalogRow row, String key) {
        try {
            var node = JSON.readTree(row.manifestJson()).get(key);
            return node == null || node.isNull() ? "" : node.asText();
        } catch (JsonProcessingException e) {
            return "";
        }
    }
}
