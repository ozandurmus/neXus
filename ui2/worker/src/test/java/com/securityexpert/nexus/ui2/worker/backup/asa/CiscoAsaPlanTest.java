package com.securityexpert.nexus.ui2.worker.backup.asa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;

/** Shapes from the estate's ASA 9.22 on Firepower 4100 (Backbox trail), values synthetic (192.0.2.x, FW-TANGO-04). */
class CiscoAsaPlanTest {

    static final String SHOW_VERSION = """

            Cisco Adaptive Security Appliance Software Version 9.22(2)14
            SSP Operating System Version 2.16(0.136)
            Device Manager Version 7.18(1)152

            Compiled on Thu 04-Sep-25 13:59 GMT by fpbesprd
            System image file is "disk0:/fxos-lfbff-k8.SPA"
            Config file at boot was "startup-config"

            FW-TANGO-04 up 358 days 20 hours
            failover cluster up 6 years 67 days
            Start-up time 21 secs

            Hardware:   FPR4K-SM-44S, 347283 MB RAM, CPU Xeon 4100/6100/8100 series 2100 MHz, 2 CPUs (88 cores)

            Failover                          : Active/Active
            Security Contexts                 : 10

            Serial Number: SYNTH0001
            """;

    @Test
    void showVersionGivesHostnameModelVersionSerial() {
        CiscoAsaPlan.Version v = CiscoAsaPlan.parseVersion(SHOW_VERSION);
        assertEquals(Optional.of("FW-TANGO-04"), v.hostname());
        assertEquals(Optional.of("FPR4K-SM-44S"), v.model());
        assertEquals(Optional.of("9.22(2)14"), v.softwareVersion());
        assertEquals(Optional.of("SYNTH0001"), v.serial());
        assertEquals(Optional.of("358 days 20 hours"), v.uptime());
    }

    @Test
    void notAnAsaGivesNoVersion() {
        assertTrue(CiscoAsaPlan.parseVersion("Cisco IOS Software, C2960 Software").softwareVersion().isEmpty());
    }

    @Test
    void cliRefusalIsRecognised() {
        assertTrue(CiscoAsaPlan.isCliError("enable\nERROR: % Invalid input detected at '^' marker.\n"));
        assertFalse(CiscoAsaPlan.isCliError(SHOW_VERSION));
    }

    @Test
    void privilegeAndFailoverRole() {
        assertEquals(Optional.of(15), CiscoAsaPlan.parsePrivilege("Username : svc\nCurrent privilege level : 15\nCurrent Mode/s : P_PRIV\n"));
        assertEquals(Optional.of("active"), CiscoAsaPlan.parseHaRole("        This host: Secondary - Active\n"));
        assertEquals(Optional.of("standby"), CiscoAsaPlan.parseHaRole("This host: Primary - Standby Ready\n"));
        assertEquals(Optional.empty(), CiscoAsaPlan.parseHaRole(""));
        assertTrue(CiscoAsaPlan.isMultipleContext("Security context mode: multiple\n"));
        assertFalse(CiscoAsaPlan.isMultipleContext("Security context mode: single\n"));
    }

