package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.CellInput;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PublishRequest;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Refused;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.View;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.ZoneInput;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Zone matrix delivery step 2 (ZONE_MATRIX_CONTRACT.md sections 3.3-3.5, 4, 6.1; ZM-22, ZM-23, ZM-36, ZM-40, ZM-41):
 * statements, service vocabulary, template import, reference confirmation and the loosening diff. Synthetic principals,
 * zone names and documentation ranges only.
 */
class ZoneMatrixStepTwoTest {
    private static final String U = ZoneMatrixContent.UNASSIGNED;
    private final ZoneMatrixServiceTest.Memory store = new ZoneMatrixServiceTest.Memory();
    private final Set<String> compliance = Set.of("compliance-1", "compliance-2");
    private final ZoneMatrixService service = new ZoneMatrixService(store, new ZoneMatrixService.Principals() {
        @Override public boolean holdsComplianceAdmin(String actor) { return compliance.contains(actor); }
        @Override public boolean isRoot(String actor) { return actor.equals("root-local"); }
    }, new ObjectMapper(), Clock.fixed(Instant.parse("2026-11-14T09:00:00Z"), ZoneOffset.UTC));

    private long revision() { return store.draft().orElseThrow().revision(); }
    private Long revisionOrNull() { return store.draft().map(v -> v.revision()).orElse(null); }

    private String zone(String actor, String name, String role, String... blocks) {
        return (String) service.createZone(actor, revisionOrNull(), new ZoneInput(name, "", role, List.of(blocks))).get("zone_id");
    }

    private Map<String, Object> cell(String actor, String source, String destination, String statement, List<String> services,
            String severity, String reference) {
        return service.setCell(actor, revisionOrNull(), new CellInput(source, destination, statement, services, severity, null, reference));
    }

    private ZoneMatrixContent draftContent() {
        return ZoneMatrixContent.parse(store.draft().orElseThrow().content(), new ObjectMapper());
    }

