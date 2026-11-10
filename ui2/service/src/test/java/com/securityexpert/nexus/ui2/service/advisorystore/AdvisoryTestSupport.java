package com.securityexpert.nexus.ui2.service.advisorystore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryCore;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryEvaluator;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.*;
import static com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ALL_TRAINS;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryParser;
import com.securityexpert.nexus.ui2.service.advisory.ProductComparator;

/** Hand-written fakes of the advisory core and the store; the real core is another lane's work. */
final class AdvisoryTestSupport {

    private AdvisoryTestSupport() { }

    /** A core with one comparator for every product: versions are {@code a.b.c}; the parser is set per source. */
    static final class FakeCore implements AdvisoryCore {
        final Map<String, BiFunction<String, byte[], ParseResult>> parsers = new HashMap<>();
        private final Map<String, List<VersionRule>> codec = new HashMap<>();
        int fromJsonCalls;

        @Override public Optional<AdvisoryParser> parser(String source) {
            var parse = parsers.get(source);
            return parse == null ? Optional.empty() : Optional.of(new AdvisoryParser() {
                @Override public String source() { return source; }
                @Override public ParseResult parse(String url, byte[] document) { return parse.apply(url, document); }
            });
        }

        @Override public Optional<ProductComparator> comparator(String productKey) {
            return productKey.equals("none:none") ? Optional.empty() : Optional.of(new ProductComparator() {
                @Override public String productKey() { return productKey; }
                @Override public Optional<String> canonical(String v) { return v != null && v.matches("\\d+\\.\\d+\\.\\d+") ? Optional.of(v) : Optional.empty(); }
                @Override public Optional<String> train(String c) { return Optional.of(c.substring(0, c.lastIndexOf('.'))); }
                @Override public int compare(String a, String b) { return Integer.compare(patch(a), patch(b)); }
            });
        }

        static int patch(String v) { return Integer.parseInt(v.substring(v.lastIndexOf('.') + 1)); }

        @Override public Optional<String> productKey(String vendorHint, String role, String model) {
            return switch (vendorHint == null ? "" : vendorHint) {
                case "palo_alto" -> Optional.of(AdvisoryModel.PRODUCT_PAN_OS);
                case "fortinet" -> Optional.of("management_server".equals(role) ? AdvisoryModel.PRODUCT_FORTIMANAGER : AdvisoryModel.PRODUCT_FORTIOS);
                case "cisco_asa" -> Optional.of(AdvisoryModel.PRODUCT_CISCO_ASA);
                default -> Optional.empty();
            };
        }

        /** AFFECTED below the range end of the device's train; FIXED at or above it; whole-train statuses as stated. */
        @Override public AdvisoryEvaluator evaluator() {
            return (product, version, state, rules) -> {
                if (rules == null || rules.isEmpty()) return new Verdict(VerdictKind.NOT_LISTED, "NOT_NAMED", null);
                if (state == ParseState.NEEDS_REVIEW) return new Verdict(VerdictKind.NOT_EVALUABLE, "ADVISORY_NEEDS_REVIEW", null);
                var cmp = comparator(product).orElseThrow();
                var canonical = cmp.canonical(version);
                if (canonical.isEmpty()) return new Verdict(VerdictKind.NOT_EVALUABLE, "VERSION_UNPARSED", null);
                String train = cmp.train(canonical.get()).orElseThrow();
                for (ProductRule rule : rules) {
                    if (!rule.train().equals(train) && !rule.train().equals(ALL_TRAINS)) continue;
                    for (VersionRule r : rule.rules()) {
                        if (r instanceof WholeTrain w) {
                            return new Verdict(w.status() == Status.AFFECTED ? VerdictKind.AFFECTED : VerdictKind.NOT_AFFECTED, "WHOLE_TRAIN", rule.fixedText());
                        }
                        if (r instanceof Range range) {
                            boolean below = patch(canonical.get()) < patch(range.to());
                            return below ? new Verdict(VerdictKind.AFFECTED, "IN_RANGE", rule.fixedText())
                                    : new Verdict(VerdictKind.FIXED, "AT_OR_ABOVE_FIX", rule.fixedText());
                        }
                    }
                }
                return new Verdict(VerdictKind.NOT_LISTED, "TRAIN_NOT_NAMED", null);
            };
        }

        @Override public String rulesToJson(List<VersionRule> rules) {
            String key = "[\"r" + codec.size() + "\"]";
            codec.put(key, List.copyOf(rules));
            return key;
        }

        @Override public List<VersionRule> rulesFromJson(String json) {
            fromJsonCalls++;
            return codec.getOrDefault(json, List.of());
        }
    }

    static ParsedAdvisory advisory(String source, String id, Severity severity, ParseState state, List<String> cves,
            List<ProductRule> rules) {
        return new ParsedAdvisory(source + ":" + id, source, "Advisory " + id, severity, new java.math.BigDecimal("7.5"), cves,
                java.time.LocalDate.of(2026, 3, 1), java.time.LocalDate.of(2026, 3, 2), "https://" + hostOf(source) + "/" + id,
                state, null, rules);
    }

