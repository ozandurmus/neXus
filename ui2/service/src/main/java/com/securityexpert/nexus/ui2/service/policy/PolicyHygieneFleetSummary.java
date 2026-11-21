package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The fleet-wide hygiene summary Policy > Hygiene opens on: per stored container, how many rules are shadowed, conflicting,
 * disabled, unused, expired and broad, with UNKNOWN counted separately and never folded into "clean", plus the time of the
 * snapshot each count was read from. It is the same per-snapshot analysis the container view runs, computed here in the
 * background from the stored snapshots; nothing contacts a device.
 *
 * <p>CPU bound: one container is analysed at a time, and a container whose snapshots are unchanged (same ids, collection
 * times and artefact references) is skipped, so a steady fleet costs one catalog read per tick. A container whose analysis
 * fails stays absent from the summary as {@code PENDING} and is retried on a later tick; it never shows zeros.
 */
@Component
public class PolicyHygieneFleetSummary {
    private static final System.Logger LOG = System.getLogger(PolicyHygieneFleetSummary.class.getName());
    public static final int DAYS = 90;
    /** Counted classes in display order; {@code unknown} is reported beside them, not among them. */
    public static final List<String> COUNTED = List.of("shadowed", "conflict", "disabled", "unused", "expired", "broad");

    record Entry(String fingerprint, Map<String, Integer> counts, int rules, String snapshotAt) {}
    private final PolicyQueryService policies;
    private final Map<String, Entry> entries = new HashMap<>();

    public PolicyHygieneFleetSummary(PolicyQueryService policies) { this.policies = policies; }

    /** One stored container as the catalog presents it (source + container id); its snapshots are the catalog's policies. */
    private record Container(String sourceId, String sourceName, String vendor, String containerId, String containerName, List<Metadata> snapshots) {
        String key() { return sourceId + "\u0000" + containerId; }
        String fingerprint() {
            return snapshots.stream().map(m -> m.id() + "|" + m.collectedAt() + "|" + m.artefactRef()).sorted().reduce("", (a, b) -> a + b + ";");
        }
        String snapshotAt() {
            return snapshots.stream().map(Metadata::collectedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse("");
        }
    }
    private List<Container> containers() {
        Map<String, List<Metadata>> groups = new LinkedHashMap<>();
        for (var m : policies.catalog()) groups.computeIfAbsent(m.sourceId() + "\u0000" + m.containerId(), k -> new ArrayList<>()).add(m);
        return groups.values().stream().map(g -> new Container(g.get(0).sourceId(), g.get(0).sourceName(), g.get(0).vendor(),
            g.get(0).containerId(), g.get(0).containerName(), List.copyOf(g))).toList();
    }

    /** Analyses the first container whose snapshots changed since the last pass; false when every container is current. */
    public synchronized boolean refreshNext() {
        var all = containers();
        entries.keySet().retainAll(all.stream().map(Container::key).toList());
        for (var container : all) {
            var known = entries.get(container.key());
            if (known != null && known.fingerprint().equals(container.fingerprint())) continue;
            try {
                Map<String, Integer> total = new TreeMap<>(); PolicyHygiene.CLASSES.forEach(c -> total.put(c, 0));
                int rules = 0;
                for (var meta : container.snapshots()) {
                    var snapshot = policies.find(meta.id());
                    if (snapshot.isEmpty()) throw new IllegalStateException("Snapshot is no longer stored");
                    policies.hygieneCounts(snapshot.get(), DAYS).forEach((c, n) -> total.merge(c, n, Integer::sum));
                    rules += snapshot.get().sections().stream().mapToInt(s -> s.rules().size()).sum();
                }
                entries.put(container.key(), new Entry(container.fingerprint(), total, rules, container.snapshotAt()));
            } catch (RuntimeException failed) {
                entries.remove(container.key());
                LOG.log(System.Logger.Level.WARNING, "[HYGIENE_SUMMARY] container skipped: {0}", failed.getClass().getSimpleName());
            }
            return true;
        }
        return false;
    }

    /** The cached summary; containers not analysed yet are listed as PENDING with no counts, never with zeros. */
    public synchronized Map<String, Object> view() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        COUNTED.forEach(c -> totals.put(c, 0)); totals.put("unknown", 0);
        int rules = 0, pending = 0;
        for (var container : containers()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceId", container.sourceId()); row.put("sourceName", container.sourceName()); row.put("vendor", container.vendor());
            row.put("containerId", container.containerId()); row.put("containerName", container.containerName());
            row.put("snapshotAt", container.snapshotAt());
            var entry = entries.get(container.key());
            if (entry == null || !entry.fingerprint().equals(container.fingerprint())) {
                row.put("state", "PENDING"); pending++;
            } else {
                row.put("state", "READY"); row.put("rules", entry.rules()); row.put("snapshotAt", entry.snapshotAt());
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (String c : COUNTED) counts.put(c, entry.counts().getOrDefault(c, 0));
                counts.put("unknown", entry.counts().getOrDefault("unknown", 0));
                row.put("counts", counts);
                counts.forEach((c, n) -> totals.merge(c, n, Integer::sum));
                rules += entry.rules();
            }
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("containers", rows); body.put("totals", totals); body.put("rules", rules); body.put("pending", pending);
        body.put("days", DAYS);
        return body;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void keepCurrent() {
        try { refreshNext(); }
        catch (RuntimeException failed) { LOG.log(System.Logger.Level.WARNING, "[HYGIENE_SUMMARY] pass skipped: {0}", failed.getClass().getSimpleName()); }
    }
}
