package com.securityexpert.nexus.ui2.worker.configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationEntry;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationEntry.DeviationKind;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationSummary;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;

/**
 * Computes a structural deviation summary (14I DV-2) by comparing two
 * {@link ConfigurationIndexEntry} lists. Stateless; every method is static.
 *
 * <p>Identity key: {@code (context, section, source)} -- the full tuple the
 * index already carries. Two Palo Alto entries that differ only by source are
 * therefore different entries (AC-3). The summary reports deviations
 * per {@code (context, section, source)}.</p>
 *
 * <p>If the old index list is null (predecessor's index was never stored),
 * the result is {@link ConfigurationDeviationSummary#notComputable()} (AC-5).
 * An empty old list is a legitimate empty predecessor, not a missing one --
 * callers are responsible for passing {@code null} only when the predecessor
 * index is genuinely absent.</p>
 */
public final class IndexDeviationComputer {

    private IndexDeviationComputer() {
    }

    /**
     * Compares {@code oldIndex} against {@code newIndex} and returns a summary
     * of which {@code (context, section, source)} groups gained, lost, or altered their
     * total entry count.
     *
     * @param oldIndex the predecessor run's index; {@code null} means the
     *     predecessor's index was never stored (AC-5 -- result is NOT_COMPUTABLE)
     * @param newIndex the current run's index; must not be {@code null}
     * @return a computed summary, or {@link ConfigurationDeviationSummary#notComputable()}
     *     when {@code oldIndex} is {@code null}
     */
    public static ConfigurationDeviationSummary compute(
            List<ConfigurationIndexEntry> oldIndex, List<ConfigurationIndexEntry> newIndex) {
        if (oldIndex == null) {
            // AC-5: absence of evidence is not evidence of absence.
            return ConfigurationDeviationSummary.notComputable();
        }

        // Aggregate entry counts by (context, section, source) for both sides.
        Map<SectionKey, Integer> oldCounts = aggregate(oldIndex);
        Map<SectionKey, Integer> newCounts = aggregate(newIndex);

        List<ConfigurationDeviationEntry> entries = new ArrayList<>();

        // Sections in new but not old: ADDED.
        for (Map.Entry<SectionKey, Integer> newEntry : newCounts.entrySet()) {
            if (!oldCounts.containsKey(newEntry.getKey())) {
                entries.add(new ConfigurationDeviationEntry(
                        newEntry.getKey().context(), newEntry.getKey().section(), newEntry.getKey().source(),
                        DeviationKind.ADDED, 0, newEntry.getValue()));
            }
        }

        // Sections in old but not new: REMOVED.
        for (Map.Entry<SectionKey, Integer> oldEntry : oldCounts.entrySet()) {
            if (!newCounts.containsKey(oldEntry.getKey())) {
                entries.add(new ConfigurationDeviationEntry(
                        oldEntry.getKey().context(), oldEntry.getKey().section(), oldEntry.getKey().source(),
                        DeviationKind.REMOVED, oldEntry.getValue(), 0));
            }
        }

        // Sections in both with a different count: RECOUNTED.
        for (Map.Entry<SectionKey, Integer> oldEntry : oldCounts.entrySet()) {
            Integer newCount = newCounts.get(oldEntry.getKey());
            if (newCount != null && !newCount.equals(oldEntry.getValue())) {
                entries.add(new ConfigurationDeviationEntry(
                        oldEntry.getKey().context(), oldEntry.getKey().section(), oldEntry.getKey().source(),
                        DeviationKind.RECOUNTED, oldEntry.getValue(), newCount));
            }
        }

        return new ConfigurationDeviationSummary(ConfigurationDeviationSummary.Status.COMPUTED, entries);
    }

