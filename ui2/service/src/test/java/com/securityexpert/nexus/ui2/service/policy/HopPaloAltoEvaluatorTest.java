package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PanoramaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Golden tests, path analysis A2, Palo Alto: target / negate per device (G8, A0 §4d), application-default (D-5), default
 * rules (§5, A0 §4c), the local mapper's NAT and default sections (G3), and the §5 order -- NAT first match, then security
 * on the pre-NAT addresses with the post-NAT egress zone. Snapshots are built by the real mapper from synthetic XML
 * (documentation addresses, masked names; the serials are synthetic placeholders).
 */
class HopPaloAltoEvaluatorTest {
    static final String SERIAL = "SYNTHETIC-SERIAL-0004", OTHER_SERIAL = "SYNTHETIC-SERIAL-0006";
    static final Device TANGO = new Device("device-tango", "vsys1", SERIAL);
    static final Tuple CLIENT_TO_PUBLIC = new Tuple("198.51.100.20", "203.0.113.10", "tcp", 443);
    static final String SHARED = "<address><entry name='OBJ-WEB-01'><ip-netmask>192.0.2.10</ip-netmask></entry>"
        + "<entry name='OBJ-PUBLIC-01'><ip-netmask>203.0.113.10</ip-netmask></entry>"
        + "<entry name='OBJ-SNAT-01'><ip-netmask>203.0.113.1</ip-netmask></entry>"
        + "<entry name='OBJ-CLIENTS-01'><ip-netmask>198.51.100.0/24</ip-netmask></entry></address>"
        + "<service><entry name='SVC-HTTPS'><protocol><tcp><port>443</port></tcp></protocol></entry></service>"
        + "<application><entry name='APP-SYNTH-01'><default><port><member>tcp/8443,9443</member></port></default></entry></application>";

    static String security(String name, String from, String to, String destination, String service, String application, String action, String target) {
        return "<entry name='" + name + "' uuid='uuid-" + name + "'><from><member>" + from + "</member></from><to><member>" + to + "</member></to>"
            + "<source><member>any</member></source><destination><member>" + destination + "</member></destination>"
            + "<service><member>" + service + "</member></service><application><member>" + application + "</member></application>"
            + target + "<action>" + action + "</action></entry>";
    }
    static String allow(String name, String target) { return security(name, "any", "any", "any", "any", "any", "allow", target); }
    static String devices(String negate, String... serials) {
        StringBuilder xml = new StringBuilder("<target>");
        if (serials.length > 0) {
            xml.append("<devices>");
            for (String serial : serials) xml.append("<entry name='").append(serial).append("'/>");
            xml.append("</devices>");
        }
        if (negate != null) xml.append("<negate>").append(negate).append("</negate>");
        return xml.append("</target>").toString();
    }
    static final String DNAT = "<entry name='NAT-DNAT-01' uuid='uuid-dnat'><from><member>ZONE-UNTRUST</member></from>"
        + "<to><member>ZONE-UNTRUST</member></to><source><member>any</member></source><destination><member>OBJ-PUBLIC-01</member></destination>"
        + "<service>any</service><destination-translation><translated-address>OBJ-WEB-01</translated-address>"
        + "<translated-port>8443</translated-port></destination-translation></entry>";

