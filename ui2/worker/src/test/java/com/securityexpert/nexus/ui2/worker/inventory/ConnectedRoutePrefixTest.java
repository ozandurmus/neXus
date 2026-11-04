package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;

/** V184 (path analysis A1, G5): a bare VS address takes a prefix only from exactly one containing connected route. */
class ConnectedRoutePrefixTest {

    private static ParsedInterface bare(String name, String address) {
        return new ParsedInterface(name, Optional.empty(), InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP,
                List.of(new ParsedAddress(address, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)),
                Optional.empty());
    }

    private static ParsedRoute connected(String destination, String dev) {
        return new ParsedRoute(destination, Optional.empty(), Optional.of(dev), InventoryRoute.PROTOCOL_CONNECTED,
                Optional.empty());
    }

    private static String address(List<ParsedInterface> result) {
        return result.get(0).addresses().get(0).address();
    }

    @Test
    void oneContainingConnectedRouteOnTheSameInterfaceGivesThePrefixAndKeepsTheRole() {
        List<ParsedInterface> result = InventoryCapabilityExecutor.applyUniqueConnectedRoutePrefixes(
                List.of(bare("eth1", "192.0.2.10")), List.of(connected("192.0.2.0/24", "eth1"),
                        connected("198.51.100.0/24", "eth1")));
        assertEquals("192.0.2.10/24", address(result));
        assertEquals(InventoryAddress.ROLE_MEMBER, result.get(0).addresses().get(0).role(), "the role is never changed");
    }

    @Test
    void twoDifferentContainingNetworksAreAmbiguousAndLeaveTheAddressBare() {
        assertEquals("192.0.2.10", address(InventoryCapabilityExecutor.applyUniqueConnectedRoutePrefixes(
                List.of(bare("eth1", "192.0.2.10")), List.of(connected("192.0.2.0/24", "eth1"),
                        connected("192.0.2.0/25", "eth1")))));
    }

    @Test
    void aRouteOnAnotherInterfaceOrANonConnectedRouteNeverGivesAPrefix() {
        assertEquals("192.0.2.10", address(InventoryCapabilityExecutor.applyUniqueConnectedRoutePrefixes(
                List.of(bare("eth1", "192.0.2.10")), List.of(connected("192.0.2.0/24", "eth2"),
                        new ParsedRoute("192.0.2.0/24", Optional.empty(), Optional.of("eth1"),
                                InventoryRoute.PROTOCOL_STATIC, Optional.empty())))));
    }

    @Test
    void anAddressThatAlreadyHasAPrefixIsUnchanged() {
        assertEquals("192.0.2.10/28", address(InventoryCapabilityExecutor.applyUniqueConnectedRoutePrefixes(
                List.of(bare("eth1", "192.0.2.10/28")), List.of(connected("192.0.2.0/24", "eth1")))));
    }
}
