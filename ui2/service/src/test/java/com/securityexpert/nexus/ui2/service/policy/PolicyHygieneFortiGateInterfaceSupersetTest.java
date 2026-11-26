package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * FortiGate L3 interface placement superset coverage in {@link PolicyHygiene} (A0-FG-9,
 * {@code ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md} section 5.4 item 6):
 * <ul>
 * <li>An earlier enabled rule whose {@code srcintf}/{@code dstintf} L3 interface sets hold a later rule's covers it
 * ({@code REDUNDANT} or {@code CONFLICT}), verified by {@link PartialPriorOracle}.</li>
 * <li>An earlier narrower or partially overlapping L3 interface set provably cannot cover as a single rule
 * ({@link PolicyNormalization#zonesCannotCover}) and releases the later rule to {@code NOT_SHADOWED}, confirmed by
 * {@link PartialPriorOracle#assertNoCover}.</li>
 * <li>An earlier named interface list before a later {@code "any"} stays fail-closed {@code UNKNOWN}
 * {@code "zone or install-on coverage is unproven"} (no {@link ZoneUniverse} assumed for FortiGate).</li>
 * <li>Compatible exact-key covers still win when both an earlier superset rule and a later exact-key rule cover a
 * third rule.</li>
 * <li>A rule naming a {@code config system zone} or SD-WAN zone object continues to fail normalization with
 * {@code "zone membership not evaluated"} (A0-FG-9) and never produces a false cover or false {@code NOT_SHADOWED} on
 * a later overlapping rule.</li>
 * </ul>
 * Synthetic FortiOS 7.4 configurations only (masked names, RFC 5737 documentation ranges). Automated validation only.
 */
class PolicyHygieneFortiGateInterfaceSupersetTest {
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final String UNPROVEN = PartialPriorOracle.UNPROVEN;
    private static final String ZONE_NOT_EVALUATED = "zone membership not evaluated";
    private static final String EARLIER_UNSUPPORTED = PolicyHygiene.EARLIER_UNSUPPORTED;

    private static final String BASE = """
            #config-version=FGVM64-7.4.8-FW-build2795-250521:opmode=0:vdom=0
            config system interface
                edit "port1"
                    set vdom "root"
                    set ip 192.0.2.1 255.255.255.0
                next
                edit "port2"
                    set vdom "root"
                    set ip 198.51.100.1 255.255.255.0
                next
                edit "port3"
                    set vdom "root"
                    set ip 203.0.113.1 255.255.255.0
                next
                edit "port4"
                    set vdom "root"
                    set ip 192.0.2.129 255.255.255.128
                next
            end
            config firewall address
                edit "all"
                next
                edit "NET-CLIENT"
                    set subnet 192.0.2.0 255.255.255.0
                next
                edit "HOST-CLIENT"
                    set subnet 192.0.2.10 255.255.255.255
                next
                edit "NET-SERVER"
                    set subnet 198.51.100.0 255.255.255.0
                next
                edit "HOST-SERVER"
                    set subnet 198.51.100.20 255.255.255.255
                next
                edit "HOST-OTHER"
                    set subnet 203.0.113.30 255.255.255.255
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
                    set protocol ALL
                next
            end
            """;

    private static final String ZONES = """
            config system zone
                edit "ZONE-DMZ-01"
                    set interface "port2"
                next
            end
            config system sdwan
                set status enable
                config zone
                    edit "SDWAN-WAN-01"
                    next
                end
                config members
                    edit 1
                        set interface "port3"
                        set zone "SDWAN-WAN-01"
                    next
                end
            end
            """;

    private static PolicySnapshot snap(String policies) {
        return snap("", policies);
    }

    private static PolicySnapshot snap(String extra, String policies) {
        String text = BASE + extra + "config firewall policy\n" + policies + "end\n";
        var snapshots = FortiGatePolicyMapper.map(
                "dev-fgt-04", "FW-TANGO-04", "2026-11-14T02:00:00Z", "artefact",
                FortiGateConfigPolicyParser.parse(text));
        assertEquals(1, snapshots.size());
        PolicySnapshot snapshot = snapshots.get(0);
        assertTrue(snapshot.failures().isEmpty(), String.valueOf(snapshot.failures()));
        return snapshot;
    }

    private static String pol(int id, String... overrides) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("srcintf", "\"port1\"");
        settings.put("dstintf", "\"port3\"");
        settings.put("srcaddr", "\"all\"");
        settings.put("dstaddr", "\"all\"");
        settings.put("action", "accept");
        settings.put("schedule", "\"always\"");
        settings.put("service", "\"ALL\"");
        for (String override : overrides) {
            int space = override.indexOf(' ');
            if (space < 0) settings.remove(override);
            else settings.put(override.substring(0, space), override.substring(space + 1));
        }
        StringBuilder out = new StringBuilder("    edit ").append(id).append('\n');
        settings.forEach((k, v) -> out.append("        set ").append(k).append(' ').append(v).append('\n'));
        return out.append("    next\n").toString();
    }

    private static List<Rule> policyRules(PolicySnapshot s) {
        return s.sections().stream()
                .filter(sec -> "FGT firewall policy".equals(sec.source()))
                .findFirst()
                .orElseThrow()
                .rules();
    }

    private static Map<String, PolicyHygiene.Assessment> analyze(PolicySnapshot s) {
        return PolicyHygiene.analyze(s, 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
    }

    private static void assertCovered(PolicySnapshot s, int ruleIndex, int coveringIndex, String expectedStatus) {
        List<Rule> rules = policyRules(s);
        Rule later = rules.get(ruleIndex);
        Rule covering = rules.get(coveringIndex);
        PolicyHygiene.Assessment got = analyze(s).get(later.id());
        assertEquals(expectedStatus, got.shadowStatus(), later.id() + " -> " + got);
        assertEquals(covering.id(), got.coveringRuleId());
        assertNull(got.shadowReason());
        assertTrue(got.findings().stream().noneMatch(f -> f.evidence().startsWith("Shadowing:")), got.findings().toString());
        String expectedClass = "CONFLICT".equals(expectedStatus) ? "conflict" : "shadowed";
        assertTrue(got.findings().stream().anyMatch(f -> expectedClass.equals(f.findingClass())), got.findings().toString());
        var universe = new PartialPriorOracle.Universe(s);
        PartialPriorOracle.assertCoverIsSafe(universe, later.id(), covering.id(), expectedStatus, later.id());
    }

    private static void assertNotShadowedAndSafe(PolicySnapshot s, int ruleIndex) {
        List<Rule> rules = policyRules(s);
        Rule later = rules.get(ruleIndex);
        PolicyHygiene.Assessment got = analyze(s).get(later.id());
        assertEquals("NOT_SHADOWED", got.shadowStatus(), later.id() + " -> " + got);
        assertNull(got.shadowReason());
        assertNull(got.coveringRuleId());
        assertTrue(got.findings().stream().noneMatch(f -> f.evidence().startsWith("Shadowing:")), got.findings().toString());
        var universe = new PartialPriorOracle.Universe(s);
        PartialPriorOracle.assertNoCover(universe, later.id(), later.id());
    }

    private static void assertUnknownReason(PolicySnapshot s, int ruleIndex, String expectedReason) {
        List<Rule> rules = policyRules(s);
        Rule rule = rules.get(ruleIndex);
        PolicyHygiene.Assessment got = analyze(s).get(rule.id());
        assertEquals("UNKNOWN", got.shadowStatus(), rule.id() + " -> " + got);
        assertEquals(expectedReason, got.shadowReason());
        assertNull(got.coveringRuleId());
        assertTrue(got.findings().stream().anyMatch(
                f -> "unknown".equals(f.findingClass()) && ("Shadowing: " + expectedReason + ".").equals(f.evidence())),
                got.findings().toString());
    }

    @Test
    void earlierSrcintfOrDstintfSupersetCoversRedundantAndConflict() {
        // Same action accept: srcintf "port1" "port2" -> dstintf "port3" covers srcintf "port1" -> dstintf "port3".
        var redundant = snap(
                pol(1, "srcintf \"port1\" \"port2\"", "dstintf \"port3\"",
                        "srcaddr \"NET-CLIENT\"", "dstaddr \"NET-SERVER\"", "service \"ALL\"", "action accept")
                + pol(2, "srcintf \"port1\"", "dstintf \"port3\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertNotShadowedAndSafe(redundant, 0);
        assertCovered(redundant, 1, 0, "REDUNDANT");

        // Different action (earlier deny, later accept): yields CONFLICT naming rule 1.
        var conflict = snap(
                pol(1, "srcintf \"port1\" \"port2\"", "dstintf \"port3\"",
                        "srcaddr \"NET-CLIENT\"", "dstaddr \"NET-SERVER\"", "service \"ALL\"", "action deny")
                + pol(2, "srcintf \"port1\"", "dstintf \"port3\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertNotShadowedAndSafe(conflict, 0);
        assertCovered(conflict, 1, 0, "CONFLICT");

        // Egress superset: dstintf "port3" "port4" covers dstintf "port4".
        var dstConflict = snap(
                pol(1, "srcintf \"port1\"", "dstintf \"port3\" \"port4\"",
                        "srcaddr \"NET-CLIENT\"", "dstaddr \"NET-SERVER\"", "service \"HTTPS\"", "action deny")
                + pol(2, "srcintf \"port1\"", "dstintf \"port4\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertCovered(dstConflict, 1, 0, "CONFLICT");

        // Superset interfaces whose address/service selectors do not contain the later rule release it to NOT_SHADOWED.
        var selectorMiss = snap(
                pol(1, "srcintf \"port1\" \"port2\"", "dstintf \"port3\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"NET-SERVER\"", "service \"HTTPS\"", "action deny")
                + pol(2, "srcintf \"port1\"", "dstintf \"port3\"",
                        "srcaddr \"NET-CLIENT\"", "dstaddr \"NET-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertNotShadowedAndSafe(selectorMiss, 1);
    }

    @Test
    void simultaneousBothSidesSupersetAndAnyCombinedWithInterfaceSupersetCover() {
        // Superset on both srcintf and dstintf simultaneously: "port1" "port2" -> "port3" "port4" covers "port2" -> "port4".
        var bothRedundant = snap(
                pol(1, "srcintf \"port1\" \"port2\"", "dstintf \"port3\" \"port4\"",
                        "srcaddr \"NET-CLIENT\"", "dstaddr \"NET-SERVER\"", "service \"ALL\"", "action accept")
                + pol(2, "srcintf \"port2\"", "dstintf \"port4\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertCovered(bothRedundant, 1, 0, "REDUNDANT");

        var bothConflict = snap(
                pol(1, "srcintf \"port1\" \"port2\"", "dstintf \"port3\" \"port4\"",
                        "srcaddr \"all\"", "dstaddr \"all\"", "service \"ALL\"", "action deny")
                + pol(2, "srcintf \"port2\"", "dstintf \"port4\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"SSH\"", "action accept"));
        assertCovered(bothConflict, 1, 0, "CONFLICT");

        // Earlier srcintf "any" combined with dstintf superset ("port3" "port4" covering "port4").
        var srcAnyDstSuperset = snap(
                pol(1, "srcintf \"any\"", "dstintf \"port3\" \"port4\"",
                        "srcaddr \"NET-CLIENT\"", "dstaddr \"NET-SERVER\"", "service \"ALL\"", "action accept")
                + pol(2, "srcintf \"port1\" \"port2\"", "dstintf \"port4\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertCovered(srcAnyDstSuperset, 1, 0, "REDUNDANT");

        // Earlier srcintf superset ("port1" "port2" covering "port1") combined with dstintf "any".
        var srcSupersetDstAny = snap(
                pol(1, "srcintf \"port1\" \"port2\"", "dstintf \"any\"",
                        "srcaddr \"all\"", "dstaddr \"all\"", "service \"ALL\"", "action deny")
                + pol(2, "srcintf \"port1\"", "dstintf \"port3\" \"port4\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertCovered(srcSupersetDstAny, 1, 0, "CONFLICT");
    }

    @Test
    void narrowerOrPartiallyOverlappingInterfacesCannotCoverAndReleaseToNotShadowed() {
        // Earlier narrower srcintf: "port1" -> "port3" before "port1" "port2" -> "port3".
        var narrowerSrc = snap(
                pol(1, "srcintf \"port1\"", "dstintf \"port3\"", "action deny")
                + pol(2, "srcintf \"port1\" \"port2\"", "dstintf \"port3\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertNotShadowedAndSafe(narrowerSrc, 1);

        // Earlier narrower dstintf: "port1" -> "port3" before "port1" -> "port3" "port4".
        var narrowerDst = snap(
                pol(1, "srcintf \"port1\"", "dstintf \"port3\"", "action deny")
                + pol(2, "srcintf \"port1\"", "dstintf \"port3\" \"port4\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertNotShadowedAndSafe(narrowerDst, 1);

        // Partial overlap on srcintf: "port1" "port2" -> "port4" before "port2" "port3" -> "port4".
        var partialOverlap = snap(
                pol(1, "srcintf \"port1\" \"port2\"", "dstintf \"port4\"", "action deny")
                + pol(2, "srcintf \"port2\" \"port3\"", "dstintf \"port4\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertNotShadowedAndSafe(partialOverlap, 1);

        // Superset on srcintf ("port1" "port2" holds "port1") but partial overlap on dstintf ("port3" vs "port3" "port4").
        var mixedSides = snap(
                pol(1, "srcintf \"port1\" \"port2\"", "dstintf \"port3\"", "action deny")
                + pol(2, "srcintf \"port1\"", "dstintf \"port3\" \"port4\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertNotShadowedAndSafe(mixedSides, 1);

        // Non-covering overlap (rule 1) does not stop a subsequent superset rule (rule 2) from covering rule 3.
        var passedOverThenCovered = snap(
                pol(1, "srcintf \"port2\" \"port3\"", "dstintf \"port4\"", "action deny")
                + pol(2, "srcintf \"port1\" \"port2\" \"port3\"", "dstintf \"port4\"", "action accept")
                + pol(3, "srcintf \"port1\" \"port2\"", "dstintf \"port4\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertNotShadowedAndSafe(passedOverThenCovered, 1);
        assertCovered(passedOverThenCovered, 2, 1, "REDUNDANT");
    }

    @Test
    void namedInterfaceListBeforeAnyStaysFailClosedUnknown() {
        // Earlier named list ("port1" "port2" "port3") before later srcintf "any" -> UNKNOWN "zone or install-on coverage is unproven".
        var srcNamedBeforeAny = snap(
                pol(1, "srcintf \"port1\" \"port2\" \"port3\"", "dstintf \"port4\"", "action accept")
                + pol(2, "srcintf \"any\"", "dstintf \"port4\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertUnknownReason(srcNamedBeforeAny, 1, UNPROVEN);

        // Even naming every interface configured in the VDOM ("port1".."port4") before dstintf "any" stays fail-closed,
        // including when a ZoneUniverse is passed to the package-private overload (FortiGate keeps ZoneUniverse.UNKNOWN).
        var allFourNamedBeforeAny = snap(
                pol(1, "srcintf \"port1\"", "dstintf \"port1\" \"port2\" \"port3\" \"port4\"", "action deny")
                + pol(2, "srcintf \"port1\"", "dstintf \"any\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertUnknownReason(allFourNamedBeforeAny, 1, UNPROVEN);
        Rule second = policyRules(allFourNamedBeforeAny).get(1);
        PolicyHygiene.Assessment withPanUniverse = PolicyHygiene.analyze(
                allFourNamedBeforeAny, 90, NOW, PolicyHygiene.TOTAL_WORK, allFourNamedBeforeAny.objects(),
                ZoneUniverse.of(List.of(Set.of("port1", "port2", "port3", "port4")))).rules().get(second.id());
        assertEquals("UNKNOWN", withPanUniverse.shadowStatus());
        assertEquals(UNPROVEN, withPanUniverse.shadowReason());
        assertNull(withPanUniverse.coveringRuleId());
    }

    @Test
    void compatibleExactKeyCoverStillWinsOverEarlierSupersetCover() {
        // Rule 1 (superset, deny) and rule 2 (exact key, accept) both cover rule 3; exact-key rule 2 wins for rule 3,
        // while rule 2 itself is covered as CONFLICT by the earlier superset rule 1.
        var snapshot = snap(
                pol(1, "srcintf \"port1\" \"port2\"", "dstintf \"port3\"", "action deny")
                + pol(2, "srcintf \"port1\"", "dstintf \"port3\"", "action accept")
                + pol(3, "srcintf \"port1\"", "dstintf \"port3\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        assertCovered(snapshot, 2, 1, "REDUNDANT");
        assertCovered(snapshot, 1, 0, "CONFLICT");
    }

    @Test
    void systemZoneAndSdwanZonePlacementsStayUnknownAndNeverProduceFalseCoverOrFalseNotShadowed() {
        // 1. Even after an earlier any -> any all-accept rule, policies naming a system zone or SD-WAN zone stay
        //    UNKNOWN "zone membership not evaluated" (A0-FG-9).
        var afterUniversalAny = snap(ZONES,
                pol(1, "srcintf \"any\"", "dstintf \"any\"", "action accept")
                + pol(2, "srcintf \"port1\"", "dstintf \"ZONE-DMZ-01\"", "service \"HTTPS\"", "action deny")
                + pol(3, "srcintf \"SDWAN-WAN-01\"", "dstintf \"port4\"", "service \"HTTPS\"", "action deny"));
        assertUnknownReason(afterUniversalAny, 1, ZONE_NOT_EVALUATED);
        assertUnknownReason(afterUniversalAny, 2, ZONE_NOT_EVALUATED);

        // 2. An earlier system-zone policy (ZONE-DMZ-01 has member port2) never covers a later overlapping policy on
        //    port2 or port4, and never lets it become NOT_SHADOWED by comparing "ZONE-DMZ-01" != "port2"/"port4".
        var earlierSystemZone = snap(ZONES,
                pol(1, "srcintf \"port1\"", "dstintf \"ZONE-DMZ-01\"",
                        "srcaddr \"NET-CLIENT\"", "dstaddr \"NET-SERVER\"", "service \"HTTPS\"", "action deny")
                + pol(2, "srcintf \"port1\"", "dstintf \"port2\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept")
                + pol(3, "srcintf \"port1\"", "dstintf \"port4\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-SERVER\"", "service \"HTTPS\"", "action accept"));
        List<Rule> sysZoneRules = policyRules(earlierSystemZone);
        Map<String, PolicyHygiene.Assessment> sysZoneGot = analyze(earlierSystemZone);
        assertUnknownReason(earlierSystemZone, 0, ZONE_NOT_EVALUATED);
        assertUnknownReason(earlierSystemZone, 1, EARLIER_UNSUPPORTED);
        assertEquals(sysZoneRules.get(0).id(), sysZoneGot.get(sysZoneRules.get(1).id()).blockingRuleId());
        assertUnknownReason(earlierSystemZone, 2, EARLIER_UNSUPPORTED);
        assertEquals(sysZoneRules.get(0).id(), sysZoneGot.get(sysZoneRules.get(2).id()).blockingRuleId());

        // 3. Same guarantee for an earlier SD-WAN zone policy (SDWAN-WAN-01 has member port3), including all-any selectors.
        var earlierSdwanZone = snap(ZONES,
                pol(1, "srcintf \"SDWAN-WAN-01\"", "dstintf \"port4\"", "action accept")
                + pol(2, "srcintf \"port3\"", "dstintf \"port4\"",
                        "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-OTHER\"", "service \"HTTPS\"", "action accept"));
        List<Rule> sdwanRules = policyRules(earlierSdwanZone);
        Map<String, PolicyHygiene.Assessment> sdwanGot = analyze(earlierSdwanZone);
        assertUnknownReason(earlierSdwanZone, 0, ZONE_NOT_EVALUATED);
        assertUnknownReason(earlierSdwanZone, 1, EARLIER_UNSUPPORTED);
        assertEquals(sdwanRules.get(0).id(), sdwanGot.get(sdwanRules.get(1).id()).blockingRuleId());
    }
}