    private Map<String, Object> publish(String actor, String rationale) {
        var draft = store.draft().orElseThrow();
        return service.publish(actor, false, new PublishRequest(draft.revision(), draft.contentSha256(), rationale));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> diff() { return (Map<String, Object>) service.draft(View.NAMES, "security-9", false, true).get("diff"); }

    @SuppressWarnings("unchecked")
    private List<String> looseningKinds() {
        return ((List<Map<String, Object>>) diff().get("loosening")).stream().map(l -> (String) l.get("kind")).toList();
    }

    @Test void serviceEntriesAreCanonicalOrRefused() {
        for (String ok : List.of("tcp/443", "tcp/8000-8099", "udp/53", "tcp/0-65535", "icmp/8", "icmp/8/0", "icmp6/128",
                "ipproto/50", "app:pan:web-browsing", "set:web"))
            assertEquals(ok, ServiceEntry.parse(ok).text(), ok);
        for (String bad : List.of("TCP/443", "tcp/0443", "tcp/443-443", "tcp/90-80", "tcp/65536", "tcp/ 443", "icmp/256",
                "icmp/8/300", "ipproto/256", "app:pan:", "app:pan:two words", "set:", "set:a b", "sctp/80", ""))
            assertThrows(IllegalArgumentException.class, () -> ServiceEntry.parse(bad), bad);
        assertNull(ServiceEntry.parse("tcp/443").notEvaluableReason());
        assertNull(ServiceEntry.parse("icmp/8").notEvaluableReason()); // evaluable since step 7d (section 4.2, A0-I1)
        assertNull(ServiceEntry.parse("icmp/8/0").notEvaluableReason());
        assertNotNull(ServiceEntry.parse("icmp6/128").notEvaluableReason());
        assertNotNull(ServiceEntry.parse("ipproto/50").notEvaluableReason());
        assertNull(ServiceEntry.parse("app:pan:web-browsing").notEvaluableReason()); // evaluable since step 7 (section 4.3)
        var coverage = ServiceEntry.Coverage.of(List.of(ServiceEntry.parse("tcp/80-90"), ServiceEntry.parse("tcp/91"), ServiceEntry.parse("icmp/8")));
        assertFalse(coverage.adds(ServiceEntry.parse("tcp/85-91")));
        assertTrue(coverage.adds(ServiceEntry.parse("tcp/85-92")));
        assertTrue(coverage.adds(ServiceEntry.parse("udp/85")));
        assertFalse(coverage.adds(ServiceEntry.parse("icmp/8/0")));
        assertTrue(coverage.adds(ServiceEntry.parse("icmp/0")));
    }

    @Test void cellsFollowTheStatementRulesAndRefusedLinesAreReportedOneByOne() {
        String app = zone("security-1", "Z-APP", "APPLICATION", "198.51.100.0/24");
        String db = zone("security-1", "Z-DB", "DATABASE", "203.0.113.0/25");
        cell("security-1", app, db, "ALLOW_LISTED", List.of("tcp/5432", "tcp/1521"), "HIGH", "");
        assertEquals(List.of("tcp/1521", "tcp/5432"), draftContent().matrix().cell(app, db).orElseThrow().services());
        assertEquals("SERVICES_NOT_ALLOWED", assertThrows(Refused.class,
                () -> cell("security-1", db, app, "DENY_ALL", List.of("tcp/22"), "HIGH", "")).code);
        Refused lines = assertThrows(Refused.class, () -> cell("security-1", db, app, "ALLOW_LISTED", List.of("tcp/22", "TCP/23", "tcp/99999"), "HIGH", ""));
        assertEquals("INVALID_SERVICES", lines.code);
        assertEquals(List.of(1, 2), ((List<?>) lines.details.get("errors")).stream().map(e -> ((Map<?, ?>) e).get("index")).toList());
        assertEquals("SERVICE_SET_NOT_FOUND", assertThrows(Refused.class,
                () -> cell("security-1", db, app, "ALLOW_LISTED", List.of("set:missing"), "HIGH", "")).code);
        assertEquals("INVALID_STATEMENT", assertThrows(Refused.class, () -> cell("security-1", db, app, "PERMIT", List.of(), "HIGH", "")).code);
        assertEquals("INVALID_STATEMENT", assertThrows(Refused.class, () -> cell("security-1", db, app, null, List.of(), "HIGH", "")).code);
        assertEquals("INVALID_SEVERITY", assertThrows(Refused.class, () -> cell("security-1", db, app, "DENY_ALL", List.of(), null, "")).code);
        assertEquals("INVALID_DEFAULT", assertThrows(Refused.class, () -> service.setDefault("security-1", revision(), null, "LOW")).code);
        assertEquals("CELL_ZONE_NOT_FOUND", assertThrows(Refused.class,
                () -> cell("security-1", "00000000-0000-4000-8000-00000000ffff", app, "DENY_ALL", List.of(), "HIGH", "")).code);
        assertEquals("INVALID_DEFAULT", assertThrows(Refused.class, () -> service.setDefault("security-1", revision(), "ALLOW_ANY", "LOW")).code);
        String tooMany = assertThrows(Refused.class, () -> cell("security-1", db, app, "ALLOW_LISTED",
                Collections.nCopies(ZoneMatrixContent.MAX_SERVICES_PER_CELL + 1, "tcp/1"), "HIGH", "")).code;
        assertEquals("TOO_MANY_SERVICES", tooMany);
        // deleting a zone takes its cells with it
        service.deleteZone("security-1", revision(), db);
        assertTrue(draftContent().matrix().cells().isEmpty());
    }

    @Test void unassignedToUnassignedIsDenyAllWhateverTheDefault() {
        zone("security-1", "Z-APP", "APPLICATION", "198.51.100.0/24");
        service.setDefault("security-1", revision(), "ALLOW_LISTED", "MEDIUM");
        var content = draftContent();
        assertEquals("DENY_ALL", content.effective(U, U).statement());
        assertEquals("ALLOW_LISTED", content.effective(U, content.zones().get(0).id()).statement());
    }

    @Test void theTemplateSeedsCellsByRoleWithEmptyReferences() {
        String internet = zone("security-1", "Z-NET", "INTERNET_UNTRUSTED", "192.0.2.0/26");
        String dmz = zone("security-1", "Z-DMZ", "DMZ", "192.0.2.64/26");
        String user = zone("security-1", "Z-USER", "USER", "198.51.100.0/25");
        String app1 = zone("security-1", "Z-APP", "APPLICATION", "198.51.100.128/26");
        String app2 = zone("security-1", "Z-APP-2", "APPLICATION", "198.51.100.192/26");
        String db = zone("security-1", "Z-DB", "DATABASE", "203.0.113.0/26");
        String mgmt = zone("security-1", "Z-MGMT", "MANAGEMENT", "203.0.113.64/27");
        String partner = zone("security-1", "Z-PARTNER", "PARTNER", "203.0.113.96/27");
        String partner2 = zone("security-1", "Z-PARTNER-2", "PARTNER", "203.0.113.128/27");
        String nonprod = zone("security-1", "Z-TEST", "NON_PRODUCTION", "203.0.113.160/27");
        String plain = zone("security-1", "Z-PLAIN", null, "203.0.113.192/27");
        service.importTemplate("security-1", revision(), false);
        var content = draftContent();
        var m = content.matrix();
        assertEquals(new ZoneMatrixContent.TemplateOrigin(ZoneMatrixTemplate.NAME, ZoneMatrixTemplate.REVISION), m.templateOrigin());
        assertEquals("DENY_ALL", m.defaultStatement());
        assertTrue(m.cells().stream().allMatch(c -> c.referenceStatus().equals("EMPTY") && c.regulationReference().isEmpty()
                && c.confirmedBy() == null && c.services().isEmpty()));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(U + ">" + U, "DENY_ALL/MEDIUM");
        expected.put(U + ">" + dmz, "ALLOW_LISTED/HIGH");
        expected.put(internet + ">" + dmz, "ALLOW_LISTED/HIGH");
        expected.put(U + ">" + mgmt, "DENY_ALL/CRITICAL");
        expected.put(U + ">" + db, "DENY_ALL/CRITICAL");
        expected.put(dmz + ">" + app1, "ALLOW_LISTED/HIGH");
        expected.put(dmz + ">" + db, "DENY_ALL/CRITICAL");
        expected.put(user + ">" + db, "DENY_ALL/CRITICAL");
        expected.put(user + ">" + mgmt, "DENY_ALL/HIGH");
        expected.put(user + ">" + app2, "ALLOW_LISTED/MEDIUM");
        expected.put(app1 + ">" + db, "ALLOW_LISTED/HIGH");
        expected.put(app1 + ">" + app2, "ALLOW_ANY/LOW");
        expected.put(app1 + ">" + app1, "ALLOW_ANY/LOW");
        expected.put(mgmt + ">" + mgmt, "ALLOW_LISTED/MEDIUM");
        expected.put(mgmt + ">" + user, "ALLOW_LISTED/MEDIUM");
        expected.put(partner + ">" + mgmt, "DENY_ALL/CRITICAL");
        expected.put(partner + ">" + partner2, "DENY_ALL/CRITICAL");
        expected.put(nonprod + ">" + user, "DENY_ALL/HIGH");
        expected.put(db + ">" + nonprod, "DENY_ALL/HIGH");
        expected.put(nonprod + ">" + nonprod, "ALLOW_ANY/LOW");
        expected.put(user + ">" + U, "ALLOW_LISTED/MEDIUM");
        expected.put(internet + ">" + internet, "ALLOW_ANY/LOW");
        for (var e : expected.entrySet()) {
            String[] pair = e.getKey().split(">");
            var cell = m.cell(pair[0], pair[1]).orElseThrow(() -> new AssertionError("no cell " + e.getKey()));
            assertEquals(e.getValue(), cell.statement() + "/" + cell.severity(), e.getKey());
        }
        assertTrue(m.cell(partner, dmz).isEmpty(), "no row: version default");
        assertTrue(m.cells().stream().noneMatch(c -> c.source().equals(plain) || c.destination().equals(plain)),
                "a zone without a role stays on the version default");
        assertEquals("TEMPLATE_REPLACES_CELLS", assertThrows(Refused.class, () -> service.importTemplate("security-1", revision(), false)).code);
        service.importTemplate("security-1", revision(), true);
        assertEquals(m.cells(), draftContent().matrix().cells());
    }

    @Test void onlyComplianceConfirmsAndAChangeResetsTheConfirmation() {
        String app = zone("security-1", "Z-APP", "APPLICATION", "198.51.100.0/24");
        String db = zone("security-1", "Z-DB", "DATABASE", "203.0.113.0/25");
        service.saveServiceSet("security-1", revision(), "db", List.of("tcp/5432"));
        cell("security-1", app, db, "ALLOW_LISTED", List.of("set:db"), "HIGH", "Synthetic standard 1, article 2");
        cell("security-1", db, app, "DENY_ALL", List.of(), "HIGH", "");
        var content = draftContent();
        var target = content.matrix().cell(app, db).orElseThrow();
        String sha = content.cellSha256(target);
        assertEquals("PROPOSED", target.referenceStatus());
        for (String actor : List.of("security-1", "root-local"))
            assertEquals("COMPLIANCE_ROLE_REQUIRED", assertThrows(Refused.class,
                    () -> service.confirmReference(actor, false, revision(), app, db, sha)).code, actor);
        assertEquals("COMPLIANCE_ROLE_REQUIRED", assertThrows(Refused.class,
                () -> service.confirmReference("compliance-1", true, revision(), app, db, sha)).code);
        assertEquals("REQUEST_CHANGED", assertThrows(Refused.class,
                () -> service.confirmReference("compliance-1", false, revision(), app, db, "0".repeat(64))).code);
        String emptySha = content.cellSha256(content.matrix().cell(db, app).orElseThrow());
        assertEquals("REFERENCE_EMPTY", assertThrows(Refused.class,
                () -> service.confirmReference("compliance-1", false, revision(), db, app, emptySha)).code);
        assertTrue(store.confirmations.isEmpty());

        service.confirmReference("compliance-1", false, revision(), app, db, sha);
        var confirmed = draftContent().matrix().cell(app, db).orElseThrow();
        assertEquals("CONFIRMED", confirmed.referenceStatus());
        assertEquals("compliance-1", confirmed.confirmedBy());
        assertEquals("2026-11-14T09:00:00Z", confirmed.confirmedAt());
        assertEquals(1, store.confirmations.size());
        assertEquals(Long.toString(revision()), store.confirmations.get(0)[1]);
        assertEquals(sha, store.confirmations.get(0)[4]);
        assertEquals("REFERENCE_CONFIRM", store.kinds(store.draftId()).get(store.kinds(store.draftId()).size() - 1));
        assertEquals("ALREADY_CONFIRMED", assertThrows(Refused.class,
                () -> service.confirmReference("compliance-2", false, revision(), app, db, sha)).code);

        // a rationale-only change keeps the confirmation; the severity does not (ZM-23)
        service.setCell("security-1", revision(), new CellInput(app, db, "ALLOW_LISTED", List.of("set:db"), "HIGH", "synthetic note",
                "Synthetic standard 1, article 2"));
        assertEquals("CONFIRMED", draftContent().matrix().cell(app, db).orElseThrow().referenceStatus());
        cell("security-1", app, db, "ALLOW_LISTED", List.of("set:db"), "MEDIUM", "Synthetic standard 1, article 2");
        var reset = draftContent().matrix().cell(app, db).orElseThrow();
        assertEquals("PROPOSED", reset.referenceStatus());
        assertNull(reset.confirmedBy());
        assertNull(reset.confirmedAt());

        // changing the entries of a set the confirmed cell uses changes its services
        String sha2 = draftContent().cellSha256(reset);
        service.confirmReference("compliance-1", false, revision(), app, db, sha2);
        service.saveServiceSet("security-1", revision(), "db", List.of("tcp/5432", "tcp/1521"));
        assertEquals("PROPOSED", draftContent().matrix().cell(app, db).orElseThrow().referenceStatus());
        assertEquals("SERVICE_SET_IN_USE", assertThrows(Refused.class, () -> service.deleteServiceSet("security-1", revision(), "db")).code);
    }

    @Test void aConfirmerIsAParticipantOfTheFourEyesRule() {
        String app = zone("security-1", "Z-APP", "APPLICATION", "198.51.100.0/24");
        String db = zone("security-1", "Z-DB", "DATABASE", "203.0.113.0/25");
        cell("security-1", app, db, "DENY_ALL", List.of(), "HIGH", "Synthetic standard 1, article 2");
        var content = draftContent();
        service.confirmReference("compliance-1", false, revision(), app, db, content.cellSha256(content.matrix().cell(app, db).orElseThrow()));
        assertEquals("SELF_APPROVAL", assertThrows(Refused.class, () -> publish("compliance-1", null)).code);
        assertEquals(1, ((Number) publish("security-2", null).get("number")).intValue());
    }

    @Test void theDiffListsEveryLooseningChangeAndPublishNeedsARationale() {
        String app = zone("security-1", "Z-APP", "APPLICATION", "198.51.100.0/24");
        String db = zone("security-1", "Z-DB", "DATABASE", "203.0.113.0/25");
        String mgmt = zone("security-1", "Z-MGMT", "MANAGEMENT", "203.0.113.128/26");
        cell("security-1", app, db, "ALLOW_LISTED", List.of("tcp/5432"), "HIGH", "");
        cell("security-1", db, app, "DENY_ALL", List.of(), "CRITICAL", "");
        cell("security-1", app, mgmt, "DENY_ALL", List.of(), "HIGH", "");
        cell("security-1", U, mgmt, "DENY_ALL", List.of(), "CRITICAL", "");
        assertEquals(List.of(), looseningKinds(), "the first version is a definition, not a loosening");
        publish("compliance-1", null);

        // tightening only: no rationale needed
        cell("security-2", app, db, "DENY_ALL", List.of(), "CRITICAL", "");
        assertEquals(List.of(), looseningKinds());
        assertEquals(false, diff().get("rationale_required"));
        publish("compliance-1", null);

        // every loosening kind of section 3.5
        cell("security-2", app, db, "ALLOW_LISTED", List.of("tcp/5432"), "CRITICAL", "");     // statement relaxed
        cell("security-2", db, app, "DENY_ALL", List.of(), "LOW", "");                        // severity downgraded
        service.setDefault("security-2", revision(), "ALLOW_LISTED", "MEDIUM");               // default changed
        service.updateZone("security-2", revision(), mgmt, new ZoneInput("Z-MGMT", "", "MANAGEMENT",
                List.of("203.0.113.128/26", "192.0.2.0/24")));                                // UNASSIGNED shrinks into a zone
        service.updateZone("security-2", revision(), db, new ZoneInput("Z-DB", "", "DATABASE", List.of("203.0.113.0/26")));
        service.updateZone("security-2", revision(), app, new ZoneInput("Z-APP", "", "APPLICATION",
                List.of("198.51.100.0/24", "203.0.113.64/26")));                              // addresses leave Z-DB
        var kinds = looseningKinds();
        for (String kind : List.of("STATEMENT_RELAXED", "SEVERITY_DOWNGRADED", "DEFAULT_CHANGED", "ADDRESSES_MOVED", "UNASSIGNED_SHRUNK"))
            assertTrue(kinds.contains(kind), kind + " in " + kinds);
        assertEquals(true, diff().get("rationale_required"));
        assertEquals("RATIONALE_REQUIRED", assertThrows(Refused.class, () -> publish("compliance-1", null)).code);
        assertEquals(3, ((Number) publish("compliance-1", "synthetic rationale").get("number")).intValue());

        // services added, also through a named set
        service.saveServiceSet("security-2", null, "db", List.of("tcp/5432"));
        cell("security-2", app, db, "ALLOW_LISTED", List.of("set:db"), "CRITICAL", "");
        assertEquals(List.of(), looseningKinds(), "the same ports through a set are not an addition");
        service.saveServiceSet("security-2", revision(), "db", List.of("tcp/5432", "tcp/1521"));
        assertEquals(List.of("SERVICES_ADDED"), looseningKinds());
    }

    @Test void unassignedShrinkingIntoAZoneAsStrictAsUnassignedIsNotLoosening() {
        String app = zone("security-1", "Z-APP", "APPLICATION", "198.51.100.0/24");
        publish("compliance-1", null);
        // the default is DENY_ALL everywhere, so a new zone carved out of UNASSIGNED is not less strict
        zone("security-2", "Z-NEW", null, "192.0.2.0/24");
        assertEquals(List.of(), looseningKinds());
        // one explicit allow from the new zone makes it less strict than UNASSIGNED was
        String fresh = draftContent().zones().stream().filter(z -> z.name().equals("Z-NEW")).findFirst().orElseThrow().id();
        cell("security-2", fresh, app, "ALLOW_ANY", List.of(), "LOW", "");
        assertEquals(List.of("UNASSIGNED_SHRUNK"), looseningKinds());
    }

    @Test @SuppressWarnings("unchecked") void theMaskedViewHidesNamesTextAndPeople() {
        String app = zone("security-1", "Z-APP", "APPLICATION", "198.51.100.0/24");
        String db = zone("security-1", "Z-DB", "DATABASE", "203.0.113.0/25");
        service.saveServiceSet("security-1", revision(), "core-db", List.of("tcp/5432"));
        service.setCell("security-1", revision(), new CellInput(app, db, "ALLOW_LISTED", List.of("set:core-db", "icmp/8", "icmp6/128"), "HIGH",
                "synthetic note", "Synthetic standard 1"));
        var content = draftContent();
        service.confirmReference("compliance-1", false, revision(), app, db, content.cellSha256(content.matrix().cell(app, db).orElseThrow()));
        publish("security-2", null);
        String id = store.active().orElseThrow().versionId();
        var masked = (Map<String, Object>) service.version(id, View.MASKED, "aiview", false).get("matrix");
        var cell = ((List<Map<String, Object>>) masked.get("cells")).get(0);
        assertTrue(Set.of("ZONE-1", "ZONE-2").contains(cell.get("source_name")));
        assertNull(cell.get("rationale"));
        assertNull(cell.get("regulation_reference"));
        assertTrue(((String) cell.get("confirmed_by")).startsWith("USER-"));
        assertEquals("CONFIRMED", cell.get("reference_status"));
        var services = (List<Map<String, Object>>) cell.get("services");
        assertEquals("icmp/8", services.get(0).get("entry"));
        assertEquals(true, services.get(0).get("evaluable"));
        assertEquals("icmp6/128", services.get(1).get("entry"));
        assertEquals(false, services.get(1).get("evaluable"));
        assertEquals("set:SET-1", services.get(2).get("entry"));
        assertEquals("SET-1", ((List<Map<String, Object>>) masked.get("service_sets")).get(0).get("name"));
        assertFalse(masked.toString().contains("Z-APP") || masked.toString().contains("core-db") || masked.toString().contains("compliance-1"));
        var names = (Map<String, Object>) service.version(id, View.NAMES, "viewer-1", false).get("matrix");
        assertEquals(1, names.get("cells_confirmed"));
        assertEquals(1, names.get("cells_with_reference"));
        assertEquals("Synthetic standard 1", ((List<Map<String, Object>>) names.get("cells")).get(0).get("regulation_reference"));
    }
}
