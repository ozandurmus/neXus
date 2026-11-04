package com.securityexpert.nexus.ui2.service.policy;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

import com.securityexpert.nexus.ui2.policy.PanoramaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import java.util.*;
import org.junit.jupiter.api.Test;

/** PAN-OS built-in services service-http / service-https resolve to their documented ports unless the config defines them. */
class PolicyHygienePanPredefinedServiceTest {
    private static final Metadata META = new Metadata("policy-1", "manager-1", "Synthetic manager", "PAN", "container-1", "Synthetic group",
            "Synthetic policy", "2026-11-04T06:00:00Z", "artifact-1", List.of(new Target("device-1", "FW-TANGO-04", "vsys1", "UNKNOWN")));

    private static PolicySnapshot map(String extraObjects, String firstService, String secondService) {
        String xml = "<config><devices><entry name='localhost.localdomain'><device-group><entry name='Synthetic group'>"
                + "<address><entry name='Synthetic host'><ip-netmask>192.0.2.8/32</ip-netmask></entry></address>"
                + "<service><entry name='Synthetic web'><protocol><tcp><port>8080</port></tcp></protocol></entry>" + extraObjects + "</service>"
                + "<pre-rulebase><security><rules>"
                + "<entry name='Synthetic broad' uuid='uuid-r1'>" + rule("any", firstService) + "<action>allow</action></entry>"
                + "<entry name='Synthetic narrow' uuid='uuid-r2'>" + rule("Synthetic host", secondService) + "<action>allow</action></entry>"
                + "</rules></security></pre-rulebase></entry></device-group></entry></devices></config>";
        return new PanoramaPolicyMapper().map(META, xml, "Synthetic group", Map.of(), false);
    }
    private static String rule(String source, String service) {
        return "<from><member>any</member></from><to><member>any</member></to><source><member>" + source + "</member></source>"
                + "<destination><member>any</member></destination><service><member>" + service + "</member></service>"
                + "<application><member>any</member></application>";
    }
    private static Map<String, PolicyHygiene.Assessment> byName(PolicySnapshot snapshot) {
        var result = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK).rules();
        Map<String, PolicyHygiene.Assessment> out = new HashMap<>();
        for (var section : snapshot.sections()) for (var r : section.rules()) out.put(r.name(), result.get(r.id()));
        return out;
    }

    @Test void builtInHttpServiceCoversTcp8080() {
        var result = byName(map("", "service-http", "Synthetic web"));
        assertEquals("NOT_SHADOWED", result.get("Synthetic broad").shadowStatus());
        assertEquals("REDUNDANT", result.get("Synthetic narrow").shadowStatus());
    }
    @Test void builtInHttpsServiceDoesNotCoverTcp8080() {
        var result = byName(map("", "service-https", "Synthetic web"));
        assertEquals("NOT_SHADOWED", result.get("Synthetic narrow").shadowStatus());
    }
    @Test void aConfiguredObjectOfTheSameNameWins() {
        var snapshot = map("<entry name='service-http'><protocol><tcp><port>9999</port></tcp></protocol></entry>", "service-http", "Synthetic web");
        var http = snapshot.objects().values().stream().filter(o -> o.name().equals("service-http")).findFirst().orElseThrow();
        assertEquals(List.of("protocol/tcp/port: 9999"), http.values());
        assertEquals("NOT_SHADOWED", byName(snapshot).get("Synthetic narrow").shadowStatus());
    }
}
