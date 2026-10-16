package com.securityexpert.nexus.ui2.packetanalysis.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Evidence;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Port of reference {@code tests/test_reference.py} and {@code tests/test_aggregates.py} reference cases
 * (negative cases first).
 */
class ReferenceCompareTest {

    @TempDir
    Path tmpDir;

    private static Map<String, Object> item(Map<String, Object> check, Object expected, String metric) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "one");
        m.put("description", "Fixture metric");
        m.put("metric", metric);
        m.put("expected", expected);
        m.put("check", check);
        return m;
    }

    static Stream<Arguments> invalidReferenceCases() {
        return Stream.of(
                Arguments.of(Map.of("abs_tol", -1), "$.value"),
                Arguments.of(Map.of("max", 1, "min", 2), "$.value"),
                Arguments.of(Map.of("eval", "bad"), "$.value"),
                Arguments.of(Map.of("max", 1), "$..value"),
                Arguments.of(Map.of("max", 1), "$.items[*]"));
    }

    @ParameterizedTest
    @MethodSource("invalidReferenceCases")
    void invalidReferenceRejected(Map<String, Object> check, String metric) {
        Map<String, Object> ref = Map.of("items", List.of(item(check, 10, metric)));
        assertThrows(IllegalArgumentException.class, () -> ReferenceCompare.Reference.modelValidate(ref));
    }

    @Test
    void missingIsNotComputedNeverFail() {
        Map<String, Object> ref = Map.of("items", List.of(item(Map.of("max", 1), 10, "$.value")));
        Map<String, Object> r1 = ReferenceCompare.compareReference(Map.of(), ref);
        assertEquals(1L, ((Map<?, ?>) r1.get("counts")).get("not-computed"));
        assertEquals(0L, ((Map<?, ?>) r1.get("counts")).get("fail"));

        Map<String, Object> nullVal = new LinkedHashMap<>();
        nullVal.put("value", null);
        Map<String, Object> r2 = ReferenceCompare.compareReference(nullVal, ref);
        assertEquals(1L, ((Map<?, ?>) r2.get("counts")).get("not-computed"));
        assertEquals(0L, ((Map<?, ?>) r2.get("counts")).get("fail"));
    }

    @Test
    void missingHostSilenceIsNotComputed() {
        Map<String, Object> hostPath = new LinkedHashMap<>();
        hostPath.put("rx_share", null);
        hostPath.put("max_silence_ms", null);
        Map<String, Object> report = Map.of("host_path", hostPath);
        Map<String, Object> ref = Map.of("items", List.of(Map.of(
                "id", "gap",
                "description", "No host capture",
                "metric", "$.host_path.max_silence_ms",
                "expected", 0,
                "check", Map.of("max", 0))));
        Map<String, Object> result = ReferenceCompare.compareReference(report, ref);
        List<?> items = (List<?>) result.get("items");
        assertEquals("not-computed", ((Map<?, ?>) items.get(0)).get("status"));
    }

    @Test
    void compareCliRejectsMissingFlagsAndUnanalyzedProject() {
        assertThrows(IllegalArgumentException.class, () ->
                CompareCli.run(new PrintStream(new ByteArrayOutputStream()), "--project", tmpDir.toString()));

        ProjectStore empty = ProjectStore.open(tmpDir.resolve("empty"), ProjectStore.Settings.defaults(null));
        assertThrows(IllegalArgumentException.class, () ->
                CompareCli.run(new PrintStream(new ByteArrayOutputStream()),
                        "--project", empty.path().toString(),
                        "--template", tmpDir.resolve("tpl.json").toString()));
    }

    static Stream<Arguments> referenceCheckCases() {
        return Stream.of(
                Arguments.of(Map.of("abs_tol", 1), 10, 11, "pass"),
                Arguments.of(Map.of("abs_tol", 1), 10, 12, "fail"),
                Arguments.of(Map.of("rel_tol_pct", 5), 100, 104, "pass"),
                Arguments.of(Map.of("rel_tol_pct", 1), 0, 1, "fail"),
                Arguments.of(Map.of("max", 10), 10, 11, "fail"),
                Arguments.of(Map.of("min", 10), 10, 10, "pass"),
                Arguments.of(Map.of("equals", "done"), "done", "done", "pass"),
                Arguments.of(Map.of("window_tol_s", 1), List.of(20, 45), Map.of("start", 19, "end", 46), "pass"),
                Arguments.of(Map.of("window_tol_s", 1), List.of(20, 45), Map.of("start", 18, "end", 46), "fail"));
    }

    @ParameterizedTest
    @MethodSource("referenceCheckCases")
    @SuppressWarnings("unchecked")
    void referenceChecks(Map<String, Object> check, Object expected, Object value, String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("value", value);
        Map<String, Object> r = ReferenceCompare.compareReference(
                data, Map.of("items", List.of(item(check, expected, "$.value"))));
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.get("items");
        assertEquals(status, items.get(0).get("status"));
        assertEquals(value, items.get(0).get("value"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pathsTemplateAndSemanticDiff() {
        Map<String, Object> findingA = new LinkedHashMap<>();
        findingA.put("id", "a");
        findingA.put("type", "loss");
        findingA.put("hop", "B");
        findingA.put("metrics", Map.of("count", 2));
        findingA.put("evidence", Collections.emptyList());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("flow_count", 50);
        report.put("window", Map.of("common_start", 20, "common_end", 45));
        report.put("findings", List.of(findingA));
        report.put("generated_at", "a");

        Map<String, Object> findingB = new LinkedHashMap<>(findingA);
        findingB.put("id", "b");
        Map<String, Object> before = new LinkedHashMap<>(report);
        before.put("generated_at", "b");
        before.put("findings", List.of(findingB));

        assertTrue(((List<?>) ReferenceCompare.compareRuns(Map.of("report", before), report).get("metrics")).isEmpty());
        assertTrue(((List<?>) ReferenceCompare.compareRuns(before, report).get("findings")).isEmpty());

        Map<String, Object> findingChanged = new LinkedHashMap<>(findingA);
        findingChanged.put("metrics", Map.of("count", 3));
        Map<String, Object> after = new LinkedHashMap<>(report);
        after.put("flow_count", 51);
        after.put("findings", List.of(findingChanged));

        Map<String, Object> diff = ReferenceCompare.compareRuns(before, after);
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) diff.get("metrics");
        List<Map<String, Object>> findings = (List<Map<String, Object>>) diff.get("findings");
        assertEquals("$.flow_count", metrics.get(0).get("metric"));
        assertEquals("changed", findings.get(0).get("status"));

        Map<String, Object> template = ReferenceCompare.referenceTemplate(report);
        ReferenceCompare.Reference.modelValidate(template);

        Map<String, Object> nestedCheck = ReferenceCompare.compareReference(
                report, Map.of("items", List.of(item(Map.of("equals", 3), 3, "$.a[0]['b']"))));
        assertEquals(1L, ((Map<?, ?>) nestedCheck.get("counts")).get("not-computed"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void compareCliEndToEnd() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("flow_count", 50);
        report.put("findings", Collections.emptyList());

        ProjectStore project = ProjectStore.open(tmpDir.resolve("project"), ProjectStore.Settings.defaults(null));
        try (var db = project.connect()) {
            project.set(db, "report", report);
        }
        Path ref = tmpDir.resolve("ref.json");
        Files.writeString(ref, PyJsonWriter.dumps(
                Map.of("items", List.of(item(Map.of("max", 60), 50, "$.flow_count"))), true),
                StandardCharsets.UTF_8);

        Path against = tmpDir.resolve("against.json");
        Files.writeString(against, PyJsonWriter.dumps(report, true), StandardCharsets.UTF_8);

        Path template = tmpDir.resolve("template.json");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CompareCli.run(new PrintStream(buf, true, StandardCharsets.UTF_8),
                "--project", project.path().toString(),
                "--reference", ref.toString(),
                "--against", against.toString(),
                "--template", template.toString());

        Map<String, Object> out = (Map<String, Object>) Evidence.loads(buf.toString(StandardCharsets.UTF_8));
        assertEquals(1L, ((Number) ((Map<String, Object>) out.get("counts")).get("pass")).longValue());
        assertEquals(0L, ((Number) ((Map<String, Object>) out.get("diff")).get("metric_difference_count")).longValue());
        assertTrue(Files.exists(template));
        Map<String, Object> tpl = (Map<String, Object>) Evidence.loads(Files.readString(template, StandardCharsets.UTF_8));
        ReferenceCompare.Reference.modelValidate(tpl);
        assertFalse(((List<?>) tpl.get("items")).isEmpty());

        // Template file is opened exclusively and must not be overwritten.
        assertThrows(FileAlreadyExistsException.class, () ->
                CompareCli.run(new PrintStream(new ByteArrayOutputStream()),
                        "--project", project.path().toString(),
                        "--template", template.toString()));
    }
}
