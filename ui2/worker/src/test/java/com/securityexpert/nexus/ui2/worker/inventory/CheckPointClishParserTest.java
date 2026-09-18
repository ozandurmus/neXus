package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;

class CheckPointClishParserTest {

    @Test
    void testParseClishInterfacesBlockFormat() {
        String output = """
                Interface eth0
                	state on
                	mac-addr 00:1c:7f:20:11:22
                	type ethernet
                	ipv4-address 192.168.1.1
                	subnet-mask 255.255.255.0
                	mtu 1500
                Interface eth1
                	state off
                	mac-addr 00:1c:7f:20:11:23
                	type ethernet
                	ipv4-address 10.0.0.1/24
                """;

        List<ParsedInterface> ifaces = InventoryCapabilityExecutor.parseClishInterfaces(output);
        assertEquals(2, ifaces.size());

        ParsedInterface eth0 = ifaces.stream().filter(i -> i.name().equals("eth0")).findFirst().orElseThrow();
        assertEquals(InventoryInterface.STATE_UP, eth0.state());
        assertEquals(1, eth0.addresses().size());
        assertEquals("192.168.1.1/24", eth0.addresses().get(0).address());

        ParsedInterface eth1 = ifaces.stream().filter(i -> i.name().equals("eth1")).findFirst().orElseThrow();
        assertEquals(InventoryInterface.STATE_DOWN, eth1.state());
        assertEquals("10.0.0.1/24", eth1.addresses().get(0).address());
    }

    @Test
    void testParseClishInterfacesTabularFormat() {
        String output = """
                Interface    Type      Status    IPv4-Address      Subnet-Mask
                LAN1         ethernet  up        10.99.88.77       255.255.255.0
                WAN          ethernet  up        1.2.3.4           255.255.255.248
                DMZ          ethernet  down      0.0.0.0           0.0.0.0
                """;

        List<ParsedInterface> ifaces = InventoryCapabilityExecutor.parseClishInterfaces(output);
        assertEquals(3, ifaces.size());

        ParsedInterface lan1 = ifaces.stream().filter(i -> i.name().equals("LAN1")).findFirst().orElseThrow();
        assertEquals(InventoryInterface.STATE_UP, lan1.state());
        assertEquals("10.99.88.77/24", lan1.addresses().get(0).address());

        ParsedInterface wan = ifaces.stream().filter(i -> i.name().equals("WAN")).findFirst().orElseThrow();
        assertEquals("1.2.3.4/29", wan.addresses().get(0).address());

        ParsedInterface dmz = ifaces.stream().filter(i -> i.name().equals("DMZ")).findFirst().orElseThrow();
        assertEquals(InventoryInterface.STATE_DOWN, dmz.state());
        assertTrue(dmz.addresses().isEmpty());
    }

    @Test
    void testParseClishRoutes() {
        String output = """
                Codes: C - Connected, S - Static, R - RIP, B - BGP (RFC1771)
                       O - OSPF, D - DHCP, ...
                C        192.168.1.0/24 is directly connected, LAN1
                S        0.0.0.0/0 [1/0] via 1.2.3.1, WAN, cost 1, tag 0
                S        10.0.0.0/8 via 10.1.1.1
                B        172.16.0.0/16 [20/0] via 10.2.2.2, eth2
                """;

        List<ParsedRoute> routes = InventoryCapabilityExecutor.parseClishRoutes(output);
        assertEquals(4, routes.size());

        ParsedRoute r0 = routes.get(0);
        assertEquals("192.168.1.0/24", r0.destination());
        assertEquals(InventoryRoute.PROTOCOL_CONNECTED, r0.protocol());
        assertEquals("LAN1", r0.interfaceName().orElse(null));

        ParsedRoute r1 = routes.get(1);
        assertEquals("0.0.0.0/0", r1.destination());
        assertEquals("1.2.3.1", r1.nextHop().orElse(null));
        assertEquals("WAN", r1.interfaceName().orElse(null));
        assertEquals(InventoryRoute.PROTOCOL_STATIC, r1.protocol());

        ParsedRoute r2 = routes.get(2);
        assertEquals("10.0.0.0/8", r2.destination());
        assertEquals("10.1.1.1", r2.nextHop().orElse(null));

        ParsedRoute r3 = routes.get(3);
        assertEquals("172.16.0.0/16", r3.destination());
        assertEquals(InventoryRoute.PROTOCOL_BGP, r3.protocol());
    }
}
