package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.policy.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyMapper;

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
        assertFalse(PolicyQueryService.hitMatches(rule, "never", 90));
        assertTrue(PolicyQueryService.hitMatches(rule.withHitCounts(new HitCounts(0L, null, null, "mds", now.toString(), "zero", List.of())), "never", 90));
        var boundary = rule.withHitCounts(new HitCounts(7L, "2026-01-01T00:00:00Z", now.minusSeconds(30 * 86400).toString(), "mds", now.toString(), "low", List.of()));
        assertTrue(PolicyQueryService.hitMatches(boundary, "inactive", 30));
        assertFalse(PolicyQueryService.hitMatches(boundary, "never", 30));
        assertFalse(PolicyQueryService.hitMatches(boundary, "inactive", 31));
        assertTrue(PolicyQueryService.hitMatches(rule.withHitCounts(new HitCounts(null, null, now.minusSeconds(30 * 86400).toString(), "mds", now.toString(), null, List.of())), "inactive", 30));
        assertFalse(PolicyQueryService.hitMatches(rule.withHitCounts(new HitCounts(null, null, null, "mds", now.toString(), null, List.of())), "never", 30));
        assertFalse(PolicyQueryService.hitMatches(rule.withHitCounts(new HitCounts(0L, null, "invalid", "mds", now.toString(), null, List.of())), "inactive", 30));
    }
    @Test void unusedWindowIsAnchoredAtCounterCollectionLikeTheHygieneFinding() {
        var now = Instant.parse("2026-10-30T00:00:00Z");
        var rule = snapshot(List.of()).sections().get(0).rules().get(0);
        // Collected 29 days ago; last hit 65 days before that collection: 94 days on the wall clock, 65 at collection time.
        Instant collected = now.minusSeconds(29L * 86400), last = collected.minusSeconds(65L * 86400);
        var stale = rule.withHitCounts(new HitCounts(7L, "2026-01-01T00:00:00Z", last.toString(), "mds", collected.toString(), "low", List.of()));
        assertFalse(PolicyQueryService.hitMatches(stale, "inactive", 90), "65 days before collection is not 90 days unused");
        assertTrue(PolicyQueryService.hitMatches(stale, "inactive", 65));
        assertFalse(PolicyQueryService.hitMatches(stale, "inactive", 66));
        // A hit the hour before collection is in use whatever the snapshot's age.
        var fresh = rule.withHitCounts(new HitCounts(7L, null, collected.minusSeconds(3600).toString(), "mds", collected.toString(), "low", List.of()));
        assertFalse(PolicyQueryService.hitMatches(fresh, "inactive", 1));
        // No readable collection time: the window has no anchor and the preset never matches.
        var unanchored = rule.withHitCounts(new HitCounts(7L, null, last.toString(), "mds", "not-a-time", "low", List.of()));
        assertFalse(PolicyQueryService.hitMatches(unanchored, "inactive", 90));
    }

    // ---- ASA / FortiGate snapshot objects (PO record 2026-11-14 decisions 1 and 5; design 5.2, 5.3, step 4) ----
    static PolicyObject snapObject(String type, String status, List<String> members, String... values) {
        return new PolicyObject("id-" + type + status + members + List.of(values), "Synthetic " + type, type, members, List.of(values), status);
    }
    static CpObjectInventory.Item asItem(PolicyObject o) {
        return new CpObjectInventory.Item(o.id(), o.id(), o.name(), o.type(), o.members(), o.values(), o.schedule(), List.of());
    }
    static Set<Set<String>> duplicateNames(PolicyDomainIndex index) {
        var out = new HashSet<Set<String>>();
        for (var group : index.duplicates) {
            var names = new TreeSet<String>();
            for (var o : (List<?>) group.get("objects")) names.add((String) ((Map<?, ?>) o).get("name"));
            out.add(names);
        }
        return out;
    }
    static Map<String, Map<String, Object>> byName(PolicyDomainIndex index) {
        var out = new HashMap<String, Map<String, Object>>();
        for (var row : index.objects) out.put((String) row.get("name"), row);
        return out;
    }
    static final String ASA_BASE = """
            ASA Version 9.18(4)22
            interface GigabitEthernet0/0
             nameif outside
             security-level 0
            interface GigabitEthernet0/1
             nameif inside
             security-level 100
            """;
    static final String FGT_HEAD = """
            #config-version=FGVM64-7.4.8-FW-build2795-250521:opmode=0:vdom=0:user=admin
            config system interface
                edit "port1"
                    set vdom "root"
                    set ip 192.0.2.1 255.255.255.0
                next
                edit "port2"
                    set vdom "root"
                    set ip 198.51.100.1 255.255.255.0
                next
            end
            """;

    @Test void asaSnapshotDuplicatesGroupByValueAndCarryTheSameDuplicateId() {
        var config = AsaConfigPolicyParser.parse(ASA_BASE + """
                object network HOST-A
                 host 192.0.2.10
                object network HOST-B
                 host 192.0.2.10
                object network NET-A
                 subnet 192.0.2.0 255.255.255.0
                object network NET-B
                 subnet 192.0.2.0 255.255.255.0
                object network RNG-A
                 range 192.0.2.20 192.0.2.30
                object network RNG-B
                 range 192.0.2.20 192.0.2.30
                object network HOST-C
                 host 192.0.2.11
                object-group network GRP-A
                 network-object object HOST-A
                 network-object object NET-A
                object-group network GRP-B
                 network-object object NET-A
                 network-object object HOST-A
                object service SVC-TCP-A
                 service tcp destination range 80 81
                object service SVC-TCP-B
                 service tcp destination range 80 81
                object service SVC-UDP-A
                 service udp destination eq 53
                object service SVC-UDP-B
                 service udp destination eq 53
                access-list ACL-OUT extended permit object SVC-TCP-A object HOST-A object HOST-B
                access-list ACL-OUT extended permit object SVC-TCP-B object NET-A object NET-B
                access-list ACL-OUT extended permit object SVC-UDP-A object RNG-A object RNG-B
                access-list ACL-OUT extended permit object SVC-UDP-B object-group GRP-A object-group GRP-B
                access-list ACL-OUT extended permit ip object HOST-C object HOST-C
                access-group ACL-OUT in interface outside
                """);
        var snapshot = AsaPolicyMapper.map("dev-asa-1", "FW-TANGO-04", "2026-11-14T02:00:00Z", "artefact", config);
        assertTrue(snapshot.failures().isEmpty(), String.valueOf(snapshot.failures()));
        var index = new PolicyDomainIndex(List.of(), List.of(snapshot));
        assertEquals(Set.of(Set.of("HOST-A", "HOST-B"), Set.of("NET-A", "NET-B"), Set.of("RNG-A", "RNG-B"), Set.of("GRP-A", "GRP-B"),
            Set.of("SVC-TCP-A", "SVC-TCP-B"), Set.of("SVC-UDP-A", "SVC-UDP-B")), duplicateNames(index));
        var rows = byName(index);
        for (var pair : List.of("HOST", "NET", "RNG", "GRP", "SVC-TCP", "SVC-UDP")) {
            var a = rows.get(pair + "-A").get("duplicateId");
            assertNotNull(a, pair);
            assertEquals(a, rows.get(pair + "-B").get("duplicateId"), pair);
        }
        assertNull(rows.get("HOST-C").get("duplicateId"));
        assertNotEquals(rows.get("HOST-A").get("duplicateId"), rows.get("NET-A").get("duplicateId"));
    }

    @Test void fortiGateSnapshotDuplicatesGroupByValueAndAFailClosedSetNeverGroups() {
        var config = FortiGateConfigPolicyParser.parse(FGT_HEAD + """
                config firewall address
                    edit "HOST-A"
                        set subnet 192.0.2.10 255.255.255.255
                    next
                    edit "HOST-B"
                        set subnet 192.0.2.10 255.255.255.255
                    next
                    edit "NET-A"
                        set subnet 192.0.2.0 255.255.255.0
                    next
                    edit "NET-B"
                        set subnet 192.0.2.0 255.255.255.0
                    next
                    edit "RNG-A"
                        set type iprange
                        set start-ip 192.0.2.20
                        set end-ip 192.0.2.30
                    next
                    edit "RNG-B"
                        set type iprange
                        set start-ip 192.0.2.20
                        set end-ip 192.0.2.30
                    next
                    edit "PLAIN-EXT"
                        set subnet 198.51.100.50 255.255.255.255
                    next
                    edit "PLAIN-EXT-2"
                        set subnet 198.51.100.50 255.255.255.255
                    next
                    edit "A-FQDN"
                        set type fqdn
                        set fqdn "portal.example.invalid"
                    next
                    edit "A-FQDN-2"
                        set type fqdn
                        set fqdn "portal.example.invalid"
                    next
                end
                config firewall vip
                    edit "VIP-WEB"
                        set extip 198.51.100.50
                        set mappedip "192.0.2.77"
                        set extintf "port1"
                    next
                end
                config firewall addrgrp
                    edit "GRP-A"
                        set member "HOST-A" "NET-A"
                    next
                    edit "GRP-B"
                        set member "NET-A" "HOST-A"
                    next
                    edit "GRP-EMPTY-1"
                    next
                    edit "GRP-EMPTY-2"
                    next
                    edit "GRP-EXCL-1"
                        set member "HOST-A" "NET-A"
                        set exclude enable
                        set exclude-member "HOST-B"
                    next
                    edit "GRP-EXCL-2"
                        set member "HOST-A" "NET-A"
                        set exclude enable
                        set exclude-member "HOST-B"
                    next
                end
                config firewall service custom
                    edit "SVC-A"
                        set tcp-portrange 80-81 443
                    next
                    edit "SVC-B"
                        set tcp-portrange 443 80 81
                    next
                    edit "SVC-UDP-A"
                        set udp-portrange 53
                    next
                    edit "SVC-UDP-B"
                        set udp-portrange 53
                    next
                    edit "SVC-DUAL-A"
                        set tcp-portrange 443
                        set udp-portrange 53 5353
                    next
                    edit "SVC-DUAL-B"
                        set tcp-portrange 443
                        set udp-portrange 5353 53
                    next
                    edit "SVC-ICMP-A"
                        set protocol ICMP
                    next
                    edit "SVC-ICMP-B"
                        set protocol ICMP
                    next
                end
                config firewall policy
                    edit 1
                        set srcintf "port1"
                        set dstintf "port2"
                        set srcaddr "HOST-A" "HOST-B" "NET-A" "NET-B" "RNG-A" "RNG-B" "GRP-A" "GRP-B"
                        set dstaddr "PLAIN-EXT" "PLAIN-EXT-2" "VIP-WEB" "A-FQDN" "A-FQDN-2" "GRP-EMPTY-1" "GRP-EMPTY-2" "GRP-EXCL-1" "GRP-EXCL-2"
                        set action accept
                        set schedule "always"
                        set service "SVC-A" "SVC-B" "SVC-UDP-A" "SVC-UDP-B" "SVC-DUAL-A" "SVC-DUAL-B" "SVC-ICMP-A" "SVC-ICMP-B"
                    next
                end
                """);
        var snapshots = FortiGatePolicyMapper.map("dev-fgt-1", "FW-TANGO-04", "2026-11-14T02:00:00Z", "artefact", config);
        assertEquals(1, snapshots.size());
        var snapshot = snapshots.get(0);
        var index = new PolicyDomainIndex(List.of(), List.of(snapshot));
        var rows = byName(index);
        // Preconditions of the negatives: the mapper really produced these shapes and statuses.
        assertEquals("DYNAMIC", rows.get("A-FQDN").get("status"));
        assertEquals("UNRESOLVED", rows.get("GRP-EMPTY-1").get("status"));
        assertTrue(rows.get("VIP-WEB").toString().contains("vip-mapped"));
        assertEquals(Set.of(Set.of("HOST-A", "HOST-B"), Set.of("NET-A", "NET-B"), Set.of("RNG-A", "RNG-B"), Set.of("GRP-A", "GRP-B"),
            Set.of("SVC-A", "SVC-B"), Set.of("SVC-UDP-A", "SVC-UDP-B"), Set.of("SVC-DUAL-A", "SVC-DUAL-B"), Set.of("PLAIN-EXT", "PLAIN-EXT-2")),
            duplicateNames(index));
        for (var pair : List.of("HOST", "NET", "RNG", "GRP", "SVC", "SVC-UDP", "SVC-DUAL", "PLAIN-EXT")) {
            String a = pair.equals("PLAIN-EXT") ? "PLAIN-EXT" : pair + "-A", b = pair.equals("PLAIN-EXT") ? "PLAIN-EXT-2" : pair + "-B";
            assertNotNull(rows.get(a).get("duplicateId"), pair);
            assertEquals(rows.get(a).get("duplicateId"), rows.get(b).get("duplicateId"), pair);
        }
        // Static-NAT VIP shares its external address with plain objects but is never value-equivalent to them.
        assertNull(rows.get("VIP-WEB").get("duplicateId"));
        // Dynamic, unresolved and exclusion objects and non-TCP/UDP services never group.
        for (var name : List.of("A-FQDN", "A-FQDN-2", "GRP-EMPTY-1", "GRP-EMPTY-2", "GRP-EXCL-1", "GRP-EXCL-2", "SVC-ICMP-A", "SVC-ICMP-B"))
            assertNull(rows.get(name).get("duplicateId"), name);
        assertTrue(index.usageGaps.contains(PolicyDomainIndex.FGT_OUTSIDE));
        assertFalse(index.usageGaps.contains(PolicyDomainIndex.ASA_OUTSIDE));
    }

    @Test void snapshotShapesNormalizeToTheCheckPointCanonicalFormsAndEverythingUnfamiliarIsNull() {
        PolicyObject host = snapObject("host", "RESOLVED", List.of(), "ip-netmask: 192.0.2.8/32");
        PolicyObject address = snapObject("address", "RESOLVED", List.of(), "ip-netmask: 192.0.2.8");
        PolicyObject slash32 = snapObject("network", "RESOLVED", List.of(), "ip-netmask: 192.0.2.8/32");
        var cpHost = item("cp", "Synthetic cp", "host", List.of(), "ipv4-address: 192.0.2.8");
        assertEquals(PolicyDomainIndex.normalized(cpHost), PolicyDomainIndex.normalized(asItem(host)));
        assertEquals(PolicyDomainIndex.normalized(cpHost), PolicyDomainIndex.normalized(asItem(address)));
        assertEquals(PolicyDomainIndex.normalized(cpHost), PolicyDomainIndex.normalized(asItem(slash32)));
        assertEquals(PolicyDomainIndex.normalized(item("cp", "Synthetic cp", "network", List.of(), "subnet4: 192.0.2.0", "mask-length4: 24")),
            PolicyDomainIndex.normalized(asItem(snapObject("network", "RESOLVED", List.of(), "ip-netmask: 192.0.2.0/24"))));
        assertEquals(PolicyDomainIndex.normalized(item("cp", "Synthetic cp", "address-range", List.of(), "ipv4-address-first: 192.0.2.1", "ipv4-address-last: 192.0.2.9")),
            PolicyDomainIndex.normalized(asItem(snapObject("address-range", "RESOLVED", List.of(), "ip-range: 192.0.2.1-192.0.2.9"))));
        assertEquals(PolicyDomainIndex.normalized(asItem(snapObject("address-range", "RESOLVED", List.of(), "ip-range: 192.0.2.1-192.0.2.9"))),
            PolicyDomainIndex.normalized(asItem(snapObject("address", "RESOLVED", List.of(), "ip-range: 192.0.2.1-192.0.2.9"))));
        // Services: reordered and adjacent ranges merge; source ports and protocols stay part of the value.
        String flat = PolicyDomainIndex.normalized(asItem(snapObject("service", "RESOLVED", List.of(), "protocol: tcp", "port: 443,80-81")));
        assertEquals(flat, PolicyDomainIndex.normalized(asItem(snapObject("service", "RESOLVED", List.of(), "protocol: TCP", "port: 81,80,443"))));
        assertEquals(flat, PolicyDomainIndex.normalized(asItem(snapObject("service", "RESOLVED", List.of(), "protocol/tcp/port: 80-81, 443"))));
        assertEquals(flat, PolicyDomainIndex.normalized(item("cp", "Synthetic cp", "service-tcp", List.of(), "port: 443, 80-81")).replace("service-tcp:", "service-tcp:"));
        assertTrue(flat.startsWith("service-tcp:"));
        assertNotEquals(flat, PolicyDomainIndex.normalized(asItem(snapObject("service", "RESOLVED", List.of(), "protocol: udp", "port: 443,80-81"))));
        assertNotEquals(flat, PolicyDomainIndex.normalized(asItem(snapObject("service", "RESOLVED", List.of(), "protocol/tcp/source-port: 1024-65535", "protocol/tcp/port: 443,80-81"))));
        String dual = PolicyDomainIndex.normalized(asItem(snapObject("service", "RESOLVED", List.of(), "protocol/tcp/port: 443", "protocol/udp/port: 53,5353")));
        assertTrue(dual.startsWith("service-tcp-udp:"));
        assertEquals(dual, PolicyDomainIndex.normalized(asItem(snapObject("service", "RESOLVED", List.of(), "protocol/udp/port: 5353,53", "protocol/tcp/port: 443"))));
        // Groups: member order does not matter, empty groups never match anything.
        assertEquals(PolicyDomainIndex.normalized(asItem(snapObject("address-group", "RESOLVED", List.of("m1", "m2")))),
            PolicyDomainIndex.normalized(asItem(snapObject("address-group", "RESOLVED", List.of("m2", "m1", "m1")))));
        assertNull(PolicyDomainIndex.normalized(asItem(snapObject("address-group", "UNRESOLVED", List.of()))));
        assertNull(PolicyDomainIndex.normalized(item("cp", "Synthetic empty", "group", List.of())));
        // Unfamiliar or impure shapes.
        for (var bad : List.of(
                snapObject("address", "RESOLVED", List.of(), "ip-netmask: 198.51.100.50/32", "vip-mapped: 192.0.2.10"),
                snapObject("address", "RESOLVED", List.of(), "ip-netmask: 198.51.100.50/32", "vip-extport: 443", "vip-mappedport: 8443"),
                snapObject("address", "RESOLVED", List.of(), "ip-range: 192.0.2.1-192.0.2.9", "vip-mapped: 192.0.2.10"),
                snapObject("host", "RESOLVED", List.of(), "ip-netmask: 192.0.2.8/32", "fqdn: portal.example.invalid"),
                snapObject("host", "RESOLVED", List.of(), "ip-netmask: 192.0.2.8/32", "isdb: synthetic"),
                snapObject("host", "RESOLVED", List.of(), "ip-netmask: 192.0.2.8/32", "wildcard: 0.0.0.255"),
                snapObject("host", "RESOLVED", List.of(), "ip-netmask: 192.0.2.8/32", "ipv6-address: 2001:db8::1"),
                snapObject("host", "RESOLVED", List.of(), "ip-netmask: 192.0.2.8/32", "bare token"),
                snapObject("host", "RESOLVED", List.of(), "ip-netmask: 192.0.2.8/33"),
                snapObject("host", "RESOLVED", List.of(), "ip-netmask: 2001:db8::1/128"),
                snapObject("host", "RESOLVED", List.of(), "ip-netmask: 192.0.2.8-192.0.2.9"),
                snapObject("host", "RESOLVED", List.of(), "ip-range: 192.0.2.8-192.0.2.9"),
                snapObject("address", "RESOLVED", List.of(), "ip-range: 192.0.2.9-192.0.2.1"),
                snapObject("address", "RESOLVED", List.of(), "ip-range: 192.0.2.9"),
                snapObject("service", "RESOLVED", List.of(), "protocol: icmp"),
                snapObject("service", "RESOLVED", List.of(), "protocol: icmp", "icmp-type: 8"),
                snapObject("service", "UNSUPPORTED", List.of(), "protocol: ip", "protocol-number: 47"),
                snapObject("service", "RESOLVED", List.of(), "protocol: tcp", "port: 443", "icmp-code: 0"),
                snapObject("service", "RESOLVED", List.of(), "protocol: tcp"),
                snapObject("service", "RESOLVED", List.of(), "protocol: tcp", "port: 70000"),
                snapObject("service", "RESOLVED", List.of(), "protocol: tcp", "port: >1024"),
                snapObject("service", "RESOLVED", List.of(), "protocol: tcp", "port: 443", "source-port: 5-"),
                snapObject("service", "RESOLVED", List.of(), "protocol/tcp/port: 443", "protocol: tcp"),
                snapObject("service", "RESOLVED", List.of(), "protocol/tcp/port: 443", "protocol/sctp/port: 443"),
                snapObject("service", "RESOLVED", List.of(), "protocol/udp/source-port: 53"),
                snapObject("service", "RESOLVED", List.of()),
                snapObject("interface", "RESOLVED", List.of(), "ip-netmask: 192.0.2.1/24"),
                snapObject("address", "UNKNOWN", List.of()),
                snapObject("address", "DYNAMIC", List.of()))) {
            assertNull(PolicyDomainIndex.normalized(asItem(bad)), bad.values().toString());
        }
    }

    @Test void nonResolvedObjectsNeverGroupEvenWhenTheirShapeWouldMatch() {
        var meta = snapshot(List.of()).metadata();
        var a = new PolicyObject("grp-a", "GRP-A", "address-group", List.of("m1", "m2"), List.of(), "UNSUPPORTED");
        var b = new PolicyObject("grp-b", "GRP-B", "address-group", List.of("m2", "m1"), List.of(), "UNSUPPORTED");
        var c = new PolicyObject("grp-c", "GRP-C", "address-group", List.of("m1", "m2"), List.of(), "UNRESOLVED");
        var d = new PolicyObject("grp-d", "GRP-D", "address-group", List.of("m1", "m2"), List.of(), "RESOLVED");
        var e = new PolicyObject("grp-e", "GRP-E", "address-group", List.of("m2", "m1"), List.of(), "RESOLVED");
        var index = new PolicyDomainIndex(List.of(), List.of(new PolicySnapshot(meta, List.of(),
            Map.of(a.id(), a, b.id(), b, c.id(), c, d.id(), d, e.id(), e))));
        assertEquals(Set.of(Set.of("GRP-D", "GRP-E")), duplicateNames(index));
        assertNull(byName(index).get("GRP-A").get("duplicateId"));
    }

    @Test void asaAndFortiGateAddAClosedDomainUsageGapReasonThatTheMaskedProjectionKeeps() {
        assertTrue(PolicyDomainIndex.REASONS.contains(PolicyDomainIndex.ASA_OUTSIDE));
        assertTrue(PolicyDomainIndex.REASONS.contains(PolicyDomainIndex.FGT_OUTSIDE));
        var host = new PolicyObject("host-id", "FW-TANGO-04 host", "host", List.of(), List.of("ip-netmask: 192.0.2.8/32"), "RESOLVED");
        for (var vendor : List.of("ASA", "FGT")) {
            var meta = snapshot(List.of()).metadata();
            var vendorMeta = new Metadata(meta.id(), meta.sourceId(), meta.sourceName(), vendor, meta.containerId(), meta.containerName(),
                meta.name(), meta.collectedAt(), meta.artefactRef(), meta.targets());
            var index = new PolicyDomainIndex(List.of(), List.of(new PolicySnapshot(vendorMeta, List.of(), Map.of(host.id(), host))));
            String reason = vendor.equals("ASA") ? PolicyDomainIndex.ASA_OUTSIDE : PolicyDomainIndex.FGT_OUTSIDE;
            String other = vendor.equals("ASA") ? PolicyDomainIndex.FGT_OUTSIDE : PolicyDomainIndex.ASA_OUTSIDE;
            assertTrue(index.usageGaps.contains(reason), vendor);
            assertFalse(index.usageGaps.contains(other), vendor);
            assertFalse(index.usageGaps.contains(PolicyDomainIndex.CP_OUTSIDE), vendor);
            @SuppressWarnings("unchecked") var reasons = (List<String>) index.objects.get(0).get("usageReasons");
            assertTrue(reasons.contains(reason), vendor);
            assertEquals(Boolean.FALSE, index.objects.get(0).get("usageComplete"));
            var body = Map.of("usageGaps", index.usageGaps, "usageReasons", reasons);
            var masked = PolicyPrivacy.mask(body, "", new com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer(new byte[32]),
                new com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker(new byte[32]));
            assertEquals(body, masked, vendor);
        }
    }
}
