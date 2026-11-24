package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.policy.CutoverRepository;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.api.CutoverAssessmentController;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathReasons;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.CutoverPathTargets;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.service.security.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyLookupTest.pan;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Step 1 acceptance criteria of the ACI segmentation design (section 11) pinned as labelled tests
 * ({@code docs/design/ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md}; backlog {@code aci_segmentation_step1}). Every test
 * display name starts with its criterion id. Synthetic documentation ranges only; Palo Alto and Check Point targets only.
 *
 * <table>
 * <caption>Criterion, test, status</caption>
 * <tr><th>Id</th><th>Test</th><th>Status</th></tr>
 * <tr><td>ACI-1</td><td>{@link #aci1EveryEngineStringAvoidsTheForbiddenWords}, {@link #aci1EveryOutcomeCarriesACoverageListAndNoForbiddenWord};
 *     wording module: {@code frontend/tests/CutoverAcceptanceWording.test.ts}</td>
 *     <td>PROVEN (no export or report text exists in step 1: the stored summary, the screen and the CLI are the only texts)</td></tr>
 * <tr><td>ACI-4</td><td>{@link #aci4AnOverriddenPaloAltoTargetWithoutBothZonesIsNeverDecided},
 *     {@link #aci4TheControllerStoresNotDecidedForEveryTupleWithoutAZonePair}</td>
 *     <td>PROVEN (override targets only, as design section 0 decides; a routed path takes its zones from the path engine)</td></tr>
 * <tr><td>ACI-5</td><td>{@link #aci5AStoredAssessmentHoldsNoRawRowHeaderMappingOrFileContent},
 *     {@link #aci5TheMigrationRefusesRawContentKeysInTheSummaryAndInEveryFlowRow}</td>
 *     <td>PROVEN for the engine, the service and the migration text. The database refusal itself needs PostgreSQL:
 *     {@code CutoverRepositoryDatabaseTest} proves the {@code rows} key of the summary; the complete key list over both
 *     tables is in the integration-tests class {@code CutoverNoRawContentTest}, which was not run here (no container
 *     runtime in this lane)</td></tr>
 * <tr><td>ACI-6</td><td>{@link #aci6AiviewReadsOfSegmentsAssessmentAndFlowsShowMaskedValuesOnly}; CLI output: {@code CutoverCliParityTest} in the
 *     cli module</td>
 *     <td>PROVEN for segments, assessment, flows and CLI output. Device pseudonyms: cutover answers carry opaque device ids and
 *     rule names only (rule names are pseudonymised); APIC pseudonyms: OUT OF SCOPE (APIC is not built)</td></tr>
 * <tr><td>ACI-7</td><td>{@link #aci7OverTheTupleFileRowSizeBlockAndPageLimitsTheRunStopsWithANamedReason},
 *     {@link #aci7TheTimeLimitNamesTheUndecidedFlowsAndTruncatesNone}</td><td>PROVEN</td></tr>
 * <tr><td>ACI-8</td><td>{@link #aci8AnAssessmentIsStaleWhenTheSegmentTheScopeOrAPolicySnapshotChanges},
 *     {@link #aci8ARoutedAssessmentIsStaleWhenTheHeldTopologyChanges}</td>
 *     <td>PROVEN (the state is the {@code stale} flag with named reasons; the screen prints "STALE")</td></tr>
 * <tr><td>ACI-16</td><td>{@code CutoverCliParityTest} in the cli module</td><td>PROVEN</td></tr>
 * <tr><td>ACI-2, ACI-3</td><td>none here</td><td>OUT OF SCOPE (no upper-bound pass on main; a queued lane makes ASA/FortiGate targets evaluable)</td></tr>
 * <tr><td>ACI-9 .. ACI-15</td><td>none</td><td>OUT OF SCOPE (APIC and the watch view are not built)</td></tr>
 * </table>
 */
class CutoverAcceptanceCriteriaTest {
    static final String SEGMENT = "00000000-0000-4000-8000-000000000001";
    static final String ASSESSMENT = "00000000-0000-4000-8000-0000000000a1";
    static final String DEVICE = "device-synthetic-01";
    static final String MAP = "\"mappings\":[{\"source\":\"src\",\"destination\":\"dst\",\"port\":\"port\",\"protocol\":\"proto\"}]";
    static final String HEADER = "src,dst,port,proto\r\n";
    static final Pattern FORBIDDEN = Pattern.compile("\\b(safe|ready|pass|passed|passes|healthy|symmetric|green)\\b", Pattern.CASE_INSENSITIVE);
    static final ObjectMapper JSON = new ObjectMapper();

    final CutoverRepository repository = mock(CutoverRepository.class);
    final PolicyQueryService policies = mock(PolicyQueryService.class);
    final RbacEvaluator rbac = mock(RbacEvaluator.class);
    final CutoverPathTargets paths = mock(CutoverPathTargets.class);
    final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer("k".repeat(32).getBytes(StandardCharsets.UTF_8));
    final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
    final CutoverAssessmentService service = new CutoverAssessmentService(repository, policies, new ObjectMapper());
    final CutoverAssessmentController controller = new CutoverAssessmentController(service, paths, rbac, names, ips, new PolicyBulkhead());

    // ---- fixtures ---------------------------------------------------------------------------------------------

    static PolicySnapshot target(String vendor, String id, String collectedAt, PolicySnapshot base) {
        var m = base.metadata();
        return new PolicySnapshot(new Metadata(id, m.sourceId(), m.sourceName(), vendor, m.containerId(), m.containerName(), m.name(), collectedAt,
            m.artefactRef(), List.of(new Target(DEVICE, "FW-TANGO-04", "", "IN_SYNC"))), base.sections(), base.objects());
    }
    static PolicySnapshot panTarget(String collectedAt, Rule... rules) { return target("PAN", "p1", collectedAt, pan(rules)); }

    void segment(int revision) {
        when(repository.findSegment(SEGMENT)).thenReturn(Optional.of("{\"segmentId\":\"" + SEGMENT + "\",\"name\":\"SEG-REAL-NAME\",\"revision\":" + revision
            + ",\"blocks\":[\"192.0.2.0/24\"],\"peerBlocks\":[\"198.51.100.0/24\"]}"));
    }
    void allow(PolicySnapshot snapshot, String hint) {
        when(rbac.evaluateAny(anyString(), anySet(), any())).thenReturn(new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.of("test"), Optional.empty(), Optional.empty()));
        segment(1);
        when(repository.deviceVendor(DEVICE)).thenReturn(Optional.of(hint));
        when(policies.find("p1")).thenReturn(Optional.of(snapshot));
    }
    static String spec(String segmentZone, String peerZone, String mappings) {
        return "{\"targetOverride\":{\"deviceId\":\"" + DEVICE + "\",\"policyId\":\"p1\",\"segmentZone\":\"" + segmentZone + "\",\"peerZone\":\"" + peerZone + "\"}," + mappings + "}";
    }
    static MockHttpServletRequest request(String method, String path, boolean masked) {
        var request = new MockHttpServletRequest(method, path);
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-actor");
        request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, masked);
        return request;
    }
    static MockHttpServletRequest upload(String spec, String... files) {
        var request = request("POST", "/api/v2/cutover/segments/" + SEGMENT + "/assessments", false);
        request.setContentType("multipart/form-data; boundary=SyntheticBoundary");
        var content = new StringBuilder("--SyntheticBoundary\r\nContent-Disposition: form-data; name=\"spec\"\r\n\r\n" + spec + "\r\n");
        for (String file : files)
            content.append("--SyntheticBoundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.csv\"\r\nContent-Type: text/csv\r\n\r\n")
                .append(file).append("\r\n");
        content.append("--SyntheticBoundary--\r\n");
        request.setContent(content.toString().getBytes(StandardCharsets.UTF_8));
        return request;
    }
    /** Runs one assessment through the controller and returns what it stored. */
    CutoverRepository.Assessment run(String spec, String... files) {
        var response = controller.assess(SEGMENT, upload(spec, files));
        assertEquals(201, response.getStatusCode().value(), String.valueOf(response.getBody()));
        var stored = ArgumentCaptor.forClass(CutoverRepository.Assessment.class);
        verify(repository).saveAssessment(stored.capture());
        return stored.getValue();
    }
    static Map<String, Object> map(String json) throws Exception { return JSON.readValue(json, new TypeReference<Map<String, Object>>() {}); }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> list(Object value) { return (List<Map<String, Object>>) value; }
    static List<String> codes(Map<String, Object> summary) { return list(summary.get("coverage")).stream().map(c -> (String) c.get("code")).toList(); }
    /** Every string in a JSON tree, keys and values. */
    static void strings(Object node, List<String> out) {
        if (node instanceof Map<?, ?> m) m.forEach((k, v) -> { out.add(String.valueOf(k)); strings(v, out); });
        else if (node instanceof Collection<?> c) c.forEach(v -> strings(v, out));
        else if (node instanceof String s) out.add(s);
    }
    static void keys(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> m) m.forEach((k, v) -> { out.add(String.valueOf(k)); keys(v, out); });
        else if (node instanceof Collection<?> c) c.forEach(v -> keys(v, out));
    }
    static void assertNoForbiddenWord(String text) {
        String words = text.replace('_', ' ');
        assertFalse(FORBIDDEN.matcher(words).find(), "forbidden word in: " + text);
    }

    // ---- ACI-1 ------------------------------------------------------------------------------------------------

    @Test @DisplayName("ACI-1 no verdict, state, flag, reason, headline or coverage code the engine can produce carries a forbidden word")
    void aci1EveryEngineStringAvoidsTheForbiddenWords() {
        List<String> vocabulary = new ArrayList<>();
        // The path engine's return-walk statuses sit in the same constants class as its reasons and so in the aiview allowlist
        // CutoverPathTargets.REASONS, but a routed decision only ever carries the forward path's reason or verdict (see
        // CutoverPathTargets.decision), so they are not text a cutover verdict can show; ASYMMETRIC_RETURN stays in the scan.
        Set<String> returnStatuses = Set.of(PathReasons.SYMMETRIC, PathReasons.ASYMMETRIC, PathReasons.INCOMPLETE, PathReasons.NOT_COMPUTED);
        for (Collection<String> set : List.of(CutoverAssessment.READINESS, CutoverAssessment.DECISIONS, CutoverAssessment.STATUSES, CutoverAssessment.DIRECTIONS,
                CutoverAssessment.FLAGS, CutoverAssessment.COVERAGE, CutoverAssessment.REASONS, CutoverAssessment.CONSTRAINT_KINDS, CutoverAssessment.TARGET_SOURCES,
                CutoverAssessment.DROP_ACTIONS, CutoverPrivacy.STALE_REASONS, PolicyLookup.REASONS, PolicyHygiene.REASONS,
                CutoverPathTargets.REASONS.stream().filter(reason -> !returnStatuses.contains(reason)).toList(), CutoverPathTargets.HOP_VERDICTS))
            vocabulary.addAll(set);
        vocabulary.addAll(List.of(CutoverAssessment.HEADLINE_BLOCKERS, CutoverAssessment.HEADLINE_NOT_DECIDED, CutoverAssessment.HEADLINE_NO_BLOCKERS,
            CutoverAssessment.HEADLINE_NO_EVIDENCE, CutoverAssessment.ENGINE_VERSION));
        assertTrue(vocabulary.size() > 60, "the scan must see the whole vocabulary");
        for (String text : vocabulary) assertNoForbiddenWord(text);
        // The guard has teeth: the words it exists for are caught.
        for (String bad : List.of("Cutover is safe", "READY_TO_CUT", "all checks pass", "healthy", "symmetric paths", "green"))
            assertTrue(FORBIDDEN.matcher(bad.replace('_', ' ')).find(), bad);
        assertFalse(FORBIDDEN.matcher("ALREADY_DROPPED_TODAY ASYMMETRY".replace('_', ' ')).find());
    }

    @Test @DisplayName("ACI-1 every outcome, even the best one, carries a non-empty coverage list and no forbidden word, stored, served and masked")
    void aci1EveryOutcomeCarriesACoverageListAndNoForbiddenWord() throws Exception {
        var allowAll = panTarget("2026-11-13T00:00:00Z", rule("r1", "any", "any", "allow", true));
        var denyAll = panTarget("2026-11-13T00:00:00Z", rule("r1", "any", "any", "deny", true));
        var cp = target("CP", "p1", "2026-11-13T00:00:00Z", sections("CP", cpSection("s1", null, edit(cpRule("r1", "Accept"), cell("host"), Map.of()))));
        var inScope = HEADER + "192.0.2.8,198.51.100.8,443,tcp\r\n";
        var scenarios = new LinkedHashMap<String, Object[]>();   // readiness -> {snapshot, hint, zones, csv}
        scenarios.put("NO_BLOCKERS_IN_EVALUATED_FLOWS", new Object[] {allowAll, "palo_alto", "Z-SEG", inScope});
        scenarios.put("BLOCKERS_FOUND", new Object[] {denyAll, "palo_alto", "Z-SEG", inScope});
        scenarios.put("NOT_DECIDED", new Object[] {allowAll, "palo_alto", "", inScope});
        scenarios.put("NO_EVIDENCE", new Object[] {allowAll, "palo_alto", "Z-SEG", HEADER + "203.0.113.8,198.51.100.8,443,tcp\r\n"});
        for (var scenario : scenarios.entrySet()) {
            var fresh = new CutoverAcceptanceCriteriaTest();
            Object[] s = scenario.getValue();
            fresh.allow((PolicySnapshot) s[0], (String) s[1]);
            String zone = (String) s[2];
            var stored = fresh.run(spec(zone, zone.isEmpty() ? "" : "Z-PEER", MAP), (String) s[3]);
            assertEquals(scenario.getKey(), stored.readiness());
            var summary = map(stored.summary());
            assertEquals(scenario.getKey(), summary.get("readiness"));
            assertTrue(codes(summary).containsAll(List.of("LOG_PERIOD", "LOGGING_COMPLETENESS_UNKNOWN", "UNLOGGED_FLOWS_INVISIBLE")), scenario.getKey());
            var texts = new ArrayList<String>();
            strings(summary, texts); stored.rows().forEach(row -> { try { strings(map(row), texts); } catch (Exception e) { throw new IllegalStateException(e); } });
            texts.forEach(CutoverAcceptanceCriteriaTest::assertNoForbiddenWord);
        }
        // The best verdict is neutral wording: it speaks only of the evaluated flows and points at the coverage list.
        assertTrue(CutoverAssessment.HEADLINE_NO_BLOCKERS.contains("Coverage is incomplete: read the coverage list."));
        // Check Point targets and a routed (path) target follow the same rule.
        var withCp = new CutoverAcceptanceCriteriaTest();
        withCp.allow(cp, "check_point");
        var cpSummary = map(withCp.run(spec("", "", MAP), HEADER + "192.0.2.9,198.51.100.8,443,tcp\r\n").summary());
        assertTrue(codes(cpSummary).containsAll(List.of("LOG_PERIOD", "LOGGING_COMPLETENESS_UNKNOWN", "UNLOGGED_FLOWS_INVISIBLE")));
        var routed = new CutoverAcceptanceCriteriaTest();
        routed.segment(1);
        var prepared = routed.service.prepare(SEGMENT, new CutoverAssessmentService.Spec(null, null, null, null, CutoverAssessmentServiceTest.MAPPINGS),
            Optional.of(CutoverAssessmentServiceTest.routed("WOULD_ALLOW")), "topology-synthetic-01");
        var routedResult = routedResult(prepared);
        assertEquals("NO_BLOCKERS_IN_EVALUATED_FLOWS", routedResult.readiness());
        assertTrue(codes(routedResult.summary()).containsAll(List.of("LOG_PERIOD", "LOGGING_COMPLETENESS_UNKNOWN", "UNLOGGED_FLOWS_INVISIBLE",
            "FABRIC_REDIRECT_NOT_MODELLED")));
        var texts = new ArrayList<String>(); strings(routedResult.summary(), texts); strings(routedResult.flows(), texts);
        texts.forEach(CutoverAcceptanceCriteriaTest::assertNoForbiddenWord);
        // The aiview projection keeps the verdict and its coverage list.
        var masked = CutoverPrivacy.mask(routedResult.summary(), names, ips, CutoverPathTargets.REASONS);
        assertEquals("NO_BLOCKERS_IN_EVALUATED_FLOWS", masked.get("readiness"));
        assertFalse(list(masked.get("coverage")).isEmpty());
        assertTrue(codes(masked).containsAll(List.of("LOG_PERIOD", "LOGGING_COMPLETENESS_UNKNOWN", "UNLOGGED_FLOWS_INVISIBLE")));
    }
    static CutoverAssessment.Result routedResult(CutoverAssessmentService.Prepared prepared) throws Exception {
        prepared.run().read(new ByteArrayInputStream("src,dst,port,proto\n192.0.2.8,198.51.100.8,443,tcp\n".getBytes(StandardCharsets.UTF_8)),
            Map.of("source", "src", "destination", "dst", "port", "port", "protocol", "proto"));
        return prepared.run().finish(Instant.now().plusSeconds(60));
    }

    // ---- ACI-4 ------------------------------------------------------------------------------------------------

    @Test @DisplayName("ACI-4 a Palo Alto override target without a zone pair gives NOT_DECIDED (zone or target required) for every tuple; no zone is inferred")
    void aci4AnOverriddenPaloAltoTargetWithoutBothZonesIsNeverDecided() throws Exception {
        segment(1);
        when(repository.deviceVendor(DEVICE)).thenReturn(Optional.of("palo_alto"));
        // Rule 1 would match every flow when its zones are given (control); rule 2 is restricted to the segment/peer zones.
        var restricted = edit(rule("r2", "any", "any", "allow", true), cell("any"), Map.of("from", List.of("Z-SEG"), "to", List.of("Z-PEER")));
        var snapshot = panTarget("2026-11-13T00:00:00Z", restricted, rule("r1", "any", "any", "allow", true));
        when(policies.find("p1")).thenReturn(Optional.of(snapshot));
        var csv = new ByteArrayInputStream(("src,dst,port,proto,szone,dzone\n192.0.2.8,198.51.100.8,443,tcp,Z-SEG,Z-PEER\n"
            + "198.51.100.8,192.0.2.8,22,tcp,Z-PEER,Z-SEG\n192.0.2.9,198.51.100.9,53,udp,Z-SEG,Z-PEER\n").getBytes(StandardCharsets.UTF_8));
        var mapping = Map.of("source", "src", "destination", "dst", "port", "port", "protocol", "proto", "sourceZone", "szone", "destinationZone", "dzone");
        for (var zones : List.of(List.of("", ""), List.of("Z-SEG", ""), List.of("", "Z-PEER"))) {
            var prepared = service.prepare(SEGMENT, new CutoverAssessmentService.Spec(
                new CutoverAssessmentService.TargetOverride(DEVICE, "p1", "", zones.get(0), zones.get(1)), null, null, null, List.of(mapping)), Optional.empty(), null);
            csv.reset();
            prepared.run().read(csv, mapping);
            var result = prepared.run().finish(Instant.now().plusSeconds(60));
            assertEquals(3, result.flows().size(), zones.toString());
            for (var flow : result.flows()) {   // the log's own zone columns never stand in for the missing zone
                assertEquals("NOT_DECIDED", flow.get("decision"), zones.toString());
                assertEquals("zone or target required", flow.get("decisionReason"));
                assertNull(flow.get("decidingRule"));
            }
            assertTrue(codes(result.summary()).contains("ZONE_PAIR_MISSING")); assertEquals("NOT_DECIDED", result.readiness());
            assertEquals(0L, ((Map<?, ?>) result.summary().get("counts")).get("WOULD_ALLOW"));
        }
        // Control: with both zones the same flows are decided, so the zone pair is what holds them back.
        var decided = service.prepare(SEGMENT, new CutoverAssessmentService.Spec(
            new CutoverAssessmentService.TargetOverride(DEVICE, "p1", "", "Z-SEG", "Z-PEER"), null, null, null, List.of(mapping)), Optional.empty(), null);
        csv.reset(); decided.run().read(csv, mapping);
        assertTrue(decided.run().finish(Instant.now().plusSeconds(60)).flows().stream().allMatch(f -> f.get("decision").equals("WOULD_ALLOW")));
    }

    @Test @DisplayName("ACI-4 through the controller every stored tuple of a zone-less Palo Alto override is NOT_DECIDED (zone or target required)")
    void aci4TheControllerStoresNotDecidedForEveryTupleWithoutAZonePair() throws Exception {
        allow(panTarget("2026-11-13T00:00:00Z", rule("r1", "any", "any", "allow", true)), "palo_alto");
        var stored = run(spec("", "", MAP), HEADER + "192.0.2.8,198.51.100.8,443,tcp\r\n198.51.100.8,192.0.2.8,80,tcp\r\n192.0.2.9,198.51.100.8,25,tcp\r\n");
        var flows = stored.rows().stream().map(row -> { try { return map(row); } catch (Exception e) { throw new IllegalStateException(e); } })
            .filter(row -> row.get("kind").equals("FLOW")).toList();
        assertEquals(3, flows.size());
        for (var row : flows) {
            assertEquals("NOT_DECIDED", row.get("decision"));
            assertEquals("zone or target required", ((Map<?, ?>) row.get("flow")).get("decisionReason"));
        }
        assertEquals("NOT_DECIDED", stored.readiness());
    }

    // ---- ACI-5 ------------------------------------------------------------------------------------------------

    static final Set<String> RAW_KEYS = Set.of("rows", "row", "raw", "file", "files", "csv", "mapping", "mappings", "headers", "header", "content", "line", "lines");

    @Test @DisplayName("ACI-5 a stored assessment holds no raw row, header, mapping or file content")
    void aci5AStoredAssessmentHoldsNoRawRowHeaderMappingOrFileContent() throws Exception {
        allow(panTarget("2026-11-13T00:00:00Z", rule("r1", "any", "any", "deny", true)), "palo_alto");
        String header = "SRC-HEADER-MARKER,DST-HEADER-MARKER,PORT-HEADER-MARKER,PROTO-HEADER-MARKER,ACTION-HEADER-MARKER,NOTE-HEADER-MARKER\r\n";
        String spec = spec("Z-SEG", "Z-PEER", "\"mappings\":[{\"source\":\"SRC-HEADER-MARKER\",\"destination\":\"DST-HEADER-MARKER\","
            + "\"port\":\"PORT-HEADER-MARKER\",\"protocol\":\"PROTO-HEADER-MARKER\",\"action\":\"ACTION-HEADER-MARKER\"}]");
        var stored = run(spec, header
            + "192.0.2.8,198.51.100.8,443,tcp,accept,NOTE-CELL-MARKER-ONE\r\n"
            + "192.0.2.9,198.51.100.8,443,tcp,VALUE-CELL-MARKER,NOTE-CELL-MARKER-TWO\r\n"
            + "bad-address-cell-marker,198.51.100.8,443,tcp,accept,NOTE-CELL-MARKER-THREE\r\n");
        var stores = new ArrayList<String>(); stores.add(stored.summary()); stores.addAll(stored.rows());
        String all = String.join("\n", stores);
        for (String marker : List.of("HEADER-MARKER", "CELL-MARKER", "192.0.2.8,198.51.100.8", "synthetic.csv", "multipart", "SyntheticBoundary"))
            assertFalse(all.contains(marker), marker);
        var keys = new TreeSet<String>();
        for (String store : stores) keys(map(store), keys);
        assertTrue(Collections.disjoint(keys, RAW_KEYS), "raw-content key stored: " + keys);
        // What is kept of an upload: counts and times only.
        for (var upload : list(map(stored.summary()).get("uploads")))
            assertEquals(Set.of("index", "rowCount", "invalidRows", "inScopeFlows", "periodStart", "periodEnd", "parseStats"), upload.keySet());
        for (var stat : list(list(map(stored.summary()).get("uploads")).get(0).get("parseStats")))
            assertEquals("invalid IP address", stat.get("statReason"));
        assertEquals(2, stored.rows().stream().filter(row -> row.contains("\"kind\":\"FLOW\"")).count());
        assertEquals(1L, ((Number) list(map(stored.summary()).get("uploads")).get(0).get("invalidRows")).longValue());
    }

    @Test @DisplayName("ACI-5 the migration's CHECK constraints refuse every raw-content key in the summary and in each flow row")
    void aci5TheMigrationRefusesRawContentKeysInTheSummaryAndInEveryFlowRow() throws Exception {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V217__cutover_assessment.sql")) {
            assertNotNull(in, "V217 must be on the classpath");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var summary = java.util.regex.Pattern.compile("NOT summary \\?\\| ARRAY\\[([^\\]]*)\\]").matcher(sql);
        var flow = java.util.regex.Pattern.compile("NOT flow \\?\\| ARRAY\\[([^\\]]*)\\]").matcher(sql);
        assertTrue(summary.find(), "summary CHECK"); assertTrue(flow.find(), "flow CHECK");
        for (var check : List.of(summary.group(1), flow.group(1))) {
            Set<String> refused = new TreeSet<>();
            for (String key : check.split(",")) refused.add(key.strip().replace("'", ""));
            assertTrue(refused.containsAll(List.of("rows", "raw", "file", "csv", "mapping", "headers")), refused.toString());
        }
        // The database part (a write holding such a key is refused and leaves nothing) is the PostgreSQL test
        // CutoverRepositoryDatabaseTest ("rows") and CutoverNoRawContentTest (every key).
    }

    // ---- ACI-6 ------------------------------------------------------------------------------------------------

    static final List<String> RAW_VALUES = List.of("192.0.2.8", "192.0.2.9", "198.51.100.8", "192.0.2.0/24", "198.51.100.0/24", "SEG-REAL-NAME",
        "actor-real-name", "OBJ-RULE-r1", "Z-SEG-REAL", "Z-PEER-REAL");

    @Test @DisplayName("ACI-6 aiview reads of the segments, the assessment and its flows show masked addresses and pseudonyms only")
    void aci6AiviewReadsOfSegmentsAssessmentAndFlowsShowMaskedValuesOnly() throws Exception {
        allow(panTarget("2026-11-13T00:00:00Z", rule("r1", "any", "any", "deny", true)), "palo_alto");
        var stored = run(spec("Z-SEG-REAL", "Z-PEER-REAL", MAP), HEADER + "192.0.2.8,198.51.100.8,443,tcp\r\n198.51.100.8,192.0.2.9,22,tcp\r\n");
        var head = new LinkedHashMap<String, Object>();
        head.put("assessmentId", ASSESSMENT); head.put("segmentId", SEGMENT); head.put("segmentRevision", 1); head.put("createdAt", "2026-11-14T10:00:00Z");
        head.put("actor", "actor-real-name"); head.put("targetSource", stored.targetSource()); head.put("targetDeviceId", DEVICE);
        head.put("targetPolicyId", "p1"); head.put("targetCollectedAt", "2026-11-13T00:00:00Z"); head.put("status", stored.status());
        head.put("readiness", stored.readiness()); head.put("engineVersion", stored.engineVersion());
        var assessment = map(stored.summary()); assessment.putAll(head);
        var flowRows = stored.rows().stream().map(row -> { try { return map(row); } catch (Exception e) { throw new IllegalStateException(e); } }).toList();
        var flowJson = new ArrayList<String>();
        for (var row : flowRows) if (row.get("kind").equals("FLOW")) flowJson.add(JSON.writeValueAsString(row.get("flow")));
        reset(repository);
        segment(1);
        String segmentJson = "{\"segmentId\":\"" + SEGMENT + "\",\"name\":\"SEG-REAL-NAME\",\"revision\":1,"
            + "\"blocks\":[\"192.0.2.0/24\"],\"peerBlocks\":[\"198.51.100.0/24\"],\"updatedAt\":\"2026-11-14T09:00:00Z\"}";
        when(repository.findSegment(SEGMENT)).thenReturn(Optional.of(segmentJson));
        when(repository.listSegments()).thenReturn(List.of(segmentJson));
        var listed = new LinkedHashMap<>(head); listed.put("headline", assessment.get("headline")); listed.put("counts", assessment.get("counts"));
        when(repository.listAssessments(SEGMENT)).thenReturn(List.of(JSON.writeValueAsString(listed)));
        when(repository.findAssessment(ASSESSMENT)).thenReturn(Optional.of(JSON.writeValueAsString(assessment)));
        when(repository.flowCount(ASSESSMENT, "FLOW", null)).thenReturn((long) flowJson.size());
        when(repository.flows(ASSESSMENT, "FLOW", null, 0)).thenReturn(flowJson);
        when(paths.currentSnapshotId()).thenReturn(Optional.empty());

        var surfaces = new LinkedHashMap<String, org.springframework.http.ResponseEntity<?>>();
        for (boolean masked : List.of(false, true)) {
            surfaces.put("segments", controller.segments(request("GET", "/api/v2/cutover/segments", masked)));
            surfaces.put("segment", controller.segment(SEGMENT, request("GET", "/api/v2/cutover/segments/" + SEGMENT, masked)));
            surfaces.put("assessment", controller.assessment(ASSESSMENT, request("GET", "/api/v2/cutover/assessments/" + ASSESSMENT, masked)));
            surfaces.put("flows", controller.flows(ASSESSMENT, "FLOW", null, 0, request("GET", "/api/v2/cutover/assessments/" + ASSESSMENT + "/flows", masked)));
            for (var surface : surfaces.entrySet()) {
                var response = (CutoverResponse) surface.getValue().getBody();
                assertEquals(masked, response.masked(), surface.getKey());
                String body = JSON.writeValueAsString(response.body());
                if (masked) for (String raw : RAW_VALUES) assertFalse(body.contains(raw), surface.getKey() + " leaks " + raw);
                else assertTrue(RAW_VALUES.stream().anyMatch(body::contains), surface.getKey() + " control must hold raw values");
            }
        }
        // Masked values are still values, not blanks: addresses stay address-shaped, the verdict and coverage stay readable.
        var flows = (CutoverResponse) surfaces.get("flows").getBody();
        var item = list(flows.body().get("items")).get(0);
        assertTrue(item.get("flowSource").toString().matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"), String.valueOf(item.get("flowSource")));
        var masked = (CutoverResponse) surfaces.get("assessment").getBody();
        assertEquals("BLOCKERS_FOUND", masked.body().get("readiness")); assertFalse(list(masked.body().get("coverage")).isEmpty());
    }

    // ---- ACI-7 ------------------------------------------------------------------------------------------------

    @Test @DisplayName("ACI-7 over the tuple, file, row, size, block or page limits the run stops with a named reason and stores nothing")
    void aci7OverTheTupleFileRowSizeBlockAndPageLimitsTheRunStopsWithANamedReason() throws Exception {
        allow(panTarget("2026-11-13T00:00:00Z", rule("r1", "any", "any", "allow", true)), "palo_alto");
        // Tuples: 50 001 distinct flows through the controller.
        var many = new StringBuilder(HEADER);
        for (int i = 0; i <= CutoverAssessment.MAX_TUPLES; i++) many.append("192.0.2.").append(i % 256).append(",198.51.100.").append(i / 256).append(",443,tcp\r\n");
        var tuples = controller.assess(SEGMENT, upload(spec("Z-SEG", "Z-PEER", MAP), many.toString()));
        assertEquals(400, tuples.getStatusCode().value());
        assertTrue(String.valueOf(tuples.getBody()).contains("TUPLE_LIMIT"), String.valueOf(tuples.getBody()));
        // Files: nine mappings, and nine files for eight mappings.
        String oneMapping = "{\"source\":\"src\",\"destination\":\"dst\",\"port\":\"port\",\"protocol\":\"proto\"}";
        var nine = controller.assess(SEGMENT, upload(spec("Z-SEG", "Z-PEER", "\"mappings\":[" + String.join(",", Collections.nCopies(9, oneMapping)) + "]"),
            HEADER + "192.0.2.8,198.51.100.8,443,tcp\r\n"));
        assertEquals(400, nine.getStatusCode().value()); assertTrue(String.valueOf(nine.getBody()).contains("CSV_MAPPING"));
        String file = HEADER + "192.0.2.8,198.51.100.8,443,tcp\r\n";
        var tooManyFiles = controller.assess(SEGMENT, upload(spec("Z-SEG", "Z-PEER", "\"mappings\":[" + String.join(",", Collections.nCopies(8, oneMapping)) + "]"),
            Collections.nCopies(9, file).toArray(new String[0])));
        assertEquals(400, tooManyFiles.getStatusCode().value());
        // Rows and size, through the engine's own hooks (the production limits are two million rows and 100 MB).
        var scope = new CutoverAssessment.Scope(List.of("192.0.2.0/24"), List.of("198.51.100.0/24"));
        var target = new CutoverAssessment.Target(CutoverAssessment.stored(panTarget("2026-11-13T00:00:00Z", rule("r1", "any", "any", "allow", true)), "", ""), "Z-SEG", "Z-PEER", false, false);
        var map = Map.of("source", "src", "destination", "dst", "port", "port", "protocol", "proto");
        var rows = CutoverAssessment.start(scope, target, null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> rows.read(new ByteArrayInputStream((file + "192.0.2.9,198.51.100.8,443,tcp\r\n").getBytes(StandardCharsets.UTF_8)),
            map, 100_000, 1)).getMessage().startsWith("CSV_ROW_LIMIT"));
        var size = CutoverAssessment.start(scope, target, null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> size.read(new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)), map, 10, 100))
            .getMessage().startsWith("CSV_SIZE_LIMIT"));
        var filesRun = CutoverAssessment.start(scope, target, null);
        for (int i = 0; i < CutoverAssessment.MAX_UPLOADS; i++) filesRun.read(new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)), map);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> filesRun.read(new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)), map))
            .getMessage().startsWith("CSV_FILE_LIMIT"));
        // Blocks: a segment with more than 4096 blocks is refused, not cut to 4096.
        var blocks = new ArrayList<String>();
        for (int i = 0; i <= CutoverAssessment.MAX_BLOCKS; i++) blocks.add("10." + (i / 256) + "." + (i % 256) + ".0/24");
        var segmentBody = new LinkedHashMap<String, Object>(); segmentBody.put("name", "SEG-ALPHA"); segmentBody.put("blocks", blocks); segmentBody.put("peerBlocks", List.of("198.51.100.0/24"));
        var tooManyBlocks = controller.create(segmentBody, request("POST", "/api/v2/cutover/segments", false));
        assertEquals(400, tooManyBlocks.getStatusCode().value()); assertTrue(String.valueOf(tooManyBlocks.getBody()).contains("SEGMENT_BLOCKS"));
        // Pages: a page beyond the cap is refused; the total is the full count, never the page's.
        assertEquals(400, controller.flows(ASSESSMENT, "FLOW", null, 10_001, request("GET", "/x", false)).getStatusCode().value());
        when(repository.findAssessment(ASSESSMENT)).thenReturn(Optional.of("{\"assessmentId\":\"" + ASSESSMENT + "\"}"));
        when(repository.flowCount(ASSESSMENT, "FLOW", null)).thenReturn(250L);
        when(repository.flows(ASSESSMENT, "FLOW", null, 0)).thenReturn(Collections.nCopies(CutoverRepository.FLOW_PAGE, "{\"decision\":\"WOULD_DROP\"}"));
        var page = (CutoverResponse) controller.flows(ASSESSMENT, "FLOW", null, 0, request("GET", "/x", false)).getBody();
        assertEquals(250L, page.body().get("total")); assertEquals(CutoverRepository.FLOW_PAGE, list(page.body().get("items")).size());
        verify(repository, never()).saveAssessment(any());
    }

    @Test @DisplayName("ACI-7 the time limit marks every undecided tuple with a named reason; no tuple is dropped or decided from partial work")
    void aci7TheTimeLimitNamesTheUndecidedFlowsAndTruncatesNone() throws Exception {
        var scope = new CutoverAssessment.Scope(List.of("192.0.2.0/24"), List.of("198.51.100.0/24"));
        var target = new CutoverAssessment.Target(CutoverAssessment.stored(panTarget("2026-11-13T00:00:00Z", rule("r1", "any", "any", "deny", true)), "", ""), "Z-SEG", "Z-PEER", false, false);
        var run = CutoverAssessment.start(scope, target, null);
        run.read(new ByteArrayInputStream((HEADER + "192.0.2.8,198.51.100.8,443,tcp\r\n192.0.2.9,198.51.100.8,443,tcp\r\n192.0.2.10,198.51.100.8,22,tcp\r\n").getBytes(StandardCharsets.UTF_8)),
            Map.of("source", "src", "destination", "dst", "port", "port", "protocol", "proto"));
        var result = run.finish(Instant.EPOCH);
        assertEquals("INCOMPLETE", result.status()); assertEquals("NOT_DECIDED", result.readiness());
        assertEquals(3, result.flows().size());
        for (var flow : result.flows()) { assertEquals("NOT_DECIDED", flow.get("decision")); assertEquals("ASSESSMENT_TIME_LIMIT", flow.get("decisionReason")); }
        assertTrue(codes(result.summary()).contains("ASSESSMENT_INCOMPLETE"));
        assertEquals(3L, ((Map<?, ?>) result.summary().get("counts")).get("tuples"));
        assertTrue(result.missingRules().isEmpty());
    }

    // ---- ACI-8 ------------------------------------------------------------------------------------------------

    static String storedAssessment(int segmentRevision, String source, String topology, String policyCollectedAt, String oldCollectedAt) {
        return "{\"assessmentId\":\"" + ASSESSMENT + "\",\"segmentId\":\"" + SEGMENT + "\",\"segmentRevision\":" + segmentRevision
            + ",\"readiness\":\"BLOCKERS_FOUND\",\"targetSource\":\"" + source + "\""
            + (topology == null ? "" : ",\"topologySnapshotId\":\"" + topology + "\"")
            + (policyCollectedAt == null ? "" : ",\"targetPolicyId\":\"p1\",\"targetCollectedAt\":\"" + policyCollectedAt + "\"")
            + (oldCollectedAt == null ? "" : ",\"oldPolicyId\":\"p-old\",\"oldCollectedAt\":\"" + oldCollectedAt + "\"") + "}";
    }
    Map<String, Object> read(boolean masked) {
        var response = (CutoverResponse) controller.assessment(ASSESSMENT, request("GET", "/api/v2/cutover/assessments/" + ASSESSMENT, masked)).getBody();
        return response.body();
    }

    @Test @DisplayName("ACI-8 an assessment is STALE when its segment, the segment's scope or a cited policy snapshot changes")
    void aci8AnAssessmentIsStaleWhenTheSegmentTheScopeOrAPolicySnapshotChanges() {
        when(repository.findAssessment(ASSESSMENT)).thenReturn(Optional.of(storedAssessment(2, "OPERATOR_OVERRIDE", null, "2026-11-13T00:00:00Z", "2026-11-12T00:00:00Z")));
        when(paths.currentSnapshotId()).thenReturn(Optional.empty());
        segment(2);
        when(policies.find("p1")).thenReturn(Optional.of(panTarget("2026-11-13T00:00:00Z", rule("r1", "any", "any", "deny", true))));
        when(policies.find("p-old")).thenReturn(Optional.of(panTarget("2026-11-12T00:00:00Z", rule("r1", "any", "any", "allow", true))));
        var fresh = read(false);
        assertEquals(false, fresh.get("stale")); assertEquals(List.of(), fresh.get("staleReasons"));
        // The segment or its scope (blocks, peer blocks: any edit raises the revision) changed.
        segment(3);
        assertEquals(true, read(false).get("stale")); assertEquals(List.of("SEGMENT_CHANGED"), read(false).get("staleReasons"));
        segment(2);
        // The target snapshot changed; the old-path snapshot changed; a cited policy is gone.
        when(policies.find("p1")).thenReturn(Optional.of(panTarget("2026-11-14T00:00:00Z", rule("r1", "any", "any", "deny", true))));
        assertEquals(List.of("TARGET_POLICY_CHANGED"), read(false).get("staleReasons"));
        when(policies.find("p1")).thenReturn(Optional.of(panTarget("2026-11-13T00:00:00Z", rule("r1", "any", "any", "deny", true))));
        when(policies.find("p-old")).thenReturn(Optional.of(panTarget("2026-11-15T00:00:00Z", rule("r1", "any", "any", "allow", true))));
        assertEquals(List.of("OLD_POLICY_CHANGED"), read(false).get("staleReasons"));
        when(policies.find("p1")).thenReturn(Optional.empty());
        assertEquals(true, read(false).get("stale")); assertEquals(List.of("POLICY_NOT_FOUND", "OLD_POLICY_CHANGED"), read(false).get("staleReasons"));
        // The aiview projection keeps the state and its reasons (closed vocabulary).
        segment(3);
        var masked = read(true);
        assertEquals(true, masked.get("stale")); assertTrue(CutoverPrivacy.STALE_REASONS.containsAll((List<?>) masked.get("staleReasons")));
        assertTrue(((List<?>) masked.get("staleReasons")).contains("SEGMENT_CHANGED"));
        // A segment that no longer exists makes the verdict stale as well.
        when(repository.findSegment(SEGMENT)).thenReturn(Optional.empty());
        assertTrue(((List<?>) read(false).get("staleReasons")).contains("SEGMENT_CHANGED"));
    }

    @Test @DisplayName("ACI-8 a routed assessment is STALE when the held topology changes or is no longer held")
    void aci8ARoutedAssessmentIsStaleWhenTheHeldTopologyChanges() {
        when(repository.findAssessment(ASSESSMENT)).thenReturn(Optional.of(storedAssessment(1, "PATH", "topology-synthetic-01", null, null)));
        segment(1);
        when(paths.currentSnapshotId()).thenReturn(Optional.of("topology-synthetic-01"));
        assertEquals(false, read(false).get("stale"));
        when(paths.currentSnapshotId()).thenReturn(Optional.of("topology-synthetic-02"));
        assertEquals(true, read(false).get("stale")); assertEquals(List.of("TOPOLOGY_CHANGED"), read(false).get("staleReasons"));
        when(paths.currentSnapshotId()).thenReturn(Optional.empty());
        assertEquals(List.of("TOPOLOGY_CHANGED"), read(false).get("staleReasons"));
        assertEquals(List.of("TOPOLOGY_CHANGED"), read(true).get("staleReasons"));
    }
}
