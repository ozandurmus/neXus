package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>Cisco ASA and FortiGate HA pairs (PO decision record 2026-11-14 decision 6): an HA pair context whose outcome is
 * {@link HaPolicyPairing.Outcome#SAME} counts once via deterministic representative {@code memberA}; divergent
 * ({@link HaPolicyPairing.Outcome#HA_POLICY_DIVERGENT}) and {@link HaPolicyPairing.Outcome#UNKNOWN} pairs are never merged.
 * SAME proves only that the policy content is identical, not that the members' rule hit counters are: only the classes that
 * come from policy content (shadowed, conflict, disabled, expired, broad, and UNKNOWN for content reasons) are counted once.
 * {@code unused} findings and UNKNOWN findings that rest on hit counters are member-specific runtime evidence, so the peer
 * member's own hit-derived counts are added to the totals and listed on the row as {@code peerMemberSpecific}. A pair whose
 * peer member is not analysed yet stays PENDING, never a half count.
 */
@Component
public class PolicyHygieneFleetSummary {
    private static final System.Logger LOG = System.getLogger(PolicyHygieneFleetSummary.class.getName());
    public static final int DAYS = 90;
    /** Counted classes in display order; {@code unknown} is reported beside them, not among them. */
    public static final List<String> COUNTED = List.of("shadowed", "conflict", "disabled", "unused", "expired", "broad");

    record Entry(String fingerprint, Map<String, Integer> counts, Map<String, Integer> unknownReasons, Map<String, Integer> blockerReasons, int rules, String snapshotAt,
            int hitUnused, int hitOnlyUnknown) {}
    private final PolicyQueryService policies;
    private volatile HaPolicyPairService haPairs;
    /** The pair index of the last tick that evaluated successfully; view() only reads it, never evaluates pairs itself. */
    private volatile PairIndex publishedPairs = PairIndex.UNAVAILABLE;
    private final Map<String, Entry> entries = new HashMap<>();

    @Autowired
    public PolicyHygieneFleetSummary(PolicyQueryService policies) { this(policies, null); }

    public PolicyHygieneFleetSummary(PolicyQueryService policies, HaPolicyPairService haPairs) {
        this.policies = policies;
        this.haPairs = haPairs;
    }

    @Autowired(required = false)
    public void setHaPolicyPairService(HaPolicyPairService haPairs) {
        this.haPairs = haPairs;
    }

    private record PairSlot(String vendor, String sourceId, String context) {}

    private record PairIndex(
            boolean available,
            Map<PairSlot, HaPolicyPairing.Result> byMemberContext,
            Set<PairSlot> omittedDuplicates,
            Map<PairSlot, PairSlot> peerOf,
            int countedOnce,
            int divergent,
            int unknown) {
        static final PairIndex UNAVAILABLE = new PairIndex(false, Map.of(), Set.of(), Map.of(), 0, 0, 0);

        HaPolicyPairing.Result match(Container container) {
            if (!available || !HaPolicyPairing.VENDORS.contains(container.vendor()) || container.sourceId() == null || container.containerName() == null) {
                return null;
            }
            return byMemberContext.get(new PairSlot(container.vendor(), container.sourceId(), container.containerName()));
        }

        PairSlot peerSlot(Container container) {
            if (!available || container.sourceId() == null || container.containerName() == null) return null;
            return peerOf.get(new PairSlot(container.vendor(), container.sourceId(), container.containerName()));
        }

        boolean isOmittedDuplicate(Container container) {
            if (!available || !HaPolicyPairing.VENDORS.contains(container.vendor()) || container.sourceId() == null || container.containerName() == null) {
                return false;
            }
            return omittedDuplicates.contains(new PairSlot(container.vendor(), container.sourceId(), container.containerName()));
        }
    }

    /** Evaluates the pairs once; null when the evaluation failed, so the caller keeps the last good index. */
    private PairIndex pairIndex(List<Container> containers) {
        var service = haPairs;
        if (service == null) return PairIndex.UNAVAILABLE;
        List<HaPolicyPairing.Result> results;
        try {
            results = service.evaluatePairs();
        } catch (RuntimeException unavailable) {
            LOG.log(System.Logger.Level.WARNING, "[HYGIENE_SUMMARY] HA pair evaluation unavailable: {0}", unavailable.getClass().getSimpleName());
            return null;
        }
        if (results == null) return null;
        Set<PairSlot> presentSlots = new HashSet<>();
        for (var container : containers) {
            if (HaPolicyPairing.VENDORS.contains(container.vendor()) && container.sourceId() != null && container.containerName() != null) {
                presentSlots.add(new PairSlot(container.vendor(), container.sourceId(), container.containerName()));
            }
        }
        Map<PairSlot, HaPolicyPairing.Result> byMemberContext = new LinkedHashMap<>();
        Set<PairSlot> omitted = new HashSet<>();
        Map<PairSlot, PairSlot> peers = new HashMap<>();
        int countedOnce = 0, divergent = 0, unknown = 0;
        for (HaPolicyPairing.Result result : results) {
            if (result == null || !HaPolicyPairing.VENDORS.contains(result.vendor())) continue;
            if (result.context() != null) {
                PairSlot slotA = new PairSlot(result.vendor(), result.memberA(), result.context());
                PairSlot slotB = new PairSlot(result.vendor(), result.memberB(), result.context());
                if (result.outcome() == HaPolicyPairing.Outcome.SAME
                        && presentSlots.contains(slotA) && presentSlots.contains(slotB)) {
                    byMemberContext.put(slotA, result);
                    byMemberContext.put(slotB, result);
                    omitted.add(slotB);
                    peers.put(slotA, slotB);
                    countedOnce++;
                    continue;
                }
                HaPolicyPairing.Result effective = result.outcome() == HaPolicyPairing.Outcome.SAME
                        ? new HaPolicyPairing.Result(result.pairRef(), result.vendor(), result.context(),
                                HaPolicyPairing.Outcome.UNKNOWN, HaPolicyPairing.Reason.MEMBER_POLICY_MISSING,
                                result.memberA(), result.memberB(), result.newerCollectedAt(), result.olderCollectedAt())
                        : result;
                byMemberContext.put(slotA, effective);
                byMemberContext.put(slotB, effective);
                if (effective.outcome() == HaPolicyPairing.Outcome.HA_POLICY_DIVERGENT) divergent++;
                else unknown++;
            } else {
                if (result.outcome() == HaPolicyPairing.Outcome.HA_POLICY_DIVERGENT) divergent++;
                else unknown++;
            }
        }
        return new PairIndex(true, byMemberContext, omitted, peers, countedOnce, divergent, unknown);
    }

    private static String fingerprint(Container container, HaPolicyPairing.Result match) {
        if (match == null) return container.fingerprint();
        return container.fingerprint() + "ha=" + match.pairRef() + "|" + match.outcome() + "|" + match.reason();
    }

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
    public boolean refreshNext() {
        var all = containers();
        // Once per tick and outside the lock: pair evaluation reads the database and re-parses configurations.
        var evaluated = pairIndex(all);
        if (evaluated != null) publishedPairs = evaluated; // a failed tick keeps the last good index; none yet fails closed
        var pairs = publishedPairs;
        return refreshWith(all, pairs);
    }

    private synchronized boolean refreshWith(List<Container> all, PairIndex pairs) {
        Set<String> activeKeys = new HashSet<>();
        for (var c : all) activeKeys.add(c.key());
        entries.keySet().retainAll(activeKeys);
        // The omitted peer member is still analysed: its hit counters are member-specific evidence the summary must not drop.
        for (var container : all) {
            String fp = fingerprint(container, pairs.match(container));
            var known = entries.get(container.key());
            if (known != null && known.fingerprint().equals(fp)) continue;
            try {
                Map<String, Integer> total = new TreeMap<>(); PolicyHygiene.CLASSES.forEach(c -> total.put(c, 0));
                Map<String, Integer> unknownReasons = new HashMap<>(), blockerReasons = new HashMap<>();
                int rules = 0, hitUnused = 0, hitOnlyUnknown = 0;
                for (var meta : container.snapshots()) {
                    var snapshot = policies.find(meta.id());
                    if (snapshot.isEmpty()) throw new IllegalStateException("Snapshot is no longer stored");
                    var report = policies.hygieneReport(snapshot.get(), DAYS);
                    report.classes().forEach((c, n) -> total.merge(c, n, Integer::sum));
                    report.unknownReasons().forEach((r, n) -> unknownReasons.merge(r, n, Integer::sum));
                    report.blockerReasons().forEach((r, n) -> blockerReasons.merge(r, n, Integer::sum));
                    hitUnused += report.hitUnused(); hitOnlyUnknown += report.hitOnlyUnknown();
                    rules += snapshot.get().sections().stream().mapToInt(s -> s.rules().size()).sum();
                }
                entries.put(container.key(), new Entry(fp, total, unknownReasons, blockerReasons, rules, container.snapshotAt(), hitUnused, hitOnlyUnknown));
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
        var all = containers();
        var pairs = publishedPairs;
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        COUNTED.forEach(c -> totals.put(c, 0)); totals.put("unknown", 0);
        Map<String, Integer> totalUnknownReasons = new HashMap<>(), totalBlockerReasons = new HashMap<>();
        Map<String, Integer> peerTotals = new LinkedHashMap<>(); peerTotals.put("unused", 0); peerTotals.put("unknown", 0);
        int rules = 0, pending = 0;
        Map<PairSlot, Container> bySlot = new HashMap<>();
        for (var c : all) {
            if (HaPolicyPairing.VENDORS.contains(c.vendor()) && c.sourceId() != null && c.containerName() != null) {
                bySlot.put(new PairSlot(c.vendor(), c.sourceId(), c.containerName()), c);
            }
        }
        for (var container : all) {
            if (pairs.isOmittedDuplicate(container)) continue;
            var match = pairs.match(container);
            String fp = fingerprint(container, match);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceId", container.sourceId()); row.put("sourceName", container.sourceName()); row.put("vendor", container.vendor());
            row.put("containerId", container.containerId()); row.put("containerName", container.containerName());
            row.put("snapshotAt", container.snapshotAt());
            if (match != null) {
                row.put("pairRef", match.pairRef());
                row.put("haContext", match.context());
                row.put("haOutcome", match.outcome().name());
                row.put("haReason", match.reason() == null ? null : match.reason().name());
            }
            var entry = entries.get(container.key());
            PairSlot peerSlot = pairs.peerSlot(container);
            Container peer = peerSlot == null ? null : bySlot.get(peerSlot);
            Entry peerEntry = peer == null ? null : entries.get(peer.key());
            boolean peerCurrent = peerSlot == null
                || (peer != null && peerEntry != null && peerEntry.fingerprint().equals(fingerprint(peer, pairs.match(peer))));
            if (entry == null || !entry.fingerprint().equals(fp) || !peerCurrent) {
                row.put("state", "PENDING"); pending++;
            } else {
                row.put("state", "READY"); row.put("rules", entry.rules()); row.put("snapshotAt", entry.snapshotAt());
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (String c : COUNTED) counts.put(c, entry.counts().getOrDefault(c, 0));
                counts.put("unknown", entry.counts().getOrDefault("unknown", 0));
                row.put("counts", counts);
                counts.forEach((c, n) -> totals.merge(c, n, Integer::sum));
                if (peerEntry != null) {
                    // Hit counters are per member: the peer's own hit-derived findings are added, its content findings are not (counted once).
                    Map<String, Integer> peerSpecific = new LinkedHashMap<>();
                    peerSpecific.put("unused", peerEntry.hitUnused());
                    peerSpecific.put("unknown", peerEntry.hitOnlyUnknown());
                    row.put("peerMemberSpecific", peerSpecific);
                    totals.merge("unused", peerEntry.hitUnused(), Integer::sum);
                    totals.merge("unknown", peerEntry.hitOnlyUnknown(), Integer::sum);
                    peerTotals.merge("unused", peerEntry.hitUnused(), Integer::sum);
                    peerTotals.merge("unknown", peerEntry.hitOnlyUnknown(), Integer::sum);
                }
                row.put("unknownReasons", ranked(entry.unknownReasons())); row.put("blockerReasons", ranked(entry.blockerReasons()));
                entry.unknownReasons().forEach((r, n) -> totalUnknownReasons.merge(r, n, Integer::sum));
                entry.blockerReasons().forEach((r, n) -> totalBlockerReasons.merge(r, n, Integer::sum));
                rules += entry.rules();
            }
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("containers", rows); body.put("totals", totals);
        // totals = sum of the row counts + these peer-member hit-counter findings (listed per row as peerMemberSpecific).
        body.put("peerMemberSpecificTotals", peerTotals); body.put("rules", rules); body.put("pending", pending);
        body.put("days", DAYS);
        body.put("unknownReasons", ranked(totalUnknownReasons)); body.put("blockerReasons", ranked(totalBlockerReasons));
        if (pairs.available()) {
            body.put("haPairsCountedOnce", pairs.countedOnce());
            body.put("haPairsDivergent", pairs.divergent());
            body.put("haPairsUnknown", pairs.unknown());
        }
        return body;
    }

    /** Reason -> rule count, largest first, ties by reason text, so the answer is stable between reads. */
    private static Map<String, Integer> ranked(Map<String, Integer> counts) {
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void keepCurrent() {
        try { refreshNext(); }
        catch (RuntimeException failed) { LOG.log(System.Logger.Level.WARNING, "[HYGIENE_SUMMARY] pass skipped: {0}", failed.getClass().getSimpleName()); }
    }
}
