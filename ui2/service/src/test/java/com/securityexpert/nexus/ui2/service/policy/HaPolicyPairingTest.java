package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.CollectionFailure;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.HitCounts;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Target;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyMapper;

/** HA pair policy comparison (record 2026-11-14 decision 6) over synthetic snapshots only. */
class HaPolicyPairingTest {
    static final String A = "dev-fw-tango-04", B = "dev-fw-tango-05";
    static final HaPolicyPairing.Pair ASA_PAIR = new HaPolicyPairing.Pair("pair-1", "ASA", A, B, true);
    static final HaPolicyPairing.Pair FGT_PAIR = new HaPolicyPairing.Pair("pair-2", "FGT", A, B, true);

    /** A synthetic rule specification: action, address group members and the group name. */
    record Spec(String name, String action, List<String> groupValues, String groupName) {
        Spec(String name, String action) { this(name, action, List.of("192.0.2.10/32"), "GRP-WEB"); }
    }

    /** Ids are derived from the device id like the real mappers do, so equal content still has different ids. */
    static PolicySnapshot snapshot(String device, String vendor, String context, String collectedAt, List<Spec> specs,
            List<CollectionFailure> failures, Long hits) {
        String sid = PolicySnapshot.ref(vendor, device, context);
        var metadata = new Metadata(sid, device, "FW-TANGO " + device, vendor, PolicySnapshot.ref(device, context), context,
                vendor.equals("ASA") ? "ASA access rules" : "FGT firewall policy", collectedAt,
                PolicySnapshot.ref("configuration-artefact", "artefact-" + device),
                List.of(new Target(device, "FW-TANGO " + device, vendor.equals("ASA") ? "" : context, "IN_SYNC")));
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        List<Rule> rules = new ArrayList<>();
        int number = 1;
        for (Spec spec : specs) {
            String host = PolicySnapshot.ref(sid, "host", spec.name());
            String group = PolicySnapshot.ref(sid, "group", spec.name());
            String any = PolicySnapshot.ref(sid, "any");
            objects.put(host, new PolicyObject(host, "HOST-" + spec.name(), "host", List.of(), spec.groupValues(), "RESOLVED"));
            objects.put(group, new PolicyObject(group, spec.groupName(), "group", List.of(host), List.of(), "RESOLVED"));
            objects.put(any, new PolicyObject(any, "any", "any", List.of(), List.of(), "RESOLVED"));
            Map<String, List<String>> extras = Map.of("from", List.of("outside"), "to", List.of("inside"), "user", List.of("synthetic-group"));
            HitCounts counts = hits == null ? null : new HitCounts(hits, null, null, "device", collectedAt, null, List.of());
            rules.add(new Rule(PolicySnapshot.ref(sid, "rule", spec.name()), null, number++, spec.name(), true,
                    new Cell(List.of(any), false), new Cell(List.of(group), false), new Cell(List.of("tcp/443"), false),
                    new Cell(List.of(), false), spec.action(), "log", "", extras, counts));
        }
        var section = new Section(PolicySnapshot.ref(sid, "section"), "ACL-OUT", vendor.equals("ASA") ? "ASA access-group" : "FGT firewall policy", null, rules);
        return new PolicySnapshot(metadata, List.of(section), objects, failures);
    }

    static PolicySnapshot asa(String device, String collectedAt, Spec... specs) {
        return snapshot(device, "ASA", "single", collectedAt, List.of(specs), List.of(), null);
    }

    static HaPolicyPairing.Result only(List<HaPolicyPairing.Result> results) {
        assertEquals(1, results.size());
        return results.get(0);
    }

    @Test void identicalContentWithDifferentIdsArtefactsTimesAndHitsIsSame() {
        var a = snapshot(A, "ASA", "single", "2026-11-20T10:00:00Z", List.of(new Spec("R1", "permit"), new Spec("R2", "deny")), List.of(), 10L);
        var b = snapshot(B, "ASA", "single", "2026-11-21T10:00:00Z", List.of(new Spec("R1", "permit"), new Spec("R2", "deny")), List.of(), 99L);
        assertNotEquals(a.metadata().id(), b.metadata().id());
        assertEquals(HaPolicyPairing.canonicalHash(a), HaPolicyPairing.canonicalHash(b));
        var result = only(HaPolicyPairing.evaluate(List.of(ASA_PAIR), List.of(a, b)));
        assertEquals(HaPolicyPairing.Outcome.SAME, result.outcome());
        assertNull(result.reason());
        assertEquals("single", result.context());
        assertEquals("2026-11-21T10:00:00Z", result.newerCollectedAt());
        assertEquals("2026-11-20T10:00:00Z", result.olderCollectedAt());
    }