    static PolicySnapshot panorama(String preSecurity, String preNat, String postSecurity, String postNat, String syncStatus) {
        String xml = "<config><shared>" + SHARED + "</shared><devices><entry name='localhost.localdomain'><device-group><entry name='DG-TANGO'>"
            + "<pre-rulebase><security><rules>" + preSecurity + "</rules></security><nat><rules>" + preNat + "</rules></nat></pre-rulebase>"
            + "<post-rulebase><security><rules>" + postSecurity + "</rules></security><nat><rules>" + postNat + "</rules></nat></post-rulebase>"
            + "</entry></device-group></entry></devices></config>";
        var metadata = new Metadata("pan-policy-01", "panorama-1", "MGR-BRAVO-01", "PAN", "dg-1", "DG-TANGO", "DG-TANGO",
            "2026-11-04T08:00:00Z", "", List.of(new Target("device-tango", "FW-TANGO-04", "vsys1", syncStatus)));
        return new PanoramaPolicyMapper().map(metadata, xml, "DG-TANGO", Map.of(), false);
    }
    static PolicySnapshot panorama(String preSecurity, String preNat) { return panorama(preSecurity, preNat, "", "", "IN_SYNC"); }
    static PolicySnapshot local(String security, String nat, String defaults) {
        String xml = "<response status='success'><result><config><devices><entry name='localhost.localdomain'><vsys><entry name='vsys1'>" + SHARED
            + "<rulebase><security><rules>" + security + "</rules></security><nat><rules>" + nat + "</rules></nat>"
            + "<default-security-rules><rules>" + defaults + "</rules></default-security-rules></rulebase></entry></vsys></entry></devices>"
            + "</config></result></response>";
        return new PanoramaPolicyMapper().local("device-tango", "2026-11-04T08:00:00Z", "artifact-1", xml).get(0);
    }
    static Verdict decide(PolicySnapshot snapshot, Device device) {
        return HopPolicyEvaluator.security(snapshot, device, Binding.NONE, CLIENT_TO_PUBLIC, "ZONE-UNTRUST", "ZONE-DMZ");
    }

    // ---- G8 target / negate -------------------------------------------------------------------------------------------

    @Test void targetSelectorsAndNegateArePerDevice() {
        String deny = security("RULE-DENY-01", "any", "any", "any", "any", "any", "deny", "");
        record Case(String target, String expected) {}
        // No target, or nothing selected without negate: every firewall. Listed: only those. Negate: all except those;
        // negate with nothing selected: no firewall.
        for (var c : List.of(new Case("", "RULE-TARGET"), new Case(devices("no"), "RULE-TARGET"),
                new Case(devices(null, SERIAL), "RULE-TARGET"), new Case(devices("no", OTHER_SERIAL), "RULE-DENY-01"),
                new Case(devices("yes", OTHER_SERIAL), "RULE-TARGET"), new Case(devices("yes", SERIAL), "RULE-DENY-01"),
                new Case(devices("yes"), "RULE-DENY-01"))) {
            var verdict = decide(panorama(allow("RULE-TARGET", c.target()) + deny, ""), TANGO);
            String decided = verdict.decidingRule() == null ? null : (String) verdict.decidingRule().get("name");
            assertEquals(c.expected(), decided, c.target());
            assertEquals(decided.equals("RULE-TARGET") ? ALLOWED : DENIED, verdict.verdict(), c.target());
        }
    }

    @Test void tagsVsysUnknownSerialAndUnrecognizedTargetsAreUnproven() {
        String deny = security("RULE-DENY-01", "any", "any", "any", "any", "any", "deny", "");
        for (String target : List.of("<target><tags><member>TAG-ALPHA</member></tags></target>",
                "<target><devices><entry name='" + SERIAL + "'><vsys><entry name='vsys1'/></vsys></entry></devices></target>",
                "<target><unexpected/></target>", "<target><negate>maybe</negate></target>")) {
            var verdict = decide(panorama(allow("RULE-TARGET", target) + deny, ""), TANGO);
            assertEquals(UNKNOWN, verdict.verdict(), target); assertEquals(EARLIER_RULE_UNRESOLVED, verdict.reason());
            assertEquals(PAN_TARGET_UNPROVEN, verdict.undecidableRules().get(0).get("pathReason"));
        }
        var unknownSerial = decide(panorama(allow("RULE-TARGET", devices(null, SERIAL)) + deny, ""), new Device("device-tango", "vsys1", null));
        assertEquals(UNKNOWN, unknownSerial.verdict());
    }

