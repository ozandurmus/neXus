package com.securityexpert.nexus.ui2.worker.inventory.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedInterface;

/** 14E PP-1/PM-2: every address kept, address-less interfaces kept, {@code hw/entry} rows are physical ports. */
class PaloAltoInterfaceParserTest {

    @Test
    void groupsInterfacesByVsysAndKeepsEveryAddressElement() {
        PaloAltoInterfaceParseResult result = PaloAltoInterfaceParser.parse(Fixtures.read("pan/show_interface_all.xml"));

        assertEquals(2, result.interfacesByVsys().size());
        assertEquals(3, result.interfacesByVsys().get("1").size(), "ethernet1/1, the address-less ha1, and ethernet1/1.50");
        assertEquals(2, result.interfacesByVsys().get("2").size(), "ethernet1/2.100 and ethernet1/3");

        ParsedInterface eth1 = result.interfacesByVsys().get("1").stream().filter(i -> i.name().equals("ethernet1/1"))
                .findFirst().orElseThrow();
        assertEquals(1, eth1.addresses().size());
        assertEquals("192.0.2.1/24", eth1.addresses().get(0).address());
        assertEquals(InventoryInterface.STATE_UP, eth1.state());
        assertEquals("default", result.interfaceNameToVirtualRouter().get("ethernet1/1"), "fwd's vr: prefix is stripped");

        ParsedInterface ha1 = result.interfacesByVsys().get("1").stream().filter(i -> i.name().equals("ha1"))
                .findFirst().orElseThrow();
        assertTrue(ha1.addresses().isEmpty(), "an address-less interface (N/A) is still kept");
        assertEquals(InventoryInterface.STATE_DOWN, ha1.state());

        ParsedInterface subif = result.interfacesByVsys().get("2").stream().filter(i -> i.name().equals("ethernet1/2.100"))
                .findFirst().orElseThrow();
        assertEquals(java.util.Optional.of("ethernet1/2"), subif.parent());
        assertEquals(InventoryInterface.KIND_SUBINTERFACE, subif.kind());
        assertEquals(3, subif.addresses().size(), "the primary <ip>, the <addr> secondary member, and the <addr6> member");
        assertTrue(subif.addresses().stream().anyMatch(a -> a.family().equals(InventoryAddress.FAMILY_IPV4)
                && a.address().equals("198.51.100.20/27")), "every child of <addr> is kept, not just the primary <ip>");
        assertTrue(subif.addresses().stream().anyMatch(a -> a.family().equals(InventoryAddress.FAMILY_IPV6)
                && a.address().equals("2001:db8::10/64")));
        assertEquals("default", result.interfaceNameToVirtualRouter().get("ethernet1/2.100"),
                "ethernet1/2.100 forwards through the same virtual router as ethernet1/1 (the spanning case)");

        assertEquals("VR-DMZ", result.interfaceNameToVirtualRouter().get("ethernet1/3"));
    }

    @Test
    void hwEntriesAreKeptSeparatelyAsPhysicalPortsWithNoAddress() {
        PaloAltoInterfaceParseResult result = PaloAltoInterfaceParser.parse(Fixtures.read("pan/show_interface_all.xml"));

        assertEquals(3, result.physicalPorts().size());
        List<String> names = result.physicalPorts().stream().map(ParsedInterface::name).toList();
        assertEquals(List.of("ethernet1/1", "ethernet1/2", "ethernet1/3"), names);
        for (ParsedInterface port : result.physicalPorts()) {
            assertEquals(InventoryInterface.KIND_PHYSICAL, port.kind());
            assertTrue(port.addresses().isEmpty(), "a hw port carries no address (PM-2)");
        }
        ParsedInterface eth3Port = result.physicalPorts().stream().filter(p -> p.name().equals("ethernet1/3"))
                .findFirst().orElseThrow();
        assertEquals(InventoryInterface.STATE_DOWN, eth3Port.state());
    }

    /** Product Owner correction (2026-09-21): a subinterface's own ifnet entry never carries a
     * <state> leaf -- only its physical port in <hw> does -- and a subinterface cannot be up while
     * its physical port is down, so it inherits that port's state exactly. */
    @Test
    void aSubinterfaceWithNoStateLeafInheritsItsPhysicalPortsState() {
        PaloAltoInterfaceParseResult result = PaloAltoInterfaceParser.parse(Fixtures.read("pan/show_interface_all.xml"));

        ParsedInterface subif = result.interfacesByVsys().get("1").stream().filter(i -> i.name().equals("ethernet1/1.50"))
                .findFirst().orElseThrow();
        assertEquals(java.util.Optional.of("ethernet1/1"), subif.parent());
        assertEquals(InventoryInterface.STATE_UP, subif.state(), "ethernet1/1's own hw port is up");
    }

    @Test
    void emptyResponseYieldsNoInterfacesOrPorts() {
        PaloAltoInterfaceParseResult result = PaloAltoInterfaceParser.parse("");
        assertEquals(java.util.Map.of(), result.interfacesByVsys());
        assertTrue(result.physicalPorts().isEmpty());
    }

    /** V148 (PO 2026-10-15): zone by interface name, link-layer address by physical port name, nothing guessed. */
    @Test
    void zonesAndPortMacAddressesAreReadByExactInterfaceName() {
        PaloAltoInterfaceParseResult result = PaloAltoInterfaceParser.parse(Fixtures.read("pan/show_interface_all.xml"));

        assertEquals("trust", result.interfaceNameToZone().get("ethernet1/1"));
        assertEquals("dmz", result.interfaceNameToZone().get("ethernet1/2.100"));
        assertEquals("dmz", result.interfaceNameToZone().get("ethernet1/3"));
        assertEquals("trust", result.interfaceNameToZone().get("ethernet1/1.50"));
        assertTrue(!result.interfaceNameToZone().containsKey("ha1"), "an interface with no zone leaf has no zone");

        assertEquals("00:1b:17:00:01:01", result.portNameToMac().get("ethernet1/1"));
        assertEquals("00:1b:17:00:01:03", result.portNameToMac().get("ethernet1/3"));
        assertTrue(!result.portNameToMac().containsKey("ethernet1/2.100"), "a subinterface is not given its port's address");
    }

    @Test
    void malformedOrAllZeroMacAndNotApplicableZoneAreNotRecorded() {
        String body = "<response><result><ifnet><entry><name>ethernet1/9</name><zone>N/A</zone><vsys>1</vsys></entry></ifnet>"
                + "<hw><entry><name>ethernet1/9</name><state>up</state><mac>00:00:00:00:00:00</mac></entry>"
                + "<entry><name>ethernet1/8</name><state>up</state><mac>not-a-mac</mac></entry></hw></result></response>";
        PaloAltoInterfaceParseResult result = PaloAltoInterfaceParser.parse(body);
        assertTrue(result.interfaceNameToZone().isEmpty());
        assertTrue(result.portNameToMac().isEmpty());
    }
}
