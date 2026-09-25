package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoSystemInfoParser;

/** Panorama inventory from show system info (synthetic values, the firewall read's XML shape). */
class PanoramaInventoryTest {

    @Test
    void managementInterfaceAndDefaultRoute() {
        String xml = """
                <response status="success"><result><system>
                <hostname>PAN-TANGO-01</hostname><ip-address>192.0.2.30</ip-address><netmask>255.255.255.0</netmask>
                <default-gateway>192.0.2.1</default-gateway><ipv6-address>unknown</ipv6-address>
                <model>Panorama</model><serial>SYNTH0001</serial><sw-version>11.1.4</sw-version>
                </system></result></response>""";
        InventoryResult r = InventoryCapabilityExecutor.panoramaInventory(xml, PaloAltoSystemInfoParser.parse(xml), "");
        InventoryResult.Completed c = assertInstanceOf(InventoryResult.Completed.class, r);
        var ctx = c.contexts().get(0);
        assertEquals("management", ctx.interfaces().get(0).name());
        assertEquals("192.0.2.30/24", ctx.interfaces().get(0).addresses().get(0).address());
        assertEquals(1, ctx.interfaces().get(0).addresses().size()); // "unknown" IPv6 is not an address
        assertEquals("0.0.0.0/0", ctx.routes().get(0).destination());
        assertEquals(Optional.of("192.0.2.1"), ctx.routes().get(0).nextHop());
    }
}
