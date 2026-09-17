package com.securityexpert.nexus.ui2.service.projectplan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads project authorities on each request and applies the explicit Java product
 * selection in java_product_plan.json. Historical outcomes remain in their source
 * files; missing projection data never falls back to a Python product roadmap.
 * Missing or malformed files produce empty defaults and metadata warnings.
 */
public final class ProjectPlanReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> STATUS_VALUES = Set.of(
            "done", "in_progress", "planned", "blocked", "deferred",
            "complete", "complete_with_followup", "automated_validated", "real_env_validated");

    private static final Set<String> CRITERION_STATES = Set.of("done", "pending", "blocked", "deferred");

    /** build_history statuses that mean "finished"; a `next` build may not hold one. */
    private static final Set<String> TERMINAL_BUILD_STATUSES =
            Set.of("done", "complete", "automated_validated", "real_env_validated");

    /**
     * Delivery states that mean work has actually begun, for the backlog/
     * feature_registry cross-authority rule below.
     */
    private static final Set<String> STARTED_STATUSES = Set.of(
            "in_progress", "automated_validated", "real_env_validated", "done", "complete", "complete_with_followup");

    /** feature_registry and build_history use slightly different words for the same delivery state. */
    private static final Map<String, String> STATUS_EQUIVALENCE =
            Map.of("complete", "done", "complete_with_followup", "done");

    private static final Pattern LEADING_VERSION = Pattern.compile("^(\\d+(?:\\.\\d+)*)");

    private final Path projectDirectory;

    public ProjectPlanReader(Path projectDirectory) {
        this.projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory");
    }

    public Map<String, Object> read() {
        FileLoad roadmapLoad = loadObject("roadmap.json");
        FileLoad registryLoad = loadObject("feature_registry.json");
        FileLoad backlogLoad = loadObject("backlog.json");
        FileLoad backlogTerminalLoad = loadObject("archive/backlog_terminal.json");
        FileLoad historyLoad = loadObject("build_history.json");

        FileLoad productLoad = loadObject("java_product_plan.json");
        boolean productAvailable = productLoad.present()
                && "1.0".equals(productLoad.value().get("schema_version"))
                && productLoad.value().get("tracks") instanceof List<?>;
        Map<String, Object> product = productAvailable ? productLoad.value() : Map.of();
        Map<String, Object> roadmap = new LinkedHashMap<>(roadmapLoad.value());
        roadmap.put("tracks", product.getOrDefault("tracks", List.of()));
        roadmap.put("current_track", product.get("current_track"));
        roadmap.put("roadmap_notes", product.getOrDefault("notes", List.of()));
        Map<String, Object> horizon = new LinkedHashMap<>();
        horizon.put("now", asMap(roadmapLoad.value().get("now_next")).get("now"));
        horizon.put("next", product.get("next"));
        horizon.put("upcoming", product.getOrDefault("upcoming", List.of()));
        roadmap.put("now_next", horizon);
        Set<String> productFeatureIds = new LinkedHashSet<>();
        for (Object id : asList(product.get("feature_ids"))) productFeatureIds.add(asString(id));
        for (Object track : asList(product.get("tracks"))) {
            for (Object id : asList(asMap(track).get("feature_ids"))) productFeatureIds.add(asString(id));
        }
        Map<String, Object> registry = registryLoad.value();
        Map<String, Object> backlog = backlogLoad.value();
        Map<String, Object> backlogTerminal = backlogTerminalLoad.value();
        Map<String, Object> history = historyLoad.value();

        List<Map<String, Object>> features = new ArrayList<>();
        Map<String, Map<String, Object>> featureById = new LinkedHashMap<>();
        for (Object rawFeature : asList(registry.get("features"))) {
            if (!(rawFeature instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> feature = new LinkedHashMap<>(asMap(rawFeature));
            if (!productFeatureIds.contains(asString(feature.get("id")))) continue;
            feature.put("progress_percent", criterionProgress(feature));
            features.add(feature);
            featureById.put(asString(feature.get("id")), feature);
        }

        List<Map<String, Object>> tracks = new ArrayList<>();
        for (Object rawTrack : asList(roadmap.get("tracks"))) {
            if (!(rawTrack instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> track = new LinkedHashMap<>(asMap(rawTrack));
            List<Map<String, Object>> trackFeatures = new ArrayList<>();
            for (Object rawId : asList(track.get("feature_ids"))) {
                Map<String, Object> feature = featureById.get(asString(rawId));
                if (feature != null) {
                    trackFeatures.add(feature);
                }
            }
            track.put("features", trackFeatures);
            track.put("progress_percent", weightedProgress(trackFeatures.stream()
                    .map(f -> new WeightedProgress(coerceWeight(f.get("weight")), toDouble(f.get("progress_percent"))))
                    .toList()));
            track.put("done_features", (int) trackFeatures.stream()
                    .filter(f -> "done".equals(asString(f.get("status"))))
                    .count());
            track.put("feature_count", trackFeatures.size());
            tracks.add(track);
        }

        double overallProgress = weightedProgress(tracks.stream()
                .map(t -> new WeightedProgress(coerceWeight(t.get("weight")), toDouble(t.get("progress_percent"))))
                .toList());

        Object currentTrackId = roadmap.get("current_track");
        Map<String, Object> currentTrack = null;
        for (Map<String, Object> track : tracks) {
            if (Objects.equals(track.get("id"), currentTrackId)) {
                currentTrack = track;
                break;
            }
        }

        List<Map<String, Object>> completedFeatures = features.stream()
                .filter(f -> "done".equals(asString(f.get("status"))))
                .collect(Collectors.toCollection(ArrayList::new));
        completedFeatures.sort(COMPLETED_FEATURE_ORDER);

        List<Map<String, Object>> backlogItems = new ArrayList<>();
        for (Object row : asList(backlog.get("items"))) {
            if (row instanceof Map<?, ?>) {
                backlogItems.add(new LinkedHashMap<>(asMap(row)));
            }
        }
        for (Object row : asList(backlogTerminal.get("items"))) {
            if (row instanceof Map<?, ?>) {
                backlogItems.add(new LinkedHashMap<>(asMap(row)));
            }
        }

        List<Map<String, Object>> builds = new ArrayList<>();
        for (Object row : asList(history.get("builds"))) {
            if (row instanceof Map<?, ?>) {
                builds.add(new LinkedHashMap<>(asMap(row)));
            }
        }

        List<String> metadataWarnings = new ArrayList<>();
        if (Stream.of(roadmapLoad, registryLoad, backlogLoad, backlogTerminalLoad, historyLoad)
                .noneMatch(FileLoad::present)) {
            metadataWarnings.add("No project-plan source is configured.");
        }
        appendFileAvailabilityWarning(metadataWarnings, "roadmap.json", roadmapLoad.present());
        appendFileAvailabilityWarning(metadataWarnings, "feature_registry.json", registryLoad.present());
        appendFileAvailabilityWarning(metadataWarnings, "backlog.json", backlogLoad.present());
        appendFileAvailabilityWarning(metadataWarnings, "archive/backlog_terminal.json", backlogTerminalLoad.present());
        appendFileAvailabilityWarning(metadataWarnings, "build_history.json", historyLoad.present());
        metadataWarnings.addAll(computeMetadataWarnings(roadmap, features, backlogItems, builds));

        appendFileAvailabilityWarning(metadataWarnings, "java_product_plan.json", productAvailable);
        Map<String, Object> classifications = asMap(product.get("backlog_classifications"));
        List<Map<String, Object>> lessons = new ArrayList<>();
        for (Object raw : asList(product.get("converted_lessons"))) {
            Map<String, Object> lesson = new LinkedHashMap<>(asMap(raw));
            String sourceId = asString(lesson.get("source_id"));
            Map<String, Object> source = backlogItems.stream()
                    .filter(row -> sourceId.equals(asString(row.get("id")))).findFirst().orElse(Map.of());
            lesson.put("source_status", source.getOrDefault("status", "UNKNOWN"));
            if (source.isEmpty()) metadataWarnings.add("Converted lesson source is missing: " + sourceId);
            lessons.add(lesson);
        }
        Set<String> activeBacklogIds = asList(backlog.get("items")).stream()
                .map(row -> asString(asMap(row).get("id"))).collect(Collectors.toSet());
        for (Map<String, Object> row : backlogItems) {
            String id = asString(row.get("id"));
            String classification = asString(classifications.getOrDefault(id,
                    activeBacklogIds.contains(id) ? "unclassified" : "legacy_reference"));
            if (!Set.of("java_feature", "java_debt", "legacy_reference", "agent_operations").contains(classification)) {
                metadataWarnings.add("Backlog classification missing or invalid: " + id);
                classification = "unclassified";
            }
            row.put("classification", classification);
        }
        long excludedBacklogCount = backlogItems.stream()
                .filter(row -> !Set.of("java_feature", "java_debt").contains(row.get("classification"))).count();
        backlogItems.removeIf(row -> !Set.of("java_feature", "java_debt").contains(row.get("classification")));
        Set<String> productBuildIds = asList(product.get("product_build_ids")).stream()
                .map(ProjectPlanReader::asString).collect(Collectors.toSet());
        List<Map<String, Object>> productBuilds = builds.stream()
                .filter(row -> productBuildIds.contains(asString(row.get("build")))).toList();
        for (String id : productBuildIds) {
            if (productBuilds.stream().noneMatch(row -> id.equals(row.get("build")))) {
                metadataWarnings.add("Java product build source is missing: " + id);
            }
        }
        String freshness = !productAvailable ? "UNAVAILABLE"
                : Objects.equals(product.get("reviewed_current_build"), roadmap.get("current_build"))
                        ? "UNKNOWN" : "STALE";
        if ("STALE".equals(freshness)) metadataWarnings.add("Java classification review predates the current recorded build.");
        int archivedBuildCount = loadArchivedBuilds().size();
        Map<String, Object> sourceMetadata = new LinkedHashMap<>();
        sourceMetadata.put("revision", sha256(Stream.of(roadmapLoad, registryLoad, backlogLoad,
                backlogTerminalLoad, historyLoad, productLoad).map(FileLoad::digest).collect(Collectors.joining(":")) + ":" + archivedBuildCount));
        sourceMetadata.put("reviewed_at", product.get("reviewed_at"));
        sourceMetadata.put("reviewed_current_build", product.get("reviewed_current_build"));
        sourceMetadata.put("freshness", freshness);
        sourceMetadata.put("update_policy", "Read on every request. Source updates require a reviewed snapshot. Upstream and deployed-code freshness are UNKNOWN.");

        Map<String, Integer> backlogCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : backlogItems) {
            String status = asString(row.get("status"));
            if (status.isEmpty()) {
                status = "planned";
            }
            backlogCounts.merge(status, 1, Integer::sum);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", "1.0");
        payload.put("generated_at", Instant.now().toString());
        payload.put("current_build", roadmap.get("current_build"));
        payload.put("current_track", currentTrackId);
        payload.put("progress_contract", roadmap.get("progress_contract"));
        payload.put("overall_progress_percent", overallProgress);
        payload.put("current_track_progress_percent",
                currentTrack != null ? toDouble(currentTrack.get("progress_percent")) : 0.0);
        payload.put("tracks", tracks);
        payload.put("now_next", roadmap.get("now_next") != null ? roadmap.get("now_next") : Map.of());
        payload.put("roadmap_notes", roadmap.get("roadmap_notes") != null ? roadmap.get("roadmap_notes") : List.of());
        payload.put("backlog", backlogItems);
        payload.put("backlog_counts", backlogCounts);
        payload.put("completed_features", completedFeatures);
        payload.put("product_scope", "java_ui2");
        payload.put("source_metadata", sourceMetadata);
        payload.put("converted_lessons", lessons);
        payload.put("excluded_backlog_count", excludedBacklogCount);
        payload.put("current_product_build", productBuilds.isEmpty() ? null : productBuilds.get(0).get("build"));
        payload.put("build_history", productBuilds);
        payload.put("archived_build_count", archivedBuildCount);
        payload.put("metadata_warnings", metadataWarnings);
        return payload;
    }

    // --- File loading -------------------------------------------------------

    private record FileLoad(Map<String, Object> value, boolean present, String digest) {
    }

    private FileLoad loadObject(String relativePath) {
        Path path = projectDirectory.resolve(relativePath);
        try {
            byte[] bytes = Files.readAllBytes(path);
            Map<String, Object> value = MAPPER.readValue(bytes, new TypeReference<Map<String, Object>>() {
            });
            return new FileLoad(Objects.requireNonNull(value), true, sha256(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return new FileLoad(Map.of(), false, "UNAVAILABLE");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * {@code project/archive/build_history_*.json}: terminal builds beyond
     * the newest window the earlier product kept in {@code build_history.json}
     * itself. Counted only -- the screen shows current rows, never archived
     * ones -- so a single corrupt shard is skipped rather than reported, and
     * an absent archive directory is not itself a warning (it is not one of
     * the five sources named in WORKER.md AC-1).
     */
    private List<Map<String, Object>> loadArchivedBuilds() {
        return new ArrayList<>();
    }

    private static void appendFileAvailabilityWarning(List<String> warnings, String relativePath, boolean present) {
        if (!present) {
            warnings.add(relativePath + " is missing or unreadable; using an empty default.");
        }
    }

    // --- Arithmetic -----------------------------------------------------------

    private record WeightedProgress(double weight, double progressPercent) {
    }

    /**
     * A criterion weight (or a track/feature weight fed into
     * {@link #weightedProgress}) defaults to 1 when absent, is clamped to a
     * minimum of 0, and becomes 1 when the raw value cannot be read as a
     * number -- WORKER.md's weight-coercion rule, applied identically
     * everywhere a weight is read.
     */
    private static double coerceWeight(Object rawWeight) {
        if (rawWeight == null) {
            return 1.0;
        }
        double value;
        if (rawWeight instanceof Number number) {
            value = number.doubleValue();
        } else {
            try {
                value = Double.parseDouble(String.valueOf(rawWeight).trim());
            } catch (NumberFormatException e) {
                return 1.0;
            }
        }
        return Math.max(0.0, value);
    }

    private static double criterionProgress(Map<String, Object> feature) {
        List<Object> criteria = asList(feature.get("criteria"));
        if (criteria.isEmpty()) {
            return "done".equals(asString(feature.get("status"))) ? 100.0 : 0.0;
        }
        double total = 0.0;
        double done = 0.0;
        for (Object rawCriterion : criteria) {
            Map<String, Object> criterion = asMap(rawCriterion);
            double weight = coerceWeight(criterion.get("weight"));
            total += weight;
            // The earlier product's own comparison is case-insensitive here
            // (unlike the CRITERION_STATES vocabulary check below, which is
            // exact-case) -- reproduced as found, not as WORKER.md paraphrases
            // it; see this movement's SESSION_CLOSE for the named divergence.
            if ("done".equals(asString(criterion.get("state")).toLowerCase(Locale.ROOT))) {
                done += weight;
            }
        }
        return total == 0.0 ? 0.0 : round1(done / total * 100.0);
    }

    private static double weightedProgress(List<WeightedProgress> items) {
        double total = 0.0;
        double completed = 0.0;
        for (WeightedProgress item : items) {
            total += item.weight();
            completed += item.weight() * item.progressPercent() / 100.0;
        }
        return total == 0.0 ? 0.0 : round1(completed / total * 100.0);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    // --- Completed-feature ordering (earlier product defect #2 fix) -----------

    /**
     * The earlier product sorted completed features by their {@code
     * introduced} field as a plain descending string comparison, which
     * orders e.g. {@code "0.6.1B.1.5"} ahead of {@code "0.6.3"} because
     * {@code '1' < '3'} lexically at that position -- backwards, since
     * 0.6.3 shipped after 0.6.1B.1.5. This comparator parses the leading
     * dot-separated run of decimal digits at the start of the string (e.g.
     * {@code "0.6.1B.1.5"} parses its leading {@code "0.6.1"}) into a
     * numeric-tuple key, compared component-wise, with the full raw string
     * as a final tiebreaker for two values sharing the same leading run
     * (so {@code "0.5"} and {@code "0.5 Final"} still order deterministically
     * against each other).
     *
     * <p>Documented fallback: a value with no leading digit (e.g. {@code
     * "CON.1"}, {@code "Local architecture health"}) or no value at all is
     * unparseable. Every unparseable value sorts after every parseable one,
     * and unparseable values keep their {@code feature_registry.json}
     * declaration order relative to each other (this comparator returns 0
     * for two unparseable values, and Java's sort is stable) rather than
     * being guessed at.</p>
     */
    private static final Comparator<Map<String, Object>> COMPLETED_FEATURE_ORDER = (a, b) -> {
        IntroducedKey ka = introducedKey(a.get("introduced"));
        IntroducedKey kb = introducedKey(b.get("introduced"));
        if (ka.parseable() != kb.parseable()) {
            return ka.parseable() ? -1 : 1;
        }
        if (!ka.parseable()) {
            return 0;
        }
        int cmp = compareVersions(kb.version(), ka.version());
        if (cmp != 0) {
            return cmp;
        }
        return kb.raw().compareTo(ka.raw());
    };

    private record IntroducedKey(boolean parseable, List<Integer> version, String raw) {
    }

    private static IntroducedKey introducedKey(Object rawIntroduced) {
        String value = asString(rawIntroduced);
        Matcher matcher = LEADING_VERSION.matcher(value);
        if (!matcher.find()) {
            return new IntroducedKey(false, List.of(), value);
        }
        List<Integer> version = new ArrayList<>();
        for (String segment : matcher.group(1).split("\\.")) {
            version.add(Integer.parseInt(segment));
        }
        return new IntroducedKey(true, version, value);
    }

    private static int compareVersions(List<Integer> a, List<Integer> b) {
        int length = Math.max(a.size(), b.size());
        for (int i = 0; i < length; i++) {
            int av = i < a.size() ? a.get(i) : 0;
            int bv = i < b.size() ? b.get(i) : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    // --- Metadata warnings ------------------------------------------------

    private static List<String> computeMetadataWarnings(Map<String, Object> roadmap, List<Map<String, Object>> features,
            List<Map<String, Object>> backlogItems, List<Map<String, Object>> builds) {
        List<String> warnings = new ArrayList<>();

        List<String> nonEmptyIds = features.stream().map(f -> asString(f.get("id"))).filter(s -> !s.isEmpty()).toList();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (String id : nonEmptyIds) {
            if (!seen.add(id)) {
                duplicates.add(id);
            }
        }
        if (!duplicates.isEmpty()) {
            warnings.add("Duplicate feature IDs: " + duplicates.stream().sorted().collect(Collectors.joining(", ")));
        }

        Set<String> featureIds = new LinkedHashSet<>(nonEmptyIds);
        Set<String> trackIds = new LinkedHashSet<>();
        for (Object rawTrack : asList(roadmap.get("tracks"))) {
            Map<String, Object> track = asMap(rawTrack);
            String trackId = asString(track.get("id"));
            if (!trackId.isEmpty()) {
                trackIds.add(trackId);
            }
            List<String> missing = new ArrayList<>();
            for (Object rawId : asList(track.get("feature_ids"))) {
                String fid = asString(rawId);
                if (!featureIds.contains(fid)) {
                    missing.add(fid);
                }
            }
            if (!missing.isEmpty()) {
                warnings.add("Track " + (trackId.isEmpty() ? "<unnamed>" : trackId)
                        + " references missing features: " + String.join(", ", missing));
            }
        }

        String currentTrack = asString(roadmap.get("current_track"));
        if (!currentTrack.isEmpty() && !trackIds.contains(currentTrack)) {
            warnings.add("Current track is not declared: " + currentTrack);
        }

        for (Map<String, Object> feature : features) {
            String status = asString(feature.get("status"));
            if (!status.isEmpty() && !STATUS_VALUES.contains(status)) {
                warnings.add("Feature " + unnamedOr(feature.get("id")) + " has unsupported status: " + status);
            }
            for (Object rawCriterion : asList(feature.get("criteria"))) {
                Map<String, Object> criterion = asMap(rawCriterion);
                String state = asString(criterion.get("state"));
                if (!state.isEmpty() && !CRITERION_STATES.contains(state)) {
                    warnings.add("Feature " + unnamedOr(feature.get("id")) + " criterion " + unnamedOr(criterion.get("id"))
                            + " has unsupported state: " + state);
                }
            }
        }

        for (Map<String, Object> item : backlogItems) {
            String status = asString(item.get("status"));
            if (status.isEmpty()) {
                status = "planned";
            }
            if (!STATUS_VALUES.contains(status)) {
                warnings.add("Backlog " + unnamedOr(item.get("id")) + " has unsupported status: " + status);
            }
        }

        for (Map<String, Object> build : builds) {
            String status = asString(build.get("status"));
            if (!status.isEmpty() && !STATUS_VALUES.contains(status)) {
                warnings.add("Build " + unnamedOr(build.get("build")) + " has unsupported status: " + status);
            }
        }

        String currentBuild = asString(roadmap.get("current_build"));
        if (!currentBuild.isEmpty() && builds.stream().noneMatch(b -> currentBuild.equals(asString(b.get("build"))))) {
            warnings.add("Current build is absent from build history: " + currentBuild);
        }

        warnings.addAll(crossAuthorityWarnings(roadmap, features, builds, backlogItems));
        return warnings;
    }

    /**
     * The rules above check each file against itself. These compare
     * {@code roadmap.json}, {@code feature_registry.json}, {@code
     * backlog.json} and {@code build_history.json} against each other --
     * WORKER.md's six cross-file rules, R1-R6 below in the same order the
     * earlier product declared them.
     */
    private static List<String> crossAuthorityWarnings(Map<String, Object> roadmap, List<Map<String, Object>> features,
            List<Map<String, Object>> builds, List<Map<String, Object>> backlogItems) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> nowNext = asMap(roadmap.get("now_next"));
        Map<String, Object> now = asMap(nowNext.get("now"));
        Map<String, Object> next = asMap(nowNext.get("next"));

        Map<String, String> buildStatus = new LinkedHashMap<>();
        for (Map<String, Object> build : builds) {
            String key = asString(build.get("build"));
            if (!key.isEmpty()) {
                buildStatus.putIfAbsent(key, asString(build.get("status")));
            }
        }

        String newest = builds.isEmpty() ? "" : asString(builds.get(0).get("build"));
        String nowBuild = asString(now.get("build"));

        // R1 -- build_history.json is newest-first by its own record_contract,
        // so its head record IS the current build; roadmap must agree.
        if (!newest.isEmpty() && !nowBuild.isEmpty() && !newest.equals(nowBuild)) {
            warnings.add("roadmap now_next.now.build (" + nowBuild + ") is not the newest build history record ("
                    + newest + "); one of the two is stale");
        }

        // R2 -- current_build and now_next.now.build are two fields claiming
        // the same fact in the same file.
        String currentBuild = asString(roadmap.get("current_build"));
        if (!currentBuild.isEmpty() && !nowBuild.isEmpty() && !currentBuild.equals(nowBuild)) {
            warnings.add("roadmap current_build (" + currentBuild + ") disagrees with now_next.now.build (" + nowBuild + ")");
        }

        // R3 -- an id that is both a feature and a build must carry one status.
        Map<String, String> featureStatus = new LinkedHashMap<>();
        for (Map<String, Object> feature : features) {
            featureStatus.put(asString(feature.get("id")), asString(feature.get("status")));
        }
        for (Map.Entry<String, String> entry : featureStatus.entrySet()) {
            String fid = entry.getKey();
            String fstatus = entry.getValue();
            if (fid.isEmpty() || !buildStatus.containsKey(fid)) {
                continue;
            }
            String bstatus = buildStatus.get(fid);
            if (fstatus.isEmpty() || bstatus.isEmpty()) {
                continue;
            }
            if (!canonicalStatus(fstatus).equals(canonicalStatus(bstatus))) {
                warnings.add("status conflict for '" + fid + "': feature_registry says '" + fstatus
                        + "', build_history says '" + bstatus + "'");
            }
        }

        // R4 -- "next" cannot already be finished.
        String nextBuild = asString(next.get("build"));
        if (!nextBuild.isEmpty() && TERMINAL_BUILD_STATUSES.contains(canonicalStatus(buildStatus.getOrDefault(nextBuild, "")))) {
            warnings.add("roadmap now_next.next.build (" + nextBuild + ") is already '" + buildStatus.get(nextBuild)
                    + "' in build history");
        }

        // R5 -- every open decision must name the gate that forces it.
        for (Object rawDecision : asList(roadmap.get("open_decisions"))) {
            Map<String, Object> decision = asMap(rawDecision);
            if (!"open".equals(asString(decision.get("status")))) {
                continue;
            }
            if (asString(decision.get("decide_by")).isBlank()) {
                warnings.add("open decision " + unnamedOr(decision.get("id")) + " has no decide_by gate");
            }
        }

        // R6 -- backlog and feature_registry describe different things and
        // are not required to match, but a backlog item cannot still be
        // 'planned' once its feature has started, and a feature cannot still
        // be 'planned' once its backlog item is done.
        Map<String, String> backlogStatus = new LinkedHashMap<>();
        for (Map<String, Object> item : backlogItems) {
            backlogStatus.put(asString(item.get("id")), asString(item.get("status")));
        }
        for (Map.Entry<String, String> entry : featureStatus.entrySet()) {
            String fid = entry.getKey();
            String fstatus = entry.getValue();
            if (fid.isEmpty() || !backlogStatus.containsKey(fid)) {
                continue;
            }
            String bstatus = backlogStatus.get(fid);
            if ("planned".equals(bstatus) && STARTED_STATUSES.contains(fstatus)) {
                warnings.add("backlog '" + fid + "' is still 'planned' while feature_registry says '" + fstatus + "'");
            }
            if ("planned".equals(fstatus) && "done".equals(canonicalStatus(bstatus))) {
                warnings.add("feature_registry '" + fid + "' is still 'planned' while backlog says '" + bstatus + "'");
            }
        }

        return warnings;
    }

    private static String canonicalStatus(String status) {
        return STATUS_EQUIVALENCE.getOrDefault(status, status);
    }

    private static String unnamedOr(Object id) {
        String value = asString(id);
        return value.isEmpty() ? "<unnamed>" : value;
    }

    // --- Small JSON-tree helpers --------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    /**
     * Best-effort repository-root discovery for the default deployment
     * (WORKER.md deployment_direction: local validation only). Walks up from
     * the JVM's working directory looking for a sibling {@code project/
     * roadmap.json} -- this works whether the process is started from the
     * repository root or from a Gradle subproject directory. When no such
     * ancestor exists (as in a container image that ships the jar alone),
     * the returned path simply does not exist, and every source degrades to
     * its documented empty-default-plus-warning outcome rather than failing
     * the request.
     */
    public static Path locateRepositoryProjectDirectory() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 8 && cursor != null; depth++) {
            Path candidate = cursor.resolve("project");
            if (Files.isRegularFile(candidate.resolve("roadmap.json"))) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        return Path.of("").toAbsolutePath().resolve("project");
    }
}
