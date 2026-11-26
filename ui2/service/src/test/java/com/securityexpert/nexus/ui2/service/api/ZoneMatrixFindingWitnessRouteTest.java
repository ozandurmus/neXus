package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Cell;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.StoredFindingWitness;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Witness;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@code GET /api/v2/zone-matrix/findings/{findingId}/witness} at the controller (ZONE_MATRIX_CONTRACT.md 3.4, 7.1, 8.1, 8.2;
 * ZM-10, ZM-33): only a holder of {@code zone_matrix_zones_read} reads a stored finding's witness tuples, the masked persona
 * never does whatever it holds, an unknown finding is 404, a finding without a stored witness is {@code WITNESS_NOT_STORED}
 * (never an empty list), and every answer that carries a witness wrote exactly one {@code WITNESS_VIEW} row first; when that
 * row cannot be written no witness leaves. Logs and the audit row carry ids and counts only.
 */
class ZoneMatrixFindingWitnessRouteTest {
    private static final Instant NOW = Instant.parse("2026-11-25T08:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VERSION_ID = "11111111-2222-4333-8444-555555555555";
    private static final String RUN_ID = "run-planted-1";
    private static final String ZONE_A = "zone-planted-cardholder";
    private static final String ZONE_B = "zone-planted-branch";
    private static final long FINDING = 4711L;
    private static final String SOURCE_RANGE = "198.51.100.16-198.51.100.31";
    private static final String DESTINATION_RANGE = "198.51.100.200";
    private static final List<String> ADDRESS_OR_NAME = List.of("198.51.100.", "192.0.2.", ZONE_A, ZONE_B, "FW-TANGO-04");

    private ZoneMatrixRepository repository;
    private ZoneMatrixRunService runs;
    private ZoneMatrixController controller;
    private Set<String> roles = Set.of();
    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        repository = mock(ZoneMatrixRepository.class);
        var service = new ZoneMatrixService(repository, new ZoneMatrixService.Principals() {
            @Override public boolean holdsComplianceAdmin(String actor) { return false; }
            @Override public boolean isRoot(String actor) { return false; }
        }, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
        runs = mock(ZoneMatrixRunService.class);
        when(runs.storedWitness(anyLong())).thenReturn(Optional.empty());
        when(runs.storedWitness(FINDING)).thenReturn(Optional.of(stored(List.of(
                new Cell(ZONE_B, ZONE_A, "DENY_ALL", "HIGH", List.of("tcp/22"), "ORIGINAL",
                        new Witness(SOURCE_RANGE, DESTINATION_RANGE, "tcp/22", 3325256720L, 3325256904L)),
                new Cell(ZONE_A, ZONE_B, "DENY_ALL", "HIGH", List.of("any"), null, null)))));
        when(runs.storedWitness(4712L)).thenReturn(Optional.of(stored(List.of(
                new Cell(ZONE_B, ZONE_A, "DENY_ALL", "HIGH", List.of("tcp/22"), "ORIGINAL", null)))));

        RbacEvaluator rbac = mock(RbacEvaluator.class);
        var permitted = new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.empty(), Optional.empty(), Optional.empty());
        var denied = new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty());
        when(rbac.evaluateAny(anyString(), anySet(), any(Instant.class))).thenAnswer(call ->
                ((Set<?>) call.getArgument(1)).stream().anyMatch(roles::contains) ? permitted : denied);
        controller = new ZoneMatrixController(service, runs, rbac, new ActionRegistry());