    static String hostOf(String source) { return AdvisorySources.hostFor(source); }

    /** A rule "affected below patch {@code fixedAt} on {@code train}". */
    static ProductRule below(String product, String train, int fixedAt, String fixedText) {
        return new ProductRule(product, train, List.of(new Range(Status.AFFECTED, null, train + "." + fixedAt, false, List.of())), fixedText);
    }

    /** In-memory store with the semantics of {@link AdvisoryRepository}. */
    static class MemoryRepository implements AdvisoryRepository {
        final Map<String, StoredAdvisory> advisories = new java.util.LinkedHashMap<>();
        List<StoredKnownExploited> kev = new ArrayList<>();
        final Map<String, FeedRun> runs = new java.util.LinkedHashMap<>();
        int kevReplacements;

        private static String key(String run, String source) { return run + "|" + source; }

        @Override public void upsertAdvisories(List<StoredAdvisory> list) { list.forEach(a -> advisories.put(a.advisoryId(), a)); }
        @Override public void replaceKnownExploited(List<StoredKnownExploited> entries) { kev = new ArrayList<>(entries); kevReplacements++; }
        @Override public void openRun(String run, String source) {
            runs.putIfAbsent(key(run, source), new FeedRun(run, source, Instant.now(), null, null, 0, 0, 0, 0, null, null, null));
        }
        @Override public void addToRun(String run, String source, int received, int rejected, int stored, int review) {
            FeedRun r = runs.get(key(run, source));
            runs.put(key(run, source), new FeedRun(run, source, r.startedAt(), r.endedAt(), r.outcome(), r.documentsReceived() + received,
                    r.rejected() + rejected, r.advisoriesStored() + stored, r.advisoriesNeedingReview() + review, r.documentsFetched(),
                    r.documentsFailed(), r.reason()));
        }
        @Override public void closeRun(String run, String source, String outcome, Integer fetched, Integer failed, String reason) {
            openRun(run, source);
            FeedRun r = runs.get(key(run, source));
            runs.put(key(run, source), new FeedRun(run, source, r.startedAt(), Instant.now(), outcome, r.documentsReceived(), r.rejected(),
                    r.advisoriesStored(), r.advisoriesNeedingReview(), fetched, failed, reason));
        }
        @Override public Optional<FeedRun> run(String run, String source) { return Optional.ofNullable(runs.get(key(run, source))); }
        @Override public List<StoredAdvisory> advisories(Optional<String> source) {
            return advisories.values().stream().filter(a -> source.isEmpty() || a.source().equals(source.get())).toList();
        }
        @Override public List<StoredKnownExploited> knownExploited() { return kev; }
        @Override public List<FeedRun> latestRuns() {
            Map<String, FeedRun> latest = new HashMap<>();
            runs.values().forEach(r -> latest.merge(r.source(), r, (a, b) -> b.startedAt().isBefore(a.startedAt()) ? a : b));
            return new ArrayList<>(latest.values());
        }
        @Override public List<FeedRun> lastGoodRuns() {
            Map<String, FeedRun> good = new HashMap<>();
            runs.values().stream().filter(r -> "OK".equals(r.outcome()) || "PARTIAL".equals(r.outcome()))
                    .forEach(r -> good.merge(r.source(), r, (a, b) -> b.endedAt().isBefore(a.endedAt()) ? a : b));
            return new ArrayList<>(good.values());
        }
        @Override public List<SourceSummary> sourceSummaries() {
            Map<String, List<StoredAdvisory>> by = new HashMap<>();
            advisories.values().forEach(a -> by.computeIfAbsent(a.source(), k -> new ArrayList<>()).add(a));
            return by.entrySet().stream().map(e -> new SourceSummary(e.getKey(), e.getValue().size(),
                    (int) e.getValue().stream().filter(a -> "NEEDS_REVIEW".equals(a.parseState())).count(),
                    e.getValue().stream().map(StoredAdvisory::publishedOn).filter(java.util.Objects::nonNull).min(java.util.Comparator.naturalOrder()).orElse(null),
                    e.getValue().stream().map(StoredAdvisory::publishedOn).filter(java.util.Objects::nonNull).max(java.util.Comparator.naturalOrder()).orElse(null))).toList();
        }
        /** Seeds a finished run of the given outcome that ended {@code daysAgo} days before {@code now}. */
        void seedRun(String source, String outcome, long daysAgo, Instant now) {
            Instant end = now.minus(java.time.Duration.ofDays(daysAgo));
            runs.put(key("seed-" + source + daysAgo, source), new FeedRun("seed-" + source + daysAgo, source, end.minusSeconds(60), end, outcome,
                    1, 0, 1, 0, 1, 0, null));
        }
    }
}
