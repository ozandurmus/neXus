package com.securityexpert.nexus.ui2.worker.inventory.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointClusterVirtualInterfaceParser.VirtualInterfaceAddress;

/** AC-2: {@code cphaprob -a -m if} -- only the section after "Virtual cluster interfaces:" is read. */
class CheckPointClusterVirtualInterfaceParserTest {

    @Test
    void readsOnlyTheVirtualClusterInterfacesSection() {
        List<VirtualInterfaceAddress> vips =
                CheckPointClusterVirtualInterfaceParser.parse(Fixtures.read("cp/cphaprob_a_m_if.txt"));

        assertEquals(List.of(
                new VirtualInterfaceAddress("eth0", "192.0.2.1"),
                new VirtualInterfaceAddress("eth1", "198.51.100.1")),
                vips);
    }

    @Test
    void returnsEmptyWhenNoSectionHeaderIsPresent() {
        assertEquals(List.of(), CheckPointClusterVirtualInterfaceParser.parse("no such section here\n"));
    }
}