        serviceLogger = (Logger) LoggerFactory.getLogger(ZoneMatrixService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() { serviceLogger.detachAppender(logs); }

    private static StoredFindingWitness stored(List<Cell> cells) {
        return new StoredFindingWitness(cells.size() == 2 ? FINDING : 4712L, RUN_ID, VERSION_ID, 3, "ORIGINAL", cells);
    }

    private MockHttpServletRequest request(boolean replayViewer, String... roleTokens) {
        roles = Set.of(roleTokens);
        var request = new MockHttpServletRequest("GET", "/api/v2/zone-matrix/findings/" + FINDING + "/witness");
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-fp-" + String.join("-", roleTokens));
        if (replayViewer) request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        return request;
    }

    private static String json(ResponseEntity<?> response) throws Exception { return MAPPER.writeValueAsString(response.getBody()); }

    private static Map<?, ?> body(ResponseEntity<?> response) { return (Map<?, ?>) response.getBody(); }

    private static void assertNoWitness(String body) {
        for (String forbidden : ADDRESS_OR_NAME) assertFalse(body.contains(forbidden), "body carries " + forbidden + ": " + body);
        assertFalse(body.contains("\"cells\""), body);
    }

    @Test
    void refusedWithoutZoneMatrixZonesReadAndNothingIsReadOrRecorded() throws Exception {
        for (String role : List.of(RoleToken.VIEWER, RoleToken.ONBOARDING_ADMIN, RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN,
                RoleToken.BACKUP_ADMIN)) {
            var response = controller.findingWitness(String.valueOf(FINDING), request(false, role));
            assertEquals(403, response.getStatusCode().value(), role);
            assertEquals("ZONE_BLOCKS_NOT_PERMITTED", body(response).get("error"), role);
            assertNoWitness(json(response));
        }
        verify(runs, never()).storedWitness(anyLong());
        verify(repository, never()).recordAccess(anyString(), anyString(), anyString());
    }

    @Test
    void theReplayViewerIsRefusedEvenWithEveryRole() throws Exception {
        var response = controller.findingWitness(String.valueOf(FINDING), request(true, RoleToken.SECURITY_ADMIN,
                RoleToken.COMPLIANCE_ADMIN, RoleToken.REPLAY_VIEWER, RoleToken.VIEWER, RoleToken.ONBOARDING_ADMIN));
        assertEquals(403, response.getStatusCode().value());
        assertEquals("READ_ONLY_PERSONA", body(response).get("error"));
        assertNoWitness(json(response));
        verify(runs, never()).storedWitness(anyLong());
        verify(repository, never()).recordAccess(anyString(), anyString(), anyString());
    }

    @Test
    void anUnknownOrNonCanonicalFindingIdIsNotFoundAndNotRecorded() throws Exception {
        var unknown = controller.findingWitness("999", request(false, RoleToken.SECURITY_ADMIN));
        assertEquals(404, unknown.getStatusCode().value());
        assertEquals("FINDING_NOT_FOUND", body(unknown).get("error"));
        // identity law: the id is compared exactly as the summary printed it, never normalised into another one
        for (String id : List.of("04711", "+4711", "4711 ", "0", "-1", "abc", "", "99999999999999999999")) {
            var response = controller.findingWitness(id, request(false, RoleToken.SECURITY_ADMIN));
            assertEquals(404, response.getStatusCode().value(), id);
            assertNoWitness(json(response));
        }
        verify(runs, times(1)).storedWitness(anyLong());
        verify(repository, never()).recordAccess(anyString(), anyString(), anyString());
    }

    @Test
    void aFindingWithoutAStoredWitnessIsWitnessNotStoredNeverAnEmptyList() throws Exception {
        var response = controller.findingWitness("4712", request(false, RoleToken.COMPLIANCE_ADMIN));
        assertEquals(409, response.getStatusCode().value());
        assertEquals("WITNESS_NOT_STORED", body(response).get("error"));
        assertEquals("4712", body(response).get("finding_id"));
        assertEquals(1, body(response).get("cells_total"));
        assertNoWitness(json(response));
        verify(repository, never()).recordAccess(anyString(), anyString(), anyString());
    }

    @Test
    void aSecurityAdministratorReadsTheStoredTupleAfterExactlyOneWitnessViewRow() throws Exception {
        var response = controller.findingWitness(String.valueOf(FINDING), request(false, RoleToken.SECURITY_ADMIN));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        var inOrder = inOrder(runs, repository);
        inOrder.verify(runs).storedWitness(FINDING);
        inOrder.verify(repository).recordAccess("actor-fp-" + RoleToken.SECURITY_ADMIN, "WITNESS_VIEW", VERSION_ID);
        verify(repository, times(1)).recordAccess(anyString(), anyString(), anyString());

        Map<?, ?> out = body(response);
        assertEquals(String.valueOf(FINDING), out.get("finding_id"));
        assertEquals(RUN_ID, out.get("run_id"));
        assertEquals(VERSION_ID, out.get("version_id"));
        assertEquals(3, out.get("version_number"));
        assertEquals(Boolean.TRUE, out.get("witnesses_included"));
        assertEquals(Boolean.TRUE, out.get("audited"));
        assertEquals(2, out.get("cells_total"));
        assertEquals(1, out.get("witnesses_stored"));
        assertEquals("ORIGINAL", out.get("nat_view"));
        List<?> cells = (List<?>) out.get("cells");
        Map<?, ?> first = (Map<?, ?>) cells.get(0);
        assertEquals(ZONE_B, first.get("source_zone_id"));
        assertEquals(ZONE_A, first.get("destination_zone_id"));
        assertEquals("ORIGINAL", first.get("nat_view"));
        Map<?, ?> witness = (Map<?, ?>) first.get("witness");
        assertEquals(SOURCE_RANGE, witness.get("source_range"), "a witness is a range as stored, never narrowed");
        assertEquals(DESTINATION_RANGE, witness.get("destination_range"));
        assertEquals("tcp", witness.get("protocol"));
        assertEquals("22", witness.get("port"));
        assertEquals("tcp/22", witness.get("service"));
        Map<?, ?> second = (Map<?, ?>) cells.get(1);
        assertNull(second.get("witness"), "a cell that stored no witness is null (the screen says UNKNOWN), never invented");
        assertEquals("UNKNOWN", second.get("nat_view"));
        String text = json(response);
        assertFalse(text.contains("sample"), "the single sample addresses are never returned: " + text);
        assertNotNull(out.get("can"), "the caller's affordances travel with the body");
    }

    @Test
    void aComplianceAdministratorReadsItToo() {
        var response = controller.findingWitness(String.valueOf(FINDING), request(false, RoleToken.COMPLIANCE_ADMIN));
        assertEquals(200, response.getStatusCode().value());
        verify(repository, times(1)).recordAccess("actor-fp-" + RoleToken.COMPLIANCE_ADMIN, "WITNESS_VIEW", VERSION_ID);
    }

    @Test
    void whenTheAuditRowCannotBeWrittenNoWitnessIsReturned() throws Exception {
        doThrow(new IllegalStateException("audit store down")).when(repository)
                .recordAccess(anyString(), anyString(), anyString());
        var response = controller.findingWitness(String.valueOf(FINDING), request(false, RoleToken.SECURITY_ADMIN));
        assertEquals(503, response.getStatusCode().value());
        assertEquals("ZONE_MATRIX_AUDIT_UNAVAILABLE", body(response).get("error"));
        assertNoWitness(json(response));
        assertTrue(logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains("zone_matrix_witness_read")),
                "no successful read is logged when the audit row failed");
    }