    @Test void outOfSyncAndUnknownSyncAndForeignContext() {
        var outOfSync = decide(panorama(allow("RULE-ALLOW-01", ""), "", "", "", "OUT_OF_SYNC"), TANGO);
        assertEquals(UNKNOWN, outOfSync.verdict()); assertEquals(STALE_OR_OUT_OF_SYNC_POLICY, outOfSync.reason());
        var unknownSync = decide(panorama(allow("RULE-ALLOW-01", ""), "", "", "", "UNKNOWN"), TANGO);
        assertEquals(ALLOWED, unknownSync.verdict()); assertFalse(unknownSync.plain());
        assertEquals(List.of(POLICY_SYNC_UNKNOWN), unknownSync.caveats());
        assertEquals(TARGET_CONTEXT_UNPROVEN, decide(panorama(allow("RULE-ALLOW-01", ""), ""), new Device("device-tango", "vsys2", SERIAL)).reason());
    }

    // ---- D-5 application-default --------------------------------------------------------------------------------------

    @Test void applicationDefaultMatchesStoredDefaultPortsAndIsNeverPlainAllowed() {
        String appDefault = security("RULE-APPDEF-01", "any", "any", "any", "application-default", "APP-SYNTH-01", "allow", "");
        String deny = security("RULE-DENY-01", "any", "any", "any", "any", "any", "deny", "");
        var snapshot = panorama(appDefault + deny, "");
        var onDefault = HopPolicyEvaluator.security(snapshot, TANGO, Binding.NONE, CLIENT_TO_PUBLIC.with("198.51.100.20", "203.0.113.10", 9443),
            "ZONE-UNTRUST", "ZONE-DMZ");
        assertEquals(ALLOWED, onDefault.verdict()); assertEquals("RULE-APPDEF-01", onDefault.decidingRule().get("name"));
        assertFalse(onDefault.plain()); assertEquals(List.of(APP_ID_ASSUMED), onDefault.caveats());
        var offDefault = decide(snapshot, TANGO);
        assertEquals(DENIED, offDefault.verdict(), "port 443 is not a default port of the application");
        assertTrue(offDefault.caveats().isEmpty());
        // A predefined application (not in the stored configuration) has no stored default ports.
        var predefined = decide(panorama(security("RULE-APPDEF-02", "any", "any", "any", "application-default", "web-browsing", "allow", "") + deny, ""), TANGO);
        assertEquals(UNKNOWN, predefined.verdict());
        assertEquals(APP_DEFAULT_PORTS_NOT_STORED, predefined.undecidableRules().get(0).get("pathReason"));
        // application any with application-default: the interaction is UNKNOWN (A0 §6).
        var anyApp = decide(panorama(security("RULE-APPDEF-03", "any", "any", "any", "application-default", "any", "allow", "") + deny, ""), TANGO);
        assertEquals(UNKNOWN, anyApp.verdict());
    }

    // ---- default rules ------------------------------------------------------------------------------------------------

    @Test void localDefaultOverrideDecidesAndAbsentOrPanoramaDefaultsStayUnknown() {
        String nonMatching = security("RULE-OTHER-01", "ZONE-ALPHA", "ZONE-BRAVO", "any", "any", "any", "allow", "");
        var override = local(nonMatching, "", "<entry name='interzone-default'><action>allow</action></entry>");
        var decided = decide(override, new Device("device-tango", "vsys1", null));
        assertEquals(ALLOWED, decided.verdict()); assertEquals(PAN_DEFAULT_RULE_OVERRIDE, decided.reason());
        assertEquals("interzone-default", decided.decidingRule().get("name"));
        var intrazone = HopPolicyEvaluator.security(override, new Device("device-tango", "vsys1", null), Binding.NONE, CLIENT_TO_PUBLIC, "ZONE-DMZ", "ZONE-DMZ");
        assertEquals(UNKNOWN, intrazone.verdict()); assertEquals(PAN_DEFAULT_RULE_NOT_COLLECTED, intrazone.reason(), "never an assumed vendor default");
        var panoramaDefault = decide(panorama(nonMatching, ""), TANGO);
        assertEquals(UNKNOWN, panoramaDefault.verdict()); assertEquals(PAN_DEFAULT_RULE_LEVELS_NOT_COLLECTED, panoramaDefault.reason());
    }