    @Test
    void interfacesJoinAddressesAndStates() {
        String ip = """
                System IP Addresses:
                Interface                Name                   IP address      Subnet mask     Method
                Port-channel1.100        inside                 192.0.2.1       255.255.255.0   CONFIG
                Current IP Addresses:
                Interface                Name                   IP address      Subnet mask     Method
                Port-channel1.100        inside                 192.0.2.1       255.255.255.0   CONFIG
                Ethernet1/2              outside                198.51.100.2    255.255.255.252 CONFIG
                """;
        String brief = """
                Interface                  IP-Address      OK? Method Status                Protocol
                Ethernet1/2                198.51.100.2    YES CONFIG up                    up
                Port-channel1.100          192.0.2.1       YES CONFIG up                    up
                Management1/1              unassigned      YES unset  administratively down down
                """;
        var interfaces = CiscoAsaPlan.interfaces(ip, brief);
        assertEquals(3, interfaces.size());
        InventoryInterface sub = interfaces.stream().filter(i -> i.name().equals("Port-channel1.100")).findFirst().orElseThrow();
        assertEquals(InventoryInterface.KIND_SUBINTERFACE, sub.kind());
        assertEquals(Optional.of("Port-channel1"), sub.parent());
        assertEquals("192.0.2.1/24", sub.addresses().get(0).address());
        InventoryInterface mgmt = interfaces.stream().filter(i -> i.name().equals("Management1/1")).findFirst().orElseThrow();
        assertEquals(InventoryInterface.STATE_DOWN, mgmt.state());
        assertTrue(mgmt.addresses().isEmpty());
    }

    @Test
    void routesCarryProtocolNextHopAndInterface() {
        String route = """
                Codes: L - local, C - connected, S - static, R - RIP, M - mobile, B - BGP

                Gateway of last resort is 198.51.100.1 to network 0.0.0.0

                S*       0.0.0.0 0.0.0.0 [1/0] via 198.51.100.1, outside
                C        192.0.2.0 255.255.255.0 is directly connected, inside
                L        192.0.2.1 255.255.255.255 is directly connected, inside
                O E2     203.0.113.0 255.255.255.0 [110/20] via 192.0.2.254, 1d02h, inside
                """;
        var routes = CiscoAsaPlan.parseRoutes(route);
        assertEquals(4, routes.size());
        assertEquals(InventoryRoute.PROTOCOL_DEFAULT, routes.get(0).protocol());
        assertEquals("0.0.0.0/0", routes.get(0).destination());
        assertEquals(Optional.of("198.51.100.1"), routes.get(0).nextHop());
        assertEquals(Optional.of("outside"), routes.get(0).interfaceName());
        assertEquals(InventoryRoute.PROTOCOL_CONNECTED, routes.get(1).protocol());
        assertEquals(InventoryRoute.PROTOCOL_HOST, routes.get(2).protocol());
        assertEquals(InventoryRoute.PROTOCOL_OSPF, routes.get(3).protocol());
        assertEquals("203.0.113.0/24", routes.get(3).destination());
    }

    @Test
    void echoAndPromptAreStripped() {
        assertEquals("line1\nline2\n", CiscoAsaExecutor.stripEcho("show version\nline1\nline2\nFW-TANGO-04/sec/act# ", "show version"));
    }

    @Test
    void archiveCommandsNameOnlyNexusOwnFile() {
        String name = CiscoAsaPlan.archiveName("ac075038-a275-4907-94f0-d385fd435700");
        assertEquals("nexus-ac075038a2754907.tar.gz", name);
        assertEquals("backup /noconfirm location disk0:/" + name, CiscoAsaPlan.backupArchive(name));
        assertEquals("delete /noconfirm disk0:/" + name, CiscoAsaPlan.deleteArchive(name));
        assertEquals("disk0:/" + name, CiscoAsaPlan.scpPath(name));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> CiscoAsaPlan.deleteArchive("*.tar.gz"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> CiscoAsaPlan.deleteArchive("startup-config"));
    }

    @Test
    void archiveOutputFinishedAndFailedItems() {
        String out = """
                Begin backup ...
                Backing up [ASA Version] ... Done!
                Backing up [Identity Certificates] ... Done!
                Backing up [Dynamic Access Policies] ... Failed!
                Compressing the backup directory ... Done!
                Backup finished!
                """;
        assertTrue(CiscoAsaPlan.archiveFinished(out));
        assertEquals(java.util.List.of("Dynamic Access Policies"), CiscoAsaPlan.archiveFailedItems(out));
        assertFalse(CiscoAsaPlan.archiveFinished("ERROR: % Invalid input detected at '^' marker."));
    }
}
