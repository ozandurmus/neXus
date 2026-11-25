package com.securityexpert.nexus.ui2.service.zonematrix;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.PAN_LOCAL_DEFAULT;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.PAN_LOCAL_RULES;
import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.policy.PolicySchedule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.*;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Zone matrix delivery step 3 unit tests (docs/design/ZONE_MATRIX_CONTRACT.md sections 3.3, 3.4, 4, 5.2-5.9, 7.1-7.2,
 * 8.1-8.2, 9 and 10.1 item 3; ZM-3 through ZM-21, ZM-24 through ZM-27, ZM-30, ZM-33).
 *
 * <p>Synthetic zone names, device pseudonyms and RFC 5737 / RFC 3849 documentation blocks only (plus synthetic RFC 1918
 * and RFC 4193 ranges exclusively for the section 3.3 unassigned-private-space gap check).</p>
 */
class ZoneMatrixEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-11-15T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Cell ANY_CELL = new Cell(List.of("obj-any"), false);
    private static final PolicyObject OBJ_ANY = new PolicyObject("obj-any", "ANY", "any", List.of(), List.of("any"), "RESOLVED");

    private record MatrixFixture(ZoneMatrixService service, ZoneMatrixServiceTest.Memory store,
                                 String versionId, int versionNumber, ZoneMatrixContent content,
                                 String zUser, String zApp, String zDb, String zDmz,
                                 Map<String, PolicySnapshot> snapshots) {}

    private static MatrixFixture buildStandardMatrix(DeviceRepository deviceRepo) {
        var store = new ZoneMatrixServiceTest.Memory();
        var snapshots = new LinkedHashMap<String, PolicySnapshot>();
        PolicyCatalog catalog = new PolicyCatalog() {
            @Override public List<Map<String, Object>> catalogViews() {
                List<Map<String, Object>> out = new ArrayList<>();
                for (PolicySnapshot s : snapshots.values()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.metadata().id());
                    m.put("name", s.metadata().name());
                    m.put("vendor", s.metadata().vendor());
                    m.put("sourceId", s.metadata().sourceId());
                    m.put("sourceName", s.metadata().sourceName());
                    m.put("containerId", s.metadata().containerId());
                    m.put("containerName", s.metadata().containerName());
                    m.put("collectedAt", s.metadata().collectedAt());
                    m.put("ruleCount", s.ruleCount());
                    m.put("targets", s.metadata().targets());
                    out.add(m);
                }
                return out;
            }
            @Override public Optional<PolicySnapshot> find(String policyId) {
                return Optional.ofNullable(snapshots.get(policyId));
            }
        };
        byte[] key = "synthetic-zone-matrix-step3-key!".getBytes(StandardCharsets.UTF_8);
        var service = new ZoneMatrixService(
                store,
                new Principals() {
                    @Override public boolean holdsComplianceAdmin(String actor) { return actor.startsWith("compliance"); }
                    @Override public boolean isRoot(String actor) { return "root-local".equals(actor); }
                },
                MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC),
                catalog,
                deviceRepo,
                new TopologyNamePseudonymizer(key),
                new SubnetPreservingIpMasker(key));

        String zUser = (String) service.createZone("security-1", null,
                new ZoneInput("Z-USER", "Synthetic user zone", "USER", List.of("192.0.2.0/25"))).get("zone_id");
        long rev = store.draft().orElseThrow().revision();
        String zDmz = (String) service.createZone("security-1", rev,
                new ZoneInput("Z-DMZ", "Synthetic DMZ zone", "DMZ", List.of("192.0.2.128/25"))).get("zone_id");
        rev = store.draft().orElseThrow().revision();
        String zApp = (String) service.createZone("security-1", rev,
                new ZoneInput("Z-APP", "Synthetic application zone", "APPLICATION", List.of("198.51.100.0/24"))).get("zone_id");
        rev = store.draft().orElseThrow().revision();
        String zDb = (String) service.createZone("security-1", rev,
                new ZoneInput("Z-DB", "Synthetic database zone", "DATABASE", List.of("203.0.113.0/24"))).get("zone_id");

        rev = store.draft().orElseThrow().revision();
        service.saveServiceSet("security-1", rev, "web", List.of("tcp/80", "tcp/443"));
        rev = store.draft().orElseThrow().revision();
        service.setCell("security-1", rev,
                new CellInput(zUser, zApp, "ALLOW_LISTED", List.of("set:web"), "HIGH", "web tier", ""));
        rev = store.draft().orElseThrow().revision();
        service.setCell("security-1", rev,
                new CellInput(zApp, zDb, "ALLOW_LISTED", List.of("tcp/5432"), "CRITICAL", "db tier", ""));
        rev = store.draft().orElseThrow().revision();
        service.setCell("security-1", rev,
                new CellInput(zUser, zDmz, "ALLOW_ANY", List.of(), "MEDIUM", "dmz access", ""));
        // zUser -> zDb stays default DENY_ALL (HIGH), zDmz -> zDb stays default DENY_ALL (HIGH)

        var draft = store.draft().orElseThrow();
        var pub = service.publish("compliance-1", false, new PublishRequest(draft.revision(), draft.contentSha256(), null));
        String versionId = (String) pub.get("version_id");
        int number = ((Number) pub.get("number")).intValue();
        ZoneMatrixContent content = ZoneMatrixContent.parse(store.active().orElseThrow().content(), MAPPER);
        return new MatrixFixture(service, store, versionId, number, content, zUser, zApp, zDb, zDmz, snapshots);
    }

    private static Metadata meta(String id, String vendor, String name) {
        return new Metadata(id, "src-1", "FW-TANGO-04", vendor, "dom-1", "DOM-BRAVO-02", name,
                "2026-11-15T08:00:00Z", "art-1", List.of(new Target("gw-1", "FW-JULIET-06", "vsys1", "IN_SYNC")));
    }

    private static PolicyObject addr(String id, String name, String... cidrs) {
        List<String> values = new ArrayList<>(cidrs.length);
        for (String c : cidrs) {
            values.add(c.contains(": ") ? c : "ip-netmask: " + c);
        }
        return new PolicyObject(id, name, "address", List.of(), values, "RESOLVED");
    }

    private static PolicyObject svc(String id, String name, String... specs) {
        List<String> values = new ArrayList<>(specs.length);
        for (String s : specs) {
            if (s.contains(": ")) {
                values.add(s);
            } else {
                int slash = s.indexOf('/');
                String proto = slash > 0 ? s.substring(0, slash) : "tcp";
                String port = slash > 0 ? s.substring(slash + 1) : s;
                values.add("protocol/" + proto + "/port: " + port);
            }
        }
        return new PolicyObject(id, name, "service", List.of(), values, "RESOLVED");
    }

    private static Section panSection(String metaId, String phase, String name, List<Rule> rules) {
        return new Section(PolicySnapshot.ref(metaId, "PAN device-group", phase), name, "PAN device-group", null, rules);
    }

    private static Rule rule(String id, int num, String name, Boolean enabled, Cell src, Cell dst, Cell service,
                             Cell app, String action, Map<String, List<String>> extras) {
        return new Rule(id, "uid-" + id, num, name, enabled, src, dst, service, app, action, "Log", "", extras);
    }

    @Test
    void zm3_zm4_zm5_zm9_statementsVendorZoneIgnoreAndDeterministicCitation() {
        var fx = buildStandardMatrix(null);
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("u1", addr("u1", "USER-SUBNET", "192.0.2.0/26"));
        objects.put("a1", addr("a1", "APP-SUBNET", "198.51.100.0/25"));
        objects.put("d1", addr("d1", "DB-SUBNET", "203.0.113.10/32"));
        objects.put("https", svc("https", "HTTPS", "tcp/443"));
        objects.put("ssh", svc("ssh", "SSH", "tcp/22"));

        // ZM-4: vendor from/to security zones claim "DMZ -> DMZ" (which would be allowed if vendor zones were trusted),
        // while the IP addresses are Z-USER (192.0.2.0/26) -> Z-DB (203.0.113.10/32), which is DENY_ALL!
        Rule r1 = rule("r1", 1, "Compliant-Web", true,
                new Cell(List.of("u1"), false), new Cell(List.of("a1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow",
                Map.of("from", List.of("TRUST"), "to", List.of("UNTRUST")));
        Rule r2 = rule("r2", 2, "Disallowed-Port-In-AllowListed", true,
                new Cell(List.of("u1"), false), new Cell(List.of("a1"), false),
                new Cell(List.of("https", "ssh"), false), ANY_CELL, "allow", Map.of());
        Rule r3 = rule("r3", 3, "VendorZoneContradiction-DenyAll", true,
                new Cell(List.of("u1"), false), new Cell(List.of("d1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow",
                Map.of("from", List.of("Z-USER"), "to", List.of("Z-DMZ")));

        PolicySnapshot snap = new PolicySnapshot(meta("pol-pan-1", "PAN", "PAN-Core"),
                List.of(panSection("pol-pan-1", "pre-rulebase", "Pre rules", List.of(r1, r2, r3))), objects);

        EvaluationResult first = ZoneMatrixEvaluator.evaluate(snap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
        EvaluationResult second = ZoneMatrixEvaluator.evaluate(snap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);

        // ZM-3 & ZM-9: deterministic identical evaluation + version citation
        assertEquals(fx.versionId(), first.version().versionId());
        assertEquals(fx.versionNumber(), first.version().versionNumber());
        assertEquals(fx.content().sha256(), first.version().contentSha256());
        assertEquals(first.rules(), second.rules());
        assertEquals(first.headline(), second.headline());

        assertEquals(OUTCOME_COMPLIANT, first.rules().get(0).outcome());
        assertEquals(OUTCOME_VIOLATION, first.rules().get(1).outcome());
        assertEquals(List.of("tcp/22"), first.rules().get(1).violatedCells().get(0).notAllowedServices());

        // ZM-4: r3 violates Z-USER -> Z-DB despite vendor zones claiming Z-DMZ
        RuleEvaluation evalR3 = first.rules().get(2);
        assertEquals(OUTCOME_VIOLATION, evalR3.outcome());
        assertEquals(fx.zUser(), evalR3.violatedCells().get(0).sourceZone());
        assertEquals(fx.zDb(), evalR3.violatedCells().get(0).destinationZone());
    }

    @Test
    void zm5_zm41_unassignedToUnassignedStaysDenyAllEvenWhenVersionDefaultIsAllowListed() {
        var store = new ZoneMatrixServiceTest.Memory();
        var svc = new ZoneMatrixService(store, new Principals() {
            @Override public boolean holdsComplianceAdmin(String actor) { return actor.startsWith("compliance"); }
            @Override public boolean isRoot(String actor) { return false; }
        }, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
        var created = svc.createZone("security-1", null, new ZoneInput("Z-APP", "", "APPLICATION", List.of("198.51.100.0/24")));
        long rev = ((Number) created.get("revision")).longValue();
        svc.setDefault("security-1", rev, "ALLOW_LISTED", "LOW");
        var draft = store.draft().orElseThrow();
        svc.publish("compliance-1", false, new PublishRequest(draft.revision(), draft.contentSha256(), "allow-listed default test"));
        ZoneMatrixContent content = ZoneMatrixContent.parse(store.active().orElseThrow().content(), MAPPER);

        Map<String, PolicyObject> objects = Map.of(
                "obj-any", OBJ_ANY,
                "un1", addr("un1", "EXT-A", "203.0.113.1/32"),
                "un2", addr("un2", "EXT-B", "203.0.113.2/32"),
                "https", svc("https", "HTTPS", "tcp/443"));
        Rule r = rule("r1", 1, "UnassignedToUnassigned", true,
                new Cell(List.of("un1"), false), new Cell(List.of("un2"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", Map.of());
        PolicySnapshot snap = new PolicySnapshot(meta("pol-u", "PAN", "PAN-Unassigned"),
                List.of(panSection("pol-u", "pre-rulebase", "Pre rules", List.of(r))), objects);

        EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, "v-1", 1, content, NOW);
        assertEquals(OUTCOME_VIOLATION, res.rules().get(0).outcome());
        assertEquals("UNASSIGNED", res.rules().get(0).violatedCells().get(0).sourceZone());
        assertEquals("UNASSIGNED", res.rules().get(0).violatedCells().get(0).destinationZone());
        assertEquals("DENY_ALL", res.rules().get(0).violatedCells().get(0).statement());
    }

    @Test
    void zm6_zm24_zm25_notApplicableCountsSchedulesAndHygieneCoverFlag() {
        var fx = buildStandardMatrix(null);
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("u1", addr("u1", "USER", "192.0.2.0/26"));
        objects.put("d1", addr("d1", "DB", "203.0.113.0/26"));
        objects.put("https", svc("https", "HTTPS", "tcp/443"));
        objects.put("sched-ended", new PolicyObject("sched-ended", "OLD-MAINT", "schedule", List.of(), List.of(), "RESOLVED",
                new PolicySchedule("one-time", "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", List.of(), "UTC", true)));
        // Unknown timezone ended only 2 hours ago (< 14h margin) -> NOT ended yet, stays TIME_IGNORED!
        objects.put("sched-recent-unknown-tz", new PolicyObject("sched-recent-unknown-tz", "RECENT-LOCAL", "schedule", List.of(), List.of(), "RESOLVED",
                new PolicySchedule("one-time", "2026-11-15T01:00", "2026-11-15T07:00", List.of(), "UTC", false)));

        Map<String, List<String>> zonesAny = Map.of("from", List.of("any"), "to", List.of("any"));
        Rule rDisabled = rule("r-dis", 1, "DisabledRule", false,
                new Cell(List.of("u1"), false), new Cell(List.of("d1"), false), new Cell(List.of("https"), false), ANY_CELL, "allow", zonesAny);
        Rule rDeny = rule("r-deny", 2, "EarlierDeny", true,
                new Cell(List.of("u1"), false), new Cell(List.of("d1"), false), new Cell(List.of("https"), false), ANY_CELL, "deny", zonesAny);
        // Covered by rDeny above -> ZM-25: still VIOLATION, carries COVERED_BY_EARLIER_DENY!
        Rule rShadowedViolation = rule("r-cov", 3, "ShadowedAllow", true,
                new Cell(List.of("u1"), false), new Cell(List.of("d1"), false), new Cell(List.of("https"), false), ANY_CELL, "allow", zonesAny);
        Rule rEnded = rule("r-ended", 4, "EndedWindow", true,
                new Cell(List.of("u1"), false), new Cell(List.of("d1"), false), new Cell(List.of("https"), false), ANY_CELL, "allow",
                Map.of("from", List.of("any"), "to", List.of("any"), "schedule", List.of("sched-ended")));
        Rule rWithinMargin = rule("r-margin", 5, "WithinTzMargin", true,
                new Cell(List.of("u1"), false), new Cell(List.of("d1"), false), new Cell(List.of("https"), false), ANY_CELL, "allow",
                Map.of("from", List.of("any"), "to", List.of("any"), "schedule", List.of("sched-recent-unknown-tz")));

        PolicySnapshot snap = new PolicySnapshot(meta("pol-na", "PAN", "PAN-Schedules"),
                List.of(panSection("pol-na", "pre-rulebase", "Pre rules",
                        List.of(rDisabled, rDeny, rShadowedViolation, rEnded, rWithinMargin))), objects);

        EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
        assertEquals(1, res.notApplicable().disabled());
        assertEquals(1, res.notApplicable().denyType());
        assertEquals(1, res.notApplicable().scheduleEnded());
        assertEquals("schedule ended 2026-10-02", res.notApplicable().endedSchedules().get(0).detail());

        RuleEvaluation shadowed = res.rules().stream().filter(r -> "r-cov".equals(r.ruleId())).findFirst().orElseThrow();
        assertEquals(OUTCOME_VIOLATION, shadowed.outcome());
        assertTrue(shadowed.flags().contains("COVERED_BY_EARLIER_DENY"));
        assertEquals("r-deny", shadowed.coveringRuleId());

        RuleEvaluation marginRule = res.rules().stream().filter(r -> "r-margin".equals(r.ruleId())).findFirst().orElseThrow();
        assertEquals(OUTCOME_VIOLATION, marginRule.outcome());
        assertTrue(marginRule.flags().contains("TIME_IGNORED"));
    }

    @Test
    void zm14_paloAltoPublicDestinationNatToDenyAllServerNeverCompliantAndCpGroupWithExclusionFailsClosed() {
        var fx = buildStandardMatrix(null);
        // Set Z-USER -> UNASSIGNED to ALLOW_ANY so pre-NAT (Z-USER -> public VIP in UNASSIGNED) is completely compliant,
        // proving ZM-14: because destination-NAT translates the public VIP to a server in Z-DB (DENY_ALL), the rule is
        // NEVER COMPLIANT!
        var store = new ZoneMatrixServiceTest.Memory();
        var svc = new ZoneMatrixService(store, new Principals() {
            @Override public boolean holdsComplianceAdmin(String actor) { return actor.startsWith("compliance"); }
            @Override public boolean isRoot(String actor) { return false; }
        }, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
        String zUser = (String) svc.createZone("security-1", null, new ZoneInput("Z-USER", "", "USER", List.of("192.0.2.0/25"))).get("zone_id");
        long rev = store.draft().orElseThrow().revision();
        String zDb = (String) svc.createZone("security-1", rev, new ZoneInput("Z-DB", "", "DATABASE", List.of("203.0.113.0/24"))).get("zone_id");
        rev = store.draft().orElseThrow().revision();
        svc.setCell("security-1", rev, new CellInput(zUser, "UNASSIGNED", "ALLOW_ANY", List.of(), "LOW", "public egress", ""));
        // zUser -> zDb remains default DENY_ALL
        var draft = store.draft().orElseThrow();
        svc.publish("compliance-1", false, new PublishRequest(draft.revision(), draft.contentSha256(), null));
        ZoneMatrixContent content = ZoneMatrixContent.parse(store.active().orElseThrow().content(), MAPPER);

        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("u1", addr("u1", "USER", "192.0.2.10/32"));
        objects.put("pub-vip", addr("pub-vip", "PUBLIC-VIP", "198.18.0.50/32")); // UNASSIGNED
        objects.put("db-srv", addr("db-srv", "DB-SERVER", "203.0.113.50/32")); // Z-DB (DENY_ALL)
        objects.put("https", svc("https", "HTTPS", "tcp/443"));

        Rule secRule = rule("r-dnat", 1, "AllowPublicVip", true,
                new Cell(List.of("u1"), false), new Cell(List.of("pub-vip"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", Map.of());
        Rule natRule = rule("nat-dnat", 1, "PublicVipToDb", true,
                ANY_CELL, new Cell(List.of("pub-vip"), false), ANY_CELL, ANY_CELL, "destination-nat",
                Map.of("translated-destination", List.of("db-srv")));

        PolicySnapshot panSnap = new PolicySnapshot(meta("pol-zm14", "PAN", "PAN-ZM14"),
                List.of(
                        panSection("pol-zm14", "pre-rulebase", "Pre rules", List.of(secRule)),
                        new Section("s-nat", PolicySnapshot.PAN_PRE_NAT, "PAN device-group", null, List.of(natRule))),
                objects);

        EvaluationResult panRes = ZoneMatrixEvaluator.evaluate(panSnap, "v-zm14", 1, content, NOW);
        RuleEvaluation eval = panRes.rules().get(0);
        assertEquals(OUTCOME_VIOLATION, eval.outcome(), "ZM-14: DNAT public address translated to DENY_ALL server is VIOLATION");
        assertTrue(eval.flags().contains("NAT_MAY_APPLY"));
        assertEquals(zDb, eval.violatedCells().get(0).destinationZone());

        // Check Point group-with-exclusion mapped per CpObjectTypes (type="group", status="UNSUPPORTED") fails closed
        Map<String, PolicyObject> cpObjects = new LinkedHashMap<>(objects);
        cpObjects.put("gwe-unsupported", new PolicyObject("gwe-unsupported", "GWE-1", "group", List.of(), List.of(), "UNSUPPORTED"));
        Rule cpGwe = rule("r-gwe", 1, "CpGroupWithExclusion", true,
                new Cell(List.of("u1"), false), new Cell(List.of("gwe-unsupported"), false),
                new Cell(List.of("https"), false), ANY_CELL, "Accept", Map.of("layer-name", List.of("Network")));
        PolicySnapshot cpSnap = new PolicySnapshot(meta("pol-cp-gwe", "CP", "CP-GWE"),
                List.of(new Section("s1", "Network", "CP access layer", null, List.of(cpGwe))), cpObjects);
        EvaluationResult cpRes = ZoneMatrixEvaluator.evaluate(cpSnap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
        assertEquals(OUTCOME_NOT_EVALUABLE, cpRes.rules().get(0).outcome());
        assertEquals("dynamic or unsupported object type", cpRes.rules().get(0).reason());
    }

    @Test
    void zm15_natDualViewPortSplitVipBiDirectionalStaticNatAndUnresolvedNat() {
        var fx = buildStandardMatrix(null);
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("u1", addr("u1", "USER", "192.0.2.10/32"));
        objects.put("vip-dmz", addr("vip-dmz", "DMZ-VIP", "192.0.2.200/32")); // in Z-DMZ (ALLOW_ANY from Z-USER)
        objects.put("real-db", addr("real-db", "DB-REAL", "203.0.113.50/32")); // in Z-DB (DENY_ALL from Z-USER)
        objects.put("app-host", addr("app-host", "APP-HOST", "198.51.100.20/32"));
        objects.put("vip-unassigned", addr("vip-unassigned", "PUBLIC-VIP", "198.18.0.10/32"));
        objects.put("unresolved-target", new PolicyObject("unresolved-target", "MISSING-OBJ", "unresolved", List.of(), List.of(), "UNRESOLVED"));
        objects.put("https", svc("https", "HTTPS", "tcp/443"));
        objects.put("ssh", svc("ssh", "SSH", "tcp/22"));

        // 1) Port-split VIP: Z-USER -> DMZ-VIP (192.0.2.200) on tcp/443 + tcp/22.
        // Pre-NAT is Z-USER -> Z-DMZ (ALLOW_ANY => compliant!).
        // NAT rule translates DMZ-VIP tcp/443 -> DB-REAL (203.0.113.50, Z-DB => DENY_ALL!), leaving tcp/22 in Z-DMZ.
        Rule sec1 = rule("sec-vip", 1, "AllowToDmzVip", true,
                new Cell(List.of("u1"), false), new Cell(List.of("vip-dmz"), false),
                new Cell(List.of("https", "ssh"), false), ANY_CELL, "allow", Map.of());

        // 2) Reverse bi-directional static NAT (PAN): Z-USER -> PUBLIC-VIP (198.18.0.10) where a bi-directional source
        // static NAT maps internal DB-REAL <-> PUBLIC-VIP; post-NAT destination becomes DB-REAL (Z-USER -> Z-DB = DENY_ALL).
        Rule sec2 = rule("sec-bidir", 2, "AllowToBiDirVip", true,
                new Cell(List.of("u1"), false), new Cell(List.of("vip-unassigned"), false),
                new Cell(List.of("ssh"), false), ANY_CELL, "allow", Map.of());

        // 3) Unresolved NAT overlapping APP-HOST
        Rule sec3 = rule("sec-unres-nat", 3, "AllowAppWeb", true,
                new Cell(List.of("u1"), false), new Cell(List.of("app-host"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", Map.of());

        Rule natPortSplitDb = rule("nat-1a", 1, "PortSplitDnatDb", true,
                ANY_CELL, new Cell(List.of("vip-dmz"), false), new Cell(List.of("https"), false), ANY_CELL, "destination-nat",
                Map.of("translated-destination", List.of("real-db")));
        Rule natPortSplitApp = rule("nat-1b", 2, "PortSplitDnatApp", true,
                ANY_CELL, new Cell(List.of("vip-dmz"), false), new Cell(List.of("ssh"), false), ANY_CELL, "destination-nat",
                Map.of("translated-destination", List.of("app-host")));
        Rule natBiDir = rule("nat-2", 3, "BiDirStaticNat", true,
                new Cell(List.of("real-db"), false), ANY_CELL, ANY_CELL, ANY_CELL, "source-nat",
                Map.of("source-translation-type", List.of("static-ip"),
                        "source-translation-bi-directional", List.of("yes"),
                        "translated-source", List.of("vip-unassigned")));
        Rule natUnresolved = rule("nat-3", 4, "BrokenDnat", true,
                ANY_CELL, new Cell(List.of("app-host"), false), ANY_CELL, ANY_CELL, "destination-nat",
                Map.of("translated-destination", List.of("unresolved-target")));

        PolicySnapshot snap = new PolicySnapshot(meta("pol-nat", "PAN", "PAN-NAT"),
                List.of(
                        panSection("pol-nat", "pre-rulebase", "Pre rules", List.of(sec1, sec2, sec3)),
                        new Section("s-nat", "Pre NAT rules", "PAN device-group", null,
                                List.of(natPortSplitDb, natPortSplitApp, natBiDir, natUnresolved))),
                objects);

        EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
        assertEquals(4, res.notApplicable().outsideSecurityRulebase());

        RuleEvaluation r1 = res.rules().get(0);
        assertEquals(OUTCOME_VIOLATION, r1.outcome());
        assertEquals("TRANSLATED", r1.natViewSummary());
        assertTrue(r1.flags().contains("NAT_MAY_APPLY"));
        assertTrue(r1.flags().contains("NAT_PORT_SPLIT"));
        assertTrue(r1.violatedCells().stream().anyMatch(cv -> fx.zDb().equals(cv.destinationZone())
                && List.of("tcp/443").equals(cv.notAllowedServices())));

        RuleEvaluation r2 = res.rules().get(1);
        assertEquals(OUTCOME_VIOLATION, r2.outcome());
        assertTrue(r2.violatedCells().stream().anyMatch(cv -> fx.zDb().equals(cv.destinationZone())
                && cv.natView().startsWith("NAT:")));

        RuleEvaluation r3 = res.rules().get(2);
        assertEquals(OUTCOME_NOT_EVALUABLE, r3.outcome());
        assertEquals("NAT translation unresolved", r3.reason());
    }

    @Test
    void zm16_checkPointMultipleOrderedLayersCheckWitnessAgainstOtherLayersAndHeadlineExcludesUnproven() {
        var fx = buildStandardMatrix(null);
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("policy-targets", new PolicyObject("policy-targets", "Policy Targets", "targets",
                List.of(), List.of("native-type: Global"), "UNSUPPORTED"));
        objects.put("u1", addr("u1", "USER-1", "192.0.2.10/32"));
        objects.put("u2", addr("u2", "USER-2", "192.0.2.20/32"));
        objects.put("u3", addr("u3", "USER-3", "192.0.2.30/32"));
        objects.put("d1", addr("d1", "DB", "203.0.113.10/32"));
        objects.put("https", svc("https", "HTTPS", "tcp/443"));
        objects.put("dyn-obj", new PolicyObject("dyn-obj", "DYN", "dynamic-object", List.of(), List.of(), "DYNAMIC"));

        // Ordered layer 1 ("Network"): three Accept rules for u1->d1, u2->d1, u3->d1 (all using Policy Targets).
        Map<String, List<String>> l1Ex = Map.of(
                "layer-name", List.of("Network"),
                "install-on", List.of("policy-targets"),
                "time", List.of("obj-any"));
        Rule l1BlockedByL2 = rule("l1-r1", 1, "AllowedInL1DroppedInL2", true,
                new Cell(List.of("u1"), false), new Cell(List.of("d1"), false), new Cell(List.of("https"), false), ANY_CELL, "Accept", l1Ex);
        Rule l1AllowedByL2 = rule("l1-r2", 2, "AllowedInBothLayers", true,
                new Cell(List.of("u2"), false), new Cell(List.of("d1"), false), new Cell(List.of("https"), false), ANY_CELL, "Accept", l1Ex);
        Rule l1UnknownInL2 = rule("l1-r3", 3, "AllowedInL1UnknownInL2", true,
                new Cell(List.of("u3"), false), new Cell(List.of("d1"), false), new Cell(List.of("https"), false), ANY_CELL, "Accept", l1Ex);
        Rule l1Cleanup = rule("l1-clean", 4, "Cleanup", true, ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL, "Drop", l1Ex);

        // Ordered layer 2 ("Application"):
        // - drops u1->d1
        // - accepts u2->d1
        // - hits dynamic-object rule before cleanup for u3->d1 (making L2 verdict UNKNOWN!)
        Map<String, List<String>> l2Ex = Map.of(
                "layer-name", List.of("Application"),
                "install-on", List.of("policy-targets"),
                "time", List.of("obj-any"));
        Rule l2DropU1 = rule("l2-r1", 1, "DropU1", true,
                new Cell(List.of("u1"), false), new Cell(List.of("d1"), false), ANY_CELL, ANY_CELL, "Drop", l2Ex);
        Rule l2AllowU2 = rule("l2-r2", 2, "AllowU2", true,
                new Cell(List.of("u2"), false), new Cell(List.of("d1"), false), ANY_CELL, ANY_CELL, "Accept", l2Ex);
        Rule l2Dyn = rule("l2-r3", 3, "DynamicGate", true,
                new Cell(List.of("dyn-obj"), false), new Cell(List.of("d1"), false), ANY_CELL, ANY_CELL, "Drop", l2Ex);
        Rule l2Cleanup = rule("l2-clean", 4, "Cleanup2", true, ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL, "Drop", l2Ex);

        PolicySnapshot snap = new PolicySnapshot(meta("pol-cp-ord", "CP", "CP-TwoLayers"),
                List.of(
                        new Section("sec-l1", "Network", "CP access layer", null, List.of(l1BlockedByL2, l1AllowedByL2, l1UnknownInL2, l1Cleanup)),
                        new Section("sec-l2", "Application", "CP access layer", null, List.of(l2DropU1, l2AllowU2, l2Dyn, l2Cleanup))),
                objects);

        EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
        Map<String, RuleEvaluation> byId = new HashMap<>();
        res.rules().forEach(r -> byId.put(r.ruleId(), r));

        // 1) u1->d1 dropped by Layer 2 -> VIOLATION + DENIED_BY_OTHER_ORDERED_LAYER (excluded from headline, ZM-16)
        assertEquals(OUTCOME_VIOLATION, byId.get("l1-r1").outcome());
        assertTrue(byId.get("l1-r1").flags().contains("DENIED_BY_OTHER_ORDERED_LAYER"));
        // 2) u2->d1 accepted by Layer 2 -> proven VIOLATION in headline without ordered-layer flag
        assertEquals(OUTCOME_VIOLATION, byId.get("l1-r2").outcome());
        assertFalse(byId.get("l1-r2").flags().contains("DENIED_BY_OTHER_ORDERED_LAYER"));
        assertFalse(byId.get("l1-r2").flags().contains("OTHER_ORDERED_LAYERS_UNKNOWN"));
        // 3) u3->d1 hits dynamic rule in Layer 2 -> VIOLATION + OTHER_ORDERED_LAYERS_UNKNOWN (excluded from headline)
        assertEquals(OUTCOME_VIOLATION, byId.get("l1-r3").outcome());
        assertTrue(byId.get("l1-r3").flags().contains("OTHER_ORDERED_LAYERS_UNKNOWN"));

        // Headline counts l1-r2 + l2-r2 as proven violating rules, and l1-r1 + l1-r3 in outsideHeadlineOrderedLayer!
        assertEquals(2, res.headline().distinctViolatingRules());
        assertEquals(2, res.headline().outsideHeadlineOrderedLayer());
        assertNotNull(res.headline().outsideHeadlineText());
    }

    @Test
    void zm17_checkPointApplyLayerParentAndInlineChildIntersection() {
        var fx = buildStandardMatrix(null);
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("u1", addr("u1", "USER", "192.0.2.0/26"));
        objects.put("a1", addr("a1", "APP", "198.51.100.0/25"));
        objects.put("d1", addr("d1", "DB", "203.0.113.0/25"));
        objects.put("https", svc("https", "HTTPS", "tcp/443"));
        objects.put("ssh", svc("ssh", "SSH", "tcp/22"));

        // Parent 1: compliant upper bound (Z-USER -> Z-APP on https) -> parent itself is COMPLIANT
        Rule p1 = rule("p1", 1, "CompliantParent", true,
                new Cell(List.of("u1"), false), new Cell(List.of("a1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "Apply Layer",
                Map.of("layer-name", List.of("Network"), "install-on", List.of("obj-any"), "inline-layer", List.of("Inline-1")));

        // Parent 2: Z-USER -> Z-APP on any service, with inline layer ending in universal Drop catch-all.
        // Child 2a: ANY -> Z-DB on ssh -> disjoint from parent's destination Z-APP! Intersection is empty -> COMPLIANT!
        // Child 2b: ANY -> ANY on ssh -> intersection with parent is Z-USER -> Z-APP on ssh -> VIOLATION!
        Rule p2 = rule("p2", 2, "ScopedParentWithCatchAll", true,
                new Cell(List.of("u1"), false), new Cell(List.of("a1"), false),
                ANY_CELL, ANY_CELL, "Apply Layer",
                Map.of("layer-name", List.of("Network"), "install-on", List.of("obj-any"), "inline-layer", List.of("Inline-2")));
        Map<String, List<String>> in2Ex = Map.of("layer-name", List.of("Inline-2"), "install-on", List.of("obj-any"));
        Rule c2Disjoint = rule("c2-a", 1, "ChildOutsideParentDest", true,
                ANY_CELL, new Cell(List.of("d1"), false), new Cell(List.of("ssh"), false), ANY_CELL, "Accept", in2Ex);
        Rule c2Violating = rule("c2-b", 2, "ChildViolatesWithinParent", true,
                ANY_CELL, ANY_CELL, new Cell(List.of("ssh"), false), ANY_CELL, "Accept", in2Ex);
        Rule c2Cleanup = rule("c2-clean", 3, "InlineCleanup", true,
                ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL, "Drop", in2Ex);

        // Parent 3: inline layer WITHOUT explicit universal catch-all -> NOT_EVALUABLE "inline layer cleanup not collected"
        Rule p3 = rule("p3", 3, "ParentWithoutInlineCatchAll", true,
                new Cell(List.of("u1"), false), new Cell(List.of("d1"), false),
                ANY_CELL, ANY_CELL, "Apply Layer",
                Map.of("layer-name", List.of("Network"), "install-on", List.of("obj-any"), "inline-layer", List.of("Inline-3")));
        Rule c3Only = rule("c3-a", 1, "PartialInlineRule", true,
                new Cell(List.of("u1"), false), new Cell(List.of("a1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "Accept",
                Map.of("layer-name", List.of("Inline-3"), "install-on", List.of("obj-any")));

        PolicySnapshot snap = new PolicySnapshot(meta("pol-cp-inline", "CP", "CP-Inline"),
                List.of(
                        new Section("s-main-1", "Network", "CP access layer", null, List.of(p1, p2)),
                        new Section("s-in2", "Inline-2", "CP access layer", "p2", List.of(c2Disjoint, c2Violating, c2Cleanup)),
                        new Section("s-main-2", "Network", "CP access layer", null, List.of(p3)),
                        new Section("s-in3", "Inline-3", "CP access layer", "p3", List.of(c3Only))),
                objects);

        EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
        Map<String, RuleEvaluation> byId = new HashMap<>();
        res.rules().forEach(r -> byId.put(r.ruleId(), r));

        assertEquals(OUTCOME_COMPLIANT, byId.get("p1").outcome());
        assertFalse(byId.containsKey("p2"), "p2 has universal inline catch-all so parent is NOT_APPLICABLE");
        assertEquals(1, res.notApplicable().applyLayerDecidedByInline());
        assertEquals(OUTCOME_COMPLIANT, byId.get("c2-a").outcome(), "child disjoint from parent destination cannot fire");
        assertEquals(OUTCOME_VIOLATION, byId.get("c2-b").outcome());
        assertEquals(List.of("p2"), byId.get("c2-b").inlineParentPath());

        assertEquals(OUTCOME_NOT_EVALUABLE, byId.get("p3").outcome());
        assertEquals("inline layer cleanup not collected", byId.get("p3").reason());
    }

    @Test
    void zm18_zm20_paloAltoDefaultOverridesAndLocalVsPanoramaCoverage() {
        var fx = buildStandardMatrix(null);
        Map<String, PolicyObject> objects = Map.of("obj-any", OBJ_ANY);

        // Local PAN firewall policy with non-allow intrazone-default override -> coverage COMPLETE!
        Rule localIntrazoneDeny = rule("def-intra", 1, "intrazone-default", true,
                ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL, "deny", Map.of("rule-type", List.of("intrazone")));
        Rule localInterzoneAllow = rule("def-inter", 2, "interzone-default", true,
                ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL, "allow", Map.of("rule-type", List.of("interzone")));
        Rule localOmittedAction = rule("def-omit", 3, "custom-default", true,
                ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL, "UNKNOWN", Map.of());

        PolicySnapshot localSnap = new PolicySnapshot(meta("pol-pan-local", "PAN", "Local-FW"),
                List.of(
                        new Section("s-loc", PAN_LOCAL_RULES, PAN_LOCAL_RULES, null, List.of()),
                        new Section("s-def", PAN_LOCAL_DEFAULT, PAN_LOCAL_DEFAULT, null,
                                List.of(localIntrazoneDeny, localInterzoneAllow, localOmittedAction))),
                objects);

        EvaluationResult localRes = ZoneMatrixEvaluator.evaluate(localSnap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
        assertEquals("COMPLETE", localRes.coverage());
        assertTrue(localRes.coverageItems().isEmpty());

        RuleEvaluation allowDefault = localRes.rules().stream().filter(r -> "def-inter".equals(r.ruleId())).findFirst().orElseThrow();
        assertEquals(OUTCOME_VIOLATION, allowDefault.outcome());
        assertTrue(allowDefault.flags().contains("DEFAULT_RULE"));

        RuleEvaluation unknownDefault = localRes.rules().stream().filter(r -> "def-omit".equals(r.ruleId())).findFirst().orElseThrow();
        assertEquals(OUTCOME_NOT_EVALUABLE, unknownDefault.outcome());
        assertEquals("action semantics not modelled", unknownDefault.reason());
        assertTrue(unknownDefault.flags().contains("DEFAULT_RULE"));

        // Panorama policy -> INCOMPLETE with PAN coverage codes (ZM-20)
        PolicySnapshot panoramaSnap = new PolicySnapshot(meta("pol-pan-dg", "PAN", "DG-Core"),
                List.of(panSection("pol-pan-dg", "pre-rulebase", "Pre rules", List.of())), objects);
        EvaluationResult panRes = ZoneMatrixEvaluator.evaluate(panoramaSnap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
        assertEquals("INCOMPLETE", panRes.coverage());
        Set<String> codes = new HashSet<>(panRes.coverageItems().stream().map(CoverageItem::code).toList());
        assertTrue(codes.contains("PAN_INTRAZONE_DEFAULT_ALLOW"));
        assertTrue(codes.contains("PAN_LOCAL_RULES_NOT_COLLECTED"));
        assertTrue(codes.contains("PAN_DEFAULT_RULE_LEVELS_NOT_COLLECTED"));
    }

    @Test
    void zm19_zm21_zm27_zm30_ipv6MixedObjectsAnyWildcardSharedRuleUidAndHeadline() {
        var fx = buildStandardMatrix(null);
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("v6-only", addr("v6-only", "V6-HOST", "ipv6-address: 2001:db8::10"));
        objects.put("mixed-v4-v6", addr("mixed-v4-v6", "DUAL-STACK", "ipv4-address: 192.0.2.10", "ipv6-address: 2001:db8::20"));
        objects.put("u1", addr("u1", "USER", "192.0.2.10/32"));
        objects.put("dmz1", addr("dmz1", "DMZ", "192.0.2.140/32"));
        objects.put("https", svc("https", "HTTPS", "tcp/443"));

        // 1) Pure IPv6 -> NOT_EVALUABLE "IPv6 not evaluated"
        Rule rV6 = rule("r-v6", 1, "PureIpv6", true,
                new Cell(List.of("v6-only"), false), new Cell(List.of("dmz1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", Map.of());
        // 2) Mixed IPv4/IPv6 object -> the IPv4 part is evaluated per family (step 7a, ZM-19); the IPv6 part stays unevaluated
        Rule rMixed = rule("r-mixed", 2, "MixedDualStack", true,
                new Cell(List.of("mixed-v4-v6"), false), new Cell(List.of("dmz1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", Map.of());
        // 3) Wildcard `any` to `any` -> touches multiple DENY_ALL cells in one rule, lists IPv6 as undecided part
        Rule rAnyViolates = new Rule("r-shared-1", "shared-uid-77", 3, "AnyToAnyHttps", true,
                ANY_CELL, ANY_CELL, new Cell(List.of("https"), false), ANY_CELL, "allow", "", "", Map.of());
        // 4) ZM-21: a second rule entry in another section sharing the SAME ruleUid within the same policy source
        // counts once in the headline!
        Rule rSharedScopeCopy = new Rule("r-shared-2", "shared-uid-77", 1, "AnyToAnyHttpsCopy", true,
                ANY_CELL, ANY_CELL, new Cell(List.of("https"), false), ANY_CELL, "allow", "", "", Map.of());

        PolicySnapshot snap = new PolicySnapshot(meta("pol-v6", "PAN", "PAN-IPv6"),
                List.of(panSection("pol-v6", "pre-rulebase", "Pre rules", List.of(rV6, rMixed, rAnyViolates)),
                        panSection("pol-v6", "post-rulebase", "Post rules", List.of(rSharedScopeCopy))),
                objects);

        EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
        assertEquals(OUTCOME_NOT_EVALUABLE, res.rules().get(0).outcome());
        assertEquals("IPv6 not evaluated", res.rules().get(0).reason());
        // Step 7a: the mixed rule gets the outcome of the same rule written with only its IPv4 address; its IPv4 part
        // (user zone -> DMZ, ALLOW_ANY) is compliant, so the unevaluated IPv6 part makes it NOT_EVALUABLE, never COMPLIANT.
        Rule rIpv4Only = rule("r-mixed", 2, "MixedDualStack", true,
                new Cell(List.of("u1"), false), new Cell(List.of("dmz1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", Map.of());
        PolicySnapshot snapIpv4Only = new PolicySnapshot(meta("pol-v6", "PAN", "PAN-IPv6"),
                List.of(panSection("pol-v6", "pre-rulebase", "Pre rules", List.of(rIpv4Only))), objects);
        RuleEvaluation ipv4OnlyEval = ZoneMatrixEvaluator
                .evaluate(snapIpv4Only, fx.versionId(), fx.versionNumber(), fx.content(), NOW).rules().get(0);
        RuleEvaluation mixedEval = res.rules().get(1);
        assertEquals(OUTCOME_COMPLIANT, ipv4OnlyEval.outcome());
        assertEquals(OUTCOME_NOT_EVALUABLE, mixedEval.outcome());
        assertEquals("IPv6 not evaluated", mixedEval.reason());
        assertEquals(ipv4OnlyEval.touchedPairs(), mixedEval.touchedPairs());

        RuleEvaluation anyEval = res.rules().get(2);
        assertEquals(OUTCOME_VIOLATION, anyEval.outcome());
        assertTrue(anyEval.undecidedParts().contains("IPv6 not evaluated"));
        assertTrue(anyEval.violatedCells().size() > 1, "any->any violates multiple cells");
        assertEquals(1, res.headline().distinctViolatingRules(), "ZM-21: shared ruleUid across scopes counts once in headline");
        assertEquals(anyEval.violatedCells().size(), res.headline().violatedZonePairs());
        assertFalse(res.headline().exact(), "not_evaluable > 0 means headline is non-exact");
        assertFalse(res.headline().text().toUpperCase(Locale.ROOT).contains("BDDK"), "ZM-30: headline never displays BDDK score/pass");

        // ZM-27: IPv4 compliant + IPv6 `any` gives NOT_EVALUABLE "IPv6 not evaluated"
        var store27 = new ZoneMatrixServiceTest.Memory();
        var svc27 = new ZoneMatrixService(store27, new Principals() {
            @Override public boolean holdsComplianceAdmin(String actor) { return actor.startsWith("compliance"); }
            @Override public boolean isRoot(String actor) { return false; }
        }, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
        String zA = (String) svc27.createZone("security-1", null, new ZoneInput("Z-A", "", "USER", List.of("192.0.2.0/24"))).get("zone_id");
        long rev27 = store27.draft().orElseThrow().revision();
        svc27.setCell("security-1", rev27, new CellInput(zA, zA, "ALLOW_ANY", List.of(), "LOW", "intra", ""));
        rev27 = store27.draft().orElseThrow().revision();
        svc27.setCell("security-1", rev27, new CellInput(zA, "UNASSIGNED", "ALLOW_ANY", List.of(), "LOW", "egress", ""));
        var draft27 = store27.draft().orElseThrow();
        svc27.publish("compliance-1", false, new PublishRequest(draft27.revision(), draft27.contentSha256(), null));
        ZoneMatrixContent content27 = ZoneMatrixContent.parse(store27.active().orElseThrow().content(), MAPPER);
        Rule rIpv4CompliantIpv6Any = rule("r27", 1, "UserToAnyHttps", true,
                new Cell(List.of("u1"), false), ANY_CELL, new Cell(List.of("https"), false), ANY_CELL, "allow", Map.of());
        PolicySnapshot snap27 = new PolicySnapshot(meta("pol-27", "PAN", "PAN-27"),
                List.of(panSection("pol-27", "pre-rulebase", "Pre rules", List.of(rIpv4CompliantIpv6Any))), objects);
        EvaluationResult res27 = ZoneMatrixEvaluator.evaluate(snap27, "v-27", 1, content27, NOW);
        assertEquals(OUTCOME_NOT_EVALUABLE, res27.rules().get(0).outcome());
        assertEquals("IPv6 not evaluated", res27.rules().get(0).reason());
    }

    @Test
    void zm7_zm26_headlineNeverShowsZeroWhenUndecidedOrIncompleteOrNotEvaluated() {
        var fx = buildStandardMatrix(null);
        // Local PAN with zero violations, COMPLETE coverage, 1 NOT_EVALUABLE IPv6 rule -> headline MUST NOT say "0 rules..."!
        Map<String, PolicyObject> objects = Map.of(
                "obj-any", OBJ_ANY,
                "v6", addr("v6", "V6", "2001:db8::1/128"),
                "https", svc("https", "HTTPS", "tcp/443"));
        Rule rV6 = rule("r1", 1, "Ipv6Rule", true,
                new Cell(List.of("v6"), false), new Cell(List.of("v6"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", Map.of());
        Rule intraDeny = rule("d1", 1, "intrazone-default", true, ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL, "deny",
                Map.of("rule-type", List.of("intrazone")));

        PolicySnapshot snap = new PolicySnapshot(meta("pol-zero", "PAN", "Local-ZeroViol"),
                List.of(new Section("s1", "Local rules", "Local rules", null, List.of(rV6)),
                        new Section("s2", "Local default rules", "Local default rules", null, List.of(intraDeny))),
                objects);

        EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
        assertEquals(0, res.headline().distinctViolatingRules());
        assertEquals("COMPLETE", res.coverage());
        assertFalse(res.headline().exact());
        assertEquals("no proven violations in evaluated rules", res.headline().text());
        assertEquals(1, res.headline().notEvaluableRules());

        // Exact zero case: 1 compliant IPv4 rule, COMPLETE coverage, 0 undecided, 0 not-evaluated
        Map<String, PolicyObject> cleanObjs = Map.of(
                "obj-any", OBJ_ANY,
                "u1", addr("u1", "USER", "192.0.2.10/32"),
                "a1", addr("a1", "APP", "198.51.100.10/32"),
                "https", svc("https", "HTTPS", "tcp/443"));
        Rule rClean = rule("rc", 1, "CleanWeb", true,
                new Cell(List.of("u1"), false), new Cell(List.of("a1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", Map.of());
        PolicySnapshot cleanSnap = new PolicySnapshot(meta("pol-clean", "PAN", "Local-Clean"),
                List.of(new Section("s1", "Local rules", "Local rules", null, List.of(rClean)),
                        new Section("s2", "Local default rules", "Local default rules", null, List.of(intraDeny))),
                cleanObjs);
        EvaluationResult cleanRes = ZoneMatrixEvaluator.evaluate(cleanSnap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
        assertTrue(cleanRes.headline().exact());
        assertEquals("0 rules violate the zone matrix (v" + fx.versionNumber() + ")", cleanRes.headline().text());
    }

    @Test
    void section3_3_unassignedPrivateRfc1918AndIpv6UlaGapsAndNonEvaluatedVendors() {
        var fx = buildStandardMatrix(null);
        Map<String, PolicyObject> objects = Map.of(
                "obj-any", OBJ_ANY,
                "priv-v4", addr("priv-v4", "SYNTHETIC-RFC1918-GAP", "10.250.0.0/24"),
                "priv-ula", addr("priv-ula", "SYNTHETIC-ULA-GAP", "fd00:dead:beef::/48"),
                "a1", addr("a1", "APP", "198.51.100.10/32"),
                "https", svc("https", "HTTPS", "tcp/443"));
        Rule rGap = rule("r-gap", 1, "PrivateUnassignedRule", true,
                new Cell(List.of("priv-v4"), false), new Cell(List.of("a1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", Map.of());
        Rule rUla = rule("r-ula", 2, "UlaUnassignedRule", true,
                new Cell(List.of("priv-ula"), false), new Cell(List.of("a1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", Map.of());

        PolicySnapshot snap = new PolicySnapshot(meta("pol-gap", "PAN", "PAN-Gaps"),
                List.of(panSection("pol-gap", "pre-rulebase", "Pre rules", List.of(rGap, rUla))), objects);
        EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
        assertEquals(2, res.zoneDefinitionGaps().size());
        assertEquals(List.of("10.250.0.0-10.250.0.255"), res.zoneDefinitionGaps().get(0).unassignedPrivateRanges());
        assertFalse(res.zoneDefinitionGaps().get(1).unassignedPrivateRanges().isEmpty());

        // Section 5.8: ASA and FortiGate snapshots are not evaluated and carry "not evaluated: policy not collected"
        for (String vendor : List.of("ASA", "FGT")) {
            PolicySnapshot nonEval = new PolicySnapshot(meta("pol-" + vendor, vendor, vendor + "-Policy"), List.of(), Map.of());
            EvaluationResult nr = ZoneMatrixEvaluator.evaluate(nonEval, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
            assertFalse(nr.evaluated());
            assertEquals("not evaluated: policy not collected", nr.notEvaluatedReason());
            assertEquals(1, nr.headline().notEvaluatedPolicies());
        }
    }

    @Test
    void identityApplicationsAskInformAndApplicationDefaultFollowStep3UpperBounds() {
        var fx = buildStandardMatrix(null);
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("u1", addr("u1", "USER", "192.0.2.10/32"));
        objects.put("a1", addr("a1", "APP", "198.51.100.10/32"));
        objects.put("d1", addr("d1", "DB", "203.0.113.10/32"));
        objects.put("https", svc("https", "HTTPS", "tcp/443"));
        String appDefRef = PolicySnapshot.ref("pol-appdef", "service", "application-default");
        objects.put(appDefRef, new PolicyObject(appDefRef, "application-default", "service", List.of(), List.of(), "RESOLVED"));
        objects.put("cp-appsite", new PolicyObject("cp-appsite", "CorpPortal", "application-site", List.of(), List.of(), "DYNAMIC"));
        objects.put("outside-proxy", addr("outside-proxy", "OUTSIDE-PROXY", "192.0.2.210/32")); // in Z-DMZ (Z-DMZ -> Z-APP is DENY_ALL)
        objects.put("role-user-net", new PolicyObject("role-user-net", "ROLE-USER", "access-role", List.of(), List.of("networks: u1"), "DYNAMIC"));

        // 1) PAN application-default in ALLOW_LISTED -> NOT_EVALUABLE "application ports not collected"
        Rule rAppDefListed = rule("r1", 1, "AppDefListed", true,
                new Cell(List.of("u1"), false), new Cell(List.of("a1"), false),
                new Cell(List.of(appDefRef), false), ANY_CELL, "allow", Map.of());
        // 2) PAN application-default in DENY_ALL -> VIOLATION (ZM-5)
        Rule rAppDefDeny = rule("r2", 2, "AppDefDeny", true,
                new Cell(List.of("u1"), false), new Cell(List.of("d1"), false),
                new Cell(List.of(appDefRef), false), ANY_CELL, "allow", Map.of());
        PolicySnapshot panSnap = new PolicySnapshot(meta("pol-appdef", "PAN", "PAN-AppDef"),
                List.of(panSection("pol-appdef", "pre-rulebase", "Pre rules", List.of(rAppDefListed, rAppDefDeny))), objects);
        EvaluationResult panRes = ZoneMatrixEvaluator.evaluate(panSnap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
        assertEquals(OUTCOME_NOT_EVALUABLE, panRes.rules().get(0).outcome());
        assertEquals("application ports not collected", panRes.rules().get(0).reason());
        assertEquals(OUTCOME_VIOLATION, panRes.rules().get(1).outcome());

        // 3) Check Point: Ask/Inform, application-site, ZM-15 access-role with outside proxy vs violating role networks
        Map<String, List<String>> cpEx = Map.of("layer-name", List.of("Network"));
        Rule cpAskCompliant = rule("cp-1", 1, "AskCompliant", true,
                new Cell(List.of("u1"), false), new Cell(List.of("a1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "Ask", cpEx);
        Rule cpAskNonCompliant = rule("cp-2", 2, "AskNonCompliant", true,
                new Cell(List.of("u1"), false), new Cell(List.of("d1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "Ask", cpEx);
        Rule cpAppSiteNonCompliant = rule("cp-3", 3, "AppSiteInService", true,
                new Cell(List.of("u1"), false), new Cell(List.of("a1"), false),
                new Cell(List.of("cp-appsite"), false), ANY_CELL, "Accept", cpEx);
        // ZM-15: role-user-net has networks=u1 (Z-USER), and Z-USER->Z-APP allows tcp/443,
        // yet an outside proxy (192.0.2.210 in Z-DMZ, where Z-DMZ->Z-APP is DENY_ALL) could match via XFF,
        // so reading source as `any` is NOT compliant -> NOT_EVALUABLE "depends on user identity" (never COMPLIANT!).
        Rule cpRoleToAllowedZoneNotCompliantBecauseProxy = rule("cp-4", 4, "RoleWithOutsideProxy", true,
                new Cell(List.of("role-user-net"), false), new Cell(List.of("a1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "Accept", cpEx);
        Rule cpRoleScopedViolates = rule("cp-5", 5, "RoleScopedViolates", true,
                new Cell(List.of("role-user-net"), false), new Cell(List.of("d1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "Accept", cpEx);

        PolicySnapshot cpSnap = new PolicySnapshot(meta("pol-cp-misc", "CP", "CP-Misc"),
                List.of(new Section("s1", "Network", "CP access layer", null,
                        List.of(cpAskCompliant, cpAskNonCompliant, cpAppSiteNonCompliant,
                                cpRoleToAllowedZoneNotCompliantBecauseProxy, cpRoleScopedViolates))), objects);
        EvaluationResult cpRes = ZoneMatrixEvaluator.evaluate(cpSnap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);

        assertEquals(OUTCOME_COMPLIANT, cpRes.rules().get(0).outcome());
        assertEquals(OUTCOME_NOT_EVALUABLE, cpRes.rules().get(1).outcome());
        assertEquals("action semantics not modelled", cpRes.rules().get(1).reason());
        assertEquals(OUTCOME_NOT_EVALUABLE, cpRes.rules().get(2).outcome());
        assertEquals("application constraint", cpRes.rules().get(2).reason());
        assertEquals(OUTCOME_NOT_EVALUABLE, cpRes.rules().get(3).outcome());
        assertEquals("depends on user identity", cpRes.rules().get(3).reason());
        assertEquals(OUTCOME_VIOLATION, cpRes.rules().get(4).outcome());
        assertTrue(cpRes.rules().get(4).flags().contains("IDENTITY_IGNORED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void zm10_zm33_serviceRbacWitnessAuditMaskingAndZeroSensitiveDataInLogs() throws Exception {
        DeviceRepository devRepo = new DeviceRepository() {
            @Override public Optional<com.securityexpert.nexus.ui2.persistence.device.DeviceRecord> find(String id) { return Optional.empty(); }
            @Override public Optional<com.securityexpert.nexus.ui2.persistence.device.EndpointRecord> findEndpoint(String id) { return Optional.empty(); }
            @Override public Optional<com.securityexpert.nexus.ui2.persistence.device.EndpointRecord> findEndpointByDeviceId(String id) { return Optional.empty(); }
            @Override public String registerDraft(com.securityexpert.nexus.ui2.persistence.device.DeviceDraft d, String a, String act) { return "x"; }
            @Override public boolean transitionEnrollmentState(String id, DeviceEnrollmentState f, DeviceEnrollmentState t, String a, String act) { return false; }
            @Override public boolean withdrawDraft(String id, String a, String act) { return false; }
            @Override public boolean setDisabled(String id, boolean d, String a, String act) { return false; }
            @Override public boolean recordConfirmSuccess(String id, com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts f, String a, String act) { return false; }
            @Override public Optional<com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts> findConfirmFacts(String id) { return Optional.empty(); }
            @Override public List<DeviceSummaryRecord> listAll() {
                return List.of(new DeviceSummaryRecord("asa-dev-1", "gateway", "cisco_asa", DeviceEnrollmentState.ENROLLED,
                        Optional.of("SYNTHETIC-ASA-EDGE"), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
            }
        };

        var fx = buildStandardMatrix(devRepo);
        // Plant distinctive synthetic markers in policy name, rule name, addresses and zone names to prove ZM-10!
        Map<String, PolicyObject> objects = Map.of(
                "obj-any", OBJ_ANY,
                "u1", addr("u1", "PLANTED-SRC-OBJ", "192.0.2.42/32"),
                "d1", addr("d1", "PLANTED-DST-OBJ", "203.0.113.99/32"),
                "gap1", addr("gap1", "PLANTED-GAP-OBJ", "10.250.77.0/24"),
                "https", svc("https", "HTTPS", "tcp/443"));
        Rule plantedRule = rule("r-planted", 7, "PLANTED-SECRET-RULE-NAME", true,
                new Cell(List.of("u1", "gap1"), false), new Cell(List.of("d1"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", Map.of());
        PolicySnapshot snap = new PolicySnapshot(
                new Metadata("pol-secret-id", "src-99", "PLANTED-SOURCE-HOST", "PAN",
                        "dom-99", "PLANTED-DOMAIN-NAME", "PLANTED-POLICY-NAME", "2026-11-15T08:00:00Z",
                        "art-99", List.of(new Target("gw-99", "PLANTED-TARGET-FW", "vsys1", "IN_SYNC"))),
                List.of(panSection("pol-secret-id", "pre-rulebase", "PLANTED-CUSTOM-SECTION", List.of(plantedRule))),
                objects);
        fx.snapshots().put("pol-secret-id", snap);

        // Attach SLF4J ListAppender to ZoneMatrixService logger to verify ZM-10
        Logger slf4jLogger = (Logger) LoggerFactory.getLogger(ZoneMatrixService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        slf4jLogger.addAppender(appender);
        try {
            // 1. Policies catalogue lists CP/PAN under policies and enrolled ASA under not_evaluated
            Map<String, Object> catalogueMasked = fx.service().policies(View.MASKED, false);
            assertFalse(MAPPER.writeValueAsString(catalogueMasked).contains("PLANTED-POLICY-NAME"));
            assertFalse(MAPPER.writeValueAsString(catalogueMasked).contains("SYNTHETIC-ASA-EDGE"));
            List<Map<String, Object>> notEval = (List<Map<String, Object>>) catalogueMasked.get("not_evaluated");
            assertEquals(1, notEval.size());
            assertEquals("ASA", notEval.get(0).get("vendor"));
            assertEquals("not evaluated: policy not collected", notEval.get(0).get("reason"));

            // 2. aiview (replayViewer) is refused from starting evaluation
            assertEquals("READ_ONLY_PERSONA", assertThrows(Refused.class, () ->
                    fx.service().evaluate("aiview", View.MASKED, true, false, false, "pol-secret-id", null, false)).code);

            // 3. Witness request without zone_matrix_zones_read is refused (ZM-33)
            assertEquals("ZONE_BLOCKS_NOT_PERMITTED", assertThrows(Refused.class, () ->
                    fx.service().evaluate("compliance-1", View.NAMES, false, false, true, "pol-secret-id", null, true)).code);
            assertTrue(fx.store().access.isEmpty());

            // 4. Draft version cannot be evaluated
            fx.service().createZone("security-1", null, new ZoneInput("Z-TEMP", "", null, List.of("198.18.1.0/24")));
            String draftId = fx.store().draftId();
            assertEquals("DRAFT_NOT_EVALUATED", assertThrows(Refused.class, () ->
                    fx.service().evaluate("security-1", View.NAMES, false, true, true, "pol-secret-id", draftId, false)).code);

            // 5. Evaluation without witnesses: no audit row, witness is null, and without policy_read rule names are masked!
            Map<String, Object> noPolicyRead = fx.service().evaluate(
                    "compliance-1", View.NAMES, false, false, false, "pol-secret-id", fx.versionId(), false);
            assertTrue(fx.store().access.isEmpty());
            String noPolicyReadJson = MAPPER.writeValueAsString(noPolicyRead);
            assertFalse(noPolicyReadJson.contains("PLANTED-SECRET-RULE-NAME"), "section 3.4: viewer without policy_read gets masked rule names");
            assertFalse(noPolicyReadJson.contains("192.0.2.42"), "witnesses omitted when include_witnesses=false");
            assertFalse(noPolicyReadJson.contains("10.250.77"), "unassigned private ranges omitted without witness/blocks permission");
            assertTrue(noPolicyReadJson.contains("Z-USER"), "zone names remain visible under zone_matrix_read");

            // 6. Evaluation WITH witnesses: writes WITNESS_VIEW audit row and includes witness range!
            Map<String, Object> withWitness = fx.service().evaluate(
                    "security-1", View.NAMES, false, true, true, "pol-secret-id", fx.versionId(), true);
            assertEquals(List.of("WITNESS_VIEW"), fx.store().access);
            String withWitnessJson = MAPPER.writeValueAsString(withWitness);
            assertTrue(withWitnessJson.contains("192.0.2.42"));
            assertTrue(withWitnessJson.contains("203.0.113.99"));
            assertTrue(withWitnessJson.contains("PLANTED-SECRET-RULE-NAME"));

            // 7. Fail-closed audit: if WITNESS_VIEW audit write fails, evaluate refuses and returns nothing!
            fx.store().failAccess = true;
            assertThrows(RuntimeException.class, () ->
                    fx.service().evaluate("security-1", View.NAMES, false, true, true, "pol-secret-id", fx.versionId(), true));
            fx.store().failAccess = false;

            // 8. ZM-10: Masked aiview projection of an EvaluationResult withholds every raw zone name, block, witness and rule name!
            EvaluationResult rawEval = ZoneMatrixEvaluator.evaluate(snap, fx.versionId(), fx.versionNumber(), fx.content(), NOW);
            Map<String, Object> maskedProj = fx.service().projectEvaluation(rawEval, fx.content(), View.MASKED, true, true);
            String maskedJson = MAPPER.writeValueAsString(maskedProj);
            for (String forbidden : List.of("Z-USER", "Z-APP", "Z-DB", "Z-DMZ",
                    "PLANTED-SECRET-RULE-NAME", "PLANTED-POLICY-NAME", "PLANTED-SOURCE-HOST",
                    "PLANTED-DOMAIN-NAME", "PLANTED-TARGET-FW", "PLANTED-CUSTOM-SECTION",
                    "192.0.2.42", "203.0.113.99", "10.250.77")) {
                assertFalse(maskedJson.contains(forbidden), "ZM-10 leak in masked projection: " + forbidden);
            }
            assertTrue(maskedJson.contains("ZONE-"), "masked projection uses ZONE-<n> pseudonyms");

            // 9. ZM-10: Verify SLF4J log events contain ZERO zone names, blocks, addresses or rule/policy names!
            assertFalse(appender.list.isEmpty(), "evaluation logs safe operational counts");
            for (ILoggingEvent event : appender.list) {
                String msg = event.getFormattedMessage();
                for (String forbidden : List.of("Z-USER", "Z-APP", "Z-DB", "Z-DMZ",
                        "PLANTED-SECRET-RULE-NAME", "PLANTED-POLICY-NAME", "PLANTED-SOURCE-HOST",
                        "192.0.2", "203.0.113", "198.51.100", "10.250.77")) {
                    assertFalse(msg.contains(forbidden), "ZM-10 log leak: " + forbidden + " in: " + msg);
                }
            }
        } finally {
            slf4jLogger.detachAppender(appender);
        }
    }
}
