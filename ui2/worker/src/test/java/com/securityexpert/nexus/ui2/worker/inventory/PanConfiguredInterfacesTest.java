package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.pan.PanFirewallCommandSwitches;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoConfiguredInterfaceParser;
import com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoHaStateParser;

/**
 * PO 2026-10-24 (replace_12, GR-4): on PAN-OS 12 or later a firewall's interfaces are derived from the stored
 * effective-running configuration -- configured, link state UNKNOWN except HA ports from the HA state read -- and
 * {@code show interface all} is not sent. Below 12 nothing changes. Synthetic configuration only (documentation
 * addresses, invented names).
 */
class PanConfiguredInterfacesTest {

    private static final String JOB_ID = "job-inventory-cfg-1";
    private static final long LEASE_EPOCH = 3L;
    private static final String DEVICE_ID = "device-cfg-1";

    /** A two-vsys effective-running document in the PAN-OS configuration schema, wrapped as the XML API returns it. */
    static String config() {
        return "<response status=\"success\"><result><config version=\"12.1.0\"><mgt-config><users/></mgt-config>"
                + "<devices><entry name=\"localhost.localdomain\">"
                + "<network><interface>"
                + "<ethernet>"
                + "<entry name=\"ethernet1/1\"><layer3><ip><entry name=\"192.0.2.1/24\"/></ip>"
                + "<ipv6><address><entry name=\"2001:db8::1/64\"/></address></ipv6></layer3></entry>"
                + "<entry name=\"ethernet1/2\"><layer3><units><entry name=\"ethernet1/2.100\"><tag>100</tag>"
                + "<ip><entry name=\"198.51.100.1/25\"/><entry name=\"OBJ-SYNTHETIC-ADDRESS\"/></ip></entry></units></layer3></entry>"
                + "<entry name=\"ethernet1/3\"><layer3><dhcp-client/></layer3></entry>"
                + "<entry name=\"ethernet1/5\"><ha/></entry>"
                + "</ethernet>"
                + "<loopback><units><entry name=\"loopback.1\"><ip><entry name=\"203.0.113.9/32\"/></ip></entry></units></loopback>"
                + "</interface>"
                + "<virtual-router><entry name=\"default\"><interface><member>ethernet1/1</member></interface></entry>"
                + "<entry name=\"VR-DMZ\"><interface><member>ethernet1/2.100</member><member>ethernet1/3</member></interface></entry>"
                + "</virtual-router></network>"
                + "<deviceconfig><system><hostname>SYNTHETIC-NAME</hostname></system><high-availability><interface>"
                + "<ha1><port>ha1-a</port></ha1><ha1-backup><port>ha1-b</port></ha1-backup><ha2><port>ethernet1/5</port></ha2>"
                + "</interface></high-availability></deviceconfig>"
                + "<vsys>"
                + "<entry name=\"vsys1\"><import><network><interface><member>ethernet1/1</member><member>loopback.1</member>"
                + "</interface></network></import><zone><entry name=\"Z-OUT\"><network><layer3><member>ethernet1/1</member>"
                + "</layer3></network></entry></zone><rulebase><security/></rulebase></entry>"
                + "<entry name=\"vsys2\"><import><network><interface><member>ethernet1/2.100</member><member>ethernet1/3</member>"
                + "</interface></network></import><zone><entry name=\"Z-DMZ\"><network><layer3><member>ethernet1/2.100</member>"
                + "</layer3></network></entry></zone></entry>"
                + "</vsys></entry></devices></config></result></response>";
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static PaloAltoConfiguredInterfaceParser.Configured parsed(String text) {
        return PaloAltoConfiguredInterfaceParser.parse(stream(text)).orElseThrow();
    }

    @Test
    void namesAddressesZonesVsysAndRoutersComeFromTheConfigurationWithLinkStateUnknown() {
        var result = parsed(config());
        var interfaces = result.interfaces();
        assertEquals(List.of("ethernet1/1", "ethernet1/2", "ethernet1/3", "ethernet1/5", "ha1-a", "ha1-b"),
                interfaces.physicalPorts().stream().map(ParsedInterface::name).toList());
        assertEquals(List.of("ethernet1/1", "loopback.1"),
                interfaces.interfacesByVsys().get("1").stream().map(ParsedInterface::name).sorted().toList());
        var vsys2 = interfaces.interfacesByVsys().get("2");
        assertEquals(List.of("ethernet1/2.100", "ethernet1/3"), vsys2.stream().map(ParsedInterface::name).sorted().toList());
        var sub = vsys2.stream().filter(i -> i.name().equals("ethernet1/2.100")).findFirst().orElseThrow();
        assertEquals(Optional.of("ethernet1/2"), sub.parent());
        assertEquals(Optional.of(100), sub.vlanId());
        assertEquals(List.of("198.51.100.1/25"), sub.addresses().stream().map(ParsedAddress::address).toList(),
                "an address-object name is not a literal address: not recorded");
        var e11 = interfaces.interfacesByVsys().get("1").stream().filter(i -> i.name().equals("ethernet1/1")).findFirst().orElseThrow();
        assertEquals(List.of("192.0.2.1/24", "2001:db8::1/64"), e11.addresses().stream().map(ParsedAddress::address).toList());
        assertTrue(vsys2.stream().filter(i -> i.name().equals("ethernet1/3")).findFirst().orElseThrow().addresses().isEmpty(),
                "a DHCP address is not in the configuration");
        // ethernet1/2 itself is layer3 with only units: unassigned (no vsys imports it) -> vsys 0 (the physical context).
        assertEquals(List.of("ethernet1/2"), interfaces.interfacesByVsys().get("0").stream().map(ParsedInterface::name).toList());
        assertFalse(interfaces.interfaceNameToVsys().containsKey("ethernet1/5"), "an HA port has no logical interface");
        assertEquals("Z-OUT", interfaces.interfaceNameToZone().get("ethernet1/1"));
        assertEquals("Z-DMZ", interfaces.interfaceNameToZone().get("ethernet1/2.100"));
        assertEquals("default", interfaces.interfaceNameToVirtualRouter().get("ethernet1/1"));
        assertEquals("VR-DMZ", interfaces.interfaceNameToVirtualRouter().get("ethernet1/3"));
        assertTrue(interfaces.portNameToMac().isEmpty(), "no MAC in configuration");
        for (List<ParsedInterface> list : interfaces.interfacesByVsys().values()) {
            assertTrue(list.stream().allMatch(i -> InventoryInterface.STATE_UNKNOWN.equals(i.state())));
        }
        assertTrue(interfaces.physicalPorts().stream().allMatch(i -> InventoryInterface.STATE_UNKNOWN.equals(i.state())));
        assertEquals(Map.of("ha1", "ha1-a", "ha1-backup", "ha1-b", "ha2", "ethernet1/5"), result.haPortByLink());
        assertEquals(1, result.addressesNotLiteral());
    }

    @Test
    void aSingleVsysFirewallAndAnAdvancedRoutingLogicalRouterAreRead() {
        String single = "<response status=\"success\"><result><config><devices><entry name=\"localhost.localdomain\">"
                + "<network><interface><ethernet><entry name=\"ethernet1/1\"><layer3><ip><entry name=\"192.0.2.10/24\"/></ip>"
                + "</layer3></entry></ethernet></interface><logical-router><entry name=\"LR-1\"><vrf><entry name=\"default\">"
                + "<interface><member>ethernet1/1</member></interface></entry></vrf></entry></logical-router></network>"
                + "<vsys><entry name=\"vsys1\"><import><network><interface><member>ethernet1/1</member></interface></network>"
                + "</import></entry></vsys></entry></devices></config></result></response>";
        var interfaces = parsed(single).interfaces();
        assertEquals(List.of("1"), List.copyOf(interfaces.interfacesByVsys().keySet()));
        assertEquals("LR-1", interfaces.interfaceNameToVirtualRouter().get("ethernet1/1"));
        assertTrue(parsed(single).haPortByLink().isEmpty(), "no HA configured");
    }

    @Test
    void anInterfaceNamedForTwoVsysOrZonesIsLeftUnattributed() {
        String twice = config().replace("<member>ethernet1/3</member></interface></network></import>",
                "<member>ethernet1/3</member><member>ethernet1/1</member></interface></network></import>");
        var result = parsed(twice);
        assertEquals("0", result.interfaces().interfaceNameToVsys().get("ethernet1/1"), "ambiguous vsys: unassigned, never guessed");
        assertEquals(1, result.ambiguousAttributions());
    }

    @Test
    void anythingButOneSuccessfulDeviceEntryIsUnreadable() {
        for (String bad : new String[] {
                "<response status=\"error\"><msg>synthetic</msg></response>",
                "not xml at all",
                "<response status=\"success\"><result><config><devices/></config></result></response>",
                "<response status=\"success\"><result><config><devices><entry name=\"a\"/><entry name=\"b\"/></devices></config></result></response>",
                "<response status=\"success\"><result><config><devices><entry name=\"a\">",
                "<!DOCTYPE r [<!ENTITY x \"y\">]><response status=\"success\"><result><config/></result></response>"}) {
            assertTrue(PaloAltoConfiguredInterfaceParser.parse(stream(bad)).isEmpty(), bad);
        }
        assertTrue(PaloAltoConfiguredInterfaceParser.parse(null).isEmpty());
        // A device with no interface configuration at all is readable and simply has none.
        var empty = parsed("<response status=\"success\"><result><config><devices><entry name=\"x\"/></devices></config></result></response>");
        assertTrue(empty.interfaces().physicalPorts().isEmpty() && empty.interfaces().interfacesByVsys().isEmpty());
    }

    @Test
    void haLinkStatesComeFromThePeerInfoConnectionStatusOnly() {
        String ha = "<response status=\"success\"><result><enabled>yes</enabled><group><local-info><state>active</state>"
                + "</local-info><peer-info><conn-ha1><conn-status>up</conn-status></conn-ha1>"
                + "<conn-ha1-backup><conn-status>down</conn-status></conn-ha1-backup>"
                + "<conn-ha2><conn-status>flapping</conn-status></conn-ha2></peer-info></group></result></response>";
        assertEquals(Map.of("ha1", "up", "ha1-backup", "down"), PaloAltoHaStateParser.linkStates(ha));
        assertTrue(PaloAltoHaStateParser.linkStates("<response><result><enabled>no</enabled></result></response>").isEmpty());
        assertTrue(PaloAltoHaStateParser.linkStates(null).isEmpty());
    }

    // ---- the inventory run ----

    private static final String HA_STATE = "<response status=\"success\"><result><enabled>yes</enabled><group>"
            + "<mode>Active-Passive</mode><local-info><state>active</state></local-info><peer-info><state>passive</state>"
            + "<conn-ha1><conn-status>up</conn-status></conn-ha1><conn-ha1-backup><conn-status>down</conn-status>"
            + "</conn-ha1-backup></peer-info></group></result></response>";
    private static final String ROUTES = "<response><result>"
            + "<entry><destination>0.0.0.0/0</destination><nexthop>192.0.2.254</nexthop>"
            + "<interface>ethernet1/1</interface><virtual-router>default</virtual-router><flags>A S</flags></entry>"
            + "<entry><destination>198.51.100.128/25</destination><nexthop>198.51.100.126</nexthop>"
            + "<interface>ethernet1/2.100</interface><virtual-router>VR-DMZ</virtual-router><flags>A S</flags></entry>"
            + "</result></response>";

    private record Run(ScriptedPaloAltoInventoryTransport transport, InventoryRun run, List<String> opened) {
    }

    private static Run run(String switchValue, String swVersion, PanConfiguredInterfaceSource source) {
        ScriptedPaloAltoInventoryTransport transport = new ScriptedPaloAltoInventoryTransport(Map.of(
                InventoryReadPlan.PAN_TYPE_VERSION, "<response status=\"success\"><result><sw-version>" + swVersion
                        + "</sw-version><model>PA-" + "TESTMODEL</model><serial>TESTSER" + "0002</serial></result></response>",
                InventoryReadPlan.PAN_SHOW_HA_STATE, HA_STATE,
                InventoryReadPlan.PAN_SHOW_INTERFACE_ALL, "<response><result><ifnet><entry><name>ethernet1/1</name>"
                        + "<ip>192.0.2.1/24</ip><vsys>1</vsys><fwd>vr:default</fwd><state>up</state></entry></ifnet></result></response>",
                InventoryReadPlan.PAN_SHOW_ROUTING_ROUTE, ROUTES));
        List<String> opened = new java.util.ArrayList<>();
        PanConfiguredInterfaceSource recording = deviceId -> {
            opened.add(deviceId);
            return source.openLatestEffectiveRunning(deviceId);
        };
        var inventoryRepository = new InventoryJobExecutorFakes.FakeDeviceInventoryRepository();
        InventoryCapabilityExecutor capabilityExecutor = new InventoryCapabilityExecutor(transport, ref ->
                new com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial("user", "pw".toCharArray()),
                PanFirewallCommandSwitches.from(Map.of(PanFirewallCommandSwitches.SHOW_INTERFACE_ALL_VARIABLE, switchValue)),
                recording);
        InventoryJobExecutor executor = new InventoryJobExecutor(
                new InventoryJobExecutorFakes.FakeLeaseRepository(JOB_ID, LEASE_EPOCH, JobState.CLAIMED),
                new InventoryJobExecutorFakes.FakeStepAttemptRepository(), new InventoryJobExecutorFakes.FakeDeviceEnrollmentReadPort(),
                new InventoryJobExecutorFakes.FakeDeviceRepository(), inventoryRepository, capabilityExecutor);
        JobOutcome outcome = executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID,
                InventoryRequest.paloAlto(new ApiTarget("ep-9", "https://fw.example"), "cred-9"), false);
        assertTrue(outcome instanceof JobOutcome.Completed, "expected Completed, got " + outcome);
        return new Run(transport, inventoryRepository.lastRecordedRun, opened);
    }

