package com.securityexpert.nexus.ui2.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Contract §5.1: HR-1, HR-2, HR-3 (AC-4). */
class HostResolutionTest {

    @Test
    void matchingAddressesArePhysicalDevice() {
        assertEquals(HostResolution.PHYSICAL_DEVICE,
                HostResolution.resolve(Address.of("198.51.100.1"), Address.of("198.51.100.1")));
    }

    @Test
    void whitespaceIsTrimmedBeforeComparison() {
        assertEquals(HostResolution.PHYSICAL_DEVICE,
                HostResolution.resolve(Address.of(" 198.51.100.1 "), Address.of("198.51.100.1")));
    }

    @Test
    void differingAddressesAreVirtualSystemHosted() {
        assertEquals(HostResolution.VIRTUAL_SYSTEM_HOSTED,
                HostResolution.resolve(Address.of("198.51.100.1"), Address.of("198.51.100.2")));
    }

    /** AC-4: an empty own address is HR-2, not a missing value to repair. */
    @Test
    void emptyOwnAddressIsVirtualSystemHostedNotMissing() {
        assertEquals(HostResolution.VIRTUAL_SYSTEM_HOSTED,
                HostResolution.resolve(Address.of("198.51.100.1"), Address.of("")));
    }

    @Test
    void absentOwnAddressIsVirtualSystemHosted() {
        assertEquals(HostResolution.VIRTUAL_SYSTEM_HOSTED,
                HostResolution.resolve(Address.of("198.51.100.1"), Address.absent()));
    }

    @Test
    void absentManagementAddressIsNotApplicable() {
        assertEquals(HostResolution.NOT_APPLICABLE,
                HostResolution.resolve(Address.absent(), Address.of("198.51.100.1")));
        assertEquals(HostResolution.NOT_APPLICABLE,
                HostResolution.resolve(Address.absent(), Address.absent()));
    }
}
