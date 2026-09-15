package com.securityexpert.nexus.ui2.service.projectplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WORKER.md AC-2 and AC-3: the arithmetic against hand-computed
 * expectations (including the weight-coercion rules and the
 * track-weight-weighted overall) and every metadata-warning rule, firing on
 * a fixture that violates it and silent on one that does not. AC-1's
 * missing-file and real-repository-file behaviour is covered here too.
 */
class ProjectPlanReaderTest {

    private static Path fixture(String scenario) {
        try {
            URL url = ProjectPlanReaderTest.class.getResource("/projectplan/" + scenario + "/project");
            if (url == null) {
                throw new IllegalStateException("missing test fixture: " + scenario);
            }
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tracksOf(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("tracks");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> completedFeaturesOf(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("completed_features");
    }

    @SuppressWarnings("unchecked")
    private static List<String> warningsOf(Map<String, Object> payload) {
        return (List<String>) payload.get("metadata_warnings");
    }

    // --- AC-2: arithmetic ---------------------------------------------------

    @Test
    void featurePercentIsHundredWhenDoneWithNoCriteriaElseZero() {
        Map<String, Object> payload = new ProjectPlanReader(fixture("clean")).read();
        Map<String, Object> trackA = tracksOf(payload).get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trackAFeatures = (List<Map<String, Object>>) trackA.get("features");
        Map<String, Object> f1 = trackAFeatures.stream().filter(f -> "f1".equals(f.get("id"))).findFirst().orElseThrow();
        assertEquals(100.0, (double) f1.get("progress_percent"));
    }

    @Test
    void featurePercentWithCriteriaIsTheDoneWeightShareRoundedToOneDecimal() {
        Map<String, Object> payload = new ProjectPlanReader(fixture("clean")).read();
        Map<String, Object> trackA = tracksOf(payload).get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trackAFeatures = (List<Map<String, Object>>) trackA.get("features");
        // f2: two criteria, weight 2 each, one done -> 2/4 * 100 = 50.0
        Map<String, Object> f2 = trackAFeatures.stream().filter(f -> "f2".equals(f.get("id"))).findFirst().orElseThrow();
        assertEquals(50.0, (double) f2.get("progress_percent"));
        // f3: one criterion with a missing weight (defaults 1) done, one with a
        // non-numeric weight (coerced to 1) pending -> 1/2 * 100 = 50.0
        Map<String, Object> f3 = trackAFeatures.stream().filter(f -> "f3".equals(f.get("id"))).findFirst().orElseThrow();
        assertEquals(50.0, (double) f3.get("progress_percent"));
    }

    @Test
    void trackPercentIsTheFeatureWeightWeightedMeanOverOnlyItsOwnListedFeatures() {
        Map<String, Object> payload = new ProjectPlanReader(fixture("clean")).read();
        Map<String, Object> trackA = tracksOf(payload).get(0);
        // (f1 w3*100 + f2 w1*50 + f3 w1*50) / (3+1+1) = 400/5 = 80.0
        assertEquals(80.0, (double) trackA.get("progress_percent"));
        assertEquals(1, trackA.get("done_features"));
        assertEquals(3, trackA.get("feature_count"));

        Map<String, Object> trackB = tracksOf(payload).get(1);
        // f4 weight "oops" (non-numeric) coerces to 1, progress 100 (done, no
        // criteria); f5 weight -5 clamps to 0, progress 0 -> (1*100+0*0)/1 = 100.0
        assertEquals(100.0, (double) trackB.get("progress_percent"));
    }

    @Test
    void overallPercentIsTheTrackWeightWeightedMeanNeverFeatureCount() {
        Map<String, Object> payload = new ProjectPlanReader(fixture("clean")).read();
        // track_a weight 2 * 80.0, track_b weight "bogus" (coerced to 1) * 100.0
        // -> (160 + 100) / 3 = 86.666... -> 86.7. Weighting by feature count
        // instead (3 features vs 2) would give a different, wrong number.
        assertEquals(86.7, (double) payload.get("overall_progress_percent"));
        assertEquals(80.0, (double) payload.get("current_track_progress_percent"));
    }

    @Test
    void anEmptyDenominatorIsZeroNotAnError() {
        Map<String, Object> payload = new ProjectPlanReader(fixture("empty-tracks")).read();
        assertEquals(0.0, (double) payload.get("overall_progress_percent"));
    }

    @Test
    void doneFeaturesCountsOnlyExactlyDoneStatus() {
        Map<String, Object> payload = new ProjectPlanReader(fixture("clean")).read();
        List<Map<String, Object>> completed = completedFeaturesOf(payload);
        // f1, f4, f6, f7, f8 are "done"; f2 (in_progress), f3/f5 (planned) are not.
        assertEquals(5, completed.size());
    }

    @Test
    void completedFeaturesSortByParsedVersionNewestFirstWithUnparseableValuesLastInRegistryOrder() {
        Map<String, Object> payload = new ProjectPlanReader(fixture("clean")).read();
        List<String> order = completedFeaturesOf(payload).stream().map(f -> (String) f.get("id")).toList();
        // f6 "0.6.3" > f4 "0.6.1B.1.5" > f1 "0.5" by parsed version (a plain
        // lexical string sort would instead put f4 ahead of f6, the earlier
        // product's own defect); f7 "CON.1" and f8 (no introduced value) are
        // unparseable and sort last, in their feature_registry.json order.
        assertEquals(List.of("f6", "f4", "f1", "f7", "f8"), order);
    }

    // --- AC-3: metadata warnings ---------------------------------------------

    @Test
    void aCleanFixtureProducesNoMetadataWarnings() {
        Map<String, Object> payload = new ProjectPlanReader(fixture("clean")).read();
        assertEquals(List.of(), warningsOf(payload));
    }

    @Test
    void everyMetadataWarningRuleFiresOnAFixtureThatViolatesIt() {
        Map<String, Object> payload = new ProjectPlanReader(fixture("warnings")).read();
        List<String> warnings = warningsOf(payload);
        String joined = String.join("\n", warnings);

        assertTrue(joined.contains("Duplicate feature IDs: dup_feat"), "duplicate feature ids: " + joined);
        assertTrue(joined.contains("Track track_a references missing features: feat_missing_id"), "track missing feature: " + joined);
        assertTrue(joined.contains("Current track is not declared: track_missing"), "undeclared current track: " + joined);
        assertTrue(joined.contains("Feature feat_status_bad has unsupported status: totally_bogus"), "unsupported feature status: " + joined);
        assertTrue(joined.contains("criterion c1 has unsupported state: sideways"), "unsupported criterion state: " + joined);
        assertTrue(joined.contains("Backlog bl_bad_status has unsupported status: bogus_status"), "unsupported backlog status: " + joined);
        assertTrue(joined.contains("Build build_bad_status has unsupported status"), "unsupported build status: " + joined);
        assertTrue(joined.contains("Current build is absent from build history: build_x"), "current build absent: " + joined);
        assertTrue(joined.contains("now_next.now.build (build_y) is not the newest build history record"), "R1 now vs newest: " + joined);
        assertTrue(joined.contains("current_build (build_x) disagrees with now_next.now.build (build_y)"), "R2 current_build vs now: " + joined);
        assertTrue(joined.contains("status conflict for 'feat_status_bad_build'"), "R3 feature/build status conflict: " + joined);
        assertTrue(joined.contains("now_next.next.build (build_terminal_next) is already"), "R4 next already terminal: " + joined);
        assertTrue(joined.contains("dec_1") && joined.contains("no decide_by gate"), "R5 open decision missing decide_by: " + joined);
        assertTrue(joined.contains("backlog 'backlog_started' is still 'planned'"), "R6a backlog planned while feature started: " + joined);
        assertTrue(joined.contains("feature_registry 'feature_should_not_be_planned' is still 'planned'"), "R6b feature planned while backlog done: " + joined);
    }

    // --- AC-1: missing/unreadable sources never fail the request -----------

    @Test
    void everySourceMissingStatesSourceIsUnconfiguredAndDegradesWithoutFailure(@TempDir Path emptyDirectory) {
        Map<String, Object> payload = new ProjectPlanReader(emptyDirectory).read();

        assertEquals("1.0", payload.get("schema_version"));
        assertEquals(List.of(), tracksOf(payload));
        assertEquals(List.of(), payload.get("backlog"));
        assertEquals(List.of(), payload.get("build_history"));
        assertEquals(0, payload.get("archived_build_count"));
        assertEquals(0.0, (double) payload.get("overall_progress_percent"));

        List<String> warnings = warningsOf(payload);
        assertTrue(warnings.contains("No project-plan source is configured."));
        assertTrue(warnings.contains("roadmap.json is missing or unreadable; using an empty default."));
        assertTrue(warnings.contains("feature_registry.json is missing or unreadable; using an empty default."));
        assertTrue(warnings.contains("backlog.json is missing or unreadable; using an empty default."));
        assertTrue(warnings.contains("archive/backlog_terminal.json is missing or unreadable; using an empty default."));
        assertTrue(warnings.contains("build_history.json is missing or unreadable; using an empty default."));
    }

    @Test
    void anUnreadableMalformedFileDegradesTheSameWayAsAMissingOne(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("roadmap.json"), "{ not valid json");

        Map<String, Object> payload = new ProjectPlanReader(directory).read();

        assertTrue(warningsOf(payload).contains("roadmap.json is missing or unreadable; using an empty default."));
        assertEquals(List.of(), tracksOf(payload));
    }

    @Test
    void theActiveAndTerminalBacklogFilesAreAlwaysConcatenated() {
        Map<String, Object> payload = new ProjectPlanReader(fixture("clean")).read();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> backlog = (List<Map<String, Object>>) payload.get("backlog");
        assertEquals(2, backlog.size());
        @SuppressWarnings("unchecked")
        Map<String, Integer> counts = (Map<String, Integer>) payload.get("backlog_counts");
        assertEquals(Integer.valueOf(1), counts.get("planned"));
        assertEquals(Integer.valueOf(1), counts.get("done"));
    }

    @Test
    void productProjectionExcludesLegacyAndOperationsAndRefreshesItsRevision(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("roadmap.json"), """
                {"current_build":"java-build","now_next":{"now":{"build":"java-build"}}}
                """);
        Files.writeString(directory.resolve("feature_registry.json"), """
                {"features":[{"id":"java","status":"automated_validated"},{"id":"python","status":"done"}]}
                """);
        Files.writeString(directory.resolve("backlog.json"), """
                {"items":[{"id":"debt","status":"automated_validated"},{"id":"agent","status":"planned"},
                {"id":"new-unclassified","status":"planned"}]}
                """);
        Files.writeString(directory.resolve("build_history.json"), """
                {"builds":[{"build":"java-build","status":"automated_validated"},
                {"build":"agent-build","status":"done"}]}
                """);
        Path projection = directory.resolve("java_product_plan.json");
        Files.writeString(projection, """
                {"schema_version":"1.0","reviewed_current_build":"java-build","reviewed_at":"2026-09-15",
                "current_track":"UI2.x","tracks":[{"id":"UI2.x","feature_ids":["java"]}],
                "product_build_ids":["java-build"],
                "backlog_classifications":{"debt":"java_debt","agent":"agent_operations"},
                "converted_lessons":[{"id":"lesson","source_id":"agent","status":"historical_derived"}]}
                """);
        ProjectPlanReader reader = new ProjectPlanReader(directory);
        Map<String, Object> first = reader.read();
        assertEquals("java-build", first.get("current_product_build"));
        assertEquals(1, ((List<?>) first.get("backlog")).size());
        assertEquals(1, ((List<?>) first.get("build_history")).size());
        assertEquals(2L, first.get("excluded_backlog_count"));
        assertEquals(List.of(), first.get("completed_features"));
        assertEquals(0.0, first.get("overall_progress_percent"));
        assertEquals("UNKNOWN", ((Map<?, ?>) first.get("source_metadata")).get("freshness"));
        assertTrue(warningsOf(first).contains("Backlog classification missing or invalid: new-unclassified"));
        Files.writeString(projection, Files.readString(projection).replace("2026-09-15", "2026-09-16"));
        assertFalse(((Map<?, ?>) first.get("source_metadata")).get("revision")
                .equals(((Map<?, ?>) reader.read().get("source_metadata")).get("revision")));
        Files.writeString(projection, Files.readString(projection).replace("\"reviewed_current_build\":\"java-build\"",
                "\"reviewed_current_build\":\"old-build\""));
        assertEquals("STALE", ((Map<?, ?>) reader.read().get("source_metadata")).get("freshness"));
        Files.writeString(projection, "{ malformed");
        Map<String, Object> unavailable = reader.read();
        assertEquals(List.of(), unavailable.get("tracks"));
        assertEquals(List.of(), unavailable.get("backlog"));
        assertEquals(List.of(), unavailable.get("build_history"));
        assertEquals("UNAVAILABLE", ((Map<?, ?>) unavailable.get("source_metadata")).get("freshness"));
    }

    // --- Real repository files (AC-1) ---------------------------------------

    @Test
    void readsTheRealCheckedOutRepositoryFilesWithoutThrowing() {
        Path real = ProjectPlanReader.locateRepositoryProjectDirectory();
        assumeTrue(Files.isRegularFile(real.resolve("roadmap.json")),
                "real project/roadmap.json not found from this test's working directory; skipping");

        Map<String, Object> payload = new ProjectPlanReader(real).read();

        assertEquals("1.0", payload.get("schema_version"));
        assertFalse(tracksOf(payload).isEmpty());
        assertTrue(payload.get("current_build") != null);
        assertEquals(List.of(), warningsOf(payload), "Project authorities and explicit Java selections must reconcile");
        assertEquals("java_ui2", payload.get("product_scope"));
        assertEquals("UNKNOWN", ((Map<?, ?>) payload.get("source_metadata")).get("freshness"));
    }
}
