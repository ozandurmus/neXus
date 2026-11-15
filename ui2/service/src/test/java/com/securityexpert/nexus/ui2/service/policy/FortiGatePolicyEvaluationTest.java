package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyMapper;

/**
 * FortiGate lookup and hygiene over a mapped configuration (A0-FG-1..27; PO decision record 2026-11-14 item 3: UNKNOWN
 * stays UNKNOWN). Synthetic addresses and masked names only.
 */
class FortiGatePolicyEvaluationTest {
    static final String HEAD = """
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
                edit "SSH"
                    set tcp-portrange 22
                next
                edit "ALL"
                    set protocol IP
                next
            end
            """;

    static PolicySnapshot fgt(String policies) {
        return fgt("", policies);
    }
    static PolicySnapshot fgt(String extra, String policies) {
        var snapshots = FortiGatePolicyMapper.map("dev-fgt-1", "FortiGate dev-fgt-1", "2026-11-14T02:00:00Z", "artefact",
            FortiGateConfigPolicyParser.parse(HEAD + extra + "config firewall policy\n" + policies + "end\n"));
        assertEquals(1, snapshots.size());
        return snapshots.get(0);
    }
    static String policy(int id, String... settings) {
        StringBuilder text = new StringBuilder("    edit " + id + "\n");
        for (String setting : settings) text.append("        set ").append(setting).append('\n');
        return text.append("    next\n").toString();
    }
    static Map<String, Object> run(PolicySnapshot s, String src, String dst, String service, String in, String out) {
        return PolicyLookup.evaluate(s, new PolicyLookup.Query(src, dst, service, in, out, null, null), 250000).body();
    }
    static final String[] ALL = {"srcaddr \"all\"", "dstaddr \"all\"", "schedule \"always\""};
    static String[] with(String... settings) {
        List<String> all = new ArrayList<>(List.of(ALL)); all.addAll(List.of(settings)); return all.toArray(String[]::new);
    }

    @Test void firstMatchInSequenceOrderThenTheImplicitDeny() {
        var s = fgt(policy(20, with("srcintf \"port1\"", "dstintf \"port2\"", "service \"SSH\"", "action deny"))
                + policy(10, with("srcintf \"port1\"", "dstintf \"port2\"", "service \"HTTPS\" \"SSH\"", "action accept")));
        assertTrue(s.failures().isEmpty(), String.valueOf(s.failures()));
        var https = run(s, "192.0.2.5", "198.51.100.20", "tcp/443", "port1", "port2");
        assertEquals("ALLOWED", https.get("verdict"));
        assertEquals("first matching rule", https.get("lookupReason"));
        // Policy 20 is printed first: sequence order, not policy id order (A0-FG-1).
        assertEquals("DENIED", run(s, "192.0.2.5", "198.51.100.20", "tcp/22", "port1", "port2").get("verdict"));
        var implicit = run(s, "192.0.2.5", "198.51.100.20", "tcp/8080", "port1", "port2");
        assertEquals("DENIED", implicit.get("verdict"));
        assertEquals(PolicyLookup.FGT_IMPLICIT_DENY, implicit.get("lookupReason"));
        // Another interface pair: no policy names it, the implicit deny applies.
        assertEquals("DENIED", run(s, "198.51.100.20", "192.0.2.5", "tcp/443", "port2", "port1").get("verdict"));
        assertEquals("UNKNOWN", run(s, "192.0.2.5", "198.51.100.20", "tcp/443", null, null).get("verdict"), "zones are required");
    }

    @Test void anyInterfacePoliciesNeedNoZoneAndAbsentActionIsTheCitedDenyDefault() {
        var s = fgt(policy(1, with("srcintf \"any\"", "dstintf \"any\"", "dstaddr \"HOST-WEB\"", "service \"SSH\""))
                + policy(2, with("srcintf \"any\"", "dstintf \"any\"", "service \"HTTPS\"", "action accept")));
        // Policy 1 sets dstaddr twice (with() adds "all" first): the last set line wins, as on the device's own text.
        assertEquals("DENIED", run(s, "192.0.2.5", "198.51.100.20", "tcp/22", null, null).get("verdict"), "absent action = deny (A0-FG-5)");
        assertEquals("ALLOWED", run(s, "192.0.2.5", "203.0.113.9", "tcp/443", "port1", "port2").get("verdict"));
        assertEquals("DENIED", run(s, "192.0.2.5", "203.0.113.9", "tcp/25", null, null).get("verdict"));
    }

    @Test void identityScheduleAndVipCriteriaStayUnknownAndDisabledPoliciesAreSkipped() {
        var s = fgt("""
                config user group
                    edit "GRP-OPS"
                    next
                end
                """, policy(1, with("srcintf \"port1\"", "dstintf \"port2\"", "service \"HTTPS\"", "action deny", "status disable"))
                + policy(2, "srcintf \"port1\"", "dstintf \"port2\"", "srcaddr \"all\"", "dstaddr \"all\"", "service \"HTTPS\"",
                        "action deny", "schedule \"WORKHOURS\"")
                + policy(3, with("srcintf \"port1\"", "dstintf \"port2\"", "service \"HTTPS\"", "action accept", "groups \"GRP-OPS\""))
                + policy(4, with("srcintf \"port1\"", "dstintf \"port2\"", "service \"HTTPS\"", "action accept")));
        var r = run(s, "192.0.2.5", "198.51.100.20", "tcp/443", "port1", "port2");
        assertEquals("UNKNOWN", r.get("verdict"));
        assertEquals("earlier rule could match", r.get("lookupReason"));
        assertEquals(2, ((List<?>) r.get("undecidableRules")).size(), "the schedule and the user group, not the disabled rule");
    }

    @Test void aZonePlacedPolicyIsNotEvaluatedByNameAndAnIntrazoneAllowMakesTheFallThroughUnknown() {
        var s = fgt("""
                config system zone
                    edit "ZONE-IN"
                        set intrazone allow
                        set interface "port1"
                    next
                end
                """, policy(1, with("srcintf \"ZONE-IN\"", "dstintf \"port2\"", "service \"SSH\"", "action deny")));
        var r = run(s, "192.0.2.5", "198.51.100.20", "tcp/22", "port1", "port2");
        assertEquals("UNKNOWN", r.get("verdict"));
        var other = run(s, "192.0.2.5", "198.51.100.20", "tcp/443", "port1", "port2");
        assertEquals("UNKNOWN", other.get("verdict"), "a zone's intrazone allow is not evaluated");
    }

    @Test void hygieneShadowsWithinTheVdomPolicyTable() {
        var s = fgt(policy(1, with("srcintf \"port1\"", "dstintf \"port2\"", "service \"HTTPS\"", "action accept"))
                + policy(2, with("srcintf \"port1\"", "dstintf \"port2\"", "service \"HTTPS\"", "action accept"))
                + policy(3, with("srcintf \"port1\"", "dstintf \"port2\"", "service \"HTTPS\"", "action deny")));
        var result = PolicyHygiene.analyze(s, 90, Instant.parse("2026-11-14T12:00:00Z"), 250000);
        var rules = s.sections().get(0).rules();
        assertEquals("NOT_SHADOWED", result.rules().get(rules.get(0).id()).shadowStatus());
        assertEquals("REDUNDANT", result.rules().get(rules.get(1).id()).shadowStatus());
        assertEquals("CONFLICT", result.rules().get(rules.get(2).id()).shadowStatus());
    }
}
