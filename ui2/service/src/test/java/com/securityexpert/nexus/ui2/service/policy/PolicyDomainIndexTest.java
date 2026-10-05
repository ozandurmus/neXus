package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.policy.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PolicyDomainIndexTest {
    static CpObjectInventory.Item item(String uid, String name, String type, List<String> members, String... values) {
        return new CpObjectInventory.Item(ref("source-1", "domain-1", "object", uid), uid, name, type, members, List.of(values), null, List.of());
    }
    static CpObjectInventory inventory(String type, String status, CpObjectInventory.Item... items) {
        return new CpObjectInventory("source-1", "domain-1", type, "2026-10-05T00:00:00Z", status, "", List.of(items), 1, 1);
    }
    static PolicySnapshot snapshot(List<Target> targets) {
        var meta = new Metadata("policy-1", "source-1", "Synthetic manager", "CP", "domain-1", "Synthetic domain", "Synthetic package",
            "2026-10-05T00:00:00Z", "artifact-1", targets);
        var empty = new Cell(List.of(), false);
        var rule = new Rule("rule-1", "rule-uid", 7, "Synthetic rule", true,
            new Cell(List.of(ref(meta.id(), "object", "group-uid")), false), empty, empty, empty, "Accept", "Log", "", Map.of());
        return new PolicySnapshot(meta, List.of(new Section("layer-1", "Synthetic layer", "CP access layer", null, List.of(rule))), Map.of());
    }
    @Test void snapshotObjectsAppearInTheObjectTabAndNestedGroupsHaveUniqueUsage() {
        var meta = snapshot(List.of()).metadata();
        var host = new PolicyObject("host-id", "Synthetic address", "address", List.of(), List.of("ip-netmask: 192.0.2.8"), "RESOLVED");
        var group = new PolicyObject("group-id", "Synthetic group", "address-group", List.of("host-id"), List.of(), "RESOLVED");
        var empty = new Cell(List.of(), false);
        var rule = new Rule("rule-id", "rule-uid", 1, "Synthetic rule", true, new Cell(List.of("group-id", "host-id"), false), empty, empty, empty, "Allow", "Log", "", Map.of());
        var index = new PolicyDomainIndex(List.of(), List.of(new PolicySnapshot(meta,
            List.of(new Section("layer-id", "Synthetic layer", "Pre rules", null, List.of(rule))), Map.of(host.id(), host, group.id(), group))));
        assertEquals(2, index.objects.size());
        assertEquals(1, index.rules.get("host-id").size());
        assertEquals(1, index.rules.get("group-id").size());
        assertEquals(java.util.Set.of("host-id", "group-id", "outer-id"), PolicyQueryService.containingGroups(
            java.util.Set.of("host-id"), Map.of("host-id", java.util.Set.of("group-id"),
                "group-id", java.util.Set.of("outer-id"), "outer-id", java.util.Set.of("group-id"))));
    }
    @Test void usageJoinsOpaquePackageRefsAndTraversesCyclesWithoutDuplicatingRules() {
        var host = item("0001", "Synthetic host", "host", List.of(), "ipv4-address: 192.0.2.8");
        var group = item("group-uid", "Synthetic group", "group", List.of("0001", "cycle-uid", "0001"));
        var cycle = item("cycle-uid", "Synthetic cycle", "group", List.of("group-uid"));
        var exclusion = item("exclude-uid", "Synthetic exclusion", "group-with-exclusion", List.of(), "include: group-uid", "except: 0001");
        var index = new PolicyDomainIndex(List.of(inventory("hosts", "RESOLVED", host), inventory("groups", "RESOLVED", group, cycle, exclusion)), List.of(snapshot(List.of())));
        assertEquals(1, index.rules.get("0001").size());
        assertEquals(7, index.rules.get("0001").get(0).get("number"));
        assertEquals("policy-1", index.rules.get("0001").get(0).get("policyId"));
        assertEquals(2, index.groups.get("0001").size());
        assertFalse(index.rules.containsKey("1"));
    }
    @Test void usageShowsTheStoredAccessLayerRatherThanItsSectionHeading() {
        var snapshot = snapshot(List.of()); var original = snapshot.sections().get(0).rules().get(0);
        var rule = new Rule(original.id(), original.uuid(), original.number(), original.name(), original.enabled(),
            original.source(), original.destination(), original.service(), original.application(), original.action(), original.log(), original.comment(),
            Map.of("layer-name", List.of("Synthetic access layer")));
        var group = item("group-uid", "Synthetic group", "group", List.of());
        var index = new PolicyDomainIndex(List.of(inventory("groups", "RESOLVED", group)), List.of(new PolicySnapshot(snapshot.metadata(),
            List.of(new Section("section-1", "Synthetic section heading", "CP access layer", null, List.of(rule))), Map.of())));
        assertEquals("Synthetic access layer", index.rules.get("group-uid").get(0).get("layerName"));
    }
    @Test void translatedNatObjectsAreUsageReferencesToo() {
        var natObject = item("nat-uid", "Synthetic NAT object", "host", List.of(), "ipv4-address: 192.0.2.9");
        var snapshot = snapshot(List.of()); var original = snapshot.sections().get(0).rules().get(0);
        var translated = new Rule(original.id(), original.uuid(), original.number(), original.name(), original.enabled(),
            original.source(), original.destination(), original.service(), original.application(), original.action(), original.log(), original.comment(),
            Map.of("translated-source", List.of(ref(snapshot.metadata().id(), "object", "nat-uid"))));
        var indexed = new PolicyDomainIndex(List.of(inventory("hosts", "RESOLVED", natObject)), List.of(new PolicySnapshot(snapshot.metadata(),
            List.of(new Section("nat-layer", "Synthetic NAT layer", "CP NAT rulebase", null, List.of(translated))), Map.of())));
        assertEquals(1, indexed.rules.get("nat-uid").size());
    }
    @Test void duplicateValuesIgnoreNamesAndMemberOrderButNotOpaqueIdentityOrIncompleteValues() {
        var host = item("h1", "Synthetic host A", "host", List.of(), "ipv4-address: 192.0.2.8");
        var host2 = item("h2", "Synthetic host B", "host", List.of(), "ipv4-address: 192.0.2.8");
        var net = item("n1", "Synthetic network A", "network", List.of(), "subnet4: 192.0.2.7", "subnet-mask: 255.255.255.0");
        var net2 = item("n2", "Synthetic network B", "network", List.of(), "subnet4: 192.0.2.0", "mask-length4: 24");
        var group = item("g1", "Synthetic group A", "group", List.of("0001", "uid-2"));
        var group2 = item("g2", "Synthetic group B", "group", List.of("uid-2", "0001", "0001"));
        var group3 = item("g3", "Synthetic group C", "group", List.of("1", "uid-2"));
        var tcp = item("s1", "Synthetic service A", "service-tcp", List.of(), "port: 443, 80-81");
        var tcp2 = item("s2", "Synthetic service B", "service-tcp", List.of(), "port: 81,80,443");
        var udp = item("s3", "Synthetic service C", "service-udp", List.of(), "port: 81,80,443");
        var range = item("r1", "Synthetic range A", "address-range", List.of(), "ipv4-address-first: 192.0.2.1", "ipv4-address-last: 192.0.2.9");
        var range2 = item("r2", "Synthetic range B", "address-range", List.of(), "ip-address-first: 192.0.2.1", "ip-address-last: 192.0.2.9");
        var index = new PolicyDomainIndex(List.of(inventory("hosts", "RESOLVED", host, host2, net, net2, group, group2, group3, tcp, tcp2, udp, range, range2)), List.of());
        assertEquals(5, index.duplicates.size());
        assertNotEquals(PolicyDomainIndex.normalized(group), PolicyDomainIndex.normalized(group3));
        assertNotEquals(PolicyDomainIndex.normalized(tcp), PolicyDomainIndex.normalized(udp));
        assertNull(PolicyDomainIndex.normalized(item("bad", "Synthetic incomplete", "network", List.of(), "subnet4: 192.0.2.0")));
        assertNull(PolicyDomainIndex.normalized(item("bad", "Synthetic incomplete", "host", List.of())));
        assertNull(PolicyDomainIndex.normalized(item("bad", "Synthetic invalid", "network", List.of(), "subnet4: 192.0.2.0", "subnet-mask: 255.0.255.0")));
        assertNull(PolicyDomainIndex.normalized(item("bad", "Synthetic expression", "service-tcp", List.of(), "port: >1024")));
        assertNull(PolicyDomainIndex.normalized(item("bad", "Synthetic exclusion", "group-with-exclusion", List.of())));
    }
    @Test void hygieneKeepsMissingUnusedEvidenceUnknownAndFlagsOnlyOrdinaryGroups() {
        var empty = item("e1", "Synthetic empty", "group", List.of());
        var single = item("s1", "Synthetic single", "service-group", List.of("member-1"));
        var exclusion = item("x1", "Synthetic exclusion", "group-with-exclusion", List.of());
        var index = new PolicyDomainIndex(List.of(inventory("groups", "RESOLVED", empty, single, exclusion), inventory("unused-objects", "COLLECTION_FAILED")), List.of());
        assertNull(index.objects.get(0).get("unused"));
        assertEquals(true, index.objects.get(0).get("emptyGroup"));
        assertEquals(true, index.objects.get(1).get("singleMember"));
        assertEquals(false, index.objects.get(2).get("emptyGroup"));
        var known = new PolicyDomainIndex(List.of(inventory("groups", "RESOLVED", empty, single), inventory("unused-objects", "RESOLVED", empty)), List.of());
        assertEquals(true, known.objects.get(0).get("unused"));
        assertEquals(false, known.objects.get(1).get("unused"));
    }
    @Test void hitFiltersUseKnownEvidenceAndExactDayBoundary() {
        var now = Instant.parse("2026-10-05T00:00:00Z");
        var rule = snapshot(List.of()).sections().get(0).rules().get(0);
        assertFalse(PolicyQueryService.hitMatches(rule, "never", 90, now));
        assertTrue(PolicyQueryService.hitMatches(rule.withHitCounts(new HitCounts(0L, null, null, "mds", now.toString(), "zero", List.of())), "never", 90, now));
        var boundary = rule.withHitCounts(new HitCounts(7L, "2026-01-01T00:00:00Z", now.minusSeconds(30 * 86400).toString(), "mds", now.toString(), "low", List.of()));
        assertTrue(PolicyQueryService.hitMatches(boundary, "inactive", 30, now));
        assertFalse(PolicyQueryService.hitMatches(boundary, "never", 30, now));
        assertFalse(PolicyQueryService.hitMatches(boundary, "inactive", 31, now));
        assertTrue(PolicyQueryService.hitMatches(rule.withHitCounts(new HitCounts(null, null, now.minusSeconds(30 * 86400).toString(), "mds", now.toString(), null, List.of())), "inactive", 30, now));
        assertFalse(PolicyQueryService.hitMatches(rule.withHitCounts(new HitCounts(null, null, null, "mds", now.toString(), null, List.of())), "never", 30, now));
        assertFalse(PolicyQueryService.hitMatches(rule.withHitCounts(new HitCounts(0L, null, "invalid", "mds", now.toString(), null, List.of())), "inactive", 30, now));
    }
}
