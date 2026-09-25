package com.securityexpert.nexus.ui2.worker.backup.fortinet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;

/** Shapes from Backbox trail 23488971 (FortiOS 7.0.12, VDOMs) and the FortiManager JSON API; values synthetic. */
class FortinetParsersTest {

    @Test
    void systemStatus() {
        var st = FortiGatePlan.parseStatus("""
                Version: FortiGate-1101E v7.0.12,build0523,230606 (GA.M)
                Serial-Number: SYNTH00001
                Hostname: FGT-TANGO-04
                Virtual domain configuration: multiple
                Current HA mode: a-p, primary
                """);
        assertEquals(Optional.of("FortiGate-1101E"), st.model());
        assertEquals(Optional.of("v7.0.12 build0523"), st.version());
        assertEquals(Optional.of("FGT-TANGO-04"), st.hostname());
        assertTrue(st.multiVdom());
        assertEquals(Optional.of("active"), FortiGatePlan.haRole(st.haMode()));
        assertFalse(FortiGatePlan.parseStatus("Version: FortiGate-60F v7.2.8,build1639\\nVirtual domain configuration: disable\\n").multiVdom());
    }

    @Test
    void interfacesByVdom() {
        var list = FortiGatePlan.parseInterfaces("""
                config system interface
                    edit "port1"
                        set vdom "root"
                        set ip 192.0.2.1 255.255.255.0
                        set type physical
                    next
                    edit "vl100"
                        set vdom "VD-ALPHA"
                        set ip 198.51.100.1 255.255.255.252
                        set interface "port2"
                        set vlanid 100
                        set type vlan
                        set status down
                    next
                end
                """);
        assertEquals(2, list.size());
        assertEquals("root", list.get(0).vdom());
        assertEquals("192.0.2.1/24", list.get(0).row().addresses().get(0).address());
        assertEquals("VD-ALPHA", list.get(1).vdom());
        assertEquals(InventoryInterface.KIND_VLAN, list.get(1).row().kind());
        assertEquals(Optional.of("port2"), list.get(1).row().parent());
        assertEquals(Optional.of(100), list.get(1).row().vlanId());
        assertEquals(InventoryInterface.STATE_DOWN, list.get(1).row().state());
    }

    @Test
    void routes() {
        var routes = FortiGatePlan.parseRoutes("""
                Routing table for VRF=0
                S*      0.0.0.0/0 [10/0] via 192.0.2.254, port1, [1/0]
                C       192.0.2.0/24 is directly connected, port1
                B       203.0.113.0/24 [20/0] via 198.51.100.9 (recursive is directly connected, port2), 2d01h
                O E2    10.9.0.0/16 [110/20] via 192.0.2.10, port1, 01:02:03
                """);
        assertEquals(4, routes.size());
        assertEquals(InventoryRoute.PROTOCOL_DEFAULT, routes.get(0).protocol());
        assertEquals(Optional.of("port1"), routes.get(0).interfaceName());
        assertEquals(InventoryRoute.PROTOCOL_CONNECTED, routes.get(1).protocol());
        assertEquals(Optional.of("198.51.100.9"), routes.get(2).nextHop());
        assertEquals(Optional.of("port2"), routes.get(2).interfaceName());
        assertEquals(InventoryRoute.PROTOCOL_OSPF, routes.get(3).protocol());
    }

    @Test
    void backupHeaderAndVdomNames() {
        assertTrue(FortiGatePlan.isConfiguration("#config-version=FG10E1-7.0.12-FW-build0523-230606:opmode=0:vdom=1\n"));
        assertFalse(FortiGatePlan.isConfiguration("Command fail. Return code -61"));
        assertTrue(FortiGatePlan.isCliError("Command fail. Return code -61"));
        assertEquals("edit VD-ALPHA", FortiGatePlan.editVdom("VD-ALPHA"));
        assertThrows(IllegalArgumentException.class, () -> FortiGatePlan.editVdom("x; execute factoryreset"));
    }

    @Test
    void fortiManagerStatusAndDevices() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var id = FortiManagerExecutor.identity(json.readTree("""
                {"Hostname":"FMG-TANGO-01","Platform Type":"FMG-VM64","Version":"v7.4.3-build2487 240514 (GA)"}"""));
        assertEquals(Optional.of("FMG-TANGO-01"), id.name());
        assertEquals(Optional.of("FMG-VM64"), id.model());
        assertEquals(Optional.of("v7.4.3-build2487"), id.version());
        var devices = FortiManagerExecutor.devices(json.readTree("""
                [{"name":"FGT-B","ip":"192.0.2.2","platform_str":"FortiGate-600E","os_ver":7,"mr":2,"patch":8,"build":1639,"conn_status":1,"ha_mode":1},
                 {"name":"FGT-A","ip":"192.0.2.1","platform_str":"FortiGate-60F","os_ver":7,"mr":0,"patch":12,"conn_status":2}]"""));
        assertEquals(2, devices.size());
        assertEquals("FGT-A", devices.get(0).hostName());
        assertEquals(Optional.of("v7.0.12"), devices.get(0).hypervisor());
        assertEquals(Optional.of("DOWN"), devices.get(0).nodeStatus());
        assertEquals(Optional.of("v7.2.8 build1639"), devices.get(1).hypervisor());
        assertTrue(devices.get(1).haEnabled());
    }
}
