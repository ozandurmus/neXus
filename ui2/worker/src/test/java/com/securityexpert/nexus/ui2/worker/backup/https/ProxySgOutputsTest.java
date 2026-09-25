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
}
