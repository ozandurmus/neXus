package com.securityexpert.nexus.ui2.worker.inventory.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointClusterVirtualInterfaceParser.VirtualInterfaceAddress;

/**
 * AC-3: {@code cphaprob -a -m if} -- only the section after "Virtual
 * cluster interfaces: <n>" is read; a leading {@code vsid N:} header, a
 * row's {@code VMAC address:} trailer and an interleaved syslog/kernel
 * line are all tolerated (PR-3, PR-5).
 */
class CheckPointClusterVirtualInterfaceParserTest {

    @Test
    void readsOnlyTheVirtualClusterInterfacesSectionToleratingTheVsidHeaderAndAVmacTrailer() {
        List<VirtualInterfaceAddress> vips =
                CheckPointClusterVirtualInterfaceParser.parse(Fixtures.read("cp/cphaprob_a_m_if.txt"));

        assertEquals(List.of(
                new VirtualInterfaceAddress("Mgmt", "192.0.2.1"),
                new VirtualInterfaceAddress("Sync", "198.51.100.1")),
                vips, "the VMAC address trailer on the Sync row is not an IPv4 literal and is ignored");
    }

    @Test
    void ignoresBothVlanTableShapesAfterTheClusterInterfacesSection() {
        List<VirtualInterfaceAddress> vips = CheckPointClusterVirtualInterfaceParser
                .parse(Fixtures.read("cp/cphaprob_a_m_if_vsid_vlan_table.txt"));

        assertEquals(List.of(new VirtualInterfaceAddress("eth3-01", "203.0.113.65")), vips,
                "the 'Interface | Low VLAN | High VLAN' table after the section header is never entered");
    }

    @Test
    void returnsEmptyWhenNoSectionHeaderIsPresent() {
        assertEquals(List.of(), CheckPointClusterVirtualInterfaceParser.parse("no such section here\n"));
    }
}
