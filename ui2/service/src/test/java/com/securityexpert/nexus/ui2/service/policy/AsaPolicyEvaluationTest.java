package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;

/**
 * Cisco ASA lookup and hygiene over a mapped running configuration (A0-ASA-1..8; PO decision record 2026-11-14 item 4:
 * the uncertain security-level default stays UNKNOWN). Synthetic addresses and masked names only.
 */
class AsaPolicyEvaluationTest {
    static final String BASE = """
            ASA Version 9.18(4)22
            interface GigabitEthernet0/0
             nameif outside
             security-level 0
            interface GigabitEthernet0/1
             nameif inside
             security-level 100
            interface GigabitEthernet0/2
             nameif dmz
             security-level 50
            interface GigabitEthernet0/3
             nameif dmz2
             security-level 50
            """;

    static PolicySnapshot asa(String body) {
        return AsaPolicyMapper.map("dev-asa-1", "Cisco ASA dev-asa-1", "2026-11-14T02:00:00Z", "artefact", AsaConfigPolicyParser.parse(BASE + body));
    }
    static Map<String, Object> run(PolicySnapshot s, String src, String dst, String service, String in, String out) {
        return PolicyLookup.evaluate(s, new PolicyLookup.Query(src, dst, service, in, out, null, null), 250000).body();
    }
    static String verdict(PolicySnapshot s, String src, String dst, String service, String in, String out) {
        return (String) run(s, src, dst, service, in, out).get("verdict");
    }

    @Test void inboundListFirstMatchThenImplicitDeny() {
        var s = asa("""
                access-list ACL-OUT extended deny tcp any host 198.51.100.20 eq 22
                access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq https
                access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq 22
                access-group ACL-OUT in interface outside
                """);
        var allowed = run(s, "203.0.113.5", "198.51.100.20", "tcp/443", "outside", "inside");
        assertEquals("ALLOWED", allowed.get("verdict"));
        assertEquals("first matching rule", allowed.get("lookupReason"));
        assertEquals("DENIED", verdict(s, "203.0.113.5", "198.51.100.20", "tcp/22", "outside", "inside"), "first match, not best match");
        var implicit = run(s, "203.0.113.5", "198.51.100.20", "tcp/8080", "outside", "inside");
        assertEquals("DENIED", implicit.get("verdict"));
        assertEquals(PolicyLookup.ASA_IMPLICIT_DENY, implicit.get("lookupReason"));
        var noZones = run(s, "203.0.113.5", "198.51.100.20", "tcp/443", null, null);
        assertEquals("UNKNOWN", noZones.get("verdict"));
        assertEquals("zone or target required", noZones.get("lookupReason"));
        assertEquals("UNKNOWN", verdict(s, "203.0.113.5", "198.51.100.20", "tcp/443", "outside", "nowhere"));
    }

    @Test void securityLevelDefaultOnlyHighToLowIsDecided() {
        var s = asa("access-list ACL-OUT extended permit tcp any any eq https\naccess-group ACL-OUT in interface outside\n");
        var high = run(s, "198.51.100.5", "203.0.113.9", "tcp/443", "inside", "outside");
        assertEquals("ALLOWED", high.get("verdict"));
        assertEquals(PolicyLookup.ASA_LEVEL_ALLOW, high.get("lookupReason"));
        var low = run(s, "192.0.2.5", "198.51.100.9", "tcp/443", "dmz", "inside");
        assertEquals("UNKNOWN", low.get("verdict"));
        assertEquals(PolicyLookup.ASA_LEVEL_UNPROVEN, low.get("lookupReason"));
    }

    @Test void sameSecurityLevelIsCheckedBeforeAccessRules() {
        var denied = asa("access-list ACL-DMZ extended permit ip any any\naccess-group ACL-DMZ in interface dmz\n");
        var inter = run(denied, "192.0.2.5", "192.0.2.9", "tcp/443", "dmz", "dmz2");
        assertEquals("DENIED", inter.get("verdict"));
        assertEquals(PolicyLookup.ASA_SAME_INTER, inter.get("lookupReason"));
        assertEquals(PolicyLookup.ASA_SAME_INTRA, run(denied, "192.0.2.5", "192.0.2.9", "tcp/443", "dmz", "dmz").get("lookupReason"));
        var permitted = asa("same-security-traffic permit inter-interface\naccess-list ACL-DMZ extended permit ip any any\naccess-group ACL-DMZ in interface dmz\n");
        assertEquals("ALLOWED", verdict(permitted, "192.0.2.5", "192.0.2.9", "tcp/443", "dmz", "dmz2"));
    }

