package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyDomainIndexTest.*;
import com.securityexpert.nexus.ui2.policy.*;
import com.securityexpert.nexus.ui2.service.privacy.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Where-used counts: every stored reference path, and UNKNOWN instead of an unproven zero. */
class PolicyObjectUsageTest {
    private static final Cell EMPTY = new Cell(List.of(), false);

    private static Metadata pan() {
        return new Metadata("policy-pan", "source-1", "Synthetic Panorama", "PAN", "domain-1", "DG-TANGO-04", "DG-TANGO-04",
            "2026-10-15T00:00:00Z", "artifact-1", List.of());
    }
    private static Rule rule(String id, List<String> source, List<String> destination, List<String> service, List<String> application) {
        return new Rule(id, id + "-uuid", 1, "RULE-" + id, true, new Cell(source, false), new Cell(destination, false),
            new Cell(service, false), new Cell(application, false), "allow", "Log", "", Map.of());
    }
    private static PolicyObject address(String id) {
        return new PolicyObject(id, "ADDR-" + id, "address", List.of(), List.of("ip-netmask: 192.0.2.10"), "RESOLVED");
    }
    private static PolicyObject group(String id, String... members) {
        return new PolicyObject(id, "GRP-" + id, "address-group", List.of(members), List.of(), "RESOLVED");
    }
    private static PolicyDomainIndex panIndex(List<Rule> rules, PolicyObject... objects) {
        var map = new LinkedHashMap<String, PolicyObject>();
        for (var object : objects) map.put(object.id(), object);
        return new PolicyDomainIndex(List.of(), List.of(new PolicySnapshot(pan(),
            List.of(new Section("pre", "Pre rules", "DG-TANGO-04", null, rules)), map)));
    }
    private static Map<String, Object> row(PolicyDomainIndex index, String uid) {
        return index.objects.stream().filter(o -> o.get("uid").equals(uid)).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked")
    private static List<String> reasons(Map<String, Object> row) { return (List<String>) row.get("usageReasons"); }

    @Test void directSourceDestinationServiceAndApplicationReferencesAreCountedOncePerRule() {
        var svc = new PolicyObject("svc", "SVC-TCP-8443", "service", List.of(), List.of("protocol/tcp/port: 8443"), "RESOLVED");
        var app = new PolicyObject("app", "APPGRP-01", "application-group", List.of(), List.of(), "RESOLVED");
        var index = panIndex(List.of(rule("r1", List.of("a1"), List.of("a1"), List.of("svc"), List.of("app")),
            rule("r2", List.of(), List.of("a1"), List.of(), List.of())), address("a1"), svc, app);
        assertEquals(2, row(index, "a1").get("ruleCount"));
        assertEquals(1, row(index, "svc").get("ruleCount"));
        assertEquals(1, row(index, "app").get("ruleCount"));
        assertEquals(1, index.rulePasses);
    }
    @Test void referencesThroughGroupsAndNestedGroupsReachTheMember() {
        var index = panIndex(List.of(rule("r1", List.of("outer"), List.of(), List.of(), List.of())),
            address("a1"), group("inner", "a1"), group("outer", "inner"));
        assertEquals(1, row(index, "a1").get("ruleCount"));
        assertEquals(1, row(index, "a1").get("groupCount"));
        assertEquals(1, row(index, "inner").get("ruleCount"));
        assertEquals(1, row(index, "inner").get("groupCount"));
        assertEquals(1, row(index, "outer").get("ruleCount"));
        assertEquals(0, row(index, "outer").get("groupCount"));
    }
    @Test void sharedAndAncestorDeviceGroupObjectsAndRulesCountForTheChildDeviceGroup() {
        String xml = "<config><shared><address><entry name='ADDR-SHARED-01'><ip-netmask>192.0.2.1/32</ip-netmask></entry>"
            + "<entry name='ADDR-SHARED-02'><ip-netmask>192.0.2.2/32</ip-netmask></entry></address></shared>"
            + "<devices><entry><device-group>"
            + "<entry name='DG-PARENT-01'><address-group><entry name='GRP-PARENT-01'><static><member>ADDR-SHARED-01</member></static></entry></address-group>"
            + "<pre-rulebase><security><rules><entry name='RULE-PARENT-01' uuid='uuid-p1'><source><member>GRP-PARENT-01</member></source>"
            + "<destination><member>any</member></destination><service><member>application-default</member></service>"
            + "<application><member>any</member></application><action>allow</action></entry></rules></security></pre-rulebase></entry>"
            + "<entry name='DG-TANGO-04'><post-rulebase><security><rules><entry name='RULE-CHILD-01' uuid='uuid-c1'>"
            + "<source><member>any</member></source><destination><member>ADDR-SHARED-02</member></destination>"
            + "<service><member>application-default</member></service><application><member>any</member></application>"
            + "<action>deny</action></entry></rules></security></post-rulebase></entry>"
            + "</device-group></entry></devices></config>";
        var snapshot = new PanoramaPolicyMapper().mapUnverifiedPrecedence(pan(), PolicyXml.parse(xml).getDocumentElement(),
            "DG-TANGO-04", Map.of("DG-TANGO-04", "DG-PARENT-01"));
        var index = new PolicyDomainIndex(List.of(), List.of(snapshot));
        var byName = new HashMap<String, Map<String, Object>>();
        index.objects.forEach(o -> byName.put((String) o.get("name"), o));
        assertEquals(1, byName.get("ADDR-SHARED-01").get("ruleCount"), "shared object via ancestor group in ancestor pre-rule");
        assertEquals(1, byName.get("ADDR-SHARED-01").get("groupCount"));
        assertEquals(1, byName.get("GRP-PARENT-01").get("ruleCount"));
        assertEquals(1, byName.get("ADDR-SHARED-02").get("ruleCount"), "shared object in child post-rule");
        // PAN never proves a count complete: other rulebases and other device groups are not collected.
        assertEquals(false, byName.get("ADDR-SHARED-02").get("usageComplete"));
        assertTrue(reasons(byName.get("ADDR-SHARED-02")).contains(PolicyDomainIndex.PAN_RULEBASES));
        assertTrue(reasons(byName.get("ADDR-SHARED-02")).contains(PolicyDomainIndex.PAN_OTHER_GROUPS));
        assertNull(byName.get("ADDR-SHARED-02").get("unused"));
    }
    @Test void unresolvedOrPrecedenceUnknownReferencesMakeZeroCountsUnknown() {
        var unresolved = new PolicyObject("missing", "ADDR-MISSING", "unresolved", List.of(), List.of(), "UNRESOLVED");
        var index = panIndex(List.of(rule("r1", List.of("missing"), List.of(), List.of(), List.of())), address("a1"), unresolved);
        assertEquals(0, row(index, "a1").get("ruleCount"));
        assertEquals(false, row(index, "a1").get("usageComplete"));
        assertTrue(reasons(row(index, "a1")).contains(PolicyDomainIndex.UNRESOLVED));
        var collision = new PolicyObject("grp", "GRP-COLLIDING", "address-group", List.of(), List.of(), "UNKNOWN");
        var hidden = panIndex(List.of(rule("r1", List.of("grp"), List.of(), List.of(), List.of())), address("a1"), collision);
        assertTrue(hidden.usageGaps.contains(PolicyDomainIndex.UNRESOLVED));
        var dynamic = new PolicyObject("dag", "DAG-01", "address-group", List.of(), List.of("dynamic/filter: 'web'"), "DYNAMIC");
        assertTrue(panIndex(List.of(rule("r1", List.of("dag"), List.of(), List.of(), List.of())), address("a1"), dynamic)
            .usageGaps.contains(PolicyDomainIndex.DYNAMIC));
        var tag = new PolicyObject("tag", "TAG-01", "tag", List.of(), List.of(), "RESOLVED");
        assertTrue(reasons(row(panIndex(List.of(), tag), "tag")).contains(PolicyDomainIndex.TAG_REFERENCES));
    }
    @Test void checkPointRuleReferenceToAnUncollectedObjectIsUnresolved() {
        var host = item("host-uid", "HOST-TANGO-04", "host", List.of(), "ipv4-address: 192.0.2.4");
        var snapshot = snapshot(List.of()); // references group-uid, which no inventory holds
        var index = new PolicyDomainIndex(List.of(inventory("hosts", "RESOLVED", host)), List.of(snapshot));
        assertTrue(index.usageGaps.contains(PolicyDomainIndex.UNRESOLVED));
        assertTrue(index.usageGaps.contains(PolicyDomainIndex.CP_OUTSIDE));
        assertEquals(false, row(index, "host-uid").get("usageComplete"));
        assertTrue(reasons(row(index, "host-uid")).contains(PolicyDomainIndex.UNUSED_NOT_COLLECTED));
    }
    @Test void zeroIsProvenOnlyWhenTheManagementUnusedReportAgreesAndContradictionsStayUnknown() {
        var unusedHost = item("u1", "HOST-UNUSED", "host", List.of(), "ipv4-address: 192.0.2.5");
        var usedElsewhere = item("u2", "HOST-VPN", "host", List.of(), "ipv4-address: 192.0.2.6");
        var contradicted = item("group-uid", "GRP-REFERENCED", "group", List.of());
        var index = new PolicyDomainIndex(List.of(inventory("hosts", "RESOLVED", unusedHost, usedElsewhere),
            inventory("groups", "RESOLVED", contradicted), inventory("unused-objects", "RESOLVED", unusedHost, contradicted)),
            List.of(snapshot(List.of())));
        var proven = row(index, "u1");
        assertEquals(true, proven.get("unused")); assertEquals(true, proven.get("usageComplete"));
        assertEquals(List.of(), proven.get("usageReasons")); assertEquals(0, proven.get("ruleCount"));
        var outside = row(index, "u2");
        assertEquals(false, outside.get("unused")); assertEquals(false, outside.get("usageComplete"));
        assertTrue(reasons(outside).contains(PolicyDomainIndex.MANAGEMENT_IN_USE));
        var conflict = row(index, "group-uid");
        assertEquals(1, conflict.get("ruleCount"));
        assertNull(conflict.get("unused"), "a stored reference contradicts the unused report: hygiene must not say Unused");
        assertEquals(List.of(PolicyDomainIndex.CONTRADICTION), conflict.get("unusedReasons"));
        assertEquals(false, conflict.get("usageComplete"));
        // Without the report nothing is proven, even with zero references and complete rule collection.
        var unknown = new PolicyDomainIndex(List.of(inventory("hosts", "RESOLVED", unusedHost)), List.of());
        assertEquals(false, row(unknown, "u1").get("usageComplete"));
        assertNull(row(unknown, "u1").get("unused"));
        assertTrue(reasons(row(unknown, "u1")).containsAll(List.of(PolicyDomainIndex.UNUSED_NOT_COLLECTED, PolicyDomainIndex.NO_RULES)));
    }
    @Test void failedRuleCollectionIsAReason() {
        var meta = pan();
        var failed = new PolicySnapshot(meta, List.of(), Map.of("a1", address("a1")), List.of(new CollectionFailure("layer-x", "COLLECTION_FAILED")));
        var index = new PolicyDomainIndex(List.of(), List.of(failed));
        assertTrue(reasons(row(index, "a1")).contains(PolicyDomainIndex.RULES_INCOMPLETE));
    }
    @Test void aiviewProjectionKeepsOnlyTheFixedReasonVocabulary() {
        var names = new TopologyNamePseudonymizer(new byte[32]); var ips = new SubnetPreservingIpMasker(new byte[32]);
        var masked = (Map<?, ?>) PolicyPrivacy.mask(Map.of("usageReasons", List.of(PolicyDomainIndex.PAN_RULEBASES, "FW-TANGO-04 leaked"),
            "usageComplete", false, "usageGaps", List.of(PolicyDomainIndex.UNRESOLVED)), "", names, ips);
        assertEquals(List.of(PolicyDomainIndex.PAN_RULEBASES, "UNKNOWN"), masked.get("usageReasons"));
        assertEquals(List.of(PolicyDomainIndex.UNRESOLVED), masked.get("usageGaps"));
        assertEquals(false, masked.get("usageComplete"));
    }
}