    @Test void oneRuleActionDifferenceIsDivergent() {
        var result = only(HaPolicyPairing.evaluate(List.of(ASA_PAIR), List.of(
                asa(A, "2026-11-20T10:00:00Z", new Spec("R1", "permit"), new Spec("R2", "deny")),
                asa(B, "2026-11-20T10:00:00Z", new Spec("R1", "permit"), new Spec("R2", "permit")))));
        assertEquals(HaPolicyPairing.Outcome.HA_POLICY_DIVERGENT, result.outcome());
        assertNull(result.reason());
    }

    @Test void oneObjectMemberDifferenceIsDivergent() {
        var result = only(HaPolicyPairing.evaluate(List.of(ASA_PAIR), List.of(
                asa(A, "2026-11-20T10:00:00Z", new Spec("R1", "permit", List.of("192.0.2.10/32"), "GRP-WEB")),
                asa(B, "2026-11-20T10:00:00Z", new Spec("R1", "permit", List.of("192.0.2.11/32"), "GRP-WEB")))));
        assertEquals(HaPolicyPairing.Outcome.HA_POLICY_DIVERGENT, result.outcome());
    }

    @Test void ruleOrderDifferenceIsDivergent() {
        var result = only(HaPolicyPairing.evaluate(List.of(ASA_PAIR), List.of(
                asa(A, "2026-11-20T10:00:00Z", new Spec("R1", "permit"), new Spec("R2", "deny")),
                asa(B, "2026-11-20T10:00:00Z", new Spec("R2", "deny"), new Spec("R1", "permit")))));
        assertEquals(HaPolicyPairing.Outcome.HA_POLICY_DIVERGENT, result.outcome());
    }

    @Test void objectNameDifferingOnlyInCaseIsDivergent() {
        var result = only(HaPolicyPairing.evaluate(List.of(ASA_PAIR), List.of(
                asa(A, "2026-11-20T10:00:00Z", new Spec("R1", "permit", List.of("192.0.2.10/32"), "GRP-WEB")),
                asa(B, "2026-11-20T10:00:00Z", new Spec("R1", "permit", List.of("192.0.2.10/32"), "grp-web")))));
        assertEquals(HaPolicyPairing.Outcome.HA_POLICY_DIVERGENT, result.outcome());
    }

    @Test void oneMemberWithoutSnapshotIsUnknown() {
        var result = only(HaPolicyPairing.evaluate(List.of(ASA_PAIR), List.of(asa(A, "2026-11-20T10:00:00Z", new Spec("R1", "permit")))));
        assertEquals(HaPolicyPairing.Outcome.UNKNOWN, result.outcome());
        assertEquals(HaPolicyPairing.Reason.MEMBER_POLICY_MISSING, result.reason());
        assertEquals("2026-11-20T10:00:00Z", result.newerCollectedAt());
        assertNull(result.olderCollectedAt());
        var none = only(HaPolicyPairing.evaluate(List.of(ASA_PAIR), List.of()));
        assertEquals(HaPolicyPairing.Reason.MEMBER_POLICY_MISSING, none.reason());
        assertNull(none.context());
        assertNull(none.newerCollectedAt());
    }

    @Test void identityNotMatchIsUnknownEvenForIdenticalContent() {
        var pair = new HaPolicyPairing.Pair("pair-1", "ASA", A, B, false);
        var result = only(HaPolicyPairing.evaluate(List.of(pair), List.of(
                asa(A, "2026-11-20T10:00:00Z", new Spec("R1", "permit")), asa(B, "2026-11-20T10:00:00Z", new Spec("R1", "permit")))));
        assertEquals(HaPolicyPairing.Outcome.UNKNOWN, result.outcome());
        assertEquals(HaPolicyPairing.Reason.PAIR_IDENTITY_NOT_MATCH, result.reason());
    }

