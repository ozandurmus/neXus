package com.securityexpert.nexus.ui2.service.logdetect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.TemplateRow;
import com.securityexpert.nexus.ui2.platform.logtemplate.TemplateMiner;

/**
 * Online matching against one frozen catalog version (design section 4.1 step 5): per source, a frozen
 * {@link TemplateMiner} over the templates' match forms. It never creates a cluster, so the same normalised line under
 * the same catalog version always gets the same template; a line that matches nothing gets a provisional id
 * ({@link LogTemplateIds#provisionalId}). Immutable after construction.
 */
public final class LogTemplateMatcher {
    private final String catalogVersion;
    private final Map<String, TemplateMiner> miners = new LinkedHashMap<>();
    private final Map<String, List<TemplateRow>> rows = new LinkedHashMap<>();
    private final Map<String, TemplateRow> byId = new LinkedHashMap<>();
    private final Map<String, Integer> cohort = new TreeMap<>();

    public LogTemplateMatcher(String catalogVersion, List<TemplateRow> templates, TemplateMiner.Params params) {
        this.catalogVersion = catalogVersion;
        Map<String, List<TemplateRow>> bySource = new TreeMap<>();
        for (TemplateRow t : templates) bySource.computeIfAbsent(t.sourceKey(), k -> new ArrayList<>()).add(t);
        for (var e : bySource.entrySet()) {
            List<TemplateRow> list = new ArrayList<>(e.getValue());
            list.sort(java.util.Comparator.comparing(TemplateRow::templateId));
            List<String> forms = list.stream().map(TemplateRow::matchForm).toList();
            miners.put(e.getKey(), TemplateMiner.frozen(params, forms));
            rows.put(e.getKey(), List.copyOf(list));
            for (TemplateRow t : list) {
                byId.put(t.templateId(), t);
                cohort.merge(e.getKey(), t.cohortDevices(), Math::max);
            }
        }
    }

    public String catalogVersion() {
        return catalogVersion;
    }

    /** The frozen template of a line's match form, if any. */
    public Optional<TemplateRow> match(String sourceKey, String matchForm) {
        TemplateMiner miner = miners.get(sourceKey);
        if (miner == null) return Optional.empty();
        int id = miner.match(matchForm);
        if (id < 0) return Optional.empty();
        return Optional.of(rows.get(sourceKey).get(id));
    }

    public Optional<TemplateRow> template(String templateId) {
        return Optional.ofNullable(byId.get(templateId));
    }

    /** The training-window cohort of the source (devices with evaluable history), 0 when the catalog has no template of it. */
    public int cohort(String sourceKey) {
        return cohort.getOrDefault(sourceKey, 0);
    }
}
