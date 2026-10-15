package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** V148 (PO 2026-10-15): {@code ip -o link show} link-layer addresses, synthetic output only. */
class CheckPointIpLinkParserTest {

    @Test
    void oneLineFormKeysEachEthernetAddressByItsOwnName() {
        String output = String.join("\n",
                "1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN mode DEFAULT \\    link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00",
                "2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP mode DEFAULT qlen 1000\\    link/ether 00:1C:7F:0A:0B:0C brd ff:ff:ff:ff:ff:ff",
                "3: eth1: <BROADCAST,MULTICAST,SLAVE,UP,LOWER_UP> mtu 1500 qdisc mq master bond0 state UP qlen 1000\\    link/ether 00:1c:7f:0a:0b:0d brd ff:ff:ff:ff:ff:ff",
                "7: bond0: <BROADCAST,MULTICAST,MASTER,UP,LOWER_UP> mtu 1500 qdisc noqueue state UP \\    link/ether 00:1c:7f:0a:0b:0d brd ff:ff:ff:ff:ff:ff",
                "8: bond0.100@bond0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc noqueue state UP \\    link/ether 00:1c:7f:0a:0b:0d brd ff:ff:ff:ff:ff:ff",
                "9: tun0: <POINTOPOINT,NOARP,UP> mtu 1500 qdisc noqueue state UNKNOWN \\    link/none ");
        Map<String, String> macs = CheckPointIpLinkParser.parse(output);

        assertEquals("00:1c:7f:0a:0b:0c", macs.get("eth0"), "lower-cased, exact name");
        assertEquals("00:1c:7f:0a:0b:0d", macs.get("eth1"));
        assertEquals("00:1c:7f:0a:0b:0d", macs.get("bond0"));
        assertEquals("00:1c:7f:0a:0b:0d", macs.get("bond0.100"), "a VLAN is keyed by its own name, not name@parent");
        assertFalse(macs.containsKey("lo"), "loopback has no ethernet address");
        assertFalse(macs.containsKey("tun0"), "link/none is absent, never guessed");
        assertEquals(4, macs.size());
    }

    @Test
    void multiLineFormAndEmptyOutputAreHandled() {
        String output = String.join("\n",
                "2: eth2: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP mode DEFAULT qlen 1000",
                "    link/ether 00:50:56:aa:bb:cc brd ff:ff:ff:ff:ff:ff",
                "3: eth3: <BROADCAST,MULTICAST> mtu 1500 qdisc noop state DOWN mode DEFAULT qlen 1000",
                "    link/ether 00:00:00:00:00:00 brd ff:ff:ff:ff:ff:ff");
        Map<String, String> macs = CheckPointIpLinkParser.parse(output);
        assertEquals(Map.of("eth2", "00:50:56:aa:bb:cc"), macs, "the all-zero address is not recorded");
        assertTrue(CheckPointIpLinkParser.parse("").isEmpty());
        assertTrue(CheckPointIpLinkParser.parse(null).isEmpty());
    }
}