    @Test void globalListFollowsTheInterfaceListAndOutboundMustAlsoAllow() {
        var s = asa("""
                access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq https
                access-list ACL-GLOBAL extended permit tcp any host 198.51.100.21 eq https
                access-list ACL-EGRESS extended deny tcp any any eq telnet
                access-list ACL-EGRESS extended permit ip any any
                access-group ACL-OUT in interface outside
                access-group ACL-EGRESS out interface outside
                access-group ACL-GLOBAL global
                """);
        assertEquals("ALLOWED", verdict(s, "203.0.113.5", "198.51.100.21", "tcp/443", "outside", "inside"), "global after the interface list");
        assertEquals("DENIED", verdict(s, "203.0.113.5", "198.51.100.22", "tcp/443", "outside", "inside"), "implicit deny after global");
        // inside has no "in" list, but the global list applies to every interface's inbound traffic (A0-ASA-2/3)
        assertEquals("DENIED", verdict(s, "198.51.100.5", "203.0.113.9", "tcp/443", "inside", "outside"));
        // dmz has no "in" list: the global list allows, then the egress list on outside allows too
        var egress = run(s, "192.0.2.5", "198.51.100.21", "tcp/443", "dmz", "outside");
        assertEquals("ALLOWED", egress.get("verdict"));
        assertEquals(PolicyLookup.ASA_RESULTS, egress.get("lookupReason"));
        assertEquals("DENIED", verdict(s, "192.0.2.5", "198.51.100.21", "tcp/23", "dmz", "outside"), "global has no telnet permit");
    }

    @Test void outboundListDeniesAfterAnAllowedInbound() {
        var s = asa("""
                access-list ACL-EGRESS extended deny tcp any any eq telnet
                access-list ACL-EGRESS extended permit ip any any
                access-group ACL-EGRESS out interface outside
                """);
        var telnet = run(s, "198.51.100.5", "203.0.113.9", "tcp/23", "inside", "outside");
        assertEquals("DENIED", telnet.get("verdict"));
        assertEquals(2, ((List<?>) telnet.get("layers")).size());
        assertEquals("ALLOWED", verdict(s, "198.51.100.5", "203.0.113.9", "tcp/443", "inside", "outside"));
    }

    @Test void identityTimeAndUnparsedAcesKeepLaterMatchesUnknownAndInactiveIsSkipped() {
        var s = asa("""
                access-list ACL-OUT extended permit tcp user-group LAB\\\\Ops Team any host 198.51.100.20 eq https
                access-list ACL-OUT extended deny tcp any host 198.51.100.21 eq https inactive
                access-list ACL-OUT extended permit tcp any any eq https
                access-group ACL-OUT in interface outside
                """);
        var identity = run(s, "203.0.113.5", "198.51.100.20", "tcp/443", "outside", "inside");
        assertEquals("UNKNOWN", identity.get("verdict"));
        assertEquals("earlier rule could match", identity.get("lookupReason"));
        // An identity ACE only narrows (A0-ASA-16a, PO approved 2026-11-14): its address cells bound it, so for another
        // destination it is passed over and the later permit decides; for its own destination it stays "could match".
        assertEquals("ALLOWED", verdict(s, "203.0.113.5", "198.51.100.21", "tcp/443", "outside", "inside"));
        var inactive = asa("""
                access-list ACL-OUT extended deny tcp any host 198.51.100.21 eq https inactive
                access-list ACL-OUT extended permit tcp any any eq https time-range TR-1
                access-list ACL-OUT extended permit tcp any any eq https
                access-group ACL-OUT in interface outside
                """);
        var timed = run(inactive, "203.0.113.5", "198.51.100.21", "tcp/443", "outside", "inside");
        assertEquals("UNKNOWN", timed.get("verdict"), "the inactive ACE is skipped; the time-ranged ACE could match");
        assertEquals(1, ((List<?>) timed.get("undecidableRules")).size());
    }

    @Test void aRuleTheLookupCannotDecideIsPassedOverOnlyWhenWhatResolvedIsDisjoint() {
        var s = asa("""
                access-list ACL-OUT extended deny tcp any host 198.51.100.30 eq https time-range TR-1
                access-list ACL-OUT extended deny tcp any object-group OG-FQDN eq 22
                access-list ACL-OUT extended permit tcp any any eq https
                object network OBJ-FQDN
                 fqdn host.example.test
                object-group network OG-FQDN
                 network-object object OBJ-FQDN
                access-group ACL-OUT in interface outside
                """);
        // the time-ranged deny names another destination, the FQDN deny another port: neither can match this packet
        var passed = run(s, "203.0.113.5", "198.51.100.20", "tcp/443", "outside", "inside");
        assertEquals("ALLOWED", passed.get("verdict"));
        assertEquals("first matching rule", passed.get("lookupReason"));
        // the same time-ranged deny for its own destination could match: UNKNOWN, never ALLOWED
        assertEquals("UNKNOWN", verdict(s, "203.0.113.5", "198.51.100.30", "tcp/443", "outside", "inside"));
        // the FQDN group is not resolved: for port 22 it could match
        assertEquals("UNKNOWN", verdict(s, "203.0.113.5", "198.51.100.20", "tcp/22", "outside", "inside"));
    }