    @Test
    void theLogLineAndTheAuditRowCarryIdsAndCountsOnlyNeverAnAddressOrAName() {
        controller.findingWitness(String.valueOf(FINDING), request(false, RoleToken.SECURITY_ADMIN));
        var line = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("zone_matrix_witness_read")).toList();
        assertEquals(1, line.size(), "one log line per read: " + logs.list);
        assertTrue(line.get(0).contains("finding_id=" + FINDING) && line.get(0).contains("cells=2")
                && line.get(0).contains("witnesses=1") && line.get(0).contains("version_number=3"), line.get(0));
        for (ILoggingEvent event : logs.list) {
            for (String forbidden : ADDRESS_OR_NAME) {
                assertFalse(event.getFormattedMessage().contains(forbidden), "log carries " + forbidden);
            }
        }
        var actor = org.mockito.ArgumentCaptor.forClass(String.class);
        var kind = org.mockito.ArgumentCaptor.forClass(String.class);
        var version = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).recordAccess(actor.capture(), kind.capture(), version.capture());
        for (String value : List.of(actor.getValue(), kind.getValue(), version.getValue())) {
            for (String forbidden : ADDRESS_OR_NAME) assertFalse(value.contains(forbidden), "audit row carries " + forbidden);
        }
    }

    @Test
    void theWitnessServiceSplitsIntoProtocolAndPortOrSaysUnknown() throws Exception {
        when(runs.storedWitness(FINDING)).thenReturn(Optional.of(stored(List.of(
                new Cell(ZONE_B, ZONE_A, "DENY_ALL", "HIGH", List.of("any"), "ORIGINAL",
                        new Witness(SOURCE_RANGE, DESTINATION_RANGE, "any", 0L, 0L)),
                new Cell(ZONE_A, ZONE_B, "DENY_ALL", "HIGH", List.of("application-default"), "ORIGINAL",
                        new Witness(SOURCE_RANGE, null, "application-default", 0L, 0L))))));
        List<?> cells = (List<?>) body(controller.findingWitness(String.valueOf(FINDING),
                request(false, RoleToken.SECURITY_ADMIN))).get("cells");
        Map<?, ?> any = (Map<?, ?>) ((Map<?, ?>) cells.get(0)).get("witness");
        assertEquals("any", any.get("protocol"));
        assertEquals("any", any.get("port"));
        Map<?, ?> appDefault = (Map<?, ?>) ((Map<?, ?>) cells.get(1)).get("witness");
        assertEquals("UNKNOWN", appDefault.get("protocol"));
        assertEquals("UNKNOWN", appDefault.get("port"));
        assertEquals("UNKNOWN", appDefault.get("destination_range"), "an unreadable part is UNKNOWN, never empty");
        assertEquals("application-default", appDefault.get("service"));
    }

    @Test
    void theSummaryRowCarriesTheOpaqueFindingIdButNeverTheWitness() throws Exception {
        var content = new com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixContent(List.of(
                new com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixContent.Zone(ZONE_A, "Z-PLANTED-CARDHOLDER", "d",
                        "DATABASE", List.of(com.securityexpert.nexus.ui2.service.zonematrix.ZoneBlock.parse("192.0.2.0/25"))),
                new com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixContent.Zone(ZONE_B, "Z-PLANTED-BRANCH", "d",
                        "USER", List.of(com.securityexpert.nexus.ui2.service.zonematrix.ZoneBlock.parse("198.51.100.0/24")))));
        var version = new ZoneMatrixRepository.Version(VERSION_ID, 3, "ACTIVE", 2, content.canonicalJson(), content.sha256(), null,
                "a", NOW, "a", NOW, "p", NOW);
        when(repository.find(VERSION_ID)).thenReturn(Optional.of(version));
        when(repository.active()).thenReturn(Optional.of(version));
        var staleness = com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.assess(
                new com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.PolicyInput("P-1",
                        NOW.minusSeconds(3600).toString(),
                        com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.RunState.COMPLETE, VERSION_ID, VERSION_ID),
                NOW, com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.INTERIM_PO_2026_11_23);
        var finding = new com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Finding("P-1", "uid-1",
                List.of(), List.of(ZONE_B + "->" + ZONE_A), "rid-1", 4, "planted-rule", "s", "Pre rules", List.of(), "VIOLATION",
                "statement DENY_ALL", "HIGH", List.of(), null,
                List.of(new Cell(ZONE_B, ZONE_A, "DENY_ALL", "HIGH", List.of("tcp/22"), "ORIGINAL",
                        new Witness(SOURCE_RANGE, DESTINATION_RANGE, "tcp/22", 1L, 2L))),
                List.of(), "ORIGINAL", NOW, NOW, NOW, null, null);
        var summary = new ZoneMatrixRunService.StoredSummary(NOW,
                com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.INTERIM_PO_2026_11_23, version, List.of(),
                List.of(), List.of(new ZoneMatrixRunService.StoredFinding(RUN_ID, VERSION_ID, 3, content.sha256(),
                        staleness.verdict(), staleness.snapshotAge(), finding, FINDING)),
                null, List.of(), Map.of(), List.of());
        when(runs.storedSummary()).thenReturn(summary);
        var response = controller.summary(null, null, null, request(false, RoleToken.SECURITY_ADMIN));
        assertEquals(200, response.getStatusCode().value());
        var row = (Map<?, ?>) ((List<?>) body(response).get("findings")).get(0);
        assertEquals(String.valueOf(FINDING), row.get("finding_id"));
        String text = json(response);
        for (String forbidden : List.of("198.51.100.16", DESTINATION_RANGE, "\"witness\"", "\"nat_view\"")) {
            assertFalse(text.contains(forbidden), "the summary carries " + forbidden);
        }
        verify(repository, never()).recordAccess(anyString(), anyString(), anyString());
    }
}