    @Test void panoramaPostRuleDecisionCarriesTheLocalRulesCaveat() {
        var post = decide(panorama("", "", allow("RULE-POST-01", ""), "", "IN_SYNC"), TANGO);
        assertEquals(ALLOWED, post.verdict()); assertEquals(List.of(PAN_LOCAL_RULES_NOT_COLLECTED), post.caveats());
        var pre = decide(panorama(allow("RULE-PRE-01", ""), ""), TANGO);
        assertTrue(pre.plain());
    }

    // ---- NAT then security (§5) ---------------------------------------------------------------------------------------

    @Test void natThenSecurityOnPreNatAddressesWithThePostNatEgressZone() {
        // The security rule names the public (pre-NAT) address and the DMZ zone the translated destination routes to.
        String byPreNat = security("RULE-PUBLISH-01", "ZONE-UNTRUST", "ZONE-DMZ", "OBJ-PUBLIC-01", "SVC-HTTPS", "any", "allow", "");
        String byPostNat = security("RULE-WEB-01", "ZONE-UNTRUST", "ZONE-DMZ", "OBJ-WEB-01", "any", "any", "allow", "");
        String deny = security("RULE-DENY-01", "any", "any", "any", "any", "any", "deny", "");
        var snapshot = panorama(byPostNat + byPreNat + deny, DNAT);
        var hop = paloAlto(snapshot, TANGO, CLIENT_TO_PUBLIC, "ZONE-UNTRUST", "ZONE-UNTRUST", null,
            destination -> destination.equals("192.0.2.10") ? "ZONE-DMZ" : null);
        assertEquals(ALLOWED, hop.verdict()); assertEquals("ZONE-DMZ", hop.egressZone());
        assertEquals(new Tuple("198.51.100.20", "192.0.2.10", "tcp", 8443), hop.nat().translated());
        assertEquals("RULE-PUBLISH-01", hop.preNatAccess().decidingRule().get("name"), "the post-NAT address never matches security");
        assertTrue(hop.notes().contains(PAN_SECURITY_PRE_NAT_PORT));
        var unrouted = paloAlto(snapshot, TANGO, CLIENT_TO_PUBLIC, "ZONE-UNTRUST", "ZONE-UNTRUST", null, destination -> null);
        assertEquals(UNKNOWN, unrouted.verdict()); assertEquals(POST_NAT_EGRESS_ZONE_UNKNOWN, unrouted.reason());
        // Without a translation the provisional zone is the egress zone.
        var direct = paloAlto(snapshot, TANGO, new Tuple("198.51.100.20", "192.0.2.10", "tcp", 443), "ZONE-UNTRUST", "ZONE-DMZ", null, destination -> null);
        assertEquals(ALLOWED, direct.verdict()); assertEquals("RULE-WEB-01", direct.preNatAccess().decidingRule().get("name"));
    }

