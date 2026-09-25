package com.securityexpert.nexus.ui2.worker.backup.https;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Pulse Secure page and export parsing (synthetic values; the trail's form and field names). */
class PulseSecureParsersTest {

    @Test
    void hiddenInputsInEitherAttributeOrder() {
        assertEquals(Optional.of("abc123"), PulseSecureExecutor.hidden("<input type=\"hidden\" name=\"xsauth\" value=\"abc123\"/>", "xsauth"));
        assertEquals(Optional.of("161;276;x"), PulseSecureExecutor.hidden("<input value=\"161;276;x\" type='hidden' name='FormDataStr'>", "FormDataStr"));
        assertEquals(Optional.empty(), PulseSecureExecutor.hidden("<html>no form</html>", "xsauth"));
    }

    @Test
    void locationsStayOnTheAppliance() {
        assertEquals("/dana-admin/misc/admin.cgi", PulseSecureExecutor.relative("https://192.0.2.5/dana-admin/misc/admin.cgi"));
        assertEquals("/x", PulseSecureExecutor.relative("/x"));
        assertNull(PulseSecureExecutor.relative("javascript:alert(1)"));
    }

    @Test
    void sysinfoIdentity() {
        var id = PulseSecureExecutor.identity("<td>Hostname</td><td>VPN-TANGO-01</td><td>Model</td><td>PSA-5000</td>"
                + "<td>Software version</td><td>9.1R18.2 (build 26011)</td>");
        assertEquals(Optional.of("VPN-TANGO-01"), id.name());
        assertEquals(Optional.of("PSA-5000"), id.model());
        assertEquals(Optional.of("9.1R18.2 (build 26011)"), id.version());
    }

    @Test
    void networkExport() {
        var net = PulseNetwork.parse("""
                <configuration><network>
                  <internal-port><settings><ip-address>192.0.2.10</ip-address><netmask>255.255.255.0</netmask></settings></internal-port>
                  <external-port><settings><ip-address>198.51.100.10</ip-address><netmask>255.255.255.252</netmask></settings></external-port>
                  <routes><route><destination>0.0.0.0</destination><netmask>0.0.0.0</netmask><gateway>192.0.2.1</gateway><interface>internal</interface></route></routes>
                </network></configuration>""");
        assertEquals(2, net.interfaces().size());
        assertEquals("192.0.2.10/24", net.interfaces().get(0).addresses().get(0).address());
        assertEquals(1, net.routes().size());
        assertEquals("0.0.0.0/0", net.routes().get(0).destination());
        assertEquals(Optional.of("192.0.2.1"), net.routes().get(0).nextHop());
    }
}