    /**
     * {@link #compute(List, List)}, plus -- for Check Point -- one {@link DeviationKind#MODIFIED} entry per section
     * that kept its entry count while its sanitized {@code set} lines changed (V176; docs/design/
     * BACKUP_MAJOR_DEVIATIONS_2026_10_23.md). A count-only summary cannot see an interface address or next-hop edit;
     * this can. Both texts are the stored sanitized texts (secret-bearing lines already withheld on both sides), so a
     * withheld line's change stays invisible. Without both texts, or for any other vendor, the result is exactly
     * {@link #compute(List, List)}. Only section names and counts leave this method, never a line.
     */
    public static ConfigurationDeviationSummary compute(List<ConfigurationIndexEntry> oldIndex,
            List<ConfigurationIndexEntry> newIndex, Optional<String> oldSanitizedText, Optional<String> newSanitizedText,
            String vendor) {
        ConfigurationDeviationSummary counted = compute(oldIndex, newIndex);
        if (counted.status() != ConfigurationDeviationSummary.Status.COMPUTED || !"check_point".equals(vendor)
                || oldSanitizedText.isEmpty() || newSanitizedText.isEmpty()) {
            return counted;
        }
        Map<String, Map<String, Integer>> oldLines = checkPointLinesBySection(oldSanitizedText.get());
        Map<String, Map<String, Integer>> newLines = checkPointLinesBySection(newSanitizedText.get());
        Map<SectionKey, Integer> oldCounts = aggregate(oldIndex);
        Map<SectionKey, Integer> newCounts = aggregate(newIndex);
        List<ConfigurationDeviationEntry> entries = new ArrayList<>(counted.entries());
        for (Map.Entry<SectionKey, Integer> oldEntry : oldCounts.entrySet()) {
            SectionKey key = oldEntry.getKey();
            if (!oldEntry.getValue().equals(newCounts.get(key))) {
                continue; // ADDED / REMOVED / RECOUNTED already say it
            }
            Map<String, Integer> before = oldLines.getOrDefault(key.section(), Map.of());
            Map<String, Integer> after = newLines.getOrDefault(key.section(), Map.of());
            if (!before.equals(after)) {
                entries.add(new ConfigurationDeviationEntry(key.context(), key.section(), key.source(),
                        DeviationKind.MODIFIED, oldEntry.getValue(), oldEntry.getValue()));
            }
        }
        return new ConfigurationDeviationSummary(ConfigurationDeviationSummary.Status.COMPUTED, entries);
    }

    /**
     * Section (the same one/two-token rule as the index) to a multiset of its sanitized {@code set} lines. An
     * interface's {@code comments} line is left out: an edited description is not a configuration change that the
     * Backups screen should call major.
     */
    static Map<String, Map<String, Integer>> checkPointLinesBySection(String sanitizedText) {
        Map<String, Map<String, Integer>> bySection = new LinkedHashMap<>();
        for (String raw : sanitizedText.split("\\R")) {
            String line = raw.strip();
            if (!line.startsWith("set ")) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            if (tokens.length >= 4 && "interface".equals(tokens[1]) && "comments".equals(tokens[3])) {
                continue;
            }
            String section = com.securityexpert.nexus.ui2.worker.configuration.cp.CheckPointGaiaConfigParser.sectionOf(line);
            bySection.computeIfAbsent(section, k -> new java.util.HashMap<>()).merge(line, 1, Integer::sum);
        }
        return bySection;
    }

    /**
     * Aggregates entry counts by {@code (context, section, source)}.
     */
    private static Map<SectionKey, Integer> aggregate(List<ConfigurationIndexEntry> index) {
        Map<SectionKey, Integer> counts = new LinkedHashMap<>();
        for (ConfigurationIndexEntry entry : index) {
            SectionKey key = new SectionKey(entry.context(), entry.section(), entry.source());
            counts.merge(key, entry.entryCount(), Integer::sum);
        }
        return counts;
    }

    /** Identity key for grouping: context + section + source. */
    private record SectionKey(String context, String section, Optional<String> source) {
    }
}
