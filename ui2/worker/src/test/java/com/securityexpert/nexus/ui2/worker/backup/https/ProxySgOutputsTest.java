package com.securityexpert.nexus.ui2.worker.backup.https;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.worker.discovery.BlueCoatDiscoveryCandidateMapper;

/** Tolerant SGOS parsers (shapes not yet measured on the estate; synthetic values). */
class ProxySgOutputsTest {

    @Test
    void interfaceBlocks() {
        var ifs = ProxySgOutputs.interfaces("""
                Interface 0:0: Intel Gigabit     running at 1 Gbps full duplex (auto)
                  Internet address: 192.0.2.10
                  Subnet mask: 255.255.255.0
                Interface 1:0: Intel Gigabit     link down
                """);
        assertEquals(2, ifs.size());
        assertEquals("0:0", ifs.get(0).name());
        assertEquals("192.0.2.10/24", ifs.get(0).addresses().get(0).address());
        assertEquals("up", ifs.get(0).state());
        assertEquals("down", ifs.get(1).state());
    }

    @Test
    void routeRows() {
        var routes = ProxySgOutputs.routes("""
                Destination      Gateway          Mask             Iface
                default          192.0.2.1                         0:0
                198.51.100.0     192.0.2.2        255.255.255.0    0:0
                192.0.2.0/24     0.0.0.0                           0:0
                """);
        assertEquals(3, routes.size());
        assertEquals("0.0.0.0/0", routes.get(0).destination());
        assertEquals(Optional.of("192.0.2.1"), routes.get(0).nextHop());
        assertEquals("198.51.100.0/24", routes.get(1).destination());
        assertEquals("connected", routes.get(2).protocol());
    }

    @Test
    void shapeHidesValues() {
        String s = ProxySgOutputs.shape("Internet address: 192.0.2.10", 3);
        assertFalse(s.contains("192"));
        assertTrue(s.startsWith("aa aa: 99.9.9.99"));
    }

    @Test
    void discoveryImportsProxySgsOnly() throws Exception {
        var list = new ObjectMapper().readTree("""
                [{"uuid":"u-1","name":"PX-1","host":"192.0.2.20:8082","type":"sgos6x","model":"S200","osVersion":"7.4.15.1","managementStatus":"FULLY_MANAGED"},
                 {"uuid":"u-2","name":"RPT-1","host":"192.0.2.21","type":"rptr"}]""");
        var records = BlueCoatDiscoveryCandidateMapper.map("run-1", list);
        assertEquals(2, records.size());
        assertEquals("BLUECOAT_PROXYSG", records.get(0).kind());
        assertTrue(records.get(0).importable());
        assertEquals(Optional.of("192.0.2.20"), records.get(0).ownAddress());
        assertFalse(records.get(1).importable());
        assertEquals("BLUECOAT_RPTR", records.get(1).kind());
    }

    @org.junit.jupiter.api.Test
    void aManagementCenterFailureMadeOnlyOfAbsentInterfaceNotesIsAccepted() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var absent = mapper.readTree("""
                {"status":"FAILURE","reply":"Ethernet interface 0:0 ...","messages":[
                 {"severity":"ERROR","message":"% Ethernet interface 4:0 is not installed\\r\\n"},
                 {"severity":"ERROR","message":"% Ethernet interface 9:0 is not installed\\r\\n"}]}""");
        var mixed = mapper.readTree("""
                {"status":"FAILURE","reply":"x","messages":[
                 {"severity":"ERROR","message":"% Ethernet interface 4:0 is not installed"},
                 {"severity":"ERROR","message":"% Invalid input detected"}]}""");
        var none = mapper.readTree("{\"status\":\"FAILURE\",\"reply\":\"x\",\"messages\":[]}");
        org.junit.jupiter.api.Assertions.assertTrue(HttpsVendorExecutor.onlyAbsentInterfaceMessages(absent));
        org.junit.jupiter.api.Assertions.assertFalse(HttpsVendorExecutor.onlyAbsentInterfaceMessages(mixed));
        org.junit.jupiter.api.Assertions.assertFalse(HttpsVendorExecutor.onlyAbsentInterfaceMessages(none));
    }

    @Test
    void parsesTheMeasuredSgosInterfaceShapeAndIgnoresEmptySlots() {
        String out = String.join("\n",
                "!- show interface all",
                "proxy-one#(config)show interface all",
                "  Ethernet interface 0:0 (Virtual network)",
                "    Status:               enabled",
                "    Link status:          autosensed to full duplex, virtual network",
                "    MAC address:          00:00:5e:00:53:01",
                "    Internet address:     192.0.2.10 netmask 255.255.255.0",
                "    IPv6 address:         fe80::1 prefixlen 64",
                "  Ethernet interface 1:0 (Virtual network)",
                "    Status:               disabled",
                "    Link status:          no link",
                "    Internet address:     0.0.0.0 netmask 0.0.0.0",
                "% Ethernet interface 4:0 is not installed");
        var list = ProxySgOutputs.interfaces(out);
        assertEquals(2, list.size());
        assertEquals("0:0", list.get(0).name());
        assertEquals(com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface.STATE_UP, list.get(0).state());
        assertEquals("192.0.2.10/24", list.get(0).addresses().get(0).address());
        assertEquals(com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface.STATE_DOWN, list.get(1).state());
        assertTrue(list.get(1).addresses().isEmpty());
    }
}
