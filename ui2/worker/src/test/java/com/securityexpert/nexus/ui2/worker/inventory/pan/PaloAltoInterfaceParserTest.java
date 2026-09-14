package com.securityexpert.nexus.ui2.worker.inventory.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedInterface;

/** AC-2: {@code show interface all} -- every address element kept, address-less interfaces kept, grouped by vsys. */
class PaloAltoInterfaceParserTest {

    @Test
    void groupsInterfacesByVsysAndKeepsEveryAddressElement() {
        Map<String, List<ParsedInterface>> byVsys =
                PaloAltoInterfaceParser.parse(Fixtures.read("pan/show_interface_all.xml"));

        assertEquals(2, byVsys.size());
        assertEquals(2, byVsys.get("vsys1").size(), "ethernet1/1 and the address-less ha1");
        assertEquals(1, byVsys.get("vsys2").size());

        ParsedInterface eth1 = byVsys.get("vsys1").stream().filter(i -> i.name().equals("ethernet1/1")).findFirst()
                .orElseThrow();
        assertEquals(1, eth1.addresses().size());
        assertEquals("192.0.2.1/24", eth1.addresses().get(0).address());
        assertEquals(InventoryInterface.STATE_UP, eth1.state());

        ParsedInterface ha1 = byVsys.get("vsys1").stream().filter(i -> i.name().equals("ha1")).findFirst()
                .orElseThrow();
        assertTrue(ha1.addresses().isEmpty(), "an address-less interface (N/A) is still kept");
        assertEquals(InventoryInterface.STATE_DOWN, ha1.state());

        ParsedInterface subif = byVsys.get("vsys2").get(0);
        assertEquals("ethernet1/2.100", subif.name());
        assertEquals(Optional.of("ethernet1/2"), subif.parent());
        assertEquals(InventoryInterface.KIND_SUBINTERFACE, subif.kind());
        assertEquals(2, subif.addresses().size(), "the ipv4 <ip> element and the nested <ipv6><entry><addr>");
        assertTrue(subif.addresses().stream().anyMatch(a -> a.family().equals(InventoryAddress.FAMILY_IPV6)
                && a.address().equals("2001:db8::10/64")));
    }

    @Test
    void emptyResponseYieldsNoInterfaces() {
        assertEquals(Map.of(), PaloAltoInterfaceParser.parse(""));
    }
}