    @Test void fortiGateVdomSetsThatDifferAreUnknownForTheUnsharedVdom() {
        List<Spec> rules = List.of(new Spec("R1", "accept"));
        var results = HaPolicyPairing.evaluate(List.of(FGT_PAIR), List.of(
                snapshot(A, "FGT", "root", "2026-11-20T10:00:00Z", rules, List.of(), null),
                snapshot(A, "FGT", "VDOM-DMZ", "2026-11-20T10:00:00Z", rules, List.of(), null),
                snapshot(B, "FGT", "root", "2026-11-20T10:00:00Z", rules, List.of(), null)));
        assertEquals(2, results.size());
        var byContext = new LinkedHashMap<String, HaPolicyPairing.Result>();
        results.forEach(r -> byContext.put(r.context(), r));
        assertEquals(HaPolicyPairing.Outcome.UNKNOWN, byContext.get("VDOM-DMZ").outcome());
        assertEquals(HaPolicyPairing.Reason.CONTEXT_SET_DIFFERS, byContext.get("VDOM-DMZ").reason());
        assertEquals(HaPolicyPairing.Outcome.SAME, byContext.get("root").outcome(), "a VDOM both members have is still compared");
    }

    @Test void memberSnapshotWithCollectionFailuresIsUnknownButAdvisoryFailuresAreNot() {
        List<Spec> rules = List.of(new Spec("R1", "permit"));
        var failed = snapshot(B, "ASA", "single", "2026-11-20T10:00:00Z", rules,
                List.of(new CollectionFailure("layer", "ASA_UNSUPPORTED_MODE", "transparent", 0)), null);
        var result = only(HaPolicyPairing.evaluate(List.of(ASA_PAIR), List.of(asa(A, "2026-11-20T10:00:00Z", rules.get(0)), failed)));
        assertEquals(HaPolicyPairing.Outcome.UNKNOWN, result.outcome());
        assertEquals(HaPolicyPairing.Reason.SNAPSHOT_HAS_COLLECTION_FAILURES, result.reason());
        var advisory = snapshot(B, "ASA", "single", "2026-11-20T10:00:00Z", rules,
                List.of(new CollectionFailure("layer", "POLICY_HITS_UNAVAILABLE")), null);
        assertEquals(HaPolicyPairing.Outcome.SAME,
                only(HaPolicyPairing.evaluate(List.of(ASA_PAIR), List.of(asa(A, "2026-11-20T10:00:00Z", rules.get(0)), advisory))).outcome());
    }

    @Test void otherVendorsAndOtherDevicesAreIgnored() {
        var pan = snapshot(B, "PAN", "single", "2026-11-20T10:00:00Z", List.of(new Spec("R1", "permit")), List.of(), null);
        var fgtOfB = snapshot(B, "FGT", "single", "2026-11-20T10:00:00Z", List.of(new Spec("R1", "permit")), List.of(), null);
        var result = only(HaPolicyPairing.evaluate(List.of(ASA_PAIR), List.of(asa(A, "2026-11-20T10:00:00Z", new Spec("R1", "permit")), pan, fgtOfB)));
        assertEquals(HaPolicyPairing.Reason.MEMBER_POLICY_MISSING, result.reason());
    }

    @Test void groupCyclesTerminateAndStayDeterministic() {
        String sid = "cycle";
        var g1 = new PolicyObject("g1", "GRP-1", "group", List.of("g2"), List.of(), "RESOLVED");
        var g2 = new PolicyObject("g2", "GRP-2", "group", List.of("g1"), List.of(), "RESOLVED");
        var rule = new Rule("r", null, 1, "R1", true, new Cell(List.of("g1"), false), new Cell(List.of("g2"), false),
                new Cell(List.of(), false), new Cell(List.of(), false), "permit", "", "", Map.of());
        var meta = new Metadata(sid, A, "x", "ASA", "c", "single", "ASA access rules", "2026-11-20T10:00:00Z", "a", List.of());
        var snapshot = new PolicySnapshot(meta, List.of(new Section("s", "ACL", "ASA access-group", null, List.of(rule))), Map.of("g1", g1, "g2", g2));
        assertEquals(HaPolicyPairing.canonicalHash(snapshot), HaPolicyPairing.canonicalHash(snapshot));
        assertEquals(64, HaPolicyPairing.canonicalHash(snapshot).length());
    }

