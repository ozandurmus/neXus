package com.securityexpert.nexus.ui2.worker.inventory.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedInterface;

class CheckPointFwGetifsParserTest {

    @Test
    void parsesEachLineIntoOneInterfaceWithOneMemberAddress() {
        String output = "localhost Mgmt 10.176.107.101 255.255.255.0\n"
                + "localhost eth2-03 10.230.10.39 255.255.254.0\n";

        List<ParsedInterface> interfaces = CheckPointFwGetifsParser.parse(output);

        assertEquals(2, interfaces.size());
        assertEquals("Mgmt", interfaces.get(0).name());
        assertEquals("10.176.107.101/24", interfaces.get(0).addresses().get(0).address());
        assertEquals(InventoryAddress.FAMILY_IPV4, interfaces.get(0).addresses().get(0).family());
        assertEquals(InventoryAddress.ROLE_MEMBER, interfaces.get(0).addresses().get(0).role());
        assertEquals(InventoryInterface.STATE_UNKNOWN, interfaces.get(0).state());

        assertEquals("eth2-03", interfaces.get(1).name());
        assertEquals("10.230.10.39/23", interfaces.get(1).addresses().get(0).address());
    }

    @Test
    void ignoresLoginBannerAndVsenvContextLinesAroundTheRealOutput() {
        String output = "Warning! Grub default password hasn't been changed.\n"
                + "Context is set to Virtual Device AjansIntraODM (ID 1).\n"
                + "localhost eth2-01.3601 192.168.48.1 255.255.255.128\n"
                + "\n";

        List<ParsedInterface> interfaces = CheckPointFwGetifsParser.parse(output);

        assertEquals(1, interfaces.size());
        assertEquals("eth2-01.3601", interfaces.get(0).name());
        assertEquals("192.168.48.1/25", interfaces.get(0).addresses().get(0).address());
        assertEquals(InventoryInterface.KIND_VLAN, interfaces.get(0).kind());
    }

    @Test
    void blankOrNullOutputParsesToNoInterfaces() {
        assertTrue(CheckPointFwGetifsParser.parse(null).isEmpty());
        assertTrue(CheckPointFwGetifsParser.parse("").isEmpty());
        assertTrue(CheckPointFwGetifsParser.parse("   \n  \n").isEmpty());
    }
}
