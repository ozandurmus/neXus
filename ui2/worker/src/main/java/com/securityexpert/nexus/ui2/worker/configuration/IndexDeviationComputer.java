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
