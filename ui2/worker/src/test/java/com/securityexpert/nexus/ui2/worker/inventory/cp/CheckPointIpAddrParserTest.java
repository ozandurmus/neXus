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

/** AC-2: {@code ip -details -4 addr show} / {@code ip -6 addr show} fixtures parse into the expected records. */
class CheckPointIpAddrParserTest {

    @Test
    void parsesInterfacesKindsStatesAndMemberAddressesFromBothReads() {
        String v4 = Fixtures.read("cp/ip_addr_show_v4.txt");
        String v6 = Fixtures.read("cp/ip_addr_show_v6.txt");

        List<ParsedInterface> interfaces = CheckPointIpAddrParser.parse(v4, v6);

        assertEquals(4, interfaces.size());

        ParsedInterface eth0 = byName(interfaces, "eth0");
        assertEquals(Optional.empty(), eth0.parent());
        assertEquals(InventoryInterface.KIND_PHYSICAL, eth0.kind());
        assertEquals(InventoryInterface.STATE_UP, eth0.state());
        assertEquals(List.of(
                new ParsedAddress("192.0.2.10/24", InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER),
                new ParsedAddress("2001:db8::10/64", InventoryAddress.FAMILY_IPV6, InventoryAddress.ROLE_MEMBER)),
                eth0.addresses());

        ParsedInterface eth1 = byName(interfaces, "eth1");
        assertEquals(InventoryInterface.STATE_DOWN, eth1.state(), "no UP token in eth1's own flag list");
        assertTrue(eth1.addresses().isEmpty());

        ParsedInterface subif = byName(interfaces, "eth2.100");
        assertEquals(Optional.of("eth2"), subif.parent());
        assertEquals(InventoryInterface.KIND_VLAN, subif.kind());
        assertEquals(1, subif.addresses().size());
        assertEquals("198.51.100.1/25", subif.addresses().get(0).address());

        ParsedInterface lo = byName(interfaces, "lo");
        assertEquals(InventoryInterface.KIND_LOOPBACK, lo.kind());
        assertEquals(InventoryInterface.STATE_UP, lo.state(), "UP is in lo's own flag list even though the "
                + "trailing 'state UNKNOWN' token is not -- 14C's own correction reads the flag list, not that token");
        assertEquals(2, lo.addresses().size());
    }

    private static ParsedInterface byName(List<ParsedInterface> interfaces, String name) {
        return interfaces.stream().filter(i -> i.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no parsed interface named " + name));
    }
}