    @Test void hygieneShadowsWithinOneAccessListAndReportsUnreferencedLists() {
        var s = asa("""
                access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq https
                access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq https
                access-list ACL-ORPHAN extended permit ip any any
                access-group ACL-OUT in interface outside
                """);
        var result = PolicyHygiene.analyze(s, 90, Instant.parse("2026-11-14T12:00:00Z"), 250000);
        var rules = s.sections().get(0).rules();
        assertEquals("NOT_SHADOWED", result.rules().get(rules.get(0).id()).shadowStatus());
        assertEquals("REDUNDANT", result.rules().get(rules.get(1).id()).shadowStatus());
        var notEvaluated = s.sections().stream().filter(x -> x.name().equals("ASA not evaluated")).findFirst().orElseThrow();
        var orphan = result.rules().get(notEvaluated.rules().get(0).id());
        assertTrue(orphan.findings().stream().anyMatch(f -> f.evidence().equals(PolicyHygiene.ASA_ACL_NOT_REFERENCED)));
    }

    @Test void aclNotBoundIsUnusedOnlyWhenNoFeatureNamesIt_andUnknownWhenAnUnparsedFeatureDoes() {
        var s = asa("""
                access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq https
                access-list ACL-ORPHAN-1 extended permit ip any any
                access-list ACL-DAP-1 extended permit ip any any
                access-list ACL-CRYPTO-1 extended permit ip any any
                access-group ACL-OUT in interface outside
                dynamic-access-policy-record DAP-1
                 network-acl ACL-DAP-1
                crypto map CM-1 10 match address ACL-CRYPTO-1
                """);
        var result = PolicyHygiene.analyze(s, 90, Instant.parse("2026-11-23T12:00:00Z"), 250000);
        var notEvaluated = s.sections().stream().filter(x -> x.name().equals("ASA not evaluated")).findFirst().orElseThrow();
        var byName = new HashMap<String, PolicyHygiene.Assessment>();
        notEvaluated.rules().forEach(r -> byName.put(r.name(), result.rules().get(r.id())));

        var none = byName.get("ACL-ORPHAN-1");
        assertEquals(1, none.findings().stream().filter(f -> f.findingClass().equals("unused")).count());
        var unused = none.findings().stream().filter(f -> f.findingClass().equals("unused")).findFirst().orElseThrow();
        assertEquals("LOW", unused.severity());
        assertEquals(PolicyHygiene.ASA_ACL_NOT_REFERENCED, unused.evidence());

        var unknown = byName.get("ACL-DAP-1");
        assertTrue(unknown.findings().stream().noneMatch(f -> f.findingClass().equals("unused")), "UNKNOWN is never shown as unused");
        assertEquals("UNKNOWN", unknown.shadowStatus());
        assertEquals(PolicyHygiene.ASA_ACL_USE_UNKNOWN, unknown.shadowReason());
        assertTrue(PolicyHygiene.REASONS.contains(unknown.shadowReason()));

        // a parsed use (crypto map) stays no finding and keeps the generic reason; a bound ACL is not in this section
        var used = byName.get("ACL-CRYPTO-1");
        assertTrue(used.findings().stream().noneMatch(f -> f.findingClass().equals("unused")));
        assertEquals("unsupported rulebase", used.shadowReason());
        assertFalse(byName.containsKey("ACL-OUT"));
    }

    @Test void anIdentityAceBoundsItsMatchForShadowingButNeverCoversOrDecides() {
        var s = asa("""
                access-list ACL-OUT extended permit tcp user-group LAB\\\\Ops any host 198.51.100.20 eq https
                access-list ACL-OUT extended deny tcp any host 198.51.100.21 eq https
                access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq https
                access-group ACL-OUT in interface outside
                """);
        var result = PolicyHygiene.analyze(s, 90, Instant.parse("2026-11-14T12:00:00Z"), 250000);
        var rules = s.sections().get(0).rules();
        assertEquals("UNKNOWN", result.rules().get(rules.get(0).id()).shadowStatus(), "the identity ACE itself is not evaluated");
        assertEquals("NOT_SHADOWED", result.rules().get(rules.get(1).id()).shadowStatus(), "disjoint from the identity ACE's bound");
        assertEquals("UNKNOWN", result.rules().get(rules.get(2).id()).shadowStatus(), "inside the bound: the identity ACE may cover it");
        // The lookup never allows by identity: for the identity ACE's own destination the answer stays UNKNOWN.
        assertEquals("UNKNOWN", verdict(s, "203.0.113.5", "198.51.100.20", "tcp/443", "outside", "inside"));
        assertEquals("DENIED", verdict(s, "203.0.113.5", "198.51.100.21", "tcp/443", "outside", "inside"));
    }
}
