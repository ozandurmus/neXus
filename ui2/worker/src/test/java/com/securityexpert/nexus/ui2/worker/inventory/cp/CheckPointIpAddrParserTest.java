package com.securityexpert.nexus.ui2.worker.inventory.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedAddress;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedInterface;

/**
 * AC-2: {@code ip -details -4 addr show} / {@code ip -6 addr show}, 14D
 * PR-2's measured corrections (both {@code @parent} forms, the VLAN detail
 * line, {@code link-netnsid} tolerance) and PR-5's interleaved-line
 * tolerance.
 */
class CheckPointIpAddrParserTest {

    @Test
    void parsesThePhysicalFixtureIncludingABondVlanSubinterfaceAndAnInterleavedSyslogLine() {
        String v4 = Fixtures.read("cp/ip_addr_show_v4.txt");
        String v6 = Fixtures.read("cp/ip_addr_show_v6.txt");

        List<ParsedInterface> interfaces = CheckPointIpAddrParser.parse(v4, v6);

        assertEquals(5, interfaces.size(), "lo, eth0, eth1, bond1, bond1.3841");

        ParsedInterface lo = byName(interfaces, "lo");
        assertEquals(InventoryInterface.KIND_LOOPBACK, lo.kind());
        assertEquals(InventoryInterface.STATE_UP, lo.state(), "UP is in lo's own flag list even though the "
                + "trailing 'state UNKNOWN' token is not -- PR-2 reads the flag list, not that token");

        ParsedInterface eth0 = byName(interfaces, "eth0");
        assertEquals(Optional.empty(), eth0.parent());
        assertEquals(InventoryInterface.STATE_UP, eth0.state());
        assertEquals(List.of(new ParsedAddress("192.0.2.10/24", InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)),
                eth0.addresses());

        ParsedInterface eth1 = byName(interfaces, "eth1");
        assertEquals(InventoryInterface.STATE_DOWN, eth1.state(), "no UP token in eth1's own flag list");
        assertTrue(eth1.addresses().isEmpty());

        assertTrue(byName(interfaces, "bond1").addresses().isEmpty(), "bond parents did not appear with an address");

        ParsedInterface subif = byName(interfaces, "bond1.3841");
        assertEquals(Optional.of("bond1"), subif.parent());
        assertEquals(InventoryInterface.KIND_VLAN, subif.kind());
        assertEquals(Optional.of(3841), subif.vlanId(), "the 'vlan protocol 802.1Q id <n>' detail line gives the VLAN id");
        assertEquals(1, subif.addresses().size());
        assertEquals("198.51.100.5/25", subif.addresses().get(0).address());
    }

    @Test
    void parsesTheVsContextFixtureWithAnAtIfNnParentAndTheLinkNetnsidTrailerTolerated() {
        String v4 = Fixtures.read("cp/ip_addr_show_vs_v4.txt");

        List<ParsedInterface> interfaces = CheckPointIpAddrParser.parse(v4, "");

        ParsedInterface subif = byName(interfaces, "eth3-01.3247");
        assertEquals(Optional.of("if34"), subif.parent(), "'name@ifNN' inside a virtual system splits identically to 'name@parent'");
        assertEquals(InventoryInterface.KIND_VLAN, subif.kind());
        assertEquals(Optional.of(3247), subif.vlanId());
        assertEquals(1, subif.addresses().size());
        assertEquals("203.0.113.65/28", subif.addresses().get(0).address(),
                "the trailing 'link-netnsid 0' token is tolerated, not captured as part of the address");
    }

    private static ParsedInterface byName(List<ParsedInterface> interfaces, String name) {
        return interfaces.stream().filter(i -> i.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no parsed interface named " + name));
    }
}
