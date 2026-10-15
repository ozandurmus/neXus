package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeRepository.*;
import com.securityexpert.nexus.ui2.service.privacy.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.Instant;
import java.io.ByteArrayOutputStream;

class PolicyChangesServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(new byte[32]);
    final PolicyChangeRepository repository = mock(PolicyChangeRepository.class);
    final PolicyChangesService service = new PolicyChangesService(repository, mapper, names, ips);
    PolicyChangesService.Request request(String actor, String q, String cursor, int limit) {
        return new PolicyChangesService.Request("2026-09-09T00:00:00Z", "2026-10-10T00:00:00Z", "p1", "d1", actor, "modified", "action", q, cursor, limit);
    }
    Row row(long id, String actor, boolean observed) {
        return new Row(id, "p1", "r1", "uuid:opaque-001", "modified", "2026-10-09T00:00:00Z", observed, actor,
            "[{\"field\":\"action\",\"before\":\"allow\",\"after\":\"deny\"},{\"field\":\"source\",\"before\":{\"refs\":[{\"id\":\"o1\",\"name\":\"Synthetic address\",\"values\":[\"192.0.2.8\"]}]},\"after\":null}]",
            "{\"name\":\"Synthetic policy\",\"targets\":[{\"deviceId\":\"d1\",\"name\":\"Synthetic gateway\",\"context\":\"Synthetic context\"}]}", "Synthetic rule", 7, false);
    }
    void stub(List<Row> rows) {
        when(repository.ceiling()).thenReturn(3L);
        when(repository.rows(any(), anyLong(), any())).thenAnswer(call -> rows.stream().filter(r -> {
            Position position = call.getArgument(2); return r.revision() <= (long) call.getArgument(1) && (position == null || r.revision() < position.revision());
        }).toList());
        when(repository.summary(any(), anyLong())).thenReturn(Map.of("total", rows.size(), "actors", 1, "rules", 1, "counts", Map.of("modified", rows.size())));
    }
    @Test void tiesUseRevisionKeysetsAndCeilingExcludesNewArrivals() {
        stub(List.of(row(3, "Synthetic actor", false), row(2, null, true), row(1, "Synthetic actor", false)));
        var first = service.changes(request("", "", "", 1), false).body();
        assertEquals("3", mapper.valueToTree(first).path("rows").get(0).path("revision").asText());
        String cursor = (String) first.get("nextCursor");
        var second = mapper.valueToTree(service.changes(request("", "", cursor, 1), false).body());
        assertEquals("2", second.path("rows").get(0).path("revision").asText());
        assertEquals(3, second.path("summary").path("total").asInt());
        var third = mapper.valueToTree(service.changes(request("", "", second.path("nextCursor").asText(), 1), false).body());
        assertEquals("1", third.path("rows").get(0).path("revision").asText()); assertEquals("", third.path("nextCursor").asText());
        verify(repository, times(1)).ceiling();
    }
    @Test void filtersComposeWithMaskedActorAndRuleAliasesAndNoRawIdentityProbe() throws Exception {
        stub(List.of(row(3, "Synthetic actor", false), row(2, null, true)));
        var result = service.changes(request(names.maskPolicyName("last-modifier", "Synthetic actor"), names.maskPolicyName("rule", "Synthetic rule"), "", 100), true);
        var json = mapper.valueToTree(PolicyPrivacy.mask(result.body(), "", names, ips));
        assertEquals(1, json.path("rows").size()); assertEquals(1, json.path("summary").path("total").asInt());
        assertFalse(json.toString().contains("Synthetic")); assertFalse(json.toString().contains("192.0.2.8"));
        assertEquals("p1", json.path("rows").get(0).path("policyId").asText());
        assertEquals("7", json.path("rows").get(0).path("number").asText());
        assertEquals(0, mapper.valueToTree(service.changes(request("Synthetic actor", "", "", 100), true).body()).path("rows").size());
        verify(repository, atLeastOnce()).rows(eq(new Filter(Instant.parse("2026-09-09T00:00:00Z"), Instant.parse("2026-10-10T00:00:00Z"), "p1", "d1", "modified", "action")), eq(3L), isNull());
    }
    @Test void unknownActorsAndObservationFlagSurviveMaskingAndCsv() throws Exception {
        stub(List.of(row(2, "  ", true)));
        var json = mapper.valueToTree(PolicyPrivacy.mask(service.changes(request("UNKNOWN", "7", "", 100), true).body(), "", names, ips));
        assertEquals(PolicyChangesService.UNKNOWN_ACTOR, json.path("rows").get(0).path("changedBy").asText());
        assertTrue(json.path("rows").get(0).path("observedAtCollection").asBoolean());
        assertEquals("2026-10-09T00:00:00Z", json.path("rows").get(0).path("changedOn").asText());
        assertEquals(0, json.path("summary").path("actors").asInt());
        var output = new ByteArrayOutputStream(); service.csv(request("", "", "", 1), true, output);
        String csv = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains(PolicyChangesService.UNKNOWN_ACTOR)); assertFalse(csv.contains("Synthetic")); assertFalse(csv.contains("192.0.2.8"));
        assertTrue(csv.contains(names.maskPolicyName("policy", "Synthetic policy")));
    }
    @Test void csvExportsBeyondPageLimitAndKeepsRowsWithoutFieldDetails() throws Exception {
        Row original = row(3, "=1+1", false);
        Row empty = new Row(2, "p1", "r2", "uuid:r2", "removed", original.time(), true, null, "[]", original.metadata(), "+formula", null, false);
        stub(List.of(original, empty));
        var output = new ByteArrayOutputStream(); service.csv(request("", "", "", 1), false, output);
        String csv = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("'=")); assertTrue(csv.contains("'+formula"));
        assertTrue(csv.contains("removed")); assertEquals(4, csv.split("\r\n").length);
    }
    @Test void csvEscapesFormulaPrefixesWhitespaceQuotesAndNewlines() {
        for (String text : List.of("=1+1", "+cmd", "-cmd", "@cmd", " \t=cmd", "\ttext", "\ntext", "\rtext"))
            assertTrue(PolicyChangesService.csvCell(text).startsWith("\"'"));
        assertEquals("\"a\"\"b\ntext\"", PolicyChangesService.csvCell("a\"b\ntext"));
        assertEquals("\"\"", PolicyChangesService.csvCell(null));
    }
    Row initialRow(long id) {
        Row base = row(id, "Synthetic actor", true);
        return new Row(id, "p1", "r" + id, "uuid:opaque-00" + id, "added", base.time(), true, "Synthetic actor", base.changes(), base.metadata(),
            "Synthetic rule", 7, false, "2026-10-08T21:00:00Z", true);
    }
    @Test void initialCapturesAreHiddenByDefaultCountedAndShownOnRequest() throws Exception {
        stub(List.of(row(3, "Synthetic actor", false), initialRow(2), initialRow(1)));
        var hidden = new PolicyChangesService.Request("2026-09-09T00:00:00Z", "2026-10-10T00:00:00Z", "", "", "", "", "", "", "", 100, false);
        // The repository is asked for real changes only; its summary owns the hidden count.
        service.changes(hidden, false);
        verify(repository, atLeastOnce()).rows(argThat(f -> !f.includeInitial()), eq(3L), isNull());
        verify(repository).summary(argThat(f -> !f.includeInitial()), eq(3L));
        // A scanned (actor/q) summary reads initial captures, counts them, and leaves them out of rows and totals.
        var scanned = new PolicyChangesService.Request(hidden.from(), hidden.to(), "", "", "Synthetic", "", "", "", "", 100, false);
        var json = mapper.valueToTree(service.changes(scanned, false).body());
        assertEquals(1, json.path("rows").size()); assertEquals(1, json.path("summary").path("total").asInt());
        assertEquals(2, json.path("summary").path("initialCaptures").asInt());
        assertFalse(json.path("summary").path("initialCapturesIncluded").asBoolean());
        assertEquals(0, json.path("summary").path("counts").path("added").asInt());
        var shown = new PolicyChangesService.Request(hidden.from(), hidden.to(), "", "", "Synthetic", "", "", "", "", 100, true);
        json = mapper.valueToTree(PolicyPrivacy.mask(service.changes(shown, false).body(), "", names, ips));
        assertEquals(3, json.path("rows").size()); assertEquals(2, json.path("summary").path("counts").path("added").asInt());
        // Grouping fields survive the AIView projection.
        assertTrue(json.path("rows").get(1).path("initialCapture").asBoolean());
        assertEquals("2026-10-08T21:00:00Z", json.path("rows").get(1).path("collectedAt").asText());
        assertFalse(json.path("rows").get(0).path("initialCapture").asBoolean());
        var output = new ByteArrayOutputStream(); service.csv(shown, false, output);
        String csv = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("Time,Observed at collection,Policy,Devices,Rule number,Rule name,Current snapshot label,Change type,Actor,Field,Before,After,Collected at,Initial capture\r\n"));
        assertTrue(csv.contains("\"2026-10-08T21:00:00Z\",\"true\""));
    }
    @Test void invalidFiltersAndCursorsAreRejectedBeforeHistoryRead() {
        assertThrows(IllegalArgumentException.class, () -> service.changes(request("", "", "bad", 100), true));
        assertThrows(IllegalArgumentException.class, () -> service.changes(request("", "", "", 201), false));
        assertThrows(IllegalArgumentException.class, () -> service.changes(request("", "x".repeat(1001), "", 100), false));
        verifyNoInteractions(repository);
    }
}