    private static InventoryContext context(InventoryRun run, String name) {
        return run.contexts().stream().filter(c -> c.context().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void onPanOsTwelveTheInterfacesComeFromTheStoredConfigurationAndShowInterfaceAllIsNotSent() {
        Run r = run("replace_12", "12.1.2", deviceId -> Optional.of(stream(config())));
        assertFalse(r.transport().sentCmds.contains(InventoryReadPlan.PAN_SHOW_INTERFACE_ALL));
        assertEquals(List.of(DEVICE_ID), r.opened(), "the device's own stored configuration");
        assertEquals(List.of("pan_show_interface_all", "pan_os_12_or_later",
                InventoryCapabilityExecutor.TOKEN_INTERFACES_FROM_CONFIGURATION), r.run().notCollected());
        InventoryContext vsys1 = context(r.run(), "1");
        InventoryInterface e11 = vsys1.interfaces().stream().filter(i -> i.name().equals("ethernet1/1")).findFirst().orElseThrow();
        assertEquals(InventoryInterface.STATE_UNKNOWN, e11.state(), "configured, not observed");
        assertEquals(Optional.of("Z-OUT"), e11.zone());
        assertTrue(e11.macAddress().isEmpty());
        InventoryContext vsys2 = context(r.run(), "2");
        assertTrue(vsys2.routes().stream().anyMatch(route -> route.routeTable().equals(Optional.of("VR-DMZ"))),
                "routes are still attributed to the vsys through the configured virtual router");
        InventoryContext physical = context(r.run(), InventoryContext.PHYSICAL);
        Map<String, String> portState = new java.util.HashMap<>();
        physical.interfaces().forEach(i -> portState.put(i.name(), i.state()));
        assertEquals(InventoryInterface.STATE_UP, portState.get("ha1-a"), "HA1 port: the HA state's HA1 connection is up");
        assertEquals(InventoryInterface.STATE_DOWN, portState.get("ha1-b"), "HA1 backup port: down per the HA state");
        assertEquals(InventoryInterface.STATE_UNKNOWN, portState.get("ethernet1/5"), "HA2 not reported by the HA state: unknown");
        assertEquals(InventoryInterface.STATE_UNKNOWN, portState.get("ethernet1/1"));
    }

    @Test
    void withoutAStoredConfigurationTheInterfacesStayUnknownWithTheReason() {
        Run r = run("replace_12", "12.1.2", PanConfiguredInterfaceSource.NONE);
        assertFalse(r.transport().sentCmds.contains(InventoryReadPlan.PAN_SHOW_INTERFACE_ALL));
        assertEquals(List.of("pan_show_interface_all", "pan_os_12_or_later",
                InventoryCapabilityExecutor.TOKEN_INTERFACES_CONFIGURATION_MISSING), r.run().notCollected());
        assertTrue(r.run().contexts().stream().allMatch(c -> c.interfaces().isEmpty()), "no interface is invented");
        assertEquals(2, r.run().contexts().stream().mapToInt(c -> c.routes().size()).sum(), "routes are still read");
    }

    @Test
    void anUnreadableStoredConfigurationIsUnknownWithItsOwnReason() {
        Run broken = run("replace_12", "12.1.2", deviceId -> {
            throw new IOException("synthetic artefact failure");
        });
        assertEquals(InventoryCapabilityExecutor.TOKEN_INTERFACES_CONFIGURATION_UNREADABLE, broken.run().notCollected().get(2));
        Run garbage = run("replace_12", "12.1.2", deviceId -> Optional.of(stream("<response status=\"error\"/>")));
        assertEquals(InventoryCapabilityExecutor.TOKEN_INTERFACES_CONFIGURATION_UNREADABLE, garbage.run().notCollected().get(2));
    }

    @Test
    void belowTwelveAndUnknownVersionsAreExactlyAsBefore() {
        for (String v : new String[] {"10.2.9", "11.1.4-h7"}) {
            Run r = run("replace_12", v, deviceId -> {
                throw new AssertionError("below 12 the stored configuration is never consulted");
            });
            assertTrue(r.transport().sentCmds.contains(InventoryReadPlan.PAN_SHOW_INTERFACE_ALL), v);
            assertEquals(List.of(), r.run().notCollected(), v);
            Run before = run("below_12", v, PanConfiguredInterfaceSource.NONE);
            assertEquals(before.transport().sentCmds, r.transport().sentCmds, v);
            assertEquals(InventoryInterface.STATE_UP, context(r.run(), "1").interfaces().get(0).state(), v);
        }
        Run unknown = run("replace_12", "not-a-version", deviceId -> {
            throw new AssertionError("unknown version: neither the read nor its replacement");
        });
        assertFalse(unknown.transport().sentCmds.contains(InventoryReadPlan.PAN_SHOW_INTERFACE_ALL));
        assertEquals(List.of("pan_show_interface_all", "pan_os_version_unknown"), unknown.run().notCollected());
        // below_12 on 12+ is unchanged too: not collected, the configuration is not consulted.
        Run below = run("below_12", "12.1.2", deviceId -> {
            throw new AssertionError("below_12 never derives interfaces");
        });
        assertEquals(List.of("pan_show_interface_all", "pan_os_12_or_later"), below.run().notCollected());
    }
}