    @Test void pairRefusesIdenticalMembersAndUnsupportedVendors() {
        assertThrows(IllegalArgumentException.class, () -> new HaPolicyPairing.Pair("p", "ASA", A, A, true));
        assertThrows(IllegalArgumentException.class, () -> new HaPolicyPairing.Pair("p", "PAN", A, B, true));
    }

    // ---- The real mappers: the same configuration mapped for two device ids hashes the same ----

    static final String ASA_BASE = """
            ASA Version 9.18(4)22
            interface GigabitEthernet0/0
             nameif outside
             security-level 0
            interface GigabitEthernet0/1
             nameif inside
             security-level 100
            object network WEB-01
             host 198.51.100.20
            object-group network WEB-SERVERS
             network-object object WEB-01
             network-object host 198.51.100.21
            """;
    static final String ASA_ACL = """
            access-list ACL-OUT extended deny tcp any host 198.51.100.20 eq 22
            access-list ACL-OUT extended permit tcp any object-group WEB-SERVERS eq https
            access-group ACL-OUT in interface outside
            """;

    static PolicySnapshot mappedAsa(String device, String collectedAt, String config) {
        return AsaPolicyMapper.map(device, "Cisco ASA " + device, collectedAt,
                PolicySnapshot.ref("configuration-artefact", "artefact-" + device), AsaConfigPolicyParser.parse(config));
    }

    @Test void realAsaMapperOutputOfTheSameConfigurationIsSameAndAnEditIsDivergent() {
        var a = mappedAsa(A, "2026-11-20T10:00:00Z", ASA_BASE + ASA_ACL);
        var b = mappedAsa(B, "2026-11-22T10:00:00Z", ASA_BASE + ASA_ACL);
        assertTrue(a.ruleComplete() && b.ruleComplete(), "synthetic configuration maps without failures");
        assertEquals(HaPolicyPairing.Outcome.SAME, only(HaPolicyPairing.evaluate(List.of(ASA_PAIR), List.of(a, b))).outcome());
        var edited = mappedAsa(B, "2026-11-22T10:00:00Z", ASA_BASE.replace("host 198.51.100.21", "host 198.51.100.22") + ASA_ACL);
        assertEquals(HaPolicyPairing.Outcome.HA_POLICY_DIVERGENT, only(HaPolicyPairing.evaluate(List.of(ASA_PAIR), List.of(a, edited))).outcome());
    }

    static final String FGT_CONFIG = """
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
            config firewall address
                edit "all"
                next
                edit "HOST-WEB"
                    set subnet 198.51.100.20 255.255.255.255
                next
            end
            config firewall service custom
                edit "HTTPS"
                    set tcp-portrange 443
                next
            end
            config firewall policy
                edit 1
                    set srcintf "port1"
                    set dstintf "port2"
                    set srcaddr "all"
                    set dstaddr "HOST-WEB"
                    set service "HTTPS"
                    set schedule "always"
                    set action accept
                next
            end
            """;

    static PolicySnapshot mappedFgt(String device, String config) {
        var snapshots = FortiGatePolicyMapper.map(device, "FortiGate " + device, "2026-11-20T10:00:00Z",
                PolicySnapshot.ref("configuration-artefact", "artefact-" + device), FortiGateConfigPolicyParser.parse(config));
        assertEquals(1, snapshots.size());
        return snapshots.get(0);
    }

    @Test void realFortiGateMapperOutputOfTheSameConfigurationIsSameAndAnEditIsDivergent() {
        var a = mappedFgt(A, FGT_CONFIG);
        var b = mappedFgt(B, FGT_CONFIG);
        var same = only(HaPolicyPairing.evaluate(List.of(FGT_PAIR), List.of(a, b)));
        assertEquals("root", same.context());
        assertEquals(HaPolicyPairing.Outcome.SAME, same.outcome(), String.valueOf(same.reason()));
        var edited = mappedFgt(B, FGT_CONFIG.replace("set action accept", "set action deny"));
        assertEquals(HaPolicyPairing.Outcome.HA_POLICY_DIVERGENT, only(HaPolicyPairing.evaluate(List.of(FGT_PAIR), List.of(a, edited))).outcome());
    }
}
