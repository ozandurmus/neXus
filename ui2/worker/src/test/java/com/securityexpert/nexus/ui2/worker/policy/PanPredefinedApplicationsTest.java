package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The predefined App-ID catalogue reply reduced to standard ports (PO approval 2026-11-14). Synthetic reply shaped as
 * the PAN-OS XML API "action=get" result; masked application names only.
 */
class PanPredefinedApplicationsTest {
    static Map<String, List<String>> parse(String xml) {
        return PanPredefinedApplications.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void keepsOnlyDefaultPortsAndIdentification() {
        var catalogue = parse("""
            <response status="success"><result total-count="3" count="3"><application>
              <entry name="APP-ALPHA-01" id="1"><category>networking</category><default><port><member>tcp/80,443</member><member>udp/53</member></port></default>
                <risk>4</risk><description>secret text</description><signature><entry name="sig"><default><port><member>tcp/9</member></port></default></entry></signature></entry>
              <entry name="APP-BRAVO-02"><default><ident-by-ip-protocol>1</ident-by-ip-protocol></default></entry>
              <entry name="APP-CHARLIE-03"><default><ident-by-icmp-type><type>8</type><code>0</code></ident-by-icmp-type></default></entry>
              <entry name="APP-DELTA-04"><default><port><member>tcp/dynamic</member></port></default></entry>
              <entry name="APP-ECHO-05"><category>x</category></entry>
            </application></result></response>""");
        assertEquals(List.of("default/port: tcp/80,443", "default/port: udp/53"), catalogue.get("APP-ALPHA-01"));
        assertEquals(List.of("default/ident-by-ip-protocol: 1"), catalogue.get("APP-BRAVO-02"));
        assertEquals(List.of("default/ident-by-icmp-type: type 8 code 0"), catalogue.get("APP-CHARLIE-03"));
        assertEquals(List.of("default/port: UNPARSED"), catalogue.get("APP-DELTA-04"));
        assertEquals(List.of(), catalogue.get("APP-ECHO-05"));
        assertFalse(catalogue.toString().contains("secret"));
    }

    @Test void anythingElseGivesNoCatalogue() {
        for (String xml : List.of(
                "<response status=\"error\"><msg>x</msg></response>",
                "<response status=\"success\"><result><application><entry name=\"bad name\"/></application></result></response>",
                "<response status=\"success\"><result><application><entry name=\"A\"/><entry name=\"A\"/></application></result></response>",
                "<response status=\"success\"><result><application><entry name=\"A\">",
                "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/hosts\">]><response status=\"success\">&e;</response>"))
            assertThrows(PanPredefinedApplications.Unreadable.class, () -> parse(xml), xml);
    }

    @Test void theCapStopsTheRead() {
        var in = PanPredefinedApplications.capped(new ByteArrayInputStream(new byte[100]), 10);
        assertThrows(IOException.class, () -> in.readAllBytes());
    }
}