    @Test void natFirstMatchOrderZonesTargetsAndUnknowns() {
        String snat = "<entry name='NAT-SNAT-01'><from><member>ZONE-UNTRUST</member></from><to><member>any</member></to>"
            + "<source><member>OBJ-CLIENTS-01</member></source><destination><member>any</member></destination><service>any</service>"
            + "<source-translation><dynamic-ip-and-port><translated-address><member>OBJ-SNAT-01</member></translated-address>"
            + "</dynamic-ip-and-port></source-translation></entry>";
        var preFirst = panorama("", DNAT, "", snat, "IN_SYNC");
        var first = prepare(preFirst, TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, "ZONE-UNTRUST", "ZONE-UNTRUST", null);
        assertEquals("NAT-DNAT-01", first.rules().get(0).get("name"), "pre NAT before post NAT; first match ends");
        var postOnly = prepare(preFirst, TANGO, Binding.NONE).nat(new Tuple("198.51.100.20", "192.0.2.99", "tcp", 443), "ZONE-UNTRUST", "ZONE-DMZ", null);
        assertEquals(HopNatEvaluator.TRANSLATED, postOnly.status()); assertEquals("203.0.113.1", postOnly.translated().source());
        assertEquals(List.of(PAN_LOCAL_RULES_NOT_COLLECTED), postOnly.caveats());
        // Wrong ingress zone: the destination NAT does not match.
        var otherZone = prepare(panorama("", DNAT), TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, "ZONE-DMZ", "ZONE-UNTRUST", null);
        assertEquals(HopNatEvaluator.NO_TRANSLATION, otherZone.status());
        // A NAT rule targeted at another firewall is skipped for this one; with an unknown serial it is undecidable.
        String targeted = DNAT.replace("<service>any</service>", "<service>any</service>" + devices(null, OTHER_SERIAL));
        assertEquals(HopNatEvaluator.NO_TRANSLATION, prepare(panorama("", targeted), TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, "ZONE-UNTRUST", "ZONE-UNTRUST", null).status());
        var unproven = prepare(panorama("", targeted), new Device("device-tango", "vsys1", null), Binding.NONE).nat(CLIENT_TO_PUBLIC, "ZONE-UNTRUST", "ZONE-UNTRUST", null);
        assertEquals(HopNatEvaluator.UNKNOWN, unproven.status()); assertEquals(PAN_TARGET_UNPROVEN, unproven.undecidableRules().get(0).get("pathReason"));
        // No provisional egress zone: a rule with a specific to-zone cannot be decided.
        assertEquals(HopNatEvaluator.UNKNOWN, prepare(panorama("", DNAT), TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, "ZONE-UNTRUST", null, null).status());
        // Dynamic destination translation is not expressible.
        String dynamic = DNAT.replace("<destination-translation><translated-address>OBJ-WEB-01</translated-address><translated-port>8443</translated-port></destination-translation>",
            "<dynamic-destination-translation><translated-address>OBJ-WEB-01</translated-address></dynamic-destination-translation>");
        assertEquals(HopNatEvaluator.NAT_TRANSLATION_NOT_EXPRESSIBLE,
            prepare(panorama("", dynamic), TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, "ZONE-UNTRUST", "ZONE-UNTRUST", null).reason());
        // A bi-directional static rule whose implicit reverse could match the destination is undecidable.
        String bidirectional = "<entry name='NAT-STATIC-01'><from><member>ZONE-DMZ</member></from><to><member>ZONE-UNTRUST</member></to>"
            + "<source><member>OBJ-WEB-01</member></source><destination><member>any</member></destination><service>any</service>"
            + "<source-translation><static-ip><translated-address>OBJ-PUBLIC-01</translated-address><bi-directional>yes</bi-directional>"
            + "</static-ip></source-translation></entry>";
        var reverse = prepare(panorama("", bidirectional), TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, "ZONE-UNTRUST", "ZONE-UNTRUST", null);
        assertEquals(HopNatEvaluator.UNKNOWN, reverse.status());
        assertEquals(HopNatEvaluator.PAN_NAT_BIDIRECTIONAL_REVERSE, reverse.undecidableRules().get(0).get("pathReason"));
    }

    @Test void localFirewallNatAndSecurityFromTheStoredConfiguration() {
        String publish = security("RULE-PUBLISH-01", "ZONE-UNTRUST", "ZONE-DMZ", "OBJ-PUBLIC-01", "any", "any", "allow", "");
        var snapshot = local(publish, DNAT, "<entry name='interzone-default'><action>deny</action></entry>");
        var device = new Device("device-tango", "vsys1", null);
        var hop = paloAlto(snapshot, device, CLIENT_TO_PUBLIC, "ZONE-UNTRUST", "ZONE-UNTRUST", null, destination -> "ZONE-DMZ");
        assertEquals(ALLOWED, hop.verdict()); assertTrue(hop.plain(), "a local snapshot holds every level: no local-rules caveat");
        assertEquals(PAN_LOCAL_NAT, hop.nat().rules().get(0).get("sectionName"));
        var blocked = paloAlto(snapshot, device, new Tuple("198.51.100.20", "203.0.113.99", "tcp", 443), "ZONE-UNTRUST", "ZONE-DMZ", null, destination -> null);
        assertEquals(DENIED, blocked.verdict()); assertEquals(PAN_DEFAULT_RULE_OVERRIDE, blocked.reason());
    }
}
